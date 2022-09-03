/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.server;

import static android.provider.DeviceConfig.Properties;

import static com.android.server.pm.PackageManagerServiceUtils.logCriticalInfo;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.VersionedPackage;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.FileUtils;
import android.os.PowerManager;
import android.os.RecoverySystem;
import android.os.RemoteCallback;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.DeviceConfig;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.ExceptionUtils;
import android.util.Log;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.FrameworkStatsLog;
import com.android.server.PackageWatchdog.FailureReasons;
import com.android.server.PackageWatchdog.PackageHealthObserver;
import com.android.server.PackageWatchdog.PackageHealthObserverImpact;
import com.android.server.am.SettingsToPropertiesMapper;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Utilities to help rescue the system from crash loops. Callers are expected to
 * report boot events and persistent app crashes, and if they happen frequently
 * enough this class will slowly escalate through several rescue operations
 * before finally rebooting and prompting the user if they want to wipe data as
 * a last resort.
 *
 * @hide
 */
public class RescueParty {
    @VisibleForTesting
    static final String PROP_ENABLE_RESCUE = "persist.sys.enable_rescue";
    static final String PROP_ATTEMPTING_FACTORY_RESET = "sys.attempting_factory_reset";
    static final String PROP_ATTEMPTING_REBOOT = "sys.attempting_reboot";
    static final String PROP_MAX_RESCUE_LEVEL_ATTEMPTED = "sys.max_rescue_level_attempted";
    @VisibleForTesting
    static final int LEVEL_NONE = 0;
    @VisibleForTesting
    static final int LEVEL_RESET_SETTINGS_UNTRUSTED_DEFAULTS = 1;
    @VisibleForTesting
    static final int LEVEL_RESET_SETTINGS_UNTRUSTED_CHANGES = 2;
    @VisibleForTesting
    static final int LEVEL_RESET_SETTINGS_TRUSTED_DEFAULTS = 3;
    @VisibleForTesting
    static final int LEVEL_WARM_REBOOT = 4;
    @VisibleForTesting
    static final int LEVEL_FACTORY_RESET = 5;
    @VisibleForTesting
    static final String PROP_RESCUE_BOOT_COUNT = "sys.rescue_boot_count";
    @VisibleForTesting
    static final String TAG = "RescueParty";
    @VisibleForTesting
    static final long DEFAULT_OBSERVING_DURATION_MS = TimeUnit.DAYS.toMillis(2);
    @VisibleForTesting
    static final int DEVICE_CONFIG_RESET_MODE = Settings.RESET_MODE_TRUSTED_DEFAULTS;
    // The DeviceConfig namespace containing all RescueParty switches.
    @VisibleForTesting
    static final String NAMESPACE_CONFIGURATION = "configuration";
    @VisibleForTesting
    static final String NAMESPACE_TO_PACKAGE_MAPPING_FLAG =
            "namespace_to_package_mapping";

    private static final String NAME = "rescue-party-observer";


    private static final String PROP_DISABLE_RESCUE = "persist.sys.disable_rescue";
    private static final String PROP_VIRTUAL_DEVICE = "ro.hardware.virtual_device";
    private static final String PROP_DEVICE_CONFIG_DISABLE_FLAG =
            "persist.device_config.configuration.disable_rescue_party";
    private static final String PROP_DISABLE_FACTORY_RESET_FLAG =
            "persist.device_config.configuration.disable_rescue_party_factory_reset";

    private static final int PERSISTENT_MASK = ApplicationInfo.FLAG_PERSISTENT
            | ApplicationInfo.FLAG_SYSTEM;

    /** Register the Rescue Party observer as a Package Watchdog health observer */
    public static void registerHealthObserver(Context context) {
        PackageWatchdog.getInstance(context).registerHealthObserver(
                RescuePartyObserver.getInstance(context));
    }

