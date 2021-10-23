package com.android.server.wm;

import static android.app.ActivityManager.START_SUCCESS;
import static android.app.ActivityManager.START_TASK_TO_FRONT;
import static android.app.ActivityManager.processStateAmToProto;
import static android.app.WaitResult.INVALID_DELAY;
import static android.app.WaitResult.LAUNCH_STATE_COLD;
import static android.app.WaitResult.LAUNCH_STATE_HOT;
import static android.app.WaitResult.LAUNCH_STATE_RELAUNCH;
import static android.app.WaitResult.LAUNCH_STATE_WARM;
import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW;
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;
import static android.app.WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_PRIMARY;
import static android.app.WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_SECONDARY;
import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;

import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.ACTION_ACTIVITY_START;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.APP_TRANSITION;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.APP_TRANSITION_BIND_APPLICATION_DELAY_MS;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.APP_TRANSITION_CALLING_PACKAGE_NAME;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.APP_TRANSITION_CANCELLED;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.APP_TRANSITION_DELAY_MS;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.APP_TRANSITION_DEVICE_UPTIME_SECONDS;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.APP_TRANSITION_IS_EPHEMERAL;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.APP_TRANSITION_PROCESS_RUNNING;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.APP_TRANSITION_REPORTED_DRAWN;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.APP_TRANSITION_REPORTED_DRAWN_MS;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.APP_TRANSITION_STARTING_WINDOW_DELAY_MS;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.APP_TRANSITION_WINDOWS_DRAWN_DELAY_MS;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.FIELD_CALLING_PACKAGE_NAME;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.FIELD_CALLING_UID;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.FIELD_CALLING_UID_HAS_ANY_VISIBLE_WINDOW;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.FIELD_CALLING_UID_PROC_STATE;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.FIELD_CLASS_NAME;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.FIELD_COMING_FROM_PENDING_INTENT;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.FIELD_INSTANT_APP_LAUNCH_TOKEN;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.FIELD_INTENT_ACTION;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.FIELD_PROCESS_RECORD_CUR_PROC_STATE;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.FIELD_PROCESS_RECORD_HAS_CLIENT_ACTIVITIES;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.FIELD_PROCESS_RECORD_HAS_FOREGROUND_ACTIVITIES;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.FIELD_PROCESS_RECORD_HAS_FOREGROUND_SERVICES;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.FIELD_PROCESS_RECORD_HAS_OVERLAY_UI;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.FIELD_PROCESS_RECORD_HAS_TOP_UI;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.FIELD_PROCESS_RECORD_MILLIS_SINCE_FG_INTERACTION;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.FIELD_PROCESS_RECORD_MILLIS_SINCE_LAST_INTERACTION_EVENT;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.FIELD_PROCESS_RECORD_MILLIS_SINCE_UNIMPORTANT;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.FIELD_PROCESS_RECORD_PENDING_UI_CLEAN;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.FIELD_PROCESS_RECORD_PROCESS_NAME;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.FIELD_REAL_CALLING_UID;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.FIELD_REAL_CALLING_UID_HAS_ANY_VISIBLE_WINDOW;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.FIELD_REAL_CALLING_UID_PROC_STATE;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.FIELD_TARGET_SHORT_COMPONENT_NAME;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.PACKAGE_OPTIMIZATION_COMPILATION_FILTER;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.PACKAGE_OPTIMIZATION_COMPILATION_REASON;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.TYPE_TRANSITION_COLD_LAUNCH;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.TYPE_TRANSITION_HOT_LAUNCH;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.TYPE_TRANSITION_REPORTED_DRAWN_NO_BUNDLE;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.TYPE_TRANSITION_REPORTED_DRAWN_WITH_BUNDLE;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.TYPE_TRANSITION_WARM_LAUNCH;
import static com.android.server.am.MemoryStatUtil.MemoryStat;
import static com.android.server.am.MemoryStatUtil.readMemoryStatFromFilesystem;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_METRICS;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.TAG_ATM;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.ActivityTaskManagerInternal.APP_TRANSITION_TIMEOUT;
import static com.android.server.wm.EventLogTags.WM_ACTIVITY_LAUNCH_TIME;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityOptions;
import android.app.ActivityOptions.SourceInfo;
import android.app.WaitResult;
import android.app.WindowConfiguration.WindowingMode;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.IncrementalStatesInfo;
import android.content.pm.dex.ArtManagerInternal;
import android.content.pm.dex.PackageOptimizationInfo;
import android.metrics.LogMaker;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.Trace;
import android.os.incremental.IncrementalManager;
import android.util.ArrayMap;
import android.util.EventLog;
import android.util.Log;
import android.util.Slog;
import android.util.TimeUtils;
import android.util.proto.ProtoOutputStream;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.util.FrameworkStatsLog;
import com.android.internal.util.function.pooled.PooledLambda;
import com.android.server.FgThread;
import com.android.server.LocalServices;
import com.android.server.apphibernation.AppHibernationManagerInternal;
import com.android.server.apphibernation.AppHibernationService;

import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

/**
 * Listens to activity launches, transitions, visibility changes and window drawn callbacks to
 * determine app launch times and draw delays. Source of truth for activity metrics and provides
 * data for Tron, logcat, event logs and {@link android.app.WaitResult}.
 * <p>
 * A typical sequence of a launch event could be:
 * {@link #notifyActivityLaunching}, {@link #notifyActivityLaunched},
 * {@link #notifyStartingWindowDrawn} (optional), {@link #notifyTransitionStarting}
 * {@link #notifyWindowsDrawn}.
 * <p>
 * Tests:
 * atest CtsWindowManagerDeviceTestCases:ActivityMetricsLoggerTests
 */
class ActivityMetricsLogger {

    private static final String TAG = TAG_WITH_CLASS_NAME ? "ActivityMetricsLogger" : TAG_ATM;

    // Window modes we are interested in logging. If we ever introduce a new type, we need to add
    // a value here and increase the {@link #TRON_WINDOW_STATE_VARZ_STRINGS} array.
    private static final int WINDOW_STATE_STANDARD = 0;
    private static final int WINDOW_STATE_SIDE_BY_SIDE = 1;
    private static final int WINDOW_STATE_FREEFORM = 2;
    private static final int WINDOW_STATE_ASSISTANT = 3;
    private static final int WINDOW_STATE_MULTI_WINDOW = 4;
    private static final int WINDOW_STATE_INVALID = -1;

    /**
     * If a launching activity isn't visible within this duration when the device is sleeping, e.g.
     * keyguard is locked, its transition info will be dropped.
     */
    private static final long UNKNOWN_VISIBILITY_CHECK_DELAY_MS = 3000;

    /**
     * The flag for {@link #notifyActivityLaunching} to skip associating a new launch with an active
     * transition, in the case the launch is standalone (e.g. from recents).
     */
    private static final int IGNORE_CALLER = -1;

    // Preallocated strings we are sending to tron, so we don't have to allocate a new one every
    // time we log.
    private static final String[] TRON_WINDOW_STATE_VARZ_STRINGS = {
            "window_time_0", "window_time_1", "window_time_2", "window_time_3", "window_time_4"};

    private int mWindowState = WINDOW_STATE_STANDARD;
    private long mLastLogTimeSecs;
    private final ActivityTaskSupervisor mSupervisor;
    private final MetricsLogger mMetricsLogger = new MetricsLogger();
    private final Handler mLoggerHandler = FgThread.getHandler();

    /** All active transitions. */
    private final ArrayList<TransitionInfo> mTransitionInfoList = new ArrayList<>();
    /** Map : Last launched activity => {@link TransitionInfo} */
    private final ArrayMap<ActivityRecord, TransitionInfo> mLastTransitionInfo = new ArrayMap<>();

    private ArtManagerInternal mArtManagerInternal;
    private final StringBuilder mStringBuilder = new StringBuilder();

