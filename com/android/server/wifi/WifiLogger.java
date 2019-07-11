/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.server.wifi;

import com.android.internal.app.IBatteryStats;
import com.android.internal.util.Protocol;

import android.support.v4.util.CircularArray;
import android.util.Base64;
import android.util.LocalLog;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Calendar;
import java.util.HashMap;
import java.util.zip.Deflater;

/**
 * Tracks various logs for framework
 */
class WifiLogger  {

    private static final String TAG = "WifiLogger";
    private static final boolean DBG = false;

    /** log level flags; keep these consistent with wifi_logger.h */

    /** No logs whatsoever */
    public static final int VERBOSE_NO_LOG = 0;
    /** No logs whatsoever */
    public static final int VERBOSE_NORMAL_LOG = 1;
    /** Be careful since this one can affect performance and power */
    public static final int VERBOSE_LOG_WITH_WAKEUP  = 2;
    /** Be careful since this one can affect performance and power and memory */
    public static final int VERBOSE_DETAILED_LOG_WITH_WAKEUP  = 3;

    /** ring buffer flags; keep these consistent with wifi_logger.h */
    public static final int RING_BUFFER_FLAG_HAS_BINARY_ENTRIES     = 0x00000001;
    public static final int RING_BUFFER_FLAG_HAS_ASCII_ENTRIES      = 0x00000002;
    public static final int RING_BUFFER_FLAG_HAS_PER_PACKET_ENTRIES = 0x00000004;

    /** various reason codes */
    public static final int REPORT_REASON_NONE                      = 0;
    public static final int REPORT_REASON_ASSOC_FAILURE             = 1;
    public static final int REPORT_REASON_AUTH_FAILURE              = 2;
    public static final int REPORT_REASON_AUTOROAM_FAILURE          = 3;
    public static final int REPORT_REASON_DHCP_FAILURE              = 4;
    public static final int REPORT_REASON_UNEXPECTED_DISCONNECT     = 5;
    public static final int REPORT_REASON_SCAN_FAILURE              = 6;
    public static final int REPORT_REASON_USER_ACTION               = 7;

    /** number of ring buffer entries to cache */
    public static final int MAX_RING_BUFFERS                        = 10;

    /** number of bug reports to hold */
    public static final int MAX_BUG_REPORTS                         = 4;

    /** number of alerts to hold */
    public static final int MAX_ALERT_REPORTS                       = 1;

    /** minimum wakeup interval for each of the log levels */
    private static final int MinWakeupIntervals[] = new int[] { 0, 3600, 60, 10 };
    /** minimum buffer size for each of the log levels */
    private static final int MinBufferSizes[] = new int[] { 0, 16384, 16384, 65536 };

    private int mLogLevel = VERBOSE_NO_LOG;
    private String mFirmwareVersion;
    private String mDriverVersion;
    private int mSupportedFeatureSet;
    private WifiNative.RingBufferStatus[] mRingBuffers;
    private WifiNative.RingBufferStatus mPerPacketRingBuffer;
    private WifiStateMachine mWifiStateMachine;

    public WifiLogger(WifiStateMachine wifiStateMachine) {
        mWifiStateMachine = wifiStateMachine;
    }

    public synchronized void startLogging(boolean verboseEnabled) {
        mFirmwareVersion = WifiNative.getFirmwareVersion();
        mDriverVersion = WifiNative.getDriverVersion();
        mSupportedFeatureSet = WifiNative.getSupportedLoggerFeatureSet();

        if (mLogLevel == VERBOSE_NO_LOG)
            WifiNative.setLoggingEventHandler(mHandler);

        if (verboseEnabled) {
            mLogLevel = VERBOSE_LOG_WITH_WAKEUP;
        } else {
            mLogLevel = VERBOSE_NORMAL_LOG;
        }
        if (mRingBuffers == null) {
            if (fetchRingBuffers()) {
                startLoggingAllExceptPerPacketBuffers();
            }
        }
    }

    public synchronized void startPacketLog() {
        if (mPerPacketRingBuffer != null) {
            startLoggingRingBuffer(mPerPacketRingBuffer);
        } else {
            if (DBG) Log.d(TAG, "There is no per packet ring buffer");
        }
    }

    public synchronized void stopPacketLog() {
        if (mPerPacketRingBuffer != null) {
            stopLoggingRingBuffer(mPerPacketRingBuffer);
        } else {
            if (DBG) Log.d(TAG, "There is no per packet ring buffer");
        }
    }

    public synchronized void stopLogging() {
        if (mLogLevel != VERBOSE_NO_LOG) {
            //resetLogHandler only can be used when you terminate all logging since all handler will
            //be removed. This also stop alert logging
            if(!WifiNative.resetLogHandler()) {
                Log.e(TAG, "Fail to reset log handler");
            } else {
                if (DBG) Log.d(TAG,"Reset log handler");
            }
            stopLoggingAllBuffers();
            mRingBuffers = null;
            mLogLevel = VERBOSE_NO_LOG;
        }
    }

