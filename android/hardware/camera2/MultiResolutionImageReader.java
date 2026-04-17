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

package android.hardware.camera2;

import static com.android.internal.util.Preconditions.checkNotNull;

import android.annotation.CallbackExecutor;
import android.annotation.FlaggedApi;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.graphics.ImageFormat;
import android.graphics.ImageFormat.Format;
import android.hardware.HardwareBuffer;
import android.hardware.HardwareBuffer.Usage;
import android.hardware.camera2.extension.IOnActiveOutputSurfaceCallback;
import android.hardware.camera2.params.MultiResolutionStreamInfo;
import android.hardware.camera2.utils.SurfaceUtils;
import android.media.Image;
import android.media.ImageReader;
import android.os.RemoteException;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import com.android.internal.camera.flags.Flags;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * <p>The MultiResolutionImageReader class wraps a group of {@link ImageReader ImageReaders} with
 * the same format and different sizes, source camera Id, or camera sensor modes.</p>
 *
 * <p>The main use case of this class is for a
 * {@link CameraCharacteristics#REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA logical
 * multi-camera} or an ultra high resolution sensor camera to output variable-size images. For a
 * logical multi-camera which implements optical zoom, different physical cameras may have different
 * maximum resolutions. As a result, when the camera device switches between physical cameras
 * depending on zoom ratio, the maximum resolution for a particular format may change. For an
 * ultra high resolution sensor camera, the camera device may deem it better or worse to run in
 * maximum resolution mode / default mode depending on lighting conditions. So the application may
 * choose to let the camera device decide on its behalf.</p>
 *
 * <p>MultiResolutionImageReader should be used for a camera device only if the camera device
 * supports multi-resolution output stream by advertising the specified output format in {@link
 * CameraCharacteristics#SCALER_MULTI_RESOLUTION_STREAM_CONFIGURATION_MAP}.</p>
 *
 * <p>To acquire images from the MultiResolutionImageReader, the application must use the
 * {@link ImageReader} object passed by
 * {@link ImageReader.OnImageAvailableListener#onImageAvailable} callback to call
 * {@link ImageReader#acquireNextImage} or {@link ImageReader#acquireLatestImage}. The application
 * must not use the {@link ImageReader} passed by an {@link
 * ImageReader.OnImageAvailableListener#onImageAvailable} callback to acquire future images
 * because future images may originate from a different {@link ImageReader} contained within the
 * {@code MultiResolutionImageReader}.</p>
 *
 * <p>Note that by default, for a MultiResolutionImageReader, each capture request produces only
 * one output buffer from one internal reader. Some devices also support producing from multiple
 * readers for a single CaptureRequest (for example, by running multiple sensors at the same time).
 * Support for this can be queried by {@link
 * MultiResolutionStreamConfigurationMap#isConcurrentReadersSupported} and enabled by using {@link
 * MultiResolutionImageReader.Builder#setConcurrentOutputsEnabled}.</p>
 *
 * @see ImageReader
 * @see android.hardware.camera2.CameraCharacteristics#SCALER_MULTI_RESOLUTION_STREAM_CONFIGURATION_MAP
 */
public class MultiResolutionImageReader implements AutoCloseable {

    private static final String TAG = "MultiResolutionImageReader";

    /**
     * Create a new multi-resolution reader based on a group of camera stream properties returned
     * by a camera device.
     *
     * <p>This constructor is the equivalent of
     * {@link #MultiResolutionImageReader(Collection<MultiResolutionStreamInfo>, int, int, long)}
     * without specifying {@code usage}.</p>
     *
     * <p>See {@link #MultiResolutionImageReader(Collection<MultiResolutionStreamInfo>, int, int, long)}
     * for full details.</p>
     *
     * @param streams The group of multi-resolution stream info.
     * @param format The format of the Image that this multi-resolution reader will produce.
     * @param maxImages The maximum number of images the user will want to
     *            access simultaneously.
     */
    public MultiResolutionImageReader(
            @NonNull Collection<MultiResolutionStreamInfo> streams,
            @Format             int format,
            @IntRange(from = 1) int maxImages) {
        mFormat = format;
        mMaxImages = maxImages;
        mConcurrencyEnabled = false;

        if (streams == null || streams.size() <= 1) {
            throw new IllegalArgumentException(
                "The streams info collection must contain at least 2 entries");
        }
        if (mMaxImages < 1) {
            throw new IllegalArgumentException(
                "Maximum outstanding image count must be at least 1");
        }

        if (format == ImageFormat.NV21) {
            throw new IllegalArgumentException(
                    "NV21 format is not supported");
        }

        int numImageReaders = streams.size();
        mReaders = new ImageReader[numImageReaders];
        mUniqueIdReaderMap = new HashMap<>(numImageReaders);
        mStreamInfo = new MultiResolutionStreamInfo[numImageReaders];
        int index = 0;
        for (MultiResolutionStreamInfo streamInfo : streams) {
            mReaders[index] = ImageReader.newInstance(streamInfo.getWidth(),
                    streamInfo.getHeight(), format, maxImages);
            mStreamInfo[index] = streamInfo;
            mUniqueIdReaderMap.put(SurfaceUtils.getSurfaceUniqueId(mReaders[index].getSurface()),
                    mReaders[index]);
            index++;
        }
    }

    /**
     * Create a new multi-resolution reader based on a group of camera stream properties returned
     * by a camera device, and the desired format, maximum buffer capacity and consumer usage flag.
     *
     * <p>
     * The valid size and formats depend on the camera characteristics.
     * {@code MultiResolutionImageReader} for an image format is supported by the camera device if
     * the format is in the supported multi-resolution output stream formats returned by
     * {@link android.hardware.camera2.params.MultiResolutionStreamConfigurationMap#getOutputFormats}.
     * If the image format is supported, the {@code MultiResolutionImageReader} object can be
     * created with the {@code streams} objects returned by
     * {@link android.hardware.camera2.params.MultiResolutionStreamConfigurationMap#getOutputInfo}.
     * </p>
     * <p>
     * The {@code maxImages} parameter determines the maximum number of
     * {@link Image} objects that can be acquired from each of the {@code ImageReader}
     * within the {@code MultiResolutionImageReader}. However, requesting more buffers will
     * use up more memory, so it is important to use only the minimum number necessary. The
     * application is strongly recommended to acquire no more than {@code maxImages} images
     * from all of the internal ImageReader objects combined. By keeping track of the number of
     * acquired images for the MultiResolutionImageReader, the application doesn't need to do the
     * bookkeeping for each internal ImageReader returned from {@link
     * ImageReader.OnImageAvailableListener#onImageAvailable onImageAvailable} callback.
     * </p>
     * <p>
     * Unlike the normal ImageReader, the MultiResolutionImageReader has a more complex
     * configuration sequence. Instead of passing the same surface to OutputConfiguration and
     * CaptureRequest, the
     * {@link android.hardware.camera2.params.OutputConfiguration#createInstancesForMultiResolutionOutput}
     * call needs to be used to create the OutputConfigurations for session creation, and then
     * {@link #getSurface} is used to get {@link CaptureRequest.Builder#addTarget the target for
     * CaptureRequest}.
     * </p>
     * @param streams The group of multi-resolution stream info, which is used to create
     *            a multi-resolution reader containing a number of ImageReader objects. Each
     *            ImageReader object represents a multi-resolution stream in the group.
     * @param format The format of the Image that this multi-resolution reader will produce.
     *            This must be one of the {@link android.graphics.ImageFormat} or
     *            {@link android.graphics.PixelFormat} constants. Note that not all formats are
     *            supported, like ImageFormat.NV21. The supported multi-resolution
     *            reader format can be queried by {@link
     *            android.hardware.camera2.params.MultiResolutionStreamConfigurationMap#getOutputFormats}.
     * @param maxImages The maximum number of images the user will want to
     *            access simultaneously. This should be as small as possible to
     *            limit memory use. Once maxImages images are obtained by the
     *            user from any given internal ImageReader, one of them has to be released before
     *            a new Image will become available for access through the ImageReader's
     *            {@link ImageReader#acquireLatestImage()} or
     *            {@link ImageReader#acquireNextImage()}. Must be greater than 0.
     * @param usage The intended usage of the images produced by the internal ImageReader.
     *              See the usages on {@link HardwareBuffer} for a list of valid usage bits. See
     *              also {@link HardwareBuffer#isSupported(int, int, int, int, long)} for checking
     *              if a combination is supported. If it's not supported this will throw
     *              an {@link IllegalArgumentException}.
     * @see Image
     * @see
     * android.hardware.camera2.CameraCharacteristics#SCALER_MULTI_RESOLUTION_STREAM_CONFIGURATION_MAP
     * @see
     * android.hardware.camera2.params.MultiResolutionStreamConfigurationMap
     */
    @FlaggedApi(Flags.FLAG_MULTIRESOLUTION_IMAGEREADER_USAGE_PUBLIC)
    public MultiResolutionImageReader(
            @NonNull Collection<MultiResolutionStreamInfo> streams,
            @Format             int format,
            @IntRange(from = 1) int maxImages,
            @Usage              long usage) {
        mFormat = format;
        mMaxImages = maxImages;
        mConcurrencyEnabled = false;

        if (streams == null || streams.size() <= 1) {
            throw new IllegalArgumentException(
                "The streams info collection must contain at least 2 entries");
        }
        if (mMaxImages < 1) {
            throw new IllegalArgumentException(
                "Maximum outstanding image count must be at least 1");
        }

        if (format == ImageFormat.NV21) {
            throw new IllegalArgumentException(
                    "NV21 format is not supported");
        }

        int numImageReaders = streams.size();
        mReaders = new ImageReader[numImageReaders];
        mStreamInfo = new MultiResolutionStreamInfo[numImageReaders];
        mUniqueIdReaderMap = new HashMap<>(numImageReaders);
        int index = 0;
        for (MultiResolutionStreamInfo streamInfo : streams) {
            mReaders[index] = ImageReader.newInstance(streamInfo.getWidth(),
                    streamInfo.getHeight(), format, maxImages, usage);
            mStreamInfo[index] = streamInfo;
            mUniqueIdReaderMap.put(SurfaceUtils.getSurfaceUniqueId(mReaders[index].getSurface()),
                    mReaders[index]);
            index++;
        }
    }

    private MultiResolutionImageReader(
            @NonNull Collection<MultiResolutionStreamInfo> streams,
            @Format             int format,
            @IntRange(from = 1) int maxImages,
            @Usage              long usage,
            boolean enableConcurrency) {
        mFormat = format;
        mMaxImages = maxImages;
        mConcurrencyEnabled = enableConcurrency;

        if (streams == null || streams.size() <= 1) {
            throw new IllegalArgumentException(
                "The streams info collection must contain at least 2 entries");
        }
        if (mMaxImages < 1) {
            throw new IllegalArgumentException(
                "Maximum outstanding image count must be at least 1");
        }

        if (format == ImageFormat.NV21) {
            throw new IllegalArgumentException(
                    "NV21 format is not supported");
        }

        int numImageReaders = streams.size();
        mReaders = new ImageReader[numImageReaders];
        mUniqueIdReaderMap = new HashMap<>(numImageReaders);
        mStreamInfo = new MultiResolutionStreamInfo[numImageReaders];
        int index = 0;
        for (MultiResolutionStreamInfo streamInfo : streams) {
            mReaders[index] = ImageReader.newInstance(streamInfo.getWidth(),
                    streamInfo.getHeight(), format, maxImages, usage);
            mStreamInfo[index] = streamInfo;
            mUniqueIdReaderMap.put(SurfaceUtils.getSurfaceUniqueId(mReaders[index].getSurface()),
                    mReaders[index]);
            index++;
        }
    }

    /**
     * Builder class for {@link MultiResolutionImageReader} objects.
     */
    @FlaggedApi(Flags.FLAG_MULTI_RESOLUTION_CONCURRENT_READERS)
    public static final class Builder {
        private Collection<MultiResolutionStreamInfo> mStreams;
        private int mFormat;
        private int mMaxImages;
        private long mUsage;
        private boolean mConcurrencyEnabled = false;

        /**
         * Constructs a new builder for {@link MultiResolutionImageReader}.
         *
         * <p>See {@link #MultiResolutionImageReader(Collection, int, int, long)}
         * for detailed explanations of the constructor parameters.</p>
         *
         * @param streams The group of multi-resolution stream info returned from {@link
         *                android.hardware.camera2.params.MultiResolutionStreamConfigurationMap#getOutputFormats}.
         * @param format The format of the Image that this multi-resolution reader will produce.
         * @param maxImages The maximum number of images the user will want to
         *                  access simultaneously.
         */
        public Builder(
                @NonNull Collection<MultiResolutionStreamInfo> streams,
                @Format             int format,
                @IntRange(from = 1) int maxImages) {
            if (streams == null || streams.size() <= 1) {
                throw new IllegalArgumentException(
                    "The streams info collection must contain at least 2 entries");
            }
            if (format == ImageFormat.NV21) {
                throw new IllegalArgumentException(
                        "NV21 format is not supported");
            }
            if (maxImages < 1) {
                throw new IllegalArgumentException(
                    "Maximum outstanding image count must be at least 1");
            }

            mStreams = streams;
            mFormat = format;
            mMaxImages = maxImages;
            mUsage = (format == ImageFormat.PRIVATE ? 0 : HardwareBuffer.USAGE_CPU_READ_OFTEN);
        }

        /**
         * Set the intended {@link HardwareBuffer.Usage usage} of the images produced by the
         * internal ImageReaders.
         *
         * <p>The default usage is {@link HardwareBuffer#USAGE_CPU_READ_OFTEN}, unless the format
         * is {@link ImageFormat#PRIVATE}, in which case the default usage is 0.</p>
         *
         * @param usage The intended {@link HardwareBuffer.Usage usage} of the images produced
         *              by the internal ImageReaders.
         *
         * @return the Builder instance with customized usage value.
         */
        @SuppressLint("MissingGetterMatchingBuilder")
        public @NonNull Builder setUsage(@Usage long usage) {
            mUsage = usage;
            return this;
        }

        /**
         * Turn on/off concurrent outputs for the internal ImageReaders.
         *
         * <p>If {@link MultiResolutionStreamConfigurationMap#isConcurrentReadersSupported}
         * returns {@code true}, the camera device supports concurrent outputs from
         * multiple internal ImageReaders (for example, in scenarios where multiple sensors run
         * at the same time). By default, concurrent outputs are disabled. The application can
         * enable such mode by calling this function with {@code true}.</p>
         *
         * @param enable Whether to allow the camera device to output concurrent
         *               readers for this MultiResolutionImageReader. If set to true for a
         *               device that doesn't support MultiResolutionImageReader concurrency,
         *               {@link android.hardware.camera2.CameraDevice#createCaptureSession}
         *               will fail.
         *
         * @return the Builder instance with concurrency enabled.
         */
        @SuppressLint("MissingGetterMatchingBuilder")
        public @NonNull Builder setConcurrentOutputsEnabled(boolean enable) {
            mConcurrencyEnabled = enable;
            return this;
        }

        /**
         * Build a new MultiResolutionImageReader object.
         *
         * @return The new MultiResolutionImageReader object.
         */
        public @NonNull MultiResolutionImageReader build() {
            return new MultiResolutionImageReader(
                    mStreams, mFormat, mMaxImages, mUsage, mConcurrencyEnabled);
        }
    }

    /**
     * Set onImageAvailableListener callback.
     *
     * <p>This function sets the onImageAvailableListener for all the internal
     * {@link ImageReader} objects.</p>
     *
     * <p>For a multi-resolution ImageReader, the timestamps of images acquired in
     * onImageAvailable callback from different internal ImageReaders may become
     * out-of-order due to the asynchronous callbacks between the different resolution
     * image queues.</p>
     *
     * @param listener
     *            The listener that will be run.
     * @param executor
     *            The executor which will be used when invoking the callback.
     */
    @SuppressLint({"ExecutorRegistration", "SamShouldBeLast"})
    public void setOnImageAvailableListener(
            @Nullable ImageReader.OnImageAvailableListener listener,
            @Nullable @CallbackExecutor Executor executor) {
        for (int i = 0; i < mReaders.length; i++) {
            mReaders[i].setOnImageAvailableListenerWithExecutor(listener, executor);
        }
    }

    /**
     * Get the {@link OnActiveOutputSurfacesListener} currently registered for this
     * MultiResolutionImageReader.
     *
     * @return The listener, or {@code null} if no listener is currently registered.
     */
    @FlaggedApi(Flags.FLAG_MULTI_RESOLUTION_CONCURRENT_READERS)
    public @Nullable OnActiveOutputSurfacesListener getOnActiveOutputSurfacesListener() {
        synchronized (mListenerLock) {
            return mOutputSurfacesListener;
        }
    }

    /**
     * Register a listener to be notified of active output surfaces for a
     * concurrency-enabled MultiResolutionImageReader.
     *
     * <p>See {@link OnActiveOutputSurfacesListener#onActiveOutputSurfaces} for more details.</p>
     *
     * @param executor
     *            The executor which will be used to invoke the callback.
     * @param listener
     *            The listener that will be run.
     *
     * @throws IllegalArgumentException
     *            If the executor or listener is null.
     * @throws IllegalStateException
     *            If the MultiResolutionImageReader is created without enabling concurrency.
     */
    @FlaggedApi(Flags.FLAG_MULTI_RESOLUTION_CONCURRENT_READERS)
    public void setOnActiveOutputSurfacesListener(@NonNull @CallbackExecutor Executor executor,
            @NonNull OnActiveOutputSurfacesListener listener) {
        if (executor == null) {
            throw new IllegalArgumentException("executor was null");
        }
        if (listener == null) {
            throw new IllegalArgumentException("listener was null");
        }
        if (!mConcurrencyEnabled) {
            throw new IllegalStateException("can only be used if concurrency is enabled");
        }
        synchronized (mListenerLock) {
            mListenerExecutor = executor;
            mOutputSurfacesListener = listener;
        }
    }

    /**
     * Callback interface for being notified of the surfaces used for the capture.
     *
     * <p>The onActiveOutputSurfaces is called per request on this MultiResolutionImageReader,
     * to indicate which underlying ImageReader surfaces are outputting images.</p>
     */
    @FlaggedApi(Flags.FLAG_MULTI_RESOLUTION_CONCURRENT_READERS)
    public interface OnActiveOutputSurfacesListener {
        /**
         * Callback that is called to notify the active surfaces that the application should
         * expect to receive images from.
         *
         * <p>If the MultiResolutionImageReader is created with {@code enableConcurrency}
         * set to {@code true}, multiple underlying readers may produce images for a single
         * capture request. The application can listen to this callback to know which readers
         * to expect an output image from.</p>
         *
         * <p>If the MultiResolutionImageReader is created without {@code enableConcurrency}
         * set to {@code true}, this callback will not the triggered.</p>
         *
         * <p>For each of the {@code activeOutputSurfaces}, a
         * {@link ImageReader.onImageAvailableListener.onImageAvailable} will be called. Or in
         * rare cases, {@link CameraCaptureSession#onCaptureBufferLost} is called if the buffer
         * is dropped.</p>
         *
         * <p>The {@code timestamp} can be used to correlate between the
         * {@code activeOutputSurfaces} and the images being output from those surfaces. The
         * timestamp can be start of exposure or start of readout depending on whether
         * {@link android.hardware.camera2.params.OutputConfiguration#setReadoutTimestampEnabled}
         * was called on the outputs. Similarly, the timestamp can be either in the same time base
         * as in {@link android.os.SystemClock#uptimeMillis} or
         * {@link android.os.SystemClock#elapsedRealtime} depending on the timestamp base of the
         * outputs. See {@link OutputConfiguration#setTimestampBase} for details.</p>
         *
         * @param activeOutputSurfaces the active output surfaces to expect an output from.
         * @param timestamp the timestamp of the capture.
         * @param frameNumber the frame number of this capture.
         *
         * @see ImageReader
         * @see CameraCaptureSession
         * @see OutputConfiguration
         */
        void onActiveOutputSurfaces(@NonNull List<Surface> activeOutputSurfaces,
                long timestamp, long frameNumber);
    }

    /**
     * Post the onActiveOutputSurfaces callback.
     *
     * @hide
     */
    public void postOnActiveOutputSurfacesCallback(
            List<Surface> activeOutputSurfaces, long timestamp, long frameNumber) {
        final Executor executor;
        final OnActiveOutputSurfacesListener listener;
        synchronized (mListenerLock) {
            executor = mListenerExecutor;
            listener = mOutputSurfacesListener;
        }

        if (executor != null && listener != null) {
            executor.execute(() -> {
                listener.onActiveOutputSurfaces(activeOutputSurfaces, timestamp, frameNumber);
            });
        }
    }

    @Override
    public void close() {
        flush();

        for (int i = 0; i < mReaders.length; i++) {
            mReaders[i].close();
        }
    }

    @Override
    protected void finalize() {
        close();
    }

    /**
     * Flush pending images from all internal ImageReaders
     *
     * <p>Acquire and close pending images from all internal ImageReaders. This has the same
     * effect as calling acquireLatestImage() on all internal ImageReaders, and closing all
     * latest images.</p>
     */
    public void flush() {
        flushOther(null);
    }

    /**
     * Flush pending images from other internal ImageReaders
     *
     * <p>Acquire and close pending images from all internal ImageReaders except for the
     * one specified.</p>
     *
     * @param reader The ImageReader object that won't be flushed.
     *
     * @hide
     */
    public void flushOther(ImageReader reader) {
        for (int i = 0; i < mReaders.length; i++) {
            if (reader != null && reader == mReaders[i]) {
                continue;
            }

            while (true) {
                Image image = mReaders[i].acquireNextImageNoThrowISE();
                if (image == null) {
                    break;
                } else {
                    image.close();
                }
            }
        }
    }

    /**
     * Get the internal ImageReader objects
     *
     * @hide
     */
    public @NonNull ImageReader[] getReaders() {
        return mReaders;
    }

    /**
     * Get the internal ImageReader surface based on configured size and physical camera Id.
     *
     * <p>The {@code configuredSize} and {@code physicalCameraId} parameters must match one of the
     * MultiResolutionStreamInfo used to create this {@link MultiResolutionImageReader}.</p>
     *
     * <p>The Surface returned from this function isn't meant to be used directly as part of a
     * {@link CaptureRequest}. It should instead be used for creating an OutputConfiguration
     * before session creation. See {@link OutputConfiguration#setSurfacesForMultiResolutionOutput}
     * for details. For {@link CaptureRequest}, use {@link #getSurface()} instead.</p>
     *
     * <p>Please note that holding on to the Surface objects returned by this method is not enough
     * to keep their parent MultiResolutionImageReaders from being reclaimed. In that sense, a
     * Surface acts like a {@link java.lang.ref.WeakReference weak reference} to the
     * MultiResolutionImageReader that provides it.</p>
     *
     * @param configuredSize The configured size corresponding to one of the internal ImageReader.
     * @param physicalCameraId The physical camera Id the internal ImageReader targets for. If
     *                         the ImageReader is not targeting a physical camera of a logical
     *                         multi-camera, this parameter is set to "".
     *
     * @return The {@link Surface} of the internal ImageReader corresponding to the provided
     *         configured size and physical camera Id.
     *
     * @throws IllegalArgumentException If {@code configuredSize} is {@code null}, or the ({@code
     *                                  configuredSize} and {@code physicalCameraId}) combo is not
     *                                  part of this {@code MultiResolutionImageReader}.
     * @hide
     */
    public @NonNull Surface getSurface(@NonNull Size configuredSize,
            @NonNull String physicalCameraId) {
        checkNotNull(configuredSize, "configuredSize must not be null");
        checkNotNull(physicalCameraId, "physicalCameraId must not be null");

        for (int i = 0; i < mStreamInfo.length; i++) {
            if (mStreamInfo[i].getWidth() == configuredSize.getWidth()
                    && mStreamInfo[i].getHeight() == configuredSize.getHeight()
                    && physicalCameraId.equals(mStreamInfo[i].getPhysicalCameraId())) {
                return mReaders[i].getSurface();
            }
        }
        throw new IllegalArgumentException("configuredSize and physicalCameraId don't match with "
                + "this MultiResolutionImageReader");
    }

    /**
     * Get the surface that is used as a target for {@link CaptureRequest}
     *
     * <p>The application must use the surface returned by this function as a target for
     * {@link CaptureRequest}. The camera device makes the decision on which internal
     * {@code ImageReader} will receive the output image.</p>
     *
     * <p>Please note that holding on to the Surface objects returned by this method is not enough
     * to keep their parent MultiResolutionImageReaders from being reclaimed. In that sense, a
     * Surface acts like a {@link java.lang.ref.WeakReference weak reference} to the
     * MultiResolutionImageReader that provides it.</p>
     *
     * @return a {@link Surface} to use as the target for a capture request.
     */
    public @NonNull Surface getSurface() {
        // Pick the surface of smallest size. This is necessary for an ultra high resolution
        // camera not to default to maximum resolution pixel mode.
        int minReaderSize = mReaders[0].getWidth() * mReaders[0].getHeight();
        Surface candidateSurface = mReaders[0].getSurface();
        for (int i = 1; i < mReaders.length; i++) {
            int readerSize =  mReaders[i].getWidth() * mReaders[i].getHeight();
            if (readerSize < minReaderSize) {
                minReaderSize = readerSize;
                candidateSurface = mReaders[i].getSurface();
            }
        }
        return candidateSurface;
    }

    /**
     * Get the MultiResolutionStreamInfo describing the ImageReader an image originates from
     *
     *<p>An image from a {@code MultiResolutionImageReader} is produced from one of the underlying
     *{@code ImageReader}s. This function returns the {@link MultiResolutionStreamInfo} to describe
     *the property for that {@code ImageReader}, such as width, height, and physical camera Id.</p>
     *
     * @param reader An internal ImageReader within {@code MultiResolutionImageReader}.
     *
     * @return The stream info describing the internal {@code ImageReader}.
     */
    public @NonNull MultiResolutionStreamInfo getStreamInfoForImageReader(
            @NonNull ImageReader reader) {
        for (int i = 0; i < mReaders.length; i++) {
            if (reader == mReaders[i]) {
                return mStreamInfo[i];
            }
        }

        throw new IllegalArgumentException("ImageReader doesn't belong to this multi-resolution "
                + "imagereader");
    }

    /**
     * Returns if concurrency is enabled for this MultiResolutionImageReader.
     *
     * <p>If a MultiResolutionImageReader is created with concurrency enabled, the camera HAL
     * may produce outputs concurrently from multiple internal readers.</p>
     *
     * @return True if concurrent readers are enabled.
     *
     * @hide
     */
    public boolean isConcurrencyEnabled() {
        return mConcurrencyEnabled;
    }

    /**
     * Returns the MultiResolutionImageReader's IOnActiveOutputSurfaceCallback.
     *
     * @hide
     */
    public @NonNull IOnActiveOutputSurfaceCallback getIOnActiveOutputSurfaceCallback() {
        return mIOnActiveOutputSurfaceCallback;
    }

    // mReaders and mStreamInfo has the same length, and their entries are 1:1 mapped.
    private final ImageReader[] mReaders;
    private final MultiResolutionStreamInfo[] mStreamInfo;

    private final HashMap<Long, ImageReader> mUniqueIdReaderMap;

    private final int mFormat;
    private final int mMaxImages;
    private final boolean mConcurrencyEnabled;

    private final Object mListenerLock = new Object();
    private Executor mListenerExecutor;
    private OnActiveOutputSurfacesListener mOutputSurfacesListener;
    private final IOnActiveOutputSurfaceCallback mIOnActiveOutputSurfaceCallback =
            new IOnActiveOutputSurfaceCallback.Stub() {
        @Override
        public void onActiveOutputSurfacesCallback(long[] activeSurfaceIds,
                long timestamp, long frameNumber) throws RemoteException {
            List<Surface> activeOutputSurfaces = new ArrayList<>(activeSurfaceIds.length);
            for (long surfaceId : activeSurfaceIds) {
                ImageReader reader = mUniqueIdReaderMap.get(surfaceId);
                if (reader != null) {
                    activeOutputSurfaces.add(reader.getSurface());
                } else {
                    Log.e(TAG, "Invalid active surface id: " + surfaceId);
                    activeOutputSurfaces.clear();
                    break;
                }
            }
            if (!activeOutputSurfaces.isEmpty()) {
                postOnActiveOutputSurfacesCallback(activeOutputSurfaces, timestamp, frameNumber);
            }
        }
    };
}
