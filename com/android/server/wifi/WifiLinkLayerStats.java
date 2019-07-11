/*
 * Copyright 2014 The Android Open Source Project
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

import java.util.Arrays;

/**
 * A class representing link layer statistics collected over a Wifi Interface.
 */

/**
 * {@hide}
 */
public class WifiLinkLayerStats {

    /** Number of beacons received from our own AP */
    public int beacon_rx;

    /** RSSI of management frames */
    public int rssi_mgmt;

    /* Packet counters */

    /** WME Best Effort Access Category received mpdu */
    public long rxmpdu_be;
    /** WME Best Effort Access Category transmitted mpdu */
    public long txmpdu_be;
    /** WME Best Effort Access Category lost mpdu */
    public long lostmpdu_be;
    /** WME Best Effort Access Category number of transmission retries */
    public long retries_be;

    /** WME Background Access Category received mpdu */
    public long rxmpdu_bk;
    /** WME Background Access Category transmitted mpdu */
    public long txmpdu_bk;
    /** WME Background Access Category lost mpdu */
    public long lostmpdu_bk;
    /** WME Background Access Category number of transmission retries */
    public long retries_bk;

    /** WME Video Access Category received mpdu */
    public long rxmpdu_vi;
    /** WME Video Access Category transmitted mpdu */
    public long txmpdu_vi;
    /** WME Video Access Category lost mpdu */
    public long lostmpdu_vi;
    /** WME Video Access Category number of transmission retries */
    public long retries_vi;

    /** WME Voice Access Category received mpdu */
    public long rxmpdu_vo;
    /** WME Voice Access Category transmitted mpdu */
    public long txmpdu_vo;
    /** WME Voice Access Category lost mpdu */
    public long lostmpdu_vo;
    /** WME Voice Access Category number of transmission retries */
    public long retries_vo;

    /**
     * Cumulative milliseconds when radio is awake
     */
    public int on_time;
    /**
     * Cumulative milliseconds of active transmission
     */
    public int tx_time;
    /**
     * Cumulative milliseconds per level of active transmission
     */
    public int[] tx_time_per_level;
    /**
     * Cumulative milliseconds of active receive
     */
    public int rx_time;
    /**
     * Cumulative milliseconds when radio is awake due to scan
     */
    public int on_time_scan;

    /**
     * TimeStamp - absolute milliseconds from boot when these stats were sampled.
     */
    public long timeStampInMs;

    @Override
    public String toString() {
        StringBuilder sbuf = new StringBuilder();
        sbuf.append(" WifiLinkLayerStats: ").append('\n');

        sbuf.append(" my bss beacon rx: ").append(Integer.toString(this.beacon_rx)).append('\n');
        sbuf.append(" RSSI mgmt: ").append(Integer.toString(this.rssi_mgmt)).append('\n');
        sbuf.append(" BE : ").append(" rx=").append(Long.toString(this.rxmpdu_be))
                .append(" tx=").append(Long.toString(this.txmpdu_be))
                .append(" lost=").append(Long.toString(this.lostmpdu_be))
                .append(" retries=").append(Long.toString(this.retries_be)).append('\n');
        sbuf.append(" BK : ").append(" rx=").append(Long.toString(this.rxmpdu_bk))
                .append(" tx=").append(Long.toString(this.txmpdu_bk))
                .append(" lost=").append(Long.toString(this.lostmpdu_bk))
                .append(" retries=").append(Long.toString(this.retries_bk)).append('\n');
        sbuf.append(" VI : ").append(" rx=").append(Long.toString(this.rxmpdu_vi))
                .append(" tx=").append(Long.toString(this.txmpdu_vi))
                .append(" lost=").append(Long.toString(this.lostmpdu_vi))
                .append(" retries=").append(Long.toString(this.retries_vi)).append('\n');
        sbuf.append(" VO : ").append(" rx=").append(Long.toString(this.rxmpdu_vo))
                .append(" tx=").append(Long.toString(this.txmpdu_vo))
                .append(" lost=").append(Long.toString(this.lostmpdu_vo))
                .append(" retries=").append(Long.toString(this.retries_vo)).append('\n');
        sbuf.append(" on_time : ").append(Integer.toString(this.on_time))
                .append(" rx_time=").append(Integer.toString(this.rx_time))
                .append(" scan_time=").append(Integer.toString(this.on_time_scan)).append('\n')
                .append(" tx_time=").append(Integer.toString(this.tx_time))
                .append(" tx_time_per_level=" + Arrays.toString(tx_time_per_level));
        sbuf.append(" ts=" + timeStampInMs);
        return sbuf.toString();
    }

}
