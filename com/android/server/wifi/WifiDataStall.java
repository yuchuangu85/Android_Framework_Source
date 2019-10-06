/*
 * Copyright 2018 The Android Open Source Project
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

import android.content.Context;
import android.provider.Settings;

import com.android.server.wifi.nano.WifiMetricsProto.WifiIsUnusableEvent;

/**
 * Looks for Wifi data stalls
 */
public class WifiDataStall {

    // Default minimum number of txBadDelta to trigger data stall
    public static final int MIN_TX_BAD_DEFAULT = 1;
    // Default minimum number of txSuccessDelta to trigger data stall
    // when rxSuccessDelta is 0
    public static final int MIN_TX_SUCCESS_WITHOUT_RX_DEFAULT = 50;
    // Maximum time gap between two WifiLinkLayerStats to trigger a data stall
    public static final long MAX_MS_DELTA_FOR_DATA_STALL = 60 * 1000; // 1 minute

    private final Context mContext;
    private final FrameworkFacade mFacade;
    private final WifiMetrics mWifiMetrics;

    private int mMinTxBad;
    private int mMinTxSuccessWithoutRx;

    public WifiDataStall(Context context, FrameworkFacade facade, WifiMetrics wifiMetrics) {
        mContext = context;
        mFacade = facade;
        mWifiMetrics = wifiMetrics;
        loadSettings();
    }

    /**
     * Load setting values related to wifi data stall.
     */
    public void loadSettings() {
        mMinTxBad = mFacade.getIntegerSetting(
                mContext, Settings.Global.WIFI_DATA_STALL_MIN_TX_BAD, MIN_TX_BAD_DEFAULT);
        mMinTxSuccessWithoutRx = mFacade.getIntegerSetting(
                mContext, Settings.Global.WIFI_DATA_STALL_MIN_TX_SUCCESS_WITHOUT_RX,
                MIN_TX_SUCCESS_WITHOUT_RX_DEFAULT);
        mWifiMetrics.setWifiDataStallMinTxBad(mMinTxBad);
        mWifiMetrics.setWifiDataStallMinRxWithoutTx(mMinTxSuccessWithoutRx);
    }

    /**
     * Checks for data stall by looking at tx/rx packet counts
     * @param oldStats second most recent WifiLinkLayerStats
     * @param newStats most recent WifiLinkLayerStats
     * @return trigger type of WifiIsUnusableEvent
     */
    public int checkForDataStall(WifiLinkLayerStats oldStats, WifiLinkLayerStats newStats) {
        if (oldStats == null || newStats == null) {
            mWifiMetrics.resetWifiIsUnusableLinkLayerStats();
            return WifiIsUnusableEvent.TYPE_UNKNOWN;
        }

        long txSuccessDelta = (newStats.txmpdu_be + newStats.txmpdu_bk
                + newStats.txmpdu_vi + newStats.txmpdu_vo)
                - (oldStats.txmpdu_be + oldStats.txmpdu_bk
                + oldStats.txmpdu_vi + oldStats.txmpdu_vo);
        long txRetriesDelta = (newStats.retries_be + newStats.retries_bk
                + newStats.retries_vi + newStats.retries_vo)
                - (oldStats.retries_be + oldStats.retries_bk
                + oldStats.retries_vi + oldStats.retries_vo);
        long txBadDelta = (newStats.lostmpdu_be + newStats.lostmpdu_bk
                + newStats.lostmpdu_vi + newStats.lostmpdu_vo)
                - (oldStats.lostmpdu_be + oldStats.lostmpdu_bk
                + oldStats.lostmpdu_vi + oldStats.lostmpdu_vo);
        long rxSuccessDelta = (newStats.rxmpdu_be + newStats.rxmpdu_bk
                + newStats.rxmpdu_vi + newStats.rxmpdu_vo)
                - (oldStats.rxmpdu_be + oldStats.rxmpdu_bk
                + oldStats.rxmpdu_vi + oldStats.rxmpdu_vo);
        long timeMsDelta = newStats.timeStampInMs - oldStats.timeStampInMs;

        if (timeMsDelta < 0
                || txSuccessDelta < 0
                || txRetriesDelta < 0
                || txBadDelta < 0
                || rxSuccessDelta < 0) {
            // There was a reset in WifiLinkLayerStats
            mWifiMetrics.resetWifiIsUnusableLinkLayerStats();
            return WifiIsUnusableEvent.TYPE_UNKNOWN;
        }

        mWifiMetrics.updateWifiIsUnusableLinkLayerStats(txSuccessDelta, txRetriesDelta,
                txBadDelta, rxSuccessDelta, timeMsDelta);
        if (timeMsDelta < MAX_MS_DELTA_FOR_DATA_STALL) {
            // There is a data stall if there are too many tx failures
            // or if we are not receiving any packets despite many tx successes
            boolean dataStallBadTx = (txBadDelta >= mMinTxBad);
            boolean dataStallTxSuccessWithoutRx =
                    (rxSuccessDelta == 0 && txSuccessDelta >= mMinTxSuccessWithoutRx);
            if (dataStallBadTx && dataStallTxSuccessWithoutRx) {
                mWifiMetrics.logWifiIsUnusableEvent(WifiIsUnusableEvent.TYPE_DATA_STALL_BOTH);
                return WifiIsUnusableEvent.TYPE_DATA_STALL_BOTH;
            } else if (dataStallBadTx) {
                mWifiMetrics.logWifiIsUnusableEvent(WifiIsUnusableEvent.TYPE_DATA_STALL_BAD_TX);
                return WifiIsUnusableEvent.TYPE_DATA_STALL_BAD_TX;
            } else if (dataStallTxSuccessWithoutRx) {
                mWifiMetrics.logWifiIsUnusableEvent(
                        WifiIsUnusableEvent.TYPE_DATA_STALL_TX_WITHOUT_RX);
                return WifiIsUnusableEvent.TYPE_DATA_STALL_TX_WITHOUT_RX;
            }
        }

        return WifiIsUnusableEvent.TYPE_UNKNOWN;
    }
}
