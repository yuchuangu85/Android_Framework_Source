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

package android.window;

import static com.android.graphics.surfaceflinger.flags.Flags.FLAG_READBACK_SCREENSHOT;

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.graphics.ColorSpace;
import android.graphics.ParcelableColorSpace;
import android.hardware.HardwareBuffer;
import android.os.OutcomeReceiver;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.view.IWindowManager;
import android.view.SurfaceControl;
import android.view.WindowManagerGlobal;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.concurrent.Executor;

/**
 * Provides an API for capturing the contents of a display.
 *
 * <p>This class allows capturing the screen content based on specified parameters and returns the
 * result asynchronously. It defines parameters for the capture via {@link ScreenCaptureParams} and
 * the result via {@link ScreenCaptureResult}.
 *
 * @hide
 */
@SystemApi
@FlaggedApi(FLAG_READBACK_SCREENSHOT)
public class ScreenCapture {

    /**
     * Parameters for a screen capture request.
     *
     * <p>This class encapsulates the various settings and configurations for a screen capture
     * operation. Use the {@link ScreenCaptureParams.Builder} to construct an instance of this
     * class.
     */
    @FlaggedApi(FLAG_READBACK_SCREENSHOT)
    public static final class ScreenCaptureParams implements Parcelable {

        /** @hide */
        @IntDef(
                prefix = {"SECURE_CONTENT_POLICY_"},
                value = {
                        SECURE_CONTENT_POLICY_REDACT,
                        SECURE_CONTENT_POLICY_CAPTURE,
                        SECURE_CONTENT_POLICY_THROW_EXCEPTION
                })
        @Retention(RetentionPolicy.SOURCE)
        public @interface SecureContentPolicy {
        }

        /**
         * When a secure window is encountered, redact it by blacking out its content.
         *
         * @hide
         */
        public static final int SECURE_CONTENT_POLICY_REDACT = 0;

        /**
         * When a secure window is encountered, attempt to capture its content.
         *
         * @hide
         */
        public static final int SECURE_CONTENT_POLICY_CAPTURE = 1;

        /**
         * When a secure window is encountered, throw an exception.
         *
         * @hide
         */
        public static final int SECURE_CONTENT_POLICY_THROW_EXCEPTION = 2;

        /** @hide */
        @IntDef(
                prefix = {"PROTECTED_CONTENT_POLICY_"},
                value = {PROTECTED_CONTENT_POLICY_REDACT,
                        PROTECTED_CONTENT_POLICY_CAPTURE,
                        PROTECTED_CONTENT_POLICY_THROW_EXCEPTION})
        @Retention(RetentionPolicy.SOURCE)
        public @interface ProtectedContentPolicy {
        }

        /**
         * When a protected window is encountered, redact it by blacking out its content.
         *
         * @hide
         */
        public static final int PROTECTED_CONTENT_POLICY_REDACT = 0;

        /**
         * When a protected window is encountered, capture its content. The resulting buffer will
         * also be protected.
         *
         * @hide
         */
        public static final int PROTECTED_CONTENT_POLICY_CAPTURE = 1;

        /**
         * When a protected window is encountered, throw an exception.
         *
         * @hide
         */
        public static final int PROTECTED_CONTENT_POLICY_THROW_EXCEPTION = 2;

        /** @hide */
        @IntDef(
                prefix = {"CAPTURE_MODE_"},
                value = {CAPTURE_MODE_NONE, CAPTURE_MODE_REQUIRE_OPTIMIZED})
        @Retention(RetentionPolicy.SOURCE)
        public @interface CaptureMode {
        }

        /**
         * Uses the standard screenshot path. Requests the system to capture the screenshot without
         * any restrictions on performance. This is the default capture mode.
         */
        public static final int CAPTURE_MODE_NONE = 0;

        /**
         * Requires the system to use an optimized capture path. This path is designed for minimal
         * performance and power impact, making it suitable for frequent captures.
         *
         * <p>If the optimized path cannot be utilized due to device limitations, or unsupported
         * capture args, the screenshot operation will throw an exception. It will NOT automatically
         * fall back to the 'None' behavior.
         */
        public static final int CAPTURE_MODE_REQUIRE_OPTIMIZED = 1;

        /**
         * Indicates a window should be treated as a mouse cursor in screen capturing.
         *
         * @hide
         */
        public static final int FLAG_MOUSE_CURSOR = 1;

        /**
         * Indicates a window should be treated as screenshot UI elements in screen capturing.
         *
         * @hide
         */
        public static final int FLAG_SCREENSHOT_UI = 1 << 1;

        /**
         * Indicates the window should be treated as a status bar in screen capturing.
         *
         * @hide
         */
        public static final int FLAG_STATUS_BAR = 1 << 2;

