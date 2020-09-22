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

import static android.net.wifi.WifiManager.WIFI_FEATURE_OWE;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.net.MacAddress;
import android.net.wifi.ScanResult;
import android.net.wifi.SupplicantState;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.LocalLog;
import android.util.Log;
import android.util.Pair;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.Preconditions;
import com.android.server.wifi.hotspot2.NetworkDetail;
import com.android.server.wifi.proto.nano.WifiMetricsProto;
import com.android.server.wifi.util.InformationElementUtil.BssLoad;
import com.android.server.wifi.util.ScanResultUtil;
import com.android.wifi.resources.R;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * WifiNetworkSelector looks at all the connectivity scan results and
 * runs all the nominators to find or create matching configurations.
 * Then it makes a final selection from among the resulting candidates.
 */
public class WifiNetworkSelector {
    private static final String TAG = "WifiNetworkSelector";

    private static final long INVALID_TIME_STAMP = Long.MIN_VALUE;

    /**
     * Minimum time gap between last successful network selection and a
     * new selection attempt.
     */
    @VisibleForTesting
    public static final int MINIMUM_NETWORK_SELECTION_INTERVAL_MS = 10 * 1000;

    /**
     * Connected score value used to decide whether a still-connected wifi should be treated
     * as unconnected when filtering scan results.
     */
    @VisibleForTesting
    public static final int WIFI_POOR_SCORE = ConnectedScore.WIFI_TRANSITION_SCORE - 10;

    /**
     * The identifier string of the CandidateScorer to use (in the absence of overrides).
     */
    public static final String PRESET_CANDIDATE_SCORER_NAME = "ThroughputScorer";

    /**
     * Experiment ID for the legacy scorer.
     */
    public static final int LEGACY_CANDIDATE_SCORER_EXP_ID = 0;

    private final Context mContext;
    private final WifiConfigManager mWifiConfigManager;
    private final Clock mClock;
    private final LocalLog mLocalLog;
    private final WifiMetrics mWifiMetrics;
    private long mLastNetworkSelectionTimeStamp = INVALID_TIME_STAMP;
    // Buffer of filtered scan results (Scan results considered by network selection) & associated
    // WifiConfiguration (if any).
    private final List<Pair<ScanDetail, WifiConfiguration>> mConnectableNetworks =
            new ArrayList<>();
    private List<ScanDetail> mFilteredNetworks = new ArrayList<>();
    private final WifiScoreCard mWifiScoreCard;
    private final ScoringParams mScoringParams;
    private final WifiNative mWifiNative;

    private final Map<String, WifiCandidates.CandidateScorer> mCandidateScorers = new ArrayMap<>();
    private boolean mIsEnhancedOpenSupportedInitialized = false;
    private boolean mIsEnhancedOpenSupported;
    private ThroughputPredictor mThroughputPredictor;
    private boolean mIsBluetoothConnected = false;
    private WifiChannelUtilization mWifiChannelUtilization;

    /**
     * Interface for WiFi Network Nominator
     *
     * A network nominator examines the scan results reports the
     * connectable candidates in its category for further consideration.
     */
    public interface NetworkNominator {
        /** Type of nominators */
        int NOMINATOR_ID_SAVED = 0;
        int NOMINATOR_ID_SUGGESTION = 1;
        int NOMINATOR_ID_SCORED = 4;
        int NOMINATOR_ID_CURRENT = 5; // Should always be last

        @IntDef(prefix = {"NOMINATOR_ID_"}, value = {
                NOMINATOR_ID_SAVED,
                NOMINATOR_ID_SUGGESTION,
                NOMINATOR_ID_SCORED,
                NOMINATOR_ID_CURRENT})
        @Retention(RetentionPolicy.SOURCE)
        public @interface NominatorId {
        }

        /**
         * Get the nominator type.
         */
        @NominatorId
        int getId();

        /**
         * Get the nominator name.
         */
        String getName();

        /**
         * Update the nominator.
         *
         * Certain nominators have to be updated with the new scan results. For example
         * the ScoredNetworkNominator needs to refresh its Score Cache.
         *
         * @param scanDetails a list of scan details constructed from the scan results
         */
        void update(List<ScanDetail> scanDetails);

        /**
         * Evaluate all the networks from the scan results.
         *
         * @param scanDetails             a list of scan details constructed from the scan results
         * @param currentNetwork          configuration of the current connected network
         *                                or null if disconnected
         * @param currentBssid            BSSID of the current connected network or null if
         *                                disconnected
         * @param connected               a flag to indicate if ClientModeImpl is in connected
         *                                state
         * @param untrustedNetworkAllowed a flag to indicate if untrusted networks like
         *                                ephemeral networks are allowed
         * @param onConnectableListener   callback to record all of the connectable networks
         */
        void nominateNetworks(List<ScanDetail> scanDetails,
                WifiConfiguration currentNetwork, String currentBssid,
                boolean connected, boolean untrustedNetworkAllowed,
                OnConnectableListener onConnectableListener);

