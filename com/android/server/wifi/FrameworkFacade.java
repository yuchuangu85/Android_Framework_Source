/*
 * Copyright (C) 2016 The Android Open Source Project
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

import static android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE;
import static android.content.pm.PackageManager.FEATURE_DEVICE_ADMIN;

import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.admin.DevicePolicyManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.net.TrafficStats;
import android.net.Uri;
import android.net.ip.IpClientCallbacks;
import android.net.ip.IpClientUtil;
import android.os.PersistableBundle;
import android.provider.Settings;
import android.telephony.CarrierConfigManager;
import android.util.Log;
import android.widget.Toast;

import com.android.server.wifi.util.WifiAsyncChannel;

/**
 * This class allows overriding objects with mocks to write unit tests
 */
public class FrameworkFacade {
    public static final String TAG = "FrameworkFacade";

    private ContentResolver mContentResolver = null;
    private CarrierConfigManager mCarrierConfigManager = null;
    private ActivityManager mActivityManager = null;

    private ContentResolver getContentResolver(Context context) {
        if (mContentResolver == null) {
            mContentResolver = context.getContentResolver();
        }
        return mContentResolver;
    }

    private CarrierConfigManager getCarrierConfigManager(Context context) {
        if (mCarrierConfigManager == null) {
            mCarrierConfigManager =
                (CarrierConfigManager) context.getSystemService(Context.CARRIER_CONFIG_SERVICE);
        }
        return mCarrierConfigManager;
    }

