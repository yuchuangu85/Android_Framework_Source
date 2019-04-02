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

package com.android.server.wifi.aware;

import android.hardware.wifi.V1_0.NanStatusType;
import android.net.wifi.aware.WifiAwareNetworkSpecifier;
import android.text.TextUtils;
import android.util.Log;
import android.util.SparseArray;
import android.util.SparseIntArray;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.wifi.Clock;
import com.android.server.wifi.nano.WifiMetricsProto;
import com.android.server.wifi.util.MetricsUtils;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Wi-Fi Aware metric container/processor.
 */
public class WifiAwareMetrics {
    private static final String TAG = "WifiAwareMetrics";
    private static final boolean VDBG = false;
    /* package */ boolean mDbg = false;

    // Histogram: 8 buckets (i=0, ..., 7) of 9 slots in range 10^i -> 10^(i+1)
    // Buckets:
    //    1 -> 10: 9 @ 1
    //    10 -> 100: 9 @ 10
    //    100 -> 1000: 9 @ 10^2
    //    10^3 -> 10^4: 9 @ 10^3
    //    10^4 -> 10^5: 9 @ 10^4
    //    10^5 -> 10^6: 9 @ 10^5
    //    10^6 -> 10^7: 9 @ 10^6
    //    10^7 -> 10^8: 9 @ 10^7 --> 10^8 ms -> 10^5s -> 28 hours
    private static final MetricsUtils.LogHistParms DURATION_LOG_HISTOGRAM =
            new MetricsUtils.LogHistParms(0, 1, 10, 9, 8);

    // Histogram for ranging limits in discovery. Indicates the following 5 buckets (in meters):
    //   < 10
    //   [10, 30)
    //   [30, 60)
    //   [60, 100)
    //   >= 100
    private static final int[] RANGING_LIMIT_METERS = { 10, 30, 60, 100 };

    private final Object mLock = new Object();
    private final Clock mClock;

    // enableUsage/disableUsage data
    private long mLastEnableUsageMs = 0;
    private long mLastEnableUsageInThisSampleWindowMs = 0;
    private long mAvailableTimeMs = 0;
    private SparseIntArray mHistogramAwareAvailableDurationMs = new SparseIntArray();

    // enabled data
    private long mLastEnableAwareMs = 0;
    private long mLastEnableAwareInThisSampleWindowMs = 0;
    private long mEnabledTimeMs = 0;
    private SparseIntArray mHistogramAwareEnabledDurationMs = new SparseIntArray();

    // attach data
    private static class AttachData {
        boolean mUsesIdentityCallback; // do any attach sessions of the UID use identity callback
        int mMaxConcurrentAttaches;
    }
    private Map<Integer, AttachData> mAttachDataByUid = new HashMap<>();
    private SparseIntArray mAttachStatusData = new SparseIntArray();
    private SparseIntArray mHistogramAttachDuration = new SparseIntArray();

    // discovery data
    private int mMaxPublishInApp = 0;
    private int mMaxSubscribeInApp = 0;
    private int mMaxDiscoveryInApp = 0;
    private int mMaxPublishInSystem = 0;
    private int mMaxSubscribeInSystem = 0;
    private int mMaxDiscoveryInSystem = 0;
    private SparseIntArray mPublishStatusData = new SparseIntArray();
    private SparseIntArray mSubscribeStatusData = new SparseIntArray();
    private SparseIntArray mHistogramPublishDuration = new SparseIntArray();
    private SparseIntArray mHistogramSubscribeDuration = new SparseIntArray();
    private Set<Integer> mAppsWithDiscoverySessionResourceFailure = new HashSet<>();

    // discovery with ranging data
    private int mMaxPublishWithRangingInApp = 0;
    private int mMaxSubscribeWithRangingInApp = 0;
    private int mMaxPublishWithRangingInSystem = 0;
    private int mMaxSubscribeWithRangingInSystem = 0;
    private SparseIntArray mHistogramSubscribeGeofenceMin = new SparseIntArray();
    private SparseIntArray mHistogramSubscribeGeofenceMax = new SparseIntArray();
    private int mNumSubscribesWithRanging = 0;
    private int mNumMatchesWithRanging = 0;
    private int mNumMatchesWithoutRangingForRangingEnabledSubscribes = 0;

    // data-path (NDI/NDP) data
    private int mMaxNdiInApp = 0;
    private int mMaxNdpInApp = 0;
    private int mMaxSecureNdpInApp = 0;
    private int mMaxNdiInSystem = 0;
    private int mMaxNdpInSystem = 0;
    private int mMaxSecureNdpInSystem = 0;
    private int mMaxNdpPerNdi = 0;
    private SparseIntArray mInBandNdpStatusData = new SparseIntArray();
    private SparseIntArray mOutOfBandNdpStatusData = new SparseIntArray();

    private SparseIntArray mNdpCreationTimeDuration = new SparseIntArray();
    private long mNdpCreationTimeMin = -1;
    private long mNdpCreationTimeMax = 0;
    private long mNdpCreationTimeSum = 0;
    private long mNdpCreationTimeSumSq = 0;
    private long mNdpCreationTimeNumSamples = 0;

