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

import static android.view.SurfaceControl.JankData.DISPLAY_HAL;
import static android.view.SurfaceControl.JankData.JANK_APP_DEADLINE_MISSED;
import static android.view.SurfaceControl.JankData.JANK_NONE;
import static android.view.SurfaceControl.JankData.JANK_SURFACEFLINGER_DEADLINE_MISSED;
import static android.view.SurfaceControl.JankData.JANK_SURFACEFLINGER_GPU_DEADLINE_MISSED;
import static android.view.SurfaceControl.JankData.PREDICTION_ERROR;
import static android.view.SurfaceControl.JankData.SURFACE_FLINGER_SCHEDULING;

import static com.android.internal.jank.InteractionJankMonitor.ACTION_SESSION_CANCEL;
import static com.android.internal.jank.InteractionJankMonitor.ACTION_SESSION_END;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.graphics.HardwareRendererObserver;
import android.os.Handler;
import android.os.Trace;
import android.text.TextUtils;
import android.util.Log;
import android.util.SparseArray;
import android.view.Choreographer;
import android.view.FrameMetrics;
import android.view.SurfaceControl;
import android.view.SurfaceControl.JankData.JankType;
import android.view.ThreadedRenderer;
import android.view.ViewRootImpl;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.jank.InteractionJankMonitor.Configuration;
import com.android.internal.jank.InteractionJankMonitor.Session;
import com.android.internal.util.FrameworkStatsLog;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.concurrent.TimeUnit;

/**
 * A class that allows the app to get the frame metrics from HardwareRendererObserver.
 * @hide
 */
