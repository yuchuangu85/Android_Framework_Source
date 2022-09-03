/*
 * Copyright (C) 2014 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.job;

import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER;
import static android.text.format.DateUtils.HOUR_IN_MILLIS;
import static android.text.format.DateUtils.MINUTE_IN_MILLIS;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.AppGlobals;
import android.app.IUidObserver;
import android.app.compat.CompatChanges;
import android.app.job.IJobScheduler;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobProtoEnums;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.app.job.JobSnapshot;
import android.app.job.JobWorkItem;
import android.app.usage.UsageStatsManager;
import android.app.usage.UsageStatsManagerInternal;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.PackageManagerInternal;
import android.content.pm.ParceledListSlice;
import android.content.pm.ProviderInfo;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.BatteryManagerInternal;
import android.os.BatteryStatsInternal;
import android.os.Binder;
import android.os.Handler;
import android.os.LimitExceededException;
import android.os.Looper;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.WorkSource;
import android.os.storage.StorageManagerInternal;
import android.provider.DeviceConfig;
import android.provider.Settings;
import android.text.format.DateUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.IndentingPrintWriter;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.util.SparseIntArray;
import android.util.SparseSetArray;
import android.util.TimeUtils;
import android.util.proto.ProtoOutputStream;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.SomeArgs;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.FrameworkStatsLog;
import com.android.server.AppStateTracker;
import com.android.server.AppStateTrackerImpl;
import com.android.server.DeviceIdleInternal;
import com.android.server.JobSchedulerBackgroundThread;
import com.android.server.LocalServices;
import com.android.server.job.JobSchedulerServiceDumpProto.PendingJob;
import com.android.server.job.controllers.BackgroundJobsController;
import com.android.server.job.controllers.BatteryController;
import com.android.server.job.controllers.ComponentController;
import com.android.server.job.controllers.ConnectivityController;
import com.android.server.job.controllers.ContentObserverController;
import com.android.server.job.controllers.DeviceIdleJobsController;
import com.android.server.job.controllers.IdleController;
import com.android.server.job.controllers.JobStatus;
import com.android.server.job.controllers.PrefetchController;
import com.android.server.job.controllers.QuotaController;
import com.android.server.job.controllers.RestrictingController;
import com.android.server.job.controllers.StateController;
import com.android.server.job.controllers.StorageController;
import com.android.server.job.controllers.TareController;
import com.android.server.job.controllers.TimeController;
import com.android.server.job.restrictions.JobRestriction;
import com.android.server.job.restrictions.ThermalStatusRestriction;
import com.android.server.pm.UserManagerInternal;
import com.android.server.tare.EconomyManagerInternal;
import com.android.server.usage.AppStandbyInternal;
import com.android.server.usage.AppStandbyInternal.AppIdleStateChangeListener;
import com.android.server.utils.quota.Categorizer;
import com.android.server.utils.quota.Category;
import com.android.server.utils.quota.CountQuotaTracker;

import dalvik.annotation.optimization.NeverCompile;

import libcore.util.EmptyArray;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Responsible for taking jobs representing work to be performed by a client app, and determining
 * based on the criteria specified when that job should be run against the client application's
 * endpoint.
 * Implements logic for scheduling, and rescheduling jobs. The JobSchedulerService knows nothing
 * about constraints, or the state of active jobs. It receives callbacks from the various
 * controllers and completed jobs and operates accordingly.
 *
 * Note on locking: Any operations that manipulate {@link #mJobs} need to lock on that object.
 * Any function with the suffix 'Locked' also needs to lock on {@link #mJobs}.
 *
 * @hide
 */
