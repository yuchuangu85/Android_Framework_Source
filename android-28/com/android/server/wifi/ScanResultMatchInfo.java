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
    public static final int NETWORK_TYPE_OPEN = 0;
    public static final int NETWORK_TYPE_WEP = 1;
    public static final int NETWORK_TYPE_PSK = 2;
    public static final int NETWORK_TYPE_EAP = 3;

    /**
     * SSID of the network.
     */
    public String networkSsid;
    /**
     * Security Type of the network.
     */
    public int networkType;

    /**
     * Get the ScanResultMatchInfo for the given WifiConfiguration
     */
    public static ScanResultMatchInfo fromWifiConfiguration(WifiConfiguration config) {
        ScanResultMatchInfo info = new ScanResultMatchInfo();
        info.networkSsid = config.SSID;
        if (WifiConfigurationUtil.isConfigForPskNetwork(config)) {
            info.networkType = NETWORK_TYPE_PSK;
        } else if (WifiConfigurationUtil.isConfigForEapNetwork(config)) {
            info.networkType = NETWORK_TYPE_EAP;
        } else if (WifiConfigurationUtil.isConfigForWepNetwork(config)) {
            info.networkType = NETWORK_TYPE_WEP;
        } else if (WifiConfigurationUtil.isConfigForOpenNetwork(config)) {
            info.networkType = NETWORK_TYPE_OPEN;
        } else {
            throw new IllegalArgumentException("Invalid WifiConfiguration: " + config);
        }
        return info;
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
        if (ScanResultUtil.isScanResultForPskNetwork(scanResult)) {
            info.networkType = NETWORK_TYPE_PSK;
        } else if (ScanResultUtil.isScanResultForEapNetwork(scanResult)) {
            info.networkType = NETWORK_TYPE_EAP;
        } else if (ScanResultUtil.isScanResultForWepNetwork(scanResult)) {
            info.networkType = NETWORK_TYPE_WEP;
        } else if (ScanResultUtil.isScanResultForOpenNetwork(scanResult)) {
            info.networkType = NETWORK_TYPE_OPEN;
        } else {
            throw new IllegalArgumentException("Invalid ScanResult: " + scanResult);
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
        return Objects.equals(networkSsid, other.networkSsid)
                && networkType == other.networkType;
    }

    @Override
    public int hashCode() {
        return Objects.hash(networkSsid, networkType);
    }

    @Override
    public String toString() {
        return "ScanResultMatchInfo: " + networkSsid + ", type: " + networkType;
    }
}
