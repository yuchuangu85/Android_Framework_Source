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
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.util.Log;

import com.android.internal.R;

/**
 * Class used to calculate scores for connected wifi networks and report it to the associated
 * network agent.
*/
public class WifiScoreReport {
    private static final String TAG = "WifiScoreReport";

    private static final int STARTING_SCORE = 56;

    private static final int SCAN_CACHE_VISIBILITY_MS = 12000;
    private static final int HOME_VISIBLE_NETWORK_MAX_COUNT = 6;
    private static final int SCAN_CACHE_COUNT_PENALTY = 2;
    private static final int AGGRESSIVE_HANDOVER_PENALTY = 6;
    private static final int MAX_SUCCESS_RATE_OF_STUCK_LINK = 3; // proportional to packets per sec
    private static final int MAX_STUCK_LINK_COUNT = 5;
    private static final int MAX_BAD_RSSI_COUNT = 7;
    private static final int BAD_RSSI_COUNT_PENALTY = 2;
    private static final int MAX_LOW_RSSI_COUNT = 1;
    private static final double MIN_TX_FAILURE_RATE_FOR_WORKING_LINK = 0.3;
    private static final int MIN_SUSTAINED_LINK_STUCK_COUNT = 1;
    private static final int LINK_STUCK_PENALTY = 2;
    private static final int BAD_LINKSPEED_PENALTY = 4;
    private static final int GOOD_LINKSPEED_BONUS = 4;

    // Device configs. The values are examples.
    private final int mThresholdMinimumRssi5;      // -82
    private final int mThresholdQualifiedRssi5;    // -70
    private final int mThresholdSaturatedRssi5;    // -57
    private final int mThresholdMinimumRssi24;     // -85
    private final int mThresholdQualifiedRssi24;   // -73
    private final int mThresholdSaturatedRssi24;   // -60
    private final int mBadLinkSpeed24;             //  6 Mbps
    private final int mBadLinkSpeed5;              // 12 Mbps
    private final int mGoodLinkSpeed24;            // 24 Mbps
    private final int mGoodLinkSpeed5;             // 36 Mbps

    private final WifiConfigManager mWifiConfigManager;
    private boolean mVerboseLoggingEnabled = false;

    // Cache of the last score report.
    private String mReport;
    private boolean mReportValid = false;

    // State set by updateScoringState
    private boolean mMultiBandScanResults;
    private boolean mIsHomeNetwork;

    WifiScoreReport(Context context, WifiConfigManager wifiConfigManager) {
        // Fetch all the device configs.
        mThresholdMinimumRssi5 = context.getResources().getInteger(
                R.integer.config_wifi_framework_wifi_score_bad_rssi_threshold_5GHz);
        mThresholdQualifiedRssi5 = context.getResources().getInteger(
                R.integer.config_wifi_framework_wifi_score_low_rssi_threshold_5GHz);
        mThresholdSaturatedRssi5 = context.getResources().getInteger(
                R.integer.config_wifi_framework_wifi_score_good_rssi_threshold_5GHz);
        mThresholdMinimumRssi24 = context.getResources().getInteger(
                R.integer.config_wifi_framework_wifi_score_bad_rssi_threshold_24GHz);
        mThresholdQualifiedRssi24 = context.getResources().getInteger(
                R.integer.config_wifi_framework_wifi_score_low_rssi_threshold_24GHz);
        mThresholdSaturatedRssi24 = context.getResources().getInteger(
                R.integer.config_wifi_framework_wifi_score_good_rssi_threshold_24GHz);
        mBadLinkSpeed24 = context.getResources().getInteger(
                R.integer.config_wifi_framework_wifi_score_bad_link_speed_24);
        mBadLinkSpeed5 = context.getResources().getInteger(
                R.integer.config_wifi_framework_wifi_score_bad_link_speed_5);
        mGoodLinkSpeed24 = context.getResources().getInteger(
                R.integer.config_wifi_framework_wifi_score_good_link_speed_24);
        mGoodLinkSpeed5 = context.getResources().getInteger(
                R.integer.config_wifi_framework_wifi_score_good_link_speed_5);

        mWifiConfigManager = wifiConfigManager;
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
        mReportValid = false;
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

        updateScoringState(wifiInfo, aggressiveHandover);
        score = calculateScore(wifiInfo, aggressiveHandover);

        //sanitize boundaries
        if (score > NetworkAgent.WIFI_BASE_SCORE) {
            score = NetworkAgent.WIFI_BASE_SCORE;
        }
        if (score < 0) {
            score = 0;
        }

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

        mReport = String.format(" score=%d", score);
        mReportValid = true;
        wifiMetrics.incrementWifiScoreCount(score);
    }

