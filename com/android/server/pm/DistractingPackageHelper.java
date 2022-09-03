/*
 * Copyright (C) 2022 The Android Open Source Project
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

import static android.content.pm.PackageManager.RESTRICTION_NONE;

import android.annotation.NonNull;
import android.content.Intent;
import android.content.pm.PackageManager.DistractionRestriction;
import android.os.Bundle;
import android.os.Handler;
import android.os.UserHandle;
import android.util.ArraySet;
import android.util.IntArray;
import android.util.Slog;

import com.android.internal.util.ArrayUtils;
import com.android.server.pm.pkg.PackageStateInternal;

import java.util.ArrayList;
import java.util.List;

/**
 * Mark, unmark, or remove any {@link DistractionRestriction restrictions} set on given packages.
 */
public final class DistractingPackageHelper {

    // TODO(b/198166813): remove PMS dependency
    private final PackageManagerService mPm;
    private final PackageManagerServiceInjector mInjector;
    private final BroadcastHelper mBroadcastHelper;
    private final SuspendPackageHelper mSuspendPackageHelper;

    /**
     * Constructor for {@link PackageManagerService}.
     */
    DistractingPackageHelper(PackageManagerService pm, PackageManagerServiceInjector injector,
            BroadcastHelper broadcastHelper, SuspendPackageHelper suspendPackageHelper) {
        mPm = pm;
        mInjector = injector;
        mBroadcastHelper = broadcastHelper;
        mSuspendPackageHelper = suspendPackageHelper;
    }

    /**
     * Mark or unmark the given packages as distracting to the given user.
     *
     * @param packageNames Packages to mark as distracting.
     * @param restrictionFlags Any combination of restrictions to impose on the given packages.
     *                         {@link DistractionRestriction#RESTRICTION_NONE} can be used to
     *                         clear any existing restrictions.
     * @param userId the user for which changes are taking place.
     * @param callingUid The caller's uid.
     *
     * @return A list of packages that could not have the {@code restrictionFlags} set. The system
     * may prevent restricting critical packages to preserve normal device function.
     */
    String[] setDistractingPackageRestrictionsAsUser(@NonNull Computer snapshot,
            String[] packageNames, int restrictionFlags, int userId, int callingUid) {
        if (ArrayUtils.isEmpty(packageNames)) {
            return packageNames;
        }
        if (restrictionFlags != RESTRICTION_NONE
                && !mSuspendPackageHelper.isSuspendAllowedForUser(snapshot, userId, callingUid)) {
            Slog.w(PackageManagerService.TAG,
                    "Cannot restrict packages due to restrictions on user " + userId);
            return packageNames;
        }

        final List<String> changedPackagesList = new ArrayList<>(packageNames.length);
        final IntArray changedUids = new IntArray(packageNames.length);
        final List<String> unactionedPackages = new ArrayList<>(packageNames.length);

        final ArraySet<String> changesToCommit = new ArraySet<>();
        final boolean[] canRestrict = (restrictionFlags != RESTRICTION_NONE)
                ? mSuspendPackageHelper.canSuspendPackageForUser(snapshot, packageNames, userId,
                callingUid) : null;
        for (int i = 0; i < packageNames.length; i++) {
            final String packageName = packageNames[i];
            final PackageStateInternal packageState =
                    snapshot.getPackageStateInternal(packageName);
            if (packageState == null
                    || snapshot.shouldFilterApplication(packageState, callingUid, userId)) {
                Slog.w(PackageManagerService.TAG,
                        "Could not find package setting for package: " + packageName
                                + ". Skipping...");
                unactionedPackages.add(packageName);
                continue;
            }
            if (canRestrict != null && !canRestrict[i]) {
                unactionedPackages.add(packageName);
                continue;
            }
            final int oldDistractionFlags = packageState.getUserStateOrDefault(userId)
                    .getDistractionFlags();
            if (restrictionFlags != oldDistractionFlags) {
                changedPackagesList.add(packageName);
                changedUids.add(UserHandle.getUid(userId, packageState.getAppId()));
                changesToCommit.add(packageName);
            }
        }

        mPm.commitPackageStateMutation(null /* initialState */, mutator -> {
            final int size = changesToCommit.size();
            for (int index = 0; index < size; index++) {
                mutator.forPackage(changesToCommit.valueAt(index))
                        .userState(userId)
                        .setDistractionFlags(restrictionFlags);
            }
        });

        if (!changedPackagesList.isEmpty()) {
            final String[] changedPackages = changedPackagesList.toArray(
                    new String[changedPackagesList.size()]);
            sendDistractingPackagesChanged(changedPackages, changedUids.toArray(), userId,
                    restrictionFlags);
            mPm.scheduleWritePackageRestrictions(userId);
        }
        return unactionedPackages.toArray(new String[0]);
    }

