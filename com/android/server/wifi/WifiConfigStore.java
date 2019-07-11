/*
 * Copyright (C) 2010 The Android Open Source Project
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
import android.content.Intent;
import android.net.IpConfiguration;
import android.net.IpConfiguration.IpAssignment;
import android.net.IpConfiguration.ProxySettings;
import android.net.LinkAddress;
import android.net.NetworkInfo.DetailedState;
import android.net.ProxyInfo;
import android.net.RouteInfo;
import android.net.StaticIpConfiguration;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiConfiguration.KeyMgmt;
import android.net.wifi.WifiConfiguration.Status;
import static android.net.wifi.WifiConfiguration.INVALID_NETWORK_ID;

import android.net.wifi.WifiEnterpriseConfig;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiSsid;
import android.net.wifi.WpsInfo;
import android.net.wifi.WpsResult;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiInfo;

import android.os.Environment;
import android.os.FileObserver;
import android.os.Process;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.security.Credentials;
import android.security.KeyChain;
import android.security.KeyStore;
import android.text.TextUtils;
import android.util.LocalLog;
import android.util.Log;
import android.util.SparseArray;

import com.android.server.net.DelayedDiskWrite;
import com.android.server.net.IpConfigStore;
import com.android.internal.R;

import java.io.BufferedReader;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.math.BigInteger;
import java.net.InetAddress;
import java.nio.charset.Charset;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.text.SimpleDateFormat;
import java.text.DateFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.*;

/**
 * This class provides the API to manage configured
 * wifi networks. The API is not thread safe is being
 * used only from WifiStateMachine.
 *
 * It deals with the following
 * - Add/update/remove a WifiConfiguration
 *   The configuration contains two types of information.
 *     = IP and proxy configuration that is handled by WifiConfigStore and
 *       is saved to disk on any change.
 *
 *       The format of configuration file is as follows:
 *       <version>
 *       <netA_key1><netA_value1><netA_key2><netA_value2>...<EOS>
 *       <netB_key1><netB_value1><netB_key2><netB_value2>...<EOS>
 *       ..
 *
 *       (key, value) pairs for a given network are grouped together and can
 *       be in any order. A EOS at the end of a set of (key, value) pairs
 *       indicates that the next set of (key, value) pairs are for a new
 *       network. A network is identified by a unique ID_KEY. If there is no
 *       ID_KEY in the (key, value) pairs, the data is discarded.
 *
 *       An invalid version on read would result in discarding the contents of
 *       the file. On the next write, the latest version is written to file.
 *
 *       Any failures during read or write to the configuration file are ignored
 *       without reporting to the user since the likelihood of these errors are
 *       low and the impact on connectivity is low.
 *
 *     = SSID & security details that is pushed to the supplicant.
 *       supplicant saves these details to the disk on calling
 *       saveConfigCommand().
 *
 *       We have two kinds of APIs exposed:
 *        > public API calls that provide fine grained control
 *          - enableNetwork, disableNetwork, addOrUpdateNetwork(),
 *          removeNetwork(). For these calls, the config is not persisted
 *          to the disk. (TODO: deprecate these calls in WifiManager)
 *        > The new API calls - selectNetwork(), saveNetwork() & forgetNetwork().
 *          These calls persist the supplicant config to disk.
 *
 * - Maintain a list of configured networks for quick access
 *
 */
public class WifiConfigStore extends IpConfigStore {

    private Context mContext;
    private static final String TAG = "WifiConfigStore";
    private static final boolean DBG = true;
    private static boolean VDBG = false;
    private static boolean VVDBG = false;

    private static final String SUPPLICANT_CONFIG_FILE = "/data/misc/wifi/wpa_supplicant.conf";

    /* configured networks with network id as the key */
    private HashMap<Integer, WifiConfiguration> mConfiguredNetworks =
            new HashMap<Integer, WifiConfiguration>();

    /* A network id is a unique identifier for a network configured in the
     * supplicant. Network ids are generated when the supplicant reads
     * the configuration file at start and can thus change for networks.
     * We store the IP configuration for networks along with a unique id
     * that is generated from SSID and security type of the network. A mapping
     * from the generated unique id to network id of the network is needed to
     * map supplicant config to IP configuration. */
    private HashMap<Integer, Integer> mNetworkIds =
            new HashMap<Integer, Integer>();

    /* Tracks the highest priority of configured networks */
    private int mLastPriority = -1;

    private static final String ipConfigFile = Environment.getDataDirectory() +
            "/misc/wifi/ipconfig.txt";

    private static final String networkHistoryConfigFile = Environment.getDataDirectory() +
            "/misc/wifi/networkHistory.txt";

    private static final String autoJoinConfigFile = Environment.getDataDirectory() +
            "/misc/wifi/autojoinconfig.txt";

    /* Network History Keys */
    private static final String SSID_KEY = "SSID:  ";
    private static final String CONFIG_KEY = "CONFIG:  ";
    private static final String CHOICE_KEY = "CHOICE:  ";
    private static final String LINK_KEY = "LINK:  ";
    private static final String BSSID_KEY = "BSSID:  ";
    private static final String BSSID_KEY_END = "/BSSID:  ";
    private static final String RSSI_KEY = "RSSI:  ";
    private static final String FREQ_KEY = "FREQ:  ";
    private static final String DATE_KEY = "DATE:  ";
    private static final String MILLI_KEY = "MILLI:  ";
    private static final String BLACKLIST_MILLI_KEY = "BLACKLIST_MILLI:  ";
    private static final String NETWORK_ID_KEY = "ID:  ";
    private static final String PRIORITY_KEY = "PRIORITY:  ";
    private static final String DEFAULT_GW_KEY = "DEFAULT_GW:  ";
    private static final String AUTH_KEY = "AUTH:  ";
    private static final String SEPARATOR_KEY = "\n";
    private static final String STATUS_KEY = "AUTO_JOIN_STATUS:  ";
    private static final String BSSID_STATUS_KEY = "BSSID_STATUS:  ";
    private static final String SELF_ADDED_KEY = "SELF_ADDED:  ";
    private static final String FAILURE_KEY = "FAILURE:  ";
    private static final String DID_SELF_ADD_KEY = "DID_SELF_ADD:  ";
    private static final String PEER_CONFIGURATION_KEY = "PEER_CONFIGURATION:  ";
    private static final String CREATOR_UID_KEY = "CREATOR_UID_KEY:  ";
    private static final String CONNECT_UID_KEY = "CONNECT_UID_KEY:  ";
    private static final String UPDATE_UID_KEY = "UPDATE_UID:  ";
    private static final String SUPPLICANT_STATUS_KEY = "SUP_STATUS:  ";
    private static final String SUPPLICANT_DISABLE_REASON_KEY = "SUP_DIS_REASON:  ";
    private static final String FQDN_KEY = "FQDN:  ";
    private static final String NUM_CONNECTION_FAILURES_KEY = "CONNECT_FAILURES:  ";
    private static final String NUM_IP_CONFIG_FAILURES_KEY = "IP_CONFIG_FAILURES:  ";
    private static final String NUM_AUTH_FAILURES_KEY = "AUTH_FAILURES:  ";
    private static final String SCORER_OVERRIDE_KEY = "SCORER_OVERRIDE:  ";
    private static final String SCORER_OVERRIDE_AND_SWITCH_KEY = "SCORER_OVERRIDE_AND_SWITCH:  ";
    private static final String NO_INTERNET_ACCESS_KEY = "NO_INTERNET_ACCESS:  ";
    private static final String EPHEMERAL_KEY = "EPHEMERAL:   ";
    private static final String NUM_ASSOCIATION_KEY = "NUM_ASSOCIATION:  ";
    private static final String THRESHOLD_INITIAL_AUTO_JOIN_ATTEMPT_RSSI_MIN_5G_KEY
            = "THRESHOLD_INITIAL_AUTO_JOIN_ATTEMPT_RSSI_MIN_5G:  ";
    private static final String THRESHOLD_INITIAL_AUTO_JOIN_ATTEMPT_RSSI_MIN_24G_KEY
            = "THRESHOLD_INITIAL_AUTO_JOIN_ATTEMPT_RSSI_MIN_24G:  ";
    private static final String THRESHOLD_UNBLACKLIST_HARD_5G_KEY
            = "THRESHOLD_UNBLACKLIST_HARD_5G:  ";
    private static final String THRESHOLD_UNBLACKLIST_SOFT_5G_KEY
            = "THRESHOLD_UNBLACKLIST_SOFT_5G:  ";
    private static final String THRESHOLD_UNBLACKLIST_HARD_24G_KEY
            = "THRESHOLD_UNBLACKLIST_HARD_24G:  ";
    private static final String THRESHOLD_UNBLACKLIST_SOFT_24G_KEY
            = "THRESHOLD_UNBLACKLIST_SOFT_24G:  ";
    private static final String THRESHOLD_GOOD_RSSI_5_KEY
            = "THRESHOLD_GOOD_RSSI_5:  ";
    private static final String THRESHOLD_LOW_RSSI_5_KEY
            = "THRESHOLD_LOW_RSSI_5:  ";
    private static final String THRESHOLD_BAD_RSSI_5_KEY
            = "THRESHOLD_BAD_RSSI_5:  ";
    private static final String THRESHOLD_GOOD_RSSI_24_KEY
            = "THRESHOLD_GOOD_RSSI_24:  ";
    private static final String THRESHOLD_LOW_RSSI_24_KEY
            = "THRESHOLD_LOW_RSSI_24:  ";
    private static final String THRESHOLD_BAD_RSSI_24_KEY
            = "THRESHOLD_BAD_RSSI_24:  ";

    private static final String THRESHOLD_MAX_TX_PACKETS_FOR_NETWORK_SWITCHING_KEY
            = "THRESHOLD_MAX_TX_PACKETS_FOR_NETWORK_SWITCHING:   ";
    private static final String THRESHOLD_MAX_RX_PACKETS_FOR_NETWORK_SWITCHING_KEY
            = "THRESHOLD_MAX_RX_PACKETS_FOR_NETWORK_SWITCHING:   ";

    private static final String THRESHOLD_MAX_TX_PACKETS_FOR_FULL_SCANS_KEY
            = "THRESHOLD_MAX_TX_PACKETS_FOR_FULL_SCANS:   ";
    private static final String THRESHOLD_MAX_RX_PACKETS_FOR_FULL_SCANS_KEY
            = "THRESHOLD_MAX_RX_PACKETS_FOR_FULL_SCANS:   ";

    private static final String THRESHOLD_MAX_TX_PACKETS_FOR_PARTIAL_SCANS_KEY
            = "THRESHOLD_MAX_TX_PACKETS_FOR_PARTIAL_SCANS:   ";
    private static final String THRESHOLD_MAX_RX_PACKETS_FOR_PARTIAL_SCANS_KEY
            = "THRESHOLD_MAX_RX_PACKETS_FOR_PARTIAL_SCANS:   ";

    private static final String MAX_NUM_ACTIVE_CHANNELS_FOR_PARTIAL_SCANS_KEY
            = "MAX_NUM_ACTIVE_CHANNELS_FOR_PARTIAL_SCANS:   ";
    private static final String MAX_NUM_PASSIVE_CHANNELS_FOR_PARTIAL_SCANS_KEY
            = "MAX_NUM_PASSIVE_CHANNELS_FOR_PARTIAL_SCANS:   ";

    private static final String A_BAND_PREFERENCE_RSSI_THRESHOLD_LOW_KEY =
            "A_BAND_PREFERENCE_RSSI_THRESHOLD_LOW:   ";
    private static final String A_BAND_PREFERENCE_RSSI_THRESHOLD_KEY =
            "A_BAND_PREFERENCE_RSSI_THRESHOLD:   ";
    private static final String G_BAND_PREFERENCE_RSSI_THRESHOLD_KEY =
            "G_BAND_PREFERENCE_RSSI_THRESHOLD:   ";

    private static final String ENABLE_AUTOJOIN_WHILE_ASSOCIATED_KEY
            = "ENABLE_AUTOJOIN_WHILE_ASSOCIATED:   ";

    private static final String ASSOCIATED_PARTIAL_SCAN_PERIOD_KEY
            = "ASSOCIATED_PARTIAL_SCAN_PERIOD:   ";
    private static final String ASSOCIATED_FULL_SCAN_BACKOFF_KEY
            = "ASSOCIATED_FULL_SCAN_BACKOFF_PERIOD:   ";
    private static final String ALWAYS_ENABLE_SCAN_WHILE_ASSOCIATED_KEY
            = "ALWAYS_ENABLE_SCAN_WHILE_ASSOCIATED:   ";
    private static final String ONLY_LINK_SAME_CREDENTIAL_CONFIGURATIONS_KEY
            = "ONLY_LINK_SAME_CREDENTIAL_CONFIGURATIONS:   ";

    private static final String ENABLE_FULL_BAND_SCAN_WHEN_ASSOCIATED_KEY
            = "ENABLE_FULL_BAND_SCAN_WHEN_ASSOCIATED:   ";

    // The three below configurations are mainly for power stats and CPU usage tracking
    // allowing to incrementally disable framework features
    private static final String ENABLE_AUTO_JOIN_SCAN_WHILE_ASSOCIATED_KEY
            = "ENABLE_AUTO_JOIN_SCAN_WHILE_ASSOCIATED:   ";
    private static final String ENABLE_AUTO_JOIN_WHILE_ASSOCIATED_KEY
            = "ENABLE_AUTO_JOIN_WHILE_ASSOCIATED:   ";
    private static final String ENABLE_CHIP_WAKE_UP_WHILE_ASSOCIATED_KEY
            = "ENABLE_CHIP_WAKE_UP_WHILE_ASSOCIATED:   ";
    private static final String ENABLE_RSSI_POLL_WHILE_ASSOCIATED_KEY
            = "ENABLE_RSSI_POLL_WHILE_ASSOCIATED_KEY:   ";

    // The Wifi verbose log is provided as a way to persist the verbose logging settings
    // for testing purpose.
    // It is not intended for normal use.
    private static final String WIFI_VERBOSE_LOGS_KEY
            = "WIFI_VERBOSE_LOGS:   ";

    public boolean enableAutoJoinScanWhenAssociated = true;
    public boolean enableAutoJoinWhenAssociated = true;
    public boolean enableChipWakeUpWhenAssociated = true;
    public boolean enableRssiPollWhenAssociated = true;

    public int maxTxPacketForNetworkSwitching = 40;
    public int maxRxPacketForNetworkSwitching = 80;

    public int maxTxPacketForFullScans = 8;
    public int maxRxPacketForFullScans = 16;

    public int maxTxPacketForPartialScans = 40;
    public int maxRxPacketForPartialScans = 80;

    public boolean enableFullBandScanWhenAssociated = true;

    public int thresholdInitialAutoJoinAttemptMin5RSSI
            = WifiConfiguration.INITIAL_AUTO_JOIN_ATTEMPT_MIN_5;
    public int thresholdInitialAutoJoinAttemptMin24RSSI
            = WifiConfiguration.INITIAL_AUTO_JOIN_ATTEMPT_MIN_24;

    public int thresholdBadRssi5 = WifiConfiguration.BAD_RSSI_5;
    public int thresholdLowRssi5 = WifiConfiguration.LOW_RSSI_5;
    public int thresholdGoodRssi5 = WifiConfiguration.GOOD_RSSI_5;
    public int thresholdBadRssi24 = WifiConfiguration.BAD_RSSI_24;
    public int thresholdLowRssi24 = WifiConfiguration.LOW_RSSI_24;
    public int thresholdGoodRssi24 = WifiConfiguration.GOOD_RSSI_24;

    public int associatedFullScanBackoff = 12; // Will be divided by 8 by WifiStateMachine
    public int associatedFullScanMaxIntervalMilli = 300000;

    public int associatedPartialScanPeriodMilli;

    public int bandPreferenceBoostFactor5 = 5; // Boost by 5 dB per dB above threshold
    public int bandPreferencePenaltyFactor5 = 2; // Penalize by 2 dB per dB below threshold
    public int bandPreferencePenaltyThreshold5 = WifiConfiguration.G_BAND_PREFERENCE_RSSI_THRESHOLD;
    public int bandPreferenceBoostThreshold5 = WifiConfiguration.A_BAND_PREFERENCE_RSSI_THRESHOLD;

    public int badLinkSpeed24 = 6;
    public int badLinkSpeed5 = 12;
    public int goodLinkSpeed24 = 24;
    public int goodLinkSpeed5 = 36;

    public int maxAuthErrorsToBlacklist = 4;
    public int maxConnectionErrorsToBlacklist = 4;
    public int wifiConfigBlacklistMinTimeMilli = 1000 * 60 * 5;

    // Boost RSSI values of associated networks
    public int associatedHysteresisHigh = +14;
    public int associatedHysteresisLow = +8;

    public int thresholdUnblacklistThreshold5Hard
            = WifiConfiguration.UNBLACKLIST_THRESHOLD_5_HARD;
    public int thresholdUnblacklistThreshold5Soft
            = WifiConfiguration.UNBLACKLIST_THRESHOLD_5_SOFT;
    public int thresholdUnblacklistThreshold24Hard
            = WifiConfiguration.UNBLACKLIST_THRESHOLD_24_HARD;
    public int thresholdUnblacklistThreshold24Soft
            = WifiConfiguration.UNBLACKLIST_THRESHOLD_24_SOFT;
    public int enableVerboseLogging = 0;
    boolean showNetworks = true; // TODO set this back to false, used for debugging 17516271

    public int alwaysEnableScansWhileAssociated = 0;

    public int maxNumActiveChannelsForPartialScans = 6;
    public int maxNumPassiveChannelsForPartialScans = 2;

    public boolean roamOnAny = false;
    public boolean onlyLinkSameCredentialConfigurations = true;

    public boolean enableLinkDebouncing = true;
    public boolean enable5GHzPreference = true;
    public boolean enableWifiCellularHandoverUserTriggeredAdjustment = true;

    /**
     * Regex pattern for extracting a connect choice.
     * Matches a strings like the following:
     * <configKey>=([0:9]+)
     */
    private static Pattern mConnectChoice =
            Pattern.compile("(.*)=([0-9]+)");


    /* Enterprise configuration keys */
    /**
     * In old configurations, the "private_key" field was used. However, newer
     * configurations use the key_id field with the engine_id set to "keystore".
     * If this field is found in the configuration, the migration code is
     * triggered.
     */
    public static final String OLD_PRIVATE_KEY_NAME = "private_key";

    /**
     * This represents an empty value of an enterprise field.
     * NULL is used at wpa_supplicant to indicate an empty value
     */
    static final String EMPTY_VALUE = "NULL";

    // Internal use only
    private static final String[] ENTERPRISE_CONFIG_SUPPLICANT_KEYS = new String[] {
            WifiEnterpriseConfig.EAP_KEY, WifiEnterpriseConfig.PHASE2_KEY,
            WifiEnterpriseConfig.IDENTITY_KEY, WifiEnterpriseConfig.ANON_IDENTITY_KEY,
            WifiEnterpriseConfig.PASSWORD_KEY, WifiEnterpriseConfig.CLIENT_CERT_KEY,
            WifiEnterpriseConfig.CA_CERT_KEY, WifiEnterpriseConfig.SUBJECT_MATCH_KEY,
            WifiEnterpriseConfig.ENGINE_KEY, WifiEnterpriseConfig.ENGINE_ID_KEY,
            WifiEnterpriseConfig.PRIVATE_KEY_ID_KEY };


    /**
     * The maximum number of times we will retry a connection to an access point
     * for which we have failed in acquiring an IP address from DHCP. A value of
     * N means that we will make N+1 connection attempts in all.
     * <p>
     * See {@link Settings.Secure#WIFI_MAX_DHCP_RETRY_COUNT}. This is the default
     * value if a Settings value is not present.
     */
    private static final int DEFAULT_MAX_DHCP_RETRIES = 9;


    private final LocalLog mLocalLog;
    private final WpaConfigFileObserver mFileObserver;

