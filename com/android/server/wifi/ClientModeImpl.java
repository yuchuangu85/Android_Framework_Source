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

import static android.net.wifi.WifiConfiguration.NetworkSelectionStatus.DISABLED_NO_INTERNET_PERMANENT;
import static android.net.wifi.WifiConfiguration.NetworkSelectionStatus.DISABLED_NO_INTERNET_TEMPORARY;
import static android.net.wifi.WifiManager.WIFI_FEATURE_FILS_SHA256;
import static android.net.wifi.WifiManager.WIFI_FEATURE_FILS_SHA384;
import static android.net.wifi.WifiManager.WIFI_STATE_DISABLED;
import static android.net.wifi.WifiManager.WIFI_STATE_DISABLING;
import static android.net.wifi.WifiManager.WIFI_STATE_ENABLED;
import static android.net.wifi.WifiManager.WIFI_STATE_ENABLING;
import static android.net.wifi.WifiManager.WIFI_STATE_UNKNOWN;

import static com.android.server.wifi.WifiDataStall.INVALID_THROUGHPUT;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.DhcpResultsParcelable;
import android.net.InvalidPacketException;
import android.net.IpConfiguration;
import android.net.KeepalivePacketData;
import android.net.Layer2PacketParcelable;
import android.net.LinkProperties;
import android.net.MacAddress;
import android.net.MatchAllNetworkSpecifier;
import android.net.NattKeepalivePacketData;
import android.net.Network;
import android.net.NetworkAgent;
import android.net.NetworkAgentConfig;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.NetworkInfo.DetailedState;
import android.net.NetworkProvider;
import android.net.SocketKeepalive;
import android.net.StaticIpConfiguration;
import android.net.TcpKeepalivePacketData;
import android.net.Uri;
import android.net.ip.IIpClient;
import android.net.ip.IpClientCallbacks;
import android.net.ip.IpClientManager;
import android.net.shared.Layer2Information;
import android.net.shared.ProvisioningConfiguration;
import android.net.shared.ProvisioningConfiguration.ScanResultInfo;
import android.net.util.MacAddressUtils;
import android.net.util.NetUtils;
import android.net.wifi.IActionListener;
import android.net.wifi.INetworkRequestMatchCallback;
import android.net.wifi.ScanResult;
import android.net.wifi.SupplicantState;
import android.net.wifi.WifiAnnotations.WifiStandard;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiEnterpriseConfig;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiManager.DeviceMobilityState;
import android.net.wifi.WifiNetworkAgentSpecifier;
import android.net.wifi.WifiScanner;
import android.net.wifi.hotspot2.IProvisioningCallback;
import android.net.wifi.hotspot2.OsuProvider;
import android.net.wifi.nl80211.DeviceWiphyCapabilities;
import android.net.wifi.nl80211.WifiNl80211Manager;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.BatteryStatsManager;
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

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.AsyncChannel;
import com.android.internal.util.MessageUtils;
import com.android.internal.util.Protocol;
import com.android.internal.util.State;
import com.android.internal.util.StateMachine;
import com.android.net.module.util.Inet4AddressUtils;
import com.android.server.wifi.MboOceController.BtmFrameData;
import com.android.server.wifi.WifiCarrierInfoManager.SimAuthRequestData;
import com.android.server.wifi.WifiCarrierInfoManager.SimAuthResponseData;
import com.android.server.wifi.hotspot2.AnqpEvent;
import com.android.server.wifi.hotspot2.IconEvent;
import com.android.server.wifi.hotspot2.NetworkDetail;
import com.android.server.wifi.hotspot2.PasspointManager;
import com.android.server.wifi.hotspot2.WnmData;
import com.android.server.wifi.p2p.WifiP2pServiceImpl;
import com.android.server.wifi.proto.WifiStatsLog;
import com.android.server.wifi.proto.nano.WifiMetricsProto;
import com.android.server.wifi.proto.nano.WifiMetricsProto.StaEvent;
import com.android.server.wifi.proto.nano.WifiMetricsProto.WifiIsUnusableEvent;
import com.android.server.wifi.proto.nano.WifiMetricsProto.WifiUsabilityStats;
import com.android.server.wifi.util.ExternalCallbackTracker;
import com.android.server.wifi.util.NativeUtil;
import com.android.server.wifi.util.RssiUtil;
import com.android.server.wifi.util.ScanResultUtil;
import com.android.server.wifi.util.WifiPermissionsUtil;
import com.android.server.wifi.util.WifiPermissionsWrapper;
import com.android.wifi.resources.R;

