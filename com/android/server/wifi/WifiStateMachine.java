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

import static android.net.wifi.WifiManager.WIFI_AP_STATE_DISABLED;
import static android.net.wifi.WifiManager.WIFI_AP_STATE_DISABLING;
import static android.net.wifi.WifiManager.WIFI_AP_STATE_ENABLED;
import static android.net.wifi.WifiManager.WIFI_AP_STATE_ENABLING;
import static android.net.wifi.WifiManager.WIFI_AP_STATE_FAILED;
import static android.net.wifi.WifiManager.WIFI_STATE_DISABLED;
import static android.net.wifi.WifiManager.WIFI_STATE_DISABLING;
import static android.net.wifi.WifiManager.WIFI_STATE_ENABLED;
import static android.net.wifi.WifiManager.WIFI_STATE_ENABLING;
/**
 * TODO:
 * Deprecate WIFI_STATE_UNKNOWN
 */
import static android.net.wifi.WifiManager.WIFI_STATE_UNKNOWN;

import android.app.ActivityManager;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.backup.IBackupManager;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.net.ConnectivityManager;
import android.net.DhcpResults;
import android.net.DhcpStateMachine;
import android.net.InterfaceConfiguration;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.NetworkAgent;
import android.net.NetworkCapabilities;
import android.net.NetworkFactory;
import android.net.NetworkInfo;
import android.net.NetworkInfo.DetailedState;
import android.net.NetworkRequest;
import android.net.NetworkUtils;
import android.net.RouteInfo;
import android.net.StaticIpConfiguration;
import android.net.TrafficStats;
import android.net.wifi.BatchedScanResult;
import android.net.wifi.BatchedScanSettings;
import android.net.wifi.RssiPacketCountInfo;
import android.net.wifi.ScanResult;
import android.net.wifi.ScanSettings;
import android.net.wifi.SupplicantState;
import android.net.wifi.WifiChannel;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiConnectionStatistics;
import android.net.wifi.WifiEnterpriseConfig;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiLinkLayerStats;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiSsid;
import android.net.wifi.WpsInfo;
import android.net.wifi.WpsResult;
import android.net.wifi.WpsResult.Status;
import android.net.wifi.p2p.IWifiP2pManager;
import android.os.BatteryStats;
import android.os.Bundle;
import android.os.IBinder;
import android.os.INetworkManagementService;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.PowerManager;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.WorkSource;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;
import android.util.LruCache;

import com.android.internal.R;
import com.android.internal.app.IBatteryStats;
import com.android.internal.util.AsyncChannel;
import com.android.internal.util.Protocol;
import com.android.internal.util.State;
import com.android.internal.util.StateMachine;
import com.android.server.net.NetlinkTracker;
import com.android.server.wifi.p2p.WifiP2pServiceImpl;

import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Track the state of Wifi connectivity. All event handling is done here,
 * and all changes in connectivity state are initiated here.
 *
 * Wi-Fi now supports three modes of operation: Client, SoftAp and p2p
 * In the current implementation, we support concurrent wifi p2p and wifi operation.
 * The WifiStateMachine handles SoftAp and Client operations while WifiP2pService
 * handles p2p operation.
 *
 * @hide
 */
public class WifiStateMachine extends StateMachine {

    private static final String NETWORKTYPE = "WIFI";
    private static final String NETWORKTYPE_UNTRUSTED = "WIFI_UT";
    private static boolean DBG = false;
    private static boolean VDBG = false;
    private static boolean VVDBG = false;
    private static boolean mLogMessages = false;

    private static final int ONE_HOUR_MILLI = 1000 * 60 * 60;

    private static final String GOOGLE_OUI = "DA-A1-19";

    /* temporary debug flag - best network selection development */
    private static boolean PDBG = false;

    /* debug flag, indicating if handling of ASSOCIATION_REJECT ended up blacklisting
     * the corresponding BSSID.
     */
    private boolean didBlackListBSSID = false;

    /**
     * Log with error attribute
     *
     * @param s is string log
     */
    protected void loge(String s) {
        Log.e(getName(), s);
    }
    protected void log(String s) {;
        Log.e(getName(), s);
    }

    private WifiMonitor mWifiMonitor;
    private WifiNative mWifiNative;
    private WifiConfigStore mWifiConfigStore;
    private WifiAutoJoinController mWifiAutoJoinController;
    private INetworkManagementService mNwService;
    private ConnectivityManager mCm;

    private final boolean mP2pSupported;
    private final AtomicBoolean mP2pConnected = new AtomicBoolean(false);
    private boolean mTemporarilyDisconnectWifi = false;
    private final String mPrimaryDeviceType;

    /* Scan results handling */
    private List<ScanResult> mScanResults = new ArrayList<ScanResult>();
    private static final Pattern scanResultPattern = Pattern.compile("\t+");
    private static final int SCAN_RESULT_CACHE_SIZE = 160;
    private final LruCache<String, ScanResult> mScanResultCache;
    // For debug, number of known scan results that were found as part of last scan result event,
    // as well the number of scans results returned by the supplicant with that message
    private int mNumScanResultsKnown;
    private int mNumScanResultsReturned;

    /* Batch scan results */
    private final List<BatchedScanResult> mBatchedScanResults =
            new ArrayList<BatchedScanResult>();
    private int mBatchedScanOwnerUid = UNKNOWN_SCAN_SOURCE;
    private int mExpectedBatchedScans = 0;
    private long mBatchedScanMinPollTime = 0;

    private boolean mScreenOn = false;

    /* Chipset supports background scan */
    private final boolean mBackgroundScanSupported;

    private String mInterfaceName;
    /* Tethering interface could be separate from wlan interface */
    private String mTetherInterfaceName;

    private int mLastSignalLevel = -1;
    private String mLastBssid;
    private int mLastNetworkId; // The network Id we successfully joined
    private boolean linkDebouncing = false;

    // Testing various network disconnect cases by sending lots of spurious
    // disconnect to supplicant
    private boolean testNetworkDisconnect = false;

    private boolean mEnableRssiPolling = false;
    private boolean mEnableBackgroundScan = false;
    private int mRssiPollToken = 0;
    /* 3 operational states for STA operation: CONNECT_MODE, SCAN_ONLY_MODE, SCAN_ONLY_WIFI_OFF_MODE
    * In CONNECT_MODE, the STA can scan and connect to an access point
    * In SCAN_ONLY_MODE, the STA can only scan for access points
    * In SCAN_ONLY_WIFI_OFF_MODE, the STA can only scan for access points with wifi toggle being off
    */
    private int mOperationalMode = CONNECT_MODE;
    private boolean mIsScanOngoing = false;
    private boolean mIsFullScanOngoing = false;
    private boolean mSendScanResultsBroadcast = false;

    private final Queue<Message> mBufferedScanMsg = new LinkedList<Message>();
    private WorkSource mScanWorkSource = null;
    private static final int UNKNOWN_SCAN_SOURCE = -1;
    private static final int SCAN_ALARM_SOURCE = -2;
    private static final int ADD_OR_UPDATE_SOURCE = -3;
    private static final int SET_ALLOW_UNTRUSTED_SOURCE = -4;
    private static final int ENABLE_WIFI = -5;
    public static final int DFS_RESTRICTED_SCAN_REQUEST = -6;

    private static final int SCAN_REQUEST_BUFFER_MAX_SIZE = 10;
    private static final String CUSTOMIZED_SCAN_SETTING = "customized_scan_settings";
    private static final String CUSTOMIZED_SCAN_WORKSOURCE = "customized_scan_worksource";
    private static final String SCAN_REQUEST_TIME = "scan_request_time";

    private static final String BATCHED_SETTING = "batched_settings";
    private static final String BATCHED_WORKSOURCE = "batched_worksource";

    /* Tracks if state machine has received any screen state change broadcast yet.
     * We can miss one of these at boot.
     */
    private AtomicBoolean mScreenBroadcastReceived = new AtomicBoolean(false);

    private boolean mBluetoothConnectionActive = false;

    private PowerManager.WakeLock mSuspendWakeLock;

    /**
     * Interval in milliseconds between polling for RSSI
     * and linkspeed information
     */
    private static final int POLL_RSSI_INTERVAL_MSECS = 3000;

    /**
     * Interval in milliseconds between receiving a disconnect event
     * while connected to a good AP, and handling the disconnect proper
     */
    private static final int LINK_FLAPPING_DEBOUNCE_MSEC = 7000;

    /**
     * Delay between supplicant restarts upon failure to establish connection
     */
    private static final int SUPPLICANT_RESTART_INTERVAL_MSECS = 5000;

    /**
     * Number of times we attempt to restart supplicant
     */
    private static final int SUPPLICANT_RESTART_TRIES = 5;

    private int mSupplicantRestartCount = 0;
    /* Tracks sequence number on stop failure message */
    private int mSupplicantStopFailureToken = 0;

    /**
     * Tether state change notification time out
     */
    private static final int TETHER_NOTIFICATION_TIME_OUT_MSECS = 5000;

    /* Tracks sequence number on a tether notification time out */
    private int mTetherToken = 0;

    /**
     * Driver start time out.
     */
    private static final int DRIVER_START_TIME_OUT_MSECS = 10000;

    /* Tracks sequence number on a driver time out */
    private int mDriverStartToken = 0;

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
    private WifiInfo mWifiInfo;
    private NetworkInfo mNetworkInfo;
    private NetworkCapabilities mNetworkCapabilities;
    private SupplicantStateTracker mSupplicantStateTracker;
    private DhcpStateMachine mDhcpStateMachine;
    private boolean mDhcpActive = false;

    private int mWifiLinkLayerStatsSupported = 4; // Temporary disable

    private final AtomicInteger mCountryCodeSequence = new AtomicInteger();

    // Whether the state machine goes thru the Disconnecting->Disconnected->ObtainingIpAddress
    private int mAutoRoaming = WifiAutoJoinController.AUTO_JOIN_IDLE;

    // Roaming failure count
    private int mRoamFailCount = 0;

    // This is the BSSID we are trying to associate to, it can be set to "any"
    // if we havent selected a BSSID for joining.
    // if we havent selected a BSSID for joining.
    // The BSSID we are associated to is found in mWifiInfo
    private String mTargetRoamBSSID = "any";

    private long mLastDriverRoamAttempt = 0;

    private WifiConfiguration targetWificonfiguration = null;

    // Used as debug to indicate which configuration last was saved
    private WifiConfiguration lastSavedConfigurationAttempt = null;

    // Used as debug to indicate which configuration last was removed
    private WifiConfiguration lastForgetConfigurationAttempt = null;

    boolean isRoaming() {
        return mAutoRoaming == WifiAutoJoinController.AUTO_JOIN_ROAMING
                || mAutoRoaming == WifiAutoJoinController.AUTO_JOIN_EXTENDED_ROAMING;
    }

    public void autoRoamSetBSSID(int netId, String bssid) {
        autoRoamSetBSSID(mWifiConfigStore.getWifiConfiguration(netId), bssid);
    }

    public boolean autoRoamSetBSSID(WifiConfiguration config, String bssid) {
        boolean ret = true;
        if (mTargetRoamBSSID == null) mTargetRoamBSSID = "any";
        if (bssid == null) bssid = "any";
        if (config == null) return false; // Nothing to do

        if (mTargetRoamBSSID != null && bssid == mTargetRoamBSSID && bssid == config.BSSID) {
            return false; // We didnt change anything
        }
        if (!mTargetRoamBSSID.equals("any") && bssid.equals("any")) {
            // Changing to ANY
            if (!mWifiConfigStore.roamOnAny) {
                ret =  false; // Nothing to do
            }
        }
        if (VDBG) {
           loge("autoRoamSetBSSID " + bssid
                   + " key=" + config.configKey());
        }
        config.autoJoinBSSID = bssid;
        mTargetRoamBSSID = bssid;
        mWifiConfigStore.saveWifiConfigBSSID(config);
        return ret;
    }

    /**
     * Subset of link properties coming from netlink.
     * Currently includes IPv4 and IPv6 addresses. In the future will also include IPv6 DNS servers
     * and domains obtained from router advertisements (RFC 6106).
     */
    private NetlinkTracker mNetlinkTracker;

    private AlarmManager mAlarmManager;
    private PendingIntent mScanIntent;
    private PendingIntent mDriverStopIntent;
    private PendingIntent mBatchedScanIntervalIntent;

    /* Tracks current frequency mode */
    private AtomicInteger mFrequencyBand = new AtomicInteger(WifiManager.WIFI_FREQUENCY_BAND_AUTO);

    /* Tracks if we are filtering Multicast v4 packets. Default is to filter. */
    private AtomicBoolean mFilteringMulticastV4Packets = new AtomicBoolean(true);

    // Channel for sending replies.
    private AsyncChannel mReplyChannel = new AsyncChannel();

    private WifiP2pServiceImpl mWifiP2pServiceImpl;

    // Used to initiate a connection with WifiP2pService
    private AsyncChannel mWifiP2pChannel;
    private AsyncChannel mWifiApConfigChannel;

    private int mConnectionRequests = 0;
    private WifiNetworkFactory mNetworkFactory;
    private UntrustedWifiNetworkFactory mUntrustedNetworkFactory;
    private WifiNetworkAgent mNetworkAgent;

    // Keep track of various statistics, for retrieval by System Apps, i.e. under @SystemApi
    // We should really persist that into the networkHistory.txt file, and read it back when
    // WifiStateMachine starts up
    private WifiConnectionStatistics mWifiConnectionStatistics = new WifiConnectionStatistics();

    // Used to filter out requests we couldn't possibly satisfy.
    private final NetworkCapabilities mNetworkCapabilitiesFilter = new NetworkCapabilities();

    /* The base for wifi message types */
    static final int BASE = Protocol.BASE_WIFI;
    /* Start the supplicant */
    static final int CMD_START_SUPPLICANT                 = BASE + 11;
    /* Stop the supplicant */
    static final int CMD_STOP_SUPPLICANT                  = BASE + 12;
    /* Start the driver */
    static final int CMD_START_DRIVER                     = BASE + 13;
    /* Stop the driver */
    static final int CMD_STOP_DRIVER                      = BASE + 14;
    /* Indicates Static IP succeeded */
    static final int CMD_STATIC_IP_SUCCESS                = BASE + 15;
    /* Indicates Static IP failed */
    static final int CMD_STATIC_IP_FAILURE                = BASE + 16;
    /* Indicates supplicant stop failed */
    static final int CMD_STOP_SUPPLICANT_FAILED           = BASE + 17;
    /* Delayed stop to avoid shutting down driver too quick*/
    static final int CMD_DELAYED_STOP_DRIVER              = BASE + 18;
    /* A delayed message sent to start driver when it fail to come up */
    static final int CMD_DRIVER_START_TIMED_OUT           = BASE + 19;

    /* Start the soft access point */
    static final int CMD_START_AP                         = BASE + 21;
    /* Indicates soft ap start succeeded */
    static final int CMD_START_AP_SUCCESS                 = BASE + 22;
    /* Indicates soft ap start failed */
    static final int CMD_START_AP_FAILURE                 = BASE + 23;
    /* Stop the soft access point */
    static final int CMD_STOP_AP                          = BASE + 24;
    /* Set the soft access point configuration */
    static final int CMD_SET_AP_CONFIG                    = BASE + 25;
    /* Soft access point configuration set completed */
    static final int CMD_SET_AP_CONFIG_COMPLETED          = BASE + 26;
    /* Request the soft access point configuration */
    static final int CMD_REQUEST_AP_CONFIG                = BASE + 27;
    /* Response to access point configuration request */
    static final int CMD_RESPONSE_AP_CONFIG               = BASE + 28;
    /* Invoked when getting a tether state change notification */
    static final int CMD_TETHER_STATE_CHANGE              = BASE + 29;
    /* A delayed message sent to indicate tether state change failed to arrive */
    static final int CMD_TETHER_NOTIFICATION_TIMED_OUT    = BASE + 30;

    static final int CMD_BLUETOOTH_ADAPTER_STATE_CHANGE   = BASE + 31;

    /* Supplicant commands */
    /* Is supplicant alive ? */
    static final int CMD_PING_SUPPLICANT                  = BASE + 51;
    /* Add/update a network configuration */
    static final int CMD_ADD_OR_UPDATE_NETWORK            = BASE + 52;
    /* Delete a network */
    static final int CMD_REMOVE_NETWORK                   = BASE + 53;
    /* Enable a network. The device will attempt a connection to the given network. */
    static final int CMD_ENABLE_NETWORK                   = BASE + 54;
    /* Enable all networks */
    static final int CMD_ENABLE_ALL_NETWORKS              = BASE + 55;
    /* Blacklist network. De-prioritizes the given BSSID for connection. */
    static final int CMD_BLACKLIST_NETWORK                = BASE + 56;
    /* Clear the blacklist network list */
    static final int CMD_CLEAR_BLACKLIST                  = BASE + 57;
    /* Save configuration */
    static final int CMD_SAVE_CONFIG                      = BASE + 58;
    /* Get configured networks */
    static final int CMD_GET_CONFIGURED_NETWORKS          = BASE + 59;
    /* Get available frequencies */
    static final int CMD_GET_CAPABILITY_FREQ              = BASE + 60;
    /* Get adaptors */
    static final int CMD_GET_SUPPORTED_FEATURES           = BASE + 61;
    /* Get configured networks with real preSharedKey */
    static final int CMD_GET_PRIVILEGED_CONFIGURED_NETWORKS = BASE + 62;
    /* Get Link Layer Stats thru HAL */
    static final int CMD_GET_LINK_LAYER_STATS             = BASE + 63;
    /* Supplicant commands after driver start*/
    /* Initiate a scan */
    static final int CMD_START_SCAN                       = BASE + 71;
    /* Set operational mode. CONNECT, SCAN ONLY, SCAN_ONLY with Wi-Fi off mode */
    static final int CMD_SET_OPERATIONAL_MODE             = BASE + 72;
    /* Disconnect from a network */
    static final int CMD_DISCONNECT                       = BASE + 73;
    /* Reconnect to a network */
    static final int CMD_RECONNECT                        = BASE + 74;
    /* Reassociate to a network */
    static final int CMD_REASSOCIATE                      = BASE + 75;
    /* Get Connection Statistis */
    static final int CMD_GET_CONNECTION_STATISTICS        = BASE + 76;

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
    static final int CMD_SET_HIGH_PERF_MODE               = BASE + 77;
    /* Set the country code */
    static final int CMD_SET_COUNTRY_CODE                 = BASE + 80;
    /* Enables RSSI poll */
    static final int CMD_ENABLE_RSSI_POLL                 = BASE + 82;
    /* RSSI poll */
    static final int CMD_RSSI_POLL                        = BASE + 83;
    /* Set up packet filtering */
    static final int CMD_START_PACKET_FILTERING           = BASE + 84;
    /* Clear packet filter */
    static final int CMD_STOP_PACKET_FILTERING            = BASE + 85;
    /* Enable suspend mode optimizations in the driver */
    static final int CMD_SET_SUSPEND_OPT_ENABLED          = BASE + 86;
    /* Delayed NETWORK_DISCONNECT */
    static final int CMD_DELAYED_NETWORK_DISCONNECT       = BASE + 87;
    /* When there are no saved networks, we do a periodic scan to notify user of
     * an open network */
    static final int CMD_NO_NETWORKS_PERIODIC_SCAN        = BASE + 88;
    /* Test network Disconnection NETWORK_DISCONNECT */
    static final int CMD_TEST_NETWORK_DISCONNECT          = BASE + 89;
    private int testNetworkDisconnectCounter = 0;

    /* arg1 values to CMD_STOP_PACKET_FILTERING and CMD_START_PACKET_FILTERING */
    static final int MULTICAST_V6  = 1;
    static final int MULTICAST_V4  = 0;

   /* Set the frequency band */
    static final int CMD_SET_FREQUENCY_BAND               = BASE + 90;
    /* Enable TDLS on a specific MAC address */
    static final int CMD_ENABLE_TDLS                      = BASE + 92;
    /* DHCP/IP configuration watchdog */
    static final int CMD_OBTAINING_IP_ADDRESS_WATCHDOG_TIMER    = BASE + 93;

    /**
     * Make this timer 40 seconds, which is about the normal DHCP timeout.
     * In no valid case, the WiFiStateMachine should remain stuck in ObtainingIpAddress
     * for more than 30 seconds.
     */
    static final int OBTAINING_IP_ADDRESS_GUARD_TIMER_MSEC = 40000;

    int obtainingIpWatchdogCount = 0;

    /* Commands from/to the SupplicantStateTracker */
    /* Reset the supplicant state tracker */
    static final int CMD_RESET_SUPPLICANT_STATE           = BASE + 111;


    /**
     * Watchdog for protecting against b/16823537
     * Leave time for 4-ways handshake to succeed
     */
    static final int ROAM_GUARD_TIMER_MSEC = 15000;

    int roamWatchdogCount = 0;
    /* Roam state watchdog */
    static final int CMD_ROAM_WATCHDOG_TIMER    = BASE + 94;
    /* Screen change intent handling */
    static final int CMD_SCREEN_STATE_CHANGED              = BASE + 95;

    int disconnectingWatchdogCount = 0;
    static final int DISCONNECTING_GUARD_TIMER_MSEC = 5000;

    /* Disconnecting state watchdog */
    static final int CMD_DISCONNECTING_WATCHDOG_TIMER     = BASE + 96;

    /* Disable an ephemeral network */
    static final int CMD_DISABLE_EPHEMERAL_NETWORK = BASE + 98;

    /* P2p commands */
    /* We are ok with no response here since we wont do much with it anyway */
    public static final int CMD_ENABLE_P2P                = BASE + 131;
    /* In order to shut down supplicant cleanly, we wait till p2p has
     * been disabled */
    public static final int CMD_DISABLE_P2P_REQ           = BASE + 132;
    public static final int CMD_DISABLE_P2P_RSP           = BASE + 133;

    public static final int CMD_BOOT_COMPLETED            = BASE + 134;

    /* change the batch scan settings.
     * arg1 = responsible UID
     * arg2 = csph (channel scans per hour)
     * obj = bundle with the new settings and the optional worksource
     */
    public static final int CMD_SET_BATCHED_SCAN          = BASE + 135;
    public static final int CMD_START_NEXT_BATCHED_SCAN   = BASE + 136;
    public static final int CMD_POLL_BATCHED_SCAN         = BASE + 137;

    /* We now have a valid IP configuration. */
    static final int CMD_IP_CONFIGURATION_SUCCESSFUL      = BASE + 138;
    /* We no longer have a valid IP configuration. */
    static final int CMD_IP_CONFIGURATION_LOST            = BASE + 139;
    /* Link configuration (IP address, DNS, ...) changes notified via netlink */
    static final int CMD_UPDATE_LINKPROPERTIES            = BASE + 140;

    /* Supplicant is trying to associate to a given BSSID */
    static final int CMD_TARGET_BSSID                     = BASE + 141;

    /* Reload all networks and reconnect */
    static final int CMD_RELOAD_TLS_AND_RECONNECT         = BASE + 142;

    static final int CMD_AUTO_CONNECT                     = BASE + 143;

    static final int network_status_unwanted_disconnect = 0;
    static final int network_status_unwanted_disable_autojoin = 1;

    static final int CMD_UNWANTED_NETWORK                 = BASE + 144;

    static final int CMD_AUTO_ROAM                        = BASE + 145;

    static final int CMD_AUTO_SAVE_NETWORK                = BASE + 146;

    static final int CMD_ASSOCIATED_BSSID                = BASE + 147;

    static final int CMD_NETWORK_STATUS                  = BASE + 148;

    /* Wifi state machine modes of operation */
    /* CONNECT_MODE - connect to any 'known' AP when it becomes available */
    public static final int CONNECT_MODE                   = 1;
    /* SCAN_ONLY_MODE - don't connect to any APs; scan, but only while apps hold lock */
    public static final int SCAN_ONLY_MODE                 = 2;
    /* SCAN_ONLY_WITH_WIFI_OFF - scan, but don't connect to any APs */
    public static final int SCAN_ONLY_WITH_WIFI_OFF_MODE   = 3;

    private static final int SUCCESS = 1;
    private static final int FAILURE = -1;

    /* Tracks if suspend optimizations need to be disabled by DHCP,
     * screen or due to high perf mode.
     * When any of them needs to disable it, we keep the suspend optimizations
     * disabled
     */
    private int mSuspendOptNeedsDisabled = 0;

    private static final int SUSPEND_DUE_TO_DHCP       = 1;
    private static final int SUSPEND_DUE_TO_HIGH_PERF  = 1<<1;
    private static final int SUSPEND_DUE_TO_SCREEN     = 1<<2;

    /* Tracks if user has enabled suspend optimizations through settings */
    private AtomicBoolean mUserWantsSuspendOpt = new AtomicBoolean(true);

    /**
     * Default framework scan interval in milliseconds. This is used in the scenario in which
     * wifi chipset does not support background scanning to set up a
     * periodic wake up scan so that the device can connect to a new access
     * point on the move. {@link Settings.Global#WIFI_FRAMEWORK_SCAN_INTERVAL_MS} can
     * override this.
     */
    private final int mDefaultFrameworkScanIntervalMs;

    private int mDisconnectedScanPeriodMs = 10000;

    /**
     * Supplicant scan interval in milliseconds.
     * Comes from {@link Settings.Global#WIFI_SUPPLICANT_SCAN_INTERVAL_MS} or
     * from the default config if the setting is not set
     */
    private long mSupplicantScanIntervalMs;

    /**
     * timeStamp of last full band scan we perfoemed for autojoin while connected with screen lit
     */
    private long lastFullBandConnectedTimeMilli;

    /**
     * time interval to the next full band scan we will perform for
     * autojoin while connected with screen lit
     */
    private long fullBandConnectedTimeIntervalMilli;

    /**
     * max time interval to the next full band scan we will perform for
     * autojoin while connected with screen lit
     * Max time is 5 minutes
     */
    private static final long  maxFullBandConnectedTimeIntervalMilli = 1000 * 60 * 5;

    /**
     * Minimum time interval between enabling all networks.
     * A device can end up repeatedly connecting to a bad network on screen on/off toggle
     * due to enabling every time. We add a threshold to avoid this.
     */
    private static final int MIN_INTERVAL_ENABLE_ALL_NETWORKS_MS = 10 * 60 * 1000; /* 10 minutes */
    private long mLastEnableAllNetworksTime;

    int mRunningBeaconCount = 0;

    /**
     * Starting and shutting down driver too quick causes problems leading to driver
     * being in a bad state. Delay driver stop.
     */
    private final int mDriverStopDelayMs;
    private int mDelayedStopCounter;
    private boolean mInDelayedStop = false;

    // sometimes telephony gives us this data before boot is complete and we can't store it
    // until after, so the write is deferred
    private volatile String mPersistedCountryCode;

    // Supplicant doesn't like setting the same country code multiple times (it may drop
    // currently connected network), so we save the country code here to avoid redundency
    private String mLastSetCountryCode;

    /* Default parent state */
    private State mDefaultState = new DefaultState();
    /* Temporary initial state */
    private State mInitialState = new InitialState();
    /* Driver loaded, waiting for supplicant to start */
    private State mSupplicantStartingState = new SupplicantStartingState();
    /* Driver loaded and supplicant ready */
    private State mSupplicantStartedState = new SupplicantStartedState();
    /* Waiting for supplicant to stop and monitor to exit */
    private State mSupplicantStoppingState = new SupplicantStoppingState();
    /* Driver start issued, waiting for completed event */
    private State mDriverStartingState = new DriverStartingState();
    /* Driver started */
    private State mDriverStartedState = new DriverStartedState();
    /* Wait until p2p is disabled
     * This is a special state which is entered right after we exit out of DriverStartedState
     * before transitioning to another state.
     */
    private State mWaitForP2pDisableState = new WaitForP2pDisableState();
    /* Driver stopping */
    private State mDriverStoppingState = new DriverStoppingState();
    /* Driver stopped */
    private State mDriverStoppedState = new DriverStoppedState();
    /* Scan for networks, no connection will be established */
    private State mScanModeState = new ScanModeState();
    /* Connecting to an access point */
    private State mConnectModeState = new ConnectModeState();
    /* Connected at 802.11 (L2) level */
    private State mL2ConnectedState = new L2ConnectedState();
    /* fetching IP after connection to access point (assoc+auth complete) */
    private State mObtainingIpState = new ObtainingIpState();
    /* Waiting for link quality verification to be complete */
    private State mVerifyingLinkState = new VerifyingLinkState();
    /* Connected with IP addr */
    private State mConnectedState = new ConnectedState();
    /* Roaming */
    private State mRoamingState = new RoamingState();
    /* disconnect issued, waiting for network disconnect confirmation */
    private State mDisconnectingState = new DisconnectingState();
    /* Network is not connected, supplicant assoc+auth is not complete */
    private State mDisconnectedState = new DisconnectedState();
    /* Waiting for WPS to be completed*/
    private State mWpsRunningState = new WpsRunningState();

    /* Soft ap is starting up */
    private State mSoftApStartingState = new SoftApStartingState();
    /* Soft ap is running */
    private State mSoftApStartedState = new SoftApStartedState();
    /* Soft ap is running and we are waiting for tether notification */
    private State mTetheringState = new TetheringState();
    /* Soft ap is running and we are tethered through connectivity service */
    private State mTetheredState = new TetheredState();
    /* Waiting for untether confirmation before stopping soft Ap */
    private State mUntetheringState = new UntetheringState();

    private class TetherStateChange {
        ArrayList<String> available;
        ArrayList<String> active;
        TetherStateChange(ArrayList<String> av, ArrayList<String> ac) {
            available = av;
            active = ac;
        }
    }

    public static class SimAuthRequestData {
        int networkId;
        int protocol;
        String ssid;
        String[] challenges;
    }

    public static class SimAuthResponseData {
        int id;
        String Kc1;
        String SRES1;
        String Kc2;
        String SRES2;
        String Kc3;
        String SRES3;
    }

    /**
     * One of  {@link WifiManager#WIFI_STATE_DISABLED},
     *         {@link WifiManager#WIFI_STATE_DISABLING},
     *         {@link WifiManager#WIFI_STATE_ENABLED},
     *         {@link WifiManager#WIFI_STATE_ENABLING},
     *         {@link WifiManager#WIFI_STATE_UNKNOWN}
     *
     */
    private final AtomicInteger mWifiState = new AtomicInteger(WIFI_STATE_DISABLED);

    /**
     * One of  {@link WifiManager#WIFI_AP_STATE_DISABLED},
     *         {@link WifiManager#WIFI_AP_STATE_DISABLING},
     *         {@link WifiManager#WIFI_AP_STATE_ENABLED},
     *         {@link WifiManager#WIFI_AP_STATE_ENABLING},
     *         {@link WifiManager#WIFI_AP_STATE_FAILED}
     *
     */
    private final AtomicInteger mWifiApState = new AtomicInteger(WIFI_AP_STATE_DISABLED);

    private static final int SCAN_REQUEST = 0;
    private static final String ACTION_START_SCAN =
        "com.android.server.WifiManager.action.START_SCAN";

    private static final String DELAYED_STOP_COUNTER = "DelayedStopCounter";
    private static final int DRIVER_STOP_REQUEST = 0;
    private static final String ACTION_DELAYED_DRIVER_STOP =
        "com.android.server.WifiManager.action.DELAYED_DRIVER_STOP";

    private static final String ACTION_REFRESH_BATCHED_SCAN =
            "com.android.server.WifiManager.action.REFRESH_BATCHED_SCAN";
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

    private final IBatteryStats mBatteryStats;

    private BatchedScanSettings mBatchedScanSettings = null;

    /**
     * Track the worksource/cost of the current settings and track what's been noted
     * to the battery stats, so we can mark the end of the previous when changing.
     */
    private WorkSource mBatchedScanWorkSource = null;
    private int mBatchedScanCsph = 0;
    private WorkSource mNotedBatchedScanWorkSource = null;
    private int mNotedBatchedScanCsph = 0;

    private String mTcpBufferSizes = null;

    // Used for debug and stats gathering
    private static int sScanAlarmIntentCount = 0;

    final static int frameworkMinScanIntervalSaneValue = 10000;

    public WifiStateMachine(Context context, String wlanInterface,
            WifiTrafficPoller trafficPoller){
        super("WifiStateMachine");
        mContext = context;
        mInterfaceName = wlanInterface;
        mNetworkInfo = new NetworkInfo(ConnectivityManager.TYPE_WIFI, 0, NETWORKTYPE, "");
        mBatteryStats = IBatteryStats.Stub.asInterface(ServiceManager.getService(
                BatteryStats.SERVICE_NAME));

        IBinder b = ServiceManager.getService(Context.NETWORKMANAGEMENT_SERVICE);
        mNwService = INetworkManagementService.Stub.asInterface(b);

        mP2pSupported = mContext.getPackageManager().hasSystemFeature(
                PackageManager.FEATURE_WIFI_DIRECT);

        mWifiNative = new WifiNative(mInterfaceName);
        mWifiConfigStore = new WifiConfigStore(context, mWifiNative);
        mWifiAutoJoinController = new WifiAutoJoinController(context, this,
                mWifiConfigStore, mWifiConnectionStatistics, mWifiNative);
        mWifiMonitor = new WifiMonitor(this, mWifiNative);
        mWifiInfo = new WifiInfo();
        mSupplicantStateTracker = new SupplicantStateTracker(context, this, mWifiConfigStore,
                getHandler());
        mLinkProperties = new LinkProperties();

        IBinder s1 = ServiceManager.getService(Context.WIFI_P2P_SERVICE);
        mWifiP2pServiceImpl = (WifiP2pServiceImpl)IWifiP2pManager.Stub.asInterface(s1);

        mNetworkInfo.setIsAvailable(false);
        mLastBssid = null;
        mLastNetworkId = WifiConfiguration.INVALID_NETWORK_ID;
        mLastSignalLevel = -1;

        mNetlinkTracker = new NetlinkTracker(mInterfaceName, new NetlinkTracker.Callback() {
            public void update() {
                sendMessage(CMD_UPDATE_LINKPROPERTIES);
            }
        });
        try {
            mNwService.registerObserver(mNetlinkTracker);
        } catch (RemoteException e) {
            loge("Couldn't register netlink tracker: " + e.toString());
        }

        mAlarmManager = (AlarmManager)mContext.getSystemService(Context.ALARM_SERVICE);
        mScanIntent = getPrivateBroadcast(ACTION_START_SCAN, SCAN_REQUEST);
        mBatchedScanIntervalIntent = getPrivateBroadcast(ACTION_REFRESH_BATCHED_SCAN, 0);

        // Make sure the interval is not configured less than 10 seconds
        int period = mContext.getResources().getInteger(
                R.integer.config_wifi_framework_scan_interval);
        if (period < frameworkMinScanIntervalSaneValue) {
            period = frameworkMinScanIntervalSaneValue;
        }
        mDefaultFrameworkScanIntervalMs = period;
        mDriverStopDelayMs = mContext.getResources().getInteger(
                R.integer.config_wifi_driver_stop_delay);

        mBackgroundScanSupported = mContext.getResources().getBoolean(
                R.bool.config_wifi_background_scan_support);

        mPrimaryDeviceType = mContext.getResources().getString(
                R.string.config_wifi_p2p_device_type);

        mUserWantsSuspendOpt.set(Settings.Global.getInt(mContext.getContentResolver(),
                    Settings.Global.WIFI_SUSPEND_OPTIMIZATIONS_ENABLED, 1) == 1);

        mNetworkCapabilitiesFilter.addTransportType(NetworkCapabilities.TRANSPORT_WIFI);
        mNetworkCapabilitiesFilter.addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        mNetworkCapabilitiesFilter.addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED);
        mNetworkCapabilitiesFilter.setLinkUpstreamBandwidthKbps(1024 * 1024);
        mNetworkCapabilitiesFilter.setLinkDownstreamBandwidthKbps(1024 * 1024);
        // TODO - needs to be a bit more dynamic
        mNetworkCapabilities = new NetworkCapabilities(mNetworkCapabilitiesFilter);