    /**
     * Due to the global single concurrent launch sequence, all calls to this observer must be made
     * in-order on the same thread to fulfill the "happens-before" guarantee in LaunchObserver.
     */
    private final LaunchObserverRegistryImpl mLaunchObserver;
    @VisibleForTesting static final int LAUNCH_OBSERVER_ACTIVITY_RECORD_PROTO_CHUNK_SIZE = 512;
    private final ArrayMap<String, Boolean> mLastHibernationStates = new ArrayMap<>();
    private AppHibernationManagerInternal mAppHibernationManagerInternal;

    /**
     * The information created when an intent is incoming but we do not yet know whether it will be
     * launched successfully.
     */
    static final class LaunchingState {
        /** The device uptime of {@link #notifyActivityLaunching}. */
        private final long mCurrentUpTimeMs = SystemClock.uptimeMillis();
        /** The timestamp of {@link #notifyActivityLaunching}. */
        private long mCurrentTransitionStartTimeNs;
        /** Non-null when a {@link TransitionInfo} is created for this state. */
        private TransitionInfo mAssociatedTransitionInfo;

        @VisibleForTesting
        boolean allDrawn() {
            return mAssociatedTransitionInfo != null && mAssociatedTransitionInfo.allDrawn();
        }

        boolean contains(ActivityRecord r) {
            return mAssociatedTransitionInfo != null && mAssociatedTransitionInfo.contains(r);
        }
    }

    /** The information created when an activity is confirmed to be launched. */
    private static final class TransitionInfo {
        /**
         * The field to lookup and update an existing transition efficiently between
         * {@link #notifyActivityLaunching} and {@link #notifyActivityLaunched}.
         *
         * @see LaunchingState#mAssociatedTransitionInfo
         */
        final LaunchingState mLaunchingState;
        /**
         * The timestamp of the first {@link #notifyActivityLaunching}. It can be used as a key for
         * observer to identify which callbacks belong to a launch event.
         */
        final long mTransitionStartTimeNs;
        /** The device uptime in millis when this transition info is created. */
        final long mTransitionDeviceUptimeMs;
        /** The type can be cold (new process), warm (new activity), or hot (bring to front). */
        final int mTransitionType;
        /** Whether the process was already running when the transition started. */
        final boolean mProcessRunning;
        /** whether the process of the launching activity didn't have any active activity. */
        final boolean mProcessSwitch;
        /** The activities that should be drawn. */
        final ArrayList<ActivityRecord> mPendingDrawActivities = new ArrayList<>(2);
        /** The latest activity to have been launched. */
        @NonNull ActivityRecord mLastLaunchedActivity;

        /** The type of the source that triggers the launch event. */
        @SourceInfo.SourceType int mSourceType;
        /** The time from the source event (e.g. touch) to {@link #notifyActivityLaunching}. */
        int mSourceEventDelayMs = INVALID_DELAY;
        /** The time from {@link #mTransitionStartTimeNs} to {@link #notifyTransitionStarting}. */
        int mCurrentTransitionDelayMs;
        /** The time from {@link #mTransitionStartTimeNs} to {@link #notifyStartingWindowDrawn}. */
        int mStartingWindowDelayMs = INVALID_DELAY;
        /** The time from {@link #mTransitionStartTimeNs} to {@link #notifyBindApplication}. */
        int mBindApplicationDelayMs = INVALID_DELAY;
        /** Elapsed time from when we launch an activity to when its windows are drawn. */
        int mWindowsDrawnDelayMs;
        /** The reason why the transition started (see ActivityManagerInternal.APP_TRANSITION_*). */
        int mReason = APP_TRANSITION_TIMEOUT;
        /** The flag ensures that {@link #mStartingWindowDelayMs} is only set once. */
        boolean mLoggedStartingWindowDrawn;
        /** If the any app transitions have been logged as starting. */
        boolean mLoggedTransitionStarting;
        /** Whether any activity belonging to this transition has relaunched. */
        boolean mRelaunched;

        /** Non-null if the application has reported drawn but its window hasn't. */
        @Nullable Runnable mPendingFullyDrawn;
        /** Non-null if the trace is active. */
        @Nullable String mLaunchTraceName;

        /** @return Non-null if there will be a window drawn event for the launch. */
        @Nullable
        static TransitionInfo create(@NonNull ActivityRecord r,
                @NonNull LaunchingState launchingState, @Nullable ActivityOptions options,
                boolean processRunning, boolean processSwitch, boolean newActivityCreated,
                int startResult) {
            if (startResult != START_SUCCESS && startResult != START_TASK_TO_FRONT) {
                return null;
            }
            final int transitionType;
            if (processRunning) {
                transitionType = !newActivityCreated && r.attachedToProcess()
                        ? TYPE_TRANSITION_HOT_LAUNCH
                        : TYPE_TRANSITION_WARM_LAUNCH;
            } else {
                // Task may still exist when cold launching an activity and the start result will be
                // set to START_TASK_TO_FRONT. Treat this as a COLD launch.
                transitionType = TYPE_TRANSITION_COLD_LAUNCH;
            }
            return new TransitionInfo(r, launchingState, options, transitionType, processRunning,
                    processSwitch);
        }

        /** Use {@link TransitionInfo#create} instead to ensure the transition type is valid. */
        private TransitionInfo(ActivityRecord r, LaunchingState launchingState,
                ActivityOptions options, int transitionType, boolean processRunning,
                boolean processSwitch) {
            mLaunchingState = launchingState;
            mTransitionStartTimeNs = launchingState.mCurrentTransitionStartTimeNs;
            mTransitionType = transitionType;
            mProcessRunning = processRunning;
            mProcessSwitch = processSwitch;
            mTransitionDeviceUptimeMs = launchingState.mCurrentUpTimeMs;
            setLatestLaunchedActivity(r);
            launchingState.mAssociatedTransitionInfo = this;
            if (options != null) {
                final SourceInfo sourceInfo = options.getSourceInfo();
                if (sourceInfo != null) {
                    mSourceType = sourceInfo.type;
                    mSourceEventDelayMs =
                            (int) (launchingState.mCurrentUpTimeMs - sourceInfo.eventTimeMs);
                }
            }
        }

        /**
         * Remembers the latest launched activity to represent the final transition. This also
         * tracks the activities that should be drawn, so a consecutive launching sequence can be
         * coalesced as one event.
         */
        void setLatestLaunchedActivity(ActivityRecord r) {
            if (mLastLaunchedActivity == r) {
                return;
            }
            if (mLastLaunchedActivity != null) {
                // Transfer the launch cookie because it is a consecutive launch event.
                r.mLaunchCookie = mLastLaunchedActivity.mLaunchCookie;
                mLastLaunchedActivity.mLaunchCookie = null;
            }
            mLastLaunchedActivity = r;
            if (!r.noDisplay && !r.isReportedDrawn()) {
                if (DEBUG_METRICS) Slog.i(TAG, "Add pending draw " + r);
                mPendingDrawActivities.add(r);
            }
        }

        /** Returns {@code true} if the incoming activity can belong to this transition. */
        boolean canCoalesce(ActivityRecord r) {
            return mLastLaunchedActivity.mDisplayContent == r.mDisplayContent
                    && mLastLaunchedActivity.getWindowingMode() == r.getWindowingMode();
        }

        /** @return {@code true} if the activity matches a launched activity in this transition. */
        boolean contains(ActivityRecord r) {
            return r != null && (r == mLastLaunchedActivity || mPendingDrawActivities.contains(r));
        }

        /** Called when the activity is drawn or won't be drawn. */
        void removePendingDrawActivity(ActivityRecord r) {
            if (DEBUG_METRICS) Slog.i(TAG, "Remove pending draw " + r);
            mPendingDrawActivities.remove(r);
        }

        boolean allDrawn() {
            return mPendingDrawActivities.isEmpty();
        }

        /** Only keep the records which can be drawn. */
        void updatePendingDraw() {
            for (int i = mPendingDrawActivities.size() - 1; i >= 0; i--) {
                final ActivityRecord r = mPendingDrawActivities.get(i);
                if (!r.mVisibleRequested) {
                    if (DEBUG_METRICS) Slog.i(TAG, "Discard pending draw " + r);
                    mPendingDrawActivities.remove(i);
                }
            }
        }

