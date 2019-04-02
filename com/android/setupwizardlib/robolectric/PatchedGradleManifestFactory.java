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

package com.android.setupwizardlib.robolectric;

import org.robolectric.annotation.Config;
import org.robolectric.internal.GradleManifestFactory;
import org.robolectric.internal.ManifestIdentifier;
import org.robolectric.res.FileFsFile;
import org.robolectric.util.Logger;
import org.robolectric.util.ReflectionHelpers;

import java.io.File;
import java.net.URL;

/**
 * Modified GradleManifestFactory to patch an issue where some build variants have merged
 * resources under res/merged/variant/type while others have it under bundles/variant/type/res.
 *
 * The change is that in the .exists() checks below we check for specific folders for the build
 * variant rather than checking existence at the parent directory.
 */
class PatchedGradleManifestFactory extends GradleManifestFactory {

    @Override
    public ManifestIdentifier identify(Config config) {
        if (config.constants() == Void.class) {
            Logger.error("Field 'constants' not specified in @Config annotation");
            Logger.error("This is required when using Robolectric with Gradle!");
            throw new RuntimeException("No 'constants' field in @Config annotation!");
        }

        final String buildOutputDir = getBuildOutputDir(config);
        final String type = getType(config);
        final String flavor = getFlavor(config);
        final String abiSplit = getAbiSplit(config);
        final String packageName = config.packageName().isEmpty()
                ? config.constants().getPackage().getName()
                : config.packageName();

        final FileFsFile res;
        final FileFsFile assets;
        final FileFsFile manifest;

        if (FileFsFile.from(buildOutputDir, "data-binding-layout-out", flavor, type).exists()) {
            // Android gradle plugin 1.5.0+ puts the merged layouts in data-binding-layout-out.
            // https://github.com/robolectric/robolectric/issues/2143
            res = FileFsFile.from(buildOutputDir, "data-binding-layout-out", flavor, type);
        } else if (FileFsFile.from(buildOutputDir, "res", "merged", flavor, type).exists()) {
            // res/merged added in Android Gradle plugin 1.3-beta1
            res = FileFsFile.from(buildOutputDir, "res", "merged", flavor, type);
        } else if (FileFsFile.from(buildOutputDir, "res", flavor, type).exists()) {
            res = FileFsFile.from(buildOutputDir, "res", flavor, type);
        } else {
            res = FileFsFile.from(buildOutputDir, "bundles", flavor, type, "res");
        }

        if (FileFsFile.from(buildOutputDir, "assets", flavor, type).exists()) {
            assets = FileFsFile.from(buildOutputDir, "assets", flavor, type);
        } else {
            assets = FileFsFile.from(buildOutputDir, "bundles", flavor, type, "assets");
        }

        String manifestName = config.manifest();
        URL manifestUrl = getClass().getClassLoader().getResource(manifestName);
        if (manifestUrl != null && manifestUrl.getProtocol().equals("file")) {
            manifest = FileFsFile.from(manifestUrl.getPath());
        } else if (FileFsFile.from(buildOutputDir, "manifests", "full", flavor, abiSplit, type,
                manifestName).exists()) {
            manifest = FileFsFile.from(
                    buildOutputDir, "manifests", "full", flavor, abiSplit, type, manifestName);
        } else if (FileFsFile.from(buildOutputDir, "manifests", "aapt", flavor, abiSplit, type,
                manifestName).exists()) {
            // Android gradle plugin 2.2.0+ can put library manifest files inside of "aapt"
            // instead of "full"
            manifest = FileFsFile.from(buildOutputDir, "manifests", "aapt", flavor, abiSplit,
                    type, manifestName);
        } else {
            manifest = FileFsFile.from(buildOutputDir, "bundles", flavor, abiSplit, type,
                    manifestName);
        }

        return new ManifestIdentifier(manifest, res, assets, packageName, null);
    }

    private static String getBuildOutputDir(Config config) {
        return config.buildDir() + File.separator + "intermediates";
    }

    private static String getType(Config config) {
        try {
            return ReflectionHelpers.getStaticField(config.constants(), "BUILD_TYPE");
        } catch (Throwable e) {
            return null;
        }
    }

    private static String getFlavor(Config config) {
        try {
            return ReflectionHelpers.getStaticField(config.constants(), "FLAVOR");
        } catch (Throwable e) {
            return null;
        }
    }

    private static String getAbiSplit(Config config) {
        try {
            return config.abiSplit();
        } catch (Throwable e) {
            return null;
        }
    }
}
