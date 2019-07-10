/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.example.rscamera;

import android.content.ContentResolver;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.ConditionVariable;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.widget.Toast;

import com.android.example.rscamera.rscamera.R;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Simple interface for operating the camera, with major camera operations
 * all performed on a background handler thread.
 */
public class CameraOps {

    private static final String TAG = "CameraOps";
    private static final long ONE_SECOND = 1000000000;
    public static final long CAMERA_CLOSE_TIMEOUT = 2000; // ms

    private final CameraManager mCameraManager;
    private CameraDevice mCameraDevice;
    private CameraCaptureSession mCameraSession;
    private List<Surface> mSurfaces;

    private final ConditionVariable mCloseWaiter = new ConditionVariable();

    private HandlerThread mCameraThread;
    private Handler mCameraHandler;

    private final ErrorDisplayer mErrorDisplayer;

    private final CameraReadyListener mReadyListener;
    private final Handler mReadyHandler;

    private int mISOmax;
    private int mISOmin;
    private long mExpMax;
    private long mExpMin;
    private float mFocusMin;
    private float mFocusDist = 0;
    private int mIso;
    boolean mAutoExposure = true;
    boolean mAutoFocus = true;
    private long mExposure = ONE_SECOND / 33;

    private Object mAutoExposureTag = new Object();

    private ImageReader mImageReader;
    private Handler mBackgroundHandler;
    private CameraCharacteristics mCameraInfo;
    private HandlerThread mBackgroundThread;
    CaptureRequest.Builder mHdrBuilder;
    private Surface mProcessingNormalSurface;
    CaptureRequest mPreviewRequest;
    private String mSaveFileName;
    private ContentResolver mContentResolver;

    public String resume() {
        String errorMessage = "Unknown error";
        boolean foundCamera = false;
        try {
            // Find first back-facing camera that has necessary capability
            String[] cameraIds = mCameraManager.getCameraIdList();
            for (String id : cameraIds) {
                CameraCharacteristics info = mCameraManager.getCameraCharacteristics(id);
                int facing = info.get(CameraCharacteristics.LENS_FACING);

                int level = info.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
                boolean hasFullLevel
                        = (level == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL);

                int[] capabilities = info.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
                int syncLatency = info.get(CameraCharacteristics.SYNC_MAX_LATENCY);
                boolean hasManualControl = hasCapability(capabilities,
                        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR);
                boolean hasEnoughCapability = hasManualControl &&
                        syncLatency == CameraCharacteristics.SYNC_MAX_LATENCY_PER_FRAME_CONTROL;
                Range<Integer> irange;
                Range<Long> lrange;

                irange = info.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
                mISOmax = irange.getUpper();
                mISOmin = irange.getLower();
                lrange = info.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
                mExpMax = lrange.getUpper();
                mExpMin = lrange.getLower();
                mFocusMin = info.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);

                mFocusDist = mFocusMin;
                StreamConfigurationMap map = info.get(
                        CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                Size[] sizes = map.getOutputSizes(ImageFormat.JPEG);
                Size largest = Collections.max(Arrays.asList(sizes), new Comparator<Size>() {
                    @Override
                    public int compare(Size lhs, Size rhs) {
                        int leftArea = lhs.getHeight() * lhs.getWidth();
                        int rightArea = lhs.getHeight() * lhs.getWidth();
                        return Integer.compare(leftArea, rightArea);
                    }
                });
                mImageReader = ImageReader.newInstance(largest.getWidth(), largest.getHeight(),
                        ImageFormat.JPEG, /*maxImages*/2);
                mImageReader.setOnImageAvailableListener(
                        mOnImageAvailableListener, mBackgroundHandler);

                if (facing == CameraCharacteristics.LENS_FACING_BACK &&
                        (hasFullLevel || hasEnoughCapability)) {
                    // Found suitable camera - get info, open, and set up outputs
                    mCameraInfo = info;
                    openCamera(id);
                    foundCamera = true;
                    break;
                }
            }
            if (!foundCamera) {
                errorMessage = "no back camera";
            }
        } catch (CameraAccessException e) {
            errorMessage = e.getMessage();
        }
        // startBackgroundThread
        mBackgroundThread = new HandlerThread("CameraBackground");
        mBackgroundThread.start();
        mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
        return (foundCamera) ? null : errorMessage;
    }


    private boolean hasCapability(int[] capabilities, int capability) {
        for (int c : capabilities) {
            if (c == capability) return true;
        }
        return false;
    }

    private final ImageReader.OnImageAvailableListener mOnImageAvailableListener
            = new ImageReader.OnImageAvailableListener() {

        @Override
        public void onImageAvailable(ImageReader reader) {
            mBackgroundHandler.post(new ImageSaver(reader.acquireNextImage(), mSaveFileName, mContentResolver));
        }

    };

