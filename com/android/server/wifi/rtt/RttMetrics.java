/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server.wifi.rtt;

import static com.android.server.wifi.util.MetricsUtils.addValueToLinearHistogram;
import static com.android.server.wifi.util.MetricsUtils.addValueToLogHistogram;
import static com.android.server.wifi.util.MetricsUtils.linearHistogramToGenericBuckets;
import static com.android.server.wifi.util.MetricsUtils.logHistogramToGenericBuckets;

import android.net.MacAddress;
import android.net.wifi.rtt.RangingRequest;
import android.net.wifi.rtt.RangingResult;
import android.net.wifi.rtt.ResponderConfig;
import android.os.WorkSource;
import android.util.Log;
import android.util.SparseArray;
import android.util.SparseIntArray;

import com.android.server.wifi.Clock;
import com.android.server.wifi.proto.nano.WifiMetricsProto;
import com.android.server.wifi.util.MetricsUtils;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Wi-Fi RTT metric container/processor.
 */
public class RttMetrics {
    private static final String TAG = "RttMetrics";
    private static final boolean VDBG = false;
    /* package */ boolean mDbg = false;

    private final Object mLock = new Object();
    private final Clock mClock;

    // accumulated metrics data

    // Histogram: 7 buckets (i=0, ..., 6) of 1 slots in range 10^i -> 10^(i+1)
    // Buckets:
    //    1 -> 10
    //    10 -> 100
    //    100 -> 1000
    //    10^3 -> 10^4
    //    10^4 -> 10^5
    //    10^5 -> 10^6
    //    10^5 -> 10^7 (10^7 ms = 160 minutes)
    private static final MetricsUtils.LogHistParms COUNT_LOG_HISTOGRAM =
            new MetricsUtils.LogHistParms(0, 1, 10, 1, 7);

    // Histogram for ranging limits in discovery. Indicates the following 7 buckets (in meters):
    //   < 0
    //   [0, 5)
    //   [5, 15)
    //   [15, 30)
    //   [30, 60)
    //   [60, 100)
    //   >= 100
    private static final int[] DISTANCE_MM_HISTOGRAM =
            {0, 5 * 1000, 15 * 1000, 30 * 1000, 60 * 1000, 100 * 1000};
    // Histogram for duration for ap only measurement. Indicates 5 buckets with 1000 ms interval.
    private static final int[] MEASUREMENT_DURATION_HISTOGRAM_AP =
            {1 * 1000, 2 * 1000, 3 * 1000, 4 * 1000};

    // Histogram for duration for measurement with aware. Indicates 5 buckets with 2000 ms interval.
    private static final int[] MEASUREMENT_DURATION_HISTOGRAM_AWARE =
            {2 * 1000, 4 * 1000, 6 * 1000, 8 * 1000};

    private static final int PEER_AP = 0;
    private static final int PEER_AWARE = 1;

    private int mNumStartRangingCalls = 0;
    private SparseIntArray mOverallStatusHistogram = new SparseIntArray();
    private SparseIntArray mMeasurementDurationApOnlyHistogram = new SparseIntArray();
    private SparseIntArray mMeasurementDurationWithAwareHistogram = new SparseIntArray();
    private PerPeerTypeInfo[] mPerPeerTypeInfo;

    public RttMetrics(Clock clock) {
        mClock = clock;

        mPerPeerTypeInfo = new PerPeerTypeInfo[2];
        mPerPeerTypeInfo[PEER_AP] = new PerPeerTypeInfo();
        mPerPeerTypeInfo[PEER_AWARE] = new PerPeerTypeInfo();
    }

    private class PerUidInfo {
        public int numRequests;
        public long lastRequestMs;

        @Override
        public String toString() {
            return "numRequests=" + numRequests + ", lastRequestMs=" + lastRequestMs;
        }
    }

    private class PerPeerTypeInfo {
        public int numCalls;
        public int numIndividualCalls;
        public SparseArray<PerUidInfo> perUidInfo = new SparseArray<>();
        public SparseIntArray numRequestsHistogram = new SparseIntArray();
        public SparseIntArray requestGapHistogram = new SparseIntArray();
        public SparseIntArray statusHistogram = new SparseIntArray();
        public SparseIntArray measuredDistanceHistogram = new SparseIntArray();

