/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.ex.camera2.portability;

import android.annotation.TargetApi;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.hardware.Camera.AutoFocusCallback;
import android.hardware.Camera.AutoFocusMoveCallback;
import android.hardware.Camera.ErrorCallback;
import android.hardware.Camera.FaceDetectionListener;
import android.hardware.Camera.OnZoomChangeListener;
import android.hardware.Camera.Parameters;
import android.hardware.Camera.PictureCallback;
import android.hardware.Camera.PreviewCallback;
import android.hardware.Camera.ShutterCallback;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.view.SurfaceHolder;

import com.android.ex.camera2.portability.debug.Log;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.StringTokenizer;

/**
 * A class to implement {@link CameraAgent} of the Android camera framework.
 */
class AndroidCameraAgentImpl extends CameraAgent {
    private static final Log.Tag TAG = new Log.Tag("AndCamAgntImp");

    private CameraDeviceInfo.Characteristics mCharacteristics;
    private AndroidCameraCapabilities mCapabilities;

    private final CameraHandler mCameraHandler;
    private final HandlerThread mCameraHandlerThread;
    private final CameraStateHolder mCameraState;
    private final DispatchThread mDispatchThread;

    private Handler mCameraExceptionCallbackHandler;
    private CameraExceptionCallback mCameraExceptionCallback =
        new CameraExceptionCallback() {
            @Override
            public void onCameraException(RuntimeException e) {
                throw e;
            }
        };

    AndroidCameraAgentImpl() {
        mCameraHandlerThread = new HandlerThread("Camera Handler Thread");
        mCameraHandlerThread.start();
        mCameraHandler = new CameraHandler(mCameraHandlerThread.getLooper());
        mCameraExceptionCallbackHandler = mCameraHandler;
        mCameraState = new AndroidCameraStateHolder();
        mDispatchThread = new DispatchThread(mCameraHandler, mCameraHandlerThread);
        mDispatchThread.start();
    }

    @Override
    public void setCameraDefaultExceptionCallback(CameraExceptionCallback callback,
            Handler handler) {
        synchronized (mCameraExceptionCallback) {
            mCameraExceptionCallback = callback;
            mCameraExceptionCallbackHandler = handler;
        }
    }

    @Override
    public void recycle() {
        closeCamera(null, true);
        mDispatchThread.end();
    }

    @Override
    public CameraDeviceInfo getCameraDeviceInfo() {
        return AndroidCameraDeviceInfo.create();
    }

    @Override
    protected Handler getCameraHandler() {
        return mCameraHandler;
    }

    @Override
    protected DispatchThread getDispatchThread() {
        return mDispatchThread;
    }

    private static class AndroidCameraDeviceInfo implements CameraDeviceInfo {
        private final Camera.CameraInfo[] mCameraInfos;
        private final int mNumberOfCameras;
        private final int mFirstBackCameraId;
        private final int mFirstFrontCameraId;

        private AndroidCameraDeviceInfo(Camera.CameraInfo[] info, int numberOfCameras,
                int firstBackCameraId, int firstFrontCameraId) {

            mCameraInfos = info;
            mNumberOfCameras = numberOfCameras;
            mFirstBackCameraId = firstBackCameraId;
            mFirstFrontCameraId = firstFrontCameraId;
        }

        public static AndroidCameraDeviceInfo create() {
            int numberOfCameras;
            Camera.CameraInfo[] cameraInfos;
            try {
                numberOfCameras = Camera.getNumberOfCameras();
                cameraInfos = new Camera.CameraInfo[numberOfCameras];
                for (int i = 0; i < numberOfCameras; i++) {
                    cameraInfos[i] = new Camera.CameraInfo();
                    Camera.getCameraInfo(i, cameraInfos[i]);
                }
            } catch (RuntimeException ex) {
                Log.e(TAG, "Exception while creating CameraDeviceInfo", ex);
                return null;
            }

            int firstFront = NO_DEVICE;
            int firstBack = NO_DEVICE;
            // Get the first (smallest) back and first front camera id.
            for (int i = numberOfCameras - 1; i >= 0; i--) {
                if (cameraInfos[i].facing == Camera.CameraInfo.CAMERA_FACING_BACK) {
                    firstBack = i;
                } else {
                    if (cameraInfos[i].facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                        firstFront = i;
                    }
                }
            }

            return new AndroidCameraDeviceInfo(cameraInfos, numberOfCameras, firstBack, firstFront);
        }

        @Override
        public Characteristics getCharacteristics(int cameraId) {
            Camera.CameraInfo info = mCameraInfos[cameraId];
            if (info != null) {
                return new AndroidCharacteristics(info);
            } else {
                return null;
            }
        }

        @Override
        public int getNumberOfCameras() {
            return mNumberOfCameras;
        }

        @Override
        public int getFirstBackCameraId() {
            return mFirstBackCameraId;
        }

        @Override
        public int getFirstFrontCameraId() {
            return mFirstFrontCameraId;
        }

        private static class AndroidCharacteristics extends Characteristics {
            private Camera.CameraInfo mCameraInfo;

            AndroidCharacteristics(Camera.CameraInfo cameraInfo) {
                mCameraInfo = cameraInfo;
            }

            @Override
            public boolean isFacingBack() {
                return mCameraInfo.facing == Camera.CameraInfo.CAMERA_FACING_BACK;
            }