    /**
     * Saves a JPEG {@link android.media.Image} into the specified {@link java.io.File}.
     */
    private static class ImageSaver implements Runnable {
        private final Image mImage;
        private final String mName;
        ContentResolver mContentResolver;

        public ImageSaver(Image image, String fileName, ContentResolver contentResolver) {
            mImage = image;
            mName = fileName;
            mContentResolver = contentResolver;
        }

        @Override
        public void run() {
            Log.v(TAG, "SAVING...");
            MediaStoreSaver.insertImage(mContentResolver, new MediaStoreSaver.StreamWriter() {
                @Override
                public void write(OutputStream imageOut) throws IOException {
                    try {
                        ByteBuffer buffer = mImage.getPlanes()[0].getBuffer();
                        byte[] bytes = new byte[buffer.remaining()];
                        buffer.get(bytes);
                        imageOut.write(bytes);
                    } finally {
                        mImage.close();
                    }
                }
            }, mName, "Saved from Simple Camera Demo");
        }
    }

    /**
     * Create a new camera ops thread.
     *
     * @param errorDisplayer listener for displaying error messages
     * @param readyListener  listener for notifying when camera is ready for requests
     */
    CameraOps(CameraManager manager, ErrorDisplayer errorDisplayer,
              CameraReadyListener readyListener) {
        mReadyHandler = new Handler(Looper.getMainLooper());

        mCameraThread = new HandlerThread("CameraOpsThread");
        mCameraThread.start();

        if (manager == null || errorDisplayer == null ||
                readyListener == null || mReadyHandler == null) {
            throw new IllegalArgumentException("Need valid displayer, listener, handler");
        }

        mCameraManager = manager;
        mErrorDisplayer = errorDisplayer;
        mReadyListener = readyListener;

    }

    /**
     * Open the first backfacing camera listed by the camera manager.
     * Displays a dialog if it cannot open a camera.
     */
    public void openCamera(final String cameraId) {
        mCameraHandler = new Handler(mCameraThread.getLooper());

        mCameraHandler.post(new Runnable() {
            public void run() {
                if (mCameraDevice != null) {
                    throw new IllegalStateException("Camera already open");
                }
                try {

                    mCameraManager.openCamera(cameraId, mCameraDeviceListener, mCameraHandler);
                } catch (CameraAccessException e) {
                    String errorMessage = mErrorDisplayer.getErrorString(e);
                    mErrorDisplayer.showErrorDialog(errorMessage);
                }
            }
        });
    }

