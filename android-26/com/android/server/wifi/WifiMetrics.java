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

import android.hardware.wifi.supplicant.V1_0.ISupplicantStaIfaceCallback;
import android.net.NetworkAgent;
import android.net.wifi.ScanResult;
import android.net.wifi.SupplicantState;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Base64;
import android.util.Log;
import android.util.SparseIntArray;

import com.android.server.wifi.hotspot2.NetworkDetail;
import com.android.server.wifi.nano.WifiMetricsProto;
import com.android.server.wifi.nano.WifiMetricsProto.StaEvent;
import com.android.server.wifi.nano.WifiMetricsProto.StaEvent.ConfigInfo;
import com.android.server.wifi.util.InformationElementUtil;
import com.android.server.wifi.util.ScanResultUtil;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Calendar;
import java.util.LinkedList;
import java.util.List;

/**
 * Provides storage for wireless connectivity metrics, as they are generated.
 * Metrics logged by this class include:
 *   Aggregated connection stats (num of connections, num of failures, ...)
 *   Discrete connection event stats (time, duration, failure codes, ...)
 *   Router details (technology type, authentication type, ...)
 *   Scan stats
 */
public class WifiMetrics {
    private static final String TAG = "WifiMetrics";
    private static final boolean DBG = false;
    /**
     * Clamp the RSSI poll counts to values between [MIN,MAX]_RSSI_POLL
     */
    private static final int MAX_RSSI_POLL = 0;
    private static final int MIN_RSSI_POLL = -127;
    public static final int MAX_RSSI_DELTA = 127;
    public static final int MIN_RSSI_DELTA = -127;
    /** Maximum time period between ScanResult and RSSI poll to generate rssi delta datapoint */
    public static final long TIMEOUT_RSSI_DELTA_MILLIS =  3000;
    private static final int MIN_WIFI_SCORE = 0;
    private static final int MAX_WIFI_SCORE = NetworkAgent.WIFI_BASE_SCORE;
    private final Object mLock = new Object();
    private static final int MAX_CONNECTION_EVENTS = 256;
    private Clock mClock;
    private boolean mScreenOn;
    private int mWifiState;
    private Handler mHandler;
    /**
     * Metrics are stored within an instance of the WifiLog proto during runtime,
     * The ConnectionEvent, SystemStateEntries & ScanReturnEntries metrics are stored during
     * runtime in member lists of this WifiMetrics class, with the final WifiLog proto being pieced
     * together at dump-time
     */
    private final WifiMetricsProto.WifiLog mWifiLogProto = new WifiMetricsProto.WifiLog();
    /**
     * Session information that gets logged for every Wifi connection attempt.
     */
    private final List<ConnectionEvent> mConnectionEventList = new ArrayList<>();
    /**
     * The latest started (but un-ended) connection attempt
     */
    private ConnectionEvent mCurrentConnectionEvent;
    /**
     * Count of number of times each scan return code, indexed by WifiLog.ScanReturnCode
     */
    private final SparseIntArray mScanReturnEntries = new SparseIntArray();
    /**
     * Mapping of system state to the counts of scans requested in that wifi state * screenOn
     * combination. Indexed by WifiLog.WifiState * (1 + screenOn)
     */
    private final SparseIntArray mWifiSystemStateEntries = new SparseIntArray();
    /** Mapping of RSSI values to counts. */
    private final SparseIntArray mRssiPollCounts = new SparseIntArray();
    /** Mapping of RSSI scan-poll delta values to counts. */
    private final SparseIntArray mRssiDeltaCounts = new SparseIntArray();
    /** RSSI of the scan result for the last connection event*/
    private int mScanResultRssi = 0;
    /** Boot-relative timestamp when the last candidate scanresult was received, used to calculate
        RSSI deltas. -1 designates no candidate scanResult being tracked */
    private long mScanResultRssiTimestampMillis = -1;
    /** Mapping of alert reason to the respective alert count. */
    private final SparseIntArray mWifiAlertReasonCounts = new SparseIntArray();
    /**
     * Records the getElapsedSinceBootMillis (in seconds) that represents the beginning of data
     * capture for for this WifiMetricsProto
     */
    private long mRecordStartTimeSec;
    /** Mapping of Wifi Scores to counts */
    private final SparseIntArray mWifiScoreCounts = new SparseIntArray();
    /** Mapping of SoftApManager start SoftAp return codes to counts */
    private final SparseIntArray mSoftApManagerReturnCodeCounts = new SparseIntArray();
    class RouterFingerPrint {
        private WifiMetricsProto.RouterFingerPrint mRouterFingerPrintProto;
        RouterFingerPrint() {
            mRouterFingerPrintProto = new WifiMetricsProto.RouterFingerPrint();
        }

