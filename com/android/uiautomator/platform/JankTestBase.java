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

package com.android.uiautomator.platform;

import android.os.Bundle;
import android.os.Environment;
import android.util.Log;

import com.android.uiautomator.core.UiDevice;
import com.android.uiautomator.testrunner.UiAutomatorTestCase;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Base class for jank test.
 * All jank test needs to extend JankTestBase
 */
public class JankTestBase extends UiAutomatorTestCase {
    private static final String TAG = JankTestBase.class.getSimpleName();

    protected UiDevice mDevice;
    protected TestWatchers mTestWatchers = null;
    protected BufferedWriter mWriter = null;
    protected BufferedWriter mStatusWriter = null;
    protected int mIteration = 20; // default iteration is set 20
    /* can be used to enable/disable systrace in the test */
    protected int mTraceTime = 0;
    protected Bundle mParams;
    protected String mTestCaseName;
    protected int mSuccessTestRuns = 0;
    protected Thread mThread = null;

    // holds all params for the derived tests
    private static final String PROPERTY_FILE_NAME = "UiJankinessTests.conf";
    private static final String PARAM_CONFIG = "conf";
    private static final String LOCAL_TMP_DIR = "/data/local/tmp/";
    // File that hold the test results
    private static String OUTPUT_FILE_NAME = LOCAL_TMP_DIR + "UiJankinessTestsOutput.txt";
    // File that hold test status, e.g successful test iterations
    private static String STATUS_FILE_NAME = LOCAL_TMP_DIR + "UiJankinessTestsStatus.txt";
    private static final String RAW_DATA_DIR = LOCAL_TMP_DIR + "UiJankinessRawData";

    private static int SUCCESS_THRESHOLD = 80;
    private static boolean DEBUG = false;

    /* default animation time is set to 2 seconds */
    protected static final long DEFAULT_ANIMATION_TIME = 2 * 1000;
    /* default swipe steps for fling animation */
    protected static final int DEFAULT_FLING_STEPS = 8;

    /* Array to record jankiness data in each test iteration */
    private int[] jankinessArray;
    /* Array to record frame rate in each test iteration */
    private double[] frameRateArray;
    /* Array to save max accumulated frame number in each test iteration */
    private int[] maxDeltaVsyncArray;
    /* Default file to store the systrace */
    private static final File SYSTRACE_DIR = new File(LOCAL_TMP_DIR, "systrace");
    /* Default trace file name */
    private static final String TRACE_FILE_NAME = "trace.txt";
    /* Default tracing time is 5 seconds */
    private static final int DEFAULT_TRACE_TIME = 5; // 5 seconds
    // Command to dump compressed trace data
    private static final String ATRACE_COMMAND = "atrace -z -t %d gfx input view sched freq";

    /**
     * Thread to capture systrace log from the test
     */
    public class SystraceTracker implements Runnable {
        File mFile = new File(SYSTRACE_DIR, TRACE_FILE_NAME);
        int mTime = DEFAULT_TRACE_TIME;

        public SystraceTracker(int traceTime, String fileName) {
            try {
                if (!SYSTRACE_DIR.exists()) {
                    if (!SYSTRACE_DIR.mkdir()) {
                        log(String.format("create directory %s failed, you can manually create "
                                + "it and start the test again", SYSTRACE_DIR.getAbsolutePath()));
                        return;
                    }
                }
            } catch (SecurityException e) {
                Log.e(TAG, "creating directory failed?", e);
            }

            if (traceTime > 0) {
                mTime = traceTime;
            }
            if (fileName != null) {
                mFile = new File(SYSTRACE_DIR, fileName);
            }
        }

        @Override
        public void run() {
            String command = String.format(ATRACE_COMMAND, mTime);
            Log.v(TAG, "command: " + command);
            Process p = null;
            InputStream in = null;
            BufferedOutputStream out = null;
            try {
                p = Runtime.getRuntime().exec(command);
                Log.v(TAG, "write systrace into file: " + mFile.getAbsolutePath());
                // read bytes from the process output stream as the output is compressed
                byte[] buffer = new byte[1024];
                in = p.getInputStream();
                out = new BufferedOutputStream(new FileOutputStream(mFile));
                int n;
                while ((n = in.read(buffer)) != -1) {
                    out.write(buffer, 0, n);
                    out.flush();
                }
                in.close();
                out.close();
                // read error message
                BufferedReader br = new BufferedReader(new InputStreamReader(p.getErrorStream()));
                String line;
                while ((line = br.readLine()) != null) {
                    Log.e(TAG, "Command return errors: " + line);
                }
                br.close();

                // Due to limited buffer size for standard input and output stream,
                // promptly reading from the input stream or output stream to avoid block
                int status = p.waitFor();
                if (status != 0) {
                    Log.e(TAG, String.format("Run shell command: %s, status: %s",
                            command, status));
                }
            } catch (InterruptedException e) {
                Log.e(TAG, "Exception from command " + command + ":");
                Log.e(TAG, "Thread interrupted? ", e);
            } catch (IOException e) {
                Log.e(TAG, "Open file error: ", e);
            } catch (IllegalThreadStateException e) {
                Log.e(TAG, "the process has not exit yet ", e);
            }
        }
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mDevice = UiDevice.getInstance();
        mTestWatchers = new TestWatchers(); // extends the common class UiWatchers
        mTestWatchers.registerAnrAndCrashWatchers();