        /**
         * @return {@code true} if the transition info should be sent to MetricsLogger, StatsLog, or
         *         LaunchObserver.
         */
        boolean isInterestingToLoggerAndObserver() {
            return mProcessSwitch;
        }

        int calculateCurrentDelay() {
            return calculateDelay(SystemClock.elapsedRealtimeNanos());
        }

        int calculateDelay(long timestampNs) {
            // Shouldn't take more than 25 days to launch an app, so int is fine here.
            return (int) TimeUnit.NANOSECONDS.toMillis(timestampNs - mTransitionStartTimeNs);
        }

        @Override
        public String toString() {
            return "TransitionInfo{" + Integer.toHexString(System.identityHashCode(this))
                    + " a=" + mLastLaunchedActivity + " ua=" + mPendingDrawActivities + "}";
        }
    }

    static final class TransitionInfoSnapshot {
        final private ApplicationInfo applicationInfo;
        final private WindowProcessController processRecord;
        final String packageName;
        final String launchedActivityName;
        final private String launchedActivityLaunchedFromPackage;
        final private String launchedActivityLaunchToken;
        final private String launchedActivityAppRecordRequiredAbi;
        final String launchedActivityShortComponentName;
        final private String processName;
        @VisibleForTesting final @SourceInfo.SourceType int sourceType;
        @VisibleForTesting final int sourceEventDelayMs;
        final private int reason;
        final private int startingWindowDelayMs;
        final private int bindApplicationDelayMs;
        final int windowsDrawnDelayMs;
        final int type;
        final int userId;
        /**
         * Elapsed time from when we launch an activity to when the app reported it was
         * fully drawn. If this is not reported then the value is set to INVALID_DELAY.
         */
        final int windowsFullyDrawnDelayMs;
        final int activityRecordIdHashCode;
        final boolean relaunched;

        private TransitionInfoSnapshot(TransitionInfo info) {
            this(info, info.mLastLaunchedActivity, INVALID_DELAY);
        }

        private TransitionInfoSnapshot(TransitionInfo info, ActivityRecord launchedActivity,
                int windowsFullyDrawnDelayMs) {
            applicationInfo = launchedActivity.info.applicationInfo;
            packageName = launchedActivity.packageName;
            launchedActivityName = launchedActivity.info.name;
            launchedActivityLaunchedFromPackage = launchedActivity.launchedFromPackage;
            launchedActivityLaunchToken = launchedActivity.info.launchToken;
            launchedActivityAppRecordRequiredAbi = launchedActivity.app == null
                    ? null
                    : launchedActivity.app.getRequiredAbi();
            reason = info.mReason;
            sourceEventDelayMs = info.mSourceEventDelayMs;
            startingWindowDelayMs = info.mStartingWindowDelayMs;
            bindApplicationDelayMs = info.mBindApplicationDelayMs;
            windowsDrawnDelayMs = info.mWindowsDrawnDelayMs;
            type = info.mTransitionType;
            processRecord = launchedActivity.app;
            processName = launchedActivity.processName;
            sourceType = info.mSourceType;
            userId = launchedActivity.mUserId;
            launchedActivityShortComponentName = launchedActivity.shortComponentName;
            activityRecordIdHashCode = System.identityHashCode(launchedActivity);
            this.windowsFullyDrawnDelayMs = windowsFullyDrawnDelayMs;
            relaunched = info.mRelaunched;
        }

        @WaitResult.LaunchState int getLaunchState() {
            switch (type) {
                case TYPE_TRANSITION_WARM_LAUNCH:
                    return LAUNCH_STATE_WARM;
                case TYPE_TRANSITION_HOT_LAUNCH:
                    return relaunched ? LAUNCH_STATE_RELAUNCH : LAUNCH_STATE_HOT;
                case TYPE_TRANSITION_COLD_LAUNCH:
                    return LAUNCH_STATE_COLD;
                default:
                    return -1;
            }
        }

        PackageOptimizationInfo getPackageOptimizationInfo(ArtManagerInternal artManagerInternal) {
            return artManagerInternal == null || launchedActivityAppRecordRequiredAbi == null
                    ? PackageOptimizationInfo.createWithNoInfo()
                    : artManagerInternal.getPackageOptimizationInfo(applicationInfo,
                            launchedActivityAppRecordRequiredAbi, launchedActivityName);
        }
    }

    ActivityMetricsLogger(ActivityTaskSupervisor supervisor, Looper looper) {
        mLastLogTimeSecs = SystemClock.elapsedRealtime() / 1000;
        mSupervisor = supervisor;
        mLaunchObserver = new LaunchObserverRegistryImpl(looper);
    }

    void logWindowState() {
        final long now = SystemClock.elapsedRealtime() / 1000;
        if (mWindowState != WINDOW_STATE_INVALID) {
            // We log even if the window state hasn't changed, because the user might remain in
            // home/fullscreen move forever and we would like to track this kind of behavior
            // too.
            mMetricsLogger.count(TRON_WINDOW_STATE_VARZ_STRINGS[mWindowState],
                    (int) (now - mLastLogTimeSecs));
        }
        mLastLogTimeSecs = now;

        mWindowState = WINDOW_STATE_INVALID;
        Task rootTask = mSupervisor.mRootWindowContainer.getTopDisplayFocusedRootTask();
        if (rootTask == null) {
            return;
        }

        if (rootTask.isActivityTypeAssistant()) {
            mWindowState = WINDOW_STATE_ASSISTANT;
            return;
        }

        @WindowingMode int windowingMode = rootTask.getWindowingMode();
        if (windowingMode == WINDOWING_MODE_PINNED) {
            rootTask = mSupervisor.mRootWindowContainer.findRootTaskBehind(rootTask);
            windowingMode = rootTask.getWindowingMode();
        }
        switch (windowingMode) {
            case WINDOWING_MODE_FULLSCREEN:
                mWindowState = WINDOW_STATE_STANDARD;
                break;
            case WINDOWING_MODE_SPLIT_SCREEN_PRIMARY:
            case WINDOWING_MODE_SPLIT_SCREEN_SECONDARY:
                mWindowState = WINDOW_STATE_SIDE_BY_SIDE;
                break;
            case WINDOWING_MODE_FREEFORM:
                mWindowState = WINDOW_STATE_FREEFORM;
                break;
            case WINDOWING_MODE_MULTI_WINDOW:
                mWindowState = WINDOW_STATE_MULTI_WINDOW;
                break;
            default:
                if (windowingMode != WINDOWING_MODE_UNDEFINED) {
                    throw new IllegalStateException("Unknown windowing mode for task=" + rootTask
                            + " windowingMode=" + windowingMode);
                }
        }
    }

    /** @return Non-null {@link TransitionInfo} if the activity is found in an active transition. */
    @Nullable
    private TransitionInfo getActiveTransitionInfo(ActivityRecord r) {
        for (int i = mTransitionInfoList.size() - 1; i >= 0; i--) {
            final TransitionInfo info = mTransitionInfoList.get(i);
            if (info.contains(r)) {
                return info;
            }
        }
        return null;
    }

    /**
     * This method should be only used by starting recents and starting from recents, or internal
     * tests. Because it doesn't lookup caller and always creates a new launching state.
     *
     * @see #notifyActivityLaunching(Intent, ActivityRecord, int)
     */
    LaunchingState notifyActivityLaunching(Intent intent) {
        return notifyActivityLaunching(intent, null /* caller */, IGNORE_CALLER);
    }

