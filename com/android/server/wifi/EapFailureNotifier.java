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

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.drawable.Icon;
import android.net.wifi.WifiConfiguration;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;
import android.telephony.SubscriptionManager;
import android.text.TextUtils;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.messages.nano.SystemMessageProto.SystemMessage;

/**
 * This class may be used to launch notifications when EAP failure occurs.
 */
public class EapFailureNotifier {
    private static final String TAG = "EapFailureNotifier";
    private static final String ERROR_MESSAGE_OVERLAY_PREFIX = "wifi_eap_error_message_code_";

    private static final long CANCEL_TIMEOUT_MILLISECONDS = 5 * 60 * 1000;
    private final WifiContext mContext;
    private final NotificationManager mNotificationManager;
    private final FrameworkFacade mFrameworkFacade;
    private final WifiCarrierInfoManager mWifiCarrierInfoManager;

    // Unique ID associated with the notification.
    public static final int NOTIFICATION_ID = SystemMessage.NOTE_WIFI_EAP_FAILURE;
    private String mCurrentShownSsid;

    public EapFailureNotifier(WifiContext context, FrameworkFacade frameworkFacade,
            WifiCarrierInfoManager wifiCarrierInfoManager) {
        mContext = context;
        mFrameworkFacade = frameworkFacade;
        mWifiCarrierInfoManager = wifiCarrierInfoManager;
        mNotificationManager =
                mContext.getSystemService(NotificationManager.class);
    }

    /**
     * Invoked when EAP failure occurs.
     *
     * @param errorCode error code which delivers from supplicant
     */
    public void onEapFailure(int errorCode, WifiConfiguration config) {
        StatusBarNotification[] activeNotifications = mNotificationManager.getActiveNotifications();
        for (StatusBarNotification activeNotification : activeNotifications) {
            if ((activeNotification.getId() == NOTIFICATION_ID)
                    && TextUtils.equals(config.SSID, mCurrentShownSsid)) {
                return;
            }
        }
        Resources res = getResourcesForSubId(mContext,
                mWifiCarrierInfoManager.getBestMatchSubscriptionId(config));
        if (res == null) return;
        int resourceId = res.getIdentifier(
                ERROR_MESSAGE_OVERLAY_PREFIX + errorCode,
                "string",
                // getIdentifier seems to use the Java package name rather than the Android
                // application package name. i.e. what you would have used if the resource name was
                // statically known:
                // import com.android.wifi.resources.R;
                // ...
                // R.string.wifi_eap_error_message_code_###
                mContext.getWifiOverlayJavaPkgName());

        if (resourceId == 0) return;
        String errorMessage = res.getString(resourceId, config.SSID);
        if (TextUtils.isEmpty(errorMessage)) return;
        showNotification(errorMessage, config.SSID);
    }

    /**
     * Display eap error notification which defined by carrier.
     *
     * @param ssid Error Message which defined by carrier
     */
    private void showNotification(String errorMessage, String ssid) {
        Intent intent = new Intent(Settings.ACTION_WIFI_SETTINGS);
        Notification.Builder builder = mFrameworkFacade.makeNotificationBuilder(mContext,
                WifiService.NOTIFICATION_NETWORK_ALERTS)
                .setAutoCancel(true)
                .setTimeoutAfter(CANCEL_TIMEOUT_MILLISECONDS)
                .setSmallIcon(Icon.createWithResource(mContext.getWifiOverlayApkPkgName(),
                        com.android.wifi.resources.R.drawable.stat_notify_wifi_in_range))
                .setContentTitle(mContext.getString(
                        com.android.wifi.resources.R.string.wifi_available_title_failed_to_connect))
                .setContentText(errorMessage)
                .setStyle(new Notification.BigTextStyle().bigText(errorMessage))
                .setContentIntent(mFrameworkFacade.getActivity(
                        mContext, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT))
                .setColor(mContext.getResources().getColor(
                        android.R.color.system_notification_accent_color));
        mNotificationManager.notify(NOTIFICATION_ID, builder.build());
        mCurrentShownSsid = ssid;
    }

    /**
     *  Returns the resources from the given context for the MCC/MNC
     *  associated with the subscription.
     */
    private Resources getResourcesForSubId(WifiContext context, int subId) {
        Context resourceContext = null;
        try {
            resourceContext = context.createPackageContext(
                    context.getWifiOverlayApkPkgName(), 0);
        } catch (PackageManager.NameNotFoundException ex) {
            return null;
        }

        return SubscriptionManager.getResourcesForSubId(resourceContext, subId);
    }

    /**
     * Allow tests to modify mCurrentShownSsid
     */
    @VisibleForTesting
    void setCurrentShownSsid(String currentShownSsid) {
        mCurrentShownSsid = currentShownSsid;
    }
}
