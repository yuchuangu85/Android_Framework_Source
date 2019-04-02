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

package com.android.server.wifi.hotspot2;

import android.net.wifi.hotspot2.PasspointConfiguration;
import android.text.TextUtils;

import com.android.internal.util.XmlUtils;
import com.android.server.wifi.SIMAccessor;
import com.android.server.wifi.WifiConfigStore;
import com.android.server.wifi.WifiKeyStore;
import com.android.server.wifi.util.XmlUtil;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

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
public class PasspointConfigStoreData implements WifiConfigStore.StoreData {
    private static final String XML_TAG_SECTION_HEADER_PASSPOINT_CONFIG_DATA =
            "PasspointConfigData";
    private static final String XML_TAG_SECTION_HEADER_PASSPOINT_PROVIDER_LIST =
            "ProviderList";
    private static final String XML_TAG_SECTION_HEADER_PASSPOINT_PROVIDER =
            "Provider";
    private static final String XML_TAG_SECTION_HEADER_PASSPOINT_CONFIGURATION =
            "Configuration";

    private static final String XML_TAG_PROVIDER_ID = "ProviderID";
    private static final String XML_TAG_CREATOR_UID = "CreatorUID";
    private static final String XML_TAG_CA_CERTIFICATE_ALIAS = "CaCertificateAlias";
    private static final String XML_TAG_CLIENT_CERTIFICATE_ALIAS = "ClientCertificateAlias";
    private static final String XML_TAG_CLIENT_PRIVATE_KEY_ALIAS = "ClientPrivateKeyAlias";

    private static final String XML_TAG_PROVIDER_INDEX = "ProviderIndex";
    private static final String XML_TAG_HAS_EVER_CONNECTED = "HasEverConnected";

    private final WifiKeyStore mKeyStore;
    private final SIMAccessor mSimAccessor;
    private final DataSource mDataSource;

    /**
     * Interface define the data source for the Passpoint configuration store data.
     */
    public interface DataSource {
        /**
         * Retrieve the provider list from the data source.
         *
         * @return List of {@link PasspointProvider}
         */
        List<PasspointProvider> getProviders();

        /**
         * Set the provider list in the data source.
         *
         * @param providers The list of providers
         */
        void setProviders(List<PasspointProvider> providers);

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

    PasspointConfigStoreData(WifiKeyStore keyStore, SIMAccessor simAccessor,
            DataSource dataSource) {
        mKeyStore = keyStore;
        mSimAccessor = simAccessor;
        mDataSource = dataSource;
    }

    @Override
    public void serializeData(XmlSerializer out, boolean shared)
            throws XmlPullParserException, IOException {
        if (shared) {
            serializeShareData(out);
        } else {
            serializeUserData(out);
        }
    }

    @Override
    public void deserializeData(XmlPullParser in, int outerTagDepth, boolean shared)
            throws XmlPullParserException, IOException {
        if (shared) {
            deserializeShareData(in, outerTagDepth);
        } else {
            deserializeUserData(in, outerTagDepth);
        }
    }

    @Override
    public void resetData(boolean shared) {
        if (shared) {
            resetShareData();
        } else {
            resetUserData();
        }
    }

    @Override
    public String getName() {
        return XML_TAG_SECTION_HEADER_PASSPOINT_CONFIG_DATA;
    }

