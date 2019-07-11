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


import android.os.Bundle;
import android.util.Log;

import com.example.android.rs.blasbenchmark.BlasTestList.TestName;
import com.example.android.rs.blasbenchmark.BlasTestRunner;

import android.test.ActivityInstrumentationTestCase2;
import android.test.suitebuilder.annotation.LargeTest;

/**
 * BLAS benchmark test.
 * To run the test, please use command
 *
 * adb shell am instrument -e iteration <n> -w com.example.android.rs.blasbenchmark/.BlasTestRunner
 *
 */
public class BlasTest extends ActivityInstrumentationTestCase2<BlasBenchmark> {
    private final String TAG = "BLAS Test";
    private final String TEST_NAME = "Testname";
    private final String ITERATIONS = "Iterations";
    private final String BENCHMARK = "Benchmark";
    private static int INSTRUMENTATION_IN_PROGRESS = 2;
    private int mIteration;
    private BlasBenchmark mActivity;

    public BlasTest() {
        super(BlasBenchmark.class);
    }

    @Override
    public void setUp() throws Exception {
        super.setUp();
        setActivityInitialTouchMode(false);
        mActivity = getActivity();
        BlasTestRunner mRunner = (BlasTestRunner) getInstrumentation();
        mIteration = mRunner.mIteration;
        assertTrue("please enter a valid iteration value", mIteration > 0);
   }

    @Override
    public void tearDown() throws Exception {
        super.tearDown();
    }

    class TestAction implements Runnable {
        TestName mTestName;
        float mResult;
        public TestAction(TestName testName) {
            mTestName = testName;
        }
        public void run() {
            mActivity.changeTest(mTestName, false);
            //mResult = mActivity.getBenchmark();
            Log.v(TAG, "Benchmark for test \"" + mTestName.toString() + "\" is: " + mResult);
            synchronized(this) {
                this.notify();
            }
        }
        public float getBenchmark() {
            return mResult;
        }
    }

    // Set the benchmark thread to run on ui thread
    // Synchronized the thread such that the test will wait for the benchmark thread to finish
    public void runOnUiThread(Runnable action) {
        synchronized(action) {
            mActivity.runOnUiThread(action);
            try {
                action.wait();
            } catch (InterruptedException e) {
                Log.v(TAG, "waiting for action running on UI thread is interrupted: " +
                        e.toString());
            }
        }
    }

    public void runTest(TestAction ta, String testName) {
        float sum = 0;
        for (int i = 0; i < mIteration; i++) {
            runOnUiThread(ta);
            float bmValue = ta.getBenchmark();
            Log.v(TAG, "results for iteration " + i + " is " + bmValue);
            sum += bmValue;
        }
        float avgResult = sum/mIteration;

        // post result to INSTRUMENTATION_STATUS
        Bundle results = new Bundle();
        results.putString(TEST_NAME, testName);
        results.putInt(ITERATIONS, mIteration);
        results.putFloat(BENCHMARK, avgResult);
        getInstrumentation().sendStatus(INSTRUMENTATION_IN_PROGRESS, results);
    }

    // Test case 0: SGEMM Test Small
    @LargeTest
    public void testSGEMMSmall() {
        TestAction ta = new TestAction(TestName.SGEMM_SMALL);
        runTest(ta, TestName.SGEMM_SMALL.name());
    }

    // Test case 1: SGEMM Test Medium
    @LargeTest
    public void testSGEMMedium() {
        TestAction ta = new TestAction(TestName.SGEMM_MEDIUM);
        runTest(ta, TestName.SGEMM_MEDIUM.name());
    }

    // Test case 2: SGEMM Test Large
    @LargeTest
    public void testSGEMMLarge() {
        TestAction ta = new TestAction(TestName.SGEMM_LARGE);
        runTest(ta, TestName.SGEMM_LARGE.name());
    }

    // Test case 3: 8Bit GEMM Test Small
    @LargeTest
    public void testBNNMSmall() {
        TestAction ta = new TestAction(TestName.BNNM_SMALL);
        runTest(ta, TestName.BNNM_SMALL.name());
    }

    // Test case 4: 8Bit GEMM Test Medium
    @LargeTest
    public void testBNNMMMedium() {
        TestAction ta = new TestAction(TestName.BNNM_MEDIUM);
        runTest(ta, TestName.BNNM_MEDIUM.name());
    }

    // Test case 5: 8Bit GEMM Test Large
    @LargeTest
    public void testBNNMLarge() {
        TestAction ta = new TestAction(TestName.BNNM_LARGE);
        runTest(ta, TestName.BNNM_LARGE.name());
    }
}
