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

package com.android.server.job;

import static com.android.server.job.JobSchedulerService.RESTRICTED_INDEX;
import static com.android.server.job.JobSchedulerService.sElapsedRealtimeClock;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.UserSwitchObserver;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.UserInfo;
import android.os.BatteryStats;
import android.os.Handler;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.provider.DeviceConfig;
import android.util.ArraySet;
import android.util.IndentingPrintWriter;
import android.util.Pair;
import android.util.Pools;
import android.util.Slog;
import android.util.SparseArrayMap;
import android.util.SparseIntArray;
import android.util.SparseLongArray;
import android.util.TimeUtils;
import android.util.proto.ProtoOutputStream;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.IBatteryStats;
import com.android.internal.app.procstats.ProcessStats;
import com.android.internal.util.StatLogger;
import com.android.server.JobSchedulerBackgroundThread;
import com.android.server.LocalServices;
import com.android.server.job.controllers.JobStatus;
import com.android.server.job.controllers.StateController;
import com.android.server.job.restrictions.JobRestriction;
import com.android.server.pm.UserManagerInternal;

import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * This class decides, given the various configuration and the system status, which jobs can start
 * and which {@link JobServiceContext} to run each job on.
 */
class JobConcurrencyManager {
    private static final String TAG = JobSchedulerService.TAG + ".Concurrency";
    private static final boolean DEBUG = JobSchedulerService.DEBUG;

    /** The maximum number of concurrent jobs we'll aim to run at one time. */
    public static final int STANDARD_CONCURRENCY_LIMIT = 16;
    /** The maximum number of objects we should retain in memory when not in use. */
    private static final int MAX_RETAINED_OBJECTS = (int) (1.5 * STANDARD_CONCURRENCY_LIMIT);

    static final String CONFIG_KEY_PREFIX_CONCURRENCY = "concurrency_";
    private static final String KEY_SCREEN_OFF_ADJUSTMENT_DELAY_MS =
            CONFIG_KEY_PREFIX_CONCURRENCY + "screen_off_adjustment_delay_ms";
    private static final long DEFAULT_SCREEN_OFF_ADJUSTMENT_DELAY_MS = 30_000;
    @VisibleForTesting
    static final String KEY_PKG_CONCURRENCY_LIMIT_EJ =
            CONFIG_KEY_PREFIX_CONCURRENCY + "pkg_concurrency_limit_ej";
    private static final int DEFAULT_PKG_CONCURRENCY_LIMIT_EJ = 3;
    @VisibleForTesting
    static final String KEY_PKG_CONCURRENCY_LIMIT_REGULAR =
            CONFIG_KEY_PREFIX_CONCURRENCY + "pkg_concurrency_limit_regular";
    private static final int DEFAULT_PKG_CONCURRENCY_LIMIT_REGULAR = STANDARD_CONCURRENCY_LIMIT / 2;

    /**
     * Set of possible execution types that a job can have. The actual type(s) of a job are based
     * on the {@link JobStatus#lastEvaluatedBias}, which is typically evaluated right before
     * execution (when we're trying to determine which jobs to run next) and won't change after the
     * job has started executing.
     *
     * Try to give higher priority types lower values.
     *
     * @see #getJobWorkTypes(JobStatus)
     */

    /** Job shouldn't run or qualify as any other work type. */
    static final int WORK_TYPE_NONE = 0;
    /** The job is for an app in the TOP state for a currently active user. */
    static final int WORK_TYPE_TOP = 1 << 0;
    /**
     * The job is for an app in a {@link ActivityManager#PROCESS_STATE_FOREGROUND_SERVICE} or higher
     * state (excluding {@link ActivityManager#PROCESS_STATE_TOP} for a currently active user.
     */
    static final int WORK_TYPE_FGS = 1 << 1;
    /** The job is allowed to run as an expedited job for a currently active user. */
    static final int WORK_TYPE_EJ = 1 << 2;
    /**
     * The job does not satisfy any of the conditions for {@link #WORK_TYPE_TOP},
     * {@link #WORK_TYPE_FGS}, or {@link #WORK_TYPE_EJ}, but is for a currently active user, so
     * can run as a background job.
     */
    static final int WORK_TYPE_BG = 1 << 3;
    /**
     * The job is for an app in a {@link ActivityManager#PROCESS_STATE_FOREGROUND_SERVICE} or higher
     * state, or is allowed to run as an expedited job, but is for a completely background user.
     */
    static final int WORK_TYPE_BGUSER_IMPORTANT = 1 << 4;
    /**
     * The job does not satisfy any of the conditions for {@link #WORK_TYPE_TOP},
     * {@link #WORK_TYPE_FGS}, or {@link #WORK_TYPE_EJ}, but is for a completely background user,
     * so can run as a background user job.
     */
    static final int WORK_TYPE_BGUSER = 1 << 5;
    @VisibleForTesting
    static final int NUM_WORK_TYPES = 6;
    private static final int ALL_WORK_TYPES = (1 << NUM_WORK_TYPES) - 1;

    @IntDef(prefix = {"WORK_TYPE_"}, flag = true, value = {
            WORK_TYPE_NONE,
            WORK_TYPE_TOP,
            WORK_TYPE_FGS,
            WORK_TYPE_EJ,
            WORK_TYPE_BG,
            WORK_TYPE_BGUSER_IMPORTANT,
            WORK_TYPE_BGUSER
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface WorkType {
    }

    @VisibleForTesting
    static String workTypeToString(@WorkType int workType) {
        switch (workType) {
            case WORK_TYPE_NONE:
                return "NONE";
            case WORK_TYPE_TOP:
                return "TOP";
            case WORK_TYPE_FGS:
                return "FGS";
            case WORK_TYPE_EJ:
                return "EJ";
            case WORK_TYPE_BG:
                return "BG";
            case WORK_TYPE_BGUSER:
                return "BGUSER";
            case WORK_TYPE_BGUSER_IMPORTANT:
                return "BGUSER_IMPORTANT";
            default:
                return "WORK(" + workType + ")";
        }
    }

    private final Object mLock;
    private final JobSchedulerService mService;
    private final Context mContext;
    private final Handler mHandler;

    private PowerManager mPowerManager;

    private boolean mCurrentInteractiveState;
    private boolean mEffectiveInteractiveState;

    private long mLastScreenOnRealtime;
    private long mLastScreenOffRealtime;

    private static final WorkConfigLimitsPerMemoryTrimLevel CONFIG_LIMITS_SCREEN_ON =
            new WorkConfigLimitsPerMemoryTrimLevel(
                    new WorkTypeConfig("screen_on_normal", 11,
                            // defaultMin
                            List.of(Pair.create(WORK_TYPE_TOP, 4), Pair.create(WORK_TYPE_FGS, 1),
                                    Pair.create(WORK_TYPE_EJ, 3), Pair.create(WORK_TYPE_BG, 2),
                                    Pair.create(WORK_TYPE_BGUSER_IMPORTANT, 1)),
                            // defaultMax
                            List.of(Pair.create(WORK_TYPE_BG, 6),
                                    Pair.create(WORK_TYPE_BGUSER_IMPORTANT, 2),
                                    Pair.create(WORK_TYPE_BGUSER, 3))
                    ),
                    new WorkTypeConfig("screen_on_moderate", 9,
                            // defaultMin
                            List.of(Pair.create(WORK_TYPE_TOP, 4), Pair.create(WORK_TYPE_FGS, 1),
                                    Pair.create(WORK_TYPE_EJ, 2), Pair.create(WORK_TYPE_BG, 1),
                                    Pair.create(WORK_TYPE_BGUSER_IMPORTANT, 1)),
                            // defaultMax
                            List.of(Pair.create(WORK_TYPE_BG, 4),
                                    Pair.create(WORK_TYPE_BGUSER_IMPORTANT, 1),
                                    Pair.create(WORK_TYPE_BGUSER, 1))
                    ),
                    new WorkTypeConfig("screen_on_low", 6,
                            // defaultMin
                            List.of(Pair.create(WORK_TYPE_TOP, 4), Pair.create(WORK_TYPE_FGS, 1),
                                    Pair.create(WORK_TYPE_EJ, 1)),
                            // defaultMax
                            List.of(Pair.create(WORK_TYPE_BG, 2),
                                    Pair.create(WORK_TYPE_BGUSER_IMPORTANT, 1),
                                    Pair.create(WORK_TYPE_BGUSER, 1))
                    ),
                    new WorkTypeConfig("screen_on_critical", 6,
                            // defaultMin
                            List.of(Pair.create(WORK_TYPE_TOP, 4), Pair.create(WORK_TYPE_FGS, 1),
                                    Pair.create(WORK_TYPE_EJ, 1)),
                            // defaultMax
                            List.of(Pair.create(WORK_TYPE_BG, 1),
                                    Pair.create(WORK_TYPE_BGUSER_IMPORTANT, 1),
                                    Pair.create(WORK_TYPE_BGUSER, 1))
                    )
            );
    private static final WorkConfigLimitsPerMemoryTrimLevel CONFIG_LIMITS_SCREEN_OFF =
            new WorkConfigLimitsPerMemoryTrimLevel(
                    new WorkTypeConfig("screen_off_normal", 16,
                            // defaultMin
                            List.of(Pair.create(WORK_TYPE_TOP, 4), Pair.create(WORK_TYPE_FGS, 2),
                                    Pair.create(WORK_TYPE_EJ, 3), Pair.create(WORK_TYPE_BG, 2),
                                    Pair.create(WORK_TYPE_BGUSER_IMPORTANT, 1)),
                            // defaultMax
                            List.of(Pair.create(WORK_TYPE_BG, 10),
                                    Pair.create(WORK_TYPE_BGUSER_IMPORTANT, 2),
                                    Pair.create(WORK_TYPE_BGUSER, 3))
                    ),
                    new WorkTypeConfig("screen_off_moderate", 14,
                            // defaultMin
                            List.of(Pair.create(WORK_TYPE_TOP, 4), Pair.create(WORK_TYPE_FGS, 2),
                                    Pair.create(WORK_TYPE_EJ, 3), Pair.create(WORK_TYPE_BG, 2),
                                    Pair.create(WORK_TYPE_BGUSER_IMPORTANT, 1)),
                            // defaultMax
                            List.of(Pair.create(WORK_TYPE_BG, 7),
                                    Pair.create(WORK_TYPE_BGUSER_IMPORTANT, 1),
                                    Pair.create(WORK_TYPE_BGUSER, 1))
                    ),
                    new WorkTypeConfig("screen_off_low", 9,
                            // defaultMin
                            List.of(Pair.create(WORK_TYPE_TOP, 4), Pair.create(WORK_TYPE_FGS, 1),
                                    Pair.create(WORK_TYPE_EJ, 2), Pair.create(WORK_TYPE_BG, 1)),
                            // defaultMax
                            List.of(Pair.create(WORK_TYPE_BG, 3),
                                    Pair.create(WORK_TYPE_BGUSER_IMPORTANT, 1),
                                    Pair.create(WORK_TYPE_BGUSER, 1))
                    ),
                    new WorkTypeConfig("screen_off_critical", 6,
                            // defaultMin
                            List.of(Pair.create(WORK_TYPE_TOP, 4), Pair.create(WORK_TYPE_FGS, 1),
                                    Pair.create(WORK_TYPE_EJ, 1)),
                            // defaultMax
                            List.of(Pair.create(WORK_TYPE_BG, 1),
                                    Pair.create(WORK_TYPE_BGUSER_IMPORTANT, 1),
                                    Pair.create(WORK_TYPE_BGUSER, 1))
                    )
            );

    /**
     * Comparator to sort the determination lists, putting the ContextAssignments that we most
     * prefer to use at the end of the list.
     */
    private static final Comparator<ContextAssignment> sDeterminationComparator = (ca1, ca2) -> {
        if (ca1 == ca2) {
            return 0;
        }
        final JobStatus js1 = ca1.context.getRunningJobLocked();
        final JobStatus js2 = ca2.context.getRunningJobLocked();
        // Prefer using an empty context over one with a running job.
        if (js1 == null) {
            if (js2 == null) {
                return 0;
            }
            return 1;
        } else if (js2 == null) {
            return -1;
        }
        // We would prefer to replace bg jobs over TOP jobs.
        if (js1.lastEvaluatedBias == JobInfo.BIAS_TOP_APP) {
            if (js2.lastEvaluatedBias != JobInfo.BIAS_TOP_APP) {
                return -1;
            }
        } else if (js2.lastEvaluatedBias == JobInfo.BIAS_TOP_APP) {
            return 1;
        }
        // Prefer replacing the job that has been running the longest.
        return Long.compare(
                ca2.context.getExecutionStartTimeElapsed(),
                ca1.context.getExecutionStartTimeElapsed());
    };

    // We reuse the lists to avoid GC churn.
    private final ArraySet<ContextAssignment> mRecycledChanged = new ArraySet<>();
    private final ArraySet<ContextAssignment> mRecycledIdle = new ArraySet<>();
    private final ArrayList<ContextAssignment> mRecycledPreferredUidOnly = new ArrayList<>();
    private final ArrayList<ContextAssignment> mRecycledStoppable = new ArrayList<>();

    private final Pools.Pool<ContextAssignment> mContextAssignmentPool =
            new Pools.SimplePool<>(MAX_RETAINED_OBJECTS);

    /**
     * Set of JobServiceContexts that are actively running jobs.
     */
    final List<JobServiceContext> mActiveServices = new ArrayList<>();

    /** Set of JobServiceContexts that aren't currently running any jobs. */
    private final ArraySet<JobServiceContext> mIdleContexts = new ArraySet<>();

    private int mNumDroppedContexts = 0;

    private final ArraySet<JobStatus> mRunningJobs = new ArraySet<>();

    private final WorkCountTracker mWorkCountTracker = new WorkCountTracker();

    private final Pools.Pool<PackageStats> mPkgStatsPool =
            new Pools.SimplePool<>(MAX_RETAINED_OBJECTS);

    private final SparseArrayMap<String, PackageStats> mActivePkgStats = new SparseArrayMap<>();

    private WorkTypeConfig mWorkTypeConfig = CONFIG_LIMITS_SCREEN_OFF.normal;

    /** Wait for this long after screen off before adjusting the job concurrency. */
    private long mScreenOffAdjustmentDelayMs = DEFAULT_SCREEN_OFF_ADJUSTMENT_DELAY_MS;

    /**
     * The maximum number of expedited jobs a single userId-package can have running simultaneously.
     * TOP apps are not limited.
     */
    private int mPkgConcurrencyLimitEj = DEFAULT_PKG_CONCURRENCY_LIMIT_EJ;

    /**
     * The maximum number of regular jobs a single userId-package can have running simultaneously.
     * TOP apps are not limited.
     */
    private int mPkgConcurrencyLimitRegular = DEFAULT_PKG_CONCURRENCY_LIMIT_REGULAR;

    /** Current memory trim level. */
    private int mLastMemoryTrimLevel;

    /** Used to throttle heavy API calls. */
    private long mNextSystemStateRefreshTime;
    private static final int SYSTEM_STATE_REFRESH_MIN_INTERVAL = 1000;

    private final Consumer<PackageStats> mPackageStatsStagingCountClearer =
            PackageStats::resetStagedCount;

    private final StatLogger mStatLogger = new StatLogger(new String[]{
            "assignJobsToContexts",
            "refreshSystemState",
    });
    @VisibleForTesting
    GracePeriodObserver mGracePeriodObserver;
    @VisibleForTesting
    boolean mShouldRestrictBgUser;

    interface Stats {
        int ASSIGN_JOBS_TO_CONTEXTS = 0;
        int REFRESH_SYSTEM_STATE = 1;

        int COUNT = REFRESH_SYSTEM_STATE + 1;
    }

    JobConcurrencyManager(JobSchedulerService service) {
        mService = service;
        mLock = mService.mLock;
        mContext = service.getTestableContext();

        mHandler = JobSchedulerBackgroundThread.getHandler();

        mGracePeriodObserver = new GracePeriodObserver(mContext);
        mShouldRestrictBgUser = mContext.getResources().getBoolean(
                R.bool.config_jobSchedulerRestrictBackgroundUser);
    }

    public void onSystemReady() {
        mPowerManager = mContext.getSystemService(PowerManager.class);

        final IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED);
        filter.addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED);
        mContext.registerReceiver(mReceiver, filter);
        try {
            ActivityManager.getService().registerUserSwitchObserver(mGracePeriodObserver, TAG);
        } catch (RemoteException e) {
        }

        onInteractiveStateChanged(mPowerManager.isInteractive());
    }