    private SparseIntArray mHistogramNdpDuration = new SparseIntArray();

    public WifiAwareMetrics(Clock clock) {
        mClock = clock;
    }

    /**
     * Push usage stats for WifiAwareStateMachine.enableUsage() to
     * histogram_aware_available_duration_ms.
     */
    public void recordEnableUsage() {
        synchronized (mLock) {
            if (mLastEnableUsageMs != 0) {
                Log.w(TAG, "enableUsage: mLastEnableUsage*Ms initialized!?");
            }
            mLastEnableUsageMs = mClock.getElapsedSinceBootMillis();
            mLastEnableUsageInThisSampleWindowMs = mLastEnableUsageMs;
        }
    }

    /**
     * Push usage stats for WifiAwareStateMachine.disableUsage() to
     * histogram_aware_available_duration_ms.
     */

    public void recordDisableUsage() {
        synchronized (mLock) {
            if (mLastEnableUsageMs == 0) {
                Log.e(TAG, "disableUsage: mLastEnableUsage not initialized!?");
                return;
            }

            long now = mClock.getElapsedSinceBootMillis();
            MetricsUtils.addValueToLogHistogram(now - mLastEnableUsageMs,
                    mHistogramAwareAvailableDurationMs, DURATION_LOG_HISTOGRAM);
            mAvailableTimeMs += now - mLastEnableUsageInThisSampleWindowMs;
            mLastEnableUsageMs = 0;
            mLastEnableUsageInThisSampleWindowMs = 0;
        }
    }

    /**
     * Push usage stats of Aware actually being enabled on-the-air: start
     */
    public void recordEnableAware() {
        synchronized (mLock) {
            if (mLastEnableAwareMs != 0) {
                return; // already enabled
            }
            mLastEnableAwareMs = mClock.getElapsedSinceBootMillis();
            mLastEnableAwareInThisSampleWindowMs = mLastEnableAwareMs;
        }
    }

    /**
     * Push usage stats of Aware actually being enabled on-the-air: stop (disable)
     */
    public void recordDisableAware() {
        synchronized (mLock) {
            if (mLastEnableAwareMs == 0) {
                return; // already disabled
            }

            long now = mClock.getElapsedSinceBootMillis();
            MetricsUtils.addValueToLogHistogram(now - mLastEnableAwareMs,
                    mHistogramAwareEnabledDurationMs, DURATION_LOG_HISTOGRAM);
            mEnabledTimeMs += now - mLastEnableAwareInThisSampleWindowMs;
            mLastEnableAwareMs = 0;
            mLastEnableAwareInThisSampleWindowMs = 0;
        }
    }

    /**
     * Push information about a new attach session.
     */
    public void recordAttachSession(int uid, boolean usesIdentityCallback,
            SparseArray<WifiAwareClientState> clients) {
        // count the number of clients with the specific uid
        int currentConcurrentCount = 0;
        for (int i = 0; i < clients.size(); ++i) {
            if (clients.valueAt(i).getUid() == uid) {
                ++currentConcurrentCount;
            }
        }

        synchronized (mLock) {
            AttachData data = mAttachDataByUid.get(uid);
            if (data == null) {
                data = new AttachData();
                mAttachDataByUid.put(uid, data);
            }
            data.mUsesIdentityCallback |= usesIdentityCallback;
            data.mMaxConcurrentAttaches = Math.max(data.mMaxConcurrentAttaches,
                    currentConcurrentCount);
            recordAttachStatus(NanStatusType.SUCCESS);
        }
    }

    /**
     * Push information about a new attach session status (recorded when attach session is created).
     */
    public void recordAttachStatus(int status) {
        synchronized (mLock) {
            mAttachStatusData.put(status, mAttachStatusData.get(status) + 1);
        }
    }

    /**
     * Push duration information of an attach session.
     */
    public void recordAttachSessionDuration(long creationTime) {
        synchronized (mLock) {
            MetricsUtils.addValueToLogHistogram(mClock.getElapsedSinceBootMillis() - creationTime,
                    mHistogramAttachDuration, DURATION_LOG_HISTOGRAM);
        }
    }

    /**
     * Push information about the new discovery session.
     */
    public void recordDiscoverySession(int uid, SparseArray<WifiAwareClientState> clients) {
        recordDiscoverySessionInternal(uid, clients, false, -1, -1);
    }

    /**
     * Push information about the new discovery session with ranging enabled
     */
    public void recordDiscoverySessionWithRanging(int uid, boolean isSubscriberWithRanging,
            int minRange, int maxRange, SparseArray<WifiAwareClientState> clients) {
        recordDiscoverySessionInternal(uid, clients, isSubscriberWithRanging, minRange, maxRange);
    }

