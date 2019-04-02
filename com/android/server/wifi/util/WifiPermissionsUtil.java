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
import android.app.AppOpsManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.net.ConnectivityManager;
import android.net.NetworkScoreManager;
import android.os.RemoteException;
import android.os.UserManager;
import android.provider.Settings;

import com.android.server.wifi.WifiInjector;
import com.android.server.wifi.WifiLog;
import com.android.server.wifi.WifiSettingsStore;

import java.util.List;

/**
 * A wifi permissions utility assessing permissions
 * for getting scan results by a package.
 */
public class WifiPermissionsUtil {
    private static final String TAG = "WifiPermissionsUtil";
    private final WifiPermissionsWrapper mWifiPermissionsWrapper;
    private final Context mContext;
    private final AppOpsManager mAppOps;
    private final UserManager mUserManager;
    private final WifiSettingsStore mSettingsStore;
    private final NetworkScoreManager mNetworkScoreManager;
    private WifiLog mLog;

    public WifiPermissionsUtil(WifiPermissionsWrapper wifiPermissionsWrapper,
            Context context, WifiSettingsStore settingsStore, UserManager userManager,
            NetworkScoreManager networkScoreManager, WifiInjector wifiInjector) {
        mWifiPermissionsWrapper = wifiPermissionsWrapper;
        mContext = context;
        mUserManager = userManager;
        mAppOps = (AppOpsManager) mContext.getSystemService(Context.APP_OPS_SERVICE);
        mSettingsStore = settingsStore;
        mLog = wifiInjector.makeLog(TAG);
        mNetworkScoreManager = networkScoreManager;
    }

    /**
     * Checks if the app has the permission to override Wi-Fi network configuration or not.
     *
     * @param uid uid of the app.
     * @return true if the app does have the permission, false otherwise.
     */
    public boolean checkConfigOverridePermission(int uid) {
        try {
            int permission = mWifiPermissionsWrapper.getOverrideWifiConfigPermission(uid);
            return (permission == PackageManager.PERMISSION_GRANTED);
        } catch (RemoteException e) {
            mLog.err("Error checking for permission: %").r(e.getMessage()).flush();
            return false;
        }
    }

    /**
     * Check and enforce tether change permission.
     *
     * @param context Context object of the caller.
     */
    public void enforceTetherChangePermission(Context context) {
        ConnectivityManager.enforceTetherChangePermission(context);
    }

    /**
     * Check and enforce Location permission.
     *
     * @param pkgName PackageName of the application requesting access
     * @param uid The uid of the package
     */
    public void enforceLocationPermission(String pkgName, int uid) {
        if (!checkCallersLocationPermission(pkgName, uid)) {
            throw new SecurityException("UID " + uid + " does not have Location permission");
        }
    }

    /**
     * API to determine if the caller has permissions to get
     * scan results.
     * @param pkgName Packagename of the application requesting access
     * @param uid The uid of the package
     * @param minVersion Minimum app API Version number to enforce location permission
     * @return boolean true or false if permissions is granted
     */
    public boolean canAccessScanResults(String pkgName, int uid,
                int minVersion) throws SecurityException {
        mAppOps.checkPackage(uid, pkgName);
        // Check if the calling Uid has CAN_READ_PEER_MAC_ADDRESS
        // permission or is an Active Nw scorer.
        boolean canCallingUidAccessLocation = checkCallerHasPeersMacAddressPermission(uid)
                || isCallerActiveNwScorer(uid);
        // LocationAccess by App: For AppVersion older than minVersion,
        // it is sufficient to check if the App is foreground.
        // Otherwise, Location Mode must be enabled and caller must have
        // Coarse Location permission to have access to location information.
        boolean canAppPackageUseLocation = isLegacyForeground(pkgName, minVersion)
                || (isLocationModeEnabled(pkgName)
                        && checkCallersLocationPermission(pkgName, uid));
        // If neither caller or app has location access, there is no need to check
        // any other permissions. Deny access to scan results.
        if (!canCallingUidAccessLocation && !canAppPackageUseLocation) {
            mLog.tC("Denied: no location permission");
            return false;
        }
        // Check if Wifi Scan request is an operation allowed for this App.
        if (!isScanAllowedbyApps(pkgName, uid)) {
            mLog.tC("Denied: app wifi scan not allowed");
            return false;
        }
        // If the User or profile is current, permission is granted
        // Otherwise, uid must have INTERACT_ACROSS_USERS_FULL permission.
        if (!isCurrentProfile(uid) && !checkInteractAcrossUsersFull(uid)) {
            mLog.tC("Denied: Profile not permitted");
            return false;
        }
        return true;
    }

