/*
 * Copyright (C) 2017 The Android Open Source Project
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
import android.net.wifi.WifiInfo;

import com.android.internal.R;

/**
 * Experimental scorer, used when aggressive handover preference is set.
 */
public class AggressiveConnectedScore extends ConnectedScore {

    // Device configs. The values are examples.
    private final int mThresholdQualifiedRssi5;    // -70
    private final int mThresholdQualifiedRssi24;   // -73

    private int mFrequencyMHz = 5000;
    private int mRssi = 0;

    public AggressiveConnectedScore(Context context, Clock clock) {
        super(clock);
        mThresholdQualifiedRssi5 = context.getResources().getInteger(
                R.integer.config_wifi_framework_wifi_score_low_rssi_threshold_5GHz);
        mThresholdQualifiedRssi24 = context.getResources().getInteger(
                R.integer.config_wifi_framework_wifi_score_low_rssi_threshold_24GHz);
    }

    @Override
    public void updateUsingRssi(int rssi, long millis, double standardDeviation) {
        mRssi = rssi;
    }

    @Override
    public void updateUsingWifiInfo(WifiInfo wifiInfo, long millis) {
        mFrequencyMHz = wifiInfo.getFrequency();
        mRssi = wifiInfo.getRssi();
    }

    @Override
    public void reset() {
        mFrequencyMHz = 5000;
    }

    @Override
    public int generateScore() {
        int badRssi = mFrequencyMHz >= 5000 ? mThresholdQualifiedRssi5 : mThresholdQualifiedRssi24;
        int score = (mRssi - badRssi) + WIFI_TRANSITION_SCORE;
        return score;
    }
}