    /**
     * Notifies the tracker at the earliest possible point when we are starting to launch an
     * activity. The caller must ensure that {@link #notifyActivityLaunched} will be called later
     * with the returned {@link LaunchingState}. If the caller is found in an active transition,
     * it will be considered as consecutive launch and coalesced into the active transition.
     */
    LaunchingState notifyActivityLaunching(Intent intent, @Nullable ActivityRecord caller,
            int callingUid) {
        final long transitionStartTimeNs = SystemClock.elapsedRealtimeNanos();
        TransitionInfo existingInfo = null;
        if (callingUid != IGNORE_CALLER) {
            // Associate the launching event to an active transition if the caller is found in its
            // launched activities.
            for (int i = mTransitionInfoList.size() - 1; i >= 0; i--) {
                final TransitionInfo info = mTransitionInfoList.get(i);
                if (caller != null && info.contains(caller)) {
                    existingInfo = info;
                    break;
                }
                if (existingInfo == null && callingUid == info.mLastLaunchedActivity.getUid()) {
                    // Fallback to check the most recent matched uid for the case that the caller is
                    // not an activity.
                    existingInfo = info;
                }
            }
        }
        if (DEBUG_METRICS) {
            Slog.i(TAG, "notifyActivityLaunching intent=" + intent
                    + " existingInfo=" + existingInfo);
        }

        if (existingInfo == null) {
            // Only notify the observer for a new launching event.
            launchObserverNotifyIntentStarted(intent, transitionStartTimeNs);
            final LaunchingState launchingState = new LaunchingState();
            launchingState.mCurrentTransitionStartTimeNs = transitionStartTimeNs;
            return launchingState;
        }
        existingInfo.mLaunchingState.mCurrentTransitionStartTimeNs = transitionStartTimeNs;
        return existingInfo.mLaunchingState;
    }

    /**
     * Notifies the tracker that the activity is actually launching.
     *
     * @param launchingState The launching state to track the new or active transition.
     * @param resultCode One of the {@link android.app.ActivityManager}.START_* flags, indicating
     *                   the result of the launch.
     * @param launchedActivity The activity that is being launched
     * @param newActivityCreated Whether a new activity instance is created.
     * @param options The given options of the launching activity.
     */
    void notifyActivityLaunched(@NonNull LaunchingState launchingState, int resultCode,
            boolean newActivityCreated, @Nullable ActivityRecord launchedActivity,
            @Nullable ActivityOptions options) {
        if (launchedActivity == null) {
            // The launch is aborted, e.g. intent not resolved, class not found.
            abort(null /* info */, "nothing launched");
            return;
        }

        final WindowProcessController processRecord = launchedActivity.app != null
                ? launchedActivity.app
                : mSupervisor.mService.getProcessController(
                        launchedActivity.processName, launchedActivity.info.applicationInfo.uid);
        // Whether the process that will contains the activity is already running.
        final boolean processRunning = processRecord != null;
        // We consider this a "process switch" if the process of the activity that gets launched
        // didn't have an activity that was in started state. In this case, we assume that lot
        // of caches might be purged so the time until it produces the first frame is very
        // interesting.
        final boolean processSwitch = !processRunning
                || !processRecord.hasStartedActivity(launchedActivity);

        final TransitionInfo info = launchingState.mAssociatedTransitionInfo;
        if (DEBUG_METRICS) {
            Slog.i(TAG, "notifyActivityLaunched" + " resultCode=" + resultCode
                    + " launchedActivity=" + launchedActivity + " processRunning=" + processRunning
                    + " processSwitch=" + processSwitch
                    + " newActivityCreated=" + newActivityCreated + " info=" + info);
        }

        if (launchedActivity.isReportedDrawn() && launchedActivity.isVisible()) {
            // Launched activity is already visible. We cannot measure windows drawn delay.
            abort(info, "launched activity already visible");
            return;
        }

        // If the launched activity is started from an existing active transition, it will be put
        // into the transition info.
        if (info != null && info.canCoalesce(launchedActivity)) {
            if (DEBUG_METRICS) Slog.i(TAG, "notifyActivityLaunched consecutive launch");

            final boolean crossPackage =
                    !info.mLastLaunchedActivity.packageName.equals(launchedActivity.packageName);
            // The trace name uses package name so different packages should be separated.
            if (crossPackage) {
                stopLaunchTrace(info);
            }

            mLastTransitionInfo.remove(info.mLastLaunchedActivity);
            // Coalesce multiple (trampoline) activities from a single sequence together.
            info.setLatestLaunchedActivity(launchedActivity);
            // Update the latest one so it can be found when reporting fully-drawn.
            mLastTransitionInfo.put(launchedActivity, info);

            if (crossPackage) {
                startLaunchTrace(info);
            }
            return;
        }

        final TransitionInfo newInfo = TransitionInfo.create(launchedActivity, launchingState,
                options, processRunning, processSwitch, newActivityCreated, resultCode);
        if (newInfo == null) {
            abort(info, "unrecognized launch");
            return;
        }

        if (DEBUG_METRICS) Slog.i(TAG, "notifyActivityLaunched successful");
        // A new launch sequence has begun. Start tracking it.
        mTransitionInfoList.add(newInfo);
        mLastTransitionInfo.put(launchedActivity, newInfo);
        startLaunchTrace(newInfo);
        if (newInfo.isInterestingToLoggerAndObserver()) {
            launchObserverNotifyActivityLaunched(newInfo);
        } else {
            // As abort for no process switch.
            launchObserverNotifyIntentFailed();
        }
        if (launchedActivity.mDisplayContent.isSleeping()) {
            // It is unknown whether the activity can be drawn or not, e.g. it depends on the
            // keyguard states and the attributes or flags set by the activity. If the activity
            // keeps invisible in the grace period, the tracker will be cancelled so it won't get
            // a very long launch time that takes unlocking as the end of launch.
            scheduleCheckActivityToBeDrawn(launchedActivity, UNKNOWN_VISIBILITY_CHECK_DELAY_MS);
        }

        // If the previous transitions are no longer visible, abort them to avoid counting the
        // launch time when resuming from back stack. E.g. launch 2 independent tasks in a short
        // time, the transition info of the first task should not keep active until it becomes
        // visible such as after the top task is finished.
        for (int i = mTransitionInfoList.size() - 2; i >= 0; i--) {
            final TransitionInfo prevInfo = mTransitionInfoList.get(i);
            prevInfo.updatePendingDraw();
            if (prevInfo.allDrawn()) {
                abort(prevInfo, "nothing will be drawn");
            }
        }
    }

    /**
     * Notifies the tracker that all windows of the app have been drawn.
     *
     * @return Non-null info if the activity was pending to draw, otherwise it might have been set
     *         to invisible (removed from active transition) or it was already drawn.
     */
    @Nullable
    TransitionInfoSnapshot notifyWindowsDrawn(@NonNull ActivityRecord r, long timestampNs) {
        if (DEBUG_METRICS) Slog.i(TAG, "notifyWindowsDrawn " + r);

        final TransitionInfo info = getActiveTransitionInfo(r);
        if (info == null || info.allDrawn()) {
            if (DEBUG_METRICS) Slog.i(TAG, "notifyWindowsDrawn no activity to be drawn");
            return null;
        }
        // Always calculate the delay because the caller may need to know the individual drawn time.
        info.mWindowsDrawnDelayMs = info.calculateDelay(timestampNs);
        info.removePendingDrawActivity(r);
        final TransitionInfoSnapshot infoSnapshot = new TransitionInfoSnapshot(info);
        if (info.mLoggedTransitionStarting && info.allDrawn()) {
            done(false /* abort */, info, "notifyWindowsDrawn - all windows drawn", timestampNs);
        }
        if (r.mWmService.isRecentsAnimationTarget(r)) {
            r.mWmService.getRecentsAnimationController().logRecentsAnimationStartTime(
                    info.mSourceEventDelayMs + info.mWindowsDrawnDelayMs);
        }
        return infoSnapshot;
    }

    /**
     * Notifies the tracker that the starting window was drawn.
     */
    void notifyStartingWindowDrawn(@NonNull ActivityRecord r) {
        final TransitionInfo info = getActiveTransitionInfo(r);
        if (info == null || info.mLoggedStartingWindowDrawn) {
            return;
        }
        if (DEBUG_METRICS) Slog.i(TAG, "notifyStartingWindowDrawn " + r);
        info.mLoggedStartingWindowDrawn = true;
        info.mStartingWindowDelayMs = info.calculateDelay(SystemClock.elapsedRealtimeNanos());
    }

