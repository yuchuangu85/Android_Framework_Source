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

import android.content.Context;
import android.net.IpConfiguration;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiConfiguration.NetworkSelectionStatus;
import android.net.wifi.WifiEnterpriseConfig;
import android.os.Process;
import android.util.Log;
import android.util.Pair;

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
import java.util.List;

/**
 * This class performs serialization and parsing of XML data block that contain the list of WiFi
 * network configurations (XML block data inside <NetworkList> tag).
 */
public class NetworkListStoreData implements WifiConfigStore.StoreData {
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
     * List of saved shared networks visible to all the users to be stored in the shared store file.
     */
    private List<WifiConfiguration> mSharedConfigurations;
    /**
     * List of saved private networks only visible to the current user to be stored in the user
     * specific store file.
     */
    private List<WifiConfiguration> mUserConfigurations;

    NetworkListStoreData(Context context) {
        mContext = context;
    }

    @Override
    public void serializeData(XmlSerializer out, boolean shared)
            throws XmlPullParserException, IOException {
        if (shared) {
            serializeNetworkList(out, mSharedConfigurations);
        } else {
            serializeNetworkList(out, mUserConfigurations);
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
            mSharedConfigurations = parseNetworkList(in, outerTagDepth);
        } else {
            mUserConfigurations = parseNetworkList(in, outerTagDepth);
        }
    }

    @Override
    public void resetData(boolean shared) {
        if (shared) {
            mSharedConfigurations = null;
        } else {
            mUserConfigurations = null;
        }
    }

    @Override
    public String getName() {
        return XML_TAG_SECTION_HEADER_NETWORK_LIST;
    }

    @Override
    public boolean supportShareData() {
        return true;
    }

    public void setSharedConfigurations(List<WifiConfiguration> configs) {
        mSharedConfigurations = configs;
    }

    /**
     * An empty list will be returned if no shared configurations.
     *
     * @return List of {@link WifiConfiguration}
     */
    public List<WifiConfiguration> getSharedConfigurations() {
        if (mSharedConfigurations == null) {
            return new ArrayList<WifiConfiguration>();
        }
        return mSharedConfigurations;
    }

    public void setUserConfigurations(List<WifiConfiguration> configs) {
        mUserConfigurations = configs;
    }

    /**
     * An empty list will be returned if no user configurations.
     *
     * @return List of {@link WifiConfiguration}
     */
    public List<WifiConfiguration> getUserConfigurations() {
        if (mUserConfigurations == null) {
            return new ArrayList<WifiConfiguration>();
        }
        return mUserConfigurations;
    }

    /**
     * Serialize the list of {@link WifiConfiguration} to an output stream in XML format.
     *
     * @param out The output stream to serialize the data to
     * @param networkList The network list to serialize
     * @throws XmlPullParserException
     * @throws IOException
     */
    private void serializeNetworkList(XmlSerializer out, List<WifiConfiguration> networkList)
            throws XmlPullParserException, IOException {
        if (networkList == null) {
            return;
        }
        for (WifiConfiguration network : networkList) {
            serializeNetwork(out, network);
        }
    }

    /**
     * Serialize a {@link WifiConfiguration} to an output stream in XML format.
     * @param out
     * @param config
     * @throws XmlPullParserException
     * @throws IOException
     */
    private void serializeNetwork(XmlSerializer out, WifiConfiguration config)
            throws XmlPullParserException, IOException {
        XmlUtil.writeNextSectionStart(out, XML_TAG_SECTION_HEADER_NETWORK);

        // Serialize WifiConfiguration.
        XmlUtil.writeNextSectionStart(out, XML_TAG_SECTION_HEADER_WIFI_CONFIGURATION);
        WifiConfigurationXmlUtil.writeToXmlForConfigStore(out, config);
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
            WifiEnterpriseConfigXmlUtil.writeToXml(out, config.enterpriseConfig);
            XmlUtil.writeNextSectionEnd(out, XML_TAG_SECTION_HEADER_WIFI_ENTERPRISE_CONFIGURATION);
        }

        XmlUtil.writeNextSectionEnd(out, XML_TAG_SECTION_HEADER_NETWORK);
    }

    /**
     * Parse a list of {@link WifiConfiguration} from an input stream in XML format.
     *
     * @param in The input stream to read from
     * @param outerTagDepth The XML tag depth of the outer XML block
     * @return List of {@link WifiConfiguration}
     * @throws XmlPullParserException
     * @throws IOException
     */
    private List<WifiConfiguration> parseNetworkList(XmlPullParser in, int outerTagDepth)
            throws XmlPullParserException, IOException {
        List<WifiConfiguration> networkList = new ArrayList<>();
        while (XmlUtil.gotoNextSectionWithNameOrEnd(in, XML_TAG_SECTION_HEADER_NETWORK,
                outerTagDepth)) {
            // Try/catch only runtime exceptions (like illegal args), any XML/IO exceptions are
            // fatal and should abort the entire loading process.
            try {
                WifiConfiguration config = parseNetwork(in, outerTagDepth + 1);
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
     * @return {@link WifiConfiguration}
     * @throws XmlPullParserException
     * @throws IOException
     */
    private WifiConfiguration parseNetwork(XmlPullParser in, int outerTagDepth)
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
                    parsedConfig = WifiConfigurationXmlUtil.parseFromXml(in, outerTagDepth + 1);
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
                            WifiEnterpriseConfigXmlUtil.parseFromXml(in, outerTagDepth + 1);
                    break;
                default:
                    throw new XmlPullParserException("Unknown tag under "
                            + XML_TAG_SECTION_HEADER_NETWORK + ": " + headerName[0]);
            }
        }
        if (parsedConfig == null || parsedConfig.first == null || parsedConfig.second == null) {
            throw new XmlPullParserException("XML parsing of wifi configuration failed");
        }
        String configKeyParsed = parsedConfig.first;
        WifiConfiguration configuration = parsedConfig.second;
        String configKeyCalculated = configuration.configKey();
        if (!configKeyParsed.equals(configKeyCalculated)) {
            throw new XmlPullParserException(
                    "Configuration key does not match. Retrieved: " + configKeyParsed
                            + ", Calculated: " + configKeyCalculated);
        }
        // Set creatorUid/creatorName for networks which don't have it set to valid value.
        String creatorName = mContext.getPackageManager().getNameForUid(configuration.creatorUid);
        if (creatorName == null) {
            Log.e(TAG, "Invalid creatorUid for saved network " + configuration.configKey()
                    + ", creatorUid=" + configuration.creatorUid);
            configuration.creatorUid = Process.SYSTEM_UID;
            configuration.creatorName =
                    mContext.getPackageManager().getNameForUid(Process.SYSTEM_UID);
        } else if (!creatorName.equals(configuration.creatorName)) {
            Log.w(TAG, "Invalid creatorName for saved network " + configuration.configKey()
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
}

