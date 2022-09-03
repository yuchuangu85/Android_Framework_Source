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

package com.android.server.pm.pkg;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.Size;
import android.annotation.UserIdInt;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.SharedLibraryInfo;
import android.content.pm.SigningInfo;
import android.util.SparseArray;

import com.android.server.pm.PackageSetting;
import com.android.server.pm.Settings;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The API surface for a {@link PackageSetting}. Methods are expected to return immutable objects.
 * This may mean copying data on each invocation until related classes are refactored to be
 * immutable.
 * <p>
 * Note that until immutability or read-only caching is enabled, {@link PackageSetting} cannot be
 * returned directly, so {@link PackageStateImpl} is used to temporarily copy the data. This is a
 * relatively expensive operation since it has to create an object for every package, but it's much
 * lighter than the alternative of generating {@link PackageInfo} objects.
 * <p>
 * TODO: Documentation TODO: Currently missing, should be exposed as API?
 * <ul>
 *     <li>keySetData</li>
 *     <li>installSource</li>
 *     <li>incrementalStates</li>
 * </ul>
 *
 * @hide
 */
//@SystemApi(client = SystemApi.Client.SYSTEM_SERVER)
public interface PackageState {

    /**
     * This can be null whenever a physical APK on device is missing. This can be the result of
     * removing an external storage device where the APK resides.
     * <p>
     * This will result in the system reading the {@link PackageSetting} from disk, but without
     * being able to parse the base APK's AndroidManifest.xml to read all of its metadata. The data
     * that is written and read in {@link Settings} includes a minimal set of metadata needed to
     * perform other checks in the system.
     * <p>
     * This is important in order to enforce uniqueness within the system, as the package, even if
     * on a removed storage device, is still considered installed. Another package of the same
     * application ID or declaring the same permissions or similar cannot be installed.
     * <p>
     * Re-attaching the storage device to make the APK available should allow the user to use the
     * app once the device reboots or otherwise re-scans it.
     */
    @Nullable
    AndroidPackageApi getAndroidPackage();

    /**
     * The non-user-specific UID, or the UID if the user ID is
     * {@link android.os.UserHandle#USER_SYSTEM}.
     */
    int getAppId();

    /**
     * Value set through {@link PackageManager#setApplicationCategoryHint(String, int)}. Only
     * applied if the application itself does not declare a category.
     *
     * @see AndroidPackageApi#getCategory()
     */
    int getCategoryOverride();

    /**
     * The install time CPU override, if any. This value is written at install time
     * and doesn't change during the life of an install. If non-null,
     * {@link #getPrimaryCpuAbi()} will also contain the same value.
     */
    @Nullable
    String getCpuAbiOverride();

    /**
     * In epoch milliseconds. The last modified time of the file directory which houses the app
     * APKs. Only updated on package update; does not track realtime modifications.
     */
    long getLastModifiedTime();

    /**
     * An aggregation across the framework of the last time an app was used for a particular reason.
     * Keys are indexes into the array represented by {@link PackageManager.NotifyReason}, values
     * are in epoch milliseconds.
     */
    @Size(PackageManager.NOTIFY_PACKAGE_USE_REASONS_COUNT)
    @NonNull
    long[] getLastPackageUsageTime();

    /**
     * In epoch milliseconds. The timestamp of the last time the package on device went through
     * an update package installation.
     */
    long getLastUpdateTime();

    /**
     * Cached here in case the physical code directory on device is unmounted.
     * @see AndroidPackageApi#getLongVersionCode()
     */
    long getVersionCode();

    /**
     * Maps mime group name to the set of Mime types in a group. Mime groups declared by app are
     * populated with empty sets at construction. Mime groups can not be created/removed at runtime,
     * thus keys in this map should not change.
     */
    @NonNull
    Map<String, Set<String>> getMimeGroups();

    /**
     * @see AndroidPackageApi#getPackageName()
     */
    @NonNull
    String getPackageName();

    /**
     * TODO: Rename this to getCodePath
     * @see AndroidPackageApi#getPath()
     */
    @NonNull
    File getPath();

