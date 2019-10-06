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

import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;

import com.android.server.wifi.util.ScanResultUtil;

import java.util.Objects;

/**
 * Class to store the info needed to match a scan result to the provided network configuration.
 */
public class ScanResultMatchInfo {
    /**
     * SSID of the network.
     */
    public String networkSsid;
    /**
     * Security Type of the network.
     */
    public @WifiConfiguration.SecurityType int networkType;
    /**
     * Special flag for PSK-SAE in transition mode
     */
    public boolean pskSaeInTransitionMode;
    /**
     * Special flag for OWE in transition mode
     */
    public boolean oweInTransitionMode;

    /**
     * Fetch network type from network configuration.
     */
    public static @WifiConfiguration.SecurityType int getNetworkType(WifiConfiguration config) {
        if (WifiConfigurationUtil.isConfigForSaeNetwork(config)) {
            return WifiConfiguration.SECURITY_TYPE_SAE;
        } else if (WifiConfigurationUtil.isConfigForPskNetwork(config)) {
            return WifiConfiguration.SECURITY_TYPE_PSK;
        } else if (WifiConfigurationUtil.isConfigForEapNetwork(config)) {
            return WifiConfiguration.SECURITY_TYPE_EAP;
        } else if (WifiConfigurationUtil.isConfigForEapSuiteBNetwork(config)) {
            return WifiConfiguration.SECURITY_TYPE_EAP_SUITE_B;
        } else if (WifiConfigurationUtil.isConfigForWepNetwork(config)) {
            return WifiConfiguration.SECURITY_TYPE_WEP;
        } else if (WifiConfigurationUtil.isConfigForOweNetwork(config)) {
            return WifiConfiguration.SECURITY_TYPE_OWE;
        } else if (WifiConfigurationUtil.isConfigForOpenNetwork(config)) {
            return WifiConfiguration.SECURITY_TYPE_OPEN;
        }
        throw new IllegalArgumentException("Invalid WifiConfiguration: " + config);
    }

    /**
     * Get the ScanResultMatchInfo for the given WifiConfiguration
     */
    public static ScanResultMatchInfo fromWifiConfiguration(WifiConfiguration config) {
        ScanResultMatchInfo info = new ScanResultMatchInfo();
        info.networkSsid = config.SSID;
        info.networkType = getNetworkType(config);
        return info;
    }

    /**
     * Fetch network type from scan result.
     */
    public static @WifiConfiguration.SecurityType int getNetworkType(ScanResult scanResult) {
        if (ScanResultUtil.isScanResultForSaeNetwork(scanResult)) {
            return WifiConfiguration.SECURITY_TYPE_SAE;
        } else if (ScanResultUtil.isScanResultForPskNetwork(scanResult)) {
            return WifiConfiguration.SECURITY_TYPE_PSK;
        } else if (ScanResultUtil.isScanResultForEapSuiteBNetwork(scanResult)) {
            return WifiConfiguration.SECURITY_TYPE_EAP_SUITE_B;
        } else if (ScanResultUtil.isScanResultForEapNetwork(scanResult)) {
            return WifiConfiguration.SECURITY_TYPE_EAP;
        } else if (ScanResultUtil.isScanResultForWepNetwork(scanResult)) {
            return WifiConfiguration.SECURITY_TYPE_WEP;
        } else if (ScanResultUtil.isScanResultForOweNetwork(scanResult)) {
            return WifiConfiguration.SECURITY_TYPE_OWE;
        } else if (ScanResultUtil.isScanResultForOpenNetwork(scanResult)) {
            return WifiConfiguration.SECURITY_TYPE_OPEN;
        } else {
            throw new IllegalArgumentException("Invalid ScanResult: " + scanResult);
        }
    }

    /**
     * Get the ScanResultMatchInfo for the given ScanResult
     */
    public static ScanResultMatchInfo fromScanResult(ScanResult scanResult) {
        ScanResultMatchInfo info = new ScanResultMatchInfo();
        // Scan result ssid's are not quoted, hence add quotes.
        // TODO: This matching algo works only if the scan result contains a string SSID.
        // However, according to our public documentation ths {@link WifiConfiguration#SSID} can
        // either have a hex string or quoted ASCII string SSID.
        info.networkSsid = ScanResultUtil.createQuotedSSID(scanResult.SSID);
        info.networkType = getNetworkType(scanResult);
        info.oweInTransitionMode = false;
        info.pskSaeInTransitionMode = false;
        if (info.networkType == WifiConfiguration.SECURITY_TYPE_SAE) {
            // Note that scan result util will always choose the highest security protocol.
            info.pskSaeInTransitionMode =
                    ScanResultUtil.isScanResultForPskSaeTransitionNetwork(scanResult);
        } else  if (info.networkType == WifiConfiguration.SECURITY_TYPE_OWE) {
            // Note that scan result util will always choose OWE.
            info.oweInTransitionMode =
                    ScanResultUtil.isScanResultForOweTransitionNetwork(scanResult);
        }
        return info;
    }

    @Override
    public boolean equals(Object otherObj) {
        if (this == otherObj) {
            return true;
        } else if (!(otherObj instanceof ScanResultMatchInfo)) {
            return false;
        }
        ScanResultMatchInfo other = (ScanResultMatchInfo) otherObj;
        if (!Objects.equals(networkSsid, other.networkSsid)) {
            return false;
        }
        boolean networkTypeEquals;

        // Detect <SSID, PSK+SAE> scan result and say it is equal to <SSID, PSK> configuration
        if (other.pskSaeInTransitionMode && networkType == WifiConfiguration.SECURITY_TYPE_PSK
                || (pskSaeInTransitionMode
                && other.networkType == WifiConfiguration.SECURITY_TYPE_PSK)) {
            networkTypeEquals = true;
        } else if ((networkType == WifiConfiguration.SECURITY_TYPE_OPEN
                && other.oweInTransitionMode) || (oweInTransitionMode
                && other.networkType == WifiConfiguration.SECURITY_TYPE_OPEN)) {
            // Special case we treat Enhanced Open and Open as equals. This is done to support the
            // case where a saved network is Open but we found an OWE in transition network.
            networkTypeEquals = true;
        } else {
            networkTypeEquals = networkType == other.networkType;
        }
        return networkTypeEquals;
    }

    @Override
    public int hashCode() {
        return Objects.hash(networkSsid);
    }

    @Override
    public String toString() {
        return "ScanResultMatchInfo: ssid: " + networkSsid + ", type: " + networkType;
    }
}