    private WifiNative mWifiNative;
    private final KeyStore mKeyStore = KeyStore.getInstance();

    /**
     * The lastSelectedConfiguration is used to remember which network
     * was selected last by the user.
     * The connection to this network may not be successful, as well
     * the selection (i.e. network priority) might not be persisted.
     * WiFi state machine is the only object that sets this variable.
     */
    private String lastSelectedConfiguration = null;

    WifiConfigStore(Context c, WifiNative wn) {
        mContext = c;
        mWifiNative = wn;

        if (showNetworks) {
            mLocalLog = mWifiNative.getLocalLog();
            mFileObserver = new WpaConfigFileObserver();
            mFileObserver.startWatching();
        } else {
            mLocalLog = null;
            mFileObserver = null;
        }

        associatedPartialScanPeriodMilli = mContext.getResources().getInteger(
                R.integer.config_wifi_framework_associated_scan_interval);
        loge("associatedPartialScanPeriodMilli set to " + associatedPartialScanPeriodMilli);

        onlyLinkSameCredentialConfigurations = mContext.getResources().getBoolean(
                R.bool.config_wifi_only_link_same_credential_configurations);
        maxNumActiveChannelsForPartialScans = mContext.getResources().getInteger(
                R.integer.config_wifi_framework_associated_partial_scan_max_num_active_channels);
        maxNumPassiveChannelsForPartialScans = mContext.getResources().getInteger(
                R.integer.config_wifi_framework_associated_partial_scan_max_num_passive_channels);
        associatedFullScanMaxIntervalMilli = mContext.getResources().getInteger(
                R.integer.config_wifi_framework_associated_full_scan_max_interval);
        associatedFullScanBackoff = mContext.getResources().getInteger(
                R.integer.config_wifi_framework_associated_full_scan_backoff);
        enableLinkDebouncing = mContext.getResources().getBoolean(
                R.bool.config_wifi_enable_disconnection_debounce);

        enable5GHzPreference = mContext.getResources().getBoolean(
                R.bool.config_wifi_enable_5GHz_preference);

        bandPreferenceBoostFactor5 = mContext.getResources().getInteger(
                R.integer.config_wifi_framework_5GHz_preference_boost_factor);
        bandPreferencePenaltyFactor5 = mContext.getResources().getInteger(
                R.integer.config_wifi_framework_5GHz_preference_penalty_factor);

        bandPreferencePenaltyThreshold5 = mContext.getResources().getInteger(
                R.integer.config_wifi_framework_5GHz_preference_penalty_threshold);
        bandPreferenceBoostThreshold5 = mContext.getResources().getInteger(
                R.integer.config_wifi_framework_5GHz_preference_boost_threshold);

        associatedHysteresisHigh = mContext.getResources().getInteger(
                R.integer.config_wifi_framework_current_association_hysteresis_high);
        associatedHysteresisLow = mContext.getResources().getInteger(
                R.integer.config_wifi_framework_current_association_hysteresis_low);

        thresholdBadRssi5 = mContext.getResources().getInteger(
                R.integer.config_wifi_framework_wifi_score_bad_rssi_threshold_5GHz);
        thresholdLowRssi5 = mContext.getResources().getInteger(
                R.integer.config_wifi_framework_wifi_score_low_rssi_threshold_5GHz);
        thresholdGoodRssi5 = mContext.getResources().getInteger(
                R.integer.config_wifi_framework_wifi_score_good_rssi_threshold_5GHz);
        thresholdBadRssi24 = mContext.getResources().getInteger(
                R.integer.config_wifi_framework_wifi_score_bad_rssi_threshold_24GHz);
        thresholdLowRssi24 = mContext.getResources().getInteger(
                R.integer.config_wifi_framework_wifi_score_low_rssi_threshold_24GHz);
        thresholdGoodRssi24 = mContext.getResources().getInteger(
                R.integer.config_wifi_framework_wifi_score_good_rssi_threshold_24GHz);

        enableWifiCellularHandoverUserTriggeredAdjustment = mContext.getResources().getBoolean(
                R.bool.config_wifi_framework_cellular_handover_enable_user_triggered_adjustment);

        badLinkSpeed24 = mContext.getResources().getInteger(
                R.integer.config_wifi_framework_wifi_score_bad_link_speed_24);
        badLinkSpeed5 = mContext.getResources().getInteger(
                R.integer.config_wifi_framework_wifi_score_bad_link_speed_5);
        goodLinkSpeed24 = mContext.getResources().getInteger(
                R.integer.config_wifi_framework_wifi_score_good_link_speed_24);
        goodLinkSpeed5 = mContext.getResources().getInteger(
                R.integer.config_wifi_framework_wifi_score_good_link_speed_5);

        maxAuthErrorsToBlacklist = mContext.getResources().getInteger(
                R.integer.config_wifi_framework_max_auth_errors_to_blacklist);
        maxConnectionErrorsToBlacklist = mContext.getResources().getInteger(
                R.integer.config_wifi_framework_max_connection_errors_to_blacklist);
        wifiConfigBlacklistMinTimeMilli = mContext.getResources().getInteger(
                R.integer.config_wifi_framework_network_black_list_min_time_milli);


        enableAutoJoinScanWhenAssociated = mContext.getResources().getBoolean(
                R.bool.config_wifi_framework_enable_associated_autojoin_scan);

        enableAutoJoinWhenAssociated = mContext.getResources().getBoolean(
                R.bool.config_wifi_framework_enable_associated_network_selection);
    }

    void enableVerboseLogging(int verbose) {
        enableVerboseLogging = verbose;
        if (verbose > 0) {
            VDBG = true;
            showNetworks = true;
        } else {
            VDBG = false;
        }
        if (verbose > 1) {
            VVDBG = true;
        } else {
            VVDBG = false;
        }
    }

    class WpaConfigFileObserver extends FileObserver {

        public WpaConfigFileObserver() {
            super(SUPPLICANT_CONFIG_FILE, CLOSE_WRITE);
        }

        @Override
        public void onEvent(int event, String path) {
            if (event == CLOSE_WRITE) {
                File file = new File(SUPPLICANT_CONFIG_FILE);
                if (VDBG) localLog("wpa_supplicant.conf changed; new size = " + file.length());
            }
        }
    }


    /**
     * Fetch the list of configured networks
     * and enable all stored networks in supplicant.
     */
    void loadAndEnableAllNetworks() {
        if (DBG) log("Loading config and enabling all networks ");
        loadConfiguredNetworks();
        enableAllNetworks();
    }

    int getConfiguredNetworksSize() {
        return mConfiguredNetworks.size();
    }

