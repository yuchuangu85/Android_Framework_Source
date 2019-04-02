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

package com.android.server.wifi.hotspot2;

import android.net.wifi.WifiConfiguration;
import android.os.Process;
import android.text.TextUtils;
import android.util.LocalLog;
import android.util.Pair;

import com.android.server.wifi.NetworkUpdateResult;
import com.android.server.wifi.ScanDetail;
import com.android.server.wifi.WifiConfigManager;
import com.android.server.wifi.WifiNetworkSelector;
import com.android.server.wifi.util.ScanResultUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * This class is the WifiNetworkSelector.NetworkEvaluator implementation for
 * Passpoint networks.
 */
public class PasspointNetworkEvaluator implements WifiNetworkSelector.NetworkEvaluator {
    private static final String NAME = "PasspointNetworkEvaluator";

    private final PasspointManager mPasspointManager;
    private final WifiConfigManager mWifiConfigManager;
    private final LocalLog mLocalLog;

    /**
     * Contained information for a Passpoint network candidate.
     */
    private class PasspointNetworkCandidate {
        PasspointNetworkCandidate(PasspointProvider provider, PasspointMatch matchStatus,
                ScanDetail scanDetail) {
            mProvider = provider;
            mMatchStatus = matchStatus;
            mScanDetail = scanDetail;
        }
        PasspointProvider mProvider;
        PasspointMatch mMatchStatus;
        ScanDetail mScanDetail;
    }

    public PasspointNetworkEvaluator(PasspointManager passpointManager,
            WifiConfigManager wifiConfigManager, LocalLog localLog) {
        mPasspointManager = passpointManager;
        mWifiConfigManager = wifiConfigManager;
        mLocalLog = localLog;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public void update(List<ScanDetail> scanDetails) {}

    @Override
    public WifiConfiguration evaluateNetworks(List<ScanDetail> scanDetails,
                    WifiConfiguration currentNetwork, String currentBssid,
                    boolean connected, boolean untrustedNetworkAllowed,
                    List<Pair<ScanDetail, WifiConfiguration>> connectableNetworks) {
        // Sweep the ANQP cache to remove any expired ANQP entries.
        mPasspointManager.sweepCache();

        // Go through each ScanDetail and find the best provider for each ScanDetail.
        List<PasspointNetworkCandidate> candidateList = new ArrayList<>();
        for (ScanDetail scanDetail : scanDetails) {
            // Skip non-Passpoint APs.
            if (!scanDetail.getNetworkDetail().isInterworking()) {
                continue;
            }

            // Find the best provider for this ScanDetail.
            Pair<PasspointProvider, PasspointMatch> bestProvider =
                    mPasspointManager.matchProvider(scanDetail.getScanResult());
            if (bestProvider != null) {
                if (bestProvider.first.isSimCredential() && !mWifiConfigManager.isSimPresent()) {
                    // Skip providers backed by SIM credential when SIM is not present.
                    continue;
                }
                candidateList.add(new PasspointNetworkCandidate(
                        bestProvider.first, bestProvider.second, scanDetail));
            }
        }

        // Done if no candidate is found.
        if (candidateList.isEmpty()) {
            localLog("No suitable Passpoint network found");
            return null;
        }

        // Find the best Passpoint network among all candidates.
        PasspointNetworkCandidate bestNetwork =
                findBestNetwork(candidateList, currentNetwork == null ? null : currentNetwork.SSID);

        // Return the configuration for the current connected network if it is the best network.
        if (currentNetwork != null && TextUtils.equals(currentNetwork.SSID,
                ScanResultUtil.createQuotedSSID(bestNetwork.mScanDetail.getSSID()))) {
            localLog("Staying with current Passpoint network " + currentNetwork.SSID);

            // Update current network with the latest scan info.
            mWifiConfigManager.setNetworkCandidateScanResult(currentNetwork.networkId,
                    bestNetwork.mScanDetail.getScanResult(), 0);
            mWifiConfigManager.updateScanDetailForNetwork(currentNetwork.networkId,
                    bestNetwork.mScanDetail);

            connectableNetworks.add(Pair.create(bestNetwork.mScanDetail, currentNetwork));
            return currentNetwork;
        }

        WifiConfiguration config = createWifiConfigForProvider(bestNetwork);
        connectableNetworks.add(Pair.create(bestNetwork.mScanDetail, config));
        localLog("Passpoint network to connect to: " + config.SSID);
        return config;
    }

    /**
     * Create and return a WifiConfiguration for the given ScanDetail and PasspointProvider.
     * The newly created WifiConfiguration will also be added to WifiConfigManager.
     *
     * @param networkInfo Contained information for the Passpoint network to connect to
     * @return {@link WifiConfiguration}
     */
    private WifiConfiguration createWifiConfigForProvider(PasspointNetworkCandidate networkInfo) {
        WifiConfiguration config = networkInfo.mProvider.getWifiConfig();
        config.SSID = ScanResultUtil.createQuotedSSID(networkInfo.mScanDetail.getSSID());
        if (networkInfo.mMatchStatus == PasspointMatch.HomeProvider) {
            config.isHomeProviderNetwork = true;
        }

        // Add the newly created WifiConfiguration to WifiConfigManager.
        NetworkUpdateResult result =
                mWifiConfigManager.addOrUpdateNetwork(config, Process.WIFI_UID);
        if (!result.isSuccess()) {
            localLog("Failed to add passpoint network");
            return null;
        }
        mWifiConfigManager.enableNetwork(result.getNetworkId(), false, Process.WIFI_UID);
        mWifiConfigManager.setNetworkCandidateScanResult(result.getNetworkId(),
                networkInfo.mScanDetail.getScanResult(), 0);
        mWifiConfigManager.updateScanDetailForNetwork(
                result.getNetworkId(), networkInfo.mScanDetail);
        return mWifiConfigManager.getConfiguredNetwork(result.getNetworkId());
    }

    /**
     * Given a list of Passpoint networks (with both provider and scan info), find and return
     * the one with highest score.  The score is calculated using
     * {@link PasspointNetworkScore#calculateScore}.
     *
     * @param networkList List of Passpoint networks
     * @param currentNetworkSsid The SSID of the currently connected network, null if not connected
     * @return {@link PasspointNetworkCandidate}
     */
    private PasspointNetworkCandidate findBestNetwork(
            List<PasspointNetworkCandidate> networkList, String currentNetworkSsid) {
        PasspointNetworkCandidate bestCandidate = null;
        int bestScore = Integer.MIN_VALUE;
        for (PasspointNetworkCandidate candidate : networkList) {
            ScanDetail scanDetail = candidate.mScanDetail;
            PasspointMatch match = candidate.mMatchStatus;

            boolean isActiveNetwork = TextUtils.equals(currentNetworkSsid,
                    ScanResultUtil.createQuotedSSID(scanDetail.getSSID()));
            int score = PasspointNetworkScore.calculateScore(match == PasspointMatch.HomeProvider,
                    scanDetail, mPasspointManager.getANQPElements(scanDetail.getScanResult()),
                    isActiveNetwork);

            if (score > bestScore) {
                bestCandidate = candidate;
                bestScore = score;
            }
        }
        localLog("Best Passpoint network " + bestCandidate.mScanDetail.getSSID() + " provided by "
                + bestCandidate.mProvider.getConfig().getHomeSp().getFqdn());
        return bestCandidate;
    }

    private void localLog(String log) {
        mLocalLog.log(log);
    }
}