        @Override
        public String toString() {
            return "numCalls=" + numCalls + ", numIndividualCalls=" + numIndividualCalls
                    + ", perUidInfo=" + perUidInfo + ", numRequestsHistogram="
                    + numRequestsHistogram + ", requestGapHistogram=" + requestGapHistogram
                    + ", statusHistogram=" + statusHistogram
                    + ", measuredDistanceHistogram=" + measuredDistanceHistogram;
        }
    }

    // metric recording API

    /**
     * Record metrics for the range request.
     */
    public void recordRequest(WorkSource ws, RangingRequest requests) {
        mNumStartRangingCalls++;

        int numApRequests = 0;
        int numAwareRequests = 0;
        for (ResponderConfig request : requests.mRttPeers) {
            if (request == null) {
                continue;
            }
            if (request.responderType == ResponderConfig.RESPONDER_AWARE) {
                numAwareRequests++;
            } else if (request.responderType == ResponderConfig.RESPONDER_AP) {
                numApRequests++;
            } else {
                if (mDbg) Log.d(TAG, "Unexpected Responder type: " + request.responderType);
            }
        }

        updatePeerInfoWithRequestInfo(mPerPeerTypeInfo[PEER_AP], ws, numApRequests);
        updatePeerInfoWithRequestInfo(mPerPeerTypeInfo[PEER_AWARE], ws, numAwareRequests);
    }

    /**
     * Record metrics for the range results.
     */
    public void recordResult(RangingRequest requests, List<RangingResult> results,
            int measurementDuration) {
        Map<MacAddress, ResponderConfig> requestEntries = new HashMap<>();
        for (ResponderConfig responder : requests.mRttPeers) {
            requestEntries.put(responder.macAddress, responder);
        }
        if (results != null) {
            boolean containsAwarePeer = false;
            for (RangingResult result : results) {
                if (result == null) {
                    continue;
                }
                ResponderConfig responder = requestEntries.remove(result.getMacAddress());
                if (responder == null) {
                    Log.e(TAG,
                            "recordResult: found a result which doesn't match any requests: "
                                    + result);
                    continue;
                }

                if (responder.responderType == ResponderConfig.RESPONDER_AP) {
                    updatePeerInfoWithResultInfo(mPerPeerTypeInfo[PEER_AP], result);
                } else if (responder.responderType == ResponderConfig.RESPONDER_AWARE) {
                    containsAwarePeer = true;
                    updatePeerInfoWithResultInfo(mPerPeerTypeInfo[PEER_AWARE], result);
                } else {
                    Log.e(TAG, "recordResult: unexpected peer type in responder: " + responder);
                }
            }
            if (containsAwarePeer) {
                addValueToLinearHistogram(measurementDuration,
                        mMeasurementDurationWithAwareHistogram,
                        MEASUREMENT_DURATION_HISTOGRAM_AWARE);
            } else {
                addValueToLinearHistogram(measurementDuration,
                        mMeasurementDurationApOnlyHistogram,
                        MEASUREMENT_DURATION_HISTOGRAM_AP);
            }
        }

        for (ResponderConfig responder : requestEntries.values()) {
            PerPeerTypeInfo peerInfo;
            if (responder.responderType == ResponderConfig.RESPONDER_AP) {
                peerInfo = mPerPeerTypeInfo[PEER_AP];
            } else if (responder.responderType == ResponderConfig.RESPONDER_AWARE) {
                peerInfo = mPerPeerTypeInfo[PEER_AWARE];
            } else {
                Log.e(TAG, "recordResult: unexpected peer type in responder: " + responder);
                continue;
            }
            peerInfo.statusHistogram.put(WifiMetricsProto.WifiRttLog.MISSING_RESULT,
                    peerInfo.statusHistogram.get(WifiMetricsProto.WifiRttLog.MISSING_RESULT) + 1);
        }
    }

    /**
     * Record metrics for the overall ranging request status.
     */
    public void recordOverallStatus(int status) {
        mOverallStatusHistogram.put(status, mOverallStatusHistogram.get(status) + 1);
    }

