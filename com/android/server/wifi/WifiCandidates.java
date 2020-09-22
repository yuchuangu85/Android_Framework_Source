/*
 * Copyright 2018 The Android Open Source Project
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
import android.net.MacAddress;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.util.ArrayMap;

import com.android.internal.util.Preconditions;
import com.android.server.wifi.proto.WifiScoreCardProto;
import com.android.wifi.resources.R;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.stream.Collectors;

/**
 * Candidates for network selection
 */
public class WifiCandidates {
    private static final String TAG = "WifiCandidates";

    public WifiCandidates(@NonNull WifiScoreCard wifiScoreCard, @NonNull Context context) {
        this(wifiScoreCard, context, Collections.EMPTY_LIST);
    }

    public WifiCandidates(@NonNull WifiScoreCard wifiScoreCard, @NonNull Context context,
            @NonNull List<Candidate> candidates) {
        mWifiScoreCard = Preconditions.checkNotNull(wifiScoreCard);
        mContext = context;
        for (Candidate c : candidates) {
            mCandidates.put(c.getKey(), c);
        }
    }

    private final WifiScoreCard mWifiScoreCard;
    private final Context mContext;

    /**
     * Represents a connectable candidate.
     */
    public interface Candidate {
        /**
         * Gets the Key, which contains the SSID, BSSID, security type, and config id.
         *
         * Generally, a CandidateScorer should not need to use this.
         */
        @Nullable Key getKey();

        /**
         * Gets the config id.
         */
        int getNetworkConfigId();
        /**
         * Returns true for an open network.
         */
        boolean isOpenNetwork();
        /**
         * Returns true for a passpoint network.
         */
        boolean isPasspoint();
        /**
         * Returns true for an ephemeral network.
         */
        boolean isEphemeral();
        /**
         * Returns true for a trusted network.
         */
        boolean isTrusted();
        /**
         * Returns true if suggestion came from a carrier or privileged app.
         */
        boolean isCarrierOrPrivileged();
        /**
         * Returns true for a metered network.
         */
        boolean isMetered();

        /**
         * Returns true if network doesn't have internet access during last connection
         */
        boolean hasNoInternetAccess();

        /**
         * Returns true if network is expected not to have Internet access
         * (e.g., a wireless printer, a Chromecast hotspot, etc.).
         */
        boolean isNoInternetAccessExpected();

        /**
         * Returns the ID of the nominator that provided the candidate.
         */
        @WifiNetworkSelector.NetworkNominator.NominatorId
        int getNominatorId();

        /**
         * Returns true if the candidate is in the same network as the
         * current connection.
         */
        boolean isCurrentNetwork();
        /**
         * Return true if the candidate is currently connected.
         */
        boolean isCurrentBssid();
        /**
         * Returns a value between 0 and 1.
         *
         * 1.0 means the network was recently selected by the user or an app.
         * 0.0 means not recently selected by user or app.
         */
        double getLastSelectionWeight();
        /**
         * Gets the scan RSSI.
         */
        int getScanRssi();
        /**
         * Gets the scan frequency.
         */
        int getFrequency();
        /**
         * Gets the predicted throughput in Mbps.
         */
        int getPredictedThroughputMbps();
        /**
         * Estimated probability of getting internet access (percent 0-100).
         */
        int getEstimatedPercentInternetAvailability();
        /**
         * Gets statistics from the scorecard.
         */
        @Nullable WifiScoreCardProto.Signal getEventStatistics(WifiScoreCardProto.Event event);
    }