    /**
     * Returns true if the caller holds PEERS_MAC_ADDRESS permission.
     */
    private boolean checkCallerHasPeersMacAddressPermission(int uid) {
        return mWifiPermissionsWrapper.getUidPermission(
                android.Manifest.permission.PEERS_MAC_ADDRESS, uid)
                == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Returns true if the caller is an Active Network Scorer.
     */
    private boolean isCallerActiveNwScorer(int uid) {
        return mNetworkScoreManager.isCallerActiveScorer(uid);
    }

    /**
     * Returns true if Wifi scan operation is allowed for this caller
     * and package.
     */
    private boolean isScanAllowedbyApps(String pkgName, int uid) {
        return checkAppOpAllowed(AppOpsManager.OP_WIFI_SCAN, pkgName, uid);
    }

    /**
     * Returns true if the caller holds INTERACT_ACROSS_USERS_FULL.
     */
    private boolean checkInteractAcrossUsersFull(int uid) {
        return mWifiPermissionsWrapper.getUidPermission(
                android.Manifest.permission.INTERACT_ACROSS_USERS_FULL, uid)
                == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Returns true if the calling user is the current one or a profile of the
     * current user.
     */
    private boolean isCurrentProfile(int uid) {
        int currentUser = mWifiPermissionsWrapper.getCurrentUser();
        int callingUserId = mWifiPermissionsWrapper.getCallingUserId(uid);
        if (callingUserId == currentUser) {
            return true;
        } else {
            List<UserInfo> userProfiles = mUserManager.getProfiles(currentUser);
            for (UserInfo user: userProfiles) {
                if (user.id == callingUserId) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Returns true if the App version is older than minVersion.
     */
    private boolean isLegacyVersion(String pkgName, int minVersion) {
        try {
            if (mContext.getPackageManager().getApplicationInfo(pkgName, 0)
                    .targetSdkVersion < minVersion) {
                return true;
            }
        } catch (PackageManager.NameNotFoundException e) {
            // In case of exception, assume known app (more strict checking)
            // Note: This case will never happen since checkPackage is
            // called to verify valididity before checking App's version.
        }
        return false;
    }

    private boolean checkAppOpAllowed(int op, String pkgName, int uid) {
        return mAppOps.noteOp(op, uid, pkgName) == AppOpsManager.MODE_ALLOWED;
    }

    private boolean isLegacyForeground(String pkgName, int version) {
        return isLegacyVersion(pkgName, version) && isForegroundApp(pkgName);
    }

    private boolean isForegroundApp(String pkgName) {
        return pkgName.equals(mWifiPermissionsWrapper.getTopPkgName());
    }

    /**
     * Checks that calling process has android.Manifest.permission.ACCESS_COARSE_LOCATION
     * and a corresponding app op is allowed for this package and uid.
     */
    private boolean checkCallersLocationPermission(String pkgName, int uid) {
        // Coarse Permission implies Fine permission
        if ((mWifiPermissionsWrapper.getUidPermission(
                Manifest.permission.ACCESS_COARSE_LOCATION, uid)
                == PackageManager.PERMISSION_GRANTED)
                && checkAppOpAllowed(AppOpsManager.OP_COARSE_LOCATION, pkgName, uid)) {
            return true;
        }
        return false;
    }
    private boolean isLocationModeEnabled(String pkgName) {
        // Location mode check on applications that are later than version.
        return (mSettingsStore.getLocationModeSetting(mContext)
                 != Settings.Secure.LOCATION_MODE_OFF);
    }
}
