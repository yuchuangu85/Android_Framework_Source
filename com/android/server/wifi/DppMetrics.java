/*
 * Copyright (C) 2019 The Android Open Source Project
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

import static android.net.wifi.EasyConnectStatusCallback.EASY_CONNECT_EVENT_FAILURE_AUTHENTICATION;
import static android.net.wifi.EasyConnectStatusCallback.EASY_CONNECT_EVENT_FAILURE_BUSY;
import static android.net.wifi.EasyConnectStatusCallback.EASY_CONNECT_EVENT_FAILURE_CONFIGURATION;
import static android.net.wifi.EasyConnectStatusCallback.EASY_CONNECT_EVENT_FAILURE_GENERIC;
import static android.net.wifi.EasyConnectStatusCallback.EASY_CONNECT_EVENT_FAILURE_INVALID_NETWORK;
import static android.net.wifi.EasyConnectStatusCallback.EASY_CONNECT_EVENT_FAILURE_INVALID_URI;
import static android.net.wifi.EasyConnectStatusCallback.EASY_CONNECT_EVENT_FAILURE_NOT_COMPATIBLE;
import static android.net.wifi.EasyConnectStatusCallback.EASY_CONNECT_EVENT_FAILURE_NOT_SUPPORTED;
import static android.net.wifi.EasyConnectStatusCallback.EASY_CONNECT_EVENT_FAILURE_TIMEOUT;
import static android.net.wifi.EasyConnectStatusCallback.EASY_CONNECT_EVENT_SUCCESS_CONFIGURATION_SENT;

import android.net.wifi.EasyConnectStatusCallback;
import android.util.SparseIntArray;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.wifi.nano.WifiMetricsProto;
import com.android.server.wifi.util.IntHistogram;

import java.io.PrintWriter;

/**
 * Provides metrics for Wi-Fi Easy Connect (DPP). Metrics include number of initiator requests,
 * number of successes, failures and time completion histogram.
 */
public class DppMetrics {
    private final WifiMetricsProto.WifiDppLog mWifiDppLogProto = new WifiMetricsProto.WifiDppLog();

    // Easy-Connect (DPP) Metrics
    // Histogram for DPP operation time. Indicates the following 5 buckets (in seconds):
    //   < 1
    //   [1, 10)
    //   [10, 25)
    //   [25, 39)
    //   >= 39  - which means timeout.
    @VisibleForTesting
    public static final int[] DPP_OPERATION_TIME = {1, 10, 25, 39};
    private IntHistogram mHistogramDppOperationTime = new IntHistogram(DPP_OPERATION_TIME);

    // Failure codes
    private SparseIntArray mHistogramDppFailureCode = new SparseIntArray();

    // Configurator success codes
    private SparseIntArray mHistogramDppConfiguratorSuccessCode = new SparseIntArray();

    private final Object mLock = new Object();

    /**
     * Update DPP Configurator-Initiator requests
     */
    public void updateDppConfiguratorInitiatorRequests() {
        synchronized (mLock) {
            mWifiDppLogProto.numDppConfiguratorInitiatorRequests++;
        }
    }

    /**
     * Update DPP Enrollee-Initiator requests
     */
    public void updateDppEnrolleeInitiatorRequests() {
        synchronized (mLock) {
            mWifiDppLogProto.numDppEnrolleeInitiatorRequests++;
        }
    }

    /**
     * Update DPP Enrollee success counter
     */
    public void updateDppEnrolleeSuccess() {
        synchronized (mLock) {
            mWifiDppLogProto.numDppEnrolleeSuccess++;
        }
    }

    /**
     * Update DPP Configurator success counter
     */
    public void updateDppConfiguratorSuccess(
            @EasyConnectStatusCallback.EasyConnectSuccessStatusCode int code) {
        synchronized (mLock) {
            switch (code) {
                case EASY_CONNECT_EVENT_SUCCESS_CONFIGURATION_SENT:
                    mHistogramDppConfiguratorSuccessCode.put(WifiMetricsProto.WifiDppLog
                                    .EASY_CONNECT_EVENT_SUCCESS_CONFIGURATION_SENT,
                            mHistogramDppConfiguratorSuccessCode.get(WifiMetricsProto.WifiDppLog
                                    .EASY_CONNECT_EVENT_SUCCESS_CONFIGURATION_SENT) + 1);
                    break;
                default:
                    break;
            }
        }
    }

