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

import android.os.Environment;
import android.util.Log;

import java.util.Arrays;
import java.util.List;
import java.util.ArrayList;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.IOException;
import java.lang.Math;

/*
 * Tools to measure jankiness through SurfaceFlinger
 */
public class SurfaceFlingerHelper {
    private static String TAG = "SurfaceFlingerHelper";
    private static int BUFFER_SIZE = 128;
    private static int BUFFER_NUMBER = 3;
    private static String CLEAR_BUFFER_CMD = "dumpsys SurfaceFlinger --latency-clear";
    private static String FRAME_LATENCY_CMD = "dumpsys SurfaceFlinger --latency";
    private static final String RAW_DATA_DIR = "UiJankinessRawData";
    private static final String LOCAL_TMP_DIR = "/data/local/tmp/";
    /* If the latency between two frames is greater than this number, it it treated as a pause
     * not a jankiness */
    private static final int PAUSE_LATENCY = 20;

    /* An array list which includes the raw buffer information from frame latency tool */
    private static List<List<String>> mFrameBufferData = new ArrayList<List<String>>(BUFFER_SIZE);

    /* Record the refresh period returned from driver */
    private static long mRefreshPeriod = -1;

    /* Record the size of frame latency */
    private static int mFrameLatencySampleSize = 0;

    /* An integer array which includes delta vsync */
    private static long[] mDeltaVsync = new long[BUFFER_SIZE];

    /* Integer array for delta of delta vsync */
    private static long[] mDelta2Vsync = new long[BUFFER_SIZE];

    /* the maximum delta vsync number */
    private static long mMaxDeltaVsync;

    /* Normalized data */
    private static double[] mNormalizedDelta2Vsync = new double[BUFFER_SIZE];
    private static int[] mRoundNormalizedDelta2Vsync = new int[BUFFER_SIZE];
    // Symbol of unfinished frame time */
    public static final String PENDING_FENCE_TIME = new Long(Long.MAX_VALUE).toString();

    /**
     * Run clear buffer command and clear the saved frame buffer results
     *
     * @param windowName the window name that the buffer will be cleared
     */
    public static void clearBuffer(String windowName) {
        // clear results
        if (mFrameBufferData != null) {
            mFrameBufferData.clear();
        }
        Arrays.fill(mDeltaVsync, -1);
        Arrays.fill(mDelta2Vsync, -1);
        Arrays.fill(mNormalizedDelta2Vsync, -1.0);
        Arrays.fill(mRoundNormalizedDelta2Vsync, -1);
        mRefreshPeriod = -1;
        mFrameLatencySampleSize = 0;
        mMaxDeltaVsync = 0;

        Process p = null;
        BufferedReader resultReader = null;
        String command = CLEAR_BUFFER_CMD;
        if (windowName != null) {
            command = String.format("%s %s", CLEAR_BUFFER_CMD, windowName);
        }
        try {
            p = Runtime.getRuntime().exec(command);
            int status = p.waitFor();
            if (status != 0) {
                Log.e(TAG, String.format("Run shell command: %s, status: %s",
                        command, status));
            }
        } catch (IOException e) {
            Log.e(TAG, "// Exception from command " + command + ":", e);
        } catch (InterruptedException e) {
            Log.e(TAG, "// Interrupted while waiting for the command to finish. ", e);
        } finally {
            try {
                if (resultReader != null) {
                    resultReader.close();
                }
                if (p != null) {
                    p.destroy();
                }
            } catch (IOException e) {
                Log.e(TAG, "exception " + e);
            }
        }
    }

    /**
     * Run frame latency command without ignoring pending fence time
     *
     * @param windowName the window name which SurfaceFlinger will acquire frame time for
     */
    public static boolean dumpFrameLatency(String windowName) {
        return dumpFrameLatency(windowName, false);
    }

