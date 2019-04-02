/*
 * Copyright (C) 2015 The Android Open Source Project
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

import android.annotation.NonNull;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;

/**
 * Maps BSSIDs to their individual ScanDetails for a given WifiConfiguration.
 */
public class ScanDetailCache {

    private static final String TAG = "ScanDetailCache";
    private static final boolean DBG = false;

    private final WifiConfiguration mConfig;
    private final int mMaxSize;
    private final int mTrimSize;
    private final HashMap<String, ScanDetail> mMap;

    /**
     * Scan Detail cache associated with each configured network.
     *
     * The cache size is trimmed down to |trimSize| once it crosses the provided |maxSize|.
     * Since this operation is relatively expensive, ensure that |maxSize| and |trimSize| are not
     * too close to each other. |trimSize| should always be <= |maxSize|.
     *
     * @param config   WifiConfiguration object corresponding to the network.
     * @param maxSize  Max size desired for the cache.
     * @param trimSize Size to trim the cache down to once it reaches |maxSize|.
     */
    ScanDetailCache(WifiConfiguration config, int maxSize, int trimSize) {
        mConfig = config;
        mMaxSize = maxSize;
        mTrimSize = trimSize;
        mMap = new HashMap(16, 0.75f);
    }

    void put(ScanDetail scanDetail) {
        // First check if we have reached |maxSize|. if yes, trim it down to |trimSize|.
        if (mMap.size() >= mMaxSize) {
            trim();
        }

        mMap.put(scanDetail.getBSSIDString(), scanDetail);
    }

    /**
     * Get ScanResult object corresponding to the provided BSSID.
     *
     * @param bssid provided BSSID
     * @return {@code null} if no match ScanResult is found.
     */
    public ScanResult getScanResult(String bssid) {
        ScanDetail scanDetail = getScanDetail(bssid);
        return scanDetail == null ? null : scanDetail.getScanResult();
    }

    /**
     * Get ScanDetail object corresponding to the provided BSSID.
     *
     * @param bssid provided BSSID
     * @return {@code null} if no match ScanDetail is found.
     */
    public ScanDetail getScanDetail(@NonNull String bssid) {
        return mMap.get(bssid);
    }

    void remove(@NonNull String bssid) {
        mMap.remove(bssid);
    }

    int size() {
        return mMap.size();
    }

    boolean isEmpty() {
        return size() == 0;
    }

    Collection<String> keySet() {
        return mMap.keySet();
    }

    Collection<ScanDetail> values() {
        return mMap.values();
    }

    /**
     * Method to reduce the cache to |mTrimSize| size by removing the oldest entries.
     * TODO: Investigate if this method can be further optimized.
     */
    private void trim() {
        int currentSize = mMap.size();
        if (currentSize < mTrimSize) {
            return; // Nothing to trim
        }
        ArrayList<ScanDetail> list = new ArrayList<ScanDetail>(mMap.values());
        if (list.size() != 0) {
            // Sort by descending timestamp
            Collections.sort(list, new Comparator() {
                public int compare(Object o1, Object o2) {
                    ScanDetail a = (ScanDetail) o1;
                    ScanDetail b = (ScanDetail) o2;
                    if (a.getSeen() > b.getSeen()) {
                        return 1;
                    }
                    if (a.getSeen() < b.getSeen()) {
                        return -1;
                    }
                    return a.getBSSIDString().compareTo(b.getBSSIDString());
                }
            });
        }
        for (int i = 0; i < currentSize - mTrimSize; i++) {
            // Remove oldest results from scan cache
            ScanDetail result = list.get(i);
            mMap.remove(result.getBSSIDString());
        }
    }

    /* @hide */
    private ArrayList<ScanDetail> sort() {
        ArrayList<ScanDetail> list = new ArrayList<ScanDetail>(mMap.values());
        if (list.size() != 0) {
            Collections.sort(list, new Comparator() {
                public int compare(Object o1, Object o2) {
                    ScanResult a = ((ScanDetail) o1).getScanResult();
                    ScanResult b = ((ScanDetail) o2).getScanResult();
                    if (a.seen > b.seen) {
                        return -1;
                    }
                    if (a.seen < b.seen) {
                        return 1;
                    }
                    if (a.level > b.level) {
                        return -1;
                    }
                    if (a.level < b.level) {
                        return 1;
                    }
                    return a.BSSID.compareTo(b.BSSID);
                }
            });
        }
        return list;
    }

    @Override
    public String toString() {
        StringBuilder sbuf = new StringBuilder();
        sbuf.append("Scan Cache:  ").append('\n');

        ArrayList<ScanDetail> list = sort();
        long now_ms = System.currentTimeMillis();
        if (list.size() > 0) {
            for (ScanDetail scanDetail : list) {
                ScanResult result = scanDetail.getScanResult();
                long milli = now_ms - scanDetail.getSeen();
                long ageSec = 0;
                long ageMin = 0;
                long ageHour = 0;
                long ageMilli = 0;
                long ageDay = 0;
                if (now_ms > scanDetail.getSeen() && scanDetail.getSeen() > 0) {
                    ageMilli = milli % 1000;
                    ageSec   = (milli / 1000) % 60;
                    ageMin   = (milli / (60 * 1000)) % 60;
                    ageHour  = (milli / (60 * 60 * 1000)) % 24;
                    ageDay   = (milli / (24 * 60 * 60 * 1000));
                }
                sbuf.append("{").append(result.BSSID).append(",").append(result.frequency);
                sbuf.append(",").append(String.format("%3d", result.level));
                if (ageSec > 0 || ageMilli > 0) {
                    sbuf.append(String.format(",%4d.%02d.%02d.%02d.%03dms", ageDay,
                            ageHour, ageMin, ageSec, ageMilli));
                }
                sbuf.append("} ");
            }
            sbuf.append('\n');
        }

        return sbuf.toString();
    }

}
