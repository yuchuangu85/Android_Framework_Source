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

package com.android.settingslib.wifi;

import static android.net.wifi.WifiConfiguration.NetworkSelectionStatus.NETWORK_SELECTION_ENABLED;
import static android.net.wifi.WifiConfiguration.NetworkSelectionStatus.getMaxNetworkSelectionDisableReason;

import android.content.Context;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiConfiguration.NetworkSelectionStatus;
import android.net.wifi.WifiInfo;
import android.os.SystemClock;

import androidx.annotation.VisibleForTesting;

import com.android.settingslib.R;

import java.util.Map;

public class WifiUtils {

    private static final int INVALID_RSSI = -127;

    static final int[] WIFI_PIE = {
            com.android.internal.R.drawable.ic_wifi_signal_0,
            com.android.internal.R.drawable.ic_wifi_signal_1,
            com.android.internal.R.drawable.ic_wifi_signal_2,
            com.android.internal.R.drawable.ic_wifi_signal_3,
            com.android.internal.R.drawable.ic_wifi_signal_4
    };

    static final int[] NO_INTERNET_WIFI_PIE = {
            R.drawable.ic_no_internet_wifi_signal_0,
            R.drawable.ic_no_internet_wifi_signal_1,
            R.drawable.ic_no_internet_wifi_signal_2,
            R.drawable.ic_no_internet_wifi_signal_3,
            R.drawable.ic_no_internet_wifi_signal_4
    };

    public static String buildLoggingSummary(AccessPoint accessPoint, WifiConfiguration config) {
        final StringBuilder summary = new StringBuilder();
        final WifiInfo info = accessPoint.getInfo();
        // Add RSSI/band information for this config, what was seen up to 6 seconds ago
        // verbose WiFi Logging is only turned on thru developers settings
        if (accessPoint.isActive() && info != null) {
            summary.append(" f=" + Integer.toString(info.getFrequency()));
        }
        summary.append(" " + getVisibilityStatus(accessPoint));
        if (config != null
                && (config.getNetworkSelectionStatus().getNetworkSelectionStatus()
                        != NETWORK_SELECTION_ENABLED)) {
            summary.append(" (" + config.getNetworkSelectionStatus().getNetworkStatusString());
            if (config.getNetworkSelectionStatus().getDisableTime() > 0) {
                long now = System.currentTimeMillis();
                long diff = (now - config.getNetworkSelectionStatus().getDisableTime()) / 1000;
                long sec = diff % 60; //seconds
                long min = (diff / 60) % 60; //minutes
                long hour = (min / 60) % 60; //hours
                summary.append(", ");
                if (hour > 0) summary.append(Long.toString(hour) + "h ");
                summary.append(Long.toString(min) + "m ");
                summary.append(Long.toString(sec) + "s ");
            }
            summary.append(")");
        }

        if (config != null) {
            NetworkSelectionStatus networkStatus = config.getNetworkSelectionStatus();
            for (int reason = 0; reason <= getMaxNetworkSelectionDisableReason(); reason++) {
                if (networkStatus.getDisableReasonCounter(reason) != 0) {
                    summary.append(" ")
                            .append(NetworkSelectionStatus
                                    .getNetworkSelectionDisableReasonString(reason))
                            .append("=")
                            .append(networkStatus.getDisableReasonCounter(reason));
                }
            }
        }

        return summary.toString();
    }

