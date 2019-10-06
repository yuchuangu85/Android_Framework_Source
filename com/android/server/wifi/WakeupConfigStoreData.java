/*
 * Copyright 2017 The Android Open Source Project
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

import android.util.ArraySet;
import android.util.Log;

import com.android.server.wifi.WifiConfigStore.StoreData;
import com.android.server.wifi.util.XmlUtil;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.IOException;
import java.util.Collections;
import java.util.Set;

/**
 * Config store data for Wifi Wake.
 */
public class WakeupConfigStoreData implements StoreData {
    private static final String TAG = "WakeupConfigStoreData";

    private static final String XML_TAG_FEATURE_STATE_SECTION = "FeatureState";
    private static final String XML_TAG_IS_ACTIVE = "IsActive";
    private static final String XML_TAG_IS_ONBOARDED = "IsOnboarded";
    private static final String XML_TAG_NOTIFICATIONS_SHOWN = "NotificationsShown";
    private static final String XML_TAG_NETWORK_SECTION = "Network";
    private static final String XML_TAG_SSID = "SSID";
    private static final String XML_TAG_SECURITY = "Security";

    private final DataSource<Boolean> mIsActiveDataSource;
    private final DataSource<Boolean> mIsOnboardedDataSource;
    private final DataSource<Integer> mNotificationsDataSource;
    private final DataSource<Set<ScanResultMatchInfo>> mNetworkDataSource;
    private boolean mHasBeenRead = false;

    /**
     * Interface defining a data source for the store data.
     *
     * @param <T> Type of data source
     */
    public interface DataSource<T> {
        /**
         * Returns the data from the data source.
         */
        T getData();

        /**
         * Updates the data in the data source.
         *
         * @param data Data retrieved from the store
         */
        void setData(T data);
    }

    /**
     * Creates the config store data with its data sources.
     *
     * @param isActiveDataSource Data source for isActive
     * @param networkDataSource Data source for the locked network list
     */
    public WakeupConfigStoreData(
            DataSource<Boolean> isActiveDataSource,
            DataSource<Boolean> isOnboardedDataSource,
            DataSource<Integer> notificationsDataSource,
            DataSource<Set<ScanResultMatchInfo>> networkDataSource) {
        mIsActiveDataSource = isActiveDataSource;
        mIsOnboardedDataSource = isOnboardedDataSource;
        mNotificationsDataSource = notificationsDataSource;
        mNetworkDataSource = networkDataSource;
    }

    /**
     * Returns whether the user store has been read.
     */
    public boolean hasBeenRead() {
        return mHasBeenRead;
    }

    @Override
    public void serializeData(XmlSerializer out)
            throws XmlPullParserException, IOException {
        writeFeatureState(out);

        for (ScanResultMatchInfo scanResultMatchInfo : mNetworkDataSource.getData()) {
            writeNetwork(out, scanResultMatchInfo);
        }
    }

    /**
     * Writes the current state of Wifi Wake to an XML output stream.
     *
     * @param out XML output stream
     * @throws XmlPullParserException
     * @throws IOException
     */
    private void writeFeatureState(XmlSerializer out)
            throws IOException, XmlPullParserException {
        XmlUtil.writeNextSectionStart(out, XML_TAG_FEATURE_STATE_SECTION);

        XmlUtil.writeNextValue(out, XML_TAG_IS_ACTIVE, mIsActiveDataSource.getData());
        XmlUtil.writeNextValue(out, XML_TAG_IS_ONBOARDED, mIsOnboardedDataSource.getData());
        XmlUtil.writeNextValue(out, XML_TAG_NOTIFICATIONS_SHOWN,
                mNotificationsDataSource.getData());

        XmlUtil.writeNextSectionEnd(out, XML_TAG_FEATURE_STATE_SECTION);
    }

    /**
     * Writes a {@link ScanResultMatchInfo} to an XML output stream.
     *
     * @param out XML output stream
     * @param scanResultMatchInfo The ScanResultMatchInfo to serialize
     * @throws XmlPullParserException
     * @throws IOException
     */
    private void writeNetwork(XmlSerializer out, ScanResultMatchInfo scanResultMatchInfo)
            throws XmlPullParserException, IOException {
        XmlUtil.writeNextSectionStart(out, XML_TAG_NETWORK_SECTION);

        XmlUtil.writeNextValue(out, XML_TAG_SSID, scanResultMatchInfo.networkSsid);
        XmlUtil.writeNextValue(out, XML_TAG_SECURITY, scanResultMatchInfo.networkType);

        XmlUtil.writeNextSectionEnd(out, XML_TAG_NETWORK_SECTION);
    }

