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

import android.annotation.NonNull;
import android.content.Context;
import android.content.IntentFilter;
import android.net.MacAddress;
import android.net.util.MacAddressUtils;
import android.net.wifi.SoftApConfiguration;
import android.os.Handler;
import android.os.Process;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.wifi.util.ApConfigUtil;
import com.android.wifi.resources.R;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Random;

import javax.annotation.Nullable;

/**
 * Provides API for reading/writing soft access point configuration.
 */
public class WifiApConfigStore {

    // Intent when user has interacted with the softap settings change notification
    public static final String ACTION_HOTSPOT_CONFIG_USER_TAPPED_CONTENT =
            "com.android.server.wifi.WifiApConfigStoreUtil.HOTSPOT_CONFIG_USER_TAPPED_CONTENT";

    private static final String TAG = "WifiApConfigStore";

    private static final int RAND_SSID_INT_MIN = 1000;
    private static final int RAND_SSID_INT_MAX = 9999;

    @VisibleForTesting
    static final int SSID_MIN_LEN = 1;
    @VisibleForTesting
    static final int SSID_MAX_LEN = 32;
    @VisibleForTesting
    static final int PSK_MIN_LEN = 8;
    @VisibleForTesting
    static final int PSK_MAX_LEN = 63;

    private SoftApConfiguration mPersistentWifiApConfig = null;

    private final Context mContext;
    private final Handler mHandler;
    private final WifiMetrics mWifiMetrics;
    private final BackupManagerProxy mBackupManagerProxy;
    private final MacAddressUtil mMacAddressUtil;
    private final WifiConfigManager mWifiConfigManager;
    private final ActiveModeWarden mActiveModeWarden;
    private boolean mHasNewDataToSerialize = false;

    /**
     * Module to interact with the wifi config store.
     */
    private class SoftApStoreDataSource implements SoftApStoreData.DataSource {

        public SoftApConfiguration toSerialize() {
            mHasNewDataToSerialize = false;
            return mPersistentWifiApConfig;
        }

        public void fromDeserialized(SoftApConfiguration config) {
            mPersistentWifiApConfig = new SoftApConfiguration.Builder(config).build();
        }

        public void reset() {
            mPersistentWifiApConfig = null;
        }

        public boolean hasNewDataToSerialize() {
            return mHasNewDataToSerialize;
        }
    }