    /**
     * Internal combiner of discovery session information.
     */
    private void recordDiscoverySessionInternal(int uid, SparseArray<WifiAwareClientState> clients,
            boolean isRangingEnabledSubscriber, int minRange, int maxRange) {
        // count the number of sessions per uid and overall
        int numPublishesInSystem = 0;
        int numSubscribesInSystem = 0;
        int numPublishesOnUid = 0;
        int numSubscribesOnUid = 0;

        int numPublishesWithRangingInSystem = 0;
        int numSubscribesWithRangingInSystem = 0;
        int numPublishesWithRangingOnUid = 0;
        int numSubscribesWithRangingOnUid = 0;

        for (int i = 0; i < clients.size(); ++i) {
            WifiAwareClientState client = clients.valueAt(i);
            boolean sameUid = client.getUid() == uid;

            SparseArray<WifiAwareDiscoverySessionState> sessions = client.getSessions();
            for (int j = 0; j < sessions.size(); ++j) {
                WifiAwareDiscoverySessionState session = sessions.valueAt(j);
                boolean isRangingEnabledForThisSession = session.isRangingEnabled();

                if (session.isPublishSession()) {
                    numPublishesInSystem += 1;
                    if (isRangingEnabledForThisSession) {
                        numPublishesWithRangingInSystem += 1;
                    }
                    if (sameUid) {
                        numPublishesOnUid += 1;
                        if (isRangingEnabledForThisSession) {
                            numPublishesWithRangingOnUid += 1;
                        }
                    }
                } else {
                    numSubscribesInSystem += 1;
                    if (isRangingEnabledForThisSession) {
                        numSubscribesWithRangingInSystem += 1;
                    }
                    if (sameUid) {
                        numSubscribesOnUid += 1;
                        if (isRangingEnabledForThisSession) {
                            numSubscribesWithRangingOnUid += 1;
                        }
                    }
                }
            }
        }

        synchronized (mLock) {
            mMaxPublishInApp = Math.max(mMaxPublishInApp, numPublishesOnUid);
            mMaxSubscribeInApp = Math.max(mMaxSubscribeInApp, numSubscribesOnUid);
            mMaxDiscoveryInApp = Math.max(mMaxDiscoveryInApp,
                    numPublishesOnUid + numSubscribesOnUid);
            mMaxPublishInSystem = Math.max(mMaxPublishInSystem, numPublishesInSystem);
            mMaxSubscribeInSystem = Math.max(mMaxSubscribeInSystem, numSubscribesInSystem);
            mMaxDiscoveryInSystem = Math.max(mMaxDiscoveryInSystem,
                    numPublishesInSystem + numSubscribesInSystem);

            mMaxPublishWithRangingInApp = Math.max(mMaxPublishWithRangingInApp,
                    numPublishesWithRangingOnUid);
            mMaxSubscribeWithRangingInApp = Math.max(mMaxSubscribeWithRangingInApp,
                    numSubscribesWithRangingOnUid);
            mMaxPublishWithRangingInSystem = Math.max(mMaxPublishWithRangingInSystem,
                    numPublishesWithRangingInSystem);
            mMaxSubscribeWithRangingInSystem = Math.max(mMaxSubscribeWithRangingInSystem,
                    numSubscribesWithRangingInSystem);
            if (isRangingEnabledSubscriber) {
                mNumSubscribesWithRanging += 1;
            }

            if (minRange != -1) {
                MetricsUtils.addValueToLinearHistogram(minRange, mHistogramSubscribeGeofenceMin,
                        RANGING_LIMIT_METERS);
            }
            if (maxRange != -1) {
                MetricsUtils.addValueToLinearHistogram(maxRange, mHistogramSubscribeGeofenceMax,
                        RANGING_LIMIT_METERS);
            }
        }
    }

    /**
     * Push information about a new discovery session status (recorded when the discovery session is
     * created).
     */
    public void recordDiscoveryStatus(int uid, int status, boolean isPublish) {
        synchronized (mLock) {
            if (isPublish) {
                mPublishStatusData.put(status, mPublishStatusData.get(status) + 1);
            } else {
                mSubscribeStatusData.put(status, mSubscribeStatusData.get(status) + 1);
            }

            if (status == NanStatusType.NO_RESOURCES_AVAILABLE) {
                mAppsWithDiscoverySessionResourceFailure.add(uid);
            }
        }
    }

    /**
     * Push duration information of a discovery session.
     */
    public void recordDiscoverySessionDuration(long creationTime, boolean isPublish) {
        synchronized (mLock) {
            MetricsUtils.addValueToLogHistogram(mClock.getElapsedSinceBootMillis() - creationTime,
                    isPublish ? mHistogramPublishDuration : mHistogramSubscribeDuration,
                    DURATION_LOG_HISTOGRAM);
        }
    }

    /**
     * Push information about Match indication (aka service discovered) for subscribe sessions
     * which enabled ranging. Collect information about whether or not service discovery was
     * triggered with ranging information or without (i.e. ranging disabled for some reason).
     */
    public void recordMatchIndicationForRangeEnabledSubscribe(boolean rangeProvided) {
        if (rangeProvided) {
            mNumMatchesWithRanging++;
        } else {
            mNumMatchesWithoutRangingForRangingEnabledSubscribes++;
        }
    }