    /**
     * Notifies the tracker that the app transition is starting.
     *
     * @param activityToReason A map from activity to a reason integer, which must be on of
     *                         ActivityTaskManagerInternal.APP_TRANSITION_* reasons.
     */
    void notifyTransitionStarting(ArrayMap<WindowContainer, Integer> activityToReason) {
        if (DEBUG_METRICS) Slog.i(TAG, "notifyTransitionStarting " + activityToReason);

        final long timestampNs = SystemClock.elapsedRealtimeNanos();
        for (int index = activityToReason.size() - 1; index >= 0; index--) {
            final WindowContainer<?> wc = activityToReason.keyAt(index);
            final ActivityRecord activity = wc.asActivityRecord();
            final ActivityRecord r = activity != null ? activity
                    : wc.getTopActivity(false /* includeFinishing */, true /* includeOverlays */);
            final TransitionInfo info = getActiveTransitionInfo(r);
            if (info == null || info.mLoggedTransitionStarting) {
                // Ignore any subsequent notifyTransitionStarting.
                continue;
            }
            if (DEBUG_METRICS) {
                Slog.i(TAG, "notifyTransitionStarting activity=" + wc + " info=" + info);
            }

            info.mCurrentTransitionDelayMs = info.calculateDelay(timestampNs);
            info.mReason = activityToReason.valueAt(index);
            info.mLoggedTransitionStarting = true;
            info.updatePendingDraw();
            if (info.allDrawn()) {
                done(false /* abort */, info, "notifyTransitionStarting - all windows drawn",
                        timestampNs);
            }
        }
    }

    void notifyActivityRelaunched(ActivityRecord r) {
        final TransitionInfo info = getActiveTransitionInfo(r);
        if (info != null) {
            info.mRelaunched = true;
        }
    }

    /** Makes sure that the reference to the removed activity is cleared. */
    void notifyActivityRemoved(@NonNull ActivityRecord r) {
        mLastTransitionInfo.remove(r);
    }

    /**
     * Notifies the tracker that the visibility of an app is changing.
     *
     * @param r the app that is changing its visibility
     */
    void notifyVisibilityChanged(@NonNull ActivityRecord r) {
        final TransitionInfo info = getActiveTransitionInfo(r);
        if (info == null) {
            return;
        }
        if (DEBUG_METRICS) {
            Slog.i(TAG, "notifyVisibilityChanged " + r + " visible=" + r.mVisibleRequested
                    + " state=" + r.getState() + " finishing=" + r.finishing);
        }
        if (r.isState(Task.ActivityState.RESUMED) && r.mDisplayContent.isSleeping()) {
            // The activity may be launching while keyguard is locked. The keyguard may be dismissed
            // after the activity finished relayout, so skip the visibility check to avoid aborting
            // the tracking of launch event.
            return;
        }
        if (!r.mVisibleRequested || r.finishing) {
            info.removePendingDrawActivity(r);
            if (info.mLastLaunchedActivity == r) {
                // Check if the tracker can be cancelled because the last launched activity may be
                // no longer visible.
                scheduleCheckActivityToBeDrawn(r, 0 /* delay */);
            }
        }
    }

    private void scheduleCheckActivityToBeDrawn(@NonNull ActivityRecord r, long delay) {
        // The activity and its task are passed separately because it is possible that the activity
        // is removed from the task later.
        r.mAtmService.mH.sendMessageDelayed(PooledLambda.obtainMessage(
                ActivityMetricsLogger::checkActivityToBeDrawn, this, r.getTask(), r), delay);
    }

    /** Cancels the tracking of launch if there won't be an activity to be drawn. */
    private void checkActivityToBeDrawn(Task t, ActivityRecord r) {
        synchronized (mSupervisor.mService.mGlobalLock) {
            final TransitionInfo info = getActiveTransitionInfo(r);

            // If we have an active transition that's waiting on a certain activity that will be
            // invisible now, we'll never get onWindowsDrawn, so abort the transition if necessary.

            // We have no active transitions.
            if (info == null) {
                return;
            }

            // The notified activity whose visibility changed is no longer the launched activity.
            // We can still wait to get onWindowsDrawn.
            if (info.mLastLaunchedActivity != r) {
                return;
            }

            // If the task of the launched activity contains any activity to be drawn, then the
            // window drawn event should report later to complete the transition. Otherwise all
            // activities in this task may be finished, invisible or drawn, so the transition event
            // should be cancelled.
            if (t != null && t.forAllActivities(
                    a -> a.mVisibleRequested && !a.isReportedDrawn() && !a.finishing)) {
                return;
            }

            if (DEBUG_METRICS) Slog.i(TAG, "checkActivityToBeDrawn cancels activity=" + r);
            logAppTransitionCancel(info);
            abort(info, "checkActivityToBeDrawn (invisible or drawn already)");
        }
    }

    @Nullable
    private AppHibernationManagerInternal getAppHibernationManagerInternal() {
        if (!AppHibernationService.isAppHibernationEnabled()) return null;
        if (mAppHibernationManagerInternal == null) {
            mAppHibernationManagerInternal =
                    LocalServices.getService(AppHibernationManagerInternal.class);
        }
        return mAppHibernationManagerInternal;
    }

    /**
     * Notifies the tracker before the package is unstopped because of launching activity.
     * @param packageName The package to be unstopped.
     */
    void notifyBeforePackageUnstopped(@NonNull String packageName) {
        final AppHibernationManagerInternal ahmInternal = getAppHibernationManagerInternal();
        if (ahmInternal != null) {
            mLastHibernationStates.put(packageName, ahmInternal.isHibernatingGlobally(packageName));
        }
    }

    /**
     * Notifies the tracker that we called immediately before we call bindApplication on the client.
     *
     * @param appInfo The client into which we'll call bindApplication.
     */
    void notifyBindApplication(ApplicationInfo appInfo) {
        for (int i = mTransitionInfoList.size() - 1; i >= 0; i--) {
            final TransitionInfo info = mTransitionInfoList.get(i);

            // App isn't attached to record yet, so match with info.
            if (info.mLastLaunchedActivity.info.applicationInfo == appInfo) {
                info.mBindApplicationDelayMs = info.calculateCurrentDelay();
            }
        }
    }

    /** Aborts tracking of current launch metrics. */
    private void abort(TransitionInfo info, String cause) {
        done(true /* abort */, info, cause, 0L /* timestampNs */);
    }

    /** Called when the given transition (info) is no longer active. */
    private void done(boolean abort, @Nullable TransitionInfo info, String cause,
            long timestampNs) {
        if (DEBUG_METRICS) {
            Slog.i(TAG, "done abort=" + abort + " cause=" + cause + " timestamp=" + timestampNs
                    + " info=" + info);
        }
        if (info == null) {
            launchObserverNotifyIntentFailed();
            return;
        }

        stopLaunchTrace(info);
        final Boolean isHibernating =
                mLastHibernationStates.remove(info.mLastLaunchedActivity.packageName);
        if (abort) {
            mLastTransitionInfo.remove(info.mLastLaunchedActivity);
            mSupervisor.stopWaitingForActivityVisible(info.mLastLaunchedActivity);
            launchObserverNotifyActivityLaunchCancelled(info);
        } else {
            if (info.isInterestingToLoggerAndObserver()) {
                launchObserverNotifyActivityLaunchFinished(info, timestampNs);
            }
            logAppTransitionFinished(info, isHibernating != null ? isHibernating : false);
        }
        info.mPendingDrawActivities.clear();
        mTransitionInfoList.remove(info);
    }

