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

import android.app.Activity;
import android.content.Context;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.renderscript.RenderScript;
import android.util.AttributeSet;
import android.util.Log;
import android.util.Size;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.View;

import com.android.example.rscamera.rscamera.R;

import java.util.ArrayList;
import java.util.List;

/**
 * Created by hoford on 2/27/15.
 */
public class CameraView extends FixedAspectSurfaceView {
    private static final String TAG = "CameraPreView";

    private static final long MICRO_SECOND = 1000;
    private static final long MILLI_SECOND = MICRO_SECOND * 1000;
    private static final long ONE_SECOND = MILLI_SECOND * 1000;

    private Surface mPreviewSurface;
    ViewfinderProcessor mProcessor;
    private Surface mProcessingNormalSurface;
    CameraOps mCameraOps;
    CameraManager mCameraManager;
    Activity mActivity;
    Context mContext;
    byte mode = 0;
    public static final byte MODE_NONE = 0;
    public static final byte MODE_SPEED = 1;
    public static final byte MODE_FOCUS = 2;
    public static final byte MODE_ISO = 3;
    RenderScript mRS;
    ErrorCallback mErrorCallback;
    ParametersChangedCallback mParametersChangedCallback;

    public CameraView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mContext = context;

        mRS = RenderScript.create(mContext);
        SurfaceHolder.Callback callback = new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                mPreviewSurface = holder.getSurface();
                setupProcessor();
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                mPreviewSurface = null;
            }
        };
        getHolder().addCallback(callback);
        mCameraManager = (CameraManager) mContext.getSystemService(mContext.CAMERA_SERVICE);

        CameraOps.ErrorDisplayer errorDisplayer = new CameraOps.ErrorDisplayer() {

            @Override
            public void showErrorDialog(String errorMessage) {
                Log.v(TAG, "ERROR");
                if (mErrorCallback != null) {
                    mErrorCallback.showError(errorMessage);
                }
                //MessageDialogFragment.newInstance(errorMessage).show(getFragmentManager(), FRAGMENT_DIALOG);
            }

            @Override
            public String getErrorString(CameraAccessException e) {
                switch (e.getReason()) {
                    case CameraAccessException.CAMERA_DISABLED:
                        return mContext.getString(R.string.camera_disabled);
                    case CameraAccessException.CAMERA_DISCONNECTED:
                        return mContext.getString(R.string.camera_disconnected);
                    case CameraAccessException.CAMERA_ERROR:
                        return mContext.getString(R.string.camera_error);
                    default:
                        return mContext.getString(R.string.camera_unknown, e.getReason());

                }
            }
        };

        CameraOps.CameraReadyListener cameraReadyListener = new CameraOps.CameraReadyListener() {
            @Override
            public void onCameraReady() {
                mCameraOps.setUpCamera(mProcessingNormalSurface);
            }
        };
        setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                return touchScreen(event);
            }
        });
        mCameraOps = new CameraOps(mCameraManager,
                errorDisplayer,
                cameraReadyListener);
    }

    public void resume(Activity activity) {
        mActivity = activity;

        String errorMessage = mCameraOps.resume();
        if (errorMessage != null) {
            if (mErrorCallback != null) {
                mErrorCallback.showError(errorMessage);
            }
        } else {

            Size outputSize = mCameraOps.getBestSize();
            mProcessor = new ViewfinderProcessor(mRS, outputSize);
            // Configure the output view - this will fire surfaceChanged
            setAspectRatio((float) outputSize.getWidth() / outputSize.getHeight());
            getHolder().setFixedSize(outputSize.getWidth(), outputSize.getHeight());
        }
    }

    public void pause() {
        mCameraOps.pause();
    }

    /**
     * Once camera is open and output surfaces are ready, configure the RS processing
     * and the camera device inputs/outputs.
     */
    private void setupProcessor() {
        if (mProcessor == null || mPreviewSurface == null) return;
        mProcessor.setOutputSurface(mPreviewSurface);
        mProcessingNormalSurface = mProcessor.getInputSurface();
        mCameraOps.setSurface(mProcessingNormalSurface);
    }

    public void takePicture() {
        // Orientation
        int rotation = mActivity.getWindowManager().getDefaultDisplay().getRotation();
        int jpegRotation = Surface.ROTATION_0;
        switch (rotation) {
            case 90:
                jpegRotation = Surface.ROTATION_0;
                break;
            case 0:
                jpegRotation = Surface.ROTATION_90;
                break;
            case 180:
                jpegRotation = Surface.ROTATION_270;
                break;
            case 270:
                jpegRotation = Surface.ROTATION_180;
                break;
        }
        String name = "Simple" + System.currentTimeMillis() + ".jpg";
        mCameraOps.captureStillPicture(jpegRotation, name, mContext.getContentResolver());
    }

    private CameraCaptureSession.CaptureCallback mPhotoCallback
            = new CameraCaptureSession.CaptureCallback() {

        public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request,
                                       TotalCaptureResult result) {
            Log.v(TAG, "onCaptureCompleted " + result.toString());
        }
    };

    float mDownY;
    long mExposureDown;
    float mFocusDistDown;

    public boolean touchScreen(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            mDownY = event.getY();
            mExposureDown = mCameraOps.getExposure();
            mFocusDistDown = mCameraOps.getFocusDistance();
            if (mFocusDistDown == 0.0) {
                mFocusDistDown = 0.01f;
            }
        }
        float distanceY = event.getY() - mDownY;
        float width = getWidth();
        float height = getHeight();

        float yDistNorm = distanceY / height;

        float ACCELERATION_FACTOR = 8;
        float scaleFactor = (float) Math.pow(2.f, yDistNorm * ACCELERATION_FACTOR);

        switch (mode) {
            case MODE_SPEED:
                long exp = (long) (mExposureDown * scaleFactor);
                exp = Math.min(mCameraOps.getExpMax(), exp);
                mCameraOps.setExposure(Math.max(mCameraOps.getExpMin(), exp));
                Log.v(TAG, "mExposure =" + mCameraOps.getExposure());
                break;
            case MODE_FOCUS:
                float focusDist = mFocusDistDown * scaleFactor;
                focusDist = Math.max(0.0f, Math.min(mCameraOps.getFocusMin(), focusDist));
                if (focusDist < 0.01) focusDist = 0;
                mCameraOps.setFocusDistance(focusDist);
                Log.v(TAG, "mFocusDist =" + focusDist);
                break;
            case MODE_ISO:
                ACCELERATION_FACTOR = 2;
                scaleFactor = (float) Math.pow(2.f, yDistNorm * ACCELERATION_FACTOR);
                int iso = (int) (getIso() * scaleFactor);
                iso = Math.min(mCameraOps.getIsoMax(), iso);
                mCameraOps.setIso(Math.max(mCameraOps.getIsoMin(), iso));
                break;
        }

        if (mParametersChangedCallback != null) {
            mParametersChangedCallback.parametersChanged();
        }
        mCameraOps.setParameters();

        return true;
    }

    public void setMode(byte mode) {
        this.mode = mode;
    }

    public byte getMode() {
        return mode;
    }

    public int getIso() {
        return mCameraOps.getIso();
    }

    public void setIso(int iso) {
        mCameraOps.setIso(iso);
        if (mParametersChangedCallback != null) {
            mParametersChangedCallback.parametersChanged();
        }
        mCameraOps.setParameters();
    }

    public long getExposure() {
        return mCameraOps.getExposure();
    }

    public void setExposure(long exposure) {
        mCameraOps.setExposure(exposure);
        if (mParametersChangedCallback != null) {
            mParametersChangedCallback.parametersChanged();
        }
        mCameraOps.setParameters();
    }

    public float getFocusDist() {
        return mCameraOps.getFocusDistance();
    }

    public void setFocusInMeters(float dist) {
        float min = mCameraOps.getFocusMin();
        float d = 10 / (dist + 10 / min);
        setFocusDist(d);
    }

    public void setFocusDist(float dist) {
        mCameraOps.setFocusDistance(dist);
        mCameraOps.setParameters();
    }

    public float getMinFocusDistance() {
        return mCameraOps.getFocusMin();
    }

    public void setAutofocus(boolean autofocus) {
        mCameraOps.setAutoFocus(autofocus);
        mCameraOps.setParameters();
    }

    public boolean isAutoExposure() {
        return mCameraOps.isAutoExposure();
    }

    public boolean isAutofocus() {
        return mCameraOps.isAutoFocus();
    }

    public void setAutoExposure(boolean autoExposure) {
        mCameraOps.setAutoExposure(autoExposure);
        mCameraOps.setParameters();
    }

    public static interface ErrorCallback {
        public void showError(String errorMessage);
    }

    public void setErrorCallback(ErrorCallback errorCallback) {
        mErrorCallback = errorCallback;
    }

    public static interface ParametersChangedCallback {
        public void parametersChanged();
    }

    public void setParametersChangedCallback(ParametersChangedCallback parametersChangedCallback) {
        mParametersChangedCallback = parametersChangedCallback;
    }

    float getFps() {
        return mProcessor.getmFps();
    }
}
