/*
 * Copyright 2017 The Android Open Source Project
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

import android.content.Context;
import android.net.wifi.ScanResult;

import com.android.internal.annotations.VisibleForTesting;

import java.util.Collection;

/**
 * Evaluates ScanResults for Wifi Wake.
 */
public class WakeupEvaluator {

    private final int mThresholdMinimumRssi24;
    private final int mThresholdMinimumRssi5;

    /**
     * Constructs a {@link WakeupEvaluator} using the given context.
     */
    public static WakeupEvaluator fromContext(Context context) {
        ScoringParams scoringParams = new ScoringParams(context); // TODO(b/74793980) - replumb
        return new WakeupEvaluator(scoringParams.getEntryRssi(ScoringParams.BAND2),
                                   scoringParams.getEntryRssi(ScoringParams.BAND5));
    }

    @VisibleForTesting
    WakeupEvaluator(int minimumRssi24, int minimumRssi5) {
        mThresholdMinimumRssi24 = minimumRssi24;
        mThresholdMinimumRssi5 = minimumRssi5;
    }

    /**
     * Searches ScanResults to find a connectable network.
     *
     * <p>This method searches the given ScanResults for one that is present in the given
     * ScanResultMatchInfos and has a sufficiently high RSSI. If there is no such ScanResult, it
     * returns null. If there are multiple, it returns the one with the highest RSSI.
     *
     * @param scanResults ScanResults to search
     * @param savedNetworks Network list to compare against
     * @return The {@link ScanResult} representing an in-range connectable network, or {@code null}
     *         signifying there is no viable network
     */
    public ScanResult findViableNetwork(Collection<ScanResult> scanResults,
                                        Collection<ScanResultMatchInfo> savedNetworks) {
        ScanResult selectedScanResult = null;

        for (ScanResult scanResult : scanResults) {
            if (isBelowThreshold(scanResult)) {
                continue;
            }
            if (savedNetworks.contains(ScanResultMatchInfo.fromScanResult(scanResult))) {
                if (selectedScanResult == null || selectedScanResult.level < scanResult.level) {
                    selectedScanResult = scanResult;
                }
            }
        }

        return selectedScanResult;
    }

    /**
     * Returns whether the given ScanResult's signal strength is below the selection threshold.
     */
    public boolean isBelowThreshold(ScanResult scanResult) {
        return ((scanResult.is24GHz() && scanResult.level < mThresholdMinimumRssi24)
                || (scanResult.is5GHz() && scanResult.level < mThresholdMinimumRssi5));
    }
}
