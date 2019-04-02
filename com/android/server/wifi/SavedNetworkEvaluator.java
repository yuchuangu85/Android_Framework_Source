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
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.util.LocalLog;
import android.util.Pair;

import com.android.internal.R;
import com.android.server.wifi.util.TelephonyUtil;

import java.util.List;

/**
 * This class is the WifiNetworkSelector.NetworkEvaluator implementation for
 * saved networks.
 */
public class SavedNetworkEvaluator implements WifiNetworkSelector.NetworkEvaluator {
    private static final String NAME = "SavedNetworkEvaluator";
    private final WifiConfigManager mWifiConfigManager;
    private final Clock mClock;
    private final LocalLog mLocalLog;
    private final WifiConnectivityHelper mConnectivityHelper;
    private final int mRssiScoreSlope;
    private final int mRssiScoreOffset;
    private final int mSameBssidAward;
    private final int mSameNetworkAward;
    private final int mBand5GHzAward;
    private final int mLastSelectionAward;
    private final int mSecurityAward;
    private final ScoringParams mScoringParams;

    SavedNetworkEvaluator(final Context context, ScoringParams scoringParams,
            WifiConfigManager configManager, Clock clock,
            LocalLog localLog, WifiConnectivityHelper connectivityHelper) {
        mScoringParams = scoringParams;
        mWifiConfigManager = configManager;
        mClock = clock;
        mLocalLog = localLog;
        mConnectivityHelper = connectivityHelper;

        mRssiScoreSlope = context.getResources().getInteger(
                R.integer.config_wifi_framework_RSSI_SCORE_SLOPE);
        mRssiScoreOffset = context.getResources().getInteger(
                R.integer.config_wifi_framework_RSSI_SCORE_OFFSET);
        mSameBssidAward = context.getResources().getInteger(
                R.integer.config_wifi_framework_SAME_BSSID_AWARD);
        mSameNetworkAward = context.getResources().getInteger(
                R.integer.config_wifi_framework_current_network_boost);
        mLastSelectionAward = context.getResources().getInteger(
                R.integer.config_wifi_framework_LAST_SELECTION_AWARD);
        mSecurityAward = context.getResources().getInteger(
                R.integer.config_wifi_framework_SECURITY_AWARD);
        mBand5GHzAward = context.getResources().getInteger(
                R.integer.config_wifi_framework_5GHz_preference_boost_factor);
    }

    private void localLog(String log) {
        mLocalLog.log(log);
    }

    /**
     * Get the evaluator name.
     */
    public String getName() {
        return NAME;
    }

    /**
     * Update all the saved networks' selection status
     */
    private void updateSavedNetworkSelectionStatus() {
        List<WifiConfiguration> savedNetworks = mWifiConfigManager.getSavedNetworks();
        if (savedNetworks.size() == 0) {
            localLog("No saved networks.");
            return;
        }

        StringBuffer sbuf = new StringBuffer();
        for (WifiConfiguration network : savedNetworks) {
            /**
             * Ignore Passpoint networks. Passpoint networks are also considered as "saved"
             * network, but without being persisted to the storage. They are managed
             * by {@link PasspointNetworkEvaluator}.
             */
            if (network.isPasspoint()) {
                continue;
            }

            // If a configuration is temporarily disabled, re-enable it before trying
            // to connect to it.
            mWifiConfigManager.tryEnableNetwork(network.networkId);

            //TODO(b/30928589): Enable "permanently" disabled networks if we are in DISCONNECTED
            // state.

            // Clear the cached candidate, score and seen.
            mWifiConfigManager.clearNetworkCandidateScanResult(network.networkId);

            // Log disabled network.
            WifiConfiguration.NetworkSelectionStatus status = network.getNetworkSelectionStatus();
            if (!status.isNetworkEnabled()) {
                sbuf.append("  ").append(WifiNetworkSelector.toNetworkString(network)).append(" ");
                for (int index = WifiConfiguration.NetworkSelectionStatus
                            .NETWORK_SELECTION_DISABLED_STARTING_INDEX;
                        index < WifiConfiguration.NetworkSelectionStatus
                            .NETWORK_SELECTION_DISABLED_MAX;
                        index++) {
                    int count = status.getDisableReasonCounter(index);
                    // Here we log the reason as long as its count is greater than zero. The
                    // network may not be disabled because of this particular reason. Logging
                    // this information anyway to help understand what happened to the network.
                    if (count > 0) {
                        sbuf.append("reason=")
                                .append(WifiConfiguration.NetworkSelectionStatus
                                        .getNetworkDisableReasonString(index))
                                .append(", count=").append(count).append("; ");
                    }
                }
                sbuf.append("\n");
            }
        }

        if (sbuf.length() > 0) {
            localLog("Disabled saved networks:");
            localLog(sbuf.toString());
        }
    }

