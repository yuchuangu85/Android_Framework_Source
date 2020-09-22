/*
 * Copyright 2019 The Android Open Source Project
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
import android.net.wifi.ScanResult;

import com.android.server.wifi.WifiCandidates.Candidate;
import com.android.server.wifi.WifiCandidates.ScoredCandidate;

import java.util.Collection;

/**
 * A candidate scorer that attempts to match the previous behavior.
 */
final class CompatibilityScorer implements WifiCandidates.CandidateScorer {

    /**
     * This should match WifiNetworkSelector.experimentIdFromIdentifier(getIdentifier())
     * when using the default ScoringParams.
     */
    public static final int COMPATIBILITY_SCORER_DEFAULT_EXPID = 42504592;

    private final ScoringParams mScoringParams;

    // config_wifi_framework_RSSI_SCORE_OFFSET
    public static final int RSSI_SCORE_OFFSET = 85;

    // config_wifi_framework_RSSI_SCORE_SLOPE
    public static final int RSSI_SCORE_SLOPE_IS_4 = 4;

    // config_wifi_framework_5GHz_preference_boost_factor
    public static final int BAND_5GHZ_AWARD_IS_40 = 40;

    // config_wifiFramework6ghzPreferenceBoostFactor
    public static final int BAND_6GHZ_AWARD_IS_40 = 40;

    // config_wifi_framework_SECURITY_AWARD
    public static final int SECURITY_AWARD_IS_80 = 80;

    // config_wifi_framework_LAST_SELECTION_AWARD
    public static final int LAST_SELECTION_AWARD_IS_480 = 480;

    // config_wifi_framework_current_network_boost
    public static final int CURRENT_NETWORK_BOOST_IS_16 = 16;

    // config_wifi_framework_SAME_BSSID_AWARD
    public static final int SAME_BSSID_AWARD_IS_24 = 24;

    private static final boolean USE_USER_CONNECT_CHOICE = true;

    CompatibilityScorer(ScoringParams scoringParams) {
        mScoringParams = scoringParams;
    }

    @Override
    public String getIdentifier() {
        return "CompatibilityScorer";
    }

    /**
     * Calculates an individual candidate's score.
     */
    private ScoredCandidate scoreCandidate(Candidate candidate) {
        int rssiSaturationThreshold = mScoringParams.getGoodRssi(candidate.getFrequency());
        int rssi = Math.min(candidate.getScanRssi(), rssiSaturationThreshold);
        int score = (rssi + RSSI_SCORE_OFFSET) * RSSI_SCORE_SLOPE_IS_4;

        if (ScanResult.is6GHz(candidate.getFrequency())) {
            score += BAND_6GHZ_AWARD_IS_40;
        } else if (ScanResult.is5GHz(candidate.getFrequency())) {
            score += BAND_5GHZ_AWARD_IS_40;
        }
        score += (int) (candidate.getLastSelectionWeight() * LAST_SELECTION_AWARD_IS_480);

        if (candidate.isCurrentNetwork()) {
            // Add both traditional awards, as would be be case with firmware roaming
            score += CURRENT_NETWORK_BOOST_IS_16 + SAME_BSSID_AWARD_IS_24;
        }

        if (!candidate.isOpenNetwork()) {
            score += SECURITY_AWARD_IS_80;
        }

        // To simulate the old strict priority rule, subtract a penalty based on
        // which nominator added the candidate.
        score -= 1000 * candidate.getNominatorId();

        // The old method breaks ties on the basis of RSSI, which we can
        // emulate easily since our score does not need to be an integer.
        double tieBreaker = candidate.getScanRssi() / 1000.0;
        return new ScoredCandidate(score + tieBreaker, 10,
                                   USE_USER_CONNECT_CHOICE, candidate);
    }

    @Override
    public ScoredCandidate scoreCandidates(@NonNull Collection<Candidate> candidates) {
        ScoredCandidate choice = ScoredCandidate.NONE;
        for (Candidate candidate : candidates) {
            ScoredCandidate scoredCandidate = scoreCandidate(candidate);
            if (scoredCandidate.value > choice.value) {
                choice = scoredCandidate;
            }
        }
        // Here we just return the highest scored candidate; we could
        // compute a new score, if desired.
        return choice;
    }

}
