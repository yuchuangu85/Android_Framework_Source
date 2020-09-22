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

package com.android.server.wifi;

import android.annotation.Nullable;
import android.net.InetAddresses;
import android.net.IpConfiguration;
import android.net.IpConfiguration.IpAssignment;
import android.net.IpConfiguration.ProxySettings;
import android.net.LinkAddress;
import android.net.ProxyInfo;
import android.net.RouteInfo;
import android.net.StaticIpConfiguration;
import android.net.Uri;
import android.net.wifi.WifiConfiguration;
import android.util.Log;
import android.util.Pair;

import com.android.server.wifi.util.XmlUtil;
import com.android.server.wifi.util.XmlUtil.IpConfigurationXmlUtil;
import com.android.server.wifi.util.XmlUtil.WifiConfigurationXmlUtil;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Parser for major version 1 of WiFi backup data.
 * Contains whitelists of tags for WifiConfiguration and IpConfiguration sections for each of
 * the minor versions.
 *
 * Overall structure of the major version 1 XML schema:
 * <?xml version='1.0' encoding='utf-8' standalone='yes' ?>
 * <WifiConfigStore>
 *  <float name="Version" value="1.0" />
 *  <NetworkList>
 *   <Network>
 *    <WifiConfiguration>
 *     <string name="ConfigKey">value</string>
 *     <string name="SSID">value</string>
 *     <string name="PreSharedKey" />value</string>
 *     <string-array name="WEPKeys" num="4">
 *      <item value="WifiConfigStoreWep1" />
 *      <item value="WifiConfigStoreWep2" />
 *      <item value="WifiConfigStoreWep3" />
 *      <item value="WifiConfigStoreWep3" />
 *     </string-array>
 *     ... (other supported tag names in minor version 1: "WEPTxKeyIndex", "HiddenSSID",
 *          "RequirePMF", "AllowedKeyMgmt", "AllowedProtocols", "AllowedAuthAlgos",
 *          "AllowedGroupCiphers", "AllowedPairwiseCiphers", "Shared")
 *    </WifiConfiguration>
 *    <IpConfiguration>
 *     <string name="IpAssignment">value</string>
 *     <string name="ProxySettings">value</string>
 *      ... (other supported tag names in minor version 1: "LinkAddress", "LinkPrefixLength",
 *           "GatewayAddress", "DNSServers", "ProxyHost", "ProxyPort", "ProxyPac",
 *           "ProxyExclusionList")
 *    </IpConfiguration>
 *   </Network>
 *   <Network>
 *    ... (format as above)
 *   </Network>
 *  </NetworkList>
 * </WifiConfigStore>
 */
class WifiBackupDataV1Parser implements WifiBackupDataParser {

    private static final String TAG = "WifiBackupDataV1Parser";

    private static final int HIGHEST_SUPPORTED_MINOR_VERSION = 2;

    // List of tags supported for <WifiConfiguration> section in minor version 0
    private static final Set<String> WIFI_CONFIGURATION_MINOR_V0_SUPPORTED_TAGS =
            new HashSet<String>(Arrays.asList(new String[] {
                WifiConfigurationXmlUtil.XML_TAG_CONFIG_KEY,
                WifiConfigurationXmlUtil.XML_TAG_SSID,
                WifiConfigurationXmlUtil.XML_TAG_PRE_SHARED_KEY,
                WifiConfigurationXmlUtil.XML_TAG_WEP_KEYS,
                WifiConfigurationXmlUtil.XML_TAG_WEP_TX_KEY_INDEX,
                WifiConfigurationXmlUtil.XML_TAG_HIDDEN_SSID,
                WifiConfigurationXmlUtil.XML_TAG_REQUIRE_PMF,
                WifiConfigurationXmlUtil.XML_TAG_ALLOWED_KEY_MGMT,
                WifiConfigurationXmlUtil.XML_TAG_ALLOWED_PROTOCOLS,
                WifiConfigurationXmlUtil.XML_TAG_ALLOWED_AUTH_ALGOS,
                WifiConfigurationXmlUtil.XML_TAG_ALLOWED_GROUP_CIPHERS,
                WifiConfigurationXmlUtil.XML_TAG_ALLOWED_PAIRWISE_CIPHERS,
                WifiConfigurationXmlUtil.XML_TAG_SHARED,
            }));

