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

package com.android.server.pm;

import static com.android.server.pm.InstructionSets.getAppDexInstructionSets;
import static com.android.server.pm.InstructionSets.getDexCodeInstructionSets;
import static com.android.server.pm.PackageManagerService.PLATFORM_PACKAGE_NAME;

import android.annotation.Nullable;
import android.content.Context;
import android.content.pm.IOtaDexopt;
import android.os.Environment;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ServiceManager;
import android.os.ShellCallback;
import android.os.storage.StorageManager;
import android.util.Log;
import android.util.Slog;

import com.android.internal.logging.MetricsLogger;
import com.android.server.pm.Installer.InstallerException;
import com.android.server.pm.dex.DexoptOptions;
import com.android.server.pm.parsing.pkg.AndroidPackage;
import com.android.server.pm.parsing.pkg.AndroidPackageUtils;

import java.io.File;
import java.io.FileDescriptor;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * A service for A/B OTA dexopting.
 *
 * {@hide}
 */
public class OtaDexoptService extends IOtaDexopt.Stub {
    private final static String TAG = "OTADexopt";
    private final static boolean DEBUG_DEXOPT = true;

    // The amount of "available" (free - low threshold) space necessary at the start of an OTA to
    // not bulk-delete unused apps' odex files.
    private final static long BULK_DELETE_THRESHOLD = 1024 * 1024 * 1024;  // 1GB.

    private final Context mContext;
    private final PackageManagerService mPackageManagerService;
    private final MetricsLogger metricsLogger;

    // TODO: Evaluate the need for WeakReferences here.

    /**
     * The list of dexopt invocations for all work.
     */
    private List<String> mDexoptCommands;

    private int completeSize;

    // MetricsLogger properties.

    // Space before and after.
    private long availableSpaceBefore;
    private long availableSpaceAfterBulkDelete;
    private long availableSpaceAfterDexopt;

    // Packages.
    private int importantPackageCount;
    private int otherPackageCount;

    // Number of dexopt commands. This may be different from the count of packages.
    private int dexoptCommandCountTotal;
    private int dexoptCommandCountExecuted;

    // For spent time.
    private long otaDexoptTimeStart;


    public OtaDexoptService(Context context, PackageManagerService packageManagerService) {
        this.mContext = context;
        this.mPackageManagerService = packageManagerService;
        metricsLogger = new MetricsLogger();
    }

    public static OtaDexoptService main(Context context,
            PackageManagerService packageManagerService) {
        OtaDexoptService ota = new OtaDexoptService(context, packageManagerService);
        ServiceManager.addService("otadexopt", ota);

        // Now it's time to check whether we need to move any A/B artifacts.
        ota.moveAbArtifacts(packageManagerService.mInstaller);

        return ota;
    }

    @Override
    public void onShellCommand(FileDescriptor in, FileDescriptor out, FileDescriptor err,
            String[] args, ShellCallback callback, ResultReceiver resultReceiver) {
        (new OtaDexoptShellCommand(this)).exec(
                this, in, out, err, args, callback, resultReceiver);
    }

