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

import android.net.Network;
import android.net.NetworkAgent;
import android.net.wifi.IScoreUpdateObserver;
import android.net.wifi.IWifiConnectedNetworkScorer;
import android.net.wifi.WifiInfo;
import android.net.wifi.nl80211.WifiNl80211Manager;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedList;
import java.util.Locale;

/**
 * Class used to calculate scores for connected wifi networks and report it to the associated
 * network agent.
 */
public class WifiScoreReport {
    private static final String TAG = "WifiScoreReport";

    private static final int DUMPSYS_ENTRY_COUNT_LIMIT = 3600; // 3 hours on 3 second poll

    private boolean mVerboseLoggingEnabled = false;
    private static final long FIRST_REASONABLE_WALL_CLOCK = 1490000000000L; // mid-December 2016

    private static final long MIN_TIME_TO_KEEP_BELOW_TRANSITION_SCORE_MILLIS = 9000;
    private long mLastDownwardBreachTimeMillis = 0;

    private static final int WIFI_CONNECTED_NETWORK_SCORER_IDENTIFIER = 0;
    private static final int INVALID_SESSION_ID = -1;
    private static final long MIN_TIME_TO_WAIT_BEFORE_BLOCKLIST_BSSID_MILLIS = 29000;
    private static final long DURATION_TO_BLOCKLIST_BSSID_AFTER_FIRST_EXITING_MILLIS = 30000;
    private static final long INVALID_WALL_CLOCK_MILLIS = -1;

    // Cache of the last score
    private int mScore = ConnectedScore.WIFI_MAX_SCORE;

    private final ScoringParams mScoringParams;
    private final Clock mClock;
    private int mSessionNumber = 0; // not to be confused with sessionid, this just counts resets
    private String mInterfaceName;
    private final BssidBlocklistMonitor mBssidBlocklistMonitor;
    private long mLastScoreBreachLowTimeMillis = -1;

    ConnectedScore mAggressiveConnectedScore;
    VelocityBasedConnectedScore mVelocityBasedConnectedScore;

    NetworkAgent mNetworkAgent;
    WifiMetrics mWifiMetrics;
    WifiInfo mWifiInfo;
    WifiNative mWifiNative;
    WifiThreadRunner mWifiThreadRunner;

    /**
     * Callback proxy. See {@link android.net.wifi.WifiManager.ScoreUpdateObserver}.
     */
    private class ScoreUpdateObserverProxy extends IScoreUpdateObserver.Stub {
        @Override
        public void notifyScoreUpdate(int sessionId, int score) {
            mWifiThreadRunner.post(() -> {
                if (mWifiConnectedNetworkScorerHolder == null
                        || sessionId == INVALID_SESSION_ID
                        || sessionId != getCurrentSessionId()) {
                    Log.w(TAG, "Ignoring stale/invalid external score"
                             + " sessionId=" + sessionId
                             + " currentSessionId=" + getCurrentSessionId()
                             + " score=" + score);
                    return;
                }
                if (mNetworkAgent != null) {
                    mNetworkAgent.sendNetworkScore(score);
                }

                long millis = mClock.getWallClockMillis();
                if (score < ConnectedScore.WIFI_TRANSITION_SCORE) {
                    if (mScore >= ConnectedScore.WIFI_TRANSITION_SCORE) {
                        mLastScoreBreachLowTimeMillis = millis;
                    }
                } else {
                    mLastScoreBreachLowTimeMillis = INVALID_WALL_CLOCK_MILLIS;
                }

                mScore = score;
                updateWifiMetrics(millis, -1, mScore);
            });
        }