    // List of tags supported for <WifiConfiguration> section in minor version 1
    private static final Set<String> WIFI_CONFIGURATION_MINOR_V1_SUPPORTED_TAGS =
            new HashSet<String>() {{
                addAll(WIFI_CONFIGURATION_MINOR_V0_SUPPORTED_TAGS);
                add(WifiConfigurationXmlUtil.XML_TAG_METERED_OVERRIDE);
            }};

    // List of tags supported for <WifiConfiguration> section in minor version 2
    private static final Set<String> WIFI_CONFIGURATION_MINOR_V2_SUPPORTED_TAGS =
            new HashSet<String>() {{
                addAll(WIFI_CONFIGURATION_MINOR_V1_SUPPORTED_TAGS);
                add(WifiConfigurationXmlUtil.XML_TAG_IS_AUTO_JOIN);
            }};

    // List of tags supported for <IpConfiguration> section in minor version 0 to 2
    private static final Set<String> IP_CONFIGURATION_MINOR_V0_V1_V2_SUPPORTED_TAGS =
            new HashSet<String>(Arrays.asList(new String[] {
                IpConfigurationXmlUtil.XML_TAG_IP_ASSIGNMENT,
                IpConfigurationXmlUtil.XML_TAG_LINK_ADDRESS,
                IpConfigurationXmlUtil.XML_TAG_LINK_PREFIX_LENGTH,
                IpConfigurationXmlUtil.XML_TAG_GATEWAY_ADDRESS,
                IpConfigurationXmlUtil.XML_TAG_DNS_SERVER_ADDRESSES,
                IpConfigurationXmlUtil.XML_TAG_PROXY_SETTINGS,
                IpConfigurationXmlUtil.XML_TAG_PROXY_HOST,
                IpConfigurationXmlUtil.XML_TAG_PROXY_PORT,
                IpConfigurationXmlUtil.XML_TAG_PROXY_EXCLUSION_LIST,
                IpConfigurationXmlUtil.XML_TAG_PROXY_PAC_FILE,
            }));

    @Override
    public List<WifiConfiguration> parseNetworkConfigurationsFromXml(XmlPullParser in,
            int outerTagDepth, int minorVersion) throws XmlPullParserException, IOException {
        // clamp down the minorVersion to the highest one that this parser version supports
        if (minorVersion > HIGHEST_SUPPORTED_MINOR_VERSION) {
            minorVersion = HIGHEST_SUPPORTED_MINOR_VERSION;
        }
        // Find the configuration list section.
        XmlUtil.gotoNextSectionWithName(in, WifiBackupRestore.XML_TAG_SECTION_HEADER_NETWORK_LIST,
                outerTagDepth);
        // Find all the configurations within the configuration list section.
        int networkListTagDepth = outerTagDepth + 1;
        List<WifiConfiguration> configurations = new ArrayList<>();
        while (XmlUtil.gotoNextSectionWithNameOrEnd(
                in, WifiBackupRestore.XML_TAG_SECTION_HEADER_NETWORK, networkListTagDepth)) {
            WifiConfiguration configuration =
                    parseNetworkConfigurationFromXml(in, minorVersion, networkListTagDepth);
            if (configuration != null) {
                Log.v(TAG, "Parsed Configuration: " + configuration.getKey());
                configurations.add(configuration);
            }
        }
        return configurations;
    }

    @Override
    public int getHighestSupportedMinorVersion() {
        return HIGHEST_SUPPORTED_MINOR_VERSION;
    }