    /**
     * Record NDP (and by extension NDI) usage - on successful creation of an NDP.
     */
    public void recordNdpCreation(int uid,
            Map<WifiAwareNetworkSpecifier, WifiAwareDataPathStateManager
                    .AwareNetworkRequestInformation> networkRequestCache) {
        int numNdpInApp = 0;
        int numSecureNdpInApp = 0;
        int numNdpInSystem = 0;
        int numSecureNdpInSystem = 0;

        Map<String, Integer> ndpPerNdiMap = new HashMap<>();
        Set<String> ndiInApp = new HashSet<>();
        Set<String> ndiInSystem = new HashSet<>();

        for (WifiAwareDataPathStateManager.AwareNetworkRequestInformation anri :
                networkRequestCache.values()) {
            if (anri.state
                    != WifiAwareDataPathStateManager.AwareNetworkRequestInformation
                    .STATE_CONFIRMED) {
                continue; // only count completed (up-and-running) NDPs
            }

            boolean sameUid = anri.uid == uid;
            boolean isSecure = !TextUtils.isEmpty(anri.networkSpecifier.passphrase) || (
                    anri.networkSpecifier.pmk != null && anri.networkSpecifier.pmk.length != 0);

            // in-app stats
            if (sameUid) {
                numNdpInApp += 1;
                if (isSecure) {
                    numSecureNdpInApp += 1;
                }

                ndiInApp.add(anri.interfaceName);
            }

            // system stats
            numNdpInSystem += 1;
            if (isSecure) {
                numSecureNdpInSystem += 1;
            }

            // ndp/ndi stats
            Integer ndpCount = ndpPerNdiMap.get(anri.interfaceName);
            if (ndpCount == null) {
                ndpPerNdiMap.put(anri.interfaceName, 1);
            } else {
                ndpPerNdiMap.put(anri.interfaceName, ndpCount + 1);
            }

            // ndi stats
            ndiInSystem.add(anri.interfaceName);
        }

        synchronized (mLock) {
            mMaxNdiInApp = Math.max(mMaxNdiInApp, ndiInApp.size());
            mMaxNdpInApp = Math.max(mMaxNdpInApp, numNdpInApp);
            mMaxSecureNdpInApp = Math.max(mMaxSecureNdpInApp, numSecureNdpInApp);
            mMaxNdiInSystem = Math.max(mMaxNdiInSystem, ndiInSystem.size());
            mMaxNdpInSystem = Math.max(mMaxNdpInSystem, numNdpInSystem);
            mMaxSecureNdpInSystem = Math.max(mMaxSecureNdpInSystem, numSecureNdpInSystem);
            mMaxNdpPerNdi = Math.max(mMaxNdpPerNdi, Collections.max(ndpPerNdiMap.values()));
        }
    }

    /**
     * Record the completion status of NDP negotiation. There are multiple steps in NDP negotiation
     * a failure on any aborts the process and is recorded. A success on intermediate stages is
     * not recorded - only the final success.
     */
    public void recordNdpStatus(int status, boolean isOutOfBand, long startTimestamp) {
        synchronized (mLock) {
            if (isOutOfBand) {
                mOutOfBandNdpStatusData.put(status, mOutOfBandNdpStatusData.get(status) + 1);
            } else {
                mInBandNdpStatusData.put(status, mOutOfBandNdpStatusData.get(status) + 1);
            }

            if (status == NanStatusType.SUCCESS) {
                long creationTime = mClock.getElapsedSinceBootMillis() - startTimestamp;
                MetricsUtils.addValueToLogHistogram(creationTime, mNdpCreationTimeDuration,
                        DURATION_LOG_HISTOGRAM);
                mNdpCreationTimeMin = (mNdpCreationTimeMin == -1) ? creationTime : Math.min(
                        mNdpCreationTimeMin, creationTime);
                mNdpCreationTimeMax = Math.max(mNdpCreationTimeMax, creationTime);
                mNdpCreationTimeSum += creationTime;
                mNdpCreationTimeSumSq += creationTime * creationTime;
                mNdpCreationTimeNumSamples += 1;
            }
        }
    }

    /**
     * Record the duration of the NDP session. The creation time is assumed to be the time at
     * which a confirm message was received (i.e. the end of the setup negotiation).
     */
    public void recordNdpSessionDuration(long creationTime) {
        synchronized (mLock) {
            MetricsUtils.addValueToLogHistogram(mClock.getElapsedSinceBootMillis() - creationTime,
                    mHistogramNdpDuration, DURATION_LOG_HISTOGRAM);
        }
    }