        @Override
        public void triggerUpdateOfWifiUsabilityStats(int sessionId) {
            mWifiThreadRunner.post(() -> {
                if (mWifiConnectedNetworkScorerHolder == null
                        || sessionId == INVALID_SESSION_ID
                        || sessionId != getCurrentSessionId()
                        || mInterfaceName == null) {
                    Log.w(TAG, "Ignoring triggerUpdateOfWifiUsabilityStats"
                             + " sessionId=" + sessionId
                             + " currentSessionId=" + getCurrentSessionId()
                             + " interfaceName=" + mInterfaceName);
                    return;
                }
                WifiLinkLayerStats stats = mWifiNative.getWifiLinkLayerStats(mInterfaceName);

                // update mWifiInfo
                // TODO(b/153075963): Better coordinate this class and ClientModeImpl to remove
                // redundant codes below and in ClientModeImpl#fetchRssiLinkSpeedAndFrequencyNative.
                WifiNl80211Manager.SignalPollResult pollResult =
                        mWifiNative.signalPoll(mInterfaceName);
                if (pollResult != null) {
                    int newRssi = pollResult.currentRssiDbm;
                    int newTxLinkSpeed = pollResult.txBitrateMbps;
                    int newFrequency = pollResult.associationFrequencyMHz;
                    int newRxLinkSpeed = pollResult.rxBitrateMbps;

                    if (newRssi > WifiInfo.INVALID_RSSI && newRssi < WifiInfo.MAX_RSSI) {
                        if (newRssi > (WifiInfo.INVALID_RSSI + 256)) {
                            Log.wtf(TAG, "Error! +ve value RSSI: " + newRssi);
                            newRssi -= 256;
                        }
                        mWifiInfo.setRssi(newRssi);
                    } else {
                        mWifiInfo.setRssi(WifiInfo.INVALID_RSSI);
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
                }

                // TODO(b/153075963): This should not be plumbed through WifiMetrics
                mWifiMetrics.updateWifiUsabilityStatsEntries(mWifiInfo, stats);
            });
        }
    }

    /**
     * Container for storing info about external scorer and tracking its death.
     */
    private final class WifiConnectedNetworkScorerHolder implements IBinder.DeathRecipient {
        private final IBinder mBinder;
        private final IWifiConnectedNetworkScorer mScorer;
        private int mSessionId = INVALID_SESSION_ID;

        WifiConnectedNetworkScorerHolder(IBinder binder, IWifiConnectedNetworkScorer scorer) {
            mBinder = binder;
            mScorer = scorer;
        }

        /**
         * Link WiFi connected scorer to death listener.
         */
        public boolean linkScorerToDeath() {
            try {
                mBinder.linkToDeath(this, 0);
            } catch (RemoteException e) {
                Log.e(TAG, "Unable to linkToDeath Wifi connected network scorer " + mScorer, e);
                return false;
            }
            return true;
        }

        /**
         * App hosting the binder has died.
         */
        @Override
        public void binderDied() {
            mWifiThreadRunner.post(() -> revertToDefaultConnectedScorer());
        }

        /**
         * Unlink this object from binder death.
         */
        public void reset() {
            mBinder.unlinkToDeath(this, 0);
        }

        /**
         * Starts a new scoring session.
         */
        public void startSession(int sessionId) {
            if (sessionId == INVALID_SESSION_ID) {
                throw new IllegalArgumentException();
            }
            if (mSessionId != INVALID_SESSION_ID) {
                // This is not expected to happen, log if it does
                Log.e(TAG, "Stopping session " + mSessionId + " before starting " + sessionId);
                stopSession();
            }
            // Bail now if the scorer has gone away
            if (this != mWifiConnectedNetworkScorerHolder) {
                return;
            }
            mSessionId = sessionId;
            try {
                mScorer.onStart(sessionId);
            } catch (RemoteException e) {
                Log.e(TAG, "Unable to start Wifi connected network scorer " + this, e);
                revertToDefaultConnectedScorer();
            }
        }
        public void stopSession() {
            final int sessionId = mSessionId;
            if (sessionId == INVALID_SESSION_ID) return;
            mSessionId = INVALID_SESSION_ID;
            try {
                mScorer.onStop(sessionId);
            } catch (RemoteException e) {
                Log.e(TAG, "Unable to stop Wifi connected network scorer " + this, e);
                revertToDefaultConnectedScorer();
            }
        }
    }

    private final ScoreUpdateObserverProxy mScoreUpdateObserver =
            new ScoreUpdateObserverProxy();

    private WifiConnectedNetworkScorerHolder mWifiConnectedNetworkScorerHolder;

