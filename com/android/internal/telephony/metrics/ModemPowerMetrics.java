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
package com.android.internal.telephony.metrics;

import android.os.BatteryStats;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.connectivity.CellularBatteryStats;
import android.text.format.DateUtils;

import com.android.internal.app.IBatteryStats;
import com.android.internal.telephony.nano.TelephonyProto.ModemPowerStats;

/**
 * ModemPowerMetrics holds the modem power metrics and converts them to ModemPowerStats proto buf.
 * This proto buf is included in the Telephony proto buf.
 */
public class ModemPowerMetrics {

    /* BatteryStats API */
    private final IBatteryStats mBatteryStats;

    public ModemPowerMetrics() {
        mBatteryStats = IBatteryStats.Stub.asInterface(ServiceManager.getService(
            BatteryStats.SERVICE_NAME));
    }

    /**
     * Build ModemPowerStats proto
     * @return ModemPowerStats
     */
    public ModemPowerStats buildProto() {
        ModemPowerStats m = new ModemPowerStats();
        CellularBatteryStats stats = getStats();
        if (stats != null) {
            m.loggingDurationMs = stats.getLoggingDurationMs();
            m.energyConsumedMah = stats.getEnergyConsumedMaMs()
                / ((double) DateUtils.HOUR_IN_MILLIS);
            m.numPacketsTx = stats.getNumPacketsTx();
            m.cellularKernelActiveTimeMs = stats.getKernelActiveTimeMs();
            if (stats.getTimeInRxSignalStrengthLevelMs() != null
                && stats.getTimeInRxSignalStrengthLevelMs().length > 0) {
                m.timeInVeryPoorRxSignalLevelMs = stats.getTimeInRxSignalStrengthLevelMs()[0];
            }
            m.sleepTimeMs = stats.getSleepTimeMs();
            m.idleTimeMs = stats.getIdleTimeMs();
            m.rxTimeMs = stats.getRxTimeMs();
            long[] t = stats.getTxTimeMs();
            m.txTimeMs = new long[t.length];
            for (int i = 0; i < t.length; i++) {
                m.txTimeMs[i] = t[i];
            }
        }
        return m;
    }

    /**
     * Get cellular stats from batterystats
     * @return CellularBatteryStats
     */
    private CellularBatteryStats getStats() {
        try {
            return mBatteryStats.getCellularBatteryStats();
        } catch (RemoteException e) {
        }
        return null;
    }
}
