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
package android.content.pm.split;

import static android.content.pm.PackageManager.INSTALL_FAILED_INVALID_APK;
import static android.content.pm.PackageManager.INSTALL_PARSE_FAILED_NOT_APK;

import android.content.pm.PackageParser.PackageParserException;
import android.content.pm.parsing.ApkLiteParseUtils;
import android.content.pm.parsing.PackageLite;
import android.content.pm.parsing.ParsingPackageUtils;
import android.content.pm.parsing.ParsingPackageUtils.ParseFlags;
import android.content.res.ApkAssets;
import android.content.res.AssetManager;
import android.os.Build;

import com.android.internal.util.ArrayUtils;

import libcore.io.IoUtils;

import java.io.IOException;

/**
 * Loads the base and split APKs into a single AssetManager.
 * @hide
 */
public class DefaultSplitAssetLoader implements SplitAssetLoader {
    private final String mBaseApkPath;
    private final String[] mSplitApkPaths;
    private final @ParseFlags int mFlags;
    private AssetManager mCachedAssetManager;

    private ApkAssets mBaseApkAssets;

    public DefaultSplitAssetLoader(PackageLite pkg, @ParseFlags int flags) {
        mBaseApkPath = pkg.getBaseApkPath();
        mSplitApkPaths = pkg.getSplitApkPaths();
        mFlags = flags;
    }

    private static ApkAssets loadApkAssets(String path, @ParseFlags int flags)
            throws PackageParserException {
        if ((flags & ParsingPackageUtils.PARSE_MUST_BE_APK) != 0
                && !ApkLiteParseUtils.isApkPath(path)) {
            throw new PackageParserException(INSTALL_PARSE_FAILED_NOT_APK,
                    "Invalid package file: " + path);
        }

        try {
            return ApkAssets.loadFromPath(path);
        } catch (IOException e) {
            throw new PackageParserException(INSTALL_FAILED_INVALID_APK,
                    "Failed to load APK at path " + path, e);
        }
    }

    @Override
    public AssetManager getBaseAssetManager() throws PackageParserException {
        if (mCachedAssetManager != null) {
            return mCachedAssetManager;
        }

        ApkAssets[] apkAssets = new ApkAssets[(mSplitApkPaths != null
                ? mSplitApkPaths.length : 0) + 1];

        // Load the base.
        int splitIdx = 0;
        apkAssets[splitIdx++] = mBaseApkAssets = loadApkAssets(mBaseApkPath, mFlags);

        // Load any splits.
        if (!ArrayUtils.isEmpty(mSplitApkPaths)) {
            for (String apkPath : mSplitApkPaths) {
                apkAssets[splitIdx++] = loadApkAssets(apkPath, mFlags);
            }
        }

        AssetManager assets = new AssetManager();
        assets.setConfiguration(0, 0, null, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                Build.VERSION.RESOURCES_SDK_INT);
        assets.setApkAssets(apkAssets, false /*invalidateCaches*/);

        mCachedAssetManager = assets;
        return mCachedAssetManager;
    }

    @Override
    public AssetManager getSplitAssetManager(int splitIdx) throws PackageParserException {
        return getBaseAssetManager();
    }

    @Override
    public ApkAssets getBaseApkAssets() {
        return mBaseApkAssets;
    }

    @Override
    public void close() throws Exception {
        IoUtils.closeQuietly(mCachedAssetManager);
    }
}