    public synchronized void captureBugReportData(int reason) {
        BugReport report = captureBugreport(reason, true);
        mLastBugReports.addLast(report);
    }

    public synchronized void captureAlertData(int errorCode, byte[] alertData) {
        BugReport report = captureBugreport(errorCode, /* captureFWDump = */ true);
        report.alertData = alertData;
        mLastAlerts.addLast(report);
    }

    public synchronized void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("Chipset information :-----------------------------------------------");
        pw.println("FW Version is: " + mFirmwareVersion);
        pw.println("Driver Version is: " + mDriverVersion);
        pw.println("Supported Feature set: " + mSupportedFeatureSet);

        for (int i = 0; i < mLastAlerts.size(); i++) {
            pw.println("--------------------------------------------------------------------");
            pw.println("Alert dump " + i);
            pw.print(mLastAlerts.get(i));
            pw.println("--------------------------------------------------------------------");
        }

        for (int i = 0; i < mLastBugReports.size(); i++) {
            pw.println("--------------------------------------------------------------------");
            pw.println("Bug dump " + i);
            pw.print(mLastBugReports.get(i));
            pw.println("--------------------------------------------------------------------");
        }

        pw.println("--------------------------------------------------------------------");
    }

    /* private methods and data */
    private static class BugReport {
        long systemTimeMs;
        long kernelTimeNanos;
        int errorCode;
        HashMap<String, byte[][]> ringBuffers = new HashMap();
        byte[] fwMemoryDump;
        byte[] alertData;

        public String toString() {
            StringBuilder builder = new StringBuilder();

            Calendar c = Calendar.getInstance();
            c.setTimeInMillis(systemTimeMs);
            builder.append("system time = ").append(
                    String.format("%tm-%td %tH:%tM:%tS.%tL", c, c, c, c, c, c)).append("\n");

            long kernelTimeMs = kernelTimeNanos/(1000*1000);
            builder.append("kernel time = ").append(kernelTimeMs/1000).append(".").append
                    (kernelTimeMs%1000).append("\n");

            if (alertData == null)
                builder.append("reason = ").append(errorCode).append("\n");
            else {
                builder.append("errorCode = ").append(errorCode);
                builder.append("data \n");
                builder.append(compressToBase64(alertData)).append("\n");
            }

            for (HashMap.Entry<String, byte[][]> e : ringBuffers.entrySet()) {
                String ringName = e.getKey();
                byte[][] buffers = e.getValue();
                builder.append("ring-buffer = ").append(ringName).append("\n");

                int size = 0;
                for (int i = 0; i < buffers.length; i++) {
                    size += buffers[i].length;
                }

                byte[] buffer = new byte[size];
                int index = 0;
                for (int i = 0; i < buffers.length; i++) {
                    System.arraycopy(buffers[i], 0, buffer, index, buffers[i].length);
                    index += buffers[i].length;
                }

                builder.append(compressToBase64(buffer));
                builder.append("\n");
            }

            if (fwMemoryDump != null) {
                builder.append("FW Memory dump \n");
                builder.append(compressToBase64(fwMemoryDump));
            }

            return builder.toString();
        }
    }

    static class LimitedCircularArray<E> {
        private CircularArray<E> mArray;
        private int mMax;
        LimitedCircularArray(int max) {
            mArray = new CircularArray<E>();
            mMax = max;
        }

        public final void addLast(E e) {
            if (mArray.size() >= mMax)
                mArray.popFirst();
            mArray.addLast(e);
        }

        public final int size() {
            return mArray.size();
        }

        public final E get(int i) {
            return mArray.get(i);
        }
    }

    private final LimitedCircularArray<BugReport> mLastAlerts =
            new LimitedCircularArray<BugReport>(MAX_ALERT_REPORTS);
    private final LimitedCircularArray<BugReport> mLastBugReports =
            new LimitedCircularArray<BugReport>(MAX_BUG_REPORTS);
    private final HashMap<String, LimitedCircularArray<byte[]>> mRingBufferData = new HashMap();

    private final WifiNative.WifiLoggerEventHandler mHandler =
            new WifiNative.WifiLoggerEventHandler() {
        @Override
        public void onRingBufferData(WifiNative.RingBufferStatus status, byte[] buffer) {
            WifiLogger.this.onRingBufferData(status, buffer);
        }

        @Override
        public void onWifiAlert(int errorCode, byte[] buffer) {
            WifiLogger.this.onWifiAlert(errorCode, buffer);
        }
    };

    synchronized void onRingBufferData(WifiNative.RingBufferStatus status, byte[] buffer) {
        LimitedCircularArray<byte[]> ring = mRingBufferData.get(status.name);
        if (ring != null) {
            ring.addLast(buffer);
        }
    }

    synchronized void onWifiAlert(int errorCode, byte[] buffer) {
        if (mWifiStateMachine != null) {
            mWifiStateMachine.sendMessage(
                    WifiStateMachine.CMD_FIRMWARE_ALERT, errorCode, 0, buffer);
        }
    }

    private boolean fetchRingBuffers() {
        if (mRingBuffers != null) return true;

        mRingBuffers = WifiNative.getRingBufferStatus();
        if (mRingBuffers != null) {
            for (WifiNative.RingBufferStatus buffer : mRingBuffers) {
                if (DBG) Log.d(TAG, "RingBufferStatus is: \n" + buffer.name);
                if (mRingBufferData.containsKey(buffer.name) == false) {
                    mRingBufferData.put(buffer.name,
                            new LimitedCircularArray<byte[]>(MAX_RING_BUFFERS));
                }
                if ((buffer.flag & RING_BUFFER_FLAG_HAS_PER_PACKET_ENTRIES) != 0) {
                    mPerPacketRingBuffer = buffer;
                }
            }
        } else {
            Log.e(TAG, "no ring buffers found");
        }

        return mRingBuffers != null;
    }

    private boolean startLoggingAllExceptPerPacketBuffers() {

        if (mRingBuffers == null) {
            if (DBG) Log.d(TAG, "No ring buffers to log anything!");
            return false;
        }

        for (WifiNative.RingBufferStatus buffer : mRingBuffers){

            if ((buffer.flag & RING_BUFFER_FLAG_HAS_PER_PACKET_ENTRIES) != 0) {
                /* skip per-packet-buffer */
                if (DBG) Log.d(TAG, "skipped per packet logging ring " + buffer.name);
                continue;
            }

            startLoggingRingBuffer(buffer);
        }

        return true;
    }

    private boolean startLoggingRingBuffer(WifiNative.RingBufferStatus buffer) {

        int minInterval = MinWakeupIntervals[mLogLevel];
        int minDataSize = MinBufferSizes[mLogLevel];

        if (WifiNative.startLoggingRingBuffer(
                mLogLevel, 0, minInterval, minDataSize, buffer.name) == false) {
            if (DBG) Log.e(TAG, "Could not start logging ring " + buffer.name);
            return false;
        }

        return true;
    }

    private boolean stopLoggingRingBuffer(WifiNative.RingBufferStatus buffer) {
        if (WifiNative.startLoggingRingBuffer(0, 0, 0, 0, buffer.name) == false) {
            if (DBG) Log.e(TAG, "Could not stop logging ring " + buffer.name);
        }
        return true;
    }

    private boolean stopLoggingAllBuffers() {
        if (mRingBuffers != null) {
            for (WifiNative.RingBufferStatus buffer : mRingBuffers) {
                stopLoggingRingBuffer(buffer);
            }
        }
        return true;
    }

    private boolean getAllRingBufferData() {
        if (mRingBuffers == null) {
            Log.e(TAG, "Not ring buffers available to collect data!");
            return false;
        }

        for (WifiNative.RingBufferStatus element : mRingBuffers){
            boolean result = WifiNative.getRingBufferData(element.name);
            if (!result) {
                Log.e(TAG, "Fail to get ring buffer data of: " + element.name);
                return false;
            }
        }

        Log.d(TAG, "getAllRingBufferData Successfully!");
        return true;
    }

    private BugReport captureBugreport(int errorCode, boolean captureFWDump) {
        BugReport report = new BugReport();
        report.errorCode = errorCode;
        report.systemTimeMs = System.currentTimeMillis();
        report.kernelTimeNanos = System.nanoTime();

        if (mRingBuffers != null) {
            for (WifiNative.RingBufferStatus buffer : mRingBuffers) {
                /* this will push data in mRingBuffers */
                WifiNative.getRingBufferData(buffer.name);
                LimitedCircularArray<byte[]> data = mRingBufferData.get(buffer.name);
                byte[][] buffers = new byte[data.size()][];
                for (int i = 0; i < data.size(); i++) {
                    buffers[i] = data.get(i).clone();
                }
                report.ringBuffers.put(buffer.name, buffers);
            }
        }

        if (captureFWDump) {
            report.fwMemoryDump = WifiNative.getFwMemoryDump();
        }
        return report;
    }

    private static String compressToBase64(byte[] input) {
        String result;
        //compress
        Deflater compressor = new Deflater();
        compressor.setLevel(Deflater.BEST_COMPRESSION);
        compressor.setInput(input);
        compressor.finish();
        ByteArrayOutputStream bos = new ByteArrayOutputStream(input.length);
        final byte[] buf = new byte[1024];

        while (!compressor.finished()) {
            int count = compressor.deflate(buf);
            bos.write(buf, 0, count);
        }

        try {
            compressor.end();
            bos.close();
        } catch (IOException e) {
            Log.e(TAG, "ByteArrayOutputStream close error");
            result =  android.util.Base64.encodeToString(input, Base64.DEFAULT);
            return result;
        }

        byte[] compressed = bos.toByteArray();
        if (DBG) {
            Log.d(TAG," length is:" + (compressed == null? "0" : compressed.length));
        }

        //encode
        result = android.util.Base64.encodeToString(
                compressed.length < input.length ? compressed : input , Base64.DEFAULT);

        if (DBG) {
            Log.d(TAG, "FwMemoryDump length is :" + result.length());
        }

        return result;
    }
}