    public void pause() {
        closeCameraAndWait();
        mBackgroundThread.quitSafely();
        try {
            mBackgroundThread.join();
            mBackgroundThread = null;
            mBackgroundHandler = null;
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    public Size getBestSize() {
        // Find a good size for output - largest 16:9 aspect ratio that's less than 720p
        final int MAX_WIDTH = 1280;
        final float TARGET_ASPECT = 16.f / 9.f;
        final float ASPECT_TOLERANCE = 0.1f;


        StreamConfigurationMap configs =
                mCameraInfo.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

        Size[] outputSizes = configs.getOutputSizes(SurfaceHolder.class);

        Size outputSize = outputSizes[0];
        float outputAspect = (float) outputSize.getWidth() / outputSize.getHeight();
        for (Size candidateSize : outputSizes) {
            if (candidateSize.getWidth() > MAX_WIDTH) continue;
            float candidateAspect = (float) candidateSize.getWidth() / candidateSize.getHeight();
            boolean goodCandidateAspect =
                    Math.abs(candidateAspect - TARGET_ASPECT) < ASPECT_TOLERANCE;
            boolean goodOutputAspect =
                    Math.abs(outputAspect - TARGET_ASPECT) < ASPECT_TOLERANCE;
            if ((goodCandidateAspect && !goodOutputAspect) ||
                    candidateSize.getWidth() > outputSize.getWidth()) {
                outputSize = candidateSize;
                outputAspect = candidateAspect;
            }
        }
        return outputSize;
    }

    /**
     * Close the camera and wait for the close callback to be called in the camera thread.
     * Times out after @{value CAMERA_CLOSE_TIMEOUT} ms.
     */
    public void closeCameraAndWait() {
        mCloseWaiter.close();
        mCameraHandler.post(mCloseCameraRunnable);
        boolean closed = mCloseWaiter.block(CAMERA_CLOSE_TIMEOUT);
        if (!closed) {
            Log.e(TAG, "Timeout closing camera");
        }
    }

    private Runnable mCloseCameraRunnable = new Runnable() {
        public void run() {
            if (mCameraDevice != null) {
                mCameraDevice.close();
            }
            mCameraDevice = null;
            mCameraSession = null;
            mSurfaces = null;
        }
    };

    /**
     * Set the output Surfaces, and finish configuration if otherwise ready.
     */
    public void setSurface(Surface surface) {
        final List<Surface> surfaceList = new ArrayList<Surface>();
        surfaceList.add(surface);
        surfaceList.add(mImageReader.getSurface());

        mCameraHandler.post(new Runnable() {
            public void run() {
                mSurfaces = surfaceList;
                startCameraSession();
            }
        });
    }

    /**
     * Get a request builder for the current camera.
     */
    public CaptureRequest.Builder createCaptureRequest(int template) throws CameraAccessException {
        CameraDevice device = mCameraDevice;
        if (device == null) {
            throw new IllegalStateException("Can't get requests when no camera is open");
        }
        return device.createCaptureRequest(template);
    }

    /**
     * Set a repeating request.
     */
    public void setRepeatingRequest(final CaptureRequest request,
                                    final CameraCaptureSession.CaptureCallback listener,
                                    final Handler handler) {
        mCameraHandler.post(new Runnable() {
            public void run() {
                try {
                    mCameraSession.setRepeatingRequest(request, listener, handler);
                } catch (CameraAccessException e) {
                    String errorMessage = mErrorDisplayer.getErrorString(e);
                    mErrorDisplayer.showErrorDialog(errorMessage);
                }
            }
        });
    }

    /**
     * Set a repeating request.
     */
    public void setRepeatingBurst(final List<CaptureRequest> requests,
                                  final CameraCaptureSession.CaptureCallback listener,
                                  final Handler handler) {
        mCameraHandler.post(new Runnable() {
            public void run() {
                try {
                    mCameraSession.setRepeatingBurst(requests, listener, handler);

                } catch (CameraAccessException e) {
                    String errorMessage = mErrorDisplayer.getErrorString(e);
                    mErrorDisplayer.showErrorDialog(errorMessage);
                }
            }
        });
    }

    /**
     * Configure the camera session.
     */
    private void startCameraSession() {
        // Wait until both the camera device is open and the SurfaceView is ready
        if (mCameraDevice == null || mSurfaces == null) return;

        try {

            mCameraDevice.createCaptureSession(
                    mSurfaces, mCameraSessionListener, mCameraHandler);
        } catch (CameraAccessException e) {
            String errorMessage = mErrorDisplayer.getErrorString(e);
            mErrorDisplayer.showErrorDialog(errorMessage);
            mCameraDevice.close();
            mCameraDevice = null;
        }
    }

    /**
     * Main listener for camera session events
     * Invoked on mCameraThread
     */
    private CameraCaptureSession.StateCallback mCameraSessionListener =
            new CameraCaptureSession.StateCallback() {

                @Override
                public void onConfigured(CameraCaptureSession session) {
                    mCameraSession = session;
                    mReadyHandler.post(new Runnable() {
                        public void run() {
                            // This can happen when the screen is turned off and turned back on.
                            if (null == mCameraDevice) {
                                return;
                            }

                            mReadyListener.onCameraReady();
                        }
                    });

                }

                @Override
                public void onConfigureFailed(CameraCaptureSession session) {
                    mErrorDisplayer.showErrorDialog("Unable to configure the capture session");
                    mCameraDevice.close();
                    mCameraDevice = null;
                }
            };

    /**
     * Main listener for camera device events.
     * Invoked on mCameraThread
     */
    private CameraDevice.StateCallback mCameraDeviceListener = new CameraDevice.StateCallback() {

        @Override
        public void onOpened(CameraDevice camera) {
            mCameraDevice = camera;
            startCameraSession();
        }

        @Override
        public void onClosed(CameraDevice camera) {
            mCloseWaiter.open();
        }

        @Override
        public void onDisconnected(CameraDevice camera) {
            mErrorDisplayer.showErrorDialog("The camera device has been disconnected.");
            camera.close();
            mCameraDevice = null;
        }

        @Override
        public void onError(CameraDevice camera, int error) {
            mErrorDisplayer.showErrorDialog("The camera encountered an error:" + error);
            camera.close();
            mCameraDevice = null;
        }

    };

    public void captureStillPicture(int currentJpegRotation, String name, ContentResolver resolver) {
        mSaveFileName = name;
        mContentResolver = resolver;
        try {
            // TODO call lock focus if we are in "AF-S(One-Shot AF) mode"
            // TODO call precapture if we are using flash
            // This is the CaptureRequest.Builder that we use to take a picture.
            final CaptureRequest.Builder captureBuilder =
                    createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            Log.v(TAG, " Target " + mImageReader.getWidth() + "," + mImageReader.getHeight());

            captureBuilder.addTarget(mImageReader.getSurface());

            captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, currentJpegRotation);

            CameraCaptureSession.CaptureCallback captureCallback
                    = new CameraCaptureSession.CaptureCallback() {

                @Override
                public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request,
                                               TotalCaptureResult result) {
                    Log.v(TAG, " onCaptureCompleted");
                    setParameters();
                }
            };


            setRequest(captureBuilder.build(), captureCallback, null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    /**
     * Set a repeating request.
     */
    private void setRequest(final CaptureRequest request,
                            final CameraCaptureSession.CaptureCallback listener,
                            final Handler handler) {
        mCameraHandler.post(new Runnable() {
            public void run() {
                try {
                    mCameraSession.stopRepeating();
                    mCameraSession.capture(request, listener, handler);
                } catch (CameraAccessException e) {
                    String errorMessage = mErrorDisplayer.getErrorString(e);
                    mErrorDisplayer.showErrorDialog(errorMessage);
                }
            }
        });
    }

    public void setUpCamera(Surface processingNormalSurface) {
        mProcessingNormalSurface = processingNormalSurface;
        // Ready to send requests in, so set them up
        try {
            CaptureRequest.Builder previewBuilder =
                    createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            previewBuilder.addTarget(mProcessingNormalSurface);
            previewBuilder.setTag(mAutoExposureTag);
            mPreviewRequest = previewBuilder.build();
            mHdrBuilder = createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            mHdrBuilder.set(CaptureRequest.CONTROL_AE_MODE,
                    CaptureRequest.CONTROL_AE_MODE_OFF);
            mHdrBuilder.addTarget(mProcessingNormalSurface);
            setParameters();

        } catch (CameraAccessException e) {
            String errorMessage = e.getMessage();
            // MessageDialogFragment.newInstance(errorMessage).show(getFragmentManager(), FRAGMENT_DIALOG);
        }
    }

    /**
     * Start running an HDR burst on a configured camera session
     */
    public void setParameters() {
        if (mHdrBuilder == null) {
            Log.v(TAG," Camera not set up");
            return;
        }
        if (mAutoExposure) {
            mHdrBuilder.set(CaptureRequest.SENSOR_FRAME_DURATION, ONE_SECOND / 30);
            mHdrBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, getExposure());
            mHdrBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
        } else {
            mHdrBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
            mHdrBuilder.set(CaptureRequest.SENSOR_FRAME_DURATION, ONE_SECOND / 30);
            mHdrBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, getExposure());
            mHdrBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, getIso());
        }
        if (mAutoFocus) {
            mHdrBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
            mHdrBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START);
        } else {
            mHdrBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);
            mHdrBuilder.set(CaptureRequest.LENS_FOCUS_DISTANCE, getFocusDistance());
            mHdrBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START);
        }

        setRepeatingRequest(mHdrBuilder.build(), mCaptureCallback, mReadyHandler);
    }

    private CameraCaptureSession.CaptureCallback mCaptureCallback
            = new CameraCaptureSession.CaptureCallback() {

        public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request,
                                       TotalCaptureResult result) {
        }
    };

    /**
     * Simple listener for main code to know the camera is ready for requests, or failed to
     * start.
     */
    public interface CameraReadyListener {
        public void onCameraReady();
    }

    /**
     * Simple listener for displaying error messages
     */
    public interface ErrorDisplayer {
        public void showErrorDialog(String errorMessage);
        public String getErrorString(CameraAccessException e);
    }

    public float getFocusDistance() {
        return mFocusDist;
    }

    public void setFocusDistance(float focusDistance) {
        mFocusDist = focusDistance;
    }

    public void setIso(int iso) {
        mIso = iso;
    }

    public boolean isAutoExposure() {
        return mAutoExposure;
    }

    public void setAutoExposure(boolean autoExposure) {
        mAutoExposure = autoExposure;
    }

    public boolean isAutoFocus() {
        return mAutoFocus;
    }

    public void setAutoFocus(boolean autoFocus) {
        mAutoFocus = autoFocus;
    }

    public int getIso() {
        return mIso;
    }

    public long getExposure() {
        return mExposure;
    }

    public void setExposure(long exposure) {
        mExposure = exposure;
    }

    public int getIsoMax() {
        return mISOmax;
    }

    public int getIsoMin() {
        return mISOmin;
    }

    public long getExpMax() {
        return mExpMax;
    }

    public long getExpMin() {
        return mExpMin;
    }

    public float getFocusMin() {
        return mFocusMin;
    }
}