    private void logAppTransitionCancel(TransitionInfo info) {
        final int type = info.mTransitionType;
        final ActivityRecord activity = info.mLastLaunchedActivity;
        final LogMaker builder = new LogMaker(APP_TRANSITION_CANCELLED);
        builder.setPackageName(activity.packageName);
        builder.setType(type);
        builder.addTaggedData(FIELD_CLASS_NAME, activity.info.name);
        mMetricsLogger.write(builder);
        FrameworkStatsLog.write(
                FrameworkStatsLog.APP_START_CANCELED,
                activity.info.applicationInfo.uid,
                activity.packageName,
                getAppStartTransitionType(type, info.mRelaunched),
                activity.info.name);
        if (DEBUG_METRICS) {
            Slog.i(TAG, String.format("APP_START_CANCELED(%s, %s, %s, %s)",
                    activity.info.applicationInfo.uid,
                    activity.packageName,
                    getAppStartTransitionType(type, info.mRelaunched),
                    activity.info.name));
        }
    }

    private void logAppTransitionFinished(@NonNull TransitionInfo info, boolean isHibernating) {
        if (DEBUG_METRICS) Slog.i(TAG, "logging finished transition " + info);

        // Take a snapshot of the transition info before sending it to the handler for logging.
        // This will avoid any races with other operations that modify the ActivityRecord.
        final TransitionInfoSnapshot infoSnapshot = new TransitionInfoSnapshot(info);
        if (info.isInterestingToLoggerAndObserver()) {
            final long timestamp = info.mTransitionStartTimeNs;
            final long uptime = info.mTransitionDeviceUptimeMs;
            final int transitionDelay = info.mCurrentTransitionDelayMs;
            mLoggerHandler.post(() -> logAppTransition(
                    timestamp, uptime, transitionDelay, infoSnapshot, isHibernating));
        }
        mLoggerHandler.post(() -> logAppDisplayed(infoSnapshot));
        if (info.mPendingFullyDrawn != null) {
            info.mPendingFullyDrawn.run();
        }

        info.mLastLaunchedActivity.info.launchToken = null;
    }

    // This gets called on another thread without holding the activity manager lock.
    private void logAppTransition(long transitionStartTimeNs, long transitionDeviceUptimeMs,
            int currentTransitionDelayMs, TransitionInfoSnapshot info, boolean isHibernating) {
        final LogMaker builder = new LogMaker(APP_TRANSITION);
        builder.setPackageName(info.packageName);
        builder.setType(info.type);
        builder.addTaggedData(FIELD_CLASS_NAME, info.launchedActivityName);
        final boolean isInstantApp = info.applicationInfo.isInstantApp();
        if (info.launchedActivityLaunchedFromPackage != null) {
            builder.addTaggedData(APP_TRANSITION_CALLING_PACKAGE_NAME,
                    info.launchedActivityLaunchedFromPackage);
        }
        String launchToken = info.launchedActivityLaunchToken;
        if (launchToken != null) {
            builder.addTaggedData(FIELD_INSTANT_APP_LAUNCH_TOKEN, launchToken);
        }
        builder.addTaggedData(APP_TRANSITION_IS_EPHEMERAL, isInstantApp ? 1 : 0);
        builder.addTaggedData(APP_TRANSITION_DEVICE_UPTIME_SECONDS,
                TimeUnit.MILLISECONDS.toSeconds(transitionDeviceUptimeMs));
        builder.addTaggedData(APP_TRANSITION_DELAY_MS, currentTransitionDelayMs);
        builder.setSubtype(info.reason);
        if (info.startingWindowDelayMs != INVALID_DELAY) {
            builder.addTaggedData(APP_TRANSITION_STARTING_WINDOW_DELAY_MS,
                    info.startingWindowDelayMs);
        }
        if (info.bindApplicationDelayMs != INVALID_DELAY) {
            builder.addTaggedData(APP_TRANSITION_BIND_APPLICATION_DELAY_MS,
                    info.bindApplicationDelayMs);
        }
        builder.addTaggedData(APP_TRANSITION_WINDOWS_DRAWN_DELAY_MS, info.windowsDrawnDelayMs);
        final PackageOptimizationInfo packageOptimizationInfo =
                info.getPackageOptimizationInfo(getArtManagerInternal());
        builder.addTaggedData(PACKAGE_OPTIMIZATION_COMPILATION_REASON,
                packageOptimizationInfo.getCompilationReason());
        builder.addTaggedData(PACKAGE_OPTIMIZATION_COMPILATION_FILTER,
                packageOptimizationInfo.getCompilationFilter());
        mMetricsLogger.write(builder);

        // Incremental info
        boolean isIncremental = false, isLoading = false;
        final String codePath = info.applicationInfo.getCodePath();
        if (codePath != null && IncrementalManager.isIncrementalPath(codePath)) {
            isIncremental = true;
            isLoading = isIncrementalLoading(info.packageName, info.userId);
        }
        FrameworkStatsLog.write(
                FrameworkStatsLog.APP_START_OCCURRED,
                info.applicationInfo.uid,
                info.packageName,
                getAppStartTransitionType(info.type, info.relaunched),
                info.launchedActivityName,
                info.launchedActivityLaunchedFromPackage,
                isInstantApp,
                0 /* deprecated transitionDeviceUptimeMs */,
                info.reason,
                currentTransitionDelayMs,
                info.startingWindowDelayMs,
                info.bindApplicationDelayMs,
                info.windowsDrawnDelayMs,
                launchToken,
                packageOptimizationInfo.getCompilationReason(),
                packageOptimizationInfo.getCompilationFilter(),
                info.sourceType,
                info.sourceEventDelayMs,
                isHibernating,
                isIncremental,
                isLoading,
                info.launchedActivityName.hashCode(),
                TimeUnit.NANOSECONDS.toMillis(transitionStartTimeNs));

        if (DEBUG_METRICS) {
            Slog.i(TAG, String.format("APP_START_OCCURRED(%s, %s, %s, %s, %s)",
                    info.applicationInfo.uid,
                    info.packageName,
                    getAppStartTransitionType(info.type, info.relaunched),
                    info.launchedActivityName,
                    info.launchedActivityLaunchedFromPackage));
        }


        logAppStartMemoryStateCapture(info);
    }

    private boolean isIncrementalLoading(String packageName, int userId) {
        final IncrementalStatesInfo info = mSupervisor.mService.getPackageManagerInternalLocked()
                .getIncrementalStatesInfo(packageName, 0 /* filterCallingUid */, userId);
        return info != null && info.isLoading();
    }

    private void logAppDisplayed(TransitionInfoSnapshot info) {
        if (info.type != TYPE_TRANSITION_WARM_LAUNCH && info.type != TYPE_TRANSITION_COLD_LAUNCH) {
            return;
        }

        EventLog.writeEvent(WM_ACTIVITY_LAUNCH_TIME,
                info.userId, info.activityRecordIdHashCode, info.launchedActivityShortComponentName,
                info.windowsDrawnDelayMs);

        StringBuilder sb = mStringBuilder;
        sb.setLength(0);
        sb.append("Displayed ");
        sb.append(info.launchedActivityShortComponentName);
        sb.append(": ");
        TimeUtils.formatDuration(info.windowsDrawnDelayMs, sb);
        Log.i(TAG, sb.toString());
    }

    private static int getAppStartTransitionType(int tronType, boolean relaunched) {
        if (tronType == TYPE_TRANSITION_COLD_LAUNCH) {
            return FrameworkStatsLog.APP_START_OCCURRED__TYPE__COLD;
        }
        if (tronType == TYPE_TRANSITION_WARM_LAUNCH) {
            return FrameworkStatsLog.APP_START_OCCURRED__TYPE__WARM;
        }
        if (tronType == TYPE_TRANSITION_HOT_LAUNCH) {
            return relaunched
                    ? FrameworkStatsLog.APP_START_OCCURRED__TYPE__RELAUNCH
                    : FrameworkStatsLog.APP_START_OCCURRED__TYPE__HOT;
        }
        return FrameworkStatsLog.APP_START_OCCURRED__TYPE__UNKNOWN;
    }