    WifiScoreReport(ScoringParams scoringParams, Clock clock, WifiMetrics wifiMetrics,
            WifiInfo wifiInfo, WifiNative wifiNative, BssidBlocklistMonitor bssidBlocklistMonitor,
            WifiThreadRunner wifiThreadRunner) {
        mScoringParams = scoringParams;
        mClock = clock;
        mAggressiveConnectedScore = new AggressiveConnectedScore(scoringParams, clock);
        mVelocityBasedConnectedScore = new VelocityBasedConnectedScore(scoringParams, clock);
        mWifiMetrics = wifiMetrics;
        mWifiInfo = wifiInfo;
        mWifiNative = wifiNative;
        mBssidBlocklistMonitor = bssidBlocklistMonitor;
        mWifiThreadRunner = wifiThreadRunner;
    }

    /**
     * Reset the last calculated score.
     */
    public void reset() {
        mSessionNumber++;
        mScore = ConnectedScore.WIFI_MAX_SCORE;
        mLastKnownNudCheckScore = ConnectedScore.WIFI_TRANSITION_SCORE;
        mAggressiveConnectedScore.reset();
        if (mVelocityBasedConnectedScore != null) {
            mVelocityBasedConnectedScore.reset();
        }
        mLastDownwardBreachTimeMillis = 0;
        mLastScoreBreachLowTimeMillis = INVALID_WALL_CLOCK_MILLIS;
        if (mVerboseLoggingEnabled) Log.d(TAG, "reset");
    }

    /**
     * Enable/Disable verbose logging in score report generation.
     */
    public void enableVerboseLogging(boolean enable) {
        mVerboseLoggingEnabled = enable;
    }

    /**
     * Calculate wifi network score based on updated link layer stats and send the score to
     * the WifiNetworkAgent.
     *
     * If the score has changed from the previous value, update the WifiNetworkAgent.
     *
     * Called periodically (POLL_RSSI_INTERVAL_MSECS) about every 3 seconds.
     */
    public void calculateAndReportScore() {
        // Bypass AOSP scorer if Wifi connected network scorer is set
        if (mWifiConnectedNetworkScorerHolder != null) {
            return;
        }

        if (mWifiInfo.getRssi() == mWifiInfo.INVALID_RSSI) {
            Log.d(TAG, "Not reporting score because RSSI is invalid");
            return;
        }
        int score;

        long millis = mClock.getWallClockMillis();
        mVelocityBasedConnectedScore.updateUsingWifiInfo(mWifiInfo, millis);

        int s2 = mVelocityBasedConnectedScore.generateScore();
        score = s2;

        if (mWifiInfo.getScore() > ConnectedScore.WIFI_TRANSITION_SCORE
                && score <= ConnectedScore.WIFI_TRANSITION_SCORE
                && mWifiInfo.getSuccessfulTxPacketsPerSecond()
                        >= mScoringParams.getYippeeSkippyPacketsPerSecond()
                && mWifiInfo.getSuccessfulRxPacketsPerSecond()
                        >= mScoringParams.getYippeeSkippyPacketsPerSecond()
        ) {
            score = ConnectedScore.WIFI_TRANSITION_SCORE + 1;
        }

        if (mWifiInfo.getScore() > ConnectedScore.WIFI_TRANSITION_SCORE
                && score <= ConnectedScore.WIFI_TRANSITION_SCORE) {
            // We don't want to trigger a downward breach unless the rssi is
            // below the entry threshold.  There is noise in the measured rssi, and
            // the kalman-filtered rssi is affected by the trend, so check them both.
            // TODO(b/74613347) skip this if there are other indications to support the low score
            int entry = mScoringParams.getEntryRssi(mWifiInfo.getFrequency());
            if (mVelocityBasedConnectedScore.getFilteredRssi() >= entry
                    || mWifiInfo.getRssi() >= entry) {
                // Stay a notch above the transition score to reduce ambiguity.
                score = ConnectedScore.WIFI_TRANSITION_SCORE + 1;
            }
        }

        if (mWifiInfo.getScore() >= ConnectedScore.WIFI_TRANSITION_SCORE
                && score < ConnectedScore.WIFI_TRANSITION_SCORE) {
            mLastDownwardBreachTimeMillis = millis;
        } else if (mWifiInfo.getScore() < ConnectedScore.WIFI_TRANSITION_SCORE
                && score >= ConnectedScore.WIFI_TRANSITION_SCORE) {
            // Staying at below transition score for a certain period of time
            // to prevent going back to wifi network again in a short time.
            long elapsedMillis = millis - mLastDownwardBreachTimeMillis;
            if (elapsedMillis < MIN_TIME_TO_KEEP_BELOW_TRANSITION_SCORE_MILLIS) {
                score = mWifiInfo.getScore();
            }
        }
        //sanitize boundaries
        if (score > ConnectedScore.WIFI_MAX_SCORE) {
            score = ConnectedScore.WIFI_MAX_SCORE;
        }
        if (score < 0) {
            score = 0;
        }

        //report score
        if (score != mWifiInfo.getScore()) {
            if (mNetworkAgent != null) {
                mNetworkAgent.sendNetworkScore(score);
            }
        }

        updateWifiMetrics(millis, s2, score);
        mScore = score;
    }

