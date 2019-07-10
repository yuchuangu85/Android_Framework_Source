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

package com.example.android.rs.blasbenchmark;

import android.app.Activity;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.view.View;
import android.graphics.Point;
import android.view.WindowManager;
import android.text.method.ScrollingMovementMethod;

import android.util.Log;
import android.renderscript.ScriptC;
import android.renderscript.RenderScript;
import android.renderscript.Type;
import android.renderscript.Allocation;
import android.renderscript.Element;
import android.renderscript.Script;

public class BlasBenchmark extends Activity {


    private final String TAG = "BLAS";
    public final String RESULT_FILE = "blas_benchmark_result.csv";

    private int mTestList[];
    private float mTestResults[];
    private String mTestInfo[];

    private TextView mTextView;
    private boolean mToggleLong;
    private boolean mTogglePause;
    private boolean mDemoMode;

    // In demo mode this is used to count updates in the pipeline.  It's
    // incremented when work is submitted to RS and decremented when invalidate is
    // called to display a result.

    // Message processor to handle notifications for when kernel completes
    private class MessageProcessor extends RenderScript.RSMessageHandler {
        MessageProcessor() {
        }

        public void run() {
            synchronized(mProcessor) {
                mProcessor.notifyAll();
            }
        }
    }


    /////////////////////////////////////////////////////////////////////////
    // Processor is a helper thread for running the work without
    // blocking the UI thread.
    class Processor extends Thread {
        RenderScript mRS;

        private float mLastResult;
        private boolean mRun = true;
        private boolean mDoingBenchmark;
        private TestBase mTest;

        private boolean mBenchmarkMode;

        void runTest() {
            mTest.runTest();
        }

        Processor(RenderScript rs, boolean benchmarkMode) {
            mRS = rs;
            mRS.setMessageHandler(new MessageProcessor());
            mBenchmarkMode = benchmarkMode;
            start();
        }

        class Result {
            float totalTime;
            int iterations;
            String testInfo;
        }

        // Run one loop of kernels for at least the specified minimum time.
        // The function returns the average time in ms for the test run
        private Result runBenchmarkLoop(float minTime) {
            Result r = new Result();
            long t = java.lang.System.currentTimeMillis();

            r.testInfo = mTest.getTestInfo();
            do {
                // Run the kernel
                mTest.runTest();
                r.iterations ++;
                // Send our RS message handler a message so we know when this work has completed
                mRS.sendMessage(0, null);

                long t2 = java.lang.System.currentTimeMillis();
                r.totalTime += (t2 - t) / 1000.f;
                t = t2;
            } while (r.totalTime < minTime);

            // Wait for any stray operations to complete and update the final time
            mRS.finish();
            long t2 = java.lang.System.currentTimeMillis();
            r.totalTime += (t2 - t) / 1000.f;
            t = t2;
            return r;
        }


        // Get a benchmark result for a specific test
        private Result getBenchmark() {
            mDoingBenchmark = true;

            long result = 0;
            float runtime = 1.f;
            if (mToggleLong) {
                runtime = 10.f;
            }

            // We run a short bit of work before starting the actual test
            // this is to let any power management do its job and respond
            runBenchmarkLoop(0.3f);

            // Run the actual benchmark
            Result r = runBenchmarkLoop(runtime);

            Log.v("rs", "Test: time=" + r.totalTime +"s,  iterations=" + r.iterations +
                  ", avg=" + r.totalTime / r.iterations * 1000.f);

            mDoingBenchmark = false;
            return r;
        }

        public void run() {
            while (mRun) {
                // Our loop for launching tests or benchmarks
                synchronized(this) {
                    // We may have been asked to exit while waiting
                    if (!mRun) return;
                }

                if (mBenchmarkMode) {
                    // Loop over the tests we want to benchmark
                    for (int ct=0; (ct < mTestList.length) && mRun; ct++) {

                        // For reproducibility we wait a short time for any sporadic work
                        // created by the user touching the screen to launch the test to pass.
                        // Also allows for things to settle after the test changes.
                        mRS.finish();
                        try {
                            sleep(250);
                        } catch(InterruptedException e) {
                        }

                        // If we just ran a test, we destroy it here to relieve some memory pressure
                        if (mTest != null) {
                            mTest.destroy();
                        }

                        // Select the next test
                        mTest = changeTest(mTestList[ct], false);
                        // If the user selected the "long pause" option, wait
                        if (mTogglePause) {
                            for (int i=0; (i < 100) && mRun; i++) {
                                try {
                                    sleep(100);
                                } catch(InterruptedException e) {
                                }
                            }
                        }

                        // Run the test
                        Result r = getBenchmark();
                        mTestResults[ct] = r.totalTime / r.iterations * 1000.f;
                        mTestInfo[ct] = r.testInfo;
                    }
                    onBenchmarkFinish(mRun);
                } else {
                    // Run the kernel
                    runTest();
                    // Send our RS message handler a message so we know when this work has completed
                    mRS.sendMessage(0, null);
                }
            }

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

            if (mTest != null) {
                mTest.destroy();
                mTest = null;
            }
            mRS.destroy();
            mRS = null;
        }
    }


    private boolean mDoingBenchmark;
    public Processor mProcessor;

    TestBase changeTest(BlasTestList.TestName t, boolean setupUI) {
        TestBase tb = BlasTestList.newTest(t);
        tb.createBaseTest(this);
        return tb;
    }

    TestBase changeTest(int id, boolean setupUI) {
        BlasTestList.TestName t = BlasTestList.TestName.values()[id];
        return changeTest(t, setupUI);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView textView = new TextView(this);
        textView.setTextSize(20);
        textView.setText("BLAS BenchMark Running.");
        setContentView(textView);
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
            intent.putExtra("testinfo", mTestInfo);
            setResult(RESULT_OK, intent);
        } else {
            setResult(RESULT_CANCELED);
        }
        finish();
    }


    void startProcessor() {
        mProcessor = new Processor(RenderScript.create(this), !mDemoMode);
        if (mDemoMode) {
            mProcessor.mTest = changeTest(mTestList[0], true);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        Intent i = getIntent();
        mTestList = i.getIntArrayExtra("tests");

        mToggleLong = i.getBooleanExtra("enable long", false);
        mTogglePause = i.getBooleanExtra("enable pause", false);
        mDemoMode = i.getBooleanExtra("demo", false);

        mTestResults = new float[mTestList.length];
        mTestInfo = new String[mTestList.length];

        startProcessor();
    }

    protected void onDestroy() {
        super.onDestroy();
    }
}
