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

import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.WifiConfiguration;
import android.os.Handler;
import android.os.Process;
import android.util.Log;

import com.android.internal.messages.nano.SystemMessageProto.SystemMessage;
import com.android.wifi.resources.R;

/**
 * This class may be used to launch notifications when wifi connections fail.
 */
public class ConnectionFailureNotifier {
    private static final String TAG = "ConnectionFailureNotifier";

    private Context mContext;
    private WifiInjector mWifiInjector;
    private FrameworkFacade mFrameworkFacade;
    private WifiConfigManager mWifiConfigManager;
    private WifiConnectivityManager mWifiConnectivityManager;
    private NotificationManager mNotificationManager;
    private Handler mHandler;
    private ConnectionFailureNotificationBuilder mConnectionFailureNotificationBuilder;

    public ConnectionFailureNotifier(
            Context context, WifiInjector wifiInjector, FrameworkFacade framework,
            WifiConfigManager wifiConfigManager, WifiConnectivityManager wifiConnectivityManager,
            Handler handler) {
        mContext = context;
        mWifiInjector = wifiInjector;
        mFrameworkFacade = framework;
        mWifiConfigManager = wifiConfigManager;
        mWifiConnectivityManager = wifiConnectivityManager;
        mNotificationManager = mWifiInjector.getNotificationManager();
        mHandler = handler;
        mConnectionFailureNotificationBuilder =
                mWifiInjector.getConnectionFailureNotificationBuilder();

        IntentFilter filter = new IntentFilter();
        filter.addAction(ConnectionFailureNotificationBuilder
                .ACTION_SHOW_SET_RANDOMIZATION_DETAILS);
        mContext.registerReceiver(
                new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        String action = intent.getAction();
                        if (action.equals(ConnectionFailureNotificationBuilder
                                .ACTION_SHOW_SET_RANDOMIZATION_DETAILS)) {
                            int networkId = intent.getIntExtra(
                                    ConnectionFailureNotificationBuilder
                                            .RANDOMIZATION_SETTINGS_NETWORK_ID,
                                    WifiConfiguration.INVALID_NETWORK_ID);
                            String ssidAndSecurityType = intent.getStringExtra(
                                    ConnectionFailureNotificationBuilder
                                            .RANDOMIZATION_SETTINGS_NETWORK_SSID);
                            showRandomizationSettingsDialog(networkId, ssidAndSecurityType);
                        }
                    }
                }, filter);
    }

    /**
     * Shows a notification which will bring up a dialog which offers the user an option to disable
     * MAC randomization on |networkdId|.
     * @param networkId
     */
    public void showFailedToConnectDueToNoRandomizedMacSupportNotification(int networkId) {
        WifiConfiguration config = mWifiConfigManager.getConfiguredNetwork(networkId);
        if (config == null) {
            return;
        }
        Notification notification = mConnectionFailureNotificationBuilder
                .buildNoMacRandomizationSupportNotification(config);
        mNotificationManager.notify(SystemMessage.NOTE_NETWORK_NO_MAC_RANDOMIZATION_SUPPORT,
                notification);
    }

    class DisableMacRandomizationListener implements DialogInterface.OnClickListener {
        private WifiConfiguration mConfig;

        DisableMacRandomizationListener(WifiConfiguration config) {
            mConfig = config;
        }

        @Override
        public void onClick(DialogInterface dialog, int which) {
            mHandler.post(() -> {
                mConfig.macRandomizationSetting =
                        WifiConfiguration.RANDOMIZATION_NONE;
                mWifiConfigManager.addOrUpdateNetwork(mConfig, Process.SYSTEM_UID);
                WifiConfiguration updatedConfig =
                        mWifiConfigManager.getConfiguredNetwork(mConfig.networkId);
                if (updatedConfig.macRandomizationSetting
                        == WifiConfiguration.RANDOMIZATION_NONE) {
                    String message = mContext.getResources().getString(
                            R.string.wifi_disable_mac_randomization_dialog_success);
                    mFrameworkFacade.showToast(mContext, message);
                    mWifiConfigManager.enableNetwork(updatedConfig.networkId, true,
                            Process.SYSTEM_UID, null);
                    mWifiConnectivityManager.forceConnectivityScan(
                            ClientModeImpl.WIFI_WORK_SOURCE);
                } else {
                    // Shouldn't ever fail, but here for completeness
                    String message = mContext.getResources().getString(
                            R.string.wifi_disable_mac_randomization_dialog_failure);
                    mFrameworkFacade.showToast(mContext, message);
                    Log.e(TAG, "Failed to modify mac randomization setting");
                }
            });
        }
    }

    /**
     * Class to show a AlertDialog which notifies the user of a network not being privacy
     * compliant and then suggests an action.
     */
    private void showRandomizationSettingsDialog(int networkId, String ssidAndSecurityType) {
        WifiConfiguration config = mWifiConfigManager.getConfiguredNetwork(networkId);
        // Make sure the networkId is still pointing to the correct WifiConfiguration since
        // there might be a large time gap between when the notification shows and when
        // it's tapped.
        if (config == null || ssidAndSecurityType == null
                || !ssidAndSecurityType.equals(config.getSsidAndSecurityTypeString())) {
            String message = mContext.getResources().getString(
                    R.string.wifi_disable_mac_randomization_dialog_network_not_found);
            mFrameworkFacade.showToast(mContext, message);
            return;
        }

        AlertDialog dialog = mConnectionFailureNotificationBuilder
                .buildChangeMacRandomizationSettingDialog(config.SSID,
                        new DisableMacRandomizationListener(config));
        dialog.show();
    }
}