    /**
     * Consolidate all metrics into the proto.
     */
    public WifiMetricsProto.WifiAwareLog consolidateProto() {
        WifiMetricsProto.WifiAwareLog log = new WifiMetricsProto.WifiAwareLog();
        long now = mClock.getElapsedSinceBootMillis();
        synchronized (mLock) {
            log.histogramAwareAvailableDurationMs = histogramToProtoArray(
                    MetricsUtils.logHistogramToGenericBuckets(mHistogramAwareAvailableDurationMs,
                            DURATION_LOG_HISTOGRAM));
            log.availableTimeMs = mAvailableTimeMs;
            if (mLastEnableUsageInThisSampleWindowMs != 0) {
                log.availableTimeMs += now - mLastEnableUsageInThisSampleWindowMs;
            }

            log.histogramAwareEnabledDurationMs = histogramToProtoArray(
                    MetricsUtils.logHistogramToGenericBuckets(mHistogramAwareEnabledDurationMs,
                            DURATION_LOG_HISTOGRAM));
            log.enabledTimeMs = mEnabledTimeMs;
            if (mLastEnableAwareInThisSampleWindowMs != 0) {
                log.enabledTimeMs += now - mLastEnableAwareInThisSampleWindowMs;
            }

            log.numApps = mAttachDataByUid.size();
            log.numAppsUsingIdentityCallback = 0;
            log.maxConcurrentAttachSessionsInApp = 0;
            for (AttachData ad: mAttachDataByUid.values()) {
                if (ad.mUsesIdentityCallback) {
                    ++log.numAppsUsingIdentityCallback;
                }
                log.maxConcurrentAttachSessionsInApp = Math.max(
                        log.maxConcurrentAttachSessionsInApp, ad.mMaxConcurrentAttaches);
            }
            log.histogramAttachSessionStatus = histogramToProtoArray(mAttachStatusData);
            log.histogramAttachDurationMs = histogramToProtoArray(
                    MetricsUtils.logHistogramToGenericBuckets(mHistogramAttachDuration,
                            DURATION_LOG_HISTOGRAM));

            log.maxConcurrentPublishInApp = mMaxPublishInApp;
            log.maxConcurrentSubscribeInApp = mMaxSubscribeInApp;
            log.maxConcurrentDiscoverySessionsInApp = mMaxDiscoveryInApp;
            log.maxConcurrentPublishInSystem = mMaxPublishInSystem;
            log.maxConcurrentSubscribeInSystem = mMaxSubscribeInSystem;
            log.maxConcurrentDiscoverySessionsInSystem = mMaxDiscoveryInSystem;
            log.histogramPublishStatus = histogramToProtoArray(mPublishStatusData);
            log.histogramSubscribeStatus = histogramToProtoArray(mSubscribeStatusData);
            log.numAppsWithDiscoverySessionFailureOutOfResources =
                    mAppsWithDiscoverySessionResourceFailure.size();
            log.histogramPublishSessionDurationMs = histogramToProtoArray(
                    MetricsUtils.logHistogramToGenericBuckets(mHistogramPublishDuration,
                            DURATION_LOG_HISTOGRAM));
            log.histogramSubscribeSessionDurationMs = histogramToProtoArray(
                    MetricsUtils.logHistogramToGenericBuckets(mHistogramSubscribeDuration,
                            DURATION_LOG_HISTOGRAM));

            log.maxConcurrentPublishWithRangingInApp = mMaxPublishWithRangingInApp;
            log.maxConcurrentSubscribeWithRangingInApp = mMaxSubscribeWithRangingInApp;
            log.maxConcurrentPublishWithRangingInSystem = mMaxPublishWithRangingInSystem;
            log.maxConcurrentSubscribeWithRangingInSystem = mMaxSubscribeWithRangingInSystem;
            log.histogramSubscribeGeofenceMin = histogramToProtoArray(
                    MetricsUtils.linearHistogramToGenericBuckets(mHistogramSubscribeGeofenceMin,
                            RANGING_LIMIT_METERS));
            log.histogramSubscribeGeofenceMax = histogramToProtoArray(
                    MetricsUtils.linearHistogramToGenericBuckets(mHistogramSubscribeGeofenceMax,
                            RANGING_LIMIT_METERS));
            log.numSubscribesWithRanging = mNumSubscribesWithRanging;
            log.numMatchesWithRanging = mNumMatchesWithRanging;
            log.numMatchesWithoutRangingForRangingEnabledSubscribes =
                    mNumMatchesWithoutRangingForRangingEnabledSubscribes;

            log.maxConcurrentNdiInApp = mMaxNdiInApp;
            log.maxConcurrentNdiInSystem = mMaxNdiInSystem;
            log.maxConcurrentNdpInApp = mMaxNdpInApp;
            log.maxConcurrentNdpInSystem = mMaxNdpInSystem;
            log.maxConcurrentSecureNdpInApp = mMaxSecureNdpInApp;
            log.maxConcurrentSecureNdpInSystem = mMaxSecureNdpInSystem;
            log.maxConcurrentNdpPerNdi = mMaxNdpPerNdi;
            log.histogramRequestNdpStatus = histogramToProtoArray(mInBandNdpStatusData);
            log.histogramRequestNdpOobStatus = histogramToProtoArray(mOutOfBandNdpStatusData);

            log.histogramNdpCreationTimeMs = histogramToProtoArray(
                    MetricsUtils.logHistogramToGenericBuckets(mNdpCreationTimeDuration,
                            DURATION_LOG_HISTOGRAM));
            log.ndpCreationTimeMsMin = mNdpCreationTimeMin;
            log.ndpCreationTimeMsMax = mNdpCreationTimeMax;
            log.ndpCreationTimeMsSum = mNdpCreationTimeSum;
            log.ndpCreationTimeMsSumOfSq = mNdpCreationTimeSumSq;
            log.ndpCreationTimeMsNumSamples = mNdpCreationTimeNumSamples;

            log.histogramNdpSessionDurationMs = histogramToProtoArray(
                    MetricsUtils.logHistogramToGenericBuckets(mHistogramNdpDuration,
                            DURATION_LOG_HISTOGRAM));
        }
        return log;
    }

