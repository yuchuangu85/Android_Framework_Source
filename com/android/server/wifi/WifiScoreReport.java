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

import android.net.NetworkAgent;
import android.net.wifi.WifiInfo;
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

    // Cache of the last score
    private int mScore = NetworkAgent.WIFI_BASE_SCORE;

    private final ScoringParams mScoringParams;
    private final Clock mClock;
    private int mSessionNumber = 0;

    ConnectedScore mAggressiveConnectedScore;
    VelocityBasedConnectedScore mVelocityBasedConnectedScore;

    WifiScoreReport(ScoringParams scoringParams, Clock clock) {
        mScoringParams = scoringParams;
        mClock = clock;
        mAggressiveConnectedScore = new AggressiveConnectedScore(scoringParams, clock);
        mVelocityBasedConnectedScore = new VelocityBasedConnectedScore(scoringParams, clock);
    }

    /**
     * Reset the last calculated score.
     */
    public void reset() {
        mSessionNumber++;
        mScore = NetworkAgent.WIFI_BASE_SCORE;
        mLastKnownNudCheckScore = ConnectedScore.WIFI_TRANSITION_SCORE;
        mAggressiveConnectedScore.reset();
        mVelocityBasedConnectedScore.reset();
        mLastDownwardBreachTimeMillis = 0;
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
     * the provided network agent.
     *
     * If the score has changed from the previous value, update the WifiNetworkAgent.
     *
     * Called periodically (POLL_RSSI_INTERVAL_MSECS) about every 3 seconds.
     *
     * @param wifiInfo WifiInfo instance pointing to the currently connected network.
     * @param networkAgent NetworkAgent to be notified of new score.
     * @param wifiMetrics for reporting our scores.
     */
    public void calculateAndReportScore(WifiInfo wifiInfo, NetworkAgent networkAgent,
                                        WifiMetrics wifiMetrics) {
        if (wifiInfo.getRssi() == WifiInfo.INVALID_RSSI) {
            Log.d(TAG, "Not reporting score because RSSI is invalid");
            return;
        }
        int score;

        long millis = mClock.getWallClockMillis();
        int netId = 0;

        if (networkAgent != null) {
            netId = networkAgent.netId;
        }

        mAggressiveConnectedScore.updateUsingWifiInfo(wifiInfo, millis);
        mVelocityBasedConnectedScore.updateUsingWifiInfo(wifiInfo, millis);

        int s1 = mAggressiveConnectedScore.generateScore();
        int s2 = mVelocityBasedConnectedScore.generateScore();

        score = s2;

        if (wifiInfo.score > ConnectedScore.WIFI_TRANSITION_SCORE
                 && score <= ConnectedScore.WIFI_TRANSITION_SCORE
                 && wifiInfo.txSuccessRate >= mScoringParams.getYippeeSkippyPacketsPerSecond()
                 && wifiInfo.rxSuccessRate >= mScoringParams.getYippeeSkippyPacketsPerSecond()) {
            score = ConnectedScore.WIFI_TRANSITION_SCORE + 1;
        }

        if (wifiInfo.score > ConnectedScore.WIFI_TRANSITION_SCORE
                 && score <= ConnectedScore.WIFI_TRANSITION_SCORE) {
            // We don't want to trigger a downward breach unless the rssi is
            // below the entry threshold.  There is noise in the measured rssi, and
            // the kalman-filtered rssi is affected by the trend, so check them both.
            // TODO(b/74613347) skip this if there are other indications to support the low score
            int entry = mScoringParams.getEntryRssi(wifiInfo.getFrequency());
            if (mVelocityBasedConnectedScore.getFilteredRssi() >= entry
                    || wifiInfo.getRssi() >= entry) {
                // Stay a notch above the transition score to reduce ambiguity.
                score = ConnectedScore.WIFI_TRANSITION_SCORE + 1;
            }
        }

        if (wifiInfo.score >= ConnectedScore.WIFI_TRANSITION_SCORE
                 && score < ConnectedScore.WIFI_TRANSITION_SCORE) {
            mLastDownwardBreachTimeMillis = millis;
        } else if (wifiInfo.score < ConnectedScore.WIFI_TRANSITION_SCORE
                 && score >= ConnectedScore.WIFI_TRANSITION_SCORE) {
            // Staying at below transition score for a certain period of time
            // to prevent going back to wifi network again in a short time.
            long elapsedMillis = millis - mLastDownwardBreachTimeMillis;
            if (elapsedMillis < MIN_TIME_TO_KEEP_BELOW_TRANSITION_SCORE_MILLIS) {
                score = wifiInfo.score;
            }
        }

        //sanitize boundaries
        if (score > NetworkAgent.WIFI_BASE_SCORE) {
            score = NetworkAgent.WIFI_BASE_SCORE;
        }
        if (score < 0) {
            score = 0;
        }

        logLinkMetrics(wifiInfo, millis, netId, s1, s2, score);

        //report score
        if (score != wifiInfo.score) {
            if (mVerboseLoggingEnabled) {
                Log.d(TAG, "report new wifi score " + score);
            }
            wifiInfo.score = score;
            if (networkAgent != null) {
                networkAgent.sendNetworkScore(score);
            }
        }

        wifiMetrics.incrementWifiScoreCount(score);
        mScore = score;
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
        // nud is between 1 and 10 at this point
        double deltaLevel = 11 - nud;
        // nextNudBreach is the bar the score needs to cross before we ask for NUD
        double nextNudBreach = ConnectedScore.WIFI_TRANSITION_SCORE;
        // If we were below threshold the last time we checked, then compute a new bar
        // that starts down from there and decays exponentially back up to the steady-state
        // bar. If 5 time constants have passed, we are 99% of the way there, so skip the math.
        if (mLastKnownNudCheckScore < ConnectedScore.WIFI_TRANSITION_SCORE
                && deltaMillis < 5.0 * TIME_CONSTANT_MILLIS) {
            double a = Math.exp(-deltaMillis / TIME_CONSTANT_MILLIS);
            nextNudBreach = a * (mLastKnownNudCheckScore - deltaLevel) + (1.0 - a) * nextNudBreach;
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
    private void logLinkMetrics(WifiInfo wifiInfo, long now, int netId,
                                int s1, int s2, int score) {
        if (now < FIRST_REASONABLE_WALL_CLOCK) return;
        double rssi = wifiInfo.getRssi();
        double filteredRssi = mVelocityBasedConnectedScore.getFilteredRssi();
        double rssiThreshold = mVelocityBasedConnectedScore.getAdjustedRssiThreshold();
        int freq = wifiInfo.getFrequency();
        int linkSpeed = wifiInfo.getLinkSpeed();
        double txSuccessRate = wifiInfo.txSuccessRate;
        double txRetriesRate = wifiInfo.txRetriesRate;
        double txBadRate = wifiInfo.txBadRate;
        double rxSuccessRate = wifiInfo.rxSuccessRate;
        String s;
        try {
            String timestamp = new SimpleDateFormat("MM-dd HH:mm:ss.SSS").format(new Date(now));
            s = String.format(Locale.US, // Use US to avoid comma/decimal confusion
                    "%s,%d,%d,%.1f,%.1f,%.1f,%d,%d,%.2f,%.2f,%.2f,%.2f,%d,%d,%d,%d,%d",
                    timestamp, mSessionNumber, netId,
                    rssi, filteredRssi, rssiThreshold, freq, linkSpeed,
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
        pw.println("time,session,netid,rssi,filtered_rssi,rssi_threshold,"
                + "freq,linkspeed,tx_good,tx_retry,tx_bad,rx_pps,nudrq,nuds,s1,s2,score");
        for (String line : history) {
            pw.println(line);
        }
        history.clear();
    }
}
