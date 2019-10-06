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

import static android.net.wifi.WifiManager.WIFI_FEATURE_CONTROL_ROAMING;

import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayList;

/**
 * This class provides helper functions for Wifi connectivity related modules to
 * access WifiNative. It starts with firmware roaming. TODO(b/34819513): Move operations
 * such as connection to network and legacy framework roaming here.
 *
 * NOTE: This class is not thread safe and should only be used from the ClientModeImpl thread.
 */
public class WifiConnectivityHelper {
    private static final String TAG = "WifiConnectivityHelper";
    @VisibleForTesting
    public static int INVALID_LIST_SIZE = -1;
    private final WifiNative mWifiNative;
    private boolean mFirmwareRoamingSupported = false;
    private int mMaxNumBlacklistBssid = INVALID_LIST_SIZE;
    private int mMaxNumWhitelistSsid = INVALID_LIST_SIZE;

    WifiConnectivityHelper(WifiNative wifiNative) {
        mWifiNative = wifiNative;
    }

    /**
     * Query firmware if it supports
     * {@link android.net.wifi.WifiManager#WIFI_FEATURE_CONTROL_ROAMING}. If yes, get the firmware
     * roaming capabilities. If firmware roaming is supported but we fail to get the roaming
     * capabilities or the returned capability values are invalid, we fall back to framework
     * roaming.
     *
     * @return true if succeed, false if firmware roaming is supported but fail to get valid
     * roaming capabilities.
     */
    public boolean getFirmwareRoamingInfo() {
        mFirmwareRoamingSupported = false;
        mMaxNumBlacklistBssid = INVALID_LIST_SIZE;
        mMaxNumWhitelistSsid = INVALID_LIST_SIZE;

        long fwFeatureSet =
                mWifiNative.getSupportedFeatureSet(mWifiNative.getClientInterfaceName());
        Log.d(TAG, "Firmware supported feature set: " + Long.toHexString(fwFeatureSet));

        if ((fwFeatureSet & WIFI_FEATURE_CONTROL_ROAMING) == 0) {
            Log.d(TAG, "Firmware roaming is not supported");
            return true;
        }

        WifiNative.RoamingCapabilities roamingCap = new WifiNative.RoamingCapabilities();
        if (mWifiNative.getRoamingCapabilities(mWifiNative.getClientInterfaceName(), roamingCap)) {
            if (roamingCap.maxBlacklistSize < 0 || roamingCap.maxWhitelistSize < 0) {
                Log.e(TAG, "Invalid firmware roaming capabilities: max num blacklist bssid="
                        + roamingCap.maxBlacklistSize + " max num whitelist ssid="
                        + roamingCap.maxWhitelistSize);
            } else {
                mFirmwareRoamingSupported = true;
                mMaxNumBlacklistBssid = roamingCap.maxBlacklistSize;
                mMaxNumWhitelistSsid = roamingCap.maxWhitelistSize;
                Log.d(TAG, "Firmware roaming supported with capabilities: max num blacklist bssid="
                        + mMaxNumBlacklistBssid + " max num whitelist ssid="
                        + mMaxNumWhitelistSsid);
                return true;
            }
        } else {
            Log.e(TAG, "Failed to get firmware roaming capabilities");
        }

        return false;
    }

    /**
     * Return if firmware roaming is supported.
     */
    public boolean isFirmwareRoamingSupported() {
        return mFirmwareRoamingSupported;
    }

    /**
     * Get the maximum size of BSSID blacklist firmware supports.
     *
     * @return INVALID_LIST_SIZE if firmware roaming is not supported, or
     * maximum size of the BSSID blacklist firmware supports.
     */
    public int getMaxNumBlacklistBssid() {
        if (mFirmwareRoamingSupported) {
            return mMaxNumBlacklistBssid;
        } else {
            Log.e(TAG, "getMaxNumBlacklistBssid: Firmware roaming is not supported");
            return INVALID_LIST_SIZE;
        }
    }

    /**
     * Get the maximum size of SSID whitelist firmware supports.
     *
     * @return INVALID_LIST_SIZE if firmware roaming is not supported, or
     * maximum size of the SSID whitelist firmware supports.
     */
    public int getMaxNumWhitelistSsid() {
        if (mFirmwareRoamingSupported) {
            return mMaxNumWhitelistSsid;
        } else {
            Log.e(TAG, "getMaxNumWhitelistSsid: Firmware roaming is not supported");
            return INVALID_LIST_SIZE;
        }
    }

    /**
     * Write firmware roaming configuration to firmware.
     *
     * @param blacklistBssids BSSIDs to be blacklisted
     * @param whitelistSsids  SSIDs to be whitelisted
     * @return true if succeeded, false otherwise.
     */
    public boolean setFirmwareRoamingConfiguration(ArrayList<String> blacklistBssids,
            ArrayList<String> whitelistSsids) {
        if (!mFirmwareRoamingSupported) {
            Log.e(TAG, "Firmware roaming is not supported");
            return false;
        }

        if (blacklistBssids == null || whitelistSsids == null) {
            Log.e(TAG, "Invalid firmware roaming configuration settings");
            return false;
        }

        int blacklistSize = blacklistBssids.size();
        int whitelistSize = whitelistSsids.size();

        if (blacklistSize > mMaxNumBlacklistBssid || whitelistSize > mMaxNumWhitelistSsid) {
            Log.e(TAG, "Invalid BSSID blacklist size " + blacklistSize + " SSID whitelist size "
                    + whitelistSize + ". Max blacklist size: " + mMaxNumBlacklistBssid
                    + ", max whitelist size: " + mMaxNumWhitelistSsid);
            return false;
        }

        WifiNative.RoamingConfig roamConfig = new WifiNative.RoamingConfig();
        roamConfig.blacklistBssids = blacklistBssids;
        roamConfig.whitelistSsids = whitelistSsids;

        return mWifiNative.configureRoaming(mWifiNative.getClientInterfaceName(), roamConfig);
    }

    /**
     * Remove the request |networkId| from supplicant if it's the current network,
     * if the current configured network matches |networkId|.
     *
     * @param networkId network id of the network to be removed from supplicant.
     */
    public void removeNetworkIfCurrent(int networkId) {
        mWifiNative.removeNetworkIfCurrent(mWifiNative.getClientInterfaceName(), networkId);
    }
}
