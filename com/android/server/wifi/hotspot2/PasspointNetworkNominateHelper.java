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

import android.annotation.NonNull;
import android.net.wifi.WifiConfiguration;
import android.os.Process;
import android.util.LocalLog;
import android.util.Pair;

import com.android.server.wifi.NetworkUpdateResult;
import com.android.server.wifi.ScanDetail;
import com.android.server.wifi.WifiConfigManager;
import com.android.server.wifi.WifiNetworkSelector;
import com.android.server.wifi.hotspot2.anqp.ANQPElement;
import com.android.server.wifi.hotspot2.anqp.Constants;
import com.android.server.wifi.hotspot2.anqp.HSWanMetricsElement;
import com.android.server.wifi.util.ScanResultUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * This class is the WifiNetworkSelector.NetworkNominator implementation for
 * Passpoint networks.
 */
public class PasspointNetworkNominateHelper {
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

    public PasspointNetworkNominateHelper(PasspointManager passpointManager,
            WifiConfigManager wifiConfigManager, LocalLog localLog) {
        mPasspointManager = passpointManager;
        mWifiConfigManager = wifiConfigManager;
        mLocalLog = localLog;
    }

    /**
     * Get best matched available Passpoint network candidates for scanDetails.
     * @param scanDetails List of ScanDetail.
     * @param isFromSuggestion True to indicate profile from suggestion, false for user saved.
     * @return List of pair of scanDetail and WifiConfig from matched available provider.
     */
    public List<Pair<ScanDetail, WifiConfiguration>> getPasspointNetworkCandidates(
            List<ScanDetail> scanDetails, boolean isFromSuggestion) {
        // Sweep the ANQP cache to remove any expired ANQP entries.
        mPasspointManager.sweepCache();
        List<ScanDetail> filteredScanDetails = new ArrayList<>();
        // Filter out all invalid scanDetail
        for (ScanDetail scanDetail : scanDetails) {
            if (scanDetail.getNetworkDetail() == null
                    || !scanDetail.getNetworkDetail().isInterworking()
                    || scanDetail.getNetworkDetail().getHSRelease() == null) {
                // If scanDetail is not Passpoint network, ignore.
                continue;
            }
            if (!scanDetail.getNetworkDetail().isInternet()
                    || isApWanLinkStatusDown(scanDetail)) {
                // If scanDetail has no internet connection, ignore.
                mLocalLog.log("Ignoring no internet connection Passpoint AP: "
                        + WifiNetworkSelector.toScanId(scanDetail.getScanResult()));
                continue;
            }
            filteredScanDetails.add(scanDetail);
        }

        return findBestMatchScanDetailForProviders(filteredScanDetails, isFromSuggestion);
    }

    /**
     * Check if ANQP element inside that scanDetail indicate AP WAN port link status is down.
     *
     * @param scanDetail contains ANQP element to check.
     * @return return true is link status is down, otherwise return false.
     */
    private boolean isApWanLinkStatusDown(ScanDetail scanDetail) {
        Map<Constants.ANQPElementType, ANQPElement> anqpElements =
                mPasspointManager.getANQPElements(scanDetail.getScanResult());
        if (anqpElements == null) {
            return false;
        }
        HSWanMetricsElement wm = (HSWanMetricsElement) anqpElements.get(
                Constants.ANQPElementType.HSWANMetrics);
        if (wm == null) {
            return false;
        }
        return wm.getStatus() != HSWanMetricsElement.LINK_STATUS_UP || wm.isCapped();
    }

