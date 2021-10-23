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

import static android.content.Intent.FLAG_RECEIVER_REGISTERED_ONLY;

import static com.android.internal.jank.FrameTracker.ChoreographerWrapper;
import static com.android.internal.jank.FrameTracker.REASON_CANCEL_NORMAL;
import static com.android.internal.jank.FrameTracker.REASON_CANCEL_NOT_BEGUN;
import static com.android.internal.jank.FrameTracker.REASON_END_NORMAL;
import static com.android.internal.jank.FrameTracker.SurfaceControlWrapper;
import static com.android.internal.util.FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__LAUNCHER_ALL_APPS_SCROLL;
import static com.android.internal.util.FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__LAUNCHER_APP_CLOSE_TO_HOME;
import static com.android.internal.util.FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__LAUNCHER_APP_CLOSE_TO_PIP;
import static com.android.internal.util.FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__LAUNCHER_APP_LAUNCH_FROM_ICON;
import static com.android.internal.util.FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__LAUNCHER_APP_LAUNCH_FROM_RECENTS;
import static com.android.internal.util.FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__LAUNCHER_APP_LAUNCH_FROM_WIDGET;
import static com.android.internal.util.FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__LAUNCHER_OPEN_ALL_APPS;
import static com.android.internal.util.FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__LAUNCHER_QUICK_SWITCH;
import static com.android.internal.util.FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__LOCKSCREEN_PASSWORD_APPEAR;
import static com.android.internal.util.FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__LOCKSCREEN_PASSWORD_DISAPPEAR;
import static com.android.internal.util.FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__LOCKSCREEN_PATTERN_APPEAR;
import static com.android.internal.util.FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__LOCKSCREEN_PATTERN_DISAPPEAR;
import static com.android.internal.util.FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__LOCKSCREEN_PIN_APPEAR;
import static com.android.internal.util.FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__LOCKSCREEN_PIN_DISAPPEAR;
import static com.android.internal.util.FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__LOCKSCREEN_TRANSITION_FROM_AOD;
import static com.android.internal.util.FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__LOCKSCREEN_TRANSITION_TO_AOD;
import static com.android.internal.util.FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__LOCKSCREEN_UNLOCK_ANIMATION;
import static com.android.internal.util.FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__NOTIFICATION_SHADE_SWIPE;
import static com.android.internal.util.FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SETTINGS_PAGE_SCROLL;
import static com.android.internal.util.FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SHADE_APP_LAUNCH;
import static com.android.internal.util.FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SHADE_APP_LAUNCH_FROM_HISTORY_BUTTON;
import static com.android.internal.util.FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SHADE_APP_LAUNCH_FROM_MEDIA_PLAYER;
import static com.android.internal.util.FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SHADE_APP_LAUNCH_FROM_QS_TILE;
import static com.android.internal.util.FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SHADE_APP_LAUNCH_FROM_SETTINGS_BUTTON;
import static com.android.internal.util.FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SHADE_EXPAND_COLLAPSE_LOCK;
import static com.android.internal.util.FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SHADE_HEADS_UP_APPEAR;
import static com.android.internal.util.FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SHADE_HEADS_UP_DISAPPEAR;
import static com.android.internal.util.FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SHADE_NOTIFICATION_ADD;
import static com.android.internal.util.FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SHADE_NOTIFICATION_REMOVE;
import static com.android.internal.util.FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SHADE_QS_EXPAND_COLLAPSE;
import static com.android.internal.util.FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SHADE_QS_SCROLL_SWIPE;
import static com.android.internal.util.FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SHADE_ROW_EXPAND;
import static com.android.internal.util.FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SHADE_ROW_SWIPE;
import static com.android.internal.util.FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SHADE_SCROLL_FLING;
import static com.android.internal.util.FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__STATUS_BAR_APP_LAUNCH_FROM_CALL_CHIP;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.HandlerExecutor;
import android.os.HandlerThread;
import android.os.SystemProperties;
import android.provider.DeviceConfig;
import android.text.TextUtils;
import android.util.Log;
import android.util.SparseArray;
import android.view.Choreographer;
import android.view.View;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.jank.FrameTracker.FrameMetricsWrapper;
import com.android.internal.jank.FrameTracker.FrameTrackerListener;
import com.android.internal.jank.FrameTracker.ThreadedRendererWrapper;
import com.android.internal.jank.FrameTracker.ViewRootWrapper;
import com.android.internal.util.PerfettoTrigger;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * This class let users to begin and end the always on tracing mechanism.
 *
 * Enabling for local development:
 *
 * adb shell device_config put interaction_jank_monitor enabled true
 * adb shell device_config put interaction_jank_monitor sampling_interval 1
 *
 * @hide
 */
