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

package android.ranging.ble.cs;

import static android.ranging.raw.RawRangingDevice.UPDATE_RATE_FREQUENT;
import static android.ranging.raw.RawRangingDevice.UPDATE_RATE_INFREQUENT;

/**
 * Constants for CS ranging.
 *
 * @hide
 */
public final class BleCsConstants {

    private static final int FREQUENT_INTERVAL_MS = 200;
    private static final int NORMAL_INTERVAL_MS = 3000;
    private static final int INFREQUENT_INTERVAL_MS = 5000;

    /**
     * Returns raw interval value in ms for given update rate.
     *
     * @param updateRate enum
     * @return raw interval value in ms
     */
    public static int getIntervalInMs(int updateRate) {
        switch (updateRate) {
            case UPDATE_RATE_FREQUENT -> {
                return FREQUENT_INTERVAL_MS;
            }
            case UPDATE_RATE_INFREQUENT -> {
                return INFREQUENT_INTERVAL_MS;
            }
            default -> {
                return NORMAL_INTERVAL_MS;
            }
        }
    }
}
