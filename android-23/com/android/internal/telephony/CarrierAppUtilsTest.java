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
 * limitations under the License
 */

package com.android.internal.telephony;

import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.telephony.TelephonyManager;
import android.test.InstrumentationTestCase;

import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

public class CarrierAppUtilsTest extends InstrumentationTestCase {
    private static final String CARRIER_APP = "com.example.carrier";
    private static final String[] CARRIER_APPS = new String[] { CARRIER_APP };
    private static final int USER_ID = 12345;
    private static final String CALLING_PACKAGE = "phone";

    @Mock private IPackageManager mPackageManager;
    @Mock private TelephonyManager mTelephonyManager;

    @Override
    public void setUp() throws Exception {
        super.setUp();
        System.setProperty("dexmaker.dexcache",
                getInstrumentation().getTargetContext().getCacheDir().getPath());
        Thread.currentThread().setContextClassLoader(getClass().getClassLoader());
        MockitoAnnotations.initMocks(this);
    }

    /** No apps configured - should do nothing. */
    public void testDisableCarrierAppsUntilPrivileged_EmptyList() {
        CarrierAppUtils.disableCarrierAppsUntilPrivileged(CALLING_PACKAGE, mPackageManager,
                mTelephonyManager, USER_ID, new String[0]);
        Mockito.verifyNoMoreInteractions(mPackageManager, mTelephonyManager);
    }

    /** Configured app is missing - should do nothing. */
    public void testDisableCarrierAppsUntilPrivileged_MissingApp() throws Exception {
        Mockito.when(mPackageManager.getApplicationInfo("com.example.missing.app",
                PackageManager.GET_DISABLED_UNTIL_USED_COMPONENTS, USER_ID)).thenReturn(null);
        CarrierAppUtils.disableCarrierAppsUntilPrivileged(CALLING_PACKAGE, mPackageManager,
                mTelephonyManager, USER_ID, new String[] { "com.example.missing.app" });
        Mockito.verify(mPackageManager, Mockito.never()).setApplicationEnabledSetting(
                Mockito.anyString(), Mockito.anyInt(), Mockito.anyInt(), Mockito.anyInt(),
                Mockito.anyString());
        Mockito.verify(mPackageManager, Mockito.never())
                .grantDefaultPermissionsToEnabledCarrierApps(Mockito.any(String[].class),
                        Mockito.anyInt());
        Mockito.verifyNoMoreInteractions(mTelephonyManager);
    }

    /** Configured app is not bundled with the system - should do nothing. */
    public void testDisableCarrierAppsUntilPrivileged_NonSystemApp() throws Exception {
        ApplicationInfo appInfo = new ApplicationInfo();
        Mockito.when(mPackageManager.getApplicationInfo(CARRIER_APP,
                PackageManager.GET_DISABLED_UNTIL_USED_COMPONENTS, USER_ID)).thenReturn(appInfo);
        CarrierAppUtils.disableCarrierAppsUntilPrivileged(CALLING_PACKAGE, mPackageManager,
                mTelephonyManager, USER_ID, CARRIER_APPS);
        Mockito.verify(mPackageManager, Mockito.never()).setApplicationEnabledSetting(
                Mockito.anyString(), Mockito.anyInt(), Mockito.anyInt(), Mockito.anyInt(),
                Mockito.anyString());
        Mockito.verify(mPackageManager, Mockito.never())
                .grantDefaultPermissionsToEnabledCarrierApps(
                        Mockito.any(String[].class), Mockito.anyInt());
        Mockito.verifyNoMoreInteractions(mTelephonyManager);
    }