    /**
     * Represents a connectable candidate
     */
    private static class CandidateImpl implements Candidate {
        private final Key mKey;                   // SSID/sectype/BSSID/configId
        private final @WifiNetworkSelector.NetworkNominator.NominatorId int mNominatorId;
        private final int mScanRssi;
        private final int mFrequency;
        private final double mLastSelectionWeight;
        private final WifiScoreCard.PerBssid mPerBssid; // For accessing the scorecard entry
        private final boolean mIsCurrentNetwork;
        private final boolean mIsCurrentBssid;
        private final boolean mIsMetered;
        private final boolean mHasNoInternetAccess;
        private final boolean mIsNoInternetAccessExpected;
        private final boolean mIsOpenNetwork;
        private final boolean mPasspoint;
        private final boolean mEphemeral;
        private final boolean mTrusted;
        private final boolean mCarrierOrPrivileged;
        private final int mPredictedThroughputMbps;
        private final int mEstimatedPercentInternetAvailability;

        CandidateImpl(Key key, WifiConfiguration config,
                WifiScoreCard.PerBssid perBssid,
                @WifiNetworkSelector.NetworkNominator.NominatorId int nominatorId,
                int scanRssi,
                int frequency,
                double lastSelectionWeight,
                boolean isCurrentNetwork,
                boolean isCurrentBssid,
                boolean isMetered,
                boolean isCarrierOrPrivileged,
                int predictedThroughputMbps) {
            this.mKey = key;
            this.mNominatorId = nominatorId;
            this.mScanRssi = scanRssi;
            this.mFrequency = frequency;
            this.mPerBssid = perBssid;
            this.mLastSelectionWeight = lastSelectionWeight;
            this.mIsCurrentNetwork = isCurrentNetwork;
            this.mIsCurrentBssid = isCurrentBssid;
            this.mIsMetered = isMetered;
            this.mHasNoInternetAccess = config.hasNoInternetAccess();
            this.mIsNoInternetAccessExpected = config.isNoInternetAccessExpected();
            this.mIsOpenNetwork = WifiConfigurationUtil.isConfigForOpenNetwork(config);
            this.mPasspoint = config.isPasspoint();
            this.mEphemeral = config.isEphemeral();
            this.mTrusted = config.trusted;
            this.mCarrierOrPrivileged = isCarrierOrPrivileged;
            this.mPredictedThroughputMbps = predictedThroughputMbps;
            this.mEstimatedPercentInternetAvailability = perBssid == null ? 50 :
                    perBssid.estimatePercentInternetAvailability();
        }

        @Override
        public Key getKey() {
            return mKey;
        }

        @Override
        public int getNetworkConfigId() {
            return mKey.networkId;
        }

        @Override
        public boolean isOpenNetwork() {
            return mIsOpenNetwork;
        }

        @Override
        public boolean isPasspoint() {
            return mPasspoint;
        }

        @Override
        public boolean isEphemeral() {
            return mEphemeral;
        }

        @Override
        public boolean isTrusted() {
            return mTrusted;
        }

        @Override
        public boolean isCarrierOrPrivileged() {
            return mCarrierOrPrivileged;
        }

        @Override
        public boolean isMetered() {
            return mIsMetered;
        }

        @Override
        public boolean hasNoInternetAccess() {
            return mHasNoInternetAccess;
        }

        @Override
        public boolean isNoInternetAccessExpected() {
            return mIsNoInternetAccessExpected;
        }

        @Override
        public @WifiNetworkSelector.NetworkNominator.NominatorId int getNominatorId() {
            return mNominatorId;
        }

        @Override
        public double getLastSelectionWeight() {
            return mLastSelectionWeight;
        }

        @Override
        public boolean isCurrentNetwork() {
            return mIsCurrentNetwork;
        }

        @Override
        public boolean isCurrentBssid() {
            return mIsCurrentBssid;
        }

        @Override
        public int getScanRssi() {
            return mScanRssi;
        }

        @Override
        public int getFrequency() {
            return mFrequency;
        }

        @Override
        public int getPredictedThroughputMbps() {
            return mPredictedThroughputMbps;
        }

        @Override
        public int getEstimatedPercentInternetAvailability() {
            return mEstimatedPercentInternetAvailability;
        }

