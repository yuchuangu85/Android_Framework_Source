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

package com.example.android.rs.vr;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.SurfaceTexture;
import android.os.AsyncTask;
import android.renderscript.RenderScript;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.Surface;
import android.view.TextureView;

import com.example.android.rs.vr.engine.Cube;
import com.example.android.rs.vr.engine.Pipeline;
import com.example.android.rs.vr.engine.RsBrickedBitMask;
import com.example.android.rs.vr.engine.TriData;
import com.example.android.rs.vr.engine.VectorUtil;
import com.example.android.rs.vr.engine.ViewMatrix;
import com.example.android.rs.vr.engine.Volume;
import com.example.android.rs.vr.engine.VrPipline1;
import com.example.android.rs.vr.engine.VrState;

import java.util.Arrays;

/**
 * VrView runs a volume rendering on the screen
 */
public class VrView extends TextureView {
    private static final String LOGTAG = "rsexample.google.com.vrdemo";
    private Pipeline mPipline = new VrPipline1();//BasicPipline();
    //    private VrState mState4 = new VrState(); // for down sampled
    private VrState mState1 = new VrState(); // for full res version
    private VrState mStateLow = new VrState(); // for full res version
    private VrState mLastDrawn = new VrState(); // for full res version
    private Paint paint = new Paint();
    private SurfaceTexture mSurfaceTexture;
    private Surface mSurface;
    ///private Size mImageViewSize;
    private int refresh = 0;  // 0 is no refresh else refresh = downsample
    int mPreviousMode = -1;
    int last_look = 0;

    //    int mDownSample = 4;
    private final char[] looks = {
            ViewMatrix.UP_AT,
            ViewMatrix.DOWN_AT,
            ViewMatrix.RIGHT_AT,
            ViewMatrix.LEFT_AT,
            ViewMatrix.FORWARD_AT,
            ViewMatrix.BEHIND_AT};
    private byte mMode = ROTATE_MODE;
    private ScaleGestureDetector mScaleDetector;
    private boolean mInScale;

    public static final byte ROTATE_MODE = 1;
    public static final byte CUT_X_MODE = 2;
    public static final byte CUT_Y_MODE = 3;
    public static final byte CUT_Z_MODE = 4;

    public void setMode(byte mode) {
        mMode = mode;
    }

    private float mDownPointX;
    private float mDownPointY;
    private double mDownScreenWidth;
    private double[] mDownLookPoint = new double[3];
    private double[] mDownEyePoint = new double[3];
    private double[] mDownUpVector = new double[3];
    private double[] mDownRightVector = new double[3];
    private float[] mCurrentTrim = new float[6];
    VrRenderTesk mRenderTesk;
    VrBinGridTask mBinGridTask;

    public VrView(Context context) {
        super(context);
        setup(context);
        paint.setFilterBitmap(true);
    }