    private void updatePeerInfoWithRequestInfo(PerPeerTypeInfo peerInfo, WorkSource ws,
            int numIndividualCalls) {
        if (numIndividualCalls == 0) {
            return;
        }

        long nowMs = mClock.getElapsedSinceBootMillis();
        peerInfo.numCalls++;
        peerInfo.numIndividualCalls += numIndividualCalls;
        peerInfo.numRequestsHistogram.put(numIndividualCalls,
                peerInfo.numRequestsHistogram.get(numIndividualCalls) + 1);
        boolean recordedIntervals = false;

        for (int i = 0; i < ws.size(); ++i) {
            int uid = ws.getUid(i);

            PerUidInfo perUidInfo = peerInfo.perUidInfo.get(uid);
            if (perUidInfo == null) {
                perUidInfo = new PerUidInfo();
            }

            perUidInfo.numRequests++;

            if (!recordedIntervals && perUidInfo.lastRequestMs != 0) {
                recordedIntervals = true; // don't want to record twice
                addValueToLogHistogram(nowMs - perUidInfo.lastRequestMs,
                        peerInfo.requestGapHistogram, COUNT_LOG_HISTOGRAM);
            }
            perUidInfo.lastRequestMs = nowMs;

            peerInfo.perUidInfo.put(uid, perUidInfo);
        }
    }

    private void updatePeerInfoWithResultInfo(PerPeerTypeInfo peerInfo, RangingResult result) {
        int protoStatus = convertRttStatusTypeToProtoEnum(result.getStatus());
        peerInfo.statusHistogram.put(protoStatus, peerInfo.statusHistogram.get(protoStatus) + 1);
        if (result.getStatus() != RangingResult.STATUS_SUCCESS) {
            return;
        }
        addValueToLinearHistogram(result.getDistanceMm(), peerInfo.measuredDistanceHistogram,
                DISTANCE_MM_HISTOGRAM);
    }

    /**
     * Consolidate all metrics into the proto.
     */
    public WifiMetricsProto.WifiRttLog consolidateProto() {
        WifiMetricsProto.WifiRttLog log = new WifiMetricsProto.WifiRttLog();
        log.rttToAp = new WifiMetricsProto.WifiRttLog.RttToPeerLog();
        log.rttToAware = new WifiMetricsProto.WifiRttLog.RttToPeerLog();
        synchronized (mLock) {
            log.numRequests = mNumStartRangingCalls;
            log.histogramOverallStatus = consolidateOverallStatus(mOverallStatusHistogram);
            log.histogramMeasurementDurationApOnly = genericBucketsToRttBuckets(
                    linearHistogramToGenericBuckets(mMeasurementDurationApOnlyHistogram,
                            MEASUREMENT_DURATION_HISTOGRAM_AP));
            log.histogramMeasurementDurationWithAware = genericBucketsToRttBuckets(
                    linearHistogramToGenericBuckets(mMeasurementDurationWithAwareHistogram,
                            MEASUREMENT_DURATION_HISTOGRAM_AWARE));

            consolidatePeerType(log.rttToAp, mPerPeerTypeInfo[PEER_AP]);
            consolidatePeerType(log.rttToAware, mPerPeerTypeInfo[PEER_AWARE]);
        }
        return log;
    }

    private WifiMetricsProto.WifiRttLog.RttOverallStatusHistogramBucket[] consolidateOverallStatus(
            SparseIntArray histogram) {
        WifiMetricsProto.WifiRttLog.RttOverallStatusHistogramBucket[] h =
                new WifiMetricsProto.WifiRttLog.RttOverallStatusHistogramBucket[histogram.size()];
        for (int i = 0; i < histogram.size(); i++) {
            h[i] = new WifiMetricsProto.WifiRttLog.RttOverallStatusHistogramBucket();
            h[i].statusType = histogram.keyAt(i);
            h[i].count = histogram.valueAt(i);
        }
        return h;
    }