    /**
     * Configured app has privileges, but was disabled by the user - should only grant
     * permissions.
     */
    public void testDisableCarrierAppsUntilPrivileged_HasPrivileges_DisabledUser()
            throws Exception {
        ApplicationInfo appInfo = new ApplicationInfo();
        appInfo.packageName = CARRIER_APP;
        appInfo.flags |= ApplicationInfo.FLAG_SYSTEM;
        appInfo.enabledSetting = PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER;
        Mockito.when(mPackageManager.getApplicationInfo(CARRIER_APP,
                PackageManager.GET_DISABLED_UNTIL_USED_COMPONENTS, USER_ID)).thenReturn(appInfo);
        Mockito.when(mTelephonyManager.checkCarrierPrivilegesForPackageAnyPhone(CARRIER_APP))
                .thenReturn(TelephonyManager.CARRIER_PRIVILEGE_STATUS_HAS_ACCESS);
        CarrierAppUtils.disableCarrierAppsUntilPrivileged(CALLING_PACKAGE, mPackageManager,
                mTelephonyManager, USER_ID, CARRIER_APPS);
        Mockito.verify(mPackageManager, Mockito.never()).setApplicationEnabledSetting(
                Mockito.anyString(), Mockito.anyInt(), Mockito.anyInt(), Mockito.anyInt(),
                Mockito.anyString());
        Mockito.verify(mPackageManager).grantDefaultPermissionsToEnabledCarrierApps(
                new String[] {appInfo.packageName}, USER_ID);
    }

    /** Configured app has privileges, but was disabled - should only grant permissions. */
    public void testDisableCarrierAppsUntilPrivileged_HasPrivileges_Disabled() throws Exception {
        ApplicationInfo appInfo = new ApplicationInfo();
        appInfo.packageName = CARRIER_APP;
        appInfo.flags |= ApplicationInfo.FLAG_SYSTEM;
        appInfo.enabledSetting = PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
        Mockito.when(mPackageManager.getApplicationInfo(CARRIER_APP,
                PackageManager.GET_DISABLED_UNTIL_USED_COMPONENTS, USER_ID)).thenReturn(appInfo);
        Mockito.when(mTelephonyManager.checkCarrierPrivilegesForPackageAnyPhone(CARRIER_APP))
                .thenReturn(TelephonyManager.CARRIER_PRIVILEGE_STATUS_HAS_ACCESS);
        CarrierAppUtils.disableCarrierAppsUntilPrivileged(CALLING_PACKAGE, mPackageManager,
                mTelephonyManager, USER_ID, CARRIER_APPS);
        Mockito.verify(mPackageManager, Mockito.never()).setApplicationEnabledSetting(
                Mockito.anyString(), Mockito.anyInt(), Mockito.anyInt(), Mockito.anyInt(),
                Mockito.anyString());
        Mockito.verify(mPackageManager).grantDefaultPermissionsToEnabledCarrierApps(
                new String[] {appInfo.packageName}, USER_ID);
    }

    /** Configured app has privileges, and is already enabled - should only grant permissions. */
    public void testDisableCarrierAppsUntilPrivileged_HasPrivileges_Enabled() throws Exception {
        ApplicationInfo appInfo = new ApplicationInfo();
        appInfo.packageName = CARRIER_APP;
        appInfo.flags |= ApplicationInfo.FLAG_SYSTEM;
        appInfo.enabledSetting = PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
        Mockito.when(mPackageManager.getApplicationInfo(CARRIER_APP,
                PackageManager.GET_DISABLED_UNTIL_USED_COMPONENTS, USER_ID)).thenReturn(appInfo);
        Mockito.when(mTelephonyManager.checkCarrierPrivilegesForPackageAnyPhone(CARRIER_APP))
                .thenReturn(TelephonyManager.CARRIER_PRIVILEGE_STATUS_HAS_ACCESS);
        CarrierAppUtils.disableCarrierAppsUntilPrivileged(CALLING_PACKAGE, mPackageManager,
                mTelephonyManager, USER_ID, CARRIER_APPS);
        Mockito.verify(mPackageManager, Mockito.never()).setApplicationEnabledSetting(
                Mockito.anyString(), Mockito.anyInt(), Mockito.anyInt(), Mockito.anyInt(),
                Mockito.anyString());
        Mockito.verify(mPackageManager).grantDefaultPermissionsToEnabledCarrierApps(
                new String[] {appInfo.packageName}, USER_ID);
    }