    /**
     * Returns the visibility status of the WifiConfiguration.
     *
     * @return autojoin debugging information
     * TODO: use a string formatter
     * ["rssi 5Ghz", "num results on 5GHz" / "rssi 5Ghz", "num results on 5GHz"]
     * For instance [-40,5/-30,2]
     */
    @VisibleForTesting
    static String getVisibilityStatus(AccessPoint accessPoint) {
        final WifiInfo info = accessPoint.getInfo();
        StringBuilder visibility = new StringBuilder();
        StringBuilder scans24GHz = new StringBuilder();
        StringBuilder scans5GHz = new StringBuilder();
        StringBuilder scans60GHz = new StringBuilder();
        String bssid = null;

        if (accessPoint.isActive() && info != null) {
            bssid = info.getBSSID();
            if (bssid != null) {
                visibility.append(" ").append(bssid);
            }
            visibility.append(" standard = ").append(info.getWifiStandard());
            visibility.append(" rssi=").append(info.getRssi());
            visibility.append(" ");
            visibility.append(" score=").append(info.getScore());
            if (accessPoint.getSpeed() != AccessPoint.Speed.NONE) {
                visibility.append(" speed=").append(accessPoint.getSpeedLabel());
            }
            visibility.append(String.format(" tx=%.1f,", info.getSuccessfulTxPacketsPerSecond()));
            visibility.append(String.format("%.1f,", info.getRetriedTxPacketsPerSecond()));
            visibility.append(String.format("%.1f ", info.getLostTxPacketsPerSecond()));
            visibility.append(String.format("rx=%.1f", info.getSuccessfulRxPacketsPerSecond()));
        }

        int maxRssi5 = INVALID_RSSI;
        int maxRssi24 = INVALID_RSSI;
        int maxRssi60 = INVALID_RSSI;
        final int maxDisplayedScans = 4;
        int num5 = 0; // number of scanned BSSID on 5GHz band
        int num24 = 0; // number of scanned BSSID on 2.4Ghz band
        int num60 = 0; // number of scanned BSSID on 60Ghz band
        int numBlockListed = 0;

        // TODO: sort list by RSSI or age
        long nowMs = SystemClock.elapsedRealtime();
        for (ScanResult result : accessPoint.getScanResults()) {
            if (result == null) {
                continue;
            }
            if (result.frequency >= AccessPoint.LOWER_FREQ_5GHZ
                    && result.frequency <= AccessPoint.HIGHER_FREQ_5GHZ) {
                // Strictly speaking: [4915, 5825]
                num5++;

                if (result.level > maxRssi5) {
                    maxRssi5 = result.level;
                }
                if (num5 <= maxDisplayedScans) {
                    scans5GHz.append(
                            verboseScanResultSummary(accessPoint, result, bssid,
                                    nowMs));
                }
            } else if (result.frequency >= AccessPoint.LOWER_FREQ_24GHZ
                    && result.frequency <= AccessPoint.HIGHER_FREQ_24GHZ) {
                // Strictly speaking: [2412, 2482]
                num24++;

                if (result.level > maxRssi24) {
                    maxRssi24 = result.level;
                }
                if (num24 <= maxDisplayedScans) {
                    scans24GHz.append(
                            verboseScanResultSummary(accessPoint, result, bssid,
                                    nowMs));
                }
            } else if (result.frequency >= AccessPoint.LOWER_FREQ_60GHZ
                    && result.frequency <= AccessPoint.HIGHER_FREQ_60GHZ) {
                // Strictly speaking: [60000, 61000]
                num60++;

                if (result.level > maxRssi60) {
                    maxRssi60 = result.level;
                }
                if (num60 <= maxDisplayedScans) {
                    scans60GHz.append(
                            verboseScanResultSummary(accessPoint, result, bssid,
                                    nowMs));
                }
            }
        }
        visibility.append(" [");
        if (num24 > 0) {
            visibility.append("(").append(num24).append(")");
            if (num24 > maxDisplayedScans) {
                visibility.append("max=").append(maxRssi24).append(",");
            }
            visibility.append(scans24GHz.toString());
        }
        visibility.append(";");
        if (num5 > 0) {
            visibility.append("(").append(num5).append(")");
            if (num5 > maxDisplayedScans) {
                visibility.append("max=").append(maxRssi5).append(",");
            }
            visibility.append(scans5GHz.toString());
        }
        visibility.append(";");
        if (num60 > 0) {
            visibility.append("(").append(num60).append(")");
            if (num60 > maxDisplayedScans) {
                visibility.append("max=").append(maxRssi60).append(",");
            }
            visibility.append(scans60GHz.toString());
        }
        if (numBlockListed > 0) {
            visibility.append("!").append(numBlockListed);
        }
        visibility.append("]");

        return visibility.toString();
    }

    @VisibleForTesting
    /* package */ static String verboseScanResultSummary(AccessPoint accessPoint, ScanResult result,
            String bssid, long nowMs) {
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append(" \n{").append(result.BSSID);
        if (result.BSSID.equals(bssid)) {
            stringBuilder.append("*");
        }
        stringBuilder.append("=").append(result.frequency);
        stringBuilder.append(",").append(result.level);
        int speed = getSpecificApSpeed(result, accessPoint.getScoredNetworkCache());
        if (speed != AccessPoint.Speed.NONE) {
            stringBuilder.append(",")
                    .append(accessPoint.getSpeedLabel(speed));
        }
        int ageSeconds = (int) (nowMs - result.timestamp / 1000) / 1000;
        stringBuilder.append(",").append(ageSeconds).append("s");
        stringBuilder.append("}");
        return stringBuilder.toString();
    }

    @AccessPoint.Speed
    private static int getSpecificApSpeed(ScanResult result,
            Map<String, TimestampedScoredNetwork> scoredNetworkCache) {
        TimestampedScoredNetwork timedScore = scoredNetworkCache.get(result.BSSID);
        if (timedScore == null) {
            return AccessPoint.Speed.NONE;
        }
        // For debugging purposes we may want to use mRssi rather than result.level as the average
        // speed wil be determined by mRssi
        return timedScore.getScore().calculateBadge(result.level);
    }

    public static String getMeteredLabel(Context context, WifiConfiguration config) {
        // meteredOverride is whether the user manually set the metered setting or not.
        // meteredHint is whether the network itself is telling us that it is metered
        if (config.meteredOverride == WifiConfiguration.METERED_OVERRIDE_METERED
                || (config.meteredHint && !isMeteredOverridden(config))) {
            return context.getString(R.string.wifi_metered_label);
        }
        return context.getString(R.string.wifi_unmetered_label);
    }

    /**
     * Returns the Internet icon resource for a given RSSI level.
     *
     * @param level The number of bars to show (0-4)
     * @param noInternet True if a connected Wi-Fi network cannot access the Internet
     * @throws IllegalArgumentException if an invalid RSSI level is given.
     */
    public static int getInternetIconResource(int level, boolean noInternet) {
        if (level < 0 || level >= WIFI_PIE.length) {
            throw new IllegalArgumentException("No Wifi icon found for level: " + level);
        }
        return noInternet ? NO_INTERNET_WIFI_PIE[level] : WIFI_PIE[level];
    }

    public static boolean isMeteredOverridden(WifiConfiguration config) {
        return config.meteredOverride != WifiConfiguration.METERED_OVERRIDE_NONE;
    }
}
