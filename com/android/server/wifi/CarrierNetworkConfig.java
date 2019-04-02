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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.EAPConstants;
import android.net.wifi.WifiEnterpriseConfig;
import android.os.PersistableBundle;
import android.telephony.CarrierConfigManager;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.util.Base64;
import android.util.Log;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Class for maintaining/caching carrier Wi-Fi network configurations.
 */
public class CarrierNetworkConfig {
    private static final String TAG = "CarrierNetworkConfig";

    private static final String NETWORK_CONFIG_SEPARATOR = ",";
    private static final int ENCODED_SSID_INDEX = 0;
    private static final int EAP_TYPE_INDEX = 1;
    private static final int CONFIG_ELEMENT_SIZE = 2;

    private final Map<String, NetworkInfo> mCarrierNetworkMap;

    public CarrierNetworkConfig(Context context) {
        mCarrierNetworkMap = new HashMap<>();
        updateNetworkConfig(context);

        // Monitor for carrier config changes.
        IntentFilter filter = new IntentFilter();
        filter.addAction(CarrierConfigManager.ACTION_CARRIER_CONFIG_CHANGED);
        context.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                updateNetworkConfig(context);
            }
        }, filter);
    }

    /**
     * @return true if the given SSID is associated with a carrier network
     */
    public boolean isCarrierNetwork(String ssid) {
        return mCarrierNetworkMap.containsKey(ssid);
    }

    /**
     * @return the EAP type associated with a carrier AP, or -1 if the specified AP
     * is not associated with a carrier network
     */
    public int getNetworkEapType(String ssid) {
        NetworkInfo info = mCarrierNetworkMap.get(ssid);
        return info == null ? -1 : info.mEapType;
    }

    /**
     * @return the name of carrier associated with a carrier AP, or null if the specified AP
     * is not associated with a carrier network.
     */
    public String getCarrierName(String ssid) {
        NetworkInfo info = mCarrierNetworkMap.get(ssid);
        return info == null ? null : info.mCarrierName;
    }

    /**
     * Utility class for storing carrier network information.
     */
    private static class NetworkInfo {
        final int mEapType;
        final String mCarrierName;

        NetworkInfo(int eapType, String carrierName) {
            mEapType = eapType;
            mCarrierName = carrierName;
        }
    }

    /**
     * Update the carrier network map based on the current carrier configuration of the active
     * subscriptions.
     *
     * @param context Current application context
     */
    private void updateNetworkConfig(Context context) {
        // Reset network map.
        mCarrierNetworkMap.clear();

        CarrierConfigManager carrierConfigManager =
                (CarrierConfigManager) context.getSystemService(Context.CARRIER_CONFIG_SERVICE);
        if (carrierConfigManager == null) {
            return;
        }

        SubscriptionManager subscriptionManager = (SubscriptionManager) context.getSystemService(
                Context.TELEPHONY_SUBSCRIPTION_SERVICE);
        if (subscriptionManager == null) {
            return;
        }
        List<SubscriptionInfo> subInfoList = subscriptionManager.getActiveSubscriptionInfoList();
        if (subInfoList == null) {
            return;
        }

        // Process the carrier config for each active subscription.
        for (SubscriptionInfo subInfo : subInfoList) {
            processNetworkConfig(
                    carrierConfigManager.getConfigForSubId(subInfo.getSubscriptionId()),
                    subInfo.getDisplayName().toString());
        }
    }

    /**
     * Process the carrier network config, the network config string is formatted as follow:
     *
     * "[Base64 Encoded SSID],[EAP Type]"
     * Where EAP Type is the standard EAP method number, refer to
     * http://www.iana.org/assignments/eap-numbers/eap-numbers.xhtml for more info.

     * @param carrierConfig The bundle containing the carrier configuration
     * @param carrierName The display name of the associated carrier
     */
    private void processNetworkConfig(PersistableBundle carrierConfig, String carrierName) {
        if (carrierConfig == null) {
            return;
        }
        String[] networkConfigs = carrierConfig.getStringArray(
                CarrierConfigManager.KEY_CARRIER_WIFI_STRING_ARRAY);
        if (networkConfigs == null) {
            return;
        }

        for (String networkConfig : networkConfigs) {
            String[] configArr = networkConfig.split(NETWORK_CONFIG_SEPARATOR);
            if (configArr.length != CONFIG_ELEMENT_SIZE) {
                Log.e(TAG, "Ignore invalid config: " + networkConfig);
                continue;
            }
            try {
                String ssid = new String(Base64.decode(
                        configArr[ENCODED_SSID_INDEX], Base64.DEFAULT));
                int eapType = parseEapType(Integer.parseInt(configArr[EAP_TYPE_INDEX]));
                // Verify EAP type, must be a SIM based EAP type.
                if (eapType == -1) {
                    Log.e(TAG, "Invalid EAP type: " + configArr[EAP_TYPE_INDEX]);
                    continue;
                }
                mCarrierNetworkMap.put(ssid, new NetworkInfo(eapType, carrierName));
            } catch (NumberFormatException e) {
                Log.e(TAG, "Failed to parse EAP type: " + e.getMessage());
            } catch (IllegalArgumentException e) {
                Log.e(TAG, "Failed to decode SSID: " + e.getMessage());
            }
        }
    }

    /**
     * Convert a standard SIM-based EAP type (SIM, AKA, AKA') to the internal EAP type as defined in
     * {@link WifiEnterpriseConfig.Eap}. -1 will be returned if the given EAP type is not
     * SIM-based.
     *
     * @return SIM-based EAP type as defined in {@link WifiEnterpriseConfig.Eap}, or -1 if not
     * SIM-based EAP type
     */
    private static int parseEapType(int eapType) {
        if (eapType == EAPConstants.EAP_SIM) {
            return WifiEnterpriseConfig.Eap.SIM;
        } else if (eapType == EAPConstants.EAP_AKA) {
            return WifiEnterpriseConfig.Eap.AKA;
        } else if (eapType == EAPConstants.EAP_AKA_PRIME) {
            return WifiEnterpriseConfig.Eap.AKA_PRIME;
        }
        return -1;
    }
}
