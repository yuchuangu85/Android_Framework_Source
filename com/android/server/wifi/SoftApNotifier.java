/*
 * Copyright (C) 2020 The Android Open Source Project
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
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.graphics.drawable.Icon;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.messages.nano.SystemMessageProto.SystemMessage;
import com.android.wifi.resources.R;

/**
 * Helper class for SoftApManager to generator notification.
 */
public class SoftApNotifier {
    private static final String TAG = "SoftApNotifier";

    @VisibleForTesting
    public static final String ACTION_HOTSPOT_PREFERENCES =
            "com.android.settings.WIFI_TETHER_SETTINGS";

    @VisibleForTesting
    public static final int NOTIFICATION_ID_SOFTAP_AUTO_DISABLED =
            SystemMessage.NOTE_SOFTAP_AUTO_DISABLED;

    private final WifiContext mContext;
    private final FrameworkFacade mFrameworkFacade;
    private final NotificationManager mNotificationManager;

    public SoftApNotifier(WifiContext context, FrameworkFacade framework) {
        mContext = context;
        mFrameworkFacade = framework;
        mNotificationManager =
                mContext.getSystemService(NotificationManager.class);
    }

    /**
     * Show notification to notify user softap disable because auto shutdown timeout expired.
     */
    public void showSoftApShutDownTimeoutExpiredNotification() {
        mNotificationManager.notify(NOTIFICATION_ID_SOFTAP_AUTO_DISABLED,
                buildSoftApShutDownTimeoutExpiredNotification());
    }

    /**
     * Dismiss notification which used to notify user softap disable because auto shutdown
     * timeout expired.
     */
    public void dismissSoftApShutDownTimeoutExpiredNotification() {
        mNotificationManager.cancel(null, NOTIFICATION_ID_SOFTAP_AUTO_DISABLED);
    }

    private Notification buildSoftApShutDownTimeoutExpiredNotification() {
        String title = mContext.getResources().getString(
                R.string.wifi_softap_auto_shutdown_timeout_expired_title);
        String contentSummary = mContext.getResources().getString(
                R.string.wifi_softap_auto_shutdown_timeout_expired_summary);

        return mFrameworkFacade.makeNotificationBuilder(mContext,
                WifiService.NOTIFICATION_NETWORK_STATUS)
                .setSmallIcon(Icon.createWithResource(mContext.getWifiOverlayApkPkgName(),
                        R.drawable.ic_wifi_settings))
                .setContentTitle(title)
                .setContentText(contentSummary)
                .setContentIntent(launchWifiTetherSettings())
                .setTicker(title)
                .setShowWhen(false)
                .setLocalOnly(true)
                .setColor(mContext.getResources().getColor(
                        android.R.color.system_notification_accent_color, mContext.getTheme()))
                .setAutoCancel(true)
                .build();
    }

    private PendingIntent launchWifiTetherSettings() {
        Intent intent = new Intent(ACTION_HOTSPOT_PREFERENCES)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return mFrameworkFacade.getActivity(mContext, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT);
    }


}
