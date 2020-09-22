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

import static com.android.internal.util.Preconditions.checkNotNull;
import static com.android.server.wifi.ClientModeImpl.WIFI_WORK_SOURCE;

import android.annotation.NonNull;
import android.app.AlarmManager;
import android.content.Context;
import android.net.MacAddress;
import android.net.wifi.ScanResult;
import android.net.wifi.SupplicantState;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiManager.DeviceMobilityState;
import android.net.wifi.WifiNetworkSuggestion;
import android.net.wifi.WifiScanner;
import android.net.wifi.WifiScanner.PnoSettings;
import android.net.wifi.WifiScanner.ScanSettings;
import android.net.wifi.hotspot2.PasspointConfiguration;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.Process;
import android.os.WorkSource;
import android.util.ArrayMap;
import android.util.LocalLog;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.wifi.util.ScanResultUtil;
import com.android.wifi.resources.R;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * This class manages all the connectivity related scanning activities.
 *
 * When the screen is turned on or off, WiFi is connected or disconnected,
 * or on-demand, a scan is initiatiated and the scan results are passed
 * to WifiNetworkSelector for it to make a recommendation on which network
 * to connect to.
 */
public class WifiConnectivityManager {
    public static final String WATCHDOG_TIMER_TAG =
            "WifiConnectivityManager Schedule Watchdog Timer";
    public static final String PERIODIC_SCAN_TIMER_TAG =
            "WifiConnectivityManager Schedule Periodic Scan Timer";
    public static final String RESTART_SINGLE_SCAN_TIMER_TAG =
            "WifiConnectivityManager Restart Single Scan";
    public static final String RESTART_CONNECTIVITY_SCAN_TIMER_TAG =
            "WifiConnectivityManager Restart Scan";
    public static final String DELAYED_PARTIAL_SCAN_TIMER_TAG =
            "WifiConnectivityManager Schedule Delayed Partial Scan Timer";

    private static final long RESET_TIME_STAMP = Long.MIN_VALUE;
    // Constants to indicate whether a scan should start immediately or
    // it should comply to the minimum scan interval rule.
    private static final boolean SCAN_IMMEDIATELY = true;
    private static final boolean SCAN_ON_SCHEDULE = false;

    // PNO scan interval in milli-seconds. This is the scan
    // performed when screen is off and connected.
    private static final int CONNECTED_PNO_SCAN_INTERVAL_MS = 160 * 1000; // 160 seconds
    // When a network is found by PNO scan but gets rejected by Wifi Network Selector due
    // to its low RSSI value, scan will be reschduled in an exponential back off manner.
    private static final int LOW_RSSI_NETWORK_RETRY_START_DELAY_MS = 20 * 1000; // 20 seconds
    private static final int LOW_RSSI_NETWORK_RETRY_MAX_DELAY_MS = 80 * 1000; // 80 seconds
    // Maximum number of retries when starting a scan failed
    @VisibleForTesting
    public static final int MAX_SCAN_RESTART_ALLOWED = 5;
    // Number of milli-seconds to delay before retry starting
    // a previously failed scan
    private static final int RESTART_SCAN_DELAY_MS = 2 * 1000; // 2 seconds
    // When in disconnected mode, a watchdog timer will be fired
    // every WATCHDOG_INTERVAL_MS to start a single scan. This is
    // to prevent caveat from things like PNO scan.
    private static final int WATCHDOG_INTERVAL_MS = 20 * 60 * 1000; // 20 minutes
    // Restricted channel list age out value.
    private static final long CHANNEL_LIST_AGE_MS = 60 * 60 * 1000; // 1 hour
    // This is the time interval for the connection attempt rate calculation. Connection attempt
    // timestamps beyond this interval is evicted from the list.
    public static final int MAX_CONNECTION_ATTEMPTS_TIME_INTERVAL_MS = 4 * 60 * 1000; // 4 mins
    // Max number of connection attempts in the above time interval.
    public static final int MAX_CONNECTION_ATTEMPTS_RATE = 6;
    private static final int TEMP_BSSID_BLOCK_DURATION = 10 * 1000; // 10 seconds
    // Maximum age of frequencies last seen to be included in pno scans. (30 days)
    private static final long MAX_PNO_SCAN_FREQUENCY_AGE_MS = (long) 1000 * 3600 * 24 * 30;
    // ClientModeImpl has a bunch of states. From the
    // WifiConnectivityManager's perspective it only cares
    // if it is in Connected state, Disconnected state or in
    // transition between these two states.
    public static final int WIFI_STATE_UNKNOWN = 0;
    public static final int WIFI_STATE_CONNECTED = 1;
    public static final int WIFI_STATE_DISCONNECTED = 2;
    public static final int WIFI_STATE_TRANSITIONING = 3;

    // Initial scan state, used to manage performing partial scans in initial scans
    // Initial scans are the first scan after enabling Wifi or turning on screen when disconnected
    private static final int INITIAL_SCAN_STATE_START = 0;
    private static final int INITIAL_SCAN_STATE_AWAITING_RESPONSE = 1;
    private static final int INITIAL_SCAN_STATE_COMPLETE = 2;

    // Log tag for this class
    private static final String TAG = "WifiConnectivityManager";
    private static final String ALL_SINGLE_SCAN_LISTENER = "AllSingleScanListener";
    private static final String PNO_SCAN_LISTENER = "PnoScanListener";

    private final Context mContext;
    private final ClientModeImpl mStateMachine;
    private final WifiInjector mWifiInjector;
    private final WifiConfigManager mConfigManager;
    private final WifiNetworkSuggestionsManager mWifiNetworkSuggestionsManager;
    private final WifiInfo mWifiInfo;
    private final WifiConnectivityHelper mConnectivityHelper;
    private final WifiNetworkSelector mNetworkSelector;
    private final WifiLastResortWatchdog mWifiLastResortWatchdog;
    private final OpenNetworkNotifier mOpenNetworkNotifier;
    private final WifiMetrics mWifiMetrics;
    private final AlarmManager mAlarmManager;
    private final Handler mEventHandler;
    private final Clock mClock;
    private final ScoringParams mScoringParams;
    private final LocalLog mLocalLog;
    private final LinkedList<Long> mConnectionAttemptTimeStamps;
    private final BssidBlocklistMonitor mBssidBlocklistMonitor;
    private WifiScanner mScanner;
    private WifiScoreCard mWifiScoreCard;

    private boolean mDbg = false;
    private boolean mVerboseLoggingEnabled = false;
    private boolean mWifiEnabled = false;
    private boolean mAutoJoinEnabled = false; // disabled by default, enabled by external triggers
    private boolean mRunning = false;
    private boolean mScreenOn = false;
    private int mWifiState = WIFI_STATE_UNKNOWN;
    private int mInitialScanState = INITIAL_SCAN_STATE_COMPLETE;
    private boolean mAutoJoinEnabledExternal = true; // enabled by default
    private boolean mUntrustedConnectionAllowed = false;
    private boolean mTrustedConnectionAllowed = false;
    private boolean mSpecificNetworkRequestInProgress = false;
    private int mScanRestartCount = 0;
    private int mSingleScanRestartCount = 0;
    private int mTotalConnectivityAttemptsRateLimited = 0;
    private String mLastConnectionAttemptBssid = null;
    private long mLastPeriodicSingleScanTimeStamp = RESET_TIME_STAMP;
    private long mLastNetworkSelectionTimeStamp = RESET_TIME_STAMP;
    private boolean mPnoScanStarted = false;
    private boolean mPeriodicScanTimerSet = false;
    private boolean mDelayedPartialScanTimerSet = false;

    // Used for Initial Scan metrics
    private boolean mFailedInitialPartialScan = false;
    private int mInitialPartialScanChannelCount;

    // Device configs
    private boolean mWaitForFullBandScanResults = false;

    // Scanning Schedules
    // Default schedule used in case of invalid configuration
    private static final int[] DEFAULT_SCANNING_SCHEDULE_SEC = {20, 40, 80, 160};
    private int[] mConnectedSingleScanScheduleSec;
    private int[] mDisconnectedSingleScanScheduleSec;
    private int[] mConnectedSingleSavedNetworkSingleScanScheduleSec;
    private List<WifiCandidates.Candidate> mLatestCandidates = null;
    private long mLatestCandidatesTimestampMs = 0;

    private final Object mLock = new Object();

    @GuardedBy("mLock")
    private int[] mCurrentSingleScanScheduleSec;

    private int mCurrentSingleScanScheduleIndex;
    private WifiChannelUtilization mWifiChannelUtilization;
    // Cached WifiCandidates used in high mobility state to avoid connecting to APs that are
    // moving relative to the user.
    private CachedWifiCandidates mCachedWifiCandidates = null;
    private @DeviceMobilityState int mDeviceMobilityState =
            WifiManager.DEVICE_MOBILITY_STATE_UNKNOWN;

    // A helper to log debugging information in the local log buffer, which can
    // be retrieved in bugreport.
    private void localLog(String log) {
        mLocalLog.log(log);
        if (mVerboseLoggingEnabled) Log.v(TAG, log);
    }

    /**
     * Enable verbose logging for WifiConnectivityManager.
     */
    public void enableVerboseLogging(boolean verbose) {
        mVerboseLoggingEnabled = verbose;
    }

    // A periodic/PNO scan will be rescheduled up to MAX_SCAN_RESTART_ALLOWED times
    // if the start scan command failed. A timer is used here to make it a deferred retry.
    private final AlarmManager.OnAlarmListener mRestartScanListener =
            new AlarmManager.OnAlarmListener() {
                public void onAlarm() {
                    startConnectivityScan(SCAN_IMMEDIATELY);
                }
            };

    // A single scan will be rescheduled up to MAX_SCAN_RESTART_ALLOWED times
    // if the start scan command failed. An timer is used here to make it a deferred retry.
    private class RestartSingleScanListener implements AlarmManager.OnAlarmListener {
        private final boolean mIsFullBandScan;

