/*
 * Copyright (C) 2025 The Android Open Source Project
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

package android.hardware.camera2.extension;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.hardware.camera2.CaptureRequest;

import com.android.internal.camera.flags.Flags;

import java.util.HashMap;
import java.util.Map;


/**
 * Helper class used to describe a single camera
 * output configuration that is passed by the
 * camera client to the extension implementation.
 *
 * @hide
 */

@FlaggedApi(Flags.FLAG_EFV_CAPTURE_LATENCY)
@SystemApi
public final class CameraConfiguration {
    private CameraOutputSurface mPreviewOutputSurface;
    private CameraOutputSurface mStillCaptureOutputSurface;
    private CameraOutputSurface mPostViewOutputSurface;
    private Map<CaptureRequest.Key<?>, Object> mSessionParams = new HashMap<>();

    CameraConfiguration(@NonNull CameraOutputSurface previewOutputSurface,
            @NonNull CameraOutputSurface stillCaptureOutputSurface,
            @NonNull CameraOutputSurface postViewOutputSurface) {
       mPreviewOutputSurface = previewOutputSurface;
       mStillCaptureOutputSurface = stillCaptureOutputSurface;
       mPostViewOutputSurface = postViewOutputSurface;
    }

    /**
     * Return the current preview output {@link CameraOutputSurface}
     */
    @NonNull
    public CameraOutputSurface getPreviewOutputSurface() {
        return mPreviewOutputSurface;
    }

    /**
     * Return the current still capture output {@link CameraOutputSurface}
     */
    @NonNull
    public CameraOutputSurface getStillCaptureOutputSurface() {
        return mStillCaptureOutputSurface;
    }

    /**
     * Return the current postview output {@link CameraOutputSurface}
     */
    @NonNull
    public CameraOutputSurface getPostViewOutputSurface() {
        return mPostViewOutputSurface;
    }

    /**
     * Set the current session parameters map.
     *
     * @param sessionCaptureParams Capture request key value map of all client session parameters.
     *                             OEM can choose to apply those that are appropriate for the
     *                             specific extension.
     */
    @FlaggedApi(Flags.FLAG_VENDOR_DEFINED_CAMERA_EXTENSIONS)
    public void setSessionWideParams(
            @NonNull Map<CaptureRequest.Key<?>, Object> sessionCaptureParams) {
        mSessionParams = sessionCaptureParams;
    }

    /**
     * Return the current session parameters map.
     */
    @FlaggedApi(Flags.FLAG_VENDOR_DEFINED_CAMERA_EXTENSIONS)
    @NonNull
    public Map<CaptureRequest.Key<?>, Object> getSessionWideParams() {
        return mSessionParams;
    }
}