    /**
     * Updates the state.
     */
    private void updateScoringState(WifiInfo wifiInfo, int aggressiveHandover) {
        mMultiBandScanResults = multiBandScanResults(wifiInfo);
        mIsHomeNetwork = isHomeNetwork(wifiInfo);

        int rssiThreshBad = mThresholdMinimumRssi24;
        int rssiThreshLow = mThresholdQualifiedRssi24;

        if (wifiInfo.is5GHz() && !mMultiBandScanResults) {
            rssiThreshBad = mThresholdMinimumRssi5;
            rssiThreshLow = mThresholdQualifiedRssi5;
        }

        int rssi =  wifiInfo.getRssi();
        if (aggressiveHandover != 0) {
            rssi -= AGGRESSIVE_HANDOVER_PENALTY * aggressiveHandover;
        }
        if (mIsHomeNetwork) {
            rssi += WifiConfiguration.HOME_NETWORK_RSSI_BOOST;
        }

        if ((wifiInfo.txBadRate >= 1)
                && (wifiInfo.txSuccessRate < MAX_SUCCESS_RATE_OF_STUCK_LINK)
                && rssi < rssiThreshLow) {
            // Link is stuck
            if (wifiInfo.linkStuckCount < MAX_STUCK_LINK_COUNT) {
                wifiInfo.linkStuckCount += 1;
            }
        } else if (wifiInfo.txBadRate < MIN_TX_FAILURE_RATE_FOR_WORKING_LINK) {
            if (wifiInfo.linkStuckCount > 0) {
                wifiInfo.linkStuckCount -= 1;
            }
        }

        if (rssi < rssiThreshBad) {
            if (wifiInfo.badRssiCount < MAX_BAD_RSSI_COUNT) {
                wifiInfo.badRssiCount += 1;
            }
        } else if (rssi < rssiThreshLow) {
            wifiInfo.lowRssiCount = MAX_LOW_RSSI_COUNT; // Dont increment the lowRssi count above 1
            if (wifiInfo.badRssiCount > 0) {
                // Decrement bad Rssi count
                wifiInfo.badRssiCount -= 1;
            }
        } else {
            wifiInfo.badRssiCount = 0;
            wifiInfo.lowRssiCount = 0;
        }

    }

    /**
     * Calculates the score, without all the cruft.
     */
    private int calculateScore(WifiInfo wifiInfo, int aggressiveHandover) {
        int score = STARTING_SCORE;

        int rssiThreshSaturated = mThresholdSaturatedRssi24;
        int linkspeedThreshBad = mBadLinkSpeed24;
        int linkspeedThreshGood = mGoodLinkSpeed24;

        if (wifiInfo.is5GHz()) {
            if (!mMultiBandScanResults) {
                rssiThreshSaturated = mThresholdSaturatedRssi5;
            }
            linkspeedThreshBad = mBadLinkSpeed5;
            linkspeedThreshGood = mGoodLinkSpeed5;
        }

        int rssi =  wifiInfo.getRssi();
        if (aggressiveHandover != 0) {
            rssi -= AGGRESSIVE_HANDOVER_PENALTY * aggressiveHandover;
        }
        if (mIsHomeNetwork) {
            rssi += WifiConfiguration.HOME_NETWORK_RSSI_BOOST;
        }

        int linkSpeed = wifiInfo.getLinkSpeed();

        if (wifiInfo.linkStuckCount > MIN_SUSTAINED_LINK_STUCK_COUNT) {
            // Once link gets stuck for more than 3 seconds, start reducing the score
            score = score - LINK_STUCK_PENALTY * (wifiInfo.linkStuckCount - 1);
        }

        if (linkSpeed < linkspeedThreshBad) {
            score -= BAD_LINKSPEED_PENALTY;
        } else if ((linkSpeed >= linkspeedThreshGood) && (wifiInfo.txSuccessRate > 5)) {
            score += GOOD_LINKSPEED_BONUS; // So as bad rssi alone doesn't kill us
        }

        score -= wifiInfo.badRssiCount * BAD_RSSI_COUNT_PENALTY + wifiInfo.lowRssiCount;

        if (rssi >= rssiThreshSaturated) score += 5;

        if (score > NetworkAgent.WIFI_BASE_SCORE) score = NetworkAgent.WIFI_BASE_SCORE;
        if (score < 0) score = 0;

        return score;
    }

    /**
     * Determines if we can see both 2.4GHz and 5GHz for current config
     */
    private boolean multiBandScanResults(WifiInfo wifiInfo) {
        WifiConfiguration currentConfiguration =
                mWifiConfigManager.getConfiguredNetwork(wifiInfo.getNetworkId());
        if (currentConfiguration == null) return false;
        ScanDetailCache scanDetailCache =
                mWifiConfigManager.getScanDetailCacheForNetwork(wifiInfo.getNetworkId());
        if (scanDetailCache == null) return false;
        // Nasty that we change state here...
        currentConfiguration.setVisibility(scanDetailCache.getVisibility(SCAN_CACHE_VISIBILITY_MS));
        if (currentConfiguration.visibility == null) return false;
        if (currentConfiguration.visibility.rssi24 == WifiConfiguration.INVALID_RSSI) return false;
        if (currentConfiguration.visibility.rssi5 == WifiConfiguration.INVALID_RSSI) return false;
        // N.B. this does not do exactly what is claimed!
        if (currentConfiguration.visibility.rssi24
                >= currentConfiguration.visibility.rssi5 - SCAN_CACHE_COUNT_PENALTY) {
            return true;
        }
        return false;
    }

    /**
     * Decides whether the current network is a "home" network
     */
    private boolean isHomeNetwork(WifiInfo wifiInfo) {
        WifiConfiguration currentConfiguration =
                mWifiConfigManager.getConfiguredNetwork(wifiInfo.getNetworkId());
        if (currentConfiguration == null) return false;
        // This seems like it will only return true for really old routers!
        if (currentConfiguration.allowedKeyManagement.cardinality() != 1) return false;
        if (!currentConfiguration.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.WPA_PSK)) {
            return false;
        }
        ScanDetailCache scanDetailCache =
                mWifiConfigManager.getScanDetailCacheForNetwork(wifiInfo.getNetworkId());
        if (scanDetailCache == null) return false;
        if (scanDetailCache.size() <= HOME_VISIBLE_NETWORK_MAX_COUNT) {
            return true;
        }
        return false;
    }
}
