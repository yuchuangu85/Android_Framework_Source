/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.net.module.util;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ContentResolver;
import android.content.Context;
import android.net.ConnectivitySettingsManager;
import android.provider.Settings;
import android.text.TextUtils;

/**
 * Collection of connectivity settings utilities.
 *
 * @hide
 */
public class ConnectivitySettingsUtils {
    public static final String TAG = ConnectivitySettingsUtils.class.getSimpleName();
    public static final int PRIVATE_DNS_MODE_OFF = 1;
    public static final int PRIVATE_DNS_MODE_OPPORTUNISTIC = 2;
    public static final int PRIVATE_DNS_MODE_PROVIDER_HOSTNAME = 3;

    public static final String PRIVATE_DNS_DEFAULT_MODE = "private_dns_default_mode";
    public static final String PRIVATE_DNS_MODE = "private_dns_mode";
    public static final String PRIVATE_DNS_MODE_OFF_STRING = "off";
    public static final String PRIVATE_DNS_MODE_OPPORTUNISTIC_STRING = "opportunistic";
    public static final String PRIVATE_DNS_MODE_PROVIDER_HOSTNAME_STRING = "hostname";
    public static final String PRIVATE_DNS_SPECIFIER = "private_dns_specifier";

    public static final String NETWORK_AVOID_BAD_WIFI = "network_avoid_bad_wifi";
    public static final String NETWORK_CARRIER_AWARE_AVOID_BAD_WIFI =
            "network_carrier_aware_avoid_bad_wifi";
    public static final String L4S_DEVELOPER_OPTION = "l4s_developer_option";

    /**
     * Get private DNS mode as string.
     *
     * @param mode One of the private DNS values.
     * @return A string of private DNS mode.
     */
    public static String getPrivateDnsModeAsString(int mode) {
        switch (mode) {
            case PRIVATE_DNS_MODE_OFF:
                return PRIVATE_DNS_MODE_OFF_STRING;
            case PRIVATE_DNS_MODE_OPPORTUNISTIC:
                return PRIVATE_DNS_MODE_OPPORTUNISTIC_STRING;
            case PRIVATE_DNS_MODE_PROVIDER_HOSTNAME:
                return PRIVATE_DNS_MODE_PROVIDER_HOSTNAME_STRING;
            default:
                throw new IllegalArgumentException("Invalid private dns mode: " + mode);
        }
    }

    private static int getPrivateDnsModeAsInt(String mode) {
        // If both PRIVATE_DNS_MODE and PRIVATE_DNS_DEFAULT_MODE are not set, choose
        // PRIVATE_DNS_MODE_OPPORTUNISTIC as default mode.
        if (TextUtils.isEmpty(mode))
            return PRIVATE_DNS_MODE_OPPORTUNISTIC;
        switch (mode) {
            case "off":
                return PRIVATE_DNS_MODE_OFF;
            case "hostname":
                return PRIVATE_DNS_MODE_PROVIDER_HOSTNAME;
            case "opportunistic":
                return PRIVATE_DNS_MODE_OPPORTUNISTIC;
            default:
                // b/260211513: adb shell settings put global private_dns_mode foo
                // can result in arbitrary strings - treat any unknown value as empty string.
                // throw new IllegalArgumentException("Invalid private dns mode: " + mode);
                return PRIVATE_DNS_MODE_OPPORTUNISTIC;
        }
    }

    /**
     * Generates a unique setting key for the "avoid bad Wi-Fi" feature,
     * specific to a given cellular subscription ID.
     * This key is typically used to store and retrieve a preference
     * that controls how the device manages Wi-Fi connectivity
     * in the context of a particular cellular carrier.
     *
     * @param subId The unique identifier of the cellular subscription.
     * @return A {@code String} representing the unique setting key.
     * The key is constructed by appending the {@code subId} to a
     * base constant string for carrier-aware "avoid bad Wi-Fi" settings,
     * separated by a forward slash.
     */
    public static String getAvoidBadWifiSettingKey(int subId) {
        return NETWORK_CARRIER_AWARE_AVOID_BAD_WIFI + "/" + subId;
    }