        /**
         * Accesses statistical information from the score card
         */
        @Override
        public WifiScoreCardProto.Signal getEventStatistics(WifiScoreCardProto.Event event) {
            if (mPerBssid == null) return null;
            WifiScoreCard.PerSignal perSignal = mPerBssid.lookupSignal(event, getFrequency());
            if (perSignal == null) return null;
            return perSignal.toSignal();
        }

        @Override
        public String toString() {
            Key key = getKey();
            String lastSelectionWeightString = "";
            if (getLastSelectionWeight() != 0.0) {
                // Round this to 3 places
                lastSelectionWeightString = "lastSelectionWeight = "
                        + Math.round(getLastSelectionWeight() * 1000.0) / 1000.0
                        + ", ";
            }
            return "Candidate { "
                    + "config = " + getNetworkConfigId() + ", "
                    + "bssid = " + key.bssid + ", "
                    + "freq = " + getFrequency() + ", "
                    + "rssi = " + getScanRssi() + ", "
                    + "Mbps = " + getPredictedThroughputMbps() + ", "
                    + "nominator = " + getNominatorId() + ", "
                    + "pInternet = " + getEstimatedPercentInternetAvailability() + ", "
                    + lastSelectionWeightString
                    + (isCurrentBssid() ? "connected, " : "")
                    + (isCurrentNetwork() ? "current, " : "")
                    + (isEphemeral() ? "ephemeral" : "saved") + ", "
                    + (isTrusted() ? "trusted, " : "")
                    + (isCarrierOrPrivileged() ? "priv, " : "")
                    + (isMetered() ? "metered, " : "")
                    + (hasNoInternetAccess() ? "noInternet, " : "")
                    + (isNoInternetAccessExpected() ? "noInternetExpected, " : "")
                    + (isPasspoint() ? "passpoint, " : "")
                    + (isOpenNetwork() ? "open" : "secure") + " }";
        }
    }

    /**
     * Represents a scoring function
     */
    public interface CandidateScorer {
        /**
         * The scorer's name, and perhaps important parameterization/version.
         */
        String getIdentifier();

        /**
         * Calculates the best score for a collection of candidates.
         */
        @Nullable ScoredCandidate scoreCandidates(@NonNull Collection<Candidate> candidates);

    }

    /**
     * Represents a candidate with a real-valued score, along with an error estimate.
     *
     * Larger values reflect more desirable candidates. The range is arbitrary,
     * because scores generated by different sources are not compared with each
     * other.
     *
     * The error estimate is on the same scale as the value, and should
     * always be strictly positive. For instance, it might be the standard deviation.
     */
    public static class ScoredCandidate {
        public final double value;
        public final double err;
        public final Key candidateKey;
        public final boolean userConnectChoiceOverride;
        public ScoredCandidate(double value, double err, boolean userConnectChoiceOverride,
                Candidate candidate) {
            this.value = value;
            this.err = err;
            this.candidateKey = (candidate == null) ? null : candidate.getKey();
            this.userConnectChoiceOverride = userConnectChoiceOverride;
        }
        /**
         * Represents no score
         */
        public static final ScoredCandidate NONE =
                new ScoredCandidate(Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY,
                        false, null);
    }

    /**
     * The key used for tracking candidates, consisting of SSID, security type, BSSID, and network
     * configuration id.
     */
    // TODO (b/123014687) unify with similar classes in the framework
    public static class Key {
        public final ScanResultMatchInfo matchInfo; // Contains the SSID and security type
        public final MacAddress bssid;
        public final int networkId;                 // network configuration id

