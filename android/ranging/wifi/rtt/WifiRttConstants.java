/*
 * Copyright 2025 The Android Open Source Project
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

package android.ranging.wifi.rtt;

import androidx.annotation.IntDef;

/**
 * Wifi Rtt ranging constants.
 *
 * @hide
 */
public final class WifiRttConstants {

    /**
     * Requests for ranging data in 512 milliseconds
     */
    public static final int NORMAL = 1;
    /**
     * Requests for ranging data in 8192 milliseconds
     */
    public static final int INFREQUENT = 2;
    /**
     * Requests for ranging data in 256 milliseconds
     */
    public static final int FAST = 3;
    private static final int FAST_PERIODIC_INTERVAL_MS = 128;
    private static final int FAST_INTERVAL_MS = 256;
    private static final int NORMAL_PERIODIC_INTERVAL_MS = 256;
    private static final int NORMAL_INTERVAL_MS = 512;
    private static final int INFREQUENT_INTERVAL_MS = 8192;

    /**
     * Get interval values in ms.
     *
     * @param updateRate                        enum
     * @param isPeriodicRangingHwFeatureEnabled bool
     * @return interval value in ms
     */
    public static int getIntervalMs(@RangingUpdateRate int updateRate,
            boolean isPeriodicRangingHwFeatureEnabled) {
        switch (updateRate) {
            case FAST -> {
                return isPeriodicRangingHwFeatureEnabled ? FAST_PERIODIC_INTERVAL_MS
                        : FAST_INTERVAL_MS;
            }
            case INFREQUENT -> {
                return INFREQUENT_INTERVAL_MS;
            }
            default -> {
                return isPeriodicRangingHwFeatureEnabled ? NORMAL_PERIODIC_INTERVAL_MS
                        : NORMAL_INTERVAL_MS;
            }
        }
    }

    @IntDef({
            INFREQUENT,
            NORMAL,
            FAST,
    })
    public @interface RangingUpdateRate {
    }
}
