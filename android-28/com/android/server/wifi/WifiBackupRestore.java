/*
 * Copyright (C) 2016 The Android Open Source Project
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

import android.net.IpConfiguration;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiEnterpriseConfig;
import android.os.Process;
import android.util.Log;
import android.util.SparseArray;
import android.util.Xml;

import com.android.internal.util.FastXmlSerializer;
import com.android.server.net.IpConfigStore;
import com.android.server.wifi.util.NativeUtil;
import com.android.server.wifi.util.WifiPermissionsUtil;
import com.android.server.wifi.util.XmlUtil;
import com.android.server.wifi.util.XmlUtil.IpConfigurationXmlUtil;
import com.android.server.wifi.util.XmlUtil.WifiConfigurationXmlUtil;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.CharArrayReader;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Class used to backup/restore data using the SettingsBackupAgent.
 * There are 2 symmetric API's exposed here:
 * 1. retrieveBackupDataFromConfigurations: Retrieve the configuration data to be backed up.
 * 2. retrieveConfigurationsFromBackupData: Restore the configuration using the provided data.
 * The byte stream to be backed up is XML encoded and versioned to migrate the data easily across
 * revisions.
 */
public class WifiBackupRestore {
    private static final String TAG = "WifiBackupRestore";

    /**
     * Current backup data version.
     * Note: before Android P this used to be an {@code int}, however support for minor versions
     * has been added in Android P. Currently this field is a {@code float} representing
     * "majorVersion.minorVersion" of the backed up data. MinorVersion starts with 0 and should
     * be incremented when necessary. MajorVersion starts with 1 and bumping it up requires
     * also resetting minorVersion to 0.
     *
     * MajorVersion will be incremented for modifications of the XML schema, excluding additive
     * modifications in <WifiConfiguration> and/or <IpConfiguration> tags.
     * Should the major version be bumped up, a new {@link WifiBackupDataParser} parser needs to
     * be added and returned from {@link getWifiBackupDataParser()}.
     * Note that bumping up the major version will result in inability to restore the backup
     * set to those lower versions of SDK_INT that don't support the version.
     *
     * MinorVersion will only be incremented for addition of <WifiConfiguration> and/or
     * <IpConfiguration> tags. Any other modifications to the schema should result in bumping up
     * the major version and resetting the minor version to 0.
     * Note that bumping up only the minor version will still allow restoring the backup set to
     * lower versions of SDK_INT.
     */
    private static final float CURRENT_BACKUP_DATA_VERSION = 1.0f;

    /** This list of older versions will be used to restore data from older backups. */
    /**
     * First version of the backup data format.
     */
    private static final int INITIAL_BACKUP_DATA_VERSION = 1;

    /**
     * List of XML section header tags in the backed up data
     */
    private static final String XML_TAG_DOCUMENT_HEADER = "WifiBackupData";
    private static final String XML_TAG_VERSION = "Version";

    static final String XML_TAG_SECTION_HEADER_NETWORK_LIST = "NetworkList";
    static final String XML_TAG_SECTION_HEADER_NETWORK = "Network";
    static final String XML_TAG_SECTION_HEADER_WIFI_CONFIGURATION = "WifiConfiguration";
    static final String XML_TAG_SECTION_HEADER_IP_CONFIGURATION = "IpConfiguration";

    /**
     * Regex to mask out passwords in backup data dump.
     */
    private static final String PSK_MASK_LINE_MATCH_PATTERN =
            "<.*" + WifiConfigurationXmlUtil.XML_TAG_PRE_SHARED_KEY + ".*>.*<.*>";
    private static final String PSK_MASK_SEARCH_PATTERN =
            "(<.*" + WifiConfigurationXmlUtil.XML_TAG_PRE_SHARED_KEY + ".*>)(.*)(<.*>)";
    private static final String PSK_MASK_REPLACE_PATTERN = "$1*$3";