    private static boolean isDisabled() {
        // Check if we're explicitly enabled for testing
        if (SystemProperties.getBoolean(PROP_ENABLE_RESCUE, false)) {
            return false;
        }

        // We're disabled if the DeviceConfig disable flag is set to true.
        // This is in case that an emergency rollback of the feature is needed.
        if (SystemProperties.getBoolean(PROP_DEVICE_CONFIG_DISABLE_FLAG, false)) {
            Slog.v(TAG, "Disabled because of DeviceConfig flag");
            return true;
        }

        // We're disabled on all engineering devices
        if (Build.IS_ENG) {
            Slog.v(TAG, "Disabled because of eng build");
            return true;
        }

        // We're disabled on userdebug devices connected over USB, since that's
        // a decent signal that someone is actively trying to debug the device,
        // or that it's in a lab environment.
        if (Build.IS_USERDEBUG && isUsbActive()) {
            Slog.v(TAG, "Disabled because of active USB connection");
            return true;
        }

        // One last-ditch check
        if (SystemProperties.getBoolean(PROP_DISABLE_RESCUE, false)) {
            Slog.v(TAG, "Disabled because of manual property");
            return true;
        }

        return false;
    }

    /**
     * Check if we're currently attempting to reboot for a factory reset. This method must
     * return true if RescueParty tries to reboot early during a boot loop, since the device
     * will not be fully booted at this time.
     *
     * TODO(gavincorkery): Rename method since its scope has expanded.
     */
    public static boolean isAttemptingFactoryReset() {
        return isFactoryResetPropertySet() || isRebootPropertySet();
    }

    static boolean isFactoryResetPropertySet() {
        return SystemProperties.getBoolean(PROP_ATTEMPTING_FACTORY_RESET, false);
    }

    static boolean isRebootPropertySet() {
        return SystemProperties.getBoolean(PROP_ATTEMPTING_REBOOT, false);
    }

    /**
     * Called when {@code SettingsProvider} has been published, which is a good
     * opportunity to reset any settings depending on our rescue level.
     */
    public static void onSettingsProviderPublished(Context context) {
        handleNativeRescuePartyResets();
        ContentResolver contentResolver = context.getContentResolver();
        Settings.Config.registerMonitorCallback(contentResolver, new RemoteCallback(result -> {
            handleMonitorCallback(context, result);
        }));
    }


    /**
     * Called when {@code RollbackManager} performs Mainline module rollbacks,
     * to avoid rolled back modules consuming flag values only expected to work
     * on modules of newer versions.
     */
    public static void resetDeviceConfigForPackages(List<String> packageNames) {
        if (packageNames == null) {
            return;
        }
        Set<String> namespacesToReset = new ArraySet<String>();
        Iterator<String> it = packageNames.iterator();
        RescuePartyObserver rescuePartyObserver = RescuePartyObserver.getInstanceIfCreated();
        // Get runtime package to namespace mapping if created.
        if (rescuePartyObserver != null) {
            while (it.hasNext()) {
                String packageName = it.next();
                Set<String> runtimeAffectedNamespaces =
                        rescuePartyObserver.getAffectedNamespaceSet(packageName);
                if (runtimeAffectedNamespaces != null) {
                    namespacesToReset.addAll(runtimeAffectedNamespaces);
                }
            }
        }
        // Get preset package to namespace mapping if created.
        Set<String> presetAffectedNamespaces = getPresetNamespacesForPackages(
                packageNames);
        if (presetAffectedNamespaces != null) {
            namespacesToReset.addAll(presetAffectedNamespaces);
        }

        // Clear flags under the namespaces mapped to these packages.
        // Using setProperties since DeviceConfig.resetToDefaults bans the current flag set.
        Iterator<String> namespaceIt = namespacesToReset.iterator();
        while (namespaceIt.hasNext()) {
            String namespaceToReset = namespaceIt.next();
            Properties properties = new Properties.Builder(namespaceToReset).build();
            try {
                DeviceConfig.setProperties(properties);
            } catch (DeviceConfig.BadConfigException exception) {
                logCriticalInfo(Log.WARN, "namespace " + namespaceToReset
                        + " is already banned, skip reset.");
            }
        }
    }

