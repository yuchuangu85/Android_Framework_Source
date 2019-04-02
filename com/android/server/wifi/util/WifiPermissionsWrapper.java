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
import android.app.AppGlobals;
import android.app.admin.DevicePolicyManagerInternal;
import android.content.Context;
import android.os.RemoteException;
import android.os.UserHandle;

import com.android.server.LocalServices;

import java.util.List;

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
     * Returns the user ID corresponding to the UID
     * @param uid Calling Uid
     * @return userid Corresponding user id
     */
    public int getCallingUserId(int uid) {
        return UserHandle.getUserId(uid);
    }

    /**
     * Get the PackageName of the top running task
     * @return String corresponding to the package
     */
    public String getTopPkgName() {
        ActivityManager am = (ActivityManager) mContext.getSystemService(Context.ACTIVITY_SERVICE);
        String topTaskPkg = " ";
        List<ActivityManager.RunningTaskInfo> tasks = am.getRunningTasks(1);
        if (!tasks.isEmpty()) {
            return tasks.get(0).topActivity.getPackageName();
        }
        return topTaskPkg;
    }

    /**
     * API is wrap around ActivityManager class to
     * get location permissions for a certain UID
     * @param: Manifest permission string
     * @param: Uid to get permission for
     * @return: Permissions setting
     */
    public int getUidPermission(String permissionType, int uid) {
        return ActivityManager.checkUidPermission(permissionType, uid);
    }

    /**
     * Gets the local service {link@ DevicePolicyManagerInternal}, can be null
     */
    public DevicePolicyManagerInternal getDevicePolicyManagerInternal() {
        return LocalServices.getService(DevicePolicyManagerInternal.class);
    }

    /**
     * Determines if the caller has the override wifi config permission.
     *
     * @param uid to check the permission for
     * @return int representation of success or denied
     * @throws RemoteException
     */
    public int getOverrideWifiConfigPermission(int uid) throws RemoteException {
        return AppGlobals.getPackageManager().checkUidPermission(
                android.Manifest.permission.OVERRIDE_WIFI_CONFIG, uid);
    }

    /**
     * Determines if the caller has the change wifi config permission.
     *
     * @param uid to check the permission for
     * @return int representation of success or denied
     * @throws RemoteException
     */
    public int getChangeWifiConfigPermission(int uid) throws RemoteException {
        return AppGlobals.getPackageManager().checkUidPermission(
                Manifest.permission.CHANGE_WIFI_STATE, uid);
    }
}