    @Override
    public synchronized void prepare() throws RemoteException {
        if (mDexoptCommands != null) {
            throw new IllegalStateException("already called prepare()");
        }
        final List<PackageSetting> important;
        final List<PackageSetting> others;
        Predicate<PackageSetting> isPlatformPackage = pkgSetting ->
                PLATFORM_PACKAGE_NAME.equals(pkgSetting.pkg.getPackageName());
        synchronized (mPackageManagerService.mLock) {
            // Important: the packages we need to run with ab-ota compiler-reason.
            important = PackageManagerServiceUtils.getPackagesForDexopt(
                    mPackageManagerService.mSettings.getPackagesLocked().values(),
                    mPackageManagerService, DEBUG_DEXOPT);
            // Remove Platform Package from A/B OTA b/160735835.
            important.removeIf(isPlatformPackage);
            // Others: we should optimize this with the (first-)boot compiler-reason.
            others = new ArrayList<>(mPackageManagerService.mSettings.getPackagesLocked().values());
            others.removeAll(important);
            others.removeIf(PackageManagerServiceUtils.REMOVE_IF_NULL_PKG);
            others.removeIf(isPlatformPackage);

            // Pre-size the array list by over-allocating by a factor of 1.5.
            mDexoptCommands = new ArrayList<>(3 * mPackageManagerService.mPackages.size() / 2);
        }

        for (PackageSetting pkgSetting : important) {
            mDexoptCommands.addAll(generatePackageDexopts(pkgSetting.pkg, pkgSetting,
                    PackageManagerService.REASON_AB_OTA));
        }
        for (PackageSetting pkgSetting : others) {
            // We assume here that there are no core apps left.
            if (pkgSetting.pkg.isCoreApp()) {
                throw new IllegalStateException("Found a core app that's not important");
            }
            mDexoptCommands.addAll(generatePackageDexopts(pkgSetting.pkg, pkgSetting,
                    PackageManagerService.REASON_FIRST_BOOT));
        }
        completeSize = mDexoptCommands.size();

        long spaceAvailable = getAvailableSpace();
        if (spaceAvailable < BULK_DELETE_THRESHOLD) {
            Log.i(TAG, "Low on space, deleting oat files in an attempt to free up space: "
                    + PackageManagerServiceUtils.packagesToString(others));
            for (PackageSetting pkg : others) {
                mPackageManagerService.deleteOatArtifactsOfPackage(pkg.name);
            }
        }
        long spaceAvailableNow = getAvailableSpace();

        prepareMetricsLogging(important.size(), others.size(), spaceAvailable, spaceAvailableNow);

        if (DEBUG_DEXOPT) {
            try {
                // Output some data about the packages.
                PackageSetting lastUsed = Collections.max(important,
                        (pkgSetting1, pkgSetting2) -> Long.compare(
                                pkgSetting1.getPkgState()
                                        .getLatestForegroundPackageUseTimeInMills(),
                                pkgSetting2.getPkgState()
                                        .getLatestForegroundPackageUseTimeInMills()));
                Log.d(TAG, "A/B OTA: lastUsed time = "
                        + lastUsed.getPkgState().getLatestForegroundPackageUseTimeInMills());
                Log.d(TAG, "A/B OTA: deprioritized packages:");
                for (PackageSetting pkgSetting : others) {
                    Log.d(TAG, "  " + pkgSetting.name + " - "
                            + pkgSetting.getPkgState().getLatestForegroundPackageUseTimeInMills());
                }
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public synchronized void cleanup() throws RemoteException {
        if (DEBUG_DEXOPT) {
            Log.i(TAG, "Cleaning up OTA Dexopt state.");
        }
        mDexoptCommands = null;
        availableSpaceAfterDexopt = getAvailableSpace();

        performMetricsLogging();
    }

    @Override
    public synchronized boolean isDone() throws RemoteException {
        if (mDexoptCommands == null) {
            throw new IllegalStateException("done() called before prepare()");
        }

        return mDexoptCommands.isEmpty();
    }

    @Override
    public synchronized float getProgress() throws RemoteException {
        // Approximate the progress by the amount of already completed commands.
        if (completeSize == 0) {
            return 1f;
        }
        int commandsLeft = mDexoptCommands.size();
        return (completeSize - commandsLeft) / ((float)completeSize);
    }

    @Override
    public synchronized String nextDexoptCommand() throws RemoteException {
        if (mDexoptCommands == null) {
            throw new IllegalStateException("dexoptNextPackage() called before prepare()");
        }

        if (mDexoptCommands.isEmpty()) {
            return "(all done)";
        }

        String next = mDexoptCommands.remove(0);

        if (getAvailableSpace() > 0) {
            dexoptCommandCountExecuted++;

            Log.d(TAG, "Next command: " + next);
            return next;
        } else {
            if (DEBUG_DEXOPT) {
                Log.w(TAG, "Not enough space for OTA dexopt, stopping with "
                        + (mDexoptCommands.size() + 1) + " commands left.");
            }
            mDexoptCommands.clear();
            return "(no free space)";
        }
    }

    private long getMainLowSpaceThreshold() {
        File dataDir = Environment.getDataDirectory();
        @SuppressWarnings("deprecation")
        long lowThreshold = StorageManager.from(mContext).getStorageLowBytes(dataDir);
        if (lowThreshold == 0) {
            throw new IllegalStateException("Invalid low memory threshold");
        }
        return lowThreshold;
    }

    /**
     * Returns the difference of free space to the low-storage-space threshold. Positive values
     * indicate free bytes.
     */
    private long getAvailableSpace() {
        // TODO: If apps are not installed in the internal /data partition, we should compare
        //       against that storage's free capacity.
        long lowThreshold = getMainLowSpaceThreshold();

        File dataDir = Environment.getDataDirectory();
        long usableSpace = dataDir.getUsableSpace();

        return usableSpace - lowThreshold;
    }

    /**
     * Generate all dexopt commands for the given package.
     */
    private synchronized List<String> generatePackageDexopts(AndroidPackage pkg,
            PackageSetting pkgSetting, int compilationReason) {
        // Intercept and collect dexopt requests
        final List<String> commands = new ArrayList<String>();
        final Installer collectingInstaller = new Installer(mContext, true) {
            /**
             * Encode the dexopt command into a string.
             *
             * Note: If you have to change the signature of this function, increase the version
             *       number, and update the counterpart in
             *       frameworks/native/cmds/installd/otapreopt.cpp.
             */
            @Override
            public void dexopt(String apkPath, int uid, @Nullable String pkgName,
                    String instructionSet, int dexoptNeeded, @Nullable String outputPath,
                    int dexFlags, String compilerFilter, @Nullable String volumeUuid,
                    @Nullable String sharedLibraries, @Nullable String seInfo, boolean downgrade,
                    int targetSdkVersion, @Nullable String profileName,
                    @Nullable String dexMetadataPath, @Nullable String dexoptCompilationReason)
                    throws InstallerException {
                final StringBuilder builder = new StringBuilder();

                // The current version. For v10, see b/115993344.
                builder.append("10 ");

                builder.append("dexopt");

                encodeParameter(builder, apkPath);
                encodeParameter(builder, uid);
                encodeParameter(builder, pkgName);
                encodeParameter(builder, instructionSet);
                encodeParameter(builder, dexoptNeeded);
                encodeParameter(builder, outputPath);
                encodeParameter(builder, dexFlags);
                encodeParameter(builder, compilerFilter);
                encodeParameter(builder, volumeUuid);
                encodeParameter(builder, sharedLibraries);
                encodeParameter(builder, seInfo);
                encodeParameter(builder, downgrade);
                encodeParameter(builder, targetSdkVersion);
                encodeParameter(builder, profileName);
                encodeParameter(builder, dexMetadataPath);
                encodeParameter(builder, dexoptCompilationReason);

                commands.add(builder.toString());
            }

            /**
             * Encode a parameter as necessary for the commands string.
             */
            private void encodeParameter(StringBuilder builder, Object arg) {
                builder.append(' ');

                if (arg == null) {
                    builder.append('!');
                    return;
                }

                String txt = String.valueOf(arg);
                if (txt.indexOf('\0') != -1 || txt.indexOf(' ') != -1 || "!".equals(txt)) {
                    throw new IllegalArgumentException(
                            "Invalid argument while executing " + arg);
                }
                builder.append(txt);
            }
        };

        // Use the package manager install and install lock here for the OTA dex optimizer.
        PackageDexOptimizer optimizer = new OTADexoptPackageDexOptimizer(
                collectingInstaller, mPackageManagerService.mInstallLock, mContext);

        optimizer.performDexOpt(pkg, pkgSetting,
                null /* ISAs */,
                null /* CompilerStats.PackageStats */,
                mPackageManagerService.getDexManager().getPackageUseInfoOrDefault(
                        pkg.getPackageName()),
                new DexoptOptions(pkg.getPackageName(), compilationReason,
                        DexoptOptions.DEXOPT_BOOT_COMPLETE));

        return commands;
    }

    @Override
    public synchronized void dexoptNextPackage() throws RemoteException {
        throw new UnsupportedOperationException();
    }

    private void moveAbArtifacts(Installer installer) {
        if (mDexoptCommands != null) {
            throw new IllegalStateException("Should not be ota-dexopting when trying to move.");
        }

        if (!mPackageManagerService.isDeviceUpgrading()) {
            Slog.d(TAG, "No upgrade, skipping A/B artifacts check.");
            return;
        }

        // Look into all packages.
        Collection<AndroidPackage> pkgs = mPackageManagerService.getPackages();
        int packagePaths = 0;
        int pathsSuccessful = 0;
        for (AndroidPackage pkg : pkgs) {
            if (pkg == null) {
                continue;
            }

            // Does the package have code? If not, there won't be any artifacts.
            if (!PackageDexOptimizer.canOptimizePackage(pkg)) {
                continue;
            }
            if (pkg.getPath() == null) {
                Slog.w(TAG, "Package " + pkg + " can be optimized but has null codePath");
                continue;
            }

            // If the path is in /system, /vendor, /product or /system_ext, ignore. It will
            // have been ota-dexopted into /data/ota and moved into the dalvik-cache already.
            if (pkg.getPath().startsWith("/system")
                    || pkg.getPath().startsWith("/vendor")
                    || pkg.getPath().startsWith("/product")
                    || pkg.getPath().startsWith("/system_ext")) {
                continue;
            }

            PackageSetting pkgSetting = mPackageManagerService.getPackageSetting(pkg.getPackageName());
            final String[] instructionSets = getAppDexInstructionSets(
                    AndroidPackageUtils.getPrimaryCpuAbi(pkg, pkgSetting),
                    AndroidPackageUtils.getSecondaryCpuAbi(pkg, pkgSetting));
            final List<String> paths =
                    AndroidPackageUtils.getAllCodePathsExcludingResourceOnly(pkg);
            final String[] dexCodeInstructionSets = getDexCodeInstructionSets(instructionSets);
            for (String dexCodeInstructionSet : dexCodeInstructionSets) {
                for (String path : paths) {
                    String oatDir = PackageDexOptimizer.getOatDir(
                            new File(pkg.getPath())).getAbsolutePath();

                    // TODO: Check first whether there is an artifact, to save the roundtrip time.

                    packagePaths++;
                    try {
                        installer.moveAb(path, dexCodeInstructionSet, oatDir);
                        pathsSuccessful++;
                    } catch (InstallerException e) {
                    }
                }
            }
        }
        Slog.i(TAG, "Moved " + pathsSuccessful + "/" + packagePaths);
    }

    /**
     * Initialize logging fields.
     */
    private void prepareMetricsLogging(int important, int others, long spaceBegin, long spaceBulk) {
        availableSpaceBefore = spaceBegin;
        availableSpaceAfterBulkDelete = spaceBulk;
        availableSpaceAfterDexopt = 0;

        importantPackageCount = important;
        otherPackageCount = others;

        dexoptCommandCountTotal = mDexoptCommands.size();
        dexoptCommandCountExecuted = 0;

        otaDexoptTimeStart = System.nanoTime();
    }

    private static int inMegabytes(long value) {
        long in_mega_bytes = value / (1024 * 1024);
        if (in_mega_bytes > Integer.MAX_VALUE) {
            Log.w(TAG, "Recording " + in_mega_bytes + "MB of free space, overflowing range");
            return Integer.MAX_VALUE;
        }
        return (int)in_mega_bytes;
    }

    private void performMetricsLogging() {
        long finalTime = System.nanoTime();

        metricsLogger.histogram("ota_dexopt_available_space_before_mb",
                inMegabytes(availableSpaceBefore));
        metricsLogger.histogram("ota_dexopt_available_space_after_bulk_delete_mb",
                inMegabytes(availableSpaceAfterBulkDelete));
        metricsLogger.histogram("ota_dexopt_available_space_after_dexopt_mb",
                inMegabytes(availableSpaceAfterDexopt));

        metricsLogger.histogram("ota_dexopt_num_important_packages", importantPackageCount);
        metricsLogger.histogram("ota_dexopt_num_other_packages", otherPackageCount);

        metricsLogger.histogram("ota_dexopt_num_commands", dexoptCommandCountTotal);
        metricsLogger.histogram("ota_dexopt_num_commands_executed", dexoptCommandCountExecuted);

        final int elapsedTimeSeconds =
                (int) TimeUnit.NANOSECONDS.toSeconds(finalTime - otaDexoptTimeStart);
        metricsLogger.histogram("ota_dexopt_time_s", elapsedTimeSeconds);
    }

    private static class OTADexoptPackageDexOptimizer extends
            PackageDexOptimizer.ForcedUpdatePackageDexOptimizer {
        public OTADexoptPackageDexOptimizer(Installer installer, Object installLock,
                Context context) {
            super(installer, installLock, context, "*otadexopt*");
        }
    }
}
