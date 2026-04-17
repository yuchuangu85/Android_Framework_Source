/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.app.jank;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.app.jank.StateTracker.StateData;
import android.util.Log;
import android.util.Pools.SimplePool;
import android.view.SurfaceControl.JankData;

import androidx.annotation.VisibleForTesting;

import com.android.internal.util.FrameworkStatsLog;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

/**
 * This class is responsible for associating frames received from SurfaceFlinger to active widget
 * states and logging those states back to the platform.
 *
 * @hide
 */
@FlaggedApi(Flags.FLAG_DETAILED_APP_JANK_METRICS_API)
public class JankDataProcessor {
    private static final String TAG = "JankDataProcessor";
    private static final boolean DEBUG_LOGGING = false;
    private static final int MAX_IN_MEMORY_STATS = 25;
    private static final int LOG_BATCH_FREQUENCY = 50;
    private int mCurrentBatchCount = 0;
    private StateTracker mStateTracker = null;
    private ArrayList<StateData> mPendingStates = new ArrayList<>();
    private SimplePool<PendingJankStat> mPendingJankStatsPool =
            new SimplePool<>(MAX_IN_MEMORY_STATS);
    private HashMap<String, PendingJankStat> mPendingJankStats = new HashMap<>();

    public JankDataProcessor(@NonNull StateTracker stateTracker) {
        mStateTracker = stateTracker;
    }

    /**
     * Called once per batch of JankData.
     *
     * @param jankData     data received from SurfaceFlinger to be processed
     * @param activityName name of the activity that is tracking jank metrics.
     * @param appUid       the uid of the app.
     */
    public void processJankData(List<JankData> jankData, String activityName, int appUid) {
        // add all the previous and active states to the pending states list.
        mStateTracker.retrieveAllStates(mPendingStates);

        // TODO b/376332122 Look to see if this logic can be optimized.
        for (int i = 0; i < jankData.size(); i++) {
            JankData frame = jankData.get(i);
            // for each frame we need to check if the state was active during that time.
            for (int j = 0; j < mPendingStates.size(); j++) {
                StateData pendingState = mPendingStates.get(j);
                // This state was active during the frame
                if (frame.getVsyncId() >= pendingState.mVsyncIdStart
                        && frame.getVsyncId() <= pendingState.mVsyncIdEnd) {
                    recordFrameCount(frame, pendingState, activityName, appUid);

                    pendingState.mProcessed = true;
                }
            }
        }
        // At this point we have attributed all frames to a state.
        incrementBatchCountAndMaybeLogStats();

        // return the StatData object back to the pool to be reused.
        jankDataProcessingComplete();
    }

    /**
     * Merges app jank stats reported by components outside the platform to the current pending
     * stats
     */
    public void mergeJankStats(AppJankStats jankStats, String activityName) {
        // Each state has a key which is a combination of widget category, widget id and widget
        // state, this key is also used to identify pending stats, a pending stat is essentially a
        // state with frames associated with it.
        String stateKey = mStateTracker.getStateKey(jankStats.getWidgetCategory(),
                jankStats.getWidgetId(), jankStats.getWidgetState());

        if (mPendingJankStats.containsKey(stateKey)) {
            mergeExistingStat(stateKey, jankStats);
        } else {
            mergeNewStat(stateKey, activityName, jankStats);
        }

        incrementBatchCountAndMaybeLogStats();
    }

    private void mergeExistingStat(String stateKey, AppJankStats jankStat) {
        PendingJankStat pendingStat = mPendingJankStats.get(stateKey);

        pendingStat.mJankyFrames += jankStat.getJankyFrameCount();
        pendingStat.mTotalFrames += jankStat.getTotalFrameCount();
        pendingStat.mergeOverrunHistogram(
                jankStat.getRelativeFrameTimeHistogram().getBucketCounters());
    }

