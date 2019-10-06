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

import android.app.ActivityManagerInternal;
import android.app.AppGlobals;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.net.TrafficStats;
import android.net.Uri;
import android.net.ip.IpClientCallbacks;
import android.net.ip.IpClientUtil;
import android.os.BatteryStats;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.storage.StorageManager;
import android.provider.Settings;
import android.telephony.CarrierConfigManager;

import com.android.internal.app.IBatteryStats;
import com.android.server.LocalServices;
import com.android.server.wifi.util.WifiAsyncChannel;

/**
 * This class allows overriding objects with mocks to write unit tests
 */
public class FrameworkFacade {
    public static final String TAG = "FrameworkFacade";

    private ActivityManagerInternal mActivityManagerInternal;

    public boolean setIntegerSetting(Context context, String name, int def) {
        return Settings.Global.putInt(context.getContentResolver(), name, def);
    }

    public int getIntegerSetting(Context context, String name, int def) {
        return Settings.Global.getInt(context.getContentResolver(), name, def);
    }

    public long getLongSetting(Context context, String name, long def) {
        return Settings.Global.getLong(context.getContentResolver(), name, def);
    }

    public boolean setStringSetting(Context context, String name, String def) {
        return Settings.Global.putString(context.getContentResolver(), name, def);
    }

    public String getStringSetting(Context context, String name) {
        return Settings.Global.getString(context.getContentResolver(), name);
    }

    /**
     * Mockable facade to Settings.Secure.getInt(.).
     */
    public int getSecureIntegerSetting(Context context, String name, int def) {
        return Settings.Secure.getInt(context.getContentResolver(), name, def);
    }

    /**
     * Mockable facade to Settings.Secure.getString(.).
     */
    public String getSecureStringSetting(Context context, String name) {
        return Settings.Secure.getString(context.getContentResolver(), name);
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
        context.getContentResolver().registerContentObserver(uri, notifyForDescendants,
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
        context.getContentResolver().unregisterContentObserver(contentObserver);
    }

    public IBinder getService(String serviceName) {
        return ServiceManager.getService(serviceName);
    }

    /**
     * Returns the battery stats interface
     * @return IBatteryStats BatteryStats service interface
     */
    public IBatteryStats getBatteryService() {
        return IBatteryStats.Stub.asInterface(getService(BatteryStats.SERVICE_NAME));
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

    public SupplicantStateTracker makeSupplicantStateTracker(Context context,
            WifiConfigManager configManager, Handler handler) {
        return new SupplicantStateTracker(context, configManager, this, handler);
    }

    public boolean getConfigWiFiDisableInECBM(Context context) {
        CarrierConfigManager configManager = (CarrierConfigManager) context
                .getSystemService(Context.CARRIER_CONFIG_SERVICE);
        if (configManager != null) {
            return configManager.getConfig().getBoolean(
                    CarrierConfigManager.KEY_CONFIG_WIFI_DISABLE_IN_ECBM);
        }
        /* Default to TRUE */
        return true;
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
     * Checks whether the given uid has been granted the given permission.
     * @param permName the permission to check
     * @param uid The uid to check
     * @return {@link PackageManager.PERMISSION_GRANTED} if the permission has been granted and
     *         {@link PackageManager.PERMISSION_DENIED} otherwise
     */
    public int checkUidPermission(String permName, int uid) throws RemoteException {
        return AppGlobals.getPackageManager().checkUidPermission(permName, uid);
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
     * Check if the device will be restarting after decrypting during boot by calling {@link
     * StorageManager.inCryptKeeperBounce}.
     * @return true if the device will restart, false otherwise
     */
    public boolean inStorageManagerCryptKeeperBounce() {
        return StorageManager.inCryptKeeperBounce();
    }

    /**
     * Check if the provided uid is the app in the foreground.
     * @param uid the uid to check
     * @return true if the app is in the foreground, false otherwise
     */
    public boolean isAppForeground(int uid) {
        if (mActivityManagerInternal == null) {
            mActivityManagerInternal = LocalServices.getService(ActivityManagerInternal.class);
        }
        return mActivityManagerInternal.isAppForeground(uid);
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
}
