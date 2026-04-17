/*
 * Copyright (C) 2026 The Android Open Source Project
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

package com.android.internal.telephony.satellite;

import static android.telephony.TelephonyManager.SATELLITE_PURCHASE_MODE_STATE_INACTIVE;

import android.annotation.FlaggedApi;
import android.telephony.TelephonyManager;
import android.util.Pair;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.flags.Flags;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages satellite purchase mode state
 */
@FlaggedApi(Flags.FLAG_SATELLITE_UPSELL_26Q4)
public class PurchaseModeTracker {
    private final Object mSatellitePurchaseModeLock = new Object();
    /**
     * Map key: subId, value: boolean indicating if satellite purchase mode is in progress.
     */
    @GuardedBy("mSatellitePurchaseModeLock")
    private ConcurrentHashMap<Integer, Pair<Boolean, Integer>>
            mSatellitePurchaseModeStatus =
            new ConcurrentHashMap<>();

    public static final Pair<Boolean, Integer> DEFAULT_PURCHASE_MODE_STATE =
            new Pair<>(false, SATELLITE_PURCHASE_MODE_STATE_INACTIVE);

    /**
     * Create a tracker for Satellite purchase mode status
     *
     * @param initialPurchaseModeStatus initial state
     */
    public PurchaseModeTracker(ConcurrentHashMap<Integer,
            Pair<Boolean, Integer>> initialPurchaseModeStatus) {
        mSatellitePurchaseModeStatus = initialPurchaseModeStatus;
    }

    /**
     * Create a tracker for Satellite purchase mode status
     */
    public PurchaseModeTracker() {
        mSatellitePurchaseModeStatus = new ConcurrentHashMap<>();
    }

    /**
     * Set satellite purchase mode in progress or not.
     *
     * @param subId subscription ID.
     * @param isEnabled {@code true} If satellite purchase mode is in progress,
     *                         {@code false} otherwise.
     * @param purchaseModeState State of the purchase mode. Network setup, teardown and Purchase
     *                          Mode active or inactive. Inactive by default.
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    public void setSatellitePurchaseModeInProgress(int subId, boolean isEnabled,
            @TelephonyManager.SatellitePurchaseModeState int purchaseModeState) {
        synchronized (mSatellitePurchaseModeLock) {
            Pair<Boolean, Integer> newPurchaseModeStatus =
                    new Pair<>(isEnabled, purchaseModeState);
            mSatellitePurchaseModeStatus.put(subId, newPurchaseModeStatus);
        }
    }

    /**
     * Notify satellite purchase mode in progress or not.
     *
     * @param phone Calling phone.
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    public void notifySatellitePurchaseModeInProgress(Phone phone) {
        int subId = phone.getSubId();

        synchronized (mSatellitePurchaseModeLock) {
            Pair<Boolean, Integer> currentPurchaseModeStatus =
                    mSatellitePurchaseModeStatus.getOrDefault(subId,
                            DEFAULT_PURCHASE_MODE_STATE);
            boolean isEnabled = currentPurchaseModeStatus.first;
            int purchaseModeState = currentPurchaseModeStatus.second;
            phone.notifySatellitePurchaseModeChanged(isEnabled, purchaseModeState);
        }
    }
}