        /**
         * Indicates the window should be treated as a IME window in screen capturing.
         *
         * @hide
         */
        public static final int FLAG_IME = 1 << 4;

        /**
         * A {@link CompositionFilterFlag} is a property of a window. It's used to denote windows
         * of specific type or behavior. The flag is mainly used to filter windows in screen
         * capturing.
         *
         * <p>See
         * {@link android.view.SurfaceControl.Transaction#setCompositionFilterFlag(SurfaceControl, int)}}
         *
         * @hide
         */
        @IntDef(value = {FLAG_MOUSE_CURSOR, FLAG_SCREENSHOT_UI, FLAG_STATUS_BAR, FLAG_IME})
        public @interface CompositionFilterFlag {
        }

        private final int mDisplayId;

        @SecureContentPolicy
        private final int mSecureContentPolicy;

        @ProtectedContentPolicy
        private final int mProtectedContentPolicy;

        @CaptureMode
        private final int mCaptureMode;

        @HardwareBuffer.Format
        private final int mPixelFormat;

        private final boolean mUseDisplayInstallationOrientation;

        private final boolean mIncludeSystemOverlays;

        private final boolean mPreserveDisplayColors;

        /** Returns the ID of the display to capture. */
        public int getDisplayId() {
            return mDisplayId;
        }

        /**
         * Returns the policy for handling secure content.
         *
         * @see Builder#setSecureContentPolicy(int)
         * @hide
         */
        public @SecureContentPolicy int getSecureContentPolicy() {
            return mSecureContentPolicy;
        }

        /**
         * Returns the policy for handling protected content.
         *
         * @see Builder#setProtectedContentPolicy(int)
         * @hide
         */
        public @ProtectedContentPolicy int getProtectedContentPolicy() {
            return mProtectedContentPolicy;
        }

        /**
         * Returns the capture mode.
         *
         * @see Builder#setCaptureMode(int)
         */
        public @CaptureMode int getCaptureMode() {
            return mCaptureMode;
        }

        /**
         * Returns the pixel format for the captured image.
         *
         * @see Builder#setPixelFormat(int)
         */
        public @HardwareBuffer.Format int getPixelFormat() {
            return mPixelFormat;
        }

        /**
         * Returns whether to use the display's native orientation for the capture.
         *
         * @see Builder#setUseDisplayInstallationOrientation(boolean)
         * @hide
         */
        public boolean isUseDisplayInstallationOrientation() {
            return mUseDisplayInstallationOrientation;
        }

        /**
         * Returns whether to include system overlays in the capture.
         *
         * @see Builder#setIncludeSystemOverlays(boolean)
         * @hide
         */
        public boolean isIncludeSystemOverlays() {
            return mIncludeSystemOverlays;
        }

