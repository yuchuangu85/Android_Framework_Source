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
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.net.NetworkStack;
import android.os.Binder;
import android.os.Build;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import com.android.server.wifi.FrameworkFacade;
import com.android.server.wifi.WifiInjector;
import com.android.server.wifi.WifiLog;

/**
 * A wifi permissions utility assessing permissions
 * for getting scan results by a package.
 */
public class WifiPermissionsUtil {
    private static final String TAG = "WifiPermissionsUtil";
    private final WifiPermissionsWrapper mWifiPermissionsWrapper;
    private final Context mContext;
    private final FrameworkFacade mFrameworkFacade;
    private final AppOpsManager mAppOps;
    private final UserManager mUserManager;
    private final Object mLock = new Object();
    @GuardedBy("mLock")
    private LocationManager mLocationManager;
    private WifiLog mLog;

    public WifiPermissionsUtil(WifiPermissionsWrapper wifiPermissionsWrapper,
            Context context, UserManager userManager, WifiInjector wifiInjector) {
        mWifiPermissionsWrapper = wifiPermissionsWrapper;
        mContext = context;
        mFrameworkFacade = wifiInjector.getFrameworkFacade();
        mUserManager = userManager;
        mAppOps = (AppOpsManager) mContext.getSystemService(Context.APP_OPS_SERVICE);
        mLog = wifiInjector.makeLog(TAG);
    }

