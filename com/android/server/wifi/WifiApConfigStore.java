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
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiConfiguration.KeyMgmt;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.messages.nano.SystemMessageProto.SystemMessage;
import com.android.internal.notification.SystemNotificationChannels;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Random;
import java.util.UUID;

/**
 * Provides API for reading/writing soft access point configuration.
 */
public class WifiApConfigStore {

    // Intent when user has interacted with the softap settings change notification
    public static final String ACTION_HOTSPOT_CONFIG_USER_TAPPED_CONTENT =
            "com.android.server.wifi.WifiApConfigStoreUtil.HOTSPOT_CONFIG_USER_TAPPED_CONTENT";

    private static final String TAG = "WifiApConfigStore";

    private static final String DEFAULT_AP_CONFIG_FILE =
            Environment.getDataDirectory() + "/misc/wifi/softap.conf";

    private static final int AP_CONFIG_FILE_VERSION = 3;

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

    @VisibleForTesting
    static final int AP_CHANNEL_DEFAULT = 0;

    private WifiConfiguration mWifiApConfig = null;

    private ArrayList<Integer> mAllowed2GChannel = null;

    private final Context mContext;
    private final Handler mHandler;
    private final String mApConfigFile;
    private final BackupManagerProxy mBackupManagerProxy;
    private final FrameworkFacade mFrameworkFacade;
    private boolean mRequiresApBandConversion = false;

    WifiApConfigStore(Context context, Looper looper,
            BackupManagerProxy backupManagerProxy, FrameworkFacade frameworkFacade) {
        this(context, looper, backupManagerProxy, frameworkFacade, DEFAULT_AP_CONFIG_FILE);
    }

