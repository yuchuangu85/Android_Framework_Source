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

import android.os.BatteryStats;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.connectivity.WifiBatteryStats;
import android.text.format.DateUtils;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.IBatteryStats;
import com.android.server.wifi.nano.WifiMetricsProto.WifiPowerStats;
import com.android.server.wifi.nano.WifiMetricsProto.WifiRadioUsage;

import java.io.PrintWriter;
import java.text.DecimalFormat;

/**
 * WifiPowerMetrics holds the wifi power metrics and converts them to WifiPowerStats proto buf.
 * This proto buf is included in the Wifi proto buf.
 */
public class WifiPowerMetrics {

    private static final String TAG = "WifiPowerMetrics";

    /* BatteryStats API */
    private final IBatteryStats mBatteryStats;

    public WifiPowerMetrics() {
        mBatteryStats = IBatteryStats.Stub.asInterface(ServiceManager.getService(
                BatteryStats.SERVICE_NAME));
    }

    // This constructor injects IBatteryStats and should be used for testing only.
    @VisibleForTesting
    public WifiPowerMetrics(IBatteryStats batteryStats) {
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
            m.loggingDurationMs = stats.getLoggingDurationMs();
            m.energyConsumedMah = stats.getEnergyConsumedMaMs()
                / ((double) DateUtils.HOUR_IN_MILLIS);
            m.idleTimeMs = stats.getIdleTimeMs();
            m.rxTimeMs = stats.getRxTimeMs();
            m.txTimeMs = stats.getTxTimeMs();
            m.wifiKernelActiveTimeMs = stats.getKernelActiveTimeMs();
            m.numPacketsTx = stats.getNumPacketsTx();
            m.numBytesTx = stats.getNumBytesTx();
            m.numPacketsRx = stats.getNumPacketsRx();
            m.numBytesRx = stats.getNumPacketsRx();
            m.sleepTimeMs = stats.getSleepTimeMs();
            m.scanTimeMs = stats.getScanTimeMs();
            m.monitoredRailEnergyConsumedMah = stats.getMonitoredRailChargeConsumedMaMs()
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
            m.loggingDurationMs = stats.getLoggingDurationMs();
            m.scanTimeMs = stats.getScanTimeMs();
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
        try {
            return mBatteryStats.getWifiBatteryStats();
        } catch (RemoteException e) {
            Log.e(TAG, "Unable to obtain Wifi power stats from BatteryStats");
        }
        return null;
    }
}
