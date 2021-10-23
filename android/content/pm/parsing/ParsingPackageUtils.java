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

package android.content.pm.parsing;

import static android.content.pm.ActivityInfo.FLAG_SUPPORTS_PICTURE_IN_PICTURE;
import static android.content.pm.ActivityInfo.RESIZE_MODE_UNRESIZEABLE;
import static android.content.pm.PackageManager.INSTALL_PARSE_FAILED_BAD_MANIFEST;
import static android.content.pm.PackageManager.INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES;
import static android.content.pm.PackageManager.INSTALL_PARSE_FAILED_ONLY_COREAPP_ALLOWED;
import static android.content.pm.PackageManager.INSTALL_PARSE_FAILED_RESOURCES_ARSC_COMPRESSED;
import static android.content.pm.PackageManager.INSTALL_PARSE_FAILED_UNEXPECTED_EXCEPTION;
import static android.os.Build.VERSION_CODES.DONUT;
import static android.os.Build.VERSION_CODES.O;
import static android.os.Trace.TRACE_TAG_PACKAGE_MANAGER;

import android.annotation.AnyRes;
import android.annotation.CheckResult;
import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.StyleableRes;
import android.app.ActivityThread;
import android.app.ResourcesManager;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.ConfigurationInfo;
import android.content.pm.FeatureGroupInfo;
import android.content.pm.FeatureInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.Property;
import android.content.pm.PackageParser;
import android.content.pm.PackageParser.PackageParserException;
import android.content.pm.PackageParser.SigningDetails;
import android.content.pm.Signature;
import android.content.pm.parsing.component.ComponentParseUtils;
import android.content.pm.parsing.component.ParsedActivity;
import android.content.pm.parsing.component.ParsedActivityUtils;
import android.content.pm.parsing.component.ParsedAttribution;
import android.content.pm.parsing.component.ParsedAttributionUtils;
import android.content.pm.parsing.component.ParsedComponent;
import android.content.pm.parsing.component.ParsedInstrumentation;
import android.content.pm.parsing.component.ParsedInstrumentationUtils;
import android.content.pm.parsing.component.ParsedIntentInfo;
import android.content.pm.parsing.component.ParsedIntentInfoUtils;
import android.content.pm.parsing.component.ParsedMainComponent;
import android.content.pm.parsing.component.ParsedPermission;
import android.content.pm.parsing.component.ParsedPermissionGroup;
import android.content.pm.parsing.component.ParsedPermissionUtils;
import android.content.pm.parsing.component.ParsedProcess;
import android.content.pm.parsing.component.ParsedProcessUtils;
import android.content.pm.parsing.component.ParsedProvider;
import android.content.pm.parsing.component.ParsedProviderUtils;
import android.content.pm.parsing.component.ParsedService;
import android.content.pm.parsing.component.ParsedServiceUtils;
import android.content.pm.parsing.component.ParsedUsesPermission;
import android.content.pm.parsing.result.ParseInput;
import android.content.pm.parsing.result.ParseInput.DeferredError;
import android.content.pm.parsing.result.ParseResult;
import android.content.pm.parsing.result.ParseTypeImpl;
import android.content.pm.split.DefaultSplitAssetLoader;
import android.content.pm.split.SplitAssetDependencyLoader;
import android.content.pm.split.SplitAssetLoader;
import android.content.res.ApkAssets;
import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.FileUtils;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.Trace;
import android.os.UserHandle;
import android.os.ext.SdkExtensions;
import android.permission.PermissionManager;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.util.TypedValue;
import android.util.apk.ApkSignatureVerifier;

import com.android.internal.R;
import com.android.internal.os.ClassLoaderFactory;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.XmlUtils;

import libcore.io.IoUtils;
import libcore.util.EmptyArray;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.StringTokenizer;

/**
 * TODO(b/135203078): Differentiate between parse_ methods and some add_ method for whether it
 * mutates the passed-in component or not. Or consolidate so all parse_ methods mutate.
 *
 * @hide
 */
public class ParsingPackageUtils {

    private static final String TAG = ParsingUtils.TAG;

    public static final boolean DEBUG_JAR = false;
    public static final boolean DEBUG_BACKUP = false;
    public static final float DEFAULT_PRE_O_MAX_ASPECT_RATIO = 1.86f;

    /** File name in an APK for the Android manifest. */
    public static final String ANDROID_MANIFEST_FILENAME = "AndroidManifest.xml";

    /** Path prefix for apps on expanded storage */
    public static final String MNT_EXPAND = "/mnt/expand/";

    public static final String TAG_ADOPT_PERMISSIONS = "adopt-permissions";
    public static final String TAG_APPLICATION = "application";
    public static final String TAG_COMPATIBLE_SCREENS = "compatible-screens";
    public static final String TAG_EAT_COMMENT = "eat-comment";
    public static final String TAG_FEATURE_GROUP = "feature-group";
    public static final String TAG_INSTRUMENTATION = "instrumentation";
    public static final String TAG_KEY_SETS = "key-sets";
    public static final String TAG_MANIFEST = "manifest";
    public static final String TAG_ORIGINAL_PACKAGE = "original-package";
    public static final String TAG_OVERLAY = "overlay";
    public static final String TAG_PACKAGE = "package";
    public static final String TAG_PACKAGE_VERIFIER = "package-verifier";
    public static final String TAG_ATTRIBUTION = "attribution";
    public static final String TAG_PERMISSION = "permission";
    public static final String TAG_PERMISSION_GROUP = "permission-group";
    public static final String TAG_PERMISSION_TREE = "permission-tree";
    public static final String TAG_PROTECTED_BROADCAST = "protected-broadcast";
    public static final String TAG_QUERIES = "queries";
    public static final String TAG_RESTRICT_UPDATE = "restrict-update";
    public static final String TAG_SUPPORT_SCREENS = "supports-screens";
    public static final String TAG_SUPPORTS_INPUT = "supports-input";
    public static final String TAG_USES_CONFIGURATION = "uses-configuration";
    public static final String TAG_USES_FEATURE = "uses-feature";
    public static final String TAG_USES_GL_TEXTURE = "uses-gl-texture";
    public static final String TAG_USES_PERMISSION = "uses-permission";
    public static final String TAG_USES_PERMISSION_SDK_23 = "uses-permission-sdk-23";
    public static final String TAG_USES_PERMISSION_SDK_M = "uses-permission-sdk-m";
    public static final String TAG_USES_SDK = "uses-sdk";
    public static final String TAG_USES_SPLIT = "uses-split";
    public static final String TAG_PROFILEABLE = "profileable";

    public static final String METADATA_MAX_ASPECT_RATIO = "android.max_aspect";
    public static final String METADATA_SUPPORTS_SIZE_CHANGES = "android.supports_size_changes";
    public static final String METADATA_ACTIVITY_WINDOW_LAYOUT_AFFINITY =
            "android.activity_window_layout_affinity";
    public static final String METADATA_ACTIVITY_LAUNCH_MODE = "android.activity.launch_mode";

    public static final int SDK_VERSION = Build.VERSION.SDK_INT;
    public static final String[] SDK_CODENAMES = Build.VERSION.ACTIVE_CODENAMES;

    public static boolean sCompatibilityModeEnabled = true;
    public static boolean sUseRoundIcon = false;

    public static final int PARSE_DEFAULT_INSTALL_LOCATION =
            PackageInfo.INSTALL_LOCATION_UNSPECIFIED;
    public static final int PARSE_DEFAULT_TARGET_SANDBOX = 1;

    /** If set to true, we will only allow package files that exactly match
     *  the DTD. Otherwise, we try to get as much from the package as we
     *  can without failing. This should normally be set to false, to
     *  support extensions to the DTD in future versions. */
    public static final boolean RIGID_PARSER = false;

    public static final int PARSE_MUST_BE_APK = 1 << 0;
    public static final int PARSE_IGNORE_PROCESSES = 1 << 1;
    public static final int PARSE_EXTERNAL_STORAGE = 1 << 3;
    public static final int PARSE_IS_SYSTEM_DIR = 1 << 4;
    public static final int PARSE_COLLECT_CERTIFICATES = 1 << 5;
    public static final int PARSE_ENFORCE_CODE = 1 << 6;
    public static final int PARSE_CHATTY = 1 << 31;

