/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.settingslib.fuelgauge;

import static com.android.settingslib.fuelgauge.BatterySaverLogging.ACTION_SAVER_STATE_MANUAL_UPDATE;
import static com.android.settingslib.fuelgauge.BatterySaverLogging.EXTRA_POWER_SAVE_MODE_MANUAL_ENABLED;
import static com.android.settingslib.fuelgauge.BatterySaverLogging.EXTRA_POWER_SAVE_MODE_MANUAL_ENABLED_REASON;
import static com.android.settingslib.fuelgauge.BatterySaverLogging.SaverManualEnabledReason;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.UserHandle;
import android.provider.Settings;
import android.provider.Settings.Global;
import android.provider.Settings.Secure;
import android.util.KeyValueListParser;
import android.util.Log;
import android.util.Slog;

/**
 * Utilities related to battery saver.
 */
public class BatterySaverUtils {

    private static final String TAG = "BatterySaverUtils";
    /**
     * When set to "true" the notification will be a generic confirm message instead of asking the
     * user if they want to turn on battery saver. If set to false the dialog will specifically
     * talk about battery saver without giving the option of turning it on. The only button visible
     * will be a generic confirmation button to acknowledge the dialog.
     */
    public static final String EXTRA_CONFIRM_TEXT_ONLY = "extra_confirm_only";
    /**
     * Ignored if {@link #EXTRA_CONFIRM_TEXT_ONLY} is "false". Can be set to any of the values in
     * {@link PowerManager.AutoPowerSaveModeTriggers}. If set the dialog will set the power
     * save mode trigger to the specified value after the user acknowledges the trigger.
     */
    public static final String EXTRA_POWER_SAVE_MODE_TRIGGER = "extra_power_save_mode_trigger";
    /**
     * Ignored if {@link #EXTRA_CONFIRM_TEXT_ONLY} is "false". can be set to any value between
     * 0-100 that will be used if {@link #EXTRA_POWER_SAVE_MODE_TRIGGER} is
     * {@link PowerManager#POWER_SAVE_MODE_TRIGGER_PERCENTAGE}.
     */
    public static final String EXTRA_POWER_SAVE_MODE_TRIGGER_LEVEL =
            "extra_power_save_mode_trigger_level";

    /** Battery saver schedule keys. */
    public static final String KEY_NO_SCHEDULE = "key_battery_saver_no_schedule";
    public static final String KEY_PERCENTAGE = "key_battery_saver_percentage";

    private BatterySaverUtils() {
    }

    private static final boolean DEBUG = false;

    private static final String SYSUI_PACKAGE = "com.android.systemui";

    /** Broadcast action for SystemUI to show the battery saver confirmation dialog. */
    public static final String ACTION_SHOW_START_SAVER_CONFIRMATION = "PNW.startSaverConfirmation";

    /**
     * Broadcast action for SystemUI to show the notification that suggests turning on
     * automatic battery saver.
     */
    public static final String ACTION_SHOW_AUTO_SAVER_SUGGESTION
            = "PNW.autoSaverSuggestion";

    private static class Parameters {
        private final Context mContext;

        /**
         * We show the auto battery saver suggestion notification when the user manually enables
         * battery saver for the START_NTH time through the END_NTH time.
         * (We won't show it for END_NTH + 1 time and after.)
         */
        private static final int AUTO_SAVER_SUGGESTION_START_NTH = 4;
        private static final int AUTO_SAVER_SUGGESTION_END_NTH = 8;

        public final int startNth;
        public final int endNth;

        public Parameters(Context context) {
            mContext = context;

            final String newValue = Global.getString(mContext.getContentResolver(),
                    Global.LOW_POWER_MODE_SUGGESTION_PARAMS);
            final KeyValueListParser parser = new KeyValueListParser(',');
            try {
                parser.setString(newValue);
            } catch (IllegalArgumentException e) {
                Slog.wtf(TAG, "Bad constants: " + newValue);
            }
            startNth = parser.getInt("start_nth", AUTO_SAVER_SUGGESTION_START_NTH);
            endNth = parser.getInt("end_nth", AUTO_SAVER_SUGGESTION_END_NTH);
        }
    }

