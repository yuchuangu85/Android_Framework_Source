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

import android.annotation.IntDef;
import android.content.Context;
import android.util.Log;

import com.android.wifi.resources.R;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.concurrent.TimeUnit;

/**
 * This class is used to recover the wifi stack from a fatal failure. The recovery mechanism
 * involves triggering a stack restart (essentially simulating an airplane mode toggle) using
 * {@link ActiveModeWarden}.
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
    public static final int REASON_WIFINATIVE_FAILURE = 1;
    public static final int REASON_STA_IFACE_DOWN = 2;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"REASON_"}, value = {
            REASON_LAST_RESORT_WATCHDOG,
            REASON_WIFINATIVE_FAILURE,
            REASON_STA_IFACE_DOWN})
    public @interface RecoveryReason {}

    protected static final String[] REASON_STRINGS = {
            "Last Resort Watchdog",  // REASON_LAST_RESORT_WATCHDOG
            "WifiNative Failure",    // REASON_WIFINATIVE_FAILURE
            "Sta Interface Down"     // REASON_STA_IFACE_DOWN
    };

    private final Context mContext;
    private final ActiveModeWarden mActiveModeWarden;
    private final Clock mClock;
    // Time since boot (in millis) that restart occurred
    private final LinkedList<Long> mPastRestartTimes;
    public SelfRecovery(Context context, ActiveModeWarden activeModeWarden, Clock clock) {
        mContext = context;
        mActiveModeWarden = activeModeWarden;
        mClock = clock;
        mPastRestartTimes = new LinkedList<>();
    }

    /**
     * Trigger recovery.
     *
     * This method does the following:
     * 1. Checks reason code used to trigger recovery
     * 2. Checks for sta iface down triggers and disables wifi by sending {@link
     * ActiveModeWarden#recoveryDisableWifi()} to {@link ActiveModeWarden} to disable wifi.
     * 3. Throttles restart calls for underlying native failures
     * 4. Sends {@link ActiveModeWarden#recoveryRestartWifi(int)} to {@link ActiveModeWarden} to
     * initiate the stack restart.
     * @param reason One of the above |REASON_*| codes.
     */
    public void trigger(@RecoveryReason int reason) {
        if (!(reason == REASON_LAST_RESORT_WATCHDOG || reason == REASON_WIFINATIVE_FAILURE
                  || reason == REASON_STA_IFACE_DOWN)) {
            Log.e(TAG, "Invalid trigger reason. Ignoring...");
            return;
        }
        if (reason == REASON_STA_IFACE_DOWN) {
            Log.e(TAG, "STA interface down, disable wifi");
            mActiveModeWarden.recoveryDisableWifi();
            return;
        }

        Log.e(TAG, "Triggering recovery for reason: " + REASON_STRINGS[reason]);
        if (reason == REASON_WIFINATIVE_FAILURE) {
            int maxRecoveriesPerHour = mContext.getResources().getInteger(
                    R.integer.config_wifiMaxNativeFailureSelfRecoveryPerHour);
            if (maxRecoveriesPerHour == 0) {
                Log.e(TAG, "Recovery disabled. Disabling wifi");
                mActiveModeWarden.recoveryDisableWifi();
                return;
            }
            trimPastRestartTimes();
            if (mPastRestartTimes.size() >= maxRecoveriesPerHour) {
                Log.e(TAG, "Already restarted wifi " + maxRecoveriesPerHour + " times in"
                        + " last 1 hour. Disabling wifi");
                mActiveModeWarden.recoveryDisableWifi();
                return;
            }
            mPastRestartTimes.add(mClock.getElapsedSinceBootMillis());
        }
        mActiveModeWarden.recoveryRestartWifi(reason);
    }

    /**
     * Process the mPastRestartTimes list, removing elements outside the max restarts time window
     */
    private void trimPastRestartTimes() {
        Iterator<Long> iter = mPastRestartTimes.iterator();
        long now = mClock.getElapsedSinceBootMillis();
        while (iter.hasNext()) {
            Long restartTimeMillis = iter.next();
            if (now - restartTimeMillis > TimeUnit.HOURS.toMillis(1)) {
                iter.remove();
            } else {
                break;
            }
        }
    }
}
