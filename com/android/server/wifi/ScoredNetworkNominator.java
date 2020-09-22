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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.net.NetworkKey;
import android.net.NetworkScoreManager;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.os.Handler;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.LocalLog;
import android.util.Log;
import android.util.Pair;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.wifi.util.ScanResultUtil;
import com.android.server.wifi.util.WifiPermissionsUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link WifiNetworkSelector.NetworkNominator} implementation that uses scores obtained by
 * {@link NetworkScoreManager#requestScores(NetworkKey[])} to make network connection decisions.
 */
public class ScoredNetworkNominator implements WifiNetworkSelector.NetworkNominator {
    private static final String TAG = "ScoredNetworkNominator";
    // TODO (b/150977740): Stop using the @hide settings global flag.
    @VisibleForTesting
    public static final String SETTINGS_GLOBAL_USE_OPEN_WIFI_PACKAGE =
            "use_open_wifi_package";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private final NetworkScoreManager mNetworkScoreManager;
    private final PackageManager mPackageManager;
    private final WifiConfigManager mWifiConfigManager;
    private final LocalLog mLocalLog;
    private final ContentObserver mContentObserver;
    private final WifiPermissionsUtil mWifiPermissionsUtil;
    private boolean mNetworkRecommendationsEnabled;
    private WifiNetworkScoreCache mScoreCache;

    ScoredNetworkNominator(final Context context, Handler handler,
            final FrameworkFacade frameworkFacade, NetworkScoreManager networkScoreManager,
            PackageManager packageManager,
            WifiConfigManager wifiConfigManager, LocalLog localLog,
            WifiNetworkScoreCache wifiNetworkScoreCache,
            WifiPermissionsUtil wifiPermissionsUtil) {
        mScoreCache = wifiNetworkScoreCache;
        mWifiPermissionsUtil = wifiPermissionsUtil;
        mNetworkScoreManager = networkScoreManager;
        mPackageManager = packageManager;
        mWifiConfigManager = wifiConfigManager;
        mLocalLog = localLog;
        mContentObserver = new ContentObserver(handler) {
            @Override
            public void onChange(boolean selfChange) {
                mNetworkRecommendationsEnabled = frameworkFacade.getStringSetting(context,
                        SETTINGS_GLOBAL_USE_OPEN_WIFI_PACKAGE) != null;
            }
        };
        frameworkFacade.registerContentObserver(context,
                Settings.Global.getUriFor(SETTINGS_GLOBAL_USE_OPEN_WIFI_PACKAGE),
                false /* notifyForDescendents */, mContentObserver);
        mContentObserver.onChange(false /* unused */);
        mLocalLog.log("ScoredNetworkNominator constructed. mNetworkRecommendationsEnabled: "
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
        if (!unscoredNetworks.isEmpty() && activeScorerAllowedtoSeeScanResults()) {
            mNetworkScoreManager.requestScores(unscoredNetworks);
        }
    }

    private Pair<Integer, String> getActiveScorerUidAndPackage() {
        String packageName = mNetworkScoreManager.getActiveScorerPackage();
        if (packageName == null) return null;
        int uid = -1;
        try {
            uid = mPackageManager.getApplicationInfo(packageName, 0).uid;
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, "Failed to retrieve package uid", e);
            return null;
        }
        return Pair.create(uid, packageName);
    }

    private boolean activeScorerAllowedtoSeeScanResults() {
        Pair<Integer, String> scorerUidAndPackage = getActiveScorerUidAndPackage();
        if (scorerUidAndPackage == null) return false;
        try {
            // TODO moltmann: Can we set a featureID here instead of null?
            mWifiPermissionsUtil.enforceCanAccessScanResults(
                    scorerUidAndPackage.second, null, scorerUidAndPackage.first, null);
            return true;
        } catch (SecurityException e) {
            return false;
        }
    }

    @Override
    public void nominateNetworks(List<ScanDetail> scanDetails,
            WifiConfiguration currentNetwork, String currentBssid, boolean connected,
            boolean untrustedNetworkAllowed,
            @NonNull OnConnectableListener onConnectableListener) {
        if (!mNetworkRecommendationsEnabled) {
            mLocalLog.log("Skipping nominateNetworks; Network recommendations disabled.");
            return;
        }

        final ScoreTracker scoreTracker = new ScoreTracker();
        for (int i = 0; i < scanDetails.size(); i++) {
            ScanDetail scanDetail = scanDetails.get(i);
            ScanResult scanResult = scanDetail.getScanResult();
            if (scanResult == null) continue;
            if (mWifiConfigManager.isNetworkTemporarilyDisabledByUser(
                    ScanResultUtil.createQuotedSSID(scanResult.SSID))) {
                debugLog("Ignoring user disabled SSID: " + scanResult.SSID);
                continue;
            }
            final WifiConfiguration configuredNetwork =
                    mWifiConfigManager.getConfiguredNetworkForScanDetailAndCache(scanDetail);
            boolean untrustedScanResult = configuredNetwork == null || !configuredNetwork.trusted;

            if (!untrustedNetworkAllowed && untrustedScanResult) {
                continue;
            }

            // Track scan results for open wifi networks
            if (configuredNetwork == null) {
                if (ScanResultUtil.isScanResultForOpenNetwork(scanResult)) {
                    scoreTracker.trackUntrustedCandidate(scanDetail);
                }
                continue;
            }

            // Ignore trusted and non-externally scored networks
            if (configuredNetwork.trusted && !configuredNetwork.useExternalScores) {
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
            if (!configuredNetwork.trusted) {
                scoreTracker.trackUntrustedCandidate(
                        scanResult, configuredNetwork, isCurrentNetwork);
            } else {
                scoreTracker.trackExternallyScoredCandidate(
                        scanResult, configuredNetwork, isCurrentNetwork);
            }
            onConnectableListener.onConnectable(scanDetail, configuredNetwork);
        }
        scoreTracker.getCandidateConfiguration(onConnectableListener);
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
        private ScanDetail mScanDetailCandidate;

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

        /** Track an untrusted {@link ScanDetail}. */
        void trackUntrustedCandidate(ScanDetail scanDetail) {
            ScanResult scanResult = scanDetail.getScanResult();
            Integer score = getNetworkScore(scanResult, false /* isCurrentNetwork */);
            if (score != null && score > mHighScore) {
                mHighScore = score;
                mScanResultCandidate = scanResult;
                mScanDetailCandidate = scanDetail;
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
                mScanDetailCandidate = null;
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
                mScanDetailCandidate = null;
                mBestCandidateType = EXTERNAL_SCORED_SAVED_NETWORK;
                mWifiConfigManager.setNetworkCandidateScanResult(config.networkId, scanResult, 0);
                debugLog(WifiNetworkSelector.toScanId(scanResult)
                        + " becomes the new externally scored saved network candidate.");
            }
        }

        /** Returns the best candidate network tracked by this {@link ScoreTracker}. */
        @Nullable
        WifiConfiguration getCandidateConfiguration(
                @NonNull OnConnectableListener onConnectableListener) {
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
                    Pair<Integer, String> scorerUidAndPackage = getActiveScorerUidAndPackage();
                    if (scorerUidAndPackage == null) {
                        mLocalLog.log("Can't find active scorer uid and package");
                        break;
                    }

                    mEphemeralConfig =
                            ScanResultUtil.createNetworkFromScanResult(mScanResultCandidate);
                    // Mark this config as ephemeral so it isn't persisted.
                    mEphemeralConfig.ephemeral = true;
                    // Mark this network as untrusted.
                    mEphemeralConfig.trusted = false;
                    mEphemeralConfig.meteredHint = mScoreCache.getMeteredHint(mScanResultCandidate);
                    NetworkUpdateResult result =
                            mWifiConfigManager.addOrUpdateNetwork(mEphemeralConfig,
                                    scorerUidAndPackage.first, scorerUidAndPackage.second);
                    if (!result.isSuccess()) {
                        mLocalLog.log("Failed to add ephemeral network");
                        break;
                    }
                    if (!mWifiConfigManager.updateNetworkSelectionStatus(result.getNetworkId(),
                            WifiConfiguration.NetworkSelectionStatus.DISABLED_NONE)) {
                        mLocalLog.log("Failed to make ephemeral network selectable");
                        break;
                    }
                    candidateNetworkId = result.getNetworkId();
                    if (mScanDetailCandidate == null) {
                        // This should never happen, but if it does, WNS will log a wtf.
                        // A message here might help with the diagnosis.
                        Log.e(TAG, "mScanDetailCandidate is null!");
                    }
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
                    mLocalLog.log("ScoredNetworkNominator did not see any good candidates.");
                    break;
            }
            WifiConfiguration ans = mWifiConfigManager.getConfiguredNetwork(
                    candidateNetworkId);
            if (ans != null && mScanDetailCandidate != null) {
                // This is a newly created config, so we need to call onConnectable.
                onConnectableListener.onConnectable(mScanDetailCandidate, ans);
            }
            return ans;
        }
    }

    private void debugLog(String msg) {
        if (DEBUG) {
            mLocalLog.log(msg);
        }
    }

    @Override
    public @NominatorId int getId() {
        return NOMINATOR_ID_SCORED;
    }

    @Override
    public String getName() {
        return TAG;
    }
}
