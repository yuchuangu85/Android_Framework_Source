/*
 * Copyright (C) 2010 The Android Open Source Project
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

import android.os.BatteryStatsManager;
import android.util.Log;

import java.util.concurrent.RejectedExecutionException;

/**
 * This class is used to track WifiState to update BatteryStats
 */
public class WifiStateTracker {
    private static final String TAG = "WifiStateTracker";

    public static final int INVALID = 0;
    public static final int SCAN_MODE = 1;
    public static final int DISCONNECTED = 2;
    public static final int CONNECTED = 3;
    public static final int SOFT_AP = 4;
    private int mWifiState;
    private BatteryStatsManager mBatteryStatsManager;

    public WifiStateTracker(BatteryStatsManager batteryStatsManager) {
        mWifiState = INVALID;
        mBatteryStatsManager = batteryStatsManager;
    }

    private void informWifiStateBatteryStats(int state) {
        try {
            mBatteryStatsManager.reportWifiState(state, null);
        } catch (RejectedExecutionException e) {
            Log.e(TAG, "Battery stats executor is being shutdown " + e.getMessage());
        }
    }

    /**
     * Inform the WifiState to this tracker to translate into the
     * WifiState corresponding to BatteryStatsManager.
     * @param state state corresponding to the ClientModeImpl state
     */
    public void updateState(int state) {
        int reportState = BatteryStatsManager.WIFI_STATE_OFF;
        if (state != mWifiState) {
            switch(state) {
                case SCAN_MODE:
                    reportState = BatteryStatsManager.WIFI_STATE_OFF_SCANNING;
                    break;
                case DISCONNECTED:
                    reportState = BatteryStatsManager.WIFI_STATE_ON_DISCONNECTED;
                    break;
                case CONNECTED:
                    reportState = BatteryStatsManager.WIFI_STATE_ON_CONNECTED_STA;
                    break;
                case SOFT_AP:
                    reportState = BatteryStatsManager.WIFI_STATE_SOFT_AP;
                    break;
                case INVALID:
                    mWifiState = INVALID;
                    /* Fall through */
                default:
                    return;
            }
            mWifiState = state;
            informWifiStateBatteryStats(reportState);
        }
        return;
    }
}