    public VrView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setup(context);
    }


    public VrView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setup(context);
    }


    public VrView(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        setup(context);
    }

    private void setup(Context context) {
        setBackgroundColor(Color.BLACK);
        if (isInEditMode()) {
            return;
        }
        setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {

            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                mSurfaceTexture = surface;
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
                mSurfaceTexture = surface;
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                return false;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture surface) {
            }
        });

        mScaleDetector = new ScaleGestureDetector(context,
                new ScaleGestureDetector.OnScaleGestureListener() {
                    @Override
                    public boolean onScale(ScaleGestureDetector detector) {
                        double width = mState1.mTransform.getScreenWidth() / detector.getScaleFactor();
                        mState1.mTransform.setScreenWidth(width);
                        panMove(detector.getFocusX(), detector.getFocusY());
                        render(4);
                        return true;
                    }

                    @Override
                    public boolean onScaleBegin(ScaleGestureDetector detector) {
                        panDown(detector.getFocusX(), detector.getFocusY());
                        mInScale = true;
                        return true;
                    }

                    @Override
                    public void onScaleEnd(ScaleGestureDetector detector) {
                        mInScale = false;
                    }
                });
    }

    private void updateOutputDimensions(int width, int height) {
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (mPreviousMode == 1) {
            mPipline.cancel();
        }
        boolean handled = mScaleDetector.onTouchEvent(e);
        if (e.getPointerCount() > 1) {
            return true;
        }
        if (mInScale) {
            return true;
        }
        int action = e.getAction();
        if (action == MotionEvent.ACTION_DOWN) {
            actionDown(e);
        } else if (action == MotionEvent.ACTION_MOVE) {
            actionMove(e);
            render(4);
        } else if (action == MotionEvent.ACTION_UP) {
            actionUp(e);
            refresh = 1;
            render(1);
        }
        return true;
    }

    private void panMove(float x, float y) {
        double dist_x = (mDownPointX - x) * mDownScreenWidth / getWidth();
        double dist_y = (y - mDownPointY) * mDownScreenWidth / getWidth();
        double[] p;
        p = mState1.mTransform.getEyePoint();
        p[0] = mDownEyePoint[0] + dist_x * mDownRightVector[0] + dist_y * mDownUpVector[0];
        p[1] = mDownEyePoint[1] + dist_x * mDownRightVector[1] + dist_y * mDownUpVector[1];
        p[2] = mDownEyePoint[2] + dist_x * mDownRightVector[2] + dist_y * mDownUpVector[2];
        mState1.mTransform.setEyePoint(p);
        p = mState1.mTransform.getLookPoint();
        p[0] = mDownLookPoint[0] + dist_x * mDownRightVector[0] + dist_y * mDownUpVector[0];
        p[1] = mDownLookPoint[1] + dist_x * mDownRightVector[1] + dist_y * mDownUpVector[1];
        p[2] = mDownLookPoint[2] + dist_x * mDownRightVector[2] + dist_y * mDownUpVector[2];
        mState1.mTransform.setLookPoint(p);
    }

    private void panDown(float x, float y) {
        mDownPointX = x;
        mDownPointY = y;
        mDownScreenWidth = mState1.mTransform.getScreenWidth();
        double[] p;
        p = mState1.mTransform.getLookPoint();
        System.arraycopy(p, 0, mDownLookPoint, 0, 3);
        p = mState1.mTransform.getEyePoint();
        System.arraycopy(p, 0, mDownEyePoint, 0, 3);
        p = mState1.mTransform.getUpVector();
        System.arraycopy(p, 0, mDownUpVector, 0, 3);
        mDownRightVector[0] = mDownLookPoint[0] - mDownEyePoint[0];
        mDownRightVector[1] = mDownLookPoint[1] - mDownEyePoint[1];
        mDownRightVector[2] = mDownLookPoint[2] - mDownEyePoint[2];
        VectorUtil.normalize(mDownRightVector);
        VectorUtil.cross(mDownRightVector, mDownUpVector, mDownRightVector);
    }

    private void actionDown(MotionEvent e) {
        panDown(e.getX(), e.getY());

        switch (mMode) {
            case ROTATE_MODE:
                mState1.mTransform.trackBallDown(e.getX(), e.getY());
                break;

            case CUT_X_MODE:
            case CUT_Y_MODE:
            case CUT_Z_MODE:
                float[] trim = mState1.mCubeVolume.getTrim();
                System.arraycopy(trim, 0, mCurrentTrim, 0, 6);
                break;
        }
    }

    private void actionMove(MotionEvent e) {
        float deltax, deltay;

        switch (mMode) {
            case ROTATE_MODE:

                mState1.mTransform.trackBallMove(e.getX(), e.getY());

                break;

            case CUT_X_MODE:
                deltax = (float) ((mDownPointX - e.getX()) / getWidth());
                deltay = (float) -((mDownPointY - e.getY()) / getWidth());
                cut(0, deltax, deltay);
                break;
            case CUT_Y_MODE:
                deltax = (float) ((mDownPointX - e.getX()) / getWidth());
                deltay = (float) -((mDownPointY - e.getY()) / getWidth());
                cut(1, deltax, deltay);
                break;
            case CUT_Z_MODE:
                deltax = (float) ((mDownPointX - e.getX()) / getWidth());
                deltay = (float) -((mDownPointY - e.getY()) / getWidth());
                cut(2, deltax, deltay);
                break;

        }
    }

    private void actionUp(MotionEvent e) {
    }

    public void cut(int side, float fractionx, float fractiony) {
        float[] f = Arrays.copyOf(mCurrentTrim, mCurrentTrim.length);
        f[side] += fractionx;
        if (f[side] < 0) f[side] = 0;
        if (f[side] > .8) f[side] = .8f;
        f[side + 3] += fractiony;
        if (f[side + 3] < 0) f[side + 3] = 0;
        if (f[side + 3] > .8) f[side + 3] = .8f;
        mState1.mCubeVolume = new Cube(mState1.mVolume, 5f, f);
        mState1.mCubeScreen = new TriData(mState1.mCubeVolume);
        mState1.mCubeScreen.scale(mState1.mVolume.mVoxelDim);
    }

    public void resetCut() {
        Arrays.fill(mCurrentTrim, 0);
        mState1.mCubeVolume = new Cube(mState1.mVolume, 5f, mCurrentTrim);
        mState1.mCubeScreen = new TriData(mState1.mCubeVolume);
        mState1.mCubeScreen.scale(mState1.mVolume.mVoxelDim);
        mState1.mTransform.look(looks[last_look], mState1.mCubeScreen, getWidth(), getHeight());
        mState1.mTransform.setScreenWidth(.6f * mState1.mTransform.getScreenWidth());
        last_look = (last_look + 1) % looks.length;
        render(4);
    }

    public void setVolume(RenderScript rs, Volume v) {
        mState1.mRs = rs;
        mState1.mVolume = v;
        mState1.mCubeVolume = new Cube(mState1.mVolume, 5f);
        mState1.mCubeScreen = new TriData(mState1.mCubeVolume);
        mState1.mCubeScreen.scale(v.mVoxelDim);
        mState1.mTransform.setVoxelDim(v.mVoxelDim);
        mState1.mTransform.look(ViewMatrix.DOWN_AT, mState1.mCubeScreen, getWidth(), getHeight());
        setLook(mState1.mVolume.getLookNames()[0]);
    }

    protected void look(int k) {
        mState1.mTransform.look(looks[k], mState1.mCubeVolume, getWidth(), getHeight());
        render(4);
        render(1);
    }

    void render(int downSample) {

        if (mRenderTesk == null) {
            mRenderTesk = new VrRenderTesk();
            refresh = 0;
            mRenderTesk.execute(downSample);
        } else {
            refresh = downSample;
        }
    }

    public String[] getLooks() {
        return mState1.mVolume.getLookNames();
    }

    public void setLook(String look) {
        int[][] color = mState1.mVolume.getLookColor(look);
        int[][] opacity = mState1.mVolume.getLookOpactiy(look);
        mState1.mMaterial.setup(opacity, color);
        if (mBinGridTask == null) {
            mBinGridTask = new VrBinGridTask();
            mBinGridTask.execute(mState1.mVolume);
        }
    }

    class VrRenderTesk extends AsyncTask<Integer, String, Long> {

        long m_last_time;

        @Override
        protected void onPreExecute() {
            mStateLow.copyData(mState1);
        }

        @Override
        protected void onCancelled() {
            mPipline.cancel();
        }

        @Override
        protected Long doInBackground(Integer... down) {
            if (mState1.mRs == null) return 0L;
            if (mSurfaceTexture == null) return 0L;
            int sample = 4;
            VrState state = mStateLow;
            if (down[0] == 1) {
                if (mPreviousMode == 4) {
                    mState1.copyData(mLastDrawn);
                } else {
                    mState1.copyData(mStateLow);
                }
                // mStateLow.mScrAllocation.setSurface(null);
                state = mState1;
                sample = 1;
                if (mStateLow.mScrAllocation != null) {
                    mStateLow.mScrAllocation.setSurface(null);
                }
            } else {
                if (mState1.mScrAllocation != null) {
                    mState1.mScrAllocation.setSurface(null);
                }
            }

            if (mPreviousMode != sample) {
                if (mSurface != null) {
                    mSurface.release();
                }
                mSurface = new Surface(mSurfaceTexture);
            }
            mPreviousMode = sample;

            int img_width = getWidth() / sample;
            int img_height = getHeight() / sample;
            state.createOutputAllocation(mSurface, img_width, img_height);

            mPipline.initBuffers(state);

            if (mPipline.isCancel()) {
                return 0L;
            }
            long start = System.nanoTime();
            addTimeLine(null);
            mPipline.setupTriangles(state);

            if (mPipline.isCancel()) {
                return 0L;
            }
            mPipline.rasterizeTriangles(state);

            if (mPipline.isCancel()) {
                return 0L;
            }
            mPipline.raycast(state);

            if (mPipline.isCancel()) {
                return 0L;
            }
            mLastDrawn.copyData(state);
            state.mRs.finish();
            state.mScrAllocation.ioSend();

            long time = System.nanoTime();
            addLine("vr(" + img_width + "," + img_height + "): " + (time - start) / 1E6f + " ms");
            return 0L;
        }

        private void addTimeLine(String line) {
            if (line == null) {
                m_last_time = System.nanoTime();
                return;
            }
            long time = System.nanoTime();
            float ftime = (time - m_last_time) / 1E6f;
            if (ftime > 100)
                addLine(line + ": " + (ftime / 1E3f) + " sec");
            else
                addLine(line + ": " + (ftime) + " ms");
            m_last_time = System.nanoTime();
        }

        private void addLine(String line) {
            publishProgress(line);
        }

        protected void onProgressUpdate(String... progress) {
            Log.v(LOGTAG, progress[0]);
        }

        protected void onPostExecute(Long result) {
            invalidate();
            mRenderTesk = null;
            if (refresh != 0) {
                render(refresh);
            }
        }
    }

    class VrBinGridTask extends AsyncTask<Volume, String, Long> {

        @Override
        protected Long doInBackground(Volume... v) {
            mState1.mRsMask = new RsBrickedBitMask(mState1);
            mState1.mRs.finish();
            return 0L;
        }

        protected void onProgressUpdate(String... progress) {
            Log.v(LOGTAG, progress[0]);
        }

        protected void onPostExecute(Long result) {
            mBinGridTask = null;
            render(4);
            render(1);
        }
    }
}
