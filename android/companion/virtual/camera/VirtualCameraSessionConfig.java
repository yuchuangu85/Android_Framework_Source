/*
 * Copyright 2025 The Android Open Source Project
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

package android.companion.virtual.camera;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.companion.virtualdevice.flags.Flags;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.SessionConfiguration;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.annotations.VisibleForTesting;

import java.util.Objects;


/**
 * The configuration for a virtual camera session.
 *
 * @hide
 */
@SystemApi
public final class VirtualCameraSessionConfig implements Parcelable {

    /**
     * The initial {@link CaptureRequest} of the session params from the
     * {@link SessionConfiguration} passed when opening the virtual camera.
     */
    private final CaptureRequest mSessionParams;

    /**
     * Construct a new instance of {@link VirtualCameraSessionConfig} initialized with the provided
     * session parameters as a {@link CaptureRequest}.
     *
     * @param sessionParams The initial {@link CaptureRequest}
     * @hide
     * @see CameraDevice#createCaptureSession(SessionConfiguration)
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    public VirtualCameraSessionConfig(@Nullable CaptureRequest sessionParams) {
        this.mSessionParams = sessionParams;
    }

    private VirtualCameraSessionConfig(@NonNull Parcel in) {
        mSessionParams = in.readTypedObject(CaptureRequest.CREATOR);
    }

    @NonNull
    public static final Creator<VirtualCameraSessionConfig> CREATOR = new Creator<>() {
        @Override
        public VirtualCameraSessionConfig createFromParcel(Parcel in) {
            return new VirtualCameraSessionConfig(in);
        }

        @Override
        public VirtualCameraSessionConfig[] newArray(int size) {
            return new VirtualCameraSessionConfig[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeTypedObject(mSessionParams, flags);
    }

    /**
     * Returns the session parameters of the virtual camera session.
     */
    public @Nullable CaptureRequest getSessionParameters() {
        return mSessionParams;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        VirtualCameraSessionConfig that = (VirtualCameraSessionConfig) o;
        return mSessionParams.equals(that.mSessionParams);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mSessionParams);
    }
}