public class InteractionJankMonitor {
    private static final String TAG = InteractionJankMonitor.class.getSimpleName();
    private static final boolean DEBUG = false;
    private static final String ACTION_PREFIX = InteractionJankMonitor.class.getCanonicalName();

    private static final String DEFAULT_WORKER_NAME = TAG + "-Worker";
    private static final long DEFAULT_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(5L);
    private static final String SETTINGS_ENABLED_KEY = "enabled";
    private static final String SETTINGS_SAMPLING_INTERVAL_KEY = "sampling_interval";
    private static final String SETTINGS_THRESHOLD_MISSED_FRAMES_KEY =
            "trace_threshold_missed_frames";
    private static final String SETTINGS_THRESHOLD_FRAME_TIME_MILLIS_KEY =
            "trace_threshold_frame_time_millis";
    /** Default to being enabled on debug builds. */
    private static final boolean DEFAULT_ENABLED = Build.IS_DEBUGGABLE;
    /** Default to collecting data for all CUJs. */
    private static final int DEFAULT_SAMPLING_INTERVAL = 1;
    /** Default to triggering trace if 3 frames are missed OR a frame takes at least 64ms */
    private static final int DEFAULT_TRACE_THRESHOLD_MISSED_FRAMES = 3;
    private static final int DEFAULT_TRACE_THRESHOLD_FRAME_TIME_MILLIS = 64;

    public static final String ACTION_SESSION_BEGIN = ACTION_PREFIX + ".ACTION_SESSION_BEGIN";
    public static final String ACTION_SESSION_END = ACTION_PREFIX + ".ACTION_SESSION_END";
    public static final String ACTION_SESSION_CANCEL = ACTION_PREFIX + ".ACTION_SESSION_CANCEL";
    public static final String ACTION_METRICS_LOGGED = ACTION_PREFIX + ".ACTION_METRICS_LOGGED";
    public static final String BUNDLE_KEY_CUJ_NAME = ACTION_PREFIX + ".CUJ_NAME";
    public static final String BUNDLE_KEY_TIMESTAMP = ACTION_PREFIX + ".TIMESTAMP";
    @VisibleForTesting
    public static final String PROP_NOTIFY_CUJ_EVENT = "debug.jank.notify_cuj_events";

    // Every value must have a corresponding entry in CUJ_STATSD_INTERACTION_TYPE.
    public static final int CUJ_NOTIFICATION_SHADE_EXPAND_COLLAPSE = 0;
    public static final int CUJ_NOTIFICATION_SHADE_EXPAND_COLLAPSE_LOCK = 1;
    public static final int CUJ_NOTIFICATION_SHADE_SCROLL_FLING = 2;
    public static final int CUJ_NOTIFICATION_SHADE_ROW_EXPAND = 3;
    public static final int CUJ_NOTIFICATION_SHADE_ROW_SWIPE = 4;
    public static final int CUJ_NOTIFICATION_SHADE_QS_EXPAND_COLLAPSE = 5;
    public static final int CUJ_NOTIFICATION_SHADE_QS_SCROLL_SWIPE = 6;
    public static final int CUJ_LAUNCHER_APP_LAUNCH_FROM_RECENTS = 7;
    public static final int CUJ_LAUNCHER_APP_LAUNCH_FROM_ICON = 8;
    public static final int CUJ_LAUNCHER_APP_CLOSE_TO_HOME = 9;
    public static final int CUJ_LAUNCHER_APP_CLOSE_TO_PIP = 10;
    public static final int CUJ_LAUNCHER_QUICK_SWITCH = 11;
    public static final int CUJ_NOTIFICATION_HEADS_UP_APPEAR = 12;
    public static final int CUJ_NOTIFICATION_HEADS_UP_DISAPPEAR = 13;
    public static final int CUJ_NOTIFICATION_ADD = 14;
    public static final int CUJ_NOTIFICATION_REMOVE = 15;
    public static final int CUJ_NOTIFICATION_APP_START = 16;
    public static final int CUJ_LOCKSCREEN_PASSWORD_APPEAR = 17;
    public static final int CUJ_LOCKSCREEN_PATTERN_APPEAR = 18;
    public static final int CUJ_LOCKSCREEN_PIN_APPEAR = 19;
    public static final int CUJ_LOCKSCREEN_PASSWORD_DISAPPEAR = 20;
    public static final int CUJ_LOCKSCREEN_PATTERN_DISAPPEAR = 21;
    public static final int CUJ_LOCKSCREEN_PIN_DISAPPEAR = 22;
    public static final int CUJ_LOCKSCREEN_TRANSITION_FROM_AOD = 23;
    public static final int CUJ_LOCKSCREEN_TRANSITION_TO_AOD = 24;
    public static final int CUJ_LAUNCHER_OPEN_ALL_APPS = 25;
    public static final int CUJ_LAUNCHER_ALL_APPS_SCROLL = 26;
    public static final int CUJ_LAUNCHER_APP_LAUNCH_FROM_WIDGET = 27;
    public static final int CUJ_SETTINGS_PAGE_SCROLL = 28;
    public static final int CUJ_LOCKSCREEN_UNLOCK_ANIMATION = 29;
    public static final int CUJ_SHADE_APP_LAUNCH_FROM_HISTORY_BUTTON = 30;
    public static final int CUJ_SHADE_APP_LAUNCH_FROM_MEDIA_PLAYER = 31;
    public static final int CUJ_SHADE_APP_LAUNCH_FROM_QS_TILE = 32;
    public static final int CUJ_SHADE_APP_LAUNCH_FROM_SETTINGS_BUTTON = 33;
    public static final int CUJ_STATUS_BAR_APP_LAUNCH_FROM_CALL_CHIP = 34;

