/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.server.wifi.util;

import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.wifi.ScanDetail;
import com.android.server.wifi.hotspot2.NetworkDetail;

/**
 * Scan result utility for any {@link ScanResult} related operations.
 * Currently contains:
 *   > Helper method for converting a ScanResult to a ScanDetail.
 *     Only fields that are supported in ScanResult are copied.
 *   > Helper methods to identify the encryption of a ScanResult.
 */
public class ScanResultUtil {
    private ScanResultUtil() { /* not constructable */ }

    /**
     * This method should only be used when the informationElements field in the provided scan
     * result is filled in with the IEs from the beacon.
     */
    public static ScanDetail toScanDetail(ScanResult scanResult) {
        NetworkDetail networkDetail = new NetworkDetail(scanResult.BSSID,
                scanResult.informationElements, scanResult.anqpLines, scanResult.frequency);
        return new ScanDetail(scanResult, networkDetail);
    }

    /**
     * Helper method to check if the provided |scanResult| corresponds to a PSK network or not.
     * This checks if the provided capabilities string contains PSK encryption type or not.
     */
    public static boolean isScanResultForPskNetwork(ScanResult scanResult) {
        return scanResult.capabilities.contains("PSK");
    }

    /**
     * Helper method to check if the provided |scanResult| corresponds to a EAP network or not.
     * This checks if the provided capabilities string contains EAP encryption type or not.
     */
    public static boolean isScanResultForEapNetwork(ScanResult scanResult) {
        return scanResult.capabilities.contains("EAP");
    }

    /**
     * Helper method to check if the provided |scanResult| corresponds to a WEP network or not.
     * This checks if the provided capabilities string contains WEP encryption type or not.
     */
    public static boolean isScanResultForWepNetwork(ScanResult scanResult) {
        return scanResult.capabilities.contains("WEP");
    }

    /**
     * Helper method to check if the provided |scanResult| corresponds to an open network or not.
     * This checks if the provided capabilities string does not contain either of WEP, PSK or EAP
     * encryption types or not.
     */
    public static boolean isScanResultForOpenNetwork(ScanResult scanResult) {
        return !(isScanResultForWepNetwork(scanResult) || isScanResultForPskNetwork(scanResult)
                || isScanResultForEapNetwork(scanResult));
    }

    /**
     * Helper method to quote the SSID in Scan result to use for comparing/filling SSID stored in
     * WifiConfiguration object.
     */
    @VisibleForTesting
    public static String createQuotedSSID(String ssid) {
        return "\"" + ssid + "\"";
    }

    /**
     * Creates a network configuration object using the provided |scanResult|.
     * This is used to create ephemeral network configurations.
     */
    public static WifiConfiguration createNetworkFromScanResult(ScanResult scanResult) {
        WifiConfiguration config = new WifiConfiguration();
        config.SSID = createQuotedSSID(scanResult.SSID);
        setAllowedKeyManagementFromScanResult(scanResult, config);
        return config;
    }

    /**
     * Sets the {@link WifiConfiguration#allowedKeyManagement} field on the given
     * {@link WifiConfiguration} based on its corresponding {@link ScanResult}.
     */
    public static void setAllowedKeyManagementFromScanResult(ScanResult scanResult,
            WifiConfiguration config) {
        if (isScanResultForPskNetwork(scanResult)) {
            config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
        } else if (isScanResultForEapNetwork(scanResult)) {
            config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_EAP);
            config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.IEEE8021X);
        } else if (isScanResultForWepNetwork(scanResult)) {
            config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
            config.allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.OPEN);
            config.allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.SHARED);
        } else {
            config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
        }
    }
}