    /**
     * Run frame latency command to get frame time
     *
     * @param windowName the window name which SurfaceFlinger will get frame time for
     * @param ignorePendingFenceTime flag to process frames with pending fence time
     *                              set true to ignore pending fence time
     *                              set false to fail the test if pending fence time is not allowed
     */
    public static boolean dumpFrameLatency(String windowName, boolean ignorePendingFenceTime) {
        Process p = null;
        BufferedReader resultReader = null;
        String command = FRAME_LATENCY_CMD;
        if (windowName != null) {
            command = String.format("%s %s", FRAME_LATENCY_CMD, windowName);
        }
        log("dump frame latency command: " + command);
        try {
            p = Runtime.getRuntime().exec(command);
            int status = p.waitFor();
            if (status != 0) {
                Log.e(TAG, String.format("Run shell command: %s, status: %s",command, status));
            }
            resultReader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line = resultReader.readLine();
            mRefreshPeriod = Long.parseLong(line.trim());
            log("reading refresh period: " + mRefreshPeriod);
            if (mRefreshPeriod < 0) {
                return false;
            }
            boolean dataInvalidFlag = false;
            while((line = resultReader.readLine()) != null) {
                // remove lines which are empty
                if (line.trim().isEmpty()) {
                    break;
                }
                String[] bufferValues = line.split("\\s+");
                if (bufferValues[0].trim().compareTo("0") == 0) {
                    continue;
                } else if (bufferValues[1].trim().compareTo(PENDING_FENCE_TIME) == 0 ) {
                    if (ignorePendingFenceTime) {
                        log("ignore pending fence time");
                        dataInvalidFlag = true;
                    } else {
                        log("the data contains unfinished frame time, please allow the animation"
                            + " to finish in the test before calling dumpFrameLatency.");
                        return false;
                    }
                }
                // store raw data which could have both valid and invalid data
                List<String> delayArray = Arrays.asList(bufferValues);
                mFrameBufferData.add(delayArray);
                if (!dataInvalidFlag) {
                    // only count frames which have valid time
                    ++mFrameLatencySampleSize;
                }
            }
            log("frame latency sample size: " + mFrameLatencySampleSize);
        } catch (InterruptedException e) {
            Log.e(TAG, "// Exception from command " + command + ":", e);
        } catch (IOException e) {
            Log.e(TAG, "Open file error: ", e);
            return false;
        }
        finally {
            try {
                if (resultReader != null) {
                    resultReader.close();
                }
                if (p != null) {
                    p.destroy();
                }
            } catch (IOException e) {
                Log.e(TAG, "io exception: ", e);
            }
        }
        return true;
    }

    public static int getDataSampleSize() {
        return mFrameLatencySampleSize;
    }

    public static long getRefreshPeriod() {
        if (mRefreshPeriod < 0) {
            // Haven't dump the frame latency yet
            log("Run command \"" + FRAME_LATENCY_CMD + " \" before retrieving the refresh period");
        }
        return mRefreshPeriod;
    }

    public static String getFrameBufferData() {
        if (mFrameBufferData.get(0) == null) {
            log("Run command \"" + FRAME_LATENCY_CMD + " \" before retrieving frame buffer data");
            return null;
        }
        String rawData = String.format("%d\n", mRefreshPeriod);
        List<String> tempList = new ArrayList<String>(BUFFER_NUMBER);
        for (int i = 0; i < mFrameBufferData.size(); i++) {
            tempList = mFrameBufferData.get(i);
            for (int j = 0; j < BUFFER_NUMBER; j++) {
                rawData += String.format("%s", tempList.get(j));
                if (j < BUFFER_NUMBER - 1) {
                    rawData += "\t";
                } else {
                    rawData += "\n";
                }
            }
        }
        return rawData;
    }

    /**
     * Calculate delta(vsync)
     * @return
     */
    public static long[] getDeltaVsync() {
        if (mRefreshPeriod < 0) {
            log("Run command \"" + FRAME_LATENCY_CMD + " \" before retrieving frame latency");
            return null;
        }
        if (mDeltaVsync[0] < 0 ) {
            // keep a record of the max DeltaVsync
            mMaxDeltaVsync = 0;
            // get the first frame vsync time
            long preVsyncTime = Long.parseLong(mFrameBufferData.get(0).get(1));
            for (int i = 0; i < mFrameLatencySampleSize - 1; i++) {
                long curVsyncTime = Long.parseLong(mFrameBufferData.get(i + 1).get(1));
                mDeltaVsync[i] = curVsyncTime - preVsyncTime;
                preVsyncTime = curVsyncTime;
                if (mMaxDeltaVsync < mDeltaVsync[i]) {
                    mMaxDeltaVsync = mDeltaVsync[i];
                }
            }
        }
        return mDeltaVsync;
    }

    /**
     * Calculate difference between delta vsync
     * @return
     */
    public static long[] getDelta2Vsync() {
        if (mRefreshPeriod < 0) {
            log("Run command \"" + FRAME_LATENCY_CMD + " \" before retrieving frame latency");
            return null;
        }
        if (mDeltaVsync[0] < 0) {
            getDeltaVsync();
        }
        if (mDelta2Vsync[0] < 0) {
            int numDeltaVsync = mFrameLatencySampleSize - 1;
            for (int i = 0; i < numDeltaVsync - 1; i++) {
                mDelta2Vsync[i] = mDeltaVsync[i + 1] - mDeltaVsync[i];
            }
        }
        return mDelta2Vsync;
    }