    private int getCurrentNetId() {
        int netId = 0;
        if (mNetworkAgent != null) {
            final Network network = mNetworkAgent.getNetwork();
            if (network != null) {
                netId = network.getNetId();
            }
        }
        return netId;
    }

    private int getCurrentSessionId() {
        return sessionIdFromNetId(getCurrentNetId());
    }

    /**
     * Encodes a network id into a scoring session id.
     *
     * We use a different numeric value for session id and the network id
     * to make it clear that these are not the same thing. However, for
     * easier debugging, the network id can be recovered by dropping the
     * last decimal digit (at least until they get very, very, large).
     */
    public static int sessionIdFromNetId(final int netId) {
        if (netId <= 0) return INVALID_SESSION_ID;
        return (int) (((long) netId * 10 + (8 - (netId % 9))) % Integer.MAX_VALUE + 1);
    }

    private void updateWifiMetrics(long now, int s2, int score) {
        int netId = getCurrentNetId();

        mAggressiveConnectedScore.updateUsingWifiInfo(mWifiInfo, now);
        int s1 = mAggressiveConnectedScore.generateScore();
        logLinkMetrics(now, netId, s1, s2, score);

        if (score != mWifiInfo.getScore()) {
            if (mVerboseLoggingEnabled) {
                Log.d(TAG, "report new wifi score " + score);
            }
            mWifiInfo.setScore(score);
        }
        mWifiMetrics.incrementWifiScoreCount(score);
    }

    private static final double TIME_CONSTANT_MILLIS = 30.0e+3;
    private static final long NUD_THROTTLE_MILLIS = 5000;
    private long mLastKnownNudCheckTimeMillis = 0;
    private int mLastKnownNudCheckScore = ConnectedScore.WIFI_TRANSITION_SCORE;
    private int mNudYes = 0;    // Counts when we voted for a NUD
    private int mNudCount = 0;  // Counts when we were told a NUD was sent

    /**
     * Recommends that a layer 3 check be done
     *
     * The caller can use this to (help) decide that an IP reachability check
     * is desirable. The check is not done here; that is the caller's responsibility.
     *
     * @return true to indicate that an IP reachability check is recommended
     */
    public boolean shouldCheckIpLayer() {
        int nud = mScoringParams.getNudKnob();
        if (nud == 0) {
            return false;
        }
        long millis = mClock.getWallClockMillis();
        long deltaMillis = millis - mLastKnownNudCheckTimeMillis;
        // Don't ever ask back-to-back - allow at least 5 seconds
        // for the previous one to finish.
        if (deltaMillis < NUD_THROTTLE_MILLIS) {
            return false;
        }
        // nextNudBreach is the bar the score needs to cross before we ask for NUD
        double nextNudBreach = ConnectedScore.WIFI_TRANSITION_SCORE;
        if (mWifiConnectedNetworkScorerHolder == null) {
            // nud is between 1 and 10 at this point
            double deltaLevel = 11 - nud;
            // If we were below threshold the last time we checked, then compute a new bar
            // that starts down from there and decays exponentially back up to the steady-state
            // bar. If 5 time constants have passed, we are 99% of the way there, so skip the math.
            if (mLastKnownNudCheckScore < ConnectedScore.WIFI_TRANSITION_SCORE
                    && deltaMillis < 5.0 * TIME_CONSTANT_MILLIS) {
                double a = Math.exp(-deltaMillis / TIME_CONSTANT_MILLIS);
                nextNudBreach =
                        a * (mLastKnownNudCheckScore - deltaLevel) + (1.0 - a) * nextNudBreach;
            }
        }
        if (mScore >= nextNudBreach) {
            return false;
        }
        mNudYes++;
        return true;
    }

