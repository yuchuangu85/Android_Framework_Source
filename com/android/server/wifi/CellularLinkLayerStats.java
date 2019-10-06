/*
 * Copyright 2019 The Android Open Source Project
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

import android.telephony.SignalStrength;
import android.telephony.TelephonyManager;
import android.telephony.TelephonyManager.NetworkType;

/**
 * A class representing the link layer statistics of the primary registered cell
 * of cellular network
 */
public class CellularLinkLayerStats {
    /** Cellular data network type currently in use on the device for data transmission */
    private @NetworkType int mDataNetworkType =
            TelephonyManager.NETWORK_TYPE_UNKNOWN;
    /**
     * Cellular signal strength in dBm, NR: CsiRsrp, LTE: Rsrp, WCDMA/TDSCDMA: Rscp,
     * CDMA: Rssi, EVDO: Rssi, GSM: Rssi
     */
    private int mSignalStrengthDbm = SignalStrength.INVALID;
    /**
     * Cellular signal strength in dB, NR: CsiSinr, LTE: Rsrq, WCDMA: EcNo, TDSCDMA: invalid,
     * CDMA: Ecio, EVDO: SNR, GSM: invalid
     */
    private int mSignalStrengthDb = SignalStrength.INVALID;
    /** Whether it is a new or old registered cell */
    private boolean mIsSameRegisteredCell = false;

    public void setDataNetworkType(@NetworkType int dataNetworkType) {
        mDataNetworkType = dataNetworkType;
    }

    public void setSignalStrengthDbm(int signalStrengthDbm) {
        mSignalStrengthDbm = signalStrengthDbm;
    }

    public void setIsSameRegisteredCell(boolean isSameRegisteredCell) {
        mIsSameRegisteredCell = isSameRegisteredCell;
    }

    public void setSignalStrengthDb(int signalStrengthDb) {
        mSignalStrengthDb = signalStrengthDb;
    }

    public @NetworkType int getDataNetworkType() {
        return mDataNetworkType;
    }

    public boolean getIsSameRegisteredCell() {
        return mIsSameRegisteredCell;
    }

    public int getSignalStrengthDb() {
        return mSignalStrengthDb;
    }

    public int getSignalStrengthDbm() {
        return mSignalStrengthDbm;
    }

    @Override
    public String toString() {
        StringBuilder sbuf = new StringBuilder();
        sbuf.append(" CellularLinkLayerStats: ").append('\n')
                .append(" Data Network Type: ")
                .append(mDataNetworkType).append('\n')
                .append(" Signal Strength in dBm: ")
                .append(mSignalStrengthDbm).append('\n')
                .append(" Signal Strength in dB: ")
                .append(mSignalStrengthDb).append('\n')
                .append(" Is it the same registered cell? ")
                .append(mIsSameRegisteredCell).append('\n');
        return sbuf.toString();
    }
}
