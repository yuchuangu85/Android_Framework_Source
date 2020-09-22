/*
 * Copyright (C) 2018 The Android Open Source Project
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
import android.net.wifi.WifiConfiguration;
import android.util.LocalLog;
import android.util.Log;
import android.util.Pair;

import com.android.server.wifi.WifiNetworkSuggestionsManager.ExtendedWifiNetworkSuggestion;
import com.android.server.wifi.hotspot2.PasspointNetworkNominateHelper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;

/**
 * Nominator nominate the highest available suggestion candidates.
 * Note:
 * <li> This class is not thread safe and meant to be used only from {@link WifiNetworkSelector}.
 * </li>
 *
 */
@NotThreadSafe
public class NetworkSuggestionNominator implements WifiNetworkSelector.NetworkNominator {
    private static final String TAG = "NetworkSuggestionNominator";

    private final WifiNetworkSuggestionsManager mWifiNetworkSuggestionsManager;
    private final WifiConfigManager mWifiConfigManager;
    private final PasspointNetworkNominateHelper mPasspointNetworkNominateHelper;
    private final LocalLog mLocalLog;
    private final WifiCarrierInfoManager mWifiCarrierInfoManager;

    NetworkSuggestionNominator(WifiNetworkSuggestionsManager networkSuggestionsManager,
            WifiConfigManager wifiConfigManager, PasspointNetworkNominateHelper nominateHelper,
            LocalLog localLog, WifiCarrierInfoManager wifiCarrierInfoManager) {
        mWifiNetworkSuggestionsManager = networkSuggestionsManager;
        mWifiConfigManager = wifiConfigManager;
        mPasspointNetworkNominateHelper = nominateHelper;
        mLocalLog = localLog;
        mWifiCarrierInfoManager = wifiCarrierInfoManager;
    }

    @Override
    public void update(List<ScanDetail> scanDetails) {
        // TODO(b/115504887): This could be used to re-evaluate any temporary blacklists.
    }

    @Override
    public void nominateNetworks(List<ScanDetail> scanDetails,
            WifiConfiguration currentNetwork, String currentBssid, boolean connected,
            boolean untrustedNetworkAllowed,
            @NonNull OnConnectableListener onConnectableListener) {
        if (scanDetails.isEmpty()) {
            return;
        }
        MatchMetaInfo matchMetaInfo = new MatchMetaInfo();
        Set<ExtendedWifiNetworkSuggestion> autoJoinDisabledSuggestions = new HashSet<>();

        findMatchedPasspointSuggestionNetworks(scanDetails, matchMetaInfo, untrustedNetworkAllowed);
        findMatchedSuggestionNetworks(scanDetails, matchMetaInfo,
                autoJoinDisabledSuggestions, untrustedNetworkAllowed);

        if (matchMetaInfo.isEmpty()) {
            mLocalLog.log("did not see any matching auto-join enabled network suggestions.");
        } else {
            matchMetaInfo.findConnectableNetworksAndHighestPriority(onConnectableListener);
        }

        addAutojoinDisabledSuggestionToWifiConfigManager(autoJoinDisabledSuggestions);
    }

    private void findMatchedPasspointSuggestionNetworks(List<ScanDetail> scanDetails,
            MatchMetaInfo matchMetaInfo, boolean untrustedNetworkAllowed) {
        List<Pair<ScanDetail, WifiConfiguration>> candidates =
                mPasspointNetworkNominateHelper.getPasspointNetworkCandidates(scanDetails, true);
        for (Pair<ScanDetail, WifiConfiguration> candidate : candidates) {
            WifiConfiguration config = candidate.second;
            Set<ExtendedWifiNetworkSuggestion> matchingPasspointExtSuggestions =
                    mWifiNetworkSuggestionsManager
                            .getNetworkSuggestionsForFqdn(config.FQDN);
            if (matchingPasspointExtSuggestions == null
                    || matchingPasspointExtSuggestions.isEmpty()) {
                mLocalLog.log("Suggestion is missing for passpoint: " + config.FQDN);
                continue;
            }

            if (WifiConfiguration.isMetered(config, null)
                    && mWifiCarrierInfoManager.isCarrierNetworkFromNonDefaultDataSim(config)) {
                continue;
            }
            if (!isSimBasedNetworkAvailableToAutoConnect(config)) {
                continue;
            }
            // If untrusted network is not allowed, ignore untrusted suggestion.
            if (!untrustedNetworkAllowed && !config.trusted) {
                continue;
            }
            Set<ExtendedWifiNetworkSuggestion> autoJoinEnabledExtSuggestions =
                    matchingPasspointExtSuggestions.stream()
                            .filter(ewns -> ewns.isAutojoinEnabled)
                            .collect(Collectors.toSet());
            if (autoJoinEnabledExtSuggestions.isEmpty()) {
                continue;
            }

            matchMetaInfo.putAll(autoJoinEnabledExtSuggestions,
                    config, candidate.first);
        }
    }