    private void mergeNewStat(String stateKey, String activityName, AppJankStats jankStats) {
        // Check if we have space for a new stat
        if (mPendingJankStats.size() > MAX_IN_MEMORY_STATS) {
            return;
        }

        PendingJankStat pendingStat = mPendingJankStatsPool.acquire();
        if (pendingStat == null) {
            pendingStat = new PendingJankStat();

        }
        pendingStat.clearStats();

        pendingStat.mActivityName = activityName;
        pendingStat.mUid = jankStats.getUid();
        pendingStat.mWidgetId = jankStats.getWidgetId();
        pendingStat.mWidgetCategory = jankStats.getWidgetCategory();
        pendingStat.mWidgetState = jankStats.getWidgetState();
        pendingStat.mTotalFrames = jankStats.getTotalFrameCount();
        pendingStat.mJankyFrames = jankStats.getJankyFrameCount();
        pendingStat.mergeOverrunHistogram(
                jankStats.getRelativeFrameTimeHistogram().getBucketCounters());

        mPendingJankStats.put(stateKey, pendingStat);
    }

    private void incrementBatchCountAndMaybeLogStats() {
        mCurrentBatchCount++;
        if (mCurrentBatchCount >= LOG_BATCH_FREQUENCY) {
            logMetricCounts();
        }
    }

    /**
     * Returns the aggregate map of different pending jank stats.
     */
    @VisibleForTesting
    public HashMap<String, PendingJankStat> getPendingJankStats() {
        return mPendingJankStats;
    }

    private void jankDataProcessingComplete() {
        mStateTracker.stateProcessingComplete();
        mPendingStates.clear();
    }

    /**
     * Determine if frame is Janky and add to existing memory counter or create a new one.
     */
    private void recordFrameCount(JankData frameData, StateData stateData, String activityName,
            int appUid) {
        // Check if we have an existing Jank state
        PendingJankStat jankStats = mPendingJankStats.get(stateData.mStateDataKey);

        if (jankStats == null) {
            // Check if we have space for another pending stat
            if (mPendingJankStats.size() > MAX_IN_MEMORY_STATS) {
                return;
            }

            jankStats = mPendingJankStatsPool.acquire();
            if (jankStats == null) {
                jankStats = new PendingJankStat();
            }
            jankStats.clearStats();
            jankStats.mActivityName = activityName;
            jankStats.mUid = appUid;
            if (Flags.retainWidgetParameters()) {
                jankStats.mWidgetCategory = stateData.mWidgetCategory;
                jankStats.mWidgetId = stateData.mWidgetId;
                jankStats.mWidgetState = stateData.mWidgetState;
            }
            mPendingJankStats.put(stateData.mStateDataKey, jankStats);
        }
        // This state has already been accounted for
        if (jankStats.processedVsyncId == frameData.getVsyncId()) return;

        jankStats.mTotalFrames += 1;
        if ((frameData.getJankType() & JankData.JANK_APPLICATION) != 0) {
            jankStats.mJankyFrames += 1;
        }
        jankStats.recordFrameOverrun(frameData.getActualAppFrameTimeNanos());
        jankStats.processedVsyncId = frameData.getVsyncId();

    }

    /**
     * When called will log pending Jank stats currently stored in memory to the platform. Will not
     * clear any pending widget states.
     */
    public void logMetricCounts() {
        try {
            mPendingJankStats.values().forEach(stat -> {
                        FrameworkStatsLog.write(
                                FrameworkStatsLog.JANK_FRAME_COUNT_BY_WIDGET_REPORTED,
                                /*app uid*/ stat.getUid(),
                                /*activity name*/ stat.getActivityName(),
                                /*widget id*/ stat.getWidgetId(),
                                /*refresh rate*/ stat.getRefreshRate(),
                                /*widget category*/ widgetCategoryToInt(stat.getWidgetCategory()),
                                /*widget state*/ widgetStateToInt(stat.getWidgetState()),
                                /*total frames*/ stat.getTotalFrames(),
                                /*janky frames*/ stat.getJankyFrames(),
                                /*histogram*/ stat.getFrameOverrunBuckets());
                        // return the pending stat to the pool it will be reset the next time its
                        // used.
                        mPendingJankStatsPool.release(stat);

                    }
            );
            // All stats have been recorded and added back to the pool for reuse, clear the pending
            // stats.
            mPendingJankStats.clear();
            mCurrentBatchCount = 0;
        } catch (Exception exception) {
            // TODO b/374608358 handle logging exceptions.
        }
    }

