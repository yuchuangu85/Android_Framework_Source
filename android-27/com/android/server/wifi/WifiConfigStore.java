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

import android.app.AlarmManager;
import android.content.Context;
import android.os.Environment;
import android.os.FileUtils;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.Xml;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.AtomicFile;
import com.android.internal.util.FastXmlSerializer;
import com.android.server.wifi.util.XmlUtil;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * This class provides the API's to save/load/modify network configurations from a persistent
 * store. Uses keystore for certificate/key management operations.
 * NOTE: This class should only be used from WifiConfigManager and is not thread-safe!
 */
public class WifiConfigStore {
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
     * Config store file name for both shared & user specific stores.
     */
    private static final String STORE_FILE_NAME = "WifiConfigStore.xml";
    /**
     * Directory to store the config store files in.
     */
    private static final String STORE_DIRECTORY_NAME = "wifi";
    /**
     * Time interval for buffering file writes for non-forced writes
     */
    private static final int BUFFERED_WRITE_ALARM_INTERVAL_MS = 10 * 1000;
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
    /**
     * Shared config store file instance.
     */
    private StoreFile mSharedStore;
    /**
     * User specific store file instance.
     */
    private StoreFile mUserStore;
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
     * List of data container.
     */
    private final Map<String, StoreData> mStoreDataList;

    /**
     * Create a new instance of WifiConfigStore.
     * Note: The store file instances have been made inputs to this class to ease unit-testing.
     *
     * @param context     context to use for retrieving the alarm manager.
     * @param looper      looper instance to post alarm timeouts to.
     * @param clock       clock instance to retrieve timestamps for alarms.
     * @param sharedStore StoreFile instance pointing to the shared store file. This should
     *                    be retrieved using {@link #createSharedFile()} method.
     */
    public WifiConfigStore(Context context, Looper looper, Clock clock,
            StoreFile sharedStore) {

        mAlarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        mEventHandler = new Handler(looper);
        mClock = clock;
        mStoreDataList = new HashMap<>();

        // Initialize the store files.
        mSharedStore = sharedStore;
        // The user store is initialized to null, this will be set when the user unlocks and
        // CE storage is accessible via |switchUserStoreAndRead|.
        mUserStore = null;
    }

    public void setUserStore(StoreFile userStore) {
        mUserStore = userStore;
    }

    /**
     * Register a {@link StoreData} to store.  A {@link StoreData} is responsible
     * for a block of data in the store file, and provides serialization/deserialization functions
     * for those data.
     *
     * @param storeData The store data to be registered to the config store
     * @return true if succeeded
     */
    public boolean registerStoreData(StoreData storeData) {
        if (storeData == null) {
            Log.e(TAG, "Unable to register null store data");
            return false;
        }
        mStoreDataList.put(storeData.getName(), storeData);
        return true;
    }

    /**
     * Helper method to create a store file instance for either the shared store or user store.
     * Note: The method creates the store directory if not already present. This may be needed for
     * user store files.
     *
     * @param storeBaseDir Base directory under which the store file is to be stored. The store file
     *                     will be at <storeBaseDir>/wifi/WifiConfigStore.xml.
     * @return new instance of the store file.
     */
    private static StoreFile createFile(File storeBaseDir) {
        File storeDir = new File(storeBaseDir, STORE_DIRECTORY_NAME);
        if (!storeDir.exists()) {
            if (!storeDir.mkdir()) {
                Log.w(TAG, "Could not create store directory " + storeDir);
            }
        }
        return new StoreFile(new File(storeDir, STORE_FILE_NAME));
    }

    /**
     * Create a new instance of the shared store file.
     *
     * @return new instance of the store file or null if the directory cannot be created.
     */
    public static StoreFile createSharedFile() {
        return createFile(Environment.getDataMiscDirectory());
    }