    private static Set<String> getPresetNamespacesForPackages(List<String> packageNames) {
        Set<String> resultSet = new ArraySet<String>();
        try {
            String flagVal = DeviceConfig.getString(NAMESPACE_CONFIGURATION,
                    NAMESPACE_TO_PACKAGE_MAPPING_FLAG, "");
            String[] mappingEntries = flagVal.split(",");
            for (int i = 0; i < mappingEntries.length; i++) {
                if (TextUtils.isEmpty(mappingEntries[i])) {
                    continue;
                }
                String[] splittedEntry = mappingEntries[i].split(":");
                if (splittedEntry.length != 2) {
                    throw new RuntimeException("Invalid mapping entry: " + mappingEntries[i]);
                }
                String namespace = splittedEntry[0];
                String packageName = splittedEntry[1];

                if (packageNames.contains(packageName)) {
                    resultSet.add(namespace);
                }
            }
        } catch (Exception e) {
            resultSet.clear();
            Slog.e(TAG, "Failed to read preset package to namespaces mapping.", e);
        } finally {
            return resultSet;
        }
    }

    @VisibleForTesting
    static long getElapsedRealtime() {
        return SystemClock.elapsedRealtime();
    }

    private static void handleMonitorCallback(Context context, Bundle result) {
        String callbackType = result.getString(Settings.EXTRA_MONITOR_CALLBACK_TYPE, "");
        switch (callbackType) {
            case Settings.EXTRA_NAMESPACE_UPDATED_CALLBACK:
                String updatedNamespace = result.getString(Settings.EXTRA_NAMESPACE);
                if (updatedNamespace != null) {
                    startObservingPackages(context, updatedNamespace);
                }
                break;
            case Settings.EXTRA_ACCESS_CALLBACK:
                String callingPackage = result.getString(Settings.EXTRA_CALLING_PACKAGE, null);
                String namespace = result.getString(Settings.EXTRA_NAMESPACE, null);
                if (namespace != null && callingPackage != null) {
                    RescuePartyObserver.getInstance(context).recordDeviceConfigAccess(
                            callingPackage,
                            namespace);
                }
                break;
            default:
                Slog.w(TAG, "Unrecognized DeviceConfig callback");
                break;
        }
    }

    private static void startObservingPackages(Context context, @NonNull String updatedNamespace) {
        RescuePartyObserver rescuePartyObserver = RescuePartyObserver.getInstance(context);
        Set<String> callingPackages = rescuePartyObserver.getCallingPackagesSet(updatedNamespace);
        if (callingPackages == null) {
            return;
        }
        List<String> callingPackageList = new ArrayList<>();
        callingPackageList.addAll(callingPackages);
        Slog.i(TAG, "Starting to observe: " + callingPackageList + ", updated namespace: "
                + updatedNamespace);
        PackageWatchdog.getInstance(context).startObservingHealth(
                rescuePartyObserver,
                callingPackageList,
                DEFAULT_OBSERVING_DURATION_MS);
    }

    private static void handleNativeRescuePartyResets() {
        if (SettingsToPropertiesMapper.isNativeFlagsResetPerformed()) {
            String[] resetNativeCategories = SettingsToPropertiesMapper.getResetNativeCategories();
            for (int i = 0; i < resetNativeCategories.length; i++) {
                // Don't let RescueParty reset the namespace for RescueParty switches.
                if (NAMESPACE_CONFIGURATION.equals(resetNativeCategories[i])) {
                    continue;
                }
                DeviceConfig.resetToDefaults(DEVICE_CONFIG_RESET_MODE,
                        resetNativeCategories[i]);
            }
        }
    }

    private static int getMaxRescueLevel(boolean mayPerformFactoryReset) {
        if (!mayPerformFactoryReset
                || SystemProperties.getBoolean(PROP_DISABLE_FACTORY_RESET_FLAG, false)) {
            return LEVEL_RESET_SETTINGS_TRUSTED_DEFAULTS;
        }
        return LEVEL_FACTORY_RESET;
    }