    private List<WifiConfiguration> getConfiguredNetworks(Map<String, String> pskMap) {
        List<WifiConfiguration> networks = new ArrayList<>();
        for(WifiConfiguration config : mConfiguredNetworks.values()) {
            WifiConfiguration newConfig = new WifiConfiguration(config);
            if (config.autoJoinStatus == WifiConfiguration.AUTO_JOIN_DELETED) {
                // Do not enumerate and return this configuration to any one,
                // for instance WiFi Picker.
                // instead treat it as unknown. the configuration can still be retrieved
                // directly by the key or networkId
                continue;
            }

            if (pskMap != null && config.allowedKeyManagement != null
                    && config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.WPA_PSK)
                    && pskMap.containsKey(config.SSID)) {
                newConfig.preSharedKey = pskMap.get(config.SSID);
            }
            networks.add(newConfig);
        }
        return networks;
    }

    /**
     * Fetch the list of currently configured networks
     * @return List of networks
     */
    List<WifiConfiguration> getConfiguredNetworks() {
        return getConfiguredNetworks(null);
    }

    /**
     * Fetch the list of currently configured networks, filled with real preSharedKeys
     * @return List of networks
     */
    List<WifiConfiguration> getPrivilegedConfiguredNetworks() {
        Map<String, String> pskMap = getCredentialsBySsidMap();
        return getConfiguredNetworks(pskMap);
    }

    /**
     * Fetch the preSharedKeys for all networks.
     * @return a map from Ssid to preSharedKey.
     */
    private Map<String, String> getCredentialsBySsidMap() {
        return readNetworkVariablesFromSupplicantFile("psk");
    }

    int getconfiguredNetworkSize() {
        if (mConfiguredNetworks == null)
            return 0;
        return mConfiguredNetworks.size();
    }

    /**
     * Fetch the list of currently configured networks that were recently seen
     *
     * @return List of networks
     */
    List<WifiConfiguration> getRecentConfiguredNetworks(int milli, boolean copy) {
        List<WifiConfiguration> networks = null;

        for (WifiConfiguration config : mConfiguredNetworks.values()) {
            if (config.autoJoinStatus == WifiConfiguration.AUTO_JOIN_DELETED) {
                // Do not enumerate and return this configuration to any one,
                // instead treat it as unknown. the configuration can still be retrieved
                // directly by the key or networkId
                continue;
            }

            // Calculate the RSSI for scan results that are more recent than milli
            config.setVisibility(milli);
            if (config.visibility == null) {
                continue;
            }
            if (config.visibility.rssi5 == WifiConfiguration.INVALID_RSSI &&
                    config.visibility.rssi24 == WifiConfiguration.INVALID_RSSI) {
                continue;
            }
            if (networks == null)
                networks = new ArrayList<WifiConfiguration>();
            if (copy) {
                networks.add(new WifiConfiguration(config));
            } else {
                networks.add(config);
            }
        }
        return networks;
    }

    /**
     *  Update the configuration and BSSID with latest RSSI value.
     */
    void updateConfiguration(WifiInfo info) {
        WifiConfiguration config = getWifiConfiguration(info.getNetworkId());
        if (config != null && config.scanResultCache != null) {
            ScanResult result = config.scanResultCache.get(info.getBSSID());
            if (result != null) {
                long previousSeen = result.seen;
                int previousRssi = result.level;

                // Update the scan result
                result.seen = System.currentTimeMillis();
                result.level = info.getRssi();

                // Average the RSSI value
                result.averageRssi(previousRssi, previousSeen,
                        WifiAutoJoinController.mScanResultMaximumAge);
                if (VDBG) {
                    loge("updateConfiguration freq=" + result.frequency
                        + " BSSID=" + result.BSSID
                        + " RSSI=" + result.level
                        + " " + config.configKey());
                }
            }
        }
    }

    /**
     * get the Wificonfiguration for this netId
     *
     * @return Wificonfiguration
     */
    WifiConfiguration getWifiConfiguration(int netId) {
        if (mConfiguredNetworks == null)
            return null;
        return mConfiguredNetworks.get(netId);
    }

    /**
     * Get the Wificonfiguration for this key
     * @return Wificonfiguration
     */
    WifiConfiguration getWifiConfiguration(String key) {
        if (key == null)
            return null;
        int hash = key.hashCode();
        if (mNetworkIds == null)
            return null;
        Integer n = mNetworkIds.get(hash);
        if (n == null)
            return null;
        int netId = n.intValue();
        return getWifiConfiguration(netId);
    }

    /**
     * Enable all networks and save config. This will be a no-op if the list
     * of configured networks indicates all networks as being enabled
     */
    void enableAllNetworks() {
        long now = System.currentTimeMillis();
        boolean networkEnabledStateChanged = false;

        for(WifiConfiguration config : mConfiguredNetworks.values()) {

            if(config != null && config.status == Status.DISABLED
                    && (config.autoJoinStatus
                    <= WifiConfiguration.AUTO_JOIN_DISABLED_ON_AUTH_FAILURE)) {

                // Wait for 20 minutes before reenabling config that have known, repeated connection
                // or DHCP failures
                if (config.disableReason == WifiConfiguration.DISABLED_DHCP_FAILURE
                        || config.disableReason == WifiConfiguration.DISABLED_ASSOCIATION_REJECT
                        || config.disableReason == WifiConfiguration.DISABLED_AUTH_FAILURE) {
                    if (config.blackListTimestamp != 0
                           && now > config.blackListTimestamp
                           && (now - config.blackListTimestamp) < wifiConfigBlacklistMinTimeMilli) {
                        continue;
                    }
                }

                if(mWifiNative.enableNetwork(config.networkId, false)) {
                    networkEnabledStateChanged = true;
                    config.status = Status.ENABLED;

                    // Reset the blacklist condition
                    config.numConnectionFailures = 0;
                    config.numIpConfigFailures = 0;
                    config.numAuthFailures = 0;

                    // Reenable the wifi configuration
                    config.setAutoJoinStatus(WifiConfiguration.AUTO_JOIN_ENABLED);
                } else {
                    loge("Enable network failed on " + config.networkId);
                }
            }
        }

        if (networkEnabledStateChanged) {
            mWifiNative.saveConfig();
            sendConfiguredNetworksChangedBroadcast();
        }
    }


    /**
     * Selects the specified network for connection. This involves
     * updating the priority of all the networks and enabling the given
     * network while disabling others.
     *
     * Selecting a network will leave the other networks disabled and
     * a call to enableAllNetworks() needs to be issued upon a connection
     * or a failure event from supplicant
     *
     * @param netId network to select for connection
     * @return false if the network id is invalid
     */
    boolean selectNetwork(int netId) {
        if (VDBG) localLog("selectNetwork", netId);
        if (netId == INVALID_NETWORK_ID) return false;

        // Reset the priority of each network at start or if it goes too high.
        if (mLastPriority == -1 || mLastPriority > 1000000) {
            for(WifiConfiguration config : mConfiguredNetworks.values()) {
                if (config.networkId != INVALID_NETWORK_ID) {
                    config.priority = 0;
                    addOrUpdateNetworkNative(config, -1);
                }
            }
            mLastPriority = 0;
        }

        // Set to the highest priority and save the configuration.
        WifiConfiguration config = new WifiConfiguration();
        config.networkId = netId;
        config.priority = ++mLastPriority;

        addOrUpdateNetworkNative(config, -1);
        mWifiNative.saveConfig();

        /* Enable the given network while disabling all other networks */
        enableNetworkWithoutBroadcast(netId, true);

       /* Avoid saving the config & sending a broadcast to prevent settings
        * from displaying a disabled list of networks */
        return true;
    }

    /**
     * Add/update the specified configuration and save config
     *
     * @param config WifiConfiguration to be saved
     * @return network update result
     */
    NetworkUpdateResult saveNetwork(WifiConfiguration config, int uid) {
        WifiConfiguration conf;

        // A new network cannot have null SSID
        if (config == null || (config.networkId == INVALID_NETWORK_ID &&
                config.SSID == null)) {
            return new NetworkUpdateResult(INVALID_NETWORK_ID);
        }
        if (VDBG) localLog("WifiConfigStore: saveNetwork netId", config.networkId);
        if (VDBG) {
            loge("WifiConfigStore saveNetwork, size=" + mConfiguredNetworks.size()
                    + " SSID=" + config.SSID
                    + " Uid=" + Integer.toString(config.creatorUid)
                    + "/" + Integer.toString(config.lastUpdateUid));
        }
        boolean newNetwork = (config.networkId == INVALID_NETWORK_ID);
        NetworkUpdateResult result = addOrUpdateNetworkNative(config, uid);
        int netId = result.getNetworkId();

        if (VDBG) localLog("WifiConfigStore: saveNetwork got it back netId=", netId);

        /* enable a new network */
        if (newNetwork && netId != INVALID_NETWORK_ID) {
            if (VDBG) localLog("WifiConfigStore: will enable netId=", netId);

            mWifiNative.enableNetwork(netId, false);
            conf = mConfiguredNetworks.get(netId);
            if (conf != null)
                conf.status = Status.ENABLED;
        }

        conf = mConfiguredNetworks.get(netId);
        if (conf != null) {
            if (conf.autoJoinStatus != WifiConfiguration.AUTO_JOIN_ENABLED) {
                if (VDBG) localLog("WifiConfigStore: re-enabling: " + conf.SSID);

                // reenable autojoin, since new information has been provided
                conf.setAutoJoinStatus(WifiConfiguration.AUTO_JOIN_ENABLED);
                enableNetworkWithoutBroadcast(conf.networkId, false);
            }
            if (VDBG) {
                loge("WifiConfigStore: saveNetwork got config back netId="
                        + Integer.toString(netId)
                        + " uid=" + Integer.toString(config.creatorUid));
            }
        }

        mWifiNative.saveConfig();
        sendConfiguredNetworksChangedBroadcast(conf, result.isNewNetwork() ?
                WifiManager.CHANGE_REASON_ADDED : WifiManager.CHANGE_REASON_CONFIG_CHANGE);
        return result;
    }

    void saveWifiConfigBSSID(WifiConfiguration config) {
        // Sanity check the config is valid
        if (config == null || (config.networkId == INVALID_NETWORK_ID &&
                config.SSID == null)) {
            return;
        }

        // If an app specified a BSSID then dont over-write it
        if (config.BSSID != null && config.BSSID != "any") {
            return;
        }

        // If autojoin specified a BSSID then write it in the network block
        if (config.autoJoinBSSID != null) {
            loge("saveWifiConfigBSSID Setting BSSID for " + config.configKey()
                    + " to " + config.autoJoinBSSID);
            if (!mWifiNative.setNetworkVariable(
                    config.networkId,
                    WifiConfiguration.bssidVarName,
                    config.autoJoinBSSID)) {
                loge("failed to set BSSID: " + config.autoJoinBSSID);
            } else if (config.autoJoinBSSID.equals("any")) {
                // Paranoia, we just want to make sure that we restore the config to normal
                mWifiNative.saveConfig();
            }
        }
    }


    void updateStatus(int netId, DetailedState state) {
        if (netId != INVALID_NETWORK_ID) {
            WifiConfiguration config = mConfiguredNetworks.get(netId);
            if (config == null) return;
            switch (state) {
                case CONNECTED:
                    config.status = Status.CURRENT;
                    //we successfully connected, hence remove the blacklist
                    config.setAutoJoinStatus(WifiConfiguration.AUTO_JOIN_ENABLED);
                    break;
                case DISCONNECTED:
                    //If network is already disabled, keep the status
                    if (config.status == Status.CURRENT) {
                        config.status = Status.ENABLED;
                    }
                    break;
                default:
                    //do nothing, retain the existing state
                    break;
            }
        }
    }

    /**
     * Forget the specified network and save config
     *
     * @param netId network to forget
     * @return {@code true} if it succeeds, {@code false} otherwise
     */
    boolean forgetNetwork(int netId) {
        if (showNetworks) localLog("forgetNetwork", netId);

        boolean remove = removeConfigAndSendBroadcastIfNeeded(netId);
        if (!remove) {
            //success but we dont want to remove the network from supplicant conf file
            return true;
        }
        if (mWifiNative.removeNetwork(netId)) {
            mWifiNative.saveConfig();
            return true;
        } else {
            loge("Failed to remove network " + netId);
            return false;
        }
    }

    /**
     * Add/update a network. Note that there is no saveConfig operation.
     * This function is retained for compatibility with the public
     * API. The more powerful saveNetwork() is used by the
     * state machine
     *
     * @param config wifi configuration to add/update
     * @return network Id
     */
    int addOrUpdateNetwork(WifiConfiguration config, int uid) {
        if (showNetworks) localLog("addOrUpdateNetwork id=", config.networkId);
        //adding unconditional message to chase b/15111865
        Log.e(TAG, " key=" + config.configKey() + " netId=" + Integer.toString(config.networkId)
                + " uid=" + Integer.toString(config.creatorUid)
                + "/" + Integer.toString(config.lastUpdateUid));
        NetworkUpdateResult result = addOrUpdateNetworkNative(config, uid);
        if (result.getNetworkId() != WifiConfiguration.INVALID_NETWORK_ID) {
            WifiConfiguration conf = mConfiguredNetworks.get(result.getNetworkId());
            if (conf != null) {
                sendConfiguredNetworksChangedBroadcast(conf,
                    result.isNewNetwork ? WifiManager.CHANGE_REASON_ADDED :
                            WifiManager.CHANGE_REASON_CONFIG_CHANGE);
            }
        }
        return result.getNetworkId();
    }

    /**
     * Remove a network. Note that there is no saveConfig operation.
     * This function is retained for compatibility with the public
     * API. The more powerful forgetNetwork() is used by the
     * state machine for network removal
     *
     * @param netId network to be removed
     * @return {@code true} if it succeeds, {@code false} otherwise
     */
    boolean removeNetwork(int netId) {
        if (showNetworks) localLog("removeNetwork", netId);
        boolean ret = mWifiNative.removeNetwork(netId);
        if (ret) {
            removeConfigAndSendBroadcastIfNeeded(netId);
        }
        return ret;
    }

    private boolean removeConfigAndSendBroadcastIfNeeded(int netId) {
        boolean remove = true;
        WifiConfiguration config = mConfiguredNetworks.get(netId);
        if (config != null) {
            if (VDBG) {
                loge("removeNetwork " + Integer.toString(netId) + " key=" +
                        config.configKey() + " config.id=" + Integer.toString(config.networkId));
            }

            // cancel the last user choice
            if (config.configKey().equals(lastSelectedConfiguration)) {
                lastSelectedConfiguration = null;
            }

            // Remove any associated keys
            if (config.enterpriseConfig != null) {
                removeKeys(config.enterpriseConfig);
            }

            if (config.selfAdded || config.linkedConfigurations != null
                    || config.allowedKeyManagement.get(KeyMgmt.WPA_PSK)) {
                remove = false;
                loge("removeNetwork " + Integer.toString(netId)
                        + " key=" + config.configKey()
                        + " config.id=" + Integer.toString(config.networkId)
                        + " -> mark as deleted");
            }

            if (remove) {
                mConfiguredNetworks.remove(netId);
                mNetworkIds.remove(configKey(config));
            } else {
                /**
                 * We can't directly remove the configuration since we could re-add it ourselves,
                 * and that would look weird to the user.
                 * Instead mark it as deleted and completely hide it from the rest of the system.
                 */
                config.setAutoJoinStatus(WifiConfiguration.AUTO_JOIN_DELETED);
                // Disable
                mWifiNative.disableNetwork(config.networkId);
                config.status = WifiConfiguration.Status.DISABLED;
                // Since we don't delete the configuration, clean it up and loose the history
                config.linkedConfigurations = null;
                config.scanResultCache = null;
                config.connectChoices = null;
                config.defaultGwMacAddress = null;
                config.setIpConfiguration(new IpConfiguration());
                // Loose the PSK
                if (!mWifiNative.setNetworkVariable(
                        config.networkId,
                        WifiConfiguration.pskVarName,
                        "\"xxxxxxxx\"")) {
                    loge("removeNetwork, failed to clear PSK, nid=" + config.networkId);
                }
                // Loose the BSSID
                config.BSSID = null;
                config.autoJoinBSSID = null;
                if (!mWifiNative.setNetworkVariable(
                        config.networkId,
                        WifiConfiguration.bssidVarName,
                        "any")) {
                    loge("removeNetwork, failed to remove BSSID");
                }
                // Loose the hiddenSSID flag
                config.hiddenSSID = false;
                if (!mWifiNative.setNetworkVariable(
                        config.networkId,
                        WifiConfiguration.hiddenSSIDVarName,
                        Integer.toString(0))) {
                    loge("removeNetwork, failed to remove hiddenSSID");
                }

                mWifiNative.saveConfig();
            }

            writeIpAndProxyConfigurations();
            sendConfiguredNetworksChangedBroadcast(config, WifiManager.CHANGE_REASON_REMOVED);
            writeKnownNetworkHistory();
        }
        return remove;
    }

    /**
     * Enable a network. Note that there is no saveConfig operation.
     * This function is retained for compatibility with the public
     * API. The more powerful selectNetwork()/saveNetwork() is used by the
     * state machine for connecting to a network
     *
     * @param netId network to be enabled
     * @return {@code true} if it succeeds, {@code false} otherwise
     */
    boolean enableNetwork(int netId, boolean disableOthers) {
        boolean ret = enableNetworkWithoutBroadcast(netId, disableOthers);
        if (disableOthers) {
            if (VDBG) localLog("enableNetwork(disableOthers=true) ", netId);
            sendConfiguredNetworksChangedBroadcast();
        } else {
            if (VDBG) localLog("enableNetwork(disableOthers=false) ", netId);
            WifiConfiguration enabledNetwork = null;
            synchronized(mConfiguredNetworks) {
                enabledNetwork = mConfiguredNetworks.get(netId);
            }
            // check just in case the network was removed by someone else.
            if (enabledNetwork != null) {
                sendConfiguredNetworksChangedBroadcast(enabledNetwork,
                        WifiManager.CHANGE_REASON_CONFIG_CHANGE);
            }
        }
        return ret;
    }

    boolean enableNetworkWithoutBroadcast(int netId, boolean disableOthers) {
        boolean ret = mWifiNative.enableNetwork(netId, disableOthers);

        WifiConfiguration config = mConfiguredNetworks.get(netId);
        if (config != null) config.status = Status.ENABLED;

        if (disableOthers) {
            markAllNetworksDisabledExcept(netId);
        }
        return ret;
    }

    void disableAllNetworks() {
        if (VDBG) localLog("disableAllNetworks");
        boolean networkDisabled = false;
        for(WifiConfiguration config : mConfiguredNetworks.values()) {
            if(config != null && config.status != Status.DISABLED) {
                if(mWifiNative.disableNetwork(config.networkId)) {
                    networkDisabled = true;
                    config.status = Status.DISABLED;
                } else {
                    loge("Disable network failed on " + config.networkId);
                }
            }
        }

        if (networkDisabled) {
            sendConfiguredNetworksChangedBroadcast();
        }
    }
    /**
     * Disable a network. Note that there is no saveConfig operation.
     * @param netId network to be disabled
     * @return {@code true} if it succeeds, {@code false} otherwise
     */
    boolean disableNetwork(int netId) {
        return disableNetwork(netId, WifiConfiguration.DISABLED_UNKNOWN_REASON);
    }

    /**
     * Disable a network. Note that there is no saveConfig operation.
     * @param netId network to be disabled
     * @param reason reason code network was disabled
     * @return {@code true} if it succeeds, {@code false} otherwise
     */
    boolean disableNetwork(int netId, int reason) {
        if (VDBG) localLog("disableNetwork", netId);
        boolean ret = mWifiNative.disableNetwork(netId);
        WifiConfiguration network = null;
        WifiConfiguration config = mConfiguredNetworks.get(netId);

        if (VDBG) {
            if (config != null) {
                loge("disableNetwork netId=" + Integer.toString(netId)
                        + " SSID=" + config.SSID
                        + " disabled=" + (config.status == Status.DISABLED)
                        + " reason=" + Integer.toString(config.disableReason));
            }
        }
        /* Only change the reason if the network was not previously disabled
        /* and the reason is not DISABLED_BY_WIFI_MANAGER, that is, if a 3rd party
         * set its configuration as disabled, then leave it disabled */
        if (config != null && config.status != Status.DISABLED
                && config.disableReason != WifiConfiguration.DISABLED_BY_WIFI_MANAGER) {
            config.status = Status.DISABLED;
            config.disableReason = reason;
            network = config;
        }
        if (reason == WifiConfiguration.DISABLED_BY_WIFI_MANAGER) {
            // Make sure autojoin wont reenable this configuration without further user
            // intervention
            config.autoJoinStatus = WifiConfiguration.AUTO_JOIN_DISABLED_USER_ACTION;
        }
        if (network != null) {
            sendConfiguredNetworksChangedBroadcast(network,
                    WifiManager.CHANGE_REASON_CONFIG_CHANGE);
        }
        return ret;
    }

    /**
     * Save the configured networks in supplicant to disk
     * @return {@code true} if it succeeds, {@code false} otherwise
     */
    boolean saveConfig() {
        return mWifiNative.saveConfig();
    }

    /**
     * Start WPS pin method configuration with pin obtained
     * from the access point
     * @param config WPS configuration
     * @return Wps result containing status and pin
     */
    WpsResult startWpsWithPinFromAccessPoint(WpsInfo config) {
        WpsResult result = new WpsResult();
        if (mWifiNative.startWpsRegistrar(config.BSSID, config.pin)) {
            /* WPS leaves all networks disabled */
            markAllNetworksDisabled();
            result.status = WpsResult.Status.SUCCESS;
        } else {
            loge("Failed to start WPS pin method configuration");
            result.status = WpsResult.Status.FAILURE;
        }
        return result;
    }

    /**
     * Start WPS pin method configuration with pin obtained
     * from the device
     * @return WpsResult indicating status and pin
     */
    WpsResult startWpsWithPinFromDevice(WpsInfo config) {
        WpsResult result = new WpsResult();
        result.pin = mWifiNative.startWpsPinDisplay(config.BSSID);
        /* WPS leaves all networks disabled */
        if (!TextUtils.isEmpty(result.pin)) {
            markAllNetworksDisabled();
            result.status = WpsResult.Status.SUCCESS;
        } else {
            loge("Failed to start WPS pin method configuration");
            result.status = WpsResult.Status.FAILURE;
        }
        return result;
    }

    /**
     * Start WPS push button configuration
     * @param config WPS configuration
     * @return WpsResult indicating status and pin
     */
    WpsResult startWpsPbc(WpsInfo config) {
        WpsResult result = new WpsResult();
        if (mWifiNative.startWpsPbc(config.BSSID)) {
            /* WPS leaves all networks disabled */
            markAllNetworksDisabled();
            result.status = WpsResult.Status.SUCCESS;
        } else {
            loge("Failed to start WPS push button configuration");
            result.status = WpsResult.Status.FAILURE;
        }
        return result;
    }

    /**
     * Fetch the static IP configuration for a given network id
     */
    StaticIpConfiguration getStaticIpConfiguration(int netId) {
        WifiConfiguration config = mConfiguredNetworks.get(netId);
        if (config != null) {
            return config.getStaticIpConfiguration();
        }
        return null;
    }

    /**
     * Set the static IP configuration for a given network id
     */
    void setStaticIpConfiguration(int netId, StaticIpConfiguration staticIpConfiguration) {
        WifiConfiguration config = mConfiguredNetworks.get(netId);
        if (config != null) {
            config.setStaticIpConfiguration(staticIpConfiguration);
        }
    }

    /**
     * set default GW MAC address
     */
    void setDefaultGwMacAddress(int netId, String macAddress) {
        WifiConfiguration config = mConfiguredNetworks.get(netId);
        if (config != null) {
            //update defaultGwMacAddress
            config.defaultGwMacAddress = macAddress;
        }
    }


    /**
     * Fetch the proxy properties for a given network id
     * @param network id
     * @return ProxyInfo for the network id
     */
    ProxyInfo getProxyProperties(int netId) {
        WifiConfiguration config = mConfiguredNetworks.get(netId);
        if (config != null) {
            return config.getHttpProxy();
        }
        return null;
    }

    /**
     * Return if the specified network is using static IP
     * @param network id
     * @return {@code true} if using static ip for netId
     */
    boolean isUsingStaticIp(int netId) {
        WifiConfiguration config = mConfiguredNetworks.get(netId);
        if (config != null && config.getIpAssignment() == IpAssignment.STATIC) {
            return true;
        }
        return false;
    }

    /**
     * Should be called when a single network configuration is made.
     * @param network The network configuration that changed.
     * @param reason The reason for the change, should be one of WifiManager.CHANGE_REASON_ADDED,
     * WifiManager.CHANGE_REASON_REMOVED, or WifiManager.CHANGE_REASON_CHANGE.
     */
    private void sendConfiguredNetworksChangedBroadcast(WifiConfiguration network,
            int reason) {
        Intent intent = new Intent(WifiManager.CONFIGURED_NETWORKS_CHANGED_ACTION);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        intent.putExtra(WifiManager.EXTRA_MULTIPLE_NETWORKS_CHANGED, false);
        intent.putExtra(WifiManager.EXTRA_WIFI_CONFIGURATION, network);
        intent.putExtra(WifiManager.EXTRA_CHANGE_REASON, reason);
        mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
    }

    /**
     * Should be called when multiple network configuration changes are made.
     */
    private void sendConfiguredNetworksChangedBroadcast() {
        Intent intent = new Intent(WifiManager.CONFIGURED_NETWORKS_CHANGED_ACTION);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        intent.putExtra(WifiManager.EXTRA_MULTIPLE_NETWORKS_CHANGED, true);
        mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
    }

    void loadConfiguredNetworks() {
        String listStr = mWifiNative.listNetworks();
        mLastPriority = 0;

        mConfiguredNetworks.clear();
        mNetworkIds.clear();

        if (listStr == null)
            return;

        String[] lines = listStr.split("\n");

        if (showNetworks) {
            localLog("WifiConfigStore: loadConfiguredNetworks:  ");
            for (String net : lines) {
                localLog(net);
            }
        }

        // Skip the first line, which is a header
        for (int i = 1; i < lines.length; i++) {
            String[] result = lines[i].split("\t");
            // network-id | ssid | bssid | flags
            WifiConfiguration config = new WifiConfiguration();
            try {
                config.networkId = Integer.parseInt(result[0]);
            } catch(NumberFormatException e) {
                loge("Failed to read network-id '" + result[0] + "'");
                continue;
            }
            if (result.length > 3) {
                if (result[3].indexOf("[CURRENT]") != -1)
                    config.status = WifiConfiguration.Status.CURRENT;
                else if (result[3].indexOf("[DISABLED]") != -1)
                    config.status = WifiConfiguration.Status.DISABLED;
                else
                    config.status = WifiConfiguration.Status.ENABLED;
            } else {
                config.status = WifiConfiguration.Status.ENABLED;
            }
            readNetworkVariables(config);
            if (config.priority > mLastPriority) {
                mLastPriority = config.priority;
            }

            config.setIpAssignment(IpAssignment.DHCP);
            config.setProxySettings(ProxySettings.NONE);

            if (mNetworkIds.containsKey(configKey(config))) {
                // That SSID is already known, just ignore this duplicate entry
                if (showNetworks) localLog("discarded duplicate network ", config.networkId);
            } else if(config.isValid()){
                mConfiguredNetworks.put(config.networkId, config);
                mNetworkIds.put(configKey(config), config.networkId);
                if (showNetworks) localLog("loaded configured network", config.networkId);
            } else {
                if (showNetworks) log("Ignoring loaded configured for network " + config.networkId
                    + " because config are not valid");
            }
        }

        readIpAndProxyConfigurations();
        readNetworkHistory();
        readAutoJoinConfig();

        sendConfiguredNetworksChangedBroadcast();

        if (showNetworks) localLog("loadConfiguredNetworks loaded " + mNetworkIds.size() + " networks");

        if (mNetworkIds.size() == 0) {
            // no networks? Lets log if the wpa_supplicant.conf file contents
            BufferedReader reader = null;
            try {
                reader = new BufferedReader(new FileReader(SUPPLICANT_CONFIG_FILE));
                if (DBG) {
                    localLog("--- Begin wpa_supplicant.conf Contents ---", true);
                    for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                        localLog(line, true);
                    }
                    localLog("--- End wpa_supplicant.conf Contents ---", true);
                }
            } catch (FileNotFoundException e) {
                localLog("Could not open " + SUPPLICANT_CONFIG_FILE + ", " + e, true);
            } catch (IOException e) {
                localLog("Could not read " + SUPPLICANT_CONFIG_FILE + ", " + e, true);
            } finally {
                try {
                    if (reader != null) {
                        reader.close();
                    }
                } catch (IOException e) {
                    // Just ignore the fact that we couldn't close
                }
            }
        }
    }

    private Map<String, String> readNetworkVariablesFromSupplicantFile(String key) {
        Map<String, String> result = new HashMap<>();
        BufferedReader reader = null;
        if (VDBG) loge("readNetworkVariablesFromSupplicantFile key=" + key);

        try {
            reader = new BufferedReader(new FileReader(SUPPLICANT_CONFIG_FILE));
            boolean found = false;
            String networkSsid = null;
            String value = null;

            for (String line = reader.readLine(); line != null; line = reader.readLine()) {

                if (line.matches("[ \\t]*network=\\{")) {
                    found = true;
                    networkSsid = null;
                    value = null;
                } else if (line.matches("[ \\t]*\\}")) {
                    found = false;
                    networkSsid = null;
                    value = null;
                }

                if (found) {
                    String trimmedLine = line.trim();
                    if (trimmedLine.startsWith("ssid=")) {
                        networkSsid = trimmedLine.substring(5);
                    } else if (trimmedLine.startsWith(key + "=")) {
                        value = trimmedLine.substring(key.length() + 1);
                    }

                    if (networkSsid != null && value != null) {
                        result.put(networkSsid, value);
                    }
                }
            }
        } catch (FileNotFoundException e) {
            if (VDBG) loge("Could not open " + SUPPLICANT_CONFIG_FILE + ", " + e);
        } catch (IOException e) {
            if (VDBG) loge("Could not read " + SUPPLICANT_CONFIG_FILE + ", " + e);
        } finally {
            try {
                if (reader != null) {
                    reader.close();
                }
            } catch (IOException e) {
                // Just ignore the fact that we couldn't close
            }
        }

        return result;
    }

    private String readNetworkVariableFromSupplicantFile(String ssid, String key) {
        Map<String, String> data = readNetworkVariablesFromSupplicantFile(key);
        if (VDBG) loge("readNetworkVariableFromSupplicantFile ssid=[" + ssid + "] key=" + key);
        return data.get(ssid);
    }

    /* Mark all networks except specified netId as disabled */
    private void markAllNetworksDisabledExcept(int netId) {
        for(WifiConfiguration config : mConfiguredNetworks.values()) {
            if(config != null && config.networkId != netId) {
                if (config.status != Status.DISABLED) {
                    config.status = Status.DISABLED;
                    config.disableReason = WifiConfiguration.DISABLED_UNKNOWN_REASON;
                }
            }
        }
    }

    private void markAllNetworksDisabled() {
        markAllNetworksDisabledExcept(INVALID_NETWORK_ID);
    }

    boolean needsUnlockedKeyStore() {

        // Any network using certificates to authenticate access requires
        // unlocked key store; unless the certificates can be stored with
        // hardware encryption

        for(WifiConfiguration config : mConfiguredNetworks.values()) {

            if (config.allowedKeyManagement.get(KeyMgmt.WPA_EAP)
                    && config.allowedKeyManagement.get(KeyMgmt.IEEE8021X)) {

                if (needsSoftwareBackedKeyStore(config.enterpriseConfig)) {
                    return true;
                }
            }
        }

        return false;
    }

    public void writeKnownNetworkHistory() {
        boolean needUpdate = false;

        /* Make a copy */
        final List<WifiConfiguration> networks = new ArrayList<WifiConfiguration>();
        for (WifiConfiguration config : mConfiguredNetworks.values()) {
            networks.add(new WifiConfiguration(config));
            if (config.dirty == true) {
                config.dirty = false;
                needUpdate = true;
            }
        }
        if (VDBG) {
            loge(" writeKnownNetworkHistory() num networks:" +
                    mConfiguredNetworks.size() + " needWrite=" + needUpdate);
        }
        if (needUpdate == false) {
            return;
        }
        mWriter.write(networkHistoryConfigFile, new DelayedDiskWrite.Writer() {
            public void onWriteCalled(DataOutputStream out) throws IOException {
                for (WifiConfiguration config : networks) {
                    //loge("onWriteCalled write SSID: " + config.SSID);
                   /* if (config.getLinkProperties() != null)
                        loge(" lp " + config.getLinkProperties().toString());
                    else
                        loge("attempt config w/o lp");
                    */

                    if (VDBG) {
                        int num = 0;
                        int numlink = 0;
                        if (config.connectChoices != null) {
                            num = config.connectChoices.size();
                        }
                        if (config.linkedConfigurations != null) {
                            numlink = config.linkedConfigurations.size();
                        }
                        loge("saving network history: " + config.configKey()  + " gw: " +
                                config.defaultGwMacAddress + " autojoin-status: " +
                                config.autoJoinStatus + " ephemeral=" + config.ephemeral
                                + " choices:" + Integer.toString(num)
                                + " link:" + Integer.toString(numlink)
                                + " status:" + Integer.toString(config.status)
                                + " nid:" + Integer.toString(config.networkId));
                    }

                    if (config.isValid() == false)
                        continue;

                    if (config.SSID == null) {
                        if (VDBG) {
                            loge("writeKnownNetworkHistory trying to write config with null SSID");
                        }
                        continue;
                    }
                    if (VDBG) {
                        loge("writeKnownNetworkHistory write config " + config.configKey());
                    }
                    out.writeUTF(CONFIG_KEY + config.configKey() + SEPARATOR_KEY);

                    out.writeUTF(SSID_KEY + config.SSID + SEPARATOR_KEY);
                    out.writeUTF(FQDN_KEY + config.FQDN + SEPARATOR_KEY);

                    out.writeUTF(PRIORITY_KEY + Integer.toString(config.priority) + SEPARATOR_KEY);
                    out.writeUTF(STATUS_KEY + Integer.toString(config.autoJoinStatus)
                            + SEPARATOR_KEY);
                    out.writeUTF(SUPPLICANT_STATUS_KEY + Integer.toString(config.status)
                            + SEPARATOR_KEY);
                    out.writeUTF(SUPPLICANT_DISABLE_REASON_KEY
                            + Integer.toString(config.disableReason)
                            + SEPARATOR_KEY);
                    out.writeUTF(NETWORK_ID_KEY + Integer.toString(config.networkId)
                            + SEPARATOR_KEY);
                    out.writeUTF(SELF_ADDED_KEY + Boolean.toString(config.selfAdded)
                            + SEPARATOR_KEY);
                    out.writeUTF(DID_SELF_ADD_KEY + Boolean.toString(config.didSelfAdd)
                            + SEPARATOR_KEY);
                    out.writeUTF(NO_INTERNET_ACCESS_KEY
                            + Boolean.toString(config.noInternetAccess)
                            + SEPARATOR_KEY);
                    out.writeUTF(EPHEMERAL_KEY
                            + Boolean.toString(config.ephemeral)
                            + SEPARATOR_KEY);
                    if (config.peerWifiConfiguration != null) {
                        out.writeUTF(PEER_CONFIGURATION_KEY + config.peerWifiConfiguration
                                + SEPARATOR_KEY);
                    }
                    out.writeUTF(NUM_CONNECTION_FAILURES_KEY
                            + Integer.toString(config.numConnectionFailures)
                            + SEPARATOR_KEY);
                    out.writeUTF(NUM_AUTH_FAILURES_KEY
                            + Integer.toString(config.numAuthFailures)
                            + SEPARATOR_KEY);
                    out.writeUTF(NUM_IP_CONFIG_FAILURES_KEY
                            + Integer.toString(config.numIpConfigFailures)
                            + SEPARATOR_KEY);
                    out.writeUTF(SCORER_OVERRIDE_KEY + Integer.toString(config.numScorerOverride)
                            + SEPARATOR_KEY);
                    out.writeUTF(SCORER_OVERRIDE_AND_SWITCH_KEY
                            + Integer.toString(config.numScorerOverrideAndSwitchedNetwork)
                            + SEPARATOR_KEY);
                    out.writeUTF(NUM_ASSOCIATION_KEY
                            + Integer.toString(config.numAssociation)
                            + SEPARATOR_KEY);
                    //out.writeUTF(BLACKLIST_MILLI_KEY + Long.toString(config.blackListTimestamp)
                    //        + SEPARATOR_KEY);
                    out.writeUTF(CREATOR_UID_KEY + Integer.toString(config.creatorUid)
                            + SEPARATOR_KEY);
                    out.writeUTF(CONNECT_UID_KEY + Integer.toString(config.lastConnectUid)
                            + SEPARATOR_KEY);
                    out.writeUTF(UPDATE_UID_KEY + Integer.toString(config.lastUpdateUid)
                            + SEPARATOR_KEY);
                    String allowedKeyManagementString =
                            makeString(config.allowedKeyManagement,
                                    WifiConfiguration.KeyMgmt.strings);
                    out.writeUTF(AUTH_KEY + allowedKeyManagementString + SEPARATOR_KEY);

                    if (config.connectChoices != null) {
                        for (String key : config.connectChoices.keySet()) {
                            Integer choice = config.connectChoices.get(key);
                            out.writeUTF(CHOICE_KEY + key + "="
                                    + choice.toString() + SEPARATOR_KEY);
                        }
                    }
                    if (config.linkedConfigurations != null) {
                        loge("writeKnownNetworkHistory write linked "
                                + config.linkedConfigurations.size());

                        for (String key : config.linkedConfigurations.keySet()) {
                            out.writeUTF(LINK_KEY + key + SEPARATOR_KEY);
                        }
                    }

                    String macAddress = config.defaultGwMacAddress;
                    if (macAddress != null) {
                        out.writeUTF(DEFAULT_GW_KEY + macAddress + SEPARATOR_KEY);
                    }

                    if (config.scanResultCache != null) {
                        for (ScanResult result : config.scanResultCache.values()) {
                            out.writeUTF(BSSID_KEY + result.BSSID + SEPARATOR_KEY);

                            out.writeUTF(FREQ_KEY + Integer.toString(result.frequency)
                                    + SEPARATOR_KEY);

                            out.writeUTF(RSSI_KEY + Integer.toString(result.level)
                                    + SEPARATOR_KEY);

                            out.writeUTF(BSSID_STATUS_KEY
                                    + Integer.toString(result.autoJoinStatus)
                                    + SEPARATOR_KEY);

                            //if (result.seen != 0) {
                            //    out.writeUTF(MILLI_KEY + Long.toString(result.seen)
                            //            + SEPARATOR_KEY);
                            //}
                            out.writeUTF(BSSID_KEY_END + SEPARATOR_KEY);
                        }
                    }
                    if (config.lastFailure != null) {
                        out.writeUTF(FAILURE_KEY + config.lastFailure + SEPARATOR_KEY);
                    }
                    out.writeUTF(SEPARATOR_KEY);
                    // Add extra blank lines for clarity
                    out.writeUTF(SEPARATOR_KEY);
                    out.writeUTF(SEPARATOR_KEY);
                }
            }

        });
    }

    public void setLastSelectedConfiguration(int netId) {
        if (DBG) {
            loge("setLastSelectedConfiguration " + Integer.toString(netId));
        }
        if (netId == WifiConfiguration.INVALID_NETWORK_ID) {
            lastSelectedConfiguration = null;
        } else {
            WifiConfiguration selected = getWifiConfiguration(netId);
            if (selected == null) {
                lastSelectedConfiguration = null;
            } else {
                lastSelectedConfiguration = selected.configKey();
                selected.numConnectionFailures = 0;
                selected.numIpConfigFailures = 0;
                selected.numAuthFailures = 0;
                if (VDBG) {
                    loge("setLastSelectedConfiguration now: " + lastSelectedConfiguration);
                }
            }
        }
    }

    public String getLastSelectedConfiguration() {
        return lastSelectedConfiguration;
    }

    public boolean isLastSelectedConfiguration(WifiConfiguration config) {
        return (lastSelectedConfiguration != null
                && config != null
                && lastSelectedConfiguration.equals(config.configKey()));
    }

    private void readNetworkHistory() {
        if (showNetworks) {
            localLog("readNetworkHistory() path:" + networkHistoryConfigFile);
        }
        DataInputStream in = null;
        try {
            in = new DataInputStream(new BufferedInputStream(new FileInputStream(
                    networkHistoryConfigFile)));
            WifiConfiguration config = null;
            while (true) {
                int id = -1;
                String key = in.readUTF();
                String bssid = null;
                String ssid = null;

                int freq = 0;
                int status = 0;
                long seen = 0;
                int rssi = WifiConfiguration.INVALID_RSSI;
                String caps = null;
                if (key.startsWith(CONFIG_KEY)) {

                    if (config != null) {
                        config = null;
                    }
                    String configKey = key.replace(CONFIG_KEY, "");
                    configKey = configKey.replace(SEPARATOR_KEY, "");
                    // get the networkId for that config Key
                    Integer n = mNetworkIds.get(configKey.hashCode());
                    // skip reading that configuration data
                    // since we don't have a corresponding network ID
                    if (n == null) {
                        localLog("readNetworkHistory didnt find netid for hash="
                                + Integer.toString(configKey.hashCode())
                                + " key: " + configKey);
                        continue;
                    }
                    config = mConfiguredNetworks.get(n);
                    if (config == null) {
                        localLog("readNetworkHistory didnt find config for netid="
                                + n.toString()
                                + " key: " + configKey);
                    }
                    status = 0;
                    ssid = null;
                    bssid = null;
                    freq = 0;
                    seen = 0;
                    rssi = WifiConfiguration.INVALID_RSSI;
                    caps = null;

                } else if (config != null) {
                    if (key.startsWith(SSID_KEY)) {
                        ssid = key.replace(SSID_KEY, "");
                        ssid = ssid.replace(SEPARATOR_KEY, "");
                        if (config.SSID != null && !config.SSID.equals(ssid)) {
                            loge("Error parsing network history file, mismatched SSIDs");
                            config = null; //error
                            ssid = null;
                        } else {
                            config.SSID = ssid;
                        }
                    }

                    if (key.startsWith(FQDN_KEY)) {
                        String fqdn = key.replace(FQDN_KEY, "");
                        fqdn = fqdn.replace(SEPARATOR_KEY, "");
                        config.FQDN = fqdn;
                    }

                    if (key.startsWith(DEFAULT_GW_KEY)) {
                        String gateway = key.replace(DEFAULT_GW_KEY, "");
                        gateway = gateway.replace(SEPARATOR_KEY, "");
                        config.defaultGwMacAddress = gateway;
                    }

                    if (key.startsWith(STATUS_KEY)) {
                        String st = key.replace(STATUS_KEY, "");
                        st = st.replace(SEPARATOR_KEY, "");
                        config.autoJoinStatus = Integer.parseInt(st);
                    }

                    if (key.startsWith(SUPPLICANT_DISABLE_REASON_KEY)) {
                        String reason = key.replace(SUPPLICANT_DISABLE_REASON_KEY, "");
                        reason = reason.replace(SEPARATOR_KEY, "");
                        config.disableReason = Integer.parseInt(reason);
                    }

                    if (key.startsWith(SELF_ADDED_KEY)) {
                        String selfAdded = key.replace(SELF_ADDED_KEY, "");
                        selfAdded = selfAdded.replace(SEPARATOR_KEY, "");
                        config.selfAdded = Boolean.parseBoolean(selfAdded);
                    }

                    if (key.startsWith(DID_SELF_ADD_KEY)) {
                        String didSelfAdd = key.replace(DID_SELF_ADD_KEY, "");
                        didSelfAdd = didSelfAdd.replace(SEPARATOR_KEY, "");
                        config.didSelfAdd = Boolean.parseBoolean(didSelfAdd);
                    }

                    if (key.startsWith(NO_INTERNET_ACCESS_KEY)) {
                        String access = key.replace(NO_INTERNET_ACCESS_KEY, "");
                        access = access.replace(SEPARATOR_KEY, "");
                        config.noInternetAccess = Boolean.parseBoolean(access);
                    }

                    if (key.startsWith(EPHEMERAL_KEY)) {
                        String access = key.replace(EPHEMERAL_KEY, "");
                        access = access.replace(SEPARATOR_KEY, "");
                        config.ephemeral = Boolean.parseBoolean(access);
                    }

                    if (key.startsWith(CREATOR_UID_KEY)) {
                        String uid = key.replace(CREATOR_UID_KEY, "");
                        uid = uid.replace(SEPARATOR_KEY, "");
                        config.creatorUid = Integer.parseInt(uid);
                    }

                    if (key.startsWith(BLACKLIST_MILLI_KEY)) {
                        String milli = key.replace(BLACKLIST_MILLI_KEY, "");
                        milli = milli.replace(SEPARATOR_KEY, "");
                        config.blackListTimestamp = Long.parseLong(milli);
                    }

                    if (key.startsWith(NUM_CONNECTION_FAILURES_KEY)) {
                        String num = key.replace(NUM_CONNECTION_FAILURES_KEY, "");
                        num = num.replace(SEPARATOR_KEY, "");
                        config.numConnectionFailures = Integer.parseInt(num);
                    }

                    if (key.startsWith(NUM_IP_CONFIG_FAILURES_KEY)) {
                        String num = key.replace(NUM_IP_CONFIG_FAILURES_KEY, "");
                        num = num.replace(SEPARATOR_KEY, "");
                        config.numIpConfigFailures = Integer.parseInt(num);
                    }

                    if (key.startsWith(NUM_AUTH_FAILURES_KEY)) {
                        String num = key.replace(NUM_AUTH_FAILURES_KEY, "");
                        num = num.replace(SEPARATOR_KEY, "");
                        config.numIpConfigFailures = Integer.parseInt(num);
                    }

                    if (key.startsWith(SCORER_OVERRIDE_KEY)) {
                        String num = key.replace(SCORER_OVERRIDE_KEY, "");
                        num = num.replace(SEPARATOR_KEY, "");
                        config.numScorerOverride = Integer.parseInt(num);
                    }

                    if (key.startsWith(SCORER_OVERRIDE_AND_SWITCH_KEY)) {
                        String num = key.replace(SCORER_OVERRIDE_AND_SWITCH_KEY, "");
                        num = num.replace(SEPARATOR_KEY, "");
                        config.numScorerOverrideAndSwitchedNetwork = Integer.parseInt(num);
                    }

                    if (key.startsWith(NUM_ASSOCIATION_KEY)) {
                        String num = key.replace(NUM_ASSOCIATION_KEY, "");
                        num = num.replace(SEPARATOR_KEY, "");
                        config.numAssociation = Integer.parseInt(num);
                    }

                    if (key.startsWith(CONNECT_UID_KEY)) {
                        String uid = key.replace(CONNECT_UID_KEY, "");
                        uid = uid.replace(SEPARATOR_KEY, "");
                        config.lastConnectUid = Integer.parseInt(uid);
                    }

                    if (key.startsWith(UPDATE_UID_KEY)) {
                        String uid = key.replace(UPDATE_UID_KEY, "");
                        uid = uid.replace(SEPARATOR_KEY, "");
                        config.lastUpdateUid = Integer.parseInt(uid);
                    }

                    if (key.startsWith(FAILURE_KEY)) {
                        config.lastFailure = key.replace(FAILURE_KEY, "");
                        config.lastFailure = config.lastFailure.replace(SEPARATOR_KEY, "");
                    }

                    if (key.startsWith(PEER_CONFIGURATION_KEY)) {
                        config.peerWifiConfiguration = key.replace(PEER_CONFIGURATION_KEY, "");
                        config.peerWifiConfiguration =
                                config.peerWifiConfiguration.replace(SEPARATOR_KEY, "");
                    }

                    if (key.startsWith(CHOICE_KEY)) {
                        String choiceStr = key.replace(CHOICE_KEY, "");
                        choiceStr = choiceStr.replace(SEPARATOR_KEY, "");
                        String configKey = "";
                        int choice = 0;
                        Matcher match = mConnectChoice.matcher(choiceStr);
                        if (!match.find()) {
                            if (DBG) Log.d(TAG, "WifiConfigStore: connectChoice: " +
                                    " Couldnt match pattern : " + choiceStr);
                        } else {
                            configKey = match.group(1);
                            try {
                                choice = Integer.parseInt(match.group(2));
                            } catch (NumberFormatException e) {
                                choice = 0;
                            }
                            if (choice > 0) {
                                if (config.connectChoices == null) {
                                    config.connectChoices = new HashMap<String, Integer>();
                                }
                                config.connectChoices.put(configKey, choice);
                            }
                        }
                    }

                    if (key.startsWith(LINK_KEY)) {
                        String configKey = key.replace(LINK_KEY, "");
                        configKey = configKey.replace(SEPARATOR_KEY, "");
                        if (config.linkedConfigurations == null) {
                            config.linkedConfigurations = new HashMap<String, Integer>();
                        }
                        if (config.linkedConfigurations != null) {
                            config.linkedConfigurations.put(configKey, -1);
                        }
                    }

                    if (key.startsWith(BSSID_KEY)) {
                        if (key.startsWith(BSSID_KEY)) {
                            bssid = key.replace(BSSID_KEY, "");
                            bssid = bssid.replace(SEPARATOR_KEY, "");
                            freq = 0;
                            seen = 0;
                            rssi = WifiConfiguration.INVALID_RSSI;
                            caps = "";
                            status = 0;
                        }

                        if (key.startsWith(RSSI_KEY)) {
                            String lvl = key.replace(RSSI_KEY, "");
                            lvl = lvl.replace(SEPARATOR_KEY, "");
                            rssi = Integer.parseInt(lvl);
                        }

                        if (key.startsWith(BSSID_STATUS_KEY)) {
                            String st = key.replace(BSSID_STATUS_KEY, "");
                            st = st.replace(SEPARATOR_KEY, "");
                            status = Integer.parseInt(st);
                        }

                        if (key.startsWith(FREQ_KEY)) {
                            String channel = key.replace(FREQ_KEY, "");
                            channel = channel.replace(SEPARATOR_KEY, "");
                            freq = Integer.parseInt(channel);
                        }

                        if (key.startsWith(DATE_KEY)) {
                        /*
                         * when reading the configuration from file we don't update the date
                         * so as to avoid reading back stale or non-sensical data that would
                         * depend on network time.
                         * The date of a WifiConfiguration should only come from actual scan result.
                         *
                        String s = key.replace(FREQ_KEY, "");
                        seen = Integer.getInteger(s);
                        */
                        }

                        if (key.startsWith(BSSID_KEY_END)) {

                            if ((bssid != null) && (ssid != null)) {

                                if (config.scanResultCache == null) {
                                    config.scanResultCache = new HashMap<String, ScanResult>();
                                }
                                WifiSsid wssid = WifiSsid.createFromAsciiEncoded(ssid);
                                ScanResult result = new ScanResult(wssid, bssid,
                                        caps, rssi, freq, (long) 0);
                                result.seen = seen;
                                config.scanResultCache.put(bssid, result);
                                result.autoJoinStatus = status;
                            }
                        }
                    }
                }
            }
        } catch (EOFException ignore) {
            if (in != null) {
                try {
                    in.close();
                } catch (Exception e) {
                    loge("readNetworkHistory: Error reading file" + e);
                }
            }
        } catch (IOException e) {
            loge("readNetworkHistory: No config file, revert to default" + e);
        }

        if(in!=null) {
            try {
                in.close();
            } catch (Exception e) {
                loge("readNetworkHistory: Error closing file" + e);
            }
        }
    }

    private void readAutoJoinConfig() {
        BufferedReader reader = null;
        try {

            reader = new BufferedReader(new FileReader(autoJoinConfigFile));

            for (String key = reader.readLine(); key != null; key = reader.readLine()) {
                if (key != null) {
                    Log.d(TAG, "readAutoJoinConfig line: " + key);
                }
                if (key.startsWith(ENABLE_AUTO_JOIN_WHILE_ASSOCIATED_KEY)) {
                    String st = key.replace(ENABLE_AUTO_JOIN_WHILE_ASSOCIATED_KEY, "");
                    st = st.replace(SEPARATOR_KEY, "");
                    try {
                        enableAutoJoinWhenAssociated = Integer.parseInt(st) != 0;
                        Log.d(TAG,"readAutoJoinConfig: enabled = " + enableAutoJoinWhenAssociated);
                    } catch (NumberFormatException e) {
                        Log.d(TAG,"readAutoJoinConfig: incorrect format :" + key);
                    }
                }

                if (key.startsWith(ENABLE_FULL_BAND_SCAN_WHEN_ASSOCIATED_KEY)) {
                    String st = key.replace(ENABLE_FULL_BAND_SCAN_WHEN_ASSOCIATED_KEY, "");
                    st = st.replace(SEPARATOR_KEY, "");
                    try {
                        enableFullBandScanWhenAssociated = Integer.parseInt(st) != 0;
                        Log.d(TAG,"readAutoJoinConfig: enableFullBandScanWhenAssociated = "
                                + enableFullBandScanWhenAssociated);
                    } catch (NumberFormatException e) {
                        Log.d(TAG,"readAutoJoinConfig: incorrect format :" + key);
                    }
                }

                if (key.startsWith(ENABLE_AUTO_JOIN_SCAN_WHILE_ASSOCIATED_KEY)) {
                    String st = key.replace(ENABLE_AUTO_JOIN_SCAN_WHILE_ASSOCIATED_KEY, "");
                    st = st.replace(SEPARATOR_KEY, "");
                    try {
                        enableAutoJoinScanWhenAssociated = Integer.parseInt(st) != 0;
                        Log.d(TAG,"readAutoJoinConfig: enabled = "
                                + enableAutoJoinScanWhenAssociated);
                    } catch (NumberFormatException e) {
                        Log.d(TAG,"readAutoJoinConfig: incorrect format :" + key);
                    }
                }

                if (key.startsWith(ENABLE_CHIP_WAKE_UP_WHILE_ASSOCIATED_KEY)) {
                    String st = key.replace(ENABLE_CHIP_WAKE_UP_WHILE_ASSOCIATED_KEY, "");
                    st = st.replace(SEPARATOR_KEY, "");
                    try {
                        enableChipWakeUpWhenAssociated = Integer.parseInt(st) != 0;
                        Log.d(TAG,"readAutoJoinConfig: enabled = "
                                + enableChipWakeUpWhenAssociated);
                    } catch (NumberFormatException e) {
                        Log.d(TAG,"readAutoJoinConfig: incorrect format :" + key);
                    }
                }

                if (key.startsWith(ENABLE_RSSI_POLL_WHILE_ASSOCIATED_KEY)) {
                    String st = key.replace(ENABLE_RSSI_POLL_WHILE_ASSOCIATED_KEY, "");
                    st = st.replace(SEPARATOR_KEY, "");
                    try {
                        enableRssiPollWhenAssociated = Integer.parseInt(st) != 0;
                        Log.d(TAG,"readAutoJoinConfig: enabled = "
                                + enableRssiPollWhenAssociated);
                    } catch (NumberFormatException e) {
                        Log.d(TAG,"readAutoJoinConfig: incorrect format :" + key);
                    }
                }

                if (key.startsWith(THRESHOLD_INITIAL_AUTO_JOIN_ATTEMPT_RSSI_MIN_5G_KEY)) {
                    String st =
                            key.replace(THRESHOLD_INITIAL_AUTO_JOIN_ATTEMPT_RSSI_MIN_5G_KEY, "");
                    st = st.replace(SEPARATOR_KEY, "");
                    try {
                        thresholdInitialAutoJoinAttemptMin5RSSI = Integer.parseInt(st);
                        Log.d(TAG,"readAutoJoinConfig: thresholdInitialAutoJoinAttemptMin5RSSI = "
                                + Integer.toString(thresholdInitialAutoJoinAttemptMin5RSSI));
                    } catch (NumberFormatException e) {
                        Log.d(TAG,"readAutoJoinConfig: incorrect format :" + key);
                    }
                }

                if (key.startsWith(THRESHOLD_INITIAL_AUTO_JOIN_ATTEMPT_RSSI_MIN_24G_KEY)) {
                    String st =
                            key.replace(THRESHOLD_INITIAL_AUTO_JOIN_ATTEMPT_RSSI_MIN_24G_KEY, "");
                    st = st.replace(SEPARATOR_KEY, "");
                    try {
                        thresholdInitialAutoJoinAttemptMin24RSSI = Integer.parseInt(st);
                        Log.d(TAG,"readAutoJoinConfig: thresholdInitialAutoJoinAttemptMin24RSSI = "
                                + Integer.toString(thresholdInitialAutoJoinAttemptMin24RSSI));
                    } catch (NumberFormatException e) {
                        Log.d(TAG,"readAutoJoinConfig: incorrect format :" + key);
                    }
                }

                if (key.startsWith(THRESHOLD_UNBLACKLIST_HARD_5G_KEY)) {
                    String st = key.replace(THRESHOLD_UNBLACKLIST_HARD_5G_KEY, "");
                    st = st.replace(SEPARATOR_KEY, "");
                    try {
                        thresholdUnblacklistThreshold5Hard = Integer.parseInt(st);
                        Log.d(TAG,"readAutoJoinConfig: thresholdUnblacklistThreshold5Hard = "
                            + Integer.toString(thresholdUnblacklistThreshold5Hard));
                    } catch (NumberFormatException e) {
                        Log.d(TAG,"readAutoJoinConfig: incorrect format :" + key);
                    }
                }
                if (key.startsWith(THRESHOLD_UNBLACKLIST_SOFT_5G_KEY)) {
                    String st = key.replace(THRESHOLD_UNBLACKLIST_SOFT_5G_KEY, "");
                    st = st.replace(SEPARATOR_KEY, "");
                    try {
                        thresholdUnblacklistThreshold5Soft = Integer.parseInt(st);
                        Log.d(TAG,"readAutoJoinConfig: thresholdUnblacklistThreshold5Soft = "
                            + Integer.toString(thresholdUnblacklistThreshold5Soft));
                    } catch (NumberFormatException e) {
                        Log.d(TAG,"readAutoJoinConfig: incorrect format :" + key);
                    }
                }
                if (key.startsWith(THRESHOLD_UNBLACKLIST_HARD_24G_KEY)) {
                    String st = key.replace(THRESHOLD_UNBLACKLIST_HARD_24G_KEY, "");
                    st = st.replace(SEPARATOR_KEY, "");
                    try {
                        thresholdUnblacklistThreshold24Hard = Integer.parseInt(st);
                        Log.d(TAG,"readAutoJoinConfig: thresholdUnblacklistThreshold24Hard = "
                            + Integer.toString(thresholdUnblacklistThreshold24Hard));
                    } catch (NumberFormatException e) {
                        Log.d(TAG,"readAutoJoinConfig: incorrect format :" + key);
                    }
                }
                if (key.startsWith(THRESHOLD_UNBLACKLIST_SOFT_24G_KEY)) {
                    String st = key.replace(THRESHOLD_UNBLACKLIST_SOFT_24G_KEY, "");
                    st = st.replace(SEPARATOR_KEY, "");
                    try {
                        thresholdUnblacklistThreshold24Soft = Integer.parseInt(st);
                        Log.d(TAG,"readAutoJoinConfig: thresholdUnblacklistThreshold24Soft = "
                            + Integer.toString(thresholdUnblacklistThreshold24Soft));
                    } catch (NumberFormatException e) {
                        Log.d(TAG,"readAutoJoinConfig: incorrect format :" + key);
                    }
                }

                if (key.startsWith(THRESHOLD_GOOD_RSSI_5_KEY)) {
                    String st = key.replace(THRESHOLD_GOOD_RSSI_5_KEY, "");
                    st = st.replace(SEPARATOR_KEY, "");
                    try {
                        thresholdGoodRssi5 = Integer.parseInt(st);
                        Log.d(TAG,"readAutoJoinConfig: thresholdGoodRssi5 = "
                            + Integer.toString(thresholdGoodRssi5));
                    } catch (NumberFormatException e) {
                        Log.d(TAG,"readAutoJoinConfig: incorrect format :" + key);
                    }
                }
                if (key.startsWith(THRESHOLD_LOW_RSSI_5_KEY)) {
                    String st = key.replace(THRESHOLD_LOW_RSSI_5_KEY, "");
                    st = st.replace(SEPARATOR_KEY, "");
                    try {
                        thresholdLowRssi5 = Integer.parseInt(st);
                        Log.d(TAG,"readAutoJoinConfig: thresholdLowRssi5 = "
                            + Integer.toString(thresholdLowRssi5));
                    } catch (NumberFormatException e) {
                        Log.d(TAG,"readAutoJoinConfig: incorrect format :" + key);
                    }
                }
                if (key.startsWith(THRESHOLD_BAD_RSSI_5_KEY)) {
                    String st = key.replace(THRESHOLD_BAD_RSSI_5_KEY, "");
                    st = st.replace(SEPARATOR_KEY, "");
                    try {
                        thresholdBadRssi5 = Integer.parseInt(st);
                        Log.d(TAG,"readAutoJoinConfig: thresholdBadRssi5 = "
                            + Integer.toString(thresholdBadRssi5));
                    } catch (NumberFormatException e) {
                        Log.d(TAG,"readAutoJoinConfig: incorrect format :" + key);
                    }
                }

                if (key.startsWith(THRESHOLD_GOOD_RSSI_24_KEY)) {
                    String st = key.replace(THRESHOLD_GOOD_RSSI_24_KEY, "");
                    st = st.replace(SEPARATOR_KEY, "");
                    try {
                        thresholdGoodRssi24 = Integer.parseInt(st);
                        Log.d(TAG,"readAutoJoinConfig: thresholdGoodRssi24 = "
                            + Integer.toString(thresholdGoodRssi24));
                    } catch (NumberFormatException e) {
                        Log.d(TAG,"readAutoJoinConfig: incorrect format :" + key);
                    }
                }
                if (key.startsWith(THRESHOLD_LOW_RSSI_24_KEY)) {
                    String st = key.replace(THRESHOLD_LOW_RSSI_24_KEY, "");
                    st = st.replace(SEPARATOR_KEY, "");
                    try {
                        thresholdLowRssi24 = Integer.parseInt(st);
                        Log.d(TAG,"readAutoJoinConfig: thresholdLowRssi24 = "
                            + Integer.toString(thresholdLowRssi24));
                    } catch (NumberFormatException e) {
                        Log.d(TAG,"readAutoJoinConfig: incorrect format :" + key);
                    }
                }
                if (key.startsWith(THRESHOLD_BAD_RSSI_24_KEY)) {
                    String st = key.replace(THRESHOLD_BAD_RSSI_24_KEY, "");
                    st = st.replace(SEPARATOR_KEY, "");
                    try {
                        thresholdBadRssi24 = Integer.parseInt(st);
                        Log.d(TAG,"readAutoJoinConfig: thresholdBadRssi24 = "
                            + Integer.toString(thresholdBadRssi24));
                    } catch (NumberFormatException e) {
                        Log.d(TAG,"readAutoJoinConfig: incorrect format :" + key);
                    }
                }

                if (key.startsWith(THRESHOLD_MAX_TX_PACKETS_FOR_NETWORK_SWITCHING_KEY)) {
                    String st = key.replace(THRESHOLD_MAX_TX_PACKETS_FOR_NETWORK_SWITCHING_KEY, "");
                    st = st.replace(SEPARATOR_KEY, "");
                    try {
                        maxTxPacketForNetworkSwitching = Integer.parseInt(st);
                        Log.d(TAG,"readAutoJoinConfig: maxTxPacketForNetworkSwitching = "
                            + Integer.toString(maxTxPacketForNetworkSwitching));
                    } catch (NumberFormatException e) {
                        Log.d(TAG,"readAutoJoinConfig: incorrect format :" + key);
                    }
                }
                if (key.startsWith(THRESHOLD_MAX_RX_PACKETS_FOR_NETWORK_SWITCHING_KEY)) {
                    String st = key.replace(THRESHOLD_MAX_RX_PACKETS_FOR_NETWORK_SWITCHING_KEY, "");
                    st = st.replace(SEPARATOR_KEY, "");
                    try {
                        maxRxPacketForNetworkSwitching = Integer.parseInt(st);
                        Log.d(TAG,"readAutoJoinConfig: maxRxPacketForNetworkSwitching = "
                            + Integer.toString(maxRxPacketForNetworkSwitching));
                    } catch (NumberFormatException e) {
                        Log.d(TAG,"readAutoJoinConfig: incorrect format :" + key);
                    }
                }

                if (key.startsWith(THRESHOLD_MAX_TX_PACKETS_FOR_FULL_SCANS_KEY)) {
                    String st = key.replace(THRESHOLD_MAX_TX_PACKETS_FOR_FULL_SCANS_KEY, "");
                    st = st.replace(SEPARATOR_KEY, "");
                    try {
                        maxTxPacketForNetworkSwitching = Integer.parseInt(st);
                        Log.d(TAG,"readAutoJoinConfig: maxTxPacketForFullScans = "
                                + Integer.toString(maxTxPacketForFullScans));
                    } catch (NumberFormatException e) {
                        Log.d(TAG,"readAutoJoinConfig: incorrect format :" + key);
                    }
                }
                if (key.startsWith(THRESHOLD_MAX_RX_PACKETS_FOR_FULL_SCANS_KEY)) {
                    String st = key.replace(THRESHOLD_MAX_RX_PACKETS_FOR_FULL_SCANS_KEY, "");
                    st = st.replace(SEPARATOR_KEY, "");
                    try {
                        maxRxPacketForNetworkSwitching = Integer.parseInt(st);
                        Log.d(TAG,"readAutoJoinConfig: maxRxPacketForFullScans = "
                                + Integer.toString(maxRxPacketForFullScans));
                    } catch (NumberFormatException e) {
                        Log.d(TAG,"readAutoJoinConfig: incorrect format :" + key);
                    }
                }

                if (key.startsWith(THRESHOLD_MAX_TX_PACKETS_FOR_PARTIAL_SCANS_KEY)) {
                    String st = key.replace(THRESHOLD_MAX_TX_PACKETS_FOR_PARTIAL_SCANS_KEY, "");
                    st = st.replace(SEPARATOR_KEY, "");
                    try {
                        maxTxPacketForNetworkSwitching = Integer.parseInt(st);
                        Log.d(TAG,"readAutoJoinConfig: maxTxPacketForPartialScans = "
                                + Integer.toString(maxTxPacketForPartialScans));
                    } catch (NumberFormatException e) {
                        Log.d(TAG,"readAutoJoinConfig: incorrect format :" + key);
                    }
                }
                if (key.startsWith(THRESHOLD_MAX_RX_PACKETS_FOR_PARTIAL_SCANS_KEY)) {
                    String st = key.replace(THRESHOLD_MAX_RX_PACKETS_FOR_PARTIAL_SCANS_KEY, "");
                    st = st.replace(SEPARATOR_KEY, "");
                    try {
                        maxRxPacketForNetworkSwitching = Integer.parseInt(st);
                        Log.d(TAG,"readAutoJoinConfig: maxRxPacketForPartialScans = "
                                + Integer.toString(maxRxPacketForPartialScans));
                    } catch (NumberFormatException e) {
                        Log.d(TAG,"readAutoJoinConfig: incorrect format :" + key);
                    }
                }

                if (key.startsWith(WIFI_VERBOSE_LOGS_KEY)) {
                    String st = key.replace(WIFI_VERBOSE_LOGS_KEY, "");
                    st = st.replace(SEPARATOR_KEY, "");
                    try {
                        enableVerboseLogging = Integer.parseInt(st);
                        Log.d(TAG,"readAutoJoinConfig: enable verbose logs = "
                                + Integer.toString(enableVerboseLogging));
                    } catch (NumberFormatException e) {
                        Log.d(TAG,"readAutoJoinConfig: incorrect format :" + key);
                    }
                }
                if (key.startsWith(A_BAND_PREFERENCE_RSSI_THRESHOLD_KEY)) {
                    String st = key.replace(A_BAND_PREFERENCE_RSSI_THRESHOLD_KEY, "");
                    st = st.replace(SEPARATOR_KEY, "");
                    try {
                        bandPreferenceBoostThreshold5 = Integer.parseInt(st);
                        Log.d(TAG,"readAutoJoinConfig: bandPreferenceBoostThreshold5 = "
                            + Integer.toString(bandPreferenceBoostThreshold5));
                    } catch (NumberFormatException e) {
                        Log.d(TAG,"readAutoJoinConfig: incorrect format :" + key);
                    }
                }
                if (key.startsWith(ASSOCIATED_PARTIAL_SCAN_PERIOD_KEY)) {
                    String st = key.replace(ASSOCIATED_PARTIAL_SCAN_PERIOD_KEY, "");
                    st = st.replace(SEPARATOR_KEY, "");
                    try {
                        associatedPartialScanPeriodMilli = Integer.parseInt(st);
                        Log.d(TAG,"readAutoJoinConfig: associatedScanPeriod = "
                                + Integer.toString(associatedPartialScanPeriodMilli));
                    } catch (NumberFormatException e) {
                        Log.d(TAG,"readAutoJoinConfig: incorrect format :" + key);
                    }
                }
                if (key.startsWith(ASSOCIATED_FULL_SCAN_BACKOFF_KEY)) {
                    String st = key.replace(ASSOCIATED_FULL_SCAN_BACKOFF_KEY, "");
                    st = st.replace(SEPARATOR_KEY, "");
                    try {
                        associatedFullScanBackoff = Integer.parseInt(st);
                        Log.d(TAG,"readAutoJoinConfig: associatedFullScanBackoff = "
                                + Integer.toString(associatedFullScanBackoff));
                    } catch (NumberFormatException e) {
                        Log.d(TAG,"readAutoJoinConfig: incorrect format :" + key);
                    }
                }
                if (key.startsWith(G_BAND_PREFERENCE_RSSI_THRESHOLD_KEY)) {
                    String st = key.replace(G_BAND_PREFERENCE_RSSI_THRESHOLD_KEY, "");
                    st = st.replace(SEPARATOR_KEY, "");
                    try {
                        bandPreferencePenaltyThreshold5 = Integer.parseInt(st);
                        Log.d(TAG,"readAutoJoinConfig: bandPreferencePenaltyThreshold5 = "
                            + Integer.toString(bandPreferencePenaltyThreshold5));
                    } catch (NumberFormatException e) {
                        Log.d(TAG,"readAutoJoinConfig: incorrect format :" + key);
                    }
                }
                if (key.startsWith(ALWAYS_ENABLE_SCAN_WHILE_ASSOCIATED_KEY)) {
                    String st = key.replace(ALWAYS_ENABLE_SCAN_WHILE_ASSOCIATED_KEY, "");
                    st = st.replace(SEPARATOR_KEY, "");
                    try {
                        alwaysEnableScansWhileAssociated = Integer.parseInt(st);
                        Log.d(TAG,"readAutoJoinConfig: alwaysEnableScansWhileAssociated = "
                                + Integer.toString(alwaysEnableScansWhileAssociated));
                    } catch (NumberFormatException e) {
                        Log.d(TAG,"readAutoJoinConfig: incorrect format :" + key);
                    }
                }
                if (key.startsWith(MAX_NUM_PASSIVE_CHANNELS_FOR_PARTIAL_SCANS_KEY)) {
                    String st = key.replace(MAX_NUM_PASSIVE_CHANNELS_FOR_PARTIAL_SCANS_KEY, "");
                    st = st.replace(SEPARATOR_KEY, "");
                    try {
                        maxNumPassiveChannelsForPartialScans = Integer.parseInt(st);
                        Log.d(TAG,"readAutoJoinConfig: maxNumPassiveChannelsForPartialScans = "
                                + Integer.toString(maxNumPassiveChannelsForPartialScans));
                    } catch (NumberFormatException e) {
                        Log.d(TAG,"readAutoJoinConfig: incorrect format :" + key);
                    }
                }
                if (key.startsWith(MAX_NUM_ACTIVE_CHANNELS_FOR_PARTIAL_SCANS_KEY)) {
                    String st = key.replace(MAX_NUM_ACTIVE_CHANNELS_FOR_PARTIAL_SCANS_KEY, "");
                    st = st.replace(SEPARATOR_KEY, "");
                    try {
                        maxNumActiveChannelsForPartialScans = Integer.parseInt(st);
                        Log.d(TAG,"readAutoJoinConfig: maxNumActiveChannelsForPartialScans = "
                                + Integer.toString(maxNumActiveChannelsForPartialScans));
                    } catch (NumberFormatException e) {
                        Log.d(TAG,"readAutoJoinConfig: incorrect format :" + key);
                    }
                }
            }
        } catch (EOFException ignore) {
            if (reader != null) {
                try {
                    reader.close();
                    reader = null;
                } catch (Exception e) {
                    loge("readAutoJoinStatus: Error closing file" + e);
                }
            }
        } catch (FileNotFoundException ignore) {
            if (reader != null) {
                try {
                    reader.close();
                    reader = null;
                } catch (Exception e) {
                    loge("readAutoJoinStatus: Error closing file" + e);
                }
            }
        } catch (IOException e) {
            loge("readAutoJoinStatus: Error parsing configuration" + e);
        }

        if (reader!=null) {
           try {
               reader.close();
           } catch (Exception e) {
               loge("readAutoJoinStatus: Error closing file" + e);
           }
        }
    }


    private void writeIpAndProxyConfigurations() {
        final SparseArray<IpConfiguration> networks = new SparseArray<IpConfiguration>();
        for(WifiConfiguration config : mConfiguredNetworks.values()) {
            if (!config.ephemeral && config.autoJoinStatus != WifiConfiguration.AUTO_JOIN_DELETED) {
                networks.put(configKey(config), config.getIpConfiguration());
            }
        }

        super.writeIpAndProxyConfigurations(ipConfigFile, networks);
    }

    private void readIpAndProxyConfigurations() {
        SparseArray<IpConfiguration> networks = super.readIpAndProxyConfigurations(ipConfigFile);

        if (networks.size() == 0) {
            // IpConfigStore.readIpAndProxyConfigurations has already logged an error.
            return;
        }

        for (int i = 0; i < networks.size(); i++) {
            int id = networks.keyAt(i);
            WifiConfiguration config = mConfiguredNetworks.get(mNetworkIds.get(id));


            if (config == null || config.autoJoinStatus == WifiConfiguration.AUTO_JOIN_DELETED) {
                loge("configuration found for missing network, nid=" + id
                        +", ignored, networks.size=" + Integer.toString(networks.size()));
            } else {
                config.setIpConfiguration(networks.valueAt(i));
            }
        }
    }

    /*
     * Convert string to Hexadecimal before passing to wifi native layer
     * In native function "doCommand()" have trouble in converting Unicode character string to UTF8
     * conversion to hex is required because SSIDs can have space characters in them;
     * and that can confuses the supplicant because it uses space charaters as delimiters
     */

    private String encodeSSID(String str){
        String tmp = removeDoubleQuotes(str);
        return String.format("%x", new BigInteger(1, tmp.getBytes(Charset.forName("UTF-8"))));
    }

    private NetworkUpdateResult addOrUpdateNetworkNative(WifiConfiguration config, int uid) {
        /*
         * If the supplied networkId is INVALID_NETWORK_ID, we create a new empty
         * network configuration. Otherwise, the networkId should
         * refer to an existing configuration.
         */

        if (VDBG) localLog("addOrUpdateNetworkNative " + config.getPrintableSsid());

        int netId = config.networkId;
        boolean newNetwork = false;
        // networkId of INVALID_NETWORK_ID means we want to create a new network
        if (netId == INVALID_NETWORK_ID) {
            Integer savedNetId = mNetworkIds.get(configKey(config));
            // Check if either we have a network Id or a WifiConfiguration
            // matching the one we are trying to add.
            if (savedNetId == null) {
                for (WifiConfiguration test : mConfiguredNetworks.values()) {
                    if (test.configKey().equals(config.configKey())) {
                        savedNetId = test.networkId;
                        loge("addOrUpdateNetworkNative " + config.configKey()
                                + " was found, but no network Id");
                        break;
                    }
                }
            }
            if (savedNetId != null) {
                netId = savedNetId;
            } else {
                newNetwork = true;
                netId = mWifiNative.addNetwork();
                if (netId < 0) {
                    loge("Failed to add a network!");
                    return new NetworkUpdateResult(INVALID_NETWORK_ID);
                } else {
                    loge("addOrUpdateNetworkNative created netId=" + netId);
                }
            }
        }

        boolean updateFailed = true;

        setVariables: {

            if (config.SSID != null &&
                    !mWifiNative.setNetworkVariable(
                        netId,
                        WifiConfiguration.ssidVarName,
                        encodeSSID(config.SSID))) {
                loge("failed to set SSID: "+config.SSID);
                break setVariables;
            }

            if (config.BSSID != null) {
                loge("Setting BSSID for " + config.configKey() + " to " + config.BSSID);
                if (!mWifiNative.setNetworkVariable(
                        netId,
                        WifiConfiguration.bssidVarName,
                        config.BSSID)) {
                    loge("failed to set BSSID: " + config.BSSID);
                    break setVariables;
                }
            }

            String allowedKeyManagementString =
                makeString(config.allowedKeyManagement, WifiConfiguration.KeyMgmt.strings);
            if (config.allowedKeyManagement.cardinality() != 0 &&
                    !mWifiNative.setNetworkVariable(
                        netId,
                        WifiConfiguration.KeyMgmt.varName,
                        allowedKeyManagementString)) {
                loge("failed to set key_mgmt: "+
                        allowedKeyManagementString);
                break setVariables;
            }

            String allowedProtocolsString =
                makeString(config.allowedProtocols, WifiConfiguration.Protocol.strings);
            if (config.allowedProtocols.cardinality() != 0 &&
                    !mWifiNative.setNetworkVariable(
                        netId,
                        WifiConfiguration.Protocol.varName,
                        allowedProtocolsString)) {
                loge("failed to set proto: "+
                        allowedProtocolsString);
                break setVariables;
            }

            String allowedAuthAlgorithmsString =
                makeString(config.allowedAuthAlgorithms, WifiConfiguration.AuthAlgorithm.strings);
            if (config.allowedAuthAlgorithms.cardinality() != 0 &&
                    !mWifiNative.setNetworkVariable(
                        netId,
                        WifiConfiguration.AuthAlgorithm.varName,
                        allowedAuthAlgorithmsString)) {
                loge("failed to set auth_alg: "+
                        allowedAuthAlgorithmsString);
                break setVariables;
            }

            String allowedPairwiseCiphersString =
                    makeString(config.allowedPairwiseCiphers,
                    WifiConfiguration.PairwiseCipher.strings);
            if (config.allowedPairwiseCiphers.cardinality() != 0 &&
                    !mWifiNative.setNetworkVariable(
                        netId,
                        WifiConfiguration.PairwiseCipher.varName,
                        allowedPairwiseCiphersString)) {
                loge("failed to set pairwise: "+
                        allowedPairwiseCiphersString);
                break setVariables;
            }

            String allowedGroupCiphersString =
                makeString(config.allowedGroupCiphers, WifiConfiguration.GroupCipher.strings);
            if (config.allowedGroupCiphers.cardinality() != 0 &&
                    !mWifiNative.setNetworkVariable(
                        netId,
                        WifiConfiguration.GroupCipher.varName,
                        allowedGroupCiphersString)) {
                loge("failed to set group: "+
                        allowedGroupCiphersString);
                break setVariables;
            }

            // Prevent client screw-up by passing in a WifiConfiguration we gave it
            // by preventing "*" as a key.
            if (config.preSharedKey != null && !config.preSharedKey.equals("*") &&
                    !mWifiNative.setNetworkVariable(
                        netId,
                        WifiConfiguration.pskVarName,
                        config.preSharedKey)) {
                loge("failed to set psk");
                break setVariables;
            }

            boolean hasSetKey = false;
            if (config.wepKeys != null) {
                for (int i = 0; i < config.wepKeys.length; i++) {
                    // Prevent client screw-up by passing in a WifiConfiguration we gave it
                    // by preventing "*" as a key.
                    if (config.wepKeys[i] != null && !config.wepKeys[i].equals("*")) {
                        if (!mWifiNative.setNetworkVariable(
                                    netId,
                                    WifiConfiguration.wepKeyVarNames[i],
                                    config.wepKeys[i])) {
                            loge("failed to set wep_key" + i + ": " + config.wepKeys[i]);
                            break setVariables;
                        }
                        hasSetKey = true;
                    }
                }
            }

            if (hasSetKey) {
                if (!mWifiNative.setNetworkVariable(
                            netId,
                            WifiConfiguration.wepTxKeyIdxVarName,
                            Integer.toString(config.wepTxKeyIndex))) {
                    loge("failed to set wep_tx_keyidx: " + config.wepTxKeyIndex);
                    break setVariables;
                }
            }

            if (!mWifiNative.setNetworkVariable(
                        netId,
                        WifiConfiguration.priorityVarName,
                        Integer.toString(config.priority))) {
                loge(config.SSID + ": failed to set priority: "
                        +config.priority);
                break setVariables;
            }

            if (config.hiddenSSID && !mWifiNative.setNetworkVariable(
                        netId,
                        WifiConfiguration.hiddenSSIDVarName,
                        Integer.toString(config.hiddenSSID ? 1 : 0))) {
                loge(config.SSID + ": failed to set hiddenSSID: "+
                        config.hiddenSSID);
                break setVariables;
            }

            if (config.requirePMF && !mWifiNative.setNetworkVariable(
                        netId,
                        WifiConfiguration.pmfVarName,
                        "2")) {
                loge(config.SSID + ": failed to set requirePMF: "+
                        config.requirePMF);
                break setVariables;
            }

            if (config.updateIdentifier != null && !mWifiNative.setNetworkVariable(
                    netId,
                    WifiConfiguration.updateIdentiferVarName,
                    config.updateIdentifier)) {
                loge(config.SSID + ": failed to set updateIdentifier: "+
                        config.updateIdentifier);
                break setVariables;
            }

            if (config.enterpriseConfig != null &&
                    config.enterpriseConfig.getEapMethod() != WifiEnterpriseConfig.Eap.NONE) {

                WifiEnterpriseConfig enterpriseConfig = config.enterpriseConfig;

                if (needsKeyStore(enterpriseConfig)) {
                    /**
                     * Keyguard settings may eventually be controlled by device policy.
                     * We check here if keystore is unlocked before installing
                     * credentials.
                     * TODO: Do we need a dialog here ?
                     */
                    if (mKeyStore.state() != KeyStore.State.UNLOCKED) {
                        loge(config.SSID + ": key store is locked");
                        break setVariables;
                    }

                    try {
                        /* config passed may include only fields being updated.
                         * In order to generate the key id, fetch uninitialized
                         * fields from the currently tracked configuration
                         */
                        WifiConfiguration currentConfig = mConfiguredNetworks.get(netId);
                        String keyId = config.getKeyIdForCredentials(currentConfig);

                        if (!installKeys(enterpriseConfig, keyId)) {
                            loge(config.SSID + ": failed to install keys");
                            break setVariables;
                        }
                    } catch (IllegalStateException e) {
                        loge(config.SSID + " invalid config for key installation");
                        break setVariables;
                    }
                }

                HashMap<String, String> enterpriseFields = enterpriseConfig.getFields();
                for (String key : enterpriseFields.keySet()) {
                        String value = enterpriseFields.get(key);
                        if (key.equals("password") && value != null && value.equals("*")) {
                            // No need to try to set an obfuscated password, which will fail
                            continue;
                        }
                        if (!mWifiNative.setNetworkVariable(
                                    netId,
                                    key,
                                    value)) {
                            removeKeys(enterpriseConfig);
                            loge(config.SSID + ": failed to set " + key +
                                    ": " + value);
                            break setVariables;
                        }
                }
            }
            updateFailed = false;
        } // End of setVariables

        if (updateFailed) {
            if (newNetwork) {
                mWifiNative.removeNetwork(netId);
                loge("Failed to set a network variable, removed network: " + netId);
            }
            return new NetworkUpdateResult(INVALID_NETWORK_ID);
        }

        /* An update of the network variables requires reading them
         * back from the supplicant to update mConfiguredNetworks.
         * This is because some of the variables (SSID, wep keys &
         * passphrases) reflect different values when read back than
         * when written. For example, wep key is stored as * irrespective
         * of the value sent to the supplicant
         */
        WifiConfiguration currentConfig = mConfiguredNetworks.get(netId);
        if (currentConfig == null) {
            currentConfig = new WifiConfiguration();
            currentConfig.setIpAssignment(IpAssignment.DHCP);
            currentConfig.setProxySettings(ProxySettings.NONE);
            currentConfig.networkId = netId;
            if (config != null) {
                // Carry over the creation parameters
                currentConfig.selfAdded = config.selfAdded;
                currentConfig.didSelfAdd = config.didSelfAdd;
                currentConfig.ephemeral = config.ephemeral;
                currentConfig.autoJoinUseAggressiveJoinAttemptThreshold
                        = config.autoJoinUseAggressiveJoinAttemptThreshold;
                currentConfig.lastConnectUid = config.lastConnectUid;
                currentConfig.lastUpdateUid = config.lastUpdateUid;
                currentConfig.creatorUid = config.creatorUid;
                currentConfig.peerWifiConfiguration = config.peerWifiConfiguration;
            }
            if (DBG) {
                loge("created new config netId=" + Integer.toString(netId)
                        + " uid=" + Integer.toString(currentConfig.creatorUid));
            }
        }

        if (uid >= 0) {
            if (newNetwork) {
                currentConfig.creatorUid = uid;
            } else {
                currentConfig.lastUpdateUid = uid;
            }
        }

        if (newNetwork) {
            currentConfig.dirty = true;
        }

        if (currentConfig.autoJoinStatus == WifiConfiguration.AUTO_JOIN_DELETED) {
            // Make sure the configuration is not deleted anymore since we just
            // added or modified it.
            currentConfig.setAutoJoinStatus(WifiConfiguration.AUTO_JOIN_ENABLED);
            currentConfig.selfAdded = false;
            currentConfig.didSelfAdd = false;
            if (DBG) {
                loge("remove deleted status netId=" + Integer.toString(netId)
                        + " " + currentConfig.configKey());
            }
        }

        if (currentConfig.status == WifiConfiguration.Status.ENABLED) {
            // Make sure autojoin remain in sync with user modifying the configuration
            currentConfig.setAutoJoinStatus(WifiConfiguration.AUTO_JOIN_ENABLED);
        }

        if (DBG) loge("will read network variables netId=" + Integer.toString(netId));

        readNetworkVariables(currentConfig);

        mConfiguredNetworks.put(netId, currentConfig);
        mNetworkIds.put(configKey(currentConfig), netId);

        NetworkUpdateResult result = writeIpAndProxyConfigurationsOnChange(currentConfig, config);
        result.setIsNewNetwork(newNetwork);
        result.setNetworkId(netId);

        writeKnownNetworkHistory();

        return result;
    }


    /**
     * This function run thru the Saved WifiConfigurations and check if some should be linked.
     * @param config
     */
    public void linkConfiguration(WifiConfiguration config) {

        if (config.scanResultCache != null && config.scanResultCache.size() > 6) {
            // Ignore configurations with large number of BSSIDs
            return;
        }
        if (!config.allowedKeyManagement.get(KeyMgmt.WPA_PSK)) {
            // Only link WPA_PSK config
            return;
        }
        for (WifiConfiguration link : mConfiguredNetworks.values()) {
            boolean doLink = false;

            if (link.configKey().equals(config.configKey())) {
                continue;
            }

            if (link.autoJoinStatus == WifiConfiguration.AUTO_JOIN_DELETED) {
                continue;
            }

            // Autojoin will be allowed to dynamically jump from a linked configuration
            // to another, hence only link configurations that have equivalent level of security
            if (!link.allowedKeyManagement.equals(config.allowedKeyManagement)) {
                continue;
            }

            if (link.scanResultCache != null && link.scanResultCache.size() > 6) {
                // Ignore configurations with large number of BSSIDs
                continue;
            }

            if (config.defaultGwMacAddress != null && link.defaultGwMacAddress != null) {
                // If both default GW are known, link only if they are equal
                if (config.defaultGwMacAddress.equals(link.defaultGwMacAddress)) {
                    if (VDBG) {
                        loge("linkConfiguration link due to same gw " + link.SSID +
                                " and " + config.SSID + " GW " + config.defaultGwMacAddress);
                    }
                    doLink = true;
                }
            } else {
                // We do not know BOTH default gateways hence we will try to link
                // hoping that WifiConfigurations are indeed behind the same gateway.
                // once both WifiConfiguration have been tried and thus once both efault gateways
                // are known we will revisit the choice of linking them
                if ((config.scanResultCache != null) && (config.scanResultCache.size() <= 6)
                        && (link.scanResultCache != null) && (link.scanResultCache.size() <= 6)) {
                    for (String abssid : config.scanResultCache.keySet()) {
                        for (String bbssid : link.scanResultCache.keySet()) {
                            if (VVDBG) {
                                loge("linkConfiguration try to link due to DBDC BSSID match "
                                        + link.SSID +
                                        " and " + config.SSID + " bssida " + abssid
                                        + " bssidb " + bbssid);
                            }
                            if (abssid.regionMatches(true, 0, bbssid, 0, 16)) {
                                // If first 16 ascii characters of BSSID matches,
                                // we assume this is a DBDC
                                doLink = true;
                            }
                        }
                    }
                }
            }

            if (doLink == true && onlyLinkSameCredentialConfigurations) {
                String apsk = readNetworkVariableFromSupplicantFile(link.SSID, "psk");
                String bpsk = readNetworkVariableFromSupplicantFile(config.SSID, "psk");
                if (apsk == null || bpsk == null
                        || TextUtils.isEmpty(apsk) || TextUtils.isEmpty(apsk)
                        || apsk.equals("*") || apsk.equals("xxxxxxxx")
                        || !apsk.equals(bpsk)) {
                    doLink = false;
                }
            }

            if (doLink) {
                if (VDBG) {
                   loge("linkConfiguration: will link " + link.configKey()
                           + " and " + config.configKey());
                }
                if (link.linkedConfigurations == null) {
                    link.linkedConfigurations = new HashMap<String, Integer>();
                }
                if (config.linkedConfigurations == null) {
                    config.linkedConfigurations = new HashMap<String, Integer>();
                }
                if (link.linkedConfigurations.get(config.configKey()) == null) {
                    link.linkedConfigurations.put(config.configKey(), Integer.valueOf(1));
                    link.dirty = true;
                }
                if (config.linkedConfigurations.get(link.configKey()) == null) {
                    config.linkedConfigurations.put(link.configKey(), Integer.valueOf(1));
                    config.dirty = true;
                }
            } else {
                if (link.linkedConfigurations != null
                        && (link.linkedConfigurations.get(config.configKey()) != null)) {
                    if (VDBG) {
                        loge("linkConfiguration: un-link " + config.configKey()
                                + " from " + link.configKey());
                    }
                    link.dirty = true;
                    link.linkedConfigurations.remove(config.configKey());
                }
                if (config.linkedConfigurations != null
                        && (config.linkedConfigurations.get(link.configKey()) != null)) {
                    if (VDBG) {
                        loge("linkConfiguration: un-link " + link.configKey()
                                + " from " + config.configKey());
                    }
                    config.dirty = true;
                    config.linkedConfigurations.remove(link.configKey());
                }
            }
        }
    }

    /*
     * We try to link a scan result with a WifiConfiguration for which SSID and
     * key management dont match,
     * for instance, we try identify the 5GHz SSID of a DBDC AP,
     * even though we know only of the 2.4GHz
     *
     * Obviously, this function is not optimal since it is used to compare every scan
     * result with every Saved WifiConfiguration, with a string.equals operation.
     * As a speed up, might be better to implement the mConfiguredNetworks store as a
     * <String, WifiConfiguration> object instead of a <Integer, WifiConfiguration> object
     * so as to speed this up. Also to prevent the tiny probability of hash collision.
     *
     */
    public WifiConfiguration associateWithConfiguration(ScanResult result) {
        String configKey = WifiConfiguration.configKey(result);
        if (configKey == null) {
            if (DBG) loge("associateWithConfiguration(): no config key " );
            return null;
        }

        // Need to compare with quoted string
        String SSID = "\"" + result.SSID + "\"";

        if (VVDBG) {
            loge("associateWithConfiguration(): try " + configKey);
        }

        WifiConfiguration config = null;
        for (WifiConfiguration link : mConfiguredNetworks.values()) {
            boolean doLink = false;

            if (link.autoJoinStatus == WifiConfiguration.AUTO_JOIN_DELETED || link.selfAdded) {
                if (VVDBG) loge("associateWithConfiguration(): skip selfadd " + link.configKey() );
                // Make sure we dont associate the scan result to a deleted config
                continue;
            }

            if (!link.allowedKeyManagement.get(KeyMgmt.WPA_PSK)) {
                if (VVDBG) loge("associateWithConfiguration(): skip non-PSK " + link.configKey() );
                // Make sure we dont associate the scan result to a non-PSK config
                continue;
            }

            if (configKey.equals(link.configKey())) {
                if (VVDBG) loge("associateWithConfiguration(): found it!!! " + configKey );
                return link; // Found it exactly
            }

            if ((link.scanResultCache != null) && (link.scanResultCache.size() <= 6)) {
                for (String bssid : link.scanResultCache.keySet()) {
                    if (result.BSSID.regionMatches(true, 0, bssid, 0, 16)
                            && SSID.regionMatches(false, 0, link.SSID, 0, 4)) {
                        // If first 16 ascii characters of BSSID matches, and first 3
                        // characters of SSID match, we assume this is a home setup
                        // and thus we will try to transfer the password from the known
                        // BSSID/SSID to the recently found BSSID/SSID

                        // If (VDBG)
                        //    loge("associateWithConfiguration OK " );
                        doLink = true;
                        break;
                    }
                }
            }

            if (doLink) {
                // Try to make a non verified WifiConfiguration, but only if the original
                // configuration was not self already added
                if (VDBG) {
                    loge("associateWithConfiguration: will create " +
                            result.SSID + " and associate it with: " + link.SSID);
                }
                config = wifiConfigurationFromScanResult(result);
                if (config != null) {
                    config.selfAdded = true;
                    config.didSelfAdd = true;
                    config.dirty = true;
                    config.peerWifiConfiguration = link.configKey();
                    if (config.allowedKeyManagement.equals(link.allowedKeyManagement) &&
                            config.allowedKeyManagement.get(KeyMgmt.WPA_PSK)) {
                        // Transfer the credentials from the configuration we are linking from
                        String psk = readNetworkVariableFromSupplicantFile(link.SSID, "psk");
                        if (psk != null) {
                            config.preSharedKey = psk;
                            if (VDBG) {
                                if (config.preSharedKey != null)
                                    loge(" transfer PSK : " + config.preSharedKey);
                            }

                            // Link configurations
                            if (link.linkedConfigurations == null) {
                                link.linkedConfigurations = new HashMap<String, Integer>();
                            }
                            if (config.linkedConfigurations == null) {
                                config.linkedConfigurations = new HashMap<String, Integer>();
                            }
                            link.linkedConfigurations.put(config.configKey(), Integer.valueOf(1));
                            config.linkedConfigurations.put(link.configKey(), Integer.valueOf(1));
                        } else {
                            config = null;
                        }
                    } else {
                        config = null;
                    }
                    if (config != null) break;
                }
            }
        }
        return config;
    }

    public HashSet<Integer> makeChannelList(WifiConfiguration config, int age, boolean restrict) {
        if (config == null)
            return null;
        long now_ms = System.currentTimeMillis();

        HashSet<Integer> channels = new HashSet<Integer>();

        //get channels for this configuration, if there are at least 2 BSSIDs
        if (config.scanResultCache == null && config.linkedConfigurations == null) {
            return null;
        }

        if (VDBG) {
            StringBuilder dbg = new StringBuilder();
            dbg.append("makeChannelList age=" + Integer.toString(age)
                    + " for " + config.configKey()
                    + " max=" + maxNumActiveChannelsForPartialScans);
            if (config.scanResultCache != null) {
                dbg.append(" bssids=" + config.scanResultCache.size());
            }
            if (config.linkedConfigurations != null) {
                dbg.append(" linked=" + config.linkedConfigurations.size());
            }
            loge(dbg.toString());
        }

        int numChannels = 0;
        if (config.scanResultCache != null && config.scanResultCache.size() > 0) {
            for (ScanResult result : config.scanResultCache.values()) {
                //TODO : cout active and passive channels separately
                if (numChannels > maxNumActiveChannelsForPartialScans) {
                    break;
                }
                if (VDBG) {
                    boolean test = (now_ms - result.seen) < age;
                    loge("has " + result.BSSID + " freq=" + Integer.toString(result.frequency)
                            + " age=" + Long.toString(now_ms - result.seen) + " ?=" + test);
                }
                if (((now_ms - result.seen) < age)/*||(!restrict || result.is24GHz())*/) {
                    channels.add(result.frequency);
                    numChannels++;
                }
            }
        }

        //get channels for linked configurations
        if (config.linkedConfigurations != null) {
            for (String key : config.linkedConfigurations.keySet()) {
                WifiConfiguration linked = getWifiConfiguration(key);
                if (linked == null)
                    continue;
                if (linked.scanResultCache == null) {
                    continue;
                }
                for (ScanResult result : linked.scanResultCache.values()) {
                    if (VDBG) {
                        loge("has link: " + result.BSSID
                                + " freq=" + Integer.toString(result.frequency)
                                + " age=" + Long.toString(now_ms - result.seen));
                    }
                    if (numChannels > maxNumActiveChannelsForPartialScans) {
                        break;
                    }
                    if (((now_ms - result.seen) < age)/*||(!restrict || result.is24GHz())*/) {
                        channels.add(result.frequency);
                        numChannels++;
                    }
                }
            }
        }
        return channels;
    }

    // Update the WifiConfiguration database with the new scan result
    // A scan result can be associated to multiple WifiConfigurations
    public boolean updateSavedNetworkHistory(ScanResult scanResult) {
        int numConfigFound = 0;
        if (scanResult == null)
            return false;

        String SSID = "\"" + scanResult.SSID + "\"";

        for (WifiConfiguration config : mConfiguredNetworks.values()) {
            boolean found = false;

            if (config.SSID == null || !config.SSID.equals(SSID)) {
                // SSID mismatch
                if (VVDBG) {
                    loge("updateSavedNetworkHistory(): SSID mismatch " + config.configKey()
                            + " SSID=" + config.SSID + " " + SSID);
                }
                continue;
            }
            if (VDBG) {
                loge("updateSavedNetworkHistory(): try " + config.configKey()
                        + " SSID=" + config.SSID + " " + scanResult.SSID
                        + " " + scanResult.capabilities
                        + " ajst=" + config.autoJoinStatus);
            }
            if (scanResult.capabilities.contains("WEP")
                    && config.configKey().contains("WEP")) {
                found = true;
            } else if (scanResult.capabilities.contains("PSK")
                    && config.configKey().contains("PSK")) {
                found = true;
            } else if (scanResult.capabilities.contains("EAP")
                    && config.configKey().contains("EAP")) {
                found = true;
            } else if (!scanResult.capabilities.contains("WEP")
                && !scanResult.capabilities.contains("PSK")
                && !scanResult.capabilities.contains("EAP")
                && !config.configKey().contains("WEP")
                    && !config.configKey().contains("PSK")
                    && !config.configKey().contains("EAP")) {
                found = true;
            }

            if (found) {
                numConfigFound ++;

                if (config.autoJoinStatus >= WifiConfiguration.AUTO_JOIN_DELETED) {
                    if (VVDBG) {
                        loge("updateSavedNetworkHistory(): found a deleted, skip it...  "
                                + config.configKey());
                    }
                    // The scan result belongs to a deleted config:
                    //   - increment numConfigFound to remember that we found a config
                    //            matching for this scan result
                    //   - dont do anything since the config was deleted, just skip...
                    continue;
                }

                if (config.scanResultCache == null) {
                    config.scanResultCache = new HashMap<String, ScanResult>();
                }

                // Adding a new BSSID
                ScanResult result = config.scanResultCache.get(scanResult.BSSID);
                if (result == null) {
                    config.dirty = true;
                } else {
                    // transfer the black list status
                    scanResult.autoJoinStatus = result.autoJoinStatus;
                    scanResult.blackListTimestamp = result.blackListTimestamp;
                    scanResult.numIpConfigFailures = result.numIpConfigFailures;
                    scanResult.numConnection = result.numConnection;
                    scanResult.isAutoJoinCandidate = result.isAutoJoinCandidate;
                }

                // Add the scan result to this WifiConfiguration
                config.scanResultCache.put(scanResult.BSSID, scanResult);
                // Since we added a scan result to this configuration, re-attempt linking
                linkConfiguration(config);
            }

            if (VDBG && found) {
                String status = "";
                if (scanResult.autoJoinStatus > 0) {
                    status = " status=" + Integer.toString(scanResult.autoJoinStatus);
                }
                loge("        got known scan result " +
                        scanResult.BSSID + " key : "
                        + config.configKey() + " num: " +
                        Integer.toString(config.scanResultCache.size())
                        + " rssi=" + Integer.toString(scanResult.level)
                        + " freq=" + Integer.toString(scanResult.frequency)
                        + status);
            }
        }
        return numConfigFound != 0;
    }

    /* Compare current and new configuration and write to file on change */
    private NetworkUpdateResult writeIpAndProxyConfigurationsOnChange(
            WifiConfiguration currentConfig,
            WifiConfiguration newConfig) {
        boolean ipChanged = false;
        boolean proxyChanged = false;

        if (VDBG) {
            loge("writeIpAndProxyConfigurationsOnChange: " + currentConfig.SSID + " -> " +
                    newConfig.SSID + " path: " + ipConfigFile);
        }


        switch (newConfig.getIpAssignment()) {
            case STATIC:
                if (currentConfig.getIpAssignment() != newConfig.getIpAssignment()) {
                    ipChanged = true;
                } else {
                    ipChanged = !Objects.equals(
                            currentConfig.getStaticIpConfiguration(),
                            newConfig.getStaticIpConfiguration());
                }
                break;
            case DHCP:
                if (currentConfig.getIpAssignment() != newConfig.getIpAssignment()) {
                    ipChanged = true;
                }
                break;
            case UNASSIGNED:
                /* Ignore */
                break;
            default:
                loge("Ignore invalid ip assignment during write");
                break;
        }

        switch (newConfig.getProxySettings()) {
            case STATIC:
            case PAC:
                ProxyInfo newHttpProxy = newConfig.getHttpProxy();
                ProxyInfo currentHttpProxy = currentConfig.getHttpProxy();

                if (newHttpProxy != null) {
                    proxyChanged = !newHttpProxy.equals(currentHttpProxy);
                } else {
                    proxyChanged = (currentHttpProxy != null);
                }
                break;
            case NONE:
                if (currentConfig.getProxySettings() != newConfig.getProxySettings()) {
                    proxyChanged = true;
                }
                break;
            case UNASSIGNED:
                /* Ignore */
                break;
            default:
                loge("Ignore invalid proxy configuration during write");
                break;
        }

        if (ipChanged) {
            currentConfig.setIpAssignment(newConfig.getIpAssignment());
            currentConfig.setStaticIpConfiguration(newConfig.getStaticIpConfiguration());
            log("IP config changed SSID = " + currentConfig.SSID);
            if (currentConfig.getStaticIpConfiguration() != null) {
                log(" static configuration: " +
                    currentConfig.getStaticIpConfiguration().toString());
            }
        }

        if (proxyChanged) {
            currentConfig.setProxySettings(newConfig.getProxySettings());
            currentConfig.setHttpProxy(newConfig.getHttpProxy());
            log("proxy changed SSID = " + currentConfig.SSID);
            if (currentConfig.getHttpProxy() != null) {
                log(" proxyProperties: " + currentConfig.getHttpProxy().toString());
            }
        }

        if (ipChanged || proxyChanged) {
            writeIpAndProxyConfigurations();
            sendConfiguredNetworksChangedBroadcast(currentConfig,
                    WifiManager.CHANGE_REASON_CONFIG_CHANGE);
        }
        return new NetworkUpdateResult(ipChanged, proxyChanged);
    }

    /** Returns true if a particular config key needs to be quoted when passed to the supplicant. */
    private boolean enterpriseConfigKeyShouldBeQuoted(String key) {
        switch (key) {
            case WifiEnterpriseConfig.EAP_KEY:
            case WifiEnterpriseConfig.ENGINE_KEY:
                return false;
            default:
                return true;
        }
    }

    /**
     * Read the variables from the supplicant daemon that are needed to
     * fill in the WifiConfiguration object.
     *
     * @param config the {@link WifiConfiguration} object to be filled in.
     */
    private void readNetworkVariables(WifiConfiguration config) {

        int netId = config.networkId;
        if (netId < 0)
            return;

        /*
         * TODO: maybe should have a native method that takes an array of
         * variable names and returns an array of values. But we'd still
         * be doing a round trip to the supplicant daemon for each variable.
         */
        String value;

        value = mWifiNative.getNetworkVariable(netId, WifiConfiguration.ssidVarName);
        if (!TextUtils.isEmpty(value)) {
            if (value.charAt(0) != '"') {
                config.SSID = "\"" + WifiSsid.createFromHex(value).toString() + "\"";
                //TODO: convert a hex string that is not UTF-8 decodable to a P-formatted
                //supplicant string
            } else {
                config.SSID = value;
            }
        } else {
            config.SSID = null;
        }

        value = mWifiNative.getNetworkVariable(netId, WifiConfiguration.bssidVarName);
        if (!TextUtils.isEmpty(value)) {
            config.BSSID = value;
        } else {
            config.BSSID = null;
        }

        value = mWifiNative.getNetworkVariable(netId, WifiConfiguration.priorityVarName);
        config.priority = -1;
        if (!TextUtils.isEmpty(value)) {
            try {
                config.priority = Integer.parseInt(value);
            } catch (NumberFormatException ignore) {
            }
        }

        value = mWifiNative.getNetworkVariable(netId, WifiConfiguration.hiddenSSIDVarName);
        config.hiddenSSID = false;
        if (!TextUtils.isEmpty(value)) {
            try {
                config.hiddenSSID = Integer.parseInt(value) != 0;
            } catch (NumberFormatException ignore) {
            }
        }

        value = mWifiNative.getNetworkVariable(netId, WifiConfiguration.wepTxKeyIdxVarName);
        config.wepTxKeyIndex = -1;
        if (!TextUtils.isEmpty(value)) {
            try {
                config.wepTxKeyIndex = Integer.parseInt(value);
            } catch (NumberFormatException ignore) {
            }
        }

        for (int i = 0; i < 4; i++) {
            value = mWifiNative.getNetworkVariable(netId,
                    WifiConfiguration.wepKeyVarNames[i]);
            if (!TextUtils.isEmpty(value)) {
                config.wepKeys[i] = value;
            } else {
                config.wepKeys[i] = null;
            }
        }

        value = mWifiNative.getNetworkVariable(netId, WifiConfiguration.pskVarName);
        if (!TextUtils.isEmpty(value)) {
            config.preSharedKey = value;
        } else {
            config.preSharedKey = null;
        }

        value = mWifiNative.getNetworkVariable(config.networkId,
                WifiConfiguration.Protocol.varName);
        if (!TextUtils.isEmpty(value)) {
            String vals[] = value.split(" ");
            for (String val : vals) {
                int index =
                    lookupString(val, WifiConfiguration.Protocol.strings);
                if (0 <= index) {
                    config.allowedProtocols.set(index);
                }
            }
        }

        value = mWifiNative.getNetworkVariable(config.networkId,
                WifiConfiguration.KeyMgmt.varName);
        if (!TextUtils.isEmpty(value)) {
            String vals[] = value.split(" ");
            for (String val : vals) {
                int index =
                    lookupString(val, WifiConfiguration.KeyMgmt.strings);
                if (0 <= index) {
                    config.allowedKeyManagement.set(index);
                }
            }
        }

        value = mWifiNative.getNetworkVariable(config.networkId,
                WifiConfiguration.AuthAlgorithm.varName);
        if (!TextUtils.isEmpty(value)) {
            String vals[] = value.split(" ");
            for (String val : vals) {
                int index =
                    lookupString(val, WifiConfiguration.AuthAlgorithm.strings);
                if (0 <= index) {
                    config.allowedAuthAlgorithms.set(index);
                }
            }
        }

        value = mWifiNative.getNetworkVariable(config.networkId,
                WifiConfiguration.PairwiseCipher.varName);
        if (!TextUtils.isEmpty(value)) {
            String vals[] = value.split(" ");
            for (String val : vals) {
                int index =
                    lookupString(val, WifiConfiguration.PairwiseCipher.strings);
                if (0 <= index) {
                    config.allowedPairwiseCiphers.set(index);
                }
            }
        }

        value = mWifiNative.getNetworkVariable(config.networkId,
                WifiConfiguration.GroupCipher.varName);
        if (!TextUtils.isEmpty(value)) {
            String vals[] = value.split(" ");
            for (String val : vals) {
                int index =
                    lookupString(val, WifiConfiguration.GroupCipher.strings);
                if (0 <= index) {
                    config.allowedGroupCiphers.set(index);
                }
            }
        }

        if (config.enterpriseConfig == null) {
            config.enterpriseConfig = new WifiEnterpriseConfig();
        }
        HashMap<String, String> enterpriseFields = config.enterpriseConfig.getFields();
        for (String key : ENTERPRISE_CONFIG_SUPPLICANT_KEYS) {
            value = mWifiNative.getNetworkVariable(netId, key);
            if (!TextUtils.isEmpty(value)) {
                if (!enterpriseConfigKeyShouldBeQuoted(key)) {
                    value = removeDoubleQuotes(value);
                }
                enterpriseFields.put(key, value);
            } else {
                enterpriseFields.put(key, EMPTY_VALUE);
            }
        }

        if (migrateOldEapTlsNative(config.enterpriseConfig, netId)) {
            saveConfig();
        }

        migrateCerts(config.enterpriseConfig);
        // initializeSoftwareKeystoreFlag(config.enterpriseConfig, mKeyStore);
    }

    private static String removeDoubleQuotes(String string) {
        int length = string.length();
        if ((length > 1) && (string.charAt(0) == '"')
                && (string.charAt(length - 1) == '"')) {
            return string.substring(1, length - 1);
        }
        return string;
    }

    private static String makeString(BitSet set, String[] strings) {
        StringBuffer buf = new StringBuffer();
        int nextSetBit = -1;

        /* Make sure all set bits are in [0, strings.length) to avoid
         * going out of bounds on strings.  (Shouldn't happen, but...) */
        set = set.get(0, strings.length);

        while ((nextSetBit = set.nextSetBit(nextSetBit + 1)) != -1) {
            buf.append(strings[nextSetBit].replace('_', '-')).append(' ');
        }

        // remove trailing space
        if (set.cardinality() > 0) {
            buf.setLength(buf.length() - 1);
        }

        return buf.toString();
    }

    private int lookupString(String string, String[] strings) {
        int size = strings.length;

        string = string.replace('-', '_');

        for (int i = 0; i < size; i++)
            if (string.equals(strings[i]))
                return i;

        // if we ever get here, we should probably add the
        // value to WifiConfiguration to reflect that it's
        // supported by the WPA supplicant
        loge("Failed to look-up a string: " + string);

        return -1;
    }

    /* return the allowed key management based on a scan result */

    public WifiConfiguration wifiConfigurationFromScanResult(ScanResult result) {
        WifiConfiguration config = new WifiConfiguration();

        config.SSID = "\"" + result.SSID + "\"";

        if (VDBG) {
            loge("WifiConfiguration from scan results " +
                    config.SSID + " cap " + result.capabilities);
        }
        if (result.capabilities.contains("WEP")) {
            config.allowedKeyManagement.set(KeyMgmt.NONE);
            config.allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.OPEN); //?
            config.allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.SHARED);
        }

        if (result.capabilities.contains("PSK")) {
            config.allowedKeyManagement.set(KeyMgmt.WPA_PSK);
        }

        if (result.capabilities.contains("EAP")) {
            //this is probably wrong, as we don't have a way to enter the enterprise config
            config.allowedKeyManagement.set(KeyMgmt.WPA_EAP);
            config.allowedKeyManagement.set(KeyMgmt.IEEE8021X);
        }

        config.scanResultCache = new HashMap<String, ScanResult>();
        if (config.scanResultCache == null)
            return null;
        config.scanResultCache.put(result.BSSID, result);

        return config;
    }


    /* Returns a unique for a given configuration */
    private static int configKey(WifiConfiguration config) {
        String key = config.configKey();
        return key.hashCode();
    }

    void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("Dump of WifiConfigStore");
        pw.println("mLastPriority " + mLastPriority);
        pw.println("Configured networks");
        for (WifiConfiguration conf : getConfiguredNetworks()) {
            pw.println(conf);
        }
        pw.println();

        if (mLocalLog != null) {
            pw.println("WifiConfigStore - Log Begin ----");
            mLocalLog.dump(fd, pw, args);
            pw.println("WifiConfigStore - Log End ----");
        }
    }

    public String getConfigFile() {
        return ipConfigFile;
    }

    protected void loge(String s) {
        loge(s, false);
    }

    protected void loge(String s, boolean stack) {
        if (stack) {
            Log.e(TAG, s + " stack:" + Thread.currentThread().getStackTrace()[2].getMethodName()
                    + " - " + Thread.currentThread().getStackTrace()[3].getMethodName()
                    + " - " + Thread.currentThread().getStackTrace()[4].getMethodName()
                    + " - " + Thread.currentThread().getStackTrace()[5].getMethodName());
        } else {
            Log.e(TAG, s);
        }
    }

    protected void log(String s) {
        Log.d(TAG, s);
    }

    private void localLog(String s) {
        if (mLocalLog != null) {
            mLocalLog.log(s);
        }
    }

    private void localLog(String s, boolean force) {
        localLog(s);
        if (force) loge(s);
    }

    private void localLog(String s, int netId) {
        if (mLocalLog == null) {
            return;
        }

        WifiConfiguration config;
        synchronized(mConfiguredNetworks) {
            config = mConfiguredNetworks.get(netId);
        }

        if (config != null) {
            mLocalLog.log(s + " " + config.getPrintableSsid() + " " + netId
                    + " status=" + config.status
                    + " key=" + config.configKey());
        } else {
            mLocalLog.log(s + " " + netId);
        }
    }

    // Certificate and private key management for EnterpriseConfig
    static boolean needsKeyStore(WifiEnterpriseConfig config) {
        // Has no keys to be installed
        if (config.getClientCertificate() == null && config.getCaCertificate() == null)
            return false;
        return true;
    }

    static boolean isHardwareBackedKey(PrivateKey key) {
        return KeyChain.isBoundKeyAlgorithm(key.getAlgorithm());
    }

    static boolean hasHardwareBackedKey(Certificate certificate) {
        return KeyChain.isBoundKeyAlgorithm(certificate.getPublicKey().getAlgorithm());
    }

    static boolean needsSoftwareBackedKeyStore(WifiEnterpriseConfig config) {
        String client = config.getClientCertificateAlias();
        if (!TextUtils.isEmpty(client)) {
            // a valid client certificate is configured

            // BUGBUG: keyStore.get() never returns certBytes; because it is not
            // taking WIFI_UID as a parameter. It always looks for certificate
            // with SYSTEM_UID, and never finds any Wifi certificates. Assuming that
            // all certificates need software keystore until we get the get() API
            // fixed.

            return true;
        }

        /*
        try {

            if (DBG) Slog.d(TAG, "Loading client certificate " + Credentials
                    .USER_CERTIFICATE + client);

            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            if (factory == null) {
                Slog.e(TAG, "Error getting certificate factory");
                return;
            }

            byte[] certBytes = keyStore.get(Credentials.USER_CERTIFICATE + client);
            if (certBytes != null) {
                Certificate cert = (X509Certificate) factory.generateCertificate(
                        new ByteArrayInputStream(certBytes));

                if (cert != null) {
                    mNeedsSoftwareKeystore = hasHardwareBackedKey(cert);

                    if (DBG) Slog.d(TAG, "Loaded client certificate " + Credentials
                            .USER_CERTIFICATE + client);
                    if (DBG) Slog.d(TAG, "It " + (mNeedsSoftwareKeystore ? "needs" :
                            "does not need" ) + " software key store");
                } else {
                    Slog.d(TAG, "could not generate certificate");
                }
            } else {
                Slog.e(TAG, "Could not load client certificate " + Credentials
                        .USER_CERTIFICATE + client);
                mNeedsSoftwareKeystore = true;
            }

        } catch(CertificateException e) {
            Slog.e(TAG, "Could not read certificates");
            mCaCert = null;
            mClientCertificate = null;
        }
        */

        return false;
    }

    /** called when CS ask WiFistateMachine to disconnect the current network
     * because the score is bad.
     */
    void handleBadNetworkDisconnectReport(int netId, WifiInfo info) {
        /* TODO verify the bad network is current */
        WifiConfiguration config = mConfiguredNetworks.get(netId);
        if (config != null) {
            if ((info.getRssi() < WifiConfiguration.UNWANTED_BLACKLIST_SOFT_RSSI_24
                    && info.is24GHz()) || (info.getRssi() <
                            WifiConfiguration.UNWANTED_BLACKLIST_SOFT_RSSI_5 && info.is5GHz())) {
                // We got disconnected and RSSI was bad, so disable light
                config.setAutoJoinStatus(WifiConfiguration.AUTO_JOIN_TEMPORARY_DISABLED
                        + WifiConfiguration.UNWANTED_BLACKLIST_SOFT_BUMP);
                loge("handleBadNetworkDisconnectReport (+4) "
                        + Integer.toString(netId) + " " + info);
            } else {
                // We got disabled but RSSI is good, so disable hard
                config.setAutoJoinStatus(WifiConfiguration.AUTO_JOIN_TEMPORARY_DISABLED
                        + WifiConfiguration.UNWANTED_BLACKLIST_HARD_BUMP);
                loge("handleBadNetworkDisconnectReport (+8) "
                        + Integer.toString(netId) + " " + info);
            }
        }
    }

    boolean handleBSSIDBlackList(int netId, String BSSID, boolean enable) {
        boolean found = false;
        if (BSSID == null)
            return found;

        // Look for the BSSID in our config store
        for (WifiConfiguration config : mConfiguredNetworks.values()) {
            if (config.scanResultCache != null) {
                for (ScanResult result: config.scanResultCache.values()) {
                    if (result.BSSID.equals(BSSID)) {
                        if (enable) {
                            result.setAutoJoinStatus(ScanResult.ENABLED);
                        } else {
                            // Black list the BSSID we were trying to join
                            // so as the Roam state machine
                            // doesn't pick it up over and over
                            result.setAutoJoinStatus(ScanResult.AUTO_ROAM_DISABLED);
                            found = true;
                        }
                    }
                }
            }
        }
        return found;
    }

    int getMaxDhcpRetries() {
        return Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.WIFI_MAX_DHCP_RETRY_COUNT,
                DEFAULT_MAX_DHCP_RETRIES);
    }

    void handleSSIDStateChange(int netId, boolean enabled, String message, String BSSID) {
        WifiConfiguration config = mConfiguredNetworks.get(netId);
        if (config != null) {
            if (enabled) {
                loge("SSID re-enabled for  " + config.configKey() +
                        " had autoJoinStatus=" + Integer.toString(config.autoJoinStatus)
                        + " self added " + config.selfAdded + " ephemeral " + config.ephemeral);
                //TODO: http://b/16381983 Fix Wifi Network Blacklisting
                //TODO: really I don't know if re-enabling is right but we
                //TODO: should err on the side of trying to connect
                //TODO: even if the attempt will fail
                if (config.autoJoinStatus == WifiConfiguration.AUTO_JOIN_DISABLED_ON_AUTH_FAILURE) {
                    config.setAutoJoinStatus(WifiConfiguration.AUTO_JOIN_ENABLED);
                }
            } else {
                loge("SSID temp disabled for  " + config.configKey() +
                        " had autoJoinStatus=" + Integer.toString(config.autoJoinStatus)
                        + " self added " + config.selfAdded + " ephemeral " + config.ephemeral);
                if (message != null) {
                    loge(" message=" + message);
                }
                if (config.selfAdded && config.lastConnected == 0) {
                    // This is a network we self added, and we never succeeded,
                    // the user did not create this network and never entered its credentials,
                    // so we want to be very aggressive in disabling it completely.
                    removeConfigAndSendBroadcastIfNeeded(config.networkId);
                } else {
                    if (message != null) {
                        if (message.contains("no identity")) {
                            config.setAutoJoinStatus(
                                    WifiConfiguration.AUTO_JOIN_DISABLED_NO_CREDENTIALS);
                            if (DBG) {
                                loge("no identity blacklisted " + config.configKey() + " to "
                                        + Integer.toString(config.autoJoinStatus));
                            }
                        } else if (message.contains("WRONG_KEY")
                                || message.contains("AUTH_FAILED")) {
                            // This configuration has received an auth failure, so disable it
                            // temporarily because we don't want auto-join to try it out.
                            // this network may be re-enabled by the "usual"
                            // enableAllNetwork function
                            config.numAuthFailures++;
                            if (config.numAuthFailures > maxAuthErrorsToBlacklist) {
                                config.setAutoJoinStatus
                                        (WifiConfiguration.AUTO_JOIN_DISABLED_ON_AUTH_FAILURE);
                                disableNetwork(netId,
                                        WifiConfiguration.DISABLED_AUTH_FAILURE);
                                loge("Authentication failure, blacklist " + config.configKey() + " "
                                            + Integer.toString(config.networkId)
                                            + " num failures " + config.numAuthFailures);
                            }
                        } else if (message.contains("DHCP FAILURE")) {
                            config.numIpConfigFailures++;
                            config.lastConnectionFailure = System.currentTimeMillis();
                            int maxRetries = getMaxDhcpRetries();
                            // maxRetries == 0 means keep trying forever
                            if (maxRetries > 0 && config.numIpConfigFailures > maxRetries) {
                                /**
                                 * If we've exceeded the maximum number of retries for DHCP
                                 * to a given network, disable the network
                                 */
                                config.setAutoJoinStatus
                                        (WifiConfiguration.AUTO_JOIN_DISABLED_ON_AUTH_FAILURE);
                                disableNetwork(netId, WifiConfiguration.DISABLED_DHCP_FAILURE);
                                loge("DHCP failure, blacklist " + config.configKey() + " "
                                        + Integer.toString(config.networkId)
                                        + " num failures " + config.numIpConfigFailures);
                            }

                            // Also blacklist the BSSId if we find it
                            ScanResult result = null;
                            String bssidDbg = "";
                            if (config.scanResultCache != null && BSSID != null) {
                                result = config.scanResultCache.get(BSSID);
                            }
                            if (result != null) {
                                result.numIpConfigFailures ++;
                                bssidDbg = BSSID + " ipfail=" + result.numIpConfigFailures;
                                if (result.numIpConfigFailures > 3) {
                                    // Tell supplicant to stop trying this BSSID
                                    mWifiNative.addToBlacklist(BSSID);
                                    result.setAutoJoinStatus(ScanResult.AUTO_JOIN_DISABLED);
                                }
                            }

                            if (DBG) {
                                loge("blacklisted " + config.configKey() + " to "
                                        + config.autoJoinStatus
                                        + " due to IP config failures, count="
                                        + config.numIpConfigFailures
                                        + " disableReason=" + config.disableReason
                                        + " " + bssidDbg);
                            }
                        } else if (message.contains("CONN_FAILED")) {
                            config.numConnectionFailures++;
                            if (config.numConnectionFailures > maxConnectionErrorsToBlacklist) {
                                config.setAutoJoinStatus
                                        (WifiConfiguration.AUTO_JOIN_DISABLED_ON_AUTH_FAILURE);
                                disableNetwork(netId,
                                        WifiConfiguration.DISABLED_ASSOCIATION_REJECT);
                                loge("Connection failure, blacklist " + config.configKey() + " "
                                        + config.networkId
                                        + " num failures " + config.numConnectionFailures);
                            }
                        }
                        message.replace("\n", "");
                        message.replace("\r", "");
                        config.lastFailure = message;
                    }
                }
            }
        }
    }

    boolean installKeys(WifiEnterpriseConfig config, String name) {
        boolean ret = true;
        String privKeyName = Credentials.USER_PRIVATE_KEY + name;
        String userCertName = Credentials.USER_CERTIFICATE + name;
        String caCertName = Credentials.CA_CERTIFICATE + name;
        if (config.getClientCertificate() != null) {
            byte[] privKeyData = config.getClientPrivateKey().getEncoded();
            if (isHardwareBackedKey(config.getClientPrivateKey())) {
                // Hardware backed key store is secure enough to store keys un-encrypted, this
                // removes the need for user to punch a PIN to get access to these keys
                if (DBG) Log.d(TAG, "importing keys " + name + " in hardware backed store");
                ret = mKeyStore.importKey(privKeyName, privKeyData, android.os.Process.WIFI_UID,
                        KeyStore.FLAG_NONE);
            } else {
                // Software backed key store is NOT secure enough to store keys un-encrypted.
                // Save keys encrypted so they are protected with user's PIN. User will
                // have to unlock phone before being able to use these keys and connect to
                // networks.
                if (DBG) Log.d(TAG, "importing keys " + name + " in software backed store");
                ret = mKeyStore.importKey(privKeyName, privKeyData, Process.WIFI_UID,
                        KeyStore.FLAG_ENCRYPTED);
            }
            if (ret == false) {
                return ret;
            }

            ret = putCertInKeyStore(userCertName, config.getClientCertificate());
            if (ret == false) {
                // Remove private key installed
                mKeyStore.delKey(privKeyName, Process.WIFI_UID);
                return ret;
            }
        }

        if (config.getCaCertificate() != null) {
            ret = putCertInKeyStore(caCertName, config.getCaCertificate());
            if (ret == false) {
                if (config.getClientCertificate() != null) {
                    // Remove client key+cert
                    mKeyStore.delKey(privKeyName, Process.WIFI_UID);
                    mKeyStore.delete(userCertName, Process.WIFI_UID);
                }
                return ret;
            }
        }

        // Set alias names
        if (config.getClientCertificate() != null) {
            config.setClientCertificateAlias(name);
            config.resetClientKeyEntry();
        }

        if (config.getCaCertificate() != null) {
            config.setCaCertificateAlias(name);
            config.resetCaCertificate();
        }

        return ret;
    }

    private boolean putCertInKeyStore(String name, Certificate cert) {
        try {
            byte[] certData = Credentials.convertToPem(cert);
            if (DBG) Log.d(TAG, "putting certificate " + name + " in keystore");
            return mKeyStore.put(name, certData, Process.WIFI_UID, KeyStore.FLAG_NONE);

        } catch (IOException e1) {
            return false;
        } catch (CertificateException e2) {
            return false;
        }
    }

    void removeKeys(WifiEnterpriseConfig config) {
        String client = config.getClientCertificateAlias();
        // a valid client certificate is configured
        if (!TextUtils.isEmpty(client)) {
            if (DBG) Log.d(TAG, "removing client private key and user cert");
            mKeyStore.delKey(Credentials.USER_PRIVATE_KEY + client, Process.WIFI_UID);
            mKeyStore.delete(Credentials.USER_CERTIFICATE + client, Process.WIFI_UID);
        }

        String ca = config.getCaCertificateAlias();
        // a valid ca certificate is configured
        if (!TextUtils.isEmpty(ca)) {
            if (DBG) Log.d(TAG, "removing CA cert");
            mKeyStore.delete(Credentials.CA_CERTIFICATE + ca, Process.WIFI_UID);
        }
    }


    /** Migrates the old style TLS config to the new config style. This should only be used
     * when restoring an old wpa_supplicant.conf or upgrading from a previous
     * platform version.
     * @return true if the config was updated
     * @hide
     */
    boolean migrateOldEapTlsNative(WifiEnterpriseConfig config, int netId) {
        String oldPrivateKey = mWifiNative.getNetworkVariable(netId, OLD_PRIVATE_KEY_NAME);
        /*
         * If the old configuration value is not present, then there is nothing
         * to do.
         */
        if (TextUtils.isEmpty(oldPrivateKey)) {
            return false;
        } else {
            // Also ignore it if it's empty quotes.
            oldPrivateKey = removeDoubleQuotes(oldPrivateKey);
            if (TextUtils.isEmpty(oldPrivateKey)) {
                return false;
            }
        }

        config.setFieldValue(WifiEnterpriseConfig.ENGINE_KEY, WifiEnterpriseConfig.ENGINE_ENABLE);
        config.setFieldValue(WifiEnterpriseConfig.ENGINE_ID_KEY,
                WifiEnterpriseConfig.ENGINE_ID_KEYSTORE);

        /*
        * The old key started with the keystore:// URI prefix, but we don't
        * need that anymore. Trim it off if it exists.
        */
        final String keyName;
        if (oldPrivateKey.startsWith(WifiEnterpriseConfig.KEYSTORE_URI)) {
            keyName = new String(
                    oldPrivateKey.substring(WifiEnterpriseConfig.KEYSTORE_URI.length()));
        } else {
            keyName = oldPrivateKey;
        }
        config.setFieldValue(WifiEnterpriseConfig.PRIVATE_KEY_ID_KEY, keyName);

        mWifiNative.setNetworkVariable(netId, WifiEnterpriseConfig.ENGINE_KEY,
                config.getFieldValue(WifiEnterpriseConfig.ENGINE_KEY, ""));

        mWifiNative.setNetworkVariable(netId, WifiEnterpriseConfig.ENGINE_ID_KEY,
                config.getFieldValue(WifiEnterpriseConfig.ENGINE_ID_KEY, ""));

        mWifiNative.setNetworkVariable(netId, WifiEnterpriseConfig.PRIVATE_KEY_ID_KEY,
                config.getFieldValue(WifiEnterpriseConfig.PRIVATE_KEY_ID_KEY, ""));

        // Remove old private_key string so we don't run this again.
        mWifiNative.setNetworkVariable(netId, OLD_PRIVATE_KEY_NAME, EMPTY_VALUE);

        return true;
    }

    /** Migrate certs from global pool to wifi UID if not already done */
    void migrateCerts(WifiEnterpriseConfig config) {
        String client = config.getClientCertificateAlias();
        // a valid client certificate is configured
        if (!TextUtils.isEmpty(client)) {
            if (!mKeyStore.contains(Credentials.USER_PRIVATE_KEY + client, Process.WIFI_UID)) {
                mKeyStore.duplicate(Credentials.USER_PRIVATE_KEY + client, -1,
                        Credentials.USER_PRIVATE_KEY + client, Process.WIFI_UID);
                mKeyStore.duplicate(Credentials.USER_CERTIFICATE + client, -1,
                        Credentials.USER_CERTIFICATE + client, Process.WIFI_UID);
            }
        }

        String ca = config.getCaCertificateAlias();
        // a valid ca certificate is configured
        if (!TextUtils.isEmpty(ca)) {
            if (!mKeyStore.contains(Credentials.CA_CERTIFICATE + ca, Process.WIFI_UID)) {
                mKeyStore.duplicate(Credentials.CA_CERTIFICATE + ca, -1,
                        Credentials.CA_CERTIFICATE + ca, Process.WIFI_UID);
            }
        }
    }

}
