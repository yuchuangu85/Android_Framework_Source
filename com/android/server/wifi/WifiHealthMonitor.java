/*
 * Copyright 2019 The Android Open Source Project
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

import static android.net.wifi.WifiInfo.DEFAULT_MAC_ADDRESS;

import static com.android.server.wifi.WifiScoreCard.TS_NONE;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.app.AlarmManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.MacAddress;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiManager.DeviceMobilityState;
import android.net.wifi.WifiScanner;
import android.os.Build;
import android.os.Handler;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.wifi.WifiScoreCard.MemoryStore;
import com.android.server.wifi.WifiScoreCard.MemoryStoreAccessBase;
import com.android.server.wifi.WifiScoreCard.PerNetwork;
import com.android.server.wifi.proto.WifiScoreCardProto.SoftwareBuildInfo;
import com.android.server.wifi.proto.WifiScoreCardProto.SystemInfoStats;
import com.android.server.wifi.proto.WifiStatsLog;
import com.android.server.wifi.proto.nano.WifiMetricsProto.HealthMonitorFailureStats;
import com.android.server.wifi.proto.nano.WifiMetricsProto.HealthMonitorMetrics;
import com.android.server.wifi.util.ScanResultUtil;

import com.google.protobuf.InvalidProtocolBufferException;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

import javax.annotation.concurrent.NotThreadSafe;

/**
 * Monitor and detect potential WiFi health issue when RSSI is sufficiently high.
 * There are two detections, daily detection and post-boot detection.
 * Post-boot detection is to detect abnormal scan/connection behavior change after device reboot
 * and/or SW build change.
 * Daily detection is to detect connection and other behavior changes especially after SW change.
 */

@NotThreadSafe
public class WifiHealthMonitor {
    private static final String TAG = "WifiHealthMonitor";
    private boolean mVerboseLoggingEnabled = false;
    public static final String DAILY_DETECTION_TIMER_TAG =
            "WifiHealthMonitor Schedule Daily Detection Timer";
    public static final String POST_BOOT_DETECTION_TIMER_TAG =
            "WifiHealthMonitor Schedule Post-Boot Detection Timer";
    // Package name of WiFi mainline module found from the following adb command
    // adb shell pm list packages --apex-only| grep wifi
    private static final String WIFI_APK_PACKAGE_NAME = "com.google.android.wifi";
    private static final String SYSTEM_INFO_DATA_NAME = "systemInfoData";
    // The time that device waits after device boot before triggering post-boot detection.
    // This needs be long enough so that memory read can complete before post-boot detection.
    private static final int POST_BOOT_DETECTION_WAIT_TIME_MS = 25_000;
    // The time interval between two daily detections
    private static final long DAILY_DETECTION_INTERVAL_MS = 24 * 3600_000;
    public static final int DAILY_DETECTION_HOUR = 23;
    private static final int DAILY_DETECTION_MIN = 00;
    private static final long MIN_WAIT_TIME_BEFORE_FIRST_DETECTION_MS = 100_000;
    // Max interval between pre-boot scan and post-boot scan to qualify post-boot scan detection
    private static final long MAX_INTERVAL_BETWEEN_TWO_SCAN_MS = 60_000;
    // The minimum number of BSSIDs that should be found during a normal scan to trigger detection
    // of an abnormal scan which happens either before or after the normal scan within a short time.
    // Minimum number of BSSIDs found at 2G with a normal scan
    private static final int MIN_NUM_BSSID_SCAN_2G = 2;
    // Minimum number of BSSIDs found above 2G with a normal scan
    private static final int MIN_NUM_BSSID_SCAN_ABOVE_2G = 2;
    // Minimum Tx speed in Mbps for disconnection stats collection
    static final int HEALTH_MONITOR_COUNT_TX_SPEED_MIN_MBPS = 54;
    // Minimum Tx packet per seconds for disconnection stats collection
    static final int HEALTH_MONITOR_MIN_TX_PACKET_PER_SEC = 4;

    private final Context mContext;
    private final WifiConfigManager mWifiConfigManager;
    private final WifiScoreCard mWifiScoreCard;
    private final Clock mClock;
    private final AlarmManager mAlarmManager;
    private final Handler mHandler;
    private final WifiNative mWifiNative;
    private final WifiInjector mWifiInjector;
    private final DeviceConfigFacade mDeviceConfigFacade;
    private WifiScanner mScanner;
    private MemoryStore mMemoryStore;
    private boolean mWifiEnabled;
    private WifiSystemInfoStats mWifiSystemInfoStats;
    private ScanStats mFirstScanStats = new ScanStats();
    // Detected significant increase of failure stats between daily data and historical data
    private FailureStats mFailureStatsIncrease = new FailureStats();
    // Detected significant decrease of failure stats between daily data and historical data
    private FailureStats mFailureStatsDecrease = new FailureStats();
    // Detected high failure stats from daily data without historical data
    private FailureStats mFailureStatsHigh = new FailureStats();
    private int mNumNetworkSufficientRecentStatsOnly = 0;
    private int mNumNetworkSufficientRecentPrevStats = 0;
    private boolean mHasNewDataForWifiMetrics = false;
    private int mDeviceMobilityState = WifiManager.DEVICE_MOBILITY_STATE_UNKNOWN;