    /** @see android.app.Activity#reportFullyDrawn */
    TransitionInfoSnapshot logAppTransitionReportedDrawn(ActivityRecord r,
            boolean restoredFromBundle) {
        final TransitionInfo info = mLastTransitionInfo.get(r);
        if (info == null) {
            return null;
        }
        if (!info.allDrawn() && info.mPendingFullyDrawn == null) {
            // There are still undrawn activities, postpone reporting fully drawn until all of its
            // windows are drawn. So that is closer to an usable state.
            info.mPendingFullyDrawn = () -> {
                logAppTransitionReportedDrawn(r, restoredFromBundle);
                info.mPendingFullyDrawn = null;
            };
            return null;
        }

        final long currentTimestampNs = SystemClock.elapsedRealtimeNanos();
        final long startupTimeMs = info.mPendingFullyDrawn != null
                ? info.mWindowsDrawnDelayMs
                : TimeUnit.NANOSECONDS.toMillis(currentTimestampNs - info.mTransitionStartTimeNs);
        final TransitionInfoSnapshot infoSnapshot =
                new TransitionInfoSnapshot(info, r, (int) startupTimeMs);
        mLoggerHandler.post(() -> logAppFullyDrawn(infoSnapshot));
        mLastTransitionInfo.remove(r);

        if (!info.isInterestingToLoggerAndObserver()) {
            return infoSnapshot;
        }

        // Record the handling of the reportFullyDrawn callback in the trace system. This is not
        // actually used to trace this function, but instead the logical task that this function
        // fullfils (handling reportFullyDrawn() callbacks).
        Trace.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER,
                "ActivityManager:ReportingFullyDrawn " + info.mLastLaunchedActivity.packageName);

        final LogMaker builder = new LogMaker(APP_TRANSITION_REPORTED_DRAWN);
        builder.setPackageName(r.packageName);
        builder.addTaggedData(FIELD_CLASS_NAME, r.info.name);
        builder.addTaggedData(APP_TRANSITION_REPORTED_DRAWN_MS, startupTimeMs);
        builder.setType(restoredFromBundle
                ? TYPE_TRANSITION_REPORTED_DRAWN_WITH_BUNDLE
                : TYPE_TRANSITION_REPORTED_DRAWN_NO_BUNDLE);
        builder.addTaggedData(APP_TRANSITION_PROCESS_RUNNING,
                info.mProcessRunning ? 1 : 0);
        mMetricsLogger.write(builder);
        final PackageOptimizationInfo packageOptimizationInfo =
                infoSnapshot.getPackageOptimizationInfo(getArtManagerInternal());
        // Incremental info
        boolean isIncremental = false, isLoading = false;
        final String codePath = info.mLastLaunchedActivity.info.applicationInfo.getCodePath();
        if (codePath != null && IncrementalManager.isIncrementalPath(codePath)) {
            isIncremental = true;
            isLoading = isIncrementalLoading(info.mLastLaunchedActivity.packageName,
                            info.mLastLaunchedActivity.mUserId);
        }
        FrameworkStatsLog.write(
                FrameworkStatsLog.APP_START_FULLY_DRAWN,
                info.mLastLaunchedActivity.info.applicationInfo.uid,
                info.mLastLaunchedActivity.packageName,
                restoredFromBundle
                        ? FrameworkStatsLog.APP_START_FULLY_DRAWN__TYPE__WITH_BUNDLE
                        : FrameworkStatsLog.APP_START_FULLY_DRAWN__TYPE__WITHOUT_BUNDLE,
                info.mLastLaunchedActivity.info.name,
                info.mProcessRunning,
                startupTimeMs,
                packageOptimizationInfo.getCompilationReason(),
                packageOptimizationInfo.getCompilationFilter(),
                info.mSourceType,
                info.mSourceEventDelayMs,
                isIncremental,
                isLoading,
                info.mLastLaunchedActivity.info.name.hashCode());

