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

import android.annotation.NonNull;
import android.content.Context;
import android.util.Base64;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.wifi.util.ByteArrayRingBuffer;
import com.android.server.wifi.util.StringUtil;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.zip.Deflater;

/**
 * Tracks various logs for framework.
 */
class WifiDiagnostics extends BaseWifiDiagnostics {
    /**
     * Thread-safety:
     * 1) All non-private methods are |synchronized|.
     * 2) Callbacks into WifiDiagnostics use non-private (and hence, synchronized) methods. See, e.g,
     *    onRingBufferData(), onWifiAlert().
     */

    private static final String TAG = "WifiDiags";
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
    public static final int REPORT_REASON_WIFINATIVE_FAILURE        = 8;

    /** number of bug reports to hold */
    public static final int MAX_BUG_REPORTS                         = 4;

    /** number of alerts to hold */
    public static final int MAX_ALERT_REPORTS                       = 1;

    /** minimum wakeup interval for each of the log levels */
    private static final int MinWakeupIntervals[] = new int[] { 0, 3600, 60, 10 };
    /** minimum buffer size for each of the log levels */
    private static final int MinBufferSizes[] = new int[] { 0, 16384, 16384, 65536 };

    @VisibleForTesting public static final String FIRMWARE_DUMP_SECTION_HEADER =
            "FW Memory dump";
    @VisibleForTesting public static final String DRIVER_DUMP_SECTION_HEADER =
            "Driver state dump";

    private final int RING_BUFFER_BYTE_LIMIT_SMALL;
    private final int RING_BUFFER_BYTE_LIMIT_LARGE;
    private int mLogLevel = VERBOSE_NO_LOG;
    private boolean mIsLoggingEventHandlerRegistered;
    private WifiNative.RingBufferStatus[] mRingBuffers;
    private WifiNative.RingBufferStatus mPerPacketRingBuffer;
    private final BuildProperties mBuildProperties;
    private final WifiLog mLog;
    private final LastMileLogger mLastMileLogger;
    private final Runtime mJavaRuntime;
    private final WifiMetrics mWifiMetrics;
    private int mMaxRingBufferSizeBytes;
    private WifiInjector mWifiInjector;

    public WifiDiagnostics(Context context, WifiInjector wifiInjector,
                           WifiNative wifiNative, BuildProperties buildProperties,
                           LastMileLogger lastMileLogger) {
        super(wifiNative);
        RING_BUFFER_BYTE_LIMIT_SMALL = context.getResources().getInteger(
                R.integer.config_wifi_logger_ring_buffer_default_size_limit_kb) * 1024;
        RING_BUFFER_BYTE_LIMIT_LARGE = context.getResources().getInteger(
                R.integer.config_wifi_logger_ring_buffer_verbose_size_limit_kb) * 1024;

        mBuildProperties = buildProperties;
        mIsLoggingEventHandlerRegistered = false;
        mMaxRingBufferSizeBytes = RING_BUFFER_BYTE_LIMIT_SMALL;
        mLog = wifiInjector.makeLog(TAG);
        mLastMileLogger = lastMileLogger;
        mJavaRuntime = wifiInjector.getJavaRuntime();
        mWifiMetrics = wifiInjector.getWifiMetrics();
        mWifiInjector = wifiInjector;
    }