        RestartSingleScanListener(boolean isFullBandScan) {
            mIsFullBandScan = isFullBandScan;
        }

        @Override
        public void onAlarm() {
            startSingleScan(mIsFullBandScan, WIFI_WORK_SOURCE);
        }
    }

    // As a watchdog mechanism, a single scan will be scheduled every WATCHDOG_INTERVAL_MS
    // if it is in the WIFI_STATE_DISCONNECTED state.
    private final AlarmManager.OnAlarmListener mWatchdogListener =
            new AlarmManager.OnAlarmListener() {
                public void onAlarm() {
                    watchdogHandler();
                }
            };

    // Due to b/28020168, timer based single scan will be scheduled
    // to provide periodic scan in an exponential backoff fashion.
    private final AlarmManager.OnAlarmListener mPeriodicScanTimerListener =
            new AlarmManager.OnAlarmListener() {
                public void onAlarm() {
                    periodicScanTimerHandler();
                }
            };

    private final AlarmManager.OnAlarmListener mDelayedPartialScanTimerListener =
            new AlarmManager.OnAlarmListener() {
                public void onAlarm() {
                    if (mCachedWifiCandidates == null
                            || mCachedWifiCandidates.frequencies == null
                            || mCachedWifiCandidates.frequencies.size() == 0) {
                        return;
                    }
                    ScanSettings settings = new ScanSettings();
                    settings.type = WifiScanner.SCAN_TYPE_HIGH_ACCURACY;
                    settings.band = getScanBand(false);
                    settings.reportEvents = WifiScanner.REPORT_EVENT_FULL_SCAN_RESULT
                            | WifiScanner.REPORT_EVENT_AFTER_EACH_SCAN;
                    settings.numBssidsPerScan = 0;
                    int index = 0;
                    settings.channels =
                            new WifiScanner.ChannelSpec[mCachedWifiCandidates.frequencies.size()];
                    for (Integer freq : mCachedWifiCandidates.frequencies) {
                        settings.channels[index++] = new WifiScanner.ChannelSpec(freq);
                    }
                    SingleScanListener singleScanListener = new SingleScanListener(false);
                    mScanner.startScan(settings, new HandlerExecutor(mEventHandler),
                            singleScanListener, WIFI_WORK_SOURCE);
                    mWifiMetrics.incrementConnectivityOneshotScanCount();
                }
            };

    /**
     * Handles 'onResult' callbacks for the Periodic, Single & Pno ScanListener.
     * Executes selection of potential network candidates, initiation of connection attempt to that
     * network.
     *
     * @return true - if a candidate is selected by WifiNetworkSelector
     *         false - if no candidate is selected by WifiNetworkSelector
     */
    private boolean handleScanResults(List<ScanDetail> scanDetails, String listenerName,
            boolean isFullScan) {
        mWifiChannelUtilization.refreshChannelStatsAndChannelUtilization(
                mStateMachine.getWifiLinkLayerStats(), WifiChannelUtilization.UNKNOWN_FREQ);

        updateUserDisabledList(scanDetails);

        // Check if any blocklisted BSSIDs can be freed.
        Set<String> bssidBlocklist = mBssidBlocklistMonitor.updateAndGetBssidBlocklist();

        if (mStateMachine.isSupplicantTransientState()) {
            localLog(listenerName
                    + " onResults: No network selection because supplicantTransientState is "
                    + mStateMachine.isSupplicantTransientState());
            return false;
        }

        localLog(listenerName + " onResults: start network selection");

        List<WifiCandidates.Candidate> candidates = mNetworkSelector.getCandidatesFromScan(
                scanDetails, bssidBlocklist, mWifiInfo, mStateMachine.isConnected(),
                mStateMachine.isDisconnected(), mUntrustedConnectionAllowed);
        mLatestCandidates = candidates;
        mLatestCandidatesTimestampMs = mClock.getElapsedSinceBootMillis();

        if (mDeviceMobilityState == WifiManager.DEVICE_MOBILITY_STATE_HIGH_MVMT
                && mContext.getResources().getBoolean(
                        R.bool.config_wifiHighMovementNetworkSelectionOptimizationEnabled)) {
            candidates = filterCandidatesHighMovement(candidates, listenerName, isFullScan);
        }

        WifiConfiguration candidate = mNetworkSelector.selectNetwork(candidates);
        mLastNetworkSelectionTimeStamp = mClock.getElapsedSinceBootMillis();
        mWifiLastResortWatchdog.updateAvailableNetworks(
                mNetworkSelector.getConnectableScanDetails());
        mWifiMetrics.countScanResults(scanDetails);
        if (candidate != null) {
            localLog(listenerName + ":  WNS candidate-" + candidate.SSID);
            connectToNetwork(candidate);
            return true;
        } else {
            if (mWifiState == WIFI_STATE_DISCONNECTED) {
                mOpenNetworkNotifier.handleScanResults(
                        mNetworkSelector.getFilteredScanDetailsForOpenUnsavedNetworks());
            }
            return false;
        }
    }

    private List<WifiCandidates.Candidate> filterCandidatesHighMovement(
            List<WifiCandidates.Candidate> candidates, String listenerName, boolean isFullScan) {
        boolean isNotPartialScan = isFullScan || listenerName.equals(PNO_SCAN_LISTENER);
        if (candidates == null || candidates.isEmpty()) {
            // No connectable networks nearby or network selection is unnecessary
            if (isNotPartialScan) {
                mCachedWifiCandidates = new CachedWifiCandidates(mClock.getElapsedSinceBootMillis(),
                        null);
            }
            return null;
        }

        long minimumTimeBetweenScansMs = mContext.getResources().getInteger(
                R.integer.config_wifiHighMovementNetworkSelectionOptimizationScanDelayMs);
        if (mCachedWifiCandidates != null && mCachedWifiCandidates.candidateRssiMap != null) {
            // cached candidates are too recent, wait for next scan
            if (mClock.getElapsedSinceBootMillis() - mCachedWifiCandidates.timeSinceBootMs
                    < minimumTimeBetweenScansMs) {
                mWifiMetrics.incrementNumHighMovementConnectionSkipped();
                return null;
            }

            int rssiDelta = mContext.getResources().getInteger(R.integer
                    .config_wifiHighMovementNetworkSelectionOptimizationRssiDelta);
            List<WifiCandidates.Candidate> filteredCandidates = candidates.stream().filter(
                    item -> mCachedWifiCandidates.candidateRssiMap.containsKey(item.getKey())
                            && Math.abs(mCachedWifiCandidates.candidateRssiMap.get(item.getKey())
                            - item.getScanRssi()) < rssiDelta)
                    .collect(Collectors.toList());

            if (!filteredCandidates.isEmpty()) {
                if (isNotPartialScan) {
                    mCachedWifiCandidates =
                            new CachedWifiCandidates(mClock.getElapsedSinceBootMillis(),
                            candidates);
                }
                mWifiMetrics.incrementNumHighMovementConnectionStarted();
                return filteredCandidates;
            }
        }

        // Either no cached candidates, or all candidates got filtered out.
        // Update the cached candidates here and schedule a delayed partial scan.
        if (isNotPartialScan) {
            mCachedWifiCandidates = new CachedWifiCandidates(mClock.getElapsedSinceBootMillis(),
                    candidates);
            localLog("Found " + candidates.size() + " candidates at high mobility state. "
                    + "Re-doing scan to confirm network quality.");
            scheduleDelayedPartialScan(minimumTimeBetweenScansMs);
        }
        mWifiMetrics.incrementNumHighMovementConnectionSkipped();
        return null;
    }

    private void updateUserDisabledList(List<ScanDetail> scanDetails) {
        List<String> results = new ArrayList<>();
        List<ScanResult> passpointAp = new ArrayList<>();
        for (ScanDetail scanDetail : scanDetails) {
            results.add(ScanResultUtil.createQuotedSSID(scanDetail.getScanResult().SSID));
            if (!scanDetail.getScanResult().isPasspointNetwork()) {
                continue;
            }
            passpointAp.add(scanDetail.getScanResult());
        }
        if (!passpointAp.isEmpty()) {
            results.addAll(new ArrayList<>(mWifiInjector.getPasspointManager()
                    .getAllMatchingPasspointProfilesForScanResults(passpointAp).keySet()));
        }
        mConfigManager.updateUserDisabledList(results);
    }

    /**
     * Set whether bluetooth is in the connected state
     */
    public void setBluetoothConnected(boolean isBluetoothConnected) {
        mNetworkSelector.setBluetoothConnected(isBluetoothConnected);
    }

    private class CachedWifiCandidates {
        public final long timeSinceBootMs;
        public final Map<WifiCandidates.Key, Integer> candidateRssiMap;
        public final Set<Integer> frequencies;

        CachedWifiCandidates(long timeSinceBootMs, List<WifiCandidates.Candidate> candidates) {
            this.timeSinceBootMs = timeSinceBootMs;
            if (candidates == null) {
                this.candidateRssiMap = null;
                this.frequencies = null;
            } else {
                this.candidateRssiMap = new ArrayMap<WifiCandidates.Key, Integer>();
                this.frequencies = new HashSet<Integer>();
                for (WifiCandidates.Candidate c : candidates) {
                    candidateRssiMap.put(c.getKey(), c.getScanRssi());
                    frequencies.add(c.getFrequency());
                }
            }
        }
    }

    // All single scan results listener.
    //
    // Note: This is the listener for all the available single scan results,
    //       including the ones initiated by WifiConnectivityManager and
    //       other modules.
    private class AllSingleScanListener implements WifiScanner.ScanListener {
        private List<ScanDetail> mScanDetails = new ArrayList<ScanDetail>();
        private int mNumScanResultsIgnoredDueToSingleRadioChain = 0;

        public void clearScanDetails() {
            mScanDetails.clear();
            mNumScanResultsIgnoredDueToSingleRadioChain = 0;
        }