    /**
     * Get the rescue level to perform if this is the n-th attempt at mitigating failure.
     *
     * @param mitigationCount: the mitigation attempt number (1 = first attempt etc.)
     * @param mayPerformFactoryReset: whether or not a factory reset may be performed for the given
     *                              failure.
     * @return the rescue level for the n-th mitigation attempt.
     */
    private static int getRescueLevel(int mitigationCount, boolean mayPerformFactoryReset) {
        if (mitigationCount == 1) {
            return LEVEL_RESET_SETTINGS_UNTRUSTED_DEFAULTS;
        } else if (mitigationCount == 2) {
            return LEVEL_RESET_SETTINGS_UNTRUSTED_CHANGES;
        } else if (mitigationCount == 3) {
            return LEVEL_RESET_SETTINGS_TRUSTED_DEFAULTS;
        } else if (mitigationCount == 4) {
            return Math.min(getMaxRescueLevel(mayPerformFactoryReset), LEVEL_WARM_REBOOT);
        } else if (mitigationCount >= 5) {
            return Math.min(getMaxRescueLevel(mayPerformFactoryReset), LEVEL_FACTORY_RESET);
        } else {
            Slog.w(TAG, "Expected positive mitigation count, was " + mitigationCount);
            return LEVEL_NONE;
        }
    }

    private static void executeRescueLevel(Context context, @Nullable String failedPackage,
            int level) {
        Slog.w(TAG, "Attempting rescue level " + levelToString(level));
        try {
            executeRescueLevelInternal(context, level, failedPackage);
            EventLogTags.writeRescueSuccess(level);
            String successMsg = "Finished rescue level " + levelToString(level);
            if (!TextUtils.isEmpty(failedPackage)) {
                successMsg += " for package " + failedPackage;
            }
            logCriticalInfo(Log.DEBUG, successMsg);
        } catch (Throwable t) {
            logRescueException(level, failedPackage, t);
        }
    }

    private static void executeRescueLevelInternal(Context context, int level, @Nullable
            String failedPackage) throws Exception {
        FrameworkStatsLog.write(FrameworkStatsLog.RESCUE_PARTY_RESET_REPORTED, level);
        // Try our best to reset all settings possible, and once finished
        // rethrow any exception that we encountered
        Exception res = null;
        Runnable runnable;
        Thread thread;
        switch (level) {
            case LEVEL_RESET_SETTINGS_UNTRUSTED_DEFAULTS:
                try {
                    resetAllSettingsIfNecessary(context, Settings.RESET_MODE_UNTRUSTED_DEFAULTS,
                            level);
                } catch (Exception e) {
                    res = e;
                }
                try {
                    resetDeviceConfig(context, /*isScoped=*/true, failedPackage);
                } catch (Exception e) {
                    res = e;
                }
                break;
            case LEVEL_RESET_SETTINGS_UNTRUSTED_CHANGES:
                try {
                    resetAllSettingsIfNecessary(context, Settings.RESET_MODE_UNTRUSTED_CHANGES,
                            level);
                } catch (Exception e) {
                    res = e;
                }
                try {
                    resetDeviceConfig(context, /*isScoped=*/true, failedPackage);
                } catch (Exception e) {
                    res = e;
                }
                break;
            case LEVEL_RESET_SETTINGS_TRUSTED_DEFAULTS:
                try {
                    resetAllSettingsIfNecessary(context, Settings.RESET_MODE_TRUSTED_DEFAULTS,
                            level);
                } catch (Exception e) {
                    res = e;
                }
                try {
                    resetDeviceConfig(context, /*isScoped=*/false, failedPackage);
                } catch (Exception e) {
                    res = e;
                }
                break;
            case LEVEL_WARM_REBOOT:
                // Request the reboot from a separate thread to avoid deadlock on PackageWatchdog
                // when device shutting down.
                SystemProperties.set(PROP_ATTEMPTING_REBOOT, "true");
                runnable = () -> {
                    try {
                        PowerManager pm = context.getSystemService(PowerManager.class);
                        if (pm != null) {
                            pm.reboot(TAG);
                        }
                    } catch (Throwable t) {
                        logRescueException(level, failedPackage, t);
                    }
                };
                thread = new Thread(runnable);
                thread.start();
                break;
            case LEVEL_FACTORY_RESET:
                SystemProperties.set(PROP_ATTEMPTING_FACTORY_RESET, "true");
                runnable = new Runnable() {
                    @Override
                    public void run() {
                        try {
                            RecoverySystem.rebootPromptAndWipeUserData(context, TAG);
                        } catch (Throwable t) {
                            logRescueException(level, failedPackage, t);
                        }
                    }
                };
                thread = new Thread(runnable);
                thread.start();
                break;
        }

        if (res != null) {
            throw res;
        }
    }

