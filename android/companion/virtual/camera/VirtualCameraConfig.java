/*
 * Copyright 2023 The Android Open Source Project
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

import static android.graphics.ImageFormat.YUV_420_888;
import static android.hardware.camera2.CameraMetadata.LENS_FACING_FRONT;

import static java.util.Objects.requireNonNull;

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.companion.virtual.VirtualDevice;
import android.companion.virtualdevice.flags.Flags;
import android.graphics.ImageFormat;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.impl.CameraMetadataNative;
import android.hardware.camera2.params.StreamConfiguration;
import android.hardware.camera2.params.StreamConfigurationDuration;
import android.os.Binder;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteException;
import android.util.ArraySet;
import android.util.Range;
import android.util.Rational;
import android.util.Size;
import android.util.SizeF;
import android.view.Surface;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.ObjLongConsumer;

/**
 * Configuration to create a new {@link VirtualCamera}.
 *
 * <p>Instance of this class are created using the {@link VirtualCameraConfig.Builder}.
 *
 * @hide
 */
@SystemApi
public final class VirtualCameraConfig implements Parcelable {

    private static final int LENS_FACING_UNKNOWN = -1;

    /**
     * Sensor orientation of {@code 0} degrees.
     * @see #getSensorOrientation
     */
    public static final int SENSOR_ORIENTATION_0 = 0;
    /**
     * Sensor orientation of {@code 90} degrees.
     * @see #getSensorOrientation
     */
    public static final int SENSOR_ORIENTATION_90 = 90;
    /**
     * Sensor orientation of {@code 180} degrees.
     * @see #getSensorOrientation
     */
    public static final int SENSOR_ORIENTATION_180 = 180;
    /**
     * Sensor orientation of {@code 270} degrees.
     * @see #getSensorOrientation
     */
    public static final int SENSOR_ORIENTATION_270 = 270;