        @Override
        public void onSuccess() {
        }

        @Override
        public void onFailure(int reason, String description) {
            localLog("registerScanListener onFailure:"
                      + " reason: " + reason + " description: " + description);
        }

        @Override
        public void onPeriodChanged(int periodInMs) {
        }

        @Override
        public void onResults(WifiScanner.ScanData[] results) {
            if (!mWifiEnabled || !mAutoJoinEnabled) {
                clearScanDetails();
                mWaitForFullBandScanResults = false;
                return;
            }

            // We treat any full band scans (with DFS or not) as "full".
            boolean isFullBandScanResults = false;
            if (results != null && results.length > 0) {
                isFullBandScanResults =
                        WifiScanner.isFullBandScan(results[0].getBandScanned(), true);
            }
            // Full band scan results only.
            if (mWaitForFullBandScanResults) {
                if (!isFullBandScanResults) {
                    localLog("AllSingleScanListener waiting for full band scan results.");
                    clearScanDetails();
                    return;
                } else {
                    mWaitForFullBandScanResults = false;
                }
            }
            if (results != null && results.length > 0) {
                mWifiMetrics.incrementAvailableNetworksHistograms(mScanDetails,
                        isFullBandScanResults);
            }
            if (mNumScanResultsIgnoredDueToSingleRadioChain > 0) {
                Log.i(TAG, "Number of scan results ignored due to single radio chain scan: "
                        + mNumScanResultsIgnoredDueToSingleRadioChain);
            }
            boolean wasConnectAttempted = handleScanResults(mScanDetails,
                    ALL_SINGLE_SCAN_LISTENER, isFullBandScanResults);
            clearScanDetails();

            // Update metrics to see if a single scan detected a valid network
            // while PNO scan didn't.
            // Note: We don't update the background scan metrics any more as it is
            //       not in use.
            if (mPnoScanStarted) {
                if (wasConnectAttempted) {
                    mWifiMetrics.incrementNumConnectivityWatchdogPnoBad();
                } else {
                    mWifiMetrics.incrementNumConnectivityWatchdogPnoGood();
                }
            }

            // Check if we are in the middle of initial partial scan
            if (mInitialScanState == INITIAL_SCAN_STATE_AWAITING_RESPONSE) {
                // Done with initial scan
                setInitialScanState(INITIAL_SCAN_STATE_COMPLETE);

                if (wasConnectAttempted) {
                    Log.i(TAG, "Connection attempted with the reduced initial scans");
                    schedulePeriodicScanTimer(
                            getScheduledSingleScanIntervalMs(mCurrentSingleScanScheduleIndex));
                    mWifiMetrics.reportInitialPartialScan(mInitialPartialScanChannelCount, true);
                    mInitialPartialScanChannelCount = 0;
                } else {
                    Log.i(TAG, "Connection was not attempted, issuing a full scan");
                    startConnectivityScan(SCAN_IMMEDIATELY);
                    mFailedInitialPartialScan = true;
                }
            } else if (mInitialScanState == INITIAL_SCAN_STATE_COMPLETE) {
                if (mFailedInitialPartialScan && wasConnectAttempted) {
                    // Initial scan failed, but following full scan succeeded
                    mWifiMetrics.reportInitialPartialScan(mInitialPartialScanChannelCount, false);
                }
                mFailedInitialPartialScan = false;
                mInitialPartialScanChannelCount = 0;
            }
        }

        @Override
        public void onFullResult(ScanResult fullScanResult) {
            if (!mWifiEnabled || !mAutoJoinEnabled) {
                return;
            }

            if (mDbg) {
                localLog("AllSingleScanListener onFullResult: " + fullScanResult.SSID
                        + " capabilities " + fullScanResult.capabilities);
            }

            // When the scan result has radio chain info, ensure we throw away scan results
            // not received with both radio chains (if |mUseSingleRadioChainScanResults| is false).
            if (!mContext.getResources().getBoolean(
                    R.bool.config_wifi_framework_use_single_radio_chain_scan_results_network_selection)
                    && fullScanResult.radioChainInfos != null
                    && fullScanResult.radioChainInfos.length == 1) {
                // Keep track of the number of dropped scan results for logging.
                mNumScanResultsIgnoredDueToSingleRadioChain++;
                return;
            }

            mScanDetails.add(ScanResultUtil.toScanDetail(fullScanResult));
        }
    }

    private final AllSingleScanListener mAllSingleScanListener = new AllSingleScanListener();

    // Single scan results listener. A single scan is initiated when
    // DisconnectedPNO scan found a valid network and woke up
    // the system, or by the watchdog timer, or to form the timer based
    // periodic scan.
    //
    // Note: This is the listener for the single scans initiated by the
    //        WifiConnectivityManager.
    private class SingleScanListener implements WifiScanner.ScanListener {
        private final boolean mIsFullBandScan;

        SingleScanListener(boolean isFullBandScan) {
            mIsFullBandScan = isFullBandScan;
        }

        @Override
        public void onSuccess() {
        }

        @Override
        public void onFailure(int reason, String description) {
            localLog("SingleScanListener onFailure:"
                    + " reason: " + reason + " description: " + description);

            // reschedule the scan
            if (mSingleScanRestartCount++ < MAX_SCAN_RESTART_ALLOWED) {
                scheduleDelayedSingleScan(mIsFullBandScan);
            } else {
                mSingleScanRestartCount = 0;
                localLog("Failed to successfully start single scan for "
                        + MAX_SCAN_RESTART_ALLOWED + " times");
            }
        }

        @Override
        public void onPeriodChanged(int periodInMs) {
            localLog("SingleScanListener onPeriodChanged: "
                    + "actual scan period " + periodInMs + "ms");
        }

        @Override
        public void onResults(WifiScanner.ScanData[] results) {
            mSingleScanRestartCount = 0;
        }

        @Override
        public void onFullResult(ScanResult fullScanResult) {
        }
    }

    // PNO scan results listener for both disconnected and connected PNO scanning.
    // A PNO scan is initiated when screen is off.
    private class PnoScanListener implements WifiScanner.PnoScanListener {
        private List<ScanDetail> mScanDetails = new ArrayList<ScanDetail>();
        private int mLowRssiNetworkRetryDelay =
                LOW_RSSI_NETWORK_RETRY_START_DELAY_MS;

        public void clearScanDetails() {
            mScanDetails.clear();
        }

        // Reset to the start value when either a non-PNO scan is started or
        // WifiNetworkSelector selects a candidate from the PNO scan results.
        public void resetLowRssiNetworkRetryDelay() {
            mLowRssiNetworkRetryDelay = LOW_RSSI_NETWORK_RETRY_START_DELAY_MS;
        }

        @VisibleForTesting
        public int getLowRssiNetworkRetryDelay() {
            return mLowRssiNetworkRetryDelay;
        }

        @Override
        public void onSuccess() {
        }

        @Override
        public void onFailure(int reason, String description) {
            localLog("PnoScanListener onFailure:"
                    + " reason: " + reason + " description: " + description);

            // reschedule the scan
            if (mScanRestartCount++ < MAX_SCAN_RESTART_ALLOWED) {
                scheduleDelayedConnectivityScan(RESTART_SCAN_DELAY_MS);
            } else {
                mScanRestartCount = 0;
                localLog("Failed to successfully start PNO scan for "
                        + MAX_SCAN_RESTART_ALLOWED + " times");
            }
        }

        @Override
        public void onPeriodChanged(int periodInMs) {
            localLog("PnoScanListener onPeriodChanged: "
                    + "actual scan period " + periodInMs + "ms");
        }

        // Currently the PNO scan results doesn't include IE,
        // which contains information required by WifiNetworkSelector. Ignore them
        // for now.
        @Override
        public void onResults(WifiScanner.ScanData[] results) {
        }

        @Override
        public void onFullResult(ScanResult fullScanResult) {
        }

        @Override
        public void onPnoNetworkFound(ScanResult[] results) {
            for (ScanResult result: results) {
                if (result.informationElements == null) {
                    localLog("Skipping scan result with null information elements");
                    continue;
                }
                mScanDetails.add(ScanResultUtil.toScanDetail(result));
            }

            boolean wasConnectAttempted;
            wasConnectAttempted = handleScanResults(mScanDetails, PNO_SCAN_LISTENER, false);
            clearScanDetails();
            mScanRestartCount = 0;

            if (!wasConnectAttempted) {
                // The scan results were rejected by WifiNetworkSelector due to low RSSI values
                if (mLowRssiNetworkRetryDelay > LOW_RSSI_NETWORK_RETRY_MAX_DELAY_MS) {
                    mLowRssiNetworkRetryDelay = LOW_RSSI_NETWORK_RETRY_MAX_DELAY_MS;
                }
                scheduleDelayedConnectivityScan(mLowRssiNetworkRetryDelay);

                // Set up the delay value for next retry.
                mLowRssiNetworkRetryDelay *= 2;
            } else {
                resetLowRssiNetworkRetryDelay();
            }
        }
    }

    private final PnoScanListener mPnoScanListener = new PnoScanListener();

    private class OnNetworkUpdateListener implements
            WifiConfigManager.OnNetworkUpdateListener {
        @Override
        public void onNetworkAdded(WifiConfiguration config) {
            triggerScanOnNetworkChanges();
        }
        @Override
        public void onNetworkEnabled(WifiConfiguration config) {
            triggerScanOnNetworkChanges();
        }
        @Override
        public void onNetworkRemoved(WifiConfiguration config) {
            triggerScanOnNetworkChanges();
        }
        @Override
        public void onNetworkUpdated(WifiConfiguration newConfig, WifiConfiguration oldConfig) {
            triggerScanOnNetworkChanges();
        }
        @Override
        public void onNetworkTemporarilyDisabled(WifiConfiguration config, int disableReason) { }

        @Override
        public void onNetworkPermanentlyDisabled(WifiConfiguration config, int disableReason) {
            triggerScanOnNetworkChanges();
        }
    }

