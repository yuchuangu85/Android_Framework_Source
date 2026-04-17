/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.internal.pm.pkg.component;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.content.pm.PackageInfo;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Set;

/**
 * A {@link android.R.styleable#AndroidManifestUsesPermission
 * &lt;uses-permission&gt;} tag parsed from the manifest.
 *
 * @hide
 */
//@SystemApi(client = SystemApi.Client.SYSTEM_SERVER)
public interface ParsedUsesPermission {

    /**
     * Strong assertion by a developer that they will never use this permission to derive the
     * physical location of the device, regardless of ACCESS_FINE_LOCATION and/or
     * ACCESS_COARSE_LOCATION being granted.
     */
    int FLAG_NEVER_FOR_LOCATION = PackageInfo.REQUESTED_PERMISSION_NEVER_FOR_LOCATION;

    /**
     * Only applies to android.permission.ACCESS_FINE_LOCATION. It indicates a location button is
     * required for a package to access precise location of the device.
     */
    int FLAG_ONLY_FOR_LOCATION_BUTTON = PackageInfo.REQUESTED_PERMISSION_ONLY_FOR_LOCATION_BUTTON;

    /**
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(flag = true, prefix = { "FLAG_" }, value = {
            FLAG_NEVER_FOR_LOCATION,
            FLAG_ONLY_FOR_LOCATION_BUTTON
    })
    @interface UsesPermissionFlags {}

    @NonNull
    String getName();

    @UsesPermissionFlags
    int getUsesPermissionFlags();

    @NonNull
    Set<String> getPurposes();

    @NonNull
    Set<String> getGeneralPurposes();

    int getPurposeStringResource();
}