    private void consolidatePeerType(WifiMetricsProto.WifiRttLog.RttToPeerLog peerLog,
            PerPeerTypeInfo peerInfo) {
        peerLog.numRequests = peerInfo.numCalls;
        peerLog.numIndividualRequests = peerInfo.numIndividualCalls;
        peerLog.numApps = peerInfo.perUidInfo.size();
        peerLog.histogramNumPeersPerRequest = consolidateNumPeersPerRequest(
                peerInfo.numRequestsHistogram);
        peerLog.histogramNumRequestsPerApp = consolidateNumRequestsPerApp(peerInfo.perUidInfo);
        peerLog.histogramRequestIntervalMs = genericBucketsToRttBuckets(
                logHistogramToGenericBuckets(peerInfo.requestGapHistogram, COUNT_LOG_HISTOGRAM));
        peerLog.histogramIndividualStatus = consolidateIndividualStatus(peerInfo.statusHistogram);
        peerLog.histogramDistance = genericBucketsToRttBuckets(
                linearHistogramToGenericBuckets(peerInfo.measuredDistanceHistogram,
                        DISTANCE_MM_HISTOGRAM));
    }

    private WifiMetricsProto.WifiRttLog.RttIndividualStatusHistogramBucket[]
            consolidateIndividualStatus(SparseIntArray histogram) {
        WifiMetricsProto.WifiRttLog.RttIndividualStatusHistogramBucket[] h =
                new WifiMetricsProto.WifiRttLog.RttIndividualStatusHistogramBucket[histogram.size(
                )];
        for (int i = 0; i < histogram.size(); i++) {
            h[i] = new WifiMetricsProto.WifiRttLog.RttIndividualStatusHistogramBucket();
            h[i].statusType = histogram.keyAt(i);
            h[i].count = histogram.valueAt(i);
        }
        return h;
    }

    private WifiMetricsProto.WifiRttLog.HistogramBucket[] consolidateNumPeersPerRequest(
            SparseIntArray data) {
        WifiMetricsProto.WifiRttLog.HistogramBucket[] protoArray =
                new WifiMetricsProto.WifiRttLog.HistogramBucket[data.size()];

        for (int i = 0; i < data.size(); i++) {
            protoArray[i] = new WifiMetricsProto.WifiRttLog.HistogramBucket();
            protoArray[i].start = data.keyAt(i);
            protoArray[i].end = data.keyAt(i);
            protoArray[i].count = data.valueAt(i);
        }

        return protoArray;
    }

    private WifiMetricsProto.WifiRttLog.HistogramBucket[] consolidateNumRequestsPerApp(
            SparseArray<PerUidInfo> perUidInfos) {
        SparseIntArray histogramNumRequestsPerUid = new SparseIntArray();
        for (int i = 0; i < perUidInfos.size(); i++) {
            addValueToLogHistogram(perUidInfos.valueAt(i).numRequests, histogramNumRequestsPerUid,
                    COUNT_LOG_HISTOGRAM);
        }

        return genericBucketsToRttBuckets(logHistogramToGenericBuckets(
                histogramNumRequestsPerUid, COUNT_LOG_HISTOGRAM));
    }

    private WifiMetricsProto.WifiRttLog.HistogramBucket[] genericBucketsToRttBuckets(
            MetricsUtils.GenericBucket[] genericHistogram) {
        WifiMetricsProto.WifiRttLog.HistogramBucket[] histogram =
                new WifiMetricsProto.WifiRttLog.HistogramBucket[genericHistogram.length];
        for (int i = 0; i < genericHistogram.length; i++) {
            histogram[i] = new WifiMetricsProto.WifiRttLog.HistogramBucket();
            histogram[i].start = genericHistogram[i].start;
            histogram[i].end = genericHistogram[i].end;
            histogram[i].count = genericHistogram[i].count;
        }
        return histogram;
    }

