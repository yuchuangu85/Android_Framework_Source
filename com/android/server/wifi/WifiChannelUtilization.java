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

import static android.net.wifi.WifiManager.DEVICE_MOBILITY_STATE_STATIONARY;
import static android.net.wifi.WifiManager.DEVICE_MOBILITY_STATE_UNKNOWN;

import android.content.Context;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager.DeviceMobilityState;
import android.util.Log;
import android.util.SparseArray;
import android.util.SparseIntArray;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.wifi.WifiLinkLayerStats.ChannelStats;
import com.android.server.wifi.util.InformationElementUtil.BssLoad;
import com.android.wifi.resources.R;

import java.util.ArrayDeque;
import java.util.Iterator;

/**
 * This class collects channel stats over a Wifi Interface
 * and calculates channel utilization using the latest and cached channel stats.
 * Cache saves previous readings of channel stats in a FIFO.
 * The cache is updated when a new stats arrives and it has been a long while since the last update.
 * To get more statistically sound channel utilization, for these devices which support
 * mobility state report, the cache update is stopped when the device stays in the stationary state.
 */
public class WifiChannelUtilization {
    private static final String TAG = "WifiChannelUtilization";
    private static boolean sVerboseLoggingEnabled = false;
    public static final int UNKNOWN_FREQ = -1;
    // Invalidate the utilization value if it is larger than the following value.
    // This is to detect and mitigate the incorrect HW reports of ccaBusy/OnTime.
    // It is reasonable to assume that utilization ratio in the real life is never beyond this value
    // given by all the inter-frame-spacings (IFS)
    static final int UTILIZATION_RATIO_MAX = BssLoad.MAX_CHANNEL_UTILIZATION * 94 / 100;
    // Minimum time interval in ms between two cache updates.
    @VisibleForTesting
    static final int DEFAULT_CACHE_UPDATE_INTERVAL_MIN_MS = 10 * 60 * 1000;
    // To get valid channel utilization, the time difference between the reference chanStat's
    // radioOnTime and current chanStat's radioOntime should be no less than the following value
    @VisibleForTesting
    static final int RADIO_ON_TIME_DIFF_MIN_MS = 250;
    // The number of chanStatsMap readings saved in cache
    // where each reading corresponds to one link layer stats update.
    @VisibleForTesting
    static final int CHANNEL_STATS_CACHE_SIZE = 5;
    private final Clock mClock;
    private final Context mContext;
    private @DeviceMobilityState int mDeviceMobilityState = DEVICE_MOBILITY_STATE_UNKNOWN;
    private int mCacheUpdateIntervalMinMs = DEFAULT_CACHE_UPDATE_INTERVAL_MIN_MS;

    // Map frequency (key) to utilization ratio (value) with the valid range of
    // [BssLoad.MIN_CHANNEL_UTILIZATION, BssLoad.MAX_CHANNEL_UTILIZATION],
    // where MIN_CHANNEL_UTILIZATION corresponds to ratio 0%
    // and MAX_CHANNEL_UTILIZATION corresponds to ratio 100%
    private SparseIntArray mChannelUtilizationMap = new SparseIntArray();
    private ArrayDeque<SparseArray<ChannelStats>> mChannelStatsMapCache = new ArrayDeque<>();
    private long mLastChannelStatsMapTimeStamp;
    private int mLastChannelStatsMapMobilityState;

    WifiChannelUtilization(Clock clock, Context context) {
        mContext = context;
        mClock = clock;
    }

    /**
     * Enable/Disable verbose logging.
     * @param verbose true to enable and false to disable.
     */
    public void enableVerboseLogging(boolean verbose) {
        sVerboseLoggingEnabled = verbose;
    }

    /**
     * (Re)initialize internal variables and status
     * @param wifiLinkLayerStats The latest wifi link layer stats
     */
    public void init(WifiLinkLayerStats wifiLinkLayerStats) {
        mChannelUtilizationMap.clear();
        mChannelStatsMapCache.clear();
        mDeviceMobilityState = DEVICE_MOBILITY_STATE_UNKNOWN;
        mLastChannelStatsMapMobilityState = DEVICE_MOBILITY_STATE_UNKNOWN;
        for (int i = 0; i < (CHANNEL_STATS_CACHE_SIZE - 1); ++i) {
            mChannelStatsMapCache.addFirst(new SparseArray<>());
        }
        if (wifiLinkLayerStats != null) {
            mChannelStatsMapCache.addFirst(wifiLinkLayerStats.channelStatsMap);
        } else {
            mChannelStatsMapCache.addFirst(new SparseArray<>());
        }
        mLastChannelStatsMapTimeStamp = mClock.getElapsedSinceBootMillis();
        if (sVerboseLoggingEnabled) {
            Log.d(TAG, "initializing");
        }
    }