    /**
     * Called when the boot phase reaches
     * {@link com.android.server.SystemService#PHASE_THIRD_PARTY_APPS_CAN_START}.
     */
    void onThirdPartyAppsCanStart() {
        final IBatteryStats batteryStats = IBatteryStats.Stub.asInterface(
                ServiceManager.getService(BatteryStats.SERVICE_NAME));
        for (int i = 0; i < STANDARD_CONCURRENCY_LIMIT; i++) {
            mIdleContexts.add(
                    new JobServiceContext(mService, this, batteryStats,
                            mService.mJobPackageTracker, mContext.getMainLooper()));
        }
    }

    @GuardedBy("mLock")
    void onAppRemovedLocked(String pkgName, int uid) {
        final PackageStats packageStats = mActivePkgStats.get(UserHandle.getUserId(uid), pkgName);
        if (packageStats != null) {
            if (packageStats.numRunningEj > 0 || packageStats.numRunningRegular > 0) {
                // Don't delete the object just yet. We'll remove it in onJobCompleted() when the
                // jobs officially stop running.
                Slog.w(TAG,
                        pkgName + "(" + uid + ") marked as removed before jobs stopped running");
            } else {
                mActivePkgStats.delete(UserHandle.getUserId(uid), pkgName);
            }
        }
    }