    @IntDef(flag = true, prefix = { "PARSE_" }, value = {
            PARSE_CHATTY,
            PARSE_COLLECT_CERTIFICATES,
            PARSE_ENFORCE_CODE,
            PARSE_EXTERNAL_STORAGE,
            PARSE_IGNORE_PROCESSES,
            PARSE_IS_SYSTEM_DIR,
            PARSE_MUST_BE_APK,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ParseFlags {}

    /**
     * For those names would be used as a part of the file name. Limits size to 223 and reserves 32
     * for the OS.
     */
    private static final int MAX_FILE_NAME_SIZE = 223;

    /**
     * @see #parseDefault(ParseInput, File, int, List, boolean)
     */
    @NonNull
    public static ParseResult<ParsingPackage> parseDefaultOneTime(File file,
            @ParseFlags int parseFlags,
            @NonNull List<PermissionManager.SplitPermissionInfo> splitPermissions,
            boolean collectCertificates) {
        ParseInput input = ParseTypeImpl.forDefaultParsing().reset();
        return parseDefault(input, file, parseFlags, splitPermissions, collectCertificates);
    }

    /**
     * For cases outside of PackageManagerService when an APK needs to be parsed as a one-off
     * request, without caching the input object and without querying the internal system state
     * for feature support.
     */
    @NonNull
    public static ParseResult<ParsingPackage> parseDefault(ParseInput input, File file,
            @ParseFlags int parseFlags,
            @NonNull List<PermissionManager.SplitPermissionInfo> splitPermissions,
            boolean collectCertificates) {
        ParseResult<ParsingPackage> result;

        ParsingPackageUtils parser = new ParsingPackageUtils(false, null, null, splitPermissions,
                new Callback() {
                    @Override
                    public boolean hasFeature(String feature) {
                        // Assume the device doesn't support anything. This will affect permission
                        // parsing and will force <uses-permission/> declarations to include all
                        // requiredNotFeature permissions and exclude all requiredFeature
                        // permissions. This mirrors the old behavior.
                        return false;
                    }

                    @Override
                    public ParsingPackage startParsingPackage(
                            @NonNull String packageName,
                            @NonNull String baseApkPath,
                            @NonNull String path,
                            @NonNull TypedArray manifestArray, boolean isCoreApp) {
                        return new ParsingPackageImpl(packageName, baseApkPath, path,
                                manifestArray);
                    }
                });
        try {
            result = parser.parsePackage(input, file, parseFlags);
            if (result.isError()) {
                return result;
            }
        } catch (PackageParser.PackageParserException e) {
            return input.error(PackageManager.INSTALL_PARSE_FAILED_UNEXPECTED_EXCEPTION,
                    "Error parsing package", e);
        }

        try {
            ParsingPackage pkg = result.getResult();
            if (collectCertificates) {
                pkg.setSigningDetails(
                        ParsingPackageUtils.getSigningDetails(pkg, false /* skipVerify */));
            }

            // Need to call this to finish the parsing stage
            pkg.hideAsParsed();

            return input.success(pkg);
        } catch (PackageParser.PackageParserException e) {
            return input.error(PackageManager.INSTALL_PARSE_FAILED_UNEXPECTED_EXCEPTION,
                    "Error collecting package certificates", e);
        }
    }

    private boolean mOnlyCoreApps;
    private String[] mSeparateProcesses;
    private DisplayMetrics mDisplayMetrics;
    @NonNull
    private List<PermissionManager.SplitPermissionInfo> mSplitPermissionInfos;
    private Callback mCallback;

    public ParsingPackageUtils(boolean onlyCoreApps, String[] separateProcesses,
            DisplayMetrics displayMetrics,
            @NonNull List<PermissionManager.SplitPermissionInfo> splitPermissions,
            @NonNull Callback callback) {
        mOnlyCoreApps = onlyCoreApps;
        mSeparateProcesses = separateProcesses;
        mDisplayMetrics = displayMetrics;
        mSplitPermissionInfos = splitPermissions;
        mCallback = callback;
    }

    /**
     * Parse the package at the given location. Automatically detects if the
     * package is a monolithic style (single APK file) or cluster style
     * (directory of APKs).
     * <p>
     * This performs validity checking on cluster style packages, such as
     * requiring identical package name and version codes, a single base APK,
     * and unique split names.
     * <p>
     * Note that this <em>does not</em> perform signature verification; that
     * must be done separately in {@link #getSigningDetails(ParsingPackageRead, boolean)}.
     *
     * If {@code useCaches} is true, the package parser might return a cached
     * result from a previous parse of the same {@code packageFile} with the same
     * {@code flags}. Note that this method does not check whether {@code packageFile}
     * has changed since the last parse, it's up to callers to do so.
     *
     * @see PackageParser#parsePackageLite(File, int)
     */
    public ParseResult<ParsingPackage> parsePackage(ParseInput input, File packageFile,
            int flags)
            throws PackageParserException {
        if (packageFile.isDirectory()) {
            return parseClusterPackage(input, packageFile, flags);
        } else {
            return parseMonolithicPackage(input, packageFile, flags);
        }
    }

    /**
     * Parse all APKs contained in the given directory, treating them as a
     * single package. This also performs validity checking, such as requiring
     * identical package name and version codes, a single base APK, and unique
     * split names.
     * <p>
     * Note that this <em>does not</em> perform signature verification; that
     * must be done separately in {@link #getSigningDetails(ParsingPackageRead, boolean)}.
     */
    private ParseResult<ParsingPackage> parseClusterPackage(ParseInput input, File packageDir,
            int flags) {
        final ParseResult<PackageLite> liteResult =
                ApkLiteParseUtils.parseClusterPackageLite(input, packageDir, 0);
        if (liteResult.isError()) {
            return input.error(liteResult);
        }

        final PackageLite lite = liteResult.getResult();
        if (mOnlyCoreApps && !lite.isCoreApp()) {
            return input.error(INSTALL_PARSE_FAILED_ONLY_COREAPP_ALLOWED,
                    "Not a coreApp: " + packageDir);
        }

        // Build the split dependency tree.
        SparseArray<int[]> splitDependencies = null;
        final SplitAssetLoader assetLoader;
        if (lite.isIsolatedSplits() && !ArrayUtils.isEmpty(lite.getSplitNames())) {
            try {
                splitDependencies = SplitAssetDependencyLoader.createDependenciesFromPackage(lite);
                assetLoader = new SplitAssetDependencyLoader(lite, splitDependencies, flags);
            } catch (SplitAssetDependencyLoader.IllegalDependencyException e) {
                return input.error(INSTALL_PARSE_FAILED_BAD_MANIFEST, e.getMessage());
            }
        } else {
            assetLoader = new DefaultSplitAssetLoader(lite, flags);
        }

        try {
            final File baseApk = new File(lite.getBaseApkPath());
            final ParseResult<ParsingPackage> result = parseBaseApk(input, baseApk,
                    lite.getPath(), assetLoader, flags);
            if (result.isError()) {
                return input.error(result);
            }

            ParsingPackage pkg = result.getResult();
            if (!ArrayUtils.isEmpty(lite.getSplitNames())) {
                pkg.asSplit(
                        lite.getSplitNames(),
                        lite.getSplitApkPaths(),
                        lite.getSplitRevisionCodes(),
                        splitDependencies
                );
                final int num = lite.getSplitNames().length;

                for (int i = 0; i < num; i++) {
                    final AssetManager splitAssets = assetLoader.getSplitAssetManager(i);
                    parseSplitApk(input, pkg, i, splitAssets, flags);
                }
            }

            pkg.setUse32BitAbi(lite.isUse32bitAbi());
            return input.success(pkg);
        } catch (PackageParserException e) {
            return input.error(INSTALL_PARSE_FAILED_UNEXPECTED_EXCEPTION,
                    "Failed to load assets: " + lite.getBaseApkPath(), e);
        } finally {
            IoUtils.closeQuietly(assetLoader);
        }
    }

    /**
     * Parse the given APK file, treating it as as a single monolithic package.
     * <p>
     * Note that this <em>does not</em> perform signature verification; that
     * must be done separately in {@link #getSigningDetails(ParsingPackageRead, boolean)}.
     */
    private ParseResult<ParsingPackage> parseMonolithicPackage(ParseInput input, File apkFile,
            int flags) throws PackageParserException {
        final ParseResult<PackageLite> liteResult =
                ApkLiteParseUtils.parseMonolithicPackageLite(input, apkFile, flags);
        if (liteResult.isError()) {
            return input.error(liteResult);
        }

        final PackageLite lite = liteResult.getResult();
        if (mOnlyCoreApps && !lite.isCoreApp()) {
            return input.error(INSTALL_PARSE_FAILED_ONLY_COREAPP_ALLOWED,
                    "Not a coreApp: " + apkFile);
        }

        final SplitAssetLoader assetLoader = new DefaultSplitAssetLoader(lite, flags);
        try {
            final ParseResult<ParsingPackage> result = parseBaseApk(input,
                    apkFile,
                    apkFile.getCanonicalPath(),
                    assetLoader, flags);
            if (result.isError()) {
                return input.error(result);
            }

            return input.success(result.getResult()
                    .setUse32BitAbi(lite.isUse32bitAbi()));
        } catch (IOException e) {
            return input.error(INSTALL_PARSE_FAILED_UNEXPECTED_EXCEPTION,
                    "Failed to get path: " + apkFile, e);
        } finally {
            IoUtils.closeQuietly(assetLoader);
        }
    }

    private ParseResult<ParsingPackage> parseBaseApk(ParseInput input, File apkFile,
            String codePath, SplitAssetLoader assetLoader, int flags)
            throws PackageParserException {
        final String apkPath = apkFile.getAbsolutePath();

        String volumeUuid = null;
        if (apkPath.startsWith(MNT_EXPAND)) {
            final int end = apkPath.indexOf('/', MNT_EXPAND.length());
            volumeUuid = apkPath.substring(MNT_EXPAND.length(), end);
        }

        if (DEBUG_JAR) Slog.d(TAG, "Scanning base APK: " + apkPath);

        final AssetManager assets = assetLoader.getBaseAssetManager();
        final int cookie = assets.findCookieForPath(apkPath);
        if (cookie == 0) {
            return input.error(INSTALL_PARSE_FAILED_BAD_MANIFEST,
                    "Failed adding asset path: " + apkPath);
        }

        try (XmlResourceParser parser = assets.openXmlResourceParser(cookie,
                ANDROID_MANIFEST_FILENAME)) {
            final Resources res = new Resources(assets, mDisplayMetrics, null);

            ParseResult<ParsingPackage> result = parseBaseApk(input, apkPath, codePath, res,
                    parser, flags);
            if (result.isError()) {
                return input.error(result.getErrorCode(),
                        apkPath + " (at " + parser.getPositionDescription() + "): "
                                + result.getErrorMessage());
            }

            final ParsingPackage pkg = result.getResult();
            if (assets.containsAllocatedTable()) {
                final ParseResult<?> deferResult = input.deferError(
                        "Targeting R+ (version " + Build.VERSION_CODES.R + " and above) requires"
                                + " the resources.arsc of installed APKs to be stored uncompressed"
                                + " and aligned on a 4-byte boundary",
                        DeferredError.RESOURCES_ARSC_COMPRESSED);
                if (deferResult.isError()) {
                    return input.error(INSTALL_PARSE_FAILED_RESOURCES_ARSC_COMPRESSED,
                            deferResult.getErrorMessage());
                }
            }

            ApkAssets apkAssets = assetLoader.getBaseApkAssets();
            boolean definesOverlayable = false;
            try {
                definesOverlayable = apkAssets.definesOverlayable();
            } catch (IOException ignored) {
                // Will fail if there's no packages in the ApkAssets, which can be treated as false
            }

            if (definesOverlayable) {
                SparseArray<String> packageNames = assets.getAssignedPackageIdentifiers();
                int size = packageNames.size();
                for (int index = 0; index < size; index++) {
                    String packageName = packageNames.valueAt(index);
                    Map<String, String> overlayableToActor = assets.getOverlayableMap(packageName);
                    if (overlayableToActor != null && !overlayableToActor.isEmpty()) {
                        for (String overlayable : overlayableToActor.keySet()) {
                            pkg.addOverlayable(overlayable, overlayableToActor.get(overlayable));
                        }
                    }
                }
            }

            pkg.setVolumeUuid(volumeUuid);

            if ((flags & PARSE_COLLECT_CERTIFICATES) != 0) {
                pkg.setSigningDetails(getSigningDetails(pkg, false));
            } else {
                pkg.setSigningDetails(SigningDetails.UNKNOWN);
            }

            return input.success(pkg);
        } catch (Exception e) {
            return input.error(INSTALL_PARSE_FAILED_UNEXPECTED_EXCEPTION,
                    "Failed to read manifest from " + apkPath, e);
        }
    }

    private ParseResult<ParsingPackage> parseSplitApk(ParseInput input,
            ParsingPackage pkg, int splitIndex, AssetManager assets, int flags) {
        final String apkPath = pkg.getSplitCodePaths()[splitIndex];

        if (DEBUG_JAR) Slog.d(TAG, "Scanning split APK: " + apkPath);

        // This must always succeed, as the path has been added to the AssetManager before.
        final int cookie = assets.findCookieForPath(apkPath);
        if (cookie == 0) {
            return input.error(INSTALL_PARSE_FAILED_BAD_MANIFEST,
                    "Failed adding asset path: " + apkPath);
        }
        try (XmlResourceParser parser = assets.openXmlResourceParser(cookie,
                ANDROID_MANIFEST_FILENAME)) {
            Resources res = new Resources(assets, mDisplayMetrics, null);
            ParseResult<ParsingPackage> parseResult = parseSplitApk(input, pkg, res,
                    parser, flags, splitIndex);
            if (parseResult.isError()) {
                return input.error(parseResult.getErrorCode(),
                        apkPath + " (at " + parser.getPositionDescription() + "): "
                                + parseResult.getErrorMessage());
            }

            return parseResult;
        } catch (Exception e) {
            return input.error(INSTALL_PARSE_FAILED_UNEXPECTED_EXCEPTION,
                    "Failed to read manifest from " + apkPath, e);
        }
    }

    /**
     * Parse the manifest of a <em>base APK</em>. When adding new features you
     * need to consider whether they should be supported by split APKs and child
     * packages.
     *
     * @param apkPath The package apk file path
     * @param res     The resources from which to resolve values
     * @param parser  The manifest parser
     * @param flags   Flags how to parse
     * @return Parsed package or null on error.
     */
    private ParseResult<ParsingPackage> parseBaseApk(ParseInput input, String apkPath,
            String codePath, Resources res, XmlResourceParser parser, int flags)
            throws XmlPullParserException, IOException {
        final String splitName;
        final String pkgName;

        ParseResult<Pair<String, String>> packageSplitResult =
                ApkLiteParseUtils.parsePackageSplitNames(input, parser);
        if (packageSplitResult.isError()) {
            return input.error(packageSplitResult);
        }

        Pair<String, String> packageSplit = packageSplitResult.getResult();
        pkgName = packageSplit.first;
        splitName = packageSplit.second;

        if (!TextUtils.isEmpty(splitName)) {
            return input.error(
                    PackageManager.INSTALL_PARSE_FAILED_BAD_PACKAGE_NAME,
                    "Expected base APK, but found split " + splitName
            );
        }

        final TypedArray manifestArray = res.obtainAttributes(parser, R.styleable.AndroidManifest);
        try {
            final boolean isCoreApp =
                    parser.getAttributeBooleanValue(null, "coreApp", false);
            final ParsingPackage pkg = mCallback.startParsingPackage(
                    pkgName, apkPath, codePath, manifestArray, isCoreApp);
            final ParseResult<ParsingPackage> result =
                    parseBaseApkTags(input, pkg, manifestArray, res, parser, flags);
            if (result.isError()) {
                return result;
            }

            return input.success(pkg);
        } finally {
            manifestArray.recycle();
        }
    }

    /**
     * Parse the manifest of a <em>split APK</em>.
     * <p>
     * Note that split APKs have many more restrictions on what they're capable
     * of doing, so many valid features of a base APK have been carefully
     * omitted here.
     *
     * @param pkg builder to fill
     * @return false on failure
     */
    private ParseResult<ParsingPackage> parseSplitApk(ParseInput input, ParsingPackage pkg,
            Resources res, XmlResourceParser parser, int flags, int splitIndex)
            throws XmlPullParserException, IOException, PackageParserException {
        AttributeSet attrs = parser;

        // We parsed manifest tag earlier; just skip past it
        PackageParser.parsePackageSplitNames(parser, attrs);

        int type;

        boolean foundApp = false;

        int outerDepth = parser.getDepth();
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT) {
            if (outerDepth + 1 < parser.getDepth() || type != XmlPullParser.START_TAG) {
                continue;
            }

            final ParseResult result;
            String tagName = parser.getName();
            if (TAG_APPLICATION.equals(tagName)) {
                if (foundApp) {
                    if (RIGID_PARSER) {
                        result = input.error("<manifest> has more than one <application>");
                    } else {
                        Slog.w(TAG, "<manifest> has more than one <application>");
                        result = input.success(null);
                    }
                } else {
                    foundApp = true;
                    result = parseSplitApplication(input, pkg, res, parser, flags, splitIndex);
                }
            } else {
                result = ParsingUtils.unknownTag("<manifest>", pkg, parser, input);
            }

            if (result.isError()) {
                return input.error(result);
            }
        }

        if (!foundApp) {
            ParseResult<?> deferResult = input.deferError(
                    "<manifest> does not contain an <application>", DeferredError.MISSING_APP_TAG);
            if (deferResult.isError()) {
                return input.error(deferResult);
            }
        }

        return input.success(pkg);
    }

    /**
     * Parse the {@code application} XML tree at the current parse location in a
     * <em>split APK</em> manifest.
     * <p>
     * Note that split APKs have many more restrictions on what they're capable
     * of doing, so many valid features of a base APK have been carefully
     * omitted here.
     */
    private ParseResult<ParsingPackage> parseSplitApplication(ParseInput input,
            ParsingPackage pkg, Resources res, XmlResourceParser parser, int flags, int splitIndex)
            throws XmlPullParserException, IOException {
        TypedArray sa = res.obtainAttributes(parser, R.styleable.AndroidManifestApplication);
        try {
            pkg.setSplitHasCode(splitIndex, sa.getBoolean(
                    R.styleable.AndroidManifestApplication_hasCode, true));

            final String classLoaderName = sa.getString(
                    R.styleable.AndroidManifestApplication_classLoader);
            if (classLoaderName == null || ClassLoaderFactory.isValidClassLoaderName(
                    classLoaderName)) {
                pkg.setSplitClassLoaderName(splitIndex, classLoaderName);
            } else {
                return input.error("Invalid class loader name: " + classLoaderName);
            }
        } finally {
            sa.recycle();
        }
        final int depth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG
                || parser.getDepth() > depth)) {
            if (type != XmlPullParser.START_TAG) {
                continue;
            }

            ParsedMainComponent mainComponent = null;

            final ParseResult result;
            String tagName = parser.getName();
            boolean isActivity = false;
            switch (tagName) {
                case "activity":
                    isActivity = true;
                    // fall-through
                case "receiver":
                    ParseResult<ParsedActivity> activityResult =
                            ParsedActivityUtils.parseActivityOrReceiver(mSeparateProcesses, pkg,
                                    res,
                                    parser, flags, sUseRoundIcon, input);
                    if (activityResult.isSuccess()) {
                        ParsedActivity activity = activityResult.getResult();
                        if (isActivity) {
                            pkg.addActivity(activity);
                        } else {
                            pkg.addReceiver(activity);
                        }
                        mainComponent = activity;
                    }
                    result = activityResult;
                    break;
                case "service":
                    ParseResult<ParsedService> serviceResult = ParsedServiceUtils.parseService(
                            mSeparateProcesses, pkg, res, parser, flags,
                            sUseRoundIcon, input);
                    if (serviceResult.isSuccess()) {
                        ParsedService service = serviceResult.getResult();
                        pkg.addService(service);
                        mainComponent = service;
                    }
                    result = serviceResult;
                    break;
                case "provider":
                    ParseResult<ParsedProvider> providerResult =
                            ParsedProviderUtils.parseProvider(mSeparateProcesses, pkg, res, parser,
                                    flags, sUseRoundIcon, input);
                    if (providerResult.isSuccess()) {
                        ParsedProvider provider = providerResult.getResult();
                        pkg.addProvider(provider);
                        mainComponent = provider;
                    }
                    result = providerResult;
                    break;
                case "activity-alias":
                    activityResult = ParsedActivityUtils.parseActivityAlias(pkg, res, parser,
                            sUseRoundIcon, input);
                    if (activityResult.isSuccess()) {
                        ParsedActivity activity = activityResult.getResult();
                        pkg.addActivity(activity);
                        mainComponent = activity;
                    }

                    result = activityResult;
                    break;
                default:
                    result = parseSplitBaseAppChildTags(input, tagName, pkg, res, parser);
                    break;
            }

            if (result.isError()) {
                return input.error(result);
            }

            if (mainComponent != null && mainComponent.getSplitName() == null) {
                // If the loaded component did not specify a split, inherit the split name
                // based on the split it is defined in.
                // This is used to later load the correct split when starting this
                // component.
                mainComponent.setSplitName(pkg.getSplitNames()[splitIndex]);
            }
        }