    private void findMatchedSuggestionNetworks(List<ScanDetail> scanDetails,
            MatchMetaInfo matchMetaInfo,
            Set<ExtendedWifiNetworkSuggestion> autoJoinDisabledSuggestions,
            boolean untrustedNetworkAllowed) {
        for (ScanDetail scanDetail : scanDetails) {
            Set<ExtendedWifiNetworkSuggestion> matchingExtNetworkSuggestions =
                    mWifiNetworkSuggestionsManager.getNetworkSuggestionsForScanDetail(scanDetail);
            if (matchingExtNetworkSuggestions == null || matchingExtNetworkSuggestions.isEmpty()) {
                continue;
            }
            Set<ExtendedWifiNetworkSuggestion> autojoinEnableSuggestions = new HashSet<>();
            for (ExtendedWifiNetworkSuggestion ewns : matchingExtNetworkSuggestions) {
                // Ignore insecure enterprise config.
                if (ewns.wns.wifiConfiguration.isEnterprise()
                        && ewns.wns.wifiConfiguration.enterpriseConfig.isInsecure()) {
                    continue;
                }
                // If untrusted network is not allowed, ignore untrusted suggestion.
                WifiConfiguration config = ewns.wns.wifiConfiguration;
                if (!untrustedNetworkAllowed && !config.trusted) {
                    continue;
                }
                if (WifiConfiguration.isMetered(config, null)
                        && mWifiCarrierInfoManager.isCarrierNetworkFromNonDefaultDataSim(config)) {
                    continue;
                }
                if (!ewns.isAutojoinEnabled
                        || !isSimBasedNetworkAvailableToAutoConnect(config)) {
                    autoJoinDisabledSuggestions.add(ewns);
                    continue;
                }
                if (mWifiConfigManager.isNetworkTemporarilyDisabledByUser(config.SSID)) {
                    mLocalLog.log("Ignoring user disabled SSID: "
                            + config.SSID);
                    autoJoinDisabledSuggestions.add(ewns);
                    continue;
                }
                autojoinEnableSuggestions.add(ewns);
            }

            if (autojoinEnableSuggestions.isEmpty()) {
                continue;
            }
            // All matching suggestions have the same network credentials type. So, use any one of
            // them to lookup/add the credentials to WifiConfigManager.
            // Note: Apps could provide different credentials (password, ceritificate) for the same
            // network, need to handle that in the future.
            String configKey = autojoinEnableSuggestions.stream().findAny().get()
                    .wns.wifiConfiguration.getKey();
            // Check if we already have a network with the same credentials in WifiConfigManager
            // database.
            WifiConfiguration wCmConfiguredNetwork =
                    mWifiConfigManager.getConfiguredNetwork(configKey);
            if (wCmConfiguredNetwork != null) {
                // If existing network is not from suggestion, ignore.
                if (!(wCmConfiguredNetwork.fromWifiNetworkSuggestion
                        && wCmConfiguredNetwork.allowAutojoin)) {
                    continue;
                }
                int creatorUid = wCmConfiguredNetwork.creatorUid;
                Set<ExtendedWifiNetworkSuggestion> matchingExtNetworkSuggestionsFromSamePackage =
                        autojoinEnableSuggestions.stream()
                                .filter(ewns -> ewns.perAppInfo.uid == creatorUid)
                                .collect(Collectors.toSet());
                if (matchingExtNetworkSuggestionsFromSamePackage.isEmpty()) {
                    continue;
                }
                // If the network is currently blacklisted, ignore.
                if (!wCmConfiguredNetwork.getNetworkSelectionStatus().isNetworkEnabled()
                        && !mWifiConfigManager.tryEnableNetwork(wCmConfiguredNetwork.networkId)) {
                    mLocalLog.log("Ignoring blacklisted network: "
                            + WifiNetworkSelector.toNetworkString(wCmConfiguredNetwork));
                    continue;
                }
                matchingExtNetworkSuggestions = matchingExtNetworkSuggestionsFromSamePackage;
            }
            matchMetaInfo.putAll(matchingExtNetworkSuggestions, wCmConfiguredNetwork, scanDetail);
        }
    }