    /**
     * @see ApplicationInfo#primaryCpuAbi
     */
    @Nullable
    String getPrimaryCpuAbi();

    /**
     * @see ApplicationInfo#secondaryCpuAbi
     */
    @Nullable
    String getSecondaryCpuAbi();

    /**
     * Whether the package shares the same user ID as other packages
     */
    boolean hasSharedUser();

    /**
     * Retrieves the shared user app ID. Note that the actual shared user data is not available here
     * and must be queried separately.
     *
     * @return the app ID of the shared user that this package is a part of, or -1 if it's not part
     * of a shared user.
     */
    int getSharedUserAppId();

    @NonNull
    SigningInfo getSigningInfo();

    @NonNull
    SparseArray<? extends PackageUserState> getUserStates();

    /**
     * @return the result of {@link #getUserStates()}.get(userId) or
     * {@link PackageUserState#DEFAULT} if the state doesn't exist.
     */
    @NonNull
    default PackageUserState getUserStateOrDefault(@UserIdInt int userId) {
        PackageUserState userState = getUserStates().get(userId);
        return userState == null ? PackageUserState.DEFAULT : userState;
    }

    /**
     * The actual files resolved for each shared library.
     *
     * @see R.styleable#AndroidManifestUsesLibrary
     */
    @NonNull
    List<String> getUsesLibraryFiles();

    /**
     * @see R.styleable#AndroidManifestUsesLibrary
     */
    @NonNull
    List<SharedLibraryInfo> getUsesLibraryInfos();

    /**
     * @see R.styleable#AndroidManifestUsesSdkLibrary
     */
    @NonNull
    String[] getUsesSdkLibraries();

    /**
     * @see R.styleable#AndroidManifestUsesSdkLibrary_versionMajor
     */
    @NonNull
    long[] getUsesSdkLibrariesVersionsMajor();

    /**
     * @see R.styleable#AndroidManifestUsesStaticLibrary
     */
    @NonNull
    String[] getUsesStaticLibraries();

    /**
     * @see R.styleable#AndroidManifestUsesStaticLibrary_version
     */
    @NonNull
    long[] getUsesStaticLibrariesVersions();

    /**
     * @see AndroidPackageApi#getVolumeUuid()
     */
    @Nullable
    String getVolumeUuid();

    /**
     * @see AndroidPackageApi#isExternalStorage()
     */
    boolean isExternalStorage();

    /**
     * Whether a package was installed --force-queryable such that it is always queryable by any
     * package, regardless of their manifest content.
     */
    boolean isForceQueryableOverride();

    /**
     * Whether a package is treated as hidden until it is installed for a user.
     *
     * @see PackageManager#MATCH_HIDDEN_UNTIL_INSTALLED_COMPONENTS
     * @see PackageManager#setSystemAppState
     */
    boolean isHiddenUntilInstalled();

    /**
     * @see com.android.server.pm.permission.UserPermissionState
     */
    boolean isInstallPermissionsFixed();

    /**
     * @see AndroidPackageApi#isOdm()
     */
    boolean isOdm();

    /**
     * @see AndroidPackageApi#isOem()
     */
    boolean isOem();

    /**
     * @see AndroidPackageApi#isPrivileged()
     */
    boolean isPrivileged();

    /**
     * @see AndroidPackageApi#isProduct()
     */
    boolean isProduct();

    /**
     * @see ApplicationInfo#PRIVATE_FLAG_REQUIRED_FOR_SYSTEM_USER
     */
    boolean isRequiredForSystemUser();

    /**
     * @see AndroidPackageApi#isSystem()
     */
    boolean isSystem();

    /**
     * @see AndroidPackageApi#isSystemExt()
     */
    boolean isSystemExt();

    /**
     * Whether or not an update is available. Ostensibly only for instant apps.
     */
    boolean isUpdateAvailable();

    /**
     * Whether this app is on the /data partition having been upgraded from a preinstalled app on a
     * system partition.
     */
    boolean isUpdatedSystemApp();

    /**
     * @see AndroidPackageApi#isVendor()
     */
    boolean isVendor();
}
