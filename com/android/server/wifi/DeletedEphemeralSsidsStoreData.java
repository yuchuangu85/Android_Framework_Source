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

import com.android.server.wifi.util.XmlUtil;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * This class performs serialization and parsing of XML data block that contain the list of
 * deleted ephemeral SSIDs (XML block data inside <DeletedEphemeralSSIDList> tag).
 */
public class DeletedEphemeralSsidsStoreData implements WifiConfigStore.StoreData {
    private static final String XML_TAG_SECTION_HEADER_DELETED_EPHEMERAL_SSID_LIST =
            "DeletedEphemeralSSIDList";
    private static final String XML_TAG_SSID_LIST = "SSIDList";

    private final Clock mClock;
    private Map<String, Long> mSsidToTimeMap;

    DeletedEphemeralSsidsStoreData(Clock clock) {
        mClock = clock;
    }

    @Override
    public void serializeData(XmlSerializer out)
            throws XmlPullParserException, IOException {
        if (mSsidToTimeMap != null) {
            XmlUtil.writeNextValue(out, XML_TAG_SSID_LIST, mSsidToTimeMap);
        }
    }

    @Override
    public void deserializeData(XmlPullParser in, int outerTagDepth)
            throws XmlPullParserException, IOException {
        // Ignore empty reads.
        if (in == null) {
            return;
        }
        while (!XmlUtil.isNextSectionEnd(in, outerTagDepth)) {
            String[] valueName = new String[1];
            Object value = XmlUtil.readCurrentValue(in, valueName);
            if (valueName[0] == null) {
                throw new XmlPullParserException("Missing value name");
            }
            switch (valueName[0]) {
                case XML_TAG_SSID_LIST:
                    // Backwards compatibility, this used to be a set.
                    if (value instanceof Set) {
                        mSsidToTimeMap = new HashMap<>();
                        for (String ssid : (Set<String>) value) {
                            // Mark the deleted time as bootup time for existing entries from
                            // previous releases.
                            mSsidToTimeMap.put(ssid, mClock.getWallClockMillis());
                        }
                    } else if (value instanceof Map) {
                        mSsidToTimeMap = (Map<String, Long>) value;
                    }
                    break;
                default:
                    throw new XmlPullParserException("Unknown tag under "
                            + XML_TAG_SECTION_HEADER_DELETED_EPHEMERAL_SSID_LIST
                            + ": " + valueName[0]);
            }
        }
    }

    @Override
    public void resetData() {
        mSsidToTimeMap = null;
    }

    @Override
    public boolean hasNewDataToSerialize() {
        // always persist.
        return true;
    }

    @Override
    public String getName() {
        return XML_TAG_SECTION_HEADER_DELETED_EPHEMERAL_SSID_LIST;
    }

    @Override
    public @WifiConfigStore.StoreFileId int getStoreFileId() {
        // Shared general store.
        return WifiConfigStore.STORE_FILE_USER_GENERAL;
    }

    /**
     * An empty map will be returned for null SSID list.
     *
     * @return Map of SSIDs
     */
    public Map<String, Long> getSsidToTimeMap() {
        if (mSsidToTimeMap == null) {
            return new HashMap<String, Long>();
        }
        return mSsidToTimeMap;
    }

    public void setSsidToTimeMap(Map<String, Long> ssidMap) {
        mSsidToTimeMap = ssidMap;
    }
}