    private static final int NO_STATSD_LOGGING = -1;

    // Used to convert CujType to InteractionType enum value for statsd logging.
    // Use NO_STATSD_LOGGING in case the measurement for a given CUJ should not be logged to statsd.
    @VisibleForTesting
    public static final int[] CUJ_TO_STATSD_INTERACTION_TYPE = {
            // This should be mapping to CUJ_NOTIFICATION_SHADE_EXPAND_COLLAPSE.
            UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__NOTIFICATION_SHADE_SWIPE,
            UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SHADE_EXPAND_COLLAPSE_LOCK,
            UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SHADE_SCROLL_FLING,
            UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SHADE_ROW_EXPAND,
            UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SHADE_ROW_SWIPE,
            UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SHADE_QS_EXPAND_COLLAPSE,
            UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SHADE_QS_SCROLL_SWIPE,
            UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__LAUNCHER_APP_LAUNCH_FROM_RECENTS,
            UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__LAUNCHER_APP_LAUNCH_FROM_ICON,
            UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__LAUNCHER_APP_CLOSE_TO_HOME,
            UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__LAUNCHER_APP_CLOSE_TO_PIP,
            UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__LAUNCHER_QUICK_SWITCH,
            UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SHADE_HEADS_UP_APPEAR,
            UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SHADE_HEADS_UP_DISAPPEAR,
            UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SHADE_NOTIFICATION_ADD,
            UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SHADE_NOTIFICATION_REMOVE,
            UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SHADE_APP_LAUNCH,
            UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__LOCKSCREEN_PASSWORD_APPEAR,
            UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__LOCKSCREEN_PATTERN_APPEAR,
            UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__LOCKSCREEN_PIN_APPEAR,
            UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__LOCKSCREEN_PASSWORD_DISAPPEAR,
            UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__LOCKSCREEN_PATTERN_DISAPPEAR,
            UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__LOCKSCREEN_PIN_DISAPPEAR,
            UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__LOCKSCREEN_TRANSITION_FROM_AOD,
            UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__LOCKSCREEN_TRANSITION_TO_AOD,
            UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__LAUNCHER_OPEN_ALL_APPS,
            UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__LAUNCHER_ALL_APPS_SCROLL,
            UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__LAUNCHER_APP_LAUNCH_FROM_WIDGET,
            UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SETTINGS_PAGE_SCROLL,
            UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__LOCKSCREEN_UNLOCK_ANIMATION,
            UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SHADE_APP_LAUNCH_FROM_HISTORY_BUTTON,
            UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SHADE_APP_LAUNCH_FROM_MEDIA_PLAYER,
            UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SHADE_APP_LAUNCH_FROM_QS_TILE,
            UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SHADE_APP_LAUNCH_FROM_SETTINGS_BUTTON,
            UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__STATUS_BAR_APP_LAUNCH_FROM_CALL_CHIP,
    };

    private static volatile InteractionJankMonitor sInstance;

    private final DeviceConfig.OnPropertiesChangedListener mPropertiesChangedListener =
            this::updateProperties;

    private FrameMetricsWrapper mMetrics;
    private SparseArray<FrameTracker> mRunningTrackers;
    private SparseArray<Runnable> mTimeoutActions;
    private HandlerThread mWorker;