    /**
     * Parses the configuration data elements from the provided XML stream to a Configuration.
     *
     * @param in            XmlPullParser instance pointing to the XML stream.
     * @param minorVersion  minor version number parsed from incoming data.
     * @param outerTagDepth depth of the outer tag in the XML document.
     * @return WifiConfiguration object if parsing is successful, null otherwise.
     */
    private WifiConfiguration parseNetworkConfigurationFromXml(XmlPullParser in, int minorVersion,
            int outerTagDepth) throws XmlPullParserException, IOException {
        WifiConfiguration configuration = null;
        int networkTagDepth = outerTagDepth + 1;
        // Retrieve WifiConfiguration object first.
        XmlUtil.gotoNextSectionWithName(
                in, WifiBackupRestore.XML_TAG_SECTION_HEADER_WIFI_CONFIGURATION,
                networkTagDepth);
        int configTagDepth = networkTagDepth + 1;
        configuration = parseWifiConfigurationFromXml(in, configTagDepth, minorVersion);
        if (configuration == null) {
            return null;
        }
        // Now retrieve any IP configuration info.
        XmlUtil.gotoNextSectionWithName(
                in, WifiBackupRestore.XML_TAG_SECTION_HEADER_IP_CONFIGURATION, networkTagDepth);
        IpConfiguration ipConfiguration = parseIpConfigurationFromXml(in, configTagDepth,
                minorVersion);
        configuration.setIpConfiguration(ipConfiguration);
        return configuration;
    }

    /**
     * Helper method to parse the WifiConfiguration object.
     */
    private WifiConfiguration parseWifiConfigurationFromXml(XmlPullParser in,
            int outerTagDepth, int minorVersion) throws XmlPullParserException, IOException {
        Pair<String, WifiConfiguration> parsedConfig =
                parseWifiConfigurationFromXmlInternal(in, outerTagDepth, minorVersion);
        if (parsedConfig == null || parsedConfig.first == null || parsedConfig.second == null) {
            return null;
        }
        String configKeyParsed = parsedConfig.first;
        WifiConfiguration configuration = parsedConfig.second;
        String configKeyCalculated = configuration.getKey();
        if (!configKeyParsed.equals(configKeyCalculated)) {
            // configKey is not part of the SDK. So, we can't expect this to be the same
            // across OEM's. Just log a warning & continue.
            Log.w(TAG, "Configuration key does not match. Retrieved: " + configKeyParsed
                    + ", Calculated: " + configKeyCalculated);
        }
        return configuration;
    }

    /**
     * Helper method to mask out any invalid data in parsed WifiConfiguration.
     *
     * This is a compatibility layer added to the parsing logic to try and weed out any known
     * issues in the backup data format from other OEM's.
     */
    private static void clearAnyKnownIssuesInParsedConfiguration(WifiConfiguration config) {
        /**
         * Fix for b/73987207. Clear any invalid bits in the bitsets.
         */
        // |allowedKeyManagement|
        if (config.allowedKeyManagement.length()
                > WifiConfiguration.KeyMgmt.strings.length) {
            config.allowedKeyManagement.clear(
                    WifiConfiguration.KeyMgmt.strings.length,
                    config.allowedKeyManagement.length());
        }
        // |allowedProtocols|
        if (config.allowedProtocols.length()
                > WifiConfiguration.Protocol.strings.length) {
            config.allowedProtocols.clear(
                    WifiConfiguration.Protocol.strings.length,
                    config.allowedProtocols.length());
        }
        // |allowedAuthAlgorithms|
        if (config.allowedAuthAlgorithms.length()
                > WifiConfiguration.AuthAlgorithm.strings.length) {
            config.allowedAuthAlgorithms.clear(
                    WifiConfiguration.AuthAlgorithm.strings.length,
                    config.allowedAuthAlgorithms.length());
        }
        // |allowedGroupCiphers|
        if (config.allowedGroupCiphers.length()
                > WifiConfiguration.GroupCipher.strings.length) {
            config.allowedGroupCiphers.clear(
                    WifiConfiguration.GroupCipher.strings.length,
                    config.allowedGroupCiphers.length());
        }
        // |allowedPairwiseCiphers|
        if (config.allowedPairwiseCiphers.length()
                > WifiConfiguration.PairwiseCipher.strings.length) {
            config.allowedPairwiseCiphers.clear(
                    WifiConfiguration.PairwiseCipher.strings.length,
                    config.allowedPairwiseCiphers.length());
        }
        // Add any other fixable issues discovered from other OEM's here.
    }

