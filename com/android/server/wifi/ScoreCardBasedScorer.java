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

import com.android.server.wifi.WifiCandidates.Candidate;
import com.android.server.wifi.WifiCandidates.ScoredCandidate;
import com.android.server.wifi.WifiScoreCardProto.Event;

import java.util.Collection;

/**
 * A candidate scorer that uses the scorecard to influence the choice.
 */
final class ScoreCardBasedScorer implements WifiCandidates.CandidateScorer {

    /**
     * This should match WifiNetworkSelector.experimentIdFromIdentifier(getIdentifier())
     * when using the default ScoringParams.
     */
    public static final int SCORE_CARD_BASED_SCORER_DEFAULT_EXPID = 42902385;

    private final ScoringParams mScoringParams;

    // config_wifi_framework_RSSI_SCORE_OFFSET
    public static final int RSSI_SCORE_OFFSET = 85;

    // config_wifi_framework_RSSI_SCORE_SLOPE
    public static final int RSSI_SCORE_SLOPE_IS_4 = 4;

    // config_wifi_framework_5GHz_preference_boost_factor
    public static final int BAND_5GHZ_AWARD_IS_40 = 40;

    // config_wifi_framework_SECURITY_AWARD
    public static final int SECURITY_AWARD_IS_80 = 80;

    // config_wifi_framework_LAST_SELECTION_AWARD
    public static final int LAST_SELECTION_AWARD_IS_480 = 480;

    // config_wifi_framework_current_network_boost
    public static final int CURRENT_NETWORK_BOOST_IS_16 = 16;

    // config_wifi_framework_SAME_BSSID_AWARD
    public static final int SAME_BSSID_AWARD_IS_24 = 24;

    // Only use scorecard id we have data from this many polls
    public static final int MIN_POLLS_FOR_SIGNIFICANCE = 30;

    // Maximum allowable adjustment of the cutoff rssi (dB)
    public static final int RSSI_RAIL = 5;

    ScoreCardBasedScorer(ScoringParams scoringParams) {
        mScoringParams = scoringParams;
    }

    @Override
    public String getIdentifier() {
        return "ScoreCardBasedScorer";
    }

    /**
     * Calculates an individual candidate's score.
     */
    private ScoredCandidate scoreCandidate(Candidate candidate) {
        int rssiSaturationThreshold = mScoringParams.getGoodRssi(candidate.getFrequency());
        int rssi = Math.min(candidate.getScanRssi(), rssiSaturationThreshold);
        int cutoff = estimatedCutoff(candidate);
        int score = (rssi - cutoff) * RSSI_SCORE_SLOPE_IS_4;

        if (candidate.getFrequency() >= ScoringParams.MINIMUM_5GHZ_BAND_FREQUENCY_IN_MEGAHERTZ) {
            score += BAND_5GHZ_AWARD_IS_40;
        }
        score += (int) (candidate.getLastSelectionWeight() * LAST_SELECTION_AWARD_IS_480);

        if (candidate.isCurrentNetwork()) {
            score += CURRENT_NETWORK_BOOST_IS_16 + SAME_BSSID_AWARD_IS_24;
        }

        if (!candidate.isOpenNetwork()) {
            score += SECURITY_AWARD_IS_80;
        }

        // To simulate the old strict priority rule, subtract a penalty based on
        // which evaluator added the candidate.
        score -= 1000 * candidate.getEvaluatorId();

        return new ScoredCandidate(score, 10, candidate);
    }

    private int estimatedCutoff(Candidate candidate) {
        int cutoff = -RSSI_SCORE_OFFSET;
        int lowest = cutoff - RSSI_RAIL;
        int highest = cutoff + RSSI_RAIL;
        WifiScoreCardProto.Signal signal = candidate.getEventStatistics(Event.SIGNAL_POLL);
        if (signal == null) return cutoff;
        if (!signal.hasRssi()) return cutoff;
        if (signal.getRssi().getCount() > MIN_POLLS_FOR_SIGNIFICANCE) {
            double mean = signal.getRssi().getSum() / signal.getRssi().getCount();
            double mean_square = signal.getRssi().getSumOfSquares() / signal.getRssi().getCount();
            double variance = mean_square - mean * mean;
            double sigma = Math.sqrt(variance);
            double value = mean - 2.0 * sigma;
            cutoff = (int) Math.min(Math.max(value, lowest), highest);
        }
        return cutoff;
    }

    @Override
    public ScoredCandidate scoreCandidates(@NonNull Collection<Candidate> group) {
        ScoredCandidate choice = ScoredCandidate.NONE;
        for (Candidate candidate : group) {
            ScoredCandidate scoredCandidate = scoreCandidate(candidate);
            if (scoredCandidate.value > choice.value) {
                choice = scoredCandidate;
            }
        }
        // Here we just return the highest scored candidate; we could
        // compute a new score, if desired.
        return choice;
    }

    @Override
    public boolean userConnectChoiceOverrideWanted() {
        return true;
    }

}