    WifiApConfigStore(Context context,
            WifiInjector wifiInjector,
            Handler handler,
            BackupManagerProxy backupManagerProxy,
            WifiConfigStore wifiConfigStore,
            WifiConfigManager wifiConfigManager,
            ActiveModeWarden activeModeWarden,
            WifiMetrics wifiMetrics) {
        mContext = context;
        mHandler = handler;
        mBackupManagerProxy = backupManagerProxy;
        mWifiConfigManager = wifiConfigManager;
        mActiveModeWarden = activeModeWarden;
        mWifiMetrics = wifiMetrics;

        // Register store data listener
        wifiConfigStore.registerStoreData(
                wifiInjector.makeSoftApStoreData(new SoftApStoreDataSource()));

        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_HOTSPOT_CONFIG_USER_TAPPED_CONTENT);
        mMacAddressUtil = wifiInjector.getMacAddressUtil();
    }

    /**
     * Return the current soft access point configuration.
     */
    public synchronized SoftApConfiguration getApConfiguration() {
        if (mPersistentWifiApConfig == null) {
            /* Use default configuration. */
            Log.d(TAG, "Fallback to use default AP configuration");
            persistConfigAndTriggerBackupManagerProxy(getDefaultApConfiguration());
        }
        SoftApConfiguration sanitizedPersistentconfig =
                sanitizePersistentApConfig(mPersistentWifiApConfig);
        if (mPersistentWifiApConfig != sanitizedPersistentconfig) {
            Log.d(TAG, "persisted config was converted, need to resave it");
            persistConfigAndTriggerBackupManagerProxy(sanitizedPersistentconfig);
        }
        return mPersistentWifiApConfig;
    }

    /**
     * Update the current soft access point configuration.
     * Restore to default AP configuration if null is provided.
     * This can be invoked under context of binder threads (WifiManager.setWifiApConfiguration)
     * and the main Wifi thread (CMD_START_AP).
     */
    public synchronized void setApConfiguration(SoftApConfiguration config) {
        if (config == null) {
            config = getDefaultApConfiguration();
        } else {
            config = sanitizePersistentApConfig(config);
        }
        persistConfigAndTriggerBackupManagerProxy(config);
    }

    /**
     * Returns SoftApConfiguration in which some parameters might be reset to supported default
     * config since it depends on UI or HW.
     *
     * MaxNumberOfClients and isClientControlByUserEnabled will need HAL support client force
     * disconnect, and Band setting (5g/6g) need HW support.
     *
     * HiddenSsid, Channel, ShutdownTimeoutMillis and AutoShutdownEnabled are features
     * which need UI(Setting) support.
     *
     * SAE/SAE-Transition need hardware support, reset to secured WPA2 security type when device
     * doesn't support it.
     */
    public SoftApConfiguration resetToDefaultForUnsupportedConfig(
            @NonNull SoftApConfiguration config) {
        SoftApConfiguration.Builder configBuilder = new SoftApConfiguration.Builder(config);
        if ((!ApConfigUtil.isClientForceDisconnectSupported(mContext)
                || mContext.getResources().getBoolean(
                R.bool.config_wifiSoftapResetUserControlConfig))
                && (config.isClientControlByUserEnabled()
                || config.getBlockedClientList().size() != 0)) {
            configBuilder.setClientControlByUserEnabled(false);
            configBuilder.setBlockedClientList(new ArrayList<>());
            Log.i(TAG, "Reset ClientControlByUser to false due to device doesn't support");
        }

        if ((!ApConfigUtil.isClientForceDisconnectSupported(mContext)
                || mContext.getResources().getBoolean(
                R.bool.config_wifiSoftapResetMaxClientSettingConfig))
                && config.getMaxNumberOfClients() != 0) {
            configBuilder.setMaxNumberOfClients(0);
            Log.i(TAG, "Reset MaxNumberOfClients to 0 due to device doesn't support");
        }

        if (!ApConfigUtil.isWpa3SaeSupported(mContext) && (config.getSecurityType()
                == SoftApConfiguration.SECURITY_TYPE_WPA3_SAE
                || config.getSecurityType()
                == SoftApConfiguration.SECURITY_TYPE_WPA3_SAE_TRANSITION)) {
            configBuilder.setPassphrase(generatePassword(),
                    SoftApConfiguration.SECURITY_TYPE_WPA2_PSK);
            Log.i(TAG, "Device doesn't support WPA3-SAE, reset config to WPA2");
        }

        if (mContext.getResources().getBoolean(R.bool.config_wifiSoftapResetChannelConfig)
                && config.getChannel() != 0) {
            // The device might not support customize channel or forced channel might not
            // work in some countries. Need to reset it.
            // Add 2.4G by default
            configBuilder.setBand(config.getBand() | SoftApConfiguration.BAND_2GHZ);
            Log.i(TAG, "Reset SAP channel configuration");
        }

        int newBand = config.getBand();
        if (!mContext.getResources().getBoolean(R.bool.config_wifi6ghzSupport)
                && (newBand & SoftApConfiguration.BAND_6GHZ) != 0) {
            newBand &= ~SoftApConfiguration.BAND_6GHZ;
            Log.i(TAG, "Device doesn't support 6g, remove 6G band from band setting");
        }

        if (!mContext.getResources().getBoolean(R.bool.config_wifi5ghzSupport)
                && (newBand & SoftApConfiguration.BAND_5GHZ) != 0) {
            newBand &= ~SoftApConfiguration.BAND_5GHZ;
            Log.i(TAG, "Device doesn't support 5g, remove 5G band from band setting");
        }

        if (newBand != config.getBand()) {
            // Always added 2.4G by default when reset the band.
            Log.i(TAG, "Reset band from " + config.getBand() + " to "
                    + (newBand | SoftApConfiguration.BAND_2GHZ));
            configBuilder.setBand(newBand | SoftApConfiguration.BAND_2GHZ);
        }

        if (mContext.getResources().getBoolean(R.bool.config_wifiSoftapResetHiddenConfig)
                && config.isHiddenSsid()) {
            configBuilder.setHiddenSsid(false);
            Log.i(TAG, "Reset SAP Hidden Network configuration");
        }

        if (mContext.getResources().getBoolean(
                R.bool.config_wifiSoftapResetAutoShutdownTimerConfig)
                && config.getShutdownTimeoutMillis() != 0) {
            configBuilder.setShutdownTimeoutMillis(0);
            Log.i(TAG, "Reset SAP auto shutdown configuration");
        }

        mWifiMetrics.noteSoftApConfigReset(config, configBuilder.build());
        return configBuilder.build();
    }

    private SoftApConfiguration sanitizePersistentApConfig(SoftApConfiguration config) {
        SoftApConfiguration.Builder convertedConfigBuilder = null;

        // some countries are unable to support 5GHz only operation, always allow for 2GHz when
        // config doesn't force channel
        if (config.getChannel() == 0 && (config.getBand() & SoftApConfiguration.BAND_2GHZ) == 0) {
            Log.w(TAG, "Supplied ap config band without 2.4G, add allowing for 2.4GHz");
            if (convertedConfigBuilder == null) {
                convertedConfigBuilder = new SoftApConfiguration.Builder(config);
            }
            convertedConfigBuilder.setBand(config.getBand() | SoftApConfiguration.BAND_2GHZ);
        }
        return convertedConfigBuilder == null ? config : convertedConfigBuilder.build();
    }

    private void persistConfigAndTriggerBackupManagerProxy(SoftApConfiguration config) {
        mPersistentWifiApConfig = config;
        mHasNewDataToSerialize = true;
        mWifiConfigManager.saveToStore(true);
        mBackupManagerProxy.notifyDataChanged();
    }

    /**
     * Generate a default WPA3 SAE transition (if supported) or WPA2 based
     * configuration with a random password.
     * We are changing the Wifi Ap configuration storage from secure settings to a
     * flat file accessible only by the system. A WPA2 based default configuration
     * will keep the device secure after the update.
     */
    private SoftApConfiguration getDefaultApConfiguration() {
        SoftApConfiguration.Builder configBuilder = new SoftApConfiguration.Builder();
        configBuilder.setBand(SoftApConfiguration.BAND_2GHZ);
        configBuilder.setSsid(mContext.getResources().getString(
                R.string.wifi_tether_configure_ssid_default) + "_" + getRandomIntForDefaultSsid());
        if (ApConfigUtil.isWpa3SaeSupported(mContext)) {
            configBuilder.setPassphrase(generatePassword(),
                    SoftApConfiguration.SECURITY_TYPE_WPA3_SAE_TRANSITION);
        } else {
            configBuilder.setPassphrase(generatePassword(),
                    SoftApConfiguration.SECURITY_TYPE_WPA2_PSK);
        }
        return configBuilder.build();
    }

    private static int getRandomIntForDefaultSsid() {
        Random random = new Random();
        return random.nextInt((RAND_SSID_INT_MAX - RAND_SSID_INT_MIN) + 1) + RAND_SSID_INT_MIN;
    }

    private static String generateLohsSsid(Context context) {
        return context.getResources().getString(
                R.string.wifi_localhotspot_configure_ssid_default) + "_"
                + getRandomIntForDefaultSsid();
    }

    /**
     * Generate a temporary WPA2 based configuration for use by the local only hotspot.
     * This config is not persisted and will not be stored by the WifiApConfigStore.
     */
    public static SoftApConfiguration generateLocalOnlyHotspotConfig(Context context, int apBand,
            @Nullable SoftApConfiguration customConfig) {
        SoftApConfiguration.Builder configBuilder;
        if (customConfig != null) {
            configBuilder = new SoftApConfiguration.Builder(customConfig);
        } else {
            configBuilder = new SoftApConfiguration.Builder();
            // Default to disable the auto shutdown
            configBuilder.setAutoShutdownEnabled(false);
        }

        configBuilder.setBand(apBand);

        if (customConfig == null || customConfig.getSsid() == null) {
            configBuilder.setSsid(generateLohsSsid(context));
        }
        if (customConfig == null) {
            if (ApConfigUtil.isWpa3SaeSupported(context)) {
                configBuilder.setPassphrase(generatePassword(),
                        SoftApConfiguration.SECURITY_TYPE_WPA3_SAE_TRANSITION);
            } else {
                configBuilder.setPassphrase(generatePassword(),
                        SoftApConfiguration.SECURITY_TYPE_WPA2_PSK);
            }
        }

        return configBuilder.build();
    }

    /**
     * @return a copy of the given SoftApConfig with the BSSID randomized, unless a custom BSSID is
     * already set.
     */
    SoftApConfiguration randomizeBssidIfUnset(Context context, SoftApConfiguration config) {
        SoftApConfiguration.Builder configBuilder = new SoftApConfiguration.Builder(config);
        if (config.getBssid() == null && context.getResources().getBoolean(
                R.bool.config_wifi_ap_mac_randomization_supported)) {
            MacAddress macAddress = mMacAddressUtil.calculatePersistentMac(config.getSsid(),
                    mMacAddressUtil.obtainMacRandHashFunctionForSap(Process.WIFI_UID));
            if (macAddress == null) {
                Log.e(TAG, "Failed to calculate MAC from SSID. "
                        + "Generating new random MAC instead.");
                macAddress = MacAddressUtils.createRandomUnicastAddress();
            }
            configBuilder.setBssid(macAddress);
        }
        return configBuilder.build();
    }

    /**
     * Verify provided SSID for existence, length and conversion to bytes
     *
     * @param ssid String ssid name
     * @return boolean indicating ssid met requirements
     */
    private static boolean validateApConfigSsid(String ssid) {
        if (TextUtils.isEmpty(ssid)) {
            Log.d(TAG, "SSID for softap configuration must be set.");
            return false;
        }

        try {
            byte[] ssid_bytes = ssid.getBytes(StandardCharsets.UTF_8);

            if (ssid_bytes.length < SSID_MIN_LEN || ssid_bytes.length > SSID_MAX_LEN) {
                Log.d(TAG, "softap SSID is defined as UTF-8 and it must be at least "
                        + SSID_MIN_LEN + " byte and not more than " + SSID_MAX_LEN + " bytes");
                return false;
            }
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "softap config SSID verification failed: malformed string " + ssid);
            return false;
        }
        return true;
    }

    /**
     * Verify provided preSharedKey in ap config for WPA2_PSK network meets requirements.
     */
    private static boolean validateApConfigPreSharedKey(String preSharedKey) {
        if (preSharedKey.length() < PSK_MIN_LEN || preSharedKey.length() > PSK_MAX_LEN) {
            Log.d(TAG, "softap network password string size must be at least " + PSK_MIN_LEN
                    + " and no more than " + PSK_MAX_LEN);
            return false;
        }

        try {
            preSharedKey.getBytes(StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "softap network password verification failed: malformed string");
            return false;
        }
        return true;
    }

    /**
     * Validate a SoftApConfiguration is properly configured for use by SoftApManager.
     *
     * This method checks the length of the SSID and for sanity between security settings (if it
     * requires a password, was one provided?).
     *
     * @param apConfig {@link SoftApConfiguration} to use for softap mode
     * @param isPrivileged indicate the caller can pass some fields check or not
     * @return boolean true if the provided config meets the minimum set of details, false
     * otherwise.
     */
    static boolean validateApWifiConfiguration(@NonNull SoftApConfiguration apConfig,
            boolean isPrivileged) {
        // first check the SSID
        if (!validateApConfigSsid(apConfig.getSsid())) {
            // failed SSID verificiation checks
            return false;
        }

        // BSSID can be set if caller own permission:android.Manifest.permission.NETWORK_SETTINGS.
        if (apConfig.getBssid() != null && !isPrivileged) {
            Log.e(TAG, "Config BSSID needs NETWORK_SETTINGS permission");
            return false;
        }

        String preSharedKey = apConfig.getPassphrase();
        boolean hasPreSharedKey = !TextUtils.isEmpty(preSharedKey);
        int authType;

        try {
            authType = apConfig.getSecurityType();
        } catch (IllegalStateException e) {
            Log.d(TAG, "Unable to get AuthType for softap config: " + e.getMessage());
            return false;
        }

        if (authType == SoftApConfiguration.SECURITY_TYPE_OPEN) {
            // open networks should not have a password
            if (hasPreSharedKey) {
                Log.d(TAG, "open softap network should not have a password");
                return false;
            }
        } else if (authType == SoftApConfiguration.SECURITY_TYPE_WPA2_PSK
                || authType == SoftApConfiguration.SECURITY_TYPE_WPA3_SAE_TRANSITION
                || authType == SoftApConfiguration.SECURITY_TYPE_WPA3_SAE) {
            // this is a config that should have a password - check that first
            if (!hasPreSharedKey) {
                Log.d(TAG, "softap network password must be set");
                return false;
            }
            if (authType != SoftApConfiguration.SECURITY_TYPE_WPA3_SAE
                    && !validateApConfigPreSharedKey(preSharedKey)) {
                // failed preSharedKey checks for WPA2 and WPA3 SAE Transition mode.
                return false;
            }
        } else {
            // this is not a supported security type
            Log.d(TAG, "softap configs must either be open or WPA2 PSK networks");
            return false;
        }

        return true;
    }

    private static String generatePassword() {
        // Characters that will be used for password generation. Some characters commonly known to
        // be confusing like 0 and O excluded from this list.
        final String allowed = "23456789abcdefghijkmnpqrstuvwxyz";
        final int passLength = 15;

        StringBuilder sb = new StringBuilder(passLength);
        SecureRandom random = new SecureRandom();
        for (int i = 0; i < passLength; i++) {
            sb.append(allowed.charAt(random.nextInt(allowed.length())));
        }
        return sb.toString();
    }
}
