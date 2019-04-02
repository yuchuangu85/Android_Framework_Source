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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.net.NetworkKey;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.text.TextUtils;
import android.util.LocalLog;
import android.util.Pair;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.wifi.util.ScanResultUtil;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * This class looks at all the connectivity scan results then
 * selects a network for the phone to connect or roam to.
 */
public class WifiNetworkSelector {
    private static final String TAG = "WifiNetworkSelector";

    private static final long INVALID_TIME_STAMP = Long.MIN_VALUE;
    // Minimum time gap between last successful network selection and a new selection
    // attempt.
    @VisibleForTesting
    public static final int MINIMUM_NETWORK_SELECTION_INTERVAL_MS = 10 * 1000;

    private final WifiConfigManager mWifiConfigManager;
    private final Clock mClock;
    private final LocalLog mLocalLog;
    private long mLastNetworkSelectionTimeStamp = INVALID_TIME_STAMP;
    // Buffer of filtered scan results (Scan results considered by network selection) & associated
    // WifiConfiguration (if any).
    private volatile List<Pair<ScanDetail, WifiConfiguration>> mConnectableNetworks =
            new ArrayList<>();
    private List<ScanDetail> mFilteredNetworks = new ArrayList<>();
    private final int mThresholdQualifiedRssi24;
    private final int mThresholdQualifiedRssi5;
    private final int mThresholdMinimumRssi24;
    private final int mThresholdMinimumRssi5;
    private final int mStayOnNetworkMinimumTxRate;
    private final int mStayOnNetworkMinimumRxRate;
    private final boolean mEnableAutoJoinWhenAssociated;

    /**
     * WiFi Network Selector supports various types of networks. Each type can
     * have its evaluator to choose the best WiFi network for the device to connect
     * to. When registering a WiFi network evaluator with the WiFi Network Selector,
     * the priority of the network must be specified, and it must be a value between
     * 0 and (EVALUATOR_MIN_PIRORITY - 1) with 0 being the highest priority. Wifi
     * Network Selector iterates through the registered scorers from the highest priority
     * to the lowest till a network is selected.
     */
    public static final int EVALUATOR_MIN_PRIORITY = 6;

    /**
     * Maximum number of evaluators can be registered with Wifi Network Selector.
     */
    public static final int MAX_NUM_EVALUATORS = EVALUATOR_MIN_PRIORITY;

    /**
     * Interface for WiFi Network Evaluator
     *
     * A network scorer evaulates all the networks from the scan results and
     * recommends the best network in its category to connect or roam to.
     */
    public interface NetworkEvaluator {
        /**
         * Get the evaluator name.
         */
        String getName();

        /**
         * Update the evaluator.
         *
         * Certain evaluators have to be updated with the new scan results. For example
         * the ExternalScoreEvalutor needs to refresh its Score Cache.
         *
         * @param scanDetails    a list of scan details constructed from the scan results
         */
        void update(List<ScanDetail> scanDetails);

        /**
         * Evaluate all the networks from the scan results.
         *
         * @param scanDetails    a list of scan details constructed from the scan results
         * @param currentNetwork configuration of the current connected network
         *                       or null if disconnected
         * @param currentBssid   BSSID of the current connected network or null if
         *                       disconnected
         * @param connected      a flag to indicate if WifiStateMachine is in connected
         *                       state
         * @param untrustedNetworkAllowed a flag to indidate if untrusted networks like
         *                                ephemeral networks are allowed
         * @param connectableNetworks     a list of the ScanDetail and WifiConfiguration
         *                                pair which is used by the WifiLastResortWatchdog
         * @return configuration of the chosen network;
         *         null if no network in this category is available.
         */
        @Nullable
        WifiConfiguration evaluateNetworks(List<ScanDetail> scanDetails,
                        WifiConfiguration currentNetwork, String currentBssid,
                        boolean connected, boolean untrustedNetworkAllowed,
                        List<Pair<ScanDetail, WifiConfiguration>> connectableNetworks);
    }

    private final NetworkEvaluator[] mEvaluators = new NetworkEvaluator[MAX_NUM_EVALUATORS];

    // A helper to log debugging information in the local log buffer, which can
    // be retrieved in bugreport.
    private void localLog(String log) {
        mLocalLog.log(log);
    }

