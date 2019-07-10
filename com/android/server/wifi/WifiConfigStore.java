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

import android.app.AppGlobals;
import android.app.admin.DeviceAdminInfo;
import android.app.admin.DevicePolicyManagerInternal;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.IpConfiguration;
import android.net.IpConfiguration.IpAssignment;
import android.net.IpConfiguration.ProxySettings;
import android.net.NetworkInfo.DetailedState;
import android.net.ProxyInfo;
import android.net.StaticIpConfiguration;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiConfiguration.KeyMgmt;
import android.net.wifi.WifiConfiguration.Status;
import android.net.wifi.WifiEnterpriseConfig;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiSsid;
import android.net.wifi.WpsInfo;
import android.net.wifi.WpsResult;
import android.os.Environment;
import android.os.FileObserver;
import android.os.Process;
import android.os.RemoteException;
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

import com.android.server.LocalServices;
import com.android.internal.R;
import com.android.server.net.DelayedDiskWrite;
import com.android.server.net.IpConfigStore;
import com.android.server.wifi.anqp.ANQPElement;
import com.android.server.wifi.anqp.Constants;
import com.android.server.wifi.hotspot2.ANQPData;
import com.android.server.wifi.hotspot2.AnqpCache;
import com.android.server.wifi.hotspot2.NetworkDetail;
import com.android.server.wifi.hotspot2.PasspointMatch;
import com.android.server.wifi.hotspot2.SupplicantBridge;
import com.android.server.wifi.hotspot2.Utils;
import com.android.server.wifi.hotspot2.omadm.MOManager;
import com.android.server.wifi.hotspot2.pps.Credential;
import com.android.server.wifi.hotspot2.pps.HomeSP;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
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
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.Checksum;

