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

import static com.android.server.wifi.WifiConfigStore.ENCRYPT_CREDENTIALS_CONFIG_STORE_DATA_VERSION;

import android.annotation.Nullable;
import android.content.Context;
import android.net.IpConfiguration;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiConfiguration.NetworkSelectionStatus;
import android.net.wifi.WifiEnterpriseConfig;
import android.os.Process;
import android.util.Log;
import android.util.Pair;

import com.android.server.wifi.util.WifiConfigStoreEncryptionUtil;
import com.android.server.wifi.util.XmlUtil;
import com.android.server.wifi.util.XmlUtil.IpConfigurationXmlUtil;
import com.android.server.wifi.util.XmlUtil.NetworkSelectionStatusXmlUtil;
import com.android.server.wifi.util.XmlUtil.WifiConfigurationXmlUtil;
import com.android.server.wifi.util.XmlUtil.WifiEnterpriseConfigXmlUtil;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * This class performs serialization and parsing of XML data block that contain the list of WiFi
 * network configurations (XML block data inside <NetworkList> tag).
 */
public abstract class NetworkListStoreData implements WifiConfigStore.StoreData {
    private static final String TAG = "NetworkListStoreData";

    private static final String XML_TAG_SECTION_HEADER_NETWORK_LIST = "NetworkList";
    private static final String XML_TAG_SECTION_HEADER_NETWORK = "Network";
    private static final String XML_TAG_SECTION_HEADER_WIFI_CONFIGURATION = "WifiConfiguration";
    private static final String XML_TAG_SECTION_HEADER_NETWORK_STATUS = "NetworkStatus";
    private static final String XML_TAG_SECTION_HEADER_IP_CONFIGURATION = "IpConfiguration";
    private static final String XML_TAG_SECTION_HEADER_WIFI_ENTERPRISE_CONFIGURATION =
            "WifiEnterpriseConfiguration";

    private final Context mContext;

    /**
     * List of saved shared networks visible to all the users to be stored in the store file.
     */
    private List<WifiConfiguration> mConfigurations;

    NetworkListStoreData(Context context) {
        mContext = context;
    }

    @Override
    public void serializeData(XmlSerializer out,
            @Nullable WifiConfigStoreEncryptionUtil encryptionUtil)
            throws XmlPullParserException, IOException {
        serializeNetworkList(out, mConfigurations, encryptionUtil);
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
        mConfigurations = parseNetworkList(in, outerTagDepth, version, encryptionUtil);
    }

    @Override
    public void resetData() {
        mConfigurations = null;
    }

    @Override
    public boolean hasNewDataToSerialize() {
        // always persist.
        return true;
    }

    @Override
    public String getName() {
        return XML_TAG_SECTION_HEADER_NETWORK_LIST;
    }

    public void setConfigurations(List<WifiConfiguration> configs) {
        mConfigurations = configs;
    }

    /**
     * An empty list will be returned if no shared configurations.
     *
     * @return List of {@link WifiConfiguration}
     */
    public List<WifiConfiguration> getConfigurations() {
        if (mConfigurations == null) {
            return new ArrayList<WifiConfiguration>();
        }
        return mConfigurations;
    }

    /**
     * Serialize the list of {@link WifiConfiguration} to an output stream in XML format.
     *
     * @param out The output stream to serialize the data to
     * @param networkList The network list to serialize
     * @param encryptionUtil Instance of {@link WifiConfigStoreEncryptionUtil}
     * @throws XmlPullParserException
     * @throws IOException
     */
    private void serializeNetworkList(XmlSerializer out, List<WifiConfiguration> networkList,
            @Nullable WifiConfigStoreEncryptionUtil encryptionUtil)
            throws XmlPullParserException, IOException {
        if (networkList == null) {
            return;
        }
        // Sort by SSID
        Collections.sort(networkList, Comparator.comparing(a -> a.SSID));
        for (WifiConfiguration network : networkList) {
            serializeNetwork(out, network, encryptionUtil);
        }
    }

