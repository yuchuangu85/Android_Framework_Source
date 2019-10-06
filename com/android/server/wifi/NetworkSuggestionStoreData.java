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

import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiEnterpriseConfig;
import android.net.wifi.WifiNetworkSuggestion;
import android.os.Process;
import android.util.Log;
import android.util.Pair;

import com.android.internal.util.XmlUtils;
import com.android.server.wifi.WifiNetworkSuggestionsManager.ExtendedWifiNetworkSuggestion;
import com.android.server.wifi.WifiNetworkSuggestionsManager.PerAppInfo;
import com.android.server.wifi.util.XmlUtil;
import com.android.server.wifi.util.XmlUtil.WifiConfigurationXmlUtil;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

/**
 * This class performs serialization and parsing of XML data block that contain the list of WiFi
 * network suggestions.
 */
public class NetworkSuggestionStoreData implements WifiConfigStore.StoreData {
    private static final String TAG = "NetworkSuggestionStoreData";

    private static final String XML_TAG_SECTION_HEADER_NETWORK_SUGGESTION_MAP =
            "NetworkSuggestionMap";
    private static final String XML_TAG_SECTION_HEADER_NETWORK_SUGGESTION_PER_APP =
            "NetworkSuggestionPerApp";
    private static final String XML_TAG_SECTION_HEADER_NETWORK_SUGGESTION = "NetworkSuggestion";
    private static final String XML_TAG_SECTION_HEADER_WIFI_CONFIGURATION = "WifiConfiguration";
    private static final String XML_TAG_SECTION_HEADER_WIFI_ENTERPRISE_CONFIGURATION =
            "WifiEnterpriseConfiguration";
    private static final String XML_TAG_IS_APP_INTERACTION_REQUIRED = "IsAppInteractionRequired";
    private static final String XML_TAG_IS_USER_INTERACTION_REQUIRED = "IsUserInteractionRequired";
    private static final String XML_TAG_SUGGESTOR_UID = "SuggestorUid";
    private static final String XML_TAG_SUGGESTOR_PACKAGE_NAME = "SuggestorPackageName";
    private static final String XML_TAG_SUGGESTOR_HAS_USER_APPROVED = "SuggestorHasUserApproved";
    private static final String XML_TAG_SUGGESTOR_MAX_SIZE = "SuggestorMaxSize";

    /**
     * Interface define the data source for the network suggestions store data.
     */
    public interface DataSource {
        /**
         * Retrieve the network suggestion list from the data source to serialize them to disk.
         *
         * @return Map of package name to {@link PerAppInfo}
         */
        Map<String, PerAppInfo> toSerialize();

        /**
         * Set the network suggestions list in the data source after serializing them from disk.
         *
         * @param networkSuggestions Map of package name to {@link PerAppInfo}
         */
        void fromDeserialized(Map<String, PerAppInfo> networkSuggestions);

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

    public NetworkSuggestionStoreData(DataSource dataSource) {
        mDataSource = dataSource;
    }

    @Override
    public void serializeData(XmlSerializer out)
            throws XmlPullParserException, IOException {
        serializeNetworkSuggestionsMap(out, mDataSource.toSerialize());
    }

    @Override
    public void deserializeData(XmlPullParser in, int outerTagDepth)
            throws XmlPullParserException, IOException {
        // Ignore empty reads.
        if (in == null) {
            return;
        }
        mDataSource.fromDeserialized(parseNetworkSuggestionsMap(in, outerTagDepth));
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
        return XML_TAG_SECTION_HEADER_NETWORK_SUGGESTION_MAP;
    }

    @Override
    public @WifiConfigStore.StoreFileId int getStoreFileId() {
        return WifiConfigStore.STORE_FILE_USER_NETWORK_SUGGESTIONS;
    }

    /**
     * Serialize the map of package name to network suggestions to an output stream in XML format.
     *
     * @throws XmlPullParserException
     * @throws IOException
     */
    private void serializeNetworkSuggestionsMap(
            XmlSerializer out, final Map<String, PerAppInfo> networkSuggestionsMap)
            throws XmlPullParserException, IOException {
        if (networkSuggestionsMap == null) {
            return;
        }
        for (Entry<String, PerAppInfo> entry : networkSuggestionsMap.entrySet()) {
            String packageName = entry.getKey();
            boolean hasUserApproved = entry.getValue().hasUserApproved;
            int maxSize = entry.getValue().maxSize;
            Set<ExtendedWifiNetworkSuggestion> networkSuggestions =
                    entry.getValue().extNetworkSuggestions;
            XmlUtil.writeNextSectionStart(out, XML_TAG_SECTION_HEADER_NETWORK_SUGGESTION_PER_APP);
            XmlUtil.writeNextValue(out, XML_TAG_SUGGESTOR_PACKAGE_NAME, packageName);
            XmlUtil.writeNextValue(out, XML_TAG_SUGGESTOR_HAS_USER_APPROVED, hasUserApproved);
            XmlUtil.writeNextValue(out, XML_TAG_SUGGESTOR_MAX_SIZE, maxSize);
            serializeExtNetworkSuggestions(out, networkSuggestions);
            XmlUtil.writeNextSectionEnd(out, XML_TAG_SECTION_HEADER_NETWORK_SUGGESTION_PER_APP);
        }
    }

