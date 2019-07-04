/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.example.android.nn.benchmark;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;

public class NNBenchmark extends Activity {
    protected static final String TAG = "NN_BENCHMARK";

    private int mTestList[];
    private float mTestResults[];
    private String mTestInfo[];

    private TextView mTextView;
    private boolean mToggleLong;
    private boolean mTogglePause;

    // In demo mode this is used to count updates in the pipeline.  It's
    // incremented when work is submitted to RS and decremented when invalidate is
    // called to display a result.
    private boolean mDemoMode;

    // Initialize the parameters for Instrumentation tests.
    protected void prepareInstrumentationTest() {
        mTestList = new int[1];
        mTestResults = new float[1];
        mTestInfo = new String[1];
        mDemoMode = false;
        mProcessor = new Processor(!mDemoMode);
    }

    /////////////////////////////////////////////////////////////////////////
    // Processor is a helper thread for running the work without
    // blocking the UI thread.
    class Processor extends Thread {

        private float mLastResult;
        private boolean mRun = true;
        private boolean mDoingBenchmark;
        private NNTestBase mTest;

        private boolean mBenchmarkMode;

        void runTest() {
            mTest.runTest();
        }

        Processor(boolean benchmarkMode) {
            mBenchmarkMode = benchmarkMode;
        }

        class Result {
            float totalTime;
            int iterations;
            String testInfo;
        }

        // Method to retreive benchmark results for instrumentation tests.
        float getInstrumentationResult(NNTestList.TestName t) {
            mTest = changeTest(t);
            Result r = getBenchmark();
            return r.totalTime / r.iterations * 1000.f;
        }

        // Run one loop of kernels for at least the specified minimum time.
        // The function returns the average time in ms for the test run
        private Result runBenchmarkLoop(float minTime) {
            Result r = new Result();
            long start = java.lang.System.currentTimeMillis();

            r.testInfo = mTest.getTestInfo();
            do {
                // Run the kernel
                mTest.runTest();
                r.iterations ++;

                long current = java.lang.System.currentTimeMillis();
                r.totalTime = (current - start) / 1000.f;
            } while (r.totalTime < minTime);

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

            Log.v(TAG, "Test: time=" + r.totalTime +"s,  iterations=" + r.iterations +
                    ", avg=" + r.totalTime / r.iterations * 1000.f + " " + r.testInfo);

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
                        try {
                            sleep(250);
                        } catch(InterruptedException e) {
                        }

                        // If we just ran a test, we destroy it here to relieve some memory pressure
                        if (mTest != null) {
                            mTest.destroy();
                        }

                        // Select the next test
                        mTest = changeTest(mTestList[ct]);
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
        }
    }


    private boolean mDoingBenchmark;
    public Processor mProcessor;

    NNTestBase changeTest(NNTestList.TestName t) {
        NNTestBase tb = NNTestList.newTest(t);
        tb.createBaseTest(this);
        return tb;
    }

    NNTestBase changeTest(int id) {
        NNTestList.TestName t = NNTestList.TestName.values()[id];
        return changeTest(t);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView textView = new TextView(this);
        textView.setTextSize(20);
        textView.setText("NN BenchMark Running.");
        setContentView(textView);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mProcessor != null) {
            mProcessor.exit();
        }
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

    @Override
    protected void onResume() {
        super.onResume();
        Intent i = getIntent();
        mTestList = i.getIntArrayExtra("tests");

        mToggleLong = i.getBooleanExtra("enable long", false);
        mTogglePause = i.getBooleanExtra("enable pause", false);
        mDemoMode = i.getBooleanExtra("demo", false);

        if (mTestList != null) {
            mTestResults = new float[mTestList.length];
            mTestInfo = new String[mTestList.length];
            mProcessor = new Processor(!mDemoMode);
            if (mDemoMode) {
                mProcessor.mTest = changeTest(mTestList[0]);
            }
            mProcessor.start();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }
}