        /**
         * Callback for recording connectable candidates
         */
        public interface OnConnectableListener {
            /**
             * Notes that an access point is an eligible connection candidate
             *
             * @param scanDetail describes the specific access point
             * @param config     is the WifiConfiguration for the network
             */
            void onConnectable(ScanDetail scanDetail, WifiConfiguration config);
        }
    }

    private final List<NetworkNominator> mNominators = new ArrayList<>(3);

    // A helper to log debugging information in the local log buffer, which can
    // be retrieved in bugreport.
    private void localLog(String log) {
        mLocalLog.log(log);
    }

    /**
     * Check if current network has sufficient RSSI
     *
     * @param wifiInfo info of currently connected network
     * @return true if current link quality is sufficient, false otherwise.
     */
    public boolean hasSufficientLinkQuality(WifiInfo wifiInfo) {
        int currentRssi = wifiInfo.getRssi();
        return currentRssi >= mScoringParams.getSufficientRssi(wifiInfo.getFrequency());
    }

    /**
     * Check if current network has active Tx or Rx traffic
     *
     * @param wifiInfo info of currently connected network
     * @return true if it has active Tx or Rx traffic, false otherwise.
     */
    public boolean hasActiveStream(WifiInfo wifiInfo) {
        return wifiInfo.getSuccessfulTxPacketsPerSecond()
                > mScoringParams.getActiveTrafficPacketsPerSecond()
                || wifiInfo.getSuccessfulRxPacketsPerSecond()
                > mScoringParams.getActiveTrafficPacketsPerSecond();
    }

    /**
     * Check if current network has internet or is expected to not have internet
     */
    public boolean hasInternetOrExpectNoInternet(WifiInfo wifiInfo) {
        WifiConfiguration network =
                mWifiConfigManager.getConfiguredNetwork(wifiInfo.getNetworkId());
        if (network == null) {
            return false;
        }
        return !network.hasNoInternetAccess() || network.isNoInternetAccessExpected();
    }
    /**
     * Determines whether the currently connected network is sufficient.
     *
     * If the network is good enough, or if switching to a new network is likely to
     * be disruptive, we should avoid doing a network selection.
     *
     * @param wifiInfo info of currently connected network
     * @return true if the network is sufficient
     */
    public boolean isNetworkSufficient(WifiInfo wifiInfo) {
        // Currently connected?
        if (wifiInfo.getSupplicantState() != SupplicantState.COMPLETED) {
            return false;
        }

        localLog("Current connected network: " + wifiInfo.getNetworkId());

        WifiConfiguration network =
                mWifiConfigManager.getConfiguredNetwork(wifiInfo.getNetworkId());

        if (network == null) {
            localLog("Current network was removed");
            return false;
        }

        // Skip autojoin for the first few seconds of a user-initiated connection.
        // This delays network selection during the time that connectivity service may be posting
        // a dialog about a no-internet network.
        if (mWifiConfigManager.getLastSelectedNetwork() == network.networkId
                && (mClock.getElapsedSinceBootMillis()
                    - mWifiConfigManager.getLastSelectedTimeStamp())
                <= mContext.getResources().getInteger(
                    R.integer.config_wifiSufficientDurationAfterUserSelectionMilliseconds)) {
            localLog("Current network is recently user-selected");
            return true;
        }

        // Set OSU (Online Sign Up) network for Passpoint Release 2 to sufficient
        // so that network select selection is skipped and OSU process can complete.
        if (network.osu) {
            localLog("Current connection is OSU");
            return true;
        }

        // Network without internet access is not sufficient, unless expected
        if (!hasInternetOrExpectNoInternet(wifiInfo)) {
            localLog("Current network has [" + network.numNoInternetAccessReports
                    + "] no-internet access reports");
            return false;
        }

        if (!hasSufficientLinkQuality(wifiInfo)) {
            localLog("Current network link quality is not sufficient");
            return false;
        }

        if (!hasActiveStream(wifiInfo)) {
            localLog("Current network has low ongoing traffic");
            return false;
        }

        return true;
    }