    private boolean isCurrentNetworkSufficient(WifiInfo wifiInfo, List<ScanDetail> scanDetails) {
        WifiConfiguration network =
                            mWifiConfigManager.getConfiguredNetwork(wifiInfo.getNetworkId());

        // Currently connected?
        if (network == null) {
            localLog("No current connected network.");
            return false;
        } else {
            localLog("Current connected network: " + network.SSID
                    + " , ID: " + network.networkId);
        }

        int currentRssi = wifiInfo.getRssi();
        boolean hasQualifiedRssi =
                (wifiInfo.is24GHz() && (currentRssi > mThresholdQualifiedRssi24))
                        || (wifiInfo.is5GHz() && (currentRssi > mThresholdQualifiedRssi5));
        // getTxSuccessRate() and getRxSuccessRate() returns the packet rate in per 5 seconds unit.
        boolean hasActiveStream = (wifiInfo.getTxSuccessRatePps() > mStayOnNetworkMinimumTxRate)
                || (wifiInfo.getRxSuccessRatePps() > mStayOnNetworkMinimumRxRate);
        if (hasQualifiedRssi && hasActiveStream) {
            localLog("Stay on current network because of good RSSI and ongoing traffic");
            return true;
        }

        // Ephemeral network is not qualified.
        if (network.ephemeral) {
            localLog("Current network is an ephemeral one.");
            return false;
        }

        // Open network is not qualified.
        if (WifiConfigurationUtil.isConfigForOpenNetwork(network)) {
            localLog("Current network is a open one.");
            return false;
        }

        if (wifiInfo.is24GHz()) {
            // 2.4GHz networks is not qualified whenever 5GHz is available
            if (is5GHzNetworkAvailable(scanDetails)) {
                localLog("Current network is 2.4GHz. 5GHz networks available.");
                return false;
            }
        }
        if (!hasQualifiedRssi) {
            localLog("Current network RSSI[" + currentRssi + "]-acceptable but not qualified.");
            return false;
        }

        return true;
    }

    // Determine whether there are any 5GHz networks in the scan result
    private boolean is5GHzNetworkAvailable(List<ScanDetail> scanDetails) {
        for (ScanDetail detail : scanDetails) {
            ScanResult result = detail.getScanResult();
            if (result.is5GHz()) return true;
        }
        return false;
    }

    private boolean isNetworkSelectionNeeded(List<ScanDetail> scanDetails, WifiInfo wifiInfo,
                        boolean connected, boolean disconnected) {
        if (scanDetails.size() == 0) {
            localLog("Empty connectivity scan results. Skip network selection.");
            return false;
        }

        if (connected) {
            // Is roaming allowed?
            if (!mEnableAutoJoinWhenAssociated) {
                localLog("Switching networks in connected state is not allowed."
                        + " Skip network selection.");
                return false;
            }

            // Has it been at least the minimum interval since last network selection?
            if (mLastNetworkSelectionTimeStamp != INVALID_TIME_STAMP) {
                long gap = mClock.getElapsedSinceBootMillis()
                            - mLastNetworkSelectionTimeStamp;
                if (gap < MINIMUM_NETWORK_SELECTION_INTERVAL_MS) {
                    localLog("Too short since last network selection: " + gap + " ms."
                            + " Skip network selection.");
                    return false;
                }
            }

            if (isCurrentNetworkSufficient(wifiInfo, scanDetails)) {
                localLog("Current connected network already sufficient. Skip network selection.");
                return false;
            } else {
                localLog("Current connected network is not sufficient.");
                return true;
            }
        } else if (disconnected) {
            return true;
        } else {
            // No network selection if WifiStateMachine is in a state other than
            // CONNECTED or DISCONNECTED.
            localLog("WifiStateMachine is in neither CONNECTED nor DISCONNECTED state."
                    + " Skip network selection.");
            return false;
        }
    }

    /**
     * Format the given ScanResult as a scan ID for logging.
     */
    public static String toScanId(@Nullable ScanResult scanResult) {
        return scanResult == null ? "NULL"
                                  : String.format("%s:%s", scanResult.SSID, scanResult.BSSID);
    }

    /**
     * Format the given WifiConfiguration as a SSID:netId string
     */
    public static String toNetworkString(WifiConfiguration network) {
        if (network == null) {
            return null;
        }

        return (network.SSID + ":" + network.networkId);
    }

