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

import android.annotation.NonNull;
import android.annotation.FlaggedApi;
import android.telephony.satellite.EnableRequestAttributes;
import android.telephony.satellite.SatelliteManager;

import com.android.internal.telephony.flags.Flags;

import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Manages satellite enablement for automatic connections.
 */
@FlaggedApi(Flags.FLAG_SATELLITE_UPSELL_26Q4)
public class AutoEnablementController implements SatelliteEnablementStrategy {
    @Override
    public void enableSatellite(@NonNull EnableRequestAttributes attributes,
            @NonNull Executor executor, @NonNull Consumer<Integer> resultListener) {
        // Implementation for automatic satellite enablement will go here.
        resultListener.accept(SatelliteManager.SATELLITE_RESULT_SUCCESS);
    }

    @Override
    public void disableSatellite(@NonNull EnableRequestAttributes attributes,
            @NonNull Executor executor, @NonNull Consumer<Integer> resultListener) {
        // Implementation for automatic satellite disablement will go here.
        resultListener.accept(SatelliteManager.SATELLITE_RESULT_SUCCESS);
    }
}