import static android.net.wifi.WifiConfiguration.INVALID_NETWORK_ID;


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
    public static final String TAG = "WifiConfigStore";
    private static final boolean DBG = true;
    private static boolean VDBG = false;
    private static boolean VVDBG = false;

    private static final String SUPPLICANT_CONFIG_FILE = "/data/misc/wifi/wpa_supplicant.conf";
    private static final String SUPPLICANT_CONFIG_FILE_BACKUP = SUPPLICANT_CONFIG_FILE + ".tmp";
    private static final String PPS_FILE = "/data/misc/wifi/PerProviderSubscription.conf";

    /* configured networks with network id as the key */
    private final ConfigurationMap mConfiguredNetworks = new ConfigurationMap();

    /* A network id is a unique identifier for a network configured in the
     * supplicant. Network ids are generated when the supplicant reads
     * the configuration file at start and can thus change for networks.
     * We store the IP configuration for networks along with a unique id
     * that is generated from SSID and security type of the network. A mapping
     * from the generated unique id to network id of the network is needed to
     * map supplicant config to IP configuration. */

    /* Stores a map of NetworkId to ScanCache */
    private HashMap<Integer, ScanDetailCache> mScanDetailCaches;

    /**
     * Framework keeps a list of (the CRC32 hashes of) all SSIDs that where deleted by user,
     * so as, framework knows not to re-add those SSIDs automatically to the Saved networks
     */
    private Set<Long> mDeletedSSIDs = new HashSet<Long>();

    /**
     * Framework keeps a list of ephemeral SSIDs that where deleted by user,
     * so as, framework knows not to autojoin again those SSIDs based on scorer input.
     * The list is never cleared up.
     *
     * The SSIDs are encoded in a String as per definition of WifiConfiguration.SSID field.
     */
    public Set<String> mDeletedEphemeralSSIDs = new HashSet<String>();

    /* Tracks the highest priority of configured networks */
    private int mLastPriority = -1;

    private static final String ipConfigFile = Environment.getDataDirectory() +
            "/misc/wifi/ipconfig.txt";

    private static final String networkHistoryConfigFile = Environment.getDataDirectory() +
            "/misc/wifi/networkHistory.txt";

    private static final String autoJoinConfigFile = Environment.getDataDirectory() +
            "/misc/wifi/autojoinconfig.txt";

    /* Network History Keys */
    private static final String SSID_KEY = "SSID";
    private static final String CONFIG_KEY = "CONFIG";
    private static final String CHOICE_KEY = "CHOICE";
    private static final String LINK_KEY = "LINK";
    private static final String BSSID_KEY = "BSSID";
    private static final String BSSID_KEY_END = "/BSSID";
    private static final String RSSI_KEY = "RSSI";
    private static final String FREQ_KEY = "FREQ";
    private static final String DATE_KEY = "DATE";
    private static final String MILLI_KEY = "MILLI";
    private static final String BLACKLIST_MILLI_KEY = "BLACKLIST_MILLI";
    private static final String NETWORK_ID_KEY = "ID";
    private static final String PRIORITY_KEY = "PRIORITY";
    private static final String DEFAULT_GW_KEY = "DEFAULT_GW";
    private static final String AUTH_KEY = "AUTH";
    private static final String STATUS_KEY = "AUTO_JOIN_STATUS";
    private static final String BSSID_STATUS_KEY = "BSSID_STATUS";
    private static final String SELF_ADDED_KEY = "SELF_ADDED";
    private static final String FAILURE_KEY = "FAILURE";
    private static final String DID_SELF_ADD_KEY = "DID_SELF_ADD";
    private static final String PEER_CONFIGURATION_KEY = "PEER_CONFIGURATION";
    private static final String CREATOR_UID_KEY = "CREATOR_UID_KEY";
    private static final String CONNECT_UID_KEY = "CONNECT_UID_KEY";
    private static final String UPDATE_UID_KEY = "UPDATE_UID";
    private static final String SUPPLICANT_STATUS_KEY = "SUP_STATUS";
    private static final String SUPPLICANT_DISABLE_REASON_KEY = "SUP_DIS_REASON";
    private static final String FQDN_KEY = "FQDN";
    private static final String NUM_CONNECTION_FAILURES_KEY = "CONNECT_FAILURES";
    private static final String NUM_IP_CONFIG_FAILURES_KEY = "IP_CONFIG_FAILURES";
    private static final String NUM_AUTH_FAILURES_KEY = "AUTH_FAILURES";
    private static final String SCORER_OVERRIDE_KEY = "SCORER_OVERRIDE";
    private static final String SCORER_OVERRIDE_AND_SWITCH_KEY = "SCORER_OVERRIDE_AND_SWITCH";
    private static final String VALIDATED_INTERNET_ACCESS_KEY = "VALIDATED_INTERNET_ACCESS";
    private static final String NO_INTERNET_ACCESS_REPORTS_KEY = "NO_INTERNET_ACCESS_REPORTS";
    private static final String EPHEMERAL_KEY = "EPHEMERAL";
    private static final String NUM_ASSOCIATION_KEY = "NUM_ASSOCIATION";
    private static final String DELETED_CRC32_KEY = "DELETED_CRC32";
    private static final String DELETED_EPHEMERAL_KEY = "DELETED_EPHEMERAL";
    private static final String JOIN_ATTEMPT_BOOST_KEY = "JOIN_ATTEMPT_BOOST";
    private static final String CREATOR_NAME_KEY = "CREATOR_NAME";
    private static final String UPDATE_NAME_KEY = "UPDATE_NAME";
    private static final String USER_APPROVED_KEY = "USER_APPROVED";
    private static final String CREATION_TIME_KEY = "CREATION_TIME";
    private static final String UPDATE_TIME_KEY = "UPDATE_TIME";

    private static final String SEPARATOR = ":  ";
    private static final String NL = "\n";

    private static final String THRESHOLD_INITIAL_AUTO_JOIN_ATTEMPT_RSSI_MIN_5G_KEY
            = "THRESHOLD_INITIAL_AUTO_JOIN_ATTEMPT_RSSI_MIN_5G";
    private static final String THRESHOLD_INITIAL_AUTO_JOIN_ATTEMPT_RSSI_MIN_24G_KEY
            = "THRESHOLD_INITIAL_AUTO_JOIN_ATTEMPT_RSSI_MIN_24G";
    private static final String THRESHOLD_UNBLACKLIST_HARD_5G_KEY
            = "THRESHOLD_UNBLACKLIST_HARD_5G";
    private static final String THRESHOLD_UNBLACKLIST_SOFT_5G_KEY
            = "THRESHOLD_UNBLACKLIST_SOFT_5G";
    private static final String THRESHOLD_UNBLACKLIST_HARD_24G_KEY
            = "THRESHOLD_UNBLACKLIST_HARD_24G";
    private static final String THRESHOLD_UNBLACKLIST_SOFT_24G_KEY
            = "THRESHOLD_UNBLACKLIST_SOFT_24G";
    private static final String THRESHOLD_GOOD_RSSI_5_KEY
            = "THRESHOLD_GOOD_RSSI_5";
    private static final String THRESHOLD_LOW_RSSI_5_KEY
            = "THRESHOLD_LOW_RSSI_5";
    private static final String THRESHOLD_BAD_RSSI_5_KEY
            = "THRESHOLD_BAD_RSSI_5";
    private static final String THRESHOLD_GOOD_RSSI_24_KEY
            = "THRESHOLD_GOOD_RSSI_24";
    private static final String THRESHOLD_LOW_RSSI_24_KEY
            = "THRESHOLD_LOW_RSSI_24";
    private static final String THRESHOLD_BAD_RSSI_24_KEY
            = "THRESHOLD_BAD_RSSI_24";

    private static final String THRESHOLD_MAX_TX_PACKETS_FOR_NETWORK_SWITCHING_KEY
            = "THRESHOLD_MAX_TX_PACKETS_FOR_NETWORK_SWITCHING";
    private static final String THRESHOLD_MAX_RX_PACKETS_FOR_NETWORK_SWITCHING_KEY
            = "THRESHOLD_MAX_RX_PACKETS_FOR_NETWORK_SWITCHING";

    private static final String THRESHOLD_MAX_TX_PACKETS_FOR_FULL_SCANS_KEY
            = "THRESHOLD_MAX_TX_PACKETS_FOR_FULL_SCANS";
    private static final String THRESHOLD_MAX_RX_PACKETS_FOR_FULL_SCANS_KEY
            = "THRESHOLD_MAX_RX_PACKETS_FOR_FULL_SCANS";

    private static final String THRESHOLD_MAX_TX_PACKETS_FOR_PARTIAL_SCANS_KEY
            = "THRESHOLD_MAX_TX_PACKETS_FOR_PARTIAL_SCANS";
    private static final String THRESHOLD_MAX_RX_PACKETS_FOR_PARTIAL_SCANS_KEY
            = "THRESHOLD_MAX_RX_PACKETS_FOR_PARTIAL_SCANS";

    private static final String MAX_NUM_ACTIVE_CHANNELS_FOR_PARTIAL_SCANS_KEY
            = "MAX_NUM_ACTIVE_CHANNELS_FOR_PARTIAL_SCANS";
    private static final String MAX_NUM_PASSIVE_CHANNELS_FOR_PARTIAL_SCANS_KEY
            = "MAX_NUM_PASSIVE_CHANNELS_FOR_PARTIAL_SCANS";

    private static final String A_BAND_PREFERENCE_RSSI_THRESHOLD_LOW_KEY =
            "A_BAND_PREFERENCE_RSSI_THRESHOLD_LOW";
    private static final String A_BAND_PREFERENCE_RSSI_THRESHOLD_KEY =
            "A_BAND_PREFERENCE_RSSI_THRESHOLD";
    private static final String G_BAND_PREFERENCE_RSSI_THRESHOLD_KEY =
            "G_BAND_PREFERENCE_RSSI_THRESHOLD";

    private static final String ENABLE_AUTOJOIN_WHILE_ASSOCIATED_KEY
            = "ENABLE_AUTOJOIN_WHILE_ASSOCIATED:   ";

    private static final String ASSOCIATED_PARTIAL_SCAN_PERIOD_KEY
            = "ASSOCIATED_PARTIAL_SCAN_PERIOD";
    private static final String ASSOCIATED_FULL_SCAN_BACKOFF_KEY
            = "ASSOCIATED_FULL_SCAN_BACKOFF_PERIOD";
    private static final String ALWAYS_ENABLE_SCAN_WHILE_ASSOCIATED_KEY
            = "ALWAYS_ENABLE_SCAN_WHILE_ASSOCIATED";
    private static final String ONLY_LINK_SAME_CREDENTIAL_CONFIGURATIONS_KEY
            = "ONLY_LINK_SAME_CREDENTIAL_CONFIGURATIONS";

    private static final String ENABLE_FULL_BAND_SCAN_WHEN_ASSOCIATED_KEY
            = "ENABLE_FULL_BAND_SCAN_WHEN_ASSOCIATED";

    private static final String ENABLE_HAL_BASED_PNO
            = "ENABLE_HAL_BASED_PNO";

    // The three below configurations are mainly for power stats and CPU usage tracking
    // allowing to incrementally disable framework features
    private static final String ENABLE_AUTO_JOIN_WHILE_ASSOCIATED_KEY
            = "ENABLE_AUTO_JOIN_WHILE_ASSOCIATED";
    private static final String ENABLE_CHIP_WAKE_UP_WHILE_ASSOCIATED_KEY
            = "ENABLE_CHIP_WAKE_UP_WHILE_ASSOCIATED";
    private static final String ENABLE_RSSI_POLL_WHILE_ASSOCIATED_KEY
            = "ENABLE_RSSI_POLL_WHILE_ASSOCIATED_KEY";

    public static final String idStringVarName = "id_str";

    // The Wifi verbose log is provided as a way to persist the verbose logging settings
    // for testing purpose.
    // It is not intended for normal use.
    private static final String WIFI_VERBOSE_LOGS_KEY
            = "WIFI_VERBOSE_LOGS";

    // As we keep deleted PSK WifiConfiguration for a while, the PSK of
    // those deleted WifiConfiguration is set to this random unused PSK
    private static final String DELETED_CONFIG_PSK = "Mjkd86jEMGn79KhKll298Uu7-deleted";

    public int maxTxPacketForFullScans = 8;
    public int maxRxPacketForFullScans = 16;

    public int maxTxPacketForPartialScans = 40;
    public int maxRxPacketForPartialScans = 80;

    public int associatedFullScanMaxIntervalMilli = 300000;

    // Sane value for roam blacklisting (not switching to a network if already associated)
    // 2 days
    public int networkSwitchingBlackListPeriodMilli = 2 * 24 * 60 * 60 * 1000;

    public int bandPreferenceBoostFactor5 = 5; // Boost by 5 dB per dB above threshold
    public int bandPreferencePenaltyFactor5 = 2; // Penalize by 2 dB per dB below threshold

    public int badLinkSpeed24 = 6;
    public int badLinkSpeed5 = 12;
    public int goodLinkSpeed24 = 24;
    public int goodLinkSpeed5 = 36;

    public int maxAuthErrorsToBlacklist = 4;
    public int maxConnectionErrorsToBlacklist = 4;
    public int wifiConfigBlacklistMinTimeMilli = 1000 * 60 * 5;

    // How long a disconnected config remain considered as the last user selection
    public int wifiConfigLastSelectionHysteresis = 1000 * 60 * 3;

    // Boost RSSI values of associated networks
    public int associatedHysteresisHigh = +14;
    public int associatedHysteresisLow = +8;

    boolean showNetworks = true; // TODO set this back to false, used for debugging 17516271

    public boolean roamOnAny = false;
    public boolean onlyLinkSameCredentialConfigurations = true;

    public boolean enableLinkDebouncing = true;
    public boolean enable5GHzPreference = true;
    public boolean enableWifiCellularHandoverUserTriggeredAdjustment = true;

    public int currentNetworkBoost = 25;
    public int scanResultRssiLevelPatchUp = -85;

    public static final int maxNumScanCacheEntries = 128;

    public final AtomicBoolean enableHalBasedPno = new AtomicBoolean(true);
    public final AtomicBoolean enableSsidWhitelist = new AtomicBoolean(true);
    public final AtomicBoolean enableAutoJoinWhenAssociated = new AtomicBoolean(true);
    public final AtomicBoolean enableFullBandScanWhenAssociated = new AtomicBoolean(true);
    public final AtomicBoolean enableChipWakeUpWhenAssociated = new AtomicBoolean(true);
    public final AtomicBoolean enableRssiPollWhenAssociated = new AtomicBoolean(true);
    public final AtomicInteger thresholdInitialAutoJoinAttemptMin5RSSI =
            new AtomicInteger(WifiConfiguration.INITIAL_AUTO_JOIN_ATTEMPT_MIN_5);
    public final AtomicInteger thresholdInitialAutoJoinAttemptMin24RSSI =
            new AtomicInteger(WifiConfiguration.INITIAL_AUTO_JOIN_ATTEMPT_MIN_24);
    public final AtomicInteger thresholdUnblacklistThreshold5Hard
            = new AtomicInteger(WifiConfiguration.UNBLACKLIST_THRESHOLD_5_HARD);
    public final AtomicInteger thresholdUnblacklistThreshold5Soft
            = new AtomicInteger(WifiConfiguration.UNBLACKLIST_THRESHOLD_5_SOFT);
    public final AtomicInteger thresholdUnblacklistThreshold24Hard
            = new AtomicInteger(WifiConfiguration.UNBLACKLIST_THRESHOLD_24_HARD);
    public final AtomicInteger thresholdUnblacklistThreshold24Soft
            = new AtomicInteger(WifiConfiguration.UNBLACKLIST_THRESHOLD_24_SOFT);
    public final AtomicInteger thresholdGoodRssi5 =
            new AtomicInteger(WifiConfiguration.GOOD_RSSI_5);
    public final AtomicInteger thresholdLowRssi5 = new AtomicInteger(WifiConfiguration.LOW_RSSI_5);
    public final AtomicInteger thresholdBadRssi5 = new AtomicInteger(WifiConfiguration.BAD_RSSI_5);
    public final AtomicInteger thresholdGoodRssi24 =
            new AtomicInteger(WifiConfiguration.GOOD_RSSI_24);
    public final AtomicInteger thresholdLowRssi24 = new AtomicInteger(WifiConfiguration.LOW_RSSI_24);
    public final AtomicInteger thresholdBadRssi24 = new AtomicInteger(WifiConfiguration.BAD_RSSI_24);
    public final AtomicInteger maxTxPacketForNetworkSwitching = new AtomicInteger(40);
    public final AtomicInteger maxRxPacketForNetworkSwitching = new AtomicInteger(80);
    public final AtomicInteger enableVerboseLogging = new AtomicInteger(0);
    public final AtomicInteger bandPreferenceBoostThreshold5 =
            new AtomicInteger(WifiConfiguration.A_BAND_PREFERENCE_RSSI_THRESHOLD);
    public final AtomicInteger associatedFullScanBackoff =
            new AtomicInteger(12); // Will be divided by 8 by WifiStateMachine
    public final AtomicInteger bandPreferencePenaltyThreshold5 =
            new AtomicInteger(WifiConfiguration.G_BAND_PREFERENCE_RSSI_THRESHOLD);
    public final AtomicInteger alwaysEnableScansWhileAssociated = new AtomicInteger(0);
    public final AtomicInteger maxNumPassiveChannelsForPartialScans = new AtomicInteger(2);
    public final AtomicInteger maxNumActiveChannelsForPartialScans = new AtomicInteger(6);
    public final AtomicInteger wifiDisconnectedShortScanIntervalMilli = new AtomicInteger(15000);
    public final AtomicInteger wifiDisconnectedLongScanIntervalMilli = new AtomicInteger(120000);
    public final AtomicInteger wifiAssociatedShortScanIntervalMilli = new AtomicInteger(20000);
    public final AtomicInteger wifiAssociatedLongScanIntervalMilli = new AtomicInteger(180000);

    private static final Map<String, Object> sKeyMap = new HashMap<>();

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
            WifiEnterpriseConfig.PRIVATE_KEY_ID_KEY, WifiEnterpriseConfig.ALTSUBJECT_MATCH_KEY,
            WifiEnterpriseConfig.DOM_SUFFIX_MATCH_KEY
    };


    /**
     * If Connectivity Service has triggered an unwanted network disconnect
     */
    public long lastUnwantedNetworkDisconnectTimestamp = 0;

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

    /**
     * Cached PNO list, it is updated when WifiConfiguration changes due to user input.
     */
    ArrayList<WifiNative.WifiPnoNetwork> mCachedPnoList
            = new ArrayList<WifiNative.WifiPnoNetwork>();

    /*
     * BSSID blacklist, i.e. list of BSSID we want to avoid
     */
    HashSet<String> mBssidBlacklist = new HashSet<String>();

    /*
     * Lost config list, whenever we read a config from networkHistory.txt that was not in
     * wpa_supplicant.conf
     */
    HashSet<String> mLostConfigsDbg = new HashSet<String>();

    private final AnqpCache mAnqpCache;
    private final SupplicantBridge mSupplicantBridge;
    private final MOManager mMOManager;
    private final SIMAccessor mSIMAccessor;

    private WifiStateMachine mWifiStateMachine;

    WifiConfigStore(Context c,  WifiStateMachine w, WifiNative wn) {
        mContext = c;
        mWifiNative = wn;
        mWifiStateMachine = w;

        // A map for value setting in readAutoJoinConfig() - replacing the replicated code.
        sKeyMap.put(ENABLE_AUTO_JOIN_WHILE_ASSOCIATED_KEY, enableAutoJoinWhenAssociated);
        sKeyMap.put(ENABLE_FULL_BAND_SCAN_WHEN_ASSOCIATED_KEY, enableFullBandScanWhenAssociated);
        sKeyMap.put(ENABLE_CHIP_WAKE_UP_WHILE_ASSOCIATED_KEY, enableChipWakeUpWhenAssociated);
        sKeyMap.put(ENABLE_RSSI_POLL_WHILE_ASSOCIATED_KEY, enableRssiPollWhenAssociated);
        sKeyMap.put(THRESHOLD_INITIAL_AUTO_JOIN_ATTEMPT_RSSI_MIN_5G_KEY, thresholdInitialAutoJoinAttemptMin5RSSI);
        sKeyMap.put(THRESHOLD_INITIAL_AUTO_JOIN_ATTEMPT_RSSI_MIN_24G_KEY, thresholdInitialAutoJoinAttemptMin24RSSI);
        sKeyMap.put(THRESHOLD_UNBLACKLIST_HARD_5G_KEY, thresholdUnblacklistThreshold5Hard);
        sKeyMap.put(THRESHOLD_UNBLACKLIST_SOFT_5G_KEY, thresholdUnblacklistThreshold5Soft);
        sKeyMap.put(THRESHOLD_UNBLACKLIST_HARD_24G_KEY, thresholdUnblacklistThreshold24Hard);
        sKeyMap.put(THRESHOLD_UNBLACKLIST_SOFT_24G_KEY, thresholdUnblacklistThreshold24Soft);
        sKeyMap.put(THRESHOLD_GOOD_RSSI_5_KEY, thresholdGoodRssi5);
        sKeyMap.put(THRESHOLD_LOW_RSSI_5_KEY, thresholdLowRssi5);
        sKeyMap.put(THRESHOLD_BAD_RSSI_5_KEY, thresholdBadRssi5);
        sKeyMap.put(THRESHOLD_GOOD_RSSI_24_KEY, thresholdGoodRssi24);
        sKeyMap.put(THRESHOLD_LOW_RSSI_24_KEY, thresholdLowRssi24);
        sKeyMap.put(THRESHOLD_BAD_RSSI_24_KEY, thresholdBadRssi24);
        sKeyMap.put(THRESHOLD_MAX_TX_PACKETS_FOR_NETWORK_SWITCHING_KEY, maxTxPacketForNetworkSwitching);
        sKeyMap.put(THRESHOLD_MAX_RX_PACKETS_FOR_NETWORK_SWITCHING_KEY, maxRxPacketForNetworkSwitching);
        sKeyMap.put(THRESHOLD_MAX_TX_PACKETS_FOR_FULL_SCANS_KEY, maxTxPacketForNetworkSwitching);
        sKeyMap.put(THRESHOLD_MAX_RX_PACKETS_FOR_FULL_SCANS_KEY, maxRxPacketForNetworkSwitching);
        sKeyMap.put(THRESHOLD_MAX_TX_PACKETS_FOR_PARTIAL_SCANS_KEY, maxTxPacketForNetworkSwitching);
        sKeyMap.put(THRESHOLD_MAX_RX_PACKETS_FOR_PARTIAL_SCANS_KEY, maxRxPacketForNetworkSwitching);
        sKeyMap.put(WIFI_VERBOSE_LOGS_KEY, enableVerboseLogging);
        sKeyMap.put(A_BAND_PREFERENCE_RSSI_THRESHOLD_KEY, bandPreferenceBoostThreshold5);
        sKeyMap.put(ASSOCIATED_PARTIAL_SCAN_PERIOD_KEY, wifiAssociatedShortScanIntervalMilli);
        sKeyMap.put(ASSOCIATED_PARTIAL_SCAN_PERIOD_KEY, wifiAssociatedShortScanIntervalMilli);

        sKeyMap.put(ASSOCIATED_FULL_SCAN_BACKOFF_KEY, associatedFullScanBackoff);
        sKeyMap.put(G_BAND_PREFERENCE_RSSI_THRESHOLD_KEY, bandPreferencePenaltyThreshold5);
        sKeyMap.put(ALWAYS_ENABLE_SCAN_WHILE_ASSOCIATED_KEY, alwaysEnableScansWhileAssociated);
        sKeyMap.put(MAX_NUM_PASSIVE_CHANNELS_FOR_PARTIAL_SCANS_KEY, maxNumPassiveChannelsForPartialScans);
        sKeyMap.put(MAX_NUM_ACTIVE_CHANNELS_FOR_PARTIAL_SCANS_KEY, maxNumActiveChannelsForPartialScans);
        sKeyMap.put(ENABLE_HAL_BASED_PNO, enableHalBasedPno);
        sKeyMap.put(ENABLE_HAL_BASED_PNO, enableSsidWhitelist);

        if (showNetworks) {
            mLocalLog = mWifiNative.getLocalLog();
            mFileObserver = new WpaConfigFileObserver();
            mFileObserver.startWatching();
        } else {
            mLocalLog = null;
            mFileObserver = null;
        }

        wifiAssociatedShortScanIntervalMilli.set(mContext.getResources().getInteger(
                R.integer.config_wifi_associated_short_scan_interval));
        wifiAssociatedLongScanIntervalMilli.set(mContext.getResources().getInteger(
                R.integer.config_wifi_associated_short_scan_interval));
        wifiDisconnectedShortScanIntervalMilli.set(mContext.getResources().getInteger(
                R.integer.config_wifi_disconnected_short_scan_interval));
        wifiDisconnectedLongScanIntervalMilli.set(mContext.getResources().getInteger(
                R.integer.config_wifi_disconnected_long_scan_interval));

        onlyLinkSameCredentialConfigurations = mContext.getResources().getBoolean(
                R.bool.config_wifi_only_link_same_credential_configurations);
        maxNumActiveChannelsForPartialScans.set(mContext.getResources().getInteger(
                R.integer.config_wifi_framework_associated_partial_scan_max_num_active_channels));
        maxNumPassiveChannelsForPartialScans.set(mContext.getResources().getInteger(
                R.integer.config_wifi_framework_associated_partial_scan_max_num_passive_channels));
        associatedFullScanMaxIntervalMilli = mContext.getResources().getInteger(
                R.integer.config_wifi_framework_associated_full_scan_max_interval);
        associatedFullScanBackoff.set(mContext.getResources().getInteger(
                R.integer.config_wifi_framework_associated_full_scan_backoff));
        enableLinkDebouncing = mContext.getResources().getBoolean(
                R.bool.config_wifi_enable_disconnection_debounce);

        enable5GHzPreference = mContext.getResources().getBoolean(
                R.bool.config_wifi_enable_5GHz_preference);

        bandPreferenceBoostFactor5 = mContext.getResources().getInteger(
                R.integer.config_wifi_framework_5GHz_preference_boost_factor);
        bandPreferencePenaltyFactor5 = mContext.getResources().getInteger(
                R.integer.config_wifi_framework_5GHz_preference_penalty_factor);

        bandPreferencePenaltyThreshold5.set(mContext.getResources().getInteger(
                R.integer.config_wifi_framework_5GHz_preference_penalty_threshold));
        bandPreferenceBoostThreshold5.set(mContext.getResources().getInteger(
                R.integer.config_wifi_framework_5GHz_preference_boost_threshold));

        associatedHysteresisHigh = mContext.getResources().getInteger(
                R.integer.config_wifi_framework_current_association_hysteresis_high);
        associatedHysteresisLow = mContext.getResources().getInteger(
                R.integer.config_wifi_framework_current_association_hysteresis_low);

        thresholdBadRssi5.set(mContext.getResources().getInteger(
                R.integer.config_wifi_framework_wifi_score_bad_rssi_threshold_5GHz));
        thresholdLowRssi5.set(mContext.getResources().getInteger(
                R.integer.config_wifi_framework_wifi_score_low_rssi_threshold_5GHz));
        thresholdGoodRssi5.set(mContext.getResources().getInteger(
                R.integer.config_wifi_framework_wifi_score_good_rssi_threshold_5GHz));
        thresholdBadRssi24.set(mContext.getResources().getInteger(
                R.integer.config_wifi_framework_wifi_score_bad_rssi_threshold_24GHz));
        thresholdLowRssi24.set(mContext.getResources().getInteger(
                R.integer.config_wifi_framework_wifi_score_low_rssi_threshold_24GHz));
        thresholdGoodRssi24.set(mContext.getResources().getInteger(
                R.integer.config_wifi_framework_wifi_score_good_rssi_threshold_24GHz));

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

        enableAutoJoinWhenAssociated.set(mContext.getResources().getBoolean(
                R.bool.config_wifi_framework_enable_associated_network_selection));

        currentNetworkBoost = mContext.getResources().getInteger(
                R.integer.config_wifi_framework_current_network_boost);

        scanResultRssiLevelPatchUp = mContext.getResources().getInteger(
                R.integer.config_wifi_framework_scan_result_rssi_level_patchup_value);

        networkSwitchingBlackListPeriodMilli = mContext.getResources().getInteger(
                R.integer.config_wifi_network_switching_blacklist_time);

        enableHalBasedPno.set(mContext.getResources().getBoolean(
                        R.bool.config_wifi_hal_pno_enable));

        enableSsidWhitelist.set(mContext.getResources().getBoolean(
                R.bool.config_wifi_ssid_white_list_enable));
        if (!enableHalBasedPno.get() && enableSsidWhitelist.get()) {
            enableSsidWhitelist.set(false);
        }

        boolean hs2on = mContext.getResources().getBoolean(R.bool.config_wifi_hotspot2_enabled);
        Log.d(Utils.hs2LogTag(getClass()), "Passpoint is " + (hs2on ? "enabled" : "disabled"));

        mMOManager = new MOManager(new File(PPS_FILE), hs2on);
        mAnqpCache = new AnqpCache();
        mSupplicantBridge = new SupplicantBridge(mWifiNative, this);
        mScanDetailCaches = new HashMap<>();

        mSIMAccessor = new SIMAccessor(mContext);
    }

    public void trimANQPCache(boolean all) {
        mAnqpCache.clear(all, DBG);
    }

    void enableVerboseLogging(int verbose) {
        enableVerboseLogging.set(verbose);
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

    private List<WifiConfiguration>
    getConfiguredNetworks(Map<String, String> pskMap) {
        List<WifiConfiguration> networks = new ArrayList<>();
        for(WifiConfiguration config : mConfiguredNetworks.values()) {
            WifiConfiguration newConfig = new WifiConfiguration(config);
            // When updating this condition, update WifiStateMachine's CONNECT_NETWORK handler to
            // correctly handle updating existing configs that are filtered out here.
            if (config.autoJoinStatus == WifiConfiguration.AUTO_JOIN_DELETED || config.ephemeral) {
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
     * This function returns all configuration, and is used for cebug and creating bug reports.
     */
    private List<WifiConfiguration>
    getAllConfiguredNetworks() {
        List<WifiConfiguration> networks = new ArrayList<>();
        for(WifiConfiguration config : mConfiguredNetworks.values()) {
            WifiConfiguration newConfig = new WifiConfiguration(config);
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
     * Find matching network for this scanResult
     */
    WifiConfiguration getMatchingConfig(ScanResult scanResult) {

        for (Map.Entry entry : mScanDetailCaches.entrySet()) {
            Integer netId = (Integer) entry.getKey();
            ScanDetailCache cache = (ScanDetailCache) entry.getValue();
            WifiConfiguration config = getWifiConfiguration(netId);
            if (config == null)
                continue;
            if (cache.get(scanResult.BSSID) != null) {
                return config;
            }
        }

        return null;
    }

    /**
     * Fetch the preSharedKeys for all networks.
     * @return a map from Ssid to preSharedKey.
     */
    private Map<String, String> getCredentialsBySsidMap() {
        return readNetworkVariablesFromSupplicantFile("psk");
    }

    /**
     * Fetch the list of currently configured networks that were recently seen
     *
     * @return List of networks
     */
    List<WifiConfiguration> getRecentConfiguredNetworks(int milli, boolean copy) {
        List<WifiConfiguration> networks = new ArrayList<WifiConfiguration>();

        for (WifiConfiguration config : mConfiguredNetworks.values()) {
            if (config.autoJoinStatus == WifiConfiguration.AUTO_JOIN_DELETED || config.ephemeral) {
                // Do not enumerate and return this configuration to any one,
                // instead treat it as unknown. the configuration can still be retrieved
                // directly by the key or networkId
                continue;
            }

            // Calculate the RSSI for scan results that are more recent than milli
            ScanDetailCache cache = getScanDetailCache(config);
            if (cache == null) {
                continue;
            }
            config.setVisibility(cache.getVisibility(milli));
            if (config.visibility == null) {
                continue;
            }
            if (config.visibility.rssi5 == WifiConfiguration.INVALID_RSSI &&
                    config.visibility.rssi24 == WifiConfiguration.INVALID_RSSI) {
                continue;
            }
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
        if (config != null && getScanDetailCache(config) != null) {
            ScanDetail scanDetail = getScanDetailCache(config).getScanDetail(info.getBSSID());
            if (scanDetail != null) {
                ScanResult result = scanDetail.getScanResult();
                long previousSeen = result.seen;
                int previousRssi = result.level;

                // Update the scan result
                scanDetail.setSeen();
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
        return mConfiguredNetworks.get(netId);
    }

    /**
     * Get the Wificonfiguration for this key
     * @return Wificonfiguration
     */
    WifiConfiguration getWifiConfiguration(String key) {
        return mConfiguredNetworks.getByConfigKey(key);
    }

    /**
     * Enable all networks and save config. This will be a no-op if the list
     * of configured networks indicates all networks as being enabled
     */
    void enableAllNetworks() {
        long now = System.currentTimeMillis();
        boolean networkEnabledStateChanged = false;

        for(WifiConfiguration config : mConfiguredNetworks.values()) {

            if(config != null && config.status == Status.DISABLED && !config.ephemeral
                    && (config.autoJoinStatus
                    <= WifiConfiguration.AUTO_JOIN_DISABLED_ON_AUTH_FAILURE)) {

                // Wait for 5 minutes before reenabling config that have known, repeated connection
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

    private boolean setNetworkPriorityNative(int netId, int priority) {
        return mWifiNative.setNetworkVariable(netId,
                WifiConfiguration.priorityVarName, Integer.toString(priority));
    }

    private boolean setSSIDNative(int netId, String ssid) {
        return mWifiNative.setNetworkVariable(netId, WifiConfiguration.ssidVarName,
                encodeSSID(ssid));
    }

    public boolean updateLastConnectUid(WifiConfiguration config, int uid) {
        if (config != null) {
            if (config.lastConnectUid != uid) {
                config.lastConnectUid = uid;
                config.dirty = true;
                return true;
            }
        }
        return false;
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
     * @param config network to select for connection
     * @param updatePriorities makes config highest priority network
     * @return false if the network id is invalid
     */
    boolean selectNetwork(WifiConfiguration config, boolean updatePriorities, int uid) {
        if (VDBG) localLog("selectNetwork", config.networkId);
        if (config.networkId == INVALID_NETWORK_ID) return false;

        // Reset the priority of each network at start or if it goes too high.
        if (mLastPriority == -1 || mLastPriority > 1000000) {
            for(WifiConfiguration config2 : mConfiguredNetworks.values()) {
                if (updatePriorities) {
                    if (config2.networkId != INVALID_NETWORK_ID) {
                        config2.priority = 0;
                        setNetworkPriorityNative(config2.networkId, config.priority);
                    }
                }
            }
            mLastPriority = 0;
        }

        // Set to the highest priority and save the configuration.
        if (updatePriorities) {
            config.priority = ++mLastPriority;
            setNetworkPriorityNative(config.networkId, config.priority);
            buildPnoList();
        }

        if (config.isPasspoint()) {
            /* need to slap on the SSID of selected bssid to work */
            if (getScanDetailCache(config).size() != 0) {
                ScanDetail result = getScanDetailCache(config).getFirst();
                if (result == null) {
                    loge("Could not find scan result for " + config.BSSID);
                } else {
                    log("Setting SSID for " + config.networkId + " to" + result.getSSID());
                    setSSIDNative(config.networkId, result.getSSID());
                    config.SSID = result.getSSID();
                }

            } else {
                loge("Could not find bssid for " + config);
            }
        }

        if (updatePriorities)
            mWifiNative.saveConfig();
        else
            mWifiNative.selectNetwork(config.networkId);

        updateLastConnectUid(config, uid);
        writeKnownNetworkHistory(false);

        /* Enable the given network while disabling all other networks */
        enableNetworkWithoutBroadcast(config.networkId, true);

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

        if (mDeletedEphemeralSSIDs.remove(config.SSID)) {
            if (VDBG) {
                loge("WifiConfigStore: removed from ephemeral blacklist: " + config.SSID);
            }
            // NOTE: This will be flushed to disk as part of the addOrUpdateNetworkNative call
            // below, since we're creating/modifying a config.
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

    /**
     * Firmware is roaming away from this BSSID, and this BSSID was on 5GHz, and it's RSSI was good,
     * this means we have a situation where we would want to remain on this BSSID but firmware
     * is not successful at it.
     * This situation is observed on a small number of Access Points, b/17960587
     * In that situation, blacklist this BSSID really hard so as framework will not attempt to
     * roam to it for the next 8 hours. We do not to keep flipping between 2.4 and 5GHz band..
     * TODO: review the blacklisting strategy so as to make it softer and adaptive
     * @param info
     */
    void driverRoamedFrom(WifiInfo info) {
        if (info != null
            && info.getBSSID() != null
            && ScanResult.is5GHz(info.getFrequency())
            && info.getRssi() > (bandPreferenceBoostThreshold5.get() + 3)) {
            WifiConfiguration config = getWifiConfiguration(info.getNetworkId());
            if (config != null) {
                if (getScanDetailCache(config) != null) {
                    ScanResult result = getScanDetailCache(config).get(info.getBSSID());
                    if (result != null) {
                        result.setAutoJoinStatus(ScanResult.AUTO_ROAM_DISABLED + 1);
                    }
                }
            }
        }
    }

    void noteRoamingFailure(WifiConfiguration config, int reason) {
        if (config == null) return;
        config.lastRoamingFailure = System.currentTimeMillis();
        config.roamingFailureBlackListTimeMilli
                = 2 * (config.roamingFailureBlackListTimeMilli + 1000);
        if (config.roamingFailureBlackListTimeMilli
                > networkSwitchingBlackListPeriodMilli) {
            config.roamingFailureBlackListTimeMilli =
                    networkSwitchingBlackListPeriodMilli;
        }
        config.lastRoamingFailureReason = reason;
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
     * Disable an ephemeral SSID for the purpose of auto-joining thru scored.
     * This SSID will never be scored anymore.
     * The only way to "un-disable it" is if the user create a network for that SSID and then
     * forget it.
     *
     * @param SSID caller must ensure that the SSID passed thru this API match
     *            the WifiConfiguration.SSID rules, and thus be surrounded by quotes.
     * @return the {@link WifiConfiguration} corresponding to this SSID, if any, so that we can
     *         disconnect if this is the current network.
     */
    WifiConfiguration disableEphemeralNetwork(String SSID) {
        if (SSID == null) {
            return null;
        }

        WifiConfiguration foundConfig = mConfiguredNetworks.getEphemeral(SSID);

        mDeletedEphemeralSSIDs.add(SSID);
        loge("Forget ephemeral SSID " + SSID + " num=" + mDeletedEphemeralSSIDs.size());

        if (foundConfig != null) {
            loge("Found ephemeral config in disableEphemeralNetwork: " + foundConfig.networkId);
        }

        // Force a write, because the mDeletedEphemeralSSIDs list has changed even though the
        // configurations may not have.
        writeKnownNetworkHistory(true);

        return foundConfig;
    }

    /**
     * Forget the specified network and save config
     *
     * @param netId network to forget
     * @return {@code true} if it succeeds, {@code false} otherwise
     */
    boolean forgetNetwork(int netId) {
        if (showNetworks) localLog("forgetNetwork", netId);

        WifiConfiguration config = mConfiguredNetworks.get(netId);
        boolean remove = removeConfigAndSendBroadcastIfNeeded(netId);
        if (!remove) {
            //success but we dont want to remove the network from supplicant conf file
            return true;
        }
        if (mWifiNative.removeNetwork(netId)) {
            if (config != null && config.isPasspoint()) {
                writePasspointConfigs(config.FQDN, null);
            }
            mWifiNative.saveConfig();
            writeKnownNetworkHistory(true);
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

        if (config.isPasspoint()) {
            /* create a temporary SSID with providerFriendlyName */
            Long csum = getChecksum(config.FQDN);
            config.SSID = csum.toString();
        }

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
     * Get the Wifi PNO list
     *
     * @return list of WifiNative.WifiPnoNetwork
     */
    private void buildPnoList() {
        mCachedPnoList = new ArrayList<WifiNative.WifiPnoNetwork>();

        ArrayList<WifiConfiguration> sortedWifiConfigurations
                = new ArrayList<WifiConfiguration>(getConfiguredNetworks());
        Log.e(TAG, "buildPnoList sortedWifiConfigurations size " + sortedWifiConfigurations.size());
        if (sortedWifiConfigurations.size() != 0) {
            // Sort by descending priority
            Collections.sort(sortedWifiConfigurations, new Comparator<WifiConfiguration>() {
                public int compare(WifiConfiguration a, WifiConfiguration b) {
                    return a.priority >= b.priority ? 1 : -1;
                }
            });
        }

        for (WifiConfiguration config : sortedWifiConfigurations) {
            // Initialize the RSSI threshold with sane value:
            // Use the 2.4GHz threshold since most WifiConfigurations are dual bands
            // There is very little penalty with triggering too soon, i.e. if PNO finds a network
            // that has an RSSI too low for us to attempt joining it.
            int threshold = thresholdInitialAutoJoinAttemptMin24RSSI.get();
            Log.e(TAG, "found sortedWifiConfigurations : " + config.configKey());
            WifiNative.WifiPnoNetwork network = mWifiNative.new WifiPnoNetwork(config, threshold);
            mCachedPnoList.add(network);
        }
    }

    String[] getWhiteListedSsids(WifiConfiguration config) {
        int num_ssids = 0;
        String nonQuoteSSID;
        int length;
        if (enableSsidWhitelist.get() == false)
            return null;
        List<String> list = new ArrayList<String>();
        if (config == null)
            return null;
        if (config.linkedConfigurations == null) {
            return null;
        }
        if (config.SSID == null || TextUtils.isEmpty(config.SSID)) {
            return null;
        }
        for (String configKey : config.linkedConfigurations.keySet()) {

            // Sanity check that the linked configuration is still valid
            WifiConfiguration link = getWifiConfiguration(configKey);
            if (link == null) {
                continue;
            }

            if (link.autoJoinStatus != WifiConfiguration.AUTO_JOIN_ENABLED) {
                continue;
            }

            if (link.hiddenSSID == true) {
                continue;
            }

            if (link.SSID == null || TextUtils.isEmpty(link.SSID)) {
                continue;
            }

            length = link.SSID.length();
            if (length > 2 && (link.SSID.charAt(0) == '"') && link.SSID.charAt(length - 1) == '"') {
                nonQuoteSSID = link.SSID.substring(1, length - 1);
            } else {
                nonQuoteSSID = link.SSID;
            }

            list.add(nonQuoteSSID);
        }

        if (list.size() != 0) {
            length = config.SSID.length();
            if (length > 2 && (config.SSID.charAt(0) == '"')
                    && config.SSID.charAt(length - 1) == '"') {
                nonQuoteSSID = config.SSID.substring(1, length - 1);
            } else {
                nonQuoteSSID = config.SSID;
            }

            list.add(nonQuoteSSID);
        }

        return (String[])list.toArray(new String[0]);
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
        WifiConfiguration config = mConfiguredNetworks.get(netId);
        boolean ret = mWifiNative.removeNetwork(netId);
        if (ret) {
            removeConfigAndSendBroadcastIfNeeded(netId);
            if (config != null && config.isPasspoint()) {
                writePasspointConfigs(config.FQDN, null);
            }
        }
        return ret;
    }


    static private Long getChecksum(String source) {
        Checksum csum = new CRC32();
        csum.update(source.getBytes(), 0, source.getBytes().length);
        return csum.getValue();
    }

    private boolean removeConfigAndSendBroadcastIfNeeded(int netId) {
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
                if (!TextUtils.isEmpty(config.SSID)) {
                    /* Remember that we deleted this PSK SSID */
                    if (config.SSID != null) {
                        Long csum = getChecksum(config.SSID);
                        mDeletedSSIDs.add(csum);
                        loge("removeNetwork " + Integer.toString(netId)
                                + " key=" + config.configKey()
                                + " config.id=" + Integer.toString(config.networkId)
                                + "  crc=" + csum);
                    } else {
                        loge("removeNetwork " + Integer.toString(netId)
                                + " key=" + config.configKey()
                                + " config.id=" + Integer.toString(config.networkId));
                    }
                }
            }

            mConfiguredNetworks.remove(netId);
            mScanDetailCaches.remove(netId);

            writeIpAndProxyConfigurations();
            sendConfiguredNetworksChangedBroadcast(config, WifiManager.CHANGE_REASON_REMOVED);
            writeKnownNetworkHistory(true);
        }
        return true;
    }

    /*
     * Remove all networks associated with an application
     *
     * @param packageName name of the package of networks to remove
     * @return {@code true} if all networks removed successfully, {@code false} otherwise
     */
    boolean removeNetworksForApp(ApplicationInfo app) {
        if (app == null || app.packageName == null) {
            return false;
        }

        boolean success = true;

        WifiConfiguration [] copiedConfigs =
                mConfiguredNetworks.values().toArray(new WifiConfiguration[0]);
        for (WifiConfiguration config : copiedConfigs) {
            if (app.uid != config.creatorUid || !app.packageName.equals(config.creatorName)) {
                continue;
            }
            if (showNetworks) {
                localLog("Removing network " + config.SSID
                         + ", application \"" + app.packageName + "\" uninstalled"
                         + " from user " + UserHandle.getUserId(app.uid));
            }
            success &= removeNetwork(config.networkId);
        }

        mWifiNative.saveConfig();

        return success;
    }

    boolean removeNetworksForUser(int userId) {
        boolean success = true;

        WifiConfiguration[] copiedConfigs =
                mConfiguredNetworks.values().toArray(new WifiConfiguration[0]);
        for (WifiConfiguration config : copiedConfigs) {
            if (userId != UserHandle.getUserId(config.creatorUid)) {
                continue;
            }
            success &= removeNetwork(config.networkId);
            if (showNetworks) {
                localLog("Removing network " + config.SSID
                        + ", user " + userId + " removed");
            }
        }

        return success;
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
    boolean enableNetwork(int netId, boolean disableOthers, int uid) {
        boolean ret = enableNetworkWithoutBroadcast(netId, disableOthers);
        if (disableOthers) {
            if (VDBG) localLog("enableNetwork(disableOthers=true, uid=" + uid + ") ", netId);
            updateLastConnectUid(getWifiConfiguration(netId), uid);
            writeKnownNetworkHistory(false);
            sendConfiguredNetworksChangedBroadcast();
        } else {
            if (VDBG) localLog("enableNetwork(disableOthers=false) ", netId);
            WifiConfiguration enabledNetwork;
            synchronized(mConfiguredNetworks) {                     // !!! Useless synchronization!
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
        for (WifiConfiguration enabled : mConfiguredNetworks.getEnabledNetworks()) {
            if(mWifiNative.disableNetwork(enabled.networkId)) {
                networkDisabled = true;
                enabled.status = Status.DISABLED;
            } else {
                loge("Disable network failed on " + enabled.networkId);
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
        boolean ret = disableNetwork(netId, WifiConfiguration.DISABLED_UNKNOWN_REASON);
        if (ret) {
            mWifiStateMachine.registerNetworkDisabled(netId);
        }
        return ret;
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
        if (config != null) {
            if (config.status != Status.DISABLED
                && config.disableReason != WifiConfiguration.DISABLED_BY_WIFI_MANAGER) {
                config.status = Status.DISABLED;
                config.disableReason = reason;
                network = config;
            }
            if (reason == WifiConfiguration.DISABLED_BY_WIFI_MANAGER) {
                // Make sure autojoin wont reenable this configuration without further user
                // intervention
                config.status = Status.DISABLED;
                config.autoJoinStatus = WifiConfiguration.AUTO_JOIN_DISABLED_USER_ACTION;
            }
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
     * @param netId id
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
     * @param netId id
     * @return {@code true} if using static ip for netId
     */
    boolean isUsingStaticIp(int netId) {
        WifiConfiguration config = mConfiguredNetworks.get(netId);
        if (config != null && config.getIpAssignment() == IpAssignment.STATIC) {
            return true;
        }
        return false;
    }

    boolean isEphemeral(int netId) {
        WifiConfiguration config = mConfiguredNetworks.get(netId);
        return config != null && config.ephemeral;
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

        mLastPriority = 0;

        mConfiguredNetworks.clear();

        int last_id = -1;
        boolean done = false;
        while (!done) {

            String listStr = mWifiNative.listNetworks(last_id);
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
                    last_id = config.networkId;
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

                Checksum csum = new CRC32();
                if (config.SSID != null) {
                    csum.update(config.SSID.getBytes(), 0, config.SSID.getBytes().length);
                    long d = csum.getValue();
                    if (mDeletedSSIDs.contains(d)) {
                        loge(" got CRC for SSID " + config.SSID + " -> " + d + ", was deleted");
                    }
                }

                if (config.priority > mLastPriority) {
                    mLastPriority = config.priority;
                }

                config.setIpAssignment(IpAssignment.DHCP);
                config.setProxySettings(ProxySettings.NONE);

                if (mConfiguredNetworks.getByConfigKey(config.configKey()) != null) {
                    // That SSID is already known, just ignore this duplicate entry
                    if (showNetworks) localLog("discarded duplicate network ", config.networkId);
                } else if(WifiServiceImpl.isValid(config)){
                    mConfiguredNetworks.put(config.networkId, config);
                    if (showNetworks) localLog("loaded configured network", config.networkId);
                } else {
                    if (showNetworks) log("Ignoring loaded configured for network " + config.networkId
                        + " because config are not valid");
                }
            }

            done = (lines.length == 1);
        }

        readPasspointConfig();
        readIpAndProxyConfigurations();
        readNetworkHistory();
        readAutoJoinConfig();

        buildPnoList();

        sendConfiguredNetworksChangedBroadcast();

        if (showNetworks) localLog("loadConfiguredNetworks loaded " + mConfiguredNetworks.size() + " networks");

        if (mConfiguredNetworks.isEmpty()) {
            // no networks? Lets log if the file contents
            logKernelTime();
            logContents(SUPPLICANT_CONFIG_FILE);
            logContents(SUPPLICANT_CONFIG_FILE_BACKUP);
            logContents(networkHistoryConfigFile);
        }
    }

    private void logContents(String file) {
        localLog("--- Begin " + file + " ---", true);
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader(file));
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                localLog(line, true);
            }
        } catch (FileNotFoundException e) {
            localLog("Could not open " + file + ", " + e, true);
        } catch (IOException e) {
            localLog("Could not read " + file + ", " + e, true);
        } finally {
            try {
                if (reader != null) {
                    reader.close();
                }
            } catch (IOException e) {
                // Just ignore the fact that we couldn't close
            }
        }
        localLog("--- End " + file + " Contents ---", true);
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
        long start = SystemClock.elapsedRealtimeNanos();
        Map<String, String> data = readNetworkVariablesFromSupplicantFile(key);
        long end = SystemClock.elapsedRealtimeNanos();

        if (VDBG) {
            loge("readNetworkVariableFromSupplicantFile ssid=[" + ssid + "] key=" + key
                    + " duration=" + (long)(end - start));
        }
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

    void readPasspointConfig() {

        List<HomeSP> homeSPs;
        try {
            homeSPs = mMOManager.loadAllSPs();
        } catch (IOException e) {
            loge("Could not read " + PPS_FILE + " : " + e);
            return;
        }

        mConfiguredNetworks.populatePasspointData(homeSPs, mWifiNative);
    }

    public void writePasspointConfigs(final String fqdn, final HomeSP homeSP) {
        mWriter.write(PPS_FILE, new DelayedDiskWrite.Writer() {
            @Override
            public void onWriteCalled(DataOutputStream out) throws IOException {
                try {
                    if (homeSP != null) {
                        mMOManager.addSP(homeSP);
                    }
                    else {
                        mMOManager.removeSP(fqdn);
                    }
                } catch (IOException e) {
                    loge("Could not write " + PPS_FILE + " : " + e);
                }
            }
        }, false);
    }

    public void writeKnownNetworkHistory(boolean force) {
        boolean needUpdate = force;

        /* Make a copy */
        final List<WifiConfiguration> networks = new ArrayList<WifiConfiguration>();
        for (WifiConfiguration config : mConfiguredNetworks.values()) {
            networks.add(new WifiConfiguration(config));
            if (config.dirty == true) {
                loge(" rewrite network history for " + config.configKey());
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

                    if (!WifiServiceImpl.isValid(config))
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
                    out.writeUTF(CONFIG_KEY + SEPARATOR + config.configKey() + NL);

                    if (config.SSID != null) {
                        out.writeUTF(SSID_KEY + SEPARATOR + config.SSID + NL);
                    }
                    if (config.FQDN != null) {
                        out.writeUTF(FQDN_KEY + SEPARATOR + config.FQDN + NL);
                    }

                    out.writeUTF(PRIORITY_KEY + SEPARATOR +
                            Integer.toString(config.priority) + NL);
                    out.writeUTF(STATUS_KEY + SEPARATOR +
                            Integer.toString(config.autoJoinStatus) + NL);
                    out.writeUTF(SUPPLICANT_STATUS_KEY + SEPARATOR +
                            Integer.toString(config.status) + NL);
                    out.writeUTF(SUPPLICANT_DISABLE_REASON_KEY + SEPARATOR +
                            Integer.toString(config.disableReason) + NL);
                    out.writeUTF(NETWORK_ID_KEY + SEPARATOR +
                            Integer.toString(config.networkId) + NL);
                    out.writeUTF(SELF_ADDED_KEY + SEPARATOR +
                            Boolean.toString(config.selfAdded) + NL);
                    out.writeUTF(DID_SELF_ADD_KEY + SEPARATOR +
                            Boolean.toString(config.didSelfAdd) + NL);
                    out.writeUTF(NO_INTERNET_ACCESS_REPORTS_KEY + SEPARATOR +
                            Integer.toString(config.numNoInternetAccessReports) + NL);
                    out.writeUTF(VALIDATED_INTERNET_ACCESS_KEY + SEPARATOR +
                            Boolean.toString(config.validatedInternetAccess) + NL);
                    out.writeUTF(EPHEMERAL_KEY + SEPARATOR +
                            Boolean.toString(config.ephemeral) + NL);
                    if (config.creationTime != null) {
                        out.writeUTF(CREATION_TIME_KEY + SEPARATOR + config.creationTime + NL);
                    }
                    if (config.updateTime != null) {
                        out.writeUTF(UPDATE_TIME_KEY + SEPARATOR + config.updateTime + NL);
                    }
                    if (config.peerWifiConfiguration != null) {
                        out.writeUTF(PEER_CONFIGURATION_KEY + SEPARATOR +
                                config.peerWifiConfiguration + NL);
                    }
                    out.writeUTF(NUM_CONNECTION_FAILURES_KEY + SEPARATOR +
                            Integer.toString(config.numConnectionFailures) + NL);
                    out.writeUTF(NUM_AUTH_FAILURES_KEY + SEPARATOR +
                            Integer.toString(config.numAuthFailures) + NL);
                    out.writeUTF(NUM_IP_CONFIG_FAILURES_KEY + SEPARATOR +
                            Integer.toString(config.numIpConfigFailures) + NL);
                    out.writeUTF(SCORER_OVERRIDE_KEY + SEPARATOR +
                            Integer.toString(config.numScorerOverride) + NL);
                    out.writeUTF(SCORER_OVERRIDE_AND_SWITCH_KEY + SEPARATOR +
                            Integer.toString(config.numScorerOverrideAndSwitchedNetwork) + NL);
                    out.writeUTF(NUM_ASSOCIATION_KEY + SEPARATOR +
                            Integer.toString(config.numAssociation) + NL);
                    out.writeUTF(JOIN_ATTEMPT_BOOST_KEY + SEPARATOR +
                            Integer.toString(config.autoJoinUseAggressiveJoinAttemptThreshold)+ NL);
                    //out.writeUTF(BLACKLIST_MILLI_KEY + SEPARATOR +
                    // Long.toString(config.blackListTimestamp) + NL);
                    out.writeUTF(CREATOR_UID_KEY + SEPARATOR +
                            Integer.toString(config.creatorUid) + NL);
                    out.writeUTF(CONNECT_UID_KEY + SEPARATOR +
                            Integer.toString(config.lastConnectUid) + NL);
                    out.writeUTF(UPDATE_UID_KEY + SEPARATOR +
                            Integer.toString(config.lastUpdateUid) + NL);
                    out.writeUTF(CREATOR_NAME_KEY + SEPARATOR +
                            config.creatorName + NL);
                    out.writeUTF(UPDATE_NAME_KEY + SEPARATOR +
                            config.lastUpdateName + NL);
                    out.writeUTF(USER_APPROVED_KEY + SEPARATOR +
                            Integer.toString(config.userApproved) + NL);
                    String allowedKeyManagementString =
                            makeString(config.allowedKeyManagement,
                                    WifiConfiguration.KeyMgmt.strings);
                    out.writeUTF(AUTH_KEY + SEPARATOR +
                            allowedKeyManagementString + NL);

                    if (config.connectChoices != null) {
                        for (String key : config.connectChoices.keySet()) {
                            Integer choice = config.connectChoices.get(key);
                            out.writeUTF(CHOICE_KEY + SEPARATOR +
                                    key + "=" + choice.toString() + NL);
                        }
                    }
                    if (config.linkedConfigurations != null) {
                        log("writeKnownNetworkHistory write linked "
                                + config.linkedConfigurations.size());

                        for (String key : config.linkedConfigurations.keySet()) {
                            out.writeUTF(LINK_KEY + SEPARATOR + key + NL);
                        }
                    }

                    String macAddress = config.defaultGwMacAddress;
                    if (macAddress != null) {
                        out.writeUTF(DEFAULT_GW_KEY + SEPARATOR + macAddress + NL);
                    }

                    if (getScanDetailCache(config) != null) {
                        for (ScanDetail scanDetail : getScanDetailCache(config).values()) {
                            ScanResult result = scanDetail.getScanResult();
                            out.writeUTF(BSSID_KEY + SEPARATOR +
                                    result.BSSID + NL);

                            out.writeUTF(FREQ_KEY + SEPARATOR +
                                    Integer.toString(result.frequency) + NL);

                            out.writeUTF(RSSI_KEY + SEPARATOR +
                                    Integer.toString(result.level) + NL);

                            out.writeUTF(BSSID_STATUS_KEY + SEPARATOR +
                                    Integer.toString(result.autoJoinStatus) + NL);

                            //if (result.seen != 0) {
                            //    out.writeUTF(MILLI_KEY + SEPARATOR + Long.toString(result.seen)
                            //            + NL);
                            //}
                            out.writeUTF(BSSID_KEY_END + NL);
                        }
                    }
                    if (config.lastFailure != null) {
                        out.writeUTF(FAILURE_KEY + SEPARATOR + config.lastFailure + NL);
                    }
                    out.writeUTF(NL);
                    // Add extra blank lines for clarity
                    out.writeUTF(NL);
                    out.writeUTF(NL);
                }
                if (mDeletedSSIDs != null && mDeletedSSIDs.size() > 0) {
                    for (Long i : mDeletedSSIDs) {
                        out.writeUTF(DELETED_CRC32_KEY);
                        out.writeUTF(String.valueOf(i));
                        out.writeUTF(NL);
                    }
                }
                if (mDeletedEphemeralSSIDs != null && mDeletedEphemeralSSIDs.size() > 0) {
                    for (String ssid : mDeletedEphemeralSSIDs) {
                        out.writeUTF(DELETED_EPHEMERAL_KEY);
                        out.writeUTF(ssid);
                        out.writeUTF(NL);
                    }
                }
            }
        });
    }

    public void setLastSelectedConfiguration(int netId) {
        if (VDBG) {
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
                selected.numNoInternetAccessReports = 0;
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

        try (DataInputStream in =
                     new DataInputStream(new BufferedInputStream(
                             new FileInputStream(networkHistoryConfigFile)))) {

            String bssid = null;
            String ssid = null;

            int freq = 0;
            int status = 0;
            long seen = 0;
            int rssi = WifiConfiguration.INVALID_RSSI;
            String caps = null;

            WifiConfiguration config = null;
            while (true) {
                String line = in.readUTF();
                if (line == null) {
                    break;
                }
                int colon = line.indexOf(':');
                if (colon < 0) {
                    continue;
                }

                String key = line.substring(0, colon).trim();
                String value = line.substring(colon + 1).trim();

                if (key.equals(CONFIG_KEY)) {

                    config = mConfiguredNetworks.getByConfigKey(value);
                    
                    // skip reading that configuration data
                    // since we don't have a corresponding network ID
                    if (config == null) {
                        localLog("readNetworkHistory didnt find netid for hash="
                                + Integer.toString(value.hashCode())
                                + " key: " + value);
                        mLostConfigsDbg.add(value);
                        continue;
                    } else {
                        // After an upgrade count old connections as owned by system
                        if (config.creatorName == null || config.lastUpdateName == null) {
                            config.creatorName =
                                mContext.getPackageManager().getNameForUid(Process.SYSTEM_UID);
                            config.lastUpdateName = config.creatorName;

                            if (DBG) Log.w(TAG, "Upgrading network " + config.networkId
                                    + " to " + config.creatorName);
                        }
                    }
                } else if (config != null) {
                    switch (key) {
                        case SSID_KEY:
                            ssid = value;
                            if (config.SSID != null && !config.SSID.equals(ssid)) {
                                loge("Error parsing network history file, mismatched SSIDs");
                                config = null; //error
                                ssid = null;
                            } else {
                                config.SSID = ssid;
                            }
                            break;
                        case FQDN_KEY:
                            // Check for literal 'null' to be backwards compatible.
                            config.FQDN = value.equals("null") ? null : value;
                            break;
                        case DEFAULT_GW_KEY:
                            config.defaultGwMacAddress = value;
                            break;
                        case STATUS_KEY:
                            config.autoJoinStatus = Integer.parseInt(value);
                            break;
                        case SUPPLICANT_DISABLE_REASON_KEY:
                            config.disableReason = Integer.parseInt(value);
                            break;
                        case SELF_ADDED_KEY:
                            config.selfAdded = Boolean.parseBoolean(value);
                            break;
                        case DID_SELF_ADD_KEY:
                            config.didSelfAdd = Boolean.parseBoolean(value);
                            break;
                        case NO_INTERNET_ACCESS_REPORTS_KEY:
                            config.numNoInternetAccessReports = Integer.parseInt(value);
                            break;
                        case VALIDATED_INTERNET_ACCESS_KEY:
                            config.validatedInternetAccess = Boolean.parseBoolean(value);
                            break;
                        case CREATION_TIME_KEY:
                            config.creationTime = value;
                            break;
                        case UPDATE_TIME_KEY:
                            config.updateTime = value;
                            break;
                        case EPHEMERAL_KEY:
                            config.ephemeral = Boolean.parseBoolean(value);
                            break;
                        case CREATOR_UID_KEY:
                            config.creatorUid = Integer.parseInt(value);
                            break;
                        case BLACKLIST_MILLI_KEY:
                            config.blackListTimestamp = Long.parseLong(value);
                            break;
                        case NUM_CONNECTION_FAILURES_KEY:
                            config.numConnectionFailures = Integer.parseInt(value);
                            break;
                        case NUM_IP_CONFIG_FAILURES_KEY:
                            config.numIpConfigFailures = Integer.parseInt(value);
                            break;
                        case NUM_AUTH_FAILURES_KEY:
                            config.numIpConfigFailures = Integer.parseInt(value);
                            break;
                        case SCORER_OVERRIDE_KEY:
                            config.numScorerOverride = Integer.parseInt(value);
                            break;
                        case SCORER_OVERRIDE_AND_SWITCH_KEY:
                            config.numScorerOverrideAndSwitchedNetwork = Integer.parseInt(value);
                            break;
                        case NUM_ASSOCIATION_KEY:
                            config.numAssociation = Integer.parseInt(value);
                            break;
                        case JOIN_ATTEMPT_BOOST_KEY:
                            config.autoJoinUseAggressiveJoinAttemptThreshold =
                                    Integer.parseInt(value);
                            break;
                        case CONNECT_UID_KEY:
                            config.lastConnectUid = Integer.parseInt(value);
                            break;
                        case UPDATE_UID_KEY:
                            config.lastUpdateUid = Integer.parseInt(value);
                            break;
                        case FAILURE_KEY:
                            config.lastFailure = value;
                            break;
                        case PEER_CONFIGURATION_KEY:
                            config.peerWifiConfiguration = value;
                            break;
                        case CHOICE_KEY:
                            String configKey = "";
                            int choice = 0;
                            Matcher match = mConnectChoice.matcher(value);
                            if (!match.find()) {
                                if (DBG) Log.d(TAG, "WifiConfigStore: connectChoice: " +
                                        " Couldnt match pattern : " + value);
                            } else {
                                configKey = match.group(1);
                                try {
                                    choice = Integer.parseInt(match.group(2));
                                } catch (NumberFormatException e) {
                                    choice = 0;
                                }
                                if (choice > 0) {
                                    if (config.connectChoices == null) {
                                        config.connectChoices = new HashMap<>();
                                    }
                                    config.connectChoices.put(configKey, choice);
                                }
                            }
                            break;
                        case LINK_KEY:
                            if (config.linkedConfigurations == null) {
                                config.linkedConfigurations = new HashMap<>();
                            }
                            else {
                                config.linkedConfigurations.put(value, -1);
                            }
                            break;
                        case BSSID_KEY:
                            status = 0;
                            ssid = null;
                            bssid = null;
                            freq = 0;
                            seen = 0;
                            rssi = WifiConfiguration.INVALID_RSSI;
                            caps = "";
                            break;
                        case RSSI_KEY:
                            rssi = Integer.parseInt(value);
                            break;
                        case BSSID_STATUS_KEY:
                            status = Integer.parseInt(value);
                            break;
                        case FREQ_KEY:
                            freq = Integer.parseInt(value);
                            break;
                        case DATE_KEY:
                            /*
                             * when reading the configuration from file we don't update the date
                             * so as to avoid reading back stale or non-sensical data that would
                             * depend on network time.
                             * The date of a WifiConfiguration should only come from actual scan result.
                             *
                            String s = key.replace(FREQ_KEY, "");
                            seen = Integer.getInteger(s);
                            */
                            break;
                        case BSSID_KEY_END:
                            if ((bssid != null) && (ssid != null)) {

                                if (getScanDetailCache(config) != null) {
                                    WifiSsid wssid = WifiSsid.createFromAsciiEncoded(ssid);
                                    ScanDetail scanDetail = new ScanDetail(wssid, bssid,
                                            caps, rssi, freq, (long) 0, seen);
                                    getScanDetailCache(config).put(scanDetail);
                                    scanDetail.getScanResult().autoJoinStatus = status;
                                }
                            }
                            break;
                        case DELETED_CRC32_KEY:
                            mDeletedSSIDs.add(Long.parseLong(value));
                            break;
                        case DELETED_EPHEMERAL_KEY:
                            if (!TextUtils.isEmpty(value)) {
                                mDeletedEphemeralSSIDs.add(value);
                            }
                            break;
                        case CREATOR_NAME_KEY:
                            config.creatorName = value;
                            break;
                        case UPDATE_NAME_KEY:
                            config.lastUpdateName = value;
                            break;
                        case USER_APPROVED_KEY:
                            config.userApproved = Integer.parseInt(value);
                            break;
                    }
                }
            }
        } catch (NumberFormatException e) {
            Log.e(TAG, "readNetworkHistory: failed to read, revert to default, " + e, e);
        } catch (EOFException e) {
            // do nothing
        } catch (IOException e) {
            Log.e(TAG, "readNetworkHistory: No config file, revert to default, " + e, e);
        }
    }

    private void readAutoJoinConfig() {
        try (BufferedReader reader = new BufferedReader(new FileReader(autoJoinConfigFile))) {
            for (String key = reader.readLine(); key != null; key = reader.readLine()) {
                Log.d(TAG, "readAutoJoinConfig line: " + key);

                int split = key.indexOf(':');
                if (split < 0) {
                    continue;
                }

                String name = key.substring(0, split);
                Object reference = sKeyMap.get(name);
                if (reference == null) {
                    continue;
                }

                try {
                    int value = Integer.parseInt(key.substring(split+1).trim());
                    if (reference.getClass() == AtomicBoolean.class) {
                        ((AtomicBoolean)reference).set(value != 0);
                    }
                    else {
                        ((AtomicInteger)reference).set(value);
                    }
                    Log.d(TAG,"readAutoJoinConfig: " + name + " = " + value);
                }
                catch (NumberFormatException nfe) {
                    Log.d(TAG,"readAutoJoinConfig: incorrect format :" + key);
                }
            }
        } catch (IOException e) {
            loge("readAutoJoinStatus: Error parsing configuration" + e);
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

        if (networks == null || networks.size() == 0) {
            // IpConfigStore.readIpAndProxyConfigurations has already logged an error.
            return;
        }

        for (int i = 0; i < networks.size(); i++) {
            int id = networks.keyAt(i);
            WifiConfiguration config = mConfiguredNetworks.getByConfigKeyID(id);
            // This is the only place the map is looked up through a (dangerous) hash-value!

            if (config == null || config.autoJoinStatus == WifiConfiguration.AUTO_JOIN_DELETED ||
                    config.ephemeral) {
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

    public static String encodeSSID(String str){
        return Utils.toHex(removeDoubleQuotes(str).getBytes(StandardCharsets.UTF_8));
    }

    private NetworkUpdateResult addOrUpdateNetworkNative(WifiConfiguration config, int uid) {
        /*
         * If the supplied networkId is INVALID_NETWORK_ID, we create a new empty
         * network configuration. Otherwise, the networkId should
         * refer to an existing configuration.
         */

        if (VDBG) localLog("addOrUpdateNetworkNative " + config.getPrintableSsid());
        if (config.isPasspoint() && !mMOManager.isEnabled()) {
            Log.e(TAG, "Passpoint is not enabled");
            return new NetworkUpdateResult(INVALID_NETWORK_ID);
        }

        int netId = config.networkId;
        boolean newNetwork = false;
        // networkId of INVALID_NETWORK_ID means we want to create a new network
        if (netId == INVALID_NETWORK_ID) {
            WifiConfiguration savedConfig = mConfiguredNetworks.getByConfigKey(config.configKey());
            if (savedConfig != null) {
                netId = savedConfig.networkId;
            } else {
                if (mMOManager.getHomeSP(config.FQDN) != null) {
                    loge("addOrUpdateNetworkNative passpoint " + config.FQDN
                            + " was found, but no network Id");
                }
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

            if (config.isPasspoint()) {
                if (!mWifiNative.setNetworkVariable(
                            netId,
                            idStringVarName,
                            '"' + config.FQDN + '"')) {
                    loge("failed to set id_str: " + config.FQDN);
                    break setVariables;
                }
            }

            if (config.BSSID != null) {
                log("Setting BSSID for " + config.configKey() + " to " + config.BSSID);
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
                        if (key.equals(WifiEnterpriseConfig.REALM_KEY)
                                || key.equals(WifiEnterpriseConfig.PLMN_KEY)) {
                            // No need to save realm or PLMN in supplicant
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
                currentConfig.creatorName = config.creatorName;
                currentConfig.lastUpdateName = config.lastUpdateName;
                currentConfig.peerWifiConfiguration = config.peerWifiConfiguration;
                currentConfig.FQDN = config.FQDN;
                currentConfig.providerFriendlyName = config.providerFriendlyName;
                currentConfig.roamingConsortiumIds = config.roamingConsortiumIds;
                currentConfig.validatedInternetAccess = config.validatedInternetAccess;
                currentConfig.numNoInternetAccessReports = config.numNoInternetAccessReports;
                currentConfig.updateTime = config.updateTime;
                currentConfig.creationTime = config.creationTime;
            }
            if (DBG) {
                log("created new config netId=" + Integer.toString(netId)
                        + " uid=" + Integer.toString(currentConfig.creatorUid)
                        + " name=" + currentConfig.creatorName);
            }
        }

        /* save HomeSP object for passpoint networks */
        HomeSP homeSP = null;

        if (config.isPasspoint()) {
            try {
                Credential credential =
                        new Credential(config.enterpriseConfig, mKeyStore, !newNetwork);
                HashSet<Long> roamingConsortiumIds = new HashSet<Long>();
                for (Long roamingConsortiumId : config.roamingConsortiumIds) {
                    roamingConsortiumIds.add(roamingConsortiumId);
                }

                homeSP = new HomeSP(Collections.<String, Long>emptyMap(), config.FQDN,
                        roamingConsortiumIds, Collections.<String>emptySet(),
                        Collections.<Long>emptySet(), Collections.<Long>emptyList(),
                        config.providerFriendlyName, null, credential);

                log("created a homeSP object for " + config.networkId + ":" + config.SSID);

                /* fix enterprise config properties for passpoint */
                currentConfig.enterpriseConfig.setRealm(config.enterpriseConfig.getRealm());
                currentConfig.enterpriseConfig.setPlmn(config.enterpriseConfig.getPlmn());
            }
            catch (IOException ioe) {
                Log.e(TAG, "Failed to create Passpoint config: " + ioe);
                return new NetworkUpdateResult(INVALID_NETWORK_ID);
            }
        }

        if (uid != WifiConfiguration.UNKNOWN_UID) {
            if (newNetwork) {
                currentConfig.creatorUid = uid;
            } else {
                currentConfig.lastUpdateUid = uid;
            }
        }

        // For debug, record the time the configuration was modified
        StringBuilder sb = new StringBuilder();
        sb.append("time=");
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(System.currentTimeMillis());
        sb.append(String.format("%tm-%td %tH:%tM:%tS.%tL", c, c, c, c, c, c));

        if (newNetwork) {
            currentConfig.dirty = true;
            currentConfig.creationTime = sb.toString();
        } else {
            currentConfig.updateTime = sb.toString();
        }

        if (currentConfig.autoJoinStatus == WifiConfiguration.AUTO_JOIN_DELETED) {
            // Make sure the configuration is not deleted anymore since we just
            // added or modified it.
            currentConfig.setAutoJoinStatus(WifiConfiguration.AUTO_JOIN_ENABLED);
            currentConfig.selfAdded = false;
            currentConfig.didSelfAdd = false;
            if (DBG) {
                log("remove deleted status netId=" + Integer.toString(netId)
                        + " " + currentConfig.configKey());
            }
        }

        if (currentConfig.status == WifiConfiguration.Status.ENABLED) {
            // Make sure autojoin remain in sync with user modifying the configuration
            currentConfig.setAutoJoinStatus(WifiConfiguration.AUTO_JOIN_ENABLED);
        }

        if (currentConfig.configKey().equals(getLastSelectedConfiguration()) &&
                currentConfig.ephemeral) {
            // Make the config non-ephemeral since the user just explicitly clicked it.
            currentConfig.ephemeral = false;
            if (DBG) log("remove ephemeral status netId=" + Integer.toString(netId)
                    + " " + currentConfig.configKey());
        }

        if (VDBG) log("will read network variables netId=" + Integer.toString(netId));

        readNetworkVariables(currentConfig);

        // Persist configuration paramaters that are not saved by supplicant.
        if (config.lastUpdateName != null) {
            currentConfig.lastUpdateName = config.lastUpdateName;
        }
        if (config.lastUpdateUid != WifiConfiguration.UNKNOWN_UID) {
            currentConfig.lastUpdateUid = config.lastUpdateUid;
        }

        mConfiguredNetworks.put(netId, currentConfig);

        NetworkUpdateResult result = writeIpAndProxyConfigurationsOnChange(currentConfig, config);
        result.setIsNewNetwork(newNetwork);
        result.setNetworkId(netId);

        if (homeSP != null) {
            writePasspointConfigs(null, homeSP);
        }
        writeKnownNetworkHistory(false);

        return result;
    }

    public WifiConfiguration getWifiConfigForHomeSP(HomeSP homeSP) {
        WifiConfiguration config = mConfiguredNetworks.getByFQDN(homeSP.getFQDN());
        if (config == null) {
            Log.e(TAG, "Could not find network for homeSP " + homeSP.getFQDN());
        }
        return config;
    }

    private HomeSP getHomeSPForConfig(WifiConfiguration config) {
        WifiConfiguration storedConfig = mConfiguredNetworks.get(config.networkId);
        return storedConfig != null && storedConfig.isPasspoint() ?
                mMOManager.getHomeSP(storedConfig.FQDN) : null;
    }

    public ScanDetailCache getScanDetailCache(WifiConfiguration config) {
        if (config == null) return null;
        ScanDetailCache cache = mScanDetailCaches.get(config.networkId);
        if (cache == null && config.networkId != WifiConfiguration.INVALID_NETWORK_ID) {
            cache = new ScanDetailCache(config);
            mScanDetailCaches.put(config.networkId, cache);
        }
        return cache;
    }

    /**
     * This function run thru the Saved WifiConfigurations and check if some should be linked.
     * @param config
     */
    public void linkConfiguration(WifiConfiguration config) {

        if (getScanDetailCache(config) != null && getScanDetailCache(config).size() > 6) {
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

            if (link.autoJoinStatus == WifiConfiguration.AUTO_JOIN_DELETED || link.ephemeral) {
                continue;
            }

            // Autojoin will be allowed to dynamically jump from a linked configuration
            // to another, hence only link configurations that have equivalent level of security
            if (!link.allowedKeyManagement.equals(config.allowedKeyManagement)) {
                continue;
            }

            ScanDetailCache linkedScanDetailCache = getScanDetailCache(link);
            if (linkedScanDetailCache != null && linkedScanDetailCache.size() > 6) {
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
                if ((getScanDetailCache(config) != null)
                        && (getScanDetailCache(config).size() <= 6)) {

                    for (String abssid : getScanDetailCache(config).keySet()) {
                        for (String bbssid : linkedScanDetailCache.keySet()) {
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
                        || apsk.equals("*") || apsk.equals(DELETED_CONFIG_PSK)
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

    public HashSet<Integer> makeChannelList(WifiConfiguration config, int age, boolean restrict) {
        if (config == null)
            return null;
        long now_ms = System.currentTimeMillis();

        HashSet<Integer> channels = new HashSet<Integer>();

        //get channels for this configuration, if there are at least 2 BSSIDs
        if (getScanDetailCache(config) == null && config.linkedConfigurations == null) {
            return null;
        }

        if (VDBG) {
            StringBuilder dbg = new StringBuilder();
            dbg.append("makeChannelList age=" + Integer.toString(age)
                    + " for " + config.configKey()
                    + " max=" + maxNumActiveChannelsForPartialScans);
            if (getScanDetailCache(config) != null) {
                dbg.append(" bssids=" + getScanDetailCache(config).size());
            }
            if (config.linkedConfigurations != null) {
                dbg.append(" linked=" + config.linkedConfigurations.size());
            }
            loge(dbg.toString());
        }

        int numChannels = 0;
        if (getScanDetailCache(config) != null && getScanDetailCache(config).size() > 0) {
            for (ScanDetail scanDetail : getScanDetailCache(config).values()) {
                ScanResult result = scanDetail.getScanResult();
                //TODO : cout active and passive channels separately
                if (numChannels > maxNumActiveChannelsForPartialScans.get()) {
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
                if (getScanDetailCache(linked) == null) {
                    continue;
                }
                for (ScanDetail scanDetail : getScanDetailCache(linked).values()) {
                    ScanResult result = scanDetail.getScanResult();
                    if (VDBG) {
                        loge("has link: " + result.BSSID
                                + " freq=" + Integer.toString(result.frequency)
                                + " age=" + Long.toString(now_ms - result.seen));
                    }
                    if (numChannels > maxNumActiveChannelsForPartialScans.get()) {
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

    private Map<HomeSP, PasspointMatch> matchPasspointNetworks(ScanDetail scanDetail) {
        if (!mMOManager.isConfigured()) {
            return null;
        }
        NetworkDetail networkDetail = scanDetail.getNetworkDetail();
        if (!networkDetail.hasInterworking()) {
            return null;
        }
        updateAnqpCache(scanDetail, networkDetail.getANQPElements());

        Map<HomeSP, PasspointMatch> matches = matchNetwork(scanDetail, true);
        Log.d(Utils.hs2LogTag(getClass()), scanDetail.getSSID() +
                " pass 1 matches: " + toMatchString(matches));
        return matches;
    }

    private Map<HomeSP, PasspointMatch> matchNetwork(ScanDetail scanDetail, boolean query) {
        NetworkDetail networkDetail = scanDetail.getNetworkDetail();

        ANQPData anqpData = mAnqpCache.getEntry(networkDetail);

        Map<Constants.ANQPElementType, ANQPElement> anqpElements =
                anqpData != null ? anqpData.getANQPElements() : null;

        boolean queried = !query;
        Collection<HomeSP> homeSPs = mMOManager.getLoadedSPs().values();
        Map<HomeSP, PasspointMatch> matches = new HashMap<>(homeSPs.size());
        Log.d(Utils.hs2LogTag(getClass()), "match nwk " + scanDetail.toKeyString() +
                ", anqp " + ( anqpData != null ? "present" : "missing" ) +
                ", query " + query + ", home sps: " + homeSPs.size());

        for (HomeSP homeSP : homeSPs) {
            PasspointMatch match = homeSP.match(networkDetail, anqpElements, mSIMAccessor);

            Log.d(Utils.hs2LogTag(getClass()), " -- " +
                    homeSP.getFQDN() + ": match " + match + ", queried " + queried);

            if (match == PasspointMatch.Incomplete && !queried) {
                if (mAnqpCache.initiate(networkDetail)) {
                    mSupplicantBridge.startANQP(scanDetail);
                }
                queried = true;
            }
            matches.put(homeSP, match);
        }
        return matches;
    }

    public void notifyANQPDone(Long bssid, boolean success) {
        mSupplicantBridge.notifyANQPDone(bssid, success);
    }

    public void notifyANQPResponse(ScanDetail scanDetail,
                                   Map<Constants.ANQPElementType, ANQPElement> anqpElements) {

        updateAnqpCache(scanDetail, anqpElements);
        if (anqpElements == null || anqpElements.isEmpty()) {
            return;
        }
        scanDetail.propagateANQPInfo(anqpElements);

        Map<HomeSP, PasspointMatch> matches = matchNetwork(scanDetail, false);
        Log.d(Utils.hs2LogTag(getClass()), scanDetail.getSSID() +
                " pass 2 matches: " + toMatchString(matches));

        cacheScanResultForPasspointConfigs(scanDetail, matches);
    }


    private void updateAnqpCache(ScanDetail scanDetail,
                                 Map<Constants.ANQPElementType,ANQPElement> anqpElements)
    {
        NetworkDetail networkDetail = scanDetail.getNetworkDetail();

        if (anqpElements == null) {
            // Try to pull cached data if query failed.
            ANQPData data = mAnqpCache.getEntry(networkDetail);
            if (data != null) {
                scanDetail.propagateANQPInfo(data.getANQPElements());
            }
            return;
        }

        mAnqpCache.update(networkDetail, anqpElements);
    }

    private static String toMatchString(Map<HomeSP, PasspointMatch> matches) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<HomeSP, PasspointMatch> entry : matches.entrySet()) {
            sb.append(' ').append(entry.getKey().getFQDN()).append("->").append(entry.getValue());
        }
        return sb.toString();
    }

    private void cacheScanResultForPasspointConfigs(ScanDetail scanDetail,
                                           Map<HomeSP,PasspointMatch> matches) {

        for (Map.Entry<HomeSP, PasspointMatch> entry : matches.entrySet()) {
            PasspointMatch match = entry.getValue();
            if (match == PasspointMatch.HomeProvider || match == PasspointMatch.RoamingProvider) {
                WifiConfiguration config = getWifiConfigForHomeSP(entry.getKey());
                if (config != null) {
                    cacheScanResultForConfig(config, scanDetail, entry.getValue());
                } else {
		            Log.w(Utils.hs2LogTag(getClass()), "Failed to find config for '" +
                            entry.getKey().getFQDN() + "'");
                    /* perhaps the configuration was deleted?? */
                }
            }
        }
    }

    private void cacheScanResultForConfig(
            WifiConfiguration config, ScanDetail scanDetail, PasspointMatch passpointMatch) {

        ScanResult scanResult = scanDetail.getScanResult();

        if (config.autoJoinStatus >= WifiConfiguration.AUTO_JOIN_DELETED) {
            if (VVDBG) {
                loge("updateSavedNetworkHistory(): found a deleted, skip it...  "
                        + config.configKey());
            }
            // The scan result belongs to a deleted config:
            //   - increment numConfigFound to remember that we found a config
            //            matching for this scan result
            //   - dont do anything since the config was deleted, just skip...
            return;
        }

        ScanDetailCache scanDetailCache = getScanDetailCache(config);
        if (scanDetailCache == null) {
            Log.w(TAG, "Could not allocate scan cache for " + config.SSID);
            return;
        }

        // Adding a new BSSID
        ScanResult result = scanDetailCache.get(scanResult.BSSID);
        if (result != null) {
            // transfer the black list status
            scanResult.autoJoinStatus = result.autoJoinStatus;
            scanResult.blackListTimestamp = result.blackListTimestamp;
            scanResult.numIpConfigFailures = result.numIpConfigFailures;
            scanResult.numConnection = result.numConnection;
            scanResult.isAutoJoinCandidate = result.isAutoJoinCandidate;
        }

        if (config.ephemeral) {
            // For an ephemeral Wi-Fi config, the ScanResult should be considered
            // untrusted.
            scanResult.untrusted = true;
        }

        if (scanDetailCache.size() > (maxNumScanCacheEntries + 64)) {
            long now_dbg = 0;
            if (VVDBG) {
                loge(" Will trim config " + config.configKey()
                        + " size " + scanDetailCache.size());

                for (ScanDetail sd : scanDetailCache.values()) {
                    loge("     " + sd.getBSSIDString() + " " + sd.getSeen());
                }
                now_dbg = SystemClock.elapsedRealtimeNanos();
            }
            // Trim the scan result cache to maxNumScanCacheEntries entries max
            // Since this operation is expensive, make sure it is not performed
            // until the cache has grown significantly above the trim treshold
            scanDetailCache.trim(maxNumScanCacheEntries);
            if (VVDBG) {
                long diff = SystemClock.elapsedRealtimeNanos() - now_dbg;
                loge(" Finished trimming config, time(ns) " + diff);
                for (ScanDetail sd : scanDetailCache.values()) {
                    loge("     " + sd.getBSSIDString() + " " + sd.getSeen());
                }
            }
        }

        // Add the scan result to this WifiConfiguration
        if (passpointMatch != null)
            scanDetailCache.put(scanDetail, passpointMatch, getHomeSPForConfig(config));
        else
            scanDetailCache.put(scanDetail);

        // Since we added a scan result to this configuration, re-attempt linking
        linkConfiguration(config);
    }


    // Update the WifiConfiguration database with the new scan result
    // A scan result can be associated to multiple WifiConfigurations
    public boolean updateSavedNetworkHistory(ScanDetail scanDetail) {

        ScanResult scanResult = scanDetail.getScanResult();
        NetworkDetail networkDetail = scanDetail.getNetworkDetail();

        int numConfigFound = 0;
        if (scanResult == null)
            return false;

        String SSID = "\"" + scanResult.SSID + "\"";

        if (networkDetail.hasInterworking()) {
            Map<HomeSP, PasspointMatch> matches = matchPasspointNetworks(scanDetail);
            if (matches != null) {
                cacheScanResultForPasspointConfigs(scanDetail, matches);
                return matches.size() != 0;
            }
        }

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
                cacheScanResultForConfig(config, scanDetail, null);
            }

            if (VDBG && found) {
                String status = "";
                if (scanResult.autoJoinStatus > 0) {
                    status = " status=" + Integer.toString(scanResult.autoJoinStatus);
                }
                loge("        got known scan result " +
                        scanResult.BSSID + " key : "
                        + config.configKey() + " num: " +
                        Integer.toString(getScanDetailCache(config).size())
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

        readNetworkBitsetVariable(config.networkId, config.allowedProtocols,
                WifiConfiguration.Protocol.varName, WifiConfiguration.Protocol.strings);

        readNetworkBitsetVariable(config.networkId, config.allowedKeyManagement,
                WifiConfiguration.KeyMgmt.varName, WifiConfiguration.KeyMgmt.strings);

        readNetworkBitsetVariable(config.networkId, config.allowedAuthAlgorithms,
                WifiConfiguration.AuthAlgorithm.varName, WifiConfiguration.AuthAlgorithm.strings);

        readNetworkBitsetVariable(config.networkId, config.allowedPairwiseCiphers,
                WifiConfiguration.PairwiseCipher.varName, WifiConfiguration.PairwiseCipher.strings);

        readNetworkBitsetVariable(config.networkId, config.allowedGroupCiphers,
                WifiConfiguration.GroupCipher.varName, WifiConfiguration.GroupCipher.strings);

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

    public WifiConfiguration wifiConfigurationFromScanResult(ScanDetail scanDetail) {

        ScanResult result = scanDetail.getScanResult();
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

        /* getScanDetailCache(config).put(scanDetail); */

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
        for (WifiConfiguration conf : getAllConfiguredNetworks()) {
            pw.println(conf);
        }
        pw.println();
        if (mLostConfigsDbg != null && mLostConfigsDbg.size() > 0) {
            pw.println("LostConfigs: ");
            for (String s : mLostConfigsDbg) {
                pw.println(s);
            }
        }
        if (mLocalLog != null) {
            pw.println("WifiConfigStore - Log Begin ----");
            mLocalLog.dump(fd, pw, args);
            pw.println("WifiConfigStore - Log End ----");
        }
        if (mMOManager.isConfigured()) {
            pw.println("Begin dump of ANQP Cache");
            mAnqpCache.dump(pw);
            pw.println("End dump of ANQP Cache");
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

    private void logKernelTime() {
        long kernelTimeMs = System.nanoTime()/(1000*1000);
        StringBuilder builder = new StringBuilder();
        builder.append("kernel time = ").append(kernelTimeMs/1000).append(".").append
                (kernelTimeMs%1000).append("\n");
        localLog(builder.toString());
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
        synchronized(mConfiguredNetworks) {             // !!! Useless synchronization
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

    boolean isNetworkConfigured(WifiConfiguration config) {
        // Check if either we have a network Id or a WifiConfiguration
        // matching the one we are trying to add.

        if(config.networkId != INVALID_NETWORK_ID) {
            return (mConfiguredNetworks.get(config.networkId) != null);
        }

        return (mConfiguredNetworks.getByConfigKey(config.configKey()) != null);
    }

    /**
     * Checks if uid has access to modify the configuration corresponding to networkId.
     *
     * Factors involved in modifiability of a config are as follows.
     *    If uid is a Device Owner app then it has full control over the device, including WiFi
     * configs.
     *    If the modification is only for administrative annotation (e.g. when connecting) or the
     * config is not lockdown eligible (currently that means any config not last updated by the DO)
     * then the creator of config or an app holding OVERRIDE_CONFIG_WIFI can modify the config.
     *    If the config is lockdown eligible and the modification is substantial (not annotation)
     * then the requirement to be able to modify the config by the uid is as follows:
     *    a) the uid has to hold OVERRIDE_CONFIG_WIFI and
     *    b) the lockdown feature should be disabled.
     */
    boolean canModifyNetwork(int uid, int networkId, boolean onlyAnnotate) {
        WifiConfiguration config = mConfiguredNetworks.get(networkId);

        if (config == null) {
            loge("canModifyNetwork: cannot find config networkId " + networkId);
            return false;
        }

        final DevicePolicyManagerInternal dpmi = LocalServices.getService(
                DevicePolicyManagerInternal.class);

        final boolean isUidDeviceOwner = dpmi != null && dpmi.isActiveAdminWithPolicy(uid,
                DeviceAdminInfo.USES_POLICY_DEVICE_OWNER);

        if (isUidDeviceOwner) {
            // Device Owner has full control over the device, including WiFi Configs
            return true;
        }

        final boolean isCreator = (config.creatorUid == uid);

        if (onlyAnnotate) {
            return isCreator || checkConfigOverridePermission(uid);
        }

        // Check if device has DPM capability. If it has and dpmi is still null, then we
        // treat this case with suspicion and bail out.
        if (mContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_DEVICE_ADMIN)
                && dpmi == null) {
            return false;
        }

        // WiFi config lockdown related logic. At this point we know uid NOT to be a Device Owner.

        final boolean isConfigEligibleForLockdown = dpmi != null && dpmi.isActiveAdminWithPolicy(
                config.creatorUid, DeviceAdminInfo.USES_POLICY_DEVICE_OWNER);
        if (!isConfigEligibleForLockdown) {
            return isCreator || checkConfigOverridePermission(uid);
        }

        final ContentResolver resolver = mContext.getContentResolver();
        final boolean isLockdownFeatureEnabled = Settings.Global.getInt(resolver,
                Settings.Global.WIFI_DEVICE_OWNER_CONFIGS_LOCKDOWN, 0) != 0;
        return !isLockdownFeatureEnabled && checkConfigOverridePermission(uid);
    }

    /**
     * Checks if uid has access to modify config.
     */
    boolean canModifyNetwork(int uid, WifiConfiguration config, boolean onlyAnnotate) {
        if (config == null) {
            loge("canModifyNetowrk recieved null configuration");
            return false;
        }

        // Resolve the correct network id.
        int netid;
        if (config.networkId != INVALID_NETWORK_ID){
            netid = config.networkId;
        } else {
            WifiConfiguration test = mConfiguredNetworks.getByConfigKey(config.configKey());
            if (test == null) {
                return false;
            } else {
                netid = test.networkId;
            }
        }

        return canModifyNetwork(uid, netid, onlyAnnotate);
    }

    boolean checkConfigOverridePermission(int uid) {
        try {
            return (AppGlobals.getPackageManager().checkUidPermission(
                    android.Manifest.permission.OVERRIDE_WIFI_CONFIG, uid)
                    == PackageManager.PERMISSION_GRANTED);
        } catch (RemoteException e) {
            return false;
        }
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
        // Record last time Connectivity Service switched us away from WiFi and onto Cell
        lastUnwantedNetworkDisconnectTimestamp = System.currentTimeMillis();
    }

    boolean handleBSSIDBlackList(int netId, String BSSID, boolean enable) {
        boolean found = false;
        if (BSSID == null)
            return found;

        // Look for the BSSID in our config store
        for (WifiConfiguration config : mConfiguredNetworks.values()) {
            if (getScanDetailCache(config) != null) {
                for (ScanDetail scanDetail : getScanDetailCache(config).values()) {
                    if (scanDetail.getBSSIDString().equals(BSSID)) {
                        if (enable) {
                            scanDetail.getScanResult().setAutoJoinStatus(ScanResult.ENABLED);
                        } else {
                            // Black list the BSSID we were trying to join
                            // so as the Roam state machine
                            // doesn't pick it up over and over
                            scanDetail.getScanResult().setAutoJoinStatus(
                                    ScanResult.AUTO_ROAM_DISABLED);
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

    void clearBssidBlacklist() {
        if (!mWifiStateMachine.useHalBasedAutoJoinOffload()) {
            if(DBG) {
                Log.d(TAG, "No blacklist allowed without epno enabled");
            }
            return;
        }
        mBssidBlacklist = new HashSet<String>();
        mWifiNative.clearBlacklist();
        mWifiNative.setBssidBlacklist(null);
    }

    void blackListBssid(String BSSID) {
        if (!mWifiStateMachine.useHalBasedAutoJoinOffload()) {
            if(DBG) {
                Log.d(TAG, "No blacklist allowed without epno enabled");
            }
            return;
        }
        if (BSSID == null)
            return;
        mBssidBlacklist.add(BSSID);
        // Blacklist at wpa_supplicant
        mWifiNative.addToBlacklist(BSSID);
        // Blacklist at firmware
        String list[] = new String[mBssidBlacklist.size()];
        int count = 0;
        for (String bssid : mBssidBlacklist) {
            list[count++] = bssid;
        }
        mWifiNative.setBssidBlacklist(list);
    }

    void handleSSIDStateChange(int netId, boolean enabled, String message, String BSSID) {
        WifiConfiguration config = mConfiguredNetworks.get(netId);
        if (config != null) {
            if (enabled) {
                loge("Ignoring SSID re-enabled from supplicant:  " + config.configKey() +
                        " had autoJoinStatus=" + Integer.toString(config.autoJoinStatus)
                        + " self added " + config.selfAdded + " ephemeral " + config.ephemeral);
                //We should not re-enable the BSSID based on Supplicant reanable.
                // Framework will re-enable it after its own blacklist timer expires
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
                            if (getScanDetailCache(config) != null && BSSID != null) {
                                result = getScanDetailCache(config).get(BSSID);
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
                mKeyStore.delete(privKeyName, Process.WIFI_UID);
                return ret;
            }
        }

        if (config.getCaCertificate() != null) {
            ret = putCertInKeyStore(caCertName, config.getCaCertificate());
            if (ret == false) {
                if (config.getClientCertificate() != null) {
                    // Remove client key+cert
                    mKeyStore.delete(privKeyName, Process.WIFI_UID);
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
            mKeyStore.delete(Credentials.USER_PRIVATE_KEY + client, Process.WIFI_UID);
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

    private void readNetworkBitsetVariable(int netId, BitSet variable, String varName,
            String[] strings) {
        String value = mWifiNative.getNetworkVariable(netId, varName);
        if (!TextUtils.isEmpty(value)) {
            variable.clear();
            String vals[] = value.split(" ");
            for (String val : vals) {
                int index = lookupString(val, strings);
                if (0 <= index) {
                    variable.set(index);
                }
            }
        }
    }
}