        mWriter = new BufferedWriter(new FileWriter(new File(OUTPUT_FILE_NAME), true));
        mStatusWriter = new BufferedWriter(new FileWriter(new File(STATUS_FILE_NAME), true));

        mParams = getParams();
        if (mParams != null && !mParams.isEmpty()) {
            log("mParams is not empty, get properties.");
            String mIterationStr = getPropertyString(mParams, "iteration");
            if (mIterationStr != null) {
                mIteration = Integer.valueOf(mIterationStr);
            }
            String mTraceTimeStr = getPropertyString(mParams, "tracetime");
            if (mTraceTimeStr  != null) {
                mTraceTime = Integer.valueOf(mTraceTimeStr);
            }
        }
        jankinessArray = new int[mIteration];
        frameRateArray = new double[mIteration];
        maxDeltaVsyncArray = new int[mIteration];
        mTestCaseName = this.getName();

        mSuccessTestRuns = 0;
        mDevice.pressHome();
    }

    /**
     * Create a new thread for systrace and start the thread
     *
     * @param testCaseName
     * @param iteration
     */
    protected void startTrace(String testCaseName, int iteration) {
        if (mTraceTime > 0) {
            String outputFile = String.format("%s_%d_trace", mTestCaseName, iteration);
            mThread = new Thread(new SystraceTracker(mTraceTime, outputFile));
            mThread.start();
        }
    }

    /**
     * Wait for the tracing thread to exit
     */
    protected void endTrace() {
        if (mThread != null) {
            try {
                mThread.join();
            } catch (InterruptedException e) {
                Log.e(TAG, "wait for the trace thread to exit exception:", e);
            }
        }
    }

    /**
     * Expects a file from the command line via conf param or default following format each on its
     * own line. <code>
     *    key=Value
     *    Browser_URL1=cnn.com
     *    Browser_URL2=google.com
     *    Camera_ShutterDelay=1000
     *    etc...
     * </code>
     * @param Bundle params
     * @param key
     * @return the value of the property else defaultValue
     * @throws FileNotFoundException
     * @throws IOException
     */
    protected String getPropertyString(Bundle params, String key)
            throws FileNotFoundException, IOException {
        Properties prop = new Properties();
        prop.load(new FileInputStream(new File(LOCAL_TMP_DIR,
                params.getString(PARAM_CONFIG, PROPERTY_FILE_NAME))));
        String value = prop.getProperty(key);
        if (value != null && !value.isEmpty())
            return value;
        return null;
    }

    /**
     * Expects a file from the command line via conf param or default following format each on its
     * own line. <code>
     *    key=Value
     *    Browser_URL1=cnn.com
     *    Browser_URL2=google.com
     *    Camera_ShutterDelay=1000
     *    etc...
     * </code>
     * @param Bundle params
     * @param key
     * @return the value of the property else defaultValue
     * @throws FileNotFoundException
     * @throws IOException
     */
    protected long getPropertyLong(Bundle params, String key)
            throws FileNotFoundException, IOException {
        Properties prop = new Properties();
        prop.load(new FileInputStream(new File(LOCAL_TMP_DIR,
                params.getString(PARAM_CONFIG, PROPERTY_FILE_NAME))));
        String value = prop.getProperty(key);
        if (value != null && !value.trim().isEmpty())
            return Long.valueOf(value.trim());
        return 0;
    }

    /**
     * Verify the test result by comparing data sample size with expected value
     * @param expectedDataSize the expected data size
     */
    protected boolean validateResults(int expectedDataSize) {
        int receivedDataSize = SurfaceFlingerHelper.getDataSampleSize();
        return ((expectedDataSize > 0) && (receivedDataSize >= expectedDataSize));
    }

