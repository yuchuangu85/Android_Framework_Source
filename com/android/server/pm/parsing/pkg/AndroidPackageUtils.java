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

package com.android.server.pm.parsing.pkg;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageParser.PackageParserException;
import android.content.pm.SharedLibraryInfo;
import android.content.pm.VersionedPackage;
import android.content.pm.dex.DexMetadataHelper;
import android.content.pm.parsing.ParsingPackageRead;
import android.content.pm.parsing.ParsingPackageUtils;
import android.content.pm.parsing.component.ParsedActivity;
import android.content.pm.parsing.component.ParsedInstrumentation;
import android.content.pm.parsing.component.ParsedProvider;
import android.content.pm.parsing.component.ParsedService;
import android.os.incremental.IncrementalManager;
import android.text.TextUtils;

import com.android.internal.content.NativeLibraryHelper;
import com.android.internal.util.ArrayUtils;
import com.android.server.SystemConfig;
import com.android.server.pm.PackageSetting;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** @hide */
public class AndroidPackageUtils {

    private AndroidPackageUtils() {
    }

    public static List<String> getAllCodePathsExcludingResourceOnly(
            AndroidPackage aPkg) {
        PackageImpl pkg = (PackageImpl) aPkg;
        ArrayList<String> paths = new ArrayList<>();
        if (pkg.isHasCode()) {
            paths.add(pkg.getBaseApkPath());
        }
        String[] splitCodePaths = pkg.getSplitCodePaths();
        if (!ArrayUtils.isEmpty(splitCodePaths)) {
            for (int i = 0; i < splitCodePaths.length; i++) {
                if ((pkg.getSplitFlags()[i] & ApplicationInfo.FLAG_HAS_CODE) != 0) {
                    paths.add(splitCodePaths[i]);
                }
            }
        }
        return paths;
    }

    /**
     * @return a list of the base and split code paths.
     */
    public static List<String> getAllCodePaths(AndroidPackage aPkg) {
        PackageImpl pkg = (PackageImpl) aPkg;
        ArrayList<String> paths = new ArrayList<>();
        paths.add(pkg.getBaseApkPath());

        String[] splitCodePaths = pkg.getSplitCodePaths();
        if (!ArrayUtils.isEmpty(splitCodePaths)) {
            Collections.addAll(paths, splitCodePaths);
        }
        return paths;
    }

    public static SharedLibraryInfo createSharedLibraryForStatic(AndroidPackage pkg) {
        return new SharedLibraryInfo(null, pkg.getPackageName(),
                AndroidPackageUtils.getAllCodePaths(pkg),
                pkg.getStaticSharedLibName(),
                pkg.getStaticSharedLibVersion(),
                SharedLibraryInfo.TYPE_STATIC,
                new VersionedPackage(pkg.getManifestPackageName(),
                        pkg.getLongVersionCode()),
                null, null, false /* isNative */);
    }

    public static SharedLibraryInfo createSharedLibraryForDynamic(AndroidPackage pkg, String name) {
        return new SharedLibraryInfo(null, pkg.getPackageName(),
                AndroidPackageUtils.getAllCodePaths(pkg), name,
                SharedLibraryInfo.VERSION_UNDEFINED,
                SharedLibraryInfo.TYPE_DYNAMIC, new VersionedPackage(pkg.getPackageName(),
                pkg.getLongVersionCode()),
                null, null, false /* isNative */);
    }

    /**
     * Return the dex metadata files for the given package as a map
     * [code path -> dex metadata path].
     *
     * NOTE: involves I/O checks.
     */
    public static Map<String, String> getPackageDexMetadata(AndroidPackage pkg) {
        return DexMetadataHelper.buildPackageApkToDexMetadataMap
                (AndroidPackageUtils.getAllCodePaths(pkg));
    }

    /**
     * Validate the dex metadata files installed for the given package.
     *
     * @throws PackageParserException in case of errors.
     */
    public static void validatePackageDexMetadata(AndroidPackage pkg)
            throws PackageParserException {
        Collection<String> apkToDexMetadataList = getPackageDexMetadata(pkg).values();
        String packageName = pkg.getPackageName();
        long versionCode = pkg.toAppInfoWithoutState().longVersionCode;
        for (String dexMetadata : apkToDexMetadataList) {
            DexMetadataHelper.validateDexMetadataFile(dexMetadata, packageName, versionCode);
        }
    }

    public static NativeLibraryHelper.Handle createNativeLibraryHandle(AndroidPackage pkg)
            throws IOException {
        return NativeLibraryHelper.Handle.create(
                AndroidPackageUtils.getAllCodePaths(pkg),
                pkg.isMultiArch(),
                pkg.isExtractNativeLibs(),
                pkg.isDebuggable()
        );
    }

    public static boolean canHaveOatDir(AndroidPackage pkg, boolean isUpdatedSystemApp) {
        // The following app types CANNOT have oat directory
        // - non-updated system apps,
        // - incrementally installed apps.
        if (pkg.isSystem() && !isUpdatedSystemApp) {
            return false;
        }
        if (IncrementalManager.isIncrementalPath(pkg.getPath())) {
            return false;
        }
        return true;
    }

