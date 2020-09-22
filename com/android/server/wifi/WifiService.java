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

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.util.Log;

import com.android.server.SystemService;
import com.android.server.wifi.util.WifiAsyncChannel;

import java.util.ArrayList;
import java.util.List;

/**
 * Service implementing Wi-Fi functionality. Delegates actual interface
 * implementation to WifiServiceImpl.
 */
public final class WifiService extends SystemService {

    private static final String TAG = "WifiService";
    // Notification channels used by the wifi service.
    public static final String NOTIFICATION_NETWORK_STATUS = "NETWORK_STATUS";
    public static final String NOTIFICATION_NETWORK_ALERTS = "NETWORK_ALERTS";
    public static final String NOTIFICATION_NETWORK_AVAILABLE = "NETWORK_AVAILABLE";

    private final WifiServiceImpl mImpl;
    private final WifiContext mWifiContext;

    public WifiService(Context contextBase) {
        super(contextBase);
        mWifiContext = new WifiContext(contextBase);
        WifiInjector injector = new WifiInjector(mWifiContext);
        WifiAsyncChannel channel =  new WifiAsyncChannel(TAG);
        mImpl = new WifiServiceImpl(mWifiContext, injector, channel);
    }

    @Override
    public void onStart() {
        Log.i(TAG, "Registering " + Context.WIFI_SERVICE);
        publishBinderService(Context.WIFI_SERVICE, mImpl);
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == SystemService.PHASE_SYSTEM_SERVICES_READY) {
            createNotificationChannels(mWifiContext);
            mImpl.checkAndStartWifi();
        } else if (phase == SystemService.PHASE_BOOT_COMPLETED) {
            mImpl.handleBootCompleted();
        }
    }

    @Override
    public void onUserSwitching(TargetUser from, TargetUser to) {
        mImpl.handleUserSwitch(to.getUserHandle().getIdentifier());
    }

    @Override
    public void onUserUnlocking(TargetUser user) {
        mImpl.handleUserUnlock(user.getUserHandle().getIdentifier());
    }

    @Override
    public void onUserStopping(TargetUser user) {
        mImpl.handleUserStop(user.getUserHandle().getIdentifier());
    }

    // Create notification channels used by wifi.
    private static void createNotificationChannels(Context ctx) {
        final NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        List<NotificationChannel> channelsList = new ArrayList<>();
        final NotificationChannel networkStatusChannel = new NotificationChannel(
                NOTIFICATION_NETWORK_STATUS,
                ctx.getResources().getString(
                        com.android.wifi.resources.R.string.notification_channel_network_status),
                NotificationManager.IMPORTANCE_LOW);
        channelsList.add(networkStatusChannel);

        final NotificationChannel networkAlertsChannel = new NotificationChannel(
                NOTIFICATION_NETWORK_ALERTS,
                ctx.getResources().getString(
                        com.android.wifi.resources.R.string.notification_channel_network_alerts),
                NotificationManager.IMPORTANCE_HIGH);
        networkAlertsChannel.setBlockable(true);
        channelsList.add(networkAlertsChannel);

        final NotificationChannel networkAvailable = new NotificationChannel(
                NOTIFICATION_NETWORK_AVAILABLE,
                ctx.getResources().getString(
                        com.android.wifi.resources.R.string.notification_channel_network_available),
                NotificationManager.IMPORTANCE_LOW);
        networkAvailable.setBlockable(true);
        channelsList.add(networkAvailable);

        nm.createNotificationChannels(channelsList);
    }
}
