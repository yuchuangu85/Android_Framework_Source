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
    private WifiLog mLog;

    public WifiPermissionsUtil(WifiPermissionsWrapper wifiPermissionsWrapper,
            Context context, WifiSettingsStore settingsStore, UserManager userManager,
            WifiInjector wifiInjector) {
        mWifiPermissionsWrapper = wifiPermissionsWrapper;
        mContext = context;
        mUserManager = userManager;
        mAppOps = (AppOpsManager) mContext.getSystemService(Context.APP_OPS_SERVICE);
        mSettingsStore = settingsStore;
        mLog = wifiInjector.makeLog(TAG);
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
     * Checks if the app has the permission to change Wi-Fi network configuration or not.
     *
     * @param uid uid of the app.
     * @return true if the app does have the permission, false otherwise.
     */
    public boolean checkChangePermission(int uid) {
        try {
            int permission = mWifiPermissionsWrapper.getChangeWifiConfigPermission(uid);
            return (permission == PackageManager.PERMISSION_GRANTED);
        } catch (RemoteException e) {
            mLog.err("Error checking for permission: %").r(e.getMessage()).flush();
            return false;
        }
    }

    /**
     * Checks if the app has the permission to access Wi-Fi state or not.
     *
     * @param uid uid of the app.
     * @return true if the app does have the permission, false otherwise.
     */
    public boolean checkWifiAccessPermission(int uid) {
        try {
            int permission = mWifiPermissionsWrapper.getAccessWifiStatePermission(uid);
            return (permission == PackageManager.PERMISSION_GRANTED);
        } catch (RemoteException e) {
            mLog.err("Error checking for permission: %").r(e.getMessage()).flush();
            return false;
        }
    }

    /**
     * Check and enforce Coarse Location permission.
     *
     * @param pkgName PackageName of the application requesting access
     * @param uid The uid of the package
     */
    public void enforceLocationPermission(String pkgName, int uid) {
        if (!checkCallersLocationPermission(pkgName, uid)) {
            throw new SecurityException("UID " + uid + " does not have Coarse Location permission");
        }
    }


    /**
     * Checks that calling process has android.Manifest.permission.ACCESS_COARSE_LOCATION
     * and a corresponding app op is allowed for this package and uid.
     *
     * @param pkgName PackageName of the application requesting access
     * @param uid The uid of the package
     */
    public boolean checkCallersLocationPermission(String pkgName, int uid) {
        // Having FINE permission implies having COARSE permission (but not the reverse)
        if ((mWifiPermissionsWrapper.getUidPermission(
                Manifest.permission.ACCESS_COARSE_LOCATION, uid)
                == PackageManager.PERMISSION_GRANTED)
                && checkAppOpAllowed(AppOpsManager.OP_COARSE_LOCATION, pkgName, uid)) {
            return true;
        }
        return false;
    }

    /**
     * Check and enforce Fine Location permission.
     *
     * @param pkgName PackageName of the application requesting access
     * @param uid The uid of the package
     */
    public void enforceFineLocationPermission(String pkgName, int uid) {
        if (!checkCallersFineLocationPermission(pkgName, uid)) {
            throw new SecurityException("UID " + uid + " does not have Fine Location permission");
        }
    }


    /**
     * Checks that calling process has android.Manifest.permission.ACCESS_FINE_LOCATION
     * and a corresponding app op is allowed for this package and uid.
     *
     * @param pkgName PackageName of the application requesting access
     * @param uid The uid of the package
     */
    public boolean checkCallersFineLocationPermission(String pkgName, int uid) {
        // Having FINE permission implies having COARSE permission (but not the reverse)
        if ((mWifiPermissionsWrapper.getUidPermission(
                Manifest.permission.ACCESS_FINE_LOCATION, uid)
                == PackageManager.PERMISSION_GRANTED)
                && checkAppOpAllowed(AppOpsManager.OP_FINE_LOCATION, pkgName, uid)) {
            return true;
        }
        return false;
    }

    /**
     * API to determine if the caller has permissions to get scan results. Throws SecurityException
     * if the caller has no permission.
     * @param pkgName package name of the application requesting access
     * @param uid The uid of the package
     */
    public void enforceCanAccessScanResults(String pkgName, int uid) throws SecurityException {
        mAppOps.checkPackage(uid, pkgName);

        // Apps with NETWORK_SETTINGS & NETWORK_SETUP_WIZARD are granted a bypass.
        if (checkNetworkSettingsPermission(uid) || checkNetworkSetupWizardPermission(uid)) {
            return;
        }

        // Location mode must be enabled
        if (!isLocationModeEnabled()) {
            // Location mode is disabled, scan results cannot be returned
            throw new SecurityException("Location mode is disabled for the device");
        }

        // Check if the calling Uid has CAN_READ_PEER_MAC_ADDRESS permission.
        boolean canCallingUidAccessLocation = checkCallerHasPeersMacAddressPermission(uid);
        // LocationAccess by App: caller must have
        // Coarse Location permission to have access to location information.
        boolean canAppPackageUseLocation = checkCallersLocationPermission(pkgName, uid);

        // If neither caller or app has location access, there is no need to check
        // any other permissions. Deny access to scan results.
        if (!canCallingUidAccessLocation && !canAppPackageUseLocation) {
            throw new SecurityException("UID " + uid + " has no location permission");
        }
        // Check if Wifi Scan request is an operation allowed for this App.
        if (!isScanAllowedbyApps(pkgName, uid)) {
            throw new SecurityException("UID " + uid + " has no wifi scan permission");
        }
        // If the User or profile is current, permission is granted
        // Otherwise, uid must have INTERACT_ACROSS_USERS_FULL permission.
        if (!isCurrentProfile(uid) && !checkInteractAcrossUsersFull(uid)) {
            throw new SecurityException("UID " + uid + " profile not permitted");
        }
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
            for (UserInfo user : userProfiles) {
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
    public boolean isLegacyVersion(String pkgName, int minVersion) {
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

    private boolean isLocationModeEnabled() {
        return (mSettingsStore.getLocationModeSetting(mContext)
                 != Settings.Secure.LOCATION_MODE_OFF);
    }

    /**
     * Returns true if the |uid| holds NETWORK_SETTINGS permission.
     */
    public boolean checkNetworkSettingsPermission(int uid) {
        return mWifiPermissionsWrapper.getUidPermission(
                android.Manifest.permission.NETWORK_SETTINGS, uid)
                == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Returns true if the |uid| holds NETWORK_SETUP_WIZARD permission.
     */
    public boolean checkNetworkSetupWizardPermission(int uid) {
        return mWifiPermissionsWrapper.getUidPermission(
                android.Manifest.permission.NETWORK_SETUP_WIZARD, uid)
                == PackageManager.PERMISSION_GRANTED;
    }
}
