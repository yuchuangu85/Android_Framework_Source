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

package android.ranging.ble.rssi;

import static android.ranging.raw.RawRangingDevice.UPDATE_RATE_FREQUENT;
import static android.ranging.raw.RawRangingDevice.UPDATE_RATE_INFREQUENT;

/**
 * BLE RSSI constants.
 *
 * @hide
 */
public final class BleRssiConstants {

    private static final int FREQUENT_INTERVAL_MS = 500;
    private static final int NORMAL_INTERVAL_MS = 1000;
    private static final int INFREQUENT_INTERVAL_MS = 3000;

    /**
     * Returns the raw interval values in ms for each update rate.
     *
     * @param updateRate enum
     * @return interval value in ms
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