    private int widgetCategoryToInt(String widgetCategory) {
        switch (widgetCategory) {
            case AppJankStats.WIDGET_CATEGORY_SCROLL -> {
                return FrameworkStatsLog
                        .JANK_FRAME_COUNT_BY_WIDGET_REPORTED__WIDGET_TYPE__SCROLL;
            }
            case AppJankStats.WIDGET_CATEGORY_ANIMATION -> {
                return FrameworkStatsLog
                        .JANK_FRAME_COUNT_BY_WIDGET_REPORTED__WIDGET_TYPE__ANIMATION;
            }
            case AppJankStats.WIDGET_CATEGORY_MEDIA -> {
                return FrameworkStatsLog
                        .JANK_FRAME_COUNT_BY_WIDGET_REPORTED__WIDGET_TYPE__MEDIA;
            }
            case AppJankStats.WIDGET_CATEGORY_NAVIGATION -> {
                return FrameworkStatsLog
                        .JANK_FRAME_COUNT_BY_WIDGET_REPORTED__WIDGET_TYPE__NAVIGATION;
            }
            case AppJankStats.WIDGET_CATEGORY_KEYBOARD -> {
                return FrameworkStatsLog
                        .JANK_FRAME_COUNT_BY_WIDGET_REPORTED__WIDGET_TYPE__KEYBOARD;
            }
            case AppJankStats.WIDGET_CATEGORY_OTHER -> {
                return FrameworkStatsLog
                        .JANK_FRAME_COUNT_BY_WIDGET_REPORTED__WIDGET_TYPE__OTHER;
            }
            default -> {
                if (DEBUG_LOGGING) {
                    Log.d(TAG, "Default Category Logged: "
                            + AppJankStats.WIDGET_CATEGORY_UNSPECIFIED);
                }
                return FrameworkStatsLog
                        .JANK_FRAME_COUNT_BY_WIDGET_REPORTED__WIDGET_TYPE__WIDGET_CATEGORY_UNSPECIFIED;
            }
        }
    }

    private int widgetStateToInt(String widgetState) {
        switch (widgetState) {
            case AppJankStats.WIDGET_STATE_NONE -> {
                return FrameworkStatsLog
                        .JANK_FRAME_COUNT_BY_WIDGET_REPORTED__WIDGET_STATE__NONE;
            }
            case AppJankStats.WIDGET_STATE_SCROLLING -> {
                return FrameworkStatsLog
                        .JANK_FRAME_COUNT_BY_WIDGET_REPORTED__WIDGET_STATE__SCROLLING;
            }
            case AppJankStats.WIDGET_STATE_FLINGING -> {
                return FrameworkStatsLog
                        .JANK_FRAME_COUNT_BY_WIDGET_REPORTED__WIDGET_STATE__FLINGING;
            }
            case AppJankStats.WIDGET_STATE_SWIPING -> {
                return FrameworkStatsLog
                        .JANK_FRAME_COUNT_BY_WIDGET_REPORTED__WIDGET_STATE__SWIPING;
            }
            case AppJankStats.WIDGET_STATE_DRAGGING -> {
                return FrameworkStatsLog
                        .JANK_FRAME_COUNT_BY_WIDGET_REPORTED__WIDGET_STATE__DRAGGING;
            }
            case AppJankStats.WIDGET_STATE_ZOOMING -> {
                return FrameworkStatsLog
                        .JANK_FRAME_COUNT_BY_WIDGET_REPORTED__WIDGET_STATE__ZOOMING;
            }
            case AppJankStats.WIDGET_STATE_ANIMATING -> {
                return FrameworkStatsLog
                        .JANK_FRAME_COUNT_BY_WIDGET_REPORTED__WIDGET_STATE__ANIMATING;
            }
            case AppJankStats.WIDGET_STATE_PLAYBACK -> {
                return FrameworkStatsLog
                        .JANK_FRAME_COUNT_BY_WIDGET_REPORTED__WIDGET_STATE__PLAYBACK;
            }
            case AppJankStats.WIDGET_STATE_TAPPING -> {
                return FrameworkStatsLog
                        .JANK_FRAME_COUNT_BY_WIDGET_REPORTED__WIDGET_STATE__TAPPING;
            }
            case AppJankStats.WIDGET_STATE_PREDICTIVE_BACK -> {
                return FrameworkStatsLog
                        .JANK_FRAME_COUNT_BY_WIDGET_REPORTED__WIDGET_STATE__PREDICTIVE_BACK;
            }
            default -> {
                if (DEBUG_LOGGING) {
                    Log.d(TAG, "Default State Logged: "
                            + AppJankStats.WIDGET_STATE_UNSPECIFIED);
                }
                return FrameworkStatsLog
                        .JANK_FRAME_COUNT_BY_WIDGET_REPORTED__WIDGET_STATE__WIDGET_STATE_UNSPECIFIED;
            }
        }
    }