    private boolean mEnabled = DEFAULT_ENABLED;
    private int mSamplingInterval = DEFAULT_SAMPLING_INTERVAL;
    private int mTraceThresholdMissedFrames = DEFAULT_TRACE_THRESHOLD_MISSED_FRAMES;
    private int mTraceThresholdFrameTimeMillis = DEFAULT_TRACE_THRESHOLD_FRAME_TIME_MILLIS;

    /** @hide */
    @IntDef({
            CUJ_NOTIFICATION_SHADE_EXPAND_COLLAPSE,
            CUJ_NOTIFICATION_SHADE_EXPAND_COLLAPSE_LOCK,
            CUJ_NOTIFICATION_SHADE_SCROLL_FLING,
            CUJ_NOTIFICATION_SHADE_ROW_EXPAND,
            CUJ_NOTIFICATION_SHADE_ROW_SWIPE,
            CUJ_NOTIFICATION_SHADE_QS_EXPAND_COLLAPSE,
            CUJ_NOTIFICATION_SHADE_QS_SCROLL_SWIPE,
            CUJ_LAUNCHER_APP_LAUNCH_FROM_RECENTS,
            CUJ_LAUNCHER_APP_LAUNCH_FROM_ICON,
            CUJ_LAUNCHER_APP_CLOSE_TO_HOME,
            CUJ_LAUNCHER_APP_CLOSE_TO_PIP,
            CUJ_LAUNCHER_QUICK_SWITCH,
            CUJ_NOTIFICATION_HEADS_UP_APPEAR,
            CUJ_NOTIFICATION_HEADS_UP_DISAPPEAR,
            CUJ_NOTIFICATION_ADD,
            CUJ_NOTIFICATION_REMOVE,
            CUJ_NOTIFICATION_APP_START,
            CUJ_LOCKSCREEN_PASSWORD_APPEAR,
            CUJ_LOCKSCREEN_PATTERN_APPEAR,
            CUJ_LOCKSCREEN_PIN_APPEAR,
            CUJ_LOCKSCREEN_PASSWORD_DISAPPEAR,
            CUJ_LOCKSCREEN_PATTERN_DISAPPEAR,
            CUJ_LOCKSCREEN_PIN_DISAPPEAR,
            CUJ_LOCKSCREEN_TRANSITION_FROM_AOD,
            CUJ_LOCKSCREEN_TRANSITION_TO_AOD,
            CUJ_LAUNCHER_OPEN_ALL_APPS,
            CUJ_LAUNCHER_ALL_APPS_SCROLL,
            CUJ_LAUNCHER_APP_LAUNCH_FROM_WIDGET,
            CUJ_SETTINGS_PAGE_SCROLL,
            CUJ_LOCKSCREEN_UNLOCK_ANIMATION,
            CUJ_SHADE_APP_LAUNCH_FROM_HISTORY_BUTTON,
            CUJ_SHADE_APP_LAUNCH_FROM_MEDIA_PLAYER,
            CUJ_SHADE_APP_LAUNCH_FROM_QS_TILE,
            CUJ_SHADE_APP_LAUNCH_FROM_SETTINGS_BUTTON,
            CUJ_STATUS_BAR_APP_LAUNCH_FROM_CALL_CHIP,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface CujType {
    }

    /**
     * Get the singleton of InteractionJankMonitor.
     *
     * @return instance of InteractionJankMonitor
     */
    public static InteractionJankMonitor getInstance() {
        // Use DCL here since this method might be invoked very often.
        if (sInstance == null) {
            synchronized (InteractionJankMonitor.class) {
                if (sInstance == null) {
                    sInstance = new InteractionJankMonitor(new HandlerThread(DEFAULT_WORKER_NAME));
                }
            }
        }
        return sInstance;
    }

    /**
     * This constructor should be only public to tests.
     *
     * @param worker the worker thread for the callbacks
     */
    @VisibleForTesting
    public InteractionJankMonitor(@NonNull HandlerThread worker) {
        mRunningTrackers = new SparseArray<>();
        mTimeoutActions = new SparseArray<>();
        mWorker = worker;
        mMetrics = new FrameMetricsWrapper();
        mWorker.start();
        mEnabled = DEFAULT_ENABLED;
        mSamplingInterval = DEFAULT_SAMPLING_INTERVAL;

        // Post initialization to the background in case we're running on the main
        // thread.
        mWorker.getThreadHandler().post(
                () -> mPropertiesChangedListener.onPropertiesChanged(
                        DeviceConfig.getProperties(
                                DeviceConfig.NAMESPACE_INTERACTION_JANK_MONITOR)));
        DeviceConfig.addOnPropertiesChangedListener(
                DeviceConfig.NAMESPACE_INTERACTION_JANK_MONITOR,
                new HandlerExecutor(mWorker.getThreadHandler()),
                mPropertiesChangedListener);
    }

    /**
     * Create a {@link FrameTracker} instance.
     *
     * @param session the session associates with this tracker
     * @return instance of the FrameTracker
     */
    @VisibleForTesting
    public FrameTracker createFrameTracker(Configuration conf, Session session) {
        final View v = conf.mView;
        final Context c = v.getContext().getApplicationContext();
        final ThreadedRendererWrapper r = new ThreadedRendererWrapper(v.getThreadedRenderer());
        final ViewRootWrapper vr = new ViewRootWrapper(v.getViewRootImpl());
        final SurfaceControlWrapper sc = new SurfaceControlWrapper();
        final ChoreographerWrapper cg = new ChoreographerWrapper(Choreographer.getInstance());

        synchronized (this) {
            FrameTrackerListener eventsListener = (s, act) -> handleCujEvents(c, act, s);
            return new FrameTracker(session, mWorker.getThreadHandler(), r, vr, sc, cg, mMetrics,
                    mTraceThresholdMissedFrames, mTraceThresholdFrameTimeMillis, eventsListener);
        }
    }

    private void handleCujEvents(Context context, String action, Session session) {
        // Clear the running and timeout tasks if the end / cancel was fired within the tracker.
        // Or we might have memory leaks.
        if (needRemoveTasks(action, session)) {
            removeTimeout(session.getCuj());
            removeTracker(session.getCuj());
        }

        // Notify the receivers if necessary.
        if (session.shouldNotify()) {
            notifyEvents(context, action, session);
        }
    }

    private boolean needRemoveTasks(String action, Session session) {
        final boolean badEnd = action.equals(ACTION_SESSION_END)
                && session.getReason() != REASON_END_NORMAL;
        final boolean badCancel = action.equals(ACTION_SESSION_CANCEL)
                && session.getReason() != REASON_CANCEL_NORMAL;
        return badEnd || badCancel;
    }

    private void notifyEvents(Context context, String action, Session session) {
        if (action.equals(ACTION_SESSION_CANCEL)
                && session.getReason() == REASON_CANCEL_NOT_BEGUN) {
            return;
        }
        Intent intent = new Intent(action);
        intent.putExtra(BUNDLE_KEY_CUJ_NAME, getNameOfCuj(session.getCuj()));
        intent.putExtra(BUNDLE_KEY_TIMESTAMP, session.getTimeStamp());
        intent.addFlags(FLAG_RECEIVER_REGISTERED_ONLY);
        context.sendBroadcast(intent);
    }

    private void removeTimeout(@CujType int cujType) {
        synchronized (this) {
            Runnable timeout = mTimeoutActions.get(cujType);
            if (timeout != null) {
                mWorker.getThreadHandler().removeCallbacks(timeout);
                mTimeoutActions.remove(cujType);
            }
        }
    }

    /**
     * Begin a trace session.
     *
     * @param v an attached view.
     * @param cujType the specific {@link InteractionJankMonitor.CujType}.
     * @return boolean true if the tracker is started successfully, false otherwise.
     */
    public boolean begin(View v, @CujType int cujType) {
        try {
            return beginInternal(
                    new Configuration.Builder(cujType)
                            .setView(v)
                            .build());
        } catch (IllegalArgumentException ex) {
            Log.d(TAG, "Build configuration failed!", ex);
            return false;
        }
    }

    /**
     * Begin a trace session.
     *
     * @param builder the builder of the configurations for instrumenting the CUJ.
     * @return boolean true if the tracker is started successfully, false otherwise.
     */
    public boolean begin(@NonNull Configuration.Builder builder) {
        try {
            return beginInternal(builder.build());
        } catch (IllegalArgumentException ex) {
            Log.d(TAG, "Build configuration failed!", ex);
            return false;
        }
    }

    private boolean beginInternal(@NonNull Configuration conf) {
        synchronized (this) {
            int cujType = conf.mCujType;
            boolean shouldSample = ThreadLocalRandom.current().nextInt() % mSamplingInterval == 0;
            if (!mEnabled || !shouldSample) {
                if (DEBUG) {
                    Log.d(TAG, "Skip monitoring cuj: " + getNameOfCuj(cujType)
                            + ", enable=" + mEnabled + ", debuggable=" + DEFAULT_ENABLED
                            + ", sample=" + shouldSample + ", interval=" + mSamplingInterval);
                }
                return false;
            }
            FrameTracker tracker = getTracker(cujType);
            // Skip subsequent calls if we already have an ongoing tracing.
            if (tracker != null) return false;

            // begin a new trace session.
            tracker = createFrameTracker(conf, new Session(cujType, conf.mTag));
            mRunningTrackers.put(cujType, tracker);
            tracker.begin();

            // Cancel the trace if we don't get an end() call in specified duration.
            Runnable timeoutAction = () -> cancel(cujType);
            mTimeoutActions.put(cujType, timeoutAction);
            mWorker.getThreadHandler().postDelayed(timeoutAction, conf.mTimeout);
            return true;
        }
    }

    /**
     * End a trace session.
     *
     * @param cujType the specific {@link InteractionJankMonitor.CujType}.
     * @return boolean true if the tracker is ended successfully, false otherwise.
     */
    public boolean end(@CujType int cujType) {
        //TODO (163505250): This should be no-op if not in droid food rom.
        synchronized (this) {

            // remove the timeout action first.
            removeTimeout(cujType);
            FrameTracker tracker = getTracker(cujType);
            // Skip this call since we haven't started a trace yet.
            if (tracker == null) return false;
            tracker.end(FrameTracker.REASON_END_NORMAL);
            removeTracker(cujType);
            return true;
        }
    }

    /**
     * Cancel the trace session.
     *
     * @return boolean true if the tracker is cancelled successfully, false otherwise.
     */
    public boolean cancel(@CujType int cujType) {
        //TODO (163505250): This should be no-op if not in droid food rom.
        synchronized (this) {
            // remove the timeout action first.
            removeTimeout(cujType);
            FrameTracker tracker = getTracker(cujType);
            // Skip this call since we haven't started a trace yet.
            if (tracker == null) return false;
            tracker.cancel(FrameTracker.REASON_CANCEL_NORMAL);
            removeTracker(cujType);
            return true;
        }
    }

    private FrameTracker getTracker(@CujType int cuj) {
        synchronized (this) {
            return mRunningTrackers.get(cuj);
        }
    }

    private void removeTracker(@CujType int cuj) {
        synchronized (this) {
            mRunningTrackers.remove(cuj);
        }
    }

    private void updateProperties(DeviceConfig.Properties properties) {
        synchronized (this) {
            mSamplingInterval = properties.getInt(SETTINGS_SAMPLING_INTERVAL_KEY,
                    DEFAULT_SAMPLING_INTERVAL);
            mEnabled = properties.getBoolean(SETTINGS_ENABLED_KEY, DEFAULT_ENABLED);
            mTraceThresholdMissedFrames = properties.getInt(SETTINGS_THRESHOLD_MISSED_FRAMES_KEY,
                    DEFAULT_TRACE_THRESHOLD_MISSED_FRAMES);
            mTraceThresholdFrameTimeMillis = properties.getInt(
                    SETTINGS_THRESHOLD_FRAME_TIME_MILLIS_KEY,
                    DEFAULT_TRACE_THRESHOLD_FRAME_TIME_MILLIS);
        }
    }

    @VisibleForTesting
    public DeviceConfig.OnPropertiesChangedListener getPropertiesChangedListener() {
        return mPropertiesChangedListener;
    }

    /**
     * Trigger the perfetto daemon to collect and upload data.
     */
    @VisibleForTesting
    public void trigger(Session session) {
        synchronized (this) {
            mWorker.getThreadHandler().post(
                    () -> PerfettoTrigger.trigger(session.getPerfettoTrigger()));
        }
    }

    /**
     * A helper method to translate interaction type to CUJ name.
     *
     * @param interactionType the interaction type defined in AtomsProto.java
     * @return the name of the interaction type
     */
    public static String getNameOfInteraction(int interactionType) {
        // There is an offset amount of 1 between cujType and interactionType.
        return getNameOfCuj(interactionType - 1);
    }

    /**
     * A helper method to translate CUJ type to CUJ name.
     *
     * @param cujType the cuj type defined in this file
     * @return the name of the cuj type
     */
    public static String getNameOfCuj(int cujType) {
        switch (cujType) {
            case CUJ_NOTIFICATION_SHADE_EXPAND_COLLAPSE:
                return "SHADE_EXPAND_COLLAPSE";
            case CUJ_NOTIFICATION_SHADE_EXPAND_COLLAPSE_LOCK:
                return "SHADE_EXPAND_COLLAPSE_LOCK";
            case CUJ_NOTIFICATION_SHADE_SCROLL_FLING:
                return "SHADE_SCROLL_FLING";
            case CUJ_NOTIFICATION_SHADE_ROW_EXPAND:
                return "SHADE_ROW_EXPAND";
            case CUJ_NOTIFICATION_SHADE_ROW_SWIPE:
                return "SHADE_ROW_SWIPE";
            case CUJ_NOTIFICATION_SHADE_QS_EXPAND_COLLAPSE:
                return "SHADE_QS_EXPAND_COLLAPSE";
            case CUJ_NOTIFICATION_SHADE_QS_SCROLL_SWIPE:
                return "SHADE_QS_SCROLL_SWIPE";
            case CUJ_LAUNCHER_APP_LAUNCH_FROM_RECENTS:
                return "LAUNCHER_APP_LAUNCH_FROM_RECENTS";
            case CUJ_LAUNCHER_APP_LAUNCH_FROM_ICON:
                return "LAUNCHER_APP_LAUNCH_FROM_ICON";
            case CUJ_LAUNCHER_APP_CLOSE_TO_HOME:
                return "LAUNCHER_APP_CLOSE_TO_HOME";
            case CUJ_LAUNCHER_APP_CLOSE_TO_PIP:
                return "LAUNCHER_APP_CLOSE_TO_PIP";
            case CUJ_LAUNCHER_QUICK_SWITCH:
                return "LAUNCHER_QUICK_SWITCH";
            case CUJ_NOTIFICATION_HEADS_UP_APPEAR:
                return "NOTIFICATION_HEADS_UP_APPEAR";
            case CUJ_NOTIFICATION_HEADS_UP_DISAPPEAR:
                return "NOTIFICATION_HEADS_UP_DISAPPEAR";
            case CUJ_NOTIFICATION_ADD:
                return "NOTIFICATION_ADD";
            case CUJ_NOTIFICATION_REMOVE:
                return "NOTIFICATION_REMOVE";
            case CUJ_NOTIFICATION_APP_START:
                return "NOTIFICATION_APP_START";
            case CUJ_LOCKSCREEN_PASSWORD_APPEAR:
                return "LOCKSCREEN_PASSWORD_APPEAR";
            case CUJ_LOCKSCREEN_PATTERN_APPEAR:
                return "LOCKSCREEN_PATTERN_APPEAR";
            case CUJ_LOCKSCREEN_PIN_APPEAR:
                return "LOCKSCREEN_PIN_APPEAR";
            case CUJ_LOCKSCREEN_PASSWORD_DISAPPEAR:
                return "LOCKSCREEN_PASSWORD_DISAPPEAR";
            case CUJ_LOCKSCREEN_PATTERN_DISAPPEAR:
                return "LOCKSCREEN_PATTERN_DISAPPEAR";
            case CUJ_LOCKSCREEN_PIN_DISAPPEAR:
                return "LOCKSCREEN_PIN_DISAPPEAR";
            case CUJ_LOCKSCREEN_TRANSITION_FROM_AOD:
                return "LOCKSCREEN_TRANSITION_FROM_AOD";
            case CUJ_LOCKSCREEN_TRANSITION_TO_AOD:
                return "LOCKSCREEN_TRANSITION_TO_AOD";
            case CUJ_LAUNCHER_OPEN_ALL_APPS :
                return "LAUNCHER_OPEN_ALL_APPS";
            case CUJ_LAUNCHER_ALL_APPS_SCROLL:
                return "LAUNCHER_ALL_APPS_SCROLL";
            case CUJ_LAUNCHER_APP_LAUNCH_FROM_WIDGET:
                return "LAUNCHER_APP_LAUNCH_FROM_WIDGET";
            case CUJ_SETTINGS_PAGE_SCROLL:
                return "SETTINGS_PAGE_SCROLL";
            case CUJ_LOCKSCREEN_UNLOCK_ANIMATION:
                return "LOCKSCREEN_UNLOCK_ANIMATION";
            case CUJ_SHADE_APP_LAUNCH_FROM_HISTORY_BUTTON:
                return "SHADE_APP_LAUNCH_FROM_HISTORY_BUTTON";
            case CUJ_SHADE_APP_LAUNCH_FROM_MEDIA_PLAYER:
                return "SHADE_APP_LAUNCH_FROM_MEDIA_PLAYER";
            case CUJ_SHADE_APP_LAUNCH_FROM_QS_TILE:
                return "SHADE_APP_LAUNCH_FROM_QS_TILE";
            case CUJ_SHADE_APP_LAUNCH_FROM_SETTINGS_BUTTON:
                return "SHADE_APP_LAUNCH_FROM_SETTINGS_BUTTON";
            case CUJ_STATUS_BAR_APP_LAUNCH_FROM_CALL_CHIP:
                return "STATUS_BAR_APP_LAUNCH_FROM_CALL_CHIP";
        }
        return "UNKNOWN";
    }

    /**
     * Configurations used while instrumenting the CUJ. <br/>
     * <b>It may refer to an attached view, don't use static reference for any purpose.</b>
     */
    public static class Configuration {
        private final View mView;
        private final long mTimeout;
        private final String mTag;
        private final @CujType int mCujType;

        /**
         * A builder for building Configuration. <br/>
         * <b>It may refer to an attached view, don't use static reference for any purpose.</b>
         */
        public static class Builder {
            private View mAttrView = null;
            private long mAttrTimeout = DEFAULT_TIMEOUT_MS;
            private String mAttrTag = "";
            private @CujType int mAttrCujType;

            /**
             * @param cuj The enum defined in {@link InteractionJankMonitor.CujType}.
             */
            public Builder(@CujType int cuj) {
                mAttrCujType = cuj;
            }

            /**
             * @param view an attached view
             * @return builder
             */
            public Builder setView(@NonNull View view) {
                mAttrView = view;
                return this;
            }

            /**
             * @param timeout duration to cancel the instrumentation in ms
             * @return builder
             */
            public Builder setTimeout(long timeout) {
                mAttrTimeout = timeout;
                return this;
            }

            /**
             * @param tag The postfix of the CUJ in the output trace.
             *           It provides a brief description for the CUJ like the concrete class
             *           who is dealing with the CUJ or the important state with the CUJ, etc.
             * @return builder
             */
            public Builder setTag(@NonNull String tag) {
                mAttrTag = tag;
                return this;
            }

            /**
             * Build the {@link Configuration} instance
             * @return the instance of {@link Configuration}
             * @throws IllegalArgumentException if any invalid attribute is set
             */
            public Configuration build() throws IllegalArgumentException {
                return new Configuration(mAttrCujType, mAttrView, mAttrTag, mAttrTimeout);
            }
        }

        private Configuration(@CujType int cuj, View view, String tag, long timeout) {
            mCujType = cuj;
            mTag = tag;
            mTimeout = timeout;
            mView = view;
            validate();
        }

        private void validate() {
            boolean shouldThrow = false;
            final StringBuilder msg = new StringBuilder();

            if (mTag == null) {
                shouldThrow = true;
                msg.append("Invalid tag; ");
            }
            if (mTimeout < 0) {
                shouldThrow = true;
                msg.append("Invalid timeout value; ");
            }
            if (mView == null || !mView.isAttachedToWindow()) {
                shouldThrow = true;
                msg.append("Null view or view is not attached yet; ");
            }
            if (shouldThrow) {
                throw new IllegalArgumentException(msg.toString());
            }
        }
    }

    /**
     * A class to represent a session.
     */
    public static class Session {
        @CujType
        private final int mCujType;
        private final long mTimeStamp;
        @FrameTracker.Reasons
        private int mReason = FrameTracker.REASON_END_UNKNOWN;
        private final boolean mShouldNotify;
        private final String mName;

        public Session(@CujType int cujType, @NonNull String postfix) {
            mCujType = cujType;
            mTimeStamp = System.nanoTime();
            mShouldNotify = SystemProperties.getBoolean(PROP_NOTIFY_CUJ_EVENT, false);
            mName = TextUtils.isEmpty(postfix)
                    ? String.format("J<%s>", getNameOfCuj(mCujType))
                    : String.format("J<%s::%s>", getNameOfCuj(mCujType), postfix);
        }

        @CujType
        public int getCuj() {
            return mCujType;
        }

        public int getStatsdInteractionType() {
            return CUJ_TO_STATSD_INTERACTION_TYPE[mCujType];
        }

        /** Describes whether the measurement from this session should be written to statsd. */
        public boolean logToStatsd() {
            return getStatsdInteractionType() != NO_STATSD_LOGGING;
        }

        public String getPerfettoTrigger() {
            return String.format(Locale.US, "com.android.telemetry.interaction-jank-monitor-%d",
                    mCujType);
        }

        public String getName() {
            return mName;
        }

        public long getTimeStamp() {
            return mTimeStamp;
        }

        public void setReason(@FrameTracker.Reasons int reason) {
            mReason = reason;
        }

        public int getReason() {
            return mReason;
        }

        /** Determine if should notify the receivers of cuj events */
        public boolean shouldNotify() {
            return mShouldNotify;
        }
    }
}