    private boolean isSimBasedNetworkAvailableToAutoConnect(WifiConfiguration config) {
        if (config.enterpriseConfig == null
                || !config.enterpriseConfig.isAuthenticationSimBased()) {
            return true;
        }
        int subId = mWifiCarrierInfoManager.getBestMatchSubscriptionId(config);
        if (!mWifiCarrierInfoManager.isSimPresent(subId)) {
            mLocalLog.log("SIM is not present for subId: " + subId);
            return false;
        }
        if (mWifiCarrierInfoManager.requiresImsiEncryption(subId)) {
            return mWifiCarrierInfoManager.isImsiEncryptionInfoAvailable(subId);
        }
        return true;
    }

    // Add auto-join disabled suggestions also to WifiConfigManager if the app allows credential
    // sharing.This will surface these networks on the UI, to allow the user manually connect to it.
    private void addAutojoinDisabledSuggestionToWifiConfigManager(
            Set<ExtendedWifiNetworkSuggestion> autoJoinDisabledSuggestions) {
        for (ExtendedWifiNetworkSuggestion ewns : autoJoinDisabledSuggestions) {
            if (!ewns.wns.isUserAllowedToManuallyConnect) {
                continue;
            }
            WifiConfiguration config = ewns.createInternalWifiConfiguration();
            WifiConfiguration wCmConfiguredNetwork =
                    mWifiConfigManager.getConfiguredNetwork(config.getKey());
            NetworkUpdateResult result = mWifiConfigManager.addOrUpdateNetwork(
                    config, ewns.perAppInfo.uid, ewns.perAppInfo.packageName);
            if (!result.isSuccess()) {
                mLocalLog.log("Failed to add network suggestion");
                continue;
            }
            WifiConfiguration currentWCmConfiguredNetwork =
                    mWifiConfigManager.getConfiguredNetwork(result.netId);
            // Try to enable network selection
            if (wCmConfiguredNetwork == null) {
                if (!mWifiConfigManager.updateNetworkSelectionStatus(result.getNetworkId(),
                        WifiConfiguration.NetworkSelectionStatus.DISABLED_NONE)) {
                    mLocalLog.log("Failed to make network suggestion selectable");
                }
            } else {
                if (!currentWCmConfiguredNetwork.getNetworkSelectionStatus().isNetworkEnabled()
                        && !mWifiConfigManager.tryEnableNetwork(wCmConfiguredNetwork.networkId)) {
                    mLocalLog.log("Ignoring blacklisted network: "
                            + WifiNetworkSelector.toNetworkString(wCmConfiguredNetwork));
                }
            }
        }
    }

    // Add and enable this network to the central database (i.e WifiConfigManager).
    // Returns the copy of WifiConfiguration with the allocated network ID filled in.
    private WifiConfiguration addCandidateToWifiConfigManager(
            @NonNull ExtendedWifiNetworkSuggestion ewns) {
        WifiConfiguration wifiConfiguration = ewns.createInternalWifiConfiguration();
        NetworkUpdateResult result =
                mWifiConfigManager.addOrUpdateNetwork(wifiConfiguration, ewns.perAppInfo.uid,
                        ewns.perAppInfo.packageName);
        if (!result.isSuccess()) {
            mLocalLog.log("Failed to add network suggestion");
            return null;
        }
        if (!mWifiConfigManager.updateNetworkSelectionStatus(result.getNetworkId(),
                WifiConfiguration.NetworkSelectionStatus.DISABLED_NONE)) {
            mLocalLog.log("Failed to make network suggestion selectable");
            return null;
        }
        int candidateNetworkId = result.getNetworkId();
        return mWifiConfigManager.getConfiguredNetwork(candidateNetworkId);
    }

    @Override
    public @NominatorId int getId() {
        return NOMINATOR_ID_SUGGESTION;
    }

    @Override
    public String getName() {
        return TAG;
    }

    // Container classes to handle book-keeping while we're iterating through the scan list.
    private class PerNetworkSuggestionMatchMetaInfo {
        public final ExtendedWifiNetworkSuggestion extWifiNetworkSuggestion;
        public final ScanDetail matchingScanDetail;
        public WifiConfiguration wCmConfiguredNetwork; // Added to WifiConfigManager.

        PerNetworkSuggestionMatchMetaInfo(
                @NonNull ExtendedWifiNetworkSuggestion extWifiNetworkSuggestion,
                @Nullable WifiConfiguration wCmConfiguredNetwork,
                @NonNull ScanDetail matchingScanDetail) {
            this.extWifiNetworkSuggestion = extWifiNetworkSuggestion;
            this.wCmConfiguredNetwork = wCmConfiguredNetwork;
            this.matchingScanDetail = matchingScanDetail;
        }
    }

