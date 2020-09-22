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

package com.android.server.wifi;

import android.net.wifi.SoftApCapability;
import android.net.wifi.SoftApConfiguration;
import android.net.wifi.WifiManager;

import com.android.internal.util.Preconditions;

import javax.annotation.Nullable;

/**
 * Object holding the parameters needed to start SoftAp mode.
 */
class SoftApModeConfiguration {
    /**
     * Routing mode. Either {@link android.net.wifi.WifiManager#IFACE_IP_MODE_TETHERED}
     * or {@link android.net.wifi.WifiManager#IFACE_IP_MODE_LOCAL_ONLY}.
     */
    private final int mTargetMode;
    private final SoftApCapability mCapability;

    /**
     * SoftApConfiguration for internal use, or null if it hasn't been generated yet.
     */
    private final @Nullable SoftApConfiguration mSoftApConfig;

    SoftApModeConfiguration(int targetMode, @Nullable SoftApConfiguration config,
            SoftApCapability capability) {
        Preconditions.checkArgument(
                targetMode == WifiManager.IFACE_IP_MODE_TETHERED
                        || targetMode == WifiManager.IFACE_IP_MODE_LOCAL_ONLY);

        mTargetMode = targetMode;
        mSoftApConfig = config;
        mCapability = capability;
    }

    public int getTargetMode() {
        return mTargetMode;
    }

    public SoftApConfiguration getSoftApConfiguration() {
        return mSoftApConfig;
    }

    public SoftApCapability getCapability() {
        return mCapability;
    }
}