    /**
     * Get private DNS mode from settings.
     *
     * @param context The Context to query the private DNS mode from settings.
     * @return An integer of private DNS mode.
     */
    public static int getPrivateDnsMode(@NonNull Context context) {
        final ContentResolver cr = context.getContentResolver();
        String mode = Settings.Global.getString(cr, PRIVATE_DNS_MODE);
        if (TextUtils.isEmpty(mode)) mode = Settings.Global.getString(cr, PRIVATE_DNS_DEFAULT_MODE);
        return getPrivateDnsModeAsInt(mode);
    }

    /**
     * Set private DNS mode to settings.
     *
     * @param context The {@link Context} to set the private DNS mode.
     * @param mode The private dns mode. This should be one of the PRIVATE_DNS_MODE_* constants.
     */
    public static void setPrivateDnsMode(@NonNull Context context, int mode) {
        if (!(mode == PRIVATE_DNS_MODE_OFF
                || mode == PRIVATE_DNS_MODE_OPPORTUNISTIC
                || mode == PRIVATE_DNS_MODE_PROVIDER_HOSTNAME)) {
            throw new IllegalArgumentException("Invalid private dns mode: " + mode);
        }
        Settings.Global.putString(context.getContentResolver(), PRIVATE_DNS_MODE,
                getPrivateDnsModeAsString(mode));
    }

    /**
     * Get specific private dns provider name from {@link Settings}.
     *
     * @param context The {@link Context} to query the setting.
     * @return The specific private dns provider name, or null if no setting value.
     */
    @Nullable
    public static String getPrivateDnsHostname(@NonNull Context context) {
        return Settings.Global.getString(context.getContentResolver(), PRIVATE_DNS_SPECIFIER);
    }

    /**
     * Set specific private dns provider name to {@link Settings}.
     *
     * @param context The {@link Context} to set the setting.
     * @param specifier The specific private dns provider name.
     */
    public static void setPrivateDnsHostname(@NonNull Context context, @Nullable String specifier) {
        Settings.Global.putString(context.getContentResolver(), PRIVATE_DNS_SPECIFIER, specifier);
    }

    /**
     * Set legacy global avoid bad wifi to {@link Settings}.
     *
     * @param context The {@link Context} to set the setting.
     * @param setting The desired setting value.
     * "0": Don't avoid bad Wi-Fi.
     * "1": Avoid bad Wi-Fi.
     * {@code null}: Ask the user whether to switch away from bad Wi-Fi.
     * @deprecated Use {@link #setNetworkAvoidBadWifiSetting(Context, String)} instead.
     */
    @Deprecated
    public static void setNetworkLegacyGlobalAvoidBadWifiSetting(
            @NonNull Context context, @Nullable String setting) {
        Settings.Global.putString(context.getContentResolver(), NETWORK_AVOID_BAD_WIFI, setting);
    }

    /**
     * Get legacy global avoid bad wifi to {@link Settings}.
     *
     * @param context The {@link Context} to query the setting.
     * @return The current setting value, which can be "0", "1", or {@code null}.
     * Returns {@code null} if the setting is not found.
     * @deprecated Use {@link #getNetworkAvoidBadWifiSetting(Context)} instead.
     */
    @Deprecated
    @Nullable
    public static String getNetworkLegacyGlobalAvoidBadWifiSetting(@NonNull Context context) {
        return Settings.Global.getString(context.getContentResolver(), NETWORK_AVOID_BAD_WIFI);
    }

    /**
     * Set the carrier-aware avoid bad wifi to {@link Settings}.
     *
     * @param context The {@link Context} to set the setting.
     * @param subId The subscription ID
     * @param setting The desired setting string.
     * subId: The carrier subscription ID (integer).
     * value: "0" to not avoid bad Wi-Fi for this subscription, or "1" to avoid.
     * {@code null}: Ask the user whether to switch away from bad Wi-Fi.
     */
    public static void setNetworkAvoidBadWifiSetting(
                @NonNull Context context, int subId, @Nullable String setting) {
        Settings.Global.putString(
                context.getContentResolver(), getAvoidBadWifiSettingKey(subId), setting);
    }