    /**
     * Enable / disable battery saver by user request.
     * - If it's the first time and needFirstTimeWarning, show the first time dialog.
     * - If it's 4th time through 8th time, show the schedule suggestion notification.
     *
     * @param enable true to enable battery saver.
     * @return true if the request succeeded.
     */
    public static synchronized boolean setPowerSaveMode(Context context,
            boolean enable, boolean needFirstTimeWarning, @SaverManualEnabledReason int reason) {
        if (DEBUG) {
            Log.d(TAG, "Battery saver turning " + (enable ? "ON" : "OFF") + ", reason: " + reason);
        }
        final ContentResolver cr = context.getContentResolver();

        final Bundle confirmationExtras = new Bundle(1);
        confirmationExtras.putBoolean(EXTRA_CONFIRM_TEXT_ONLY, false);
        if (enable && needFirstTimeWarning
                && maybeShowBatterySaverConfirmation(context, confirmationExtras)) {
            return false;
        }
        if (enable && !needFirstTimeWarning) {
            setBatterySaverConfirmationAcknowledged(context);
        }

        if (context.getSystemService(PowerManager.class).setPowerSaveModeEnabled(enable)) {
            if (enable) {
                final int count =
                        Secure.getInt(cr, Secure.LOW_POWER_MANUAL_ACTIVATION_COUNT, 0) + 1;
                Secure.putInt(cr, Secure.LOW_POWER_MANUAL_ACTIVATION_COUNT, count);

                final Parameters parameters = new Parameters(context);

                if ((count >= parameters.startNth)
                        && (count <= parameters.endNth)
                        && Global.getInt(cr, Global.LOW_POWER_MODE_TRIGGER_LEVEL, 0) == 0
                        && Secure.getInt(cr,
                        Secure.SUPPRESS_AUTO_BATTERY_SAVER_SUGGESTION, 0) == 0) {
                    sendSystemUiBroadcast(context, ACTION_SHOW_AUTO_SAVER_SUGGESTION,
                            confirmationExtras);
                }
            }
            recordBatterySaverEnabledReason(context, enable, reason);
            return true;
        }
        return false;
    }

    /**
     * Shows the battery saver confirmation warning if it hasn't been acknowledged by the user in
     * the past before. Various extras can be provided that will change the behavior of this
     * notification as well as the ui for it.
     *
     * @param context A valid context
     * @param extras  Any extras to include in the intent to trigger this confirmation that will
     *                help the system disambiguate what to show/do
     * @return True if it showed the notification because it has not been previously acknowledged.
     * @see #EXTRA_CONFIRM_TEXT_ONLY
     * @see #EXTRA_POWER_SAVE_MODE_TRIGGER
     * @see #EXTRA_POWER_SAVE_MODE_TRIGGER_LEVEL
     */
    public static boolean maybeShowBatterySaverConfirmation(Context context, Bundle extras) {
        if (Secure.getInt(context.getContentResolver(),
                Secure.LOW_POWER_WARNING_ACKNOWLEDGED, 0) != 0
                && Secure.getInt(context.getContentResolver(),
                Secure.EXTRA_LOW_POWER_WARNING_ACKNOWLEDGED, 0) != 0) {
            // Already shown.
            return false;
        }
        sendSystemUiBroadcast(context, ACTION_SHOW_START_SAVER_CONFIRMATION, extras);
        return true;
    }

    private static void recordBatterySaverEnabledReason(Context context, boolean enable,
            @SaverManualEnabledReason int reason) {
        final Bundle enabledReasonExtras = new Bundle(2);
        enabledReasonExtras.putInt(EXTRA_POWER_SAVE_MODE_MANUAL_ENABLED_REASON, reason);
        enabledReasonExtras.putBoolean(EXTRA_POWER_SAVE_MODE_MANUAL_ENABLED, enable);
        sendSystemUiBroadcast(context, ACTION_SAVER_STATE_MANUAL_UPDATE, enabledReasonExtras);
    }

    private static void sendSystemUiBroadcast(Context context, String action, Bundle extras) {
        final Intent intent = new Intent(action);
        intent.setFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        intent.setPackage(SYSUI_PACKAGE);
        intent.putExtras(extras);
        context.sendBroadcast(intent);
    }

    private static void setBatterySaverConfirmationAcknowledged(Context context) {
        Secure.putIntForUser(context.getContentResolver(),
                Secure.LOW_POWER_WARNING_ACKNOWLEDGED, 1, UserHandle.USER_CURRENT);
        Secure.putIntForUser(context.getContentResolver(),
                Secure.EXTRA_LOW_POWER_WARNING_ACKNOWLEDGED, 1, UserHandle.USER_CURRENT);
    }

