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

import static com.android.server.wifi.WifiConfigStore.ENCRYPT_CREDENTIALS_CONFIG_STORE_DATA_VERSION;

import android.annotation.Nullable;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiEnterpriseConfig;
import android.net.wifi.WifiNetworkSuggestion;
import android.net.wifi.hotspot2.PasspointConfiguration;
import android.os.Process;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.util.Pair;

import com.android.server.wifi.WifiNetworkSuggestionsManager.ExtendedWifiNetworkSuggestion;
import com.android.server.wifi.WifiNetworkSuggestionsManager.PerAppInfo;
import com.android.server.wifi.hotspot2.PasspointXmlUtils;
import com.android.server.wifi.util.WifiConfigStoreEncryptionUtil;
import com.android.server.wifi.util.XmlUtil;
import com.android.server.wifi.util.XmlUtil.WifiConfigurationXmlUtil;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
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
    private static final String XML_TAG_IS_USER_ALLOWED_TO_MANUALLY_CONNECT =
            "IsUserAllowedToManuallyConnect";
    private static final String XML_TAG_IS_INITIALIZED_AUTO_JOIN = "InitializedAutoJoinEnabled";
    private static final String XML_TAG_IS_AUTO_JOIN = "AutoJoinEnabled";
    private static final String XML_TAG_SUGGESTOR_UID = "SuggestorUid";
    private static final String XML_TAG_SUGGESTOR_PACKAGE_NAME = "SuggestorPackageName";
    private static final String XML_TAG_SUGGESTOR_FEATURE_ID = "SuggestorFeatureId";
    private static final String XML_TAG_SUGGESTOR_HAS_USER_APPROVED = "SuggestorHasUserApproved";
    private static final String XML_TAG_SUGGESTOR_CARRIER_ID = "SuggestorCarrierId";
    private static final String XML_TAG_SUGGESTOR_MAX_SIZE = "SuggestorMaxSize";
    private static final String XML_TAG_SECTION_HEADER_PASSPOINT_CONFIGURATION =
            "PasspointConfiguration";

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
    public void serializeData(XmlSerializer out,
            @Nullable WifiConfigStoreEncryptionUtil encryptionUtil)
            throws XmlPullParserException, IOException {
        serializeNetworkSuggestionsMap(out, mDataSource.toSerialize(), encryptionUtil);
    }

    @Override
    public void deserializeData(XmlPullParser in, int outerTagDepth,
            @WifiConfigStore.Version int version,
            @Nullable WifiConfigStoreEncryptionUtil encryptionUtil)
            throws XmlPullParserException, IOException {
        if (in == null) {
            mDataSource.fromDeserialized(Collections.EMPTY_MAP);
            return;
        }
        mDataSource.fromDeserialized(
                parseNetworkSuggestionsMap(in, outerTagDepth, version, encryptionUtil));
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
            XmlSerializer out, final Map<String, PerAppInfo> networkSuggestionsMap,
            @Nullable WifiConfigStoreEncryptionUtil encryptionUtil)
            throws XmlPullParserException, IOException {
        if (networkSuggestionsMap == null) {
            return;
        }
        for (Entry<String, PerAppInfo> entry : networkSuggestionsMap.entrySet()) {
            String packageName = entry.getValue().packageName;
            String featureId = entry.getValue().featureId;
            boolean hasUserApproved = entry.getValue().hasUserApproved;
            int maxSize = entry.getValue().maxSize;
            int uid = entry.getValue().uid;
            int carrierId = entry.getValue().carrierId;
            Set<ExtendedWifiNetworkSuggestion> networkSuggestions =
                    entry.getValue().extNetworkSuggestions;
            XmlUtil.writeNextSectionStart(out, XML_TAG_SECTION_HEADER_NETWORK_SUGGESTION_PER_APP);
            XmlUtil.writeNextValue(out, XML_TAG_SUGGESTOR_PACKAGE_NAME, packageName);
            XmlUtil.writeNextValue(out, XML_TAG_SUGGESTOR_FEATURE_ID, featureId);
            XmlUtil.writeNextValue(out, XML_TAG_SUGGESTOR_HAS_USER_APPROVED, hasUserApproved);
            XmlUtil.writeNextValue(out, XML_TAG_SUGGESTOR_MAX_SIZE, maxSize);
            XmlUtil.writeNextValue(out, XML_TAG_SUGGESTOR_UID, uid);
            XmlUtil.writeNextValue(out, XML_TAG_SUGGESTOR_CARRIER_ID, carrierId);
            serializeExtNetworkSuggestions(out, networkSuggestions, encryptionUtil);
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
            XmlSerializer out, final Set<ExtendedWifiNetworkSuggestion> extNetworkSuggestions,
            @Nullable WifiConfigStoreEncryptionUtil encryptionUtil)
            throws XmlPullParserException, IOException {
        for (ExtendedWifiNetworkSuggestion extNetworkSuggestion : extNetworkSuggestions) {
            serializeNetworkSuggestion(out, extNetworkSuggestion, encryptionUtil);
        }
    }

    /**
     * Serialize a {@link ExtendedWifiNetworkSuggestion} to an output stream in XML format.
     *
     * @throws XmlPullParserException
     * @throws IOException
     */
    private void serializeNetworkSuggestion(XmlSerializer out,
            final ExtendedWifiNetworkSuggestion extSuggestion,
            @Nullable WifiConfigStoreEncryptionUtil encryptionUtil)
            throws XmlPullParserException, IOException {
        WifiNetworkSuggestion suggestion = extSuggestion.wns;

        XmlUtil.writeNextSectionStart(out, XML_TAG_SECTION_HEADER_NETWORK_SUGGESTION);

        // Serialize WifiConfiguration.
        XmlUtil.writeNextSectionStart(out, XML_TAG_SECTION_HEADER_WIFI_CONFIGURATION);
        WifiConfigurationXmlUtil.writeToXmlForConfigStore(
                out, suggestion.wifiConfiguration, encryptionUtil);
        XmlUtil.writeNextSectionEnd(out, XML_TAG_SECTION_HEADER_WIFI_CONFIGURATION);
        // Serialize enterprise configuration for enterprise networks.
        if (suggestion.wifiConfiguration.enterpriseConfig != null
                && suggestion.wifiConfiguration.enterpriseConfig.getEapMethod()
                != WifiEnterpriseConfig.Eap.NONE) {
            XmlUtil.writeNextSectionStart(
                    out, XML_TAG_SECTION_HEADER_WIFI_ENTERPRISE_CONFIGURATION);
            XmlUtil.WifiEnterpriseConfigXmlUtil.writeToXml(
                    out, suggestion.wifiConfiguration.enterpriseConfig, encryptionUtil);
            XmlUtil.writeNextSectionEnd(out, XML_TAG_SECTION_HEADER_WIFI_ENTERPRISE_CONFIGURATION);
        }
        if (suggestion.passpointConfiguration != null) {
            XmlUtil.writeNextSectionStart(out, XML_TAG_SECTION_HEADER_PASSPOINT_CONFIGURATION);
            PasspointXmlUtils.serializePasspointConfiguration(out,
                    suggestion.passpointConfiguration);
            XmlUtil.writeNextSectionEnd(out, XML_TAG_SECTION_HEADER_PASSPOINT_CONFIGURATION);
        }

        // Serialize other fields
        XmlUtil.writeNextValue(out, XML_TAG_IS_APP_INTERACTION_REQUIRED,
                suggestion.isAppInteractionRequired);
        XmlUtil.writeNextValue(out, XML_TAG_IS_USER_INTERACTION_REQUIRED,
                suggestion.isUserInteractionRequired);
        XmlUtil.writeNextValue(out, XML_TAG_IS_USER_ALLOWED_TO_MANUALLY_CONNECT,
                suggestion.isUserAllowedToManuallyConnect);
        XmlUtil.writeNextValue(out, XML_TAG_IS_INITIALIZED_AUTO_JOIN,
                suggestion.isInitialAutoJoinEnabled);
        XmlUtil.writeNextValue(out, XML_TAG_IS_AUTO_JOIN,
                extSuggestion.isAutojoinEnabled);
        XmlUtil.writeNextSectionEnd(out, XML_TAG_SECTION_HEADER_NETWORK_SUGGESTION);
    }

    /**
     * Parse a map of package name to network suggestions from an input stream in XML format.
     *
     * @throws XmlPullParserException
     * @throws IOException
     */
    private Map<String, PerAppInfo> parseNetworkSuggestionsMap(XmlPullParser in, int outerTagDepth,
            @WifiConfigStore.Version int version,
            @Nullable WifiConfigStoreEncryptionUtil encryptionUtil)
            throws XmlPullParserException, IOException {
        Map<String, PerAppInfo> networkSuggestionsMap = new HashMap<>();
        while (XmlUtil.gotoNextSectionWithNameOrEnd(
                in, XML_TAG_SECTION_HEADER_NETWORK_SUGGESTION_PER_APP, outerTagDepth)) {
            // Try/catch only runtime exceptions (like illegal args), any XML/IO exceptions are
            // fatal and should abort the entire loading process.
            try {
                PerAppInfo perAppInfo = null;
                String packageName = null;
                String featureId = null;
                boolean hasUserApproved = false;
                int maxSize = -1;
                int uid = Process.INVALID_UID;
                int carrierId = TelephonyManager.UNKNOWN_CARRIER_ID;
                // Loop through and parse out all the elements from the stream within this section.
                while (XmlUtil.nextElementWithin(in, outerTagDepth + 1)) {
                    if (in.getAttributeValue(null, "name") != null) {
                        // Value elements.
                        String[] valueName = new String[1];
                        Object value = XmlUtil.readCurrentValue(in, valueName);
                        switch (valueName[0]) {
                            case XML_TAG_SUGGESTOR_PACKAGE_NAME:
                                packageName = (String) value;
                                break;
                            case XML_TAG_SUGGESTOR_FEATURE_ID:
                                featureId = (String) value;
                                break;
                            case XML_TAG_SUGGESTOR_HAS_USER_APPROVED:
                                hasUserApproved = (boolean) value;
                                break;
                            case XML_TAG_SUGGESTOR_MAX_SIZE:
                                maxSize = (int) value;
                                break;
                            case XML_TAG_SUGGESTOR_UID:
                                uid = (int) value;
                                break;
                            case XML_TAG_SUGGESTOR_CARRIER_ID:
                                carrierId = (int) value;
                                break;
                            default:
                                Log.w(TAG, "Ignoring unknown value name found: " + valueName[0]);
                                break;
                        }
                    } else {
                        String tagName = in.getName();
                        if (tagName == null) {
                            throw new XmlPullParserException("Unexpected null under "
                                    + XML_TAG_SECTION_HEADER_NETWORK_SUGGESTION_PER_APP);
                        }
                        // Create the PerAppInfo struct before parsing individual network
                        // suggestions in the block.
                        if (perAppInfo == null) {
                            if (packageName == null) {
                                throw new XmlPullParserException(
                                        "XML parsing of PerAppInfo failed");
                            }
                            perAppInfo = new PerAppInfo(uid, packageName, featureId);
                            perAppInfo.hasUserApproved = hasUserApproved;
                            perAppInfo.maxSize = maxSize;
                            perAppInfo.carrierId = carrierId;
                        }
                        switch (tagName) {
                            case XML_TAG_SECTION_HEADER_NETWORK_SUGGESTION:
                                Pair<WifiNetworkSuggestion, Boolean> networkSuggestionData =
                                        parseNetworkSuggestion(
                                                in, outerTagDepth + 2, version, encryptionUtil,
                                                perAppInfo);
                                perAppInfo.extNetworkSuggestions.add(
                                        ExtendedWifiNetworkSuggestion.fromWns(
                                                networkSuggestionData.first, perAppInfo,
                                                networkSuggestionData.second));
                                break;
                            default:
                                Log.w(TAG, "Ignoring unknown tag under "
                                        + XML_TAG_SECTION_HEADER_NETWORK_SUGGESTION_PER_APP + ": "
                                        + in.getName());
                                break;
                        }
                    }
                }
                // If app has no suggestions, create perAppInfo here.
                if (perAppInfo == null) {
                    if (packageName == null) {
                        throw new XmlPullParserException(
                                "XML parsing of PerAppInfo failed");
                    }
                    perAppInfo = new PerAppInfo(uid, packageName, featureId);
                    perAppInfo.hasUserApproved = hasUserApproved;
                    perAppInfo.maxSize = maxSize;
                }
                // Store this app info in the map.
                networkSuggestionsMap.put(packageName, perAppInfo);
            } catch (RuntimeException e) {
                // Failed to parse this network, skip it.
                Log.e(TAG, "Failed to parse network suggestion. Skipping...", e);
            }
        }
        return networkSuggestionsMap;
    }

    /**
     * Parse a {@link ExtendedWifiNetworkSuggestion} from an input stream in XML format.
     *
     * @throws XmlPullParserException
     * @throws IOException
     */
    private Pair<WifiNetworkSuggestion, Boolean> parseNetworkSuggestion(XmlPullParser in,
            int outerTagDepth, @WifiConfigStore.Version int version,
            @Nullable WifiConfigStoreEncryptionUtil encryptionUtil, PerAppInfo perAppInfo)
            throws XmlPullParserException, IOException {
        Pair<String, WifiConfiguration> parsedConfig = null;
        WifiEnterpriseConfig enterpriseConfig = null;
        PasspointConfiguration passpointConfiguration = null;
        boolean isAppInteractionRequired = false;
        boolean isUserInteractionRequired = false;
        boolean isUserAllowedToManuallyConnect = false; // Backward compatibility.
        boolean isInitializedAutoJoinEnabled = true; // backward compat
        boolean isAutoJoinEnabled = true; // backward compat
        boolean isNetworkUntrusted = false;
        int suggestorUid = Process.INVALID_UID;

        // Loop through and parse out all the elements from the stream within this section.
        while (XmlUtil.nextElementWithin(in, outerTagDepth)) {
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
                    case XML_TAG_IS_USER_ALLOWED_TO_MANUALLY_CONNECT:
                        isUserAllowedToManuallyConnect = (boolean) value;
                        break;
                    case XML_TAG_IS_INITIALIZED_AUTO_JOIN:
                        isInitializedAutoJoinEnabled = (boolean) value;
                        break;
                    case XML_TAG_IS_AUTO_JOIN:
                        isAutoJoinEnabled = (boolean) value;
                        break;
                    case XML_TAG_SUGGESTOR_UID:
                        // Only needed for migration of data from Q to R.
                        suggestorUid = (int) value;
                        break;
                    default:
                        Log.w(TAG, "Ignoring unknown value name found: " + valueName[0]);
                        break;
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
                                in, outerTagDepth + 1,
                            version >= ENCRYPT_CREDENTIALS_CONFIG_STORE_DATA_VERSION,
                            encryptionUtil);
                        break;
                    case XML_TAG_SECTION_HEADER_WIFI_ENTERPRISE_CONFIGURATION:
                        if (enterpriseConfig != null) {
                            throw new XmlPullParserException("Detected duplicate tag for: "
                                    + XML_TAG_SECTION_HEADER_WIFI_ENTERPRISE_CONFIGURATION);
                        }
                        enterpriseConfig = XmlUtil.WifiEnterpriseConfigXmlUtil.parseFromXml(
                                in, outerTagDepth + 1,
                            version >= ENCRYPT_CREDENTIALS_CONFIG_STORE_DATA_VERSION,
                            encryptionUtil);
                        break;
                    case XML_TAG_SECTION_HEADER_PASSPOINT_CONFIGURATION:
                        if (passpointConfiguration != null) {
                            throw new XmlPullParserException("Detected duplicate tag for: "
                                    + XML_TAG_SECTION_HEADER_PASSPOINT_CONFIGURATION);
                        }
                        passpointConfiguration = PasspointXmlUtils
                                .deserializePasspointConfiguration(in, outerTagDepth + 1);
                        break;
                    default:
                        Log.w(TAG, "Ignoring unknown tag under "
                                + XML_TAG_SECTION_HEADER_NETWORK_SUGGESTION + ": " + in.getName());
                        break;
                }
            }
        }
        if (parsedConfig == null || parsedConfig.second == null) {
            throw new XmlPullParserException("XML parsing of wifi configuration failed");
        }
        // Note: In R, we migrated the uid/package name storage from individual
        // ExtWifiNetworkSuggestion to the top level PerAppInfo. This block of code helps
        // with migration of data for devices upgrading from Q to R.
        perAppInfo.setUid(suggestorUid);
        WifiConfiguration wifiConfiguration = parsedConfig.second;
        if (passpointConfiguration != null) {
            wifiConfiguration.setPasspointUniqueId(passpointConfiguration.getUniqueId());
        }
        if (enterpriseConfig != null) {
            wifiConfiguration.enterpriseConfig = enterpriseConfig;
        }
        return Pair.create(new WifiNetworkSuggestion(wifiConfiguration, passpointConfiguration,
                isAppInteractionRequired, isUserInteractionRequired, isUserAllowedToManuallyConnect,
                isInitializedAutoJoinEnabled), isAutoJoinEnabled);
    }
}