    private boolean isNetworkSelectionNeeded(List<ScanDetail> scanDetails, WifiInfo wifiInfo,
            boolean connected, boolean disconnected) {
        if (scanDetails.size() == 0) {
            localLog("Empty connectivity scan results. Skip network selection.");
            return false;
        }

        if (connected) {
            // Is roaming allowed?
            if (!mContext.getResources().getBoolean(
                    R.bool.config_wifi_framework_enable_associated_network_selection)) {
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
            // Please note other scans (e.g., location scan or app scan) may also trigger network
            // selection and these scans may or may not run sufficiency check.
            // So it is better to run sufficiency check here before network selection.
            if (isNetworkSufficient(wifiInfo)) {
                localLog("Current connected network already sufficient. Skip network selection.");
                return false;
            } else {
                localLog("Current connected network is not sufficient.");
                return true;
            }
        } else if (disconnected) {
            return true;
        } else {
            // No network selection if ClientModeImpl is in a state other than
            // CONNECTED or DISCONNECTED.
            localLog("ClientModeImpl is in neither CONNECTED nor DISCONNECTED state."
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
        return (scanResult.level < mScoringParams.getEntryRssi(scanResult.frequency));
    }

    private List<ScanDetail> filterScanResults(List<ScanDetail> scanDetails,
            Set<String> bssidBlacklist, boolean isConnected, String currentBssid) {
        List<ScanDetail> validScanDetails = new ArrayList<>();
        StringBuffer noValidSsid = new StringBuffer();
        StringBuffer blacklistedBssid = new StringBuffer();
        StringBuffer lowRssi = new StringBuffer();
        StringBuffer mboAssociationDisallowedBssid = new StringBuffer();
        boolean scanResultsHaveCurrentBssid = false;
        int numBssidFiltered = 0;

        for (ScanDetail scanDetail : scanDetails) {
            ScanResult scanResult = scanDetail.getScanResult();

            if (TextUtils.isEmpty(scanResult.SSID)) {
                noValidSsid.append(scanResult.BSSID).append(" / ");
                continue;
            }

            // Check if the scan results contain the currently connected BSSID
            if (scanResult.BSSID.equals(currentBssid)) {
                scanResultsHaveCurrentBssid = true;
                validScanDetails.add(scanDetail);
                continue;
            }

            final String scanId = toScanId(scanResult);

            if (bssidBlacklist.contains(scanResult.BSSID)) {
                blacklistedBssid.append(scanId).append(" / ");
                numBssidFiltered++;
                continue;
            }

            // Skip network with too weak signals.
            if (isSignalTooWeak(scanResult)) {
                lowRssi.append(scanId);
                if (scanResult.is24GHz()) {
                    lowRssi.append("(2.4GHz)");
                } else if (scanResult.is5GHz()) {
                    lowRssi.append("(5GHz)");
                } else if (scanResult.is6GHz()) {
                    lowRssi.append("(6GHz)");
                }
                lowRssi.append(scanResult.level).append(" / ");
                continue;
            }

            // Skip BSS which is not accepting new connections.
            NetworkDetail networkDetail = scanDetail.getNetworkDetail();
            if (networkDetail != null) {
                if (networkDetail.getMboAssociationDisallowedReasonCode()
                        != MboOceConstants.MBO_OCE_ATTRIBUTE_NOT_PRESENT) {
                    mWifiMetrics
                            .incrementNetworkSelectionFilteredBssidCountDueToMboAssocDisallowInd();
                    mboAssociationDisallowedBssid.append(scanId).append("(")
                            .append(networkDetail.getMboAssociationDisallowedReasonCode())
                            .append(")").append(" / ");
                    continue;
                }
            }

            validScanDetails.add(scanDetail);
        }
        mWifiMetrics.incrementNetworkSelectionFilteredBssidCount(numBssidFiltered);

        // WNS listens to all single scan results. Some scan requests may not include
        // the channel of the currently connected network, so the currently connected
        // network won't show up in the scan results. We don't act on these scan results
        // to avoid aggressive network switching which might trigger disconnection.
        // TODO(b/147751334) this may no longer be needed
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
            localLog("Networks filtered out due to blocklist: " + blacklistedBssid);
        }

        if (lowRssi.length() != 0) {
            localLog("Networks filtered out due to low signal strength: " + lowRssi);
        }

        if (mboAssociationDisallowedBssid.length() != 0) {
            localLog("Networks filtered out due to mbo association disallowed indication: "
                    + mboAssociationDisallowedBssid);
        }

        return validScanDetails;
    }

    private boolean isEnhancedOpenSupported() {
        if (mIsEnhancedOpenSupportedInitialized) {
            return mIsEnhancedOpenSupported;
        }

        mIsEnhancedOpenSupportedInitialized = true;
        mIsEnhancedOpenSupported = (mWifiNative.getSupportedFeatureSet(
                mWifiNative.getClientInterfaceName()) & WIFI_FEATURE_OWE) != 0;
        return mIsEnhancedOpenSupported;
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
        boolean enhancedOpenSupported = isEnhancedOpenSupported();
        for (ScanDetail scanDetail : mFilteredNetworks) {
            ScanResult scanResult = scanDetail.getScanResult();

            if (!ScanResultUtil.isScanResultForOpenNetwork(scanResult)) {
                continue;
            }

            // Filter out Enhanced Open networks on devices that do not support it
            if (ScanResultUtil.isScanResultForOweNetwork(scanResult)
                    && !enhancedOpenSupported) {
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
     * selection procedure.
     *
     * @param netId ID for the network chosen by the user
     * @return true -- There is change made to connection choice of any saved network.
     * false -- There is no change made to connection choice of any saved network.
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
                    WifiConfiguration.NetworkSelectionStatus.DISABLED_NONE);
        }
        return setLegacyUserConnectChoice(selected);
    }

    /**
     * This maintains the legacy user connect choice state in the config store
     */
    private boolean setLegacyUserConnectChoice(@NonNull final WifiConfiguration selected) {
        boolean change = false;
        String key = selected.getKey();
        List<WifiConfiguration> configuredNetworks = mWifiConfigManager.getConfiguredNetworks();

        for (WifiConfiguration network : configuredNetworks) {
            WifiConfiguration.NetworkSelectionStatus status = network.getNetworkSelectionStatus();
            if (network.networkId == selected.networkId) {
                if (status.getConnectChoice() != null) {
                    localLog("Remove user selection preference of " + status.getConnectChoice()
                            + " from " + network.SSID + " : " + network.networkId);
                    mWifiConfigManager.clearNetworkConnectChoice(network.networkId);
                    change = true;
                }
                continue;
            }

            if (status.getSeenInLastQualifiedNetworkSelection()
                    && !key.equals(status.getConnectChoice())) {
                localLog("Add key: " + key + " to "
                        + toNetworkString(network));
                mWifiConfigManager.setNetworkConnectChoice(network.networkId, key);
                change = true;
            }
        }

        return change;
    }


    /**
     * Iterate thru the list of configured networks (includes all saved network configurations +
     * any ephemeral network configurations created for passpoint networks, suggestions, carrier
     * networks, etc) and do the following:
     * a) Try to re-enable any temporarily enabled networks (if the blacklist duration has expired).
     * b) Clear the {@link WifiConfiguration.NetworkSelectionStatus#getCandidate()} field for all
     * of them to identify networks that are present in the current scan result.
     * c) Log any disabled networks.
     */
    private void updateConfiguredNetworks() {
        List<WifiConfiguration> configuredNetworks = mWifiConfigManager.getConfiguredNetworks();
        if (configuredNetworks.size() == 0) {
            localLog("No configured networks.");
            return;
        }

        StringBuffer sbuf = new StringBuffer();
        for (WifiConfiguration network : configuredNetworks) {
            // If a configuration is temporarily disabled, re-enable it before trying
            // to connect to it.
            mWifiConfigManager.tryEnableNetwork(network.networkId);
            // Clear the cached candidate, score and seen.
            mWifiConfigManager.clearNetworkCandidateScanResult(network.networkId);

            // Log disabled network.
            WifiConfiguration.NetworkSelectionStatus status = network.getNetworkSelectionStatus();
            if (!status.isNetworkEnabled()) {
                sbuf.append("  ").append(toNetworkString(network)).append(" ");
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
                                        .getNetworkSelectionDisableReasonString(index))
                                .append(", count=").append(count).append("; ");
                    }
                }
                sbuf.append("\n");
            }
        }