    /**
     * Serialize the set of network suggestions to an output stream in XML format.
     *
     * @throws XmlPullParserException
     * @throws IOException
     */
    private void serializeExtNetworkSuggestions(
            XmlSerializer out, final Set<ExtendedWifiNetworkSuggestion> extNetworkSuggestions)
            throws XmlPullParserException, IOException {
        for (ExtendedWifiNetworkSuggestion extNetworkSuggestion : extNetworkSuggestions) {
            serializeNetworkSuggestion(out, extNetworkSuggestion.wns);
        }
    }

    /**
     * Serialize a {@link ExtendedWifiNetworkSuggestion} to an output stream in XML format.
     *
     * @throws XmlPullParserException
     * @throws IOException
     */
    private void serializeNetworkSuggestion(XmlSerializer out,
                                            final WifiNetworkSuggestion suggestion)
            throws XmlPullParserException, IOException {
        XmlUtil.writeNextSectionStart(out, XML_TAG_SECTION_HEADER_NETWORK_SUGGESTION);

        // Serialize WifiConfiguration.
        XmlUtil.writeNextSectionStart(out, XML_TAG_SECTION_HEADER_WIFI_CONFIGURATION);
        WifiConfigurationXmlUtil.writeToXmlForConfigStore(out, suggestion.wifiConfiguration);
        XmlUtil.writeNextSectionEnd(out, XML_TAG_SECTION_HEADER_WIFI_CONFIGURATION);
        // Serialize enterprise configuration for enterprise networks.
        if (suggestion.wifiConfiguration.enterpriseConfig != null
                && suggestion.wifiConfiguration.enterpriseConfig.getEapMethod()
                != WifiEnterpriseConfig.Eap.NONE) {
            XmlUtil.writeNextSectionStart(
                    out, XML_TAG_SECTION_HEADER_WIFI_ENTERPRISE_CONFIGURATION);
            XmlUtil.WifiEnterpriseConfigXmlUtil.writeToXml(
                    out, suggestion.wifiConfiguration.enterpriseConfig);
            XmlUtil.writeNextSectionEnd(out, XML_TAG_SECTION_HEADER_WIFI_ENTERPRISE_CONFIGURATION);
        }

        // Serialize other fields
        XmlUtil.writeNextValue(out, XML_TAG_IS_APP_INTERACTION_REQUIRED,
                suggestion.isAppInteractionRequired);
        XmlUtil.writeNextValue(out, XML_TAG_IS_USER_INTERACTION_REQUIRED,
                suggestion.isUserInteractionRequired);
        XmlUtil.writeNextValue(out, XML_TAG_SUGGESTOR_UID, suggestion.suggestorUid);
        XmlUtil.writeNextValue(out, XML_TAG_SUGGESTOR_PACKAGE_NAME,
                suggestion.suggestorPackageName);

        XmlUtil.writeNextSectionEnd(out, XML_TAG_SECTION_HEADER_NETWORK_SUGGESTION);
    }

    /**
     * Parse a map of package name to network suggestions from an input stream in XML format.
     *
     * @throws XmlPullParserException
     * @throws IOException
     */
    private Map<String, PerAppInfo> parseNetworkSuggestionsMap(XmlPullParser in, int outerTagDepth)
            throws XmlPullParserException, IOException {
        Map<String, PerAppInfo> networkSuggestionsMap = new HashMap<>();
        while (XmlUtil.gotoNextSectionWithNameOrEnd(
                in, XML_TAG_SECTION_HEADER_NETWORK_SUGGESTION_PER_APP, outerTagDepth)) {
            // Try/catch only runtime exceptions (like illegal args), any XML/IO exceptions are
            // fatal and should abort the entire loading process.
            try {
                String packageName =
                        (String) XmlUtil.readNextValueWithName(in, XML_TAG_SUGGESTOR_PACKAGE_NAME);
                boolean hasUserApproved = (boolean) XmlUtil.readNextValueWithName(in,
                        XML_TAG_SUGGESTOR_HAS_USER_APPROVED);
                int maxSize = (int) XmlUtil.readNextValueWithName(in, XML_TAG_SUGGESTOR_MAX_SIZE);
                PerAppInfo perAppInfo = new PerAppInfo(packageName);
                Set<ExtendedWifiNetworkSuggestion> extNetworkSuggestions =
                        parseExtNetworkSuggestions(in, outerTagDepth + 1, perAppInfo);
                perAppInfo.hasUserApproved = hasUserApproved;
                perAppInfo.maxSize = maxSize;
                perAppInfo.extNetworkSuggestions.addAll(extNetworkSuggestions);
                networkSuggestionsMap.put(packageName, perAppInfo);
            } catch (RuntimeException e) {
                // Failed to parse this network, skip it.
                Log.e(TAG, "Failed to parse network suggestion. Skipping...", e);
            }
        }
        return networkSuggestionsMap;
    }

