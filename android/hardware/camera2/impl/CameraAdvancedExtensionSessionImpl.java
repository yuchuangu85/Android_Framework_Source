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

package android.hardware.camera2.impl;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraExtensionCharacteristics;
import android.hardware.camera2.CameraExtensionSession;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.extension.CameraOutputConfig;
import android.hardware.camera2.extension.CameraSessionConfig;
import android.hardware.camera2.extension.CaptureStageImpl;
import android.hardware.camera2.extension.IAdvancedExtenderImpl;
import android.hardware.camera2.extension.ICaptureCallback;
import android.hardware.camera2.extension.IImageProcessorImpl;
import android.hardware.camera2.extension.IInitializeSessionCallback;
import android.hardware.camera2.extension.IRequestCallback;
import android.hardware.camera2.extension.IRequestProcessorImpl;
import android.hardware.camera2.extension.ISessionProcessorImpl;
import android.hardware.camera2.extension.OutputConfigId;
import android.hardware.camera2.extension.OutputSurface;
import android.hardware.camera2.extension.ParcelCaptureResult;
import android.hardware.camera2.extension.ParcelImage;
import android.hardware.camera2.extension.ParcelTotalCaptureResult;
import android.hardware.camera2.extension.Request;
import android.hardware.camera2.params.ExtensionSessionConfiguration;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.hardware.camera2.utils.SurfaceUtils;
import android.media.Image;
import android.media.ImageReader;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

public final class CameraAdvancedExtensionSessionImpl extends CameraExtensionSession {
    private static final String TAG = "CameraAdvancedExtensionSessionImpl";

    private final Executor mExecutor;
    private final CameraDevice mCameraDevice;
    private final long mExtensionClientId;
    private final Handler mHandler;
    private final HandlerThread mHandlerThread;
    private final CameraExtensionSession.StateCallback mCallbacks;
    private final IAdvancedExtenderImpl mAdvancedExtender;
    // maps registered camera surfaces to extension output configs
    private final HashMap<Surface, CameraOutputConfig> mCameraConfigMap = new HashMap<>();
    // maps camera extension output ids to camera registered image readers
    private final HashMap<Integer, ImageReader> mReaderMap = new HashMap<>();
    private final RequestProcessor mRequestProcessor = new RequestProcessor();

    private Surface mClientRepeatingRequestSurface;
    private Surface mClientCaptureSurface;
    private CameraCaptureSession mCaptureSession = null;
    private ISessionProcessorImpl mSessionProcessor = null;
    private final InitializeSessionHandler mInitializeHandler;

    private boolean mInitialized;


    // Lock to synchronize cross-thread access to device public interface
    final Object mInterfaceLock = new Object(); // access from this class and Session only!