        if (sbuf.length() > 0) {
            localLog("Disabled configured networks:");
            localLog(sbuf.toString());
        }
    }

    /**
     * Overrides the {@code candidate} chosen by the {@link #mNominators} with the user chosen
     * {@link WifiConfiguration} if one exists.
     *
     * @return the user chosen {@link WifiConfiguration} if one exists, {@code candidate} otherwise
     */
    private WifiConfiguration overrideCandidateWithUserConnectChoice(
            @NonNull WifiConfiguration candidate) {
        WifiConfiguration tempConfig = Preconditions.checkNotNull(candidate);
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
            mWifiMetrics.setNominatorForNetwork(candidate.networkId,
                    WifiMetricsProto.ConnectionEvent.NOMINATOR_SAVED_USER_CONNECT_CHOICE);
        }
        return candidate;
    }


    /**
     * Indicates whether we have ever seen the network to be metered since wifi was enabled.
     *
     * This is sticky to prevent continuous flip-flopping between networks, when the metered
     * status is learned after association.
     */
    private boolean isEverMetered(@NonNull WifiConfiguration config, @Nullable WifiInfo info,
            @NonNull ScanDetail scanDetail) {
        // If info does not match config, don't use it.
        if (info != null && info.getNetworkId() != config.networkId) info = null;
        boolean metered = WifiConfiguration.isMetered(config, info);
        NetworkDetail networkDetail = scanDetail.getNetworkDetail();
        if (networkDetail != null
                && networkDetail.getAnt()
                == NetworkDetail.Ant.ChargeablePublic) {
            metered = true;
        }
        mWifiMetrics.addMeteredStat(config, metered);
        if (config.meteredOverride != WifiConfiguration.METERED_OVERRIDE_NONE) {
            // User override is in effect; we should trust it
            if (mKnownMeteredNetworkIds.remove(config.networkId)) {
                localLog("KnownMeteredNetworkIds = " + mKnownMeteredNetworkIds);
            }
            metered = config.meteredOverride == WifiConfiguration.METERED_OVERRIDE_METERED;
        } else if (mKnownMeteredNetworkIds.contains(config.networkId)) {
            // Use the saved information
            metered = true;
        } else if (metered) {
            // Update the saved information
            mKnownMeteredNetworkIds.add(config.networkId);
            localLog("KnownMeteredNetworkIds = " + mKnownMeteredNetworkIds);
        }
        return metered;
    }

    /**
     * Returns the set of known metered network ids (for tests. dumpsys, and metrics).
     */
    public Set<Integer> getKnownMeteredNetworkIds() {
        return new ArraySet<>(mKnownMeteredNetworkIds);
    }

    private final ArraySet<Integer> mKnownMeteredNetworkIds = new ArraySet<>();


    /**
     * Cleans up state that should go away when wifi is disabled.
     */
    public void resetOnDisable() {
        mWifiConfigManager.clearLastSelectedNetwork();
        mKnownMeteredNetworkIds.clear();
    }

    /**
     * Returns the list of Candidates from networks in range.
     *
     * @param scanDetails             List of ScanDetail for all the APs in range
     * @param bssidBlacklist          Blacklisted BSSIDs
     * @param wifiInfo                Currently connected network
     * @param connected               True if the device is connected
     * @param disconnected            True if the device is disconnected
     * @param untrustedNetworkAllowed True if untrusted networks are allowed for connection
     * @return list of valid Candidate(s)
     */
    public List<WifiCandidates.Candidate> getCandidatesFromScan(
            List<ScanDetail> scanDetails, Set<String> bssidBlacklist, WifiInfo wifiInfo,
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

        // Update the scan detail cache at the start, even if we skip network selection
        updateScanDetailCache(scanDetails);

        // Shall we start network selection at all?
        if (!isNetworkSelectionNeeded(scanDetails, wifiInfo, connected, disconnected)) {
            return null;
        }

        // Update all configured networks before initiating network selection.
        updateConfiguredNetworks();

        // Update the registered network nominators.
        for (NetworkNominator registeredNominator : mNominators) {
            registeredNominator.update(scanDetails);
        }

        // Filter out unwanted networks.
        mFilteredNetworks = filterScanResults(scanDetails, bssidBlacklist,
                connected && wifiInfo.getScore() >= WIFI_POOR_SCORE, currentBssid);
        if (mFilteredNetworks.size() == 0) {
            return null;
        }

        WifiCandidates wifiCandidates = new WifiCandidates(mWifiScoreCard, mContext);
        if (currentNetwork != null) {
            wifiCandidates.setCurrent(currentNetwork.networkId, currentBssid);
            // We always want the current network to be a candidate so that it can participate.
            // It may also get re-added by a nominator, in which case this fallback
            // will be replaced.
            MacAddress bssid = MacAddress.fromString(currentBssid);
            WifiCandidates.Key key = new WifiCandidates.Key(
                    ScanResultMatchInfo.fromWifiConfiguration(currentNetwork),
                    bssid, currentNetwork.networkId);
            wifiCandidates.add(key, currentNetwork,
                    NetworkNominator.NOMINATOR_ID_CURRENT,
                    wifiInfo.getRssi(),
                    wifiInfo.getFrequency(),
                    calculateLastSelectionWeight(currentNetwork.networkId),
                    WifiConfiguration.isMetered(currentNetwork, wifiInfo),
                    isFromCarrierOrPrivilegedApp(currentNetwork),
                    0 /* Mbps */);
        }
        for (NetworkNominator registeredNominator : mNominators) {
            localLog("About to run " + registeredNominator.getName() + " :");
            registeredNominator.nominateNetworks(
                    new ArrayList<>(mFilteredNetworks), currentNetwork, currentBssid, connected,
                    untrustedNetworkAllowed,
                    (scanDetail, config) -> {
                        WifiCandidates.Key key = wifiCandidates.keyFromScanDetailAndConfig(
                                scanDetail, config);
                        if (key != null) {
                            boolean metered = isEverMetered(config, wifiInfo, scanDetail);
                            // TODO(b/151981920) Saved passpoint candidates are marked ephemeral
                            boolean added = wifiCandidates.add(key, config,
                                    registeredNominator.getId(),
                                    scanDetail.getScanResult().level,
                                    scanDetail.getScanResult().frequency,
                                    calculateLastSelectionWeight(config.networkId),
                                    metered,
                                    isFromCarrierOrPrivilegedApp(config),
                                    predictThroughput(scanDetail));
                            if (added) {
                                mConnectableNetworks.add(Pair.create(scanDetail, config));
                                mWifiConfigManager.updateScanDetailForNetwork(
                                        config.networkId, scanDetail);
                                mWifiMetrics.setNominatorForNetwork(config.networkId,
                                        toProtoNominatorId(registeredNominator.getId()));
                            }
                        }
                    });
        }
        if (mConnectableNetworks.size() != wifiCandidates.size()) {
            localLog("Connectable: " + mConnectableNetworks.size()
                    + " Candidates: " + wifiCandidates.size());
        }
        return wifiCandidates.getCandidates();
    }

    /**
     * Using the registered Scorers, choose the best network from the list of Candidate(s).
     * The ScanDetailCache is also updated here.
     * @param candidates - Candidates to perferm network selection on.
     * @return WifiConfiguration - the selected network, or null.
     */
    @NonNull
    public WifiConfiguration selectNetwork(List<WifiCandidates.Candidate> candidates) {
        if (candidates == null || candidates.size() == 0) {
            return null;
        }
        WifiCandidates wifiCandidates = new WifiCandidates(mWifiScoreCard, mContext, candidates);
        final WifiCandidates.CandidateScorer activeScorer = getActiveCandidateScorer();
        // Update the NetworkSelectionStatus in the configs for the current candidates
        // This is needed for the legacy user connect choice, at least
        Collection<Collection<WifiCandidates.Candidate>> groupedCandidates =
                wifiCandidates.getGroupedCandidates();
        for (Collection<WifiCandidates.Candidate> group : groupedCandidates) {
            WifiCandidates.ScoredCandidate choice = activeScorer.scoreCandidates(group);
            if (choice == null) continue;
            ScanDetail scanDetail = getScanDetailForCandidateKey(choice.candidateKey);
            if (scanDetail == null) continue;
            mWifiConfigManager.setNetworkCandidateScanResult(choice.candidateKey.networkId,
                    scanDetail.getScanResult(), 0);
        }

        for (Collection<WifiCandidates.Candidate> group : groupedCandidates) {
            for (WifiCandidates.Candidate candidate : group.stream()
                    .sorted((a, b) -> (b.getScanRssi() - a.getScanRssi())) // decreasing rssi
                    .collect(Collectors.toList())) {
                localLog(candidate.toString());
            }
        }

        ArrayMap<Integer, Integer> experimentNetworkSelections = new ArrayMap<>(); // for metrics

        int selectedNetworkId = WifiConfiguration.INVALID_NETWORK_ID;

        // Run all the CandidateScorers
        boolean legacyOverrideWanted = true;
        for (WifiCandidates.CandidateScorer candidateScorer : mCandidateScorers.values()) {
            WifiCandidates.ScoredCandidate choice;
            try {
                choice = wifiCandidates.choose(candidateScorer);
            } catch (RuntimeException e) {
                Log.wtf(TAG, "Exception running a CandidateScorer", e);
                continue;
            }
            int networkId = choice.candidateKey == null
                    ? WifiConfiguration.INVALID_NETWORK_ID
                    : choice.candidateKey.networkId;
            String chooses = " would choose ";
            if (candidateScorer == activeScorer) {
                chooses = " chooses ";
                legacyOverrideWanted = choice.userConnectChoiceOverride;
                selectedNetworkId = networkId;
                updateChosenPasspointNetwork(choice);
            }
            String id = candidateScorer.getIdentifier();
            int expid = experimentIdFromIdentifier(id);
            localLog(id + chooses + networkId
                    + " score " + choice.value + "+/-" + choice.err
                    + " expid " + expid);
            experimentNetworkSelections.put(expid, networkId);
        }

        // Update metrics about differences in the selections made by various methods
        final int activeExperimentId = experimentIdFromIdentifier(activeScorer.getIdentifier());
        for (Map.Entry<Integer, Integer> entry :
                experimentNetworkSelections.entrySet()) {
            int experimentId = entry.getKey();
            if (experimentId == activeExperimentId) continue;
            int thisSelectedNetworkId = entry.getValue();
            mWifiMetrics.logNetworkSelectionDecision(experimentId, activeExperimentId,
                    selectedNetworkId == thisSelectedNetworkId,
                    groupedCandidates.size());
        }

        // Get a fresh copy of WifiConfiguration reflecting any scan result updates
        WifiConfiguration selectedNetwork =
                mWifiConfigManager.getConfiguredNetwork(selectedNetworkId);
        if (selectedNetwork != null && legacyOverrideWanted) {
            selectedNetwork = overrideCandidateWithUserConnectChoice(selectedNetwork);
        }
        if (selectedNetwork != null) {
            mLastNetworkSelectionTimeStamp = mClock.getElapsedSinceBootMillis();
        }
        return selectedNetwork;
    }

    /**
     * Returns the ScanDetail given the candidate key, using the saved list of connectible networks.
     */
    private ScanDetail getScanDetailForCandidateKey(WifiCandidates.Key candidateKey) {
        if (candidateKey == null) return null;
        String bssid = candidateKey.bssid.toString();
        for (Pair<ScanDetail, WifiConfiguration> pair : mConnectableNetworks) {
            if (candidateKey.networkId == pair.second.networkId
                    && bssid.equals(pair.first.getBSSIDString())) {
                return pair.first;
            }
        }
        return null;
    }

    private void updateChosenPasspointNetwork(WifiCandidates.ScoredCandidate choice) {
        if (choice.candidateKey == null) {
            return;
        }
        WifiConfiguration config =
                mWifiConfigManager.getConfiguredNetwork(choice.candidateKey.networkId);
        if (config == null) {
            return;
        }
        if (config.isPasspoint()) {
            config.SSID = choice.candidateKey.matchInfo.networkSsid;
            mWifiConfigManager.addOrUpdateNetwork(config, config.creatorUid, config.creatorName);
        }
    }

    private void updateScanDetailCache(List<ScanDetail> scanDetails) {
        for (ScanDetail scanDetail : scanDetails) {
            mWifiConfigManager.updateScanDetailCacheFromScanDetail(scanDetail);
        }
    }

    private static int toProtoNominatorId(@NetworkNominator.NominatorId int nominatorId) {
        switch (nominatorId) {
            case NetworkNominator.NOMINATOR_ID_SAVED:
                return WifiMetricsProto.ConnectionEvent.NOMINATOR_SAVED;
            case NetworkNominator.NOMINATOR_ID_SUGGESTION:
                return WifiMetricsProto.ConnectionEvent.NOMINATOR_SUGGESTION;
            case NetworkNominator.NOMINATOR_ID_SCORED:
                return WifiMetricsProto.ConnectionEvent.NOMINATOR_EXTERNAL_SCORED;
            case NetworkNominator.NOMINATOR_ID_CURRENT:
                Log.e(TAG, "Unexpected NOMINATOR_ID_CURRENT", new RuntimeException());
                return WifiMetricsProto.ConnectionEvent.NOMINATOR_UNKNOWN;
            default:
                Log.e(TAG, "UnrecognizedNominatorId" + nominatorId);
                return WifiMetricsProto.ConnectionEvent.NOMINATOR_UNKNOWN;
        }
    }

    private double calculateLastSelectionWeight(int networkId) {
        if (networkId != mWifiConfigManager.getLastSelectedNetwork()) return 0.0;
        double timeDifference = mClock.getElapsedSinceBootMillis()
                - mWifiConfigManager.getLastSelectedTimeStamp();
        long millis = TimeUnit.MINUTES.toMillis(mScoringParams.getLastSelectionMinutes());
        if (timeDifference >= millis) return 0.0;
        double unclipped = 1.0 - (timeDifference / millis);
        return Math.min(Math.max(unclipped, 0.0), 1.0);
    }

    private WifiCandidates.CandidateScorer getActiveCandidateScorer() {
        WifiCandidates.CandidateScorer ans = mCandidateScorers.get(PRESET_CANDIDATE_SCORER_NAME);
        int overrideExperimentId = mScoringParams.getExperimentIdentifier();
        if (overrideExperimentId >= MIN_SCORER_EXP_ID) {
            for (WifiCandidates.CandidateScorer candidateScorer : mCandidateScorers.values()) {
                int expId = experimentIdFromIdentifier(candidateScorer.getIdentifier());
                if (expId == overrideExperimentId) {
                    ans = candidateScorer;
                    break;
                }
            }
        }
        if (ans == null && PRESET_CANDIDATE_SCORER_NAME != null) {
            Log.wtf(TAG, PRESET_CANDIDATE_SCORER_NAME + " is not registered!");
        }
        mWifiMetrics.setNetworkSelectorExperimentId(ans == null
                ? LEGACY_CANDIDATE_SCORER_EXP_ID
                : experimentIdFromIdentifier(ans.getIdentifier()));
        return ans;
    }

    private int predictThroughput(@NonNull ScanDetail scanDetail) {
        if (scanDetail.getScanResult() == null || scanDetail.getNetworkDetail() == null) {
            return 0;
        }
        int channelUtilizationLinkLayerStats = BssLoad.INVALID;
        if (mWifiChannelUtilization != null) {
            channelUtilizationLinkLayerStats =
                    mWifiChannelUtilization.getUtilizationRatio(
                            scanDetail.getScanResult().frequency);
        }
        return mThroughputPredictor.predictThroughput(
                mWifiNative.getDeviceWiphyCapabilities(mWifiNative.getClientInterfaceName()),
                scanDetail.getScanResult().getWifiStandard(),
                scanDetail.getScanResult().channelWidth,
                scanDetail.getScanResult().level,
                scanDetail.getScanResult().frequency,
                scanDetail.getNetworkDetail().getMaxNumberSpatialStreams(),
                scanDetail.getNetworkDetail().getChannelUtilization(),
                channelUtilizationLinkLayerStats,
                mIsBluetoothConnected);
    }

    /**
     * Register a network nominator
     *
     * @param nominator the network nominator to be registered
     */
    public void registerNetworkNominator(@NonNull NetworkNominator nominator) {
        mNominators.add(Preconditions.checkNotNull(nominator));
    }

    /**
     * Register a candidate scorer.
     *
     * Replaces any existing scorer having the same identifier.
     */
    public void registerCandidateScorer(@NonNull WifiCandidates.CandidateScorer candidateScorer) {
        String name = Preconditions.checkNotNull(candidateScorer).getIdentifier();
        if (name != null) {
            mCandidateScorers.put(name, candidateScorer);
        }
    }

    /**
     * Unregister a candidate scorer.
     */
    public void unregisterCandidateScorer(@NonNull WifiCandidates.CandidateScorer candidateScorer) {
        String name = Preconditions.checkNotNull(candidateScorer).getIdentifier();
        if (name != null) {
            mCandidateScorers.remove(name);
        }
    }

    private static boolean isFromCarrierOrPrivilegedApp(WifiConfiguration config) {
        if (config.fromWifiNetworkSuggestion
                && config.carrierId != TelephonyManager.UNKNOWN_CARRIER_ID) {
            // Privileged carrier suggestion
            return true;
        }
        if (config.isEphemeral()
                && !config.fromWifiNetworkSpecifier
                && !config.fromWifiNetworkSuggestion) {
            // From ScoredNetworkNominator
            return true;
        }
        return false;
    }

    /**
     * Derives a numeric experiment identifier from a CandidateScorer's identifier.
     *
     * @returns a positive number that starts with the decimal digits ID_PREFIX
     */
    public static int experimentIdFromIdentifier(String id) {
        final int digits = (int) (((long) id.hashCode()) & Integer.MAX_VALUE) % ID_SUFFIX_MOD;
        return ID_PREFIX * ID_SUFFIX_MOD + digits;
    }

    private static final int ID_SUFFIX_MOD = 1_000_000;
    private static final int ID_PREFIX = 42;
    private static final int MIN_SCORER_EXP_ID = ID_PREFIX * ID_SUFFIX_MOD;

    /**
     * Set Wifi channel utilization calculated from link layer stats
     */
    public void setWifiChannelUtilization(WifiChannelUtilization wifiChannelUtilization) {
        mWifiChannelUtilization = wifiChannelUtilization;
    }

    /**
     * Set whether bluetooth is in the connected state
     */
    public void setBluetoothConnected(boolean isBlueToothConnected) {
        mIsBluetoothConnected = isBlueToothConnected;
    }

    WifiNetworkSelector(Context context, WifiScoreCard wifiScoreCard, ScoringParams scoringParams,
            WifiConfigManager configManager, Clock clock, LocalLog localLog,
            WifiMetrics wifiMetrics, WifiNative wifiNative,
            ThroughputPredictor throughputPredictor) {
        mContext = context;
        mWifiConfigManager = configManager;
        mClock = clock;
        mWifiScoreCard = wifiScoreCard;
        mScoringParams = scoringParams;
        mLocalLog = localLog;
        mWifiMetrics = wifiMetrics;
        mWifiNative = wifiNative;
        mThroughputPredictor = throughputPredictor;
    }
}