    /**
     * Parses the configuration data elements from the provided XML stream to a
     * WifiConfiguration object.
     * Looping through the tags makes it easy to add elements in the future minor versions if
     * needed. Unsupported elements will be ignored.
     *
     * @param in            XmlPullParser instance pointing to the XML stream.
     * @param outerTagDepth depth of the outer tag in the XML document.
     * @param minorVersion  minor version number parsed from incoming data.
     * @return Pair<Config key, WifiConfiguration object> if parsing is successful, null otherwise.
     */
    private static Pair<String, WifiConfiguration> parseWifiConfigurationFromXmlInternal(
            XmlPullParser in, int outerTagDepth, int minorVersion)
            throws XmlPullParserException, IOException {
        WifiConfiguration configuration = new WifiConfiguration();
        String configKeyInData = null;
        Set<String> supportedTags = getSupportedWifiConfigurationTags(minorVersion);

        // Loop through and parse out all the elements from the stream within this section.
        while (!XmlUtil.isNextSectionEnd(in, outerTagDepth)) {
            String[] valueName = new String[1];
            Object value = XmlUtil.readCurrentValue(in, valueName);
            String tagName = valueName[0];
            if (tagName == null) {
                throw new XmlPullParserException("Missing value name");
            }

            // ignore the tags that are not supported up until the current minor version
            if (!supportedTags.contains(tagName)) {
                Log.w(TAG, "Unsupported tag + \"" + tagName + "\" found in <WifiConfiguration>"
                        + " section, ignoring.");
                continue;
            }

            // note: the below switch case list should contain all tags supported up until the
            // highest minor version supported by this parser
            switch (tagName) {
                case WifiConfigurationXmlUtil.XML_TAG_CONFIG_KEY:
                    configKeyInData = (String) value;
                    break;
                case WifiConfigurationXmlUtil.XML_TAG_SSID:
                    configuration.SSID = (String) value;
                    break;
                case WifiConfigurationXmlUtil.XML_TAG_PRE_SHARED_KEY:
                    configuration.preSharedKey = (String) value;
                    break;
                case WifiConfigurationXmlUtil.XML_TAG_WEP_KEYS:
                    populateWepKeysFromXmlValue(value, configuration.wepKeys);
                    break;
                case WifiConfigurationXmlUtil.XML_TAG_WEP_TX_KEY_INDEX:
                    configuration.wepTxKeyIndex = (int) value;
                    break;
                case WifiConfigurationXmlUtil.XML_TAG_HIDDEN_SSID:
                    configuration.hiddenSSID = (boolean) value;
                    break;
                case WifiConfigurationXmlUtil.XML_TAG_REQUIRE_PMF:
                    configuration.requirePmf = (boolean) value;
                    break;
                case WifiConfigurationXmlUtil.XML_TAG_ALLOWED_KEY_MGMT:
                    byte[] allowedKeyMgmt = (byte[]) value;
                    configuration.allowedKeyManagement = BitSet.valueOf(allowedKeyMgmt);
                    break;
                case WifiConfigurationXmlUtil.XML_TAG_ALLOWED_PROTOCOLS:
                    byte[] allowedProtocols = (byte[]) value;
                    configuration.allowedProtocols = BitSet.valueOf(allowedProtocols);
                    break;
                case WifiConfigurationXmlUtil.XML_TAG_ALLOWED_AUTH_ALGOS:
                    byte[] allowedAuthAlgorithms = (byte[]) value;
                    configuration.allowedAuthAlgorithms = BitSet.valueOf(allowedAuthAlgorithms);
                    break;
                case WifiConfigurationXmlUtil.XML_TAG_ALLOWED_GROUP_CIPHERS:
                    byte[] allowedGroupCiphers = (byte[]) value;
                    configuration.allowedGroupCiphers = BitSet.valueOf(allowedGroupCiphers);
                    break;
                case WifiConfigurationXmlUtil.XML_TAG_ALLOWED_PAIRWISE_CIPHERS:
                    byte[] allowedPairwiseCiphers = (byte[]) value;
                    configuration.allowedPairwiseCiphers =
                            BitSet.valueOf(allowedPairwiseCiphers);
                    break;
                case WifiConfigurationXmlUtil.XML_TAG_SHARED:
                    configuration.shared = (boolean) value;
                    break;
                case WifiConfigurationXmlUtil.XML_TAG_METERED_OVERRIDE:
                    configuration.meteredOverride = (int) value;
                    break;
                case WifiConfigurationXmlUtil.XML_TAG_IS_AUTO_JOIN:
                    configuration.allowAutojoin = (boolean) value;
                    break;
                default:
                    // should never happen, since other tags are filtered out earlier
                    throw new XmlPullParserException(
                            "Unknown value name found: " + valueName[0]);
            }
        }
        clearAnyKnownIssuesInParsedConfiguration(configuration);
        return Pair.create(configKeyInData, configuration);
    }

