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

import java.util.Iterator;
import java.util.LinkedList;

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
    public static final long MAX_RESTARTS_IN_TIME_WINDOW = 2; // 2 restarts per hour
    public static final long MAX_RESTARTS_TIME_WINDOW_MILLIS = 60 * 60 * 1000; // 1 hour
    private static final String[] REASON_STRINGS = {
            "Last Resort Watchdog", // REASON_LAST_RESORT_WATCHDOG
            "Hal Crash",            // REASON_HAL_CRASH
            "Wificond Crash"        // REASON_WIFICOND_CRASH
    };

    private final WifiController mWifiController;
    private final Clock mClock;
    // Time since boot (in millis) that restart occurred
    private final LinkedList<Long> mPastRestartTimes;
    public SelfRecovery(WifiController wifiController, Clock clock) {
        mWifiController = wifiController;
        mClock = clock;
        mPastRestartTimes = new LinkedList<Long>();
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
        if (!(reason == REASON_LAST_RESORT_WATCHDOG || reason == REASON_HAL_CRASH
                || reason == REASON_WIFICOND_CRASH)) {
            Log.e(TAG, "Invalid trigger reason. Ignoring...");
            return;
        }
        Log.e(TAG, "Triggering recovery for reason: " + REASON_STRINGS[reason]);
        if (reason == REASON_WIFICOND_CRASH || reason == REASON_HAL_CRASH) {
            trimPastRestartTimes();
            // Ensure there haven't been too many restarts within MAX_RESTARTS_TIME_WINDOW
            if (mPastRestartTimes.size() >= MAX_RESTARTS_IN_TIME_WINDOW) {
                Log.e(TAG, "Already restarted wifi (" + MAX_RESTARTS_IN_TIME_WINDOW + ") times in"
                        + " last (" + MAX_RESTARTS_TIME_WINDOW_MILLIS + "ms ). Ignoring...");
                return;
            }
            mPastRestartTimes.add(mClock.getElapsedSinceBootMillis());
        }
        mWifiController.sendMessage(WifiController.CMD_RESTART_WIFI);
    }

    /**
     * Process the mPastRestartTimes list, removing elements outside the max restarts time window
     */
    private void trimPastRestartTimes() {
        Iterator<Long> iter = mPastRestartTimes.iterator();
        long now = mClock.getElapsedSinceBootMillis();
        while (iter.hasNext()) {
            Long restartTimeMillis = iter.next();
            if (now - restartTimeMillis > MAX_RESTARTS_TIME_WINDOW_MILLIS) {
                iter.remove();
            } else {
                break;
            }
        }
    }
}
