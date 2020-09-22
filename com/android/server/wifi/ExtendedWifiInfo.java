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

import android.annotation.NonNull;
import android.content.Context;
import android.net.wifi.WifiInfo;

import com.android.wifi.resources.R;

/**
 * Extends WifiInfo with the methods for computing the averaged packet rates
 */
public class ExtendedWifiInfo extends WifiInfo {
    private static final long RESET_TIME_STAMP = Long.MIN_VALUE;
    private static final double FILTER_TIME_CONSTANT = 3000.0;
    private static final int SOURCE_UNKNOWN = 0;
    private static final int SOURCE_TRAFFIC_COUNTERS = 1;
    private static final int SOURCE_LLSTATS = 2;

    private final Context mContext;

    private int mLastSource = SOURCE_UNKNOWN;
    private long mLastPacketCountUpdateTimeStamp = RESET_TIME_STAMP;

    ExtendedWifiInfo(Context context) {
        super();
        mContext = context;
    }

    @Override
    public void reset() {
        super.reset();
        mLastSource = SOURCE_UNKNOWN;
        mLastPacketCountUpdateTimeStamp = RESET_TIME_STAMP;
        if (mContext.getResources().getBoolean(
                R.bool.config_wifi_connected_mac_randomization_supported)) {
            setMacAddress(DEFAULT_MAC_ADDRESS);
        }
    }

    /**
     * Updates the packet rates using link layer stats
     *
     * @param stats WifiLinkLayerStats
     * @param timeStamp time in milliseconds
     */
    public void updatePacketRates(@NonNull WifiLinkLayerStats stats, long timeStamp) {
        long txgood = stats.txmpdu_be + stats.txmpdu_bk + stats.txmpdu_vi + stats.txmpdu_vo;
        long txretries = stats.retries_be + stats.retries_bk + stats.retries_vi + stats.retries_vo;
        long txbad = stats.lostmpdu_be + stats.lostmpdu_bk + stats.lostmpdu_vi + stats.lostmpdu_vo;
        long rxgood = stats.rxmpdu_be + stats.rxmpdu_bk + stats.rxmpdu_vi + stats.rxmpdu_vo;
        update(SOURCE_LLSTATS, txgood, txretries, txbad, rxgood, timeStamp);
    }

    /**
     * This function is less powerful and used if the WifiLinkLayerStats API is not implemented
     * at the Wifi HAL
     */
    public void updatePacketRates(long txPackets, long rxPackets, long timeStamp) {
        update(SOURCE_TRAFFIC_COUNTERS, txPackets, 0, 0, rxPackets, timeStamp);
    }

    private void update(int source, long txgood, long txretries, long txbad, long rxgood,
            long timeStamp) {
        if (source == mLastSource
                && mLastPacketCountUpdateTimeStamp != RESET_TIME_STAMP
                && mLastPacketCountUpdateTimeStamp < timeStamp
                && txBad <= txbad
                && txSuccess <= txgood
                && rxSuccess <= rxgood
                && txRetries <= txretries) {
            long timeDelta = timeStamp - mLastPacketCountUpdateTimeStamp;
            double lastSampleWeight = Math.exp(-1.0 * timeDelta / FILTER_TIME_CONSTANT);
            double currentSampleWeight = 1.0 - lastSampleWeight;

            setLostTxPacketsPerSecond(getLostTxPacketsPerSecond() * lastSampleWeight
                    + (txbad - txBad) * 1000.0 / timeDelta
                    * currentSampleWeight);
            setSuccessfulTxPacketsPerSecond(getSuccessfulTxPacketsPerSecond() * lastSampleWeight
                    + (txgood - txSuccess) * 1000.0 / timeDelta
                    * currentSampleWeight);
            setSuccessfulRxPacketsPerSecond(getSuccessfulRxPacketsPerSecond() * lastSampleWeight
                    + (rxgood - rxSuccess) * 1000.0 / timeDelta
                    * currentSampleWeight);
            setRetriedTxPacketsRate(getRetriedTxPacketsPerSecond() * lastSampleWeight
                    + (txretries - txRetries) * 1000.0 / timeDelta
                    * currentSampleWeight);
        } else {
            setLostTxPacketsPerSecond(0);
            setSuccessfulTxPacketsPerSecond(0);
            setSuccessfulRxPacketsPerSecond(0);
            setRetriedTxPacketsRate(0);
            mLastSource = source;
        }
        txBad = txbad;
        txSuccess = txgood;
        rxSuccess = rxgood;
        txRetries = txretries;
        mLastPacketCountUpdateTimeStamp = timeStamp;
    }
}