        // Ends the trace started at the beginning of this function. This is located here to allow
        // the trace slice to have a noticable duration.
        Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);

        // Notify reportFullyDrawn event.
        launchObserverNotifyReportFullyDrawn(r, currentTimestampNs);

        return infoSnapshot;
    }

    private void logAppFullyDrawn(TransitionInfoSnapshot info) {
        if (info.type != TYPE_TRANSITION_WARM_LAUNCH && info.type != TYPE_TRANSITION_COLD_LAUNCH) {
            return;
        }

        StringBuilder sb = mStringBuilder;
        sb.setLength(0);
        sb.append("Fully drawn ");
        sb.append(info.launchedActivityShortComponentName);
        sb.append(": ");
        TimeUtils.formatDuration(info.windowsFullyDrawnDelayMs, sb);
        Log.i(TAG, sb.toString());
    }

    void logAbortedBgActivityStart(Intent intent, WindowProcessController callerApp,
            int callingUid, String callingPackage, int callingUidProcState,
            boolean callingUidHasAnyVisibleWindow,
            int realCallingUid, int realCallingUidProcState,
            boolean realCallingUidHasAnyVisibleWindow,
            boolean comingFromPendingIntent) {

        final long nowElapsed = SystemClock.elapsedRealtime();
        final long nowUptime = SystemClock.uptimeMillis();
        final LogMaker builder = new LogMaker(ACTION_ACTIVITY_START);
        builder.setTimestamp(System.currentTimeMillis());
        builder.addTaggedData(FIELD_CALLING_UID, callingUid);
        builder.addTaggedData(FIELD_CALLING_PACKAGE_NAME, callingPackage);
        builder.addTaggedData(FIELD_CALLING_UID_PROC_STATE,
                processStateAmToProto(callingUidProcState));
        builder.addTaggedData(FIELD_CALLING_UID_HAS_ANY_VISIBLE_WINDOW,
                callingUidHasAnyVisibleWindow ? 1 : 0);
        builder.addTaggedData(FIELD_REAL_CALLING_UID, realCallingUid);
        builder.addTaggedData(FIELD_REAL_CALLING_UID_PROC_STATE,
                processStateAmToProto(realCallingUidProcState));
        builder.addTaggedData(FIELD_REAL_CALLING_UID_HAS_ANY_VISIBLE_WINDOW,
                realCallingUidHasAnyVisibleWindow ? 1 : 0);
        builder.addTaggedData(FIELD_COMING_FROM_PENDING_INTENT, comingFromPendingIntent ? 1 : 0);
        if (intent != null) {
            builder.addTaggedData(FIELD_INTENT_ACTION, intent.getAction());
            ComponentName component = intent.getComponent();
            if (component != null) {
                builder.addTaggedData(FIELD_TARGET_SHORT_COMPONENT_NAME,
                        component.flattenToShortString());
            }
        }
        if (callerApp != null) {
            builder.addTaggedData(FIELD_PROCESS_RECORD_PROCESS_NAME, callerApp.mName);
            builder.addTaggedData(FIELD_PROCESS_RECORD_CUR_PROC_STATE,
                    processStateAmToProto(callerApp.getCurrentProcState()));
            builder.addTaggedData(FIELD_PROCESS_RECORD_HAS_CLIENT_ACTIVITIES,
                    callerApp.hasClientActivities() ? 1 : 0);
            builder.addTaggedData(FIELD_PROCESS_RECORD_HAS_FOREGROUND_SERVICES,
                    callerApp.hasForegroundServices() ? 1 : 0);
            builder.addTaggedData(FIELD_PROCESS_RECORD_HAS_FOREGROUND_ACTIVITIES,
                    callerApp.hasForegroundActivities() ? 1 : 0);
            builder.addTaggedData(FIELD_PROCESS_RECORD_HAS_TOP_UI, callerApp.hasTopUi() ? 1 : 0);
            builder.addTaggedData(FIELD_PROCESS_RECORD_HAS_OVERLAY_UI,
                    callerApp.hasOverlayUi() ? 1 : 0);
            builder.addTaggedData(FIELD_PROCESS_RECORD_PENDING_UI_CLEAN,
                    callerApp.hasPendingUiClean() ? 1 : 0);
            if (callerApp.getInteractionEventTime() != 0) {
                builder.addTaggedData(FIELD_PROCESS_RECORD_MILLIS_SINCE_LAST_INTERACTION_EVENT,
                        (nowElapsed - callerApp.getInteractionEventTime()));
            }
            if (callerApp.getFgInteractionTime() != 0) {
                builder.addTaggedData(FIELD_PROCESS_RECORD_MILLIS_SINCE_FG_INTERACTION,
                        (nowElapsed - callerApp.getFgInteractionTime()));
            }
            if (callerApp.getWhenUnimportant() != 0) {
                builder.addTaggedData(FIELD_PROCESS_RECORD_MILLIS_SINCE_UNIMPORTANT,
                        (nowUptime - callerApp.getWhenUnimportant()));
            }
        }
        mMetricsLogger.write(builder);
    }

    private void logAppStartMemoryStateCapture(TransitionInfoSnapshot info) {
        if (info.processRecord == null) {
            if (DEBUG_METRICS) Slog.i(TAG, "logAppStartMemoryStateCapture processRecord null");
            return;
        }

        final int pid = info.processRecord.getPid();
        final int uid = info.applicationInfo.uid;
        final MemoryStat memoryStat = readMemoryStatFromFilesystem(uid, pid);
        if (memoryStat == null) {
            if (DEBUG_METRICS) Slog.i(TAG, "logAppStartMemoryStateCapture memoryStat null");
            return;
        }

        FrameworkStatsLog.write(
                FrameworkStatsLog.APP_START_MEMORY_STATE_CAPTURED,
                uid,
                info.processName,
                info.launchedActivityName,
                memoryStat.pgfault,
                memoryStat.pgmajfault,
                memoryStat.rssInBytes,
                memoryStat.cacheInBytes,
                memoryStat.swapInBytes);
    }

    private ArtManagerInternal getArtManagerInternal() {
        if (mArtManagerInternal == null) {
            // Note that this may be null.
            // ArtManagerInternal is registered during PackageManagerService
            // initialization which happens after ActivityManagerService.
            mArtManagerInternal = LocalServices.getService(ArtManagerInternal.class);
        }
        return mArtManagerInternal;
    }

    /** Starts trace for an activity is actually launching. */
    private void startLaunchTrace(@NonNull TransitionInfo info) {
        if (DEBUG_METRICS) Slog.i(TAG, "startLaunchTrace " + info);
        if (!Trace.isTagEnabled(Trace.TRACE_TAG_ACTIVITY_MANAGER)) {
            return;
        }
        info.mLaunchTraceName = "launching: " + info.mLastLaunchedActivity.packageName;
        Trace.asyncTraceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER, info.mLaunchTraceName,
                (int) info.mTransitionStartTimeNs /* cookie */);
    }

    /** Stops trace for the launch is completed or cancelled. */
    private void stopLaunchTrace(@NonNull TransitionInfo info) {
        if (DEBUG_METRICS) Slog.i(TAG, "stopLaunchTrace " + info);
        if (info.mLaunchTraceName == null) {
            return;
        }
        Trace.asyncTraceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER, info.mLaunchTraceName,
                (int) info.mTransitionStartTimeNs /* cookie */);
        info.mLaunchTraceName = null;
    }

    public ActivityMetricsLaunchObserverRegistry getLaunchObserverRegistry() {
        return mLaunchObserver;
    }

    /** Notify the {@link ActivityMetricsLaunchObserver} that a new launch sequence has begun. */
    private void launchObserverNotifyIntentStarted(Intent intent, long timestampNs) {
        Trace.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER,
                "MetricsLogger:launchObserverNotifyIntentStarted");

        // Beginning a launch is timing sensitive and so should be observed as soon as possible.
        mLaunchObserver.onIntentStarted(intent, timestampNs);

        Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);
    }

    /**
     * Notify the {@link ActivityMetricsLaunchObserver} that the previous launch sequence has
     * aborted due to intent failure (e.g. intent resolve failed or security error, etc) or
     * intent being delivered to the top running activity.
     */
    private void launchObserverNotifyIntentFailed() {
       Trace.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER,
                "MetricsLogger:launchObserverNotifyIntentFailed");

        mLaunchObserver.onIntentFailed();

        Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);
    }

    /**
     * Notify the {@link ActivityMetricsLaunchObserver} that the current launch sequence's activity
     * has started.
     */
    private void launchObserverNotifyActivityLaunched(TransitionInfo info) {
        Trace.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER,
                "MetricsLogger:launchObserverNotifyActivityLaunched");

        @ActivityMetricsLaunchObserver.Temperature int temperature =
                convertTransitionTypeToLaunchObserverTemperature(info.mTransitionType);

        // Beginning a launch is timing sensitive and so should be observed as soon as possible.
        mLaunchObserver.onActivityLaunched(convertActivityRecordToProto(info.mLastLaunchedActivity),
                temperature);

        Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);
    }

    /**
     * Notifies the {@link ActivityMetricsLaunchObserver} the reportFullDrawn event.
     */
    private void launchObserverNotifyReportFullyDrawn(ActivityRecord r, long timestampNs) {
        Trace.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER,
            "MetricsLogger:launchObserverNotifyReportFullyDrawn");
        mLaunchObserver.onReportFullyDrawn(convertActivityRecordToProto(r), timestampNs);
        Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);
    }

    /**
     * Notify the {@link ActivityMetricsLaunchObserver} that the current launch sequence is
     * cancelled.
     */
    private void launchObserverNotifyActivityLaunchCancelled(TransitionInfo info) {
        Trace.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER,
                "MetricsLogger:launchObserverNotifyActivityLaunchCancelled");

        final @ActivityMetricsLaunchObserver.ActivityRecordProto byte[] activityRecordProto =
                info != null ? convertActivityRecordToProto(info.mLastLaunchedActivity) : null;

        mLaunchObserver.onActivityLaunchCancelled(activityRecordProto);

        Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);
    }

    /**
     * Notify the {@link ActivityMetricsLaunchObserver} that the current launch sequence's activity
     * has fully finished (successfully).
     */
    private void launchObserverNotifyActivityLaunchFinished(TransitionInfo info, long timestampNs) {
        Trace.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER,
                "MetricsLogger:launchObserverNotifyActivityLaunchFinished");

        mLaunchObserver.onActivityLaunchFinished(
                convertActivityRecordToProto(info.mLastLaunchedActivity), timestampNs);

        Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);
    }

    @VisibleForTesting
    static @ActivityMetricsLaunchObserver.ActivityRecordProto byte[]
            convertActivityRecordToProto(ActivityRecord record) {
        // May take non-negligible amount of time to convert ActivityRecord into a proto,
        // so track the time.
        Trace.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER,
                "MetricsLogger:convertActivityRecordToProto");

        // There does not appear to be a way to 'reset' a ProtoOutputBuffer stream,
        // so create a new one every time.
        final ProtoOutputStream protoOutputStream =
                new ProtoOutputStream(LAUNCH_OBSERVER_ACTIVITY_RECORD_PROTO_CHUNK_SIZE);
        // Write this data out as the top-most ActivityRecordProto (i.e. it is not a sub-object).
        record.dumpDebug(protoOutputStream, WindowTraceLogLevel.ALL);
        final byte[] bytes = protoOutputStream.getBytes();

        Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);

        return bytes;
    }

    private static @ActivityMetricsLaunchObserver.Temperature int
            convertTransitionTypeToLaunchObserverTemperature(int transitionType) {
        switch (transitionType) {
            case TYPE_TRANSITION_WARM_LAUNCH:
                return ActivityMetricsLaunchObserver.TEMPERATURE_WARM;
            case TYPE_TRANSITION_HOT_LAUNCH:
                return ActivityMetricsLaunchObserver.TEMPERATURE_HOT;
            case TYPE_TRANSITION_COLD_LAUNCH:
                return ActivityMetricsLaunchObserver.TEMPERATURE_COLD;
            default:
                return -1;
        }
    }
}
