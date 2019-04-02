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

import android.util.Log;

/**
 * This class is used to recover the wifi stack from a fatal failure. The recovery mechanism
 * involves triggering a stack restart (essentially simulating an airplane mode toggle) using
 * {@link WifiController}.
 * The current triggers for:
 * 1. Last resort watchdog bite.
 * 2. HAL/wificond crashes during normal operation.
 * 3. TBD: supplicant crashes during normal operation.
 */
public class SelfRecovery {
    private static final String TAG = "WifiSelfRecovery";

    /**
     * Reason codes for the various recovery triggers.
     */
    public static final int REASON_LAST_RESORT_WATCHDOG = 0;
    public static final int REASON_HAL_CRASH = 1;
    public static final int REASON_WIFICOND_CRASH = 2;

    private static final String[] REASON_STRINGS = {
            "Last Resort Watchdog", // REASON_LAST_RESORT_WATCHDOG
            "Hal Crash",            // REASON_HAL_CRASH
            "Wificond Crash"        // REASON_WIFICOND_CRASH
    };

    private final WifiController mWifiController;

    SelfRecovery(WifiController wifiController) {
        mWifiController = wifiController;
    }

    /**
     * Trigger recovery.
     *
     * This method does the following:
     * 1. Raises a wtf.
     * 2. Sends {@link WifiController#CMD_RESTART_WIFI} to {@link WifiController} to initiate the
     * stack restart.
     * @param reason One of the above |REASON_*| codes.
     */
    public void trigger(int reason) {
        if (reason < REASON_LAST_RESORT_WATCHDOG || reason > REASON_WIFICOND_CRASH) {
            Log.e(TAG, "Invalid trigger reason. Ignoring...");
            return;
        }
        Log.wtf(TAG, "Triggering recovery for reason: " + REASON_STRINGS[reason]);
        mWifiController.sendMessage(WifiController.CMD_RESTART_WIFI);
    }
}