        public Key(ScanResultMatchInfo matchInfo,
                   MacAddress bssid,
                   int networkId) {
            this.matchInfo = matchInfo;
            this.bssid = bssid;
            this.networkId = networkId;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Key)) return false;
            Key that = (Key) other;
            return (this.matchInfo.equals(that.matchInfo)
                    && this.bssid.equals(that.bssid)
                    && this.networkId == that.networkId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(matchInfo, bssid, networkId);
        }
    }

    private final Map<Key, Candidate> mCandidates = new ArrayMap<>();

    private int mCurrentNetworkId = -1;
    @Nullable private MacAddress mCurrentBssid = null;

    /**
     * Sets up information about the currently-connected network.
     */
    public void setCurrent(int currentNetworkId, String currentBssid) {
        mCurrentNetworkId = currentNetworkId;
        mCurrentBssid = null;
        if (currentBssid == null) return;
        try {
            mCurrentBssid = MacAddress.fromString(currentBssid);
        } catch (RuntimeException e) {
            failWithException(e);
        }
    }

    /**
     * Adds a new candidate
     *
     * @return true if added or replaced, false otherwise
     */
    public boolean add(ScanDetail scanDetail,
            WifiConfiguration config,
            @WifiNetworkSelector.NetworkNominator.NominatorId int nominatorId,
            double lastSelectionWeightBetweenZeroAndOne,
            boolean isMetered,
            int predictedThroughputMbps) {
        Key key = keyFromScanDetailAndConfig(scanDetail, config);
        if (key == null) return false;
        return add(key, config, nominatorId,
                scanDetail.getScanResult().level,
                scanDetail.getScanResult().frequency,
                lastSelectionWeightBetweenZeroAndOne,
                isMetered,
                false,
                predictedThroughputMbps);
    }

    /**
     * Makes a Key from a ScanDetail and WifiConfiguration (null if error).
     */
    public @Nullable Key keyFromScanDetailAndConfig(ScanDetail scanDetail,
            WifiConfiguration config) {
        if (!validConfigAndScanDetail(config, scanDetail)) return null;
        ScanResult scanResult = scanDetail.getScanResult();
        MacAddress bssid = MacAddress.fromString(scanResult.BSSID);
        return new Key(ScanResultMatchInfo.fromScanResult(scanResult), bssid, config.networkId);
    }

    /**
     * Adds a new candidate
     *
     * @return true if added or replaced, false otherwise
     */
    public boolean add(@NonNull Key key,
            WifiConfiguration config,
            @WifiNetworkSelector.NetworkNominator.NominatorId int nominatorId,
            int scanRssi,
            int frequency,
            double lastSelectionWeightBetweenZeroAndOne,
            boolean isMetered,
            boolean isCarrierOrPrivileged,
            int predictedThroughputMbps) {
        Candidate old = mCandidates.get(key);
        if (old != null) {
            // check if we want to replace this old candidate
            if (nominatorId > old.getNominatorId()) return false;
            remove(old);
        }
        WifiScoreCard.PerBssid perBssid = mWifiScoreCard.lookupBssid(
                key.matchInfo.networkSsid,
                key.bssid.toString());
        perBssid.setSecurityType(
                WifiScoreCardProto.SecurityType.forNumber(key.matchInfo.networkType));
        perBssid.setNetworkConfigId(config.networkId);
        CandidateImpl candidate = new CandidateImpl(key, config, perBssid, nominatorId,
                scanRssi,
                frequency,
                Math.min(Math.max(lastSelectionWeightBetweenZeroAndOne, 0.0), 1.0),
                config.networkId == mCurrentNetworkId,
                key.bssid.equals(mCurrentBssid),
                isMetered,
                isCarrierOrPrivileged,
                predictedThroughputMbps);
        mCandidates.put(key, candidate);
        return true;
    }

    /**
     * Checks that the supplied config and scan detail are valid (for the parts
     * we care about) and consistent with each other.
     *
     * @param config to be validated
     * @param scanDetail to be validated
     * @return true if the config and scanDetail are consistent with each other
     */
    private boolean validConfigAndScanDetail(WifiConfiguration config, ScanDetail scanDetail) {
        if (config == null) return failure();
        if (scanDetail == null) return failure();
        ScanResult scanResult = scanDetail.getScanResult();
        if (scanResult == null) return failure();
        MacAddress bssid;
        try {
            bssid = MacAddress.fromString(scanResult.BSSID);
        } catch (RuntimeException e) {
            return failWithException(e);
        }
        ScanResultMatchInfo key1 = ScanResultMatchInfo.fromScanResult(scanResult);
        if (!config.isPasspoint()) {
            ScanResultMatchInfo key2 = ScanResultMatchInfo.fromWifiConfiguration(config);
            if (!key1.matchForNetworkSelection(key2, mContext.getResources()
                    .getBoolean(R.bool.config_wifiSaeUpgradeEnabled))) {
                return failure(key1, key2);
            }
        }
        return true;
    }

    /**
     * Removes a candidate
     * @return true if the candidate was successfully removed
     */
    public boolean remove(Candidate candidate) {
        if (!(candidate instanceof CandidateImpl)) return failure();
        return mCandidates.remove(candidate.getKey(), candidate);
    }

    /**
     * Returns the number of candidates (at the BSSID level)
     */
    public int size() {
        return mCandidates.size();
    }

    /**
     * Returns the candidates, grouped by network.
     */
    public Collection<Collection<Candidate>> getGroupedCandidates() {
        Map<Integer, Collection<Candidate>> candidatesForNetworkId = new ArrayMap<>();
        for (Candidate candidate : mCandidates.values()) {
            Collection<Candidate> cc = candidatesForNetworkId.get(candidate.getNetworkConfigId());
            if (cc == null) {
                cc = new ArrayList<>(2); // Guess 2 bssids per network
                candidatesForNetworkId.put(candidate.getNetworkConfigId(), cc);
            }
            cc.add(candidate);
        }
        return candidatesForNetworkId.values();
    }

    /**
     * Return a copy of the Candidates.
     */
    public List<Candidate> getCandidates() {
        return mCandidates.entrySet().stream().map(entry -> entry.getValue())
                .collect(Collectors.toList());
    }

    /**
     * Make a choice from among the candidates, using the provided scorer.
     *
     * @return the chosen scored candidate, or ScoredCandidate.NONE.
     */
    public @NonNull ScoredCandidate choose(@NonNull CandidateScorer candidateScorer) {
        Preconditions.checkNotNull(candidateScorer);
        Collection<Candidate> candidates = new ArrayList<>(mCandidates.values());
        ScoredCandidate choice = candidateScorer.scoreCandidates(candidates);
        return choice == null ? ScoredCandidate.NONE : choice;
    }

    /**
     * After a failure indication is returned, this may be used to get details.
     */
    public RuntimeException getLastFault() {
        return mLastFault;
    }

    /**
     * Returns the number of faults we have seen
     */
    public int getFaultCount() {
        return mFaultCount;
    }

    /**
     * Clears any recorded faults
     */
    public void clearFaults() {
        mLastFault = null;
        mFaultCount = 0;
    }

    /**
     * Controls whether to immediately raise an exception on a failure
     */
    public WifiCandidates setPicky(boolean picky) {
        mPicky = picky;
        return this;
    }

    /**
     * Records details about a failure
     *
     * This captures a stack trace, so don't bother to construct a string message, just
     * supply any culprits (convertible to strings) that might aid diagnosis.
     *
     * @return false
     * @throws RuntimeException (if in picky mode)
     */
    private boolean failure(Object... culprits) {
        StringJoiner joiner = new StringJoiner(",");
        for (Object c : culprits) {
            joiner.add("" + c);
        }
        return failWithException(new IllegalArgumentException(joiner.toString()));
    }

    /**
     * As above, if we already have an exception.
     */
    private boolean failWithException(RuntimeException e) {
        mLastFault = e;
        mFaultCount++;
        if (mPicky) {
            throw e;
        }
        return false;
    }

    private boolean mPicky = false;
    private RuntimeException mLastFault = null;
    private int mFaultCount = 0;
}
