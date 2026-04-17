/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.internal.jank;

import static android.os.Trace.TRACE_TAG_APP;
import static android.view.SurfaceControl.JankData.JANK_APPLICATION;
import static android.view.SurfaceControl.JankData.JANK_COMPOSER;
import static android.view.SurfaceControl.JankData.JANK_NONE;

import static com.android.internal.jank.DisplayRefreshRate.UNKNOWN_REFRESH_RATE;
import static com.android.internal.jank.DisplayRefreshRate.VARIABLE_REFRESH_RATE;
import static com.android.internal.jank.InteractionJankMonitor.ACTION_SESSION_CANCEL;
import static com.android.internal.jank.InteractionJankMonitor.ACTION_SESSION_END;
import static com.android.internal.jank.InteractionJankMonitor.EXECUTOR_TASK_TIMEOUT;

import static java.lang.Double.isNaN;

import android.animation.AnimationHandler;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UiThread;
import android.os.Handler;
import android.os.Trace;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.SparseArray;
import android.view.Choreographer;
import android.view.SurfaceControl;
import android.view.SurfaceControl.JankData.JankType;
import android.view.View;
import android.view.ViewRootImpl;
import android.view.WindowCallbacks;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.jank.DisplayRefreshRate.RefreshRate;
import com.android.internal.jank.InteractionJankMonitor.Configuration;
import com.android.internal.util.FrameworkStatsLog;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * A class that allows the app to get the frame metrics from SurfaceFlinger.
 * @hide
 */
public class FrameTracker implements SurfaceControl.OnJankDataListener {
    private static final String TAG = "FrameTracker";

    private static final long INVALID_ID = -1;
    private static final int NANOS_IN_MILLISECOND = 1_000_000;
    private static final double NANOS_IN_SECOND = 1e9;
    private static final double LOG2 = Math.log(2);

    private static final int MAX_LENGTH_EVENT_DESC = 127;

    private static final int MAX_FLUSH_ATTEMPTS = 3;
    private static final int FLUSH_DELAY_MILLISECOND = 60;

    static final int REASON_END_UNKNOWN = -1;
    static final int REASON_END_NORMAL = 0;
    static final int REASON_END_SURFACE_DESTROYED = 1;
    static final int REASON_CANCEL_NORMAL = 16;
    static final int REASON_CANCEL_NOT_BEGUN = 17;
    static final int REASON_CANCEL_SAME_VSYNC = 18;
    static final int REASON_CANCEL_TIMEOUT = 19;