    /** Configured /data app has privileges - should only grant permissions. */
    public void testDisableCarrierAppsUntilPrivileged_HasPrivileges_UpdatedApp() throws Exception {
        ApplicationInfo appInfo = new ApplicationInfo();
        appInfo.packageName = CARRIER_APP;
        appInfo.flags |= ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP;
        appInfo.enabledSetting = PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
        Mockito.when(mPackageManager.getApplicationInfo(CARRIER_APP,
                PackageManager.GET_DISABLED_UNTIL_USED_COMPONENTS, USER_ID)).thenReturn(appInfo);
        Mockito.when(mTelephonyManager.checkCarrierPrivilegesForPackageAnyPhone(CARRIER_APP))
                .thenReturn(TelephonyManager.CARRIER_PRIVILEGE_STATUS_HAS_ACCESS);
        CarrierAppUtils.disableCarrierAppsUntilPrivileged(CALLING_PACKAGE, mPackageManager,
                mTelephonyManager, USER_ID, CARRIER_APPS);
        Mockito.verify(mPackageManager, Mockito.never()).setApplicationEnabledSetting(
                Mockito.anyString(), Mockito.anyInt(), Mockito.anyInt(), Mockito.anyInt(),
                Mockito.anyString());
        Mockito.verify(mPackageManager).grantDefaultPermissionsToEnabledCarrierApps(
                new String[] {appInfo.packageName}, USER_ID);
    }

    /** Configured app has privileges, and is in the default state - should enable. */
    public void testDisableCarrierAppsUntilPrivileged_HasPrivileges_Default() throws Exception {
        ApplicationInfo appInfo = new ApplicationInfo();
        appInfo.packageName = CARRIER_APP;
        appInfo.flags |= ApplicationInfo.FLAG_SYSTEM;
        appInfo.enabledSetting = PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
        Mockito.when(mPackageManager.getApplicationInfo(CARRIER_APP,
                PackageManager.GET_DISABLED_UNTIL_USED_COMPONENTS, USER_ID)).thenReturn(appInfo);
        Mockito.when(mTelephonyManager.checkCarrierPrivilegesForPackageAnyPhone(CARRIER_APP))
                .thenReturn(TelephonyManager.CARRIER_PRIVILEGE_STATUS_HAS_ACCESS);
        CarrierAppUtils.disableCarrierAppsUntilPrivileged(CALLING_PACKAGE, mPackageManager,
                mTelephonyManager, USER_ID, CARRIER_APPS);
        Mockito.verify(mPackageManager).setApplicationEnabledSetting(
                CARRIER_APP, PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP, USER_ID, CALLING_PACKAGE);
        Mockito.verify(mPackageManager).grantDefaultPermissionsToEnabledCarrierApps(
                new String[] {appInfo.packageName}, USER_ID);
    }

    /** Configured app has privileges, and is disabled until used - should enable. */
    public void testDisableCarrierAppsUntilPrivileged_HasPrivileges_DisabledUntilUsed()
            throws Exception {
        ApplicationInfo appInfo = new ApplicationInfo();
        appInfo.packageName = CARRIER_APP;
        appInfo.flags |= ApplicationInfo.FLAG_SYSTEM;
        appInfo.enabledSetting = PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED;
        Mockito.when(mPackageManager.getApplicationInfo(CARRIER_APP,
                PackageManager.GET_DISABLED_UNTIL_USED_COMPONENTS, USER_ID)).thenReturn(appInfo);
        Mockito.when(mTelephonyManager.checkCarrierPrivilegesForPackageAnyPhone(CARRIER_APP))
                .thenReturn(TelephonyManager.CARRIER_PRIVILEGE_STATUS_HAS_ACCESS);
        CarrierAppUtils.disableCarrierAppsUntilPrivileged(CALLING_PACKAGE, mPackageManager,
                mTelephonyManager, USER_ID, CARRIER_APPS);
        Mockito.verify(mPackageManager).setApplicationEnabledSetting(
                CARRIER_APP, PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP, USER_ID, CALLING_PACKAGE);
        Mockito.verify(mPackageManager).grantDefaultPermissionsToEnabledCarrierApps(
                new String[] {appInfo.packageName}, USER_ID);
    }

