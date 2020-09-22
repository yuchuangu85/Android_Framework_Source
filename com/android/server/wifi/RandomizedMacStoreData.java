/*
 * Copyright (C) 2019 The Android Open Source Project
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

import android.annotation.Nullable;
import android.util.Log;

import com.android.server.wifi.util.WifiConfigStoreEncryptionUtil;
import com.android.server.wifi.util.XmlUtil;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * This class performs serialization and parsing of XML data block that contain the mapping
 * from configKey to randomized MAC address
 * (XML block data inside <MacAddressMappingList> tag).
 */
public class RandomizedMacStoreData implements WifiConfigStore.StoreData {
    private static final String TAG = "RandomizedMacStoreData";
    private static final String XML_TAG_SECTION_HEADER_MAC_ADDRESS_MAP = "MacAddressMap";
    private static final String XML_TAG_MAC_MAP = "MacMapEntry";

    private Map<String, String> mMacMapping;

    RandomizedMacStoreData() {}

    @Override
    public void serializeData(XmlSerializer out,
            @Nullable WifiConfigStoreEncryptionUtil encryptionUtil)
            throws XmlPullParserException, IOException {
        if (mMacMapping != null) {
            XmlUtil.writeNextValue(out, XML_TAG_MAC_MAP, mMacMapping);
        }
    }

    @Override
    public void deserializeData(XmlPullParser in, int outerTagDepth,
            @WifiConfigStore.Version int version,
            @Nullable WifiConfigStoreEncryptionUtil encryptionUtil)
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
                case XML_TAG_MAC_MAP:
                    mMacMapping = (Map<String, String>) value;
                    break;
                default:
                    Log.w(TAG, "Ignoring unknown tag under "
                            + XML_TAG_SECTION_HEADER_MAC_ADDRESS_MAP
                            + ": " + valueName[0]);
                    break;
            }
        }
    }

    @Override
    public void resetData() {
        mMacMapping = null;
    }

    @Override
    public boolean hasNewDataToSerialize() {
        // always persist.
        return true;
    }

    @Override
    public String getName() {
        return XML_TAG_SECTION_HEADER_MAC_ADDRESS_MAP;
    }

    @Override
    public @WifiConfigStore.StoreFileId int getStoreFileId() {
        // Shared general store.
        return WifiConfigStore.STORE_FILE_SHARED_GENERAL;
    }

    /**
     * An empty Map will be returned for null MAC address map.
     *
     * @return Map of mapping from configKey to the randomized MAC address.
     */
    public Map<String, String> getMacMapping() {
        if (mMacMapping == null) {
            return new HashMap<String, String>();
        }
        return mMacMapping;
    }

    /**
     * Sets the data to be stored to file.
     * @param macMapping
     */
    public void setMacMapping(Map<String, String> macMapping) {
        mMacMapping = macMapping;
    }
}