    /**
     * clear Wi-Fi Aware metrics
     */
    public void clear() {
        long now = mClock.getElapsedSinceBootMillis();
        synchronized (mLock) {
            // don't clear mLastEnableUsage since could be valid for next measurement period
            mHistogramAwareAvailableDurationMs.clear();
            mAvailableTimeMs = 0;
            if (mLastEnableUsageInThisSampleWindowMs != 0) {
                mLastEnableUsageInThisSampleWindowMs = now;
            }

            // don't clear mLastEnableAware since could be valid for next measurement period
            mHistogramAwareEnabledDurationMs.clear();
            mEnabledTimeMs = 0;
            if (mLastEnableAwareInThisSampleWindowMs != 0) {
                mLastEnableAwareInThisSampleWindowMs = now;
            }

            mAttachDataByUid.clear();
            mAttachStatusData.clear();
            mHistogramAttachDuration.clear();

            mMaxPublishInApp = 0;
            mMaxSubscribeInApp = 0;
            mMaxDiscoveryInApp = 0;
            mMaxPublishInSystem = 0;
            mMaxSubscribeInSystem = 0;
            mMaxDiscoveryInSystem = 0;
            mPublishStatusData.clear();
            mSubscribeStatusData.clear();
            mHistogramPublishDuration.clear();
            mHistogramSubscribeDuration.clear();
            mAppsWithDiscoverySessionResourceFailure.clear();

            mMaxPublishWithRangingInApp = 0;
            mMaxSubscribeWithRangingInApp = 0;
            mMaxPublishWithRangingInSystem = 0;
            mMaxSubscribeWithRangingInSystem = 0;
            mHistogramSubscribeGeofenceMin.clear();
            mHistogramSubscribeGeofenceMax.clear();
            mNumSubscribesWithRanging = 0;
            mNumMatchesWithRanging = 0;
            mNumMatchesWithoutRangingForRangingEnabledSubscribes = 0;

            mMaxNdiInApp = 0;
            mMaxNdpInApp = 0;
            mMaxSecureNdpInApp = 0;
            mMaxNdiInSystem = 0;
            mMaxNdpInSystem = 0;
            mMaxSecureNdpInSystem = 0;
            mMaxNdpPerNdi = 0;
            mInBandNdpStatusData.clear();
            mOutOfBandNdpStatusData.clear();

            mNdpCreationTimeDuration.clear();
            mNdpCreationTimeMin = -1;
            mNdpCreationTimeMax = 0;
            mNdpCreationTimeSum = 0;
            mNdpCreationTimeSumSq = 0;
            mNdpCreationTimeNumSamples = 0;

            mHistogramNdpDuration.clear();
        }
    }

