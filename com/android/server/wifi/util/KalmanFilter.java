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

package com.android.server.wifi.util;

/**
 * Utility providiing a basic Kalman filter
 *
 * For background, see https://en.wikipedia.org/wiki/Kalman_filter
 */
public class KalmanFilter {
    public Matrix mF; // stateTransition
    public Matrix mQ; // processNoiseCovariance
    public Matrix mH; // observationModel
    public Matrix mR; // observationNoiseCovariance
    public Matrix mP; // aPosterioriErrorCovariance
    public Matrix mx; // stateEstimate

    /**
     * Performs the prediction phase of the filter, using the state estimate to produce
     * a new estimate for the current timestep.
     */
    public void predict() {
        mx = mF.dot(mx);
        mP = mF.dot(mP).dotTranspose(mF).plus(mQ);
    }

    /**
     * Updates the state estimate to incorporate the new observation z.
     */
    public void update(Matrix z) {
        Matrix y = z.minus(mH.dot(mx));
        Matrix tS = mH.dot(mP).dotTranspose(mH).plus(mR);
        Matrix tK = mP.dotTranspose(mH).dot(tS.inverse());
        mx = mx.plus(tK.dot(y));
        mP = mP.minus(tK.dot(mH).dot(mP));
    }

    @Override
    public String toString() {
        return "{F: " + mF
                + " Q: " + mQ
                + " H: " + mH
                + " R: " + mR
                + " P: " + mP
                + " x: " + mx
                + "}";
    }
}