    @Override
    public boolean supportShareData() {
        return true;
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
     * Serialize user data (user specific Passpoint configurations) to a XML block.
     *
     * @param out The output stream to serialize data to
     * @throws XmlPullParserException
     * @throws IOException
     */
    private void serializeUserData(XmlSerializer out) throws XmlPullParserException, IOException {
        serializeProviderList(out, mDataSource.getProviders());
    }

    /**
     * Serialize the list of Passpoint providers from the data source to a XML block.
     *
     * @param out The output stream to serialize data to
     * @param providerList The list of providers to serialize
     * @throws XmlPullParserException
     * @throws IOException
     */
    private void serializeProviderList(XmlSerializer out, List<PasspointProvider> providerList)
            throws XmlPullParserException, IOException {
        if (providerList == null) {
            return;
        }
        XmlUtil.writeNextSectionStart(out, XML_TAG_SECTION_HEADER_PASSPOINT_PROVIDER_LIST);
        for (PasspointProvider provider : providerList) {
            serializeProvider(out, provider);
        }
        XmlUtil.writeNextSectionEnd(out, XML_TAG_SECTION_HEADER_PASSPOINT_PROVIDER_LIST);
    }

    /**
     * Serialize a Passpoint provider to a XML block.
     *
     * @param out The output stream to serialize data to
     * @param provider The provider to serialize
     * @throws XmlPullParserException
     * @throws IOException
     */
    private void serializeProvider(XmlSerializer out, PasspointProvider provider)
            throws XmlPullParserException, IOException {
        XmlUtil.writeNextSectionStart(out, XML_TAG_SECTION_HEADER_PASSPOINT_PROVIDER);
        XmlUtil.writeNextValue(out, XML_TAG_PROVIDER_ID, provider.getProviderId());
        XmlUtil.writeNextValue(out, XML_TAG_CREATOR_UID, provider.getCreatorUid());
        XmlUtil.writeNextValue(out, XML_TAG_CA_CERTIFICATE_ALIAS,
                provider.getCaCertificateAlias());
        XmlUtil.writeNextValue(out, XML_TAG_CLIENT_CERTIFICATE_ALIAS,
                provider.getClientCertificateAlias());
        XmlUtil.writeNextValue(out, XML_TAG_CLIENT_PRIVATE_KEY_ALIAS,
                provider.getClientPrivateKeyAlias());
        XmlUtil.writeNextValue(out, XML_TAG_HAS_EVER_CONNECTED, provider.getHasEverConnected());
        if (provider.getConfig() != null) {
            XmlUtil.writeNextSectionStart(out, XML_TAG_SECTION_HEADER_PASSPOINT_CONFIGURATION);
            PasspointXmlUtils.serializePasspointConfiguration(out, provider.getConfig());
            XmlUtil.writeNextSectionEnd(out, XML_TAG_SECTION_HEADER_PASSPOINT_CONFIGURATION);
        }
        XmlUtil.writeNextSectionEnd(out, XML_TAG_SECTION_HEADER_PASSPOINT_PROVIDER);
    }

    /**
     * Deserialize share data (system wide Passpoint configurations) from the input stream.
     *
     * @param in The input stream to read data from
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
                    throw new XmlPullParserException("Unknown value under share store data "
                            + valueName[0]);
            }
        }
    }

    /**
     * Deserialize user data (user specific Passpoint configurations) from the input stream.
     *
     * @param in The input stream to read data from
     * @param outerTagDepth The tag depth of the current XML section
     * @throws XmlPullParserException
     * @throws IOException
     */
    private void deserializeUserData(XmlPullParser in, int outerTagDepth)
            throws XmlPullParserException, IOException {
        String[] headerName = new String[1];
        while (XmlUtil.gotoNextSectionOrEnd(in, headerName, outerTagDepth)) {
            switch (headerName[0]) {
                case XML_TAG_SECTION_HEADER_PASSPOINT_PROVIDER_LIST:
                    mDataSource.setProviders(deserializeProviderList(in, outerTagDepth + 1));
                    break;
                default:
                    throw new XmlPullParserException("Unknown Passpoint user store data "
                            + headerName[0]);
            }
        }
    }

    /**
     * Deserialize a list of Passpoint providers from the input stream.
     *
     * @param in The input stream to read data form
     * @param outerTagDepth The tag depth of the current XML section
     * @return List of {@link PasspointProvider}
     * @throws XmlPullParserException
     * @throws IOException
     */
    private List<PasspointProvider> deserializeProviderList(XmlPullParser in, int outerTagDepth)
            throws XmlPullParserException, IOException {
        List<PasspointProvider> providerList = new ArrayList<>();
        while (XmlUtil.gotoNextSectionWithNameOrEnd(in, XML_TAG_SECTION_HEADER_PASSPOINT_PROVIDER,
                outerTagDepth)) {
            providerList.add(deserializeProvider(in, outerTagDepth + 1));
        }
        return providerList;
    }

    /**
     * Deserialize a Passpoint provider from the input stream.
     *
     * @param in The input stream to read data from
     * @param outerTagDepth The tag depth of the current XML section
     * @return {@link PasspointProvider}
     * @throws XmlPullParserException
     * @throws IOException
     */
    private PasspointProvider deserializeProvider(XmlPullParser in, int outerTagDepth)
            throws XmlPullParserException, IOException {
        long providerId = Long.MIN_VALUE;
        int creatorUid = Integer.MIN_VALUE;
        String caCertificateAlias = null;
        String clientCertificateAlias = null;
        String clientPrivateKeyAlias = null;
        boolean hasEverConnected = false;
        PasspointConfiguration config = null;
        while (XmlUtils.nextElementWithin(in, outerTagDepth)) {
            if (in.getAttributeValue(null, "name") != null) {
                // Value elements.
                String[] name = new String[1];
                Object value = XmlUtil.readCurrentValue(in, name);
                switch (name[0]) {
                    case XML_TAG_PROVIDER_ID:
                        providerId = (long) value;
                        break;
                    case XML_TAG_CREATOR_UID:
                        creatorUid = (int) value;
                        break;
                    case XML_TAG_CA_CERTIFICATE_ALIAS:
                        caCertificateAlias = (String) value;
                        break;
                    case XML_TAG_CLIENT_CERTIFICATE_ALIAS:
                        clientCertificateAlias = (String) value;
                        break;
                    case XML_TAG_CLIENT_PRIVATE_KEY_ALIAS:
                        clientPrivateKeyAlias = (String) value;
                        break;
                    case XML_TAG_HAS_EVER_CONNECTED:
                        hasEverConnected = (boolean) value;
                        break;
                }
            } else {
                if (!TextUtils.equals(in.getName(),
                        XML_TAG_SECTION_HEADER_PASSPOINT_CONFIGURATION)) {
                    throw new XmlPullParserException("Unexpected section under Provider: "
                            + in.getName());
                }
                config = PasspointXmlUtils.deserializePasspointConfiguration(in,
                        outerTagDepth + 1);
            }
        }
        if (providerId == Long.MIN_VALUE) {
            throw new XmlPullParserException("Missing provider ID");
        }
        if (config == null) {
            throw new XmlPullParserException("Missing Passpoint configuration");
        }
        return new PasspointProvider(config, mKeyStore, mSimAccessor, providerId, creatorUid,
                caCertificateAlias, clientCertificateAlias, clientPrivateKeyAlias,
                hasEverConnected);
    }

    /**
     * Reset share data (system wide Passpoint configurations).
     */
    private void resetShareData() {
        mDataSource.setProviderIndex(0);
    }

    /**
     * Reset user data (user specific Passpoint configurations).
     */
    private void resetUserData() {
        mDataSource.setProviders(new ArrayList<PasspointProvider>());
    }
}

