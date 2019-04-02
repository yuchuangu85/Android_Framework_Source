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

import android.annotation.Nullable;
import android.content.Context;
import android.database.ContentObserver;
import android.net.NetworkKey;
import android.net.NetworkScoreManager;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiNetworkScoreCache;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.LocalLog;
import android.util.Log;
import android.util.Pair;

import com.android.server.wifi.util.ScanResultUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link WifiNetworkSelector.NetworkEvaluator} implementation that uses scores obtained by
 * {@link NetworkScoreManager#requestScores(NetworkKey[])} to make network connection decisions.
 */
public class ScoredNetworkEvaluator implements WifiNetworkSelector.NetworkEvaluator {
    private static final String TAG = "ScoredNetworkEvaluator";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private final NetworkScoreManager mNetworkScoreManager;
    private final WifiConfigManager mWifiConfigManager;
    private final LocalLog mLocalLog;
    private final ContentObserver mContentObserver;
    private boolean mNetworkRecommendationsEnabled;
    private WifiNetworkScoreCache mScoreCache;

    ScoredNetworkEvaluator(final Context context, Looper looper,
            final FrameworkFacade frameworkFacade, NetworkScoreManager networkScoreManager,
            WifiConfigManager wifiConfigManager, LocalLog localLog,
            WifiNetworkScoreCache wifiNetworkScoreCache) {
        mScoreCache = wifiNetworkScoreCache;
        mNetworkScoreManager = networkScoreManager;
        mWifiConfigManager = wifiConfigManager;
        mLocalLog = localLog;
        mContentObserver = new ContentObserver(new Handler(looper)) {
            @Override
            public void onChange(boolean selfChange) {
                mNetworkRecommendationsEnabled = frameworkFacade.getIntegerSetting(context,
                        Settings.Global.NETWORK_RECOMMENDATIONS_ENABLED, 0) == 1;
            }
        };
        frameworkFacade.registerContentObserver(context,
                Settings.Global.getUriFor(Settings.Global.NETWORK_RECOMMENDATIONS_ENABLED),
                false /* notifyForDescendents */, mContentObserver);
        mContentObserver.onChange(false /* unused */);
        mLocalLog.log("ScoredNetworkEvaluator constructed. mNetworkRecommendationsEnabled: "
                + mNetworkRecommendationsEnabled);
    }

    @Override
    public void update(List<ScanDetail> scanDetails) {
        if (mNetworkRecommendationsEnabled) {
            updateNetworkScoreCache(scanDetails);
        }
    }

    private void updateNetworkScoreCache(List<ScanDetail> scanDetails) {
        ArrayList<NetworkKey> unscoredNetworks = new ArrayList<NetworkKey>();
        for (int i = 0; i < scanDetails.size(); i++) {
            ScanResult scanResult = scanDetails.get(i).getScanResult();
            NetworkKey networkKey = NetworkKey.createFromScanResult(scanResult);
            if (networkKey != null) {
                // Is there a ScoredNetwork for this ScanResult? If not, request a score.
                if (mScoreCache.getScoredNetwork(networkKey) == null) {
                    unscoredNetworks.add(networkKey);
                }
            }
        }

        // Kick the score manager if there are any unscored network.
        if (!unscoredNetworks.isEmpty()) {
            NetworkKey[] unscoredNetworkKeys =
                    unscoredNetworks.toArray(new NetworkKey[unscoredNetworks.size()]);
            mNetworkScoreManager.requestScores(unscoredNetworkKeys);
        }
    }

    @Override
    public WifiConfiguration evaluateNetworks(List<ScanDetail> scanDetails,
            WifiConfiguration currentNetwork, String currentBssid, boolean connected,
            boolean untrustedNetworkAllowed,
            List<Pair<ScanDetail, WifiConfiguration>> connectableNetworks) {
        if (!mNetworkRecommendationsEnabled) {
            mLocalLog.log("Skipping evaluateNetworks; Network recommendations disabled.");
            return null;
        }

        final ScoreTracker scoreTracker = new ScoreTracker();
        for (int i = 0; i < scanDetails.size(); i++) {
            ScanDetail scanDetail = scanDetails.get(i);
            ScanResult scanResult = scanDetail.getScanResult();
            if (scanResult == null) continue;
            if (mWifiConfigManager.wasEphemeralNetworkDeleted(
                    ScanResultUtil.createQuotedSSID(scanResult.SSID))) {
                debugLog("Ignoring disabled ephemeral SSID: " + scanResult.SSID);
                continue;
            }
            final WifiConfiguration configuredNetwork =
                    mWifiConfigManager.getConfiguredNetworkForScanDetailAndCache(scanDetail);
            boolean untrustedScanResult = configuredNetwork == null || configuredNetwork.ephemeral;

            if (!untrustedNetworkAllowed && untrustedScanResult) {
                continue;
            }

            // Track scan results for open wifi networks
            if (configuredNetwork == null) {
                if (ScanResultUtil.isScanResultForOpenNetwork(scanResult)) {
                    scoreTracker.trackUntrustedCandidate(scanResult);
                }
                continue;
            }

            // Ignore non-ephemeral and non-externally scored networks
            if (!configuredNetwork.ephemeral && !configuredNetwork.useExternalScores) {
                continue;
            }

            // Ignore externally scored or ephemeral networks that have been disabled for selection
            if (!configuredNetwork.getNetworkSelectionStatus().isNetworkEnabled()) {
                debugLog("Ignoring disabled SSID: " + configuredNetwork.SSID);
                continue;
            }

            // TODO(b/37485956): consider applying a boost for networks with only the same SSID
            boolean isCurrentNetwork = currentNetwork != null
                    && currentNetwork.networkId == configuredNetwork.networkId
                    && TextUtils.equals(currentBssid, scanResult.BSSID);
            if (configuredNetwork.ephemeral) {
                scoreTracker.trackUntrustedCandidate(
                        scanResult, configuredNetwork, isCurrentNetwork);
            } else {
                scoreTracker.trackExternallyScoredCandidate(
                        scanResult, configuredNetwork, isCurrentNetwork);
            }
            if (connectableNetworks != null) {
                connectableNetworks.add(Pair.create(scanDetail, configuredNetwork));
            }
        }

        return scoreTracker.getCandidateConfiguration();
    }

    /** Used to track the network with the highest score. */
    class ScoreTracker {
        private static final int EXTERNAL_SCORED_NONE = 0;
        private static final int EXTERNAL_SCORED_SAVED_NETWORK = 1;
        private static final int EXTERNAL_SCORED_UNTRUSTED_NETWORK = 2;

        private int mBestCandidateType = EXTERNAL_SCORED_NONE;
        private int mHighScore = WifiNetworkScoreCache.INVALID_NETWORK_SCORE;
        private WifiConfiguration mEphemeralConfig;
        private WifiConfiguration mSavedConfig;
        private ScanResult mScanResultCandidate;

        /**
         * Returns the available external network score or null if no score is available.
         *
         * @param scanResult The scan result of the network to score.
         * @param isCurrentNetwork Flag which indicates whether this is the current network.
         * @return A valid external score if one is available or NULL.
         */
        @Nullable
        private Integer getNetworkScore(ScanResult scanResult, boolean isCurrentNetwork) {
            if (mScoreCache.isScoredNetwork(scanResult)) {
                int score = mScoreCache.getNetworkScore(scanResult, isCurrentNetwork);
                if (DEBUG) {
                    mLocalLog.log(WifiNetworkSelector.toScanId(scanResult) + " has score: "
                            + score + " isCurrentNetwork network: " + isCurrentNetwork);
                }
                return score;
            }
            return null;
        }

        /** Track an untrusted {@link ScanResult}. */
        void trackUntrustedCandidate(ScanResult scanResult) {
            Integer score = getNetworkScore(scanResult, false /* isCurrentNetwork */);
            if (score != null && score > mHighScore) {
                mHighScore = score;
                mScanResultCandidate = scanResult;
                mBestCandidateType = EXTERNAL_SCORED_UNTRUSTED_NETWORK;
                debugLog(WifiNetworkSelector.toScanId(scanResult)
                        + " becomes the new untrusted candidate.");
            }
        }

        /**
         * Track an untrusted {@link ScanResult} that already has a corresponding
         * ephemeral {@link WifiConfiguration}.
         */
        void trackUntrustedCandidate(
                ScanResult scanResult, WifiConfiguration config, boolean isCurrentNetwork) {
            Integer score = getNetworkScore(scanResult, isCurrentNetwork);
            if (score != null && score > mHighScore) {
                mHighScore = score;
                mScanResultCandidate = scanResult;
                mBestCandidateType = EXTERNAL_SCORED_UNTRUSTED_NETWORK;
                mEphemeralConfig = config;
                mWifiConfigManager.setNetworkCandidateScanResult(config.networkId, scanResult, 0);
                debugLog(WifiNetworkSelector.toScanId(scanResult)
                        + " becomes the new untrusted candidate.");
            }
        }

        /** Tracks a saved network that has been marked with useExternalScores */
        void trackExternallyScoredCandidate(
                ScanResult scanResult, WifiConfiguration config, boolean isCurrentNetwork) {
            // Always take the highest score. If there's a tie and an untrusted network is currently
            // the best then pick the saved network.
            Integer score = getNetworkScore(scanResult, isCurrentNetwork);
            if (score != null
                    && (score > mHighScore
                    || (mBestCandidateType == EXTERNAL_SCORED_UNTRUSTED_NETWORK
                    && score == mHighScore))) {
                mHighScore = score;
                mSavedConfig = config;
                mScanResultCandidate = scanResult;
                mBestCandidateType = EXTERNAL_SCORED_SAVED_NETWORK;
                mWifiConfigManager.setNetworkCandidateScanResult(config.networkId, scanResult, 0);
                debugLog(WifiNetworkSelector.toScanId(scanResult)
                        + " becomes the new externally scored saved network candidate.");
            }
        }

        /** Returns the best candidate network tracked by this {@link ScoreTracker}. */
        @Nullable
        WifiConfiguration getCandidateConfiguration() {
            int candidateNetworkId = WifiConfiguration.INVALID_NETWORK_ID;
            switch (mBestCandidateType) {
                case ScoreTracker.EXTERNAL_SCORED_UNTRUSTED_NETWORK:
                    if (mEphemeralConfig != null) {
                        candidateNetworkId = mEphemeralConfig.networkId;
                        mLocalLog.log(String.format("existing ephemeral candidate %s network ID:%d"
                                        + ", meteredHint=%b",
                                WifiNetworkSelector.toScanId(mScanResultCandidate),
                                candidateNetworkId,
                                mEphemeralConfig.meteredHint));
                        break;
                    }

                    mEphemeralConfig =
                            ScanResultUtil.createNetworkFromScanResult(mScanResultCandidate);
                    // Mark this config as ephemeral so it isn't persisted.
                    mEphemeralConfig.ephemeral = true;
                    mEphemeralConfig.meteredHint = mScoreCache.getMeteredHint(mScanResultCandidate);
                    NetworkUpdateResult result =
                            mWifiConfigManager.addOrUpdateNetwork(mEphemeralConfig,
                                    Process.WIFI_UID);
                    if (!result.isSuccess()) {
                        mLocalLog.log("Failed to add ephemeral network");
                        break;
                    }
                    if (!mWifiConfigManager.updateNetworkSelectionStatus(result.getNetworkId(),
                            WifiConfiguration.NetworkSelectionStatus.NETWORK_SELECTION_ENABLE)) {
                        mLocalLog.log("Failed to make ephemeral network selectable");
                        break;
                    }
                    candidateNetworkId = result.getNetworkId();
                    mWifiConfigManager.setNetworkCandidateScanResult(candidateNetworkId,
                            mScanResultCandidate, 0);
                    mLocalLog.log(String.format("new ephemeral candidate %s network ID:%d, "
                                                + "meteredHint=%b",
                                        WifiNetworkSelector.toScanId(mScanResultCandidate),
                                        candidateNetworkId,
                                        mEphemeralConfig.meteredHint));
                    break;
                case ScoreTracker.EXTERNAL_SCORED_SAVED_NETWORK:
                    candidateNetworkId = mSavedConfig.networkId;
                    mLocalLog.log(String.format("new saved network candidate %s network ID:%d",
                                        WifiNetworkSelector.toScanId(mScanResultCandidate),
                                        candidateNetworkId));
                    break;
                case ScoreTracker.EXTERNAL_SCORED_NONE:
                default:
                    mLocalLog.log("ScoredNetworkEvaluator did not see any good candidates.");
                    break;
            }
            return mWifiConfigManager.getConfiguredNetwork(candidateNetworkId);
        }
    }

    private void debugLog(String msg) {
        if (DEBUG) {
            mLocalLog.log(msg);
        }
    }

    @Override
    public String getName() {
        return TAG;
    }
}
