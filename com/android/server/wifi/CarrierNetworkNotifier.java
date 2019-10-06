/*
 * Copyright (C) 2018 The Android Open Source Project
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

import android.content.Context;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiConfiguration.KeyMgmt;
import android.net.wifi.WifiEnterpriseConfig;
import android.net.wifi.WifiEnterpriseConfig.Eap;
import android.os.Looper;
import android.provider.Settings;

import com.android.internal.messages.nano.SystemMessageProto.SystemMessage;
import com.android.server.wifi.nano.WifiMetricsProto;

/**
 * This class handles the "carrier wi-fi network available" notification
 *
 * NOTE: These API's are not thread safe and should only be used from ClientModeImpl thread.
 */
public class CarrierNetworkNotifier extends AvailableNetworkNotifier {
    public static final String TAG = "WifiCarrierNetworkNotifier";
    private static final String STORE_DATA_IDENTIFIER = "CarrierNetworkNotifierBlacklist";
    private static final String TOGGLE_SETTINGS_NAME =
            Settings.Global.WIFI_CARRIER_NETWORKS_AVAILABLE_NOTIFICATION_ON;

    public CarrierNetworkNotifier(
            Context context,
            Looper looper,
            FrameworkFacade framework,
            Clock clock,
            WifiMetrics wifiMetrics,
            WifiConfigManager wifiConfigManager,
            WifiConfigStore wifiConfigStore,
            ClientModeImpl clientModeImpl,
            ConnectToNetworkNotificationBuilder connectToNetworkNotificationBuilder) {
        super(TAG, STORE_DATA_IDENTIFIER, TOGGLE_SETTINGS_NAME,
                SystemMessage.NOTE_CARRIER_NETWORK_AVAILABLE,
                WifiMetricsProto.ConnectionEvent.NOMINATOR_CARRIER,
                context, looper, framework, clock,
                wifiMetrics, wifiConfigManager, wifiConfigStore, clientModeImpl,
                connectToNetworkNotificationBuilder);
    }

    @Override
    WifiConfiguration createRecommendedNetworkConfig(ScanResult recommendedNetwork) {
        WifiConfiguration network = super.createRecommendedNetworkConfig(recommendedNetwork);

        int eapMethod = recommendedNetwork.carrierApEapType;
        if (eapMethod == Eap.SIM || eapMethod == Eap.AKA || eapMethod == Eap.AKA_PRIME) {
            network.allowedKeyManagement.set(KeyMgmt.WPA_EAP);
            network.allowedKeyManagement.set(KeyMgmt.IEEE8021X);
            network.enterpriseConfig = new WifiEnterpriseConfig();
            network.enterpriseConfig.setEapMethod(recommendedNetwork.carrierApEapType);
            network.enterpriseConfig.setIdentity("");
            network.enterpriseConfig.setAnonymousIdentity("");
        }

        return network;
    }
}
