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
import java.util.HashSet;
import java.util.Set;

/**
 * This class performs serialization and parsing of XML data block that contain the list of
 * deleted ephemeral SSIDs (XML block data inside <DeletedEphemeralSSIDList> tag).
 */
public class DeletedEphemeralSsidsStoreData implements WifiConfigStore.StoreData {
    private static final String XML_TAG_SECTION_HEADER_DELETED_EPHEMERAL_SSID_LIST =
            "DeletedEphemeralSSIDList";
    private static final String XML_TAG_SSID_LIST = "SSIDList";

    private Set<String> mSsidList;

    DeletedEphemeralSsidsStoreData() {}

    @Override
    public void serializeData(XmlSerializer out, boolean shared)
            throws XmlPullParserException, IOException {
        if (shared) {
            throw new XmlPullParserException("Share data not supported");
        }
        if (mSsidList != null) {
            XmlUtil.writeNextValue(out, XML_TAG_SSID_LIST, mSsidList);
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
            if (valueName[0] == null) {
                throw new XmlPullParserException("Missing value name");
            }
            switch (valueName[0]) {
                case XML_TAG_SSID_LIST:
                    mSsidList = (Set<String>) value;
                    break;
                default:
                    throw new XmlPullParserException("Unknown tag under "
                            + XML_TAG_SECTION_HEADER_DELETED_EPHEMERAL_SSID_LIST
                            + ": " + valueName[0]);
            }
        }
    }

    @Override
    public void resetData(boolean shared) {
        if (!shared) {
            mSsidList = null;
        }
    }

    @Override
    public String getName() {
        return XML_TAG_SECTION_HEADER_DELETED_EPHEMERAL_SSID_LIST;
    }

    @Override
    public boolean supportShareData() {
        return false;
    }

    /**
     * An empty set will be returned for null SSID list.
     *
     * @return Set of SSIDs
     */
    public Set<String> getSsidList() {
        if (mSsidList == null) {
            return new HashSet<String>();
        }
        return mSsidList;
    }

    public void setSsidList(Set<String> ssidList) {
        mSsidList = ssidList;
    }
}

