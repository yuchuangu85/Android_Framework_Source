/*
 * Copyright (C) 2017 The Android Open Source Project
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

import android.text.TextUtils;

import com.android.server.wifi.util.XmlUtil;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

/**
 * Store data for network notifiers.
 *
 * Below are the current configuration data for each respective store file:
 *
 * Share Store (system wide configurations)
 * - No data
 *
 * User Store (user specific configurations)
 * - Set of blacklisted SSIDs
 */
public class SsidSetStoreData implements WifiConfigStore.StoreData {
    private static final String XML_TAG_SECTION_HEADER_SUFFIX = "ConfigData";
    private static final String XML_TAG_SSID_SET = "SSIDSet";

    private final String mTagName;
    private final DataSource mDataSource;

    /**
     * Interface define the data source for the notifier store data.
     */
    public interface DataSource {
        /**
         * Retrieve the SSID set from the data source.
         *
         * @return Set of SSIDs
         */
        Set<String> getSsids();

        /**
         * Update the SSID set in the data source.
         *
         * @param ssidSet The set of SSIDs
         */
        void setSsids(Set<String> ssidSet);
    }

    /**
     * Creates the SSID Set store data.
     *
     * @param name Identifier of the SSID set.
     * @param dataSource The DataSource that implements the update and retrieval of the SSID set.
     */
    SsidSetStoreData(String name, DataSource dataSource) {
        mTagName = name + XML_TAG_SECTION_HEADER_SUFFIX;
        mDataSource = dataSource;
    }

    @Override
    public void serializeData(XmlSerializer out, boolean shared)
            throws XmlPullParserException, IOException {
        if (shared) {
            throw new XmlPullParserException("Share data not supported");
        }
        Set<String> ssidSet = mDataSource.getSsids();
        if (ssidSet != null && !ssidSet.isEmpty()) {
            XmlUtil.writeNextValue(out, XML_TAG_SSID_SET, mDataSource.getSsids());
        }
    }

    @Override
    public void deserializeData(XmlPullParser in, int outerTagDepth, boolean shared)
            throws XmlPullParserException, IOException {
        // Ignore empty reads.
        if (in == null) {
            return;
        }
        if (shared) {
            throw new XmlPullParserException("Share data not supported");
        }

        while (!XmlUtil.isNextSectionEnd(in, outerTagDepth)) {
            String[] valueName = new String[1];
            Object value = XmlUtil.readCurrentValue(in, valueName);
            if (TextUtils.isEmpty(valueName[0])) {
                throw new XmlPullParserException("Missing value name");
            }
            switch (valueName[0]) {
                case XML_TAG_SSID_SET:
                    mDataSource.setSsids((Set<String>) value);
                    break;
                default:
                    throw new XmlPullParserException("Unknown tag under "
                            + mTagName + ": " + valueName[0]);
            }
        }
    }

    @Override
    public void resetData(boolean shared) {
        if (!shared) {
            mDataSource.setSsids(new HashSet<>());
        }
    }

    @Override
    public String getName() {
        return mTagName;
    }

    @Override
    public boolean supportShareData() {
        return false;
    }
}
