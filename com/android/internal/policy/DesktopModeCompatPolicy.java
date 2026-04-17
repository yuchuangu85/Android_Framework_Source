/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.internal.policy;

import static android.app.WindowConfiguration.ACTIVITY_TYPE_DREAM;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.os.UserHandle.USER_SYSTEM;
import static android.os.UserManager.isHeadlessSystemUserMode;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.app.AppCompatTaskInfo;
import android.app.TaskInfo;
import android.app.WindowConfiguration;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.provider.Settings;

import com.android.internal.R;
import com.android.window.flags.Flags;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

public class DesktopModeCompatPolicy {
    @NonNull
    private final Context mContext;
    @NonNull
    private final String mSystemUiPackage;
    @NonNull
    private final List<String> mConfigExemptPackages;
    @NonNull
    private final List<String> mConfigTransparentExemptionIgnoreList;
    private final Map<String, Boolean> mPackageInfoCache = new HashMap<>();
    private PackageManager mPackageManager = null;

    public Supplier<String> mDefaultHomePackageSupplier;

    public DesktopModeCompatPolicy(@NonNull Context context) {
        mContext = context;
        mSystemUiPackage = context.getResources().getString(R.string.config_systemUi);
        mConfigExemptPackages = Arrays.asList(context.getResources().getStringArray(
                R.array.config_desktopExemptPackages));
        mConfigTransparentExemptionIgnoreList = Arrays.asList(context.getResources().getStringArray(
                R.array.config_desktopTransparentExemptionIgnoreList));
    }

    public void setDefaultHomePackageSupplier(
            @NonNull Supplier<String> defaultHomePackageSupplier) {
        mDefaultHomePackageSupplier = defaultHomePackageSupplier;
    }

    @NonNull
    private PackageManager getPackageManager() {
        if (mPackageManager != null) {
            return mPackageManager;
        }

        mPackageManager = mContext.getPackageManager();
        return mPackageManager;
    }

    @Nullable
    public String getDefaultHomePackage(int userId) {
        if (mDefaultHomePackageSupplier != null) {
            return mDefaultHomePackageSupplier.get();
        }
        final ResolveInfo homeActivityInfo = getPackageManager().resolveActivityAsUser(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0, userId);
        if (homeActivityInfo == null) return null;
        return homeActivityInfo.activityInfo.packageName;
    }

    /**
     * @see #isTopActivityExemptFromDesktopWindowing(ComponentName, boolean, boolean, int, int,
     * ActivityInfo, int)
     */
    public boolean isTopActivityExemptFromDesktopWindowing(@NonNull TaskInfo task) {
        return isTopActivityExemptFromDesktopWindowing(task.baseActivity,
                task.isTopActivityNoDisplay, task.isActivityStackTransparent, task.numActivities,
                task.userId, task.topActivityInfo, task.topActivityType);
    }