            @Override
            public boolean isFacingFront() {
                return mCameraInfo.facing == Camera.CameraInfo.CAMERA_FACING_FRONT;
            }

            @Override
            public int getSensorOrientation() {
                return mCameraInfo.orientation;
            }

            @Override
            public boolean canDisableShutterSound() {
                return mCameraInfo.canDisableShutterSound;
            }
        }
    }

    private static class ParametersCache {
        private Parameters mParameters;
        private Camera mCamera;

        public ParametersCache(Camera camera) {
            mCamera = camera;
        }

        public synchronized void invalidate() {
            mParameters = null;
        }

        /**
         * Access parameters from the cache. If cache is empty, block by
         * retrieving parameters directly from Camera, but if cache is present,
         * returns immediately.
         */
        public synchronized Parameters getBlocking() {
            if (mParameters == null) {
                mParameters = mCamera.getParameters();
                if (mParameters == null) {
                    Log.e(TAG, "Camera object returned null parameters!");
                    throw new IllegalStateException("camera.getParameters returned null");
                }
            }
            return mParameters;
        }
    }

    /**
     * The handler on which the actual camera operations happen.
     */
    private class CameraHandler extends HistoryHandler {

        private Camera mCamera;
        private int mCameraId;
        private ParametersCache mParameterCache;
        private int mCancelAfPending = 0;

        private class CaptureCallbacks {
            public final ShutterCallback mShutter;
            public final PictureCallback mRaw;
            public final PictureCallback mPostView;
            public final PictureCallback mJpeg;

            CaptureCallbacks(ShutterCallback shutter, PictureCallback raw, PictureCallback postView,
                    PictureCallback jpeg) {
                mShutter = shutter;
                mRaw = raw;
                mPostView = postView;
                mJpeg = jpeg;
            }
        }

        CameraHandler(Looper looper) {
            super(looper);
        }

        private void startFaceDetection() {
            mCamera.startFaceDetection();
        }

        private void stopFaceDetection() {
            mCamera.stopFaceDetection();
        }

        private void setFaceDetectionListener(FaceDetectionListener listener) {
            mCamera.setFaceDetectionListener(listener);
        }

        private void setPreviewTexture(Object surfaceTexture) {
            try {
                mCamera.setPreviewTexture((SurfaceTexture) surfaceTexture);
            } catch (IOException e) {
                Log.e(TAG, "Could not set preview texture", e);
            }
        }

        @TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR1)
        private void enableShutterSound(boolean enable) {
            mCamera.enableShutterSound(enable);
        }

        @TargetApi(Build.VERSION_CODES.JELLY_BEAN)
        private void setAutoFocusMoveCallback(
                android.hardware.Camera camera, Object cb) {
            try {
                camera.setAutoFocusMoveCallback((AutoFocusMoveCallback) cb);
            } catch (RuntimeException ex) {
                Log.w(TAG, ex.getMessage());
            }
        }

        private void capture(final CaptureCallbacks cb) {
            try {
                mCamera.takePicture(cb.mShutter, cb.mRaw, cb.mPostView, cb.mJpeg);
            } catch (RuntimeException e) {
                // TODO: output camera state and focus state for debugging.
                Log.e(TAG, "take picture failed.");
                throw e;
            }
        }

        public void requestTakePicture(
                final ShutterCallback shutter,
                final PictureCallback raw,
                final PictureCallback postView,
                final PictureCallback jpeg) {
            final CaptureCallbacks callbacks = new CaptureCallbacks(shutter, raw, postView, jpeg);
            obtainMessage(CameraActions.CAPTURE_PHOTO, callbacks).sendToTarget();
        }

