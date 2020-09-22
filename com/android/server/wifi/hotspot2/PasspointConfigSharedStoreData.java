/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server.wifi.hotspot2;

import android.annotation.Nullable;
import android.util.Log;

import com.android.server.wifi.WifiConfigStore;
import com.android.server.wifi.util.WifiConfigStoreEncryptionUtil;
import com.android.server.wifi.util.XmlUtil;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.IOException;

/**
 * Responsible for Passpoint specific configuration store data.  There are two types of
 * configuration data, system wide and user specific.  The system wide configurations are stored
 * in the share store and user specific configurations are store in the user store.
 *
 * Below are the current configuration data for each respective store file, the list will
 * probably grow in the future.
 *
 * Share Store (system wide configurations)
 * - Current provider index - use for assigning provider ID during provider creation, to make
 *                            sure each provider will have an unique ID across all users.
 *
 * User Store (user specific configurations)
 * - Provider list - list of Passpoint provider configurations
 *
 */
public class PasspointConfigSharedStoreData implements WifiConfigStore.StoreData {
    private static final String TAG = "PasspointConfigSharedStoreData";
    private static final String XML_TAG_SECTION_HEADER_PASSPOINT_CONFIG_DATA =
            "PasspointConfigData";
    private static final String XML_TAG_PROVIDER_INDEX = "ProviderIndex";

    private final DataSource mDataSource;

    /**
     * Interface define the data source for the Passpoint configuration store data.
     */
    public interface DataSource {
        /**
         * Retrieve the current provider index.
         *
         * @return long
         */
        long getProviderIndex();

        /**
         * Set the current provider index.
         *
         * @param providerIndex The provider index used for provider creation
         */
        void setProviderIndex(long providerIndex);
    }

    PasspointConfigSharedStoreData(DataSource dataSource) {
        mDataSource = dataSource;
    }

    @Override
    public void serializeData(XmlSerializer out,
            @Nullable WifiConfigStoreEncryptionUtil encryptionUtil)
            throws XmlPullParserException, IOException {
        serializeShareData(out);
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
        deserializeShareData(in, outerTagDepth);
    }

    /**
     * Reset share data (system wide Passpoint configurations).
     */
    @Override
    public void resetData() {
        mDataSource.setProviderIndex(0);
    }

    @Override
    public boolean hasNewDataToSerialize() {
        // always persist.
        return true;
    }

    @Override
    public String getName() {
        return XML_TAG_SECTION_HEADER_PASSPOINT_CONFIG_DATA;
    }

    @Override
    public @WifiConfigStore.StoreFileId int getStoreFileId() {
        // Shared general store.
        return WifiConfigStore.STORE_FILE_SHARED_GENERAL;
    }

    /**
     * Serialize share data (system wide Passpoint configurations) to a XML block.
     *
     * @param out The output stream to serialize data to
     * @throws XmlPullParserException
     * @throws IOException
     */
    private void serializeShareData(XmlSerializer out) throws XmlPullParserException, IOException {
        XmlUtil.writeNextValue(out, XML_TAG_PROVIDER_INDEX, mDataSource.getProviderIndex());
    }

    /**
     * Deserialize share data (system wide Passpoint configurations) from the input stream.
     *
     * @param in            The input stream to read data from
     * @param outerTagDepth The tag depth of the current XML section
     * @throws XmlPullParserException
     * @throws IOException
     */
    private void deserializeShareData(XmlPullParser in, int outerTagDepth)
            throws XmlPullParserException, IOException {
        while (!XmlUtil.isNextSectionEnd(in, outerTagDepth)) {
            String[] valueName = new String[1];
            Object value = XmlUtil.readCurrentValue(in, valueName);
            if (valueName[0] == null) {
                throw new XmlPullParserException("Missing value name");
            }
            switch (valueName[0]) {
                case XML_TAG_PROVIDER_INDEX:
                    mDataSource.setProviderIndex((long) value);
                    break;
                default:
                    Log.w(TAG, "Ignoring unknown value under share store data " + valueName[0]);
                    break;
            }
        }
    }
}