    private static final String WEP_KEYS_MASK_LINE_START_MATCH_PATTERN =
            "<string-array.*" + WifiConfigurationXmlUtil.XML_TAG_WEP_KEYS + ".*num=\"[0-9]\">";
    private static final String WEP_KEYS_MASK_LINE_END_MATCH_PATTERN = "</string-array>";
    private static final String WEP_KEYS_MASK_SEARCH_PATTERN = "(<.*=)(.*)(/>)";
    private static final String WEP_KEYS_MASK_REPLACE_PATTERN = "$1*$3";

    private final WifiPermissionsUtil mWifiPermissionsUtil;
    /**
     * Verbose logging flag.
     */
    private boolean mVerboseLoggingEnabled = false;

    /**
     * Store the dump of the backup/restore data for debugging. This is only stored when verbose
     * logging is enabled in developer options.
     */
    private byte[] mDebugLastBackupDataRetrieved;
    private byte[] mDebugLastBackupDataRestored;
    private byte[] mDebugLastSupplicantBackupDataRestored;

    public WifiBackupRestore(WifiPermissionsUtil wifiPermissionsUtil) {
        mWifiPermissionsUtil = wifiPermissionsUtil;
    }

    /**
     * Retrieve an XML byte stream representing the data that needs to be backed up from the
     * provided configurations.
     *
     * @param configurations list of currently saved networks that needs to be backed up.
     * @return Raw byte stream of XML that needs to be backed up.
     */
    public byte[] retrieveBackupDataFromConfigurations(List<WifiConfiguration> configurations) {
        if (configurations == null) {
            Log.e(TAG, "Invalid configuration list received");
            return new byte[0];
        }

        try {
            final XmlSerializer out = new FastXmlSerializer();
            final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            out.setOutput(outputStream, StandardCharsets.UTF_8.name());

            // Start writing the XML stream.
            XmlUtil.writeDocumentStart(out, XML_TAG_DOCUMENT_HEADER);

            XmlUtil.writeNextValue(out, XML_TAG_VERSION, CURRENT_BACKUP_DATA_VERSION);

            writeNetworkConfigurationsToXml(out, configurations);

            XmlUtil.writeDocumentEnd(out, XML_TAG_DOCUMENT_HEADER);

            byte[] data = outputStream.toByteArray();

            if (mVerboseLoggingEnabled) {
                mDebugLastBackupDataRetrieved = data;
            }

            return data;
        } catch (XmlPullParserException e) {
            Log.e(TAG, "Error retrieving the backup data: " + e);
        } catch (IOException e) {
            Log.e(TAG, "Error retrieving the backup data: " + e);
        }
        return new byte[0];
    }

    /**
     * Write the list of configurations to the XML stream.
     */
    private void writeNetworkConfigurationsToXml(
            XmlSerializer out, List<WifiConfiguration> configurations)
            throws XmlPullParserException, IOException {
        XmlUtil.writeNextSectionStart(out, XML_TAG_SECTION_HEADER_NETWORK_LIST);
        for (WifiConfiguration configuration : configurations) {
            // We don't want to backup/restore enterprise/passpoint configurations.
            if (configuration.isEnterprise() || configuration.isPasspoint()) {
                continue;
            }
            if (!mWifiPermissionsUtil.checkConfigOverridePermission(configuration.creatorUid)) {
                Log.d(TAG, "Ignoring network from an app with no config override permission: "
                        + configuration.configKey());
                continue;
            }
            // Write this configuration data now.
            XmlUtil.writeNextSectionStart(out, XML_TAG_SECTION_HEADER_NETWORK);
            writeNetworkConfigurationToXml(out, configuration);
            XmlUtil.writeNextSectionEnd(out, XML_TAG_SECTION_HEADER_NETWORK);
        }
        XmlUtil.writeNextSectionEnd(out, XML_TAG_SECTION_HEADER_NETWORK_LIST);
    }