    private ActivityManager getActivityManager(Context context) {
        if (mActivityManager == null) {
            mActivityManager =
                (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        }
        return mActivityManager;
    }

    /**
     * Mockable setter for Settings.Global
     */
    public boolean setIntegerSetting(ContentResolver contentResolver, String name, int value) {
        return Settings.Global.putInt(contentResolver, name, value);
    }

    /**
     * Mockable getter for Settings.Global
     */
    public int getIntegerSetting(ContentResolver contentResolver, String name, int def) {
        return Settings.Global.getInt(contentResolver, name, def);
    }

    public boolean setIntegerSetting(Context context, String name, int def) {
        return Settings.Global.putInt(getContentResolver(context), name, def);
    }

    public int getIntegerSetting(Context context, String name, int def) {
        return Settings.Global.getInt(getContentResolver(context), name, def);
    }

    public long getLongSetting(Context context, String name, long def) {
        return Settings.Global.getLong(getContentResolver(context), name, def);
    }

    public boolean setStringSetting(Context context, String name, String def) {
        return Settings.Global.putString(getContentResolver(context), name, def);
    }

    public String getStringSetting(Context context, String name) {
        return Settings.Global.getString(getContentResolver(context), name);
    }

    /**
     * Mockable facade to Settings.Secure.getInt(.).
     */
    public int getSecureIntegerSetting(Context context, String name, int def) {
        return Settings.Secure.getInt(getContentResolver(context), name, def);
    }

    /**
     * Mockable facade to Settings.Secure.getString(.).
     */
    public String getSecureStringSetting(Context context, String name) {
        return Settings.Secure.getString(getContentResolver(context), name);
    }

    /**
     * Returns whether the device is in NIAP mode or not.
     */
    public boolean isNiapModeOn(Context context) {
        DevicePolicyManager devicePolicyManager =
                context.getSystemService(DevicePolicyManager.class);
        if (devicePolicyManager == null
                && context.getPackageManager().hasSystemFeature(FEATURE_DEVICE_ADMIN)) {
            Log.e(TAG, "Error retrieving DPM service");
        }
        if (devicePolicyManager == null) return false;
        return devicePolicyManager.isCommonCriteriaModeEnabled(null);
    }

    /**
     * Helper method for classes to register a ContentObserver
     * {@see ContentResolver#registerContentObserver(Uri,boolean,ContentObserver)}.
     *
     * @param context
     * @param uri
     * @param notifyForDescendants
     * @param contentObserver
     */
    public void registerContentObserver(Context context, Uri uri,
            boolean notifyForDescendants, ContentObserver contentObserver) {
        getContentResolver(context).registerContentObserver(uri, notifyForDescendants,
                contentObserver);
    }

    /**
     * Helper method for classes to unregister a ContentObserver
     * {@see ContentResolver#unregisterContentObserver(ContentObserver)}.
     *
     * @param context
     * @param contentObserver
     */
    public void unregisterContentObserver(Context context, ContentObserver contentObserver) {
        getContentResolver(context).unregisterContentObserver(contentObserver);
    }

    public PendingIntent getBroadcast(Context context, int requestCode, Intent intent, int flags) {
        return PendingIntent.getBroadcast(context, requestCode, intent, flags);
    }

    /**
     * Wrapper for {@link PendingIntent#getActivity}.
     */
    public PendingIntent getActivity(Context context, int requestCode, Intent intent, int flags) {
        return PendingIntent.getActivity(context, requestCode, intent, flags);
    }

    public boolean getConfigWiFiDisableInECBM(Context context) {
        CarrierConfigManager configManager = getCarrierConfigManager(context);
        if (configManager == null) {
            return false;
        }
        PersistableBundle bundle = configManager.getConfig();
        if (bundle == null) {
            return false;
        }
        return bundle.getBoolean(CarrierConfigManager.KEY_CONFIG_WIFI_DISABLE_IN_ECBM);
    }

    public long getTxPackets(String iface) {
        return TrafficStats.getTxPackets(iface);
    }

    public long getRxPackets(String iface) {
        return TrafficStats.getRxPackets(iface);
    }

    /**
     * Request a new IpClient to be created asynchronously.
     * @param context Context to use for creation.
     * @param iface Interface the client should act on.
     * @param callback IpClient event callbacks.
     */
    public void makeIpClient(Context context, String iface, IpClientCallbacks callback) {
        IpClientUtil.makeIpClient(context, iface, callback);
    }

    /**
     * Create a new instance of WifiAsyncChannel
     * @param tag String corresponding to the service creating the channel
     * @return WifiAsyncChannel object created
     */
    public WifiAsyncChannel makeWifiAsyncChannel(String tag) {
        return new WifiAsyncChannel(tag);
    }

    /**
     * Check if the provided uid is the app in the foreground.
     * @param uid the uid to check
     * @return true if the app is in the foreground, false otherwise
     */
    public boolean isAppForeground(Context context, int uid) {
        ActivityManager activityManager = getActivityManager(context);
        if (activityManager == null) return false;
        return activityManager.getUidImportance(uid) <= IMPORTANCE_VISIBLE;
    }

    /**
     * Create a new instance of {@link Notification.Builder}.
     * @param context reference to a Context
     * @param channelId ID of the notification channel
     * @return an instance of Notification.Builder
     */
    public Notification.Builder makeNotificationBuilder(Context context, String channelId) {
        return new Notification.Builder(context, channelId);
    }

    /**
     * Starts supplicant
     */
    public void startSupplicant() {
        SupplicantManager.start();
    }

    /**
     * Stops supplicant
     */
    public void stopSupplicant() {
        SupplicantManager.stop();
    }

    /**
     * Create a new instance of {@link AlertDialog.Builder}.
     * @param context reference to a Context
     * @return an instance of AlertDialog.Builder
     */
    public AlertDialog.Builder makeAlertDialogBuilder(Context context) {
        return new AlertDialog.Builder(context);
    }

    /**
     * Show a toast message
     * @param context reference to a Context
     * @param text the message to display
     */
    public void showToast(Context context, String text) {
        Toast toast = Toast.makeText(context, text, Toast.LENGTH_SHORT);
        toast.show();
    }

    /**
     * Wrapper for {@link TrafficStats#getMobileTxBytes}.
     */
    public long getMobileTxBytes() {
        return TrafficStats.getMobileTxBytes();
    }

    /**
     * Wrapper for {@link TrafficStats#getMobileRxBytes}.
     */
    public long getMobileRxBytes() {
        return TrafficStats.getMobileRxBytes();
    }

    /**
     * Wrapper for {@link TrafficStats#getTotalTxBytes}.
     */
    public long getTotalTxBytes() {
        return TrafficStats.getTotalTxBytes();
    }

    /**
     * Wrapper for {@link TrafficStats#getTotalRxBytes}.
     */
    public long getTotalRxBytes() {
        return TrafficStats.getTotalRxBytes();
    }
}
