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
package com.android.server.wifi;

import android.os.BatteryStatsManager;
import android.os.connectivity.WifiBatteryStats;
import android.text.format.DateUtils;

import com.android.server.wifi.proto.nano.WifiMetricsProto.WifiPowerStats;
import com.android.server.wifi.proto.nano.WifiMetricsProto.WifiRadioUsage;

import java.io.PrintWriter;
import java.text.DecimalFormat;

/**
 * WifiPowerMetrics holds the wifi power metrics and converts them to WifiPowerStats proto buf.
 * This proto buf is included in the Wifi proto buf.
 */
public class WifiPowerMetrics {

    private static final String TAG = "WifiPowerMetrics";

    /* BatteryStats API */
    private final BatteryStatsManager mBatteryStats;

    public WifiPowerMetrics(BatteryStatsManager batteryStats) {
        mBatteryStats = batteryStats;
    }

    /**
     * Build WifiPowerStats proto
     * A snapshot of Wifi statistics in Batterystats is obtained. Due to reboots multiple correlated
     * logs may be uploaded in a day. Resolution is on the server side. The log with longest
     * duration is picked.
     * @return WifiPowerStats
     */
    public WifiPowerStats buildProto() {
        WifiPowerStats m = new WifiPowerStats();
        WifiBatteryStats stats = getStats();
        if (stats != null) {
            m.loggingDurationMs = stats.getLoggingDurationMillis();
            m.energyConsumedMah = stats.getEnergyConsumedMaMillis()
                / ((double) DateUtils.HOUR_IN_MILLIS);
            m.idleTimeMs = stats.getIdleTimeMillis();
            m.rxTimeMs = stats.getRxTimeMillis();
            m.txTimeMs = stats.getTxTimeMillis();
            m.wifiKernelActiveTimeMs = stats.getKernelActiveTimeMillis();
            m.numPacketsTx = stats.getNumPacketsTx();
            m.numBytesTx = stats.getNumBytesTx();
            m.numPacketsRx = stats.getNumPacketsRx();
            m.numBytesRx = stats.getNumBytesRx();
            m.sleepTimeMs = stats.getSleepTimeMillis();
            m.scanTimeMs = stats.getScanTimeMillis();
            m.monitoredRailEnergyConsumedMah = stats.getMonitoredRailChargeConsumedMaMillis()
                    / ((double) DateUtils.HOUR_IN_MILLIS);
        }
        return m;
    }

    /**
     * Build WifiRadioUsage proto
     * A snapshot of Wifi statistics in Batterystats is obtained. Due to reboots multiple correlated
     * logs may be uploaded in a day. Server side should analyze based the ratio of collected
     * properties over the total logging duration (ie. |scanTimeMs| / |loggingDurationMs|)
     *
     * This proto contains additional wifi usage data that are not directly related to power
     * calculations.
     * @return WifiRadioUsage
     */
    public WifiRadioUsage buildWifiRadioUsageProto() {
        WifiRadioUsage m = new WifiRadioUsage();
        WifiBatteryStats stats = getStats();
        if (stats != null) {
            m.loggingDurationMs = stats.getLoggingDurationMillis();
            m.scanTimeMs = stats.getScanTimeMillis();
        }
        return m;
    }

    /**
     * Dump all WifiPowerStats to console (pw)
     * @param pw
     */
    public void dump(PrintWriter pw) {
        WifiPowerStats s = buildProto();
        if (s!=null) {
            pw.println("Wifi power metrics:");
            pw.println("Logging duration (time on battery): " + s.loggingDurationMs);
            pw.println("Energy consumed by wifi (mAh): " + s.energyConsumedMah);
            pw.println("Amount of time wifi is in idle (ms): " + s.idleTimeMs);
            pw.println("Amount of time wifi is in rx (ms): " + s.rxTimeMs);
            pw.println("Amount of time wifi is in tx (ms): " + s.txTimeMs);
            pw.println("Amount of time kernel is active because of wifi data (ms): "
                    + s.wifiKernelActiveTimeMs);
            pw.println("Amount of time wifi is in sleep (ms): " + s.sleepTimeMs);
            pw.println("Amount of time wifi is scanning (ms): " + s.scanTimeMs);
            pw.println("Number of packets sent (tx): " + s.numPacketsTx);
            pw.println("Number of bytes sent (tx): " + s.numBytesTx);
            pw.println("Number of packets received (rx): " + s.numPacketsRx);
            pw.println("Number of bytes sent (rx): " + s.numBytesRx);
            pw.println("Energy consumed across measured wifi rails (mAh): "
                    + new DecimalFormat("#.##").format(s.monitoredRailEnergyConsumedMah));
        }
        WifiRadioUsage wifiRadioUsage = buildWifiRadioUsageProto();
        pw.println("Wifi radio usage metrics:");
        pw.println("Logging duration (time on battery): " + wifiRadioUsage.loggingDurationMs);
        pw.println("Amount of time wifi is in scan mode while on battery (ms): "
                + wifiRadioUsage.scanTimeMs);
    }

    /**
     * Get wifi stats from batterystats
     * @return WifiBatteryStats
     */
    private WifiBatteryStats getStats() {
        return mBatteryStats.getWifiBatteryStats();
    }
}
