/*
 * Copyright (C) 2019 The Android Open Source Project
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
import android.database.ContentObserver;
import android.net.MacAddress;
import android.net.wifi.WifiInfo;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.wifi.util.TimedQuotaManager;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Tracks state that decides if a link probe should be performed. If so, trigger a link probe to
 * evaluate connection quality.
 */
public class LinkProbeManager {
    private static final String TAG = "WifiLinkProbeManager";

    private static final int WIFI_LINK_PROBING_ENABLED_DEFAULT = 1; // 1 = enabled

    // TODO(112029045): Use constants from ScoringParams instead
    @VisibleForTesting static final int RSSI_THRESHOLD = -70;
    @VisibleForTesting static final int LINK_SPEED_THRESHOLD_MBPS = 15; // in megabits per second
    /** Minimum delay before probing after the last probe. */
    @VisibleForTesting static final long DELAY_BETWEEN_PROBES_MS = 6000;
    /** Minimum delay before probing after screen turned on. */
    @VisibleForTesting static final long SCREEN_ON_DELAY_MS = 6000;
    /**
     * Minimum delay before probing after last increase of the Tx success counter (which indicates
     * that a data frame (i.e. not counting management frame) was successfully transmitted).
     */
    @VisibleForTesting static final long DELAY_AFTER_TX_SUCCESS_MS = 6000;

    @VisibleForTesting static final long MAX_PROBE_COUNT_IN_PERIOD =
            WifiMetrics.MAX_LINK_PROBE_STA_EVENTS;
    @VisibleForTesting static final long PERIOD_MILLIS = Duration.ofDays(1).toMillis();

    @VisibleForTesting static final int[] EXPERIMENT_DELAYS_MS = {3000, 6000, 9000, 12000, 15000};
    @VisibleForTesting static final int[] EXPERIMENT_RSSIS = {-65, -70, -75};
    @VisibleForTesting static final int[] EXPERIMENT_LINK_SPEEDS = {10, 15, 20};
    private List<Experiment> mExperiments = new ArrayList<>();

    private final Clock mClock;
    private final WifiNative mWifiNative;
    private final WifiMetrics mWifiMetrics;
    private final FrameworkFacade mFrameworkFacade;
    private final Context mContext;

    private boolean mLinkProbingSupported;
    private boolean mLinkProbingEnabled = false;

    private boolean mVerboseLoggingEnabled = false;

    /**
     * Tracks the last timestamp when a link probe was triggered. Link probing only occurs when at
     * least {@link #DELAY_BETWEEN_PROBES_MS} has passed since the last link probe.
     */
    private long mLastLinkProbeTimestampMs;
    /**
     * Tracks the last timestamp when {@link WifiInfo#txSuccess} was increased i.e. the last time a
     * Tx was successful. Link probing only occurs when at least {@link #DELAY_AFTER_TX_SUCCESS_MS}
     * has passed since the last Tx success.
     * This is also reset to the current time when {@link #resetOnNewConnection()} is called, so
     * that a link probe only occurs at least {@link #DELAY_AFTER_TX_SUCCESS_MS} after a new
     * connection is made.
     */
    private long mLastTxSuccessIncreaseTimestampMs;
    /**
     * Stores the last value of {@link WifiInfo#txSuccess}. The current value of
     * {@link WifiInfo#txSuccess} is compared against the last value to determine whether there was
     * a successful Tx.
     */
    private long mLastTxSuccessCount;
    /**
     * Tracks the last timestamp when the screen turned on. Link probing only occurs when at least
     * {@link #SCREEN_ON_DELAY_MS} has passed since the last time the screen was turned on.
     */
    private long mLastScreenOnTimestampMs;
    private final TimedQuotaManager mTimedQuotaManager;

    public LinkProbeManager(Clock clock, WifiNative wifiNative, WifiMetrics wifiMetrics,
            FrameworkFacade frameworkFacade, Looper looper, Context context) {
        mClock = clock;
        mWifiNative = wifiNative;
        mWifiMetrics = wifiMetrics;
        mFrameworkFacade = frameworkFacade;
        mContext = context;
        mLinkProbingSupported = mContext.getResources()
                .getBoolean(R.bool.config_wifi_link_probing_supported);
        mTimedQuotaManager = new TimedQuotaManager(clock, MAX_PROBE_COUNT_IN_PERIOD, PERIOD_MILLIS);

        if (mLinkProbingSupported) {
            mFrameworkFacade.registerContentObserver(mContext, Settings.Global.getUriFor(
                    Settings.Global.WIFI_LINK_PROBING_ENABLED), false,
                    new ContentObserver(new Handler(looper)) {
                        @Override
                        public void onChange(boolean selfChange) {
                            updateLinkProbeSetting();
                        }
                    });
            updateLinkProbeSetting();

            resetOnNewConnection();
            resetOnScreenTurnedOn();
        }

        initExperiments();
    }

    private void updateLinkProbeSetting() {
        int flag = mFrameworkFacade.getIntegerSetting(mContext,
                Settings.Global.WIFI_LINK_PROBING_ENABLED,
                WIFI_LINK_PROBING_ENABLED_DEFAULT);
        mLinkProbingEnabled = (flag == 1);
    }

