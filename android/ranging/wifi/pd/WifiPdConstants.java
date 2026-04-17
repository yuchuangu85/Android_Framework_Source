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

package android.ranging.wifi.pd;

import static android.ranging.raw.RawRangingDevice.UPDATE_RATE_FREQUENT;
import static android.ranging.raw.RawRangingDevice.UPDATE_RATE_INFREQUENT;
import static android.ranging.raw.RawRangingDevice.UPDATE_RATE_NORMAL;

/**
 * WiFi PD constants.
 *
 * @hide
 */
public final class WifiPdConstants {

    private static final int FREQUENT_INTERVAL_MS = 120;
    private static final int NORMAL_INTERVAL_MS = 240;
    private static final int INFREQUENT_INTERVAL_MS = 600;

    public static final int IEEE_802_11MC = 0x1;
    public static final int IEEE_802_11AZ = 0x2;

    // Channel number to frequency in Mhz
    public static final int CHANNEL_36 = 5180;
    public static final int CHANNEL_40 = 5200;
    public static final int CHANNEL_44 = 5220;
    public static final int CHANNEL_48 = 5240;
    public static final int CHANNEL_153 = 5765;
    public static final int CHANNEL_157 = 5785;
    public static final int CHANNEL_161 = 5805;
    public static final int CHANNEL_165 = 5825;
    public static final int CHANNEL_1 = 2412;
    public static final int CHANNEL_11 = 2462;

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

    public static int getUpdateRateFromMs(int rangingInterval) {
        switch (rangingInterval) {
            case FREQUENT_INTERVAL_MS -> {
                return UPDATE_RATE_FREQUENT;
            }
            case INFREQUENT_INTERVAL_MS -> {
                return UPDATE_RATE_INFREQUENT;
            }
            default -> {
                return UPDATE_RATE_NORMAL;
            }
        }
    }
}