        /**
         * This method does not deal with the API level check.  Everyone should
         * check first for supported operations before sending message to this handler.
         */
        @Override
        public void handleMessage(final Message msg) {
            super.handleMessage(msg);
            Log.v(TAG, "handleMessage - action = '" + CameraActions.stringify(msg.what) + "'");
            try {
                switch (msg.what) {
                    case CameraActions.OPEN_CAMERA: {
                        final CameraOpenCallback openCallback = (CameraOpenCallback) msg.obj;
                        final int cameraId = msg.arg1;
                        if (mCameraState.getState() != AndroidCameraStateHolder.CAMERA_UNOPENED) {
                            openCallback.onDeviceOpenedAlready(cameraId, generateHistoryString(cameraId));
                            break;
                        }

                        Log.i(TAG, "Opening camera " + cameraId + " with camera1 API");
                        mCamera = android.hardware.Camera.open(cameraId);
                        if (mCamera != null) {
                            mCameraId = cameraId;
                            mParameterCache = new ParametersCache(mCamera);

                            mCharacteristics =
                                    AndroidCameraDeviceInfo.create().getCharacteristics(cameraId);
                            mCapabilities = new AndroidCameraCapabilities(
                                    mParameterCache.getBlocking());

                            mCameraState.setState(AndroidCameraStateHolder.CAMERA_IDLE);
                            if (openCallback != null) {
                                openCallback.onCameraOpened(
                                        new AndroidCameraProxyImpl(cameraId, mCamera,
                                                mCharacteristics, mCapabilities));
                            }
                        } else {
                            if (openCallback != null) {
                                openCallback.onDeviceOpenFailure(cameraId, generateHistoryString(cameraId));
                            }
                        }
                        break;
                    }

                    case CameraActions.RELEASE: {
                        if (mCamera != null) {
                            mCamera.release();
                            mCameraState.setState(AndroidCameraStateHolder.CAMERA_UNOPENED);
                            mCamera = null;
                        } else {
                            Log.w(TAG, "Releasing camera without any camera opened.");
                        }
                        break;
                    }

                    case CameraActions.RECONNECT: {
                        final CameraOpenCallbackForward cbForward =
                                (CameraOpenCallbackForward) msg.obj;
                        final int cameraId = msg.arg1;
                        try {
                            mCamera.reconnect();
                        } catch (IOException ex) {
                            if (cbForward != null) {
                                cbForward.onReconnectionFailure(AndroidCameraAgentImpl.this,
                                        generateHistoryString(mCameraId));
                            }
                            break;
                        }

                        mCameraState.setState(AndroidCameraStateHolder.CAMERA_IDLE);
                        if (cbForward != null) {
                            cbForward.onCameraOpened(
                                    new AndroidCameraProxyImpl(cameraId, mCamera, mCharacteristics,
                                            mCapabilities));
                        }
                        break;
                    }

                    case CameraActions.UNLOCK: {
                        mCamera.unlock();
                        mCameraState.setState(AndroidCameraStateHolder.CAMERA_UNLOCKED);
                        break;
                    }

                    case CameraActions.LOCK: {
                        mCamera.lock();
                        mCameraState.setState(AndroidCameraStateHolder.CAMERA_IDLE);
                        break;
                    }

                    // TODO: Lock the CameraSettings object's sizes
                    case CameraActions.SET_PREVIEW_TEXTURE_ASYNC: {
                        setPreviewTexture(msg.obj);
                        break;
                    }

                    case CameraActions.SET_PREVIEW_DISPLAY_ASYNC: {
                        try {
                            mCamera.setPreviewDisplay((SurfaceHolder) msg.obj);
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                        break;
                    }

                    case CameraActions.START_PREVIEW_ASYNC: {
                        final CameraStartPreviewCallbackForward cbForward =
                            (CameraStartPreviewCallbackForward) msg.obj;
                        mCamera.startPreview();
                        if (cbForward != null) {
                            cbForward.onPreviewStarted();
                        }
                        break;
                    }

                    // TODO: Unlock the CameraSettings object's sizes
                    case CameraActions.STOP_PREVIEW: {
                        mCamera.stopPreview();
                        break;
                    }

                    case CameraActions.SET_PREVIEW_CALLBACK_WITH_BUFFER: {
                        mCamera.setPreviewCallbackWithBuffer((PreviewCallback) msg.obj);
                        break;
                    }

                    case CameraActions.SET_ONE_SHOT_PREVIEW_CALLBACK: {
                        mCamera.setOneShotPreviewCallback((PreviewCallback) msg.obj);
                        break;
                    }

                    case CameraActions.ADD_CALLBACK_BUFFER: {
                        mCamera.addCallbackBuffer((byte[]) msg.obj);
                        break;
                    }

                    case CameraActions.AUTO_FOCUS: {
                        if (mCancelAfPending > 0) {
                            Log.v(TAG, "handleMessage - Ignored AUTO_FOCUS because there was "
                                    + mCancelAfPending + " pending CANCEL_AUTO_FOCUS messages");
                            break; // ignore AF because a CANCEL_AF is queued after this
                        }
                        mCameraState.setState(AndroidCameraStateHolder.CAMERA_FOCUSING);
                        mCamera.autoFocus((AutoFocusCallback) msg.obj);
                        break;
                    }

                    case CameraActions.CANCEL_AUTO_FOCUS: {
                        // Ignore all AFs that were already queued until we see
                        // a CANCEL_AUTO_FOCUS_FINISH
                        mCancelAfPending++;
                        mCamera.cancelAutoFocus();
                        mCameraState.setState(AndroidCameraStateHolder.CAMERA_IDLE);
                        break;
                    }

                    case CameraActions.CANCEL_AUTO_FOCUS_FINISH: {
                        // Stop ignoring AUTO_FOCUS messages unless there are additional
                        // CANCEL_AUTO_FOCUSes that were added
                        mCancelAfPending--;
                        break;
                    }

                    case CameraActions.SET_AUTO_FOCUS_MOVE_CALLBACK: {
                        setAutoFocusMoveCallback(mCamera, msg.obj);
                        break;
                    }

                    case CameraActions.SET_DISPLAY_ORIENTATION: {
                        // Update preview orientation
                        mCamera.setDisplayOrientation(
                                mCharacteristics.getPreviewOrientation(msg.arg1));
                        // Only set the JPEG capture orientation if requested to do so; otherwise,
                        // capture in the sensor's physical orientation. (e.g., JPEG rotation is
                        // necessary in auto-rotate mode.
                        Parameters parameters = mParameterCache.getBlocking();
                        parameters.setRotation(
                                msg.arg2 > 0 ? mCharacteristics.getJpegOrientation(msg.arg1) : 0);
                        mCamera.setParameters(parameters);
                        mParameterCache.invalidate();
                        break;
                    }

                    case CameraActions.SET_JPEG_ORIENTATION: {
                        Parameters parameters = mParameterCache.getBlocking();
                        parameters.setRotation(msg.arg1);
                        mCamera.setParameters(parameters);
                        mParameterCache.invalidate();
                        break;
                    }

                    case CameraActions.SET_ZOOM_CHANGE_LISTENER: {
                        mCamera.setZoomChangeListener((OnZoomChangeListener) msg.obj);
                        break;
                    }

                    case CameraActions.SET_FACE_DETECTION_LISTENER: {
                        setFaceDetectionListener((FaceDetectionListener) msg.obj);
                        break;
                    }

                    case CameraActions.START_FACE_DETECTION: {
                        startFaceDetection();
                        break;
                    }

                    case CameraActions.STOP_FACE_DETECTION: {
                        stopFaceDetection();
                        break;
                    }

                    case CameraActions.SET_ERROR_CALLBACK: {
                        mCamera.setErrorCallback((ErrorCallback) msg.obj);
                        break;
                    }

                    case CameraActions.APPLY_SETTINGS: {
                        Parameters parameters = mParameterCache.getBlocking();
                        CameraSettings settings = (CameraSettings) msg.obj;
                        applySettingsToParameters(settings, parameters);
                        mCamera.setParameters(parameters);
                        mParameterCache.invalidate();
                        break;
                    }

                    case CameraActions.SET_PARAMETERS: {
                        Parameters parameters = mParameterCache.getBlocking();
                        parameters.unflatten((String) msg.obj);
                        mCamera.setParameters(parameters);
                        mParameterCache.invalidate();
                        break;
                    }

                    case CameraActions.GET_PARAMETERS: {
                        Parameters[] parametersHolder = (Parameters[]) msg.obj;
                        Parameters parameters = mParameterCache.getBlocking();
                        parametersHolder[0] = parameters;
                        break;
                    }

                    case CameraActions.SET_PREVIEW_CALLBACK: {
                        mCamera.setPreviewCallback((PreviewCallback) msg.obj);
                        break;
                    }

                    case CameraActions.ENABLE_SHUTTER_SOUND: {
                        enableShutterSound((msg.arg1 == 1) ? true : false);
                        break;
                    }

                    case CameraActions.REFRESH_PARAMETERS: {
                        mParameterCache.invalidate();;
                        break;
                    }

                    case CameraActions.CAPTURE_PHOTO: {
                        mCameraState.setState(AndroidCameraStateHolder.CAMERA_CAPTURING);
                        capture((CaptureCallbacks) msg.obj);
                        break;
                    }

                    default: {
                        throw new RuntimeException("Invalid CameraProxy message=" + msg.what);
                    }
                }
            } catch (final RuntimeException e) {
                Log.e(TAG, "Exception during camera operation " + msg.what, e);
                if (msg.what != CameraActions.RELEASE && mCamera != null) {
                    try {
                        mCamera.release();
                        mCameraState.setState(AndroidCameraStateHolder.CAMERA_UNOPENED);
                    } catch (Exception ex) {
                        Log.e(TAG, "Fail to release the camera.");
                    }
                    mCamera = null;
                } else {
                    if (mCamera == null) {
                        if (msg.what == CameraActions.OPEN_CAMERA) {
                            final int cameraId = msg.arg1;
                            if (msg.obj != null) {
                                ((CameraOpenCallback) msg.obj).onDeviceOpenFailure(
                                        msg.arg1, generateHistoryString(cameraId));
                            }
                        } else {
                            Log.w(TAG, "Cannot handle message " + msg.what + ", mCamera is null.");
                        }
                        return;
                    }
                }
                synchronized (mCameraExceptionCallback) {
                    mCameraExceptionCallbackHandler.post(new Runnable() {
                        @Override
                        public void run() {
                                mCameraExceptionCallback.onCameraException(e);
                            }
                        });
                }
            } finally {
                WaitDoneBundle.unblockSyncWaiters(msg);
            }
        }

        private void applySettingsToParameters(final CameraSettings settings,
                final Parameters parameters) {
            final CameraCapabilities.Stringifier stringifier = mCapabilities.getStringifier();
            Size photoSize = settings.getCurrentPhotoSize();
            parameters.setPictureSize(photoSize.width(), photoSize.height());
            Size previewSize = settings.getCurrentPreviewSize();
            parameters.setPreviewSize(previewSize.width(), previewSize.height());
            if (settings.getPreviewFrameRate() == -1) {
                parameters.setPreviewFpsRange(settings.getPreviewFpsRangeMin(),
                        settings.getPreviewFpsRangeMax());
            } else {
                parameters.setPreviewFrameRate(settings.getPreviewFrameRate());
            }
            parameters.setPreviewFormat(settings.getCurrentPreviewFormat());
            parameters.setJpegQuality(settings.getPhotoJpegCompressionQuality());
            if (mCapabilities.supports(CameraCapabilities.Feature.ZOOM)) {
                parameters.setZoom(zoomRatioToIndex(settings.getCurrentZoomRatio(),
                        parameters.getZoomRatios()));
            }
            parameters.setExposureCompensation(settings.getExposureCompensationIndex());
            if (mCapabilities.supports(CameraCapabilities.Feature.AUTO_EXPOSURE_LOCK)) {
                parameters.setAutoExposureLock(settings.isAutoExposureLocked());
            }
            parameters.setFocusMode(stringifier.stringify(settings.getCurrentFocusMode()));
            if (mCapabilities.supports(CameraCapabilities.Feature.AUTO_WHITE_BALANCE_LOCK)) {
                parameters.setAutoWhiteBalanceLock(settings.isAutoWhiteBalanceLocked());
            }
            if (mCapabilities.supports(CameraCapabilities.Feature.FOCUS_AREA)) {
                if (settings.getFocusAreas().size() != 0) {
                    parameters.setFocusAreas(settings.getFocusAreas());
                } else {
                    parameters.setFocusAreas(null);
                }
            }
            if (mCapabilities.supports(CameraCapabilities.Feature.METERING_AREA)) {
                if (settings.getMeteringAreas().size() != 0) {
                    parameters.setMeteringAreas(settings.getMeteringAreas());
                } else {
                    parameters.setMeteringAreas(null);
                }
            }
            if (settings.getCurrentFlashMode() != CameraCapabilities.FlashMode.NO_FLASH) {
                parameters.setFlashMode(stringifier.stringify(settings.getCurrentFlashMode()));
            }
            if (settings.getCurrentSceneMode() != CameraCapabilities.SceneMode.NO_SCENE_MODE) {
                if (settings.getCurrentSceneMode() != null) {
                    parameters
                            .setSceneMode(stringifier.stringify(settings.getCurrentSceneMode()));
                }
            }
            parameters.setRecordingHint(settings.isRecordingHintEnabled());
            Size jpegThumbSize = settings.getExifThumbnailSize();
            parameters.setJpegThumbnailSize(jpegThumbSize.width(), jpegThumbSize.height());
            parameters.setPictureFormat(settings.getCurrentPhotoFormat());

            CameraSettings.GpsData gpsData = settings.getGpsData();
            if (gpsData == null) {
                parameters.removeGpsData();
            } else {
                parameters.setGpsTimestamp(gpsData.timeStamp);
                if (gpsData.processingMethod != null) {
                    // It's a hack since we always use GPS time stamp but does
                    // not use other fields sometimes. Setting processing
                    // method to null means the other fields should not be used.
                    parameters.setGpsAltitude(gpsData.altitude);
                    parameters.setGpsLatitude(gpsData.latitude);
                    parameters.setGpsLongitude(gpsData.longitude);
                    parameters.setGpsProcessingMethod(gpsData.processingMethod);
                }
            }

        }

        /**
         * @param ratio Desired zoom ratio, in [1.0f,+Inf).
         * @param percentages Available zoom ratios, as percentages.
         * @return Index of the closest corresponding ratio, rounded up toward
         *         that of the maximum available ratio.
         */
        private int zoomRatioToIndex(float ratio, List<Integer> percentages) {
            int percent = (int) (ratio * AndroidCameraCapabilities.ZOOM_MULTIPLIER);
            int index = Collections.binarySearch(percentages, percent);
            if (index >= 0) {
                // Found the desired ratio in the supported list
                return index;
            } else {
                // Didn't find an exact match. Where would it have been?
                index = -(index + 1);
                if (index == percentages.size()) {
                    // Put it back in bounds by setting to the maximum allowable zoom
                    --index;
                }
                return index;
            }
        }
    }