    /**
     * Update the evaluator.
     */
    public void update(List<ScanDetail> scanDetails) {
        updateSavedNetworkSelectionStatus();
    }

    private int calculateBssidScore(ScanResult scanResult, WifiConfiguration network,
                        WifiConfiguration currentNetwork, String currentBssid,
                        StringBuffer sbuf) {
        int score = 0;
        boolean is5GHz = scanResult.is5GHz();

        sbuf.append("[ ").append(scanResult.SSID).append(" ").append(scanResult.BSSID)
                .append(" RSSI:").append(scanResult.level).append(" ] ");
        // Calculate the RSSI score.
        int rssiSaturationThreshold = mScoringParams.getGoodRssi(scanResult.frequency);
        int rssi = scanResult.level < rssiSaturationThreshold ? scanResult.level
                : rssiSaturationThreshold;
        score += (rssi + mRssiScoreOffset) * mRssiScoreSlope;
        sbuf.append(" RSSI score: ").append(score).append(",");

        // 5GHz band bonus.
        if (is5GHz) {
            score += mBand5GHzAward;
            sbuf.append(" 5GHz bonus: ").append(mBand5GHzAward).append(",");
        }

        // Last user selection award.
        int lastUserSelectedNetworkId = mWifiConfigManager.getLastSelectedNetwork();
        if (lastUserSelectedNetworkId != WifiConfiguration.INVALID_NETWORK_ID
                && lastUserSelectedNetworkId == network.networkId) {
            long timeDifference = mClock.getElapsedSinceBootMillis()
                    - mWifiConfigManager.getLastSelectedTimeStamp();
            if (timeDifference > 0) {
                int bonus = mLastSelectionAward - (int) (timeDifference / 1000 / 60);
                score += bonus > 0 ? bonus : 0;
                sbuf.append(" User selection ").append(timeDifference / 1000 / 60)
                        .append(" minutes ago, bonus: ").append(bonus).append(",");
            }
        }

        // Same network award.
        if (currentNetwork != null
                && (network.networkId == currentNetwork.networkId
                //TODO(b/36788683): re-enable linked configuration check
                /* || network.isLinked(currentNetwork) */)) {
            score += mSameNetworkAward;
            sbuf.append(" Same network bonus: ").append(mSameNetworkAward).append(",");

            // When firmware roaming is supported, equivalent BSSIDs (the ones under the
            // same network as the currently connected one) get the same BSSID award.
            if (mConnectivityHelper.isFirmwareRoamingSupported()
                    && currentBssid != null && !currentBssid.equals(scanResult.BSSID)) {
                score += mSameBssidAward;
                sbuf.append(" Equivalent BSSID bonus: ").append(mSameBssidAward).append(",");
            }
        }

        // Same BSSID award.
        if (currentBssid != null && currentBssid.equals(scanResult.BSSID)) {
            score += mSameBssidAward;
            sbuf.append(" Same BSSID bonus: ").append(mSameBssidAward).append(",");
        }

        // Security award.
        if (!WifiConfigurationUtil.isConfigForOpenNetwork(network)) {
            score += mSecurityAward;
            sbuf.append(" Secure network bonus: ").append(mSecurityAward).append(",");
        }

        sbuf.append(" ## Total score: ").append(score).append("\n");

        return score;
    }