    public static boolean hasComponentClassName(AndroidPackage pkg, String className) {
        List<ParsedActivity> activities = pkg.getActivities();
        int activitiesSize = activities.size();
        for (int index = 0; index < activitiesSize; index++) {
            if (Objects.equals(className, activities.get(index).getName())) {
                return true;
            }
        }

        List<ParsedActivity> receivers = pkg.getReceivers();
        int receiversSize = receivers.size();
        for (int index = 0; index < receiversSize; index++) {
            if (Objects.equals(className, receivers.get(index).getName())) {
                return true;
            }
        }

        List<ParsedProvider> providers = pkg.getProviders();
        int providersSize = providers.size();
        for (int index = 0; index < providersSize; index++) {
            if (Objects.equals(className, providers.get(index).getName())) {
                return true;
            }
        }

        List<ParsedService> services = pkg.getServices();
        int servicesSize = services.size();
        for (int index = 0; index < servicesSize; index++) {
            if (Objects.equals(className, services.get(index).getName())) {
                return true;
            }
        }

        List<ParsedInstrumentation> instrumentations = pkg.getInstrumentations();
        int instrumentationsSize = instrumentations.size();
        for (int index = 0; index < instrumentationsSize; index++) {
            if (Objects.equals(className, instrumentations.get(index).getName())) {
                return true;
            }
        }

        return false;
    }

    public static boolean isEncryptionAware(AndroidPackage pkg) {
        return pkg.isDirectBootAware() || pkg.isPartiallyDirectBootAware();
    }

    public static boolean isLibrary(AndroidPackage pkg) {
        // TODO(b/135203078): Can parsing just enforce these always match?
        return pkg.getStaticSharedLibName() != null || !pkg.getLibraryNames().isEmpty();
    }

    public static int getHiddenApiEnforcementPolicy(AndroidPackage pkg,
            @NonNull PackageSetting pkgSetting) {
        boolean isAllowedToUseHiddenApis;
        if (pkg.isSignedWithPlatformKey()) {
            isAllowedToUseHiddenApis = true;
        } else if (pkg.isSystem() || pkgSetting.getPkgState().isUpdatedSystemApp()) {
            isAllowedToUseHiddenApis = pkg.isUsesNonSdkApi()
                    || SystemConfig.getInstance().getHiddenApiWhitelistedApps().contains(
                    pkg.getPackageName());
        } else {
            isAllowedToUseHiddenApis = false;
        }

        if (isAllowedToUseHiddenApis) {
            return ApplicationInfo.HIDDEN_API_ENFORCEMENT_DISABLED;
        }

        // TODO(b/135203078): Handle maybeUpdateHiddenApiEnforcementPolicy. Right now it's done
        //  entirely through ApplicationInfo and shouldn't touch this specific class, but that
        //  may not always hold true.
//        if (mHiddenApiPolicy != ApplicationInfo.HIDDEN_API_ENFORCEMENT_DEFAULT) {
//            return mHiddenApiPolicy;
//        }
        return ApplicationInfo.HIDDEN_API_ENFORCEMENT_ENABLED;
    }

    public static int getIcon(ParsingPackageRead pkg) {
        return (ParsingPackageUtils.sUseRoundIcon && pkg.getRoundIconRes() != 0)
                ? pkg.getRoundIconRes() : pkg.getIconRes();
    }

    public static long getLongVersionCode(AndroidPackage pkg) {
        return PackageInfo.composeLongVersionCode(pkg.getVersionCodeMajor(), pkg.getVersionCode());
    }

    /**
     * Returns false iff the provided flags include the {@link PackageManager#MATCH_SYSTEM_ONLY}
     * flag and the provided package is not a system package. Otherwise returns {@code true}.
     */
    public static boolean isMatchForSystemOnly(AndroidPackage pkg, int flags) {
        if ((flags & PackageManager.MATCH_SYSTEM_ONLY) != 0) {
            return pkg.isSystem();
        }
        return true;
    }

    public static String getPrimaryCpuAbi(AndroidPackage pkg, @Nullable PackageSetting pkgSetting) {
        if (pkgSetting == null || TextUtils.isEmpty(pkgSetting.primaryCpuAbiString)) {
            return pkg.getPrimaryCpuAbi();
        }

        return pkgSetting.primaryCpuAbiString;
    }

    public static String getSecondaryCpuAbi(AndroidPackage pkg,
            @Nullable PackageSetting pkgSetting) {
        if (pkgSetting == null || TextUtils.isEmpty(pkgSetting.secondaryCpuAbiString)) {
            return pkg.getSecondaryCpuAbi();
        }

        return pkgSetting.secondaryCpuAbiString;
    }

    /**
     * Returns the primary ABI as parsed from the package. Used only during parsing and derivation.
     * Otherwise prefer {@link #getPrimaryCpuAbi(AndroidPackage, PackageSetting)}.
     *
     * TODO(b/135203078): Actually hide the method
     * Placed in the utility to hide the method on the interface.
     */
    public static String getRawPrimaryCpuAbi(AndroidPackage pkg) {
        return pkg.getPrimaryCpuAbi();
    }

    /**
     * Returns the secondary ABI as parsed from the package. Used only during parsing and
     * derivation. Otherwise prefer {@link #getSecondaryCpuAbi(AndroidPackage, PackageSetting)}.
     *
     * TODO(b/135203078): Actually hide the method
     * Placed in the utility to hide the method on the interface.
     */
    public static String getRawSecondaryCpuAbi(AndroidPackage pkg) {
        return pkg.getSecondaryCpuAbi();
    }

    public static String getSeInfo(AndroidPackage pkg, @Nullable PackageSetting pkgSetting) {
        if (pkgSetting != null) {
            String overrideSeInfo = pkgSetting.getPkgState().getOverrideSeInfo();
            if (!TextUtils.isEmpty(overrideSeInfo)) {
                return overrideSeInfo;
            }
        }
        return pkg.getSeInfo();
    }
}