    WifiHealthMonitor(Context context, WifiInjector wifiInjector, Clock clock,
            WifiConfigManager wifiConfigManager, WifiScoreCard wifiScoreCard, Handler handler,
            WifiNative wifiNative, String l2KeySeed, DeviceConfigFacade deviceConfigFacade) {
        mContext = context;
        mWifiInjector = wifiInjector;
        mClock = clock;
        mWifiConfigManager = wifiConfigManager;
        mWifiScoreCard = wifiScoreCard;
        mAlarmManager = (AlarmManager) mContext.getSystemService(Context.ALARM_SERVICE);
        mHandler = handler;
        mWifiNative = wifiNative;
        mDeviceConfigFacade = deviceConfigFacade;
        mWifiEnabled = false;
        mWifiSystemInfoStats = new WifiSystemInfoStats(l2KeySeed);
        mWifiConfigManager.addOnNetworkUpdateListener(new OnNetworkUpdateListener());
    }

    /**
     * Enable/Disable verbose logging.
     *
     * @param verbose true to enable and false to disable.
     */
    public void enableVerboseLogging(boolean verbose) {
        mVerboseLoggingEnabled = verbose;
    }

    private final AlarmManager.OnAlarmListener mDailyDetectionListener =
            new AlarmManager.OnAlarmListener() {
                public void onAlarm() {
                    dailyDetectionHandler();
                }
            };

    private final AlarmManager.OnAlarmListener mPostBootDetectionListener =
            new AlarmManager.OnAlarmListener() {
                public void onAlarm() {
                    postBootDetectionHandler();
                }
            };

    /**
     * Installs a memory store, request read for post-boot detection and set up detection alarms.
     */
    public void installMemoryStoreSetUpDetectionAlarm(@NonNull MemoryStore memoryStore) {
        if (mMemoryStore == null) {
            mMemoryStore = memoryStore;
            Log.i(TAG, "Installing MemoryStore");
        } else {
            mMemoryStore = memoryStore;
            Log.e(TAG, "Reinstalling MemoryStore");
        }
        requestReadForPostBootDetection();
        setFirstHealthDetectionAlarm();
        setPostBootDetectionAlarm();
    }

    /**
     * Set WiFi enable state.
     * During the off->on transition, retrieve scanner.
     * During the on->off transition, issue MemoryStore write to save data.
     */
    public void setWifiEnabled(boolean enable) {
        mWifiEnabled = enable;
        logd("Set WiFi " + (enable ? "enabled" : "disabled"));
        if (enable) {
            retrieveWifiScanner();
        } else {
            doWrites();
        }
    }

    /**
     * Issue MemoryStore write. This should be called from time to time
     * to save the state to persistent storage.
     */
    public void doWrites() {
        mWifiSystemInfoStats.writeToMemory();
    }

    /**
     * Set device mobility state to assist abnormal scan failure detection
     */
    public void setDeviceMobilityState(@DeviceMobilityState int newState) {
        logd("Device mobility state: " + newState);
        mDeviceMobilityState = newState;
        mWifiSystemInfoStats.setMobilityState(newState);
    }

    /**
     * Get the maximum scan RSSI valid time for scan RSSI search which is done by finding
     * the maximum RSSI found among all valid scan detail entries of each network's scanDetailCache
     * If a scanDetail was older than the returned value, it will not be considered valid.
     */
    public int getScanRssiValidTimeMs() {
        return (mDeviceMobilityState == WifiManager.DEVICE_MOBILITY_STATE_STATIONARY)
                ? mDeviceConfigFacade.getStationaryScanRssiValidTimeMs() :
                mDeviceConfigFacade.getNonstationaryScanRssiValidTimeMs();
    }

    /**
     * Issue read request to prepare for post-boot detection.
     */
    private void requestReadForPostBootDetection() {
        mWifiSystemInfoStats.readFromMemory();
        // Potential SW change detection may require to update all networks.
        // Thus read all networks.
        requestReadAllNetworks();
    }

    /**
     * Helper method to populate WifiScanner handle. This is done lazily because
     * WifiScanningService is started after WifiService.
     */
    private void retrieveWifiScanner() {
        if (mScanner != null) return;
        mScanner = mWifiInjector.getWifiScanner();
        if (mScanner == null) return;
        // Register for all single scan results
        mScanner.registerScanListener(new ScanListener());
    }

    /**
     * Handle scan results when scan results come back from WiFi scanner.
     */
    private void handleScanResults(List<ScanDetail> scanDetails) {
        ScanStats scanStats = mWifiSystemInfoStats.getCurrScanStats();
        scanStats.clear();
        scanStats.setLastScanTimeMs(mClock.getWallClockMillis());
        for (ScanDetail scanDetail : scanDetails) {
            ScanResult scanResult = scanDetail.getScanResult();
            if (scanResult.is24GHz()) {
                scanStats.incrementNumBssidLastScan2g();
            } else {
                scanStats.incrementNumBssidLastScanAbove2g();
            }
        }
        if (mFirstScanStats.getLastScanTimeMs() == TS_NONE) {
            mFirstScanStats.copy(scanStats);
        }
        mWifiSystemInfoStats.setChanged(true);
        logd(" 2G scanResult count: " + scanStats.getNumBssidLastScan2g()
                + ", Above2g scanResult count: " + scanStats.getNumBssidLastScanAbove2g());
    }

    private void setFirstHealthDetectionAlarm() {
        long currTimeMs = mClock.getWallClockMillis();
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(currTimeMs);
        calendar.set(Calendar.HOUR_OF_DAY, DAILY_DETECTION_HOUR);
        calendar.set(Calendar.MINUTE, DAILY_DETECTION_MIN);
        long targetTimeMs = calendar.getTimeInMillis();
        long waitTimeMs = targetTimeMs - currTimeMs;
        if (waitTimeMs < MIN_WAIT_TIME_BEFORE_FIRST_DETECTION_MS) {
            waitTimeMs += DAILY_DETECTION_INTERVAL_MS;
        }
        scheduleDailyDetectionAlarm(waitTimeMs);
    }