    /**
     * A class which implements {@link CameraAgent.CameraProxy} and
     * camera handler thread.
     */
    private class AndroidCameraProxyImpl extends CameraAgent.CameraProxy {
        private final int mCameraId;
        /* TODO: remove this Camera instance. */
        private final Camera mCamera;
        private final CameraDeviceInfo.Characteristics mCharacteristics;
        private final AndroidCameraCapabilities mCapabilities;

        private AndroidCameraProxyImpl(int cameraId, Camera camera,
                CameraDeviceInfo.Characteristics characteristics,
                AndroidCameraCapabilities capabilities) {
            mCamera = camera;
            mCameraId = cameraId;
            mCharacteristics = characteristics;
            mCapabilities = capabilities;
        }

        @Deprecated
        @Override
        public android.hardware.Camera getCamera() {
            return mCamera;
        }

        @Override
        public int getCameraId() {
            return mCameraId;
        }

        @Override
        public CameraDeviceInfo.Characteristics getCharacteristics() {
            return mCharacteristics;
        }

        @Override
        public CameraCapabilities getCapabilities() {
            return new AndroidCameraCapabilities(mCapabilities);
        }

        @Override
        public void setPreviewDataCallback(
                final Handler handler, final CameraPreviewDataCallback cb) {
            mDispatchThread.runJob(new Runnable() {
                @Override
                public void run() {
                    mCameraHandler.obtainMessage(CameraActions.SET_PREVIEW_CALLBACK,
                            PreviewCallbackForward.getNewInstance(
                                    handler, AndroidCameraProxyImpl.this, cb))
                            .sendToTarget();
                }
            });
        }

