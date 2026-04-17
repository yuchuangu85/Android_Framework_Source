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

package android.content.res;

import static android.app.WindowConfiguration.ROTATION_UNDEFINED;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.TestApi;
import android.os.Parcel;
import android.os.Parcelable;
import android.ravenwood.annotation.RavenwoodKeepWholeClass;

import java.util.Objects;

/**
 * Object to communicate camera compatibility info setup.
 *
 * <p>Camera Compatibility is used to resolve major camera issues - like stretched or sideways
 * previews - for apps not adapted to large screens. The camera compatibility treatment represented
 * by this object sandboxes the eligible activity's environment to what that app most likely expects
 * given its requested orientation and current device state. Some of the most important platform
 * signals for calculating camera preview are: camera sensor orientation, device rotation, app
 * window aspect ratio and how the camera feed is rotated, all of which apps tend to make
 * assumptions about, and therefore could be changed as part of Camera Compatibility treatment.
 *
 * <p>Upon detecting eligible camera activity, Window Manager calculates the necessary changes to
 * display rotation, window bounds, camera sensor and feed, and requests the platform to reflect
 * these changes using this {@link CameraCompatibilityInfo} object.
 *
 * @hide
 */
@TestApi
@RavenwoodKeepWholeClass
public final class CameraCompatibilityInfo implements Parcelable {
    private final int mRotateAndCropRotation;
    private final boolean mShouldOverrideSensorOrientation;
    private final boolean mShouldLetterboxForCameraCompat;
    private final int mDisplayRotationSandbox;
    private final boolean mShouldAllowTransformInverseDisplay;

    private CameraCompatibilityInfo(Builder builder) {
        mRotateAndCropRotation = builder.mRotateAndCropRotation;
        mShouldOverrideSensorOrientation = builder.mShouldOverrideSensorOrientation;
        mShouldLetterboxForCameraCompat = builder.mShouldLetterboxForCameraCompat;
        mDisplayRotationSandbox = builder.mDisplayRotationSandbox;
        mShouldAllowTransformInverseDisplay = builder.mShouldAllowTransformInverseDisplay;
    }

    private CameraCompatibilityInfo(Parcel in) {
        mRotateAndCropRotation = in.readInt();
        mShouldOverrideSensorOrientation = in.readByte() != 0;
        mShouldLetterboxForCameraCompat = in.readByte() != 0;
        mDisplayRotationSandbox = in.readInt();
        mShouldAllowTransformInverseDisplay = in.readByte() != 0;
    }

    /**
     * By how much camera feed should be rotated for compatibility, as
     * `android.view.Surface.Rotation` enum. If none, the value is
     * `android.app.WindowConfiguration.ROTATION_UNDEFINED`.
     */
    public int getRotateAndCropRotation() {
        return mRotateAndCropRotation;
    }

    /** Whether camera sensor orientation should be sandboxed (usually to portrait). */
    public boolean shouldOverrideSensorOrientation() {
        return mShouldOverrideSensorOrientation;
    }

    /** Whether camera activity should be letterboxed, i.e. whether app bounds should be changed. */
    public boolean shouldLetterboxForCameraCompat() {
        return mShouldLetterboxForCameraCompat;
    }

    /**
     *  Display rotation that the camera compatibility app should see. If sandboxing should not be
     *  applied, the value is `android.app.WindowConfiguration.ROTATION_UNDEFINED`.
     */
    public int getDisplayRotationSandbox() {
        return mDisplayRotationSandbox;
    }

    /**
     * Whether rotating the camera buffers to counter the display rotation is allowed.
     *
     * <p> This value is often set to false when display rotation is sandboxed for compatibility.
     * Since this rotation is not real for the display, native transform can rotate the buffers
     * incorrectly. Additionally, for camera compat, display sandboxing and rotate-and-crop are
     * often applied together so the apps do not have to apply any display-based transformations.
     */
    public boolean shouldAllowTransformInverseDisplay() {
        return mShouldAllowTransformInverseDisplay;
    }

    /** Builder for {@link CameraCompatibilityInfo} */
    public static final class Builder {
        private int mRotateAndCropRotation = ROTATION_UNDEFINED;
        private boolean mShouldOverrideSensorOrientation = false;
        private boolean mShouldLetterboxForCameraCompat = false;
        private int mDisplayRotationSandbox = ROTATION_UNDEFINED;
        private boolean mShouldAllowTransformInverseDisplay = true;

        public Builder() {}

        /**
         *  Sets by how much camera feed should be rotated for compatibility, as
         * `android.view.Surface.Rotation` enum. If none, the value is
         * `android.app.WindowConfiguration.ROTATION_UNDEFINED`.
         */
        @NonNull
        public Builder setRotateAndCropRotation(int rotateAndCropRotation) {
            mRotateAndCropRotation = rotateAndCropRotation;
            return this;
        }

