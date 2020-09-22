/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.wifi;

import android.annotation.NonNull;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.util.Log;

import com.android.server.wifi.util.Environment;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Wrapper for context to override getResources method. Resources for wifi mainline jar needs to be
 * fetched from the resources APK.
 */
public class WifiContext extends ContextWrapper {
    private static final String TAG = "WifiContext";
    /** Intent action that is used to identify ServiceWifiResources.apk */
    private static final String ACTION_RESOURCES_APK =
            "com.android.server.wifi.intent.action.SERVICE_WIFI_RESOURCES_APK";
    private static final String WIFI_OVERLAY_JAVA_PKG_NAME = "com.android.wifi.resources";

    private String mWifiOverlayApkPkgName;

    // Cached resources from the resources APK.
    private AssetManager mWifiAssetsFromApk;
    private Resources mWifiResourcesFromApk;
    private Resources.Theme mWifiThemeFromApk;

    public WifiContext(@NonNull Context contextBase) {
        super(contextBase);
    }

    /**
     * Get the Java package name of the resources in ServiceWifiResources.apk
     *
     * i.e. the package name of the Wifi Resources R class:
     * {@code import com.android.wifi.resources.R;}, which is "com.android.wifi.resources"
     */
    public String getWifiOverlayJavaPkgName() {
        return WIFI_OVERLAY_JAVA_PKG_NAME;
    }

    /** Get the Android application package name of ServiceWifiResources.apk */
    public String getWifiOverlayApkPkgName() {
        if (mWifiOverlayApkPkgName != null) {
            return mWifiOverlayApkPkgName;
        }

        List<ResolveInfo> resolveInfos = getPackageManager().queryIntentActivities(
                new Intent(ACTION_RESOURCES_APK),
                PackageManager.MATCH_SYSTEM_ONLY);

        // remove apps that don't live in the Wifi apex
        resolveInfos.removeIf(info ->
                !Environment.isAppInWifiApex(info.activityInfo.applicationInfo));

        if (resolveInfos.isEmpty()) {
            // Resource APK not loaded yet, print a stack trace to see where this is called from
            Log.e(TAG, "Attempted to fetch resources before Wifi Resources APK is loaded!",
                    new IllegalStateException());
            return null;
        }

        if (resolveInfos.size() > 1) {
            // multiple apps found, log a warning, but continue
            Log.w(TAG, "Found > 1 APK that can resolve Wifi Resources APK intent: "
                    + resolveInfos.stream()
                            .map(info -> info.activityInfo.applicationInfo.packageName)
                            .collect(Collectors.joining(", ")));
        }

        // Assume the first ResolveInfo is the one we're looking for
        ResolveInfo info = resolveInfos.get(0);
        mWifiOverlayApkPkgName = info.activityInfo.applicationInfo.packageName;
        Log.i(TAG, "Found Wifi Resources APK at: " + mWifiOverlayApkPkgName);
        return mWifiOverlayApkPkgName;
    }

    private Context getResourcesApkContext() {
        try {
            return createPackageContext(getWifiOverlayApkPkgName(), 0);
        } catch (PackageManager.NameNotFoundException e) {
            Log.wtf(TAG, "Failed to load resources", e);
        }
        return null;
    }

    /**
     * Retrieve assets held in the wifi resources APK.
     */
    @Override
    public AssetManager getAssets() {
        if (mWifiAssetsFromApk == null) {
            Context resourcesApkContext = getResourcesApkContext();
            if (resourcesApkContext != null) {
                mWifiAssetsFromApk = resourcesApkContext.getAssets();
            }
        }
        return mWifiAssetsFromApk;
    }

    /**
     * Retrieve resources held in the wifi resources APK.
     */
    @Override
    public Resources getResources() {
        if (mWifiResourcesFromApk == null) {
            Context resourcesApkContext = getResourcesApkContext();
            if (resourcesApkContext != null) {
                mWifiResourcesFromApk = resourcesApkContext.getResources();
            }
        }
        return mWifiResourcesFromApk;
    }

    /**
     * Retrieve theme held in the wifi resources APK.
     */
    @Override
    public Resources.Theme getTheme() {
        if (mWifiThemeFromApk == null) {
            Context resourcesApkContext = getResourcesApkContext();
            if (resourcesApkContext != null) {
                mWifiThemeFromApk = resourcesApkContext.getTheme();
            }
        }
        return mWifiThemeFromApk;
    }
}