        public String toString() {
            StringBuilder sb = new StringBuilder();
            synchronized (mLock) {
                sb.append("mConnectionEvent.roamType=" + mRouterFingerPrintProto.roamType);
                sb.append(", mChannelInfo=" + mRouterFingerPrintProto.channelInfo);
                sb.append(", mDtim=" + mRouterFingerPrintProto.dtim);
                sb.append(", mAuthentication=" + mRouterFingerPrintProto.authentication);
                sb.append(", mHidden=" + mRouterFingerPrintProto.hidden);
                sb.append(", mRouterTechnology=" + mRouterFingerPrintProto.routerTechnology);
                sb.append(", mSupportsIpv6=" + mRouterFingerPrintProto.supportsIpv6);
            }
            return sb.toString();
        }
        public void updateFromWifiConfiguration(WifiConfiguration config) {
            synchronized (mLock) {
                if (config != null) {
                    // Is this a hidden network
                    mRouterFingerPrintProto.hidden = config.hiddenSSID;
                    // Config may not have a valid dtimInterval set yet, in which case dtim will be zero
                    // (These are only populated from beacon frame scan results, which are returned as
                    // scan results from the chip far less frequently than Probe-responses)
                    if (config.dtimInterval > 0) {
                        mRouterFingerPrintProto.dtim = config.dtimInterval;
                    }
                    mCurrentConnectionEvent.mConfigSsid = config.SSID;
                    // Get AuthType information from config (We do this again from ScanResult after
                    // associating with BSSID)
                    if (config.allowedKeyManagement != null
                            && config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.NONE)) {
                        mCurrentConnectionEvent.mRouterFingerPrint.mRouterFingerPrintProto
                                .authentication = WifiMetricsProto.RouterFingerPrint.AUTH_OPEN;
                    } else if (config.isEnterprise()) {
                        mCurrentConnectionEvent.mRouterFingerPrint.mRouterFingerPrintProto
                                .authentication = WifiMetricsProto.RouterFingerPrint.AUTH_ENTERPRISE;
                    } else {
                        mCurrentConnectionEvent.mRouterFingerPrint.mRouterFingerPrintProto
                                .authentication = WifiMetricsProto.RouterFingerPrint.AUTH_PERSONAL;
                    }
                    mCurrentConnectionEvent.mRouterFingerPrint.mRouterFingerPrintProto
                            .passpoint = config.isPasspoint();
                    // If there's a ScanResult candidate associated with this config already, get it and
                    // log (more accurate) metrics from it
                    ScanResult candidate = config.getNetworkSelectionStatus().getCandidate();
                    if (candidate != null) {
                        updateMetricsFromScanResult(candidate);
                    }
                }
            }
        }
    }

    /**
     * Log event, tracking the start time, end time and result of a wireless connection attempt.
     */
    class ConnectionEvent {
        WifiMetricsProto.ConnectionEvent mConnectionEvent;
        //<TODO> Move these constants into a wifi.proto Enum, and create a new Failure Type field
        //covering more than just l2 failures. see b/27652362
        /**
         * Failure codes, used for the 'level_2_failure_code' Connection event field (covers a lot
         * more failures than just l2 though, since the proto does not have a place to log
         * framework failures)
         */
        // Failure is unknown
        public static final int FAILURE_UNKNOWN = 0;
        // NONE
        public static final int FAILURE_NONE = 1;
        // ASSOCIATION_REJECTION_EVENT
        public static final int FAILURE_ASSOCIATION_REJECTION = 2;
        // AUTHENTICATION_FAILURE_EVENT
        public static final int FAILURE_AUTHENTICATION_FAILURE = 3;
        // SSID_TEMP_DISABLED (Also Auth failure)
        public static final int FAILURE_SSID_TEMP_DISABLED = 4;
        // reconnect() or reassociate() call to WifiNative failed
        public static final int FAILURE_CONNECT_NETWORK_FAILED = 5;
        // NETWORK_DISCONNECTION_EVENT
        public static final int FAILURE_NETWORK_DISCONNECTION = 6;
        // NEW_CONNECTION_ATTEMPT before previous finished
        public static final int FAILURE_NEW_CONNECTION_ATTEMPT = 7;
        // New connection attempt to the same network & bssid
        public static final int FAILURE_REDUNDANT_CONNECTION_ATTEMPT = 8;
        // Roam Watchdog timer triggered (Roaming timed out)
        public static final int FAILURE_ROAM_TIMEOUT = 9;
        // DHCP failure
        public static final int FAILURE_DHCP = 10;

        RouterFingerPrint mRouterFingerPrint;
        private long mRealStartTime;
        private long mRealEndTime;
        private String mConfigSsid;
        private String mConfigBssid;
        private int mWifiState;
        private boolean mScreenOn;

        private ConnectionEvent() {
            mConnectionEvent = new WifiMetricsProto.ConnectionEvent();
            mRealEndTime = 0;
            mRealStartTime = 0;
            mRouterFingerPrint = new RouterFingerPrint();
            mConnectionEvent.routerFingerprint = mRouterFingerPrint.mRouterFingerPrintProto;
            mConfigSsid = "<NULL>";
            mConfigBssid = "<NULL>";
            mWifiState = WifiMetricsProto.WifiLog.WIFI_UNKNOWN;
            mScreenOn = false;
        }

        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("startTime=");
            Calendar c = Calendar.getInstance();
            synchronized (mLock) {
                c.setTimeInMillis(mConnectionEvent.startTimeMillis);
                sb.append(mConnectionEvent.startTimeMillis == 0 ? "            <null>" :
                        String.format("%tm-%td %tH:%tM:%tS.%tL", c, c, c, c, c, c));
                sb.append(", SSID=");
                sb.append(mConfigSsid);
                sb.append(", BSSID=");
                sb.append(mConfigBssid);
                sb.append(", durationMillis=");
                sb.append(mConnectionEvent.durationTakenToConnectMillis);
                sb.append(", roamType=");
                switch(mConnectionEvent.roamType) {
                    case 1:
                        sb.append("ROAM_NONE");
                        break;
                    case 2:
                        sb.append("ROAM_DBDC");
                        break;
                    case 3:
                        sb.append("ROAM_ENTERPRISE");
                        break;
                    case 4:
                        sb.append("ROAM_USER_SELECTED");
                        break;
                    case 5:
                        sb.append("ROAM_UNRELATED");
                        break;
                    default:
                        sb.append("ROAM_UNKNOWN");
                }
                sb.append(", connectionResult=");
                sb.append(mConnectionEvent.connectionResult);
                sb.append(", level2FailureCode=");
                switch(mConnectionEvent.level2FailureCode) {
                    case FAILURE_NONE:
                        sb.append("NONE");
                        break;
                    case FAILURE_ASSOCIATION_REJECTION:
                        sb.append("ASSOCIATION_REJECTION");
                        break;
                    case FAILURE_AUTHENTICATION_FAILURE:
                        sb.append("AUTHENTICATION_FAILURE");
                        break;
                    case FAILURE_SSID_TEMP_DISABLED:
                        sb.append("SSID_TEMP_DISABLED");
                        break;
                    case FAILURE_CONNECT_NETWORK_FAILED:
                        sb.append("CONNECT_NETWORK_FAILED");
                        break;
                    case FAILURE_NETWORK_DISCONNECTION:
                        sb.append("NETWORK_DISCONNECTION");
                        break;
                    case FAILURE_NEW_CONNECTION_ATTEMPT:
                        sb.append("NEW_CONNECTION_ATTEMPT");
                        break;
                    case FAILURE_REDUNDANT_CONNECTION_ATTEMPT:
                        sb.append("REDUNDANT_CONNECTION_ATTEMPT");
                        break;
                    case FAILURE_ROAM_TIMEOUT:
                        sb.append("ROAM_TIMEOUT");
                        break;
                    case FAILURE_DHCP:
                        sb.append("DHCP");
                    default:
                        sb.append("UNKNOWN");
                        break;
                }
                sb.append(", connectivityLevelFailureCode=");
                switch(mConnectionEvent.connectivityLevelFailureCode) {
                    case WifiMetricsProto.ConnectionEvent.HLF_NONE:
                        sb.append("NONE");
                        break;
                    case WifiMetricsProto.ConnectionEvent.HLF_DHCP:
                        sb.append("DHCP");
                        break;
                    case WifiMetricsProto.ConnectionEvent.HLF_NO_INTERNET:
                        sb.append("NO_INTERNET");
                        break;
                    case WifiMetricsProto.ConnectionEvent.HLF_UNWANTED:
                        sb.append("UNWANTED");
                        break;
                    default:
                        sb.append("UNKNOWN");
                        break;
                }
                sb.append(", signalStrength=");
                sb.append(mConnectionEvent.signalStrength);
                sb.append(", wifiState=");
                switch(mWifiState) {
                    case WifiMetricsProto.WifiLog.WIFI_DISABLED:
                        sb.append("WIFI_DISABLED");
                        break;
                    case WifiMetricsProto.WifiLog.WIFI_DISCONNECTED:
                        sb.append("WIFI_DISCONNECTED");
                        break;
                    case WifiMetricsProto.WifiLog.WIFI_ASSOCIATED:
                        sb.append("WIFI_ASSOCIATED");
                        break;
                    default:
                        sb.append("WIFI_UNKNOWN");
                        break;
                }
                sb.append(", screenOn=");
                sb.append(mScreenOn);
                sb.append(". mRouterFingerprint: ");
                sb.append(mRouterFingerPrint.toString());
            }
            return sb.toString();
        }
    }

    public WifiMetrics(Clock clock, Looper looper) {
        mClock = clock;
        mCurrentConnectionEvent = null;
        mScreenOn = true;
        mWifiState = WifiMetricsProto.WifiLog.WIFI_DISABLED;
        mRecordStartTimeSec = mClock.getElapsedSinceBootMillis() / 1000;

        mHandler = new Handler(looper) {
            public void handleMessage(Message msg) {
                synchronized (mLock) {
                    processMessage(msg);
                }
            }
        };
    }

    // Values used for indexing SystemStateEntries
    private static final int SCREEN_ON = 1;
    private static final int SCREEN_OFF = 0;

    /**
     * Create a new connection event. Call when wifi attempts to make a new network connection
     * If there is a current 'un-ended' connection event, it will be ended with UNKNOWN connectivity
     * failure code.
     * Gathers and sets the RouterFingerPrint data as well
     *
     * @param config WifiConfiguration of the config used for the current connection attempt
     * @param roamType Roam type that caused connection attempt, see WifiMetricsProto.WifiLog.ROAM_X
     */
    public void startConnectionEvent(WifiConfiguration config, String targetBSSID, int roamType) {
        synchronized (mLock) {
            // Check if this is overlapping another current connection event
            if (mCurrentConnectionEvent != null) {
                //Is this new Connection Event the same as the current one
                if (mCurrentConnectionEvent.mConfigSsid != null
                        && mCurrentConnectionEvent.mConfigBssid != null
                        && config != null
                        && mCurrentConnectionEvent.mConfigSsid.equals(config.SSID)
                        && (mCurrentConnectionEvent.mConfigBssid.equals("any")
                        || mCurrentConnectionEvent.mConfigBssid.equals(targetBSSID))) {
                    mCurrentConnectionEvent.mConfigBssid = targetBSSID;
                    // End Connection Event due to new connection attempt to the same network
                    endConnectionEvent(ConnectionEvent.FAILURE_REDUNDANT_CONNECTION_ATTEMPT,
                            WifiMetricsProto.ConnectionEvent.HLF_NONE);
                } else {
                    // End Connection Event due to new connection attempt to different network
                    endConnectionEvent(ConnectionEvent.FAILURE_NEW_CONNECTION_ATTEMPT,
                            WifiMetricsProto.ConnectionEvent.HLF_NONE);
                }
            }
            //If past maximum connection events, start removing the oldest
            while(mConnectionEventList.size() >= MAX_CONNECTION_EVENTS) {
                mConnectionEventList.remove(0);
            }
            mCurrentConnectionEvent = new ConnectionEvent();
            mCurrentConnectionEvent.mConnectionEvent.startTimeMillis =
                    mClock.getWallClockMillis();
            mCurrentConnectionEvent.mConfigBssid = targetBSSID;
            mCurrentConnectionEvent.mConnectionEvent.roamType = roamType;
            mCurrentConnectionEvent.mRouterFingerPrint.updateFromWifiConfiguration(config);
            mCurrentConnectionEvent.mConfigBssid = "any";
            mCurrentConnectionEvent.mRealStartTime = mClock.getElapsedSinceBootMillis();
            mCurrentConnectionEvent.mWifiState = mWifiState;
            mCurrentConnectionEvent.mScreenOn = mScreenOn;
            mConnectionEventList.add(mCurrentConnectionEvent);
            mScanResultRssiTimestampMillis = -1;
            if (config != null) {
                ScanResult candidate = config.getNetworkSelectionStatus().getCandidate();
                if (candidate != null) {
                    // Cache the RSSI of the candidate, as the connection event level is updated
                    // from other sources (polls, bssid_associations) and delta requires the
                    // scanResult rssi
                    mScanResultRssi = candidate.level;
                    mScanResultRssiTimestampMillis = mClock.getElapsedSinceBootMillis();
                }
            }
        }
    }

    /**
     * set the RoamType of the current ConnectionEvent (if any)
     */
    public void setConnectionEventRoamType(int roamType) {
        synchronized (mLock) {
            if (mCurrentConnectionEvent != null) {
                mCurrentConnectionEvent.mConnectionEvent.roamType = roamType;
            }
        }
    }

    /**
     * Set AP related metrics from ScanDetail
     */
    public void setConnectionScanDetail(ScanDetail scanDetail) {
        synchronized (mLock) {
            if (mCurrentConnectionEvent != null && scanDetail != null) {
                NetworkDetail networkDetail = scanDetail.getNetworkDetail();
                ScanResult scanResult = scanDetail.getScanResult();
                //Ensure that we have a networkDetail, and that it corresponds to the currently
                //tracked connection attempt
                if (networkDetail != null && scanResult != null
                        && mCurrentConnectionEvent.mConfigSsid != null
                        && mCurrentConnectionEvent.mConfigSsid
                        .equals("\"" + networkDetail.getSSID() + "\"")) {
                    updateMetricsFromNetworkDetail(networkDetail);
                    updateMetricsFromScanResult(scanResult);
                }
            }
        }
    }

    /**
     * End a Connection event record. Call when wifi connection attempt succeeds or fails.
     * If a Connection event has not been started and is active when .end is called, a new one is
     * created with zero duration.
     *
     * @param level2FailureCode Level 2 failure code returned by supplicant
     * @param connectivityFailureCode WifiMetricsProto.ConnectionEvent.HLF_X
     */
    public void endConnectionEvent(int level2FailureCode, int connectivityFailureCode) {
        synchronized (mLock) {
            if (mCurrentConnectionEvent != null) {
                boolean result = (level2FailureCode == 1)
                        && (connectivityFailureCode == WifiMetricsProto.ConnectionEvent.HLF_NONE);
                mCurrentConnectionEvent.mConnectionEvent.connectionResult = result ? 1 : 0;
                mCurrentConnectionEvent.mRealEndTime = mClock.getElapsedSinceBootMillis();
                mCurrentConnectionEvent.mConnectionEvent.durationTakenToConnectMillis = (int)
                        (mCurrentConnectionEvent.mRealEndTime
                        - mCurrentConnectionEvent.mRealStartTime);
                mCurrentConnectionEvent.mConnectionEvent.level2FailureCode = level2FailureCode;
                mCurrentConnectionEvent.mConnectionEvent.connectivityLevelFailureCode =
                        connectivityFailureCode;
                // ConnectionEvent already added to ConnectionEvents List. Safe to null current here
                mCurrentConnectionEvent = null;
                if (!result) {
                    mScanResultRssiTimestampMillis = -1;
                }
            }
        }
    }

    /**
     * Set ConnectionEvent DTIM Interval (if set), and 802.11 Connection mode, from NetworkDetail
     */
    private void updateMetricsFromNetworkDetail(NetworkDetail networkDetail) {
        int dtimInterval = networkDetail.getDtimInterval();
        if (dtimInterval > 0) {
            mCurrentConnectionEvent.mRouterFingerPrint.mRouterFingerPrintProto.dtim =
                    dtimInterval;
        }
        int connectionWifiMode;
        switch (networkDetail.getWifiMode()) {
            case InformationElementUtil.WifiMode.MODE_UNDEFINED:
                connectionWifiMode = WifiMetricsProto.RouterFingerPrint.ROUTER_TECH_UNKNOWN;
                break;
            case InformationElementUtil.WifiMode.MODE_11A:
                connectionWifiMode = WifiMetricsProto.RouterFingerPrint.ROUTER_TECH_A;
                break;
            case InformationElementUtil.WifiMode.MODE_11B:
                connectionWifiMode = WifiMetricsProto.RouterFingerPrint.ROUTER_TECH_B;
                break;
            case InformationElementUtil.WifiMode.MODE_11G:
                connectionWifiMode = WifiMetricsProto.RouterFingerPrint.ROUTER_TECH_G;
                break;
            case InformationElementUtil.WifiMode.MODE_11N:
                connectionWifiMode = WifiMetricsProto.RouterFingerPrint.ROUTER_TECH_N;
                break;
            case InformationElementUtil.WifiMode.MODE_11AC  :
                connectionWifiMode = WifiMetricsProto.RouterFingerPrint.ROUTER_TECH_AC;
                break;
            default:
                connectionWifiMode = WifiMetricsProto.RouterFingerPrint.ROUTER_TECH_OTHER;
                break;
        }
        mCurrentConnectionEvent.mRouterFingerPrint.mRouterFingerPrintProto
                .routerTechnology = connectionWifiMode;
    }

    /**
     * Set ConnectionEvent RSSI and authentication type from ScanResult
     */
    private void updateMetricsFromScanResult(ScanResult scanResult) {
        mCurrentConnectionEvent.mConnectionEvent.signalStrength = scanResult.level;
        mCurrentConnectionEvent.mRouterFingerPrint.mRouterFingerPrintProto.authentication =
                WifiMetricsProto.RouterFingerPrint.AUTH_OPEN;
        mCurrentConnectionEvent.mConfigBssid = scanResult.BSSID;
        if (scanResult.capabilities != null) {
            if (ScanResultUtil.isScanResultForWepNetwork(scanResult)) {
                mCurrentConnectionEvent.mRouterFingerPrint.mRouterFingerPrintProto.authentication =
                        WifiMetricsProto.RouterFingerPrint.AUTH_PERSONAL;
            } else if (ScanResultUtil.isScanResultForPskNetwork(scanResult)) {
                mCurrentConnectionEvent.mRouterFingerPrint.mRouterFingerPrintProto.authentication =
                        WifiMetricsProto.RouterFingerPrint.AUTH_PERSONAL;
            } else if (ScanResultUtil.isScanResultForEapNetwork(scanResult)) {
                mCurrentConnectionEvent.mRouterFingerPrint.mRouterFingerPrintProto.authentication =
                        WifiMetricsProto.RouterFingerPrint.AUTH_ENTERPRISE;
            }
        }
        mCurrentConnectionEvent.mRouterFingerPrint.mRouterFingerPrintProto.channelInfo =
                scanResult.frequency;
    }

    void setIsLocationEnabled(boolean enabled) {
        synchronized (mLock) {
            mWifiLogProto.isLocationEnabled = enabled;
        }
    }

    void setIsScanningAlwaysEnabled(boolean enabled) {
        synchronized (mLock) {
            mWifiLogProto.isScanningAlwaysEnabled = enabled;
        }
    }

    /**
     * Increment Non Empty Scan Results count
     */
    public void incrementNonEmptyScanResultCount() {
        if (DBG) Log.v(TAG, "incrementNonEmptyScanResultCount");
        synchronized (mLock) {
            mWifiLogProto.numNonEmptyScanResults++;
        }
    }

    /**
     * Increment Empty Scan Results count
     */
    public void incrementEmptyScanResultCount() {
        if (DBG) Log.v(TAG, "incrementEmptyScanResultCount");
        synchronized (mLock) {
            mWifiLogProto.numEmptyScanResults++;
        }
    }

    /**
     * Increment background scan count
     */
    public void incrementBackgroundScanCount() {
        if (DBG) Log.v(TAG, "incrementBackgroundScanCount");
        synchronized (mLock) {
            mWifiLogProto.numBackgroundScans++;
        }
    }

   /**
     * Get Background scan count
     */
    public int getBackgroundScanCount() {
        synchronized (mLock) {
            return mWifiLogProto.numBackgroundScans;
        }
    }

    /**
     * Increment oneshot scan count, and the associated WifiSystemScanStateCount entry
     */
    public void incrementOneshotScanCount() {
        synchronized (mLock) {
            mWifiLogProto.numOneshotScans++;
        }
        incrementWifiSystemScanStateCount(mWifiState, mScreenOn);
    }

    /**
     * Get oneshot scan count
     */
    public int getOneshotScanCount() {
        synchronized (mLock) {
            return mWifiLogProto.numOneshotScans;
        }
    }

    private String returnCodeToString(int scanReturnCode) {
        switch(scanReturnCode){
            case WifiMetricsProto.WifiLog.SCAN_UNKNOWN:
                return "SCAN_UNKNOWN";
            case WifiMetricsProto.WifiLog.SCAN_SUCCESS:
                return "SCAN_SUCCESS";
            case WifiMetricsProto.WifiLog.SCAN_FAILURE_INTERRUPTED:
                return "SCAN_FAILURE_INTERRUPTED";
            case WifiMetricsProto.WifiLog.SCAN_FAILURE_INVALID_CONFIGURATION:
                return "SCAN_FAILURE_INVALID_CONFIGURATION";
            case WifiMetricsProto.WifiLog.FAILURE_WIFI_DISABLED:
                return "FAILURE_WIFI_DISABLED";
            default:
                return "<UNKNOWN>";
        }
    }

    /**
     * Increment count of scan return code occurrence
     *
     * @param scanReturnCode Return code from scan attempt WifiMetricsProto.WifiLog.SCAN_X
     */
    public void incrementScanReturnEntry(int scanReturnCode, int countToAdd) {
        synchronized (mLock) {
            if (DBG) Log.v(TAG, "incrementScanReturnEntry " + returnCodeToString(scanReturnCode));
            int entry = mScanReturnEntries.get(scanReturnCode);
            entry += countToAdd;
            mScanReturnEntries.put(scanReturnCode, entry);
        }
    }
    /**
     * Get the count of this scanReturnCode
     * @param scanReturnCode that we are getting the count for
     */
    public int getScanReturnEntry(int scanReturnCode) {
        synchronized (mLock) {
            return mScanReturnEntries.get(scanReturnCode);
        }
    }

    private String wifiSystemStateToString(int state) {
        switch(state){
            case WifiMetricsProto.WifiLog.WIFI_UNKNOWN:
                return "WIFI_UNKNOWN";
            case WifiMetricsProto.WifiLog.WIFI_DISABLED:
                return "WIFI_DISABLED";
            case WifiMetricsProto.WifiLog.WIFI_DISCONNECTED:
                return "WIFI_DISCONNECTED";
            case WifiMetricsProto.WifiLog.WIFI_ASSOCIATED:
                return "WIFI_ASSOCIATED";
            default:
                return "default";
        }
    }

    /**
     * Increments the count of scans initiated by each wifi state, accounts for screenOn/Off
     *
     * @param state State of the system when scan was initiated, see WifiMetricsProto.WifiLog.WIFI_X
     * @param screenOn Is the screen on
     */
    public void incrementWifiSystemScanStateCount(int state, boolean screenOn) {
        synchronized (mLock) {
            if (DBG) {
                Log.v(TAG, "incrementWifiSystemScanStateCount " + wifiSystemStateToString(state)
                        + " " + screenOn);
            }
            int index = (state * 2) + (screenOn ? SCREEN_ON : SCREEN_OFF);
            int entry = mWifiSystemStateEntries.get(index);
            entry++;
            mWifiSystemStateEntries.put(index, entry);
        }
    }

    /**
     * Get the count of this system State Entry
     */
    public int getSystemStateCount(int state, boolean screenOn) {
        synchronized (mLock) {
            int index = state * 2 + (screenOn ? SCREEN_ON : SCREEN_OFF);
            return mWifiSystemStateEntries.get(index);
        }
    }

    /**
     * Increment number of times the Watchdog of Last Resort triggered, resetting the wifi stack
     */
    public void incrementNumLastResortWatchdogTriggers() {
        synchronized (mLock) {
            mWifiLogProto.numLastResortWatchdogTriggers++;
        }
    }
    /**
     * @param count number of networks over bad association threshold when watchdog triggered
     */
    public void addCountToNumLastResortWatchdogBadAssociationNetworksTotal(int count) {
        synchronized (mLock) {
            mWifiLogProto.numLastResortWatchdogBadAssociationNetworksTotal += count;
        }
    }
    /**
     * @param count number of networks over bad authentication threshold when watchdog triggered
     */
    public void addCountToNumLastResortWatchdogBadAuthenticationNetworksTotal(int count) {
        synchronized (mLock) {
            mWifiLogProto.numLastResortWatchdogBadAuthenticationNetworksTotal += count;
        }
    }
    /**
     * @param count number of networks over bad dhcp threshold when watchdog triggered
     */
    public void addCountToNumLastResortWatchdogBadDhcpNetworksTotal(int count) {
        synchronized (mLock) {
            mWifiLogProto.numLastResortWatchdogBadDhcpNetworksTotal += count;
        }
    }
    /**
     * @param count number of networks over bad other threshold when watchdog triggered
     */
    public void addCountToNumLastResortWatchdogBadOtherNetworksTotal(int count) {
        synchronized (mLock) {
            mWifiLogProto.numLastResortWatchdogBadOtherNetworksTotal += count;
        }
    }
    /**
     * @param count number of networks seen when watchdog triggered
     */
    public void addCountToNumLastResortWatchdogAvailableNetworksTotal(int count) {
        synchronized (mLock) {
            mWifiLogProto.numLastResortWatchdogAvailableNetworksTotal += count;
        }
    }
    /**
     * Increment count of triggers with atleast one bad association network
     */
    public void incrementNumLastResortWatchdogTriggersWithBadAssociation() {
        synchronized (mLock) {
            mWifiLogProto.numLastResortWatchdogTriggersWithBadAssociation++;
        }
    }
    /**
     * Increment count of triggers with atleast one bad authentication network
     */
    public void incrementNumLastResortWatchdogTriggersWithBadAuthentication() {
        synchronized (mLock) {
            mWifiLogProto.numLastResortWatchdogTriggersWithBadAuthentication++;
        }
    }
    /**
     * Increment count of triggers with atleast one bad dhcp network
     */
    public void incrementNumLastResortWatchdogTriggersWithBadDhcp() {
        synchronized (mLock) {
            mWifiLogProto.numLastResortWatchdogTriggersWithBadDhcp++;
        }
    }
    /**
     * Increment count of triggers with atleast one bad other network
     */
    public void incrementNumLastResortWatchdogTriggersWithBadOther() {
        synchronized (mLock) {
            mWifiLogProto.numLastResortWatchdogTriggersWithBadOther++;
        }
    }

    /**
     * Increment number of times connectivity watchdog confirmed pno is working
     */
    public void incrementNumConnectivityWatchdogPnoGood() {
        synchronized (mLock) {
            mWifiLogProto.numConnectivityWatchdogPnoGood++;
        }
    }
    /**
     * Increment number of times connectivity watchdog found pno not working
     */
    public void incrementNumConnectivityWatchdogPnoBad() {
        synchronized (mLock) {
            mWifiLogProto.numConnectivityWatchdogPnoBad++;
        }
    }
    /**
     * Increment number of times connectivity watchdog confirmed background scan is working
     */
    public void incrementNumConnectivityWatchdogBackgroundGood() {
        synchronized (mLock) {
            mWifiLogProto.numConnectivityWatchdogBackgroundGood++;
        }
    }
    /**
     * Increment number of times connectivity watchdog found background scan not working
     */
    public void incrementNumConnectivityWatchdogBackgroundBad() {
        synchronized (mLock) {
            mWifiLogProto.numConnectivityWatchdogBackgroundBad++;
        }
    }

    /**
     * Increment various poll related metrics, and cache performance data for StaEvent logging
     */
    public void handlePollResult(WifiInfo wifiInfo) {
        mLastPollRssi = wifiInfo.getRssi();
        mLastPollLinkSpeed = wifiInfo.getLinkSpeed();
        mLastPollFreq = wifiInfo.getFrequency();
        incrementRssiPollRssiCount(mLastPollRssi);
    }

    /**
     * Increment occurence count of RSSI level from RSSI poll.
     * Ignores rssi values outside the bounds of [MIN_RSSI_POLL, MAX_RSSI_POLL]
     */
    public void incrementRssiPollRssiCount(int rssi) {
        if (!(rssi >= MIN_RSSI_POLL && rssi <= MAX_RSSI_POLL)) {
            return;
        }
        synchronized (mLock) {
            int count = mRssiPollCounts.get(rssi);
            mRssiPollCounts.put(rssi, count + 1);
            maybeIncrementRssiDeltaCount(rssi - mScanResultRssi);
        }
    }

    /**
     * Increment occurence count of difference between scan result RSSI and the first RSSI poll.
     * Ignores rssi values outside the bounds of [MIN_RSSI_DELTA, MAX_RSSI_DELTA]
     * mLock must be held when calling this method.
     */
    private void maybeIncrementRssiDeltaCount(int rssi) {
        // Check if this RSSI poll is close enough to a scan result RSSI to log a delta value
        if (mScanResultRssiTimestampMillis >= 0) {
            long timeDelta = mClock.getElapsedSinceBootMillis() - mScanResultRssiTimestampMillis;
            if (timeDelta <= TIMEOUT_RSSI_DELTA_MILLIS) {
                if (rssi >= MIN_RSSI_DELTA && rssi <= MAX_RSSI_DELTA) {
                    int count = mRssiDeltaCounts.get(rssi);
                    mRssiDeltaCounts.put(rssi, count + 1);
                }
            }
            mScanResultRssiTimestampMillis = -1;
        }
    }

    /**
     * Increment count of Watchdog successes.
     */
    public void incrementNumLastResortWatchdogSuccesses() {
        synchronized (mLock) {
            mWifiLogProto.numLastResortWatchdogSuccesses++;
        }
    }

    /**
     * Increments the count of alerts by alert reason.
     *
     * @param reason The cause of the alert. The reason values are driver-specific.
     */
    public void incrementAlertReasonCount(int reason) {
        if (reason > WifiLoggerHal.WIFI_ALERT_REASON_MAX
                || reason < WifiLoggerHal.WIFI_ALERT_REASON_MIN) {
            reason = WifiLoggerHal.WIFI_ALERT_REASON_RESERVED;
        }
        synchronized (mLock) {
            int alertCount = mWifiAlertReasonCounts.get(reason);
            mWifiAlertReasonCounts.put(reason, alertCount + 1);
        }
    }

    /**
     * Counts all the different types of networks seen in a set of scan results
     */
    public void countScanResults(List<ScanDetail> scanDetails) {
        if (scanDetails == null) {
            return;
        }
        int totalResults = 0;
        int openNetworks = 0;
        int personalNetworks = 0;
        int enterpriseNetworks = 0;
        int hiddenNetworks = 0;
        int hotspot2r1Networks = 0;
        int hotspot2r2Networks = 0;
        for (ScanDetail scanDetail : scanDetails) {
            NetworkDetail networkDetail = scanDetail.getNetworkDetail();
            ScanResult scanResult = scanDetail.getScanResult();
            totalResults++;
            if (networkDetail != null) {
                if (networkDetail.isHiddenBeaconFrame()) {
                    hiddenNetworks++;
                }
                if (networkDetail.getHSRelease() != null) {
                    if (networkDetail.getHSRelease() == NetworkDetail.HSRelease.R1) {
                        hotspot2r1Networks++;
                    } else if (networkDetail.getHSRelease() == NetworkDetail.HSRelease.R2) {
                        hotspot2r2Networks++;
                    }
                }
            }
            if (scanResult != null && scanResult.capabilities != null) {
                if (ScanResultUtil.isScanResultForEapNetwork(scanResult)) {
                    enterpriseNetworks++;
                } else if (ScanResultUtil.isScanResultForPskNetwork(scanResult)
                        || ScanResultUtil.isScanResultForWepNetwork(scanResult)) {
                    personalNetworks++;
                } else {
                    openNetworks++;
                }
            }
        }
        synchronized (mLock) {
            mWifiLogProto.numTotalScanResults += totalResults;
            mWifiLogProto.numOpenNetworkScanResults += openNetworks;
            mWifiLogProto.numPersonalNetworkScanResults += personalNetworks;
            mWifiLogProto.numEnterpriseNetworkScanResults += enterpriseNetworks;
            mWifiLogProto.numHiddenNetworkScanResults += hiddenNetworks;
            mWifiLogProto.numHotspot2R1NetworkScanResults += hotspot2r1Networks;
            mWifiLogProto.numHotspot2R2NetworkScanResults += hotspot2r2Networks;
            mWifiLogProto.numScans++;
        }
    }

    /**
     * Increments occurence of a particular wifi score calculated
     * in WifiScoreReport by current connected network. Scores are bounded
     * within  [MIN_WIFI_SCORE, MAX_WIFI_SCORE] to limit size of SparseArray
     */
    public void incrementWifiScoreCount(int score) {
        if (score < MIN_WIFI_SCORE || score > MAX_WIFI_SCORE) {
            return;
        }
        synchronized (mLock) {
            int count = mWifiScoreCounts.get(score);
            mWifiScoreCounts.put(score, count + 1);
        }
    }

    /**
     * Increments occurence of the results from attempting to start SoftAp.
     * Maps the |result| and WifiManager |failureCode| constant to proto defined SoftApStartResult
     * codes.
     */
    public void incrementSoftApStartResult(boolean result, int failureCode) {
        synchronized (mLock) {
            if (result) {
                int count = mSoftApManagerReturnCodeCounts.get(
                        WifiMetricsProto.SoftApReturnCodeCount.SOFT_AP_STARTED_SUCCESSFULLY);
                mSoftApManagerReturnCodeCounts.put(
                        WifiMetricsProto.SoftApReturnCodeCount.SOFT_AP_STARTED_SUCCESSFULLY,
                        count + 1);
                return;
            }

            // now increment failure modes - if not explicitly handled, dump into the general
            // error bucket.
            if (failureCode == WifiManager.SAP_START_FAILURE_NO_CHANNEL) {
                int count = mSoftApManagerReturnCodeCounts.get(
                        WifiMetricsProto.SoftApReturnCodeCount.SOFT_AP_FAILED_NO_CHANNEL);
                mSoftApManagerReturnCodeCounts.put(
                        WifiMetricsProto.SoftApReturnCodeCount.SOFT_AP_FAILED_NO_CHANNEL,
                        count + 1);
            } else {
                // failure mode not tracked at this time...  count as a general error for now.
                int count = mSoftApManagerReturnCodeCounts.get(
                        WifiMetricsProto.SoftApReturnCodeCount.SOFT_AP_FAILED_GENERAL_ERROR);
                mSoftApManagerReturnCodeCounts.put(
                        WifiMetricsProto.SoftApReturnCodeCount.SOFT_AP_FAILED_GENERAL_ERROR,
                        count + 1);
            }
        }
    }

    /**
     * Increment number of times the HAL crashed.
     */
    public void incrementNumHalCrashes() {
        synchronized (mLock) {
            mWifiLogProto.numHalCrashes++;
        }
    }

    /**
     * Increment number of times the Wificond crashed.
     */
    public void incrementNumWificondCrashes() {
        synchronized (mLock) {
            mWifiLogProto.numWificondCrashes++;
        }
    }

    /**
     * Increment number of times the wifi on failed due to an error in HAL.
     */
    public void incrementNumWifiOnFailureDueToHal() {
        synchronized (mLock) {
            mWifiLogProto.numWifiOnFailureDueToHal++;
        }
    }

    /**
     * Increment number of times the wifi on failed due to an error in wificond.
     */
    public void incrementNumWifiOnFailureDueToWificond() {
        synchronized (mLock) {
            mWifiLogProto.numWifiOnFailureDueToWificond++;
        }
    }


    public static final String PROTO_DUMP_ARG = "wifiMetricsProto";
    public static final String CLEAN_DUMP_ARG = "clean";

    /**
     * Dump all WifiMetrics. Collects some metrics from ConfigStore, Settings and WifiManager
     * at this time.
     *
     * @param fd unused
     * @param pw PrintWriter for writing dump to
     * @param args unused
     */
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        synchronized (mLock) {
            if (args != null && args.length > 0 && PROTO_DUMP_ARG.equals(args[0])) {
                // Dump serialized WifiLog proto
                consolidateProto(true);
                for (ConnectionEvent event : mConnectionEventList) {
                    if (mCurrentConnectionEvent != event) {
                        //indicate that automatic bug report has been taken for all valid
                        //connection events
                        event.mConnectionEvent.automaticBugReportTaken = true;
                    }
                }
                byte[] wifiMetricsProto = WifiMetricsProto.WifiLog.toByteArray(mWifiLogProto);
                String metricsProtoDump = Base64.encodeToString(wifiMetricsProto, Base64.DEFAULT);
                if (args.length > 1 && CLEAN_DUMP_ARG.equals(args[1])) {
                    // Output metrics proto bytes (base64) and nothing else
                    pw.print(metricsProtoDump);
                } else {
                    // Tag the start and end of the metrics proto bytes
                    pw.println("WifiMetrics:");
                    pw.println(metricsProtoDump);
                    pw.println("EndWifiMetrics");
                }
                clear();
            } else {
                pw.println("WifiMetrics:");
                pw.println("mConnectionEvents:");
                for (ConnectionEvent event : mConnectionEventList) {
                    String eventLine = event.toString();
                    if (event == mCurrentConnectionEvent) {
                        eventLine += "CURRENTLY OPEN EVENT";
                    }
                    pw.println(eventLine);
                }
                pw.println("mWifiLogProto.numSavedNetworks=" + mWifiLogProto.numSavedNetworks);
                pw.println("mWifiLogProto.numOpenNetworks=" + mWifiLogProto.numOpenNetworks);
                pw.println("mWifiLogProto.numPersonalNetworks="
                        + mWifiLogProto.numPersonalNetworks);
                pw.println("mWifiLogProto.numEnterpriseNetworks="
                        + mWifiLogProto.numEnterpriseNetworks);
                pw.println("mWifiLogProto.numHiddenNetworks=" + mWifiLogProto.numHiddenNetworks);
                pw.println("mWifiLogProto.numPasspointNetworks="
                        + mWifiLogProto.numPasspointNetworks);
                pw.println("mWifiLogProto.isLocationEnabled=" + mWifiLogProto.isLocationEnabled);
                pw.println("mWifiLogProto.isScanningAlwaysEnabled="
                        + mWifiLogProto.isScanningAlwaysEnabled);
                pw.println("mWifiLogProto.numNetworksAddedByUser="
                        + mWifiLogProto.numNetworksAddedByUser);
                pw.println("mWifiLogProto.numNetworksAddedByApps="
                        + mWifiLogProto.numNetworksAddedByApps);
                pw.println("mWifiLogProto.numNonEmptyScanResults="
                        + mWifiLogProto.numNonEmptyScanResults);
                pw.println("mWifiLogProto.numEmptyScanResults="
                        + mWifiLogProto.numEmptyScanResults);
                pw.println("mWifiLogProto.numOneshotScans="
                        + mWifiLogProto.numOneshotScans);
                pw.println("mWifiLogProto.numBackgroundScans="
                        + mWifiLogProto.numBackgroundScans);

                pw.println("mScanReturnEntries:");
                pw.println("  SCAN_UNKNOWN: " + getScanReturnEntry(
                        WifiMetricsProto.WifiLog.SCAN_UNKNOWN));
                pw.println("  SCAN_SUCCESS: " + getScanReturnEntry(
                        WifiMetricsProto.WifiLog.SCAN_SUCCESS));
                pw.println("  SCAN_FAILURE_INTERRUPTED: " + getScanReturnEntry(
                        WifiMetricsProto.WifiLog.SCAN_FAILURE_INTERRUPTED));
                pw.println("  SCAN_FAILURE_INVALID_CONFIGURATION: " + getScanReturnEntry(
                        WifiMetricsProto.WifiLog.SCAN_FAILURE_INVALID_CONFIGURATION));
                pw.println("  FAILURE_WIFI_DISABLED: " + getScanReturnEntry(
                        WifiMetricsProto.WifiLog.FAILURE_WIFI_DISABLED));

                pw.println("mSystemStateEntries: <state><screenOn> : <scansInitiated>");
                pw.println("  WIFI_UNKNOWN       ON: "
                        + getSystemStateCount(WifiMetricsProto.WifiLog.WIFI_UNKNOWN, true));
                pw.println("  WIFI_DISABLED      ON: "
                        + getSystemStateCount(WifiMetricsProto.WifiLog.WIFI_DISABLED, true));
                pw.println("  WIFI_DISCONNECTED  ON: "
                        + getSystemStateCount(WifiMetricsProto.WifiLog.WIFI_DISCONNECTED, true));
                pw.println("  WIFI_ASSOCIATED    ON: "
                        + getSystemStateCount(WifiMetricsProto.WifiLog.WIFI_ASSOCIATED, true));
                pw.println("  WIFI_UNKNOWN      OFF: "
                        + getSystemStateCount(WifiMetricsProto.WifiLog.WIFI_UNKNOWN, false));
                pw.println("  WIFI_DISABLED     OFF: "
                        + getSystemStateCount(WifiMetricsProto.WifiLog.WIFI_DISABLED, false));
                pw.println("  WIFI_DISCONNECTED OFF: "
                        + getSystemStateCount(WifiMetricsProto.WifiLog.WIFI_DISCONNECTED, false));
                pw.println("  WIFI_ASSOCIATED   OFF: "
                        + getSystemStateCount(WifiMetricsProto.WifiLog.WIFI_ASSOCIATED, false));
                pw.println("mWifiLogProto.numConnectivityWatchdogPnoGood="
                        + mWifiLogProto.numConnectivityWatchdogPnoGood);
                pw.println("mWifiLogProto.numConnectivityWatchdogPnoBad="
                        + mWifiLogProto.numConnectivityWatchdogPnoBad);
                pw.println("mWifiLogProto.numConnectivityWatchdogBackgroundGood="
                        + mWifiLogProto.numConnectivityWatchdogBackgroundGood);
                pw.println("mWifiLogProto.numConnectivityWatchdogBackgroundBad="
                        + mWifiLogProto.numConnectivityWatchdogBackgroundBad);
                pw.println("mWifiLogProto.numLastResortWatchdogTriggers="
                        + mWifiLogProto.numLastResortWatchdogTriggers);
                pw.println("mWifiLogProto.numLastResortWatchdogBadAssociationNetworksTotal="
                        + mWifiLogProto.numLastResortWatchdogBadAssociationNetworksTotal);
                pw.println("mWifiLogProto.numLastResortWatchdogBadAuthenticationNetworksTotal="
                        + mWifiLogProto.numLastResortWatchdogBadAuthenticationNetworksTotal);
                pw.println("mWifiLogProto.numLastResortWatchdogBadDhcpNetworksTotal="
                        + mWifiLogProto.numLastResortWatchdogBadDhcpNetworksTotal);
                pw.println("mWifiLogProto.numLastResortWatchdogBadOtherNetworksTotal="
                        + mWifiLogProto.numLastResortWatchdogBadOtherNetworksTotal);
                pw.println("mWifiLogProto.numLastResortWatchdogAvailableNetworksTotal="
                        + mWifiLogProto.numLastResortWatchdogAvailableNetworksTotal);
                pw.println("mWifiLogProto.numLastResortWatchdogTriggersWithBadAssociation="
                        + mWifiLogProto.numLastResortWatchdogTriggersWithBadAssociation);
                pw.println("mWifiLogProto.numLastResortWatchdogTriggersWithBadAuthentication="
                        + mWifiLogProto.numLastResortWatchdogTriggersWithBadAuthentication);
                pw.println("mWifiLogProto.numLastResortWatchdogTriggersWithBadDhcp="
                        + mWifiLogProto.numLastResortWatchdogTriggersWithBadDhcp);
                pw.println("mWifiLogProto.numLastResortWatchdogTriggersWithBadOther="
                        + mWifiLogProto.numLastResortWatchdogTriggersWithBadOther);
                pw.println("mWifiLogProto.numLastResortWatchdogSuccesses="
                        + mWifiLogProto.numLastResortWatchdogSuccesses);
                pw.println("mWifiLogProto.recordDurationSec="
                        + ((mClock.getElapsedSinceBootMillis() / 1000) - mRecordStartTimeSec));
                pw.println("mWifiLogProto.rssiPollRssiCount: Printing counts for [" + MIN_RSSI_POLL
                        + ", " + MAX_RSSI_POLL + "]");
                StringBuilder sb = new StringBuilder();
                for (int i = MIN_RSSI_POLL; i <= MAX_RSSI_POLL; i++) {
                    sb.append(mRssiPollCounts.get(i) + " ");
                }
                pw.println("  " + sb.toString());
                pw.println("mWifiLogProto.rssiPollDeltaCount: Printing counts for ["
                        + MIN_RSSI_DELTA + ", " + MAX_RSSI_DELTA + "]");
                sb.setLength(0);
                for (int i = MIN_RSSI_DELTA; i <= MAX_RSSI_DELTA; i++) {
                    sb.append(mRssiDeltaCounts.get(i) + " ");
                }
                pw.println("  " + sb.toString());
                pw.print("mWifiLogProto.alertReasonCounts=");
                sb.setLength(0);
                for (int i = WifiLoggerHal.WIFI_ALERT_REASON_MIN;
                        i <= WifiLoggerHal.WIFI_ALERT_REASON_MAX; i++) {
                    int count = mWifiAlertReasonCounts.get(i);
                    if (count > 0) {
                        sb.append("(" + i + "," + count + "),");
                    }
                }
                if (sb.length() > 1) {
                    sb.setLength(sb.length() - 1);  // strip trailing comma
                    pw.println(sb.toString());
                } else {
                    pw.println("()");
                }
                pw.println("mWifiLogProto.numTotalScanResults="
                        + mWifiLogProto.numTotalScanResults);
                pw.println("mWifiLogProto.numOpenNetworkScanResults="
                        + mWifiLogProto.numOpenNetworkScanResults);
                pw.println("mWifiLogProto.numPersonalNetworkScanResults="
                        + mWifiLogProto.numPersonalNetworkScanResults);
                pw.println("mWifiLogProto.numEnterpriseNetworkScanResults="
                        + mWifiLogProto.numEnterpriseNetworkScanResults);
                pw.println("mWifiLogProto.numHiddenNetworkScanResults="
                        + mWifiLogProto.numHiddenNetworkScanResults);
                pw.println("mWifiLogProto.numHotspot2R1NetworkScanResults="
                        + mWifiLogProto.numHotspot2R1NetworkScanResults);
                pw.println("mWifiLogProto.numHotspot2R2NetworkScanResults="
                        + mWifiLogProto.numHotspot2R2NetworkScanResults);
                pw.println("mWifiLogProto.numScans=" + mWifiLogProto.numScans);
                pw.println("mWifiLogProto.WifiScoreCount: [" + MIN_WIFI_SCORE + ", "
                        + MAX_WIFI_SCORE + "]");
                for (int i = 0; i <= MAX_WIFI_SCORE; i++) {
                    pw.print(mWifiScoreCounts.get(i) + " ");
                }
                pw.println(); // add a line after wifi scores
                pw.println("mWifiLogProto.SoftApManagerReturnCodeCounts:");
                pw.println("  SUCCESS: " + mSoftApManagerReturnCodeCounts.get(
                        WifiMetricsProto.SoftApReturnCodeCount.SOFT_AP_STARTED_SUCCESSFULLY));
                pw.println("  FAILED_GENERAL_ERROR: " + mSoftApManagerReturnCodeCounts.get(
                        WifiMetricsProto.SoftApReturnCodeCount.SOFT_AP_FAILED_GENERAL_ERROR));
                pw.println("  FAILED_NO_CHANNEL: " + mSoftApManagerReturnCodeCounts.get(
                        WifiMetricsProto.SoftApReturnCodeCount.SOFT_AP_FAILED_NO_CHANNEL));
                pw.print("\n");
                pw.println("mWifiLogProto.numHalCrashes="
                        + mWifiLogProto.numHalCrashes);
                pw.println("mWifiLogProto.numWificondCrashes="
                        + mWifiLogProto.numWificondCrashes);
                pw.println("mWifiLogProto.numWifiOnFailureDueToHal="
                        + mWifiLogProto.numWifiOnFailureDueToHal);
                pw.println("mWifiLogProto.numWifiOnFailureDueToWificond="
                        + mWifiLogProto.numWifiOnFailureDueToWificond);
                pw.println("StaEventList:");
                for (StaEvent event : mStaEventList) {
                    pw.println(staEventToString(event));
                }
            }
        }
    }


    /**
     * Update various counts of saved network types
     * @param networks List of WifiConfigurations representing all saved networks, must not be null
     */
    public void updateSavedNetworks(List<WifiConfiguration> networks) {
        synchronized (mLock) {
            mWifiLogProto.numSavedNetworks = networks.size();
            mWifiLogProto.numOpenNetworks = 0;
            mWifiLogProto.numPersonalNetworks = 0;
            mWifiLogProto.numEnterpriseNetworks = 0;
            mWifiLogProto.numNetworksAddedByUser = 0;
            mWifiLogProto.numNetworksAddedByApps = 0;
            mWifiLogProto.numHiddenNetworks = 0;
            mWifiLogProto.numPasspointNetworks = 0;
            for (WifiConfiguration config : networks) {
                if (config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.NONE)) {
                    mWifiLogProto.numOpenNetworks++;
                } else if (config.isEnterprise()) {
                    mWifiLogProto.numEnterpriseNetworks++;
                } else {
                    mWifiLogProto.numPersonalNetworks++;
                }
                if (config.selfAdded) {
                    mWifiLogProto.numNetworksAddedByUser++;
                } else {
                    mWifiLogProto.numNetworksAddedByApps++;
                }
                if (config.hiddenSSID) {
                    mWifiLogProto.numHiddenNetworks++;
                }
                if (config.isPasspoint()) {
                    mWifiLogProto.numPasspointNetworks++;
                }
            }
        }
    }

    /**
     * append the separate ConnectionEvent, SystemStateEntry and ScanReturnCode collections to their
     * respective lists within mWifiLogProto
     *
     * @param incremental Only include ConnectionEvents created since last automatic bug report
     */
    private void consolidateProto(boolean incremental) {
        List<WifiMetricsProto.ConnectionEvent> events = new ArrayList<>();
        List<WifiMetricsProto.RssiPollCount> rssis = new ArrayList<>();
        List<WifiMetricsProto.RssiPollCount> rssiDeltas = new ArrayList<>();
        List<WifiMetricsProto.AlertReasonCount> alertReasons = new ArrayList<>();
        List<WifiMetricsProto.WifiScoreCount> scores = new ArrayList<>();
        synchronized (mLock) {
            for (ConnectionEvent event : mConnectionEventList) {
                // If this is not incremental, dump full ConnectionEvent list
                // Else Dump all un-dumped events except for the current one
                if (!incremental || ((mCurrentConnectionEvent != event)
                        && !event.mConnectionEvent.automaticBugReportTaken)) {
                    //Get all ConnectionEvents that haven not been dumped as a proto, also exclude
                    //the current active un-ended connection event
                    events.add(event.mConnectionEvent);
                    if (incremental) {
                        event.mConnectionEvent.automaticBugReportTaken = true;
                    }
                }
            }
            if (events.size() > 0) {
                mWifiLogProto.connectionEvent = events.toArray(mWifiLogProto.connectionEvent);
            }

            //Convert the SparseIntArray of scanReturnEntry integers into ScanReturnEntry proto list
            mWifiLogProto.scanReturnEntries =
                    new WifiMetricsProto.WifiLog.ScanReturnEntry[mScanReturnEntries.size()];
            for (int i = 0; i < mScanReturnEntries.size(); i++) {
                mWifiLogProto.scanReturnEntries[i] = new WifiMetricsProto.WifiLog.ScanReturnEntry();
                mWifiLogProto.scanReturnEntries[i].scanReturnCode = mScanReturnEntries.keyAt(i);
                mWifiLogProto.scanReturnEntries[i].scanResultsCount = mScanReturnEntries.valueAt(i);
            }

            // Convert the SparseIntArray of systemStateEntry into WifiSystemStateEntry proto list
            // This one is slightly more complex, as the Sparse are indexed with:
            //     key: wifiState * 2 + isScreenOn, value: wifiStateCount
            mWifiLogProto.wifiSystemStateEntries =
                    new WifiMetricsProto.WifiLog
                    .WifiSystemStateEntry[mWifiSystemStateEntries.size()];
            for (int i = 0; i < mWifiSystemStateEntries.size(); i++) {
                mWifiLogProto.wifiSystemStateEntries[i] =
                        new WifiMetricsProto.WifiLog.WifiSystemStateEntry();
                mWifiLogProto.wifiSystemStateEntries[i].wifiState =
                        mWifiSystemStateEntries.keyAt(i) / 2;
                mWifiLogProto.wifiSystemStateEntries[i].wifiStateCount =
                        mWifiSystemStateEntries.valueAt(i);
                mWifiLogProto.wifiSystemStateEntries[i].isScreenOn =
                        (mWifiSystemStateEntries.keyAt(i) % 2) > 0;
            }
            mWifiLogProto.recordDurationSec = (int) ((mClock.getElapsedSinceBootMillis() / 1000)
                    - mRecordStartTimeSec);

            /**
             * Convert the SparseIntArray of RSSI poll rssi's and counts to the proto's repeated
             * IntKeyVal array.
             */
            for (int i = 0; i < mRssiPollCounts.size(); i++) {
                WifiMetricsProto.RssiPollCount keyVal = new WifiMetricsProto.RssiPollCount();
                keyVal.rssi = mRssiPollCounts.keyAt(i);
                keyVal.count = mRssiPollCounts.valueAt(i);
                rssis.add(keyVal);
            }
            mWifiLogProto.rssiPollRssiCount = rssis.toArray(mWifiLogProto.rssiPollRssiCount);

            /**
             * Convert the SparseIntArray of RSSI delta rssi's and counts to the proto's repeated
             * IntKeyVal array.
             */
            for (int i = 0; i < mRssiDeltaCounts.size(); i++) {
                WifiMetricsProto.RssiPollCount keyVal = new WifiMetricsProto.RssiPollCount();
                keyVal.rssi = mRssiDeltaCounts.keyAt(i);
                keyVal.count = mRssiDeltaCounts.valueAt(i);
                rssiDeltas.add(keyVal);
            }
            mWifiLogProto.rssiPollDeltaCount = rssiDeltas.toArray(mWifiLogProto.rssiPollDeltaCount);

            /**
             * Convert the SparseIntArray of alert reasons and counts to the proto's repeated
             * IntKeyVal array.
             */
            for (int i = 0; i < mWifiAlertReasonCounts.size(); i++) {
                WifiMetricsProto.AlertReasonCount keyVal = new WifiMetricsProto.AlertReasonCount();
                keyVal.reason = mWifiAlertReasonCounts.keyAt(i);
                keyVal.count = mWifiAlertReasonCounts.valueAt(i);
                alertReasons.add(keyVal);
            }
            mWifiLogProto.alertReasonCount = alertReasons.toArray(mWifiLogProto.alertReasonCount);

            /**
            *  Convert the SparseIntArray of Wifi Score and counts to proto's repeated
            * IntKeyVal array.
            */
            for (int score = 0; score < mWifiScoreCounts.size(); score++) {
                WifiMetricsProto.WifiScoreCount keyVal = new WifiMetricsProto.WifiScoreCount();
                keyVal.score = mWifiScoreCounts.keyAt(score);
                keyVal.count = mWifiScoreCounts.valueAt(score);
                scores.add(keyVal);
            }
            mWifiLogProto.wifiScoreCount = scores.toArray(mWifiLogProto.wifiScoreCount);

            /**
             * Convert the SparseIntArray of SoftAp Return codes and counts to proto's repeated
             * IntKeyVal array.
             */
            int codeCounts = mSoftApManagerReturnCodeCounts.size();
            mWifiLogProto.softApReturnCode = new WifiMetricsProto.SoftApReturnCodeCount[codeCounts];
            for (int sapCode = 0; sapCode < codeCounts; sapCode++) {
                mWifiLogProto.softApReturnCode[sapCode] =
                        new WifiMetricsProto.SoftApReturnCodeCount();
                mWifiLogProto.softApReturnCode[sapCode].startResult =
                        mSoftApManagerReturnCodeCounts.keyAt(sapCode);
                mWifiLogProto.softApReturnCode[sapCode].count =
                        mSoftApManagerReturnCodeCounts.valueAt(sapCode);
            }

            mWifiLogProto.staEventList = mStaEventList.toArray(mWifiLogProto.staEventList);
        }
    }

    /**
     * Clear all WifiMetrics, except for currentConnectionEvent.
     */
    private void clear() {
        synchronized (mLock) {
            mConnectionEventList.clear();
            if (mCurrentConnectionEvent != null) {
                mConnectionEventList.add(mCurrentConnectionEvent);
            }
            mScanReturnEntries.clear();
            mWifiSystemStateEntries.clear();
            mRecordStartTimeSec = mClock.getElapsedSinceBootMillis() / 1000;
            mRssiPollCounts.clear();
            mRssiDeltaCounts.clear();
            mWifiAlertReasonCounts.clear();
            mWifiScoreCounts.clear();
            mWifiLogProto.clear();
            mScanResultRssiTimestampMillis = -1;
            mSoftApManagerReturnCodeCounts.clear();
            mStaEventList.clear();
        }
    }

    /**
     *  Set screen state (On/Off)
     */
    public void setScreenState(boolean screenOn) {
        synchronized (mLock) {
            mScreenOn = screenOn;
        }
    }

    /**
     *  Set wifi state (WIFI_UNKNOWN, WIFI_DISABLED, WIFI_DISCONNECTED, WIFI_ASSOCIATED)
     */
    public void setWifiState(int wifiState) {
        synchronized (mLock) {
            mWifiState = wifiState;
        }
    }

    /**
     * Message handler for interesting WifiMonitor messages. Generates StaEvents
     */
    private void processMessage(Message msg) {
        StaEvent event = new StaEvent();
        boolean logEvent = true;
        switch (msg.what) {
            case WifiMonitor.ASSOCIATION_REJECTION_EVENT:
                event.type = StaEvent.TYPE_ASSOCIATION_REJECTION_EVENT;
                event.associationTimedOut = msg.arg1 > 0 ? true : false;
                event.status = msg.arg2;
                break;
            case WifiMonitor.AUTHENTICATION_FAILURE_EVENT:
                event.type = StaEvent.TYPE_AUTHENTICATION_FAILURE_EVENT;
                switch (msg.arg2) {
                    case WifiManager.ERROR_AUTH_FAILURE_NONE:
                        event.authFailureReason = StaEvent.AUTH_FAILURE_NONE;
                        break;
                    case WifiManager.ERROR_AUTH_FAILURE_TIMEOUT:
                        event.authFailureReason = StaEvent.AUTH_FAILURE_TIMEOUT;
                        break;
                    case WifiManager.ERROR_AUTH_FAILURE_WRONG_PSWD:
                        event.authFailureReason = StaEvent.AUTH_FAILURE_WRONG_PSWD;
                        break;
                    case WifiManager.ERROR_AUTH_FAILURE_EAP_FAILURE:
                        event.authFailureReason = StaEvent.AUTH_FAILURE_EAP_FAILURE;
                        break;
                    default:
                        break;
                }
                break;
            case WifiMonitor.NETWORK_CONNECTION_EVENT:
                event.type = StaEvent.TYPE_NETWORK_CONNECTION_EVENT;
                break;
            case WifiMonitor.NETWORK_DISCONNECTION_EVENT:
                event.type = StaEvent.TYPE_NETWORK_DISCONNECTION_EVENT;
                event.reason = msg.arg2;
                event.localGen = msg.arg1 == 0 ? false : true;
                break;
            case WifiMonitor.SUPPLICANT_STATE_CHANGE_EVENT:
                logEvent = false;
                StateChangeResult stateChangeResult = (StateChangeResult) msg.obj;
                mSupplicantStateChangeBitmask |= supplicantStateToBit(stateChangeResult.state);
                break;
            case WifiStateMachine.CMD_ASSOCIATED_BSSID:
                event.type = StaEvent.TYPE_CMD_ASSOCIATED_BSSID;
                break;
            case WifiStateMachine.CMD_TARGET_BSSID:
                event.type = StaEvent.TYPE_CMD_TARGET_BSSID;
                break;
            default:
                return;
        }
        if (logEvent) {
            addStaEvent(event);
        }
    }
    /**
     * Log a StaEvent from WifiStateMachine. The StaEvent must not be one of the supplicant
     * generated event types, which are logged through 'sendMessage'
     * @param type StaEvent.EventType describing the event
     */
    public void logStaEvent(int type) {
        logStaEvent(type, StaEvent.DISCONNECT_UNKNOWN, null);
    }
    /**
     * Log a StaEvent from WifiStateMachine. The StaEvent must not be one of the supplicant
     * generated event types, which are logged through 'sendMessage'
     * @param type StaEvent.EventType describing the event
     * @param config WifiConfiguration for a framework initiated connection attempt
     */
    public void logStaEvent(int type, WifiConfiguration config) {
        logStaEvent(type, StaEvent.DISCONNECT_UNKNOWN, config);
    }
    /**
     * Log a StaEvent from WifiStateMachine. The StaEvent must not be one of the supplicant
     * generated event types, which are logged through 'sendMessage'
     * @param type StaEvent.EventType describing the event
     * @param frameworkDisconnectReason StaEvent.FrameworkDisconnectReason explaining why framework
     *                                  initiated a FRAMEWORK_DISCONNECT
     */
    public void logStaEvent(int type, int frameworkDisconnectReason) {
        logStaEvent(type, frameworkDisconnectReason, null);
    }
    /**
     * Log a StaEvent from WifiStateMachine. The StaEvent must not be one of the supplicant
     * generated event types, which are logged through 'sendMessage'
     * @param type StaEvent.EventType describing the event
     * @param frameworkDisconnectReason StaEvent.FrameworkDisconnectReason explaining why framework
     *                                  initiated a FRAMEWORK_DISCONNECT
     * @param config WifiConfiguration for a framework initiated connection attempt
     */
    public void logStaEvent(int type, int frameworkDisconnectReason, WifiConfiguration config) {
        switch (type) {
            case StaEvent.TYPE_CMD_IP_CONFIGURATION_SUCCESSFUL:
            case StaEvent.TYPE_CMD_IP_CONFIGURATION_LOST:
            case StaEvent.TYPE_CMD_IP_REACHABILITY_LOST:
            case StaEvent.TYPE_CMD_START_CONNECT:
            case StaEvent.TYPE_CMD_START_ROAM:
            case StaEvent.TYPE_CONNECT_NETWORK:
            case StaEvent.TYPE_NETWORK_AGENT_VALID_NETWORK:
            case StaEvent.TYPE_FRAMEWORK_DISCONNECT:
                break;
            default:
                Log.e(TAG, "Unknown StaEvent:" + type);
                return;
        }
        StaEvent event = new StaEvent();
        event.type = type;
        if (frameworkDisconnectReason != StaEvent.DISCONNECT_UNKNOWN) {
            event.frameworkDisconnectReason = frameworkDisconnectReason;
        }
        event.configInfo = createConfigInfo(config);
        addStaEvent(event);
    }

    private void addStaEvent(StaEvent staEvent) {
        staEvent.startTimeMillis = mClock.getElapsedSinceBootMillis();
        staEvent.lastRssi = mLastPollRssi;
        staEvent.lastFreq = mLastPollFreq;
        staEvent.lastLinkSpeed = mLastPollLinkSpeed;
        staEvent.supplicantStateChangesBitmask = mSupplicantStateChangeBitmask;
        mSupplicantStateChangeBitmask = 0;
        mLastPollRssi = -127;
        mLastPollFreq = -1;
        mLastPollLinkSpeed = -1;
        mStaEventList.add(staEvent);
        // Prune StaEventList if it gets too long
        if (mStaEventList.size() > MAX_STA_EVENTS) mStaEventList.remove();
    }

    private ConfigInfo createConfigInfo(WifiConfiguration config) {
        if (config == null) return null;
        ConfigInfo info = new ConfigInfo();
        info.allowedKeyManagement = bitSetToInt(config.allowedKeyManagement);
        info.allowedProtocols = bitSetToInt(config.allowedProtocols);
        info.allowedAuthAlgorithms = bitSetToInt(config.allowedAuthAlgorithms);
        info.allowedPairwiseCiphers = bitSetToInt(config.allowedPairwiseCiphers);
        info.allowedGroupCiphers = bitSetToInt(config.allowedGroupCiphers);
        info.hiddenSsid = config.hiddenSSID;
        info.isPasspoint = config.isPasspoint();
        info.isEphemeral = config.isEphemeral();
        info.hasEverConnected = config.getNetworkSelectionStatus().getHasEverConnected();
        ScanResult candidate = config.getNetworkSelectionStatus().getCandidate();
        if (candidate != null) {
            info.scanRssi = candidate.level;
            info.scanFreq = candidate.frequency;
        }
        return info;
    }

    public Handler getHandler() {
        return mHandler;
    }

    // Rather than generate a StaEvent for each SUPPLICANT_STATE_CHANGE, cache these in a bitmask
    // and attach it to the next event which is generated.
    private int mSupplicantStateChangeBitmask = 0;

    /**
     * Converts a SupplicantState value to a single bit, with position defined by
     * {@code StaEvent.SupplicantState}
     */
    public static int supplicantStateToBit(SupplicantState state) {
        switch(state) {
            case DISCONNECTED:
                return 1 << StaEvent.STATE_DISCONNECTED;
            case INTERFACE_DISABLED:
                return 1 << StaEvent.STATE_INTERFACE_DISABLED;
            case INACTIVE:
                return 1 << StaEvent.STATE_INACTIVE;
            case SCANNING:
                return 1 << StaEvent.STATE_SCANNING;
            case AUTHENTICATING:
                return 1 << StaEvent.STATE_AUTHENTICATING;
            case ASSOCIATING:
                return 1 << StaEvent.STATE_ASSOCIATING;
            case ASSOCIATED:
                return 1 << StaEvent.STATE_ASSOCIATED;
            case FOUR_WAY_HANDSHAKE:
                return 1 << StaEvent.STATE_FOUR_WAY_HANDSHAKE;
            case GROUP_HANDSHAKE:
                return 1 << StaEvent.STATE_GROUP_HANDSHAKE;
            case COMPLETED:
                return 1 << StaEvent.STATE_COMPLETED;
            case DORMANT:
                return 1 << StaEvent.STATE_DORMANT;
            case UNINITIALIZED:
                return 1 << StaEvent.STATE_UNINITIALIZED;
            case INVALID:
                return 1 << StaEvent.STATE_INVALID;
            default:
                Log.wtf(TAG, "Got unknown supplicant state: " + state.ordinal());
                return 0;
        }
    }

    private static String supplicantStateChangesBitmaskToString(int mask) {
        StringBuilder sb = new StringBuilder();
        sb.append("SUPPLICANT_STATE_CHANGE_EVENTS: {");
        if ((mask & (1 << StaEvent.STATE_DISCONNECTED)) > 0) sb.append(" DISCONNECTED");
        if ((mask & (1 << StaEvent.STATE_INTERFACE_DISABLED)) > 0) sb.append(" INTERFACE_DISABLED");
        if ((mask & (1 << StaEvent.STATE_INACTIVE)) > 0) sb.append(" INACTIVE");
        if ((mask & (1 << StaEvent.STATE_SCANNING)) > 0) sb.append(" SCANNING");
        if ((mask & (1 << StaEvent.STATE_AUTHENTICATING)) > 0) sb.append(" AUTHENTICATING");
        if ((mask & (1 << StaEvent.STATE_ASSOCIATING)) > 0) sb.append(" ASSOCIATING");
        if ((mask & (1 << StaEvent.STATE_ASSOCIATED)) > 0) sb.append(" ASSOCIATED");
        if ((mask & (1 << StaEvent.STATE_FOUR_WAY_HANDSHAKE)) > 0) sb.append(" FOUR_WAY_HANDSHAKE");
        if ((mask & (1 << StaEvent.STATE_GROUP_HANDSHAKE)) > 0) sb.append(" GROUP_HANDSHAKE");
        if ((mask & (1 << StaEvent.STATE_COMPLETED)) > 0) sb.append(" COMPLETED");
        if ((mask & (1 << StaEvent.STATE_DORMANT)) > 0) sb.append(" DORMANT");
        if ((mask & (1 << StaEvent.STATE_UNINITIALIZED)) > 0) sb.append(" UNINITIALIZED");
        if ((mask & (1 << StaEvent.STATE_INVALID)) > 0) sb.append(" INVALID");
        sb.append("}");
        return sb.toString();
    }

    /**
     * Returns a human readable string from a Sta Event. Only adds information relevant to the event
     * type.
     */
    public static String staEventToString(StaEvent event) {
        if (event == null) return "<NULL>";
        StringBuilder sb = new StringBuilder();
        Long time = event.startTimeMillis;
        sb.append(String.format("%9d ", time.longValue())).append(" ");
        switch (event.type) {
            case StaEvent.TYPE_ASSOCIATION_REJECTION_EVENT:
                sb.append("ASSOCIATION_REJECTION_EVENT:")
                        .append(" timedOut=").append(event.associationTimedOut)
                        .append(" status=").append(event.status).append(":")
                        .append(ISupplicantStaIfaceCallback.StatusCode.toString(event.status));
                break;
            case StaEvent.TYPE_AUTHENTICATION_FAILURE_EVENT:
                sb.append("AUTHENTICATION_FAILURE_EVENT: reason=").append(event.authFailureReason)
                        .append(":").append(authFailureReasonToString(event.authFailureReason));
                break;
            case StaEvent.TYPE_NETWORK_CONNECTION_EVENT:
                sb.append("NETWORK_CONNECTION_EVENT:");
                break;
            case StaEvent.TYPE_NETWORK_DISCONNECTION_EVENT:
                sb.append("NETWORK_DISCONNECTION_EVENT:")
                        .append(" local_gen=").append(event.localGen)
                        .append(" reason=").append(event.reason).append(":")
                        .append(ISupplicantStaIfaceCallback.ReasonCode.toString(
                                (event.reason >= 0 ? event.reason : -1 * event.reason)));
                break;
            case StaEvent.TYPE_CMD_ASSOCIATED_BSSID:
                sb.append("CMD_ASSOCIATED_BSSID:");
                break;
            case StaEvent.TYPE_CMD_IP_CONFIGURATION_SUCCESSFUL:
                sb.append("CMD_IP_CONFIGURATION_SUCCESSFUL:");
                break;
            case StaEvent.TYPE_CMD_IP_CONFIGURATION_LOST:
                sb.append("CMD_IP_CONFIGURATION_LOST:");
                break;
            case StaEvent.TYPE_CMD_IP_REACHABILITY_LOST:
                sb.append("CMD_IP_REACHABILITY_LOST:");
                break;
            case StaEvent.TYPE_CMD_TARGET_BSSID:
                sb.append("CMD_TARGET_BSSID:");
                break;
            case StaEvent.TYPE_CMD_START_CONNECT:
                sb.append("CMD_START_CONNECT:");
                break;
            case StaEvent.TYPE_CMD_START_ROAM:
                sb.append("CMD_START_ROAM:");
                break;
            case StaEvent.TYPE_CONNECT_NETWORK:
                sb.append("CONNECT_NETWORK:");
                break;
            case StaEvent.TYPE_NETWORK_AGENT_VALID_NETWORK:
                sb.append("NETWORK_AGENT_VALID_NETWORK:");
                break;
            case StaEvent.TYPE_FRAMEWORK_DISCONNECT:
                sb.append("FRAMEWORK_DISCONNECT:")
                        .append(" reason=")
                        .append(frameworkDisconnectReasonToString(event.frameworkDisconnectReason));
                break;
            default:
                sb.append("UNKNOWN " + event.type + ":");
                break;
        }
        if (event.lastRssi != -127) sb.append(" lastRssi=").append(event.lastRssi);
        if (event.lastFreq != -1) sb.append(" lastFreq=").append(event.lastFreq);
        if (event.lastLinkSpeed != -1) sb.append(" lastLinkSpeed=").append(event.lastLinkSpeed);
        if (event.supplicantStateChangesBitmask != 0) {
            sb.append("\n             ").append(supplicantStateChangesBitmaskToString(
                    event.supplicantStateChangesBitmask));
        }
        if (event.configInfo != null) {
            sb.append("\n             ").append(configInfoToString(event.configInfo));
        }

        return sb.toString();
    }

    private static String authFailureReasonToString(int authFailureReason) {
        switch (authFailureReason) {
            case StaEvent.AUTH_FAILURE_NONE:
                return "ERROR_AUTH_FAILURE_NONE";
            case StaEvent.AUTH_FAILURE_TIMEOUT:
                return "ERROR_AUTH_FAILURE_TIMEOUT";
            case StaEvent.AUTH_FAILURE_WRONG_PSWD:
                return "ERROR_AUTH_FAILURE_WRONG_PSWD";
            case StaEvent.AUTH_FAILURE_EAP_FAILURE:
                return "ERROR_AUTH_FAILURE_EAP_FAILURE";
            default:
                return "";
        }
    }

    private static String frameworkDisconnectReasonToString(int frameworkDisconnectReason) {
        switch (frameworkDisconnectReason) {
            case StaEvent.DISCONNECT_API:
                return "DISCONNECT_API";
            case StaEvent.DISCONNECT_GENERIC:
                return "DISCONNECT_GENERIC";
            case StaEvent.DISCONNECT_UNWANTED:
                return "DISCONNECT_UNWANTED";
            case StaEvent.DISCONNECT_ROAM_WATCHDOG_TIMER:
                return "DISCONNECT_ROAM_WATCHDOG_TIMER";
            case StaEvent.DISCONNECT_P2P_DISCONNECT_WIFI_REQUEST:
                return "DISCONNECT_P2P_DISCONNECT_WIFI_REQUEST";
            case StaEvent.DISCONNECT_RESET_SIM_NETWORKS:
                return "DISCONNECT_RESET_SIM_NETWORKS";
            default:
                return "DISCONNECT_UNKNOWN=" + frameworkDisconnectReason;
        }
    }

    private static String configInfoToString(ConfigInfo info) {
        StringBuilder sb = new StringBuilder();
        sb.append("ConfigInfo:")
                .append(" allowed_key_management=").append(info.allowedKeyManagement)
                .append(" allowed_protocols=").append(info.allowedProtocols)
                .append(" allowed_auth_algorithms=").append(info.allowedAuthAlgorithms)
                .append(" allowed_pairwise_ciphers=").append(info.allowedPairwiseCiphers)
                .append(" allowed_group_ciphers=").append(info.allowedGroupCiphers)
                .append(" hidden_ssid=").append(info.hiddenSsid)
                .append(" is_passpoint=").append(info.isPasspoint)
                .append(" is_ephemeral=").append(info.isEphemeral)
                .append(" has_ever_connected=").append(info.hasEverConnected)
                .append(" scan_rssi=").append(info.scanRssi)
                .append(" scan_freq=").append(info.scanFreq);
        return sb.toString();
    }

    public static final int MAX_STA_EVENTS = 512;
    private LinkedList<StaEvent> mStaEventList = new LinkedList<StaEvent>();
    private int mLastPollRssi = -127;
    private int mLastPollLinkSpeed = -1;
    private int mLastPollFreq = -1;

    /**
     * Converts the first 31 bits of a BitSet to a little endian int
     */
    private static int bitSetToInt(BitSet bits) {
        int value = 0;
        int nBits = bits.length() < 31 ? bits.length() : 31;
        for (int i = 0; i < nBits; i++) {
            value += bits.get(i) ? (1 << i) : 0;
        }
        return value;
    }
}
