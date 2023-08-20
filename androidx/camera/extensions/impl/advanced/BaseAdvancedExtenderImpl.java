/*
 * Copyright 2021 The Android Open Source Project
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

package androidx.camera.extensions.impl.advanced;

import androidx.camera.extensions.impl.advanced.JpegEncoder;

import static  androidx.camera.extensions.impl.advanced.JpegEncoder.JPEG_DEFAULT_QUALITY;
import static androidx.camera.extensions.impl.advanced.JpegEncoder.JPEG_DEFAULT_ROTATION;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.Image.Plane;
import android.media.ImageWriter;
import android.os.Build;
import android.util.Log;
import android.util.Pair;
import android.util.Range;
import android.util.Size;
import android.view.Surface;

import androidx.annotation.GuardedBy;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.Executor;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@SuppressLint("UnknownNullness")
public abstract class BaseAdvancedExtenderImpl implements AdvancedExtenderImpl {

    static {
        try {
            System.loadLibrary("encoderjpeg_jni");
        } catch (UnsatisfiedLinkError e) {
            Log.e("BaseAdvancedExtenderImpl", "libencoderjpeg_jni not loaded");
        }
    }

    protected CameraCharacteristics mCameraCharacteristics;

    public BaseAdvancedExtenderImpl() {
    }

    @Override
    public abstract boolean isExtensionAvailable(String cameraId,
            Map<String, CameraCharacteristics> characteristicsMap);

    @Override
    public void init(String cameraId,
            Map<String, CameraCharacteristics> characteristicsMap) {
        mCameraCharacteristics = characteristicsMap.get(cameraId);
    }

    @Override
    public Range<Long> getEstimatedCaptureLatencyRange(
            String cameraId, Size size, int imageFormat) {
        return null;
    }

    protected Map<Integer, List<Size>> filterOutputResolutions(List<Integer> formats) {
        Map<Integer, List<Size>> formatResolutions = new HashMap<>();

        StreamConfigurationMap map =
                mCameraCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

        if (map != null) {
            for (Integer format : formats) {
                if (map.getOutputSizes(format) != null) {
                    formatResolutions.put(format, Arrays.asList(map.getOutputSizes(format)));
                }
            }
        }

        return formatResolutions;
    }

    @Override
    public Map<Integer, List<Size>> getSupportedPreviewOutputResolutions(String cameraId) {
        return filterOutputResolutions(Arrays.asList(ImageFormat.PRIVATE, ImageFormat.YUV_420_888));
    }

    @Override
    public Map<Integer, List<Size>> getSupportedCaptureOutputResolutions(String cameraId) {
        return filterOutputResolutions(Arrays.asList(ImageFormat.JPEG, ImageFormat.YUV_420_888));
    }

    @Override
    public Map<Integer, List<Size>> getSupportedPostviewResolutions(Size captureSize) {
        return new HashMap<>();
    }

    @Override
    public List<Size> getSupportedYuvAnalysisResolutions(
            String cameraId) {
        return null;
    }

    public class BaseAdvancedSessionProcessor implements SessionProcessorImpl {
        protected String TAG = "BaseAdvancedSessionProcessor";

        protected static final int DEFAULT_CAPTURE_ID = 0;
        protected static final int BASIC_CAPTURE_PROCESS_MAX_IMAGES = 3;
        protected static final int MAX_NUM_IMAGES = 1;

        protected Camera2OutputConfigImpl mPreviewOutputConfig;
        protected Camera2OutputConfigImpl mCaptureOutputConfig;

        protected OutputSurfaceImpl mPreviewOutputSurfaceConfig;
        protected OutputSurfaceImpl mCaptureOutputSurfaceConfig;

        protected final Object mLock = new Object();
        @GuardedBy("mLock")
        protected Map<CaptureRequest.Key<?>, Object> mParameters = new LinkedHashMap<>();

        protected final Object mLockCaptureSurfaceImageWriter = new Object();
        @GuardedBy("mLockCaptureSurfaceImageWriter")
        protected ImageWriter mCaptureSurfaceImageWriter;

        protected CaptureResultImageMatcher mImageCaptureCaptureResultImageMatcher =
                new CaptureResultImageMatcher();
        protected HashMap<Integer, Pair<ImageReferenceImpl, TotalCaptureResult>> mCaptureResults =
                new HashMap<>();
        protected RequestProcessorImpl mRequestProcessor;

        protected List<Integer> mCaptureIdList = List.of(DEFAULT_CAPTURE_ID);

        protected AtomicInteger mNextCaptureSequenceId = new AtomicInteger(1);

        protected void appendTag(String tag) {
            TAG += tag;
        }

        @Override
        @NonNull
        public Camera2SessionConfigImpl initSession(@NonNull String cameraId,
                @NonNull Map<String, CameraCharacteristics> cameraCharacteristicsMap,
                @NonNull Context context,
                @NonNull OutputSurfaceConfigurationImpl surfaceConfigs) {

            Log.d(TAG, "initSession cameraId=" + cameraId);

            mPreviewOutputSurfaceConfig = surfaceConfigs.getPreviewOutputSurface();
            mCaptureOutputSurfaceConfig = surfaceConfigs.getImageCaptureOutputSurface();

            Camera2SessionConfigImplBuilder builder =
                    new Camera2SessionConfigImplBuilder()
                    .setSessionTemplateId(CameraDevice.TEMPLATE_PREVIEW);

            // Preview
            if (mPreviewOutputSurfaceConfig.getSurface() != null) {
                Camera2OutputConfigImplBuilder previewOutputConfigBuilder;

                previewOutputConfigBuilder =
                        Camera2OutputConfigImplBuilder.newSurfaceConfig(
                            mPreviewOutputSurfaceConfig.getSurface());

                mPreviewOutputConfig = previewOutputConfigBuilder.build();

                builder.addOutputConfig(mPreviewOutputConfig);
            }

            // Image Capture
            if (mCaptureOutputSurfaceConfig.getSurface() != null) {
                Camera2OutputConfigImplBuilder captureOutputConfigBuilder;

                captureOutputConfigBuilder =
                        Camera2OutputConfigImplBuilder.newImageReaderConfig(
                                mCaptureOutputSurfaceConfig.getSize(),
                                ImageFormat.YUV_420_888,
                                BASIC_CAPTURE_PROCESS_MAX_IMAGES);

                mCaptureOutputConfig = captureOutputConfigBuilder.build();

                builder.addOutputConfig(mCaptureOutputConfig);
            }

            addSessionParameter(builder);

            return builder.build();
        }

        @Override
        public Camera2SessionConfigImpl initSession(@NonNull String cameraId,
                @NonNull Map<String, CameraCharacteristics> cameraCharacteristicsMap,
                @NonNull Context context,
                @NonNull OutputSurfaceImpl previewSurfaceConfig,
                @NonNull OutputSurfaceImpl imageCaptureSurfaceConfig,
                @Nullable OutputSurfaceImpl imageAnalysisSurfaceConfig) {

            // Since this sample impl uses version 1.4, the other initSession method will be
            // called. This is just a sample for earlier versions if wanting to redirect this call.
            OutputSurfaceConfigurationImplImpl surfaceConfigs =
                    new OutputSurfaceConfigurationImplImpl(previewSurfaceConfig,
                    imageCaptureSurfaceConfig, imageAnalysisSurfaceConfig,
                    null /*postviewSurfaceConfig*/);

            return initSession(cameraId, cameraCharacteristicsMap, context, surfaceConfigs);
        }

        protected void addSessionParameter(Camera2SessionConfigImplBuilder builder) {
            // default empty implementation
        }

        @Override
        public void deInitSession() {
            synchronized (mLockCaptureSurfaceImageWriter) {
                if (mCaptureSurfaceImageWriter != null) {
                    mCaptureSurfaceImageWriter.close();
                    mCaptureSurfaceImageWriter = null;
                }
            }
        }

        @Override
        public void setParameters(@NonNull Map<CaptureRequest.Key<?>, Object> parameters) {
            synchronized (mLock) {
                for (CaptureRequest.Key<?> key : parameters.keySet()) {
                    Object value = parameters.get(key);
                    if (value != null) {
                        mParameters.put(key, value);
                    }
                }
            }
        }

        protected void applyParameters(RequestBuilder builder) {
            synchronized (mLock) {
                for (CaptureRequest.Key<?> key : mParameters.keySet()) {
                    Object value = mParameters.get(key);
                    builder.setParameters(key, value);
                }
            }
        }

        protected void addTriggerRequestKeys(RequestBuilder builder,
                Map<CaptureRequest.Key<?>, Object> triggers) {
            HashSet<CaptureRequest.Key> supportedCaptureRequestKeys =
                    new HashSet<>(getAvailableCaptureRequestKeys());

            for (CaptureRequest.Key<?> key : triggers.keySet()) {
                if (supportedCaptureRequestKeys.contains(key)) {
                    Object value = triggers.get(key);
                    builder.setParameters(key, value);
                }
            }
        }

        @Override
        public int startTrigger(Map<CaptureRequest.Key<?>, Object> triggers,
                CaptureCallback captureCallback) {
            RequestBuilder builder = new RequestBuilder(mPreviewOutputConfig.getId(),
                    CameraDevice.TEMPLATE_PREVIEW, 0);
            addTriggerRequestKeys(builder, triggers);

            final int seqId = mNextCaptureSequenceId.getAndIncrement();

            RequestProcessorImpl.Callback callback = new RequestProcessorImpl.Callback() {
                @Override
                public void onCaptureStarted(RequestProcessorImpl.Request request, long frameNumber,
                        long timestamp) {
                    captureCallback.onCaptureStarted(seqId, timestamp);
                }

                @Override
                public void onCaptureProgressed(RequestProcessorImpl.Request request,
                        CaptureResult partialResult) {

                }

                @Override
                public void onCaptureCompleted(RequestProcessorImpl.Request request,
                        TotalCaptureResult totalCaptureResult) {
                    addCaptureResultKeys(seqId, totalCaptureResult, captureCallback);

                    captureCallback.onCaptureProcessStarted(seqId);
                }

                @Override
                public void onCaptureFailed(RequestProcessorImpl.Request request,
                        CaptureFailure captureFailure) {
                    captureCallback.onCaptureFailed(seqId);
                }

                @Override
                public void onCaptureBufferLost(RequestProcessorImpl.Request request,
                        long frameNumber, int outputStreamId) {
                    captureCallback.onCaptureFailed(seqId);
                }

                @Override
                public void onCaptureSequenceCompleted(int sequenceId, long frameNumber) {
                    captureCallback.onCaptureSequenceCompleted(seqId);
                }

                @Override
                public void onCaptureSequenceAborted(int sequenceId) {
                    captureCallback.onCaptureSequenceAborted(seqId);
                }
            };

            mRequestProcessor.submit(builder.build(), callback);

            return seqId;
        }

        @Override
        public void onCaptureSessionStart(@NonNull RequestProcessorImpl requestProcessor) {
            mRequestProcessor = requestProcessor;

            if (mCaptureOutputSurfaceConfig.getSurface() != null) {
                synchronized (mLockCaptureSurfaceImageWriter) {
                    if (JpegEncoder.imageFormatToPublic(mCaptureOutputSurfaceConfig
                            .getImageFormat()) == ImageFormat.JPEG) {
                        mCaptureSurfaceImageWriter = new ImageWriter
                                .Builder(mCaptureOutputSurfaceConfig.getSurface())
                                .setImageFormat(ImageFormat.JPEG)
                                .setMaxImages(MAX_NUM_IMAGES)
                                // For JPEG format, width x height should be set to (w*h) x 1
                                // since the JPEG image is returned as a 1D byte array
                                .setWidthAndHeight(mCaptureOutputSurfaceConfig.getSize().getWidth()
                                        * mCaptureOutputSurfaceConfig.getSize().getHeight(), 1)
                                .build();
                    } else {
                        mCaptureSurfaceImageWriter = new ImageWriter
                                .Builder(mCaptureOutputSurfaceConfig.getSurface())
                                .setImageFormat(mCaptureOutputSurfaceConfig.getImageFormat())
                                .setMaxImages(MAX_NUM_IMAGES)
                                .build();
                    }
                }
            }
        }

        @Override
        public void onCaptureSessionEnd() {
            synchronized (this) {
                mImageCaptureCaptureResultImageMatcher.clear();
            }

            mRequestProcessor = null;
        }

        @Override
        public int startRepeating(@NonNull CaptureCallback captureCallback) {
            RequestBuilder builder = new RequestBuilder(mPreviewOutputConfig.getId(),
                    CameraDevice.TEMPLATE_PREVIEW, 0);
            applyParameters(builder);
            final int seqId = mNextCaptureSequenceId.getAndIncrement();

            RequestProcessorImpl.Callback callback = new RequestProcessorImpl.Callback() {
                @Override
                public void onCaptureStarted(RequestProcessorImpl.Request request, long frameNumber,
                        long timestamp) {
                    captureCallback.onCaptureStarted(seqId, timestamp);
                }

                @Override
                public void onCaptureProgressed(RequestProcessorImpl.Request request,
                        CaptureResult partialResult) {

                }

                @Override
                public void onCaptureCompleted(RequestProcessorImpl.Request request,
                        TotalCaptureResult totalCaptureResult) {
                    addCaptureResultKeys(seqId, totalCaptureResult, captureCallback);

                    captureCallback.onCaptureProcessStarted(seqId);
                }

                @Override
                public void onCaptureFailed(RequestProcessorImpl.Request request,
                        CaptureFailure captureFailure) {
                    captureCallback.onCaptureFailed(seqId);
                }

                @Override
                public void onCaptureBufferLost(RequestProcessorImpl.Request request,
                        long frameNumber, int outputStreamId) {
                    captureCallback.onCaptureFailed(seqId);
                }

                @Override
                public void onCaptureSequenceCompleted(int sequenceId, long frameNumber) {
                    captureCallback.onCaptureSequenceCompleted(seqId);
                }

                @Override
                public void onCaptureSequenceAborted(int sequenceId) {
                    captureCallback.onCaptureSequenceAborted(seqId);
                }
            };

            mRequestProcessor.setRepeating(builder.build(), callback);

            return seqId;
        }

        protected void addCaptureResultKeys(
            @NonNull int seqId,
            @NonNull TotalCaptureResult result,
            @NonNull CaptureCallback captureCallback) {
            HashMap<CaptureResult.Key, Object> captureResults = new HashMap<>();

            Long shutterTimestamp = result.get(CaptureResult.SENSOR_TIMESTAMP);

            if (shutterTimestamp != null) {

                List<CaptureResult.Key> captureResultKeys = getAvailableCaptureResultKeys();
                for (CaptureResult.Key key : captureResultKeys) {
                    if (result.get(key) != null) {
                        captureResults.put(key, result.get(key));
                    }
                }

                captureCallback.onCaptureCompleted(shutterTimestamp, seqId,
                        captureResults);
            }
        }

        protected void addCaptureRequestParameters(List<RequestProcessorImpl.Request> requestList) {
            RequestBuilder build = new RequestBuilder(mCaptureOutputConfig.getId(),
                    CameraDevice.TEMPLATE_STILL_CAPTURE, DEFAULT_CAPTURE_ID);
            applyParameters(build);

            requestList.add(build.build());
        }

        @Override
        public int startCaptureWithPostview(@NonNull CaptureCallback captureCallback) {
            return startCapture(captureCallback);
        }

        @Override
        public int startCapture(@NonNull CaptureCallback captureCallback) {
            List<RequestProcessorImpl.Request> requestList = new ArrayList<>();
            addCaptureRequestParameters(requestList);
            final int seqId = mNextCaptureSequenceId.getAndIncrement();

            RequestProcessorImpl.Callback callback = new RequestProcessorImpl.Callback() {

                @Override
                public void onCaptureStarted(RequestProcessorImpl.Request request,
                        long frameNumber, long timestamp) {
                    captureCallback.onCaptureStarted(seqId, timestamp);
                }

                @Override
                public void onCaptureProgressed(RequestProcessorImpl.Request request,
                        CaptureResult partialResult) {

                }

                @Override
                public void onCaptureCompleted(RequestProcessorImpl.Request request,
                        TotalCaptureResult totalCaptureResult) {
                    RequestBuilder.RequestProcessorRequest requestProcessorRequest =
                            (RequestBuilder.RequestProcessorRequest) request;

                    addCaptureResultKeys(seqId, totalCaptureResult, captureCallback);

                    mImageCaptureCaptureResultImageMatcher.setCameraCaptureCallback(
                            totalCaptureResult,
                            requestProcessorRequest.getCaptureStageId());
                }

                @Override
                public void onCaptureFailed(RequestProcessorImpl.Request request,
                        CaptureFailure captureFailure) {
                    captureCallback.onCaptureFailed(seqId);
                }

                @Override
                public void onCaptureBufferLost(RequestProcessorImpl.Request request,
                        long frameNumber, int outputStreamId) {
                    captureCallback.onCaptureFailed(seqId);
                }

                @Override
                public void onCaptureSequenceCompleted(int sequenceId, long frameNumber) {
                    captureCallback.onCaptureSequenceCompleted(seqId);
                    captureCallback.onCaptureProcessProgressed(100);
                }

                @Override
                public void onCaptureSequenceAborted(int sequenceId) {
                    captureCallback.onCaptureSequenceAborted(seqId);
                }
            };

            Log.d(TAG, "startCapture");

            mRequestProcessor.submit(requestList, callback);

            if (mCaptureOutputSurfaceConfig.getSurface() != null) {
                mRequestProcessor.setImageProcessor(mCaptureOutputConfig.getId(),
                        new ImageProcessorImpl() {
                                @Override
                                public void onNextImageAvailable(int outputStreamId,
                                        long timestampNs,
                                        @NonNull ImageReferenceImpl imgReferenceImpl,
                                        @Nullable String physicalCameraId) {
                                    mImageCaptureCaptureResultImageMatcher
                                            .setInputImage(imgReferenceImpl);
                                }
                });

                mImageCaptureCaptureResultImageMatcher.setImageReferenceListener(
                        new CaptureResultImageMatcher.ImageReferenceListener() {
                                    @Override
                                    public void onImageReferenceIncoming(
                                            @NonNull ImageReferenceImpl imageReferenceImpl,
                                            @NonNull TotalCaptureResult totalCaptureResult,
                                            int captureId) {
                                        captureCallback.onCaptureProcessStarted(seqId);
                                        processImageCapture(imageReferenceImpl, totalCaptureResult,
                                                captureId);
                                    }
                });
            }

            return seqId;
        }

        protected void processImageCapture(@NonNull ImageReferenceImpl imageReferenceImpl,
                @NonNull TotalCaptureResult totalCaptureResult,
                int captureId) {

            mCaptureResults.put(captureId, new Pair<>(imageReferenceImpl, totalCaptureResult));

            if (mCaptureResults.keySet().containsAll(mCaptureIdList)) {
                List<Pair<ImageReferenceImpl, TotalCaptureResult>> imageDataPairs =
                        new ArrayList<>(mCaptureResults.values());

                Image resultImage = null;
                int captureSurfaceWriterImageFormat = ImageFormat.UNKNOWN;
                synchronized (mLockCaptureSurfaceImageWriter) {
                    resultImage = mCaptureSurfaceImageWriter.dequeueInputImage();
                    captureSurfaceWriterImageFormat = mCaptureSurfaceImageWriter.getFormat();
                }

                if (captureSurfaceWriterImageFormat == ImageFormat.JPEG) {
                    // Simple processing sample that encodes image from YUV to JPEG
                    Image yuvImage = imageDataPairs.get(DEFAULT_CAPTURE_ID).first.get();

                    Integer jpegOrientation = JPEG_DEFAULT_ROTATION;

                    synchronized (mLock) {
                        if (mParameters.get(CaptureRequest.JPEG_ORIENTATION) != null) {
                            jpegOrientation =
                                    (Integer) mParameters.get(CaptureRequest.JPEG_ORIENTATION);
                        }
                    }

                    JpegEncoder.encodeToJpeg(yuvImage, resultImage, jpegOrientation,
                            JPEG_DEFAULT_QUALITY);

                    resultImage.setTimestamp(yuvImage.getTimestamp());

                } else {
                    // Simple processing sample that transfers bytes and returns image as is
                    ByteBuffer yByteBuffer = resultImage.getPlanes()[0].getBuffer();
                    ByteBuffer uByteBuffer = resultImage.getPlanes()[2].getBuffer();
                    ByteBuffer vByteBuffer = resultImage.getPlanes()[1].getBuffer();

                    yByteBuffer.put(imageDataPairs.get(
                            DEFAULT_CAPTURE_ID).first.get().getPlanes()[0].getBuffer());
                    uByteBuffer.put(imageDataPairs.get(
                            DEFAULT_CAPTURE_ID).first.get().getPlanes()[2].getBuffer());
                    vByteBuffer.put(imageDataPairs.get(
                            DEFAULT_CAPTURE_ID).first.get().getPlanes()[1].getBuffer());

                    resultImage.setTimestamp(imageDataPairs.get(
                                DEFAULT_CAPTURE_ID).first.get().getTimestamp());
                }

                synchronized (mLockCaptureSurfaceImageWriter) {
                    mCaptureSurfaceImageWriter.queueInputImage(resultImage);
                }

                for (Pair<ImageReferenceImpl, TotalCaptureResult> val : mCaptureResults.values()) {
                    val.first.decrement();
                }
            } else {
                Log.w(TAG, "Unable to process, waiting for all images");
            }
        }

        @Override
        public void stopRepeating() {
            mRequestProcessor.stopRepeating();
        }

        @Override
        public void abortCapture(int captureSequenceId) {

        }

        @Override
        public Pair<Long, Long> getRealtimeCaptureLatency() {
            return null;
        }
    }

    public static class OutputSurfaceConfigurationImplImpl implements OutputSurfaceConfigurationImpl {
        private OutputSurfaceImpl mOutputPreviewSurfaceImpl;
        private OutputSurfaceImpl mOutputImageCaptureSurfaceImpl;
        private OutputSurfaceImpl mOutputImageAnalysisSurfaceImpl;
        private OutputSurfaceImpl mOutputPostviewSurfaceImpl;

        public OutputSurfaceConfigurationImplImpl(OutputSurfaceImpl previewSurfaceConfig,
                OutputSurfaceImpl imageCaptureSurfaceConfig,
                OutputSurfaceImpl imageAnalysisSurfaceConfig,
                OutputSurfaceImpl postviewSurfaceConfig) {
            mOutputPreviewSurfaceImpl = previewSurfaceConfig;
            mOutputImageCaptureSurfaceImpl = imageCaptureSurfaceConfig;
            mOutputImageAnalysisSurfaceImpl = imageAnalysisSurfaceConfig;
            mOutputPostviewSurfaceImpl = postviewSurfaceConfig;
        }

        @Override
        public OutputSurfaceImpl getPreviewOutputSurface() {
            return mOutputPreviewSurfaceImpl;
        }

        @Override
        public OutputSurfaceImpl getImageCaptureOutputSurface() {
            return mOutputImageCaptureSurfaceImpl;
        }

        @Override
        public OutputSurfaceImpl getImageAnalysisOutputSurface() {
            return mOutputImageAnalysisSurfaceImpl;
        }

        @Override
        public OutputSurfaceImpl getPostviewOutputSurface() {
            return mOutputPostviewSurfaceImpl;
        }
    }

    @Override
    public abstract SessionProcessorImpl createSessionProcessor();

    @Override
    public List<CaptureRequest.Key> getAvailableCaptureRequestKeys() {
        final CaptureRequest.Key [] CAPTURE_REQUEST_SET = {CaptureRequest.JPEG_QUALITY,
                CaptureRequest.JPEG_ORIENTATION};
        return Arrays.asList(CAPTURE_REQUEST_SET);
    }

    @Override
    public List<CaptureResult.Key> getAvailableCaptureResultKeys() {
        final CaptureResult.Key [] CAPTURE_RESULT_SET = {CaptureResult.JPEG_QUALITY,
                CaptureResult.JPEG_ORIENTATION};
        return Arrays.asList(CAPTURE_RESULT_SET);
    }

    @Override
    public boolean isCaptureProcessProgressAvailable() {
        return true;
    }

    @Override
    public boolean isPostviewAvailable() {
        return false;
    }
}