    @Override
    public synchronized void startLogging(boolean verboseEnabled) {
        mFirmwareVersion = mWifiNative.getFirmwareVersion();
        mDriverVersion = mWifiNative.getDriverVersion();
        mSupportedFeatureSet = mWifiNative.getSupportedLoggerFeatureSet();

        if (!mIsLoggingEventHandlerRegistered) {
            mIsLoggingEventHandlerRegistered = mWifiNative.setLoggingEventHandler(mHandler);
        }

        if (verboseEnabled) {
            mLogLevel = VERBOSE_LOG_WITH_WAKEUP;
            mMaxRingBufferSizeBytes = RING_BUFFER_BYTE_LIMIT_LARGE;
        } else {
            mLogLevel = VERBOSE_NORMAL_LOG;
            mMaxRingBufferSizeBytes = enableVerboseLoggingForDogfood()
                    ? RING_BUFFER_BYTE_LIMIT_LARGE : RING_BUFFER_BYTE_LIMIT_SMALL;
            clearVerboseLogs();
        }

        if (mRingBuffers == null) {
            fetchRingBuffers();
        }

        if (mRingBuffers != null) {
            /* log level may have changed, so restart logging with new levels */
            stopLoggingAllBuffers();
            resizeRingBuffers();
            startLoggingAllExceptPerPacketBuffers();
        }

        if (!mWifiNative.startPktFateMonitoring(mWifiNative.getClientInterfaceName())) {
            mLog.wC("Failed to start packet fate monitoring");
        }
    }

    @Override
    public synchronized void startPacketLog() {
        if (mPerPacketRingBuffer != null) {
            startLoggingRingBuffer(mPerPacketRingBuffer);
        } else {
            if (DBG) mLog.tC("There is no per packet ring buffer");
        }
    }

    @Override
    public synchronized void stopPacketLog() {
        if (mPerPacketRingBuffer != null) {
            stopLoggingRingBuffer(mPerPacketRingBuffer);
        } else {
            if (DBG) mLog.tC("There is no per packet ring buffer");
        }
    }

    @Override
    public synchronized void stopLogging() {
        if (mIsLoggingEventHandlerRegistered) {
            if (!mWifiNative.resetLogHandler()) {
                mLog.wC("Fail to reset log handler");
            } else {
                if (DBG) mLog.tC("Reset log handler");
            }
            // Clear mIsLoggingEventHandlerRegistered even if resetLogHandler() failed, because
            // the log handler is in an indeterminate state.
            mIsLoggingEventHandlerRegistered = false;
        }
        if (mLogLevel != VERBOSE_NO_LOG) {
            stopLoggingAllBuffers();
            mRingBuffers = null;
            mLogLevel = VERBOSE_NO_LOG;
        }
    }

    @Override
    synchronized void reportConnectionEvent(long connectionId, byte event) {
        mLastMileLogger.reportConnectionEvent(connectionId, event);
        if (event == CONNECTION_EVENT_FAILED) {
            mPacketFatesForLastFailure = fetchPacketFates();
        }
    }

    @Override
    public synchronized void captureBugReportData(int reason) {
        BugReport report = captureBugreport(reason, isVerboseLoggingEnabled());
        mLastBugReports.addLast(report);
    }

    @Override
    public synchronized void captureAlertData(int errorCode, byte[] alertData) {
        BugReport report = captureBugreport(errorCode, isVerboseLoggingEnabled());
        report.alertData = alertData;
        mLastAlerts.addLast(report);
    }

    @Override
    public synchronized void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        super.dump(pw);

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

        dumpPacketFates(pw);
        mLastMileLogger.dump(pw);