    private class OnSuggestionUpdateListener implements
            WifiNetworkSuggestionsManager.OnSuggestionUpdateListener {
        @Override
        public void onSuggestionsAddedOrUpdated(List<WifiNetworkSuggestion> suggestions) {
            triggerScanOnNetworkChanges();
        }

        @Override
        public void onSuggestionsRemoved(List<WifiNetworkSuggestion> suggestions) {
            triggerScanOnNetworkChanges();
        }
    }

    /**
     * WifiConnectivityManager constructor
     */
    WifiConnectivityManager(Context context, ScoringParams scoringParams,
            ClientModeImpl stateMachine,
            WifiInjector injector, WifiConfigManager configManager,
            WifiNetworkSuggestionsManager wifiNetworkSuggestionsManager, WifiInfo wifiInfo,
            WifiNetworkSelector networkSelector, WifiConnectivityHelper connectivityHelper,
            WifiLastResortWatchdog wifiLastResortWatchdog, OpenNetworkNotifier openNetworkNotifier,
            WifiMetrics wifiMetrics, Handler handler,
            Clock clock, LocalLog localLog, WifiScoreCard scoreCard) {
        mContext = context;
        mStateMachine = stateMachine;
        mWifiInjector = injector;
        mConfigManager = configManager;
        mWifiNetworkSuggestionsManager = wifiNetworkSuggestionsManager;
        mWifiInfo = wifiInfo;
        mNetworkSelector = networkSelector;
        mConnectivityHelper = connectivityHelper;
        mLocalLog = localLog;
        mWifiLastResortWatchdog = wifiLastResortWatchdog;
        mOpenNetworkNotifier = openNetworkNotifier;
        mWifiMetrics = wifiMetrics;
        mAlarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        mEventHandler = handler;
        mClock = clock;
        mScoringParams = scoringParams;
        mConnectionAttemptTimeStamps = new LinkedList<>();

        // Listen to WifiConfigManager network update events
        mConfigManager.addOnNetworkUpdateListener(new OnNetworkUpdateListener());
        // Listen to WifiNetworkSuggestionsManager suggestion update events
        mWifiNetworkSuggestionsManager.addOnSuggestionUpdateListener(
                new OnSuggestionUpdateListener());
        mBssidBlocklistMonitor = mWifiInjector.getBssidBlocklistMonitor();
        mWifiChannelUtilization = mWifiInjector.getWifiChannelUtilizationScan();
        mNetworkSelector.setWifiChannelUtilization(mWifiChannelUtilization);
        mWifiScoreCard = scoreCard;
    }

    /** Initialize single scanning schedules, and validate them */
    private int[] initializeScanningSchedule(int state) {
        int[] scheduleSec;

        if (state == WIFI_STATE_CONNECTED) {
            scheduleSec = mContext.getResources().getIntArray(
                    R.array.config_wifiConnectedScanIntervalScheduleSec);
        } else if (state == WIFI_STATE_DISCONNECTED) {
            scheduleSec = mContext.getResources().getIntArray(
                    R.array.config_wifiDisconnectedScanIntervalScheduleSec);
        } else {
            scheduleSec = null;
        }

        boolean invalidConfig = false;
        if (scheduleSec == null || scheduleSec.length == 0) {
            invalidConfig = true;
        } else {
            for (int val : scheduleSec) {
                if (val <= 0) {
                    invalidConfig = true;
                    break;
                }
            }
        }
        if (!invalidConfig) {
            return scheduleSec;
        }

        Log.e(TAG, "Configuration for wifi scanning schedule is mis-configured,"
                + "using default schedule");
        return DEFAULT_SCANNING_SCHEDULE_SEC;
    }

    /**
     * This checks the connection attempt rate and recommends whether the connection attempt
     * should be skipped or not. This attempts to rate limit the rate of connections to
     * prevent us from flapping between networks and draining battery rapidly.
     */
    private boolean shouldSkipConnectionAttempt(Long timeMillis) {
        Iterator<Long> attemptIter = mConnectionAttemptTimeStamps.iterator();
        // First evict old entries from the queue.
        while (attemptIter.hasNext()) {
            Long connectionAttemptTimeMillis = attemptIter.next();
            if ((timeMillis - connectionAttemptTimeMillis)
                    > MAX_CONNECTION_ATTEMPTS_TIME_INTERVAL_MS) {
                attemptIter.remove();
            } else {
                // This list is sorted by timestamps, so we can skip any more checks
                break;
            }
        }
        // If we've reached the max connection attempt rate, skip this connection attempt
        return (mConnectionAttemptTimeStamps.size() >= MAX_CONNECTION_ATTEMPTS_RATE);
    }

    /**
     * Add the current connection attempt timestamp to our queue of connection attempts.
     */
    private void noteConnectionAttempt(Long timeMillis) {
        mConnectionAttemptTimeStamps.addLast(timeMillis);
    }

    /**
     * This is used to clear the connection attempt rate limiter. This is done when the user
     * explicitly tries to connect to a specified network.
     */
    private void clearConnectionAttemptTimeStamps() {
        mConnectionAttemptTimeStamps.clear();
    }

    /**
     * Attempt to connect to a network candidate.
     *
     * Based on the currently connected network, this menthod determines whether we should
     * connect or roam to the network candidate recommended by WifiNetworkSelector.
     */
    private void connectToNetwork(WifiConfiguration candidate) {
        ScanResult scanResultCandidate = candidate.getNetworkSelectionStatus().getCandidate();
        if (scanResultCandidate == null) {
            localLog("connectToNetwork: bad candidate - "  + candidate
                    + " scanResult: " + scanResultCandidate);
            return;
        }

        String targetBssid = scanResultCandidate.BSSID;
        String targetAssociationId = candidate.SSID + " : " + targetBssid;

        // Check if we are already connected or in the process of connecting to the target
        // BSSID. mWifiInfo.mBSSID tracks the currently connected BSSID. This is checked just
        // in case the firmware automatically roamed to a BSSID different from what
        // WifiNetworkSelector selected.
        if (targetBssid != null
                && (targetBssid.equals(mLastConnectionAttemptBssid)
                    || targetBssid.equals(mWifiInfo.getBSSID()))
                && SupplicantState.isConnecting(mWifiInfo.getSupplicantState())) {
            localLog("connectToNetwork: Either already connected "
                    + "or is connecting to " + targetAssociationId);
            return;
        }

        if (candidate.BSSID != null
                && !candidate.BSSID.equals(ClientModeImpl.SUPPLICANT_BSSID_ANY)
                && !candidate.BSSID.equals(targetBssid)) {
            localLog("connecToNetwork: target BSSID " + targetBssid + " does not match the "
                    + "config specified BSSID " + candidate.BSSID + ". Drop it!");
            return;
        }

        long elapsedTimeMillis = mClock.getElapsedSinceBootMillis();
        if (!mScreenOn && shouldSkipConnectionAttempt(elapsedTimeMillis)) {
            localLog("connectToNetwork: Too many connection attempts. Skipping this attempt!");
            mTotalConnectivityAttemptsRateLimited++;
            return;
        }
        noteConnectionAttempt(elapsedTimeMillis);

        mLastConnectionAttemptBssid = targetBssid;

        WifiConfiguration currentConnectedNetwork = mConfigManager
                .getConfiguredNetwork(mWifiInfo.getNetworkId());
        String currentAssociationId = (currentConnectedNetwork == null) ? "Disconnected" :
                (mWifiInfo.getSSID() + " : " + mWifiInfo.getBSSID());

        if (currentConnectedNetwork != null
                && (currentConnectedNetwork.networkId == candidate.networkId
                //TODO(b/36788683): re-enable linked configuration check
                /* || currentConnectedNetwork.isLinked(candidate) */)) {
            // Framework initiates roaming only if firmware doesn't support
            // {@link android.net.wifi.WifiManager#WIFI_FEATURE_CONTROL_ROAMING}.
            if (mConnectivityHelper.isFirmwareRoamingSupported()) {
                // Keep this logging here for now to validate the firmware roaming behavior.
                localLog("connectToNetwork: Roaming candidate - " + targetAssociationId + "."
                        + " The actual roaming target is up to the firmware.");
            } else {
                localLog("connectToNetwork: Roaming to " + targetAssociationId + " from "
                        + currentAssociationId);
                mStateMachine.startRoamToNetwork(candidate.networkId, scanResultCandidate);
            }
        } else {
            // Framework specifies the connection target BSSID if firmware doesn't support
            // {@link android.net.wifi.WifiManager#WIFI_FEATURE_CONTROL_ROAMING} or the
            // candidate configuration contains a specified BSSID.
            if (mConnectivityHelper.isFirmwareRoamingSupported() && (candidate.BSSID == null
                      || candidate.BSSID.equals(ClientModeImpl.SUPPLICANT_BSSID_ANY))) {
                targetBssid = ClientModeImpl.SUPPLICANT_BSSID_ANY;
                localLog("connectToNetwork: Connect to " + candidate.SSID + ":" + targetBssid
                        + " from " + currentAssociationId);
            } else {
                localLog("connectToNetwork: Connect to " + targetAssociationId + " from "
                        + currentAssociationId);
            }
            mStateMachine.startConnectToNetwork(candidate.networkId, Process.WIFI_UID, targetBssid);
        }
    }

    // Helper for selecting the band for connectivity scan
    private int getScanBand() {
        return getScanBand(true);
    }

    private int getScanBand(boolean isFullBandScan) {
        if (isFullBandScan) {
            return WifiScanner.WIFI_BAND_ALL;
        } else {
            // Use channel list instead.
            return WifiScanner.WIFI_BAND_UNSPECIFIED;
        }
    }

