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

import android.content.Context;
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

    private static final int DUMPSYS_ENTRY_COUNT_LIMIT = 14400; // 12 hours on 3 second poll

    private boolean mVerboseLoggingEnabled = false;
    private static final long FIRST_REASONABLE_WALL_CLOCK = 1490000000000L; // mid-December 2016

    // Cache of the last score report.
    private String mReport;
    private boolean mReportValid = false;

    private final Clock mClock;
    private int mSessionNumber = 0;

    ConnectedScore mConnectedScore;
    ConnectedScore mAggressiveConnectedScore;

    WifiScoreReport(Context context, WifiConfigManager wifiConfigManager, Clock clock) {
        mClock = clock;
        mConnectedScore = new LegacyConnectedScore(context, wifiConfigManager, clock);
        mAggressiveConnectedScore = new AggressiveConnectedScore(context, clock);
    }

    /**
     * Method returning the String representation of the last score report.
     *
     *  @return String score report
     */
    public String getLastReport() {
        return mReport;
    }

    /**
     * Reset the last calculated score.
     */
    public void reset() {
        mReport = "";
        if (mReportValid) {
            mSessionNumber++;
            mReportValid = false;
        }
        mConnectedScore.reset();
        mAggressiveConnectedScore.reset();
        if (mVerboseLoggingEnabled) Log.d(TAG, "reset");
    }

    /**
     * Checks if the last report data is valid or not. This will be cleared when {@link #reset()} is
     * invoked.
     *
     * @return true if valid, false otherwise.
     */
    public boolean isLastReportValid() {
        return mReportValid;
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
     * @param aggressiveHandover int current aggressiveHandover setting.
     * @param wifiMetrics for reporting our scores.
     */
    public void calculateAndReportScore(WifiInfo wifiInfo, NetworkAgent networkAgent,
                                        int aggressiveHandover, WifiMetrics wifiMetrics) {
        int score;

        long millis = mConnectedScore.getMillis();

        mConnectedScore.updateUsingWifiInfo(wifiInfo, millis);
        mAggressiveConnectedScore.updateUsingWifiInfo(wifiInfo, millis);

        int s0 = mConnectedScore.generateScore();
        int s1 = mAggressiveConnectedScore.generateScore();

        if (aggressiveHandover == 0) {
            score = s0;
        } else {
            score = s1;
        }

        //sanitize boundaries
        if (score > NetworkAgent.WIFI_BASE_SCORE) {
            score = NetworkAgent.WIFI_BASE_SCORE;
        }
        if (score < 0) {
            score = 0;
        }

        logLinkMetrics(wifiInfo, s0, s1);

        //report score
        if (score != wifiInfo.score) {
            if (mVerboseLoggingEnabled) {
                Log.d(TAG, " report new wifi score " + score);
            }
            wifiInfo.score = score;
            if (networkAgent != null) {
                networkAgent.sendNetworkScore(score);
            }
        }

        mReport = String.format(Locale.US, " score=%d", score);
        mReportValid = true;
        wifiMetrics.incrementWifiScoreCount(score);
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
    private void logLinkMetrics(WifiInfo wifiInfo, int s0, int s1) {
        long now = mClock.getWallClockMillis();
        if (now < FIRST_REASONABLE_WALL_CLOCK) return;
        double rssi = wifiInfo.getRssi();
        int freq = wifiInfo.getFrequency();
        int linkSpeed = wifiInfo.getLinkSpeed();
        double txSuccessRate = wifiInfo.txSuccessRate;
        double txRetriesRate = wifiInfo.txRetriesRate;
        double txBadRate = wifiInfo.txBadRate;
        double rxSuccessRate = wifiInfo.rxSuccessRate;
        try {
            String timestamp = new SimpleDateFormat("MM-dd HH:mm:ss.SSS").format(new Date(now));
            String s = String.format(Locale.US, // Use US to avoid comma/decimal confusion
                    "%s,%d,%.1f,%d,%d,%.2f,%.2f,%.2f,%.2f,%d,%d",
                    timestamp, mSessionNumber, rssi, freq, linkSpeed,
                    txSuccessRate, txRetriesRate, txBadRate, rxSuccessRate,
                    s0, s1);
            mLinkMetricsHistory.add(s);
        } catch (Exception e) {
            Log.e(TAG, "format problem", e);
        }
        while (mLinkMetricsHistory.size() > DUMPSYS_ENTRY_COUNT_LIMIT) {
            mLinkMetricsHistory.removeFirst();
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
        pw.println("time,session,rssi,freq,linkspeed,tx_good,tx_retry,tx_bad,rx,s0,s1");
        for (String line : mLinkMetricsHistory) {
            pw.println(line);
        }
    }
}