    /** @hide */
    @IntDef({
            REASON_END_UNKNOWN,
            REASON_END_NORMAL,
            REASON_END_SURFACE_DESTROYED,
            REASON_CANCEL_NORMAL,
            REASON_CANCEL_NOT_BEGUN,
            REASON_CANCEL_SAME_VSYNC,
            REASON_CANCEL_TIMEOUT,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Reasons {
    }

    private final int mTraceThresholdMissedFrames;
    private final int mTraceThresholdFrameTimeMillis;
    private final SparseArray<JankInfo> mJankInfos = new SparseArray<>();
    private final Configuration mConfig;
    private final ViewRootWrapper mViewRoot;
    private final SurfaceControlWrapper mSurfaceControlWrapper;
    private final int mDisplayId;
    private final ViewRootImpl.SurfaceChangedCallback mSurfaceChangedCallback;
    private final Handler mHandler;
    private final ChoreographerWrapper mChoreographer;
    private final StatsLogWrapper mStatsLog;
    private final boolean mDeferMonitoring;
    private final FrameTrackerListener mListener;

    @VisibleForTesting
    public final boolean mSurfaceOnly;

    private SurfaceControl mSurfaceControl;
    private SurfaceControl.OnJankDataListenerRegistration mJankDataListenerRegistration;
    private long mBeginVsyncId = INVALID_ID;
    private long mEndVsyncId = INVALID_ID;
    private boolean mMetricsFinalized;
    private boolean mCancelled = false;
    private boolean mTracingStarted = false;
    private Runnable mWaitForFinishTimedOut;

    private static class JankInfo {
        final long frameVsyncId;
        long totalDurationNanos;
        @JankType int jankTypeLegacy;
        @JankType int jankTypeExperimental;
        long frameInterval;
        long presentDelay;
        @RefreshRate int refreshRate = UNKNOWN_REFRESH_RATE;

        JankInfo(SurfaceControl.JankData jankStat) {
            this.frameVsyncId = jankStat.getVsyncId();
            update(jankStat);
        }

        JankInfo update(SurfaceControl.JankData jankStat) {
            this.jankTypeLegacy = jankStat.getJankTypeLegacy();
            this.jankTypeExperimental = jankStat.getJankTypeExperimental();
            this.frameInterval = jankStat.getFrameIntervalNanos();
            if (this.frameInterval <= 0) {
                Trace.instant(
                    TRACE_TAG_APP, "Invalid frame interval, assuming 120Hz: " + frameInterval);
                this.frameInterval = 8_333_333;
                this.refreshRate = UNKNOWN_REFRESH_RATE;
            } else {
                this.refreshRate = DisplayRefreshRate.getRefreshRate(this.frameInterval);
            }
            this.totalDurationNanos = jankStat.getActualAppFrameTimeNanos();
            this.presentDelay = jankStat.getPresentDelayNanos();
            return this;
        }

        private static void appendJankType(StringBuilder str, int jankType) {
            switch (jankType) {
                case JANK_NONE:
                    str.append("JANK_NONE");
                    break;
                case JANK_APPLICATION:
                    str.append("JANK_APPLICATION");
                    break;
                case JANK_COMPOSER:
                    str.append("JANK_COMPOSER");
                    break;
                default:
                    str.append("UNKNOWN: ").append(jankType);
                    break;
            }
        }

        @Override
        public String toString() {
            StringBuilder str = new StringBuilder();
            str.append("jankTypeLegacy: ");
            appendJankType(str, jankTypeLegacy);
            str.append(", jankTypeExperimental: ");
            appendJankType(str, jankTypeExperimental);
            str.append(", vsyncId: ").append(frameVsyncId);
            str.append(", totalDuration: ").append(totalDurationNanos);
            return str.toString();
        }
    }

    public FrameTracker(@NonNull Configuration config,
            @Nullable ViewRootWrapper viewRootWrapper,
            @NonNull SurfaceControlWrapper surfaceControlWrapper,
            @NonNull ChoreographerWrapper choreographer,
            @NonNull StatsLogWrapper statsLog,
            int traceThresholdMissedFrames, int traceThresholdFrameTimeMillis,
            @Nullable FrameTrackerListener listener) {
        mSurfaceOnly = config.isSurfaceOnly();
        mConfig = config;
        mHandler = config.getHandler();
        mChoreographer = choreographer;
        mSurfaceControlWrapper = surfaceControlWrapper;
        mStatsLog = statsLog;
        mDeferMonitoring = config.shouldDeferMonitor();

        // HWUI instrumentation init.
        mViewRoot = mSurfaceOnly ? null : viewRootWrapper;

        mTraceThresholdMissedFrames = traceThresholdMissedFrames;
        mTraceThresholdFrameTimeMillis = traceThresholdFrameTimeMillis;
        mListener = listener;
        mDisplayId = config.getDisplayId();

        if (mSurfaceOnly) {
            mSurfaceControl = config.getSurfaceControl();
            mSurfaceChangedCallback = null;
        } else {
            // HWUI instrumentation init.
            // If the surface isn't valid yet, wait until it's created.
            if (mViewRoot.getSurfaceControl().isValid()) {
                mSurfaceControl = mViewRoot.getSurfaceControl();
            }

            mSurfaceChangedCallback = new ViewRootImpl.SurfaceChangedCallback() {
                @Override
                public void surfaceCreated(SurfaceControl.Transaction t) {
                    Trace.beginSection("FrameTracker#surfaceCreated");
                    mHandler.runWithScissors(() -> {
                        if (mSurfaceControl == null) {
                            mSurfaceControl = mViewRoot.getSurfaceControl();
                            if (mBeginVsyncId != INVALID_ID) {
                                // Previous begin invocation is not successfully, begin it again.
                                begin();
                            }
                        }
                    }, EXECUTOR_TASK_TIMEOUT);
                    Trace.endSection();
                }

                @Override
                public void surfaceReplaced(SurfaceControl.Transaction t) {
                }

                @Override
                public void surfaceDestroyed() {
                    mHandler.post(() -> {
                        if (!mMetricsFinalized) {
                            end(REASON_END_SURFACE_DESTROYED);
                        }
                    });
                }
            };
            // This callback has a reference to FrameTracker,
            // remember to remove it to avoid leakage.
            mViewRoot.addSurfaceChangedCallback(mSurfaceChangedCallback);
        }
    }

    /**
     * Begin a trace session of the CUJ.
     */
    @UiThread
    public void begin() {
        final long currentVsync = mChoreographer.getVsyncId();
        // In normal case, we should begin at the next frame,
        // the id of the next frame is not simply increased by 1,
        // but we can exclude the current frame at least.
        if (mBeginVsyncId == INVALID_ID) {
            mBeginVsyncId = mDeferMonitoring ? currentVsync + 1 : currentVsync;
        }
        if (mSurfaceControl != null) {
            if (mDeferMonitoring && currentVsync < mBeginVsyncId) {
                markEvent("FT#deferMonitoring", 0);
                // Normal case, we begin the instrument from the very beginning,
                // will exclude the first frame.
                postTraceStartMarker(this::beginInternal);
            } else {
                // If we don't begin the instrument from the very beginning,
                // there is no need to skip the frame where the begin invocation happens.
                beginInternal();
            }
        }
    }

    /**
     * Start trace section at appropriate time.
     */
    @VisibleForTesting
    public void postTraceStartMarker(Runnable action) {
        mChoreographer.mChoreographer.postCallback(Choreographer.CALLBACK_INPUT, action, null);
    }

    @UiThread
    private void beginInternal() {
        if (mCancelled || mEndVsyncId != INVALID_ID) {
            return;
        }
        mTracingStarted = true;
        String name = mConfig.getSessionName();
        Trace.asyncTraceForTrackBegin(TRACE_TAG_APP, name, name, (int) mBeginVsyncId);
        markEvent("FT#beginVsync", mBeginVsyncId);
        markEvent("FT#layerId", mSurfaceControl.getLayerId());
        markCujUiThread();
        mJankDataListenerRegistration =
                mSurfaceControlWrapper.addJankStatsListener(this, mSurfaceControl);
    }

    /**
     * End the trace session of the CUJ.
     */
    @UiThread
    public boolean end(@Reasons int reason) {
        if (mCancelled || mEndVsyncId != INVALID_ID) return false;
        mEndVsyncId = AnimationHandler.getInstance().getLastAnimationFrameVsyncId(
                mChoreographer.getVsyncId());
        // Cancel the session if:
        // 1. The session begins and ends at the same vsync id.
        // 2. The session never begun.
        if (mBeginVsyncId == INVALID_ID) {
            return cancel(REASON_CANCEL_NOT_BEGUN);
        } else if (mEndVsyncId <= mBeginVsyncId) {
            return cancel(REASON_CANCEL_SAME_VSYNC);
        } else {
            final String name = mConfig.getSessionName();
            markEvent("FT#end", reason);
            markEvent("FT#endVsync", mEndVsyncId);
            Trace.asyncTraceForTrackEnd(TRACE_TAG_APP, name, (int) mBeginVsyncId);

            if (mJankDataListenerRegistration != null) {
                mJankDataListenerRegistration.removeAfter(mEndVsyncId);
            }

            // Waiting at most 10 seconds for all callbacks to finish.
            mWaitForFinishTimedOut = new Runnable() {
                private int mFlushAttempts = 0;

                @Override
                public void run() {
                    if (mWaitForFinishTimedOut == null || mMetricsFinalized) {
                        return;
                    }

                    // Send a flush jank data transaction.
                    if (mSurfaceControl != null && mSurfaceControl.isValid()) {
                        SurfaceControl.Transaction.sendSurfaceFlushJankData(mSurfaceControl);
                    }
                    if (mJankDataListenerRegistration != null) {
                        mJankDataListenerRegistration.flush();
                    }

                    long delay;
                    if (mFlushAttempts < MAX_FLUSH_ATTEMPTS) {
                        delay = FLUSH_DELAY_MILLISECOND;
                        mFlushAttempts++;
                    } else {
                        mWaitForFinishTimedOut = () -> {
                            Log.e(TAG, "force finish cuj, time out: " + name);
                            finish();
                        };
                        delay = TimeUnit.SECONDS.toMillis(10);
                    }
                    mHandler.postDelayed(mWaitForFinishTimedOut, delay);
                }
            };
            mHandler.postDelayed(mWaitForFinishTimedOut, FLUSH_DELAY_MILLISECOND);
            notifyCujEvent(ACTION_SESSION_END, reason);
            return true;
        }
    }

    /**
     * Cancel the trace session of the CUJ.
     */
    @UiThread
    public boolean cancel(@Reasons int reason) {
        final boolean cancelFromEnd =
                reason == REASON_CANCEL_NOT_BEGUN || reason == REASON_CANCEL_SAME_VSYNC;
        if (mCancelled || (mEndVsyncId != INVALID_ID && !cancelFromEnd)) return false;
        mCancelled = true;
        markEvent("FT#cancel", reason);
        // We don't need to end the trace section if it has never begun.
        if (mTracingStarted) {
            Trace.asyncTraceForTrackEnd(
                    TRACE_TAG_APP, mConfig.getSessionName(), (int) mBeginVsyncId);
        }

        // Always remove the observers in cancel call to avoid leakage.
        removeObservers();

        // Notify the listener the session has been cancelled.
        // We don't notify the listeners if the session never begun.
        notifyCujEvent(ACTION_SESSION_CANCEL, reason);
        return true;
    }

    /**
     * Mark the FrameTracker events in the trace.
     *
     * @param eventName  The description of the trace event,
     * @param eventValue The value of the related trace event
     *                   Both shouldn't exceed {@link #MAX_LENGTH_EVENT_DESC}.
     */
    private void markEvent(@NonNull String eventName, long eventValue) {
        if (Trace.isTagEnabled(TRACE_TAG_APP)) {
            String event = TextUtils.formatSimple("%s#%s", eventName, eventValue);
            if (event.length() > MAX_LENGTH_EVENT_DESC) {
                throw new IllegalArgumentException(TextUtils.formatSimple(
                        "The length of the trace event description <%s> exceeds %d",
                        event, MAX_LENGTH_EVENT_DESC));
            }
            Trace.instantForTrack(TRACE_TAG_APP, mConfig.getSessionName(), event);
        }
    }

    private void markCujUiThread() {
        if (Trace.isTagEnabled(TRACE_TAG_APP)) {
            // This is being called from the CUJ ui thread.
            Trace.instant(TRACE_TAG_APP, mConfig.getSessionName() + "#UIThread");
        }
    }

    private void notifyCujEvent(String action, @Reasons int reason) {
        if (mListener == null) return;
        mListener.onCujEvents(this, action, reason);
    }

    @Override
    public void onJankDataAvailable(List<SurfaceControl.JankData> jankData) {
        postCallback(() -> {
            try {
                Trace.beginSection("FrameTracker#onJankDataAvailable");
                if (mCancelled || mMetricsFinalized) {
                    return;
                }

                for (SurfaceControl.JankData jankStat : jankData) {
                    long vsync = jankStat.getVsyncId();
                    if (!isInRange(vsync)) {
                        continue;
                    }
                    JankInfo info = findJankInfo(vsync);
                    if (info != null) {
                        Trace.instant(TRACE_TAG_APP,
                                "Duplicate jank data for frame " + vsync);
                        info.update(jankStat);
                    } else {
                        mJankInfos.put((int) vsync, new JankInfo(jankStat));
                    }
                }
                processJankInfos();
            } finally {
                Trace.endSection();
            }
        });
    }

    /**
     * For easier argument capture.
     */
    @VisibleForTesting
    public void postCallback(Runnable callback) {
        mHandler.post(callback);
    }

    @Nullable
    private JankInfo findJankInfo(long frameVsyncId) {
        return mJankInfos.get((int) frameVsyncId);
    }

    private boolean isInRange(long vsyncId) {
        // It's possible that we may miss a callback for the frame with vsyncId == mEndVsyncId.
        // Because of that, we collect all frames even if they happen after the end so we eventually
        // have a frame after the end with both callbacks present.
        return vsyncId >= mBeginVsyncId;
    }

    @UiThread
    private boolean hasReceivedCallbacksAfterEnd() {
        if (mEndVsyncId == INVALID_ID) {
            return false;
        }
        JankInfo last = mJankInfos.size() == 0 ? null : mJankInfos.valueAt(mJankInfos.size() - 1);
        if (last == null) {
            return false;
        }
        if (last.frameVsyncId < mEndVsyncId) {
            return false;
        }

        return true;
    }

    @UiThread
    private void processJankInfos() {
        if (mMetricsFinalized) {
            return;
        }
        if (!hasReceivedCallbacksAfterEnd()) {
            return;
        }
        finish();
    }

    @UiThread
    private void finish() {
        Trace.beginSection("FrameTracker#finish");
        finishTraced();
        Trace.endSection();
    }

    @UiThread
    private void finishTraced() {
        if (mMetricsFinalized || mCancelled) return;
        mMetricsFinalized = true;

        mHandler.removeCallbacks(mWaitForFinishTimedOut);
        mWaitForFinishTimedOut = null;
        markEvent("FT#finish", mJankInfos.size());

        // The tracing has been ended, remove the observer, see if need to trigger perfetto.
        removeObservers();

        final String name = mConfig.getSessionName();

        // See b/416904263 for context on experimental vs legacy jank classification.
        int totalFramesCount = 0;
        long maxFrameTimeNanos = 0;
        int missedFramesCount = 0;
        int missedFramesCountLegacy = 0;
        int missedAppFramesCount = 0;
        int missedAppFramesCountLegacy = 0;
        int missedSfFramesCount = 0;
        int missedSfFramesCountLegacy = 0;
        int maxSuccessiveMissedFramesCount = 0;
        int successiveMissedFramesCount = 0;
        @RefreshRate int refreshRate = UNKNOWN_REFRESH_RATE;
        long totalAnimationTime = 0;
        float totalWeightedJank = 0;
        float appWeightedJank = 0;
        float sfWeightedJank = 0;

        for (int i = 0; i < mJankInfos.size(); i++) {
            JankInfo info = mJankInfos.valueAt(i);
            if (info.frameVsyncId > mEndVsyncId) {
                break;
            }

            totalFramesCount++;

            // Count missed frames using the legacy classification for WW data.
            boolean missedFrame = false;
            if ((info.jankTypeLegacy & JANK_APPLICATION) != 0) {
                Log.w(TAG, "Missed App frame:" + info + ", CUJ=" + name);
                missedAppFramesCountLegacy++;
                missedFrame = true;
            }
            if ((info.jankTypeLegacy & JANK_COMPOSER) != 0) {
                Log.w(TAG, "Missed SF frame:" + info + ", CUJ=" + name);
                missedSfFramesCountLegacy++;
                missedFrame = true;
            }

            if (missedFrame) {
                missedFramesCountLegacy++;
            }

            // Count missed frames using the experimental classification for trace data.
            missedFrame = false;
            if ((info.jankTypeExperimental & JANK_APPLICATION) != 0) {
                missedAppFramesCount++;
                missedFrame = true;
            }
            if ((info.jankTypeExperimental & JANK_COMPOSER) != 0) {
                missedSfFramesCount++;
                missedFrame = true;
            }

            if (missedFrame) {
                missedFramesCount++;
                successiveMissedFramesCount++;
            } else {
                maxSuccessiveMissedFramesCount = Math.max(
                        maxSuccessiveMissedFramesCount, successiveMissedFramesCount);
                successiveMissedFramesCount = 0;
            }

            // Weighted jank metric see go/refined-jank-metric.
            int jankType = Flags.useNewJankClassificationForJps()
                    ? info.jankTypeExperimental : info.jankTypeLegacy;
            if ((jankType & (JANK_APPLICATION | JANK_COMPOSER)) != 0) {
                totalAnimationTime += info.frameInterval + Math.max(info.presentDelay, 0);
                float weightedJank = computeWeightedJank(info);
                totalWeightedJank += weightedJank;
                if ((jankType & JANK_APPLICATION) != 0) {
                    appWeightedJank += weightedJank;
                }
                if ((jankType & JANK_COMPOSER) != 0) {
                    sfWeightedJank += weightedJank;
                }
            } else {
                totalAnimationTime += info.frameInterval;
            }

            if (info.refreshRate != UNKNOWN_REFRESH_RATE && info.refreshRate != refreshRate) {
                refreshRate = (refreshRate == UNKNOWN_REFRESH_RATE)
                        ? info.refreshRate : VARIABLE_REFRESH_RATE;
            }
            maxFrameTimeNanos = Math.max(info.totalDurationNanos, maxFrameTimeNanos);
        }
        maxSuccessiveMissedFramesCount = Math.max(
                maxSuccessiveMissedFramesCount, successiveMissedFramesCount);

        // Log the frame stats as counters to make them easily accessible in traces.
        if (Trace.isTagEnabled(TRACE_TAG_APP)) {
            Trace.traceCounter(TRACE_TAG_APP, name + "#missedFrames", missedFramesCount);
            Trace.traceCounter(TRACE_TAG_APP, name + "#missedAppFrames", missedAppFramesCount);
            Trace.traceCounter(TRACE_TAG_APP, name + "#missedSfFrames", missedSfFramesCount);
            Trace.traceCounter(TRACE_TAG_APP, name + "#totalFrames", totalFramesCount);
            Trace.traceCounter(TRACE_TAG_APP, name + "#maxFrameTimeMillis",
                    (int) (maxFrameTimeNanos / NANOS_IN_MILLISECOND));
            Trace.traceCounter(TRACE_TAG_APP, name + "#maxSuccessiveMissedFrames",
                    maxSuccessiveMissedFramesCount);
            Trace.traceCounter(TRACE_TAG_APP, name + "#totalAnimTime",
                    (int) totalAnimationTime / NANOS_IN_MILLISECOND);
            Trace.traceCounter(TRACE_TAG_APP, name + "#weightedSfJank",
                    (int) (sfWeightedJank / totalAnimationTime * NANOS_IN_SECOND * 1000));
            Trace.traceCounter(TRACE_TAG_APP, name + "#weightedAppJank",
                    (int) (appWeightedJank / totalAnimationTime * NANOS_IN_SECOND * 1000));
        }

        // Trigger perfetto if necessary.
        if (mListener != null
                && shouldTriggerPerfetto(missedFramesCount, (int) maxFrameTimeNanos)) {
            mListener.triggerPerfetto(mConfig);
        }
        if (mConfig.logToStatsd()) {
            mStatsLog.write(
                    FrameworkStatsLog.UI_INTERACTION_FRAME_INFO_REPORTED,
                    mDisplayId,
                    refreshRate,
                    mConfig.getStatsdInteractionType(),
                    totalFramesCount,
                    missedFramesCountLegacy,
                    maxFrameTimeNanos, /* will be 0 if mSurfaceOnly == true */
                    missedSfFramesCountLegacy,
                    missedAppFramesCountLegacy,
                    maxSuccessiveMissedFramesCount,
                    totalAnimationTime,
                    totalWeightedJank,
                    sfWeightedJank,
                    appWeightedJank);
        }
    }

    private boolean shouldTriggerPerfetto(int missedFramesCount, int maxFrameTimeNanos) {
        boolean overMissedFramesThreshold = mTraceThresholdMissedFrames != -1
                && missedFramesCount >= mTraceThresholdMissedFrames;
        boolean overFrameTimeThreshold = !mSurfaceOnly && mTraceThresholdFrameTimeMillis != -1
                && maxFrameTimeNanos >= mTraceThresholdFrameTimeMillis * NANOS_IN_MILLISECOND;
        return overMissedFramesThreshold || overFrameTimeThreshold;
    }

    private static float computeWeightedJank(JankInfo info) {
        double frameInterval = info.frameInterval;

        // Treat negative present delay, i.e. an early frame, the same as if it was late by the same
        // amount of time it was early.
        double frameTime = frameInterval + Math.abs(info.presentDelay);

        // Compute the jank severity, based on jank duration, and frame rate, normalized to 120Hz,
        // weights. See go/refined-jank-metric.
        double omegaS = Math.log(frameTime / frameInterval) / LOG2;
        double omegaF = Math.sqrt(frameInterval / 8_333_333.0);

        if (Trace.isTagEnabled(TRACE_TAG_APP)) {
            Trace.instant(TRACE_TAG_APP,
                    "vsync=" + info.frameVsyncId + ", omega_s=" + omegaS + ", omega_f=" + omegaF);
        }

        return (float) ((isNaN(omegaS) ? 1.0 : omegaS) * (isNaN(omegaF) ? 1.0 : omegaF));
    }

    /**
     * Remove all the registered listeners, observers and callbacks.
     */
    @VisibleForTesting
    @UiThread
    public void removeObservers() {
        if (mJankDataListenerRegistration != null) {
            mJankDataListenerRegistration.release();
            mJankDataListenerRegistration = null;
        }
        if (!mSurfaceOnly) {
            // HWUI part.
            if (mSurfaceChangedCallback != null) {
                mViewRoot.removeSurfaceChangedCallback(mSurfaceChangedCallback);
            }
        }
    }

    public static class ViewRootWrapper {
        private final ViewRootImpl mViewRoot;

        public ViewRootWrapper(ViewRootImpl viewRoot) {
            mViewRoot = viewRoot;
        }

        /**
         * {@link ViewRootImpl#addSurfaceChangedCallback(ViewRootImpl.SurfaceChangedCallback)}
         * @param callback {@link ViewRootImpl.SurfaceChangedCallback}
         */
        public void addSurfaceChangedCallback(ViewRootImpl.SurfaceChangedCallback callback) {
            mViewRoot.addSurfaceChangedCallback(callback);
        }

        /**
         * {@link ViewRootImpl#removeSurfaceChangedCallback(ViewRootImpl.SurfaceChangedCallback)}
         * @param callback {@link ViewRootImpl.SurfaceChangedCallback}
         */
        public void removeSurfaceChangedCallback(ViewRootImpl.SurfaceChangedCallback callback) {
            mViewRoot.removeSurfaceChangedCallback(callback);
        }

        public SurfaceControl getSurfaceControl() {
            return mViewRoot.getSurfaceControl();
        }

        void requestInvalidateRootRenderNode() {
            mViewRoot.requestInvalidateRootRenderNode();
        }

        void addWindowCallbacks(WindowCallbacks windowCallbacks) {
            mViewRoot.addWindowCallbacks(windowCallbacks);
        }

        void removeWindowCallbacks(WindowCallbacks windowCallbacks) {
            mViewRoot.removeWindowCallbacks(windowCallbacks);
        }

        View getView() {
            return mViewRoot.getView();
        }

        int dipToPx(int dip) {
            final DisplayMetrics displayMetrics =
                    mViewRoot.mContext.getResources().getDisplayMetrics();
            return (int) (displayMetrics.density * dip + 0.5f);
        }
    }

    public static class SurfaceControlWrapper {
        /** adds the jank listener to the given surface */
        public SurfaceControl.OnJankDataListenerRegistration addJankStatsListener(
                SurfaceControl.OnJankDataListener listener, SurfaceControl surfaceControl) {
            return surfaceControl.addOnJankDataListener(listener);
        }
    }

    public static class ChoreographerWrapper {

        private final Choreographer mChoreographer;

        public ChoreographerWrapper(Choreographer choreographer) {
            mChoreographer = choreographer;
        }

        public long getVsyncId() {
            return mChoreographer.getVsyncId();
        }
    }

    public static class StatsLogWrapper {
        private final DisplayResolutionTracker mDisplayResolutionTracker;

        public StatsLogWrapper(DisplayResolutionTracker displayResolutionTracker) {
            mDisplayResolutionTracker = displayResolutionTracker;
        }

        /** {@see FrameworkStatsLog#write) */
        public void write(int code, int displayId, @RefreshRate int refreshRate,
                int cuj, long totalFrames, long missedFrames, long maxFrameTime,
                long missedSfFrames, long missedAppFrames, long maxSuccessiveMissedFrames,
                long totalAnimationTime, float weightedJank, float sfWeightedJank,
                float appWeightedJank) {
            FrameworkStatsLog.write(code, cuj, totalFrames, missedFrames, maxFrameTime,
                    missedSfFrames, missedAppFrames, maxSuccessiveMissedFrames,
                    mDisplayResolutionTracker.getResolution(displayId), refreshRate,
                    totalAnimationTime, sfWeightedJank, appWeightedJank, weightedJank);
        }
    }

    /**
     * A listener that notifies cuj events.
     */
    public interface FrameTrackerListener {
        /**
         * Notify that the CUJ session was created.
         *
         * @param tracker the tracker
         * @param action the specific action
         * @param reason the reason for the action
         */
        void onCujEvents(FrameTracker tracker, String action, @Reasons int reason);

        /**
         * Notify that the Perfetto trace should be triggered.
         *
         * @param config the tracker configuration
         */
        void triggerPerfetto(Configuration config);
    }
}