    private class PerAppMatchMetaInfo {
        public final List<PerNetworkSuggestionMatchMetaInfo> networkInfos = new ArrayList<>();

        /**
         * Add the network suggestion & associated info to this package meta info.
         */
        public void put(ExtendedWifiNetworkSuggestion wifiNetworkSuggestion,
                        WifiConfiguration matchingWifiConfiguration,
                        ScanDetail matchingScanDetail) {
            networkInfos.add(new PerNetworkSuggestionMatchMetaInfo(
                    wifiNetworkSuggestion, matchingWifiConfiguration, matchingScanDetail));
        }

        /**
         * Pick the highest priority networks among the current match info candidates for this
         * app.
         */
        public List<PerNetworkSuggestionMatchMetaInfo> getHighestPriorityNetworks() {
            // Partition the list to a map of network suggestions keyed in by the priorities.
            // There can be multiple networks with the same priority, hence a list in the value.
            Map<Integer, List<PerNetworkSuggestionMatchMetaInfo>> matchedNetworkInfosPerPriority =
                    networkInfos.stream()
                            .collect(Collectors.toMap(
                                    e -> e.extWifiNetworkSuggestion.wns.wifiConfiguration.priority,
                                    e -> Arrays.asList(e),
                                    (v1, v2) -> { // concatenate networks with the same priority.
                                        List<PerNetworkSuggestionMatchMetaInfo> concatList =
                                                new ArrayList<>(v1);
                                        concatList.addAll(v2);
                                        return concatList;
                                    }));
            if (matchedNetworkInfosPerPriority.isEmpty()) { // should never happen.
                Log.wtf(TAG, "Unexepectedly got empty");
                return Collections.EMPTY_LIST;
            }
            // Return the list associated with the highest priority value.
            return matchedNetworkInfosPerPriority.get(Collections.max(
                    matchedNetworkInfosPerPriority.keySet()));
        }
    }

    private class MatchMetaInfo {
        private Map<String, PerAppMatchMetaInfo> mAppInfos = new HashMap<>();

        /**
         * Add all the network suggestion & associated info.
         */
        public void putAll(Set<ExtendedWifiNetworkSuggestion> wifiNetworkSuggestions,
                           WifiConfiguration wCmConfiguredNetwork,
                           ScanDetail matchingScanDetail) {
            // Separate the suggestions into buckets for each app to allow sorting based on
            // priorities set by app.
            for (ExtendedWifiNetworkSuggestion wifiNetworkSuggestion : wifiNetworkSuggestions) {
                PerAppMatchMetaInfo appInfo = mAppInfos.computeIfAbsent(
                        wifiNetworkSuggestion.perAppInfo.packageName,
                        k -> new PerAppMatchMetaInfo());
                appInfo.put(wifiNetworkSuggestion, wCmConfiguredNetwork, matchingScanDetail);
            }
        }

        /**
         * Are there any matched candidates?
         */
        public boolean isEmpty() {
            return mAppInfos.isEmpty();
        }

        /**
         * Run through all connectable suggestions and nominate highest priority networks from each
         * app as candidates to {@link WifiNetworkSelector}.
         */
        public void findConnectableNetworksAndHighestPriority(
                @NonNull OnConnectableListener onConnectableListener) {
            for (PerAppMatchMetaInfo appInfo : mAppInfos.values()) {
                List<PerNetworkSuggestionMatchMetaInfo> matchedNetworkInfos =
                        appInfo.getHighestPriorityNetworks();
                for (PerNetworkSuggestionMatchMetaInfo matchedNetworkInfo : matchedNetworkInfos) {
                    // if the network does not already exist in WifiConfigManager, add now.
                    if (matchedNetworkInfo.wCmConfiguredNetwork == null) {
                        matchedNetworkInfo.wCmConfiguredNetwork = addCandidateToWifiConfigManager(
                                matchedNetworkInfo.extWifiNetworkSuggestion);
                        if (matchedNetworkInfo.wCmConfiguredNetwork == null) continue;
                        mLocalLog.log(String.format("network suggestion candidate %s (new)",
                                WifiNetworkSelector.toNetworkString(
                                        matchedNetworkInfo.wCmConfiguredNetwork)));
                    } else {
                        mLocalLog.log(String.format("network suggestion candidate %s (existing)",
                                WifiNetworkSelector.toNetworkString(
                                        matchedNetworkInfo.wCmConfiguredNetwork)));
                    }
                    onConnectableListener.onConnectable(
                            matchedNetworkInfo.matchingScanDetail,
                            matchedNetworkInfo.wCmConfiguredNetwork);
                }
            }
        }
    }

}