    /**
     * Serialize a {@link WifiConfiguration} to an output stream in XML format.
     *
     * @param out The output stream to serialize the data to
     * @param config The network config to serialize
     * @param encryptionUtil Instance of {@link WifiConfigStoreEncryptionUtil}
     * @throws XmlPullParserException
     * @throws IOException
     */
    private void serializeNetwork(XmlSerializer out, WifiConfiguration config,
            @Nullable WifiConfigStoreEncryptionUtil encryptionUtil)
            throws XmlPullParserException, IOException {
        XmlUtil.writeNextSectionStart(out, XML_TAG_SECTION_HEADER_NETWORK);

        // Serialize WifiConfiguration.
        XmlUtil.writeNextSectionStart(out, XML_TAG_SECTION_HEADER_WIFI_CONFIGURATION);
        WifiConfigurationXmlUtil.writeToXmlForConfigStore(out, config, encryptionUtil);
        XmlUtil.writeNextSectionEnd(out, XML_TAG_SECTION_HEADER_WIFI_CONFIGURATION);

        // Serialize network selection status.
        XmlUtil.writeNextSectionStart(out, XML_TAG_SECTION_HEADER_NETWORK_STATUS);
        NetworkSelectionStatusXmlUtil.writeToXml(out, config.getNetworkSelectionStatus());
        XmlUtil.writeNextSectionEnd(out, XML_TAG_SECTION_HEADER_NETWORK_STATUS);

        // Serialize IP configuration.
        XmlUtil.writeNextSectionStart(out, XML_TAG_SECTION_HEADER_IP_CONFIGURATION);
        IpConfigurationXmlUtil.writeToXml(out, config.getIpConfiguration());
        XmlUtil.writeNextSectionEnd(out, XML_TAG_SECTION_HEADER_IP_CONFIGURATION);

        // Serialize enterprise configuration for enterprise networks.
        if (config.enterpriseConfig != null
                && config.enterpriseConfig.getEapMethod() != WifiEnterpriseConfig.Eap.NONE) {
            XmlUtil.writeNextSectionStart(
                    out, XML_TAG_SECTION_HEADER_WIFI_ENTERPRISE_CONFIGURATION);
            WifiEnterpriseConfigXmlUtil.writeToXml(out, config.enterpriseConfig, encryptionUtil);
            XmlUtil.writeNextSectionEnd(out, XML_TAG_SECTION_HEADER_WIFI_ENTERPRISE_CONFIGURATION);
        }

        XmlUtil.writeNextSectionEnd(out, XML_TAG_SECTION_HEADER_NETWORK);
    }

    /**
     * Parse a list of {@link WifiConfiguration} from an input stream in XML format.
     *
     * @param in The input stream to read from
     * @param outerTagDepth The XML tag depth of the outer XML block
     * @param version Version of config store file.
     * @param encryptionUtil Instance of {@link WifiConfigStoreEncryptionUtil}
     * @return List of {@link WifiConfiguration}
     * @throws XmlPullParserException
     * @throws IOException
     */
    private List<WifiConfiguration> parseNetworkList(XmlPullParser in, int outerTagDepth,
            @WifiConfigStore.Version int version,
            @Nullable WifiConfigStoreEncryptionUtil encryptionUtil)
            throws XmlPullParserException, IOException {
        List<WifiConfiguration> networkList = new ArrayList<>();
        while (XmlUtil.gotoNextSectionWithNameOrEnd(in, XML_TAG_SECTION_HEADER_NETWORK,
                outerTagDepth)) {
            // Try/catch only runtime exceptions (like illegal args), any XML/IO exceptions are
            // fatal and should abort the entire loading process.
            try {
                WifiConfiguration config =
                        parseNetwork(in, outerTagDepth + 1, version, encryptionUtil);
                networkList.add(config);
            } catch (RuntimeException e) {
                // Failed to parse this network, skip it.
                Log.e(TAG, "Failed to parse network config. Skipping...", e);
            }
        }
        return networkList;
    }

    /**
     * Parse a {@link WifiConfiguration} from an input stream in XML format.
     *
     * @param in The input stream to read from
     * @param outerTagDepth The XML tag depth of the outer XML block
     * @param version Version of config store file.
     * @param encryptionUtil Instance of {@link WifiConfigStoreEncryptionUtil}
     * @return {@link WifiConfiguration}
     * @throws XmlPullParserException
     * @throws IOException
     */
    private WifiConfiguration parseNetwork(XmlPullParser in, int outerTagDepth,
            @WifiConfigStore.Version int version,
            @Nullable WifiConfigStoreEncryptionUtil encryptionUtil)
            throws XmlPullParserException, IOException {
        Pair<String, WifiConfiguration> parsedConfig = null;
        NetworkSelectionStatus status = null;
        IpConfiguration ipConfiguration = null;
        WifiEnterpriseConfig enterpriseConfig = null;

        String[] headerName = new String[1];
        while (XmlUtil.gotoNextSectionOrEnd(in, headerName, outerTagDepth)) {
            switch (headerName[0]) {
                case XML_TAG_SECTION_HEADER_WIFI_CONFIGURATION:
                    if (parsedConfig != null) {
                        throw new XmlPullParserException("Detected duplicate tag for: "
                                + XML_TAG_SECTION_HEADER_WIFI_CONFIGURATION);
                    }
                    parsedConfig = WifiConfigurationXmlUtil.parseFromXml(in, outerTagDepth + 1,
                            version >= ENCRYPT_CREDENTIALS_CONFIG_STORE_DATA_VERSION,
                            encryptionUtil);
                    break;
                case XML_TAG_SECTION_HEADER_NETWORK_STATUS:
                    if (status != null) {
                        throw new XmlPullParserException("Detected duplicate tag for: "
                                + XML_TAG_SECTION_HEADER_NETWORK_STATUS);
                    }
                    status = NetworkSelectionStatusXmlUtil.parseFromXml(in, outerTagDepth + 1);
                    break;
                case XML_TAG_SECTION_HEADER_IP_CONFIGURATION:
                    if (ipConfiguration != null) {
                        throw new XmlPullParserException("Detected duplicate tag for: "
                                + XML_TAG_SECTION_HEADER_IP_CONFIGURATION);
                    }
                    ipConfiguration = IpConfigurationXmlUtil.parseFromXml(in, outerTagDepth + 1);
                    break;
                case XML_TAG_SECTION_HEADER_WIFI_ENTERPRISE_CONFIGURATION:
                    if (enterpriseConfig != null) {
                        throw new XmlPullParserException("Detected duplicate tag for: "
                                + XML_TAG_SECTION_HEADER_WIFI_ENTERPRISE_CONFIGURATION);
                    }
                    enterpriseConfig =
                            WifiEnterpriseConfigXmlUtil.parseFromXml(in, outerTagDepth + 1,
                            version >= ENCRYPT_CREDENTIALS_CONFIG_STORE_DATA_VERSION,
                            encryptionUtil);
                    break;
                default:
                    Log.w(TAG, "Ignoring unknown tag under " + XML_TAG_SECTION_HEADER_NETWORK
                            + ": " + headerName[0]);
                    break;
            }
        }
        if (parsedConfig == null || parsedConfig.first == null || parsedConfig.second == null) {
            throw new XmlPullParserException("XML parsing of wifi configuration failed");
        }
        String configKeyParsed = parsedConfig.first;
        WifiConfiguration configuration = parsedConfig.second;

        if (configuration.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.SAE)) {
            fixSaeNetworkSecurityBits(configuration);
        }
        // b/153435438: Added to deal with badly formed WifiConfiguration from apps.
        if (configuration.preSharedKey != null && !configuration.needsPreSharedKey()) {
            Log.e(TAG, "preSharedKey set with an invalid KeyMgmt, resetting KeyMgmt to WPA_PSK");
            configuration.allowedKeyManagement.clear();
            configuration.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
            // Recreate configKey to pass the check below.
            configKeyParsed = configuration.getKey();
        }

