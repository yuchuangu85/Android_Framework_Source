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

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.net.wifi.ScanResult;
import android.util.Log;

import com.android.internal.R;
import com.android.internal.notification.SystemNotificationChannels;

/**
 * Helper to create notifications for {@link OpenNetworkNotifier}.
 */
public class ConnectToNetworkNotificationBuilder {

    /** Intent when user dismissed the "Connect to Network" notification. */
    public static final String ACTION_USER_DISMISSED_NOTIFICATION =
            "com.android.server.wifi.ConnectToNetworkNotification.USER_DISMISSED_NOTIFICATION";

    /** Intent when user tapped action button to connect to recommended network. */
    public static final String ACTION_CONNECT_TO_NETWORK =
            "com.android.server.wifi.ConnectToNetworkNotification.CONNECT_TO_NETWORK";

    /** Intent when user tapped action button to open Wi-Fi Settings. */
    public static final String ACTION_PICK_WIFI_NETWORK =
            "com.android.server.wifi.ConnectToNetworkNotification.PICK_WIFI_NETWORK";

    /** Intent when user tapped "Failed to connect" notification to open Wi-Fi Settings. */
    public static final String ACTION_PICK_WIFI_NETWORK_AFTER_CONNECT_FAILURE =
            "com.android.server.wifi.ConnectToNetworkNotification.PICK_NETWORK_AFTER_FAILURE";

    /** Extra data added to the Intent to specify the registering network notifier. */
    public static final String AVAILABLE_NETWORK_NOTIFIER_TAG =
            "com.android.server.wifi.ConnectToNetworkNotification.AVAILABLE_NETWORK_NOTIFIER_TAG";

    private Context mContext;
    private Resources mResources;
    private FrameworkFacade mFrameworkFacade;

    public ConnectToNetworkNotificationBuilder(
            Context context,
            FrameworkFacade framework) {
        mContext = context;
        mResources = context.getResources();
        mFrameworkFacade = framework;
    }

    /**
     * Creates the connect to network notification that alerts users of a recommended connectable
     * network.
     *
     * There are two actions - "Options" link to the Wi-Fi picker activity, and "Connect" prompts
     * the connection to the recommended network.
     *
     * @param notifierTag Unique tag of calling network notifier
     * @param network The network to be recommended
     */
    public Notification createConnectToAvailableNetworkNotification(String notifierTag,
            ScanResult network) {
        CharSequence title;
        switch (notifierTag) {
            case OpenNetworkNotifier.TAG:
                title = mContext.getText(R.string.wifi_available_title);
                break;
            case CarrierNetworkNotifier.TAG:
                title = mContext.getText(R.string.wifi_available_carrier_network_title);
                break;
            default:
                Log.wtf("ConnectToNetworkNotificationBuilder", "Unknown network notifier."
                        + notifierTag);
                return null;
        }
        Notification.Action connectAction = new Notification.Action.Builder(null /* icon */,
                mResources.getText(R.string.wifi_available_action_connect),
                getPrivateBroadcast(ACTION_CONNECT_TO_NETWORK, notifierTag)).build();
        Notification.Action allNetworksAction = new Notification.Action.Builder(null /* icon */,
                mResources.getText(R.string.wifi_available_action_all_networks),
                getPrivateBroadcast(ACTION_PICK_WIFI_NETWORK, notifierTag)).build();
        return createNotificationBuilder(title, network.SSID, notifierTag)
                .setContentIntent(getPrivateBroadcast(ACTION_PICK_WIFI_NETWORK, notifierTag))
                .addAction(connectAction)
                .addAction(allNetworksAction)
                .build();
    }

    /**
     * Creates the notification that indicates the controller is attempting to connect to the
     * recommended network.
     *
     * @param notifierTag Unique tag of the calling network notifier
     * @param network The network to be recommended
     */
    public Notification createNetworkConnectingNotification(String notifierTag,
            ScanResult network) {
        return createNotificationBuilder(
                mContext.getText(R.string.wifi_available_title_connecting), network.SSID,
                        notifierTag)
                .setProgress(0 /* max */, 0 /* progress */, true /* indeterminate */)
                .build();
    }

    /**
     * Creates the notification that indicates the controller successfully connected to the
     * recommended network.
     *
     * @param notifierTag Unique tag of the calling network notifier
     * @param network The network to be recommended
     */
    public Notification createNetworkConnectedNotification(String notifierTag, ScanResult network) {
        return createNotificationBuilder(
                mContext.getText(R.string.wifi_available_title_connected), network.SSID,
                        notifierTag)
                .build();
    }

    /**
     * Creates the notification that indicates the controller failed to connect to the recommended
     * network. Tapping this notification opens the wifi picker.
     *
     * @param notifierTag Unique tag of the calling network notifier
     */
    public Notification createNetworkFailedNotification(String notifierTag) {
        return createNotificationBuilder(
                mContext.getText(R.string.wifi_available_title_failed_to_connect),
                mContext.getText(R.string.wifi_available_content_failed_to_connect), notifierTag)
                .setContentIntent(
                        getPrivateBroadcast(ACTION_PICK_WIFI_NETWORK_AFTER_CONNECT_FAILURE,
                                notifierTag))
                .setAutoCancel(true)
                .build();
    }

    private int getNotifierRequestCode(String notifierTag) {
        switch (notifierTag) {
            case OpenNetworkNotifier.TAG:
                return 1;
            case CarrierNetworkNotifier.TAG:
                return 2;
        }
        return 0;
    }

    private Notification.Builder createNotificationBuilder(
            CharSequence title, CharSequence content, String extraData) {
        return mFrameworkFacade.makeNotificationBuilder(mContext,
                SystemNotificationChannels.NETWORK_AVAILABLE)
                .setSmallIcon(R.drawable.stat_notify_wifi_in_range)
                .setTicker(title)
                .setContentTitle(title)
                .setContentText(content)
                .setDeleteIntent(getPrivateBroadcast(ACTION_USER_DISMISSED_NOTIFICATION, extraData))
                .setShowWhen(false)
                .setLocalOnly(true)
                .setColor(mResources.getColor(R.color.system_notification_accent_color,
                        mContext.getTheme()));
    }

    private PendingIntent getPrivateBroadcast(String action, String extraData) {
        Intent intent = new Intent(action).setPackage("android");
        int requestCode = 0;  // Makes the different kinds of notifications distinguishable
        if (extraData != null) {
            intent.putExtra(AVAILABLE_NETWORK_NOTIFIER_TAG, extraData);
            requestCode = getNotifierRequestCode(extraData);
        }
        return mFrameworkFacade.getBroadcast(mContext, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT);
    }
}