    /**
     * Should be called when a reachability check has been issued
     *
     * When the caller has requested an IP reachability check, calling this will
     * help to rate-limit requests via shouldCheckIpLayer()
     */
    public void noteIpCheck() {
        long millis = mClock.getWallClockMillis();
        mLastKnownNudCheckTimeMillis = millis;
        mLastKnownNudCheckScore = mScore;
        mNudCount++;
    }

    /**
     * Data for dumpsys
     *
     * These are stored as csv formatted lines
     */
    private LinkedList<String> mLinkMetricsHistory = new LinkedList<String>();

    /**
     * Data logging for dumpsys
     */
    private void logLinkMetrics(long now, int netId, int s1, int s2, int score) {
        if (now < FIRST_REASONABLE_WALL_CLOCK) return;
        double rssi = mWifiInfo.getRssi();
        double filteredRssi = -1;
        double rssiThreshold = -1;
        if (mWifiConnectedNetworkScorerHolder == null) {
            filteredRssi = mVelocityBasedConnectedScore.getFilteredRssi();
            rssiThreshold = mVelocityBasedConnectedScore.getAdjustedRssiThreshold();
        }
        int freq = mWifiInfo.getFrequency();
        int txLinkSpeed = mWifiInfo.getLinkSpeed();
        int rxLinkSpeed = mWifiInfo.getRxLinkSpeedMbps();
        double txSuccessRate = mWifiInfo.getSuccessfulTxPacketsPerSecond();
        double txRetriesRate = mWifiInfo.getRetriedTxPacketsPerSecond();
        double txBadRate = mWifiInfo.getLostTxPacketsPerSecond();
        double rxSuccessRate = mWifiInfo.getSuccessfulRxPacketsPerSecond();
        String s;
        try {
            String timestamp = new SimpleDateFormat("MM-dd HH:mm:ss.SSS").format(new Date(now));
            s = String.format(Locale.US, // Use US to avoid comma/decimal confusion
                    "%s,%d,%d,%.1f,%.1f,%.1f,%d,%d,%d,%.2f,%.2f,%.2f,%.2f,%d,%d,%d,%d,%d",
                    timestamp, mSessionNumber, netId,
                    rssi, filteredRssi, rssiThreshold, freq, txLinkSpeed, rxLinkSpeed,
                    txSuccessRate, txRetriesRate, txBadRate, rxSuccessRate,
                    mNudYes, mNudCount,
                    s1, s2, score);
        } catch (Exception e) {
            Log.e(TAG, "format problem", e);
            return;
        }
        synchronized (mLinkMetricsHistory) {
            mLinkMetricsHistory.add(s);
            while (mLinkMetricsHistory.size() > DUMPSYS_ENTRY_COUNT_LIMIT) {
                mLinkMetricsHistory.removeFirst();
            }
        }
    }

    /**
     * Tag to be used in dumpsys request
     */
    public static final String DUMP_ARG = "WifiScoreReport";

    /**
     * Dump logged signal strength and traffic measurements.
     * @param fd unused
     * @param pw PrintWriter for writing dump to
     * @param args unused
     */
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        LinkedList<String> history;
        synchronized (mLinkMetricsHistory) {
            history = new LinkedList<>(mLinkMetricsHistory);
        }
        pw.println("time,session,netid,rssi,filtered_rssi,rssi_threshold,freq,txLinkSpeed,"
                + "rxLinkSpeed,tx_good,tx_retry,tx_bad,rx_pps,nudrq,nuds,s1,s2,score");
        for (String line : history) {
            pw.println(line);
        }
        history.clear();
    }