    /**
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.CAMERA)
    public static CameraAdvancedExtensionSessionImpl createCameraAdvancedExtensionSession(
            @NonNull CameraDevice cameraDevice, @NonNull Context ctx,
            @NonNull ExtensionSessionConfiguration config)
            throws CameraAccessException, RemoteException {
        long clientId = CameraExtensionCharacteristics.registerClient(ctx);
        if (clientId < 0) {
            throw new UnsupportedOperationException("Unsupported extension!");
        }

        String cameraId = cameraDevice.getId();
        CameraManager manager = ctx.getSystemService(CameraManager.class);
        CameraCharacteristics chars = manager.getCameraCharacteristics(cameraId);
        CameraExtensionCharacteristics extensionChars = new CameraExtensionCharacteristics(ctx,
                cameraId, chars);

        if (!CameraExtensionCharacteristics.isExtensionSupported(cameraDevice.getId(),
                config.getExtension(), chars)) {
            throw new UnsupportedOperationException("Unsupported extension type: " +
                    config.getExtension());
        }

        if (config.getOutputConfigurations().isEmpty() ||
                config.getOutputConfigurations().size() > 2) {
            throw new IllegalArgumentException("Unexpected amount of output surfaces, received: " +
                    config.getOutputConfigurations().size() + " expected <= 2");
        }

        int suitableSurfaceCount = 0;
        List<Size> supportedPreviewSizes = extensionChars.getExtensionSupportedSizes(
                config.getExtension(), SurfaceTexture.class);
        Surface repeatingRequestSurface = CameraExtensionUtils.getRepeatingRequestSurface(
                config.getOutputConfigurations(), supportedPreviewSizes);
        if (repeatingRequestSurface != null) {
            suitableSurfaceCount++;
        }

        HashMap<Integer, List<Size>> supportedCaptureSizes = new HashMap<>();
        for (int format : CameraExtensionUtils.SUPPORTED_CAPTURE_OUTPUT_FORMATS) {
            List<Size> supportedSizes = extensionChars.getExtensionSupportedSizes(
                    config.getExtension(), format);
            if (supportedSizes != null) {
                supportedCaptureSizes.put(format, supportedSizes);
            }
        }
        Surface burstCaptureSurface = CameraExtensionUtils.getBurstCaptureSurface(
                config.getOutputConfigurations(), supportedCaptureSizes);
        if (burstCaptureSurface != null) {
            suitableSurfaceCount++;
        }

        if (suitableSurfaceCount != config.getOutputConfigurations().size()) {
            throw new IllegalArgumentException("One or more unsupported output surfaces found!");
        }

        IAdvancedExtenderImpl extender = CameraExtensionCharacteristics.initializeAdvancedExtension(
                config.getExtension());
        extender.init(cameraId);

        CameraAdvancedExtensionSessionImpl ret = new CameraAdvancedExtensionSessionImpl(clientId,
                extender, cameraDevice, repeatingRequestSurface, burstCaptureSurface,
                config.getStateCallback(), config.getExecutor());
        ret.initialize();

        return ret;
    }

    private CameraAdvancedExtensionSessionImpl(long extensionClientId,
            @NonNull IAdvancedExtenderImpl extender, @NonNull CameraDevice cameraDevice,
            @Nullable Surface repeatingRequestSurface, @Nullable Surface burstCaptureSurface,
            @NonNull CameraExtensionSession.StateCallback callback, @NonNull Executor executor) {
        mExtensionClientId = extensionClientId;
        mAdvancedExtender = extender;
        mCameraDevice = cameraDevice;
        mCallbacks = callback;
        mExecutor = executor;
        mClientRepeatingRequestSurface = repeatingRequestSurface;
        mClientCaptureSurface = burstCaptureSurface;
        mHandlerThread = new HandlerThread(TAG);
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());
        mInitialized = false;
        mInitializeHandler = new InitializeSessionHandler();
    }

    /**
     * @hide
     */
    public synchronized void initialize() throws CameraAccessException, RemoteException {
        if (mInitialized) {
            Log.d(TAG, "Session already initialized");
            return;
        }

        OutputSurface previewSurface = initializeParcelable(mClientRepeatingRequestSurface);
        OutputSurface captureSurface = initializeParcelable(mClientCaptureSurface);
        mSessionProcessor = mAdvancedExtender.getSessionProcessor();
        CameraSessionConfig sessionConfig = mSessionProcessor.initSession(mCameraDevice.getId(),
                previewSurface, captureSurface);
        List<CameraOutputConfig> outputConfigs = sessionConfig.outputConfigs;
        // map camera output ids to output configurations
        HashMap<Integer, OutputConfiguration> cameraOutputs = new HashMap<>();
        for (CameraOutputConfig output : outputConfigs) {
            OutputConfiguration cameraOutput = null;
            switch(output.type) {
                case CameraOutputConfig.TYPE_SURFACE:
                    if (output.surface == null) {
                        Log.w(TAG, "Unsupported client output id: " + output.outputId.id +
                                ", skipping!");
                        continue;
                    }
                    cameraOutput = new OutputConfiguration(output.surfaceGroupId,
                            output.surface);
                    break;
                case CameraOutputConfig.TYPE_IMAGEREADER:
                    if ((output.imageFormat == ImageFormat.UNKNOWN) || (output.size.width <= 0) ||
                            (output.size.height <= 0)) {
                        Log.w(TAG, "Unsupported client output id: " + output.outputId.id +
                                ", skipping!");
                        continue;
                    }
                    ImageReader reader = ImageReader.newInstance(output.size.width,
                            output.size.height, output.imageFormat, output.capacity);
                    mReaderMap.put(output.outputId.id, reader);
                    cameraOutput = new OutputConfiguration(output.surfaceGroupId,
                            reader.getSurface());
                    break;
                case CameraOutputConfig.TYPE_MULTIRES_IMAGEREADER:
                    // Support for multi-resolution outputs to be added in future releases
                default:
                    throw new IllegalArgumentException("Unsupported output config type: " +
                            output.type);
            }
            cameraOutput.setPhysicalCameraId(output.physicalCameraId);
            cameraOutputs.put(output.outputId.id, cameraOutput);
        }

        ArrayList<OutputConfiguration> outputList = new ArrayList<>();
        for (CameraOutputConfig output : outputConfigs) {
            if (!cameraOutputs.containsKey(output.outputId.id)) {
                // Shared surface already removed by a previous iteration
                continue;
            }
            OutputConfiguration outConfig = cameraOutputs.get(output.outputId.id);
            if ((output.surfaceSharingOutputConfigs != null) &&
                    !output.surfaceSharingOutputConfigs.isEmpty()) {
                outConfig.enableSurfaceSharing();
                for (OutputConfigId outputId : output.surfaceSharingOutputConfigs) {
                    outConfig.addSurface(cameraOutputs.get(outputId.id).getSurface());
                    cameraOutputs.remove(outputId.id);
                }
            }
            outputList.add(outConfig);
            mCameraConfigMap.put(outConfig.getSurface(), output);
        }

        SessionConfiguration sessionConfiguration = new SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR, outputList,
                new CameraExtensionUtils.HandlerExecutor(mHandler), new SessionStateHandler());

