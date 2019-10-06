/*
 * Copyright 2017 The Android Open Source Project
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

package com.android.internal.telephony;

import android.app.AlarmManager;
import android.app.timedetector.TimeDetector;
import android.app.timedetector.TimeSignal;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.TimestampedValue;

/**
 * An interface to various time / time zone detection behaviors that should be centralized into a
 * new service.
 */
// Non-final to allow mocking.
public class NewTimeServiceHelper {

    /**
     * Callback interface for automatic detection enable/disable changes.
     */
    public interface Listener {
        /**
         * Automatic time zone detection has been enabled or disabled.
         */
        void onTimeZoneDetectionChange(boolean enabled);
    }

    private static final String TIMEZONE_PROPERTY = "persist.sys.timezone";

    private final Context mContext;
    private final ContentResolver mCr;
    private final TimeDetector mTimeDetector;

    private Listener mListener;

    /** Creates a TimeServiceHelper */
    public NewTimeServiceHelper(Context context) {
        mContext = context;
        mCr = context.getContentResolver();
        mTimeDetector = context.getSystemService(TimeDetector.class);
    }

    /**
     * Sets a listener that will be called when the automatic time / time zone detection setting
     * changes.
     */
    public void setListener(Listener listener) {
        if (listener == null) {
            throw new NullPointerException("listener==null");
        }
        if (mListener != null) {
            throw new IllegalStateException("listener already set");
        }
        this.mListener = listener;
        mCr.registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.AUTO_TIME_ZONE), true,
                new ContentObserver(new Handler()) {
                    public void onChange(boolean selfChange) {
                        listener.onTimeZoneDetectionChange(isTimeZoneDetectionEnabled());
                    }
                });
    }

    /**
     * Returns the same value as {@link System#currentTimeMillis()}.
     */
    public long currentTimeMillis() {
        return System.currentTimeMillis();
    }

    /**
     * Returns the same value as {@link SystemClock#elapsedRealtime()}.
     */
    public long elapsedRealtime() {
        return SystemClock.elapsedRealtime();
    }

    /**
     * Returns true if the device has an explicit time zone set.
     */
    public boolean isTimeZoneSettingInitialized() {
        return isTimeZoneSettingInitializedStatic();

    }

    /**
     * Returns true if automatic time zone detection is enabled in settings.
     */
    public boolean isTimeZoneDetectionEnabled() {
        try {
            return Settings.Global.getInt(mCr, Settings.Global.AUTO_TIME_ZONE) > 0;
        } catch (Settings.SettingNotFoundException snfe) {
            return true;
        }
    }

    /**
     * Set the device time zone and send out a sticky broadcast so the system can
     * determine if the timezone was set by the carrier.
     *
     * @param zoneId timezone set by carrier
     */
    public void setDeviceTimeZone(String zoneId) {
        setDeviceTimeZoneStatic(mContext, zoneId);
    }

    /**
     * Suggest the time to the {@link TimeDetector}.
     *
     * @param signalTimeMillis the signal time as received from the network
     */
    public void suggestDeviceTime(TimestampedValue<Long> signalTimeMillis) {
        TimeSignal timeSignal = new TimeSignal(TimeSignal.SOURCE_ID_NITZ, signalTimeMillis);
        mTimeDetector.suggestTime(timeSignal);
    }

    /**
     * Static implementation of isTimeZoneSettingInitialized() for use from {@link MccTable}. This
     * is a hack to deflake TelephonyTests when running on a device with a real SIM: in that
     * situation real service events may come in while a TelephonyTest is running, leading to flakes
     * as the real / fake instance of TimeServiceHelper is swapped in and out from
     * {@link TelephonyComponentFactory}.
     */
    static boolean isTimeZoneSettingInitializedStatic() {
        // timezone.equals("GMT") will be true and only true if the timezone was
        // set to a default value by the system server (when starting, system server
        // sets the persist.sys.timezone to "GMT" if it's not set). "GMT" is not used by
        // any code that sets it explicitly (in case where something sets GMT explicitly,
        // "Etc/GMT" Olsen ID would be used).
        // TODO(b/64056758): Remove "timezone.equals("GMT")" hack when there's a
        // better way of telling if the value has been defaulted.

        String timeZoneId = SystemProperties.get(TIMEZONE_PROPERTY);
        return timeZoneId != null && timeZoneId.length() > 0 && !timeZoneId.equals("GMT");
    }

    /**
     * Static method for use by MccTable. See {@link #isTimeZoneSettingInitializedStatic()} for
     * explanation.
     */
    static void setDeviceTimeZoneStatic(Context context, String zoneId) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        alarmManager.setTimeZone(zoneId);
        Intent intent = new Intent(TelephonyIntents.ACTION_NETWORK_SET_TIMEZONE);
        intent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING);
        intent.putExtra("time-zone", zoneId);
        context.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
    }
}
