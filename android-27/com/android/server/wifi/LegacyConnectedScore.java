/*
 * Copyright (C) 2017 The Android Open Source Project
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

import com.android.internal.R;

/**
 * Class used to calculate scores for connected wifi networks and report it to the associated
 * network agent.
 */
public class LegacyConnectedScore extends ConnectedScore {

    private static final int STARTING_SCORE = 56;

    private static final int SCAN_CACHE_VISIBILITY_MS = 12000;
    private static final int HOME_VISIBLE_NETWORK_MAX_COUNT = 6;
    private static final int SCAN_CACHE_COUNT_PENALTY = 2;
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

    private boolean mMultiBandScanResults;
    private boolean mIsHomeNetwork;
    private int mScore = 0;

    LegacyConnectedScore(Context context, WifiConfigManager wifiConfigManager, Clock clock) {
        super(clock);
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

    @Override
    public void updateUsingWifiInfo(WifiInfo wifiInfo, long millis) {
        mMultiBandScanResults = multiBandScanResults(wifiInfo);
        mIsHomeNetwork = isHomeNetwork(wifiInfo);

        int rssiThreshBad = mThresholdMinimumRssi24;
        int rssiThreshLow = mThresholdQualifiedRssi24;

        if (wifiInfo.is5GHz() && !mMultiBandScanResults) {
            rssiThreshBad = mThresholdMinimumRssi5;
            rssiThreshLow = mThresholdQualifiedRssi5;
        }

        int rssi =  wifiInfo.getRssi();
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

        // Ugh, we need to finish the score calculation while we have wifiInfo
        mScore = calculateScore(wifiInfo);

    }

    @Override
    public void updateUsingRssi(int rssi, long millis, double standardDeviation) {
        // This scorer needs more than just the RSSI. Just ignore.
    }

    @Override
    public int generateScore() {
        return mScore;
    }

    @Override
    public void reset() {
        mScore = 0;
    }

    /**
     * Calculates a score based on the current state and wifiInfo
     */
    private int calculateScore(WifiInfo wifiInfo) {
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