    /**
     * Compares ScanResult level against the minimum threshold for its band, returns true if lower
     */
    public boolean isSignalTooWeak(ScanResult scanResult) {
        return ((scanResult.is24GHz() && scanResult.level < mThresholdMinimumRssi24)
                || (scanResult.is5GHz() && scanResult.level < mThresholdMinimumRssi5));
    }

    private List<ScanDetail> filterScanResults(List<ScanDetail> scanDetails,
                HashSet<String> bssidBlacklist, boolean isConnected, String currentBssid) {
        ArrayList<NetworkKey> unscoredNetworks = new ArrayList<NetworkKey>();
        List<ScanDetail> validScanDetails = new ArrayList<ScanDetail>();
        StringBuffer noValidSsid = new StringBuffer();
        StringBuffer blacklistedBssid = new StringBuffer();
        StringBuffer lowRssi = new StringBuffer();
        boolean scanResultsHaveCurrentBssid = false;

        for (ScanDetail scanDetail : scanDetails) {
            ScanResult scanResult = scanDetail.getScanResult();

            if (TextUtils.isEmpty(scanResult.SSID)) {
                noValidSsid.append(scanResult.BSSID).append(" / ");
                continue;
            }

            // Check if the scan results contain the currently connected BSSID
            if (scanResult.BSSID.equals(currentBssid)) {
                scanResultsHaveCurrentBssid = true;
            }

            final String scanId = toScanId(scanResult);

            if (bssidBlacklist.contains(scanResult.BSSID)) {
                blacklistedBssid.append(scanId).append(" / ");
                continue;
            }

            // Skip network with too weak signals.
            if (isSignalTooWeak(scanResult)) {
                lowRssi.append(scanId).append("(")
                    .append(scanResult.is24GHz() ? "2.4GHz" : "5GHz")
                    .append(")").append(scanResult.level).append(" / ");
                continue;
            }

            validScanDetails.add(scanDetail);
        }

        // WNS listens to all single scan results. Some scan requests may not include
        // the channel of the currently connected network, so the currently connected
        // network won't show up in the scan results. We don't act on these scan results
        // to avoid aggressive network switching which might trigger disconnection.
        if (isConnected && !scanResultsHaveCurrentBssid) {
            localLog("Current connected BSSID " + currentBssid + " is not in the scan results."
                    + " Skip network selection.");
            validScanDetails.clear();
            return validScanDetails;
        }

        if (noValidSsid.length() != 0) {
            localLog("Networks filtered out due to invalid SSID: " + noValidSsid);
        }

        if (blacklistedBssid.length() != 0) {
            localLog("Networks filtered out due to blacklist: " + blacklistedBssid);
        }

        if (lowRssi.length() != 0) {
            localLog("Networks filtered out due to low signal strength: " + lowRssi);
        }

        return validScanDetails;
    }

    /**
     * This returns a list of ScanDetails that were filtered in the process of network selection.
     * The list is further filtered for only open unsaved networks.
     *
     * @return the list of ScanDetails for open unsaved networks that do not have invalid SSIDS,
     * blacklisted BSSIDS, or low signal strength. This will return an empty list when there are
     * no open unsaved networks, or when network selection has not been run.
     */
    public List<ScanDetail> getFilteredScanDetailsForOpenUnsavedNetworks() {
        List<ScanDetail> openUnsavedNetworks = new ArrayList<>();
        for (ScanDetail scanDetail : mFilteredNetworks) {
            ScanResult scanResult = scanDetail.getScanResult();

            if (!ScanResultUtil.isScanResultForOpenNetwork(scanResult)) {
                continue;
            }

            // Skip saved networks
            if (mWifiConfigManager.getConfiguredNetworkForScanDetailAndCache(scanDetail) != null) {
                continue;
            }

            openUnsavedNetworks.add(scanDetail);
        }
        return openUnsavedNetworks;
    }

    /**
     * @return the list of ScanDetails scored as potential candidates by the last run of
     * selectNetwork, this will be empty if Network selector determined no selection was
     * needed on last run. This includes scan details of sufficient signal strength, and
     * had an associated WifiConfiguration.
     */
    public List<Pair<ScanDetail, WifiConfiguration>> getConnectableScanDetails() {
        return mConnectableNetworks;
    }