        /**
         * Returns whether to preserve the display's colors in the capture.
         *
         * @see Builder#setPreserveDisplayColors(boolean)
         * @hide
         */
        public boolean isPreserveDisplayColors() {
            return mPreserveDisplayColors;
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(@NonNull Parcel out, int flags) {
            out.writeInt(mDisplayId);
            out.writeInt(mSecureContentPolicy);
            out.writeInt(mProtectedContentPolicy);
            out.writeInt(mCaptureMode);
            out.writeInt(mPixelFormat);
            out.writeBoolean(mUseDisplayInstallationOrientation);
            out.writeBoolean(mIncludeSystemOverlays);
            out.writeBoolean(mPreserveDisplayColors);
        }

        @NonNull
        public static final Parcelable.Creator<ScreenCaptureParams> CREATOR =
                new Parcelable.Creator<ScreenCaptureParams>() {
                    public ScreenCaptureParams createFromParcel(Parcel in) {
                        return new ScreenCaptureParams(in);
                    }

                    public ScreenCaptureParams[] newArray(int size) {
                        return new ScreenCaptureParams[size];
                    }
                };

        private ScreenCaptureParams(Parcel in) {
            mDisplayId = in.readInt();
            mSecureContentPolicy = in.readInt();
            mProtectedContentPolicy = in.readInt();
            mCaptureMode = in.readInt();
            mPixelFormat = in.readInt();
            mUseDisplayInstallationOrientation = in.readBoolean();
            mIncludeSystemOverlays = in.readBoolean();
            mPreserveDisplayColors = in.readBoolean();
        }

        private ScreenCaptureParams(
                int displayId,
                @SecureContentPolicy int secureContentPolicy,
                @ProtectedContentPolicy int protectedContentPolicy,
                @CaptureMode int captureMode,
                @HardwareBuffer.Format int pixelFormat,
                boolean useDisplayInstallationOrientation,
                boolean includeSystemOverlays,
                boolean preserveDisplayColors) {
            mDisplayId = displayId;
            mSecureContentPolicy = secureContentPolicy;
            mProtectedContentPolicy = protectedContentPolicy;
            mCaptureMode = captureMode;
            mPixelFormat = pixelFormat;
            mUseDisplayInstallationOrientation = useDisplayInstallationOrientation;
            mIncludeSystemOverlays = includeSystemOverlays;
            mPreserveDisplayColors = preserveDisplayColors;
        }

        /**
         * Builder for creating {@link ScreenCaptureParams} instances.
         */
        @FlaggedApi(FLAG_READBACK_SCREENSHOT)
        public static final class Builder {
            private int mDisplayId;
            @SecureContentPolicy
            private int mSecureContentPolicy = SECURE_CONTENT_POLICY_REDACT;
            @ProtectedContentPolicy
            private int mProtectedContentPolicy = PROTECTED_CONTENT_POLICY_REDACT;
            @CaptureMode
            private int mCaptureMode = CAPTURE_MODE_NONE;
            @HardwareBuffer.Format
            private int mPixelFormat = HardwareBuffer.RGBA_8888;
            private boolean mUseDisplayInstallationOrientation = false;
            private boolean mIncludeSystemOverlays = false;
            private boolean mPreserveDisplayColors = false;

            /** Builder constructor. */
            public Builder(int displayId) {
                mDisplayId = displayId;
            }

            /**
             * Specifies how to handle secure windows.
             *
             * <p>A secure window is a window with {@link
             * android.view.WindowManager.LayoutParams.FLAG_SECURE} set.
             *
             * <p>Default value is {@link #SECURE_CONTENT_POLICY_REDACT}.
             *
             * @hide
             */
            public @NonNull Builder setSecureContentPolicy(
                    @SecureContentPolicy int secureContentPolicy) {
                mSecureContentPolicy = secureContentPolicy;
                return this;
            }

            /**
             * Specifies how to handle protected windows.
             *
             * <p>A protected window is a window that has buffers with the protected bit set.
             *
             * <p>Default value is {@link #PROTECTED_CONTENT_POLICY_REDACT}.
             *
             * @hide
             */
            public @NonNull Builder setProtectedContentPolicy(
                    @ProtectedContentPolicy int protectedContentPolicy) {
                mProtectedContentPolicy = protectedContentPolicy;
                return this;
            }

            /**
             * Sets the capture mode for the screen capture. The default capture mode is
             * {@link #CAPTURE_MODE_NONE}.
             */
            public @NonNull Builder setCaptureMode(@CaptureMode int captureMode) {
                mCaptureMode = captureMode;
                return this;
            }

            /**
             * Sets the desired pixel format for the captured image.
             *
             * <p>Default value is {@link HardwareBuffer#RGBA_8888}.
             */
            public @NonNull Builder setPixelFormat(@HardwareBuffer.Format int pixelFormat) {
                mPixelFormat = pixelFormat;
                return this;
            }

            /**
             * Sets whether to use the display's installation orientation for the capture.
             *
             * <p>If {@code true}, the screenshot will be oriented according to the display's
             * physical installation orientation, ignoring any current logical orientation.
             *
             * <p>If {@code false}, the screenshot will be oriented according to the current logical
             * orientation of the display, including any software rotation.
             *
             * <p>Default value is {@code false}
             *
             * @hide
             */
            public @NonNull Builder setUseDisplayInstallationOrientation(
                    boolean useDisplayInstallationOrientation) {
                mUseDisplayInstallationOrientation = useDisplayInstallationOrientation;
                return this;
            }

            /**
             * Sets whether to include system overlays such as display cutouts.
             *
             * <p>If {@code true}, the capture includes layers that might normally be excluded, such
             * as certain system UI elements or overlays.
             *
             * <p>If {@code false}, standard layer exclusion rules apply, capturing primarily
             * user-visible content.
             *
             * <p>Default value is {@code false}
             *
             * @hide
             */
            public @NonNull Builder setIncludeSystemOverlays(boolean includeSystemOverlays) {
                mIncludeSystemOverlays = includeSystemOverlays;
                return this;
            }

            /**
             * Set to true to preserves the native display colorspace. Useful for mixed HDR + SDR
             * content, using identical processing as the display's.
             *
             * <p>Default value is {@code false}
             *
             * @hide
             */
            public @NonNull Builder setPreserveDisplayColors(boolean preserveDisplayColors) {
                mPreserveDisplayColors = preserveDisplayColors;
                return this;
            }

            /** Builds the ScreenCaptureParams object. */
            public @NonNull ScreenCaptureParams build() {
                return new ScreenCaptureParams(
                        mDisplayId,
                        mSecureContentPolicy,
                        mProtectedContentPolicy,
                        mCaptureMode,
                        mPixelFormat,
                        mUseDisplayInstallationOrientation,
                        mIncludeSystemOverlays,
                        mPreserveDisplayColors);
            }
        }
    }