    /**
     * Returns a set of supported tags of <WifiConfiguration> element for all minor versions of
     * this major version up to and including the specified minorVersion (only adding tags is
     * supported in minor versions, removal or changing the meaning of tags requires bumping
     * the major version and reseting the minor to 0).
     *
     * @param minorVersion  minor version number parsed from incoming data.
     */
    private static Set<String> getSupportedWifiConfigurationTags(int minorVersion) {
        switch (minorVersion) {
            case 0:
                return WIFI_CONFIGURATION_MINOR_V0_SUPPORTED_TAGS;
            case 1:
                return WIFI_CONFIGURATION_MINOR_V1_SUPPORTED_TAGS;
            case 2:
                return WIFI_CONFIGURATION_MINOR_V2_SUPPORTED_TAGS;
            default:
                Log.e(TAG, "Invalid minorVersion: " + minorVersion);
                return Collections.<String>emptySet();
        }
    }

    /**
     * Populate wepKeys array elements only if they were non-empty in the backup data.
     *
     * @throws XmlPullParserException if parsing errors occur.
     */
    private static void populateWepKeysFromXmlValue(Object value, String[] wepKeys)
            throws XmlPullParserException, IOException {
        String[] wepKeysInData = (String[]) value;
        if (wepKeysInData == null) {
            return;
        }
        if (wepKeysInData.length != wepKeys.length) {
            throw new XmlPullParserException(
                    "Invalid Wep Keys length: " + wepKeysInData.length);
        }
        for (int i = 0; i < wepKeys.length; i++) {
            if (wepKeysInData[i].isEmpty()) {
                wepKeys[i] = null;
            } else {
                wepKeys[i] = wepKeysInData[i];
            }
        }
    }

    private static List<String> parseProxyExclusionListString(
            @Nullable String exclusionListString) {
        if (exclusionListString == null) {
            return Collections.emptyList();
        } else {
            return Arrays.asList(exclusionListString.toLowerCase(Locale.ROOT).split(","));
        }
    }

