/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.pm;

import static android.os.PowerExemptionManager.REASON_LOCKED_BOOT_COMPLETED;
import static android.os.PowerExemptionManager.TEMPORARY_ALLOW_LIST_TYPE_FOREGROUND_SERVICE_ALLOWED;
import static android.safetylabel.SafetyLabelConstants.SAFETY_LABEL_CHANGE_NOTIFICATIONS_ENABLED;

import static com.android.server.pm.PackageManagerService.DEBUG_INSTALL;
import static com.android.server.pm.PackageManagerService.PACKAGE_SCHEME;
import static com.android.server.pm.PackageManagerService.PLATFORM_PACKAGE_NAME;
import static com.android.server.pm.PackageManagerService.TAG;

import android.Manifest;
import android.annotation.AppIdInt;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.BroadcastOptions;
import android.app.IActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.IIntentReceiver;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.PowerExemptionManager;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.DeviceConfig;
import android.util.IntArray;
import android.util.Log;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.util.ArrayUtils;

import java.util.ArrayList;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/**
 * Helper class to send broadcasts for various situations.
 */
public final class BroadcastHelper {
    private static final boolean DEBUG_BROADCASTS = false;
    /**
     * Permissions required in order to receive instant application lifecycle broadcasts.
     */
    private static final String[] INSTANT_APP_BROADCAST_PERMISSION =
            new String[]{android.Manifest.permission.ACCESS_INSTANT_APPS};

    private final UserManagerInternal mUmInternal;
    private final ActivityManagerInternal mAmInternal;
    private final Context mContext;

    BroadcastHelper(PackageManagerServiceInjector injector) {
        mUmInternal = injector.getUserManagerInternal();
        mAmInternal = injector.getActivityManagerInternal();
        mContext = injector.getContext();
    }

    public void sendPackageBroadcast(final String action, final String pkg, final Bundle extras,
            final int flags, final String targetPkg, final IIntentReceiver finishedReceiver,
            final int[] userIds, int[] instantUserIds,
            @Nullable SparseArray<int[]> broadcastAllowList,
            @Nullable BiFunction<Integer, Bundle, Bundle> filterExtrasForReceiver,
            @Nullable Bundle bOptions) {
        try {
            final IActivityManager am = ActivityManager.getService();
            if (am == null) return;
            final int[] resolvedUserIds;
            if (userIds == null) {
                resolvedUserIds = am.getRunningUserIds();
            } else {
                resolvedUserIds = userIds;
            }

            if (ArrayUtils.isEmpty(instantUserIds)) {
                doSendBroadcast(action, pkg, extras, flags, targetPkg, finishedReceiver,
                        resolvedUserIds, false /* isInstantApp */, broadcastAllowList,
                        filterExtrasForReceiver, bOptions);
            } else {
                // send restricted broadcasts for instant apps
                doSendBroadcast(action, pkg, extras, flags, targetPkg, finishedReceiver,
                        instantUserIds, true /* isInstantApp */, null,
                        null /* filterExtrasForReceiver */, bOptions);
            }
        } catch (RemoteException ex) {
        }
    }