    /**
     * Represents the result of a screen capture operation.
     *
     * <p>This class encapsulates the captured image data, including the {@link HardwareBuffer}
     * containing the pixel data and the {@link ColorSpace} describing the color information
     * of the captured image.
     */
    @FlaggedApi(FLAG_READBACK_SCREENSHOT)
    public static final class ScreenCaptureResult implements Parcelable {
        private final ColorSpace mColorSpace;
        private final HardwareBuffer mHardwareBuffer;

        /**
         * Returns the {@link ColorSpace} of the captured image.
         */
        public @NonNull ColorSpace getColorSpace() {
            return mColorSpace;
        }

        /**
         * Returns the {@link HardwareBuffer} containing the captured image data.
         */
        public @NonNull HardwareBuffer getHardwareBuffer() {
            return mHardwareBuffer;
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(@NonNull Parcel out, int flags) {
            new ParcelableColorSpace(mColorSpace).writeToParcel(out, flags);
            mHardwareBuffer.writeToParcel(out, flags);
        }

        @NonNull
        public static final Parcelable.Creator<ScreenCaptureResult> CREATOR =
                new Parcelable.Creator<ScreenCaptureResult>() {
                    public ScreenCaptureResult createFromParcel(Parcel in) {
                        return new ScreenCaptureResult(in);
                    }

                    public ScreenCaptureResult[] newArray(int size) {
                        return new ScreenCaptureResult[size];
                    }
                };

        private ScreenCaptureResult(Parcel in) {
            mColorSpace = ParcelableColorSpace.CREATOR.createFromParcel(in).getColorSpace();
            mHardwareBuffer = HardwareBuffer.CREATOR.createFromParcel(in);
        }

        /** ScreenCaptureResult constructor. */
        public ScreenCaptureResult(
                @NonNull ColorSpace colorSpace, @NonNull HardwareBuffer hardwareBuffer) {
            mColorSpace = colorSpace;
            mHardwareBuffer = hardwareBuffer;
        }
    }

    /** @hide */
    @IntDef(
            prefix = {"SCREEN_CAPTURE_ERROR_CODE_"},
            value = {SCREEN_CAPTURE_ERROR_CODE_UNKNOWN,
                    SCREEN_CAPTURE_ERROR_SENSITIVE_CONTENT})
    @Retention(RetentionPolicy.SOURCE)
    public @interface ScreenCaptureErrorCode {
    }

    /**
     * Unknown screen capture error.
     *
     * @hide
     */
    public static final int SCREEN_CAPTURE_ERROR_CODE_UNKNOWN = 1;

    /**
     * Screen capture failed because sensitive content (secure or protected windows) exists.
     *
     * @hide
     */
    public static final int SCREEN_CAPTURE_ERROR_SENSITIVE_CONTENT = 2;

    /**
     * Screen capture failed due to missing permissions.
     *
     * @hide
     */
    public static final int SCREEN_CAPTURE_ERROR_MISSING_PERMISSIONS = 3;

    /** Capture a screenshot. */
    public static void capture(
            @NonNull ScreenCaptureParams params,
            @NonNull Executor executor,
            @NonNull OutcomeReceiver<ScreenCaptureResult, Exception> receiver) {
        IScreenCaptureCallback callback =
                new IScreenCaptureCallback.Stub() {
                    public void onSuccess(ScreenCaptureResult result) {
                        executor.execute(() -> receiver.onResult(result));
                    }

                    public void onFailure(@ScreenCaptureErrorCode int errorCode) {
                        Exception e;
                        if (errorCode == SCREEN_CAPTURE_ERROR_MISSING_PERMISSIONS) {
                            e =
                                    new SecurityException(
                                            "Caller does not have READ_FRAME_BUFFER permission");
                        } else {
                            e = new IllegalStateException("Screen capture failed.");
                        }
                        executor.execute(() -> receiver.onError(e));
                    }
                };
        IWindowManager wm = WindowManagerGlobal.getWindowManagerService();
        try {
            if (wm != null) {
                wm.screenCapture(params, callback);
            } else {
                receiver.onError(
                        new IllegalStateException("Failed to retrieve WindowManagerService."));
            }
        } catch (RemoteException exception) {
            receiver.onError(new RuntimeException(exception));
        }
    }

    /**
     * Returns true if optimized screen capture is enabled on the device.
     *
     * <p>If false, then capture requests with
     * {@link ScreenCaptureParams#CAPTURE_MODE_REQUIRE_OPTIMIZED} will always fail.
     */
    public static boolean isScreenCaptureOptimizationEnabled() {
        return SystemProperties.getBoolean("debug.sf.productionize_readback_screenshot", false);
    }

    private ScreenCapture() {
    }
}