    /** Configured app has no privileges, and was disabled by the user - should do nothing. */
    public void testDisableCarrierAppsUntilPrivileged_NoPrivileges_DisabledUser() throws Exception {
        ApplicationInfo appInfo = new ApplicationInfo();
        appInfo.packageName = CARRIER_APP;
        appInfo.flags |= ApplicationInfo.FLAG_SYSTEM;
        appInfo.enabledSetting = PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER;
        Mockito.when(mPackageManager.getApplicationInfo(CARRIER_APP,
                PackageManager.GET_DISABLED_UNTIL_USED_COMPONENTS, USER_ID)).thenReturn(appInfo);
        Mockito.when(mTelephonyManager.checkCarrierPrivilegesForPackageAnyPhone(CARRIER_APP))
                .thenReturn(TelephonyManager.CARRIER_PRIVILEGE_STATUS_NO_ACCESS);
        CarrierAppUtils.disableCarrierAppsUntilPrivileged(CALLING_PACKAGE, mPackageManager,
                mTelephonyManager, USER_ID, CARRIER_APPS);
        Mockito.verify(mPackageManager, Mockito.never()).setApplicationEnabledSetting(
                Mockito.anyString(), Mockito.anyInt(), Mockito.anyInt(), Mockito.anyInt(),
                Mockito.anyString());
        Mockito.verify(mPackageManager, Mockito.never())
                .grantDefaultPermissionsToEnabledCarrierApps(
                        Mockito.any(String[].class), Mockito.anyInt());
    }

    /** Configured app has no privileges, and was disabled - should do nothing. */
    public void testDisableCarrierAppsUntilPrivileged_NoPrivileges_Disabled() throws Exception {
        ApplicationInfo appInfo = new ApplicationInfo();
        appInfo.packageName = CARRIER_APP;
        appInfo.flags |= ApplicationInfo.FLAG_SYSTEM;
        appInfo.enabledSetting = PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
        Mockito.when(mPackageManager.getApplicationInfo(CARRIER_APP,
                PackageManager.GET_DISABLED_UNTIL_USED_COMPONENTS, USER_ID)).thenReturn(appInfo);
        Mockito.when(mTelephonyManager.checkCarrierPrivilegesForPackageAnyPhone(CARRIER_APP))
                .thenReturn(TelephonyManager.CARRIER_PRIVILEGE_STATUS_NO_ACCESS);
        CarrierAppUtils.disableCarrierAppsUntilPrivileged(CALLING_PACKAGE, mPackageManager,
                mTelephonyManager, USER_ID, CARRIER_APPS);
        Mockito.verify(mPackageManager, Mockito.never()).setApplicationEnabledSetting(
                Mockito.anyString(), Mockito.anyInt(), Mockito.anyInt(), Mockito.anyInt(),
                Mockito.anyString());
        Mockito.verify(mPackageManager, Mockito.never())
                .grantDefaultPermissionsToEnabledCarrierApps(
                        Mockito.any(String[].class), Mockito.anyInt());
    }

    /** Configured app has no privileges, and is explicitly enabled - should do nothing. */
    public void testDisableCarrierAppsUntilPrivileged_NoPrivileges_Enabled() throws Exception {
        ApplicationInfo appInfo = new ApplicationInfo();
        appInfo.packageName = CARRIER_APP;
        appInfo.flags |= ApplicationInfo.FLAG_SYSTEM;
        appInfo.enabledSetting = PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
        Mockito.when(mPackageManager.getApplicationInfo(CARRIER_APP,
                PackageManager.GET_DISABLED_UNTIL_USED_COMPONENTS, USER_ID)).thenReturn(appInfo);
        Mockito.when(mTelephonyManager.checkCarrierPrivilegesForPackageAnyPhone(CARRIER_APP))
                .thenReturn(TelephonyManager.CARRIER_PRIVILEGE_STATUS_NO_ACCESS);
        CarrierAppUtils.disableCarrierAppsUntilPrivileged(CALLING_PACKAGE, mPackageManager,
                mTelephonyManager, USER_ID, CARRIER_APPS);
        Mockito.verify(mPackageManager, Mockito.never()).setApplicationEnabledSetting(
                Mockito.anyString(), Mockito.anyInt(), Mockito.anyInt(), Mockito.anyInt(),
                Mockito.anyString());
        Mockito.verify(mPackageManager, Mockito.never())
                .grantDefaultPermissionsToEnabledCarrierApps(
                        Mockito.any(String[].class), Mockito.anyInt());
    }