    /**
     * Write the configuration data elements from the provided Configuration to the XML stream.
     * Uses XmlUtils to write the values of each element.
     */
    private void writeNetworkConfigurationToXml(XmlSerializer out, WifiConfiguration configuration)
            throws XmlPullParserException, IOException {
        XmlUtil.writeNextSectionStart(out, XML_TAG_SECTION_HEADER_WIFI_CONFIGURATION);
        WifiConfigurationXmlUtil.writeToXmlForBackup(out, configuration);
        XmlUtil.writeNextSectionEnd(out, XML_TAG_SECTION_HEADER_WIFI_CONFIGURATION);
        XmlUtil.writeNextSectionStart(out, XML_TAG_SECTION_HEADER_IP_CONFIGURATION);
        IpConfigurationXmlUtil.writeToXml(out, configuration.getIpConfiguration());
        XmlUtil.writeNextSectionEnd(out, XML_TAG_SECTION_HEADER_IP_CONFIGURATION);
    }

    /**
     * Parse out the configurations from the back up data.
     *
     * @param data raw byte stream representing the XML data.
     * @return list of networks retrieved from the backed up data.
     */
    public List<WifiConfiguration> retrieveConfigurationsFromBackupData(byte[] data) {
        if (data == null || data.length == 0) {
            Log.e(TAG, "Invalid backup data received");
            return null;
        }
        try {
            if (mVerboseLoggingEnabled) {
                mDebugLastBackupDataRestored = data;
            }

            final XmlPullParser in = Xml.newPullParser();
            ByteArrayInputStream inputStream = new ByteArrayInputStream(data);
            in.setInput(inputStream, StandardCharsets.UTF_8.name());

            // Start parsing the XML stream.
            XmlUtil.gotoDocumentStart(in, XML_TAG_DOCUMENT_HEADER);
            int rootTagDepth = in.getDepth();

            int majorVersion = -1;
            int minorVersion = -1;
            try {
                float version = (float) XmlUtil.readNextValueWithName(in, XML_TAG_VERSION);

                // parse out major and minor versions
                String versionStr = new Float(version).toString();
                int separatorPos = versionStr.indexOf('.');
                if (separatorPos == -1) {
                    majorVersion = Integer.parseInt(versionStr);
                    minorVersion = 0;
                } else {
                    majorVersion = Integer.parseInt(versionStr.substring(0, separatorPos));
                    minorVersion = Integer.parseInt(versionStr.substring(separatorPos + 1));
                }
            } catch (ClassCastException cce) {
                // Integer cannot be cast to Float for data coming from before Android P
                majorVersion = 1;
                minorVersion = 0;
            }
            Log.d(TAG, "Version of backup data - major: " + majorVersion
                    + "; minor: " + minorVersion);

            WifiBackupDataParser parser = getWifiBackupDataParser(majorVersion);
            if (parser == null) {
                Log.w(TAG, "Major version of backup data is unknown to this Android"
                        + " version; not restoring");
                return null;
            } else {
                return parser.parseNetworkConfigurationsFromXml(in, rootTagDepth, minorVersion);
            }
        } catch (XmlPullParserException | IOException | ClassCastException
                | IllegalArgumentException e) {
            Log.e(TAG, "Error parsing the backup data: " + e);
        }
        return null;
    }

    private WifiBackupDataParser getWifiBackupDataParser(int majorVersion) {
        switch (majorVersion) {
            case INITIAL_BACKUP_DATA_VERSION:
                return new WifiBackupDataV1Parser();
            default:
                Log.e(TAG, "Unrecognized majorVersion of backup data: " + majorVersion);
                return null;
        }
    }