    /** @hide */
    @IntDef(prefix = {"SENSOR_ORIENTATION_"}, value = {
            SENSOR_ORIENTATION_0,
            SENSOR_ORIENTATION_90,
            SENSOR_ORIENTATION_180,
            SENSOR_ORIENTATION_270
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface SensorOrientation {}

    /**
     * Default {@link CameraCharacteristics} for creating a Virtual Camera starting from a preset
     * list of {@link CameraCharacteristics.Key}s and values that cover the mandatory keys from the
     * <a href="https://android.googlesource.com/platform/hardware/libhardware/+/refs/heads/main/include_all/hardware/camera3.h">Camera HAL specification</a>.
     * It can be used to create a functional {@link VirtualCamera} that can be queried and opened
     * by camera apps.
     * <p>
     * It can be used as a start template in a {@link CameraCharacteristics.Builder} to further
     * customize and overwrite the opinionated preset keys and values.
     */
    public static final CameraCharacteristics DEFAULT_VIRTUAL_CAMERA_CHARACTERISTICS =
            getDefaultVirtualCameraCharacteristics();

    private static final Set<Integer> SUPPORTED_FORMATS = new ArraySet<Integer>();

    static {
        SUPPORTED_FORMATS.add(ImageFormat.YUV_420_888);
        SUPPORTED_FORMATS.add(PixelFormat.RGBA_8888);
        if (Flags.virtualCameraDirectBlobTransfer()) {
            SUPPORTED_FORMATS.add(ImageFormat.JPEG);
            SUPPORTED_FORMATS.add(ImageFormat.HEIC);
        }
    }

    private final String mName;
    private final Set<VirtualCameraStreamConfig> mStreamConfigurations;
    private final IVirtualCameraCallback mCallback;
    @SensorOrientation
    private final int mSensorOrientation;
    private final int mLensFacing;
    private final boolean mPerFrameCameraMetadataEnabled;
    private final CameraCharacteristics mCameraCharacteristics;
    private final boolean mIsMultiStreamEnabled;

    private VirtualCameraConfig(
            @NonNull String name,
            @NonNull Set<VirtualCameraStreamConfig> streamConfigurations,
            @NonNull Executor executor,
            @NonNull VirtualCameraCallback callback,
            @SensorOrientation int sensorOrientation,
            int lensFacing,
            boolean perFrameCameraMetadataEnabled,
            @Nullable CameraCharacteristics cameraCharacteristics,
            boolean isMultiStreamEnabled) {
        mName = requireNonNull(name, "Missing name");
        if (cameraCharacteristics != null) {
            Integer characteristicsLensFacing = cameraCharacteristics.get(
                    CameraCharacteristics.LENS_FACING);
            if (characteristicsLensFacing != null && lensFacing != LENS_FACING_UNKNOWN
                    && characteristicsLensFacing != lensFacing) {
                throw new IllegalArgumentException("Different values are set for "
                        + "lensFacing and CameraCharacteristics.LENS_FACING");
            }
        } else {
            if (lensFacing == LENS_FACING_UNKNOWN) {
                throw new IllegalArgumentException("Lens facing must be set");
            }
        }
        mLensFacing = lensFacing;
        mStreamConfigurations =
                Set.copyOf(requireNonNull(streamConfigurations, "Missing stream configurations"));
        if (mStreamConfigurations.isEmpty()) {
            throw new IllegalArgumentException(
                    "At least one stream configuration is needed to create a virtual camera.");
        }
        mCallback =
                new VirtualCameraCallbackInternal(
                        requireNonNull(callback, "Missing callback"),
                        requireNonNull(executor, "Missing callback executor"),
                        perFrameCameraMetadataEnabled);
        mSensorOrientation = sensorOrientation;
        mPerFrameCameraMetadataEnabled = perFrameCameraMetadataEnabled;
        mCameraCharacteristics = cameraCharacteristics;
        mIsMultiStreamEnabled = isMultiStreamEnabled;
    }

    private VirtualCameraConfig(@NonNull Parcel in) {
        mName = in.readString8();
        mCallback = IVirtualCameraCallback.Stub.asInterface(in.readStrongBinder());
        mStreamConfigurations =
                Set.of(
                        in.readParcelableArray(
                                VirtualCameraStreamConfig.class.getClassLoader(),
                                VirtualCameraStreamConfig.class));
        mSensorOrientation = in.readInt();
        mLensFacing = in.readInt();
        mPerFrameCameraMetadataEnabled = in.readBoolean();
        final CameraMetadataNative nativeMetadata =
                in.readTypedObject(CameraMetadataNative.CREATOR);
        if (nativeMetadata != null) {
            mCameraCharacteristics = new CameraCharacteristics(nativeMetadata);
        } else {
            mCameraCharacteristics = null;
        }
        mIsMultiStreamEnabled = in.readBoolean();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString8(mName);
        dest.writeStrongInterface(mCallback);
        dest.writeParcelableArray(
                mStreamConfigurations.toArray(new VirtualCameraStreamConfig[0]), flags);
        dest.writeInt(mSensorOrientation);
        dest.writeInt(mLensFacing);
        dest.writeBoolean(mPerFrameCameraMetadataEnabled);
        if (mCameraCharacteristics != null) {
            dest.writeTypedObject(mCameraCharacteristics.getNativeMetadata(), flags);
        } else {
            dest.writeTypedObject(null, flags);
        }
        dest.writeBoolean(mIsMultiStreamEnabled);
    }

    /**
     * @return The name of this VirtualCamera
     */
    @NonNull
    public String getName() {
        return mName;
    }

    /**
     * Returns an unmodifiable set of the stream configurations added to this {@link
     * VirtualCameraConfig}.
     *
     * @see VirtualCameraConfig.Builder#addStreamConfig(int, int, int, int)
     */
    @NonNull
    public Set<VirtualCameraStreamConfig> getStreamConfigs() {
        return mStreamConfigurations;
    }

    /**
     * Returns the callback used to communicate from the server to the client.
     *
     * @hide
     */
    @NonNull
    public IVirtualCameraCallback getCallback() {
        return mCallback;
    }

    /**
     * Returns the sensor orientation of this stream, which represents the clockwise angle (in
     * degrees) through which the output image needs to be rotated to be upright on the device
     * screen in its native orientation. Returns {@link #SENSOR_ORIENTATION_0} if omitted.
     */
    @SensorOrientation
    public int getSensorOrientation() {
        return mSensorOrientation;
    }

    /**
     * Returns the direction that the virtual camera faces relative to the virtual device's screen.
     *
     * @see Builder#setLensFacing(int)
     */
    public int getLensFacing() {
        return mLensFacing;
    }

    /**
     * Returns true if the virtual camera has per frame support for camera metadata.
     *
     * @see Builder#setPerFrameCameraMetadataEnabled(boolean)
     */
    public boolean isPerFrameCameraMetadataEnabled() {
        return mPerFrameCameraMetadataEnabled;
    }

    /**
     * Returns the {@link CameraCharacteristics} for this virtual camera config.
     *
     * @see Builder#setCameraCharacteristics(CameraCharacteristics)
     */
    @Nullable
    public CameraCharacteristics getCameraCharacteristics() {
        return mCameraCharacteristics;
    }

    /**
     * If true, a {@link Surface} will be provided for all opened
     * {@link VirtualCameraStreamConfig}. Otherwise, only a single stream will be opened.
     *
     * @see Builder#setConcurrentStreamConfigSupported(boolean)
     * @return true if this virtual camera has been configured to support multiple input streams.
     */
    @FlaggedApi(Flags.FLAG_CAMERA_MULTIPLE_INPUT_STREAMS)
    public boolean isConcurrentStreamConfigSupported() {
        return mIsMultiStreamEnabled;
    }

    @Override
    public String toString() {
        return "VirtualCameraConfig("
                + " name=" + mName
                + " lensFacing=" + mLensFacing
                + " sensorOrientation=" + mSensorOrientation + " )";
    }

    /**
     * Builder for {@link VirtualCameraConfig}.
     *
     * <p>To build an instance of {@link VirtualCameraConfig} the following conditions must be met:
     * <li>At least one stream must be added with {@link #addStreamConfig(int, int, int, int)}.
     * <li>A callback must be set with {@link #setVirtualCameraCallback(Executor,
     *     VirtualCameraCallback)}
     * <li>A lens facing must be set with {@link #setLensFacing(int)} or
     * {@link CameraCharacteristics} with {@link #setCameraCharacteristics(CameraCharacteristics)}
     */
    public static final class Builder {

        private final String mName;
        private final ArraySet<VirtualCameraStreamConfig> mStreamConfigurations = new ArraySet<>();
        private Executor mCallbackExecutor;
        private VirtualCameraCallback mCallback;
        private int mSensorOrientation = SENSOR_ORIENTATION_0;
        private int mLensFacing = LENS_FACING_UNKNOWN;
        private boolean mPerFrameCameraMetadataEnabled = false;
        private CameraCharacteristics mCameraCharacteristics = null;
        private int mStreamIndex = 0;
        private boolean mIsMultiStreamEnabled = false;

        /**
         * Creates a new instance of {@link Builder}.
         *
         * @param name The name of the {@link VirtualCamera}.
         */
        public Builder(@NonNull String name) {
            mName = requireNonNull(name, "Name cannot be null");
        }

        /**
         * Adds a supported input stream configuration for this {@link VirtualCamera}.
         *
         * <p>At least one {@link VirtualCameraStreamConfig} must be added.
         *
         * <p> Each stream will be assigned an id corresponding to the index at which it was
         * added (e.g. first added stream has id 0, second added stream has id 1, etc.) allowing
         * the caller to identify this stream when
         * {@link VirtualCameraCallback#onStreamConfigured(int, Surface, int, int, int)} is called.
         *
         * @param width The width of the stream.
         * @param height The height of the stream.
         * @param format The input format of the stream. Supported formats are
         *               {@link ImageFormat#YUV_420_888} and {@link PixelFormat#RGBA_8888}.
         * @param maximumFramesPerSecond The maximum frame rate (in frames per second) for the
         *                               stream.
         *
         * @throws IllegalArgumentException if invalid dimensions, format or frame rate are passed.
         */
        @NonNull
        public Builder addStreamConfig(
                @IntRange(from = 1) int width,
                @IntRange(from = 1) int height,
                @ImageFormat.Format int format,
                @IntRange(from = 1) int maximumFramesPerSecond) {
            if (width <= 0) {
                throw new IllegalArgumentException(
                        "Invalid width passed for stream config: " + width
                                + ", must be greater than 0");
            }
            if (height <= 0) {
                throw new IllegalArgumentException(
                        "Invalid height passed for stream config: " + height
                                + ", must be greater than 0");
            }
            if (!isFormatSupported(format)) {
                throw new IllegalArgumentException(
                        "Invalid format passed for stream config: " + format);
            }
            if (maximumFramesPerSecond <= 0
                    || maximumFramesPerSecond > VirtualCameraStreamConfig.MAX_FPS_UPPER_LIMIT) {
                throw new IllegalArgumentException(
                        "Invalid maximumFramesPerSecond, must be greater than 0 and less than "
                                + VirtualCameraStreamConfig.MAX_FPS_UPPER_LIMIT);
            }
            mStreamConfigurations.add(new VirtualCameraStreamConfig(width, height, format,
                    maximumFramesPerSecond, mStreamIndex++));
            return this;
        }

        /**
         * Sets the sensor orientation of the virtual camera. This field is optional and can be
         * omitted (defaults to {@link #SENSOR_ORIENTATION_0}).
         * <p>Only used if camera characteristics are not set.
         *
         * @param sensorOrientation The sensor orientation of the camera, which represents the
         *                          clockwise angle (in degrees) through which the output image
         *                          needs to be rotated to be upright on the device screen in its
         *                          native orientation.
         */
        @NonNull
        public Builder setSensorOrientation(@SensorOrientation int sensorOrientation) {
            if (sensorOrientation != SENSOR_ORIENTATION_0
                    && sensorOrientation != SENSOR_ORIENTATION_90
                    && sensorOrientation != SENSOR_ORIENTATION_180
                    && sensorOrientation != SENSOR_ORIENTATION_270) {
                throw new IllegalArgumentException(
                        "Invalid sensor orientation: " + sensorOrientation);
            }
            mSensorOrientation = sensorOrientation;
            return this;
        }

        /**
         * Sets the lens facing direction of the virtual camera.
         * <p>Only used if camera characteristics are not set.
         *
         * <p>A {@link VirtualDevice} can have at most one {@link VirtualCamera} with
         * {@link CameraMetadata#LENS_FACING_FRONT} and at most one {@link VirtualCamera} with
         * {@link CameraMetadata#LENS_FACING_BACK}, though it can create multiple cameras with
         * {@link CameraMetadata#LENS_FACING_EXTERNAL}.
         *
         * @param lensFacing The direction that the virtual camera faces relative to the device's
         *                   screen.
         * @see CameraCharacteristics#LENS_FACING
         */
        @NonNull
        public Builder setLensFacing(int lensFacing) {
            boolean allowLensFacing = lensFacing == CameraMetadata.LENS_FACING_FRONT
                    || lensFacing == CameraMetadata.LENS_FACING_BACK
                    || lensFacing == CameraMetadata.LENS_FACING_EXTERNAL;
            if (!allowLensFacing) {
                throw new IllegalArgumentException("Unsupported lens facing: " + lensFacing);
            }
            mLensFacing = lensFacing;
            return this;
        }

        /**
         * Declares that the virtual camera owner wants to receive {@link CaptureRequest} and
         * can provide {@link CaptureResult} for every frame.
         *
         * <p>This changes which {@link VirtualCameraCallback} methods are called. When enabled,
         * {@link VirtualCameraCallback#onProcessCaptureRequest(int, long, CaptureRequest)}
         * is called and a {@code Consumer} is received in
         * {@link VirtualCameraCallback#onConfigureSession(VirtualCameraSessionConfig,
         * ObjLongConsumer)}. The {@code Consumer} takes the {@link CaptureResult} and
         * the timestamp (as {@code long}) for its parameters.
         *
         * @param perFrameCameraMetadataEnabled if set camera metadata is handled for each frame
         * @see VirtualCameraCallback#onConfigureSession(VirtualCameraSessionConfig,
         * ObjLongConsumer)
         * @see VirtualCameraCallback#onProcessCaptureRequest(int, long)
         * @see VirtualCameraCallback#onProcessCaptureRequest(int, long, CaptureRequest)
         */
        @NonNull
        public Builder setPerFrameCameraMetadataEnabled(boolean perFrameCameraMetadataEnabled) {
            mPerFrameCameraMetadataEnabled = perFrameCameraMetadataEnabled;
            return this;
        }

        /**
         * Sets the {@link CameraCharacteristics} to expose for the configured virtual camera.
         * This field is optional and can be omitted.
         * <p>
         * When set, this {@link CameraCharacteristics} represents the static configuration of
         * the {@link VirtualCamera}, except for the stream configurations which are still
         * configured using the {@link #addStreamConfig}.
         * The and {@link #setLensFacing} and {@link #setSensorOrientation} are ignored, but
         * that also means that the corresponding key must be set in the
         * {@link CameraCharacteristics}.
         * <p>
         * The {@link CameraCharacteristics} needs to contain the set of mandatory
         * {@link CameraCharacteristics.Key}s required by the
         * <a href="https://android.googlesource.com/platform/hardware/libhardware/+/refs/heads/main/include_all/hardware/camera3.h">Camera HAL specification</a>
         *
         * @param cameraCharacteristics The instance of the {@link CameraCharacteristics}
         *                              to be associated with the virtual camera.
         */
        @NonNull
        public Builder setCameraCharacteristics(
                @Nullable CameraCharacteristics cameraCharacteristics) {
            mCameraCharacteristics = cameraCharacteristics;
            return this;
        }

        /**
         * Sets the {@link VirtualCameraCallback} used by the framework to communicate with the
         * {@link VirtualCamera} owner.
         *
         * <p>Setting a callback is mandatory.
         *
         * @param executor The executor onto which the callback methods will be called
         * @param callback The instance of the callback to be added. Subsequent call to this method
         *     will replace the callback set.
         */
        @NonNull
        @SuppressLint("MissingGetterMatchingBuilder") // The configuration is immutable
        public Builder setVirtualCameraCallback(
                @NonNull Executor executor, @NonNull VirtualCameraCallback callback) {
            mCallbackExecutor = requireNonNull(executor);
            mCallback = requireNonNull(callback);
            return this;
        }

        /**
         * Set whether this virtual camera supports opening multiple
         * {@link VirtualCameraStreamConfig} concurrently.
         * <p>
         * By default, when the client camera application requests multiple
         * {@link StreamConfiguration output streams}, the
         * virtual camera service will try to pick a single {@link VirtualCameraStreamConfig} which
         * can honor all requested outputs. It will then create a {@link Surface} matching the
         * picked configuration and pass it in a single
         * {@link VirtualCameraCallback#onStreamConfigured(int, Surface, int, int, int)} call.
         * <p>
         * In case it can't find a single config to match the output, the configuration will fail
         * on the client side and the virtual camera owner won't be notified.
         * <p>
         * With {@code setConcurrentStreamConfigSupported} enabled, the virtual camera service will
         * call
         * {@link VirtualCameraCallback#onStreamConfigured(int, Surface, int, int, int)} with a
         * different {@link Surface} for each opened
         * {@link android.hardware.camera2.params.OutputConfiguration}, allowing the virtual camera
         * owner to write different data for each stream.
         * <p>
         * Enabling this will ensure that {@link CaptureRequest} for different configuration can be
         * submitted concurrently.
         */
        @FlaggedApi(Flags.FLAG_CAMERA_MULTIPLE_INPUT_STREAMS)
        @NonNull
        public Builder setConcurrentStreamConfigSupported(boolean supported) {
            if (!Flags.cameraMultipleInputStreams()) {
                throw new UnsupportedOperationException("Flag %s is not enabled".formatted(
                        Flags.FLAG_CAMERA_MULTIPLE_INPUT_STREAMS));
            }
            mIsMultiStreamEnabled = supported;
            return this;
        }

        /**
         * Builds a new instance of {@link VirtualCameraConfig}
         *
         * @throws NullPointerException if some required parameters are missing.
         * @throws IllegalArgumentException if any parameter is invalid.
         */
        @NonNull
        public VirtualCameraConfig build() {
            return new VirtualCameraConfig(
                    mName, mStreamConfigurations, mCallbackExecutor, mCallback, mSensorOrientation,
                    mLensFacing, mPerFrameCameraMetadataEnabled, mCameraCharacteristics,
                    mIsMultiStreamEnabled);
        }
    }

    private static class VirtualCameraCallbackInternal extends IVirtualCameraCallback.Stub {

        private final VirtualCameraCallback mCallback;
        private final Executor mExecutor;
        private final boolean mPerFrameCameraMetadataEnabled;

        private VirtualCameraCallbackInternal(VirtualCameraCallback callback, Executor executor,
                boolean perFrameCameraMetadataEnabled) {
            mCallback = callback;
            mExecutor = executor;
            mPerFrameCameraMetadataEnabled = perFrameCameraMetadataEnabled;
        }

        @Override
        public void onOpenCamera() {
            if (Flags.virtualCameraOnOpen()) {
                Binder.withCleanCallingIdentity(() -> mExecutor.execute(mCallback::onOpenCamera));
            }
        }

        @Override
        public void onConfigureSession(CaptureRequest sessionParameters,
                ICaptureResultConsumer captureResultConsumer) {
            VirtualCameraSessionConfig virtualCameraSessionConfig =
                    new VirtualCameraSessionConfig(sessionParameters);

            Binder.withCleanCallingIdentity(() ->
                    mExecutor.execute(() -> mCallback.onConfigureSession(
                            virtualCameraSessionConfig,
                            convertToFrameworkCaptureResultConsumer(captureResultConsumer))));
        }

        @Override
        public void onStreamConfigured(int streamId, Surface surface, int width, int height,
                int format) {
            Binder.withCleanCallingIdentity(() ->
                    mExecutor.execute(() -> mCallback.onStreamConfigured(
                            streamId, surface, width, height, format)));
        }

        @Override
        public void onProcessCaptureRequest(int streamId, long frameId,
                CaptureRequest captureRequest) {
            if (mPerFrameCameraMetadataEnabled) {
                Binder.withCleanCallingIdentity(() ->
                        mExecutor.execute(() -> mCallback.onProcessCaptureRequest(
                                streamId, frameId, captureRequest)));
            } else {
                Binder.withCleanCallingIdentity(() ->
                        mExecutor.execute(() -> mCallback.onProcessCaptureRequest(
                                streamId, frameId)));
            }
        }

        @Override
        public void onStreamClosed(int streamId) {
            Binder.withCleanCallingIdentity(() ->
                    mExecutor.execute(() -> mCallback.onStreamClosed(streamId)));
        }

        @Nullable
        private ObjLongConsumer<CaptureResult> convertToFrameworkCaptureResultConsumer(
                @Nullable ICaptureResultConsumer captureResultConsumer) {
            if (!mPerFrameCameraMetadataEnabled || captureResultConsumer == null) {
                return null;
            }

            return (captureResult, timestamp) -> {
                try {
                    captureResultConsumer.acceptCaptureResult(timestamp,
                            captureResult.getNativeMetadata());
                } catch (RemoteException e) {
                    throw e.rethrowFromSystemServer();
                }
            };
        }
    }

    @NonNull
    public static final Parcelable.Creator<VirtualCameraConfig> CREATOR =
            new Parcelable.Creator<>() {
                @Override
                public VirtualCameraConfig createFromParcel(Parcel in) {
                    return new VirtualCameraConfig(in);
                }

                @Override
                public VirtualCameraConfig[] newArray(int size) {
                    return new VirtualCameraConfig[size];
                }
            };

    private static boolean isFormatSupported(@ImageFormat.Format int format) {
        return SUPPORTED_FORMATS.contains(format);
    }

    // Set the default keys and values necessary for a valid and usable CameraCharacteristics
    @NonNull
    private static CameraCharacteristics getDefaultVirtualCameraCharacteristics() {
        List<CameraCharacteristics.Key<?>> availableCharacteristicsKeys = List.of(
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL,
                CameraCharacteristics.FLASH_INFO_AVAILABLE, CameraCharacteristics.LENS_FACING,
                CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS,
                CameraCharacteristics.SENSOR_ORIENTATION,
                CameraCharacteristics.SENSOR_READOUT_TIMESTAMP,
                CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE,
                CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE,
                CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_ABERRATION_MODES,
                CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES,
                CameraCharacteristics.STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES,
                CameraCharacteristics.SCALER_AVAILABLE_STREAM_USE_CASES,
                CameraCharacteristics.SENSOR_AVAILABLE_TEST_PATTERN_MODES,
                CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM,
                CameraCharacteristics.CONTROL_AVAILABLE_MODES,
                CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES,
                CameraCharacteristics.CONTROL_AVAILABLE_SCENE_MODES,
                CameraCharacteristics.CONTROL_AVAILABLE_EFFECTS,
                CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES,
                CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES,
                CameraCharacteristics.CONTROL_AE_AVAILABLE_ANTIBANDING_MODES,
                CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES,
                CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE,
                CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP,
                CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE,
                CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE,
                CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES,
                CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE,
                CameraCharacteristics.SCALER_CROPPING_TYPE,
                CameraCharacteristics.JPEG_AVAILABLE_THUMBNAIL_SIZES,
                CameraCharacteristics.STATISTICS_INFO_MAX_FACE_COUNT,
                CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION,
                CameraCharacteristics.REQUEST_PARTIAL_RESULT_COUNT,
                CameraCharacteristics.REQUEST_PIPELINE_MAX_DEPTH,
                CameraCharacteristics.SYNC_MAX_LATENCY,
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES,
                CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE,
                CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE);

        List<CaptureRequest.Key<?>> availableCaptureRequestKeys = List.of(
                CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE,
                CaptureRequest.CONTROL_CAPTURE_INTENT,
                CaptureRequest.CONTROL_AE_MODE,
                CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,
                CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                CaptureRequest.CONTROL_AE_ANTIBANDING_MODE,
                CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                CaptureRequest.CONTROL_AF_TRIGGER,
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AWB_MODE,
                CaptureRequest.SCALER_CROP_REGION,
                CaptureRequest.CONTROL_EFFECT_MODE,
                CaptureRequest.CONTROL_MODE,
                CaptureRequest.CONTROL_SCENE_MODE,
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                CaptureRequest.CONTROL_ZOOM_RATIO,
                CaptureRequest.FLASH_MODE,
                CaptureRequest.JPEG_THUMBNAIL_SIZE,
                CaptureRequest.JPEG_ORIENTATION,
                CaptureRequest.JPEG_QUALITY,
                CaptureRequest.JPEG_THUMBNAIL_QUALITY,
                CaptureRequest.JPEG_THUMBNAIL_SIZE,
                CaptureRequest.NOISE_REDUCTION_MODE,
                CaptureRequest.STATISTICS_FACE_DETECT_MODE);

        List<CaptureResult.Key<?>> availableCaptureResultKeys = List.of(
                CaptureResult.COLOR_CORRECTION_ABERRATION_MODE,
                CaptureResult.CONTROL_AE_ANTIBANDING_MODE,
                CaptureResult.CONTROL_AE_EXPOSURE_COMPENSATION,
                CaptureResult.CONTROL_AE_LOCK,
                CaptureResult.CONTROL_AE_MODE,
                CaptureResult.CONTROL_AE_PRECAPTURE_TRIGGER,
                CaptureResult.CONTROL_AE_STATE,
                CaptureResult.CONTROL_AE_TARGET_FPS_RANGE,
                CaptureResult.CONTROL_AF_MODE,
                CaptureResult.CONTROL_AF_STATE,
                CaptureResult.CONTROL_AF_TRIGGER,
                CaptureResult.CONTROL_AWB_LOCK,
                CaptureResult.CONTROL_AWB_MODE,
                CaptureResult.CONTROL_AWB_STATE,
                CaptureResult.CONTROL_CAPTURE_INTENT,
                CaptureResult.CONTROL_EFFECT_MODE,
                CaptureResult.CONTROL_MODE,
                CaptureResult.CONTROL_SCENE_MODE,
                CaptureResult.CONTROL_VIDEO_STABILIZATION_MODE,
                CaptureResult.STATISTICS_FACE_DETECT_MODE,
                CaptureResult.FLASH_MODE,
                CaptureResult.FLASH_STATE,
                CaptureResult.JPEG_THUMBNAIL_SIZE,
                CaptureResult.JPEG_QUALITY,
                CaptureResult.JPEG_THUMBNAIL_QUALITY,
                CaptureResult.LENS_FOCAL_LENGTH,
                CaptureResult.LENS_OPTICAL_STABILIZATION_MODE,
                CaptureResult.NOISE_REDUCTION_MODE,
                CaptureResult.REQUEST_PIPELINE_DEPTH,
                CaptureResult.SENSOR_TIMESTAMP,
                CaptureResult.STATISTICS_HOT_PIXEL_MAP_MODE,
                CaptureResult.STATISTICS_LENS_SHADING_MAP_MODE,
                CaptureResult.STATISTICS_SCENE_FLICKER);

        int cameraWidth = 640;
        int cameraHeight = 480;
        int minFps = 4;
        int maxFps = 30;
        int streamFormat = YUV_420_888;
        long minFrameDuration = 1_000_000_000L / maxFps;
        long minStallDuration = 0L;

        Size supportedSize = new Size(cameraWidth, cameraHeight);
        Range<Integer>[] supportedFpsRange = new Range[]{new Range<>(minFps, maxFps)};

        StreamConfiguration streamConfig = new StreamConfiguration(streamFormat, cameraWidth,
                cameraHeight, false);
        StreamConfigurationDuration streamMinFrameConfig = new StreamConfigurationDuration(
                streamFormat, cameraWidth, cameraHeight, minFrameDuration);
        StreamConfigurationDuration streamStallConfig = new StreamConfigurationDuration(
                streamFormat, cameraWidth, cameraHeight, minStallDuration);

        return new CameraCharacteristics.Builder()
                .set(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL,
                        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL)
                .set(CameraCharacteristics.FLASH_INFO_AVAILABLE, false)
                .set(CameraCharacteristics.LENS_FACING, LENS_FACING_FRONT)
                .set(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS, new float[]{43.0f})
                .set(CameraCharacteristics.SENSOR_ORIENTATION, SENSOR_ORIENTATION_0)
                .set(CameraCharacteristics.SENSOR_READOUT_TIMESTAMP,
                        CameraCharacteristics.SENSOR_READOUT_TIMESTAMP_NOT_SUPPORTED)
                .set(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE,
                        CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_UNKNOWN)
                .set(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE,
                        new SizeF(36.0f, 24.0f))
                .set(CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_ABERRATION_MODES,
                        new int[]{CameraCharacteristics.COLOR_CORRECTION_ABERRATION_MODE_OFF})
                .set(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES,
                        new int[]{CameraCharacteristics.NOISE_REDUCTION_MODE_OFF})
                .set(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES,
                        new int[]{CameraCharacteristics.STATISTICS_FACE_DETECT_MODE_OFF})
                .set(CameraCharacteristics.SCALER_AVAILABLE_STREAM_USE_CASES, new long[]{
                        CameraCharacteristics.SCALER_AVAILABLE_STREAM_USE_CASES_DEFAULT,
                        CameraCharacteristics.SCALER_AVAILABLE_STREAM_USE_CASES_PREVIEW,
                        CameraCharacteristics.SCALER_AVAILABLE_STREAM_USE_CASES_STILL_CAPTURE,
                        CameraCharacteristics.SCALER_AVAILABLE_STREAM_USE_CASES_VIDEO_RECORD,
                        CameraCharacteristics.SCALER_AVAILABLE_STREAM_USE_CASES_PREVIEW_VIDEO_STILL,
                        CameraCharacteristics.SCALER_AVAILABLE_STREAM_USE_CASES_VIDEO_CALL})
                .set(CameraCharacteristics.SENSOR_AVAILABLE_TEST_PATTERN_MODES,
                        new int[]{CameraCharacteristics.SENSOR_TEST_PATTERN_MODE_OFF})
                .set(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM, 1.0f)
                .set(CameraCharacteristics.CONTROL_AVAILABLE_MODES,
                        new int[]{CameraCharacteristics.CONTROL_MODE_AUTO})
                .set(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES,
                        new int[]{CameraCharacteristics.CONTROL_AF_MODE_OFF})
                .set(CameraCharacteristics.CONTROL_AVAILABLE_SCENE_MODES,
                        new int[]{CameraCharacteristics.CONTROL_SCENE_MODE_DISABLED})
                .set(CameraCharacteristics.CONTROL_AVAILABLE_EFFECTS,
                        new int[]{CameraCharacteristics.CONTROL_EFFECT_MODE_OFF})
                .set(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES,
                        new int[]{CameraCharacteristics.CONTROL_VIDEO_STABILIZATION_MODE_OFF})
                .set(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES,
                        new int[]{CameraCharacteristics.CONTROL_AE_MODE_ON})
                .set(CameraCharacteristics.CONTROL_AE_AVAILABLE_ANTIBANDING_MODES,
                        new int[]{CameraCharacteristics.CONTROL_AE_ANTIBANDING_MODE_AUTO})
                .set(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES,
                        supportedFpsRange)
                .set(CameraCharacteristics.CONTROL_MAX_REGIONS, new int[]{0, 0, 0})
                .set(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE, new Range<>(0, 0))
                .set(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP, new Rational(0, 0))
                .set(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE, false)
                .set(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE, false)
                .set(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES,
                        new int[]{CameraCharacteristics.CONTROL_AWB_MODE_AUTO})
                .set(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE, new Range<>(1.0f, 1.0f))
                .set(CameraCharacteristics.SCALER_CROPPING_TYPE,
                        CameraCharacteristics.SCALER_CROPPING_TYPE_CENTER_ONLY)
                .set(CameraCharacteristics.JPEG_AVAILABLE_THUMBNAIL_SIZES,
                        new Size[]{supportedSize})
                .set(CameraCharacteristics.STATISTICS_INFO_MAX_FACE_COUNT, 0)
                .set(CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION, 1_000_000_000L)
                .set(CameraCharacteristics.REQUEST_MAX_NUM_OUTPUT_STREAMS, new int[]{0, 3, 1})
                .set(CameraCharacteristics.REQUEST_PARTIAL_RESULT_COUNT, 1)
                .set(CameraCharacteristics.REQUEST_PIPELINE_MAX_DEPTH, (byte) 2)
                .set(CameraCharacteristics.SYNC_MAX_LATENCY,
                        CameraCharacteristics.SYNC_MAX_LATENCY_UNKNOWN)
                .set(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES, new int[]{
                        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE})
                .set(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE,
                        new Rect(0, 0, cameraWidth, cameraHeight))
                .set(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE, supportedSize)
                // stream configurations
                .set(CameraCharacteristics.SCALER_AVAILABLE_STREAM_CONFIGURATIONS,
                        new StreamConfiguration[]{streamConfig})
                .set(CameraCharacteristics.SCALER_AVAILABLE_MIN_FRAME_DURATIONS,
                        new StreamConfigurationDuration[]{streamMinFrameConfig})
                .set(CameraCharacteristics.SCALER_AVAILABLE_STALL_DURATIONS,
                        new StreamConfigurationDuration[]{streamStallConfig})
                .setAvailableCharacteristicsKeys(availableCharacteristicsKeys)
                .setAvailableCaptureRequestKeys(availableCaptureRequestKeys)
                .setAvailableCaptureResultKeys(availableCaptureResultKeys)
                .build();
    }
}