    /**
     * Update DPP failure counters
     */
    public void updateDppFailure(@EasyConnectStatusCallback.EasyConnectFailureStatusCode int code) {
        synchronized (mLock) {
            switch (code) {
                case EASY_CONNECT_EVENT_FAILURE_INVALID_URI:
                    mHistogramDppFailureCode.put(WifiMetricsProto.WifiDppLog
                                    .EASY_CONNECT_EVENT_FAILURE_INVALID_URI,
                            mHistogramDppFailureCode.get(WifiMetricsProto.WifiDppLog
                                    .EASY_CONNECT_EVENT_FAILURE_INVALID_URI) + 1);
                    break;
                case EASY_CONNECT_EVENT_FAILURE_AUTHENTICATION:
                    mHistogramDppFailureCode.put(WifiMetricsProto.WifiDppLog
                                    .EASY_CONNECT_EVENT_FAILURE_AUTHENTICATION,
                            mHistogramDppFailureCode.get(WifiMetricsProto.WifiDppLog
                                    .EASY_CONNECT_EVENT_FAILURE_AUTHENTICATION) + 1);
                    break;
                case EASY_CONNECT_EVENT_FAILURE_NOT_COMPATIBLE:
                    mHistogramDppFailureCode.put(WifiMetricsProto.WifiDppLog
                                    .EASY_CONNECT_EVENT_FAILURE_NOT_COMPATIBLE,
                            mHistogramDppFailureCode.get(WifiMetricsProto.WifiDppLog
                                    .EASY_CONNECT_EVENT_FAILURE_NOT_COMPATIBLE) + 1);
                    break;
                case EASY_CONNECT_EVENT_FAILURE_CONFIGURATION:
                    mHistogramDppFailureCode.put(WifiMetricsProto.WifiDppLog
                                    .EASY_CONNECT_EVENT_FAILURE_CONFIGURATION,
                            mHistogramDppFailureCode.get(WifiMetricsProto.WifiDppLog
                                    .EASY_CONNECT_EVENT_FAILURE_CONFIGURATION) + 1);
                    break;
                case EASY_CONNECT_EVENT_FAILURE_BUSY:
                    mHistogramDppFailureCode.put(WifiMetricsProto.WifiDppLog
                                    .EASY_CONNECT_EVENT_FAILURE_BUSY,
                            mHistogramDppFailureCode.get(WifiMetricsProto.WifiDppLog
                                    .EASY_CONNECT_EVENT_FAILURE_BUSY) + 1);
                    break;
                case EASY_CONNECT_EVENT_FAILURE_TIMEOUT:
                    mHistogramDppFailureCode.put(WifiMetricsProto.WifiDppLog
                                    .EASY_CONNECT_EVENT_FAILURE_TIMEOUT,
                            mHistogramDppFailureCode.get(WifiMetricsProto.WifiDppLog
                                    .EASY_CONNECT_EVENT_FAILURE_TIMEOUT) + 1);
                    break;
                case EASY_CONNECT_EVENT_FAILURE_GENERIC:
                    mHistogramDppFailureCode.put(WifiMetricsProto.WifiDppLog
                                    .EASY_CONNECT_EVENT_FAILURE_GENERIC,
                            mHistogramDppFailureCode.get(WifiMetricsProto.WifiDppLog
                                    .EASY_CONNECT_EVENT_FAILURE_GENERIC) + 1);
                    break;
                case EASY_CONNECT_EVENT_FAILURE_NOT_SUPPORTED:
                    mHistogramDppFailureCode.put(WifiMetricsProto.WifiDppLog
                                    .EASY_CONNECT_EVENT_FAILURE_NOT_SUPPORTED,
                            mHistogramDppFailureCode.get(WifiMetricsProto.WifiDppLog
                                    .EASY_CONNECT_EVENT_FAILURE_NOT_SUPPORTED) + 1);
                    break;
                case EASY_CONNECT_EVENT_FAILURE_INVALID_NETWORK:
                    mHistogramDppFailureCode.put(WifiMetricsProto.WifiDppLog
                                    .EASY_CONNECT_EVENT_FAILURE_INVALID_NETWORK,
                            mHistogramDppFailureCode.get(WifiMetricsProto.WifiDppLog
                                    .EASY_CONNECT_EVENT_FAILURE_INVALID_NETWORK) + 1);
                    break;
                default:
                    break;
            }
        }
    }

    /**
     * Update DPP operation time
     *
     * @param timeMs Time it took to complete the operation, in milliseconds
     */
    public void updateDppOperationTime(int timeMs) {
        synchronized (mLock) {
            mHistogramDppOperationTime.increment(timeMs / 1000);
        }
    }