    /**
     * Get the raw carrier-aware avoid bad wifi string from {@link Settings}.
     * @see #convertCarrierAwareSettingsStringToMap(String)
     *
     * @param context The {@link Context} to set the setting.
     * @param subId The subscription ID.
     * @return The current setting string, formatted as
     * "network_carrier_aware_avoid_bad_wifi/{subId}" or {@code null} if the setting is not set.
     */
    @Nullable
    public static String getNetworkAvoidBadWifiSetting(@NonNull Context context, int subId) {
        return Settings.Global.getString(
            context.getContentResolver(), getAvoidBadWifiSettingKey(subId));
    }

    /**
     * Retrieves the carrier-aware avoid bad wifi string from {@link Settings} and
     * converts it to the corresponding integer constant defined in
     * {@link ConnectivitySettingsManager}.
     *
     * The raw setting is a string, which is mapped to an integer constant as follows:
     * If the setting string is {@code null},
     *     it defaults to {@link ConnectivitySettingsManager#NETWORK_AVOID_BAD_WIFI_PROMPT}.
     * If the setting string equals {@code "1"},
     *     it maps to {@link ConnectivitySettingsManager#NETWORK_AVOID_BAD_WIFI_AVOID}.
     * Any other non-null string
     *     maps to {@link ConnectivitySettingsManager#NETWORK_AVOID_BAD_WIFI_IGNORE}.
     *
     * @param context The application {@link Context}.
     * @param subId The subscription ID for which to fetch the setting.
     * @return An integer constant from {@link ConnectivitySettingsManager} representing the
     *         effective "Avoid Bad Wifi" mode (PROMPT, AVOID, or IGNORE).
     */
    public static int getNetworkAvoidBadWifiIntegerSetting(@NonNull Context context, int subId) {
        final String setting = getNetworkAvoidBadWifiSetting(context, subId);
        if (setting == null) return ConnectivitySettingsManager.NETWORK_AVOID_BAD_WIFI_PROMPT;

        if ("1".equals(setting)) {
            return ConnectivitySettingsManager.NETWORK_AVOID_BAD_WIFI_AVOID;
        } else {
            return ConnectivitySettingsManager.NETWORK_AVOID_BAD_WIFI_IGNORE;
        }
    }

    /**
     * Get L4S global developer option from {@link Settings}.
     *
     * @param context The Context to query the L4S develop option from settings.
     * @return An integer constant from {@link ConnectivitySettingsManager} representing the
     * L4S develop option setting
     * - DISABLED: Disable L4S developer option.
     * - ENABLED: Enable L4S developer option.
     * - AUTOMATIC: Determined by the default.
     */
    public static int getL4sDeveloperOptionSetting(@NonNull Context context) {
        String setting = Settings.Global.getString(
                context.getContentResolver(),
                L4S_DEVELOPER_OPTION);
        if (setting == null) {
            return ConnectivitySettingsManager.L4S_DEVELOPER_OPTION_AUTOMATIC;
        }

        if ("1".equals(setting)) {
            return ConnectivitySettingsManager.L4S_DEVELOPER_OPTION_ENABLED;
        } else {
            return ConnectivitySettingsManager.L4S_DEVELOPER_OPTION_DISABLED;
        }
    }

    /**
     * Set L4S developer option to {@link Settings}.
     *
     * @param context The Context to set the L4S developer option from settings.
     * @param setting The desired setting string.
     * - "0": Disable L4S developer option.
     * - "1": Enable L4S developer option.
     * - {@code null}: Determined by the default.
     */
    public static void setL4sDeveloperOptionSetting(@NonNull Context context, int setting) {
        final String config;
        switch (setting) {
            case ConnectivitySettingsManager.L4S_DEVELOPER_OPTION_ENABLED:
                config = "1";
                break;
            case ConnectivitySettingsManager.L4S_DEVELOPER_OPTION_DISABLED:
                config = "0";
                break;
            case ConnectivitySettingsManager.L4S_DEVELOPER_OPTION_AUTOMATIC:
                config = null;
                break;
            default:
                throw new IllegalArgumentException("Invalid L4S status: " + setting);
        }

        // TODO: Enable key-value pair removal by implementing the delete()
        // method in MockContentProvider.
        // Afterwards, refactor this section to use resolver.delete().
        Settings.Global.putString(
                context.getContentResolver(),
                L4S_DEVELOPER_OPTION,
                config);
    }
}