    // Helper for setting the channels for connectivity scan when band is unspecified. Returns
    // false if we can't retrieve the info.
    // If connected, return channels used for the connected network
    // If disconnected, return channels used for any network.
    private boolean setScanChannels(ScanSettings settings) {
        Set<Integer> freqs;

        WifiConfiguration config = mStateMachine.getCurrentWifiConfiguration();
        if (config == null) {
            long ageInMillis = 1000 * 60 * mContext.getResources().getInteger(
                    R.integer.config_wifiInitialPartialScanChannelCacheAgeMins);
            int maxCount = mContext.getResources().getInteger(
                    R.integer.config_wifiInitialPartialScanChannelMaxCount);
            freqs = fetchChannelSetForPartialScan(maxCount, ageInMillis);
        } else {
            freqs = fetchChannelSetForNetworkForPartialScan(config.networkId);
        }

        if (freqs != null && freqs.size() != 0) {
            int index = 0;
            settings.channels = new WifiScanner.ChannelSpec[freqs.size()];
            for (Integer freq : freqs) {
                settings.channels[index++] = new WifiScanner.ChannelSpec(freq);
            }
            return true;
        } else {
            localLog("No history scan channels found, Perform full band scan");
            return false;
        }
    }

    /**
     * Add the channels into the channel set with a size limit.
     * If maxCount equals to 0, will add all available channels into the set.
     * @param channelSet Target set for adding channel to.
     * @param config Network for query channel from WifiScoreCard
     * @param maxCount Size limit of the set. If equals to 0, means no limit.
     * @param ageInMillis Only consider channel info whose timestamps are younger than this value.
     * @return True if all available channels for this network are added, otherwise false.
     */
    private boolean addChannelFromWifiScoreCard(@NonNull Set<Integer> channelSet,
            @NonNull WifiConfiguration config, int maxCount, long ageInMillis) {
        WifiScoreCard.PerNetwork network = mWifiScoreCard.lookupNetwork(config.SSID);
        for (Integer channel : network.getFrequencies(ageInMillis)) {
            if (maxCount > 0 && channelSet.size() >= maxCount) {
                localLog("addChannelFromWifiScoreCard: size limit reached for network:"
                        + config.SSID);
                return false;
            }
            channelSet.add(channel);
        }
        return true;
    }

    /**
     * Fetch channel set for target network.
     */
    @VisibleForTesting
    public Set<Integer> fetchChannelSetForNetworkForPartialScan(int networkId) {
        WifiConfiguration config = mConfigManager.getConfiguredNetwork(networkId);
        if (config == null) {
            return null;
        }
        final int maxNumActiveChannelsForPartialScans = mContext.getResources().getInteger(
                R.integer.config_wifi_framework_associated_partial_scan_max_num_active_channels);
        Set<Integer> channelSet = new HashSet<>();
        // First add the currently connected network channel.
        if (mWifiInfo.getFrequency() > 0) {
            channelSet.add(mWifiInfo.getFrequency());
        }
        // Then get channels for the network.
        addChannelFromWifiScoreCard(channelSet, config, maxNumActiveChannelsForPartialScans,
                CHANNEL_LIST_AGE_MS);
        return channelSet;
    }

    /**
     * Fetch channel set for all saved and suggestion non-passpoint network for partial scan.
     */
    @VisibleForTesting
    public Set<Integer> fetchChannelSetForPartialScan(int maxCount, long ageInMillis) {
        List<WifiConfiguration> networks = getAllScanOptimizationNetworks();
        if (networks.isEmpty()) {
            return null;
        }

        // Sort the networks with the most frequent ones at the front of the network list.
        Collections.sort(networks, mConfigManager.getScanListComparator());

        Set<Integer> channelSet = new HashSet<>();

        for (WifiConfiguration config : networks) {
            if (!addChannelFromWifiScoreCard(channelSet, config, maxCount, ageInMillis)) {
                return channelSet;
            }
        }

        return channelSet;
    }

    // Watchdog timer handler
    private void watchdogHandler() {
        // Schedule the next timer and start a single scan if we are in disconnected state.
        // Otherwise, the watchdog timer will be scheduled when entering disconnected
        // state.
        if (mWifiState == WIFI_STATE_DISCONNECTED) {
            localLog("start a single scan from watchdogHandler");

            scheduleWatchdogTimer();
            startSingleScan(true, WIFI_WORK_SOURCE);
        }
    }

    private void triggerScanOnNetworkChanges() {
        if (mScreenOn) {
            // Update scanning schedule if needed
            if (updateSingleScanningSchedule()) {
                localLog("Saved networks / suggestions updated impacting single scan schedule");
                startConnectivityScan(false);
            }
        } else {
            // Update the PNO scan network list when screen is off. Here we
            // rely on startConnectivityScan() to perform all the checks and clean up.
            localLog("Saved networks / suggestions updated impacting pno scan");
            startConnectivityScan(false);
        }
    }

    // Start a single scan and set up the interval for next single scan.
    private void startPeriodicSingleScan() {
        // Reaching here with scanning schedule is null means this is a false timer alarm
        if (getSingleScanningSchedule() == null) {
            return;
        }

        long currentTimeStamp = mClock.getElapsedSinceBootMillis();

        if (mLastPeriodicSingleScanTimeStamp != RESET_TIME_STAMP) {
            long msSinceLastScan = currentTimeStamp - mLastPeriodicSingleScanTimeStamp;
            if (msSinceLastScan < getScheduledSingleScanIntervalMs(0)) {
                localLog("Last periodic single scan started " + msSinceLastScan
                        + "ms ago, defer this new scan request.");
                schedulePeriodicScanTimer(
                        getScheduledSingleScanIntervalMs(0) - (int) msSinceLastScan);
                return;
            }
        }

        boolean isScanNeeded = true;
        boolean isFullBandScan = true;

        boolean isShortTimeSinceLastNetworkSelection =
                ((currentTimeStamp - mLastNetworkSelectionTimeStamp)
                <= 1000 * mContext.getResources().getInteger(
                R.integer.config_wifiConnectedHighRssiScanMinimumWindowSizeSec));

        boolean isGoodLinkAndAcceptableInternetAndShortTimeSinceLastNetworkSelection =
                mNetworkSelector.hasSufficientLinkQuality(mWifiInfo)
                && mNetworkSelector.hasInternetOrExpectNoInternet(mWifiInfo)
                && isShortTimeSinceLastNetworkSelection;
        // Check it is one of following conditions to skip scan (with firmware roaming)
        // or do partial scan only (without firmware roaming).
        // 1) Network is sufficient
        // 2) link is good, internet status is acceptable
        //    and it is a short time since last network selection
        // 3) There is active stream such that scan will be likely disruptive
        if (mWifiState == WIFI_STATE_CONNECTED
                && (mNetworkSelector.isNetworkSufficient(mWifiInfo)
                || isGoodLinkAndAcceptableInternetAndShortTimeSinceLastNetworkSelection
                || mNetworkSelector.hasActiveStream(mWifiInfo))) {
            // If only partial scan is proposed and firmware roaming control is supported,
            // we will not issue any scan because firmware roaming will take care of
            // intra-SSID roam.
            if (mConnectivityHelper.isFirmwareRoamingSupported()) {
                localLog("No partial scan because firmware roaming is supported.");
                isScanNeeded = false;
            } else {
                localLog("No full band scan because current network is sufficient");
                isFullBandScan = false;
            }
        }

        if (isScanNeeded) {
            mLastPeriodicSingleScanTimeStamp = currentTimeStamp;

            if (mWifiState == WIFI_STATE_DISCONNECTED
                    && mInitialScanState == INITIAL_SCAN_STATE_START) {
                startSingleScan(false, WIFI_WORK_SOURCE);

                // Note, initial partial scan may fail due to lack of channel history
                // Hence, we verify state before changing to AWIATING_RESPONSE
                if (mInitialScanState == INITIAL_SCAN_STATE_START) {
                    setInitialScanState(INITIAL_SCAN_STATE_AWAITING_RESPONSE);
                    mWifiMetrics.incrementInitialPartialScanCount();
                }
                // No scheduling for another scan (until we get the results)
                return;
            }

            startSingleScan(isFullBandScan, WIFI_WORK_SOURCE);
            schedulePeriodicScanTimer(
                    getScheduledSingleScanIntervalMs(mCurrentSingleScanScheduleIndex));

            // Set up the next scan interval in an exponential backoff fashion.
            mCurrentSingleScanScheduleIndex++;
        } else {
            // Since we already skipped this scan, keep the same scan interval for next scan.
            schedulePeriodicScanTimer(
                    getScheduledSingleScanIntervalMs(mCurrentSingleScanScheduleIndex));
        }
    }

    // Retrieve a value from single scanning schedule in ms
    private int getScheduledSingleScanIntervalMs(int index) {
        synchronized (mLock) {
            if (mCurrentSingleScanScheduleSec == null) {
                Log.e(TAG, "Invalid attempt to get schedule interval, Schedule array is null ");

                // Use a default value
                return DEFAULT_SCANNING_SCHEDULE_SEC[0] * 1000;
            }

            if (index >= mCurrentSingleScanScheduleSec.length) {
                index = mCurrentSingleScanScheduleSec.length - 1;
            }

            return mCurrentSingleScanScheduleSec[index] * 1000;
        }
    }

    // Set the single scanning schedule
    private void setSingleScanningSchedule(int[] scheduleSec) {
        synchronized (mLock) {
            mCurrentSingleScanScheduleSec = scheduleSec;
        }
    }

    // Get the single scanning schedule
    private int[] getSingleScanningSchedule() {
        synchronized (mLock) {
            return mCurrentSingleScanScheduleSec;
        }
    }