        mContext.registerReceiver(
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    ArrayList<String> available = intent.getStringArrayListExtra(
                            ConnectivityManager.EXTRA_AVAILABLE_TETHER);
                    ArrayList<String> active = intent.getStringArrayListExtra(
                            ConnectivityManager.EXTRA_ACTIVE_TETHER);
                    sendMessage(CMD_TETHER_STATE_CHANGE, new TetherStateChange(available, active));
                }
            },new IntentFilter(ConnectivityManager.ACTION_TETHER_STATE_CHANGED));

        mContext.registerReceiver(
                new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        sScanAlarmIntentCount++; // Used for debug only
                        startScan(SCAN_ALARM_SOURCE, mDelayedScanCounter.incrementAndGet(), null, null);
                        if (VDBG)
                            loge("WiFiStateMachine SCAN ALARM -> " + mDelayedScanCounter.get());
                    }
                },
                new IntentFilter(ACTION_START_SCAN));

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(ACTION_REFRESH_BATCHED_SCAN);
        mContext.registerReceiver(
                new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        String action = intent.getAction();

                        if (action.equals(Intent.ACTION_SCREEN_ON)) {
                            sendMessage(CMD_SCREEN_STATE_CHANGED, 1);
                        } else if (action.equals(Intent.ACTION_SCREEN_OFF)) {
                            sendMessage(CMD_SCREEN_STATE_CHANGED, 0);
                        } else if (action.equals(ACTION_REFRESH_BATCHED_SCAN)) {
                            startNextBatchedScanAsync();
                        }
                    }
                }, filter);

        mContext.registerReceiver(
                new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                       int counter = intent.getIntExtra(DELAYED_STOP_COUNTER, 0);
                       sendMessage(CMD_DELAYED_STOP_DRIVER, counter, 0);
                    }
                },
                new IntentFilter(ACTION_DELAYED_DRIVER_STOP));

        mContext.getContentResolver().registerContentObserver(Settings.Global.getUriFor(
                Settings.Global.WIFI_SUSPEND_OPTIMIZATIONS_ENABLED), false,
                new ContentObserver(getHandler()) {
                    @Override
                    public void onChange(boolean selfChange) {
                        mUserWantsSuspendOpt.set(Settings.Global.getInt(mContext.getContentResolver(),
                                Settings.Global.WIFI_SUSPEND_OPTIMIZATIONS_ENABLED, 1) == 1);
                    }
                });

        mContext.registerReceiver(
                new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        sendMessage(CMD_BOOT_COMPLETED);
                    }
                },
                new IntentFilter(Intent.ACTION_BOOT_COMPLETED));

        mScanResultCache = new LruCache<String, ScanResult>(SCAN_RESULT_CACHE_SIZE);

        PowerManager powerManager = (PowerManager)mContext.getSystemService(Context.POWER_SERVICE);
        mWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, getName());

        mSuspendWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WifiSuspend");
        mSuspendWakeLock.setReferenceCounted(false);

        mTcpBufferSizes = mContext.getResources().getString(
                com.android.internal.R.string.config_wifi_tcp_buffers);

        addState(mDefaultState);
            addState(mInitialState, mDefaultState);
            addState(mSupplicantStartingState, mDefaultState);
            addState(mSupplicantStartedState, mDefaultState);
                addState(mDriverStartingState, mSupplicantStartedState);
                addState(mDriverStartedState, mSupplicantStartedState);
                    addState(mScanModeState, mDriverStartedState);
                    addState(mConnectModeState, mDriverStartedState);
                        addState(mL2ConnectedState, mConnectModeState);
                            addState(mObtainingIpState, mL2ConnectedState);
                            addState(mVerifyingLinkState, mL2ConnectedState);
                            addState(mConnectedState, mL2ConnectedState);
                            addState(mRoamingState, mL2ConnectedState);
                        addState(mDisconnectingState, mConnectModeState);
                        addState(mDisconnectedState, mConnectModeState);
                        addState(mWpsRunningState, mConnectModeState);
                addState(mWaitForP2pDisableState, mSupplicantStartedState);
                addState(mDriverStoppingState, mSupplicantStartedState);
                addState(mDriverStoppedState, mSupplicantStartedState);
            addState(mSupplicantStoppingState, mDefaultState);
            addState(mSoftApStartingState, mDefaultState);
            addState(mSoftApStartedState, mDefaultState);
                addState(mTetheringState, mSoftApStartedState);
                addState(mTetheredState, mSoftApStartedState);
                addState(mUntetheringState, mSoftApStartedState);

        setInitialState(mInitialState);

        setLogRecSize(ActivityManager.isLowRamDeviceStatic() ? 100 : 3000);
        setLogOnlyTransitions(false);
        if (VDBG) setDbg(true);

        //start the state machine
        start();

        final Intent intent = new Intent(WifiManager.WIFI_SCAN_AVAILABLE);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        intent.putExtra(WifiManager.EXTRA_SCAN_AVAILABLE, WIFI_STATE_DISABLED);
        mContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
    }


    PendingIntent getPrivateBroadcast(String action, int requestCode) {
        Intent intent = new Intent(action, null);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        intent.setPackage(this.getClass().getPackage().getName());
        return PendingIntent.getBroadcast(mContext, requestCode, intent, 0);
    }

    private int mVerboseLoggingLevel = 0;

    int getVerboseLoggingLevel() {
        return mVerboseLoggingLevel;
    }

    void enableVerboseLogging(int verbose) {
        mVerboseLoggingLevel = verbose;
        if (verbose > 0) {
            DBG = true;
            VDBG = true;
            PDBG = true;
            mLogMessages = true;
            mWifiNative.setSupplicantLogLevel("DEBUG");
        } else {
            DBG = false;
            VDBG = false;
            PDBG = false;
            mLogMessages = false;
            mWifiNative.setSupplicantLogLevel("INFO");
        }
        mWifiAutoJoinController.enableVerboseLogging(verbose);
        mWifiMonitor.enableVerboseLogging(verbose);
        mWifiNative.enableVerboseLogging(verbose);
        mWifiConfigStore.enableVerboseLogging(verbose);
        mSupplicantStateTracker.enableVerboseLogging(verbose);
    }

    private int mAggressiveHandover = 0;

    int getAggressiveHandover() {
        return mAggressiveHandover;
    }

    void enableAggressiveHandover(int enabled) {
        mAggressiveHandover = enabled;
    }

    public void setAllowScansWithTraffic(int enabled) {
        mWifiConfigStore.alwaysEnableScansWhileAssociated = enabled;
    }

    public int getAllowScansWithTraffic() {
        return mWifiConfigStore.alwaysEnableScansWhileAssociated;
    }

    /*
     *
     * Framework scan control
     */

    private boolean mAlarmEnabled = false;
    /* This is set from the overlay config file or from a secure setting.
     * A value of 0 disables scanning in the framework.
     */
    private long mFrameworkScanIntervalMs = 10000;

    private AtomicInteger mDelayedScanCounter = new AtomicInteger();

    private void setScanAlarm(boolean enabled) {
        if (PDBG) {
            loge("setScanAlarm " + enabled
                    + " period " + mDefaultFrameworkScanIntervalMs
                    + " mBackgroundScanSupported " + mBackgroundScanSupported);
        }
        if (mBackgroundScanSupported == false) {
            // Scan alarm is only used for background scans if they are not
            // offloaded to the wifi chipset, hence enable the scan alarm
            // gicing us RTC_WAKEUP of backgroundScan is NOT supported
            enabled = true;
        }

        if (enabled == mAlarmEnabled) return;
        if (enabled) {
            /* Set RTC_WAKEUP alarms if PNO is not supported - because no one is */
            /* going to wake up the host processor to look for access points */
            mAlarmManager.set(AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + mDefaultFrameworkScanIntervalMs,
                    mScanIntent);
            mAlarmEnabled = true;
        } else {
            mAlarmManager.cancel(mScanIntent);
            mAlarmEnabled = false;
        }
    }

    private void cancelDelayedScan() {
        mDelayedScanCounter.incrementAndGet();
        loge("cancelDelayedScan -> " + mDelayedScanCounter);
    }

    private boolean checkAndRestartDelayedScan(int counter, boolean restart, int milli,
                                   ScanSettings settings, WorkSource workSource) {
        if (counter != mDelayedScanCounter.get()) {
            return false;
        }
        if (restart)
            startDelayedScan(milli, settings, workSource);
        return true;
    }

    private void startDelayedScan(int milli, ScanSettings settings, WorkSource workSource) {
        if (milli <= 0) return;
        /**
         * The cases where the scan alarm should be run are :
         * - DisconnectedState && screenOn => used delayed timer
         * - DisconnectedState && !screenOn && mBackgroundScanSupported => PNO
         * - DisconnectedState && !screenOn && !mBackgroundScanSupported => used RTC_WAKEUP Alarm
         * - ConnectedState && screenOn => used delayed timer
         */

        mDelayedScanCounter.incrementAndGet();
        if (mScreenOn &&
                (getCurrentState() == mDisconnectedState
                || getCurrentState() == mConnectedState)) {
            Bundle bundle = new Bundle();
            bundle.putParcelable(CUSTOMIZED_SCAN_SETTING, settings);
            bundle.putParcelable(CUSTOMIZED_SCAN_WORKSOURCE, workSource);
            bundle.putLong(SCAN_REQUEST_TIME, System.currentTimeMillis());
            sendMessageDelayed(CMD_START_SCAN, SCAN_ALARM_SOURCE,
                    mDelayedScanCounter.get(), bundle, milli);
            if (DBG) loge("startDelayedScan send -> " + mDelayedScanCounter + " milli " + milli);
        } else if (mBackgroundScanSupported == false
                && !mScreenOn && getCurrentState() == mDisconnectedState) {
            setScanAlarm(true);
            if (DBG) loge("startDelayedScan start scan alarm -> "
                    + mDelayedScanCounter + " milli " + milli);
        } else {
            if (DBG) loge("startDelayedScan unhandled -> "
                    + mDelayedScanCounter + " milli " + milli);
        }
    }

    private boolean setRandomMacOui() {
        String oui = mContext.getResources().getString(
                R.string.config_wifi_random_mac_oui, GOOGLE_OUI);
        String[] ouiParts = oui.split("-");
        byte[] ouiBytes = new byte[3];
        ouiBytes[0] = (byte) (Integer.parseInt(ouiParts[0], 16) & 0xFF);
        ouiBytes[1] = (byte) (Integer.parseInt(ouiParts[1], 16) & 0xFF);
        ouiBytes[2] = (byte) (Integer.parseInt(ouiParts[2], 16) & 0xFF);

        logd("Setting OUI to " + oui);
        return mWifiNative.setScanningMacOui(ouiBytes);
    }

    /*********************************************************
     * Methods exposed for public use
     ********************************************************/

    public Messenger getMessenger() {
        return new Messenger(getHandler());
    }

    public WifiMonitor getWifiMonitor() {
        return mWifiMonitor;
    }

    /**
     * TODO: doc
     */
    public boolean syncPingSupplicant(AsyncChannel channel) {
        Message resultMsg = channel.sendMessageSynchronously(CMD_PING_SUPPLICANT);
        boolean result = (resultMsg.arg1 != FAILURE);
        resultMsg.recycle();
        return result;
    }

    public List<WifiChannel> syncGetChannelList(AsyncChannel channel) {
        Message resultMsg = channel.sendMessageSynchronously(CMD_GET_CAPABILITY_FREQ);
        List<WifiChannel> list = null;
        if (resultMsg.obj != null) {
            list = new ArrayList<WifiChannel>();
            String freqs = (String) resultMsg.obj;
            String[] lines = freqs.split("\n");
            for (String line : lines)
                if (line.contains("MHz")) {
                    // line format: " 52 = 5260 MHz (NO_IBSS) (DFS)"
                    WifiChannel c = new WifiChannel();
                    String[] prop = line.split(" ");
                    if (prop.length < 5) continue;
                    try {
                        c.channelNum = Integer.parseInt(prop[1]);
                        c.freqMHz = Integer.parseInt(prop[3]);
                    } catch (NumberFormatException e) { }
                    c.isDFS = line.contains("(DFS)");
                    list.add(c);
                } else if (line.contains("Mode[B] Channels:")) {
                    // B channels are the same as G channels, skipped
                    break;
                }
        }
        resultMsg.recycle();
        return (list != null && list.size() > 0) ? list : null;
    }

    /**
     * When settings allowing making use of untrusted networks change, trigger a scan
     * so as to kick of autojoin.
     */
    public void startScanForUntrustedSettingChange() {
        startScan(SET_ALLOW_UNTRUSTED_SOURCE, 0, null, null);
    }

    /**
     * Initiate a wifi scan. If workSource is not null, blame is given to it, otherwise blame is
     * given to callingUid.
     *
     * @param callingUid The uid initiating the wifi scan. Blame will be given here unless
     *                   workSource is specified.
     * @param workSource If not null, blame is given to workSource.
     * @param settings Scan settings, see {@link ScanSettings}.
     */
    public void startScan(int callingUid, int scanCounter,
                          ScanSettings settings, WorkSource workSource) {
        Bundle bundle = new Bundle();
        bundle.putParcelable(CUSTOMIZED_SCAN_SETTING, settings);
        bundle.putParcelable(CUSTOMIZED_SCAN_WORKSOURCE, workSource);
        bundle.putLong(SCAN_REQUEST_TIME, System.currentTimeMillis());
        sendMessage(CMD_START_SCAN, callingUid, scanCounter, bundle);
    }

    /**
     * start or stop batched scanning using the given settings
     */
    public void setBatchedScanSettings(BatchedScanSettings settings, int callingUid, int csph,
            WorkSource workSource) {
        Bundle bundle = new Bundle();
        bundle.putParcelable(BATCHED_SETTING, settings);
        bundle.putParcelable(BATCHED_WORKSOURCE, workSource);
        sendMessage(CMD_SET_BATCHED_SCAN, callingUid, csph, bundle);
    }

    public List<BatchedScanResult> syncGetBatchedScanResultsList() {
        synchronized (mBatchedScanResults) {
            List<BatchedScanResult> batchedScanList =
                    new ArrayList<BatchedScanResult>(mBatchedScanResults.size());
            for(BatchedScanResult result: mBatchedScanResults) {
                batchedScanList.add(new BatchedScanResult(result));
            }
            return batchedScanList;
        }
    }

    public void requestBatchedScanPoll() {
        sendMessage(CMD_POLL_BATCHED_SCAN);
    }

    private void startBatchedScan() {
        if (mBatchedScanSettings == null) return;

        if (mDhcpActive) {
            if (DBG) log("not starting Batched Scans due to DHCP");
            return;
        }

        // first grab any existing data
        retrieveBatchedScanData();

        if (PDBG) loge("try  starting Batched Scans due to DHCP");


        mAlarmManager.cancel(mBatchedScanIntervalIntent);

        String scansExpected = mWifiNative.setBatchedScanSettings(mBatchedScanSettings);
        try {
            mExpectedBatchedScans = Integer.parseInt(scansExpected);
            setNextBatchedAlarm(mExpectedBatchedScans);
            if (mExpectedBatchedScans > 0) noteBatchedScanStart();
        } catch (NumberFormatException e) {
            stopBatchedScan();
            loge("Exception parsing WifiNative.setBatchedScanSettings response " + e);
        }
    }

    // called from BroadcastListener
    private void startNextBatchedScanAsync() {
        sendMessage(CMD_START_NEXT_BATCHED_SCAN);
    }

    private void startNextBatchedScan() {
        // first grab any existing data
        retrieveBatchedScanData();

        setNextBatchedAlarm(mExpectedBatchedScans);
    }

    private void handleBatchedScanPollRequest() {
        if (DBG) {
            log("handleBatchedScanPoll Request - mBatchedScanMinPollTime=" +
                    mBatchedScanMinPollTime + " , mBatchedScanSettings=" +
                    mBatchedScanSettings);
        }
        // if there is no appropriate PollTime that's because we either aren't
        // batching or we've already set a time for a poll request
        if (mBatchedScanMinPollTime == 0) return;
        if (mBatchedScanSettings == null) return;

        long now = System.currentTimeMillis();

        if (now > mBatchedScanMinPollTime) {
            // do the poll and reset our timers
            startNextBatchedScan();
        } else {
            mAlarmManager.setExact(AlarmManager.RTC_WAKEUP, mBatchedScanMinPollTime,
                    mBatchedScanIntervalIntent);
            mBatchedScanMinPollTime = 0;
        }
    }

    // return true if new/different
    private boolean recordBatchedScanSettings(int responsibleUid, int csph, Bundle bundle) {
        BatchedScanSettings settings = bundle.getParcelable(BATCHED_SETTING);
        WorkSource responsibleWorkSource = bundle.getParcelable(BATCHED_WORKSOURCE);

        if (DBG) {
            log("set batched scan to " + settings + " for uid=" + responsibleUid +
                    ", worksource=" + responsibleWorkSource);
        }
        if (settings != null) {
            if (settings.equals(mBatchedScanSettings)) return false;
        } else {
            if (mBatchedScanSettings == null) return false;
        }
        mBatchedScanSettings = settings;
        if (responsibleWorkSource == null) responsibleWorkSource = new WorkSource(responsibleUid);
        mBatchedScanWorkSource = responsibleWorkSource;
        mBatchedScanCsph = csph;
        return true;
    }

    private void stopBatchedScan() {
        mAlarmManager.cancel(mBatchedScanIntervalIntent);
        retrieveBatchedScanData();
        mWifiNative.setBatchedScanSettings(null);
        noteBatchedScanStop();
    }

    private void setNextBatchedAlarm(int scansExpected) {

        if (mBatchedScanSettings == null || scansExpected < 1) return;

        mBatchedScanMinPollTime = System.currentTimeMillis() +
                mBatchedScanSettings.scanIntervalSec * 1000;

        if (mBatchedScanSettings.maxScansPerBatch < scansExpected) {
            scansExpected = mBatchedScanSettings.maxScansPerBatch;
        }

        int secToFull = mBatchedScanSettings.scanIntervalSec;
        secToFull *= scansExpected;

        int debugPeriod = SystemProperties.getInt("wifi.batchedScan.pollPeriod", 0);
        if (debugPeriod > 0) secToFull = debugPeriod;

        // set the alarm to do the next poll.  We set it a little short as we'd rather
        // wake up wearly than miss a scan due to buffer overflow
        mAlarmManager.setExact(AlarmManager.RTC_WAKEUP, System.currentTimeMillis()
                + ((secToFull - (mBatchedScanSettings.scanIntervalSec / 2)) * 1000),
                mBatchedScanIntervalIntent);
    }

    /**
     * Start reading new scan data
     * Data comes in as:
     * "scancount=5\n"
     * "nextcount=5\n"
     *   "apcount=3\n"
     *   "trunc\n" (optional)
     *     "bssid=...\n"
     *     "ssid=...\n"
     *     "freq=...\n" (in Mhz)
     *     "level=...\n"
     *     "dist=...\n" (in cm)
     *     "distsd=...\n" (standard deviation, in cm)
     *     "===="
     *     "bssid=...\n"
     *     etc
     *     "===="
     *     "bssid=...\n"
     *     etc
     *     "%%%%"
     *   "apcount=2\n"
     *     "bssid=...\n"
     *     etc
     *     "%%%%
     *   etc
     *   "----"
     */
    private final static boolean DEBUG_PARSE = false;
    private void retrieveBatchedScanData() {
        String rawData = mWifiNative.getBatchedScanResults();
        if (DEBUG_PARSE) log("rawData = " + rawData);
        mBatchedScanMinPollTime = 0;
        if (rawData == null || rawData.equalsIgnoreCase("OK")) {
            loge("Unexpected BatchedScanResults :" + rawData);
            return;
        }

        int scanCount = 0;
        final String END_OF_BATCHES = "----";
        final String SCANCOUNT = "scancount=";
        final String TRUNCATED = "trunc";
        final String AGE = "age=";
        final String DIST = "dist=";
        final String DISTSD = "distSd=";

        String splitData[] = rawData.split("\n");
        int n = 0;
        if (splitData[n].startsWith(SCANCOUNT)) {
            try {
                scanCount = Integer.parseInt(splitData[n++].substring(SCANCOUNT.length()));
            } catch (NumberFormatException e) {
                loge("scancount parseInt Exception from " + splitData[n]);
            }
        } else log("scancount not found");
        if (scanCount == 0) {
            loge("scanCount==0 - aborting");
            return;
        }

        final Intent intent = new Intent(WifiManager.BATCHED_SCAN_RESULTS_AVAILABLE_ACTION);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);

        synchronized (mBatchedScanResults) {
            mBatchedScanResults.clear();
            BatchedScanResult batchedScanResult = new BatchedScanResult();

            String bssid = null;
            WifiSsid wifiSsid = null;
            int level = 0;
            int freq = 0;
            int dist, distSd;
            long tsf = 0;
            dist = distSd = ScanResult.UNSPECIFIED;
            final long now = SystemClock.elapsedRealtime();
            final int bssidStrLen = BSSID_STR.length();

            while (true) {
                while (n < splitData.length) {
                    if (DEBUG_PARSE) logd("parsing " + splitData[n]);
                    if (splitData[n].equals(END_OF_BATCHES)) {
                        if (n+1 != splitData.length) {
                            loge("didn't consume " + (splitData.length-n));
                        }
                        if (mBatchedScanResults.size() > 0) {
                            mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
                        }
                        logd("retrieveBatchedScanResults X");
                        return;
                    }
                    if ((splitData[n].equals(END_STR)) || splitData[n].equals(DELIMITER_STR)) {
                        if (bssid != null) {
                            batchedScanResult.scanResults.add(new ScanResult(
                                    wifiSsid, bssid, "", level, freq, tsf, dist, distSd));
                            wifiSsid = null;
                            bssid = null;
                            level = 0;
                            freq = 0;
                            tsf = 0;
                            dist = distSd = ScanResult.UNSPECIFIED;
                        }
                        if (splitData[n].equals(END_STR)) {
                            if (batchedScanResult.scanResults.size() != 0) {
                                mBatchedScanResults.add(batchedScanResult);
                                batchedScanResult = new BatchedScanResult();
                            } else {
                                logd("Found empty batch");
                            }
                        }
                    } else if (splitData[n].equals(TRUNCATED)) {
                        batchedScanResult.truncated = true;
                    } else if (splitData[n].startsWith(BSSID_STR)) {
                        bssid = new String(splitData[n].getBytes(), bssidStrLen,
                                splitData[n].length() - bssidStrLen);
                    } else if (splitData[n].startsWith(FREQ_STR)) {
                        try {
                            freq = Integer.parseInt(splitData[n].substring(FREQ_STR.length()));
                        } catch (NumberFormatException e) {
                            loge("Invalid freqency: " + splitData[n]);
                            freq = 0;
                        }
                    } else if (splitData[n].startsWith(AGE)) {
                        try {
                            tsf = now - Long.parseLong(splitData[n].substring(AGE.length()));
                            tsf *= 1000; // convert mS -> uS
                        } catch (NumberFormatException e) {
                            loge("Invalid timestamp: " + splitData[n]);
                            tsf = 0;
                        }
                    } else if (splitData[n].startsWith(SSID_STR)) {
                        wifiSsid = WifiSsid.createFromAsciiEncoded(
                                splitData[n].substring(SSID_STR.length()));
                    } else if (splitData[n].startsWith(LEVEL_STR)) {
                        try {
                            level = Integer.parseInt(splitData[n].substring(LEVEL_STR.length()));
                            if (level > 0) level -= 256;
                        } catch (NumberFormatException e) {
                            loge("Invalid level: " + splitData[n]);
                            level = 0;
                        }
                    } else if (splitData[n].startsWith(DIST)) {
                        try {
                            dist = Integer.parseInt(splitData[n].substring(DIST.length()));
                        } catch (NumberFormatException e) {
                            loge("Invalid distance: " + splitData[n]);
                            dist = ScanResult.UNSPECIFIED;
                        }
                    } else if (splitData[n].startsWith(DISTSD)) {
                        try {
                            distSd = Integer.parseInt(splitData[n].substring(DISTSD.length()));
                        } catch (NumberFormatException e) {
                            loge("Invalid distanceSd: " + splitData[n]);
                            distSd = ScanResult.UNSPECIFIED;
                        }
                    } else {
                        loge("Unable to parse batched scan result line: " + splitData[n]);
                    }
                    n++;
                }
                rawData = mWifiNative.getBatchedScanResults();
                if (DEBUG_PARSE) log("reading more data:\n" + rawData);
                if (rawData == null) {
                    loge("Unexpected null BatchedScanResults");
                    return;
                }
                splitData = rawData.split("\n");
                if (splitData.length == 0 || splitData[0].equals("ok")) {
                    loge("batch scan results just ended!");
                    if (mBatchedScanResults.size() > 0) {
                        mContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
                    }
                    return;
                }
                n = 0;
            }
        }
    }

    private long mDisconnectedTimeStamp = 0;

    public long getDisconnectedTimeMilli() {
        if (getCurrentState() == mDisconnectedState
                && mDisconnectedTimeStamp != 0) {
            long now_ms = System.currentTimeMillis();
            return now_ms - mDisconnectedTimeStamp;
        }
        return 0;
    }

    // Keeping track of scan requests
    private long lastStartScanTimeStamp = 0;
    private long lastScanDuration = 0;
    // Last connect attempt is used to prevent scan requests:
    //  - for a period of 10 seconds after attempting to connect
    private long lastConnectAttempt = 0;
    private String lastScanFreqs = null;

    // For debugging, keep track of last message status handling
    // TODO, find an equivalent mechanism as part of parent class
    private static int MESSAGE_HANDLING_STATUS_PROCESSED = 2;
    private static int MESSAGE_HANDLING_STATUS_OK = 1;
    private static int MESSAGE_HANDLING_STATUS_UNKNOWN = 0;
    private static int MESSAGE_HANDLING_STATUS_REFUSED = -1;
    private static int MESSAGE_HANDLING_STATUS_FAIL = -2;
    private static int MESSAGE_HANDLING_STATUS_OBSOLETE = -3;
    private static int MESSAGE_HANDLING_STATUS_DEFERRED = -4;
    private static int MESSAGE_HANDLING_STATUS_DISCARD = -5;
    private static int MESSAGE_HANDLING_STATUS_LOOPED = -6;
    private static int MESSAGE_HANDLING_STATUS_HANDLING_ERROR = -7;

    private int messageHandlingStatus = 0;

    //TODO: this is used only to track connection attempts, however the link state and packet per
    //TODO: second logic should be folded into that
    private boolean checkOrDeferScanAllowed(Message msg) {
        long now = System.currentTimeMillis();
        if (lastConnectAttempt != 0 && (now - lastConnectAttempt) < 10000) {
            Message dmsg = Message.obtain(msg);
            sendMessageDelayed(dmsg, 11000 - (now - lastConnectAttempt));
            return false;
        }
        return true;
    }

    private int mOnTime = 0;
    private int mTxTime = 0;
    private int mRxTime = 0;
    private int mOnTimeStartScan = 0;
    private int mTxTimeStartScan = 0;
    private int mRxTimeStartScan = 0;
    private int mOnTimeScan = 0;
    private int mTxTimeScan = 0;
    private int mRxTimeScan = 0;
    private int mOnTimeThisScan = 0;
    private int mTxTimeThisScan = 0;
    private int mRxTimeThisScan = 0;

    private int mOnTimeScreenStateChange = 0;
    private int mOnTimeAtLastReport = 0;
    private long lastOntimeReportTimeStamp = 0;
    private long lastScreenStateChangeTimeStamp = 0;
    private int mOnTimeLastReport = 0;
    private int mTxTimeLastReport = 0;
    private int mRxTimeLastReport = 0;

    private long lastLinkLayerStatsUpdate = 0;

    String reportOnTime() {
        long now = System.currentTimeMillis();
        StringBuilder sb = new StringBuilder();
        // Report stats since last report
        int on = mOnTime - mOnTimeLastReport;
        mOnTimeLastReport = mOnTime;
        int tx = mTxTime - mTxTimeLastReport;
        mTxTimeLastReport = mTxTime;
        int rx = mRxTime - mRxTimeLastReport;
        mRxTimeLastReport = mRxTime;
        int period = (int)(now - lastOntimeReportTimeStamp);
        lastOntimeReportTimeStamp = now;
        sb.append(String.format("[on:%d tx:%d rx:%d period:%d]", on, tx, rx, period));
        // Report stats since Screen State Changed
        on = mOnTime - mOnTimeScreenStateChange;
        period = (int)(now - lastScreenStateChangeTimeStamp);
        sb.append(String.format(" from screen [on:%d period:%d]", on, period));
        return sb.toString();
    }

    WifiLinkLayerStats getWifiLinkLayerStats(boolean dbg) {
        WifiLinkLayerStats stats = null;
        if (mWifiLinkLayerStatsSupported > 0) {
            String name = "wlan0";
            stats = mWifiNative.getWifiLinkLayerStats(name);
            if (name != null && stats == null && mWifiLinkLayerStatsSupported > 0) {
                mWifiLinkLayerStatsSupported -= 1;
            } else if (stats != null) {
                lastLinkLayerStatsUpdate = System.currentTimeMillis();
                mOnTime = stats.on_time;
                mTxTime = stats.tx_time;
                mRxTime = stats.rx_time;
                mRunningBeaconCount = stats.beacon_rx;
                if (dbg) {
                    loge(stats.toString());
                }
            }
        }
        if (stats == null || mWifiLinkLayerStatsSupported <= 0) {
            long mTxPkts = TrafficStats.getTxPackets(mInterfaceName);
            long mRxPkts = TrafficStats.getRxPackets(mInterfaceName);
            mWifiInfo.updatePacketRates(mTxPkts, mRxPkts);
        } else {
            mWifiInfo.updatePacketRates(stats);
        }
        return stats;
    }

    void startRadioScanStats() {
        WifiLinkLayerStats stats = getWifiLinkLayerStats(false);
        if (stats != null) {
            mOnTimeStartScan = stats.on_time;
            mTxTimeStartScan = stats.tx_time;
            mRxTimeStartScan = stats.rx_time;
            mOnTime = stats.on_time;
            mTxTime = stats.tx_time;
            mRxTime = stats.rx_time;
        }
    }

    void closeRadioScanStats() {
        WifiLinkLayerStats stats = getWifiLinkLayerStats(false);
        if (stats != null) {
            mOnTimeThisScan = stats.on_time - mOnTimeStartScan;
            mTxTimeThisScan = stats.tx_time - mTxTimeStartScan;
            mRxTimeThisScan = stats.rx_time - mRxTimeStartScan;
            mOnTimeScan += mOnTimeThisScan;
            mTxTimeScan += mTxTimeThisScan;
            mRxTimeScan += mRxTimeThisScan;
        }
    }

    // If workSource is not null, blame is given to it, otherwise blame is given to callingUid.
    private void noteScanStart(int callingUid, WorkSource workSource) {
        long now = System.currentTimeMillis();
        lastStartScanTimeStamp = now;
        lastScanDuration = 0;
        if (DBG) {
            String ts = String.format("[%,d ms]", now);
            if (workSource != null) {
                loge(ts + " noteScanStart" + workSource.toString()
                        + " uid " + Integer.toString(callingUid));
            } else {
                loge(ts + " noteScanstart no scan source"
                        + " uid " + Integer.toString(callingUid));
            }
        }
        startRadioScanStats();
        if (mScanWorkSource == null && ((callingUid != UNKNOWN_SCAN_SOURCE
                && callingUid != SCAN_ALARM_SOURCE)
                || workSource != null)) {
            mScanWorkSource = workSource != null ? workSource : new WorkSource(callingUid);
            try {
                mBatteryStats.noteWifiScanStartedFromSource(mScanWorkSource);
            } catch (RemoteException e) {
                log(e.toString());
            }
        }
    }

    private void noteScanEnd() {
        long now = System.currentTimeMillis();
        if (lastStartScanTimeStamp != 0) {
            lastScanDuration = now - lastStartScanTimeStamp;
        }
        lastStartScanTimeStamp = 0;
        if (DBG) {
            String ts = String.format("[%,d ms]", now);
            if (mScanWorkSource != null)
                loge(ts + " noteScanEnd " + mScanWorkSource.toString()
                        + " onTime=" + mOnTimeThisScan);
            else
                loge(ts + " noteScanEnd no scan source"
                        + " onTime=" + mOnTimeThisScan);
        }
        if (mScanWorkSource != null) {
            try {
                mBatteryStats.noteWifiScanStoppedFromSource(mScanWorkSource);
            } catch (RemoteException e) {
                log(e.toString());
            } finally {
                mScanWorkSource = null;
            }
        }
    }

    private void noteBatchedScanStart() {
        if (PDBG) loge("noteBatchedScanstart()");
        // note the end of a previous scan set
        if (mNotedBatchedScanWorkSource != null &&
                (mNotedBatchedScanWorkSource.equals(mBatchedScanWorkSource) == false ||
                 mNotedBatchedScanCsph != mBatchedScanCsph)) {
            try {
                mBatteryStats.noteWifiBatchedScanStoppedFromSource(mNotedBatchedScanWorkSource);
            } catch (RemoteException e) {
                log(e.toString());
            } finally {
                mNotedBatchedScanWorkSource = null;
                mNotedBatchedScanCsph = 0;
            }
        }
        // note the start of the new
        try {
            mBatteryStats.noteWifiBatchedScanStartedFromSource(mBatchedScanWorkSource,
                    mBatchedScanCsph);
            mNotedBatchedScanWorkSource = mBatchedScanWorkSource;
            mNotedBatchedScanCsph = mBatchedScanCsph;
        } catch (RemoteException e) {
            log(e.toString());
        }
    }

    private void noteBatchedScanStop() {
        if (PDBG) loge("noteBatchedScanstop()");

        if (mNotedBatchedScanWorkSource != null) {
            try {
                mBatteryStats.noteWifiBatchedScanStoppedFromSource(mNotedBatchedScanWorkSource);
            } catch (RemoteException e) {
                log(e.toString());
            } finally {
                mNotedBatchedScanWorkSource = null;
                mNotedBatchedScanCsph = 0;
            }
        }
    }

    private void handleScanRequest(int type, Message message) {
        ScanSettings settings = null;
        WorkSource workSource = null;

        // unbundle parameters
        Bundle bundle = (Bundle) message.obj;

        if (bundle != null) {
            settings = bundle.getParcelable(CUSTOMIZED_SCAN_SETTING);
            workSource = bundle.getParcelable(CUSTOMIZED_SCAN_WORKSOURCE);
        }

        // parse scan settings
        String freqs = null;
        if (settings != null && settings.channelSet != null) {
            StringBuilder sb = new StringBuilder();
            boolean first = true;
            for (WifiChannel channel : settings.channelSet) {
                if (!first) sb.append(','); else first = false;
                sb.append(channel.freqMHz);
            }
            freqs = sb.toString();
        }

        // call wifi native to start the scan
        if (startScanNative(type, freqs)) {
            // only count battery consumption if scan request is accepted
            noteScanStart(message.arg1, workSource);
            // a full scan covers everything, clearing scan request buffer
            if (freqs == null)
                mBufferedScanMsg.clear();
            messageHandlingStatus = MESSAGE_HANDLING_STATUS_OK;
            if (workSource != null) {
                // External worksource was passed along the scan request,
                // hence always send a broadcast
                mSendScanResultsBroadcast = true;
            }
            return;
        }

        // if reach here, scan request is rejected

        if (!mIsScanOngoing) {
            // if rejection is NOT due to ongoing scan (e.g. bad scan parameters),

            // discard this request and pop up the next one
            if (mBufferedScanMsg.size() > 0) {
                sendMessage(mBufferedScanMsg.remove());
            }
            messageHandlingStatus = MESSAGE_HANDLING_STATUS_DISCARD;
        } else if (!mIsFullScanOngoing) {
            // if rejection is due to an ongoing scan, and the ongoing one is NOT a full scan,
            // buffer the scan request to make sure specified channels will be scanned eventually
            if (freqs == null)
                mBufferedScanMsg.clear();
            if (mBufferedScanMsg.size() < SCAN_REQUEST_BUFFER_MAX_SIZE) {
                Message msg = obtainMessage(CMD_START_SCAN,
                        message.arg1, message.arg2, bundle);
                mBufferedScanMsg.add(msg);
            } else {
                // if too many requests in buffer, combine them into a single full scan
                bundle = new Bundle();
                bundle.putParcelable(CUSTOMIZED_SCAN_SETTING, null);
                bundle.putParcelable(CUSTOMIZED_SCAN_WORKSOURCE, workSource);
                Message msg = obtainMessage(CMD_START_SCAN, message.arg1, message.arg2, bundle);
                mBufferedScanMsg.clear();
                mBufferedScanMsg.add(msg);
            }
            messageHandlingStatus = MESSAGE_HANDLING_STATUS_LOOPED;
        } else {
            // mIsScanOngoing and mIsFullScanOngoing
            messageHandlingStatus = MESSAGE_HANDLING_STATUS_FAIL;
        }
    }


    /** return true iff scan request is accepted */
    private boolean startScanNative(int type, String freqs) {
        if (mWifiNative.scan(type, freqs)) {
            mIsScanOngoing = true;
            mIsFullScanOngoing = (freqs == null);
            lastScanFreqs = freqs;
            return true;
        }
        return false;
    }

    /**
     * TODO: doc
     */
    public void setSupplicantRunning(boolean enable) {
        if (enable) {
            sendMessage(CMD_START_SUPPLICANT);
        } else {
            sendMessage(CMD_STOP_SUPPLICANT);
        }
    }

    /**
     * TODO: doc
     */
    public void setHostApRunning(WifiConfiguration wifiConfig, boolean enable) {
        if (enable) {
            sendMessage(CMD_START_AP, wifiConfig);
        } else {
            sendMessage(CMD_STOP_AP);
        }
    }

    public void setWifiApConfiguration(WifiConfiguration config) {
        mWifiApConfigChannel.sendMessage(CMD_SET_AP_CONFIG, config);
    }

    public WifiConfiguration syncGetWifiApConfiguration() {
        Message resultMsg = mWifiApConfigChannel.sendMessageSynchronously(CMD_REQUEST_AP_CONFIG);
        WifiConfiguration ret = (WifiConfiguration) resultMsg.obj;
        resultMsg.recycle();
        return ret;
    }

    /**
     * TODO: doc
     */
    public int syncGetWifiState() {
        return mWifiState.get();
    }

    /**
     * TODO: doc
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

    /**
     * TODO: doc
     */
    public int syncGetWifiApState() {
        return mWifiApState.get();
    }

    /**
     * TODO: doc
     */
    public String syncGetWifiApStateByName() {
        switch (mWifiApState.get()) {
            case WIFI_AP_STATE_DISABLING:
                return "disabling";
            case WIFI_AP_STATE_DISABLED:
                return "disabled";
            case WIFI_AP_STATE_ENABLING:
                return "enabling";
            case WIFI_AP_STATE_ENABLED:
                return "enabled";
            case WIFI_AP_STATE_FAILED:
                return "failed";
            default:
                return "[invalid state]";
        }
    }

    /**
     * Get status information for the current connection, if any.
     * @return a {@link WifiInfo} object containing information about the current connection
     *
     */
    public WifiInfo syncRequestConnectionInfo() {
        return mWifiInfo;
    }

    public DhcpResults syncGetDhcpResults() {
        synchronized (mDhcpResultsLock) {
            return new DhcpResults(mDhcpResults);
        }
    }

    /**
     * TODO: doc
     */
    public void setDriverStart(boolean enable) {
        if (enable) {
            sendMessage(CMD_START_DRIVER);
        } else {
            sendMessage(CMD_STOP_DRIVER);
        }
    }

    /**
     * TODO: doc
     */
    public void setOperationalMode(int mode) {
        if (DBG) log("setting operational mode to " + String.valueOf(mode));
        sendMessage(CMD_SET_OPERATIONAL_MODE, mode, 0);
    }

    /**
     * TODO: doc
     */
    public List<ScanResult> syncGetScanResultsList() {
        synchronized (mScanResultCache) {
            List<ScanResult> scanList = new ArrayList<ScanResult>();
            for(ScanResult result: mScanResults) {
                scanList.add(new ScanResult(result));
            }
            return scanList;
        }
    }

    public void disableEphemeralNetwork(String SSID) {
        if (SSID != null) {
            sendMessage(CMD_DISABLE_EPHEMERAL_NETWORK, SSID);
        }
    }

    /**
     * Get unsynchronized pointer to scan result list
     * Can be called only from AutoJoinController which runs in the WifiStateMachine context
     */
    public List<ScanResult> getScanResultsListNoCopyUnsync() {
        return mScanResults;
    }

    /**
     * Disconnect from Access Point
     */
    public void disconnectCommand() {
        sendMessage(CMD_DISCONNECT);
    }

    public void disconnectCommand(int uid, int reason) {
        sendMessage(CMD_DISCONNECT, uid, reason);
    }

    /**
     * Initiate a reconnection to AP
     */
    public void reconnectCommand() {
        sendMessage(CMD_RECONNECT);
    }

    /**
     * Initiate a re-association to AP
     */
    public void reassociateCommand() {
        sendMessage(CMD_REASSOCIATE);
    }

    /**
     * Reload networks and then reconnect; helps load correct data for TLS networks
     */

    public void reloadTlsNetworksAndReconnect() {
        sendMessage(CMD_RELOAD_TLS_AND_RECONNECT);
    }

    /**
     * Add a network synchronously
     *
     * @return network id of the new network
     */
    public int syncAddOrUpdateNetwork(AsyncChannel channel, WifiConfiguration config) {
        Message resultMsg = channel.sendMessageSynchronously(CMD_ADD_OR_UPDATE_NETWORK, config);
        int result = resultMsg.arg1;
        resultMsg.recycle();
        return result;
    }

    /**
     * Get configured networks synchronously
     * @param channel
     * @return
     */

    public List<WifiConfiguration> syncGetConfiguredNetworks(int uuid, AsyncChannel channel) {
        Message resultMsg = channel.sendMessageSynchronously(CMD_GET_CONFIGURED_NETWORKS, uuid);
        List<WifiConfiguration> result = (List<WifiConfiguration>) resultMsg.obj;
        resultMsg.recycle();
        return result;
    }

    public List<WifiConfiguration> syncGetPrivilegedConfiguredNetwork(AsyncChannel channel) {
        Message resultMsg = channel.sendMessageSynchronously(
                CMD_GET_PRIVILEGED_CONFIGURED_NETWORKS);
        List<WifiConfiguration> result = (List<WifiConfiguration>) resultMsg.obj;
        resultMsg.recycle();
        return result;
    }


    /**
     * Get connection statistics synchronously
     * @param channel
     * @return
     */

    public WifiConnectionStatistics syncGetConnectionStatistics(AsyncChannel channel) {
        Message resultMsg = channel.sendMessageSynchronously(CMD_GET_CONNECTION_STATISTICS);
        WifiConnectionStatistics result = (WifiConnectionStatistics) resultMsg.obj;
        resultMsg.recycle();
        return result;
    }

    /**
     * Get adaptors synchronously
     */

    public int syncGetSupportedFeatures(AsyncChannel channel) {
        Message resultMsg = channel.sendMessageSynchronously(CMD_GET_SUPPORTED_FEATURES);
        int supportedFeatureSet = resultMsg.arg1;
        resultMsg.recycle();
        return supportedFeatureSet;
    }

    /**
     * Get link layers stats for adapter synchronously
     */
    public WifiLinkLayerStats syncGetLinkLayerStats(AsyncChannel channel) {
        Message resultMsg = channel.sendMessageSynchronously(CMD_GET_LINK_LAYER_STATS);
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
        boolean result = (resultMsg.arg1 != FAILURE);
        resultMsg.recycle();
        return result;
    }

    /**
     * Enable a network
     *
     * @param netId network id of the network
     * @param disableOthers true, if all other networks have to be disabled
     * @return {@code true} if the operation succeeds, {@code false} otherwise
     */
    public boolean syncEnableNetwork(AsyncChannel channel, int netId, boolean disableOthers) {
        Message resultMsg = channel.sendMessageSynchronously(CMD_ENABLE_NETWORK, netId,
                disableOthers ? 1 : 0);
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
        boolean result = (resultMsg.arg1 != WifiManager.DISABLE_NETWORK_FAILED);
        resultMsg.recycle();
        return result;
    }

    /**
     * Retrieves a WPS-NFC configuration token for the specified network
     * @return a hex string representation of the WPS-NFC configuration token
     */
    public String syncGetWpsNfcConfigurationToken(int netId) {
        return mWifiNative.getNfcWpsConfigurationToken(netId);
    }

    void enableBackgroundScan(boolean enable) {
        if (enable) {
            mWifiConfigStore.enableAllNetworks();
        }
        mWifiNative.enableBackgroundScan(enable);
    }

    /**
     * Blacklist a BSSID. This will avoid the AP if there are
     * alternate APs to connect
     *
     * @param bssid BSSID of the network
     */
    public void addToBlacklist(String bssid) {
        sendMessage(CMD_BLACKLIST_NETWORK, bssid);
    }

    /**
     * Clear the blacklist list
     *
     */
    public void clearBlacklist() {
        sendMessage(CMD_CLEAR_BLACKLIST);
    }

    public void enableRssiPolling(boolean enabled) {
       sendMessage(CMD_ENABLE_RSSI_POLL, enabled ? 1 : 0, 0);
    }

    public void enableAllNetworks() {
        sendMessage(CMD_ENABLE_ALL_NETWORKS);
    }

    /**
     * Start filtering Multicast v4 packets
     */
    public void startFilteringMulticastV4Packets() {
        mFilteringMulticastV4Packets.set(true);
        sendMessage(CMD_START_PACKET_FILTERING, MULTICAST_V4, 0);
    }

    /**
     * Stop filtering Multicast v4 packets
     */
    public void stopFilteringMulticastV4Packets() {
        mFilteringMulticastV4Packets.set(false);
        sendMessage(CMD_STOP_PACKET_FILTERING, MULTICAST_V4, 0);
    }

    /**
     * Start filtering Multicast v4 packets
     */
    public void startFilteringMulticastV6Packets() {
        sendMessage(CMD_START_PACKET_FILTERING, MULTICAST_V6, 0);
    }

    /**
     * Stop filtering Multicast v4 packets
     */
    public void stopFilteringMulticastV6Packets() {
        sendMessage(CMD_STOP_PACKET_FILTERING, MULTICAST_V6, 0);
    }

    /**
     * Set high performance mode of operation.
     * Enabling would set active power mode and disable suspend optimizations;
     * disabling would set auto power mode and enable suspend optimizations
     * @param enable true if enable, false otherwise
     */
    public void setHighPerfModeEnabled(boolean enable) {
        sendMessage(CMD_SET_HIGH_PERF_MODE, enable ? 1 : 0, 0);
    }

    /**
     * Set the country code
     * @param countryCode following ISO 3166 format
     * @param persist {@code true} if the setting should be remembered.
     */
    public void setCountryCode(String countryCode, boolean persist) {
        // If it's a good country code, apply after the current
        // wifi connection is terminated; ignore resetting of code
        // for now (it is unclear what the chipset should do when
        // country code is reset)
        int countryCodeSequence = mCountryCodeSequence.incrementAndGet();
        if (TextUtils.isEmpty(countryCode)) {
            log("Ignoring resetting of country code");
        } else {
            sendMessage(CMD_SET_COUNTRY_CODE, countryCodeSequence, persist ? 1 : 0, countryCode);
        }
    }

    /**
     * Set the operational frequency band
     * @param band
     * @param persist {@code true} if the setting should be remembered.
     */
    public void setFrequencyBand(int band, boolean persist) {
        if (persist) {
            Settings.Global.putInt(mContext.getContentResolver(),
                    Settings.Global.WIFI_FREQUENCY_BAND,
                    band);
        }
        sendMessage(CMD_SET_FREQUENCY_BAND, band, 0);
    }

    /**
     * Enable TDLS for a specific MAC address
     */
    public void enableTdls(String remoteMacAddress, boolean enable) {
        int enabler = enable ? 1 : 0;
        sendMessage(CMD_ENABLE_TDLS, enabler, 0, remoteMacAddress);
    }

    /**
     * Returns the operational frequency band
     */
    public int getFrequencyBand() {
        return mFrequencyBand.get();
    }

    /**
     * Returns the wifi configuration file
     */
    public String getConfigFile() {
        return mWifiConfigStore.getConfigFile();
    }

    /**
     * Send a message indicating bluetooth adapter connection state changed
     */
    public void sendBluetoothAdapterStateChange(int state) {
        sendMessage(CMD_BLUETOOTH_ADAPTER_STATE_CHANGE, state, 0);
    }

    /**
     * Save configuration on supplicant
     *
     * @return {@code true} if the operation succeeds, {@code false} otherwise
     *
     * TODO: deprecate this
     */
    public boolean syncSaveConfig(AsyncChannel channel) {
        Message resultMsg = channel.sendMessageSynchronously(CMD_SAVE_CONFIG);
        boolean result = (resultMsg.arg1 != FAILURE);
        resultMsg.recycle();
        return result;
    }

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
                        if (mLastRunningWifiUids.diff(mRunningWifiUids)) {
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
        pw.println("Supplicant status " + mWifiNative.status(true));
        pw.println("mEnableBackgroundScan " + mEnableBackgroundScan);
        pw.println("mLastSetCountryCode " + mLastSetCountryCode);
        pw.println("mPersistedCountryCode " + mPersistedCountryCode);
        mNetworkFactory.dump(fd, pw, args);
        mUntrustedNetworkFactory.dump(fd, pw, args);
        pw.println();
        mWifiConfigStore.dump(fd, pw, args);
    }

    /*********************************************************
     * Internal private functions
     ********************************************************/

    private void logStateAndMessage(Message message, String state) {
        messageHandlingStatus = 0;
        if (mLogMessages) {
            //long now = SystemClock.elapsedRealtimeNanos();
            //String ts = String.format("[%,d us]", now/1000);

            loge( " " + state + " " + getLogRecString(message));
        }
    }

    /**
     * helper, prints the milli time since boot wi and w/o suspended time
     */
    String printTime() {
        StringBuilder sb = new StringBuilder();
        sb.append(" rt=").append(SystemClock.uptimeMillis());
        sb.append("/").append(SystemClock.elapsedRealtime());
        return sb.toString();
    }

    /**
     * Return the additional string to be logged by LogRec, default
     *
     * @param msg that was processed
     * @return information to be logged as a String
     */
    protected String getLogRecString(Message msg) {
        WifiConfiguration config;
        Long now;
        String report;
        String key;
        StringBuilder sb = new StringBuilder();
        if (mScreenOn) {
            sb.append("!");
        }
        if (messageHandlingStatus != MESSAGE_HANDLING_STATUS_UNKNOWN) {
            sb.append("(").append(messageHandlingStatus).append(")");
        }
        sb.append(smToString(msg));
        if (msg.sendingUid > 0 && msg.sendingUid != Process.WIFI_UID) {
            sb.append(" uid=" + msg.sendingUid);
        }
        switch (msg.what) {
            case CMD_START_SCAN:
                now = System.currentTimeMillis();
                sb.append(" ");
                sb.append(Integer.toString(msg.arg1));
                sb.append(" ");
                sb.append(Integer.toString(msg.arg2));
                sb.append(" ic=");
                sb.append(Integer.toString(sScanAlarmIntentCount));
                if (msg.obj != null) {
                    Bundle bundle = (Bundle)msg.obj;
                    Long request = bundle.getLong(SCAN_REQUEST_TIME, 0);
                    if (request != 0) {
                        sb.append(" proc(ms):").append(now - request);
                    }
                }
                if (mIsScanOngoing) sb.append(" onGoing");
                if (mIsFullScanOngoing) sb.append(" full");
                if (lastStartScanTimeStamp != 0) {
                    sb.append(" started:").append(lastStartScanTimeStamp);
                    sb.append(",").append(now - lastStartScanTimeStamp);
                }
                if (lastScanDuration != 0) {
                    sb.append(" dur:").append(lastScanDuration);
                }
                sb.append(" cnt=").append(mDelayedScanCounter);
                sb.append(" rssi=").append(mWifiInfo.getRssi());
                sb.append(" f=").append(mWifiInfo.getFrequency());
                sb.append(" sc=").append(mWifiInfo.score);
                sb.append(" link=").append(mWifiInfo.getLinkSpeed());
                sb.append(String.format(" tx=%.1f,", mWifiInfo.txSuccessRate));
                sb.append(String.format(" %.1f,", mWifiInfo.txRetriesRate));
                sb.append(String.format(" %.1f ", mWifiInfo.txBadRate));
                sb.append(String.format(" rx=%.1f", mWifiInfo.rxSuccessRate));
                if (lastScanFreqs != null) {
                    sb.append(" list=").append(lastScanFreqs);
                } else {
                    sb.append(" fiv=").append(fullBandConnectedTimeIntervalMilli);
                }
                report = reportOnTime();
                if (report != null) {
                    sb.append(" ").append(report);
                }
                break;
            case WifiMonitor.SUPPLICANT_STATE_CHANGE_EVENT:
                sb.append(" ");
                sb.append(Integer.toString(msg.arg1));
                sb.append(" ");
                sb.append(Integer.toString(msg.arg2));
                sb.append(printTime());
                StateChangeResult stateChangeResult = (StateChangeResult) msg.obj;
                if (stateChangeResult != null) {
                    sb.append(stateChangeResult.toString());
                }
                break;
            case WifiManager.SAVE_NETWORK:
            case WifiStateMachine.CMD_AUTO_SAVE_NETWORK:
                sb.append(" ");
                sb.append(Integer.toString(msg.arg1));
                sb.append(" ");
                sb.append(Integer.toString(msg.arg2));
                if (lastSavedConfigurationAttempt != null) {
                    sb.append(" ").append(lastSavedConfigurationAttempt.configKey());
                    sb.append(" nid=").append(lastSavedConfigurationAttempt.networkId);
                    if (lastSavedConfigurationAttempt.hiddenSSID) {
                        sb.append(" hidden");
                    }
                    if (lastSavedConfigurationAttempt.preSharedKey != null
                            && !lastSavedConfigurationAttempt.preSharedKey.equals("*")) {
                        sb.append(" hasPSK");
                    }
                    if (lastSavedConfigurationAttempt.ephemeral) {
                        sb.append(" ephemeral");
                    }
                    if (lastSavedConfigurationAttempt.selfAdded) {
                        sb.append(" selfAdded");
                    }
                    sb.append(" cuid=").append(lastSavedConfigurationAttempt.creatorUid);
                    sb.append(" suid=").append(lastSavedConfigurationAttempt.lastUpdateUid);
                }
                break;
            case WifiManager.FORGET_NETWORK:
                sb.append(" ");
                sb.append(Integer.toString(msg.arg1));
                sb.append(" ");
                sb.append(Integer.toString(msg.arg2));
                if (lastForgetConfigurationAttempt != null) {
                    sb.append(" ").append(lastForgetConfigurationAttempt.configKey());
                    sb.append(" nid=").append(lastForgetConfigurationAttempt.networkId);
                    if (lastForgetConfigurationAttempt.hiddenSSID) {
                        sb.append(" hidden");
                    }
                    if (lastForgetConfigurationAttempt.preSharedKey != null) {
                        sb.append(" hasPSK");
                    }
                    if (lastForgetConfigurationAttempt.ephemeral) {
                        sb.append(" ephemeral");
                    }
                    if (lastForgetConfigurationAttempt.selfAdded) {
                        sb.append(" selfAdded");
                    }
                    sb.append(" cuid=").append(lastForgetConfigurationAttempt.creatorUid);
                    sb.append(" suid=").append(lastForgetConfigurationAttempt.lastUpdateUid);
                    sb.append(" ajst=").append(lastForgetConfigurationAttempt.autoJoinStatus);
                }
                break;
            case WifiMonitor.ASSOCIATION_REJECTION_EVENT:
                sb.append(" ");
                sb.append(Integer.toString(msg.arg1));
                sb.append(" ");
                sb.append(Integer.toString(msg.arg2));
                String bssid = (String)msg.obj;
                if (bssid != null && bssid.length()>0) {
                    sb.append(" ");
                    sb.append(bssid);
                }
                sb.append(" blacklist=" + Boolean.toString(didBlackListBSSID));
                sb.append(printTime());
                break;
            case WifiMonitor.SCAN_RESULTS_EVENT:
                sb.append(" ");
                sb.append(Integer.toString(msg.arg1));
                sb.append(" ");
                sb.append(Integer.toString(msg.arg2));
                if (mScanResults != null) {
                    sb.append(" found=");
                    sb.append(mScanResults.size());
                }
                sb.append(" known=").append(mNumScanResultsKnown);
                sb.append(" got=").append(mNumScanResultsReturned);
                if (lastScanDuration != 0) {
                    sb.append(" dur:").append(lastScanDuration);
                }
                if (mOnTime != 0) {
                    sb.append(" on:").append(mOnTimeThisScan).append(",").append(mOnTimeScan);
                    sb.append(",").append(mOnTime);
                }
                if (mTxTime != 0) {
                    sb.append(" tx:").append(mTxTimeThisScan).append(",").append(mTxTimeScan);
                    sb.append(",").append(mTxTime);
                }
                if (mRxTime != 0) {
                    sb.append(" rx:").append(mRxTimeThisScan).append(",").append(mRxTimeScan);
                    sb.append(",").append(mRxTime);
                }
                sb.append(String.format(" bcn=%d", mRunningBeaconCount));
                sb.append(String.format(" con=%d", mConnectionRequests));
                key = mWifiConfigStore.getLastSelectedConfiguration();
                if (key != null) {
                    sb.append(" last=").append(key);
                }
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
                sb.append(printTime());
                key = mWifiConfigStore.getLastSelectedConfiguration();
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
                    sb.append(" BSSID=").append((String)msg.obj);
                }
                if (mTargetRoamBSSID != null) {
                    sb.append(" Target=").append(mTargetRoamBSSID);
                }
                sb.append(" roam=").append(Integer.toString(mAutoRoaming));
                sb.append(printTime());
                break;
            case WifiMonitor.NETWORK_DISCONNECTION_EVENT:
                if (msg.obj != null) {
                    sb.append(" ").append((String)msg.obj);
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
                if (linkDebouncing) {
                    sb.append(" debounce");
                }
                sb.append(printTime());
                break;
            case WifiMonitor.SSID_TEMP_DISABLED:
            case WifiMonitor.SSID_REENABLED:
                sb.append(" nid=").append(msg.arg1);
                if (msg.obj != null) {
                    sb.append(" ").append((String)msg.obj);
                }
                config = getCurrentWifiConfiguration();
                if (config != null) {
                    sb.append(" cur=").append(config.configKey());
                    sb.append(" ajst=").append(config.autoJoinStatus);
                    if (config.selfAdded) {
                        sb.append(" selfAdded");
                    }
                    if (config.status != 0) {
                        sb.append(" st=").append(config.status);
                        sb.append(" rs=").append(config.disableReason);
                    }
                    if (config.lastConnected != 0) {
                        now = System.currentTimeMillis();
                        sb.append(" lastconn=").append(now - config.lastConnected).append("(ms)");
                    }
                    if (mLastBssid != null) {
                        sb.append(" lastbssid=").append(mLastBssid);
                    }
                    if (mWifiInfo.getFrequency() != -1) {
                        sb.append(" freq=").append(mWifiInfo.getFrequency());
                        sb.append(" rssi=").append(mWifiInfo.getRssi());
                        sb.append(" bssid=").append(mWifiInfo.getBSSID());
                    }
                }
                sb.append(printTime());
                break;
            case CMD_RSSI_POLL:
            case CMD_UNWANTED_NETWORK:
            case WifiManager.RSSI_PKTCNT_FETCH:
                sb.append(" ");
                sb.append(Integer.toString(msg.arg1));
                sb.append(" ");
                sb.append(Integer.toString(msg.arg2));
                if (mWifiInfo.getSSID() != null)
                if (mWifiInfo.getSSID() != null)
                    sb.append(" ").append(mWifiInfo.getSSID());
                if (mWifiInfo.getBSSID() != null)
                    sb.append(" ").append(mWifiInfo.getBSSID());
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
                if (wifiScoringReport != null) {
                    sb.append(wifiScoringReport);
                }
                break;
            case CMD_AUTO_CONNECT:
            case WifiManager.CONNECT_NETWORK:
                sb.append(" ");
                sb.append(Integer.toString(msg.arg1));
                sb.append(" ");
                sb.append(Integer.toString(msg.arg2));
                config = (WifiConfiguration) msg.obj;
                if (config != null) {
                    sb.append(" ").append(config.configKey());
                    if (config.visibility != null) {
                        sb.append(" ").append(config.visibility.toString());
                    }
                }
                if (mTargetRoamBSSID != null) {
                    sb.append(" ").append(mTargetRoamBSSID);
                }
                sb.append(" roam=").append(Integer.toString(mAutoRoaming));
                sb.append(printTime());
                config = getCurrentWifiConfiguration();
                if (config != null) {
                    sb.append(config.configKey());
                    if (config.visibility != null) {
                        sb.append(" ").append(config.visibility.toString());
                    }
                }
                break;
            case CMD_AUTO_ROAM:
                sb.append(" ");
                sb.append(Integer.toString(msg.arg1));
                sb.append(" ");
                sb.append(Integer.toString(msg.arg2));
                ScanResult result = (ScanResult)msg.obj;
                if (result != null) {
                    now = System.currentTimeMillis();
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
                sb.append(" roam=").append(Integer.toString(mAutoRoaming));
                sb.append(" fail count=").append(Integer.toString(mRoamFailCount));
                sb.append(printTime());
                break;
            case CMD_ADD_OR_UPDATE_NETWORK:
                sb.append(" ");
                sb.append(Integer.toString(msg.arg1));
                sb.append(" ");
                sb.append(Integer.toString(msg.arg2));
                if (msg.obj != null) {
                    config = (WifiConfiguration)msg.obj;
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
                key = mWifiConfigStore.getLastSelectedConfiguration();
                if (key != null) {
                    sb.append(" last=").append(key);
                }
                config = mWifiConfigStore.getWifiConfiguration(msg.arg1);
                if (config != null && (key == null || !config.configKey().equals(key))) {
                    sb.append(" target=").append(key);
                }
                break;
            case CMD_GET_CONFIGURED_NETWORKS:
                sb.append(" ");
                sb.append(Integer.toString(msg.arg1));
                sb.append(" ");
                sb.append(Integer.toString(msg.arg2));
                sb.append(" num=").append(mWifiConfigStore.getConfiguredNetworksSize());
                break;
            case DhcpStateMachine.CMD_PRE_DHCP_ACTION:
                sb.append(" ");
                sb.append(Integer.toString(msg.arg1));
                sb.append(" ");
                sb.append(Integer.toString(msg.arg2));
                sb.append(" txpkts=").append(mWifiInfo.txSuccess);
                sb.append(",").append(mWifiInfo.txBad);
                sb.append(",").append(mWifiInfo.txRetries);
                break;
            case DhcpStateMachine.CMD_POST_DHCP_ACTION:
                sb.append(" ");
                sb.append(Integer.toString(msg.arg1));
                sb.append(" ");
                sb.append(Integer.toString(msg.arg2));
                if (msg.arg1 == DhcpStateMachine.DHCP_SUCCESS) {
                    sb.append(" OK ");
                } else if (msg.arg1 == DhcpStateMachine.DHCP_FAILURE) {
                    sb.append(" FAIL ");
                }
                if (mLinkProperties != null) {
                    if (mLinkProperties.hasIPv4Address()) {
                        sb.append(" v4");
                    }
                    if (mLinkProperties.hasGlobalIPv6Address()) {
                        sb.append(" v6");
                    }
                    if (mLinkProperties.hasIPv4DefaultRoute()) {
                        sb.append(" v4r");
                    }
                    if (mLinkProperties.hasIPv6DefaultRoute()) {
                        sb.append(" v6r");
                    }
                    if (mLinkProperties.hasIPv4DnsServer()) {
                        sb.append(" v4dns");
                    }
                    if (mLinkProperties.hasIPv6DnsServer()) {
                        sb.append(" v6dns");
                    }
                }
                break;
            case WifiP2pServiceImpl.P2P_CONNECTION_CHANGED:
                sb.append(" ");
                sb.append(Integer.toString(msg.arg1));
                sb.append(" ");
                sb.append(Integer.toString(msg.arg2));
                if (msg.obj != null) {
                    NetworkInfo info = (NetworkInfo)msg.obj;
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
                if (c != null) count = c.numIpConfigFailures;
                sb.append(" ");
                sb.append(Integer.toString(msg.arg1));
                sb.append(" ");
                sb.append(Integer.toString(msg.arg2));
                sb.append(" failures: ");
                sb.append(Integer.toString(count));
                sb.append("/");
                sb.append(Integer.toString(mWifiConfigStore.getMaxDhcpRetries()));
                if (mWifiInfo.getBSSID() != null) {
                    sb.append(" ").append(mWifiInfo.getBSSID());
                }
                if (c != null) {
                    if (c.scanResultCache != null) {
                        for (ScanResult r : c.scanResultCache.values()) {
                            if (r.BSSID.equals(mWifiInfo.getBSSID())) {
                                sb.append(" ipfail=").append(r.numIpConfigFailures);
                                sb.append(",st=").append(r.autoJoinStatus);
                            }
                        }
                    }
                    sb.append(" -> ajst=").append(c.autoJoinStatus);
                    sb.append(" ").append(c.disableReason);
                    sb.append(" txpkts=").append(mWifiInfo.txSuccess);
                    sb.append(",").append(mWifiInfo.txBad);
                    sb.append(",").append(mWifiInfo.txRetries);
                }
                sb.append(printTime());
                sb.append(String.format(" bcn=%d", mRunningBeaconCount));
                break;
            case CMD_UPDATE_LINKPROPERTIES:
                sb.append(" ");
                sb.append(Integer.toString(msg.arg1));
                sb.append(" ");
                sb.append(Integer.toString(msg.arg2));
                if (mLinkProperties != null) {
                    if (mLinkProperties.hasIPv4Address()) {
                        sb.append(" v4");
                    }
                    if (mLinkProperties.hasGlobalIPv6Address()) {
                        sb.append(" v6");
                    }
                    if (mLinkProperties.hasIPv4DefaultRoute()) {
                        sb.append(" v4r");
                    }
                    if (mLinkProperties.hasIPv6DefaultRoute()) {
                        sb.append(" v6r");
                    }
                    if (mLinkProperties.hasIPv4DnsServer()) {
                        sb.append(" v4dns");
                    }
                    if (mLinkProperties.hasIPv6DnsServer()) {
                        sb.append(" v6dns");
                    }
                }
                break;
            case CMD_SET_COUNTRY_CODE:
                sb.append(" ");
                sb.append(Integer.toString(msg.arg1));
                sb.append(" ");
                sb.append(Integer.toString(msg.arg2));
                if (msg.obj != null) {
                    sb.append(" ").append((String)msg.obj);
                }
                break;
            case CMD_ROAM_WATCHDOG_TIMER:
                sb.append(" ");
                sb.append(Integer.toString(msg.arg1));
                sb.append(" ");
                sb.append(Integer.toString(msg.arg2));
                sb.append(" cur=").append(roamWatchdogCount);
                break;
            case CMD_DISCONNECTING_WATCHDOG_TIMER:
                sb.append(" ");
                sb.append(Integer.toString(msg.arg1));
                sb.append(" ");
                sb.append(Integer.toString(msg.arg2));
                sb.append(" cur=").append(disconnectingWatchdogCount);
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

    private void handleScreenStateChanged(boolean screenOn, boolean startBackgroundScanIfNeeded) {
        mScreenOn = screenOn;
        if (PDBG) {
            loge(" handleScreenStateChanged Enter: screenOn=" + screenOn
                    + " mUserWantsSuspendOpt=" + mUserWantsSuspendOpt
                    + " state " + getCurrentState().getName()
                    + " suppState:" + mSupplicantStateTracker.getSupplicantStateName());
        }
        enableRssiPolling(screenOn);
        if (screenOn) enableAllNetworks();
        if (mUserWantsSuspendOpt.get()) {
            if (screenOn) {
                sendMessage(CMD_SET_SUSPEND_OPT_ENABLED, 0, 0);
            } else {
                // Allow 2s for suspend optimizations to be set
                mSuspendWakeLock.acquire(2000);
                sendMessage(CMD_SET_SUSPEND_OPT_ENABLED, 1, 0);
            }
        }
        mScreenBroadcastReceived.set(true);

        getWifiLinkLayerStats(false);
        mOnTimeScreenStateChange = mOnTime;
        lastScreenStateChangeTimeStamp = lastLinkLayerStatsUpdate;
        mEnableBackgroundScan = false;
        cancelDelayedScan();

        if (screenOn) {
            setScanAlarm(false);
            clearBlacklist();

            fullBandConnectedTimeIntervalMilli = mWifiConfigStore.associatedPartialScanPeriodMilli;
            // In either Disconnectedstate or ConnectedState,
            // start the scan alarm so as to enable autojoin
            if (getCurrentState() == mConnectedState
                    && mWifiConfigStore.enableAutoJoinScanWhenAssociated) {
                // Scan after 500ms
                startDelayedScan(500, null, null);
            } else if (getCurrentState() == mDisconnectedState) {
                // Scan after 200ms
                startDelayedScan(200, null, null);
            }
        } else if (startBackgroundScanIfNeeded) {
            // Screen Off and Disconnected and chipset doesn't support scan offload
            //              => start scan alarm
            // Screen Off and Disconnected and chipset does support scan offload
            //              => will use scan offload (i.e. background scan)
            if (!mBackgroundScanSupported) {
                setScanAlarm(true);
            } else {
                mEnableBackgroundScan = true;
            }
        }
        if (DBG) logd("backgroundScan enabled=" + mEnableBackgroundScan
                + " startBackgroundScanIfNeeded:" + startBackgroundScanIfNeeded);
        if (startBackgroundScanIfNeeded) {
            // to scan for them in background, we need all networks enabled
            enableBackgroundScan(mEnableBackgroundScan);
        }
        if (DBG) log("handleScreenStateChanged Exit: " + screenOn);
    }

    private void checkAndSetConnectivityInstance() {
        if (mCm == null) {
            mCm = (ConnectivityManager) mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        }
    }

    private boolean startTethering(ArrayList<String> available) {

        boolean wifiAvailable = false;

        checkAndSetConnectivityInstance();

        String[] wifiRegexs = mCm.getTetherableWifiRegexs();

        for (String intf : available) {
            for (String regex : wifiRegexs) {
                if (intf.matches(regex)) {

                    InterfaceConfiguration ifcg = null;
                    try {
                        ifcg = mNwService.getInterfaceConfig(intf);
                        if (ifcg != null) {
                            /* IP/netmask: 192.168.43.1/255.255.255.0 */
                            ifcg.setLinkAddress(new LinkAddress(
                                    NetworkUtils.numericToInetAddress("192.168.43.1"), 24));
                            ifcg.setInterfaceUp();

                            mNwService.setInterfaceConfig(intf, ifcg);
                        }
                    } catch (Exception e) {
                        loge("Error configuring interface " + intf + ", :" + e);
                        return false;
                    }

                    if(mCm.tether(intf) != ConnectivityManager.TETHER_ERROR_NO_ERROR) {
                        loge("Error tethering on " + intf);
                        return false;
                    }
                    mTetherInterfaceName = intf;
                    return true;
                }
            }
        }
        // We found no interfaces to tether
        return false;
    }

    private void stopTethering() {

        checkAndSetConnectivityInstance();

        /* Clear the interface config to allow dhcp correctly configure new
           ip settings */
        InterfaceConfiguration ifcg = null;
        try {
            ifcg = mNwService.getInterfaceConfig(mTetherInterfaceName);
            if (ifcg != null) {
                ifcg.setLinkAddress(
                        new LinkAddress(NetworkUtils.numericToInetAddress("0.0.0.0"), 0));
                mNwService.setInterfaceConfig(mTetherInterfaceName, ifcg);
            }
        } catch (Exception e) {
            loge("Error resetting interface " + mTetherInterfaceName + ", :" + e);
        }

        if (mCm.untether(mTetherInterfaceName) != ConnectivityManager.TETHER_ERROR_NO_ERROR) {
            loge("Untether initiate failed!");
        }
    }

    private boolean isWifiTethered(ArrayList<String> active) {

        checkAndSetConnectivityInstance();

        String[] wifiRegexs = mCm.getTetherableWifiRegexs();
        for (String intf : active) {
            for (String regex : wifiRegexs) {
                if (intf.matches(regex)) {
                    return true;
                }
            }
        }
        // We found no interfaces that are tethered
        return false;
    }

    /**
     * Set the country code from the system setting value, if any.
     */
    private void setCountryCode() {
        String countryCode = Settings.Global.getString(mContext.getContentResolver(),
                Settings.Global.WIFI_COUNTRY_CODE);
        if (countryCode != null && !countryCode.isEmpty()) {
            setCountryCode(countryCode, false);
        } else {
            //use driver default
        }
    }

    /**
     * Set the frequency band from the system setting value, if any.
     */
    private void setFrequencyBand() {
        int band = Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.WIFI_FREQUENCY_BAND, WifiManager.WIFI_FREQUENCY_BAND_AUTO);
        setFrequencyBand(band, false);
    }

    private void setSuspendOptimizationsNative(int reason, boolean enabled) {
        if (DBG) {
            log("setSuspendOptimizationsNative: " + reason + " " + enabled
                    + " -want " + mUserWantsSuspendOpt.get()
                    + " stack:" + Thread.currentThread().getStackTrace()[2].getMethodName()
                    +" - "+ Thread.currentThread().getStackTrace()[3].getMethodName()
                    +" - "+ Thread.currentThread().getStackTrace()[4].getMethodName()
                    +" - "+ Thread.currentThread().getStackTrace()[5].getMethodName());
        }
        //mWifiNative.setSuspendOptimizations(enabled);

        if (enabled) {
            mSuspendOptNeedsDisabled &= ~reason;
            /* None of dhcp, screen or highperf need it disabled and user wants it enabled */
            if (mSuspendOptNeedsDisabled == 0 && mUserWantsSuspendOpt.get()) {
                if (DBG) {
                    log("setSuspendOptimizationsNative do it " + reason + " " + enabled
                            + " stack:" + Thread.currentThread().getStackTrace()[2].getMethodName()
                            +" - "+ Thread.currentThread().getStackTrace()[3].getMethodName()
                            +" - "+ Thread.currentThread().getStackTrace()[4].getMethodName()
                            +" - "+ Thread.currentThread().getStackTrace()[5].getMethodName());
                }
                mWifiNative.setSuspendOptimizations(true);
            }
        } else {
            mSuspendOptNeedsDisabled |= reason;
            mWifiNative.setSuspendOptimizations(false);
        }
    }

    private void setSuspendOptimizations(int reason, boolean enabled) {
        if (DBG) log("setSuspendOptimizations: " + reason + " " + enabled);
        if (enabled) {
            mSuspendOptNeedsDisabled &= ~reason;
        } else {
            mSuspendOptNeedsDisabled |= reason;
        }
        if (DBG) log("mSuspendOptNeedsDisabled " + mSuspendOptNeedsDisabled);
    }

    private void setWifiState(int wifiState) {
        final int previousWifiState = mWifiState.get();

        try {
            if (wifiState == WIFI_STATE_ENABLED) {
                mBatteryStats.noteWifiOn();
            } else if (wifiState == WIFI_STATE_DISABLED) {
                mBatteryStats.noteWifiOff();
            }
        } catch (RemoteException e) {
            loge("Failed to note battery stats in wifi");
        }

        mWifiState.set(wifiState);

        if (DBG) log("setWifiState: " + syncGetWifiStateByName());

        final Intent intent = new Intent(WifiManager.WIFI_STATE_CHANGED_ACTION);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        intent.putExtra(WifiManager.EXTRA_WIFI_STATE, wifiState);
        intent.putExtra(WifiManager.EXTRA_PREVIOUS_WIFI_STATE, previousWifiState);
        mContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
    }

    private void setWifiApState(int wifiApState) {
        final int previousWifiApState = mWifiApState.get();

        try {
            if (wifiApState == WIFI_AP_STATE_ENABLED) {
                mBatteryStats.noteWifiOn();
            } else if (wifiApState == WIFI_AP_STATE_DISABLED) {
                mBatteryStats.noteWifiOff();
            }
        } catch (RemoteException e) {
            loge("Failed to note battery stats in wifi");
        }

        // Update state
        mWifiApState.set(wifiApState);

        if (DBG) log("setWifiApState: " + syncGetWifiApStateByName());

        final Intent intent = new Intent(WifiManager.WIFI_AP_STATE_CHANGED_ACTION);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        intent.putExtra(WifiManager.EXTRA_WIFI_AP_STATE, wifiApState);
        intent.putExtra(WifiManager.EXTRA_PREVIOUS_WIFI_AP_STATE, previousWifiApState);
        mContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
    }

    /*
    void ageOutScanResults(int age) {
        synchronized(mScanResultCache) {
            // Trim mScanResults, which prevent WifiStateMachine to return
            // obsolete scan results to queriers
            long now = System.CurrentTimeMillis();
            for (int i = 0; i < mScanResults.size(); i++) {
                ScanResult result = mScanResults.get(i);
                if ((result.seen > now || (now - result.seen) > age)) {
                    mScanResults.remove(i);
                }
            }
        }
    }*/

    private static final String ID_STR = "id=";
    private static final String BSSID_STR = "bssid=";
    private static final String FREQ_STR = "freq=";
    private static final String LEVEL_STR = "level=";
    private static final String TSF_STR = "tsf=";
    private static final String FLAGS_STR = "flags=";
    private static final String SSID_STR = "ssid=";
    private static final String DELIMITER_STR = "====";
    private static final String END_STR = "####";

    int emptyScanResultCount = 0;

    // Used for matching BSSID strings, at least one characteer must be a non-zero number
    private static Pattern mNotZero = Pattern.compile("[1-9a-fA-F]");

    /**
     * Format:
     *
     * id=1
     * bssid=68:7f:76:d7:1a:6e
     * freq=2412
     * level=-44
     * tsf=1344626243700342
     * flags=[WPA2-PSK-CCMP][WPS][ESS]
     * ssid=zfdy
     * ====
     * id=2
     * bssid=68:5f:74:d7:1a:6f
     * freq=5180
     * level=-73
     * tsf=1344626243700373
     * flags=[WPA2-PSK-CCMP][WPS][ESS]
     * ssid=zuby
     * ====
     */
    private void setScanResults() {
        mNumScanResultsKnown = 0;
        mNumScanResultsReturned = 0;
        String bssid = "";
        int level = 0;
        int freq = 0;
        long tsf = 0;
        String flags = "";
        WifiSsid wifiSsid = null;
        String scanResults;
        String tmpResults;
        StringBuffer scanResultsBuf = new StringBuffer();
        int sid = 0;

        while (true) {
            tmpResults = mWifiNative.scanResults(sid);
            if (TextUtils.isEmpty(tmpResults)) break;
            scanResultsBuf.append(tmpResults);
            scanResultsBuf.append("\n");
            String[] lines = tmpResults.split("\n");
            sid = -1;
            for (int i=lines.length - 1; i >= 0; i--) {
                if (lines[i].startsWith(END_STR)) {
                    break;
                } else if (lines[i].startsWith(ID_STR)) {
                    try {
                        sid = Integer.parseInt(lines[i].substring(ID_STR.length())) + 1;
                    } catch (NumberFormatException e) {
                        // Nothing to do
                    }
                    break;
                }
            }
            if (sid == -1) break;
        }

        // Age out scan results, we return all scan results found in the last 12 seconds,
        // and NOT all scan results since last scan.
        // ageOutScanResults(12000);

        scanResults = scanResultsBuf.toString();
        if (TextUtils.isEmpty(scanResults)) {
            emptyScanResultCount++;
            if (emptyScanResultCount > 10) {
                // If we got too many empty scan results, the current scan cache is stale,
                // hence clear it.
                mScanResults = new ArrayList<ScanResult>();
            }
           return;
        }

        emptyScanResultCount = 0;

        // note that all these splits and substrings keep references to the original
        // huge string buffer while the amount we really want is generally pretty small
        // so make copies instead (one example b/11087956 wasted 400k of heap here).
        synchronized(mScanResultCache) {
            mScanResults = new ArrayList<ScanResult>();
            String[] lines = scanResults.split("\n");
            final int bssidStrLen = BSSID_STR.length();
            final int flagLen = FLAGS_STR.length();

            for (String line : lines) {
                if (line.startsWith(BSSID_STR)) {
                    bssid = new String(line.getBytes(), bssidStrLen, line.length() - bssidStrLen);
                } else if (line.startsWith(FREQ_STR)) {
                    try {
                        freq = Integer.parseInt(line.substring(FREQ_STR.length()));
                    } catch (NumberFormatException e) {
                        freq = 0;
                    }
                } else if (line.startsWith(LEVEL_STR)) {
                    try {
                        level = Integer.parseInt(line.substring(LEVEL_STR.length()));
                        /* some implementations avoid negative values by adding 256
                         * so we need to adjust for that here.
                         */
                        if (level > 0) level -= 256;
                    } catch(NumberFormatException e) {
                        level = 0;
                    }
                } else if (line.startsWith(TSF_STR)) {
                    try {
                        tsf = Long.parseLong(line.substring(TSF_STR.length()));
                    } catch (NumberFormatException e) {
                        tsf = 0;
                    }
                } else if (line.startsWith(FLAGS_STR)) {
                    flags = new String(line.getBytes(), flagLen, line.length() - flagLen);
                } else if (line.startsWith(SSID_STR)) {
                    wifiSsid = WifiSsid.createFromAsciiEncoded(
                            line.substring(SSID_STR.length()));
                } else if (line.startsWith(DELIMITER_STR) || line.startsWith(END_STR)) {
                    Matcher match = null;
                    if (bssid!= null) {
                        match = mNotZero.matcher(bssid);
                    }
                    if (match != null && !bssid.isEmpty() && match.find()) {
                        String ssid = (wifiSsid != null) ? wifiSsid.toString() : WifiSsid.NONE;
                        String key = bssid + ssid;
                        ScanResult scanResult = mScanResultCache.get(key);
                        if (scanResult != null) {
                            // TODO: average the RSSI, instead of overwriting it
                            scanResult.level = level;
                            scanResult.wifiSsid = wifiSsid;
                            // Keep existing API
                            scanResult.SSID = (wifiSsid != null) ? wifiSsid.toString() :
                                    WifiSsid.NONE;
                            scanResult.capabilities = flags;
                            scanResult.frequency = freq;
                            scanResult.timestamp = tsf;
                            scanResult.seen = System.currentTimeMillis();
                        } else {
                            scanResult =
                                new ScanResult(
                                        wifiSsid, bssid, flags, level, freq, tsf);
                            scanResult.seen = System.currentTimeMillis();
                            mScanResultCache.put(key, scanResult);
                        }
                        mNumScanResultsReturned ++; // Keep track of how many scan results we got
                                                    // as part of this scan's processing
                        mScanResults.add(scanResult);
                    } else {
                        if (bssid != null)  {
                            loge("setScanResults obtaining null BSSID results <"
                                + bssid + ">, discard it");
                        }
                    }
                    bssid = null;
                    level = 0;
                    freq = 0;
                    tsf = 0;
                    flags = "";
                    wifiSsid = null;
                }
            }
        }
        boolean attemptAutoJoin = true;
        SupplicantState state = mWifiInfo.getSupplicantState();
        String selection = mWifiConfigStore.getLastSelectedConfiguration();
        if (getCurrentState() == mRoamingState
                || getCurrentState() == mObtainingIpState
                || getCurrentState() == mScanModeState
                || getCurrentState() == mDisconnectingState
                || (getCurrentState() == mConnectedState
                && !mWifiConfigStore.enableAutoJoinWhenAssociated)
                || linkDebouncing
                || state == SupplicantState.ASSOCIATING
                || state == SupplicantState.AUTHENTICATING
                || state == SupplicantState.FOUR_WAY_HANDSHAKE
                || state == SupplicantState.GROUP_HANDSHAKE
                || (/* keep autojoin enabled if user has manually selected a wifi network,
                        so as to make sure we reliably remain connected to this network */
                        mConnectionRequests == 0 && selection == null)) {
            // Dont attempt auto-joining again while we are already attempting to join
            // and/or obtaining Ip address
            attemptAutoJoin = false;
        }
        if (DBG) {
            if (selection == null) {
                selection = "<none>";
            }
            loge("wifi setScanResults state" + getCurrentState()
                    + " sup_state=" + state
                    + " debouncing=" + linkDebouncing
                    + " mConnectionRequests=" + mConnectionRequests
                    + " selection=" + selection);
        }
        if (attemptAutoJoin) {
            messageHandlingStatus = MESSAGE_HANDLING_STATUS_PROCESSED;
        }
        // Loose last selected configuration if we have been disconnected for 5 minutes
        if (getDisconnectedTimeMilli() > mWifiConfigStore.wifiConfigLastSelectionHysteresis) {
            mWifiConfigStore.setLastSelectedConfiguration(WifiConfiguration.INVALID_NETWORK_ID);
        }

        if (mWifiConfigStore.enableAutoJoinWhenAssociated) {
            synchronized(mScanResultCache) {
                // AutoJoincontroller will directly acces the scan result list and update it with
                // ScanResult status
                mNumScanResultsKnown = mWifiAutoJoinController.newSupplicantResults(attemptAutoJoin);
            }
        }
        if (linkDebouncing) {
            // If debouncing, we dont re-select a SSID or BSSID hence
            // there is no need to call the network selection code
            // in WifiAutoJoinController, instead,
            // just try to reconnect to the same SSID by triggering a roam
            sendMessage(CMD_AUTO_ROAM, mLastNetworkId, 1, null);
        }
    }

    /*
     * Fetch RSSI, linkspeed, and frequency on current connection
     */
    private void fetchRssiLinkSpeedAndFrequencyNative() {
        int newRssi = -1;
        int newLinkSpeed = -1;
        int newFrequency = -1;

        String signalPoll = mWifiNative.signalPoll();

        if (signalPoll != null) {
            String[] lines = signalPoll.split("\n");
            for (String line : lines) {
                String[] prop = line.split("=");
                if (prop.length < 2) continue;
                try {
                    if (prop[0].equals("RSSI")) {
                        newRssi = Integer.parseInt(prop[1]);
                    } else if (prop[0].equals("LINKSPEED")) {
                        newLinkSpeed = Integer.parseInt(prop[1]);
                    } else if (prop[0].equals("FREQUENCY")) {
                        newFrequency = Integer.parseInt(prop[1]);
                    }
                } catch (NumberFormatException e) {
                    //Ignore, defaults on rssi and linkspeed are assigned
                }
            }
        }

        if (PDBG) {
            loge("fetchRssiLinkSpeedAndFrequencyNative rssi="
                    + Integer.toString(newRssi) + " linkspeed="
                    + Integer.toString(newLinkSpeed));
        }

        if (newRssi > WifiInfo.INVALID_RSSI && newRssi < WifiInfo.MAX_RSSI) {
        // screen out invalid values
            /* some implementations avoid negative values by adding 256
             * so we need to adjust for that here.
             */
            if (newRssi > 0) newRssi -= 256;
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
                sendRssiChangeBroadcast(newRssi);
            }
            mLastSignalLevel = newSignalLevel;
        } else {
            mWifiInfo.setRssi(WifiInfo.INVALID_RSSI);
        }

        if (newLinkSpeed != -1) {
            mWifiInfo.setLinkSpeed(newLinkSpeed);
        }
        if (newFrequency > 0) {
            if (ScanResult.is5GHz(newFrequency)) {
                mWifiConnectionStatistics.num5GhzConnected++;
            }
            if (ScanResult.is24GHz(newFrequency)) {
                mWifiConnectionStatistics.num24GhzConnected++;
            }
            mWifiInfo.setFrequency(newFrequency);
        }
        mWifiConfigStore.updateConfiguration(mWifiInfo);
    }

    /**
     *  Determine if we need to switch network:
     * - the delta determine the urgency to switch and/or or the expected evilness of the disruption
     * - match the uregncy of the switch versus the packet usage at the interface
     */
    boolean shouldSwitchNetwork(int networkDelta) {
        int delta;
        if (networkDelta <= 0) {
            return false;
        }
        delta = networkDelta;
        if (mWifiInfo != null) {
            if (!mWifiConfigStore.enableAutoJoinWhenAssociated
                    && mWifiInfo.getNetworkId() != WifiConfiguration.INVALID_NETWORK_ID) {
                // If AutoJoin while associated is not enabled,
                // we should never switch network when already associated
                delta = -1000;
            } else {
                // TODO: Look at per AC packet count, do not switch if VO/VI traffic is present
                // TODO: at the interface. We should also discriminate between ucast and mcast,
                // TODO: since the rxSuccessRate include all the bonjour and Ipv6
                // TODO: broadcasts
                if ((mWifiInfo.txSuccessRate > 20) || (mWifiInfo.rxSuccessRate > 80)) {
                    delta -= 999;
                } else if ((mWifiInfo.txSuccessRate > 5) || (mWifiInfo.rxSuccessRate > 30)) {
                    delta -= 6;
                }
                loge("WifiStateMachine shouldSwitchNetwork "
                        + " txSuccessRate=" + String.format("%.2f", mWifiInfo.txSuccessRate)
                        + " rxSuccessRate=" + String.format("%.2f", mWifiInfo.rxSuccessRate)
                        + " delta " + networkDelta + " -> " + delta);
            }
        } else {
            loge("WifiStateMachine shouldSwitchNetwork "
                    + " delta " + networkDelta + " -> " + delta);
        }
        if (delta > 0) {
            return true;
        }
        return false;
    }

    // Polling has completed, hence we wont have a score anymore
    private void cleanWifiScore() {
        mWifiInfo.txBadRate = 0;
        mWifiInfo.txSuccessRate = 0;
        mWifiInfo.txRetriesRate = 0;
        mWifiInfo.rxSuccessRate = 0;
    }

    int mBadLinkspeedcount = 0;

    // For debug, provide information about the last scoring operation
    String wifiScoringReport = null;
    private void calculateWifiScore(WifiLinkLayerStats stats) {
        StringBuilder sb = new StringBuilder();

        int score = 56; // Starting score, temporarily hardcoded in between 50 and 60
        boolean isBadLinkspeed = (mWifiInfo.is24GHz()
                && mWifiInfo.getLinkSpeed() < mWifiConfigStore.badLinkSpeed24)
                || (mWifiInfo.is5GHz() && mWifiInfo.getLinkSpeed()
                < mWifiConfigStore.badLinkSpeed5);
        boolean isGoodLinkspeed = (mWifiInfo.is24GHz()
                && mWifiInfo.getLinkSpeed() >= mWifiConfigStore.goodLinkSpeed24)
                || (mWifiInfo.is5GHz() && mWifiInfo.getLinkSpeed()
                >= mWifiConfigStore.goodLinkSpeed5);

        if (isBadLinkspeed) {
            if (mBadLinkspeedcount < 6)
                mBadLinkspeedcount++;
        } else {
            if (mBadLinkspeedcount > 0)
                mBadLinkspeedcount--;
        }

        if (isBadLinkspeed) sb.append(" bl(").append(mBadLinkspeedcount).append(")");
        if (isGoodLinkspeed) sb.append(" gl");

        /**
         * We want to make sure that we use the 24GHz RSSI thresholds if
         * there are 2.4GHz scan results
         * otherwise we end up lowering the score based on 5GHz values
         * which may cause a switch to LTE before roaming has a chance to try 2.4GHz
         * We also might unblacklist the configuation based on 2.4GHz
         * thresholds but joining 5GHz anyhow, and failing over to 2.4GHz because 5GHz is not good
         */
        boolean use24Thresholds = false;
        boolean homeNetworkBoost = false;
        WifiConfiguration currentConfiguration = getCurrentWifiConfiguration();
        if (currentConfiguration != null
                && currentConfiguration.scanResultCache != null) {
            currentConfiguration.setVisibility(12000);
            if (currentConfiguration.visibility != null) {
                if (currentConfiguration.visibility.rssi24 != WifiConfiguration.INVALID_RSSI
                        && currentConfiguration.visibility.rssi24
                        >= (currentConfiguration.visibility.rssi5-2)) {
                    use24Thresholds = true;
                }
            }
            if (currentConfiguration.scanResultCache.size() <= 6
                && currentConfiguration.allowedKeyManagement.cardinality() == 1
                && currentConfiguration.allowedKeyManagement.
                    get(WifiConfiguration.KeyMgmt.WPA_PSK) == true) {
                // A PSK network with less than 6 known BSSIDs
                // This is most likely a home network and thus we want to stick to wifi more
                homeNetworkBoost = true;
            }
        }
        if (homeNetworkBoost) sb.append(" hn");
        if (use24Thresholds) sb.append(" u24");

        int rssi = mWifiInfo.getRssi() - 6 * mAggressiveHandover
                + (homeNetworkBoost ? WifiConfiguration.HOME_NETWORK_RSSI_BOOST : 0);
        sb.append(String.format(" rssi=%d ag=%d", rssi, mAggressiveHandover));

        boolean is24GHz = use24Thresholds || mWifiInfo.is24GHz();

        boolean isBadRSSI = (is24GHz && rssi < mWifiConfigStore.thresholdBadRssi24)
                || (!is24GHz && rssi < mWifiConfigStore.thresholdBadRssi5);
        boolean isLowRSSI = (is24GHz && rssi < mWifiConfigStore.thresholdLowRssi24)
                || (!is24GHz && mWifiInfo.getRssi() < mWifiConfigStore.thresholdLowRssi5);
        boolean isHighRSSI = (is24GHz && rssi >= mWifiConfigStore.thresholdGoodRssi24)
                || (!is24GHz && mWifiInfo.getRssi() >= mWifiConfigStore.thresholdGoodRssi5);

        if (isBadRSSI) sb.append(" br");
        if (isLowRSSI) sb.append(" lr");
        if (isHighRSSI) sb.append(" hr");

        int penalizedDueToUserTriggeredDisconnect = 0;        // For debug information
        if (currentConfiguration!= null &&
                (mWifiInfo.txSuccessRate > 5 || mWifiInfo.rxSuccessRate > 5)) {
            if (isBadRSSI) {
                currentConfiguration.numTicksAtBadRSSI++;
                if (currentConfiguration.numTicksAtBadRSSI > 1000) {
                    // We remained associated for a compound amount of time while passing
                    // traffic, hence loose the corresponding user triggered disabled stats
                    if (currentConfiguration.numUserTriggeredWifiDisableBadRSSI > 0) {
                        currentConfiguration.numUserTriggeredWifiDisableBadRSSI--;
                    }
                    if (currentConfiguration.numUserTriggeredWifiDisableLowRSSI > 0) {
                        currentConfiguration.numUserTriggeredWifiDisableLowRSSI--;
                    }
                    if (currentConfiguration.numUserTriggeredWifiDisableNotHighRSSI > 0) {
                        currentConfiguration.numUserTriggeredWifiDisableNotHighRSSI--;
                    }
                    currentConfiguration.numTicksAtBadRSSI = 0;
                }
                if (mWifiConfigStore.enableWifiCellularHandoverUserTriggeredAdjustment &&
                        (currentConfiguration.numUserTriggeredWifiDisableBadRSSI > 0
                        || currentConfiguration.numUserTriggeredWifiDisableLowRSSI > 0
                        || currentConfiguration.numUserTriggeredWifiDisableNotHighRSSI > 0)) {
                    score = score -5;
                    penalizedDueToUserTriggeredDisconnect = 1;
                    sb.append(" p1");
                }
            } else if (isLowRSSI) {
                currentConfiguration.numTicksAtLowRSSI++;
                if (currentConfiguration.numTicksAtLowRSSI > 1000) {
                    // We remained associated for a compound amount of time while passing
                    // traffic, hence loose the corresponding user triggered disabled stats
                    if (currentConfiguration.numUserTriggeredWifiDisableLowRSSI > 0) {
                        currentConfiguration.numUserTriggeredWifiDisableLowRSSI--;
                    }
                    if (currentConfiguration.numUserTriggeredWifiDisableNotHighRSSI > 0) {
                        currentConfiguration.numUserTriggeredWifiDisableNotHighRSSI--;
                    }
                    currentConfiguration.numTicksAtLowRSSI = 0;
                }
                if (mWifiConfigStore.enableWifiCellularHandoverUserTriggeredAdjustment &&
                        (currentConfiguration.numUserTriggeredWifiDisableLowRSSI > 0
                        || currentConfiguration.numUserTriggeredWifiDisableNotHighRSSI > 0)) {
                    score = score -5;
                    penalizedDueToUserTriggeredDisconnect = 2;
                    sb.append(" p2");
                }
            } else if (!isHighRSSI) {
                currentConfiguration.numTicksAtNotHighRSSI++;
                if (currentConfiguration.numTicksAtNotHighRSSI > 1000) {
                    // We remained associated for a compound amount of time while passing
                    // traffic, hence loose the corresponding user triggered disabled stats
                    if (currentConfiguration.numUserTriggeredWifiDisableNotHighRSSI > 0) {
                        currentConfiguration.numUserTriggeredWifiDisableNotHighRSSI--;
                    }
                    currentConfiguration.numTicksAtNotHighRSSI = 0;
                }
                if (mWifiConfigStore.enableWifiCellularHandoverUserTriggeredAdjustment &&
                        currentConfiguration.numUserTriggeredWifiDisableNotHighRSSI > 0) {
                    score = score -5;
                    penalizedDueToUserTriggeredDisconnect = 3;
                    sb.append(" p3");
                }
            }
            sb.append(String.format(" ticks %d,%d,%d", currentConfiguration.numTicksAtBadRSSI,
                    currentConfiguration.numTicksAtLowRSSI,
                    currentConfiguration.numTicksAtNotHighRSSI));
        }

        if (PDBG) {
            String rssiStatus = "";
            if (isBadRSSI) rssiStatus += " badRSSI ";
            else if (isHighRSSI) rssiStatus += " highRSSI ";
            else if (isLowRSSI) rssiStatus += " lowRSSI ";
            if (isBadLinkspeed) rssiStatus += " lowSpeed ";
            loge("calculateWifiScore freq=" + Integer.toString(mWifiInfo.getFrequency())
                            + " speed=" + Integer.toString(mWifiInfo.getLinkSpeed())
                            + " score=" + Integer.toString(mWifiInfo.score)
                            + rssiStatus
                            + " -> txbadrate=" + String.format( "%.2f", mWifiInfo.txBadRate )
                            + " txgoodrate=" + String.format("%.2f", mWifiInfo.txSuccessRate)
                            + " txretriesrate=" + String.format("%.2f", mWifiInfo.txRetriesRate)
                            + " rxrate=" + String.format("%.2f", mWifiInfo.rxSuccessRate)
                            + " userTriggerdPenalty" + penalizedDueToUserTriggeredDisconnect);
        }

        if ((mWifiInfo.txBadRate >= 1) && (mWifiInfo.txSuccessRate < 3)
                && (isBadRSSI || isLowRSSI)) {
            // Link is stuck
            if (mWifiInfo.linkStuckCount < 5)
                mWifiInfo.linkStuckCount += 1;
            sb.append(String.format(" ls+=%d", mWifiInfo.linkStuckCount));
            if (PDBG) loge(" bad link -> stuck count ="
                    + Integer.toString(mWifiInfo.linkStuckCount));
        } else if (mWifiInfo.txSuccessRate > 2 || mWifiInfo.txBadRate < 0.1) {
            if (mWifiInfo.linkStuckCount > 0)
                mWifiInfo.linkStuckCount -= 1;
            sb.append(String.format(" ls-=%d", mWifiInfo.linkStuckCount));
            if (PDBG) loge(" good link -> stuck count ="
                    + Integer.toString(mWifiInfo.linkStuckCount));
        }

        sb.append(String.format(" [%d", score));

        if (mWifiInfo.linkStuckCount > 1) {
            // Once link gets stuck for more than 3 seconds, start reducing the score
            score = score - 2 * (mWifiInfo.linkStuckCount - 1);
        }
        sb.append(String.format(",%d", score));

        if (isBadLinkspeed) {
            score -= 4 ;
            if (PDBG) {
                loge(" isBadLinkspeed   ---> count=" + mBadLinkspeedcount
                        + " score=" + Integer.toString(score));
            }
        } else if ((isGoodLinkspeed) && (mWifiInfo.txSuccessRate > 5)) {
            score += 4; // So as bad rssi alone dont kill us
        }
        sb.append(String.format(",%d", score));

        if (isBadRSSI) {
            if (mWifiInfo.badRssiCount < 7)
                mWifiInfo.badRssiCount += 1;
        } else if (isLowRSSI) {
            mWifiInfo.lowRssiCount = 1; // Dont increment the lowRssi count above 1
            if (mWifiInfo.badRssiCount > 0) {
                // Decrement bad Rssi count
                mWifiInfo.badRssiCount -= 1;
            }
        } else {
            mWifiInfo.badRssiCount = 0;
            mWifiInfo.lowRssiCount = 0;
        }

        score -= mWifiInfo.badRssiCount * 2 +  mWifiInfo.lowRssiCount ;
        sb.append(String.format(",%d", score));

        if (PDBG) loge(" badRSSI count" + Integer.toString(mWifiInfo.badRssiCount)
                     + " lowRSSI count" + Integer.toString(mWifiInfo.lowRssiCount)
                        + " --> score " + Integer.toString(score));


        if (isHighRSSI) {
            score += 5;
            if (PDBG) loge(" isHighRSSI       ---> score=" + Integer.toString(score));
        }
        sb.append(String.format(",%d]", score));

        sb.append(String.format(" brc=%d lrc=%d", mWifiInfo.badRssiCount, mWifiInfo.lowRssiCount));

        //sanitize boundaries
        if (score > NetworkAgent.WIFI_BASE_SCORE)
            score = NetworkAgent.WIFI_BASE_SCORE;
        if (score < 0)
            score = 0;

        //report score
        if (score != mWifiInfo.score) {
            if (DBG) {
                loge("calculateWifiScore() report new score " + Integer.toString(score));
            }
            mWifiInfo.score = score;
            if (mNetworkAgent != null) {
                mNetworkAgent.sendNetworkScore(score);
            }
        }
        wifiScoringReport = sb.toString();
    }

    public double getTxPacketRate() {
        if (mWifiInfo != null) {
            return mWifiInfo.txSuccessRate;
        }
        return -1;
    }

    public double getRxPacketRate() {
        if (mWifiInfo != null) {
            return mWifiInfo.rxSuccessRate;
        }
        return -1;
    }

    /**
     * Fetch TX packet counters on current connection
     */
    private void fetchPktcntNative(RssiPacketCountInfo info) {
        String pktcntPoll = mWifiNative.pktcntPoll();

        if (pktcntPoll != null) {
            String[] lines = pktcntPoll.split("\n");
            for (String line : lines) {
                String[] prop = line.split("=");
                if (prop.length < 2) continue;
                try {
                    if (prop[0].equals("TXGOOD")) {
                        info.txgood = Integer.parseInt(prop[1]);
                    } else if (prop[0].equals("TXBAD")) {
                        info.txbad = Integer.parseInt(prop[1]);
                    }
                } catch (NumberFormatException e) {
                    // Ignore
                }
            }
        }
    }

    private boolean clearIPv4Address(String iface) {
        try {
            InterfaceConfiguration ifcg = new InterfaceConfiguration();
            ifcg.setLinkAddress(new LinkAddress("0.0.0.0/0"));
            mNwService.setInterfaceConfig(iface, ifcg);
            return true;
        } catch (RemoteException e) {
            return false;
        }
    }

    private boolean isProvisioned(LinkProperties lp) {
        return lp.isProvisioned() ||
               (mWifiConfigStore.isUsingStaticIp(mLastNetworkId) && lp.hasIPv4Address());
    }

    /**
     * Updates mLinkProperties by merging information from various sources.
     *
     * This is needed because the information in mLinkProperties comes from multiple sources (DHCP,
     * netlink, static configuration, ...). When one of these sources of information has updated
     * link properties, we can't just assign them to mLinkProperties or we'd lose track of the
     * information that came from other sources. Instead, when one of those sources has new
     * information, we update the object that tracks the information from that source and then
     * call this method to apply the change to mLinkProperties.
     *
     * The information in mLinkProperties is currently obtained as follows:
     * - Interface name: set in the constructor.
     * - IPv4 and IPv6 addresses: netlink, passed in by mNetlinkTracker.
     * - IPv4 routes, DNS servers, and domains: DHCP.
     * - IPv6 routes and DNS servers: netlink, passed in by mNetlinkTracker.
     * - HTTP proxy: the wifi config store.
     */
    private void updateLinkProperties(int reason) {
        LinkProperties newLp = new LinkProperties();

        // Interface name and proxy are locally configured.
        newLp.setInterfaceName(mInterfaceName);
        newLp.setHttpProxy(mWifiConfigStore.getProxyProperties(mLastNetworkId));

        // IPv4/v6 addresses, IPv6 routes and IPv6 DNS servers come from netlink.
        LinkProperties netlinkLinkProperties = mNetlinkTracker.getLinkProperties();
        newLp.setLinkAddresses(netlinkLinkProperties.getLinkAddresses());
        for (RouteInfo route : netlinkLinkProperties.getRoutes()) {
            newLp.addRoute(route);
        }
        for (InetAddress dns : netlinkLinkProperties.getDnsServers()) {
            newLp.addDnsServer(dns);
        }

        // IPv4 routes, DNS servers and domains come from mDhcpResults.
        synchronized (mDhcpResultsLock) {
            // Even when we're using static configuration, we don't need to look at the config
            // store, because static IP configuration also populates mDhcpResults.
            if ((mDhcpResults != null)) {
                for (RouteInfo route : mDhcpResults.getRoutes(mInterfaceName)) {
                    newLp.addRoute(route);
                }
                for (InetAddress dns : mDhcpResults.dnsServers) {
                    newLp.addDnsServer(dns);
                }
                newLp.setDomains(mDhcpResults.domains);
            }
        }

        final boolean linkChanged = !newLp.equals(mLinkProperties);
        final boolean wasProvisioned = isProvisioned(mLinkProperties);
        final boolean isProvisioned = isProvisioned(newLp);
        final boolean lostIPv4Provisioning =
            mLinkProperties.hasIPv4Address() && !newLp.hasIPv4Address();
        final DetailedState detailedState = getNetworkDetailedState();

        if (linkChanged) {
            if (DBG) {
                log("Link configuration changed for netId: " + mLastNetworkId
                        + " old: " + mLinkProperties + " new: " + newLp);
            }
            mLinkProperties = newLp;
            if (TextUtils.isEmpty(mTcpBufferSizes) == false) {
                mLinkProperties.setTcpBufferSizes(mTcpBufferSizes);
            }
            if (mNetworkAgent != null) mNetworkAgent.sendLinkProperties(mLinkProperties);
        }

        if (DBG) {
            StringBuilder sb = new StringBuilder();
            sb.append("updateLinkProperties nid: " + mLastNetworkId);
            sb.append(" state: " + detailedState);
            sb.append(" reason: " + smToString(reason));

            if (mLinkProperties != null) {
                if (mLinkProperties.hasIPv4Address()) {
                    sb.append(" v4");
                }
                if (mLinkProperties.hasGlobalIPv6Address()) {
                    sb.append(" v6");
                }
                if (mLinkProperties.hasIPv4DefaultRoute()) {
                    sb.append(" v4r");
                }
                if (mLinkProperties.hasIPv6DefaultRoute()) {
                    sb.append(" v6r");
                }
                if (mLinkProperties.hasIPv4DnsServer()) {
                    sb.append(" v4dns");
                }
                if (mLinkProperties.hasIPv6DnsServer()) {
                    sb.append(" v6dns");
                }
                if (isProvisioned) {
                    sb.append(" isprov");
                }
            }
            loge(sb.toString());
        }

        // If we just configured or lost IP configuration, do the needful.
        // We don't just call handleSuccessfulIpConfiguration() or handleIpConfigurationLost()
        // here because those should only be called if we're attempting to connect or already
        // connected, whereas updateLinkProperties can be called at any time.
        switch (reason) {
            case DhcpStateMachine.DHCP_SUCCESS:
            case CMD_STATIC_IP_SUCCESS:
                // IPv4 provisioning succeded. Advance to connected state.
                sendMessage(CMD_IP_CONFIGURATION_SUCCESSFUL);
                if (!isProvisioned) {
                    // Can never happen unless DHCP reports success but isProvisioned thinks the
                    // resulting configuration is invalid (e.g., no IPv4 address, or the state in
                    // mLinkProperties is out of sync with reality, or there's a bug in this code).
                    // TODO: disconnect here instead. If our configuration is not usable, there's no
                    // point in staying connected, and if mLinkProperties is out of sync with
                    // reality, that will cause problems in the future.
                    loge("IPv4 config succeeded, but not provisioned");
                }
                break;

            case DhcpStateMachine.DHCP_FAILURE:
                // DHCP failed. If we're not already provisioned, or we had IPv4 and now lost it,
                // give up and disconnect.
                // If we're already provisioned (e.g., IPv6-only network), stay connected.
                if (!isProvisioned || lostIPv4Provisioning) {
                    sendMessage(CMD_IP_CONFIGURATION_LOST);
                } else {
                    // DHCP failed, but we're provisioned (e.g., if we're on an IPv6-only network).
                    sendMessage(CMD_IP_CONFIGURATION_SUCCESSFUL);

                    // To be sure we don't get stuck with a non-working network if all we had is
                    // IPv4, remove the IPv4 address from the interface (since we're using DHCP,
                    // and DHCP failed). If we had an IPv4 address before, the deletion of the
                    // address  will cause a CMD_UPDATE_LINKPROPERTIES. If the IPv4 address was
                    // necessary for provisioning, its deletion will cause us to disconnect.
                    //
                    // This shouldn't be needed, because on an IPv4-only network a DHCP failure will
                    // have empty DhcpResults and thus empty LinkProperties, and isProvisioned will
                    // not return true if we're using DHCP and don't have an IPv4 default route. So
                    // for now it's only here for extra redundancy. However, it will increase
                    // robustness if we move to getting IPv4 routes from netlink as well.
                    loge("DHCP failure: provisioned, clearing IPv4 address.");
                    if (!clearIPv4Address(mInterfaceName)) {
                        sendMessage(CMD_IP_CONFIGURATION_LOST);
                    }
                }
                break;

            case CMD_STATIC_IP_FAILURE:
                // Static configuration was invalid, or an error occurred in applying it. Give up.
                sendMessage(CMD_IP_CONFIGURATION_LOST);
                break;

            case CMD_UPDATE_LINKPROPERTIES:
                // IP addresses, DNS servers, etc. changed. Act accordingly.
                if (wasProvisioned && !isProvisioned) {
                    // We no longer have a usable network configuration. Disconnect.
                    sendMessage(CMD_IP_CONFIGURATION_LOST);
                } else if (!wasProvisioned && isProvisioned) {
                    // We have a usable IPv6-only config. Advance to connected state.
                    sendMessage(CMD_IP_CONFIGURATION_SUCCESSFUL);
                }
                if (linkChanged && getNetworkDetailedState() == DetailedState.CONNECTED) {
                    // If anything has changed and we're already connected, send out a notification.
                    sendLinkConfigurationChangedBroadcast();
                }
                break;
        }
    }

    /**
     * Clears all our link properties.
     */
     private void clearLinkProperties() {
         // Clear the link properties obtained from DHCP and netlink.
         synchronized (mDhcpResultsLock) {
             if (mDhcpResults != null) {
                 mDhcpResults.clear();
             }
         }
         mNetlinkTracker.clearLinkProperties();

         // Now clear the merged link properties.
         mLinkProperties.clear();
         if (mNetworkAgent != null) mNetworkAgent.sendLinkProperties(mLinkProperties);
     }

     /**
      * try to update default route MAC address.
      */
      private String updateDefaultRouteMacAddress(int timeout) {
          String address = null;
          for (RouteInfo route : mLinkProperties.getRoutes()) {
              if (route.isDefaultRoute() && route.hasGateway()) {
                  InetAddress gateway = route.getGateway();
                  if (gateway instanceof Inet4Address) {
                      if (PDBG) {
                          loge("updateDefaultRouteMacAddress found Ipv4 default :"
                                  + gateway.getHostAddress());
                      }
                      address = macAddressFromRoute(gateway.getHostAddress());
                     /* The gateway's MAC address is known */
                      if ((address == null) && (timeout > 0)) {
                          boolean reachable = false;
                          try {
                              reachable = gateway.isReachable(timeout);
                          } catch (Exception e) {
                              loge("updateDefaultRouteMacAddress exception reaching :"
                                      + gateway.getHostAddress());

                          } finally {
                              if (reachable == true) {

                                  address = macAddressFromRoute(gateway.getHostAddress());
                                  if (PDBG) {
                                      loge("updateDefaultRouteMacAddress reachable (tried again) :"
                                              + gateway.getHostAddress() + " found " + address);
                                  }
                              }
                          }
                      }
                      if (address != null) {
                          mWifiConfigStore.setDefaultGwMacAddress(mLastNetworkId, address);
                      }
                  }
              }
          }
          return address;
      }

    private void sendScanResultsAvailableBroadcast() {
        Intent intent = new Intent(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
    }

    private void sendRssiChangeBroadcast(final int newRssi) {
        try {
            mBatteryStats.noteWifiRssiChanged(newRssi);
        } catch (RemoteException e) {
            // Won't happen.
        }
        Intent intent = new Intent(WifiManager.RSSI_CHANGED_ACTION);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        intent.putExtra(WifiManager.EXTRA_NEW_RSSI, newRssi);
        mContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
    }

    private void sendNetworkStateChangeBroadcast(String bssid) {
        Intent intent = new Intent(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        intent.putExtra(WifiManager.EXTRA_NETWORK_INFO, new NetworkInfo(mNetworkInfo));
        intent.putExtra(WifiManager.EXTRA_LINK_PROPERTIES, new LinkProperties (mLinkProperties));
        if (bssid != null)
            intent.putExtra(WifiManager.EXTRA_BSSID, bssid);
        if (mNetworkInfo.getDetailedState() == DetailedState.VERIFYING_POOR_LINK ||
                mNetworkInfo.getDetailedState() == DetailedState.CONNECTED) {
            intent.putExtra(WifiManager.EXTRA_WIFI_INFO, new WifiInfo(mWifiInfo));
        }
        mContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
    }

    private void sendLinkConfigurationChangedBroadcast() {
        Intent intent = new Intent(WifiManager.LINK_CONFIGURATION_CHANGED_ACTION);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        intent.putExtra(WifiManager.EXTRA_LINK_PROPERTIES, new LinkProperties(mLinkProperties));
        mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
    }

    private void sendSupplicantConnectionChangedBroadcast(boolean connected) {
        Intent intent = new Intent(WifiManager.SUPPLICANT_CONNECTION_CHANGE_ACTION);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        intent.putExtra(WifiManager.EXTRA_SUPPLICANT_CONNECTED, connected);
        mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
    }

    /**
     * Record the detailed state of a network.
     * @param state the new {@code DetailedState}
     */
    private boolean setNetworkDetailedState(NetworkInfo.DetailedState state) {
        boolean hidden = false;

        if (linkDebouncing || isRoaming()) {
            // There is generally a confusion in the system about colluding
            // WiFi Layer 2 state (as reported by supplicant) and the Network state
            // which leads to multiple confusion.
            //
            // If link is de-bouncing or roaming, we already have an IP address
            // as well we were connected and are doing L2 cycles of
            // reconnecting or renewing IP address to check that we still have it
            // This L2 link flapping should ne be reflected into the Network state
            // which is the state of the WiFi Network visible to Layer 3 and applications
            // Note that once debouncing and roaming are completed, we will
            // set the Network state to where it should be, or leave it as unchanged
            //
            hidden = true;
        }
        if (DBG) {
            log("setDetailed state, old ="
                    + mNetworkInfo.getDetailedState() + " and new state=" + state
                    + " hidden=" + hidden);
        }
        if (mNetworkInfo.getExtraInfo() != null && mWifiInfo.getSSID() != null) {
            // Always indicate that SSID has changed
            if (!mNetworkInfo.getExtraInfo().equals(mWifiInfo.getSSID())) {
                if (DBG) {
                    log("setDetailed state send new extra info"  + mWifiInfo.getSSID());
                }
                mNetworkInfo.setExtraInfo(mWifiInfo.getSSID());
                sendNetworkStateChangeBroadcast(null);
            }
        }
        if (hidden == true) {
            return false;
        }

        if (state != mNetworkInfo.getDetailedState()) {
            mNetworkInfo.setDetailedState(state, null, mWifiInfo.getSSID());
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
        // Supplicant state change
        // [31-13] Reserved for future use
        // [8 - 0] Supplicant state (as defined in SupplicantState.java)
        // 50023 supplicant_state_changed (custom|1|5)
        mWifiInfo.setSupplicantState(state);
        // Network id is only valid when we start connecting
        if (SupplicantState.isConnecting(state)) {
            mWifiInfo.setNetworkId(stateChangeResult.networkId);
        } else {
            mWifiInfo.setNetworkId(WifiConfiguration.INVALID_NETWORK_ID);
        }

        mWifiInfo.setBSSID(stateChangeResult.BSSID);
        mWifiInfo.setSSID(stateChangeResult.wifiSsid);

        mSupplicantStateTracker.sendMessage(Message.obtain(message));

        return state;
    }

    /**
     * Resets the Wi-Fi Connections by clearing any state, resetting any sockets
     * using the interface, stopping DHCP & disabling interface
     */
    private void handleNetworkDisconnect() {
        if (DBG) log("handleNetworkDisconnect: Stopping DHCP and clearing IP"
                + " stack:" + Thread.currentThread().getStackTrace()[2].getMethodName()
                +" - "+ Thread.currentThread().getStackTrace()[3].getMethodName()
                +" - "+ Thread.currentThread().getStackTrace()[4].getMethodName()
                +" - "+ Thread.currentThread().getStackTrace()[5].getMethodName());


        clearCurrentConfigBSSID("handleNetworkDisconnect");

        stopDhcp();

        try {
            mNwService.clearInterfaceAddresses(mInterfaceName);
            mNwService.disableIpv6(mInterfaceName);
        } catch (Exception e) {
            loge("Failed to clear addresses or disable ipv6" + e);
        }

        /* Reset data structures */
        mBadLinkspeedcount = 0;
        mWifiInfo.reset();
        linkDebouncing = false;
        /* Reset roaming parameters */
        mAutoRoaming = WifiAutoJoinController.AUTO_JOIN_IDLE;

        /**
         *  fullBandConnectedTimeIntervalMilli:
         *  - start scans at mWifiConfigStore.associatedPartialScanPeriodMilli seconds interval
         *  - exponentially increase to mWifiConfigStore.associatedFullScanMaxIntervalMilli
         *  Initialize to sane value = 20 seconds
         */
        fullBandConnectedTimeIntervalMilli = 20 * 1000;

        setNetworkDetailedState(DetailedState.DISCONNECTED);
        if (mNetworkAgent != null) {
            mNetworkAgent.sendNetworkInfo(mNetworkInfo);
            mNetworkAgent = null;
        }
        mWifiConfigStore.updateStatus(mLastNetworkId, DetailedState.DISCONNECTED);

        /* Clear network properties */
        clearLinkProperties();

        /* Cend event to CM & network change broadcast */
        sendNetworkStateChangeBroadcast(mLastBssid);

        /* Cancel auto roam requests */
        autoRoamSetBSSID(mLastNetworkId, "any");

        mLastBssid= null;
        registerDisconnected();
        mLastNetworkId = WifiConfiguration.INVALID_NETWORK_ID;
    }

    private void handleSupplicantConnectionLoss() {
        /* Socket connection can be lost when we do a graceful shutdown
        * or when the driver is hung. Ensure supplicant is stopped here.
        */
        mWifiMonitor.killSupplicant(mP2pSupported);
        mWifiNative.closeSupplicantConnection();
        sendSupplicantConnectionChangedBroadcast(false);
        setWifiState(WIFI_STATE_DISABLED);
    }

    void handlePreDhcpSetup() {
        mDhcpActive = true;
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
                    mWifiNative.BLUETOOTH_COEXISTENCE_MODE_DISABLED);
        }

        // Disable power save and suspend optimizations during DHCP
        // Note: The order here is important for now. Brcm driver changes
        // power settings when we control suspend mode optimizations.
        // TODO: Remove this comment when the driver is fixed.
        setSuspendOptimizationsNative(SUSPEND_DUE_TO_DHCP, false);
        mWifiNative.setPowerSave(false);

        stopBatchedScan();
        WifiNative.pauseScan();

        // Update link layer stats
        getWifiLinkLayerStats(false);

        /* P2p discovery breaks dhcp, shut it down in order to get through this */
        Message msg = new Message();
        msg.what = WifiP2pServiceImpl.BLOCK_DISCOVERY;
        msg.arg1 = WifiP2pServiceImpl.ENABLED;
        msg.arg2 = DhcpStateMachine.CMD_PRE_DHCP_ACTION_COMPLETE;
        msg.obj = mDhcpStateMachine;
        mWifiP2pChannel.sendMessage(msg);
    }


    void startDhcp() {
        if (mDhcpStateMachine == null) {
            mDhcpStateMachine = DhcpStateMachine.makeDhcpStateMachine(
                    mContext, WifiStateMachine.this, mInterfaceName);

        }
        mDhcpStateMachine.registerForPreDhcpNotification();
        mDhcpStateMachine.sendMessage(DhcpStateMachine.CMD_START_DHCP);
    }

    void renewDhcp() {
        if (mDhcpStateMachine == null) {
            mDhcpStateMachine = DhcpStateMachine.makeDhcpStateMachine(
                    mContext, WifiStateMachine.this, mInterfaceName);

        }
        mDhcpStateMachine.registerForPreDhcpNotification();
        mDhcpStateMachine.sendMessage(DhcpStateMachine.CMD_RENEW_DHCP);
    }

    void stopDhcp() {
        if (mDhcpStateMachine != null) {
            /* In case we were in middle of DHCP operation restore back powermode */
            handlePostDhcpSetup();
            mDhcpStateMachine.sendMessage(DhcpStateMachine.CMD_STOP_DHCP);
        }
    }

    void handlePostDhcpSetup() {
        /* Restore power save and suspend optimizations */
        setSuspendOptimizationsNative(SUSPEND_DUE_TO_DHCP, true);
        mWifiNative.setPowerSave(true);

        mWifiP2pChannel.sendMessage(WifiP2pServiceImpl.BLOCK_DISCOVERY, WifiP2pServiceImpl.DISABLED);

        // Set the coexistence mode back to its default value
        mWifiNative.setBluetoothCoexistenceMode(
                mWifiNative.BLUETOOTH_COEXISTENCE_MODE_SENSE);

        mDhcpActive = false;

        startBatchedScan();
        WifiNative.restartScan();
    }

    private void handleIPv4Success(DhcpResults dhcpResults, int reason) {

        if (PDBG) {
            loge("wifistatemachine handleIPv4Success <" + dhcpResults.toString() + ">");
            loge("link address " + dhcpResults.ipAddress);
        }

        synchronized (mDhcpResultsLock) {
            mDhcpResults = dhcpResults;
        }

        Inet4Address addr = (Inet4Address) dhcpResults.ipAddress.getAddress();
        if (isRoaming()) {
            if (addr instanceof Inet4Address) {
                int previousAddress = mWifiInfo.getIpAddress();
                int newAddress = NetworkUtils.inetAddressToInt(addr);
                if (previousAddress != newAddress) {
                    loge("handleIPv4Success, roaming and address changed" +
                            mWifiInfo + " got: " + addr);
                } else {

                }
            } else {
                loge("handleIPv4Success, roaming and didnt get an IPv4 address" +
                        addr.toString());
            }
        }
        mWifiInfo.setInetAddress(addr);
        mWifiInfo.setMeteredHint(dhcpResults.hasMeteredHint());
        updateLinkProperties(reason);
    }

    private void handleSuccessfulIpConfiguration() {
        mLastSignalLevel = -1; // Force update of signal strength
        WifiConfiguration c = getCurrentWifiConfiguration();
        if (c != null) {
            // Reset IP failure tracking
            c.numConnectionFailures = 0;

            // Tell the framework whether the newly connected network is trusted or untrusted.
            updateCapabilities(c);
        }
        if (c != null) {
            ScanResult result = getCurrentScanResult();
            if (result == null) {
                loge("WifiStateMachine: handleSuccessfulIpConfiguration and no scan results" +
                        c.configKey());
            } else {
                // Clear the per BSSID failure count
                result.numIpConfigFailures = 0;
                // Clear the WHOLE BSSID blacklist, which means supplicant is free to retry
                // any BSSID, even though it may already have a non zero ip failure count,
                // this will typically happen if the user walks away and come back to his arrea
                // TODO: implement blacklisting based on a timer, i.e. keep BSSID blacklisted
                // in supplicant for a couple of hours or a day
                mWifiNative.clearBlacklist();
            }
        }
    }

    private void handleIPv4Failure(int reason) {
        synchronized(mDhcpResultsLock) {
             if (mDhcpResults != null) {
                 mDhcpResults.clear();
             }
        }
        if (PDBG) {
            loge("wifistatemachine handleIPv4Failure");
        }
        updateLinkProperties(reason);
    }

    private void handleIpConfigurationLost() {
        mWifiInfo.setInetAddress(null);
        mWifiInfo.setMeteredHint(false);

        mWifiConfigStore.handleSSIDStateChange(mLastNetworkId, false,
                "DHCP FAILURE", mWifiInfo.getBSSID());

        /* DHCP times out after about 30 seconds, we do a
         * disconnect thru supplicant, we will let autojoin retry connecting to the network
         */
        mWifiNative.disconnect();
    }

    /* Current design is to not set the config on a running hostapd but instead
     * stop and start tethering when user changes config on a running access point
     *
     * TODO: Add control channel setup through hostapd that allows changing config
     * on a running daemon
     */
    private void startSoftApWithConfig(final WifiConfiguration config) {
        // Start hostapd on a separate thread
        new Thread(new Runnable() {
            public void run() {
                try {
                    mNwService.startAccessPoint(config, mInterfaceName);
                } catch (Exception e) {
                    loge("Exception in softap start " + e);
                    try {
                        mNwService.stopAccessPoint(mInterfaceName);
                        mNwService.startAccessPoint(config, mInterfaceName);
                    } catch (Exception e1) {
                        loge("Exception in softap re-start " + e1);
                        sendMessage(CMD_START_AP_FAILURE);
                        return;
                    }
                }
                if (DBG) log("Soft AP start successful");
                sendMessage(CMD_START_AP_SUCCESS);
            }
        }).start();
    }

    /*
     * Read a MAC address in /proc/arp/table, used by WifistateMachine
     * so as to record MAC address of default gateway.
     **/
    private String macAddressFromRoute(String ipAddress) {
        String macAddress = null;
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader("/proc/net/arp"));

            // Skip over the line bearing colum titles
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
                loge("Did not find remoteAddress {" + ipAddress + "} in " +
                        "/proc/net/arp");
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

    private class WifiNetworkFactory extends NetworkFactory {
        public WifiNetworkFactory(Looper l, Context c, String TAG, NetworkCapabilities f) {
            super(l, c, TAG, f);
        }

        @Override
        protected void needNetworkFor(NetworkRequest networkRequest, int score) {
            ++mConnectionRequests;
        }

        @Override
        protected void releaseNetworkFor(NetworkRequest networkRequest) {
            --mConnectionRequests;
        }

        public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            pw.println("mConnectionRequests " + mConnectionRequests);
        }

    }

    private class UntrustedWifiNetworkFactory extends NetworkFactory {
        private int mUntrustedReqCount;

        public UntrustedWifiNetworkFactory(Looper l, Context c, String tag, NetworkCapabilities f) {
            super(l, c, tag, f);
        }

        @Override
        protected void needNetworkFor(NetworkRequest networkRequest, int score) {
            if (!networkRequest.networkCapabilities.hasCapability(
                    NetworkCapabilities.NET_CAPABILITY_TRUSTED)) {
                if (++mUntrustedReqCount == 1) {
                    mWifiAutoJoinController.setAllowUntrustedConnections(true);
                }
            }
        }

        @Override
        protected void releaseNetworkFor(NetworkRequest networkRequest) {
            if (!networkRequest.networkCapabilities.hasCapability(
                    NetworkCapabilities.NET_CAPABILITY_TRUSTED)) {
                if (--mUntrustedReqCount == 0) {
                    mWifiAutoJoinController.setAllowUntrustedConnections(false);
                }
            }
        }

        public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            pw.println("mUntrustedReqCount " + mUntrustedReqCount);
        }
    }

    void maybeRegisterNetworkFactory() {
        if (mNetworkFactory == null) {
            checkAndSetConnectivityInstance();
            if (mCm != null) {
                mNetworkFactory = new WifiNetworkFactory(getHandler().getLooper(), mContext,
                        NETWORKTYPE, mNetworkCapabilitiesFilter);
                mNetworkFactory.setScoreFilter(60);
                mNetworkFactory.register();

                // We can't filter untrusted network in the capabilities filter because a trusted
                // network would still satisfy a request that accepts untrusted ones.
                mUntrustedNetworkFactory = new UntrustedWifiNetworkFactory(getHandler().getLooper(),
                        mContext, NETWORKTYPE_UNTRUSTED, mNetworkCapabilitiesFilter);
                mUntrustedNetworkFactory.setScoreFilter(Integer.MAX_VALUE);
                mUntrustedNetworkFactory.register();
            }
        }
    }

    /********************************************************
     * HSM states
     *******************************************************/

    class DefaultState extends State {
        @Override
        public boolean processMessage(Message message) {
            logStateAndMessage(message, getClass().getSimpleName());

            switch (message.what) {
                case AsyncChannel.CMD_CHANNEL_HALF_CONNECTED: {
                    AsyncChannel ac = (AsyncChannel) message.obj;
                    if (ac == mWifiP2pChannel) {
                        if (message.arg1 == AsyncChannel.STATUS_SUCCESSFUL) {
                            mWifiP2pChannel.sendMessage(AsyncChannel.CMD_CHANNEL_FULL_CONNECTION);
                        } else {
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
                        //TODO: Re-establish connection to state machine after a delay
                        // mWifiP2pChannel.connect(mContext, getHandler(),
                        // mWifiP2pManager.getMessenger());
                    }
                    break;
                }
                case CMD_BLUETOOTH_ADAPTER_STATE_CHANGE:
                    mBluetoothConnectionActive = (message.arg1 !=
                            BluetoothAdapter.STATE_DISCONNECTED);
                    break;
                    /* Synchronous call returns */
                case CMD_PING_SUPPLICANT:
                case CMD_ENABLE_NETWORK:
                case CMD_ADD_OR_UPDATE_NETWORK:
                case CMD_REMOVE_NETWORK:
                case CMD_SAVE_CONFIG:
                    replyToMessage(message, message.what, FAILURE);
                    break;
                case CMD_GET_CAPABILITY_FREQ:
                    replyToMessage(message, message.what, null);
                    break;
                case CMD_GET_CONFIGURED_NETWORKS:
                    replyToMessage(message, message.what, (List<WifiConfiguration>) null);
                    break;
                case CMD_GET_PRIVILEGED_CONFIGURED_NETWORKS:
                    replyToMessage(message, message.what, (List<WifiConfiguration>) null);
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
                case CMD_BOOT_COMPLETED:
                    String countryCode = mPersistedCountryCode;
                    if (TextUtils.isEmpty(countryCode) == false) {
                        Settings.Global.putString(mContext.getContentResolver(),
                                Settings.Global.WIFI_COUNTRY_CODE,
                                countryCode);
                        // It may be that the state transition that should send this info
                        // to the driver happened between mPersistedCountryCode getting set
                        // and now, so simply persisting it here would mean we have sent
                        // nothing to the driver.  Send the cmd so it might be set now.
                        int sequenceNum = mCountryCodeSequence.incrementAndGet();
                        sendMessageAtFrontOfQueue(CMD_SET_COUNTRY_CODE,
                                sequenceNum, 0, countryCode);
                    }
                    maybeRegisterNetworkFactory();
                    break;
                case CMD_SET_BATCHED_SCAN:
                    recordBatchedScanSettings(message.arg1, message.arg2, (Bundle)message.obj);
                    break;
                case CMD_POLL_BATCHED_SCAN:
                    handleBatchedScanPollRequest();
                    break;
                case CMD_START_NEXT_BATCHED_SCAN:
                    startNextBatchedScan();
                    break;
                case CMD_SCREEN_STATE_CHANGED:
                    handleScreenStateChanged(message.arg1 != 0,
                            /* startBackgroundScanIfNeeded = */ false);
                    break;
                    /* Discard */
                case CMD_START_SCAN:
                    messageHandlingStatus = MESSAGE_HANDLING_STATUS_DISCARD;
                    break;
                case CMD_START_SUPPLICANT:
                case CMD_STOP_SUPPLICANT:
                case CMD_STOP_SUPPLICANT_FAILED:
                case CMD_START_DRIVER:
                case CMD_STOP_DRIVER:
                case CMD_DELAYED_STOP_DRIVER:
                case CMD_DRIVER_START_TIMED_OUT:
                case CMD_START_AP:
                case CMD_START_AP_SUCCESS:
                case CMD_START_AP_FAILURE:
                case CMD_STOP_AP:
                case CMD_TETHER_STATE_CHANGE:
                case CMD_TETHER_NOTIFICATION_TIMED_OUT:
                case CMD_DISCONNECT:
                case CMD_RECONNECT:
                case CMD_REASSOCIATE:
                case CMD_RELOAD_TLS_AND_RECONNECT:
                case WifiMonitor.SUP_CONNECTION_EVENT:
                case WifiMonitor.SUP_DISCONNECTION_EVENT:
                case WifiMonitor.NETWORK_CONNECTION_EVENT:
                case WifiMonitor.NETWORK_DISCONNECTION_EVENT:
                case WifiMonitor.SCAN_RESULTS_EVENT:
                case WifiMonitor.SUPPLICANT_STATE_CHANGE_EVENT:
                case WifiMonitor.AUTHENTICATION_FAILURE_EVENT:
                case WifiMonitor.ASSOCIATION_REJECTION_EVENT:
                case WifiMonitor.WPS_OVERLAP_EVENT:
                case CMD_BLACKLIST_NETWORK:
                case CMD_CLEAR_BLACKLIST:
                case CMD_SET_OPERATIONAL_MODE:
                case CMD_SET_COUNTRY_CODE:
                case CMD_SET_FREQUENCY_BAND:
                case CMD_RSSI_POLL:
                case CMD_ENABLE_ALL_NETWORKS:
                case DhcpStateMachine.CMD_PRE_DHCP_ACTION:
                case DhcpStateMachine.CMD_POST_DHCP_ACTION:
                /* Handled by WifiApConfigStore */
                case CMD_SET_AP_CONFIG:
                case CMD_SET_AP_CONFIG_COMPLETED:
                case CMD_REQUEST_AP_CONFIG:
                case CMD_RESPONSE_AP_CONFIG:
                case WifiWatchdogStateMachine.POOR_LINK_DETECTED:
                case WifiWatchdogStateMachine.GOOD_LINK_DETECTED:
                case CMD_NO_NETWORKS_PERIODIC_SCAN:
                case CMD_DISABLE_P2P_RSP:
                case WifiMonitor.SUP_REQUEST_IDENTITY:
                case CMD_TEST_NETWORK_DISCONNECT:
                case CMD_OBTAINING_IP_ADDRESS_WATCHDOG_TIMER:
                case WifiMonitor.SUP_REQUEST_SIM_AUTH:
                case CMD_TARGET_BSSID:
                case CMD_AUTO_CONNECT:
                case CMD_AUTO_ROAM:
                case CMD_AUTO_SAVE_NETWORK:
                case CMD_ASSOCIATED_BSSID:
                case CMD_UNWANTED_NETWORK:
                case CMD_DISCONNECTING_WATCHDOG_TIMER:
                case CMD_ROAM_WATCHDOG_TIMER:
                case CMD_DISABLE_EPHEMERAL_NETWORK:
                    messageHandlingStatus = MESSAGE_HANDLING_STATUS_DISCARD;
                    break;
                case DhcpStateMachine.CMD_ON_QUIT:
                    mDhcpStateMachine = null;
                    break;
                case CMD_SET_SUSPEND_OPT_ENABLED:
                    if (message.arg1 == 1) {
                        mSuspendWakeLock.release();
                        setSuspendOptimizations(SUSPEND_DUE_TO_SCREEN, true);
                    } else {
                        setSuspendOptimizations(SUSPEND_DUE_TO_SCREEN, false);
                    }
                    break;
                case WifiMonitor.DRIVER_HUNG_EVENT:
                    setSupplicantRunning(false);
                    setSupplicantRunning(true);
                    break;
                case WifiManager.CONNECT_NETWORK:
                    replyToMessage(message, WifiManager.CONNECT_NETWORK_FAILED,
                            WifiManager.BUSY);
                    break;
                case WifiManager.FORGET_NETWORK:
                    replyToMessage(message, WifiManager.FORGET_NETWORK_FAILED,
                            WifiManager.BUSY);
                    break;
                case WifiManager.SAVE_NETWORK:
                    messageHandlingStatus = MESSAGE_HANDLING_STATUS_FAIL;
                    replyToMessage(message, WifiManager.SAVE_NETWORK_FAILED,
                            WifiManager.BUSY);
                    break;
                case WifiManager.START_WPS:
                    replyToMessage(message, WifiManager.WPS_FAILED,
                            WifiManager.BUSY);
                    break;
                case WifiManager.CANCEL_WPS:
                    replyToMessage(message, WifiManager.CANCEL_WPS_FAILED,
                            WifiManager.BUSY);
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
                    if (WifiNative.startHal()) {
                        int featureSet = WifiNative.getSupportedFeatureSet();
                        replyToMessage(message, message.what, featureSet);
                    } else {
                        replyToMessage(message, message.what, 0);
                    }
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
                    updateLinkProperties(CMD_UPDATE_LINKPROPERTIES);
                    break;
                case CMD_IP_CONFIGURATION_SUCCESSFUL:
                case CMD_IP_CONFIGURATION_LOST:
                    messageHandlingStatus = MESSAGE_HANDLING_STATUS_DISCARD;
                    break;
                case CMD_GET_CONNECTION_STATISTICS:
                    replyToMessage(message, message.what, mWifiConnectionStatistics);
                    break;
                default:
                    loge("Error! unhandled message" + message);
                    break;
            }
            return HANDLED;
        }
    }

    class InitialState extends State {
        @Override
        public void enter() {
            mWifiNative.unloadDriver();

            if (mWifiP2pChannel == null) {
                mWifiP2pChannel = new AsyncChannel();
                mWifiP2pChannel.connect(mContext, getHandler(),
                    mWifiP2pServiceImpl.getP2pStateMachineMessenger());
            }

            if (mWifiApConfigChannel == null) {
                mWifiApConfigChannel = new AsyncChannel();
                WifiApConfigStore wifiApConfigStore = WifiApConfigStore.makeWifiApConfigStore(
                        mContext, getHandler());
                wifiApConfigStore.loadApConfiguration();
                mWifiApConfigChannel.connectSync(mContext, getHandler(),
                        wifiApConfigStore.getMessenger());
            }
        }
        @Override
        public boolean processMessage(Message message) {
            logStateAndMessage(message, getClass().getSimpleName());
            switch (message.what) {
                case CMD_START_SUPPLICANT:
                    if (mWifiNative.loadDriver()) {
                        try {
                            mNwService.wifiFirmwareReload(mInterfaceName, "STA");
                        } catch (Exception e) {
                            loge("Failed to reload STA firmware " + e);
                            // Continue
                        }

                        try {
                            // A runtime crash can leave the interface up and
                            // IP addresses configured, and this affects
                            // connectivity when supplicant starts up.
                            // Ensure interface is down and we have no IP
                            // addresses before a supplicant start.
                            mNwService.setInterfaceDown(mInterfaceName);
                            mNwService.clearInterfaceAddresses(mInterfaceName);

                            // Set privacy extensions
                            mNwService.setInterfaceIpv6PrivacyExtensions(mInterfaceName, true);

                           // IPv6 is enabled only as long as access point is connected since:
                           // - IPv6 addresses and routes stick around after disconnection
                           // - kernel is unaware when connected and fails to start IPv6 negotiation
                           // - kernel can start autoconfiguration when 802.1x is not complete
                            mNwService.disableIpv6(mInterfaceName);
                        } catch (RemoteException re) {
                            loge("Unable to change interface settings: " + re);
                        } catch (IllegalStateException ie) {
                            loge("Unable to change interface settings: " + ie);
                        }

                       /* Stop a running supplicant after a runtime restart
                        * Avoids issues with drivers that do not handle interface down
                        * on a running supplicant properly.
                        */
                        mWifiMonitor.killSupplicant(mP2pSupported);
                        if(mWifiNative.startSupplicant(mP2pSupported)) {
                            setWifiState(WIFI_STATE_ENABLING);
                            if (DBG) log("Supplicant start successful");
                            mWifiMonitor.startMonitoring();
                            transitionTo(mSupplicantStartingState);
                        } else {
                            loge("Failed to start supplicant!");
                        }
                    } else {
                        loge("Failed to load driver");
                    }
                    break;
                case CMD_START_AP:
                    if (mWifiNative.loadDriver()) {
                        setWifiApState(WIFI_AP_STATE_ENABLING);
                        transitionTo(mSoftApStartingState);
                    } else {
                        loge("Failed to load driver for softap");
                    }
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }
    }

    class SupplicantStartingState extends State {
        private void initializeWpsDetails() {
            String detail;
            detail = SystemProperties.get("ro.product.name", "");
            if (!mWifiNative.setDeviceName(detail)) {
                loge("Failed to set device name " +  detail);
            }
            detail = SystemProperties.get("ro.product.manufacturer", "");
            if (!mWifiNative.setManufacturer(detail)) {
                loge("Failed to set manufacturer " + detail);
            }
            detail = SystemProperties.get("ro.product.model", "");
            if (!mWifiNative.setModelName(detail)) {
                loge("Failed to set model name " + detail);
            }
            detail = SystemProperties.get("ro.product.model", "");
            if (!mWifiNative.setModelNumber(detail)) {
                loge("Failed to set model number " + detail);
            }
            detail = SystemProperties.get("ro.serialno", "");
            if (!mWifiNative.setSerialNumber(detail)) {
                loge("Failed to set serial number " + detail);
            }
            if (!mWifiNative.setConfigMethods("physical_display virtual_push_button")) {
                loge("Failed to set WPS config methods");
            }
            if (!mWifiNative.setDeviceType(mPrimaryDeviceType)) {
                loge("Failed to set primary device type " + mPrimaryDeviceType);
            }
        }

        @Override
        public boolean processMessage(Message message) {
            logStateAndMessage(message, getClass().getSimpleName());

            switch(message.what) {
                case WifiMonitor.SUP_CONNECTION_EVENT:
                    if (DBG) log("Supplicant connection established");
                    setWifiState(WIFI_STATE_ENABLED);
                    mSupplicantRestartCount = 0;
                    /* Reset the supplicant state to indicate the supplicant
                     * state is not known at this time */
                    mSupplicantStateTracker.sendMessage(CMD_RESET_SUPPLICANT_STATE);
                    /* Initialize data structures */
                    mLastBssid = null;
                    mLastNetworkId = WifiConfiguration.INVALID_NETWORK_ID;
                    mLastSignalLevel = -1;

                    mWifiInfo.setMacAddress(mWifiNative.getMacAddress());
                    mWifiNative.enableSaveConfig();
                    mWifiConfigStore.loadAndEnableAllNetworks();
                    if (mWifiConfigStore.enableVerboseLogging > 0) {
                        enableVerboseLogging(mWifiConfigStore.enableVerboseLogging);
                    }
                    if (mWifiConfigStore.associatedPartialScanPeriodMilli < 0) {
                        mWifiConfigStore.associatedPartialScanPeriodMilli = 0;
                    }
                    initializeWpsDetails();

                    sendSupplicantConnectionChangedBroadcast(true);
                    transitionTo(mDriverStartedState);
                    break;
                case WifiMonitor.SUP_DISCONNECTION_EVENT:
                    if (++mSupplicantRestartCount <= SUPPLICANT_RESTART_TRIES) {
                        loge("Failed to setup control channel, restart supplicant");
                        mWifiMonitor.killSupplicant(mP2pSupported);
                        transitionTo(mInitialState);
                        sendMessageDelayed(CMD_START_SUPPLICANT, SUPPLICANT_RESTART_INTERVAL_MSECS);
                    } else {
                        loge("Failed " + mSupplicantRestartCount +
                                " times to start supplicant, unload driver");
                        mSupplicantRestartCount = 0;
                        setWifiState(WIFI_STATE_UNKNOWN);
                        transitionTo(mInitialState);
                    }
                    break;
                case CMD_START_SUPPLICANT:
                case CMD_STOP_SUPPLICANT:
                case CMD_START_AP:
                case CMD_STOP_AP:
                case CMD_START_DRIVER:
                case CMD_STOP_DRIVER:
                case CMD_SET_OPERATIONAL_MODE:
                case CMD_SET_COUNTRY_CODE:
                case CMD_SET_FREQUENCY_BAND:
                case CMD_START_PACKET_FILTERING:
                case CMD_STOP_PACKET_FILTERING:
                    messageHandlingStatus = MESSAGE_HANDLING_STATUS_DEFERRED;
                    deferMessage(message);
                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }
    }

    class SupplicantStartedState extends State {
        @Override
        public void enter() {
            /* Wifi is available as long as we have a connection to supplicant */
            mNetworkInfo.setIsAvailable(true);
            if (mNetworkAgent != null) mNetworkAgent.sendNetworkInfo(mNetworkInfo);

            int defaultInterval = mContext.getResources().getInteger(
                    R.integer.config_wifi_supplicant_scan_interval);

            mSupplicantScanIntervalMs = Settings.Global.getLong(mContext.getContentResolver(),
                    Settings.Global.WIFI_SUPPLICANT_SCAN_INTERVAL_MS,
                    defaultInterval);

            mWifiNative.setScanInterval((int)mSupplicantScanIntervalMs / 1000);
            mWifiNative.setExternalSim(true);

            setRandomMacOui();
            mWifiNative.enableAutoConnect(false);
        }

        @Override
        public boolean processMessage(Message message) {
            logStateAndMessage(message, getClass().getSimpleName());

            switch(message.what) {
                case CMD_STOP_SUPPLICANT:   /* Supplicant stopped by user */
                    if (mP2pSupported) {
                        transitionTo(mWaitForP2pDisableState);
                    } else {
                        transitionTo(mSupplicantStoppingState);
                    }
                    break;
                case WifiMonitor.SUP_DISCONNECTION_EVENT:  /* Supplicant connection lost */
                    loge("Connection lost, restart supplicant");
                    handleSupplicantConnectionLoss();
                    handleNetworkDisconnect();
                    mSupplicantStateTracker.sendMessage(CMD_RESET_SUPPLICANT_STATE);
                    if (mP2pSupported) {
                        transitionTo(mWaitForP2pDisableState);
                    } else {
                        transitionTo(mInitialState);
                    }
                    sendMessageDelayed(CMD_START_SUPPLICANT, SUPPLICANT_RESTART_INTERVAL_MSECS);
                    break;
                case WifiMonitor.SCAN_RESULTS_EVENT:
                    maybeRegisterNetworkFactory(); // Make sure our NetworkFactory is registered
                    closeRadioScanStats();
                    noteScanEnd();
                    setScanResults();
                    if (mIsFullScanOngoing || mSendScanResultsBroadcast) {
                        /* Just updated results from full scan, let apps know about this */
                        sendScanResultsAvailableBroadcast();
                    }
                    mSendScanResultsBroadcast = false;
                    mIsScanOngoing = false;
                    mIsFullScanOngoing = false;
                    if (mBufferedScanMsg.size() > 0)
                        sendMessage(mBufferedScanMsg.remove());
                    break;
                case CMD_PING_SUPPLICANT:
                    boolean ok = mWifiNative.ping();
                    replyToMessage(message, message.what, ok ? SUCCESS : FAILURE);
                    break;
                case CMD_GET_CAPABILITY_FREQ:
                    String freqs = mWifiNative.getFreqCapability();
                    replyToMessage(message, message.what, freqs);
                    break;
                case CMD_START_AP:
                    /* Cannot start soft AP while in client mode */
                    loge("Failed to start soft AP with a running supplicant");
                    setWifiApState(WIFI_AP_STATE_FAILED);
                    break;
                case CMD_SET_OPERATIONAL_MODE:
                    mOperationalMode = message.arg1;
                    mWifiConfigStore.
                            setLastSelectedConfiguration(WifiConfiguration.INVALID_NETWORK_ID);
                    break;
                case CMD_TARGET_BSSID:
                    // Trying to associate to this BSSID
                    if (message.obj != null) {
                        mTargetRoamBSSID = (String) message.obj;
                    }
                    break;
                case CMD_GET_LINK_LAYER_STATS:
                    WifiLinkLayerStats stats = getWifiLinkLayerStats(DBG);
                    if (stats == null) {
                        // When firmware doesnt support link layer stats, return an empty object
                        stats = new WifiLinkLayerStats();
                    }
                    replyToMessage(message, message.what, stats);
                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }

        @Override
        public void exit() {
            mNetworkInfo.setIsAvailable(false);
            if (mNetworkAgent != null) mNetworkAgent.sendNetworkInfo(mNetworkInfo);
        }
    }

    class SupplicantStoppingState extends State {
        @Override
        public void enter() {
            /* Send any reset commands to supplicant before shutting it down */
            handleNetworkDisconnect();
            if (mDhcpStateMachine != null) {
                mDhcpStateMachine.doQuit();
            }

            String suppState = System.getProperty("init.svc.wpa_supplicant");
            if (suppState == null) suppState = "unknown";
            String p2pSuppState = System.getProperty("init.svc.p2p_supplicant");
            if (p2pSuppState == null) p2pSuppState = "unknown";

            loge("SupplicantStoppingState: stopSupplicant "
                    + " init.svc.wpa_supplicant=" + suppState
                    + " init.svc.p2p_supplicant=" + p2pSuppState);
            mWifiMonitor.stopSupplicant();

            /* Send ourselves a delayed message to indicate failure after a wait time */
            sendMessageDelayed(obtainMessage(CMD_STOP_SUPPLICANT_FAILED,
                    ++mSupplicantStopFailureToken, 0), SUPPLICANT_RESTART_INTERVAL_MSECS);
            setWifiState(WIFI_STATE_DISABLING);
            mSupplicantStateTracker.sendMessage(CMD_RESET_SUPPLICANT_STATE);
        }
        @Override
        public boolean processMessage(Message message) {
            logStateAndMessage(message, getClass().getSimpleName());

            switch(message.what) {
                case WifiMonitor.SUP_CONNECTION_EVENT:
                    loge("Supplicant connection received while stopping");
                    break;
                case WifiMonitor.SUP_DISCONNECTION_EVENT:
                    if (DBG) log("Supplicant connection lost");
                    handleSupplicantConnectionLoss();
                    transitionTo(mInitialState);
                    break;
                case CMD_STOP_SUPPLICANT_FAILED:
                    if (message.arg1 == mSupplicantStopFailureToken) {
                        loge("Timed out on a supplicant stop, kill and proceed");
                        handleSupplicantConnectionLoss();
                        transitionTo(mInitialState);
                    }
                    break;
                case CMD_START_SUPPLICANT:
                case CMD_STOP_SUPPLICANT:
                case CMD_START_AP:
                case CMD_STOP_AP:
                case CMD_START_DRIVER:
                case CMD_STOP_DRIVER:
                case CMD_SET_OPERATIONAL_MODE:
                case CMD_SET_COUNTRY_CODE:
                case CMD_SET_FREQUENCY_BAND:
                case CMD_START_PACKET_FILTERING:
                case CMD_STOP_PACKET_FILTERING:
                    deferMessage(message);
                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }
    }

    class DriverStartingState extends State {
        private int mTries;
        @Override
        public void enter() {
            mTries = 1;
            /* Send ourselves a delayed message to start driver a second time */
            sendMessageDelayed(obtainMessage(CMD_DRIVER_START_TIMED_OUT,
                        ++mDriverStartToken, 0), DRIVER_START_TIME_OUT_MSECS);
        }
        @Override
        public boolean processMessage(Message message) {
            logStateAndMessage(message, getClass().getSimpleName());

            switch(message.what) {
               case WifiMonitor.SUPPLICANT_STATE_CHANGE_EVENT:
                    SupplicantState state = handleSupplicantStateChange(message);
                    /* If suplicant is exiting out of INTERFACE_DISABLED state into
                     * a state that indicates driver has started, it is ready to
                     * receive driver commands
                     */
                    if (SupplicantState.isDriverActive(state)) {
                        transitionTo(mDriverStartedState);
                    }
                    break;
                case CMD_DRIVER_START_TIMED_OUT:
                    if (message.arg1 == mDriverStartToken) {
                        if (mTries >= 2) {
                            loge("Failed to start driver after " + mTries);
                            transitionTo(mDriverStoppedState);
                        } else {
                            loge("Driver start failed, retrying");
                            mWakeLock.acquire();
                            mWifiNative.startDriver();
                            mWakeLock.release();

                            ++mTries;
                            /* Send ourselves a delayed message to start driver again */
                            sendMessageDelayed(obtainMessage(CMD_DRIVER_START_TIMED_OUT,
                                        ++mDriverStartToken, 0), DRIVER_START_TIME_OUT_MSECS);
                        }
                    }
                    break;
                    /* Queue driver commands & connection events */
                case CMD_START_DRIVER:
                case CMD_STOP_DRIVER:
                case WifiMonitor.NETWORK_CONNECTION_EVENT:
                case WifiMonitor.NETWORK_DISCONNECTION_EVENT:
                case WifiMonitor.AUTHENTICATION_FAILURE_EVENT:
                case WifiMonitor.ASSOCIATION_REJECTION_EVENT:
                case WifiMonitor.WPS_OVERLAP_EVENT:
                case CMD_SET_COUNTRY_CODE:
                case CMD_SET_FREQUENCY_BAND:
                case CMD_START_PACKET_FILTERING:
                case CMD_STOP_PACKET_FILTERING:
                case CMD_START_SCAN:
                case CMD_DISCONNECT:
                case CMD_REASSOCIATE:
                case CMD_RECONNECT:
                    messageHandlingStatus = MESSAGE_HANDLING_STATUS_DEFERRED;
                    deferMessage(message);
                    break;
                case WifiMonitor.SCAN_RESULTS_EVENT:
                    // Loose scan results obtained in Driver Starting state, they can only confuse
                    // the state machine
                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }
    }

    class DriverStartedState extends State {
        @Override
        public void enter() {

            if (PDBG) {
                loge("DriverStartedState enter");
            }
            mIsRunning = true;
            mInDelayedStop = false;
            mDelayedStopCounter++;
            updateBatteryWorkSource(null);
            /**
             * Enable bluetooth coexistence scan mode when bluetooth connection is active.
             * When this mode is on, some of the low-level scan parameters used by the
             * driver are changed to reduce interference with bluetooth
             */
            mWifiNative.setBluetoothCoexistenceScanMode(mBluetoothConnectionActive);
            /* set country code */
            setCountryCode();
            /* set frequency band of operation */
            setFrequencyBand();
            /* initialize network state */
            setNetworkDetailedState(DetailedState.DISCONNECTED);

            /* Remove any filtering on Multicast v6 at start */
            mWifiNative.stopFilteringMulticastV6Packets();

            /* Reset Multicast v4 filtering state */
            if (mFilteringMulticastV4Packets.get()) {
                mWifiNative.startFilteringMulticastV4Packets();
            } else {
                mWifiNative.stopFilteringMulticastV4Packets();
            }

            mDhcpActive = false;

            startBatchedScan();

            if (mOperationalMode != CONNECT_MODE) {
                mWifiNative.disconnect();
                mWifiConfigStore.disableAllNetworks();
                if (mOperationalMode == SCAN_ONLY_WITH_WIFI_OFF_MODE) {
                    setWifiState(WIFI_STATE_DISABLED);
                }
                transitionTo(mScanModeState);
            } else {

                // Status pulls in the current supplicant state and network connection state
                // events over the monitor connection. This helps framework sync up with
                // current supplicant state
                // TODO: actually check th supplicant status string and make sure the supplicant
                // is in disconnecte4d state.
                mWifiNative.status();
                // Transitioning to Disconnected state will trigger a scan and subsequently AutoJoin
                transitionTo(mDisconnectedState);
            }

            // We may have missed screen update at boot
            if (mScreenBroadcastReceived.get() == false) {
                PowerManager powerManager = (PowerManager)mContext.getSystemService(
                        Context.POWER_SERVICE);
                handleScreenStateChanged(powerManager.isScreenOn(),
                        /* startBackgroundScanIfNeeded = */ false);
            } else {
                // Set the right suspend mode settings
                mWifiNative.setSuspendOptimizations(mSuspendOptNeedsDisabled == 0
                        && mUserWantsSuspendOpt.get());
            }
            mWifiNative.setPowerSave(true);

            if (mP2pSupported) {
                if (mOperationalMode == CONNECT_MODE) {
                    mWifiP2pChannel.sendMessage(WifiStateMachine.CMD_ENABLE_P2P);
                } else {
                    // P2P statemachine starts in disabled state, and is not enabled until
                    // CMD_ENABLE_P2P is sent from here; so, nothing needs to be done to
                    // keep it disabled.
                }
            }

            final Intent intent = new Intent(WifiManager.WIFI_SCAN_AVAILABLE);
            intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
            intent.putExtra(WifiManager.EXTRA_SCAN_AVAILABLE, WIFI_STATE_ENABLED);
            mContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);

            if (PDBG) {
                loge("Driverstarted State enter done");
            }
        }

        @Override
        public boolean processMessage(Message message) {
            logStateAndMessage(message, getClass().getSimpleName());

            switch(message.what) {
                case CMD_START_SCAN:
                    handleScanRequest(WifiNative.SCAN_WITHOUT_CONNECTION_SETUP, message);
                    break;
                case CMD_SET_BATCHED_SCAN:
                    if (recordBatchedScanSettings(message.arg1, message.arg2,
                            (Bundle)message.obj)) {
                        if (mBatchedScanSettings != null) {
                            startBatchedScan();
                        } else {
                            stopBatchedScan();
                        }
                    }
                    break;
                case CMD_SET_COUNTRY_CODE:
                    String country = (String) message.obj;
                    final boolean persist = (message.arg2 == 1);
                    final int sequence = message.arg1;
                    if (sequence != mCountryCodeSequence.get()) {
                        if (DBG) log("set country code ignored due to sequnce num");
                        break;
                    }
                    if (DBG) log("set country code " + country);
                    if (persist) {
                        mPersistedCountryCode = country;
                        Settings.Global.putString(mContext.getContentResolver(),
                                Settings.Global.WIFI_COUNTRY_CODE,
                                country);
                    }
                    country = country.toUpperCase(Locale.ROOT);
                    if (mLastSetCountryCode == null
                            || country.equals(mLastSetCountryCode) == false) {
                        if (mWifiNative.setCountryCode(country)) {
                            mLastSetCountryCode = country;
                        } else {
                            loge("Failed to set country code " + country);
                        }
                    }
                    mWifiP2pChannel.sendMessage(WifiP2pServiceImpl.SET_COUNTRY_CODE, country);
                    break;
                case CMD_SET_FREQUENCY_BAND:
                    int band =  message.arg1;
                    if (DBG) log("set frequency band " + band);
                    if (mWifiNative.setBand(band)) {

                        if (PDBG)  loge("did set frequency band " + band);

                        mFrequencyBand.set(band);
                        // Flush old data - like scan results
                        mWifiNative.bssFlush();
                        // Fetch the latest scan results when frequency band is set
                        startScanNative(WifiNative.SCAN_WITHOUT_CONNECTION_SETUP, null);

                        if (PDBG)  loge("done set frequency band " + band);

                    } else {
                        loge("Failed to set frequency band " + band);
                    }
                    break;
                case CMD_BLUETOOTH_ADAPTER_STATE_CHANGE:
                    mBluetoothConnectionActive = (message.arg1 !=
                            BluetoothAdapter.STATE_DISCONNECTED);
                    mWifiNative.setBluetoothCoexistenceScanMode(mBluetoothConnectionActive);
                    break;
                case CMD_STOP_DRIVER:
                    int mode = message.arg1;

                    /* Already doing a delayed stop */
                    if (mInDelayedStop) {
                        if (DBG) log("Already in delayed stop");
                        break;
                    }
                    /* disconnect right now, but leave the driver running for a bit */
                    mWifiConfigStore.disableAllNetworks();

                    mInDelayedStop = true;
                    mDelayedStopCounter++;
                    if (DBG) log("Delayed stop message " + mDelayedStopCounter);

                    /* send regular delayed shut down */
                    Intent driverStopIntent = new Intent(ACTION_DELAYED_DRIVER_STOP, null);
                    driverStopIntent.setPackage(this.getClass().getPackage().getName());
                    driverStopIntent.putExtra(DELAYED_STOP_COUNTER, mDelayedStopCounter);
                    mDriverStopIntent = PendingIntent.getBroadcast(mContext,
                            DRIVER_STOP_REQUEST, driverStopIntent,
                            PendingIntent.FLAG_UPDATE_CURRENT);

                    mAlarmManager.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis()
                            + mDriverStopDelayMs, mDriverStopIntent);
                    break;
                case CMD_START_DRIVER:
                    if (mInDelayedStop) {
                        mInDelayedStop = false;
                        mDelayedStopCounter++;
                        mAlarmManager.cancel(mDriverStopIntent);
                        if (DBG) log("Delayed stop ignored due to start");
                        if (mOperationalMode == CONNECT_MODE) {
                            mWifiConfigStore.enableAllNetworks();
                        }
                    }
                    break;
                case CMD_DELAYED_STOP_DRIVER:
                    if (DBG) log("delayed stop " + message.arg1 + " " + mDelayedStopCounter);
                    if (message.arg1 != mDelayedStopCounter) break;
                    if (getCurrentState() != mDisconnectedState) {
                        mWifiNative.disconnect();
                        handleNetworkDisconnect();
                    }
                    mWakeLock.acquire();
                    mWifiNative.stopDriver();
                    mWakeLock.release();
                    if (mP2pSupported) {
                        transitionTo(mWaitForP2pDisableState);
                    } else {
                        transitionTo(mDriverStoppingState);
                    }
                    break;
                case CMD_START_PACKET_FILTERING:
                    if (message.arg1 == MULTICAST_V6) {
                        mWifiNative.startFilteringMulticastV6Packets();
                    } else if (message.arg1 == MULTICAST_V4) {
                        mWifiNative.startFilteringMulticastV4Packets();
                    } else {
                        loge("Illegal arugments to CMD_START_PACKET_FILTERING");
                    }
                    break;
                case CMD_STOP_PACKET_FILTERING:
                    if (message.arg1 == MULTICAST_V6) {
                        mWifiNative.stopFilteringMulticastV6Packets();
                    } else if (message.arg1 == MULTICAST_V4) {
                        mWifiNative.stopFilteringMulticastV4Packets();
                    } else {
                        loge("Illegal arugments to CMD_STOP_PACKET_FILTERING");
                    }
                    break;
                case CMD_SET_SUSPEND_OPT_ENABLED:
                    if (message.arg1 == 1) {
                        setSuspendOptimizationsNative(SUSPEND_DUE_TO_SCREEN, true);
                        mSuspendWakeLock.release();
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
                        mWifiNative.startTdls(remoteAddress, enable);
                    }
                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }
        @Override
        public void exit() {
            mIsRunning = false;
            updateBatteryWorkSource(null);
            mScanResults = new ArrayList<ScanResult>();

            stopBatchedScan();

            final Intent intent = new Intent(WifiManager.WIFI_SCAN_AVAILABLE);
            intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
            intent.putExtra(WifiManager.EXTRA_SCAN_AVAILABLE, WIFI_STATE_DISABLED);
            mContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
            noteScanEnd(); // wrap up any pending request.
            mBufferedScanMsg.clear();

            mLastSetCountryCode = null;
        }
    }

    class WaitForP2pDisableState extends State {
        private State mTransitionToState;
        @Override
        public void enter() {
            switch (getCurrentMessage().what) {
                case WifiMonitor.SUP_DISCONNECTION_EVENT:
                    mTransitionToState = mInitialState;
                    break;
                case CMD_DELAYED_STOP_DRIVER:
                    mTransitionToState = mDriverStoppingState;
                    break;
                case CMD_STOP_SUPPLICANT:
                    mTransitionToState = mSupplicantStoppingState;
                    break;
                default:
                    mTransitionToState = mDriverStoppingState;
                    break;
            }
            mWifiP2pChannel.sendMessage(WifiStateMachine.CMD_DISABLE_P2P_REQ);
        }
        @Override
        public boolean processMessage(Message message) {
            logStateAndMessage(message, getClass().getSimpleName());

            switch(message.what) {
                case WifiStateMachine.CMD_DISABLE_P2P_RSP:
                    transitionTo(mTransitionToState);
                    break;
                /* Defer wifi start/shut and driver commands */
                case WifiMonitor.SUPPLICANT_STATE_CHANGE_EVENT:
                case CMD_START_SUPPLICANT:
                case CMD_STOP_SUPPLICANT:
                case CMD_START_AP:
                case CMD_STOP_AP:
                case CMD_START_DRIVER:
                case CMD_STOP_DRIVER:
                case CMD_SET_OPERATIONAL_MODE:
                case CMD_SET_COUNTRY_CODE:
                case CMD_SET_FREQUENCY_BAND:
                case CMD_START_PACKET_FILTERING:
                case CMD_STOP_PACKET_FILTERING:
                case CMD_START_SCAN:
                case CMD_DISCONNECT:
                case CMD_REASSOCIATE:
                case CMD_RECONNECT:
                    messageHandlingStatus = MESSAGE_HANDLING_STATUS_DEFERRED;
                    deferMessage(message);
                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }
    }

    class DriverStoppingState extends State {
        @Override
        public boolean processMessage(Message message) {
            logStateAndMessage(message, getClass().getSimpleName());

            switch(message.what) {
                case WifiMonitor.SUPPLICANT_STATE_CHANGE_EVENT:
                    SupplicantState state = handleSupplicantStateChange(message);
                    if (state == SupplicantState.INTERFACE_DISABLED) {
                        transitionTo(mDriverStoppedState);
                    }
                    break;
                    /* Queue driver commands */
                case CMD_START_DRIVER:
                case CMD_STOP_DRIVER:
                case CMD_SET_COUNTRY_CODE:
                case CMD_SET_FREQUENCY_BAND:
                case CMD_START_PACKET_FILTERING:
                case CMD_STOP_PACKET_FILTERING:
                case CMD_START_SCAN:
                case CMD_DISCONNECT:
                case CMD_REASSOCIATE:
                case CMD_RECONNECT:
                    messageHandlingStatus = MESSAGE_HANDLING_STATUS_DEFERRED;
                    deferMessage(message);
                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }
    }

    class DriverStoppedState extends State {
        @Override
        public boolean processMessage(Message message) {
            logStateAndMessage(message, getClass().getSimpleName());
            switch (message.what) {
                case WifiMonitor.SUPPLICANT_STATE_CHANGE_EVENT:
                    StateChangeResult stateChangeResult = (StateChangeResult) message.obj;
                    SupplicantState state = stateChangeResult.state;
                    // A WEXT bug means that we can be back to driver started state
                    // unexpectedly
                    if (SupplicantState.isDriverActive(state)) {
                        transitionTo(mDriverStartedState);
                    }
                    break;
                case CMD_START_DRIVER:
                    mWakeLock.acquire();
                    mWifiNative.startDriver();
                    mWakeLock.release();
                    transitionTo(mDriverStartingState);
                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }
    }

    class ScanModeState extends State {
        private int mLastOperationMode;
        @Override
        public void enter() {
            mLastOperationMode = mOperationalMode;
        }
        @Override
        public boolean processMessage(Message message) {
            logStateAndMessage(message, getClass().getSimpleName());

            switch(message.what) {
                case CMD_SET_OPERATIONAL_MODE:
                    if (message.arg1 == CONNECT_MODE) {

                        if (mLastOperationMode == SCAN_ONLY_WITH_WIFI_OFF_MODE) {
                            setWifiState(WIFI_STATE_ENABLED);
                            // Load and re-enable networks when going back to enabled state
                            // This is essential for networks to show up after restore
                            mWifiConfigStore.loadAndEnableAllNetworks();
                            mWifiP2pChannel.sendMessage(CMD_ENABLE_P2P);
                        } else {
                            mWifiConfigStore.enableAllNetworks();
                        }

                        // Try autojoining with recent network already present in the cache
                        // If none are found then trigger a scan which will trigger autojoin
                        // upon reception of scan results event
                        if (!mWifiAutoJoinController.attemptAutoJoin()) {
                            startScan(ENABLE_WIFI, 0, null, null);
                        }

                        // Loose last selection choice since user toggled WiFi
                        mWifiConfigStore.
                                setLastSelectedConfiguration(WifiConfiguration.INVALID_NETWORK_ID);

                        mOperationalMode = CONNECT_MODE;
                        transitionTo(mDisconnectedState);
                    } else {
                        // Nothing to do
                        return HANDLED;
                    }
                    break;
                // Handle scan. All the connection related commands are
                // handled only in ConnectModeState
                case CMD_START_SCAN:
                    handleScanRequest(WifiNative.SCAN_WITHOUT_CONNECTION_SETUP, message);
                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }
    }


    String smToString(Message message) {
        return smToString(message.what);
    }

    String smToString(int what) {
        String s = "unknown";
        switch (what) {
            case WifiMonitor.DRIVER_HUNG_EVENT:
                s = "DRIVER_HUNG_EVENT";
                break;
            case AsyncChannel.CMD_CHANNEL_HALF_CONNECTED:
                s = "AsyncChannel.CMD_CHANNEL_HALF_CONNECTED";
                break;
            case AsyncChannel.CMD_CHANNEL_DISCONNECTED:
                s = "AsyncChannel.CMD_CHANNEL_DISCONNECTED";
                break;
            case CMD_SET_FREQUENCY_BAND:
                s = "CMD_SET_FREQUENCY_BAND";
                break;
            case CMD_DELAYED_NETWORK_DISCONNECT:
                s = "CMD_DELAYED_NETWORK_DISCONNECT";
                break;
            case CMD_TEST_NETWORK_DISCONNECT:
                s = "CMD_TEST_NETWORK_DISCONNECT";
                break;
            case CMD_OBTAINING_IP_ADDRESS_WATCHDOG_TIMER:
                s = "CMD_OBTAINING_IP_ADDRESS_WATCHDOG_TIMER";
                break;
            case CMD_DISABLE_EPHEMERAL_NETWORK:
                s = "CMD_DISABLE_EPHEMERAL_NETWORK";
                break;
            case CMD_START_DRIVER:
                s = "CMD_START_DRIVER";
                break;
            case CMD_STOP_DRIVER:
                s = "CMD_STOP_DRIVER";
                break;
            case CMD_STOP_SUPPLICANT:
                s = "CMD_STOP_SUPPLICANT";
                break;
            case CMD_STOP_SUPPLICANT_FAILED:
                s = "CMD_STOP_SUPPLICANT_FAILED";
                break;
            case CMD_START_SUPPLICANT:
                s = "CMD_START_SUPPLICANT";
                break;
            case CMD_REQUEST_AP_CONFIG:
                s = "CMD_REQUEST_AP_CONFIG";
                break;
            case CMD_RESPONSE_AP_CONFIG:
                s = "CMD_RESPONSE_AP_CONFIG";
                break;
            case CMD_TETHER_STATE_CHANGE:
                s = "CMD_TETHER_STATE_CHANGE";
                break;
            case CMD_TETHER_NOTIFICATION_TIMED_OUT:
                s = "CMD_TETHER_NOTIFICATION_TIMED_OUT";
                break;
            case CMD_BLUETOOTH_ADAPTER_STATE_CHANGE:
                s = "CMD_BLUETOOTH_ADAPTER_STATE_CHANGE";
                break;
            case CMD_ADD_OR_UPDATE_NETWORK:
                s = "CMD_ADD_OR_UPDATE_NETWORK";
                break;
            case CMD_REMOVE_NETWORK:
                s = "CMD_REMOVE_NETWORK";
                break;
            case CMD_ENABLE_NETWORK:
                s = "CMD_ENABLE_NETWORK";
                break;
            case CMD_ENABLE_ALL_NETWORKS:
                s = "CMD_ENABLE_ALL_NETWORKS";
                break;
            case CMD_AUTO_CONNECT:
                s = "CMD_AUTO_CONNECT";
                break;
            case CMD_AUTO_ROAM:
                s = "CMD_AUTO_ROAM";
                break;
            case CMD_AUTO_SAVE_NETWORK:
                s = "CMD_AUTO_SAVE_NETWORK";
                break;
            case CMD_BOOT_COMPLETED:
                s = "CMD_BOOT_COMPLETED";
                break;
            case DhcpStateMachine.CMD_START_DHCP:
                s = "CMD_START_DHCP";
                break;
            case DhcpStateMachine.CMD_STOP_DHCP:
                s = "CMD_STOP_DHCP";
                break;
            case DhcpStateMachine.CMD_RENEW_DHCP:
                s = "CMD_RENEW_DHCP";
                break;
            case DhcpStateMachine.CMD_PRE_DHCP_ACTION:
                s = "CMD_PRE_DHCP_ACTION";
                break;
            case DhcpStateMachine.CMD_POST_DHCP_ACTION:
                s = "CMD_POST_DHCP_ACTION";
                break;
            case DhcpStateMachine.CMD_PRE_DHCP_ACTION_COMPLETE:
                s = "CMD_PRE_DHCP_ACTION_COMPLETE";
                break;
            case DhcpStateMachine.CMD_ON_QUIT:
                s = "CMD_ON_QUIT";
                break;
            case WifiP2pServiceImpl.DISCONNECT_WIFI_REQUEST:
                s = "WifiP2pServiceImpl.DISCONNECT_WIFI_REQUEST";
                break;
            case WifiManager.DISABLE_NETWORK:
                s = "WifiManager.DISABLE_NETWORK";
                break;
            case CMD_BLACKLIST_NETWORK:
                s = "CMD_BLACKLIST_NETWORK";
                break;
            case CMD_CLEAR_BLACKLIST:
                s = "CMD_CLEAR_BLACKLIST";
                break;
            case CMD_SAVE_CONFIG:
                s = "CMD_SAVE_CONFIG";
                break;
            case CMD_GET_CONFIGURED_NETWORKS:
                s = "CMD_GET_CONFIGURED_NETWORKS";
                break;
            case CMD_GET_SUPPORTED_FEATURES:
                s = "CMD_GET_ADAPTORS";
                break;
            case CMD_UNWANTED_NETWORK:
                s = "CMD_UNWANTED_NETWORK";
                break;
            case CMD_NETWORK_STATUS:
                s = "CMD_NETWORK_STATUS";
                break;
            case CMD_GET_LINK_LAYER_STATS:
                s = "CMD_GET_LINK_LAYER_STATS";
                break;
            case CMD_GET_PRIVILEGED_CONFIGURED_NETWORKS:
                s = "CMD_GET_PRIVILEGED_CONFIGURED_NETWORKS";
                break;
            case CMD_DISCONNECT:
                s = "CMD_DISCONNECT";
                break;
            case CMD_RECONNECT:
                s = "CMD_RECONNECT";
                break;
            case CMD_REASSOCIATE:
                s = "CMD_REASSOCIATE";
                break;
            case CMD_GET_CONNECTION_STATISTICS:
                s = "CMD_GET_CONNECTION_STATISTICS";
                break;
            case CMD_SET_HIGH_PERF_MODE:
                s = "CMD_SET_HIGH_PERF_MODE";
                break;
            case CMD_SET_COUNTRY_CODE:
                s = "CMD_SET_COUNTRY_CODE";
                break;
            case CMD_ENABLE_RSSI_POLL:
                s = "CMD_ENABLE_RSSI_POLL";
                break;
            case CMD_RSSI_POLL:
                s = "CMD_RSSI_POLL";
                break;
            case CMD_START_PACKET_FILTERING:
                s = "CMD_START_PACKET_FILTERING";
                break;
            case CMD_STOP_PACKET_FILTERING:
                s = "CMD_STOP_PACKET_FILTERING";
                break;
            case CMD_SET_SUSPEND_OPT_ENABLED:
                s = "CMD_SET_SUSPEND_OPT_ENABLED";
                break;
            case CMD_NO_NETWORKS_PERIODIC_SCAN:
                s = "CMD_NO_NETWORKS_PERIODIC_SCAN";
                break;
            case CMD_SET_BATCHED_SCAN:
                s = "CMD_SET_BATCHED_SCAN";
                break;
            case CMD_START_NEXT_BATCHED_SCAN:
                s = "CMD_START_NEXT_BATCHED_SCAN";
                break;
            case CMD_POLL_BATCHED_SCAN:
                s = "CMD_POLL_BATCHED_SCAN";
                break;
            case CMD_UPDATE_LINKPROPERTIES:
                s = "CMD_UPDATE_LINKPROPERTIES";
                break;
            case CMD_RELOAD_TLS_AND_RECONNECT:
                s = "CMD_RELOAD_TLS_AND_RECONNECT";
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
            case WifiMonitor.SUP_CONNECTION_EVENT:
                s = "SUP_CONNECTION_EVENT";
                break;
            case WifiMonitor.SUP_DISCONNECTION_EVENT:
                s = "SUP_DISCONNECTION_EVENT";
                break;
            case WifiMonitor.SCAN_RESULTS_EVENT:
                s = "SCAN_RESULTS_EVENT";
                break;
            case WifiMonitor.SUPPLICANT_STATE_CHANGE_EVENT:
                s = "SUPPLICANT_STATE_CHANGE_EVENT";
                break;
            case WifiMonitor.AUTHENTICATION_FAILURE_EVENT:
                s = "AUTHENTICATION_FAILURE_EVENT";
                break;
            case WifiMonitor.SSID_TEMP_DISABLED:
                s = "SSID_TEMP_DISABLED";
                break;
            case WifiMonitor.SSID_REENABLED:
                s = "SSID_REENABLED";
                break;
            case WifiMonitor.WPS_SUCCESS_EVENT:
                s = "WPS_SUCCESS_EVENT";
                break;
            case WifiMonitor.WPS_FAIL_EVENT:
                s = "WPS_FAIL_EVENT";
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
            case CMD_SET_OPERATIONAL_MODE:
                s = "CMD_SET_OPERATIONAL_MODE";
                break;
            case CMD_START_SCAN:
                s = "CMD_START_SCAN";
                break;
            case CMD_DISABLE_P2P_RSP:
                s = "CMD_DISABLE_P2P_RSP";
                break;
            case CMD_DISABLE_P2P_REQ:
                s = "CMD_DISABLE_P2P_REQ";
                break;
            case WifiWatchdogStateMachine.GOOD_LINK_DETECTED:
                s = "GOOD_LINK_DETECTED";
                break;
            case WifiWatchdogStateMachine.POOR_LINK_DETECTED:
                s = "POOR_LINK_DETECTED";
                break;
            case WifiP2pServiceImpl.GROUP_CREATING_TIMED_OUT:
                s = "GROUP_CREATING_TIMED_OUT";
                break;
            case WifiP2pServiceImpl.P2P_CONNECTION_CHANGED:
                s = "P2P_CONNECTION_CHANGED";
                break;
            case WifiP2pServiceImpl.DISCONNECT_WIFI_RESPONSE:
                s = "P2P.DISCONNECT_WIFI_RESPONSE";
                break;
            case WifiP2pServiceImpl.SET_MIRACAST_MODE:
                s = "P2P.SET_MIRACAST_MODE";
                break;
            case WifiP2pServiceImpl.BLOCK_DISCOVERY:
                s = "P2P.BLOCK_DISCOVERY";
                break;
            case WifiP2pServiceImpl.SET_COUNTRY_CODE:
                s = "P2P.SET_COUNTRY_CODE";
                break;
            case WifiManager.CANCEL_WPS:
                s = "CANCEL_WPS";
                break;
            case WifiManager.CANCEL_WPS_FAILED:
                s = "CANCEL_WPS_FAILED";
                break;
            case WifiManager.CANCEL_WPS_SUCCEDED:
                s = "CANCEL_WPS_SUCCEDED";
                break;
            case WifiManager.START_WPS:
                s = "START_WPS";
                break;
            case WifiManager.START_WPS_SUCCEEDED:
                s = "START_WPS_SUCCEEDED";
                break;
            case WifiManager.WPS_FAILED:
                s = "WPS_FAILED";
                break;
            case WifiManager.WPS_COMPLETED:
                s = "WPS_COMPLETED";
                break;
            case WifiManager.RSSI_PKTCNT_FETCH:
                s = "RSSI_PKTCNT_FETCH";
                break;
            case CMD_IP_CONFIGURATION_LOST:
                s = "CMD_IP_CONFIGURATION_LOST";
                break;
            case CMD_IP_CONFIGURATION_SUCCESSFUL:
                s = "CMD_IP_CONFIGURATION_SUCCESSFUL";
                break;
            case CMD_STATIC_IP_SUCCESS:
                s = "CMD_STATIC_IP_SUCCESSFUL";
                break;
            case CMD_STATIC_IP_FAILURE:
                s = "CMD_STATIC_IP_FAILURE";
                break;
            case DhcpStateMachine.DHCP_SUCCESS:
                s = "DHCP_SUCCESS";
                break;
            case DhcpStateMachine.DHCP_FAILURE:
                s = "DHCP_FAILURE";
                break;
            case CMD_TARGET_BSSID:
                s = "CMD_TARGET_BSSID";
                break;
            case CMD_ASSOCIATED_BSSID:
                s = "CMD_ASSOCIATED_BSSID";
                break;
            case CMD_ROAM_WATCHDOG_TIMER:
                s = "CMD_ROAM_WATCHDOG_TIMER";
                break;
            case CMD_SCREEN_STATE_CHANGED:
                s = "CMD_SCREEN_STATE_CHANGED";
                break;
            case CMD_DISCONNECTING_WATCHDOG_TIMER:
                s = "CMD_DISCONNECTING_WATCHDOG_TIMER";
                break;
            default:
                s = "what:" + Integer.toString(what);
                break;
        }
        return s;
    }

    void registerConnected() {
       if (mLastNetworkId != WifiConfiguration.INVALID_NETWORK_ID) {
           long now_ms = System.currentTimeMillis();
           // We are switching away from this configuration,
           // hence record the time we were connected last
           WifiConfiguration config = mWifiConfigStore.getWifiConfiguration(mLastNetworkId);
           if (config != null) {
               config.lastConnected = System.currentTimeMillis();
               config.autoJoinBailedDueToLowRssi = false;
               config.setAutoJoinStatus(WifiConfiguration.AUTO_JOIN_ENABLED);
               config.numConnectionFailures = 0;
               config.numIpConfigFailures = 0;
               config.numAuthFailures = 0;
               config.numAssociation++;
           }
           mBadLinkspeedcount = 0;
       }
    }

    void registerDisconnected() {
        if (mLastNetworkId != WifiConfiguration.INVALID_NETWORK_ID) {
            long now_ms = System.currentTimeMillis();
            // We are switching away from this configuration,
            // hence record the time we were connected last
            WifiConfiguration config = mWifiConfigStore.getWifiConfiguration(mLastNetworkId);
            if (config != null) {
                config.lastDisconnected = System.currentTimeMillis();
                if (config.ephemeral) {
                    // Remove ephemeral WifiConfigurations from file
                    mWifiConfigStore.forgetNetwork(mLastNetworkId);
                }
            }
        }
    }

    void noteWifiDisabledWhileAssociated() {
        // We got disabled by user while we were associated, make note of it
        int rssi = mWifiInfo.getRssi();
        WifiConfiguration config = getCurrentWifiConfiguration();
        if (getCurrentState() == mConnectedState
                && rssi != WifiInfo.INVALID_RSSI
                && config != null) {
            boolean is24GHz = mWifiInfo.is24GHz();
            boolean isBadRSSI = (is24GHz && rssi < mWifiConfigStore.thresholdBadRssi24)
                    || (!is24GHz && rssi < mWifiConfigStore.thresholdBadRssi5);
            boolean isLowRSSI = (is24GHz && rssi < mWifiConfigStore.thresholdLowRssi24)
                    || (!is24GHz && mWifiInfo.getRssi() < mWifiConfigStore.thresholdLowRssi5);
            boolean isHighRSSI = (is24GHz && rssi >= mWifiConfigStore.thresholdGoodRssi24)
                    || (!is24GHz && mWifiInfo.getRssi() >= mWifiConfigStore.thresholdGoodRssi5);
            if (isBadRSSI) {
                // Take note that we got disabled while RSSI was Bad
                config.numUserTriggeredWifiDisableLowRSSI++;
            } else if (isLowRSSI) {
                // Take note that we got disabled while RSSI was Low
                config.numUserTriggeredWifiDisableBadRSSI++;
            } else if (!isHighRSSI) {
                // Take note that we got disabled while RSSI was Not high
                config.numUserTriggeredWifiDisableNotHighRSSI++;
            }
        }
    }

    WifiConfiguration getCurrentWifiConfiguration() {
        if (mLastNetworkId == WifiConfiguration.INVALID_NETWORK_ID) {
            return null;
        }
        return mWifiConfigStore.getWifiConfiguration(mLastNetworkId);
    }

    ScanResult getCurrentScanResult() {
        WifiConfiguration config = getCurrentWifiConfiguration();
        if (config == null) {
            return null;
        }
        String BSSID = mWifiInfo.getBSSID();
        if (BSSID == null) {
            BSSID = mTargetRoamBSSID;
        }
        if (config.scanResultCache == null) {
            return null;
        }
        return config.scanResultCache.get(BSSID);
    }

    String getCurrentBSSID() {
        if (linkDebouncing) {
            return null;
        }
        return mLastBssid;
    }

    class ConnectModeState extends State {
        @Override
        public boolean processMessage(Message message) {
            WifiConfiguration config;
            int netId;
            boolean ok;
            boolean didDisconnect;
            String bssid;
            String ssid;
            NetworkUpdateResult result;
            logStateAndMessage(message, getClass().getSimpleName());

            switch (message.what) {
                case WifiMonitor.ASSOCIATION_REJECTION_EVENT:
                    didBlackListBSSID = false;
                    bssid = (String) message.obj;
                    if (bssid == null || TextUtils.isEmpty(bssid)) {
                        // If BSSID is null, use the target roam BSSID
                        bssid = mTargetRoamBSSID;
                    }
                    if (bssid != null) {
                        // If we have a BSSID, tell configStore to black list it
                        synchronized(mScanResultCache) {
                            didBlackListBSSID = mWifiConfigStore.handleBSSIDBlackList
                                    (mLastNetworkId, bssid, false);
                        }
                    }
                    mSupplicantStateTracker.sendMessage(WifiMonitor.ASSOCIATION_REJECTION_EVENT);
                    break;
                case WifiMonitor.AUTHENTICATION_FAILURE_EVENT:
                    mSupplicantStateTracker.sendMessage(WifiMonitor.AUTHENTICATION_FAILURE_EVENT);
                    break;
                case WifiMonitor.SSID_TEMP_DISABLED:
                case WifiMonitor.SSID_REENABLED:
                    String substr = (String) message.obj;
                    String en = message.what == WifiMonitor.SSID_TEMP_DISABLED ?
                            "temp-disabled" : "re-enabled";
                    loge("ConnectModeState SSID state=" + en + " nid="
                            + Integer.toString(message.arg1) + " [" + substr + "]");
                    synchronized(mScanResultCache) {
                        mWifiConfigStore.handleSSIDStateChange(message.arg1, message.what ==
                                WifiMonitor.SSID_REENABLED, substr, mWifiInfo.getBSSID());
                    }
                    break;
                case WifiMonitor.SUPPLICANT_STATE_CHANGE_EVENT:
                    SupplicantState state = handleSupplicantStateChange(message);
                    // A driver/firmware hang can now put the interface in a down state.
                    // We detect the interface going down and recover from it
                    if (!SupplicantState.isDriverActive(state)) {
                        if (mNetworkInfo.getState() != NetworkInfo.State.DISCONNECTED) {
                            handleNetworkDisconnect();
                        }
                        log("Detected an interface down, restart driver");
                        transitionTo(mDriverStoppedState);
                        sendMessage(CMD_START_DRIVER);
                        break;
                    }

                    // Supplicant can fail to report a NETWORK_DISCONNECTION_EVENT
                    // when authentication times out after a successful connection,
                    // we can figure this from the supplicant state. If supplicant
                    // state is DISCONNECTED, but the mNetworkInfo says we are not
                    // disconnected, we need to handle a disconnection
                    if (!linkDebouncing && state == SupplicantState.DISCONNECTED &&
                            mNetworkInfo.getState() != NetworkInfo.State.DISCONNECTED) {
                        if (DBG) log("Missed CTRL-EVENT-DISCONNECTED, disconnect");
                        handleNetworkDisconnect();
                        transitionTo(mDisconnectedState);
                    }
                    break;
                case WifiP2pServiceImpl.DISCONNECT_WIFI_REQUEST:
                    if (message.arg1 == 1) {
                        mWifiNative.disconnect();
                        mTemporarilyDisconnectWifi = true;
                    } else {
                        mWifiNative.reconnect();
                        mTemporarilyDisconnectWifi = false;
                    }
                    break;
                case CMD_ADD_OR_UPDATE_NETWORK:
                    config = (WifiConfiguration) message.obj;
                    int res = mWifiConfigStore.addOrUpdateNetwork(config, message.sendingUid);
                    if (res < 0) {
                        messageHandlingStatus = MESSAGE_HANDLING_STATUS_FAIL;
                    } else {
                        WifiConfiguration curConfig = getCurrentWifiConfiguration();
                        if (curConfig != null && config != null) {
                            if (curConfig.priority < config.priority
                                    && config.status == WifiConfiguration.Status.ENABLED) {
                                // Interpret this as a connect attempt
                                // Set the last selected configuration so as to allow the system to
                                // stick the last user choice without persisting the choice
                                mWifiConfigStore.setLastSelectedConfiguration(res);

                                // Remember time of last connection attempt
                                lastConnectAttempt = System.currentTimeMillis();

                                mWifiConnectionStatistics.numWifiManagerJoinAttempt++;

                                // As a courtesy to the caller, trigger a scan now
                                startScan(ADD_OR_UPDATE_SOURCE, 0, null, null);
                            }
                        }
                    }
                    replyToMessage(message, CMD_ADD_OR_UPDATE_NETWORK, res);
                    break;
                case CMD_REMOVE_NETWORK:
                    ok = mWifiConfigStore.removeNetwork(message.arg1);
                    if (!ok) {
                        messageHandlingStatus = MESSAGE_HANDLING_STATUS_FAIL;
                    }
                    replyToMessage(message, message.what, ok ? SUCCESS : FAILURE);
                    break;
                case CMD_ENABLE_NETWORK:
                    boolean others = message.arg2 == 1;
                    // Tell autojoin the user did try to select to that network
                    // However, do NOT persist the choice by bumping the priority of the network
                    if (others) {
                        mWifiAutoJoinController.
                                updateConfigurationHistory(message.arg1, true, false);
                        // Set the last selected configuration so as to allow the system to
                        // stick the last user choice without persisting the choice
                        mWifiConfigStore.setLastSelectedConfiguration(message.arg1);

                        // Remember time of last connection attempt
                        lastConnectAttempt = System.currentTimeMillis();

                        mWifiConnectionStatistics.numWifiManagerJoinAttempt++;
                    }
                    // Cancel auto roam requests
                    autoRoamSetBSSID(message.arg1, "any");

                    ok = mWifiConfigStore.enableNetwork(message.arg1, message.arg2 == 1);
                    if (!ok) {
                        messageHandlingStatus = MESSAGE_HANDLING_STATUS_FAIL;
                    }
                    replyToMessage(message, message.what, ok ? SUCCESS : FAILURE);
                    break;
                case CMD_ENABLE_ALL_NETWORKS:
                    long time = android.os.SystemClock.elapsedRealtime();
                    if (time - mLastEnableAllNetworksTime > MIN_INTERVAL_ENABLE_ALL_NETWORKS_MS) {
                        mWifiConfigStore.enableAllNetworks();
                        mLastEnableAllNetworksTime = time;
                    }
                    break;
                case WifiManager.DISABLE_NETWORK:
                    if (mWifiConfigStore.disableNetwork(message.arg1,
                            WifiConfiguration.DISABLED_BY_WIFI_MANAGER) == true) {
                        replyToMessage(message, WifiManager.DISABLE_NETWORK_SUCCEEDED);
                    } else {
                        messageHandlingStatus = MESSAGE_HANDLING_STATUS_FAIL;
                        replyToMessage(message, WifiManager.DISABLE_NETWORK_FAILED,
                                WifiManager.ERROR);
                    }
                    break;
                case CMD_DISABLE_EPHEMERAL_NETWORK:
                    config = mWifiConfigStore.disableEphemeralNetwork((String)message.obj);
                    if (config != null) {
                        if (config.networkId == mLastNetworkId) {
                            // Disconnect and let autojoin reselect a new network
                            sendMessage(CMD_DISCONNECT);
                        }
                    }
                    break;
                case CMD_BLACKLIST_NETWORK:
                    mWifiNative.addToBlacklist((String) message.obj);
                    break;
                case CMD_CLEAR_BLACKLIST:
                    mWifiNative.clearBlacklist();
                    break;
                case CMD_SAVE_CONFIG:
                    ok = mWifiConfigStore.saveConfig();

                    if (DBG) loge("wifistatemachine did save config " + ok);
                    replyToMessage(message, CMD_SAVE_CONFIG, ok ? SUCCESS : FAILURE);

                    // Inform the backup manager about a data change
                    IBackupManager ibm = IBackupManager.Stub.asInterface(
                            ServiceManager.getService(Context.BACKUP_SERVICE));
                    if (ibm != null) {
                        try {
                            ibm.dataChanged("com.android.providers.settings");
                        } catch (Exception e) {
                            // Try again later
                        }
                    }
                    break;
                case CMD_GET_CONFIGURED_NETWORKS:
                    replyToMessage(message, message.what,
                            mWifiConfigStore.getConfiguredNetworks());
                    break;
                case WifiMonitor.SUP_REQUEST_IDENTITY:
                    // Supplicant lacks credentials to connect to that network, hence black list
                    ssid = (String) message.obj;

                    if (targetWificonfiguration != null && ssid != null
                            && targetWificonfiguration.SSID != null
                            && targetWificonfiguration.SSID.equals("\"" + ssid + "\"")) {
                        mWifiConfigStore.handleSSIDStateChange(targetWificonfiguration.networkId,
                                false, "AUTH_FAILED no identity", null);
                    }
                    // Disconnect now, as we don't have any way to fullfill the  supplicant request.
                    mWifiConfigStore.setLastSelectedConfiguration
                            (WifiConfiguration.INVALID_NETWORK_ID);
                    mWifiNative.disconnect();
                    break;
                case WifiMonitor.SUP_REQUEST_SIM_AUTH:
                    logd("Received SUP_REQUEST_SIM_AUTH");
                    SimAuthRequestData requestData = (SimAuthRequestData) message.obj;
                    if (requestData != null) {
                        if (requestData.protocol == WifiEnterpriseConfig.Eap.SIM) {
                            handleGsmAuthRequest(requestData);
                        } else if (requestData.protocol == WifiEnterpriseConfig.Eap.AKA) {
                            handle3GAuthRequest(requestData);
                        }
                    } else {
                        loge("Invalid sim auth request");
                    }
                    break;
                case CMD_GET_PRIVILEGED_CONFIGURED_NETWORKS:
                    replyToMessage(message, message.what,
                            mWifiConfigStore.getPrivilegedConfiguredNetworks());
                    break;
                    /* Do a redundant disconnect without transition */
                case CMD_DISCONNECT:
                    mWifiConfigStore.setLastSelectedConfiguration
                            (WifiConfiguration.INVALID_NETWORK_ID);
                    mWifiNative.disconnect();
                    break;
                case CMD_RECONNECT:
                    mWifiAutoJoinController.attemptAutoJoin();
                    break;
                case CMD_REASSOCIATE:
                    lastConnectAttempt = System.currentTimeMillis();
                    mWifiNative.reassociate();
                    break;
                case CMD_RELOAD_TLS_AND_RECONNECT:
                    if (mWifiConfigStore.needsUnlockedKeyStore()) {
                        logd("Reconnecting to give a chance to un-connected TLS networks");
                        mWifiNative.disconnect();
                        lastConnectAttempt = System.currentTimeMillis();
                        mWifiNative.reconnect();
                    }
                    break;
                case CMD_AUTO_ROAM:
                    messageHandlingStatus = MESSAGE_HANDLING_STATUS_DISCARD;
                    return HANDLED;
                case CMD_AUTO_CONNECT:
                    /* Work Around: wpa_supplicant can get in a bad state where it returns a non
                     * associated status to the STATUS command but somehow-someplace still thinks
                     * it is associated and thus will ignore select/reconnect command with
                     * following message:
                     * "Already associated with the selected network - do nothing"
                     *
                     * Hence, sends a disconnect to supplicant first.
                     */
                    didDisconnect = false;
                    if (getCurrentState() != mDisconnectedState) {
                        /** Supplicant will ignore the reconnect if we are currently associated,
                         * hence trigger a disconnect
                         */
                        didDisconnect = true;
                        mWifiNative.disconnect();
                    }

                    /* connect command coming from auto-join */
                    config = (WifiConfiguration) message.obj;
                    netId = message.arg1;
                    int roam = message.arg2;
                    loge("CMD_AUTO_CONNECT sup state "
                            + mSupplicantStateTracker.getSupplicantStateName()
                            + " my state " + getCurrentState().getName()
                            + " nid=" + Integer.toString(netId)
                            + " roam=" + Integer.toString(roam));
                    if (config == null) {
                        loge("AUTO_CONNECT and no config, bail out...");
                        break;
                    }

                    /* Make sure we cancel any previous roam request */
                    autoRoamSetBSSID(netId, config.BSSID);

                    /* Save the network config */
                    loge("CMD_AUTO_CONNECT will save config -> " + config.SSID
                            + " nid=" + Integer.toString(netId));
                    result = mWifiConfigStore.saveNetwork(config, -1);
                    netId = result.getNetworkId();
                    loge("CMD_AUTO_CONNECT did save config -> "
                            + " nid=" + Integer.toString(netId));

                    // Make sure the network is enabled, since supplicant will not reenable it
                    mWifiConfigStore.enableNetworkWithoutBroadcast(netId, false);

                    if (mWifiConfigStore.selectNetwork(netId) &&
                            mWifiNative.reconnect()) {
                        lastConnectAttempt = System.currentTimeMillis();
                        targetWificonfiguration = mWifiConfigStore.getWifiConfiguration(netId);
                        config = mWifiConfigStore.getWifiConfiguration(netId);
                        if (config != null
                                && !mWifiConfigStore.isLastSelectedConfiguration(config)) {
                            // If we autojoined a different config than the user selected one,
                            // it means we could not see the last user selection,
                            // or that the last user selection was faulty and ended up blacklisted
                            // for some reason (in which case the user is notified with an error
                            // message in the Wifi picker), and thus we managed to auto-join away
                            // from the selected  config. -> in that case we need to forget
                            // the selection because we don't want to abruptly switch back to it.
                            //
                            // Note that the user selection is also forgotten after a period of time
                            // during which the device has been disconnected.
                            // The default value is 30 minutes : see the code path at bottom of
                            // setScanResults() function.
                            mWifiConfigStore.
                                 setLastSelectedConfiguration(WifiConfiguration.INVALID_NETWORK_ID);
                        }
                        mAutoRoaming = roam;
                        if (isRoaming() || linkDebouncing) {
                            transitionTo(mRoamingState);
                        } else if (didDisconnect) {
                            transitionTo(mDisconnectingState);
                        } else {
                            /* Already in disconnected state, nothing to change */
                        }
                    } else {
                        loge("Failed to connect config: " + config + " netId: " + netId);
                        replyToMessage(message, WifiManager.CONNECT_NETWORK_FAILED,
                                WifiManager.ERROR);
                        break;
                    }
                    break;
                case WifiManager.CONNECT_NETWORK:
                    /**
                     *  The connect message can contain a network id passed as arg1 on message or
                     * or a config passed as obj on message.
                     * For a new network, a config is passed to create and connect.
                     * For an existing network, a network id is passed
                     */
                    netId = message.arg1;
                    config = (WifiConfiguration) message.obj;
                    mWifiConnectionStatistics.numWifiManagerJoinAttempt++;
                    boolean updatedExisting = false;

                    /* Save the network config */
                    if (config != null) {
                        String configKey = config.configKey(true /* allowCached */);
                        WifiConfiguration savedConfig =
                                mWifiConfigStore.getWifiConfiguration(configKey);
                        if (savedConfig != null) {
                            // There is an existing config with this netId, but it wasn't exposed
                            // (either AUTO_JOIN_DELETED or ephemeral; see WifiConfigStore#
                            // getConfiguredNetworks). Remove those bits and update the config.
                            config = savedConfig;
                            loge("CONNECT_NETWORK updating existing config with id=" +
                                    config.networkId + " configKey=" + configKey);
                            config.ephemeral = false;
                            config.autoJoinStatus = WifiConfiguration.AUTO_JOIN_ENABLED;
                            updatedExisting = true;
                        }

                        result = mWifiConfigStore.saveNetwork(config, message.sendingUid);
                        netId = result.getNetworkId();
                    }
                    config = mWifiConfigStore.getWifiConfiguration(netId);

                    if (config == null) {
                        loge("CONNECT_NETWORK id=" + Integer.toString(netId) + " "
                                + mSupplicantStateTracker.getSupplicantStateName() + " my state "
                                + getCurrentState().getName());
                    } else {
                        String wasSkipped = config.autoJoinBailedDueToLowRssi ? " skipped" : "";
                        loge("CONNECT_NETWORK id=" + Integer.toString(netId)
                                + " config=" + config.SSID
                                + " cnid=" + config.networkId
                                + " supstate=" + mSupplicantStateTracker.getSupplicantStateName()
                                + " my state " + getCurrentState().getName()
                                + " uid = " + message.sendingUid
                                + wasSkipped);
                    }

                    autoRoamSetBSSID(netId, "any");

                    if (message.sendingUid == Process.WIFI_UID
                        || message.sendingUid == Process.SYSTEM_UID) {
                        // As a sanity measure, clear the BSSID in the supplicant network block.
                        // If system or Wifi Settings want to connect, they will not
                        // specify the BSSID.
                        // If an app however had added a BSSID to this configuration, and the BSSID
                        // was wrong, Then we would forever fail to connect until that BSSID
                        // is cleaned up.
                        clearConfigBSSID(config, "CONNECT_NETWORK");
                    }

                    mAutoRoaming = WifiAutoJoinController.AUTO_JOIN_IDLE;

                    /* Tell autojoin the user did try to connect to that network */
                    mWifiAutoJoinController.updateConfigurationHistory(netId, true, true);

                    mWifiConfigStore.setLastSelectedConfiguration(netId);

                    didDisconnect = false;
                    if (mLastNetworkId != WifiConfiguration.INVALID_NETWORK_ID
                            && mLastNetworkId != netId) {
                        /** Supplicant will ignore the reconnect if we are currently associated,
                         * hence trigger a disconnect
                         */
                        didDisconnect = true;
                        mWifiNative.disconnect();
                    }

                    // Make sure the network is enabled, since supplicant will not reenable it
                    mWifiConfigStore.enableNetworkWithoutBroadcast(netId, false);

                    if (mWifiConfigStore.selectNetwork(netId) &&
                            mWifiNative.reconnect()) {
                        lastConnectAttempt = System.currentTimeMillis();
                        targetWificonfiguration = mWifiConfigStore.getWifiConfiguration(netId);

                        /* The state tracker handles enabling networks upon completion/failure */
                        mSupplicantStateTracker.sendMessage(WifiManager.CONNECT_NETWORK);
                        replyToMessage(message, WifiManager.CONNECT_NETWORK_SUCCEEDED);
                        if (didDisconnect) {
                            /* Expect a disconnection from the old connection */
                            transitionTo(mDisconnectingState);
                        } else if (updatedExisting && getCurrentState() == mConnectedState &&
                                getCurrentWifiConfiguration().networkId == netId) {
                            // Update the current set of network capabilities, but stay in the
                            // current state.
                            updateCapabilities(config);
                        } else {
                            /**
                             *  Directly go to disconnected state where we
                             * process the connection events from supplicant
                             **/
                            transitionTo(mDisconnectedState);
                        }
                    } else {
                        loge("Failed to connect config: " + config + " netId: " + netId);
                        replyToMessage(message, WifiManager.CONNECT_NETWORK_FAILED,
                                WifiManager.ERROR);
                        break;
                    }
                    break;
                case WifiManager.SAVE_NETWORK:
                    mWifiConnectionStatistics.numWifiManagerJoinAttempt++;
                    // Fall thru
                case WifiStateMachine.CMD_AUTO_SAVE_NETWORK:
                    lastSavedConfigurationAttempt = null; // Used for debug
                    config = (WifiConfiguration) message.obj;
                    if (config == null) {
                        loge("ERROR: SAVE_NETWORK with null configuration"
                                + mSupplicantStateTracker.getSupplicantStateName()
                                + " my state " + getCurrentState().getName());
                        messageHandlingStatus = MESSAGE_HANDLING_STATUS_FAIL;
                        replyToMessage(message, WifiManager.SAVE_NETWORK_FAILED,
                                WifiManager.ERROR);
                        break;
                    }
                    lastSavedConfigurationAttempt = new WifiConfiguration(config);
                    int nid = config.networkId;
                    loge("SAVE_NETWORK id=" + Integer.toString(nid)
                                + " config=" + config.SSID
                                + " nid=" + config.networkId
                                + " supstate=" + mSupplicantStateTracker.getSupplicantStateName()
                                + " my state " + getCurrentState().getName());

                    result = mWifiConfigStore.saveNetwork(config, -1);
                    if (result.getNetworkId() != WifiConfiguration.INVALID_NETWORK_ID) {
                        if (mWifiInfo.getNetworkId() == result.getNetworkId()) {
                            if (result.hasIpChanged()) {
                                // The currently connection configuration was changed
                                // We switched from DHCP to static or from static to DHCP, or the
                                // static IP address has changed.
                                log("Reconfiguring IP on connection");
                                // TODO: clear addresses and disable IPv6
                                // to simplify obtainingIpState.
                                transitionTo(mObtainingIpState);
                            }
                            if (result.hasProxyChanged()) {
                                log("Reconfiguring proxy on connection");
                                updateLinkProperties(CMD_UPDATE_LINKPROPERTIES);
                            }
                        }
                        replyToMessage(message, WifiManager.SAVE_NETWORK_SUCCEEDED);
                        if (VDBG) {
                           loge("Success save network nid="
                                        + Integer.toString(result.getNetworkId()));
                        }

                        synchronized(mScanResultCache) {
                            /**
                             * If the command comes from WifiManager, then
                             * tell autojoin the user did try to modify and save that network,
                             * and interpret the SAVE_NETWORK as a request to connect
                             */
                            boolean user = message.what == WifiManager.SAVE_NETWORK;
                            mWifiAutoJoinController.updateConfigurationHistory(result.getNetworkId()
                                    , user, true);
                            mWifiAutoJoinController.attemptAutoJoin();
                        }
                    } else {
                        loge("Failed to save network");
                        messageHandlingStatus = MESSAGE_HANDLING_STATUS_FAIL;
                        replyToMessage(message, WifiManager.SAVE_NETWORK_FAILED,
                                WifiManager.ERROR);
                    }
                    break;
                case WifiManager.FORGET_NETWORK:
                    // Debug only, remember last configuration that was forgotten
                    WifiConfiguration toRemove
                            = mWifiConfigStore.getWifiConfiguration(message.arg1);
                    if (toRemove == null) {
                        lastForgetConfigurationAttempt = null;
                    } else {
                        lastForgetConfigurationAttempt = new WifiConfiguration(toRemove);
                    }
                    if (mWifiConfigStore.forgetNetwork(message.arg1)) {
                        replyToMessage(message, WifiManager.FORGET_NETWORK_SUCCEEDED);
                    } else {
                        loge("Failed to forget network");
                        replyToMessage(message, WifiManager.FORGET_NETWORK_FAILED,
                                WifiManager.ERROR);
                    }
                    break;
                case WifiManager.START_WPS:
                    WpsInfo wpsInfo = (WpsInfo) message.obj;
                    WpsResult wpsResult;
                    switch (wpsInfo.setup) {
                        case WpsInfo.PBC:
                            wpsResult = mWifiConfigStore.startWpsPbc(wpsInfo);
                            break;
                        case WpsInfo.KEYPAD:
                            wpsResult = mWifiConfigStore.startWpsWithPinFromAccessPoint(wpsInfo);
                            break;
                        case WpsInfo.DISPLAY:
                            wpsResult = mWifiConfigStore.startWpsWithPinFromDevice(wpsInfo);
                            break;
                        default:
                            wpsResult = new WpsResult(Status.FAILURE);
                            loge("Invalid setup for WPS");
                            break;
                    }
                    mWifiConfigStore.setLastSelectedConfiguration
                            (WifiConfiguration.INVALID_NETWORK_ID);
                    if (wpsResult.status == Status.SUCCESS) {
                        replyToMessage(message, WifiManager.START_WPS_SUCCEEDED, wpsResult);
                        transitionTo(mWpsRunningState);
                    } else {
                        loge("Failed to start WPS with config " + wpsInfo.toString());
                        replyToMessage(message, WifiManager.WPS_FAILED, WifiManager.ERROR);
                    }
                    break;
                case WifiMonitor.NETWORK_CONNECTION_EVENT:
                    if (DBG) log("Network connection established");
                    mLastNetworkId = message.arg1;
                    mLastBssid = (String) message.obj;

                    mWifiInfo.setBSSID(mLastBssid);
                    mWifiInfo.setNetworkId(mLastNetworkId);

                    sendNetworkStateChangeBroadcast(mLastBssid);
                    transitionTo(mObtainingIpState);
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
                    if (DBG) log("ConnectModeState: Network connection lost ");
                    handleNetworkDisconnect();
                    transitionTo(mDisconnectedState);
                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }
    }

    private void updateCapabilities(WifiConfiguration config) {
        if (config.ephemeral) {
            mNetworkCapabilities.removeCapability(
                    NetworkCapabilities.NET_CAPABILITY_TRUSTED);
        } else {
            mNetworkCapabilities.addCapability(
                    NetworkCapabilities.NET_CAPABILITY_TRUSTED);
        }
        mNetworkAgent.sendNetworkCapabilities(mNetworkCapabilities);
    }

    private class WifiNetworkAgent extends NetworkAgent {
        public WifiNetworkAgent(Looper l, Context c, String TAG, NetworkInfo ni,
                NetworkCapabilities nc, LinkProperties lp, int score) {
            super(l, c, TAG, ni, nc, lp, score);
        }
        protected void unwanted() {
            // Ignore if we're not the current networkAgent.
            if (this != mNetworkAgent) return;
            if (DBG) log("WifiNetworkAgent -> Wifi unwanted score "
                    + Integer.toString(mWifiInfo.score));
            unwantedNetwork(network_status_unwanted_disconnect);
        }

        protected void networkStatus(int status) {
            if (status == NetworkAgent.INVALID_NETWORK) {
                if (DBG) log("WifiNetworkAgent -> Wifi networkStatus invalid, score="
                        + Integer.toString(mWifiInfo.score));
                unwantedNetwork(network_status_unwanted_disable_autojoin);
            } else if (status == NetworkAgent.VALID_NETWORK) {
                if (DBG && mWifiInfo != null) log("WifiNetworkAgent -> Wifi networkStatus valid, score= "
                        + Integer.toString(mWifiInfo.score));
                doNetworkStatus(status);
            }
        }
    }

    void unwantedNetwork(int reason) {
        sendMessage(CMD_UNWANTED_NETWORK, reason);
    }

    void doNetworkStatus(int status) {
        sendMessage(CMD_NETWORK_STATUS, status);
    }

    boolean startScanForConfiguration(WifiConfiguration config, boolean restrictChannelList) {
        if (config == null)
            return false;

        // We are still seeing a fairly high power consumption triggered by autojoin scans
        // Hence do partial scans only for PSK configuration that are roamable since the
        // primary purpose of the partial scans is roaming.
        // Full badn scans with exponential backoff for the purpose or extended roaming and
        // network switching are performed unconditionally.
        if (config.scanResultCache == null
                || !config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.WPA_PSK)
                || config.scanResultCache.size() > 6) {
            //return true but to not trigger the scan
            return true;
        }
        HashSet<Integer> channels
                = mWifiConfigStore.makeChannelList(config,
                ONE_HOUR_MILLI, restrictChannelList);
        if (channels != null && channels.size() != 0) {
            StringBuilder freqs = new StringBuilder();
            boolean first = true;
            for (Integer channel : channels) {
                if (!first)
                    freqs.append(",");
                freqs.append(channel.toString());
                first = false;
            }
            //if (DBG) {
            loge("WifiStateMachine starting scan for " + config.configKey() + " with " + freqs);
            //}
            // Call wifi native to start the scan
            if (startScanNative(
                    WifiNative.SCAN_WITHOUT_CONNECTION_SETUP,
                    freqs.toString())) {
                // Only count battery consumption if scan request is accepted
                noteScanStart(SCAN_ALARM_SOURCE, null);
                messageHandlingStatus = MESSAGE_HANDLING_STATUS_OK;
            } else {
                // used for debug only, mark scan as failed
                messageHandlingStatus = MESSAGE_HANDLING_STATUS_HANDLING_ERROR;
            }
            return true;
        } else {
            if (DBG) loge("WifiStateMachine no channels for " + config.configKey());
            return false;
        }
    }

    void clearCurrentConfigBSSID(String dbg) {
        // Clear the bssid in the current config's network block
        WifiConfiguration config = getCurrentWifiConfiguration();
        if (config == null)
            return;
        clearConfigBSSID(config, dbg);
    }
    void clearConfigBSSID(WifiConfiguration config, String dbg) {
        if (config == null)
            return;
        if (DBG) {
            loge(dbg + " " + mTargetRoamBSSID + " config " + config.configKey()
                    + " config.bssid " + config.BSSID);
        }
        config.autoJoinBSSID = "any";
        config.BSSID = "any";
        if (DBG) {
           loge(dbg + " " + config.SSID
                    + " nid=" + Integer.toString(config.networkId));
        }
        mWifiConfigStore.saveWifiConfigBSSID(config);
    }

    class L2ConnectedState extends State {
        @Override
        public void enter() {
            mRssiPollToken++;
            if (mEnableRssiPolling) {
                sendMessage(CMD_RSSI_POLL, mRssiPollToken, 0);
            }
            if (mNetworkAgent != null) {
                loge("Have NetworkAgent when entering L2Connected");
                setNetworkDetailedState(DetailedState.DISCONNECTED);
            }
            setNetworkDetailedState(DetailedState.CONNECTING);

            if (TextUtils.isEmpty(mTcpBufferSizes) == false) {
                mLinkProperties.setTcpBufferSizes(mTcpBufferSizes);
            }
            mNetworkAgent = new WifiNetworkAgent(getHandler().getLooper(), mContext,
                    "WifiNetworkAgent", mNetworkInfo, mNetworkCapabilitiesFilter,
                    mLinkProperties, 60);

            // We must clear the config BSSID, as the wifi chipset may decide to roam
            // from this point on and having the BSSID specified in the network block would
            // cause the roam to faile and the device to disconnect
            clearCurrentConfigBSSID("L2ConnectedState");
        }

        @Override
        public void exit() {
            // This is handled by receiving a NETWORK_DISCONNECTION_EVENT in ConnectModeState
            // Bug: 15347363
            // For paranoia's sake, call handleNetworkDisconnect
            // only if BSSID is null or last networkId
            // is not invalid.
            if (DBG) {
                StringBuilder sb = new StringBuilder();
                sb.append("leaving L2ConnectedState state nid=" + Integer.toString(mLastNetworkId));
                if (mLastBssid !=null) {
                    sb.append(" ").append(mLastBssid);
                }
            }
            if (mLastBssid != null || mLastNetworkId != WifiConfiguration.INVALID_NETWORK_ID) {
                handleNetworkDisconnect();
            }
        }

        @Override
        public boolean processMessage(Message message) {
            logStateAndMessage(message, getClass().getSimpleName());

            switch (message.what) {
              case DhcpStateMachine.CMD_PRE_DHCP_ACTION:
                  handlePreDhcpSetup();
                  break;
              case DhcpStateMachine.CMD_POST_DHCP_ACTION:
                  handlePostDhcpSetup();
                  if (message.arg1 == DhcpStateMachine.DHCP_SUCCESS) {
                      if (DBG) log("WifiStateMachine DHCP successful");
                      handleIPv4Success((DhcpResults) message.obj, DhcpStateMachine.DHCP_SUCCESS);
                      // We advance to mVerifyingLinkState because handleIPv4Success will call
                      // updateLinkProperties, which then sends CMD_IP_CONFIGURATION_SUCCESSFUL.
                  } else if (message.arg1 == DhcpStateMachine.DHCP_FAILURE) {
                      if (DBG) {
                          int count = -1;
                          WifiConfiguration config = getCurrentWifiConfiguration();
                          if (config != null) {
                              count = config.numConnectionFailures;
                          }
                          log("WifiStateMachine DHCP failure count=" + count);
                      }
                      handleIPv4Failure(DhcpStateMachine.DHCP_FAILURE);
                      // As above, we transition to mDisconnectingState via updateLinkProperties.
                  }
                  break;
                case CMD_IP_CONFIGURATION_SUCCESSFUL:
                    handleSuccessfulIpConfiguration();
                    sendConnectedState();
                    transitionTo(mConnectedState);
                    break;
                case CMD_IP_CONFIGURATION_LOST:
                    // Get Link layer stats so as we get fresh tx packet counters
                    getWifiLinkLayerStats(true);
                    handleIpConfigurationLost();
                    transitionTo(mDisconnectingState);
                    break;
                case CMD_DISCONNECT:
                    mWifiNative.disconnect();
                    transitionTo(mDisconnectingState);
                    break;
                case WifiP2pServiceImpl.DISCONNECT_WIFI_REQUEST:
                    if (message.arg1 == 1) {
                        mWifiNative.disconnect();
                        mTemporarilyDisconnectWifi = true;
                        transitionTo(mDisconnectingState);
                    }
                    break;
                case CMD_SET_OPERATIONAL_MODE:
                    if (message.arg1 != CONNECT_MODE) {
                        sendMessage(CMD_DISCONNECT);
                        deferMessage(message);
                        if (message.arg1 == SCAN_ONLY_WITH_WIFI_OFF_MODE) {
                            noteWifiDisabledWhileAssociated();
                        }
                    }
                    mWifiConfigStore.
                                setLastSelectedConfiguration(WifiConfiguration.INVALID_NETWORK_ID);
                    break;
                case CMD_SET_COUNTRY_CODE:
                    messageHandlingStatus = MESSAGE_HANDLING_STATUS_DEFERRED;
                    deferMessage(message);
                    break;
                case CMD_START_SCAN:
                    //if (DBG) {
                        loge("WifiStateMachine CMD_START_SCAN source " + message.arg1
                              + " txSuccessRate="+String.format( "%.2f", mWifiInfo.txSuccessRate)
                              + " rxSuccessRate="+String.format( "%.2f", mWifiInfo.rxSuccessRate)
                              + " targetRoamBSSID=" + mTargetRoamBSSID
                              + " RSSI=" + mWifiInfo.getRssi());
                    //}
                    if (message.arg1 == SCAN_ALARM_SOURCE) {
                        // Check if the CMD_START_SCAN message is obsolete (and thus if it should
                        // not be processed) and restart the scan if needed
                        boolean shouldScan =
                                mScreenOn && mWifiConfigStore.enableAutoJoinScanWhenAssociated;
                        if (!checkAndRestartDelayedScan(message.arg2,
                                shouldScan,
                                mWifiConfigStore.associatedPartialScanPeriodMilli, null, null)) {
                            messageHandlingStatus = MESSAGE_HANDLING_STATUS_OBSOLETE;
                            loge("WifiStateMachine L2Connected CMD_START_SCAN source "
                                    + message.arg1
                                    + " " + message.arg2 + ", " + mDelayedScanCounter
                                    + " -> obsolete");
                            return HANDLED;
                        }
                        if (mP2pConnected.get()) {
                            loge("WifiStateMachine L2Connected CMD_START_SCAN source "
                                    + message.arg1
                                    + " " + message.arg2 + ", " + mDelayedScanCounter
                                    + " ignore because P2P is connected");
                            messageHandlingStatus = MESSAGE_HANDLING_STATUS_DISCARD;
                            return HANDLED;
                        }
                        boolean tryFullBandScan = false;
                        boolean restrictChannelList = false;
                        long now_ms = System.currentTimeMillis();
                        if (DBG) {
                            loge("WifiStateMachine CMD_START_SCAN with age="
                                    + Long.toString(now_ms - lastFullBandConnectedTimeMilli)
                                    + " interval=" + fullBandConnectedTimeIntervalMilli
                                    + " maxinterval=" + maxFullBandConnectedTimeIntervalMilli);
                        }
                        if (mWifiInfo != null) {
                            if (mWifiConfigStore.enableFullBandScanWhenAssociated &&
                                    (now_ms - lastFullBandConnectedTimeMilli)
                                    > fullBandConnectedTimeIntervalMilli) {
                                if (DBG) {
                                    loge("WifiStateMachine CMD_START_SCAN try full band scan age="
                                         + Long.toString(now_ms - lastFullBandConnectedTimeMilli)
                                         + " interval=" + fullBandConnectedTimeIntervalMilli
                                         + " maxinterval=" + maxFullBandConnectedTimeIntervalMilli);
                                }
                                tryFullBandScan = true;
                            }

                            if (mWifiInfo.txSuccessRate >
                                    mWifiConfigStore.maxTxPacketForFullScans
                                    || mWifiInfo.rxSuccessRate >
                                    mWifiConfigStore.maxRxPacketForFullScans) {
                                // Too much traffic at the interface, hence no full band scan
                                if (DBG) {
                                    loge("WifiStateMachine CMD_START_SCAN " +
                                            "prevent full band scan due to pkt rate");
                                }
                                tryFullBandScan = false;
                            }

                            if (mWifiInfo.txSuccessRate >
                                    mWifiConfigStore.maxTxPacketForPartialScans
                                    || mWifiInfo.rxSuccessRate >
                                    mWifiConfigStore.maxRxPacketForPartialScans) {
                                // Don't scan if lots of packets are being sent
                                restrictChannelList = true;
                                if (mWifiConfigStore.alwaysEnableScansWhileAssociated == 0) {
                                    if (DBG) {
                                     loge("WifiStateMachine CMD_START_SCAN source " + message.arg1
                                        + " ...and ignore scans"
                                        + " tx=" + String.format("%.2f", mWifiInfo.txSuccessRate)
                                        + " rx=" + String.format("%.2f", mWifiInfo.rxSuccessRate));
                                    }
                                    messageHandlingStatus = MESSAGE_HANDLING_STATUS_REFUSED;
                                    return HANDLED;
                                }
                            }
                        }

                        WifiConfiguration currentConfiguration = getCurrentWifiConfiguration();
                        if (DBG) {
                            loge("WifiStateMachine CMD_START_SCAN full=" +
                                    tryFullBandScan);
                        }
                        if (currentConfiguration != null) {
                            if (fullBandConnectedTimeIntervalMilli
                                    < mWifiConfigStore.associatedPartialScanPeriodMilli) {
                                // Sanity
                                fullBandConnectedTimeIntervalMilli
                                        = mWifiConfigStore.associatedPartialScanPeriodMilli;
                            }
                            if (tryFullBandScan) {
                                lastFullBandConnectedTimeMilli = now_ms;
                                if (fullBandConnectedTimeIntervalMilli
                                        < mWifiConfigStore.associatedFullScanMaxIntervalMilli) {
                                    // Increase the interval
                                    fullBandConnectedTimeIntervalMilli
                                            = fullBandConnectedTimeIntervalMilli
                                            * mWifiConfigStore.associatedFullScanBackoff / 8;

                                    if (DBG) {
                                        loge("WifiStateMachine CMD_START_SCAN bump interval ="
                                        + fullBandConnectedTimeIntervalMilli);
                                    }
                                }
                                handleScanRequest(
                                        WifiNative.SCAN_WITHOUT_CONNECTION_SETUP, message);
                            } else {
                                if (!startScanForConfiguration(
                                        currentConfiguration, restrictChannelList)) {
                                    if (DBG) {
                                        loge("WifiStateMachine starting scan, " +
                                                " did not find channels -> full");
                                    }
                                    lastFullBandConnectedTimeMilli = now_ms;
                                    if (fullBandConnectedTimeIntervalMilli
                                            < mWifiConfigStore.associatedFullScanMaxIntervalMilli) {
                                        // Increase the interval
                                        fullBandConnectedTimeIntervalMilli
                                                = fullBandConnectedTimeIntervalMilli
                                                * mWifiConfigStore.associatedFullScanBackoff / 8;

                                        if (DBG) {
                                            loge("WifiStateMachine CMD_START_SCAN bump interval ="
                                                    + fullBandConnectedTimeIntervalMilli);
                                        }
                                    }
                                    handleScanRequest(
                                                WifiNative.SCAN_WITHOUT_CONNECTION_SETUP, message);
                                }
                            }

                        } else {
                            loge("CMD_START_SCAN : connected mode and no configuration");
                            messageHandlingStatus = MESSAGE_HANDLING_STATUS_HANDLING_ERROR;
                        }
                    } else {
                        // Not scan alarm source
                        return NOT_HANDLED;
                    }
                    break;
                    /* Ignore connection to same network */
                case WifiManager.CONNECT_NETWORK:
                    int netId = message.arg1;
                    if (mWifiInfo.getNetworkId() == netId) {
                        break;
                    }
                    return NOT_HANDLED;
                    /* Ignore */
                case WifiMonitor.NETWORK_CONNECTION_EVENT:
                    break;
                case CMD_RSSI_POLL:
                    if (message.arg1 == mRssiPollToken) {
                        if (mWifiConfigStore.enableChipWakeUpWhenAssociated) {
                            if (VVDBG) log(" get link layer stats " + mWifiLinkLayerStatsSupported);
                            WifiLinkLayerStats stats = getWifiLinkLayerStats(VDBG);
                            if (stats != null) {
                                // Sanity check the results provided by driver
                                if (mWifiInfo.getRssi() != WifiInfo.INVALID_RSSI
                                        && (stats.rssi_mgmt == 0
                                        || stats.beacon_rx == 0)) {
                                    stats = null;
                                }
                            }
                            // Get Info and continue polling
                            fetchRssiLinkSpeedAndFrequencyNative();
                            calculateWifiScore(stats);
                        }
                        sendMessageDelayed(obtainMessage(CMD_RSSI_POLL,
                                mRssiPollToken, 0), POLL_RSSI_INTERVAL_MSECS);

                        if (DBG) sendRssiChangeBroadcast(mWifiInfo.getRssi());
                    } else {
                        // Polling has completed
                    }
                    break;
                case CMD_ENABLE_RSSI_POLL:
                    if (mWifiConfigStore.enableRssiPollWhenAssociated) {
                        mEnableRssiPolling = (message.arg1 == 1);
                    } else {
                        mEnableRssiPolling = false;
                    }
                    mRssiPollToken++;
                    if (mEnableRssiPolling) {
                        // First poll
                        fetchRssiLinkSpeedAndFrequencyNative();
                        sendMessageDelayed(obtainMessage(CMD_RSSI_POLL,
                                mRssiPollToken, 0), POLL_RSSI_INTERVAL_MSECS);
                    } else {
                        cleanWifiScore();
                    }
                    break;
                case WifiManager.RSSI_PKTCNT_FETCH:
                    RssiPacketCountInfo info = new RssiPacketCountInfo();
                    fetchRssiLinkSpeedAndFrequencyNative();
                    info.rssi = mWifiInfo.getRssi();
                    fetchPktcntNative(info);
                    replyToMessage(message, WifiManager.RSSI_PKTCNT_FETCH_SUCCEEDED, info);
                    break;
                case CMD_DELAYED_NETWORK_DISCONNECT:
                    if (!linkDebouncing && mWifiConfigStore.enableLinkDebouncing) {

                        // Ignore if we are not debouncing
                        loge("CMD_DELAYED_NETWORK_DISCONNECT and not debouncing - ignore "
                                + message.arg1);
                        return HANDLED;
                    } else {
                        loge("CMD_DELAYED_NETWORK_DISCONNECT and debouncing - disconnect "
                                + message.arg1);

                        linkDebouncing = false;
                        // If we are still debouncing while this message comes,
                        // it means we were not able to reconnect within the alloted time
                        // = LINK_FLAPPING_DEBOUNCE_MSEC
                        // and thus, trigger a real disconnect
                        handleNetworkDisconnect();
                        transitionTo(mDisconnectedState);
                    }
                    break;
                case CMD_ASSOCIATED_BSSID:
                    if ((String) message.obj == null) {
                        loge("Associated command w/o BSSID");
                        break;
                    }
                    mLastBssid = (String) message.obj;
                    mWifiInfo.setBSSID((String) message.obj);
                    break;
                default:
                    return NOT_HANDLED;
            }

            return HANDLED;
        }
    }

    class ObtainingIpState extends State {
        @Override
        public void enter() {
            if (DBG) {
                String key = "";
                if (getCurrentWifiConfiguration() != null) {
                    key = getCurrentWifiConfiguration().configKey();
                }
                log("enter ObtainingIpState netId=" + Integer.toString(mLastNetworkId)
                        + " " + key + " "
                        + " roam=" + mAutoRoaming
                        + " static=" + mWifiConfigStore.isUsingStaticIp(mLastNetworkId)
                        + " watchdog= " + obtainingIpWatchdogCount);
            }

            // Reset link Debouncing, indicating we have successfully re-connected to the AP
            // We might still be roaming
            linkDebouncing = false;

            // Send event to CM & network change broadcast
            setNetworkDetailedState(DetailedState.OBTAINING_IPADDR);

            // We must clear the config BSSID, as the wifi chipset may decide to roam
            // from this point on and having the BSSID specified in the network block would
            // cause the roam to faile and the device to disconnect
            clearCurrentConfigBSSID("ObtainingIpAddress");

            try {
                mNwService.enableIpv6(mInterfaceName);
            } catch (RemoteException re) {
                loge("Failed to enable IPv6: " + re);
            } catch (IllegalStateException e) {
                loge("Failed to enable IPv6: " + e);
            }

            if (!mWifiConfigStore.isUsingStaticIp(mLastNetworkId)) {
                if (isRoaming()) {
                    renewDhcp();
                } else {
                    // Remove any IP address on the interface in case we're switching from static
                    // IP configuration to DHCP. This is safe because if we get here when not
                    // roaming, we don't have a usable address.
                    clearIPv4Address(mInterfaceName);
                    startDhcp();
                }
                obtainingIpWatchdogCount++;
                loge("Start Dhcp Watchdog " + obtainingIpWatchdogCount);
                // Get Link layer stats so as we get fresh tx packet counters
                getWifiLinkLayerStats(true);
                sendMessageDelayed(obtainMessage(CMD_OBTAINING_IP_ADDRESS_WATCHDOG_TIMER,
                        obtainingIpWatchdogCount, 0), OBTAINING_IP_ADDRESS_GUARD_TIMER_MSEC);
            } else {
                // stop any running dhcp before assigning static IP
                stopDhcp();
                StaticIpConfiguration config = mWifiConfigStore.getStaticIpConfiguration(
                        mLastNetworkId);
                if (config.ipAddress == null) {
                    loge("Static IP lacks address");
                    sendMessage(CMD_STATIC_IP_FAILURE);
                } else {
                    InterfaceConfiguration ifcg = new InterfaceConfiguration();
                    ifcg.setLinkAddress(config.ipAddress);
                    ifcg.setInterfaceUp();
                    try {
                        mNwService.setInterfaceConfig(mInterfaceName, ifcg);
                        if (DBG) log("Static IP configuration succeeded");
                        DhcpResults dhcpResults = new DhcpResults(config);
                        sendMessage(CMD_STATIC_IP_SUCCESS, dhcpResults);
                    } catch (RemoteException re) {
                        loge("Static IP configuration failed: " + re);
                        sendMessage(CMD_STATIC_IP_FAILURE);
                    } catch (IllegalStateException e) {
                        loge("Static IP configuration failed: " + e);
                        sendMessage(CMD_STATIC_IP_FAILURE);
                    }
                }
            }
        }
      @Override
      public boolean processMessage(Message message) {
          logStateAndMessage(message, getClass().getSimpleName());

          switch(message.what) {
              case CMD_STATIC_IP_SUCCESS:
                  handleIPv4Success((DhcpResults) message.obj, CMD_STATIC_IP_SUCCESS);
                  break;
              case CMD_STATIC_IP_FAILURE:
                  handleIPv4Failure(CMD_STATIC_IP_FAILURE);
                  break;
              case CMD_AUTO_CONNECT:
              case CMD_AUTO_ROAM:
                  messageHandlingStatus = MESSAGE_HANDLING_STATUS_DISCARD;
                  break;
              case WifiManager.SAVE_NETWORK:
              case WifiStateMachine.CMD_AUTO_SAVE_NETWORK:
                  messageHandlingStatus = MESSAGE_HANDLING_STATUS_DEFERRED;
                  deferMessage(message);
                  break;
                  /* Defer any power mode changes since we must keep active power mode at DHCP */
              case CMD_SET_HIGH_PERF_MODE:
                  messageHandlingStatus = MESSAGE_HANDLING_STATUS_DEFERRED;
                  deferMessage(message);
                  break;
                  /* Defer scan request since we should not switch to other channels at DHCP */
              case CMD_START_SCAN:
                  messageHandlingStatus = MESSAGE_HANDLING_STATUS_DEFERRED;
                  deferMessage(message);
                  break;
              case CMD_OBTAINING_IP_ADDRESS_WATCHDOG_TIMER:
                  if (message.arg1 == obtainingIpWatchdogCount) {
                      loge("ObtainingIpAddress: Watchdog Triggered, count="
                              + obtainingIpWatchdogCount);
                      handleIpConfigurationLost();
                      transitionTo(mDisconnectingState);
                      break;
                  }
                  messageHandlingStatus = MESSAGE_HANDLING_STATUS_DISCARD;
                  break;
              default:
                  return NOT_HANDLED;
          }
          return HANDLED;
      }
    }

    class VerifyingLinkState extends State {
        @Override
        public void enter() {
            log(getName() + " enter");
            setNetworkDetailedState(DetailedState.VERIFYING_POOR_LINK);
            mWifiConfigStore.updateStatus(mLastNetworkId, DetailedState.VERIFYING_POOR_LINK);
            sendNetworkStateChangeBroadcast(mLastBssid);
            // End roaming
            mAutoRoaming = WifiAutoJoinController.AUTO_JOIN_IDLE;
        }
        @Override
        public boolean processMessage(Message message) {
            logStateAndMessage(message, getClass().getSimpleName());

            switch (message.what) {
                case WifiWatchdogStateMachine.POOR_LINK_DETECTED:
                    // Stay here
                    log(getName() + " POOR_LINK_DETECTED: no transition");
                    break;
                case WifiWatchdogStateMachine.GOOD_LINK_DETECTED:
                    log(getName() + " GOOD_LINK_DETECTED: transition to captive portal check");

                    log(getName() + " GOOD_LINK_DETECTED: transition to CONNECTED");
                    sendConnectedState();
                    transitionTo(mConnectedState);
                    break;
                default:
                    if (DBG) log(getName() + " what=" + message.what + " NOT_HANDLED");
                    return NOT_HANDLED;
            }
            return HANDLED;
        }
    }

    private void sendConnectedState() {
        // Send out a broadcast with the CAPTIVE_PORTAL_CHECK to preserve
        // existing behaviour. The captive portal check really happens after we
        // transition into DetailedState.CONNECTED.
        setNetworkDetailedState(DetailedState.CAPTIVE_PORTAL_CHECK);
        mWifiConfigStore.updateStatus(mLastNetworkId,
        DetailedState.CAPTIVE_PORTAL_CHECK);
        sendNetworkStateChangeBroadcast(mLastBssid);

        if (mWifiConfigStore.getLastSelectedConfiguration() != null) {
            if (mNetworkAgent != null) mNetworkAgent.explicitlySelected();
        }

        setNetworkDetailedState(DetailedState.CONNECTED);
        mWifiConfigStore.updateStatus(mLastNetworkId, DetailedState.CONNECTED);
        sendNetworkStateChangeBroadcast(mLastBssid);
    }

    class RoamingState extends State {
        boolean mAssociated;
        @Override
        public void enter() {
            if (DBG) {
                log("RoamingState Enter"
                        + " mScreenOn=" + mScreenOn );
            }
            setScanAlarm(false);

            // Make sure we disconnect if roaming fails
            roamWatchdogCount++;
            loge("Start Roam Watchdog " + roamWatchdogCount);
            sendMessageDelayed(obtainMessage(CMD_ROAM_WATCHDOG_TIMER,
                    roamWatchdogCount, 0), ROAM_GUARD_TIMER_MSEC);
            mAssociated = false;
        }
        @Override
        public boolean processMessage(Message message) {
            logStateAndMessage(message, getClass().getSimpleName());
            WifiConfiguration config;
            switch (message.what) {
                case CMD_IP_CONFIGURATION_LOST:
                    config = getCurrentWifiConfiguration();
                    if (config != null) {
                        mWifiConfigStore.noteRoamingFailure(config,
                                WifiConfiguration.ROAMING_FAILURE_IP_CONFIG);
                    }
                    return NOT_HANDLED;
               case WifiWatchdogStateMachine.POOR_LINK_DETECTED:
                    if (DBG) log("Roaming and Watchdog reports poor link -> ignore");
                    return HANDLED;
               case CMD_UNWANTED_NETWORK:
                    if (DBG) log("Roaming and CS doesnt want the network -> ignore");
                    return HANDLED;
               case CMD_SET_OPERATIONAL_MODE:
                    if (message.arg1 != CONNECT_MODE) {
                        deferMessage(message);
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
                        if (DBG) {
                            log("STATE_CHANGE_EVENT in roaming state "
                                    + stateChangeResult.toString() );
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
                            mTargetRoamBSSID = (String) stateChangeResult.BSSID;
                        }
                    }
                    break;
                case CMD_ROAM_WATCHDOG_TIMER:
                    if (roamWatchdogCount == message.arg1) {
                        if (DBG) log("roaming watchdog! -> disconnect");
                        mRoamFailCount++;
                        handleNetworkDisconnect();
                        mWifiNative.disconnect();
                        transitionTo(mDisconnectedState);
                    }
                    break;
               case WifiMonitor.NETWORK_CONNECTION_EVENT:
                   if (mAssociated) {
                       if (DBG) log("roaming and Network connection established");
                       mLastNetworkId = message.arg1;
                       mLastBssid = (String) message.obj;
                       mWifiInfo.setBSSID(mLastBssid);
                       mWifiInfo.setNetworkId(mLastNetworkId);
                       mWifiConfigStore.handleBSSIDBlackList(mLastNetworkId, mLastBssid, true);
                       transitionTo(mObtainingIpState);
                   } else {
                       messageHandlingStatus = MESSAGE_HANDLING_STATUS_DISCARD;
                   }
                   break;
               case WifiMonitor.NETWORK_DISCONNECTION_EVENT:
                   // Throw away but only if it corresponds to the network we're roaming to
                   String bssid = (String)message.obj;
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
                case WifiMonitor.SSID_TEMP_DISABLED:
                    // Auth error while roaming
                    loge("SSID_TEMP_DISABLED nid=" + Integer.toString(mLastNetworkId)
                            + " id=" + Integer.toString(message.arg1)
                            + " isRoaming=" + isRoaming()
                            + " roam=" + Integer.toString(mAutoRoaming));
                    if (message.arg1 == mLastNetworkId) {
                        config = getCurrentWifiConfiguration();
                        if (config != null) {
                            mWifiConfigStore.noteRoamingFailure(config,
                                    WifiConfiguration.ROAMING_FAILURE_AUTH_FAILURE);
                        }
                        handleNetworkDisconnect();
                        transitionTo(mDisconnectingState);
                    }
                    return NOT_HANDLED;
                case CMD_START_SCAN:
                    deferMessage(message);
                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }

        @Override
        public void exit() {
            loge("WifiStateMachine: Leaving Roaming state");
        }
    }

    class ConnectedState extends State {
        @Override
        public void enter() {
            String address;
            updateDefaultRouteMacAddress(1000);
            if (DBG) {
                log("ConnectedState Enter "
                        + " mScreenOn=" + mScreenOn
                        + " scanperiod="
                        + Integer.toString(mWifiConfigStore.associatedPartialScanPeriodMilli) );
            }
            if (mScreenOn
                    && mWifiConfigStore.enableAutoJoinScanWhenAssociated) {
                // restart scan alarm
                startDelayedScan(mWifiConfigStore.associatedPartialScanPeriodMilli, null, null);
            }
            registerConnected();
            lastConnectAttempt = 0;
            targetWificonfiguration = null;
            // Paranoia
            linkDebouncing = false;

            // Not roaming anymore
            mAutoRoaming = WifiAutoJoinController.AUTO_JOIN_IDLE;

            if (testNetworkDisconnect) {
                testNetworkDisconnectCounter++;
                loge("ConnectedState Enter start disconnect test " +
                        testNetworkDisconnectCounter);
                sendMessageDelayed(obtainMessage(CMD_TEST_NETWORK_DISCONNECT,
                        testNetworkDisconnectCounter, 0), 15000);
            }

            // Reenable all networks, allow for hidden networks to be scanned
            mWifiConfigStore.enableAllNetworks();

            mLastDriverRoamAttempt = 0;
        }
        @Override
        public boolean processMessage(Message message) {
            WifiConfiguration config = null;
            logStateAndMessage(message, getClass().getSimpleName());

            switch (message.what) {
                case WifiWatchdogStateMachine.POOR_LINK_DETECTED:
                    if (DBG) log("Watchdog reports poor link");
                    transitionTo(mVerifyingLinkState);
                    break;
                case CMD_UNWANTED_NETWORK:
                    if (message.arg1 == network_status_unwanted_disconnect) {
                        mWifiConfigStore.handleBadNetworkDisconnectReport(mLastNetworkId, mWifiInfo);
                        mWifiNative.disconnect();
                        transitionTo(mDisconnectingState);
                    } else if (message.arg1 == network_status_unwanted_disable_autojoin) {
                        config = getCurrentWifiConfiguration();
                        if (config != null) {
                            // Disable autojoin
                            config.numNoInternetAccessReports += 1;
                        }
                    }
                    return HANDLED;
                case CMD_NETWORK_STATUS:
                    if (message.arg1 == NetworkAgent.VALID_NETWORK) {
                        config = getCurrentWifiConfiguration();
                        if (config != null) {
                            // re-enable autojoin
                            config.numNoInternetAccessReports = 0;
                            config.validatedInternetAccess = true;
                        }
                    }
                    return HANDLED;
                case CMD_TEST_NETWORK_DISCONNECT:
                    // Force a disconnect
                    if (message.arg1 == testNetworkDisconnectCounter) {
                        mWifiNative.disconnect();
                    }
                    break;
                case CMD_ASSOCIATED_BSSID:
                    // ASSOCIATING to a new BSSID while already connected, indicates
                    // that driver is roaming
                    mLastDriverRoamAttempt = System.currentTimeMillis();
                    String toBSSID = (String)message.obj;
                    if (toBSSID != null && !toBSSID.equals(mWifiInfo.getBSSID())) {
                        mWifiConfigStore.driverRoamedFrom(mWifiInfo);
                    }
                    return NOT_HANDLED;
                case WifiMonitor.NETWORK_DISCONNECTION_EVENT:
                    long lastRoam = 0;
                    if (mLastDriverRoamAttempt != 0) {
                        // Calculate time since last driver roam attempt
                        lastRoam = System.currentTimeMillis() - mLastDriverRoamAttempt;
                        mLastDriverRoamAttempt = 0;
                    }
                    config = getCurrentWifiConfiguration();
                    if (mScreenOn
                            && !linkDebouncing
                            && config != null
                            && config.autoJoinStatus == WifiConfiguration.AUTO_JOIN_ENABLED
                            && !mWifiConfigStore.isLastSelectedConfiguration(config)
                            && (message.arg2 != 3 /* reason cannot be 3, i.e. locally generated */
                                || (lastRoam > 0 && lastRoam < 2000) /* unless driver is roaming */)
                            && ((ScanResult.is24GHz(mWifiInfo.getFrequency())
                                    && mWifiInfo.getRssi() >
                                    WifiConfiguration.BAD_RSSI_24)
                                    || (ScanResult.is5GHz(mWifiInfo.getFrequency())
                                    && mWifiInfo.getRssi() >
                                    WifiConfiguration.BAD_RSSI_5))) {
                        // Start de-bouncing the L2 disconnection:
                        // this L2 disconnection might be spurious.
                        // Hence we allow 7 seconds for the state machine to try
                        // to reconnect, go thru the
                        // roaming cycle and enter Obtaining IP address
                        // before signalling the disconnect to ConnectivityService and L3
                        startScanForConfiguration(getCurrentWifiConfiguration(), false);
                        linkDebouncing = true;

                        sendMessageDelayed(obtainMessage(CMD_DELAYED_NETWORK_DISCONNECT,
                                0, mLastNetworkId), LINK_FLAPPING_DEBOUNCE_MSEC);
                        if (DBG) {
                            log("NETWORK_DISCONNECTION_EVENT in connected state"
                                    + " BSSID=" + mWifiInfo.getBSSID()
                                    + " RSSI=" + mWifiInfo.getRssi()
                                    + " freq=" + mWifiInfo.getFrequency()
                                    + " reason=" + message.arg2
                                    + " -> debounce");
                        }
                        return HANDLED;
                    } else {
                        if (DBG) {
                            int ajst = -1;
                            if (config != null) ajst = config.autoJoinStatus;
                            log("NETWORK_DISCONNECTION_EVENT in connected state"
                                    + " BSSID=" + mWifiInfo.getBSSID()
                                    + " RSSI=" + mWifiInfo.getRssi()
                                    + " freq=" + mWifiInfo.getFrequency()
                                    + " was debouncing=" + linkDebouncing
                                    + " reason=" + message.arg2
                                    + " ajst=" + ajst);
                        }
                    }
                    break;
                case CMD_AUTO_ROAM:
                    // Clear the driver roam indication since we are attempting a framerwork roam
                    mLastDriverRoamAttempt = 0;

                    /* Connect command coming from auto-join */
                    ScanResult candidate = (ScanResult)message.obj;
                    String bssid = "any";
                    if (candidate != null && candidate.is5GHz()) {
                        // Only lock BSSID for 5GHz networks
                        bssid = candidate.BSSID;
                    }
                    int netId = mLastNetworkId;
                    config = getCurrentWifiConfiguration();


                    if (config == null) {
                        loge("AUTO_ROAM and no config, bail out...");
                        break;
                    }

                    loge("CMD_AUTO_ROAM sup state "
                            + mSupplicantStateTracker.getSupplicantStateName()
                            + " my state " + getCurrentState().getName()
                            + " nid=" + Integer.toString(netId)
                            + " config " + config.configKey()
                            + " roam=" + Integer.toString(message.arg2)
                            + " to " + bssid
                            + " targetRoamBSSID " + mTargetRoamBSSID);

                    /* Save the BSSID so as to lock it @ firmware */
                    if (!autoRoamSetBSSID(config, bssid) && !linkDebouncing) {
                        loge("AUTO_ROAM nothing to do");
                        // Same BSSID, nothing to do
                        messageHandlingStatus = MESSAGE_HANDLING_STATUS_DISCARD;
                        break;
                    };

                    // Make sure the network is enabled, since supplicant will not reenable it
                    mWifiConfigStore.enableNetworkWithoutBroadcast(netId, false);

                    boolean ret = false;
                    if (mLastNetworkId != netId) {
                       if (mWifiConfigStore.selectNetwork(netId) &&
                           mWifiNative.reconnect()) {
                           ret = true;
                       }
                    } else {
                         ret = mWifiNative.reassociate();
                    }
                    if (ret) {
                        lastConnectAttempt = System.currentTimeMillis();
                        targetWificonfiguration = mWifiConfigStore.getWifiConfiguration(netId);

                        // replyToMessage(message, WifiManager.CONNECT_NETWORK_SUCCEEDED);
                        mAutoRoaming = message.arg2;
                        transitionTo(mRoamingState);

                    } else {
                        loge("Failed to connect config: " + config + " netId: " + netId);
                        replyToMessage(message, WifiManager.CONNECT_NETWORK_FAILED,
                                WifiManager.ERROR);
                        messageHandlingStatus = MESSAGE_HANDLING_STATUS_FAIL;
                        break;
                    }
                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }

        @Override
        public void exit() {
            loge("WifiStateMachine: Leaving Connected state");
            setScanAlarm(false);
            mLastDriverRoamAttempt = 0;
        }
    }

    class DisconnectingState extends State {

        @Override
        public void enter() {

            if (PDBG) {
                loge(" Enter DisconnectingState State scan interval " + mFrameworkScanIntervalMs
                        + " mEnableBackgroundScan= " + mEnableBackgroundScan
                        + " screenOn=" + mScreenOn);
            }

            // Make sure we disconnect: we enter this state prior connecting to a new
            // network, waiting for either a DISCONECT event or a SUPPLICANT_STATE_CHANGE
            // event which in this case will be indicating that supplicant started to associate.
            // In some cases supplicant doesn't ignore the connect requests (it might not
            // find the target SSID in its cache),
            // Therefore we end up stuck that state, hence the need for the watchdog.
            disconnectingWatchdogCount++;
            loge("Start Disconnecting Watchdog " + disconnectingWatchdogCount);
            sendMessageDelayed(obtainMessage(CMD_DISCONNECTING_WATCHDOG_TIMER,
                    disconnectingWatchdogCount, 0), DISCONNECTING_GUARD_TIMER_MSEC);
        }

        @Override
        public boolean processMessage(Message message) {
            logStateAndMessage(message, getClass().getSimpleName());
            switch (message.what) {
                case CMD_SET_OPERATIONAL_MODE:
                    if (message.arg1 != CONNECT_MODE) {
                        deferMessage(message);
                    }
                    break;
                case CMD_START_SCAN:
                    deferMessage(message);
                    return HANDLED;
                case CMD_DISCONNECTING_WATCHDOG_TIMER:
                    if (disconnectingWatchdogCount == message.arg1) {
                        if (DBG) log("disconnecting watchdog! -> disconnect");
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
                    return NOT_HANDLED;
            }
            return HANDLED;
        }
    }

    class DisconnectedState extends State {
        @Override
        public void enter() {
            // We dont scan frequently if this is a temporary disconnect
            // due to p2p
            if (mTemporarilyDisconnectWifi) {
                mWifiP2pChannel.sendMessage(WifiP2pServiceImpl.DISCONNECT_WIFI_RESPONSE);
                return;
            }

            mFrameworkScanIntervalMs = Settings.Global.getLong(mContext.getContentResolver(),
                    Settings.Global.WIFI_FRAMEWORK_SCAN_INTERVAL_MS,
                    mDefaultFrameworkScanIntervalMs);

            if (PDBG) {
                loge(" Enter disconnected State scan interval " + mFrameworkScanIntervalMs
                        + " mEnableBackgroundScan= " + mEnableBackgroundScan
                        + " screenOn=" + mScreenOn
                        + " mFrameworkScanIntervalMs=" + mFrameworkScanIntervalMs);
            }

            /** clear the roaming state, if we were roaming, we failed */
            mAutoRoaming = WifiAutoJoinController.AUTO_JOIN_IDLE;

            if (mScreenOn) {
                /**
                 * screen lit and => delayed timer
                 */
                startDelayedScan(mDisconnectedScanPeriodMs, null, null);
            } else {
                /**
                 * screen dark and PNO supported => scan alarm disabled
                 */
                if (mEnableBackgroundScan) {
                    /* If a regular scan result is pending, do not initiate background
                     * scan until the scan results are returned. This is needed because
                     * initiating a background scan will cancel the regular scan and
                     * scan results will not be returned until background scanning is
                     * cleared
                     */
                    if (!mIsScanOngoing) {
                        enableBackgroundScan(true);
                    }
                } else {
                    setScanAlarm(true);
                }
            }

            /**
             * If we have no networks saved, the supplicant stops doing the periodic scan.
             * The scans are useful to notify the user of the presence of an open network.
             * Note that these are not wake up scans.
             */
            if (!mP2pConnected.get() && mWifiConfigStore.getConfiguredNetworks().size() == 0) {
                sendMessageDelayed(obtainMessage(CMD_NO_NETWORKS_PERIODIC_SCAN,
                        ++mPeriodicScanToken, 0), mSupplicantScanIntervalMs);
            }

            mDisconnectedTimeStamp = System.currentTimeMillis();

        }
        @Override
        public boolean processMessage(Message message) {
            boolean ret = HANDLED;

            logStateAndMessage(message, getClass().getSimpleName());

            switch (message.what) {
                case CMD_NO_NETWORKS_PERIODIC_SCAN:
                    if (mP2pConnected.get()) break;
                    if (message.arg1 == mPeriodicScanToken &&
                            mWifiConfigStore.getConfiguredNetworks().size() == 0) {
                        startScan(UNKNOWN_SCAN_SOURCE, -1, null, null);
                        sendMessageDelayed(obtainMessage(CMD_NO_NETWORKS_PERIODIC_SCAN,
                                    ++mPeriodicScanToken, 0), mSupplicantScanIntervalMs);
                    }
                    break;
                case WifiManager.FORGET_NETWORK:
                case CMD_REMOVE_NETWORK:
                    // Set up a delayed message here. After the forget/remove is handled
                    // the handled delayed message will determine if there is a need to
                    // scan and continue
                    sendMessageDelayed(obtainMessage(CMD_NO_NETWORKS_PERIODIC_SCAN,
                                ++mPeriodicScanToken, 0), mSupplicantScanIntervalMs);
                    ret = NOT_HANDLED;
                    break;
                case CMD_SET_OPERATIONAL_MODE:
                    if (message.arg1 != CONNECT_MODE) {
                        mOperationalMode = message.arg1;

                        mWifiConfigStore.disableAllNetworks();
                        if (mOperationalMode == SCAN_ONLY_WITH_WIFI_OFF_MODE) {
                            mWifiP2pChannel.sendMessage(CMD_DISABLE_P2P_REQ);
                            setWifiState(WIFI_STATE_DISABLED);
                        }
                        transitionTo(mScanModeState);
                    }
                    mWifiConfigStore.
                            setLastSelectedConfiguration(WifiConfiguration.INVALID_NETWORK_ID);
                    break;
                    /* Ignore network disconnect */
                case WifiMonitor.NETWORK_DISCONNECTION_EVENT:
                    break;
                case WifiMonitor.SUPPLICANT_STATE_CHANGE_EVENT:
                    StateChangeResult stateChangeResult = (StateChangeResult) message.obj;
                    if (DBG) {
                        loge("SUPPLICANT_STATE_CHANGE_EVENT state=" + stateChangeResult.state +
                                " -> state= " + WifiInfo.getDetailedStateOf(stateChangeResult.state)
                                + " debouncing=" + linkDebouncing);
                    }
                    setNetworkDetailedState(WifiInfo.getDetailedStateOf(stateChangeResult.state));
                    /* ConnectModeState does the rest of the handling */
                    ret = NOT_HANDLED;
                    break;
                case CMD_START_SCAN:
                    if (!checkOrDeferScanAllowed(message)) {
                        // The scan request was rescheduled
                        messageHandlingStatus = MESSAGE_HANDLING_STATUS_REFUSED;
                        return HANDLED;
                    }
                    /* Disable background scan temporarily during a regular scan */
                    if (mEnableBackgroundScan) {
                        enableBackgroundScan(false);
                    }
                    if (message.arg1 == SCAN_ALARM_SOURCE) {
                        // Check if the CMD_START_SCAN message is obsolete (and thus if it should
                        // not be processed) and restart the scan
                        int period =  mDisconnectedScanPeriodMs;
                        if (mP2pConnected.get()) {
                           period = (int)Settings.Global.getLong(mContext.getContentResolver(),
                                    Settings.Global.WIFI_SCAN_INTERVAL_WHEN_P2P_CONNECTED_MS,
                                    mDisconnectedScanPeriodMs);
                        }
                        if (!checkAndRestartDelayedScan(message.arg2,
                                true, period, null, null)) {
                            messageHandlingStatus = MESSAGE_HANDLING_STATUS_OBSOLETE;
                            loge("WifiStateMachine Disconnected CMD_START_SCAN source "
                                    + message.arg1
                                    + " " + message.arg2 + ", " + mDelayedScanCounter
                                    + " -> obsolete");
                            return HANDLED;
                        }
                        handleScanRequest(WifiNative.SCAN_WITHOUT_CONNECTION_SETUP, message);
                        ret = HANDLED;
                    } else {
                        ret = NOT_HANDLED;
                    }
                    break;
                case WifiMonitor.SCAN_RESULTS_EVENT:
                    /* Re-enable background scan when a pending scan result is received */
                    if (mEnableBackgroundScan && mIsScanOngoing) {
                        enableBackgroundScan(true);
                    }
                    /* Handled in parent state */
                    ret = NOT_HANDLED;
                    break;
                case WifiP2pServiceImpl.P2P_CONNECTION_CHANGED:
                    NetworkInfo info = (NetworkInfo) message.obj;
                    mP2pConnected.set(info.isConnected());
                    if (mP2pConnected.get()) {
                        int defaultInterval = mContext.getResources().getInteger(
                                R.integer.config_wifi_scan_interval_p2p_connected);
                        long scanIntervalMs = Settings.Global.getLong(mContext.getContentResolver(),
                                Settings.Global.WIFI_SCAN_INTERVAL_WHEN_P2P_CONNECTED_MS,
                                defaultInterval);
                        mWifiNative.setScanInterval((int) scanIntervalMs/1000);
                    } else if (mWifiConfigStore.getConfiguredNetworks().size() == 0) {
                        if (DBG) log("Turn on scanning after p2p disconnected");
                        sendMessageDelayed(obtainMessage(CMD_NO_NETWORKS_PERIODIC_SCAN,
                                    ++mPeriodicScanToken, 0), mSupplicantScanIntervalMs);
                    } else {
                        // If P2P is not connected and there are saved networks, then restart
                        // scanning at the normal period. This is necessary because scanning might
                        // have been disabled altogether if WIFI_SCAN_INTERVAL_WHEN_P2P_CONNECTED_MS
                        // was set to zero.
                        startDelayedScan(mDisconnectedScanPeriodMs, null, null);
                    }
                case CMD_RECONNECT:
                case CMD_REASSOCIATE:
                    if (mTemporarilyDisconnectWifi) {
                        // Drop a third party reconnect/reassociate if STA is
                        // temporarily disconnected for p2p
                        break;
                    } else {
                        // ConnectModeState handles it
                        ret = NOT_HANDLED;
                    }
                    break;
                case CMD_SCREEN_STATE_CHANGED:
                    handleScreenStateChanged(message.arg1 != 0,
                            /* startBackgroundScanIfNeeded = */ true);
                    break;
                default:
                    ret = NOT_HANDLED;
            }
            return ret;
        }

        @Override
        public void exit() {
            /* No need for a background scan upon exit from a disconnected state */
            if (mEnableBackgroundScan) {
                enableBackgroundScan(false);
            }
            setScanAlarm(false);
        }
    }

    class WpsRunningState extends State {
        // Tracks the source to provide a reply
        private Message mSourceMessage;
        @Override
        public void enter() {
            mSourceMessage = Message.obtain(getCurrentMessage());
        }
        @Override
        public boolean processMessage(Message message) {
            logStateAndMessage(message, getClass().getSimpleName());

            switch (message.what) {
                case WifiMonitor.WPS_SUCCESS_EVENT:
                    // Ignore intermediate success, wait for full connection
                    break;
                case WifiMonitor.NETWORK_CONNECTION_EVENT:
                    replyToMessage(mSourceMessage, WifiManager.WPS_COMPLETED);
                    mSourceMessage.recycle();
                    mSourceMessage = null;
                    deferMessage(message);
                    transitionTo(mDisconnectedState);
                    break;
                case WifiMonitor.WPS_OVERLAP_EVENT:
                    replyToMessage(mSourceMessage, WifiManager.WPS_FAILED,
                            WifiManager.WPS_OVERLAP_ERROR);
                    mSourceMessage.recycle();
                    mSourceMessage = null;
                    transitionTo(mDisconnectedState);
                    break;
                case WifiMonitor.WPS_FAIL_EVENT:
                    // Arg1 has the reason for the failure
                    if ((message.arg1 != WifiManager.ERROR) || (message.arg2 != 0)) {
                        replyToMessage(mSourceMessage, WifiManager.WPS_FAILED, message.arg1);
                        mSourceMessage.recycle();
                        mSourceMessage = null;
                        transitionTo(mDisconnectedState);
                    } else {
                        if (DBG) log("Ignore unspecified fail event during WPS connection");
                    }
                    break;
                case WifiMonitor.WPS_TIMEOUT_EVENT:
                    replyToMessage(mSourceMessage, WifiManager.WPS_FAILED,
                            WifiManager.WPS_TIMED_OUT);
                    mSourceMessage.recycle();
                    mSourceMessage = null;
                    transitionTo(mDisconnectedState);
                    break;
                case WifiManager.START_WPS:
                    replyToMessage(message, WifiManager.WPS_FAILED, WifiManager.IN_PROGRESS);
                    break;
                case WifiManager.CANCEL_WPS:
                    if (mWifiNative.cancelWps()) {
                        replyToMessage(message, WifiManager.CANCEL_WPS_SUCCEDED);
                    } else {
                        replyToMessage(message, WifiManager.CANCEL_WPS_FAILED, WifiManager.ERROR);
                    }
                    transitionTo(mDisconnectedState);
                    break;
                /**
                 * Defer all commands that can cause connections to a different network
                 * or put the state machine out of connect mode
                 */
                case CMD_STOP_DRIVER:
                case CMD_SET_OPERATIONAL_MODE:
                case WifiManager.CONNECT_NETWORK:
                case CMD_ENABLE_NETWORK:
                case CMD_RECONNECT:
                case CMD_REASSOCIATE:
                    deferMessage(message);
                    break;
                case CMD_AUTO_CONNECT:
                case CMD_AUTO_ROAM:
                    messageHandlingStatus = MESSAGE_HANDLING_STATUS_DISCARD;
                    return HANDLED;
                case CMD_START_SCAN:
                    messageHandlingStatus = MESSAGE_HANDLING_STATUS_DISCARD;
                    return HANDLED;
                case WifiMonitor.NETWORK_DISCONNECTION_EVENT:
                    if (DBG) log("Network connection lost");
                    handleNetworkDisconnect();
                    break;
                case WifiMonitor.ASSOCIATION_REJECTION_EVENT:
                    if (DBG) log("Ignore Assoc reject event during WPS Connection");
                    break;
                case WifiMonitor.AUTHENTICATION_FAILURE_EVENT:
                    // Disregard auth failure events during WPS connection. The
                    // EAP sequence is retried several times, and there might be
                    // failures (especially for wps pin). We will get a WPS_XXX
                    // event at the end of the sequence anyway.
                    if (DBG) log("Ignore auth failure during WPS connection");
                    break;
                case WifiMonitor.SUPPLICANT_STATE_CHANGE_EVENT:
                    // Throw away supplicant state changes when WPS is running.
                    // We will start getting supplicant state changes once we get
                    // a WPS success or failure
                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }

        @Override
        public void exit() {
            mWifiConfigStore.enableAllNetworks();
            mWifiConfigStore.loadConfiguredNetworks();
        }
    }

    class SoftApStartingState extends State {
        @Override
        public void enter() {
            final Message message = getCurrentMessage();
            if (message.what == CMD_START_AP) {
                final WifiConfiguration config = (WifiConfiguration) message.obj;

                if (config == null) {
                    mWifiApConfigChannel.sendMessage(CMD_REQUEST_AP_CONFIG);
                } else {
                    mWifiApConfigChannel.sendMessage(CMD_SET_AP_CONFIG, config);
                    startSoftApWithConfig(config);
                }
            } else {
                throw new RuntimeException("Illegal transition to SoftApStartingState: " + message);
            }
        }
        @Override
        public boolean processMessage(Message message) {
            logStateAndMessage(message, getClass().getSimpleName());

            switch(message.what) {
                case CMD_START_SUPPLICANT:
                case CMD_STOP_SUPPLICANT:
                case CMD_START_AP:
                case CMD_STOP_AP:
                case CMD_START_DRIVER:
                case CMD_STOP_DRIVER:
                case CMD_SET_OPERATIONAL_MODE:
                case CMD_SET_COUNTRY_CODE:
                case CMD_SET_FREQUENCY_BAND:
                case CMD_START_PACKET_FILTERING:
                case CMD_STOP_PACKET_FILTERING:
                case CMD_TETHER_STATE_CHANGE:
                    deferMessage(message);
                    break;
                case WifiStateMachine.CMD_RESPONSE_AP_CONFIG:
                    WifiConfiguration config = (WifiConfiguration) message.obj;
                    if (config != null) {
                        startSoftApWithConfig(config);
                    } else {
                        loge("Softap config is null!");
                        sendMessage(CMD_START_AP_FAILURE);
                    }
                    break;
                case CMD_START_AP_SUCCESS:
                    setWifiApState(WIFI_AP_STATE_ENABLED);
                    transitionTo(mSoftApStartedState);
                    break;
                case CMD_START_AP_FAILURE:
                    setWifiApState(WIFI_AP_STATE_FAILED);
                    transitionTo(mInitialState);
                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }
    }

    class SoftApStartedState extends State {
        @Override
        public boolean processMessage(Message message) {
            logStateAndMessage(message, getClass().getSimpleName());

            switch(message.what) {
                case CMD_STOP_AP:
                    if (DBG) log("Stopping Soft AP");
                    /* We have not tethered at this point, so we just shutdown soft Ap */
                    try {
                        mNwService.stopAccessPoint(mInterfaceName);
                    } catch(Exception e) {
                        loge("Exception in stopAccessPoint()");
                    }
                    setWifiApState(WIFI_AP_STATE_DISABLED);
                    transitionTo(mInitialState);
                    break;
                case CMD_START_AP:
                    // Ignore a start on a running access point
                    break;
                    // Fail client mode operation when soft AP is enabled
                case CMD_START_SUPPLICANT:
                    loge("Cannot start supplicant with a running soft AP");
                    setWifiState(WIFI_STATE_UNKNOWN);
                    break;
                case CMD_TETHER_STATE_CHANGE:
                    TetherStateChange stateChange = (TetherStateChange) message.obj;
                    if (startTethering(stateChange.available)) {
                        transitionTo(mTetheringState);
                    }
                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }
    }

    class TetheringState extends State {
        @Override
        public void enter() {
            /* Send ourselves a delayed message to shut down if tethering fails to notify */
            sendMessageDelayed(obtainMessage(CMD_TETHER_NOTIFICATION_TIMED_OUT,
                    ++mTetherToken, 0), TETHER_NOTIFICATION_TIME_OUT_MSECS);
        }
        @Override
        public boolean processMessage(Message message) {
            logStateAndMessage(message, getClass().getSimpleName());

            switch(message.what) {
                case CMD_TETHER_STATE_CHANGE:
                    TetherStateChange stateChange = (TetherStateChange) message.obj;
                    if (isWifiTethered(stateChange.active)) {
                        transitionTo(mTetheredState);
                    }
                    return HANDLED;
                case CMD_TETHER_NOTIFICATION_TIMED_OUT:
                    if (message.arg1 == mTetherToken) {
                        loge("Failed to get tether update, shutdown soft access point");
                        transitionTo(mSoftApStartedState);
                        // Needs to be first thing handled
                        sendMessageAtFrontOfQueue(CMD_STOP_AP);
                    }
                    break;
                case CMD_START_SUPPLICANT:
                case CMD_STOP_SUPPLICANT:
                case CMD_START_AP:
                case CMD_STOP_AP:
                case CMD_START_DRIVER:
                case CMD_STOP_DRIVER:
                case CMD_SET_OPERATIONAL_MODE:
                case CMD_SET_COUNTRY_CODE:
                case CMD_SET_FREQUENCY_BAND:
                case CMD_START_PACKET_FILTERING:
                case CMD_STOP_PACKET_FILTERING:
                    deferMessage(message);
                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }
    }

    class TetheredState extends State {
        @Override
        public boolean processMessage(Message message) {
            logStateAndMessage(message, getClass().getSimpleName());

            switch(message.what) {
                case CMD_TETHER_STATE_CHANGE:
                    TetherStateChange stateChange = (TetherStateChange) message.obj;
                    if (!isWifiTethered(stateChange.active)) {
                        loge("Tethering reports wifi as untethered!, shut down soft Ap");
                        setHostApRunning(null, false);
                        setHostApRunning(null, true);
                    }
                    return HANDLED;
                case CMD_STOP_AP:
                    if (DBG) log("Untethering before stopping AP");
                    setWifiApState(WIFI_AP_STATE_DISABLING);
                    stopTethering();
                    transitionTo(mUntetheringState);
                    // More work to do after untethering
                    deferMessage(message);
                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }
    }

    class UntetheringState extends State {
        @Override
        public void enter() {
            /* Send ourselves a delayed message to shut down if tethering fails to notify */
            sendMessageDelayed(obtainMessage(CMD_TETHER_NOTIFICATION_TIMED_OUT,
                    ++mTetherToken, 0), TETHER_NOTIFICATION_TIME_OUT_MSECS);

        }
        @Override
        public boolean processMessage(Message message) {
            logStateAndMessage(message, getClass().getSimpleName());

            switch(message.what) {
                case CMD_TETHER_STATE_CHANGE:
                    TetherStateChange stateChange = (TetherStateChange) message.obj;

                    /* Wait till wifi is untethered */
                    if (isWifiTethered(stateChange.active)) break;

                    transitionTo(mSoftApStartedState);
                    break;
                case CMD_TETHER_NOTIFICATION_TIMED_OUT:
                    if (message.arg1 == mTetherToken) {
                        loge("Failed to get tether update, force stop access point");
                        transitionTo(mSoftApStartedState);
                    }
                    break;
                case CMD_START_SUPPLICANT:
                case CMD_STOP_SUPPLICANT:
                case CMD_START_AP:
                case CMD_STOP_AP:
                case CMD_START_DRIVER:
                case CMD_STOP_DRIVER:
                case CMD_SET_OPERATIONAL_MODE:
                case CMD_SET_COUNTRY_CODE:
                case CMD_SET_FREQUENCY_BAND:
                case CMD_START_PACKET_FILTERING:
                case CMD_STOP_PACKET_FILTERING:
                    deferMessage(message);
                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }
    }

    //State machine initiated requests can have replyTo set to null indicating
    //there are no recepients, we ignore those reply actions
    private void replyToMessage(Message msg, int what) {
        if (msg.replyTo == null) return;
        Message dstMsg = obtainMessageWithArg2(msg);
        dstMsg.what = what;
        mReplyChannel.replyToMessage(msg, dstMsg);
    }

    private void replyToMessage(Message msg, int what, int arg1) {
        if (msg.replyTo == null) return;
        Message dstMsg = obtainMessageWithArg2(msg);
        dstMsg.what = what;
        dstMsg.arg1 = arg1;
        mReplyChannel.replyToMessage(msg, dstMsg);
    }

    private void replyToMessage(Message msg, int what, Object obj) {
        if (msg.replyTo == null) return;
        Message dstMsg = obtainMessageWithArg2(msg);
        dstMsg.what = what;
        dstMsg.obj = obj;
        mReplyChannel.replyToMessage(msg, dstMsg);
    }

    /**
     * arg2 on the source message has a unique id that needs to be retained in replies
     * to match the request

     * see WifiManager for details
     */
    private Message obtainMessageWithArg2(Message srcMsg) {
        Message msg = Message.obtain();
        msg.arg2 = srcMsg.arg2;
        return msg;
    }

    private static int parseHex(char ch) {
        if ('0' <= ch && ch <= '9') {
            return ch - '0';
        } else if ('a' <= ch && ch <= 'f') {
            return ch - 'a' + 10;
        } else if ('A' <= ch && ch <= 'F') {
            return ch - 'A' + 10;
        } else {
            throw new NumberFormatException("" + ch + " is not a valid hex digit");
        }
    }

    private byte[] parseHex(String hex) {
        /* This only works for good input; don't throw bad data at it */
        if (hex == null) {
            return new byte[0];
        }

        if (hex.length() % 2 != 0) {
            throw new NumberFormatException(hex + " is not a valid hex string");
        }

        byte[] result = new byte[(hex.length())/2 + 1];
        result[0] = (byte) ((hex.length())/2);
        for (int i = 0, j = 1; i < hex.length(); i += 2, j++) {
            int val = parseHex(hex.charAt(i)) * 16 + parseHex(hex.charAt(i+1));
            byte b = (byte) (val & 0xFF);
            result[j] = b;
        }

        return result;
    }

    private static String makeHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static String makeHex(byte[] bytes, int from, int len) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) {
            sb.append(String.format("%02x", bytes[from+i]));
        }
        return sb.toString();
    }


    private static byte[] concat(byte[] array1, byte[] array2, byte[] array3) {

        int len = array1.length + array2.length + array3.length;

        if (array1.length != 0) {
            len++;                      /* add another byte for size */
        }

        if (array2.length != 0) {
            len++;                      /* add another byte for size */
        }

        if (array3.length != 0) {
            len++;                      /* add another byte for size */
        }

        byte[] result = new byte[len];

        int index = 0;
        if (array1.length != 0) {
            result[index] = (byte) (array1.length & 0xFF);
            index++;
            for (byte b : array1) {
                result[index] = b;
                index++;
            }
        }

        if (array2.length != 0) {
            result[index] = (byte) (array2.length & 0xFF);
            index++;
            for (byte b : array2) {
                result[index] = b;
                index++;
            }
        }

        if (array3.length != 0) {
            result[index] = (byte) (array3.length & 0xFF);
            index++;
            for (byte b : array3) {
                result[index] = b;
                index++;
            }
        }
        return result;
    }

    void handleGsmAuthRequest(SimAuthRequestData requestData) {
        if (targetWificonfiguration == null
                || targetWificonfiguration.networkId == requestData.networkId) {
            logd("id matches targetWifiConfiguration");
        } else {
            logd("id does not match targetWifiConfiguration");
            return;
        }

        TelephonyManager tm = (TelephonyManager)
                mContext.getSystemService(Context.TELEPHONY_SERVICE);

        if (tm != null) {
            StringBuilder sb = new StringBuilder();
            for (String challenge : requestData.challenges) {

                logd("RAND = " + challenge);

                byte[] rand = null;
                try {
                    rand = parseHex(challenge);
                } catch (NumberFormatException e) {
                    loge("malformed challenge");
                    continue;
                }

                String base64Challenge = android.util.Base64.encodeToString(
                        rand, android.util.Base64.NO_WRAP);
                /*
                 * appType = 1 => SIM, 2 => USIM according to
                 * com.android.internal.telephony.PhoneConstants#APPTYPE_xxx
                 */
                int appType = 2;
                String tmResponse = tm.getIccSimChallengeResponse(appType, base64Challenge);
                logv("Raw Response - " + tmResponse);

                if (tmResponse != null && tmResponse.length() > 4) {
                    byte[] result = android.util.Base64.decode(tmResponse,
                            android.util.Base64.DEFAULT);
                    logv("Hex Response -" + makeHex(result));
                    int sres_len = result[0];
                    String sres = makeHex(result, 1, sres_len);
                    int kc_offset = 1+sres_len;
                    int kc_len = result[kc_offset];
                    String kc = makeHex(result, 1+kc_offset, kc_len);
                    sb.append(":" + kc + ":" + sres);
                    logv("kc:" + kc + " sres:" + sres);
                } else {
                    loge("bad response - " + tmResponse);
                }
            }

            String response = sb.toString();
            logv("Supplicant Response -" + response);
            mWifiNative.simAuthResponse(requestData.networkId, response);
        } else {
            loge("could not get telephony manager");
        }
    }

    void handle3GAuthRequest(SimAuthRequestData requestData) {

    }
}
