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

package android.ranging.uwb;

import android.util.ArrayMap;

import androidx.annotation.IntDef;

import java.util.Map;

/**
 * Uwb ranging constants
 *
 * @hide
 */
public final class UwbConstants {

    /**
     * FiRa-defined unicast {@code STATIC STS DS-TWR} ranging, deferred mode, ranging interval 240
     * ms.
     *
     * <p>Typical use case: device tracking tags.
     */
    public static final int CONFIG_UNICAST_DS_TWR = 1;
    public static final int CONFIG_MULTICAST_DS_TWR = 2;
    /** Same as {@code CONFIG_ID_1}, except P-STS security mode is enabled. */
    public static final int CONFIG_PROVISIONED_UNICAST_DS_TWR = 3;
    /** Same as {@code CONFIG_ID_2}, except P-STS security mode is enabled. */
    public static final int CONFIG_PROVISIONED_MULTICAST_DS_TWR = 4;
    /** Same as {@code CONFIG_ID_2}, except P-STS individual controlee key mode is enabled. */
    public static final int CONFIG_PROVISIONED_INDIVIDUAL_MULTICAST_DS_TWR = 5;
    /** Same as {@code CONFIG_ID_3}, except fast ranging interval is 96 milliseconds. */
    public static final int CONFIG_PROVISIONED_UNICAST_DS_TWR_VERY_FAST = 6;
    /**
     * Reports ranging data in hundreds of milliseconds (depending on the ranging interval setting
     * of the config)
     */
    public static final int NORMAL = 1;
    /** Reports ranging data in a couple of seconds (default to 4 seconds). */
    public static final int INFREQUENT = 2;
    /** Reports ranging data as fast as possible (depending on the device's capability). */
    public static final int FAST = 3;
    private static final Map<Integer, Map<Integer, Integer>> CONFIG_RANGING_INTERVAL_MAP =
            new ArrayMap<>();

    static {
        setRangingTimingParams(CONFIG_UNICAST_DS_TWR,
                Map.of(NORMAL, 240, FAST, 120, INFREQUENT, 600));

        setRangingTimingParams(CONFIG_MULTICAST_DS_TWR,
                Map.of(NORMAL, 200, FAST, 120, INFREQUENT, 600));

        setRangingTimingParams(CONFIG_PROVISIONED_UNICAST_DS_TWR,
                Map.of(NORMAL, 240, FAST, 120, INFREQUENT, 600));

        setRangingTimingParams(CONFIG_PROVISIONED_MULTICAST_DS_TWR,
                Map.of(NORMAL, 200, FAST, 120, INFREQUENT, 600));

        setRangingTimingParams(CONFIG_PROVISIONED_INDIVIDUAL_MULTICAST_DS_TWR,
                Map.of(NORMAL, 200, FAST, 120, INFREQUENT, 600));

        setRangingTimingParams(CONFIG_PROVISIONED_UNICAST_DS_TWR_VERY_FAST,
                Map.of(NORMAL, 240, FAST, 96, INFREQUENT, 600));
    }

    private static void setRangingTimingParams(
            @UwbConfigId int configId, Map<Integer, Integer> updateRateIntervalMap) {
        CONFIG_RANGING_INTERVAL_MAP.put(configId, updateRateIntervalMap);
    }

    /** Gets the raw interval duration in ms */
    public static int getIntervalMs(@UwbConfigId int configId, @RangingUpdateRate int updateRate) {
        return CONFIG_RANGING_INTERVAL_MAP.get(configId).get(updateRate);
    }

    /** Supported Ranging configurations. */
    @IntDef({
            CONFIG_UNICAST_DS_TWR,
            CONFIG_MULTICAST_DS_TWR,
            CONFIG_PROVISIONED_UNICAST_DS_TWR,
            CONFIG_PROVISIONED_MULTICAST_DS_TWR,
            CONFIG_PROVISIONED_INDIVIDUAL_MULTICAST_DS_TWR,
            CONFIG_PROVISIONED_UNICAST_DS_TWR_VERY_FAST,
    })
    public @interface UwbConfigId {
    }


    @IntDef({
            INFREQUENT,
            NORMAL,
            FAST,
    })
    public @interface RangingUpdateRate {
    }

    public static final byte[] DEFAULT_DLTDOA_SESSION_KEY_INFO =
            new byte[] {7, 8, 1, 2, 3, 4, 5, 6};
    public static final int DEFAULT_DLTDOA_CHANNEL_9 = 9;
    public static final int DEFAULT_DLTDOA_PREAMBLE_INDEX_10 = 10;
    public static final int DEFAULT_DLTDOA_RANGING_INTERVAL_200_MS = 200;
    public static final int DEFAULT_DLTDOA_SLOT_DURATION_2_MS = UwbRangingParams.DURATION_2_MS;
    public static final int DEFAULT_DLTDOA_SLOTS_PER_RANGING_ROUND_25 = 25;
    public static final byte[] DEFAULT_DLTDOA_RANGING_ROUND_INDEXES = new byte[] {0};
}
