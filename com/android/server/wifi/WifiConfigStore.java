/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.wifi;

import static java.lang.Math.toIntExact;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.AlarmManager;
import android.content.Context;
import android.net.wifi.WifiMigration;
import android.os.Handler;
import android.os.UserHandle;
import android.util.AtomicFile;
import android.util.Log;
import android.util.SparseArray;
import android.util.Xml;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.FastXmlSerializer;
import com.android.internal.util.Preconditions;
import com.android.server.wifi.util.EncryptedData;
import com.android.server.wifi.util.Environment;
import com.android.server.wifi.util.FileUtils;
import com.android.server.wifi.util.WifiConfigStoreEncryptionUtil;
import com.android.server.wifi.util.XmlUtil;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * This class provides a mechanism to save data to persistent store files {@link StoreFile}.
 * Modules can register a {@link StoreData} instance indicating the {@StoreFile} into which they
 * want to save their data to.
 *
 * NOTE:
 * <li>Modules can register their {@StoreData} using
 * {@link WifiConfigStore#registerStoreData(StoreData)} directly, but should
 * use {@link WifiConfigManager#saveToStore(boolean)} for any writes.</li>
 * <li>{@link WifiConfigManager} controls {@link WifiConfigStore} and initiates read at bootup and
 * store file changes on user switch.</li>
 * <li>Not thread safe!</li>
 */
public class WifiConfigStore {
    /**
     * Config store file for general shared store file.
     */
    public static final int STORE_FILE_SHARED_GENERAL = 0;
    /**
     * Config store file for softap shared store file.
     */
    public static final int STORE_FILE_SHARED_SOFTAP = 1;
    /**
     * Config store file for general user store file.
     */
    public static final int STORE_FILE_USER_GENERAL = 2;
    /**
     * Config store file for network suggestions user store file.
     */
    public static final int STORE_FILE_USER_NETWORK_SUGGESTIONS = 3;

    @IntDef(prefix = { "STORE_FILE_" }, value = {
            STORE_FILE_SHARED_GENERAL,
            STORE_FILE_SHARED_SOFTAP,
            STORE_FILE_USER_GENERAL,
            STORE_FILE_USER_NETWORK_SUGGESTIONS
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface StoreFileId { }

    private static final String XML_TAG_DOCUMENT_HEADER = "WifiConfigStoreData";
    private static final String XML_TAG_VERSION = "Version";
    private static final String XML_TAG_HEADER_INTEGRITY = "Integrity";
    /**
     * Current config store data version. This will be incremented for any additions.
     */
    private static final int CURRENT_CONFIG_STORE_DATA_VERSION = 3;
    /** This list of older versions will be used to restore data from older config store. */
    /**
     * First version of the config store data format.
     */
    public static final int INITIAL_CONFIG_STORE_DATA_VERSION = 1;
    /**
     * Second version of the config store data format, introduced:
     *  - Integrity info.
     */
    public static final int INTEGRITY_CONFIG_STORE_DATA_VERSION = 2;
    /**
     * Third version of the config store data format,
     * introduced:
     *  - Encryption of credentials
     * removed:
     *  - Integrity info.
     */
    public static final int ENCRYPT_CREDENTIALS_CONFIG_STORE_DATA_VERSION = 3;

    @IntDef(suffix = { "_VERSION" }, value = {
            INITIAL_CONFIG_STORE_DATA_VERSION,
            INTEGRITY_CONFIG_STORE_DATA_VERSION,
            ENCRYPT_CREDENTIALS_CONFIG_STORE_DATA_VERSION
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Version { }

    /**
     * Alarm tag to use for starting alarms for buffering file writes.
     */
    @VisibleForTesting
    public static final String BUFFERED_WRITE_ALARM_TAG = "WriteBufferAlarm";
    /**
     * Log tag.
     */
    private static final String TAG = "WifiConfigStore";
    /**
     * Time interval for buffering file writes for non-forced writes
     */
    private static final int BUFFERED_WRITE_ALARM_INTERVAL_MS = 10 * 1000;
    /**
     * Config store file name for general shared store file.
     */
    private static final String STORE_FILE_NAME_SHARED_GENERAL = "WifiConfigStore.xml";
    /**
     * Config store file name for SoftAp shared store file.
     */
    private static final String STORE_FILE_NAME_SHARED_SOFTAP = "WifiConfigStoreSoftAp.xml";
    /**
     * Config store file name for general user store file.
     */
    private static final String STORE_FILE_NAME_USER_GENERAL = "WifiConfigStore.xml";
    /**
     * Config store file name for network suggestions user store file.
     */
    private static final String STORE_FILE_NAME_USER_NETWORK_SUGGESTIONS =
            "WifiConfigStoreNetworkSuggestions.xml";
    /**
     * Mapping of Store file Id to Store file names.
     */
    private static final SparseArray<String> STORE_ID_TO_FILE_NAME =
            new SparseArray<String>() {{
                put(STORE_FILE_SHARED_GENERAL, STORE_FILE_NAME_SHARED_GENERAL);
                put(STORE_FILE_SHARED_SOFTAP, STORE_FILE_NAME_SHARED_SOFTAP);
                put(STORE_FILE_USER_GENERAL, STORE_FILE_NAME_USER_GENERAL);
                put(STORE_FILE_USER_NETWORK_SUGGESTIONS, STORE_FILE_NAME_USER_NETWORK_SUGGESTIONS);
            }};
    /**
     * Handler instance to post alarm timeouts to
     */
    private final Handler mEventHandler;
    /**
     * Alarm manager instance to start buffer timeout alarms.
     */
    private final AlarmManager mAlarmManager;
    /**
     * Clock instance to retrieve timestamps for alarms.
     */
    private final Clock mClock;
    private final WifiMetrics mWifiMetrics;
    /**
     * Shared config store file instance. There are 2 shared store files:
     * {@link #STORE_FILE_NAME_SHARED_GENERAL} & {@link #STORE_FILE_NAME_SHARED_SOFTAP}.
     */
    private final List<StoreFile> mSharedStores;
    /**
     * User specific store file instances. There are 2 user store files:
     * {@link #STORE_FILE_NAME_USER_GENERAL} & {@link #STORE_FILE_NAME_USER_NETWORK_SUGGESTIONS}.
     */
    private List<StoreFile> mUserStores;
    /**
     * Verbose logging flag.
     */
    private boolean mVerboseLoggingEnabled = false;
    /**
     * Flag to indicate if there is a buffered write pending.
     */
    private boolean mBufferedWritePending = false;
    /**
     * Alarm listener for flushing out any buffered writes.
     */
    private final AlarmManager.OnAlarmListener mBufferedWriteListener =
            new AlarmManager.OnAlarmListener() {
                public void onAlarm() {
                    try {
                        writeBufferedData();
                    } catch (IOException e) {
                        Log.wtf(TAG, "Buffered write failed", e);
                    }
                }
            };

    /**
     * List of data containers.
     */
    private final List<StoreData> mStoreDataList;

    /**
     * Create a new instance of WifiConfigStore.
     * Note: The store file instances have been made inputs to this class to ease unit-testing.
     *
     * @param context     context to use for retrieving the alarm manager.
     * @param handler     handler instance to post alarm timeouts to.
     * @param clock       clock instance to retrieve timestamps for alarms.
     * @param wifiMetrics Metrics instance.
     * @param sharedStores List of {@link StoreFile} instances pointing to the shared store files.
     *                     This should be retrieved using {@link #createSharedFiles(boolean)}
     *                     method.
     */
    public WifiConfigStore(Context context, Handler handler, Clock clock, WifiMetrics wifiMetrics,
            List<StoreFile> sharedStores) {

        mAlarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        mEventHandler = handler;
        mClock = clock;
        mWifiMetrics = wifiMetrics;
        mStoreDataList = new ArrayList<>();

        // Initialize the store files.
        mSharedStores = sharedStores;
        // The user store is initialized to null, this will be set when the user unlocks and
        // CE storage is accessible via |switchUserStoresAndRead|.
        mUserStores = null;
    }

    /**
     * Set the user store files.
     * (Useful for mocking in unit tests).
     * @param userStores List of {@link StoreFile} created using
     * {@link #createUserFiles(int, boolean)}.
     */
    public void setUserStores(@NonNull List<StoreFile> userStores) {
        Preconditions.checkNotNull(userStores);
        mUserStores = userStores;
    }

    /**
     * Register a {@link StoreData} to read/write data from/to a store. A {@link StoreData} is
     * responsible for a block of data in the store file, and provides serialization/deserialization
     * functions for those data.
     *
     * @param storeData The store data to be registered to the config store
     * @return true if registered successfully, false if the store file name is not valid.
     */
    public boolean registerStoreData(@NonNull StoreData storeData) {
        if (storeData == null) {
            Log.e(TAG, "Unable to register null store data");
            return false;
        }
        int storeFileId = storeData.getStoreFileId();
        if (STORE_ID_TO_FILE_NAME.get(storeFileId) == null) {
            Log.e(TAG, "Invalid shared store file specified" + storeFileId);
            return false;
        }
        mStoreDataList.add(storeData);
        return true;
    }

    /**
     * Helper method to create a store file instance for either the shared store or user store.
     * Note: The method creates the store directory if not already present. This may be needed for
     * user store files.
     *
     * @param storeDir Base directory under which the store file is to be stored. The store file
     *                 will be at <storeDir>/WifiConfigStore.xml.
     * @param fileId Identifier for the file. See {@link StoreFileId}.
     * @param userHandle User handle. Meaningful only for user specific store files.
     * @param shouldEncryptCredentials Whether to encrypt credentials or not.
     * @return new instance of the store file or null if the directory cannot be created.
     */
    private static @Nullable StoreFile createFile(@NonNull File storeDir,
            @StoreFileId int fileId, UserHandle userHandle, boolean shouldEncryptCredentials) {
        if (!storeDir.exists()) {
            if (!storeDir.mkdir()) {
                Log.w(TAG, "Could not create store directory " + storeDir);
                return null;
            }
        }
        File file = new File(storeDir, STORE_ID_TO_FILE_NAME.get(fileId));
        WifiConfigStoreEncryptionUtil encryptionUtil = null;
        if (shouldEncryptCredentials) {
            encryptionUtil = new WifiConfigStoreEncryptionUtil(file.getName());
        }
        return new StoreFile(file, fileId, userHandle, encryptionUtil);
    }

    private static @Nullable List<StoreFile> createFiles(File storeDir, List<Integer> storeFileIds,
            UserHandle userHandle, boolean shouldEncryptCredentials) {
        List<StoreFile> storeFiles = new ArrayList<>();
        for (int fileId : storeFileIds) {
            StoreFile storeFile =
                    createFile(storeDir, fileId, userHandle, shouldEncryptCredentials);
            if (storeFile == null) {
                return null;
            }
            storeFiles.add(storeFile);
        }
        return storeFiles;
    }

    /**
     * Create a new instance of the shared store file.
     *
     * @param shouldEncryptCredentials Whether to encrypt credentials or not.
     * @return new instance of the store file or null if the directory cannot be created.
     */
    public static @NonNull List<StoreFile> createSharedFiles(boolean shouldEncryptCredentials) {
        return createFiles(
                Environment.getWifiSharedDirectory(),
                Arrays.asList(STORE_FILE_SHARED_GENERAL, STORE_FILE_SHARED_SOFTAP),
                UserHandle.ALL,
                shouldEncryptCredentials);
    }

    /**
     * Create new instances of the user specific store files.
     * The user store file is inside the user's encrypted data directory.
     *
     * @param userId userId corresponding to the currently logged-in user.
     * @param shouldEncryptCredentials Whether to encrypt credentials or not.
     * @return List of new instances of the store files created or null if the directory cannot be
     * created.
     */
    public static @Nullable List<StoreFile> createUserFiles(int userId,
            boolean shouldEncryptCredentials) {
        UserHandle userHandle = UserHandle.of(userId);
        return createFiles(
                Environment.getWifiUserDirectory(userId),
                Arrays.asList(STORE_FILE_USER_GENERAL, STORE_FILE_USER_NETWORK_SUGGESTIONS),
                userHandle,
                shouldEncryptCredentials);
    }

    /**
     * Enable verbose logging.
     */
    public void enableVerboseLogging(boolean verbose) {
        mVerboseLoggingEnabled = verbose;
    }

    /**
     * Retrieve the list of {@link StoreData} instances registered for the provided
     * {@link StoreFile}.
     */
    private List<StoreData> retrieveStoreDataListForStoreFile(@NonNull StoreFile storeFile) {
        return mStoreDataList
                .stream()
                .filter(s -> s.getStoreFileId() == storeFile.getFileId())
                .collect(Collectors.toList());
    }

    /**
     * Check if any of the provided list of {@link StoreData} instances registered
     * for the provided {@link StoreFile }have indicated that they have new data to serialize.
     */
    private boolean hasNewDataToSerialize(@NonNull StoreFile storeFile) {
        List<StoreData> storeDataList = retrieveStoreDataListForStoreFile(storeFile);
        return storeDataList.stream().anyMatch(s -> s.hasNewDataToSerialize());
    }

    /**
     * API to write the data provided by registered store data to config stores.
     * The method writes the user specific configurations to user specific config store and the
     * shared configurations to shared config store.
     *
     * @param forceSync boolean to force write the config stores now. if false, the writes are
     *                  buffered and written after the configured interval.
     */
    public void write(boolean forceSync)
            throws XmlPullParserException, IOException {
        boolean hasAnyNewData = false;
        // Serialize the provided data and send it to the respective stores. The actual write will
        // be performed later depending on the |forceSync| flag .
        for (StoreFile sharedStoreFile : mSharedStores) {
            if (hasNewDataToSerialize(sharedStoreFile)) {
                byte[] sharedDataBytes = serializeData(sharedStoreFile);
                sharedStoreFile.storeRawDataToWrite(sharedDataBytes);
                hasAnyNewData = true;
            }
        }
        if (mUserStores != null) {
            for (StoreFile userStoreFile : mUserStores) {
                if (hasNewDataToSerialize(userStoreFile)) {
                    byte[] userDataBytes = serializeData(userStoreFile);
                    userStoreFile.storeRawDataToWrite(userDataBytes);
                    hasAnyNewData = true;
                }
            }
        }

        if (hasAnyNewData) {
            // Every write provides a new snapshot to be persisted, so |forceSync| flag overrides
            // any pending buffer writes.
            if (forceSync) {
                writeBufferedData();
            } else {
                startBufferedWriteAlarm();
            }
        } else if (forceSync && mBufferedWritePending) {
            // no new data to write, but there is a pending buffered write. So, |forceSync| should
            // flush that out.
            writeBufferedData();
        }
    }

    /**
     * Serialize all the data from all the {@link StoreData} clients registered for the provided
     * {@link StoreFile}.
     *
     * This method also computes the integrity of the data being written and serializes the computed
     * {@link EncryptedData} to the output.
     *
     * @param storeFile StoreFile that we want to write to.
     * @return byte[] of serialized bytes
     * @throws XmlPullParserException
     * @throws IOException
     */
    private byte[] serializeData(@NonNull StoreFile storeFile)
            throws XmlPullParserException, IOException {
        List<StoreData> storeDataList = retrieveStoreDataListForStoreFile(storeFile);

        final XmlSerializer out = new FastXmlSerializer();
        final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        out.setOutput(outputStream, StandardCharsets.UTF_8.name());

        // First XML header.
        XmlUtil.writeDocumentStart(out, XML_TAG_DOCUMENT_HEADER);
        // Next version.
        XmlUtil.writeNextValue(out, XML_TAG_VERSION, CURRENT_CONFIG_STORE_DATA_VERSION);
        for (StoreData storeData : storeDataList) {
            String tag = storeData.getName();
            XmlUtil.writeNextSectionStart(out, tag);
            storeData.serializeData(out, storeFile.getEncryptionUtil());
            XmlUtil.writeNextSectionEnd(out, tag);
        }
        XmlUtil.writeDocumentEnd(out, XML_TAG_DOCUMENT_HEADER);
        return outputStream.toByteArray();
    }

    /**
     * Helper method to start a buffered write alarm if one doesn't already exist.
     */
    private void startBufferedWriteAlarm() {
        if (!mBufferedWritePending) {
            mAlarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    mClock.getElapsedSinceBootMillis() + BUFFERED_WRITE_ALARM_INTERVAL_MS,
                    BUFFERED_WRITE_ALARM_TAG, mBufferedWriteListener, mEventHandler);
            mBufferedWritePending = true;
        }
    }

    /**
     * Helper method to stop a buffered write alarm if one exists.
     */
    private void stopBufferedWriteAlarm() {
        if (mBufferedWritePending) {
            mAlarmManager.cancel(mBufferedWriteListener);
            mBufferedWritePending = false;
        }
    }

    /**
     * Helper method to actually perform the writes to the file. This flushes out any write data
     * being buffered in the respective stores and cancels any pending buffer write alarms.
     */
    private void writeBufferedData() throws IOException {
        stopBufferedWriteAlarm();

        long writeStartTime = mClock.getElapsedSinceBootMillis();
        for (StoreFile sharedStoreFile : mSharedStores) {
            sharedStoreFile.writeBufferedRawData();
        }
        if (mUserStores != null) {
            for (StoreFile userStoreFile : mUserStores) {
                userStoreFile.writeBufferedRawData();
            }
        }
        long writeTime = mClock.getElapsedSinceBootMillis() - writeStartTime;
        try {
            mWifiMetrics.noteWifiConfigStoreWriteDuration(toIntExact(writeTime));
        } catch (ArithmeticException e) {
            // Silently ignore on any overflow errors.
        }
        Log.d(TAG, "Writing to stores completed in " + writeTime + " ms.");
    }

    /**
     * Note: This is a copy of {@link AtomicFile#readFully()} modified to use the passed in
     * {@link InputStream} which was returned using {@link AtomicFile#openRead()}.
     */
    private static byte[] readAtomicFileFully(InputStream stream) throws IOException {
        try {
            int pos = 0;
            int avail = stream.available();
            byte[] data = new byte[avail];
            while (true) {
                int amt = stream.read(data, pos, data.length - pos);
                if (amt <= 0) {
                    return data;
                }
                pos += amt;
                avail = stream.available();
                if (avail > data.length - pos) {
                    byte[] newData = new byte[pos + avail];
                    System.arraycopy(data, 0, newData, 0, pos);
                    data = newData;
                }
            }
        } finally {
            stream.close();
        }
    }

    /**
     * Conversion for file id's to use in WifiMigration API surface.
     */
    private static Integer getMigrationStoreFileId(@StoreFileId int fileId) {
        switch (fileId) {
            case STORE_FILE_SHARED_GENERAL:
                return WifiMigration.STORE_FILE_SHARED_GENERAL;
            case STORE_FILE_SHARED_SOFTAP:
                return WifiMigration.STORE_FILE_SHARED_SOFTAP;
            case STORE_FILE_USER_GENERAL:
                return WifiMigration.STORE_FILE_USER_GENERAL;
            case STORE_FILE_USER_NETWORK_SUGGESTIONS:
                return WifiMigration.STORE_FILE_USER_NETWORK_SUGGESTIONS;
            default:
                return null;
        }
    }

    private static byte[] readDataFromMigrationSharedStoreFile(@StoreFileId int fileId)
            throws IOException {
        Integer migrationStoreFileId = getMigrationStoreFileId(fileId);
        if (migrationStoreFileId == null) return null;
        InputStream migrationIs =
                WifiMigration.convertAndRetrieveSharedConfigStoreFile(migrationStoreFileId);
        if (migrationIs == null) return null;
        return readAtomicFileFully(migrationIs);
    }

    private static byte[] readDataFromMigrationUserStoreFile(@StoreFileId int fileId,
            UserHandle userHandle) throws IOException {
        Integer migrationStoreFileId = getMigrationStoreFileId(fileId);
        if (migrationStoreFileId == null) return null;
        InputStream migrationIs =
                WifiMigration.convertAndRetrieveUserConfigStoreFile(
                        migrationStoreFileId, userHandle);
        if (migrationIs == null) return null;
        return readAtomicFileFully(migrationIs);
    }

    /**
     * Helper method to read from the shared store files.
     * @throws XmlPullParserException
     * @throws IOException
     */
    private void readFromSharedStoreFiles() throws XmlPullParserException, IOException {
        for (StoreFile sharedStoreFile : mSharedStores) {
            byte[] sharedDataBytes =
                    readDataFromMigrationSharedStoreFile(sharedStoreFile.getFileId());
            if (sharedDataBytes == null) {
                // nothing to migrate, do normal read.
                sharedDataBytes = sharedStoreFile.readRawData();
            } else {
                Log.i(TAG, "Read data out of shared migration store file: "
                        + sharedStoreFile.getName());
                // Save the migrated file contents to the regular store file and delete the
                // migrated stored file.
                sharedStoreFile.storeRawDataToWrite(sharedDataBytes);
                sharedStoreFile.writeBufferedRawData();
                // Note: If the migrated store file is at the same location as the store file,
                // then the OEM implementation should ignore this remove.
                WifiMigration.removeSharedConfigStoreFile(
                        getMigrationStoreFileId(sharedStoreFile.getFileId()));
            }
            deserializeData(sharedDataBytes, sharedStoreFile);
        }
    }

    /**
     * Helper method to read from the user store files.
     * @throws XmlPullParserException
     * @throws IOException
     */
    private void readFromUserStoreFiles() throws XmlPullParserException, IOException {
        for (StoreFile userStoreFile : mUserStores) {
            byte[] userDataBytes = readDataFromMigrationUserStoreFile(
                    userStoreFile.getFileId(), userStoreFile.mUserHandle);
            if (userDataBytes == null) {
                // nothing to migrate, do normal read.
                userDataBytes = userStoreFile.readRawData();
            } else {
                Log.i(TAG, "Read data out of user migration store file: "
                        + userStoreFile.getName());
                // Save the migrated file contents to the regular store file and delete the
                // migrated stored file.
                userStoreFile.storeRawDataToWrite(userDataBytes);
                userStoreFile.writeBufferedRawData();
                // Note: If the migrated store file is at the same location as the store file,
                // then the OEM implementation should ignore this remove.
                WifiMigration.removeUserConfigStoreFile(
                        getMigrationStoreFileId(userStoreFile.getFileId()),
                        userStoreFile.mUserHandle);
            }
            deserializeData(userDataBytes, userStoreFile);
        }
    }

    /**
     * API to read the store data from the config stores.
     * The method reads the user specific configurations from user specific config store and the
     * shared configurations from the shared config store.
     */
    public void read() throws XmlPullParserException, IOException {
        // Reset both share and user store data.
        for (StoreFile sharedStoreFile : mSharedStores) {
            resetStoreData(sharedStoreFile);
        }
        if (mUserStores != null) {
            for (StoreFile userStoreFile : mUserStores) {
                resetStoreData(userStoreFile);
            }
        }
        long readStartTime = mClock.getElapsedSinceBootMillis();
        readFromSharedStoreFiles();
        if (mUserStores != null) {
            readFromUserStoreFiles();
        }
        long readTime = mClock.getElapsedSinceBootMillis() - readStartTime;
        try {
            mWifiMetrics.noteWifiConfigStoreReadDuration(toIntExact(readTime));
        } catch (ArithmeticException e) {
            // Silently ignore on any overflow errors.
        }
        Log.d(TAG, "Reading from all stores completed in " + readTime + " ms.");
    }

    /**
     * Handles a user switch. This method changes the user specific store files and reads from the
     * new user's store files.
     *
     * @param userStores List of {@link StoreFile} created using {@link #createUserFiles(int)}.
     */
    public void switchUserStoresAndRead(@NonNull List<StoreFile> userStores)
            throws XmlPullParserException, IOException {
        Preconditions.checkNotNull(userStores);
        // Reset user store data.
        if (mUserStores != null) {
            for (StoreFile userStoreFile : mUserStores) {
                resetStoreData(userStoreFile);
            }
        }

        // Stop any pending buffered writes, if any.
        stopBufferedWriteAlarm();
        mUserStores = userStores;

        // Now read from the user store files.
        long readStartTime = mClock.getElapsedSinceBootMillis();
        readFromUserStoreFiles();
        long readTime = mClock.getElapsedSinceBootMillis() - readStartTime;
        mWifiMetrics.noteWifiConfigStoreReadDuration(toIntExact(readTime));
        Log.d(TAG, "Reading from user stores completed in " + readTime + " ms.");
    }

    /**
     * Reset data for all {@link StoreData} instances registered for this {@link StoreFile}.
     */
    private void resetStoreData(@NonNull StoreFile storeFile) {
        for (StoreData storeData: retrieveStoreDataListForStoreFile(storeFile)) {
            storeData.resetData();
        }
    }

    // Inform all the provided store data clients that there is nothing in the store for them.
    private void indicateNoDataForStoreDatas(Collection<StoreData> storeDataSet,
            @Version int version, @NonNull WifiConfigStoreEncryptionUtil encryptionUtil)
            throws XmlPullParserException, IOException {
        for (StoreData storeData : storeDataSet) {
            storeData.deserializeData(null, 0, version, encryptionUtil);
        }
    }

    /**
     * Deserialize data from a {@link StoreFile} for all {@link StoreData} instances registered.
     *
     * This method also computes the integrity of the incoming |dataBytes| and compare with
     * {@link EncryptedData} parsed from |dataBytes|. If the integrity check fails, the data
     * is discarded.
     *
     * @param dataBytes The data to parse
     * @param storeFile StoreFile that we read from. Will be used to retrieve the list of clients
     *                  who have data to deserialize from this file.
     *
     * @throws XmlPullParserException
     * @throws IOException
     */
    private void deserializeData(@NonNull byte[] dataBytes, @NonNull StoreFile storeFile)
            throws XmlPullParserException, IOException {
        List<StoreData> storeDataList = retrieveStoreDataListForStoreFile(storeFile);
        if (dataBytes == null) {
            indicateNoDataForStoreDatas(storeDataList, -1 /* unknown */,
                    storeFile.getEncryptionUtil());
            return;
        }
        final XmlPullParser in = Xml.newPullParser();
        final ByteArrayInputStream inputStream = new ByteArrayInputStream(dataBytes);
        in.setInput(inputStream, StandardCharsets.UTF_8.name());

        // Start parsing the XML stream.
        int rootTagDepth = in.getDepth() + 1;
        XmlUtil.gotoDocumentStart(in, XML_TAG_DOCUMENT_HEADER);

        @Version int version = parseVersionFromXml(in);
        // Version 2 contains the now unused integrity data, parse & then discard the information.
        if (version == INTEGRITY_CONFIG_STORE_DATA_VERSION) {
            parseAndDiscardIntegrityDataFromXml(in, rootTagDepth);
        }

        String[] headerName = new String[1];
        Set<StoreData> storeDatasInvoked = new HashSet<>();
        while (XmlUtil.gotoNextSectionOrEnd(in, headerName, rootTagDepth)) {
            // There can only be 1 store data matching the tag, O indicates a previous StoreData
            // module that no longer exists (ignore this XML section).
            StoreData storeData = storeDataList.stream()
                    .filter(s -> s.getName().equals(headerName[0]))
                    .findAny()
                    .orElse(null);
            if (storeData == null) {
                Log.e(TAG, "Unknown store data: " + headerName[0] + ". List of store data: "
                        + storeDataList);
                continue;
            }
            storeData.deserializeData(in, rootTagDepth + 1, version,
                    storeFile.getEncryptionUtil());
            storeDatasInvoked.add(storeData);
        }
        // Inform all the other registered store data clients that there is nothing in the store
        // for them.
        Set<StoreData> storeDatasNotInvoked = new HashSet<>(storeDataList);
        storeDatasNotInvoked.removeAll(storeDatasInvoked);
        indicateNoDataForStoreDatas(storeDatasNotInvoked, version, storeFile.getEncryptionUtil());
    }

    /**
     * Parse the version from the XML stream.
     * This is used for both the shared and user config store data.
     *
     * @param in XmlPullParser instance pointing to the XML stream.
     * @return version number retrieved from the Xml stream.
     */
    private static @Version int parseVersionFromXml(XmlPullParser in)
            throws XmlPullParserException, IOException {
        int version = (int) XmlUtil.readNextValueWithName(in, XML_TAG_VERSION);
        if (version < INITIAL_CONFIG_STORE_DATA_VERSION
                || version > CURRENT_CONFIG_STORE_DATA_VERSION) {
            throw new XmlPullParserException("Invalid version of data: " + version);
        }
        return version;
    }

    /**
     * Parse the integrity data structure from the XML stream and discard it.
     *
     * @param in XmlPullParser instance pointing to the XML stream.
     * @param outerTagDepth Outer tag depth.
     */
    private static void parseAndDiscardIntegrityDataFromXml(XmlPullParser in, int outerTagDepth)
            throws XmlPullParserException, IOException {
        XmlUtil.gotoNextSectionWithName(in, XML_TAG_HEADER_INTEGRITY, outerTagDepth);
        XmlUtil.EncryptedDataXmlUtil.parseFromXml(in, outerTagDepth + 1);
    }

    /**
     * Dump the local log buffer and other internal state of WifiConfigManager.
     */
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("Dump of WifiConfigStore");
        pw.println("WifiConfigStore - Store File Begin ----");
        Stream.of(mSharedStores, mUserStores)
                .flatMap(List::stream)
                .forEach((storeFile) -> {
                    pw.print("Name: " + storeFile.mFileName);
                    pw.print(", File Id: " + storeFile.mFileId);
                    pw.println(", Credentials encrypted: "
                            + (storeFile.getEncryptionUtil() != null));
                });
        pw.println("WifiConfigStore - Store Data Begin ----");
        for (StoreData storeData : mStoreDataList) {
            pw.print("StoreData =>");
            pw.print(" ");
            pw.print("Name: " + storeData.getName());
            pw.print(", ");
            pw.print("File Id: " + storeData.getStoreFileId());
            pw.print(", ");
            pw.println("File Name: " + STORE_ID_TO_FILE_NAME.get(storeData.getStoreFileId()));
        }
        pw.println("WifiConfigStore - Store Data End ----");
    }

    /**
     * Class to encapsulate all file writes. This is a wrapper over {@link AtomicFile} to write/read
     * raw data from the persistent file with integrity. This class provides helper methods to
     * read/write the entire file into a byte array.
     * This helps to separate out the processing, parsing, and integrity checking from the actual
     * file writing.
     */
    public static class StoreFile {
        /**
         * File permissions to lock down the file.
         */
        private static final int FILE_MODE = 0600;
        /**
         * The store file to be written to.
         */
        private final AtomicFile mAtomicFile;
        /**
         * This is an intermediate buffer to store the data to be written.
         */
        private byte[] mWriteData;
        /**
         * Store the file name for setting the file permissions/logging purposes.
         */
        private final String mFileName;
        /**
         * {@link StoreFileId} Type of store file.
         */
        private final @StoreFileId int mFileId;
        /**
         * User handle. Meaningful only for user specific store files.
         */
        private final UserHandle mUserHandle;
        /**
         * Integrity checking for the store file.
         */
        private final WifiConfigStoreEncryptionUtil mEncryptionUtil;

        public StoreFile(File file, @StoreFileId int fileId,
                @NonNull UserHandle userHandle,
                @Nullable WifiConfigStoreEncryptionUtil encryptionUtil) {
            mAtomicFile = new AtomicFile(file);
            mFileName = file.getAbsolutePath();
            mFileId = fileId;
            mUserHandle = userHandle;
            mEncryptionUtil = encryptionUtil;
        }

        public String getName() {
            return mAtomicFile.getBaseFile().getName();
        }

        public @StoreFileId int getFileId() {
            return mFileId;
        }

        /**
         * @return Returns the encryption util used for this store file.
         */
        public @Nullable WifiConfigStoreEncryptionUtil getEncryptionUtil() {
            return mEncryptionUtil;
        }

        /**
         * Read the entire raw data from the store file and return in a byte array.
         *
         * @return raw data read from the file or null if the file is not found or the data has
         *  been altered.
         * @throws IOException if an error occurs. The input stream is always closed by the method
         * even when an exception is encountered.
         */
        public byte[] readRawData() throws IOException {
            byte[] bytes = null;
            try {
                bytes = mAtomicFile.readFully();
            } catch (FileNotFoundException e) {
                return null;
            }
            return bytes;
        }

        /**
         * Store the provided byte array to be written when {@link #writeBufferedRawData()} method
         * is invoked.
         * This intermediate step is needed to help in buffering file writes.
         *
         * @param data raw data to be written to the file.
         */
        public void storeRawDataToWrite(byte[] data) {
            mWriteData = data;
        }

        /**
         * Write the stored raw data to the store file.
         * After the write to file, the mWriteData member is reset.
         * @throws IOException if an error occurs. The output stream is always closed by the method
         * even when an exception is encountered.
         */
        public void writeBufferedRawData() throws IOException {
            if (mWriteData == null) return; // No data to write for this file.
            // Write the data to the atomic file.
            FileOutputStream out = null;
            try {
                out = mAtomicFile.startWrite();
                FileUtils.chmod(mFileName, FILE_MODE);
                out.write(mWriteData);
                mAtomicFile.finishWrite(out);
            } catch (IOException e) {
                if (out != null) {
                    mAtomicFile.failWrite(out);
                }
                throw e;
            }
            // Reset the pending write data after write.
            mWriteData = null;
        }
    }

    /**
     * Interface to be implemented by a module that contained data in the config store file.
     *
     * The module will be responsible for serializing/deserializing their own data.
     * Whenever {@link WifiConfigStore#read()} is invoked, all registered StoreData instances will
     * be notified that a read was performed via {@link StoreData#deserializeData(
     * XmlPullParser, int)} regardless of whether there is any data for them or not in the
     * store file.
     *
     * Note: StoreData clients that need a config store read to kick-off operations should wait
     * for the {@link StoreData#deserializeData(XmlPullParser, int)} invocation.
     */
    public interface StoreData {
        /**
         * Serialize a XML data block to the output stream.
         *
         * @param out The output stream to serialize the data to
         * @param encryptionUtil Utility to help encrypt any credential data.
         */
        void serializeData(XmlSerializer out,
                @Nullable WifiConfigStoreEncryptionUtil encryptionUtil)
                throws XmlPullParserException, IOException;

        /**
         * Deserialize a XML data block from the input stream.
         *
         * @param in The input stream to read the data from. This could be null if there is
         *           nothing in the store.
         * @param outerTagDepth The depth of the outer tag in the XML document
         * @param version Version of config store file.
         * @param encryptionUtil Utility to help decrypt any credential data.
         *
         * Note: This will be invoked every time a store file is read, even if there is nothing
         *                      in the store for them.
         */
        void deserializeData(@Nullable XmlPullParser in, int outerTagDepth, @Version int version,
                @Nullable WifiConfigStoreEncryptionUtil encryptionUtil)
                throws XmlPullParserException, IOException;

        /**
         * Reset configuration data.
         */
        void resetData();

        /**
         * Check if there is any new data to persist from the last write.
         *
         * @return true if the module has new data to persist, false otherwise.
         */
        boolean hasNewDataToSerialize();

        /**
         * Return the name of this store data.  The data will be enclosed under this tag in
         * the XML block.
         *
         * @return The name of the store data
         */
        String getName();

        /**
         * File Id where this data needs to be written to.
         * This should be one of {@link #STORE_FILE_SHARED_GENERAL},
         * {@link #STORE_FILE_USER_GENERAL} or
         * {@link #STORE_FILE_USER_NETWORK_SUGGESTIONS}.
         *
         * Note: For most uses, the shared or user general store is sufficient. Creating and
         * managing store files are expensive. Only use specific store files if you have a large
         * amount of data which may not need to be persisted frequently (or at least not as
         * frequently as the general store).
         * @return Id of the file where this data needs to be persisted.
         */
        @StoreFileId int getStoreFileId();
    }
}