    /**
     * Removes any {@link DistractionRestriction restrictions} set on given packages.
     *
     * <p> Caller must flush package restrictions if it cares about immediate data consistency.
     *
     * @param packagesToChange The packages on which restrictions are to be removed.
     * @param userId the user for which changes are taking place.
     */
    void removeDistractingPackageRestrictions(@NonNull Computer snapshot,
            String[] packagesToChange, int userId) {
        if (ArrayUtils.isEmpty(packagesToChange)) {
            return;
        }
        final List<String> changedPackages = new ArrayList<>(packagesToChange.length);
        final IntArray changedUids = new IntArray(packagesToChange.length);
        for (int i = 0; i < packagesToChange.length; i++) {
            final String packageName = packagesToChange[i];
            final PackageStateInternal ps = snapshot.getPackageStateInternal(packageName);
            if (ps != null && ps.getUserStateOrDefault(userId).getDistractionFlags()
                    != RESTRICTION_NONE) {
                changedPackages.add(ps.getPackageName());
                changedUids.add(UserHandle.getUid(userId, ps.getAppId()));
            }
        }
        mPm.commitPackageStateMutation(null /* initialState */, mutator -> {
            for (int index = 0; index < changedPackages.size(); index++) {
                mutator.forPackage(changedPackages.get(index))
                        .userState(userId)
                        .setDistractionFlags(RESTRICTION_NONE);
            }
        });

        if (!changedPackages.isEmpty()) {
            final String[] packageArray = changedPackages.toArray(
                    new String[changedPackages.size()]);
            sendDistractingPackagesChanged(packageArray, changedUids.toArray(), userId,
                    RESTRICTION_NONE);
            mPm.scheduleWritePackageRestrictions(userId);
        }
    }

    /**
     * Send broadcast intents for packages distracting changes.
     *
     * @param pkgList The names of packages which have suspension changes.
     * @param uidList The uids of packages which have suspension changes.
     * @param userId The user where packages reside.
     */
    void sendDistractingPackagesChanged(@NonNull String[] pkgList,
            int[] uidList, int userId, int distractionFlags) {
        final Bundle extras = new Bundle(3);
        extras.putStringArray(Intent.EXTRA_CHANGED_PACKAGE_LIST, pkgList);
        extras.putIntArray(Intent.EXTRA_CHANGED_UID_LIST, uidList);
        extras.putInt(Intent.EXTRA_DISTRACTION_RESTRICTIONS, distractionFlags);

        final Handler handler = mInjector.getHandler();
        handler.post(() -> mBroadcastHelper.sendPackageBroadcast(
                Intent.ACTION_DISTRACTING_PACKAGES_CHANGED, null /* pkg */, extras,
                Intent.FLAG_RECEIVER_REGISTERED_ONLY, null /* targetPkg */,
                null /* finishedReceiver */, new int[]{userId}, null /* instantUserIds */,
                null /* allowList */, null /* bOptions */));
    }
}