    /**
     * Don't show the automatic battery suggestion notification in the future.
     */
    public static void suppressAutoBatterySaver(Context context) {
        Secure.putInt(context.getContentResolver(),
                Secure.SUPPRESS_AUTO_BATTERY_SAVER_SUGGESTION, 1);
    }

    /**
     * Set the automatic battery saver trigger level to {@code level}.
     */
    public static void setAutoBatterySaverTriggerLevel(Context context, int level) {
        if (level > 0) {
            suppressAutoBatterySaver(context);
        }
        Global.putInt(context.getContentResolver(), Global.LOW_POWER_MODE_TRIGGER_LEVEL, level);
    }

    /**
     * Set the automatic battery saver trigger level to {@code level}, but only when
     * automatic battery saver isn't enabled yet.
     */
    public static void ensureAutoBatterySaver(Context context, int level) {
        if (Global.getInt(context.getContentResolver(), Global.LOW_POWER_MODE_TRIGGER_LEVEL, 0)
                == 0) {
            setAutoBatterySaverTriggerLevel(context, level);
        }
    }

    /**
     * Reverts battery saver schedule mode to none if routine mode is selected.
     *
     * @param context a valid context
     */
    public static void revertScheduleToNoneIfNeeded(Context context) {
        ContentResolver resolver = context.getContentResolver();
        final int currentMode = Global.getInt(resolver, Global.AUTOMATIC_POWER_SAVE_MODE,
                PowerManager.POWER_SAVE_MODE_TRIGGER_PERCENTAGE);
        if (currentMode == PowerManager.POWER_SAVE_MODE_TRIGGER_DYNAMIC) {
            Global.putInt(resolver, Global.LOW_POWER_MODE_TRIGGER_LEVEL, 0);
            Global.putInt(resolver, Global.AUTOMATIC_POWER_SAVE_MODE,
                    PowerManager.POWER_SAVE_MODE_TRIGGER_PERCENTAGE);
        }
    }

    /**
     * Gets battery saver schedule mode.
     *
     * @param context a valid context
     * @return battery saver schedule key
     */
    public static String getBatterySaverScheduleKey(Context context) {
        final ContentResolver resolver = context.getContentResolver();
        final int mode = Settings.Global.getInt(resolver, Global.AUTOMATIC_POWER_SAVE_MODE,
                PowerManager.POWER_SAVE_MODE_TRIGGER_PERCENTAGE);
        if (mode == PowerManager.POWER_SAVE_MODE_TRIGGER_PERCENTAGE) {
            final int threshold =
                    Settings.Global.getInt(resolver, Global.LOW_POWER_MODE_TRIGGER_LEVEL, 0);
            return threshold <= 0 ? KEY_NO_SCHEDULE : KEY_PERCENTAGE;
        }
        revertScheduleToNoneIfNeeded(context);
        return KEY_NO_SCHEDULE;
    }

    /**
     * Sets battery saver schedule mode.
     *
     * @param context      a valid context
     * @param scheduleKey  {@link #KEY_NO_SCHEDULE} and {@link #KEY_PERCENTAGE}
     * @param triggerLevel for automatic battery saver trigger level
     */
    public static void setBatterySaverScheduleMode(Context context, String scheduleKey,
            int triggerLevel) {
        final ContentResolver resolver = context.getContentResolver();
        switch (scheduleKey) {
            case KEY_NO_SCHEDULE:
                Settings.Global.putInt(resolver, Global.AUTOMATIC_POWER_SAVE_MODE,
                        PowerManager.POWER_SAVE_MODE_TRIGGER_PERCENTAGE);
                Settings.Global.putInt(resolver, Settings.Global.LOW_POWER_MODE_TRIGGER_LEVEL, 0);
                break;
            case KEY_PERCENTAGE:
                Settings.Global.putInt(resolver, Global.AUTOMATIC_POWER_SAVE_MODE,
                        PowerManager.POWER_SAVE_MODE_TRIGGER_PERCENTAGE);
                Settings.Global.putInt(resolver,
                        Settings.Global.LOW_POWER_MODE_TRIGGER_LEVEL, triggerLevel);
                break;
            default:
                throw new IllegalStateException("Not a valid schedule key");
        }
    }
}