    /**
     * Create a new instance of the user specific store file.
     * The user store file is inside the user's encrypted data directory.
     *
     * @param userId userId corresponding to the currently logged-in user.
     * @return new instance of the store file or null if the directory cannot be created.
     */
    public static StoreFile createUserFile(int userId) {
        return createFile(Environment.getDataMiscCeDirectory(userId));
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
        return (mSharedStore.exists() || (mUserStore != null && mUserStore.exists()));
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
        // Serialize the provided data and send it to the respective stores. The actual write will
        // be performed later depending on the |forceSync| flag .
        byte[] sharedDataBytes = serializeData(true);
        mSharedStore.storeRawDataToWrite(sharedDataBytes);
        if (mUserStore != null) {
            byte[] userDataBytes = serializeData(false);
            mUserStore.storeRawDataToWrite(userDataBytes);
        }

        // Every write provides a new snapshot to be persisted, so |forceSync| flag overrides any
        // pending buffer writes.
        if (forceSync) {
            writeBufferedData();
        } else {
            startBufferedWriteAlarm();
        }
    }

    /**
     * Serialize share data or user data from all store data.
     *
     * @param shareData Flag indicating share data
     * @return byte[] of serialized bytes
     * @throws XmlPullParserException
     * @throws IOException
     */
    private byte[] serializeData(boolean shareData) throws XmlPullParserException, IOException {
        final XmlSerializer out = new FastXmlSerializer();
        final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        out.setOutput(outputStream, StandardCharsets.UTF_8.name());

        XmlUtil.writeDocumentStart(out, XML_TAG_DOCUMENT_HEADER);
        XmlUtil.writeNextValue(out, XML_TAG_VERSION, CURRENT_CONFIG_STORE_DATA_VERSION);

        for (Map.Entry<String, StoreData> entry : mStoreDataList.entrySet()) {
            String tag = entry.getKey();
            StoreData storeData = entry.getValue();
            // Ignore this store data if this is for share file and the store data doesn't support
            // share store.
            if (shareData && !storeData.supportShareData()) {
                continue;
            }
            XmlUtil.writeNextSectionStart(out, tag);
            storeData.serializeData(out, shareData);
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
        if (mUserStore != null) {
            mUserStore.writeBufferedRawData();
        }
        long writeTime = mClock.getElapsedSinceBootMillis() - writeStartTime;

        Log.d(TAG, "Writing to stores completed in " + writeTime + " ms.");
    }

    /**
     * API to read the store data from the config stores.
     * The method reads the user specific configurations from user specific config store and the
     * shared configurations from the shared config store.
     */
    public void read() throws XmlPullParserException, IOException {
        // Reset both share and user store data.
        resetStoreData(true);
        resetStoreData(false);

        long readStartTime = mClock.getElapsedSinceBootMillis();
        byte[] sharedDataBytes = mSharedStore.readRawData();
        byte[] userDataBytes = null;
        if (mUserStore != null) {
            userDataBytes = mUserStore.readRawData();
        }
        long readTime = mClock.getElapsedSinceBootMillis() - readStartTime;
        Log.d(TAG, "Reading from stores completed in " + readTime + " ms.");
        deserializeData(sharedDataBytes, true);
        deserializeData(userDataBytes, false);
    }

    /**
     * Handles a user switch. This method changes the user specific store file and reads from the
     * new user's store file.
     *
     * @param userStore StoreFile instance pointing to the user specific store file. This should
     *                  be retrieved using {@link #createUserFile(int)} method.
     */
    public void switchUserStoreAndRead(StoreFile userStore)
            throws XmlPullParserException, IOException {
        // Reset user store data.
        resetStoreData(false);

        // Stop any pending buffered writes, if any.
        stopBufferedWriteAlarm();
        mUserStore = userStore;

        // Now read from the user store file.
        long readStartTime = mClock.getElapsedSinceBootMillis();
        byte[] userDataBytes = mUserStore.readRawData();
        long readTime = mClock.getElapsedSinceBootMillis() - readStartTime;
        Log.d(TAG, "Reading from user store completed in " + readTime + " ms.");
        deserializeData(userDataBytes, false);
    }

    /**
     * Reset share data or user data in all store data.
     *
     * @param shareData Flag indicating share data
     */
    private void resetStoreData(boolean shareData) {
        for (Map.Entry<String, StoreData> entry : mStoreDataList.entrySet()) {
            entry.getValue().resetData(shareData);
        }
    }

    /**
     * Deserialize share data or user data into store data.
     *
     * @param dataBytes The data to parse
     * @param shareData The flag indicating share data
     * @throws XmlPullParserException
     * @throws IOException
     */
    private void deserializeData(byte[] dataBytes, boolean shareData)
            throws XmlPullParserException, IOException {
        if (dataBytes == null) {
            return;
        }
        final XmlPullParser in = Xml.newPullParser();
        final ByteArrayInputStream inputStream = new ByteArrayInputStream(dataBytes);
        in.setInput(inputStream, StandardCharsets.UTF_8.name());

        // Start parsing the XML stream.
        int rootTagDepth = in.getDepth() + 1;
        parseDocumentStartAndVersionFromXml(in);

        String[] headerName = new String[1];
        while (XmlUtil.gotoNextSectionOrEnd(in, headerName, rootTagDepth)) {
            StoreData storeData = mStoreDataList.get(headerName[0]);
            if (storeData == null) {
                throw new XmlPullParserException("Unknown store data: " + headerName[0]);
            }
            storeData.deserializeData(in, rootTagDepth + 1, shareData);
        }
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
     * Class to encapsulate all file writes. This is a wrapper over {@link AtomicFile} to write/read
     * raw data from the persistent file. This class provides helper methods to read/write the
     * entire file into a byte array.
     * This helps to separate out the processing/parsing from the actual file writing.
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

        public StoreFile(File file) {
            mAtomicFile = new AtomicFile(file);
            mFileName = mAtomicFile.getBaseFile().getAbsolutePath();
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
         * @return raw data read from the file or null if the file is not found.
         * @throws IOException if an error occurs. The input stream is always closed by the method
         * even when an exception is encountered.
         */
        public byte[] readRawData() throws IOException {
            try {
                return mAtomicFile.readFully();
            } catch (FileNotFoundException e) {
                return null;
            }
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
            if (mWriteData == null) {
                Log.w(TAG, "No data stored for writing to file: " + mFileName);
                return;
            }
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
            // Reset the pending write data after write.
            mWriteData = null;
        }
    }

    /**
     * Interface to be implemented by a module that contained data in the config store file.
     *
     * The module will be responsible for serializing/deserializing their own data.
     */
    public interface StoreData {
        /**
         * Serialize a XML data block to the output stream. The |shared| flag indicates if the
         * output stream is backed by a share store or an user store.
         *
         * @param out The output stream to serialize the data to
         * @param shared Flag indicating if the output stream is backed by a share store or an
         *               user store
         */
        void serializeData(XmlSerializer out, boolean shared)
                throws XmlPullParserException, IOException;

        /**
         * Deserialize a XML data block from the input stream.  The |shared| flag indicates if the
         * input stream is backed by a share store or an user store.  When |shared| is set to true,
         * the shared configuration data will be overwritten by the parsed data. Otherwise,
         * the user configuration will be overwritten by the parsed data.
         *
         * @param in The input stream to read the data from
         * @param outerTagDepth The depth of the outer tag in the XML document
         * @Param shared Flag indicating if the input stream is backed by a share store or an
         *               user store
         */
        void deserializeData(XmlPullParser in, int outerTagDepth, boolean shared)
                throws XmlPullParserException, IOException;

        /**
         * Reset configuration data.  The |shared| flag indicates which configuration data to
         * reset.  When |shared| is set to true, the shared configuration data will be reset.
         * Otherwise, the user configuration data will be reset.
         */
        void resetData(boolean shared);

        /**
         * Return the name of this store data.  The data will be enclosed under this tag in
         * the XML block.
         *
         * @return The name of the store data
         */
        String getName();

        /**
         * Flag indicating if shared configuration data is supported.
         *
         * @return true if shared configuration data is supported
         */
        boolean supportShareData();
    }
}