    /**
     * This API is called when user explicitly selects a network. Currently, it is used in following
     * cases:
     * (1) User explicitly chooses to connect to a saved network.
     * (2) User saves a network after adding a new network.
     * (3) User saves a network after modifying a saved network.
     * Following actions will be triggered:
     * 1. If this network is disabled, we need re-enable it again.
     * 2. This network is favored over all the other networks visible in latest network
     *    selection procedure.
     *
     * @param netId  ID for the network chosen by the user
     * @return true -- There is change made to connection choice of any saved network.
     *         false -- There is no change made to connection choice of any saved network.
     */
    public boolean setUserConnectChoice(int netId) {
        localLog("userSelectNetwork: network ID=" + netId);
        WifiConfiguration selected = mWifiConfigManager.getConfiguredNetwork(netId);

        if (selected == null || selected.SSID == null) {
            localLog("userSelectNetwork: Invalid configuration with nid=" + netId);
            return false;
        }

        // Enable the network if it is disabled.
        if (!selected.getNetworkSelectionStatus().isNetworkEnabled()) {
            mWifiConfigManager.updateNetworkSelectionStatus(netId,
                    WifiConfiguration.NetworkSelectionStatus.NETWORK_SELECTION_ENABLE);
        }

        boolean change = false;
        String key = selected.configKey();
        // This is only used for setting the connect choice timestamp for debugging purposes.
        long currentTime = mClock.getWallClockMillis();
        List<WifiConfiguration> savedNetworks = mWifiConfigManager.getSavedNetworks();

        for (WifiConfiguration network : savedNetworks) {
            WifiConfiguration.NetworkSelectionStatus status = network.getNetworkSelectionStatus();
            if (network.networkId == selected.networkId) {
                if (status.getConnectChoice() != null) {
                    localLog("Remove user selection preference of " + status.getConnectChoice()
                            + " Set Time: " + status.getConnectChoiceTimestamp() + " from "
                            + network.SSID + " : " + network.networkId);
                    mWifiConfigManager.clearNetworkConnectChoice(network.networkId);
                    change = true;
                }
                continue;
            }

            if (status.getSeenInLastQualifiedNetworkSelection()
                    && (status.getConnectChoice() == null
                    || !status.getConnectChoice().equals(key))) {
                localLog("Add key: " + key + " Set Time: " + currentTime + " to "
                        + toNetworkString(network));
                mWifiConfigManager.setNetworkConnectChoice(network.networkId, key, currentTime);
                change = true;
            }
        }

        return change;
    }

    /**
     * Overrides the {@code candidate} chosen by the {@link #mEvaluators} with the user chosen
     * {@link WifiConfiguration} if one exists.
     *
     * @return the user chosen {@link WifiConfiguration} if one exists, {@code candidate} otherwise
     */
    private WifiConfiguration overrideCandidateWithUserConnectChoice(
            @NonNull WifiConfiguration candidate) {
        WifiConfiguration tempConfig = candidate;
        WifiConfiguration originalCandidate = candidate;
        ScanResult scanResultCandidate = candidate.getNetworkSelectionStatus().getCandidate();

        while (tempConfig.getNetworkSelectionStatus().getConnectChoice() != null) {
            String key = tempConfig.getNetworkSelectionStatus().getConnectChoice();
            tempConfig = mWifiConfigManager.getConfiguredNetwork(key);

            if (tempConfig != null) {
                WifiConfiguration.NetworkSelectionStatus tempStatus =
                        tempConfig.getNetworkSelectionStatus();
                if (tempStatus.getCandidate() != null && tempStatus.isNetworkEnabled()) {
                    scanResultCandidate = tempStatus.getCandidate();
                    candidate = tempConfig;
                }
            } else {
                localLog("Connect choice: " + key + " has no corresponding saved config.");
                break;
            }
        }

        if (candidate != originalCandidate) {
            localLog("After user selection adjustment, the final candidate is:"
                    + WifiNetworkSelector.toNetworkString(candidate) + " : "
                    + scanResultCandidate.BSSID);
        }
        return candidate;
    }

