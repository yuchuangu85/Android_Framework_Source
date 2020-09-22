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

package com.android.server.wifi;

import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.os.Handler;
import android.provider.Settings;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;

/**
 * Note: This is a hack to provide backward compatibility with the
 * {@link Settings.Global#WIFI_SCAN_ALWAYS_AVAILABLE} @hide settings usage. We migrated storage
 * of the scan always available state from this setting to our internal storage, but need to support
 * the existing @hide users.
 * TODO(b/149954910): We should find a path to stop supporting this!
 */
public class WifiScanAlwaysAvailableSettingsCompatibility {
    private static final String TAG = "WifiScanAlwaysAvailableSettingsCompatibility";

    /**
     * Copy of the settings string. Can't directly use the constant because it is @hide.
     */
    @VisibleForTesting
    public static final String SETTINGS_GLOBAL_WIFI_SCAN_ALWAYS_AVAILABLE =
            "wifi_scan_always_enabled";

    private final Context mContext;
    private final ContentResolver mContentResolver;
    private final Handler mHandler;
    private final WifiSettingsStore mWifiSettingsStore;
    private final ActiveModeWarden mActiveModeWarden;
    private final FrameworkFacade mFrameworkFacade;

    public WifiScanAlwaysAvailableSettingsCompatibility(Context context,
            Handler handler, WifiSettingsStore wifiSettingsStore,
            ActiveModeWarden activeModeWarden, FrameworkFacade frameworkFacade) {
        mContext = context;
        mHandler = handler;
        mWifiSettingsStore = wifiSettingsStore;
        mActiveModeWarden = activeModeWarden;
        mFrameworkFacade = frameworkFacade;

        // Cache the content resolver to ensure that we can detect self changes.
        mContentResolver = context.getContentResolver();
    }

    /**
     * Register settings change observer.
     */
    public void initialize() {
        ContentObserver contentObserver = new ContentObserver(mHandler) {
            @Override
            public void onChange(boolean selfChange) {
                // Ignore any changes we triggered to avoid causing a loop.
                if (selfChange) return;

                boolean settingsIsAvailable =
                        mFrameworkFacade.getIntegerSetting(
                                mContentResolver, SETTINGS_GLOBAL_WIFI_SCAN_ALWAYS_AVAILABLE, 0)
                                == 1;
                // Check if the new state is different from our current state.
                if (mWifiSettingsStore.isScanAlwaysAvailable() != settingsIsAvailable) {
                    Log.i(TAG, "settings changed, new value: " + settingsIsAvailable
                            + ", triggering update");
                    mWifiSettingsStore.handleWifiScanAlwaysAvailableToggled(settingsIsAvailable);
                    mActiveModeWarden.scanAlwaysModeChanged();
                }
            }
        };
        mContentResolver.registerContentObserver(
                Settings.Global.getUriFor(SETTINGS_GLOBAL_WIFI_SCAN_ALWAYS_AVAILABLE),
                false, contentObserver);
    }

    /**
     * Handle scan always available toggle from {@link android.net.wifi.WifiManager#
     * setScanAlwaysAvailable(boolean)}
     */
    public void handleWifiScanAlwaysAvailableToggled(boolean isAvailable) {
        mFrameworkFacade.setIntegerSetting(
                mContentResolver, SETTINGS_GLOBAL_WIFI_SCAN_ALWAYS_AVAILABLE, isAvailable ? 1 : 0);
    }
}