        if ((sessionConfig.sessionParameter != null) &&
                (!sessionConfig.sessionParameter.isEmpty())) {
            CaptureRequest.Builder requestBuilder = mCameraDevice.createCaptureRequest(
                    sessionConfig.sessionTemplateId);
            CaptureRequest sessionRequest = requestBuilder.build();
            CameraMetadataNative.update(sessionRequest.getNativeMetadata(),
                    sessionConfig.sessionParameter);
            sessionConfiguration.setSessionParameters(sessionRequest);
        }

        mCameraDevice.createCaptureSession(sessionConfiguration);
    }

    private static ParcelCaptureResult initializeParcelable(CaptureResult result) {
        ParcelCaptureResult ret = new ParcelCaptureResult();
        ret.cameraId = result.getCameraId();
        ret.results = result.getNativeMetadata();
        ret.parent = result.getRequest();
        ret.sequenceId = result.getSequenceId();
        ret.frameNumber = result.getFrameNumber();

        return ret;
    }

    private static ParcelTotalCaptureResult initializeParcelable(TotalCaptureResult totalResult) {
        ParcelTotalCaptureResult ret = new ParcelTotalCaptureResult();
        ret.logicalCameraId = totalResult.getCameraId();
        ret.results = totalResult.getNativeMetadata();
        ret.parent = totalResult.getRequest();
        ret.sequenceId = totalResult.getSequenceId();
        ret.frameNumber = totalResult.getFrameNumber();
        ret.sessionId = totalResult.getSessionId();
        ret.partials = new ArrayList<>(totalResult.getPartialResults().size());
        for (CaptureResult partial : totalResult.getPartialResults()) {
            ret.partials.add(initializeParcelable(partial));
        }
        Map<String, TotalCaptureResult> physicalResults =
                totalResult.getPhysicalCameraTotalResults();
        ret.physicalResult = new ArrayList<>(physicalResults.size());
        for (TotalCaptureResult physicalResult : physicalResults.values()) {
            ret.physicalResult.add(new PhysicalCaptureResultInfo(physicalResult.getCameraId(),
                    physicalResult.getNativeMetadata()));
        }

        return ret;
    }

    private static OutputSurface initializeParcelable(Surface s) {
        OutputSurface ret = new OutputSurface();
        if (s != null) {
            ret.surface = s;
            ret.size = new android.hardware.camera2.extension.Size();
            Size surfaceSize = SurfaceUtils.getSurfaceSize(s);
            ret.size.width = surfaceSize.getWidth();
            ret.size.height = surfaceSize.getHeight();
            ret.imageFormat = SurfaceUtils.getSurfaceFormat(s);
        } else {
            ret.surface = null;
            ret.size = new android.hardware.camera2.extension.Size();
            ret.size.width = -1;
            ret.size.height = -1;
            ret.imageFormat = ImageFormat.UNKNOWN;
        }

        return ret;
    }

    @Override
    public @NonNull CameraDevice getDevice() {
        synchronized (mInterfaceLock) {
            return mCameraDevice;
        }
    }

    @Override
    public int setRepeatingRequest(@NonNull CaptureRequest request, @NonNull Executor executor,
            @NonNull ExtensionCaptureCallback listener) throws CameraAccessException {
        int seqId = -1;
        synchronized (mInterfaceLock) {
            if (!mInitialized) {
                throw new IllegalStateException("Uninitialized component");
            }

            if (mClientRepeatingRequestSurface == null) {
                throw new IllegalArgumentException("No registered preview surface");
            }

            if (!request.containsTarget(mClientRepeatingRequestSurface) ||
                    (request.getTargets().size() != 1)) {
                throw new IllegalArgumentException("Invalid repeating request output target!");
            }

            try {
                seqId = mSessionProcessor.startRepeating(new RequestCallbackHandler(request,
                        executor, listener));
            } catch (RemoteException e) {
                throw new CameraAccessException(CameraAccessException.CAMERA_ERROR,
                        "Failed to enable repeating request, extension service failed to respond!");
            }
        }

        return seqId;
    }

    @Override
    public int capture(@NonNull CaptureRequest request,
            @NonNull Executor executor,
            @NonNull ExtensionCaptureCallback listener) throws CameraAccessException {
        int seqId = -1;
        synchronized (mInterfaceLock) {
            if (!mInitialized) {
                throw new IllegalStateException("Uninitialized component");
            }

            if (mClientCaptureSurface == null) {
                throw new IllegalArgumentException("No output surface registered for single"
                        + " requests!");
            }

            if (!request.containsTarget(mClientCaptureSurface) ||
                    (request.getTargets().size() != 1)) {
                throw new IllegalArgumentException("Invalid single capture output target!");
            }

            try {
                // This will override the extension capture stage jpeg parameters with the user set
                // jpeg quality and rotation. This will guarantee that client configured jpeg
                // parameters always have highest priority.
                Integer jpegRotation = request.get(CaptureRequest.JPEG_ORIENTATION);
                if (jpegRotation == null) {
                    jpegRotation = CameraExtensionUtils.JPEG_DEFAULT_ROTATION;
                }
                Byte jpegQuality = request.get(CaptureRequest.JPEG_QUALITY);
                if (jpegQuality == null) {
                    jpegQuality = CameraExtensionUtils.JPEG_DEFAULT_QUALITY;
                }

                seqId = mSessionProcessor.startCapture(new RequestCallbackHandler(request,
                        executor, listener), jpegRotation, jpegQuality);
            } catch (RemoteException e) {
                throw new CameraAccessException(CameraAccessException.CAMERA_ERROR,
                        "Failed to submit capture request, extension service failed to respond!");
            }
        }

        return seqId;
    }

    @Override
    public void stopRepeating() throws CameraAccessException {
        synchronized (mInterfaceLock) {
            if (!mInitialized) {
                throw new IllegalStateException("Uninitialized component");
            }

            mCaptureSession.stopRepeating();

            try {
                mSessionProcessor.stopRepeating();
            } catch (RemoteException e) {
               throw new CameraAccessException(CameraAccessException.CAMERA_ERROR,
                       "Failed to notify about the end of repeating request, extension service"
                               + " failed to respond!");
            }
        }
    }

    @Override
    public void close() throws CameraAccessException {
        synchronized (mInterfaceLock) {
            if (mInitialized) {
                try {
                    mCaptureSession.stopRepeating();
                    mSessionProcessor.stopRepeating();
                    mSessionProcessor.onCaptureSessionEnd();
                } catch (RemoteException e) {
                    Log.e(TAG, "Failed to stop the repeating request or end the session,"
                            + " , extension service does not respond!") ;
                }
                mCaptureSession.close();
            }
        }
    }

    public void release(boolean skipCloseNotification) {
        boolean notifyClose = false;

        synchronized (mInterfaceLock) {
            mHandlerThread.quitSafely();

            if (mSessionProcessor != null) {
                try {
                    mSessionProcessor.deInitSession();
                } catch (RemoteException e) {
                    Log.e(TAG, "Failed to de-initialize session processor, extension service"
                            + " does not respond!") ;
                }
                mSessionProcessor = null;
            }

            if (mExtensionClientId >= 0) {
                CameraExtensionCharacteristics.unregisterClient(mExtensionClientId);
                if (mInitialized) {
                    notifyClose = true;
                    CameraExtensionCharacteristics.releaseSession();
                }
            }
            mInitialized = false;

            for (ImageReader reader : mReaderMap.values()) {
                reader.close();
            }
            mReaderMap.clear();

            mClientRepeatingRequestSurface = null;
            mClientCaptureSurface = null;
        }

        if (notifyClose && !skipCloseNotification) {
            final long ident = Binder.clearCallingIdentity();
            try {
                mExecutor.execute(() -> mCallbacks.onClosed(
                        CameraAdvancedExtensionSessionImpl.this));
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }

    private void notifyConfigurationFailure() {
        synchronized (mInterfaceLock) {
            if (mInitialized) {
                return;
            }
        }

        release(true /*skipCloseNotification*/);

        final long ident = Binder.clearCallingIdentity();
        try {
            mExecutor.execute(
                    () -> mCallbacks.onConfigureFailed(
                            CameraAdvancedExtensionSessionImpl.this));
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private class SessionStateHandler extends
            android.hardware.camera2.CameraCaptureSession.StateCallback {
        @Override
        public void onClosed(@NonNull CameraCaptureSession session) {
            release(false /*skipCloseNotification*/);
        }

        @Override
        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
            notifyConfigurationFailure();
        }

        @Override
        public void onConfigured(@NonNull CameraCaptureSession session) {
            synchronized (mInterfaceLock) {
                mCaptureSession = session;
                try {
                    CameraExtensionCharacteristics.initializeSession(mInitializeHandler);
                } catch (RemoteException e) {
                    Log.e(TAG, "Failed to initialize session! Extension service does"
                            + " not respond!");
                    notifyConfigurationFailure();
                }
            }
        }
    }

    private class InitializeSessionHandler extends IInitializeSessionCallback.Stub {
        @Override
        public void onSuccess() {
            boolean status = true;
            synchronized (mInterfaceLock) {
                try {
                    mSessionProcessor.onCaptureSessionStart(mRequestProcessor);
                    mInitialized = true;
                } catch (RemoteException e) {
                    Log.e(TAG, "Failed to start capture session,"
                            + " extension service does not respond!");
                    status = false;
                    mCaptureSession.close();
                }
            }

            if (status) {
                final long ident = Binder.clearCallingIdentity();
                try {
                    mExecutor.execute(
                            () -> mCallbacks.onConfigured(CameraAdvancedExtensionSessionImpl.this));
                } finally {
                    Binder.restoreCallingIdentity(ident);
                }
            } else {
                notifyConfigurationFailure();
            }
        }

        @Override
        public void onFailure() {
            mCaptureSession.close();
            Log.e(TAG, "Failed to initialize proxy service session!"
                    + " This can happen when trying to configure multiple "
                    + "concurrent extension sessions!");
            notifyConfigurationFailure();
        }
    }

    private final class RequestCallbackHandler extends ICaptureCallback.Stub {
        private final CaptureRequest mClientRequest;
        private final Executor mClientExecutor;
        private final ExtensionCaptureCallback mClientCallbacks;

        private RequestCallbackHandler(@NonNull CaptureRequest clientRequest,
                @NonNull Executor clientExecutor,
                @NonNull ExtensionCaptureCallback clientCallbacks) {
            mClientRequest = clientRequest;
            mClientExecutor = clientExecutor;
            mClientCallbacks = clientCallbacks;
        }

        @Override
        public void onCaptureStarted(int captureSequenceId, long timestamp) {
            final long ident = Binder.clearCallingIdentity();
            try {
                mClientExecutor.execute(
                        () -> mClientCallbacks.onCaptureStarted(
                                CameraAdvancedExtensionSessionImpl.this, mClientRequest,
                                timestamp));
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override
        public void onCaptureProcessStarted(int captureSequenceId) {
            final long ident = Binder.clearCallingIdentity();
            try {
                mClientExecutor.execute(
                        () -> mClientCallbacks.onCaptureProcessStarted(
                                CameraAdvancedExtensionSessionImpl.this, mClientRequest));
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override
        public void onCaptureFailed(int captureSequenceId) {
            final long ident = Binder.clearCallingIdentity();
            try {
                mClientExecutor.execute(
                        () -> mClientCallbacks.onCaptureFailed(
                                CameraAdvancedExtensionSessionImpl.this, mClientRequest));
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override
        public void onCaptureSequenceCompleted(int captureSequenceId) {
            final long ident = Binder.clearCallingIdentity();
            try {
                mClientExecutor.execute(
                        () -> mClientCallbacks.onCaptureSequenceCompleted(
                                CameraAdvancedExtensionSessionImpl.this, captureSequenceId));
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override
        public void onCaptureSequenceAborted(int captureSequenceId) {
            final long ident = Binder.clearCallingIdentity();
            try {
                mClientExecutor.execute(
                        () -> mClientCallbacks.onCaptureSequenceAborted(
                                CameraAdvancedExtensionSessionImpl.this, captureSequenceId));
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }

    private final class CaptureCallbackHandler extends CameraCaptureSession.CaptureCallback {
        private final IRequestCallback mCallback;

        public CaptureCallbackHandler(IRequestCallback callback) {
            mCallback = callback;
        }

        @Override
        public void onCaptureBufferLost(CameraCaptureSession session, CaptureRequest request,
                Surface target, long frameNumber) {
            try {
                if (request.getTag() instanceof Integer) {
                    Integer requestId = (Integer) request.getTag();
                    mCallback.onCaptureBufferLost(requestId, frameNumber,
                            mCameraConfigMap.get(target).outputId.id);
                } else {
                    Log.e(TAG, "Invalid capture request tag!");
                }
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to notify lost capture buffer, extension service doesn't"
                        + " respond!");
            }
        }

        @Override
        public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request,
                TotalCaptureResult result) {
            try {
                if (request.getTag() instanceof Integer) {
                    Integer requestId = (Integer) request.getTag();
                    mCallback.onCaptureCompleted(requestId, initializeParcelable(result));
                } else {
                    Log.e(TAG, "Invalid capture request tag!");
                }
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to notify capture result, extension service doesn't"
                        + " respond!");
            }
        }

        @Override
        public void onCaptureFailed(CameraCaptureSession session, CaptureRequest request,
                CaptureFailure failure) {
            try {
                if (request.getTag() instanceof Integer) {
                    Integer requestId = (Integer) request.getTag();
                    android.hardware.camera2.extension.CaptureFailure captureFailure =
                            new android.hardware.camera2.extension.CaptureFailure();
                    captureFailure.request = request;
                    captureFailure.reason = failure.getReason();
                    captureFailure.errorPhysicalCameraId = failure.getPhysicalCameraId();
                    captureFailure.frameNumber = failure.getFrameNumber();
                    captureFailure.sequenceId = failure.getSequenceId();
                    captureFailure.dropped = !failure.wasImageCaptured();
                    mCallback.onCaptureFailed(requestId, captureFailure);
                } else {
                    Log.e(TAG, "Invalid capture request tag!");
                }
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to notify capture failure, extension service doesn't"
                        + " respond!");
            }
        }

        @Override
        public void onCaptureProgressed(CameraCaptureSession session, CaptureRequest request,
                CaptureResult partialResult) {
            try {
                if (request.getTag() instanceof Integer) {
                    Integer requestId = (Integer) request.getTag();
                    mCallback.onCaptureProgressed(requestId, initializeParcelable(partialResult));
                } else {
                    Log.e(TAG, "Invalid capture request tag!");
                }
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to notify capture partial result, extension service doesn't"
                        + " respond!");
            }
        }

        @Override
        public void onCaptureSequenceAborted(CameraCaptureSession session, int sequenceId) {
            try {
                mCallback.onCaptureSequenceAborted(sequenceId);
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to notify aborted sequence, extension service doesn't"
                        + " respond!");
            }
        }

        @Override
        public void onCaptureSequenceCompleted(CameraCaptureSession session, int sequenceId,
                long frameNumber) {
            try {
                mCallback.onCaptureSequenceCompleted(sequenceId, frameNumber);
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to notify sequence complete, extension service doesn't"
                        + " respond!");
            }
        }

        @Override
        public void onCaptureStarted(CameraCaptureSession session, CaptureRequest request,
                long timestamp, long frameNumber) {
            try {
                if (request.getTag() instanceof Integer) {
                    Integer requestId = (Integer) request.getTag();
                    mCallback.onCaptureStarted(requestId, frameNumber, timestamp);
                } else {
                    Log.e(TAG, "Invalid capture request tag!");
                }
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to notify capture started, extension service doesn't"
                        + " respond!");
            }
        }
    }

    private static final class ImageReaderHandler implements ImageReader.OnImageAvailableListener {
        private final OutputConfigId mOutputConfigId;
        private final IImageProcessorImpl mIImageProcessor;
        private final String mPhysicalCameraId;

        private ImageReaderHandler(int outputConfigId,
                IImageProcessorImpl iImageProcessor, String physicalCameraId) {
            mOutputConfigId = new OutputConfigId();
            mOutputConfigId.id = outputConfigId;
            mIImageProcessor = iImageProcessor;
            mPhysicalCameraId = physicalCameraId;
        }

        @Override
        public void onImageAvailable(ImageReader reader) {
            if (mIImageProcessor == null) {
                return;
            }

            Image img;
            try {
                img = reader.acquireNextImage();
            } catch (IllegalStateException e) {
                Log.e(TAG, "Failed to acquire image, too many images pending!");
                return;
            }
            if (img == null) {
                Log.e(TAG, "Invalid image!");
                return;
            }

            try {
                reader.detachImage(img);
            } catch(Exception e) {
                Log.e(TAG, "Failed to detach image");
                img.close();
                return;
            }

            ParcelImage parcelImage = new ParcelImage();
            parcelImage.buffer = img.getHardwareBuffer();
            if (img.getFenceFd() >= 0) {
                try {
                    parcelImage.fence = ParcelFileDescriptor.fromFd(img.getFenceFd());
                } catch (IOException e) {
                    Log.e(TAG,"Failed to parcel buffer fence!");
                }
            }
            parcelImage.width = img.getWidth();
            parcelImage.height = img.getHeight();
            parcelImage.format = img.getFormat();
            parcelImage.timestamp = img.getTimestamp();
            parcelImage.transform = img.getTransform();
            parcelImage.scalingMode = img.getScalingMode();
            parcelImage.planeCount = img.getPlaneCount();
            parcelImage.crop = img.getCropRect();

            try {
                mIImageProcessor.onNextImageAvailable(mOutputConfigId, parcelImage,
                        mPhysicalCameraId);
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to propagate image buffer on output surface id: " +
                        mOutputConfigId + " extension service does not respond!");
            } finally {
                parcelImage.buffer.close();
                img.close();
            }
        }
    }

    private final class RequestProcessor extends IRequestProcessorImpl.Stub {
        @Override
        public void setImageProcessor(OutputConfigId outputConfigId,
                IImageProcessorImpl imageProcessor) {
            synchronized (mInterfaceLock) {
                if (mReaderMap.containsKey(outputConfigId.id)) {
                    ImageReader reader = mReaderMap.get(outputConfigId.id);
                    String physicalCameraId = null;
                    if (mCameraConfigMap.containsKey(reader.getSurface())) {
                        physicalCameraId =
                                mCameraConfigMap.get(reader.getSurface()).physicalCameraId;
                        reader.setOnImageAvailableListener(new ImageReaderHandler(outputConfigId.id,
                                    imageProcessor, physicalCameraId), mHandler);
                    } else {
                        Log.e(TAG, "Camera output configuration for ImageReader with " +
                                        " config Id " + outputConfigId.id + " not found!");
                    }
                } else {
                    Log.e(TAG, "ImageReader with output config id: " + outputConfigId.id +
                            " not found!");
                }
            }
        }

        @Override
        public int submit(Request request, IRequestCallback callback) {
            ArrayList<Request> captureList = new ArrayList<>();
            captureList.add(request);
            return submitBurst(captureList, callback);
        }

        @Override
        public int submitBurst(List<Request> requests, IRequestCallback callback) {
            int seqId = -1;
            synchronized (mInterfaceLock) {
                try {
                    CaptureCallbackHandler captureCallback = new CaptureCallbackHandler(callback);
                    ArrayList<CaptureRequest> captureRequests = new ArrayList<>();
                    for (Request request : requests) {
                        captureRequests.add(initializeCaptureRequest(mCameraDevice, request,
                                mCameraConfigMap));
                    }
                    seqId = mCaptureSession.captureBurstRequests(captureRequests,
                            new CameraExtensionUtils.HandlerExecutor(mHandler), captureCallback);
                } catch (CameraAccessException e) {
                    Log.e(TAG, "Failed to submit capture requests!");
                } catch (IllegalStateException e) {
                    Log.e(TAG, "Capture session closed!");
                }
            }

            return seqId;
        }

        @Override
        public int setRepeating(Request request, IRequestCallback callback) {
            int seqId = -1;
            synchronized (mInterfaceLock) {
                try {
                    CaptureRequest repeatingRequest = initializeCaptureRequest(mCameraDevice,
                                request, mCameraConfigMap);
                    CaptureCallbackHandler captureCallback = new CaptureCallbackHandler(callback);
                    seqId = mCaptureSession.setSingleRepeatingRequest(repeatingRequest,
                            new CameraExtensionUtils.HandlerExecutor(mHandler), captureCallback);
                } catch (CameraAccessException e) {
                    Log.e(TAG, "Failed to enable repeating request!");
                } catch (IllegalStateException e) {
                    Log.e(TAG, "Capture session closed!");
                }
            }

            return seqId;
        }

        @Override
        public void abortCaptures() {
            synchronized (mInterfaceLock) {
                try {
                    mCaptureSession.abortCaptures();
                } catch (CameraAccessException e) {
                    Log.e(TAG, "Failed during capture abort!");
                } catch (IllegalStateException e) {
                    Log.e(TAG, "Capture session closed!");
                }
            }
        }

        @Override
        public void stopRepeating() {
            synchronized (mInterfaceLock) {
                try {
                    mCaptureSession.stopRepeating();
                } catch (CameraAccessException e) {
                    Log.e(TAG, "Failed during repeating capture stop!");
                } catch (IllegalStateException e) {
                    Log.e(TAG, "Capture session closed!");
                }
            }
        }
    }

    private static CaptureRequest initializeCaptureRequest(CameraDevice cameraDevice,
            Request request, HashMap<Surface, CameraOutputConfig> surfaceIdMap)
            throws CameraAccessException {
        CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(request.templateId);
        for (OutputConfigId configId : request.targetOutputConfigIds) {
            boolean found = false;
            for (Map.Entry<Surface, CameraOutputConfig> entry : surfaceIdMap.entrySet()) {
                if (entry.getValue().outputId.id == configId.id) {
                    builder.addTarget(entry.getKey());
                    found = true;
                    break;
                }
            }

            if (!found) {
                Log.e(TAG, "Surface with output id: " + configId.id +
                        " not found among registered camera outputs!");
            }
        }

        builder.setTag(request.requestId);
        CaptureRequest ret = builder.build();
        CameraMetadataNative.update(ret.getNativeMetadata(), request.parameters);
        return ret;
    }
}