    /**
     * normalized delta(delta(vsync)) by refresh period
     * @return
     */
    public static double[] getNormalizedDelta2Vsync() {
        if (mRefreshPeriod < 0) {
            log("Run command \"" + FRAME_LATENCY_CMD + " \" before retrieving frame latency");
            return null;
        }
        if (mDelta2Vsync[0] < 0) {
            getDelta2Vsync();
        }
        if (mNormalizedDelta2Vsync[0] < 0) {
            for (int i = 0; i < mFrameLatencySampleSize - 2; i++) {
                mNormalizedDelta2Vsync[i] = (double)mDelta2Vsync[i] /mRefreshPeriod;
            }
        }
        return mNormalizedDelta2Vsync;
    }

    public static int[] getRoundNormalizedDelta2Vsync() {
        if (mRefreshPeriod < 0) {
            log("Run command \"" + FRAME_LATENCY_CMD + " \" for number of jankiness.");
            return null;
        }
        if (mNormalizedDelta2Vsync[0] < 0) {
            getNormalizedDelta2Vsync();
        }

        for (int i = 0; i < mFrameLatencySampleSize - 2; i++) {
             int value = (int)Math.round(Math.max(mNormalizedDelta2Vsync[i], 0.0));
             mRoundNormalizedDelta2Vsync[i] = value;
        }
        return mRoundNormalizedDelta2Vsync;
    }

    /*
     * Get number of jankiness using Vsync time difference
     */
    public static int getVsyncJankiness() {
        if (mRefreshPeriod < 0) {
            log("Run command \"" + FRAME_LATENCY_CMD + " \" for number of jankiness.");
            return -1;
        }
        if (mRoundNormalizedDelta2Vsync[0] < 0) {
            getRoundNormalizedDelta2Vsync();
        }
        int numberJankiness = 0;
        for (int i = 0; i < mFrameLatencySampleSize - 2; i++) {
            int value = mRoundNormalizedDelta2Vsync[i];
            // ignore the latency which is too long
            if (value > 0 && value < PAUSE_LATENCY) {
                numberJankiness++;
            }
        }
        return numberJankiness;
    }

    /* Track the maximum delta which shows the accumulating time
     * before animation starts */
    public static int getMaxDeltaVsync() {
        return Math.round((float)mMaxDeltaVsync /mRefreshPeriod);
    }

    /**
     * Calculate frame rate
     * @return
     */
    public static double getFrameRate() {
        if (mRefreshPeriod < 0) {
            log("Run command \"" + FRAME_LATENCY_CMD + " \" before calcuating average frame rate");
            return -1.0;
        }
        if (mFrameBufferData.get(0) == null) {
            log("Run command \"" + FRAME_LATENCY_CMD + " \" before retrieving frame buffer data");
            return -1.0;
        }
        long startTime = Long.parseLong(mFrameBufferData.get(0).get(1));
        long endTime =  Long.parseLong(mFrameBufferData.get(mFrameLatencySampleSize - 1).get(1));
        long totalDuration = endTime - startTime;
        return (double)((mFrameLatencySampleSize - 1) * Math.pow(10, 9))/totalDuration;
    }

    /**
     * Print raw data and processed results into file <testcasename_[iteration]_processed.txt>
     * @param fileName
     * @param index
     */
    public static void printData(String fileName, int index) {
        String rawAndProcDataFileName = String.format("%s/%s_%d_processed.txt", RAW_DATA_DIR,
                fileName, index);
        log("write raw data and process data into file: " + rawAndProcDataFileName);
        BufferedWriter fw = null;
        try {
            fw = new BufferedWriter(new FileWriter(new File(rawAndProcDataFileName), false));
            // Show the number of jankiness first:
            fw.write(String.format("Jankiness count: %d\n", getVsyncJankiness()));
            fw.write(String.format("Max accumulated frames: %d\n", getMaxDeltaVsync()));
            fw.write(String.format("Frame rate is: %f\n", getFrameRate()));

            // refresh period
            fw.write(String.valueOf(mRefreshPeriod));
            fw.write("\n");
            fw.write("app\tvsync\tset\tdelta(vsync)\tdelta^2(vsync)\t" +
                    "delta^2(vsync)/refreshPeriod\t normalized delta^2(vsync)\n");

            for (int i = 0; i < mFrameLatencySampleSize; i++) {
                // write raw data
                List<String> rawData = mFrameBufferData.get(i);
                String line = String.format("%s\t%s\t%s\t%d\t%d\t%f\t%d\n",
                        rawData.get(0), rawData.get(1), rawData.get(2),
                        mDeltaVsync[i], mDelta2Vsync[i],
                        mNormalizedDelta2Vsync[i], mRoundNormalizedDelta2Vsync[i]);
                fw.write(line);
            }
        } catch (IOException e) {
            log("Open file error: " + e.toString());
        } finally {
            try {
                if (fw != null) {
                    fw.flush();
                    fw.close();
                }
            }
            catch (IOException e) {
                Log.e(TAG, "close file exception: ", e);
            }
        }
    }

    private static void log(String msg) {
        Log.v(TAG, msg);
    }
}