public class JobSchedulerService extends com.android.server.SystemService
        implements StateChangedListener, JobCompletedListener {
    public static final String TAG = "JobScheduler";
    public static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);
    public static final boolean DEBUG_STANDBY = DEBUG || false;

    /** The maximum number of jobs that we allow an app to schedule */
    private static final int MAX_JOBS_PER_APP = 150;
    /** The number of the most recently completed jobs to keep track of for debugging purposes. */
    private static final int NUM_COMPLETED_JOB_HISTORY = 20;

    @VisibleForTesting
    public static Clock sSystemClock = Clock.systemUTC();

    private abstract static class MySimpleClock extends Clock {
        private final ZoneId mZoneId;

        MySimpleClock(ZoneId zoneId) {
            this.mZoneId = zoneId;
        }

        @Override
        public ZoneId getZone() {
            return mZoneId;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new MySimpleClock(zone) {
                @Override
                public long millis() {
                    return MySimpleClock.this.millis();
                }
            };
        }

        @Override
        public abstract long millis();

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis());
        }
    }

    @VisibleForTesting
    public static Clock sUptimeMillisClock = new MySimpleClock(ZoneOffset.UTC) {
        @Override
        public long millis() {
            return SystemClock.uptimeMillis();
        }
    };

    @VisibleForTesting
    public static Clock sElapsedRealtimeClock = new MySimpleClock(ZoneOffset.UTC) {
        @Override
        public long millis() {
            return SystemClock.elapsedRealtime();
        }
    };

    /** Global local for all job scheduler state. */
    final Object mLock = new Object();
    /** Master list of jobs. */
    final JobStore mJobs;
    /** Tracking the standby bucket state of each app */
    final StandbyTracker mStandbyTracker;
    /** Tracking amount of time each package runs for. */
    final JobPackageTracker mJobPackageTracker = new JobPackageTracker();
    final JobConcurrencyManager mConcurrencyManager;

    static final int MSG_CHECK_INDIVIDUAL_JOB = 0;
    static final int MSG_CHECK_JOB = 1;
    static final int MSG_STOP_JOB = 2;
    static final int MSG_CHECK_JOB_GREEDY = 3;
    static final int MSG_UID_STATE_CHANGED = 4;
    static final int MSG_UID_GONE = 5;
    static final int MSG_UID_ACTIVE = 6;
    static final int MSG_UID_IDLE = 7;
    static final int MSG_CHECK_CHANGED_JOB_LIST = 8;
    static final int MSG_CHECK_MEDIA_EXEMPTION = 9;

    /** List of controllers that will notify this service of updates to jobs. */
    final List<StateController> mControllers;
    /**
     * List of controllers that will apply to all jobs in the RESTRICTED bucket. This is a subset of
     * {@link #mControllers}.
     */
    private final List<RestrictingController> mRestrictiveControllers;
    /** Need direct access to this for testing. */
    private final StorageController mStorageController;
    /** Need directly for sending uid state changes */
    private final DeviceIdleJobsController mDeviceIdleJobsController;
    /** Needed to get next estimated launch time. */
    private final PrefetchController mPrefetchController;
    /** Needed to get remaining quota time. */
    private final QuotaController mQuotaController;
    /** Needed to get max execution time and expedited-job allowance. */
    private final TareController mTareController;
    /**
     * List of restrictions.
     * Note: do not add to or remove from this list at runtime except in the constructor, because we
     * do not synchronize access to this list.
     */
    private final List<JobRestriction> mJobRestrictions;

    @GuardedBy("mLock")
    private final BatteryStateTracker mBatteryStateTracker;

    @GuardedBy("mLock")
    private final SparseArray<String> mCloudMediaProviderPackages = new SparseArray<>();

    private final CountQuotaTracker mQuotaTracker;
    private static final String QUOTA_TRACKER_SCHEDULE_PERSISTED_TAG = ".schedulePersisted()";
    private static final String QUOTA_TRACKER_SCHEDULE_LOGGED =
            ".schedulePersisted out-of-quota logged";
    private static final Category QUOTA_TRACKER_CATEGORY_SCHEDULE_PERSISTED = new Category(
            ".schedulePersisted()");
    private static final Category QUOTA_TRACKER_CATEGORY_SCHEDULE_LOGGED = new Category(
            ".schedulePersisted out-of-quota logged");
    private static final Categorizer QUOTA_CATEGORIZER = (userId, packageName, tag) -> {
        if (QUOTA_TRACKER_SCHEDULE_PERSISTED_TAG.equals(tag)) {
            return QUOTA_TRACKER_CATEGORY_SCHEDULE_PERSISTED;
        }
        return QUOTA_TRACKER_CATEGORY_SCHEDULE_LOGGED;
    };

    /**
     * Queue of pending jobs. The JobServiceContext class will receive jobs from this list
     * when ready to execute them.
     */
    private final PendingJobQueue mPendingJobQueue = new PendingJobQueue();

    int[] mStartedUsers = EmptyArray.INT;

    final JobHandler mHandler;
    final JobSchedulerStub mJobSchedulerStub;

    PackageManagerInternal mLocalPM;
    ActivityManagerInternal mActivityManagerInternal;
    DeviceIdleInternal mLocalDeviceIdleController;
    @VisibleForTesting
    AppStateTrackerImpl mAppStateTracker;
    final UsageStatsManagerInternal mUsageStats;
    private final AppStandbyInternal mAppStandbyInternal;

    /**
     * Set to true once we are allowed to run third party apps.
     */
    boolean mReadyToRock;

    /**
     * What we last reported to DeviceIdleController about whether we are active.
     */
    boolean mReportedActive;

    private int mLastCompletedJobIndex = 0;
    private final JobStatus[] mLastCompletedJobs = new JobStatus[NUM_COMPLETED_JOB_HISTORY];
    private final long[] mLastCompletedJobTimeElapsed = new long[NUM_COMPLETED_JOB_HISTORY];

    /**
     * A mapping of which uids are currently in the foreground to their effective bias.
     */
    final SparseIntArray mUidBiasOverride = new SparseIntArray();

    /**
     * Which uids are currently performing backups, so we shouldn't allow their jobs to run.
     */
    private final SparseBooleanArray mBackingUpUids = new SparseBooleanArray();

    /**
     * Cache of debuggable app status.
     */
    final ArrayMap<String, Boolean> mDebuggableApps = new ArrayMap<>();

    /** Cached mapping of UIDs (for all users) to a list of packages in the UID. */
    private final SparseSetArray<String> mUidToPackageCache = new SparseSetArray<>();

    /** List of jobs whose controller state has changed since the last time we evaluated the job. */
    @GuardedBy("mLock")
    private final ArraySet<JobStatus> mChangedJobList = new ArraySet<>();

    /**
     * Named indices into standby bucket arrays, for clarity in referring to
     * specific buckets' bookkeeping.
     */
    public static final int ACTIVE_INDEX = 0;
    public static final int WORKING_INDEX = 1;
    public static final int FREQUENT_INDEX = 2;
    public static final int RARE_INDEX = 3;
    public static final int NEVER_INDEX = 4;
    // Putting RESTRICTED_INDEX after NEVER_INDEX to make it easier for proto dumping
    // (ScheduledJobStateChanged and JobStatusDumpProto).
    public static final int RESTRICTED_INDEX = 5;
    // Putting EXEMPTED_INDEX after RESTRICTED_INDEX to make it easier for proto dumping
    // (ScheduledJobStateChanged and JobStatusDumpProto).
    public static final int EXEMPTED_INDEX = 6;

    private class ConstantsObserver implements DeviceConfig.OnPropertiesChangedListener,
            EconomyManagerInternal.TareStateChangeListener {
        public void start() {
            DeviceConfig.addOnPropertiesChangedListener(DeviceConfig.NAMESPACE_JOB_SCHEDULER,
                    JobSchedulerBackgroundThread.getExecutor(), this);
            final EconomyManagerInternal economyManagerInternal =
                    LocalServices.getService(EconomyManagerInternal.class);
            economyManagerInternal.registerTareStateChangeListener(this);
            // Load all the constants.
            synchronized (mLock) {
                mConstants.updateTareSettingsLocked(economyManagerInternal.isEnabled());
            }
            onPropertiesChanged(DeviceConfig.getProperties(DeviceConfig.NAMESPACE_JOB_SCHEDULER));
        }

        @Override
        public void onPropertiesChanged(DeviceConfig.Properties properties) {
            boolean apiQuotaScheduleUpdated = false;
            boolean concurrencyUpdated = false;
            boolean runtimeUpdated = false;
            for (int controller = 0; controller < mControllers.size(); controller++) {
                final StateController sc = mControllers.get(controller);
                sc.prepareForUpdatedConstantsLocked();
            }

            synchronized (mLock) {
                for (String name : properties.getKeyset()) {
                    if (name == null) {
                        continue;
                    }
                    switch (name) {
                        case Constants.KEY_ENABLE_API_QUOTAS:
                        case Constants.KEY_API_QUOTA_SCHEDULE_COUNT:
                        case Constants.KEY_API_QUOTA_SCHEDULE_WINDOW_MS:
                        case Constants.KEY_API_QUOTA_SCHEDULE_RETURN_FAILURE_RESULT:
                        case Constants.KEY_API_QUOTA_SCHEDULE_THROW_EXCEPTION:
                            if (!apiQuotaScheduleUpdated) {
                                mConstants.updateApiQuotaConstantsLocked();
                                updateQuotaTracker();
                                apiQuotaScheduleUpdated = true;
                            }
                            break;
                        case Constants.KEY_MIN_READY_NON_ACTIVE_JOBS_COUNT:
                        case Constants.KEY_MAX_NON_ACTIVE_JOB_BATCH_DELAY_MS:
                            mConstants.updateBatchingConstantsLocked();
                            break;
                        case Constants.KEY_HEAVY_USE_FACTOR:
                        case Constants.KEY_MODERATE_USE_FACTOR:
                            mConstants.updateUseFactorConstantsLocked();
                            break;
                        case Constants.KEY_MIN_LINEAR_BACKOFF_TIME_MS:
                        case Constants.KEY_MIN_EXP_BACKOFF_TIME_MS:
                            mConstants.updateBackoffConstantsLocked();
                            break;
                        case Constants.KEY_CONN_CONGESTION_DELAY_FRAC:
                        case Constants.KEY_CONN_PREFETCH_RELAX_FRAC:
                        case Constants.KEY_CONN_LOW_SIGNAL_STRENGTH_RELAX_FRAC:
                        case Constants.KEY_CONN_USE_CELL_SIGNAL_STRENGTH:
                        case Constants.KEY_CONN_UPDATE_ALL_JOBS_MIN_INTERVAL_MS:
                            mConstants.updateConnectivityConstantsLocked();
                            break;
                        case Constants.KEY_PREFETCH_FORCE_BATCH_RELAX_THRESHOLD_MS:
                            mConstants.updatePrefetchConstantsLocked();
                            break;
                        case Constants.KEY_RUNTIME_FREE_QUOTA_MAX_LIMIT_MS:
                        case Constants.KEY_RUNTIME_MIN_GUARANTEE_MS:
                        case Constants.KEY_RUNTIME_MIN_EJ_GUARANTEE_MS:
                        case Constants.KEY_RUNTIME_MIN_HIGH_PRIORITY_GUARANTEE_MS:
                            if (!runtimeUpdated) {
                                mConstants.updateRuntimeConstantsLocked();
                                runtimeUpdated = true;
                            }
                            break;
                        default:
                            if (name.startsWith(JobConcurrencyManager.CONFIG_KEY_PREFIX_CONCURRENCY)
                                    && !concurrencyUpdated) {
                                mConcurrencyManager.updateConfigLocked();
                                concurrencyUpdated = true;
                            } else {
                                for (int ctrlr = 0; ctrlr < mControllers.size(); ctrlr++) {
                                    final StateController sc = mControllers.get(ctrlr);
                                    sc.processConstantLocked(properties, name);
                                }
                            }
                            break;
                    }
                }
                for (int controller = 0; controller < mControllers.size(); controller++) {
                    final StateController sc = mControllers.get(controller);
                    sc.onConstantsUpdatedLocked();
                }
            }
        }

        @Override
        public void onTareEnabledStateChanged(boolean isTareEnabled) {
            if (mConstants.updateTareSettingsLocked(isTareEnabled)) {
                for (int controller = 0; controller < mControllers.size(); controller++) {
                    final StateController sc = mControllers.get(controller);
                    sc.onConstantsUpdatedLocked();
                }
                onControllerStateChanged(null);
            }
        }
    }

    @VisibleForTesting
    void updateQuotaTracker() {
        mQuotaTracker.setEnabled(mConstants.ENABLE_API_QUOTAS);
        mQuotaTracker.setCountLimit(QUOTA_TRACKER_CATEGORY_SCHEDULE_PERSISTED,
                mConstants.API_QUOTA_SCHEDULE_COUNT,
                mConstants.API_QUOTA_SCHEDULE_WINDOW_MS);
    }

    /**
     * All times are in milliseconds. Any access to this class or its fields should be done while
     * holding the JobSchedulerService.mLock lock.
     */
    public static class Constants {
        // Key names stored in the settings value.
        private static final String KEY_MIN_READY_NON_ACTIVE_JOBS_COUNT =
                "min_ready_non_active_jobs_count";
        private static final String KEY_MAX_NON_ACTIVE_JOB_BATCH_DELAY_MS =
                "max_non_active_job_batch_delay_ms";
        private static final String KEY_HEAVY_USE_FACTOR = "heavy_use_factor";
        private static final String KEY_MODERATE_USE_FACTOR = "moderate_use_factor";

        private static final String KEY_MIN_LINEAR_BACKOFF_TIME_MS = "min_linear_backoff_time_ms";
        private static final String KEY_MIN_EXP_BACKOFF_TIME_MS = "min_exp_backoff_time_ms";
        private static final String KEY_CONN_CONGESTION_DELAY_FRAC = "conn_congestion_delay_frac";
        private static final String KEY_CONN_PREFETCH_RELAX_FRAC = "conn_prefetch_relax_frac";
        private static final String KEY_CONN_USE_CELL_SIGNAL_STRENGTH =
                "conn_use_cell_signal_strength";
        private static final String KEY_CONN_UPDATE_ALL_JOBS_MIN_INTERVAL_MS =
                "conn_update_all_jobs_min_interval_ms";
        private static final String KEY_CONN_LOW_SIGNAL_STRENGTH_RELAX_FRAC =
                "conn_low_signal_strength_relax_frac";
        private static final String KEY_PREFETCH_FORCE_BATCH_RELAX_THRESHOLD_MS =
                "prefetch_force_batch_relax_threshold_ms";
        private static final String KEY_ENABLE_API_QUOTAS = "enable_api_quotas";
        private static final String KEY_API_QUOTA_SCHEDULE_COUNT = "aq_schedule_count";
        private static final String KEY_API_QUOTA_SCHEDULE_WINDOW_MS = "aq_schedule_window_ms";
        private static final String KEY_API_QUOTA_SCHEDULE_THROW_EXCEPTION =
                "aq_schedule_throw_exception";
        private static final String KEY_API_QUOTA_SCHEDULE_RETURN_FAILURE_RESULT =
                "aq_schedule_return_failure";

        private static final String KEY_RUNTIME_FREE_QUOTA_MAX_LIMIT_MS =
                "runtime_free_quota_max_limit_ms";
        private static final String KEY_RUNTIME_MIN_GUARANTEE_MS = "runtime_min_guarantee_ms";
        private static final String KEY_RUNTIME_MIN_EJ_GUARANTEE_MS = "runtime_min_ej_guarantee_ms";
        private static final String KEY_RUNTIME_MIN_HIGH_PRIORITY_GUARANTEE_MS =
                "runtime_min_high_priority_guarantee_ms";

        private static final int DEFAULT_MIN_READY_NON_ACTIVE_JOBS_COUNT = 5;
        private static final long DEFAULT_MAX_NON_ACTIVE_JOB_BATCH_DELAY_MS = 31 * MINUTE_IN_MILLIS;
        private static final float DEFAULT_HEAVY_USE_FACTOR = .9f;
        private static final float DEFAULT_MODERATE_USE_FACTOR = .5f;
        private static final long DEFAULT_MIN_LINEAR_BACKOFF_TIME_MS = JobInfo.MIN_BACKOFF_MILLIS;
        private static final long DEFAULT_MIN_EXP_BACKOFF_TIME_MS = JobInfo.MIN_BACKOFF_MILLIS;
        private static final float DEFAULT_CONN_CONGESTION_DELAY_FRAC = 0.5f;
        private static final float DEFAULT_CONN_PREFETCH_RELAX_FRAC = 0.5f;
        private static final boolean DEFAULT_CONN_USE_CELL_SIGNAL_STRENGTH = true;
        private static final long DEFAULT_CONN_UPDATE_ALL_JOBS_MIN_INTERVAL_MS = MINUTE_IN_MILLIS;
        private static final float DEFAULT_CONN_LOW_SIGNAL_STRENGTH_RELAX_FRAC = 0.5f;
        private static final long DEFAULT_PREFETCH_FORCE_BATCH_RELAX_THRESHOLD_MS = HOUR_IN_MILLIS;
        private static final boolean DEFAULT_ENABLE_API_QUOTAS = true;
        private static final int DEFAULT_API_QUOTA_SCHEDULE_COUNT = 250;
        private static final long DEFAULT_API_QUOTA_SCHEDULE_WINDOW_MS = MINUTE_IN_MILLIS;
        private static final boolean DEFAULT_API_QUOTA_SCHEDULE_THROW_EXCEPTION = true;
        private static final boolean DEFAULT_API_QUOTA_SCHEDULE_RETURN_FAILURE_RESULT = false;
        @VisibleForTesting
        public static final long DEFAULT_RUNTIME_FREE_QUOTA_MAX_LIMIT_MS = 30 * MINUTE_IN_MILLIS;
        @VisibleForTesting
        public static final long DEFAULT_RUNTIME_MIN_GUARANTEE_MS = 10 * MINUTE_IN_MILLIS;
        @VisibleForTesting
        public static final long DEFAULT_RUNTIME_MIN_EJ_GUARANTEE_MS = 3 * MINUTE_IN_MILLIS;
        @VisibleForTesting
        static final long DEFAULT_RUNTIME_MIN_HIGH_PRIORITY_GUARANTEE_MS = 5 * MINUTE_IN_MILLIS;
        private static final boolean DEFAULT_USE_TARE_POLICY = false;

        /**
         * Minimum # of non-ACTIVE jobs for which the JMS will be happy running some work early.
         */
        int MIN_READY_NON_ACTIVE_JOBS_COUNT = DEFAULT_MIN_READY_NON_ACTIVE_JOBS_COUNT;

        /**
         * Don't batch a non-ACTIVE job if it's been delayed due to force batching attempts for
         * at least this amount of time.
         */
        long MAX_NON_ACTIVE_JOB_BATCH_DELAY_MS = DEFAULT_MAX_NON_ACTIVE_JOB_BATCH_DELAY_MS;

        /**
         * This is the job execution factor that is considered to be heavy use of the system.
         */
        float HEAVY_USE_FACTOR = DEFAULT_HEAVY_USE_FACTOR;
        /**
         * This is the job execution factor that is considered to be moderate use of the system.
         */
        float MODERATE_USE_FACTOR = DEFAULT_MODERATE_USE_FACTOR;

        /**
         * The minimum backoff time to allow for linear backoff.
         */
        long MIN_LINEAR_BACKOFF_TIME_MS = DEFAULT_MIN_LINEAR_BACKOFF_TIME_MS;
        /**
         * The minimum backoff time to allow for exponential backoff.
         */
        long MIN_EXP_BACKOFF_TIME_MS = DEFAULT_MIN_EXP_BACKOFF_TIME_MS;

        /**
         * The fraction of a job's running window that must pass before we
         * consider running it when the network is congested.
         */
        public float CONN_CONGESTION_DELAY_FRAC = DEFAULT_CONN_CONGESTION_DELAY_FRAC;
        /**
         * The fraction of a prefetch job's running window that must pass before
         * we consider matching it against a metered network.
         */
        public float CONN_PREFETCH_RELAX_FRAC = DEFAULT_CONN_PREFETCH_RELAX_FRAC;
        /**
         * Whether to use the cell signal strength to determine if a particular job is eligible to
         * run.
         */
        public boolean CONN_USE_CELL_SIGNAL_STRENGTH = DEFAULT_CONN_USE_CELL_SIGNAL_STRENGTH;
        /**
         * When throttling updating all tracked jobs, make sure not to update them more frequently
         * than this value.
         */
        public long CONN_UPDATE_ALL_JOBS_MIN_INTERVAL_MS =
                DEFAULT_CONN_UPDATE_ALL_JOBS_MIN_INTERVAL_MS;
        /**
         * The fraction of a job's running window that must pass before we consider running it on
         * low signal strength networks.
         */
        public float CONN_LOW_SIGNAL_STRENGTH_RELAX_FRAC =
                DEFAULT_CONN_LOW_SIGNAL_STRENGTH_RELAX_FRAC;

        /**
         * The amount of time within which we would consider the app to be launching relatively soon
         * and will relax the force batching policy on prefetch jobs. If the app is not going to be
         * launched within this amount of time from now, then we will force batch the prefetch job.
         */
        public long PREFETCH_FORCE_BATCH_RELAX_THRESHOLD_MS =
                DEFAULT_PREFETCH_FORCE_BATCH_RELAX_THRESHOLD_MS;

        /**
         * Whether to enable quota limits on APIs.
         */
        public boolean ENABLE_API_QUOTAS = DEFAULT_ENABLE_API_QUOTAS;
        /**
         * The maximum number of schedule() calls an app can make in a set amount of time.
         */
        public int API_QUOTA_SCHEDULE_COUNT = DEFAULT_API_QUOTA_SCHEDULE_COUNT;
        /**
         * The time window that {@link #API_QUOTA_SCHEDULE_COUNT} should be evaluated over.
         */
        public long API_QUOTA_SCHEDULE_WINDOW_MS = DEFAULT_API_QUOTA_SCHEDULE_WINDOW_MS;
        /**
         * Whether to throw an exception when an app hits its schedule quota limit.
         */
        public boolean API_QUOTA_SCHEDULE_THROW_EXCEPTION =
                DEFAULT_API_QUOTA_SCHEDULE_THROW_EXCEPTION;
        /**
         * Whether or not to return a failure result when an app hits its schedule quota limit.
         */
        public boolean API_QUOTA_SCHEDULE_RETURN_FAILURE_RESULT =
                DEFAULT_API_QUOTA_SCHEDULE_RETURN_FAILURE_RESULT;

        /** The maximum amount of time we will let a job run for when quota is "free". */
        public long RUNTIME_FREE_QUOTA_MAX_LIMIT_MS = DEFAULT_RUNTIME_FREE_QUOTA_MAX_LIMIT_MS;

        /**
         * The minimum amount of time we try to guarantee regular jobs will run for.
         */
        public long RUNTIME_MIN_GUARANTEE_MS = DEFAULT_RUNTIME_MIN_GUARANTEE_MS;

        /**
         * The minimum amount of time we try to guarantee EJs will run for.
         */
        public long RUNTIME_MIN_EJ_GUARANTEE_MS = DEFAULT_RUNTIME_MIN_EJ_GUARANTEE_MS;

        /**
         * The minimum amount of time we try to guarantee high priority jobs will run for.
         */
        public long RUNTIME_MIN_HIGH_PRIORITY_GUARANTEE_MS =
                DEFAULT_RUNTIME_MIN_HIGH_PRIORITY_GUARANTEE_MS;

        /**
         * If true, use TARE policy for job limiting. If false, use quotas.
         */
        public boolean USE_TARE_POLICY = DEFAULT_USE_TARE_POLICY;

        private void updateBatchingConstantsLocked() {
            MIN_READY_NON_ACTIVE_JOBS_COUNT = DeviceConfig.getInt(
                    DeviceConfig.NAMESPACE_JOB_SCHEDULER,
                    KEY_MIN_READY_NON_ACTIVE_JOBS_COUNT,
                    DEFAULT_MIN_READY_NON_ACTIVE_JOBS_COUNT);
            MAX_NON_ACTIVE_JOB_BATCH_DELAY_MS = DeviceConfig.getLong(
                    DeviceConfig.NAMESPACE_JOB_SCHEDULER,
                    KEY_MAX_NON_ACTIVE_JOB_BATCH_DELAY_MS,
                    DEFAULT_MAX_NON_ACTIVE_JOB_BATCH_DELAY_MS);
        }

        private void updateUseFactorConstantsLocked() {
            HEAVY_USE_FACTOR = DeviceConfig.getFloat(DeviceConfig.NAMESPACE_JOB_SCHEDULER,
                    KEY_HEAVY_USE_FACTOR,
                    DEFAULT_HEAVY_USE_FACTOR);
            MODERATE_USE_FACTOR = DeviceConfig.getFloat(DeviceConfig.NAMESPACE_JOB_SCHEDULER,
                    KEY_MODERATE_USE_FACTOR,
                    DEFAULT_MODERATE_USE_FACTOR);
        }

        private void updateBackoffConstantsLocked() {
            MIN_LINEAR_BACKOFF_TIME_MS = DeviceConfig.getLong(DeviceConfig.NAMESPACE_JOB_SCHEDULER,
                    KEY_MIN_LINEAR_BACKOFF_TIME_MS,
                    DEFAULT_MIN_LINEAR_BACKOFF_TIME_MS);
            MIN_EXP_BACKOFF_TIME_MS = DeviceConfig.getLong(DeviceConfig.NAMESPACE_JOB_SCHEDULER,
                    KEY_MIN_EXP_BACKOFF_TIME_MS,
                    DEFAULT_MIN_EXP_BACKOFF_TIME_MS);
        }

        private void updateConnectivityConstantsLocked() {
            CONN_CONGESTION_DELAY_FRAC = DeviceConfig.getFloat(DeviceConfig.NAMESPACE_JOB_SCHEDULER,
                    KEY_CONN_CONGESTION_DELAY_FRAC,
                    DEFAULT_CONN_CONGESTION_DELAY_FRAC);
            CONN_PREFETCH_RELAX_FRAC = DeviceConfig.getFloat(DeviceConfig.NAMESPACE_JOB_SCHEDULER,
                    KEY_CONN_PREFETCH_RELAX_FRAC,
                    DEFAULT_CONN_PREFETCH_RELAX_FRAC);
            CONN_USE_CELL_SIGNAL_STRENGTH = DeviceConfig.getBoolean(
                    DeviceConfig.NAMESPACE_JOB_SCHEDULER,
                    KEY_CONN_USE_CELL_SIGNAL_STRENGTH,
                    DEFAULT_CONN_USE_CELL_SIGNAL_STRENGTH);
            CONN_UPDATE_ALL_JOBS_MIN_INTERVAL_MS = DeviceConfig.getLong(
                    DeviceConfig.NAMESPACE_JOB_SCHEDULER,
                    KEY_CONN_UPDATE_ALL_JOBS_MIN_INTERVAL_MS,
                    DEFAULT_CONN_UPDATE_ALL_JOBS_MIN_INTERVAL_MS);
            CONN_LOW_SIGNAL_STRENGTH_RELAX_FRAC = DeviceConfig.getFloat(
                    DeviceConfig.NAMESPACE_JOB_SCHEDULER,
                    KEY_CONN_LOW_SIGNAL_STRENGTH_RELAX_FRAC,
                    DEFAULT_CONN_LOW_SIGNAL_STRENGTH_RELAX_FRAC);
        }

        private void updatePrefetchConstantsLocked() {
            PREFETCH_FORCE_BATCH_RELAX_THRESHOLD_MS = DeviceConfig.getLong(
                    DeviceConfig.NAMESPACE_JOB_SCHEDULER,
                    KEY_PREFETCH_FORCE_BATCH_RELAX_THRESHOLD_MS,
                    DEFAULT_PREFETCH_FORCE_BATCH_RELAX_THRESHOLD_MS);
        }

        private void updateApiQuotaConstantsLocked() {
            ENABLE_API_QUOTAS = DeviceConfig.getBoolean(DeviceConfig.NAMESPACE_JOB_SCHEDULER,
                    KEY_ENABLE_API_QUOTAS, DEFAULT_ENABLE_API_QUOTAS);
            // Set a minimum value on the quota limit so it's not so low that it interferes with
            // legitimate use cases.
            API_QUOTA_SCHEDULE_COUNT = Math.max(250,
                    DeviceConfig.getInt(DeviceConfig.NAMESPACE_JOB_SCHEDULER,
                            KEY_API_QUOTA_SCHEDULE_COUNT, DEFAULT_API_QUOTA_SCHEDULE_COUNT));
            API_QUOTA_SCHEDULE_WINDOW_MS = DeviceConfig.getLong(
                    DeviceConfig.NAMESPACE_JOB_SCHEDULER,
                    KEY_API_QUOTA_SCHEDULE_WINDOW_MS, DEFAULT_API_QUOTA_SCHEDULE_WINDOW_MS);
            API_QUOTA_SCHEDULE_THROW_EXCEPTION = DeviceConfig.getBoolean(
                    DeviceConfig.NAMESPACE_JOB_SCHEDULER,
                    KEY_API_QUOTA_SCHEDULE_THROW_EXCEPTION,
                    DEFAULT_API_QUOTA_SCHEDULE_THROW_EXCEPTION);
            API_QUOTA_SCHEDULE_RETURN_FAILURE_RESULT = DeviceConfig.getBoolean(
                    DeviceConfig.NAMESPACE_JOB_SCHEDULER,
                    KEY_API_QUOTA_SCHEDULE_RETURN_FAILURE_RESULT,
                    DEFAULT_API_QUOTA_SCHEDULE_RETURN_FAILURE_RESULT);
        }

        private void updateRuntimeConstantsLocked() {
            DeviceConfig.Properties properties = DeviceConfig.getProperties(
                    DeviceConfig.NAMESPACE_JOB_SCHEDULER,
                    KEY_RUNTIME_FREE_QUOTA_MAX_LIMIT_MS,
                    KEY_RUNTIME_MIN_GUARANTEE_MS, KEY_RUNTIME_MIN_EJ_GUARANTEE_MS,
                    KEY_RUNTIME_MIN_HIGH_PRIORITY_GUARANTEE_MS);

            // Make sure min runtime for regular jobs is at least 10 minutes.
            RUNTIME_MIN_GUARANTEE_MS = Math.max(10 * MINUTE_IN_MILLIS,
                    properties.getLong(
                            KEY_RUNTIME_MIN_GUARANTEE_MS, DEFAULT_RUNTIME_MIN_GUARANTEE_MS));
            // Make sure min runtime for high priority jobs is at least 4 minutes.
            RUNTIME_MIN_HIGH_PRIORITY_GUARANTEE_MS = Math.max(4 * MINUTE_IN_MILLIS,
                    properties.getLong(
                            KEY_RUNTIME_MIN_HIGH_PRIORITY_GUARANTEE_MS,
                            DEFAULT_RUNTIME_MIN_HIGH_PRIORITY_GUARANTEE_MS));
            // Make sure min runtime for expedited jobs is at least one minute.
            RUNTIME_MIN_EJ_GUARANTEE_MS = Math.max(MINUTE_IN_MILLIS,
                    properties.getLong(
                            KEY_RUNTIME_MIN_EJ_GUARANTEE_MS, DEFAULT_RUNTIME_MIN_EJ_GUARANTEE_MS));
            RUNTIME_FREE_QUOTA_MAX_LIMIT_MS = Math.max(RUNTIME_MIN_GUARANTEE_MS,
                    properties.getLong(KEY_RUNTIME_FREE_QUOTA_MAX_LIMIT_MS,
                            DEFAULT_RUNTIME_FREE_QUOTA_MAX_LIMIT_MS));
        }

        private boolean updateTareSettingsLocked(boolean isTareEnabled) {
            boolean changed = false;
            if (USE_TARE_POLICY != isTareEnabled) {
                USE_TARE_POLICY = isTareEnabled;
                changed = true;
            }
            return changed;
        }

        void dump(IndentingPrintWriter pw) {
            pw.println("Settings:");
            pw.increaseIndent();
            pw.print(KEY_MIN_READY_NON_ACTIVE_JOBS_COUNT,
                    MIN_READY_NON_ACTIVE_JOBS_COUNT).println();
            pw.print(KEY_MAX_NON_ACTIVE_JOB_BATCH_DELAY_MS,
                    MAX_NON_ACTIVE_JOB_BATCH_DELAY_MS).println();
            pw.print(KEY_HEAVY_USE_FACTOR, HEAVY_USE_FACTOR).println();
            pw.print(KEY_MODERATE_USE_FACTOR, MODERATE_USE_FACTOR).println();

            pw.print(KEY_MIN_LINEAR_BACKOFF_TIME_MS, MIN_LINEAR_BACKOFF_TIME_MS).println();
            pw.print(KEY_MIN_EXP_BACKOFF_TIME_MS, MIN_EXP_BACKOFF_TIME_MS).println();
            pw.print(KEY_CONN_CONGESTION_DELAY_FRAC, CONN_CONGESTION_DELAY_FRAC).println();
            pw.print(KEY_CONN_PREFETCH_RELAX_FRAC, CONN_PREFETCH_RELAX_FRAC).println();
            pw.print(KEY_CONN_USE_CELL_SIGNAL_STRENGTH, CONN_USE_CELL_SIGNAL_STRENGTH).println();
            pw.print(KEY_CONN_UPDATE_ALL_JOBS_MIN_INTERVAL_MS, CONN_UPDATE_ALL_JOBS_MIN_INTERVAL_MS)
                    .println();
            pw.print(KEY_CONN_LOW_SIGNAL_STRENGTH_RELAX_FRAC, CONN_LOW_SIGNAL_STRENGTH_RELAX_FRAC)
                    .println();
            pw.print(KEY_PREFETCH_FORCE_BATCH_RELAX_THRESHOLD_MS,
                    PREFETCH_FORCE_BATCH_RELAX_THRESHOLD_MS).println();

            pw.print(KEY_ENABLE_API_QUOTAS, ENABLE_API_QUOTAS).println();
            pw.print(KEY_API_QUOTA_SCHEDULE_COUNT, API_QUOTA_SCHEDULE_COUNT).println();
            pw.print(KEY_API_QUOTA_SCHEDULE_WINDOW_MS, API_QUOTA_SCHEDULE_WINDOW_MS).println();
            pw.print(KEY_API_QUOTA_SCHEDULE_THROW_EXCEPTION,
                    API_QUOTA_SCHEDULE_THROW_EXCEPTION).println();
            pw.print(KEY_API_QUOTA_SCHEDULE_RETURN_FAILURE_RESULT,
                    API_QUOTA_SCHEDULE_RETURN_FAILURE_RESULT).println();

            pw.print(KEY_RUNTIME_MIN_GUARANTEE_MS, RUNTIME_MIN_GUARANTEE_MS).println();
            pw.print(KEY_RUNTIME_MIN_EJ_GUARANTEE_MS, RUNTIME_MIN_EJ_GUARANTEE_MS).println();
            pw.print(KEY_RUNTIME_MIN_HIGH_PRIORITY_GUARANTEE_MS,
                    RUNTIME_MIN_HIGH_PRIORITY_GUARANTEE_MS).println();
            pw.print(KEY_RUNTIME_FREE_QUOTA_MAX_LIMIT_MS, RUNTIME_FREE_QUOTA_MAX_LIMIT_MS)
                    .println();

            pw.print(Settings.Global.ENABLE_TARE, USE_TARE_POLICY).println();

            pw.decreaseIndent();
        }

        void dump(ProtoOutputStream proto) {
            proto.write(ConstantsProto.MIN_READY_NON_ACTIVE_JOBS_COUNT,
                    MIN_READY_NON_ACTIVE_JOBS_COUNT);
            proto.write(ConstantsProto.MAX_NON_ACTIVE_JOB_BATCH_DELAY_MS,
                    MAX_NON_ACTIVE_JOB_BATCH_DELAY_MS);
            proto.write(ConstantsProto.HEAVY_USE_FACTOR, HEAVY_USE_FACTOR);
            proto.write(ConstantsProto.MODERATE_USE_FACTOR, MODERATE_USE_FACTOR);

            proto.write(ConstantsProto.MIN_LINEAR_BACKOFF_TIME_MS, MIN_LINEAR_BACKOFF_TIME_MS);
            proto.write(ConstantsProto.MIN_EXP_BACKOFF_TIME_MS, MIN_EXP_BACKOFF_TIME_MS);
            proto.write(ConstantsProto.CONN_CONGESTION_DELAY_FRAC, CONN_CONGESTION_DELAY_FRAC);
            proto.write(ConstantsProto.CONN_PREFETCH_RELAX_FRAC, CONN_PREFETCH_RELAX_FRAC);

            proto.write(ConstantsProto.ENABLE_API_QUOTAS, ENABLE_API_QUOTAS);
            proto.write(ConstantsProto.API_QUOTA_SCHEDULE_COUNT, API_QUOTA_SCHEDULE_COUNT);
            proto.write(ConstantsProto.API_QUOTA_SCHEDULE_WINDOW_MS, API_QUOTA_SCHEDULE_WINDOW_MS);
            proto.write(ConstantsProto.API_QUOTA_SCHEDULE_THROW_EXCEPTION,
                    API_QUOTA_SCHEDULE_THROW_EXCEPTION);
            proto.write(ConstantsProto.API_QUOTA_SCHEDULE_RETURN_FAILURE_RESULT,
                    API_QUOTA_SCHEDULE_RETURN_FAILURE_RESULT);
        }
    }

    final Constants mConstants;
    final ConstantsObserver mConstantsObserver;

    /**
     * Cleans up outstanding jobs when a package is removed. Even if it's being replaced later we
     * still clean up. On reinstall the package will have a new uid.
     */
    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (DEBUG) {
                Slog.d(TAG, "Receieved: " + action);
            }
            final String pkgName = getPackageName(intent);
            final int pkgUid = intent.getIntExtra(Intent.EXTRA_UID, -1);

            if (Intent.ACTION_PACKAGE_CHANGED.equals(action)) {
                // Purge the app's jobs if the whole package was just disabled.  When this is
                // the case the component name will be a bare package name.
                if (pkgName != null && pkgUid != -1) {
                    final String[] changedComponents = intent.getStringArrayExtra(
                            Intent.EXTRA_CHANGED_COMPONENT_NAME_LIST);
                    if (changedComponents != null) {
                        for (String component : changedComponents) {
                            if (component.equals(pkgName)) {
                                if (DEBUG) {
                                    Slog.d(TAG, "Package state change: " + pkgName);
                                }
                                try {
                                    final int userId = UserHandle.getUserId(pkgUid);
                                    IPackageManager pm = AppGlobals.getPackageManager();
                                    final int state =
                                            pm.getApplicationEnabledSetting(pkgName, userId);
                                    if (state == COMPONENT_ENABLED_STATE_DISABLED
                                            || state == COMPONENT_ENABLED_STATE_DISABLED_USER) {
                                        if (DEBUG) {
                                            Slog.d(TAG, "Removing jobs for package " + pkgName
                                                    + " in user " + userId);
                                        }
                                        synchronized (mLock) {
                                            // There's no guarantee that the process has been
                                            // stopped by the time we get here, but since this is
                                            // a user-initiated action, it should be fine to just
                                            // put USER instead of UNINSTALL or DISABLED.
                                            cancelJobsForPackageAndUidLocked(pkgName, pkgUid,
                                                    JobParameters.STOP_REASON_USER,
                                                    JobParameters.INTERNAL_STOP_REASON_UNINSTALL,
                                                    "app disabled");
                                        }
                                    }
                                } catch (RemoteException | IllegalArgumentException e) {
                                    /*
                                     * IllegalArgumentException means that the package doesn't exist.
                                     * This arises when PACKAGE_CHANGED broadcast delivery has lagged
                                     * behind outright uninstall, so by the time we try to act it's gone.
                                     * We don't need to act on this PACKAGE_CHANGED when this happens;
                                     * we'll get a PACKAGE_REMOVED later and clean up then.
                                     *
                                     * RemoteException can't actually happen; the package manager is
                                     * running in this same process.
                                     */
                                }
                                break;
                            }
                        }
                        if (DEBUG) {
                            Slog.d(TAG, "Something in " + pkgName
                                    + " changed. Reevaluating controller states.");
                        }
                        synchronized (mLock) {
                            for (int c = mControllers.size() - 1; c >= 0; --c) {
                                mControllers.get(c).reevaluateStateLocked(pkgUid);
                            }
                        }
                    }
                } else {
                    Slog.w(TAG, "PACKAGE_CHANGED for " + pkgName + " / uid " + pkgUid);
                }
            } else if (Intent.ACTION_PACKAGE_ADDED.equals(action)) {
                if (!intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
                    final int uid = intent.getIntExtra(Intent.EXTRA_UID, -1);
                    synchronized (mLock) {
                        mUidToPackageCache.remove(uid);
                    }
                }
            } else if (Intent.ACTION_PACKAGE_FULLY_REMOVED.equals(action)) {
                if (DEBUG) {
                    Slog.d(TAG, "Removing jobs for " + pkgName + " (uid=" + pkgUid + ")");
                }
                synchronized (mLock) {
                    mUidToPackageCache.remove(pkgUid);
                    // There's no guarantee that the process has been stopped by the time we
                    // get here, but since this is generally a user-initiated action, it should
                    // be fine to just put USER instead of UNINSTALL or DISABLED.
                    cancelJobsForPackageAndUidLocked(pkgName, pkgUid,
                            JobParameters.STOP_REASON_USER,
                            JobParameters.INTERNAL_STOP_REASON_UNINSTALL, "app uninstalled");
                    for (int c = 0; c < mControllers.size(); ++c) {
                        mControllers.get(c).onAppRemovedLocked(pkgName, pkgUid);
                    }
                    mDebuggableApps.remove(pkgName);
                    mConcurrencyManager.onAppRemovedLocked(pkgName, pkgUid);
                }
            } else if (Intent.ACTION_USER_ADDED.equals(action)) {
                final int userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, 0);
                synchronized (mLock) {
                    for (int c = 0; c < mControllers.size(); ++c) {
                        mControllers.get(c).onUserAddedLocked(userId);
                    }
                }
            } else if (Intent.ACTION_USER_REMOVED.equals(action)) {
                final int userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, 0);
                if (DEBUG) {
                    Slog.d(TAG, "Removing jobs for user: " + userId);
                }
                synchronized (mLock) {
                    mUidToPackageCache.clear();
                    cancelJobsForUserLocked(userId);
                    for (int c = 0; c < mControllers.size(); ++c) {
                        mControllers.get(c).onUserRemovedLocked(userId);
                    }
                }
                mConcurrencyManager.onUserRemoved(userId);
            } else if (Intent.ACTION_QUERY_PACKAGE_RESTART.equals(action)) {
                // Has this package scheduled any jobs, such that we will take action
                // if it were to be force-stopped?
                if (pkgUid != -1) {
                    List<JobStatus> jobsForUid;
                    synchronized (mLock) {
                        jobsForUid = mJobs.getJobsByUid(pkgUid);
                    }
                    for (int i = jobsForUid.size() - 1; i >= 0; i--) {
                        if (jobsForUid.get(i).getSourcePackageName().equals(pkgName)) {
                            if (DEBUG) {
                                Slog.d(TAG, "Restart query: package " + pkgName + " at uid "
                                        + pkgUid + " has jobs");
                            }
                            setResultCode(Activity.RESULT_OK);
                            break;
                        }
                    }
                }
            } else if (Intent.ACTION_PACKAGE_RESTARTED.equals(action)) {
                // possible force-stop
                if (pkgUid != -1) {
                    if (DEBUG) {
                        Slog.d(TAG, "Removing jobs for pkg " + pkgName + " at uid " + pkgUid);
                    }
                    synchronized (mLock) {
                        cancelJobsForPackageAndUidLocked(pkgName, pkgUid,
                                JobParameters.STOP_REASON_USER,
                                JobParameters.INTERNAL_STOP_REASON_CANCELED,
                                "app force stopped");
                    }
                }
            }
        }
    };

    private String getPackageName(Intent intent) {
        Uri uri = intent.getData();
        String pkg = uri != null ? uri.getSchemeSpecificPart() : null;
        return pkg;
    }

    final private IUidObserver mUidObserver = new IUidObserver.Stub() {
        @Override public void onUidStateChanged(int uid, int procState, long procStateSeq,
                int capability) {
            mHandler.obtainMessage(MSG_UID_STATE_CHANGED, uid, procState).sendToTarget();
        }

        @Override public void onUidGone(int uid, boolean disabled) {
            mHandler.obtainMessage(MSG_UID_GONE, uid, disabled ? 1 : 0).sendToTarget();
        }

        @Override public void onUidActive(int uid) throws RemoteException {
            mHandler.obtainMessage(MSG_UID_ACTIVE, uid, 0).sendToTarget();
        }

        @Override public void onUidIdle(int uid, boolean disabled) {
            mHandler.obtainMessage(MSG_UID_IDLE, uid, disabled ? 1 : 0).sendToTarget();
        }

        @Override public void onUidCachedChanged(int uid, boolean cached) {
        }

        @Override public void onUidProcAdjChanged(int uid) {
        }
    };

    public Context getTestableContext() {
        return getContext();
    }

    public Object getLock() {
        return mLock;
    }

    public JobStore getJobStore() {
        return mJobs;
    }

    public Constants getConstants() {
        return mConstants;
    }

    @NonNull
    PendingJobQueue getPendingJobQueue() {
        return mPendingJobQueue;
    }

    @NonNull
    public WorkSource deriveWorkSource(int sourceUid, @Nullable String sourcePackageName) {
        if (WorkSource.isChainedBatteryAttributionEnabled(getContext())) {
            WorkSource ws = new WorkSource();
            ws.createWorkChain()
                    .addNode(sourceUid, sourcePackageName)
                    .addNode(Process.SYSTEM_UID, "JobScheduler");
            return ws;
        } else {
            return sourcePackageName == null
                    ? new WorkSource(sourceUid) : new WorkSource(sourceUid, sourcePackageName);
        }
    }

    @Nullable
    @GuardedBy("mLock")
    public ArraySet<String> getPackagesForUidLocked(final int uid) {
        ArraySet<String> packages = mUidToPackageCache.get(uid);
        if (packages == null) {
            try {
                String[] pkgs = AppGlobals.getPackageManager()
                        .getPackagesForUid(uid);
                if (pkgs != null) {
                    for (String pkg : pkgs) {
                        mUidToPackageCache.add(uid, pkg);
                    }
                    packages = mUidToPackageCache.get(uid);
                }
            } catch (RemoteException e) {
                // Shouldn't happen.
            }
        }
        return packages;
    }

    @Override
    public void onUserStarting(@NonNull TargetUser user) {
        synchronized (mLock) {
            mStartedUsers = ArrayUtils.appendInt(mStartedUsers, user.getUserIdentifier());
        }
    }

    /** Start jobs after user is available, delayed by a few seconds since non-urgent. */
    @Override
    public void onUserCompletedEvent(@NonNull TargetUser user, UserCompletedEventType eventType) {
        if (eventType.includesOnUserStarting() || eventType.includesOnUserUnlocked()) {
            // onUserStarting: direct-boot-aware jobs can safely run
            // onUserUnlocked: direct-boot-UNaware jobs can safely run.
            mHandler.obtainMessage(MSG_CHECK_JOB).sendToTarget();
        }
    }

    @Override
    public void onUserStopping(@NonNull TargetUser user) {
        synchronized (mLock) {
            mStartedUsers = ArrayUtils.removeInt(mStartedUsers, user.getUserIdentifier());
        }
    }

    /**
     * Return whether an UID is active or idle.
     */
    private boolean isUidActive(int uid) {
        return mAppStateTracker.isUidActiveSynced(uid);
    }

    private final Predicate<Integer> mIsUidActivePredicate = this::isUidActive;

    public int scheduleAsPackage(JobInfo job, JobWorkItem work, int uId, String packageName,
            int userId, String tag) {
        // Rate limit excessive schedule() calls.
        final String servicePkg = job.getService().getPackageName();
        if (job.isPersisted() && (packageName == null || packageName.equals(servicePkg))) {
            // Only limit schedule calls for persisted jobs scheduled by the app itself.
            final String pkg = packageName == null ? servicePkg : packageName;
            if (!mQuotaTracker.isWithinQuota(userId, pkg, QUOTA_TRACKER_SCHEDULE_PERSISTED_TAG)) {
                if (mQuotaTracker.isWithinQuota(userId, pkg, QUOTA_TRACKER_SCHEDULE_LOGGED)) {
                    // Don't log too frequently
                    Slog.wtf(TAG, userId + "-" + pkg + " has called schedule() too many times");
                    mQuotaTracker.noteEvent(userId, pkg, QUOTA_TRACKER_SCHEDULE_LOGGED);
                }
                mAppStandbyInternal.restrictApp(
                        pkg, userId, UsageStatsManager.REASON_SUB_FORCED_SYSTEM_FLAG_BUGGY);
                if (mConstants.API_QUOTA_SCHEDULE_THROW_EXCEPTION) {
                    final boolean isDebuggable;
                    synchronized (mLock) {
                        if (!mDebuggableApps.containsKey(packageName)) {
                            try {
                                final ApplicationInfo appInfo = AppGlobals.getPackageManager()
                                        .getApplicationInfo(pkg, 0, userId);
                                if (appInfo != null) {
                                    mDebuggableApps.put(packageName,
                                            (appInfo.flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0);
                                } else {
                                    return JobScheduler.RESULT_FAILURE;
                                }
                            } catch (RemoteException e) {
                                throw new RuntimeException(e);
                            }
                        }
                        isDebuggable = mDebuggableApps.get(packageName);
                    }
                    if (isDebuggable) {
                        // Only throw the exception for debuggable apps.
                        throw new LimitExceededException(
                                "schedule()/enqueue() called more than "
                                        + mQuotaTracker.getLimit(
                                        QUOTA_TRACKER_CATEGORY_SCHEDULE_PERSISTED)
                                        + " times in the past "
                                        + mQuotaTracker.getWindowSizeMs(
                                        QUOTA_TRACKER_CATEGORY_SCHEDULE_PERSISTED)
                                        + "ms. See the documentation for more information.");
                    }
                }
                if (mConstants.API_QUOTA_SCHEDULE_RETURN_FAILURE_RESULT) {
                    return JobScheduler.RESULT_FAILURE;
                }
            }
            mQuotaTracker.noteEvent(userId, pkg, QUOTA_TRACKER_SCHEDULE_PERSISTED_TAG);
        }

        if (mActivityManagerInternal.isAppStartModeDisabled(uId, servicePkg)) {
            Slog.w(TAG, "Not scheduling job " + uId + ":" + job.toString()
                    + " -- package not allowed to start");
            return JobScheduler.RESULT_FAILURE;
        }

        synchronized (mLock) {
            final JobStatus toCancel = mJobs.getJobByUidAndJobId(uId, job.getId());

            if (work != null && toCancel != null) {
                // Fast path: we are adding work to an existing job, and the JobInfo is not
                // changing.  We can just directly enqueue this work in to the job.
                if (toCancel.getJob().equals(job)) {

                    toCancel.enqueueWorkLocked(work);

                    // If any of work item is enqueued when the source is in the foreground,
                    // exempt the entire job.
                    toCancel.maybeAddForegroundExemption(mIsUidActivePredicate);

                    return JobScheduler.RESULT_SUCCESS;
                }
            }

            JobStatus jobStatus = JobStatus.createFromJobInfo(job, uId, packageName, userId, tag);

            // Return failure early if expedited job quota used up.
            if (jobStatus.isRequestedExpeditedJob()) {
                if ((mConstants.USE_TARE_POLICY && !mTareController.canScheduleEJ(jobStatus))
                        || (!mConstants.USE_TARE_POLICY
                        && !mQuotaController.isWithinEJQuotaLocked(jobStatus))) {
                    return JobScheduler.RESULT_FAILURE;
                }
            }

            // Give exemption if the source is in the foreground just now.
            // Note if it's a sync job, this method is called on the handler so it's not exactly
            // the state when requestSync() was called, but that should be fine because of the
            // 1 minute foreground grace period.
            jobStatus.maybeAddForegroundExemption(mIsUidActivePredicate);

            if (DEBUG) Slog.d(TAG, "SCHEDULE: " + jobStatus.toShortString());
            // Jobs on behalf of others don't apply to the per-app job cap
            if (packageName == null) {
                if (mJobs.countJobsForUid(uId) > MAX_JOBS_PER_APP) {
                    Slog.w(TAG, "Too many jobs for uid " + uId);
                    throw new IllegalStateException("Apps may not schedule more than "
                            + MAX_JOBS_PER_APP + " distinct jobs");
                }
            }

            // This may throw a SecurityException.
            jobStatus.prepareLocked();

            if (toCancel != null) {
                // Implicitly replaces the existing job record with the new instance
                cancelJobImplLocked(toCancel, jobStatus, JobParameters.STOP_REASON_CANCELLED_BY_APP,
                        JobParameters.INTERNAL_STOP_REASON_CANCELED, "job rescheduled by app");
            } else {
                startTrackingJobLocked(jobStatus, null);
            }

            if (work != null) {
                // If work has been supplied, enqueue it into the new job.
                jobStatus.enqueueWorkLocked(work);
            }

            FrameworkStatsLog.write_non_chained(FrameworkStatsLog.SCHEDULED_JOB_STATE_CHANGED,
                    uId, null, jobStatus.getBatteryName(),
                    FrameworkStatsLog.SCHEDULED_JOB_STATE_CHANGED__STATE__SCHEDULED,
                    JobProtoEnums.INTERNAL_STOP_REASON_UNKNOWN, jobStatus.getStandbyBucket(),
                    jobStatus.getJobId(),
                    jobStatus.hasChargingConstraint(),
                    jobStatus.hasBatteryNotLowConstraint(),
                    jobStatus.hasStorageNotLowConstraint(),
                    jobStatus.hasTimingDelayConstraint(),
                    jobStatus.hasDeadlineConstraint(),
                    jobStatus.hasIdleConstraint(),
                    jobStatus.hasConnectivityConstraint(),
                    jobStatus.hasContentTriggerConstraint(),
                    jobStatus.isRequestedExpeditedJob(),
                    /* isRunningAsExpeditedJob */ false,
                    JobProtoEnums.STOP_REASON_UNDEFINED,
                    jobStatus.getJob().isPrefetch(),
                    jobStatus.getJob().getPriority(),
                    jobStatus.getEffectivePriority(),
                    jobStatus.getNumFailures());

            // If the job is immediately ready to run, then we can just immediately
            // put it in the pending list and try to schedule it.  This is especially
            // important for jobs with a 0 deadline constraint, since they will happen a fair
            // amount, we want to handle them as quickly as possible, and semantically we want to
            // make sure we have started holding the wake lock for the job before returning to
            // the caller.
            // If the job is not yet ready to run, there is nothing more to do -- we are
            // now just waiting for one of its controllers to change state and schedule
            // the job appropriately.
            if (isReadyToBeExecutedLocked(jobStatus)) {
                // This is a new job, we can just immediately put it on the pending
                // list and try to run it.
                mJobPackageTracker.notePending(jobStatus);
                mPendingJobQueue.add(jobStatus);
                maybeRunPendingJobsLocked();
            } else {
                evaluateControllerStatesLocked(jobStatus);
            }
        }
        return JobScheduler.RESULT_SUCCESS;
    }

    public List<JobInfo> getPendingJobs(int uid) {
        synchronized (mLock) {
            List<JobStatus> jobs = mJobs.getJobsByUid(uid);
            ArrayList<JobInfo> outList = new ArrayList<JobInfo>(jobs.size());
            for (int i = jobs.size() - 1; i >= 0; i--) {
                JobStatus job = jobs.get(i);
                outList.add(job.getJob());
            }
            return outList;
        }
    }

    public JobInfo getPendingJob(int uid, int jobId) {
        synchronized (mLock) {
            List<JobStatus> jobs = mJobs.getJobsByUid(uid);
            for (int i = jobs.size() - 1; i >= 0; i--) {
                JobStatus job = jobs.get(i);
                if (job.getJobId() == jobId) {
                    return job.getJob();
                }
            }
            return null;
        }
    }

    private void cancelJobsForUserLocked(int userHandle) {
        final List<JobStatus> jobsForUser = mJobs.getJobsByUser(userHandle);
        for (int i = 0; i < jobsForUser.size(); i++) {
            JobStatus toRemove = jobsForUser.get(i);
            // There's no guarantee that the process has been stopped by the time we get here,
            // but since this is a user-initiated action, it should be fine to just put USER
            // instead of UNINSTALL or DISABLED.
            cancelJobImplLocked(toRemove, null, JobParameters.STOP_REASON_USER,
                    JobParameters.INTERNAL_STOP_REASON_UNINSTALL, "user removed");
        }
    }

    private void cancelJobsForNonExistentUsers() {
        UserManagerInternal umi = LocalServices.getService(UserManagerInternal.class);
        synchronized (mLock) {
            mJobs.removeJobsOfUnlistedUsers(umi.getUserIds());
        }
    }

    private void cancelJobsForPackageAndUidLocked(String pkgName, int uid,
            @JobParameters.StopReason int reason, int internalReasonCode, String debugReason) {
        if ("android".equals(pkgName)) {
            Slog.wtfStack(TAG, "Can't cancel all jobs for system package");
            return;
        }
        final List<JobStatus> jobsForUid = mJobs.getJobsByUid(uid);
        for (int i = jobsForUid.size() - 1; i >= 0; i--) {
            final JobStatus job = jobsForUid.get(i);
            if (job.getSourcePackageName().equals(pkgName)) {
                cancelJobImplLocked(job, null, reason, internalReasonCode, debugReason);
            }
        }
    }

    /**
     * Entry point from client to cancel all jobs originating from their uid.
     * This will remove the job from the master list, and cancel the job if it was staged for
     * execution or being executed.
     *
     * @param uid Uid to check against for removal of a job.
     */
    public boolean cancelJobsForUid(int uid, @JobParameters.StopReason int reason,
            int internalReasonCode, String debugReason) {
        if (uid == Process.SYSTEM_UID) {
            Slog.wtfStack(TAG, "Can't cancel all jobs for system uid");
            return false;
        }

        boolean jobsCanceled = false;
        synchronized (mLock) {
            final List<JobStatus> jobsForUid = mJobs.getJobsByUid(uid);
            for (int i = 0; i < jobsForUid.size(); i++) {
                JobStatus toRemove = jobsForUid.get(i);
                cancelJobImplLocked(toRemove, null, reason, internalReasonCode, debugReason);
                jobsCanceled = true;
            }
        }
        return jobsCanceled;
    }

    /**
     * Entry point from client to cancel the job corresponding to the jobId provided.
     * This will remove the job from the master list, and cancel the job if it was staged for
     * execution or being executed.
     *
     * @param uid   Uid of the calling client.
     * @param jobId Id of the job, provided at schedule-time.
     */
    private boolean cancelJob(int uid, int jobId, int callingUid,
            @JobParameters.StopReason int reason) {
        JobStatus toCancel;
        synchronized (mLock) {
            toCancel = mJobs.getJobByUidAndJobId(uid, jobId);
            if (toCancel != null) {
                cancelJobImplLocked(toCancel, null, reason,
                        JobParameters.INTERNAL_STOP_REASON_CANCELED,
                        "cancel() called by app, callingUid=" + callingUid
                                + " uid=" + uid + " jobId=" + jobId);
            }
            return (toCancel != null);
        }
    }

    /**
     * Cancel the given job, stopping it if it's currently executing.  If {@code incomingJob}
     * is null, the cancelled job is removed outright from the system.  If
     * {@code incomingJob} is non-null, it replaces {@code cancelled} in the store of
     * currently scheduled jobs.
     */
    private void cancelJobImplLocked(JobStatus cancelled, JobStatus incomingJob,
            @JobParameters.StopReason int reason, int internalReasonCode, String debugReason) {
        if (DEBUG) Slog.d(TAG, "CANCEL: " + cancelled.toShortString());
        cancelled.unprepareLocked();
        stopTrackingJobLocked(cancelled, incomingJob, true /* writeBack */);
        // Remove from pending queue.
        if (mPendingJobQueue.remove(cancelled)) {
            mJobPackageTracker.noteNonpending(cancelled);
        }
        mChangedJobList.remove(cancelled);
        // Cancel if running.
        mConcurrencyManager.stopJobOnServiceContextLocked(
                cancelled, reason, internalReasonCode, debugReason);
        // If this is a replacement, bring in the new version of the job
        if (incomingJob != null) {
            if (DEBUG) Slog.i(TAG, "Tracking replacement job " + incomingJob.toShortString());
            startTrackingJobLocked(incomingJob, cancelled);
        }
        reportActiveLocked();
    }

    void updateUidState(int uid, int procState) {
        synchronized (mLock) {
            final int prevBias = mUidBiasOverride.get(uid, JobInfo.BIAS_DEFAULT);
            if (procState == ActivityManager.PROCESS_STATE_TOP) {
                // Only use this if we are exactly the top app.  All others can live
                // with just the foreground bias.  This means that persistent processes
                // can never have the top app bias...  that is fine.
                mUidBiasOverride.put(uid, JobInfo.BIAS_TOP_APP);
            } else if (procState <= ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE) {
                mUidBiasOverride.put(uid, JobInfo.BIAS_FOREGROUND_SERVICE);
            } else if (procState <= ActivityManager.PROCESS_STATE_BOUND_FOREGROUND_SERVICE) {
                mUidBiasOverride.put(uid, JobInfo.BIAS_BOUND_FOREGROUND_SERVICE);
            } else {
                mUidBiasOverride.delete(uid);
            }
            final int newBias = mUidBiasOverride.get(uid, JobInfo.BIAS_DEFAULT);
            if (prevBias != newBias) {
                if (DEBUG) {
                    Slog.d(TAG, "UID " + uid + " bias changed from " + prevBias + " to " + newBias);
                }
                for (int c = 0; c < mControllers.size(); ++c) {
                    mControllers.get(c).onUidBiasChangedLocked(uid, prevBias, newBias);
                }
                mConcurrencyManager.onUidBiasChangedLocked(prevBias, newBias);
            }
        }
    }

    /** Return the current bias of the given UID. */
    public int getUidBias(int uid) {
        synchronized (mLock) {
            return mUidBiasOverride.get(uid, JobInfo.BIAS_DEFAULT);
        }
    }

    @Override
    public void onDeviceIdleStateChanged(boolean deviceIdle) {
        synchronized (mLock) {
            if (DEBUG) {
                Slog.d(TAG, "Doze state changed: " + deviceIdle);
            }
            if (!deviceIdle) {
                // When coming out of idle, allow thing to start back up.
                if (mReadyToRock) {
                    if (mLocalDeviceIdleController != null) {
                        if (!mReportedActive) {
                            mReportedActive = true;
                            mLocalDeviceIdleController.setJobsActive(true);
                        }
                    }
                    mHandler.obtainMessage(MSG_CHECK_JOB).sendToTarget();
                }
            }
        }
    }

    @Override
    public void onRestrictedBucketChanged(List<JobStatus> jobs) {
        final int len = jobs.size();
        if (len == 0) {
            Slog.wtf(TAG, "onRestrictedBucketChanged called with no jobs");
            return;
        }
        synchronized (mLock) {
            for (int i = 0; i < len; ++i) {
                JobStatus js = jobs.get(i);
                for (int j = mRestrictiveControllers.size() - 1; j >= 0; --j) {
                    // Effective standby bucket can change after this in some situations so use
                    // the real bucket so that the job is tracked by the controllers.
                    if (js.getStandbyBucket() == RESTRICTED_INDEX) {
                        mRestrictiveControllers.get(j).startTrackingRestrictedJobLocked(js);
                    } else {
                        mRestrictiveControllers.get(j).stopTrackingRestrictedJobLocked(js);
                    }
                }
            }
        }
        mHandler.obtainMessage(MSG_CHECK_JOB).sendToTarget();
    }

    void reportActiveLocked() {
        // active is true if pending queue contains jobs OR some job is running.
        boolean active = mPendingJobQueue.size() > 0;
        if (!active) {
            final ArraySet<JobStatus> runningJobs = mConcurrencyManager.getRunningJobsLocked();
            for (int i = runningJobs.size() - 1; i >= 0; --i) {
                final JobStatus job = runningJobs.valueAt(i);
                if (!job.canRunInDoze()) {
                    // We will report active if we have a job running and it does not have an
                    // exception that allows it to run in Doze.
                    active = true;
                    break;
                }
            }
        }

        if (mReportedActive != active) {
            mReportedActive = active;
            if (mLocalDeviceIdleController != null) {
                mLocalDeviceIdleController.setJobsActive(active);
            }
        }
    }

    void reportAppUsage(String packageName, int userId) {
        // This app just transitioned into interactive use or near equivalent, so we should
        // take a look at its job state for feedback purposes.
    }

    /**
     * Initializes the system service.
     * <p>
     * Subclasses must define a single argument constructor that accepts the context
     * and passes it to super.
     * </p>
     *
     * @param context The system server context.
     */
    public JobSchedulerService(Context context) {
        super(context);

        mLocalPM = LocalServices.getService(PackageManagerInternal.class);
        mActivityManagerInternal = Objects.requireNonNull(
                LocalServices.getService(ActivityManagerInternal.class));

        mHandler = new JobHandler(context.getMainLooper());
        mConstants = new Constants();
        mConstantsObserver = new ConstantsObserver();
        mJobSchedulerStub = new JobSchedulerStub();

        mConcurrencyManager = new JobConcurrencyManager(this);

        // Set up the app standby bucketing tracker
        mStandbyTracker = new StandbyTracker();
        mUsageStats = LocalServices.getService(UsageStatsManagerInternal.class);
        mQuotaTracker = new CountQuotaTracker(context, QUOTA_CATEGORIZER);
        mQuotaTracker.setCountLimit(QUOTA_TRACKER_CATEGORY_SCHEDULE_PERSISTED,
                mConstants.API_QUOTA_SCHEDULE_COUNT,
                mConstants.API_QUOTA_SCHEDULE_WINDOW_MS);
        // Log at most once per minute.
        mQuotaTracker.setCountLimit(QUOTA_TRACKER_CATEGORY_SCHEDULE_LOGGED, 1, 60_000);

        mAppStandbyInternal = LocalServices.getService(AppStandbyInternal.class);
        mAppStandbyInternal.addListener(mStandbyTracker);

        // The job store needs to call back
        publishLocalService(JobSchedulerInternal.class, new LocalService());

        // Initialize the job store and set up any persisted jobs
        mJobs = JobStore.initAndGet(this);

        mBatteryStateTracker = new BatteryStateTracker();
        mBatteryStateTracker.startTracking();

        // Create the controllers.
        mControllers = new ArrayList<StateController>();
        final ConnectivityController connectivityController = new ConnectivityController(this);
        mControllers.add(connectivityController);
        mControllers.add(new TimeController(this));
        final IdleController idleController = new IdleController(this);
        mControllers.add(idleController);
        final BatteryController batteryController = new BatteryController(this);
        mControllers.add(batteryController);
        mStorageController = new StorageController(this);
        mControllers.add(mStorageController);
        final BackgroundJobsController backgroundJobsController =
                new BackgroundJobsController(this);
        mControllers.add(backgroundJobsController);
        mControllers.add(new ContentObserverController(this));
        mDeviceIdleJobsController = new DeviceIdleJobsController(this);
        mControllers.add(mDeviceIdleJobsController);
        mPrefetchController = new PrefetchController(this);
        mControllers.add(mPrefetchController);
        mQuotaController =
                new QuotaController(this, backgroundJobsController, connectivityController);
        mControllers.add(mQuotaController);
        mControllers.add(new ComponentController(this));
        mTareController =
                new TareController(this, backgroundJobsController, connectivityController);
        mControllers.add(mTareController);

        mRestrictiveControllers = new ArrayList<>();
        mRestrictiveControllers.add(batteryController);
        mRestrictiveControllers.add(connectivityController);
        mRestrictiveControllers.add(idleController);

        // Create restrictions
        mJobRestrictions = new ArrayList<>();
        mJobRestrictions.add(new ThermalStatusRestriction(this));

        // If the job store determined that it can't yet reschedule persisted jobs,
        // we need to start watching the clock.
        if (!mJobs.jobTimesInflatedValid()) {
            Slog.w(TAG, "!!! RTC not yet good; tracking time updates for job scheduling");
            context.registerReceiver(mTimeSetReceiver, new IntentFilter(Intent.ACTION_TIME_CHANGED));
        }
    }

    private final BroadcastReceiver mTimeSetReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_TIME_CHANGED.equals(intent.getAction())) {
                // When we reach clock sanity, recalculate the temporal windows
                // of all affected jobs.
                if (mJobs.clockNowValidToInflate(sSystemClock.millis())) {
                    Slog.i(TAG, "RTC now valid; recalculating persisted job windows");

                    // We've done our job now, so stop watching the time.
                    context.unregisterReceiver(this);

                    // And kick off the work to update the affected jobs, using a secondary
                    // thread instead of chugging away here on the main looper thread.
                    new Thread(mJobTimeUpdater, "JobSchedulerTimeSetReceiver").start();
                }
            }
        }
    };

    private final Runnable mJobTimeUpdater = () -> {
        Process.setThreadPriority(Process.THREAD_PRIORITY_FOREGROUND);

        final ArrayList<JobStatus> toRemove = new ArrayList<>();
        final ArrayList<JobStatus> toAdd = new ArrayList<>();
        synchronized (mLock) {
            // Note: we intentionally both look up the existing affected jobs and replace them
            // with recalculated ones inside the same lock lifetime.
            getJobStore().getRtcCorrectedJobsLocked(toAdd, toRemove);

            // Now, at each position [i], we have both the existing JobStatus
            // and the one that replaces it.
            final int N = toAdd.size();
            for (int i = 0; i < N; i++) {
                final JobStatus oldJob = toRemove.get(i);
                final JobStatus newJob = toAdd.get(i);
                if (DEBUG) {
                    Slog.v(TAG, "  replacing " + oldJob + " with " + newJob);
                }
                cancelJobImplLocked(oldJob, newJob, JobParameters.STOP_REASON_SYSTEM_PROCESSING,
                        JobParameters.INTERNAL_STOP_REASON_RTC_UPDATED, "deferred rtc calculation");
            }
        }
    };

    @Override
    public void onStart() {
        publishBinderService(Context.JOB_SCHEDULER_SERVICE, mJobSchedulerStub);
    }

    @Override
    public void onBootPhase(int phase) {
        if (PHASE_SYSTEM_SERVICES_READY == phase) {
            mConstantsObserver.start();
            for (StateController controller : mControllers) {
                controller.onSystemServicesReady();
            }

            mAppStateTracker = (AppStateTrackerImpl) Objects.requireNonNull(
                    LocalServices.getService(AppStateTracker.class));

            LocalServices.getService(StorageManagerInternal.class)
                    .registerCloudProviderChangeListener(new CloudProviderChangeListener());

            // Register br for package removals and user removals.
            final IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED);
            filter.addAction(Intent.ACTION_PACKAGE_ADDED);
            filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
            filter.addAction(Intent.ACTION_PACKAGE_RESTARTED);
            filter.addAction(Intent.ACTION_QUERY_PACKAGE_RESTART);
            filter.addDataScheme("package");
            getContext().registerReceiverAsUser(
                    mBroadcastReceiver, UserHandle.ALL, filter, null, null);
            final IntentFilter userFilter = new IntentFilter(Intent.ACTION_USER_REMOVED);
            userFilter.addAction(Intent.ACTION_USER_ADDED);
            getContext().registerReceiverAsUser(
                    mBroadcastReceiver, UserHandle.ALL, userFilter, null, null);
            try {
                ActivityManager.getService().registerUidObserver(mUidObserver,
                        ActivityManager.UID_OBSERVER_PROCSTATE | ActivityManager.UID_OBSERVER_GONE
                        | ActivityManager.UID_OBSERVER_IDLE | ActivityManager.UID_OBSERVER_ACTIVE,
                        ActivityManager.PROCESS_STATE_UNKNOWN, null);
            } catch (RemoteException e) {
                // ignored; both services live in system_server
            }

            mConcurrencyManager.onSystemReady();

            // Remove any jobs that are not associated with any of the current users.
            cancelJobsForNonExistentUsers();

            for (int i = mJobRestrictions.size() - 1; i >= 0; i--) {
                mJobRestrictions.get(i).onSystemServicesReady();
            }
        } else if (phase == PHASE_THIRD_PARTY_APPS_CAN_START) {
            synchronized (mLock) {
                // Let's go!
                mReadyToRock = true;
                mLocalDeviceIdleController =
                        LocalServices.getService(DeviceIdleInternal.class);
                mConcurrencyManager.onThirdPartyAppsCanStart();
                // Attach jobs to their controllers.
                mJobs.forEachJob((job) -> {
                    for (int controller = 0; controller < mControllers.size(); controller++) {
                        final StateController sc = mControllers.get(controller);
                        sc.maybeStartTrackingJobLocked(job, null);
                    }
                });
                // GO GO GO!
                mHandler.obtainMessage(MSG_CHECK_JOB).sendToTarget();
            }
        }
    }

    /**
     * Called when we have a job status object that we need to insert in our
     * {@link com.android.server.job.JobStore}, and make sure all the relevant controllers know
     * about.
     */
    private void startTrackingJobLocked(JobStatus jobStatus, JobStatus lastJob) {
        if (!jobStatus.isPreparedLocked()) {
            Slog.wtf(TAG, "Not yet prepared when started tracking: " + jobStatus);
        }
        jobStatus.enqueueTime = sElapsedRealtimeClock.millis();
        final boolean update = mJobs.add(jobStatus);
        if (mReadyToRock) {
            for (int i = 0; i < mControllers.size(); i++) {
                StateController controller = mControllers.get(i);
                if (update) {
                    controller.maybeStopTrackingJobLocked(jobStatus, null, true);
                }
                controller.maybeStartTrackingJobLocked(jobStatus, lastJob);
            }
        }
    }

    /**
     * Called when we want to remove a JobStatus object that we've finished executing.
     *
     * @return true if the job was removed.
     */
    private boolean stopTrackingJobLocked(JobStatus jobStatus, JobStatus incomingJob,
            boolean removeFromPersisted) {
        // Deal with any remaining work items in the old job.
        jobStatus.stopTrackingJobLocked(incomingJob);

        // Remove from store as well as controllers.
        final boolean removed = mJobs.remove(jobStatus, removeFromPersisted);
        if (!removed) {
            // We never create JobStatus objects for the express purpose of removing them, and this
            // method is only ever called for jobs that were saved in the JobStore at some point,
            // so if we can't find it, something may be wrong. As of Android T, there is a
            // legitimate code path where removed is false --- when an actively running job is
            // cancelled (eg. via JobScheduler.cancel() or the app scheduling a new job with the
            // same job ID), we remove it from the JobStore and tell the JobServiceContext to stop
            // running the job. Once the job stops running, we then call this method again.
            // TODO: rework code so we don't intentionally call this method twice for the same job
            Slog.w(TAG, "Job didn't exist in JobStore: " + jobStatus.toShortString());
        }
        if (mReadyToRock) {
            for (int i = 0; i < mControllers.size(); i++) {
                StateController controller = mControllers.get(i);
                controller.maybeStopTrackingJobLocked(jobStatus, incomingJob, false);
            }
        }
        return removed;
    }

    /** Return {@code true} if the specified job is currently executing. */
    @GuardedBy("mLock")
    public boolean isCurrentlyRunningLocked(JobStatus job) {
        return mConcurrencyManager.isJobRunningLocked(job);
    }

    private void noteJobPending(JobStatus job) {
        mJobPackageTracker.notePending(job);
    }

    void noteJobsPending(List<JobStatus> jobs) {
        for (int i = jobs.size() - 1; i >= 0; i--) {
            noteJobPending(jobs.get(i));
        }
    }

    private void noteJobNonPending(JobStatus job) {
        mJobPackageTracker.noteNonpending(job);
    }

    private void clearPendingJobQueue() {
        JobStatus job;
        mPendingJobQueue.resetIterator();
        while ((job = mPendingJobQueue.next()) != null) {
            noteJobNonPending(job);
        }
        mPendingJobQueue.clear();
    }

    /**
     * Reschedules the given job based on the job's backoff policy. It doesn't make sense to
     * specify an override deadline on a failed job (the failed job will run even though it's not
     * ready), so we reschedule it with {@link JobStatus#NO_LATEST_RUNTIME}, but specify that any
     * ready job with {@link JobStatus#getNumFailures()} > 0 will be executed.
     *
     * @param failureToReschedule Provided job status that we will reschedule.
     * @return A newly instantiated JobStatus with the same constraints as the last job except
     * with adjusted timing constraints.
     * @see #maybeQueueReadyJobsForExecutionLocked
     */
    @VisibleForTesting
    JobStatus getRescheduleJobForFailureLocked(JobStatus failureToReschedule) {
        final long elapsedNowMillis = sElapsedRealtimeClock.millis();
        final JobInfo job = failureToReschedule.getJob();

        final long initialBackoffMillis = job.getInitialBackoffMillis();
        final int backoffAttempts = failureToReschedule.getNumFailures() + 1;
        long delayMillis;

        switch (job.getBackoffPolicy()) {
            case JobInfo.BACKOFF_POLICY_LINEAR: {
                long backoff = initialBackoffMillis;
                if (backoff < mConstants.MIN_LINEAR_BACKOFF_TIME_MS) {
                    backoff = mConstants.MIN_LINEAR_BACKOFF_TIME_MS;
                }
                delayMillis = backoff * backoffAttempts;
            } break;
            default:
                if (DEBUG) {
                    Slog.v(TAG, "Unrecognised back-off policy, defaulting to exponential.");
                }
            case JobInfo.BACKOFF_POLICY_EXPONENTIAL: {
                long backoff = initialBackoffMillis;
                if (backoff < mConstants.MIN_EXP_BACKOFF_TIME_MS) {
                    backoff = mConstants.MIN_EXP_BACKOFF_TIME_MS;
                }
                delayMillis = (long) Math.scalb(backoff, backoffAttempts - 1);
            } break;
        }
        delayMillis =
                Math.min(delayMillis, JobInfo.MAX_BACKOFF_DELAY_MILLIS);
        JobStatus newJob = new JobStatus(failureToReschedule,
                elapsedNowMillis + delayMillis,
                JobStatus.NO_LATEST_RUNTIME, backoffAttempts,
                failureToReschedule.getLastSuccessfulRunTime(), sSystemClock.millis());
        if (job.isPeriodic()) {
            newJob.setOriginalLatestRunTimeElapsed(
                    failureToReschedule.getOriginalLatestRunTimeElapsed());
        }
        for (int ic = 0; ic < mControllers.size(); ic++) {
            StateController controller = mControllers.get(ic);
            controller.rescheduleForFailureLocked(newJob, failureToReschedule);
        }
        return newJob;
    }

    /**
     * Maximum time buffer in which JobScheduler will try to optimize periodic job scheduling. This
     * does not cause a job's period to be larger than requested (eg: if the requested period is
     * shorter than this buffer). This is used to put a limit on when JobScheduler will intervene
     * and try to optimize scheduling if the current job finished less than this amount of time to
     * the start of the next period
     */
    private static final long PERIODIC_JOB_WINDOW_BUFFER = 30 * MINUTE_IN_MILLIS;

    /** The maximum period a periodic job can have. Anything higher will be clamped down to this. */
    public static final long MAX_ALLOWED_PERIOD_MS = 365 * 24 * 60 * 60 * 1000L;

    /**
     * Called after a periodic has executed so we can reschedule it. We take the last execution
     * time of the job to be the time of completion (i.e. the time at which this function is
     * called).
     * <p>This could be inaccurate b/c the job can run for as long as
     * {@link Constants#DEFAULT_RUNTIME_FREE_QUOTA_MAX_LIMIT_MS}, but
     * will lead to underscheduling at least, rather than if we had taken the last execution time
     * to be the start of the execution.
     *
     * @return A new job representing the execution criteria for this instantiation of the
     * recurring job.
     */
    @VisibleForTesting
    JobStatus getRescheduleJobForPeriodic(JobStatus periodicToReschedule) {
        final long elapsedNow = sElapsedRealtimeClock.millis();
        final long newLatestRuntimeElapsed;
        // Make sure period is in the interval [min_possible_period, max_possible_period].
        final long period = Math.max(JobInfo.getMinPeriodMillis(),
                Math.min(MAX_ALLOWED_PERIOD_MS, periodicToReschedule.getJob().getIntervalMillis()));
        // Make sure flex is in the interval [min_possible_flex, period].
        final long flex = Math.max(JobInfo.getMinFlexMillis(),
                Math.min(period, periodicToReschedule.getJob().getFlexMillis()));
        long rescheduleBuffer = 0;

        long olrte = periodicToReschedule.getOriginalLatestRunTimeElapsed();
        if (olrte < 0 || olrte == JobStatus.NO_LATEST_RUNTIME) {
            Slog.wtf(TAG, "Invalid periodic job original latest run time: " + olrte);
            olrte = elapsedNow;
        }
        final long latestRunTimeElapsed = olrte;

        final long diffMs = Math.abs(elapsedNow - latestRunTimeElapsed);
        if (elapsedNow > latestRunTimeElapsed) {
            // The job ran past its expected run window. Have it count towards the current window
            // and schedule a new job for the next window.
            if (DEBUG) {
                Slog.i(TAG, "Periodic job ran after its intended window by " + diffMs + " ms");
            }
            long numSkippedWindows = (diffMs / period) + 1; // +1 to include original window
            // Determine how far into a single period the job ran, and determine if it's too close
            // to the start of the next period. If the difference between the start of the execution
            // window and the previous execution time inside of the period is less than the
            // threshold, then we say that the job ran too close to the next period.
            if (period != flex && (period - flex - (diffMs % period)) <= flex / 6) {
                if (DEBUG) {
                    Slog.d(TAG, "Custom flex job ran too close to next window.");
                }
                // For custom flex periods, if the job was run too close to the next window,
                // skip the next window and schedule for the following one.
                numSkippedWindows += 1;
            }
            newLatestRuntimeElapsed = latestRunTimeElapsed + (period * numSkippedWindows);
        } else {
            newLatestRuntimeElapsed = latestRunTimeElapsed + period;
            if (diffMs < PERIODIC_JOB_WINDOW_BUFFER && diffMs < period / 6) {
                // Add a little buffer to the start of the next window so the job doesn't run
                // too soon after this completed one.
                rescheduleBuffer = Math.min(PERIODIC_JOB_WINDOW_BUFFER, period / 6 - diffMs);
            }
        }

        if (newLatestRuntimeElapsed < elapsedNow) {
            Slog.wtf(TAG, "Rescheduling calculated latest runtime in the past: "
                    + newLatestRuntimeElapsed);
            return new JobStatus(periodicToReschedule,
                    elapsedNow + period - flex, elapsedNow + period,
                    0 /* backoffAttempt */,
                    sSystemClock.millis() /* lastSuccessfulRunTime */,
                    periodicToReschedule.getLastFailedRunTime());
        }

        final long newEarliestRunTimeElapsed = newLatestRuntimeElapsed
                - Math.min(flex, period - rescheduleBuffer);

        if (DEBUG) {
            Slog.v(TAG, "Rescheduling executed periodic. New execution window [" +
                    newEarliestRunTimeElapsed / 1000 + ", " + newLatestRuntimeElapsed / 1000
                    + "]s");
        }
        return new JobStatus(periodicToReschedule,
                newEarliestRunTimeElapsed, newLatestRuntimeElapsed,
                0 /* backoffAttempt */,
                sSystemClock.millis() /* lastSuccessfulRunTime */,
                periodicToReschedule.getLastFailedRunTime());
    }

    // JobCompletedListener implementations.

    /**
     * A job just finished executing. We fetch the
     * {@link com.android.server.job.controllers.JobStatus} from the store and depending on
     * whether we want to reschedule we re-add it to the controllers.
     *
     * @param jobStatus       Completed job.
     * @param needsReschedule Whether the implementing class should reschedule this job.
     */
    @Override
    public void onJobCompletedLocked(JobStatus jobStatus, int debugStopReason,
            boolean needsReschedule) {
        if (DEBUG) {
            Slog.d(TAG, "Completed " + jobStatus + ", reason=" + debugStopReason
                    + ", reschedule=" + needsReschedule);
        }

        mLastCompletedJobs[mLastCompletedJobIndex] = jobStatus;
        mLastCompletedJobTimeElapsed[mLastCompletedJobIndex] = sElapsedRealtimeClock.millis();
        mLastCompletedJobIndex = (mLastCompletedJobIndex + 1) % NUM_COMPLETED_JOB_HISTORY;

        if (debugStopReason == JobParameters.INTERNAL_STOP_REASON_UNINSTALL
                || debugStopReason == JobParameters.INTERNAL_STOP_REASON_DATA_CLEARED) {
            // The job should have already been cleared from the rest of the JS tracking. No need
            // to go through all that flow again.
            jobStatus.unprepareLocked();
            reportActiveLocked();
            return;
        }

        // Intentionally not checking expedited job quota here. An app can't find out if it's run
        // out of quota when it asks JS to reschedule an expedited job. Instead, the rescheduled
        // EJ will just be demoted to a regular job if the app has no EJ quota left.

        // If the job wants to be rescheduled, we first need to make the next upcoming
        // job so we can transfer any appropriate state over from the previous job when
        // we stop it.
        final JobStatus rescheduledJob = needsReschedule
                ? getRescheduleJobForFailureLocked(jobStatus) : null;
        if (rescheduledJob != null
                && (debugStopReason == JobParameters.INTERNAL_STOP_REASON_TIMEOUT
                || debugStopReason == JobParameters.INTERNAL_STOP_REASON_PREEMPT)) {
            rescheduledJob.disallowRunInBatterySaverAndDoze();
        }

        // Do not write back immediately if this is a periodic job. The job may get lost if system
        // shuts down before it is added back.
        if (!stopTrackingJobLocked(jobStatus, rescheduledJob, !jobStatus.getJob().isPeriodic())) {
            if (DEBUG) {
                Slog.d(TAG, "Could not find job to remove. Was job removed while executing?");
            }
            JobStatus newJs = mJobs.getJobByUidAndJobId(jobStatus.getUid(), jobStatus.getJobId());
            if (newJs != null) {
                // This job was stopped because the app scheduled a new job with the same job ID.
                // Check if the new job is ready to run.
                mHandler.obtainMessage(MSG_CHECK_INDIVIDUAL_JOB, newJs).sendToTarget();
            }
            return;
        }

        if (rescheduledJob != null) {
            try {
                rescheduledJob.prepareLocked();
            } catch (SecurityException e) {
                Slog.w(TAG, "Unable to regrant job permissions for " + rescheduledJob);
            }
            startTrackingJobLocked(rescheduledJob, jobStatus);
        } else if (jobStatus.getJob().isPeriodic()) {
            JobStatus rescheduledPeriodic = getRescheduleJobForPeriodic(jobStatus);
            try {
                rescheduledPeriodic.prepareLocked();
            } catch (SecurityException e) {
                Slog.w(TAG, "Unable to regrant job permissions for " + rescheduledPeriodic);
            }
            startTrackingJobLocked(rescheduledPeriodic, jobStatus);
        }
        jobStatus.unprepareLocked();
        reportActiveLocked();
    }

    // StateChangedListener implementations.

    /**
     * Posts a message to the {@link com.android.server.job.JobSchedulerService.JobHandler} to run
     * through a list of jobs and start/stop any whose status has changed.
     */
    @Override
    public void onControllerStateChanged(@Nullable ArraySet<JobStatus> changedJobs) {
        if (changedJobs == null) {
            mHandler.obtainMessage(MSG_CHECK_JOB).sendToTarget();
        } else if (changedJobs.size() > 0) {
            synchronized (mLock) {
                mChangedJobList.addAll(changedJobs);
            }
            mHandler.obtainMessage(MSG_CHECK_CHANGED_JOB_LIST).sendToTarget();
        }
    }

    @Override
    public void onRunJobNow(JobStatus jobStatus) {
        if (jobStatus == null) {
            mHandler.obtainMessage(MSG_CHECK_JOB_GREEDY).sendToTarget();
        } else {
            mHandler.obtainMessage(MSG_CHECK_INDIVIDUAL_JOB, jobStatus).sendToTarget();
        }
    }

    final private class JobHandler extends Handler {

        public JobHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message message) {
            synchronized (mLock) {
                if (!mReadyToRock) {
                    return;
                }
                switch (message.what) {
                    case MSG_CHECK_INDIVIDUAL_JOB: {
                        JobStatus js = (JobStatus) message.obj;
                        if (js != null) {
                            if (isReadyToBeExecutedLocked(js)) {
                                mJobPackageTracker.notePending(js);
                                mPendingJobQueue.add(js);
                            }
                            mChangedJobList.remove(js);
                        } else {
                            Slog.e(TAG, "Given null job to check individually");
                        }
                    } break;
                    case MSG_CHECK_JOB:
                        if (DEBUG) {
                            Slog.d(TAG, "MSG_CHECK_JOB");
                        }
                        if (mReportedActive) {
                            // if jobs are currently being run, queue all ready jobs for execution.
                            queueReadyJobsForExecutionLocked();
                        } else {
                            // Check the list of jobs and run some of them if we feel inclined.
                            maybeQueueReadyJobsForExecutionLocked();
                        }
                        break;
                    case MSG_CHECK_JOB_GREEDY:
                        if (DEBUG) {
                            Slog.d(TAG, "MSG_CHECK_JOB_GREEDY");
                        }
                        queueReadyJobsForExecutionLocked();
                        break;
                    case MSG_CHECK_CHANGED_JOB_LIST:
                        if (DEBUG) {
                            Slog.d(TAG, "MSG_CHECK_CHANGED_JOB_LIST");
                        }
                        checkChangedJobListLocked();
                        break;
                    case MSG_STOP_JOB:
                        cancelJobImplLocked((JobStatus) message.obj, null, message.arg1,
                                JobParameters.INTERNAL_STOP_REASON_CONSTRAINTS_NOT_SATISFIED,
                                "app no longer allowed to run");
                        break;

                    case MSG_UID_STATE_CHANGED: {
                        final int uid = message.arg1;
                        final int procState = message.arg2;
                        updateUidState(uid, procState);
                        break;
                    }
                    case MSG_UID_GONE: {
                        final int uid = message.arg1;
                        final boolean disabled = message.arg2 != 0;
                        updateUidState(uid, ActivityManager.PROCESS_STATE_CACHED_EMPTY);
                        if (disabled) {
                            cancelJobsForUid(uid,
                                    JobParameters.STOP_REASON_BACKGROUND_RESTRICTION,
                                    JobParameters.INTERNAL_STOP_REASON_CONSTRAINTS_NOT_SATISFIED,
                                    "uid gone");
                        }
                        synchronized (mLock) {
                            mDeviceIdleJobsController.setUidActiveLocked(uid, false);
                        }
                        break;
                    }
                    case MSG_UID_ACTIVE: {
                        final int uid = message.arg1;
                        synchronized (mLock) {
                            mDeviceIdleJobsController.setUidActiveLocked(uid, true);
                        }
                        break;
                    }
                    case MSG_UID_IDLE: {
                        final int uid = message.arg1;
                        final boolean disabled = message.arg2 != 0;
                        if (disabled) {
                            cancelJobsForUid(uid,
                                    JobParameters.STOP_REASON_BACKGROUND_RESTRICTION,
                                    JobParameters.INTERNAL_STOP_REASON_CONSTRAINTS_NOT_SATISFIED,
                                    "app uid idle");
                        }
                        synchronized (mLock) {
                            mDeviceIdleJobsController.setUidActiveLocked(uid, false);
                        }
                        break;
                    }

                    case MSG_CHECK_MEDIA_EXEMPTION: {
                        final SomeArgs args = (SomeArgs) message.obj;
                        synchronized (mLock) {
                            updateMediaBackupExemptionLocked(
                                    args.argi1, (String) args.arg1, (String) args.arg2);
                        }
                        args.recycle();
                        break;
                    }
                }
                maybeRunPendingJobsLocked();
            }
        }
    }

    /**
     * Check if a job is restricted by any of the declared {@link JobRestriction JobRestrictions}.
     * Note, that the jobs with {@link JobInfo#BIAS_FOREGROUND_SERVICE} bias or higher may not
     * be restricted, thus we won't even perform the check, but simply return null early.
     *
     * @param job to be checked
     * @return the first {@link JobRestriction} restricting the given job that has been found; null
     * - if passes all the restrictions or has {@link JobInfo#BIAS_FOREGROUND_SERVICE} bias
     * or higher.
     */
    @GuardedBy("mLock")
    JobRestriction checkIfRestricted(JobStatus job) {
        if (evaluateJobBiasLocked(job) >= JobInfo.BIAS_FOREGROUND_SERVICE) {
            // Jobs with BIAS_FOREGROUND_SERVICE or higher should not be restricted
            return null;
        }
        for (int i = mJobRestrictions.size() - 1; i >= 0; i--) {
            final JobRestriction restriction = mJobRestrictions.get(i);
            if (restriction.isJobRestricted(job)) {
                return restriction;
            }
        }
        return null;
    }

    @GuardedBy("mLock")
    private void stopNonReadyActiveJobsLocked() {
        mConcurrencyManager.stopNonReadyActiveJobsLocked();
    }

    /**
     * Run through list of jobs and execute all possible - at least one is expired so we do
     * as many as we can.
     */
    @GuardedBy("mLock")
    private void queueReadyJobsForExecutionLocked() {
        // This method will check and capture all ready jobs, so we don't need to keep any messages
        // in the queue.
        mHandler.removeMessages(MSG_CHECK_JOB_GREEDY);
        mHandler.removeMessages(MSG_CHECK_INDIVIDUAL_JOB);
        // MSG_CHECK_JOB is a weaker form of _GREEDY. Since we're checking and queueing all ready
        // jobs, we don't need to keep any MSG_CHECK_JOB messages in the queue.
        mHandler.removeMessages(MSG_CHECK_JOB);
        // MSG_CHECK_CHANGED_JOB_LIST is a weaker form of _GREEDY. Since we're checking and queueing
        // all ready jobs, we don't need to keep any MSG_CHECK_CHANGED_JOB_LIST messages in the
        // queue.
        mHandler.removeMessages(MSG_CHECK_CHANGED_JOB_LIST);
        mChangedJobList.clear();
        if (DEBUG) {
            Slog.d(TAG, "queuing all ready jobs for execution:");
        }
        clearPendingJobQueue();
        stopNonReadyActiveJobsLocked();
        mJobs.forEachJob(mReadyQueueFunctor);
        mReadyQueueFunctor.postProcessLocked();

        if (DEBUG) {
            final int queuedJobs = mPendingJobQueue.size();
            if (queuedJobs == 0) {
                Slog.d(TAG, "No jobs pending.");
            } else {
                Slog.d(TAG, queuedJobs + " jobs queued.");
            }
        }
    }

    final class ReadyJobQueueFunctor implements Consumer<JobStatus> {
        final ArrayList<JobStatus> newReadyJobs = new ArrayList<>();

        @Override
        public void accept(JobStatus job) {
            if (isReadyToBeExecutedLocked(job)) {
                if (DEBUG) {
                    Slog.d(TAG, "    queued " + job.toShortString());
                }
                newReadyJobs.add(job);
            } else {
                evaluateControllerStatesLocked(job);
            }
        }

        @GuardedBy("mLock")
        private void postProcessLocked() {
            noteJobsPending(newReadyJobs);
            mPendingJobQueue.addAll(newReadyJobs);

            newReadyJobs.clear();
        }
    }

    private final ReadyJobQueueFunctor mReadyQueueFunctor = new ReadyJobQueueFunctor();

    /**
     * The state of at least one job has changed. Here is where we could enforce various
     * policies on when we want to execute jobs.
     */
    final class MaybeReadyJobQueueFunctor implements Consumer<JobStatus> {
        int forceBatchedCount;
        int unbatchedCount;
        final List<JobStatus> runnableJobs = new ArrayList<>();

        public MaybeReadyJobQueueFunctor() {
            reset();
        }

        @Override
        public void accept(JobStatus job) {
            final boolean isRunning = isCurrentlyRunningLocked(job);
            if (isReadyToBeExecutedLocked(job, false)) {
                if (mActivityManagerInternal.isAppStartModeDisabled(job.getUid(),
                        job.getJob().getService().getPackageName())) {
                    Slog.w(TAG, "Aborting job " + job.getUid() + ":"
                            + job.getJob().toString() + " -- package not allowed to start");
                    if (isRunning) {
                        mHandler.obtainMessage(MSG_STOP_JOB,
                                JobParameters.STOP_REASON_BACKGROUND_RESTRICTION, 0, job)
                                .sendToTarget();
                    } else if (mPendingJobQueue.remove(job)) {
                        noteJobNonPending(job);
                    }
                    return;
                }

                final boolean shouldForceBatchJob;
                if (job.shouldTreatAsExpeditedJob()) {
                    // Never batch expedited jobs, even for RESTRICTED apps.
                    shouldForceBatchJob = false;
                } else if (job.getEffectiveStandbyBucket() == RESTRICTED_INDEX) {
                    // Restricted jobs must always be batched
                    shouldForceBatchJob = true;
                } else if (job.getJob().isPrefetch()) {
                    // Only relax batching on prefetch jobs if we expect the app to be launched
                    // relatively soon. PREFETCH_FORCE_BATCH_RELAX_THRESHOLD_MS defines what
                    // "relatively soon" means.
                    final long relativelySoonCutoffTime = sSystemClock.millis()
                            + mConstants.PREFETCH_FORCE_BATCH_RELAX_THRESHOLD_MS;
                    shouldForceBatchJob =
                            mPrefetchController.getNextEstimatedLaunchTimeLocked(job)
                                    > relativelySoonCutoffTime;
                } else if (job.getNumFailures() > 0) {
                    shouldForceBatchJob = false;
                } else {
                    final long nowElapsed = sElapsedRealtimeClock.millis();
                    final boolean batchDelayExpired = job.getFirstForceBatchedTimeElapsed() > 0
                            && nowElapsed - job.getFirstForceBatchedTimeElapsed()
                            >= mConstants.MAX_NON_ACTIVE_JOB_BATCH_DELAY_MS;
                    shouldForceBatchJob =
                            mConstants.MIN_READY_NON_ACTIVE_JOBS_COUNT > 1
                                    && job.getEffectiveStandbyBucket() != ACTIVE_INDEX
                                    && job.getEffectiveStandbyBucket() != EXEMPTED_INDEX
                                    && !batchDelayExpired;
                }

                if (shouldForceBatchJob) {
                    // Force batching non-ACTIVE jobs. Don't include them in the other counts.
                    forceBatchedCount++;
                    if (job.getFirstForceBatchedTimeElapsed() == 0) {
                        job.setFirstForceBatchedTimeElapsed(sElapsedRealtimeClock.millis());
                    }
                } else {
                    unbatchedCount++;
                }
                if (!isRunning) {
                    runnableJobs.add(job);
                }
            } else {
                if (isRunning) {
                    final int internalStopReason;
                    final String debugReason;
                    if (!job.isReady()) {
                        if (job.getEffectiveStandbyBucket() == RESTRICTED_INDEX
                                && job.getStopReason() == JobParameters.STOP_REASON_APP_STANDBY) {
                            internalStopReason =
                                    JobParameters.INTERNAL_STOP_REASON_RESTRICTED_BUCKET;
                            debugReason = "cancelled due to restricted bucket";
                        } else {
                            internalStopReason =
                                    JobParameters.INTERNAL_STOP_REASON_CONSTRAINTS_NOT_SATISFIED;
                            debugReason = "cancelled due to unsatisfied constraints";
                        }
                    } else {
                        final JobRestriction restriction = checkIfRestricted(job);
                        if (restriction != null) {
                            internalStopReason = restriction.getInternalReason();
                            debugReason = "restricted due to "
                                    + JobParameters.getInternalReasonCodeDescription(
                                    internalStopReason);
                        } else {
                            internalStopReason = JobParameters.INTERNAL_STOP_REASON_UNKNOWN;
                            debugReason = "couldn't figure out why the job should stop running";
                        }
                    }
                    mConcurrencyManager.stopJobOnServiceContextLocked(job, job.getStopReason(),
                            internalStopReason, debugReason);
                } else if (mPendingJobQueue.remove(job)) {
                    noteJobNonPending(job);
                }
                evaluateControllerStatesLocked(job);
            }
        }

        @GuardedBy("mLock")
        @VisibleForTesting
        void postProcessLocked() {
            if (unbatchedCount > 0
                    || forceBatchedCount >= mConstants.MIN_READY_NON_ACTIVE_JOBS_COUNT) {
                if (DEBUG) {
                    Slog.d(TAG, "maybeQueueReadyJobsForExecutionLocked: Running jobs.");
                }
                noteJobsPending(runnableJobs);
                mPendingJobQueue.addAll(runnableJobs);
            } else {
                if (DEBUG) {
                    Slog.d(TAG, "maybeQueueReadyJobsForExecutionLocked: Not running anything.");
                }
            }

            // Be ready for next time
            reset();
        }

        @VisibleForTesting
        void reset() {
            forceBatchedCount = 0;
            unbatchedCount = 0;
            runnableJobs.clear();
        }
    }

    private final MaybeReadyJobQueueFunctor mMaybeQueueFunctor = new MaybeReadyJobQueueFunctor();

    @GuardedBy("mLock")
    private void maybeQueueReadyJobsForExecutionLocked() {
        mHandler.removeMessages(MSG_CHECK_JOB);
        // This method will evaluate all jobs, so we don't need to keep any messages for a subset
        // of jobs in the queue.
        mHandler.removeMessages(MSG_CHECK_CHANGED_JOB_LIST);
        mChangedJobList.clear();
        if (DEBUG) Slog.d(TAG, "Maybe queuing ready jobs...");

        clearPendingJobQueue();
        stopNonReadyActiveJobsLocked();
        mJobs.forEachJob(mMaybeQueueFunctor);
        mMaybeQueueFunctor.postProcessLocked();
    }

    @GuardedBy("mLock")
    private void checkChangedJobListLocked() {
        mHandler.removeMessages(MSG_CHECK_CHANGED_JOB_LIST);
        if (DEBUG) {
            Slog.d(TAG, "Check changed jobs...");
        }
        if (mChangedJobList.size() == 0) {
            return;
        }

        mChangedJobList.forEach(mMaybeQueueFunctor);
        mMaybeQueueFunctor.postProcessLocked();
        mChangedJobList.clear();
    }

    @GuardedBy("mLock")
    private void updateMediaBackupExemptionLocked(int userId, @Nullable String oldPkg,
            @Nullable String newPkg) {
        final Predicate<JobStatus> shouldProcessJob =
                (job) -> job.getSourceUserId() == userId
                        && (job.getSourcePackageName().equals(oldPkg)
                        || job.getSourcePackageName().equals(newPkg));
        mJobs.forEachJob(shouldProcessJob,
                (job) -> {
                    if (job.updateMediaBackupExemptionStatus()) {
                        mChangedJobList.add(job);
                    }
                });
        mHandler.sendEmptyMessage(MSG_CHECK_CHANGED_JOB_LIST);
    }

    /** Returns true if both the calling and source users for the job are started. */
    @GuardedBy("mLock")
    public boolean areUsersStartedLocked(final JobStatus job) {
        boolean sourceStarted = ArrayUtils.contains(mStartedUsers, job.getSourceUserId());
        if (job.getUserId() == job.getSourceUserId()) {
            return sourceStarted;
        }
        return sourceStarted && ArrayUtils.contains(mStartedUsers, job.getUserId());
    }

    /**
     * Criteria for moving a job into the pending queue:
     *      - It's ready.
     *      - It's not pending.
     *      - It's not already running on a JSC.
     *      - The user that requested the job is running.
     *      - The job's standby bucket has come due to be runnable.
     *      - The component is enabled and runnable.
     */
    @VisibleForTesting
    @GuardedBy("mLock")
    boolean isReadyToBeExecutedLocked(JobStatus job) {
        return isReadyToBeExecutedLocked(job, true);
    }

    @GuardedBy("mLock")
    boolean isReadyToBeExecutedLocked(JobStatus job, boolean rejectActive) {
        final boolean jobReady = job.isReady();

        if (DEBUG) {
            Slog.v(TAG, "isReadyToBeExecutedLocked: " + job.toShortString()
                    + " ready=" + jobReady);
        }

        // This is a condition that is very likely to be false (most jobs that are
        // scheduled are sitting there, not ready yet) and very cheap to check (just
        // a few conditions on data in JobStatus).
        if (!jobReady) {
            if (job.getSourcePackageName().equals("android.jobscheduler.cts.jobtestapp")) {
                Slog.v(TAG, "    NOT READY: " + job);
            }
            return false;
        }

        final boolean jobExists = mJobs.containsJob(job);
        final boolean userStarted = areUsersStartedLocked(job);
        final boolean backingUp = mBackingUpUids.get(job.getSourceUid());

        if (DEBUG) {
            Slog.v(TAG, "isReadyToBeExecutedLocked: " + job.toShortString()
                    + " exists=" + jobExists + " userStarted=" + userStarted
                    + " backingUp=" + backingUp);
        }

        // These are also fairly cheap to check, though they typically will not
        // be conditions we fail.
        if (!jobExists || !userStarted || backingUp) {
            return false;
        }

        if (checkIfRestricted(job) != null) {
            return false;
        }

        final boolean jobPending = mPendingJobQueue.contains(job);
        final boolean jobActive = rejectActive && mConcurrencyManager.isJobRunningLocked(job);

        if (DEBUG) {
            Slog.v(TAG, "isReadyToBeExecutedLocked: " + job.toShortString()
                    + " pending=" + jobPending + " active=" + jobActive);
        }

        // These can be a little more expensive (especially jobActive, since we need to
        // go through the array of all potentially active jobs), so we are doing them
        // later...  but still before checking with the package manager!
        if (jobPending || jobActive) {
            return false;
        }

        // Validate that the defined package+service is still present & viable.
        return isComponentUsable(job);
    }

    private boolean isComponentUsable(@NonNull JobStatus job) {
        final ServiceInfo service = job.serviceInfo;

        if (service == null) {
            if (DEBUG) {
                Slog.v(TAG, "isComponentUsable: " + job.toShortString()
                        + " component not present");
            }
            return false;
        }

        // Everything else checked out so far, so this is the final yes/no check
        final boolean appIsBad = mActivityManagerInternal.isAppBad(
                service.processName, service.applicationInfo.uid);
        if (DEBUG && appIsBad) {
            Slog.i(TAG, "App is bad for " + job.toShortString() + " so not runnable");
        }
        return !appIsBad;
    }

    @VisibleForTesting
    void evaluateControllerStatesLocked(final JobStatus job) {
        for (int c = mControllers.size() - 1; c >= 0; --c) {
            final StateController sc = mControllers.get(c);
            sc.evaluateStateLocked(job);
        }
    }

    /**
     * Returns true if non-job constraint components are in place -- if job.isReady() returns true
     * and this method returns true, then the job is ready to be executed.
     */
    public boolean areComponentsInPlaceLocked(JobStatus job) {
        // This code is very similar to the code in isReadyToBeExecutedLocked --- it uses the same
        // conditions.

        final boolean jobExists = mJobs.containsJob(job);
        final boolean userStarted = areUsersStartedLocked(job);
        final boolean backingUp = mBackingUpUids.get(job.getSourceUid());

        if (DEBUG) {
            Slog.v(TAG, "areComponentsInPlaceLocked: " + job.toShortString()
                    + " exists=" + jobExists + " userStarted=" + userStarted
                    + " backingUp=" + backingUp);
        }

        // These are also fairly cheap to check, though they typically will not
        // be conditions we fail.
        if (!jobExists || !userStarted || backingUp) {
            return false;
        }

        final JobRestriction restriction = checkIfRestricted(job);
        if (restriction != null) {
            if (DEBUG) {
                Slog.v(TAG, "areComponentsInPlaceLocked: " + job.toShortString()
                        + " restricted due to " + restriction.getInternalReason());
            }
            return false;
        }

        // Job pending/active doesn't affect the readiness of a job.

        // The expensive check: validate that the defined package+service is
        // still present & viable.
        return isComponentUsable(job);
    }

    /** Returns the minimum amount of time we should let this job run before timing out. */
    public long getMinJobExecutionGuaranteeMs(JobStatus job) {
        synchronized (mLock) {
            if (job.shouldTreatAsExpeditedJob()) {
                // Don't guarantee RESTRICTED jobs more than 5 minutes.
                return job.getEffectiveStandbyBucket() != RESTRICTED_INDEX
                        ? mConstants.RUNTIME_MIN_EJ_GUARANTEE_MS
                        : Math.min(mConstants.RUNTIME_MIN_EJ_GUARANTEE_MS, 5 * MINUTE_IN_MILLIS);
            } else if (job.getEffectivePriority() >= JobInfo.PRIORITY_HIGH) {
                return mConstants.RUNTIME_MIN_HIGH_PRIORITY_GUARANTEE_MS;
            } else {
                return mConstants.RUNTIME_MIN_GUARANTEE_MS;
            }
        }
    }

    /** Returns the maximum amount of time this job could run for. */
    public long getMaxJobExecutionTimeMs(JobStatus job) {
        synchronized (mLock) {
            return Math.min(mConstants.RUNTIME_FREE_QUOTA_MAX_LIMIT_MS,
                    mConstants.USE_TARE_POLICY
                            ? mTareController.getMaxJobExecutionTimeMsLocked(job)
                            : mQuotaController.getMaxJobExecutionTimeMsLocked(job));
        }
    }

    /**
     * Reconcile jobs in the pending queue against available execution contexts.
     * A controller can force a job into the pending queue even if it's already running, but
     * here is where we decide whether to actually execute it.
     */
    void maybeRunPendingJobsLocked() {
        if (DEBUG) {
            Slog.d(TAG, "pending queue: " + mPendingJobQueue.size() + " jobs.");
        }
        mConcurrencyManager.assignJobsToContextsLocked();
        reportActiveLocked();
    }

    private int adjustJobBias(int curBias, JobStatus job) {
        if (curBias < JobInfo.BIAS_TOP_APP) {
            float factor = mJobPackageTracker.getLoadFactor(job);
            if (factor >= mConstants.HEAVY_USE_FACTOR) {
                curBias += JobInfo.BIAS_ADJ_ALWAYS_RUNNING;
            } else if (factor >= mConstants.MODERATE_USE_FACTOR) {
                curBias += JobInfo.BIAS_ADJ_OFTEN_RUNNING;
            }
        }
        return curBias;
    }

    int evaluateJobBiasLocked(JobStatus job) {
        int bias = job.getBias();
        if (bias >= JobInfo.BIAS_BOUND_FOREGROUND_SERVICE) {
            return adjustJobBias(bias, job);
        }
        int override = mUidBiasOverride.get(job.getSourceUid(), 0);
        if (override != 0) {
            return adjustJobBias(override, job);
        }
        return adjustJobBias(bias, job);
    }

    private final class BatteryStateTracker extends BroadcastReceiver {
        /**
         * Track whether we're "charging", where charging means that we're ready to commit to
         * doing work.
         */
        private boolean mCharging;
        /** Keep track of whether the battery is charged enough that we want to do work. */
        private boolean mBatteryNotLow;
        /** Sequence number of last broadcast. */
        private int mLastBatterySeq = -1;

        private BroadcastReceiver mMonitor;

        BatteryStateTracker() {
        }

        public void startTracking() {
            IntentFilter filter = new IntentFilter();

            // Battery health.
            filter.addAction(Intent.ACTION_BATTERY_LOW);
            filter.addAction(Intent.ACTION_BATTERY_OKAY);
            // Charging/not charging.
            filter.addAction(BatteryManager.ACTION_CHARGING);
            filter.addAction(BatteryManager.ACTION_DISCHARGING);
            getTestableContext().registerReceiver(this, filter);

            // Initialise tracker state.
            BatteryManagerInternal batteryManagerInternal =
                    LocalServices.getService(BatteryManagerInternal.class);
            mBatteryNotLow = !batteryManagerInternal.getBatteryLevelLow();
            mCharging = batteryManagerInternal.isPowered(BatteryManager.BATTERY_PLUGGED_ANY);
        }

        public void setMonitorBatteryLocked(boolean enabled) {
            if (enabled) {
                if (mMonitor == null) {
                    mMonitor = new BroadcastReceiver() {
                        @Override
                        public void onReceive(Context context, Intent intent) {
                            onReceiveInternal(intent);
                        }
                    };
                    IntentFilter filter = new IntentFilter();
                    filter.addAction(Intent.ACTION_BATTERY_CHANGED);
                    getTestableContext().registerReceiver(mMonitor, filter);
                }
            } else if (mMonitor != null) {
                getTestableContext().unregisterReceiver(mMonitor);
                mMonitor = null;
            }
        }

        public boolean isCharging() {
            return mCharging;
        }

        public boolean isBatteryNotLow() {
            return mBatteryNotLow;
        }

        public boolean isMonitoring() {
            return mMonitor != null;
        }

        public int getSeq() {
            return mLastBatterySeq;
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            onReceiveInternal(intent);
        }

        @VisibleForTesting
        public void onReceiveInternal(Intent intent) {
            synchronized (mLock) {
                final String action = intent.getAction();
                boolean changed = false;
                if (Intent.ACTION_BATTERY_LOW.equals(action)) {
                    if (DEBUG) {
                        Slog.d(TAG, "Battery life too low @ " + sElapsedRealtimeClock.millis());
                    }
                    if (mBatteryNotLow) {
                        mBatteryNotLow = false;
                        changed = true;
                    }
                } else if (Intent.ACTION_BATTERY_OKAY.equals(action)) {
                    if (DEBUG) {
                        Slog.d(TAG, "Battery high enough @ " + sElapsedRealtimeClock.millis());
                    }
                    if (!mBatteryNotLow) {
                        mBatteryNotLow = true;
                        changed = true;
                    }
                } else if (BatteryManager.ACTION_CHARGING.equals(action)) {
                    if (DEBUG) {
                        Slog.d(TAG, "Battery charging @ " + sElapsedRealtimeClock.millis());
                    }
                    if (!mCharging) {
                        mCharging = true;
                        changed = true;
                    }
                } else if (BatteryManager.ACTION_DISCHARGING.equals(action)) {
                    if (DEBUG) {
                        Slog.d(TAG, "Battery discharging @ " + sElapsedRealtimeClock.millis());
                    }
                    if (mCharging) {
                        mCharging = false;
                        changed = true;
                    }
                }
                mLastBatterySeq =
                        intent.getIntExtra(BatteryManager.EXTRA_SEQUENCE, mLastBatterySeq);
                if (changed) {
                    for (int c = mControllers.size() - 1; c >= 0; --c) {
                        mControllers.get(c).onBatteryStateChangedLocked();
                    }
                }
            }
        }
    }

    final class LocalService implements JobSchedulerInternal {

        /**
         * Returns a list of all pending jobs. A running job is not considered pending. Periodic
         * jobs are always considered pending.
         */
        @Override
        public List<JobInfo> getSystemScheduledPendingJobs() {
            synchronized (mLock) {
                final List<JobInfo> pendingJobs = new ArrayList<JobInfo>();
                mJobs.forEachJob(Process.SYSTEM_UID, (job) -> {
                    if (job.getJob().isPeriodic() || !mConcurrencyManager.isJobRunningLocked(job)) {
                        pendingJobs.add(job.getJob());
                    }
                });
                return pendingJobs;
            }
        }

        @Override
        public void cancelJobsForUid(int uid, @JobParameters.StopReason int reason,
                int internalReasonCode, String debugReason) {
            JobSchedulerService.this.cancelJobsForUid(uid, reason, internalReasonCode, debugReason);
        }

        @Override
        public void addBackingUpUid(int uid) {
            synchronized (mLock) {
                // No need to actually do anything here, since for a full backup the
                // activity manager will kill the process which will kill the job (and
                // cause it to restart, but now it can't run).
                mBackingUpUids.put(uid, true);
            }
        }

        @Override
        public void removeBackingUpUid(int uid) {
            synchronized (mLock) {
                mBackingUpUids.delete(uid);
                // If there are any jobs for this uid, we need to rebuild the pending list
                // in case they are now ready to run.
                if (mJobs.countJobsForUid(uid) > 0) {
                    mHandler.obtainMessage(MSG_CHECK_JOB).sendToTarget();
                }
            }
        }

        @Override
        public void clearAllBackingUpUids() {
            synchronized (mLock) {
                if (mBackingUpUids.size() > 0) {
                    mBackingUpUids.clear();
                    mHandler.obtainMessage(MSG_CHECK_JOB).sendToTarget();
                }
            }
        }

        @Override
        public String getCloudMediaProviderPackage(int userId) {
            return mCloudMediaProviderPackages.get(userId);
        }

        @Override
        public void reportAppUsage(String packageName, int userId) {
            JobSchedulerService.this.reportAppUsage(packageName, userId);
        }

        @Override
        public JobStorePersistStats getPersistStats() {
            synchronized (mLock) {
                return new JobStorePersistStats(mJobs.getPersistStats());
            }
        }
    }

    /**
     * Tracking of app assignments to standby buckets
     */
    final class StandbyTracker extends AppIdleStateChangeListener {

        // AppIdleStateChangeListener interface for live updates

        @Override
        public void onAppIdleStateChanged(final String packageName, final @UserIdInt int userId,
                boolean idle, int bucket, int reason) {
            // QuotaController handles this now.
        }

        @Override
        public void onUserInteractionStarted(String packageName, int userId) {
            final int uid = mLocalPM.getPackageUid(packageName,
                    PackageManager.MATCH_UNINSTALLED_PACKAGES, userId);
            if (uid < 0) {
                // Quietly ignore; the case is already logged elsewhere
                return;
            }

            long sinceLast = mUsageStats.getTimeSinceLastJobRun(packageName, userId);
            if (sinceLast > 2 * DateUtils.DAY_IN_MILLIS) {
                // Too long ago, not worth logging
                sinceLast = 0L;
            }
            final DeferredJobCounter counter = new DeferredJobCounter();
            synchronized (mLock) {
                mJobs.forEachJobForSourceUid(uid, counter);
            }
            if (counter.numDeferred() > 0 || sinceLast > 0) {
                BatteryStatsInternal mBatteryStatsInternal = LocalServices.getService
                        (BatteryStatsInternal.class);
                mBatteryStatsInternal.noteJobsDeferred(uid, counter.numDeferred(), sinceLast);
                FrameworkStatsLog.write_non_chained(
                        FrameworkStatsLog.DEFERRED_JOB_STATS_REPORTED, uid, null,
                        counter.numDeferred(), sinceLast);
            }
        }
    }

    static class DeferredJobCounter implements Consumer<JobStatus> {
        private int mDeferred = 0;

        public int numDeferred() {
            return mDeferred;
        }

        @Override
        public void accept(JobStatus job) {
            if (job.getWhenStandbyDeferred() > 0) {
                mDeferred++;
            }
        }
    }

    public static int standbyBucketToBucketIndex(int bucket) {
        // Normalize AppStandby constants to indices into our bookkeeping
        if (bucket == UsageStatsManager.STANDBY_BUCKET_NEVER) {
            return NEVER_INDEX;
        } else if (bucket > UsageStatsManager.STANDBY_BUCKET_RARE) {
            return RESTRICTED_INDEX;
        } else if (bucket > UsageStatsManager.STANDBY_BUCKET_FREQUENT) {
            return RARE_INDEX;
        } else if (bucket > UsageStatsManager.STANDBY_BUCKET_WORKING_SET) {
            return FREQUENT_INDEX;
        } else if (bucket > UsageStatsManager.STANDBY_BUCKET_ACTIVE) {
            return WORKING_INDEX;
        } else if (bucket > UsageStatsManager.STANDBY_BUCKET_EXEMPTED) {
            return ACTIVE_INDEX;
        } else {
            return EXEMPTED_INDEX;
        }
    }

    // Static to support external callers
    public static int standbyBucketForPackage(String packageName, int userId, long elapsedNow) {
        UsageStatsManagerInternal usageStats = LocalServices.getService(
                UsageStatsManagerInternal.class);
        int bucket = usageStats != null
                ? usageStats.getAppStandbyBucket(packageName, userId, elapsedNow)
                : 0;

        bucket = standbyBucketToBucketIndex(bucket);

        if (DEBUG_STANDBY) {
            Slog.v(TAG, packageName + "/" + userId + " standby bucket index: " + bucket);
        }
        return bucket;
    }

    private class CloudProviderChangeListener implements
            StorageManagerInternal.CloudProviderChangeListener {

        @Override
        public void onCloudProviderChanged(int userId, @Nullable String authority) {
            final PackageManager pm = getContext()
                    .createContextAsUser(UserHandle.of(userId), 0)
                    .getPackageManager();
            final ProviderInfo pi = pm.resolveContentProvider(
                    authority, PackageManager.ComponentInfoFlags.of(0));
            final String newPkg = (pi == null) ? null : pi.packageName;
            synchronized (mLock) {
                final String oldPkg = mCloudMediaProviderPackages.get(userId);
                if (!Objects.equals(oldPkg, newPkg)) {
                    if (DEBUG) {
                        Slog.d(TAG, "Cloud provider of user " + userId + " changed from " + oldPkg
                                + " to " + newPkg);
                    }
                    mCloudMediaProviderPackages.put(userId, newPkg);
                    SomeArgs args = SomeArgs.obtain();
                    args.argi1 = userId;
                    args.arg1 = oldPkg;
                    args.arg2 = newPkg;
                    mHandler.obtainMessage(MSG_CHECK_MEDIA_EXEMPTION, args).sendToTarget();
                }
            }
        }
    }

    /**
     * Binder stub trampoline implementation
     */
    final class JobSchedulerStub extends IJobScheduler.Stub {
        /**
         * Cache determination of whether a given app can persist jobs
         * key is uid of the calling app; value is undetermined/true/false
         */
        private final SparseArray<Boolean> mPersistCache = new SparseArray<Boolean>();

        // Enforce that only the app itself (or shared uid participant) can schedule a
        // job that runs one of the app's services, as well as verifying that the
        // named service properly requires the BIND_JOB_SERVICE permission
        private void enforceValidJobRequest(int uid, JobInfo job) {
            final PackageManager pm = getContext()
                    .createContextAsUser(UserHandle.getUserHandleForUid(uid), 0)
                    .getPackageManager();
            final ComponentName service = job.getService();
            try {
                ServiceInfo si = pm.getServiceInfo(service,
                        PackageManager.MATCH_DIRECT_BOOT_AWARE
                                | PackageManager.MATCH_DIRECT_BOOT_UNAWARE);
                if (si == null) {
                    throw new IllegalArgumentException("No such service " + service);
                }
                if (si.applicationInfo.uid != uid) {
                    throw new IllegalArgumentException("uid " + uid +
                            " cannot schedule job in " + service.getPackageName());
                }
                if (!JobService.PERMISSION_BIND.equals(si.permission)) {
                    throw new IllegalArgumentException("Scheduled service " + service
                            + " does not require android.permission.BIND_JOB_SERVICE permission");
                }
            } catch (NameNotFoundException e) {
                throw new IllegalArgumentException(
                        "Tried to schedule job for non-existent component: " + service);
            }
        }

        private boolean canPersistJobs(int pid, int uid) {
            // If we get this far we're good to go; all we need to do now is check
            // whether the app is allowed to persist its scheduled work.
            final boolean canPersist;
            synchronized (mPersistCache) {
                Boolean cached = mPersistCache.get(uid);
                if (cached != null) {
                    canPersist = cached.booleanValue();
                } else {
                    // Persisting jobs is tantamount to running at boot, so we permit
                    // it when the app has declared that it uses the RECEIVE_BOOT_COMPLETED
                    // permission
                    int result = getContext().checkPermission(
                            android.Manifest.permission.RECEIVE_BOOT_COMPLETED, pid, uid);
                    canPersist = (result == PackageManager.PERMISSION_GRANTED);
                    mPersistCache.put(uid, canPersist);
                }
            }
            return canPersist;
        }

        private void validateJobFlags(JobInfo job, int callingUid) {
            job.enforceValidity(
                    CompatChanges.isChangeEnabled(
                            JobInfo.DISALLOW_DEADLINES_FOR_PREFETCH_JOBS, callingUid));
            if ((job.getFlags() & JobInfo.FLAG_WILL_BE_FOREGROUND) != 0) {
                getContext().enforceCallingOrSelfPermission(
                        android.Manifest.permission.CONNECTIVITY_INTERNAL, TAG);
            }
            if ((job.getFlags() & JobInfo.FLAG_EXEMPT_FROM_APP_STANDBY) != 0) {
                if (callingUid != Process.SYSTEM_UID) {
                    throw new SecurityException("Job has invalid flags");
                }
                if (job.isPeriodic()) {
                    Slog.wtf(TAG, "Periodic jobs mustn't have"
                            + " FLAG_EXEMPT_FROM_APP_STANDBY. Job=" + job);
                }
            }
        }

        // IJobScheduler implementation
        @Override
        public int schedule(JobInfo job) throws RemoteException {
            if (DEBUG) {
                Slog.d(TAG, "Scheduling job: " + job.toString());
            }
            final int pid = Binder.getCallingPid();
            final int uid = Binder.getCallingUid();
            final int userId = UserHandle.getUserId(uid);

            enforceValidJobRequest(uid, job);
            if (job.isPersisted()) {
                if (!canPersistJobs(pid, uid)) {
                    throw new IllegalArgumentException("Error: requested job be persisted without"
                            + " holding RECEIVE_BOOT_COMPLETED permission.");
                }
            }

            validateJobFlags(job, uid);

            final long ident = Binder.clearCallingIdentity();
            try {
                return JobSchedulerService.this.scheduleAsPackage(job, null, uid, null, userId,
                        null);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        // IJobScheduler implementation
        @Override
        public int enqueue(JobInfo job, JobWorkItem work) throws RemoteException {
            if (DEBUG) {
                Slog.d(TAG, "Enqueueing job: " + job.toString() + " work: " + work);
            }
            final int uid = Binder.getCallingUid();
            final int userId = UserHandle.getUserId(uid);

            enforceValidJobRequest(uid, job);
            if (job.isPersisted()) {
                throw new IllegalArgumentException("Can't enqueue work for persisted jobs");
            }
            if (work == null) {
                throw new NullPointerException("work is null");
            }

            work.enforceValidity();
            validateJobFlags(job, uid);

            final long ident = Binder.clearCallingIdentity();
            try {
                return JobSchedulerService.this.scheduleAsPackage(job, work, uid, null, userId,
                        null);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override
        public int scheduleAsPackage(JobInfo job, String packageName, int userId, String tag)
                throws RemoteException {
            final int callerUid = Binder.getCallingUid();
            if (DEBUG) {
                Slog.d(TAG, "Caller uid " + callerUid + " scheduling job: " + job.toString()
                        + " on behalf of " + packageName + "/");
            }

            if (packageName == null) {
                throw new NullPointerException("Must specify a package for scheduleAsPackage()");
            }

            int mayScheduleForOthers = getContext().checkCallingOrSelfPermission(
                    android.Manifest.permission.UPDATE_DEVICE_STATS);
            if (mayScheduleForOthers != PackageManager.PERMISSION_GRANTED) {
                throw new SecurityException("Caller uid " + callerUid
                        + " not permitted to schedule jobs for other apps");
            }

            validateJobFlags(job, callerUid);

            final long ident = Binder.clearCallingIdentity();
            try {
                return JobSchedulerService.this.scheduleAsPackage(job, null, callerUid,
                        packageName, userId, tag);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override
        public ParceledListSlice<JobInfo> getAllPendingJobs() throws RemoteException {
            final int uid = Binder.getCallingUid();

            final long ident = Binder.clearCallingIdentity();
            try {
                return new ParceledListSlice<>(JobSchedulerService.this.getPendingJobs(uid));
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override
        public JobInfo getPendingJob(int jobId) throws RemoteException {
            final int uid = Binder.getCallingUid();

            final long ident = Binder.clearCallingIdentity();
            try {
                return JobSchedulerService.this.getPendingJob(uid, jobId);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override
        public void cancelAll() throws RemoteException {
            final int uid = Binder.getCallingUid();
            final long ident = Binder.clearCallingIdentity();
            try {
                JobSchedulerService.this.cancelJobsForUid(uid,
                        JobParameters.STOP_REASON_CANCELLED_BY_APP,
                        JobParameters.INTERNAL_STOP_REASON_CANCELED,
                        "cancelAll() called by app, callingUid=" + uid);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override
        public void cancel(int jobId) throws RemoteException {
            final int uid = Binder.getCallingUid();

            final long ident = Binder.clearCallingIdentity();
            try {
                JobSchedulerService.this.cancelJob(uid, jobId, uid,
                        JobParameters.STOP_REASON_CANCELLED_BY_APP);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        /**
         * "dumpsys" infrastructure
         */
        @Override
        public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            if (!DumpUtils.checkDumpAndUsageStatsPermission(getContext(), TAG, pw)) return;

            int filterUid = -1;
            boolean proto = false;
            if (!ArrayUtils.isEmpty(args)) {
                int opti = 0;
                while (opti < args.length) {
                    String arg = args[opti];
                    if ("-h".equals(arg)) {
                        dumpHelp(pw);
                        return;
                    } else if ("-a".equals(arg)) {
                        // Ignore, we always dump all.
                    } else if ("--proto".equals(arg)) {
                        proto = true;
                    } else if (arg.length() > 0 && arg.charAt(0) == '-') {
                        pw.println("Unknown option: " + arg);
                        return;
                    } else {
                        break;
                    }
                    opti++;
                }
                if (opti < args.length) {
                    String pkg = args[opti];
                    try {
                        filterUid = getContext().getPackageManager().getPackageUid(pkg,
                                PackageManager.MATCH_ANY_USER);
                    } catch (NameNotFoundException ignored) {
                        pw.println("Invalid package: " + pkg);
                        return;
                    }
                }
            }

            final long identityToken = Binder.clearCallingIdentity();
            try {
                if (proto) {
                    JobSchedulerService.this.dumpInternalProto(fd, filterUid);
                } else {
                    JobSchedulerService.this.dumpInternal(new IndentingPrintWriter(pw, "  "),
                            filterUid);
                }
            } finally {
                Binder.restoreCallingIdentity(identityToken);
            }
        }

        @Override
        public int handleShellCommand(@NonNull ParcelFileDescriptor in,
                @NonNull ParcelFileDescriptor out, @NonNull ParcelFileDescriptor err,
                @NonNull String[] args) {
            return (new JobSchedulerShellCommand(JobSchedulerService.this)).exec(
                    this, in.getFileDescriptor(), out.getFileDescriptor(), err.getFileDescriptor(),
                    args);
        }

        /**
         * <b>For internal system user only!</b>
         * Returns a list of all currently-executing jobs.
         */
        @Override
        public List<JobInfo> getStartedJobs() {
            final int uid = Binder.getCallingUid();
            if (uid != Process.SYSTEM_UID) {
                throw new SecurityException("getStartedJobs() is system internal use only.");
            }

            final ArrayList<JobInfo> runningJobs;

            synchronized (mLock) {
                final ArraySet<JobStatus> runningJobStatuses =
                        mConcurrencyManager.getRunningJobsLocked();
                runningJobs = new ArrayList<>(runningJobStatuses.size());
                for (int i = runningJobStatuses.size() - 1; i >= 0; --i) {
                    final JobStatus job = runningJobStatuses.valueAt(i);
                    if (job != null) {
                        runningJobs.add(job.getJob());
                    }
                }
            }

            return runningJobs;
        }

        /**
         * <b>For internal system user only!</b>
         * Returns a snapshot of the state of all jobs known to the system.
         *
         * <p class="note">This is a slow operation, so it should be called sparingly.
         */
        @Override
        public ParceledListSlice<JobSnapshot> getAllJobSnapshots() {
            final int uid = Binder.getCallingUid();
            if (uid != Process.SYSTEM_UID) {
                throw new SecurityException("getAllJobSnapshots() is system internal use only.");
            }
            synchronized (mLock) {
                final ArrayList<JobSnapshot> snapshots = new ArrayList<>(mJobs.size());
                mJobs.forEachJob((job) -> snapshots.add(
                        new JobSnapshot(job.getJob(), job.getSatisfiedConstraintFlags(),
                                isReadyToBeExecutedLocked(job))));
                return new ParceledListSlice<>(snapshots);
            }
        }
    }

    // Shell command infrastructure: run the given job immediately
    int executeRunCommand(String pkgName, int userId, int jobId, boolean satisfied, boolean force) {
        Slog.d(TAG, "executeRunCommand(): " + pkgName + "/" + userId
                + " " + jobId + " s=" + satisfied + " f=" + force);

        try {
            final int uid = AppGlobals.getPackageManager().getPackageUid(pkgName, 0,
                    userId != UserHandle.USER_ALL ? userId : UserHandle.USER_SYSTEM);
            if (uid < 0) {
                return JobSchedulerShellCommand.CMD_ERR_NO_PACKAGE;
            }

            synchronized (mLock) {
                final JobStatus js = mJobs.getJobByUidAndJobId(uid, jobId);
                if (js == null) {
                    return JobSchedulerShellCommand.CMD_ERR_NO_JOB;
                }

                js.overrideState = (force) ? JobStatus.OVERRIDE_FULL
                        : (satisfied ? JobStatus.OVERRIDE_SORTING : JobStatus.OVERRIDE_SOFT);

                // Re-evaluate constraints after the override is set in case one of the overridden
                // constraints was preventing another constraint from thinking it needed to update.
                for (int c = mControllers.size() - 1; c >= 0; --c) {
                    mControllers.get(c).reevaluateStateLocked(uid);
                }

                if (!js.isConstraintsSatisfied()) {
                    js.overrideState = JobStatus.OVERRIDE_NONE;
                    return JobSchedulerShellCommand.CMD_ERR_CONSTRAINTS;
                }

                queueReadyJobsForExecutionLocked();
                maybeRunPendingJobsLocked();
            }
        } catch (RemoteException e) {
            // can't happen
        }
        return 0;
    }

    // Shell command infrastructure: immediately timeout currently executing jobs
    int executeTimeoutCommand(PrintWriter pw, String pkgName, int userId,
            boolean hasJobId, int jobId) {
        if (DEBUG) {
            Slog.v(TAG, "executeTimeoutCommand(): " + pkgName + "/" + userId + " " + jobId);
        }

        synchronized (mLock) {
            final boolean foundSome = mConcurrencyManager.executeTimeoutCommandLocked(pw,
                    pkgName, userId, hasJobId, jobId);
            if (!foundSome) {
                pw.println("No matching executing jobs found.");
            }
        }
        return 0;
    }

    // Shell command infrastructure: cancel a scheduled job
    int executeCancelCommand(PrintWriter pw, String pkgName, int userId,
            boolean hasJobId, int jobId) {
        if (DEBUG) {
            Slog.v(TAG, "executeCancelCommand(): " + pkgName + "/" + userId + " " + jobId);
        }

        int pkgUid = -1;
        try {
            IPackageManager pm = AppGlobals.getPackageManager();
            pkgUid = pm.getPackageUid(pkgName, 0, userId);
        } catch (RemoteException e) { /* can't happen */ }

        if (pkgUid < 0) {
            pw.println("Package " + pkgName + " not found.");
            return JobSchedulerShellCommand.CMD_ERR_NO_PACKAGE;
        }

        if (!hasJobId) {
            pw.println("Canceling all jobs for " + pkgName + " in user " + userId);
            if (!cancelJobsForUid(pkgUid, JobParameters.STOP_REASON_USER,
                    JobParameters.INTERNAL_STOP_REASON_CANCELED,
                    "cancel shell command for package")) {
                pw.println("No matching jobs found.");
            }
        } else {
            pw.println("Canceling job " + pkgName + "/#" + jobId + " in user " + userId);
            if (!cancelJob(pkgUid, jobId, Process.SHELL_UID, JobParameters.STOP_REASON_USER)) {
                pw.println("No matching job found.");
            }
        }

        return 0;
    }

    void setMonitorBattery(boolean enabled) {
        synchronized (mLock) {
            mBatteryStateTracker.setMonitorBatteryLocked(enabled);
        }
    }

    int getBatterySeq() {
        synchronized (mLock) {
            return mBatteryStateTracker.getSeq();
        }
    }

    /** Return {@code true} if the device is currently charging. */
    public boolean isBatteryCharging() {
        synchronized (mLock) {
            return mBatteryStateTracker.isCharging();
        }
    }

    /** Return {@code true} if the battery is not low. */
    public boolean isBatteryNotLow() {
        synchronized (mLock) {
            return mBatteryStateTracker.isBatteryNotLow();
        }
    }

    int getStorageSeq() {
        synchronized (mLock) {
            return mStorageController != null ? mStorageController.getTracker().getSeq() : -1;
        }
    }

    boolean getStorageNotLow() {
        synchronized (mLock) {
            return mStorageController != null
                    ? mStorageController.getTracker().isStorageNotLow() : false;
        }
    }

    // Shell command infrastructure
    int getJobState(PrintWriter pw, String pkgName, int userId, int jobId) {
        try {
            final int uid = AppGlobals.getPackageManager().getPackageUid(pkgName, 0,
                    userId != UserHandle.USER_ALL ? userId : UserHandle.USER_SYSTEM);
            if (uid < 0) {
                pw.print("unknown("); pw.print(pkgName); pw.println(")");
                return JobSchedulerShellCommand.CMD_ERR_NO_PACKAGE;
            }

            synchronized (mLock) {
                final JobStatus js = mJobs.getJobByUidAndJobId(uid, jobId);
                if (DEBUG) Slog.d(TAG, "get-job-state " + uid + "/" + jobId + ": " + js);
                if (js == null) {
                    pw.print("unknown("); UserHandle.formatUid(pw, uid);
                    pw.print("/jid"); pw.print(jobId); pw.println(")");
                    return JobSchedulerShellCommand.CMD_ERR_NO_JOB;
                }

                boolean printed = false;
                if (mPendingJobQueue.contains(js)) {
                    pw.print("pending");
                    printed = true;
                }
                if (mConcurrencyManager.isJobRunningLocked(js)) {
                    if (printed) {
                        pw.print(" ");
                    }
                    printed = true;
                    pw.println("active");
                }
                if (!ArrayUtils.contains(mStartedUsers, js.getUserId())) {
                    if (printed) {
                        pw.print(" ");
                    }
                    printed = true;
                    pw.println("user-stopped");
                }
                if (!ArrayUtils.contains(mStartedUsers, js.getSourceUserId())) {
                    if (printed) {
                        pw.print(" ");
                    }
                    printed = true;
                    pw.println("source-user-stopped");
                }
                if (mBackingUpUids.get(js.getSourceUid())) {
                    if (printed) {
                        pw.print(" ");
                    }
                    printed = true;
                    pw.println("backing-up");
                }
                boolean componentPresent = false;
                try {
                    componentPresent = (AppGlobals.getPackageManager().getServiceInfo(
                            js.getServiceComponent(),
                            PackageManager.MATCH_DIRECT_BOOT_AUTO,
                            js.getUserId()) != null);
                } catch (RemoteException e) {
                }
                if (!componentPresent) {
                    if (printed) {
                        pw.print(" ");
                    }
                    printed = true;
                    pw.println("no-component");
                }
                if (js.isReady()) {
                    if (printed) {
                        pw.print(" ");
                    }
                    printed = true;
                    pw.println("ready");
                }
                if (!printed) {
                    pw.print("waiting");
                }
                pw.println();
            }
        } catch (RemoteException e) {
            // can't happen
        }
        return 0;
    }

    void resetExecutionQuota(@NonNull String pkgName, int userId) {
        synchronized (mLock) {
            mQuotaController.clearAppStatsLocked(userId, pkgName);
        }
    }

    void resetScheduleQuota() {
        mQuotaTracker.clear();
    }

    void triggerDockState(boolean idleState) {
        final Intent dockIntent;
        if (idleState) {
            dockIntent = new Intent(Intent.ACTION_DOCK_IDLE);
        } else {
            dockIntent = new Intent(Intent.ACTION_DOCK_ACTIVE);
        }
        dockIntent.setPackage("android");
        dockIntent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY | Intent.FLAG_RECEIVER_FOREGROUND);
        getContext().sendBroadcastAsUser(dockIntent, UserHandle.ALL);
    }

    static void dumpHelp(PrintWriter pw) {
        pw.println("Job Scheduler (jobscheduler) dump options:");
        pw.println("  [-h] [package] ...");
        pw.println("    -h: print this help");
        pw.println("  [package] is an optional package name to limit the output to.");
    }

    /** Sort jobs by caller UID, then by Job ID. */
    private static void sortJobs(List<JobStatus> jobs) {
        Collections.sort(jobs, new Comparator<JobStatus>() {
            @Override
            public int compare(JobStatus o1, JobStatus o2) {
                int uid1 = o1.getUid();
                int uid2 = o2.getUid();
                int id1 = o1.getJobId();
                int id2 = o2.getJobId();
                if (uid1 != uid2) {
                    return uid1 < uid2 ? -1 : 1;
                }
                return id1 < id2 ? -1 : (id1 > id2 ? 1 : 0);
            }
        });
    }

    @NeverCompile // Avoid size overhead of debugging code.
    void dumpInternal(final IndentingPrintWriter pw, int filterUid) {
        final int filterAppId = UserHandle.getAppId(filterUid);
        final long now = sSystemClock.millis();
        final long nowElapsed = sElapsedRealtimeClock.millis();
        final long nowUptime = sUptimeMillisClock.millis();

        final Predicate<JobStatus> predicate = (js) -> {
            return filterAppId == -1 || UserHandle.getAppId(js.getUid()) == filterAppId
                    || UserHandle.getAppId(js.getSourceUid()) == filterAppId;
        };
        synchronized (mLock) {
            mConstants.dump(pw);
            for (StateController controller : mControllers) {
                pw.increaseIndent();
                controller.dumpConstants(pw);
                pw.decreaseIndent();
            }
            pw.println();

            for (int i = mJobRestrictions.size() - 1; i >= 0; i--) {
                mJobRestrictions.get(i).dumpConstants(pw);
            }
            pw.println();

            mQuotaTracker.dump(pw);
            pw.println();

            pw.print("Battery charging: ");
            pw.println(mBatteryStateTracker.isCharging());
            pw.print("Battery not low: ");
            pw.println(mBatteryStateTracker.isBatteryNotLow());
            if (mBatteryStateTracker.isMonitoring()) {
                pw.print("MONITORING: seq=");
                pw.println(mBatteryStateTracker.getSeq());
            }
            pw.println();

            pw.println("Started users: " + Arrays.toString(mStartedUsers));
            pw.println();

            pw.print("Media Cloud Providers: ");
            pw.println(mCloudMediaProviderPackages);
            pw.println();

            pw.print("Registered ");
            pw.print(mJobs.size());
            pw.println(" jobs:");
            pw.increaseIndent();
            boolean jobPrinted = false;
            if (mJobs.size() > 0) {
                final List<JobStatus> jobs = mJobs.mJobSet.getAllJobs();
                sortJobs(jobs);
                for (JobStatus job : jobs) {
                    // Skip printing details if the caller requested a filter
                    if (!predicate.test(job)) {
                        continue;
                    }
                    jobPrinted = true;

                    pw.print("JOB #"); job.printUniqueId(pw); pw.print(": ");
                    pw.println(job.toShortStringExceptUniqueId());

                    pw.increaseIndent();
                    job.dump(pw, true, nowElapsed);

                    pw.print("Restricted due to:");
                    final boolean isRestricted = checkIfRestricted(job) != null;
                    if (isRestricted) {
                        for (int i = mJobRestrictions.size() - 1; i >= 0; i--) {
                            final JobRestriction restriction = mJobRestrictions.get(i);
                            if (restriction.isJobRestricted(job)) {
                                final int reason = restriction.getInternalReason();
                                pw.print(" ");
                                pw.print(JobParameters.getInternalReasonCodeDescription(reason));
                            }
                        }
                    } else {
                        pw.print(" none");
                    }
                    pw.println(".");

                    pw.print("Ready: ");
                    pw.print(isReadyToBeExecutedLocked(job));
                    pw.print(" (job=");
                    pw.print(job.isReady());
                    pw.print(" user=");
                    pw.print(areUsersStartedLocked(job));
                    pw.print(" !restricted=");
                    pw.print(!isRestricted);
                    pw.print(" !pending=");
                    pw.print(!mPendingJobQueue.contains(job));
                    pw.print(" !active=");
                    pw.print(!mConcurrencyManager.isJobRunningLocked(job));
                    pw.print(" !backingup=");
                    pw.print(!(mBackingUpUids.get(job.getSourceUid())));
                    pw.print(" comp=");
                    pw.print(isComponentUsable(job));
                    pw.println(")");

                    pw.decreaseIndent();
                }
            }
            if (!jobPrinted) {
                pw.println("None.");
            }
            pw.decreaseIndent();

            for (int i = 0; i < mControllers.size(); i++) {
                pw.println();
                pw.println(mControllers.get(i).getClass().getSimpleName() + ":");
                pw.increaseIndent();
                mControllers.get(i).dumpControllerStateLocked(pw, predicate);
                pw.decreaseIndent();
            }

            boolean overridePrinted = false;
            for (int i = 0; i < mUidBiasOverride.size(); i++) {
                int uid = mUidBiasOverride.keyAt(i);
                if (filterAppId == -1 || filterAppId == UserHandle.getAppId(uid)) {
                    if (!overridePrinted) {
                        overridePrinted = true;
                        pw.println();
                        pw.println("Uid bias overrides:");
                        pw.increaseIndent();
                    }
                    pw.print(UserHandle.formatUid(uid));
                    pw.print(": "); pw.println(mUidBiasOverride.valueAt(i));
                }
            }
            if (overridePrinted) {
                pw.decreaseIndent();
            }

            boolean uidMapPrinted = false;
            for (int i = 0; i < mUidToPackageCache.size(); ++i) {
                final int uid = mUidToPackageCache.keyAt(i);
                if (filterUid != -1 && filterUid != uid) {
                    continue;
                }
                if (!uidMapPrinted) {
                    uidMapPrinted = true;
                    pw.println();
                    pw.println("Cached UID->package map:");
                    pw.increaseIndent();
                }
                pw.print(uid);
                pw.print(": ");
                pw.println(mUidToPackageCache.get(uid));
            }
            if (uidMapPrinted) {
                pw.decreaseIndent();
            }

            boolean backingPrinted = false;
            for (int i = 0; i < mBackingUpUids.size(); i++) {
                int uid = mBackingUpUids.keyAt(i);
                if (filterAppId == -1 || filterAppId == UserHandle.getAppId(uid)) {
                    if (!backingPrinted) {
                        pw.println();
                        pw.println("Backing up uids:");
                        pw.increaseIndent();
                        backingPrinted = true;
                    } else {
                        pw.print(", ");
                    }
                    pw.print(UserHandle.formatUid(uid));
                }
            }
            if (backingPrinted) {
                pw.decreaseIndent();
                pw.println();
            }

            pw.println();
            mJobPackageTracker.dump(pw, filterAppId);
            pw.println();
            if (mJobPackageTracker.dumpHistory(pw, filterAppId)) {
                pw.println();
            }

            boolean pendingPrinted = false;
            pw.println("Pending queue:");
            pw.increaseIndent();
            JobStatus job;
            int pendingIdx = 0;
            mPendingJobQueue.resetIterator();
            while ((job = mPendingJobQueue.next()) != null) {
                pendingIdx++;
                if (!predicate.test(job)) {
                    continue;
                }
                if (!pendingPrinted) {
                    pendingPrinted = true;
                }

                pw.print("Pending #"); pw.print(pendingIdx); pw.print(": ");
                pw.println(job.toShortString());

                pw.increaseIndent();
                job.dump(pw, false, nowElapsed);
                int bias = evaluateJobBiasLocked(job);
                pw.print("Evaluated bias: ");
                pw.println(JobInfo.getBiasString(bias));

                pw.print("Tag: "); pw.println(job.getTag());
                pw.print("Enq: ");
                TimeUtils.formatDuration(job.madePending - nowUptime, pw);
                pw.decreaseIndent();
                pw.println();
            }
            if (!pendingPrinted) {
                pw.println("None");
            }
            pw.decreaseIndent();

            pw.println();
            mConcurrencyManager.dumpContextInfoLocked(pw, predicate, nowElapsed, nowUptime);

            pw.println();
            boolean recentPrinted = false;
            pw.println("Recently completed jobs:");
            pw.increaseIndent();
            for (int r = 1; r <= NUM_COMPLETED_JOB_HISTORY; ++r) {
                // Print most recent first
                final int idx = (mLastCompletedJobIndex + NUM_COMPLETED_JOB_HISTORY - r)
                        % NUM_COMPLETED_JOB_HISTORY;
                job = mLastCompletedJobs[idx];
                if (job != null) {
                    if (!predicate.test(job)) {
                        continue;
                    }
                    recentPrinted = true;
                    TimeUtils.formatDuration(mLastCompletedJobTimeElapsed[idx], nowElapsed, pw);
                    pw.println();
                    // Double indent for readability
                    pw.increaseIndent();
                    pw.increaseIndent();
                    pw.println(job.toShortString());
                    job.dump(pw, true, nowElapsed);
                    pw.decreaseIndent();
                    pw.decreaseIndent();
                }
            }
            if (!recentPrinted) {
                pw.println("None");
            }
            pw.decreaseIndent();
            pw.println();

            if (filterUid == -1) {
                pw.println();
                pw.print("mReadyToRock="); pw.println(mReadyToRock);
                pw.print("mReportedActive="); pw.println(mReportedActive);
            }
            pw.println();

            mConcurrencyManager.dumpLocked(pw, now, nowElapsed);

            pw.println();
            pw.print("PersistStats: ");
            pw.println(mJobs.getPersistStats());
        }
        pw.println();
    }

    void dumpInternalProto(final FileDescriptor fd, int filterUid) {
        ProtoOutputStream proto = new ProtoOutputStream(fd);
        final int filterAppId = UserHandle.getAppId(filterUid);
        final long now = sSystemClock.millis();
        final long nowElapsed = sElapsedRealtimeClock.millis();
        final long nowUptime = sUptimeMillisClock.millis();
        final Predicate<JobStatus> predicate = (js) -> {
            return filterAppId == -1 || UserHandle.getAppId(js.getUid()) == filterAppId
                    || UserHandle.getAppId(js.getSourceUid()) == filterAppId;
        };

        synchronized (mLock) {
            final long settingsToken = proto.start(JobSchedulerServiceDumpProto.SETTINGS);
            mConstants.dump(proto);
            for (StateController controller : mControllers) {
                controller.dumpConstants(proto);
            }
            proto.end(settingsToken);

            for (int i = mJobRestrictions.size() - 1; i >= 0; i--) {
                mJobRestrictions.get(i).dumpConstants(proto);
            }

            for (int u : mStartedUsers) {
                proto.write(JobSchedulerServiceDumpProto.STARTED_USERS, u);
            }

            mQuotaTracker.dump(proto, JobSchedulerServiceDumpProto.QUOTA_TRACKER);

            if (mJobs.size() > 0) {
                final List<JobStatus> jobs = mJobs.mJobSet.getAllJobs();
                sortJobs(jobs);
                for (JobStatus job : jobs) {
                    final long rjToken = proto.start(JobSchedulerServiceDumpProto.REGISTERED_JOBS);
                    job.writeToShortProto(proto, JobSchedulerServiceDumpProto.RegisteredJob.INFO);

                    // Skip printing details if the caller requested a filter
                    if (!predicate.test(job)) {
                        continue;
                    }

                    job.dump(proto,
                            JobSchedulerServiceDumpProto.RegisteredJob.DUMP, true, nowElapsed);

                    proto.write(
                            JobSchedulerServiceDumpProto.RegisteredJob.IS_JOB_READY_TO_BE_EXECUTED,
                            isReadyToBeExecutedLocked(job));
                    proto.write(JobSchedulerServiceDumpProto.RegisteredJob.IS_JOB_READY,
                            job.isReady());
                    proto.write(JobSchedulerServiceDumpProto.RegisteredJob.ARE_USERS_STARTED,
                            areUsersStartedLocked(job));
                    proto.write(
                            JobSchedulerServiceDumpProto.RegisteredJob.IS_JOB_RESTRICTED,
                            checkIfRestricted(job) != null);
                    proto.write(JobSchedulerServiceDumpProto.RegisteredJob.IS_JOB_PENDING,
                            mPendingJobQueue.contains(job));
                    proto.write(JobSchedulerServiceDumpProto.RegisteredJob.IS_JOB_CURRENTLY_ACTIVE,
                            mConcurrencyManager.isJobRunningLocked(job));
                    proto.write(JobSchedulerServiceDumpProto.RegisteredJob.IS_UID_BACKING_UP,
                            mBackingUpUids.get(job.getSourceUid()));
                    proto.write(JobSchedulerServiceDumpProto.RegisteredJob.IS_COMPONENT_USABLE,
                            isComponentUsable(job));

                    for (JobRestriction restriction : mJobRestrictions) {
                        final long restrictionsToken = proto.start(
                                JobSchedulerServiceDumpProto.RegisteredJob.RESTRICTIONS);
                        proto.write(JobSchedulerServiceDumpProto.JobRestriction.REASON,
                                restriction.getInternalReason());
                        proto.write(JobSchedulerServiceDumpProto.JobRestriction.IS_RESTRICTING,
                                restriction.isJobRestricted(job));
                        proto.end(restrictionsToken);
                    }

                    proto.end(rjToken);
                }
            }
            for (StateController controller : mControllers) {
                controller.dumpControllerStateLocked(
                        proto, JobSchedulerServiceDumpProto.CONTROLLERS, predicate);
            }
            for (int i = 0; i < mUidBiasOverride.size(); i++) {
                int uid = mUidBiasOverride.keyAt(i);
                if (filterAppId == -1 || filterAppId == UserHandle.getAppId(uid)) {
                    long pToken = proto.start(JobSchedulerServiceDumpProto.PRIORITY_OVERRIDES);
                    proto.write(JobSchedulerServiceDumpProto.PriorityOverride.UID, uid);
                    proto.write(JobSchedulerServiceDumpProto.PriorityOverride.OVERRIDE_VALUE,
                            mUidBiasOverride.valueAt(i));
                    proto.end(pToken);
                }
            }
            for (int i = 0; i < mBackingUpUids.size(); i++) {
                int uid = mBackingUpUids.keyAt(i);
                if (filterAppId == -1 || filterAppId == UserHandle.getAppId(uid)) {
                    proto.write(JobSchedulerServiceDumpProto.BACKING_UP_UIDS, uid);
                }
            }

            mJobPackageTracker.dump(proto, JobSchedulerServiceDumpProto.PACKAGE_TRACKER,
                    filterAppId);
            mJobPackageTracker.dumpHistory(proto, JobSchedulerServiceDumpProto.HISTORY,
                    filterAppId);

            JobStatus job;
            mPendingJobQueue.resetIterator();
            while ((job = mPendingJobQueue.next()) != null) {
                final long pjToken = proto.start(JobSchedulerServiceDumpProto.PENDING_JOBS);

                job.writeToShortProto(proto, PendingJob.INFO);
                job.dump(proto, PendingJob.DUMP, false, nowElapsed);
                proto.write(PendingJob.EVALUATED_PRIORITY, evaluateJobBiasLocked(job));
                proto.write(PendingJob.PENDING_DURATION_MS, nowUptime - job.madePending);

                proto.end(pjToken);
            }
            if (filterUid == -1) {
                proto.write(JobSchedulerServiceDumpProto.IS_READY_TO_ROCK, mReadyToRock);
                proto.write(JobSchedulerServiceDumpProto.REPORTED_ACTIVE, mReportedActive);
            }
            mConcurrencyManager.dumpProtoLocked(proto,
                    JobSchedulerServiceDumpProto.CONCURRENCY_MANAGER, now, nowElapsed);

            mJobs.getPersistStats().dumpDebug(proto, JobSchedulerServiceDumpProto.PERSIST_STATS);
        }

        proto.flush();
    }
}
