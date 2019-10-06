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

import static android.net.wifi.WifiManager.WIFI_STATE_DISABLED;
import static android.net.wifi.WifiManager.WIFI_STATE_DISABLING;
import static android.net.wifi.WifiManager.WIFI_STATE_ENABLED;
import static android.net.wifi.WifiManager.WIFI_STATE_ENABLING;
import static android.net.wifi.WifiManager.WIFI_STATE_UNKNOWN;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.net.ConnectivityManager;
import android.net.DhcpResults;
import android.net.IpConfiguration;
import android.net.KeepalivePacketData;
import android.net.LinkProperties;
import android.net.MacAddress;
import android.net.MatchAllNetworkSpecifier;
import android.net.NattKeepalivePacketData;
import android.net.Network;
import android.net.NetworkAgent;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.NetworkInfo.DetailedState;
import android.net.NetworkMisc;
import android.net.NetworkUtils;
import android.net.RouteInfo;
import android.net.SocketKeepalive;
import android.net.SocketKeepalive.InvalidPacketException;
import android.net.StaticIpConfiguration;
import android.net.TcpKeepalivePacketData;
import android.net.ip.IIpClient;
import android.net.ip.IpClientCallbacks;
import android.net.ip.IpClientManager;
import android.net.shared.ProvisioningConfiguration;
import android.net.wifi.INetworkRequestMatchCallback;
import android.net.wifi.RssiPacketCountInfo;
import android.net.wifi.ScanResult;
import android.net.wifi.SupplicantState;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiEnterpriseConfig;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiManager.DeviceMobilityState;
import android.net.wifi.WifiNetworkAgentSpecifier;
import android.net.wifi.WifiSsid;
import android.net.wifi.hotspot2.IProvisioningCallback;
import android.net.wifi.hotspot2.OsuProvider;
import android.net.wifi.hotspot2.PasspointConfiguration;
import android.net.wifi.p2p.IWifiP2pManager;
import android.os.BatteryStats;
import android.os.Bundle;
import android.os.ConditionVariable;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.PowerManager;
import android.os.Process;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.WorkSource;
import android.provider.Settings;
import android.system.OsConstants;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;
import android.util.SparseArray;
import android.util.StatsLog;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.IBatteryStats;
import com.android.internal.util.AsyncChannel;
import com.android.internal.util.MessageUtils;
import com.android.internal.util.Protocol;
import com.android.internal.util.State;
import com.android.internal.util.StateMachine;
import com.android.server.wifi.hotspot2.AnqpEvent;
import com.android.server.wifi.hotspot2.IconEvent;
import com.android.server.wifi.hotspot2.NetworkDetail;
import com.android.server.wifi.hotspot2.PasspointManager;
import com.android.server.wifi.hotspot2.WnmData;
import com.android.server.wifi.nano.WifiMetricsProto;
import com.android.server.wifi.nano.WifiMetricsProto.StaEvent;
import com.android.server.wifi.nano.WifiMetricsProto.WifiIsUnusableEvent;
import com.android.server.wifi.nano.WifiMetricsProto.WifiUsabilityStats;
import com.android.server.wifi.p2p.WifiP2pServiceImpl;
import com.android.server.wifi.util.NativeUtil;
import com.android.server.wifi.util.TelephonyUtil;
import com.android.server.wifi.util.TelephonyUtil.SimAuthRequestData;
import com.android.server.wifi.util.TelephonyUtil.SimAuthResponseData;
import com.android.server.wifi.util.WifiPermissionsUtil;
import com.android.server.wifi.util.WifiPermissionsWrapper;

import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Implementation of ClientMode.  Event handling for Client mode logic is done here,
 * and all changes in connectivity state are initiated here.
 *
 * @hide
 */
public class ClientModeImpl extends StateMachine {

    private static final String NETWORKTYPE = "WIFI";
    @VisibleForTesting public static final short NUM_LOG_RECS_NORMAL = 100;
    @VisibleForTesting public static final short NUM_LOG_RECS_VERBOSE_LOW_MEMORY = 200;
    @VisibleForTesting public static final short NUM_LOG_RECS_VERBOSE = 3000;
    private static final String TAG = "WifiClientModeImpl";

    private static final int ONE_HOUR_MILLI = 1000 * 60 * 60;

    private static final String GOOGLE_OUI = "DA-A1-19";

    private static final String EXTRA_OSU_ICON_QUERY_BSSID = "BSSID";
    private static final String EXTRA_OSU_ICON_QUERY_FILENAME = "FILENAME";
    private static final String EXTRA_OSU_PROVIDER = "OsuProvider";
    private static final String EXTRA_UID = "uid";
    private static final String EXTRA_PACKAGE_NAME = "PackageName";
    private static final String EXTRA_PASSPOINT_CONFIGURATION = "PasspointConfiguration";
    private static final int IPCLIENT_TIMEOUT_MS = 10_000;

    private boolean mVerboseLoggingEnabled = false;
    private final WifiPermissionsWrapper mWifiPermissionsWrapper;

    /* debug flag, indicating if handling of ASSOCIATION_REJECT ended up blacklisting
     * the corresponding BSSID.
     */
    private boolean mDidBlackListBSSID = false;

    /**
     * Log with error attribute
     *
     * @param s is string log
     */
    @Override
    protected void loge(String s) {
        Log.e(getName(), s);
    }
    @Override
    protected void logd(String s) {
        Log.d(getName(), s);
    }
    @Override
    protected void log(String s) {
        Log.d(getName(), s);
    }
    private final WifiMetrics mWifiMetrics;
    private final WifiInjector mWifiInjector;
    private final WifiMonitor mWifiMonitor;
    private final WifiNative mWifiNative;
    private final WifiPermissionsUtil mWifiPermissionsUtil;
    private final WifiConfigManager mWifiConfigManager;
    private final WifiConnectivityManager mWifiConnectivityManager;
    private ConnectivityManager mCm;
    private BaseWifiDiagnostics mWifiDiagnostics;
    private final boolean mP2pSupported;
    private final AtomicBoolean mP2pConnected = new AtomicBoolean(false);
    private boolean mTemporarilyDisconnectWifi = false;
    private final Clock mClock;
    private final PropertyService mPropertyService;
    private final BuildProperties mBuildProperties;
    private final WifiCountryCode mCountryCode;
    private final WifiScoreCard mWifiScoreCard;
    private final WifiScoreReport mWifiScoreReport;
    private final SarManager mSarManager;
    private final WifiTrafficPoller mWifiTrafficPoller;
    public WifiScoreReport getWifiScoreReport() {
        return mWifiScoreReport;
    }
    private final PasspointManager mPasspointManager;
    private final WifiDataStall mWifiDataStall;
    private final LinkProbeManager mLinkProbeManager;

    private final McastLockManagerFilterController mMcastLockManagerFilterController;

    private boolean mScreenOn = false;

    private String mInterfaceName;

    private int mLastSignalLevel = -1;
    private String mLastBssid;
    private int mLastNetworkId; // The network Id we successfully joined

    private boolean mIpReachabilityDisconnectEnabled = true;

    private void processRssiThreshold(byte curRssi, int reason,
            WifiNative.WifiRssiEventHandler rssiHandler) {
        if (curRssi == Byte.MAX_VALUE || curRssi == Byte.MIN_VALUE) {
            Log.wtf(TAG, "processRssiThreshold: Invalid rssi " + curRssi);
            return;
        }
        for (int i = 0; i < mRssiRanges.length; i++) {
            if (curRssi < mRssiRanges[i]) {
                // Assume sorted values(ascending order) for rssi,
                // bounded by high(127) and low(-128) at extremeties
                byte maxRssi = mRssiRanges[i];
                byte minRssi = mRssiRanges[i - 1];
                // This value of hw has to be believed as this value is averaged and has breached
                // the rssi thresholds and raised event to host. This would be eggregious if this
                // value is invalid
                mWifiInfo.setRssi(curRssi);
                updateCapabilities();
                int ret = startRssiMonitoringOffload(maxRssi, minRssi, rssiHandler);
                Log.d(TAG, "Re-program RSSI thresholds for " + getWhatToString(reason)
                        + ": [" + minRssi + ", " + maxRssi + "], curRssi=" + curRssi
                        + " ret=" + ret);
                break;
            }
        }
    }

    private boolean mEnableRssiPolling = false;
    // Accessed via Binder thread ({get,set}PollRssiIntervalMsecs), and ClientModeImpl thread.
    private volatile int mPollRssiIntervalMsecs = DEFAULT_POLL_RSSI_INTERVAL_MSECS;
    private int mRssiPollToken = 0;
    /* 3 operational states for STA operation: CONNECT_MODE, SCAN_ONLY_MODE, SCAN_ONLY_WIFI_OFF_MODE
    * In CONNECT_MODE, the STA can scan and connect to an access point
    * In SCAN_ONLY_MODE, the STA can only scan for access points
    * In SCAN_ONLY_WIFI_OFF_MODE, the STA can only scan for access points with wifi toggle being off
    */
    private int mOperationalMode = DISABLED_MODE;

    // variable indicating we are expecting a mode switch - do not attempt recovery for failures
    private boolean mModeChange = false;

    private ClientModeManager.Listener mClientModeCallback = null;

    private boolean mBluetoothConnectionActive = false;

    private PowerManager.WakeLock mSuspendWakeLock;

    /**
     * Interval in milliseconds between polling for RSSI and linkspeed information.
     * This is also used as the polling interval for WifiTrafficPoller, which updates
     * its data activity on every CMD_RSSI_POLL.
     */
    private static final int DEFAULT_POLL_RSSI_INTERVAL_MSECS = 3000;

    /**
     * Interval in milliseconds between receiving a disconnect event
     * while connected to a good AP, and handling the disconnect proper
     */
    private static final int LINK_FLAPPING_DEBOUNCE_MSEC = 4000;

    /**
     * Delay between supplicant restarts upon failure to establish connection
     */
    private static final int SUPPLICANT_RESTART_INTERVAL_MSECS = 5000;

    /**
     * Number of times we attempt to restart supplicant
     */
    private static final int SUPPLICANT_RESTART_TRIES = 5;

    /**
     * Value to set in wpa_supplicant "bssid" field when we don't want to restrict connection to
     * a specific AP.
     */
    public static final String SUPPLICANT_BSSID_ANY = "any";

    /**
     * The link properties of the wifi interface.
     * Do not modify this directly; use updateLinkProperties instead.
     */
    private LinkProperties mLinkProperties;

    /* Tracks sequence number on a periodic scan message */
    private int mPeriodicScanToken = 0;

    // Wakelock held during wifi start/stop and driver load/unload
    private PowerManager.WakeLock mWakeLock;

    private Context mContext;

    private final Object mDhcpResultsLock = new Object();
    private DhcpResults mDhcpResults;

    // NOTE: Do not return to clients - see syncRequestConnectionInfo()
    private final ExtendedWifiInfo mWifiInfo;
    private NetworkInfo mNetworkInfo;
    private SupplicantStateTracker mSupplicantStateTracker;

    // Indicates that framework is attempting to roam, set true on CMD_START_ROAM, set false when
    // wifi connects or fails to connect
    private boolean mIsAutoRoaming = false;

    // Roaming failure count
    private int mRoamFailCount = 0;

    // This is the BSSID we are trying to associate to, it can be set to SUPPLICANT_BSSID_ANY
    // if we havent selected a BSSID for joining.
    private String mTargetRoamBSSID = SUPPLICANT_BSSID_ANY;
    // This one is used to track the current target network ID. This is used for error
    // handling during connection setup since many error message from supplicant does not report
    // SSID Once connected, it will be set to invalid
    private int mTargetNetworkId = WifiConfiguration.INVALID_NETWORK_ID;
    private long mLastDriverRoamAttempt = 0;
    private WifiConfiguration mTargetWifiConfiguration = null;

    int getPollRssiIntervalMsecs() {
        return mPollRssiIntervalMsecs;
    }

    void setPollRssiIntervalMsecs(int newPollIntervalMsecs) {
        mPollRssiIntervalMsecs = newPollIntervalMsecs;
    }

    /**
     * Method to clear {@link #mTargetRoamBSSID} and reset the the current connected network's
     * bssid in wpa_supplicant after a roam/connect attempt.
     */
    public boolean clearTargetBssid(String dbg) {
        WifiConfiguration config = mWifiConfigManager.getConfiguredNetwork(mTargetNetworkId);
        if (config == null) {
            return false;
        }
        String bssid = SUPPLICANT_BSSID_ANY;
        if (config.BSSID != null) {
            bssid = config.BSSID;
            if (mVerboseLoggingEnabled) {
                Log.d(TAG, "force BSSID to " + bssid + "due to config");
            }
        }
        if (mVerboseLoggingEnabled) {
            logd(dbg + " clearTargetBssid " + bssid + " key=" + config.configKey());
        }
        mTargetRoamBSSID = bssid;
        return mWifiNative.setConfiguredNetworkBSSID(mInterfaceName, bssid);
    }

    /**
     * Set Config's default BSSID (for association purpose) and {@link #mTargetRoamBSSID}
     * @param config config need set BSSID
     * @param bssid  default BSSID to assocaite with when connect to this network
     * @return false -- does not change the current default BSSID of the configure
     *         true -- change the  current default BSSID of the configur
     */
    private boolean setTargetBssid(WifiConfiguration config, String bssid) {
        if (config == null || bssid == null) {
            return false;
        }
        if (config.BSSID != null) {
            bssid = config.BSSID;
            if (mVerboseLoggingEnabled) {
                Log.d(TAG, "force BSSID to " + bssid + "due to config");
            }
        }
        if (mVerboseLoggingEnabled) {
            Log.d(TAG, "setTargetBssid set to " + bssid + " key=" + config.configKey());
        }
        mTargetRoamBSSID = bssid;
        config.getNetworkSelectionStatus().setNetworkSelectionBSSID(bssid);
        return true;
    }

    private volatile IpClientManager mIpClient;
    private IpClientCallbacksImpl mIpClientCallbacks;

    // Channel for sending replies.
    private AsyncChannel mReplyChannel = new AsyncChannel();

    // Used to initiate a connection with WifiP2pService
    private AsyncChannel mWifiP2pChannel;

    private WifiNetworkFactory mNetworkFactory;
    private UntrustedWifiNetworkFactory mUntrustedNetworkFactory;
    @GuardedBy("mNetworkAgentLock")
    private WifiNetworkAgent mNetworkAgent;
    private final Object mNetworkAgentLock = new Object();

    private byte[] mRssiRanges;

    // Used to filter out requests we couldn't possibly satisfy.
    private final NetworkCapabilities mNetworkCapabilitiesFilter = new NetworkCapabilities();

    // Provide packet filter capabilities to ConnectivityService.
    private final NetworkMisc mNetworkMisc = new NetworkMisc();

    /* The base for wifi message types */
    static final int BASE = Protocol.BASE_WIFI;

    static final int CMD_BLUETOOTH_ADAPTER_STATE_CHANGE                 = BASE + 31;

    /* Supplicant commands */
    /* Add/update a network configuration */
    static final int CMD_ADD_OR_UPDATE_NETWORK                          = BASE + 52;
    /* Delete a network */
    static final int CMD_REMOVE_NETWORK                                 = BASE + 53;
    /* Enable a network. The device will attempt a connection to the given network. */
    static final int CMD_ENABLE_NETWORK                                 = BASE + 54;
    /* Get configured networks */
    static final int CMD_GET_CONFIGURED_NETWORKS                        = BASE + 59;
    /* Get adaptors */
    static final int CMD_GET_SUPPORTED_FEATURES                         = BASE + 61;
    /* Get configured networks with real preSharedKey */
    static final int CMD_GET_PRIVILEGED_CONFIGURED_NETWORKS             = BASE + 62;
    /* Get Link Layer Stats thru HAL */
    static final int CMD_GET_LINK_LAYER_STATS                           = BASE + 63;
    /* Supplicant commands after driver start*/
    /* Set operational mode. CONNECT, SCAN ONLY, SCAN_ONLY with Wi-Fi off mode */
    static final int CMD_SET_OPERATIONAL_MODE                           = BASE + 72;
    /* Disconnect from a network */
    static final int CMD_DISCONNECT                                     = BASE + 73;
    /* Reconnect to a network */
    static final int CMD_RECONNECT                                      = BASE + 74;
    /* Reassociate to a network */
    static final int CMD_REASSOCIATE                                    = BASE + 75;

    /* Controls suspend mode optimizations
     *
     * When high perf mode is enabled, suspend mode optimizations are disabled
     *
     * When high perf mode is disabled, suspend mode optimizations are enabled
     *
     * Suspend mode optimizations include:
     * - packet filtering
     * - turn off roaming
     * - DTIM wake up settings
     */
    static final int CMD_SET_HIGH_PERF_MODE                             = BASE + 77;
    /* Enables RSSI poll */
    static final int CMD_ENABLE_RSSI_POLL                               = BASE + 82;
    /* RSSI poll */
    static final int CMD_RSSI_POLL                                      = BASE + 83;
    /** Runs RSSI poll once */
    static final int CMD_ONESHOT_RSSI_POLL                              = BASE + 84;
    /* Enable suspend mode optimizations in the driver */
    static final int CMD_SET_SUSPEND_OPT_ENABLED                        = BASE + 86;

    /* Enable TDLS on a specific MAC address */
    static final int CMD_ENABLE_TDLS                                    = BASE + 92;

    /**
     * Watchdog for protecting against b/16823537
     * Leave time for 4-way handshake to succeed
     */
    static final int ROAM_GUARD_TIMER_MSEC = 15000;

    int mRoamWatchdogCount = 0;
    /* Roam state watchdog */
    static final int CMD_ROAM_WATCHDOG_TIMER                            = BASE + 94;
    /* Screen change intent handling */
    static final int CMD_SCREEN_STATE_CHANGED                           = BASE + 95;

    /* Disconnecting state watchdog */
    static final int CMD_DISCONNECTING_WATCHDOG_TIMER                   = BASE + 96;

    /* Remove a packages associated configurations */
    static final int CMD_REMOVE_APP_CONFIGURATIONS                      = BASE + 97;

    /* Disable an ephemeral network */
    static final int CMD_DISABLE_EPHEMERAL_NETWORK                      = BASE + 98;

    /* SIM is removed; reset any cached data for it */
    static final int CMD_RESET_SIM_NETWORKS                             = BASE + 101;

    /* OSU APIs */
    static final int CMD_QUERY_OSU_ICON                                 = BASE + 104;

    /* try to match a provider with current network */
    static final int CMD_MATCH_PROVIDER_NETWORK                         = BASE + 105;

    // Add or update a Passpoint configuration.
    static final int CMD_ADD_OR_UPDATE_PASSPOINT_CONFIG                 = BASE + 106;

    // Remove a Passpoint configuration.
    static final int CMD_REMOVE_PASSPOINT_CONFIG                        = BASE + 107;

    // Get the list of installed Passpoint configurations.
    static final int CMD_GET_PASSPOINT_CONFIGS                          = BASE + 108;

    // Get the list of OSU providers associated with a Passpoint network.
    static final int CMD_GET_MATCHING_OSU_PROVIDERS                     = BASE + 109;

    // Get the list of installed Passpoint configurations matched with OSU providers
    static final int CMD_GET_MATCHING_PASSPOINT_CONFIGS_FOR_OSU_PROVIDERS = BASE + 110;

    /* Commands from/to the SupplicantStateTracker */
    /* Reset the supplicant state tracker */
    static final int CMD_RESET_SUPPLICANT_STATE                         = BASE + 111;

    // Get the list of wifi configurations for installed Passpoint profiles
    static final int CMD_GET_WIFI_CONFIGS_FOR_PASSPOINT_PROFILES = BASE + 112;

    int mDisconnectingWatchdogCount = 0;
    static final int DISCONNECTING_GUARD_TIMER_MSEC = 5000;

    /**
     * Indicates the end of boot process, should be used to trigger load from config store,
     * initiate connection attempt, etc.
     * */
    static final int CMD_BOOT_COMPLETED                                 = BASE + 134;
    /**
     * Initialize ClientModeImpl. This is currently used to initialize the
     * {@link HalDeviceManager} module.
     */
    static final int CMD_INITIALIZE                                     = BASE + 135;

    /* We now have a valid IP configuration. */
    static final int CMD_IP_CONFIGURATION_SUCCESSFUL                    = BASE + 138;
    /* We no longer have a valid IP configuration. */
    static final int CMD_IP_CONFIGURATION_LOST                          = BASE + 139;
    /* Link configuration (IP address, DNS, ...) changes notified via netlink */
    static final int CMD_UPDATE_LINKPROPERTIES                          = BASE + 140;

    /* Supplicant is trying to associate to a given BSSID */
    static final int CMD_TARGET_BSSID                                   = BASE + 141;

    static final int CMD_START_CONNECT                                  = BASE + 143;

    private static final int NETWORK_STATUS_UNWANTED_DISCONNECT         = 0;
    private static final int NETWORK_STATUS_UNWANTED_VALIDATION_FAILED  = 1;
    private static final int NETWORK_STATUS_UNWANTED_DISABLE_AUTOJOIN   = 2;

    static final int CMD_UNWANTED_NETWORK                               = BASE + 144;

    static final int CMD_START_ROAM                                     = BASE + 145;

    static final int CMD_ASSOCIATED_BSSID                               = BASE + 147;

    static final int CMD_NETWORK_STATUS                                 = BASE + 148;

    /* A layer 3 neighbor on the Wi-Fi link became unreachable. */
    static final int CMD_IP_REACHABILITY_LOST                           = BASE + 149;

    /* Remove a packages associated configrations */
    static final int CMD_REMOVE_USER_CONFIGURATIONS                     = BASE + 152;

    static final int CMD_ACCEPT_UNVALIDATED                             = BASE + 153;

    /* used to offload sending IP packet */
    static final int CMD_START_IP_PACKET_OFFLOAD                        = BASE + 160;

    /* used to stop offload sending IP packet */
    static final int CMD_STOP_IP_PACKET_OFFLOAD                         = BASE + 161;

    /* used to start rssi monitoring in hw */
    static final int CMD_START_RSSI_MONITORING_OFFLOAD                  = BASE + 162;

    /* used to stop rssi moniroting in hw */
    static final int CMD_STOP_RSSI_MONITORING_OFFLOAD                   = BASE + 163;

    /* used to indicated RSSI threshold breach in hw */
    static final int CMD_RSSI_THRESHOLD_BREACHED                        = BASE + 164;

    /* Enable/Disable WifiConnectivityManager */
    static final int CMD_ENABLE_WIFI_CONNECTIVITY_MANAGER               = BASE + 166;


    /* Get FQDN list for Passpoint profiles matched with a given scanResults */
    static final int CMD_GET_ALL_MATCHING_FQDNS_FOR_SCAN_RESULTS = BASE + 168;

    /**
     * Used to handle messages bounced between ClientModeImpl and IpClient.
     */
    static final int CMD_IPV4_PROVISIONING_SUCCESS                      = BASE + 200;
    static final int CMD_IPV4_PROVISIONING_FAILURE                      = BASE + 201;

    /* Push a new APF program to the HAL */
    static final int CMD_INSTALL_PACKET_FILTER                          = BASE + 202;

    /* Enable/disable fallback packet filtering */
    static final int CMD_SET_FALLBACK_PACKET_FILTERING                  = BASE + 203;

    /* Enable/disable Neighbor Discovery offload functionality. */
    static final int CMD_CONFIG_ND_OFFLOAD                              = BASE + 204;

    /* used to indicate that the foreground user was switched */
    static final int CMD_USER_SWITCH                                    = BASE + 205;

    /* used to indicate that the foreground user was switched */
    static final int CMD_USER_UNLOCK                                    = BASE + 206;

    /* used to indicate that the foreground user was switched */
    static final int CMD_USER_STOP                                      = BASE + 207;

    /* Read the APF program & data buffer */
    static final int CMD_READ_PACKET_FILTER                             = BASE + 208;

    /** Used to add packet filter to apf. */
    static final int CMD_ADD_KEEPALIVE_PACKET_FILTER_TO_APF = BASE + 209;

    /** Used to remove packet filter from apf. */
    static final int CMD_REMOVE_KEEPALIVE_PACKET_FILTER_FROM_APF = BASE + 210;

    /* Indicates that diagnostics should time out a connection start event. */
    static final int CMD_DIAGS_CONNECT_TIMEOUT                          = BASE + 252;

    // Start subscription provisioning with a given provider
    private static final int CMD_START_SUBSCRIPTION_PROVISIONING        = BASE + 254;

    @VisibleForTesting
    static final int CMD_PRE_DHCP_ACTION                                = BASE + 255;
    private static final int CMD_PRE_DHCP_ACTION_COMPLETE               = BASE + 256;
    private static final int CMD_POST_DHCP_ACTION                       = BASE + 257;

    // For message logging.
    private static final Class[] sMessageClasses = {
            AsyncChannel.class, ClientModeImpl.class };
    private static final SparseArray<String> sGetWhatToString =
            MessageUtils.findMessageNames(sMessageClasses);


    /* Wifi state machine modes of operation */
    /* CONNECT_MODE - connect to any 'known' AP when it becomes available */
    public static final int CONNECT_MODE = 1;
    /* SCAN_ONLY_MODE - don't connect to any APs; scan, but only while apps hold lock */
    public static final int SCAN_ONLY_MODE = 2;
    /* SCAN_ONLY_WITH_WIFI_OFF - scan, but don't connect to any APs */
    public static final int SCAN_ONLY_WITH_WIFI_OFF_MODE = 3;
    /* DISABLED_MODE - Don't connect, don't scan, don't be an AP */
    public static final int DISABLED_MODE = 4;

    private static final int SUCCESS = 1;
    private static final int FAILURE = -1;

    /* Tracks if suspend optimizations need to be disabled by DHCP,
     * screen or due to high perf mode.
     * When any of them needs to disable it, we keep the suspend optimizations
     * disabled
     */
    private int mSuspendOptNeedsDisabled = 0;

    private static final int SUSPEND_DUE_TO_DHCP = 1;
    private static final int SUSPEND_DUE_TO_HIGH_PERF = 1 << 1;
    private static final int SUSPEND_DUE_TO_SCREEN = 1 << 2;

    /**
     * Time window in milliseconds for which we send
     * {@link NetworkAgent#explicitlySelected(boolean, boolean)}
     * after connecting to the network which the user last selected.
     */
    @VisibleForTesting
    public static final int LAST_SELECTED_NETWORK_EXPIRATION_AGE_MILLIS = 30 * 1000;

    /* Tracks if user has enabled suspend optimizations through settings */
    private AtomicBoolean mUserWantsSuspendOpt = new AtomicBoolean(true);

    /* Tracks if user has enabled Connected Mac Randomization through settings */

    /**
     * Supplicant scan interval in milliseconds.
     * Comes from {@link Settings.Global#WIFI_SUPPLICANT_SCAN_INTERVAL_MS} or
     * from the default config if the setting is not set
     */
    private long mSupplicantScanIntervalMs;

    int mRunningBeaconCount = 0;

    /* Default parent state */
    private State mDefaultState = new DefaultState();
    /* Connecting to an access point */
    private State mConnectModeState = new ConnectModeState();
    /* Connected at 802.11 (L2) level */
    private State mL2ConnectedState = new L2ConnectedState();
    /* fetching IP after connection to access point (assoc+auth complete) */
    private State mObtainingIpState = new ObtainingIpState();
    /* Connected with IP addr */
    private State mConnectedState = new ConnectedState();
    /* Roaming */
    private State mRoamingState = new RoamingState();
    /* disconnect issued, waiting for network disconnect confirmation */
    private State mDisconnectingState = new DisconnectingState();
    /* Network is not connected, supplicant assoc+auth is not complete */
    private State mDisconnectedState = new DisconnectedState();

    /**
     * One of  {@link WifiManager#WIFI_STATE_DISABLED},
     * {@link WifiManager#WIFI_STATE_DISABLING},
     * {@link WifiManager#WIFI_STATE_ENABLED},
     * {@link WifiManager#WIFI_STATE_ENABLING},
     * {@link WifiManager#WIFI_STATE_UNKNOWN}
     */
    private final AtomicInteger mWifiState = new AtomicInteger(WIFI_STATE_DISABLED);

    /**
     * Work source to use to blame usage on the WiFi service
     */
    public static final WorkSource WIFI_WORK_SOURCE = new WorkSource(Process.WIFI_UID);

    /**
     * Keep track of whether WIFI is running.
     */
    private boolean mIsRunning = false;

    /**
     * Keep track of whether we last told the battery stats we had started.
     */
    private boolean mReportedRunning = false;

    /**
     * Most recently set source of starting WIFI.
     */
    private final WorkSource mRunningWifiUids = new WorkSource();

    /**
     * The last reported UIDs that were responsible for starting WIFI.
     */
    private final WorkSource mLastRunningWifiUids = new WorkSource();

    private TelephonyManager mTelephonyManager;
    private TelephonyManager getTelephonyManager() {
        if (mTelephonyManager == null) {
            mTelephonyManager = mWifiInjector.makeTelephonyManager();
        }
        return mTelephonyManager;
    }

    private final IBatteryStats mBatteryStats;

    private final String mTcpBufferSizes;

    // Used for debug and stats gathering
    private static int sScanAlarmIntentCount = 0;

    private FrameworkFacade mFacade;
    private WifiStateTracker mWifiStateTracker;
    private final BackupManagerProxy mBackupManagerProxy;
    private final WrongPasswordNotifier mWrongPasswordNotifier;
    private WifiNetworkSuggestionsManager mWifiNetworkSuggestionsManager;
    private boolean mConnectedMacRandomzationSupported;