    private void scheduleDailyDetectionAlarm(long waitTimeMs) {
        mAlarmManager.set(AlarmManager.ELAPSED_REALTIME,
                mClock.getElapsedSinceBootMillis() + waitTimeMs,
                DAILY_DETECTION_TIMER_TAG,
                mDailyDetectionListener, mHandler);
    }

    private void setPostBootDetectionAlarm() {
        mAlarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                mClock.getElapsedSinceBootMillis() + POST_BOOT_DETECTION_WAIT_TIME_MS,
                POST_BOOT_DETECTION_TIMER_TAG,
                mPostBootDetectionListener, mHandler);
    }

    /**
     * Dump the internal state of WifiHealthMonitor.
     */
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("Dump of WifiHealthMonitor");
        pw.println("WifiHealthMonitor - Log Begin ----");
        pw.println("System Info Stats");
        pw.println(mWifiSystemInfoStats);
        pw.println("configured network connection stats");
        List<WifiConfiguration> configuredNetworks = mWifiConfigManager.getConfiguredNetworks();
        for (WifiConfiguration network : configuredNetworks) {
            if (isInvalidConfiguredNetwork(network)) continue;
            PerNetwork perNetwork = mWifiScoreCard.lookupNetwork(network.SSID);
            int cntName = WifiScoreCard.CNT_CONNECTION_ATTEMPT;
            if (perNetwork.getStatsCurrBuild().getCount(cntName) > 0
                    || perNetwork.getRecentStats().getCount(cntName) > 0) {
                pw.println(mWifiScoreCard.lookupNetwork(network.SSID));
            }
        }
        pw.println("networks with failure increase: ");
        pw.println(mFailureStatsIncrease);
        pw.println("networks with failure drop: ");
        pw.println(mFailureStatsDecrease);
        pw.println("networks with high failure without previous stats: ");
        pw.println(mFailureStatsHigh);
        pw.println("WifiHealthMonitor - Log End ----");
    }

    /**
     * Get current wifi mainline module long version code
     * @Return a non-zero value if version code is available, 0 otherwise.
     */
    public long getWifiStackVersion() {
        WifiSoftwareBuildInfo currentBuild = getWifiSystemInfoStats().getCurrSoftwareBuildInfo();
        return (currentBuild == null) ? 0 : currentBuild.getWifiStackVersion();
    }

    private synchronized void dailyDetectionHandler() {
        logd("Run daily detection");
        // Clear daily detection result
        mFailureStatsDecrease.clear();
        mFailureStatsIncrease.clear();
        mFailureStatsHigh.clear();
        mNumNetworkSufficientRecentStatsOnly = 0;
        mNumNetworkSufficientRecentPrevStats = 0;
        mHasNewDataForWifiMetrics = true;
        int connectionDurationSec = 0;
        // Set the alarm for the next day
        scheduleDailyDetectionAlarm(DAILY_DETECTION_INTERVAL_MS);
        List<WifiConfiguration> configuredNetworks = mWifiConfigManager.getConfiguredNetworks();
        for (WifiConfiguration network : configuredNetworks) {
            if (isInvalidConfiguredNetwork(network)) {
                continue;
            }
            PerNetwork perNetwork = mWifiScoreCard.lookupNetwork(network.SSID);

            int detectionFlag = perNetwork.dailyDetection(mFailureStatsDecrease,
                    mFailureStatsIncrease, mFailureStatsHigh);
            if (detectionFlag == WifiScoreCard.SUFFICIENT_RECENT_STATS_ONLY) {
                mNumNetworkSufficientRecentStatsOnly++;
            }
            if (detectionFlag == WifiScoreCard.SUFFICIENT_RECENT_PREV_STATS) {
                mNumNetworkSufficientRecentPrevStats++;
            }

            connectionDurationSec += perNetwork.getRecentStats().getCount(
                    WifiScoreCard.CNT_CONNECTION_DURATION_SEC);

            logd("before daily update: " + perNetwork);
            // Update historical stats with dailyStats and clear dailyStats
            perNetwork.updateAfterDailyDetection();
            logd("after daily update: " + perNetwork);
        }
        logd("total connection duration: " + connectionDurationSec);
        logd("#networks w/ sufficient recent stats: " + mNumNetworkSufficientRecentStatsOnly);
        logd("#networks w/ sufficient recent and prev stats: "
                + mNumNetworkSufficientRecentPrevStats);
        // Write metrics to statsd
        writeToWifiStatsLog();
        doWrites();
        mWifiScoreCard.doWrites();
    }

    private void writeToWifiStatsLog() {
        writeToWifiStatsLogPerStats(mFailureStatsIncrease,
                WifiStatsLog.WIFI_FAILURE_STAT_REPORTED__ABNORMALITY_TYPE__SIGNIFICANT_INCREASE);
        writeToWifiStatsLogPerStats(mFailureStatsDecrease,
                WifiStatsLog.WIFI_FAILURE_STAT_REPORTED__ABNORMALITY_TYPE__SIGNIFICANT_DECREASE);
        writeToWifiStatsLogPerStats(mFailureStatsHigh,
                WifiStatsLog.WIFI_FAILURE_STAT_REPORTED__ABNORMALITY_TYPE__SIMPLY_HIGH);
    }

    private void writeToWifiStatsLogPerStats(FailureStats failureStats, int abnormalityType) {
        int cntAssocRejection = failureStats.getCount(REASON_ASSOC_REJECTION);
        if (cntAssocRejection > 0) {
            WifiStatsLog.write(WifiStatsLog.WIFI_FAILURE_STAT_REPORTED, abnormalityType,
                    WifiStatsLog.WIFI_FAILURE_STAT_REPORTED__FAILURE_TYPE__FAILURE_ASSOCIATION_REJECTION,
                    cntAssocRejection);
        }
        int cntAssocTimeout = failureStats.getCount(REASON_ASSOC_TIMEOUT);
        if (cntAssocTimeout > 0) {
            WifiStatsLog.write(WifiStatsLog.WIFI_FAILURE_STAT_REPORTED, abnormalityType,
                    WifiStatsLog.WIFI_FAILURE_STAT_REPORTED__FAILURE_TYPE__FAILURE_ASSOCIATION_TIMEOUT,
                    cntAssocTimeout);
        }
        int cntAuthFailure = failureStats.getCount(REASON_AUTH_FAILURE);
        if (cntAuthFailure > 0) {
            WifiStatsLog.write(WifiStatsLog.WIFI_FAILURE_STAT_REPORTED, abnormalityType,
                    WifiStatsLog.WIFI_FAILURE_STAT_REPORTED__FAILURE_TYPE__FAILURE_AUTHENTICATION,
                    cntAuthFailure);
        }
        int cntConnectionFailure = failureStats.getCount(REASON_CONNECTION_FAILURE);
        if (cntConnectionFailure > 0) {
            WifiStatsLog.write(WifiStatsLog.WIFI_FAILURE_STAT_REPORTED, abnormalityType,
                    WifiStatsLog.WIFI_FAILURE_STAT_REPORTED__FAILURE_TYPE__FAILURE_CONNECTION,
                    cntConnectionFailure);
        }
        int cntDisconnectionNonlocal =  failureStats.getCount(REASON_DISCONNECTION_NONLOCAL);
        if (cntDisconnectionNonlocal > 0) {
            WifiStatsLog.write(WifiStatsLog.WIFI_FAILURE_STAT_REPORTED, abnormalityType,
                    WifiStatsLog.WIFI_FAILURE_STAT_REPORTED__FAILURE_TYPE__FAILURE_NON_LOCAL_DISCONNECTION,
                    cntDisconnectionNonlocal);
        }
        int cntShortConnectionNonlocal = failureStats.getCount(REASON_SHORT_CONNECTION_NONLOCAL);
        if (cntShortConnectionNonlocal > 0) {
            WifiStatsLog.write(WifiStatsLog.WIFI_FAILURE_STAT_REPORTED, abnormalityType,
                    WifiStatsLog.WIFI_FAILURE_STAT_REPORTED__FAILURE_TYPE__FAILURE_SHORT_CONNECTION_DUE_TO_NON_LOCAL_DISCONNECTION,
                    cntShortConnectionNonlocal);
        }
    }

    /**
     * Build HealthMonitor proto for WifiMetrics
     * @return counts of networks with significant connection failure stats if there is a new
     * detection, or a empty proto with default values if there is no new detection
     */
    public synchronized HealthMonitorMetrics buildProto() {
        if (!mHasNewDataForWifiMetrics) return null;
        HealthMonitorMetrics metrics = new HealthMonitorMetrics();
        metrics.failureStatsIncrease = failureStatsToProto(mFailureStatsIncrease);
        metrics.failureStatsDecrease = failureStatsToProto(mFailureStatsDecrease);
        metrics.failureStatsHigh = failureStatsToProto(mFailureStatsHigh);

        metrics.numNetworkSufficientRecentStatsOnly = mNumNetworkSufficientRecentStatsOnly;
        metrics.numNetworkSufficientRecentPrevStats = mNumNetworkSufficientRecentPrevStats;
        mHasNewDataForWifiMetrics = false;
        return metrics;
    }

    private HealthMonitorFailureStats failureStatsToProto(FailureStats failureStats) {
        HealthMonitorFailureStats stats = new HealthMonitorFailureStats();
        stats.cntAssocRejection = failureStats.getCount(REASON_ASSOC_REJECTION);
        stats.cntAssocTimeout = failureStats.getCount(REASON_ASSOC_TIMEOUT);
        stats.cntAuthFailure = failureStats.getCount(REASON_AUTH_FAILURE);
        stats.cntConnectionFailure = failureStats.getCount(REASON_CONNECTION_FAILURE);
        stats.cntDisconnectionNonlocal =
                failureStats.getCount(REASON_DISCONNECTION_NONLOCAL);
        stats.cntShortConnectionNonlocal =
                failureStats.getCount(REASON_SHORT_CONNECTION_NONLOCAL);
        return stats;
    }

    private boolean isInvalidConfiguredNetwork(WifiConfiguration config) {
        return (config == null || WifiManager.UNKNOWN_SSID.equals(config.SSID)
                || config.SSID == null);
    }

    private void postBootDetectionHandler() {
        logd("Run post-boot detection");
        postBootSwBuildCheck();
        mWifiSystemInfoStats.postBootAbnormalScanDetection(mFirstScanStats);
        logd(" postBootAbnormalScanDetection: " + mWifiSystemInfoStats.getScanFailure());
        // TODO: Check if scan is not empty but all high RSSI connection attempts failed
        //  while connection attempt with the same network succeeded before boot.
        doWrites();
    }

    private void postBootSwBuildCheck() {
        WifiSoftwareBuildInfo currSoftwareBuildInfo = extractCurrentSoftwareBuildInfo();
        if (currSoftwareBuildInfo == null) return;
        logd(currSoftwareBuildInfo.toString());

        mWifiSystemInfoStats.finishPendingRead();
        if (mWifiSystemInfoStats.getCurrSoftwareBuildInfo() == null) {
            logd("Miss current software build info from memory");
            mWifiSystemInfoStats.setCurrSoftwareBuildInfo(currSoftwareBuildInfo);
            return;
        }
        if (mWifiSystemInfoStats.detectSwBuildChange(currSoftwareBuildInfo)) {
            logd("Detect SW build change");
            updateAllNetworkAfterSwBuildChange();
            mWifiSystemInfoStats.updateBuildInfoAfterSwBuildChange(currSoftwareBuildInfo);
        } else {
            logd("Detect no SW build change");
        }
    }

    /**
     * Issue NetworkStats read request for all configured networks.
     */
    private void requestReadAllNetworks() {
        List<WifiConfiguration> configuredNetworks = mWifiConfigManager.getConfiguredNetworks();
        for (WifiConfiguration network : configuredNetworks) {
            if (isInvalidConfiguredNetwork(network)) {
                continue;
            }
            logd(network.SSID);
            WifiScoreCard.PerNetwork perNetwork = mWifiScoreCard.fetchByNetwork(network.SSID);
            if (perNetwork == null) {
                // This network is not in cache. Move it to cache and read it out from MemoryStore.
                mWifiScoreCard.lookupNetwork(network.SSID);
            } else {
                // This network is already in cache before memoryStore is stalled.
                mWifiScoreCard.requestReadNetwork(perNetwork);
            }
        }
    }

    /**
     * Update NetworkStats of all configured networks after a SW build change is detected
     */
    private void updateAllNetworkAfterSwBuildChange() {
        List<WifiConfiguration> configuredNetworks = mWifiConfigManager.getConfiguredNetworks();
        for (WifiConfiguration network : configuredNetworks) {
            if (isInvalidConfiguredNetwork(network)) {
                continue;
            }
            logd(network.SSID);
            WifiScoreCard.PerNetwork perNetwork = mWifiScoreCard.lookupNetwork(network.SSID);

            logd("before SW build update: " + perNetwork);
            perNetwork.updateAfterSwBuildChange();
            logd("after SW build update: " + perNetwork);
        }
    }

    /**
     * Extract current software build information from the running software.
     */
    private WifiSoftwareBuildInfo extractCurrentSoftwareBuildInfo() {
        if (!mWifiEnabled) {
            return null;
        }
        PackageManager packageManager = mContext.getPackageManager();
        long wifiStackVersion = 0;
        try {
            wifiStackVersion = packageManager.getPackageInfo(
                    WIFI_APK_PACKAGE_NAME, PackageManager.MATCH_APEX).getLongVersionCode();
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, " Hit PackageManager exception", e);
        }
        String osBuildVersion = replaceNullByEmptyString(Build.DISPLAY);
        if (mWifiNative == null) {
            return null;
        }
        String driverVersion = replaceNullByEmptyString(mWifiNative.getDriverVersion());
        String firmwareVersion = replaceNullByEmptyString(mWifiNative.getFirmwareVersion());
        return (new WifiSoftwareBuildInfo(osBuildVersion,
                wifiStackVersion, driverVersion, firmwareVersion));
    }

    private String replaceNullByEmptyString(String str) {
        return str == null ? "" : str;
    }

    /**
     * Clears the internal state.
     * This is called in response to a factoryReset call from Settings.
     */
    public void clear() {
        mWifiSystemInfoStats.clearAll();
    }

    public static final int REASON_NO_FAILURE = -1;
    public static final int REASON_ASSOC_REJECTION = 0;
    public static final int REASON_ASSOC_TIMEOUT = 1;
    public static final int REASON_AUTH_FAILURE = 2;
    public static final int REASON_CONNECTION_FAILURE = 3;
    public static final int REASON_DISCONNECTION_NONLOCAL = 4;
    public static final int REASON_SHORT_CONNECTION_NONLOCAL = 5;
    public static final int NUMBER_FAILURE_REASON_CODE = 6;
    public static final String[] FAILURE_REASON_NAME = {
            "association rejection failure",
            "association timeout failure",
            "authentication failure",
            "connection failure",
            "disconnection",
            "short connection"
    };
    @IntDef(prefix = { "REASON_" }, value = {
        REASON_NO_FAILURE,
        REASON_ASSOC_REJECTION,
        REASON_ASSOC_TIMEOUT,
        REASON_AUTH_FAILURE,
        REASON_CONNECTION_FAILURE,
        REASON_DISCONNECTION_NONLOCAL,
        REASON_SHORT_CONNECTION_NONLOCAL
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface FailureReasonCode {}

    /**
     * A class maintaining the number of networks with high failure rate or
     * with a significant change of failure rate
     */
    public static class FailureStats {
        private final int[] mCount = new int[NUMBER_FAILURE_REASON_CODE];
        void clear() {
            for (int i = 0; i < NUMBER_FAILURE_REASON_CODE; i++) {
                mCount[i] = 0;
            }
        }

        int getCount(@FailureReasonCode int reasonCode) {
            return mCount[reasonCode];
        }

        void setCount(@FailureReasonCode int reasonCode, int cnt) {
            mCount[reasonCode] = cnt;
        }

        void incrementCount(@FailureReasonCode int reasonCode) {
            mCount[reasonCode]++;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < NUMBER_FAILURE_REASON_CODE; i++) {
                if (mCount[i] == 0) continue;
                sb.append(FAILURE_REASON_NAME[i]).append(": ").append(mCount[i]).append(" ");
            }
            return sb.toString();
        }
    }
    /**
     * A class maintaining current OS, Wifi APK, Wifi driver and firmware build version information.
     */
    final class WifiSoftwareBuildInfo {
        private String mOsBuildVersion;
        private long mWifiStackVersion;
        private String mWifiDriverVersion;
        private String mWifiFirmwareVersion;
        WifiSoftwareBuildInfo(@NonNull String osBuildVersion, long wifiStackVersion,
                @NonNull String wifiDriverVersion, @NonNull String wifiFirmwareVersion) {
            mOsBuildVersion = osBuildVersion;
            mWifiStackVersion = wifiStackVersion;
            mWifiDriverVersion = wifiDriverVersion;
            mWifiFirmwareVersion = wifiFirmwareVersion;
        }
        WifiSoftwareBuildInfo(@NonNull WifiSoftwareBuildInfo wifiSoftwareBuildInfo) {
            mOsBuildVersion = wifiSoftwareBuildInfo.getOsBuildVersion();
            mWifiStackVersion = wifiSoftwareBuildInfo.getWifiStackVersion();
            mWifiDriverVersion = wifiSoftwareBuildInfo.getWifiDriverVersion();
            mWifiFirmwareVersion = wifiSoftwareBuildInfo.getWifiFirmwareVersion();
        }
        String getOsBuildVersion() {
            return mOsBuildVersion;
        }
        long getWifiStackVersion() {
            return mWifiStackVersion;
        }
        String getWifiDriverVersion() {
            return mWifiDriverVersion;
        }
        String getWifiFirmwareVersion() {
            return mWifiFirmwareVersion;
        }
        @Override
        public boolean equals(Object otherObj) {
            if (this == otherObj) {
                return true;
            }
            if (!(otherObj instanceof WifiSoftwareBuildInfo)) {
                return false;
            }
            if (otherObj == null) {
                return false;
            }
            WifiSoftwareBuildInfo other = (WifiSoftwareBuildInfo) otherObj;
            return mOsBuildVersion.equals(other.getOsBuildVersion())
                    && mWifiStackVersion == other.getWifiStackVersion()
                    && mWifiDriverVersion.equals(other.getWifiDriverVersion())
                    && mWifiFirmwareVersion.equals(other.getWifiFirmwareVersion());
        }
        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("OS build version: ");
            sb.append(mOsBuildVersion);
            sb.append(" Wifi stack version: ");
            sb.append(mWifiStackVersion);
            sb.append(" Wifi driver version: ");
            sb.append(mWifiDriverVersion);
            sb.append(" Wifi firmware version: ");
            sb.append(mWifiFirmwareVersion);
            return sb.toString();
        }
    }

    /**
     * A class maintaining various WiFi system information and statistics.
     */
    final class WifiSystemInfoStats extends MemoryStoreAccessBase {
        private WifiSoftwareBuildInfo mCurrSoftwareBuildInfo;
        private WifiSoftwareBuildInfo mPrevSoftwareBuildInfo;
        private ScanStats mCurrScanStats = new ScanStats();
        private ScanStats mPrevScanStats = new ScanStats();
        private int mScanFailure;
        private @DeviceMobilityState int mMobilityState;
        private boolean mChanged = false;
        WifiSystemInfoStats(String l2KeySeed) {
            super(WifiScoreCard.computeHashLong(
                    "", MacAddress.fromString(DEFAULT_MAC_ADDRESS), l2KeySeed));
        }

        ScanStats getCurrScanStats() {
            return mCurrScanStats;
        }

        void setChanged(boolean changed) {
            mChanged = changed;
        }

        void setCurrSoftwareBuildInfo(WifiSoftwareBuildInfo currSoftwareBuildInfo) {
            mCurrSoftwareBuildInfo = currSoftwareBuildInfo;
            mChanged = true;
        }

        void setMobilityState(@DeviceMobilityState int mobilityState) {
            mMobilityState = mobilityState;
        }

        WifiSoftwareBuildInfo getCurrSoftwareBuildInfo() {
            return mCurrSoftwareBuildInfo;
        }

        WifiSoftwareBuildInfo getPrevSoftwareBuildInfo() {
            return mPrevSoftwareBuildInfo;
        }

        void clearAll() {
            mCurrSoftwareBuildInfo = null;
            mPrevSoftwareBuildInfo = null;
            mCurrScanStats.clear();
            mPrevScanStats.clear();
            mChanged = true;
        }

        /**
         * Detect if there is a SW build change by comparing current SW build version vs. SW build
         * version previously saved in MemoryStore.
         * @param currSoftwareBuildInfo is current SW build info derived from running SW
         * @return true if a SW build change is detected, false if no change is detected.
         */
        boolean detectSwBuildChange(@NonNull WifiSoftwareBuildInfo currSoftwareBuildInfo) {
            if (mCurrSoftwareBuildInfo == null) {
                return false;
            }

            logd(" from Memory: " + mCurrSoftwareBuildInfo);
            logd(" from SW: " + currSoftwareBuildInfo);
            return (!mCurrSoftwareBuildInfo.equals(currSoftwareBuildInfo));
        }

        void updateBuildInfoAfterSwBuildChange(@NonNull WifiSoftwareBuildInfo currBuildInfo) {
            mPrevSoftwareBuildInfo = new WifiSoftwareBuildInfo(mCurrSoftwareBuildInfo);
            mCurrSoftwareBuildInfo = new WifiSoftwareBuildInfo(currBuildInfo);
            mChanged = true;
        }

        void readFromMemory() {
            if (mMemoryStore != null) {
                mMemoryStore.read(getL2Key(), SYSTEM_INFO_DATA_NAME,
                        (value) -> readBackListener(value));
            }
        }

        // Read may not be completed in theory when finishPendingRead() is called.
        // Currently it relies on the fact that memory read is issued right after boot complete
        // while finishPendingRead() is called only POST_BOOT_DETECTION_WAIT_TIME_MS after that.
        private void finishPendingRead() {
            final byte[] serialized = finishPendingReadBytes();
            if (serialized == null) {
                logd("Fail to read systemInfoStats from memory");
                return;
            }
            SystemInfoStats systemInfoStats;
            try {
                systemInfoStats = SystemInfoStats.parseFrom(serialized);
            } catch (InvalidProtocolBufferException e) {
                Log.e(TAG, "Failed to deserialize", e);
                return;
            }
            readFromMemory(systemInfoStats);
        }

        private void readFromMemory(@NonNull SystemInfoStats systemInfoStats) {
            if (systemInfoStats.hasCurrSoftwareBuildInfo()) {
                mCurrSoftwareBuildInfo = fromSoftwareBuildInfo(
                        systemInfoStats.getCurrSoftwareBuildInfo());
            }
            if (systemInfoStats.hasPrevSoftwareBuildInfo()) {
                mPrevSoftwareBuildInfo = fromSoftwareBuildInfo(
                        systemInfoStats.getPrevSoftwareBuildInfo());
            }
            if (systemInfoStats.hasNumBssidLastScan2G()) {
                mPrevScanStats.setNumBssidLastScan2g(systemInfoStats.getNumBssidLastScan2G());
            }
            if (systemInfoStats.hasNumBssidLastScanAbove2G()) {
                mPrevScanStats.setNumBssidLastScanAbove2g(systemInfoStats
                        .getNumBssidLastScanAbove2G());
            }
            if (systemInfoStats.hasLastScanTimeMs()) {
                mPrevScanStats.setLastScanTimeMs(systemInfoStats.getLastScanTimeMs());
            }
        }

        void writeToMemory() {
            if (mMemoryStore == null || !mChanged) return;
            byte[] serialized = toSystemInfoStats().toByteArray();
            mMemoryStore.write(getL2Key(), SYSTEM_INFO_DATA_NAME, serialized);
            mChanged = false;
        }

        SystemInfoStats toSystemInfoStats() {
            SystemInfoStats.Builder builder = SystemInfoStats.newBuilder();
            if (mCurrSoftwareBuildInfo != null) {
                builder.setCurrSoftwareBuildInfo(toSoftwareBuildInfo(mCurrSoftwareBuildInfo));
            }
            if (mPrevSoftwareBuildInfo != null) {
                builder.setPrevSoftwareBuildInfo(toSoftwareBuildInfo(mPrevSoftwareBuildInfo));
            }
            builder.setLastScanTimeMs(mCurrScanStats.getLastScanTimeMs());
            builder.setNumBssidLastScan2G(mCurrScanStats.getNumBssidLastScan2g());
            builder.setNumBssidLastScanAbove2G(mCurrScanStats.getNumBssidLastScanAbove2g());
            return builder.build();
        }

        private SoftwareBuildInfo toSoftwareBuildInfo(
                @NonNull WifiSoftwareBuildInfo softwareBuildInfo) {
            SoftwareBuildInfo.Builder builder = SoftwareBuildInfo.newBuilder();
            builder.setOsBuildVersion(softwareBuildInfo.getOsBuildVersion());
            builder.setWifiStackVersion(softwareBuildInfo.getWifiStackVersion());
            builder.setWifiDriverVersion(softwareBuildInfo.getWifiDriverVersion());
            builder.setWifiFirmwareVersion(softwareBuildInfo.getWifiFirmwareVersion());
            return builder.build();
        }

        WifiSoftwareBuildInfo fromSoftwareBuildInfo(
                @NonNull SoftwareBuildInfo softwareBuildInfo) {
            String osBuildVersion = softwareBuildInfo.hasOsBuildVersion()
                    ? softwareBuildInfo.getOsBuildVersion() : "NA";
            long stackVersion = softwareBuildInfo.hasWifiStackVersion()
                    ? softwareBuildInfo.getWifiStackVersion() : 0;
            String driverVersion = softwareBuildInfo.hasWifiDriverVersion()
                    ? softwareBuildInfo.getWifiDriverVersion() : "NA";
            String firmwareVersion = softwareBuildInfo.hasWifiFirmwareVersion()
                    ? softwareBuildInfo.getWifiFirmwareVersion() : "NA";
            return new WifiSoftwareBuildInfo(osBuildVersion, stackVersion,
                    driverVersion, firmwareVersion);
        }
        /**
         *  Detect pre-boot or post-boot detection failure.
         *  @return 0 if no failure is found or a positive integer if failure is found where
         *  b0 for pre-boot 2G scan failure
         *  b1 for pre-boot Above2g scan failure
         *  b2 for post-boot 2G scan failure
         *  b3 for post-boot Above2g scan failure
         */
        void postBootAbnormalScanDetection(ScanStats firstScanStats) {
            long preBootScanTimeMs = mPrevScanStats.getLastScanTimeMs();
            long postBootScanTimeMs = firstScanStats.getLastScanTimeMs();
            logd(" preBootScanTimeMs: " + preBootScanTimeMs);
            logd(" postBootScanTimeMs: " + postBootScanTimeMs);
            int preBootNumBssid2g = mPrevScanStats.getNumBssidLastScan2g();
            int preBootNumBssidAbove2g = mPrevScanStats.getNumBssidLastScanAbove2g();
            int postBootNumBssid2g = firstScanStats.getNumBssidLastScan2g();
            int postBootNumBssidAbove2g = firstScanStats.getNumBssidLastScanAbove2g();
            logd(" preBootScan 2G count: " + preBootNumBssid2g
                    + ", Above2G count: " + preBootNumBssidAbove2g);
            logd(" postBootScan 2G count: " + postBootNumBssid2g
                    + ", Above2G count: " + postBootNumBssidAbove2g);
            mScanFailure = 0;
            if (postBootScanTimeMs == TS_NONE || preBootScanTimeMs == TS_NONE) return;
            if ((postBootScanTimeMs - preBootScanTimeMs) > MAX_INTERVAL_BETWEEN_TWO_SCAN_MS) {
                return;
            }
            if (preBootNumBssid2g == 0 && postBootNumBssid2g >= MIN_NUM_BSSID_SCAN_2G) {
                mScanFailure += 1;
            }
            if (preBootNumBssidAbove2g == 0 && postBootNumBssidAbove2g
                    >= MIN_NUM_BSSID_SCAN_ABOVE_2G) {
                mScanFailure += 2;
            }
            if (postBootNumBssid2g == 0 && preBootNumBssid2g >= MIN_NUM_BSSID_SCAN_2G) {
                mScanFailure += 4;
            }
            if (postBootNumBssidAbove2g == 0 && preBootNumBssidAbove2g
                    >= MIN_NUM_BSSID_SCAN_ABOVE_2G) {
                mScanFailure += 8;
            }
        }

        int getScanFailure() {
            return mScanFailure;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            if (mCurrSoftwareBuildInfo != null) {
                sb.append("current SW build: ");
                sb.append(mCurrSoftwareBuildInfo);
            }
            if (mPrevSoftwareBuildInfo != null) {
                sb.append("\n");
                sb.append("previous SW build: ");
                sb.append(mPrevSoftwareBuildInfo);
            }
            sb.append("\n");
            sb.append("currScanStats: ");
            sb.append(mCurrScanStats);
            sb.append("\n");
            sb.append("prevScanStats: ");
            sb.append(mPrevScanStats);
            return sb.toString();
        }
    }

    final class ScanStats {
        private long mLastScanTimeMs = TS_NONE;
        private int mNumBssidLastScan2g;
        private int mNumBssidLastScanAbove2g;
        void copy(ScanStats source) {
            mLastScanTimeMs = source.mLastScanTimeMs;
            mNumBssidLastScan2g = source.mNumBssidLastScan2g;
            mNumBssidLastScanAbove2g = source.mNumBssidLastScanAbove2g;
        }
        void setLastScanTimeMs(long lastScanTimeMs) {
            mLastScanTimeMs = lastScanTimeMs;
        }
        void setNumBssidLastScan2g(int numBssidLastScan2g) {
            mNumBssidLastScan2g = numBssidLastScan2g;
        }
        void setNumBssidLastScanAbove2g(int numBssidLastScanAbove2g) {
            mNumBssidLastScanAbove2g = numBssidLastScanAbove2g;
        }
        long getLastScanTimeMs() {
            return mLastScanTimeMs;
        }
        int getNumBssidLastScan2g() {
            return mNumBssidLastScan2g;
        }
        int getNumBssidLastScanAbove2g() {
            return mNumBssidLastScanAbove2g;
        }
        void incrementNumBssidLastScan2g() {
            mNumBssidLastScan2g++;
        }
        void incrementNumBssidLastScanAbove2g() {
            mNumBssidLastScanAbove2g++;
        }
        void clear() {
            mLastScanTimeMs = TS_NONE;
            mNumBssidLastScan2g = 0;
            mNumBssidLastScanAbove2g = 0;
        }
        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("last scan time: ");
            sb.append(mLastScanTimeMs);
            sb.append(" APs found at 2G: ");
            sb.append(mNumBssidLastScan2g);
            sb.append(" APs found above 2g: ");
            sb.append(mNumBssidLastScanAbove2g);
            return sb.toString();
        }
    }

    @VisibleForTesting
    WifiSystemInfoStats getWifiSystemInfoStats() {
        return mWifiSystemInfoStats;
    }

    private void logd(String string) {
        if (mVerboseLoggingEnabled) {
            Log.d(TAG, string);
        }
    }

    /**
     * Listener for config manager network config related events.
     */
    private class OnNetworkUpdateListener implements
            WifiConfigManager.OnNetworkUpdateListener {

        @Override
        public void onNetworkAdded(WifiConfiguration config) {
            if (config == null) return;
            mWifiScoreCard.lookupNetwork(config.SSID);
        }

        @Override
        public void onNetworkEnabled(WifiConfiguration config) {
        }

        @Override
        public void onNetworkPermanentlyDisabled(WifiConfiguration config, int disableReason) {
        }

        @Override
        public void onNetworkRemoved(WifiConfiguration config) {
            if (config == null || (config.fromWifiNetworkSuggestion && !config.isPasspoint())) {
                // If a suggestion non-passpoint network is removed from wifiConfigManager do not
                // remove the ScoreCard. That will be removed when suggestion is removed.
                return;
            }
            mWifiScoreCard.removeNetwork(config.SSID);
        }

        @Override
        public void onNetworkTemporarilyDisabled(WifiConfiguration config, int disableReason) {
        }

        @Override
        public void onNetworkUpdated(WifiConfiguration newConfig, WifiConfiguration oldConfig) {
        }
    }

    /**
     *  Scan listener for any full band scan.
     */
    private class ScanListener implements WifiScanner.ScanListener {
        private List<ScanDetail> mScanDetails = new ArrayList<ScanDetail>();

        public void clearScanDetails() {
            mScanDetails.clear();
        }

        @Override
        public void onSuccess() {
        }

        @Override
        public void onFailure(int reason, String description) {
            logd("registerScanListener onFailure:"
                    + " reason: " + reason + " description: " + description);
        }

        @Override
        public void onPeriodChanged(int periodInMs) {
        }

        @Override
        public void onResults(WifiScanner.ScanData[] results) {
            if (!mWifiEnabled || results == null || results.length == 0) {
                clearScanDetails();
                return;
            }

            if (WifiScanner.isFullBandScan(results[0].getBandScanned(), true)) {
                handleScanResults(mScanDetails);
            }
            clearScanDetails();
        }

        @Override
        public void onFullResult(ScanResult fullScanResult) {
            if (!mWifiEnabled) {
                return;
            }
            mScanDetails.add(ScanResultUtil.toScanDetail(fullScanResult));
        }
    }
}
