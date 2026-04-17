/*
 * Copyright (C) 2026 The Android Open Source Project
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

package com.android.internal.compat;

import android.annotation.NonNull;
import android.os.Environment;
import android.util.Log;
import android.util.LongSparseArray;
import android.util.Xml;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.ravenwood.RavenwoodHelperBridge;
import com.android.modules.utils.TypedXmlPullParser;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.function.Predicate;

/**
 * Holds preloaded compatibility rules.
 * @hide
 */
@android.ravenwood.annotation.RavenwoodKeepWholeClass
public final class CompatibilityRules {
    private static final String TAG = "CompatibilityRules";
    private static LongSparseArray<CompatibilityChangeInfo> sRules = new LongSparseArray<>();

    private static final AndroidBuildClassifier sBuildClassifier = new AndroidBuildClassifier();

    /**
     * Initializes the preloaded rules. Should be called during Zygote preload.
     */
    public static void init(@NonNull LongSparseArray<CompatibilityChangeInfo> rules) {
        if (sRules.size() > 0) {
            Log.e(TAG, "Compatibility rules already initialized");
            return;
        }
        // Shallow clone protects against external array modifications.
        // Safe and highly efficient because CompatibilityChangeInfo objects are immutable.
        sRules = rules.clone();
        Log.i(TAG, "Compatibility rules initialized with " + sRules.size() + " rules.");
    }

    /**
     * Resets the preloaded rules. Used for testing only.
     * @hide
     */
    @VisibleForTesting
    public static void reset() {
        sRules = new LongSparseArray<>();
    }

    /**
     * Loads the system-level compatibility configurations.
     */
    @android.ravenwood.annotation.RavenwoodReplace
    public static void loadSystemRules() {
        LongSparseArray<CompatibilityChangeInfo> rules = new LongSparseArray<>();
        loadConfigFromDir(Environment.buildPath(
                Environment.getRootDirectory(), "etc", "compatconfig"),
                rules, (file) -> true);
        loadConfigFromDir(
                Environment.buildPath(
                        Environment.getRootDirectory(), "system_ext", "etc", "compatconfig"),
                rules,
                (file) -> true);
        init(rules);
    }

    private static void loadSystemRules$ravenwood() {
        LongSparseArray<CompatibilityChangeInfo> rules = new LongSparseArray<>();
        final var runtimePath = RavenwoodHelperBridge.getInstance().getRavenwoodRuntimePath();
        Log.i(TAG, "Loading rules on Ravenwood. Runtime path: " + runtimePath);
        final var configDir = new File(runtimePath + "/ravenwood-data/");
        loadConfigFromDir(
                configDir, rules, (file) -> file.getName().endsWith("compat-config.xml"));
        init(rules);
    }

    @VisibleForTesting
    static void loadConfigFromDir(
            File libraryDir,
            LongSparseArray<CompatibilityChangeInfo> rules,
            Predicate<File> filter) {
        Log.i(TAG, "Loading configs from " + libraryDir.getAbsolutePath());
        if (!libraryDir.exists() || !libraryDir.isDirectory()) {
            Log.w(TAG, "Directory does not exist: " + libraryDir.getAbsolutePath());
            return;
        }
        File[] files = libraryDir.listFiles();
        if (files == null) {
            Log.w(TAG, "No files found in " + libraryDir.getAbsolutePath());
            return;
        }
        for (File f : files) {
            if (f.isFile() && f.getName().endsWith(".xml") && filter.test(f)) {
                readConfigToMap(f, rules);
            }
        }
    }

    private static void readConfigToMap(
            File configFile, LongSparseArray<CompatibilityChangeInfo> rules) {
        Log.i(TAG, "Reading config file: " + configFile.getAbsolutePath());
        try (InputStream in = new BufferedInputStream(new FileInputStream(configFile))) {
            TypedXmlPullParser parser = Xml.resolvePullParser(in);
            int type;
            while ((type = parser.next()) != XmlPullParser.END_DOCUMENT) {
                if (type != XmlPullParser.START_TAG) {
                    continue;
                }
                String tag = parser.getName();
                if ("compat-change".equals(tag)) {
                    String idStr = parser.getAttributeValue(null, "id");
                    if (idStr == null) {
                        continue;
                    }
                    try {
                        long id = Long.parseLong(idStr);
                        String name = parser.getAttributeValue(null, "name");
                        int enableAfterTargetSdk =
                                getIntAttribute(parser, "enableAfterTargetSdk", -1);
                        int enableSinceTargetSdk =
                                getIntAttribute(parser, "enableSinceTargetSdk", -1);
                        boolean disabled = getBooleanAttribute(parser, "disabled", false);
                        boolean loggingOnly = getBooleanAttribute(parser, "loggingOnly", false);
                        boolean noLogging = getBooleanAttribute(parser, "noLogging", false);
                        String description = parser.getAttributeValue(null, "description");
                        boolean overridable = getBooleanAttribute(parser, "overridable", false);

                        CompatibilityChangeInfo info = new CompatibilityChangeInfo(
                                id, name, enableAfterTargetSdk, enableSinceTargetSdk,
                                disabled, loggingOnly, noLogging, description, overridable);
                        rules.put(id, info);
                    } catch (NumberFormatException e) {
                        Log.e(
                                TAG,
                                "Invalid ID in compat config file: "
                                        + configFile.getAbsolutePath()
                                        + " id: "
                                        + idStr,
                                e);
                    }
                }
            }
        } catch (IOException | XmlPullParserException e) {
            Log.e(TAG, "Encountered an error while reading/parsing compat config file: "
                    + configFile.getAbsolutePath(), e);
        }
    }

    private static int getIntAttribute(TypedXmlPullParser parser, String name, int defaultValue) {
        return parser.getAttributeInt(null, name, defaultValue);
    }

    private static boolean getBooleanAttribute(
            TypedXmlPullParser parser, String name, boolean defaultValue) {
        return parser.getAttributeBoolean(null, name, defaultValue);
    }

    /**
     * Adds rules for testing.
     * @hide
     */
    @VisibleForTesting
    public static void initRulesForTest(@NonNull CompatibilityChangeInfo... rules) {
        for (CompatibilityChangeInfo info : rules) {
            sRules.put(info.getId(), info);
        }
    }

    /**
     * Checks if a change is enabled based on preloaded rules.
     */
    public static boolean isChangeEnabled(long changeId, int targetSdkVersion) {
        CompatibilityChangeInfo info = sRules.get(changeId);
        if (info == null) {
            // Unknown changes are enabled by default.
            return true;
        }
        if (info.getDisabled()) {
            return false;
        }
        if (info.getEnableSinceTargetSdk() != -1) {
            int compareSdk = Math.min(targetSdkVersion, sBuildClassifier.platformTargetSdk());
            return compareSdk >= info.getEnableSinceTargetSdk();
        }
        return true;
    }

    /**
     * Returns the preloaded rules.
     */
    public static LongSparseArray<CompatibilityChangeInfo> getRules() {
        return sRules;
    }
}