    public ClientModeImpl(Context context, FrameworkFacade facade, Looper looper,
                            UserManager userManager, WifiInjector wifiInjector,
                            BackupManagerProxy backupManagerProxy, WifiCountryCode countryCode,
                            WifiNative wifiNative, WrongPasswordNotifier wrongPasswordNotifier,
                            SarManager sarManager, WifiTrafficPoller wifiTrafficPoller,
                            LinkProbeManager linkProbeManager) {
        super(TAG, looper);
        mWifiInjector = wifiInjector;
        mWifiMetrics = mWifiInjector.getWifiMetrics();
        mClock = wifiInjector.getClock();
        mPropertyService = wifiInjector.getPropertyService();
        mBuildProperties = wifiInjector.getBuildProperties();
        mWifiScoreCard = wifiInjector.getWifiScoreCard();
        mContext = context;
        mFacade = facade;
        mWifiNative = wifiNative;
        mBackupManagerProxy = backupManagerProxy;
        mWrongPasswordNotifier = wrongPasswordNotifier;
        mSarManager = sarManager;
        mWifiTrafficPoller = wifiTrafficPoller;
        mLinkProbeManager = linkProbeManager;

        mNetworkInfo = new NetworkInfo(ConnectivityManager.TYPE_WIFI, 0, NETWORKTYPE, "");
        mBatteryStats = IBatteryStats.Stub.asInterface(mFacade.getService(
                BatteryStats.SERVICE_NAME));
        mWifiStateTracker = wifiInjector.getWifiStateTracker();
        IBinder b = mFacade.getService(Context.NETWORKMANAGEMENT_SERVICE);

        mP2pSupported = mContext.getPackageManager().hasSystemFeature(
                PackageManager.FEATURE_WIFI_DIRECT);

        mWifiPermissionsUtil = mWifiInjector.getWifiPermissionsUtil();
        mWifiConfigManager = mWifiInjector.getWifiConfigManager();

        mPasspointManager = mWifiInjector.getPasspointManager();

        mWifiMonitor = mWifiInjector.getWifiMonitor();
        mWifiDiagnostics = mWifiInjector.getWifiDiagnostics();
        mWifiPermissionsWrapper = mWifiInjector.getWifiPermissionsWrapper();
        mWifiDataStall = mWifiInjector.getWifiDataStall();

        mWifiInfo = new ExtendedWifiInfo();
        mSupplicantStateTracker =
                mFacade.makeSupplicantStateTracker(context, mWifiConfigManager, getHandler());
        mWifiConnectivityManager = mWifiInjector.makeWifiConnectivityManager(this);


        mLinkProperties = new LinkProperties();
        mMcastLockManagerFilterController = new McastLockManagerFilterController();

        mNetworkInfo.setIsAvailable(false);
        mLastBssid = null;
        mLastNetworkId = WifiConfiguration.INVALID_NETWORK_ID;
        mLastSignalLevel = -1;

        mCountryCode = countryCode;

        mWifiScoreReport = new WifiScoreReport(mWifiInjector.getScoringParams(), mClock);

        mNetworkCapabilitiesFilter.addTransportType(NetworkCapabilities.TRANSPORT_WIFI);
        mNetworkCapabilitiesFilter.addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        mNetworkCapabilitiesFilter.addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
        mNetworkCapabilitiesFilter.addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING);
        mNetworkCapabilitiesFilter.addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_CONGESTED);
        mNetworkCapabilitiesFilter.addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED);
        // TODO - needs to be a bit more dynamic
        mNetworkCapabilitiesFilter.setLinkUpstreamBandwidthKbps(1024 * 1024);
        mNetworkCapabilitiesFilter.setLinkDownstreamBandwidthKbps(1024 * 1024);
        mNetworkCapabilitiesFilter.setNetworkSpecifier(new MatchAllNetworkSpecifier());
        // Make the network factories.
        mNetworkFactory = mWifiInjector.makeWifiNetworkFactory(
                mNetworkCapabilitiesFilter, mWifiConnectivityManager);
        // We can't filter untrusted network in the capabilities filter because a trusted
        // network would still satisfy a request that accepts untrusted ones.
        // We need a second network factory for untrusted network requests because we need a
        // different score filter for these requests.
        mUntrustedNetworkFactory = mWifiInjector.makeUntrustedWifiNetworkFactory(
                mNetworkCapabilitiesFilter, mWifiConnectivityManager);

        mWifiNetworkSuggestionsManager = mWifiInjector.getWifiNetworkSuggestionsManager();

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        mContext.registerReceiver(
                new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        String action = intent.getAction();

                        if (action.equals(Intent.ACTION_SCREEN_ON)) {
                            sendMessage(CMD_SCREEN_STATE_CHANGED, 1);
                        } else if (action.equals(Intent.ACTION_SCREEN_OFF)) {
                            sendMessage(CMD_SCREEN_STATE_CHANGED, 0);
                        }
                    }
                }, filter);

        mFacade.registerContentObserver(mContext, Settings.Global.getUriFor(
                        Settings.Global.WIFI_SUSPEND_OPTIMIZATIONS_ENABLED), false,
                new ContentObserver(getHandler()) {
                    @Override
                    public void onChange(boolean selfChange) {
                        mUserWantsSuspendOpt.set(mFacade.getIntegerSetting(mContext,
                                Settings.Global.WIFI_SUSPEND_OPTIMIZATIONS_ENABLED, 1) == 1);
                    }
                });

        mUserWantsSuspendOpt.set(mFacade.getIntegerSetting(mContext,
                Settings.Global.WIFI_SUSPEND_OPTIMIZATIONS_ENABLED, 1) == 1);

        PowerManager powerManager = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
        mWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, getName());

        mSuspendWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WifiSuspend");
        mSuspendWakeLock.setReferenceCounted(false);

        mConnectedMacRandomzationSupported = mContext.getResources()
                .getBoolean(R.bool.config_wifi_connected_mac_randomization_supported);
        mWifiInfo.setEnableConnectedMacRandomization(mConnectedMacRandomzationSupported);
        mWifiMetrics.setIsMacRandomizationOn(mConnectedMacRandomzationSupported);

        mTcpBufferSizes = mContext.getResources().getString(
                R.string.config_wifi_tcp_buffers);

        // CHECKSTYLE:OFF IndentationCheck
        addState(mDefaultState);
            addState(mConnectModeState, mDefaultState);
                addState(mL2ConnectedState, mConnectModeState);
                    addState(mObtainingIpState, mL2ConnectedState);
                    addState(mConnectedState, mL2ConnectedState);
                    addState(mRoamingState, mL2ConnectedState);
                addState(mDisconnectingState, mConnectModeState);
                addState(mDisconnectedState, mConnectModeState);
        // CHECKSTYLE:ON IndentationCheck

        setInitialState(mDefaultState);

        setLogRecSize(NUM_LOG_RECS_NORMAL);
        setLogOnlyTransitions(false);
    }

    @Override
    public void start() {
        super.start();

        PowerManager powerManager = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);

        // Learn the initial state of whether the screen is on.
        // We update this field when we receive broadcasts from the system.
        handleScreenStateChanged(powerManager.isInteractive());
    }

    private void registerForWifiMonitorEvents()  {
        mWifiMonitor.registerHandler(mInterfaceName, CMD_TARGET_BSSID, getHandler());
        mWifiMonitor.registerHandler(mInterfaceName, CMD_ASSOCIATED_BSSID, getHandler());
        mWifiMonitor.registerHandler(mInterfaceName, WifiMonitor.ANQP_DONE_EVENT, getHandler());
        mWifiMonitor.registerHandler(mInterfaceName, WifiMonitor.ASSOCIATION_REJECTION_EVENT,
                getHandler());
        mWifiMonitor.registerHandler(mInterfaceName, WifiMonitor.AUTHENTICATION_FAILURE_EVENT,
                getHandler());
        mWifiMonitor.registerHandler(mInterfaceName, WifiMonitor.GAS_QUERY_DONE_EVENT,
                getHandler());
        mWifiMonitor.registerHandler(mInterfaceName, WifiMonitor.GAS_QUERY_START_EVENT,
                getHandler());
        mWifiMonitor.registerHandler(mInterfaceName, WifiMonitor.HS20_REMEDIATION_EVENT,
                getHandler());
        mWifiMonitor.registerHandler(mInterfaceName, WifiMonitor.NETWORK_CONNECTION_EVENT,
                getHandler());
        mWifiMonitor.registerHandler(mInterfaceName, WifiMonitor.NETWORK_DISCONNECTION_EVENT,
                getHandler());
        mWifiMonitor.registerHandler(mInterfaceName, WifiMonitor.RX_HS20_ANQP_ICON_EVENT,
                getHandler());
        mWifiMonitor.registerHandler(mInterfaceName, WifiMonitor.SUPPLICANT_STATE_CHANGE_EVENT,
                getHandler());
        mWifiMonitor.registerHandler(mInterfaceName, WifiMonitor.SUP_REQUEST_IDENTITY,
                getHandler());
        mWifiMonitor.registerHandler(mInterfaceName, WifiMonitor.SUP_REQUEST_SIM_AUTH,
                getHandler());
        mWifiMonitor.registerHandler(mInterfaceName, WifiMonitor.ASSOCIATION_REJECTION_EVENT,
                mWifiMetrics.getHandler());
        mWifiMonitor.registerHandler(mInterfaceName, WifiMonitor.AUTHENTICATION_FAILURE_EVENT,
                mWifiMetrics.getHandler());
        mWifiMonitor.registerHandler(mInterfaceName, WifiMonitor.NETWORK_CONNECTION_EVENT,
                mWifiMetrics.getHandler());
        mWifiMonitor.registerHandler(mInterfaceName, WifiMonitor.NETWORK_DISCONNECTION_EVENT,
                mWifiMetrics.getHandler());
        mWifiMonitor.registerHandler(mInterfaceName, WifiMonitor.SUPPLICANT_STATE_CHANGE_EVENT,
                mWifiMetrics.getHandler());
        mWifiMonitor.registerHandler(mInterfaceName, CMD_ASSOCIATED_BSSID,
                mWifiMetrics.getHandler());
        mWifiMonitor.registerHandler(mInterfaceName, CMD_TARGET_BSSID,
                mWifiMetrics.getHandler());
    }

    private void setMulticastFilter(boolean enabled) {
        if (mIpClient != null) {
            mIpClient.setMulticastFilter(enabled);
        }
    }

    /**
     * Class to implement the MulticastLockManager.FilterController callback.
     */
    class McastLockManagerFilterController implements WifiMulticastLockManager.FilterController {
        /**
         * Start filtering Multicast v4 packets
         */
        public void startFilteringMulticastPackets() {
            setMulticastFilter(true);
        }

        /**
         * Stop filtering Multicast v4 packets
         */
        public void stopFilteringMulticastPackets() {
            setMulticastFilter(false);
        }
    }

    class IpClientCallbacksImpl extends IpClientCallbacks {
        private final ConditionVariable mWaitForCreationCv = new ConditionVariable(false);
        private final ConditionVariable mWaitForStopCv = new ConditionVariable(false);

        @Override
        public void onIpClientCreated(IIpClient ipClient) {
            mIpClient = new IpClientManager(ipClient, getName());
            mWaitForCreationCv.open();
        }

        @Override
        public void onPreDhcpAction() {
            sendMessage(CMD_PRE_DHCP_ACTION);
        }

        @Override
        public void onPostDhcpAction() {
            sendMessage(CMD_POST_DHCP_ACTION);
        }

        @Override
        public void onNewDhcpResults(DhcpResults dhcpResults) {
            if (dhcpResults != null) {
                sendMessage(CMD_IPV4_PROVISIONING_SUCCESS, dhcpResults);
            } else {
                sendMessage(CMD_IPV4_PROVISIONING_FAILURE);
            }
        }

        @Override
        public void onProvisioningSuccess(LinkProperties newLp) {
            mWifiMetrics.logStaEvent(StaEvent.TYPE_CMD_IP_CONFIGURATION_SUCCESSFUL);
            sendMessage(CMD_UPDATE_LINKPROPERTIES, newLp);
            sendMessage(CMD_IP_CONFIGURATION_SUCCESSFUL);
        }

        @Override
        public void onProvisioningFailure(LinkProperties newLp) {
            mWifiMetrics.logStaEvent(StaEvent.TYPE_CMD_IP_CONFIGURATION_LOST);
            sendMessage(CMD_IP_CONFIGURATION_LOST);
        }

        @Override
        public void onLinkPropertiesChange(LinkProperties newLp) {
            sendMessage(CMD_UPDATE_LINKPROPERTIES, newLp);
        }

        @Override
        public void onReachabilityLost(String logMsg) {
            mWifiMetrics.logStaEvent(StaEvent.TYPE_CMD_IP_REACHABILITY_LOST);
            sendMessage(CMD_IP_REACHABILITY_LOST, logMsg);
        }

        @Override
        public void installPacketFilter(byte[] filter) {
            sendMessage(CMD_INSTALL_PACKET_FILTER, filter);
        }

        @Override
        public void startReadPacketFilter() {
            sendMessage(CMD_READ_PACKET_FILTER);
        }

        @Override
        public void setFallbackMulticastFilter(boolean enabled) {
            sendMessage(CMD_SET_FALLBACK_PACKET_FILTERING, enabled);
        }

        @Override
        public void setNeighborDiscoveryOffload(boolean enabled) {
            sendMessage(CMD_CONFIG_ND_OFFLOAD, (enabled ? 1 : 0));
        }

        @Override
        public void onQuit() {
            mWaitForStopCv.open();
        }

        boolean awaitCreation() {
            return mWaitForCreationCv.block(IPCLIENT_TIMEOUT_MS);
        }

        boolean awaitShutdown() {
            return mWaitForStopCv.block(IPCLIENT_TIMEOUT_MS);
        }
    }

    private void stopIpClient() {
        /* Restore power save and suspend optimizations */
        handlePostDhcpSetup();
        if (mIpClient != null) {
            mIpClient.stop();
        }
    }

    /**
     * Set wpa_supplicant log level using |mVerboseLoggingLevel| flag.
     */
    void setSupplicantLogLevel() {
        mWifiNative.setSupplicantLogLevel(mVerboseLoggingEnabled);
    }

    /**
     * Method to update logging level in wifi service related classes.
     *
     * @param verbose int logging level to use
     */
    public void enableVerboseLogging(int verbose) {
        if (verbose > 0) {
            mVerboseLoggingEnabled = true;
            setLogRecSize(ActivityManager.isLowRamDeviceStatic()
                    ? NUM_LOG_RECS_VERBOSE_LOW_MEMORY : NUM_LOG_RECS_VERBOSE);
        } else {
            mVerboseLoggingEnabled = false;
            setLogRecSize(NUM_LOG_RECS_NORMAL);
        }
        configureVerboseHalLogging(mVerboseLoggingEnabled);
        setSupplicantLogLevel();
        mCountryCode.enableVerboseLogging(verbose);
        mWifiScoreReport.enableVerboseLogging(mVerboseLoggingEnabled);
        mWifiDiagnostics.startLogging(mVerboseLoggingEnabled);
        mWifiMonitor.enableVerboseLogging(verbose);
        mWifiNative.enableVerboseLogging(verbose);
        mWifiConfigManager.enableVerboseLogging(verbose);
        mSupplicantStateTracker.enableVerboseLogging(verbose);
        mPasspointManager.enableVerboseLogging(verbose);
        mNetworkFactory.enableVerboseLogging(verbose);
        mLinkProbeManager.enableVerboseLogging(mVerboseLoggingEnabled);
    }

    private static final String SYSTEM_PROPERTY_LOG_CONTROL_WIFIHAL = "log.tag.WifiHAL";
    private static final String LOGD_LEVEL_DEBUG = "D";
    private static final String LOGD_LEVEL_VERBOSE = "V";
    private void configureVerboseHalLogging(boolean enableVerbose) {
        if (mBuildProperties.isUserBuild()) {  // Verbose HAL logging not supported on user builds.
            return;
        }
        mPropertyService.set(SYSTEM_PROPERTY_LOG_CONTROL_WIFIHAL,
                enableVerbose ? LOGD_LEVEL_VERBOSE : LOGD_LEVEL_DEBUG);
    }

    private boolean setRandomMacOui() {
        String oui = mContext.getResources().getString(R.string.config_wifi_random_mac_oui);
        if (TextUtils.isEmpty(oui)) {
            oui = GOOGLE_OUI;
        }
        String[] ouiParts = oui.split("-");
        byte[] ouiBytes = new byte[3];
        ouiBytes[0] = (byte) (Integer.parseInt(ouiParts[0], 16) & 0xFF);
        ouiBytes[1] = (byte) (Integer.parseInt(ouiParts[1], 16) & 0xFF);
        ouiBytes[2] = (byte) (Integer.parseInt(ouiParts[2], 16) & 0xFF);

        logd("Setting OUI to " + oui);
        return mWifiNative.setScanningMacOui(mInterfaceName, ouiBytes);
    }

    /**
     * Initiates connection to a network specified by the user/app. This method checks if the
     * requesting app holds the NETWORK_SETTINGS permission.
     *
     * @param netId Id network to initiate connection.
     * @param uid UID of the app requesting the connection.
     * @param forceReconnect Whether to force a connection even if we're connected to the same
     *                       network currently.
     */
    private boolean connectToUserSelectNetwork(int netId, int uid, boolean forceReconnect) {
        logd("connectToUserSelectNetwork netId " + netId + ", uid " + uid
                + ", forceReconnect = " + forceReconnect);
        WifiConfiguration config = mWifiConfigManager.getConfiguredNetwork(netId);
        if (config == null) {
            loge("connectToUserSelectNetwork Invalid network Id=" + netId);
            return false;
        }
        if (!mWifiConfigManager.enableNetwork(netId, true, uid)
                || !mWifiConfigManager.updateLastConnectUid(netId, uid)) {
            logi("connectToUserSelectNetwork Allowing uid " + uid
                    + " with insufficient permissions to connect=" + netId);
        } else if (mWifiPermissionsUtil.checkNetworkSettingsPermission(uid)) {
            // Note user connect choice here, so that it will be considered in the next network
            // selection.
            mWifiConnectivityManager.setUserConnectChoice(netId);
        }
        if (!forceReconnect && mWifiInfo.getNetworkId() == netId) {
            // We're already connected to the user specified network, don't trigger a
            // reconnection unless it was forced.
            logi("connectToUserSelectNetwork already connecting/connected=" + netId);
        } else {
            mWifiConnectivityManager.prepareForForcedConnection(netId);
            if (uid == Process.SYSTEM_UID) {
                mWifiMetrics.setNominatorForNetwork(config.networkId,
                        WifiMetricsProto.ConnectionEvent.NOMINATOR_MANUAL);
            }
            startConnectToNetwork(netId, uid, SUPPLICANT_BSSID_ANY);
        }
        return true;
    }

    /**
     * ******************************************************
     * Methods exposed for public use
     * ******************************************************
     */

    /**
     * Retrieve a Messenger for the ClientModeImpl Handler
     *
     * @return Messenger
     */
    public Messenger getMessenger() {
        return new Messenger(getHandler());
    }

    // Last connect attempt is used to prevent scan requests:
    //  - for a period of 10 seconds after attempting to connect
    private long mLastConnectAttemptTimestamp = 0;

    // For debugging, keep track of last message status handling
    // TODO, find an equivalent mechanism as part of parent class
    private static final int MESSAGE_HANDLING_STATUS_PROCESSED = 2;
    private static final int MESSAGE_HANDLING_STATUS_OK = 1;
    private static final int MESSAGE_HANDLING_STATUS_UNKNOWN = 0;
    private static final int MESSAGE_HANDLING_STATUS_REFUSED = -1;
    private static final int MESSAGE_HANDLING_STATUS_FAIL = -2;
    private static final int MESSAGE_HANDLING_STATUS_OBSOLETE = -3;
    private static final int MESSAGE_HANDLING_STATUS_DEFERRED = -4;
    private static final int MESSAGE_HANDLING_STATUS_DISCARD = -5;
    private static final int MESSAGE_HANDLING_STATUS_LOOPED = -6;
    private static final int MESSAGE_HANDLING_STATUS_HANDLING_ERROR = -7;

    private int mMessageHandlingStatus = 0;

    private int mOnTime = 0;
    private int mTxTime = 0;
    private int mRxTime = 0;

    private int mOnTimeScreenStateChange = 0;
    private long mLastOntimeReportTimeStamp = 0;
    private long mLastScreenStateChangeTimeStamp = 0;
    private int mOnTimeLastReport = 0;
    private int mTxTimeLastReport = 0;
    private int mRxTimeLastReport = 0;

    private WifiLinkLayerStats mLastLinkLayerStats;
    private long mLastLinkLayerStatsUpdate = 0;

    String reportOnTime() {
        long now = mClock.getWallClockMillis();
        StringBuilder sb = new StringBuilder();
        // Report stats since last report
        int on = mOnTime - mOnTimeLastReport;
        mOnTimeLastReport = mOnTime;
        int tx = mTxTime - mTxTimeLastReport;
        mTxTimeLastReport = mTxTime;
        int rx = mRxTime - mRxTimeLastReport;
        mRxTimeLastReport = mRxTime;
        int period = (int) (now - mLastOntimeReportTimeStamp);
        mLastOntimeReportTimeStamp = now;
        sb.append(String.format("[on:%d tx:%d rx:%d period:%d]", on, tx, rx, period));
        // Report stats since Screen State Changed
        on = mOnTime - mOnTimeScreenStateChange;
        period = (int) (now - mLastScreenStateChangeTimeStamp);
        sb.append(String.format(" from screen [on:%d period:%d]", on, period));
        return sb.toString();
    }

    WifiLinkLayerStats getWifiLinkLayerStats() {
        if (mInterfaceName == null) {
            loge("getWifiLinkLayerStats called without an interface");
            return null;
        }
        mLastLinkLayerStatsUpdate = mClock.getWallClockMillis();
        WifiLinkLayerStats stats = mWifiNative.getWifiLinkLayerStats(mInterfaceName);
        if (stats != null) {
            mOnTime = stats.on_time;
            mTxTime = stats.tx_time;
            mRxTime = stats.rx_time;
            mRunningBeaconCount = stats.beacon_rx;
            mWifiInfo.updatePacketRates(stats, mLastLinkLayerStatsUpdate);
        } else {
            long mTxPkts = mFacade.getTxPackets(mInterfaceName);
            long mRxPkts = mFacade.getRxPackets(mInterfaceName);
            mWifiInfo.updatePacketRates(mTxPkts, mRxPkts, mLastLinkLayerStatsUpdate);
        }
        return stats;
    }

    private byte[] getDstMacForKeepalive(KeepalivePacketData packetData)
            throws InvalidPacketException {
        try {
            InetAddress gateway = RouteInfo.selectBestRoute(
                    mLinkProperties.getRoutes(), packetData.dstAddress).getGateway();
            String dstMacStr = macAddressFromRoute(gateway.getHostAddress());
            return NativeUtil.macAddressToByteArray(dstMacStr);
        } catch (NullPointerException | IllegalArgumentException e) {
            throw new InvalidPacketException(SocketKeepalive.ERROR_INVALID_IP_ADDRESS);
        }
    }

    private static int getEtherProtoForKeepalive(KeepalivePacketData packetData)
            throws InvalidPacketException {
        if (packetData.dstAddress instanceof Inet4Address) {
            return OsConstants.ETH_P_IP;
        } else if (packetData.dstAddress instanceof Inet6Address) {
            return OsConstants.ETH_P_IPV6;
        } else {
            throw new InvalidPacketException(SocketKeepalive.ERROR_INVALID_IP_ADDRESS);
        }
    }

    private int startWifiIPPacketOffload(int slot, KeepalivePacketData packetData,
            int intervalSeconds) {
        byte[] packet = null;
        byte[] dstMac = null;
        int proto = 0;

        try {
            packet = packetData.getPacket();
            dstMac = getDstMacForKeepalive(packetData);
            proto = getEtherProtoForKeepalive(packetData);
        } catch (InvalidPacketException e) {
            return e.error;
        }

        int ret = mWifiNative.startSendingOffloadedPacket(
                mInterfaceName, slot, dstMac, packet, proto, intervalSeconds * 1000);
        if (ret != 0) {
            loge("startWifiIPPacketOffload(" + slot + ", " + intervalSeconds
                    + "): hardware error " + ret);
            return SocketKeepalive.ERROR_HARDWARE_ERROR;
        } else {
            return SocketKeepalive.SUCCESS;
        }
    }

    private int stopWifiIPPacketOffload(int slot) {
        int ret = mWifiNative.stopSendingOffloadedPacket(mInterfaceName, slot);
        if (ret != 0) {
            loge("stopWifiIPPacketOffload(" + slot + "): hardware error " + ret);
            return SocketKeepalive.ERROR_HARDWARE_ERROR;
        } else {
            return SocketKeepalive.SUCCESS;
        }
    }

    private int startRssiMonitoringOffload(byte maxRssi, byte minRssi,
            WifiNative.WifiRssiEventHandler rssiHandler) {
        return mWifiNative.startRssiMonitoring(mInterfaceName, maxRssi, minRssi, rssiHandler);
    }

    private int stopRssiMonitoringOffload() {
        return mWifiNative.stopRssiMonitoring(mInterfaceName);
    }

    /**
     * Temporary method that allows the active ClientModeManager to set the wifi state that is
     * retrieved by API calls. This will be removed when WifiServiceImpl no longer directly calls
     * this class (b/31479117).
     *
     * @param newState new state to set, invalid states are ignored.
     */
    public void setWifiStateForApiCalls(int newState) {
        switch (newState) {
            case WIFI_STATE_DISABLING:
            case WIFI_STATE_DISABLED:
            case WIFI_STATE_ENABLING:
            case WIFI_STATE_ENABLED:
            case WIFI_STATE_UNKNOWN:
                if (mVerboseLoggingEnabled) {
                    Log.d(TAG, "setting wifi state to: " + newState);
                }
                mWifiState.set(newState);
                return;
            default:
                Log.d(TAG, "attempted to set an invalid state: " + newState);
                return;
        }
    }

    /**
     * Method used by WifiServiceImpl to get the current state of Wifi (in client mode) for API
     * calls.  This will be removed when WifiService no longer directly calls this class
     * (b/31479117).
     */
    public int syncGetWifiState() {
        return mWifiState.get();
    }

    /**
     * Converts the current wifi state to a printable form.
     */
    public String syncGetWifiStateByName() {
        switch (mWifiState.get()) {
            case WIFI_STATE_DISABLING:
                return "disabling";
            case WIFI_STATE_DISABLED:
                return "disabled";
            case WIFI_STATE_ENABLING:
                return "enabling";
            case WIFI_STATE_ENABLED:
                return "enabled";
            case WIFI_STATE_UNKNOWN:
                return "unknown state";
            default:
                return "[invalid state]";
        }
    }

    public boolean isConnected() {
        return getCurrentState() == mConnectedState;
    }

    public boolean isDisconnected() {
        return getCurrentState() == mDisconnectedState;
    }

    /**
     * Method checking if supplicant is in a transient state
     *
     * @return boolean true if in transient state
     */
    public boolean isSupplicantTransientState() {
        SupplicantState supplicantState = mWifiInfo.getSupplicantState();
        if (supplicantState == SupplicantState.ASSOCIATING
                || supplicantState == SupplicantState.AUTHENTICATING
                || supplicantState == SupplicantState.FOUR_WAY_HANDSHAKE
                || supplicantState == SupplicantState.GROUP_HANDSHAKE) {

            if (mVerboseLoggingEnabled) {
                Log.d(TAG, "Supplicant is under transient state: " + supplicantState);
            }
            return true;
        } else {
            if (mVerboseLoggingEnabled) {
                Log.d(TAG, "Supplicant is under steady state: " + supplicantState);
            }
        }

        return false;
    }

    /**
     * Get status information for the current connection, if any.
     *
     * @return a {@link WifiInfo} object containing information about the current connection
     */
    public WifiInfo syncRequestConnectionInfo() {
        WifiInfo result = new WifiInfo(mWifiInfo);
        return result;
    }

    /**
     * Method to retrieve the current WifiInfo
     *
     * @returns WifiInfo
     */
    public WifiInfo getWifiInfo() {
        return mWifiInfo;
    }

    /**
     * Blocking call to get the current DHCP results
     *
     * @return DhcpResults current results
     */
    public DhcpResults syncGetDhcpResults() {
        synchronized (mDhcpResultsLock) {
            return new DhcpResults(mDhcpResults);
        }
    }

    /**
     * When the underlying interface is destroyed, we must immediately tell connectivity service to
     * mark network agent as disconnected and stop the ip client.
     */
    public void handleIfaceDestroyed() {
        handleNetworkDisconnect();
    }

    /**
     * TODO: doc
     */
    public void setOperationalMode(int mode, String ifaceName) {
        if (mVerboseLoggingEnabled) {
            log("setting operational mode to " + String.valueOf(mode) + " for iface: " + ifaceName);
        }
        mModeChange = true;
        if (mode != CONNECT_MODE) {
            // we are disabling client mode...   need to exit connect mode now
            transitionTo(mDefaultState);
        } else {
            // do a quick sanity check on the iface name, make sure it isn't null
            if (ifaceName != null) {
                mInterfaceName = ifaceName;
                transitionTo(mDisconnectedState);
            } else {
                Log.e(TAG, "supposed to enter connect mode, but iface is null -> DefaultState");
                transitionTo(mDefaultState);
            }
        }
        // use the CMD_SET_OPERATIONAL_MODE to force the transitions before other messages are
        // handled.
        sendMessageAtFrontOfQueue(CMD_SET_OPERATIONAL_MODE);
    }

    /**
     * Initiates a system-level bugreport, in a non-blocking fashion.
     */
    public void takeBugReport(String bugTitle, String bugDetail) {
        mWifiDiagnostics.takeBugReport(bugTitle, bugDetail);
    }

    /**
     * Allow tests to confirm the operational mode for ClientModeImpl for testing.
     */
    @VisibleForTesting
    protected int getOperationalModeForTest() {
        return mOperationalMode;
    }

    /**
     * Retrieve the WifiMulticastLockManager.FilterController callback for registration.
     */
    protected WifiMulticastLockManager.FilterController getMcastLockManagerFilterController() {
        return mMcastLockManagerFilterController;
    }

    /**
     * Blocking method to retrieve the passpoint icon.
     *
     * @param channel AsyncChannel for the response
     * @param bssid representation of the bssid as a long
     * @param fileName name of the file
     *
     * @return boolean returning the result of the call
     */
    public boolean syncQueryPasspointIcon(AsyncChannel channel, long bssid, String fileName) {
        Bundle bundle = new Bundle();
        bundle.putLong(EXTRA_OSU_ICON_QUERY_BSSID, bssid);
        bundle.putString(EXTRA_OSU_ICON_QUERY_FILENAME, fileName);
        Message resultMsg = channel.sendMessageSynchronously(CMD_QUERY_OSU_ICON, bundle);
        int result = resultMsg.arg1;
        resultMsg.recycle();
        return result == 1;
    }

    /**
     * Blocking method to match the provider with the current network
     *
     * @param channel AsyncChannel to use for the response
     * @param fqdn
     * @return int returns message result
     */
    public int matchProviderWithCurrentNetwork(AsyncChannel channel, String fqdn) {
        Message resultMsg = channel.sendMessageSynchronously(CMD_MATCH_PROVIDER_NETWORK, fqdn);
        int result = resultMsg.arg1;
        resultMsg.recycle();
        return result;
    }

    /**
     * Deauthenticate and set the re-authentication hold off time for the current network
     * @param holdoff hold off time in milliseconds
     * @param ess set if the hold off pertains to an ESS rather than a BSS
     */
    public void deauthenticateNetwork(AsyncChannel channel, long holdoff, boolean ess) {
        // TODO: This needs an implementation
    }

    /**
     * Method to disable an ephemeral config for an ssid
     *
     * @param ssid network name to disable
     */
    public void disableEphemeralNetwork(String ssid) {
        if (ssid != null) {
            sendMessage(CMD_DISABLE_EPHEMERAL_NETWORK, ssid);
        }
    }

    /**
     * Disconnect from Access Point
     */
    public void disconnectCommand() {
        sendMessage(CMD_DISCONNECT);
    }

    /**
     * Method to trigger a disconnect.
     *
     * @param uid UID of requesting caller
     * @param reason disconnect reason
     */
    public void disconnectCommand(int uid, int reason) {
        sendMessage(CMD_DISCONNECT, uid, reason);
    }

    /**
     * Initiate a reconnection to AP
     */
    public void reconnectCommand(WorkSource workSource) {
        sendMessage(CMD_RECONNECT, workSource);
    }

    /**
     * Initiate a re-association to AP
     */
    public void reassociateCommand() {
        sendMessage(CMD_REASSOCIATE);
    }

    /**
     * Checks for a null Message.
     *
     * This can happen with sendMessageSynchronously, for example if an
     * InterruptedException occurs. If this just happens once, silently
     * ignore it, because it is probably a side effect of shutting down.
     * If it happens a second time, generate a WTF.
     */
    private boolean messageIsNull(Message resultMsg) {
        if (resultMsg != null) return false;
        if (mNullMessageCounter.getAndIncrement() > 0) {
            Log.wtf(TAG, "Persistent null Message", new RuntimeException());
        }
        return true;
    }
    private AtomicInteger mNullMessageCounter = new AtomicInteger(0);

    /**
     * Add a network synchronously
     *
     * @return network id of the new network
     */
    public int syncAddOrUpdateNetwork(AsyncChannel channel, WifiConfiguration config) {
        Message resultMsg = channel.sendMessageSynchronously(CMD_ADD_OR_UPDATE_NETWORK, config);
        if (messageIsNull(resultMsg)) return WifiConfiguration.INVALID_NETWORK_ID;
        int result = resultMsg.arg1;
        resultMsg.recycle();
        return result;
    }

    /**
     * Get configured networks synchronously
     *
     * @param channel
     * @return
     */
    public List<WifiConfiguration> syncGetConfiguredNetworks(int uuid, AsyncChannel channel,
            int targetUid) {
        Message resultMsg = channel.sendMessageSynchronously(CMD_GET_CONFIGURED_NETWORKS, uuid,
                targetUid);
        if (messageIsNull(resultMsg)) return null;
        List<WifiConfiguration> result = (List<WifiConfiguration>) resultMsg.obj;
        resultMsg.recycle();
        return result;
    }

    /**
     * Blocking call to get the current WifiConfiguration by a privileged caller so private data,
     * like the password, is not redacted.
     *
     * @param channel AsyncChannel to use for the response
     * @return List list of configured networks configs
     */
    public List<WifiConfiguration> syncGetPrivilegedConfiguredNetwork(AsyncChannel channel) {
        Message resultMsg = channel.sendMessageSynchronously(
                CMD_GET_PRIVILEGED_CONFIGURED_NETWORKS);
        if (messageIsNull(resultMsg)) return null;
        List<WifiConfiguration> result = (List<WifiConfiguration>) resultMsg.obj;
        resultMsg.recycle();
        return result;
    }

    /**
     * Returns the list of FQDN (Fully Qualified Domain Name) to installed Passpoint configurations.
     *
     * Return the map of all matching configurations with corresponding scanResults (or an empty map
     * if none).
     *
     * @param scanResults The list of scan results
     * @return Map that consists of FQDN (Fully Qualified Domain Name) and corresponding
     * scanResults per network type({@link WifiManager#PASSPOINT_HOME_NETWORK} and {@link
     * WifiManager#PASSPOINT_ROAMING_NETWORK}).
     */
    @NonNull
    Map<String, Map<Integer, List<ScanResult>>> syncGetAllMatchingFqdnsForScanResults(
            List<ScanResult> scanResults,
            AsyncChannel channel) {
        Message resultMsg = channel.sendMessageSynchronously(
                CMD_GET_ALL_MATCHING_FQDNS_FOR_SCAN_RESULTS,
                scanResults);
        if (messageIsNull(resultMsg)) return new HashMap<>();
        Map<String, Map<Integer, List<ScanResult>>> configs =
                (Map<String, Map<Integer, List<ScanResult>>>) resultMsg.obj;
        resultMsg.recycle();
        return configs;
    }

    /**
     * Retrieve a list of {@link OsuProvider} associated with the given list of ScanResult
     * synchronously.
     *
     * @param scanResults a list of ScanResult that has Passpoint APs.
     * @param channel     Channel for communicating with the state machine
     * @return Map that consists of {@link OsuProvider} and a matching list of {@link ScanResult}.
     */
    @NonNull
    public Map<OsuProvider, List<ScanResult>> syncGetMatchingOsuProviders(
            List<ScanResult> scanResults,
            AsyncChannel channel) {
        Message resultMsg =
                channel.sendMessageSynchronously(CMD_GET_MATCHING_OSU_PROVIDERS, scanResults);
        if (messageIsNull(resultMsg)) return new HashMap<>();
        Map<OsuProvider, List<ScanResult>> providers =
                (Map<OsuProvider, List<ScanResult>>) resultMsg.obj;
        resultMsg.recycle();
        return providers;
    }

    /**
     * Returns the matching Passpoint configurations for given OSU(Online Sign-Up) Providers
     *
     * @param osuProviders a list of {@link OsuProvider}
     * @param channel  AsyncChannel to use for the response
     * @return Map that consists of {@link OsuProvider} and matching {@link PasspointConfiguration}.
     */
    @NonNull
    public Map<OsuProvider, PasspointConfiguration> syncGetMatchingPasspointConfigsForOsuProviders(
            List<OsuProvider> osuProviders, AsyncChannel channel) {
        Message resultMsg =
                channel.sendMessageSynchronously(
                        CMD_GET_MATCHING_PASSPOINT_CONFIGS_FOR_OSU_PROVIDERS, osuProviders);
        if (messageIsNull(resultMsg)) return new HashMap<>();
        Map<OsuProvider, PasspointConfiguration> result =
                (Map<OsuProvider, PasspointConfiguration>) resultMsg.obj;
        resultMsg.recycle();
        return result;
    }

    /**
     * Returns the corresponding wifi configurations for given FQDN (Fully Qualified Domain Name)
     * list.
     *
     * An empty list will be returned when no match is found.
     *
     * @param fqdnList a list of FQDN
     * @param channel  AsyncChannel to use for the response
     * @return List of {@link WifiConfiguration} converted from
     * {@link com.android.server.wifi.hotspot2.PasspointProvider}
     */
    @NonNull
    public List<WifiConfiguration> syncGetWifiConfigsForPasspointProfiles(List<String> fqdnList,
            AsyncChannel channel) {
        Message resultMsg =
                channel.sendMessageSynchronously(
                        CMD_GET_WIFI_CONFIGS_FOR_PASSPOINT_PROFILES, fqdnList);
        if (messageIsNull(resultMsg)) return new ArrayList<>();
        List<WifiConfiguration> result = (List<WifiConfiguration>) resultMsg.obj;
        resultMsg.recycle();
        return result;
    }

    /**
     * Add or update a Passpoint configuration synchronously.
     *
     * @param channel Channel for communicating with the state machine
     * @param config The configuration to add or update
     * @param packageName Package name of the app adding/updating {@code config}.
     * @return true on success
     */
    public boolean syncAddOrUpdatePasspointConfig(AsyncChannel channel,
            PasspointConfiguration config, int uid, String packageName) {
        Bundle bundle = new Bundle();
        bundle.putInt(EXTRA_UID, uid);
        bundle.putString(EXTRA_PACKAGE_NAME, packageName);
        bundle.putParcelable(EXTRA_PASSPOINT_CONFIGURATION, config);
        Message resultMsg = channel.sendMessageSynchronously(CMD_ADD_OR_UPDATE_PASSPOINT_CONFIG,
                bundle);
        if (messageIsNull(resultMsg)) return false;
        boolean result = (resultMsg.arg1 == SUCCESS);
        resultMsg.recycle();
        return result;
    }

    /**
     * Remove a Passpoint configuration synchronously.
     *
     * @param channel Channel for communicating with the state machine
     * @param fqdn The FQDN of the Passpoint configuration to remove
     * @return true on success
     */
    public boolean syncRemovePasspointConfig(AsyncChannel channel, String fqdn) {
        Message resultMsg = channel.sendMessageSynchronously(CMD_REMOVE_PASSPOINT_CONFIG,
                fqdn);
        if (messageIsNull(resultMsg)) return false;
        boolean result = (resultMsg.arg1 == SUCCESS);
        resultMsg.recycle();
        return result;
    }

    /**
     * Get the list of installed Passpoint configurations synchronously.
     *
     * @param channel Channel for communicating with the state machine
     * @return List of {@link PasspointConfiguration}
     */
    public List<PasspointConfiguration> syncGetPasspointConfigs(AsyncChannel channel) {
        Message resultMsg = channel.sendMessageSynchronously(CMD_GET_PASSPOINT_CONFIGS);
        if (messageIsNull(resultMsg)) return null;
        List<PasspointConfiguration> result = (List<PasspointConfiguration>) resultMsg.obj;
        resultMsg.recycle();
        return result;
    }

    /**
     * Start subscription provisioning synchronously
     *
     * @param provider {@link OsuProvider} the provider to provision with
     * @param callback {@link IProvisioningCallback} callback for provisioning status
     * @return boolean true indicates provisioning was started, false otherwise
     */
    public boolean syncStartSubscriptionProvisioning(int callingUid, OsuProvider provider,
            IProvisioningCallback callback, AsyncChannel channel) {
        Message msg = Message.obtain();
        msg.what = CMD_START_SUBSCRIPTION_PROVISIONING;
        msg.arg1 = callingUid;
        msg.obj = callback;
        msg.getData().putParcelable(EXTRA_OSU_PROVIDER, provider);
        Message resultMsg = channel.sendMessageSynchronously(msg);
        if (messageIsNull(resultMsg)) return false;
        boolean result = resultMsg.arg1 != 0;
        resultMsg.recycle();
        return result;
    }

    /**
     * Get the supported feature set synchronously
     */
    public long syncGetSupportedFeatures(AsyncChannel channel) {
        Message resultMsg = channel.sendMessageSynchronously(CMD_GET_SUPPORTED_FEATURES);
        if (messageIsNull(resultMsg)) return 0;
        long supportedFeatureSet = ((Long) resultMsg.obj).longValue();
        resultMsg.recycle();

        // Mask the feature set against system properties.
        boolean rttSupported = mContext.getPackageManager().hasSystemFeature(
                PackageManager.FEATURE_WIFI_RTT);
        if (!rttSupported) {
            supportedFeatureSet &=
                    ~(WifiManager.WIFI_FEATURE_D2D_RTT | WifiManager.WIFI_FEATURE_D2AP_RTT);
        }

        return supportedFeatureSet;
    }

    /**
     * Get link layers stats for adapter synchronously
     */
    public WifiLinkLayerStats syncGetLinkLayerStats(AsyncChannel channel) {
        Message resultMsg = channel.sendMessageSynchronously(CMD_GET_LINK_LAYER_STATS);
        if (messageIsNull(resultMsg)) return null;
        WifiLinkLayerStats result = (WifiLinkLayerStats) resultMsg.obj;
        resultMsg.recycle();
        return result;
    }

    /**
     * Delete a network
     *
     * @param networkId id of the network to be removed
     */
    public boolean syncRemoveNetwork(AsyncChannel channel, int networkId) {
        Message resultMsg = channel.sendMessageSynchronously(CMD_REMOVE_NETWORK, networkId);
        if (messageIsNull(resultMsg)) return false;
        boolean result = (resultMsg.arg1 != FAILURE);
        resultMsg.recycle();
        return result;
    }

    /**
     * Enable a network
     *
     * @param netId         network id of the network
     * @param disableOthers true, if all other networks have to be disabled
     * @return {@code true} if the operation succeeds, {@code false} otherwise
     */
    public boolean syncEnableNetwork(AsyncChannel channel, int netId, boolean disableOthers) {
        Message resultMsg = channel.sendMessageSynchronously(CMD_ENABLE_NETWORK, netId,
                disableOthers ? 1 : 0);
        if (messageIsNull(resultMsg)) return false;
        boolean result = (resultMsg.arg1 != FAILURE);
        resultMsg.recycle();
        return result;
    }

    /**
     * Disable a network
     *
     * @param netId network id of the network
     * @return {@code true} if the operation succeeds, {@code false} otherwise
     */
    public boolean syncDisableNetwork(AsyncChannel channel, int netId) {
        Message resultMsg = channel.sendMessageSynchronously(WifiManager.DISABLE_NETWORK, netId);
        boolean result = (resultMsg.what != WifiManager.DISABLE_NETWORK_FAILED);
        if (messageIsNull(resultMsg)) return false;
        resultMsg.recycle();
        return result;
    }

    /**
     * Method to enable/disable RSSI polling
     * @param enabled boolean idicating if polling should start
     */
    public void enableRssiPolling(boolean enabled) {
        sendMessage(CMD_ENABLE_RSSI_POLL, enabled ? 1 : 0, 0);
    }

    /**
     * Set high performance mode of operation.
     * Enabling would set active power mode and disable suspend optimizations;
     * disabling would set auto power mode and enable suspend optimizations
     *
     * @param enable true if enable, false otherwise
     */
    public void setHighPerfModeEnabled(boolean enable) {
        sendMessage(CMD_SET_HIGH_PERF_MODE, enable ? 1 : 0, 0);
    }


    /**
     * reset cached SIM credential data
     */
    public synchronized void resetSimAuthNetworks(boolean simPresent) {
        sendMessage(CMD_RESET_SIM_NETWORKS, simPresent ? 1 : 0);
    }

    /**
     * Get Network object of current wifi network
     * @return Network object of current wifi network
     */
    public Network getCurrentNetwork() {
        synchronized (mNetworkAgentLock) {
            if (mNetworkAgent != null) {
                return new Network(mNetworkAgent.netId);
            } else {
                return null;
            }
        }
    }

    /**
     * Enable TDLS for a specific MAC address
     */
    public void enableTdls(String remoteMacAddress, boolean enable) {
        int enabler = enable ? 1 : 0;
        sendMessage(CMD_ENABLE_TDLS, enabler, 0, remoteMacAddress);
    }

    /**
     * Send a message indicating bluetooth adapter connection state changed
     */
    public void sendBluetoothAdapterStateChange(int state) {
        sendMessage(CMD_BLUETOOTH_ADAPTER_STATE_CHANGE, state, 0);
    }

    /**
     * Send a message indicating a package has been uninstalled.
     */
    public void removeAppConfigs(String packageName, int uid) {
        // Build partial AppInfo manually - package may not exist in database any more
        ApplicationInfo ai = new ApplicationInfo();
        ai.packageName = packageName;
        ai.uid = uid;
        sendMessage(CMD_REMOVE_APP_CONFIGURATIONS, ai);
    }

    /**
     * Send a message indicating a user has been removed.
     */
    public void removeUserConfigs(int userId) {
        sendMessage(CMD_REMOVE_USER_CONFIGURATIONS, userId);
    }

    /**
     * Update the BatteryStats WorkSource.
     */
    public void updateBatteryWorkSource(WorkSource newSource) {
        synchronized (mRunningWifiUids) {
            try {
                if (newSource != null) {
                    mRunningWifiUids.set(newSource);
                }
                if (mIsRunning) {
                    if (mReportedRunning) {
                        // If the work source has changed since last time, need
                        // to remove old work from battery stats.
                        if (!mLastRunningWifiUids.equals(mRunningWifiUids)) {
                            mBatteryStats.noteWifiRunningChanged(mLastRunningWifiUids,
                                    mRunningWifiUids);
                            mLastRunningWifiUids.set(mRunningWifiUids);
                        }
                    } else {
                        // Now being started, report it.
                        mBatteryStats.noteWifiRunning(mRunningWifiUids);
                        mLastRunningWifiUids.set(mRunningWifiUids);
                        mReportedRunning = true;
                    }
                } else {
                    if (mReportedRunning) {
                        // Last reported we were running, time to stop.
                        mBatteryStats.noteWifiStopped(mLastRunningWifiUids);
                        mLastRunningWifiUids.clear();
                        mReportedRunning = false;
                    }
                }
                mWakeLock.setWorkSource(newSource);
            } catch (RemoteException ignore) {
            }
        }
    }

    /**
     * Trigger dump on the class IpClient object.
     */
    public void dumpIpClient(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (mIpClient != null) {
            // All dumpIpClient does is print this log message.
            // TODO: consider deleting this, since it's not useful.
            pw.println("IpClient logs have moved to dumpsys network_stack");
        }
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        super.dump(fd, pw, args);
        mSupplicantStateTracker.dump(fd, pw, args);
        pw.println("mLinkProperties " + mLinkProperties);
        pw.println("mWifiInfo " + mWifiInfo);
        pw.println("mDhcpResults " + mDhcpResults);
        pw.println("mNetworkInfo " + mNetworkInfo);
        pw.println("mLastSignalLevel " + mLastSignalLevel);
        pw.println("mLastBssid " + mLastBssid);
        pw.println("mLastNetworkId " + mLastNetworkId);
        pw.println("mOperationalMode " + mOperationalMode);
        pw.println("mUserWantsSuspendOpt " + mUserWantsSuspendOpt);
        pw.println("mSuspendOptNeedsDisabled " + mSuspendOptNeedsDisabled);
        mCountryCode.dump(fd, pw, args);
        mNetworkFactory.dump(fd, pw, args);
        mUntrustedNetworkFactory.dump(fd, pw, args);
        pw.println("Wlan Wake Reasons:" + mWifiNative.getWlanWakeReasonCount());
        pw.println();

        mWifiConfigManager.dump(fd, pw, args);
        pw.println();
        mPasspointManager.dump(pw);
        pw.println();
        mWifiDiagnostics.captureBugReportData(WifiDiagnostics.REPORT_REASON_USER_ACTION);
        mWifiDiagnostics.dump(fd, pw, args);
        dumpIpClient(fd, pw, args);
        mWifiConnectivityManager.dump(fd, pw, args);
        mWifiInjector.getWakeupController().dump(fd, pw, args);
        mLinkProbeManager.dump(fd, pw, args);
        mWifiInjector.getWifiLastResortWatchdog().dump(fd, pw, args);
    }

    /**
     * Trigger message to handle boot completed event.
     */
    public void handleBootCompleted() {
        sendMessage(CMD_BOOT_COMPLETED);
    }

    /**
     * Trigger message to handle user switch event.
     */
    public void handleUserSwitch(int userId) {
        sendMessage(CMD_USER_SWITCH, userId);
    }

    /**
     * Trigger message to handle user unlock event.
     */
    public void handleUserUnlock(int userId) {
        sendMessage(CMD_USER_UNLOCK, userId);
    }

    /**
     * Trigger message to handle user stop event.
     */
    public void handleUserStop(int userId) {
        sendMessage(CMD_USER_STOP, userId);
    }

    /**
     * ******************************************************
     * Internal private functions
     * ******************************************************
     */

    private void logStateAndMessage(Message message, State state) {
        mMessageHandlingStatus = 0;
        if (mVerboseLoggingEnabled) {
            logd(" " + state.getClass().getSimpleName() + " " + getLogRecString(message));
        }
    }

    @Override
    protected boolean recordLogRec(Message msg) {
        switch (msg.what) {
            case CMD_RSSI_POLL:
                return mVerboseLoggingEnabled;
            default:
                return true;
        }
    }

    /**
     * Return the additional string to be logged by LogRec, default
     *
     * @param msg that was processed
     * @return information to be logged as a String
     */
    @Override
    protected String getLogRecString(Message msg) {
        WifiConfiguration config;
        Long now;
        String report;
        String key;
        StringBuilder sb = new StringBuilder();
        sb.append("screen=").append(mScreenOn ? "on" : "off");
        if (mMessageHandlingStatus != MESSAGE_HANDLING_STATUS_UNKNOWN) {
            sb.append("(").append(mMessageHandlingStatus).append(")");
        }
        if (msg.sendingUid > 0 && msg.sendingUid != Process.WIFI_UID) {
            sb.append(" uid=" + msg.sendingUid);
        }
        switch (msg.what) {
            case WifiMonitor.SUPPLICANT_STATE_CHANGE_EVENT:
                sb.append(" ");
                sb.append(Integer.toString(msg.arg1));
                sb.append(" ");
                sb.append(Integer.toString(msg.arg2));
                StateChangeResult stateChangeResult = (StateChangeResult) msg.obj;
                if (stateChangeResult != null) {
                    sb.append(stateChangeResult.toString());
                }
                break;
            case WifiManager.SAVE_NETWORK:
                sb.append(" ");
                sb.append(Integer.toString(msg.arg1));
                sb.append(" ");
                sb.append(Integer.toString(msg.arg2));
                config = (WifiConfiguration) msg.obj;
                if (config != null) {
                    sb.append(" ").append(config.configKey());
                    sb.append(" nid=").append(config.networkId);
                    if (config.hiddenSSID) {
                        sb.append(" hidden");
                    }
                    if (config.preSharedKey != null
                            && !config.preSharedKey.equals("*")) {
                        sb.append(" hasPSK");
                    }
                    if (config.ephemeral) {
                        sb.append(" ephemeral");
                    }
                    if (config.selfAdded) {
                        sb.append(" selfAdded");
                    }
                    sb.append(" cuid=").append(config.creatorUid);
                    sb.append(" suid=").append(config.lastUpdateUid);
                }
                break;
            case WifiManager.FORGET_NETWORK:
                sb.append(" ");
                sb.append(Integer.toString(msg.arg1));
                sb.append(" ");
                sb.append(Integer.toString(msg.arg2));
                config = (WifiConfiguration) msg.obj;
                if (config != null) {
                    sb.append(" ").append(config.configKey());
                    sb.append(" nid=").append(config.networkId);
                    if (config.hiddenSSID) {
                        sb.append(" hidden");
                    }
                    if (config.preSharedKey != null) {
                        sb.append(" hasPSK");
                    }
                    if (config.ephemeral) {
                        sb.append(" ephemeral");
                    }
                    if (config.selfAdded) {
                        sb.append(" selfAdded");
                    }
                    sb.append(" cuid=").append(config.creatorUid);
                    sb.append(" suid=").append(config.lastUpdateUid);
                    WifiConfiguration.NetworkSelectionStatus netWorkSelectionStatus =
                            config.getNetworkSelectionStatus();
                    sb.append(" ajst=").append(
                            netWorkSelectionStatus.getNetworkStatusString());
                }
                break;
            case WifiMonitor.ASSOCIATION_REJECTION_EVENT:
                sb.append(" ");
                sb.append(" timedOut=" + Integer.toString(msg.arg1));
                sb.append(" ");
                sb.append(Integer.toString(msg.arg2));
                String bssid = (String) msg.obj;
                if (bssid != null && bssid.length() > 0) {
                    sb.append(" ");
                    sb.append(bssid);
                }
                sb.append(" blacklist=" + Boolean.toString(mDidBlackListBSSID));
                break;
            case WifiMonitor.NETWORK_CONNECTION_EVENT:
                sb.append(" ");
                sb.append(Integer.toString(msg.arg1));
                sb.append(" ");
                sb.append(Integer.toString(msg.arg2));
                sb.append(" ").append(mLastBssid);
                sb.append(" nid=").append(mLastNetworkId);
                config = getCurrentWifiConfiguration();
                if (config != null) {
                    sb.append(" ").append(config.configKey());
                }
                key = mWifiConfigManager.getLastSelectedNetworkConfigKey();
                if (key != null) {
                    sb.append(" last=").append(key);
                }
                break;
            case CMD_TARGET_BSSID:
            case CMD_ASSOCIATED_BSSID:
                sb.append(" ");
                sb.append(Integer.toString(msg.arg1));
                sb.append(" ");
                sb.append(Integer.toString(msg.arg2));
                if (msg.obj != null) {
                    sb.append(" BSSID=").append((String) msg.obj);
                }
                if (mTargetRoamBSSID != null) {
                    sb.append(" Target=").append(mTargetRoamBSSID);
                }
                sb.append(" roam=").append(Boolean.toString(mIsAutoRoaming));
                break;
            case WifiMonitor.NETWORK_DISCONNECTION_EVENT:
                if (msg.obj != null) {
                    sb.append(" ").append((String) msg.obj);
                }
                sb.append(" nid=").append(msg.arg1);
                sb.append(" reason=").append(msg.arg2);
                if (mLastBssid != null) {
                    sb.append(" lastbssid=").append(mLastBssid);
                }
                if (mWifiInfo.getFrequency() != -1) {
                    sb.append(" freq=").append(mWifiInfo.getFrequency());
                    sb.append(" rssi=").append(mWifiInfo.getRssi());
                }
                break;
            case CMD_RSSI_POLL:
            case CMD_ONESHOT_RSSI_POLL:
            case CMD_UNWANTED_NETWORK:
            case WifiManager.RSSI_PKTCNT_FETCH:
                sb.append(" ");
                sb.append(Integer.toString(msg.arg1));
                sb.append(" ");
                sb.append(Integer.toString(msg.arg2));
                if (mWifiInfo.getSSID() != null) {
                    if (mWifiInfo.getSSID() != null) {
                        sb.append(" ").append(mWifiInfo.getSSID());
                    }
                }
                if (mWifiInfo.getBSSID() != null) {
                    sb.append(" ").append(mWifiInfo.getBSSID());
                }
                sb.append(" rssi=").append(mWifiInfo.getRssi());
                sb.append(" f=").append(mWifiInfo.getFrequency());
                sb.append(" sc=").append(mWifiInfo.score);
                sb.append(" link=").append(mWifiInfo.getLinkSpeed());
                sb.append(String.format(" tx=%.1f,", mWifiInfo.txSuccessRate));
                sb.append(String.format(" %.1f,", mWifiInfo.txRetriesRate));
                sb.append(String.format(" %.1f ", mWifiInfo.txBadRate));
                sb.append(String.format(" rx=%.1f", mWifiInfo.rxSuccessRate));
                sb.append(String.format(" bcn=%d", mRunningBeaconCount));
                report = reportOnTime();
                if (report != null) {
                    sb.append(" ").append(report);
                }
                sb.append(String.format(" score=%d", mWifiInfo.score));
                break;
            case CMD_START_CONNECT:
            case WifiManager.CONNECT_NETWORK:
                sb.append(" ");
                sb.append(Integer.toString(msg.arg1));
                sb.append(" ");
                sb.append(Integer.toString(msg.arg2));
                config = mWifiConfigManager.getConfiguredNetwork(msg.arg1);
                if (config != null) {
                    sb.append(" ").append(config.configKey());
                }
                if (mTargetRoamBSSID != null) {
                    sb.append(" ").append(mTargetRoamBSSID);
                }
                sb.append(" roam=").append(Boolean.toString(mIsAutoRoaming));
                config = getCurrentWifiConfiguration();
                if (config != null) {
                    sb.append(config.configKey());
                }
                break;
            case CMD_START_ROAM:
                sb.append(" ");
                sb.append(Integer.toString(msg.arg1));
                sb.append(" ");
                sb.append(Integer.toString(msg.arg2));
                ScanResult result = (ScanResult) msg.obj;
                if (result != null) {
                    now = mClock.getWallClockMillis();
                    sb.append(" bssid=").append(result.BSSID);
                    sb.append(" rssi=").append(result.level);
                    sb.append(" freq=").append(result.frequency);
                    if (result.seen > 0 && result.seen < now) {
                        sb.append(" seen=").append(now - result.seen);
                    } else {
                        // Somehow the timestamp for this scan result is inconsistent
                        sb.append(" !seen=").append(result.seen);
                    }
                }
                if (mTargetRoamBSSID != null) {
                    sb.append(" ").append(mTargetRoamBSSID);
                }
                sb.append(" roam=").append(Boolean.toString(mIsAutoRoaming));
                sb.append(" fail count=").append(Integer.toString(mRoamFailCount));
                break;
            case CMD_ADD_OR_UPDATE_NETWORK:
                sb.append(" ");
                sb.append(Integer.toString(msg.arg1));
                sb.append(" ");
                sb.append(Integer.toString(msg.arg2));
                if (msg.obj != null) {
                    config = (WifiConfiguration) msg.obj;
                    sb.append(" ").append(config.configKey());
                    sb.append(" prio=").append(config.priority);
                    sb.append(" status=").append(config.status);
                    if (config.BSSID != null) {
                        sb.append(" ").append(config.BSSID);
                    }
                    WifiConfiguration curConfig = getCurrentWifiConfiguration();
                    if (curConfig != null) {
                        if (curConfig.configKey().equals(config.configKey())) {
                            sb.append(" is current");
                        } else {
                            sb.append(" current=").append(curConfig.configKey());
                            sb.append(" prio=").append(curConfig.priority);
                            sb.append(" status=").append(curConfig.status);
                        }
                    }
                }
                break;
            case WifiManager.DISABLE_NETWORK:
            case CMD_ENABLE_NETWORK:
                sb.append(" ");
                sb.append(Integer.toString(msg.arg1));
                sb.append(" ");
                sb.append(Integer.toString(msg.arg2));
                key = mWifiConfigManager.getLastSelectedNetworkConfigKey();
                if (key != null) {
                    sb.append(" last=").append(key);
                }
                config = mWifiConfigManager.getConfiguredNetwork(msg.arg1);
                if (config != null && (key == null || !config.configKey().equals(key))) {
                    sb.append(" target=").append(key);
                }
                break;
            case CMD_GET_CONFIGURED_NETWORKS:
                sb.append(" ");
                sb.append(Integer.toString(msg.arg1));
                sb.append(" ");
                sb.append(Integer.toString(msg.arg2));
                sb.append(" num=").append(mWifiConfigManager.getConfiguredNetworks().size());
                break;
            case CMD_PRE_DHCP_ACTION:
                sb.append(" ");
                sb.append(Integer.toString(msg.arg1));
                sb.append(" ");
                sb.append(Integer.toString(msg.arg2));
                sb.append(" txpkts=").append(mWifiInfo.txSuccess);
                sb.append(",").append(mWifiInfo.txBad);
                sb.append(",").append(mWifiInfo.txRetries);
                break;
            case CMD_POST_DHCP_ACTION:
                if (mLinkProperties != null) {
                    sb.append(" ");
                    sb.append(getLinkPropertiesSummary(mLinkProperties));
                }
                break;
            case WifiP2pServiceImpl.P2P_CONNECTION_CHANGED:
                sb.append(" ");
                sb.append(Integer.toString(msg.arg1));
                sb.append(" ");
                sb.append(Integer.toString(msg.arg2));
                if (msg.obj != null) {
                    NetworkInfo info = (NetworkInfo) msg.obj;
                    NetworkInfo.State state = info.getState();
                    NetworkInfo.DetailedState detailedState = info.getDetailedState();
                    if (state != null) {
                        sb.append(" st=").append(state);
                    }
                    if (detailedState != null) {
                        sb.append("/").append(detailedState);
                    }
                }
                break;
            case CMD_IP_CONFIGURATION_LOST:
                int count = -1;
                WifiConfiguration c = getCurrentWifiConfiguration();
                if (c != null) {
                    count = c.getNetworkSelectionStatus().getDisableReasonCounter(
                            WifiConfiguration.NetworkSelectionStatus.DISABLED_DHCP_FAILURE);
                }
                sb.append(" ");
                sb.append(Integer.toString(msg.arg1));
                sb.append(" ");
                sb.append(Integer.toString(msg.arg2));
                sb.append(" failures: ");
                sb.append(Integer.toString(count));
                sb.append("/");
                sb.append(Integer.toString(mFacade.getIntegerSetting(
                        mContext, Settings.Global.WIFI_MAX_DHCP_RETRY_COUNT, 0)));
                if (mWifiInfo.getBSSID() != null) {
                    sb.append(" ").append(mWifiInfo.getBSSID());
                }
                sb.append(String.format(" bcn=%d", mRunningBeaconCount));
                break;
            case CMD_UPDATE_LINKPROPERTIES:
                sb.append(" ");
                sb.append(Integer.toString(msg.arg1));
                sb.append(" ");
                sb.append(Integer.toString(msg.arg2));
                if (mLinkProperties != null) {
                    sb.append(" ");
                    sb.append(getLinkPropertiesSummary(mLinkProperties));
                }
                break;
            case CMD_IP_REACHABILITY_LOST:
                if (msg.obj != null) {
                    sb.append(" ").append((String) msg.obj);
                }
                break;
            case CMD_INSTALL_PACKET_FILTER:
                sb.append(" len=" + ((byte[]) msg.obj).length);
                break;
            case CMD_SET_FALLBACK_PACKET_FILTERING:
                sb.append(" enabled=" + (boolean) msg.obj);
                break;
            case CMD_ROAM_WATCHDOG_TIMER:
                sb.append(" ");
                sb.append(Integer.toString(msg.arg1));
                sb.append(" ");
                sb.append(Integer.toString(msg.arg2));
                sb.append(" cur=").append(mRoamWatchdogCount);
                break;
            case CMD_DISCONNECTING_WATCHDOG_TIMER:
                sb.append(" ");
                sb.append(Integer.toString(msg.arg1));
                sb.append(" ");
                sb.append(Integer.toString(msg.arg2));
                sb.append(" cur=").append(mDisconnectingWatchdogCount);
                break;
            case CMD_START_RSSI_MONITORING_OFFLOAD:
            case CMD_STOP_RSSI_MONITORING_OFFLOAD:
            case CMD_RSSI_THRESHOLD_BREACHED:
                sb.append(" rssi=");
                sb.append(Integer.toString(msg.arg1));
                sb.append(" thresholds=");
                sb.append(Arrays.toString(mRssiRanges));
                break;
            case CMD_USER_SWITCH:
                sb.append(" userId=");
                sb.append(Integer.toString(msg.arg1));
                break;
            case CMD_IPV4_PROVISIONING_SUCCESS:
                sb.append(" ");
                sb.append(/* DhcpResults */ msg.obj);
                break;
            default:
                sb.append(" ");
                sb.append(Integer.toString(msg.arg1));
                sb.append(" ");
                sb.append(Integer.toString(msg.arg2));
                break;
        }

        return sb.toString();
    }

    @Override
    protected String getWhatToString(int what) {
        String s = sGetWhatToString.get(what);
        if (s != null) {
            return s;
        }
        switch (what) {
            case AsyncChannel.CMD_CHANNEL_HALF_CONNECTED:
                s = "CMD_CHANNEL_HALF_CONNECTED";
                break;
            case AsyncChannel.CMD_CHANNEL_DISCONNECTED:
                s = "CMD_CHANNEL_DISCONNECTED";
                break;
            case WifiManager.DISABLE_NETWORK:
                s = "DISABLE_NETWORK";
                break;
            case WifiManager.CONNECT_NETWORK:
                s = "CONNECT_NETWORK";
                break;
            case WifiManager.SAVE_NETWORK:
                s = "SAVE_NETWORK";
                break;
            case WifiManager.FORGET_NETWORK:
                s = "FORGET_NETWORK";
                break;
            case WifiMonitor.SUPPLICANT_STATE_CHANGE_EVENT:
                s = "SUPPLICANT_STATE_CHANGE_EVENT";
                break;
            case WifiMonitor.AUTHENTICATION_FAILURE_EVENT:
                s = "AUTHENTICATION_FAILURE_EVENT";
                break;
            case WifiMonitor.SUP_REQUEST_IDENTITY:
                s = "SUP_REQUEST_IDENTITY";
                break;
            case WifiMonitor.NETWORK_CONNECTION_EVENT:
                s = "NETWORK_CONNECTION_EVENT";
                break;
            case WifiMonitor.NETWORK_DISCONNECTION_EVENT:
                s = "NETWORK_DISCONNECTION_EVENT";
                break;
            case WifiMonitor.ASSOCIATION_REJECTION_EVENT:
                s = "ASSOCIATION_REJECTION_EVENT";
                break;
            case WifiMonitor.ANQP_DONE_EVENT:
                s = "ANQP_DONE_EVENT";
                break;
            case WifiMonitor.RX_HS20_ANQP_ICON_EVENT:
                s = "RX_HS20_ANQP_ICON_EVENT";
                break;
            case WifiMonitor.GAS_QUERY_DONE_EVENT:
                s = "GAS_QUERY_DONE_EVENT";
                break;
            case WifiMonitor.HS20_REMEDIATION_EVENT:
                s = "HS20_REMEDIATION_EVENT";
                break;
            case WifiMonitor.GAS_QUERY_START_EVENT:
                s = "GAS_QUERY_START_EVENT";
                break;
            case WifiP2pServiceImpl.GROUP_CREATING_TIMED_OUT:
                s = "GROUP_CREATING_TIMED_OUT";
                break;
            case WifiP2pServiceImpl.P2P_CONNECTION_CHANGED:
                s = "P2P_CONNECTION_CHANGED";
                break;
            case WifiP2pServiceImpl.DISCONNECT_WIFI_REQUEST:
                s = "DISCONNECT_WIFI_REQUEST";
                break;
            case WifiP2pServiceImpl.DISCONNECT_WIFI_RESPONSE:
                s = "DISCONNECT_WIFI_RESPONSE";
                break;
            case WifiP2pServiceImpl.SET_MIRACAST_MODE:
                s = "SET_MIRACAST_MODE";
                break;
            case WifiP2pServiceImpl.BLOCK_DISCOVERY:
                s = "BLOCK_DISCOVERY";
                break;
            case WifiManager.RSSI_PKTCNT_FETCH:
                s = "RSSI_PKTCNT_FETCH";
                break;
            default:
                s = "what:" + Integer.toString(what);
                break;
        }
        return s;
    }

    private void handleScreenStateChanged(boolean screenOn) {
        mScreenOn = screenOn;
        if (mVerboseLoggingEnabled) {
            logd(" handleScreenStateChanged Enter: screenOn=" + screenOn
                    + " mUserWantsSuspendOpt=" + mUserWantsSuspendOpt
                    + " state " + getCurrentState().getName()
                    + " suppState:" + mSupplicantStateTracker.getSupplicantStateName());
        }
        enableRssiPolling(screenOn);
        if (mUserWantsSuspendOpt.get()) {
            int shouldReleaseWakeLock = 0;
            if (screenOn) {
                sendMessage(CMD_SET_SUSPEND_OPT_ENABLED, 0, shouldReleaseWakeLock);
            } else {
                if (isConnected()) {
                    // Allow 2s for suspend optimizations to be set
                    mSuspendWakeLock.acquire(2000);
                    shouldReleaseWakeLock = 1;
                }
                sendMessage(CMD_SET_SUSPEND_OPT_ENABLED, 1, shouldReleaseWakeLock);
            }
        }

        getWifiLinkLayerStats();
        mOnTimeScreenStateChange = mOnTime;
        mLastScreenStateChangeTimeStamp = mLastLinkLayerStatsUpdate;

        mWifiMetrics.setScreenState(screenOn);

        mWifiConnectivityManager.handleScreenStateChanged(screenOn);
        mNetworkFactory.handleScreenStateChanged(screenOn);

        WifiLockManager wifiLockManager = mWifiInjector.getWifiLockManager();
        if (wifiLockManager == null) {
            Log.w(TAG, "WifiLockManager not initialized, skipping screen state notification");
        } else {
            wifiLockManager.handleScreenStateChanged(screenOn);
        }

        mSarManager.handleScreenStateChanged(screenOn);

        if (mVerboseLoggingEnabled) log("handleScreenStateChanged Exit: " + screenOn);
    }

    private boolean checkAndSetConnectivityInstance() {
        if (mCm == null) {
            mCm = (ConnectivityManager) mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        }
        if (mCm == null) {
            Log.e(TAG, "Cannot retrieve connectivity service");
            return false;
        }
        return true;
    }

    private void setSuspendOptimizationsNative(int reason, boolean enabled) {
        if (mVerboseLoggingEnabled) {
            log("setSuspendOptimizationsNative: " + reason + " " + enabled
                    + " -want " + mUserWantsSuspendOpt.get()
                    + " stack:" + Thread.currentThread().getStackTrace()[2].getMethodName()
                    + " - " + Thread.currentThread().getStackTrace()[3].getMethodName()
                    + " - " + Thread.currentThread().getStackTrace()[4].getMethodName()
                    + " - " + Thread.currentThread().getStackTrace()[5].getMethodName());
        }
        //mWifiNative.setSuspendOptimizations(enabled);

        if (enabled) {
            mSuspendOptNeedsDisabled &= ~reason;
            /* None of dhcp, screen or highperf need it disabled and user wants it enabled */
            if (mSuspendOptNeedsDisabled == 0 && mUserWantsSuspendOpt.get()) {
                if (mVerboseLoggingEnabled) {
                    log("setSuspendOptimizationsNative do it " + reason + " " + enabled
                            + " stack:" + Thread.currentThread().getStackTrace()[2].getMethodName()
                            + " - " + Thread.currentThread().getStackTrace()[3].getMethodName()
                            + " - " + Thread.currentThread().getStackTrace()[4].getMethodName()
                            + " - " + Thread.currentThread().getStackTrace()[5].getMethodName());
                }
                mWifiNative.setSuspendOptimizations(mInterfaceName, true);
            }
        } else {
            mSuspendOptNeedsDisabled |= reason;
            mWifiNative.setSuspendOptimizations(mInterfaceName, false);
        }
    }

    /**
     * Makes a record of the user intent about suspend optimizations.
     */
    private void setSuspendOptimizations(int reason, boolean enabled) {
        if (mVerboseLoggingEnabled) log("setSuspendOptimizations: " + reason + " " + enabled);
        if (enabled) {
            mSuspendOptNeedsDisabled &= ~reason;
        } else {
            mSuspendOptNeedsDisabled |= reason;
        }
        if (mVerboseLoggingEnabled) log("mSuspendOptNeedsDisabled " + mSuspendOptNeedsDisabled);
    }

    /*
     * Fetch RSSI, linkspeed, and frequency on current connection
     */
    private void fetchRssiLinkSpeedAndFrequencyNative() {
        WifiNative.SignalPollResult pollResult = mWifiNative.signalPoll(mInterfaceName);
        if (pollResult == null) {
            return;
        }

        int newRssi = pollResult.currentRssi;
        int newTxLinkSpeed = pollResult.txBitrate;
        int newFrequency = pollResult.associationFrequency;
        int newRxLinkSpeed = pollResult.rxBitrate;

        if (mVerboseLoggingEnabled) {
            logd("fetchRssiLinkSpeedAndFrequencyNative rssi=" + newRssi
                    + " TxLinkspeed=" + newTxLinkSpeed + " freq=" + newFrequency
                    + " RxLinkSpeed=" + newRxLinkSpeed);
        }

        if (newRssi > WifiInfo.INVALID_RSSI && newRssi < WifiInfo.MAX_RSSI) {
            // screen out invalid values
            /* some implementations avoid negative values by adding 256
             * so we need to adjust for that here.
             */
            if (newRssi > 0) {
                Log.wtf(TAG, "Error! +ve value RSSI: " + newRssi);
                newRssi -= 256;
            }
            mWifiInfo.setRssi(newRssi);
            /*
             * Rather then sending the raw RSSI out every time it
             * changes, we precalculate the signal level that would
             * be displayed in the status bar, and only send the
             * broadcast if that much more coarse-grained number
             * changes. This cuts down greatly on the number of
             * broadcasts, at the cost of not informing others
             * interested in RSSI of all the changes in signal
             * level.
             */
            int newSignalLevel = WifiManager.calculateSignalLevel(newRssi, WifiManager.RSSI_LEVELS);
            if (newSignalLevel != mLastSignalLevel) {
                updateCapabilities();
                sendRssiChangeBroadcast(newRssi);
            }
            mLastSignalLevel = newSignalLevel;
        } else {
            mWifiInfo.setRssi(WifiInfo.INVALID_RSSI);
            updateCapabilities();
        }
        /*
         * set Tx link speed only if it is valid
         */
        if (newTxLinkSpeed > 0) {
            mWifiInfo.setLinkSpeed(newTxLinkSpeed);
            mWifiInfo.setTxLinkSpeedMbps(newTxLinkSpeed);
        }
        /*
         * set Rx link speed only if it is valid
         */
        if (newRxLinkSpeed > 0) {
            mWifiInfo.setRxLinkSpeedMbps(newRxLinkSpeed);
        }
        if (newFrequency > 0) {
            mWifiInfo.setFrequency(newFrequency);
        }
        mWifiConfigManager.updateScanDetailCacheFromWifiInfo(mWifiInfo);
        /*
         * Increment various performance metrics
         */
        mWifiMetrics.handlePollResult(mWifiInfo);
    }

    // Polling has completed, hence we won't have a score anymore
    private void cleanWifiScore() {
        mWifiInfo.txBadRate = 0;
        mWifiInfo.txSuccessRate = 0;
        mWifiInfo.txRetriesRate = 0;
        mWifiInfo.rxSuccessRate = 0;
        mWifiScoreReport.reset();
        mLastLinkLayerStats = null;
    }

    private void updateLinkProperties(LinkProperties newLp) {
        if (mVerboseLoggingEnabled) {
            log("Link configuration changed for netId: " + mLastNetworkId
                    + " old: " + mLinkProperties + " new: " + newLp);
        }
        // We own this instance of LinkProperties because IpClient passes us a copy.
        mLinkProperties = newLp;
        if (mNetworkAgent != null) {
            mNetworkAgent.sendLinkProperties(mLinkProperties);
        }

        if (getNetworkDetailedState() == DetailedState.CONNECTED) {
            // If anything has changed and we're already connected, send out a notification.
            // TODO: Update all callers to use NetworkCallbacks and delete this.
            sendLinkConfigurationChangedBroadcast();
        }

        if (mVerboseLoggingEnabled) {
            StringBuilder sb = new StringBuilder();
            sb.append("updateLinkProperties nid: " + mLastNetworkId);
            sb.append(" state: " + getNetworkDetailedState());

            if (mLinkProperties != null) {
                sb.append(" ");
                sb.append(getLinkPropertiesSummary(mLinkProperties));
            }
            logd(sb.toString());
        }
    }

    /**
     * Clears all our link properties.
     */
    private void clearLinkProperties() {
        // Clear the link properties obtained from DHCP. The only caller of this
        // function has already called IpClient#stop(), which clears its state.
        synchronized (mDhcpResultsLock) {
            if (mDhcpResults != null) {
                mDhcpResults.clear();
            }
        }

        // Now clear the merged link properties.
        mLinkProperties.clear();
        if (mNetworkAgent != null) mNetworkAgent.sendLinkProperties(mLinkProperties);
    }

    private void sendRssiChangeBroadcast(final int newRssi) {
        try {
            mBatteryStats.noteWifiRssiChanged(newRssi);
        } catch (RemoteException e) {
            // Won't happen.
        }
        StatsLog.write(StatsLog.WIFI_SIGNAL_STRENGTH_CHANGED,
                WifiManager.calculateSignalLevel(newRssi, WifiManager.RSSI_LEVELS));

        Intent intent = new Intent(WifiManager.RSSI_CHANGED_ACTION);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        intent.putExtra(WifiManager.EXTRA_NEW_RSSI, newRssi);
        mContext.sendBroadcastAsUser(intent, UserHandle.ALL,
                android.Manifest.permission.ACCESS_WIFI_STATE);
    }

    private void sendNetworkStateChangeBroadcast(String bssid) {
        Intent intent = new Intent(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        NetworkInfo networkInfo = new NetworkInfo(mNetworkInfo);
        networkInfo.setExtraInfo(null);
        intent.putExtra(WifiManager.EXTRA_NETWORK_INFO, networkInfo);
        //TODO(b/69974497) This should be non-sticky, but settings needs fixing first.
        mContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
    }

    private void sendLinkConfigurationChangedBroadcast() {
        Intent intent = new Intent(WifiManager.LINK_CONFIGURATION_CHANGED_ACTION);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        intent.putExtra(WifiManager.EXTRA_LINK_PROPERTIES, new LinkProperties(mLinkProperties));
        mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
    }

    /**
     * Helper method used to send state about supplicant - This is NOT information about the current
     * wifi connection state.
     *
     * TODO: b/79504296 This broadcast has been deprecated and should be removed
     */
    private void sendSupplicantConnectionChangedBroadcast(boolean connected) {
        Intent intent = new Intent(WifiManager.SUPPLICANT_CONNECTION_CHANGE_ACTION);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        intent.putExtra(WifiManager.EXTRA_SUPPLICANT_CONNECTED, connected);
        mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
    }

    /**
     * Record the detailed state of a network.
     *
     * @param state the new {@code DetailedState}
     */
    private boolean setNetworkDetailedState(NetworkInfo.DetailedState state) {
        boolean hidden = false;

        if (mIsAutoRoaming) {
            // There is generally a confusion in the system about colluding
            // WiFi Layer 2 state (as reported by supplicant) and the Network state
            // which leads to multiple confusion.
            //
            // If link is roaming, we already have an IP address
            // as well we were connected and are doing L2 cycles of
            // reconnecting or renewing IP address to check that we still have it
            // This L2 link flapping should ne be reflected into the Network state
            // which is the state of the WiFi Network visible to Layer 3 and applications
            // Note that once roaming is completed, we will
            // set the Network state to where it should be, or leave it as unchanged
            //
            hidden = true;
        }
        if (mVerboseLoggingEnabled) {
            log("setDetailed state, old ="
                    + mNetworkInfo.getDetailedState() + " and new state=" + state
                    + " hidden=" + hidden);
        }
        if (hidden) {
            return false;
        }

        if (state != mNetworkInfo.getDetailedState()) {
            mNetworkInfo.setDetailedState(state, null, null);
            if (mNetworkAgent != null) {
                mNetworkAgent.sendNetworkInfo(mNetworkInfo);
            }
            sendNetworkStateChangeBroadcast(null);
            return true;
        }
        return false;
    }

    private DetailedState getNetworkDetailedState() {
        return mNetworkInfo.getDetailedState();
    }

    private SupplicantState handleSupplicantStateChange(Message message) {
        StateChangeResult stateChangeResult = (StateChangeResult) message.obj;
        SupplicantState state = stateChangeResult.state;
        mWifiScoreCard.noteSupplicantStateChanging(mWifiInfo, state);
        // Supplicant state change
        // [31-13] Reserved for future use
        // [8 - 0] Supplicant state (as defined in SupplicantState.java)
        // 50023 supplicant_state_changed (custom|1|5)
        mWifiInfo.setSupplicantState(state);
        // Network id and SSID are only valid when we start connecting
        if (SupplicantState.isConnecting(state)) {
            mWifiInfo.setNetworkId(stateChangeResult.networkId);
            mWifiInfo.setBSSID(stateChangeResult.BSSID);
            mWifiInfo.setSSID(stateChangeResult.wifiSsid);
        } else {
            // Reset parameters according to WifiInfo.reset()
            mWifiInfo.setNetworkId(WifiConfiguration.INVALID_NETWORK_ID);
            mWifiInfo.setBSSID(null);
            mWifiInfo.setSSID(null);
        }
        updateL2KeyAndGroupHint();
        // SSID might have been updated, so call updateCapabilities
        updateCapabilities();

        final WifiConfiguration config = getCurrentWifiConfiguration();
        if (config != null) {
            mWifiInfo.setEphemeral(config.ephemeral);
            mWifiInfo.setTrusted(config.trusted);
            mWifiInfo.setOsuAp(config.osu);
            if (config.fromWifiNetworkSpecifier || config.fromWifiNetworkSuggestion) {
                mWifiInfo.setNetworkSuggestionOrSpecifierPackageName(config.creatorName);
            }

            // Set meteredHint if scan result says network is expensive
            ScanDetailCache scanDetailCache = mWifiConfigManager.getScanDetailCacheForNetwork(
                    config.networkId);
            if (scanDetailCache != null) {
                ScanDetail scanDetail = scanDetailCache.getScanDetail(stateChangeResult.BSSID);
                if (scanDetail != null) {
                    mWifiInfo.setFrequency(scanDetail.getScanResult().frequency);
                    NetworkDetail networkDetail = scanDetail.getNetworkDetail();
                    if (networkDetail != null
                            && networkDetail.getAnt() == NetworkDetail.Ant.ChargeablePublic) {
                        mWifiInfo.setMeteredHint(true);
                    }
                }
            }
        }

        mSupplicantStateTracker.sendMessage(Message.obtain(message));
        mWifiScoreCard.noteSupplicantStateChanged(mWifiInfo);
        return state;
    }

    /**
     * Tells IpClient what L2Key and GroupHint to use for IpMemoryStore.
     */
    private void updateL2KeyAndGroupHint() {
        if (mIpClient != null) {
            Pair<String, String> p = mWifiScoreCard.getL2KeyAndGroupHint(mWifiInfo);
            if (!p.equals(mLastL2KeyAndGroupHint)) {
                if (mIpClient.setL2KeyAndGroupHint(p.first, p.second)) {
                    mLastL2KeyAndGroupHint = p;
                } else {
                    mLastL2KeyAndGroupHint = null;
                }
            }
        }
    }
    private @Nullable Pair<String, String> mLastL2KeyAndGroupHint = null;

    /**
     * Resets the Wi-Fi Connections by clearing any state, resetting any sockets
     * using the interface, stopping DHCP & disabling interface
     */
    private void handleNetworkDisconnect() {
        if (mVerboseLoggingEnabled) {
            log("handleNetworkDisconnect: Stopping DHCP and clearing IP"
                    + " stack:" + Thread.currentThread().getStackTrace()[2].getMethodName()
                    + " - " + Thread.currentThread().getStackTrace()[3].getMethodName()
                    + " - " + Thread.currentThread().getStackTrace()[4].getMethodName()
                    + " - " + Thread.currentThread().getStackTrace()[5].getMethodName());
        }

        WifiConfiguration wifiConfig = getCurrentWifiConfiguration();
        if (wifiConfig != null) {
            ScanResultMatchInfo matchInfo = ScanResultMatchInfo.fromWifiConfiguration(wifiConfig);
            mWifiInjector.getWakeupController().setLastDisconnectInfo(matchInfo);
            mWifiNetworkSuggestionsManager.handleDisconnect(wifiConfig, getCurrentBSSID());
        }

        stopRssiMonitoringOffload();

        clearTargetBssid("handleNetworkDisconnect");

        stopIpClient();

        /* Reset data structures */
        mWifiScoreReport.reset();
        mWifiInfo.reset();
        /* Reset roaming parameters */
        mIsAutoRoaming = false;

        setNetworkDetailedState(DetailedState.DISCONNECTED);
        synchronized (mNetworkAgentLock) {
            if (mNetworkAgent != null) {
                mNetworkAgent.sendNetworkInfo(mNetworkInfo);
                mNetworkAgent = null;
            }
        }

        /* Clear network properties */
        clearLinkProperties();

        /* Cend event to CM & network change broadcast */
        sendNetworkStateChangeBroadcast(mLastBssid);

        mLastBssid = null;
        mLastLinkLayerStats = null;
        registerDisconnected();
        mLastNetworkId = WifiConfiguration.INVALID_NETWORK_ID;
        mWifiScoreCard.resetConnectionState();
        updateL2KeyAndGroupHint();
    }

    void handlePreDhcpSetup() {
        if (!mBluetoothConnectionActive) {
            /*
             * There are problems setting the Wi-Fi driver's power
             * mode to active when bluetooth coexistence mode is
             * enabled or sense.
             * <p>
             * We set Wi-Fi to active mode when
             * obtaining an IP address because we've found
             * compatibility issues with some routers with low power
             * mode.
             * <p>
             * In order for this active power mode to properly be set,
             * we disable coexistence mode until we're done with
             * obtaining an IP address.  One exception is if we
             * are currently connected to a headset, since disabling
             * coexistence would interrupt that connection.
             */
            // Disable the coexistence mode
            mWifiNative.setBluetoothCoexistenceMode(
                    mInterfaceName, WifiNative.BLUETOOTH_COEXISTENCE_MODE_DISABLED);
        }

        // Disable power save and suspend optimizations during DHCP
        // Note: The order here is important for now. Brcm driver changes
        // power settings when we control suspend mode optimizations.
        // TODO: Remove this comment when the driver is fixed.
        setSuspendOptimizationsNative(SUSPEND_DUE_TO_DHCP, false);
        setPowerSave(false);

        // Update link layer stats
        getWifiLinkLayerStats();

        if (mWifiP2pChannel != null) {
            /* P2p discovery breaks dhcp, shut it down in order to get through this */
            Message msg = new Message();
            msg.what = WifiP2pServiceImpl.BLOCK_DISCOVERY;
            msg.arg1 = WifiP2pServiceImpl.ENABLED;
            msg.arg2 = CMD_PRE_DHCP_ACTION_COMPLETE;
            msg.obj = ClientModeImpl.this;
            mWifiP2pChannel.sendMessage(msg);
        } else {
            // If the p2p service is not running, we can proceed directly.
            sendMessage(CMD_PRE_DHCP_ACTION_COMPLETE);
        }
    }

    void handlePostDhcpSetup() {
        /* Restore power save and suspend optimizations */
        setSuspendOptimizationsNative(SUSPEND_DUE_TO_DHCP, true);
        setPowerSave(true);

        p2pSendMessage(WifiP2pServiceImpl.BLOCK_DISCOVERY, WifiP2pServiceImpl.DISABLED);

        // Set the coexistence mode back to its default value
        mWifiNative.setBluetoothCoexistenceMode(
                mInterfaceName, WifiNative.BLUETOOTH_COEXISTENCE_MODE_SENSE);
    }

    /**
     * Set power save mode
     *
     * @param ps true to enable power save (default behavior)
     *           false to disable power save.
     * @return true for success, false for failure
     */
    public boolean setPowerSave(boolean ps) {
        if (mInterfaceName != null) {
            if (mVerboseLoggingEnabled) {
                Log.d(TAG, "Setting power save for: " + mInterfaceName + " to: " + ps);
            }
            mWifiNative.setPowerSave(mInterfaceName, ps);
        } else {
            Log.e(TAG, "Failed to setPowerSave, interfaceName is null");
            return false;
        }
        return true;
    }

    /**
     * Set low latency mode
     *
     * @param enabled true to enable low latency
     *                false to disable low latency (default behavior).
     * @return true for success, false for failure
     */
    public boolean setLowLatencyMode(boolean enabled) {
        if (mVerboseLoggingEnabled) {
            Log.d(TAG, "Setting low latency mode to " + enabled);
        }
        if (!mWifiNative.setLowLatencyMode(enabled)) {
            Log.e(TAG, "Failed to setLowLatencyMode");
            return false;
        }
        return true;
    }

    @VisibleForTesting
    public static final long DIAGS_CONNECT_TIMEOUT_MILLIS = 60 * 1000;
    /**
     * Inform other components that a new connection attempt is starting.
     */
    private void reportConnectionAttemptStart(
            WifiConfiguration config, String targetBSSID, int roamType) {
        mWifiMetrics.startConnectionEvent(config, targetBSSID, roamType);
        mWifiDiagnostics.reportConnectionEvent(WifiDiagnostics.CONNECTION_EVENT_STARTED);
        mWrongPasswordNotifier.onNewConnectionAttempt();
        removeMessages(CMD_DIAGS_CONNECT_TIMEOUT);
        sendMessageDelayed(CMD_DIAGS_CONNECT_TIMEOUT, DIAGS_CONNECT_TIMEOUT_MILLIS);
    }

    private void handleConnectionAttemptEndForDiagnostics(int level2FailureCode) {
        switch (level2FailureCode) {
            case WifiMetrics.ConnectionEvent.FAILURE_NONE:
                break;
            case WifiMetrics.ConnectionEvent.FAILURE_CONNECT_NETWORK_FAILED:
                // WifiDiagnostics doesn't care about pre-empted connections, or cases
                // where we failed to initiate a connection attempt with supplicant.
                break;
            default:
                removeMessages(CMD_DIAGS_CONNECT_TIMEOUT);
                mWifiDiagnostics.reportConnectionEvent(WifiDiagnostics.CONNECTION_EVENT_FAILED);
        }
    }

    /**
     * Inform other components (WifiMetrics, WifiDiagnostics, WifiConnectivityManager, etc.) that
     * the current connection attempt has concluded.
     */
    private void reportConnectionAttemptEnd(int level2FailureCode, int connectivityFailureCode,
            int level2FailureReason) {
        if (level2FailureCode != WifiMetrics.ConnectionEvent.FAILURE_NONE) {
            mWifiScoreCard.noteConnectionFailure(mWifiInfo,
                    level2FailureCode, connectivityFailureCode);
        }
        // if connected, this should be non-null.
        WifiConfiguration configuration = getCurrentWifiConfiguration();
        if (configuration == null) {
            // If not connected, this should be non-null.
            configuration = getTargetWifiConfiguration();
        }
        mWifiMetrics.endConnectionEvent(level2FailureCode, connectivityFailureCode,
                level2FailureReason);
        mWifiConnectivityManager.handleConnectionAttemptEnded(level2FailureCode);
        if (configuration != null) {
            mNetworkFactory.handleConnectionAttemptEnded(level2FailureCode, configuration);
            mWifiNetworkSuggestionsManager.handleConnectionAttemptEnded(
                    level2FailureCode, configuration, getCurrentBSSID());
        }
        handleConnectionAttemptEndForDiagnostics(level2FailureCode);
    }

    private void handleIPv4Success(DhcpResults dhcpResults) {
        if (mVerboseLoggingEnabled) {
            logd("handleIPv4Success <" + dhcpResults.toString() + ">");
            logd("link address " + dhcpResults.ipAddress);
        }

        Inet4Address addr;
        synchronized (mDhcpResultsLock) {
            mDhcpResults = dhcpResults;
            addr = (Inet4Address) dhcpResults.ipAddress.getAddress();
        }

        if (mIsAutoRoaming) {
            int previousAddress = mWifiInfo.getIpAddress();
            int newAddress = NetworkUtils.inetAddressToInt(addr);
            if (previousAddress != newAddress) {
                logd("handleIPv4Success, roaming and address changed"
                        + mWifiInfo + " got: " + addr);
            }
        }

        mWifiInfo.setInetAddress(addr);

        final WifiConfiguration config = getCurrentWifiConfiguration();
        if (config != null) {
            mWifiInfo.setEphemeral(config.ephemeral);
            mWifiInfo.setTrusted(config.trusted);
        }

        // Set meteredHint if DHCP result says network is metered
        if (dhcpResults.hasMeteredHint()) {
            mWifiInfo.setMeteredHint(true);
        }

        updateCapabilities(config);
    }

    private void handleSuccessfulIpConfiguration() {
        mLastSignalLevel = -1; // Force update of signal strength
        WifiConfiguration c = getCurrentWifiConfiguration();
        if (c != null) {
            // Reset IP failure tracking
            c.getNetworkSelectionStatus().clearDisableReasonCounter(
                    WifiConfiguration.NetworkSelectionStatus.DISABLED_DHCP_FAILURE);

            // Tell the framework whether the newly connected network is trusted or untrusted.
            updateCapabilities(c);
        }
        mWifiScoreCard.noteIpConfiguration(mWifiInfo);
    }

    private void handleIPv4Failure() {
        // TODO: Move this to provisioning failure, not DHCP failure.
        // DHCPv4 failure is expected on an IPv6-only network.
        mWifiDiagnostics.captureBugReportData(WifiDiagnostics.REPORT_REASON_DHCP_FAILURE);
        if (mVerboseLoggingEnabled) {
            int count = -1;
            WifiConfiguration config = getCurrentWifiConfiguration();
            if (config != null) {
                count = config.getNetworkSelectionStatus().getDisableReasonCounter(
                        WifiConfiguration.NetworkSelectionStatus.DISABLED_DHCP_FAILURE);
            }
            log("DHCP failure count=" + count);
        }
        reportConnectionAttemptEnd(
                WifiMetrics.ConnectionEvent.FAILURE_DHCP,
                WifiMetricsProto.ConnectionEvent.HLF_DHCP,
                WifiMetricsProto.ConnectionEvent.FAILURE_REASON_UNKNOWN);
        synchronized (mDhcpResultsLock) {
            if (mDhcpResults != null) {
                mDhcpResults.clear();
            }
        }
        if (mVerboseLoggingEnabled) {
            logd("handleIPv4Failure");
        }
    }

    private void handleIpConfigurationLost() {
        mWifiInfo.setInetAddress(null);
        mWifiInfo.setMeteredHint(false);

        mWifiConfigManager.updateNetworkSelectionStatus(mLastNetworkId,
                WifiConfiguration.NetworkSelectionStatus.DISABLED_DHCP_FAILURE);

        /* DHCP times out after about 30 seconds, we do a
         * disconnect thru supplicant, we will let autojoin retry connecting to the network
         */
        mWifiNative.disconnect(mInterfaceName);
    }

    private void handleIpReachabilityLost() {
        mWifiScoreCard.noteIpReachabilityLost(mWifiInfo);
        mWifiInfo.setInetAddress(null);
        mWifiInfo.setMeteredHint(false);

        // Disconnect via supplicant, and let autojoin retry connecting to the network.
        mWifiNative.disconnect(mInterfaceName);
    }

    /*
     * Read a MAC address in /proc/arp/table, used by ClientModeImpl
     * so as to record MAC address of default gateway.
     **/
    private String macAddressFromRoute(String ipAddress) {
        String macAddress = null;
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader("/proc/net/arp"));

            // Skip over the line bearing column titles
            String line = reader.readLine();

            while ((line = reader.readLine()) != null) {
                String[] tokens = line.split("[ ]+");
                if (tokens.length < 6) {
                    continue;
                }

                // ARP column format is
                // Address HWType HWAddress Flags Mask IFace
                String ip = tokens[0];
                String mac = tokens[3];

                if (ipAddress.equals(ip)) {
                    macAddress = mac;
                    break;
                }
            }

            if (macAddress == null) {
                loge("Did not find remoteAddress {" + ipAddress + "} in /proc/net/arp");
            }

        } catch (FileNotFoundException e) {
            loge("Could not open /proc/net/arp to lookup mac address");
        } catch (IOException e) {
            loge("Could not read /proc/net/arp to lookup mac address");
        } finally {
            try {
                if (reader != null) {
                    reader.close();
                }
            } catch (IOException e) {
                // Do nothing
            }
        }
        return macAddress;

    }

    /**
     * Determine if the specified auth failure is considered to be a permanent wrong password
     * failure. The criteria for such failure is when wrong password error is detected
     * and the network had never been connected before.
     *
     * For networks that have previously connected successfully, we consider wrong password
     * failures to be temporary, to be on the conservative side.  Since this might be the
     * case where we are trying to connect to a wrong network (e.g. A network with same SSID
     * but different password).
     */
    private boolean isPermanentWrongPasswordFailure(int networkId, int reasonCode) {
        if (reasonCode != WifiManager.ERROR_AUTH_FAILURE_WRONG_PSWD) {
            return false;
        }
        WifiConfiguration network = mWifiConfigManager.getConfiguredNetwork(networkId);
        if (network != null && network.getNetworkSelectionStatus().getHasEverConnected()) {
            return false;
        }
        return true;
    }

    void registerNetworkFactory() {
        if (!checkAndSetConnectivityInstance()) return;
        mNetworkFactory.register();
        mUntrustedNetworkFactory.register();
    }

    /**
     * ClientModeImpl needs to enable/disable other services when wifi is in client mode.  This
     * method allows ClientModeImpl to get these additional system services.
     *
     * At this time, this method is used to setup variables for P2P service and Wifi Aware.
     */
    private void getAdditionalWifiServiceInterfaces() {
        // First set up Wifi Direct
        if (mP2pSupported) {
            IBinder s1 = mFacade.getService(Context.WIFI_P2P_SERVICE);
            WifiP2pServiceImpl wifiP2pServiceImpl =
                    (WifiP2pServiceImpl) IWifiP2pManager.Stub.asInterface(s1);

            if (wifiP2pServiceImpl != null) {
                mWifiP2pChannel = new AsyncChannel();
                mWifiP2pChannel.connect(mContext, getHandler(),
                        wifiP2pServiceImpl.getP2pStateMachineMessenger());
            }
        }
    }

     /**
     * Dynamically change the MAC address to use the locally randomized
     * MAC address generated for each network.
     * @param config WifiConfiguration with mRandomizedMacAddress to change into. If the address
     * is masked out or not set, it will generate a new random MAC address.
     */
    private void configureRandomizedMacAddress(WifiConfiguration config) {
        if (config == null) {
            Log.e(TAG, "No config to change MAC address to");
            return;
        }
        MacAddress currentMac = MacAddress.fromString(mWifiNative.getMacAddress(mInterfaceName));
        MacAddress newMac = config.getOrCreateRandomizedMacAddress();
        mWifiConfigManager.setNetworkRandomizedMacAddress(config.networkId, newMac);
        if (!WifiConfiguration.isValidMacAddressForRandomization(newMac)) {
            Log.wtf(TAG, "Config generated an invalid MAC address");
        } else if (currentMac.equals(newMac)) {
            Log.d(TAG, "No changes in MAC address");
        } else {
            mWifiMetrics.logStaEvent(StaEvent.TYPE_MAC_CHANGE, config);
            boolean setMacSuccess =
                    mWifiNative.setMacAddress(mInterfaceName, newMac);
            Log.d(TAG, "ConnectedMacRandomization SSID(" + config.getPrintableSsid()
                    + "). setMacAddress(" + newMac.toString() + ") from "
                    + currentMac.toString() + " = " + setMacSuccess);
        }
    }

    /**
     * Sets the current MAC to the factory MAC address.
     */
    private void setCurrentMacToFactoryMac(WifiConfiguration config) {
        MacAddress factoryMac = mWifiNative.getFactoryMacAddress(mInterfaceName);
        if (factoryMac == null) {
            Log.e(TAG, "Fail to set factory MAC address. Factory MAC is null.");
            return;
        }
        String currentMacStr = mWifiNative.getMacAddress(mInterfaceName);
        if (!TextUtils.equals(currentMacStr, factoryMac.toString())) {
            if (mWifiNative.setMacAddress(mInterfaceName, factoryMac)) {
                mWifiMetrics.logStaEvent(StaEvent.TYPE_MAC_CHANGE, config);
            } else {
                Log.e(TAG, "Failed to set MAC address to " + "'" + factoryMac.toString() + "'");
            }
        }
    }

    /**
     * Helper method to check if Connected MAC Randomization is supported - onDown events are
     * skipped if this feature is enabled (b/72459123).
     *
     * @return boolean true if Connected MAC randomization is supported, false otherwise
     */
    public boolean isConnectedMacRandomizationEnabled() {
        return mConnectedMacRandomzationSupported;
    }

    /**
     * Helper method allowing ClientModeManager to report an error (interface went down) and trigger
     * recovery.
     *
     * @param reason int indicating the SelfRecovery failure type.
     */
    public void failureDetected(int reason) {
        // report a failure
        mWifiInjector.getSelfRecovery().trigger(SelfRecovery.REASON_STA_IFACE_DOWN);
    }

    /********************************************************
     * HSM states
     *******************************************************/

    class DefaultState extends State {

        @Override
        public boolean processMessage(Message message) {
            boolean handleStatus = HANDLED;

            switch (message.what) {
                case AsyncChannel.CMD_CHANNEL_HALF_CONNECTED: {
                    AsyncChannel ac = (AsyncChannel) message.obj;
                    if (ac == mWifiP2pChannel) {
                        if (message.arg1 == AsyncChannel.STATUS_SUCCESSFUL) {
                            p2pSendMessage(AsyncChannel.CMD_CHANNEL_FULL_CONNECTION);
                        } else {
                            // TODO: We should probably do some cleanup or attempt a retry
                            // b/34283611
                            loge("WifiP2pService connection failure, error=" + message.arg1);
                        }
                    } else {
                        loge("got HALF_CONNECTED for unknown channel");
                    }
                    break;
                }
                case AsyncChannel.CMD_CHANNEL_DISCONNECTED: {
                    AsyncChannel ac = (AsyncChannel) message.obj;
                    if (ac == mWifiP2pChannel) {
                        loge("WifiP2pService channel lost, message.arg1 =" + message.arg1);
                        //TODO: Re-establish connection to state machine after a delay (b/34283611)
                        // mWifiP2pChannel.connect(mContext, getHandler(),
                        // mWifiP2pManager.getMessenger());
                    }
                    break;
                }
                case CMD_BLUETOOTH_ADAPTER_STATE_CHANGE:
                    mBluetoothConnectionActive =
                            (message.arg1 != BluetoothAdapter.STATE_DISCONNECTED);
                    break;
                case CMD_ENABLE_NETWORK:
                    boolean disableOthers = message.arg2 == 1;
                    int netId = message.arg1;
                    boolean ok = mWifiConfigManager.enableNetwork(
                            netId, disableOthers, message.sendingUid);
                    if (!ok) {
                        mMessageHandlingStatus = MESSAGE_HANDLING_STATUS_FAIL;
                    }
                    replyToMessage(message, message.what, ok ? SUCCESS : FAILURE);
                    break;
                case CMD_ADD_OR_UPDATE_NETWORK:
                    WifiConfiguration config = (WifiConfiguration) message.obj;
                    NetworkUpdateResult result =
                            mWifiConfigManager.addOrUpdateNetwork(config, message.sendingUid);
                    if (!result.isSuccess()) {
                        mMessageHandlingStatus = MESSAGE_HANDLING_STATUS_FAIL;
                    }
                    replyToMessage(message, message.what, result.getNetworkId());
                    break;
                case CMD_REMOVE_NETWORK:
                    deleteNetworkConfigAndSendReply(message, false);
                    break;
                case CMD_GET_CONFIGURED_NETWORKS:
                    replyToMessage(message, message.what,
                            mWifiConfigManager.getSavedNetworks(message.arg2));
                    break;
                case CMD_GET_PRIVILEGED_CONFIGURED_NETWORKS:
                    replyToMessage(message, message.what,
                            mWifiConfigManager.getConfiguredNetworksWithPasswords());
                    break;
                case CMD_ENABLE_RSSI_POLL:
                    mEnableRssiPolling = (message.arg1 == 1);
                    break;
                case CMD_SET_HIGH_PERF_MODE:
                    if (message.arg1 == 1) {
                        setSuspendOptimizations(SUSPEND_DUE_TO_HIGH_PERF, false);
                    } else {
                        setSuspendOptimizations(SUSPEND_DUE_TO_HIGH_PERF, true);
                    }
                    break;
                case CMD_INITIALIZE:
                    ok = mWifiNative.initialize();
                    mPasspointManager.initializeProvisioner(
                            mWifiInjector.getWifiServiceHandlerThread().getLooper());
                    replyToMessage(message, message.what, ok ? SUCCESS : FAILURE);
                    break;
                case CMD_BOOT_COMPLETED:
                    // get other services that we need to manage
                    getAdditionalWifiServiceInterfaces();
                    new MemoryStoreImpl(mContext, mWifiInjector, mWifiScoreCard).start();
                    if (!mWifiConfigManager.loadFromStore()) {
                        Log.e(TAG, "Failed to load from config store");
                    }
                    registerNetworkFactory();
                    break;
                case CMD_SCREEN_STATE_CHANGED:
                    handleScreenStateChanged(message.arg1 != 0);
                    break;
                case CMD_DISCONNECT:
                case CMD_RECONNECT:
                case CMD_REASSOCIATE:
                case WifiMonitor.NETWORK_CONNECTION_EVENT:
                case WifiMonitor.NETWORK_DISCONNECTION_EVENT:
                case WifiMonitor.SUPPLICANT_STATE_CHANGE_EVENT:
                case WifiMonitor.AUTHENTICATION_FAILURE_EVENT:
                case WifiMonitor.ASSOCIATION_REJECTION_EVENT:
                case CMD_RSSI_POLL:
                case CMD_ONESHOT_RSSI_POLL:
                case CMD_PRE_DHCP_ACTION:
                case CMD_PRE_DHCP_ACTION_COMPLETE:
                case CMD_POST_DHCP_ACTION:
                case WifiMonitor.SUP_REQUEST_IDENTITY:
                case WifiMonitor.SUP_REQUEST_SIM_AUTH:
                case CMD_TARGET_BSSID:
                case CMD_START_CONNECT:
                case CMD_START_ROAM:
                case CMD_ASSOCIATED_BSSID:
                case CMD_UNWANTED_NETWORK:
                case CMD_DISCONNECTING_WATCHDOG_TIMER:
                case CMD_ROAM_WATCHDOG_TIMER:
                case CMD_DISABLE_EPHEMERAL_NETWORK:
                    mMessageHandlingStatus = MESSAGE_HANDLING_STATUS_DISCARD;
                    break;
                case CMD_SET_OPERATIONAL_MODE:
                    // using the CMD_SET_OPERATIONAL_MODE (sent at front of queue) to trigger the
                    // state transitions performed in setOperationalMode.
                    break;
                case CMD_SET_SUSPEND_OPT_ENABLED:
                    if (message.arg1 == 1) {
                        if (message.arg2 == 1) {
                            mSuspendWakeLock.release();
                        }
                        setSuspendOptimizations(SUSPEND_DUE_TO_SCREEN, true);
                    } else {
                        setSuspendOptimizations(SUSPEND_DUE_TO_SCREEN, false);
                    }
                    break;
                case WifiManager.CONNECT_NETWORK:
                    replyToMessage(message, WifiManager.CONNECT_NETWORK_FAILED,
                            WifiManager.BUSY);
                    break;
                case WifiManager.FORGET_NETWORK:
                    deleteNetworkConfigAndSendReply(message, true);
                    break;
                case WifiManager.SAVE_NETWORK:
                    saveNetworkConfigAndSendReply(message);
                    break;
                case WifiManager.DISABLE_NETWORK:
                    replyToMessage(message, WifiManager.DISABLE_NETWORK_FAILED,
                            WifiManager.BUSY);
                    break;
                case WifiManager.RSSI_PKTCNT_FETCH:
                    replyToMessage(message, WifiManager.RSSI_PKTCNT_FETCH_FAILED,
                            WifiManager.BUSY);
                    break;
                case CMD_GET_SUPPORTED_FEATURES:
                    long featureSet = (mWifiNative.getSupportedFeatureSet(mInterfaceName));
                    replyToMessage(message, message.what, Long.valueOf(featureSet));
                    break;
                case CMD_GET_LINK_LAYER_STATS:
                    // Not supported hence reply with error message
                    replyToMessage(message, message.what, null);
                    break;
                case WifiP2pServiceImpl.P2P_CONNECTION_CHANGED:
                    NetworkInfo info = (NetworkInfo) message.obj;
                    mP2pConnected.set(info.isConnected());
                    break;
                case WifiP2pServiceImpl.DISCONNECT_WIFI_REQUEST:
                    mTemporarilyDisconnectWifi = (message.arg1 == 1);
                    replyToMessage(message, WifiP2pServiceImpl.DISCONNECT_WIFI_RESPONSE);
                    break;
                /* Link configuration (IP address, DNS, ...) changes notified via netlink */
                case CMD_UPDATE_LINKPROPERTIES:
                    updateLinkProperties((LinkProperties) message.obj);
                    break;
                case CMD_GET_MATCHING_OSU_PROVIDERS:
                    replyToMessage(message, message.what, new HashMap<>());
                    break;
                case CMD_GET_MATCHING_PASSPOINT_CONFIGS_FOR_OSU_PROVIDERS:
                    replyToMessage(message, message.what,
                            new HashMap<OsuProvider, PasspointConfiguration>());
                    break;
                case CMD_GET_WIFI_CONFIGS_FOR_PASSPOINT_PROFILES:
                    replyToMessage(message, message.what, new ArrayList<>());
                    break;
                case CMD_START_SUBSCRIPTION_PROVISIONING:
                    replyToMessage(message, message.what, 0);
                    break;
                case CMD_IP_CONFIGURATION_SUCCESSFUL:
                case CMD_IP_CONFIGURATION_LOST:
                case CMD_IP_REACHABILITY_LOST:
                    mMessageHandlingStatus = MESSAGE_HANDLING_STATUS_DISCARD;
                    break;
                case CMD_REMOVE_APP_CONFIGURATIONS:
                    deferMessage(message);
                    break;
                case CMD_REMOVE_USER_CONFIGURATIONS:
                    deferMessage(message);
                    break;
                case CMD_START_IP_PACKET_OFFLOAD:
                    /* fall-through */
                case CMD_STOP_IP_PACKET_OFFLOAD:
                case CMD_ADD_KEEPALIVE_PACKET_FILTER_TO_APF:
                case CMD_REMOVE_KEEPALIVE_PACKET_FILTER_FROM_APF:
                    if (mNetworkAgent != null) {
                        mNetworkAgent.onSocketKeepaliveEvent(message.arg1,
                                SocketKeepalive.ERROR_INVALID_NETWORK);
                    }
                    break;
                case CMD_START_RSSI_MONITORING_OFFLOAD:
                    mMessageHandlingStatus = MESSAGE_HANDLING_STATUS_DISCARD;
                    break;
                case CMD_STOP_RSSI_MONITORING_OFFLOAD:
                    mMessageHandlingStatus = MESSAGE_HANDLING_STATUS_DISCARD;
                    break;
                case CMD_USER_SWITCH:
                    Set<Integer> removedNetworkIds =
                            mWifiConfigManager.handleUserSwitch(message.arg1);
                    if (removedNetworkIds.contains(mTargetNetworkId)
                            || removedNetworkIds.contains(mLastNetworkId)) {
                        // Disconnect and let autojoin reselect a new network
                        sendMessage(CMD_DISCONNECT);
                    }
                    break;
                case CMD_USER_UNLOCK:
                    mWifiConfigManager.handleUserUnlock(message.arg1);
                    break;
                case CMD_USER_STOP:
                    mWifiConfigManager.handleUserStop(message.arg1);
                    break;
                case CMD_QUERY_OSU_ICON:
                case CMD_MATCH_PROVIDER_NETWORK:
                    /* reply with arg1 = 0 - it returns API failure to the calling app
                     * (message.what is not looked at)
                     */
                    replyToMessage(message, message.what);
                    break;
                case CMD_ADD_OR_UPDATE_PASSPOINT_CONFIG:
                    Bundle bundle = (Bundle) message.obj;
                    int addResult = mPasspointManager.addOrUpdateProvider(bundle.getParcelable(
                            EXTRA_PASSPOINT_CONFIGURATION),
                            bundle.getInt(EXTRA_UID),
                            bundle.getString(EXTRA_PACKAGE_NAME))
                            ? SUCCESS : FAILURE;
                    replyToMessage(message, message.what, addResult);
                    break;
                case CMD_REMOVE_PASSPOINT_CONFIG:
                    int removeResult = mPasspointManager.removeProvider(
                            (String) message.obj) ? SUCCESS : FAILURE;
                    replyToMessage(message, message.what, removeResult);
                    break;
                case CMD_GET_PASSPOINT_CONFIGS:
                    replyToMessage(message, message.what, mPasspointManager.getProviderConfigs());
                    break;
                case CMD_RESET_SIM_NETWORKS:
                    /* Defer this message until supplicant is started. */
                    mMessageHandlingStatus = MESSAGE_HANDLING_STATUS_DEFERRED;
                    deferMessage(message);
                    break;
                case CMD_INSTALL_PACKET_FILTER:
                    mWifiNative.installPacketFilter(mInterfaceName, (byte[]) message.obj);
                    break;
                case CMD_READ_PACKET_FILTER:
                    byte[] data = mWifiNative.readPacketFilter(mInterfaceName);
                    if (mIpClient != null) {
                        mIpClient.readPacketFilterComplete(data);
                    }
                    break;
                case CMD_SET_FALLBACK_PACKET_FILTERING:
                    if ((boolean) message.obj) {
                        mWifiNative.startFilteringMulticastV4Packets(mInterfaceName);
                    } else {
                        mWifiNative.stopFilteringMulticastV4Packets(mInterfaceName);
                    }
                    break;
                case CMD_DIAGS_CONNECT_TIMEOUT:
                    mWifiDiagnostics.reportConnectionEvent(
                            BaseWifiDiagnostics.CONNECTION_EVENT_TIMEOUT);
                    break;
                case CMD_GET_ALL_MATCHING_FQDNS_FOR_SCAN_RESULTS:
                    replyToMessage(message, message.what, new HashMap<>());
                    break;
                case 0:
                    // We want to notice any empty messages (with what == 0) that might crop up.
                    // For example, we may have recycled a message sent to multiple handlers.
                    Log.wtf(TAG, "Error! empty message encountered");
                    break;
                default:
                    loge("Error! unhandled message" + message);
                    break;
            }

            if (handleStatus == HANDLED) {
                logStateAndMessage(message, this);
            }

            return handleStatus;
        }
    }

    /**
     * Helper method to start other services and get state ready for client mode
     */
    private void setupClientMode() {
        Log.d(TAG, "setupClientMode() ifacename = " + mInterfaceName);

        setHighPerfModeEnabled(false);

        mWifiStateTracker.updateState(WifiStateTracker.INVALID);
        mIpClientCallbacks = new IpClientCallbacksImpl();
        mFacade.makeIpClient(mContext, mInterfaceName, mIpClientCallbacks);
        if (!mIpClientCallbacks.awaitCreation()) {
            loge("Timeout waiting for IpClient");
        }

        setMulticastFilter(true);
        registerForWifiMonitorEvents();
        mWifiInjector.getWifiLastResortWatchdog().clearAllFailureCounts();
        setSupplicantLogLevel();

        // reset state related to supplicant starting
        mSupplicantStateTracker.sendMessage(CMD_RESET_SUPPLICANT_STATE);
        // Initialize data structures
        mLastBssid = null;
        mLastNetworkId = WifiConfiguration.INVALID_NETWORK_ID;
        mLastSignalLevel = -1;
        mWifiInfo.setMacAddress(mWifiNative.getMacAddress(mInterfaceName));
        // TODO: b/79504296 This broadcast has been deprecated and should be removed
        sendSupplicantConnectionChangedBroadcast(true);

        mWifiNative.setExternalSim(mInterfaceName, true);

        setRandomMacOui();
        mCountryCode.setReadyForChange(true);

        mWifiDiagnostics.startLogging(mVerboseLoggingEnabled);
        mIsRunning = true;
        updateBatteryWorkSource(null);

        /**
         * Enable bluetooth coexistence scan mode when bluetooth connection is active.
         * When this mode is on, some of the low-level scan parameters used by the
         * driver are changed to reduce interference with bluetooth
         */
        mWifiNative.setBluetoothCoexistenceScanMode(mInterfaceName, mBluetoothConnectionActive);

        // initialize network state
        setNetworkDetailedState(DetailedState.DISCONNECTED);

        // Disable legacy multicast filtering, which on some chipsets defaults to enabled.
        // Legacy IPv6 multicast filtering blocks ICMPv6 router advertisements which breaks IPv6
        // provisioning. Legacy IPv4 multicast filtering may be re-enabled later via
        // IpClient.Callback.setFallbackMulticastFilter()
        mWifiNative.stopFilteringMulticastV4Packets(mInterfaceName);
        mWifiNative.stopFilteringMulticastV6Packets(mInterfaceName);

        // Set the right suspend mode settings
        mWifiNative.setSuspendOptimizations(mInterfaceName, mSuspendOptNeedsDisabled == 0
                && mUserWantsSuspendOpt.get());

        setPowerSave(true);

        // Disable wpa_supplicant from auto reconnecting.
        mWifiNative.enableStaAutoReconnect(mInterfaceName, false);
        // STA has higher priority over P2P
        mWifiNative.setConcurrencyPriority(true);
    }

    /**
     * Helper method to stop external services and clean up state from client mode.
     */
    private void stopClientMode() {
        // exiting supplicant started state is now only applicable to client mode
        mWifiDiagnostics.stopLogging();

        mIsRunning = false;
        updateBatteryWorkSource(null);

        if (mIpClient != null && mIpClient.shutdown()) {
            // Block to make sure IpClient has really shut down, lest cleanup
            // race with, say, bringup code over in tethering.
            mIpClientCallbacks.awaitShutdown();
        }
        mNetworkInfo.setIsAvailable(false);
        if (mNetworkAgent != null) mNetworkAgent.sendNetworkInfo(mNetworkInfo);
        mCountryCode.setReadyForChange(false);
        mInterfaceName = null;
        // TODO: b/79504296 This broadcast has been deprecated and should be removed
        sendSupplicantConnectionChangedBroadcast(false);

        // Let's remove any ephemeral or passpoint networks.
        mWifiConfigManager.removeAllEphemeralOrPasspointConfiguredNetworks();
    }

    void registerConnected() {
        if (mLastNetworkId != WifiConfiguration.INVALID_NETWORK_ID) {
            mWifiConfigManager.updateNetworkAfterConnect(mLastNetworkId);
            // Notify PasspointManager of Passpoint network connected event.
            WifiConfiguration currentNetwork = getCurrentWifiConfiguration();
            if (currentNetwork != null && currentNetwork.isPasspoint()) {
                mPasspointManager.onPasspointNetworkConnected(currentNetwork.FQDN);
            }
        }
    }

    void registerDisconnected() {
        if (mLastNetworkId != WifiConfiguration.INVALID_NETWORK_ID) {
            mWifiConfigManager.updateNetworkAfterDisconnect(mLastNetworkId);
        }
    }

    /**
     * Returns WifiConfiguration object corresponding to the currently connected network, null if
     * not connected.
     */
    public WifiConfiguration getCurrentWifiConfiguration() {
        if (mLastNetworkId == WifiConfiguration.INVALID_NETWORK_ID) {
            return null;
        }
        return mWifiConfigManager.getConfiguredNetwork(mLastNetworkId);
    }

    private WifiConfiguration getTargetWifiConfiguration() {
        if (mTargetNetworkId == WifiConfiguration.INVALID_NETWORK_ID) {
            return null;
        }
        return mWifiConfigManager.getConfiguredNetwork(mTargetNetworkId);
    }

    ScanResult getCurrentScanResult() {
        WifiConfiguration config = getCurrentWifiConfiguration();
        if (config == null) {
            return null;
        }
        String bssid = mWifiInfo.getBSSID();
        if (bssid == null) {
            bssid = mTargetRoamBSSID;
        }
        ScanDetailCache scanDetailCache =
                mWifiConfigManager.getScanDetailCacheForNetwork(config.networkId);

        if (scanDetailCache == null) {
            return null;
        }

        return scanDetailCache.getScanResult(bssid);
    }

    String getCurrentBSSID() {
        return mLastBssid;
    }

    class ConnectModeState extends State {

        @Override
        public void enter() {
            Log.d(TAG, "entering ConnectModeState: ifaceName = " + mInterfaceName);
            mOperationalMode = CONNECT_MODE;
            setupClientMode();
            if (!mWifiNative.removeAllNetworks(mInterfaceName)) {
                loge("Failed to remove networks on entering connect mode");
            }
            mWifiInfo.reset();
            mWifiInfo.setSupplicantState(SupplicantState.DISCONNECTED);

            mWifiInjector.getWakeupController().reset();

            mNetworkInfo.setIsAvailable(true);
            if (mNetworkAgent != null) mNetworkAgent.sendNetworkInfo(mNetworkInfo);

            // initialize network state
            setNetworkDetailedState(DetailedState.DISCONNECTED);

            // Inform WifiConnectivityManager that Wifi is enabled
            mWifiConnectivityManager.setWifiEnabled(true);
            mNetworkFactory.setWifiState(true);
            // Inform metrics that Wifi is Enabled (but not yet connected)
            mWifiMetrics.setWifiState(WifiMetricsProto.WifiLog.WIFI_DISCONNECTED);
            mWifiMetrics.logStaEvent(StaEvent.TYPE_WIFI_ENABLED);
            // Inform sar manager that wifi is Enabled
            mSarManager.setClientWifiState(WifiManager.WIFI_STATE_ENABLED);
            mWifiScoreCard.noteSupplicantStateChanged(mWifiInfo);
        }

        @Override
        public void exit() {
            mOperationalMode = DISABLED_MODE;
            // Let the system know that wifi is not available since we are exiting client mode.
            mNetworkInfo.setIsAvailable(false);
            if (mNetworkAgent != null) mNetworkAgent.sendNetworkInfo(mNetworkInfo);

            // Inform WifiConnectivityManager that Wifi is disabled
            mWifiConnectivityManager.setWifiEnabled(false);
            mNetworkFactory.setWifiState(false);
            // Inform metrics that Wifi is being disabled (Toggled, airplane enabled, etc)
            mWifiMetrics.setWifiState(WifiMetricsProto.WifiLog.WIFI_DISABLED);
            mWifiMetrics.logStaEvent(StaEvent.TYPE_WIFI_DISABLED);
            // Inform scorecard that wifi is being disabled
            mWifiScoreCard.noteWifiDisabled(mWifiInfo);
            // Inform sar manager that wifi is being disabled
            mSarManager.setClientWifiState(WifiManager.WIFI_STATE_DISABLED);

            if (!mWifiNative.removeAllNetworks(mInterfaceName)) {
                loge("Failed to remove networks on exiting connect mode");
            }
            mWifiInfo.reset();
            mWifiInfo.setSupplicantState(SupplicantState.DISCONNECTED);
            mWifiScoreCard.noteSupplicantStateChanged(mWifiInfo);
            stopClientMode();
        }

        @Override
        public boolean processMessage(Message message) {
            WifiConfiguration config;
            int netId;
            boolean ok;
            boolean didDisconnect;
            String bssid;
            String ssid;
            NetworkUpdateResult result;
            Set<Integer> removedNetworkIds;
            int reasonCode;
            boolean timedOut;
            boolean handleStatus = HANDLED;

            switch (message.what) {
                case WifiMonitor.ASSOCIATION_REJECTION_EVENT:
                    mWifiDiagnostics.captureBugReportData(
                            WifiDiagnostics.REPORT_REASON_ASSOC_FAILURE);
                    mDidBlackListBSSID = false;
                    bssid = (String) message.obj;
                    timedOut = message.arg1 > 0;
                    reasonCode = message.arg2;
                    Log.d(TAG, "Association Rejection event: bssid=" + bssid + " reason code="
                            + reasonCode + " timedOut=" + Boolean.toString(timedOut));
                    if (bssid == null || TextUtils.isEmpty(bssid)) {
                        // If BSSID is null, use the target roam BSSID
                        bssid = mTargetRoamBSSID;
                    }
                    if (bssid != null) {
                        // If we have a BSSID, tell configStore to black list it
                        mDidBlackListBSSID = mWifiConnectivityManager.trackBssid(bssid, false,
                            reasonCode);
                    }
                    mWifiConfigManager.updateNetworkSelectionStatus(mTargetNetworkId,
                            WifiConfiguration.NetworkSelectionStatus
                            .DISABLED_ASSOCIATION_REJECTION);
                    mWifiConfigManager.setRecentFailureAssociationStatus(mTargetNetworkId,
                            reasonCode);
                    mSupplicantStateTracker.sendMessage(WifiMonitor.ASSOCIATION_REJECTION_EVENT);
                    // If rejection occurred while Metrics is tracking a ConnnectionEvent, end it.
                    reportConnectionAttemptEnd(
                            timedOut
                                    ? WifiMetrics.ConnectionEvent.FAILURE_ASSOCIATION_TIMED_OUT
                                    : WifiMetrics.ConnectionEvent.FAILURE_ASSOCIATION_REJECTION,
                            WifiMetricsProto.ConnectionEvent.HLF_NONE,
                            WifiMetricsProto.ConnectionEvent.FAILURE_REASON_UNKNOWN);
                    mWifiInjector.getWifiLastResortWatchdog()
                            .noteConnectionFailureAndTriggerIfNeeded(
                                    getTargetSsid(), bssid,
                                    WifiLastResortWatchdog.FAILURE_CODE_ASSOCIATION);
                    break;
                case WifiMonitor.AUTHENTICATION_FAILURE_EVENT:
                    mWifiDiagnostics.captureBugReportData(
                            WifiDiagnostics.REPORT_REASON_AUTH_FAILURE);
                    mSupplicantStateTracker.sendMessage(WifiMonitor.AUTHENTICATION_FAILURE_EVENT);
                    int disableReason = WifiConfiguration.NetworkSelectionStatus
                            .DISABLED_AUTHENTICATION_FAILURE;
                    reasonCode = message.arg1;
                    // Check if this is a permanent wrong password failure.
                    if (isPermanentWrongPasswordFailure(mTargetNetworkId, reasonCode)) {
                        disableReason = WifiConfiguration.NetworkSelectionStatus
                                .DISABLED_BY_WRONG_PASSWORD;
                        WifiConfiguration targetedNetwork =
                                mWifiConfigManager.getConfiguredNetwork(mTargetNetworkId);
                        if (targetedNetwork != null) {
                            mWrongPasswordNotifier.onWrongPasswordError(
                                    targetedNetwork.SSID);
                        }
                    } else if (reasonCode == WifiManager.ERROR_AUTH_FAILURE_EAP_FAILURE) {
                        int errorCode = message.arg2;
                        handleEapAuthFailure(mTargetNetworkId, errorCode);
                        if (errorCode == WifiNative.EAP_SIM_NOT_SUBSCRIBED) {
                            disableReason = WifiConfiguration.NetworkSelectionStatus
                                .DISABLED_AUTHENTICATION_NO_SUBSCRIPTION;
                        }
                    }
                    mWifiConfigManager.updateNetworkSelectionStatus(
                            mTargetNetworkId, disableReason);
                    mWifiConfigManager.clearRecentFailureReason(mTargetNetworkId);

                    //If failure occurred while Metrics is tracking a ConnnectionEvent, end it.
                    int level2FailureReason;
                    switch (reasonCode) {
                        case WifiManager.ERROR_AUTH_FAILURE_NONE:
                            level2FailureReason =
                                    WifiMetricsProto.ConnectionEvent.AUTH_FAILURE_NONE;
                            break;
                        case WifiManager.ERROR_AUTH_FAILURE_TIMEOUT:
                            level2FailureReason =
                                    WifiMetricsProto.ConnectionEvent.AUTH_FAILURE_TIMEOUT;
                            break;
                        case WifiManager.ERROR_AUTH_FAILURE_WRONG_PSWD:
                            level2FailureReason =
                                    WifiMetricsProto.ConnectionEvent.AUTH_FAILURE_WRONG_PSWD;
                            break;
                        case WifiManager.ERROR_AUTH_FAILURE_EAP_FAILURE:
                            level2FailureReason =
                                    WifiMetricsProto.ConnectionEvent.AUTH_FAILURE_EAP_FAILURE;
                            break;
                        default:
                            level2FailureReason =
                                    WifiMetricsProto.ConnectionEvent.FAILURE_REASON_UNKNOWN;
                            break;
                    }
                    reportConnectionAttemptEnd(
                            WifiMetrics.ConnectionEvent.FAILURE_AUTHENTICATION_FAILURE,
                            WifiMetricsProto.ConnectionEvent.HLF_NONE,
                            level2FailureReason);
                    if (reasonCode != WifiManager.ERROR_AUTH_FAILURE_WRONG_PSWD) {
                        mWifiInjector.getWifiLastResortWatchdog()
                                .noteConnectionFailureAndTriggerIfNeeded(
                                        getTargetSsid(), mTargetRoamBSSID,
                                        WifiLastResortWatchdog.FAILURE_CODE_AUTHENTICATION);
                    }
                    break;
                case WifiMonitor.SUPPLICANT_STATE_CHANGE_EVENT:
                    SupplicantState state = handleSupplicantStateChange(message);

                    // Supplicant can fail to report a NETWORK_DISCONNECTION_EVENT
                    // when authentication times out after a successful connection,
                    // we can figure this from the supplicant state. If supplicant
                    // state is DISCONNECTED, but the mNetworkInfo says we are not
                    // disconnected, we need to handle a disconnection
                    if (state == SupplicantState.DISCONNECTED
                            && mNetworkInfo.getState() != NetworkInfo.State.DISCONNECTED) {
                        if (mVerboseLoggingEnabled) {
                            log("Missed CTRL-EVENT-DISCONNECTED, disconnect");
                        }
                        handleNetworkDisconnect();
                        transitionTo(mDisconnectedState);
                    }

                    // If we have COMPLETED a connection to a BSSID, start doing
                    // DNAv4/DNAv6 -style probing for on-link neighbors of
                    // interest (e.g. routers); harmless if none are configured.
                    if (state == SupplicantState.COMPLETED) {
                        if (mIpClient != null) {
                            mIpClient.confirmConfiguration();
                        }
                        mWifiScoreReport.noteIpCheck();
                    }
                    break;
                case WifiP2pServiceImpl.DISCONNECT_WIFI_REQUEST:
                    if (message.arg1 == 1) {
                        mWifiMetrics.logStaEvent(StaEvent.TYPE_FRAMEWORK_DISCONNECT,
                                StaEvent.DISCONNECT_P2P_DISCONNECT_WIFI_REQUEST);
                        mWifiNative.disconnect(mInterfaceName);
                        mTemporarilyDisconnectWifi = true;
                    } else {
                        mWifiNative.reconnect(mInterfaceName);
                        mTemporarilyDisconnectWifi = false;
                    }
                    break;
                case CMD_REMOVE_NETWORK:
                    if (!deleteNetworkConfigAndSendReply(message, false)) {
                        // failed to remove the config and caller was notified
                        mMessageHandlingStatus = MESSAGE_HANDLING_STATUS_FAIL;
                        break;
                    }
                    //  we successfully deleted the network config
                    netId = message.arg1;
                    if (netId == mTargetNetworkId || netId == mLastNetworkId) {
                        // Disconnect and let autojoin reselect a new network
                        sendMessage(CMD_DISCONNECT);
                    }
                    break;
                case CMD_ENABLE_NETWORK:
                    boolean disableOthers = message.arg2 == 1;
                    netId = message.arg1;
                    if (disableOthers) {
                        // If the app has all the necessary permissions, this will trigger a connect
                        // attempt.
                        ok = connectToUserSelectNetwork(netId, message.sendingUid, false);
                    } else {
                        ok = mWifiConfigManager.enableNetwork(netId, false, message.sendingUid);
                    }
                    if (!ok) {
                        mMessageHandlingStatus = MESSAGE_HANDLING_STATUS_FAIL;
                    }
                    replyToMessage(message, message.what, ok ? SUCCESS : FAILURE);
                    break;
                case WifiManager.DISABLE_NETWORK:
                    netId = message.arg1;
                    if (mWifiConfigManager.disableNetwork(netId, message.sendingUid)) {
                        replyToMessage(message, WifiManager.DISABLE_NETWORK_SUCCEEDED);
                        if (netId == mTargetNetworkId || netId == mLastNetworkId) {
                            // Disconnect and let autojoin reselect a new network
                            sendMessage(CMD_DISCONNECT);
                        }
                    } else {
                        loge("Failed to disable network");
                        mMessageHandlingStatus = MESSAGE_HANDLING_STATUS_FAIL;
                        replyToMessage(message, WifiManager.DISABLE_NETWORK_FAILED,
                                WifiManager.ERROR);
                    }
                    break;
                case CMD_DISABLE_EPHEMERAL_NETWORK:
                    config = mWifiConfigManager.disableEphemeralNetwork((String) message.obj);
                    if (config != null) {
                        if (config.networkId == mTargetNetworkId
                                || config.networkId == mLastNetworkId) {
                            // Disconnect and let autojoin reselect a new network
                            sendMessage(CMD_DISCONNECT);
                        }
                    }
                    break;
                case WifiMonitor.SUP_REQUEST_IDENTITY:
                    netId = message.arg2;
                    boolean identitySent = false;
                    // For SIM & AKA/AKA' EAP method Only, get identity from ICC
                    if (mTargetWifiConfiguration != null
                            && mTargetWifiConfiguration.networkId == netId
                            && TelephonyUtil.isSimConfig(mTargetWifiConfiguration)) {
                        // Pair<identity, encrypted identity>
                        Pair<String, String> identityPair =
                                TelephonyUtil.getSimIdentity(getTelephonyManager(),
                                        new TelephonyUtil(), mTargetWifiConfiguration,
                                        mWifiInjector.getCarrierNetworkConfig());
                        Log.i(TAG, "SUP_REQUEST_IDENTITY: identityPair=" + identityPair);
                        if (identityPair != null && identityPair.first != null) {
                            mWifiNative.simIdentityResponse(mInterfaceName, netId,
                                    identityPair.first, identityPair.second);
                            identitySent = true;
                        } else {
                            Log.e(TAG, "Unable to retrieve identity from Telephony");
                        }
                    }

                    if (!identitySent) {
                        // Supplicant lacks credentials to connect to that network, hence black list
                        ssid = (String) message.obj;
                        if (mTargetWifiConfiguration != null && ssid != null
                                && mTargetWifiConfiguration.SSID != null
                                && mTargetWifiConfiguration.SSID.equals("\"" + ssid + "\"")) {
                            mWifiConfigManager.updateNetworkSelectionStatus(
                                    mTargetWifiConfiguration.networkId,
                                    WifiConfiguration.NetworkSelectionStatus
                                            .DISABLED_AUTHENTICATION_NO_CREDENTIALS);
                        }
                        mWifiMetrics.logStaEvent(StaEvent.TYPE_FRAMEWORK_DISCONNECT,
                                StaEvent.DISCONNECT_GENERIC);
                        mWifiNative.disconnect(mInterfaceName);
                    }
                    break;
                case WifiMonitor.SUP_REQUEST_SIM_AUTH:
                    logd("Received SUP_REQUEST_SIM_AUTH");
                    SimAuthRequestData requestData = (SimAuthRequestData) message.obj;
                    if (requestData != null) {
                        if (requestData.protocol == WifiEnterpriseConfig.Eap.SIM) {
                            handleGsmAuthRequest(requestData);
                        } else if (requestData.protocol == WifiEnterpriseConfig.Eap.AKA
                                || requestData.protocol == WifiEnterpriseConfig.Eap.AKA_PRIME) {
                            handle3GAuthRequest(requestData);
                        }
                    } else {
                        loge("Invalid SIM auth request");
                    }
                    break;
                case CMD_GET_MATCHING_OSU_PROVIDERS:
                    replyToMessage(message, message.what,
                            mPasspointManager.getMatchingOsuProviders(
                                    (List<ScanResult>) message.obj));
                    break;
                case CMD_GET_MATCHING_PASSPOINT_CONFIGS_FOR_OSU_PROVIDERS:
                    replyToMessage(message, message.what,
                            mPasspointManager.getMatchingPasspointConfigsForOsuProviders(
                                    (List<OsuProvider>) message.obj));
                    break;
                case CMD_GET_WIFI_CONFIGS_FOR_PASSPOINT_PROFILES:
                    replyToMessage(message, message.what,
                            mPasspointManager.getWifiConfigsForPasspointProfiles(
                                    (List<String>) message.obj));

                    break;
                case CMD_START_SUBSCRIPTION_PROVISIONING:
                    IProvisioningCallback callback = (IProvisioningCallback) message.obj;
                    OsuProvider provider =
                            (OsuProvider) message.getData().getParcelable(EXTRA_OSU_PROVIDER);
                    int res = mPasspointManager.startSubscriptionProvisioning(
                                    message.arg1, provider, callback) ? 1 : 0;
                    replyToMessage(message, message.what, res);
                    break;
                case CMD_RECONNECT:
                    WorkSource workSource = (WorkSource) message.obj;
                    mWifiConnectivityManager.forceConnectivityScan(workSource);
                    break;
                case CMD_REASSOCIATE:
                    mLastConnectAttemptTimestamp = mClock.getWallClockMillis();
                    mWifiNative.reassociate(mInterfaceName);
                    break;
                case CMD_START_ROAM:
                    mMessageHandlingStatus = MESSAGE_HANDLING_STATUS_DISCARD;
                    break;
                case CMD_START_CONNECT:
                    /* connect command coming from auto-join */
                    netId = message.arg1;
                    int uid = message.arg2;
                    bssid = (String) message.obj;

                    if (!hasConnectionRequests()) {
                        if (mNetworkAgent == null) {
                            loge("CMD_START_CONNECT but no requests and not connected,"
                                    + " bailing");
                            break;
                        } else if (!mWifiPermissionsUtil.checkNetworkSettingsPermission(uid)) {
                            loge("CMD_START_CONNECT but no requests and connected, but app "
                                    + "does not have sufficient permissions, bailing");
                            break;
                        }
                    }
                    config = mWifiConfigManager.getConfiguredNetworkWithoutMasking(netId);
                    logd("CMD_START_CONNECT sup state "
                            + mSupplicantStateTracker.getSupplicantStateName()
                            + " my state " + getCurrentState().getName()
                            + " nid=" + Integer.toString(netId)
                            + " roam=" + Boolean.toString(mIsAutoRoaming));
                    if (config == null) {
                        loge("CMD_START_CONNECT and no config, bail out...");
                        break;
                    }
                    // Update scorecard while there is still state from existing connection
                    mWifiScoreCard.noteConnectionAttempt(mWifiInfo);
                    mTargetNetworkId = netId;
                    setTargetBssid(config, bssid);

                    reportConnectionAttemptStart(config, mTargetRoamBSSID,
                            WifiMetricsProto.ConnectionEvent.ROAM_UNRELATED);
                    if (config.macRandomizationSetting
                            == WifiConfiguration.RANDOMIZATION_PERSISTENT
                            && mConnectedMacRandomzationSupported) {
                        configureRandomizedMacAddress(config);
                    } else {
                        setCurrentMacToFactoryMac(config);
                    }

                    String currentMacAddress = mWifiNative.getMacAddress(mInterfaceName);
                    mWifiInfo.setMacAddress(currentMacAddress);
                    Log.i(TAG, "Connecting with " + currentMacAddress + " as the mac address");

                    if (config.enterpriseConfig != null
                            && TelephonyUtil.isSimEapMethod(config.enterpriseConfig.getEapMethod())
                            && mWifiInjector.getCarrierNetworkConfig()
                                    .isCarrierEncryptionInfoAvailable()
                            && TextUtils.isEmpty(config.enterpriseConfig.getAnonymousIdentity())) {
                        String anonAtRealm = TelephonyUtil.getAnonymousIdentityWith3GppRealm(
                                getTelephonyManager());
                        config.enterpriseConfig.setAnonymousIdentity(anonAtRealm);
                    }

                    if (mWifiNative.connectToNetwork(mInterfaceName, config)) {
                        mWifiMetrics.logStaEvent(StaEvent.TYPE_CMD_START_CONNECT, config);
                        mLastConnectAttemptTimestamp = mClock.getWallClockMillis();
                        mTargetWifiConfiguration = config;
                        mIsAutoRoaming = false;
                        if (getCurrentState() != mDisconnectedState) {
                            transitionTo(mDisconnectingState);
                        }
                    } else {
                        loge("CMD_START_CONNECT Failed to start connection to network " + config);
                        reportConnectionAttemptEnd(
                                WifiMetrics.ConnectionEvent.FAILURE_CONNECT_NETWORK_FAILED,
                                WifiMetricsProto.ConnectionEvent.HLF_NONE,
                                WifiMetricsProto.ConnectionEvent.FAILURE_REASON_UNKNOWN);
                        replyToMessage(message, WifiManager.CONNECT_NETWORK_FAILED,
                                WifiManager.ERROR);
                        break;
                    }
                    break;
                case CMD_REMOVE_APP_CONFIGURATIONS:
                    removedNetworkIds =
                            mWifiConfigManager.removeNetworksForApp((ApplicationInfo) message.obj);
                    if (removedNetworkIds.contains(mTargetNetworkId)
                            || removedNetworkIds.contains(mLastNetworkId)) {
                        // Disconnect and let autojoin reselect a new network.
                        sendMessage(CMD_DISCONNECT);
                    }
                    break;
                case CMD_REMOVE_USER_CONFIGURATIONS:
                    removedNetworkIds =
                            mWifiConfigManager.removeNetworksForUser((Integer) message.arg1);
                    if (removedNetworkIds.contains(mTargetNetworkId)
                            || removedNetworkIds.contains(mLastNetworkId)) {
                        // Disconnect and let autojoin reselect a new network.
                        sendMessage(CMD_DISCONNECT);
                    }
                    break;
                case WifiManager.CONNECT_NETWORK:
                    /**
                     * The connect message can contain a network id passed as arg1 on message or
                     * or a config passed as obj on message.
                     * For a new network, a config is passed to create and connect.
                     * For an existing network, a network id is passed
                     */
                    netId = message.arg1;
                    config = (WifiConfiguration) message.obj;
                    boolean hasCredentialChanged = false;
                    // New network addition.
                    if (config != null) {
                        result = mWifiConfigManager.addOrUpdateNetwork(config, message.sendingUid);
                        if (!result.isSuccess()) {
                            loge("CONNECT_NETWORK adding/updating config=" + config + " failed");
                            mMessageHandlingStatus = MESSAGE_HANDLING_STATUS_FAIL;
                            replyToMessage(message, WifiManager.CONNECT_NETWORK_FAILED,
                                    WifiManager.ERROR);
                            break;
                        }
                        netId = result.getNetworkId();
                        hasCredentialChanged = result.hasCredentialChanged();
                    }
                    if (!connectToUserSelectNetwork(
                            netId, message.sendingUid, hasCredentialChanged)) {
                        mMessageHandlingStatus = MESSAGE_HANDLING_STATUS_FAIL;
                        replyToMessage(message, WifiManager.CONNECT_NETWORK_FAILED,
                                WifiManager.NOT_AUTHORIZED);
                        break;
                    }
                    mWifiMetrics.logStaEvent(StaEvent.TYPE_CONNECT_NETWORK, config);
                    broadcastWifiCredentialChanged(WifiManager.WIFI_CREDENTIAL_SAVED, config);
                    replyToMessage(message, WifiManager.CONNECT_NETWORK_SUCCEEDED);
                    break;
                case WifiManager.SAVE_NETWORK:
                    result = saveNetworkConfigAndSendReply(message);
                    netId = result.getNetworkId();
                    if (result.isSuccess() && mWifiInfo.getNetworkId() == netId) {
                        if (result.hasCredentialChanged()) {
                            config = (WifiConfiguration) message.obj;
                            // The network credentials changed and we're connected to this network,
                            // start a new connection with the updated credentials.
                            logi("SAVE_NETWORK credential changed for config=" + config.configKey()
                                    + ", Reconnecting.");
                            startConnectToNetwork(netId, message.sendingUid, SUPPLICANT_BSSID_ANY);
                        } else {
                            if (result.hasProxyChanged()) {
                                if (mIpClient != null) {
                                    log("Reconfiguring proxy on connection");
                                    mIpClient.setHttpProxy(
                                            getCurrentWifiConfiguration().getHttpProxy());
                                }
                            }
                            if (result.hasIpChanged()) {
                                // The current connection configuration was changed
                                // We switched from DHCP to static or from static to DHCP, or the
                                // static IP address has changed.
                                log("Reconfiguring IP on connection");
                                // TODO(b/36576642): clear addresses and disable IPv6
                                // to simplify obtainingIpState.
                                transitionTo(mObtainingIpState);
                            }
                        }
                    }
                    break;
                case WifiManager.FORGET_NETWORK:
                    if (!deleteNetworkConfigAndSendReply(message, true)) {
                        // Caller was notified of failure, nothing else to do
                        break;
                    }
                    // the network was deleted
                    netId = message.arg1;
                    if (netId == mTargetNetworkId || netId == mLastNetworkId) {
                        // Disconnect and let autojoin reselect a new network
                        sendMessage(CMD_DISCONNECT);
                    }
                    break;
                case CMD_ASSOCIATED_BSSID:
                    // This is where we can confirm the connection BSSID. Use it to find the
                    // right ScanDetail to populate metrics.
                    String someBssid = (String) message.obj;
                    if (someBssid != null) {
                        // Get the ScanDetail associated with this BSSID.
                        ScanDetailCache scanDetailCache =
                                mWifiConfigManager.getScanDetailCacheForNetwork(mTargetNetworkId);
                        if (scanDetailCache != null) {
                            mWifiMetrics.setConnectionScanDetail(scanDetailCache.getScanDetail(
                                    someBssid));
                        }
                    }
                    handleStatus = NOT_HANDLED;
                    break;
                case WifiMonitor.NETWORK_CONNECTION_EVENT:
                    if (mVerboseLoggingEnabled) log("Network connection established");
                    mLastNetworkId = message.arg1;
                    mWifiConfigManager.clearRecentFailureReason(mLastNetworkId);
                    mLastBssid = (String) message.obj;
                    reasonCode = message.arg2;
                    // TODO: This check should not be needed after ClientModeImpl refactor.
                    // Currently, the last connected network configuration is left in
                    // wpa_supplicant, this may result in wpa_supplicant initiating connection
                    // to it after a config store reload. Hence the old network Id lookups may not
                    // work, so disconnect the network and let network selector reselect a new
                    // network.
                    config = getCurrentWifiConfiguration();
                    if (config != null) {
                        mWifiInfo.setBSSID(mLastBssid);
                        mWifiInfo.setNetworkId(mLastNetworkId);
                        mWifiInfo.setMacAddress(mWifiNative.getMacAddress(mInterfaceName));

                        ScanDetailCache scanDetailCache =
                                mWifiConfigManager.getScanDetailCacheForNetwork(config.networkId);
                        if (scanDetailCache != null && mLastBssid != null) {
                            ScanResult scanResult = scanDetailCache.getScanResult(mLastBssid);
                            if (scanResult != null) {
                                mWifiInfo.setFrequency(scanResult.frequency);
                            }
                        }
                        mWifiConnectivityManager.trackBssid(mLastBssid, true, reasonCode);

                        // We need to get the updated pseudonym from supplicant for EAP-SIM/AKA/AKA'
                        if (config.enterpriseConfig != null
                                && TelephonyUtil.isSimEapMethod(
                                        config.enterpriseConfig.getEapMethod())
                                // if using anonymous@<realm>, do not use pseudonym identity on
                                // reauthentication. Instead, use full authentication using
                                // anonymous@<realm> followed by encrypted IMSI every time.
                                // This is because the encrypted IMSI spec does not specify its
                                // compatibility with the pseudonym identity specified by EAP-AKA.
                                && !TelephonyUtil.isAnonymousAtRealmIdentity(
                                        config.enterpriseConfig.getAnonymousIdentity())) {
                            String anonymousIdentity =
                                    mWifiNative.getEapAnonymousIdentity(mInterfaceName);
                            if (mVerboseLoggingEnabled) {
                                log("EAP Pseudonym: " + anonymousIdentity);
                            }
                            config.enterpriseConfig.setAnonymousIdentity(anonymousIdentity);
                            mWifiConfigManager.addOrUpdateNetwork(config, Process.WIFI_UID);
                        }
                        sendNetworkStateChangeBroadcast(mLastBssid);
                        transitionTo(mObtainingIpState);
                    } else {
                        logw("Connected to unknown networkId " + mLastNetworkId
                                + ", disconnecting...");
                        sendMessage(CMD_DISCONNECT);
                    }
                    break;
                case WifiMonitor.NETWORK_DISCONNECTION_EVENT:
                    // Calling handleNetworkDisconnect here is redundant because we might already
                    // have called it when leaving L2ConnectedState to go to disconnecting state
                    // or thru other path
                    // We should normally check the mWifiInfo or mLastNetworkId so as to check
                    // if they are valid, and only in this case call handleNEtworkDisconnect,
                    // TODO: this should be fixed for a L MR release
                    // The side effect of calling handleNetworkDisconnect twice is that a bunch of
                    // idempotent commands are executed twice (stopping Dhcp, enabling the SPS mode
                    // at the chip etc...
                    if (mVerboseLoggingEnabled) log("ConnectModeState: Network connection lost ");
                    handleNetworkDisconnect();
                    transitionTo(mDisconnectedState);
                    break;
                case CMD_QUERY_OSU_ICON:
                    mPasspointManager.queryPasspointIcon(
                            ((Bundle) message.obj).getLong(EXTRA_OSU_ICON_QUERY_BSSID),
                            ((Bundle) message.obj).getString(EXTRA_OSU_ICON_QUERY_FILENAME));
                    break;
                case CMD_MATCH_PROVIDER_NETWORK:
                    // TODO(b/31065385): Passpoint config management.
                    replyToMessage(message, message.what, 0);
                    break;
                case CMD_ADD_OR_UPDATE_PASSPOINT_CONFIG:
                    Bundle bundle = (Bundle) message.obj;
                    PasspointConfiguration passpointConfig = bundle.getParcelable(
                            EXTRA_PASSPOINT_CONFIGURATION);
                    if (mPasspointManager.addOrUpdateProvider(passpointConfig,
                            bundle.getInt(EXTRA_UID),
                            bundle.getString(EXTRA_PACKAGE_NAME))) {
                        String fqdn = passpointConfig.getHomeSp().getFqdn();
                        if (isProviderOwnedNetwork(mTargetNetworkId, fqdn)
                                || isProviderOwnedNetwork(mLastNetworkId, fqdn)) {
                            logd("Disconnect from current network since its provider is updated");
                            sendMessage(CMD_DISCONNECT);
                        }
                        replyToMessage(message, message.what, SUCCESS);
                    } else {
                        replyToMessage(message, message.what, FAILURE);
                    }
                    break;
                case CMD_REMOVE_PASSPOINT_CONFIG:
                    String fqdn = (String) message.obj;
                    if (mPasspointManager.removeProvider(fqdn)) {
                        if (isProviderOwnedNetwork(mTargetNetworkId, fqdn)
                                || isProviderOwnedNetwork(mLastNetworkId, fqdn)) {
                            logd("Disconnect from current network since its provider is removed");
                            sendMessage(CMD_DISCONNECT);
                        }
                        mWifiConfigManager.removePasspointConfiguredNetwork(fqdn);
                        replyToMessage(message, message.what, SUCCESS);
                    } else {
                        replyToMessage(message, message.what, FAILURE);
                    }
                    break;
                case CMD_GET_ALL_MATCHING_FQDNS_FOR_SCAN_RESULTS:
                    replyToMessage(message, message.what,
                            mPasspointManager.getAllMatchingFqdnsForScanResults(
                                    (List<ScanResult>) message.obj));
                    break;
                case CMD_TARGET_BSSID:
                    // Trying to associate to this BSSID
                    if (message.obj != null) {
                        mTargetRoamBSSID = (String) message.obj;
                    }
                    break;
                case CMD_GET_LINK_LAYER_STATS:
                    WifiLinkLayerStats stats = getWifiLinkLayerStats();
                    replyToMessage(message, message.what, stats);
                    break;
                case CMD_RESET_SIM_NETWORKS:
                    log("resetting EAP-SIM/AKA/AKA' networks since SIM was changed");
                    boolean simPresent = message.arg1 == 1;
                    if (!simPresent) {
                        mPasspointManager.removeEphemeralProviders();
                        mWifiConfigManager.resetSimNetworks();
                    }
                    break;
                case CMD_BLUETOOTH_ADAPTER_STATE_CHANGE:
                    mBluetoothConnectionActive = (message.arg1
                            != BluetoothAdapter.STATE_DISCONNECTED);
                    mWifiNative.setBluetoothCoexistenceScanMode(
                            mInterfaceName, mBluetoothConnectionActive);
                    break;
                case CMD_SET_SUSPEND_OPT_ENABLED:
                    if (message.arg1 == 1) {
                        setSuspendOptimizationsNative(SUSPEND_DUE_TO_SCREEN, true);
                        if (message.arg2 == 1) {
                            mSuspendWakeLock.release();
                        }
                    } else {
                        setSuspendOptimizationsNative(SUSPEND_DUE_TO_SCREEN, false);
                    }
                    break;
                case CMD_SET_HIGH_PERF_MODE:
                    if (message.arg1 == 1) {
                        setSuspendOptimizationsNative(SUSPEND_DUE_TO_HIGH_PERF, false);
                    } else {
                        setSuspendOptimizationsNative(SUSPEND_DUE_TO_HIGH_PERF, true);
                    }
                    break;
                case CMD_ENABLE_TDLS:
                    if (message.obj != null) {
                        String remoteAddress = (String) message.obj;
                        boolean enable = (message.arg1 == 1);
                        mWifiNative.startTdls(mInterfaceName, remoteAddress, enable);
                    }
                    break;
                case WifiMonitor.ANQP_DONE_EVENT:
                    // TODO(zqiu): remove this when switch over to wificond for ANQP requests.
                    mPasspointManager.notifyANQPDone((AnqpEvent) message.obj);
                    break;
                case CMD_STOP_IP_PACKET_OFFLOAD: {
                    int slot = message.arg1;
                    int ret = stopWifiIPPacketOffload(slot);
                    if (mNetworkAgent != null) {
                        mNetworkAgent.onSocketKeepaliveEvent(slot, ret);
                    }
                    break;
                }
                case WifiMonitor.RX_HS20_ANQP_ICON_EVENT:
                    // TODO(zqiu): remove this when switch over to wificond for icon requests.
                    mPasspointManager.notifyIconDone((IconEvent) message.obj);
                    break;
                case WifiMonitor.HS20_REMEDIATION_EVENT:
                    // TODO(zqiu): remove this when switch over to wificond for WNM frames
                    // monitoring.
                    mPasspointManager.receivedWnmFrame((WnmData) message.obj);
                    break;
                case CMD_CONFIG_ND_OFFLOAD:
                    final boolean enabled = (message.arg1 > 0);
                    mWifiNative.configureNeighborDiscoveryOffload(mInterfaceName, enabled);
                    break;
                case CMD_ENABLE_WIFI_CONNECTIVITY_MANAGER:
                    mWifiConnectivityManager.enable(message.arg1 == 1 ? true : false);
                    break;
                default:
                    handleStatus = NOT_HANDLED;
                    break;
            }

            if (handleStatus == HANDLED) {
                logStateAndMessage(message, this);
            }

            return handleStatus;
        }
    }

    private WifiNetworkAgentSpecifier createNetworkAgentSpecifier(
            @NonNull WifiConfiguration currentWifiConfiguration, @Nullable String currentBssid,
            int specificRequestUid, @NonNull String specificRequestPackageName) {
        currentWifiConfiguration.BSSID = currentBssid;
        WifiNetworkAgentSpecifier wns =
                new WifiNetworkAgentSpecifier(currentWifiConfiguration, specificRequestUid,
                        specificRequestPackageName);
        return wns;
    }

    private NetworkCapabilities getCapabilities(WifiConfiguration currentWifiConfiguration) {
        final NetworkCapabilities result = new NetworkCapabilities(mNetworkCapabilitiesFilter);
        // MatchAllNetworkSpecifier set in the mNetworkCapabilitiesFilter should never be set in the
        // agent's specifier.
        result.setNetworkSpecifier(null);
        if (currentWifiConfiguration == null) {
            return result;
        }

        if (!mWifiInfo.isTrusted()) {
            result.removeCapability(NetworkCapabilities.NET_CAPABILITY_TRUSTED);
        } else {
            result.addCapability(NetworkCapabilities.NET_CAPABILITY_TRUSTED);
        }

        if (!WifiConfiguration.isMetered(currentWifiConfiguration, mWifiInfo)) {
            result.addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
        } else {
            result.removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
        }

        if (mWifiInfo.getRssi() != WifiInfo.INVALID_RSSI) {
            result.setSignalStrength(mWifiInfo.getRssi());
        } else {
            result.setSignalStrength(NetworkCapabilities.SIGNAL_STRENGTH_UNSPECIFIED);
        }

        if (currentWifiConfiguration.osu) {
            result.removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        }

        if (!mWifiInfo.getSSID().equals(WifiSsid.NONE)) {
            result.setSSID(mWifiInfo.getSSID());
        } else {
            result.setSSID(null);
        }
        Pair<Integer, String> specificRequestUidAndPackageName =
                mNetworkFactory.getSpecificNetworkRequestUidAndPackageName(
                        currentWifiConfiguration);
        // There is an active specific request.
        if (specificRequestUidAndPackageName.first != Process.INVALID_UID) {
            // Remove internet capability.
            result.removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        }
        // Fill up the network agent specifier for this connection.
        result.setNetworkSpecifier(
                createNetworkAgentSpecifier(
                        currentWifiConfiguration, getCurrentBSSID(),
                        specificRequestUidAndPackageName.first,
                        specificRequestUidAndPackageName.second));
        return result;
    }

    /**
     * Method to update network capabilities from the current WifiConfiguration.
     */
    public void updateCapabilities() {
        updateCapabilities(getCurrentWifiConfiguration());
    }

    private void updateCapabilities(WifiConfiguration currentWifiConfiguration) {
        if (mNetworkAgent == null) {
            return;
        }
        mNetworkAgent.sendNetworkCapabilities(getCapabilities(currentWifiConfiguration));
    }

    /**
     * Checks if the given network |networkdId| is provided by the given Passpoint provider with
     * |providerFqdn|.
     *
     * @param networkId The ID of the network to check
     * @param providerFqdn The FQDN of the Passpoint provider
     * @return true if the given network is provided by the given Passpoint provider
     */
    private boolean isProviderOwnedNetwork(int networkId, String providerFqdn) {
        if (networkId == WifiConfiguration.INVALID_NETWORK_ID) {
            return false;
        }
        WifiConfiguration config = mWifiConfigManager.getConfiguredNetwork(networkId);
        if (config == null) {
            return false;
        }
        return TextUtils.equals(config.FQDN, providerFqdn);
    }

    private void handleEapAuthFailure(int networkId, int errorCode) {
        WifiConfiguration targetedNetwork =
                mWifiConfigManager.getConfiguredNetwork(mTargetNetworkId);
        if (targetedNetwork != null) {
            switch (targetedNetwork.enterpriseConfig.getEapMethod()) {
                case WifiEnterpriseConfig.Eap.SIM:
                case WifiEnterpriseConfig.Eap.AKA:
                case WifiEnterpriseConfig.Eap.AKA_PRIME:
                    if (errorCode == WifiNative.EAP_SIM_VENDOR_SPECIFIC_CERT_EXPIRED) {
                        getTelephonyManager()
                                .createForSubscriptionId(
                                        SubscriptionManager.getDefaultDataSubscriptionId())
                                .resetCarrierKeysForImsiEncryption();
                    }
                    break;

                default:
                    // Do Nothing
            }
        }
    }

    private class WifiNetworkAgent extends NetworkAgent {
        WifiNetworkAgent(Looper l, Context c, String tag, NetworkInfo ni,
                NetworkCapabilities nc, LinkProperties lp, int score, NetworkMisc misc) {
            super(l, c, tag, ni, nc, lp, score, misc);
        }
        private int mLastNetworkStatus = -1; // To detect when the status really changes

        @Override
        protected void unwanted() {
            // Ignore if we're not the current networkAgent.
            if (this != mNetworkAgent) return;
            if (mVerboseLoggingEnabled) {
                log("WifiNetworkAgent -> Wifi unwanted score " + Integer.toString(mWifiInfo.score));
            }
            unwantedNetwork(NETWORK_STATUS_UNWANTED_DISCONNECT);
        }

        @Override
        protected void networkStatus(int status, String redirectUrl) {
            if (this != mNetworkAgent) return;
            if (status == mLastNetworkStatus) return;
            mLastNetworkStatus = status;
            if (status == NetworkAgent.INVALID_NETWORK) {
                if (mVerboseLoggingEnabled) {
                    log("WifiNetworkAgent -> Wifi networkStatus invalid, score="
                            + Integer.toString(mWifiInfo.score));
                }
                unwantedNetwork(NETWORK_STATUS_UNWANTED_VALIDATION_FAILED);
            } else if (status == NetworkAgent.VALID_NETWORK) {
                if (mVerboseLoggingEnabled) {
                    log("WifiNetworkAgent -> Wifi networkStatus valid, score= "
                            + Integer.toString(mWifiInfo.score));
                }
                mWifiMetrics.logStaEvent(StaEvent.TYPE_NETWORK_AGENT_VALID_NETWORK);
                doNetworkStatus(status);
            }
        }

        @Override
        protected void saveAcceptUnvalidated(boolean accept) {
            if (this != mNetworkAgent) return;
            ClientModeImpl.this.sendMessage(CMD_ACCEPT_UNVALIDATED, accept ? 1 : 0);
        }

        @Override
        protected void startSocketKeepalive(Message msg) {
            ClientModeImpl.this.sendMessage(
                    CMD_START_IP_PACKET_OFFLOAD, msg.arg1, msg.arg2, msg.obj);
        }

        @Override
        protected void stopSocketKeepalive(Message msg) {
            ClientModeImpl.this.sendMessage(
                    CMD_STOP_IP_PACKET_OFFLOAD, msg.arg1, msg.arg2, msg.obj);
        }

        @Override
        protected void addKeepalivePacketFilter(Message msg) {
            ClientModeImpl.this.sendMessage(
                    CMD_ADD_KEEPALIVE_PACKET_FILTER_TO_APF, msg.arg1, msg.arg2, msg.obj);
        }

        @Override
        protected void removeKeepalivePacketFilter(Message msg) {
            ClientModeImpl.this.sendMessage(
                    CMD_REMOVE_KEEPALIVE_PACKET_FILTER_FROM_APF, msg.arg1, msg.arg2, msg.obj);
        }

        @Override
        protected void setSignalStrengthThresholds(int[] thresholds) {
            // 0. If there are no thresholds, or if the thresholds are invalid,
            //    stop RSSI monitoring.
            // 1. Tell the hardware to start RSSI monitoring here, possibly adding MIN_VALUE and
            //    MAX_VALUE at the start/end of the thresholds array if necessary.
            // 2. Ensure that when the hardware event fires, we fetch the RSSI from the hardware
            //    event, call mWifiInfo.setRssi() with it, and call updateCapabilities(), and then
            //    re-arm the hardware event. This needs to be done on the state machine thread to
            //    avoid race conditions. The RSSI used to re-arm the event (and perhaps also the one
            //    sent in the NetworkCapabilities) must be the one received from the hardware event
            //    received, or we might skip callbacks.
            // 3. Ensure that when we disconnect, RSSI monitoring is stopped.
            log("Received signal strength thresholds: " + Arrays.toString(thresholds));
            if (thresholds.length == 0) {
                ClientModeImpl.this.sendMessage(CMD_STOP_RSSI_MONITORING_OFFLOAD,
                        mWifiInfo.getRssi());
                return;
            }
            int [] rssiVals = Arrays.copyOf(thresholds, thresholds.length + 2);
            rssiVals[rssiVals.length - 2] = Byte.MIN_VALUE;
            rssiVals[rssiVals.length - 1] = Byte.MAX_VALUE;
            Arrays.sort(rssiVals);
            byte[] rssiRange = new byte[rssiVals.length];
            for (int i = 0; i < rssiVals.length; i++) {
                int val = rssiVals[i];
                if (val <= Byte.MAX_VALUE && val >= Byte.MIN_VALUE) {
                    rssiRange[i] = (byte) val;
                } else {
                    Log.e(TAG, "Illegal value " + val + " for RSSI thresholds: "
                            + Arrays.toString(rssiVals));
                    ClientModeImpl.this.sendMessage(CMD_STOP_RSSI_MONITORING_OFFLOAD,
                            mWifiInfo.getRssi());
                    return;
                }
            }
            // TODO: Do we quash rssi values in this sorted array which are very close?
            mRssiRanges = rssiRange;
            ClientModeImpl.this.sendMessage(CMD_START_RSSI_MONITORING_OFFLOAD,
                    mWifiInfo.getRssi());
        }

        @Override
        protected void preventAutomaticReconnect() {
            if (this != mNetworkAgent) return;
            unwantedNetwork(NETWORK_STATUS_UNWANTED_DISABLE_AUTOJOIN);
        }
    }

    void unwantedNetwork(int reason) {
        sendMessage(CMD_UNWANTED_NETWORK, reason);
    }

    void doNetworkStatus(int status) {
        sendMessage(CMD_NETWORK_STATUS, status);
    }

    // rfc4186 & rfc4187:
    // create Permanent Identity base on IMSI,
    // identity = usernam@realm
    // with username = prefix | IMSI
    // and realm is derived MMC/MNC tuple according 3GGP spec(TS23.003)
    private String buildIdentity(int eapMethod, String imsi, String mccMnc) {
        String mcc;
        String mnc;
        String prefix;

        if (imsi == null || imsi.isEmpty()) {
            return "";
        }

        if (eapMethod == WifiEnterpriseConfig.Eap.SIM) {
            prefix = "1";
        } else if (eapMethod == WifiEnterpriseConfig.Eap.AKA) {
            prefix = "0";
        } else if (eapMethod == WifiEnterpriseConfig.Eap.AKA_PRIME) {
            prefix = "6";
        } else {
            // not a valid EapMethod
            return "";
        }

        /* extract mcc & mnc from mccMnc */
        if (mccMnc != null && !mccMnc.isEmpty()) {
            mcc = mccMnc.substring(0, 3);
            mnc = mccMnc.substring(3);
            if (mnc.length() == 2) {
                mnc = "0" + mnc;
            }
        } else {
            // extract mcc & mnc from IMSI, assume mnc size is 3
            mcc = imsi.substring(0, 3);
            mnc = imsi.substring(3, 6);
        }

        return prefix + imsi + "@wlan.mnc" + mnc + ".mcc" + mcc + ".3gppnetwork.org";
    }

    class L2ConnectedState extends State {
        class RssiEventHandler implements WifiNative.WifiRssiEventHandler {
            @Override
            public void onRssiThresholdBreached(byte curRssi) {
                if (mVerboseLoggingEnabled) {
                    Log.e(TAG, "onRssiThresholdBreach event. Cur Rssi = " + curRssi);
                }
                sendMessage(CMD_RSSI_THRESHOLD_BREACHED, curRssi);
            }
        }

        RssiEventHandler mRssiEventHandler = new RssiEventHandler();

        @Override
        public void enter() {
            mRssiPollToken++;
            if (mEnableRssiPolling) {
                mLinkProbeManager.resetOnNewConnection();
                sendMessage(CMD_RSSI_POLL, mRssiPollToken, 0);
            }
            if (mNetworkAgent != null) {
                loge("Have NetworkAgent when entering L2Connected");
                setNetworkDetailedState(DetailedState.DISCONNECTED);
            }
            setNetworkDetailedState(DetailedState.CONNECTING);

            final NetworkCapabilities nc = getCapabilities(getCurrentWifiConfiguration());
            synchronized (mNetworkAgentLock) {
                mNetworkAgent = new WifiNetworkAgent(getHandler().getLooper(), mContext,
                    "WifiNetworkAgent", mNetworkInfo, nc, mLinkProperties, 60, mNetworkMisc);
            }

            // We must clear the config BSSID, as the wifi chipset may decide to roam
            // from this point on and having the BSSID specified in the network block would
            // cause the roam to faile and the device to disconnect
            clearTargetBssid("L2ConnectedState");
            mCountryCode.setReadyForChange(false);
            mWifiMetrics.setWifiState(WifiMetricsProto.WifiLog.WIFI_ASSOCIATED);
            mWifiScoreCard.noteNetworkAgentCreated(mWifiInfo, mNetworkAgent.netId);
        }

        @Override
        public void exit() {
            if (mIpClient != null) {
                mIpClient.stop();
            }

            // This is handled by receiving a NETWORK_DISCONNECTION_EVENT in ConnectModeState
            // Bug: 15347363
            // For paranoia's sake, call handleNetworkDisconnect
            // only if BSSID is null or last networkId
            // is not invalid.
            if (mVerboseLoggingEnabled) {
                StringBuilder sb = new StringBuilder();
                sb.append("leaving L2ConnectedState state nid=" + Integer.toString(mLastNetworkId));
                if (mLastBssid != null) {
                    sb.append(" ").append(mLastBssid);
                }
            }
            if (mLastBssid != null || mLastNetworkId != WifiConfiguration.INVALID_NETWORK_ID) {
                handleNetworkDisconnect();
            }
            mCountryCode.setReadyForChange(true);
            mWifiMetrics.setWifiState(WifiMetricsProto.WifiLog.WIFI_DISCONNECTED);
            mWifiStateTracker.updateState(WifiStateTracker.DISCONNECTED);
            //Inform WifiLockManager
            WifiLockManager wifiLockManager = mWifiInjector.getWifiLockManager();
            wifiLockManager.updateWifiClientConnected(false);
        }

        @Override
        public boolean processMessage(Message message) {
            boolean handleStatus = HANDLED;

            switch (message.what) {
                case CMD_PRE_DHCP_ACTION:
                    handlePreDhcpSetup();
                    break;
                case CMD_PRE_DHCP_ACTION_COMPLETE:
                    if (mIpClient != null) {
                        mIpClient.completedPreDhcpAction();
                    }
                    break;
                case CMD_POST_DHCP_ACTION:
                    handlePostDhcpSetup();
                    // We advance to mConnectedState because IpClient will also send a
                    // CMD_IPV4_PROVISIONING_SUCCESS message, which calls handleIPv4Success(),
                    // which calls updateLinkProperties, which then sends
                    // CMD_IP_CONFIGURATION_SUCCESSFUL.
                    //
                    // In the event of failure, we transition to mDisconnectingState
                    // similarly--via messages sent back from IpClient.
                    break;
                case CMD_IPV4_PROVISIONING_SUCCESS: {
                    handleIPv4Success((DhcpResults) message.obj);
                    sendNetworkStateChangeBroadcast(mLastBssid);
                    break;
                }
                case CMD_IPV4_PROVISIONING_FAILURE: {
                    handleIPv4Failure();
                    mWifiInjector.getWifiLastResortWatchdog()
                            .noteConnectionFailureAndTriggerIfNeeded(
                                    getTargetSsid(), mTargetRoamBSSID,
                                    WifiLastResortWatchdog.FAILURE_CODE_DHCP);
                    break;
                }
                case CMD_IP_CONFIGURATION_SUCCESSFUL:
                    if (getCurrentWifiConfiguration() == null) {
                        // The current config may have been removed while we were connecting,
                        // trigger a disconnect to clear up state.
                        reportConnectionAttemptEnd(
                                WifiMetrics.ConnectionEvent.FAILURE_NETWORK_DISCONNECTION,
                                WifiMetricsProto.ConnectionEvent.HLF_NONE,
                                WifiMetricsProto.ConnectionEvent.FAILURE_REASON_UNKNOWN);
                        mWifiNative.disconnect(mInterfaceName);
                        transitionTo(mDisconnectingState);
                    } else {
                        handleSuccessfulIpConfiguration();
                        sendConnectedState();
                        transitionTo(mConnectedState);
                    }
                    break;
                case CMD_IP_CONFIGURATION_LOST:
                    // Get Link layer stats so that we get fresh tx packet counters.
                    getWifiLinkLayerStats();
                    handleIpConfigurationLost();
                    reportConnectionAttemptEnd(
                            WifiMetrics.ConnectionEvent.FAILURE_DHCP,
                            WifiMetricsProto.ConnectionEvent.HLF_NONE,
                            WifiMetricsProto.ConnectionEvent.FAILURE_REASON_UNKNOWN);
                    mWifiInjector.getWifiLastResortWatchdog()
                            .noteConnectionFailureAndTriggerIfNeeded(
                                    getTargetSsid(), mTargetRoamBSSID,
                                    WifiLastResortWatchdog.FAILURE_CODE_DHCP);
                    transitionTo(mDisconnectingState);
                    break;
                case CMD_IP_REACHABILITY_LOST:
                    if (mVerboseLoggingEnabled && message.obj != null) log((String) message.obj);
                    mWifiDiagnostics.captureBugReportData(
                            WifiDiagnostics.REPORT_REASON_REACHABILITY_LOST);
                    mWifiMetrics.logWifiIsUnusableEvent(
                            WifiIsUnusableEvent.TYPE_IP_REACHABILITY_LOST);
                    mWifiMetrics.addToWifiUsabilityStatsList(WifiUsabilityStats.LABEL_BAD,
                            WifiUsabilityStats.TYPE_IP_REACHABILITY_LOST, -1);
                    if (mIpReachabilityDisconnectEnabled) {
                        handleIpReachabilityLost();
                        transitionTo(mDisconnectingState);
                    } else {
                        logd("CMD_IP_REACHABILITY_LOST but disconnect disabled -- ignore");
                    }
                    break;
                case CMD_DISCONNECT:
                    mWifiMetrics.logStaEvent(StaEvent.TYPE_FRAMEWORK_DISCONNECT,
                            StaEvent.DISCONNECT_GENERIC);
                    mWifiNative.disconnect(mInterfaceName);
                    transitionTo(mDisconnectingState);
                    break;
                case WifiP2pServiceImpl.DISCONNECT_WIFI_REQUEST:
                    if (message.arg1 == 1) {
                        mWifiMetrics.logStaEvent(StaEvent.TYPE_FRAMEWORK_DISCONNECT,
                                StaEvent.DISCONNECT_P2P_DISCONNECT_WIFI_REQUEST);
                        mWifiNative.disconnect(mInterfaceName);
                        mTemporarilyDisconnectWifi = true;
                        transitionTo(mDisconnectingState);
                    }
                    break;
                    /* Ignore connection to same network */
                case WifiManager.CONNECT_NETWORK:
                    int netId = message.arg1;
                    if (mWifiInfo.getNetworkId() == netId) {
                        replyToMessage(message, WifiManager.CONNECT_NETWORK_SUCCEEDED);
                        break;
                    }
                    handleStatus = NOT_HANDLED;
                    break;
                case WifiMonitor.NETWORK_CONNECTION_EVENT:
                    mWifiInfo.setBSSID((String) message.obj);
                    mLastNetworkId = message.arg1;
                    mWifiInfo.setNetworkId(mLastNetworkId);
                    mWifiInfo.setMacAddress(mWifiNative.getMacAddress(mInterfaceName));
                    if (!mLastBssid.equals(message.obj)) {
                        mLastBssid = (String) message.obj;
                        sendNetworkStateChangeBroadcast(mLastBssid);
                    }
                    break;
                case CMD_ONESHOT_RSSI_POLL:
                    if (!mEnableRssiPolling) {
                        updateLinkLayerStatsRssiAndScoreReportInternal();
                    }
                    break;
                case CMD_RSSI_POLL:
                    if (message.arg1 == mRssiPollToken) {
                        WifiLinkLayerStats stats = updateLinkLayerStatsRssiAndScoreReportInternal();
                        mWifiMetrics.updateWifiUsabilityStatsEntries(mWifiInfo, stats);
                        if (mWifiScoreReport.shouldCheckIpLayer()) {
                            if (mIpClient != null) {
                                mIpClient.confirmConfiguration();
                            }
                            mWifiScoreReport.noteIpCheck();
                        }
                        int statusDataStall =
                                mWifiDataStall.checkForDataStall(mLastLinkLayerStats, stats);
                        if (statusDataStall != WifiIsUnusableEvent.TYPE_UNKNOWN) {
                            mWifiMetrics.addToWifiUsabilityStatsList(WifiUsabilityStats.LABEL_BAD,
                                    convertToUsabilityStatsTriggerType(statusDataStall), -1);
                        }
                        mWifiMetrics.incrementWifiLinkLayerUsageStats(stats);
                        mLastLinkLayerStats = stats;
                        mWifiScoreCard.noteSignalPoll(mWifiInfo);
                        mLinkProbeManager.updateConnectionStats(
                                mWifiInfo, mInterfaceName);
                        sendMessageDelayed(obtainMessage(CMD_RSSI_POLL, mRssiPollToken, 0),
                                mPollRssiIntervalMsecs);
                        if (mVerboseLoggingEnabled) sendRssiChangeBroadcast(mWifiInfo.getRssi());
                        mWifiTrafficPoller.notifyOnDataActivity(mWifiInfo.txSuccess,
                                mWifiInfo.rxSuccess);
                    } else {
                        // Polling has completed
                    }
                    break;
                case CMD_ENABLE_RSSI_POLL:
                    cleanWifiScore();
                    mEnableRssiPolling = (message.arg1 == 1);
                    mRssiPollToken++;
                    if (mEnableRssiPolling) {
                        // First poll
                        mLastSignalLevel = -1;
                        mLinkProbeManager.resetOnScreenTurnedOn();
                        fetchRssiLinkSpeedAndFrequencyNative();
                        sendMessageDelayed(obtainMessage(CMD_RSSI_POLL, mRssiPollToken, 0),
                                mPollRssiIntervalMsecs);
                    }
                    break;
                case WifiManager.RSSI_PKTCNT_FETCH:
                    RssiPacketCountInfo info = new RssiPacketCountInfo();
                    fetchRssiLinkSpeedAndFrequencyNative();
                    info.rssi = mWifiInfo.getRssi();
                    WifiNative.TxPacketCounters counters =
                            mWifiNative.getTxPacketCounters(mInterfaceName);
                    if (counters != null) {
                        info.txgood = counters.txSucceeded;
                        info.txbad = counters.txFailed;
                        replyToMessage(message, WifiManager.RSSI_PKTCNT_FETCH_SUCCEEDED, info);
                    } else {
                        replyToMessage(message,
                                WifiManager.RSSI_PKTCNT_FETCH_FAILED, WifiManager.ERROR);
                    }
                    break;
                case CMD_ASSOCIATED_BSSID:
                    if ((String) message.obj == null) {
                        logw("Associated command w/o BSSID");
                        break;
                    }
                    mLastBssid = (String) message.obj;
                    if (mLastBssid != null && (mWifiInfo.getBSSID() == null
                            || !mLastBssid.equals(mWifiInfo.getBSSID()))) {
                        mWifiInfo.setBSSID(mLastBssid);
                        WifiConfiguration config = getCurrentWifiConfiguration();
                        if (config != null) {
                            ScanDetailCache scanDetailCache = mWifiConfigManager
                                    .getScanDetailCacheForNetwork(config.networkId);
                            if (scanDetailCache != null) {
                                ScanResult scanResult = scanDetailCache.getScanResult(mLastBssid);
                                if (scanResult != null) {
                                    mWifiInfo.setFrequency(scanResult.frequency);
                                }
                            }
                        }
                        sendNetworkStateChangeBroadcast(mLastBssid);
                    }
                    break;
                case CMD_START_RSSI_MONITORING_OFFLOAD:
                case CMD_RSSI_THRESHOLD_BREACHED:
                    byte currRssi = (byte) message.arg1;
                    processRssiThreshold(currRssi, message.what, mRssiEventHandler);
                    break;
                case CMD_STOP_RSSI_MONITORING_OFFLOAD:
                    stopRssiMonitoringOffload();
                    break;
                case CMD_RECONNECT:
                    log(" Ignore CMD_RECONNECT request because wifi is already connected");
                    break;
                case CMD_RESET_SIM_NETWORKS:
                    if (message.arg1 == 0 // sim was removed
                            && mLastNetworkId != WifiConfiguration.INVALID_NETWORK_ID) {
                        WifiConfiguration config =
                                mWifiConfigManager.getConfiguredNetwork(mLastNetworkId);
                        if (TelephonyUtil.isSimConfig(config)) {
                            mWifiMetrics.logStaEvent(StaEvent.TYPE_FRAMEWORK_DISCONNECT,
                                    StaEvent.DISCONNECT_RESET_SIM_NETWORKS);
                            // TODO(b/132385576): STA may immediately connect back to the network
                            //  that we just disconnected from
                            mWifiNative.disconnect(mInterfaceName);
                            transitionTo(mDisconnectingState);
                        }
                    }
                    /* allow parent state to reset data for other networks */
                    handleStatus = NOT_HANDLED;
                    break;
                case CMD_START_IP_PACKET_OFFLOAD: {
                    int slot = message.arg1;
                    int intervalSeconds = message.arg2;
                    KeepalivePacketData pkt = (KeepalivePacketData) message.obj;
                    int result = startWifiIPPacketOffload(slot, pkt, intervalSeconds);
                    if (mNetworkAgent != null) {
                        mNetworkAgent.onSocketKeepaliveEvent(slot, result);
                    }
                    break;
                }
                case CMD_ADD_KEEPALIVE_PACKET_FILTER_TO_APF: {
                    if (mIpClient != null) {
                        final int slot = message.arg1;
                        if (message.obj instanceof NattKeepalivePacketData) {
                            final NattKeepalivePacketData pkt =
                                    (NattKeepalivePacketData) message.obj;
                            mIpClient.addKeepalivePacketFilter(slot, pkt);
                        } else if (message.obj instanceof TcpKeepalivePacketData) {
                            final TcpKeepalivePacketData pkt =
                                    (TcpKeepalivePacketData) message.obj;
                            mIpClient.addKeepalivePacketFilter(slot, pkt);
                        }
                    }
                    break;
                }
                case CMD_REMOVE_KEEPALIVE_PACKET_FILTER_FROM_APF: {
                    if (mIpClient != null) {
                        mIpClient.removeKeepalivePacketFilter(message.arg1);
                    }
                    break;
                }
                default:
                    handleStatus = NOT_HANDLED;
                    break;
            }

            if (handleStatus == HANDLED) {
                logStateAndMessage(message, this);
            }

            return handleStatus;
        }

        /**
         * Fetches link stats and updates Wifi Score Report.
         */
        private WifiLinkLayerStats updateLinkLayerStatsRssiAndScoreReportInternal() {
            WifiLinkLayerStats stats = getWifiLinkLayerStats();
            // Get Info and continue polling
            fetchRssiLinkSpeedAndFrequencyNative();
            // Send the update score to network agent.
            mWifiScoreReport.calculateAndReportScore(mWifiInfo, mNetworkAgent, mWifiMetrics);
            return stats;
        }
    }

    /**
     * Fetches link stats and updates Wifi Score Report.
     */
    public void updateLinkLayerStatsRssiAndScoreReport() {
        sendMessage(CMD_ONESHOT_RSSI_POLL);
    }

    private static int convertToUsabilityStatsTriggerType(int unusableEventTriggerType) {
        int triggerType;
        switch (unusableEventTriggerType) {
            case WifiIsUnusableEvent.TYPE_DATA_STALL_BAD_TX:
                triggerType = WifiUsabilityStats.TYPE_DATA_STALL_BAD_TX;
                break;
            case WifiIsUnusableEvent.TYPE_DATA_STALL_TX_WITHOUT_RX:
                triggerType = WifiUsabilityStats.TYPE_DATA_STALL_TX_WITHOUT_RX;
                break;
            case WifiIsUnusableEvent.TYPE_DATA_STALL_BOTH:
                triggerType = WifiUsabilityStats.TYPE_DATA_STALL_BOTH;
                break;
            case WifiIsUnusableEvent.TYPE_FIRMWARE_ALERT:
                triggerType = WifiUsabilityStats.TYPE_FIRMWARE_ALERT;
                break;
            case WifiIsUnusableEvent.TYPE_IP_REACHABILITY_LOST:
                triggerType = WifiUsabilityStats.TYPE_IP_REACHABILITY_LOST;
                break;
            default:
                triggerType = WifiUsabilityStats.TYPE_UNKNOWN;
                Log.e(TAG, "Unknown WifiIsUnusableEvent: " + unusableEventTriggerType);
        }
        return triggerType;
    }

    class ObtainingIpState extends State {
        @Override
        public void enter() {
            final WifiConfiguration currentConfig = getCurrentWifiConfiguration();
            final boolean isUsingStaticIp =
                    (currentConfig.getIpAssignment() == IpConfiguration.IpAssignment.STATIC);
            if (mVerboseLoggingEnabled) {
                final String key = currentConfig.configKey();
                log("enter ObtainingIpState netId=" + Integer.toString(mLastNetworkId)
                        + " " + key + " "
                        + " roam=" + mIsAutoRoaming
                        + " static=" + isUsingStaticIp);
            }

            // Send event to CM & network change broadcast
            setNetworkDetailedState(DetailedState.OBTAINING_IPADDR);

            // We must clear the config BSSID, as the wifi chipset may decide to roam
            // from this point on and having the BSSID specified in the network block would
            // cause the roam to fail and the device to disconnect.
            clearTargetBssid("ObtainingIpAddress");

            // Stop IpClient in case we're switching from DHCP to static
            // configuration or vice versa.
            //
            // TODO: Only ever enter this state the first time we connect to a
            // network, never on switching between static configuration and
            // DHCP. When we transition from static configuration to DHCP in
            // particular, we must tell ConnectivityService that we're
            // disconnected, because DHCP might take a long time during which
            // connectivity APIs such as getActiveNetworkInfo should not return
            // CONNECTED.
            stopIpClient();

            if (mIpClient != null) {
                mIpClient.setHttpProxy(currentConfig.getHttpProxy());
                if (!TextUtils.isEmpty(mTcpBufferSizes)) {
                    mIpClient.setTcpBufferSizes(mTcpBufferSizes);
                }
            }
            final ProvisioningConfiguration prov;
            if (!isUsingStaticIp) {
                prov = new ProvisioningConfiguration.Builder()
                            .withPreDhcpAction()
                            .withApfCapabilities(mWifiNative.getApfCapabilities(mInterfaceName))
                            .withNetwork(getCurrentNetwork())
                            .withDisplayName(currentConfig.SSID)
                            .withRandomMacAddress()
                            .build();
            } else {
                StaticIpConfiguration staticIpConfig = currentConfig.getStaticIpConfiguration();
                prov = new ProvisioningConfiguration.Builder()
                            .withStaticConfiguration(staticIpConfig)
                            .withApfCapabilities(mWifiNative.getApfCapabilities(mInterfaceName))
                            .withNetwork(getCurrentNetwork())
                            .withDisplayName(currentConfig.SSID)
                            .build();
            }
            if (mIpClient != null) {
                mIpClient.startProvisioning(prov);
            }
            // Get Link layer stats so as we get fresh tx packet counters
            getWifiLinkLayerStats();
        }

        @Override
        public boolean processMessage(Message message) {
            boolean handleStatus = HANDLED;

            switch(message.what) {
                case CMD_START_CONNECT:
                case CMD_START_ROAM:
                    mMessageHandlingStatus = MESSAGE_HANDLING_STATUS_DISCARD;
                    break;
                case WifiManager.SAVE_NETWORK:
                    mMessageHandlingStatus = MESSAGE_HANDLING_STATUS_DEFERRED;
                    deferMessage(message);
                    break;
                case WifiMonitor.NETWORK_DISCONNECTION_EVENT:
                    reportConnectionAttemptEnd(
                            WifiMetrics.ConnectionEvent.FAILURE_NETWORK_DISCONNECTION,
                            WifiMetricsProto.ConnectionEvent.HLF_NONE,
                            WifiMetricsProto.ConnectionEvent.FAILURE_REASON_UNKNOWN);
                    handleStatus = NOT_HANDLED;
                    break;
                case CMD_SET_HIGH_PERF_MODE:
                    mMessageHandlingStatus = MESSAGE_HANDLING_STATUS_DEFERRED;
                    deferMessage(message);
                    break;
                default:
                    handleStatus = NOT_HANDLED;
                    break;
            }

            if (handleStatus == HANDLED) {
                logStateAndMessage(message, this);
            }
            return handleStatus;
        }
    }

    /**
     * Helper function to check if we need to invoke
     * {@link NetworkAgent#explicitlySelected(boolean, boolean)} to indicate that we connected to a
     * network which the user just chose
     * (i.e less than {@link #LAST_SELECTED_NETWORK_EXPIRATION_AGE_MILLIS) before).
     */
    @VisibleForTesting
    public boolean shouldEvaluateWhetherToSendExplicitlySelected(WifiConfiguration currentConfig) {
        if (currentConfig == null) {
            Log.wtf(TAG, "Current WifiConfiguration is null, but IP provisioning just succeeded");
            return false;
        }
        long currentTimeMillis = mClock.getElapsedSinceBootMillis();
        return (mWifiConfigManager.getLastSelectedNetwork() == currentConfig.networkId
                && currentTimeMillis - mWifiConfigManager.getLastSelectedTimeStamp()
                < LAST_SELECTED_NETWORK_EXPIRATION_AGE_MILLIS);
    }

    private void sendConnectedState() {
        // If this network was explicitly selected by the user, evaluate whether to inform
        // ConnectivityService of that fact so the system can treat it appropriately.
        WifiConfiguration config = getCurrentWifiConfiguration();

        boolean explicitlySelected = false;
        if (shouldEvaluateWhetherToSendExplicitlySelected(config)) {
            // If explicitlySelected is true, the network was selected by the user via Settings or
            // QuickSettings. If this network has Internet access, switch to it. Otherwise, switch
            // to it only if the user confirms that they really want to switch, or has already
            // confirmed and selected "Don't ask again".
            explicitlySelected =
                    mWifiPermissionsUtil.checkNetworkSettingsPermission(config.lastConnectUid);
            if (mVerboseLoggingEnabled) {
                log("Network selected by UID " + config.lastConnectUid + " explicitlySelected="
                        + explicitlySelected);
            }
        }

        if (mVerboseLoggingEnabled) {
            log("explictlySelected=" + explicitlySelected + " acceptUnvalidated="
                    + config.noInternetAccessExpected);
        }

        if (mNetworkAgent != null) {
            mNetworkAgent.explicitlySelected(explicitlySelected, config.noInternetAccessExpected);
        }

        setNetworkDetailedState(DetailedState.CONNECTED);
        sendNetworkStateChangeBroadcast(mLastBssid);
    }

    class RoamingState extends State {
        boolean mAssociated;
        @Override
        public void enter() {
            if (mVerboseLoggingEnabled) {
                log("RoamingState Enter mScreenOn=" + mScreenOn);
            }

            // Make sure we disconnect if roaming fails
            mRoamWatchdogCount++;
            logd("Start Roam Watchdog " + mRoamWatchdogCount);
            sendMessageDelayed(obtainMessage(CMD_ROAM_WATCHDOG_TIMER,
                    mRoamWatchdogCount, 0), ROAM_GUARD_TIMER_MSEC);
            mAssociated = false;
        }
        @Override
        public boolean processMessage(Message message) {
            WifiConfiguration config;
            boolean handleStatus = HANDLED;

            switch (message.what) {
                case CMD_IP_CONFIGURATION_LOST:
                    config = getCurrentWifiConfiguration();
                    if (config != null) {
                        mWifiDiagnostics.captureBugReportData(
                                WifiDiagnostics.REPORT_REASON_AUTOROAM_FAILURE);
                    }
                    handleStatus = NOT_HANDLED;
                    break;
                case CMD_UNWANTED_NETWORK:
                    if (mVerboseLoggingEnabled) {
                        log("Roaming and CS doesn't want the network -> ignore");
                    }
                    break;
                case WifiMonitor.SUPPLICANT_STATE_CHANGE_EVENT:
                    /**
                     * If we get a SUPPLICANT_STATE_CHANGE_EVENT indicating a DISCONNECT
                     * before NETWORK_DISCONNECTION_EVENT
                     * And there is an associated BSSID corresponding to our target BSSID, then
                     * we have missed the network disconnection, transition to mDisconnectedState
                     * and handle the rest of the events there.
                     */
                    StateChangeResult stateChangeResult = (StateChangeResult) message.obj;
                    if (stateChangeResult.state == SupplicantState.DISCONNECTED
                            || stateChangeResult.state == SupplicantState.INACTIVE
                            || stateChangeResult.state == SupplicantState.INTERFACE_DISABLED) {
                        if (mVerboseLoggingEnabled) {
                            log("STATE_CHANGE_EVENT in roaming state "
                                    + stateChangeResult.toString());
                        }
                        if (stateChangeResult.BSSID != null
                                && stateChangeResult.BSSID.equals(mTargetRoamBSSID)) {
                            handleNetworkDisconnect();
                            transitionTo(mDisconnectedState);
                        }
                    }
                    if (stateChangeResult.state == SupplicantState.ASSOCIATED) {
                        // We completed the layer2 roaming part
                        mAssociated = true;
                        if (stateChangeResult.BSSID != null) {
                            mTargetRoamBSSID = stateChangeResult.BSSID;
                        }
                    }
                    break;
                case CMD_ROAM_WATCHDOG_TIMER:
                    if (mRoamWatchdogCount == message.arg1) {
                        if (mVerboseLoggingEnabled) log("roaming watchdog! -> disconnect");
                        mWifiMetrics.endConnectionEvent(
                                WifiMetrics.ConnectionEvent.FAILURE_ROAM_TIMEOUT,
                                WifiMetricsProto.ConnectionEvent.HLF_NONE,
                                WifiMetricsProto.ConnectionEvent.FAILURE_REASON_UNKNOWN);
                        mRoamFailCount++;
                        handleNetworkDisconnect();
                        mWifiMetrics.logStaEvent(StaEvent.TYPE_FRAMEWORK_DISCONNECT,
                                StaEvent.DISCONNECT_ROAM_WATCHDOG_TIMER);
                        mWifiNative.disconnect(mInterfaceName);
                        transitionTo(mDisconnectedState);
                    }
                    break;
                case WifiMonitor.NETWORK_CONNECTION_EVENT:
                    if (mAssociated) {
                        if (mVerboseLoggingEnabled) {
                            log("roaming and Network connection established");
                        }
                        mLastNetworkId = message.arg1;
                        mLastBssid = (String) message.obj;
                        mWifiInfo.setBSSID(mLastBssid);
                        mWifiInfo.setNetworkId(mLastNetworkId);
                        int reasonCode = message.arg2;
                        mWifiConnectivityManager.trackBssid(mLastBssid, true, reasonCode);
                        sendNetworkStateChangeBroadcast(mLastBssid);

                        // Successful framework roam! (probably)
                        reportConnectionAttemptEnd(
                                WifiMetrics.ConnectionEvent.FAILURE_NONE,
                                WifiMetricsProto.ConnectionEvent.HLF_NONE,
                                WifiMetricsProto.ConnectionEvent.FAILURE_REASON_UNKNOWN);

                        // We must clear the config BSSID, as the wifi chipset may decide to roam
                        // from this point on and having the BSSID specified by QNS would cause
                        // the roam to fail and the device to disconnect.
                        // When transition from RoamingState to DisconnectingState or
                        // DisconnectedState, the config BSSID is cleared by
                        // handleNetworkDisconnect().
                        clearTargetBssid("RoamingCompleted");

                        // We used to transition to ObtainingIpState in an
                        // attempt to do DHCPv4 RENEWs on framework roams.
                        // DHCP can take too long to time out, and we now rely
                        // upon IpClient's use of IpReachabilityMonitor to
                        // confirm our current network configuration.
                        //
                        // mIpClient.confirmConfiguration() is called within
                        // the handling of SupplicantState.COMPLETED.
                        transitionTo(mConnectedState);
                    } else {
                        mMessageHandlingStatus = MESSAGE_HANDLING_STATUS_DISCARD;
                    }
                    break;
                case WifiMonitor.NETWORK_DISCONNECTION_EVENT:
                    // Throw away but only if it corresponds to the network we're roaming to
                    String bssid = (String) message.obj;
                    if (true) {
                        String target = "";
                        if (mTargetRoamBSSID != null) target = mTargetRoamBSSID;
                        log("NETWORK_DISCONNECTION_EVENT in roaming state"
                                + " BSSID=" + bssid
                                + " target=" + target);
                    }
                    if (bssid != null && bssid.equals(mTargetRoamBSSID)) {
                        handleNetworkDisconnect();
                        transitionTo(mDisconnectedState);
                    }
                    break;
                default:
                    handleStatus = NOT_HANDLED;
                    break;
            }

            if (handleStatus == HANDLED) {
                logStateAndMessage(message, this);
            }
            return handleStatus;
        }

        @Override
        public void exit() {
            logd("ClientModeImpl: Leaving Roaming state");
        }
    }

    class ConnectedState extends State {
        @Override
        public void enter() {
            if (mVerboseLoggingEnabled) {
                log("Enter ConnectedState  mScreenOn=" + mScreenOn);
            }

            reportConnectionAttemptEnd(
                    WifiMetrics.ConnectionEvent.FAILURE_NONE,
                    WifiMetricsProto.ConnectionEvent.HLF_NONE,
                    WifiMetricsProto.ConnectionEvent.FAILURE_REASON_UNKNOWN);
            mWifiConnectivityManager.handleConnectionStateChanged(
                    WifiConnectivityManager.WIFI_STATE_CONNECTED);
            registerConnected();
            mLastConnectAttemptTimestamp = 0;
            mTargetWifiConfiguration = null;
            mWifiScoreReport.reset();
            mLastSignalLevel = -1;

            // Not roaming anymore
            mIsAutoRoaming = false;

            mLastDriverRoamAttempt = 0;
            mTargetNetworkId = WifiConfiguration.INVALID_NETWORK_ID;
            mWifiInjector.getWifiLastResortWatchdog().connectedStateTransition(true);
            mWifiStateTracker.updateState(WifiStateTracker.CONNECTED);
            //Inform WifiLockManager
            WifiLockManager wifiLockManager = mWifiInjector.getWifiLockManager();
            wifiLockManager.updateWifiClientConnected(true);
        }
        @Override
        public boolean processMessage(Message message) {
            WifiConfiguration config = null;
            boolean handleStatus = HANDLED;

            switch (message.what) {
                case CMD_UNWANTED_NETWORK:
                    if (message.arg1 == NETWORK_STATUS_UNWANTED_DISCONNECT) {
                        mWifiMetrics.logStaEvent(StaEvent.TYPE_FRAMEWORK_DISCONNECT,
                                StaEvent.DISCONNECT_UNWANTED);
                        mWifiNative.disconnect(mInterfaceName);
                        transitionTo(mDisconnectingState);
                    } else if (message.arg1 == NETWORK_STATUS_UNWANTED_DISABLE_AUTOJOIN
                            || message.arg1 == NETWORK_STATUS_UNWANTED_VALIDATION_FAILED) {
                        Log.d(TAG, (message.arg1 == NETWORK_STATUS_UNWANTED_DISABLE_AUTOJOIN
                                ? "NETWORK_STATUS_UNWANTED_DISABLE_AUTOJOIN"
                                : "NETWORK_STATUS_UNWANTED_VALIDATION_FAILED"));
                        config = getCurrentWifiConfiguration();
                        if (config != null) {
                            // Disable autojoin
                            if (message.arg1 == NETWORK_STATUS_UNWANTED_DISABLE_AUTOJOIN) {
                                mWifiConfigManager.setNetworkValidatedInternetAccess(
                                        config.networkId, false);
                                mWifiConfigManager.updateNetworkSelectionStatus(config.networkId,
                                        WifiConfiguration.NetworkSelectionStatus
                                        .DISABLED_NO_INTERNET_PERMANENT);
                            } else {
                                // stop collect last-mile stats since validation fail
                                removeMessages(CMD_DIAGS_CONNECT_TIMEOUT);
                                mWifiDiagnostics.reportConnectionEvent(
                                        WifiDiagnostics.CONNECTION_EVENT_FAILED);
                                mWifiConfigManager.incrementNetworkNoInternetAccessReports(
                                        config.networkId);
                                // If this was not the last selected network, update network
                                // selection status to temporarily disable the network.
                                if (mWifiConfigManager.getLastSelectedNetwork() != config.networkId
                                        && !config.noInternetAccessExpected) {
                                    Log.i(TAG, "Temporarily disabling network because of"
                                            + "no-internet access");
                                    mWifiConfigManager.updateNetworkSelectionStatus(
                                            config.networkId,
                                            WifiConfiguration.NetworkSelectionStatus
                                                    .DISABLED_NO_INTERNET_TEMPORARY);
                                }
                            }
                        }
                    }
                    break;
                case CMD_NETWORK_STATUS:
                    if (message.arg1 == NetworkAgent.VALID_NETWORK) {
                        // stop collect last-mile stats since validation pass
                        removeMessages(CMD_DIAGS_CONNECT_TIMEOUT);
                        mWifiDiagnostics.reportConnectionEvent(
                                WifiDiagnostics.CONNECTION_EVENT_SUCCEEDED);
                        mWifiScoreCard.noteValidationSuccess(mWifiInfo);
                        config = getCurrentWifiConfiguration();
                        if (config != null) {
                            // re-enable autojoin
                            mWifiConfigManager.updateNetworkSelectionStatus(
                                    config.networkId,
                                    WifiConfiguration.NetworkSelectionStatus
                                            .NETWORK_SELECTION_ENABLE);
                            mWifiConfigManager.setNetworkValidatedInternetAccess(
                                    config.networkId, true);
                        }
                    }
                    break;
                case CMD_ACCEPT_UNVALIDATED:
                    boolean accept = (message.arg1 != 0);
                    mWifiConfigManager.setNetworkNoInternetAccessExpected(mLastNetworkId, accept);
                    break;
                case CMD_ASSOCIATED_BSSID:
                    // ASSOCIATING to a new BSSID while already connected, indicates
                    // that driver is roaming
                    mLastDriverRoamAttempt = mClock.getWallClockMillis();
                    handleStatus = NOT_HANDLED;
                    break;
                case WifiMonitor.NETWORK_DISCONNECTION_EVENT:
                    long lastRoam = 0;
                    reportConnectionAttemptEnd(
                            WifiMetrics.ConnectionEvent.FAILURE_NETWORK_DISCONNECTION,
                            WifiMetricsProto.ConnectionEvent.HLF_NONE,
                            WifiMetricsProto.ConnectionEvent.FAILURE_REASON_UNKNOWN);
                    if (mLastDriverRoamAttempt != 0) {
                        // Calculate time since last driver roam attempt
                        lastRoam = mClock.getWallClockMillis() - mLastDriverRoamAttempt;
                        mLastDriverRoamAttempt = 0;
                    }
                    if (unexpectedDisconnectedReason(message.arg2)) {
                        mWifiDiagnostics.captureBugReportData(
                                WifiDiagnostics.REPORT_REASON_UNEXPECTED_DISCONNECT);
                    }
                    config = getCurrentWifiConfiguration();

                    if (mVerboseLoggingEnabled) {
                        log("NETWORK_DISCONNECTION_EVENT in connected state"
                                + " BSSID=" + mWifiInfo.getBSSID()
                                + " RSSI=" + mWifiInfo.getRssi()
                                + " freq=" + mWifiInfo.getFrequency()
                                + " reason=" + message.arg2
                                + " Network Selection Status=" + (config == null ? "Unavailable"
                                    : config.getNetworkSelectionStatus().getNetworkStatusString()));
                    }
                    break;
                case CMD_START_ROAM:
                    // Clear the driver roam indication since we are attempting a framework roam
                    mLastDriverRoamAttempt = 0;

                    /* Connect command coming from auto-join */
                    int netId = message.arg1;
                    ScanResult candidate = (ScanResult) message.obj;
                    String bssid = SUPPLICANT_BSSID_ANY;
                    if (candidate != null) {
                        bssid = candidate.BSSID;
                    }
                    config = mWifiConfigManager.getConfiguredNetworkWithoutMasking(netId);
                    if (config == null) {
                        loge("CMD_START_ROAM and no config, bail out...");
                        break;
                    }
                    mWifiScoreCard.noteConnectionAttempt(mWifiInfo);
                    setTargetBssid(config, bssid);
                    mTargetNetworkId = netId;

                    logd("CMD_START_ROAM sup state "
                            + mSupplicantStateTracker.getSupplicantStateName()
                            + " my state " + getCurrentState().getName()
                            + " nid=" + Integer.toString(netId)
                            + " config " + config.configKey()
                            + " targetRoamBSSID " + mTargetRoamBSSID);

                    reportConnectionAttemptStart(config, mTargetRoamBSSID,
                            WifiMetricsProto.ConnectionEvent.ROAM_ENTERPRISE);
                    if (mWifiNative.roamToNetwork(mInterfaceName, config)) {
                        mLastConnectAttemptTimestamp = mClock.getWallClockMillis();
                        mTargetWifiConfiguration = config;
                        mIsAutoRoaming = true;
                        mWifiMetrics.logStaEvent(StaEvent.TYPE_CMD_START_ROAM, config);
                        transitionTo(mRoamingState);
                    } else {
                        loge("CMD_START_ROAM Failed to start roaming to network " + config);
                        reportConnectionAttemptEnd(
                                WifiMetrics.ConnectionEvent.FAILURE_CONNECT_NETWORK_FAILED,
                                WifiMetricsProto.ConnectionEvent.HLF_NONE,
                                WifiMetricsProto.ConnectionEvent.FAILURE_REASON_UNKNOWN);
                        replyToMessage(message, WifiManager.CONNECT_NETWORK_FAILED,
                                WifiManager.ERROR);
                        mMessageHandlingStatus = MESSAGE_HANDLING_STATUS_FAIL;
                        break;
                    }
                    break;
                default:
                    handleStatus = NOT_HANDLED;
                    break;
            }

            if (handleStatus == HANDLED) {
                logStateAndMessage(message, this);
            }

            return handleStatus;
        }

        @Override
        public void exit() {
            logd("ClientModeImpl: Leaving Connected state");
            mWifiConnectivityManager.handleConnectionStateChanged(
                     WifiConnectivityManager.WIFI_STATE_TRANSITIONING);

            mLastDriverRoamAttempt = 0;
            mWifiInjector.getWifiLastResortWatchdog().connectedStateTransition(false);
        }
    }

    class DisconnectingState extends State {

        @Override
        public void enter() {

            if (mVerboseLoggingEnabled) {
                logd(" Enter DisconnectingState State screenOn=" + mScreenOn);
            }

            // Make sure we disconnect: we enter this state prior to connecting to a new
            // network, waiting for either a DISCONNECT event or a SUPPLICANT_STATE_CHANGE
            // event which in this case will be indicating that supplicant started to associate.
            // In some cases supplicant doesn't ignore the connect requests (it might not
            // find the target SSID in its cache),
            // Therefore we end up stuck that state, hence the need for the watchdog.
            mDisconnectingWatchdogCount++;
            logd("Start Disconnecting Watchdog " + mDisconnectingWatchdogCount);
            sendMessageDelayed(obtainMessage(CMD_DISCONNECTING_WATCHDOG_TIMER,
                    mDisconnectingWatchdogCount, 0), DISCONNECTING_GUARD_TIMER_MSEC);
        }

        @Override
        public boolean processMessage(Message message) {
            boolean handleStatus = HANDLED;

            switch (message.what) {
                case CMD_DISCONNECT:
                    if (mVerboseLoggingEnabled) {
                        log("Ignore CMD_DISCONNECT when already disconnecting.");
                    }
                    break;
                case CMD_DISCONNECTING_WATCHDOG_TIMER:
                    if (mDisconnectingWatchdogCount == message.arg1) {
                        if (mVerboseLoggingEnabled) log("disconnecting watchdog! -> disconnect");
                        handleNetworkDisconnect();
                        transitionTo(mDisconnectedState);
                    }
                    break;
                case WifiMonitor.SUPPLICANT_STATE_CHANGE_EVENT:
                    /**
                     * If we get a SUPPLICANT_STATE_CHANGE_EVENT before NETWORK_DISCONNECTION_EVENT
                     * we have missed the network disconnection, transition to mDisconnectedState
                     * and handle the rest of the events there
                     */
                    deferMessage(message);
                    handleNetworkDisconnect();
                    transitionTo(mDisconnectedState);
                    break;
                default:
                    handleStatus = NOT_HANDLED;
                    break;
            }

            if (handleStatus == HANDLED) {
                logStateAndMessage(message, this);
            }
            return handleStatus;
        }
    }

    class DisconnectedState extends State {
        @Override
        public void enter() {
            Log.i(TAG, "disconnectedstate enter");
            // We don't scan frequently if this is a temporary disconnect
            // due to p2p
            if (mTemporarilyDisconnectWifi) {
                p2pSendMessage(WifiP2pServiceImpl.DISCONNECT_WIFI_RESPONSE);
                return;
            }

            if (mVerboseLoggingEnabled) {
                logd(" Enter DisconnectedState screenOn=" + mScreenOn);
            }

            /** clear the roaming state, if we were roaming, we failed */
            mIsAutoRoaming = false;

            mWifiConnectivityManager.handleConnectionStateChanged(
                    WifiConnectivityManager.WIFI_STATE_DISCONNECTED);
        }

        @Override
        public boolean processMessage(Message message) {
            boolean handleStatus = HANDLED;

            switch (message.what) {
                case CMD_DISCONNECT:
                    mWifiMetrics.logStaEvent(StaEvent.TYPE_FRAMEWORK_DISCONNECT,
                            StaEvent.DISCONNECT_GENERIC);
                    mWifiNative.disconnect(mInterfaceName);
                    break;
                case WifiMonitor.NETWORK_DISCONNECTION_EVENT:
                    if (message.arg2 == 15 /* FOURWAY_HANDSHAKE_TIMEOUT */) {
                        String bssid = (message.obj == null)
                                ? mTargetRoamBSSID : (String) message.obj;
                        mWifiInjector.getWifiLastResortWatchdog()
                                .noteConnectionFailureAndTriggerIfNeeded(
                                        getTargetSsid(), bssid,
                                        WifiLastResortWatchdog.FAILURE_CODE_AUTHENTICATION);
                    }
                    break;
                case WifiMonitor.SUPPLICANT_STATE_CHANGE_EVENT:
                    StateChangeResult stateChangeResult = (StateChangeResult) message.obj;
                    if (mVerboseLoggingEnabled) {
                        logd("SUPPLICANT_STATE_CHANGE_EVENT state=" + stateChangeResult.state
                                + " -> state= "
                                + WifiInfo.getDetailedStateOf(stateChangeResult.state));
                    }
                    if (SupplicantState.isConnecting(stateChangeResult.state)) {
                        WifiConfiguration config = mWifiConfigManager.getConfiguredNetwork(
                                stateChangeResult.networkId);

                        // Update Passpoint information before setNetworkDetailedState as
                        // WifiTracker monitors NETWORK_STATE_CHANGED_ACTION to update UI.
                        mWifiInfo.setFQDN(null);
                        mWifiInfo.setOsuAp(false);
                        mWifiInfo.setProviderFriendlyName(null);
                        if (config != null && (config.isPasspoint() || config.osu)) {
                            if (config.isPasspoint()) {
                                mWifiInfo.setFQDN(config.FQDN);
                            } else {
                                mWifiInfo.setOsuAp(true);
                            }
                            mWifiInfo.setProviderFriendlyName(config.providerFriendlyName);
                        }
                    }
                    setNetworkDetailedState(WifiInfo.getDetailedStateOf(stateChangeResult.state));
                    /* ConnectModeState does the rest of the handling */
                    handleStatus = NOT_HANDLED;
                    break;
                case WifiP2pServiceImpl.P2P_CONNECTION_CHANGED:
                    NetworkInfo info = (NetworkInfo) message.obj;
                    mP2pConnected.set(info.isConnected());
                    break;
                case CMD_RECONNECT:
                case CMD_REASSOCIATE:
                    if (mTemporarilyDisconnectWifi) {
                        // Drop a third party reconnect/reassociate if STA is
                        // temporarily disconnected for p2p
                        break;
                    } else {
                        // ConnectModeState handles it
                        handleStatus = NOT_HANDLED;
                    }
                    break;
                case CMD_SCREEN_STATE_CHANGED:
                    handleScreenStateChanged(message.arg1 != 0);
                    break;
                default:
                    handleStatus = NOT_HANDLED;
                    break;
            }

            if (handleStatus == HANDLED) {
                logStateAndMessage(message, this);
            }
            return handleStatus;
        }

        @Override
        public void exit() {
            mWifiConnectivityManager.handleConnectionStateChanged(
                     WifiConnectivityManager.WIFI_STATE_TRANSITIONING);
        }
    }

    /**
     * State machine initiated requests can have replyTo set to null, indicating
     * there are no recipients, we ignore those reply actions.
     */
    private void replyToMessage(Message msg, int what) {
        if (msg.replyTo == null) return;
        Message dstMsg = obtainMessageWithWhatAndArg2(msg, what);
        mReplyChannel.replyToMessage(msg, dstMsg);
    }

    private void replyToMessage(Message msg, int what, int arg1) {
        if (msg.replyTo == null) return;
        Message dstMsg = obtainMessageWithWhatAndArg2(msg, what);
        dstMsg.arg1 = arg1;
        mReplyChannel.replyToMessage(msg, dstMsg);
    }

    private void replyToMessage(Message msg, int what, Object obj) {
        if (msg.replyTo == null) return;
        Message dstMsg = obtainMessageWithWhatAndArg2(msg, what);
        dstMsg.obj = obj;
        mReplyChannel.replyToMessage(msg, dstMsg);
    }

    /**
     * arg2 on the source message has a unique id that needs to be retained in replies
     * to match the request
     * <p>see WifiManager for details
     */
    private Message obtainMessageWithWhatAndArg2(Message srcMsg, int what) {
        Message msg = Message.obtain();
        msg.what = what;
        msg.arg2 = srcMsg.arg2;
        return msg;
    }

    /**
     * Notify interested parties if a wifi config has been changed.
     *
     * @param wifiCredentialEventType WIFI_CREDENTIAL_SAVED or WIFI_CREDENTIAL_FORGOT
     * @param config Must have a WifiConfiguration object to succeed
     * TODO: b/35258354 investigate if this can be removed.  Is the broadcast sent by
     * WifiConfigManager sufficient?
     */
    private void broadcastWifiCredentialChanged(int wifiCredentialEventType,
            WifiConfiguration config) {
        if (config != null && config.preSharedKey != null) {
            Intent intent = new Intent(WifiManager.WIFI_CREDENTIAL_CHANGED_ACTION);
            intent.putExtra(WifiManager.EXTRA_WIFI_CREDENTIAL_SSID, config.SSID);
            intent.putExtra(WifiManager.EXTRA_WIFI_CREDENTIAL_EVENT_TYPE,
                    wifiCredentialEventType);
            mContext.sendBroadcastAsUser(intent, UserHandle.CURRENT,
                    android.Manifest.permission.RECEIVE_WIFI_CREDENTIAL_CHANGE);
        }
    }

    void handleGsmAuthRequest(SimAuthRequestData requestData) {
        if (mTargetWifiConfiguration == null
                || mTargetWifiConfiguration.networkId
                == requestData.networkId) {
            logd("id matches targetWifiConfiguration");
        } else {
            logd("id does not match targetWifiConfiguration");
            return;
        }

        /*
         * Try authentication in the following order.
         *
         *    Standard       Cellular_auth     Type Command
         *
         * 1. 3GPP TS 31.102 3G_authentication [Length][RAND][Length][AUTN]
         *                            [Length][RES][Length][CK][Length][IK] and more
         * 2. 3GPP TS 31.102 2G_authentication [Length][RAND]
         *                            [Length][SRES][Length][Cipher Key Kc]
         * 3. 3GPP TS 11.11  2G_authentication [RAND]
         *                            [SRES][Cipher Key Kc]
         */
        String response =
                TelephonyUtil.getGsmSimAuthResponse(requestData.data, getTelephonyManager());
        if (response == null) {
            // In case of failure, issue may be due to sim type, retry as No.2 case
            response = TelephonyUtil.getGsmSimpleSimAuthResponse(requestData.data,
                    getTelephonyManager());
            if (response == null) {
                // In case of failure, issue may be due to sim type, retry as No.3 case
                response = TelephonyUtil.getGsmSimpleSimNoLengthAuthResponse(requestData.data,
                        getTelephonyManager());
            }
        }
        if (response == null || response.length() == 0) {
            mWifiNative.simAuthFailedResponse(mInterfaceName, requestData.networkId);
        } else {
            logv("Supplicant Response -" + response);
            mWifiNative.simAuthResponse(
                    mInterfaceName, requestData.networkId,
                    WifiNative.SIM_AUTH_RESP_TYPE_GSM_AUTH, response);
        }
    }

    void handle3GAuthRequest(SimAuthRequestData requestData) {
        if (mTargetWifiConfiguration == null
                || mTargetWifiConfiguration.networkId
                == requestData.networkId) {
            logd("id matches targetWifiConfiguration");
        } else {
            logd("id does not match targetWifiConfiguration");
            return;
        }

        SimAuthResponseData response =
                TelephonyUtil.get3GAuthResponse(requestData, getTelephonyManager());
        if (response != null) {
            mWifiNative.simAuthResponse(
                    mInterfaceName, requestData.networkId, response.type, response.response);
        } else {
            mWifiNative.umtsAuthFailedResponse(mInterfaceName, requestData.networkId);
        }
    }

    /**
     * Automatically connect to the network specified
     *
     * @param networkId ID of the network to connect to
     * @param uid UID of the app triggering the connection.
     * @param bssid BSSID of the network
     */
    public void startConnectToNetwork(int networkId, int uid, String bssid) {
        sendMessage(CMD_START_CONNECT, networkId, uid, bssid);
    }

    /**
     * Automatically roam to the network specified
     *
     * @param networkId ID of the network to roam to
     * @param scanResult scan result which identifies the network to roam to
     */
    public void startRoamToNetwork(int networkId, ScanResult scanResult) {
        sendMessage(CMD_START_ROAM, networkId, 0, scanResult);
    }

    /**
     * Dynamically turn on/off WifiConnectivityManager
     *
     * @param enabled true-enable; false-disable
     */
    public void enableWifiConnectivityManager(boolean enabled) {
        sendMessage(CMD_ENABLE_WIFI_CONNECTIVITY_MANAGER, enabled ? 1 : 0);
    }

    /**
     * @param reason reason code from supplicant on network disconnected event
     * @return true if this is a suspicious disconnect
     */
    static boolean unexpectedDisconnectedReason(int reason) {
        return reason == 2              // PREV_AUTH_NOT_VALID
                || reason == 6          // CLASS2_FRAME_FROM_NONAUTH_STA
                || reason == 7          // FRAME_FROM_NONASSOC_STA
                || reason == 8          // STA_HAS_LEFT
                || reason == 9          // STA_REQ_ASSOC_WITHOUT_AUTH
                || reason == 14         // MICHAEL_MIC_FAILURE
                || reason == 15         // 4WAY_HANDSHAKE_TIMEOUT
                || reason == 16         // GROUP_KEY_UPDATE_TIMEOUT
                || reason == 18         // GROUP_CIPHER_NOT_VALID
                || reason == 19         // PAIRWISE_CIPHER_NOT_VALID
                || reason == 23         // IEEE_802_1X_AUTH_FAILED
                || reason == 34;        // DISASSOC_LOW_ACK
    }

    /**
     * Update WifiMetrics before dumping
     */
    public void updateWifiMetrics() {
        mWifiMetrics.updateSavedNetworks(mWifiConfigManager.getSavedNetworks(Process.WIFI_UID));
        mPasspointManager.updateMetrics();
    }

    /**
     * Private method to handle calling WifiConfigManager to forget/remove network configs and reply
     * to the message from the sender of the outcome.
     *
     * The current implementation requires that forget and remove be handled in different ways
     * (responses are handled differently).  In the interests of organization, the handling is all
     * now in this helper method.  TODO: b/35257965 is filed to track the possibility of merging
     * the two call paths.
     */
    private boolean deleteNetworkConfigAndSendReply(Message message, boolean calledFromForget) {
        boolean success = mWifiConfigManager.removeNetwork(message.arg1, message.sendingUid);
        if (!success) {
            loge("Failed to remove network");
        }

        if (calledFromForget) {
            if (success) {
                replyToMessage(message, WifiManager.FORGET_NETWORK_SUCCEEDED);
                broadcastWifiCredentialChanged(WifiManager.WIFI_CREDENTIAL_FORGOT,
                                               (WifiConfiguration) message.obj);
                return true;
            }
            replyToMessage(message, WifiManager.FORGET_NETWORK_FAILED, WifiManager.ERROR);
            return false;
        } else {
            // Remaining calls are from the removeNetwork path
            if (success) {
                replyToMessage(message, message.what, SUCCESS);
                return true;
            }
            mMessageHandlingStatus = MESSAGE_HANDLING_STATUS_FAIL;
            replyToMessage(message, message.what, FAILURE);
            return false;
        }
    }

    /**
     * Private method to handle calling WifiConfigManager to add & enable network configs and reply
     * to the message from the sender of the outcome.
     *
     * @return NetworkUpdateResult with networkId of the added/updated configuration. Will return
     * {@link WifiConfiguration#INVALID_NETWORK_ID} in case of error.
     */
    private NetworkUpdateResult saveNetworkConfigAndSendReply(Message message) {
        WifiConfiguration config = (WifiConfiguration) message.obj;
        if (config == null) {
            loge("SAVE_NETWORK with null configuration "
                    + mSupplicantStateTracker.getSupplicantStateName()
                    + " my state " + getCurrentState().getName());
            mMessageHandlingStatus = MESSAGE_HANDLING_STATUS_FAIL;
            replyToMessage(message, WifiManager.SAVE_NETWORK_FAILED, WifiManager.ERROR);
            return new NetworkUpdateResult(WifiConfiguration.INVALID_NETWORK_ID);
        }
        NetworkUpdateResult result =
                mWifiConfigManager.addOrUpdateNetwork(config, message.sendingUid);
        if (!result.isSuccess()) {
            loge("SAVE_NETWORK adding/updating config=" + config + " failed");
            mMessageHandlingStatus = MESSAGE_HANDLING_STATUS_FAIL;
            replyToMessage(message, WifiManager.SAVE_NETWORK_FAILED, WifiManager.ERROR);
            return result;
        }
        if (!mWifiConfigManager.enableNetwork(
                result.getNetworkId(), false, message.sendingUid)) {
            loge("SAVE_NETWORK enabling config=" + config + " failed");
            mMessageHandlingStatus = MESSAGE_HANDLING_STATUS_FAIL;
            replyToMessage(message, WifiManager.SAVE_NETWORK_FAILED, WifiManager.ERROR);
            return new NetworkUpdateResult(WifiConfiguration.INVALID_NETWORK_ID);
        }
        broadcastWifiCredentialChanged(WifiManager.WIFI_CREDENTIAL_SAVED, config);
        replyToMessage(message, WifiManager.SAVE_NETWORK_SUCCEEDED);
        return result;
    }

    private static String getLinkPropertiesSummary(LinkProperties lp) {
        List<String> attributes = new ArrayList<>(6);
        if (lp.hasIPv4Address()) {
            attributes.add("v4");
        }
        if (lp.hasIPv4DefaultRoute()) {
            attributes.add("v4r");
        }
        if (lp.hasIPv4DnsServer()) {
            attributes.add("v4dns");
        }
        if (lp.hasGlobalIPv6Address()) {
            attributes.add("v6");
        }
        if (lp.hasIPv6DefaultRoute()) {
            attributes.add("v6r");
        }
        if (lp.hasIPv6DnsServer()) {
            attributes.add("v6dns");
        }

        return TextUtils.join(" ", attributes);
    }

    /**
     * Gets the SSID from the WifiConfiguration pointed at by 'mTargetNetworkId'
     * This should match the network config framework is attempting to connect to.
     */
    private String getTargetSsid() {
        WifiConfiguration currentConfig = mWifiConfigManager.getConfiguredNetwork(mTargetNetworkId);
        if (currentConfig != null) {
            return currentConfig.SSID;
        }
        return null;
    }

    /**
     * Send message to WifiP2pServiceImpl.
     * @return true if message is sent.
     *         false if there is no channel configured for WifiP2pServiceImpl.
     */
    private boolean p2pSendMessage(int what) {
        if (mWifiP2pChannel != null) {
            mWifiP2pChannel.sendMessage(what);
            return true;
        }
        return false;
    }

    /**
     * Send message to WifiP2pServiceImpl with an additional param |arg1|.
     * @return true if message is sent.
     *         false if there is no channel configured for WifiP2pServiceImpl.
     */
    private boolean p2pSendMessage(int what, int arg1) {
        if (mWifiP2pChannel != null) {
            mWifiP2pChannel.sendMessage(what, arg1);
            return true;
        }
        return false;
    }

    /**
     * Check if there is any connection request for WiFi network.
     */
    private boolean hasConnectionRequests() {
        return mNetworkFactory.hasConnectionRequests()
                || mUntrustedNetworkFactory.hasConnectionRequests();
    }

    /**
     * Returns whether CMD_IP_REACHABILITY_LOST events should trigger disconnects.
     */
    public boolean getIpReachabilityDisconnectEnabled() {
        return mIpReachabilityDisconnectEnabled;
    }

    /**
     * Sets whether CMD_IP_REACHABILITY_LOST events should trigger disconnects.
     */
    public void setIpReachabilityDisconnectEnabled(boolean enabled) {
        mIpReachabilityDisconnectEnabled = enabled;
    }

    /**
     * Sends a message to initialize the ClientModeImpl.
     *
     * @return true if succeeded, false otherwise.
     */
    public boolean syncInitialize(AsyncChannel channel) {
        Message resultMsg = channel.sendMessageSynchronously(CMD_INITIALIZE);
        boolean result = (resultMsg.arg1 != FAILURE);
        resultMsg.recycle();
        return result;
    }

    /**
     * Add a network request match callback to {@link WifiNetworkFactory}.
     */
    public void addNetworkRequestMatchCallback(IBinder binder,
                                               INetworkRequestMatchCallback callback,
                                               int callbackIdentifier) {
        mNetworkFactory.addCallback(binder, callback, callbackIdentifier);
    }

    /**
     * Remove a network request match callback from {@link WifiNetworkFactory}.
     */
    public void removeNetworkRequestMatchCallback(int callbackIdentifier) {
        mNetworkFactory.removeCallback(callbackIdentifier);
    }

    /**
     * Remove all approved access points from {@link WifiNetworkFactory} for the provided package.
     */
    public void removeNetworkRequestUserApprovedAccessPointsForApp(@NonNull String packageName) {
        mNetworkFactory.removeUserApprovedAccessPointsForApp(packageName);
    }

    /**
     * Clear all approved access points from {@link WifiNetworkFactory}.
     */
    public void clearNetworkRequestUserApprovedAccessPoints() {
        mNetworkFactory.clear();
    }

    /**
     * Gets the factory MAC address of wlan0 (station interface).
     * @return String representation of the factory MAC address.
     */
    public String getFactoryMacAddress() {
        MacAddress macAddress = mWifiNative.getFactoryMacAddress(mInterfaceName);
        if (macAddress != null) {
            return macAddress.toString();
        }
        if (!mConnectedMacRandomzationSupported) {
            return mWifiNative.getMacAddress(mInterfaceName);
        }
        return null;
    }

    /**
     * Sets the current device mobility state.
     * @param state the new device mobility state
     */
    public void setDeviceMobilityState(@DeviceMobilityState int state) {
        mWifiConnectivityManager.setDeviceMobilityState(state);
    }

    /**
     * Updates the Wi-Fi usability score.
     * @param seqNum Sequence number of the Wi-Fi usability score.
     * @param score The Wi-Fi usability score.
     * @param predictionHorizonSec Prediction horizon of the Wi-Fi usability score.
     */
    public void updateWifiUsabilityScore(int seqNum, int score, int predictionHorizonSec) {
        mWifiMetrics.incrementWifiUsabilityScoreCount(seqNum, score, predictionHorizonSec);
    }

    /**
     * Sends a link probe.
     */
    @VisibleForTesting
    public void probeLink(WifiNative.SendMgmtFrameCallback callback, int mcs) {
        mWifiNative.probeLink(mInterfaceName, MacAddress.fromString(mWifiInfo.getBSSID()),
                callback, mcs);
    }
}