    /**
     * Process the raw data, calculate jankiness, frame rate and max accumulated frames number
     * @param testCaseName
     * @param iteration
     */
    protected void recordResults(String testCaseName, int iteration) {
        long refreshPeriod = SurfaceFlingerHelper.getRefreshPeriod();
        // if the raw directory doesn't exit, create the directory
        File rawDataDir = new File(RAW_DATA_DIR);
        try {
            if (!rawDataDir.exists()) {
                if (!rawDataDir.mkdir()) {
                    log(String.format("create directory %s failed, you can manually create " +
                            "it and start the test again", rawDataDir));
                }
            }
        } catch (SecurityException e) {
            Log.e(TAG, "create directory failed: ", e);
        }
        String rawFileName = String.format("%s/%s_%d.txt", RAW_DATA_DIR, testCaseName, iteration);
        // write results into a file
        BufferedWriter fw = null;
        try {
            fw = new BufferedWriter(new FileWriter(new File(rawFileName), false));
            fw.write(SurfaceFlingerHelper.getFrameBufferData());
        } catch (IOException e) {
            Log.e(TAG, "failed to write to file", e);
            return;
        } finally {
            try {
                if (fw != null) {
                    fw.close();
                }
            }
            catch (IOException e) {
                    Log.e(TAG, "close file failed.", e);
            }
        }

        // get jankiness count
        int jankinessCount = SurfaceFlingerHelper.getVsyncJankiness();
        // get frame rate
        double frameRate = SurfaceFlingerHelper.getFrameRate();
        // get max accumulated frames
        int maxDeltaVsync = SurfaceFlingerHelper.getMaxDeltaVsync();

        // only record data when they are valid
        if (jankinessCount >=0 && frameRate > 0) {
            jankinessArray[iteration] = jankinessCount;
            frameRateArray[iteration] = frameRate;
            maxDeltaVsyncArray[iteration] = maxDeltaVsync;
            mSuccessTestRuns++;
        }
        String msg = String.format("%s, iteration %d\n" +
                "refresh period: %d\n" +
                "jankiness count: %d\n" +
                "frame rate: %f\n" +
                "max accumulated frames: %d\n",
                testCaseName, iteration, refreshPeriod,
                jankinessCount, frameRate, maxDeltaVsync);
        log(msg);
        if (DEBUG) {
            SurfaceFlingerHelper.printData(testCaseName, iteration);
        }
    }

    /**
     * Process data from all test iterations, and save to disk
     * @param testCaseName
     */
    protected void saveResults(String testCaseName) {
        // write test status into status file
        try {
            mStatusWriter.write(String.format("%s: %d success runs out of %d iterations\n",
                    testCaseName, mSuccessTestRuns, mIteration));
        } catch (IOException e) {
            log("failed to write output for test case " + testCaseName);
        }

        // if successful test runs is less than the threshold, no results will be saved.
        if (mSuccessTestRuns * 100 / mIteration < SUCCESS_THRESHOLD) {
            log(String.format("In %s, # of successful test runs out of %s iterations: %d ",
                    testCaseName, mIteration, mSuccessTestRuns));
            log(String.format("threshold is %d%%", SUCCESS_THRESHOLD));
            return;
        }

        if (DEBUG) {
            print(jankinessArray, "jankiness array");
            print(frameRateArray, "frame rate array");
            print(maxDeltaVsyncArray, "max delta vsync array");
        }
        double avgJankinessCount = getAverage(jankinessArray);
        int maxJankinessCount = getMaxValue(jankinessArray);
        double avgFrameRate = getAverage(frameRateArray);
        double avgMaxDeltaVsync = getAverage(maxDeltaVsyncArray);

        String avgMsg = String.format("%s\n" +
                "average number of jankiness: %f\n" +
                "max number of jankiness: %d\n" +
                "average frame rate: %f\n" +
                "average of max accumulated frames: %f\n",
                testCaseName, avgJankinessCount, maxJankinessCount, avgFrameRate, avgMaxDeltaVsync);
        log(avgMsg);

        try {
            mWriter.write(avgMsg);
        } catch (IOException e) {
            log("failed to write output for test case " + testCaseName);
        }
    }

    // return the max value in an integer array
    private int getMaxValue(int[] intArray) {
        int index = 0;
        int max = intArray[index];
        for (int i  = 1; i < intArray.length; i++) {
            if (max < intArray[i]) {
                max = intArray[i];
            }
        }
        return max;
    }

    private double getAverage(int[] intArray) {
        int mean = 0;
        int numberTests = 0;
        for (int i = 0; i < intArray.length; i++) {
            // in case in some iteration, test fails, no data points is collected
            if (intArray[i] >= 0) {
                mean += intArray[i];
                ++numberTests;
            }
        }
        return (double)mean/numberTests;
    }

    private double getAverage(double[] doubleArray) {
        double mean = 0;
        int numberTests = 0;
        for (int i = 0; i < doubleArray.length; i++) {
            // in case in some iteration, test fails, no data points is collected
            if (doubleArray[i] >= 0) {
                mean += doubleArray[i];
                ++numberTests;
            }
        }
        return mean/numberTests;
    }

    private void print(int[] intArray, String arrayName) {
        log("start to print array for " + arrayName);
        for (int i = 0; i < intArray.length; i++) {
            log(String.format("%d: %d", i, intArray[i]));
        }
    }

    private void print(double[] doubleArray, String arrayName) {
        log("start to print array for " + arrayName);
        for (int i = 0; i < doubleArray.length; i++) {
            log(String.format("%d: %f", i, doubleArray[i]));
        }
    }

    @Override
    protected void tearDown() throws Exception {
        super.tearDown();
        if (mWriter != null) {
            mWriter.close();
        }
        if (mStatusWriter != null) {
            mStatusWriter.close();
        }
    }

   private void log(String message) {
       Log.v(TAG, message);
   }

   /**
    * Set the total number of test iteration
    * @param iteration
    */
   protected void setIteration(int iteration){
       mIteration = iteration;
   }

   /**
    * Get the total number of test iteration
    * @return iteration
    */
   protected int getIteration(){
       return mIteration;
   }
}