    public static final class PendingJankStat {
        private static final int NANOS_PER_MS = 1000000;
        private static final int HISTOGRAM_BUCKET_COUNT =
                RelativeFrameTimeHistogram.getHistogramBucketCount();
        public long processedVsyncId = -1;

        // UID of the app
        private int mUid;

        // The name of the activity that is currently collecting frame metrics.
        private String mActivityName;

        // The id that has been set for the widget.
        private String mWidgetId;

        // A general category that the widget applies to.
        private String mWidgetCategory;

        // The states that the UI elements can report
        private String mWidgetState;

        // The number of frames reported during this state.
        private long mTotalFrames;

        // Total number of frames determined to be janky during the reported state.
        private long mJankyFrames;

        private int mRefreshRate;

        private final int[] mFrameOverrunBuckets = new int[HISTOGRAM_BUCKET_COUNT];

        // Histogram of frame duration overruns encoded in predetermined buckets.
        public PendingJankStat() {
        }

        public long getProcessedVsyncId() {
            return processedVsyncId;
        }

        public void setProcessedVsyncId(long processedVsyncId) {
            this.processedVsyncId = processedVsyncId;
        }

        public int getUid() {
            return mUid;
        }

        public void setUid(int uid) {
            mUid = uid;
        }

        public String getActivityName() {
            return mActivityName;
        }

        public void setActivityName(String activityName) {
            mActivityName = activityName;
        }

        public String getWidgetId() {
            return mWidgetId;
        }

        public void setWidgetId(String widgetId) {
            mWidgetId = widgetId;
        }

        public String getWidgetCategory() {
            return mWidgetCategory;
        }

        public void setWidgetCategory(String widgetCategory) {
            mWidgetCategory = widgetCategory;
        }

        public String getWidgetState() {
            return mWidgetState;
        }

        public void setWidgetState(String widgetState) {
            mWidgetState = widgetState;
        }

        public long getTotalFrames() {
            return mTotalFrames;
        }

        public void setTotalFrames(long totalFrames) {
            mTotalFrames = totalFrames;
        }

        public long getJankyFrames() {
            return mJankyFrames;
        }

        public void setJankyFrames(long jankyFrames) {
            mJankyFrames = jankyFrames;
        }

        public int[] getFrameOverrunBuckets() {
            return mFrameOverrunBuckets;
        }

        public int getRefreshRate() {
            return mRefreshRate;
        }

        public void setRefreshRate(int refreshRate) {
            mRefreshRate = refreshRate;
        }

        /**
         * Will convert the frame time from ns to ms and record how long the frame took to render.
         */
        public void recordFrameOverrun(long frameTimeNano) {
            try {
                int frameTimeMillis = (int) frameTimeNano / NANOS_PER_MS;
                int indexForFrameOverrun = RelativeFrameTimeHistogram
                        .getRelativeFrameTimeBucketIndex(frameTimeMillis);
                mFrameOverrunBuckets[indexForFrameOverrun]++;
            } catch (IndexOutOfBoundsException exception) {
                // Drop the frame if it's out of bounds.
            }
        }

        /**
         * Merges the histogram that is passed in with the current histogram that is
         * stored in the object.
         */
        public void mergeOverrunHistogram(int[] histogram) {
            if (histogram.length != HISTOGRAM_BUCKET_COUNT) return;

            for (int i = 0; i < histogram.length; i++) {
                mFrameOverrunBuckets[i] += histogram[i];
            }
        }

        /**
         * resets all fields in the object back to defaults.
         */
        public void clearStats() {
            this.mUid = -1;
            this.mActivityName = "";
            this.processedVsyncId = -1;
            this.mJankyFrames = 0;
            this.mTotalFrames = 0;
            this.mWidgetCategory = "";
            this.mWidgetState = "";
            this.mRefreshRate = 0;
            clearHistogram();
        }

        private void clearHistogram() {
            Arrays.fill(mFrameOverrunBuckets, 0);
        }
    }
}