        pw.println("--------------------------------------------------------------------");
    }

    @Override
    /**
     * Initiates a system-level bugreport, in a non-blocking fashion.
     */
    public void takeBugReport(String bugTitle, String bugDetail) {
        if (mBuildProperties.isUserBuild()) {
            return;
        }

        try {
            mWifiInjector.getActivityManagerService().requestWifiBugReport(
                    bugTitle, bugDetail);
        } catch (Exception e) {  // diagnostics should never crash system_server
            mLog.err("error taking bugreport: %").c(e.getClass().getName()).flush();
        }
    }

    /* private methods and data */
    class BugReport {
        long systemTimeMs;
        long kernelTimeNanos;
        int errorCode;
        HashMap<String, byte[][]> ringBuffers = new HashMap();
        byte[] fwMemoryDump;
        byte[] mDriverStateDump;
        byte[] alertData;
        LimitedCircularArray<String> kernelLogLines;
        ArrayList<String> logcatLines;

        void clearVerboseLogs() {
            fwMemoryDump = null;
            mDriverStateDump = null;
        }

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

            if (kernelLogLines != null) {
                builder.append("kernel log: \n");
                for (int i = 0; i < kernelLogLines.size(); i++) {
                    builder.append(kernelLogLines.get(i)).append("\n");
                }
                builder.append("\n");
            }

            if (logcatLines != null) {
                builder.append("system log: \n");
                for (int i = 0; i < logcatLines.size(); i++) {
                    builder.append(logcatLines.get(i)).append("\n");
                }
                builder.append("\n");
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
                builder.append(FIRMWARE_DUMP_SECTION_HEADER);
                builder.append("\n");
                builder.append(compressToBase64(fwMemoryDump));
                builder.append("\n");
            }

            if (mDriverStateDump != null) {
                builder.append(DRIVER_DUMP_SECTION_HEADER);
                if (StringUtil.isAsciiPrintable(mDriverStateDump)) {
                    builder.append(" (ascii)\n");
                    builder.append(new String(mDriverStateDump, Charset.forName("US-ASCII")));
                    builder.append("\n");
                } else {
                    builder.append(" (base64)\n");
                    builder.append(compressToBase64(mDriverStateDump));
                }
            }

            return builder.toString();
        }
    }

    class LimitedCircularArray<E> {
        private ArrayList<E> mArrayList;
        private int mMax;
        LimitedCircularArray(int max) {
            mArrayList = new ArrayList<E>(max);
            mMax = max;
        }

        public final void addLast(E e) {
            if (mArrayList.size() >= mMax)
                mArrayList.remove(0);
            mArrayList.add(e);
        }

        public final int size() {
            return mArrayList.size();
        }

        public final E get(int i) {
            return mArrayList.get(i);
        }
    }

    private final LimitedCircularArray<BugReport> mLastAlerts =
            new LimitedCircularArray<BugReport>(MAX_ALERT_REPORTS);
    private final LimitedCircularArray<BugReport> mLastBugReports =
            new LimitedCircularArray<BugReport>(MAX_BUG_REPORTS);
    private final HashMap<String, ByteArrayRingBuffer> mRingBufferData = new HashMap();

    private final WifiNative.WifiLoggerEventHandler mHandler =
            new WifiNative.WifiLoggerEventHandler() {
        @Override
        public void onRingBufferData(WifiNative.RingBufferStatus status, byte[] buffer) {
            WifiDiagnostics.this.onRingBufferData(status, buffer);
        }

        @Override
        public void onWifiAlert(int errorCode, byte[] buffer) {
            WifiDiagnostics.this.onWifiAlert(errorCode, buffer);
        }
    };

    synchronized void onRingBufferData(WifiNative.RingBufferStatus status, byte[] buffer) {
        ByteArrayRingBuffer ring = mRingBufferData.get(status.name);
        if (ring != null) {
            ring.appendBuffer(buffer);
        }
    }

    synchronized void onWifiAlert(int errorCode, @NonNull byte[] buffer) {
        captureAlertData(errorCode, buffer);
        mWifiMetrics.incrementAlertReasonCount(errorCode);
    }

    private boolean isVerboseLoggingEnabled() {
        return mLogLevel > VERBOSE_NORMAL_LOG;
    }

    private void clearVerboseLogs() {
        mPacketFatesForLastFailure = null;

        for (int i = 0; i < mLastAlerts.size(); i++) {
            mLastAlerts.get(i).clearVerboseLogs();
        }

        for (int i = 0; i < mLastBugReports.size(); i++) {
            mLastBugReports.get(i).clearVerboseLogs();
        }
    }

    private boolean fetchRingBuffers() {
        if (mRingBuffers != null) return true;

        mRingBuffers = mWifiNative.getRingBufferStatus();
        if (mRingBuffers != null) {
            for (WifiNative.RingBufferStatus buffer : mRingBuffers) {
                if (DBG) mLog.trace("RingBufferStatus is: %").c(buffer.name).flush();
                if (mRingBufferData.containsKey(buffer.name) == false) {
                    mRingBufferData.put(buffer.name,
                            new ByteArrayRingBuffer(mMaxRingBufferSizeBytes));
                }
                if ((buffer.flag & RING_BUFFER_FLAG_HAS_PER_PACKET_ENTRIES) != 0) {
                    mPerPacketRingBuffer = buffer;
                }
            }
        } else {
            mLog.wC("no ring buffers found");
        }

        return mRingBuffers != null;
    }

    private void resizeRingBuffers() {
        for (ByteArrayRingBuffer byteArrayRingBuffer : mRingBufferData.values()) {
            byteArrayRingBuffer.resize(mMaxRingBufferSizeBytes);
        }
    }

    private boolean startLoggingAllExceptPerPacketBuffers() {

        if (mRingBuffers == null) {
            if (DBG) mLog.tC("No ring buffers to log anything!");
            return false;
        }

        for (WifiNative.RingBufferStatus buffer : mRingBuffers){

            if ((buffer.flag & RING_BUFFER_FLAG_HAS_PER_PACKET_ENTRIES) != 0) {
                /* skip per-packet-buffer */
                if (DBG) mLog.trace("skipped per packet logging ring %").c(buffer.name).flush();
                continue;
            }

            startLoggingRingBuffer(buffer);
        }

        return true;
    }

    private boolean startLoggingRingBuffer(WifiNative.RingBufferStatus buffer) {

        int minInterval = MinWakeupIntervals[mLogLevel];
        int minDataSize = MinBufferSizes[mLogLevel];

        if (mWifiNative.startLoggingRingBuffer(
                mLogLevel, 0, minInterval, minDataSize, buffer.name) == false) {
            if (DBG) mLog.warn("Could not start logging ring %").c(buffer.name).flush();
            return false;
        }

        return true;
    }

    private boolean stopLoggingRingBuffer(WifiNative.RingBufferStatus buffer) {
        if (mWifiNative.startLoggingRingBuffer(0, 0, 0, 0, buffer.name) == false) {
            if (DBG) mLog.warn("Could not stop logging ring %").c(buffer.name).flush();
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

    private boolean enableVerboseLoggingForDogfood() {
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
                mWifiNative.getRingBufferData(buffer.name);
                ByteArrayRingBuffer data = mRingBufferData.get(buffer.name);
                byte[][] buffers = new byte[data.getNumBuffers()][];
                for (int i = 0; i < data.getNumBuffers(); i++) {
                    buffers[i] = data.getBuffer(i).clone();
                }
                report.ringBuffers.put(buffer.name, buffers);
            }
        }

        report.logcatLines = getLogcat(127);
        report.kernelLogLines = getKernelLog(127);

        if (captureFWDump) {
            report.fwMemoryDump = mWifiNative.getFwMemoryDump();
            report.mDriverStateDump = mWifiNative.getDriverStateDump();
        }
        return report;
    }

    @VisibleForTesting
    LimitedCircularArray<BugReport> getBugReports() {
        return mLastBugReports;
    }

    @VisibleForTesting
    LimitedCircularArray<BugReport> getAlertReports() {
        return mLastAlerts;
    }

    private String compressToBase64(byte[] input) {
        String result;
        //compress
        Deflater compressor = new Deflater();
        compressor.setLevel(Deflater.BEST_SPEED);
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
            mLog.wC("ByteArrayOutputStream close error");
            result =  android.util.Base64.encodeToString(input, Base64.DEFAULT);
            return result;
        }

        byte[] compressed = bos.toByteArray();
        if (DBG) {
            mLog.dump("length is: %").c(compressed == null ? 0 : compressed.length).flush();
        }

        //encode
        result = android.util.Base64.encodeToString(
                compressed.length < input.length ? compressed : input , Base64.DEFAULT);

        if (DBG) {
            mLog.dump("FwMemoryDump length is: %").c(result.length()).flush();
        }

        return result;
    }

    private ArrayList<String> getLogcat(int maxLines) {
        ArrayList<String> lines = new ArrayList<String>(maxLines);
        try {
            Process process = mJavaRuntime.exec(String.format("logcat -t %d", maxLines));
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
            reader = new BufferedReader(
                    new InputStreamReader(process.getErrorStream()));
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
            process.waitFor();
        } catch (InterruptedException|IOException e) {
            mLog.dump("Exception while capturing logcat: %").c(e.toString()).flush();
        }
        return lines;
    }

    private LimitedCircularArray<String> getKernelLog(int maxLines) {
        if (DBG) mLog.tC("Reading kernel log ...");
        LimitedCircularArray<String> lines = new LimitedCircularArray<String>(maxLines);
        String log = mWifiNative.readKernelLog();
        String logLines[] = log.split("\n");
        for (int i = 0; i < logLines.length; i++) {
            lines.addLast(logLines[i]);
        }
        if (DBG) mLog.dump("Added % lines").c(logLines.length).flush();
        return lines;
    }

    /** Packet fate reporting */
    private ArrayList<WifiNative.FateReport> mPacketFatesForLastFailure;

    private ArrayList<WifiNative.FateReport> fetchPacketFates() {
        ArrayList<WifiNative.FateReport> mergedFates = new ArrayList<WifiNative.FateReport>();
        WifiNative.TxFateReport[] txFates =
                new WifiNative.TxFateReport[WifiLoggerHal.MAX_FATE_LOG_LEN];
        if (mWifiNative.getTxPktFates(mWifiNative.getClientInterfaceName(), txFates)) {
            for (int i = 0; i < txFates.length && txFates[i] != null; i++) {
                mergedFates.add(txFates[i]);
            }
        }

        WifiNative.RxFateReport[] rxFates =
                new WifiNative.RxFateReport[WifiLoggerHal.MAX_FATE_LOG_LEN];
        if (mWifiNative.getRxPktFates(mWifiNative.getClientInterfaceName(), rxFates)) {
            for (int i = 0; i < rxFates.length && rxFates[i] != null; i++) {
                mergedFates.add(rxFates[i]);
            }
        }

        Collections.sort(mergedFates, new Comparator<WifiNative.FateReport>() {
            @Override
            public int compare(WifiNative.FateReport lhs, WifiNative.FateReport rhs) {
                return Long.compare(lhs.mDriverTimestampUSec, rhs.mDriverTimestampUSec);
            }
        });

        return mergedFates;
    }

    private void dumpPacketFates(PrintWriter pw) {
        dumpPacketFatesInternal(pw, "Last failed connection fates", mPacketFatesForLastFailure,
                isVerboseLoggingEnabled());
        dumpPacketFatesInternal(pw, "Latest fates", fetchPacketFates(), isVerboseLoggingEnabled());
    }

    private static void dumpPacketFatesInternal(PrintWriter pw, String description,
            ArrayList<WifiNative.FateReport> fates, boolean verbose) {
        if (fates == null) {
            pw.format("No fates fetched for \"%s\"\n", description);
            return;
        }

        if (fates.size() == 0) {
            pw.format("HAL provided zero fates for \"%s\"\n", description);
            return;
        }

        pw.format("--------------------- %s ----------------------\n", description);

        StringBuilder verboseOutput = new StringBuilder();
        pw.print(WifiNative.FateReport.getTableHeader());
        for (WifiNative.FateReport fate : fates) {
            pw.print(fate.toTableRowString());
            if (verbose) {
                // Important: only print Personally Identifiable Information (PII) if verbose
                // logging is turned on.
                verboseOutput.append(fate.toVerboseStringWithPiiAllowed());
                verboseOutput.append("\n");
            }
        }

        if (verbose) {
            pw.format("\n>>> VERBOSE PACKET FATE DUMP <<<\n\n");
            pw.print(verboseOutput.toString());
        }

        pw.println("--------------------------------------------------------------------");
    }
}
