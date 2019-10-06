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

import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.os.UserHandle;
import android.os.UserManager;

import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public class ConfigurationMap {
    private final Map<Integer, WifiConfiguration> mPerID = new HashMap<>();

    private final Map<Integer, WifiConfiguration> mPerIDForCurrentUser = new HashMap<>();
    private final Map<ScanResultMatchInfo, WifiConfiguration>
            mScanResultMatchInfoMapForCurrentUser = new HashMap<>();

    private final UserManager mUserManager;

    private int mCurrentUserId = UserHandle.USER_SYSTEM;

    ConfigurationMap(UserManager userManager) {
        mUserManager = userManager;
    }

    // RW methods:
    public WifiConfiguration put(WifiConfiguration config) {
        final WifiConfiguration current = mPerID.put(config.networkId, config);
        if (WifiConfigurationUtil.isVisibleToAnyProfile(config,
                mUserManager.getProfiles(mCurrentUserId))) {
            mPerIDForCurrentUser.put(config.networkId, config);
            mScanResultMatchInfoMapForCurrentUser.put(
                    ScanResultMatchInfo.fromWifiConfiguration(config), config);
        }
        return current;
    }

    public WifiConfiguration remove(int netID) {
        WifiConfiguration config = mPerID.remove(netID);
        if (config == null) {
            return null;
        }

        mPerIDForCurrentUser.remove(netID);

        Iterator<Map.Entry<ScanResultMatchInfo, WifiConfiguration>> scanResultMatchInfoEntries =
                mScanResultMatchInfoMapForCurrentUser.entrySet().iterator();
        while (scanResultMatchInfoEntries.hasNext()) {
            if (scanResultMatchInfoEntries.next().getValue().networkId == netID) {
                scanResultMatchInfoEntries.remove();
                break;
            }
        }
        return config;
    }

    public void clear() {
        mPerID.clear();
        mPerIDForCurrentUser.clear();
        mScanResultMatchInfoMapForCurrentUser.clear();
    }

    /**
     * Sets the new foreground user ID.
     *
     * @param userId the id of the new foreground user
     */
    public void setNewUser(int userId) {
        mCurrentUserId = userId;
    }

    // RO methods:
    public WifiConfiguration getForAllUsers(int netid) {
        return mPerID.get(netid);
    }

    public WifiConfiguration getForCurrentUser(int netid) {
        return mPerIDForCurrentUser.get(netid);
    }

    public int sizeForAllUsers() {
        return mPerID.size();
    }

    public int sizeForCurrentUser() {
        return mPerIDForCurrentUser.size();
    }

    public WifiConfiguration getByConfigKeyForCurrentUser(String key) {
        if (key == null) {
            return null;
        }
        for (WifiConfiguration config : mPerIDForCurrentUser.values()) {
            if (config.configKey().equals(key)) {
                return config;
            }
        }
        return null;
    }

    /**
     * Retrieves the |WifiConfiguration| object matching the provided |scanResult| from the internal
     * map.
     * Essentially checks if network config and scan result have the same SSID and encryption type.
     */
    public WifiConfiguration getByScanResultForCurrentUser(ScanResult scanResult) {
        return mScanResultMatchInfoMapForCurrentUser.get(
                ScanResultMatchInfo.fromScanResult(scanResult));
    }

    public Collection<WifiConfiguration> valuesForAllUsers() {
        return mPerID.values();
    }

    public Collection<WifiConfiguration> valuesForCurrentUser() {
        return mPerIDForCurrentUser.values();
    }
}
