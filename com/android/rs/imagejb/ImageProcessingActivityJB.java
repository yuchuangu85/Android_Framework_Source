/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.rs.imagejb;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.view.View;
import android.view.TextureView;
import android.view.Surface;
import android.graphics.SurfaceTexture;
import android.graphics.Point;

import android.util.Log;
import android.renderscript.ScriptC;
import android.renderscript.RenderScript;
import android.renderscript.Type;
import android.renderscript.Allocation;
import android.renderscript.Element;
import android.renderscript.Script;


public class ImageProcessingActivityJB extends Activity
                                       implements SeekBar.OnSeekBarChangeListener,
                                                  TextureView.SurfaceTextureListener {
    private final String TAG = "Img";

    private Spinner mSpinner;
    private SeekBar mBar1;
    private SeekBar mBar2;
    private SeekBar mBar3;
    private SeekBar mBar4;
    private SeekBar mBar5;
    private TextView mText1;
    private TextView mText2;
    private TextView mText3;
    private TextView mText4;
    private TextView mText5;
    private SizedTV mDisplayView;

    private int mTestList[];
    private float mTestResults[];

    private boolean mToggleIO;
    private boolean mToggleDVFS;
    private boolean mToggleLong;
    private boolean mTogglePause;
    private int mBitmapWidth;
    private int mBitmapHeight;

    static public class SizedTV extends TextureView {
        int mWidth;
        int mHeight;

        public SizedTV(android.content.Context c) {
            super(c);
            mWidth = 800;
            mHeight = 450;
        }

        public SizedTV(android.content.Context c, android.util.AttributeSet attrs) {
            super(c, attrs);
            mWidth = 800;
            mHeight = 450;
        }

        public SizedTV(android.content.Context c, android.util.AttributeSet attrs, int f) {
            super(c, attrs, f);
            mWidth = 800;
            mHeight = 450;
        }

        protected void onMeasure(int w, int h) {
            setMeasuredDimension(mWidth, mHeight);
        }
    }

    /////////////////////////////////////////////////////////////////////////

    class Processor extends Thread {
        RenderScript mRS;
        Allocation mInPixelsAllocation;
        Allocation mInPixelsAllocation2;
        Allocation mOutDisplayAllocation;
        Allocation mOutPixelsAllocation;

        private Surface mOutSurface;
        private float mLastResult;
        private boolean mRun = true;
        private int mOp = 0;
        private boolean mDoingBenchmark;
        private TestBase mTest;
        private TextureView mDisplayView;

        private boolean mBenchmarkMode;

        Processor(RenderScript rs, TextureView v, boolean benchmarkMode) {
            mRS = rs;
            mDisplayView = v;

            switch(mBitmapWidth) {
            case 3840:
                mInPixelsAllocation = Allocation.createFromBitmapResource(
                        mRS, getResources(), R.drawable.img3840x2160a);
                mInPixelsAllocation2 = Allocation.createFromBitmapResource(
                        mRS, getResources(), R.drawable.img3840x2160b);
                break;
            case 1920:
                mInPixelsAllocation = Allocation.createFromBitmapResource(
                        mRS, getResources(), R.drawable.img1920x1080a);
                mInPixelsAllocation2 = Allocation.createFromBitmapResource(
                        mRS, getResources(), R.drawable.img1920x1080b);
                break;
            case 1280:
                mInPixelsAllocation = Allocation.createFromBitmapResource(
                        mRS, getResources(), R.drawable.img1280x720a);
                mInPixelsAllocation2 = Allocation.createFromBitmapResource(
                        mRS, getResources(), R.drawable.img1280x720b);
                break;
            case 800:
                mInPixelsAllocation = Allocation.createFromBitmapResource(
                        mRS, getResources(), R.drawable.img800x450a);
                mInPixelsAllocation2 = Allocation.createFromBitmapResource(
                        mRS, getResources(), R.drawable.img800x450b);
                break;
            }

            mOutDisplayAllocation = Allocation.createTyped(mRS, mInPixelsAllocation.getType(),
                                                               Allocation.MipmapControl.MIPMAP_NONE,
                                                               Allocation.USAGE_SCRIPT |
                                                               Allocation.USAGE_IO_OUTPUT);
            mOutPixelsAllocation = mOutDisplayAllocation;

            if (!mToggleIO) {
                // Not using USAGE_IO for the script so create a non-io kernel to copy from
                mOutPixelsAllocation = Allocation.createTyped(mRS, mInPixelsAllocation.getType(),
                                                              Allocation.MipmapControl.MIPMAP_NONE,
                                                              Allocation.USAGE_SCRIPT);
            }

            mBenchmarkMode = benchmarkMode;
            start();
        }

        private float getBenchmark() {
            mDoingBenchmark = true;

            mTest.setupBenchmark();
            long result = 0;
            long runtime = 1000;
            if (mToggleLong) {
                runtime = 10000;
            }

            if (mToggleDVFS) {
                mDvfsWar.go();
            }

            //Log.v("rs", "Warming");
            long t = java.lang.System.currentTimeMillis() + 250;
            do {
                mTest.runTest();
                mTest.finish();
            } while (t > java.lang.System.currentTimeMillis());
            //mHandler.sendMessage(Message.obtain());

            //Log.v("rs", "Benchmarking");
            int ct = 0;
            t = java.lang.System.currentTimeMillis();
            do {
                mTest.runTest();
                mTest.finish();
                ct++;
            } while ((t + runtime) > java.lang.System.currentTimeMillis());
            t = java.lang.System.currentTimeMillis() - t;
            float ft = (float)t;
            ft /= ct;

            mTest.exitBenchmark();
            mDoingBenchmark = false;

            android.util.Log.v("rs", "bench " + ft);
            return ft;
        }

        private Handler mHandler = new Handler() {
            // Allow the filter to complete without blocking the UI
            // thread.  When the message arrives that the op is complete
            // we will either mark completion or start a new filter if
            // more work is ready.  Either way, display the result.
            @Override
            public void handleMessage(Message msg) {
                synchronized(this) {
                    if (mRS == null || mOutPixelsAllocation == null) {
                        return;
                    }
                    if (mOutDisplayAllocation != mOutPixelsAllocation) {
                        mOutDisplayAllocation.copyFrom(mOutPixelsAllocation);
                    }
                    mOutDisplayAllocation.ioSend();
                    mDisplayView.invalidate();
                    //mTest.runTestSendMessage();
                }
            }
        };

        public void run() {
            Surface lastSurface = null;
            while (mRun) {
                synchronized(this) {
                    try {
                        this.wait();
                    } catch(InterruptedException e) {
                    }
                    if (!mRun) return;

                    if ((mOutSurface == null) || (mOutPixelsAllocation == null)) {
                        continue;
                    }

                    if (lastSurface != mOutSurface) {
                        mOutDisplayAllocation.setSurface(mOutSurface);
                        lastSurface = mOutSurface;
                    }
                }

                if (mBenchmarkMode) {
                    for (int ct=0; (ct < mTestList.length) && mRun; ct++) {
                        mRS.finish();

                        try {
                            sleep(250);
                        } catch(InterruptedException e) {
                        }

                        if (mTest != null) {
                            mTest.destroy();
                        }

                        mTest = changeTest(mTestList[ct]);
                        if (mTogglePause) {
                            for (int i=0; (i < 100) && mRun; i++) {
                                try {
                                    sleep(100);
                                } catch(InterruptedException e) {
                                }
                            }
                        }

                        mTestResults[ct] = getBenchmark();
                        mHandler.sendMessage(Message.obtain());
                    }
                    onBenchmarkFinish(mRun);
                }
            }

        }

        public void update() {
            synchronized(this) {
                if (mOp == 0) {
                    mOp = 2;
                }
                notifyAll();
            }
        }

        public void setSurface(Surface s) {
            synchronized(this) {
                mOutSurface = s;
                notifyAll();
            }
            //update();
        }

        public void exit() {
            mRun = false;

            synchronized(this) {
                notifyAll();
            }

            try {
                this.join();
            } catch(InterruptedException e) {
            }

            mInPixelsAllocation.destroy();
            mInPixelsAllocation2.destroy();
            if (mOutPixelsAllocation != mOutDisplayAllocation) {
                mOutPixelsAllocation.destroy();
            }
            mOutDisplayAllocation.destroy();
            mRS.destroy();

            mInPixelsAllocation = null;
            mInPixelsAllocation2 = null;
            mOutPixelsAllocation = null;
            mOutDisplayAllocation = null;
            mRS = null;
        }
    }

    ///////////////////////////////////////////////////////////////////////////////////////

    static class DVFSWorkaround {
        static class spinner extends Thread {
            boolean mRun = true;
            long mNextSleep;

            spinner() {
                setPriority(MIN_PRIORITY);
                start();
            }

            public void run() {
                while (mRun) {
                    Thread.yield();
                    synchronized(this) {
                        long t = java.lang.System.currentTimeMillis();
                        if (t > mNextSleep) {
                            try {
                                this.wait();
                            } catch(InterruptedException e) {
                            }
                        }
                    }
                }
            }

            public void go(long t) {
                synchronized(this) {
                    mNextSleep = t;
                    notifyAll();
                }
            }
        }

        spinner s1;
        DVFSWorkaround() {
            s1 = new spinner();
        }

        void go() {
            long t = java.lang.System.currentTimeMillis() + 2000;
            s1.go(t);
        }

        void destroy() {
            synchronized(this) {
                s1.mRun = false;
                notifyAll();
            }
        }
    }
    DVFSWorkaround mDvfsWar = new DVFSWorkaround();

    ///////////////////////////////////////////////////////////


    private boolean mDoingBenchmark;
    public Processor mProcessor;


    private Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            mDisplayView.invalidate();
        }
    };

    public void updateDisplay() {
        mHandler.sendMessage(Message.obtain());
        //mProcessor.update();
    }

    TestBase changeTest(int id) {
        IPTestListJB.TestName t = IPTestListJB.TestName.values()[id];
        TestBase tb = IPTestListJB.newTest(t);
        tb.createBaseTest(this);
        //setupBars(tb);
        return tb;
    }

    public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
        if (fromUser) {
            if (seekBar == mBar1) {
                mProcessor.mTest.onBar1Changed(progress);
            } else if (seekBar == mBar2) {
                mProcessor.mTest.onBar2Changed(progress);
            } else if (seekBar == mBar3) {
                mProcessor.mTest.onBar3Changed(progress);
            } else if (seekBar == mBar4) {
                mProcessor.mTest.onBar4Changed(progress);
            } else if (seekBar == mBar5) {
                mProcessor.mTest.onBar5Changed(progress);
            }
            mProcessor.update();
        }
    }

    public void onStartTrackingTouch(SeekBar seekBar) {
    }

    public void onStopTrackingTouch(SeekBar seekBar) {
    }

    void setupBars(TestBase t) {
        mSpinner.setVisibility(View.VISIBLE);
        t.onSpinner1Setup(mSpinner);

        mBar1.setVisibility(View.VISIBLE);
        mText1.setVisibility(View.VISIBLE);
        t.onBar1Setup(mBar1, mText1);

        mBar2.setVisibility(View.VISIBLE);
        mText2.setVisibility(View.VISIBLE);
        t.onBar2Setup(mBar2, mText2);

        mBar3.setVisibility(View.VISIBLE);
        mText3.setVisibility(View.VISIBLE);
        t.onBar3Setup(mBar3, mText3);

        mBar4.setVisibility(View.VISIBLE);
        mText4.setVisibility(View.VISIBLE);
        t.onBar4Setup(mBar4, mText4);

        mBar5.setVisibility(View.VISIBLE);
        mText5.setVisibility(View.VISIBLE);
        t.onBar5Setup(mBar5, mText5);
    }

    void hideBars() {
        mSpinner.setVisibility(View.INVISIBLE);

        mBar1.setVisibility(View.INVISIBLE);
        mText1.setVisibility(View.INVISIBLE);

        mBar2.setVisibility(View.INVISIBLE);
        mText2.setVisibility(View.INVISIBLE);

        mBar3.setVisibility(View.INVISIBLE);
        mText3.setVisibility(View.INVISIBLE);

        mBar4.setVisibility(View.INVISIBLE);
        mText4.setVisibility(View.INVISIBLE);

        mBar5.setVisibility(View.INVISIBLE);
        mText5.setVisibility(View.INVISIBLE);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

        mDisplayView = (SizedTV) findViewById(R.id.display);

        mSpinner = (Spinner) findViewById(R.id.spinner1);

        mBar1 = (SeekBar) findViewById(R.id.slider1);
        mBar2 = (SeekBar) findViewById(R.id.slider2);
        mBar3 = (SeekBar) findViewById(R.id.slider3);
        mBar4 = (SeekBar) findViewById(R.id.slider4);
        mBar5 = (SeekBar) findViewById(R.id.slider5);

        mBar1.setOnSeekBarChangeListener(this);
        mBar2.setOnSeekBarChangeListener(this);
        mBar3.setOnSeekBarChangeListener(this);
        mBar4.setOnSeekBarChangeListener(this);
        mBar5.setOnSeekBarChangeListener(this);

        mText1 = (TextView) findViewById(R.id.slider1Text);
        mText2 = (TextView) findViewById(R.id.slider2Text);
        mText3 = (TextView) findViewById(R.id.slider3Text);
        mText4 = (TextView) findViewById(R.id.slider4Text);
        mText5 = (TextView) findViewById(R.id.slider5Text);
    }

    @Override
    protected void onPause() {
        super.onPause();
        mProcessor.exit();
    }

    public void onBenchmarkFinish(boolean ok) {
        if (ok) {
            Intent intent = new Intent();
            intent.putExtra("tests", mTestList);
            intent.putExtra("results", mTestResults);
            setResult(RESULT_OK, intent);
        } else {
            setResult(RESULT_CANCELED);
        }
        finish();
    }

    @Override
    protected void onResume() {
        super.onResume();
        Intent i = getIntent();
        mTestList = i.getIntArrayExtra("tests");

        mToggleIO = i.getBooleanExtra("enable io", false);
        mToggleDVFS = i.getBooleanExtra("enable dvfs", false);
        mToggleLong = i.getBooleanExtra("enable long", false);
        mTogglePause = i.getBooleanExtra("enable pause", false);
        mBitmapWidth = i.getIntExtra("resolution X", 0);
        mBitmapHeight = i.getIntExtra("resolution Y", 0);

        mTestResults = new float[mTestList.length];

        hideBars();

        Point size = new Point();
        getWindowManager().getDefaultDisplay().getSize(size);

        int mScreenWidth = size.x;
        int mScreenHeight = size.y;

        int tw = mBitmapWidth;
        int th = mBitmapHeight;

        if (tw > mScreenWidth || th > mScreenHeight) {
            float s1 = (float)tw / (float)mScreenWidth;
            float s2 = (float)th / (float)mScreenHeight;

            if (s1 > s2) {
                tw /= s1;
                th /= s1;
            } else {
                tw /= s2;
                th /= s2;
            }
        }

        android.util.Log.v("rs", "TV sizes " + tw + ", " + th);

        mDisplayView.mWidth = tw;
        mDisplayView.mHeight = th;
        //mDisplayView.setTransform(new android.graphics.Matrix());

        mProcessor = new Processor(RenderScript.create(this), mDisplayView, true);
        mDisplayView.setSurfaceTextureListener(this);
    }

    protected void onDestroy() {
        super.onDestroy();
    }


    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
        mProcessor.setSurface(new Surface(surface));
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
        mProcessor.setSurface(new Surface(surface));
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        mProcessor.setSurface(null);
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surface) {
    }
}