    /**
     * Evaluate all the networks from the scan results and return
     * the WifiConfiguration of the network chosen for connection.
     *
     * @return configuration of the chosen network;
     *         null if no network in this category is available.
     */
    public WifiConfiguration evaluateNetworks(List<ScanDetail> scanDetails,
                    WifiConfiguration currentNetwork, String currentBssid, boolean connected,
                    boolean untrustedNetworkAllowed,
                    List<Pair<ScanDetail, WifiConfiguration>> connectableNetworks) {
        int highestScore = Integer.MIN_VALUE;
        ScanResult scanResultCandidate = null;
        WifiConfiguration candidate = null;
        StringBuffer scoreHistory = new StringBuffer();

        for (ScanDetail scanDetail : scanDetails) {
            ScanResult scanResult = scanDetail.getScanResult();

            // One ScanResult can be associated with more than one networks, hence we calculate all
            // the scores and use the highest one as the ScanResult's score.
            WifiConfiguration network =
                    mWifiConfigManager.getConfiguredNetworkForScanDetailAndCache(scanDetail);

            if (network == null) {
                continue;
            }

            /**
             * Ignore Passpoint and Ephemeral networks. They are configured networks,
             * but without being persisted to the storage. They are evaluated by
             * {@link PasspointNetworkEvaluator} and {@link ScoredNetworkEvaluator}
             * respectively.
             */
            if (network.isPasspoint() || network.isEphemeral()) {
                continue;
            }

            WifiConfiguration.NetworkSelectionStatus status =
                    network.getNetworkSelectionStatus();
            status.setSeenInLastQualifiedNetworkSelection(true);

            if (!status.isNetworkEnabled()) {
                continue;
            } else if (network.BSSID != null &&  !network.BSSID.equals("any")
                    && !network.BSSID.equals(scanResult.BSSID)) {
                // App has specified the only BSSID to connect for this
                // configuration. So only the matching ScanResult can be a candidate.
                localLog("Network " + WifiNetworkSelector.toNetworkString(network)
                        + " has specified BSSID " + network.BSSID + ". Skip "
                        + scanResult.BSSID);
                continue;
            } else if (TelephonyUtil.isSimConfig(network)
                    && !mWifiConfigManager.isSimPresent()) {
                // Don't select if security type is EAP SIM/AKA/AKA' when SIM is not present.
                continue;
            }

            int score = calculateBssidScore(scanResult, network, currentNetwork, currentBssid,
                    scoreHistory);

            // Set candidate ScanResult for all saved networks to ensure that users can
            // override network selection. See WifiNetworkSelector#setUserConnectChoice.
            // TODO(b/36067705): consider alternative designs to push filtering/selecting of
            // user connect choice networks to RecommendedNetworkEvaluator.
            if (score > status.getCandidateScore() || (score == status.getCandidateScore()
                    && status.getCandidate() != null
                    && scanResult.level > status.getCandidate().level)) {
                mWifiConfigManager.setNetworkCandidateScanResult(
                        network.networkId, scanResult, score);
            }

            // If the network is marked to use external scores, or is an open network with
            // curate saved open networks enabled, do not consider it for network selection.
            if (network.useExternalScores) {
                localLog("Network " + WifiNetworkSelector.toNetworkString(network)
                        + " has external score.");
                continue;
            }

            if (connectableNetworks != null) {
                connectableNetworks.add(Pair.create(scanDetail,
                        mWifiConfigManager.getConfiguredNetwork(network.networkId)));
            }

            if (score > highestScore
                    || (score == highestScore
                    && scanResultCandidate != null
                    && scanResult.level > scanResultCandidate.level)) {
                highestScore = score;
                scanResultCandidate = scanResult;
                mWifiConfigManager.setNetworkCandidateScanResult(
                        network.networkId, scanResultCandidate, highestScore);
                // Reload the network config with the updated info.
                candidate = mWifiConfigManager.getConfiguredNetwork(network.networkId);
            }
        }

        if (scoreHistory.length() > 0) {
            localLog("\n" + scoreHistory.toString());
        }

        if (scanResultCandidate == null) {
            localLog("did not see any good candidates.");
        }
        return candidate;
    }
}
