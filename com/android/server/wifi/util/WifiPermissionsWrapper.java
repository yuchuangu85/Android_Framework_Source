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

package com.android.server.wifi.util;

import android.Manifest;
import android.app.ActivityManager;
import android.content.Context;

/**
 * A wifi permissions dependency class to wrap around external
 * calls to static methods that enable testing.
 */
public class WifiPermissionsWrapper {
    private static final String TAG = "WifiPermissionsWrapper";
    private final Context mContext;

    public WifiPermissionsWrapper(Context context) {
        mContext = context;
    }

    public int getCurrentUser() {
        return ActivityManager.getCurrentUser();
    }

    /**
     * Determine if a UID has a permission.
     * @param permissionType permission string
     * @param uid to get permission for
     * @return Permissions setting
     */
    public int getUidPermission(String permissionType, int uid) {
        // We don't care about pid, pass in -1
        return mContext.checkPermission(permissionType, -1, uid);
    }

    /**
     * Determines if the caller has the override wifi config permission.
     *
     * @param uid to check the permission for
     * @return int representation of success or denied
     */
    public int getOverrideWifiConfigPermission(int uid) {
        return getUidPermission(android.Manifest.permission.OVERRIDE_WIFI_CONFIG, uid);
    }

    /**
     * Determines if the caller has local mac address permission.
     *
     * @param uid to check the permission for
     * @return int representation of success or denied
     */
    public int getLocalMacAddressPermission(int uid) {
        return getUidPermission(Manifest.permission.LOCAL_MAC_ADDRESS, uid);
    }
}
