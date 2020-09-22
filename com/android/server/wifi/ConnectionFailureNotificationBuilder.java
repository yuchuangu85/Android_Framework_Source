/*
 * Copyright (C) 2019 The Android Open Source Project
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
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.net.wifi.WifiConfiguration;
import android.os.Handler;
import android.view.WindowManager;

import com.android.wifi.resources.R;

/**
 * Helper class for ConnectionFailureNotifier.
 */
public class ConnectionFailureNotificationBuilder {
    private static final String TAG = "ConnectionFailureNotifier";

    public static final String ACTION_SHOW_SET_RANDOMIZATION_DETAILS =
            "com.android.server.wifi.ACTION_SHOW_SET_RANDOMIZATION_DETAILS";
    public static final String RANDOMIZATION_SETTINGS_NETWORK_ID =
            "com.android.server.wifi.RANDOMIZATION_SETTINGS_NETWORK_ID";
    public static final String RANDOMIZATION_SETTINGS_NETWORK_SSID =
            "com.android.server.wifi.RANDOMIZATION_SETTINGS_NETWORK_SSID";

    private WifiContext mContext;
    private String mPackageName;
    private FrameworkFacade mFrameworkFacade;
    private WifiConnectivityManager mWifiConnectivityManager;
    private NotificationManager mNotificationManager;
    private Handler mHandler;

    public ConnectionFailureNotificationBuilder(WifiContext context, String packageName,
            FrameworkFacade framework) {
        mContext = context;
        mPackageName = packageName;
        mFrameworkFacade = framework;
    }

    /**
     * Creates a notification that alerts the user that the connection may be failing due to
     * MAC randomization.
     * @param config
     */
    public Notification buildNoMacRandomizationSupportNotification(
            @NonNull WifiConfiguration config) {
        String ssid = config.SSID;
        String ssidAndSecurityType = config.getSsidAndSecurityTypeString();
        String title = mContext.getResources().getString(
                R.string.wifi_cannot_connect_with_randomized_mac_title, ssid);
        String content = mContext.getResources().getString(
                R.string.wifi_cannot_connect_with_randomized_mac_message);

        Intent showDetailIntent = new Intent(ACTION_SHOW_SET_RANDOMIZATION_DETAILS)
                .setPackage(mPackageName);
        showDetailIntent.putExtra(RANDOMIZATION_SETTINGS_NETWORK_ID, config.networkId);
        showDetailIntent.putExtra(RANDOMIZATION_SETTINGS_NETWORK_SSID, ssidAndSecurityType);
        PendingIntent pendingShowDetailIntent = mFrameworkFacade.getBroadcast(
                mContext, 0, showDetailIntent, PendingIntent.FLAG_UPDATE_CURRENT);

        return mFrameworkFacade.makeNotificationBuilder(
                mContext, WifiService.NOTIFICATION_NETWORK_ALERTS)
                .setSmallIcon(Icon.createWithResource(mContext.getWifiOverlayApkPkgName(),
                        com.android.wifi.resources.R.drawable.stat_notify_wifi_in_range))
                .setTicker(title)
                .setContentTitle(title)
                .setContentText(content)
                .setContentIntent(pendingShowDetailIntent)
                .setShowWhen(false)
                .setLocalOnly(true)
                .setColor(mContext.getResources().getColor(
                        android.R.color.system_notification_accent_color, mContext.getTheme()))
                .setAutoCancel(true)
                .build();
    }

    /**
     * Creates an AlertDialog that allows the user to disable MAC randomization for a network.
     * @param ssid the displayed SSID in the dialog
     * @param onUserConfirm
     */
    public AlertDialog buildChangeMacRandomizationSettingDialog(
            String ssid, DialogInterface.OnClickListener onUserConfirm) {
        AlertDialog.Builder builder = mFrameworkFacade.makeAlertDialogBuilder(mContext)
                .setTitle(mContext.getResources().getString(
                        R.string.wifi_disable_mac_randomization_dialog_title))
                .setMessage(mContext.getResources().getString(
                        R.string.wifi_disable_mac_randomization_dialog_message, ssid))
                .setPositiveButton(
                        mContext.getResources().getString(
                                R.string.wifi_disable_mac_randomization_dialog_confirm_text),
                        onUserConfirm)
                // A null listener allows the dialog to be dismissed directly.
                .setNegativeButton(android.R.string.no, null);
        AlertDialog dialog = builder.create();
        dialog.setCanceledOnTouchOutside(false);
        dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT);
        return dialog;
    }
}
