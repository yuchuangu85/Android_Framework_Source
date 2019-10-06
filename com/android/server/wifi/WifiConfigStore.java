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
import android.os.Environment;
import android.os.FileUtils;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.SparseArray;
import android.util.Xml;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.AtomicFile;
import com.android.internal.util.FastXmlSerializer;
import com.android.internal.util.Preconditions;
import com.android.server.wifi.util.DataIntegrityChecker;
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
import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.nio.charset.StandardCharsets;
import java.security.DigestException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

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
     * Config store file for general user store file.
     */
    public static final int STORE_FILE_USER_GENERAL = 1;
    /**
     * Config store file for network suggestions user store file.
     */
    public static final int STORE_FILE_USER_NETWORK_SUGGESTIONS = 2;

    @IntDef(prefix = { "STORE_FILE_" }, value = {
            STORE_FILE_SHARED_GENERAL,
            STORE_FILE_USER_GENERAL,
            STORE_FILE_USER_NETWORK_SUGGESTIONS
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface StoreFileId { }

    private static final String XML_TAG_DOCUMENT_HEADER = "WifiConfigStoreData";
    private static final String XML_TAG_VERSION = "Version";
    /**
     * Current config store data version. This will be incremented for any additions.
     */
    private static final int CURRENT_CONFIG_STORE_DATA_VERSION = 1;
    /** This list of older versions will be used to restore data from older config store. */
    /**
     * First version of the config store data format.
     */
    private static final int INITIAL_CONFIG_STORE_DATA_VERSION = 1;

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
     * Directory to store the config store files in.
     */
    private static final String STORE_DIRECTORY_NAME = "wifi";
    /**
     * Time interval for buffering file writes for non-forced writes
     */
    private static final int BUFFERED_WRITE_ALARM_INTERVAL_MS = 10 * 1000;
    /**
     * Config store file name for general shared store file.
     */
    private static final String STORE_FILE_NAME_SHARED_GENERAL = "WifiConfigStore.xml";
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
     * Shared config store file instance. There is 1 shared store file:
     * {@link #STORE_FILE_NAME_SHARED_GENERAL}.
     */
    private StoreFile mSharedStore;
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
     * @param looper      looper instance to post alarm timeouts to.
     * @param clock       clock instance to retrieve timestamps for alarms.
     * @param wifiMetrics Metrics instance.
     * @param sharedStore StoreFile instance pointing to the shared store file. This should
     *                    be retrieved using {@link #createSharedFile()} method.
     */
    public WifiConfigStore(Context context, Looper looper, Clock clock, WifiMetrics wifiMetrics,
            StoreFile sharedStore) {

        mAlarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        mEventHandler = new Handler(looper);
        mClock = clock;
        mWifiMetrics = wifiMetrics;
        mStoreDataList = new ArrayList<>();

        // Initialize the store files.
        mSharedStore = sharedStore;
        // The user store is initialized to null, this will be set when the user unlocks and
        // CE storage is accessible via |switchUserStoresAndRead|.
        mUserStores = null;
    }

    /**
     * Set the user store files.
     * (Useful for mocking in unit tests).
     * @param userStores List of {@link StoreFile} created using {@link #createUserFiles(int)}.
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
     * @param storeBaseDir Base directory under which the store file is to be stored. The store file
     *                     will be at <storeBaseDir>/wifi/WifiConfigStore.xml.
     * @param fileId Identifier for the file. See {@link StoreFileId}.
     * @return new instance of the store file or null if the directory cannot be created.
     */
    private static @Nullable StoreFile createFile(File storeBaseDir, @StoreFileId int fileId) {
        File storeDir = new File(storeBaseDir, STORE_DIRECTORY_NAME);
        if (!storeDir.exists()) {
            if (!storeDir.mkdir()) {
                Log.w(TAG, "Could not create store directory " + storeDir);
                return null;
            }
        }
        return new StoreFile(new File(storeDir, STORE_ID_TO_FILE_NAME.get(fileId)), fileId);
    }

    /**
     * Create a new instance of the shared store file.
     *
     * @return new instance of the store file or null if the directory cannot be created.
     */
    public static @Nullable StoreFile createSharedFile() {
        return createFile(Environment.getDataMiscDirectory(), STORE_FILE_SHARED_GENERAL);
    }

    /**
     * Create new instances of the user specific store files.
     * The user store file is inside the user's encrypted data directory.
     *
     * @param userId userId corresponding to the currently logged-in user.
     * @return List of new instances of the store files created or null if the directory cannot be
     * created.
     */
    public static @Nullable List<StoreFile> createUserFiles(int userId) {
        List<StoreFile> storeFiles = new ArrayList<>();
        for (int fileId : Arrays.asList(
                STORE_FILE_USER_GENERAL, STORE_FILE_USER_NETWORK_SUGGESTIONS)) {
            StoreFile storeFile = createFile(Environment.getDataMiscCeDirectory(userId), fileId);
            if (storeFile == null) {
                return null;
            }
            storeFiles.add(storeFile);
        }
        return storeFiles;
    }

    /**
     * Enable verbose logging.
     */
    public void enableVerboseLogging(boolean verbose) {
        mVerboseLoggingEnabled = verbose;
    }

    /**
     * API to check if any of the store files are present on the device. This can be used
     * to detect if the device needs to perform data migration from legacy stores.
     *
     * @return true if any of the store file is present, false otherwise.
     */
    public boolean areStoresPresent() {
        // Checking for the shared store file existence is sufficient since this is guaranteed
        // to be present on migrated devices.
        return mSharedStore.exists();
    }

    /**
     * Retrieve the list of {@link StoreData} instances registered for the provided
     * {@link StoreFile}.
     */
    private List<StoreData> retrieveStoreDataListForStoreFile(@NonNull StoreFile storeFile) {
        return mStoreDataList
                .stream()
                .filter(s -> s.getStoreFileId() == storeFile.mFileId)
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
        if (hasNewDataToSerialize(mSharedStore)) {
            byte[] sharedDataBytes = serializeData(mSharedStore);
            mSharedStore.storeRawDataToWrite(sharedDataBytes);
            hasAnyNewData = true;
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

        XmlUtil.writeDocumentStart(out, XML_TAG_DOCUMENT_HEADER);
        XmlUtil.writeNextValue(out, XML_TAG_VERSION, CURRENT_CONFIG_STORE_DATA_VERSION);
        for (StoreData storeData : storeDataList) {
            String tag = storeData.getName();
            XmlUtil.writeNextSectionStart(out, tag);
            storeData.serializeData(out);
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
        mSharedStore.writeBufferedRawData();
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
     * API to read the store data from the config stores.
     * The method reads the user specific configurations from user specific config store and the
     * shared configurations from the shared config store.
     */
    public void read() throws XmlPullParserException, IOException {
        // Reset both share and user store data.
        resetStoreData(mSharedStore);
        if (mUserStores != null) {
            for (StoreFile userStoreFile : mUserStores) {
                resetStoreData(userStoreFile);
            }
        }

        long readStartTime = mClock.getElapsedSinceBootMillis();
        byte[] sharedDataBytes = mSharedStore.readRawData();
        deserializeData(sharedDataBytes, mSharedStore);
        if (mUserStores != null) {
            for (StoreFile userStoreFile : mUserStores) {
                byte[] userDataBytes = userStoreFile.readRawData();
                deserializeData(userDataBytes, userStoreFile);
            }
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

        // Now read from the user store file.
        long readStartTime = mClock.getElapsedSinceBootMillis();
        for (StoreFile userStoreFile : mUserStores) {
            byte[] userDataBytes = userStoreFile.readRawData();
            deserializeData(userDataBytes, userStoreFile);
        }
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
    private void indicateNoDataForStoreDatas(Collection<StoreData> storeDataSet)
            throws XmlPullParserException, IOException {
        for (StoreData storeData : storeDataSet) {
            storeData.deserializeData(null, 0);
        }
    }

    /**
     * Deserialize data from a {@link StoreFile} for all {@link StoreData} instances registered.
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
            indicateNoDataForStoreDatas(storeDataList);
            return;
        }
        final XmlPullParser in = Xml.newPullParser();
        final ByteArrayInputStream inputStream = new ByteArrayInputStream(dataBytes);
        in.setInput(inputStream, StandardCharsets.UTF_8.name());

        // Start parsing the XML stream.
        int rootTagDepth = in.getDepth() + 1;
        parseDocumentStartAndVersionFromXml(in);

        String[] headerName = new String[1];
        Set<StoreData> storeDatasInvoked = new HashSet<>();
        while (XmlUtil.gotoNextSectionOrEnd(in, headerName, rootTagDepth)) {
            // There can only be 1 store data matching the tag (O indicates a fatal
            // error).
            StoreData storeData = storeDataList.stream()
                    .filter(s -> s.getName().equals(headerName[0]))
                    .findAny()
                    .orElse(null);
            if (storeData == null) {
                throw new XmlPullParserException("Unknown store data: " + headerName[0]
                        + ". List of store data: " + storeDataList);
            }
            storeData.deserializeData(in, rootTagDepth + 1);
            storeDatasInvoked.add(storeData);
        }
        // Inform all the other registered store data clients that there is nothing in the store
        // for them.
        Set<StoreData> storeDatasNotInvoked = new HashSet<>(storeDataList);
        storeDatasNotInvoked.removeAll(storeDatasInvoked);
        indicateNoDataForStoreDatas(storeDatasNotInvoked);
    }

    /**
     * Parse the document start and version from the XML stream.
     * This is used for both the shared and user config store data.
     *
     * @param in XmlPullParser instance pointing to the XML stream.
     * @return version number retrieved from the Xml stream.
     */
    private static int parseDocumentStartAndVersionFromXml(XmlPullParser in)
            throws XmlPullParserException, IOException {
        XmlUtil.gotoDocumentStart(in, XML_TAG_DOCUMENT_HEADER);
        int version = (int) XmlUtil.readNextValueWithName(in, XML_TAG_VERSION);
        if (version < INITIAL_CONFIG_STORE_DATA_VERSION
                || version > CURRENT_CONFIG_STORE_DATA_VERSION) {
            throw new XmlPullParserException("Invalid version of data: " + version);
        }
        return version;
    }

    /**
     * Dump the local log buffer and other internal state of WifiConfigManager.
     */
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("Dump of WifiConfigStore");
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
        private String mFileName;
        /**
         * The integrity file storing integrity checking data for the store file.
         */
        private DataIntegrityChecker mDataIntegrityChecker;
        /**
         * {@link StoreFileId} Type of store file.
         */
        private @StoreFileId int mFileId;

        public StoreFile(File file, @StoreFileId int fileId) {
            mAtomicFile = new AtomicFile(file);
            mFileName = mAtomicFile.getBaseFile().getAbsolutePath();
            mDataIntegrityChecker = new DataIntegrityChecker(mFileName);
            mFileId = fileId;
        }

        /**
         * Returns whether the store file already exists on disk or not.
         *
         * @return true if it exists, false otherwise.
         */
        public boolean exists() {
            return mAtomicFile.exists();
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
                // Check that the file has not been altered since last writeBufferedRawData()
                if (!mDataIntegrityChecker.isOk(bytes)) {
                    Log.wtf(TAG, "Data integrity problem with file: " + mFileName);
                    return null;
                }
            } catch (FileNotFoundException e) {
                return null;
            } catch (DigestException e) {
                // When integrity checking is introduced. The existing data will have no related
                // integrity file for validation. Thus, we will assume the existing data is correct
                // and immediately create the integrity file.
                Log.i(TAG, "isOK() had no integrity data to check; thus vacuously "
                        + "true. Running update now.");
                mDataIntegrityChecker.update(bytes);
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
                FileUtils.setPermissions(mFileName, FILE_MODE, -1, -1);
                out.write(mWriteData);
                mAtomicFile.finishWrite(out);
            } catch (IOException e) {
                if (out != null) {
                    mAtomicFile.failWrite(out);
                }
                throw e;
            }
            // There was a legitimate change and update the integrity checker.
            mDataIntegrityChecker.update(mWriteData);
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
         */
        void serializeData(XmlSerializer out)
                throws XmlPullParserException, IOException;

        /**
         * Deserialize a XML data block from the input stream.
         *
         * @param in The input stream to read the data from. This could be null if there is
         *           nothing in the store.
         * @param outerTagDepth The depth of the outer tag in the XML document
         * Note: This will be invoked every time a store file is read, even if there is nothing
         *                      in the store for them.
         */
        void deserializeData(@Nullable XmlPullParser in, int outerTagDepth)
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
