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

import static java.lang.StrictMath.toIntExact;

import android.content.Context;
import android.hardware.wifi.supplicant.V1_0.ISupplicantStaIfaceCallback;
import android.net.NetworkAgent;
import android.net.wifi.EAPConstants;
import android.net.wifi.IOnWifiUsabilityStatsListener;
import android.net.wifi.ScanResult;
import android.net.wifi.SupplicantState;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiManager.DeviceMobilityState;
import android.net.wifi.WifiUsabilityStatsEntry.ProbeStatus;
import android.net.wifi.hotspot2.PasspointConfiguration;
import android.net.wifi.hotspot2.ProvisioningCallback;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.util.ArrayMap;
import android.util.Base64;
import android.util.Log;
import android.util.Pair;
import android.util.SparseArray;
import android.util.SparseIntArray;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.wifi.aware.WifiAwareMetrics;
import com.android.server.wifi.hotspot2.ANQPNetworkKey;
import com.android.server.wifi.hotspot2.NetworkDetail;
import com.android.server.wifi.hotspot2.PasspointManager;
import com.android.server.wifi.hotspot2.PasspointMatch;
import com.android.server.wifi.hotspot2.PasspointProvider;
import com.android.server.wifi.hotspot2.Utils;
import com.android.server.wifi.nano.WifiMetricsProto;
import com.android.server.wifi.nano.WifiMetricsProto.ConnectToNetworkNotificationAndActionCount;
import com.android.server.wifi.nano.WifiMetricsProto.DeviceMobilityStatePnoScanStats;
import com.android.server.wifi.nano.WifiMetricsProto.ExperimentValues;
import com.android.server.wifi.nano.WifiMetricsProto.LinkProbeStats;
import com.android.server.wifi.nano.WifiMetricsProto.LinkProbeStats.ExperimentProbeCounts;
import com.android.server.wifi.nano.WifiMetricsProto.LinkProbeStats.LinkProbeFailureReasonCount;
import com.android.server.wifi.nano.WifiMetricsProto.LinkSpeedCount;
import com.android.server.wifi.nano.WifiMetricsProto.NetworkSelectionExperimentDecisions;
import com.android.server.wifi.nano.WifiMetricsProto.PasspointProfileTypeCount;
import com.android.server.wifi.nano.WifiMetricsProto.PasspointProvisionStats;
import com.android.server.wifi.nano.WifiMetricsProto.PasspointProvisionStats.ProvisionFailureCount;
import com.android.server.wifi.nano.WifiMetricsProto.PnoScanMetrics;
import com.android.server.wifi.nano.WifiMetricsProto.SoftApConnectedClientsEvent;
import com.android.server.wifi.nano.WifiMetricsProto.StaEvent;
import com.android.server.wifi.nano.WifiMetricsProto.StaEvent.ConfigInfo;
import com.android.server.wifi.nano.WifiMetricsProto.WifiIsUnusableEvent;
import com.android.server.wifi.nano.WifiMetricsProto.WifiLinkLayerUsageStats;
import com.android.server.wifi.nano.WifiMetricsProto.WifiLockStats;
import com.android.server.wifi.nano.WifiMetricsProto.WifiNetworkRequestApiLog;
import com.android.server.wifi.nano.WifiMetricsProto.WifiNetworkSuggestionApiLog;
import com.android.server.wifi.nano.WifiMetricsProto.WifiToggleStats;
import com.android.server.wifi.nano.WifiMetricsProto.WifiUsabilityStats;
import com.android.server.wifi.nano.WifiMetricsProto.WifiUsabilityStatsEntry;
import com.android.server.wifi.nano.WifiMetricsProto.WpsMetrics;
import com.android.server.wifi.p2p.WifiP2pMetrics;
import com.android.server.wifi.rtt.RttMetrics;
import com.android.server.wifi.util.ExternalCallbackTracker;
import com.android.server.wifi.util.InformationElementUtil;
import com.android.server.wifi.util.IntCounter;
import com.android.server.wifi.util.IntHistogram;
import com.android.server.wifi.util.MetricsUtils;
import com.android.server.wifi.util.ObjectCounter;
import com.android.server.wifi.util.ScanResultUtil;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

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
    /** Minimum link speed (Mbps) to count for link_speed_counts */
    public static final int MIN_LINK_SPEED_MBPS = 0;
    /** Maximum time period between ScanResult and RSSI poll to generate rssi delta datapoint */
    public static final long TIMEOUT_RSSI_DELTA_MILLIS =  3000;
    private static final int MIN_WIFI_SCORE = 0;
    private static final int MAX_WIFI_SCORE = NetworkAgent.WIFI_BASE_SCORE;
    private static final int MIN_WIFI_USABILITY_SCORE = 0; // inclusive
    private static final int MAX_WIFI_USABILITY_SCORE = 100; // inclusive
    @VisibleForTesting
    static final int LOW_WIFI_SCORE = 50; // Mobile data score
    @VisibleForTesting
    static final int LOW_WIFI_USABILITY_SCORE = 50; // Mobile data score
    private final Object mLock = new Object();
    private static final int MAX_CONNECTION_EVENTS = 256;
    // Largest bucket in the NumConnectableNetworkCount histogram,
    // anything large will be stored in this bucket
    public static final int MAX_CONNECTABLE_SSID_NETWORK_BUCKET = 20;
    public static final int MAX_CONNECTABLE_BSSID_NETWORK_BUCKET = 50;
    public static final int MAX_TOTAL_SCAN_RESULT_SSIDS_BUCKET = 100;
    public static final int MAX_TOTAL_SCAN_RESULTS_BUCKET = 250;
    public static final int MAX_TOTAL_PASSPOINT_APS_BUCKET = 50;
    public static final int MAX_TOTAL_PASSPOINT_UNIQUE_ESS_BUCKET = 20;
    public static final int MAX_PASSPOINT_APS_PER_UNIQUE_ESS_BUCKET = 50;
    public static final int MAX_TOTAL_80211MC_APS_BUCKET = 20;
    private static final int CONNECT_TO_NETWORK_NOTIFICATION_ACTION_KEY_MULTIPLIER = 1000;
    // Max limit for number of soft AP related events, extra events will be dropped.
    private static final int MAX_NUM_SOFT_AP_EVENTS = 256;
    // Maximum number of WifiIsUnusableEvent
    public static final int MAX_UNUSABLE_EVENTS = 20;
    // Minimum time wait before generating next WifiIsUnusableEvent from data stall
    public static final int MIN_DATA_STALL_WAIT_MS = 120 * 1000; // 2 minutes
    private static final int WIFI_IS_UNUSABLE_EVENT_METRICS_ENABLED_DEFAULT = 1; // 1 = true
    private static final int WIFI_LINK_SPEED_METRICS_ENABLED_DEFAULT = 1; // 1 = true
    // Max number of WifiUsabilityStatsEntry elements to store in the ringbuffer.
    public static final int MAX_WIFI_USABILITY_STATS_ENTRIES_LIST_SIZE = 40;
    // Max number of WifiUsabilityStats elements to store for each type.
    public static final int MAX_WIFI_USABILITY_STATS_LIST_SIZE_PER_TYPE = 10;
    // Max number of WifiUsabilityStats per labeled type to upload to server
    public static final int MAX_WIFI_USABILITY_STATS_PER_TYPE_TO_UPLOAD = 2;
    public static final int NUM_WIFI_USABILITY_STATS_ENTRIES_PER_WIFI_GOOD = 100;
    public static final int MIN_WIFI_GOOD_USABILITY_STATS_PERIOD_MS = 1000 * 3600; // 1 hour
    // Histogram for WifiConfigStore IO duration times. Indicates the following 5 buckets (in ms):
    //   < 50
    //   [50, 100)
    //   [100, 150)
    //   [150, 200)
    //   [200, 300)
    //   >= 300
    private static final int[] WIFI_CONFIG_STORE_IO_DURATION_BUCKET_RANGES_MS =
            {50, 100, 150, 200, 300};
    // Minimum time wait before generating a LABEL_GOOD stats after score breaching low.
    public static final int MIN_SCORE_BREACH_TO_GOOD_STATS_WAIT_TIME_MS = 60 * 1000; // 1 minute
    // Maximum time that a score breaching low event stays valid.
    public static final int VALIDITY_PERIOD_OF_SCORE_BREACH_LOW_MS = 90 * 1000; // 1.5 minutes

    private Clock mClock;
    private boolean mScreenOn;
    private int mWifiState;
    private WifiAwareMetrics mWifiAwareMetrics;
    private RttMetrics mRttMetrics;
    private final PnoScanMetrics mPnoScanMetrics = new PnoScanMetrics();
    private final WifiLinkLayerUsageStats mWifiLinkLayerUsageStats = new WifiLinkLayerUsageStats();
    private final WpsMetrics mWpsMetrics = new WpsMetrics();
    private final ExperimentValues mExperimentValues = new ExperimentValues();
    private Handler mHandler;
    private ScoringParams mScoringParams;
    private WifiConfigManager mWifiConfigManager;
    private WifiNetworkSelector mWifiNetworkSelector;
    private PasspointManager mPasspointManager;
    private Context mContext;
    private FrameworkFacade mFacade;
    private WifiDataStall mWifiDataStall;
    private WifiLinkLayerStats mLastLinkLayerStats;
    private String mLastBssid;
    private int mLastFrequency = -1;
    private int mSeqNumInsideFramework = 0;
    private int mLastWifiUsabilityScore = -1;
    private int mLastWifiUsabilityScoreNoReset = -1;
    private int mLastPredictionHorizonSec = -1;
    private int mLastPredictionHorizonSecNoReset = -1;
    private int mSeqNumToFramework = -1;
    @ProbeStatus private int mProbeStatusSinceLastUpdate =
            android.net.wifi.WifiUsabilityStatsEntry.PROBE_STATUS_NO_PROBE;
    private int mProbeElapsedTimeSinceLastUpdateMs = -1;
    private int mProbeMcsRateSinceLastUpdate = -1;
    private long mScoreBreachLowTimeMillis = -1;

    public static final int MAX_STA_EVENTS = 768;
    private LinkedList<StaEventWithTime> mStaEventList = new LinkedList<>();
    private int mLastPollRssi = -127;
    private int mLastPollLinkSpeed = -1;
    private int mLastPollFreq = -1;
    private int mLastScore = -1;

    /** Tracks if we should be logging WifiIsUnusableEvent */
    private boolean mUnusableEventLogging = false;
    /** Tracks if we should be logging LinkSpeedCounts */
    private boolean mLinkSpeedCountsLogging = true;

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
    /** Mapping of channel frequency to its RSSI distribution histogram **/
    private final Map<Integer, SparseIntArray> mRssiPollCountsMap = new HashMap<>();
    /** Mapping of RSSI scan-poll delta values to counts. */
    private final SparseIntArray mRssiDeltaCounts = new SparseIntArray();
    /** Mapping of link speed values to LinkSpeedCount objects. */
    private final SparseArray<LinkSpeedCount> mLinkSpeedCounts = new SparseArray<>();
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
    /** Mapping of Wifi Usability Scores to counts */
    private final SparseIntArray mWifiUsabilityScoreCounts = new SparseIntArray();
    /** Mapping of SoftApManager start SoftAp return codes to counts */
    private final SparseIntArray mSoftApManagerReturnCodeCounts = new SparseIntArray();

    private final SparseIntArray mTotalSsidsInScanHistogram = new SparseIntArray();
    private final SparseIntArray mTotalBssidsInScanHistogram = new SparseIntArray();
    private final SparseIntArray mAvailableOpenSsidsInScanHistogram = new SparseIntArray();
    private final SparseIntArray mAvailableOpenBssidsInScanHistogram = new SparseIntArray();
    private final SparseIntArray mAvailableSavedSsidsInScanHistogram = new SparseIntArray();
    private final SparseIntArray mAvailableSavedBssidsInScanHistogram = new SparseIntArray();
    private final SparseIntArray mAvailableOpenOrSavedSsidsInScanHistogram = new SparseIntArray();
    private final SparseIntArray mAvailableOpenOrSavedBssidsInScanHistogram = new SparseIntArray();
    private final SparseIntArray mAvailableSavedPasspointProviderProfilesInScanHistogram =
            new SparseIntArray();
    private final SparseIntArray mAvailableSavedPasspointProviderBssidsInScanHistogram =
            new SparseIntArray();

    private final IntCounter mInstalledPasspointProfileTypeForR1 = new IntCounter();
    private final IntCounter mInstalledPasspointProfileTypeForR2 = new IntCounter();

    /** Mapping of "Connect to Network" notifications to counts. */
    private final SparseIntArray mConnectToNetworkNotificationCount = new SparseIntArray();
    /** Mapping of "Connect to Network" notification user actions to counts. */
    private final SparseIntArray mConnectToNetworkNotificationActionCount = new SparseIntArray();
    private int mOpenNetworkRecommenderBlacklistSize = 0;
    private boolean mIsWifiNetworksAvailableNotificationOn = false;
    private int mNumOpenNetworkConnectMessageFailedToSend = 0;
    private int mNumOpenNetworkRecommendationUpdates = 0;
    /** List of soft AP events related to number of connected clients in tethered mode */
    private final List<SoftApConnectedClientsEvent> mSoftApEventListTethered = new ArrayList<>();
    /** List of soft AP events related to number of connected clients in local only mode */
    private final List<SoftApConnectedClientsEvent> mSoftApEventListLocalOnly = new ArrayList<>();

    private final SparseIntArray mObservedHotspotR1ApInScanHistogram = new SparseIntArray();
    private final SparseIntArray mObservedHotspotR2ApInScanHistogram = new SparseIntArray();
    private final SparseIntArray mObservedHotspotR1EssInScanHistogram = new SparseIntArray();
    private final SparseIntArray mObservedHotspotR2EssInScanHistogram = new SparseIntArray();
    private final SparseIntArray mObservedHotspotR1ApsPerEssInScanHistogram = new SparseIntArray();
    private final SparseIntArray mObservedHotspotR2ApsPerEssInScanHistogram = new SparseIntArray();

    private final SparseIntArray mObserved80211mcApInScanHistogram = new SparseIntArray();

    // link probing stats
    private final IntCounter mLinkProbeSuccessRssiCounts = new IntCounter(-85, -65);
    private final IntCounter mLinkProbeFailureRssiCounts = new IntCounter(-85, -65);
    private final IntCounter mLinkProbeSuccessLinkSpeedCounts = new IntCounter();
    private final IntCounter mLinkProbeFailureLinkSpeedCounts = new IntCounter();

    private static final int[] LINK_PROBE_TIME_SINCE_LAST_TX_SUCCESS_SECONDS_HISTOGRAM_BUCKETS =
            {5, 15, 45, 135};
    private final IntHistogram mLinkProbeSuccessSecondsSinceLastTxSuccessHistogram =
            new IntHistogram(LINK_PROBE_TIME_SINCE_LAST_TX_SUCCESS_SECONDS_HISTOGRAM_BUCKETS);
    private final IntHistogram mLinkProbeFailureSecondsSinceLastTxSuccessHistogram =
            new IntHistogram(LINK_PROBE_TIME_SINCE_LAST_TX_SUCCESS_SECONDS_HISTOGRAM_BUCKETS);

    private static final int[] LINK_PROBE_ELAPSED_TIME_MS_HISTOGRAM_BUCKETS =
            {5, 10, 15, 20, 25, 50, 100, 200, 400, 800};
    private final IntHistogram mLinkProbeSuccessElapsedTimeMsHistogram = new IntHistogram(
            LINK_PROBE_ELAPSED_TIME_MS_HISTOGRAM_BUCKETS);
    private final IntCounter mLinkProbeFailureReasonCounts = new IntCounter();

    /**
     * Maps a String link probe experiment ID to the number of link probes that were sent for this
     * experiment.
     */
    private final ObjectCounter<String> mLinkProbeExperimentProbeCounts = new ObjectCounter<>();
    private int mLinkProbeStaEventCount = 0;
    @VisibleForTesting static final int MAX_LINK_PROBE_STA_EVENTS = MAX_STA_EVENTS / 4;

    private final LinkedList<WifiUsabilityStatsEntry> mWifiUsabilityStatsEntriesList =
            new LinkedList<>();
    private final LinkedList<WifiUsabilityStats> mWifiUsabilityStatsListBad = new LinkedList<>();
    private final LinkedList<WifiUsabilityStats> mWifiUsabilityStatsListGood = new LinkedList<>();
    private int mWifiUsabilityStatsCounter = 0;
    private final Random mRand = new Random();
    private final ExternalCallbackTracker<IOnWifiUsabilityStatsListener> mOnWifiUsabilityListeners;

    private final SparseArray<DeviceMobilityStatePnoScanStats> mMobilityStatePnoStatsMap =
            new SparseArray<>();
    private int mCurrentDeviceMobilityState;
    /**
     * The timestamp of the start of the current device mobility state.
     */
    private long mCurrentDeviceMobilityStateStartMs;
    /**
     * The timestamp of when the PNO scan started in the current device mobility state.
     */
    private long mCurrentDeviceMobilityStatePnoScanStartMs;

    /** Wifi power metrics*/
    private WifiPowerMetrics mWifiPowerMetrics;

    /** Wifi Wake metrics */
    private final WifiWakeMetrics mWifiWakeMetrics = new WifiWakeMetrics();

    /** Wifi P2p metrics */
    private final WifiP2pMetrics mWifiP2pMetrics;

    private boolean mIsMacRandomizationOn = false;

    /** DPP */
    private final DppMetrics mDppMetrics;

    /** WifiConfigStore read duration histogram. */
    private SparseIntArray mWifiConfigStoreReadDurationHistogram = new SparseIntArray();

    /** WifiConfigStore write duration histogram. */
    private SparseIntArray mWifiConfigStoreWriteDurationHistogram = new SparseIntArray();

    /** New  API surface metrics */
    private final WifiNetworkRequestApiLog mWifiNetworkRequestApiLog =
            new WifiNetworkRequestApiLog();
    private static final int[] NETWORK_REQUEST_API_MATCH_SIZE_HISTOGRAM_BUCKETS =
            {0, 1, 5, 10};
    private final IntHistogram mWifiNetworkRequestApiMatchSizeHistogram =
            new IntHistogram(NETWORK_REQUEST_API_MATCH_SIZE_HISTOGRAM_BUCKETS);

    private final WifiNetworkSuggestionApiLog mWifiNetworkSuggestionApiLog =
            new WifiNetworkSuggestionApiLog();
    private static final int[] NETWORK_SUGGESTION_API_LIST_SIZE_HISTOGRAM_BUCKETS =
            {5, 20, 50, 100, 500};
    private final IntHistogram mWifiNetworkSuggestionApiListSizeHistogram =
            new IntHistogram(NETWORK_SUGGESTION_API_LIST_SIZE_HISTOGRAM_BUCKETS);
    private final WifiLockStats mWifiLockStats = new WifiLockStats();
    private static final int[] WIFI_LOCK_SESSION_DURATION_HISTOGRAM_BUCKETS =
            {1, 10, 60, 600, 3600};
    private final WifiToggleStats mWifiToggleStats = new WifiToggleStats();

    private final IntHistogram mWifiLockHighPerfAcqDurationSecHistogram =
            new IntHistogram(WIFI_LOCK_SESSION_DURATION_HISTOGRAM_BUCKETS);
    private final IntHistogram mWifiLockLowLatencyAcqDurationSecHistogram =
            new IntHistogram(WIFI_LOCK_SESSION_DURATION_HISTOGRAM_BUCKETS);

    private final IntHistogram mWifiLockHighPerfActiveSessionDurationSecHistogram =
            new IntHistogram(WIFI_LOCK_SESSION_DURATION_HISTOGRAM_BUCKETS);
    private final IntHistogram mWifiLockLowLatencyActiveSessionDurationSecHistogram =
            new IntHistogram(WIFI_LOCK_SESSION_DURATION_HISTOGRAM_BUCKETS);

    /**
     * (experiment1Id, experiment2Id) =>
     *     (sameSelectionNumChoicesCounter, differentSelectionNumChoicesCounter)
     */
    private Map<Pair<Integer, Integer>, NetworkSelectionExperimentResults>
            mNetworkSelectionExperimentPairNumChoicesCounts = new ArrayMap<>();

    private int mNetworkSelectorExperimentId;

    private final CellularLinkLayerStatsCollector mCellularLinkLayerStatsCollector;

    /**
     * Tracks the nominator for each network (i.e. which entity made the suggestion to connect).
     * This object should not be cleared.
     */
    private final SparseIntArray mNetworkIdToNominatorId = new SparseIntArray();

    /** passpoint provision success count */
    private int mNumProvisionSuccess = 0;

    /** Mapping of failure code to the respective passpoint provision failure count. */
    private final IntCounter mPasspointProvisionFailureCounts = new IntCounter();

    @VisibleForTesting
    static class NetworkSelectionExperimentResults {
        public static final int MAX_CHOICES = 10;

        public IntCounter sameSelectionNumChoicesCounter = new IntCounter(0, MAX_CHOICES);
        public IntCounter differentSelectionNumChoicesCounter = new IntCounter(0, MAX_CHOICES);

        @Override
        public String toString() {
            return "NetworkSelectionExperimentResults{"
                    + "sameSelectionNumChoicesCounter="
                    + sameSelectionNumChoicesCounter
                    + ", differentSelectionNumChoicesCounter="
                    + differentSelectionNumChoicesCounter
                    + '}';
        }
    }

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
        // ASSOCIATION_TIMED_OUT
        public static final int FAILURE_ASSOCIATION_TIMED_OUT = 11;

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
                        break;
                    case FAILURE_ASSOCIATION_TIMED_OUT:
                        sb.append("ASSOCIATION_TIMED_OUT");
                        break;
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
                sb.append(", mRouterFingerprint=");
                sb.append(mRouterFingerPrint.toString());
                sb.append(", useRandomizedMac=");
                sb.append(mConnectionEvent.useRandomizedMac);
                sb.append(", connectionNominator=");
                switch (mConnectionEvent.connectionNominator) {
                    case WifiMetricsProto.ConnectionEvent.NOMINATOR_UNKNOWN:
                        sb.append("NOMINATOR_UNKNOWN");
                        break;
                    case WifiMetricsProto.ConnectionEvent.NOMINATOR_MANUAL:
                        sb.append("NOMINATOR_MANUAL");
                        break;
                    case WifiMetricsProto.ConnectionEvent.NOMINATOR_SAVED:
                        sb.append("NOMINATOR_SAVED");
                        break;
                    case WifiMetricsProto.ConnectionEvent.NOMINATOR_SUGGESTION:
                        sb.append("NOMINATOR_SUGGESTION");
                        break;
                    case WifiMetricsProto.ConnectionEvent.NOMINATOR_PASSPOINT:
                        sb.append("NOMINATOR_PASSPOINT");
                        break;
                    case WifiMetricsProto.ConnectionEvent.NOMINATOR_CARRIER:
                        sb.append("NOMINATOR_CARRIER");
                        break;
                    case WifiMetricsProto.ConnectionEvent.NOMINATOR_EXTERNAL_SCORED:
                        sb.append("NOMINATOR_EXTERNAL_SCORED");
                        break;
                    case WifiMetricsProto.ConnectionEvent.NOMINATOR_SPECIFIER:
                        sb.append("NOMINATOR_SPECIFIER");
                        break;
                    case WifiMetricsProto.ConnectionEvent.NOMINATOR_SAVED_USER_CONNECT_CHOICE:
                        sb.append("NOMINATOR_SAVED_USER_CONNECT_CHOICE");
                        break;
                    case WifiMetricsProto.ConnectionEvent.NOMINATOR_OPEN_NETWORK_AVAILABLE:
                        sb.append("NOMINATOR_OPEN_NETWORK_AVAILABLE");
                        break;
                    default:
                        sb.append(String.format("UnrecognizedNominator(%d)",
                                mConnectionEvent.connectionNominator));
                }
                sb.append(", networkSelectorExperimentId=");
                sb.append(mConnectionEvent.networkSelectorExperimentId);
                sb.append(", level2FailureReason=");
                switch(mConnectionEvent.level2FailureReason) {
                    case WifiMetricsProto.ConnectionEvent.AUTH_FAILURE_NONE:
                        sb.append("AUTH_FAILURE_NONE");
                        break;
                    case WifiMetricsProto.ConnectionEvent.AUTH_FAILURE_TIMEOUT:
                        sb.append("AUTH_FAILURE_TIMEOUT");
                        break;
                    case WifiMetricsProto.ConnectionEvent.AUTH_FAILURE_WRONG_PSWD:
                        sb.append("AUTH_FAILURE_WRONG_PSWD");
                        break;
                    case WifiMetricsProto.ConnectionEvent.AUTH_FAILURE_EAP_FAILURE:
                        sb.append("AUTH_FAILURE_EAP_FAILURE");
                        break;
                    default:
                        sb.append("FAILURE_REASON_UNKNOWN");
                        break;
                }
            }
            return sb.toString();
        }
    }

    public WifiMetrics(Context context, FrameworkFacade facade, Clock clock, Looper looper,
            WifiAwareMetrics awareMetrics, RttMetrics rttMetrics,
            WifiPowerMetrics wifiPowerMetrics, WifiP2pMetrics wifiP2pMetrics,
            DppMetrics dppMetrics,
            CellularLinkLayerStatsCollector cellularLinkLayerStatsCollector) {
        mContext = context;
        mFacade = facade;
        mClock = clock;
        mCurrentConnectionEvent = null;
        mScreenOn = true;
        mWifiState = WifiMetricsProto.WifiLog.WIFI_DISABLED;
        mRecordStartTimeSec = mClock.getElapsedSinceBootMillis() / 1000;
        mWifiAwareMetrics = awareMetrics;
        mRttMetrics = rttMetrics;
        mWifiPowerMetrics = wifiPowerMetrics;
        mWifiP2pMetrics = wifiP2pMetrics;
        mDppMetrics = dppMetrics;
        mCellularLinkLayerStatsCollector = cellularLinkLayerStatsCollector;
        loadSettings();
        mHandler = new Handler(looper) {
            public void handleMessage(Message msg) {
                synchronized (mLock) {
                    processMessage(msg);
                }
            }
        };

        mCurrentDeviceMobilityState = WifiManager.DEVICE_MOBILITY_STATE_UNKNOWN;
        DeviceMobilityStatePnoScanStats unknownStateStats =
                getOrCreateDeviceMobilityStatePnoScanStats(mCurrentDeviceMobilityState);
        unknownStateStats.numTimesEnteredState++;
        mCurrentDeviceMobilityStateStartMs = mClock.getElapsedSinceBootMillis();
        mCurrentDeviceMobilityStatePnoScanStartMs = -1;
        mOnWifiUsabilityListeners =
                new ExternalCallbackTracker<IOnWifiUsabilityStatsListener>(mHandler);
    }

    /**
     * Load setting values related to metrics logging.
     */
    @VisibleForTesting
    public void loadSettings() {
        int unusableEventFlag = mFacade.getIntegerSetting(
                mContext, Settings.Global.WIFI_IS_UNUSABLE_EVENT_METRICS_ENABLED,
                WIFI_IS_UNUSABLE_EVENT_METRICS_ENABLED_DEFAULT);
        mUnusableEventLogging = (unusableEventFlag == 1);
        setWifiIsUnusableLoggingEnabled(mUnusableEventLogging);
        int linkSpeedCountsFlag = mFacade.getIntegerSetting(
                mContext, Settings.Global.WIFI_LINK_SPEED_METRICS_ENABLED,
                WIFI_LINK_SPEED_METRICS_ENABLED_DEFAULT);
        mLinkSpeedCountsLogging = (linkSpeedCountsFlag == 1);
        setLinkSpeedCountsLoggingEnabled(mLinkSpeedCountsLogging);
        if (mWifiDataStall != null) {
            mWifiDataStall.loadSettings();
        }
    }

    /** Sets internal ScoringParams member */
    public void setScoringParams(ScoringParams scoringParams) {
        mScoringParams = scoringParams;
    }

    /** Sets internal WifiConfigManager member */
    public void setWifiConfigManager(WifiConfigManager wifiConfigManager) {
        mWifiConfigManager = wifiConfigManager;
    }

    /** Sets internal WifiNetworkSelector member */
    public void setWifiNetworkSelector(WifiNetworkSelector wifiNetworkSelector) {
        mWifiNetworkSelector = wifiNetworkSelector;
    }

    /** Sets internal PasspointManager member */
    public void setPasspointManager(PasspointManager passpointManager) {
        mPasspointManager = passpointManager;
    }

    /** Sets internal WifiDataStall member */
    public void setWifiDataStall(WifiDataStall wifiDataStall) {
        mWifiDataStall = wifiDataStall;
    }

    /**
     * Increment cumulative counters for link layer stats.
     * @param newStats
     */
    public void incrementWifiLinkLayerUsageStats(WifiLinkLayerStats newStats) {
        if (newStats == null) {
            return;
        }
        if (mLastLinkLayerStats == null) {
            mLastLinkLayerStats = newStats;
            return;
        }
        if (!newLinkLayerStatsIsValid(mLastLinkLayerStats, newStats)) {
            // This could mean the radio chip is reset or the data is incorrectly reported.
            // Don't increment any counts and discard the possibly corrupt |newStats| completely.
            mLastLinkLayerStats = null;
            return;
        }
        mWifiLinkLayerUsageStats.loggingDurationMs +=
                (newStats.timeStampInMs - mLastLinkLayerStats.timeStampInMs);
        mWifiLinkLayerUsageStats.radioOnTimeMs += (newStats.on_time - mLastLinkLayerStats.on_time);
        mWifiLinkLayerUsageStats.radioTxTimeMs += (newStats.tx_time - mLastLinkLayerStats.tx_time);
        mWifiLinkLayerUsageStats.radioRxTimeMs += (newStats.rx_time - mLastLinkLayerStats.rx_time);
        mWifiLinkLayerUsageStats.radioScanTimeMs +=
                (newStats.on_time_scan - mLastLinkLayerStats.on_time_scan);
        mWifiLinkLayerUsageStats.radioNanScanTimeMs +=
                (newStats.on_time_nan_scan - mLastLinkLayerStats.on_time_nan_scan);
        mWifiLinkLayerUsageStats.radioBackgroundScanTimeMs +=
                (newStats.on_time_background_scan - mLastLinkLayerStats.on_time_background_scan);
        mWifiLinkLayerUsageStats.radioRoamScanTimeMs +=
                (newStats.on_time_roam_scan - mLastLinkLayerStats.on_time_roam_scan);
        mWifiLinkLayerUsageStats.radioPnoScanTimeMs +=
                (newStats.on_time_pno_scan - mLastLinkLayerStats.on_time_pno_scan);
        mWifiLinkLayerUsageStats.radioHs20ScanTimeMs +=
                (newStats.on_time_hs20_scan - mLastLinkLayerStats.on_time_hs20_scan);
        mLastLinkLayerStats = newStats;
    }

    private boolean newLinkLayerStatsIsValid(WifiLinkLayerStats oldStats,
            WifiLinkLayerStats newStats) {
        if (newStats.on_time < oldStats.on_time
                || newStats.tx_time < oldStats.tx_time
                || newStats.rx_time < oldStats.rx_time
                || newStats.on_time_scan < oldStats.on_time_scan) {
            return false;
        }
        return true;
    }

    /**
     * Increment total number of attempts to start a pno scan
     */
    public void incrementPnoScanStartAttempCount() {
        synchronized (mLock) {
            mPnoScanMetrics.numPnoScanAttempts++;
        }
    }

    /**
     * Increment total number of attempts with pno scan failed
     */
    public void incrementPnoScanFailedCount() {
        synchronized (mLock) {
            mPnoScanMetrics.numPnoScanFailed++;
        }
    }

    /**
     * Increment number of pno scans started successfully over offload
     */
    public void incrementPnoScanStartedOverOffloadCount() {
        synchronized (mLock) {
            mPnoScanMetrics.numPnoScanStartedOverOffload++;
        }
    }

    /**
     * Increment number of pno scans failed over offload
     */
    public void incrementPnoScanFailedOverOffloadCount() {
        synchronized (mLock) {
            mPnoScanMetrics.numPnoScanFailedOverOffload++;
        }
    }

    /**
     * Increment number of times pno scan found a result
     */
    public void incrementPnoFoundNetworkEventCount() {
        synchronized (mLock) {
            mPnoScanMetrics.numPnoFoundNetworkEvents++;
        }
    }

    /**
     * Increment total number of wps connection attempts
     */
    public void incrementWpsAttemptCount() {
        synchronized (mLock) {
            mWpsMetrics.numWpsAttempts++;
        }
    }

    /**
     * Increment total number of wps connection success
     */
    public void incrementWpsSuccessCount() {
        synchronized (mLock) {
            mWpsMetrics.numWpsSuccess++;
        }
    }

    /**
     * Increment total number of wps failure on start
     */
    public void incrementWpsStartFailureCount() {
        synchronized (mLock) {
            mWpsMetrics.numWpsStartFailure++;
        }
    }

    /**
     * Increment total number of wps overlap failure
     */
    public void incrementWpsOverlapFailureCount() {
        synchronized (mLock) {
            mWpsMetrics.numWpsOverlapFailure++;
        }
    }

    /**
     * Increment total number of wps timeout failure
     */
    public void incrementWpsTimeoutFailureCount() {
        synchronized (mLock) {
            mWpsMetrics.numWpsTimeoutFailure++;
        }
    }

    /**
     * Increment total number of other wps failure during connection
     */
    public void incrementWpsOtherConnectionFailureCount() {
        synchronized (mLock) {
            mWpsMetrics.numWpsOtherConnectionFailure++;
        }
    }

    /**
     * Increment total number of supplicant failure after wps
     */
    public void incrementWpsSupplicantFailureCount() {
        synchronized (mLock) {
            mWpsMetrics.numWpsSupplicantFailure++;
        }
    }

    /**
     * Increment total number of wps cancellation
     */
    public void incrementWpsCancellationCount() {
        synchronized (mLock) {
            mWpsMetrics.numWpsCancellation++;
        }
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
                            WifiMetricsProto.ConnectionEvent.HLF_NONE,
                            WifiMetricsProto.ConnectionEvent.FAILURE_REASON_UNKNOWN);
                } else {
                    // End Connection Event due to new connection attempt to different network
                    endConnectionEvent(ConnectionEvent.FAILURE_NEW_CONNECTION_ATTEMPT,
                            WifiMetricsProto.ConnectionEvent.HLF_NONE,
                            WifiMetricsProto.ConnectionEvent.FAILURE_REASON_UNKNOWN);
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
            mCurrentConnectionEvent.mConnectionEvent.networkSelectorExperimentId =
                    mNetworkSelectorExperimentId;
            mCurrentConnectionEvent.mRouterFingerPrint.updateFromWifiConfiguration(config);
            mCurrentConnectionEvent.mConfigBssid = "any";
            mCurrentConnectionEvent.mRealStartTime = mClock.getElapsedSinceBootMillis();
            mCurrentConnectionEvent.mWifiState = mWifiState;
            mCurrentConnectionEvent.mScreenOn = mScreenOn;
            mConnectionEventList.add(mCurrentConnectionEvent);
            mScanResultRssiTimestampMillis = -1;
            if (config != null) {
                mCurrentConnectionEvent.mConnectionEvent.useRandomizedMac =
                        config.macRandomizationSetting
                        == WifiConfiguration.RANDOMIZATION_PERSISTENT;
                mCurrentConnectionEvent.mConnectionEvent.connectionNominator =
                        mNetworkIdToNominatorId.get(config.networkId,
                                WifiMetricsProto.ConnectionEvent.NOMINATOR_UNKNOWN);
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
     * @param level2FailureReason Breakdown of level2FailureCode with more detailed reason
     */
    public void endConnectionEvent(int level2FailureCode, int connectivityFailureCode,
            int level2FailureReason) {
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
                mCurrentConnectionEvent.mConnectionEvent.level2FailureReason = level2FailureReason;
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
            } else if (ScanResultUtil.isScanResultForPskNetwork(scanResult)
                    || ScanResultUtil.isScanResultForSaeNetwork(scanResult)) {
                mCurrentConnectionEvent.mRouterFingerPrint.mRouterFingerPrintProto.authentication =
                        WifiMetricsProto.RouterFingerPrint.AUTH_PERSONAL;
            } else if (ScanResultUtil.isScanResultForEapNetwork(scanResult)
                    || ScanResultUtil.isScanResultForEapSuiteBNetwork(scanResult)) {
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
     * Increment the count of oneshot scans that include DFS channels.
     */
    public void incrementOneshotScanWithDfsCount() {
        synchronized (mLock) {
            mWifiLogProto.numOneshotHasDfsChannelScans++;
        }
    }

    /**
     * Increment connectivity oneshot scan count.
     */
    public void incrementConnectivityOneshotScanCount() {
        synchronized (mLock) {
            mWifiLogProto.numConnectivityOneshotScans++;
        }
    }

    /**
     * Get oneshot scan count
     */
    public int getOneshotScanCount() {
        synchronized (mLock) {
            return mWifiLogProto.numOneshotScans;
        }
    }

    /**
     * Get connectivity oneshot scan count
     */
    public int getConnectivityOneshotScanCount() {
        synchronized (mLock) {
            return mWifiLogProto.numConnectivityOneshotScans;
        }
    }

    /**
     * Get the count of oneshot scan requests that included DFS channels.
     */
    public int getOneshotScanWithDfsCount() {
        synchronized (mLock) {
            return mWifiLogProto.numOneshotHasDfsChannelScans;
        }
    }

    /**
     * Increment oneshot scan count for external apps.
     */
    public void incrementExternalAppOneshotScanRequestsCount() {
        synchronized (mLock) {
            mWifiLogProto.numExternalAppOneshotScanRequests++;
        }
    }
    /**
     * Increment oneshot scan throttle count for external foreground apps.
     */
    public void incrementExternalForegroundAppOneshotScanRequestsThrottledCount() {
        synchronized (mLock) {
            mWifiLogProto.numExternalForegroundAppOneshotScanRequestsThrottled++;
        }
    }

    /**
     * Increment oneshot scan throttle count for external background apps.
     */
    public void incrementExternalBackgroundAppOneshotScanRequestsThrottledCount() {
        synchronized (mLock) {
            mWifiLogProto.numExternalBackgroundAppOneshotScanRequestsThrottled++;
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
        incrementRssiPollRssiCount(mLastPollFreq, mLastPollRssi);
        incrementLinkSpeedCount(mLastPollLinkSpeed, mLastPollRssi);
    }

    /**
     * Increment occurence count of RSSI level from RSSI poll for the given frequency.
     * @param frequency (MHz)
     * @param rssi
     */
    @VisibleForTesting
    public void incrementRssiPollRssiCount(int frequency, int rssi) {
        if (!(rssi >= MIN_RSSI_POLL && rssi <= MAX_RSSI_POLL)) {
            return;
        }
        synchronized (mLock) {
            if (!mRssiPollCountsMap.containsKey(frequency)) {
                mRssiPollCountsMap.put(frequency, new SparseIntArray());
            }
            SparseIntArray sparseIntArray = mRssiPollCountsMap.get(frequency);
            int count = sparseIntArray.get(rssi);
            sparseIntArray.put(rssi, count + 1);
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
     * Increment occurrence count of link speed.
     * Ignores link speed values that are lower than MIN_LINK_SPEED_MBPS
     * and rssi values outside the bounds of [MIN_RSSI_POLL, MAX_RSSI_POLL]
     */
    @VisibleForTesting
    public void incrementLinkSpeedCount(int linkSpeed, int rssi) {
        if (!(mLinkSpeedCountsLogging
                && linkSpeed >= MIN_LINK_SPEED_MBPS
                && rssi >= MIN_RSSI_POLL
                && rssi <= MAX_RSSI_POLL)) {
            return;
        }
        synchronized (mLock) {
            LinkSpeedCount linkSpeedCount = mLinkSpeedCounts.get(linkSpeed);
            if (linkSpeedCount == null) {
                linkSpeedCount = new LinkSpeedCount();
                linkSpeedCount.linkSpeedMbps = linkSpeed;
                mLinkSpeedCounts.put(linkSpeed, linkSpeedCount);
            }
            linkSpeedCount.count++;
            linkSpeedCount.rssiSumDbm += Math.abs(rssi);
            linkSpeedCount.rssiSumOfSquaresDbmSq += rssi * rssi;
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
     * Increment the count of network connection failures that happened after watchdog has been
     * triggered.
     */
    public void incrementWatchdogTotalConnectionFailureCountAfterTrigger() {
        synchronized (mLock) {
            mWifiLogProto.watchdogTotalConnectionFailureCountAfterTrigger++;
        }
    }

    /**
     * Sets the time taken for wifi to connect after a watchdog triggers a restart.
     * @param milliseconds
     */
    public void setWatchdogSuccessTimeDurationMs(long ms) {
        synchronized (mLock) {
            mWifiLogProto.watchdogTriggerToConnectionSuccessDurationMs = ms;
        }
    }

    /**
     * Increments the count of alerts by alert reason.
     *
     * @param reason The cause of the alert. The reason values are driver-specific.
     */
    private void incrementAlertReasonCount(int reason) {
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
        int enhacedOpenNetworks = 0;
        int wpa3PersonalNetworks = 0;
        int wpa3EnterpriseNetworks = 0;

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
                if (ScanResultUtil.isScanResultForEapSuiteBNetwork(scanResult)) {
                    wpa3EnterpriseNetworks++;
                } else if (ScanResultUtil.isScanResultForEapNetwork(scanResult)) {
                    enterpriseNetworks++;
                } else if (ScanResultUtil.isScanResultForSaeNetwork(scanResult)) {
                    wpa3PersonalNetworks++;
                } else if (ScanResultUtil.isScanResultForPskNetwork(scanResult)
                        || ScanResultUtil.isScanResultForWepNetwork(scanResult)) {
                    personalNetworks++;
                } else if (ScanResultUtil.isScanResultForOweNetwork(scanResult)) {
                    enhacedOpenNetworks++;
                } else {
                    openNetworks++;
                }
            }
        }
        synchronized (mLock) {
            mWifiLogProto.numTotalScanResults += totalResults;
            mWifiLogProto.numOpenNetworkScanResults += openNetworks;
            mWifiLogProto.numLegacyPersonalNetworkScanResults += personalNetworks;
            mWifiLogProto.numLegacyEnterpriseNetworkScanResults += enterpriseNetworks;
            mWifiLogProto.numEnhancedOpenNetworkScanResults += enhacedOpenNetworks;
            mWifiLogProto.numWpa3PersonalNetworkScanResults += wpa3PersonalNetworks;
            mWifiLogProto.numWpa3EnterpriseNetworkScanResults += wpa3EnterpriseNetworks;
            mWifiLogProto.numHiddenNetworkScanResults += hiddenNetworks;
            mWifiLogProto.numHotspot2R1NetworkScanResults += hotspot2r1Networks;
            mWifiLogProto.numHotspot2R2NetworkScanResults += hotspot2r2Networks;
            mWifiLogProto.numScans++;
        }
    }

    private boolean mWifiWins = false; // Based on scores, use wifi instead of mobile data?
    // Based on Wifi usability scores. use wifi instead of mobile data?
    private boolean mWifiWinsUsabilityScore = false;

    /**
     * Increments occurence of a particular wifi score calculated
     * in WifiScoreReport by current connected network. Scores are bounded
     * within  [MIN_WIFI_SCORE, MAX_WIFI_SCORE] to limit size of SparseArray.
     *
     * Also records events when the current score breaches significant thresholds.
     */
    public void incrementWifiScoreCount(int score) {
        if (score < MIN_WIFI_SCORE || score > MAX_WIFI_SCORE) {
            return;
        }
        synchronized (mLock) {
            int count = mWifiScoreCounts.get(score);
            mWifiScoreCounts.put(score, count + 1);

            boolean wifiWins = mWifiWins;
            if (mWifiWins && score < LOW_WIFI_SCORE) {
                wifiWins = false;
            } else if (!mWifiWins && score > LOW_WIFI_SCORE) {
                wifiWins = true;
            }
            mLastScore = score;
            mLastScoreNoReset = score;
            if (wifiWins != mWifiWins) {
                mWifiWins = wifiWins;
                StaEvent event = new StaEvent();
                event.type = StaEvent.TYPE_SCORE_BREACH;
                addStaEvent(event);
                // Only record the first score breach by checking whether mScoreBreachLowTimeMillis
                // has been set to -1
                if (!wifiWins && mScoreBreachLowTimeMillis == -1) {
                    mScoreBreachLowTimeMillis = mClock.getElapsedSinceBootMillis();
                }
            }
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
     * Adds a record indicating the current up state of soft AP
     */
    public void addSoftApUpChangedEvent(boolean isUp, int mode) {
        SoftApConnectedClientsEvent event = new SoftApConnectedClientsEvent();
        event.eventType = isUp ? SoftApConnectedClientsEvent.SOFT_AP_UP :
                SoftApConnectedClientsEvent.SOFT_AP_DOWN;
        event.numConnectedClients = 0;
        addSoftApConnectedClientsEvent(event, mode);
    }

    /**
     * Adds a record for current number of associated stations to soft AP
     */
    public void addSoftApNumAssociatedStationsChangedEvent(int numStations, int mode) {
        SoftApConnectedClientsEvent event = new SoftApConnectedClientsEvent();
        event.eventType = SoftApConnectedClientsEvent.NUM_CLIENTS_CHANGED;
        event.numConnectedClients = numStations;
        addSoftApConnectedClientsEvent(event, mode);
    }

    /**
     * Adds a record to the corresponding event list based on mode param
     */
    private void addSoftApConnectedClientsEvent(SoftApConnectedClientsEvent event, int mode) {
        synchronized (mLock) {
            List<SoftApConnectedClientsEvent> softApEventList;
            switch (mode) {
                case WifiManager.IFACE_IP_MODE_TETHERED:
                    softApEventList = mSoftApEventListTethered;
                    break;
                case WifiManager.IFACE_IP_MODE_LOCAL_ONLY:
                    softApEventList = mSoftApEventListLocalOnly;
                    break;
                default:
                    return;
            }

            if (softApEventList.size() > MAX_NUM_SOFT_AP_EVENTS) {
                return;
            }

            event.timeStampMillis = mClock.getElapsedSinceBootMillis();
            softApEventList.add(event);
        }
    }

    /**
     * Updates current soft AP events with channel info
     */
    public void addSoftApChannelSwitchedEvent(int frequency, int bandwidth, int mode) {
        synchronized (mLock) {
            List<SoftApConnectedClientsEvent> softApEventList;
            switch (mode) {
                case WifiManager.IFACE_IP_MODE_TETHERED:
                    softApEventList = mSoftApEventListTethered;
                    break;
                case WifiManager.IFACE_IP_MODE_LOCAL_ONLY:
                    softApEventList = mSoftApEventListLocalOnly;
                    break;
                default:
                    return;
            }

            for (int index = softApEventList.size() - 1; index >= 0; index--) {
                SoftApConnectedClientsEvent event = softApEventList.get(index);

                if (event != null && event.eventType == SoftApConnectedClientsEvent.SOFT_AP_UP) {
                    event.channelFrequency = frequency;
                    event.channelBandwidth = bandwidth;
                    break;
                }
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
     * Increment number of times the supplicant crashed.
     */
    public void incrementNumSupplicantCrashes() {
        synchronized (mLock) {
            mWifiLogProto.numSupplicantCrashes++;
        }
    }

    /**
     * Increment number of times the hostapd crashed.
     */
    public void incrementNumHostapdCrashes() {
        synchronized (mLock) {
            mWifiLogProto.numHostapdCrashes++;
        }
    }

    /**
     * Increment number of times the wifi on failed due to an error in HAL.
     */
    public void incrementNumSetupClientInterfaceFailureDueToHal() {
        synchronized (mLock) {
            mWifiLogProto.numSetupClientInterfaceFailureDueToHal++;
        }
    }

    /**
     * Increment number of times the wifi on failed due to an error in wificond.
     */
    public void incrementNumSetupClientInterfaceFailureDueToWificond() {
        synchronized (mLock) {
            mWifiLogProto.numSetupClientInterfaceFailureDueToWificond++;
        }
    }

    /**
     * Increment number of times the wifi on failed due to an error in supplicant.
     */
    public void incrementNumSetupClientInterfaceFailureDueToSupplicant() {
        synchronized (mLock) {
            mWifiLogProto.numSetupClientInterfaceFailureDueToSupplicant++;
        }
    }

    /**
     * Increment number of times the SoftAp on failed due to an error in HAL.
     */
    public void incrementNumSetupSoftApInterfaceFailureDueToHal() {
        synchronized (mLock) {
            mWifiLogProto.numSetupSoftApInterfaceFailureDueToHal++;
        }
    }

    /**
     * Increment number of times the SoftAp on failed due to an error in wificond.
     */
    public void incrementNumSetupSoftApInterfaceFailureDueToWificond() {
        synchronized (mLock) {
            mWifiLogProto.numSetupSoftApInterfaceFailureDueToWificond++;
        }
    }

    /**
     * Increment number of times the SoftAp on failed due to an error in hostapd.
     */
    public void incrementNumSetupSoftApInterfaceFailureDueToHostapd() {
        synchronized (mLock) {
            mWifiLogProto.numSetupSoftApInterfaceFailureDueToHostapd++;
        }
    }

    /**
     * Increment number of times we got client interface down.
     */
    public void incrementNumClientInterfaceDown() {
        synchronized (mLock) {
            mWifiLogProto.numClientInterfaceDown++;
        }
    }

    /**
     * Increment number of times we got client interface down.
     */
    public void incrementNumSoftApInterfaceDown() {
        synchronized (mLock) {
            mWifiLogProto.numSoftApInterfaceDown++;
        }
    }

    /**
     * Increment number of times Passpoint provider being installed.
     */
    public void incrementNumPasspointProviderInstallation() {
        synchronized (mLock) {
            mWifiLogProto.numPasspointProviderInstallation++;
        }
    }

    /**
     * Increment number of times Passpoint provider is installed successfully.
     */
    public void incrementNumPasspointProviderInstallSuccess() {
        synchronized (mLock) {
            mWifiLogProto.numPasspointProviderInstallSuccess++;
        }
    }

    /**
     * Increment number of times Passpoint provider being uninstalled.
     */
    public void incrementNumPasspointProviderUninstallation() {
        synchronized (mLock) {
            mWifiLogProto.numPasspointProviderUninstallation++;
        }
    }

    /**
     * Increment number of times Passpoint provider is uninstalled successfully.
     */
    public void incrementNumPasspointProviderUninstallSuccess() {
        synchronized (mLock) {
            mWifiLogProto.numPasspointProviderUninstallSuccess++;
        }
    }

    /**
     * Increment number of times we detected a radio mode change to MCC.
     */
    public void incrementNumRadioModeChangeToMcc() {
        synchronized (mLock) {
            mWifiLogProto.numRadioModeChangeToMcc++;
        }
    }

    /**
     * Increment number of times we detected a radio mode change to SCC.
     */
    public void incrementNumRadioModeChangeToScc() {
        synchronized (mLock) {
            mWifiLogProto.numRadioModeChangeToScc++;
        }
    }

    /**
     * Increment number of times we detected a radio mode change to SBS.
     */
    public void incrementNumRadioModeChangeToSbs() {
        synchronized (mLock) {
            mWifiLogProto.numRadioModeChangeToSbs++;
        }
    }

    /**
     * Increment number of times we detected a radio mode change to DBS.
     */
    public void incrementNumRadioModeChangeToDbs() {
        synchronized (mLock) {
            mWifiLogProto.numRadioModeChangeToDbs++;
        }
    }

    /**
     * Increment number of times we detected a channel did not satisfy user band preference.
     */
    public void incrementNumSoftApUserBandPreferenceUnsatisfied() {
        synchronized (mLock) {
            mWifiLogProto.numSoftApUserBandPreferenceUnsatisfied++;
        }
    }

    /** Increment the failure count of SAR sensor listener registration */
    public void incrementNumSarSensorRegistrationFailures() {
        synchronized (mLock) {
            mWifiLogProto.numSarSensorRegistrationFailures++;
        }
    }

    /**
     * Increment N-Way network selection decision histograms:
     * Counts the size of various sets of scanDetails within a scan, and increment the occurrence
     * of that size for the associated histogram. There are ten histograms generated for each
     * combination of: {SSID, BSSID} *{Total, Saved, Open, Saved_or_Open, Passpoint}
     * Only performs this count if isFullBand is true, otherwise, increments the partial scan count
     */
    public void incrementAvailableNetworksHistograms(List<ScanDetail> scanDetails,
            boolean isFullBand) {
        synchronized (mLock) {
            if (mWifiConfigManager == null || mWifiNetworkSelector == null
                    || mPasspointManager == null) {
                return;
            }
            if (!isFullBand) {
                mWifiLogProto.partialAllSingleScanListenerResults++;
                return;
            }
            Set<ScanResultMatchInfo> ssids = new HashSet<ScanResultMatchInfo>();
            int bssids = 0;
            Set<ScanResultMatchInfo> openSsids = new HashSet<ScanResultMatchInfo>();
            int openBssids = 0;
            Set<ScanResultMatchInfo> savedSsids = new HashSet<ScanResultMatchInfo>();
            int savedBssids = 0;
            // openOrSavedSsids calculated from union of savedSsids & openSsids
            int openOrSavedBssids = 0;
            Set<PasspointProvider> savedPasspointProviderProfiles =
                    new HashSet<PasspointProvider>();
            int savedPasspointProviderBssids = 0;
            int passpointR1Aps = 0;
            int passpointR2Aps = 0;
            Map<ANQPNetworkKey, Integer> passpointR1UniqueEss = new HashMap<>();
            Map<ANQPNetworkKey, Integer> passpointR2UniqueEss = new HashMap<>();
            int supporting80211mcAps = 0;
            for (ScanDetail scanDetail : scanDetails) {
                NetworkDetail networkDetail = scanDetail.getNetworkDetail();
                ScanResult scanResult = scanDetail.getScanResult();

                // statistics to be collected for ALL APs (irrespective of signal power)
                if (networkDetail.is80211McResponderSupport()) {
                    supporting80211mcAps++;
                }

                ScanResultMatchInfo matchInfo = ScanResultMatchInfo.fromScanResult(scanResult);
                Pair<PasspointProvider, PasspointMatch> providerMatch = null;
                PasspointProvider passpointProvider = null;
                if (networkDetail.isInterworking()) {
                    providerMatch =
                            mPasspointManager.matchProvider(scanResult);
                    passpointProvider = providerMatch != null ? providerMatch.first : null;

                    if (networkDetail.getHSRelease() == NetworkDetail.HSRelease.R1) {
                        passpointR1Aps++;
                    } else if (networkDetail.getHSRelease() == NetworkDetail.HSRelease.R2) {
                        passpointR2Aps++;
                    }

                    long bssid = 0;
                    boolean validBssid = false;
                    try {
                        bssid = Utils.parseMac(scanResult.BSSID);
                        validBssid = true;
                    } catch (IllegalArgumentException e) {
                        Log.e(TAG,
                                "Invalid BSSID provided in the scan result: " + scanResult.BSSID);
                    }
                    if (validBssid) {
                        ANQPNetworkKey uniqueEss = ANQPNetworkKey.buildKey(scanResult.SSID, bssid,
                                scanResult.hessid, networkDetail.getAnqpDomainID());
                        if (networkDetail.getHSRelease() == NetworkDetail.HSRelease.R1) {
                            Integer countObj = passpointR1UniqueEss.get(uniqueEss);
                            int count = countObj == null ? 0 : countObj;
                            passpointR1UniqueEss.put(uniqueEss, count + 1);
                        } else if (networkDetail.getHSRelease() == NetworkDetail.HSRelease.R2) {
                            Integer countObj = passpointR2UniqueEss.get(uniqueEss);
                            int count = countObj == null ? 0 : countObj;
                            passpointR2UniqueEss.put(uniqueEss, count + 1);
                        }
                    }

                }

                if (mWifiNetworkSelector.isSignalTooWeak(scanResult)) {
                    continue;
                }

                // statistics to be collected ONLY for those APs with sufficient signal power

                ssids.add(matchInfo);
                bssids++;
                boolean isOpen = matchInfo.networkType == WifiConfiguration.SECURITY_TYPE_OPEN;
                WifiConfiguration config =
                        mWifiConfigManager.getConfiguredNetworkForScanDetail(scanDetail);
                boolean isSaved = (config != null) && !config.isEphemeral()
                        && !config.isPasspoint();
                boolean isSavedPasspoint = passpointProvider != null;
                if (isOpen) {
                    openSsids.add(matchInfo);
                    openBssids++;
                }
                if (isSaved) {
                    savedSsids.add(matchInfo);
                    savedBssids++;
                }
                if (isOpen || isSaved) {
                    openOrSavedBssids++;
                    // Calculate openOrSavedSsids union later
                }
                if (isSavedPasspoint) {
                    savedPasspointProviderProfiles.add(passpointProvider);
                    savedPasspointProviderBssids++;
                }
            }
            mWifiLogProto.fullBandAllSingleScanListenerResults++;
            incrementTotalScanSsids(mTotalSsidsInScanHistogram, ssids.size());
            incrementTotalScanResults(mTotalBssidsInScanHistogram, bssids);
            incrementSsid(mAvailableOpenSsidsInScanHistogram, openSsids.size());
            incrementBssid(mAvailableOpenBssidsInScanHistogram, openBssids);
            incrementSsid(mAvailableSavedSsidsInScanHistogram, savedSsids.size());
            incrementBssid(mAvailableSavedBssidsInScanHistogram, savedBssids);
            openSsids.addAll(savedSsids); // openSsids = Union(openSsids, savedSsids)
            incrementSsid(mAvailableOpenOrSavedSsidsInScanHistogram, openSsids.size());
            incrementBssid(mAvailableOpenOrSavedBssidsInScanHistogram, openOrSavedBssids);
            incrementSsid(mAvailableSavedPasspointProviderProfilesInScanHistogram,
                    savedPasspointProviderProfiles.size());
            incrementBssid(mAvailableSavedPasspointProviderBssidsInScanHistogram,
                    savedPasspointProviderBssids);
            incrementTotalPasspointAps(mObservedHotspotR1ApInScanHistogram, passpointR1Aps);
            incrementTotalPasspointAps(mObservedHotspotR2ApInScanHistogram, passpointR2Aps);
            incrementTotalUniquePasspointEss(mObservedHotspotR1EssInScanHistogram,
                    passpointR1UniqueEss.size());
            incrementTotalUniquePasspointEss(mObservedHotspotR2EssInScanHistogram,
                    passpointR2UniqueEss.size());
            for (Integer count : passpointR1UniqueEss.values()) {
                incrementPasspointPerUniqueEss(mObservedHotspotR1ApsPerEssInScanHistogram, count);
            }
            for (Integer count : passpointR2UniqueEss.values()) {
                incrementPasspointPerUniqueEss(mObservedHotspotR2ApsPerEssInScanHistogram, count);
            }
            increment80211mcAps(mObserved80211mcApInScanHistogram, supporting80211mcAps);
        }
    }

    /**
     * TODO: (b/72443859) Use notifierTag param to separate metrics for OpenNetworkNotifier and
     * CarrierNetworkNotifier, for this method and all other related metrics.
     */
    /** Increments the occurence of a "Connect to Network" notification. */
    public void incrementConnectToNetworkNotification(String notifierTag, int notificationType) {
        synchronized (mLock) {
            int count = mConnectToNetworkNotificationCount.get(notificationType);
            mConnectToNetworkNotificationCount.put(notificationType, count + 1);
        }
    }

    /** Increments the occurence of an "Connect to Network" notification user action. */
    public void incrementConnectToNetworkNotificationAction(String notifierTag,
            int notificationType, int actionType) {
        synchronized (mLock) {
            int key = notificationType * CONNECT_TO_NETWORK_NOTIFICATION_ACTION_KEY_MULTIPLIER
                    + actionType;
            int count = mConnectToNetworkNotificationActionCount.get(key);
            mConnectToNetworkNotificationActionCount.put(key, count + 1);
        }
    }

    /**
     * Sets the number of SSIDs blacklisted from recommendation by the open network notification
     * recommender.
     */
    public void setNetworkRecommenderBlacklistSize(String notifierTag, int size) {
        synchronized (mLock) {
            mOpenNetworkRecommenderBlacklistSize = size;
        }
    }

    /** Sets if the available network notification feature is enabled. */
    public void setIsWifiNetworksAvailableNotificationEnabled(String notifierTag, boolean enabled) {
        synchronized (mLock) {
            mIsWifiNetworksAvailableNotificationOn = enabled;
        }
    }

    /** Increments the occurence of connection attempts that were initiated unsuccessfully */
    public void incrementNumNetworkRecommendationUpdates(String notifierTag) {
        synchronized (mLock) {
            mNumOpenNetworkRecommendationUpdates++;
        }
    }

    /** Increments the occurence of connection attempts that were initiated unsuccessfully */
    public void incrementNumNetworkConnectMessageFailedToSend(String notifierTag) {
        synchronized (mLock) {
            mNumOpenNetworkConnectMessageFailedToSend++;
        }
    }

    /** Sets if Connected MAC Randomization feature is enabled */
    public void setIsMacRandomizationOn(boolean enabled) {
        synchronized (mLock) {
            mIsMacRandomizationOn = enabled;
        }
    }

    /** Log firmware alert related metrics */
    public void logFirmwareAlert(int errorCode) {
        incrementAlertReasonCount(errorCode);
        logWifiIsUnusableEvent(WifiIsUnusableEvent.TYPE_FIRMWARE_ALERT, errorCode);
        addToWifiUsabilityStatsList(WifiUsabilityStats.LABEL_BAD,
                WifiUsabilityStats.TYPE_FIRMWARE_ALERT, errorCode);
    }

    public static final String PROTO_DUMP_ARG = "wifiMetricsProto";
    public static final String CLEAN_DUMP_ARG = "clean";

    /**
     * Dump all WifiMetrics. Collects some metrics from ConfigStore, Settings and WifiManager
     * at this time.
     *
     * @param fd unused
     * @param pw PrintWriter for writing dump to
     * @param args [wifiMetricsProto [clean]]
     */
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        synchronized (mLock) {
            consolidateScoringParams();
            if (args != null && args.length > 0 && PROTO_DUMP_ARG.equals(args[0])) {
                // Dump serialized WifiLog proto
                consolidateProto();

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
                pw.println("mWifiLogProto.numSavedNetworksWithMacRandomization="
                        + mWifiLogProto.numSavedNetworksWithMacRandomization);
                pw.println("mWifiLogProto.numOpenNetworks=" + mWifiLogProto.numOpenNetworks);
                pw.println("mWifiLogProto.numLegacyPersonalNetworks="
                        + mWifiLogProto.numLegacyPersonalNetworks);
                pw.println("mWifiLogProto.numLegacyEnterpriseNetworks="
                        + mWifiLogProto.numLegacyEnterpriseNetworks);
                pw.println("mWifiLogProto.numEnhancedOpenNetworks="
                        + mWifiLogProto.numEnhancedOpenNetworks);
                pw.println("mWifiLogProto.numWpa3PersonalNetworks="
                        + mWifiLogProto.numWpa3PersonalNetworks);
                pw.println("mWifiLogProto.numWpa3EnterpriseNetworks="
                        + mWifiLogProto.numWpa3EnterpriseNetworks);
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
                pw.println("mWifiLogProto.numConnecitvityOneshotScans="
                        + mWifiLogProto.numConnectivityOneshotScans);
                pw.println("mWifiLogProto.numOneshotScans="
                        + mWifiLogProto.numOneshotScans);
                pw.println("mWifiLogProto.numOneshotHasDfsChannelScans="
                        + mWifiLogProto.numOneshotHasDfsChannelScans);
                pw.println("mWifiLogProto.numBackgroundScans="
                        + mWifiLogProto.numBackgroundScans);
                pw.println("mWifiLogProto.numExternalAppOneshotScanRequests="
                        + mWifiLogProto.numExternalAppOneshotScanRequests);
                pw.println("mWifiLogProto.numExternalForegroundAppOneshotScanRequestsThrottled="
                        + mWifiLogProto.numExternalForegroundAppOneshotScanRequestsThrottled);
                pw.println("mWifiLogProto.numExternalBackgroundAppOneshotScanRequestsThrottled="
                        + mWifiLogProto.numExternalBackgroundAppOneshotScanRequestsThrottled);

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
                pw.println("mWifiLogProto.watchdogTotalConnectionFailureCountAfterTrigger="
                        + mWifiLogProto.watchdogTotalConnectionFailureCountAfterTrigger);
                pw.println("mWifiLogProto.watchdogTriggerToConnectionSuccessDurationMs="
                        + mWifiLogProto.watchdogTriggerToConnectionSuccessDurationMs);
                pw.println("mWifiLogProto.recordDurationSec="
                        + ((mClock.getElapsedSinceBootMillis() / 1000) - mRecordStartTimeSec));

                try {
                    JSONObject rssiMap = new JSONObject();
                    for (Map.Entry<Integer, SparseIntArray> entry : mRssiPollCountsMap.entrySet()) {
                        int frequency = entry.getKey();
                        final SparseIntArray histogram = entry.getValue();
                        JSONArray histogramElements = new JSONArray();
                        for (int i = MIN_RSSI_POLL; i <= MAX_RSSI_POLL; i++) {
                            int count = histogram.get(i);
                            if (count == 0) {
                                continue;
                            }
                            JSONObject histogramElement = new JSONObject();
                            histogramElement.put(Integer.toString(i), count);
                            histogramElements.put(histogramElement);
                        }
                        rssiMap.put(Integer.toString(frequency), histogramElements);
                    }
                    pw.println("mWifiLogProto.rssiPollCount: " + rssiMap.toString());
                } catch (JSONException e) {
                    pw.println("JSONException occurred: " + e.getMessage());
                }

                pw.println("mWifiLogProto.rssiPollDeltaCount: Printing counts for ["
                        + MIN_RSSI_DELTA + ", " + MAX_RSSI_DELTA + "]");
                StringBuilder sb = new StringBuilder();
                for (int i = MIN_RSSI_DELTA; i <= MAX_RSSI_DELTA; i++) {
                    sb.append(mRssiDeltaCounts.get(i) + " ");
                }
                pw.println("  " + sb.toString());
                pw.println("mWifiLogProto.linkSpeedCounts: ");
                sb.setLength(0);
                for (int i = 0; i < mLinkSpeedCounts.size(); i++) {
                    LinkSpeedCount linkSpeedCount = mLinkSpeedCounts.valueAt(i);
                    sb.append(linkSpeedCount.linkSpeedMbps).append(":{")
                            .append(linkSpeedCount.count).append(", ")
                            .append(linkSpeedCount.rssiSumDbm).append(", ")
                            .append(linkSpeedCount.rssiSumOfSquaresDbmSq).append("} ");
                }
                if (sb.length() > 0) {
                    pw.println(sb.toString());
                }
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
                pw.println("mWifiLogProto.numLegacyPersonalNetworkScanResults="
                        + mWifiLogProto.numLegacyPersonalNetworkScanResults);
                pw.println("mWifiLogProto.numLegacyEnterpriseNetworkScanResults="
                        + mWifiLogProto.numLegacyEnterpriseNetworkScanResults);
                pw.println("mWifiLogProto.numEnhancedOpenNetworkScanResults="
                        + mWifiLogProto.numEnhancedOpenNetworkScanResults);
                pw.println("mWifiLogProto.numWpa3PersonalNetworkScanResults="
                        + mWifiLogProto.numWpa3PersonalNetworkScanResults);
                pw.println("mWifiLogProto.numWpa3EnterpriseNetworkScanResults="
                        + mWifiLogProto.numWpa3EnterpriseNetworkScanResults);
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
                pw.println("mWifiLogProto.WifiUsabilityScoreCount: [" + MIN_WIFI_USABILITY_SCORE
                        + ", " + MAX_WIFI_USABILITY_SCORE + "]");
                for (int i = MIN_WIFI_USABILITY_SCORE; i <= MAX_WIFI_USABILITY_SCORE; i++) {
                    pw.print(mWifiUsabilityScoreCounts.get(i) + " ");
                }
                pw.println(); // add a line after wifi usability scores
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
                pw.println("mWifiLogProto.numSupplicantCrashes="
                        + mWifiLogProto.numSupplicantCrashes);
                pw.println("mWifiLogProto.numHostapdCrashes="
                        + mWifiLogProto.numHostapdCrashes);
                pw.println("mWifiLogProto.numSetupClientInterfaceFailureDueToHal="
                        + mWifiLogProto.numSetupClientInterfaceFailureDueToHal);
                pw.println("mWifiLogProto.numSetupClientInterfaceFailureDueToWificond="
                        + mWifiLogProto.numSetupClientInterfaceFailureDueToWificond);
                pw.println("mWifiLogProto.numSetupClientInterfaceFailureDueToSupplicant="
                        + mWifiLogProto.numSetupClientInterfaceFailureDueToSupplicant);
                pw.println("mWifiLogProto.numSetupSoftApInterfaceFailureDueToHal="
                        + mWifiLogProto.numSetupSoftApInterfaceFailureDueToHal);
                pw.println("mWifiLogProto.numSetupSoftApInterfaceFailureDueToWificond="
                        + mWifiLogProto.numSetupSoftApInterfaceFailureDueToWificond);
                pw.println("mWifiLogProto.numSetupSoftApInterfaceFailureDueToHostapd="
                        + mWifiLogProto.numSetupSoftApInterfaceFailureDueToHostapd);
                pw.println("mWifiLogProto.numSarSensorRegistrationFailures="
                        + mWifiLogProto.numSarSensorRegistrationFailures);
                pw.println("StaEventList:");
                for (StaEventWithTime event : mStaEventList) {
                    pw.println(event);
                }

                pw.println("mWifiLogProto.numPasspointProviders="
                        + mWifiLogProto.numPasspointProviders);
                pw.println("mWifiLogProto.numPasspointProviderInstallation="
                        + mWifiLogProto.numPasspointProviderInstallation);
                pw.println("mWifiLogProto.numPasspointProviderInstallSuccess="
                        + mWifiLogProto.numPasspointProviderInstallSuccess);
                pw.println("mWifiLogProto.numPasspointProviderUninstallation="
                        + mWifiLogProto.numPasspointProviderUninstallation);
                pw.println("mWifiLogProto.numPasspointProviderUninstallSuccess="
                        + mWifiLogProto.numPasspointProviderUninstallSuccess);
                pw.println("mWifiLogProto.numPasspointProvidersSuccessfullyConnected="
                        + mWifiLogProto.numPasspointProvidersSuccessfullyConnected);

                pw.println("mWifiLogProto.installedPasspointProfileTypeForR1:"
                        + mInstalledPasspointProfileTypeForR1);
                pw.println("mWifiLogProto.installedPasspointProfileTypeForR2:"
                        + mInstalledPasspointProfileTypeForR2);

                pw.println("mWifiLogProto.passpointProvisionStats.numProvisionSuccess="
                            + mNumProvisionSuccess);
                pw.println("mWifiLogProto.passpointProvisionStats.provisionFailureCount:"
                            + mPasspointProvisionFailureCounts);

                pw.println("mWifiLogProto.numRadioModeChangeToMcc="
                        + mWifiLogProto.numRadioModeChangeToMcc);
                pw.println("mWifiLogProto.numRadioModeChangeToScc="
                        + mWifiLogProto.numRadioModeChangeToScc);
                pw.println("mWifiLogProto.numRadioModeChangeToSbs="
                        + mWifiLogProto.numRadioModeChangeToSbs);
                pw.println("mWifiLogProto.numRadioModeChangeToDbs="
                        + mWifiLogProto.numRadioModeChangeToDbs);
                pw.println("mWifiLogProto.numSoftApUserBandPreferenceUnsatisfied="
                        + mWifiLogProto.numSoftApUserBandPreferenceUnsatisfied);
                pw.println("mTotalSsidsInScanHistogram:"
                        + mTotalSsidsInScanHistogram.toString());
                pw.println("mTotalBssidsInScanHistogram:"
                        + mTotalBssidsInScanHistogram.toString());
                pw.println("mAvailableOpenSsidsInScanHistogram:"
                        + mAvailableOpenSsidsInScanHistogram.toString());
                pw.println("mAvailableOpenBssidsInScanHistogram:"
                        + mAvailableOpenBssidsInScanHistogram.toString());
                pw.println("mAvailableSavedSsidsInScanHistogram:"
                        + mAvailableSavedSsidsInScanHistogram.toString());
                pw.println("mAvailableSavedBssidsInScanHistogram:"
                        + mAvailableSavedBssidsInScanHistogram.toString());
                pw.println("mAvailableOpenOrSavedSsidsInScanHistogram:"
                        + mAvailableOpenOrSavedSsidsInScanHistogram.toString());
                pw.println("mAvailableOpenOrSavedBssidsInScanHistogram:"
                        + mAvailableOpenOrSavedBssidsInScanHistogram.toString());
                pw.println("mAvailableSavedPasspointProviderProfilesInScanHistogram:"
                        + mAvailableSavedPasspointProviderProfilesInScanHistogram.toString());
                pw.println("mAvailableSavedPasspointProviderBssidsInScanHistogram:"
                        + mAvailableSavedPasspointProviderBssidsInScanHistogram.toString());
                pw.println("mWifiLogProto.partialAllSingleScanListenerResults="
                        + mWifiLogProto.partialAllSingleScanListenerResults);
                pw.println("mWifiLogProto.fullBandAllSingleScanListenerResults="
                        + mWifiLogProto.fullBandAllSingleScanListenerResults);
                pw.println("mWifiAwareMetrics:");
                mWifiAwareMetrics.dump(fd, pw, args);
                pw.println("mRttMetrics:");
                mRttMetrics.dump(fd, pw, args);

                pw.println("mPnoScanMetrics.numPnoScanAttempts="
                        + mPnoScanMetrics.numPnoScanAttempts);
                pw.println("mPnoScanMetrics.numPnoScanFailed="
                        + mPnoScanMetrics.numPnoScanFailed);
                pw.println("mPnoScanMetrics.numPnoScanStartedOverOffload="
                        + mPnoScanMetrics.numPnoScanStartedOverOffload);
                pw.println("mPnoScanMetrics.numPnoScanFailedOverOffload="
                        + mPnoScanMetrics.numPnoScanFailedOverOffload);
                pw.println("mPnoScanMetrics.numPnoFoundNetworkEvents="
                        + mPnoScanMetrics.numPnoFoundNetworkEvents);

                pw.println("mWifiLinkLayerUsageStats.loggingDurationMs="
                        + mWifiLinkLayerUsageStats.loggingDurationMs);
                pw.println("mWifiLinkLayerUsageStats.radioOnTimeMs="
                        + mWifiLinkLayerUsageStats.radioOnTimeMs);
                pw.println("mWifiLinkLayerUsageStats.radioTxTimeMs="
                        + mWifiLinkLayerUsageStats.radioTxTimeMs);
                pw.println("mWifiLinkLayerUsageStats.radioRxTimeMs="
                        + mWifiLinkLayerUsageStats.radioRxTimeMs);
                pw.println("mWifiLinkLayerUsageStats.radioScanTimeMs="
                        + mWifiLinkLayerUsageStats.radioScanTimeMs);
                pw.println("mWifiLinkLayerUsageStats.radioNanScanTimeMs="
                        + mWifiLinkLayerUsageStats.radioNanScanTimeMs);
                pw.println("mWifiLinkLayerUsageStats.radioBackgroundScanTimeMs="
                        + mWifiLinkLayerUsageStats.radioBackgroundScanTimeMs);
                pw.println("mWifiLinkLayerUsageStats.radioRoamScanTimeMs="
                        + mWifiLinkLayerUsageStats.radioRoamScanTimeMs);
                pw.println("mWifiLinkLayerUsageStats.radioPnoScanTimeMs="
                        + mWifiLinkLayerUsageStats.radioPnoScanTimeMs);
                pw.println("mWifiLinkLayerUsageStats.radioHs20ScanTimeMs="
                        + mWifiLinkLayerUsageStats.radioHs20ScanTimeMs);

                pw.println("mWifiLogProto.connectToNetworkNotificationCount="
                        + mConnectToNetworkNotificationCount.toString());
                pw.println("mWifiLogProto.connectToNetworkNotificationActionCount="
                        + mConnectToNetworkNotificationActionCount.toString());
                pw.println("mWifiLogProto.openNetworkRecommenderBlacklistSize="
                        + mOpenNetworkRecommenderBlacklistSize);
                pw.println("mWifiLogProto.isWifiNetworksAvailableNotificationOn="
                        + mIsWifiNetworksAvailableNotificationOn);
                pw.println("mWifiLogProto.numOpenNetworkRecommendationUpdates="
                        + mNumOpenNetworkRecommendationUpdates);
                pw.println("mWifiLogProto.numOpenNetworkConnectMessageFailedToSend="
                        + mNumOpenNetworkConnectMessageFailedToSend);

                pw.println("mWifiLogProto.observedHotspotR1ApInScanHistogram="
                        + mObservedHotspotR1ApInScanHistogram);
                pw.println("mWifiLogProto.observedHotspotR2ApInScanHistogram="
                        + mObservedHotspotR2ApInScanHistogram);
                pw.println("mWifiLogProto.observedHotspotR1EssInScanHistogram="
                        + mObservedHotspotR1EssInScanHistogram);
                pw.println("mWifiLogProto.observedHotspotR2EssInScanHistogram="
                        + mObservedHotspotR2EssInScanHistogram);
                pw.println("mWifiLogProto.observedHotspotR1ApsPerEssInScanHistogram="
                        + mObservedHotspotR1ApsPerEssInScanHistogram);
                pw.println("mWifiLogProto.observedHotspotR2ApsPerEssInScanHistogram="
                        + mObservedHotspotR2ApsPerEssInScanHistogram);

                pw.println("mWifiLogProto.observed80211mcSupportingApsInScanHistogram"
                        + mObserved80211mcApInScanHistogram);

                pw.println("mSoftApTetheredEvents:");
                for (SoftApConnectedClientsEvent event : mSoftApEventListTethered) {
                    StringBuilder eventLine = new StringBuilder();
                    eventLine.append("event_type=" + event.eventType);
                    eventLine.append(",time_stamp_millis=" + event.timeStampMillis);
                    eventLine.append(",num_connected_clients=" + event.numConnectedClients);
                    eventLine.append(",channel_frequency=" + event.channelFrequency);
                    eventLine.append(",channel_bandwidth=" + event.channelBandwidth);
                    pw.println(eventLine.toString());
                }
                pw.println("mSoftApLocalOnlyEvents:");
                for (SoftApConnectedClientsEvent event : mSoftApEventListLocalOnly) {
                    StringBuilder eventLine = new StringBuilder();
                    eventLine.append("event_type=" + event.eventType);
                    eventLine.append(",time_stamp_millis=" + event.timeStampMillis);
                    eventLine.append(",num_connected_clients=" + event.numConnectedClients);
                    eventLine.append(",channel_frequency=" + event.channelFrequency);
                    eventLine.append(",channel_bandwidth=" + event.channelBandwidth);
                    pw.println(eventLine.toString());
                }

                pw.println("mWpsMetrics.numWpsAttempts="
                        + mWpsMetrics.numWpsAttempts);
                pw.println("mWpsMetrics.numWpsSuccess="
                        + mWpsMetrics.numWpsSuccess);
                pw.println("mWpsMetrics.numWpsStartFailure="
                        + mWpsMetrics.numWpsStartFailure);
                pw.println("mWpsMetrics.numWpsOverlapFailure="
                        + mWpsMetrics.numWpsOverlapFailure);
                pw.println("mWpsMetrics.numWpsTimeoutFailure="
                        + mWpsMetrics.numWpsTimeoutFailure);
                pw.println("mWpsMetrics.numWpsOtherConnectionFailure="
                        + mWpsMetrics.numWpsOtherConnectionFailure);
                pw.println("mWpsMetrics.numWpsSupplicantFailure="
                        + mWpsMetrics.numWpsSupplicantFailure);
                pw.println("mWpsMetrics.numWpsCancellation="
                        + mWpsMetrics.numWpsCancellation);

                mWifiPowerMetrics.dump(pw);
                mWifiWakeMetrics.dump(pw);

                pw.println("mWifiLogProto.isMacRandomizationOn=" + mIsMacRandomizationOn);
                pw.println("mWifiLogProto.scoreExperimentId=" + mWifiLogProto.scoreExperimentId);
                pw.println("mExperimentValues.wifiIsUnusableLoggingEnabled="
                        + mExperimentValues.wifiIsUnusableLoggingEnabled);
                pw.println("mExperimentValues.wifiDataStallMinTxBad="
                        + mExperimentValues.wifiDataStallMinTxBad);
                pw.println("mExperimentValues.wifiDataStallMinTxSuccessWithoutRx="
                        + mExperimentValues.wifiDataStallMinTxSuccessWithoutRx);
                pw.println("mExperimentValues.linkSpeedCountsLoggingEnabled="
                        + mExperimentValues.linkSpeedCountsLoggingEnabled);
                pw.println("WifiIsUnusableEventList: ");
                for (WifiIsUnusableWithTime event : mWifiIsUnusableList) {
                    pw.println(event);
                }
                pw.println("Hardware Version: " + SystemProperties.get("ro.boot.revision", ""));

                pw.println("mWifiUsabilityStatsEntriesList:");
                for (WifiUsabilityStatsEntry stats : mWifiUsabilityStatsEntriesList) {
                    printWifiUsabilityStatsEntry(pw, stats);
                }
                pw.println("mWifiUsabilityStatsList:");
                for (WifiUsabilityStats stats : mWifiUsabilityStatsListGood) {
                    pw.println("\nlabel=" + stats.label);
                    pw.println("\ntrigger_type=" + stats.triggerType);
                    pw.println("\ntime_stamp_ms=" + stats.timeStampMs);
                    for (WifiUsabilityStatsEntry entry : stats.stats) {
                        printWifiUsabilityStatsEntry(pw, entry);
                    }
                }
                for (WifiUsabilityStats stats : mWifiUsabilityStatsListBad) {
                    pw.println("\nlabel=" + stats.label);
                    pw.println("\ntrigger_type=" + stats.triggerType);
                    pw.println("\ntime_stamp_ms=" + stats.timeStampMs);
                    for (WifiUsabilityStatsEntry entry : stats.stats) {
                        printWifiUsabilityStatsEntry(pw, entry);
                    }
                }

                pw.println("mMobilityStatePnoStatsMap:");
                for (int i = 0; i < mMobilityStatePnoStatsMap.size(); i++) {
                    printDeviceMobilityStatePnoScanStats(pw, mMobilityStatePnoStatsMap.valueAt(i));
                }

                mWifiP2pMetrics.dump(pw);
                pw.println("mDppMetrics:");
                mDppMetrics.dump(pw);

                pw.println("mWifiConfigStoreReadDurationHistogram:"
                        + mWifiConfigStoreReadDurationHistogram.toString());
                pw.println("mWifiConfigStoreWriteDurationHistogram:"
                        + mWifiConfigStoreWriteDurationHistogram.toString());

                pw.println("mLinkProbeSuccessRssiCounts:" + mLinkProbeSuccessRssiCounts);
                pw.println("mLinkProbeFailureRssiCounts:" + mLinkProbeFailureRssiCounts);
                pw.println("mLinkProbeSuccessLinkSpeedCounts:" + mLinkProbeSuccessLinkSpeedCounts);
                pw.println("mLinkProbeFailureLinkSpeedCounts:" + mLinkProbeFailureLinkSpeedCounts);
                pw.println("mLinkProbeSuccessSecondsSinceLastTxSuccessHistogram:"
                        + mLinkProbeSuccessSecondsSinceLastTxSuccessHistogram);
                pw.println("mLinkProbeFailureSecondsSinceLastTxSuccessHistogram:"
                        + mLinkProbeFailureSecondsSinceLastTxSuccessHistogram);
                pw.println("mLinkProbeSuccessElapsedTimeMsHistogram:"
                        + mLinkProbeSuccessElapsedTimeMsHistogram);
                pw.println("mLinkProbeFailureReasonCounts:" + mLinkProbeFailureReasonCounts);
                pw.println("mLinkProbeExperimentProbeCounts:" + mLinkProbeExperimentProbeCounts);

                pw.println("mNetworkSelectionExperimentPairNumChoicesCounts:"
                        + mNetworkSelectionExperimentPairNumChoicesCounts);
                pw.println("mLinkProbeStaEventCount:" + mLinkProbeStaEventCount);

                pw.println("mWifiNetworkRequestApiLog:\n" + mWifiNetworkRequestApiLog);
                pw.println("mWifiNetworkRequestApiMatchSizeHistogram:\n"
                        + mWifiNetworkRequestApiMatchSizeHistogram);
                pw.println("mWifiNetworkSuggestionApiLog:\n" + mWifiNetworkSuggestionApiLog);
                pw.println("mWifiNetworkSuggestionApiMatchSizeHistogram:\n"
                        + mWifiNetworkRequestApiMatchSizeHistogram);
                pw.println("mNetworkIdToNominatorId:\n" + mNetworkIdToNominatorId);
                pw.println("mWifiLockStats:\n" + mWifiLockStats);
                pw.println("mWifiLockHighPerfAcqDurationSecHistogram:\n"
                        + mWifiLockHighPerfAcqDurationSecHistogram);
                pw.println("mWifiLockLowLatencyAcqDurationSecHistogram:\n"
                        + mWifiLockLowLatencyAcqDurationSecHistogram);
                pw.println("mWifiLockHighPerfActiveSessionDurationSecHistogram:\n"
                        + mWifiLockHighPerfActiveSessionDurationSecHistogram);
                pw.println("mWifiLockLowLatencyActiveSessionDurationSecHistogram:\n"
                        + mWifiLockLowLatencyActiveSessionDurationSecHistogram);
                pw.println("mWifiToggleStats:\n" + mWifiToggleStats);
                pw.println("mWifiLogProto.numAddOrUpdateNetworkCalls="
                        + mWifiLogProto.numAddOrUpdateNetworkCalls);
                pw.println("mWifiLogProto.numEnableNetworkCalls="
                        + mWifiLogProto.numEnableNetworkCalls);
            }
        }
    }

    private void printWifiUsabilityStatsEntry(PrintWriter pw, WifiUsabilityStatsEntry entry) {
        StringBuilder line = new StringBuilder();
        line.append("timestamp_ms=" + entry.timeStampMs);
        line.append(",rssi=" + entry.rssi);
        line.append(",link_speed_mbps=" + entry.linkSpeedMbps);
        line.append(",total_tx_success=" + entry.totalTxSuccess);
        line.append(",total_tx_retries=" + entry.totalTxRetries);
        line.append(",total_tx_bad=" + entry.totalTxBad);
        line.append(",total_rx_success=" + entry.totalRxSuccess);
        line.append(",total_radio_on_time_ms=" + entry.totalRadioOnTimeMs);
        line.append(",total_radio_tx_time_ms=" + entry.totalRadioTxTimeMs);
        line.append(",total_radio_rx_time_ms=" + entry.totalRadioRxTimeMs);
        line.append(",total_scan_time_ms=" + entry.totalScanTimeMs);
        line.append(",total_nan_scan_time_ms=" + entry.totalNanScanTimeMs);
        line.append(",total_background_scan_time_ms=" + entry.totalBackgroundScanTimeMs);
        line.append(",total_roam_scan_time_ms=" + entry.totalRoamScanTimeMs);
        line.append(",total_pno_scan_time_ms=" + entry.totalPnoScanTimeMs);
        line.append(",total_hotspot_2_scan_time_ms=" + entry.totalHotspot2ScanTimeMs);
        line.append(",wifi_score=" + entry.wifiScore);
        line.append(",wifi_usability_score=" + entry.wifiUsabilityScore);
        line.append(",seq_num_to_framework=" + entry.seqNumToFramework);
        line.append(",prediction_horizon_sec=" + entry.predictionHorizonSec);
        line.append(",total_cca_busy_freq_time_ms=" + entry.totalCcaBusyFreqTimeMs);
        line.append(",total_radio_on_freq_time_ms=" + entry.totalRadioOnFreqTimeMs);
        line.append(",total_beacon_rx=" + entry.totalBeaconRx);
        line.append(",probe_status_since_last_update=" + entry.probeStatusSinceLastUpdate);
        line.append(",probe_elapsed_time_ms_since_last_update="
                + entry.probeElapsedTimeSinceLastUpdateMs);
        line.append(",probe_mcs_rate_since_last_update=" + entry.probeMcsRateSinceLastUpdate);
        line.append(",rx_link_speed_mbps=" + entry.rxLinkSpeedMbps);
        line.append(",seq_num_inside_framework=" + entry.seqNumInsideFramework);
        line.append(",is_same_bssid_and_freq=" + entry.isSameBssidAndFreq);
        line.append(",cellular_data_network_type=" + entry.cellularDataNetworkType);
        line.append(",cellular_signal_strength_dbm=" + entry.cellularSignalStrengthDbm);
        line.append(",cellular_signal_strength_db=" + entry.cellularSignalStrengthDb);
        line.append(",is_same_registered_cell=" + entry.isSameRegisteredCell);
        line.append(",device_mobility_state=" + entry.deviceMobilityState);
        pw.println(line.toString());
    }

    private void printDeviceMobilityStatePnoScanStats(PrintWriter pw,
            DeviceMobilityStatePnoScanStats stats) {
        StringBuilder line = new StringBuilder();
        line.append("device_mobility_state=" + stats.deviceMobilityState);
        line.append(",num_times_entered_state=" + stats.numTimesEnteredState);
        line.append(",total_duration_ms=" + stats.totalDurationMs);
        line.append(",pno_duration_ms=" + stats.pnoDurationMs);
        pw.println(line.toString());
    }

    /**
     * Update various counts of saved network types
     * @param networks List of WifiConfigurations representing all saved networks, must not be null
     */
    public void updateSavedNetworks(List<WifiConfiguration> networks) {
        synchronized (mLock) {
            mWifiLogProto.numSavedNetworks = networks.size();
            mWifiLogProto.numOpenNetworks = 0;
            mWifiLogProto.numLegacyPersonalNetworks = 0;
            mWifiLogProto.numLegacyEnterpriseNetworks = 0;
            mWifiLogProto.numEnhancedOpenNetworks = 0;
            mWifiLogProto.numWpa3PersonalNetworks = 0;
            mWifiLogProto.numWpa3EnterpriseNetworks = 0;
            mWifiLogProto.numNetworksAddedByUser = 0;
            mWifiLogProto.numNetworksAddedByApps = 0;
            mWifiLogProto.numHiddenNetworks = 0;
            mWifiLogProto.numPasspointNetworks = 0;
            for (WifiConfiguration config : networks) {
                if (config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.NONE)) {
                    mWifiLogProto.numOpenNetworks++;
                } else if (config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.OWE)) {
                    mWifiLogProto.numEnhancedOpenNetworks++;
                } else if (config.isEnterprise()) {
                    if (config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.SUITE_B_192)) {
                        mWifiLogProto.numWpa3EnterpriseNetworks++;
                    } else {
                        mWifiLogProto.numLegacyEnterpriseNetworks++;
                    }
                } else {
                    if (config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.SAE)) {
                        mWifiLogProto.numWpa3PersonalNetworks++;
                    } else {
                        mWifiLogProto.numLegacyPersonalNetworks++;
                    }
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
                if (config.macRandomizationSetting == WifiConfiguration.RANDOMIZATION_PERSISTENT) {
                    mWifiLogProto.numSavedNetworksWithMacRandomization++;
                }
            }
        }
    }

    /**
     * Update metrics for saved Passpoint profiles.
     *
     * @param numSavedProfiles The number of saved Passpoint profiles
     * @param numConnectedProfiles The number of saved Passpoint profiles that have ever resulted
     *                             in a successful network connection
     */
    public void updateSavedPasspointProfiles(int numSavedProfiles, int numConnectedProfiles) {
        synchronized (mLock) {
            mWifiLogProto.numPasspointProviders = numSavedProfiles;
            mWifiLogProto.numPasspointProvidersSuccessfullyConnected = numConnectedProfiles;
        }
    }

    /**
     * Update number of times for type of saved Passpoint profile.
     *
     * @param providers Passpoint providers installed on the device.
     */
    public void updateSavedPasspointProfilesInfo(
            Map<String, PasspointProvider> providers) {
        int passpointType;
        int eapType;
        PasspointConfiguration config;
        synchronized (mLock) {
            mInstalledPasspointProfileTypeForR1.clear();
            mInstalledPasspointProfileTypeForR2.clear();
            for (Map.Entry<String, PasspointProvider> entry : providers.entrySet()) {
                config = entry.getValue().getConfig();
                if (config.getCredential().getUserCredential() != null) {
                    eapType = EAPConstants.EAP_TTLS;
                } else if (config.getCredential().getCertCredential() != null) {
                    eapType = EAPConstants.EAP_TLS;
                } else if (config.getCredential().getSimCredential() != null) {
                    eapType = config.getCredential().getSimCredential().getEapType();
                } else {
                    eapType = -1;
                }
                switch (eapType) {
                    case EAPConstants.EAP_TLS:
                        passpointType = WifiMetricsProto.PasspointProfileTypeCount.TYPE_EAP_TLS;
                        break;
                    case EAPConstants.EAP_TTLS:
                        passpointType = WifiMetricsProto.PasspointProfileTypeCount.TYPE_EAP_TTLS;
                        break;
                    case EAPConstants.EAP_SIM:
                        passpointType = WifiMetricsProto.PasspointProfileTypeCount.TYPE_EAP_SIM;
                        break;
                    case EAPConstants.EAP_AKA:
                        passpointType = WifiMetricsProto.PasspointProfileTypeCount.TYPE_EAP_AKA;
                        break;
                    case EAPConstants.EAP_AKA_PRIME:
                        passpointType =
                                WifiMetricsProto.PasspointProfileTypeCount.TYPE_EAP_AKA_PRIME;
                        break;
                    default:
                        passpointType = WifiMetricsProto.PasspointProfileTypeCount.TYPE_UNKNOWN;

                }
                if (config.validateForR2()) {
                    mInstalledPasspointProfileTypeForR2.increment(passpointType);
                } else {
                    mInstalledPasspointProfileTypeForR1.increment(passpointType);
                }
            }
        }
    }

    /**
     * Put all metrics that were being tracked separately into mWifiLogProto
     */
    private void consolidateProto() {
        List<WifiMetricsProto.RssiPollCount> rssis = new ArrayList<>();
        synchronized (mLock) {
            int connectionEventCount = mConnectionEventList.size();
            // Exclude the current active un-ended connection event
            if (mCurrentConnectionEvent != null) {
                connectionEventCount--;
            }
            mWifiLogProto.connectionEvent =
                    new WifiMetricsProto.ConnectionEvent[connectionEventCount];
            for (int i = 0; i < connectionEventCount; i++) {
                mWifiLogProto.connectionEvent[i] = mConnectionEventList.get(i).mConnectionEvent;
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
             * Convert the SparseIntArrays of RSSI poll rssi, counts, and frequency to the
             * proto's repeated IntKeyVal array.
             */
            for (Map.Entry<Integer, SparseIntArray> entry : mRssiPollCountsMap.entrySet()) {
                int frequency = entry.getKey();
                SparseIntArray histogram = entry.getValue();
                for (int i = 0; i < histogram.size(); i++) {
                    WifiMetricsProto.RssiPollCount keyVal = new WifiMetricsProto.RssiPollCount();
                    keyVal.rssi = histogram.keyAt(i);
                    keyVal.count = histogram.valueAt(i);
                    keyVal.frequency = frequency;
                    rssis.add(keyVal);
                }
            }
            mWifiLogProto.rssiPollRssiCount = rssis.toArray(mWifiLogProto.rssiPollRssiCount);

            /**
             * Convert the SparseIntArray of RSSI delta rssi's and counts to the proto's repeated
             * IntKeyVal array.
             */
            mWifiLogProto.rssiPollDeltaCount =
                    new WifiMetricsProto.RssiPollCount[mRssiDeltaCounts.size()];
            for (int i = 0; i < mRssiDeltaCounts.size(); i++) {
                mWifiLogProto.rssiPollDeltaCount[i] = new WifiMetricsProto.RssiPollCount();
                mWifiLogProto.rssiPollDeltaCount[i].rssi = mRssiDeltaCounts.keyAt(i);
                mWifiLogProto.rssiPollDeltaCount[i].count = mRssiDeltaCounts.valueAt(i);
            }

            /**
             * Add LinkSpeedCount objects from mLinkSpeedCounts to proto.
             */
            mWifiLogProto.linkSpeedCounts =
                    new WifiMetricsProto.LinkSpeedCount[mLinkSpeedCounts.size()];
            for (int i = 0; i < mLinkSpeedCounts.size(); i++) {
                mWifiLogProto.linkSpeedCounts[i] = mLinkSpeedCounts.valueAt(i);
            }

            /**
             * Convert the SparseIntArray of alert reasons and counts to the proto's repeated
             * IntKeyVal array.
             */
            mWifiLogProto.alertReasonCount =
                    new WifiMetricsProto.AlertReasonCount[mWifiAlertReasonCounts.size()];
            for (int i = 0; i < mWifiAlertReasonCounts.size(); i++) {
                mWifiLogProto.alertReasonCount[i] = new WifiMetricsProto.AlertReasonCount();
                mWifiLogProto.alertReasonCount[i].reason = mWifiAlertReasonCounts.keyAt(i);
                mWifiLogProto.alertReasonCount[i].count = mWifiAlertReasonCounts.valueAt(i);
            }

            /**
            *  Convert the SparseIntArray of Wifi Score and counts to proto's repeated
            * IntKeyVal array.
            */
            mWifiLogProto.wifiScoreCount =
                    new WifiMetricsProto.WifiScoreCount[mWifiScoreCounts.size()];
            for (int score = 0; score < mWifiScoreCounts.size(); score++) {
                mWifiLogProto.wifiScoreCount[score] = new WifiMetricsProto.WifiScoreCount();
                mWifiLogProto.wifiScoreCount[score].score = mWifiScoreCounts.keyAt(score);
                mWifiLogProto.wifiScoreCount[score].count = mWifiScoreCounts.valueAt(score);
            }

            /**
             * Convert the SparseIntArray of Wifi Usability Score and counts to proto's repeated
             * IntKeyVal array.
             */
            mWifiLogProto.wifiUsabilityScoreCount =
                new WifiMetricsProto.WifiUsabilityScoreCount[mWifiUsabilityScoreCounts.size()];
            for (int scoreIdx = 0; scoreIdx < mWifiUsabilityScoreCounts.size(); scoreIdx++) {
                mWifiLogProto.wifiUsabilityScoreCount[scoreIdx] =
                    new WifiMetricsProto.WifiUsabilityScoreCount();
                mWifiLogProto.wifiUsabilityScoreCount[scoreIdx].score =
                    mWifiUsabilityScoreCounts.keyAt(scoreIdx);
                mWifiLogProto.wifiUsabilityScoreCount[scoreIdx].count =
                    mWifiUsabilityScoreCounts.valueAt(scoreIdx);
            }

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

            /**
             * Convert StaEventList to array of StaEvents
             */
            mWifiLogProto.staEventList = new StaEvent[mStaEventList.size()];
            for (int i = 0; i < mStaEventList.size(); i++) {
                mWifiLogProto.staEventList[i] = mStaEventList.get(i).staEvent;
            }
            mWifiLogProto.totalSsidsInScanHistogram =
                    makeNumConnectableNetworksBucketArray(mTotalSsidsInScanHistogram);
            mWifiLogProto.totalBssidsInScanHistogram =
                    makeNumConnectableNetworksBucketArray(mTotalBssidsInScanHistogram);
            mWifiLogProto.availableOpenSsidsInScanHistogram =
                    makeNumConnectableNetworksBucketArray(mAvailableOpenSsidsInScanHistogram);
            mWifiLogProto.availableOpenBssidsInScanHistogram =
                    makeNumConnectableNetworksBucketArray(mAvailableOpenBssidsInScanHistogram);
            mWifiLogProto.availableSavedSsidsInScanHistogram =
                    makeNumConnectableNetworksBucketArray(mAvailableSavedSsidsInScanHistogram);
            mWifiLogProto.availableSavedBssidsInScanHistogram =
                    makeNumConnectableNetworksBucketArray(mAvailableSavedBssidsInScanHistogram);
            mWifiLogProto.availableOpenOrSavedSsidsInScanHistogram =
                    makeNumConnectableNetworksBucketArray(
                    mAvailableOpenOrSavedSsidsInScanHistogram);
            mWifiLogProto.availableOpenOrSavedBssidsInScanHistogram =
                    makeNumConnectableNetworksBucketArray(
                    mAvailableOpenOrSavedBssidsInScanHistogram);
            mWifiLogProto.availableSavedPasspointProviderProfilesInScanHistogram =
                    makeNumConnectableNetworksBucketArray(
                    mAvailableSavedPasspointProviderProfilesInScanHistogram);
            mWifiLogProto.availableSavedPasspointProviderBssidsInScanHistogram =
                    makeNumConnectableNetworksBucketArray(
                    mAvailableSavedPasspointProviderBssidsInScanHistogram);
            mWifiLogProto.wifiAwareLog = mWifiAwareMetrics.consolidateProto();
            mWifiLogProto.wifiRttLog = mRttMetrics.consolidateProto();

            mWifiLogProto.pnoScanMetrics = mPnoScanMetrics;
            mWifiLogProto.wifiLinkLayerUsageStats = mWifiLinkLayerUsageStats;

            /**
             * Convert the SparseIntArray of "Connect to Network" notification types and counts to
             * proto's repeated IntKeyVal array.
             */
            ConnectToNetworkNotificationAndActionCount[] notificationCountArray =
                    new ConnectToNetworkNotificationAndActionCount[
                            mConnectToNetworkNotificationCount.size()];
            for (int i = 0; i < mConnectToNetworkNotificationCount.size(); i++) {
                ConnectToNetworkNotificationAndActionCount keyVal =
                        new ConnectToNetworkNotificationAndActionCount();
                keyVal.notification = mConnectToNetworkNotificationCount.keyAt(i);
                keyVal.recommender =
                        ConnectToNetworkNotificationAndActionCount.RECOMMENDER_OPEN;
                keyVal.count = mConnectToNetworkNotificationCount.valueAt(i);
                notificationCountArray[i] = keyVal;
            }
            mWifiLogProto.connectToNetworkNotificationCount = notificationCountArray;

            /**
             * Convert the SparseIntArray of "Connect to Network" notification types and counts to
             * proto's repeated IntKeyVal array.
             */
            ConnectToNetworkNotificationAndActionCount[] notificationActionCountArray =
                    new ConnectToNetworkNotificationAndActionCount[
                            mConnectToNetworkNotificationActionCount.size()];
            for (int i = 0; i < mConnectToNetworkNotificationActionCount.size(); i++) {
                ConnectToNetworkNotificationAndActionCount keyVal =
                        new ConnectToNetworkNotificationAndActionCount();
                int key = mConnectToNetworkNotificationActionCount.keyAt(i);
                keyVal.notification = key / CONNECT_TO_NETWORK_NOTIFICATION_ACTION_KEY_MULTIPLIER;
                keyVal.action = key % CONNECT_TO_NETWORK_NOTIFICATION_ACTION_KEY_MULTIPLIER;
                keyVal.recommender =
                        ConnectToNetworkNotificationAndActionCount.RECOMMENDER_OPEN;
                keyVal.count = mConnectToNetworkNotificationActionCount.valueAt(i);
                notificationActionCountArray[i] = keyVal;
            }

            mWifiLogProto.installedPasspointProfileTypeForR1 =
                    convertPasspointProfilesToProto(mInstalledPasspointProfileTypeForR1);
            mWifiLogProto.installedPasspointProfileTypeForR2 =
                    convertPasspointProfilesToProto(mInstalledPasspointProfileTypeForR2);

            mWifiLogProto.connectToNetworkNotificationActionCount = notificationActionCountArray;

            mWifiLogProto.openNetworkRecommenderBlacklistSize =
                    mOpenNetworkRecommenderBlacklistSize;
            mWifiLogProto.isWifiNetworksAvailableNotificationOn =
                    mIsWifiNetworksAvailableNotificationOn;
            mWifiLogProto.numOpenNetworkRecommendationUpdates =
                    mNumOpenNetworkRecommendationUpdates;
            mWifiLogProto.numOpenNetworkConnectMessageFailedToSend =
                    mNumOpenNetworkConnectMessageFailedToSend;

            mWifiLogProto.observedHotspotR1ApsInScanHistogram =
                    makeNumConnectableNetworksBucketArray(mObservedHotspotR1ApInScanHistogram);
            mWifiLogProto.observedHotspotR2ApsInScanHistogram =
                    makeNumConnectableNetworksBucketArray(mObservedHotspotR2ApInScanHistogram);
            mWifiLogProto.observedHotspotR1EssInScanHistogram =
                    makeNumConnectableNetworksBucketArray(mObservedHotspotR1EssInScanHistogram);
            mWifiLogProto.observedHotspotR2EssInScanHistogram =
                    makeNumConnectableNetworksBucketArray(mObservedHotspotR2EssInScanHistogram);
            mWifiLogProto.observedHotspotR1ApsPerEssInScanHistogram =
                    makeNumConnectableNetworksBucketArray(
                            mObservedHotspotR1ApsPerEssInScanHistogram);
            mWifiLogProto.observedHotspotR2ApsPerEssInScanHistogram =
                    makeNumConnectableNetworksBucketArray(
                            mObservedHotspotR2ApsPerEssInScanHistogram);

            mWifiLogProto.observed80211McSupportingApsInScanHistogram =
                    makeNumConnectableNetworksBucketArray(mObserved80211mcApInScanHistogram);

            if (mSoftApEventListTethered.size() > 0) {
                mWifiLogProto.softApConnectedClientsEventsTethered =
                        mSoftApEventListTethered.toArray(
                        mWifiLogProto.softApConnectedClientsEventsTethered);
            }
            if (mSoftApEventListLocalOnly.size() > 0) {
                mWifiLogProto.softApConnectedClientsEventsLocalOnly =
                        mSoftApEventListLocalOnly.toArray(
                        mWifiLogProto.softApConnectedClientsEventsLocalOnly);
            }

            mWifiLogProto.wpsMetrics = mWpsMetrics;
            mWifiLogProto.wifiPowerStats = mWifiPowerMetrics.buildProto();
            mWifiLogProto.wifiRadioUsage = mWifiPowerMetrics.buildWifiRadioUsageProto();
            mWifiLogProto.wifiWakeStats = mWifiWakeMetrics.buildProto();
            mWifiLogProto.isMacRandomizationOn = mIsMacRandomizationOn;
            mWifiLogProto.experimentValues = mExperimentValues;
            mWifiLogProto.wifiIsUnusableEventList =
                    new WifiIsUnusableEvent[mWifiIsUnusableList.size()];
            for (int i = 0; i < mWifiIsUnusableList.size(); i++) {
                mWifiLogProto.wifiIsUnusableEventList[i] = mWifiIsUnusableList.get(i).event;
            }
            mWifiLogProto.hardwareRevision = SystemProperties.get("ro.boot.revision", "");

            // Postprocessing on WifiUsabilityStats to upload an equal number of LABEL_GOOD and
            // LABEL_BAD WifiUsabilityStats
            final int numUsabilityStats = Math.min(
                    Math.min(mWifiUsabilityStatsListBad.size(),
                            mWifiUsabilityStatsListGood.size()),
                    MAX_WIFI_USABILITY_STATS_PER_TYPE_TO_UPLOAD);
            LinkedList<WifiUsabilityStats> usabilityStatsGoodCopy =
                    new LinkedList<>(mWifiUsabilityStatsListGood);
            LinkedList<WifiUsabilityStats> usabilityStatsBadCopy =
                    new LinkedList<>(mWifiUsabilityStatsListBad);
            mWifiLogProto.wifiUsabilityStatsList = new WifiUsabilityStats[numUsabilityStats * 2];
            for (int i = 0; i < numUsabilityStats; i++) {
                mWifiLogProto.wifiUsabilityStatsList[2 * i] = usabilityStatsGoodCopy.remove(
                        mRand.nextInt(usabilityStatsGoodCopy.size()));
                mWifiLogProto.wifiUsabilityStatsList[2 * i + 1] = usabilityStatsBadCopy.remove(
                        mRand.nextInt(usabilityStatsBadCopy.size()));
            }
            mWifiLogProto.mobilityStatePnoStatsList =
                    new DeviceMobilityStatePnoScanStats[mMobilityStatePnoStatsMap.size()];
            for (int i = 0; i < mMobilityStatePnoStatsMap.size(); i++) {
                mWifiLogProto.mobilityStatePnoStatsList[i] = mMobilityStatePnoStatsMap.valueAt(i);
            }
            mWifiLogProto.wifiP2PStats = mWifiP2pMetrics.consolidateProto();
            mWifiLogProto.wifiDppLog = mDppMetrics.consolidateProto();
            mWifiLogProto.wifiConfigStoreIo = new WifiMetricsProto.WifiConfigStoreIO();
            mWifiLogProto.wifiConfigStoreIo.readDurations =
                    makeWifiConfigStoreIODurationBucketArray(mWifiConfigStoreReadDurationHistogram);
            mWifiLogProto.wifiConfigStoreIo.writeDurations =
                    makeWifiConfigStoreIODurationBucketArray(
                            mWifiConfigStoreWriteDurationHistogram);

            LinkProbeStats linkProbeStats = new LinkProbeStats();
            linkProbeStats.successRssiCounts = mLinkProbeSuccessRssiCounts.toProto();
            linkProbeStats.failureRssiCounts = mLinkProbeFailureRssiCounts.toProto();
            linkProbeStats.successLinkSpeedCounts = mLinkProbeSuccessLinkSpeedCounts.toProto();
            linkProbeStats.failureLinkSpeedCounts = mLinkProbeFailureLinkSpeedCounts.toProto();
            linkProbeStats.successSecondsSinceLastTxSuccessHistogram =
                    mLinkProbeSuccessSecondsSinceLastTxSuccessHistogram.toProto();
            linkProbeStats.failureSecondsSinceLastTxSuccessHistogram =
                    mLinkProbeFailureSecondsSinceLastTxSuccessHistogram.toProto();
            linkProbeStats.successElapsedTimeMsHistogram =
                    mLinkProbeSuccessElapsedTimeMsHistogram.toProto();
            linkProbeStats.failureReasonCounts = mLinkProbeFailureReasonCounts.toProto(
                    LinkProbeFailureReasonCount.class,
                    (reason, count) -> {
                        LinkProbeFailureReasonCount c = new LinkProbeFailureReasonCount();
                        c.failureReason = linkProbeFailureReasonToProto(reason);
                        c.count = count;
                        return c;
                    });
            linkProbeStats.experimentProbeCounts = mLinkProbeExperimentProbeCounts.toProto(
                    ExperimentProbeCounts.class,
                    (experimentId, probeCount) -> {
                        ExperimentProbeCounts c = new ExperimentProbeCounts();
                        c.experimentId = experimentId;
                        c.probeCount = probeCount;
                        return c;
                    });
            mWifiLogProto.linkProbeStats = linkProbeStats;

            mWifiLogProto.networkSelectionExperimentDecisionsList =
                    makeNetworkSelectionExperimentDecisionsList();

            mWifiNetworkRequestApiLog.networkMatchSizeHistogram =
                    mWifiNetworkRequestApiMatchSizeHistogram.toProto();
            mWifiLogProto.wifiNetworkRequestApiLog = mWifiNetworkRequestApiLog;

            mWifiNetworkSuggestionApiLog.networkListSizeHistogram =
                    mWifiNetworkSuggestionApiListSizeHistogram.toProto();
            mWifiLogProto.wifiNetworkSuggestionApiLog = mWifiNetworkSuggestionApiLog;

            mWifiLockStats.highPerfLockAcqDurationSecHistogram =
                    mWifiLockHighPerfAcqDurationSecHistogram.toProto();

            mWifiLockStats.lowLatencyLockAcqDurationSecHistogram =
                    mWifiLockLowLatencyAcqDurationSecHistogram.toProto();

            mWifiLockStats.highPerfActiveSessionDurationSecHistogram =
                    mWifiLockHighPerfActiveSessionDurationSecHistogram.toProto();

            mWifiLockStats.lowLatencyActiveSessionDurationSecHistogram =
                    mWifiLockLowLatencyActiveSessionDurationSecHistogram.toProto();

            mWifiLogProto.wifiLockStats = mWifiLockStats;
            mWifiLogProto.wifiToggleStats = mWifiToggleStats;

            /**
             * Convert the SparseIntArray of passpoint provision failure code
             * and counts to the proto's repeated IntKeyVal array.
             */
            mWifiLogProto.passpointProvisionStats = new PasspointProvisionStats();
            mWifiLogProto.passpointProvisionStats.numProvisionSuccess = mNumProvisionSuccess;
            mWifiLogProto.passpointProvisionStats.provisionFailureCount =
                    mPasspointProvisionFailureCounts.toProto(ProvisionFailureCount.class,
                            (key, count) -> {
                                ProvisionFailureCount entry = new ProvisionFailureCount();
                                entry.failureCode = key;
                                entry.count = count;
                                return entry;
                            });
        }
    }

    private static int linkProbeFailureReasonToProto(@WifiNative.SendMgmtFrameError int reason) {
        switch (reason) {
            case WifiNative.SEND_MGMT_FRAME_ERROR_MCS_UNSUPPORTED:
                return LinkProbeStats.LINK_PROBE_FAILURE_REASON_MCS_UNSUPPORTED;
            case WifiNative.SEND_MGMT_FRAME_ERROR_NO_ACK:
                return LinkProbeStats.LINK_PROBE_FAILURE_REASON_NO_ACK;
            case WifiNative.SEND_MGMT_FRAME_ERROR_TIMEOUT:
                return LinkProbeStats.LINK_PROBE_FAILURE_REASON_TIMEOUT;
            case WifiNative.SEND_MGMT_FRAME_ERROR_ALREADY_STARTED:
                return LinkProbeStats.LINK_PROBE_FAILURE_REASON_ALREADY_STARTED;
            default:
                return LinkProbeStats.LINK_PROBE_FAILURE_REASON_UNKNOWN;
        }
    }

    private NetworkSelectionExperimentDecisions[] makeNetworkSelectionExperimentDecisionsList() {
        NetworkSelectionExperimentDecisions[] results = new NetworkSelectionExperimentDecisions[
                mNetworkSelectionExperimentPairNumChoicesCounts.size()];
        int i = 0;
        for (Map.Entry<Pair<Integer, Integer>, NetworkSelectionExperimentResults> entry :
                mNetworkSelectionExperimentPairNumChoicesCounts.entrySet()) {
            NetworkSelectionExperimentDecisions result = new NetworkSelectionExperimentDecisions();
            result.experiment1Id = entry.getKey().first;
            result.experiment2Id = entry.getKey().second;
            result.sameSelectionNumChoicesCounter =
                    entry.getValue().sameSelectionNumChoicesCounter.toProto();
            result.differentSelectionNumChoicesCounter =
                    entry.getValue().differentSelectionNumChoicesCounter.toProto();
            results[i] = result;
            i++;
        }
        return results;
    }

    /** Sets the scoring experiment id to current value */
    private void consolidateScoringParams() {
        synchronized (mLock) {
            if (mScoringParams != null) {
                int experimentIdentifier = mScoringParams.getExperimentIdentifier();
                if (experimentIdentifier == 0) {
                    mWifiLogProto.scoreExperimentId = "";
                } else {
                    mWifiLogProto.scoreExperimentId = "x" + experimentIdentifier;
                }
            }
        }
    }

    private WifiMetricsProto.NumConnectableNetworksBucket[] makeNumConnectableNetworksBucketArray(
            SparseIntArray sia) {
        WifiMetricsProto.NumConnectableNetworksBucket[] array =
                new WifiMetricsProto.NumConnectableNetworksBucket[sia.size()];
        for (int i = 0; i < sia.size(); i++) {
            WifiMetricsProto.NumConnectableNetworksBucket keyVal =
                    new WifiMetricsProto.NumConnectableNetworksBucket();
            keyVal.numConnectableNetworks = sia.keyAt(i);
            keyVal.count = sia.valueAt(i);
            array[i] = keyVal;
        }
        return array;
    }

    private WifiMetricsProto.WifiConfigStoreIO.DurationBucket[]
            makeWifiConfigStoreIODurationBucketArray(SparseIntArray sia) {
        MetricsUtils.GenericBucket[] genericBuckets =
                MetricsUtils.linearHistogramToGenericBuckets(sia,
                        WIFI_CONFIG_STORE_IO_DURATION_BUCKET_RANGES_MS);
        WifiMetricsProto.WifiConfigStoreIO.DurationBucket[] array =
                new WifiMetricsProto.WifiConfigStoreIO.DurationBucket[genericBuckets.length];
        try {
            for (int i = 0; i < genericBuckets.length; i++) {
                array[i] = new WifiMetricsProto.WifiConfigStoreIO.DurationBucket();
                array[i].rangeStartMs = toIntExact(genericBuckets[i].start);
                array[i].rangeEndMs = toIntExact(genericBuckets[i].end);
                array[i].count = genericBuckets[i].count;
            }
        } catch (ArithmeticException e) {
            // Return empty array on any overflow errors.
            array = new WifiMetricsProto.WifiConfigStoreIO.DurationBucket[0];
        }
        return array;
    }

    /**
     * Clear all WifiMetrics, except for currentConnectionEvent and Open Network Notification
     * feature enabled state, blacklist size.
     */
    private void clear() {
        synchronized (mLock) {
            loadSettings();
            mConnectionEventList.clear();
            if (mCurrentConnectionEvent != null) {
                mConnectionEventList.add(mCurrentConnectionEvent);
            }
            mScanReturnEntries.clear();
            mWifiSystemStateEntries.clear();
            mRecordStartTimeSec = mClock.getElapsedSinceBootMillis() / 1000;
            mRssiPollCountsMap.clear();
            mRssiDeltaCounts.clear();
            mLinkSpeedCounts.clear();
            mWifiAlertReasonCounts.clear();
            mWifiScoreCounts.clear();
            mWifiUsabilityScoreCounts.clear();
            mWifiLogProto.clear();
            mScanResultRssiTimestampMillis = -1;
            mSoftApManagerReturnCodeCounts.clear();
            mStaEventList.clear();
            mWifiAwareMetrics.clear();
            mRttMetrics.clear();
            mTotalSsidsInScanHistogram.clear();
            mTotalBssidsInScanHistogram.clear();
            mAvailableOpenSsidsInScanHistogram.clear();
            mAvailableOpenBssidsInScanHistogram.clear();
            mAvailableSavedSsidsInScanHistogram.clear();
            mAvailableSavedBssidsInScanHistogram.clear();
            mAvailableOpenOrSavedSsidsInScanHistogram.clear();
            mAvailableOpenOrSavedBssidsInScanHistogram.clear();
            mAvailableSavedPasspointProviderProfilesInScanHistogram.clear();
            mAvailableSavedPasspointProviderBssidsInScanHistogram.clear();
            mPnoScanMetrics.clear();
            mWifiLinkLayerUsageStats.clear();
            mConnectToNetworkNotificationCount.clear();
            mConnectToNetworkNotificationActionCount.clear();
            mNumOpenNetworkRecommendationUpdates = 0;
            mNumOpenNetworkConnectMessageFailedToSend = 0;
            mObservedHotspotR1ApInScanHistogram.clear();
            mObservedHotspotR2ApInScanHistogram.clear();
            mObservedHotspotR1EssInScanHistogram.clear();
            mObservedHotspotR2EssInScanHistogram.clear();
            mObservedHotspotR1ApsPerEssInScanHistogram.clear();
            mObservedHotspotR2ApsPerEssInScanHistogram.clear();
            mSoftApEventListTethered.clear();
            mSoftApEventListLocalOnly.clear();
            mWpsMetrics.clear();
            mWifiWakeMetrics.clear();
            mObserved80211mcApInScanHistogram.clear();
            mWifiIsUnusableList.clear();
            mInstalledPasspointProfileTypeForR1.clear();
            mInstalledPasspointProfileTypeForR2.clear();
            mWifiUsabilityStatsListGood.clear();
            mWifiUsabilityStatsListBad.clear();
            mWifiUsabilityStatsEntriesList.clear();
            mMobilityStatePnoStatsMap.clear();
            mWifiP2pMetrics.clear();
            mDppMetrics.clear();
            mWifiUsabilityStatsCounter = 0;
            mLastBssid = null;
            mLastFrequency = -1;
            mSeqNumInsideFramework = 0;
            mLastWifiUsabilityScore = -1;
            mLastWifiUsabilityScoreNoReset = -1;
            mLastPredictionHorizonSec = -1;
            mLastPredictionHorizonSecNoReset = -1;
            mSeqNumToFramework = -1;
            mProbeStatusSinceLastUpdate =
                    android.net.wifi.WifiUsabilityStatsEntry.PROBE_STATUS_NO_PROBE;
            mProbeElapsedTimeSinceLastUpdateMs = -1;
            mProbeMcsRateSinceLastUpdate = -1;
            mScoreBreachLowTimeMillis = -1;
            mWifiConfigStoreReadDurationHistogram.clear();
            mWifiConfigStoreWriteDurationHistogram.clear();
            mLinkProbeSuccessRssiCounts.clear();
            mLinkProbeFailureRssiCounts.clear();
            mLinkProbeSuccessLinkSpeedCounts.clear();
            mLinkProbeFailureLinkSpeedCounts.clear();
            mLinkProbeSuccessSecondsSinceLastTxSuccessHistogram.clear();
            mLinkProbeFailureSecondsSinceLastTxSuccessHistogram.clear();
            mLinkProbeSuccessElapsedTimeMsHistogram.clear();
            mLinkProbeFailureReasonCounts.clear();
            mLinkProbeExperimentProbeCounts.clear();
            mLinkProbeStaEventCount = 0;
            mNetworkSelectionExperimentPairNumChoicesCounts.clear();
            mWifiNetworkSuggestionApiLog.clear();
            mWifiNetworkSuggestionApiLog.clear();
            mWifiNetworkRequestApiMatchSizeHistogram.clear();
            mWifiNetworkSuggestionApiListSizeHistogram.clear();
            mWifiLockHighPerfAcqDurationSecHistogram.clear();
            mWifiLockLowLatencyAcqDurationSecHistogram.clear();
            mWifiLockHighPerfActiveSessionDurationSecHistogram.clear();
            mWifiLockLowLatencyActiveSessionDurationSecHistogram.clear();
            mWifiLockStats.clear();
            mWifiToggleStats.clear();
            mPasspointProvisionFailureCounts.clear();
            mNumProvisionSuccess = 0;
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
            mWifiWins = (wifiState == WifiMetricsProto.WifiLog.WIFI_ASSOCIATED);
            mWifiWinsUsabilityScore = (wifiState == WifiMetricsProto.WifiLog.WIFI_ASSOCIATED);
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
                switch (msg.arg1) {
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
            case ClientModeImpl.CMD_ASSOCIATED_BSSID:
                event.type = StaEvent.TYPE_CMD_ASSOCIATED_BSSID;
                break;
            case ClientModeImpl.CMD_TARGET_BSSID:
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
     * Log a StaEvent from ClientModeImpl. The StaEvent must not be one of the supplicant
     * generated event types, which are logged through 'sendMessage'
     * @param type StaEvent.EventType describing the event
     */
    public void logStaEvent(int type) {
        logStaEvent(type, StaEvent.DISCONNECT_UNKNOWN, null);
    }
    /**
     * Log a StaEvent from ClientModeImpl. The StaEvent must not be one of the supplicant
     * generated event types, which are logged through 'sendMessage'
     * @param type StaEvent.EventType describing the event
     * @param config WifiConfiguration for a framework initiated connection attempt
     */
    public void logStaEvent(int type, WifiConfiguration config) {
        logStaEvent(type, StaEvent.DISCONNECT_UNKNOWN, config);
    }
    /**
     * Log a StaEvent from ClientModeImpl. The StaEvent must not be one of the supplicant
     * generated event types, which are logged through 'sendMessage'
     * @param type StaEvent.EventType describing the event
     * @param frameworkDisconnectReason StaEvent.FrameworkDisconnectReason explaining why framework
     *                                  initiated a FRAMEWORK_DISCONNECT
     */
    public void logStaEvent(int type, int frameworkDisconnectReason) {
        logStaEvent(type, frameworkDisconnectReason, null);
    }
    /**
     * Log a StaEvent from ClientModeImpl. The StaEvent must not be one of the supplicant
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
            case StaEvent.TYPE_SCORE_BREACH:
            case StaEvent.TYPE_MAC_CHANGE:
            case StaEvent.TYPE_WIFI_ENABLED:
            case StaEvent.TYPE_WIFI_DISABLED:
            case StaEvent.TYPE_WIFI_USABILITY_SCORE_BREACH:
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
        staEvent.lastScore = mLastScore;
        staEvent.lastWifiUsabilityScore = mLastWifiUsabilityScore;
        staEvent.lastPredictionHorizonSec = mLastPredictionHorizonSec;
        mSupplicantStateChangeBitmask = 0;
        mLastPollRssi = -127;
        mLastPollFreq = -1;
        mLastPollLinkSpeed = -1;
        mLastScore = -1;
        mLastWifiUsabilityScore = -1;
        mLastPredictionHorizonSec = -1;
        mStaEventList.add(new StaEventWithTime(staEvent, mClock.getWallClockMillis()));
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

    public WifiAwareMetrics getWifiAwareMetrics() {
        return mWifiAwareMetrics;
    }

    public WifiWakeMetrics getWakeupMetrics() {
        return mWifiWakeMetrics;
    }

    public RttMetrics getRttMetrics() {
        return mRttMetrics;
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
        sb.append("supplicantStateChangeEvents: {");
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
        sb.append(" }");
        return sb.toString();
    }

    /**
     * Returns a human readable string from a Sta Event. Only adds information relevant to the event
     * type.
     */
    public static String staEventToString(StaEvent event) {
        if (event == null) return "<NULL>";
        StringBuilder sb = new StringBuilder();
        switch (event.type) {
            case StaEvent.TYPE_ASSOCIATION_REJECTION_EVENT:
                sb.append("ASSOCIATION_REJECTION_EVENT")
                        .append(" timedOut=").append(event.associationTimedOut)
                        .append(" status=").append(event.status).append(":")
                        .append(ISupplicantStaIfaceCallback.StatusCode.toString(event.status));
                break;
            case StaEvent.TYPE_AUTHENTICATION_FAILURE_EVENT:
                sb.append("AUTHENTICATION_FAILURE_EVENT reason=").append(event.authFailureReason)
                        .append(":").append(authFailureReasonToString(event.authFailureReason));
                break;
            case StaEvent.TYPE_NETWORK_CONNECTION_EVENT:
                sb.append("NETWORK_CONNECTION_EVENT");
                break;
            case StaEvent.TYPE_NETWORK_DISCONNECTION_EVENT:
                sb.append("NETWORK_DISCONNECTION_EVENT")
                        .append(" local_gen=").append(event.localGen)
                        .append(" reason=").append(event.reason).append(":")
                        .append(ISupplicantStaIfaceCallback.ReasonCode.toString(
                                (event.reason >= 0 ? event.reason : -1 * event.reason)));
                break;
            case StaEvent.TYPE_CMD_ASSOCIATED_BSSID:
                sb.append("CMD_ASSOCIATED_BSSID");
                break;
            case StaEvent.TYPE_CMD_IP_CONFIGURATION_SUCCESSFUL:
                sb.append("CMD_IP_CONFIGURATION_SUCCESSFUL");
                break;
            case StaEvent.TYPE_CMD_IP_CONFIGURATION_LOST:
                sb.append("CMD_IP_CONFIGURATION_LOST");
                break;
            case StaEvent.TYPE_CMD_IP_REACHABILITY_LOST:
                sb.append("CMD_IP_REACHABILITY_LOST");
                break;
            case StaEvent.TYPE_CMD_TARGET_BSSID:
                sb.append("CMD_TARGET_BSSID");
                break;
            case StaEvent.TYPE_CMD_START_CONNECT:
                sb.append("CMD_START_CONNECT");
                break;
            case StaEvent.TYPE_CMD_START_ROAM:
                sb.append("CMD_START_ROAM");
                break;
            case StaEvent.TYPE_CONNECT_NETWORK:
                sb.append("CONNECT_NETWORK");
                break;
            case StaEvent.TYPE_NETWORK_AGENT_VALID_NETWORK:
                sb.append("NETWORK_AGENT_VALID_NETWORK");
                break;
            case StaEvent.TYPE_FRAMEWORK_DISCONNECT:
                sb.append("FRAMEWORK_DISCONNECT")
                        .append(" reason=")
                        .append(frameworkDisconnectReasonToString(event.frameworkDisconnectReason));
                break;
            case StaEvent.TYPE_SCORE_BREACH:
                sb.append("SCORE_BREACH");
                break;
            case StaEvent.TYPE_MAC_CHANGE:
                sb.append("MAC_CHANGE");
                break;
            case StaEvent.TYPE_WIFI_ENABLED:
                sb.append("WIFI_ENABLED");
                break;
            case StaEvent.TYPE_WIFI_DISABLED:
                sb.append("WIFI_DISABLED");
                break;
            case StaEvent.TYPE_WIFI_USABILITY_SCORE_BREACH:
                sb.append("WIFI_USABILITY_SCORE_BREACH");
                break;
            case StaEvent.TYPE_LINK_PROBE:
                sb.append("LINK_PROBE");
                sb.append(" linkProbeWasSuccess=").append(event.linkProbeWasSuccess);
                if (event.linkProbeWasSuccess) {
                    sb.append(" linkProbeSuccessElapsedTimeMs=")
                            .append(event.linkProbeSuccessElapsedTimeMs);
                } else {
                    sb.append(" linkProbeFailureReason=").append(event.linkProbeFailureReason);
                }
                break;
            default:
                sb.append("UNKNOWN " + event.type + ":");
                break;
        }
        if (event.lastRssi != -127) sb.append(" lastRssi=").append(event.lastRssi);
        if (event.lastFreq != -1) sb.append(" lastFreq=").append(event.lastFreq);
        if (event.lastLinkSpeed != -1) sb.append(" lastLinkSpeed=").append(event.lastLinkSpeed);
        if (event.lastScore != -1) sb.append(" lastScore=").append(event.lastScore);
        if (event.lastWifiUsabilityScore != -1) {
            sb.append(" lastWifiUsabilityScore=").append(event.lastWifiUsabilityScore);
            sb.append(" lastPredictionHorizonSec=").append(event.lastPredictionHorizonSec);
        }
        if (event.supplicantStateChangesBitmask != 0) {
            sb.append(", ").append(supplicantStateChangesBitmaskToString(
                    event.supplicantStateChangesBitmask));
        }
        if (event.configInfo != null) {
            sb.append(", ").append(configInfoToString(event.configInfo));
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
    private void incrementSsid(SparseIntArray sia, int element) {
        increment(sia, Math.min(element, MAX_CONNECTABLE_SSID_NETWORK_BUCKET));
    }
    private void incrementBssid(SparseIntArray sia, int element) {
        increment(sia, Math.min(element, MAX_CONNECTABLE_BSSID_NETWORK_BUCKET));
    }
    private void incrementTotalScanResults(SparseIntArray sia, int element) {
        increment(sia, Math.min(element, MAX_TOTAL_SCAN_RESULTS_BUCKET));
    }
    private void incrementTotalScanSsids(SparseIntArray sia, int element) {
        increment(sia, Math.min(element, MAX_TOTAL_SCAN_RESULT_SSIDS_BUCKET));
    }
    private void incrementTotalPasspointAps(SparseIntArray sia, int element) {
        increment(sia, Math.min(element, MAX_TOTAL_PASSPOINT_APS_BUCKET));
    }
    private void incrementTotalUniquePasspointEss(SparseIntArray sia, int element) {
        increment(sia, Math.min(element, MAX_TOTAL_PASSPOINT_UNIQUE_ESS_BUCKET));
    }
    private void incrementPasspointPerUniqueEss(SparseIntArray sia, int element) {
        increment(sia, Math.min(element, MAX_PASSPOINT_APS_PER_UNIQUE_ESS_BUCKET));
    }
    private void increment80211mcAps(SparseIntArray sia, int element) {
        increment(sia, Math.min(element, MAX_TOTAL_80211MC_APS_BUCKET));
    }
    private void increment(SparseIntArray sia, int element) {
        int count = sia.get(element);
        sia.put(element, count + 1);
    }

    private static class StaEventWithTime {
        public StaEvent staEvent;
        public long wallClockMillis;

        StaEventWithTime(StaEvent event, long wallClockMillis) {
            staEvent = event;
            this.wallClockMillis = wallClockMillis;
        }

        public String toString() {
            StringBuilder sb = new StringBuilder();
            Calendar c = Calendar.getInstance();
            c.setTimeInMillis(wallClockMillis);
            if (wallClockMillis != 0) {
                sb.append(String.format("%tm-%td %tH:%tM:%tS.%tL", c, c, c, c, c, c));
            } else {
                sb.append("                  ");
            }
            sb.append(" ").append(staEventToString(staEvent));
            return sb.toString();
        }
    }

    private LinkedList<WifiIsUnusableWithTime> mWifiIsUnusableList =
            new LinkedList<WifiIsUnusableWithTime>();
    private long mTxScucessDelta = 0;
    private long mTxRetriesDelta = 0;
    private long mTxBadDelta = 0;
    private long mRxSuccessDelta = 0;
    private long mLlStatsUpdateTimeDelta = 0;
    private long mLlStatsLastUpdateTime = 0;
    private int mLastScoreNoReset = -1;
    private long mLastDataStallTime = Long.MIN_VALUE;

    private static class WifiIsUnusableWithTime {
        public WifiIsUnusableEvent event;
        public long wallClockMillis;

        WifiIsUnusableWithTime(WifiIsUnusableEvent event, long wallClockMillis) {
            this.event = event;
            this.wallClockMillis = wallClockMillis;
        }

        public String toString() {
            if (event == null) return "<NULL>";
            StringBuilder sb = new StringBuilder();
            if (wallClockMillis != 0) {
                Calendar c = Calendar.getInstance();
                c.setTimeInMillis(wallClockMillis);
                sb.append(String.format("%tm-%td %tH:%tM:%tS.%tL", c, c, c, c, c, c));
            } else {
                sb.append("                  ");
            }
            sb.append(" ");

            switch(event.type) {
                case WifiIsUnusableEvent.TYPE_DATA_STALL_BAD_TX:
                    sb.append("DATA_STALL_BAD_TX");
                    break;
                case WifiIsUnusableEvent.TYPE_DATA_STALL_TX_WITHOUT_RX:
                    sb.append("DATA_STALL_TX_WITHOUT_RX");
                    break;
                case WifiIsUnusableEvent.TYPE_DATA_STALL_BOTH:
                    sb.append("DATA_STALL_BOTH");
                    break;
                case WifiIsUnusableEvent.TYPE_FIRMWARE_ALERT:
                    sb.append("FIRMWARE_ALERT");
                    break;
                default:
                    sb.append("UNKNOWN " + event.type);
                    break;
            }

            sb.append(" lastScore=").append(event.lastScore);
            sb.append(" txSuccessDelta=").append(event.txSuccessDelta);
            sb.append(" txRetriesDelta=").append(event.txRetriesDelta);
            sb.append(" txBadDelta=").append(event.txBadDelta);
            sb.append(" rxSuccessDelta=").append(event.rxSuccessDelta);
            sb.append(" packetUpdateTimeDelta=").append(event.packetUpdateTimeDelta)
                    .append("ms");
            if (event.firmwareAlertCode != -1) {
                sb.append(" firmwareAlertCode=").append(event.firmwareAlertCode);
            }
            sb.append(" lastWifiUsabilityScore=").append(event.lastWifiUsabilityScore);
            sb.append(" lastPredictionHorizonSec=").append(event.lastPredictionHorizonSec);
            return sb.toString();
        }
    }

    /**
     * Update the difference between the last two WifiLinkLayerStats for WifiIsUnusableEvent
     */
    public void updateWifiIsUnusableLinkLayerStats(long txSuccessDelta, long txRetriesDelta,
            long txBadDelta, long rxSuccessDelta, long updateTimeDelta) {
        mTxScucessDelta = txSuccessDelta;
        mTxRetriesDelta = txRetriesDelta;
        mTxBadDelta = txBadDelta;
        mRxSuccessDelta = rxSuccessDelta;
        mLlStatsUpdateTimeDelta = updateTimeDelta;
        mLlStatsLastUpdateTime = mClock.getElapsedSinceBootMillis();
    }

    /**
     * Clear the saved difference between the last two WifiLinkLayerStats
     */
    public void resetWifiIsUnusableLinkLayerStats() {
        mTxScucessDelta = 0;
        mTxRetriesDelta = 0;
        mTxBadDelta = 0;
        mRxSuccessDelta = 0;
        mLlStatsUpdateTimeDelta = 0;
        mLlStatsLastUpdateTime = 0;
        mLastDataStallTime = Long.MIN_VALUE;
    }

    /**
     * Log a WifiIsUnusableEvent
     * @param triggerType WifiIsUnusableEvent.type describing the event
     */
    public void logWifiIsUnusableEvent(int triggerType) {
        logWifiIsUnusableEvent(triggerType, -1);
    }

    /**
     * Log a WifiIsUnusableEvent
     * @param triggerType WifiIsUnusableEvent.type describing the event
     * @param firmwareAlertCode WifiIsUnusableEvent.firmwareAlertCode for firmware alert code
     */
    public void logWifiIsUnusableEvent(int triggerType, int firmwareAlertCode) {
        mScoreBreachLowTimeMillis = -1;
        if (!mUnusableEventLogging) {
            return;
        }

        long currentBootTime = mClock.getElapsedSinceBootMillis();
        switch (triggerType) {
            case WifiIsUnusableEvent.TYPE_DATA_STALL_BAD_TX:
            case WifiIsUnusableEvent.TYPE_DATA_STALL_TX_WITHOUT_RX:
            case WifiIsUnusableEvent.TYPE_DATA_STALL_BOTH:
                // Have a time-based throttle for generating WifiIsUnusableEvent from data stalls
                if (currentBootTime < mLastDataStallTime + MIN_DATA_STALL_WAIT_MS) {
                    return;
                }
                mLastDataStallTime = currentBootTime;
                break;
            case WifiIsUnusableEvent.TYPE_FIRMWARE_ALERT:
                break;
            case WifiIsUnusableEvent.TYPE_IP_REACHABILITY_LOST:
                break;
            default:
                Log.e(TAG, "Unknown WifiIsUnusableEvent: " + triggerType);
                return;
        }

        WifiIsUnusableEvent event = new WifiIsUnusableEvent();
        event.type = triggerType;
        if (triggerType == WifiIsUnusableEvent.TYPE_FIRMWARE_ALERT) {
            event.firmwareAlertCode = firmwareAlertCode;
        }
        event.startTimeMillis = currentBootTime;
        event.lastScore = mLastScoreNoReset;
        event.lastWifiUsabilityScore = mLastWifiUsabilityScoreNoReset;
        event.lastPredictionHorizonSec = mLastPredictionHorizonSecNoReset;
        event.txSuccessDelta = mTxScucessDelta;
        event.txRetriesDelta = mTxRetriesDelta;
        event.txBadDelta = mTxBadDelta;
        event.rxSuccessDelta = mRxSuccessDelta;
        event.packetUpdateTimeDelta = mLlStatsUpdateTimeDelta;
        event.lastLinkLayerStatsUpdateTime = mLlStatsLastUpdateTime;
        event.screenOn = mScreenOn;

        mWifiIsUnusableList.add(new WifiIsUnusableWithTime(event, mClock.getWallClockMillis()));
        if (mWifiIsUnusableList.size() > MAX_UNUSABLE_EVENTS) {
            mWifiIsUnusableList.removeFirst();
        }
    }

    /**
     * Sets whether or not WifiIsUnusableEvent is logged in metrics
     */
    @VisibleForTesting
    public void setWifiIsUnusableLoggingEnabled(boolean enabled) {
        synchronized (mLock) {
            mExperimentValues.wifiIsUnusableLoggingEnabled = enabled;
        }
    }

    /**
     * Sets whether or not LinkSpeedCounts is logged in metrics
     */
    @VisibleForTesting
    public void setLinkSpeedCountsLoggingEnabled(boolean enabled) {
        synchronized (mLock) {
            mExperimentValues.linkSpeedCountsLoggingEnabled = enabled;
        }
    }

    /**
     * Sets the minimum number of txBad to trigger a data stall
     */
    public void setWifiDataStallMinTxBad(int minTxBad) {
        synchronized (mLock) {
            mExperimentValues.wifiDataStallMinTxBad = minTxBad;
        }
    }

    /**
     * Sets the minimum number of txSuccess to trigger a data stall
     * when rxSuccess is 0
     */
    public void setWifiDataStallMinRxWithoutTx(int minTxSuccessWithoutRx) {
        synchronized (mLock) {
            mExperimentValues.wifiDataStallMinTxSuccessWithoutRx = minTxSuccessWithoutRx;
        }
    }

    /**
     * Extract data from |info| and |stats| to build a WifiUsabilityStatsEntry and then adds it
     * into an internal ring buffer.
     * @param info
     * @param stats
     */
    public void updateWifiUsabilityStatsEntries(WifiInfo info, WifiLinkLayerStats stats) {
        synchronized (mLock) {
            if (info == null || stats == null) {
                return;
            }
            WifiUsabilityStatsEntry wifiUsabilityStatsEntry =
                    mWifiUsabilityStatsEntriesList.size()
                    < MAX_WIFI_USABILITY_STATS_ENTRIES_LIST_SIZE
                    ? new WifiUsabilityStatsEntry() : mWifiUsabilityStatsEntriesList.remove();
            wifiUsabilityStatsEntry.timeStampMs = stats.timeStampInMs;
            wifiUsabilityStatsEntry.totalTxSuccess = stats.txmpdu_be + stats.txmpdu_bk
                    + stats.txmpdu_vi + stats.txmpdu_vo;
            wifiUsabilityStatsEntry.totalTxRetries = stats.retries_be + stats.retries_bk
                    + stats.retries_vi + stats.retries_vo;
            wifiUsabilityStatsEntry.totalTxBad = stats.lostmpdu_be + stats.lostmpdu_bk
                    + stats.lostmpdu_vi + stats.lostmpdu_vo;
            wifiUsabilityStatsEntry.totalRxSuccess = stats.rxmpdu_be + stats.rxmpdu_bk
                    + stats.rxmpdu_vi + stats.rxmpdu_vo;
            wifiUsabilityStatsEntry.totalRadioOnTimeMs = stats.on_time;
            wifiUsabilityStatsEntry.totalRadioTxTimeMs = stats.tx_time;
            wifiUsabilityStatsEntry.totalRadioRxTimeMs = stats.rx_time;
            wifiUsabilityStatsEntry.totalScanTimeMs = stats.on_time_scan;
            wifiUsabilityStatsEntry.totalNanScanTimeMs = stats.on_time_nan_scan;
            wifiUsabilityStatsEntry.totalBackgroundScanTimeMs = stats.on_time_background_scan;
            wifiUsabilityStatsEntry.totalRoamScanTimeMs = stats.on_time_roam_scan;
            wifiUsabilityStatsEntry.totalPnoScanTimeMs = stats.on_time_pno_scan;
            wifiUsabilityStatsEntry.totalHotspot2ScanTimeMs = stats.on_time_hs20_scan;
            wifiUsabilityStatsEntry.rssi = info.getRssi();
            wifiUsabilityStatsEntry.linkSpeedMbps = info.getLinkSpeed();
            WifiLinkLayerStats.ChannelStats statsMap =
                    stats.channelStatsMap.get(info.getFrequency());
            if (statsMap != null) {
                wifiUsabilityStatsEntry.totalRadioOnFreqTimeMs = statsMap.radioOnTimeMs;
                wifiUsabilityStatsEntry.totalCcaBusyFreqTimeMs = statsMap.ccaBusyTimeMs;
            }
            wifiUsabilityStatsEntry.totalBeaconRx = stats.beacon_rx;

            boolean isSameBssidAndFreq = mLastBssid == null || mLastFrequency == -1
                    || (mLastBssid.equals(info.getBSSID())
                    && mLastFrequency == info.getFrequency());
            mLastBssid = info.getBSSID();
            mLastFrequency = info.getFrequency();
            wifiUsabilityStatsEntry.wifiScore = mLastScoreNoReset;
            wifiUsabilityStatsEntry.wifiUsabilityScore = mLastWifiUsabilityScoreNoReset;
            wifiUsabilityStatsEntry.seqNumToFramework = mSeqNumToFramework;
            wifiUsabilityStatsEntry.predictionHorizonSec = mLastPredictionHorizonSecNoReset;
            switch (mProbeStatusSinceLastUpdate) {
                case android.net.wifi.WifiUsabilityStatsEntry.PROBE_STATUS_NO_PROBE:
                    wifiUsabilityStatsEntry.probeStatusSinceLastUpdate =
                            WifiUsabilityStatsEntry.PROBE_STATUS_NO_PROBE;
                    break;
                case android.net.wifi.WifiUsabilityStatsEntry.PROBE_STATUS_SUCCESS:
                    wifiUsabilityStatsEntry.probeStatusSinceLastUpdate =
                            WifiUsabilityStatsEntry.PROBE_STATUS_SUCCESS;
                    break;
                case android.net.wifi.WifiUsabilityStatsEntry.PROBE_STATUS_FAILURE:
                    wifiUsabilityStatsEntry.probeStatusSinceLastUpdate =
                            WifiUsabilityStatsEntry.PROBE_STATUS_FAILURE;
                    break;
                default:
                    wifiUsabilityStatsEntry.probeStatusSinceLastUpdate =
                            WifiUsabilityStatsEntry.PROBE_STATUS_UNKNOWN;
                    Log.e(TAG, "Unknown link probe status: " + mProbeStatusSinceLastUpdate);
            }
            wifiUsabilityStatsEntry.probeElapsedTimeSinceLastUpdateMs =
                    mProbeElapsedTimeSinceLastUpdateMs;
            wifiUsabilityStatsEntry.probeMcsRateSinceLastUpdate = mProbeMcsRateSinceLastUpdate;
            wifiUsabilityStatsEntry.rxLinkSpeedMbps = info.getRxLinkSpeedMbps();
            wifiUsabilityStatsEntry.isSameBssidAndFreq = isSameBssidAndFreq;
            wifiUsabilityStatsEntry.seqNumInsideFramework = mSeqNumInsideFramework;
            wifiUsabilityStatsEntry.deviceMobilityState = mCurrentDeviceMobilityState;

            CellularLinkLayerStats cls = mCellularLinkLayerStatsCollector.update();
            if (DBG) Log.v(TAG, "Latest Cellular Link Layer Stats: " + cls);
            wifiUsabilityStatsEntry.cellularDataNetworkType =
                    parseDataNetworkTypeToProto(cls.getDataNetworkType());
            wifiUsabilityStatsEntry.cellularSignalStrengthDbm = cls.getSignalStrengthDbm();
            wifiUsabilityStatsEntry.cellularSignalStrengthDb = cls.getSignalStrengthDb();
            wifiUsabilityStatsEntry.isSameRegisteredCell = cls.getIsSameRegisteredCell();

            mWifiUsabilityStatsEntriesList.add(wifiUsabilityStatsEntry);
            mWifiUsabilityStatsCounter++;
            if (mWifiUsabilityStatsCounter >= NUM_WIFI_USABILITY_STATS_ENTRIES_PER_WIFI_GOOD) {
                addToWifiUsabilityStatsList(WifiUsabilityStats.LABEL_GOOD,
                        WifiUsabilityStats.TYPE_UNKNOWN, -1);
            }
            if (mScoreBreachLowTimeMillis != -1) {
                long elapsedTime =  mClock.getElapsedSinceBootMillis() - mScoreBreachLowTimeMillis;
                if (elapsedTime >= MIN_SCORE_BREACH_TO_GOOD_STATS_WAIT_TIME_MS) {
                    mScoreBreachLowTimeMillis = -1;
                    if (elapsedTime <= VALIDITY_PERIOD_OF_SCORE_BREACH_LOW_MS) {
                        addToWifiUsabilityStatsList(WifiUsabilityStats.LABEL_GOOD,
                                WifiUsabilityStats.TYPE_UNKNOWN, -1);
                    }
                }
            }

            // Invoke Wifi usability stats listener.
            sendWifiUsabilityStats(mSeqNumInsideFramework, isSameBssidAndFreq,
                    createNewWifiUsabilityStatsEntryParcelable(wifiUsabilityStatsEntry));

            mSeqNumInsideFramework++;
            mProbeStatusSinceLastUpdate =
                    android.net.wifi.WifiUsabilityStatsEntry.PROBE_STATUS_NO_PROBE;
            mProbeElapsedTimeSinceLastUpdateMs = -1;
            mProbeMcsRateSinceLastUpdate = -1;
        }
    }

    private int parseDataNetworkTypeToProto(int cellularDataNetworkType) {
        switch (cellularDataNetworkType) {
            case TelephonyManager.NETWORK_TYPE_UNKNOWN:
                return WifiUsabilityStatsEntry.NETWORK_TYPE_UNKNOWN;
            case TelephonyManager.NETWORK_TYPE_GSM:
                return WifiUsabilityStatsEntry.NETWORK_TYPE_GSM;
            case TelephonyManager.NETWORK_TYPE_CDMA:
                return WifiUsabilityStatsEntry.NETWORK_TYPE_CDMA;
            case TelephonyManager.NETWORK_TYPE_EVDO_0:
                return WifiUsabilityStatsEntry.NETWORK_TYPE_EVDO_0;
            case TelephonyManager.NETWORK_TYPE_UMTS:
                return WifiUsabilityStatsEntry.NETWORK_TYPE_UMTS;
            case TelephonyManager.NETWORK_TYPE_TD_SCDMA:
                return WifiUsabilityStatsEntry.NETWORK_TYPE_TD_SCDMA;
            case TelephonyManager.NETWORK_TYPE_LTE:
                return WifiUsabilityStatsEntry.NETWORK_TYPE_LTE;
            case TelephonyManager.NETWORK_TYPE_NR:
                return WifiUsabilityStatsEntry.NETWORK_TYPE_NR;
            default:
                Log.e(TAG, "Unknown data network type : " + cellularDataNetworkType);
                return WifiUsabilityStatsEntry.NETWORK_TYPE_UNKNOWN;
        }
    }

    private int parseDataNetworkTypeFromProto(int cellularDataNetworkType) {
        switch (cellularDataNetworkType) {
            case WifiUsabilityStatsEntry.NETWORK_TYPE_UNKNOWN:
                return TelephonyManager.NETWORK_TYPE_UNKNOWN;
            case WifiUsabilityStatsEntry.NETWORK_TYPE_GSM:
                return TelephonyManager.NETWORK_TYPE_GSM;
            case WifiUsabilityStatsEntry.NETWORK_TYPE_CDMA:
                return TelephonyManager.NETWORK_TYPE_CDMA;
            case WifiUsabilityStatsEntry.NETWORK_TYPE_EVDO_0:
                return TelephonyManager.NETWORK_TYPE_EVDO_0;
            case WifiUsabilityStatsEntry.NETWORK_TYPE_UMTS:
                return TelephonyManager.NETWORK_TYPE_UMTS;
            case WifiUsabilityStatsEntry.NETWORK_TYPE_TD_SCDMA:
                return TelephonyManager.NETWORK_TYPE_TD_SCDMA;
            case WifiUsabilityStatsEntry.NETWORK_TYPE_LTE:
                return TelephonyManager.NETWORK_TYPE_LTE;
            case WifiUsabilityStatsEntry.NETWORK_TYPE_NR:
                return TelephonyManager.NETWORK_TYPE_NR;
            default:
                Log.e(TAG, "Unknown data network type : " + cellularDataNetworkType);
                return TelephonyManager.NETWORK_TYPE_UNKNOWN;
        }
    }
    /**
     * Send Wifi usability stats.
     * @param seqNum
     * @param isSameBssidAndFreq
     * @param statsEntry
     */
    private void sendWifiUsabilityStats(int seqNum, boolean isSameBssidAndFreq,
            android.net.wifi.WifiUsabilityStatsEntry statsEntry) {
        for (IOnWifiUsabilityStatsListener listener : mOnWifiUsabilityListeners.getCallbacks()) {
            try {
                listener.onWifiUsabilityStats(seqNum, isSameBssidAndFreq, statsEntry);
            } catch (RemoteException e) {
                Log.e(TAG, "Unable to invoke Wifi usability stats entry listener "
                        + listener, e);
            }
        }
    }

    private android.net.wifi.WifiUsabilityStatsEntry createNewWifiUsabilityStatsEntryParcelable(
            WifiUsabilityStatsEntry s) {
        int probeStatus;
        switch (s.probeStatusSinceLastUpdate) {
            case WifiUsabilityStatsEntry.PROBE_STATUS_NO_PROBE:
                probeStatus = android.net.wifi.WifiUsabilityStatsEntry.PROBE_STATUS_NO_PROBE;
                break;
            case WifiUsabilityStatsEntry.PROBE_STATUS_SUCCESS:
                probeStatus = android.net.wifi.WifiUsabilityStatsEntry.PROBE_STATUS_SUCCESS;
                break;
            case WifiUsabilityStatsEntry.PROBE_STATUS_FAILURE:
                probeStatus = android.net.wifi.WifiUsabilityStatsEntry.PROBE_STATUS_FAILURE;
                break;
            default:
                probeStatus = android.net.wifi.WifiUsabilityStatsEntry.PROBE_STATUS_UNKNOWN;
                Log.e(TAG, "Unknown link probe status: " + s.probeStatusSinceLastUpdate);
        }
        int cellularDataNetworkType = parseDataNetworkTypeFromProto(s.cellularDataNetworkType);
        return new android.net.wifi.WifiUsabilityStatsEntry(s.timeStampMs, s.rssi,
                s.linkSpeedMbps, s.totalTxSuccess, s.totalTxRetries,
                s.totalTxBad, s.totalRxSuccess, s.totalRadioOnTimeMs,
                s.totalRadioTxTimeMs, s.totalRadioRxTimeMs, s.totalScanTimeMs,
                s.totalNanScanTimeMs, s.totalBackgroundScanTimeMs, s.totalRoamScanTimeMs,
                s.totalPnoScanTimeMs, s.totalHotspot2ScanTimeMs, s.totalCcaBusyFreqTimeMs,
                s.totalRadioOnFreqTimeMs, s.totalBeaconRx, probeStatus,
                s.probeElapsedTimeSinceLastUpdateMs, s.probeMcsRateSinceLastUpdate,
                s.rxLinkSpeedMbps, cellularDataNetworkType,
                s.cellularSignalStrengthDbm, s.cellularSignalStrengthDb,
                s.isSameRegisteredCell
        );
    }

    private WifiUsabilityStatsEntry createNewWifiUsabilityStatsEntry(WifiUsabilityStatsEntry s) {
        WifiUsabilityStatsEntry out = new WifiUsabilityStatsEntry();
        out.timeStampMs = s.timeStampMs;
        out.totalTxSuccess = s.totalTxSuccess;
        out.totalTxRetries = s.totalTxRetries;
        out.totalTxBad = s.totalTxBad;
        out.totalRxSuccess = s.totalRxSuccess;
        out.totalRadioOnTimeMs = s.totalRadioOnTimeMs;
        out.totalRadioTxTimeMs = s.totalRadioTxTimeMs;
        out.totalRadioRxTimeMs = s.totalRadioRxTimeMs;
        out.totalScanTimeMs = s.totalScanTimeMs;
        out.totalNanScanTimeMs = s.totalNanScanTimeMs;
        out.totalBackgroundScanTimeMs = s.totalBackgroundScanTimeMs;
        out.totalRoamScanTimeMs = s.totalRoamScanTimeMs;
        out.totalPnoScanTimeMs = s.totalPnoScanTimeMs;
        out.totalHotspot2ScanTimeMs = s.totalHotspot2ScanTimeMs;
        out.rssi = s.rssi;
        out.linkSpeedMbps = s.linkSpeedMbps;
        out.totalCcaBusyFreqTimeMs = s.totalCcaBusyFreqTimeMs;
        out.totalRadioOnFreqTimeMs = s.totalRadioOnFreqTimeMs;
        out.totalBeaconRx = s.totalBeaconRx;
        out.wifiScore = s.wifiScore;
        out.wifiUsabilityScore = s.wifiUsabilityScore;
        out.seqNumToFramework = s.seqNumToFramework;
        out.predictionHorizonSec = s.predictionHorizonSec;
        out.probeStatusSinceLastUpdate = s.probeStatusSinceLastUpdate;
        out.probeElapsedTimeSinceLastUpdateMs = s.probeElapsedTimeSinceLastUpdateMs;
        out.probeMcsRateSinceLastUpdate = s.probeMcsRateSinceLastUpdate;
        out.rxLinkSpeedMbps = s.rxLinkSpeedMbps;
        out.isSameBssidAndFreq = s.isSameBssidAndFreq;
        out.seqNumInsideFramework = s.seqNumInsideFramework;
        out.cellularDataNetworkType = s.cellularDataNetworkType;
        out.cellularSignalStrengthDbm = s.cellularSignalStrengthDbm;
        out.cellularSignalStrengthDb = s.cellularSignalStrengthDb;
        out.isSameRegisteredCell = s.isSameRegisteredCell;
        out.deviceMobilityState = s.deviceMobilityState;
        return out;
    }

    private WifiUsabilityStats createWifiUsabilityStatsWithLabel(int label, int triggerType,
            int firmwareAlertCode) {
        WifiUsabilityStats wifiUsabilityStats = new WifiUsabilityStats();
        wifiUsabilityStats.label = label;
        wifiUsabilityStats.triggerType = triggerType;
        wifiUsabilityStats.firmwareAlertCode = firmwareAlertCode;
        wifiUsabilityStats.timeStampMs = mClock.getElapsedSinceBootMillis();
        wifiUsabilityStats.stats =
                new WifiUsabilityStatsEntry[mWifiUsabilityStatsEntriesList.size()];
        for (int i = 0; i < mWifiUsabilityStatsEntriesList.size(); i++) {
            wifiUsabilityStats.stats[i] =
                    createNewWifiUsabilityStatsEntry(mWifiUsabilityStatsEntriesList.get(i));
        }
        return wifiUsabilityStats;
    }

    /**
     * Label the current snapshot of WifiUsabilityStatsEntrys and save the labeled data in memory.
     * @param label WifiUsabilityStats.LABEL_GOOD or WifiUsabilityStats.LABEL_BAD
     * @param triggerType what event triggers WifiUsabilityStats
     * @param firmwareAlertCode the firmware alert code when the stats was triggered by a
     *        firmware alert
     */
    public void addToWifiUsabilityStatsList(int label, int triggerType, int firmwareAlertCode) {
        synchronized (mLock) {
            if (mWifiUsabilityStatsEntriesList.isEmpty() || !mScreenOn) {
                return;
            }
            if (label == WifiUsabilityStats.LABEL_GOOD) {
                // Only add a good event if at least |MIN_WIFI_GOOD_USABILITY_STATS_PERIOD_MS|
                // has passed.
                if (mWifiUsabilityStatsListGood.isEmpty()
                        || mWifiUsabilityStatsListGood.getLast().stats[mWifiUsabilityStatsListGood
                        .getLast().stats.length - 1].timeStampMs
                        + MIN_WIFI_GOOD_USABILITY_STATS_PERIOD_MS
                        < mWifiUsabilityStatsEntriesList.getLast().timeStampMs) {
                    while (mWifiUsabilityStatsListGood.size()
                            >= MAX_WIFI_USABILITY_STATS_LIST_SIZE_PER_TYPE) {
                        mWifiUsabilityStatsListGood.remove(
                                mRand.nextInt(mWifiUsabilityStatsListGood.size()));
                    }
                    mWifiUsabilityStatsListGood.add(
                            createWifiUsabilityStatsWithLabel(label, triggerType,
                                    firmwareAlertCode));
                }
            } else {
                // Only add a bad event if at least |MIN_DATA_STALL_WAIT_MS|
                // has passed.
                mScoreBreachLowTimeMillis = -1;
                if (mWifiUsabilityStatsListBad.isEmpty()
                        || (mWifiUsabilityStatsListBad.getLast().stats[mWifiUsabilityStatsListBad
                        .getLast().stats.length - 1].timeStampMs
                        + MIN_DATA_STALL_WAIT_MS
                        < mWifiUsabilityStatsEntriesList.getLast().timeStampMs)) {
                    while (mWifiUsabilityStatsListBad.size()
                            >= MAX_WIFI_USABILITY_STATS_LIST_SIZE_PER_TYPE) {
                        mWifiUsabilityStatsListBad.remove(
                                mRand.nextInt(mWifiUsabilityStatsListBad.size()));
                    }
                    mWifiUsabilityStatsListBad.add(
                            createWifiUsabilityStatsWithLabel(label, triggerType,
                                    firmwareAlertCode));
                }
            }
            mWifiUsabilityStatsCounter = 0;
        }
    }

    private DeviceMobilityStatePnoScanStats getOrCreateDeviceMobilityStatePnoScanStats(
            @DeviceMobilityState int deviceMobilityState) {
        DeviceMobilityStatePnoScanStats stats = mMobilityStatePnoStatsMap.get(deviceMobilityState);
        if (stats == null) {
            stats = new DeviceMobilityStatePnoScanStats();
            stats.deviceMobilityState = deviceMobilityState;
            stats.numTimesEnteredState = 0;
            stats.totalDurationMs = 0;
            stats.pnoDurationMs = 0;
            mMobilityStatePnoStatsMap.put(deviceMobilityState, stats);
        }
        return stats;
    }

    /**
     * Updates the current device mobility state's total duration. This method should be called
     * before entering a new device mobility state.
     */
    private void updateCurrentMobilityStateTotalDuration(long now) {
        DeviceMobilityStatePnoScanStats stats =
                getOrCreateDeviceMobilityStatePnoScanStats(mCurrentDeviceMobilityState);
        stats.totalDurationMs += now - mCurrentDeviceMobilityStateStartMs;
        mCurrentDeviceMobilityStateStartMs = now;
    }

    /**
     * Convert the IntCounter of passpoint profile types and counts to proto's
     * repeated IntKeyVal array.
     *
     * @param passpointProfileTypes passpoint profile types and counts.
     */
    private PasspointProfileTypeCount[] convertPasspointProfilesToProto(
                IntCounter passpointProfileTypes) {
        return passpointProfileTypes.toProto(PasspointProfileTypeCount.class, (key, count) -> {
            PasspointProfileTypeCount entry = new PasspointProfileTypeCount();
            entry.eapMethodType = key;
            entry.count = count;
            return entry;
        });
    }

    /**
     * Reports that the device entered a new mobility state.
     *
     * @param newState the new device mobility state.
     */
    public void enterDeviceMobilityState(@DeviceMobilityState int newState) {
        synchronized (mLock) {
            long now = mClock.getElapsedSinceBootMillis();
            updateCurrentMobilityStateTotalDuration(now);

            if (newState == mCurrentDeviceMobilityState) return;

            mCurrentDeviceMobilityState = newState;
            DeviceMobilityStatePnoScanStats stats =
                    getOrCreateDeviceMobilityStatePnoScanStats(mCurrentDeviceMobilityState);
            stats.numTimesEnteredState++;
        }
    }

    /**
     * Logs the start of a PNO scan.
     */
    public void logPnoScanStart() {
        synchronized (mLock) {
            long now = mClock.getElapsedSinceBootMillis();
            mCurrentDeviceMobilityStatePnoScanStartMs = now;
            updateCurrentMobilityStateTotalDuration(now);
        }
    }

    /**
     * Logs the end of a PNO scan. This is attributed to the current device mobility state, as
     * logged by {@link #enterDeviceMobilityState(int)}. Thus, if the mobility state changes during
     * a PNO scan, one should call {@link #logPnoScanStop()}, {@link #enterDeviceMobilityState(int)}
     * , then {@link #logPnoScanStart()} so that the portion of PNO scan before the mobility state
     * change can be correctly attributed to the previous mobility state.
     */
    public void logPnoScanStop() {
        synchronized (mLock) {
            if (mCurrentDeviceMobilityStatePnoScanStartMs < 0) {
                Log.e(TAG, "Called WifiMetrics#logPNoScanStop() without calling "
                        + "WifiMetrics#logPnoScanStart() first!");
                return;
            }
            DeviceMobilityStatePnoScanStats stats =
                    getOrCreateDeviceMobilityStatePnoScanStats(mCurrentDeviceMobilityState);
            long now = mClock.getElapsedSinceBootMillis();
            stats.pnoDurationMs += now - mCurrentDeviceMobilityStatePnoScanStartMs;
            mCurrentDeviceMobilityStatePnoScanStartMs = -1;
            updateCurrentMobilityStateTotalDuration(now);
        }
    }

    /**
     * Add a new listener for Wi-Fi usability stats handling.
     */
    public void addOnWifiUsabilityListener(IBinder binder, IOnWifiUsabilityStatsListener listener,
            int listenerIdentifier) {
        if (!mOnWifiUsabilityListeners.add(binder, listener, listenerIdentifier)) {
            Log.e(TAG, "Failed to add listener");
            return;
        }
        if (DBG) {
            Log.v(TAG, "Adding listener. Num listeners: "
                    + mOnWifiUsabilityListeners.getNumCallbacks());
        }
    }

    /**
     * Remove an existing listener for Wi-Fi usability stats handling.
     */
    public void removeOnWifiUsabilityListener(int listenerIdentifier) {
        mOnWifiUsabilityListeners.remove(listenerIdentifier);
        if (DBG) {
            Log.v(TAG, "Removing listener. Num listeners: "
                    + mOnWifiUsabilityListeners.getNumCallbacks());
        }
    }

    /**
     * Updates the Wi-Fi usability score and increments occurence of a particular Wifi usability
     * score passed in from outside framework. Scores are bounded within
     * [MIN_WIFI_USABILITY_SCORE, MAX_WIFI_USABILITY_SCORE].
     *
     * Also records events when the Wifi usability score breaches significant thresholds.
     *
     * @param seqNum Sequence number of the Wi-Fi usability score.
     * @param score The Wi-Fi usability score.
     * @param predictionHorizonSec Prediction horizon of the Wi-Fi usability score.
     */
    public void incrementWifiUsabilityScoreCount(int seqNum, int score, int predictionHorizonSec) {
        if (score < MIN_WIFI_USABILITY_SCORE || score > MAX_WIFI_USABILITY_SCORE) {
            return;
        }
        synchronized (mLock) {
            mSeqNumToFramework = seqNum;
            mLastWifiUsabilityScore = score;
            mLastWifiUsabilityScoreNoReset = score;
            mWifiUsabilityScoreCounts.put(score, mWifiUsabilityScoreCounts.get(score) + 1);
            mLastPredictionHorizonSec = predictionHorizonSec;
            mLastPredictionHorizonSecNoReset = predictionHorizonSec;

            boolean wifiWins = mWifiWinsUsabilityScore;
            if (score > LOW_WIFI_USABILITY_SCORE) {
                wifiWins = true;
            } else if (score < LOW_WIFI_USABILITY_SCORE) {
                wifiWins = false;
            }

            if (wifiWins != mWifiWinsUsabilityScore) {
                mWifiWinsUsabilityScore = wifiWins;
                StaEvent event = new StaEvent();
                event.type = StaEvent.TYPE_WIFI_USABILITY_SCORE_BREACH;
                addStaEvent(event);
                // Only record the first score breach by checking whether mScoreBreachLowTimeMillis
                // has been set to -1
                if (!wifiWins && mScoreBreachLowTimeMillis == -1) {
                    mScoreBreachLowTimeMillis = mClock.getElapsedSinceBootMillis();
                }
            }
        }
    }

    /**
     * Reports stats for a successful link probe.
     *
     * @param timeSinceLastTxSuccessMs At {@code startTimestampMs}, the number of milliseconds since
     *                                 the last Tx success (according to
     *                                 {@link WifiInfo#txSuccess}).
     * @param rssi The Rx RSSI at {@code startTimestampMs}.
     * @param linkSpeed The Tx link speed in Mbps at {@code startTimestampMs}.
     * @param elapsedTimeMs The number of milliseconds between when the command to transmit the
     *                      probe was sent to the driver and when the driver responded that the
     *                      probe was ACKed. Note: this number should be correlated with the number
     *                      of retries that the driver attempted before the probe was ACKed.
     */
    public void logLinkProbeSuccess(long timeSinceLastTxSuccessMs,
            int rssi, int linkSpeed, int elapsedTimeMs) {
        synchronized (mLock) {
            mProbeStatusSinceLastUpdate =
                    android.net.wifi.WifiUsabilityStatsEntry.PROBE_STATUS_SUCCESS;
            mProbeElapsedTimeSinceLastUpdateMs = elapsedTimeMs;

            mLinkProbeSuccessSecondsSinceLastTxSuccessHistogram.increment(
                    (int) (timeSinceLastTxSuccessMs / 1000));
            mLinkProbeSuccessRssiCounts.increment(rssi);
            mLinkProbeSuccessLinkSpeedCounts.increment(linkSpeed);
            mLinkProbeSuccessElapsedTimeMsHistogram.increment(elapsedTimeMs);

            if (mLinkProbeStaEventCount < MAX_LINK_PROBE_STA_EVENTS) {
                StaEvent event = new StaEvent();
                event.type = StaEvent.TYPE_LINK_PROBE;
                event.linkProbeWasSuccess = true;
                event.linkProbeSuccessElapsedTimeMs = elapsedTimeMs;
                addStaEvent(event);
            }
            mLinkProbeStaEventCount++;
        }
    }

    /**
     * Reports stats for an unsuccessful link probe.
     *
     * @param timeSinceLastTxSuccessMs At {@code startTimestampMs}, the number of milliseconds since
     *                                 the last Tx success (according to
     *                                 {@link WifiInfo#txSuccess}).
     * @param rssi The Rx RSSI at {@code startTimestampMs}.
     * @param linkSpeed The Tx link speed in Mbps at {@code startTimestampMs}.
     * @param reason The error code for the failure. See {@link WifiNative.SendMgmtFrameError}.
     */
    public void logLinkProbeFailure(long timeSinceLastTxSuccessMs,
            int rssi, int linkSpeed, @WifiNative.SendMgmtFrameError int reason) {
        synchronized (mLock) {
            mProbeStatusSinceLastUpdate =
                    android.net.wifi.WifiUsabilityStatsEntry.PROBE_STATUS_FAILURE;
            mProbeElapsedTimeSinceLastUpdateMs = Integer.MAX_VALUE;

            mLinkProbeFailureSecondsSinceLastTxSuccessHistogram.increment(
                    (int) (timeSinceLastTxSuccessMs / 1000));
            mLinkProbeFailureRssiCounts.increment(rssi);
            mLinkProbeFailureLinkSpeedCounts.increment(linkSpeed);
            mLinkProbeFailureReasonCounts.increment(reason);

            if (mLinkProbeStaEventCount < MAX_LINK_PROBE_STA_EVENTS) {
                StaEvent event = new StaEvent();
                event.type = StaEvent.TYPE_LINK_PROBE;
                event.linkProbeWasSuccess = false;
                event.linkProbeFailureReason = linkProbeFailureReasonToProto(reason);
                addStaEvent(event);
            }
            mLinkProbeStaEventCount++;
        }
    }

    /**
     * Increments the number of probes triggered by the experiment `experimentId`.
     */
    public void incrementLinkProbeExperimentProbeCount(String experimentId) {
        synchronized (mLock) {
            mLinkProbeExperimentProbeCounts.increment(experimentId);
        }
    }

    /**
     * Update wifi config store read duration.
     *
     * @param timeMs Time it took to complete the operation, in milliseconds
     */
    public void noteWifiConfigStoreReadDuration(int timeMs) {
        synchronized (mLock) {
            MetricsUtils.addValueToLinearHistogram(timeMs, mWifiConfigStoreReadDurationHistogram,
                    WIFI_CONFIG_STORE_IO_DURATION_BUCKET_RANGES_MS);
        }
    }

    /**
     * Update wifi config store write duration.
     *
     * @param timeMs Time it took to complete the operation, in milliseconds
     */
    public void noteWifiConfigStoreWriteDuration(int timeMs) {
        synchronized (mLock) {
            MetricsUtils.addValueToLinearHistogram(timeMs, mWifiConfigStoreWriteDurationHistogram,
                    WIFI_CONFIG_STORE_IO_DURATION_BUCKET_RANGES_MS);
        }
    }

    /**
     * Logs the decision of a network selection algorithm when compared against another network
     * selection algorithm.
     *
     * @param experiment1Id ID of one experiment
     * @param experiment2Id ID of the other experiment
     * @param isSameDecision did the 2 experiments make the same decision?
     * @param numNetworkChoices the number of non-null network choices there were, where the null
     *                          choice is not selecting any network
     */
    public void logNetworkSelectionDecision(int experiment1Id, int experiment2Id,
            boolean isSameDecision, int numNetworkChoices) {
        if (numNetworkChoices < 0) {
            Log.e(TAG, "numNetworkChoices cannot be negative!");
            return;
        }
        if (experiment1Id == experiment2Id) {
            Log.e(TAG, "comparing the same experiment id: " + experiment1Id);
            return;
        }

        Pair<Integer, Integer> key = new Pair<>(experiment1Id, experiment2Id);
        synchronized (mLock) {
            NetworkSelectionExperimentResults results =
                    mNetworkSelectionExperimentPairNumChoicesCounts
                            .computeIfAbsent(key, k -> new NetworkSelectionExperimentResults());

            IntCounter counter = isSameDecision
                    ? results.sameSelectionNumChoicesCounter
                    : results.differentSelectionNumChoicesCounter;

            counter.increment(numNetworkChoices);
        }
    }

    /** Increment number of network request API usage stats */
    public void incrementNetworkRequestApiNumRequest() {
        synchronized (mLock) {
            mWifiNetworkRequestApiLog.numRequest++;
        }
    }

    /** Add to the network request API match size histogram */
    public void incrementNetworkRequestApiMatchSizeHistogram(int matchSize) {
        synchronized (mLock) {
            mWifiNetworkRequestApiMatchSizeHistogram.increment(matchSize);
        }
    }

    /** Increment number of connection success via network request API */
    public void incrementNetworkRequestApiNumConnectSuccess() {
        synchronized (mLock) {
            mWifiNetworkRequestApiLog.numConnectSuccess++;
        }
    }

    /** Increment number of requests that bypassed user approval via network request API */
    public void incrementNetworkRequestApiNumUserApprovalBypass() {
        synchronized (mLock) {
            mWifiNetworkRequestApiLog.numUserApprovalBypass++;
        }
    }

    /** Increment number of requests that user rejected via network request API */
    public void incrementNetworkRequestApiNumUserReject() {
        synchronized (mLock) {
            mWifiNetworkRequestApiLog.numUserReject++;
        }
    }

    /** Increment number of requests from unique apps via network request API */
    public void incrementNetworkRequestApiNumApps() {
        synchronized (mLock) {
            mWifiNetworkRequestApiLog.numApps++;
        }
    }

    /** Increment number of network suggestion API modification by app stats */
    public void incrementNetworkSuggestionApiNumModification() {
        synchronized (mLock) {
            mWifiNetworkSuggestionApiLog.numModification++;
        }
    }

    /** Increment number of connection success via network suggestion API */
    public void incrementNetworkSuggestionApiNumConnectSuccess() {
        synchronized (mLock) {
            mWifiNetworkSuggestionApiLog.numConnectSuccess++;
        }
    }

    /** Increment number of connection failure via network suggestion API */
    public void incrementNetworkSuggestionApiNumConnectFailure() {
        synchronized (mLock) {
            mWifiNetworkSuggestionApiLog.numConnectFailure++;
        }
    }

    /** Clear and set the latest network suggestion API max list size histogram */
    public void noteNetworkSuggestionApiListSizeHistogram(List<Integer> listSizes) {
        synchronized (mLock) {
            mWifiNetworkSuggestionApiListSizeHistogram.clear();
            for (Integer listSize : listSizes) {
                mWifiNetworkSuggestionApiListSizeHistogram.increment(listSize);
            }
        }
    }

    /**
     * Sets the nominator for a network (i.e. which entity made the suggestion to connect)
     * @param networkId the ID of the network, from its {@link WifiConfiguration}
     * @param nominatorId the entity that made the suggestion to connect to this network,
     *                    from {@link WifiMetricsProto.ConnectionEvent.ConnectionNominator}
     */
    public void setNominatorForNetwork(int networkId, int nominatorId) {
        synchronized (mLock) {
            if (networkId == WifiConfiguration.INVALID_NETWORK_ID) return;
            mNetworkIdToNominatorId.put(networkId, nominatorId);
        }
    }

    /**
     * Sets the numeric CandidateScorer id.
     */
    public void setNetworkSelectorExperimentId(int expId) {
        synchronized (mLock) {
            mNetworkSelectorExperimentId = expId;
        }
    }

    /** Add a WifiLock acqusition session */
    public void addWifiLockAcqSession(int lockType, long duration) {
        switch (lockType) {
            case WifiManager.WIFI_MODE_FULL_HIGH_PERF:
                mWifiLockHighPerfAcqDurationSecHistogram.increment((int) (duration / 1000));
                break;

            case WifiManager.WIFI_MODE_FULL_LOW_LATENCY:
                mWifiLockLowLatencyAcqDurationSecHistogram.increment((int) (duration / 1000));
                break;

            default:
                Log.e(TAG, "addWifiLockAcqSession: Invalid lock type: " + lockType);
                break;
        }
    }

    /** Add a WifiLock active session */
    public void addWifiLockActiveSession(int lockType, long duration) {
        switch (lockType) {
            case WifiManager.WIFI_MODE_FULL_HIGH_PERF:
                mWifiLockStats.highPerfActiveTimeMs += duration;
                mWifiLockHighPerfActiveSessionDurationSecHistogram.increment(
                        (int) (duration / 1000));
                break;

            case WifiManager.WIFI_MODE_FULL_LOW_LATENCY:
                mWifiLockStats.lowLatencyActiveTimeMs += duration;
                mWifiLockLowLatencyActiveSessionDurationSecHistogram.increment(
                        (int) (duration / 1000));
                break;

            default:
                Log.e(TAG, "addWifiLockActiveSession: Invalid lock type: " + lockType);
                break;
        }
    }

    /** Increments metrics counting number of addOrUpdateNetwork calls. **/
    public void incrementNumAddOrUpdateNetworkCalls() {
        synchronized (mLock) {
            mWifiLogProto.numAddOrUpdateNetworkCalls++;
        }
    }

    /** Increments metrics counting number of enableNetwork calls. **/
    public void incrementNumEnableNetworkCalls() {
        synchronized (mLock) {
            mWifiLogProto.numEnableNetworkCalls++;
        }
    }

    /** Add to WifiToggleStats **/
    public void incrementNumWifiToggles(boolean isPrivileged, boolean enable) {
        synchronized (mLock) {
            if (isPrivileged && enable) {
                mWifiToggleStats.numToggleOnPrivileged++;
            } else if (isPrivileged && !enable) {
                mWifiToggleStats.numToggleOffPrivileged++;
            } else if (!isPrivileged && enable) {
                mWifiToggleStats.numToggleOnNormal++;
            } else {
                mWifiToggleStats.numToggleOffNormal++;
            }
        }
    }

    /**
     * Increment number of passpoint provision failure
     * @param failureCode indicates error condition
     */
    public void incrementPasspointProvisionFailure(int failureCode) {
        int provisionFailureCode;
        synchronized (mLock) {
            switch (failureCode) {
                case ProvisioningCallback.OSU_FAILURE_AP_CONNECTION:
                    provisionFailureCode = PasspointProvisionStats.OSU_FAILURE_AP_CONNECTION;
                    break;
                case ProvisioningCallback.OSU_FAILURE_SERVER_URL_INVALID:
                    provisionFailureCode = PasspointProvisionStats.OSU_FAILURE_SERVER_URL_INVALID;
                    break;
                case ProvisioningCallback.OSU_FAILURE_SERVER_CONNECTION:
                    provisionFailureCode = PasspointProvisionStats.OSU_FAILURE_SERVER_CONNECTION;
                    break;
                case ProvisioningCallback.OSU_FAILURE_SERVER_VALIDATION:
                    provisionFailureCode = PasspointProvisionStats.OSU_FAILURE_SERVER_VALIDATION;
                    break;
                case ProvisioningCallback.OSU_FAILURE_SERVICE_PROVIDER_VERIFICATION:
                    provisionFailureCode = PasspointProvisionStats
                            .OSU_FAILURE_SERVICE_PROVIDER_VERIFICATION;
                    break;
                case ProvisioningCallback.OSU_FAILURE_PROVISIONING_ABORTED:
                    provisionFailureCode = PasspointProvisionStats.OSU_FAILURE_PROVISIONING_ABORTED;
                    break;
                case ProvisioningCallback.OSU_FAILURE_PROVISIONING_NOT_AVAILABLE:
                    provisionFailureCode = PasspointProvisionStats
                            .OSU_FAILURE_PROVISIONING_NOT_AVAILABLE;
                    break;
                case ProvisioningCallback.OSU_FAILURE_INVALID_URL_FORMAT_FOR_OSU:
                    provisionFailureCode = PasspointProvisionStats
                            .OSU_FAILURE_INVALID_URL_FORMAT_FOR_OSU;
                    break;
                case ProvisioningCallback.OSU_FAILURE_UNEXPECTED_COMMAND_TYPE:
                    provisionFailureCode = PasspointProvisionStats
                            .OSU_FAILURE_UNEXPECTED_COMMAND_TYPE;
                    break;
                case ProvisioningCallback.OSU_FAILURE_UNEXPECTED_SOAP_MESSAGE_TYPE:
                    provisionFailureCode = PasspointProvisionStats
                            .OSU_FAILURE_UNEXPECTED_SOAP_MESSAGE_TYPE;
                    break;
                case ProvisioningCallback.OSU_FAILURE_SOAP_MESSAGE_EXCHANGE:
                    provisionFailureCode = PasspointProvisionStats
                            .OSU_FAILURE_SOAP_MESSAGE_EXCHANGE;
                    break;
                case ProvisioningCallback.OSU_FAILURE_START_REDIRECT_LISTENER:
                    provisionFailureCode = PasspointProvisionStats
                            .OSU_FAILURE_START_REDIRECT_LISTENER;
                    break;
                case ProvisioningCallback.OSU_FAILURE_TIMED_OUT_REDIRECT_LISTENER:
                    provisionFailureCode = PasspointProvisionStats
                            .OSU_FAILURE_TIMED_OUT_REDIRECT_LISTENER;
                    break;
                case ProvisioningCallback.OSU_FAILURE_NO_OSU_ACTIVITY_FOUND:
                    provisionFailureCode = PasspointProvisionStats
                            .OSU_FAILURE_NO_OSU_ACTIVITY_FOUND;
                    break;
                case ProvisioningCallback.OSU_FAILURE_UNEXPECTED_SOAP_MESSAGE_STATUS:
                    provisionFailureCode = PasspointProvisionStats
                            .OSU_FAILURE_UNEXPECTED_SOAP_MESSAGE_STATUS;
                    break;
                case ProvisioningCallback.OSU_FAILURE_NO_PPS_MO:
                    provisionFailureCode = PasspointProvisionStats.OSU_FAILURE_NO_PPS_MO;
                    break;
                case ProvisioningCallback.OSU_FAILURE_NO_AAA_SERVER_TRUST_ROOT_NODE:
                    provisionFailureCode = PasspointProvisionStats
                            .OSU_FAILURE_NO_AAA_SERVER_TRUST_ROOT_NODE;
                    break;
                case ProvisioningCallback.OSU_FAILURE_NO_REMEDIATION_SERVER_TRUST_ROOT_NODE:
                    provisionFailureCode = PasspointProvisionStats
                            .OSU_FAILURE_NO_REMEDIATION_SERVER_TRUST_ROOT_NODE;
                    break;
                case ProvisioningCallback.OSU_FAILURE_NO_POLICY_SERVER_TRUST_ROOT_NODE:
                    provisionFailureCode = PasspointProvisionStats
                            .OSU_FAILURE_NO_POLICY_SERVER_TRUST_ROOT_NODE;
                    break;
                case ProvisioningCallback.OSU_FAILURE_RETRIEVE_TRUST_ROOT_CERTIFICATES:
                    provisionFailureCode = PasspointProvisionStats
                            .OSU_FAILURE_RETRIEVE_TRUST_ROOT_CERTIFICATES;
                    break;
                case ProvisioningCallback.OSU_FAILURE_NO_AAA_TRUST_ROOT_CERTIFICATE:
                    provisionFailureCode = PasspointProvisionStats
                            .OSU_FAILURE_NO_AAA_TRUST_ROOT_CERTIFICATE;
                    break;
                case ProvisioningCallback.OSU_FAILURE_ADD_PASSPOINT_CONFIGURATION:
                    provisionFailureCode = PasspointProvisionStats
                            .OSU_FAILURE_ADD_PASSPOINT_CONFIGURATION;
                    break;
                case ProvisioningCallback.OSU_FAILURE_OSU_PROVIDER_NOT_FOUND:
                    provisionFailureCode = PasspointProvisionStats
                            .OSU_FAILURE_OSU_PROVIDER_NOT_FOUND;
                    break;
                default:
                    provisionFailureCode = PasspointProvisionStats.OSU_FAILURE_UNKNOWN;
            }
            mPasspointProvisionFailureCounts.increment(provisionFailureCode);
        }
    }

    /**
     * Increment number of passpoint provision success
     */
    public void incrementPasspointProvisionSuccess() {
        synchronized (mLock) {
            mNumProvisionSuccess++;
        }
    }
}
