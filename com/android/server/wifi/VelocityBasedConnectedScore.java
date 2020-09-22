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

import android.net.wifi.ScanResult;
import android.net.wifi.WifiInfo;

import com.android.server.wifi.util.KalmanFilter;
import com.android.server.wifi.util.Matrix;

/**
 * Class used to calculate scores for connected wifi networks and report it to the associated
 * network agent.
 */
public class VelocityBasedConnectedScore extends ConnectedScore {

    private final ScoringParams mScoringParams;

    private int mFrequency = ScanResult.BAND_5_GHZ_START_FREQ_MHZ;
    private double mThresholdAdjustment;
    private final KalmanFilter mFilter;
    private long mLastMillis;

    public VelocityBasedConnectedScore(ScoringParams scoringParams, Clock clock) {
        super(clock);
        mScoringParams = scoringParams;
        mFilter = new KalmanFilter();
        mFilter.mH = new Matrix(2, new double[]{1.0, 0.0});
        mFilter.mR = new Matrix(1, new double[]{1.0});
    }

    /**
     * Set the Kalman filter's state transition matrix F and process noise covariance Q given
     * a time step.
     *
     * @param dt delta time, in seconds
     */
    private void setDeltaTimeSeconds(double dt) {
        mFilter.mF = new Matrix(2, new double[]{1.0, dt, 0.0, 1.0});
        Matrix tG = new Matrix(1, new double[]{0.5 * dt * dt, dt});
        double stda = 0.02; // standard deviation of modelled acceleration
        mFilter.mQ = tG.dotTranspose(tG).dot(new Matrix(2, new double[]{
                stda * stda, 0.0,
                0.0, stda * stda}));
    }
    /**
     * Reset the filter state.
     */
    @Override
    public void reset() {
        mLastMillis = 0;
        mThresholdAdjustment = 0;
        mFilter.mx = null;
    }

    /**
     * Updates scoring state using RSSI and measurement noise estimate
     * <p>
     * This is useful if an RSSI comes from another source (e.g. scan results) and the
     * expected noise varies by source.
     *
     * @param rssi              signal strength (dB).
     * @param millis            millisecond-resolution time.
     * @param standardDeviation of the RSSI.
     */
    @Override
    public void updateUsingRssi(int rssi, long millis, double standardDeviation) {
        if (millis <= 0) return;
        if (mLastMillis <= 0 || millis < mLastMillis || mFilter.mx == null) {
            double initialVariance = 9.0 * standardDeviation * standardDeviation;
            mFilter.mx = new Matrix(1, new double[]{rssi, 0.0});
            mFilter.mP = new Matrix(2, new double[]{initialVariance, 0.0, 0.0, 0.0});
        } else {
            double dt = (millis - mLastMillis) * 0.001;
            mFilter.mR.put(0, 0, standardDeviation * standardDeviation);
            setDeltaTimeSeconds(dt);
            mFilter.predict();
            mFilter.update(new Matrix(1, new double[]{rssi}));
        }
        mLastMillis = millis;
        mFilteredRssi = mFilter.mx.get(0, 0);
        mEstimatedRateOfRssiChange = mFilter.mx.get(1, 0);
    }

    /**
     * Updates the state.
     */
    @Override
    public void updateUsingWifiInfo(WifiInfo wifiInfo, long millis) {
        int frequency = wifiInfo.getFrequency();
        if (frequency != mFrequency) {
            mLastMillis = 0; // Probably roamed; reset filter but retain threshold adjustment
            // Consider resetting or partially resetting threshold adjustment
            // Consider checking bssid
            mFrequency = frequency;
        }
        updateUsingRssi(wifiInfo.getRssi(), millis, mDefaultRssiStandardDeviation);
        adjustThreshold(wifiInfo);
    }

    private double mFilteredRssi;
    private double mEstimatedRateOfRssiChange;

    /**
     * Returns the most recently computed extimate of the RSSI.
     */
    public double getFilteredRssi() {
        return mFilteredRssi;
    }

    /**
     * Returns the estimated rate of change of RSSI, in dB/second
     */
    public double getEstimatedRateOfRssiChange() {
        return mEstimatedRateOfRssiChange;
    }

    /**
     * Returns the adjusted RSSI threshold
     */
    public double getAdjustedRssiThreshold() {
        return mScoringParams.getExitRssi(mFrequency) + mThresholdAdjustment;
    }

    private double mMinimumPpsForMeasuringSuccess = 2.0;

    /**
     * Adjusts the threshold if appropriate
     * <p>
     * If the (filtered) rssi is near or below the current effective threshold, and the
     * rate of rssi change is small, and there is traffic, and the error rate is looking
     * reasonable, then decrease the effective threshold to keep from dropping a perfectly good
     * connection.
     *
     */
    private void adjustThreshold(WifiInfo wifiInfo) {
        if (mThresholdAdjustment < -7) return;
        if (mFilteredRssi >= getAdjustedRssiThreshold() + 2.0) return;
        if (Math.abs(mEstimatedRateOfRssiChange) >= 0.2) return;
        double txSuccessPps = wifiInfo.getSuccessfulTxPacketsPerSecond();
        double rxSuccessPps = wifiInfo.getSuccessfulRxPacketsPerSecond();
        if (txSuccessPps < mMinimumPpsForMeasuringSuccess) return;
        if (rxSuccessPps < mMinimumPpsForMeasuringSuccess) return;
        double txBadPps = wifiInfo.getLostTxPacketsPerSecond();
        double txRetriesPps = wifiInfo.getRetriedTxPacketsPerSecond();
        double probabilityOfSuccessfulTx = txSuccessPps / (txSuccessPps + txBadPps + txRetriesPps);
        if (probabilityOfSuccessfulTx > 0.2) {
            // May want this amount to vary with how close to threshold we are
            mThresholdAdjustment -= 0.5;
        }
    }

    /**
     * Velocity scorer - predict the rssi a few seconds from now
     */
    @Override
    public int generateScore() {
        if (mFilter.mx == null) return WIFI_TRANSITION_SCORE + 1;
        double badRssi = getAdjustedRssiThreshold();
        double horizonSeconds = mScoringParams.getHorizonSeconds();
        Matrix x = new Matrix(mFilter.mx);
        double filteredRssi = x.get(0, 0);
        setDeltaTimeSeconds(horizonSeconds);
        x = mFilter.mF.dot(x);
        double forecastRssi = x.get(0, 0);
        if (forecastRssi > filteredRssi) {
            forecastRssi = filteredRssi; // Be pessimistic about predicting an actual increase
        }
        int score = (int) (Math.round(forecastRssi) - badRssi) + WIFI_TRANSITION_SCORE;
        return score;
    }
}