    // Update the single scanning schedule if needed, and return true if update occurs
    private boolean updateSingleScanningSchedule() {
        if (mWifiState != WIFI_STATE_CONNECTED) {
            // No need to update the scanning schedule
            return false;
        }

        boolean shouldUseSingleSavedNetworkSchedule = useSingleSavedNetworkSchedule();

        if (mCurrentSingleScanScheduleSec == mConnectedSingleScanScheduleSec
                && shouldUseSingleSavedNetworkSchedule) {
            mCurrentSingleScanScheduleSec = mConnectedSingleSavedNetworkSingleScanScheduleSec;
            return true;
        }
        if (mCurrentSingleScanScheduleSec == mConnectedSingleSavedNetworkSingleScanScheduleSec
                && !shouldUseSingleSavedNetworkSchedule) {
            mCurrentSingleScanScheduleSec = mConnectedSingleScanScheduleSec;
            return true;
        }
        return false;
    }

    // Set initial scan state
    private void setInitialScanState(int state) {
        Log.i(TAG, "SetInitialScanState to : " + state);
        mInitialScanState = state;
    }

    // Reset the last periodic single scan time stamp so that the next periodic single
    // scan can start immediately.
    private void resetLastPeriodicSingleScanTimeStamp() {
        mLastPeriodicSingleScanTimeStamp = RESET_TIME_STAMP;
    }

    // Periodic scan timer handler
    private void periodicScanTimerHandler() {
        localLog("periodicScanTimerHandler");

        // Schedule the next timer and start a single scan if screen is on.
        if (mScreenOn) {
            startPeriodicSingleScan();
        }
    }

    // Start a single scan
    private void startForcedSingleScan(boolean isFullBandScan, WorkSource workSource) {
        mPnoScanListener.resetLowRssiNetworkRetryDelay();

        ScanSettings settings = new ScanSettings();
        if (!isFullBandScan) {
            if (!setScanChannels(settings)) {
                isFullBandScan = true;
                // Skip the initial scan since no channel history available
                setInitialScanState(INITIAL_SCAN_STATE_COMPLETE);
            } else {
                mInitialPartialScanChannelCount = settings.channels.length;
            }
        }
        settings.type = WifiScanner.SCAN_TYPE_HIGH_ACCURACY; // always do high accuracy scans.
        settings.band = getScanBand(isFullBandScan);
        settings.reportEvents = WifiScanner.REPORT_EVENT_FULL_SCAN_RESULT
                            | WifiScanner.REPORT_EVENT_AFTER_EACH_SCAN;
        settings.numBssidsPerScan = 0;
        settings.hiddenNetworks.clear();
        // retrieve the list of hidden network SSIDs from saved network to scan for
        settings.hiddenNetworks.addAll(mConfigManager.retrieveHiddenNetworkList());
        // retrieve the list of hidden network SSIDs from Network suggestion to scan for
        settings.hiddenNetworks.addAll(mWifiNetworkSuggestionsManager.retrieveHiddenNetworkList());

        SingleScanListener singleScanListener =
                new SingleScanListener(isFullBandScan);
        mScanner.startScan(
                settings, new HandlerExecutor(mEventHandler), singleScanListener, workSource);
        mWifiMetrics.incrementConnectivityOneshotScanCount();
    }

    private void startSingleScan(boolean isFullBandScan, WorkSource workSource) {
        if (!mWifiEnabled || !mAutoJoinEnabled) {
            return;
        }
        startForcedSingleScan(isFullBandScan, workSource);
    }

    // Start a periodic scan when screen is on
    private void startPeriodicScan(boolean scanImmediately) {
        mPnoScanListener.resetLowRssiNetworkRetryDelay();

        // No connectivity scan if auto roaming is disabled.
        if (mWifiState == WIFI_STATE_CONNECTED && !mContext.getResources().getBoolean(
                R.bool.config_wifi_framework_enable_associated_network_selection)) {
            return;
        }

        // Due to b/28020168, timer based single scan will be scheduled
        // to provide periodic scan in an exponential backoff fashion.
        if (scanImmediately) {
            resetLastPeriodicSingleScanTimeStamp();
        }
        mCurrentSingleScanScheduleIndex = 0;
        startPeriodicSingleScan();
    }

    private int deviceMobilityStateToPnoScanIntervalMs(@DeviceMobilityState int state) {
        switch (state) {
            case WifiManager.DEVICE_MOBILITY_STATE_UNKNOWN:
            case WifiManager.DEVICE_MOBILITY_STATE_LOW_MVMT:
            case WifiManager.DEVICE_MOBILITY_STATE_HIGH_MVMT:
                return mContext.getResources()
                        .getInteger(R.integer.config_wifiMovingPnoScanIntervalMillis);
            case WifiManager.DEVICE_MOBILITY_STATE_STATIONARY:
                return mContext.getResources()
                        .getInteger(R.integer.config_wifiStationaryPnoScanIntervalMillis);
            default:
                return -1;
        }
    }

    /**
     * Pass device mobility state to WifiChannelUtilization and
     * alter the PNO scan interval based on the current device mobility state.
     * If the device is stationary, it will likely not find many new Wifi networks. Thus, increase
     * the interval between scans. Decrease the interval between scans if the device begins to move
     * again.
     * @param newState the new device mobility state
     */
    public void setDeviceMobilityState(@DeviceMobilityState int newState) {
        int oldDeviceMobilityState = mDeviceMobilityState;
        localLog("Device mobility state changed. state=" + newState);
        int newPnoScanIntervalMs = deviceMobilityStateToPnoScanIntervalMs(newState);
        if (newPnoScanIntervalMs < 0) {
            Log.e(TAG, "Invalid device mobility state: " + newState);
            return;
        }
        mDeviceMobilityState = newState;
        mWifiChannelUtilization.setDeviceMobilityState(newState);

        int oldPnoScanIntervalMs = deviceMobilityStateToPnoScanIntervalMs(oldDeviceMobilityState);
        if (newPnoScanIntervalMs == oldPnoScanIntervalMs) {
            if (mPnoScanStarted) {
                mWifiMetrics.logPnoScanStop();
                mWifiMetrics.enterDeviceMobilityState(newState);
                mWifiMetrics.logPnoScanStart();
            } else {
                mWifiMetrics.enterDeviceMobilityState(newState);
            }
        } else {
            Log.d(TAG, "PNO Scan Interval changed to " + newPnoScanIntervalMs + " ms.");

            if (mPnoScanStarted) {
                Log.d(TAG, "Restarting PNO Scan with new scan interval");
                stopPnoScan();
                mWifiMetrics.enterDeviceMobilityState(newState);
                startDisconnectedPnoScan();
            } else {
                mWifiMetrics.enterDeviceMobilityState(newState);
            }
        }
    }

    // Start a DisconnectedPNO scan when screen is off and Wifi is disconnected
    private void startDisconnectedPnoScan() {
        // Initialize PNO settings
        PnoSettings pnoSettings = new PnoSettings();
        List<PnoSettings.PnoNetwork> pnoNetworkList = retrievePnoNetworkList();
        int listSize = pnoNetworkList.size();

        if (listSize == 0) {
            // No saved network
            localLog("No saved network for starting disconnected PNO.");
            return;
        }

        pnoSettings.networkList = new PnoSettings.PnoNetwork[listSize];
        pnoSettings.networkList = pnoNetworkList.toArray(pnoSettings.networkList);
        pnoSettings.min6GHzRssi = mScoringParams.getEntryRssi(ScanResult.BAND_6_GHZ_START_FREQ_MHZ);
        pnoSettings.min5GHzRssi = mScoringParams.getEntryRssi(ScanResult.BAND_5_GHZ_START_FREQ_MHZ);
        pnoSettings.min24GHzRssi = mScoringParams.getEntryRssi(
                ScanResult.BAND_24_GHZ_START_FREQ_MHZ);

        // Initialize scan settings
        ScanSettings scanSettings = new ScanSettings();
        scanSettings.band = getScanBand();
        scanSettings.reportEvents = WifiScanner.REPORT_EVENT_NO_BATCH;
        scanSettings.numBssidsPerScan = 0;
        scanSettings.periodInMs = deviceMobilityStateToPnoScanIntervalMs(mDeviceMobilityState);

        mPnoScanListener.clearScanDetails();

        mScanner.startDisconnectedPnoScan(
                scanSettings, pnoSettings, new HandlerExecutor(mEventHandler), mPnoScanListener);
        mPnoScanStarted = true;
        mWifiMetrics.logPnoScanStart();
    }

    private @NonNull List<WifiConfiguration> getAllScanOptimizationNetworks() {
        List<WifiConfiguration> networks = mConfigManager.getSavedNetworks(-1);
        networks.addAll(mWifiNetworkSuggestionsManager.getAllScanOptimizationSuggestionNetworks());
        // remove all auto-join disabled or network selection disabled network.
        networks.removeIf(config -> !config.allowAutojoin
                || !config.getNetworkSelectionStatus().isNetworkEnabled());
        return networks;
    }

    /**
     * Retrieve the PnoNetworks from Saved and suggestion non-passpoint network.
     */
    @VisibleForTesting
    public List<PnoSettings.PnoNetwork> retrievePnoNetworkList() {
        List<WifiConfiguration> networks = getAllScanOptimizationNetworks();

        if (networks.isEmpty()) {
            return Collections.EMPTY_LIST;
        }
        Collections.sort(networks, mConfigManager.getScanListComparator());
        boolean pnoFrequencyCullingEnabled = mContext.getResources()
                .getBoolean(R.bool.config_wifiPnoFrequencyCullingEnabled);

        List<PnoSettings.PnoNetwork> pnoList = new ArrayList<>();
        Set<WifiScanner.PnoSettings.PnoNetwork> pnoSet = new HashSet<>();
        for (WifiConfiguration config : networks) {
            WifiScanner.PnoSettings.PnoNetwork pnoNetwork =
                    WifiConfigurationUtil.createPnoNetwork(config);
            if (pnoSet.contains(pnoNetwork)) {
                continue;
            }
            pnoList.add(pnoNetwork);
            pnoSet.add(pnoNetwork);
            if (!pnoFrequencyCullingEnabled) {
                continue;
            }
            Set<Integer> channelList = new HashSet<>();
            addChannelFromWifiScoreCard(channelList, config, 0,
                    MAX_PNO_SCAN_FREQUENCY_AGE_MS);
            pnoNetwork.frequencies = channelList.stream().mapToInt(Integer::intValue).toArray();
            localLog("retrievePnoNetworkList " + pnoNetwork.ssid + ":"
                    + Arrays.toString(pnoNetwork.frequencies));
        }
        return pnoList;
    }

