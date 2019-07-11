/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.setupwizardlib.util;

import android.content.Context;
import android.content.Intent;
import android.os.Build.VERSION;
import android.os.Build.VERSION_CODES;
import android.provider.Settings;

public class WizardManagerHelper {

    private static final String ACTION_NEXT = "com.android.wizard.NEXT";

    private static final String EXTRA_SCRIPT_URI = "scriptUri";
    private static final String EXTRA_ACTION_ID = "actionId";
    private static final String EXTRA_RESULT_CODE = "com.android.setupwizard.ResultCode";
    private static final String EXTRA_IS_FIRST_RUN = "firstRun";

    public static final String EXTRA_THEME = "theme";
    public static final String EXTRA_USE_IMMERSIVE_MODE = "useImmersiveMode";

    public static final String SETTINGS_GLOBAL_DEVICE_PROVISIONED = "device_provisioned";
    public static final String SETTINGS_SECURE_USER_SETUP_COMPLETE = "user_setup_complete";

    public static final String THEME_HOLO = "holo";
    public static final String THEME_HOLO_LIGHT = "holo_light";
    public static final String THEME_MATERIAL = "material";
    public static final String THEME_MATERIAL_LIGHT = "material_light";
    public static final String THEME_MATERIAL_BLUE = "material_blue";
    public static final String THEME_MATERIAL_BLUE_LIGHT = "material_blue_light";

    /**
     * Get an intent that will invoke the next step of setup wizard.
     *
     * @param originalIntent The original intent that was used to start the step, usually via
     *                       Activity.getIntent().
     * @param resultCode The result code of the step. See {@link ResultCodes}.
     * @return A new intent that can be used with startActivityForResult() to start the next step of
     *         the setup flow.
     */
    public static Intent getNextIntent(Intent originalIntent, int resultCode) {
        return getNextIntent(originalIntent, resultCode, null);
    }

    /**
     * Get an intent that will invoke the next step of setup wizard.
     *
     * @param originalIntent The original intent that was used to start the step, usually via
     *                       Activity.getIntent().
     * @param resultCode The result code of the step. See {@link ResultCodes}.
     * @param data An intent containing extra result data.
     * @return A new intent that can be used with startActivityForResult() to start the next step of
     *         the setup flow.
     */
    public static Intent getNextIntent(Intent originalIntent, int resultCode, Intent data) {
        Intent intent = new Intent(ACTION_NEXT);
        intent.putExtra(EXTRA_SCRIPT_URI, originalIntent.getStringExtra(EXTRA_SCRIPT_URI));
        intent.putExtra(EXTRA_ACTION_ID, originalIntent.getStringExtra(EXTRA_ACTION_ID));
        intent.putExtra(EXTRA_RESULT_CODE, resultCode);
        if (data != null && data.getExtras() != null) {
            intent.putExtras(data.getExtras());
        }
        intent.putExtra(EXTRA_THEME, originalIntent.getStringExtra(EXTRA_THEME));
        return intent;
    }

    /**
     * Check whether an intent is intended to be used within the setup wizard flow.
     *
     * @param intent The intent to be checked, usually from Activity.getIntent().
     * @return true if the intent passed in was intended to be used with setup wizard.
     */
    public static boolean isSetupWizardIntent(Intent intent) {
        return intent.getBooleanExtra(EXTRA_IS_FIRST_RUN, false);
    }

    /**
     * Checks whether the current user has completed Setup Wizard. This is true if the current user
     * has gone through Setup Wizard. The current user may or may not be the device owner and the
     * device owner may have already completed setup wizard.
     *
     * @param context The context to retrieve the settings.
     * @return true if the current user has completed Setup Wizard.
     * @see #isDeviceProvisioned(android.content.Context)
     */
    public static boolean isUserSetupComplete(Context context) {
        if (VERSION.SDK_INT >= VERSION_CODES.ICE_CREAM_SANDWICH) {
            return Settings.Secure.getInt(context.getContentResolver(),
                    SETTINGS_SECURE_USER_SETUP_COMPLETE, 0) == 1;
        } else {
            // For versions below JB MR1, there are no user profiles. Just return the global device
            // provisioned state.
            return Settings.Secure.getInt(context.getContentResolver(),
                    SETTINGS_GLOBAL_DEVICE_PROVISIONED, 0) == 1;
        }
    }

    /**
     * Checks whether the device is provisioned. This means that the device has gone through Setup
     * Wizard at least once. Note that the user can still be in Setup Wizard even if this is true,
     * for a secondary user profile triggered through Settings > Add account.
     *
     * @param context The context to retrieve the settings.
     * @return true if the device is provisioned.
     * @see #isUserSetupComplete(android.content.Context)
     */
    public static boolean isDeviceProvisioned(Context context) {
        if (VERSION.SDK_INT >= VERSION_CODES.JELLY_BEAN_MR1) {
            return Settings.Global.getInt(context.getContentResolver(),
                    SETTINGS_GLOBAL_DEVICE_PROVISIONED, 0) == 1;
        } else {
            return Settings.Secure.getInt(context.getContentResolver(),
                    SETTINGS_GLOBAL_DEVICE_PROVISIONED, 0) == 1;
        }
    }

    /**
     * Checks the intent whether the extra indicates that the light theme should be used or not. If
     * the theme is not specified in the intent, or the theme specified is unknown, the value def
     * will be returned.
     *
     * @param intent The intent used to start the activity, which the theme extra will be read from.
     * @param def The default value if the theme is not specified.
     * @return True if the activity started by the given intent should use light theme.
     */
    public static boolean isLightTheme(Intent intent, boolean def) {
        final String theme = intent.getStringExtra(EXTRA_THEME);
        if (THEME_HOLO_LIGHT.equals(theme) || THEME_MATERIAL_LIGHT.equals(theme)
                || THEME_MATERIAL_BLUE_LIGHT.equals(theme)) {
            return true;
        } else if (THEME_HOLO.equals(theme) || THEME_MATERIAL.equals(theme)
                || THEME_MATERIAL_BLUE.equals(theme)) {
            return false;
        } else {
            return def;
        }
    }
}
