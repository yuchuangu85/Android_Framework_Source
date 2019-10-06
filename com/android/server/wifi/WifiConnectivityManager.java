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

import static android.net.wifi.WifiConfiguration.NetworkSelectionStatus.DISABLED_NO_INTERNET_PERMANENT;
import static android.net.wifi.WifiConfiguration.NetworkSelectionStatus.DISABLED_NO_INTERNET_TEMPORARY;

import static com.android.internal.util.Preconditions.checkNotNull;
import static com.android.server.wifi.ClientModeImpl.WIFI_WORK_SOURCE;

import android.app.AlarmManager;
import android.content.Context;
import android.net.wifi.ScanResult;
import android.net.wifi.SupplicantState;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiManager.DeviceMobilityState;
import android.net.wifi.WifiScanner;
import android.net.wifi.WifiScanner.PnoSettings;
import android.net.wifi.WifiScanner.ScanSettings;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.WorkSource;
import android.util.LocalLog;
import android.util.Log;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.wifi.util.ScanResultUtil;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    private static final long RESET_TIME_STAMP = Long.MIN_VALUE;
    // Constants to indicate whether a scan should start immediately or
    // it should comply to the minimum scan interval rule.
    private static final boolean SCAN_IMMEDIATELY = true;
    private static final boolean SCAN_ON_SCHEDULE = false;
    // Periodic scan interval in milli-seconds. This is the scan
    // performed when screen is on.
    @VisibleForTesting
    public static final int PERIODIC_SCAN_INTERVAL_MS = 20 * 1000; // 20 seconds
    // When screen is on and WiFi traffic is heavy, exponential backoff
    // connectivity scans are scheduled. This constant defines the maximum
    // scan interval in this scenario.
    @VisibleForTesting
    public static final int MAX_PERIODIC_SCAN_INTERVAL_MS = 160 * 1000; // 160 seconds
    // Initial PNO scan interval in milliseconds when the device is moving. The scan interval backs
    // off from this initial interval on subsequent scans. This scan is performed when screen is
    // off and disconnected.
    @VisibleForTesting
    static final int MOVING_PNO_SCAN_INTERVAL_MS = 20 * 1000; // 20 seconds
    // Initial PNO scan interval in milliseconds when the device is stationary. The scan interval
    // backs off from this initial interval on subsequent scans. This scan is performed when screen
    // is off and disconnected.
    @VisibleForTesting
    static final int STATIONARY_PNO_SCAN_INTERVAL_MS = 60 * 1000; // 1 minute

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
    private static final int CHANNEL_LIST_AGE_MS = 60 * 60 * 1000; // 1 hour
    // This is the time interval for the connection attempt rate calculation. Connection attempt
    // timestamps beyond this interval is evicted from the list.
    public static final int MAX_CONNECTION_ATTEMPTS_TIME_INTERVAL_MS = 4 * 60 * 1000; // 4 mins
    // Max number of connection attempts in the above time interval.
    public static final int MAX_CONNECTION_ATTEMPTS_RATE = 6;

    // ClientModeImpl has a bunch of states. From the
    // WifiConnectivityManager's perspective it only cares
    // if it is in Connected state, Disconnected state or in
    // transition between these two states.
    public static final int WIFI_STATE_UNKNOWN = 0;
    public static final int WIFI_STATE_CONNECTED = 1;
    public static final int WIFI_STATE_DISCONNECTED = 2;
    public static final int WIFI_STATE_TRANSITIONING = 3;

    // Log tag for this class
    private static final String TAG = "WifiConnectivityManager";

    private final ClientModeImpl mStateMachine;
    private final WifiInjector mWifiInjector;
    private final WifiConfigManager mConfigManager;
    private final WifiInfo mWifiInfo;
    private final WifiConnectivityHelper mConnectivityHelper;
    private final WifiNetworkSelector mNetworkSelector;
    private final WifiLastResortWatchdog mWifiLastResortWatchdog;
    private final OpenNetworkNotifier mOpenNetworkNotifier;
    private final CarrierNetworkNotifier mCarrierNetworkNotifier;
    private final CarrierNetworkConfig mCarrierNetworkConfig;
    private final WifiMetrics mWifiMetrics;
    private final AlarmManager mAlarmManager;
    private final Handler mEventHandler;
    private final Clock mClock;
    private final ScoringParams mScoringParams;
    private final LocalLog mLocalLog;
    private final LinkedList<Long> mConnectionAttemptTimeStamps;
    private WifiScanner mScanner;

    private boolean mDbg = false;
    private boolean mWifiEnabled = false;
    private boolean mWifiConnectivityManagerEnabled = false;
    private boolean mRunning = false;
    private boolean mScreenOn = false;
    private int mWifiState = WIFI_STATE_UNKNOWN;
    private boolean mUntrustedConnectionAllowed = false;
    private boolean mTrustedConnectionAllowed = false;
    private boolean mSpecificNetworkRequestInProgress = false;
    private int mScanRestartCount = 0;
    private int mSingleScanRestartCount = 0;
    private int mTotalConnectivityAttemptsRateLimited = 0;
    private String mLastConnectionAttemptBssid = null;
    private int mPeriodicSingleScanInterval = PERIODIC_SCAN_INTERVAL_MS;
    private long mLastPeriodicSingleScanTimeStamp = RESET_TIME_STAMP;
    private boolean mPnoScanStarted = false;
    private boolean mPeriodicScanTimerSet = false;
    // Device configs
    private boolean mEnableAutoJoinWhenAssociated;
    private boolean mWaitForFullBandScanResults = false;
    private boolean mUseSingleRadioChainScanResults = false;
    private int mFullScanMaxTxRate;
    private int mFullScanMaxRxRate;

    // PNO settings
    private int mCurrentConnectionBonus;
    private int mSameNetworkBonus;
    private int mSecureBonus;
    private int mBand5GHzBonus;
    private int mRssiScoreOffset;
    private int mRssiScoreSlope;
    private int mPnoScanIntervalMs;

    // BSSID blacklist
    @VisibleForTesting
    public static final int BSSID_BLACKLIST_THRESHOLD = 3;
    @VisibleForTesting
    public static final int BSSID_BLACKLIST_EXPIRE_TIME_MS = 5 * 60 * 1000;
    private static class BssidBlacklistStatus {
        // Number of times this BSSID has been rejected for association.
        public int counter;
        public boolean isBlacklisted;
        public long blacklistedTimeStamp = RESET_TIME_STAMP;
    }
    private Map<String, BssidBlacklistStatus> mBssidBlacklist =
            new HashMap<>();

    // Association failure reason codes
    @VisibleForTesting
    public static final int REASON_CODE_AP_UNABLE_TO_HANDLE_NEW_STA = 17;

    // A helper to log debugging information in the local log buffer, which can
    // be retrieved in bugreport.
    private void localLog(String log) {
        mLocalLog.log(log);
    }

    // A periodic/PNO scan will be rescheduled up to MAX_SCAN_RESTART_ALLOWED times
    // if the start scan command failed. An timer is used here to make it a deferred retry.
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

    /**
     * Handles 'onResult' callbacks for the Periodic, Single & Pno ScanListener.
     * Executes selection of potential network candidates, initiation of connection attempt to that
     * network.
     *
     * @return true - if a candidate is selected by WifiNetworkSelector
     *         false - if no candidate is selected by WifiNetworkSelector
     */
    private boolean handleScanResults(List<ScanDetail> scanDetails, String listenerName) {
        // Check if any blacklisted BSSIDs can be freed.
        refreshBssidBlacklist();

        if (mStateMachine.isSupplicantTransientState()) {
            localLog(listenerName
                    + " onResults: No network selection because supplicantTransientState is "
                    + mStateMachine.isSupplicantTransientState());
            return false;
        }

        localLog(listenerName + " onResults: start network selection");

        WifiConfiguration candidate =
                mNetworkSelector.selectNetwork(scanDetails, buildBssidBlacklist(), mWifiInfo,
                mStateMachine.isConnected(), mStateMachine.isDisconnected(),
                mUntrustedConnectionAllowed);
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
                if (mCarrierNetworkConfig.isCarrierEncryptionInfoAvailable()) {
                    mCarrierNetworkNotifier.handleScanResults(
                            mNetworkSelector.getFilteredScanDetailsForCarrierUnsavedNetworks(
                                    mCarrierNetworkConfig));
                }
            }
            return false;
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
            if (!mWifiEnabled || !mWifiConnectivityManagerEnabled) {
                clearScanDetails();
                mWaitForFullBandScanResults = false;
                return;
            }

            // We treat any full band scans (with DFS or not) as "full".
            boolean isFullBandScanResults =
                    results[0].getBandScanned() == WifiScanner.WIFI_BAND_BOTH_WITH_DFS
                            || results[0].getBandScanned() == WifiScanner.WIFI_BAND_BOTH;
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
            if (results.length > 0) {
                mWifiMetrics.incrementAvailableNetworksHistograms(mScanDetails,
                        isFullBandScanResults);
            }
            if (mNumScanResultsIgnoredDueToSingleRadioChain > 0) {
                Log.i(TAG, "Number of scan results ignored due to single radio chain scan: "
                        + mNumScanResultsIgnoredDueToSingleRadioChain);
            }
            boolean wasConnectAttempted = handleScanResults(mScanDetails, "AllSingleScanListener");
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
        }

        @Override
        public void onFullResult(ScanResult fullScanResult) {
            if (!mWifiEnabled || !mWifiConnectivityManagerEnabled) {
                return;
            }

            if (mDbg) {
                localLog("AllSingleScanListener onFullResult: " + fullScanResult.SSID
                        + " capabilities " + fullScanResult.capabilities);
            }

            // When the scan result has radio chain info, ensure we throw away scan results
            // not received with both radio chains (if |mUseSingleRadioChainScanResults| is false).
            if (!mUseSingleRadioChainScanResults
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
            wasConnectAttempted = handleScanResults(mScanDetails, "PnoScanListener");
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

    private class OnSavedNetworkUpdateListener implements
            WifiConfigManager.OnSavedNetworkUpdateListener {
        @Override
        public void onSavedNetworkAdded(int networkId) {
            updatePnoScan();
        }
        @Override
        public void onSavedNetworkEnabled(int networkId) {
            updatePnoScan();
        }
        @Override
        public void onSavedNetworkRemoved(int networkId) {
            updatePnoScan();
        }
        @Override
        public void onSavedNetworkUpdated(int networkId) {
            // User might have changed meteredOverride, so update capabilties
            mStateMachine.updateCapabilities();
            updatePnoScan();
        }
        @Override
        public void onSavedNetworkTemporarilyDisabled(int networkId, int disableReason) {
            if (disableReason == DISABLED_NO_INTERNET_TEMPORARY) return;
            mConnectivityHelper.removeNetworkIfCurrent(networkId);
        }
        @Override
        public void onSavedNetworkPermanentlyDisabled(int networkId, int disableReason) {
            // For DISABLED_NO_INTERNET_PERMANENT we do not need to remove the network
            // because supplicant won't be trying to reconnect. If this is due to a
            // preventAutomaticReconnect request from ConnectivityService, that service
            // will disconnect as appropriate.
            if (disableReason == DISABLED_NO_INTERNET_PERMANENT) return;
            mConnectivityHelper.removeNetworkIfCurrent(networkId);
            updatePnoScan();
        }
        private void updatePnoScan() {
            // Update the PNO scan network list when screen is off. Here we
            // rely on startConnectivityScan() to perform all the checks and clean up.
            if (!mScreenOn) {
                localLog("Saved networks updated");
                startConnectivityScan(false);
            }
        }
    }

    /**
     * WifiConnectivityManager constructor
     */
    WifiConnectivityManager(Context context, ScoringParams scoringParams,
            ClientModeImpl stateMachine,
            WifiInjector injector, WifiConfigManager configManager, WifiInfo wifiInfo,
            WifiNetworkSelector networkSelector, WifiConnectivityHelper connectivityHelper,
            WifiLastResortWatchdog wifiLastResortWatchdog, OpenNetworkNotifier openNetworkNotifier,
            CarrierNetworkNotifier carrierNetworkNotifier,
            CarrierNetworkConfig carrierNetworkConfig, WifiMetrics wifiMetrics, Looper looper,
            Clock clock, LocalLog localLog) {
        mStateMachine = stateMachine;
        mWifiInjector = injector;
        mConfigManager = configManager;
        mWifiInfo = wifiInfo;
        mNetworkSelector = networkSelector;
        mConnectivityHelper = connectivityHelper;
        mLocalLog = localLog;
        mWifiLastResortWatchdog = wifiLastResortWatchdog;
        mOpenNetworkNotifier = openNetworkNotifier;
        mCarrierNetworkNotifier = carrierNetworkNotifier;
        mCarrierNetworkConfig = carrierNetworkConfig;
        mWifiMetrics = wifiMetrics;
        mAlarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        mEventHandler = new Handler(looper);
        mClock = clock;
        mScoringParams = scoringParams;
        mConnectionAttemptTimeStamps = new LinkedList<>();

        mBand5GHzBonus = context.getResources().getInteger(
                R.integer.config_wifi_framework_5GHz_preference_boost_factor);
        mCurrentConnectionBonus = context.getResources().getInteger(
                R.integer.config_wifi_framework_current_network_boost);
        mSameNetworkBonus = context.getResources().getInteger(
                R.integer.config_wifi_framework_SAME_BSSID_AWARD);
        mSecureBonus = context.getResources().getInteger(
                R.integer.config_wifi_framework_SECURITY_AWARD);
        mRssiScoreOffset = context.getResources().getInteger(
                R.integer.config_wifi_framework_RSSI_SCORE_OFFSET);
        mRssiScoreSlope = context.getResources().getInteger(
                R.integer.config_wifi_framework_RSSI_SCORE_SLOPE);
        mEnableAutoJoinWhenAssociated = context.getResources().getBoolean(
                R.bool.config_wifi_framework_enable_associated_network_selection);
        mUseSingleRadioChainScanResults = context.getResources().getBoolean(
                R.bool.config_wifi_framework_use_single_radio_chain_scan_results_network_selection);


        mFullScanMaxTxRate = context.getResources().getInteger(
                R.integer.config_wifi_framework_max_tx_rate_for_full_scan);
        mFullScanMaxRxRate = context.getResources().getInteger(
                R.integer.config_wifi_framework_max_rx_rate_for_full_scan);

        mPnoScanIntervalMs = MOVING_PNO_SCAN_INTERVAL_MS;

        localLog("PNO settings:"
                + " min5GHzRssi " + mScoringParams.getEntryRssi(ScoringParams.BAND5)
                + " min24GHzRssi " + mScoringParams.getEntryRssi(ScoringParams.BAND2)
                + " currentConnectionBonus " + mCurrentConnectionBonus
                + " sameNetworkBonus " + mSameNetworkBonus
                + " secureNetworkBonus " + mSecureBonus
                + " initialScoreMax " + initialScoreMax());

        // Listen to WifiConfigManager network update events
        mConfigManager.setOnSavedNetworkUpdateListener(new OnSavedNetworkUpdateListener());
    }

    /** Returns maximum PNO score, before any awards/bonuses. */
    private int initialScoreMax() {
        return mRssiScoreSlope * (Math.max(mScoringParams.getGoodRssi(ScoringParams.BAND2),
                                           mScoringParams.getGoodRssi(ScoringParams.BAND5))
                                  + mRssiScoreOffset);
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
            return WifiScanner.WIFI_BAND_BOTH_WITH_DFS;
        } else {
            // Use channel list instead.
            return WifiScanner.WIFI_BAND_UNSPECIFIED;
        }
    }

    // Helper for setting the channels for connectivity scan when band is unspecified. Returns
    // false if we can't retrieve the info.
    private boolean setScanChannels(ScanSettings settings) {
        WifiConfiguration config = mStateMachine.getCurrentWifiConfiguration();

        if (config == null) {
            return false;
        }

        Set<Integer> freqs =
                mConfigManager.fetchChannelSetForNetworkForPartialScan(
                        config.networkId, CHANNEL_LIST_AGE_MS, mWifiInfo.getFrequency());

        if (freqs != null && freqs.size() != 0) {
            int index = 0;
            settings.channels = new WifiScanner.ChannelSpec[freqs.size()];
            for (Integer freq : freqs) {
                settings.channels[index++] = new WifiScanner.ChannelSpec(freq);
            }
            return true;
        } else {
            localLog("No scan channels for " + config.configKey() + ". Perform full band scan");
            return false;
        }
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

    // Start a single scan and set up the interval for next single scan.
    private void startPeriodicSingleScan() {
        long currentTimeStamp = mClock.getElapsedSinceBootMillis();

        if (mLastPeriodicSingleScanTimeStamp != RESET_TIME_STAMP) {
            long msSinceLastScan = currentTimeStamp - mLastPeriodicSingleScanTimeStamp;
            if (msSinceLastScan < PERIODIC_SCAN_INTERVAL_MS) {
                localLog("Last periodic single scan started " + msSinceLastScan
                        + "ms ago, defer this new scan request.");
                schedulePeriodicScanTimer(PERIODIC_SCAN_INTERVAL_MS - (int) msSinceLastScan);
                return;
            }
        }

        boolean isScanNeeded = true;
        boolean isFullBandScan = true;
        boolean isTrafficOverThreshold = mWifiInfo.txSuccessRate > mFullScanMaxTxRate
                || mWifiInfo.rxSuccessRate > mFullScanMaxRxRate;

        // If the WiFi traffic is heavy, only partial scan is proposed.
        if (mWifiState == WIFI_STATE_CONNECTED && isTrafficOverThreshold) {
            // If only partial scan is proposed and firmware roaming control is supported,
            // we will not issue any scan because firmware roaming will take care of
            // intra-SSID roam.
            if (mConnectivityHelper.isFirmwareRoamingSupported()) {
                localLog("No partial scan because firmware roaming is supported.");
                isScanNeeded = false;
            } else {
                localLog("No full band scan due to ongoing traffic");
                isFullBandScan = false;
            }
        }

        if (isScanNeeded) {
            mLastPeriodicSingleScanTimeStamp = currentTimeStamp;
            startSingleScan(isFullBandScan, WIFI_WORK_SOURCE);
            schedulePeriodicScanTimer(mPeriodicSingleScanInterval);

            // Set up the next scan interval in an exponential backoff fashion.
            mPeriodicSingleScanInterval *= 2;
            if (mPeriodicSingleScanInterval >  MAX_PERIODIC_SCAN_INTERVAL_MS) {
                mPeriodicSingleScanInterval = MAX_PERIODIC_SCAN_INTERVAL_MS;
            }
        } else {
            // Since we already skipped this scan, keep the same scan interval for next scan.
            schedulePeriodicScanTimer(mPeriodicSingleScanInterval);
        }
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
    private void startSingleScan(boolean isFullBandScan, WorkSource workSource) {
        if (!mWifiEnabled || !mWifiConnectivityManagerEnabled) {
            return;
        }

        mPnoScanListener.resetLowRssiNetworkRetryDelay();

        ScanSettings settings = new ScanSettings();
        if (!isFullBandScan) {
            if (!setScanChannels(settings)) {
                isFullBandScan = true;
            }
        }
        settings.type = WifiScanner.TYPE_HIGH_ACCURACY; // always do high accuracy scans.
        settings.band = getScanBand(isFullBandScan);
        settings.reportEvents = WifiScanner.REPORT_EVENT_FULL_SCAN_RESULT
                            | WifiScanner.REPORT_EVENT_AFTER_EACH_SCAN;
        settings.numBssidsPerScan = 0;

        List<ScanSettings.HiddenNetwork> hiddenNetworkList =
                mConfigManager.retrieveHiddenNetworkList();
        settings.hiddenNetworks =
                hiddenNetworkList.toArray(new ScanSettings.HiddenNetwork[hiddenNetworkList.size()]);

        SingleScanListener singleScanListener =
                new SingleScanListener(isFullBandScan);
        mScanner.startScan(settings, singleScanListener, workSource);
        mWifiMetrics.incrementConnectivityOneshotScanCount();
    }

    // Start a periodic scan when screen is on
    private void startPeriodicScan(boolean scanImmediately) {
        mPnoScanListener.resetLowRssiNetworkRetryDelay();

        // No connectivity scan if auto roaming is disabled.
        if (mWifiState == WIFI_STATE_CONNECTED && !mEnableAutoJoinWhenAssociated) {
            return;
        }

        // Due to b/28020168, timer based single scan will be scheduled
        // to provide periodic scan in an exponential backoff fashion.
        if (scanImmediately) {
            resetLastPeriodicSingleScanTimeStamp();
        }
        mPeriodicSingleScanInterval = PERIODIC_SCAN_INTERVAL_MS;
        startPeriodicSingleScan();
    }

    private static int deviceMobilityStateToPnoScanIntervalMs(@DeviceMobilityState int state) {
        switch (state) {
            case WifiManager.DEVICE_MOBILITY_STATE_UNKNOWN:
            case WifiManager.DEVICE_MOBILITY_STATE_LOW_MVMT:
            case WifiManager.DEVICE_MOBILITY_STATE_HIGH_MVMT:
                return MOVING_PNO_SCAN_INTERVAL_MS;
            case WifiManager.DEVICE_MOBILITY_STATE_STATIONARY:
                return STATIONARY_PNO_SCAN_INTERVAL_MS;
            default:
                return -1;
        }
    }

    /**
     * Alters the PNO scan interval based on the current device mobility state.
     * If the device is stationary, it will likely not find many new Wifi networks. Thus, increase
     * the interval between scans. Decrease the interval between scans if the device begins to move
     * again.
     * @param newState the new device mobility state
     */
    public void setDeviceMobilityState(@DeviceMobilityState int newState) {
        int newPnoScanIntervalMs = deviceMobilityStateToPnoScanIntervalMs(newState);
        if (newPnoScanIntervalMs < 0) {
            Log.e(TAG, "Invalid device mobility state: " + newState);
            return;
        }

        if (newPnoScanIntervalMs == mPnoScanIntervalMs) {
            if (mPnoScanStarted) {
                mWifiMetrics.logPnoScanStop();
                mWifiMetrics.enterDeviceMobilityState(newState);
                mWifiMetrics.logPnoScanStart();
            } else {
                mWifiMetrics.enterDeviceMobilityState(newState);
            }
        } else {
            mPnoScanIntervalMs = newPnoScanIntervalMs;
            Log.d(TAG, "PNO Scan Interval changed to " + mPnoScanIntervalMs + " ms.");

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
        List<PnoSettings.PnoNetwork> pnoNetworkList = mConfigManager.retrievePnoNetworkList();
        int listSize = pnoNetworkList.size();

        if (listSize == 0) {
            // No saved network
            localLog("No saved network for starting disconnected PNO.");
            return;
        }

        pnoSettings.networkList = new PnoSettings.PnoNetwork[listSize];
        pnoSettings.networkList = pnoNetworkList.toArray(pnoSettings.networkList);
        pnoSettings.min5GHzRssi = mScoringParams.getEntryRssi(ScoringParams.BAND5);
        pnoSettings.min24GHzRssi = mScoringParams.getEntryRssi(ScoringParams.BAND2);
        pnoSettings.initialScoreMax = initialScoreMax();
        pnoSettings.currentConnectionBonus = mCurrentConnectionBonus;
        pnoSettings.sameNetworkBonus = mSameNetworkBonus;
        pnoSettings.secureBonus = mSecureBonus;
        pnoSettings.band5GHzBonus = mBand5GHzBonus;

        // Initialize scan settings
        ScanSettings scanSettings = new ScanSettings();
        scanSettings.band = getScanBand();
        scanSettings.reportEvents = WifiScanner.REPORT_EVENT_NO_BATCH;
        scanSettings.numBssidsPerScan = 0;
        scanSettings.periodInMs = mPnoScanIntervalMs;

        mPnoScanListener.clearScanDetails();

        mScanner.startDisconnectedPnoScan(scanSettings, pnoSettings, mPnoScanListener);
        mPnoScanStarted = true;
        mWifiMetrics.logPnoScanStart();
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
                + mWifiConnectivityManagerEnabled);

        if (!mWifiEnabled || !mWifiConnectivityManagerEnabled) {
            return;
        }

        // Always stop outstanding connecivity scan if there is any
        stopConnectivityScan();

        // Don't start a connectivity scan while Wifi is in the transition
        // between connected and disconnected states.
        if (mWifiState != WIFI_STATE_CONNECTED && mWifiState != WIFI_STATE_DISCONNECTED) {
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
        stopPnoScan();
        mScanRestartCount = 0;
    }

    /**
     * Handler for screen state (on/off) changes
     */
    public void handleScreenStateChanged(boolean screenOn) {
        localLog("handleScreenStateChanged: screenOn=" + screenOn);

        mScreenOn = screenOn;

        mOpenNetworkNotifier.handleScreenStateChanged(screenOn);
        mCarrierNetworkNotifier.handleScreenStateChanged(screenOn);

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
     * Handler for WiFi state (connected/disconnected) changes
     */
    public void handleConnectionStateChanged(int state) {
        localLog("handleConnectionStateChanged: state=" + stateToString(state));

        mWifiState = state;

        // Reset BSSID of last connection attempt and kick off
        // the watchdog timer if entering disconnected state.
        if (mWifiState == WIFI_STATE_DISCONNECTED) {
            mLastConnectionAttemptBssid = null;
            scheduleWatchdogTimer();
            startConnectivityScan(SCAN_IMMEDIATELY);
        } else {
            startConnectivityScan(SCAN_ON_SCHEDULE);
        }
    }

    /**
     * Handler when a WiFi connection attempt ended.
     *
     * @param failureCode {@link WifiMetrics.ConnectionEvent} failure code.
     */
    public void handleConnectionAttemptEnded(int failureCode) {
        if (failureCode == WifiMetrics.ConnectionEvent.FAILURE_NONE) {
            String ssid = (mWifiInfo.getWifiSsid() == null)
                    ? null
                    : mWifiInfo.getWifiSsid().toString();
            mOpenNetworkNotifier.handleWifiConnected(ssid);
            mCarrierNetworkNotifier.handleWifiConnected(ssid);
        } else {
            mOpenNetworkNotifier.handleConnectionFailure();
            mCarrierNetworkNotifier.handleConnectionFailure();
        }
    }

    // Enable auto-join if we have any pending network request (trusted or untrusted) and no
    // specific network request in progress.
    private void checkStateAndEnable() {
        enable(!mSpecificNetworkRequestInProgress
                && (mUntrustedConnectionAllowed || mTrustedConnectionAllowed));
        startConnectivityScan(SCAN_IMMEDIATELY);
    }

    /**
     * Triggered when {@link WifiNetworkFactory} has a pending general network request.
     */
    public void setTrustedConnectionAllowed(boolean allowed) {
        localLog("setTrustedConnectionAllowed: allowed=" + allowed);

        if (mTrustedConnectionAllowed != allowed) {
            mTrustedConnectionAllowed = allowed;
            checkStateAndEnable();
        }
    }


    /**
     * Triggered when {@link UntrustedWifiNetworkFactory} has a pending ephemeral network request.
     */
    public void setUntrustedConnectionAllowed(boolean allowed) {
        localLog("setUntrustedConnectionAllowed: allowed=" + allowed);

        if (mUntrustedConnectionAllowed != allowed) {
            mUntrustedConnectionAllowed = allowed;
            checkStateAndEnable();
        }
    }

    /**
     * Triggered when {@link WifiNetworkFactory} is processing a specific network request.
     */
    public void setSpecificNetworkRequestInProgress(boolean inProgress) {
        localLog("setsetSpecificNetworkRequestInProgress : inProgress=" + inProgress);

        if (mSpecificNetworkRequestInProgress != inProgress) {
            mSpecificNetworkRequestInProgress = inProgress;
            checkStateAndEnable();
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
        localLog("prepareForForcedConnection: netId=" + netId);

        clearConnectionAttemptTimeStamps();
        clearBssidBlacklist();
    }

    /**
     * Handler for on-demand connectivity scan
     */
    public void forceConnectivityScan(WorkSource workSource) {
        localLog("forceConnectivityScan in request of " + workSource);

        mWaitForFullBandScanResults = true;
        startSingleScan(true, workSource);
    }

    /**
     * Update the BSSID blacklist when a BSSID is enabled or disabled
     *
     * @param bssid the bssid to be enabled/disabled
     * @param enable -- true enable the bssid
     *               -- false disable the bssid
     * @param reasonCode enable/disable reason code
     * @return true if blacklist is updated; false otherwise
     */
    private boolean updateBssidBlacklist(String bssid, boolean enable, int reasonCode) {
        // Remove the bssid from blacklist when it is enabled.
        if (enable) {
            return mBssidBlacklist.remove(bssid) != null;
        }

        // Do not update BSSID blacklist with information if this is the only
        // BSSID for its SSID. By ignoring it we will cause additional failures
        // which will trigger Watchdog.
        if (mWifiLastResortWatchdog.shouldIgnoreBssidUpdate(bssid)) {
            localLog("Ignore update Bssid Blacklist since Watchdog trigger is activated");
            return false;
        }

        // Update the bssid's blacklist status when it is disabled because of
        // association rejection.
        BssidBlacklistStatus status = mBssidBlacklist.get(bssid);
        if (status == null) {
            // First time for this BSSID
            status = new BssidBlacklistStatus();
            mBssidBlacklist.put(bssid, status);
        }

        status.blacklistedTimeStamp = mClock.getElapsedSinceBootMillis();
        status.counter++;
        if (!status.isBlacklisted) {
            if (status.counter >= BSSID_BLACKLIST_THRESHOLD
                    || reasonCode == REASON_CODE_AP_UNABLE_TO_HANDLE_NEW_STA) {
                status.isBlacklisted = true;
                return true;
            }
        }
        return false;
    }

    /**
     * Track whether a BSSID should be enabled or disabled for WifiNetworkSelector
     *
     * @param bssid the bssid to be enabled/disabled
     * @param enable -- true enable the bssid
     *               -- false disable the bssid
     * @param reasonCode enable/disable reason code
     * @return true if blacklist is updated; false otherwise
     */
    public boolean trackBssid(String bssid, boolean enable, int reasonCode) {
        localLog("trackBssid: " + (enable ? "enable " : "disable ") + bssid + " reason code "
                + reasonCode);

        if (bssid == null) {
            return false;
        }

        if (!updateBssidBlacklist(bssid, enable, reasonCode)) {
            return false;
        }

        // Blacklist was updated, so update firmware roaming configuration.
        updateFirmwareRoamingConfiguration();

        if (!enable) {
            // Disabling a BSSID can happen when connection to the AP was rejected.
            // We start another scan immediately so that WifiNetworkSelector can
            // give us another candidate to connect to.
            startConnectivityScan(SCAN_IMMEDIATELY);
        }

        return true;
    }

    /**
     * Check whether a bssid is disabled
     */
    @VisibleForTesting
    public boolean isBssidDisabled(String bssid) {
        BssidBlacklistStatus status = mBssidBlacklist.get(bssid);
        return status == null ? false : status.isBlacklisted;
    }

    /**
     * Compile and return a hashset of the blacklisted BSSIDs
     */
    private HashSet<String> buildBssidBlacklist() {
        HashSet<String> blacklistedBssids = new HashSet<String>();
        for (String bssid : mBssidBlacklist.keySet()) {
            if (isBssidDisabled(bssid)) {
                blacklistedBssids.add(bssid);
            }
        }

        return blacklistedBssids;
    }

    /**
     * Update firmware roaming configuration if the firmware roaming feature is supported.
     * Compile and write the BSSID blacklist only. TODO(b/36488259): SSID whitelist is always
     * empty for now.
     */
    private void updateFirmwareRoamingConfiguration() {
        if (!mConnectivityHelper.isFirmwareRoamingSupported()) {
            return;
        }

        int maxBlacklistSize = mConnectivityHelper.getMaxNumBlacklistBssid();
        if (maxBlacklistSize <= 0) {
            Log.wtf(TAG, "Invalid max BSSID blacklist size:  " + maxBlacklistSize);
            return;
        }

        ArrayList<String> blacklistedBssids = new ArrayList<String>(buildBssidBlacklist());
        int blacklistSize = blacklistedBssids.size();

        if (blacklistSize > maxBlacklistSize) {
            Log.wtf(TAG, "Attempt to write " + blacklistSize + " blacklisted BSSIDs, max size is "
                    + maxBlacklistSize);

            blacklistedBssids = new ArrayList<String>(blacklistedBssids.subList(0,
                    maxBlacklistSize));
            localLog("Trim down BSSID blacklist size from " + blacklistSize + " to "
                    + blacklistedBssids.size());
        }

        if (!mConnectivityHelper.setFirmwareRoamingConfiguration(blacklistedBssids,
                new ArrayList<String>())) {  // TODO(b/36488259): SSID whitelist management.
            localLog("Failed to set firmware roaming configuration.");
        }
    }

    /**
     * Refresh the BSSID blacklist
     *
     * Go through the BSSID blacklist and check if a BSSID has been blacklisted for
     * BSSID_BLACKLIST_EXPIRE_TIME_MS. If yes, re-enable it.
     */
    private void refreshBssidBlacklist() {
        if (mBssidBlacklist.isEmpty()) {
            return;
        }

        boolean updated = false;
        Iterator<BssidBlacklistStatus> iter = mBssidBlacklist.values().iterator();
        Long currentTimeStamp = mClock.getElapsedSinceBootMillis();

        while (iter.hasNext()) {
            BssidBlacklistStatus status = iter.next();
            if (status.isBlacklisted && ((currentTimeStamp - status.blacklistedTimeStamp)
                    >= BSSID_BLACKLIST_EXPIRE_TIME_MS)) {
                iter.remove();
                updated = true;
            }
        }

        if (updated) {
            updateFirmwareRoamingConfiguration();
        }
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
        mScanner.registerScanListener(mAllSingleScanListener);
    }


    /**
     * Clear the BSSID blacklist
     */
    private void clearBssidBlacklist() {
        mBssidBlacklist.clear();
        updateFirmwareRoamingConfiguration();
    }

    /**
     * Start WifiConnectivityManager
     */
    private void start() {
        if (mRunning) return;
        retrieveWifiScanner();
        mConnectivityHelper.getFirmwareRoamingInfo();
        clearBssidBlacklist();
        mRunning = true;
    }

    /**
     * Stop and reset WifiConnectivityManager
     */
    private void stop() {
        if (!mRunning) return;
        mRunning = false;
        stopConnectivityScan();
        clearBssidBlacklist();
        resetLastPeriodicSingleScanTimeStamp();
        mOpenNetworkNotifier.clearPendingNotification(true /* resetRepeatDelay */);
        mCarrierNetworkNotifier.clearPendingNotification(true /* resetRepeatDelay */);
        mLastConnectionAttemptBssid = null;
        mWaitForFullBandScanResults = false;
    }

    /**
     * Update WifiConnectivityManager running state
     *
     * Start WifiConnectivityManager only if both Wifi and WifiConnectivityManager
     * are enabled, otherwise stop it.
     */
    private void updateRunningState() {
        if (mWifiEnabled && mWifiConnectivityManagerEnabled) {
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

        mWifiEnabled = enable;
        updateRunningState();
    }

    /**
     * Turn on/off the WifiConnectivityManager at runtime
     */
    public void enable(boolean enable) {
        localLog("Set WiFiConnectivityManager " + (enable ? "enabled" : "disabled"));

        mWifiConnectivityManagerEnabled = enable;
        updateRunningState();
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
        mCarrierNetworkNotifier.dump(fd, pw, args);
        mCarrierNetworkConfig.dump(fd, pw, args);
    }
}
