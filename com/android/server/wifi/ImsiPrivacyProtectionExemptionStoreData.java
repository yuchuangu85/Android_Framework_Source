/*
 * Copyright (C) 2020 The Android Open Source Project
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

import android.annotation.NonNull;
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
 * This class performs serialization and parsing of XML data block that contain the map of IMSI
 * protection exemption user approval info.
 */
public class ImsiPrivacyProtectionExemptionStoreData implements WifiConfigStore.StoreData {
    private static final String TAG = "ImsiPrivacyProtectionExemptionStoreData";
    private static final String XML_TAG_SECTION_HEADER_IMSI_PROTECTION_EXEMPTION_CARRIER_MAP =
            "ImsiPrivacyProtectionExemptionMap";
    private static final String XML_TAG_CARRIER_EXEMPTION_MAP = "CarrierExemptionMap";

    /**
     * Interface define the data source for the carrier IMSI protection exemption map store data.
     */
    public interface DataSource {
        /**
         * Retrieve the IMSI protection exemption map from the data source to serialize to disk.
         *
         * @return Map of carrier Id to if allowed.
         */
        Map<Integer, Boolean> toSerialize();

        /**
         * Set the IMSI protection exemption map in the data source after serializing them from disk
         *
         * @param imsiProtectionExemptionMap Map of carrier Id to allowed or not.
         */
        void fromDeserialized(Map<Integer, Boolean> imsiProtectionExemptionMap);

        /**
         * Clear internal data structure in preparation for user switch or initial store read.
         */
        void reset();

        /**
         * Indicates whether there is new data to serialize.
         */
        boolean hasNewDataToSerialize();
    }

    private final DataSource mDataSource;

    /**
     * Set the data source fot store data.
     */
    public ImsiPrivacyProtectionExemptionStoreData(@NonNull DataSource dataSource) {
        mDataSource = dataSource;
    }

    @Override
    public void serializeData(XmlSerializer out, WifiConfigStoreEncryptionUtil encryptionUtil)
            throws XmlPullParserException, IOException {
        Map<String, Boolean> dataToSerialize = integerMapToStringMap(mDataSource.toSerialize());
        XmlUtil.writeNextValue(out, XML_TAG_CARRIER_EXEMPTION_MAP, dataToSerialize);
    }

    @Override
    public void deserializeData(XmlPullParser in, int outerTagDepth, int version,
            WifiConfigStoreEncryptionUtil encryptionUtil)
            throws XmlPullParserException, IOException {
        // Ignore empty reads.
        if (in == null) {
            return;
        }

        mDataSource.fromDeserialized(parseCarrierImsiProtectionExemptionMap(in, outerTagDepth,
                version, encryptionUtil));

    }

    private Map<Integer, Boolean> parseCarrierImsiProtectionExemptionMap(XmlPullParser in,
            int outerTagDepth,
            @WifiConfigStore.Version int version,
            @Nullable WifiConfigStoreEncryptionUtil encryptionUtil)
            throws XmlPullParserException, IOException {
        Map<String, Boolean> protectionExemptionMap = new HashMap<>();
        while (!XmlUtil.isNextSectionEnd(in, outerTagDepth)) {
            String[] valueName = new String[1];
            Object value = XmlUtil.readCurrentValue(in, valueName);
            if (valueName[0] == null) {
                throw new XmlPullParserException("Missing value name");
            }
            switch (valueName[0]) {
                case XML_TAG_CARRIER_EXEMPTION_MAP:
                    if (value instanceof Map) {
                        protectionExemptionMap = (Map<String, Boolean>) value;
                    }
                    break;
                default:
                    Log.w(TAG, "Unknown tag under "
                            + XML_TAG_SECTION_HEADER_IMSI_PROTECTION_EXEMPTION_CARRIER_MAP
                            + ": " + valueName[0]);
                    break;
            }
        }
        return stringMapToIntegerMap(protectionExemptionMap);
    }

    private Map<String, Boolean> integerMapToStringMap(Map<Integer, Boolean> input) {
        Map<String, Boolean> output = new HashMap<>();
        if (input == null) {
            return output;
        }
        for (Map.Entry<Integer, Boolean> entry : input.entrySet()) {
            output.put(Integer.toString(entry.getKey()), entry.getValue());
        }
        return output;
    }

    private Map<Integer, Boolean> stringMapToIntegerMap(Map<String, Boolean> input) {
        Map<Integer, Boolean> output = new HashMap<>();
        if (input == null) {
            return output;
        }
        for (Map.Entry<String, Boolean> entry : input.entrySet()) {
            try {
                output.put(Integer.valueOf(entry.getKey()), entry.getValue());
            } catch (NumberFormatException e) {
                Log.e(TAG, "Failed to Integer convert: " + entry.getKey());
            }
        }
        return output;
    }


    @Override
    public void resetData() {
        mDataSource.reset();
    }

    @Override
    public boolean hasNewDataToSerialize() {
        return mDataSource.hasNewDataToSerialize();
    }

    @Override
    public String getName() {
        return XML_TAG_SECTION_HEADER_IMSI_PROTECTION_EXEMPTION_CARRIER_MAP;
    }

    @Override
    public int getStoreFileId() {
        // User general store.
        return WifiConfigStore.STORE_FILE_USER_GENERAL;
    }
}