    /**
     * Set a scorer for Wi-Fi connected network score handling.
     * @param binder
     * @param scorer
     */
    public boolean setWifiConnectedNetworkScorer(IBinder binder,
            IWifiConnectedNetworkScorer scorer) {
        if (binder == null || scorer == null) return false;
        // Enforce that only a single scorer can be set successfully.
        if (mWifiConnectedNetworkScorerHolder != null) {
            Log.e(TAG, "Failed to set current scorer because one scorer is already set");
            return false;
        }
        WifiConnectedNetworkScorerHolder scorerHolder =
                new WifiConnectedNetworkScorerHolder(binder, scorer);
        if (!scorerHolder.linkScorerToDeath()) {
            return false;
        }
        mWifiConnectedNetworkScorerHolder = scorerHolder;

        try {
            scorer.onSetScoreUpdateObserver(mScoreUpdateObserver);
        } catch (RemoteException e) {
            Log.e(TAG, "Unable to set score update observer " + scorer, e);
            revertToDefaultConnectedScorer();
            return false;
        }
        // Disable AOSP scorer
        mVelocityBasedConnectedScore = null;
        mWifiMetrics.setIsExternalWifiScorerOn(true);
        // If there is already a connection, start a new session
        final int netId = getCurrentNetId();
        if (netId > 0) {
            startConnectedNetworkScorer(netId);
        }
        return true;
    }

    /**
     * Clear an existing scorer for Wi-Fi connected network score handling.
     */
    public void clearWifiConnectedNetworkScorer() {
        if (mWifiConnectedNetworkScorerHolder == null) {
            return;
        }
        mWifiConnectedNetworkScorerHolder.reset();
        revertToDefaultConnectedScorer();
    }

    /**
     * Start the registered Wi-Fi connected network scorer.
     * @param netId identifies the current android.net.Network
     */
    public void startConnectedNetworkScorer(int netId) {
        final int sessionId = getCurrentSessionId();
        if (mWifiConnectedNetworkScorerHolder == null
                || netId != getCurrentNetId()
                || sessionId == INVALID_SESSION_ID) {
            Log.w(TAG, "Cannot start external scoring"
                    + " netId=" + netId
                    + " currentNetId=" + getCurrentNetId()
                    + " sessionId=" + sessionId);
            return;
        }
        mWifiConnectedNetworkScorerHolder.startSession(sessionId);
        mLastScoreBreachLowTimeMillis = INVALID_WALL_CLOCK_MILLIS;
    }

    /**
     * Stop the registered Wi-Fi connected network scorer.
     */
    public void stopConnectedNetworkScorer() {
        mNetworkAgent = null;
        if (mWifiConnectedNetworkScorerHolder == null) {
            return;
        }
        mWifiConnectedNetworkScorerHolder.stopSession();

        long millis = mClock.getWallClockMillis();
        // Blocklist the current BSS
        if ((mLastScoreBreachLowTimeMillis != INVALID_WALL_CLOCK_MILLIS)
                && ((millis - mLastScoreBreachLowTimeMillis)
                        >= MIN_TIME_TO_WAIT_BEFORE_BLOCKLIST_BSSID_MILLIS)) {
            mBssidBlocklistMonitor.blockBssidForDurationMs(mWifiInfo.getBSSID(),
                    mWifiInfo.getSSID(),
                    DURATION_TO_BLOCKLIST_BSSID_AFTER_FIRST_EXITING_MILLIS);
            mLastScoreBreachLowTimeMillis = INVALID_WALL_CLOCK_MILLIS;
        }
    }

    /**
     * Set NetworkAgent
     */
    public void setNetworkAgent(NetworkAgent agent) {
        mNetworkAgent = agent;
    }

    /**
     * Get cached score
     */
    public int getScore() {
        return mScore;
    }

    /**
     * Set interface name
     * @param ifaceName
     */
    public void setInterfaceName(String ifaceName) {
        mInterfaceName = ifaceName;
    }

    private void revertToDefaultConnectedScorer() {
        Log.d(TAG, "Using VelocityBasedConnectedScore");
        mVelocityBasedConnectedScore = new VelocityBasedConnectedScore(mScoringParams, mClock);
        mWifiConnectedNetworkScorerHolder = null;
        mWifiMetrics.setIsExternalWifiScorerOn(false);
    }
}