    /**
     * Parses the IP configuration data elements from the provided XML stream to an
     * IpConfiguration object.
     *
     * @param in            XmlPullParser instance pointing to the XML stream.
     * @param outerTagDepth depth of the outer tag in the XML document.
     * @param minorVersion  minor version number parsed from incoming data.
     * @return IpConfiguration object if parsing is successful, null otherwise.
     */
    private static IpConfiguration parseIpConfigurationFromXml(XmlPullParser in,
            int outerTagDepth, int minorVersion) throws XmlPullParserException, IOException {
        // First parse *all* of the tags in <IpConfiguration> section
        Set<String> supportedTags = getSupportedIpConfigurationTags(minorVersion);

        String ipAssignmentString = null;
        String linkAddressString = null;
        Integer linkPrefixLength = null;
        String gatewayAddressString = null;
        String[] dnsServerAddressesString = null;
        String proxySettingsString = null;
        String proxyHost = null;
        int proxyPort = -1;
        String proxyExclusionList = null;
        String proxyPacFile = null;

        // Loop through and parse out all the elements from the stream within this section.
        while (!XmlUtil.isNextSectionEnd(in, outerTagDepth)) {
            String[] valueName = new String[1];
            Object value = XmlUtil.readCurrentValue(in, valueName);
            String tagName = valueName[0];
            if (tagName == null) {
                throw new XmlPullParserException("Missing value name");
            }

            // ignore the tags that are not supported up until the current minor version
            if (!supportedTags.contains(tagName)) {
                Log.w(TAG, "Unsupported tag + \"" + tagName + "\" found in <IpConfiguration>"
                        + " section, ignoring.");
                continue;
            }

            // note: the below switch case list should contain all tags supported up until the
            // highest minor version supported by this parser
            // should any tags be added in next minor versions, conditional processing of them
            // also needs to be added in the below code (processing into IpConfiguration object)
            switch (tagName) {
                case IpConfigurationXmlUtil.XML_TAG_IP_ASSIGNMENT:
                    ipAssignmentString = (String) value;
                    break;
                case IpConfigurationXmlUtil.XML_TAG_LINK_ADDRESS:
                    linkAddressString = (String) value;
                    break;
                case IpConfigurationXmlUtil.XML_TAG_LINK_PREFIX_LENGTH:
                    linkPrefixLength = (Integer) value;
                    break;
                case IpConfigurationXmlUtil.XML_TAG_GATEWAY_ADDRESS:
                    gatewayAddressString = (String) value;
                    break;
                case IpConfigurationXmlUtil.XML_TAG_DNS_SERVER_ADDRESSES:
                    dnsServerAddressesString = (String[]) value;
                    break;
                case IpConfigurationXmlUtil.XML_TAG_PROXY_SETTINGS:
                    proxySettingsString = (String) value;
                    break;
                case IpConfigurationXmlUtil.XML_TAG_PROXY_HOST:
                    proxyHost = (String) value;
                    break;
                case IpConfigurationXmlUtil.XML_TAG_PROXY_PORT:
                    proxyPort = (int) value;
                    break;
                case IpConfigurationXmlUtil.XML_TAG_PROXY_EXCLUSION_LIST:
                    proxyExclusionList = (String) value;
                    break;
                case IpConfigurationXmlUtil.XML_TAG_PROXY_PAC_FILE:
                    proxyPacFile = (String) value;
                    break;
                default:
                    // should never happen, since other tags are filtered out earlier
                    throw new XmlPullParserException(
                            "Unknown value name found: " + valueName[0]);
            }
        }

        // Now process the values into IpConfiguration object
        IpConfiguration ipConfiguration = new IpConfiguration();
        if (ipAssignmentString == null) {
            throw new XmlPullParserException("IpAssignment was missing in IpConfiguration section");
        }
        IpAssignment ipAssignment = IpAssignment.valueOf(ipAssignmentString);
        ipConfiguration.setIpAssignment(ipAssignment);
        switch (ipAssignment) {
            case STATIC:
                StaticIpConfiguration.Builder builder = new StaticIpConfiguration.Builder();
                if (linkAddressString != null && linkPrefixLength != null) {
                    LinkAddress linkAddress = new LinkAddress(
                            InetAddresses.parseNumericAddress(linkAddressString), linkPrefixLength);
                    if (linkAddress.getAddress() instanceof Inet4Address) {
                        builder.setIpAddress(linkAddress);
                    } else {
                        Log.w(TAG, "Non-IPv4 address: " + linkAddress);
                    }
                }
                if (gatewayAddressString != null) {
                    InetAddress gateway = InetAddresses.parseNumericAddress(gatewayAddressString);
                    RouteInfo route = new RouteInfo(null, gateway, null, RouteInfo.RTN_UNICAST);
                    if (route.isDefaultRoute()
                            && route.getDestination().getAddress() instanceof Inet4Address) {
                        builder.setGateway(gateway);
                    } else {
                        Log.w(TAG, "Non-IPv4 default route: " + route);
                    }
                }
                if (dnsServerAddressesString != null) {
                    List<InetAddress> dnsServerAddresses = new ArrayList<>();
                    for (String dnsServerAddressString : dnsServerAddressesString) {
                        InetAddress dnsServerAddress =
                                InetAddresses.parseNumericAddress(dnsServerAddressString);
                        dnsServerAddresses.add(dnsServerAddress);
                    }
                    builder.setDnsServers(dnsServerAddresses);
                }
                ipConfiguration.setStaticIpConfiguration(builder.build());
                break;
            case DHCP:
            case UNASSIGNED:
                break;
            default:
                throw new XmlPullParserException("Unknown ip assignment type: " + ipAssignment);
        }

        // Process the proxy settings next
        if (proxySettingsString == null) {
            throw new XmlPullParserException("ProxySettings was missing in"
                    + " IpConfiguration section");
        }
        ProxySettings proxySettings = ProxySettings.valueOf(proxySettingsString);
        ipConfiguration.setProxySettings(proxySettings);
        switch (proxySettings) {
            case STATIC:
                if (proxyHost == null) {
                    throw new XmlPullParserException("ProxyHost was missing in"
                            + " IpConfiguration section");
                }
                if (proxyPort == -1) {
                    throw new XmlPullParserException("ProxyPort was missing in"
                            + " IpConfiguration section");
                }
                if (proxyExclusionList == null) {
                    throw new XmlPullParserException("ProxyExclusionList was missing in"
                            + " IpConfiguration section");
                }
                ipConfiguration.setHttpProxy(
                        ProxyInfo.buildDirectProxy(
                                proxyHost, proxyPort,
                                parseProxyExclusionListString(proxyExclusionList)));
                break;
            case PAC:
                if (proxyPacFile == null) {
                    throw new XmlPullParserException("ProxyPac was missing in"
                            + " IpConfiguration section");
                }
                ipConfiguration.setHttpProxy(
                        ProxyInfo.buildPacProxy(Uri.parse(proxyPacFile)));
                break;
            case NONE:
            case UNASSIGNED:
                break;
            default:
                throw new XmlPullParserException(
                        "Unknown proxy settings type: " + proxySettings);
        }

        return ipConfiguration;
    }

    /**
     * Returns a set of supported tags of <IpConfiguration> element for all minor versions of
     * this major version up to and including the specified minorVersion (only adding tags is
     * supported in minor versions, removal or changing the meaning of tags requires bumping
     * the major version and reseting the minor to 0).
     *
     * @param minorVersion  minor version number parsed from incoming data.
     */
    private static Set<String> getSupportedIpConfigurationTags(int minorVersion) {
        switch (minorVersion) {
            case 0:
            case 1:
            case 2:
                return IP_CONFIGURATION_MINOR_V0_V1_V2_SUPPORTED_TAGS;
            default:
                Log.e(TAG, "Invalid minorVersion: " + minorVersion);
                return Collections.<String>emptySet();
        }
    }
}