        @Override
        public void setOneShotPreviewCallback(final Handler handler,
                final CameraPreviewDataCallback cb) {
            mDispatchThread.runJob(new Runnable() {
                @Override
                public void run() {
                    mCameraHandler.obtainMessage(CameraActions.SET_ONE_SHOT_PREVIEW_CALLBACK,
                            PreviewCallbackForward
                                    .getNewInstance(handler, AndroidCameraProxyImpl.this, cb))
                            .sendToTarget();
                }
            });
        }

        @Override
        public void setPreviewDataCallbackWithBuffer(
                final Handler handler, final CameraPreviewDataCallback cb) {
            mDispatchThread.runJob(new Runnable() {
                @Override
                public void run() {
                    mCameraHandler.obtainMessage(CameraActions.SET_PREVIEW_CALLBACK_WITH_BUFFER,
                            PreviewCallbackForward
                                    .getNewInstance(handler, AndroidCameraProxyImpl.this, cb))
                            .sendToTarget();
                }
            });
        }

        @Override
        public void autoFocus(final Handler handler, final CameraAFCallback cb) {
            final AutoFocusCallback afCallback = new AutoFocusCallback() {
                @Override
                public void onAutoFocus(final boolean b, Camera camera) {
                    if (mCameraState.getState() != AndroidCameraStateHolder.CAMERA_FOCUSING) {
                        Log.w(TAG, "onAutoFocus callback returning when not focusing");
                    } else {
                        mCameraState.setState(AndroidCameraStateHolder.CAMERA_IDLE);
                    }
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            cb.onAutoFocus(b, AndroidCameraProxyImpl.this);
                        }
                    });
                }
            };
            mDispatchThread.runJob(new Runnable() {
                @Override
                public void run() {
                    mCameraState.waitForStates(AndroidCameraStateHolder.CAMERA_IDLE);
                    mCameraHandler.obtainMessage(CameraActions.AUTO_FOCUS, afCallback)
                            .sendToTarget();
                }
            });
        }

        @TargetApi(Build.VERSION_CODES.JELLY_BEAN)
        @Override
        public void setAutoFocusMoveCallback(
                final Handler handler, final CameraAFMoveCallback cb) {
            mDispatchThread.runJob(new Runnable() {
                @Override
                public void run() {
                    mCameraHandler.obtainMessage(CameraActions.SET_AUTO_FOCUS_MOVE_CALLBACK,
                            AFMoveCallbackForward.getNewInstance(
                                    handler, AndroidCameraProxyImpl.this, cb))
                            .sendToTarget();
                }
            });
        }

        @Override
        public void takePicture(
                final Handler handler, final CameraShutterCallback shutter,
                final CameraPictureCallback raw, final CameraPictureCallback post,
                final CameraPictureCallback jpeg) {
            final PictureCallback jpegCallback = new PictureCallback() {
                @Override
                public void onPictureTaken(final byte[] data, Camera camera) {
                    if (mCameraState.getState() != AndroidCameraStateHolder.CAMERA_CAPTURING) {
                        Log.w(TAG, "picture callback returning when not capturing");
                    } else {
                        mCameraState.setState(AndroidCameraStateHolder.CAMERA_IDLE);
                    }
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            jpeg.onPictureTaken(data, AndroidCameraProxyImpl.this);
                        }
                    });
                }
            };

            mDispatchThread.runJob(new Runnable() {
                @Override
                public void run() {
                    mCameraState.waitForStates(AndroidCameraStateHolder.CAMERA_IDLE |
                            AndroidCameraStateHolder.CAMERA_UNLOCKED);
                    mCameraHandler.requestTakePicture(ShutterCallbackForward
                                    .getNewInstance(handler, AndroidCameraProxyImpl.this, shutter),
                            PictureCallbackForward
                                    .getNewInstance(handler, AndroidCameraProxyImpl.this, raw),
                            PictureCallbackForward
                                    .getNewInstance(handler, AndroidCameraProxyImpl.this, post),
                            jpegCallback
                    );
                }
            });
        }

        @Override
        public void setZoomChangeListener(final OnZoomChangeListener listener) {
            mDispatchThread.runJob(new Runnable() {
                @Override
                public void run() {
                    mCameraHandler.obtainMessage(CameraActions.SET_ZOOM_CHANGE_LISTENER, listener)
                            .sendToTarget();
                }
            });
        }

        @Override
        public void setFaceDetectionCallback(final Handler handler,
                final CameraFaceDetectionCallback cb) {
            mDispatchThread.runJob(new Runnable() {
                @Override
                public void run() {
                    mCameraHandler.obtainMessage(CameraActions.SET_FACE_DETECTION_LISTENER,
                            FaceDetectionCallbackForward
                                    .getNewInstance(handler, AndroidCameraProxyImpl.this, cb))
                            .sendToTarget();
                }
            });
        }

        @Override
        public void setErrorCallback(final Handler handler, final CameraErrorCallback cb) {
            mDispatchThread.runJob(new Runnable() {
                @Override
                public void run() {
                    mCameraHandler.obtainMessage(CameraActions.SET_ERROR_CALLBACK,
                            ErrorCallbackForward.getNewInstance(
                                    handler, AndroidCameraProxyImpl.this, cb))
                            .sendToTarget();
                }
            });
        }

        @Deprecated
        @Override
        public void setParameters(final Parameters params) {
            if (params == null) {
                Log.v(TAG, "null parameters in setParameters()");
                return;
            }
            final String flattenedParameters = params.flatten();
            mDispatchThread.runJob(new Runnable() {
                @Override
                public void run() {
                    mCameraState.waitForStates(AndroidCameraStateHolder.CAMERA_IDLE |
                            AndroidCameraStateHolder.CAMERA_UNLOCKED);
                    mCameraHandler.obtainMessage(CameraActions.SET_PARAMETERS, flattenedParameters)
                            .sendToTarget();
                }
            });
        }

        @Deprecated
        @Override
        public Parameters getParameters() {
            final WaitDoneBundle bundle = new WaitDoneBundle();
            final Parameters[] parametersHolder = new Parameters[1];
            mDispatchThread.runJobSync(new Runnable() {
                @Override
                public void run() {
                    mCameraHandler.obtainMessage(
                            CameraActions.GET_PARAMETERS, parametersHolder).sendToTarget();
                    mCameraHandler.post(bundle.mUnlockRunnable);
                }
            }, bundle.mWaitLock, CAMERA_OPERATION_TIMEOUT_MS, "get parameters");
            return parametersHolder[0];
        }

        @Override
        public CameraSettings getSettings() {
            return new AndroidCameraSettings(mCapabilities, getParameters());
        }

        @Override
        public boolean applySettings(CameraSettings settings) {
            return applySettingsHelper(settings, AndroidCameraStateHolder.CAMERA_IDLE |
                    AndroidCameraStateHolder.CAMERA_UNLOCKED);
        }

        @Override
        public String dumpDeviceSettings() {
            Parameters parameters = getParameters();
            if (parameters != null) {
                String flattened = getParameters().flatten();
                StringTokenizer tokenizer = new StringTokenizer(flattened, ";");
                String dumpedSettings = new String();
                while (tokenizer.hasMoreElements()) {
                    dumpedSettings += tokenizer.nextToken() + '\n';
                }

                return dumpedSettings;
            } else {
                return "[no parameters retrieved]";
            }
        }

        @Override
        public Handler getCameraHandler() {
            return AndroidCameraAgentImpl.this.getCameraHandler();
        }

        @Override
        public DispatchThread getDispatchThread() {
            return AndroidCameraAgentImpl.this.getDispatchThread();
        }

        @Override
        public CameraStateHolder getCameraState() {
            return mCameraState;
        }
    }

    private static class AndroidCameraStateHolder extends CameraStateHolder {
        /* Camera states */
        // These states are defined bitwise so we can easily to specify a set of
        // states together.
        public static final int CAMERA_UNOPENED = 1;
        public static final int CAMERA_IDLE = 1 << 1;
        public static final int CAMERA_UNLOCKED = 1 << 2;
        public static final int CAMERA_CAPTURING = 1 << 3;
        public static final int CAMERA_FOCUSING = 1 << 4;

        public AndroidCameraStateHolder() {
            this(CAMERA_UNOPENED);
        }

        public AndroidCameraStateHolder(int state) {
            super(state);
        }
    }

    /**
     * A helper class to forward AutoFocusCallback to another thread.
     */
    private static class AFCallbackForward implements AutoFocusCallback {
        private final Handler mHandler;
        private final CameraProxy mCamera;
        private final CameraAFCallback mCallback;

        /**
         * Returns a new instance of {@link AFCallbackForward}.
         *
         * @param handler The handler in which the callback will be invoked in.
         * @param camera  The {@link CameraProxy} which the callback is from.
         * @param cb      The callback to be invoked.
         * @return        The instance of the {@link AFCallbackForward},
         *                or null if any parameter is null.
         */
        public static AFCallbackForward getNewInstance(
                Handler handler, CameraProxy camera, CameraAFCallback cb) {
            if (handler == null || camera == null || cb == null) {
                return null;
            }
            return new AFCallbackForward(handler, camera, cb);
        }

        private AFCallbackForward(
                Handler h, CameraProxy camera, CameraAFCallback cb) {
            mHandler = h;
            mCamera = camera;
            mCallback = cb;
        }

        @Override
        public void onAutoFocus(final boolean b, Camera camera) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mCallback.onAutoFocus(b, mCamera);
                }
            });
        }
    }

    /**
     * A helper class to forward ErrorCallback to another thread.
     */
    private static class ErrorCallbackForward implements Camera.ErrorCallback {
        private final Handler mHandler;
        private final CameraProxy mCamera;
        private final CameraErrorCallback mCallback;

        /**
         * Returns a new instance of {@link AFCallbackForward}.
         *
         * @param handler The handler in which the callback will be invoked in.
         * @param camera  The {@link CameraProxy} which the callback is from.
         * @param cb      The callback to be invoked.
         * @return        The instance of the {@link AFCallbackForward},
         *                or null if any parameter is null.
         */
        public static ErrorCallbackForward getNewInstance(
                Handler handler, CameraProxy camera, CameraErrorCallback cb) {
            if (handler == null || camera == null || cb == null) {
                return null;
            }
            return new ErrorCallbackForward(handler, camera, cb);
        }

        private ErrorCallbackForward(
                Handler h, CameraProxy camera, CameraErrorCallback cb) {
            mHandler = h;
            mCamera = camera;
            mCallback = cb;
        }

        @Override
        public void onError(final int error, Camera camera) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mCallback.onError(error, mCamera);
                }
            });
        }
    }

    /** A helper class to forward AutoFocusMoveCallback to another thread. */
    @TargetApi(Build.VERSION_CODES.JELLY_BEAN)
    private static class AFMoveCallbackForward implements AutoFocusMoveCallback {
        private final Handler mHandler;
        private final CameraAFMoveCallback mCallback;
        private final CameraProxy mCamera;

        /**
         * Returns a new instance of {@link AFMoveCallbackForward}.
         *
         * @param handler The handler in which the callback will be invoked in.
         * @param camera  The {@link CameraProxy} which the callback is from.
         * @param cb      The callback to be invoked.
         * @return        The instance of the {@link AFMoveCallbackForward},
         *                or null if any parameter is null.
         */
        public static AFMoveCallbackForward getNewInstance(
                Handler handler, CameraProxy camera, CameraAFMoveCallback cb) {
            if (handler == null || camera == null || cb == null) {
                return null;
            }
            return new AFMoveCallbackForward(handler, camera, cb);
        }

        private AFMoveCallbackForward(
                Handler h, CameraProxy camera, CameraAFMoveCallback cb) {
            mHandler = h;
            mCamera = camera;
            mCallback = cb;
        }

        @Override
        public void onAutoFocusMoving(
                final boolean moving, android.hardware.Camera camera) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mCallback.onAutoFocusMoving(moving, mCamera);
                }
            });
        }
    }

    /**
     * A helper class to forward ShutterCallback to to another thread.
     */
    private static class ShutterCallbackForward implements ShutterCallback {
        private final Handler mHandler;
        private final CameraShutterCallback mCallback;
        private final CameraProxy mCamera;

        /**
         * Returns a new instance of {@link ShutterCallbackForward}.
         *
         * @param handler The handler in which the callback will be invoked in.
         * @param camera  The {@link CameraProxy} which the callback is from.
         * @param cb      The callback to be invoked.
         * @return        The instance of the {@link ShutterCallbackForward},
         *                or null if any parameter is null.
         */
        public static ShutterCallbackForward getNewInstance(
                Handler handler, CameraProxy camera, CameraShutterCallback cb) {
            if (handler == null || camera == null || cb == null) {
                return null;
            }
            return new ShutterCallbackForward(handler, camera, cb);
        }

        private ShutterCallbackForward(
                Handler h, CameraProxy camera, CameraShutterCallback cb) {
            mHandler = h;
            mCamera = camera;
            mCallback = cb;
        }

        @Override
        public void onShutter() {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mCallback.onShutter(mCamera);
                }
            });
        }
    }

    /**
     * A helper class to forward PictureCallback to another thread.
     */
    private static class PictureCallbackForward implements PictureCallback {
        private final Handler mHandler;
        private final CameraPictureCallback mCallback;
        private final CameraProxy mCamera;

        /**
         * Returns a new instance of {@link PictureCallbackForward}.
         *
         * @param handler The handler in which the callback will be invoked in.
         * @param camera  The {@link CameraProxy} which the callback is from.
         * @param cb      The callback to be invoked.
         * @return        The instance of the {@link PictureCallbackForward},
         *                or null if any parameters is null.
         */
        public static PictureCallbackForward getNewInstance(
                Handler handler, CameraProxy camera, CameraPictureCallback cb) {
            if (handler == null || camera == null || cb == null) {
                return null;
            }
            return new PictureCallbackForward(handler, camera, cb);
        }

        private PictureCallbackForward(
                Handler h, CameraProxy camera, CameraPictureCallback cb) {
            mHandler = h;
            mCamera = camera;
            mCallback = cb;
        }

        @Override
        public void onPictureTaken(
                final byte[] data, android.hardware.Camera camera) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mCallback.onPictureTaken(data, mCamera);
                }
            });
        }
    }

    /**
     * A helper class to forward PreviewCallback to another thread.
     */
    private static class PreviewCallbackForward implements PreviewCallback {
        private final Handler mHandler;
        private final CameraPreviewDataCallback mCallback;
        private final CameraProxy mCamera;

        /**
         * Returns a new instance of {@link PreviewCallbackForward}.
         *
         * @param handler The handler in which the callback will be invoked in.
         * @param camera  The {@link CameraProxy} which the callback is from.
         * @param cb      The callback to be invoked.
         * @return        The instance of the {@link PreviewCallbackForward},
         *                or null if any parameters is null.
         */
        public static PreviewCallbackForward getNewInstance(
                Handler handler, CameraProxy camera, CameraPreviewDataCallback cb) {
            if (handler == null || camera == null || cb == null) {
                return null;
            }
            return new PreviewCallbackForward(handler, camera, cb);
        }

        private PreviewCallbackForward(
                Handler h, CameraProxy camera, CameraPreviewDataCallback cb) {
            mHandler = h;
            mCamera = camera;
            mCallback = cb;
        }

        @Override
        public void onPreviewFrame(
                final byte[] data, android.hardware.Camera camera) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mCallback.onPreviewFrame(data, mCamera);
                }
            });
        }
    }

    private static class FaceDetectionCallbackForward implements FaceDetectionListener {
        private final Handler mHandler;
        private final CameraFaceDetectionCallback mCallback;
        private final CameraProxy mCamera;

        /**
         * Returns a new instance of {@link FaceDetectionCallbackForward}.
         *
         * @param handler The handler in which the callback will be invoked in.
         * @param camera  The {@link CameraProxy} which the callback is from.
         * @param cb      The callback to be invoked.
         * @return        The instance of the {@link FaceDetectionCallbackForward},
         *                or null if any parameter is null.
         */
        public static FaceDetectionCallbackForward getNewInstance(
                Handler handler, CameraProxy camera, CameraFaceDetectionCallback cb) {
            if (handler == null || camera == null || cb == null) {
                return null;
            }
            return new FaceDetectionCallbackForward(handler, camera, cb);
        }

        private FaceDetectionCallbackForward(
                Handler h, CameraProxy camera, CameraFaceDetectionCallback cb) {
            mHandler = h;
            mCamera = camera;
            mCallback = cb;
        }

        @Override
        public void onFaceDetection(
                final Camera.Face[] faces, Camera camera) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mCallback.onFaceDetection(faces, mCamera);
                }
            });
        }
    }
}