    /** enables/disables wifi verbose logging */
    public void enableVerboseLogging(boolean enable) {
        mVerboseLoggingEnabled = enable;
    }

    /** dumps internal state */
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("Dump of LinkProbeManager");
        pw.println("LinkProbeManager - link probing supported by device: " + mLinkProbingSupported);
        pw.println("LinkProbeManager - link probing feature flag enabled: " + mLinkProbingEnabled);
        pw.println("LinkProbeManager - mLastLinkProbeTimestampMs: " + mLastLinkProbeTimestampMs);
        pw.println("LinkProbeManager - mLastTxSuccessIncreaseTimestampMs: "
                + mLastTxSuccessIncreaseTimestampMs);
        pw.println("LinkProbeManager - mLastTxSuccessCount: " + mLastTxSuccessCount);
        pw.println("LinkProbeManager - mLastScreenOnTimestampMs: " + mLastScreenOnTimestampMs);
        pw.println("LinkProbeManager - mTimedQuotaManager: " + mTimedQuotaManager);
    }

    /**
     * When connecting to a new network, reset internal state.
     */
    public void resetOnNewConnection() {
        mExperiments.forEach(Experiment::resetOnNewConnection);
        if (!mLinkProbingSupported) return;

        long now = mClock.getElapsedSinceBootMillis();
        mLastLinkProbeTimestampMs = now;
        mLastTxSuccessIncreaseTimestampMs = now;
        mLastTxSuccessCount = 0;
    }

    /**
     * When RSSI poll events are stopped and restarted (usually screen turned off then back on),
     * reset internal state.
     */
    public void resetOnScreenTurnedOn() {
        mExperiments.forEach(Experiment::resetOnScreenTurnedOn);
        if (!mLinkProbingSupported) return;

        mLastScreenOnTimestampMs = mClock.getElapsedSinceBootMillis();
    }

    /**
     * Based on network conditions provided by WifiInfo, decides if a link probe should be
     * performed. If so, trigger a link probe and report the results to WifiMetrics.
     *
     * @param wifiInfo the updated WifiInfo
     * @param interfaceName the interface that the link probe should be performed on, if applicable.
     */
    public void updateConnectionStats(WifiInfo wifiInfo, String interfaceName) {
        mExperiments.forEach(e -> e.updateConnectionStats(wifiInfo));

        if (!mLinkProbingSupported) return;

        long now = mClock.getElapsedSinceBootMillis();

        // at least 1 tx succeeded since last update
        if (mLastTxSuccessCount < wifiInfo.txSuccess) {
            mLastTxSuccessIncreaseTimestampMs = now;
        }
        mLastTxSuccessCount = wifiInfo.txSuccess;

        // maximum 1 link probe every DELAY_BETWEEN_PROBES_MS
        long timeSinceLastLinkProbeMs = now - mLastLinkProbeTimestampMs;
        if (timeSinceLastLinkProbeMs < DELAY_BETWEEN_PROBES_MS) {
            return;
        }

        // if tx succeeded at least once in the last DELAY_AFTER_TX_SUCCESS_MS, don't need to probe
        long timeSinceLastTxSuccessIncreaseMs = now - mLastTxSuccessIncreaseTimestampMs;
        if (timeSinceLastTxSuccessIncreaseMs < DELAY_AFTER_TX_SUCCESS_MS) {
            return;
        }

        // if not enough time has passed since the screen last turned on, don't probe
        long timeSinceLastScreenOnMs = now - mLastScreenOnTimestampMs;
        if (timeSinceLastScreenOnMs < SCREEN_ON_DELAY_MS) {
            return;
        }

        // can skip probing if RSSI is valid and high and link speed is fast
        int rssi = wifiInfo.getRssi();
        int linkSpeed = wifiInfo.getLinkSpeed();
        if (rssi != WifiInfo.INVALID_RSSI && rssi > RSSI_THRESHOLD
                && linkSpeed > LINK_SPEED_THRESHOLD_MBPS) {
            return;
        }

        if (!mTimedQuotaManager.requestQuota()) {
            return;
        }

        if (mLinkProbingEnabled) {
            if (mVerboseLoggingEnabled) {
                Log.d(TAG, String.format(
                        "link probing triggered with conditions: timeSinceLastLinkProbeMs=%d "
                                + "timeSinceLastTxSuccessIncreaseMs=%d rssi=%d linkSpeed=%s",
                        timeSinceLastLinkProbeMs, timeSinceLastTxSuccessIncreaseMs,
                        rssi, linkSpeed));
            }

            // TODO(b/112029045): also report MCS rate to metrics when supported by driver
            mWifiNative.probeLink(
                    interfaceName,
                    MacAddress.fromString(wifiInfo.getBSSID()),
                    new WifiNative.SendMgmtFrameCallback() {
                        @Override
                        public void onAck(int elapsedTimeMs) {
                            if (mVerboseLoggingEnabled) {
                                Log.d(TAG, "link probing success, elapsedTimeMs="
                                        + elapsedTimeMs);
                            }
                            mWifiMetrics.logLinkProbeSuccess(
                                    timeSinceLastTxSuccessIncreaseMs, rssi, linkSpeed,
                                    elapsedTimeMs);
                        }

                        @Override
                        public void onFailure(int reason) {
                            if (mVerboseLoggingEnabled) {
                                Log.d(TAG, "link probing failure, reason=" + reason);
                            }
                            mWifiMetrics.logLinkProbeFailure(
                                    timeSinceLastTxSuccessIncreaseMs, rssi, linkSpeed, reason);
                        }
                    },
                    -1); // placeholder, lets driver determine MCS rate
        }

        mLastLinkProbeTimestampMs = mClock.getElapsedSinceBootMillis();
    }

    private void initExperiments() {
        for (int delay : EXPERIMENT_DELAYS_MS) {
            for (int rssiThreshold : EXPERIMENT_RSSIS) {
                for (int linkSpeedThreshold: EXPERIMENT_LINK_SPEEDS) {
                    Experiment experiment = new Experiment(mClock, mWifiMetrics,
                            delay, delay, delay, rssiThreshold, linkSpeedThreshold);
                    mExperiments.add(experiment);
                }
            }
        }
    }

    // TODO(b/131091030): remove once experiment is over
    private static class Experiment {

        private final Clock mClock;
        private final WifiMetrics mWifiMetrics;
        private final int mScreenOnDelayMs;
        private final int mNoTxDelayMs;
        private final int mDelayBetweenProbesMs;
        private final int mRssiThreshold;
        private final int mLinkSpeedThreshold;
        private final String mExperimentId;

        private long mLastLinkProbeTimestampMs;
        private long mLastTxSuccessIncreaseTimestampMs;
        private long mLastTxSuccessCount;
        private long mLastScreenOnTimestampMs;

        Experiment(Clock clock, WifiMetrics wifiMetrics,
                int screenOnDelayMs, int noTxDelayMs, int delayBetweenProbesMs,
                int rssiThreshold, int linkSpeedThreshold) {
            mClock = clock;
            mWifiMetrics = wifiMetrics;
            mScreenOnDelayMs = screenOnDelayMs;
            mNoTxDelayMs = noTxDelayMs;
            mDelayBetweenProbesMs = delayBetweenProbesMs;
            mRssiThreshold = rssiThreshold;
            mLinkSpeedThreshold = linkSpeedThreshold;

            mExperimentId = getExperimentId();

            resetOnNewConnection();
            resetOnScreenTurnedOn();
        }

        private String getExperimentId() {
            return "[screenOnDelay=" + mScreenOnDelayMs + ','
                    + "noTxDelay=" + mNoTxDelayMs + ','
                    + "delayBetweenProbes=" + mDelayBetweenProbesMs  + ','
                    + "rssiThreshold=" + mRssiThreshold + ','
                    + "linkSpeedThreshold=" + mLinkSpeedThreshold + ']';
        }

        void resetOnNewConnection() {
            long now = mClock.getElapsedSinceBootMillis();
            mLastLinkProbeTimestampMs = now;
            mLastTxSuccessIncreaseTimestampMs = now;
            mLastTxSuccessCount = 0;
        }

        void resetOnScreenTurnedOn() {
            mLastScreenOnTimestampMs = mClock.getElapsedSinceBootMillis();
        }

        void updateConnectionStats(WifiInfo wifiInfo) {
            long now = mClock.getElapsedSinceBootMillis();

            if (mLastTxSuccessCount < wifiInfo.txSuccess) {
                mLastTxSuccessIncreaseTimestampMs = now;
            }
            mLastTxSuccessCount = wifiInfo.txSuccess;

            long timeSinceLastLinkProbeMs = now - mLastLinkProbeTimestampMs;
            if (timeSinceLastLinkProbeMs < mDelayBetweenProbesMs) {
                return;
            }

            // if tx succeeded at least once in the last LINK_PROBE_INTERVAL_MS, don't need to probe
            long timeSinceLastTxSuccessIncreaseMs = now - mLastTxSuccessIncreaseTimestampMs;
            if (timeSinceLastTxSuccessIncreaseMs < mNoTxDelayMs) {
                return;
            }

            long timeSinceLastScreenOnMs = now - mLastScreenOnTimestampMs;
            if (timeSinceLastScreenOnMs < SCREEN_ON_DELAY_MS) {
                return;
            }

            // can skip probing if RSSI is valid and high and link speed is fast
            int rssi = wifiInfo.getRssi();
            int linkSpeed = wifiInfo.getLinkSpeed();
            if (rssi != WifiInfo.INVALID_RSSI && rssi > mRssiThreshold
                    && linkSpeed > mLinkSpeedThreshold) {
                return;
            }

            mWifiMetrics.incrementLinkProbeExperimentProbeCount(mExperimentId);

            mLastLinkProbeTimestampMs = mClock.getElapsedSinceBootMillis();
        }
    }
}