        return input.success(pkg);
    }

    /**
     * For parsing non-MainComponents. Main ones have an order and some special handling which is
     * done directly in {@link #parseSplitApplication(ParseInput, ParsingPackage, Resources,
     * XmlResourceParser, int, int)}.
     */
    private ParseResult parseSplitBaseAppChildTags(ParseInput input, String tag, ParsingPackage pkg,
            Resources res, XmlResourceParser parser) throws IOException, XmlPullParserException {
        switch (tag) {
            case "meta-data":
                // note: application meta-data is stored off to the side, so it can
                // remain null in the primary copy (we like to avoid extra copies because
                // it can be large)
                ParseResult<Property> metaDataResult = parseMetaData(pkg, null, res,
                        parser, "<meta-data>", input);
                if (metaDataResult.isSuccess() && metaDataResult.getResult() != null) {
                    pkg.setMetaData(metaDataResult.getResult().toBundle(pkg.getMetaData()));
                }
                return metaDataResult;
            case "property":
                ParseResult<Property> propertyResult = parseMetaData(pkg, null, res,
                        parser, "<property>", input);
                if (propertyResult.isSuccess()) {
                    pkg.addProperty(propertyResult.getResult());
                }
                return propertyResult;
            case "uses-static-library":
                return parseUsesStaticLibrary(input, pkg, res, parser);
            case "uses-library":
                return parseUsesLibrary(input, pkg, res, parser);
            case "uses-native-library":
                return parseUsesNativeLibrary(input, pkg, res, parser);
            case "uses-package":
                // Dependencies for app installers; we don't currently try to
                // enforce this.
                return input.success(null);
            default:
                return ParsingUtils.unknownTag("<application>", pkg, parser, input);
        }
    }

    private ParseResult<ParsingPackage> parseBaseApkTags(ParseInput input, ParsingPackage pkg,
            TypedArray sa, Resources res, XmlResourceParser parser, int flags)
            throws XmlPullParserException, IOException {
        ParseResult<ParsingPackage> sharedUserResult = parseSharedUser(input, pkg, sa);
        if (sharedUserResult.isError()) {
            return sharedUserResult;
        }

        pkg.setInstallLocation(anInteger(PARSE_DEFAULT_INSTALL_LOCATION,
                R.styleable.AndroidManifest_installLocation, sa))
                .setTargetSandboxVersion(anInteger(PARSE_DEFAULT_TARGET_SANDBOX,
                        R.styleable.AndroidManifest_targetSandboxVersion, sa))
                /* Set the global "on SD card" flag */
                .setExternalStorage((flags & PARSE_EXTERNAL_STORAGE) != 0);

        boolean foundApp = false;
        final int depth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG
                || parser.getDepth() > depth)) {
            if (type != XmlPullParser.START_TAG) {
                continue;
            }

            String tagName = parser.getName();
            final ParseResult result;

            // <application> has special logic, so it's handled outside the general method
            if (TAG_APPLICATION.equals(tagName)) {
                if (foundApp) {
                    if (RIGID_PARSER) {
                        result = input.error("<manifest> has more than one <application>");
                    } else {
                        Slog.w(TAG, "<manifest> has more than one <application>");
                        result = input.success(null);
                    }
                } else {
                    foundApp = true;
                    result = parseBaseApplication(input, pkg, res, parser, flags);
                }
            } else {
                result = parseBaseApkTag(tagName, input, pkg, res, parser, flags);
            }

            if (result.isError()) {
                return input.error(result);
            }
        }

        if (!foundApp && ArrayUtils.size(pkg.getInstrumentations()) == 0) {
            ParseResult<?> deferResult = input.deferError(
                    "<manifest> does not contain an <application> or <instrumentation>",
                    DeferredError.MISSING_APP_TAG);
            if (deferResult.isError()) {
                return input.error(deferResult);
            }
        }

        if (!ParsedAttribution.isCombinationValid(pkg.getAttributions())) {
            return input.error(
                    INSTALL_PARSE_FAILED_BAD_MANIFEST,
                    "Combination <attribution> tags are not valid"
            );
        }

        convertNewPermissions(pkg);

        convertSplitPermissions(pkg);

        // At this point we can check if an application is not supporting densities and hence
        // cannot be windowed / resized. Note that an SDK version of 0 is common for
        // pre-Doughnut applications.
        if (pkg.getTargetSdkVersion() < DONUT
                || (!pkg.isSupportsSmallScreens()
                && !pkg.isSupportsNormalScreens()
                && !pkg.isSupportsLargeScreens()
                && !pkg.isSupportsExtraLargeScreens()
                && !pkg.isResizeable()
                && !pkg.isAnyDensity())) {
            adjustPackageToBeUnresizeableAndUnpipable(pkg);
        }

        return input.success(pkg);
    }

    private ParseResult parseBaseApkTag(String tag, ParseInput input,
            ParsingPackage pkg, Resources res, XmlResourceParser parser, int flags)
            throws IOException, XmlPullParserException {
        switch (tag) {
            case TAG_OVERLAY:
                return parseOverlay(input, pkg, res, parser);
            case TAG_KEY_SETS:
                return parseKeySets(input, pkg, res, parser);
            case "feature": // TODO moltmann: Remove
            case TAG_ATTRIBUTION:
                return parseAttribution(input, pkg, res, parser);
            case TAG_PERMISSION_GROUP:
                return parsePermissionGroup(input, pkg, res, parser);
            case TAG_PERMISSION:
                return parsePermission(input, pkg, res, parser);
            case TAG_PERMISSION_TREE:
                return parsePermissionTree(input, pkg, res, parser);
            case TAG_USES_PERMISSION:
            case TAG_USES_PERMISSION_SDK_M:
            case TAG_USES_PERMISSION_SDK_23:
                return parseUsesPermission(input, pkg, res, parser);
            case TAG_USES_CONFIGURATION:
                return parseUsesConfiguration(input, pkg, res, parser);
            case TAG_USES_FEATURE:
                return parseUsesFeature(input, pkg, res, parser);
            case TAG_FEATURE_GROUP:
                return parseFeatureGroup(input, pkg, res, parser);
            case TAG_USES_SDK:
                return parseUsesSdk(input, pkg, res, parser);
            case TAG_SUPPORT_SCREENS:
                return parseSupportScreens(input, pkg, res, parser);
            case TAG_PROTECTED_BROADCAST:
                return parseProtectedBroadcast(input, pkg, res, parser);
            case TAG_INSTRUMENTATION:
                return parseInstrumentation(input, pkg, res, parser);
            case TAG_ORIGINAL_PACKAGE:
                return parseOriginalPackage(input, pkg, res, parser);
            case TAG_ADOPT_PERMISSIONS:
                return parseAdoptPermissions(input, pkg, res, parser);
            case TAG_USES_GL_TEXTURE:
            case TAG_COMPATIBLE_SCREENS:
            case TAG_SUPPORTS_INPUT:
            case TAG_EAT_COMMENT:
                // Just skip this tag
                XmlUtils.skipCurrentTag(parser);
                return input.success(pkg);
            case TAG_RESTRICT_UPDATE:
                return parseRestrictUpdateHash(flags, input, pkg, res, parser);
            case TAG_QUERIES:
                return parseQueries(input, pkg, res, parser);
            default:
                return ParsingUtils.unknownTag("<manifest>", pkg, parser, input);
        }
    }

    private static ParseResult<ParsingPackage> parseSharedUser(ParseInput input,
            ParsingPackage pkg, TypedArray sa) {
        String str = nonConfigString(0, R.styleable.AndroidManifest_sharedUserId, sa);
        if (TextUtils.isEmpty(str)) {
            return input.success(pkg);
        }

        if (!"android".equals(pkg.getPackageName())) {
            ParseResult<?> nameResult = validateName(input, str, true, true);
            if (nameResult.isError()) {
                return input.error(PackageManager.INSTALL_PARSE_FAILED_BAD_SHARED_USER_ID,
                        "<manifest> specifies bad sharedUserId name \"" + str + "\": "
                                + nameResult.getErrorMessage());
            }
        }

        return input.success(pkg
                .setSharedUserId(str.intern())
                .setSharedUserLabel(resId(R.styleable.AndroidManifest_sharedUserLabel, sa)));
    }

    private static ParseResult<ParsingPackage> parseKeySets(ParseInput input,
            ParsingPackage pkg, Resources res, XmlResourceParser parser)
            throws XmlPullParserException, IOException {
        // we've encountered the 'key-sets' tag
        // all the keys and keysets that we want must be defined here
        // so we're going to iterate over the parser and pull out the things we want
        int outerDepth = parser.getDepth();
        int currentKeySetDepth = -1;
        int type;
        String currentKeySet = null;
        ArrayMap<String, PublicKey> publicKeys = new ArrayMap<>();
        ArraySet<String> upgradeKeySets = new ArraySet<>();
        ArrayMap<String, ArraySet<String>> definedKeySets = new ArrayMap<>();
        ArraySet<String> improperKeySets = new ArraySet<>();
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG) {
                if (parser.getDepth() == currentKeySetDepth) {
                    currentKeySet = null;
                    currentKeySetDepth = -1;
                }
                continue;
            }
            String tagName = parser.getName();
            switch (tagName) {
                case "key-set": {
                    if (currentKeySet != null) {
                        return input.error("Improperly nested 'key-set' tag at "
                                + parser.getPositionDescription());
                    }
                    TypedArray sa = res.obtainAttributes(parser, R.styleable.AndroidManifestKeySet);
                    try {
                        final String keysetName = sa.getNonResourceString(
                                R.styleable.AndroidManifestKeySet_name);
                        definedKeySets.put(keysetName, new ArraySet<>());
                        currentKeySet = keysetName;
                        currentKeySetDepth = parser.getDepth();
                    } finally {
                        sa.recycle();
                    }
                } break;
                case "public-key": {
                    if (currentKeySet == null) {
                        return input.error("Improperly nested 'key-set' tag at "
                                + parser.getPositionDescription());
                    }
                    TypedArray sa = res.obtainAttributes(parser,
                            R.styleable.AndroidManifestPublicKey);
                    try {
                        final String publicKeyName = nonResString(
                                R.styleable.AndroidManifestPublicKey_name, sa);
                        final String encodedKey = nonResString(
                                R.styleable.AndroidManifestPublicKey_value, sa);
                        if (encodedKey == null && publicKeys.get(publicKeyName) == null) {
                            return input.error("'public-key' " + publicKeyName
                                    + " must define a public-key value on first use at "
                                    + parser.getPositionDescription());
                        } else if (encodedKey != null) {
                            PublicKey currentKey = PackageParser.parsePublicKey(encodedKey);
                            if (currentKey == null) {
                                Slog.w(TAG, "No recognized valid key in 'public-key' tag at "
                                        + parser.getPositionDescription() + " key-set "
                                        + currentKeySet
                                        + " will not be added to the package's defined key-sets.");
                                improperKeySets.add(currentKeySet);
                                XmlUtils.skipCurrentTag(parser);
                                continue;
                            }
                            if (publicKeys.get(publicKeyName) == null
                                    || publicKeys.get(publicKeyName).equals(currentKey)) {

                                /* public-key first definition, or matches old definition */
                                publicKeys.put(publicKeyName, currentKey);
                            } else {
                                return input.error("Value of 'public-key' " + publicKeyName
                                        + " conflicts with previously defined value at "
                                        + parser.getPositionDescription());
                            }
                        }
                        definedKeySets.get(currentKeySet).add(publicKeyName);
                        XmlUtils.skipCurrentTag(parser);
                    } finally {
                        sa.recycle();
                    }
                } break;
                case "upgrade-key-set": {
                    TypedArray sa = res.obtainAttributes(parser,
                            R.styleable.AndroidManifestUpgradeKeySet);
                    try {
                        String name = sa.getNonResourceString(
                                R.styleable.AndroidManifestUpgradeKeySet_name);
                        upgradeKeySets.add(name);
                        XmlUtils.skipCurrentTag(parser);
                    } finally {
                        sa.recycle();
                    }
                } break;
                default:
                    ParseResult result = ParsingUtils.unknownTag("<key-sets>", pkg, parser,
                            input);
                    if (result.isError()) {
                        return input.error(result);
                    }
                    break;
            }
        }
        String packageName = pkg.getPackageName();
        Set<String> publicKeyNames = publicKeys.keySet();
        if (publicKeyNames.removeAll(definedKeySets.keySet())) {
            return input.error("Package" + packageName
                    + " AndroidManifest.xml 'key-set' and 'public-key' names must be distinct.");
        }

        for (ArrayMap.Entry<String, ArraySet<String>> e : definedKeySets.entrySet()) {
            final String keySetName = e.getKey();
            if (e.getValue().size() == 0) {
                Slog.w(TAG, "Package" + packageName + " AndroidManifest.xml "
                        + "'key-set' " + keySetName + " has no valid associated 'public-key'."
                        + " Not including in package's defined key-sets.");
                continue;
            } else if (improperKeySets.contains(keySetName)) {
                Slog.w(TAG, "Package" + packageName + " AndroidManifest.xml "
                        + "'key-set' " + keySetName + " contained improper 'public-key'"
                        + " tags. Not including in package's defined key-sets.");
                continue;
            }

            for (String s : e.getValue()) {
                pkg.addKeySet(keySetName, publicKeys.get(s));
            }
        }
        if (pkg.getKeySetMapping().keySet().containsAll(upgradeKeySets)) {
            pkg.setUpgradeKeySets(upgradeKeySets);
        } else {
            return input.error("Package" + packageName
                    + " AndroidManifest.xml does not define all 'upgrade-key-set's .");
        }

        return input.success(pkg);
    }

    private static ParseResult<ParsingPackage> parseAttribution(ParseInput input,
            ParsingPackage pkg, Resources res, XmlResourceParser parser)
            throws IOException, XmlPullParserException {
        ParseResult<ParsedAttribution> result = ParsedAttributionUtils.parseAttribution(res,
                parser, input);
        if (result.isError()) {
            return input.error(result);
        }
        return input.success(pkg.addAttribution(result.getResult()));
    }

    private static ParseResult<ParsingPackage> parsePermissionGroup(ParseInput input,
            ParsingPackage pkg, Resources res, XmlResourceParser parser)
            throws XmlPullParserException, IOException {
        ParseResult<ParsedPermissionGroup> result = ParsedPermissionUtils.parsePermissionGroup(
                pkg, res, parser, sUseRoundIcon, input);
        if (result.isError()) {
            return input.error(result);
        }
        return input.success(pkg.addPermissionGroup(result.getResult()));
    }

    private static ParseResult<ParsingPackage> parsePermission(ParseInput input,
            ParsingPackage pkg, Resources res, XmlResourceParser parser)
            throws XmlPullParserException, IOException {
        ParseResult<ParsedPermission> result = ParsedPermissionUtils.parsePermission(
                pkg, res, parser, sUseRoundIcon, input);
        if (result.isError()) {
            return input.error(result);
        }
        return input.success(pkg.addPermission(result.getResult()));
    }

    private static ParseResult<ParsingPackage> parsePermissionTree(ParseInput input,
            ParsingPackage pkg, Resources res, XmlResourceParser parser)
            throws XmlPullParserException, IOException {
        ParseResult<ParsedPermission> result = ParsedPermissionUtils.parsePermissionTree(
                pkg, res, parser, sUseRoundIcon, input);
        if (result.isError()) {
            return input.error(result);
        }
        return input.success(pkg.addPermission(result.getResult()));
    }

    private ParseResult<ParsingPackage> parseUsesPermission(ParseInput input,
            ParsingPackage pkg, Resources res, XmlResourceParser parser)
            throws IOException, XmlPullParserException {
        TypedArray sa = res.obtainAttributes(parser, R.styleable.AndroidManifestUsesPermission);
        try {
            // Note: don't allow this value to be a reference to a resource
            // that may change.
            String name = sa.getNonResourceString(
                    R.styleable.AndroidManifestUsesPermission_name);

            int maxSdkVersion = 0;
            TypedValue val = sa.peekValue(
                    R.styleable.AndroidManifestUsesPermission_maxSdkVersion);
            if (val != null) {
                if (val.type >= TypedValue.TYPE_FIRST_INT && val.type <= TypedValue.TYPE_LAST_INT) {
                    maxSdkVersion = val.data;
                }
            }

            final ArraySet<String> requiredFeatures = new ArraySet<>();
            String feature = sa.getNonConfigurationString(
                    com.android.internal.R.styleable.AndroidManifestUsesPermission_requiredFeature,
                    0);
            if (feature != null) {
                requiredFeatures.add(feature);
            }

            final ArraySet<String> requiredNotFeatures = new ArraySet<>();
            feature = sa.getNonConfigurationString(
                    com.android.internal.R.styleable
                            .AndroidManifestUsesPermission_requiredNotFeature,
                    0);
            if (feature != null) {
                requiredNotFeatures.add(feature);
            }

            final int usesPermissionFlags = sa.getInt(
                com.android.internal.R.styleable.AndroidManifestUsesPermission_usesPermissionFlags,
                0);

            final int outerDepth = parser.getDepth();
            int type;
            while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                    && (type != XmlPullParser.END_TAG
                    || parser.getDepth() > outerDepth)) {
                if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                    continue;
                }

                final ParseResult<?> result;
                switch (parser.getName()) {
                    case "required-feature":
                        result = parseRequiredFeature(input, res, parser);
                        if (result.isSuccess()) {
                            requiredFeatures.add((String) result.getResult());
                        }
                        break;

                    case "required-not-feature":
                        result = parseRequiredNotFeature(input, res, parser);
                        if (result.isSuccess()) {
                            requiredNotFeatures.add((String) result.getResult());
                        }
                        break;

                    default:
                        result = ParsingUtils.unknownTag("<uses-permission>", pkg, parser, input);
                        break;
                }

                if (result.isError()) {
                    return input.error(result);
                }
            }

            // Can only succeed from here on out
            ParseResult<ParsingPackage> success = input.success(pkg);

            if (name == null) {
                return success;
            }

            if ((maxSdkVersion != 0) && (maxSdkVersion < Build.VERSION.RESOURCES_SDK_INT)) {
                return success;
            }

            if (mCallback != null) {
                // Only allow requesting this permission if the platform supports all of the
                // "required-feature"s.
                for (int i = requiredFeatures.size() - 1; i >= 0; i--) {
                    if (!mCallback.hasFeature(requiredFeatures.valueAt(i))) {
                        return success;
                    }
                }

                // Only allow requesting this permission if the platform does not supports any of
                // the "required-not-feature"s.
                for (int i = requiredNotFeatures.size() - 1; i >= 0; i--) {
                    if (mCallback.hasFeature(requiredNotFeatures.valueAt(i))) {
                        return success;
                    }
                }
            }

            // Quietly ignore duplicate permission requests, but fail loudly if
            // the two requests have conflicting flags
            boolean found = false;
            final List<ParsedUsesPermission> usesPermissions = pkg.getUsesPermissions();
            final int size = usesPermissions.size();
            for (int i = 0; i < size; i++) {
                final ParsedUsesPermission usesPermission = usesPermissions.get(i);
                if (Objects.equals(usesPermission.name, name)) {
                    if (usesPermission.usesPermissionFlags != usesPermissionFlags) {
                        return input.error("Conflicting uses-permissions flags: "
                                + name + " in package: " + pkg.getPackageName() + " at: "
                                + parser.getPositionDescription());
                    } else {
                        Slog.w(TAG, "Ignoring duplicate uses-permissions/uses-permissions-sdk-m: "
                                + name + " in package: " + pkg.getPackageName() + " at: "
                                + parser.getPositionDescription());
                    }
                    found = true;
                    break;
                }
            }

            if (!found) {
                pkg.addUsesPermission(new ParsedUsesPermission(name, usesPermissionFlags));
            }
            return success;
        } finally {
            sa.recycle();
        }
    }

    private ParseResult<String> parseRequiredFeature(ParseInput input, Resources res,
            AttributeSet attrs) {
        final TypedArray sa = res.obtainAttributes(attrs,
                com.android.internal.R.styleable.AndroidManifestRequiredFeature);
        try {
            final String featureName = sa.getString(
                    R.styleable.AndroidManifestRequiredFeature_name);
            return TextUtils.isEmpty(featureName)
                    ? input.error("Feature name is missing from <required-feature> tag.")
                    : input.success(featureName);
        } finally {
            sa.recycle();
        }
    }

    private ParseResult<String> parseRequiredNotFeature(ParseInput input, Resources res,
            AttributeSet attrs) {
        final TypedArray sa = res.obtainAttributes(attrs,
                com.android.internal.R.styleable.AndroidManifestRequiredNotFeature);
        try {
            final String featureName = sa.getString(
                    R.styleable.AndroidManifestRequiredNotFeature_name);
            return TextUtils.isEmpty(featureName)
                    ? input.error("Feature name is missing from <required-not-feature> tag.")
                    : input.success(featureName);
        } finally {
            sa.recycle();
        }
    }

    private static ParseResult<ParsingPackage> parseUsesConfiguration(ParseInput input,
            ParsingPackage pkg, Resources res, XmlResourceParser parser) {
        ConfigurationInfo cPref = new ConfigurationInfo();
        TypedArray sa = res.obtainAttributes(parser, R.styleable.AndroidManifestUsesConfiguration);
        try {
            cPref.reqTouchScreen = sa.getInt(
                    R.styleable.AndroidManifestUsesConfiguration_reqTouchScreen,
                    Configuration.TOUCHSCREEN_UNDEFINED);
            cPref.reqKeyboardType = sa.getInt(
                    R.styleable.AndroidManifestUsesConfiguration_reqKeyboardType,
                    Configuration.KEYBOARD_UNDEFINED);
            if (sa.getBoolean(
                    R.styleable.AndroidManifestUsesConfiguration_reqHardKeyboard,
                    false)) {
                cPref.reqInputFeatures |= ConfigurationInfo.INPUT_FEATURE_HARD_KEYBOARD;
            }
            cPref.reqNavigation = sa.getInt(
                    R.styleable.AndroidManifestUsesConfiguration_reqNavigation,
                    Configuration.NAVIGATION_UNDEFINED);
            if (sa.getBoolean(
                    R.styleable.AndroidManifestUsesConfiguration_reqFiveWayNav,
                    false)) {
                cPref.reqInputFeatures |= ConfigurationInfo.INPUT_FEATURE_FIVE_WAY_NAV;
            }
            pkg.addConfigPreference(cPref);
            return input.success(pkg);
        } finally {
            sa.recycle();
        }
    }

    private static ParseResult<ParsingPackage> parseUsesFeature(ParseInput input,
            ParsingPackage pkg, Resources res, XmlResourceParser parser) {
        FeatureInfo fi = parseFeatureInfo(res, parser);
        pkg.addReqFeature(fi);

        if (fi.name == null) {
            ConfigurationInfo cPref = new ConfigurationInfo();
            cPref.reqGlEsVersion = fi.reqGlEsVersion;
            pkg.addConfigPreference(cPref);
        }

        return input.success(pkg);
    }

    private static FeatureInfo parseFeatureInfo(Resources res, AttributeSet attrs) {
        FeatureInfo fi = new FeatureInfo();
        TypedArray sa = res.obtainAttributes(attrs, R.styleable.AndroidManifestUsesFeature);
        try {
            // Note: don't allow this value to be a reference to a resource
            // that may change.
            fi.name = sa.getNonResourceString(R.styleable.AndroidManifestUsesFeature_name);
            fi.version = sa.getInt(R.styleable.AndroidManifestUsesFeature_version, 0);
            if (fi.name == null) {
                fi.reqGlEsVersion = sa.getInt(R.styleable.AndroidManifestUsesFeature_glEsVersion,
                        FeatureInfo.GL_ES_VERSION_UNDEFINED);
            }
            if (sa.getBoolean(R.styleable.AndroidManifestUsesFeature_required, true)) {
                fi.flags |= FeatureInfo.FLAG_REQUIRED;
            }
            return fi;
        } finally {
            sa.recycle();
        }
    }

    private static ParseResult<ParsingPackage> parseFeatureGroup(ParseInput input,
            ParsingPackage pkg, Resources res, XmlResourceParser parser)
            throws IOException, XmlPullParserException {
        FeatureGroupInfo group = new FeatureGroupInfo();
        ArrayList<FeatureInfo> features = null;
        final int depth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG
                || parser.getDepth() > depth)) {
            if (type != XmlPullParser.START_TAG) {
                continue;
            }

            final String innerTagName = parser.getName();
            if (innerTagName.equals("uses-feature")) {
                FeatureInfo featureInfo = parseFeatureInfo(res, parser);
                // FeatureGroups are stricter and mandate that
                // any <uses-feature> declared are mandatory.
                featureInfo.flags |= FeatureInfo.FLAG_REQUIRED;
                features = ArrayUtils.add(features, featureInfo);
            } else {
                Slog.w(TAG,
                        "Unknown element under <feature-group>: " + innerTagName
                                + " at " + pkg.getBaseApkPath() + " "
                                + parser.getPositionDescription());
            }
        }

        if (features != null) {
            group.features = new FeatureInfo[features.size()];
            group.features = features.toArray(group.features);
        }

        pkg.addFeatureGroup(group);
        return input.success(pkg);
    }

    private static ParseResult<ParsingPackage> parseUsesSdk(ParseInput input,
            ParsingPackage pkg, Resources res, XmlResourceParser parser)
            throws IOException, XmlPullParserException {
        if (SDK_VERSION > 0) {
            TypedArray sa = res.obtainAttributes(parser, R.styleable.AndroidManifestUsesSdk);
            try {
                int minVers = ParsingUtils.DEFAULT_MIN_SDK_VERSION;
                String minCode = null;
                int targetVers = ParsingUtils.DEFAULT_TARGET_SDK_VERSION;
                String targetCode = null;

                TypedValue val = sa.peekValue(R.styleable.AndroidManifestUsesSdk_minSdkVersion);
                if (val != null) {
                    if (val.type == TypedValue.TYPE_STRING && val.string != null) {
                        minCode = val.string.toString();
                    } else {
                        // If it's not a string, it's an integer.
                        minVers = val.data;
                    }
                }

                val = sa.peekValue(R.styleable.AndroidManifestUsesSdk_targetSdkVersion);
                if (val != null) {
                    if (val.type == TypedValue.TYPE_STRING && val.string != null) {
                        targetCode = val.string.toString();
                        if (minCode == null) {
                            minCode = targetCode;
                        }
                    } else {
                        // If it's not a string, it's an integer.
                        targetVers = val.data;
                    }
                } else {
                    targetVers = minVers;
                    targetCode = minCode;
                }

                ParseResult<Integer> targetSdkVersionResult = computeTargetSdkVersion(
                        targetVers, targetCode, SDK_CODENAMES, input);
                if (targetSdkVersionResult.isError()) {
                    return input.error(targetSdkVersionResult);
                }

                int targetSdkVersion = targetSdkVersionResult.getResult();

                ParseResult<?> deferResult =
                        input.enableDeferredError(pkg.getPackageName(), targetSdkVersion);
                if (deferResult.isError()) {
                    return input.error(deferResult);
                }

                ParseResult<Integer> minSdkVersionResult = computeMinSdkVersion(minVers, minCode,
                        SDK_VERSION, SDK_CODENAMES, input);
                if (minSdkVersionResult.isError()) {
                    return input.error(minSdkVersionResult);
                }

                int minSdkVersion = minSdkVersionResult.getResult();

                pkg.setMinSdkVersion(minSdkVersion)
                        .setTargetSdkVersion(targetSdkVersion);

                int type;
                final int innerDepth = parser.getDepth();
                SparseIntArray minExtensionVersions = null;
                while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                        && (type != XmlPullParser.END_TAG || parser.getDepth() > innerDepth)) {
                    if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                        continue;
                    }

                    final ParseResult result;
                    if (parser.getName().equals("extension-sdk")) {
                        if (minExtensionVersions == null) {
                            minExtensionVersions = new SparseIntArray();
                        }
                        result = parseExtensionSdk(input, res, parser, minExtensionVersions);
                        XmlUtils.skipCurrentTag(parser);
                    } else {
                        result = ParsingUtils.unknownTag("<uses-sdk>", pkg, parser, input);
                    }

                    if (result.isError()) {
                        return input.error(result);
                    }
                }
                pkg.setMinExtensionVersions(exactSizedCopyOfSparseArray(minExtensionVersions));
            } finally {
                sa.recycle();
            }
        }
        return input.success(pkg);
    }

    @Nullable
    private static SparseIntArray exactSizedCopyOfSparseArray(@Nullable SparseIntArray input) {
        if (input == null) {
            return null;
        }
        SparseIntArray output = new SparseIntArray(input.size());
        for (int i = 0; i < input.size(); i++) {
            output.put(input.keyAt(i), input.valueAt(i));
        }
        return output;
    }

    private static ParseResult<SparseIntArray> parseExtensionSdk(
            ParseInput input, Resources res, XmlResourceParser parser,
            SparseIntArray minExtensionVersions) {
        int sdkVersion;
        int minVersion;
        TypedArray sa = res.obtainAttributes(parser, R.styleable.AndroidManifestExtensionSdk);
        try {
            sdkVersion = sa.getInt(R.styleable.AndroidManifestExtensionSdk_sdkVersion, -1);
            minVersion = sa.getInt(R.styleable.AndroidManifestExtensionSdk_minExtensionVersion, -1);
        } finally {
            sa.recycle();
        }

        if (sdkVersion < 0) {
            return input.error(
                    PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED,
                    "<extension-sdk> must specify an sdkVersion >= 0");
        }
        if (minVersion < 0) {
            return input.error(
                    PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED,
                    "<extension-sdk> must specify minExtensionVersion >= 0");
        }

        try {
            int version = SdkExtensions.getExtensionVersion(sdkVersion);
            if (version < minVersion) {
                return input.error(
                        PackageManager.INSTALL_FAILED_OLDER_SDK,
                        "Package requires " + sdkVersion + " extension version " + minVersion
                                + " which exceeds device version " + version);
            }
        } catch (RuntimeException e) {
            return input.error(
                    PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED,
                    "Specified sdkVersion " + sdkVersion + " is not valid");
        }
        minExtensionVersions.put(sdkVersion, minVersion);
        return input.success(minExtensionVersions);
    }

    /**
     * {@link ParseResult} version of
     * {@link PackageParser#computeMinSdkVersion(int, String, int, String[], String[])}
     */
    public static ParseResult<Integer> computeMinSdkVersion(@IntRange(from = 1) int minVers,
            @Nullable String minCode, @IntRange(from = 1) int platformSdkVersion,
            @NonNull String[] platformSdkCodenames, @NonNull ParseInput input) {
        // If it's a release SDK, make sure we meet the minimum SDK requirement.
        if (minCode == null) {
            if (minVers <= platformSdkVersion) {
                return input.success(minVers);
            }

            // We don't meet the minimum SDK requirement.
            return input.error(PackageManager.INSTALL_FAILED_OLDER_SDK,
                    "Requires newer sdk version #" + minVers
                            + " (current version is #" + platformSdkVersion + ")");
        }

        // If it's a pre-release SDK and the codename matches this platform, we
        // definitely meet the minimum SDK requirement.
        if (matchTargetCode(platformSdkCodenames, minCode)) {
            return input.success(Build.VERSION_CODES.CUR_DEVELOPMENT);
        }

        // Otherwise, we're looking at an incompatible pre-release SDK.
        if (platformSdkCodenames.length > 0) {
            return input.error(PackageManager.INSTALL_FAILED_OLDER_SDK,
                    "Requires development platform " + minCode
                            + " (current platform is any of "
                            + Arrays.toString(platformSdkCodenames) + ")");
        } else {
            return input.error(PackageManager.INSTALL_FAILED_OLDER_SDK,
                    "Requires development platform " + minCode
                            + " but this is a release platform.");
        }
    }

    /**
     * {@link ParseResult} version of
     * {@link PackageParser#computeTargetSdkVersion(int, String, String[], String[])}
     */
    public static ParseResult<Integer> computeTargetSdkVersion(@IntRange(from = 0) int targetVers,
            @Nullable String targetCode, @NonNull String[] platformSdkCodenames,
            @NonNull ParseInput input) {
        // If it's a release SDK, return the version number unmodified.
        if (targetCode == null) {
            return input.success(targetVers);
        }

        // If it's a pre-release SDK and the codename matches this platform, it
        // definitely targets this SDK.
        if (matchTargetCode(platformSdkCodenames, targetCode)) {
            return input.success(Build.VERSION_CODES.CUR_DEVELOPMENT);
        }

        // Otherwise, we're looking at an incompatible pre-release SDK.
        if (platformSdkCodenames.length > 0) {
            return input.error(PackageManager.INSTALL_FAILED_OLDER_SDK,
                    "Requires development platform " + targetCode
                            + " (current platform is any of "
                            + Arrays.toString(platformSdkCodenames) + ")");
        } else {
            return input.error(PackageManager.INSTALL_FAILED_OLDER_SDK,
                    "Requires development platform " + targetCode
                            + " but this is a release platform.");
        }
    }

    /**
     * Matches a given {@code targetCode} against a set of release codeNames. Target codes can
     * either be of the form {@code [codename]}" (e.g {@code "Q"}) or of the form
     * {@code [codename].[fingerprint]} (e.g {@code "Q.cafebc561"}).
     */
    private static boolean matchTargetCode(@NonNull String[] codeNames,
            @NonNull String targetCode) {
        final String targetCodeName;
        final int targetCodeIdx = targetCode.indexOf('.');
        if (targetCodeIdx == -1) {
            targetCodeName = targetCode;
        } else {
            targetCodeName = targetCode.substring(0, targetCodeIdx);
        }
        return ArrayUtils.contains(codeNames, targetCodeName);
    }

    private static ParseResult<ParsingPackage> parseRestrictUpdateHash(int flags, ParseInput input,
            ParsingPackage pkg, Resources res, XmlResourceParser parser) {
        if ((flags & PARSE_IS_SYSTEM_DIR) != 0) {
            TypedArray sa = res.obtainAttributes(parser, R.styleable.AndroidManifestRestrictUpdate);
            try {
                final String hash = sa.getNonConfigurationString(
                        R.styleable.AndroidManifestRestrictUpdate_hash,
                        0);

                if (hash != null) {
                    final int hashLength = hash.length();
                    final byte[] hashBytes = new byte[hashLength / 2];
                    for (int i = 0; i < hashLength; i += 2) {
                        hashBytes[i / 2] = (byte) ((Character.digit(hash.charAt(i), 16)
                                << 4)
                                + Character.digit(hash.charAt(i + 1), 16));
                    }
                    pkg.setRestrictUpdateHash(hashBytes);
                } else {
                    pkg.setRestrictUpdateHash(null);
                }
            } finally {
                sa.recycle();
            }
        }
        return input.success(pkg);
    }

    private static ParseResult<ParsingPackage> parseQueries(ParseInput input, ParsingPackage pkg,
            Resources res, XmlResourceParser parser) throws IOException, XmlPullParserException {
        final int depth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG
                || parser.getDepth() > depth)) {
            if (type != XmlPullParser.START_TAG) {
                continue;
            }
            if (parser.getName().equals("intent")) {
                ParseResult<ParsedIntentInfo> result = ParsedIntentInfoUtils.parseIntentInfo(null,
                        pkg, res, parser, true /*allowGlobs*/, true /*allowAutoVerify*/, input);
                if (result.isError()) {
                    return input.error(result);
                }

                ParsedIntentInfo intentInfo = result.getResult();

                Uri data = null;
                String dataType = null;
                String host = null;
                final int numActions = intentInfo.countActions();
                final int numSchemes = intentInfo.countDataSchemes();
                final int numTypes = intentInfo.countDataTypes();
                final int numHosts = intentInfo.getHosts().length;
                if ((numSchemes == 0 && numTypes == 0 && numActions == 0)) {
                    return input.error("intent tags must contain either an action or data.");
                }
                if (numActions > 1) {
                    return input.error("intent tag may have at most one action.");
                }
                if (numTypes > 1) {
                    return input.error("intent tag may have at most one data type.");
                }
                if (numSchemes > 1) {
                    return input.error("intent tag may have at most one data scheme.");
                }
                if (numHosts > 1) {
                    return input.error("intent tag may have at most one data host.");
                }
                Intent intent = new Intent();
                for (int i = 0, max = intentInfo.countCategories(); i < max; i++) {
                    intent.addCategory(intentInfo.getCategory(i));
                }
                if (numHosts == 1) {
                    host = intentInfo.getHosts()[0];
                }
                if (numSchemes == 1) {
                    data = new Uri.Builder()
                            .scheme(intentInfo.getDataScheme(0))
                            .authority(host)
                            .path(IntentFilter.WILDCARD_PATH)
                            .build();
                }
                if (numTypes == 1) {
                    dataType = intentInfo.getDataType(0);
                    // The dataType may have had the '/' removed for the dynamic mimeType feature.
                    // If we detect that case, we add the * back.
                    if (!dataType.contains("/")) {
                        dataType = dataType + "/*";
                    }
                    if (data == null) {
                        data = new Uri.Builder()
                                .scheme("content")
                                .authority(IntentFilter.WILDCARD)
                                .path(IntentFilter.WILDCARD_PATH)
                                .build();
                    }
                }
                intent.setDataAndType(data, dataType);
                if (numActions == 1) {
                    intent.setAction(intentInfo.getAction(0));
                }
                pkg.addQueriesIntent(intent);
            } else if (parser.getName().equals("package")) {
                final TypedArray sa = res.obtainAttributes(parser,
                        R.styleable.AndroidManifestQueriesPackage);
                final String packageName = sa.getNonConfigurationString(
                        R.styleable.AndroidManifestQueriesPackage_name, 0);
                if (TextUtils.isEmpty(packageName)) {
                    return input.error("Package name is missing from package tag.");
                }
                pkg.addQueriesPackage(packageName.intern());
            } else if (parser.getName().equals("provider")) {
                final TypedArray sa = res.obtainAttributes(parser,
                        R.styleable.AndroidManifestQueriesProvider);
                try {
                    final String authorities = sa.getNonConfigurationString(
                            R.styleable.AndroidManifestQueriesProvider_authorities, 0);
                    if (TextUtils.isEmpty(authorities)) {
                        return input.error(
                                PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED,
                                "Authority missing from provider tag."
                        );
                    }
                    StringTokenizer authoritiesTokenizer = new StringTokenizer(authorities, ";");
                    while (authoritiesTokenizer.hasMoreElements()) {
                        pkg.addQueriesProvider(authoritiesTokenizer.nextToken());
                    }
                } finally {
                    sa.recycle();
                }
            }
        }
        return input.success(pkg);
    }

    /**
     * Parse the {@code application} XML tree at the current parse location in a
     * <em>base APK</em> manifest.
     * <p>
     * When adding new features, carefully consider if they should also be
     * supported by split APKs.
     *
     * This method should avoid using a getter for fields set by this method. Prefer assigning
     * a local variable and using it. Otherwise there's an ordering problem which can be broken
     * if any code moves around.
     */
    private ParseResult<ParsingPackage> parseBaseApplication(ParseInput input,
            ParsingPackage pkg, Resources res, XmlResourceParser parser, int flags)
            throws XmlPullParserException, IOException {
        final String pkgName = pkg.getPackageName();
        int targetSdk = pkg.getTargetSdkVersion();

        TypedArray sa = res.obtainAttributes(parser, R.styleable.AndroidManifestApplication);
        try {
            // TODO(b/135203078): Remove this and force unit tests to mock an empty manifest
            // This case can only happen in unit tests where we sometimes need to create fakes
            // of various package parser data structures.
            if (sa == null) {
                return input.error("<application> does not contain any attributes");
            }

            String name = sa.getNonConfigurationString(R.styleable.AndroidManifestApplication_name,
                    0);
            if (name != null) {
                String packageName = pkg.getPackageName();
                String outInfoName = ParsingUtils.buildClassName(packageName, name);
                if (PackageManager.APP_DETAILS_ACTIVITY_CLASS_NAME.equals(outInfoName)) {
                    return input.error("<application> invalid android:name");
                } else if (outInfoName == null) {
                    return input.error("Empty class name in package " + packageName);
                }

                pkg.setClassName(outInfoName);
            }

            TypedValue labelValue = sa.peekValue(R.styleable.AndroidManifestApplication_label);
            if (labelValue != null) {
                pkg.setLabelRes(labelValue.resourceId);
                if (labelValue.resourceId == 0) {
                    pkg.setNonLocalizedLabel(labelValue.coerceToString());
                }
            }

            parseBaseAppBasicFlags(pkg, sa);

            String manageSpaceActivity = nonConfigString(Configuration.NATIVE_CONFIG_VERSION,
                    R.styleable.AndroidManifestApplication_manageSpaceActivity, sa);
            if (manageSpaceActivity != null) {
                String manageSpaceActivityName = ParsingUtils.buildClassName(pkgName,
                        manageSpaceActivity);

                if (manageSpaceActivityName == null) {
                    return input.error("Empty class name in package " + pkgName);
                }

                pkg.setManageSpaceActivityName(manageSpaceActivityName);
            }

            if (pkg.isAllowBackup()) {
                // backupAgent, killAfterRestore, fullBackupContent, backupInForeground,
                // and restoreAnyVersion are only relevant if backup is possible for the
                // given application.
                String backupAgent = nonConfigString(Configuration.NATIVE_CONFIG_VERSION,
                        R.styleable.AndroidManifestApplication_backupAgent, sa);
                if (backupAgent != null) {
                    String backupAgentName = ParsingUtils.buildClassName(pkgName, backupAgent);
                    if (backupAgentName == null) {
                        return input.error("Empty class name in package " + pkgName);
                    }

                    if (DEBUG_BACKUP) {
                        Slog.v(TAG, "android:backupAgent = " + backupAgentName
                                + " from " + pkgName + "+" + backupAgent);
                    }

                    pkg.setBackupAgentName(backupAgentName)
                            .setKillAfterRestore(bool(true,
                                    R.styleable.AndroidManifestApplication_killAfterRestore, sa))
                            .setRestoreAnyVersion(bool(false,
                                    R.styleable.AndroidManifestApplication_restoreAnyVersion, sa))
                            .setFullBackupOnly(bool(false,
                                    R.styleable.AndroidManifestApplication_fullBackupOnly, sa))
                            .setBackupInForeground(bool(false,
                                    R.styleable.AndroidManifestApplication_backupInForeground, sa));
                }

                TypedValue v = sa.peekValue(
                        R.styleable.AndroidManifestApplication_fullBackupContent);
                int fullBackupContent = 0;

                if (v != null) {
                    fullBackupContent = v.resourceId;

                    if (v.resourceId == 0) {
                        if (DEBUG_BACKUP) {
                            Slog.v(TAG, "fullBackupContent specified as boolean=" +
                                    (v.data == 0 ? "false" : "true"));
                        }
                        // "false" => -1, "true" => 0
                        fullBackupContent = v.data == 0 ? -1 : 0;
                    }

                    pkg.setFullBackupContent(fullBackupContent);
                }
                if (DEBUG_BACKUP) {
                    Slog.v(TAG, "fullBackupContent=" + fullBackupContent + " for " + pkgName);
                }
            }

            if (sa.getBoolean(R.styleable.AndroidManifestApplication_persistent, false)) {
                // Check if persistence is based on a feature being present
                final String requiredFeature = sa.getNonResourceString(R.styleable
                        .AndroidManifestApplication_persistentWhenFeatureAvailable);
                pkg.setPersistent(requiredFeature == null || mCallback.hasFeature(requiredFeature));
            }

            if (sa.hasValueOrEmpty(R.styleable.AndroidManifestApplication_resizeableActivity)) {
                pkg.setResizeableActivity(sa.getBoolean(
                        R.styleable.AndroidManifestApplication_resizeableActivity, true));
            } else {
                pkg.setResizeableActivityViaSdkVersion(
                        targetSdk >= Build.VERSION_CODES.N);
            }

            String taskAffinity;
            if (targetSdk >= Build.VERSION_CODES.FROYO) {
                taskAffinity = sa.getNonConfigurationString(
                        R.styleable.AndroidManifestApplication_taskAffinity,
                        Configuration.NATIVE_CONFIG_VERSION);
            } else {
                // Some older apps have been seen to use a resource reference
                // here that on older builds was ignored (with a warning).  We
                // need to continue to do this for them so they don't break.
                taskAffinity = sa.getNonResourceString(
                        R.styleable.AndroidManifestApplication_taskAffinity);
            }

            ParseResult<String> taskAffinityResult = ComponentParseUtils.buildTaskAffinityName(
                    pkgName, pkgName, taskAffinity, input);
            if (taskAffinityResult.isError()) {
                return input.error(taskAffinityResult);
            }

            pkg.setTaskAffinity(taskAffinityResult.getResult());
            String factory = sa.getNonResourceString(
                    R.styleable.AndroidManifestApplication_appComponentFactory);
            if (factory != null) {
                String appComponentFactory = ParsingUtils.buildClassName(pkgName, factory);
                if (appComponentFactory == null) {
                    return input.error("Empty class name in package " + pkgName);
                }

                pkg.setAppComponentFactory(appComponentFactory);
            }

            CharSequence pname;
            if (targetSdk >= Build.VERSION_CODES.FROYO) {
                pname = sa.getNonConfigurationString(
                        R.styleable.AndroidManifestApplication_process,
                        Configuration.NATIVE_CONFIG_VERSION);
            } else {
                // Some older apps have been seen to use a resource reference
                // here that on older builds was ignored (with a warning).  We
                // need to continue to do this for them so they don't break.
                pname = sa.getNonResourceString(
                        R.styleable.AndroidManifestApplication_process);
            }
            ParseResult<String> processNameResult = ComponentParseUtils.buildProcessName(
                    pkgName, null, pname, flags, mSeparateProcesses, input);
            if (processNameResult.isError()) {
                return input.error(processNameResult);
            }

            String processName = processNameResult.getResult();
            pkg.setProcessName(processName);

            if (pkg.isCantSaveState()) {
                // A heavy-weight application can not be in a custom process.
                // We can do direct compare because we intern all strings.
                if (processName != null && !processName.equals(pkgName)) {
                    return input.error(
                            "cantSaveState applications can not use custom processes");
                }
            }

            String classLoaderName = pkg.getClassLoaderName();
            if (classLoaderName != null
                    && !ClassLoaderFactory.isValidClassLoaderName(classLoaderName)) {
                return input.error("Invalid class loader name: " + classLoaderName);
            }

            pkg.setGwpAsanMode(sa.getInt(R.styleable.AndroidManifestApplication_gwpAsanMode, -1));
            pkg.setMemtagMode(sa.getInt(R.styleable.AndroidManifestApplication_memtagMode, -1));
            if (sa.hasValue(R.styleable.AndroidManifestApplication_nativeHeapZeroInitialized)) {
                Boolean v = sa.getBoolean(
                        R.styleable.AndroidManifestApplication_nativeHeapZeroInitialized, false);
                pkg.setNativeHeapZeroInitialized(
                        v ? ApplicationInfo.ZEROINIT_ENABLED : ApplicationInfo.ZEROINIT_DISABLED);
            }
            if (sa.hasValue(
                    R.styleable.AndroidManifestApplication_requestRawExternalStorageAccess)) {
                pkg.setRequestRawExternalStorageAccess(sa.getBoolean(R.styleable
                                .AndroidManifestApplication_requestRawExternalStorageAccess,
                        false));
            }
            if (sa.hasValue(
                    R.styleable.AndroidManifestApplication_requestForegroundServiceExemption)) {
                pkg.setRequestForegroundServiceExemption(sa.getBoolean(R.styleable
                                .AndroidManifestApplication_requestForegroundServiceExemption,
                        false));
            }
        } finally {
            sa.recycle();
        }

        boolean hasActivityOrder = false;
        boolean hasReceiverOrder = false;
        boolean hasServiceOrder = false;
        final int depth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG
                || parser.getDepth() > depth)) {
            if (type != XmlPullParser.START_TAG) {
                continue;
            }

            final ParseResult result;
            String tagName = parser.getName();
            boolean isActivity = false;
            switch (tagName) {
                case "activity":
                    isActivity = true;
                    // fall-through
                case "receiver":
                    ParseResult<ParsedActivity> activityResult =
                            ParsedActivityUtils.parseActivityOrReceiver(mSeparateProcesses, pkg,
                                    res, parser, flags, sUseRoundIcon, input);

                    if (activityResult.isSuccess()) {
                        ParsedActivity activity = activityResult.getResult();
                        if (isActivity) {
                            hasActivityOrder |= (activity.getOrder() != 0);
                            pkg.addActivity(activity);
                        } else {
                            hasReceiverOrder |= (activity.getOrder() != 0);
                            pkg.addReceiver(activity);
                        }
                    }

                    result = activityResult;
                    break;
                case "service":
                    ParseResult<ParsedService> serviceResult =
                            ParsedServiceUtils.parseService(mSeparateProcesses, pkg, res, parser,
                                    flags, sUseRoundIcon, input);
                    if (serviceResult.isSuccess()) {
                        ParsedService service = serviceResult.getResult();
                        hasServiceOrder |= (service.getOrder() != 0);
                        pkg.addService(service);
                    }

                    result = serviceResult;
                    break;
                case "provider":
                    ParseResult<ParsedProvider> providerResult =
                            ParsedProviderUtils.parseProvider(mSeparateProcesses, pkg, res, parser,
                                    flags, sUseRoundIcon, input);
                    if (providerResult.isSuccess()) {
                        pkg.addProvider(providerResult.getResult());
                    }

                    result = providerResult;
                    break;
                case "activity-alias":
                    activityResult = ParsedActivityUtils.parseActivityAlias(pkg, res,
                            parser, sUseRoundIcon, input);
                    if (activityResult.isSuccess()) {
                        ParsedActivity activity = activityResult.getResult();
                        hasActivityOrder |= (activity.getOrder() != 0);
                        pkg.addActivity(activity);
                    }

                    result = activityResult;
                    break;
                default:
                    result = parseBaseAppChildTag(input, tagName, pkg, res, parser, flags);
                    break;
            }

            if (result.isError()) {
                return input.error(result);
            }
        }

        if (TextUtils.isEmpty(pkg.getStaticSharedLibName())) {
            // Add a hidden app detail activity to normal apps which forwards user to App Details
            // page.
            ParseResult<ParsedActivity> a = generateAppDetailsHiddenActivity(input, pkg);
            if (a.isError()) {
                // Error should be impossible here, as the only failure case as of SDK R is a
                // string validation error on a constant ":app_details" string passed in by the
                // parsing code itself. For this reason, this is just a hard failure instead of
                // deferred.
                return input.error(a);
            }

            pkg.addActivity(a.getResult());
        }

        if (hasActivityOrder) {
            pkg.sortActivities();
        }
        if (hasReceiverOrder) {
            pkg.sortReceivers();
        }
        if (hasServiceOrder) {
            pkg.sortServices();
        }

        // Must be run after the entire {@link ApplicationInfo} has been fully processed and after
        // every activity info has had a chance to set it from its attributes.
        setMaxAspectRatio(pkg);
        setMinAspectRatio(pkg);
        setSupportsSizeChanges(pkg);

        pkg.setHasDomainUrls(hasDomainURLs(pkg));

        return input.success(pkg);
    }

    /**
     * Collection of single-line, no (or little) logic assignments. Separated for readability.
     *
     * Flags are separated by type and by default value. They are sorted alphabetically within each
     * section.
     */
    private void parseBaseAppBasicFlags(ParsingPackage pkg, TypedArray sa) {
        int targetSdk = pkg.getTargetSdkVersion();
        //@formatter:off
        // CHECKSTYLE:off
        pkg
                // Default true
                .setAllowBackup(bool(true, R.styleable.AndroidManifestApplication_allowBackup, sa))
                .setAllowClearUserData(bool(true, R.styleable.AndroidManifestApplication_allowClearUserData, sa))
                .setAllowClearUserDataOnFailedRestore(bool(true, R.styleable.AndroidManifestApplication_allowClearUserDataOnFailedRestore, sa))
                .setAllowNativeHeapPointerTagging(bool(true, R.styleable.AndroidManifestApplication_allowNativeHeapPointerTagging, sa))
                .setEnabled(bool(true, R.styleable.AndroidManifestApplication_enabled, sa))
                .setExtractNativeLibs(bool(true, R.styleable.AndroidManifestApplication_extractNativeLibs, sa))
                .setHasCode(bool(true, R.styleable.AndroidManifestApplication_hasCode, sa))
                // Default false
                .setAllowTaskReparenting(bool(false, R.styleable.AndroidManifestApplication_allowTaskReparenting, sa))
                .setCantSaveState(bool(false, R.styleable.AndroidManifestApplication_cantSaveState, sa))
                .setCrossProfile(bool(false, R.styleable.AndroidManifestApplication_crossProfile, sa))
                .setDebuggable(bool(false, R.styleable.AndroidManifestApplication_debuggable, sa))
                .setDefaultToDeviceProtectedStorage(bool(false, R.styleable.AndroidManifestApplication_defaultToDeviceProtectedStorage, sa))
                .setDirectBootAware(bool(false, R.styleable.AndroidManifestApplication_directBootAware, sa))
                .setForceQueryable(bool(false, R.styleable.AndroidManifestApplication_forceQueryable, sa))
                .setGame(bool(false, R.styleable.AndroidManifestApplication_isGame, sa))
                .setHasFragileUserData(bool(false, R.styleable.AndroidManifestApplication_hasFragileUserData, sa))
                .setLargeHeap(bool(false, R.styleable.AndroidManifestApplication_largeHeap, sa))
                .setMultiArch(bool(false, R.styleable.AndroidManifestApplication_multiArch, sa))
                .setPreserveLegacyExternalStorage(bool(false, R.styleable.AndroidManifestApplication_preserveLegacyExternalStorage, sa))
                .setRequiredForAllUsers(bool(false, R.styleable.AndroidManifestApplication_requiredForAllUsers, sa))
                .setSupportsRtl(bool(false, R.styleable.AndroidManifestApplication_supportsRtl, sa))
                .setTestOnly(bool(false, R.styleable.AndroidManifestApplication_testOnly, sa))
                .setUseEmbeddedDex(bool(false, R.styleable.AndroidManifestApplication_useEmbeddedDex, sa))
                .setUsesNonSdkApi(bool(false, R.styleable.AndroidManifestApplication_usesNonSdkApi, sa))
                .setVmSafeMode(bool(false, R.styleable.AndroidManifestApplication_vmSafeMode, sa))
                .setAutoRevokePermissions(anInt(R.styleable.AndroidManifestApplication_autoRevokePermissions, sa))
                .setAttributionsAreUserVisible(bool(false, R.styleable.AndroidManifestApplication_attributionsAreUserVisible, sa))
                // targetSdkVersion gated
                .setAllowAudioPlaybackCapture(bool(targetSdk >= Build.VERSION_CODES.Q, R.styleable.AndroidManifestApplication_allowAudioPlaybackCapture, sa))
                .setBaseHardwareAccelerated(bool(targetSdk >= Build.VERSION_CODES.ICE_CREAM_SANDWICH, R.styleable.AndroidManifestApplication_hardwareAccelerated, sa))
                .setRequestLegacyExternalStorage(bool(targetSdk < Build.VERSION_CODES.Q, R.styleable.AndroidManifestApplication_requestLegacyExternalStorage, sa))
                .setUsesCleartextTraffic(bool(targetSdk < Build.VERSION_CODES.P, R.styleable.AndroidManifestApplication_usesCleartextTraffic, sa))
                // Ints Default 0
                .setUiOptions(anInt(R.styleable.AndroidManifestApplication_uiOptions, sa))
                // Ints
                .setCategory(anInt(ApplicationInfo.CATEGORY_UNDEFINED, R.styleable.AndroidManifestApplication_appCategory, sa))
                // Floats Default 0f
                .setMaxAspectRatio(aFloat(R.styleable.AndroidManifestApplication_maxAspectRatio, sa))
                .setMinAspectRatio(aFloat(R.styleable.AndroidManifestApplication_minAspectRatio, sa))
                // Resource ID
                .setBanner(resId(R.styleable.AndroidManifestApplication_banner, sa))
                .setDescriptionRes(resId(R.styleable.AndroidManifestApplication_description, sa))
                .setIconRes(resId(R.styleable.AndroidManifestApplication_icon, sa))
                .setLogo(resId(R.styleable.AndroidManifestApplication_logo, sa))
                .setNetworkSecurityConfigRes(resId(R.styleable.AndroidManifestApplication_networkSecurityConfig, sa))
                .setRoundIconRes(resId(R.styleable.AndroidManifestApplication_roundIcon, sa))
                .setTheme(resId(R.styleable.AndroidManifestApplication_theme, sa))
                .setDataExtractionRules(
                        resId(R.styleable.AndroidManifestApplication_dataExtractionRules, sa))
                // Strings
                .setClassLoaderName(string(R.styleable.AndroidManifestApplication_classLoader, sa))
                .setRequiredAccountType(string(R.styleable.AndroidManifestApplication_requiredAccountType, sa))
                .setRestrictedAccountType(string(R.styleable.AndroidManifestApplication_restrictedAccountType, sa))
                .setZygotePreloadName(string(R.styleable.AndroidManifestApplication_zygotePreloadName, sa))
                // Non-Config String
                .setPermission(nonConfigString(0, R.styleable.AndroidManifestApplication_permission, sa));
        // CHECKSTYLE:on
        //@formatter:on
    }

    /**
     * For parsing non-MainComponents. Main ones have an order and some special handling which is
     * done directly in {@link #parseBaseApplication(ParseInput, ParsingPackage, Resources,
     * XmlResourceParser, int)}.
     */
    private ParseResult parseBaseAppChildTag(ParseInput input, String tag, ParsingPackage pkg,
            Resources res, XmlResourceParser parser, int flags)
            throws IOException, XmlPullParserException {
        switch (tag) {
            case "meta-data":
                // TODO(b/135203078): I have no idea what this comment means
                // note: application meta-data is stored off to the side, so it can
                // remain null in the primary copy (we like to avoid extra copies because
                // it can be large)
                final ParseResult<Property> metaDataResult = parseMetaData(pkg, null, res,
                        parser, "<meta-data>", input);
                if (metaDataResult.isSuccess() && metaDataResult.getResult() != null) {
                    pkg.setMetaData(metaDataResult.getResult().toBundle(pkg.getMetaData()));
                }
                return metaDataResult;
            case "property":
                final ParseResult<Property> propertyResult = parseMetaData(pkg, null, res,
                        parser, "<property>", input);
                if (propertyResult.isSuccess()) {
                    pkg.addProperty(propertyResult.getResult());
                }
                return propertyResult;
            case "static-library":
                return parseStaticLibrary(pkg, res, parser, input);
            case "library":
                return parseLibrary(pkg, res, parser, input);
            case "uses-static-library":
                return parseUsesStaticLibrary(input, pkg, res, parser);
            case "uses-library":
                return parseUsesLibrary(input, pkg, res, parser);
            case "uses-native-library":
                return parseUsesNativeLibrary(input, pkg, res, parser);
            case "processes":
                return parseProcesses(input, pkg, res, parser, mSeparateProcesses, flags);
            case "uses-package":
                // Dependencies for app installers; we don't currently try to
                // enforce this.
                return input.success(null);
            case "profileable":
                return parseProfileable(input, pkg, res, parser);
            default:
                return ParsingUtils.unknownTag("<application>", pkg, parser, input);
        }
    }

    @NonNull
    private static ParseResult<ParsingPackage> parseStaticLibrary(
            ParsingPackage pkg, Resources res,
            XmlResourceParser parser, ParseInput input) {
        TypedArray sa = res.obtainAttributes(parser, R.styleable.AndroidManifestStaticLibrary);
        try {
            // Note: don't allow this value to be a reference to a resource
            // that may change.
            String lname = sa.getNonResourceString(
                    R.styleable.AndroidManifestStaticLibrary_name);
            final int version = sa.getInt(
                    R.styleable.AndroidManifestStaticLibrary_version, -1);
            final int versionMajor = sa.getInt(
                    R.styleable.AndroidManifestStaticLibrary_versionMajor,
                    0);

            // Since the app canot run without a static lib - fail if malformed
            if (lname == null || version < 0) {
                return input.error("Bad static-library declaration name: " + lname
                        + " version: " + version);
            } else if (pkg.getSharedUserId() != null) {
                return input.error(
                        PackageManager.INSTALL_PARSE_FAILED_BAD_SHARED_USER_ID,
                        "sharedUserId not allowed in static shared library"
                );
            } else if (pkg.getStaticSharedLibName() != null) {
                return input.error("Multiple static-shared libs for package "
                        + pkg.getPackageName());
            }

            return input.success(pkg.setStaticSharedLibName(lname.intern())
                    .setStaticSharedLibVersion(
                            PackageInfo.composeLongVersionCode(versionMajor, version))
                    .setStaticSharedLibrary(true));
        } finally {
            sa.recycle();
        }
    }

    @NonNull
    private static ParseResult<ParsingPackage> parseLibrary(
            ParsingPackage pkg, Resources res,
            XmlResourceParser parser, ParseInput input) {
        TypedArray sa = res.obtainAttributes(parser, R.styleable.AndroidManifestLibrary);
        try {
            // Note: don't allow this value to be a reference to a resource
            // that may change.
            String lname = sa.getNonResourceString(R.styleable.AndroidManifestLibrary_name);

            if (lname != null) {
                lname = lname.intern();
                if (!ArrayUtils.contains(pkg.getLibraryNames(), lname)) {
                    pkg.addLibraryName(lname);
                }
            }
            return input.success(pkg);
        } finally {
            sa.recycle();
        }
    }

    @NonNull
    private static ParseResult<ParsingPackage> parseUsesStaticLibrary(ParseInput input,
            ParsingPackage pkg, Resources res, XmlResourceParser parser)
            throws XmlPullParserException, IOException {
        TypedArray sa = res.obtainAttributes(parser, R.styleable.AndroidManifestUsesStaticLibrary);
        try {
            // Note: don't allow this value to be a reference to a resource that may change.
            String lname = sa.getNonResourceString(
                    R.styleable.AndroidManifestUsesLibrary_name);
            final int version = sa.getInt(
                    R.styleable.AndroidManifestUsesStaticLibrary_version, -1);
            String certSha256Digest = sa.getNonResourceString(R.styleable
                    .AndroidManifestUsesStaticLibrary_certDigest);

            // Since an APK providing a static shared lib can only provide the lib - fail if
            // malformed
            if (lname == null || version < 0 || certSha256Digest == null) {
                return input.error("Bad uses-static-library declaration name: " + lname
                        + " version: " + version + " certDigest" + certSha256Digest);
            }

            // Can depend only on one version of the same library
            List<String> usesStaticLibraries = pkg.getUsesStaticLibraries();
            if (usesStaticLibraries.contains(lname)) {
                return input.error(
                        "Depending on multiple versions of static library " + lname);
            }

            lname = lname.intern();
            // We allow ":" delimiters in the SHA declaration as this is the format
            // emitted by the certtool making it easy for developers to copy/paste.
            certSha256Digest = certSha256Digest.replace(":", "").toLowerCase();

            // Fot apps targeting O-MR1 we require explicit enumeration of all certs.
            String[] additionalCertSha256Digests = EmptyArray.STRING;
            if (pkg.getTargetSdkVersion() >= Build.VERSION_CODES.O_MR1) {
                ParseResult<String[]> certResult = parseAdditionalCertificates(input, res, parser);
                if (certResult.isError()) {
                    return input.error(certResult);
                }
                additionalCertSha256Digests = certResult.getResult();
            }

            final String[] certSha256Digests = new String[additionalCertSha256Digests.length + 1];
            certSha256Digests[0] = certSha256Digest;
            System.arraycopy(additionalCertSha256Digests, 0, certSha256Digests,
                    1, additionalCertSha256Digests.length);

            return input.success(pkg.addUsesStaticLibrary(lname)
                    .addUsesStaticLibraryVersion(version)
                    .addUsesStaticLibraryCertDigests(certSha256Digests));
        } finally {
            sa.recycle();
        }
    }

    @NonNull
    private static ParseResult<ParsingPackage> parseUsesLibrary(ParseInput input,
            ParsingPackage pkg, Resources res, XmlResourceParser parser) {
        TypedArray sa = res.obtainAttributes(parser, R.styleable.AndroidManifestUsesLibrary);
        try {
            // Note: don't allow this value to be a reference to a resource
            // that may change.
            String lname = sa.getNonResourceString(R.styleable.AndroidManifestUsesLibrary_name);
            boolean req = sa.getBoolean(R.styleable.AndroidManifestUsesLibrary_required, true);

            if (lname != null) {
                lname = lname.intern();
                if (req) {
                    // Upgrade to treat as stronger constraint
                    pkg.addUsesLibrary(lname)
                            .removeUsesOptionalLibrary(lname);
                } else {
                    // Ignore if someone already defined as required
                    if (!ArrayUtils.contains(pkg.getUsesLibraries(), lname)) {
                        pkg.addUsesOptionalLibrary(lname);
                    }
                }
            }

            return input.success(pkg);
        } finally {
            sa.recycle();
        }
    }

    @NonNull
    private static ParseResult<ParsingPackage> parseUsesNativeLibrary(ParseInput input,
            ParsingPackage pkg, Resources res, XmlResourceParser parser) {
        TypedArray sa = res.obtainAttributes(parser, R.styleable.AndroidManifestUsesNativeLibrary);
        try {
            // Note: don't allow this value to be a reference to a resource
            // that may change.
            String lname = sa.getNonResourceString(
                    R.styleable.AndroidManifestUsesNativeLibrary_name);
            boolean req = sa.getBoolean(R.styleable.AndroidManifestUsesNativeLibrary_required,
                    true);

            if (lname != null) {
                if (req) {
                    // Upgrade to treat as stronger constraint
                    pkg.addUsesNativeLibrary(lname)
                            .removeUsesOptionalNativeLibrary(lname);
                } else {
                    // Ignore if someone already defined as required
                    if (!ArrayUtils.contains(pkg.getUsesNativeLibraries(), lname)) {
                        pkg.addUsesOptionalNativeLibrary(lname);
                    }
                }
            }

            return input.success(pkg);
        } finally {
            sa.recycle();
        }
    }

    @NonNull
    private static ParseResult<ParsingPackage> parseProcesses(ParseInput input, ParsingPackage pkg,
            Resources res, XmlResourceParser parser, String[] separateProcesses, int flags)
            throws IOException, XmlPullParserException {
        ParseResult<ArrayMap<String, ParsedProcess>> result =
                ParsedProcessUtils.parseProcesses(separateProcesses, pkg, res, parser, flags,
                        input);
        if (result.isError()) {
            return input.error(result);
        }

        return input.success(pkg.setProcesses(result.getResult()));
    }

    @NonNull
    private static ParseResult<ParsingPackage> parseProfileable(ParseInput input,
            ParsingPackage pkg, Resources res, XmlResourceParser parser) {
        TypedArray sa = res.obtainAttributes(parser, R.styleable.AndroidManifestProfileable);
        try {
            ParsingPackage newPkg = pkg.setProfileableByShell(pkg.isProfileableByShell()
                    || bool(false, R.styleable.AndroidManifestProfileable_shell, sa));
            return input.success(newPkg.setProfileable(newPkg.isProfileable()
                    && bool(true, R.styleable.AndroidManifestProfileable_enabled, sa)));
        } finally {
            sa.recycle();
        }
    }

    private static ParseResult<String[]> parseAdditionalCertificates(ParseInput input,
            Resources resources, XmlResourceParser parser)
            throws XmlPullParserException, IOException {
        String[] certSha256Digests = EmptyArray.STRING;
        final int depth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG
                || parser.getDepth() > depth)) {
            if (type != XmlPullParser.START_TAG) {
                continue;
            }

            final String nodeName = parser.getName();
            if (nodeName.equals("additional-certificate")) {
                TypedArray sa = resources.obtainAttributes(parser,
                        R.styleable.AndroidManifestAdditionalCertificate);
                try {
                    String certSha256Digest = sa.getNonResourceString(
                            R.styleable.AndroidManifestAdditionalCertificate_certDigest);

                    if (TextUtils.isEmpty(certSha256Digest)) {
                        return input.error("Bad additional-certificate declaration with empty"
                                + " certDigest:" + certSha256Digest);
                    }


                    // We allow ":" delimiters in the SHA declaration as this is the format
                    // emitted by the certtool making it easy for developers to copy/paste.
                    certSha256Digest = certSha256Digest.replace(":", "").toLowerCase();
                    certSha256Digests = ArrayUtils.appendElement(String.class,
                            certSha256Digests, certSha256Digest);
                } finally {
                    sa.recycle();
                }
            }
        }

        return input.success(certSha256Digests);
    }

    /**
     * Generate activity object that forwards user to App Details page automatically.
     * This activity should be invisible to user and user should not know or see it.
     */
    @NonNull
    private static ParseResult<ParsedActivity> generateAppDetailsHiddenActivity(ParseInput input,
            ParsingPackage pkg) {
        String packageName = pkg.getPackageName();
        ParseResult<String> result = ComponentParseUtils.buildTaskAffinityName(
                packageName, packageName, ":app_details", input);
        if (result.isError()) {
            return input.error(result);
        }

        String taskAffinity = result.getResult();

        // Build custom App Details activity info instead of parsing it from xml
        return input.success(ParsedActivity.makeAppDetailsActivity(packageName,
                pkg.getProcessName(), pkg.getUiOptions(), taskAffinity,
                pkg.isBaseHardwareAccelerated()));
    }

    /**
     * Check if one of the IntentFilter as both actions DEFAULT / VIEW and a HTTP/HTTPS data URI
     *
     * This is distinct from any of the functionality of app links domain verification, and cannot
     * be converted to remain backwards compatible. It's possible the presence of this flag does
     * not indicate a valid package for domain verification.
     */
    private static boolean hasDomainURLs(ParsingPackage pkg) {
        final List<ParsedActivity> activities = pkg.getActivities();
        final int activitiesSize = activities.size();
        for (int index = 0; index < activitiesSize; index++) {
            ParsedActivity activity = activities.get(index);
            List<ParsedIntentInfo> filters = activity.getIntents();
            final int filtersSize = filters.size();
            for (int filtersIndex = 0; filtersIndex < filtersSize; filtersIndex++) {
                ParsedIntentInfo aii = filters.get(filtersIndex);
                if (!aii.hasAction(Intent.ACTION_VIEW)) continue;
                if (!aii.hasAction(Intent.ACTION_DEFAULT)) continue;
                if (aii.hasDataScheme(IntentFilter.SCHEME_HTTP) ||
                        aii.hasDataScheme(IntentFilter.SCHEME_HTTPS)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Sets the max aspect ratio of every child activity that doesn't already have an aspect
     * ratio set.
     */
    private static void setMaxAspectRatio(ParsingPackage pkg) {
        // Default to (1.86) 16.7:9 aspect ratio for pre-O apps and unset for O and greater.
        // NOTE: 16.7:9 was the max aspect ratio Android devices can support pre-O per the CDD.
        float maxAspectRatio = pkg.getTargetSdkVersion() < O ? DEFAULT_PRE_O_MAX_ASPECT_RATIO : 0;

        float packageMaxAspectRatio = pkg.getMaxAspectRatio();
        if (packageMaxAspectRatio != 0) {
            // Use the application max aspect ration as default if set.
            maxAspectRatio = packageMaxAspectRatio;
        } else {
            Bundle appMetaData = pkg.getMetaData();
            if (appMetaData != null && appMetaData.containsKey(METADATA_MAX_ASPECT_RATIO)) {
                maxAspectRatio = appMetaData.getFloat(METADATA_MAX_ASPECT_RATIO, maxAspectRatio);
            }
        }

        List<ParsedActivity> activities = pkg.getActivities();
        int activitiesSize = activities.size();
        for (int index = 0; index < activitiesSize; index++) {
            ParsedActivity activity = activities.get(index);
            // If the max aspect ratio for the activity has already been set, skip.
            if (activity.getMaxAspectRatio() != null) {
                continue;
            }

            // By default we prefer to use a values defined on the activity directly than values
            // defined on the application. We do not check the styled attributes on the activity
            // as it would have already been set when we processed the activity. We wait to
            // process the meta data here since this method is called at the end of processing
            // the application and all meta data is guaranteed.
            final float activityAspectRatio = activity.getMetaData() != null
                    ? activity.getMetaData().getFloat(METADATA_MAX_ASPECT_RATIO, maxAspectRatio)
                    : maxAspectRatio;

            activity.setMaxAspectRatio(activity.getResizeMode(), activityAspectRatio);
        }
    }

    /**
     * Sets the min aspect ratio of every child activity that doesn't already have an aspect
     * ratio set.
     */
    private void setMinAspectRatio(ParsingPackage pkg) {
        // Use the application max aspect ration as default if set.
        final float minAspectRatio = pkg.getMinAspectRatio();

        List<ParsedActivity> activities = pkg.getActivities();
        int activitiesSize = activities.size();
        for (int index = 0; index < activitiesSize; index++) {
            ParsedActivity activity = activities.get(index);
            if (activity.getMinAspectRatio() == null) {
                activity.setMinAspectRatio(activity.getResizeMode(), minAspectRatio);
            }
        }
    }

    private void setSupportsSizeChanges(ParsingPackage pkg) {
        final Bundle appMetaData = pkg.getMetaData();
        final boolean supportsSizeChanges = appMetaData != null
                && appMetaData.getBoolean(METADATA_SUPPORTS_SIZE_CHANGES, false);

        List<ParsedActivity> activities = pkg.getActivities();
        int activitiesSize = activities.size();
        for (int index = 0; index < activitiesSize; index++) {
            ParsedActivity activity = activities.get(index);
            if (supportsSizeChanges || (activity.getMetaData() != null
                    && activity.getMetaData().getBoolean(
                            METADATA_SUPPORTS_SIZE_CHANGES, false))) {
                activity.setSupportsSizeChanges(true);
            }
        }
    }

    private static ParseResult<ParsingPackage> parseOverlay(ParseInput input, ParsingPackage pkg,
            Resources res, XmlResourceParser parser) {
        TypedArray sa = res.obtainAttributes(parser, R.styleable.AndroidManifestResourceOverlay);
        try {
            String target = sa.getString(R.styleable.AndroidManifestResourceOverlay_targetPackage);
            int priority = anInt(0, R.styleable.AndroidManifestResourceOverlay_priority, sa);

            if (target == null) {
                return input.error("<overlay> does not specify a target package");
            } else if (priority < 0 || priority > 9999) {
                return input.error("<overlay> priority must be between 0 and 9999");
            }

            // check to see if overlay should be excluded based on system property condition
            String propName = sa.getString(
                    R.styleable.AndroidManifestResourceOverlay_requiredSystemPropertyName);
            String propValue = sa.getString(
                    R.styleable.AndroidManifestResourceOverlay_requiredSystemPropertyValue);
            if (!PackageParser.checkRequiredSystemProperties(propName, propValue)) {
                String message = "Skipping target and overlay pair " + target + " and "
                        + pkg.getBaseApkPath()
                        + ": overlay ignored due to required system property: "
                        + propName + " with value: " + propValue;
                Slog.i(TAG, message);
                return input.skip(message);
            }

            return input.success(pkg.setOverlay(true)
                    .setOverlayTarget(target)
                    .setOverlayPriority(priority)
                    .setOverlayTargetName(
                            sa.getString(R.styleable.AndroidManifestResourceOverlay_targetName))
                    .setOverlayCategory(
                            sa.getString(R.styleable.AndroidManifestResourceOverlay_category))
                    .setOverlayIsStatic(
                            bool(false, R.styleable.AndroidManifestResourceOverlay_isStatic, sa)));
        } finally {
            sa.recycle();
        }
    }

    private static ParseResult<ParsingPackage> parseProtectedBroadcast(ParseInput input,
            ParsingPackage pkg, Resources res, XmlResourceParser parser) {
        TypedArray sa = res.obtainAttributes(parser, R.styleable.AndroidManifestProtectedBroadcast);
        try {
            // Note: don't allow this value to be a reference to a resource
            // that may change.
            String name = nonResString(R.styleable.AndroidManifestProtectedBroadcast_name, sa);
            if (name != null) {
                pkg.addProtectedBroadcast(name);
            }
            return input.success(pkg);
        } finally {
            sa.recycle();
        }
    }

    private static ParseResult<ParsingPackage> parseSupportScreens(ParseInput input,
            ParsingPackage pkg, Resources res, XmlResourceParser parser) {
        TypedArray sa = res.obtainAttributes(parser, R.styleable.AndroidManifestSupportsScreens);
        try {
            int requiresSmallestWidthDp = anInt(0,
                    R.styleable.AndroidManifestSupportsScreens_requiresSmallestWidthDp, sa);
            int compatibleWidthLimitDp = anInt(0,
                    R.styleable.AndroidManifestSupportsScreens_compatibleWidthLimitDp, sa);
            int largestWidthLimitDp = anInt(0,
                    R.styleable.AndroidManifestSupportsScreens_largestWidthLimitDp, sa);

            // This is a trick to get a boolean and still able to detect
            // if a value was actually set.
            return input.success(pkg
                    .setSupportsSmallScreens(
                            anInt(1, R.styleable.AndroidManifestSupportsScreens_smallScreens, sa))
                    .setSupportsNormalScreens(
                            anInt(1, R.styleable.AndroidManifestSupportsScreens_normalScreens, sa))
                    .setSupportsLargeScreens(
                            anInt(1, R.styleable.AndroidManifestSupportsScreens_largeScreens, sa))
                    .setSupportsExtraLargeScreens(
                            anInt(1, R.styleable.AndroidManifestSupportsScreens_xlargeScreens, sa))
                    .setResizeable(
                            anInt(1, R.styleable.AndroidManifestSupportsScreens_resizeable, sa))
                    .setAnyDensity(
                            anInt(1, R.styleable.AndroidManifestSupportsScreens_anyDensity, sa))
                    .setRequiresSmallestWidthDp(requiresSmallestWidthDp)
                    .setCompatibleWidthLimitDp(compatibleWidthLimitDp)
                    .setLargestWidthLimitDp(largestWidthLimitDp));
        } finally {
            sa.recycle();
        }
    }

    private static ParseResult<ParsingPackage> parseInstrumentation(ParseInput input,
            ParsingPackage pkg, Resources res, XmlResourceParser parser)
            throws XmlPullParserException, IOException {
        ParseResult<ParsedInstrumentation> result = ParsedInstrumentationUtils.parseInstrumentation(
                pkg, res, parser, sUseRoundIcon, input);
        if (result.isError()) {
            return input.error(result);
        }
        return input.success(pkg.addInstrumentation(result.getResult()));
    }

    private static ParseResult<ParsingPackage> parseOriginalPackage(ParseInput input,
            ParsingPackage pkg, Resources res, XmlResourceParser parser) {
        TypedArray sa = res.obtainAttributes(parser, R.styleable.AndroidManifestOriginalPackage);
        try {
            String orig = sa.getNonConfigurationString(
                    R.styleable.AndroidManifestOriginalPackage_name,
                    0);
            if (!pkg.getPackageName().equals(orig)) {
                if (pkg.getOriginalPackages().isEmpty()) {
                    pkg.setRealPackage(pkg.getPackageName());
                }
                pkg.addOriginalPackage(orig);
            }
            return input.success(pkg);
        } finally {
            sa.recycle();
        }
    }

    private static ParseResult<ParsingPackage> parseAdoptPermissions(ParseInput input,
            ParsingPackage pkg, Resources res, XmlResourceParser parser) {
        TypedArray sa = res.obtainAttributes(parser, R.styleable.AndroidManifestOriginalPackage);
        try {
            String name = nonConfigString(0, R.styleable.AndroidManifestOriginalPackage_name, sa);
            if (name != null) {
                pkg.addAdoptPermission(name);
            }
            return input.success(pkg);
        } finally {
            sa.recycle();
        }
    }

    private static void convertNewPermissions(ParsingPackage pkg) {
        final int NP = PackageParser.NEW_PERMISSIONS.length;
        StringBuilder newPermsMsg = null;
        for (int ip = 0; ip < NP; ip++) {
            final PackageParser.NewPermissionInfo npi
                    = PackageParser.NEW_PERMISSIONS[ip];
            if (pkg.getTargetSdkVersion() >= npi.sdkVersion) {
                break;
            }
            if (!pkg.getRequestedPermissions().contains(npi.name)) {
                if (newPermsMsg == null) {
                    newPermsMsg = new StringBuilder(128);
                    newPermsMsg.append(pkg.getPackageName());
                    newPermsMsg.append(": compat added ");
                } else {
                    newPermsMsg.append(' ');
                }
                newPermsMsg.append(npi.name);
                pkg.addUsesPermission(new ParsedUsesPermission(npi.name, 0))
                        .addImplicitPermission(npi.name);
            }
        }
        if (newPermsMsg != null) {
            Slog.i(TAG, newPermsMsg.toString());
        }
    }

    private void convertSplitPermissions(ParsingPackage pkg) {
        final int listSize = mSplitPermissionInfos.size();
        for (int is = 0; is < listSize; is++) {
            final PermissionManager.SplitPermissionInfo spi = mSplitPermissionInfos.get(is);
            List<String> requestedPermissions = pkg.getRequestedPermissions();
            if (pkg.getTargetSdkVersion() >= spi.getTargetSdk()
                    || !requestedPermissions.contains(spi.getSplitPermission())) {
                continue;
            }
            final List<String> newPerms = spi.getNewPermissions();
            for (int in = 0; in < newPerms.size(); in++) {
                final String perm = newPerms.get(in);
                if (!requestedPermissions.contains(perm)) {
                    pkg.addUsesPermission(new ParsedUsesPermission(perm, 0))
                            .addImplicitPermission(perm);
                }
            }
        }
    }

    /**
     * This is a pre-density application which will get scaled - instead of being pixel perfect.
     * This type of application is not resizable.
     *
     * @param pkg The package which needs to be marked as unresizable.
     */
    private static void adjustPackageToBeUnresizeableAndUnpipable(ParsingPackage pkg) {
        List<ParsedActivity> activities = pkg.getActivities();
        int activitiesSize = activities.size();
        for (int index = 0; index < activitiesSize; index++) {
            ParsedActivity activity = activities.get(index);
            activity.setResizeMode(RESIZE_MODE_UNRESIZEABLE)
                    .setFlags(activity.getFlags() & ~FLAG_SUPPORTS_PICTURE_IN_PICTURE);
        }
    }

    /**
     * Check if the given name is valid.
     *
     * @param name The name to check.
     * @param requireSeparator {@code true} if the name requires containing a separator at least.
     * @param requireFilename {@code true} to apply file name validation to the given name. It also
     *                        limits length of the name to the {@link #MAX_FILE_NAME_SIZE}.
     * @return Success if it's valid.
     */
    public static String validateName(String name, boolean requireSeparator,
            boolean requireFilename) {
        final int N = name.length();
        boolean hasSep = false;
        boolean front = true;
        for (int i = 0; i < N; i++) {
            final char c = name.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) {
                front = false;
                continue;
            }
            if (!front) {
                if ((c >= '0' && c <= '9') || c == '_') {
                    continue;
                }
            }
            if (c == '.') {
                hasSep = true;
                front = true;
                continue;
            }
            return "bad character '" + c + "'";
        }
        if (requireFilename) {
            if (!FileUtils.isValidExtFilename(name)) {
                return "Invalid filename";
            } else if (N > MAX_FILE_NAME_SIZE) {
                return "the length of the name is greater than " + MAX_FILE_NAME_SIZE;
            }
        }
        return hasSep || !requireSeparator ? null : "must have at least one '.' separator";
    }

    /**
     * @see #validateName(String, boolean, boolean)
     */
    public static ParseResult validateName(ParseInput input, String name, boolean requireSeparator,
            boolean requireFilename) {
        final String errorMessage = validateName(name, requireSeparator, requireFilename);
        if (errorMessage != null) {
            return input.error(errorMessage);
        }
        return input.success(null);
    }

    /**
     * Parse a meta data defined on the enclosing tag.
     * <p>Meta data can be defined by either &lt;meta-data&gt; or &lt;property&gt; elements.
     */
    public static ParseResult<Property> parseMetaData(ParsingPackage pkg, ParsedComponent component,
            Resources res, XmlResourceParser parser, String tagName, ParseInput input) {
        TypedArray sa = res.obtainAttributes(parser, R.styleable.AndroidManifestMetaData);
        try {
            final Property property;
            final String name = TextUtils.safeIntern(
                    nonConfigString(0, R.styleable.AndroidManifestMetaData_name, sa));
            if (name == null) {
                return input.error(tagName + " requires an android:name attribute");
            }

            final String packageName = pkg.getPackageName();
            final String className = component != null ? component.getName() : null;
            TypedValue v = sa.peekValue(R.styleable.AndroidManifestMetaData_resource);
            if (v != null && v.resourceId != 0) {
                property = new Property(name, v.resourceId, true, packageName, className);
            } else {
                v = sa.peekValue(R.styleable.AndroidManifestMetaData_value);
                if (v != null) {
                    if (v.type == TypedValue.TYPE_STRING) {
                        final CharSequence cs = v.coerceToString();
                        final String stringValue = cs != null ? cs.toString() : null;
                        property = new Property(name, stringValue, packageName, className);
                    } else if (v.type == TypedValue.TYPE_INT_BOOLEAN) {
                        property = new Property(name, v.data != 0, packageName, className);
                    } else if (v.type >= TypedValue.TYPE_FIRST_INT
                            && v.type <= TypedValue.TYPE_LAST_INT) {
                        property = new Property(name, v.data, false, packageName, className);
                    } else if (v.type == TypedValue.TYPE_FLOAT) {
                        property = new Property(name, v.getFloat(), packageName, className);
                    } else {
                        if (!RIGID_PARSER) {
                            Slog.w(TAG,
                                    tagName + " only supports string, integer, float, color, "
                                            + "boolean, and resource reference types: "
                                            + parser.getName() + " at "
                                            + pkg.getBaseApkPath() + " "
                                            + parser.getPositionDescription());
                            property = null;
                        } else {
                            return input.error(tagName + " only supports string, integer, float, "
                                    + "color, boolean, and resource reference types");
                        }
                    }
                } else {
                    return input.error(tagName + " requires an android:value "
                            + "or android:resource attribute");
                }
            }
            return input.success(property);
        } finally {
            sa.recycle();
        }
    }

    /**
     * Collect certificates from all the APKs described in the given package. Also asserts that
     * all APK contents are signed correctly and consistently.
     *
     * TODO(b/155513789): Remove this in favor of collecting certificates during the original parse
     *  call if requested. Leaving this as an optional method for the caller means we have to
     *  construct a dummy ParseInput.
     */
    @CheckResult
    public static SigningDetails getSigningDetails(ParsingPackageRead pkg, boolean skipVerify)
            throws PackageParserException {
        SigningDetails signingDetails = SigningDetails.UNKNOWN;

        ParseInput input = ParseTypeImpl.forDefaultParsing().reset();

        Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "collectCertificates");
        try {
            ParseResult<SigningDetails> result = getSigningDetails(
                    input,
                    pkg.getBaseApkPath(),
                    skipVerify,
                    pkg.isStaticSharedLibrary(),
                    signingDetails,
                    pkg.getTargetSdkVersion()
            );
            if (result.isError()) {
                throw new PackageParser.PackageParserException(result.getErrorCode(),
                        result.getErrorMessage(), result.getException());
            }

            signingDetails = result.getResult();

            String[] splitCodePaths = pkg.getSplitCodePaths();
            if (!ArrayUtils.isEmpty(splitCodePaths)) {
                for (int i = 0; i < splitCodePaths.length; i++) {
                    result = getSigningDetails(
                            input,
                            splitCodePaths[i],
                            skipVerify,
                            pkg.isStaticSharedLibrary(),
                            signingDetails,
                            pkg.getTargetSdkVersion()
                    );
                    if (result.isError()) {
                        throw new PackageParser.PackageParserException(result.getErrorCode(),
                                result.getErrorMessage(), result.getException());
                    }


                    signingDetails = result.getResult();
                }
            }
            return signingDetails;
        } finally {
            Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
        }
    }

    @CheckResult
    public static ParseResult<SigningDetails> getSigningDetails(ParseInput input,
            String baseCodePath, boolean skipVerify, boolean isStaticSharedLibrary,
            @NonNull SigningDetails existingSigningDetails, int targetSdk) {
        int minSignatureScheme = ApkSignatureVerifier.getMinimumSignatureSchemeVersionForTargetSdk(
                targetSdk);
        if (isStaticSharedLibrary) {
            // must use v2 signing scheme
            minSignatureScheme = SigningDetails.SignatureSchemeVersion.SIGNING_BLOCK_V2;
        }
        SigningDetails verified;
        try {
            if (skipVerify) {
                // systemDir APKs are already trusted, save time by not verifying; since the
                // signature is not verified and some system apps can have their V2+ signatures
                // stripped allow pulling the certs from the jar signature.
                verified = ApkSignatureVerifier.unsafeGetCertsWithoutVerification(
                        baseCodePath, SigningDetails.SignatureSchemeVersion.JAR);
            } else {
                verified = ApkSignatureVerifier.verify(baseCodePath, minSignatureScheme);
            }
        } catch (PackageParserException e) {
            return input.error(PackageManager.INSTALL_PARSE_FAILED_NO_CERTIFICATES,
                    "Failed collecting certificates for " + baseCodePath, e);
        }

        // Verify that entries are signed consistently with the first pkg
        // we encountered. Note that for splits, certificates may have
        // already been populated during an earlier parse of a base APK.
        if (existingSigningDetails == SigningDetails.UNKNOWN) {
            return input.success(verified);
        } else {
            if (!Signature.areExactMatch(existingSigningDetails.signatures, verified.signatures)) {
                return input.error(INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES,
                        baseCodePath + " has mismatched certificates");
            }

            return input.success(existingSigningDetails);
        }
    }

    /**
     * @hide
     */
    public static void readConfigUseRoundIcon(Resources r) {
        if (r != null) {
            sUseRoundIcon = r.getBoolean(com.android.internal.R.bool.config_useRoundIcon);
            return;
        }

        final ApplicationInfo androidAppInfo;
        try {
            androidAppInfo = ActivityThread.getPackageManager().getApplicationInfo(
                    "android", 0 /* flags */,
                    UserHandle.myUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
        final Resources systemResources = Resources.getSystem();

        // Create in-flight as this overlayable resource is only used when config changes
        final Resources overlayableRes = ResourcesManager.getInstance().getResources(
                null /* activityToken */,
                null /* resDir */,
                null /* splitResDirs */,
                androidAppInfo.resourceDirs,
                androidAppInfo.overlayPaths,
                androidAppInfo.sharedLibraryFiles,
                null /* overrideDisplayId */,
                null /* overrideConfig */,
                systemResources.getCompatibilityInfo(),
                systemResources.getClassLoader(),
                null /* loaders */);

        sUseRoundIcon = overlayableRes.getBoolean(com.android.internal.R.bool.config_useRoundIcon);
    }

    /*
     The following set of methods makes code easier to read by re-ordering the TypedArray methods.

     The first parameter is the default, which is the most important to understand for someone
     reading through the parsing code.

     That's followed by the attribute name, which is usually irrelevant during reading because
     it'll look like setSomeValue(true, R.styleable.ReallyLongParentName_SomeValueAttr... and
     the "setSomeValue" part is enough to communicate what the line does.

     Last comes the TypedArray, which is by far the least important since each try-with-resources
     should only have 1.
    */

    // Note there is no variant of bool without a defaultValue parameter, since explicit true/false
    // is important to specify when adding an attribute.
    private static boolean bool(boolean defaultValue, @StyleableRes int attribute, TypedArray sa) {
        return sa.getBoolean(attribute, defaultValue);
    }

    private static float aFloat(float defaultValue, @StyleableRes int attribute, TypedArray sa) {
        return sa.getFloat(attribute, defaultValue);
    }

    private static float aFloat(@StyleableRes int attribute, TypedArray sa) {
        return sa.getFloat(attribute, 0f);
    }

    private static int anInt(int defaultValue, @StyleableRes int attribute, TypedArray sa) {
        return sa.getInt(attribute, defaultValue);
    }

    private static int anInteger(int defaultValue, @StyleableRes int attribute, TypedArray sa) {
        return sa.getInteger(attribute, defaultValue);
    }

    private static int anInt(@StyleableRes int attribute, TypedArray sa) {
        return sa.getInt(attribute, 0);
    }

    @AnyRes
    private static int resId(@StyleableRes int attribute, TypedArray sa) {
        return sa.getResourceId(attribute, 0);
    }

    private static String string(@StyleableRes int attribute, TypedArray sa) {
        return sa.getString(attribute);
    }

    private static String nonConfigString(int allowedChangingConfigs, @StyleableRes int attribute,
            TypedArray sa) {
        return sa.getNonConfigurationString(attribute, allowedChangingConfigs);
    }

    private static String nonResString(@StyleableRes int index, TypedArray sa) {
        return sa.getNonResourceString(index);
    }

    /**
     * Writes the keyset mapping to the provided package. {@code null} mappings are permitted.
     */
    public static void writeKeySetMapping(@NonNull Parcel dest,
            @NonNull Map<String, ArraySet<PublicKey>> keySetMapping) {
        if (keySetMapping == null) {
            dest.writeInt(-1);
            return;
        }

        final int N = keySetMapping.size();
        dest.writeInt(N);

        for (String key : keySetMapping.keySet()) {
            dest.writeString(key);
            ArraySet<PublicKey> keys = keySetMapping.get(key);
            if (keys == null) {
                dest.writeInt(-1);
                continue;
            }

            final int M = keys.size();
            dest.writeInt(M);
            for (int j = 0; j < M; j++) {
                dest.writeSerializable(keys.valueAt(j));
            }
        }
    }

    /**
     * Reads a keyset mapping from the given parcel at the given data position. May return
     * {@code null} if the serialized mapping was {@code null}.
     */
    @NonNull
    public static ArrayMap<String, ArraySet<PublicKey>> readKeySetMapping(@NonNull Parcel in) {
        final int N = in.readInt();
        if (N == -1) {
            return null;
        }

        ArrayMap<String, ArraySet<PublicKey>> keySetMapping = new ArrayMap<>();
        for (int i = 0; i < N; ++i) {
            String key = in.readString();
            final int M = in.readInt();
            if (M == -1) {
                keySetMapping.put(key, null);
                continue;
            }

            ArraySet<PublicKey> keys = new ArraySet<>(M);
            for (int j = 0; j < M; ++j) {
                PublicKey pk = (PublicKey) in.readSerializable();
                keys.add(pk);
            }

            keySetMapping.put(key, keys);
        }

        return keySetMapping;
    }


    /**
     * Callback interface for retrieving information that may be needed while parsing
     * a package.
     */
    public interface Callback {
        boolean hasFeature(String feature);

        ParsingPackage startParsingPackage(@NonNull String packageName,
                @NonNull String baseApkPath, @NonNull String path,
                @NonNull TypedArray manifestArray, boolean isCoreApp);
    }
}
