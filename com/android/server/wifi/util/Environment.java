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

package com.android.server.wifi.util;

import android.content.ApexEnvironment;
import android.content.pm.ApplicationInfo;
import android.os.UserHandle;

import java.io.File;

/**
 * Provides access to environment variables.
 *
 * Note: @hide methods copied from android.os.Environment
 */
public class Environment {
    /**
     * Wifi apex name.
     */
    private static final String WIFI_APEX_NAME = "com.android.wifi";

    /**
     * The path where the Wifi apex is mounted.
     * Current value = "/apex/com.android.wifi"
     */
    private static final String WIFI_APEX_PATH =
            new File("/apex", WIFI_APEX_NAME).getAbsolutePath();

    /**
     * Wifi shared folder.
     */
    public static File getWifiSharedDirectory() {
        return ApexEnvironment.getApexEnvironment(WIFI_APEX_NAME).getDeviceProtectedDataDir();
    }

    /**
     * Wifi user specific folder.
     */
    public static File getWifiUserDirectory(int userId) {
        return ApexEnvironment.getApexEnvironment(WIFI_APEX_NAME)
                .getCredentialProtectedDataDirForUser(UserHandle.of(userId));
    }

    /**
     * Returns true if the app is in the Wifi apex, false otherwise.
     * Checks if the app's path starts with "/apex/com.android.wifi".
     */
    public static boolean isAppInWifiApex(ApplicationInfo appInfo) {
        return appInfo.sourceDir.startsWith(WIFI_APEX_PATH);
    }
}