    WifiApConfigStore(Context context,
                      Looper looper,
                      BackupManagerProxy backupManagerProxy,
                      FrameworkFacade frameworkFacade,
                      String apConfigFile) {
        mContext = context;
        mHandler = new Handler(looper);
        mBackupManagerProxy = backupManagerProxy;
        mFrameworkFacade = frameworkFacade;
        mApConfigFile = apConfigFile;

        String ap2GChannelListStr = mContext.getResources().getString(
                R.string.config_wifi_framework_sap_2G_channel_list);
        Log.d(TAG, "2G band allowed channels are:" + ap2GChannelListStr);

        if (ap2GChannelListStr != null) {
            mAllowed2GChannel = new ArrayList<Integer>();
            String channelList[] = ap2GChannelListStr.split(",");
            for (String tmp : channelList) {
                mAllowed2GChannel.add(Integer.parseInt(tmp));
            }
        }

        mRequiresApBandConversion = mContext.getResources().getBoolean(
                R.bool.config_wifi_convert_apband_5ghz_to_any);

        /* Load AP configuration from persistent storage. */
        mWifiApConfig = loadApConfiguration(mApConfigFile);
        if (mWifiApConfig == null) {
            /* Use default configuration. */
            Log.d(TAG, "Fallback to use default AP configuration");
            mWifiApConfig = getDefaultApConfiguration();

            /* Save the default configuration to persistent storage. */
            writeApConfiguration(mApConfigFile, mWifiApConfig);
        }

        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_HOTSPOT_CONFIG_USER_TAPPED_CONTENT);
        mContext.registerReceiver(
                mBroadcastReceiver, filter, null /* broadcastPermission */, mHandler);
    }

    private final BroadcastReceiver mBroadcastReceiver =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    // For now we only have one registered listener, but we easily could expand this
                    // to support multiple signals.  Starting off with a switch to support trivial
                    // expansion.
                    switch(intent.getAction()) {
                        case ACTION_HOTSPOT_CONFIG_USER_TAPPED_CONTENT:
                            handleUserHotspotConfigTappedContent();
                            break;
                        default:
                            Log.e(TAG, "Unknown action " + intent.getAction());
                    }
                }
            };

    /**
     * Return the current soft access point configuration.
     */
    public synchronized WifiConfiguration getApConfiguration() {
        WifiConfiguration config = apBandCheckConvert(mWifiApConfig);
        if (mWifiApConfig != config) {
            Log.d(TAG, "persisted config was converted, need to resave it");
            mWifiApConfig = config;
            persistConfigAndTriggerBackupManagerProxy(mWifiApConfig);
        }
        return mWifiApConfig;
    }

    /**
     * Update the current soft access point configuration.
     * Restore to default AP configuration if null is provided.
     * This can be invoked under context of binder threads (WifiManager.setWifiApConfiguration)
     * and ClientModeImpl thread (CMD_START_AP).
     */
    public synchronized void setApConfiguration(WifiConfiguration config) {
        if (config == null) {
            mWifiApConfig = getDefaultApConfiguration();
        } else {
            mWifiApConfig = apBandCheckConvert(config);
        }
        persistConfigAndTriggerBackupManagerProxy(mWifiApConfig);
    }

    public ArrayList<Integer> getAllowed2GChannel() {
        return mAllowed2GChannel;
    }

    /**
     * Helper method to create and send notification to user of apBand conversion.
     *
     * @param packageName name of the calling app
     */
    public void notifyUserOfApBandConversion(String packageName) {
        Log.w(TAG, "ready to post notification - triggered by " + packageName);
        Notification notification = createConversionNotification();
        NotificationManager notificationManager = (NotificationManager)
                    mContext.getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.notify(SystemMessage.NOTE_SOFTAP_CONFIG_CHANGED, notification);
    }

    private Notification createConversionNotification() {
        CharSequence title =
                mContext.getResources().getText(R.string.wifi_softap_config_change);
        CharSequence contentSummary =
                mContext.getResources().getText(R.string.wifi_softap_config_change_summary);
        CharSequence content =
                mContext.getResources().getText(R.string.wifi_softap_config_change_detailed);
        int color =
                mContext.getResources().getColor(
                        R.color.system_notification_accent_color, mContext.getTheme());

        return new Notification.Builder(mContext, SystemNotificationChannels.NETWORK_STATUS)
                .setSmallIcon(R.drawable.ic_wifi_settings)
                .setPriority(Notification.PRIORITY_HIGH)
                .setCategory(Notification.CATEGORY_SYSTEM)
                .setContentTitle(title)
                .setContentText(contentSummary)
                .setContentIntent(getPrivateBroadcast(ACTION_HOTSPOT_CONFIG_USER_TAPPED_CONTENT))
                .setTicker(title)
                .setShowWhen(false)
                .setLocalOnly(true)
                .setColor(color)
                .setStyle(new Notification.BigTextStyle().bigText(content)
                                                         .setBigContentTitle(title)
                                                         .setSummaryText(contentSummary))
                .build();
    }

    private WifiConfiguration apBandCheckConvert(WifiConfiguration config) {
        if (mRequiresApBandConversion) {
            // some devices are unable to support 5GHz only operation, check for 5GHz and
            // move to ANY if apBand conversion is required.
            if (config.apBand == WifiConfiguration.AP_BAND_5GHZ) {
                Log.w(TAG, "Supplied ap config band was 5GHz only, converting to ANY");
                WifiConfiguration convertedConfig = new WifiConfiguration(config);
                convertedConfig.apBand = WifiConfiguration.AP_BAND_ANY;
                convertedConfig.apChannel = AP_CHANNEL_DEFAULT;
                return convertedConfig;
            }
        } else {
            // this is a single mode device, we do not support ANY.  Convert all ANY to 5GHz
            if (config.apBand == WifiConfiguration.AP_BAND_ANY) {
                Log.w(TAG, "Supplied ap config band was ANY, converting to 5GHz");
                WifiConfiguration convertedConfig = new WifiConfiguration(config);
                convertedConfig.apBand = WifiConfiguration.AP_BAND_5GHZ;
                convertedConfig.apChannel = AP_CHANNEL_DEFAULT;
                return convertedConfig;
            }
        }
        return config;
    }

    private void persistConfigAndTriggerBackupManagerProxy(WifiConfiguration config) {
        writeApConfiguration(mApConfigFile, mWifiApConfig);
        // Stage the backup of the SettingsProvider package which backs this up
        mBackupManagerProxy.notifyDataChanged();
    }

    /**
     * Load AP configuration from persistent storage.
     */
    private static WifiConfiguration loadApConfiguration(final String filename) {
        WifiConfiguration config = null;
        DataInputStream in = null;
        try {
            config = new WifiConfiguration();
            in = new DataInputStream(
                    new BufferedInputStream(new FileInputStream(filename)));

            int version = in.readInt();
            if (version < 1 || version > AP_CONFIG_FILE_VERSION) {
                Log.e(TAG, "Bad version on hotspot configuration file");
                return null;
            }
            config.SSID = in.readUTF();

            if (version >= 2) {
                config.apBand = in.readInt();
                config.apChannel = in.readInt();
            }

            if (version >= 3) {
                config.hiddenSSID = in.readBoolean();
            }

            int authType = in.readInt();
            config.allowedKeyManagement.set(authType);
            if (authType != KeyMgmt.NONE) {
                config.preSharedKey = in.readUTF();
            }
        } catch (IOException e) {
            Log.e(TAG, "Error reading hotspot configuration " + e);
            config = null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException e) {
                    Log.e(TAG, "Error closing hotspot configuration during read" + e);
                }
            }
        }
        return config;
    }

    /**
     * Write AP configuration to persistent storage.
     */
    private static void writeApConfiguration(final String filename,
                                             final WifiConfiguration config) {
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(
                        new FileOutputStream(filename)))) {
            out.writeInt(AP_CONFIG_FILE_VERSION);
            out.writeUTF(config.SSID);
            out.writeInt(config.apBand);
            out.writeInt(config.apChannel);
            out.writeBoolean(config.hiddenSSID);
            int authType = config.getAuthType();
            out.writeInt(authType);
            if (authType != KeyMgmt.NONE) {
                out.writeUTF(config.preSharedKey);
            }
        } catch (IOException e) {
            Log.e(TAG, "Error writing hotspot configuration" + e);
        }
    }

    /**
     * Generate a default WPA2 based configuration with a random password.
     * We are changing the Wifi Ap configuration storage from secure settings to a
     * flat file accessible only by the system. A WPA2 based default configuration
     * will keep the device secure after the update.
     */
    private WifiConfiguration getDefaultApConfiguration() {
        WifiConfiguration config = new WifiConfiguration();
        config.apBand = WifiConfiguration.AP_BAND_2GHZ;
        config.SSID = mContext.getResources().getString(
                R.string.wifi_tether_configure_ssid_default) + "_" + getRandomIntForDefaultSsid();
        config.allowedKeyManagement.set(KeyMgmt.WPA2_PSK);
        String randomUUID = UUID.randomUUID().toString();
        //first 12 chars from xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx
        config.preSharedKey = randomUUID.substring(0, 8) + randomUUID.substring(9, 13);
        return config;
    }

    private static int getRandomIntForDefaultSsid() {
        Random random = new Random();
        return random.nextInt((RAND_SSID_INT_MAX - RAND_SSID_INT_MIN) + 1) + RAND_SSID_INT_MIN;
    }

    /**
     * Generate a temporary WPA2 based configuration for use by the local only hotspot.
     * This config is not persisted and will not be stored by the WifiApConfigStore.
     */
    public static WifiConfiguration generateLocalOnlyHotspotConfig(Context context, int apBand) {
        WifiConfiguration config = new WifiConfiguration();

        config.SSID = context.getResources().getString(
              R.string.wifi_localhotspot_configure_ssid_default) + "_"
                      + getRandomIntForDefaultSsid();
        config.apBand = apBand;
        config.allowedKeyManagement.set(KeyMgmt.WPA2_PSK);
        config.networkId = WifiConfiguration.LOCAL_ONLY_NETWORK_ID;
        String randomUUID = UUID.randomUUID().toString();
        // first 12 chars from xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx
        config.preSharedKey = randomUUID.substring(0, 8) + randomUUID.substring(9, 13);
        return config;
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
     * Validate a WifiConfiguration is properly configured for use by SoftApManager.
     *
     * This method checks the length of the SSID and for sanity between security settings (if it
     * requires a password, was one provided?).
     *
     * @param apConfig {@link WifiConfiguration} to use for softap mode
     * @return boolean true if the provided config meets the minimum set of details, false
     * otherwise.
     */
    static boolean validateApWifiConfiguration(@NonNull WifiConfiguration apConfig) {
        // first check the SSID
        if (!validateApConfigSsid(apConfig.SSID)) {
            // failed SSID verificiation checks
            return false;
        }

        // now check security settings: settings app allows open and WPA2 PSK
        if (apConfig.allowedKeyManagement == null) {
            Log.d(TAG, "softap config key management bitset was null");
            return false;
        }

        String preSharedKey = apConfig.preSharedKey;
        boolean hasPreSharedKey = !TextUtils.isEmpty(preSharedKey);
        int authType;

        try {
            authType = apConfig.getAuthType();
        } catch (IllegalStateException e) {
            Log.d(TAG, "Unable to get AuthType for softap config: " + e.getMessage());
            return false;
        }

        if (authType == KeyMgmt.NONE) {
            // open networks should not have a password
            if (hasPreSharedKey) {
                Log.d(TAG, "open softap network should not have a password");
                return false;
            }
        } else if (authType == KeyMgmt.WPA2_PSK) {
            // this is a config that should have a password - check that first
            if (!hasPreSharedKey) {
                Log.d(TAG, "softap network password must be set");
                return false;
            }

            if (!validateApConfigPreSharedKey(preSharedKey)) {
                // failed preSharedKey checks
                return false;
            }
        } else {
            // this is not a supported security type
            Log.d(TAG, "softap configs must either be open or WPA2 PSK networks");
            return false;
        }

        return true;
    }

    /**
     * Helper method to start up settings on the softap config page.
     */
    private void startSoftApSettings() {
        mContext.startActivity(
                new Intent("com.android.settings.WIFI_TETHER_SETTINGS")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    /**
     * Helper method to trigger settings to open the softap config page
     */
    private void handleUserHotspotConfigTappedContent() {
        startSoftApSettings();
        NotificationManager notificationManager = (NotificationManager)
                mContext.getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.cancel(SystemMessage.NOTE_SOFTAP_CONFIG_CHANGED);
    }

    private PendingIntent getPrivateBroadcast(String action) {
        Intent intent = new Intent(action).setPackage("android");
        return mFrameworkFacade.getBroadcast(
                mContext, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT);
    }
}