    /**
     * Select the best network from the ones in range.
     *
     * @param scanDetails    List of ScanDetail for all the APs in range
     * @param bssidBlacklist Blacklisted BSSIDs
     * @param wifiInfo       Currently connected network
     * @param connected      True if the device is connected
     * @param disconnected   True if the device is disconnected
     * @param untrustedNetworkAllowed True if untrusted networks are allowed for connection
     * @return Configuration of the selected network, or Null if nothing
     */
    @Nullable
    public WifiConfiguration selectNetwork(List<ScanDetail> scanDetails,
            HashSet<String> bssidBlacklist, WifiInfo wifiInfo,
            boolean connected, boolean disconnected, boolean untrustedNetworkAllowed) {
        mFilteredNetworks.clear();
        mConnectableNetworks.clear();
        if (scanDetails.size() == 0) {
            localLog("Empty connectivity scan result");
            return null;
        }

        WifiConfiguration currentNetwork =
                mWifiConfigManager.getConfiguredNetwork(wifiInfo.getNetworkId());

        // Always get the current BSSID from WifiInfo in case that firmware initiated
        // roaming happened.
        String currentBssid = wifiInfo.getBSSID();

        // Shall we start network selection at all?
        if (!isNetworkSelectionNeeded(scanDetails, wifiInfo, connected, disconnected)) {
            return null;
        }

        // Update the registered network evaluators.
        for (NetworkEvaluator registeredEvaluator : mEvaluators) {
            if (registeredEvaluator != null) {
                registeredEvaluator.update(scanDetails);
            }
        }

        // Filter out unwanted networks.
        mFilteredNetworks = filterScanResults(scanDetails, bssidBlacklist,
                connected, currentBssid);
        if (mFilteredNetworks.size() == 0) {
            return null;
        }

        // Go through the registered network evaluators from the highest priority
        // one to the lowest till a network is selected.
        WifiConfiguration selectedNetwork = null;
        for (NetworkEvaluator registeredEvaluator : mEvaluators) {
            if (registeredEvaluator != null) {
                localLog("About to run " + registeredEvaluator.getName() + " :");
                selectedNetwork = registeredEvaluator.evaluateNetworks(
                        new ArrayList<>(mFilteredNetworks), currentNetwork, currentBssid, connected,
                        untrustedNetworkAllowed, mConnectableNetworks);
                if (selectedNetwork != null) {
                    localLog(registeredEvaluator.getName() + " selects "
                            + WifiNetworkSelector.toNetworkString(selectedNetwork) + " : "
                            + selectedNetwork.getNetworkSelectionStatus().getCandidate().BSSID);
                    break;
                }
            }
        }

        if (selectedNetwork != null) {
            selectedNetwork = overrideCandidateWithUserConnectChoice(selectedNetwork);
            mLastNetworkSelectionTimeStamp = mClock.getElapsedSinceBootMillis();
        }

        return selectedNetwork;
    }

    /**
     * Register a network evaluator
     *
     * @param evaluator the network evaluator to be registered
     * @param priority a value between 0 and (SCORER_MIN_PRIORITY-1)
     *
     * @return true if the evaluator is successfully registered with QNS;
     *         false if failed to register the evaluator
     */
    public boolean registerNetworkEvaluator(NetworkEvaluator evaluator, int priority) {
        if (priority < 0 || priority >= EVALUATOR_MIN_PRIORITY) {
            localLog("Invalid network evaluator priority: " + priority);
            return false;
        }

        if (mEvaluators[priority] != null) {
            localLog("Priority " + priority + " is already registered by "
                    + mEvaluators[priority].getName());
            return false;
        }

        mEvaluators[priority] = evaluator;
        return true;
    }

    WifiNetworkSelector(Context context, WifiConfigManager configManager, Clock clock,
            LocalLog localLog) {
        mWifiConfigManager = configManager;
        mClock = clock;
        mLocalLog = localLog;

        mThresholdQualifiedRssi24 = context.getResources().getInteger(
                R.integer.config_wifi_framework_wifi_score_low_rssi_threshold_24GHz);
        mThresholdQualifiedRssi5 = context.getResources().getInteger(
                R.integer.config_wifi_framework_wifi_score_low_rssi_threshold_5GHz);
        mThresholdMinimumRssi24 = context.getResources().getInteger(
                R.integer.config_wifi_framework_wifi_score_entry_rssi_threshold_24GHz);
        mThresholdMinimumRssi5 = context.getResources().getInteger(
                R.integer.config_wifi_framework_wifi_score_entry_rssi_threshold_5GHz);
        mEnableAutoJoinWhenAssociated = context.getResources().getBoolean(
                R.bool.config_wifi_framework_enable_associated_network_selection);
        mStayOnNetworkMinimumTxRate = context.getResources().getInteger(
                R.integer.config_wifi_framework_min_tx_rate_for_staying_on_network);
        mStayOnNetworkMinimumRxRate = context.getResources().getInteger(
                R.integer.config_wifi_framework_min_rx_rate_for_staying_on_network);
    }
}