    void onUserRemoved(int userId) {
        mGracePeriodObserver.onUserRemoved(userId);
    }

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            switch (intent.getAction()) {
                case Intent.ACTION_SCREEN_ON:
                    onInteractiveStateChanged(true);
                    break;
                case Intent.ACTION_SCREEN_OFF:
                    onInteractiveStateChanged(false);
                    break;
                case PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED:
                    if (mPowerManager != null && mPowerManager.isDeviceIdleMode()) {
                        synchronized (mLock) {
                            stopUnexemptedJobsForDoze();
                            stopLongRunningJobsLocked("deep doze");
                        }
                    }
                    break;
                case PowerManager.ACTION_POWER_SAVE_MODE_CHANGED:
                    if (mPowerManager != null && mPowerManager.isPowerSaveMode()) {
                        synchronized (mLock) {
                            stopLongRunningJobsLocked("battery saver");
                        }
                    }
                    break;
            }
        }
    };

    /**
     * Called when the screen turns on / off.
     */
    private void onInteractiveStateChanged(boolean interactive) {
        synchronized (mLock) {
            if (mCurrentInteractiveState == interactive) {
                return;
            }
            mCurrentInteractiveState = interactive;
            if (DEBUG) {
                Slog.d(TAG, "Interactive: " + interactive);
            }

            final long nowRealtime = sElapsedRealtimeClock.millis();
            if (interactive) {
                mLastScreenOnRealtime = nowRealtime;
                mEffectiveInteractiveState = true;

                mHandler.removeCallbacks(mRampUpForScreenOff);
            } else {
                mLastScreenOffRealtime = nowRealtime;

                // Set mEffectiveInteractiveState to false after the delay, when we may increase
                // the concurrency.
                // We don't need a wakeup alarm here. When there's a pending job, there should
                // also be jobs running too, meaning the device should be awake.

                // Note: we can't directly do postDelayed(this::rampUpForScreenOn), because
                // we need the exact same instance for removeCallbacks().
                mHandler.postDelayed(mRampUpForScreenOff, mScreenOffAdjustmentDelayMs);
            }
        }
    }

    private final Runnable mRampUpForScreenOff = this::rampUpForScreenOff;

    /**
     * Called in {@link #mScreenOffAdjustmentDelayMs} after
     * the screen turns off, in order to increase concurrency.
     */
    private void rampUpForScreenOff() {
        synchronized (mLock) {
            // Make sure the screen has really been off for the configured duration.
            // (There could be a race.)
            if (!mEffectiveInteractiveState) {
                return;
            }
            if (mLastScreenOnRealtime > mLastScreenOffRealtime) {
                return;
            }
            final long now = sElapsedRealtimeClock.millis();
            if ((mLastScreenOffRealtime + mScreenOffAdjustmentDelayMs) > now) {
                return;
            }

            mEffectiveInteractiveState = false;

            if (DEBUG) {
                Slog.d(TAG, "Ramping up concurrency");
            }

            mService.maybeRunPendingJobsLocked();
        }
    }

    @GuardedBy("mLock")
    ArraySet<JobStatus> getRunningJobsLocked() {
        return mRunningJobs;
    }

    @GuardedBy("mLock")
    boolean isJobRunningLocked(JobStatus job) {
        return mRunningJobs.contains(job);
    }

    /**
     * Returns true if a job that is "similar" to the provided job is currently running.
     * "Similar" in this context means any job that the {@link JobStore} would consider equivalent
     * and replace one with the other.
     */
    @GuardedBy("mLock")
    private boolean isSimilarJobRunningLocked(JobStatus job) {
        for (int i = mRunningJobs.size() - 1; i >= 0; --i) {
            JobStatus js = mRunningJobs.valueAt(i);
            if (job.getUid() == js.getUid() && job.getJobId() == js.getJobId()) {
                return true;
            }
        }
        return false;
    }

    /** Return {@code true} if the state was updated. */
    @GuardedBy("mLock")
    private boolean refreshSystemStateLocked() {
        final long nowUptime = JobSchedulerService.sUptimeMillisClock.millis();

        // Only refresh the information every so often.
        if (nowUptime < mNextSystemStateRefreshTime) {
            return false;
        }

        final long start = mStatLogger.getTime();
        mNextSystemStateRefreshTime = nowUptime + SYSTEM_STATE_REFRESH_MIN_INTERVAL;

        mLastMemoryTrimLevel = ProcessStats.ADJ_MEM_FACTOR_NORMAL;
        try {
            mLastMemoryTrimLevel = ActivityManager.getService().getMemoryTrimLevel();
        } catch (RemoteException e) {
        }

        mStatLogger.logDurationStat(Stats.REFRESH_SYSTEM_STATE, start);
        return true;
    }

    @GuardedBy("mLock")
    private void updateCounterConfigLocked() {
        if (!refreshSystemStateLocked()) {
            return;
        }

        final WorkConfigLimitsPerMemoryTrimLevel workConfigs = mEffectiveInteractiveState
                ? CONFIG_LIMITS_SCREEN_ON : CONFIG_LIMITS_SCREEN_OFF;

        switch (mLastMemoryTrimLevel) {
            case ProcessStats.ADJ_MEM_FACTOR_MODERATE:
                mWorkTypeConfig = workConfigs.moderate;
                break;
            case ProcessStats.ADJ_MEM_FACTOR_LOW:
                mWorkTypeConfig = workConfigs.low;
                break;
            case ProcessStats.ADJ_MEM_FACTOR_CRITICAL:
                mWorkTypeConfig = workConfigs.critical;
                break;
            default:
                mWorkTypeConfig = workConfigs.normal;
                break;
        }

        mWorkCountTracker.setConfig(mWorkTypeConfig);
    }

    /**
     * Takes jobs from pending queue and runs them on available contexts.
     * If no contexts are available, preempts lower bias jobs to run higher bias ones.
     * Lock on mLock before calling this function.
     */
    @GuardedBy("mLock")
    void assignJobsToContextsLocked() {
        final long start = mStatLogger.getTime();

        assignJobsToContextsInternalLocked();

        mStatLogger.logDurationStat(Stats.ASSIGN_JOBS_TO_CONTEXTS, start);
    }

    @GuardedBy("mLock")
    private void assignJobsToContextsInternalLocked() {
        if (DEBUG) {
            Slog.d(TAG, printPendingQueueLocked());
        }

        if (mService.getPendingJobQueue().size() == 0) {
            // Nothing to do.
            return;
        }

        final PendingJobQueue pendingJobQueue = mService.getPendingJobQueue();
        final List<JobServiceContext> activeServices = mActiveServices;

        // To avoid GC churn, we recycle the arrays.
        final ArraySet<ContextAssignment> changed = mRecycledChanged;
        final ArraySet<ContextAssignment> idle = mRecycledIdle;
        final ArrayList<ContextAssignment> preferredUidOnly = mRecycledPreferredUidOnly;
        final ArrayList<ContextAssignment> stoppable = mRecycledStoppable;

        updateCounterConfigLocked();
        // Reset everything since we'll re-evaluate the current state.
        mWorkCountTracker.resetCounts();

        // Update the priorities of jobs that aren't running, and also count the pending work types.
        // Do this before the following loop to hopefully reduce the cost of
        // shouldStopRunningJobLocked().
        updateNonRunningPrioritiesLocked(pendingJobQueue, true);

        final int numRunningJobs = activeServices.size();
        for (int i = 0; i < numRunningJobs; ++i) {
            final JobServiceContext jsc = activeServices.get(i);
            final JobStatus js = jsc.getRunningJobLocked();

            ContextAssignment assignment = mContextAssignmentPool.acquire();
            if (assignment == null) {
                assignment = new ContextAssignment();
            }

            assignment.context = jsc;

            if (js != null) {
                mWorkCountTracker.incrementRunningJobCount(jsc.getRunningJobWorkType());
                assignment.workType = jsc.getRunningJobWorkType();
            }

            assignment.preferredUid = jsc.getPreferredUid();
            if ((assignment.shouldStopJobReason = shouldStopRunningJobLocked(jsc)) != null) {
                stoppable.add(assignment);
            } else {
                preferredUidOnly.add(assignment);
            }
        }
        preferredUidOnly.sort(sDeterminationComparator);
        stoppable.sort(sDeterminationComparator);
        for (int i = numRunningJobs; i < STANDARD_CONCURRENCY_LIMIT; ++i) {
            final JobServiceContext jsc;
            final int numIdleContexts = mIdleContexts.size();
            if (numIdleContexts > 0) {
                jsc = mIdleContexts.removeAt(numIdleContexts - 1);
            } else {
                Slog.wtf(TAG, "Had fewer than " + STANDARD_CONCURRENCY_LIMIT + " in existence");
                jsc = createNewJobServiceContext();
            }

            ContextAssignment assignment = mContextAssignmentPool.acquire();
            if (assignment == null) {
                assignment = new ContextAssignment();
            }

            assignment.context = jsc;
            idle.add(assignment);
        }
        if (DEBUG) {
            Slog.d(TAG, printAssignments("running jobs initial", stoppable, preferredUidOnly));
        }

        mWorkCountTracker.onCountDone();

        JobStatus nextPending;
        pendingJobQueue.resetIterator();
        int projectedRunningCount = numRunningJobs;
        while ((nextPending = pendingJobQueue.next()) != null) {
            if (mRunningJobs.contains(nextPending)) {
                // Should never happen.
                Slog.wtf(TAG, "Pending queue contained a running job");
                if (DEBUG) {
                    Slog.e(TAG, "Pending+running job: " + nextPending);
                }
                pendingJobQueue.remove(nextPending);
                continue;
            }

            final boolean isTopEj = nextPending.shouldTreatAsExpeditedJob()
                    && nextPending.lastEvaluatedBias == JobInfo.BIAS_TOP_APP;
            if (DEBUG && isSimilarJobRunningLocked(nextPending)) {
                Slog.w(TAG, "Already running similar " + (isTopEj ? "TOP-EJ" : "job")
                        + " to: " + nextPending);
            }

            // Find an available slot for nextPending. The context should be one of the following:
            // 1. Unused
            // 2. Its job should have used up its minimum execution guarantee so it
            // 3. Its job should have the lowest bias among all running jobs (sharing the same UID
            //    as nextPending)
            ContextAssignment selectedContext = null;
            final int allWorkTypes = getJobWorkTypes(nextPending);
            final boolean pkgConcurrencyOkay = !isPkgConcurrencyLimitedLocked(nextPending);
            final boolean isInOverage = projectedRunningCount > STANDARD_CONCURRENCY_LIMIT;
            boolean startingJob = false;
            if (idle.size() > 0) {
                final int idx = idle.size() - 1;
                final ContextAssignment assignment = idle.valueAt(idx);
                final boolean preferredUidOkay = (assignment.preferredUid == nextPending.getUid())
                        || (assignment.preferredUid == JobServiceContext.NO_PREFERRED_UID);
                int workType = mWorkCountTracker.canJobStart(allWorkTypes);
                if (preferredUidOkay && pkgConcurrencyOkay && workType != WORK_TYPE_NONE) {
                    // This slot is free, and we haven't yet hit the limit on
                    // concurrent jobs...  we can just throw the job in to here.
                    selectedContext = assignment;
                    startingJob = true;
                    idle.removeAt(idx);
                    assignment.newJob = nextPending;
                    assignment.newWorkType = workType;
                }
            }
            if (selectedContext == null && stoppable.size() > 0) {
                int topEjCount = 0;
                for (int r = mRunningJobs.size() - 1; r >= 0; --r) {
                    JobStatus js = mRunningJobs.valueAt(r);
                    if (js.startedAsExpeditedJob && js.lastEvaluatedBias == JobInfo.BIAS_TOP_APP) {
                        topEjCount++;
                    }
                }
                for (int s = stoppable.size() - 1; s >= 0; --s) {
                    final ContextAssignment assignment = stoppable.get(s);
                    final JobStatus runningJob = assignment.context.getRunningJobLocked();
                    // Maybe stop the job if it has had its day in the sun. Only allow replacing
                    // for one of the following conditions:
                    // 1. We're putting in the current TOP app's EJ
                    // 2. There aren't too many jobs running AND the current job started when the
                    //    app was in the background
                    // 3. There aren't too many jobs running AND the current job started when the
                    //    app was on TOP, but the app has since left TOP
                    // 4. There aren't too many jobs running AND the current job started when the
                    //    app was on TOP, the app is still TOP, but there are too many TOP+EJs
                    //    running (because we don't want them to starve out other apps and the
                    //    current job has already run for the minimum guaranteed time).
                    boolean canReplace = isTopEj; // Case 1
                    if (!canReplace && !isInOverage) {
                        final int currentJobBias = mService.evaluateJobBiasLocked(runningJob);
                        canReplace = runningJob.lastEvaluatedBias < JobInfo.BIAS_TOP_APP // Case 2
                                || currentJobBias < JobInfo.BIAS_TOP_APP // Case 3
                                || topEjCount > .5 * mWorkTypeConfig.getMaxTotal(); // Case 4
                    }
                    if (canReplace) {
                        int replaceWorkType = mWorkCountTracker.canJobStart(allWorkTypes,
                                assignment.context.getRunningJobWorkType());
                        if (replaceWorkType != WORK_TYPE_NONE) {
                            // Right now, the way the code is set up, we don't need to explicitly
                            // assign the new job to this context since we'll reassign when the
                            // preempted job finally stops.
                            assignment.preemptReason = assignment.shouldStopJobReason;
                            assignment.preemptReasonCode = JobParameters.STOP_REASON_DEVICE_STATE;
                            selectedContext = assignment;
                            stoppable.remove(s);
                            assignment.newJob = nextPending;
                            assignment.newWorkType = replaceWorkType;
                            break;
                        }
                    }
                }
            }
            if (selectedContext == null && (!isInOverage || isTopEj)) {
                int lowestBiasSeen = Integer.MAX_VALUE;
                for (int p = preferredUidOnly.size() - 1; p >= 0; --p) {
                    final ContextAssignment assignment = preferredUidOnly.get(p);
                    final JobStatus runningJob = assignment.context.getRunningJobLocked();
                    if (runningJob.getUid() != nextPending.getUid()) {
                        continue;
                    }
                    final int jobBias = mService.evaluateJobBiasLocked(runningJob);
                    if (jobBias >= nextPending.lastEvaluatedBias) {
                        continue;
                    }

                    if (selectedContext == null || lowestBiasSeen > jobBias) {
                        // Step down the preemption threshold - wind up replacing
                        // the lowest-bias running job
                        lowestBiasSeen = jobBias;
                        selectedContext = assignment;
                        assignment.preemptReason = "higher bias job found";
                        assignment.preemptReasonCode = JobParameters.STOP_REASON_PREEMPT;
                        // In this case, we're just going to preempt a low bias job, we're not
                        // actually starting a job, so don't set startingJob to true.
                    }
                }
                if (selectedContext != null) {
                    selectedContext.newJob = nextPending;
                    preferredUidOnly.remove(selectedContext);
                }
            }
            // Make sure to run EJs for the TOP app immediately.
            if (isTopEj) {
                if (selectedContext != null
                        && selectedContext.context.getRunningJobLocked() != null) {
                    // We're "replacing" a currently running job, but we want TOP EJs to start
                    // immediately, so we'll start the EJ on a fresh available context and
                    // stop this currently running job to replace in two steps.
                    changed.add(selectedContext);
                    projectedRunningCount--;
                    selectedContext.newJob = null;
                    selectedContext.newWorkType = WORK_TYPE_NONE;
                    selectedContext = null;
                }
                if (selectedContext == null) {
                    selectedContext = mContextAssignmentPool.acquire();
                    if (selectedContext == null) {
                        selectedContext = new ContextAssignment();
                    }
                    selectedContext.context = mIdleContexts.size() > 0
                            ? mIdleContexts.removeAt(mIdleContexts.size() - 1)
                            : createNewJobServiceContext();
                    selectedContext.newJob = nextPending;
                    final int workType = mWorkCountTracker.canJobStart(allWorkTypes);
                    selectedContext.newWorkType =
                            (workType != WORK_TYPE_NONE) ? workType : WORK_TYPE_TOP;
                }
            }
            final PackageStats packageStats = getPkgStatsLocked(
                    nextPending.getSourceUserId(), nextPending.getSourcePackageName());
            if (selectedContext != null) {
                changed.add(selectedContext);
                if (selectedContext.context.getRunningJobLocked() != null) {
                    projectedRunningCount--;
                }
                if (selectedContext.newJob != null) {
                    projectedRunningCount++;
                }
                packageStats.adjustStagedCount(true, nextPending.shouldTreatAsExpeditedJob());
            }
            if (startingJob) {
                // Increase the counters when we're going to start a job.
                mWorkCountTracker.stageJob(selectedContext.newWorkType, allWorkTypes);
                mActivePkgStats.add(
                        nextPending.getSourceUserId(), nextPending.getSourcePackageName(),
                        packageStats);
            }
        }
        if (DEBUG) {
            Slog.d(TAG, printAssignments("running jobs final",
                    stoppable, preferredUidOnly, changed));

            Slog.d(TAG, "assignJobsToContexts: " + mWorkCountTracker.toString());
        }

        for (int c = changed.size() - 1; c >= 0; --c) {
            final ContextAssignment assignment = changed.valueAt(c);
            final JobStatus js = assignment.context.getRunningJobLocked();
            if (js != null) {
                if (DEBUG) {
                    Slog.d(TAG, "preempting job: " + js);
                }
                // preferredUid will be set to uid of currently running job, if appropriate.
                assignment.context.cancelExecutingJobLocked(
                        assignment.preemptReasonCode,
                        JobParameters.INTERNAL_STOP_REASON_PREEMPT, assignment.preemptReason);
            } else {
                final JobStatus pendingJob = assignment.newJob;
                if (DEBUG) {
                    Slog.d(TAG, "About to run job on context "
                            + assignment.context.getId() + ", job: " + pendingJob);
                }
                startJobLocked(assignment.context, pendingJob, assignment.newWorkType);
            }

            assignment.clear();
            mContextAssignmentPool.release(assignment);
        }
        for (int s = stoppable.size() - 1; s >= 0; --s) {
            final ContextAssignment assignment = stoppable.get(s);
            assignment.clear();
            mContextAssignmentPool.release(assignment);
        }
        for (int p = preferredUidOnly.size() - 1; p >= 0; --p) {
            final ContextAssignment assignment = preferredUidOnly.get(p);
            assignment.clear();
            mContextAssignmentPool.release(assignment);
        }
        for (int i = idle.size() - 1; i >= 0; --i) {
            final ContextAssignment assignment = idle.valueAt(i);
            mIdleContexts.add(assignment.context);
            assignment.clear();
            mContextAssignmentPool.release(assignment);
        }
        changed.clear();
        idle.clear();
        stoppable.clear();
        preferredUidOnly.clear();
        mWorkCountTracker.resetStagingCount();
        mActivePkgStats.forEach(mPackageStatsStagingCountClearer);
        noteConcurrency();
    }

    @GuardedBy("mLock")
    void onUidBiasChangedLocked(int prevBias, int newBias) {
        if (prevBias != JobInfo.BIAS_TOP_APP && newBias != JobInfo.BIAS_TOP_APP) {
            // TOP app didn't change. Nothing to do.
            return;
        }
        if (mService.getPendingJobQueue().size() == 0) {
            // Nothing waiting for the top app to leave. Nothing to do.
            return;
        }
        // Don't stop the TOP jobs directly. Instead, see if they would be replaced by some
        // pending job (there may not always be something to replace them).
        assignJobsToContextsLocked();
    }

    @GuardedBy("mLock")
    boolean stopJobOnServiceContextLocked(JobStatus job,
            @JobParameters.StopReason int reason, int internalReasonCode, String debugReason) {
        if (!mRunningJobs.contains(job)) {
            return false;
        }

        for (int i = 0; i < mActiveServices.size(); i++) {
            JobServiceContext jsc = mActiveServices.get(i);
            final JobStatus executing = jsc.getRunningJobLocked();
            if (executing == job) {
                jsc.cancelExecutingJobLocked(reason, internalReasonCode, debugReason);
                return true;
            }
        }
        Slog.wtf(TAG, "Couldn't find running job on a context");
        mRunningJobs.remove(job);
        return false;
    }

    @GuardedBy("mLock")
    private void stopUnexemptedJobsForDoze() {
        // When becoming idle, make sure no jobs are actively running,
        // except those using the idle exemption flag.
        for (int i = 0; i < mActiveServices.size(); i++) {
            JobServiceContext jsc = mActiveServices.get(i);
            final JobStatus executing = jsc.getRunningJobLocked();
            if (executing != null && !executing.canRunInDoze()) {
                jsc.cancelExecutingJobLocked(JobParameters.STOP_REASON_DEVICE_STATE,
                        JobParameters.INTERNAL_STOP_REASON_DEVICE_IDLE,
                        "cancelled due to doze");
            }
        }
    }

    @GuardedBy("mLock")
    private void stopLongRunningJobsLocked(@NonNull String debugReason) {
        for (int i = 0; i < mActiveServices.size(); ++i) {
            final JobServiceContext jsc = mActiveServices.get(i);
            final JobStatus jobStatus = jsc.getRunningJobLocked();

            if (jobStatus != null && !jsc.isWithinExecutionGuaranteeTime()) {
                jsc.cancelExecutingJobLocked(JobParameters.STOP_REASON_DEVICE_STATE,
                        JobParameters.INTERNAL_STOP_REASON_TIMEOUT, debugReason);
            }
        }
    }

    @GuardedBy("mLock")
    void stopNonReadyActiveJobsLocked() {
        for (int i = 0; i < mActiveServices.size(); i++) {
            JobServiceContext serviceContext = mActiveServices.get(i);
            final JobStatus running = serviceContext.getRunningJobLocked();
            if (running == null) {
                continue;
            }
            if (!running.isReady()) {
                if (running.getEffectiveStandbyBucket() == RESTRICTED_INDEX
                        && running.getStopReason() == JobParameters.STOP_REASON_APP_STANDBY) {
                    serviceContext.cancelExecutingJobLocked(
                            running.getStopReason(),
                            JobParameters.INTERNAL_STOP_REASON_RESTRICTED_BUCKET,
                            "cancelled due to restricted bucket");
                } else {
                    serviceContext.cancelExecutingJobLocked(
                            running.getStopReason(),
                            JobParameters.INTERNAL_STOP_REASON_CONSTRAINTS_NOT_SATISFIED,
                            "cancelled due to unsatisfied constraints");
                }
            } else {
                final JobRestriction restriction = mService.checkIfRestricted(running);
                if (restriction != null) {
                    final int internalReasonCode = restriction.getInternalReason();
                    serviceContext.cancelExecutingJobLocked(restriction.getReason(),
                            internalReasonCode,
                            "restricted due to "
                                    + JobParameters.getInternalReasonCodeDescription(
                                    internalReasonCode));
                }
            }
        }
    }

    private void noteConcurrency() {
        mService.mJobPackageTracker.noteConcurrency(mRunningJobs.size(),
                // TODO: log per type instead of only TOP
                mWorkCountTracker.getRunningJobCount(WORK_TYPE_TOP));
    }

    @GuardedBy("mLock")
    private void updateNonRunningPrioritiesLocked(@NonNull final PendingJobQueue jobQueue,
            boolean updateCounter) {
        JobStatus pending;
        jobQueue.resetIterator();
        while ((pending = jobQueue.next()) != null) {

            // If job is already running, go to next job.
            if (mRunningJobs.contains(pending)) {
                continue;
            }

            pending.lastEvaluatedBias = mService.evaluateJobBiasLocked(pending);

            if (updateCounter) {
                mWorkCountTracker.incrementPendingJobCount(getJobWorkTypes(pending));
            }
        }
    }

    @GuardedBy("mLock")
    @NonNull
    private PackageStats getPkgStatsLocked(int userId, @NonNull String packageName) {
        PackageStats packageStats = mActivePkgStats.get(userId, packageName);
        if (packageStats == null) {
            packageStats = mPkgStatsPool.acquire();
            if (packageStats == null) {
                packageStats = new PackageStats();
            }
            packageStats.setPackage(userId, packageName);
        }
        return packageStats;
    }

    @GuardedBy("mLock")
    @VisibleForTesting
    boolean isPkgConcurrencyLimitedLocked(@NonNull JobStatus jobStatus) {
        if (jobStatus.lastEvaluatedBias >= JobInfo.BIAS_TOP_APP) {
            // Don't restrict top apps' concurrency. The work type limits will make sure
            // background jobs have slots to run if the system has resources.
            return false;
        }
        // Use < instead of <= as that gives us a little wiggle room in case a new job comes
        // along very shortly.
        if (mService.getPendingJobQueue().size() + mRunningJobs.size()
                < mWorkTypeConfig.getMaxTotal()) {
            // Don't artificially limit a single package if we don't even have enough jobs to use
            // the maximum number of slots. We'll preempt the job later if we need the slot.
            return false;
        }
        final PackageStats packageStats =
                mActivePkgStats.get(jobStatus.getSourceUserId(), jobStatus.getSourcePackageName());
        if (packageStats == null) {
            // No currently running jobs.
            return false;
        }
        if (jobStatus.shouldTreatAsExpeditedJob()) {
            return packageStats.numRunningEj + packageStats.numStagedEj >= mPkgConcurrencyLimitEj;
        } else {
            return packageStats.numRunningRegular + packageStats.numStagedRegular
                    >= mPkgConcurrencyLimitRegular;
        }
    }

    @GuardedBy("mLock")
    private void startJobLocked(@NonNull JobServiceContext worker, @NonNull JobStatus jobStatus,
            @WorkType final int workType) {
        final List<StateController> controllers = mService.mControllers;
        final int numControllers = controllers.size();
        final PowerManager.WakeLock wl =
                mPowerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, jobStatus.getTag());
        wl.setWorkSource(mService.deriveWorkSource(
                jobStatus.getSourceUid(), jobStatus.getSourcePackageName()));
        wl.setReferenceCounted(false);
        // Since the quota controller will start counting from the time prepareForExecutionLocked()
        // is called, hold a wakelock to make sure the CPU doesn't suspend between that call and
        // when the service actually starts.
        wl.acquire();
        try {
            for (int ic = 0; ic < numControllers; ic++) {
                controllers.get(ic).prepareForExecutionLocked(jobStatus);
            }
            final PackageStats packageStats = getPkgStatsLocked(
                    jobStatus.getSourceUserId(), jobStatus.getSourcePackageName());
            packageStats.adjustStagedCount(false, jobStatus.shouldTreatAsExpeditedJob());
            if (!worker.executeRunnableJob(jobStatus, workType)) {
                Slog.e(TAG, "Error executing " + jobStatus);
                mWorkCountTracker.onStagedJobFailed(workType);
                for (int ic = 0; ic < numControllers; ic++) {
                    controllers.get(ic).unprepareFromExecutionLocked(jobStatus);
                }
            } else {
                mRunningJobs.add(jobStatus);
                mActiveServices.add(worker);
                mIdleContexts.remove(worker);
                mWorkCountTracker.onJobStarted(workType);
                packageStats.adjustRunningCount(true, jobStatus.shouldTreatAsExpeditedJob());
                mActivePkgStats.add(
                        jobStatus.getSourceUserId(), jobStatus.getSourcePackageName(),
                        packageStats);
            }
            if (mService.getPendingJobQueue().remove(jobStatus)) {
                mService.mJobPackageTracker.noteNonpending(jobStatus);
            }
        } finally {
            wl.release();
        }
    }

    @GuardedBy("mLock")
    void onJobCompletedLocked(@NonNull JobServiceContext worker, @NonNull JobStatus jobStatus,
            @WorkType final int workType) {
        mWorkCountTracker.onJobFinished(workType);
        mRunningJobs.remove(jobStatus);
        mActiveServices.remove(worker);
        if (mIdleContexts.size() < MAX_RETAINED_OBJECTS) {
            // Don't need to save all new contexts, but keep some extra around in case we need
            // extras for another TOP+EJ overage.
            mIdleContexts.add(worker);
        } else {
            mNumDroppedContexts++;
        }
        final PackageStats packageStats =
                mActivePkgStats.get(jobStatus.getSourceUserId(), jobStatus.getSourcePackageName());
        if (packageStats == null) {
            Slog.wtf(TAG, "Running job didn't have an active PackageStats object");
        } else {
            packageStats.adjustRunningCount(false, jobStatus.startedAsExpeditedJob);
            if (packageStats.numRunningEj <= 0 && packageStats.numRunningRegular <= 0) {
                mActivePkgStats.delete(packageStats.userId, packageStats.packageName);
                mPkgStatsPool.release(packageStats);
            }
        }

        final PendingJobQueue pendingJobQueue = mService.getPendingJobQueue();
        if (mActiveServices.size() >= STANDARD_CONCURRENCY_LIMIT || pendingJobQueue.size() == 0) {
            worker.clearPreferredUid();
            // We're over the limit (because the TOP app scheduled a lot of EJs). Don't start
            // running anything new until we get back below the limit.
            noteConcurrency();
            return;
        }

        if (worker.getPreferredUid() != JobServiceContext.NO_PREFERRED_UID) {
            updateCounterConfigLocked();
            // Preemption case needs special care.
            updateNonRunningPrioritiesLocked(pendingJobQueue, false);

            JobStatus highestBiasJob = null;
            int highBiasWorkType = workType;
            int highBiasAllWorkTypes = workType;
            JobStatus backupJob = null;
            int backupWorkType = WORK_TYPE_NONE;
            int backupAllWorkTypes = WORK_TYPE_NONE;

            JobStatus nextPending;
            pendingJobQueue.resetIterator();
            while ((nextPending = pendingJobQueue.next()) != null) {
                if (mRunningJobs.contains(nextPending)) {
                    // Should never happen.
                    Slog.wtf(TAG, "Pending queue contained a running job");
                    if (DEBUG) {
                        Slog.e(TAG, "Pending+running job: " + nextPending);
                    }
                    pendingJobQueue.remove(nextPending);
                    continue;
                }

                if (DEBUG && isSimilarJobRunningLocked(nextPending)) {
                    Slog.w(TAG, "Already running similar job to: " + nextPending);
                }

                if (worker.getPreferredUid() != nextPending.getUid()) {
                    if (backupJob == null && !isPkgConcurrencyLimitedLocked(nextPending)) {
                        int allWorkTypes = getJobWorkTypes(nextPending);
                        int workAsType = mWorkCountTracker.canJobStart(allWorkTypes);
                        if (workAsType != WORK_TYPE_NONE) {
                            backupJob = nextPending;
                            backupWorkType = workAsType;
                            backupAllWorkTypes = allWorkTypes;
                        }
                    }
                    continue;
                }

                // Only bypass the concurrent limit if we had preempted the job due to a higher
                // bias job.
                if (nextPending.lastEvaluatedBias <= jobStatus.lastEvaluatedBias
                        && isPkgConcurrencyLimitedLocked(nextPending)) {
                    continue;
                }

                if (highestBiasJob == null
                        || highestBiasJob.lastEvaluatedBias < nextPending.lastEvaluatedBias) {
                    highestBiasJob = nextPending;
                } else {
                    continue;
                }

                // In this path, we pre-empted an existing job. We don't fully care about the
                // reserved slots. We should just run the highest bias job we can find,
                // though it would be ideal to use an available WorkType slot instead of
                // overloading slots.
                highBiasAllWorkTypes = getJobWorkTypes(nextPending);
                final int workAsType = mWorkCountTracker.canJobStart(highBiasAllWorkTypes);
                if (workAsType == WORK_TYPE_NONE) {
                    // Just use the preempted job's work type since this new one is technically
                    // replacing it anyway.
                    highBiasWorkType = workType;
                } else {
                    highBiasWorkType = workAsType;
                }
            }
            if (highestBiasJob != null) {
                if (DEBUG) {
                    Slog.d(TAG, "Running job " + highestBiasJob + " as preemption");
                }
                mWorkCountTracker.stageJob(highBiasWorkType, highBiasAllWorkTypes);
                startJobLocked(worker, highestBiasJob, highBiasWorkType);
            } else {
                if (DEBUG) {
                    Slog.d(TAG, "Couldn't find preemption job for uid " + worker.getPreferredUid());
                }
                worker.clearPreferredUid();
                if (backupJob != null) {
                    if (DEBUG) {
                        Slog.d(TAG, "Running job " + backupJob + " instead");
                    }
                    mWorkCountTracker.stageJob(backupWorkType, backupAllWorkTypes);
                    startJobLocked(worker, backupJob, backupWorkType);
                }
            }
        } else if (pendingJobQueue.size() > 0) {
            updateCounterConfigLocked();
            updateNonRunningPrioritiesLocked(pendingJobQueue, false);

            // This slot is now free and we have pending jobs. Start the highest bias job we find.
            JobStatus highestBiasJob = null;
            int highBiasWorkType = workType;
            int highBiasAllWorkTypes = workType;

            JobStatus nextPending;
            pendingJobQueue.resetIterator();
            while ((nextPending = pendingJobQueue.next()) != null) {

                if (mRunningJobs.contains(nextPending)) {
                    // Should never happen.
                    Slog.wtf(TAG, "Pending queue contained a running job");
                    if (DEBUG) {
                        Slog.e(TAG, "Pending+running job: " + nextPending);
                    }
                    pendingJobQueue.remove(nextPending);
                    continue;
                }

                if (DEBUG && isSimilarJobRunningLocked(nextPending)) {
                    Slog.w(TAG, "Already running similar job to: " + nextPending);
                }

                if (isPkgConcurrencyLimitedLocked(nextPending)) {
                    continue;
                }

                final int allWorkTypes = getJobWorkTypes(nextPending);
                final int workAsType = mWorkCountTracker.canJobStart(allWorkTypes);
                if (workAsType == WORK_TYPE_NONE) {
                    continue;
                }
                if (highestBiasJob == null
                        || highestBiasJob.lastEvaluatedBias < nextPending.lastEvaluatedBias) {
                    highestBiasJob = nextPending;
                    highBiasWorkType = workAsType;
                    highBiasAllWorkTypes = allWorkTypes;
                }
            }

            if (highestBiasJob != null) {
                // This slot is free, and we haven't yet hit the limit on
                // concurrent jobs...  we can just throw the job in to here.
                if (DEBUG) {
                    Slog.d(TAG, "About to run job: " + highestBiasJob);
                }
                mWorkCountTracker.stageJob(highBiasWorkType, highBiasAllWorkTypes);
                startJobLocked(worker, highestBiasJob, highBiasWorkType);
            }
        }

        noteConcurrency();
    }

    /**
     * Returns {@code null} if the job can continue running and a non-null String if the job should
     * be stopped. The non-null String details the reason for stopping the job. A job will generally
     * be stopped if there are similar job types waiting to be run and stopping this job would allow
     * another job to run, or if system state suggests the job should stop.
     */
    @Nullable
    @GuardedBy("mLock")
    String shouldStopRunningJobLocked(@NonNull JobServiceContext context) {
        final JobStatus js = context.getRunningJobLocked();
        if (js == null) {
            // This can happen when we try to assign newly found pending jobs to contexts.
            return null;
        }

        if (context.isWithinExecutionGuaranteeTime()) {
            return null;
        }

        // We're over the minimum guaranteed runtime. Stop the job if we're over config limits,
        // there are pending jobs that could replace this one, or the device state is not conducive
        // to long runs.

        if (mPowerManager.isPowerSaveMode()) {
            return "battery saver";
        }
        if (mPowerManager.isDeviceIdleMode()) {
            return "deep doze";
        }

        // Update config in case memory usage has changed significantly.
        updateCounterConfigLocked();

        @WorkType final int workType = context.getRunningJobWorkType();

        if (mRunningJobs.size() > mWorkTypeConfig.getMaxTotal()
                || mWorkCountTracker.isOverTypeLimit(workType)) {
            return "too many jobs running";
        }

        final PendingJobQueue pendingJobQueue = mService.getPendingJobQueue();
        final int numPending = pendingJobQueue.size();
        if (numPending == 0) {
            // All quiet. We can let this job run to completion.
            return null;
        }

        // Only expedited jobs can replace expedited jobs.
        if (js.shouldTreatAsExpeditedJob() || js.startedAsExpeditedJob) {
            // Keep fg/bg user distinction.
            if (workType == WORK_TYPE_BGUSER_IMPORTANT || workType == WORK_TYPE_BGUSER) {
                // Let any important bg user job replace a bg user expedited job.
                if (mWorkCountTracker.getPendingJobCount(WORK_TYPE_BGUSER_IMPORTANT) > 0) {
                    return "blocking " + workTypeToString(WORK_TYPE_BGUSER_IMPORTANT) + " queue";
                }
                // Let a fg user EJ preempt a bg user EJ (if able), but not the other way around.
                if (mWorkCountTracker.getPendingJobCount(WORK_TYPE_EJ) > 0
                        && mWorkCountTracker.canJobStart(WORK_TYPE_EJ, workType)
                        != WORK_TYPE_NONE) {
                    return "blocking " + workTypeToString(WORK_TYPE_EJ) + " queue";
                }
            } else if (mWorkCountTracker.getPendingJobCount(WORK_TYPE_EJ) > 0) {
                return "blocking " + workTypeToString(WORK_TYPE_EJ) + " queue";
            } else if (js.startedAsExpeditedJob && js.lastEvaluatedBias == JobInfo.BIAS_TOP_APP) {
                // Try not to let TOP + EJ starve out other apps.
                int topEjCount = 0;
                for (int r = mRunningJobs.size() - 1; r >= 0; --r) {
                    JobStatus j = mRunningJobs.valueAt(r);
                    if (j.startedAsExpeditedJob && j.lastEvaluatedBias == JobInfo.BIAS_TOP_APP) {
                        topEjCount++;
                    }
                }
                if (topEjCount > .5 * mWorkTypeConfig.getMaxTotal()) {
                    return "prevent top EJ dominance";
                }
            }
            // No other pending EJs. Return null so we don't let regular jobs preempt an EJ.
            return null;
        }

        // Easy check. If there are pending jobs of the same work type, then we know that
        // something will replace this.
        if (mWorkCountTracker.getPendingJobCount(workType) > 0) {
            return "blocking " + workTypeToString(workType) + " queue";
        }

        // Harder check. We need to see if a different work type can replace this job.
        int remainingWorkTypes = ALL_WORK_TYPES;
        JobStatus pending;
        pendingJobQueue.resetIterator();
        while ((pending = pendingJobQueue.next()) != null) {
            final int workTypes = getJobWorkTypes(pending);
            if ((workTypes & remainingWorkTypes) > 0
                    && mWorkCountTracker.canJobStart(workTypes, workType) != WORK_TYPE_NONE) {
                return "blocking other pending jobs";
            }

            remainingWorkTypes = remainingWorkTypes & ~workTypes;
            if (remainingWorkTypes == 0) {
                break;
            }
        }

        return null;
    }

    @GuardedBy("mLock")
    boolean executeTimeoutCommandLocked(PrintWriter pw, String pkgName, int userId,
            boolean hasJobId, int jobId) {
        boolean foundSome = false;
        for (int i = 0; i < mActiveServices.size(); i++) {
            final JobServiceContext jc = mActiveServices.get(i);
            final JobStatus js = jc.getRunningJobLocked();
            if (jc.timeoutIfExecutingLocked(pkgName, userId, hasJobId, jobId, "shell")) {
                foundSome = true;
                pw.print("Timing out: ");
                js.printUniqueId(pw);
                pw.print(" ");
                pw.println(js.getServiceComponent().flattenToShortString());
            }
        }
        return foundSome;
    }

    @NonNull
    private JobServiceContext createNewJobServiceContext() {
        return new JobServiceContext(mService, this,
                IBatteryStats.Stub.asInterface(
                        ServiceManager.getService(BatteryStats.SERVICE_NAME)),
                mService.mJobPackageTracker, mContext.getMainLooper());
    }

    @GuardedBy("mLock")
    private String printPendingQueueLocked() {
        StringBuilder s = new StringBuilder("Pending queue: ");
        PendingJobQueue pendingJobQueue = mService.getPendingJobQueue();
        JobStatus js;
        pendingJobQueue.resetIterator();
        while ((js = pendingJobQueue.next()) != null) {
            s.append("(")
                    .append(js.getJob().getId())
                    .append(", ")
                    .append(js.getUid())
                    .append(") ");
        }
        return s.toString();
    }

    private static String printAssignments(String header, Collection<ContextAssignment>... list) {
        final StringBuilder s = new StringBuilder(header + ": ");
        for (int l = 0; l < list.length; ++l) {
            final Collection<ContextAssignment> assignments = list[l];
            int c = 0;
            for (final ContextAssignment assignment : assignments) {
                final JobStatus job = assignment.newJob == null
                        ? assignment.context.getRunningJobLocked() : assignment.newJob;

                if (l > 0 || c > 0) {
                    s.append(" ");
                }
                s.append("(").append(assignment.context.getId()).append("=");
                if (job == null) {
                    s.append("nothing");
                } else {
                    s.append(job.getJobId()).append("/").append(job.getUid());
                }
                s.append(")");
                c++;
            }
        }
        return s.toString();
    }

    @GuardedBy("mLock")
    void updateConfigLocked() {
        DeviceConfig.Properties properties =
                DeviceConfig.getProperties(DeviceConfig.NAMESPACE_JOB_SCHEDULER);

        mScreenOffAdjustmentDelayMs = properties.getLong(
                KEY_SCREEN_OFF_ADJUSTMENT_DELAY_MS, DEFAULT_SCREEN_OFF_ADJUSTMENT_DELAY_MS);

        CONFIG_LIMITS_SCREEN_ON.normal.update(properties);
        CONFIG_LIMITS_SCREEN_ON.moderate.update(properties);
        CONFIG_LIMITS_SCREEN_ON.low.update(properties);
        CONFIG_LIMITS_SCREEN_ON.critical.update(properties);

        CONFIG_LIMITS_SCREEN_OFF.normal.update(properties);
        CONFIG_LIMITS_SCREEN_OFF.moderate.update(properties);
        CONFIG_LIMITS_SCREEN_OFF.low.update(properties);
        CONFIG_LIMITS_SCREEN_OFF.critical.update(properties);

        // Package concurrency limits must in the range [1, STANDARD_CONCURRENCY_LIMIT].
        mPkgConcurrencyLimitEj = Math.max(1, Math.min(STANDARD_CONCURRENCY_LIMIT,
                properties.getInt(KEY_PKG_CONCURRENCY_LIMIT_EJ, DEFAULT_PKG_CONCURRENCY_LIMIT_EJ)));
        mPkgConcurrencyLimitRegular = Math.max(1, Math.min(STANDARD_CONCURRENCY_LIMIT,
                properties.getInt(
                        KEY_PKG_CONCURRENCY_LIMIT_REGULAR, DEFAULT_PKG_CONCURRENCY_LIMIT_REGULAR)));
    }

    @GuardedBy("mLock")
    public void dumpLocked(IndentingPrintWriter pw, long now, long nowRealtime) {
        pw.println("Concurrency:");

        pw.increaseIndent();
        try {
            pw.println("Configuration:");
            pw.increaseIndent();
            pw.print(KEY_SCREEN_OFF_ADJUSTMENT_DELAY_MS, mScreenOffAdjustmentDelayMs).println();
            pw.print(KEY_PKG_CONCURRENCY_LIMIT_EJ, mPkgConcurrencyLimitEj).println();
            pw.print(KEY_PKG_CONCURRENCY_LIMIT_REGULAR, mPkgConcurrencyLimitRegular).println();
            pw.println();
            CONFIG_LIMITS_SCREEN_ON.normal.dump(pw);
            pw.println();
            CONFIG_LIMITS_SCREEN_ON.moderate.dump(pw);
            pw.println();
            CONFIG_LIMITS_SCREEN_ON.low.dump(pw);
            pw.println();
            CONFIG_LIMITS_SCREEN_ON.critical.dump(pw);
            pw.println();
            CONFIG_LIMITS_SCREEN_OFF.normal.dump(pw);
            pw.println();
            CONFIG_LIMITS_SCREEN_OFF.moderate.dump(pw);
            pw.println();
            CONFIG_LIMITS_SCREEN_OFF.low.dump(pw);
            pw.println();
            CONFIG_LIMITS_SCREEN_OFF.critical.dump(pw);
            pw.println();
            pw.decreaseIndent();

            pw.print("Screen state: current ");
            pw.print(mCurrentInteractiveState ? "ON" : "OFF");
            pw.print("  effective ");
            pw.print(mEffectiveInteractiveState ? "ON" : "OFF");
            pw.println();

            pw.print("Last screen ON: ");
            TimeUtils.dumpTimeWithDelta(pw, now - nowRealtime + mLastScreenOnRealtime, now);
            pw.println();

            pw.print("Last screen OFF: ");
            TimeUtils.dumpTimeWithDelta(pw, now - nowRealtime + mLastScreenOffRealtime, now);
            pw.println();

            pw.println();

            pw.print("Current work counts: ");
            pw.println(mWorkCountTracker);

            pw.println();

            pw.print("mLastMemoryTrimLevel: ");
            pw.println(mLastMemoryTrimLevel);
            pw.println();

            pw.println("Active Package stats:");
            pw.increaseIndent();
            mActivePkgStats.forEach(pkgStats -> pkgStats.dumpLocked(pw));
            pw.decreaseIndent();
            pw.println();

            pw.print("User Grace Period: ");
            pw.println(mGracePeriodObserver.mGracePeriodExpiration);
            pw.println();

            mStatLogger.dump(pw);
        } finally {
            pw.decreaseIndent();
        }
    }

    @GuardedBy("mLock")
    void dumpContextInfoLocked(IndentingPrintWriter pw, Predicate<JobStatus> predicate,
            long nowElapsed, long nowUptime) {
        pw.println("Active jobs:");
        pw.increaseIndent();
        if (mActiveServices.size() == 0) {
            pw.println("N/A");
        }
        for (int i = 0; i < mActiveServices.size(); i++) {
            JobServiceContext jsc = mActiveServices.get(i);
            final JobStatus job = jsc.getRunningJobLocked();

            if (job != null && !predicate.test(job)) {
                continue;
            }

            pw.print("Slot #"); pw.print(i);
            pw.print("(ID="); pw.print(jsc.getId()); pw.print("): ");
            jsc.dumpLocked(pw, nowElapsed);

            if (job != null) {
                pw.increaseIndent();

                pw.increaseIndent();
                job.dump(pw, false, nowElapsed);
                pw.decreaseIndent();

                pw.print("Evaluated bias: ");
                pw.println(JobInfo.getBiasString(job.lastEvaluatedBias));

                pw.print("Active at ");
                TimeUtils.formatDuration(job.madeActive - nowUptime, pw);
                pw.print(", pending for ");
                TimeUtils.formatDuration(job.madeActive - job.madePending, pw);
                pw.decreaseIndent();
                pw.println();
            }
        }
        pw.decreaseIndent();

        pw.println();
        pw.print("Idle contexts (");
        pw.print(mIdleContexts.size());
        pw.println("):");
        pw.increaseIndent();
        for (int i = 0; i < mIdleContexts.size(); i++) {
            JobServiceContext jsc = mIdleContexts.valueAt(i);

            pw.print("ID="); pw.print(jsc.getId()); pw.print(": ");
            jsc.dumpLocked(pw, nowElapsed);
        }
        pw.decreaseIndent();

        if (mNumDroppedContexts > 0) {
            pw.println();
            pw.print("Dropped ");
            pw.print(mNumDroppedContexts);
            pw.println(" contexts");
        }
    }

    public void dumpProtoLocked(ProtoOutputStream proto, long tag, long now, long nowRealtime) {
        final long token = proto.start(tag);

        proto.write(JobConcurrencyManagerProto.CURRENT_INTERACTIVE_STATE, mCurrentInteractiveState);
        proto.write(JobConcurrencyManagerProto.EFFECTIVE_INTERACTIVE_STATE,
                mEffectiveInteractiveState);

        proto.write(JobConcurrencyManagerProto.TIME_SINCE_LAST_SCREEN_ON_MS,
                nowRealtime - mLastScreenOnRealtime);
        proto.write(JobConcurrencyManagerProto.TIME_SINCE_LAST_SCREEN_OFF_MS,
                nowRealtime - mLastScreenOffRealtime);

        proto.write(JobConcurrencyManagerProto.MEMORY_TRIM_LEVEL, mLastMemoryTrimLevel);

        mStatLogger.dumpProto(proto, JobConcurrencyManagerProto.STATS);

        proto.end(token);
    }

    /**
     * Decides whether a job is from the current foreground user or the equivalent.
     */
    @VisibleForTesting
    boolean shouldRunAsFgUserJob(JobStatus job) {
        if (!mShouldRestrictBgUser) return true;
        int userId = job.getSourceUserId();
        UserManagerInternal um = LocalServices.getService(UserManagerInternal.class);
        UserInfo userInfo = um.getUserInfo(userId);

        // If the user has a parent user (e.g. a work profile of another user), the user should be
        // treated equivalent as its parent user.
        if (userInfo.profileGroupId != UserInfo.NO_PROFILE_GROUP_ID
                && userInfo.profileGroupId != userId) {
            userId = userInfo.profileGroupId;
            userInfo = um.getUserInfo(userId);
        }

        int currentUser = LocalServices.getService(ActivityManagerInternal.class)
                .getCurrentUserId();
        // A user is treated as foreground user if any of the followings is true:
        // 1. The user is current user
        // 2. The user is primary user
        // 3. The user's grace period has not expired
        return currentUser == userId || userInfo.isPrimary()
                || mGracePeriodObserver.isWithinGracePeriodForUser(userId);
    }

    int getJobWorkTypes(@NonNull JobStatus js) {
        int classification = 0;

        if (shouldRunAsFgUserJob(js)) {
            if (js.lastEvaluatedBias >= JobInfo.BIAS_TOP_APP) {
                classification |= WORK_TYPE_TOP;
            } else if (js.lastEvaluatedBias >= JobInfo.BIAS_FOREGROUND_SERVICE) {
                classification |= WORK_TYPE_FGS;
            } else {
                classification |= WORK_TYPE_BG;
            }

            if (js.shouldTreatAsExpeditedJob()) {
                classification |= WORK_TYPE_EJ;
            }
        } else {
            if (js.lastEvaluatedBias >= JobInfo.BIAS_FOREGROUND_SERVICE
                    || js.shouldTreatAsExpeditedJob()) {
                classification |= WORK_TYPE_BGUSER_IMPORTANT;
            }
            // BGUSER_IMPORTANT jobs can also run as BGUSER jobs, so not an 'else' here.
            classification |= WORK_TYPE_BGUSER;
        }

        return classification;
    }

    @VisibleForTesting
    static class WorkTypeConfig {
        @VisibleForTesting
        static final String KEY_PREFIX_MAX_TOTAL = CONFIG_KEY_PREFIX_CONCURRENCY + "max_total_";
        private static final String KEY_PREFIX_MAX_TOP = CONFIG_KEY_PREFIX_CONCURRENCY + "max_top_";
        private static final String KEY_PREFIX_MAX_FGS = CONFIG_KEY_PREFIX_CONCURRENCY + "max_fgs_";
        private static final String KEY_PREFIX_MAX_EJ = CONFIG_KEY_PREFIX_CONCURRENCY + "max_ej_";
        private static final String KEY_PREFIX_MAX_BG = CONFIG_KEY_PREFIX_CONCURRENCY + "max_bg_";
        private static final String KEY_PREFIX_MAX_BGUSER =
                CONFIG_KEY_PREFIX_CONCURRENCY + "max_bguser_";
        private static final String KEY_PREFIX_MAX_BGUSER_IMPORTANT =
                CONFIG_KEY_PREFIX_CONCURRENCY + "max_bguser_important_";
        private static final String KEY_PREFIX_MIN_TOP = CONFIG_KEY_PREFIX_CONCURRENCY + "min_top_";
        private static final String KEY_PREFIX_MIN_FGS = CONFIG_KEY_PREFIX_CONCURRENCY + "min_fgs_";
        private static final String KEY_PREFIX_MIN_EJ = CONFIG_KEY_PREFIX_CONCURRENCY + "min_ej_";
        private static final String KEY_PREFIX_MIN_BG = CONFIG_KEY_PREFIX_CONCURRENCY + "min_bg_";
        private static final String KEY_PREFIX_MIN_BGUSER =
                CONFIG_KEY_PREFIX_CONCURRENCY + "min_bguser_";
        private static final String KEY_PREFIX_MIN_BGUSER_IMPORTANT =
                CONFIG_KEY_PREFIX_CONCURRENCY + "min_bguser_important_";
        private final String mConfigIdentifier;

        private int mMaxTotal;
        private final SparseIntArray mMinReservedSlots = new SparseIntArray(NUM_WORK_TYPES);
        private final SparseIntArray mMaxAllowedSlots = new SparseIntArray(NUM_WORK_TYPES);
        private final int mDefaultMaxTotal;
        private final SparseIntArray mDefaultMinReservedSlots = new SparseIntArray(NUM_WORK_TYPES);
        private final SparseIntArray mDefaultMaxAllowedSlots = new SparseIntArray(NUM_WORK_TYPES);

        WorkTypeConfig(@NonNull String configIdentifier, int defaultMaxTotal,
                List<Pair<Integer, Integer>> defaultMin, List<Pair<Integer, Integer>> defaultMax) {
            mConfigIdentifier = configIdentifier;
            mDefaultMaxTotal = mMaxTotal = Math.min(defaultMaxTotal, STANDARD_CONCURRENCY_LIMIT);
            int numReserved = 0;
            for (int i = defaultMin.size() - 1; i >= 0; --i) {
                mDefaultMinReservedSlots.put(defaultMin.get(i).first, defaultMin.get(i).second);
                numReserved += defaultMin.get(i).second;
            }
            if (mDefaultMaxTotal < 0 || numReserved > mDefaultMaxTotal) {
                // We only create new configs on boot, so this should trigger during development
                // (before the code gets checked in), so this makes sure the hard-coded defaults
                // make sense. DeviceConfig values will be handled gracefully in update().
                throw new IllegalArgumentException("Invalid default config: t=" + defaultMaxTotal
                        + " min=" + defaultMin + " max=" + defaultMax);
            }
            for (int i = defaultMax.size() - 1; i >= 0; --i) {
                mDefaultMaxAllowedSlots.put(defaultMax.get(i).first, defaultMax.get(i).second);
            }
            update(new DeviceConfig.Properties.Builder(
                    DeviceConfig.NAMESPACE_JOB_SCHEDULER).build());
        }

        void update(@NonNull DeviceConfig.Properties properties) {
            // Ensure total in the range [1, STANDARD_CONCURRENCY_LIMIT].
            mMaxTotal = Math.max(1, Math.min(STANDARD_CONCURRENCY_LIMIT,
                    properties.getInt(KEY_PREFIX_MAX_TOTAL + mConfigIdentifier, mDefaultMaxTotal)));

            mMaxAllowedSlots.clear();
            // Ensure they're in the range [1, total].
            final int maxTop = Math.max(1, Math.min(mMaxTotal,
                    properties.getInt(KEY_PREFIX_MAX_TOP + mConfigIdentifier,
                            mDefaultMaxAllowedSlots.get(WORK_TYPE_TOP, mMaxTotal))));
            mMaxAllowedSlots.put(WORK_TYPE_TOP, maxTop);
            final int maxFgs = Math.max(1, Math.min(mMaxTotal,
                    properties.getInt(KEY_PREFIX_MAX_FGS + mConfigIdentifier,
                            mDefaultMaxAllowedSlots.get(WORK_TYPE_FGS, mMaxTotal))));
            mMaxAllowedSlots.put(WORK_TYPE_FGS, maxFgs);
            final int maxEj = Math.max(1, Math.min(mMaxTotal,
                    properties.getInt(KEY_PREFIX_MAX_EJ + mConfigIdentifier,
                            mDefaultMaxAllowedSlots.get(WORK_TYPE_EJ, mMaxTotal))));
            mMaxAllowedSlots.put(WORK_TYPE_EJ, maxEj);
            final int maxBg = Math.max(1, Math.min(mMaxTotal,
                    properties.getInt(KEY_PREFIX_MAX_BG + mConfigIdentifier,
                            mDefaultMaxAllowedSlots.get(WORK_TYPE_BG, mMaxTotal))));
            mMaxAllowedSlots.put(WORK_TYPE_BG, maxBg);
            final int maxBgUserImp = Math.max(1, Math.min(mMaxTotal,
                    properties.getInt(KEY_PREFIX_MAX_BGUSER_IMPORTANT + mConfigIdentifier,
                            mDefaultMaxAllowedSlots.get(WORK_TYPE_BGUSER_IMPORTANT, mMaxTotal))));
            mMaxAllowedSlots.put(WORK_TYPE_BGUSER_IMPORTANT, maxBgUserImp);
            final int maxBgUser = Math.max(1, Math.min(mMaxTotal,
                    properties.getInt(KEY_PREFIX_MAX_BGUSER + mConfigIdentifier,
                            mDefaultMaxAllowedSlots.get(WORK_TYPE_BGUSER, mMaxTotal))));
            mMaxAllowedSlots.put(WORK_TYPE_BGUSER, maxBgUser);

            int remaining = mMaxTotal;
            mMinReservedSlots.clear();
            // Ensure top is in the range [1, min(maxTop, total)]
            final int minTop = Math.max(1, Math.min(Math.min(maxTop, mMaxTotal),
                    properties.getInt(KEY_PREFIX_MIN_TOP + mConfigIdentifier,
                            mDefaultMinReservedSlots.get(WORK_TYPE_TOP))));
            mMinReservedSlots.put(WORK_TYPE_TOP, minTop);
            remaining -= minTop;
            // Ensure fgs is in the range [0, min(maxFgs, remaining)]
            final int minFgs = Math.max(0, Math.min(Math.min(maxFgs, remaining),
                    properties.getInt(KEY_PREFIX_MIN_FGS + mConfigIdentifier,
                            mDefaultMinReservedSlots.get(WORK_TYPE_FGS))));
            mMinReservedSlots.put(WORK_TYPE_FGS, minFgs);
            remaining -= minFgs;
            // Ensure ej is in the range [0, min(maxEj, remaining)]
            final int minEj = Math.max(0, Math.min(Math.min(maxEj, remaining),
                    properties.getInt(KEY_PREFIX_MIN_EJ + mConfigIdentifier,
                            mDefaultMinReservedSlots.get(WORK_TYPE_EJ))));
            mMinReservedSlots.put(WORK_TYPE_EJ, minEj);
            remaining -= minEj;
            // Ensure bg is in the range [0, min(maxBg, remaining)]
            final int minBg = Math.max(0, Math.min(Math.min(maxBg, remaining),
                    properties.getInt(KEY_PREFIX_MIN_BG + mConfigIdentifier,
                            mDefaultMinReservedSlots.get(WORK_TYPE_BG))));
            mMinReservedSlots.put(WORK_TYPE_BG, minBg);
            remaining -= minBg;
            // Ensure bg user imp is in the range [0, min(maxBgUserImp, remaining)]
            final int minBgUserImp = Math.max(0, Math.min(Math.min(maxBgUserImp, remaining),
                    properties.getInt(KEY_PREFIX_MIN_BGUSER_IMPORTANT + mConfigIdentifier,
                            mDefaultMinReservedSlots.get(WORK_TYPE_BGUSER_IMPORTANT, 0))));
            mMinReservedSlots.put(WORK_TYPE_BGUSER_IMPORTANT, minBgUserImp);
            // Ensure bg user is in the range [0, min(maxBgUser, remaining)]
            final int minBgUser = Math.max(0, Math.min(Math.min(maxBgUser, remaining),
                    properties.getInt(KEY_PREFIX_MIN_BGUSER + mConfigIdentifier,
                            mDefaultMinReservedSlots.get(WORK_TYPE_BGUSER, 0))));
            mMinReservedSlots.put(WORK_TYPE_BGUSER, minBgUser);
        }

        int getMaxTotal() {
            return mMaxTotal;
        }

        int getMax(@WorkType int workType) {
            return mMaxAllowedSlots.get(workType, mMaxTotal);
        }

        int getMinReserved(@WorkType int workType) {
            return mMinReservedSlots.get(workType);
        }

        void dump(IndentingPrintWriter pw) {
            pw.print(KEY_PREFIX_MAX_TOTAL + mConfigIdentifier, mMaxTotal).println();
            pw.print(KEY_PREFIX_MIN_TOP + mConfigIdentifier, mMinReservedSlots.get(WORK_TYPE_TOP))
                    .println();
            pw.print(KEY_PREFIX_MAX_TOP + mConfigIdentifier, mMaxAllowedSlots.get(WORK_TYPE_TOP))
                    .println();
            pw.print(KEY_PREFIX_MIN_FGS + mConfigIdentifier, mMinReservedSlots.get(WORK_TYPE_FGS))
                    .println();
            pw.print(KEY_PREFIX_MAX_FGS + mConfigIdentifier, mMaxAllowedSlots.get(WORK_TYPE_FGS))
                    .println();
            pw.print(KEY_PREFIX_MIN_EJ + mConfigIdentifier, mMinReservedSlots.get(WORK_TYPE_EJ))
                    .println();
            pw.print(KEY_PREFIX_MAX_EJ + mConfigIdentifier, mMaxAllowedSlots.get(WORK_TYPE_EJ))
                    .println();
            pw.print(KEY_PREFIX_MIN_BG + mConfigIdentifier, mMinReservedSlots.get(WORK_TYPE_BG))
                    .println();
            pw.print(KEY_PREFIX_MAX_BG + mConfigIdentifier, mMaxAllowedSlots.get(WORK_TYPE_BG))
                    .println();
            pw.print(KEY_PREFIX_MIN_BGUSER + mConfigIdentifier,
                    mMinReservedSlots.get(WORK_TYPE_BGUSER_IMPORTANT)).println();
            pw.print(KEY_PREFIX_MAX_BGUSER + mConfigIdentifier,
                    mMaxAllowedSlots.get(WORK_TYPE_BGUSER_IMPORTANT)).println();
            pw.print(KEY_PREFIX_MIN_BGUSER + mConfigIdentifier,
                    mMinReservedSlots.get(WORK_TYPE_BGUSER)).println();
            pw.print(KEY_PREFIX_MAX_BGUSER + mConfigIdentifier,
                    mMaxAllowedSlots.get(WORK_TYPE_BGUSER)).println();
        }
    }

    /** {@link WorkTypeConfig} for each memory trim level. */
    static class WorkConfigLimitsPerMemoryTrimLevel {
        public final WorkTypeConfig normal;
        public final WorkTypeConfig moderate;
        public final WorkTypeConfig low;
        public final WorkTypeConfig critical;

        WorkConfigLimitsPerMemoryTrimLevel(WorkTypeConfig normal, WorkTypeConfig moderate,
                WorkTypeConfig low, WorkTypeConfig critical) {
            this.normal = normal;
            this.moderate = moderate;
            this.low = low;
            this.critical = critical;
        }
    }

    /**
     * This class keeps the track of when a user's grace period expires.
     */
    @VisibleForTesting
    static class GracePeriodObserver extends UserSwitchObserver {
        // Key is UserId and Value is the time when grace period expires
        @VisibleForTesting
        final SparseLongArray mGracePeriodExpiration = new SparseLongArray();
        private int mCurrentUserId;
        @VisibleForTesting
        int mGracePeriod;
        private final UserManagerInternal mUserManagerInternal;
        final Object mLock = new Object();


        GracePeriodObserver(Context context) {
            mCurrentUserId = LocalServices.getService(ActivityManagerInternal.class)
                    .getCurrentUserId();
            mUserManagerInternal = LocalServices.getService(UserManagerInternal.class);
            mGracePeriod = Math.max(0, context.getResources().getInteger(
                    R.integer.config_jobSchedulerUserGracePeriod));
        }

        @Override
        public void onUserSwitchComplete(int newUserId) {
            final long expiration = sElapsedRealtimeClock.millis() + mGracePeriod;
            synchronized (mLock) {
                if (mCurrentUserId != UserHandle.USER_NULL
                        && mUserManagerInternal.exists(mCurrentUserId)) {
                    mGracePeriodExpiration.append(mCurrentUserId, expiration);
                }
                mGracePeriodExpiration.delete(newUserId);
                mCurrentUserId = newUserId;
            }
        }

        void onUserRemoved(int userId) {
            synchronized (mLock) {
                mGracePeriodExpiration.delete(userId);
            }
        }

        @VisibleForTesting
        public boolean isWithinGracePeriodForUser(int userId) {
            synchronized (mLock) {
                return userId == mCurrentUserId
                        || sElapsedRealtimeClock.millis()
                        < mGracePeriodExpiration.get(userId, Long.MAX_VALUE);
            }
        }
    }

    /**
     * This class decides, taking into account the current {@link WorkTypeConfig} and how many jobs
     * are running/pending, how many more job can start.
     *
     * Extracted for testing and logging.
     */
    @VisibleForTesting
    static class WorkCountTracker {
        private int mConfigMaxTotal;
        private final SparseIntArray mConfigNumReservedSlots = new SparseIntArray(NUM_WORK_TYPES);
        private final SparseIntArray mConfigAbsoluteMaxSlots = new SparseIntArray(NUM_WORK_TYPES);
        private final SparseIntArray mRecycledReserved = new SparseIntArray(NUM_WORK_TYPES);

        /**
         * Numbers may be lower in this than in {@link #mConfigNumReservedSlots} if there aren't
         * enough ready jobs of a type to take up all of the desired reserved slots.
         */
        private final SparseIntArray mNumActuallyReservedSlots = new SparseIntArray(NUM_WORK_TYPES);
        private final SparseIntArray mNumPendingJobs = new SparseIntArray(NUM_WORK_TYPES);
        private final SparseIntArray mNumRunningJobs = new SparseIntArray(NUM_WORK_TYPES);
        private final SparseIntArray mNumStartingJobs = new SparseIntArray(NUM_WORK_TYPES);
        private int mNumUnspecializedRemaining = 0;

        void setConfig(@NonNull WorkTypeConfig workTypeConfig) {
            mConfigMaxTotal = workTypeConfig.getMaxTotal();
            for (int workType = 1; workType < ALL_WORK_TYPES; workType <<= 1) {
                mConfigNumReservedSlots.put(workType, workTypeConfig.getMinReserved(workType));
                mConfigAbsoluteMaxSlots.put(workType, workTypeConfig.getMax(workType));
            }

            mNumUnspecializedRemaining = mConfigMaxTotal;
            for (int i = mNumRunningJobs.size() - 1; i >= 0; --i) {
                mNumUnspecializedRemaining -= Math.max(mNumRunningJobs.valueAt(i),
                        mConfigNumReservedSlots.get(mNumRunningJobs.keyAt(i)));
            }
        }

        void resetCounts() {
            mNumActuallyReservedSlots.clear();
            mNumPendingJobs.clear();
            mNumRunningJobs.clear();
            resetStagingCount();
        }

        void resetStagingCount() {
            mNumStartingJobs.clear();
        }

        void incrementRunningJobCount(@WorkType int workType) {
            mNumRunningJobs.put(workType, mNumRunningJobs.get(workType) + 1);
        }

        void incrementPendingJobCount(int workTypes) {
            adjustPendingJobCount(workTypes, true);
        }

        void decrementPendingJobCount(int workTypes) {
            if (adjustPendingJobCount(workTypes, false) > 1) {
                // We don't need to adjust reservations if only one work type was modified
                // because that work type is the one we're using.

                for (int workType = 1; workType <= workTypes; workType <<= 1) {
                    if ((workType & workTypes) == workType) {
                        maybeAdjustReservations(workType);
                    }
                }
            }
        }

        /** Returns the number of WorkTypes that were modified. */
        private int adjustPendingJobCount(int workTypes, boolean add) {
            final int adj = add ? 1 : -1;

            int numAdj = 0;
            // We don't know which type we'll classify the job as when we run it yet, so make sure
            // we have space in all applicable slots.
            for (int workType = 1; workType <= workTypes; workType <<= 1) {
                if ((workTypes & workType) == workType) {
                    mNumPendingJobs.put(workType, mNumPendingJobs.get(workType) + adj);
                    numAdj++;
                }
            }

            return numAdj;
        }

        void stageJob(@WorkType int workType, int allWorkTypes) {
            final int newNumStartingJobs = mNumStartingJobs.get(workType) + 1;
            mNumStartingJobs.put(workType, newNumStartingJobs);
            decrementPendingJobCount(allWorkTypes);
            if (newNumStartingJobs + mNumRunningJobs.get(workType)
                    > mNumActuallyReservedSlots.get(workType)) {
                mNumUnspecializedRemaining--;
            }
        }

        void onStagedJobFailed(@WorkType int workType) {
            final int oldNumStartingJobs = mNumStartingJobs.get(workType);
            if (oldNumStartingJobs == 0) {
                Slog.e(TAG, "# staged jobs for " + workType + " went negative.");
                // We are in a bad state. We will eventually recover when the pending list is
                // regenerated.
                return;
            }
            mNumStartingJobs.put(workType, oldNumStartingJobs - 1);
            maybeAdjustReservations(workType);
        }

        private void maybeAdjustReservations(@WorkType int workType) {
            // Always make sure we reserve the minimum number of slots in case new jobs become ready
            // soon.
            final int numRemainingForType = Math.max(mConfigNumReservedSlots.get(workType),
                    mNumRunningJobs.get(workType) + mNumStartingJobs.get(workType)
                            + mNumPendingJobs.get(workType));
            if (numRemainingForType < mNumActuallyReservedSlots.get(workType)) {
                // We've run all jobs for this type. Let another type use it now.
                mNumActuallyReservedSlots.put(workType, numRemainingForType);
                int assignWorkType = WORK_TYPE_NONE;
                for (int i = 0; i < mNumActuallyReservedSlots.size(); ++i) {
                    int wt = mNumActuallyReservedSlots.keyAt(i);
                    if (assignWorkType == WORK_TYPE_NONE || wt < assignWorkType) {
                        // Try to give this slot to the highest bias one within its limits.
                        int total = mNumRunningJobs.get(wt) + mNumStartingJobs.get(wt)
                                + mNumPendingJobs.get(wt);
                        if (mNumActuallyReservedSlots.valueAt(i) < mConfigAbsoluteMaxSlots.get(wt)
                                && total > mNumActuallyReservedSlots.valueAt(i)) {
                            assignWorkType = wt;
                        }
                    }
                }
                if (assignWorkType != WORK_TYPE_NONE) {
                    mNumActuallyReservedSlots.put(assignWorkType,
                            mNumActuallyReservedSlots.get(assignWorkType) + 1);
                } else {
                    mNumUnspecializedRemaining++;
                }
            }
        }

        void onJobStarted(@WorkType int workType) {
            mNumRunningJobs.put(workType, mNumRunningJobs.get(workType) + 1);
            final int oldNumStartingJobs = mNumStartingJobs.get(workType);
            if (oldNumStartingJobs == 0) {
                Slog.e(TAG, "# stated jobs for " + workType + " went negative.");
                // We are in a bad state. We will eventually recover when the pending list is
                // regenerated. For now, only modify the running count.
            } else {
                mNumStartingJobs.put(workType, oldNumStartingJobs - 1);
            }
        }

        void onJobFinished(@WorkType int workType) {
            final int newNumRunningJobs = mNumRunningJobs.get(workType) - 1;
            if (newNumRunningJobs < 0) {
                // We are in a bad state. We will eventually recover when the pending list is
                // regenerated.
                Slog.e(TAG, "# running jobs for " + workType + " went negative.");
                return;
            }
            mNumRunningJobs.put(workType, newNumRunningJobs);
            maybeAdjustReservations(workType);
        }

        void onCountDone() {
            // Calculate how many slots to reserve for each work type. "Unspecialized" slots will
            // be reserved for higher importance types first (ie. top before ej before bg).
            // Steps:
            //   1. Account for slots for already running jobs
            //   2. Use remaining unaccounted slots to try and ensure minimum reserved slots
            //   3. Allocate remaining up to max, based on importance

            mNumUnspecializedRemaining = mConfigMaxTotal;

            // Step 1
            for (int workType = 1; workType < ALL_WORK_TYPES; workType <<= 1) {
                int run = mNumRunningJobs.get(workType);
                mRecycledReserved.put(workType, run);
                mNumUnspecializedRemaining -= run;
            }

            // Step 2
            for (int workType = 1; workType < ALL_WORK_TYPES; workType <<= 1) {
                int num = mNumRunningJobs.get(workType) + mNumPendingJobs.get(workType);
                int res = mRecycledReserved.get(workType);
                int fillUp = Math.max(0, Math.min(mNumUnspecializedRemaining,
                        Math.min(num, mConfigNumReservedSlots.get(workType) - res)));
                res += fillUp;
                mRecycledReserved.put(workType, res);
                mNumUnspecializedRemaining -= fillUp;
            }

            // Step 3
            for (int workType = 1; workType < ALL_WORK_TYPES; workType <<= 1) {
                int num = mNumRunningJobs.get(workType) + mNumPendingJobs.get(workType);
                int res = mRecycledReserved.get(workType);
                int unspecializedAssigned = Math.max(0,
                        Math.min(mNumUnspecializedRemaining,
                                Math.min(mConfigAbsoluteMaxSlots.get(workType), num) - res));
                mNumActuallyReservedSlots.put(workType, res + unspecializedAssigned);
                mNumUnspecializedRemaining -= unspecializedAssigned;
            }
        }

        int canJobStart(int workTypes) {
            for (int workType = 1; workType <= workTypes; workType <<= 1) {
                if ((workTypes & workType) == workType) {
                    final int maxAllowed = Math.min(
                            mConfigAbsoluteMaxSlots.get(workType),
                            mNumActuallyReservedSlots.get(workType) + mNumUnspecializedRemaining);
                    if (mNumRunningJobs.get(workType) + mNumStartingJobs.get(workType)
                            < maxAllowed) {
                        return workType;
                    }
                }
            }
            return WORK_TYPE_NONE;
        }

        int canJobStart(int workTypes, @WorkType int replacingWorkType) {
            final boolean changedNums;
            int oldNumRunning = mNumRunningJobs.get(replacingWorkType);
            if (replacingWorkType != WORK_TYPE_NONE && oldNumRunning > 0) {
                mNumRunningJobs.put(replacingWorkType, oldNumRunning - 1);
                // Lazy implementation to avoid lots of processing. Best way would be to go
                // through the whole process of adjusting reservations, but the processing cost
                // is likely not worth it.
                mNumUnspecializedRemaining++;
                changedNums = true;
            } else {
                changedNums = false;
            }

            final int ret = canJobStart(workTypes);
            if (changedNums) {
                mNumRunningJobs.put(replacingWorkType, oldNumRunning);
                mNumUnspecializedRemaining--;
            }
            return ret;
        }

        int getPendingJobCount(@WorkType final int workType) {
            return mNumPendingJobs.get(workType, 0);
        }

        int getRunningJobCount(@WorkType final int workType) {
            return mNumRunningJobs.get(workType, 0);
        }

        boolean isOverTypeLimit(@WorkType final int workType) {
            return getRunningJobCount(workType) > mConfigAbsoluteMaxSlots.get(workType);
        }

        public String toString() {
            StringBuilder sb = new StringBuilder();

            sb.append("Config={");
            sb.append("tot=").append(mConfigMaxTotal);
            sb.append(" mins=");
            sb.append(mConfigNumReservedSlots);
            sb.append(" maxs=");
            sb.append(mConfigAbsoluteMaxSlots);
            sb.append("}");

            sb.append(", act res=").append(mNumActuallyReservedSlots);
            sb.append(", Pending=").append(mNumPendingJobs);
            sb.append(", Running=").append(mNumRunningJobs);
            sb.append(", Staged=").append(mNumStartingJobs);
            sb.append(", # unspecialized remaining=").append(mNumUnspecializedRemaining);

            return sb.toString();
        }
    }

    @VisibleForTesting
    static class PackageStats {
        public int userId;
        public String packageName;
        public int numRunningEj;
        public int numRunningRegular;
        public int numStagedEj;
        public int numStagedRegular;

        private void setPackage(int userId, @NonNull String packageName) {
            this.userId = userId;
            this.packageName = packageName;
            numRunningEj = numRunningRegular = 0;
            resetStagedCount();
        }

        private void resetStagedCount() {
            numStagedEj = numStagedRegular = 0;
        }

        private void adjustRunningCount(boolean add, boolean forEj) {
            if (forEj) {
                numRunningEj = Math.max(0, numRunningEj + (add ? 1 : -1));
            } else {
                numRunningRegular = Math.max(0, numRunningRegular + (add ? 1 : -1));
            }
        }

        private void adjustStagedCount(boolean add, boolean forEj) {
            if (forEj) {
                numStagedEj = Math.max(0, numStagedEj + (add ? 1 : -1));
            } else {
                numStagedRegular = Math.max(0, numStagedRegular + (add ? 1 : -1));
            }
        }

        @GuardedBy("mLock")
        private void dumpLocked(IndentingPrintWriter pw) {
            pw.print("PackageStats{");
            pw.print(userId);
            pw.print("-");
            pw.print(packageName);
            pw.print("#runEJ", numRunningEj);
            pw.print("#runReg", numRunningRegular);
            pw.print("#stagedEJ", numStagedEj);
            pw.print("#stagedReg", numStagedRegular);
            pw.println("}");
        }
    }

    private static final class ContextAssignment {
        public JobServiceContext context;
        public int preferredUid = JobServiceContext.NO_PREFERRED_UID;
        public int workType = WORK_TYPE_NONE;
        public String preemptReason;
        public int preemptReasonCode = JobParameters.STOP_REASON_UNDEFINED;
        public String shouldStopJobReason;
        public JobStatus newJob;
        public int newWorkType = WORK_TYPE_NONE;

        void clear() {
            context = null;
            preferredUid = JobServiceContext.NO_PREFERRED_UID;
            workType = WORK_TYPE_NONE;
            preemptReason = null;
            preemptReasonCode = JobParameters.STOP_REASON_UNDEFINED;
            shouldStopJobReason = null;
            newJob = null;
            newWorkType = WORK_TYPE_NONE;
        }
    }

    // TESTING HELPERS

    @VisibleForTesting
    void addRunningJobForTesting(@NonNull JobStatus job) {
        mRunningJobs.add(job);
        final PackageStats packageStats =
                getPackageStatsForTesting(job.getSourceUserId(), job.getSourcePackageName());
        packageStats.adjustRunningCount(true, job.shouldTreatAsExpeditedJob());
    }

    @VisibleForTesting
    int getPackageConcurrencyLimitEj() {
        return mPkgConcurrencyLimitEj;
    }

    int getPackageConcurrencyLimitRegular() {
        return mPkgConcurrencyLimitRegular;
    }

    /** Gets the {@link PackageStats} object for the app and saves it for testing use. */
    @NonNull
    @VisibleForTesting
    PackageStats getPackageStatsForTesting(int userId, @NonNull String packageName) {
        final PackageStats packageStats = getPkgStatsLocked(userId, packageName);
        mActivePkgStats.add(userId, packageName, packageStats);
        return packageStats;
    }
}