    private static void logRescueException(int level, @Nullable String failedPackageName,
            Throwable t) {
        final String msg = ExceptionUtils.getCompleteMessage(t);
        EventLogTags.writeRescueFailure(level, msg);
        String failureMsg = "Failed rescue level " + levelToString(level);
        if (!TextUtils.isEmpty(failedPackageName)) {
            failureMsg += " for package " + failedPackageName;
        }
        logCriticalInfo(Log.ERROR, failureMsg + ": " + msg);
    }

    private static int mapRescueLevelToUserImpact(int rescueLevel) {
        switch(rescueLevel) {
            case LEVEL_RESET_SETTINGS_UNTRUSTED_DEFAULTS:
            case LEVEL_RESET_SETTINGS_UNTRUSTED_CHANGES:
                return PackageHealthObserverImpact.USER_IMPACT_LOW;
            case LEVEL_RESET_SETTINGS_TRUSTED_DEFAULTS:
            case LEVEL_WARM_REBOOT:
            case LEVEL_FACTORY_RESET:
                return PackageHealthObserverImpact.USER_IMPACT_HIGH;
            default:
                return PackageHealthObserverImpact.USER_IMPACT_NONE;
        }
    }

    private static void resetAllSettingsIfNecessary(Context context, int mode,
            int level) throws Exception {
        // No need to reset Settings again if they are already reset in the current level once.
        if (SystemProperties.getInt(PROP_MAX_RESCUE_LEVEL_ATTEMPTED, LEVEL_NONE) >= level) {
            return;
        }
        SystemProperties.set(PROP_MAX_RESCUE_LEVEL_ATTEMPTED, Integer.toString(level));
        // Try our best to reset all settings possible, and once finished
        // rethrow any exception that we encountered
        Exception res = null;
        final ContentResolver resolver = context.getContentResolver();
        try {
            Settings.Global.resetToDefaultsAsUser(resolver, null, mode, UserHandle.USER_SYSTEM);
        } catch (Exception e) {
            res = new RuntimeException("Failed to reset global settings", e);
        }
        for (int userId : getAllUserIds()) {
            try {
                Settings.Secure.resetToDefaultsAsUser(resolver, null, mode, userId);
            } catch (Exception e) {
                res = new RuntimeException("Failed to reset secure settings for " + userId, e);
            }
        }
        if (res != null) {
            throw res;
        }
    }