    /**
     * Set channel stats cache update minimum interval
     */
    public void setCacheUpdateIntervalMs(int cacheUpdateIntervalMinMs) {
        mCacheUpdateIntervalMinMs = cacheUpdateIntervalMinMs;
    }

    /**
     * Get channel utilization ratio for a given frequency
     * @param frequency The center frequency of 20MHz WLAN channel
     * @return Utilization ratio value if it is available; BssLoad.INVALID otherwise
     */
    public int getUtilizationRatio(int frequency) {
        if (mContext.getResources().getBoolean(
                R.bool.config_wifiChannelUtilizationOverrideEnabled)) {
            if (ScanResult.is24GHz(frequency)) {
                return mContext.getResources().getInteger(
                        R.integer.config_wifiChannelUtilizationOverride2g);
            }
            if (ScanResult.is5GHz(frequency)) {
                return mContext.getResources().getInteger(
                        R.integer.config_wifiChannelUtilizationOverride5g);
            }
            return mContext.getResources().getInteger(
                        R.integer.config_wifiChannelUtilizationOverride6g);
        }
        return mChannelUtilizationMap.get(frequency, BssLoad.INVALID);
    }

    /**
     * Update device mobility state
     * @param newState the new device mobility state
     */
    public void setDeviceMobilityState(@DeviceMobilityState int newState) {
        mDeviceMobilityState = newState;
        if (sVerboseLoggingEnabled) {
            Log.d(TAG, " update device mobility state to " + newState);
        }
    }

    /**
     * Set channel utilization ratio for a given frequency
     * @param frequency The center frequency of 20MHz channel
     * @param utilizationRatio The utilization ratio of 20MHz channel
     */
    public void setUtilizationRatio(int frequency, int utilizationRatio) {
        mChannelUtilizationMap.put(frequency, utilizationRatio);
    }

    /**
     * Update channel utilization with the latest link layer stats and the cached channel stats
     * and then update channel stats cache
     * If the given frequency is UNKNOWN_FREQ, calculate channel utilization of all frequencies
     * Otherwise, calculate the channel utilization of the given frequency
     * @param wifiLinkLayerStats The latest wifi link layer stats
     * @param frequency Current frequency of network.
     */
    public void refreshChannelStatsAndChannelUtilization(WifiLinkLayerStats wifiLinkLayerStats,
            int frequency) {
        if (mContext.getResources().getBoolean(
                R.bool.config_wifiChannelUtilizationOverrideEnabled)) {
            return;
        }

        if (wifiLinkLayerStats == null) {
            return;
        }
        SparseArray<ChannelStats>  channelStatsMap = wifiLinkLayerStats.channelStatsMap;
        if (channelStatsMap == null) {
            return;
        }
        if (frequency != UNKNOWN_FREQ) {
            ChannelStats channelStats = channelStatsMap.get(frequency, null);
            if (channelStats != null) calculateChannelUtilization(channelStats);
        } else {
            for (int i = 0; i < channelStatsMap.size(); i++) {
                ChannelStats channelStats = channelStatsMap.valueAt(i);
                calculateChannelUtilization(channelStats);
            }
        }
        updateChannelStatsCache(channelStatsMap, frequency);
    }