        String configKeyCalculated = configuration.getKey();
        if (!configKeyParsed.equals(configKeyCalculated)) {
            throw new XmlPullParserException(
                    "Configuration key does not match. Retrieved: " + configKeyParsed
                            + ", Calculated: " + configKeyCalculated);
        }
        // Set creatorUid/creatorName for networks which don't have it set to valid value.
        String creatorName = mContext.getPackageManager().getNameForUid(configuration.creatorUid);
        if (creatorName == null) {
            Log.e(TAG, "Invalid creatorUid for saved network " + configuration.getKey()
                    + ", creatorUid=" + configuration.creatorUid);
            configuration.creatorUid = Process.SYSTEM_UID;
            configuration.creatorName =
                    mContext.getPackageManager().getNameForUid(Process.SYSTEM_UID);
        } else if (!creatorName.equals(configuration.creatorName)) {
            Log.w(TAG, "Invalid creatorName for saved network " + configuration.getKey()
                    + ", creatorUid=" + configuration.creatorUid
                    + ", creatorName=" + configuration.creatorName);
            configuration.creatorName = creatorName;
        }

        configuration.setNetworkSelectionStatus(status);
        configuration.setIpConfiguration(ipConfiguration);
        if (enterpriseConfig != null) {
            configuration.enterpriseConfig = enterpriseConfig;
        }
        return configuration;
    }

    private void fixSaeNetworkSecurityBits(WifiConfiguration saeNetwork) {
        // SAE saved networks Auth Algorithm set to OPEN need to be have this field cleared.
        if (saeNetwork.allowedAuthAlgorithms.get(WifiConfiguration.AuthAlgorithm.OPEN)) {
            saeNetwork.allowedAuthAlgorithms.clear();
        }
        // SAE saved networks Pairwise Cipher with TKIP enabled need to be have this bit
        // cleared.
        if (saeNetwork.allowedPairwiseCiphers.get(WifiConfiguration.PairwiseCipher.TKIP)) {
            saeNetwork.allowedPairwiseCiphers.clear(WifiConfiguration.PairwiseCipher.TKIP);
        }
        // SAE saved networks Protocols with WPA enabled need to be have this bit cleared.
        if (saeNetwork.allowedProtocols.get(WifiConfiguration.Protocol.WPA)) {
            saeNetwork.allowedProtocols.clear(WifiConfiguration.Protocol.WPA);
        }
        // SAE saved networks Group Ciphers with legacy ciphers enabled, need to be have these
        // bits cleared.
        if (saeNetwork.allowedGroupCiphers.get(WifiConfiguration.GroupCipher.WEP40)) {
            saeNetwork.allowedGroupCiphers.clear(WifiConfiguration.GroupCipher.WEP40);
        }
        if (saeNetwork.allowedGroupCiphers.get(WifiConfiguration.GroupCipher.WEP104)) {
            saeNetwork.allowedGroupCiphers.clear(WifiConfiguration.GroupCipher.WEP104);
        }
        if (saeNetwork.allowedGroupCiphers.get(WifiConfiguration.GroupCipher.TKIP)) {
            saeNetwork.allowedGroupCiphers.clear(WifiConfiguration.GroupCipher.TKIP);
        }
        saeNetwork.allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.GCMP_256);
        saeNetwork.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.GCMP_256);
    }
}

