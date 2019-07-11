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

import com.android.internal.app.IBatteryStats;
import com.android.server.wifi.nano.WifiMetricsProto.WifiPowerStats;

import java.io.PrintWriter;

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
        }
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