    /**
     * If the top activity should be exempt from desktop windowing and forced back to fullscreen.
     * Currently includes all system ui, default home and transparent stack activities with the
     * relevant permission or signature. However if the top activity is not being displayed,
     * regardless of its configuration, we will not exempt it as to remain in the desktop windowing
     * environment.
     */
    public boolean isTopActivityExemptFromDesktopWindowing(@Nullable ComponentName baseActivity,
            boolean isTopActivityNoDisplay, boolean isActivityStackTransparent, int numActivities,
            int userId, ActivityInfo info, @WindowConfiguration.ActivityType int topActivityType) {
        if (isHeadlessSystemUserMode() && userId == USER_SYSTEM) {
            // An activity for system user in HSUM should not activate desktop windowing.
            return true;
        }
        final String packageName = baseActivity != null ? baseActivity.getPackageName() : null;
        if (packageName == null) {
            return false;
        }
        // If activity is not being displayed, window mode change has no visual affect so leave
        // unchanged.
        if (isTopActivityNoDisplay) {
            return false;
        }
        // Dream activities should be fullscreen and thus should be forced out of desktop.
        if (topActivityType == ACTIVITY_TYPE_DREAM) {
            return true;
        }
        // If activity belongs to package exempt via device config, force out of desktop.
        if (isPackageExemptViaConfig(packageName) && !isActivityStackTransparent) {
            return true;
        }
        // If activity belongs to system ui package, safe to force out of desktop.
        if (isSystemUiTask(packageName)) {
            return true;
        }
        // If activity belongs to default home package, safe to force out of desktop.
        if (isPartOfDefaultHomePackageOrNoHomeAvailable(packageName, userId)) {
            return true;
        }
        // If all activities in task stack are transparent AND package has the relevant
        // fullscreen transparent permission OR is signed with platform key, safe to force out
        // of desktop.
        // TODO: b/458693972 - Replace with permission and manifest check.
        return isTransparentTask(isActivityStackTransparent, numActivities)
                && (hasFullscreenTransparentPermission(packageName, userId, info)
                    || hasPlatformSignature(info)
                    || (Flags.enablePrivilegedAppTransparentWindowingExemptions()
                        && isPrivilegedApp(info)))
                && !mConfigTransparentExemptionIgnoreList.contains(packageName);
    }

    /** @see #shouldDisableDesktopEntryPoints(String, int, boolean, boolean, int, int) */
    public boolean shouldDisableDesktopEntryPoints(@NonNull TaskInfo task) {
        final String packageName = task.baseActivity != null ? task.baseActivity.getPackageName() :
                null;
        return shouldDisableDesktopEntryPoints(
                packageName,
                task.numActivities,
                task.isTopActivityNoDisplay,
                task.isActivityStackTransparent,
                task.topActivityType,
                task.userId
        );
    }

    /**
     * Whether all desktop entry points should be disabled for a given activity. Currently includes
     * all system ui, default home, transparent stack and no display activities.
     */
    public boolean shouldDisableDesktopEntryPoints(
            @Nullable String packageName,
            int numActivities,
            boolean isTopActivityNoDisplay,
            boolean isActivityStackTransparent,
            @WindowConfiguration.ActivityType int topActivityType,
            int userId
    ) {
        if (packageName == null) {
            return true;
        }

        // Activity will not be displayed, no need to show desktop entry point.
        if (isTopActivityNoDisplay) {
            return true;
        }
        // Dream activities should be fullscreen and thus not allowed to enter desktop.
        if (topActivityType == ACTIVITY_TYPE_DREAM) {
            return true;
        }
        // If activity belongs to system ui package, hide desktop entry point.
        if (isSystemUiTask(packageName)) {
            return true;
        }
        // If activity belongs to default home package, safe to force out of desktop.
        if (isPartOfDefaultHomePackageOrNoHomeAvailable(packageName, userId)) {
            return true;
        }
        // If activity belongs to package exempt via device config, hide desktop entry point.
        if (isPackageExemptViaConfig(packageName)) {
            return true;
        }
        // If all activities in task stack are transparent AND package has the relevant fullscreen
        // transparent permission, safe to force out of desktop.
        return isTransparentTask(isActivityStackTransparent, numActivities);
    }


    /** @see DesktopModeCompatUtils#shouldExcludeCaptionFromAppBounds */
    public boolean shouldExcludeCaptionFromAppBounds(@NonNull TaskInfo taskInfo) {
        final AppCompatTaskInfo appCompatInfo = taskInfo.appCompatTaskInfo;
        if (Flags.refactorCaptionSandboxingToCore()) {
            return appCompatInfo != null && appCompatInfo.hasIsExcludeCaptionInsets();
        }
        if (taskInfo.topActivityInfo != null) {
            return DesktopModeCompatUtils.shouldExcludeCaptionFromAppBounds(
                    taskInfo.topActivityInfo,
                    taskInfo.isResizeable,
                    appCompatInfo != null && appCompatInfo.hasOptOutEdgeToEdge(),
                    appCompatInfo != null && appCompatInfo.hasIsExcludeCaptionInsets()
            );
        }
        return false;
    }

