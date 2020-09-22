/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.wifi.util;

import android.annotation.NonNull;
import android.content.Context;
import android.net.wifi.WifiConfiguration;

import com.android.server.wifi.ScanResultMatchInfo;
import com.android.wifi.resources.R;

/**
 * Create a lru list to store the network connection order. sorted by most recently connected
 * first.
 */
public class LruConnectionTracker {
    private final LruList<ScanResultMatchInfo> mList;
    private final Context mContext;
    public LruConnectionTracker(int size, Context context) {
        mList = new LruList<>(size);
        mContext = context;
    }
    /**
     * Check if a WifiConfiguration is in the most recently connected list.
     */
    public boolean isMostRecentlyConnected(@NonNull WifiConfiguration config) {
        return getAgeIndexOfNetwork(config) < mContext.getResources()
                .getInteger(R.integer.config_wifiMaxPnoSsidCount);
    }

    /**
     * Add a WifiConfiguration into the most recently connected list.
     */
    public void addNetwork(@NonNull WifiConfiguration config) {
        mList.add(ScanResultMatchInfo.fromWifiConfiguration(config));
    }

    /**
     * Remove a network from the list.
     */
    public void removeNetwork(@NonNull WifiConfiguration config) {
        mList.remove(ScanResultMatchInfo.fromWifiConfiguration(config));
    }

    /**
     * Get the index of the input config inside the MostRecentlyConnectNetworkList.
     * If input config is in the list will return index number,
     * otherwise return {@code Integer.MAX_VALUE}
     */
    public int getAgeIndexOfNetwork(@NonNull WifiConfiguration config) {
        int index = mList.indexOf(ScanResultMatchInfo.fromWifiConfiguration(config));
        // Not in the most recently connected list will return the MAX_INT
        if (index < 0) {
            return Integer.MAX_VALUE;
        }
        return index;
    }
}