    @Override
    public void deserializeData(XmlPullParser in, int outerTagDepth)
            throws XmlPullParserException, IOException {
        if (!mHasBeenRead) {
            Log.d(TAG, "WifiWake user data has been read");
            mHasBeenRead = true;
        }
        // Ignore empty reads.
        if (in == null) {
            return;
        }

        Set<ScanResultMatchInfo> networks = new ArraySet<>();

        String[] headerName = new String[1];
        while (XmlUtil.gotoNextSectionOrEnd(in, headerName, outerTagDepth)) {
            switch (headerName[0]) {
                case XML_TAG_FEATURE_STATE_SECTION:
                    parseFeatureState(in, outerTagDepth + 1);
                    break;
                case XML_TAG_NETWORK_SECTION:
                    networks.add(parseNetwork(in, outerTagDepth + 1));
                    break;
            }
        }

        mNetworkDataSource.setData(networks);
    }

    /**
     * Parses the state of Wifi Wake from an XML input stream and sets the respective data sources.
     *
     * @param in XML input stream
     * @param outerTagDepth XML tag depth of the containing section
     * @throws IOException
     * @throws XmlPullParserException
     */
    private void parseFeatureState(XmlPullParser in, int outerTagDepth)
            throws IOException, XmlPullParserException {
        boolean isActive = false;
        boolean isOnboarded = false;
        int notificationsShown = 0;

        while (!XmlUtil.isNextSectionEnd(in, outerTagDepth)) {
            String[] valueName = new String[1];
            Object value = XmlUtil.readCurrentValue(in, valueName);
            if (valueName[0] == null) {
                throw new XmlPullParserException("Missing value name");
            }
            switch (valueName[0]) {
                case XML_TAG_IS_ACTIVE:
                    isActive = (Boolean) value;
                    break;
                case XML_TAG_IS_ONBOARDED:
                    isOnboarded = (Boolean) value;
                    break;
                case XML_TAG_NOTIFICATIONS_SHOWN:
                    notificationsShown = (Integer) value;
                    break;
                default:
                    throw new XmlPullParserException("Unknown value found: " + valueName[0]);
            }
        }

        mIsActiveDataSource.setData(isActive);
        mIsOnboardedDataSource.setData(isOnboarded);
        mNotificationsDataSource.setData(notificationsShown);
    }

    /**
     * Parses a {@link ScanResultMatchInfo} from an XML input stream.
     *
     * @param in XML input stream
     * @param outerTagDepth XML tag depth of the containing section
     * @return The {@link ScanResultMatchInfo}
     * @throws IOException
     * @throws XmlPullParserException
     */
    private ScanResultMatchInfo parseNetwork(XmlPullParser in, int outerTagDepth)
            throws IOException, XmlPullParserException {
        ScanResultMatchInfo scanResultMatchInfo = new ScanResultMatchInfo();
        while (!XmlUtil.isNextSectionEnd(in, outerTagDepth)) {
            String[] valueName = new String[1];
            Object value = XmlUtil.readCurrentValue(in, valueName);
            if (valueName[0] == null) {
                throw new XmlPullParserException("Missing value name");
            }
            switch (valueName[0]) {
                case XML_TAG_SSID:
                    scanResultMatchInfo.networkSsid = (String) value;
                    break;
                case XML_TAG_SECURITY:
                    scanResultMatchInfo.networkType = (int) value;
                    break;
                default:
                    throw new XmlPullParserException("Unknown tag under " + TAG + ": "
                            + valueName[0]);
            }
        }

        return scanResultMatchInfo;
    }

    @Override
    public void resetData() {
        mNetworkDataSource.setData(Collections.emptySet());
        mIsActiveDataSource.setData(false);
        mIsOnboardedDataSource.setData(false);
        mNotificationsDataSource.setData(0);
    }

    @Override
    public boolean hasNewDataToSerialize() {
        // always persist.
        return true;
    }

    @Override
    public String getName() {
        return TAG;
    }

    @Override
    public @WifiConfigStore.StoreFileId int getStoreFileId() {
        // Shared general store.
        return WifiConfigStore.STORE_FILE_USER_GENERAL;
    }
}