    /**
     * Checks if the app has the permission to override Wi-Fi network configuration or not.
     *
     * @param uid uid of the app.
     * @return true if the app does have the permission, false otherwise.
     */
    public boolean checkConfigOverridePermission(int uid) {
        int permission = mWifiPermissionsWrapper.getOverrideWifiConfigPermission(uid);
        return permission == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Check and enforce Coarse or Fine Location permission (depending on target SDK).
     *
     * @param pkgName PackageName of the application requesting access
     * @param featureId The feature in the package
     * @param uid The uid of the package
     */
    public void enforceLocationPermission(String pkgName, @Nullable String featureId, int uid) {
        if (!checkCallersLocationPermission(pkgName, featureId,
                uid, /* coarseForTargetSdkLessThanQ */ true, null)) {
            throw new SecurityException(
                    "UID " + uid + " does not have Coarse/Fine Location permission");
        }
    }

    /**
     * Checks whether than the target SDK of the package is less than the specified version code.
     */
    public boolean isTargetSdkLessThan(String packageName, int versionCode, int callingUid) {
        long ident = Binder.clearCallingIdentity();
        try {
            if (mContext.getPackageManager().getApplicationInfoAsUser(
                    packageName, 0,
                    UserHandle.getUserHandleForUid(callingUid)).targetSdkVersion
                    < versionCode) {
                return true;
            }
        } catch (PackageManager.NameNotFoundException e) {
            // In case of exception, assume unknown app (more strict checking)
            // Note: This case will never happen since checkPackage is
            // called to verify validity before checking App's version.
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
        return false;
    }

    /**
     * Checks that calling process has android.Manifest.permission.ACCESS_FINE_LOCATION or
     * android.Manifest.permission.ACCESS_FINE_LOCATION (depending on config/targetSDK leve)
     * and a corresponding app op is allowed for this package and uid.
     *
     * @param pkgName PackageName of the application requesting access
     * @param featureId The feature in the package
     * @param uid The uid of the package
     * @param coarseForTargetSdkLessThanQ If true and the targetSDK < Q then will check for COARSE
     *                                    else (false or targetSDK >= Q) then will check for FINE
     * @param message A message describing why the permission was checked. Only needed if this is
     *                not inside of a two-way binder call from the data receiver
     */
    public boolean checkCallersLocationPermission(String pkgName, @Nullable String featureId,
            int uid, boolean coarseForTargetSdkLessThanQ, @Nullable String message) {
        boolean isTargetSdkLessThanQ = isTargetSdkLessThan(pkgName, Build.VERSION_CODES.Q, uid);

        String permissionType = Manifest.permission.ACCESS_FINE_LOCATION;
        if (coarseForTargetSdkLessThanQ && isTargetSdkLessThanQ) {
            // Having FINE permission implies having COARSE permission (but not the reverse)
            permissionType = Manifest.permission.ACCESS_COARSE_LOCATION;
        }
        if (mWifiPermissionsWrapper.getUidPermission(permissionType, uid)
                == PackageManager.PERMISSION_DENIED) {
            return false;
        }

        // Always checking FINE - even if will not enforce. This will record the request for FINE
        // so that a location request by the app is surfaced to the user.
        boolean isFineLocationAllowed = noteAppOpAllowed(
                AppOpsManager.OPSTR_FINE_LOCATION, pkgName, featureId, uid, message);
        if (isFineLocationAllowed) {
            return true;
        }
        if (coarseForTargetSdkLessThanQ && isTargetSdkLessThanQ) {
            return noteAppOpAllowed(AppOpsManager.OPSTR_COARSE_LOCATION, pkgName, featureId, uid,
                    message);
        }
        return false;
    }

    /**
     * Check and enforce Fine Location permission.
     *
     * @param pkgName PackageName of the application requesting access
     * @param featureId The feature in the package
     * @param uid The uid of the package
     */
    public void enforceFineLocationPermission(String pkgName, @Nullable String featureId,
            int uid) {
        if (!checkCallersFineLocationPermission(pkgName, featureId, uid, false)) {
            throw new SecurityException("UID " + uid + " does not have Fine Location permission");
        }
    }

    /**
     * Checks that calling process has android.Manifest.permission.ACCESS_FINE_LOCATION
     * and a corresponding app op is allowed for this package and uid.
     *
     * @param pkgName PackageName of the application requesting access
     * @param featureId The feature in the package
     * @param uid The uid of the package
     * @param hideFromAppOps True to invoke {@link AppOpsManager#checkOp(int, int, String)}, false
     *                       to invoke {@link AppOpsManager#noteOp(String, int, String, String,
     *                       String)}.
     */
    private boolean checkCallersFineLocationPermission(String pkgName, @Nullable String featureId,
            int uid, boolean hideFromAppOps) {
        // Having FINE permission implies having COARSE permission (but not the reverse)
        if (mWifiPermissionsWrapper.getUidPermission(
                Manifest.permission.ACCESS_FINE_LOCATION, uid)
                == PackageManager.PERMISSION_DENIED) {
            return false;
        }
        if (hideFromAppOps) {
            // Don't note the operation, just check if the app is allowed to perform the operation.
            return checkAppOpAllowed(AppOpsManager.OPSTR_FINE_LOCATION, pkgName, uid);
        } else {
            return noteAppOpAllowed(AppOpsManager.OPSTR_FINE_LOCATION, pkgName, featureId, uid,
                    null);
        }
    }

    /**
     * Checks that calling process has android.Manifest.permission.LOCATION_HARDWARE.
     *
     * @param uid The uid of the package
     */
    private boolean checkCallersHardwareLocationPermission(int uid) {
        return mWifiPermissionsWrapper.getUidPermission(Manifest.permission.LOCATION_HARDWARE, uid)
                == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * API to determine if the caller has permissions to get scan results. Throws SecurityException
     * if the caller has no permission.
     * @param pkgName package name of the application requesting access
     * @param featureId The feature in the package
     * @param uid The uid of the package
     * @param message A message describing why the permission was checked. Only needed if this is
     *                not inside of a two-way binder call from the data receiver
     */
    public void enforceCanAccessScanResults(String pkgName, @Nullable String featureId, int uid,
            @Nullable String message)
            throws SecurityException {
        checkPackage(uid, pkgName);

        // Apps with NETWORK_SETTINGS, NETWORK_SETUP_WIZARD, NETWORK_MANAGED_PROVISIONING,
        // NETWORK_STACK & MAINLINE_NETWORK_STACK, RADIO_SCAN_WITHOUT_LOCATION are granted a bypass.
        if (checkNetworkSettingsPermission(uid) || checkNetworkSetupWizardPermission(uid)
                || checkNetworkManagedProvisioningPermission(uid)
                || checkNetworkStackPermission(uid) || checkMainlineNetworkStackPermission(uid)
                || checkScanWithoutLocationPermission(uid)) {
            return;
        }

        // Location mode must be enabled
        if (!isLocationModeEnabled()) {
            // Location mode is disabled, scan results cannot be returned
            throw new SecurityException("Location mode is disabled for the device");
        }

        // Check if the calling Uid has CAN_READ_PEER_MAC_ADDRESS permission.
        boolean canCallingUidAccessLocation = checkCallerHasPeersMacAddressPermission(uid);
        // LocationAccess by App: caller must have Coarse/Fine Location permission to have access to
        // location information.
        boolean canAppPackageUseLocation = checkCallersLocationPermission(pkgName, featureId,
                uid, /* coarseForTargetSdkLessThanQ */ true, message);

        // If neither caller or app has location access, there is no need to check
        // any other permissions. Deny access to scan results.
        if (!canCallingUidAccessLocation && !canAppPackageUseLocation) {
            throw new SecurityException("UID " + uid + " has no location permission");
        }
        // Check if Wifi Scan request is an operation allowed for this App.
        if (!isScanAllowedbyApps(pkgName, featureId, uid)) {
            throw new SecurityException("UID " + uid + " has no wifi scan permission");
        }
        // If the User or profile is current, permission is granted
        // Otherwise, uid must have INTERACT_ACROSS_USERS_FULL permission.
        if (!isCurrentProfile(uid) && !checkInteractAcrossUsersFull(uid)) {
            throw new SecurityException("UID " + uid + " profile not permitted");
        }
    }

    /**
     * API to determine if the caller has permissions to get scan results. Throws SecurityException
     * if the caller has no permission.
     * @param pkgName package name of the application requesting access
     * @param featureId The feature in the package
     * @param uid The uid of the package
     * @param ignoreLocationSettings Whether this request can bypass location settings.
     * @param hideFromAppOps Whether to note the request in app-ops logging or not.
     *
     * Note: This is to be used for checking permissions in the internal WifiScanner API surface
     * for requests coming from system apps.
     */
    public void enforceCanAccessScanResultsForWifiScanner(String pkgName,
            @Nullable String featureId, int uid, boolean ignoreLocationSettings,
            boolean hideFromAppOps) throws SecurityException {
        checkPackage(uid, pkgName);

        // Location mode must be enabled
        if (!isLocationModeEnabled()) {
            if (ignoreLocationSettings) {
                mLog.w("Request from " + pkgName + " violated location settings");
            } else {
                // Location mode is disabled, scan results cannot be returned
                throw new SecurityException("Location mode is disabled for the device");
            }
        }
        // LocationAccess by App: caller must have fine & hardware Location permission to have
        // access to location information.
        if (!checkCallersFineLocationPermission(pkgName, featureId, uid, hideFromAppOps)
                || !checkCallersHardwareLocationPermission(uid)) {
            throw new SecurityException("UID " + uid + " has no location permission");
        }
        // Check if Wifi Scan request is an operation allowed for this App.
        if (!isScanAllowedbyApps(pkgName, featureId, uid)) {
            throw new SecurityException("UID " + uid + " has no wifi scan permission");
        }
    }

    /**
     *
     * Checks that calling process has android.Manifest.permission.ACCESS_FINE_LOCATION
     * and a corresponding app op is allowed for this package and uid
     *
     * @param pkgName package name of the application requesting access
     * @param featureId The feature in the package
     * @param uid The uid of the package
     * @param needLocationModeEnabled indicates location mode must be enabled.
     *
     * @return true if caller has permission, false otherwise
     */
    public boolean checkCanAccessWifiDirect(String pkgName, @Nullable String featureId, int uid,
                                            boolean needLocationModeEnabled) {
        try {
            checkPackage(uid, pkgName);
        } catch (SecurityException se) {
            Log.e(TAG, "Package check exception - " + se);
            return false;
        }

        // Apps with NETWORK_SETTINGS are granted a bypass.
        if (checkNetworkSettingsPermission(uid)) {
            return true;
        }

        // Location mode must be enabled if needed.
        if (needLocationModeEnabled && !isLocationModeEnabled()) {
            Log.e(TAG, "Location mode is disabled for the device");
            return false;
        }

        // LocationAccess by App: caller must have Fine Location permission to have access to
        // location information.
        if (!checkCallersLocationPermission(pkgName, featureId, uid,
                /* coarseForTargetSdkLessThanQ */ false, null)) {
            Log.e(TAG, "UID " + uid + " has no location permission");
            return false;
        }
        return true;
    }

    /**
     * API to check to validate if a package name belongs to a UID. Throws SecurityException
     * if pkgName does not belongs to a UID
     *
     * @param pkgName package name of the application requesting access
     * @param uid The uid of the package
     *
     */
    public void checkPackage(int uid, String pkgName) throws SecurityException {
        if (pkgName == null) {
            throw new SecurityException("Checking UID " + uid + " but Package Name is Null");
        }
        mAppOps.checkPackage(uid, pkgName);
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
    private boolean isScanAllowedbyApps(String pkgName, @Nullable String featureId, int uid) {
        return noteAppOpAllowed(AppOpsManager.OPSTR_WIFI_SCAN, pkgName, featureId, uid, null);
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
        UserHandle currentUser = UserHandle.of(mWifiPermissionsWrapper.getCurrentUser());
        UserHandle callingUser = UserHandle.getUserHandleForUid(uid);
        return currentUser.equals(callingUser)
                || mUserManager.isSameProfileGroup(currentUser, callingUser);
    }

    private boolean noteAppOpAllowed(String op, String pkgName, @Nullable String featureId,
            int uid, @Nullable String message) {
        return mAppOps.noteOp(op, uid, pkgName, featureId, message) == AppOpsManager.MODE_ALLOWED;
    }

    private boolean checkAppOpAllowed(String op, String pkgName, int uid) {
        return mAppOps.unsafeCheckOp(op, uid, pkgName) == AppOpsManager.MODE_ALLOWED;
    }

    private boolean retrieveLocationManagerIfNecessary() {
        // This is going to be accessed by multiple threads.
        synchronized (mLock) {
            if (mLocationManager == null) {
                mLocationManager =
                        (LocationManager) mContext.getSystemService(Context.LOCATION_SERVICE);
            }
        }
        return mLocationManager != null;
    }

    /**
     * Retrieves a handle to LocationManager (if not already done) and check if location is enabled.
     */
    public boolean isLocationModeEnabled() {
        if (!retrieveLocationManagerIfNecessary()) return false;
        try {
            return mLocationManager.isLocationEnabledForUser(UserHandle.of(
                    mWifiPermissionsWrapper.getCurrentUser()));
        } catch (Exception e) {
            Log.e(TAG, "Failure to get location mode via API, falling back to settings", e);
            return mFrameworkFacade.getIntegerSetting(
                    mContext, Settings.Secure.LOCATION_MODE, Settings.Secure.LOCATION_MODE_OFF)
                    == Settings.Secure.LOCATION_MODE_ON;
        }
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
     * Returns true if the |uid| holds RADIO_SCAN_WITHOUT_LOCATION permission.
     */
    public boolean checkScanWithoutLocationPermission(int uid) {
        return mWifiPermissionsWrapper.getUidPermission(
                android.Manifest.permission.RADIO_SCAN_WITHOUT_LOCATION, uid)
                == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Returns true if the |uid| holds LOCAL_MAC_ADDRESS permission.
     */
    public boolean checkLocalMacAddressPermission(int uid) {
        return mWifiPermissionsWrapper.getUidPermission(
                android.Manifest.permission.LOCAL_MAC_ADDRESS, uid)
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

    /**
     * Returns true if the |uid| holds NETWORK_STACK permission.
     */
    public boolean checkNetworkStackPermission(int uid) {
        return mWifiPermissionsWrapper.getUidPermission(
                android.Manifest.permission.NETWORK_STACK, uid)
                == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Returns true if the |uid| holds MAINLINE_NETWORK_STACK permission.
     */
    public boolean checkMainlineNetworkStackPermission(int uid) {
        return mWifiPermissionsWrapper.getUidPermission(
                NetworkStack.PERMISSION_MAINLINE_NETWORK_STACK, uid)
                == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Returns true if the |uid| holds NETWORK_MANAGED_PROVISIONING permission.
     */
    public boolean checkNetworkManagedProvisioningPermission(int uid) {
        return mWifiPermissionsWrapper.getUidPermission(
                android.Manifest.permission.NETWORK_MANAGED_PROVISIONING, uid)
                == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Returns true if the |uid| holds NETWORK_CARRIER_PROVISIONING permission.
     */
    public boolean checkNetworkCarrierProvisioningPermission(int uid) {
        return mWifiPermissionsWrapper.getUidPermission(
                android.Manifest.permission.NETWORK_CARRIER_PROVISIONING, uid)
                == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Returns true if the |uid| holds READ_WIFI_CREDENTIAL permission.
     */
    public boolean checkReadWifiCredentialPermission(int uid) {
        return mWifiPermissionsWrapper.getUidPermission(
                android.Manifest.permission.READ_WIFI_CREDENTIAL, uid)
                == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Returns true if the |callingUid|/\callingPackage| holds SYSTEM_ALERT_WINDOW permission.
     */
    public boolean checkSystemAlertWindowPermission(int callingUid, String callingPackage) {
        final int mode = mAppOps.noteOp(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, callingUid,
                callingPackage, null, null);
        if (mode == AppOpsManager.MODE_DEFAULT) {
            return mWifiPermissionsWrapper.getUidPermission(
                    Manifest.permission.SYSTEM_ALERT_WINDOW, callingUid)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    private static DevicePolicyManager retrieveDevicePolicyManagerFromContext(Context context) {
        DevicePolicyManager devicePolicyManager =
                context.getSystemService(DevicePolicyManager.class);
        if (devicePolicyManager == null
                && context.getPackageManager().hasSystemFeature(
                PackageManager.FEATURE_DEVICE_ADMIN)) {
            Log.w(TAG, "Error retrieving DPM service");
        }
        return devicePolicyManager;
    }

    private DevicePolicyManager retrieveDevicePolicyManagerFromUserContext(int uid) {
        Context userContext = null;
        try {
            userContext = mContext.createPackageContextAsUser(mContext.getPackageName(), 0,
                    UserHandle.getUserHandleForUid(uid));
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, "Unknown package name");
            return null;
        }
        if (userContext == null) {
            Log.e(TAG, "Unable to retrieve user context for " + uid);
            return null;
        }
        return retrieveDevicePolicyManagerFromContext(userContext);
    }

    /**
     * Returns true if the |callingUid|/\callingPackage| is the device owner.
     */
    public boolean isDeviceOwner(int uid, @Nullable String packageName) {
        // Cannot determine if the app is DO/PO if packageName is null. So, will return false to be
        // safe.
        if (packageName == null) {
            Log.e(TAG, "isDeviceOwner: packageName is null, returning false");
            return false;
        }
        DevicePolicyManager devicePolicyManager =
                retrieveDevicePolicyManagerFromContext(mContext);
        if (devicePolicyManager == null) return false;
        long ident = Binder.clearCallingIdentity();
        UserHandle deviceOwnerUser = null;
        ComponentName deviceOwnerComponent = null;
        try {
            deviceOwnerUser = devicePolicyManager.getDeviceOwnerUser();
            deviceOwnerComponent = devicePolicyManager.getDeviceOwnerComponentOnAnyUser();
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
        // no device owner
        if (deviceOwnerUser == null || deviceOwnerComponent == null) return false;
        return deviceOwnerUser.equals(UserHandle.getUserHandleForUid(uid))
                && deviceOwnerComponent.getPackageName().equals(packageName);
    }

    /**
     * Returns true if the |callingUid|/\callingPackage| is the profile owner.
     */
    public boolean isProfileOwner(int uid, @Nullable String packageName) {
        // Cannot determine if the app is DO/PO if packageName is null. So, will return false to be
        // safe.
        if (packageName == null) {
            Log.e(TAG, "isProfileOwner: packageName is null, returning false");
            return false;
        }
        DevicePolicyManager devicePolicyManager =
                retrieveDevicePolicyManagerFromUserContext(uid);
        if (devicePolicyManager == null) return false;
        return devicePolicyManager.isProfileOwnerApp(packageName);
    }
}