    // Stop PNO scan.
    private void stopPnoScan() {
        if (!mPnoScanStarted) return;

        mScanner.stopPnoScan(mPnoScanListener);
        mPnoScanStarted = false;
        mWifiMetrics.logPnoScanStop();
    }

    // Set up watchdog timer
    private void scheduleWatchdogTimer() {
        localLog("scheduleWatchdogTimer");

        mAlarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                            mClock.getElapsedSinceBootMillis() + WATCHDOG_INTERVAL_MS,
                            WATCHDOG_TIMER_TAG,
                            mWatchdogListener, mEventHandler);
    }

    // Schedules a delayed partial scan, which will scan the frequencies in mCachedWifiCandidates.
    private void scheduleDelayedPartialScan(long delayMillis) {
        mAlarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                mClock.getElapsedSinceBootMillis() + delayMillis, DELAYED_PARTIAL_SCAN_TIMER_TAG,
                mDelayedPartialScanTimerListener, mEventHandler);
        mDelayedPartialScanTimerSet = true;
    }

    // Cancel the delayed partial scan timer.
    private void cancelDelayedPartialScan() {
        if (mDelayedPartialScanTimerSet) {
            mAlarmManager.cancel(mDelayedPartialScanTimerListener);
            mDelayedPartialScanTimerSet = false;
        }
    }

    // Set up periodic scan timer
    private void schedulePeriodicScanTimer(int intervalMs) {
        mAlarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                            mClock.getElapsedSinceBootMillis() + intervalMs,
                            PERIODIC_SCAN_TIMER_TAG,
                            mPeriodicScanTimerListener, mEventHandler);
        mPeriodicScanTimerSet = true;
    }

    // Cancel periodic scan timer
    private void cancelPeriodicScanTimer() {
        if (mPeriodicScanTimerSet) {
            mAlarmManager.cancel(mPeriodicScanTimerListener);
            mPeriodicScanTimerSet = false;
        }
    }

    // Set up timer to start a delayed single scan after RESTART_SCAN_DELAY_MS
    private void scheduleDelayedSingleScan(boolean isFullBandScan) {
        localLog("scheduleDelayedSingleScan");

        RestartSingleScanListener restartSingleScanListener =
                new RestartSingleScanListener(isFullBandScan);
        mAlarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                            mClock.getElapsedSinceBootMillis() + RESTART_SCAN_DELAY_MS,
                            RESTART_SINGLE_SCAN_TIMER_TAG,
                            restartSingleScanListener, mEventHandler);
    }

    // Set up timer to start a delayed scan after msFromNow milli-seconds
    private void scheduleDelayedConnectivityScan(int msFromNow) {
        localLog("scheduleDelayedConnectivityScan");

        mAlarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                            mClock.getElapsedSinceBootMillis() + msFromNow,
                            RESTART_CONNECTIVITY_SCAN_TIMER_TAG,
                            mRestartScanListener, mEventHandler);

    }

    // Start a connectivity scan. The scan method is chosen according to
    // the current screen state and WiFi state.
    private void startConnectivityScan(boolean scanImmediately) {
        localLog("startConnectivityScan: screenOn=" + mScreenOn
                + " wifiState=" + stateToString(mWifiState)
                + " scanImmediately=" + scanImmediately
                + " wifiEnabled=" + mWifiEnabled
                + " wifiConnectivityManagerEnabled="
                + mAutoJoinEnabled);

        if (!mWifiEnabled || !mAutoJoinEnabled) {
            return;
        }

        // Always stop outstanding connecivity scan if there is any
        stopConnectivityScan();

        // Don't start a connectivity scan while Wifi is in the transition
        // between connected and disconnected states.
        if ((mWifiState != WIFI_STATE_CONNECTED && mWifiState != WIFI_STATE_DISCONNECTED)
                || (getSingleScanningSchedule() == null)) {
            return;
        }

        if (mScreenOn) {
            startPeriodicScan(scanImmediately);
        } else {
            if (mWifiState == WIFI_STATE_DISCONNECTED && !mPnoScanStarted) {
                startDisconnectedPnoScan();
            }
        }

    }

    // Stop connectivity scan if there is any.
    private void stopConnectivityScan() {
        // Due to b/28020168, timer based single scan will be scheduled
        // to provide periodic scan in an exponential backoff fashion.
        cancelPeriodicScanTimer();
        cancelDelayedPartialScan();
        stopPnoScan();
    }

    /**
     * Handler for screen state (on/off) changes
     */
    public void handleScreenStateChanged(boolean screenOn) {
        localLog("handleScreenStateChanged: screenOn=" + screenOn);

        mScreenOn = screenOn;

        if (mWifiState == WIFI_STATE_DISCONNECTED
                && mContext.getResources().getBoolean(R.bool.config_wifiEnablePartialInitialScan)) {
            setInitialScanState(INITIAL_SCAN_STATE_START);
        }

        mOpenNetworkNotifier.handleScreenStateChanged(screenOn);

        startConnectivityScan(SCAN_ON_SCHEDULE);
    }

    /**
     * Helper function that converts the WIFI_STATE_XXX constants to string
     */
    private static String stateToString(int state) {
        switch (state) {
            case WIFI_STATE_CONNECTED:
                return "connected";
            case WIFI_STATE_DISCONNECTED:
                return "disconnected";
            case WIFI_STATE_TRANSITIONING:
                return "transitioning";
            default:
                return "unknown";
        }
    }

    /**
     * Check if Single saved network schedule should be used
     * This is true if the one of the following is satisfied:
     * 1. Device has a total of 1 network whether saved, passpoint, or suggestion.
     * 2. The device is connected to that network.
     */
    private boolean useSingleSavedNetworkSchedule() {
        WifiConfiguration currentNetwork = mStateMachine.getCurrentWifiConfiguration();
        if (currentNetwork == null) {
            localLog("Current network is missing, may caused by remove network and disconnecting ");
            return false;
        }
        List<WifiConfiguration> savedNetworks =
                mConfigManager.getSavedNetworks(Process.WIFI_UID);
        // If we have multiple saved networks, then no need to proceed
        if (savedNetworks.size() > 1) {
            return false;
        }

        List<PasspointConfiguration> passpointNetworks =
                mWifiInjector.getPasspointManager().getProviderConfigs(Process.WIFI_UID, true);
        // If we have multiple networks (saved + passpoint), then no need to proceed
        if (passpointNetworks.size() + savedNetworks.size() > 1) {
            return false;
        }

        Set<WifiNetworkSuggestion> suggestionsNetworks =
                mWifiNetworkSuggestionsManager.getAllApprovedNetworkSuggestions();
        // If total size not equal to 1, then no need to proceed
        if (passpointNetworks.size() + savedNetworks.size() + suggestionsNetworks.size() != 1) {
            return false;
        }

        // Next verify that this network is the one device is connected to
        int currentNetworkId = currentNetwork.networkId;

        // If we have a single saved network, and we are connected to it, return true.
        if (savedNetworks.size() == 1) {
            return (savedNetworks.get(0).networkId == currentNetworkId);
        }

        // If we have a single passpoint network, and we are connected to it, return true.
        if (passpointNetworks.size() == 1) {
            String passpointKey = passpointNetworks.get(0).getUniqueId();
            WifiConfiguration config = mConfigManager.getConfiguredNetwork(passpointKey);
            return (config != null && config.networkId == currentNetworkId);
        }

        // If we have a single suggestion network, and we are connected to it, return true.
        WifiNetworkSuggestion network = suggestionsNetworks.iterator().next();
        String suggestionKey = network.getWifiConfiguration().getKey();
        WifiConfiguration config = mConfigManager.getConfiguredNetwork(suggestionKey);
        return (config != null && config.networkId == currentNetworkId);
    }

    private int[] initSingleSavedNetworkSchedule() {
        int[] schedule = mContext.getResources().getIntArray(
                    R.array.config_wifiSingleSavedNetworkConnectedScanIntervalScheduleSec);
        if (schedule == null || schedule.length == 0) {
            return null;
        }

        for (int val : schedule) {
            if (val <= 0) {
                return null;
            }
        }
        return schedule;
    }

    /**
     * Handler for WiFi state (connected/disconnected) changes
     */
    public void handleConnectionStateChanged(int state) {
        localLog("handleConnectionStateChanged: state=" + stateToString(state));

        if (mConnectedSingleScanScheduleSec == null) {
            mConnectedSingleScanScheduleSec = initializeScanningSchedule(WIFI_STATE_CONNECTED);
        }
        if (mDisconnectedSingleScanScheduleSec == null) {
            mDisconnectedSingleScanScheduleSec =
                    initializeScanningSchedule(WIFI_STATE_DISCONNECTED);
        }
        if (mConnectedSingleSavedNetworkSingleScanScheduleSec == null) {
            mConnectedSingleSavedNetworkSingleScanScheduleSec =
                    initSingleSavedNetworkSchedule();
            if (mConnectedSingleSavedNetworkSingleScanScheduleSec == null) {
                mConnectedSingleSavedNetworkSingleScanScheduleSec = mConnectedSingleScanScheduleSec;
            }
        }

        mWifiState = state;

        // Reset BSSID of last connection attempt and kick off
        // the watchdog timer if entering disconnected state.
        if (mWifiState == WIFI_STATE_DISCONNECTED) {
            mLastConnectionAttemptBssid = null;
            scheduleWatchdogTimer();
            // Switch to the disconnected scanning schedule
            setSingleScanningSchedule(mDisconnectedSingleScanScheduleSec);
            startConnectivityScan(SCAN_IMMEDIATELY);
        } else if (mWifiState == WIFI_STATE_CONNECTED) {
            if (useSingleSavedNetworkSchedule()) {
                // Switch to Single-Saved-Network connected schedule
                setSingleScanningSchedule(mConnectedSingleSavedNetworkSingleScanScheduleSec);
            } else {
                // Switch to connected single scanning schedule
                setSingleScanningSchedule(mConnectedSingleScanScheduleSec);
            }
            startConnectivityScan(SCAN_ON_SCHEDULE);
        } else {
            // Intermediate state, no applicable single scanning schedule
            setSingleScanningSchedule(null);
            startConnectivityScan(SCAN_ON_SCHEDULE);
        }
    }

    /**
     * Handler when a WiFi connection attempt ended.
     *
     * @param failureCode {@link WifiMetrics.ConnectionEvent} failure code.
     * @param bssid the failed network.
     * @param ssid identifies the failed network.
     */
    public void handleConnectionAttemptEnded(int failureCode, @NonNull String bssid,
            @NonNull String ssid) {
        if (failureCode == WifiMetrics.ConnectionEvent.FAILURE_NONE) {
            String ssidUnquoted = (mWifiInfo.getWifiSsid() == null)
                    ? null
                    : mWifiInfo.getWifiSsid().toString();
            mOpenNetworkNotifier.handleWifiConnected(ssidUnquoted);
        } else {
            mOpenNetworkNotifier.handleConnectionFailure();
            retryConnectionOnLatestCandidates(bssid, ssid);
        }
    }

    private void retryConnectionOnLatestCandidates(String bssid, String ssid) {
        try {
            if (mLatestCandidates == null || mLatestCandidates.size() == 0
                    || mClock.getElapsedSinceBootMillis() - mLatestCandidatesTimestampMs
                    > TEMP_BSSID_BLOCK_DURATION) {
                mLatestCandidates = null;
                return;
            }
            MacAddress macAddress = MacAddress.fromString(bssid);
            int prevNumCandidates = mLatestCandidates.size();
            mLatestCandidates = mLatestCandidates.stream()
                    .filter(candidate -> !macAddress.equals(candidate.getKey().bssid))
                    .collect(Collectors.toList());
            if (prevNumCandidates == mLatestCandidates.size()) {
                return;
            }
            WifiConfiguration candidate = mNetworkSelector.selectNetwork(mLatestCandidates);
            if (candidate != null) {
                localLog("Automatic retry on the next best WNS candidate-" + candidate.SSID);
                // Make sure that the failed BSSID is blocked for at least TEMP_BSSID_BLOCK_DURATION
                // to prevent the supplicant from trying it again.
                mBssidBlocklistMonitor.blockBssidForDurationMs(bssid, ssid,
                        TEMP_BSSID_BLOCK_DURATION);
                connectToNetwork(candidate);
            }
        } catch (IllegalArgumentException e) {
            localLog("retryConnectionOnLatestCandidates: failed to create MacAddress from bssid="
                    + bssid);
            mLatestCandidates = null;
            return;
        }
    }

    // Enable auto-join if WifiConnectivityManager is enabled & we have any pending generic network
    // request (trusted or untrusted) and no specific network request in progress.
    private void checkAllStatesAndEnableAutoJoin() {
        // if auto-join was disabled externally, don't re-enable for any triggers.
        // External triggers to disable always trumps any internal state.
        setAutoJoinEnabled(mAutoJoinEnabledExternal
                && (mUntrustedConnectionAllowed || mTrustedConnectionAllowed)
                && !mSpecificNetworkRequestInProgress);
        startConnectivityScan(SCAN_IMMEDIATELY);
    }

    /**
     * Triggered when {@link WifiNetworkFactory} has a pending general network request.
     */
    public void setTrustedConnectionAllowed(boolean allowed) {
        localLog("setTrustedConnectionAllowed: allowed=" + allowed);

        if (mTrustedConnectionAllowed != allowed) {
            mTrustedConnectionAllowed = allowed;
            checkAllStatesAndEnableAutoJoin();
        }
    }


    /**
     * Triggered when {@link UntrustedWifiNetworkFactory} has a pending ephemeral network request.
     */
    public void setUntrustedConnectionAllowed(boolean allowed) {
        localLog("setUntrustedConnectionAllowed: allowed=" + allowed);

        if (mUntrustedConnectionAllowed != allowed) {
            mUntrustedConnectionAllowed = allowed;
            checkAllStatesAndEnableAutoJoin();
        }
    }

    /**
     * Triggered when {@link WifiNetworkFactory} is processing a specific network request.
     */
    public void setSpecificNetworkRequestInProgress(boolean inProgress) {
        localLog("setsetSpecificNetworkRequestInProgress : inProgress=" + inProgress);

        if (mSpecificNetworkRequestInProgress != inProgress) {
            mSpecificNetworkRequestInProgress = inProgress;
            checkAllStatesAndEnableAutoJoin();
        }
    }

    /**
     * Handler when user specifies a particular network to connect to
     */
    public void setUserConnectChoice(int netId) {
        localLog("setUserConnectChoice: netId=" + netId);

        mNetworkSelector.setUserConnectChoice(netId);
    }

    /**
     * Handler to prepare for connection to a user or app specified network
     */
    public void prepareForForcedConnection(int netId) {
        WifiConfiguration config = mConfigManager.getConfiguredNetwork(netId);
        if (config == null) {
            return;
        }
        localLog("prepareForForcedConnection: SSID=" + config.SSID);

        clearConnectionAttemptTimeStamps();
        mBssidBlocklistMonitor.clearBssidBlocklistForSsid(config.SSID);
    }

    /**
     * Handler for on-demand connectivity scan
     */
    public void forceConnectivityScan(WorkSource workSource) {
        if (!mWifiEnabled) return;
        localLog("forceConnectivityScan in request of " + workSource);

        clearConnectionAttemptTimeStamps();
        mWaitForFullBandScanResults = true;
        startForcedSingleScan(true, workSource);
    }

    /**
     * Helper method to populate WifiScanner handle. This is done lazily because
     * WifiScanningService is started after WifiService.
     */
    private void retrieveWifiScanner() {
        if (mScanner != null) return;
        mScanner = mWifiInjector.getWifiScanner();
        checkNotNull(mScanner);
        // Register for all single scan results
        mScanner.registerScanListener(new HandlerExecutor(mEventHandler), mAllSingleScanListener);
    }

    /**
     * Start WifiConnectivityManager
     */
    private void start() {
        if (mRunning) return;
        retrieveWifiScanner();
        mConnectivityHelper.getFirmwareRoamingInfo();
        mBssidBlocklistMonitor.clearBssidBlocklist();
        mWifiChannelUtilization.init(mStateMachine.getWifiLinkLayerStats());

        if (mContext.getResources().getBoolean(R.bool.config_wifiEnablePartialInitialScan)) {
            setInitialScanState(INITIAL_SCAN_STATE_START);
        }

        mRunning = true;
        mLatestCandidates = null;
        mLatestCandidatesTimestampMs = 0;
    }

    /**
     * Stop and reset WifiConnectivityManager
     */
    private void stop() {
        if (!mRunning) return;
        mRunning = false;
        stopConnectivityScan();
        resetLastPeriodicSingleScanTimeStamp();
        mOpenNetworkNotifier.clearPendingNotification(true /* resetRepeatDelay */);
        mLastConnectionAttemptBssid = null;
        mWaitForFullBandScanResults = false;
        mLatestCandidates = null;
        mLatestCandidatesTimestampMs = 0;
        mScanRestartCount = 0;
    }

    /**
     * Update WifiConnectivityManager running state
     *
     * Start WifiConnectivityManager only if both Wifi and WifiConnectivityManager
     * are enabled, otherwise stop it.
     */
    private void updateRunningState() {
        if (mWifiEnabled && mAutoJoinEnabled) {
            localLog("Starting up WifiConnectivityManager");
            start();
        } else {
            localLog("Stopping WifiConnectivityManager");
            stop();
        }
    }

    /**
     * Inform WiFi is enabled for connection or not
     */
    public void setWifiEnabled(boolean enable) {
        localLog("Set WiFi " + (enable ? "enabled" : "disabled"));

        if (mWifiEnabled && !enable) {
            mNetworkSelector.resetOnDisable();
            mBssidBlocklistMonitor.clearBssidBlocklist();
        }
        mWifiEnabled = enable;
        updateRunningState();
    }

    /**
     * Turn on/off the WifiConnectivityManager at runtime
     */
    private void setAutoJoinEnabled(boolean enable) {
        mAutoJoinEnabled = enable;
        updateRunningState();
    }

    /**
     * Turn on/off the auto join at runtime
     */
    public void setAutoJoinEnabledExternal(boolean enable) {
        localLog("Set auto join " + (enable ? "enabled" : "disabled"));

        if (mAutoJoinEnabledExternal != enable) {
            mAutoJoinEnabledExternal = enable;
            checkAllStatesAndEnableAutoJoin();
        }
    }

    @VisibleForTesting
    int getLowRssiNetworkRetryDelay() {
        return mPnoScanListener.getLowRssiNetworkRetryDelay();
    }

    @VisibleForTesting
    long getLastPeriodicSingleScanTimeStamp() {
        return mLastPeriodicSingleScanTimeStamp;
    }

    /**
     * Dump the local logs.
     */
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("Dump of WifiConnectivityManager");
        pw.println("WifiConnectivityManager - Log Begin ----");
        mLocalLog.dump(fd, pw, args);
        pw.println("WifiConnectivityManager - Log End ----");
        mOpenNetworkNotifier.dump(fd, pw, args);
        mBssidBlocklistMonitor.dump(fd, pw, args);
    }
}