    /**
     * Match available providers for each scan detail. Then for each available provider, find the
     * best scan detail for it.
     * @param scanDetails all details for this scan.
     * @param isFromSuggestion True to indicate profile from suggestion, false for user saved.
     * @return List of pair of scanDetail and WifiConfig from matched available provider.
     */
    private @NonNull List<Pair<ScanDetail, WifiConfiguration>> findBestMatchScanDetailForProviders(
            List<ScanDetail> scanDetails, boolean isFromSuggestion) {
        if (mPasspointManager.isProvidersListEmpty()) {
            return Collections.emptyList();
        }
        List<Pair<ScanDetail, WifiConfiguration>> results = new ArrayList<>();
        Map<PasspointProvider, List<PasspointNetworkCandidate>> candidatesPerProvider =
                new HashMap<>();
        // Match each scanDetail with best provider (home > roaming), and grouped by FQDN.
        for (ScanDetail scanDetail : scanDetails) {
            List<Pair<PasspointProvider, PasspointMatch>> matchedProviders =
                    mPasspointManager.matchProvider(scanDetail.getScanResult());
            if (matchedProviders == null) {
                continue;
            }
            for (Pair<PasspointProvider, PasspointMatch> matchedProvider : matchedProviders) {
                if (matchedProvider.first.isFromSuggestion() != isFromSuggestion) {
                    continue;
                }
                List<PasspointNetworkCandidate> candidates = candidatesPerProvider
                        .computeIfAbsent(matchedProvider.first, k -> new ArrayList<>());
                candidates.add(new PasspointNetworkCandidate(matchedProvider.first,
                        matchedProvider.second, scanDetail));
            }
        }
        // For each provider find the best scanDetail for it and create selection candidate pair.
        for (List<PasspointNetworkCandidate> candidates : candidatesPerProvider.values()) {
            List<PasspointNetworkCandidate> bestCandidates = findHomeNetworksIfPossible(candidates);
            for (PasspointNetworkCandidate candidate : bestCandidates) {
                WifiConfiguration config = createWifiConfigForProvider(candidate);
                if (config == null) {
                    continue;
                }
                if (mWifiConfigManager.isNetworkTemporarilyDisabledByUser(config.FQDN)) {
                    mLocalLog.log("Ignoring user disabled FQDN: " + config.FQDN);
                    continue;
                }
                results.add(Pair.create(candidate.mScanDetail, config));
            }
        }
        return results;
    }

    /**
     * Create and return a WifiConfiguration for the given ScanDetail and PasspointProvider.
     * The newly created WifiConfiguration will also be added to WifiConfigManager.
     *
     * @return {@link WifiConfiguration}
     */
    private WifiConfiguration createWifiConfigForProvider(
            PasspointNetworkCandidate candidate) {
        WifiConfiguration config = candidate.mProvider.getWifiConfig();
        config.SSID = ScanResultUtil.createQuotedSSID(candidate.mScanDetail.getSSID());
        config.isHomeProviderNetwork = candidate.mMatchStatus == PasspointMatch.HomeProvider;
        if (candidate.mScanDetail.getNetworkDetail().getAnt()
                == NetworkDetail.Ant.ChargeablePublic) {
            config.meteredHint = true;
        }
        WifiConfiguration existingNetwork = mWifiConfigManager.getConfiguredNetwork(
                config.getKey());
        if (existingNetwork != null) {
            WifiConfiguration.NetworkSelectionStatus status =
                    existingNetwork.getNetworkSelectionStatus();
            if (!(status.isNetworkEnabled()
                    || mWifiConfigManager.tryEnableNetwork(existingNetwork.networkId))) {
                mLocalLog.log("Current configuration for the Passpoint AP " + config.SSID
                        + " is disabled, skip this candidate");
                return null;
            }
        }

        // Add or update with the newly created WifiConfiguration to WifiConfigManager.
        // NOTE: if existingNetwork != null, this update is a no-op in most cases if the SSID is the
        // same (since we update the cached config in PasspointManager#addOrUpdateProvider().
        NetworkUpdateResult result = mWifiConfigManager.addOrUpdateNetwork(
                config, config.creatorUid, config.creatorName);

        if (!result.isSuccess()) {
            mLocalLog.log("Failed to add passpoint network");
            return existingNetwork;
        }
        mWifiConfigManager.allowAutojoin(result.getNetworkId(), config.allowAutojoin);
        mWifiConfigManager.enableNetwork(result.getNetworkId(), false, Process.WIFI_UID, null);
        mWifiConfigManager.setNetworkCandidateScanResult(result.getNetworkId(),
                candidate.mScanDetail.getScanResult(), 0);
        mWifiConfigManager.updateScanDetailForNetwork(
                result.getNetworkId(), candidate.mScanDetail);
        return mWifiConfigManager.getConfiguredNetwork(result.getNetworkId());
    }

    /**
     * Given a list of Passpoint networks (with both provider and scan info), return all
     * homeProvider matching networks if there is any, otherwise return all roamingProvider matching
     * networks.
     *
     * @param networkList List of Passpoint networks
     * @return List of {@link PasspointNetworkCandidate}
     */
    private @NonNull List<PasspointNetworkCandidate> findHomeNetworksIfPossible(
            @NonNull List<PasspointNetworkCandidate> networkList) {
        List<PasspointNetworkCandidate> homeProviderCandidates = networkList.stream()
                .filter(candidate -> candidate.mMatchStatus == PasspointMatch.HomeProvider)
                .collect(Collectors.toList());
        if (homeProviderCandidates.isEmpty()) {
            return networkList;
        }
        return homeProviderCandidates;
    }
}