    /**
     * Dump all WifiAwareMetrics to console (pw) - this method is never called to dump the
     * serialized metrics (handled by parent WifiMetrics).
     *
     * @param fd   unused
     * @param pw   PrintWriter for writing dump to
     * @param args unused
     */
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        synchronized (mLock) {
            pw.println("mLastEnableUsageMs:" + mLastEnableUsageMs);
            pw.println(
                    "mLastEnableUsageInThisSampleWindowMs:" + mLastEnableUsageInThisSampleWindowMs);
            pw.println("mAvailableTimeMs:" + mAvailableTimeMs);
            pw.println("mHistogramAwareAvailableDurationMs:");
            for (int i = 0; i < mHistogramAwareAvailableDurationMs.size(); ++i) {
                pw.println("  " + mHistogramAwareAvailableDurationMs.keyAt(i) + ": "
                        + mHistogramAwareAvailableDurationMs.valueAt(i));
            }

            pw.println("mLastEnableAwareMs:" + mLastEnableAwareMs);
            pw.println(
                    "mLastEnableAwareInThisSampleWindowMs:" + mLastEnableAwareInThisSampleWindowMs);
            pw.println("mEnabledTimeMs:" + mEnabledTimeMs);
            pw.println("mHistogramAwareEnabledDurationMs:");
            for (int i = 0; i < mHistogramAwareEnabledDurationMs.size(); ++i) {
                pw.println("  " + mHistogramAwareEnabledDurationMs.keyAt(i) + ": "
                        + mHistogramAwareEnabledDurationMs.valueAt(i));
            }

            pw.println("mAttachDataByUid:");
            for (Map.Entry<Integer, AttachData> ade: mAttachDataByUid.entrySet()) {
                pw.println("  " + "uid=" + ade.getKey() + ": identity="
                        + ade.getValue().mUsesIdentityCallback + ", maxConcurrent="
                        + ade.getValue().mMaxConcurrentAttaches);
            }
            pw.println("mAttachStatusData:");
            for (int i = 0; i < mAttachStatusData.size(); ++i) {
                pw.println("  " + mAttachStatusData.keyAt(i) + ": "
                        + mAttachStatusData.valueAt(i));
            }
            pw.println("mHistogramAttachDuration:");
            for (int i = 0; i < mHistogramAttachDuration.size(); ++i) {
                pw.println("  " + mHistogramAttachDuration.keyAt(i) + ": "
                        + mHistogramAttachDuration.valueAt(i));
            }

            pw.println("mMaxPublishInApp:" + mMaxPublishInApp);
            pw.println("mMaxSubscribeInApp:" + mMaxSubscribeInApp);
            pw.println("mMaxDiscoveryInApp:" + mMaxDiscoveryInApp);
            pw.println("mMaxPublishInSystem:" + mMaxPublishInSystem);
            pw.println("mMaxSubscribeInSystem:" + mMaxSubscribeInSystem);
            pw.println("mMaxDiscoveryInSystem:" + mMaxDiscoveryInSystem);
            pw.println("mPublishStatusData:");
            for (int i = 0; i < mPublishStatusData.size(); ++i) {
                pw.println("  " + mPublishStatusData.keyAt(i) + ": "
                        + mPublishStatusData.valueAt(i));
            }
            pw.println("mSubscribeStatusData:");
            for (int i = 0; i < mSubscribeStatusData.size(); ++i) {
                pw.println("  " + mSubscribeStatusData.keyAt(i) + ": "
                        + mSubscribeStatusData.valueAt(i));
            }
            pw.println("mHistogramPublishDuration:");
            for (int i = 0; i < mHistogramPublishDuration.size(); ++i) {
                pw.println("  " + mHistogramPublishDuration.keyAt(i) + ": "
                        + mHistogramPublishDuration.valueAt(i));
            }
            pw.println("mHistogramSubscribeDuration:");
            for (int i = 0; i < mHistogramSubscribeDuration.size(); ++i) {
                pw.println("  " + mHistogramSubscribeDuration.keyAt(i) + ": "
                        + mHistogramSubscribeDuration.valueAt(i));
            }
            pw.println("mAppsWithDiscoverySessionResourceFailure:");
            for (Integer uid: mAppsWithDiscoverySessionResourceFailure) {
                pw.println("  " + uid);

            }

            pw.println("mMaxPublishWithRangingInApp:" + mMaxPublishWithRangingInApp);
            pw.println("mMaxSubscribeWithRangingInApp:" + mMaxSubscribeWithRangingInApp);
            pw.println("mMaxPublishWithRangingInSystem:" + mMaxPublishWithRangingInSystem);
            pw.println("mMaxSubscribeWithRangingInSystem:" + mMaxSubscribeWithRangingInSystem);
            pw.println("mHistogramSubscribeGeofenceMin:");
            for (int i = 0; i < mHistogramSubscribeGeofenceMin.size(); ++i) {
                pw.println("  " + mHistogramSubscribeGeofenceMin.keyAt(i) + ": "
                        + mHistogramSubscribeGeofenceMin.valueAt(i));
            }
            pw.println("mHistogramSubscribeGeofenceMax:");
            for (int i = 0; i < mHistogramSubscribeGeofenceMax.size(); ++i) {
                pw.println("  " + mHistogramSubscribeGeofenceMax.keyAt(i) + ": "
                        + mHistogramSubscribeGeofenceMax.valueAt(i));
            }
            pw.println("mNumSubscribesWithRanging:" + mNumSubscribesWithRanging);
            pw.println("mNumMatchesWithRanging:" + mNumMatchesWithRanging);
            pw.println("mNumMatchesWithoutRangingForRangingEnabledSubscribes:"
                    + mNumMatchesWithoutRangingForRangingEnabledSubscribes);

            pw.println("mMaxNdiInApp:" + mMaxNdiInApp);
            pw.println("mMaxNdpInApp:" + mMaxNdpInApp);
            pw.println("mMaxSecureNdpInApp:" + mMaxSecureNdpInApp);
            pw.println("mMaxNdiInSystem:" + mMaxNdiInSystem);
            pw.println("mMaxNdpInSystem:" + mMaxNdpInSystem);
            pw.println("mMaxSecureNdpInSystem:" + mMaxSecureNdpInSystem);
            pw.println("mMaxNdpPerNdi:" + mMaxNdpPerNdi);
            pw.println("mInBandNdpStatusData:");
            for (int i = 0; i < mInBandNdpStatusData.size(); ++i) {
                pw.println("  " + mInBandNdpStatusData.keyAt(i) + ": "
                        + mInBandNdpStatusData.valueAt(i));
            }
            pw.println("mOutOfBandNdpStatusData:");
            for (int i = 0; i < mOutOfBandNdpStatusData.size(); ++i) {
                pw.println("  " + mOutOfBandNdpStatusData.keyAt(i) + ": "
                        + mOutOfBandNdpStatusData.valueAt(i));
            }

            pw.println("mNdpCreationTimeDuration:");
            for (int i = 0; i < mNdpCreationTimeDuration.size(); ++i) {
                pw.println("  " + mNdpCreationTimeDuration.keyAt(i) + ": "
                        + mNdpCreationTimeDuration.valueAt(i));
            }
            pw.println("mNdpCreationTimeMin:" + mNdpCreationTimeMin);
            pw.println("mNdpCreationTimeMax:" + mNdpCreationTimeMax);
            pw.println("mNdpCreationTimeSum:" + mNdpCreationTimeSum);
            pw.println("mNdpCreationTimeSumSq:" + mNdpCreationTimeSumSq);
            pw.println("mNdpCreationTimeNumSamples:" + mNdpCreationTimeNumSamples);

            pw.println("mHistogramNdpDuration:");
            for (int i = 0; i < mHistogramNdpDuration.size(); ++i) {
                pw.println("  " + mHistogramNdpDuration.keyAt(i) + ": "
                        + mHistogramNdpDuration.valueAt(i));
            }
        }
    }

    // histogram utilities
    /**
     * Convert a generic bucket to Aware HistogramBucket proto.
     */
    @VisibleForTesting
    public static WifiMetricsProto.WifiAwareLog.HistogramBucket[] histogramToProtoArray(
            MetricsUtils.GenericBucket[] buckets) {
        WifiMetricsProto.WifiAwareLog.HistogramBucket[] protoArray =
                new WifiMetricsProto.WifiAwareLog.HistogramBucket[buckets.length];

        for (int i = 0; i < buckets.length; ++i) {
            protoArray[i] = new WifiMetricsProto.WifiAwareLog.HistogramBucket();
            protoArray[i].start = buckets[i].start;
            protoArray[i].end = buckets[i].end;
            protoArray[i].count = buckets[i].count;
        }

        return protoArray;
    }

    /**
     * Adds the NanStatusType to the histogram (translating to the proto enumeration of the status).
     */
    public static void addNanHalStatusToHistogram(int halStatus, SparseIntArray histogram) {
        int protoStatus = convertNanStatusTypeToProtoEnum(halStatus);
        int newValue = histogram.get(protoStatus) + 1;
        histogram.put(protoStatus, newValue);
    }

    /**
     * Converts a histogram of proto NanStatusTypeEnum to a raw proto histogram.
     */
    @VisibleForTesting
    public static WifiMetricsProto.WifiAwareLog.NanStatusHistogramBucket[] histogramToProtoArray(
            SparseIntArray histogram) {
        WifiMetricsProto.WifiAwareLog.NanStatusHistogramBucket[] protoArray =
                new WifiMetricsProto.WifiAwareLog.NanStatusHistogramBucket[histogram.size()];

        for (int i = 0; i < histogram.size(); ++i) {
            protoArray[i] = new WifiMetricsProto.WifiAwareLog.NanStatusHistogramBucket();
            protoArray[i].nanStatusType = histogram.keyAt(i);
            protoArray[i].count = histogram.valueAt(i);
        }

        return protoArray;
    }

    /**
     * Convert a HAL NanStatusType enum to a Metrics proto enum NanStatusTypeEnum.
     */
    public static int convertNanStatusTypeToProtoEnum(int nanStatusType) {
        switch (nanStatusType) {
            case NanStatusType.SUCCESS:
                return WifiMetricsProto.WifiAwareLog.SUCCESS;
            case NanStatusType.INTERNAL_FAILURE:
                return WifiMetricsProto.WifiAwareLog.INTERNAL_FAILURE;
            case NanStatusType.PROTOCOL_FAILURE:
                return WifiMetricsProto.WifiAwareLog.PROTOCOL_FAILURE;
            case NanStatusType.INVALID_SESSION_ID:
                return WifiMetricsProto.WifiAwareLog.INVALID_SESSION_ID;
            case NanStatusType.NO_RESOURCES_AVAILABLE:
                return WifiMetricsProto.WifiAwareLog.NO_RESOURCES_AVAILABLE;
            case NanStatusType.INVALID_ARGS:
                return WifiMetricsProto.WifiAwareLog.INVALID_ARGS;
            case NanStatusType.INVALID_PEER_ID:
                return WifiMetricsProto.WifiAwareLog.INVALID_PEER_ID;
            case NanStatusType.INVALID_NDP_ID:
                return WifiMetricsProto.WifiAwareLog.INVALID_NDP_ID;
            case NanStatusType.NAN_NOT_ALLOWED:
                return WifiMetricsProto.WifiAwareLog.NAN_NOT_ALLOWED;
            case NanStatusType.NO_OTA_ACK:
                return WifiMetricsProto.WifiAwareLog.NO_OTA_ACK;
            case NanStatusType.ALREADY_ENABLED:
                return WifiMetricsProto.WifiAwareLog.ALREADY_ENABLED;
            case NanStatusType.FOLLOWUP_TX_QUEUE_FULL:
                return WifiMetricsProto.WifiAwareLog.FOLLOWUP_TX_QUEUE_FULL;
            case NanStatusType.UNSUPPORTED_CONCURRENCY_NAN_DISABLED:
                return WifiMetricsProto.WifiAwareLog.UNSUPPORTED_CONCURRENCY_NAN_DISABLED;
            default:
                Log.e(TAG, "Unrecognized NanStatusType: " + nanStatusType);
                return WifiMetricsProto.WifiAwareLog.UNKNOWN_HAL_STATUS;
        }
    }
}
