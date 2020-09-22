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
 * A CandidateScorer that weights the RSSIs for more compactly-shaped
 * regions of selection around access points.
 */
final class BubbleFunScorer implements WifiCandidates.CandidateScorer {

    /**
     * This should match WifiNetworkSelector.experimentIdFromIdentifier(getIdentifier())
     * when using the default ScoringParams.
     */
    public static final int BUBBLE_FUN_SCORER_DEFAULT_EXPID = 42598152;

    private static final double SECURITY_AWARD = 44.0;
    private static final double CURRENT_NETWORK_BOOST = 22.0;
    private static final double LAST_SELECTION_BOOST = 250.0;
    private static final double LOW_BAND_FACTOR = 0.25;
    private static final double TYPICAL_SCAN_RSSI_STD = 4.0;
    private static final boolean USE_USER_CONNECT_CHOICE = true;

    private final ScoringParams mScoringParams;

    BubbleFunScorer(ScoringParams scoringParams) {
        mScoringParams = scoringParams;
    }

    @Override
    public String getIdentifier() {
        return "BubbleFunScorer_v2";
    }

    /**
     * Calculates an individual candidate's score.
     *
     * Ideally, this is a pure function of the candidate, and side-effect free.
     */
    private ScoredCandidate scoreCandidate(Candidate candidate) {
        final int rssi = candidate.getScanRssi();
        final int rssiEntryThreshold = mScoringParams.getEntryRssi(candidate.getFrequency());

        double score = shapeFunction(rssi) - shapeFunction(rssiEntryThreshold);

        // If we are below the entry threshold, make the score more negative
        if (score < 0.0) score *= 2.0;

        // The gain is approximately the derivative of shapeFunction at the given rssi
        // This is used to estimate the error
        double gain = shapeFunction(rssi + 0.5)
                    - shapeFunction(rssi - 0.5);

        // Prefer 5GHz/6GHz when all are strong, but at the fringes, 2.4 might be better
        // Typically the entry rssi is lower for the 2.4 band, which provides the fringe boost
        if (ScanResult.is24GHz(candidate.getFrequency())) {
            score *= LOW_BAND_FACTOR;
            gain *= LOW_BAND_FACTOR;
        }

        // A recently selected network gets a large boost
        score += candidate.getLastSelectionWeight() * LAST_SELECTION_BOOST;

        // Hysteresis to prefer staying on the current network.
        if (candidate.isCurrentNetwork()) {
            score += CURRENT_NETWORK_BOOST;
        }

        if (!candidate.isOpenNetwork()) {
            score += SECURITY_AWARD;
        }

        return new ScoredCandidate(score, TYPICAL_SCAN_RSSI_STD * gain,
                                   USE_USER_CONNECT_CHOICE, candidate);
    }

    /**
     * Reshapes raw RSSI into a value that varies more usefully for scoring purposes.
     *
     * The most important aspect of this function is that it is monotone (has
     * positive slope). The offset and scale are not important, because the
     * calculation above uses differences that cancel out the offset, and
     * a rescaling here effects all the candidates' scores in the same way.
     * However, we choose to scale things for an overall range of about 100 for
     * useful values of RSSI.
     */
    private static double unscaledShapeFunction(double rssi) {
        return -Math.exp(-rssi * BELS_PER_DECIBEL);
    }
    private static final double BELS_PER_DECIBEL = 0.1;

    private static final double RESCALE_FACTOR = 100.0 / (
            unscaledShapeFunction(0.0) - unscaledShapeFunction(-85.0));
    private static double shapeFunction(double rssi) {
        return unscaledShapeFunction(rssi) * RESCALE_FACTOR;
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