    /**
     * Create log dump of the backup data in XML format with the preShared & WEP key masked.
     *
     * PSK keys are written in the following format in XML:
     * <string name="PreSharedKey">WifiBackupRestorePsk</string>
     *
     * WEP Keys are written in following format in XML:
     * <string-array name="WEPKeys" num="4">
     *  <item value="WifiBackupRestoreWep1" />
     *  <item value="WifiBackupRestoreWep2" />
     *  <item value="WifiBackupRestoreWep3" />
     *  <item value="WifiBackupRestoreWep3" />
     * </string-array>
     */
    private String createLogFromBackupData(byte[] data) {
        StringBuilder sb = new StringBuilder();
        try {
            String xmlString = new String(data, StandardCharsets.UTF_8.name());
            boolean wepKeysLine = false;
            for (String line : xmlString.split("\n")) {
                if (line.matches(PSK_MASK_LINE_MATCH_PATTERN)) {
                    line = line.replaceAll(PSK_MASK_SEARCH_PATTERN, PSK_MASK_REPLACE_PATTERN);
                }
                if (line.matches(WEP_KEYS_MASK_LINE_START_MATCH_PATTERN)) {
                    wepKeysLine = true;
                } else if (line.matches(WEP_KEYS_MASK_LINE_END_MATCH_PATTERN)) {
                    wepKeysLine = false;
                } else if (wepKeysLine) {
                    line = line.replaceAll(
                            WEP_KEYS_MASK_SEARCH_PATTERN, WEP_KEYS_MASK_REPLACE_PATTERN);
                }
                sb.append(line).append("\n");
            }
        } catch (UnsupportedEncodingException e) {
            return "";
        }
        return sb.toString();
    }

    /**
     * Restore state from the older supplicant back up data.
     * The old backup data was essentially a backup of wpa_supplicant.conf & ipconfig.txt file.
     *
     * @param supplicantData Raw byte stream of wpa_supplicant.conf
     * @param ipConfigData   Raw byte stream of ipconfig.txt
     * @return list of networks retrieved from the backed up data.
     */
    public List<WifiConfiguration> retrieveConfigurationsFromSupplicantBackupData(
            byte[] supplicantData, byte[] ipConfigData) {
        if (supplicantData == null || supplicantData.length == 0) {
            Log.e(TAG, "Invalid supplicant backup data received");
            return null;
        }

        if (mVerboseLoggingEnabled) {
            mDebugLastSupplicantBackupDataRestored = supplicantData;
        }

        SupplicantBackupMigration.SupplicantNetworks supplicantNetworks =
                new SupplicantBackupMigration.SupplicantNetworks();
        // Incorporate the networks present in the backup data.
        char[] restoredAsChars = new char[supplicantData.length];
        for (int i = 0; i < supplicantData.length; i++) {
            restoredAsChars[i] = (char) supplicantData[i];
        }

        BufferedReader in = new BufferedReader(new CharArrayReader(restoredAsChars));
        supplicantNetworks.readNetworksFromStream(in);

        // Retrieve corresponding WifiConfiguration objects.
        List<WifiConfiguration> configurations = supplicantNetworks.retrieveWifiConfigurations();

        // Now retrieve all the IpConfiguration objects and set in the corresponding
        // WifiConfiguration objects if ipconfig data is present.
        if (ipConfigData != null && ipConfigData.length != 0) {
            SparseArray<IpConfiguration> networks =
                    IpConfigStore.readIpAndProxyConfigurations(
                            new ByteArrayInputStream(ipConfigData));
            if (networks != null) {
                for (int i = 0; i < networks.size(); i++) {
                    int id = networks.keyAt(i);
                    for (WifiConfiguration configuration : configurations) {
                        // This is a dangerous lookup, but that's how it is currently written.
                        if (configuration.configKey().hashCode() == id) {
                            configuration.setIpConfiguration(networks.valueAt(i));
                        }
                    }
                }
            } else {
                Log.e(TAG, "Failed to parse ipconfig data");
            }
        } else {
            Log.e(TAG, "Invalid ipconfig backup data received");
        }
        return configurations;
    }

    /**
     * Enable verbose logging.
     *
     * @param verbose verbosity level.
     */
    public void enableVerboseLogging(int verbose) {
        mVerboseLoggingEnabled = (verbose > 0);
        if (!mVerboseLoggingEnabled) {
            mDebugLastBackupDataRetrieved = null;
            mDebugLastBackupDataRestored = null;
            mDebugLastSupplicantBackupDataRestored = null;
        }
    }

