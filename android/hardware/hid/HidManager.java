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

package android.hardware.hid;

import static com.android.hardware.input.Flags.FLAG_HID_API;

import android.Manifest;
import android.annotation.FlaggedApi;
import android.annotation.SuppressLint;
import android.content.AttributionSource;
import android.content.Context;
import android.permission.PermissionManager;

/**
 * Provides direct access to Human Interface Device (HID) nodes
 */
@FlaggedApi(FLAG_HID_API)
public final class HidManager {
    private final Context mContext;

    /**
     * @hide
     */
    public HidManager(Context context) {
        mContext = context;
    }

    /**
     * Checks if the caller can enumerate connected HIDs.
     * <p>
     * This method returns {@code true} if and only if
     * {@link android.Manifest.permission#ACCESS_HID} is declared in the
     * caller's Manifest <em>and</em> the permission is granted by the user.
     *
     * @return {@code true} if the calling app can enumerate HIDs, {@code false} otherwise
     */
    // Suppressing "Documentation mentions permissions without declaring @RequiresPermission"
    @SuppressLint("RequiresPermission")
    public boolean canEnumerateDevices() {
        final AttributionSource attributionSource = mContext.getAttributionSource();
        final PermissionManager permissionManager =
                mContext.getSystemService(PermissionManager.class);

        return permissionManager.checkPermissionForPreflight(
                                Manifest.permission.ACCESS_HID, attributionSource)
                                    == PermissionManager.PERMISSION_GRANTED;
    }
}