    /** Configured /data app has no privileges - should do nothing. */
    public void testDisableCarrierAppsUntilPrivileged_NoPrivileges_UpdatedApp() throws Exception {
        ApplicationInfo appInfo = new ApplicationInfo();
        appInfo.packageName = CARRIER_APP;
        appInfo.flags |= ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP;
        appInfo.enabledSetting = PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
        Mockito.when(mPackageManager.getApplicationInfo(CARRIER_APP,
                PackageManager.GET_DISABLED_UNTIL_USED_COMPONENTS, USER_ID)).thenReturn(appInfo);
        Mockito.when(mTelephonyManager.checkCarrierPrivilegesForPackageAnyPhone(CARRIER_APP))
                .thenReturn(TelephonyManager.CARRIER_PRIVILEGE_STATUS_NO_ACCESS);
        CarrierAppUtils.disableCarrierAppsUntilPrivileged(CALLING_PACKAGE, mPackageManager,
                mTelephonyManager, USER_ID, CARRIER_APPS);
        Mockito.verify(mPackageManager, Mockito.never()).setApplicationEnabledSetting(
                Mockito.anyString(), Mockito.anyInt(), Mockito.anyInt(), Mockito.anyInt(),
                Mockito.anyString());
        Mockito.verify(mPackageManager, Mockito.never())
                .grantDefaultPermissionsToEnabledCarrierApps(
                        Mockito.any(String[].class), Mockito.anyInt());
    }

    /** Configured app has no privileges, and is in the default state - should disable until use. */
    public void testDisableCarrierAppsUntilPrivileged_NoPrivileges_Default() throws Exception {
        ApplicationInfo appInfo = new ApplicationInfo();
        appInfo.packageName = CARRIER_APP;
        appInfo.flags |= ApplicationInfo.FLAG_SYSTEM;
        appInfo.enabledSetting = PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
        Mockito.when(mPackageManager.getApplicationInfo(CARRIER_APP,
                PackageManager.GET_DISABLED_UNTIL_USED_COMPONENTS, USER_ID)).thenReturn(appInfo);
        Mockito.when(mTelephonyManager.checkCarrierPrivilegesForPackageAnyPhone(CARRIER_APP))
                .thenReturn(TelephonyManager.CARRIER_PRIVILEGE_STATUS_NO_ACCESS);
        CarrierAppUtils.disableCarrierAppsUntilPrivileged(CALLING_PACKAGE, mPackageManager,
                mTelephonyManager, USER_ID, CARRIER_APPS);
        Mockito.verify(mPackageManager).setApplicationEnabledSetting(
                CARRIER_APP, PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED, 0, USER_ID,
                CALLING_PACKAGE);
        Mockito.verify(mPackageManager, Mockito.never())
                .grantDefaultPermissionsToEnabledCarrierApps(
                        Mockito.any(String[].class), Mockito.anyInt());
    }

    /** Configured app has no privileges, and is disabled until used - should do nothing. */
    public void testDisableCarrierAppsUntilPrivileged_NoPrivileges_DisabledUntilUsed()
            throws Exception {
        ApplicationInfo appInfo = new ApplicationInfo();
        appInfo.packageName = CARRIER_APP;
        appInfo.flags |= ApplicationInfo.FLAG_SYSTEM;
        appInfo.enabledSetting = PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED;
        Mockito.when(mPackageManager.getApplicationInfo(CARRIER_APP,
                PackageManager.GET_DISABLED_UNTIL_USED_COMPONENTS, USER_ID)).thenReturn(appInfo);
        Mockito.when(mTelephonyManager.checkCarrierPrivilegesForPackageAnyPhone(CARRIER_APP))
                .thenReturn(TelephonyManager.CARRIER_PRIVILEGE_STATUS_NO_ACCESS);
        CarrierAppUtils.disableCarrierAppsUntilPrivileged(CALLING_PACKAGE, mPackageManager,
                mTelephonyManager, USER_ID, CARRIER_APPS);
        Mockito.verify(mPackageManager, Mockito.never()).setApplicationEnabledSetting(
                Mockito.anyString(), Mockito.anyInt(), Mockito.anyInt(), Mockito.anyInt(),
                Mockito.anyString());
        Mockito.verify(mPackageManager, Mockito.never())
                .grantDefaultPermissionsToEnabledCarrierApps(
                        Mockito.any(String[].class), Mockito.anyInt());
    }
}