    /**
     * Parse a set of network suggestions from an input stream in XML format.
     *
     * @throws XmlPullParserException
     * @throws IOException
     */
    private Set<ExtendedWifiNetworkSuggestion> parseExtNetworkSuggestions(
            XmlPullParser in, int outerTagDepth, PerAppInfo perAppInfo)
            throws XmlPullParserException, IOException {
        Set<ExtendedWifiNetworkSuggestion> extNetworkSuggestions = new HashSet<>();
        while (XmlUtil.gotoNextSectionWithNameOrEnd(
                in, XML_TAG_SECTION_HEADER_NETWORK_SUGGESTION, outerTagDepth)) {
            // Try/catch only runtime exceptions (like illegal args), any XML/IO exceptions are
            // fatal and should abort the entire loading process.
            try {
                WifiNetworkSuggestion networkSuggestion =
                        parseNetworkSuggestion(in, outerTagDepth + 1);
                extNetworkSuggestions.add(ExtendedWifiNetworkSuggestion.fromWns(
                        networkSuggestion, perAppInfo));
            } catch (RuntimeException e) {
                // Failed to parse this network, skip it.
                Log.e(TAG, "Failed to parse network suggestion. Skipping...", e);
            }
        }
        return extNetworkSuggestions;
    }

    /**
     * Parse a {@link ExtendedWifiNetworkSuggestion} from an input stream in XML format.
     *
     * @throws XmlPullParserException
     * @throws IOException
     */
    private WifiNetworkSuggestion parseNetworkSuggestion(XmlPullParser in, int outerTagDepth)
            throws XmlPullParserException, IOException {
        Pair<String, WifiConfiguration> parsedConfig = null;
        WifiEnterpriseConfig enterpriseConfig = null;
        boolean isAppInteractionRequired = false;
        boolean isUserInteractionRequired = false;
        int suggestorUid = Process.INVALID_UID;
        String suggestorPackageName = null;

        // Loop through and parse out all the elements from the stream within this section.
        while (XmlUtils.nextElementWithin(in, outerTagDepth)) {
            if (in.getAttributeValue(null, "name") != null) {
                // Value elements.
                String[] valueName = new String[1];
                Object value = XmlUtil.readCurrentValue(in, valueName);
                switch (valueName[0]) {
                    case XML_TAG_IS_APP_INTERACTION_REQUIRED:
                        isAppInteractionRequired = (boolean) value;
                        break;
                    case XML_TAG_IS_USER_INTERACTION_REQUIRED:
                        isUserInteractionRequired = (boolean) value;
                        break;
                    case XML_TAG_SUGGESTOR_UID:
                        suggestorUid = (int) value;
                        break;
                    case XML_TAG_SUGGESTOR_PACKAGE_NAME:
                        suggestorPackageName = (String) value;
                        break;
                    default:
                        throw new XmlPullParserException(
                                "Unknown value name found: " + valueName[0]);
                }
            } else {
                String tagName = in.getName();
                if (tagName == null) {
                    throw new XmlPullParserException("Unexpected null under "
                            + XML_TAG_SECTION_HEADER_NETWORK_SUGGESTION);
                }
                switch (tagName) {
                    case XML_TAG_SECTION_HEADER_WIFI_CONFIGURATION:
                        if (parsedConfig != null) {
                            throw new XmlPullParserException("Detected duplicate tag for: "
                                    + XML_TAG_SECTION_HEADER_WIFI_CONFIGURATION);
                        }
                        parsedConfig = WifiConfigurationXmlUtil.parseFromXml(
                                in, outerTagDepth + 1);
                        break;
                    case XML_TAG_SECTION_HEADER_WIFI_ENTERPRISE_CONFIGURATION:
                        if (enterpriseConfig != null) {
                            throw new XmlPullParserException("Detected duplicate tag for: "
                                    + XML_TAG_SECTION_HEADER_WIFI_ENTERPRISE_CONFIGURATION);
                        }
                        enterpriseConfig = XmlUtil.WifiEnterpriseConfigXmlUtil.parseFromXml(
                                in, outerTagDepth + 1);
                        break;
                    default:
                        throw new XmlPullParserException("Unknown tag under "
                                + XML_TAG_SECTION_HEADER_NETWORK_SUGGESTION + ": " + in.getName());
                }
            }
        }
        if (parsedConfig == null || parsedConfig.second == null) {
            throw new XmlPullParserException("XML parsing of wifi configuration failed");
        }
        if (suggestorUid == -1) {
            throw new XmlPullParserException("XML parsing of suggestor uid failed");
        }
        if (suggestorPackageName == null) {
            throw new XmlPullParserException("XML parsing of suggestor package name failed");
        }
        WifiConfiguration wifiConfiguration =  parsedConfig.second;
        if (enterpriseConfig != null) {
            wifiConfiguration.enterpriseConfig = enterpriseConfig;
        }
        return new WifiNetworkSuggestion(
                wifiConfiguration, isAppInteractionRequired, isUserInteractionRequired,
                suggestorUid, suggestorPackageName);
    }
}