        /** Sets whether camera sensor orientation should be sandboxed (usually to portrait). */
        @NonNull
        public Builder setShouldOverrideSensorOrientation(boolean shouldOverrideSensorOrientation) {
            mShouldOverrideSensorOrientation = shouldOverrideSensorOrientation;
            return this;
        }

        /**
         * Sets whether camera activity should be letterboxed, i.e. whether app bounds should be
         * changed.
         */
        @NonNull
        public Builder setShouldLetterboxForCameraCompat(boolean shouldLetterboxForCameraCompat) {
            mShouldLetterboxForCameraCompat = shouldLetterboxForCameraCompat;
            return this;
        }

        /**
         *  Sets the display rotation that the camera compatibility app should see. If sandboxing
         *  should not be applied, the value should be
         *  `android.app.WindowConfiguration.ROTATION_UNDEFINED`.
         */
        @NonNull
        public Builder setDisplayRotationSandbox(int displayRotationSandbox) {
            mDisplayRotationSandbox = displayRotationSandbox;
            return this;
        }

        /**
         * Whether rotating the camera buffers to counter the display rotation is allowed.
         *
         * <p> This value is often set to {@code false} when display rotation is sandboxed for
         * compatibility. Since this rotation is not real for the display, native transform can
         * rotate the buffers incorrectly. Additionally, for camera compat, display sandboxing and
         * rotate-and-crop are often applied together so the apps do not have to apply any
         * display-based transformations.
         */
        @NonNull
        public Builder setShouldAllowTransformInverseDisplay(boolean
                shouldAllowTransformInverseDisplay) {
            mShouldAllowTransformInverseDisplay = shouldAllowTransformInverseDisplay;
            return this;
        }

        /** Builds a {@link CameraCompatibilityInfo} object. */
        @NonNull
        public CameraCompatibilityInfo build() {
            return new CameraCompatibilityInfo(this);
        }
    }

    @NonNull
    public static final Creator<CameraCompatibilityInfo> CREATOR =
            new Creator<CameraCompatibilityInfo>() {
                @Override
                public CameraCompatibilityInfo createFromParcel(Parcel in) {
                    return new CameraCompatibilityInfo(in);
                }

                @Override
                public CameraCompatibilityInfo[] newArray(int size) {
                    return new CameraCompatibilityInfo[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mRotateAndCropRotation);
        dest.writeBoolean(mShouldOverrideSensorOrientation);
        dest.writeBoolean(mShouldLetterboxForCameraCompat);
        dest.writeInt(mDisplayRotationSandbox);
        dest.writeBoolean(mShouldAllowTransformInverseDisplay);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mRotateAndCropRotation,
                mShouldOverrideSensorOrientation, mShouldLetterboxForCameraCompat,
                mDisplayRotationSandbox, mShouldAllowTransformInverseDisplay);
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CameraCompatibilityInfo that)) {
            return false;
        }
        return mRotateAndCropRotation == that.mRotateAndCropRotation
                && mShouldOverrideSensorOrientation == that.mShouldOverrideSensorOrientation
                && mShouldLetterboxForCameraCompat == that.mShouldLetterboxForCameraCompat
                && mDisplayRotationSandbox == that.mDisplayRotationSandbox
                && mShouldAllowTransformInverseDisplay == that.mShouldAllowTransformInverseDisplay;
    }

    /** Whether any camera compat mode changes are requested via this object. */
    public static boolean isCameraCompatModeActive(@NonNull CameraCompatibilityInfo
            cameraCompatMode) {
        return cameraCompatMode.mRotateAndCropRotation != ROTATION_UNDEFINED
                || cameraCompatMode.mShouldOverrideSensorOrientation
                || cameraCompatMode.mShouldLetterboxForCameraCompat
                || cameraCompatMode.mDisplayRotationSandbox != ROTATION_UNDEFINED
                || !cameraCompatMode.mShouldAllowTransformInverseDisplay;
    }

    /** Changes the WindowConfiguration display rotation for the given configuration. */
    public void applyToConfigurationIfNeeded(@NonNull Configuration inoutConfig) {
        if (mDisplayRotationSandbox != ROTATION_UNDEFINED) {
            inoutConfig.windowConfiguration.setDisplayRotation(mDisplayRotationSandbox);
        }
    }

    @Override
    public String toString() {
        return "CameraCompatibilityInfo{"
                + "mRotateAndCropRotation=" + mRotateAndCropRotation
                + ", mShouldOverrideSensorOrientation=" + mShouldOverrideSensorOrientation
                + ", mShouldLetterboxForCameraCompat=" + mShouldLetterboxForCameraCompat
                + ", mDisplayRotationSandbox=" + mDisplayRotationSandbox
                + ", mShouldAllowTransformInverseDisplay=" + mShouldAllowTransformInverseDisplay
                + '}';
    }
}