    /**
     * Sends a broadcast for the given action.
     * <p>If {@code isInstantApp} is {@code true}, then the broadcast is protected with
     * the {@link android.Manifest.permission#ACCESS_INSTANT_APPS} permission. This allows
     * the system and applications allowed to see instant applications to receive package
     * lifecycle events for instant applications.
     */
    public void doSendBroadcast(String action, String pkg, Bundle extras,
            int flags, String targetPkg, IIntentReceiver finishedReceiver,
            int[] userIds, boolean isInstantApp, @Nullable SparseArray<int[]> broadcastAllowList,
            @Nullable BiFunction<Integer, Bundle, Bundle> filterExtrasForReceiver,
            @Nullable Bundle bOptions) {
        for (int userId : userIds) {
            final Intent intent = new Intent(action,
                    pkg != null ? Uri.fromParts(PACKAGE_SCHEME, pkg, null) : null);
            final String[] requiredPermissions =
                    isInstantApp ? INSTANT_APP_BROADCAST_PERMISSION : null;
            if (extras != null) {
                intent.putExtras(extras);
            }
            if (targetPkg != null) {
                intent.setPackage(targetPkg);
            }
            // Modify the UID when posting to other users
            int uid = intent.getIntExtra(Intent.EXTRA_UID, -1);
            if (uid >= 0 && UserHandle.getUserId(uid) != userId) {
                uid = UserHandle.getUid(userId, UserHandle.getAppId(uid));
                intent.putExtra(Intent.EXTRA_UID, uid);
            }
            if (broadcastAllowList != null && PLATFORM_PACKAGE_NAME.equals(targetPkg)) {
                intent.putExtra(Intent.EXTRA_VISIBILITY_ALLOW_LIST,
                         broadcastAllowList.get(userId));
            }
            intent.putExtra(Intent.EXTRA_USER_HANDLE, userId);
            intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT | flags);
            if (DEBUG_BROADCASTS) {
                RuntimeException here = new RuntimeException("here");
                here.fillInStackTrace();
                Slog.d(TAG, "Sending to user " + userId + ": "
                        + intent.toShortString(false, true, false, false)
                        + " " + intent.getExtras(), here);
            }
            final boolean ordered;
            if (mAmInternal.isModernQueueEnabled()) {
                // When the modern broadcast stack is enabled, deliver all our
                // broadcasts as unordered, since the modern stack has better
                // support for sequencing cold-starts, and it supports
                // delivering resultTo for non-ordered broadcasts
                ordered = false;
            } else {
                ordered = (finishedReceiver != null);
            }
            mAmInternal.broadcastIntent(
                    intent, finishedReceiver, requiredPermissions, ordered, userId,
                    broadcastAllowList == null ? null : broadcastAllowList.get(userId),
                    filterExtrasForReceiver, bOptions);
        }
    }

    public void sendResourcesChangedBroadcast(@NonNull Supplier<Computer> snapshotComputer,
            boolean mediaStatus, boolean replacing, @NonNull String[] pkgNames,
            @NonNull int[] uids) {
        if (ArrayUtils.isEmpty(pkgNames) || ArrayUtils.isEmpty(uids)) {
            return;
        }
        Bundle extras = new Bundle();
        extras.putStringArray(Intent.EXTRA_CHANGED_PACKAGE_LIST, pkgNames);
        extras.putIntArray(Intent.EXTRA_CHANGED_UID_LIST, uids);
        if (replacing) {
            extras.putBoolean(Intent.EXTRA_REPLACING, replacing);
        }
        String action = mediaStatus ? Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE
                : Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE;
        sendPackageBroadcast(action, null /* pkg */, extras, 0 /* flags */,
                null /* targetPkg */, null /* finishedReceiver */, null /* userIds */,
                null /* instantUserIds */, null /* broadcastAllowList */,
                (callingUid, intentExtras) -> filterExtrasChangedPackageList(
                        snapshotComputer.get(), callingUid, intentExtras),
                null /* bOptions */);
    }

    /**
     * The just-installed/enabled app is bundled on the system, so presumed to be able to run
     * automatically without needing an explicit launch.
     * Send it a LOCKED_BOOT_COMPLETED/BOOT_COMPLETED if it would ordinarily have gotten ones.
     */
    public void sendBootCompletedBroadcastToSystemApp(
            String packageName, boolean includeStopped, int userId) {
        // If user is not running, the app didn't miss any broadcast
        if (!mUmInternal.isUserRunning(userId)) {
            return;
        }
        final IActivityManager am = ActivityManager.getService();
        try {
            // Deliver LOCKED_BOOT_COMPLETED first
            Intent lockedBcIntent = new Intent(Intent.ACTION_LOCKED_BOOT_COMPLETED)
                    .setPackage(packageName);
            if (includeStopped) {
                lockedBcIntent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            }
            final String[] requiredPermissions = {Manifest.permission.RECEIVE_BOOT_COMPLETED};
            final BroadcastOptions bOptions = getTemporaryAppAllowlistBroadcastOptions(
                    REASON_LOCKED_BOOT_COMPLETED);
            am.broadcastIntentWithFeature(null, null, lockedBcIntent, null, null, 0, null, null,
                    requiredPermissions, null, null, android.app.AppOpsManager.OP_NONE,
                    bOptions.toBundle(), false, false, userId);

            // Deliver BOOT_COMPLETED only if user is unlocked
            if (mUmInternal.isUserUnlockingOrUnlocked(userId)) {
                Intent bcIntent = new Intent(Intent.ACTION_BOOT_COMPLETED).setPackage(packageName);
                if (includeStopped) {
                    bcIntent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
                }
                am.broadcastIntentWithFeature(null, null, bcIntent, null, null, 0, null, null,
                        requiredPermissions, null, null, android.app.AppOpsManager.OP_NONE,
                        bOptions.toBundle(), false, false, userId);
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    public @NonNull BroadcastOptions getTemporaryAppAllowlistBroadcastOptions(
            @PowerExemptionManager.ReasonCode int reasonCode) {
        long duration = 10_000;
        if (mAmInternal != null) {
            duration = mAmInternal.getBootTimeTempAllowListDuration();
        }
        final BroadcastOptions bOptions = BroadcastOptions.makeBasic();
        bOptions.setTemporaryAppAllowlist(duration,
                TEMPORARY_ALLOW_LIST_TYPE_FOREGROUND_SERVICE_ALLOWED,
                reasonCode, "");
        return bOptions;
    }

    public void sendPackageChangedBroadcast(String packageName, boolean dontKillApp,
            ArrayList<String> componentNames, int packageUid, String reason,
            int[] userIds, int[] instantUserIds, SparseArray<int[]> broadcastAllowList) {
        if (DEBUG_INSTALL) {
            Log.v(TAG, "Sending package changed: package=" + packageName + " components="
                    + componentNames);
        }
        Bundle extras = new Bundle(4);
        extras.putString(Intent.EXTRA_CHANGED_COMPONENT_NAME, componentNames.get(0));
        String[] nameList = new String[componentNames.size()];
        componentNames.toArray(nameList);
        extras.putStringArray(Intent.EXTRA_CHANGED_COMPONENT_NAME_LIST, nameList);
        extras.putBoolean(Intent.EXTRA_DONT_KILL_APP, dontKillApp);
        extras.putInt(Intent.EXTRA_UID, packageUid);
        if (reason != null) {
            extras.putString(Intent.EXTRA_REASON, reason);
        }
        // If this is not reporting a change of the overall package, then only send it
        // to registered receivers.  We don't want to launch a swath of apps for every
        // little component state change.
        final int flags = !componentNames.contains(packageName)
                ? Intent.FLAG_RECEIVER_REGISTERED_ONLY : 0;
        sendPackageBroadcast(Intent.ACTION_PACKAGE_CHANGED, packageName, extras, flags, null, null,
                userIds, instantUserIds, broadcastAllowList, null /* filterExtrasForReceiver */,
                null /* bOptions */);
    }

    public static void sendDeviceCustomizationReadyBroadcast() {
        final Intent intent = new Intent(Intent.ACTION_DEVICE_CUSTOMIZATION_READY);
        intent.setFlags(Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND);
        final IActivityManager am = ActivityManager.getService();
        final String[] requiredPermissions = {
                Manifest.permission.RECEIVE_DEVICE_CUSTOMIZATION_READY,
        };
        try {
            am.broadcastIntentWithFeature(null, null, intent, null, null, 0, null, null,
                    requiredPermissions, null, null, android.app.AppOpsManager.OP_NONE, null, false,
                    false, UserHandle.USER_ALL);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    public void sendSessionCommitBroadcast(PackageInstaller.SessionInfo sessionInfo, int userId,
            int launcherUid, @Nullable ComponentName launcherComponent,
            @Nullable String appPredictionServicePackage) {
        if (launcherComponent != null) {
            Intent launcherIntent = new Intent(PackageInstaller.ACTION_SESSION_COMMITTED)
                    .putExtra(PackageInstaller.EXTRA_SESSION, sessionInfo)
                    .putExtra(Intent.EXTRA_USER, UserHandle.of(userId))
                    .setPackage(launcherComponent.getPackageName());
            mContext.sendBroadcastAsUser(launcherIntent, UserHandle.of(launcherUid));
        }
        // TODO(b/122900055) Change/Remove this and replace with new permission role.
        if (appPredictionServicePackage != null) {
            Intent predictorIntent = new Intent(PackageInstaller.ACTION_SESSION_COMMITTED)
                    .putExtra(PackageInstaller.EXTRA_SESSION, sessionInfo)
                    .putExtra(Intent.EXTRA_USER, UserHandle.of(userId))
                    .setPackage(appPredictionServicePackage);
            mContext.sendBroadcastAsUser(predictorIntent, UserHandle.of(launcherUid));
        }
    }

    public void sendPreferredActivityChangedBroadcast(int userId) {
        final IActivityManager am = ActivityManager.getService();
        if (am == null) {
            return;
        }

        final Intent intent = new Intent(Intent.ACTION_PREFERRED_ACTIVITY_CHANGED);
        intent.putExtra(Intent.EXTRA_USER_HANDLE, userId);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        try {
            am.broadcastIntentWithFeature(null, null, intent, null, null,
                    0, null, null, null, null, null, android.app.AppOpsManager.OP_NONE,
                    null, false, false, userId);
        } catch (RemoteException e) {
        }
    }

    public void sendPackageAddedForNewUsers(String packageName,
            @AppIdInt int appId, int[] userIds, int[] instantUserIds,
            int dataLoaderType, SparseArray<int[]> broadcastAllowlist) {
        Bundle extras = new Bundle(1);
        // Set to UID of the first user, EXTRA_UID is automatically updated in sendPackageBroadcast
        final int uid = UserHandle.getUid(
                (ArrayUtils.isEmpty(userIds) ? instantUserIds[0] : userIds[0]), appId);
        extras.putInt(Intent.EXTRA_UID, uid);
        extras.putInt(PackageInstaller.EXTRA_DATA_LOADER_TYPE, dataLoaderType);

        sendPackageBroadcast(Intent.ACTION_PACKAGE_ADDED,
                packageName, extras, 0, null, null, userIds, instantUserIds,
                broadcastAllowlist, null /* filterExtrasForReceiver */, null);
        // Send to PermissionController for all new users, even if it may not be running for some
        // users
        if (isPrivacySafetyLabelChangeNotificationsEnabled(mContext)) {
            sendPackageBroadcast(Intent.ACTION_PACKAGE_ADDED,
                    packageName, extras, 0,
                    mContext.getPackageManager().getPermissionControllerPackageName(),
                    null, userIds, instantUserIds,
                    broadcastAllowlist, null /* filterExtrasForReceiver */, null);
        }
    }

    public void sendFirstLaunchBroadcast(String pkgName, String installerPkg,
            int[] userIds, int[] instantUserIds) {
        sendPackageBroadcast(Intent.ACTION_PACKAGE_FIRST_LAUNCH, pkgName, null, 0,
                installerPkg, null, userIds, instantUserIds, null /* broadcastAllowList */,
                null /* filterExtrasForReceiver */, null);
    }

    /**
     * Filter package names for the intent extras {@link Intent#EXTRA_CHANGED_PACKAGE_LIST} and
     * {@link Intent#EXTRA_CHANGED_UID_LIST} by using the rules of the package visibility.
     *
     * @param callingUid The uid that is going to access the intent extras.
     * @param extras The intent extras to filter
     * @return An extras that have been filtered, or {@code null} if the given uid is unable to
     * access all the packages in the extras.
     */
    @Nullable
    public static Bundle filterExtrasChangedPackageList(@NonNull Computer snapshot, int callingUid,
            @NonNull Bundle extras) {
        if (UserHandle.isCore(callingUid)) {
            // see all
            return extras;
        }
        final String[] pkgs = extras.getStringArray(Intent.EXTRA_CHANGED_PACKAGE_LIST);
        if (ArrayUtils.isEmpty(pkgs)) {
            return extras;
        }
        final int userId = extras.getInt(
                Intent.EXTRA_USER_HANDLE, UserHandle.getUserId(callingUid));
        final int[] uids = extras.getIntArray(Intent.EXTRA_CHANGED_UID_LIST);
        final Pair<String[], int[]> filteredPkgs =
                filterPackages(snapshot, pkgs, uids, callingUid, userId);
        if (ArrayUtils.isEmpty(filteredPkgs.first)) {
            // caller is unable to access this intent
            return null;
        }
        final Bundle filteredExtras = new Bundle(extras);
        filteredExtras.putStringArray(Intent.EXTRA_CHANGED_PACKAGE_LIST, filteredPkgs.first);
        filteredExtras.putIntArray(Intent.EXTRA_CHANGED_UID_LIST, filteredPkgs.second);
        return filteredExtras;
    }

    /** Returns whether the Safety Label Change notification, a privacy feature, is enabled. */
    public static boolean isPrivacySafetyLabelChangeNotificationsEnabled(Context context) {
        PackageManager packageManager = context.getPackageManager();
        return DeviceConfig.getBoolean(DeviceConfig.NAMESPACE_PRIVACY,
                SAFETY_LABEL_CHANGE_NOTIFICATIONS_ENABLED, true)
            && !packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)
            && !packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
            && !packageManager.hasSystemFeature(PackageManager.FEATURE_WATCH);
    }

    @NonNull
    private static Pair<String[], int[]> filterPackages(@NonNull Computer snapshot,
            @NonNull String[] pkgs, @Nullable int[] uids, int callingUid, int userId) {
        final int pkgSize = pkgs.length;
        final int uidSize = !ArrayUtils.isEmpty(uids) ? uids.length : 0;

        final ArrayList<String> pkgList = new ArrayList<>(pkgSize);
        final IntArray uidList = uidSize > 0 ? new IntArray(uidSize) : null;
        for (int i = 0; i < pkgSize; i++) {
            final String packageName = pkgs[i];
            if (snapshot.shouldFilterApplication(
                    snapshot.getPackageStateInternal(packageName), callingUid, userId)) {
                continue;
            }
            pkgList.add(packageName);
            if (uidList != null && i < uidSize) {
                uidList.add(uids[i]);
            }
        }
        return new Pair<>(
                pkgList.size() > 0 ? pkgList.toArray(new String[pkgList.size()]) : null,
                uidList != null && uidList.size() > 0 ? uidList.toArray() : null);
    }
}
