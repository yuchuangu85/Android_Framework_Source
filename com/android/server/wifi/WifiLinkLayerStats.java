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

import android.util.SparseArray;

import java.util.Arrays;

/**
 * A class representing link layer statistics collected over a Wifi Interface.
 */

/**
 * {@hide}
 */
public class WifiLinkLayerStats {
    public static final String V1_0 = "V1_0";
    public static final String V1_3 = "V1_3";

    /** The version of hal StaLinkLayerStats **/
    public String version;

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
     * Cumulative milliseconds when radio is awake due to nan scan
     */
    public int on_time_nan_scan = -1;
    /**
     * Cumulative milliseconds when radio is awake due to background scan
     */
    public int on_time_background_scan = -1;
    /**
     * Cumulative milliseconds when radio is awake due to roam scan
     */
    public int on_time_roam_scan = -1;
    /**
     * Cumulative milliseconds when radio is awake due to pno scan
     */
    public int on_time_pno_scan = -1;
    /**
     * Cumulative milliseconds when radio is awake due to hotspot 2.0 scan amd GAS exchange
     */
    public int on_time_hs20_scan = -1;
    /**
     * channel stats
     */
    public static class ChannelStats {
        /**
         * Channel frequency in MHz;
         */
        public int frequency;
        /**
         * Cumulative milliseconds radio is awake on this channel
         */
        public int radioOnTimeMs;
        /**
         * Cumulative milliseconds CCA is held busy on this channel
         */
        public int ccaBusyTimeMs;
    }
    /**
     * Channel stats list
     */
    public final SparseArray<ChannelStats> channelStatsMap = new SparseArray<>();

    /**
     * TimeStamp - absolute milliseconds from boot when these stats were sampled.
     */
    public long timeStampInMs;

    @Override
    public String toString() {
        StringBuilder sbuf = new StringBuilder();
        sbuf.append(" WifiLinkLayerStats: ").append('\n');

        sbuf.append(" version of StaLinkLayerStats: ").append(version).append('\n');
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
                .append(" tx_time=").append(Integer.toString(this.tx_time))
                .append(" rx_time=").append(Integer.toString(this.rx_time))
                .append(" scan_time=").append(Integer.toString(this.on_time_scan)).append('\n')
                .append(" nan_scan_time=")
                .append(Integer.toString(this.on_time_nan_scan)).append('\n')
                .append(" g_scan_time=")
                .append(Integer.toString(this.on_time_background_scan)).append('\n')
                .append(" roam_scan_time=")
                .append(Integer.toString(this.on_time_roam_scan)).append('\n')
                .append(" pno_scan_time=")
                .append(Integer.toString(this.on_time_pno_scan)).append('\n')
                .append(" hs2.0_scan_time=")
                .append(Integer.toString(this.on_time_hs20_scan)).append('\n')
                .append(" tx_time_per_level=" + Arrays.toString(tx_time_per_level)).append('\n');
        int numChanStats = this.channelStatsMap.size();
        sbuf.append(" Number of channel stats=").append(numChanStats).append('\n');
        for (int i = 0; i < numChanStats; ++i) {
            ChannelStats channelStatsEntry = this.channelStatsMap.valueAt(i);
            sbuf.append(" Frequency=").append(channelStatsEntry.frequency)
                    .append(" radioOnTimeMs=").append(channelStatsEntry.radioOnTimeMs)
                    .append(" ccaBusyTimeMs=").append(channelStatsEntry.ccaBusyTimeMs).append('\n');
        }
        sbuf.append(" ts=" + timeStampInMs);
        return sbuf.toString();
    }

}