    /**
     * Dump all RttMetrics to console (pw) - this method is never called to dump the serialized
     * metrics (handled by parent WifiMetrics).
     *
     * @param fd   unused
     * @param pw   PrintWriter for writing dump to
     * @param args unused
     */
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        synchronized (mLock) {
            pw.println("RTT Metrics:");
            pw.println("mNumStartRangingCalls:" + mNumStartRangingCalls);
            pw.println("mOverallStatusHistogram:" + mOverallStatusHistogram);
            pw.println("mMeasurementDurationApOnlyHistogram" + mMeasurementDurationApOnlyHistogram);
            pw.println("mMeasurementDurationWithAwareHistogram"
                    + mMeasurementDurationWithAwareHistogram);
            pw.println("AP:" + mPerPeerTypeInfo[PEER_AP]);
            pw.println("AWARE:" + mPerPeerTypeInfo[PEER_AWARE]);
        }
    }

    /**
     * clear Wi-Fi RTT metrics
     */
    public void clear() {
        synchronized (mLock) {
            mNumStartRangingCalls = 0;
            mOverallStatusHistogram.clear();
            mPerPeerTypeInfo[PEER_AP] = new PerPeerTypeInfo();
            mPerPeerTypeInfo[PEER_AWARE] = new PerPeerTypeInfo();
            mMeasurementDurationApOnlyHistogram.clear();
            mMeasurementDurationWithAwareHistogram.clear();
        }
    }

    /**
     * Convert a HAL RttStatus enum to a Metrics proto enum RttIndividualStatusTypeEnum.
     */
    public static int convertRttStatusTypeToProtoEnum(int rttStatusType) {
        switch (rttStatusType) {
            case RttNative.FRAMEWORK_RTT_STATUS_SUCCESS:
                return WifiMetricsProto.WifiRttLog.SUCCESS;
            case RttNative.FRAMEWORK_RTT_STATUS_FAILURE:
                return WifiMetricsProto.WifiRttLog.FAILURE;
            case RttNative.FRAMEWORK_RTT_STATUS_FAIL_NO_RSP:
                return WifiMetricsProto.WifiRttLog.FAIL_NO_RSP;
            case RttNative.FRAMEWORK_RTT_STATUS_FAIL_REJECTED:
                return WifiMetricsProto.WifiRttLog.FAIL_REJECTED;
            case RttNative.FRAMEWORK_RTT_STATUS_FAIL_NOT_SCHEDULED_YET:
                return WifiMetricsProto.WifiRttLog.FAIL_NOT_SCHEDULED_YET;
            case RttNative.FRAMEWORK_RTT_STATUS_FAIL_TM_TIMEOUT:
                return WifiMetricsProto.WifiRttLog.FAIL_TM_TIMEOUT;
            case RttNative.FRAMEWORK_RTT_STATUS_FAIL_AP_ON_DIFF_CHANNEL:
                return WifiMetricsProto.WifiRttLog.FAIL_AP_ON_DIFF_CHANNEL;
            case RttNative.FRAMEWORK_RTT_STATUS_FAIL_NO_CAPABILITY:
                return WifiMetricsProto.WifiRttLog.FAIL_NO_CAPABILITY;
            case RttNative.FRAMEWORK_RTT_STATUS_ABORTED:
                return WifiMetricsProto.WifiRttLog.ABORTED;
            case RttNative.FRAMEWORK_RTT_STATUS_FAIL_INVALID_TS:
                return WifiMetricsProto.WifiRttLog.FAIL_INVALID_TS;
            case RttNative.FRAMEWORK_RTT_STATUS_FAIL_PROTOCOL:
                return WifiMetricsProto.WifiRttLog.FAIL_PROTOCOL;
            case RttNative.FRAMEWORK_RTT_STATUS_FAIL_SCHEDULE:
                return WifiMetricsProto.WifiRttLog.FAIL_SCHEDULE;
            case RttNative.FRAMEWORK_RTT_STATUS_FAIL_BUSY_TRY_LATER:
                return WifiMetricsProto.WifiRttLog.FAIL_BUSY_TRY_LATER;
            case RttNative.FRAMEWORK_RTT_STATUS_INVALID_REQ:
                return WifiMetricsProto.WifiRttLog.INVALID_REQ;
            case RttNative.FRAMEWORK_RTT_STATUS_NO_WIFI:
                return WifiMetricsProto.WifiRttLog.NO_WIFI;
            case RttNative.FRAMEWORK_RTT_STATUS_FAIL_FTM_PARAM_OVERRIDE:
                return WifiMetricsProto.WifiRttLog.FAIL_FTM_PARAM_OVERRIDE;
            default:
                Log.e(TAG, "Unrecognized RttStatus: " + rttStatusType);
                return WifiMetricsProto.WifiRttLog.UNKNOWN;
        }
    }
}
