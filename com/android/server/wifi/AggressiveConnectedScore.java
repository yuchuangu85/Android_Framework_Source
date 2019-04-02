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

import android.net.wifi.WifiInfo;

/**
 * Experimental scorer
 */
public class AggressiveConnectedScore extends ConnectedScore {

    private final ScoringParams mScoringParams;

    private int mFrequencyMHz = 5000;
    private int mRssi = 0;

    public AggressiveConnectedScore(ScoringParams scoringParams, Clock clock) {
        super(clock);
        mScoringParams = scoringParams;
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
        int threshRssi = mScoringParams.getSufficientRssi(mFrequencyMHz);
        int score = (mRssi - threshRssi) + WIFI_TRANSITION_SCORE;
        return score;
    }
}