    /**
     * Dump all DPP metrics
     *
     * @param pw PrintWriter handle
     */
    public void dump(PrintWriter pw) {
        synchronized (mLock) {
            pw.println("---Easy Connect/DPP metrics---");
            pw.println("mWifiDppLogProto.numDppConfiguratorInitiatorRequests="
                    + mWifiDppLogProto.numDppConfiguratorInitiatorRequests);
            pw.println("mWifiDppLogProto.numDppEnrolleeInitiatorRequests="
                    + mWifiDppLogProto.numDppEnrolleeInitiatorRequests);
            pw.println("mWifiDppLogProto.numDppEnrolleeSuccess="
                    + mWifiDppLogProto.numDppEnrolleeSuccess);

            if (mHistogramDppFailureCode.size() > 0) {
                pw.println("mHistogramDppFailureCode=");
                pw.println(mHistogramDppFailureCode);
            }

            if (mHistogramDppConfiguratorSuccessCode.size() > 0) {
                pw.println("mHistogramDppConfiguratorSuccessCode=");
                pw.println(mHistogramDppConfiguratorSuccessCode);
            }

            if (mHistogramDppOperationTime.numNonEmptyBuckets() > 0) {
                pw.println("mHistogramDppOperationTime=");
                pw.println(mHistogramDppOperationTime);
            }
            pw.println("---End of Easy Connect/DPP metrics---");
        }
    }

    /**
     * Clear all DPP metrics
     */
    public void clear() {
        synchronized (mLock) {
            mWifiDppLogProto.numDppConfiguratorInitiatorRequests = 0;
            mWifiDppLogProto.numDppEnrolleeInitiatorRequests = 0;
            mWifiDppLogProto.numDppEnrolleeSuccess = 0;
            mHistogramDppFailureCode.clear();
            mHistogramDppOperationTime.clear();
            mHistogramDppConfiguratorSuccessCode.clear();
        }
    }

    private WifiMetricsProto.WifiDppLog.DppFailureStatusHistogramBucket[] consolidateDppFailure(
            SparseIntArray data) {
        WifiMetricsProto.WifiDppLog.DppFailureStatusHistogramBucket[]
                dppFailureStatusHistogramBuckets =
                new WifiMetricsProto.WifiDppLog.DppFailureStatusHistogramBucket[data.size()];

        for (int i = 0; i < data.size(); i++) {
            dppFailureStatusHistogramBuckets[i] =
                    new WifiMetricsProto.WifiDppLog.DppFailureStatusHistogramBucket();
            dppFailureStatusHistogramBuckets[i].dppStatusType = data.keyAt(i);
            dppFailureStatusHistogramBuckets[i].count = data.valueAt(i);
        }

        return dppFailureStatusHistogramBuckets;
    }

    private WifiMetricsProto.WifiDppLog.DppConfiguratorSuccessStatusHistogramBucket[]
            consolidateDppSuccess(
            SparseIntArray data) {
        WifiMetricsProto.WifiDppLog.DppConfiguratorSuccessStatusHistogramBucket[]
                dppConfiguratorSuccessStatusHistogramBuckets =
                new WifiMetricsProto.WifiDppLog
                        .DppConfiguratorSuccessStatusHistogramBucket[data.size()];

        for (int i = 0; i < data.size(); i++) {
            dppConfiguratorSuccessStatusHistogramBuckets[i] =
                    new WifiMetricsProto.WifiDppLog.DppConfiguratorSuccessStatusHistogramBucket();
            dppConfiguratorSuccessStatusHistogramBuckets[i].dppStatusType = data.keyAt(i);
            dppConfiguratorSuccessStatusHistogramBuckets[i].count = data.valueAt(i);
        }

        return dppConfiguratorSuccessStatusHistogramBuckets;
    }

    /**
     * Consolidate all metrics into the proto.
     */
    public WifiMetricsProto.WifiDppLog consolidateProto() {
        WifiMetricsProto.WifiDppLog log = new WifiMetricsProto.WifiDppLog();
        synchronized (mLock) {
            log.numDppConfiguratorInitiatorRequests =
                    mWifiDppLogProto.numDppConfiguratorInitiatorRequests;
            log.numDppEnrolleeInitiatorRequests = mWifiDppLogProto.numDppEnrolleeInitiatorRequests;
            log.numDppEnrolleeSuccess = mWifiDppLogProto.numDppEnrolleeSuccess;
            log.dppFailureCode = consolidateDppFailure(mHistogramDppFailureCode);
            log.dppConfiguratorSuccessCode =
                    consolidateDppSuccess(mHistogramDppConfiguratorSuccessCode);
            log.dppOperationTime = mHistogramDppOperationTime.toProto();
        }
        return log;
    }
}