public class FrameTracker extends SurfaceControl.OnJankDataListener
        implements HardwareRendererObserver.OnFrameMetricsAvailableListener {
    private static final String TAG = "FrameTracker";
    private static final boolean DEBUG = false;

    private static final long INVALID_ID = -1;
    public static final int NANOS_IN_MILLISECOND = 1_000_000;

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
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Reasons {
    }

    private final HardwareRendererObserver mObserver;
    private SurfaceControl mSurfaceControl;
    private final int mTraceThresholdMissedFrames;
    private final int mTraceThresholdFrameTimeMillis;
    private final ThreadedRendererWrapper mRendererWrapper;
    private final FrameMetricsWrapper mMetricsWrapper;
    private final SparseArray<JankInfo> mJankInfos = new SparseArray<>();
    private final Session mSession;
    private final ViewRootWrapper mViewRoot;
    private final SurfaceControlWrapper mSurfaceControlWrapper;
    private final ViewRootImpl.SurfaceChangedCallback mSurfaceChangedCallback;
    private final Handler mHandler;
    private final ChoreographerWrapper mChoreographer;
    private final StatsLogWrapper mStatsLog;
    private final Object mLock = InteractionJankMonitor.getInstance().getLock();
    private final boolean mDeferMonitoring;

    @VisibleForTesting
    public final boolean mSurfaceOnly;

    private long mBeginVsyncId = INVALID_ID;
    private long mEndVsyncId = INVALID_ID;
    private boolean mMetricsFinalized;
    private boolean mCancelled = false;
    private FrameTrackerListener mListener;
    private boolean mTracingStarted = false;
    private Runnable mWaitForFinishTimedOut;

    private static class JankInfo {
        long frameVsyncId;
        long totalDurationNanos;
        boolean isFirstFrame;
        boolean hwuiCallbackFired;
        boolean surfaceControlCallbackFired;
        @JankType int jankType;

        static JankInfo createFromHwuiCallback(long frameVsyncId, long totalDurationNanos,
                boolean isFirstFrame) {
            return new JankInfo(frameVsyncId, true, false, JANK_NONE, totalDurationNanos,
                    isFirstFrame);
        }

        static JankInfo createFromSurfaceControlCallback(long frameVsyncId,
                @JankType int jankType) {
            return new JankInfo(frameVsyncId, false, true, jankType, 0, false /* isFirstFrame */);
        }

        private JankInfo(long frameVsyncId, boolean hwuiCallbackFired,
                boolean surfaceControlCallbackFired, @JankType int jankType,
                long totalDurationNanos, boolean isFirstFrame) {
            this.frameVsyncId = frameVsyncId;
            this.hwuiCallbackFired = hwuiCallbackFired;
            this.surfaceControlCallbackFired = surfaceControlCallbackFired;
            this.totalDurationNanos = totalDurationNanos;
            this.jankType = jankType;
            this.isFirstFrame = isFirstFrame;
        }
    }

    public FrameTracker(@NonNull Session session, @NonNull Handler handler,
            @Nullable ThreadedRendererWrapper renderer, @Nullable ViewRootWrapper viewRootWrapper,
            @NonNull SurfaceControlWrapper surfaceControlWrapper,
            @NonNull ChoreographerWrapper choreographer,
            @Nullable FrameMetricsWrapper metrics,
            @NonNull StatsLogWrapper statsLog,
            int traceThresholdMissedFrames, int traceThresholdFrameTimeMillis,
            @Nullable FrameTrackerListener listener, @NonNull Configuration config) {
        mSurfaceOnly = config.isSurfaceOnly();
        mSession = session;
        mHandler = handler;
        mChoreographer = choreographer;
        mSurfaceControlWrapper = surfaceControlWrapper;
        mStatsLog = statsLog;
        mDeferMonitoring = config.shouldDeferMonitor();

        // HWUI instrumentation init.
        mRendererWrapper = mSurfaceOnly ? null : renderer;
        mMetricsWrapper = mSurfaceOnly ? null : metrics;
        mViewRoot = mSurfaceOnly ? null : viewRootWrapper;
        mObserver = mSurfaceOnly
                ? null
                : new HardwareRendererObserver(this, mMetricsWrapper.getTiming(),
                        handler, /* waitForPresentTime= */ false);

        mTraceThresholdMissedFrames = traceThresholdMissedFrames;
        mTraceThresholdFrameTimeMillis = traceThresholdFrameTimeMillis;
        mListener = listener;

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
                    synchronized (mLock) {
                        if (mSurfaceControl == null) {
                            mSurfaceControl = mViewRoot.getSurfaceControl();
                            if (mBeginVsyncId != INVALID_ID) {
                                mSurfaceControlWrapper.addJankStatsListener(
                                        FrameTracker.this, mSurfaceControl);
                                markEvent("FT#deferMonitoring");
                                postTraceStartMarker();
                            }
                        }
                    }
                }

                @Override
                public void surfaceReplaced(SurfaceControl.Transaction t) {
                }

                @Override
                public void surfaceDestroyed() {

                    // Wait a while to give the system a chance for the remaining
                    // frames to arrive, then force finish the session.
                    mHandler.postDelayed(() -> {
                        synchronized (mLock) {
                            if (DEBUG) {
                                Log.d(TAG, "surfaceDestroyed: " + mSession.getName()
                                        + ", finalized=" + mMetricsFinalized
                                        + ", info=" + mJankInfos.size()
                                        + ", vsync=" + mBeginVsyncId);
                            }
                            if (!mMetricsFinalized) {
                                end(REASON_END_SURFACE_DESTROYED);
                                finish();
                            }
                        }
                    }, 50);
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
    public void begin() {
        synchronized (mLock) {
            final long currentVsync = mChoreographer.getVsyncId();
            // In normal case, we should begin at the next frame,
            // the id of the next frame is not simply increased by 1,
            // but we can exclude the current frame at least.
            mBeginVsyncId = mDeferMonitoring ? currentVsync + 1 : currentVsync;
            if (DEBUG) {
                Log.d(TAG, "begin: " + mSession.getName() + ", begin=" + mBeginVsyncId
                        + ", defer=" + mDeferMonitoring);
            }
            if (mSurfaceControl != null) {
                if (mDeferMonitoring) {
                    markEvent("FT#deferMonitoring");
                    // Normal case, we begin the instrument from the very beginning,
                    // will exclude the first frame.
                    postTraceStartMarker();
                } else {
                    // If we don't begin the instrument from the very beginning,
                    // there is no need to skip the frame where the begin invocation happens.
                    beginInternal();
                }
                mSurfaceControlWrapper.addJankStatsListener(this, mSurfaceControl);
            }
            if (!mSurfaceOnly) {
                mRendererWrapper.addObserver(mObserver);
            }
        }
    }

    /**
     * Start trace section at appropriate time.
     */
    @VisibleForTesting
    public void postTraceStartMarker() {
        mChoreographer.mChoreographer.postCallback(
                Choreographer.CALLBACK_INPUT, this::beginInternal, null);
    }

    private void beginInternal() {
        synchronized (mLock) {
            if (mCancelled || mEndVsyncId != INVALID_ID) {
                return;
            }
            mTracingStarted = true;
            markEvent("FT#begin");
            Trace.beginAsyncSection(mSession.getName(), (int) mBeginVsyncId);
        }
    }

    /**
     * End the trace session of the CUJ.
     */
    public boolean end(@Reasons int reason) {
        synchronized (mLock) {
            if (mCancelled || mEndVsyncId != INVALID_ID) return false;
            mEndVsyncId = mChoreographer.getVsyncId();
            // Cancel the session if:
            // 1. The session begins and ends at the same vsync id.
            // 2. The session never begun.
            if (mBeginVsyncId == INVALID_ID) {
                return cancel(REASON_CANCEL_NOT_BEGUN);
            } else if (mEndVsyncId <= mBeginVsyncId) {
                return cancel(REASON_CANCEL_SAME_VSYNC);
            } else {
                if (DEBUG) {
                    Log.d(TAG, "end: " + mSession.getName()
                            + ", end=" + mEndVsyncId + ", reason=" + reason);
                }
                markEvent("FT#end#" + reason);
                Trace.endAsyncSection(mSession.getName(), (int) mBeginVsyncId);
                mSession.setReason(reason);

                // We don't remove observer here,
                // will remove it when all the frame metrics in this duration are called back.
                // See onFrameMetricsAvailable for the logic of removing the observer.
                // Waiting at most 10 seconds for all callbacks to finish.
                mWaitForFinishTimedOut = () -> {
                    Log.e(TAG, "force finish cuj because of time out:" + mSession.getName());
                    finish();
                };
                mHandler.postDelayed(mWaitForFinishTimedOut, TimeUnit.SECONDS.toMillis(10));
                notifyCujEvent(ACTION_SESSION_END);
                return true;
            }
        }
    }

    /**
     * Cancel the trace session of the CUJ.
     */
    public boolean cancel(@Reasons int reason) {
        synchronized (mLock) {
            final boolean cancelFromEnd =
                    reason == REASON_CANCEL_NOT_BEGUN || reason == REASON_CANCEL_SAME_VSYNC;
            if (mCancelled || (mEndVsyncId != INVALID_ID && !cancelFromEnd)) return false;
            mCancelled = true;
            markEvent("FT#cancel#" + reason);
            // We don't need to end the trace section if it never begun.
            if (mTracingStarted) {
                Trace.endAsyncSection(mSession.getName(), (int) mBeginVsyncId);
            }

            // Always remove the observers in cancel call to avoid leakage.
            removeObservers();

            if (DEBUG) {
                Log.d(TAG, "cancel: " + mSession.getName() + ", begin=" + mBeginVsyncId
                        + ", end=" + mEndVsyncId + ", reason=" + reason);
            }

            mSession.setReason(reason);
            // Notify the listener the session has been cancelled.
            // We don't notify the listeners if the session never begun.
            notifyCujEvent(ACTION_SESSION_CANCEL);
            return true;
        }
    }

    private void markEvent(String desc) {
        Trace.beginSection(TextUtils.formatSimple("%s#%s", mSession.getName(), desc));
        Trace.endSection();
    }

    private void notifyCujEvent(String action) {
        if (mListener == null) return;
        mListener.onCujEvents(mSession, action);
    }

    @Override
    public void onJankDataAvailable(SurfaceControl.JankData[] jankData) {
        synchronized (mLock) {
            if (mCancelled) {
                return;
            }

            for (SurfaceControl.JankData jankStat : jankData) {
                if (!isInRange(jankStat.frameVsyncId)) {
                    continue;
                }
                JankInfo info = findJankInfo(jankStat.frameVsyncId);
                if (info != null) {
                    info.surfaceControlCallbackFired = true;
                    info.jankType = jankStat.jankType;
                } else {
                    mJankInfos.put((int) jankStat.frameVsyncId,
                            JankInfo.createFromSurfaceControlCallback(
                                    jankStat.frameVsyncId, jankStat.jankType));
                }
            }
            processJankInfos();
        }
    }

    private @Nullable JankInfo findJankInfo(long frameVsyncId) {
        return mJankInfos.get((int) frameVsyncId);
    }

    private boolean isInRange(long vsyncId) {
        // It's possible that we may miss a callback for the frame with vsyncId == mEndVsyncId.
        // Because of that, we collect all frames even if they happen after the end so we eventually
        // have a frame after the end with both callbacks present.
        return vsyncId >= mBeginVsyncId;
    }

    @Override
    public void onFrameMetricsAvailable(int dropCountSinceLastInvocation) {
        synchronized (mLock) {
            if (mCancelled) {
                return;
            }

            // Since this callback might come a little bit late after the end() call.
            // We should keep tracking the begin / end timestamp that we can compare with
            // vsync timestamp to check if the frame is in the duration of the CUJ.
            long totalDurationNanos = mMetricsWrapper.getMetric(FrameMetrics.TOTAL_DURATION);
            boolean isFirstFrame = mMetricsWrapper.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 1;
            long frameVsyncId =
                    mMetricsWrapper.getTiming()[FrameMetrics.Index.FRAME_TIMELINE_VSYNC_ID];

            if (!isInRange(frameVsyncId)) {
                return;
            }
            JankInfo info = findJankInfo(frameVsyncId);
            if (info != null) {
                info.hwuiCallbackFired = true;
                info.totalDurationNanos = totalDurationNanos;
                info.isFirstFrame = isFirstFrame;
            } else {
                mJankInfos.put((int) frameVsyncId, JankInfo.createFromHwuiCallback(
                        frameVsyncId, totalDurationNanos, isFirstFrame));
            }
            processJankInfos();
        }
    }

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
        for (int i = mJankInfos.size() - 1; i >= 0; i--) {
            JankInfo info = mJankInfos.valueAt(i);
            if (info.frameVsyncId >= mEndVsyncId) {
                if (callbacksReceived(info)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void processJankInfos() {
        if (mMetricsFinalized) {
            return;
        }
        if (!hasReceivedCallbacksAfterEnd()) {
            return;
        }
        finish();
    }

    private boolean callbacksReceived(JankInfo info) {
        return mSurfaceOnly
                ? info.surfaceControlCallbackFired
                : info.hwuiCallbackFired && info.surfaceControlCallbackFired;
    }

    private void finish() {
        mHandler.removeCallbacks(mWaitForFinishTimedOut);
        mWaitForFinishTimedOut = null;
        mMetricsFinalized = true;

        // The tracing has been ended, remove the observer, see if need to trigger perfetto.
        removeObservers();

        int totalFramesCount = 0;
        long maxFrameTimeNanos = 0;
        int missedFramesCount = 0;
        int missedAppFramesCount = 0;
        int missedSfFramesCount = 0;
        int maxSuccessiveMissedFramesCount = 0;
        int successiveMissedFramesCount = 0;

        for (int i = 0; i < mJankInfos.size(); i++) {
            JankInfo info = mJankInfos.valueAt(i);
            final boolean isFirstDrawn = !mSurfaceOnly && info.isFirstFrame;
            if (isFirstDrawn) {
                continue;
            }
            if (info.frameVsyncId > mEndVsyncId) {
                break;
            }
            if (info.surfaceControlCallbackFired) {
                totalFramesCount++;
                boolean missedFrame = false;
                if ((info.jankType & JANK_APP_DEADLINE_MISSED) != 0) {
                    Log.w(TAG, "Missed App frame:" + info.jankType);
                    missedAppFramesCount++;
                    missedFrame = true;
                }
                if ((info.jankType & DISPLAY_HAL) != 0
                        || (info.jankType & JANK_SURFACEFLINGER_DEADLINE_MISSED) != 0
                        || (info.jankType & JANK_SURFACEFLINGER_GPU_DEADLINE_MISSED) != 0
                        || (info.jankType & SURFACE_FLINGER_SCHEDULING) != 0
                        || (info.jankType & PREDICTION_ERROR) != 0) {
                    Log.w(TAG, "Missed SF frame:" + info.jankType);
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
                // TODO (b/174755489): Early latch currently gets fired way too often, so we have
                // to ignore it for now.
                if (!mSurfaceOnly && !info.hwuiCallbackFired) {
                    Log.w(TAG, "Missing HWUI jank callback for vsyncId: " + info.frameVsyncId);
                }
            }
            if (!mSurfaceOnly && info.hwuiCallbackFired) {
                maxFrameTimeNanos = Math.max(info.totalDurationNanos, maxFrameTimeNanos);
                if (!info.surfaceControlCallbackFired) {
                    Log.w(TAG, "Missing SF jank callback for vsyncId: " + info.frameVsyncId);
                }
            }
        }
        maxSuccessiveMissedFramesCount = Math.max(
                maxSuccessiveMissedFramesCount, successiveMissedFramesCount);

        // Log the frame stats as counters to make them easily accessible in traces.
        Trace.traceCounter(Trace.TRACE_TAG_APP, mSession.getName() + "#missedFrames",
                missedFramesCount);
        Trace.traceCounter(Trace.TRACE_TAG_APP, mSession.getName() + "#missedAppFrames",
                missedAppFramesCount);
        Trace.traceCounter(Trace.TRACE_TAG_APP, mSession.getName() + "#missedSfFrames",
                missedSfFramesCount);
        Trace.traceCounter(Trace.TRACE_TAG_APP, mSession.getName() + "#totalFrames",
                totalFramesCount);
        Trace.traceCounter(Trace.TRACE_TAG_APP, mSession.getName() + "#maxFrameTimeMillis",
                (int) (maxFrameTimeNanos / NANOS_IN_MILLISECOND));
        Trace.traceCounter(Trace.TRACE_TAG_APP, mSession.getName() + "#maxSuccessiveMissedFrames",
                maxSuccessiveMissedFramesCount);

        // Trigger perfetto if necessary.
        if (shouldTriggerPerfetto(missedFramesCount, (int) maxFrameTimeNanos)) {
            triggerPerfetto();
        }
        if (mSession.logToStatsd()) {
            mStatsLog.write(
                    FrameworkStatsLog.UI_INTERACTION_FRAME_INFO_REPORTED,
                    mSession.getStatsdInteractionType(),
                    totalFramesCount,
                    missedFramesCount,
                    maxFrameTimeNanos, /* will be 0 if mSurfaceOnly == true */
                    missedSfFramesCount,
                    missedAppFramesCount,
                    maxSuccessiveMissedFramesCount);
        }
        if (DEBUG) {
            Log.i(TAG, "finish: CUJ=" + mSession.getName()
                    + " (" + mBeginVsyncId + "," + mEndVsyncId + ")"
                    + " totalFrames=" + totalFramesCount
                    + " missedAppFrames=" + missedAppFramesCount
                    + " missedSfFrames=" + missedSfFramesCount
                    + " missedFrames=" + missedFramesCount
                    + " maxFrameTimeMillis=" + maxFrameTimeNanos / NANOS_IN_MILLISECOND
                    + " maxSuccessiveMissedFramesCount=" + maxSuccessiveMissedFramesCount);
        }
    }

    private boolean shouldTriggerPerfetto(int missedFramesCount, int maxFrameTimeNanos) {
        boolean overMissedFramesThreshold = mTraceThresholdMissedFrames != -1
                && missedFramesCount >= mTraceThresholdMissedFrames;
        boolean overFrameTimeThreshold = !mSurfaceOnly && mTraceThresholdFrameTimeMillis != -1
                && maxFrameTimeNanos >= mTraceThresholdFrameTimeMillis * NANOS_IN_MILLISECOND;
        return overMissedFramesThreshold || overFrameTimeThreshold;
    }

    /**
     * Remove all the registered listeners, observers and callbacks.
     */
    @VisibleForTesting
    public void removeObservers() {
        mSurfaceControlWrapper.removeJankStatsListener(this);
        if (!mSurfaceOnly) {
            // HWUI part.
            mRendererWrapper.removeObserver(mObserver);
            if (mSurfaceChangedCallback != null) {
                mViewRoot.removeSurfaceChangedCallback(mSurfaceChangedCallback);
            }
        }
    }

    /**
     * Trigger the prefetto daemon.
     */
    public void triggerPerfetto() {
        InteractionJankMonitor.getInstance().trigger(mSession);
    }

    /**
     * A wrapper class that we can spy FrameMetrics (a final class) in unit tests.
     */
    public static class FrameMetricsWrapper {
        private final FrameMetrics mFrameMetrics;

        public FrameMetricsWrapper() {
            mFrameMetrics = new FrameMetrics();
        }

        /**
         * Wrapper method.
         * @return timing data of the metrics
         */
        public long[] getTiming() {
            return mFrameMetrics.mTimingData;
        }

        /**
         * Wrapper method.
         * @param index specific index of the timing data
         * @return the timing data of the specified index
         */
        public long getMetric(int index) {
            return mFrameMetrics.getMetric(index);
        }
    }

    /**
     * A wrapper class that we can spy ThreadedRenderer (a final class) in unit tests.
     */
    public static class ThreadedRendererWrapper {
        private final ThreadedRenderer mRenderer;

        public ThreadedRendererWrapper(ThreadedRenderer renderer) {
            mRenderer = renderer;
        }

        /**
         * Wrapper method.
         * @param observer observer
         */
        public void addObserver(HardwareRendererObserver observer) {
            mRenderer.addObserver(observer);
        }

        /**
         * Wrapper method.
         * @param observer observer
         */
        public void removeObserver(HardwareRendererObserver observer) {
            mRenderer.removeObserver(observer);
        }
    }

    public static class ViewRootWrapper {
        private final ViewRootImpl mViewRoot;

        public ViewRootWrapper(ViewRootImpl viewRoot) {
            mViewRoot = viewRoot;
        }

        public void addSurfaceChangedCallback(ViewRootImpl.SurfaceChangedCallback callback) {
            mViewRoot.addSurfaceChangedCallback(callback);
        }

        public void removeSurfaceChangedCallback(ViewRootImpl.SurfaceChangedCallback callback) {
            mViewRoot.removeSurfaceChangedCallback(callback);
        }

        public SurfaceControl getSurfaceControl() {
            return mViewRoot.getSurfaceControl();
        }
    }

    public static class SurfaceControlWrapper {

        public void addJankStatsListener(SurfaceControl.OnJankDataListener listener,
                SurfaceControl surfaceControl) {
            SurfaceControl.addJankDataListener(listener, surfaceControl);
        }

        public void removeJankStatsListener(SurfaceControl.OnJankDataListener listener) {
            SurfaceControl.removeJankDataListener(listener);
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
        public void write(int code,
                int arg1, long arg2, long arg3, long arg4, long arg5, long arg6, long arg7) {
            FrameworkStatsLog.write(code, arg1, arg2, arg3, arg4, arg5, arg6, arg7);
        }
    }

    /**
     * A listener that notifies cuj events.
     */
    public interface FrameTrackerListener {
        /**
         * Notify that the CUJ session was created.
         *
         * @param session the CUJ session
         * @param action the specific action
         */
        void onCujEvents(Session session, String action);
    }
}