    private static void resetDeviceConfig(Context context, boolean isScoped,
            @Nullable String failedPackage) throws Exception {
        final ContentResolver resolver = context.getContentResolver();
        try {
            if (!isScoped || failedPackage == null) {
                resetAllAffectedNamespaces(context);
            } else {
                performScopedReset(context, failedPackage);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to reset config settings", e);
        }
    }

    private static void resetAllAffectedNamespaces(Context context) {
        RescuePartyObserver rescuePartyObserver = RescuePartyObserver.getInstance(context);
        Set<String> allAffectedNamespaces = rescuePartyObserver.getAllAffectedNamespaceSet();

        Slog.w(TAG,
                "Performing reset for all affected namespaces: "
                        + Arrays.toString(allAffectedNamespaces.toArray()));
        Iterator<String> it = allAffectedNamespaces.iterator();
        while (it.hasNext()) {
            String namespace = it.next();
            // Don't let RescueParty reset the namespace for RescueParty switches.
            if (NAMESPACE_CONFIGURATION.equals(namespace)) {
                continue;
            }
            DeviceConfig.resetToDefaults(DEVICE_CONFIG_RESET_MODE, namespace);
        }
    }

    private static void performScopedReset(Context context, @NonNull String failedPackage) {
        RescuePartyObserver rescuePartyObserver = RescuePartyObserver.getInstance(context);
        Set<String> affectedNamespaces = rescuePartyObserver.getAffectedNamespaceSet(
                failedPackage);
        // If we can't find namespaces affected for current package,
        // skip this round of reset.
        if (affectedNamespaces != null) {
            Slog.w(TAG,
                    "Performing scoped reset for package: " + failedPackage
                            + ", affected namespaces: "
                            + Arrays.toString(affectedNamespaces.toArray()));
            Iterator<String> it = affectedNamespaces.iterator();
            while (it.hasNext()) {
                String namespace = it.next();
                // Don't let RescueParty reset the namespace for RescueParty switches.
                if (NAMESPACE_CONFIGURATION.equals(namespace)) {
                    continue;
                }
                DeviceConfig.resetToDefaults(DEVICE_CONFIG_RESET_MODE, namespace);
            }
        }
    }

    /**
     * Handle mitigation action for package failures. This observer will be register to Package
     * Watchdog and will receive calls about package failures. This observer is persistent so it
     * may choose to mitigate failures for packages it has not explicitly asked to observe.
     */
    public static class RescuePartyObserver implements PackageHealthObserver {

        private final Context mContext;
        private final Map<String, Set<String>> mCallingPackageNamespaceSetMap = new HashMap<>();
        private final Map<String, Set<String>> mNamespaceCallingPackageSetMap = new HashMap<>();

        @GuardedBy("RescuePartyObserver.class")
        static RescuePartyObserver sRescuePartyObserver;

        private RescuePartyObserver(Context context) {
            mContext = context;
        }

        /** Creates or gets singleton instance of RescueParty. */
        public static RescuePartyObserver getInstance(Context context) {
            synchronized (RescuePartyObserver.class) {
                if (sRescuePartyObserver == null) {
                    sRescuePartyObserver = new RescuePartyObserver(context);
                }
                return sRescuePartyObserver;
            }
        }

        /** Gets singleton instance. It returns null if the instance is not created yet.*/
        @Nullable
        public static RescuePartyObserver getInstanceIfCreated() {
            synchronized (RescuePartyObserver.class) {
                return sRescuePartyObserver;
            }
        }

        @VisibleForTesting
        static void reset() {
            synchronized (RescuePartyObserver.class) {
                sRescuePartyObserver = null;
            }
        }

        @Override
        public int onHealthCheckFailed(@Nullable VersionedPackage failedPackage,
                @FailureReasons int failureReason, int mitigationCount) {
            if (!isDisabled() && (failureReason == PackageWatchdog.FAILURE_REASON_APP_CRASH
                    || failureReason == PackageWatchdog.FAILURE_REASON_APP_NOT_RESPONDING)) {
                return mapRescueLevelToUserImpact(getRescueLevel(mitigationCount,
                        mayPerformFactoryReset(failedPackage)));
            } else {
                return PackageHealthObserverImpact.USER_IMPACT_NONE;
            }
        }

        @Override
        public boolean execute(@Nullable VersionedPackage failedPackage,
                @FailureReasons int failureReason, int mitigationCount) {
            if (isDisabled()) {
                return false;
            }
            if (failureReason == PackageWatchdog.FAILURE_REASON_APP_CRASH
                    || failureReason == PackageWatchdog.FAILURE_REASON_APP_NOT_RESPONDING) {
                final int level = getRescueLevel(mitigationCount,
                        mayPerformFactoryReset(failedPackage));
                executeRescueLevel(mContext,
                        failedPackage == null ? null : failedPackage.getPackageName(), level);
                return true;
            } else {
                return false;
            }
        }

        @Override
        public boolean isPersistent() {
            return true;
        }

        @Override
        public boolean mayObservePackage(String packageName) {
            PackageManager pm = mContext.getPackageManager();
            try {
                // A package is a module if this is non-null
                if (pm.getModuleInfo(packageName, 0) != null) {
                    return true;
                }
            } catch (PackageManager.NameNotFoundException ignore) {
            }

            return isPersistentSystemApp(packageName);
        }

        @Override
        public int onBootLoop(int mitigationCount) {
            if (isDisabled()) {
                return PackageHealthObserverImpact.USER_IMPACT_NONE;
            }
            return mapRescueLevelToUserImpact(getRescueLevel(mitigationCount, true));
        }

        @Override
        public boolean executeBootLoopMitigation(int mitigationCount) {
            if (isDisabled()) {
                return false;
            }
            executeRescueLevel(mContext, /*failedPackage=*/ null,
                    getRescueLevel(mitigationCount, true));
            return true;
        }

        @Override
        public String getName() {
            return NAME;
        }

        /**
         * Returns {@code true} if the failing package is non-null and performing a reboot or
         * prompting a factory reset is an acceptable mitigation strategy for the package's
         * failure, {@code false} otherwise.
         */
        private boolean mayPerformFactoryReset(@Nullable VersionedPackage failingPackage) {
            if (failingPackage == null) {
                return false;
            }

            return isPersistentSystemApp(failingPackage.getPackageName());
        }

        private boolean isPersistentSystemApp(@NonNull String packageName) {
            PackageManager pm = mContext.getPackageManager();
            try {
                ApplicationInfo info = pm.getApplicationInfo(packageName, 0);
                return (info.flags & PERSISTENT_MASK) == PERSISTENT_MASK;
            } catch (PackageManager.NameNotFoundException e) {
                return false;
            }
        }

        private synchronized void recordDeviceConfigAccess(@NonNull String callingPackage,
                @NonNull String namespace) {
            // Record it in calling packages to namespace map
            Set<String> namespaceSet = mCallingPackageNamespaceSetMap.get(callingPackage);
            if (namespaceSet == null) {
                namespaceSet = new ArraySet<>();
                mCallingPackageNamespaceSetMap.put(callingPackage, namespaceSet);
            }
            namespaceSet.add(namespace);
            // Record it in namespace to calling packages map
            Set<String> callingPackageSet = mNamespaceCallingPackageSetMap.get(namespace);
            if (callingPackageSet == null) {
                callingPackageSet = new ArraySet<>();
            }
            callingPackageSet.add(callingPackage);
            mNamespaceCallingPackageSetMap.put(namespace, callingPackageSet);
        }

        private synchronized Set<String> getAffectedNamespaceSet(String failedPackage) {
            return mCallingPackageNamespaceSetMap.get(failedPackage);
        }

        private synchronized Set<String> getAllAffectedNamespaceSet() {
            return new HashSet<String>(mNamespaceCallingPackageSetMap.keySet());
        }

        private synchronized Set<String> getCallingPackagesSet(String namespace) {
            return mNamespaceCallingPackageSetMap.get(namespace);
        }
    }

    private static int[] getAllUserIds() {
        int[] userIds = { UserHandle.USER_SYSTEM };
        try {
            for (File file : FileUtils.listFilesOrEmpty(Environment.getDataSystemDeDirectory())) {
                try {
                    final int userId = Integer.parseInt(file.getName());
                    if (userId != UserHandle.USER_SYSTEM) {
                        userIds = ArrayUtils.appendInt(userIds, userId);
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        } catch (Throwable t) {
            Slog.w(TAG, "Trouble discovering users", t);
        }
        return userIds;
    }

    /**
     * Hacky test to check if the device has an active USB connection, which is
     * a good proxy for someone doing local development work.
     */
    private static boolean isUsbActive() {
        if (SystemProperties.getBoolean(PROP_VIRTUAL_DEVICE, false)) {
            Slog.v(TAG, "Assuming virtual device is connected over USB");
            return true;
        }
        try {
            final String state = FileUtils
                    .readTextFile(new File("/sys/class/android_usb/android0/state"), 128, "");
            return "CONFIGURED".equals(state.trim());
        } catch (Throwable t) {
            Slog.w(TAG, "Failed to determine if device was on USB", t);
            return false;
        }
    }

    private static String levelToString(int level) {
        switch (level) {
            case LEVEL_NONE: return "NONE";
            case LEVEL_RESET_SETTINGS_UNTRUSTED_DEFAULTS: return "RESET_SETTINGS_UNTRUSTED_DEFAULTS";
            case LEVEL_RESET_SETTINGS_UNTRUSTED_CHANGES: return "RESET_SETTINGS_UNTRUSTED_CHANGES";
            case LEVEL_RESET_SETTINGS_TRUSTED_DEFAULTS: return "RESET_SETTINGS_TRUSTED_DEFAULTS";
            case LEVEL_WARM_REBOOT: return "WARM_REBOOT";
            case LEVEL_FACTORY_RESET: return "FACTORY_RESET";
            default: return Integer.toString(level);
        }
    }
}