    private void calculateChannelUtilization(ChannelStats channelStats) {
        int freq = channelStats.frequency;
        int ccaBusyTimeMs = channelStats.ccaBusyTimeMs;
        int radioOnTimeMs = channelStats.radioOnTimeMs;

        ChannelStats channelStatsRef = findChanStatsReference(freq, radioOnTimeMs);
        int busyTimeDiff = ccaBusyTimeMs - channelStatsRef.ccaBusyTimeMs;
        int radioOnTimeDiff = radioOnTimeMs - channelStatsRef.radioOnTimeMs;
        int utilizationRatio = BssLoad.INVALID;
        if (radioOnTimeDiff >= RADIO_ON_TIME_DIFF_MIN_MS && busyTimeDiff >= 0) {
            utilizationRatio = calculateUtilizationRatio(radioOnTimeDiff, busyTimeDiff);
        }
        mChannelUtilizationMap.put(freq, utilizationRatio);

        if (sVerboseLoggingEnabled) {
            int utilizationRatioT0 = calculateUtilizationRatio(radioOnTimeMs, ccaBusyTimeMs);
            StringBuilder sb = new StringBuilder();
            Log.d(TAG, sb.append(" freq: ").append(freq)
                    .append(" onTime: ").append(radioOnTimeMs)
                    .append(" busyTime: ").append(ccaBusyTimeMs)
                    .append(" onTimeDiff: ").append(radioOnTimeDiff)
                    .append(" busyTimeDiff: ").append(busyTimeDiff)
                    .append(" utilization: ").append(utilizationRatio)
                    .append(" utilization t0: ").append(utilizationRatioT0)
                    .toString());
        }
    }
    /**
     * Find a proper channelStats reference from channelStatsMap cache.
     * The search continues until it finds a channelStat at the given frequency with radioOnTime
     * sufficiently smaller than current radioOnTime, or there is no channelStats for the given
     * frequency or it reaches the end of cache.
     * @param freq Frequency of current channel
     * @param radioOnTimeMs The latest radioOnTime of current channel
     * @return the found channelStat reference if search succeeds, or a dummy channelStats with time
     * zero if channelStats is not found for the given frequency, or a dummy channelStats with the
     * latest radioOnTimeMs if it reaches the end of cache.
     */
    private ChannelStats findChanStatsReference(int freq, int radioOnTimeMs) {
        // A dummy channelStats with the latest radioOnTimeMs.
        ChannelStats channelStatsCurrRadioOnTime = new ChannelStats();
        channelStatsCurrRadioOnTime.radioOnTimeMs = radioOnTimeMs;
        Iterator iterator = mChannelStatsMapCache.iterator();
        while (iterator.hasNext()) {
            SparseArray<ChannelStats> channelStatsMap = (SparseArray<ChannelStats>) iterator.next();
            // If the freq can't be found in current channelStatsMap, stop search because it won't
            // appear in older ones either due to the fact that channelStatsMap are accumulated
            // in HW and thus a recent reading should have channels no less than old readings.
            // Return a dummy channelStats with zero radioOnTimeMs
            if (channelStatsMap == null || channelStatsMap.get(freq) == null) {
                return new ChannelStats();
            }
            ChannelStats channelStats = channelStatsMap.get(freq);
            int radioOnTimeDiff = radioOnTimeMs - channelStats.radioOnTimeMs;
            if (radioOnTimeDiff >= RADIO_ON_TIME_DIFF_MIN_MS) {
                return channelStats;
            }
        }
        return channelStatsCurrRadioOnTime;
    }

    private int calculateUtilizationRatio(int radioOnTimeDiff, int busyTimeDiff) {
        if (radioOnTimeDiff > 0) {
            int utilizationRatio = busyTimeDiff * BssLoad.MAX_CHANNEL_UTILIZATION / radioOnTimeDiff;
            return (utilizationRatio > UTILIZATION_RATIO_MAX) ? BssLoad.INVALID : utilizationRatio;
        } else {
            return BssLoad.INVALID;
        }
    }

    private void updateChannelStatsCache(SparseArray<ChannelStats> channelStatsMap, int freq) {
        // Update cache if it hits one of following conditions
        // 1) it has been a long while since the last update and device doesn't remain stationary
        // 2) cache is empty
        boolean remainStationary =
                mLastChannelStatsMapMobilityState == DEVICE_MOBILITY_STATE_STATIONARY
                && mDeviceMobilityState == DEVICE_MOBILITY_STATE_STATIONARY;
        long currTimeStamp = mClock.getElapsedSinceBootMillis();
        boolean isLongTimeSinceLastUpdate =
                (currTimeStamp - mLastChannelStatsMapTimeStamp) >= mCacheUpdateIntervalMinMs;
        if ((isLongTimeSinceLastUpdate && !remainStationary) || isChannelStatsMapCacheEmpty(freq)) {
            mChannelStatsMapCache.addFirst(channelStatsMap);
            mChannelStatsMapCache.removeLast();
            mLastChannelStatsMapTimeStamp = currTimeStamp;
            mLastChannelStatsMapMobilityState = mDeviceMobilityState;
        }
    }

    private boolean isChannelStatsMapCacheEmpty(int freq) {
        SparseArray<ChannelStats> channelStatsMap = mChannelStatsMapCache.peekFirst();
        if (channelStatsMap == null || channelStatsMap.size() == 0) return true;
        if (freq != UNKNOWN_FREQ && channelStatsMap.get(freq) == null) return true;
        return false;
    }
}