    /**
     * Returns true if all activities in a tasks stack are transparent. If there are no activities
     * will return false.
     */
    public boolean isTransparentTask(@NonNull TaskInfo task) {
        return isTransparentTask(task.isActivityStackTransparent, task.numActivities);
    }

    private boolean isTransparentTask(boolean isActivityStackTransparent, int numActivities) {
        return isActivityStackTransparent && numActivities > 0;
    }

    private boolean isSystemUiTask(@Nullable String packageName) {
        return Objects.equals(packageName, mSystemUiPackage);
    }

    private boolean isPackageExemptViaConfig(@NonNull String packageName) {
        return mConfigExemptPackages.contains(packageName);
    }

    // Checks if the app for the given package has the SYSTEM_ALERT_WINDOW permission.
    private boolean hasFullscreenTransparentPermission(
            @NonNull String packageName, int userId, ActivityInfo info) {
        if (info != null && info.applicationInfo != null) {
            int uid = info.applicationInfo.uid;
            Boolean appOpState = Settings.isCallingPackageAllowedToDrawOverlays(
                    mContext, uid, packageName, false);
            if (appOpState) return true;
        }

        final String cacheKey = userId + "@" + packageName;
        if (mPackageInfoCache.containsKey(cacheKey)) {
            return mPackageInfoCache.get(cacheKey);
        }
        boolean hasPermission = false;
        try {
            PackageInfo packageInfo = getPackageManager().getPackageInfoAsUser(
                    packageName,
                    PackageManager.GET_PERMISSIONS,
                    userId
            );
            if (packageInfo != null && packageInfo.requestedPermissions != null) {
                for (int i = 0; i < packageInfo.requestedPermissions.length; i++) {
                    if (Objects.equals(
                            packageInfo.requestedPermissions[i],
                            Manifest.permission.SYSTEM_ALERT_WINDOW)) {
                        if ((packageInfo.requestedPermissionsFlags[i]
                                & PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0) {
                            hasPermission = true;
                        }
                        break;
                    }
                }
            }
        } catch (PackageManager.NameNotFoundException e) {
            // Package not found, so no permission
        }
        mPackageInfoCache.put(cacheKey, hasPermission);
        return hasPermission;
    }

    // Checks if the app is signed with the platform signature.
    private boolean hasPlatformSignature(@Nullable ActivityInfo info) {
        return info != null
                && info.applicationInfo != null
                && info.applicationInfo.isSignedWithPlatformKey();
    }

    // Checks if the app is a privileged application.
    @RequiresPermission(Manifest.permission.INSTALL_PACKAGES)
    private boolean isPrivilegedApp(@Nullable ActivityInfo info) {
        return info != null
                && info.applicationInfo != null
                && info.applicationInfo.isPrivilegedApp();
    }

    /**
     * Returns true if the tasks base activity is part of the default home package, or there is
     * currently no default home package available.
     */
    public boolean isPartOfDefaultHomePackageOrNoHomeAvailable(@NonNull String packageName,
            int userId) {
        final String defaultHomePackage = getDefaultHomePackage(userId);
        return defaultHomePackage == null || packageName.equals(defaultHomePackage);
    }

    /**
    * Returns true if the task is transparent and full screen.
    * Examples are Gemini and circle to search that overlay on top of other apps.
    */
    public boolean isTransparentOverlay(@NonNull TaskInfo task) {
        return isTransparentOverlay(task.isActivityStackTransparent, task.numActivities,
                task.getWindowingMode());
    }

   /**
    * Returns true if the task is transparent and full screen.
    * Examples are Gemini and circle to search that overlay on top of other apps.
    */
    public boolean isTransparentOverlay(boolean isActivityStackTransparent,
            int numActivities, int windowingMode) {
        return isTransparentTask(isActivityStackTransparent, numActivities)
                && windowingMode == WINDOWING_MODE_FULLSCREEN;
    }
}