import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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

    // Association rejection reason codes
    @VisibleForTesting
    protected static final int REASON_CODE_AP_UNABLE_TO_HANDLE_NEW_STA = 17;

    private static final String TAG = "WifiClientModeImpl";

    private static final int ONE_HOUR_MILLI = 1000 * 60 * 60;

    private static final String GOOGLE_OUI = "DA-A1-19";

    private static final String EXTRA_OSU_ICON_QUERY_BSSID = "BSSID";
    private static final String EXTRA_OSU_ICON_QUERY_FILENAME = "FILENAME";
    private static final String EXTRA_OSU_PROVIDER = "OsuProvider";
    private static final String EXTRA_UID = "uid";
    private static final String EXTRA_PACKAGE_NAME = "PackageName";
    private static final String EXTRA_PASSPOINT_CONFIGURATION = "PasspointConfiguration";
    private static final int IPCLIENT_STARTUP_TIMEOUT_MS = 20 * 60 * 1000; // 20 minutes!
    private static final int IPCLIENT_SHUTDOWN_TIMEOUT_MS = 60_000; // 60 seconds

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
    private final BssidBlocklistMonitor mBssidBlocklistMonitor;
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
    private final WifiHealthMonitor mWifiHealthMonitor;
    private final WifiScoreReport mWifiScoreReport;
    private final SarManager mSarManager;
    private final WifiTrafficPoller mWifiTrafficPoller;
    public WifiScoreReport getWifiScoreReport() {
        return mWifiScoreReport;
    }
    private final PasspointManager mPasspointManager;
    private final WifiDataStall mWifiDataStall;
    private final LinkProbeManager mLinkProbeManager;
    private final MboOceController mMboOceController;

    private final McastLockManagerFilterController mMcastLockManagerFilterController;
    private final ActivityManager mActivityManager;

    private boolean mScreenOn = false;

    private String mInterfaceName;

    private int mLastSignalLevel = -1;
    private String mLastBssid;
    private int mLastNetworkId; // The network Id we successfully joined
    // The subId used by WifiConfiguration with SIM credential which was connected successfully
    private int mLastSubId;
    private String mLastSimBasedConnectionCarrierName;

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
    // Accessed via Binder thread ({get,set}PollRssiIntervalMsecs), and the main Wifi thread.
    private volatile int mPollRssiIntervalMsecs = -1;
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
     * Maximum allowable interval in milliseconds between polling for RSSI and linkspeed
     * information. This is also used as the polling interval for WifiTrafficPoller, which updates
     * its data activity on every CMD_RSSI_POLL.
     */
    private static final int MAXIMUM_POLL_RSSI_INTERVAL_MSECS = 6000;

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

    private Context mContext;

    private final Object mDhcpResultsParcelableLock = new Object();
    @NonNull
    private DhcpResultsParcelable mDhcpResultsParcelable = new DhcpResultsParcelable();

    // NOTE: Do not return to clients - see syncRequestConnectionInfo()
    private final ExtendedWifiInfo mWifiInfo;
    // TODO : remove this member. It should be possible to only call sendNetworkChangeBroadcast when
    // the state actually changed, and to deduce the state of the agent from the state of the
    // machine when generating the NetworkInfo for the broadcast.
    private DetailedState mNetworkAgentState;
    private SupplicantStateTracker mSupplicantStateTracker;

    // Indicates that framework is attempting to roam, set true on CMD_START_ROAM, set false when
    // wifi connects or fails to connect
    private boolean mIsAutoRoaming = false;

    // Roaming failure count
    private int mRoamFailCount = 0;

    // This is the BSSID we are trying to associate to, it can be set to SUPPLICANT_BSSID_ANY
    // if we havent selected a BSSID for joining.
    private String mTargetBssid = SUPPLICANT_BSSID_ANY;
    // This one is used to track the current target network ID. This is used for error
    // handling during connection setup since many error message from supplicant does not report
    // SSID. Once connected, it will be set to invalid
    private int mTargetNetworkId = WifiConfiguration.INVALID_NETWORK_ID;
    private long mLastDriverRoamAttempt = 0;
    private WifiConfiguration mTargetWifiConfiguration = null;

    int getPollRssiIntervalMsecs() {
        if (mPollRssiIntervalMsecs > 0) {
            return mPollRssiIntervalMsecs;
        }
        return Math.min(mContext.getResources().getInteger(
                R.integer.config_wifiPollRssiIntervalMilliseconds),
                        MAXIMUM_POLL_RSSI_INTERVAL_MSECS);
    }

    void setPollRssiIntervalMsecs(int newPollIntervalMsecs) {
        mPollRssiIntervalMsecs = newPollIntervalMsecs;
    }

    /**
     * Method to clear {@link #mTargetBssid} and reset the current connected network's
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
            logd(dbg + " clearTargetBssid " + bssid + " key=" + config.getKey());
        }
        mTargetBssid = bssid;
        return mWifiNative.setConfiguredNetworkBSSID(mInterfaceName, bssid);
    }

    /**
     * Set Config's default BSSID (for association purpose) and {@link #mTargetBssid}
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
            Log.d(TAG, "setTargetBssid set to " + bssid + " key=" + config.getKey());
        }
        mTargetBssid = bssid;
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
    private WifiNetworkAgent mNetworkAgent;

    private byte[] mRssiRanges;

    // Used to filter out requests we couldn't possibly satisfy.
    private final NetworkCapabilities mNetworkCapabilitiesFilter;

    private final ExternalCallbackTracker<IActionListener> mProcessingActionListeners;

    /* The base for wifi message types */
    static final int BASE = Protocol.BASE_WIFI;
    /* BT state change, e.g., on or off */
    static final int CMD_BLUETOOTH_ADAPTER_STATE_CHANGE                 = BASE + 31;
    /* BT connection state change, e.g., connected or disconnected */
    static final int CMD_BLUETOOTH_ADAPTER_CONNECTION_STATE_CHANGE      = BASE + 32;

    /* Get adaptors */
    static final int CMD_GET_SUPPORTED_FEATURES                         = BASE + 61;
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

    /* SIM is removed; reset any cached data for it */
    static final int CMD_RESET_SIM_NETWORKS                             = BASE + 101;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"RESET_SIM_REASON_"},
            value = {
                    RESET_SIM_REASON_SIM_REMOVED,
                    RESET_SIM_REASON_SIM_INSERTED,
                    RESET_SIM_REASON_DEFAULT_DATA_SIM_CHANGED})
    @interface ResetSimReason {}
    static final int RESET_SIM_REASON_SIM_REMOVED              = 0;
    static final int RESET_SIM_REASON_SIM_INSERTED             = 1;
    static final int RESET_SIM_REASON_DEFAULT_DATA_SIM_CHANGED = 2;

    /* OSU APIs */
    static final int CMD_QUERY_OSU_ICON                                 = BASE + 104;

    /* Commands from/to the SupplicantStateTracker */
    /* Reset the supplicant state tracker */
    static final int CMD_RESET_SUPPLICANT_STATE                         = BASE + 111;

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

    static final int CMD_START_CONNECT                                  = BASE + 143;

    private static final int NETWORK_STATUS_UNWANTED_DISCONNECT         = 0;
    private static final int NETWORK_STATUS_UNWANTED_VALIDATION_FAILED  = 1;
    private static final int NETWORK_STATUS_UNWANTED_DISABLE_AUTOJOIN   = 2;

    static final int CMD_UNWANTED_NETWORK                               = BASE + 144;

    static final int CMD_START_ROAM                                     = BASE + 145;

    static final int CMD_NETWORK_STATUS                                 = BASE + 148;

    /* A layer 3 neighbor on the Wi-Fi link became unreachable. */
    static final int CMD_IP_REACHABILITY_LOST                           = BASE + 149;

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

    private static final int CMD_CONNECT_NETWORK                        = BASE + 258;
    private static final int CMD_SAVE_NETWORK                           = BASE + 259;

    /* Start connection to FILS AP*/
    static final int CMD_START_FILS_CONNECTION                          = BASE + 262;

    private static final int CMD_GET_CURRENT_NETWORK                    = BASE + 263;

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

    /*
     * FILS connection related variables.
     */
    /* To indicate to IpClient whether HLP IEs were included or not in assoc request */
    private boolean mSentHLPs = false;
    /* Tracks IpClient start state until (FILS_)NETWORK_CONNECTION_EVENT event */
    private boolean mIpClientWithPreConnection = false;

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

    private final BatteryStatsManager mBatteryStatsManager;

    private final WifiCarrierInfoManager mWifiCarrierInfoManager;


    // Used for debug and stats gathering
    private static int sScanAlarmIntentCount = 0;

    private FrameworkFacade mFacade;
    private WifiStateTracker mWifiStateTracker;
    private final BackupManagerProxy mBackupManagerProxy;
    private final WrongPasswordNotifier mWrongPasswordNotifier;
    private final EapFailureNotifier mEapFailureNotifier;
    private final SimRequiredNotifier mSimRequiredNotifier;
    private final ConnectionFailureNotifier mConnectionFailureNotifier;
    private WifiNetworkSuggestionsManager mWifiNetworkSuggestionsManager;
    // Maximum duration to continue to log Wifi usability stats after a data stall is triggered.
    @VisibleForTesting
    public static final long DURATION_TO_WAIT_ADD_STATS_AFTER_DATA_STALL_MS = 30 * 1000;
    private long mDataStallTriggerTimeMs = -1;
    private int mLastStatusDataStall = WifiIsUnusableEvent.TYPE_UNKNOWN;

    public ClientModeImpl(Context context, FrameworkFacade facade, Looper looper,
                            UserManager userManager, WifiInjector wifiInjector,
                            BackupManagerProxy backupManagerProxy, WifiCountryCode countryCode,
                            WifiNative wifiNative, WrongPasswordNotifier wrongPasswordNotifier,
                            SarManager sarManager, WifiTrafficPoller wifiTrafficPoller,
                            LinkProbeManager linkProbeManager,
                            BatteryStatsManager batteryStatsManager,
                            SupplicantStateTracker supplicantStateTracker,
                            MboOceController mboOceController,
                            WifiCarrierInfoManager wifiCarrierInfoManager,
                            EapFailureNotifier eapFailureNotifier,
                            SimRequiredNotifier simRequiredNotifier) {
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
        mEapFailureNotifier = eapFailureNotifier;
        mSimRequiredNotifier = simRequiredNotifier;
        mSarManager = sarManager;
        mWifiTrafficPoller = wifiTrafficPoller;
        mLinkProbeManager = linkProbeManager;
        mMboOceController = mboOceController;
        mWifiCarrierInfoManager = wifiCarrierInfoManager;
        mNetworkAgentState = DetailedState.DISCONNECTED;

        mBatteryStatsManager = batteryStatsManager;
        mWifiStateTracker = wifiInjector.getWifiStateTracker();

        mP2pSupported = mContext.getPackageManager().hasSystemFeature(
                PackageManager.FEATURE_WIFI_DIRECT);

        mWifiPermissionsUtil = mWifiInjector.getWifiPermissionsUtil();
        mWifiConfigManager = mWifiInjector.getWifiConfigManager();

        mPasspointManager = mWifiInjector.getPasspointManager();

        mWifiMonitor = mWifiInjector.getWifiMonitor();
        mWifiDiagnostics = mWifiInjector.getWifiDiagnostics();
        mWifiPermissionsWrapper = mWifiInjector.getWifiPermissionsWrapper();
        mWifiDataStall = mWifiInjector.getWifiDataStall();

        mWifiInfo = new ExtendedWifiInfo(context);
        mSupplicantStateTracker = supplicantStateTracker;
        mWifiConnectivityManager = mWifiInjector.makeWifiConnectivityManager(this);
        mBssidBlocklistMonitor = mWifiInjector.getBssidBlocklistMonitor();
        mConnectionFailureNotifier = mWifiInjector.makeConnectionFailureNotifier(
                mWifiConnectivityManager);

        mLinkProperties = new LinkProperties();
        mMcastLockManagerFilterController = new McastLockManagerFilterController();
        mActivityManager = context.getSystemService(ActivityManager.class);

        mLastBssid = null;
        mLastNetworkId = WifiConfiguration.INVALID_NETWORK_ID;
        mLastSubId = SubscriptionManager.INVALID_SUBSCRIPTION_ID;
        mLastSimBasedConnectionCarrierName = null;
        mLastSignalLevel = -1;

        mCountryCode = countryCode;

        mWifiScoreReport = new WifiScoreReport(mWifiInjector.getScoringParams(), mClock,
                mWifiMetrics, mWifiInfo, mWifiNative, mBssidBlocklistMonitor,
                mWifiInjector.getWifiThreadRunner());

        mNetworkCapabilitiesFilter = new NetworkCapabilities.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_CONGESTED)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)
                // TODO - needs to be a bit more dynamic
                .setLinkUpstreamBandwidthKbps(1024 * 1024)
                .setLinkDownstreamBandwidthKbps(1024 * 1024)
                .setNetworkSpecifier(new MatchAllNetworkSpecifier())
                .build();
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
        mProcessingActionListeners = new ExternalCallbackTracker<>(getHandler());
        mWifiHealthMonitor = mWifiInjector.getWifiHealthMonitor();

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

        PowerManager powerManager = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);

        mSuspendWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WifiSuspend");
        mSuspendWakeLock.setReferenceCounted(false);

        mWifiConfigManager.addOnNetworkUpdateListener(new OnNetworkUpdateListener());

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
        mWifiMonitor.registerHandler(mInterfaceName, WifiMonitor.TARGET_BSSID_EVENT, getHandler());
        mWifiMonitor.registerHandler(mInterfaceName, WifiMonitor.ASSOCIATED_BSSID_EVENT,
                getHandler());
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
        mWifiMonitor.registerHandler(mInterfaceName, WifiMonitor.FILS_NETWORK_CONNECTION_EVENT,
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
        mWifiMonitor.registerHandler(mInterfaceName, WifiMonitor.ASSOCIATED_BSSID_EVENT,
                mWifiMetrics.getHandler());
        mWifiMonitor.registerHandler(mInterfaceName, WifiMonitor.TARGET_BSSID_EVENT,
                mWifiMetrics.getHandler());
        mWifiMonitor.registerHandler(mInterfaceName, WifiMonitor.NETWORK_CONNECTION_EVENT,
                mWifiInjector.getWifiLastResortWatchdog().getHandler());
        mWifiMonitor.registerHandler(mInterfaceName, WifiMonitor.ASSOCIATION_REJECTION_EVENT,
                mSupplicantStateTracker.getHandler());
        mWifiMonitor.registerHandler(mInterfaceName, WifiMonitor.AUTHENTICATION_FAILURE_EVENT,
                mSupplicantStateTracker.getHandler());
        mWifiMonitor.registerHandler(mInterfaceName, WifiMonitor.SUPPLICANT_STATE_CHANGE_EVENT,
                mSupplicantStateTracker.getHandler());
        mWifiMonitor.registerHandler(mInterfaceName, WifiMonitor.MBO_OCE_BSS_TM_HANDLING_DONE,
                getHandler());
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
        public void onNewDhcpResults(DhcpResultsParcelable dhcpResults) {
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
        public void onPreconnectionStart(List<Layer2PacketParcelable> packets) {
            sendMessage(CMD_START_FILS_CONNECTION, 0, 0, packets);
        }

        @Override
        public void onQuit() {
            mWaitForStopCv.open();
        }

        boolean awaitCreation() {
            return mWaitForCreationCv.block(IPCLIENT_STARTUP_TIMEOUT_MS);
        }

        boolean awaitShutdown() {
            return mWaitForStopCv.block(IPCLIENT_SHUTDOWN_TIMEOUT_MS);
        }
    }

    private void stopIpClient() {
        // TODO(b/157943924): Adding more log to debug the issue.
        Log.v(TAG, "stopIpClient IpClientWithPreConnection: " + mIpClientWithPreConnection,
                new Throwable());
        if (mIpClient != null) {
            if (mIpClientWithPreConnection) {
                mIpClient.notifyPreconnectionComplete(false);
            }
            mIpClient.stop();
        }
        mIpClientWithPreConnection = false;
        mSentHLPs = false;
    }

    private void stopDhcpSetup() {
        /* Restore power save and suspend optimizations */
        handlePostDhcpSetup();
        stopIpClient();
    }

    /**
     * Listener for config manager network config related events.
     * TODO (b/117601161) : Move some of the existing handling in WifiConnectivityManager's listener
     * for the same events.
     */
    private class OnNetworkUpdateListener implements
            WifiConfigManager.OnNetworkUpdateListener {
        @Override
        public void onNetworkAdded(WifiConfiguration config) { }

        @Override
        public void onNetworkEnabled(WifiConfiguration config) { }

        @Override
        public void onNetworkRemoved(WifiConfiguration config) {
            // The current connected or connecting network has been removed, trigger a disconnect.
            if (config.networkId == mTargetNetworkId || config.networkId == mLastNetworkId) {
                // Disconnect and let autojoin reselect a new network
                sendMessage(CMD_DISCONNECT);
            }
            mWifiNative.removeNetworkCachedData(config.networkId);
        }

        @Override
        public void onNetworkUpdated(WifiConfiguration newConfig, WifiConfiguration oldConfig) {
            // Clear invalid cached data.
            mWifiNative.removeNetworkCachedData(oldConfig.networkId);

            // Check if user/app change meteredOverride for connected network.
            if (newConfig.networkId != mLastNetworkId
                    || newConfig.meteredOverride == oldConfig.meteredOverride) {
                // nothing to do.
                return;
            }
            boolean isMetered = WifiConfiguration.isMetered(newConfig, mWifiInfo);
            boolean wasMetered = WifiConfiguration.isMetered(oldConfig, mWifiInfo);
            if (isMetered == wasMetered) {
                // no meteredness change, nothing to do.
                if (mVerboseLoggingEnabled) {
                    Log.v(TAG, "User/app changed meteredOverride, but no change in meteredness");
                }
                return;
            }
            // If unmetered->metered trigger a disconnect.
            // If metered->unmetered update capabilities.
            if (isMetered) {
                Log.w(TAG, "Network marked metered, triggering disconnect");
                sendMessage(CMD_DISCONNECT);
            } else {
                Log.i(TAG, "Network marked unmetered, triggering capabilities update");
                updateCapabilities(newConfig);
            }
        }

        @Override
        public void onNetworkTemporarilyDisabled(WifiConfiguration config, int disableReason) {
            if (disableReason == DISABLED_NO_INTERNET_TEMPORARY) return;
            if (config.networkId == mTargetNetworkId || config.networkId == mLastNetworkId) {
                // Disconnect and let autojoin reselect a new network
                sendMessage(CMD_DISCONNECT);
            }

        }

        @Override
        public void onNetworkPermanentlyDisabled(WifiConfiguration config, int disableReason) {
            // For DISABLED_NO_INTERNET_PERMANENT we do not need to remove the network
            // because supplicant won't be trying to reconnect. If this is due to a
            // preventAutomaticReconnect request from ConnectivityService, that service
            // will disconnect as appropriate.
            if (disableReason == DISABLED_NO_INTERNET_PERMANENT) return;
            if (config.networkId == mTargetNetworkId || config.networkId == mLastNetworkId) {
                // Disconnect and let autojoin reselect a new network
                sendMessage(CMD_DISCONNECT);
            }
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
            setLogRecSize(mActivityManager.isLowRamDevice()
                    ? NUM_LOG_RECS_VERBOSE_LOW_MEMORY : NUM_LOG_RECS_VERBOSE);
        } else {
            mVerboseLoggingEnabled = false;
            setLogRecSize(NUM_LOG_RECS_NORMAL);
        }
        setSupplicantLogLevel();
        mCountryCode.enableVerboseLogging(verbose);
        mWifiScoreReport.enableVerboseLogging(mVerboseLoggingEnabled);
        mWifiDiagnostics.enableVerboseLogging(mVerboseLoggingEnabled);
        mWifiMonitor.enableVerboseLogging(verbose);
        mWifiNative.enableVerboseLogging(verbose);
        mWifiConfigManager.enableVerboseLogging(verbose);
        mSupplicantStateTracker.enableVerboseLogging(verbose);
        mPasspointManager.enableVerboseLogging(verbose);
        mNetworkFactory.enableVerboseLogging(verbose);
        mLinkProbeManager.enableVerboseLogging(mVerboseLoggingEnabled);
        mMboOceController.enableVerboseLogging(mVerboseLoggingEnabled);
        mWifiScoreCard.enableVerboseLogging(mVerboseLoggingEnabled);
        mWifiHealthMonitor.enableVerboseLogging(mVerboseLoggingEnabled);
        mWifiInjector.getThroughputPredictor().enableVerboseLogging(mVerboseLoggingEnabled);
        mWifiDataStall.enableVerboseLogging(mVerboseLoggingEnabled);
        mWifiConnectivityManager.enableVerboseLogging(mVerboseLoggingEnabled);
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
    private void connectToUserSelectNetwork(int netId, int uid, boolean forceReconnect) {
        logd("connectToUserSelectNetwork netId " + netId + ", uid " + uid
                + ", forceReconnect = " + forceReconnect);
        if (!forceReconnect && (mLastNetworkId == netId || mTargetNetworkId == netId)) {
            // We're already connecting/connected to the user specified network, don't trigger a
            // reconnection unless it was forced.
            logi("connectToUserSelectNetwork already connecting/connected=" + netId);
        } else {
            mWifiConnectivityManager.prepareForForcedConnection(netId);
            if (uid == Process.SYSTEM_UID) {
                mWifiMetrics.setNominatorForNetwork(netId,
                        WifiMetricsProto.ConnectionEvent.NOMINATOR_MANUAL);
            }
            startConnectToNetwork(netId, uid, SUPPLICANT_BSSID_ANY);
        }
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

    /**
     * Check if a Wi-Fi band is supported
     *
     * @param band A value from {@link WifiScanner.WIFI_BAND_5_GHZ} or
     *        {@link WifiScanner.WIFI_BAND_6_GHZ}
     * @return {@code true} if band is supported, {@code false} otherwise.
     */
    public boolean isWifiBandSupported(int band) {
        if (band == WifiScanner.WIFI_BAND_5_GHZ) {
            // In some cases, devices override the value by the overlay configs
            if (mContext.getResources().getBoolean(R.bool.config_wifi5ghzSupport)) {
                return true;
            }
            return (mWifiNative.getChannelsForBand(WifiScanner.WIFI_BAND_5_GHZ).length > 0);
        }

        if (band == WifiScanner.WIFI_BAND_6_GHZ) {
            if (mContext.getResources().getBoolean(R.bool.config_wifi6ghzSupport)) {
                return true;
            }
            return (mWifiNative.getChannelsForBand(WifiScanner.WIFI_BAND_6_GHZ).length > 0);
        }

        return false;
    }

    /**
     * Update interface capabilities
     * This method is used to update some of interface capabilities defined in overlay
     *
     * @param ifaceName name of interface to update
     */
    private void updateInterfaceCapabilities(@NonNull String ifaceName) {
        DeviceWiphyCapabilities cap = mWifiNative.getDeviceWiphyCapabilities(ifaceName);
        if (cap != null) {
            // Some devices don't have support of 11ax indicated by the chip,
            // so an override config value is used
            if (mContext.getResources().getBoolean(R.bool.config_wifi11axSupportOverride)) {
                cap.setWifiStandardSupport(ScanResult.WIFI_STANDARD_11AX, true);
            }

            mWifiNative.setDeviceWiphyCapabilities(ifaceName, cap);
        }
    }

    /**
     * Check if a Wi-Fi standard is supported
     *
     * @param standard A value from {@link ScanResult}'s {@code WIFI_STANDARD_}
     * @return {@code true} if standard is supported, {@code false} otherwise.
     */
    public boolean isWifiStandardSupported(@WifiStandard int standard) {
        return mWifiNative.isWifiStandardSupported(mInterfaceName, standard);
    }

    private byte[] getDstMacForKeepalive(KeepalivePacketData packetData)
            throws InvalidPacketException {
        try {
            InetAddress gateway = NetUtils.selectBestRoute(
                    mLinkProperties.getRoutes(), packetData.getDstAddress()).getGateway();
            String dstMacStr = macAddressFromRoute(gateway.getHostAddress());
            return NativeUtil.macAddressToByteArray(dstMacStr);
        } catch (NullPointerException | IllegalArgumentException e) {
            throw new InvalidPacketException(InvalidPacketException.ERROR_INVALID_IP_ADDRESS);
        }
    }

    private static int getEtherProtoForKeepalive(KeepalivePacketData packetData)
            throws InvalidPacketException {
        if (packetData.getDstAddress() instanceof Inet4Address) {
            return OsConstants.ETH_P_IP;
        } else if (packetData.getDstAddress() instanceof Inet6Address) {
            return OsConstants.ETH_P_IPV6;
        } else {
            throw new InvalidPacketException(InvalidPacketException.ERROR_INVALID_IP_ADDRESS);
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
            return e.getError();
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
     * @return DhcpResultsParcelable current results
     */
    @NonNull
    public DhcpResultsParcelable syncGetDhcpResultsParcelable() {
        synchronized (mDhcpResultsParcelableLock) {
            return mDhcpResultsParcelable;
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
                updateInterfaceCapabilities(ifaceName);
                transitionTo(mDisconnectedState);
                mWifiScoreReport.setInterfaceName(ifaceName);
            } else {
                Log.e(TAG, "supposed to enter connect mode, but iface is null -> DefaultState");
                transitionTo(mDefaultState);
            }
        }
        // use the CMD_SET_OPERATIONAL_MODE to force the transitions before other messages are
        // handled.
        sendMessageAtFrontOfQueue(CMD_SET_OPERATIONAL_MODE);
    }

    private void checkAbnormalConnectionFailureAndTakeBugReport(String ssid) {
        if (mWifiInjector.getDeviceConfigFacade()
                .isAbnormalConnectionFailureBugreportEnabled()) {
            int reasonCode = mWifiScoreCard.detectAbnormalConnectionFailure(ssid);
            if (reasonCode != WifiHealthMonitor.REASON_NO_FAILURE) {
                String bugTitle = "Wi-Fi BugReport";
                String bugDetail = "Detect abnormal "
                        + WifiHealthMonitor.FAILURE_REASON_NAME[reasonCode];
                takeBugReport(bugTitle, bugDetail);
            }
        }
    }

    private void checkAbnormalDisconnectionAndTakeBugReport() {
        if (mWifiInjector.getDeviceConfigFacade()
                .isAbnormalDisconnectionBugreportEnabled()) {
            int reasonCode = mWifiScoreCard.detectAbnormalDisconnection();
            if (reasonCode != WifiHealthMonitor.REASON_NO_FAILURE) {
                String bugTitle = "Wi-Fi BugReport";
                String bugDetail = "Detect abnormal "
                        + WifiHealthMonitor.FAILURE_REASON_NAME[reasonCode];
                takeBugReport(bugTitle, bugDetail);
            }
        }
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
     * Deauthenticate and set the re-authentication hold off time for the current network
     * @param holdoff hold off time in milliseconds
     * @param ess set if the hold off pertains to an ESS rather than a BSS
     */
    public void deauthenticateNetwork(AsyncChannel channel, long holdoff, boolean ess) {
        // TODO: This needs an implementation
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
    public synchronized void resetSimAuthNetworks(@ResetSimReason int resetReason) {
        sendMessage(CMD_RESET_SIM_NETWORKS, resetReason);
    }

    /**
     * Should only be used internally.
     * External callers should use {@link #syncGetCurrentNetwork(AsyncChannel)}.
     */
    private Network getCurrentNetwork() {
        if (mNetworkAgent != null) {
            return mNetworkAgent.getNetwork();
        } else {
            return null;
        }
    }

    /**
     * Get Network object of currently connected wifi network, or null if not connected.
     * @return Network object of current wifi network
     */
    public Network syncGetCurrentNetwork(AsyncChannel channel) {
        Message resultMsg = channel.sendMessageSynchronously(CMD_GET_CURRENT_NETWORK);
        if (messageIsNull(resultMsg)) return null;
        Network network = (Network) resultMsg.obj;
        resultMsg.recycle();
        return network;
    }

    /**
     * Enable TDLS for a specific MAC address
     */
    public void enableTdls(String remoteMacAddress, boolean enable) {
        int enabler = enable ? 1 : 0;
        sendMessage(CMD_ENABLE_TDLS, enabler, 0, remoteMacAddress);
    }

    /**
     * Send a message indicating bluetooth adapter state changed, e.g., turn on or ff
     */
    public void sendBluetoothAdapterStateChange(int state) {
        sendMessage(CMD_BLUETOOTH_ADAPTER_STATE_CHANGE, state, 0);
    }

    /**
     * Send a message indicating bluetooth adapter connection state changed, e.g., connected
     * or disconnected. Note that turning off BT after pairing success keeps connection state in
     * connected state.
     */
    public void sendBluetoothAdapterConnectionStateChange(int state) {
        sendMessage(CMD_BLUETOOTH_ADAPTER_CONNECTION_STATE_CHANGE, state, 0);
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

    private static String dhcpResultsParcelableToString(DhcpResultsParcelable dhcpResults) {
        return new StringBuilder()
                .append("baseConfiguration ").append(dhcpResults.baseConfiguration)
                .append("leaseDuration ").append(dhcpResults.leaseDuration)
                .append("mtu ").append(dhcpResults.mtu)
                .append("serverAddress ").append(dhcpResults.serverAddress)
                .append("serverHostName ").append(dhcpResults.serverHostName)
                .append("vendorInfo ").append(dhcpResults.vendorInfo)
                .toString();
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        super.dump(fd, pw, args);
        mSupplicantStateTracker.dump(fd, pw, args);
        pw.println("mLinkProperties " + mLinkProperties);
        pw.println("mWifiInfo " + mWifiInfo);
        pw.println("mDhcpResultsParcelable "
                + dhcpResultsParcelableToString(mDhcpResultsParcelable));
        pw.println("mLastSignalLevel " + mLastSignalLevel);
        pw.println("mLastBssid " + mLastBssid);
        pw.println("mLastNetworkId " + mLastNetworkId);
        pw.println("mLastSubId " + mLastSubId);
        pw.println("mLastSimBasedConnectionCarrierName " + mLastSimBasedConnectionCarrierName);
        pw.println("mOperationalMode " + mOperationalMode);
        pw.println("mSuspendOptimizationsEnabled " + mContext.getResources().getBoolean(
                R.bool.config_wifiSuspendOptimizationsEnabled));
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
        mWifiHealthMonitor.dump(fd, pw, args);
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
            case CMD_CONNECT_NETWORK:
            case CMD_SAVE_NETWORK: {
                NetworkUpdateResult result = (NetworkUpdateResult) msg.obj;
                sb.append(" ");
                sb.append(Integer.toString(result.netId));
                sb.append(" ");
                sb.append(Integer.toString(msg.arg2));
                config = mWifiConfigManager.getConfiguredNetwork(result.netId);
                if (config != null) {
                    sb.append(" ").append(config.getKey());
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
                    sb.append(" cuid=").append(config.creatorUid);
                    sb.append(" suid=").append(config.lastUpdateUid);
                }
                break;
            }
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
            case WifiMonitor.FILS_NETWORK_CONNECTION_EVENT:
            case WifiMonitor.NETWORK_CONNECTION_EVENT:
                sb.append(" ");
                sb.append(Integer.toString(msg.arg1));
                sb.append(" ");
                sb.append(Integer.toString(msg.arg2));
                sb.append(" ").append(mLastBssid);
                sb.append(" nid=").append(mLastNetworkId);
                config = getCurrentWifiConfiguration();
                if (config != null) {
                    sb.append(" ").append(config.getKey());
                }
                key = mWifiConfigManager.getLastSelectedNetworkConfigKey();
                if (key != null) {
                    sb.append(" last=").append(key);
                }
                break;
            case WifiMonitor.TARGET_BSSID_EVENT:
            case WifiMonitor.ASSOCIATED_BSSID_EVENT:
                sb.append(" ");
                sb.append(Integer.toString(msg.arg1));
                sb.append(" ");
                sb.append(Integer.toString(msg.arg2));
                if (msg.obj != null) {
                    sb.append(" BSSID=").append((String) msg.obj);
                }
                if (mTargetBssid != null) {
                    sb.append(" Target=").append(mTargetBssid);
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
                sb.append(" sc=").append(mWifiInfo.getScore());
                sb.append(" link=").append(mWifiInfo.getLinkSpeed());
                sb.append(String.format(" tx=%.1f,", mWifiInfo.getSuccessfulTxPacketsPerSecond()));
                sb.append(String.format(" %.1f,", mWifiInfo.getRetriedTxPacketsPerSecond()));
                sb.append(String.format(" %.1f ", mWifiInfo.getLostTxPacketsPerSecond()));
                sb.append(String.format(" rx=%.1f", mWifiInfo.getSuccessfulRxPacketsPerSecond()));
                sb.append(String.format(" bcn=%d", mRunningBeaconCount));
                report = reportOnTime();
                if (report != null) {
                    sb.append(" ").append(report);
                }
                sb.append(String.format(" score=%d", mWifiInfo.getScore()));
                break;
            case CMD_START_CONNECT:
                sb.append(" ");
                sb.append(Integer.toString(msg.arg1));
                sb.append(" ");
                sb.append(Integer.toString(msg.arg2));
                config = mWifiConfigManager.getConfiguredNetwork(msg.arg1);
                if (config != null) {
                    sb.append(" targetConfigKey=").append(config.getKey());
                    sb.append(" BSSID=" + config.BSSID);
                }
                if (mTargetBssid != null) {
                    sb.append(" targetBssid=").append(mTargetBssid);
                }
                sb.append(" roam=").append(Boolean.toString(mIsAutoRoaming));
                config = getCurrentWifiConfiguration();
                if (config != null) {
                    sb.append(" currentConfigKey=").append(config.getKey());
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
                if (mTargetBssid != null) {
                    sb.append(" ").append(mTargetBssid);
                }
                sb.append(" roam=").append(Boolean.toString(mIsAutoRoaming));
                sb.append(" fail count=").append(Integer.toString(mRoamFailCount));
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
            case CMD_IPV4_PROVISIONING_SUCCESS:
                sb.append(" ");
                sb.append(/* DhcpResultsParcelable */ msg.obj);
                break;
            case WifiMonitor.MBO_OCE_BSS_TM_HANDLING_DONE:
                BtmFrameData frameData = (BtmFrameData) msg.obj;
                if (frameData != null) {
                    sb.append(" ").append(frameData.toString());
                }
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
            case CMD_CONNECT_NETWORK:
                s = "CMD_CONNECT_NETWORK";
                break;
            case CMD_SAVE_NETWORK:
                s = "CMD_SAVE_NETWORK";
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
            case WifiMonitor.FILS_NETWORK_CONNECTION_EVENT:
                s = "FILS_NETWORK_CONNECTION_EVENT";
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
            case WifiMonitor.MBO_OCE_BSS_TM_HANDLING_DONE:
                s = "MBO_OCE_BSS_TM_HANDLING_DONE";
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
                    + " mSuspendOptimizationsEnabled="
                    + mContext.getResources().getBoolean(
                            R.bool.config_wifiSuspendOptimizationsEnabled)
                    + " state " + getCurrentState().getName());
        }
        enableRssiPolling(screenOn);
        if (mContext.getResources().getBoolean(R.bool.config_wifiSuspendOptimizationsEnabled)) {
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
                    + " -want " + mContext.getResources().getBoolean(
                            R.bool.config_wifiSuspendOptimizationsEnabled)
                    + " stack:" + Thread.currentThread().getStackTrace()[2].getMethodName()
                    + " - " + Thread.currentThread().getStackTrace()[3].getMethodName()
                    + " - " + Thread.currentThread().getStackTrace()[4].getMethodName()
                    + " - " + Thread.currentThread().getStackTrace()[5].getMethodName());
        }
        //mWifiNative.setSuspendOptimizations(enabled);

        if (enabled) {
            mSuspendOptNeedsDisabled &= ~reason;
            /* None of dhcp, screen or highperf need it disabled and user wants it enabled */
            if (mSuspendOptNeedsDisabled == 0
                    && mContext.getResources().getBoolean(
                            R.bool.config_wifiSuspendOptimizationsEnabled)) {
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
        WifiNl80211Manager.SignalPollResult pollResult = mWifiNative.signalPoll(mInterfaceName);
        if (pollResult == null) {
            return;
        }

        int newRssi = pollResult.currentRssiDbm;
        int newTxLinkSpeed = pollResult.txBitrateMbps;
        int newFrequency = pollResult.associationFrequencyMHz;
        int newRxLinkSpeed = pollResult.rxBitrateMbps;

        if (mVerboseLoggingEnabled) {
            logd("fetchRssiLinkSpeedAndFrequencyNative rssi=" + newRssi
                    + " TxLinkspeed=" + newTxLinkSpeed + " freq=" + newFrequency
                    + " RxLinkSpeed=" + newRxLinkSpeed);
        }

        if (newRssi > WifiInfo.INVALID_RSSI && newRssi < WifiInfo.MAX_RSSI) {
            /*
             * Positive RSSI is possible when devices are close(~0m apart) to each other.
             * And there are some driver/firmware implementation, where they avoid
             * reporting large negative rssi values by adding 256.
             * so adjust the valid rssi reports for such implementations.
             */
            if (newRssi > (WifiInfo.INVALID_RSSI + 256)) {
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
            int newSignalLevel = RssiUtil.calculateSignalLevel(mContext, newRssi);
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
        mWifiInfo.setLostTxPacketsPerSecond(0);
        mWifiInfo.setSuccessfulTxPacketsPerSecond(0);
        mWifiInfo.setRetriedTxPacketsRate(0);
        mWifiInfo.setSuccessfulRxPacketsPerSecond(0);
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

        if (mNetworkAgentState == DetailedState.CONNECTED) {
            // If anything has changed and we're already connected, send out a notification.
            // TODO: Update all callers to use NetworkCallbacks and delete this.
            sendLinkConfigurationChangedBroadcast();
        }

        if (mVerboseLoggingEnabled) {
            StringBuilder sb = new StringBuilder();
            sb.append("updateLinkProperties nid: " + mLastNetworkId);
            sb.append(" state: " + mNetworkAgentState);

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
        synchronized (mDhcpResultsParcelableLock) {
            mDhcpResultsParcelable = new DhcpResultsParcelable();
        }

        // Now clear the merged link properties.
        mLinkProperties.clear();
        if (mNetworkAgent != null) mNetworkAgent.sendLinkProperties(mLinkProperties);
    }

    private void sendRssiChangeBroadcast(final int newRssi) {
        mBatteryStatsManager.reportWifiRssiChanged(newRssi);
        WifiStatsLog.write(WifiStatsLog.WIFI_SIGNAL_STRENGTH_CHANGED,
                RssiUtil.calculateSignalLevel(mContext, newRssi));

        Intent intent = new Intent(WifiManager.RSSI_CHANGED_ACTION);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        intent.putExtra(WifiManager.EXTRA_NEW_RSSI, newRssi);
        mContext.sendBroadcastAsUser(intent, UserHandle.ALL,
                android.Manifest.permission.ACCESS_WIFI_STATE);
    }

    private void sendLinkConfigurationChangedBroadcast() {
        Intent intent = new Intent(WifiManager.ACTION_LINK_CONFIGURATION_CHANGED);
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
    private void sendNetworkChangeBroadcast(NetworkInfo.DetailedState state) {
        boolean hidden = false;

        if (mIsAutoRoaming) {
            // There is generally a confusion in the system about colluding
            // WiFi Layer 2 state (as reported by supplicant) and the Network state
            // which leads to multiple confusion.
            //
            // If link is roaming, we already have an IP address
            // as well we were connected and are doing L2 cycles of
            // reconnecting or renewing IP address to check that we still have it
            // This L2 link flapping should not be reflected into the Network state
            // which is the state of the WiFi Network visible to Layer 3 and applications
            // Note that once roaming is completed, we will
            // set the Network state to where it should be, or leave it as unchanged
            //
            hidden = true;
        }
        if (mVerboseLoggingEnabled) {
            log("setDetailed state, old ="
                    + mNetworkAgentState + " and new state=" + state
                    + " hidden=" + hidden);
        }
        if (hidden || state == mNetworkAgentState) return;
        mNetworkAgentState = state;

        Intent intent = new Intent(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        NetworkInfo networkInfo = makeNetworkInfo();
        intent.putExtra(WifiManager.EXTRA_NETWORK_INFO, networkInfo);
        //TODO(b/69974497) This should be non-sticky, but settings needs fixing first.
        mContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
    }

    private NetworkInfo makeNetworkInfo() {
        final NetworkInfo ni = new NetworkInfo(ConnectivityManager.TYPE_WIFI, 0, NETWORKTYPE, "");
        ni.setDetailedState(mNetworkAgentState, null, null);
        return ni;
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
            if (state == SupplicantState.ASSOCIATED) {
                updateWifiInfoAfterAssociation();
            }
        } else {
            // Reset parameters according to WifiInfo.reset()
            mWifiInfo.setNetworkId(WifiConfiguration.INVALID_NETWORK_ID);
            mWifiInfo.setBSSID(null);
            mWifiInfo.setSSID(null);
            mWifiInfo.setWifiStandard(ScanResult.WIFI_STANDARD_UNKNOWN);
        }
        updateLayer2Information();
        // SSID might have been updated, so call updateCapabilities
        updateCapabilities();

        WifiConfiguration config = getCurrentWifiConfiguration();
        if (config == null) {
            // If not connected, this should be non-null.
            config = getTargetWifiConfiguration();
        }
        if (config != null && config.networkId == mWifiInfo.getNetworkId()) {
            mWifiInfo.setEphemeral(config.ephemeral);
            mWifiInfo.setTrusted(config.trusted);
            mWifiInfo.setOsuAp(config.osu);
            if (config.fromWifiNetworkSpecifier || config.fromWifiNetworkSuggestion) {
                mWifiInfo.setRequestingPackageName(config.creatorName);
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
        mWifiScoreCard.noteSupplicantStateChanged(mWifiInfo);
        return state;
    }

    private void updateWifiInfoAfterAssociation() {
        WifiNative.ConnectionCapabilities capabilities =
                mWifiNative.getConnectionCapabilities(mInterfaceName);
        ThroughputPredictor throughputPredictor = mWifiInjector.getThroughputPredictor();
        int maxTxLinkSpeedMbps = throughputPredictor.predictMaxTxThroughput(capabilities);
        int maxRxLinkSpeedMbps = throughputPredictor.predictMaxRxThroughput(capabilities);
        mWifiInfo.setWifiStandard(capabilities.wifiStandard);
        mWifiInfo.setMaxSupportedTxLinkSpeedMbps(maxTxLinkSpeedMbps);
        mWifiInfo.setMaxSupportedRxLinkSpeedMbps(maxRxLinkSpeedMbps);
        mWifiMetrics.setConnectionMaxSupportedLinkSpeedMbps(
                maxTxLinkSpeedMbps, maxRxLinkSpeedMbps);
        mWifiDataStall.setConnectionCapabilities(capabilities);
        if (mVerboseLoggingEnabled) {
            StringBuilder sb = new StringBuilder();
            logd(sb.append("WifiStandard: ").append(capabilities.wifiStandard)
                    .append(" maxTxSpeed: ").append(maxTxLinkSpeedMbps)
                    .append(" maxRxSpeed: ").append(maxRxLinkSpeedMbps)
                    .toString());
        }
    }

    /**
     * Tells IpClient what BSSID, L2Key and GroupHint to use for IpMemoryStore.
     */
    private void updateLayer2Information() {
        if (mIpClient != null) {
            Pair<String, String> p = mWifiScoreCard.getL2KeyAndGroupHint(mWifiInfo);
            if (!p.equals(mLastL2KeyAndGroupHint)) {
                final MacAddress lastBssid = getCurrentBssid();
                final Layer2Information l2Information = new Layer2Information(
                        p.first, p.second, lastBssid);
                // Update current BSSID on IpClient side whenever l2Key and groupHint
                // pair changes (i.e. the initial connection establishment or L2 roaming
                // happened). If we have COMPLETED the roaming to a different BSSID, start
                // doing DNAv4/DNAv6 -style probing for on-link neighbors of interest (e.g.
                // routers/DNS servers/default gateway).
                if (mIpClient.updateLayer2Information(l2Information)) {
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
            log("handleNetworkDisconnect:"
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

        // Don't stop DHCP if Fils connection is in progress.
        if (mLastNetworkId != WifiConfiguration.INVALID_NETWORK_ID
                && mTargetNetworkId != WifiConfiguration.INVALID_NETWORK_ID
                && mLastNetworkId != mTargetNetworkId && mIpClientWithPreConnection) {
            if (mVerboseLoggingEnabled) {
                log("handleNetworkDisconnect: Don't stop IpClient as fils connection in progress: "
                        + " mLastNetworkId: " + mLastNetworkId
                        + " mTargetNetworkId" + mTargetNetworkId);
            }
        } else {
            stopDhcpSetup();
        }

        mWifiScoreReport.stopConnectedNetworkScorer();
        /* Reset data structures */
        mWifiScoreReport.reset();
        mWifiInfo.reset();
        /* Reset roaming parameters */
        mIsAutoRoaming = false;

        sendNetworkChangeBroadcast(DetailedState.DISCONNECTED);
        if (mNetworkAgent != null) {
            mNetworkAgent.unregister();
            mNetworkAgent = null;
        }

        /* Clear network properties */
        clearLinkProperties();

        mLastBssid = null;
        mLastLinkLayerStats = null;
        registerDisconnected();
        mLastNetworkId = WifiConfiguration.INVALID_NETWORK_ID;
        mLastSubId = SubscriptionManager.INVALID_SUBSCRIPTION_ID;
        mLastSimBasedConnectionCarrierName = null;
        checkAbnormalDisconnectionAndTakeBugReport();
        mWifiScoreCard.resetConnectionState();
        mWifiDataStall.reset();
        updateLayer2Information();
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
            mWifiP2pChannel.sendMessage(msg);
        } else {
            // If the p2p service is not running, we can proceed directly.
            sendMessage(CMD_PRE_DHCP_ACTION_COMPLETE);
        }
    }

    void addLayer2PacketsToHlpReq(List<Layer2PacketParcelable> packets) {
        List<Layer2PacketParcelable> mLayer2Packet = packets;
        if ((mLayer2Packet != null) && (mLayer2Packet.size() > 0)) {
            mWifiNative.flushAllHlp(mInterfaceName);

            for (int j = 0; j < mLayer2Packet.size(); j++) {
                byte [] bytes = mLayer2Packet.get(j).payload;
                byte [] payloadBytes = Arrays.copyOfRange(bytes, 12, bytes.length);
                MacAddress dstAddress = mLayer2Packet.get(j).dstMacAddress;

                mWifiNative.addHlpReq(mInterfaceName, dstAddress, payloadBytes);
            }
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
        int overlapWithLastConnectionMs =
                mWifiMetrics.startConnectionEvent(config, targetBSSID, roamType);
        DeviceConfigFacade deviceConfigFacade = mWifiInjector.getDeviceConfigFacade();
        if (deviceConfigFacade.isOverlappingConnectionBugreportEnabled()
                && overlapWithLastConnectionMs
                > deviceConfigFacade.getOverlappingConnectionDurationThresholdMs()) {
            String bugTitle = "Wi-Fi BugReport";
            String bugDetail = "Detect abnormal overlapping connection";
            takeBugReport(bugTitle, bugDetail);
        }
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
        // if connected, this should be non-null.
        WifiConfiguration configuration = getCurrentWifiConfiguration();
        if (configuration == null) {
            // If not connected, this should be non-null.
            configuration = getTargetWifiConfiguration();
        }

        String bssid = mLastBssid == null ? mTargetBssid : mLastBssid;
        String ssid = mWifiInfo.getSSID();
        if (WifiManager.UNKNOWN_SSID.equals(ssid)) {
            ssid = getTargetSsid();
        }
        if (level2FailureCode != WifiMetrics.ConnectionEvent.FAILURE_NONE) {
            int blocklistReason = convertToBssidBlocklistMonitorFailureReason(
                    level2FailureCode, level2FailureReason);
            if (blocklistReason != -1) {
                int networkId = (configuration == null) ? WifiConfiguration.INVALID_NETWORK_ID
                        : configuration.networkId;
                int scanRssi = mWifiConfigManager.findScanRssi(networkId,
                        mWifiHealthMonitor.getScanRssiValidTimeMs());
                mWifiScoreCard.noteConnectionFailure(mWifiInfo, scanRssi, ssid, blocklistReason);
                checkAbnormalConnectionFailureAndTakeBugReport(ssid);
                boolean isLowRssi = false;
                int sufficientRssi = getSufficientRssi(networkId, bssid);
                if (scanRssi != WifiInfo.INVALID_RSSI && sufficientRssi != WifiInfo.INVALID_RSSI) {
                    isLowRssi = scanRssi < sufficientRssi;
                }
                mBssidBlocklistMonitor.handleBssidConnectionFailure(bssid, ssid, blocklistReason,
                        isLowRssi);
            }
        }

        if (configuration != null
                && configuration.carrierId != TelephonyManager.UNKNOWN_CARRIER_ID) {
            if (level2FailureCode == WifiMetrics.ConnectionEvent.FAILURE_NONE) {
                mWifiMetrics.incrementNumOfCarrierWifiConnectionSuccess();
            } else if (level2FailureCode
                            == WifiMetrics.ConnectionEvent.FAILURE_AUTHENTICATION_FAILURE
                    && level2FailureReason
                            != WifiMetricsProto.ConnectionEvent.AUTH_FAILURE_NONE) {
                mWifiMetrics.incrementNumOfCarrierWifiConnectionAuthFailure();
            } else {
                mWifiMetrics.incrementNumOfCarrierWifiConnectionNonAuthFailure();
            }
        }

        boolean isAssociationRejection = level2FailureCode
                == WifiMetrics.ConnectionEvent.FAILURE_ASSOCIATION_REJECTION;
        boolean isAuthenticationFailure = level2FailureCode
                == WifiMetrics.ConnectionEvent.FAILURE_AUTHENTICATION_FAILURE
                && level2FailureReason != WifiMetricsProto.ConnectionEvent.AUTH_FAILURE_WRONG_PSWD;
        if ((isAssociationRejection || isAuthenticationFailure)
                && mWifiConfigManager.isInFlakyRandomizationSsidHotlist(mTargetNetworkId)) {
            mConnectionFailureNotifier
                    .showFailedToConnectDueToNoRandomizedMacSupportNotification(mTargetNetworkId);
        }

        mWifiMetrics.endConnectionEvent(level2FailureCode, connectivityFailureCode,
                level2FailureReason);
        mWifiConnectivityManager.handleConnectionAttemptEnded(level2FailureCode, bssid, ssid);
        if (configuration != null) {
            mNetworkFactory.handleConnectionAttemptEnded(level2FailureCode, configuration);
            mWifiNetworkSuggestionsManager.handleConnectionAttemptEnded(
                    level2FailureCode, configuration, getCurrentBSSID());
            ScanResult candidate = configuration.getNetworkSelectionStatus().getCandidate();
            if (candidate != null && !TextUtils.equals(candidate.BSSID, getCurrentBSSID())) {
                mWifiMetrics.incrementNumBssidDifferentSelectionBetweenFrameworkAndFirmware();
            }
        }
        handleConnectionAttemptEndForDiagnostics(level2FailureCode);
    }

    /* If this connection attempt fails after 802.1x stage, clear intermediate cached data. */
    void clearNetworkCachedDataIfNeeded(WifiConfiguration config, int reason) {
        if (config == null) return;

        switch(reason) {
            case 14: // MICHAEL_MIC_FAILURE
            case 15: // 4WAY_HANDSHAKE_TIMEOUT
            case 16: // GROUP_KEY_UPDATE_TIMEOUT
            case 17: // IE_IN_4WAY_DIFFERS
            case 18: // GROUP_CIPHER_NOT_VALID
            case 19: // PAIRWISE_CIPHER_NOT_VALID
            case 20: // AKMP_NOT_VALID
            case 23: // IEEE_802_1X_AUTH_FAILED
            case 24: // CIPHER_SUITE_REJECTED
            case 29: // BAD_CIPHER_OR_AKM
            case 45: // PEERKEY_MISMATCH
            case 49: // INVALID_PMKID
                mWifiNative.removeNetworkCachedData(config.networkId);
                break;
            default:
                logi("Keep PMK cache for network disconnection reason " + reason);
                break;
        }
    }

    /**
     * Returns the sufficient RSSI for the frequency that this network is last seen on.
     */
    private int getSufficientRssi(int networkId, String bssid) {
        ScanDetailCache scanDetailCache =
                mWifiConfigManager.getScanDetailCacheForNetwork(networkId);
        if (scanDetailCache == null) {
            return WifiInfo.INVALID_RSSI;
        }
        ScanResult scanResult = scanDetailCache.getScanResult(bssid);
        if (scanResult == null) {
            return WifiInfo.INVALID_RSSI;
        }
        return mWifiInjector.getScoringParams().getSufficientRssi(scanResult.frequency);
    }

    private int convertToBssidBlocklistMonitorFailureReason(
            int level2FailureCode, int failureReason) {
        switch (level2FailureCode) {
            case WifiMetrics.ConnectionEvent.FAILURE_ASSOCIATION_TIMED_OUT:
                return BssidBlocklistMonitor.REASON_ASSOCIATION_TIMEOUT;
            case WifiMetrics.ConnectionEvent.FAILURE_ASSOCIATION_REJECTION:
                if (failureReason == WifiMetricsProto.ConnectionEvent
                        .ASSOCIATION_REJECTION_AP_UNABLE_TO_HANDLE_NEW_STA) {
                    return BssidBlocklistMonitor.REASON_AP_UNABLE_TO_HANDLE_NEW_STA;
                }
                return BssidBlocklistMonitor.REASON_ASSOCIATION_REJECTION;
            case WifiMetrics.ConnectionEvent.FAILURE_AUTHENTICATION_FAILURE:
                if (failureReason == WifiMetricsProto.ConnectionEvent.AUTH_FAILURE_WRONG_PSWD) {
                    return BssidBlocklistMonitor.REASON_WRONG_PASSWORD;
                } else if (failureReason == WifiMetricsProto.ConnectionEvent
                        .AUTH_FAILURE_EAP_FAILURE) {
                    return BssidBlocklistMonitor.REASON_EAP_FAILURE;
                }
                return BssidBlocklistMonitor.REASON_AUTHENTICATION_FAILURE;
            case WifiMetrics.ConnectionEvent.FAILURE_DHCP:
                return BssidBlocklistMonitor.REASON_DHCP_FAILURE;
            default:
                return -1;
        }
    }

    private void handleIPv4Success(DhcpResultsParcelable dhcpResults) {
        if (mVerboseLoggingEnabled) {
            logd("handleIPv4Success <" + dhcpResults.toString() + ">");
            logd("link address " + dhcpResults.baseConfiguration.getIpAddress());
        }

        Inet4Address addr;
        synchronized (mDhcpResultsParcelableLock) {
            mDhcpResultsParcelable = dhcpResults;
            addr = (Inet4Address) dhcpResults.baseConfiguration.getIpAddress().getAddress();
        }

        if (mIsAutoRoaming) {
            int previousAddress = mWifiInfo.getIpAddress();
            int newAddress = Inet4AddressUtils.inet4AddressToIntHTL(addr);
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
            mWifiConfigManager.updateRandomizedMacExpireTime(config, dhcpResults.leaseDuration);
            mBssidBlocklistMonitor.handleDhcpProvisioningSuccess(mLastBssid, mWifiInfo.getSSID());
        }

        // Set meteredHint if DHCP result says network is metered
        if (dhcpResults.vendorInfo != null && dhcpResults.vendorInfo.contains("ANDROID_METERED")) {
            mWifiInfo.setMeteredHint(true);
            mWifiMetrics.addMeteredStat(config, true);
        } else {
            mWifiMetrics.addMeteredStat(config, false);
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
        synchronized (mDhcpResultsParcelableLock) {
            mDhcpResultsParcelable = new DhcpResultsParcelable();
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
        if (network != null && network.getNetworkSelectionStatus().hasEverConnected()) {
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
            WifiP2pManager wifiP2pService = mContext.getSystemService(WifiP2pManager.class);

            if (wifiP2pService != null) {
                mWifiP2pChannel = new AsyncChannel();
                mWifiP2pChannel.connect(mContext, getHandler(),
                        wifiP2pService.getP2pStateMachineMessenger());
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
        String currentMacString = mWifiNative.getMacAddress(mInterfaceName);
        MacAddress currentMac = currentMacString == null ? null :
                MacAddress.fromString(currentMacString);
        MacAddress newMac = mWifiConfigManager.getRandomizedMacAndUpdateIfNeeded(config);
        if (!WifiConfiguration.isValidMacAddressForRandomization(newMac)) {
            Log.wtf(TAG, "Config generated an invalid MAC address");
        } else if (newMac.equals(currentMac)) {
            Log.d(TAG, "No changes in MAC address");
        } else {
            mWifiMetrics.logStaEvent(StaEvent.TYPE_MAC_CHANGE, config);
            boolean setMacSuccess =
                    mWifiNative.setMacAddress(mInterfaceName, newMac);
            if (setMacSuccess) {
                mWifiNative.removeNetworkCachedDataIfNeeded(config.networkId, newMac);
            }
            Log.d(TAG, "ConnectedMacRandomization SSID(" + config.getPrintableSsid()
                    + "). setMacAddress(" + newMac.toString() + ") from "
                    + currentMacString + " = " + setMacSuccess);
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
                mWifiNative.removeNetworkCachedDataIfNeeded(config.networkId, factoryMac);
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
        return mContext.getResources().getBoolean(
                R.bool.config_wifi_connected_mac_randomization_supported);
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

    /**
     * Helper method to check if WPA2 network upgrade feature is enabled in the framework
     *
     * @return boolean true if feature is enabled.
     */
    private boolean isWpa3SaeUpgradeEnabled() {
        return mContext.getResources().getBoolean(R.bool.config_wifiSaeUpgradeEnabled);
    }

    /**
     * Helper method to check if WPA2 network upgrade offload is enabled in the driver/fw
     *
     * @return boolean true if feature is enabled.
     */
    private boolean isWpa3SaeUpgradeOffloadEnabled() {
        return mContext.getResources().getBoolean(R.bool.config_wifiSaeUpgradeOffloadEnabled);
    }

    /********************************************************
     * HSM states
     *******************************************************/

    class DefaultState extends State {

        @Override
        public boolean processMessage(Message message) {
            boolean handleStatus = HANDLED;
            int callbackIdentifier = -1;
            int netId;
            boolean ok;

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
                    // If BT was connected and then turned off, there is no CONNECTION_STATE_CHANGE
                    // message. So we need to rely on STATE_CHANGE message to detect on->off
                    // transition and update mBluetoothConnectionActive status correctly.
                    mBluetoothConnectionActive = mBluetoothConnectionActive
                            && message.arg1 != BluetoothAdapter.STATE_OFF;
                    mWifiConnectivityManager.setBluetoothConnected(mBluetoothConnectionActive);
                    break;
                case CMD_BLUETOOTH_ADAPTER_CONNECTION_STATE_CHANGE:
                    // Transition to a non-disconnected state does correctly
                    // indicate BT is connected or being connected.
                    mBluetoothConnectionActive =
                            message.arg1 != BluetoothAdapter.STATE_DISCONNECTED;
                    mWifiConnectivityManager.setBluetoothConnected(mBluetoothConnectionActive);
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
                    mWifiNative.initialize();
                    break;
                case CMD_BOOT_COMPLETED:
                    // get other services that we need to manage
                    getAdditionalWifiServiceInterfaces();
                    registerNetworkFactory();
                    mSarManager.handleBootCompleted();
                    break;
                case CMD_SCREEN_STATE_CHANGED:
                    handleScreenStateChanged(message.arg1 != 0);
                    break;
                case CMD_DISCONNECT:
                case CMD_RECONNECT:
                case CMD_REASSOCIATE:
                case WifiMonitor.NETWORK_CONNECTION_EVENT:
                case WifiMonitor.FILS_NETWORK_CONNECTION_EVENT:
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
                case WifiMonitor.TARGET_BSSID_EVENT:
                case CMD_START_CONNECT:
                case CMD_START_ROAM:
                case WifiMonitor.ASSOCIATED_BSSID_EVENT:
                case CMD_UNWANTED_NETWORK:
                case CMD_DISCONNECTING_WATCHDOG_TIMER:
                case CMD_ROAM_WATCHDOG_TIMER:
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
                case CMD_CONNECT_NETWORK:
                    // wifi off, can't connect.
                    callbackIdentifier = message.arg2;
                    sendActionListenerFailure(callbackIdentifier, WifiManager.BUSY);
                    break;
                case CMD_SAVE_NETWORK:
                    // wifi off, nothing more to do here.
                    callbackIdentifier = message.arg2;
                    sendActionListenerSuccess(callbackIdentifier);
                    break;
                case CMD_GET_SUPPORTED_FEATURES:
                    long featureSet = (mWifiNative.getSupportedFeatureSet(mInterfaceName));
                    replyToMessage(message, message.what, Long.valueOf(featureSet));
                    break;
                case CMD_GET_LINK_LAYER_STATS:
                case CMD_GET_CURRENT_NETWORK:
                    // Not supported hence reply with null message.obj
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
                case CMD_START_SUBSCRIPTION_PROVISIONING:
                    replyToMessage(message, message.what, 0);
                    break;
                case CMD_IP_CONFIGURATION_SUCCESSFUL:
                case CMD_IP_CONFIGURATION_LOST:
                case CMD_IP_REACHABILITY_LOST:
                    mMessageHandlingStatus = MESSAGE_HANDLING_STATUS_DISCARD;
                    break;
                case CMD_START_IP_PACKET_OFFLOAD:
                    /* fall-through */
                case CMD_STOP_IP_PACKET_OFFLOAD:
                case CMD_ADD_KEEPALIVE_PACKET_FILTER_TO_APF:
                case CMD_REMOVE_KEEPALIVE_PACKET_FILTER_FROM_APF:
                    if (mNetworkAgent != null) {
                        mNetworkAgent.sendSocketKeepaliveEvent(message.arg1,
                                SocketKeepalive.ERROR_INVALID_NETWORK);
                    }
                    break;
                case CMD_START_RSSI_MONITORING_OFFLOAD:
                    mMessageHandlingStatus = MESSAGE_HANDLING_STATUS_DISCARD;
                    break;
                case CMD_STOP_RSSI_MONITORING_OFFLOAD:
                    mMessageHandlingStatus = MESSAGE_HANDLING_STATUS_DISCARD;
                    break;
                case CMD_QUERY_OSU_ICON:
                    /* reply with arg1 = 0 - it returns API failure to the calling app
                     * (message.what is not looked at)
                     */
                    replyToMessage(message, message.what);
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
            Log.wtf(getName(), "Timeout waiting for IpClient");
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
        mLastSubId = SubscriptionManager.INVALID_SUBSCRIPTION_ID;
        mLastSimBasedConnectionCarrierName = null;
        mLastSignalLevel = -1;
        if (isConnectedMacRandomizationEnabled()) {
            mWifiNative.setMacAddress(mInterfaceName, MacAddressUtils.createRandomUnicastAddress());
        }
        mWifiInfo.setMacAddress(mWifiNative.getMacAddress(mInterfaceName));
        // TODO: b/79504296 This broadcast has been deprecated and should be removed
        sendSupplicantConnectionChangedBroadcast(true);

        mWifiNative.setExternalSim(mInterfaceName, true);

        mCountryCode.setReadyForChange(true);

        mWifiDiagnostics.startPktFateMonitoring(mInterfaceName);
        mWifiDiagnostics.startLogging(mInterfaceName);

        mMboOceController.enable();
        mWifiDataStall.enablePhoneStateListener();

        /**
         * Enable bluetooth coexistence scan mode when bluetooth connection is active.
         * When this mode is on, some of the low-level scan parameters used by the
         * driver are changed to reduce interference with bluetooth
         */
        mWifiNative.setBluetoothCoexistenceScanMode(mInterfaceName, mBluetoothConnectionActive);
        sendNetworkChangeBroadcast(DetailedState.DISCONNECTED);

        // Disable legacy multicast filtering, which on some chipsets defaults to enabled.
        // Legacy IPv6 multicast filtering blocks ICMPv6 router advertisements which breaks IPv6
        // provisioning. Legacy IPv4 multicast filtering may be re-enabled later via
        // IpClient.Callback.setFallbackMulticastFilter()
        mWifiNative.stopFilteringMulticastV4Packets(mInterfaceName);
        mWifiNative.stopFilteringMulticastV6Packets(mInterfaceName);

        // Set the right suspend mode settings
        mWifiNative.setSuspendOptimizations(mInterfaceName, mSuspendOptNeedsDisabled == 0
                && mContext.getResources().getBoolean(
                        R.bool.config_wifiSuspendOptimizationsEnabled));

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
        handleNetworkDisconnect();
        // exiting supplicant started state is now only applicable to client mode
        mWifiDiagnostics.stopLogging(mInterfaceName);

        mMboOceController.disable();
        mWifiDataStall.disablePhoneStateListener();
        if (mIpClient != null && mIpClient.shutdown()) {
            // Block to make sure IpClient has really shut down, lest cleanup
            // race with, say, bringup code over in tethering.
            mIpClientCallbacks.awaitShutdown();
        }
        mCountryCode.setReadyForChange(false);
        mInterfaceName = null;
        mWifiScoreReport.setInterfaceName(null);
        // TODO: b/79504296 This broadcast has been deprecated and should be removed
        sendSupplicantConnectionChangedBroadcast(false);

        // Let's remove any ephemeral or passpoint networks.
        mWifiConfigManager.removeAllEphemeralOrPasspointConfiguredNetworks();
        mWifiConfigManager.clearUserTemporarilyDisabledList();
    }

    void registerConnected() {
        if (mLastNetworkId != WifiConfiguration.INVALID_NETWORK_ID) {
            mWifiConfigManager.updateNetworkAfterConnect(mLastNetworkId);
            // Notify PasspointManager of Passpoint network connected event.
            WifiConfiguration currentNetwork = getCurrentWifiConfiguration();
            if (currentNetwork != null && currentNetwork.isPasspoint()) {
                mPasspointManager.onPasspointNetworkConnected(currentNetwork.getKey());
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
            bssid = mTargetBssid;
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

    MacAddress getCurrentBssid() {
        MacAddress bssid = null;
        try {
            bssid = (mLastBssid != null) ? MacAddress.fromString(mLastBssid) : null;
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Invalid BSSID format: " + mLastBssid);
        }
        return bssid;
    }

    void connectToNetwork(WifiConfiguration config) {
        if ((config != null) && mWifiNative.connectToNetwork(mInterfaceName, config)) {
            mWifiInjector.getWifiLastResortWatchdog().noteStartConnectTime();
            mWifiMetrics.logStaEvent(StaEvent.TYPE_CMD_START_CONNECT, config);
            mLastConnectAttemptTimestamp = mClock.getWallClockMillis();
            mIsAutoRoaming = false;
            if (getCurrentState() != mDisconnectedState) {
                transitionTo(mDisconnectingState);
            }
        } else {
            loge("CMD_START_CONNECT Failed to start connection to network " + config);
            mTargetWifiConfiguration = null;
            stopIpClient();
            reportConnectionAttemptEnd(
                    WifiMetrics.ConnectionEvent.FAILURE_CONNECT_NETWORK_FAILED,
                    WifiMetricsProto.ConnectionEvent.HLF_NONE,
                    WifiMetricsProto.ConnectionEvent.FAILURE_REASON_UNKNOWN);
        }
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
            sendNetworkChangeBroadcast(DetailedState.DISCONNECTED);

            // Inform WifiConnectivityManager that Wifi is enabled
            mWifiConnectivityManager.setWifiEnabled(true);
            mNetworkFactory.setWifiState(true);
            // Inform metrics that Wifi is Enabled (but not yet connected)
            mWifiMetrics.setWifiState(WifiMetricsProto.WifiLog.WIFI_DISCONNECTED);
            mWifiMetrics.logStaEvent(StaEvent.TYPE_WIFI_ENABLED);
            mWifiScoreCard.noteSupplicantStateChanged(mWifiInfo);
            mWifiHealthMonitor.setWifiEnabled(true);
            mWifiDataStall.init();
        }

        @Override
        public void exit() {
            mOperationalMode = DISABLED_MODE;

            // Inform WifiConnectivityManager that Wifi is disabled
            mWifiConnectivityManager.setWifiEnabled(false);
            mNetworkFactory.setWifiState(false);
            // Inform metrics that Wifi is being disabled (Toggled, airplane enabled, etc)
            mWifiMetrics.setWifiState(WifiMetricsProto.WifiLog.WIFI_DISABLED);
            mWifiMetrics.logStaEvent(StaEvent.TYPE_WIFI_DISABLED);
            // Inform scorecard that wifi is being disabled
            mWifiScoreCard.noteWifiDisabled(mWifiInfo);

            if (!mWifiNative.removeAllNetworks(mInterfaceName)) {
                loge("Failed to remove networks on exiting connect mode");
            }
            mWifiInfo.reset();
            mWifiInfo.setSupplicantState(SupplicantState.DISCONNECTED);
            mWifiScoreCard.noteSupplicantStateChanged(mWifiInfo);
            mWifiHealthMonitor.setWifiEnabled(false);
            mWifiDataStall.reset();
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
            int callbackIdentifier = -1;

            int level2FailureReason =
                    WifiMetricsProto.ConnectionEvent.FAILURE_REASON_UNKNOWN;
            switch (message.what) {
                case WifiMonitor.ASSOCIATION_REJECTION_EVENT:
                    stopIpClient();
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
                        bssid = mTargetBssid;
                    } else if (mTargetBssid == SUPPLICANT_BSSID_ANY) {
                        // This is needed by BssidBlocklistMonitor to block continuously
                        // failing BSSIDs. Need to set here because mTargetBssid is currently
                        // not being set until association success.
                        mTargetBssid = bssid;
                    }
                    mWifiConfigManager.updateNetworkSelectionStatus(mTargetNetworkId,
                            WifiConfiguration.NetworkSelectionStatus
                            .DISABLED_ASSOCIATION_REJECTION);
                    mWifiConfigManager.setRecentFailureAssociationStatus(mTargetNetworkId,
                            reasonCode);

                    if (reasonCode == REASON_CODE_AP_UNABLE_TO_HANDLE_NEW_STA) {
                        level2FailureReason = WifiMetricsProto.ConnectionEvent
                                .ASSOCIATION_REJECTION_AP_UNABLE_TO_HANDLE_NEW_STA;
                    }
                    // If rejection occurred while Metrics is tracking a ConnnectionEvent, end it.
                    reportConnectionAttemptEnd(
                            timedOut
                                    ? WifiMetrics.ConnectionEvent.FAILURE_ASSOCIATION_TIMED_OUT
                                    : WifiMetrics.ConnectionEvent.FAILURE_ASSOCIATION_REJECTION,
                            WifiMetricsProto.ConnectionEvent.HLF_NONE,
                            level2FailureReason);
                    if (reasonCode != REASON_CODE_AP_UNABLE_TO_HANDLE_NEW_STA) {
                        mWifiInjector.getWifiLastResortWatchdog()
                                .noteConnectionFailureAndTriggerIfNeeded(
                                        getTargetSsid(), bssid,
                                        WifiLastResortWatchdog.FAILURE_CODE_ASSOCIATION);
                    }
                    mTargetNetworkId = WifiConfiguration.INVALID_NETWORK_ID;
                    break;
                case WifiMonitor.AUTHENTICATION_FAILURE_EVENT:
                    stopIpClient();
                    mWifiDiagnostics.captureBugReportData(
                            WifiDiagnostics.REPORT_REASON_AUTH_FAILURE);
                    int disableReason = WifiConfiguration.NetworkSelectionStatus
                            .DISABLED_AUTHENTICATION_FAILURE;
                    reasonCode = message.arg1;
                    WifiConfiguration targetedNetwork =
                            mWifiConfigManager.getConfiguredNetwork(mTargetNetworkId);
                    // Check if this is a permanent wrong password failure.
                    if (isPermanentWrongPasswordFailure(mTargetNetworkId, reasonCode)) {
                        disableReason = WifiConfiguration.NetworkSelectionStatus
                                .DISABLED_BY_WRONG_PASSWORD;
                        if (targetedNetwork != null) {
                            mWrongPasswordNotifier.onWrongPasswordError(
                                    targetedNetwork.SSID);
                        }
                    } else if (reasonCode == WifiManager.ERROR_AUTH_FAILURE_EAP_FAILURE) {
                        int errorCode = message.arg2;
                        if (targetedNetwork != null && targetedNetwork.enterpriseConfig != null
                                && targetedNetwork.enterpriseConfig.isAuthenticationSimBased()) {
                            mEapFailureNotifier.onEapFailure(errorCode, targetedNetwork);
                        }
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
                    if (reasonCode != WifiManager.ERROR_AUTH_FAILURE_WRONG_PSWD && reasonCode
                            != WifiManager.ERROR_AUTH_FAILURE_EAP_FAILURE) {
                        mWifiInjector.getWifiLastResortWatchdog()
                                .noteConnectionFailureAndTriggerIfNeeded(
                                        getTargetSsid(),
                                        (mLastBssid == null) ? mTargetBssid : mLastBssid,
                                        WifiLastResortWatchdog.FAILURE_CODE_AUTHENTICATION);
                    }
                    break;
                case WifiMonitor.SUPPLICANT_STATE_CHANGE_EVENT:
                    SupplicantState state = handleSupplicantStateChange(message);

                    // Supplicant can fail to report a NETWORK_DISCONNECTION_EVENT
                    // when authentication times out after a successful connection,
                    // we can figure this from the supplicant state. If supplicant
                    // state is DISCONNECTED, but the agent is not disconnected, we
                    // need to handle a disconnection
                    if (state == SupplicantState.DISCONNECTED && mNetworkAgent != null) {
                        if (mVerboseLoggingEnabled) {
                            log("Missed CTRL-EVENT-DISCONNECTED, disconnect");
                        }
                        handleNetworkDisconnect();
                        transitionTo(mDisconnectedState);
                    }

                    if (state == SupplicantState.COMPLETED) {
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
                case WifiMonitor.SUP_REQUEST_IDENTITY:
                    netId = message.arg2;
                    boolean identitySent = false;
                    // For SIM & AKA/AKA' EAP method Only, get identity from ICC
                    if (mTargetWifiConfiguration != null
                            && mTargetWifiConfiguration.networkId == netId
                            && mTargetWifiConfiguration.enterpriseConfig != null
                            && mTargetWifiConfiguration.enterpriseConfig
                                    .isAuthenticationSimBased()) {
                        // Pair<identity, encrypted identity>
                        Pair<String, String> identityPair = mWifiCarrierInfoManager
                                .getSimIdentity(mTargetWifiConfiguration);
                        if (identityPair != null && identityPair.first != null) {
                            Log.i(TAG, "SUP_REQUEST_IDENTITY: identityPair=["
                                    + ((identityPair.first.length() >= 7)
                                    ? identityPair.first.substring(0, 7 /* Prefix+PLMN ID */)
                                    + "****"
                                    : identityPair.first) + ", "
                                    + (!TextUtils.isEmpty(identityPair.second) ? identityPair.second
                                    : "<NONE>") + "]");
                            mWifiNative.simIdentityResponse(mInterfaceName, identityPair.first,
                                    identityPair.second);
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
                    mSentHLPs = false;

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
                    logd("CMD_START_CONNECT "
                            + " my state " + getCurrentState().getName()
                            + " nid=" + Integer.toString(netId)
                            + " roam=" + Boolean.toString(mIsAutoRoaming));
                    if (config == null) {
                        loge("CMD_START_CONNECT and no config, bail out...");
                        break;
                    }
                    mTargetNetworkId = netId;
                    // Update scorecard while there is still state from existing connection
                    int scanRssi = mWifiConfigManager.findScanRssi(netId,
                            mWifiHealthMonitor.getScanRssiValidTimeMs());
                    mWifiScoreCard.noteConnectionAttempt(mWifiInfo, scanRssi, config.SSID);
                    mBssidBlocklistMonitor.updateFirmwareRoamingConfiguration(config.SSID);

                    updateWifiConfigOnStartConnection(config, bssid);
                    reportConnectionAttemptStart(config, mTargetBssid,
                            WifiMetricsProto.ConnectionEvent.ROAM_UNRELATED);

                    String currentMacAddress = mWifiNative.getMacAddress(mInterfaceName);
                    mWifiInfo.setMacAddress(currentMacAddress);
                    Log.i(TAG, "Connecting with " + currentMacAddress + " as the mac address");

                    mTargetWifiConfiguration = config;
                    /* Check for FILS configuration again after updating the config */
                    if (config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.FILS_SHA256)
                            || config.allowedKeyManagement.get(
                            WifiConfiguration.KeyMgmt.FILS_SHA384)) {

                        boolean isIpClientStarted = startIpClient(config, true);
                        if (isIpClientStarted) {
                            mIpClientWithPreConnection = true;
                            break;
                        }
                    }
                    connectToNetwork(config);
                    break;
                case CMD_START_FILS_CONNECTION:
                    mWifiMetrics.incrementConnectRequestWithFilsAkmCount();
                    List<Layer2PacketParcelable> packets;
                    packets = (List<Layer2PacketParcelable>) message.obj;
                    if (mVerboseLoggingEnabled) {
                        Log.d(TAG, "Send HLP IEs to supplicant");
                    }
                    addLayer2PacketsToHlpReq(packets);
                    config = mTargetWifiConfiguration;
                    connectToNetwork(config);
                    break;
                case CMD_CONNECT_NETWORK:
                    callbackIdentifier = message.arg2;
                    result = (NetworkUpdateResult) message.obj;
                    netId = result.getNetworkId();
                    connectToUserSelectNetwork(
                            netId, message.sendingUid, result.hasCredentialChanged());
                    mWifiMetrics.logStaEvent(
                            StaEvent.TYPE_CONNECT_NETWORK,
                            mWifiConfigManager.getConfiguredNetwork(netId));
                    sendActionListenerSuccess(callbackIdentifier);
                    break;
                case CMD_SAVE_NETWORK:
                    callbackIdentifier = message.arg2;
                    result = (NetworkUpdateResult) message.obj;
                    netId = result.getNetworkId();
                    if (mWifiInfo.getNetworkId() == netId) {
                        if (result.hasCredentialChanged()) {
                            // The network credentials changed and we're connected to this network,
                            // start a new connection with the updated credentials.
                            logi("CMD_SAVE_NETWORK credential changed for nid="
                                    + netId + ". Reconnecting.");
                            startConnectToNetwork(netId, message.sendingUid, SUPPLICANT_BSSID_ANY);
                        } else {
                            if (result.hasProxyChanged()) {
                                if (mIpClient != null) {
                                    log("Reconfiguring proxy on connection");
                                    WifiConfiguration currentConfig = getCurrentWifiConfiguration();
                                    if (currentConfig != null) {
                                        mIpClient.setHttpProxy(currentConfig.getHttpProxy());
                                    } else {
                                        Log.w(TAG,
                                                "CMD_SAVE_NETWORK proxy change - but no current "
                                                        + "Wi-Fi config");
                                    }
                                }
                            }
                            if (result.hasIpChanged()) {
                                // The current connection configuration was changed
                                // We switched from DHCP to static or from static to DHCP, or the
                                // static IP address has changed.
                                log("Reconfiguring IP on connection");
                                WifiConfiguration currentConfig = getCurrentWifiConfiguration();
                                if (currentConfig != null) {
                                    transitionTo(mObtainingIpState);
                                } else {
                                    Log.w(TAG, "CMD_SAVE_NETWORK Ip change - but no current "
                                            + "Wi-Fi config");
                                }
                            }
                        }
                    } else if (mWifiInfo.getNetworkId() == WifiConfiguration.INVALID_NETWORK_ID
                            && result.hasCredentialChanged()) {
                        logi("CMD_SAVE_NETWORK credential changed for nid="
                                + netId + " while disconnected. Connecting.");
                        startConnectToNetwork(netId, message.sendingUid, SUPPLICANT_BSSID_ANY);
                    }
                    sendActionListenerSuccess(callbackIdentifier);
                    break;
                case WifiMonitor.ASSOCIATED_BSSID_EVENT:
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
                        // Update last associated BSSID
                        mLastBssid = someBssid;
                    }
                    handleStatus = NOT_HANDLED;
                    break;
                case WifiMonitor.FILS_NETWORK_CONNECTION_EVENT:
                    mWifiMetrics.incrementL2ConnectionThroughFilsAuthCount();
                    mSentHLPs = true;
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

                        // We need to get the updated pseudonym from supplicant for EAP-SIM/AKA/AKA'
                        if (config.enterpriseConfig != null
                                && config.enterpriseConfig.isAuthenticationSimBased()) {
                            mLastSubId = mWifiCarrierInfoManager.getBestMatchSubscriptionId(config);
                            mLastSimBasedConnectionCarrierName =
                                mWifiCarrierInfoManager.getCarrierNameforSubId(mLastSubId);
                            String anonymousIdentity =
                                    mWifiNative.getEapAnonymousIdentity(mInterfaceName);
                            if (!TextUtils.isEmpty(anonymousIdentity)
                                    && !WifiCarrierInfoManager
                                    .isAnonymousAtRealmIdentity(anonymousIdentity)) {
                                String decoratedPseudonym = mWifiCarrierInfoManager
                                        .decoratePseudonymWith3GppRealm(config,
                                                anonymousIdentity);
                                if (decoratedPseudonym != null) {
                                    anonymousIdentity = decoratedPseudonym;
                                }
                                if (mVerboseLoggingEnabled) {
                                    log("EAP Pseudonym: " + anonymousIdentity);
                                }
                                // Save the pseudonym only if it is a real one
                                config.enterpriseConfig.setAnonymousIdentity(anonymousIdentity);
                            } else {
                                // Clear any stored pseudonyms
                                config.enterpriseConfig.setAnonymousIdentity(null);
                            }
                            mWifiConfigManager.addOrUpdateNetwork(config, Process.WIFI_UID);
                        }
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
                    clearNetworkCachedDataIfNeeded(getTargetWifiConfiguration(), message.arg2);
                    handleNetworkDisconnect();
                    transitionTo(mDisconnectedState);
                    break;
                case CMD_QUERY_OSU_ICON:
                    mPasspointManager.queryPasspointIcon(
                            ((Bundle) message.obj).getLong(EXTRA_OSU_ICON_QUERY_BSSID),
                            ((Bundle) message.obj).getString(EXTRA_OSU_ICON_QUERY_FILENAME));
                    break;
                case WifiMonitor.TARGET_BSSID_EVENT:
                    // Trying to associate to this BSSID
                    if (message.obj != null) {
                        mTargetBssid = (String) message.obj;
                    }
                    break;
                case CMD_GET_LINK_LAYER_STATS:
                    WifiLinkLayerStats stats = getWifiLinkLayerStats();
                    replyToMessage(message, message.what, stats);
                    break;
                case CMD_RESET_SIM_NETWORKS:
                    log("resetting EAP-SIM/AKA/AKA' networks since SIM was changed");
                    int resetReason = message.arg1;
                    if (resetReason == RESET_SIM_REASON_SIM_INSERTED) {
                        // whenever a SIM is inserted clear all SIM related notifications
                        mSimRequiredNotifier.dismissSimRequiredNotification();
                    } else {
                        mWifiConfigManager.resetSimNetworks();
                    }
                    if (resetReason != RESET_SIM_REASON_DEFAULT_DATA_SIM_CHANGED) {
                        mWifiNetworkSuggestionsManager.resetCarrierPrivilegedApps();
                    }
                    break;
                case CMD_BLUETOOTH_ADAPTER_STATE_CHANGE:
                    // If BT was connected and then turned off, there is no CONNECTION_STATE_CHANGE
                    // message. So we need to rely on STATE_CHANGE message to detect on->off
                    // transition and update mBluetoothConnectionActive status correctly.
                    mBluetoothConnectionActive = mBluetoothConnectionActive
                            && message.arg1 != BluetoothAdapter.STATE_OFF;
                    mWifiNative.setBluetoothCoexistenceScanMode(
                            mInterfaceName, mBluetoothConnectionActive);
                    mWifiConnectivityManager.setBluetoothConnected(mBluetoothConnectionActive);
                    break;
                case CMD_BLUETOOTH_ADAPTER_CONNECTION_STATE_CHANGE:
                    // Transition to a non-disconnected state does correctly
                    // indicate BT is connected or being connected.
                    mBluetoothConnectionActive =
                            message.arg1 != BluetoothAdapter.STATE_DISCONNECTED;
                    mWifiNative.setBluetoothCoexistenceScanMode(
                            mInterfaceName, mBluetoothConnectionActive);
                    mWifiConnectivityManager.setBluetoothConnected(mBluetoothConnectionActive);
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
                        mNetworkAgent.sendSocketKeepaliveEvent(slot, ret);
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
                case WifiMonitor.MBO_OCE_BSS_TM_HANDLING_DONE:
                    handleBssTransitionRequest((BtmFrameData) message.obj);
                    break;
                case CMD_CONFIG_ND_OFFLOAD:
                    final boolean enabled = (message.arg1 > 0);
                    mWifiNative.configureNeighborDiscoveryOffload(mInterfaceName, enabled);
                    break;
                case CMD_PRE_DHCP_ACTION:
                case CMD_PRE_DHCP_ACTION_COMPLETE:
                case CMD_POST_DHCP_ACTION:
                case CMD_IPV4_PROVISIONING_SUCCESS:
                case CMD_IP_CONFIGURATION_SUCCESSFUL:
                case CMD_IPV4_PROVISIONING_FAILURE:
                    handleStatus = handleL3MessagesWhenNotConnected(message);
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

    private boolean handleL3MessagesWhenNotConnected(Message message) {
        boolean handleStatus = HANDLED;

        if (!mIpClientWithPreConnection) {
            return NOT_HANDLED;
        }

        switch (message.what) {
            case CMD_PRE_DHCP_ACTION:
                handlePreDhcpSetup();
                break;
            case CMD_PRE_DHCP_ACTION_COMPLETE:
                if (mIpClient != null) {
                    mIpClient.completedPreDhcpAction();
                }
                break;
            case CMD_IPV4_PROVISIONING_FAILURE:
                stopDhcpSetup();
                deferMessage(message);
                break;
            case CMD_POST_DHCP_ACTION:
            case CMD_IPV4_PROVISIONING_SUCCESS:
            case CMD_IP_CONFIGURATION_SUCCESSFUL:
                deferMessage(message);
                break;
            default:
                return NOT_HANDLED;
        }

        return handleStatus;
    }

    private WifiNetworkAgentSpecifier createNetworkAgentSpecifier(
            @NonNull WifiConfiguration currentWifiConfiguration, @Nullable String currentBssid) {
        currentWifiConfiguration.BSSID = currentBssid;
        WifiNetworkAgentSpecifier wns =
                new WifiNetworkAgentSpecifier(currentWifiConfiguration);
        return wns;
    }

    private NetworkCapabilities getCapabilities(WifiConfiguration currentWifiConfiguration) {
        final NetworkCapabilities.Builder builder =
                new NetworkCapabilities.Builder(mNetworkCapabilitiesFilter);
        // MatchAllNetworkSpecifier set in the mNetworkCapabilitiesFilter should never be set in the
        // agent's specifier.
        builder.setNetworkSpecifier(null);
        if (currentWifiConfiguration == null) {
            return builder.build();
        }

        if (mWifiInfo.isTrusted()) {
            builder.addCapability(NetworkCapabilities.NET_CAPABILITY_TRUSTED);
        } else {
            builder.removeCapability(NetworkCapabilities.NET_CAPABILITY_TRUSTED);
        }

        builder.setOwnerUid(currentWifiConfiguration.creatorUid);
        builder.setAdministratorUids(new int[] {currentWifiConfiguration.creatorUid});

        if (!WifiConfiguration.isMetered(currentWifiConfiguration, mWifiInfo)) {
            builder.addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
        } else {
            builder.removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
        }

        if (mWifiInfo.getRssi() != WifiInfo.INVALID_RSSI) {
            builder.setSignalStrength(mWifiInfo.getRssi());
        } else {
            builder.setSignalStrength(NetworkCapabilities.SIGNAL_STRENGTH_UNSPECIFIED);
        }

        if (currentWifiConfiguration.osu) {
            builder.removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        }

        if (!mWifiInfo.getSSID().equals(WifiManager.UNKNOWN_SSID)) {
            builder.setSsid(mWifiInfo.getSSID());
        }
        Pair<Integer, String> specificRequestUidAndPackageName =
                mNetworkFactory.getSpecificNetworkRequestUidAndPackageName(
                        currentWifiConfiguration);
        // There is an active specific request.
        if (specificRequestUidAndPackageName.first != Process.INVALID_UID) {
            // Remove internet capability.
            builder.removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
            // Fill up the uid/packageName for this connection.
            builder.setRequestorUid(specificRequestUidAndPackageName.first);
            builder.setRequestorPackageName(specificRequestUidAndPackageName.second);
            // Fill up the network agent specifier for this connection.
            builder.setNetworkSpecifier(createNetworkAgentSpecifier(
                    currentWifiConfiguration, getCurrentBSSID()));
        }
        updateLinkBandwidth(builder);
        return builder.build();
    }

    private void updateLinkBandwidth(NetworkCapabilities.Builder networkCapabilitiesBuilder) {
        int rssiDbm = mWifiInfo.getRssi();
        int txTputKbps = INVALID_THROUGHPUT;
        int rxTputKbps = INVALID_THROUGHPUT;
        // If RSSI is available, check if throughput is available
        if (rssiDbm != WifiInfo.INVALID_RSSI && mWifiDataStall != null) {
            txTputKbps = mWifiDataStall.getTxThroughputKbps();
            rxTputKbps = mWifiDataStall.getRxThroughputKbps();
        }
        if (txTputKbps == INVALID_THROUGHPUT && rxTputKbps != INVALID_THROUGHPUT) {
            txTputKbps = rxTputKbps;
        } else if (rxTputKbps == INVALID_THROUGHPUT && txTputKbps != INVALID_THROUGHPUT) {
            rxTputKbps = txTputKbps;
        } else if (txTputKbps == INVALID_THROUGHPUT && rxTputKbps == INVALID_THROUGHPUT) {
            int maxTxLinkSpeedMbps = mWifiInfo.getMaxSupportedTxLinkSpeedMbps();
            int maxRxLinkSpeedMbps = mWifiInfo.getMaxSupportedRxLinkSpeedMbps();
            if (maxTxLinkSpeedMbps > 0) {
                txTputKbps = maxTxLinkSpeedMbps * 1000;
            }
            if (maxRxLinkSpeedMbps > 0) {
                rxTputKbps = maxRxLinkSpeedMbps * 1000;
            }
        }
        if (mVerboseLoggingEnabled) {
            logd("tx tput in kbps: " + txTputKbps);
            logd("rx tput in kbps: " + rxTputKbps);
        }
        if (txTputKbps > 0) {
            networkCapabilitiesBuilder.setLinkUpstreamBandwidthKbps(txTputKbps);
        }
        if (rxTputKbps > 0) {
            networkCapabilitiesBuilder.setLinkDownstreamBandwidthKbps(rxTputKbps);
        }
    }

    /**
     * Method to update network capabilities from the current WifiConfiguration.
     */
    public void updateCapabilities() {
        updateCapabilities(getCurrentWifiConfiguration());
    }

    private void updateCapabilities(WifiConfiguration currentWifiConfiguration) {
        updateCapabilities(getCapabilities(currentWifiConfiguration));
    }

    private void updateCapabilities(NetworkCapabilities networkCapabilities) {
        if (mNetworkAgent == null) {
            return;
        }
        mNetworkAgent.sendNetworkCapabilities(networkCapabilities);
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
                        mWifiCarrierInfoManager.resetCarrierKeysForImsiEncryption(targetedNetwork);
                    }
                    break;

                default:
                    // Do Nothing
            }
        }
    }

    private class WifiNetworkAgent extends NetworkAgent {
        WifiNetworkAgent(Context c, Looper l, String tag, NetworkCapabilities nc, LinkProperties lp,
                int score, NetworkAgentConfig config, NetworkProvider provider) {
            super(c, l, tag, nc, lp, score, config, provider);
            register();
        }
        private int mLastNetworkStatus = -1; // To detect when the status really changes

        @Override
        public void onNetworkUnwanted() {
            // Ignore if we're not the current networkAgent.
            if (this != mNetworkAgent) return;
            if (mVerboseLoggingEnabled) {
                logd("WifiNetworkAgent -> Wifi unwanted score " + Integer.toString(
                        mWifiInfo.getScore()));
            }
            unwantedNetwork(NETWORK_STATUS_UNWANTED_DISCONNECT);
        }

        @Override
        public void onValidationStatus(int status, @Nullable Uri redirectUri) {
            if (this != mNetworkAgent) return;
            if (status == mLastNetworkStatus) return;
            mLastNetworkStatus = status;
            if (status == NetworkAgent.VALIDATION_STATUS_NOT_VALID) {
                if (mVerboseLoggingEnabled) {
                    logd("WifiNetworkAgent -> Wifi networkStatus invalid, score="
                            + Integer.toString(mWifiInfo.getScore()));
                }
                unwantedNetwork(NETWORK_STATUS_UNWANTED_VALIDATION_FAILED);
            } else if (status == NetworkAgent.VALIDATION_STATUS_VALID) {
                if (mVerboseLoggingEnabled) {
                    logd("WifiNetworkAgent -> Wifi networkStatus valid, score= "
                            + Integer.toString(mWifiInfo.getScore()));
                }
                mWifiMetrics.logStaEvent(StaEvent.TYPE_NETWORK_AGENT_VALID_NETWORK);
                doNetworkStatus(status);
            }
        }

        @Override
        public void onSaveAcceptUnvalidated(boolean accept) {
            if (this != mNetworkAgent) return;
            ClientModeImpl.this.sendMessage(CMD_ACCEPT_UNVALIDATED, accept ? 1 : 0);
        }

        @Override
        public void onStartSocketKeepalive(int slot, @NonNull Duration interval,
                @NonNull KeepalivePacketData packet) {
            if (this != mNetworkAgent) return;
            ClientModeImpl.this.sendMessage(
                    CMD_START_IP_PACKET_OFFLOAD, slot, (int) interval.getSeconds(), packet);
        }

        @Override
        public void onStopSocketKeepalive(int slot) {
            if (this != mNetworkAgent) return;
            ClientModeImpl.this.sendMessage(CMD_STOP_IP_PACKET_OFFLOAD, slot);
        }

        @Override
        public void onAddKeepalivePacketFilter(int slot, @NonNull KeepalivePacketData packet) {
            if (this != mNetworkAgent) return;
            ClientModeImpl.this.sendMessage(
                    CMD_ADD_KEEPALIVE_PACKET_FILTER_TO_APF, slot, 0, packet);
        }

        @Override
        public void onRemoveKeepalivePacketFilter(int slot) {
            if (this != mNetworkAgent) return;
            ClientModeImpl.this.sendMessage(CMD_REMOVE_KEEPALIVE_PACKET_FILTER_FROM_APF, slot);
        }

        @Override
        public void onSignalStrengthThresholdsUpdated(@NonNull int[] thresholds) {
            if (this != mNetworkAgent) return;
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
            logd("Received signal strength thresholds: " + Arrays.toString(thresholds));
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
        public void onAutomaticReconnectDisabled() {
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
            sendNetworkChangeBroadcast(DetailedState.CONNECTING);

            // If this network was explicitly selected by the user, evaluate whether to inform
            // ConnectivityService of that fact so the system can treat it appropriately.
            final WifiConfiguration config = getCurrentWifiConfiguration();

            boolean explicitlySelected = false;
            if (shouldEvaluateWhetherToSendExplicitlySelected(config)) {
                // If explicitlySelected is true, the network was selected by the user via Settings
                // or QuickSettings. If this network has Internet access, switch to it. Otherwise,
                // switch to it only if the user confirms that they really want to switch, or has
                // already confirmed and selected "Don't ask again".
                explicitlySelected =
                        mWifiPermissionsUtil.checkNetworkSettingsPermission(config.lastConnectUid);
                if (mVerboseLoggingEnabled) {
                    log("Network selected by UID " + config.lastConnectUid + " explicitlySelected="
                            + explicitlySelected);
                }
            }

            if (mVerboseLoggingEnabled) {
                log("explicitlySelected=" + explicitlySelected + " acceptUnvalidated="
                        + config.noInternetAccessExpected);
            }

            final NetworkAgentConfig naConfig = new NetworkAgentConfig.Builder()
                    .setLegacyType(ConnectivityManager.TYPE_WIFI)
                    .setLegacyTypeName(NETWORKTYPE)
                    .setExplicitlySelected(explicitlySelected)
                    .setUnvalidatedConnectivityAcceptable(
                            explicitlySelected && config.noInternetAccessExpected)
                    .setPartialConnectivityAcceptable(config.noInternetAccessExpected)
                    .build();
            final NetworkCapabilities nc = getCapabilities(getCurrentWifiConfiguration());
            // This should never happen.
            if (mNetworkAgent != null) {
                Log.wtf(TAG, "mNetworkAgent is not null: " + mNetworkAgent);
                mNetworkAgent.unregister();
            }
            mNetworkAgent = new WifiNetworkAgent(mContext, getHandler().getLooper(),
                    "WifiNetworkAgent", nc, mLinkProperties, 60, naConfig,
                    mNetworkFactory.getProvider());
            mWifiScoreReport.setNetworkAgent(mNetworkAgent);

            // We must clear the config BSSID, as the wifi chipset may decide to roam
            // from this point on and having the BSSID specified in the network block would
            // cause the roam to faile and the device to disconnect
            clearTargetBssid("L2ConnectedState");
            mCountryCode.setReadyForChange(false);
            mWifiMetrics.setWifiState(WifiMetricsProto.WifiLog.WIFI_ASSOCIATED);
            mWifiScoreCard.noteNetworkAgentCreated(mWifiInfo,
                    mNetworkAgent.getNetwork().getNetId());
            mBssidBlocklistMonitor.handleBssidConnectionSuccess(mLastBssid, mWifiInfo.getSSID());
        }

        @Override
        public void exit() {
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
            int callbackIdentifier = -1;

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
                    handleIPv4Success((DhcpResultsParcelable) message.obj);
                    break;
                }
                case CMD_IPV4_PROVISIONING_FAILURE: {
                    handleIPv4Failure();
                    mWifiInjector.getWifiLastResortWatchdog()
                            .noteConnectionFailureAndTriggerIfNeeded(
                                    getTargetSsid(),
                                    (mLastBssid == null) ? mTargetBssid : mLastBssid,
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
                                    getTargetSsid(),
                                    (mLastBssid == null) ? mTargetBssid : mLastBssid,
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
                case WifiMonitor.NETWORK_CONNECTION_EVENT:
                case WifiMonitor.FILS_NETWORK_CONNECTION_EVENT:
                    mWifiInfo.setBSSID((String) message.obj);
                    mLastNetworkId = message.arg1;
                    mWifiInfo.setNetworkId(mLastNetworkId);
                    mWifiInfo.setMacAddress(mWifiNative.getMacAddress(mInterfaceName));
                    if (!mLastBssid.equals(message.obj)) {
                        mLastBssid = (String) message.obj;
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
                        int statusDataStall = mWifiDataStall.checkDataStallAndThroughputSufficiency(
                                mLastLinkLayerStats, stats, mWifiInfo);
                        if (mDataStallTriggerTimeMs == -1
                                && statusDataStall != WifiIsUnusableEvent.TYPE_UNKNOWN) {
                            mDataStallTriggerTimeMs = mClock.getElapsedSinceBootMillis();
                            mLastStatusDataStall = statusDataStall;
                        }
                        if (mDataStallTriggerTimeMs != -1) {
                            long elapsedTime =  mClock.getElapsedSinceBootMillis()
                                    - mDataStallTriggerTimeMs;
                            if (elapsedTime >= DURATION_TO_WAIT_ADD_STATS_AFTER_DATA_STALL_MS) {
                                mDataStallTriggerTimeMs = -1;
                                mWifiMetrics.addToWifiUsabilityStatsList(
                                        WifiUsabilityStats.LABEL_BAD,
                                        convertToUsabilityStatsTriggerType(mLastStatusDataStall),
                                        -1);
                                mLastStatusDataStall = WifiIsUnusableEvent.TYPE_UNKNOWN;
                            }
                        }
                        mWifiMetrics.incrementWifiLinkLayerUsageStats(stats);
                        mLastLinkLayerStats = stats;
                        mWifiScoreCard.noteSignalPoll(mWifiInfo);
                        mLinkProbeManager.updateConnectionStats(
                                mWifiInfo, mInterfaceName);
                        sendMessageDelayed(obtainMessage(CMD_RSSI_POLL, mRssiPollToken, 0),
                                getPollRssiIntervalMsecs());
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
                                getPollRssiIntervalMsecs());
                    }
                    break;
                case WifiMonitor.ASSOCIATED_BSSID_EVENT:
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
                    if (message.arg1 != RESET_SIM_REASON_SIM_INSERTED
                            && mLastNetworkId != WifiConfiguration.INVALID_NETWORK_ID) {
                        WifiConfiguration config =
                                mWifiConfigManager.getConfiguredNetwork(mLastNetworkId);
                        if ((message.arg1 == RESET_SIM_REASON_DEFAULT_DATA_SIM_CHANGED
                                && config.carrierId != TelephonyManager.UNKNOWN_CARRIER_ID)
                                || (config.enterpriseConfig != null
                                        && config.enterpriseConfig.isAuthenticationSimBased()
                                        && !mWifiCarrierInfoManager.isSimPresent(mLastSubId))) {
                            mWifiMetrics.logStaEvent(StaEvent.TYPE_FRAMEWORK_DISCONNECT,
                                    StaEvent.DISCONNECT_RESET_SIM_NETWORKS);
                            // remove local PMKSA cache in framework
                            mWifiNative.removeNetworkCachedData(mLastNetworkId);
                            // remove network so that supplicant's PMKSA cache is cleared
                            mWifiNative.removeAllNetworks(mInterfaceName);
                            mSimRequiredNotifier.showSimRequiredNotification(
                                    config, mLastSimBasedConnectionCarrierName);
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
                        mNetworkAgent.sendSocketKeepaliveEvent(slot, result);
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
            mWifiScoreReport.calculateAndReportScore();
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
            WifiConfiguration currentConfig = getCurrentWifiConfiguration();
            if (mIpClientWithPreConnection && mIpClient != null) {
                mIpClient.notifyPreconnectionComplete(mSentHLPs);
                mIpClientWithPreConnection = false;
                mSentHLPs = false;
            } else {
                startIpClient(currentConfig, false);
            }
            // Get Link layer stats so as we get fresh tx packet counters
            getWifiLinkLayerStats();
        }

        @Override
        public boolean processMessage(Message message) {
            boolean handleStatus = HANDLED;

            switch(message.what) {
                case WifiMonitor.NETWORK_DISCONNECTION_EVENT:
                    reportConnectionAttemptEnd(
                            WifiMetrics.ConnectionEvent.FAILURE_NETWORK_DISCONNECTION,
                            WifiMetricsProto.ConnectionEvent.HLF_NONE,
                            WifiMetricsProto.ConnectionEvent.FAILURE_REASON_UNKNOWN);
                    mWifiInjector.getWifiLastResortWatchdog()
                            .noteConnectionFailureAndTriggerIfNeeded(
                                    getTargetSsid(),
                                    (message.obj == null)
                                    ? mTargetBssid : (String) message.obj,
                                    WifiLastResortWatchdog.FAILURE_CODE_DHCP);
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
        mNetworkAgent.markConnected();
        sendNetworkChangeBroadcast(DetailedState.CONNECTED);
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
                                && stateChangeResult.BSSID.equals(mTargetBssid)) {
                            handleNetworkDisconnect();
                            transitionTo(mDisconnectedState);
                        }
                    }
                    if (stateChangeResult.state == SupplicantState.ASSOCIATED) {
                        // We completed the layer2 roaming part
                        mAssociated = true;
                        if (stateChangeResult.BSSID != null) {
                            mTargetBssid = stateChangeResult.BSSID;
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

                        // Successful framework roam! (probably)
                        mBssidBlocklistMonitor.handleBssidConnectionSuccess(mLastBssid,
                                mWifiInfo.getSSID());
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
                        if (mTargetBssid != null) target = mTargetBssid;
                        log("NETWORK_DISCONNECTION_EVENT in roaming state"
                                + " BSSID=" + bssid
                                + " target=" + target);
                    }
                    clearNetworkCachedDataIfNeeded(getTargetWifiConfiguration(), message.arg2);
                    if (bssid != null && bssid.equals(mTargetBssid)) {
                        handleNetworkDisconnect();
                        transitionTo(mDisconnectedState);
                    }
                    break;
                case CMD_GET_CURRENT_NETWORK:
                    replyToMessage(message, message.what, getCurrentNetwork());
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
            mWifiScoreReport.startConnectedNetworkScorer(mNetworkAgent.getNetwork().getNetId());
            updateLinkLayerStatsRssiAndScoreReport();
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
                                        DISABLED_NO_INTERNET_PERMANENT);
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
                                            DISABLED_NO_INTERNET_TEMPORARY);
                                }
                                int rssi = mWifiInfo.getRssi();
                                int sufficientRssi = mWifiInjector.getScoringParams()
                                        .getSufficientRssi(mWifiInfo.getFrequency());
                                boolean isLowRssi = rssi < sufficientRssi;
                                mBssidBlocklistMonitor.handleBssidConnectionFailure(
                                        mLastBssid, config.SSID,
                                        BssidBlocklistMonitor.REASON_NETWORK_VALIDATION_FAILURE,
                                        isLowRssi);
                                mWifiScoreCard.noteValidationFailure(mWifiInfo);
                            }
                        }
                    }
                    break;
                case CMD_NETWORK_STATUS:
                    if (message.arg1 == NetworkAgent.VALIDATION_STATUS_VALID) {
                        // stop collect last-mile stats since validation pass
                        removeMessages(CMD_DIAGS_CONNECT_TIMEOUT);
                        mWifiDiagnostics.reportConnectionEvent(
                                WifiDiagnostics.CONNECTION_EVENT_SUCCEEDED);
                        mWifiScoreCard.noteValidationSuccess(mWifiInfo);
                        mBssidBlocklistMonitor.handleNetworkValidationSuccess(mLastBssid,
                                mWifiInfo.getSSID());
                        config = getCurrentWifiConfiguration();
                        if (config != null) {
                            // re-enable autojoin
                            mWifiConfigManager.updateNetworkSelectionStatus(
                                    config.networkId,
                                    WifiConfiguration.NetworkSelectionStatus
                                            .DISABLED_NONE);
                            mWifiConfigManager.setNetworkValidatedInternetAccess(
                                    config.networkId, true);
                        }
                    }
                    break;
                case CMD_ACCEPT_UNVALIDATED:
                    boolean accept = (message.arg1 != 0);
                    mWifiConfigManager.setNetworkNoInternetAccessExpected(mLastNetworkId, accept);
                    break;
                case WifiMonitor.ASSOCIATED_BSSID_EVENT:
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

                    boolean localGen = message.arg1 == 1;
                    if (!localGen) { // ignore disconnects initiated by wpa_supplicant.
                        mWifiScoreCard.noteNonlocalDisconnect(message.arg2);
                        int rssi = mWifiInfo.getRssi();
                        int sufficientRssi = mWifiInjector.getScoringParams()
                                .getSufficientRssi(mWifiInfo.getFrequency());
                        boolean isLowRssi = rssi < sufficientRssi;
                        mBssidBlocklistMonitor.handleBssidConnectionFailure(mWifiInfo.getBSSID(),
                                mWifiInfo.getSSID(),
                                BssidBlocklistMonitor.REASON_ABNORMAL_DISCONNECT, isLowRssi);
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
                    int scanRssi = mWifiConfigManager.findScanRssi(netId,
                            mWifiHealthMonitor.getScanRssiValidTimeMs());
                    mWifiScoreCard.noteConnectionAttempt(mWifiInfo, scanRssi, config.SSID);
                    setTargetBssid(config, bssid);
                    mTargetNetworkId = netId;

                    logd("CMD_START_ROAM sup state "
                            + " my state " + getCurrentState().getName()
                            + " nid=" + Integer.toString(netId)
                            + " config " + config.getKey()
                            + " targetRoamBSSID " + mTargetBssid);

                    reportConnectionAttemptStart(config, mTargetBssid,
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
                        mMessageHandlingStatus = MESSAGE_HANDLING_STATUS_FAIL;
                        break;
                    }
                    break;
                case CMD_IP_CONFIGURATION_LOST:
                    mWifiMetrics.incrementIpRenewalFailure();
                    handleStatus = NOT_HANDLED;
                    break;
                case CMD_GET_CURRENT_NETWORK:
                    replyToMessage(message, message.what, getCurrentNetwork());
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
                case CMD_CONNECT_NETWORK:
                case CMD_SAVE_NETWORK:
                    mMessageHandlingStatus = MESSAGE_HANDLING_STATUS_DEFERRED;
                    deferMessage(message);
                    break;
                case CMD_DISCONNECT:
                    if (mVerboseLoggingEnabled) {
                        log("Ignore CMD_DISCONNECT when already disconnecting.");
                    }
                    break;
                case WifiMonitor.NETWORK_CONNECTION_EVENT:
                    if (mVerboseLoggingEnabled) {
                        log("Ignore NETWORK_CONNECTION_EVENT when already disconnecting.");
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
                    mMessageHandlingStatus = MESSAGE_HANDLING_STATUS_DEFERRED;
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
                    stopIpClient();
                    if (message.arg2 == 15 /* FOURWAY_HANDSHAKE_TIMEOUT */) {
                        String bssid = (message.obj == null)
                                ? mTargetBssid : (String) message.obj;
                        mWifiInjector.getWifiLastResortWatchdog()
                                .noteConnectionFailureAndTriggerIfNeeded(
                                        getTargetSsid(), bssid,
                                        WifiLastResortWatchdog.FAILURE_CODE_AUTHENTICATION);
                    }
                    clearNetworkCachedDataIfNeeded(getTargetWifiConfiguration(), message.arg2);
                    mTargetNetworkId = WifiConfiguration.INVALID_NETWORK_ID;
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
                        mWifiInfo.setPasspointUniqueId(null);
                        mWifiInfo.setOsuAp(false);
                        mWifiInfo.setProviderFriendlyName(null);
                        if (config != null && (config.isPasspoint() || config.osu)) {
                            if (config.isPasspoint()) {
                                mWifiInfo.setFQDN(config.FQDN);
                                mWifiInfo.setPasspointUniqueId(config.getPasspointUniqueId());
                            } else {
                                mWifiInfo.setOsuAp(true);
                            }
                            mWifiInfo.setProviderFriendlyName(config.providerFriendlyName);
                        }
                    }
                    sendNetworkChangeBroadcast(
                            WifiInfo.getDetailedStateOf(stateChangeResult.state));
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
        Intent intent = new Intent(WifiManager.WIFI_CREDENTIAL_CHANGED_ACTION);
        if (config != null && config.SSID != null && mWifiPermissionsUtil.isLocationModeEnabled()) {
            intent.putExtra(WifiManager.EXTRA_WIFI_CREDENTIAL_SSID, config.SSID);
        }
        intent.putExtra(WifiManager.EXTRA_WIFI_CREDENTIAL_EVENT_TYPE, wifiCredentialEventType);
        mContext.createContextAsUser(UserHandle.CURRENT, 0)
                .sendBroadcastWithMultiplePermissions(
                        intent,
                        new String[]{
                                android.Manifest.permission.RECEIVE_WIFI_CREDENTIAL_CHANGE,
                                android.Manifest.permission.ACCESS_FINE_LOCATION,
                        });
    }

    void handleGsmAuthRequest(SimAuthRequestData requestData) {
        if (mTargetWifiConfiguration != null
                && mTargetWifiConfiguration.networkId
                == requestData.networkId) {
            logd("id matches targetWifiConfiguration");
        } else if (mLastNetworkId != WifiConfiguration.INVALID_NETWORK_ID
                && mLastNetworkId == requestData.networkId) {
            logd("id matches currentWifiConfiguration");
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
        String response = mWifiCarrierInfoManager
                .getGsmSimAuthResponse(requestData.data, mTargetWifiConfiguration);
        if (response == null) {
            // In case of failure, issue may be due to sim type, retry as No.2 case
            response = mWifiCarrierInfoManager
                    .getGsmSimpleSimAuthResponse(requestData.data, mTargetWifiConfiguration);
            if (response == null) {
                // In case of failure, issue may be due to sim type, retry as No.3 case
                response = mWifiCarrierInfoManager.getGsmSimpleSimNoLengthAuthResponse(
                                requestData.data, mTargetWifiConfiguration);
            }
        }
        if (response == null || response.length() == 0) {
            mWifiNative.simAuthFailedResponse(mInterfaceName);
        } else {
            logv("Supplicant Response -" + response);
            mWifiNative.simAuthResponse(
                    mInterfaceName, WifiNative.SIM_AUTH_RESP_TYPE_GSM_AUTH, response);
        }
    }

    void handle3GAuthRequest(SimAuthRequestData requestData) {
        if (mTargetWifiConfiguration != null
                && mTargetWifiConfiguration.networkId
                == requestData.networkId) {
            logd("id matches targetWifiConfiguration");
        } else if (mLastNetworkId != WifiConfiguration.INVALID_NETWORK_ID
                && mLastNetworkId == requestData.networkId) {
            logd("id matches currentWifiConfiguration");
        } else {
            logd("id does not match targetWifiConfiguration");
            return;
        }

        SimAuthResponseData response = mWifiCarrierInfoManager
                .get3GAuthResponse(requestData, mTargetWifiConfiguration);
        if (response != null) {
            mWifiNative.simAuthResponse(
                    mInterfaceName, response.type, response.response);
        } else {
            mWifiNative.umtsAuthFailedResponse(mInterfaceName);
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
     * @param choice true-enable; false-disable
     */
    public void allowAutoJoinGlobal(boolean choice) {
        mWifiConnectivityManager.setAutoJoinEnabledExternal(choice);
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

    private static String getLinkPropertiesSummary(LinkProperties lp) {
        List<String> attributes = new ArrayList<>(6);
        if (lp.hasIpv4Address()) {
            attributes.add("v4");
        }
        if (lp.hasIpv4DefaultRoute()) {
            attributes.add("v4r");
        }
        if (lp.hasIpv4DnsServer()) {
            attributes.add("v4dns");
        }
        if (lp.hasGlobalIpv6Address()) {
            attributes.add("v6");
        }
        if (lp.hasIpv6DefaultRoute()) {
            attributes.add("v6r");
        }
        if (lp.hasIpv6DnsServer()) {
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
     */
    public void initialize() {
        sendMessage(CMD_INITIALIZE);
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
     * Approve all access points from {@link WifiNetworkFactory} for the provided package.
     * Used by shell commands.
     */
    public void setNetworkRequestUserApprovedApp(@NonNull String packageName, boolean approved) {
        mNetworkFactory.setUserApprovedApp(packageName, approved);
    }

    /**
     * Whether all access points are approved for the specified app.
     * Used by shell commands.
     */
    public boolean hasNetworkRequestUserApprovedApp(@NonNull String packageName) {
        return mNetworkFactory.hasUserApprovedApp(packageName);
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
        if (!isConnectedMacRandomizationEnabled()) {
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
        mWifiHealthMonitor.setDeviceMobilityState(state);
        mWifiDataStall.setDeviceMobilityState(state);
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
    public void probeLink(WifiNl80211Manager.SendMgmtFrameCallback callback, int mcs) {
        mWifiNative.probeLink(mInterfaceName, MacAddress.fromString(mWifiInfo.getBSSID()),
                callback, mcs);
    }

    private void sendActionListenerFailure(int callbackIdentifier, int reason) {
        IActionListener actionListener;
        synchronized (mProcessingActionListeners) {
            actionListener = mProcessingActionListeners.remove(callbackIdentifier);
        }
        if (actionListener != null) {
            try {
                actionListener.onFailure(reason);
            } catch (RemoteException e) {
                // no-op (client may be dead, nothing to be done)
            }
        }
    }

    private void sendActionListenerSuccess(int callbackIdentifier) {
        IActionListener actionListener;
        synchronized (mProcessingActionListeners) {
            actionListener = mProcessingActionListeners.remove(callbackIdentifier);
        }
        if (actionListener != null) {
            try {
                actionListener.onSuccess();
            } catch (RemoteException e) {
                // no-op (client may be dead, nothing to be done)
            }
        }
    }

    /**
     * Trigger network connection and provide status via the provided callback.
     */
    public void connect(WifiConfiguration config, int netId, @Nullable IBinder binder,
            @Nullable IActionListener callback, int callbackIdentifier, int callingUid) {
        mWifiInjector.getWifiThreadRunner().post(() -> {
            if (callback != null && binder != null) {
                mProcessingActionListeners.add(binder, callback, callbackIdentifier);
            }
            /**
             * The connect message can contain a network id passed as arg1 on message or
             * or a config passed as obj on message.
             * For a new network, a config is passed to create and connect.
             * For an existing network, a network id is passed
             */
            NetworkUpdateResult result = null;
            if (config != null) {
                result = mWifiConfigManager.addOrUpdateNetwork(config, callingUid);
                if (!result.isSuccess()) {
                    loge("connectNetwork adding/updating config=" + config + " failed");
                    sendActionListenerFailure(callbackIdentifier, WifiManager.ERROR);
                    return;
                }
                broadcastWifiCredentialChanged(WifiManager.WIFI_CREDENTIAL_SAVED, config);
            } else {
                if (mWifiConfigManager.getConfiguredNetwork(netId) == null) {
                    loge("connectNetwork Invalid network Id=" + netId);
                    sendActionListenerFailure(callbackIdentifier, WifiManager.ERROR);
                    return;
                }
                result = new NetworkUpdateResult(netId);
            }
            final int networkId = result.getNetworkId();
            mWifiConfigManager.userEnabledNetwork(networkId);
            if (!mWifiConfigManager.enableNetwork(networkId, true, callingUid, null)
                    || !mWifiConfigManager.updateLastConnectUid(networkId, callingUid)) {
                logi("connect Allowing uid " + callingUid
                        + " with insufficient permissions to connect=" + networkId);
            } else if (mWifiPermissionsUtil.checkNetworkSettingsPermission(callingUid)) {
                // Note user connect choice here, so that it will be considered in the
                // next network selection.
                mWifiConnectivityManager.setUserConnectChoice(networkId);
            }
            Message message =
                    obtainMessage(CMD_CONNECT_NETWORK, -1, callbackIdentifier, result);
            message.sendingUid = callingUid;
            sendMessage(message);
        });
    }

    /**
     * Trigger network save and provide status via the provided callback.
     */
    public void save(WifiConfiguration config, @Nullable IBinder binder,
            @Nullable IActionListener callback, int callbackIdentifier, int callingUid) {
        mWifiInjector.getWifiThreadRunner().post(() -> {
            if (callback != null && binder != null) {
                mProcessingActionListeners.add(binder, callback, callbackIdentifier);
            }
            if (config == null) {
                loge("saveNetwork with null configuration my state "
                        + getCurrentState().getName());
                sendActionListenerFailure(callbackIdentifier, WifiManager.ERROR);
                return;
            }
            NetworkUpdateResult result =
                    mWifiConfigManager.addOrUpdateNetwork(config, callingUid);
            if (!result.isSuccess()) {
                loge("saveNetwork adding/updating config=" + config + " failed");
                sendActionListenerFailure(callbackIdentifier, WifiManager.ERROR);
                return;
            }
            if (!mWifiConfigManager.enableNetwork(
                    result.getNetworkId(), false, callingUid, null)) {
                loge("saveNetwork enabling config=" + config + " failed");
                sendActionListenerFailure(callbackIdentifier, WifiManager.ERROR);
                return;
            }
            broadcastWifiCredentialChanged(WifiManager.WIFI_CREDENTIAL_SAVED, config);
            Message message =
                    obtainMessage(CMD_SAVE_NETWORK, -1 , callbackIdentifier, result);
            message.sendingUid = callingUid;
            sendMessage(message);
        });
    }

    /**
     * Trigger network forget and provide status via the provided callback.
     */
    public void forget(int netId, @Nullable IBinder binder, @Nullable IActionListener callback,
            int callbackIdentifier, int callingUid) {
        mWifiInjector.getWifiThreadRunner().post(() -> {
            if (callback != null && binder != null) {
                mProcessingActionListeners.add(binder, callback, callbackIdentifier);
            }
            WifiConfiguration config = mWifiConfigManager.getConfiguredNetwork(netId);
            boolean success = mWifiConfigManager.removeNetwork(netId, callingUid, null);
            if (!success) {
                loge("Failed to remove network");
                sendActionListenerFailure(callbackIdentifier, WifiManager.ERROR);
            }
            sendActionListenerSuccess(callbackIdentifier);
            broadcastWifiCredentialChanged(WifiManager.WIFI_CREDENTIAL_FORGOT, config);
        });
    }

    /**
     * Handle BSS transition request from Connected BSS.
     *
     * @param frameData Data retrieved from received BTM request frame.
     */
    private void handleBssTransitionRequest(BtmFrameData frameData) {
        if (frameData == null) {
            return;
        }

        String bssid = mWifiInfo.getBSSID();
        String ssid = mWifiInfo.getSSID();
        if ((bssid == null) || (ssid == null) || WifiManager.UNKNOWN_SSID.equals(ssid)) {
            Log.e(TAG, "Failed to handle BSS transition: bssid: " + bssid + " ssid: " + ssid);
            return;
        }

        if ((frameData.mBssTmDataFlagsMask
                & MboOceConstants.BTM_DATA_FLAG_MBO_CELL_DATA_CONNECTION_PREFERENCE_INCLUDED)
                != 0) {
            mWifiMetrics.incrementMboCellularSwitchRequestCount();
        }


        if ((frameData.mBssTmDataFlagsMask
                & MboOceConstants.BTM_DATA_FLAG_MBO_ASSOC_RETRY_DELAY_INCLUDED)
                != 0) {
            long duration = frameData.mBlackListDurationMs;
            mWifiMetrics.incrementSteeringRequestCountIncludingMboAssocRetryDelay();
            if (duration == 0) {
                /*
                 * When MBO assoc retry delay is set to zero(reserved as per spec),
                 * blacklist the BSS for sometime to avoid AP rejecting the re-connect request.
                 */
                duration = MboOceConstants.DEFAULT_BLACKLIST_DURATION_MS;
            }
            // Blacklist the current BSS
            mBssidBlocklistMonitor.blockBssidForDurationMs(bssid, ssid, duration);
        }

        if (frameData.mStatus != MboOceConstants.BTM_RESPONSE_STATUS_ACCEPT) {
            // Trigger the network selection and re-connect to new network if available.
            mWifiMetrics.incrementForceScanCountDueToSteeringRequest();
            mWifiConnectivityManager.forceConnectivityScan(ClientModeImpl.WIFI_WORK_SOURCE);
        }
    }

    /**
     * @return true if this device supports FILS-SHA256
     */
    private boolean isFilsSha256Supported() {
        return (mWifiNative.getSupportedFeatureSet(mInterfaceName) & WIFI_FEATURE_FILS_SHA256) != 0;
    }

    /**
     * @return true if this device supports FILS-SHA384
     */
    private boolean isFilsSha384Supported() {
        return (mWifiNative.getSupportedFeatureSet(mInterfaceName) & WIFI_FEATURE_FILS_SHA384) != 0;
    }

    /**
     * Helper method to set the allowed key management schemes from
     * scan result.
     */
    private void updateAllowedKeyManagementSchemesFromScanResult(
            WifiConfiguration config, ScanResult scanResult) {
        if (isFilsSha256Supported()
                && ScanResultUtil.isScanResultForFilsSha256Network(scanResult)) {
            config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.FILS_SHA256);
        }
        if (isFilsSha384Supported()
                && ScanResultUtil.isScanResultForFilsSha384Network(scanResult)) {
            config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.FILS_SHA384);
        }
    }
    /**
     * Update wifi configuration based on the matching scan result.
     *
     * @param config Wifi configuration object.
     * @param scanResult Scan result matching the network.
     */
    private void updateWifiConfigFromMatchingScanResult(WifiConfiguration config,
            ScanResult scanResult) {
        updateAllowedKeyManagementSchemesFromScanResult(config, scanResult);
        if (config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.FILS_SHA256)
                || config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.FILS_SHA384)) {
            config.enterpriseConfig.setFieldValue(WifiEnterpriseConfig.EAP_ERP, "1");
        }
    }

    /**
     * Update the wifi configuration before sending connect to
     * supplicant/driver.
     *
     * @param config wifi configuration object.
     * @param bssid BSSID to assocaite with.
     */
    void updateWifiConfigOnStartConnection(WifiConfiguration config, String bssid) {
        boolean canUpgradePskToSae = false;
        boolean isFrameworkWpa3SaeUpgradePossible = false;
        boolean isLegacyWpa2ApInScanResult = false;

        setTargetBssid(config, bssid);

        if (isWpa3SaeUpgradeEnabled() && config.allowedKeyManagement.get(
                WifiConfiguration.KeyMgmt.WPA_PSK)) {
            isFrameworkWpa3SaeUpgradePossible = true;
        }

        if (isFrameworkWpa3SaeUpgradePossible && isWpa3SaeUpgradeOffloadEnabled()) {
            // Driver offload of upgrading legacy WPA/WPA2 connection to WPA3
            if (mVerboseLoggingEnabled) {
                Log.d(TAG, "Driver upgrade legacy WPA/WPA2 connection to WPA3");
            }
            config.allowedAuthAlgorithms.clear();
            // Note: KeyMgmt.WPA2_PSK is already enabled, enable SAE as well
            config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.SAE);
            isFrameworkWpa3SaeUpgradePossible = false;
        }
        // Check if network selection selected a good WPA3 candidate AP for a WPA2
        // saved network.
        ScanResult scanResultCandidate = config.getNetworkSelectionStatus().getCandidate();
        if (isFrameworkWpa3SaeUpgradePossible && scanResultCandidate != null) {
            ScanResultMatchInfo scanResultMatchInfo = ScanResultMatchInfo
                    .fromScanResult(scanResultCandidate);
            if ((scanResultMatchInfo.networkType == WifiConfiguration.SECURITY_TYPE_SAE)) {
                canUpgradePskToSae = true;
            } else {
                // No SAE candidate
                isFrameworkWpa3SaeUpgradePossible = false;
            }
        }

        /**
         *  Go through the matching scan results and update wifi config.
         */
        ScanResultMatchInfo key1 = ScanResultMatchInfo.fromWifiConfiguration(config);
        ScanRequestProxy scanRequestProxy = mWifiInjector.getScanRequestProxy();
        List<ScanResult> scanResults = scanRequestProxy.getScanResults();
        for (ScanResult scanResult : scanResults) {
            if (!config.SSID.equals(ScanResultUtil.createQuotedSSID(scanResult.SSID))) {
                continue;
            }
            if (isFrameworkWpa3SaeUpgradePossible && !isLegacyWpa2ApInScanResult) {
                if (ScanResultUtil.isScanResultForPskNetwork(scanResult)
                        && !ScanResultUtil.isScanResultForSaeNetwork(scanResult)) {
                    // Found a legacy WPA2 AP in range. Do not upgrade the connection to WPA3 to
                    // allow seamless roaming within the ESS.
                    if (mVerboseLoggingEnabled) {
                        Log.d(TAG, "Found legacy WPA2 AP, do not upgrade to WPA3");
                    }
                    isLegacyWpa2ApInScanResult = true;
                    canUpgradePskToSae = false;
                }
                if (ScanResultUtil.isScanResultForSaeNetwork(scanResult)
                        && scanResultCandidate == null) {
                    // When the user manually selected a network from the Wi-Fi picker, evaluate
                    // if to upgrade based on the scan results. The most typical use case during
                    // the WPA3 transition mode is to have a WPA2/WPA3 AP in transition mode. In
                    // this case, we would like to upgrade the connection.
                    canUpgradePskToSae = true;
                }
            }

            ScanResultMatchInfo key2 = ScanResultMatchInfo.fromScanResult(scanResult);
            if (!key1.equals(key2)) {
                continue;
            }
            updateWifiConfigFromMatchingScanResult(config, scanResult);
        }

        if (isFrameworkWpa3SaeUpgradePossible && canUpgradePskToSae
                && !(config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.FILS_SHA256)
                            || config.allowedKeyManagement.get(
                            WifiConfiguration.KeyMgmt.FILS_SHA384))) {
            // Upgrade legacy WPA/WPA2 connection to WPA3
            if (mVerboseLoggingEnabled) {
                Log.d(TAG, "Upgrade legacy WPA/WPA2 connection to WPA3");
            }
            config.setSecurityParams(WifiConfiguration.SECURITY_TYPE_SAE);
        }

        if (isConnectedMacRandomizationEnabled()) {
            if (config.macRandomizationSetting == WifiConfiguration.RANDOMIZATION_PERSISTENT) {
                configureRandomizedMacAddress(config);
            } else {
                setCurrentMacToFactoryMac(config);
            }
        }

        if (config.enterpriseConfig != null
                && config.enterpriseConfig.isAuthenticationSimBased()
                && mWifiCarrierInfoManager.isImsiEncryptionInfoAvailable(
                mWifiCarrierInfoManager.getBestMatchSubscriptionId(config))
                && TextUtils.isEmpty(config.enterpriseConfig.getAnonymousIdentity())) {
            String anonAtRealm = mWifiCarrierInfoManager
                    .getAnonymousIdentityWith3GppRealm(config);
            // Use anonymous@<realm> when pseudonym is not available
            config.enterpriseConfig.setAnonymousIdentity(anonAtRealm);
        }
    }

    private void setConfigurationsPriorToIpClientProvisioning(WifiConfiguration config) {
        mIpClient.setHttpProxy(config.getHttpProxy());
        if (!TextUtils.isEmpty(mContext.getResources().getString(
                R.string.config_wifi_tcp_buffers))) {
            mIpClient.setTcpBufferSizes(mContext.getResources().getString(
                    R.string.config_wifi_tcp_buffers));
        }
    }

    private boolean startIpClient(WifiConfiguration config, boolean isFilsConnection) {
        if (mIpClient == null) {
            return false;
        }

        final boolean isUsingStaticIp =
                (config.getIpAssignment() == IpConfiguration.IpAssignment.STATIC);
        final boolean isUsingMacRandomization =
                config.macRandomizationSetting
                        == WifiConfiguration.RANDOMIZATION_PERSISTENT
                        && isConnectedMacRandomizationEnabled();
        if (mVerboseLoggingEnabled) {
            final String key = config.getKey();
            log("startIpClient netId=" + Integer.toString(mLastNetworkId)
                    + " " + key + " "
                    + " roam=" + mIsAutoRoaming
                    + " static=" + isUsingStaticIp
                    + " randomMac=" + isUsingMacRandomization
                    + " isFilsConnection=" + isFilsConnection);
        }

        final MacAddress currentBssid = getCurrentBssid();
        final String l2Key = mLastL2KeyAndGroupHint != null
                ? mLastL2KeyAndGroupHint.first : null;
        final String groupHint = mLastL2KeyAndGroupHint != null
                ? mLastL2KeyAndGroupHint.second : null;
        final Layer2Information layer2Info = new Layer2Information(l2Key, groupHint,
                currentBssid);

        if (isFilsConnection) {
            stopIpClient();
            if (isUsingStaticIp) {
                mWifiNative.flushAllHlp(mInterfaceName);
                return false;
            }
            setConfigurationsPriorToIpClientProvisioning(config);
            final ProvisioningConfiguration.Builder prov =
                    new ProvisioningConfiguration.Builder()
                    .withPreDhcpAction()
                    .withPreconnection()
                    .withApfCapabilities(
                    mWifiNative.getApfCapabilities(mInterfaceName))
                    .withLayer2Information(layer2Info);
            if (isUsingMacRandomization) {
                // Use EUI64 address generation for link-local IPv6 addresses.
                prov.withRandomMacAddress();
            }
            mIpClient.startProvisioning(prov.build());
        } else {
            sendNetworkChangeBroadcast(DetailedState.OBTAINING_IPADDR);
            // We must clear the config BSSID, as the wifi chipset may decide to roam
            // from this point on and having the BSSID specified in the network block would
            // cause the roam to fail and the device to disconnect.
            clearTargetBssid("ObtainingIpAddress");

            // Stop IpClient in case we're switching from DHCP to static
            // configuration or vice versa.
            //
            // When we transition from static configuration to DHCP in
            // particular, we must tell ConnectivityService that we're
            // disconnected, because DHCP might take a long time during which
            // connectivity APIs such as getActiveNetworkInfo should not return
            // CONNECTED.
            stopDhcpSetup();
            setConfigurationsPriorToIpClientProvisioning(config);
            ScanDetailCache scanDetailCache =
                    mWifiConfigManager.getScanDetailCacheForNetwork(config.networkId);
            ScanResult scanResult = null;
            if (mLastBssid != null) {
                if (scanDetailCache != null) {
                    scanResult = scanDetailCache.getScanResult(mLastBssid);
                }

                // The cached scan result of connected network would be null at the first
                // connection, try to check full scan result list again to look up matched
                // scan result associated to the current SSID and BSSID.
                if (scanResult == null) {
                    ScanRequestProxy scanRequestProxy = mWifiInjector.getScanRequestProxy();
                    List<ScanResult> scanResults = scanRequestProxy.getScanResults();
                    for (ScanResult result : scanResults) {
                        if (result.SSID.equals(WifiInfo.removeDoubleQuotes(config.SSID))
                                && result.BSSID.equals(mLastBssid)) {
                            scanResult = result;
                            break;
                        }
                    }
                }
            }

            final ProvisioningConfiguration.Builder prov;
            ProvisioningConfiguration.ScanResultInfo scanResultInfo = null;
            if (scanResult != null) {
                final List<ScanResultInfo.InformationElement> ies =
                        new ArrayList<ScanResultInfo.InformationElement>();
                for (ScanResult.InformationElement ie : scanResult.getInformationElements()) {
                    ScanResultInfo.InformationElement scanResultInfoIe =
                            new ScanResultInfo.InformationElement(ie.getId(), ie.getBytes());
                    ies.add(scanResultInfoIe);
                }
                scanResultInfo = new ProvisioningConfiguration.ScanResultInfo(scanResult.SSID,
                        scanResult.BSSID, ies);
            }

            if (!isUsingStaticIp) {
                prov = new ProvisioningConfiguration.Builder()
                    .withPreDhcpAction()
                    .withApfCapabilities(mWifiNative.getApfCapabilities(mInterfaceName))
                    .withNetwork(getCurrentNetwork())
                    .withDisplayName(config.SSID)
                    .withScanResultInfo(scanResultInfo)
                    .withLayer2Information(layer2Info);
            } else {
                StaticIpConfiguration staticIpConfig = config.getStaticIpConfiguration();
                prov = new ProvisioningConfiguration.Builder()
                        .withStaticConfiguration(staticIpConfig)
                        .withApfCapabilities(mWifiNative.getApfCapabilities(mInterfaceName))
                        .withNetwork(getCurrentNetwork())
                        .withDisplayName(config.SSID)
                        .withLayer2Information(layer2Info);
            }
            if (isUsingMacRandomization) {
                // Use EUI64 address generation for link-local IPv6 addresses.
                prov.withRandomMacAddress();
            }
            mIpClient.startProvisioning(prov.build());
        }

        return true;
    }

}