    /**
     * Dump out the last backup/restore data if verbose logging is enabled.
     *
     * @param fd   unused
     * @param pw   PrintWriter for writing dump to
     * @param args unused
     */
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("Dump of WifiBackupRestore");
        if (mDebugLastBackupDataRetrieved != null) {
            pw.println("Last backup data retrieved: "
                    + createLogFromBackupData(mDebugLastBackupDataRetrieved));
        }
        if (mDebugLastBackupDataRestored != null) {
            pw.println("Last backup data restored: "
                    + createLogFromBackupData(mDebugLastBackupDataRestored));
        }
        if (mDebugLastSupplicantBackupDataRestored != null) {
            pw.println("Last old backup data restored: "
                    + SupplicantBackupMigration.createLogFromBackupData(
                            mDebugLastSupplicantBackupDataRestored));
        }
    }

    /**
     * These sub classes contain the logic to parse older backups and restore wifi state from it.
     * Most of the code here has been migrated over from BackupSettingsAgent.
     * This is kind of ugly text parsing, but it is needed to support the migration of this data.
     */
    public static class SupplicantBackupMigration {
        /**
         * List of keys to look out for in wpa_supplicant.conf parsing.
         * These key values are declared in different parts of the wifi codebase today.
         */
        public static final String SUPPLICANT_KEY_SSID = WifiConfiguration.ssidVarName;
        public static final String SUPPLICANT_KEY_HIDDEN = WifiConfiguration.hiddenSSIDVarName;
        public static final String SUPPLICANT_KEY_KEY_MGMT = WifiConfiguration.KeyMgmt.varName;
        public static final String SUPPLICANT_KEY_CLIENT_CERT =
                WifiEnterpriseConfig.CLIENT_CERT_KEY;
        public static final String SUPPLICANT_KEY_CA_CERT = WifiEnterpriseConfig.CA_CERT_KEY;
        public static final String SUPPLICANT_KEY_CA_PATH = WifiEnterpriseConfig.CA_PATH_KEY;
        public static final String SUPPLICANT_KEY_EAP = WifiEnterpriseConfig.EAP_KEY;
        public static final String SUPPLICANT_KEY_PSK = WifiConfiguration.pskVarName;
        public static final String SUPPLICANT_KEY_WEP_KEY0 = WifiConfiguration.wepKeyVarNames[0];
        public static final String SUPPLICANT_KEY_WEP_KEY1 = WifiConfiguration.wepKeyVarNames[1];
        public static final String SUPPLICANT_KEY_WEP_KEY2 = WifiConfiguration.wepKeyVarNames[2];
        public static final String SUPPLICANT_KEY_WEP_KEY3 = WifiConfiguration.wepKeyVarNames[3];
        public static final String SUPPLICANT_KEY_WEP_KEY_IDX =
                WifiConfiguration.wepTxKeyIdxVarName;
        public static final String SUPPLICANT_KEY_ID_STR = "id_str";

        /**
         * Regex to mask out passwords in backup data dump.
         */
        private static final String PSK_MASK_LINE_MATCH_PATTERN =
                ".*" + SUPPLICANT_KEY_PSK + ".*=.*";
        private static final String PSK_MASK_SEARCH_PATTERN =
                "(.*" + SUPPLICANT_KEY_PSK + ".*=)(.*)";
        private static final String PSK_MASK_REPLACE_PATTERN = "$1*";

        private static final String WEP_KEYS_MASK_LINE_MATCH_PATTERN =
                ".*" + SUPPLICANT_KEY_WEP_KEY0.replace("0", "") + ".*=.*";
        private static final String WEP_KEYS_MASK_SEARCH_PATTERN =
                "(.*" + SUPPLICANT_KEY_WEP_KEY0.replace("0", "") + ".*=)(.*)";
        private static final String WEP_KEYS_MASK_REPLACE_PATTERN = "$1*";

        /**
         * Create log dump of the backup data in wpa_supplicant.conf format with the preShared &
         * WEP key masked.
         *
         * PSK keys are written in the following format in wpa_supplicant.conf:
         *  psk=WifiBackupRestorePsk
         *
         * WEP Keys are written in following format in wpa_supplicant.conf:
         *  wep_keys0=WifiBackupRestoreWep0
         *  wep_keys1=WifiBackupRestoreWep1
         *  wep_keys2=WifiBackupRestoreWep2
         *  wep_keys3=WifiBackupRestoreWep3
         */
        public static String createLogFromBackupData(byte[] data) {
            StringBuilder sb = new StringBuilder();
            try {
                String supplicantConfString = new String(data, StandardCharsets.UTF_8.name());
                for (String line : supplicantConfString.split("\n")) {
                    if (line.matches(PSK_MASK_LINE_MATCH_PATTERN)) {
                        line = line.replaceAll(PSK_MASK_SEARCH_PATTERN, PSK_MASK_REPLACE_PATTERN);
                    }
                    if (line.matches(WEP_KEYS_MASK_LINE_MATCH_PATTERN)) {
                        line = line.replaceAll(
                                WEP_KEYS_MASK_SEARCH_PATTERN, WEP_KEYS_MASK_REPLACE_PATTERN);
                    }
                    sb.append(line).append("\n");
                }
            } catch (UnsupportedEncodingException e) {
                return "";
            }
            return sb.toString();
        }

        /**
         * Class for capturing a network definition from the wifi supplicant config file.
         */
        static class SupplicantNetwork {
            private String mParsedSSIDLine;
            private String mParsedHiddenLine;
            private String mParsedKeyMgmtLine;
            private String mParsedPskLine;
            private String[] mParsedWepKeyLines = new String[4];
            private String mParsedWepTxKeyIdxLine;
            private String mParsedIdStrLine;
            public boolean certUsed = false;
            public boolean isEap = false;

            /**
             * Read lines from wpa_supplicant.conf stream for this network.
             */
            public static SupplicantNetwork readNetworkFromStream(BufferedReader in) {
                final SupplicantNetwork n = new SupplicantNetwork();
                String line;
                try {
                    while (in.ready()) {
                        line = in.readLine();
                        if (line == null || line.startsWith("}")) {
                            break;
                        }
                        n.parseLine(line);
                    }
                } catch (IOException e) {
                    return null;
                }
                return n;
            }

            /**
             * Parse a line from wpa_supplicant.conf stream for this network.
             */
            void parseLine(String line) {
                // Can't rely on particular whitespace patterns so strip leading/trailing.
                line = line.trim();
                if (line.isEmpty()) return; // only whitespace; drop the line.

                // Now parse the network block within wpa_supplicant.conf and store the important
                // lines for processing later.
                if (line.startsWith(SUPPLICANT_KEY_SSID + "=")) {
                    mParsedSSIDLine = line;
                } else if (line.startsWith(SUPPLICANT_KEY_HIDDEN + "=")) {
                    mParsedHiddenLine = line;
                } else if (line.startsWith(SUPPLICANT_KEY_KEY_MGMT + "=")) {
                    mParsedKeyMgmtLine = line;
                    if (line.contains("EAP")) {
                        isEap = true;
                    }
                } else if (line.startsWith(SUPPLICANT_KEY_CLIENT_CERT + "=")) {
                    certUsed = true;
                } else if (line.startsWith(SUPPLICANT_KEY_CA_CERT + "=")) {
                    certUsed = true;
                } else if (line.startsWith(SUPPLICANT_KEY_CA_PATH + "=")) {
                    certUsed = true;
                } else if (line.startsWith(SUPPLICANT_KEY_EAP + "=")) {
                    isEap = true;
                } else if (line.startsWith(SUPPLICANT_KEY_PSK + "=")) {
                    mParsedPskLine = line;
                } else if (line.startsWith(SUPPLICANT_KEY_WEP_KEY0 + "=")) {
                    mParsedWepKeyLines[0] = line;
                } else if (line.startsWith(SUPPLICANT_KEY_WEP_KEY1 + "=")) {
                    mParsedWepKeyLines[1] = line;
                } else if (line.startsWith(SUPPLICANT_KEY_WEP_KEY2 + "=")) {
                    mParsedWepKeyLines[2] = line;
                } else if (line.startsWith(SUPPLICANT_KEY_WEP_KEY3 + "=")) {
                    mParsedWepKeyLines[3] = line;
                } else if (line.startsWith(SUPPLICANT_KEY_WEP_KEY_IDX + "=")) {
                    mParsedWepTxKeyIdxLine = line;
                } else if (line.startsWith(SUPPLICANT_KEY_ID_STR + "=")) {
                    mParsedIdStrLine = line;
                }
            }

            /**
             * Create WifiConfiguration object from the parsed data for this network.
             */
            public WifiConfiguration createWifiConfiguration() {
                if (mParsedSSIDLine == null) {
                    // No SSID => malformed network definition
                    return null;
                }
                WifiConfiguration configuration = new WifiConfiguration();
                configuration.SSID = mParsedSSIDLine.substring(mParsedSSIDLine.indexOf('=') + 1);

                if (mParsedHiddenLine != null) {
                    // Can't use Boolean.valueOf() because it works only for true/false.
                    configuration.hiddenSSID =
                            Integer.parseInt(mParsedHiddenLine.substring(
                                    mParsedHiddenLine.indexOf('=') + 1)) != 0;
                }
                if (mParsedKeyMgmtLine == null) {
                    // no key_mgmt line specified; this is defined as equivalent to
                    // "WPA-PSK WPA-EAP".
                    configuration.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
                    configuration.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_EAP);
                } else {
                    // Need to parse the mParsedKeyMgmtLine line
                    final String bareKeyMgmt =
                            mParsedKeyMgmtLine.substring(mParsedKeyMgmtLine.indexOf('=') + 1);
                    String[] typeStrings = bareKeyMgmt.split("\\s+");

                    // Parse out all the key management regimes permitted for this network.
                    // The literal strings here are the standard values permitted in
                    // wpa_supplicant.conf.
                    for (int i = 0; i < typeStrings.length; i++) {
                        final String ktype = typeStrings[i];
                        if (ktype.equals("NONE")) {
                            configuration.allowedKeyManagement.set(
                                    WifiConfiguration.KeyMgmt.NONE);
                        } else if (ktype.equals("WPA-PSK")) {
                            configuration.allowedKeyManagement.set(
                                    WifiConfiguration.KeyMgmt.WPA_PSK);
                        } else if (ktype.equals("WPA-EAP")) {
                            configuration.allowedKeyManagement.set(
                                    WifiConfiguration.KeyMgmt.WPA_EAP);
                        } else if (ktype.equals("IEEE8021X")) {
                            configuration.allowedKeyManagement.set(
                                    WifiConfiguration.KeyMgmt.IEEE8021X);
                        }
                    }
                }
                if (mParsedPskLine != null) {
                    configuration.preSharedKey =
                            mParsedPskLine.substring(mParsedPskLine.indexOf('=') + 1);
                }
                if (mParsedWepKeyLines[0] != null) {
                    configuration.wepKeys[0] =
                            mParsedWepKeyLines[0].substring(mParsedWepKeyLines[0].indexOf('=') + 1);
                }
                if (mParsedWepKeyLines[1] != null) {
                    configuration.wepKeys[1] =
                            mParsedWepKeyLines[1].substring(mParsedWepKeyLines[1].indexOf('=') + 1);
                }
                if (mParsedWepKeyLines[2] != null) {
                    configuration.wepKeys[2] =
                            mParsedWepKeyLines[2].substring(mParsedWepKeyLines[2].indexOf('=') + 1);
                }
                if (mParsedWepKeyLines[3] != null) {
                    configuration.wepKeys[3] =
                            mParsedWepKeyLines[3].substring(mParsedWepKeyLines[3].indexOf('=') + 1);
                }
                if (mParsedWepTxKeyIdxLine != null) {
                    configuration.wepTxKeyIndex =
                            Integer.valueOf(mParsedWepTxKeyIdxLine.substring(
                                    mParsedWepTxKeyIdxLine.indexOf('=') + 1));
                }
                if (mParsedIdStrLine != null) {
                    String idString =
                            mParsedIdStrLine.substring(mParsedIdStrLine.indexOf('=') + 1);
                    if (idString != null) {
                        Map<String, String> extras =
                                SupplicantStaNetworkHal.parseNetworkExtra(
                                        NativeUtil.removeEnclosingQuotes(idString));
                        if (extras == null) {
                            Log.e(TAG, "Error parsing network extras, ignoring network.");
                            return null;
                        }
                        String configKey = extras.get(
                                SupplicantStaNetworkHal.ID_STRING_KEY_CONFIG_KEY);
                        // No ConfigKey was passed but we need it for validating the parsed
                        // network so we stop the restore.
                        if (configKey == null) {
                            Log.e(TAG, "Configuration key was not passed, ignoring network.");
                            return null;
                        }
                        if (!configKey.equals(configuration.configKey())) {
                            // ConfigKey mismatches are expected for private networks because the
                            // UID is not preserved across backup/restore.
                            Log.w(TAG, "Configuration key does not match. Retrieved: " + configKey
                                    + ", Calculated: " + configuration.configKey());
                        }
                        // For wpa_supplicant backup data, parse out the creatorUid to ensure that
                        // these networks were created by system apps.
                        int creatorUid =
                                Integer.parseInt(extras.get(
                                        SupplicantStaNetworkHal.ID_STRING_KEY_CREATOR_UID));
                        if (creatorUid >= Process.FIRST_APPLICATION_UID) {
                            Log.d(TAG, "Ignoring network from non-system app: "
                                    + configuration.configKey());
                            return null;
                        }
                    }
                }
                return configuration;
            }
        }

        /**
         * Ingest multiple wifi config fragments from wpa_supplicant.conf, looking for network={}
         * blocks and eliminating duplicates
         */
        static class SupplicantNetworks {
            final ArrayList<SupplicantNetwork> mNetworks = new ArrayList<>(8);

            /**
             * Parse the wpa_supplicant.conf file stream and add networks.
             */
            public void readNetworksFromStream(BufferedReader in) {
                try {
                    String line;
                    while (in.ready()) {
                        line = in.readLine();
                        if (line != null) {
                            if (line.startsWith("network")) {
                                SupplicantNetwork net = SupplicantNetwork.readNetworkFromStream(in);

                                // An IOException occurred while trying to read the network.
                                if (net == null) {
                                    Log.e(TAG, "Error while parsing the network.");
                                    continue;
                                }

                                // Networks that use certificates for authentication can't be
                                // restored because the certificates they need don't get restored
                                // (because they are stored in keystore, and can't be restored).
                                // Similarly, omit EAP network definitions to avoid propagating
                                // controlled enterprise network definitions.
                                if (net.isEap || net.certUsed) {
                                    Log.d(TAG, "Skipping enterprise network for restore: "
                                            + net.mParsedSSIDLine + " / " + net.mParsedKeyMgmtLine);
                                    continue;
                                }
                                mNetworks.add(net);
                            }
                        }
                    }
                } catch (IOException e) {
                    // whatever happened, we're done now
                }
            }

            /**
             * Retrieve a list of WifiConfiguration objects parsed from wpa_supplicant.conf
             */
            public List<WifiConfiguration> retrieveWifiConfigurations() {
                ArrayList<WifiConfiguration> wifiConfigurations = new ArrayList<>();
                for (SupplicantNetwork net : mNetworks) {
                    try {
                        WifiConfiguration wifiConfiguration = net.createWifiConfiguration();
                        if (wifiConfiguration != null) {
                            Log.v(TAG, "Parsed Configuration: " + wifiConfiguration.configKey());
                            wifiConfigurations.add(wifiConfiguration);
                        }
                    } catch (NumberFormatException e) {
                        // Occurs if we are unable to parse the hidden SSID, WEP Key index or
                        // creator UID.
                        Log.e(TAG, "Error parsing wifi configuration: " + e);
                        return null;
                    }
                }
                return wifiConfigurations;
            }
        }
    }
}
