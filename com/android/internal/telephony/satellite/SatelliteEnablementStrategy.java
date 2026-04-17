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

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.telephony.satellite.EnableRequestAttributes;

import com.android.internal.telephony.flags.Flags;

import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Interface for satellite enablement strategy.
 */
@FlaggedApi(Flags.FLAG_SATELLITE_UPSELL_26Q4)
public interface SatelliteEnablementStrategy {
    /**
     * Request to enable satellite.
     *
     * @param attributes The attributes of the enable request.
     * @param executor The executor on which the callback will be called.
     * @param resultListener Listener for the result of the operation.
     */
    void enableSatellite(@NonNull EnableRequestAttributes attributes,
            @NonNull Executor executor, @NonNull Consumer<Integer> resultListener);

    /**
     * Request to disable satellite.
     *
     * @param attributes The attributes of the disable request.
     * @param executor The executor on which the callback will be called.
     * @param resultListener Listener for the result of the operation.
     */
    void disableSatellite(@NonNull EnableRequestAttributes attributes,
            @NonNull Executor executor, @NonNull Consumer<Integer> resultListener);
}
