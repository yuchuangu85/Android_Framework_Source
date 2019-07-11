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
import android.annotation.Nullable;
import android.app.AppOpsManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.net.ConnectivityManager;
import android.net.NetworkScoreManager;
import android.net.NetworkScorerAppData;
import android.net.wifi.WifiConfiguration;
import android.os.Binder;
import android.os.RemoteException;
import android.os.UserManager;
import android.provider.Settings;
import android.text.TextUtils;

import com.android.server.wifi.FrameworkFacade;
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
    private final FrameworkFacade mFrameworkFacade;
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
        mFrameworkFacade = wifiInjector.getFrameworkFacade();
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
     * Check and enforce tether change permission.
     *
     * @param context Context object of the caller.
     */
    public void enforceTetherChangePermission(Context context) {
        String pkgName = context.getOpPackageName();
        ConnectivityManager.enforceTetherChangePermission(context, pkgName);
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
     * Checks that calling process has android.Manifest.permission.ACCESS_COARSE_LOCATION
     * and a corresponding app op is allowed for this package and uid.
     *
     * @param pkgName PackageName of the application requesting access
     * @param uid The uid of the package
     */
    public boolean checkCallersLocationPermission(String pkgName, int uid) {
        // Coarse Permission implies Fine permission
        if ((mWifiPermissionsWrapper.getUidPermission(
                Manifest.permission.ACCESS_COARSE_LOCATION, uid)
                == PackageManager.PERMISSION_GRANTED)
                && checkAppOpAllowed(AppOpsManager.OP_COARSE_LOCATION, pkgName, uid)) {
            return true;
        }
        return false;
    }

    /**
     * API to determine if the caller has permissions to get
     * scan results.
     * @param pkgName package name of the application requesting access
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
        if (!canAccessUserProfile(uid)) {
            mLog.tC("Denied: Profile not permitted");
            return false;
        }
        return true;
    }

    /**
     * API to determine if the caller has permissions to get a {@link android.net.wifi.WifiInfo}
     * instance containing the SSID and BSSID.
     *
     *
     * @param currentConfig the currently connected WiFi config
     * @param pkgName package name of the application requesting access
     * @param uid The uid of the package
     * @param minVersion Minimum app API Version number to enforce location permission
     * @return boolean true if the SSID/BSSID can be sent to the user, false if they
     *         should be hidden/removed.
     */
    public boolean canAccessFullConnectionInfo(@Nullable WifiConfiguration currentConfig,
            String pkgName, int uid, int minVersion) throws SecurityException {
        mAppOps.checkPackage(uid, pkgName);

        // The User or profile must be current or the uid must
        // have INTERACT_ACROSS_USERS_FULL permission.
        if (!canAccessUserProfile(uid)) {
            mLog.tC("Denied: Profile not permitted");
            return false;
        }

        // If the caller has scan result access then they can also see the full connection info.
        // Otherwise the caller must be the active use open wifi package and the current config
        // must be for an open network.
        return canAccessScanResults(pkgName, uid, minVersion)
                || isUseOpenWifiPackageWithConnectionInfoAccess(currentConfig, pkgName);

    }

    /**
     * Returns true if the given WiFi config is for an open network and the package is the active
     * use open wifi app.
     */
    private boolean isUseOpenWifiPackageWithConnectionInfoAccess(
            @Nullable WifiConfiguration currentConfig, String pkgName) {

        // Access is only granted for open networks.
        if (currentConfig == null) {
            mLog.tC("Denied: WifiConfiguration is NULL.");
            return false;
        }

        // Access is only granted for open networks.
        if (!currentConfig.isOpenNetwork()) {
            mLog.tC("Denied: The current config is not for an open network.");
            return false;
        }

        // The USE_OPEN_WIFI_PACKAGE can access the full connection info details without
        // scan result access.
        if (!isUseOpenWifiPackage(pkgName)) {
            mLog.tC("Denied: caller is not the current USE_OPEN_WIFI_PACKAGE");
            return false;
        }

        return true;
    }

    /**
     * Returns true if the User or profile is current or the
     * uid has the INTERACT_ACROSS_USERS_FULL permission.
     */
    private boolean canAccessUserProfile(int uid) {
        if (!isCurrentProfile(uid) && !checkInteractAcrossUsersFull(uid)) {
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
     * Returns true if the given package is equal to the setting keyed by
     * {@link Settings.Global#USE_OPEN_WIFI_PACKAGE} and the NetworkScoreManager
     * has the package name set as the use open wifi package.
     */
    private boolean isUseOpenWifiPackage(String packageName) {
        if (TextUtils.isEmpty(packageName)) {
            return false;
        }

        // When the setting is enabled it's set to the package name of the use open wifi app.
        final String useOpenWifiPkg =
                mFrameworkFacade.getStringSetting(mContext, Settings.Global.USE_OPEN_WIFI_PACKAGE);
        if (packageName.equals(useOpenWifiPkg)) {
            // If the package name matches the setting then also confirm that the scorer is
            // active and the package matches the expected use open wifi package from the scorer's
            // perspective. The scorer can be active when the use open wifi feature is off so we
            // can't rely on this check alone.
            // TODO(b/67278755): Refactor this into an API similar to isCallerActiveScorer()
            final NetworkScorerAppData appData;
            final long token = Binder.clearCallingIdentity();
            try {
                appData = mNetworkScoreManager.getActiveScorer();
            } finally {
                Binder.restoreCallingIdentity(token);
            }
            if (appData != null) {
                final ComponentName enableUseOpenWifiActivity =
                        appData.getEnableUseOpenWifiActivity();
                return enableUseOpenWifiActivity != null
                        && packageName.equals(enableUseOpenWifiActivity.getPackageName());
            }
        }

        return false;
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
        final long token = Binder.clearCallingIdentity();
        try {
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
        } finally {
            Binder.restoreCallingIdentity(token);
        }
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

    private boolean isLocationModeEnabled(String pkgName) {
        // Location mode check on applications that are later than version.
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
}
