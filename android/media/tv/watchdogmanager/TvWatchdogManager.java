/*
 * Copyright (C) 2025 The Android Open Source Project
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

package android.media.tv.watchdogmanager;

import android.annotation.CallbackExecutor;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.media.tv.flags.Flags;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Slog;
import android.util.SparseIntArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.Preconditions;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * @hide TvWatchdogManager enables applications to track and manage system resource usage.
 *     <p>It allows applications to collect latest system resource overuse statistics, add listener
 *     for resource overuse notifications, and update resource overuse configurations.
 *     <p>TODO: use genrules for this class via CarWatchdogManager (b/428257040)
 */
public final class TvWatchdogManager {
    private static final String TAG = TvWatchdogManager.class.getSimpleName();
    private static final boolean DEBUG = false; // STOPSHIP if true

    /** Timeout for critical services that need to be responsive in 3000 milliseconds. */
    public static final int TIMEOUT_CRITICAL = 0;

    /** Timeout for moderate services that need to be responsive in 5000 milliseconds. */
    public static final int TIMEOUT_MODERATE = 1;

    /** Timeout for normal services that need to be responsive in 10000 milliseconds. */
    public static final int TIMEOUT_NORMAL = 2;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef(
            prefix = "TIMEOUT_",
            value = {
                TIMEOUT_CRITICAL,
                TIMEOUT_MODERATE,
                TIMEOUT_NORMAL,
            })
    @Target({ElementType.TYPE_USE})
    public @interface TimeoutLengthEnum {}

    private final ITvWatchdogService mService;
    private final IResourceOveruseListenerImpl mResourceOveruseListenerImpl;
    private final IResourceOveruseListenerImpl mResourceOveruseListenerForSystemImpl;

    private final Object mLock = new Object();

    @GuardedBy("mLock")
    private final List<ResourceOveruseListenerInfo> mResourceOveruseListenerInfos;

    @GuardedBy("mLock")
    private final List<ResourceOveruseListenerInfo> mResourceOveruseListenerForSystemInfos;

    public TvWatchdogManager(ITvWatchdogService service) {
        mService = service;
        mResourceOveruseListenerImpl =
                new IResourceOveruseListenerImpl(this, /* isSystem= */ false);
        mResourceOveruseListenerForSystemImpl =
                new IResourceOveruseListenerImpl(this, /* isSystem= */ true);
        mResourceOveruseListenerInfos = new ArrayList<>();
        mResourceOveruseListenerForSystemInfos = new ArrayList<>();
    }

    @IntDef(
            flag = false,
            prefix = {"STATS_PERIOD_"},
            value = {
                STATS_PERIOD_CURRENT_DAY,
                STATS_PERIOD_PAST_3_DAYS,
                STATS_PERIOD_PAST_7_DAYS,
                STATS_PERIOD_PAST_15_DAYS,
                STATS_PERIOD_PAST_30_DAYS,
            })
    @Retention(RetentionPolicy.SOURCE)
    public @interface StatsPeriod {}

    @IntDef(
            flag = true,
            prefix = {"FLAG_RESOURCE_OVERUSE_"},
            value = {
                FLAG_RESOURCE_OVERUSE_IO,
            })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ResourceOveruseFlag {}

    @IntDef(
            flag = true,
            prefix = {"FLAG_MINIMUM_STATS_"},
            value = {
                FLAG_MINIMUM_STATS_IO_1_MB,
                FLAG_MINIMUM_STATS_IO_100_MB,
                FLAG_MINIMUM_STATS_IO_1_GB,
            })
    @Retention(RetentionPolicy.SOURCE)
    public @interface MinimumStatsFlag {}

    @IntDef(
            flag = true,
            prefix = {"RETURN_CODE_"},
            value = {
                RETURN_CODE_SUCCESS,
                RETURN_CODE_ERROR,
            })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ReturnCode {}

    /**
     * Constants that define the stats period in days.
     *
     * <p>The following constants represent the stats period for the past N days, It is used to
     * specify that the stats should be gathered for the last N days, including today and the N-1
     * previous days.
     *
     * <p>The stats period for the current day.
     */
    public static final int STATS_PERIOD_CURRENT_DAY = 1;

    /** The stats period for the past 3 days. */
    public static final int STATS_PERIOD_PAST_3_DAYS = 2;

    /** The stats period for the past 7 days */
    public static final int STATS_PERIOD_PAST_7_DAYS = 3;

    /** The stats period for the past 15 days */
    public static final int STATS_PERIOD_PAST_15_DAYS = 4;

    /** The stats period for the past 30 days */
    public static final int STATS_PERIOD_PAST_30_DAYS = 5;

    /** Constants that define the type of resource overuse. */
    public static final int FLAG_RESOURCE_OVERUSE_IO = 1 << 0;

    /**
     * Constants that define the minimum stats for each resource type.
     *
     * <p>The following constants represent the minimum amount of data written to disk.
     *
     * <p>The minimum amount of data that should be written to disk is 1 MB.
     */
    public static final int FLAG_MINIMUM_STATS_IO_1_MB = 1 << 0;

    /** The minimum amount of data that should be written to disk is 100 MB. */
    public static final int FLAG_MINIMUM_STATS_IO_100_MB = 1 << 1;

    /** The minimum amount of data that should be written to disk is 1 GB. */
    public static final int FLAG_MINIMUM_STATS_IO_1_GB = 1 << 2;

    /**
     * Returns codes used to indicate the result of a request.
     *
     * <p>The return code indicating a successful request.
     */
    public static final int RETURN_CODE_SUCCESS = 0;

    /** The return code indicating an error in the request. */
    public static final int RETURN_CODE_ERROR = -1;

    /**
     * Returns resource overuse stats for the calling package. Returns {@code null}, if no stats.
     *
     * @param resourceOveruseFlag Flag to indicate the types of resource overuse stats to return.
     * @param maxStatsPeriod Maximum period to aggregate the resource overuse stats.
     * @return Resource overuse stats for the calling package. If the calling package has no stats
     *     for a specified resource overuse type, null value is returned for the corresponding
     *     resource overuse stats. If the calling package doesn't have sufficient stats for {@code
     *     maxStatsPeriod} for a specified resource overuse type, the stats are returned only for
     *     the period returned in the individual resource overuse stats.
     */
    @NonNull
    public ResourceOveruseStats getResourceOveruseStats(
            @ResourceOveruseFlag int resourceOveruseFlag, @StatsPeriod int maxStatsPeriod) {
        try {
            return mService.getResourceOveruseStats(resourceOveruseFlag, maxStatsPeriod);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns resource overuse stats for all monitored packages.
     *
     * @param resourceOveruseFlag Flag to indicate the types of resource overuse stats to return.
     * @param minimumStatsFlag Flag to specify the minimum stats for each resource overuse type.
     *     Only stats greater than the specified minimum stats for a resource overuse type will be
     *     returned. May provide only one minimum stats flag for each resource overuse type. When no
     *     minimum stats flag is specified, all stats are returned.
     * @param maxStatsPeriod Maximum period to aggregate the resource overuse stats.
     * @return Resource overuse stats for all monitored packages. If any package doesn't have stats
     *     for a specified resource type, null value is returned for the corresponding resource
     *     overuse stats. If any package doesn't have sufficient stats for {@code maxStatsPeriod}
     *     for a specified resource overuse type, the stats are returned only for the period
     *     returned in the individual resource stats.
     */
    @RequiresPermission("android.permission.COLLECT_TV_WATCHDOG_METRICS")
    @NonNull
    public List<ResourceOveruseStats> getAllResourceOveruseStats(
            @ResourceOveruseFlag int resourceOveruseFlag,
            @MinimumStatsFlag int minimumStatsFlag,
            @StatsPeriod int maxStatsPeriod) {
        try {
            return mService.getAllResourceOveruseStats(
                    resourceOveruseFlag, minimumStatsFlag, maxStatsPeriod);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns resource overuse stats for a specific user package.
     *
     * @param packageName Name of the package whose stats should be returned.
     * @param userHandle Handle of the user whose stats should be returned.
     * @param resourceOveruseFlag Flag to indicate the types of resource overuse stats to return.
     * @param maxStatsPeriod Maximum period to aggregate the resource overuse stats.
     * @return Resource overuse stats for the specified user package. If the user package has no
     *     stats for a specified resource overuse type, null value is returned for the corresponding
     *     resource overuse stats. If the user package doesn't have sufficient stats for {@code
     *     maxStatsPeriod} for a specified resource overuse type, the stats are returned only for
     *     the period returned in the individual resource overuse stats.
     */
    @RequiresPermission("android.permission.COLLECT_TV_WATCHDOG_METRICS")
    @NonNull
    public ResourceOveruseStats getResourceOveruseStatsForUserPackage(
            @NonNull String packageName,
            @NonNull UserHandle userHandle,
            @ResourceOveruseFlag int resourceOveruseFlag,
            @StatsPeriod int maxStatsPeriod) {
        try {
            return mService.getResourceOveruseStatsForUserPackage(
                    packageName, userHandle, resourceOveruseFlag, maxStatsPeriod);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Listener to get resource overuse notifications.
     *
     * <p>Applications implement the listener method to take action and/or log on resource overuse.
     */
    public interface ResourceOveruseListener {
        /**
         * Called when a package either overuses a resource or about to overuse a resource.
         *
         * <p>The listener is called at the executor which is specified in {@link
         * TvWatchdogManager#addResourceOveruseListener} or {@link
         * TvWatchdogManager#addResourceOveruseListenerForSystem}.
         *
         * <p>The listener is called only on overusing one of the resources specified at the {@code
         * resourceOveruseFlag} in {@link TvWatchdogManager#addResourceOveruseListener} or {@link
         * TvWatchdogManager#addResourceOveruseListenerForSystem}.
         *
         * @param resourceOveruseStats Resource overuse stats containing stats only for resources
         *     overuse types that are either overused or about to be overused by the package.
         *     Implementations must check for null value in each resource overuse stats before
         *     reading the stats.
         */
        void onOveruse(@NonNull ResourceOveruseStats resourceOveruseStats);
    }

    /**
     * Adds the {@link ResourceOveruseListener} for the calling package.
     *
     * <p>Resource overuse notifications are sent only for the calling package's resource overuse.
     *
     * @param listener Listener implementing {@link ResourceOveruseListener} interface.
     * @param resourceOveruseFlag Flag to indicate the types of resource overuses to listen.
     * @throws IllegalStateException if {@code listener} is already added.
     */
    public void addResourceOveruseListener(
            @NonNull @CallbackExecutor Executor executor,
            @ResourceOveruseFlag int resourceOveruseFlag,
            @NonNull ResourceOveruseListener listener) {
        Objects.requireNonNull(listener, "Listener must be non-null");
        Objects.requireNonNull(executor, "Executor must be non-null");
        Preconditions.checkArgument(
                (resourceOveruseFlag > 0), "Must provide valid resource overuse flag");
        boolean shouldRemoveFromService;
        boolean shouldAddToService;
        synchronized (mLock) {
            ResourceOveruseListenerInfo listenerInfo =
                    new ResourceOveruseListenerInfo(listener, executor, resourceOveruseFlag);
            if (mResourceOveruseListenerInfos.contains(listenerInfo)) {
                throw new IllegalStateException("Cannot add the listener as it is already added");
            }
            shouldRemoveFromService = mResourceOveruseListenerImpl.hasListeners();
            shouldAddToService = mResourceOveruseListenerImpl.maybeAppendFlag(resourceOveruseFlag);
            mResourceOveruseListenerInfos.add(listenerInfo);
        }
        if (shouldAddToService) {
            if (shouldRemoveFromService) {
                removeResourceOveruseListenerImpl();
            }
            addResourceOveruseListenerImpl();
        }
    }

    /**
     * Removes the {@link ResourceOveruseListener} for the calling package.
     *
     * @param listener Listener implementing {@link ResourceOveruseListener} interface.
     */
    public void removeResourceOveruseListener(@NonNull ResourceOveruseListener listener) {
        Objects.requireNonNull(listener, "Listener must be non-null");
        boolean shouldRemoveFromService;
        boolean shouldReAddToService;
        synchronized (mLock) {
            int index = 0;
            int resourceOveruseFlag = 0;
            for (; index != mResourceOveruseListenerInfos.size(); ++index) {
                ResourceOveruseListenerInfo listenerInfo = mResourceOveruseListenerInfos.get(index);
                if (listenerInfo.listener == listener) {
                    resourceOveruseFlag = listenerInfo.resourceOveruseFlag;
                    break;
                }
            }
            if (index == mResourceOveruseListenerInfos.size()) {
                Slog.w(TAG, "Cannot remove the listener. It has not been added.");
                return;
            }
            mResourceOveruseListenerInfos.remove(index);
            shouldRemoveFromService =
                    mResourceOveruseListenerImpl.maybeRemoveFlag(resourceOveruseFlag);
            shouldReAddToService = mResourceOveruseListenerImpl.hasListeners();
        }
        if (shouldRemoveFromService) {
            removeResourceOveruseListenerImpl();
            if (shouldReAddToService) {
                addResourceOveruseListenerImpl();
            }
        }
    }

    /**
     * Adds {@link ResourceOveruseListener} to get resource overuse notifications for all packages.
     *
     * <p>Listening system services will get notified on any package overusing one of the resources
     * specified at {@code resourceOveruseFlag}.
     *
     * @param listener Listener implementing {@link ResourceOveruseListener} interface.
     * @param resourceOveruseFlag Flag to indicate the types of resource overuses to listen.
     * @throws IllegalStateException if {@code listener} is already added.
     */
    @RequiresPermission("android.permission.COLLECT_TV_WATCHDOG_METRICS")
    public void addResourceOveruseListenerForSystem(
            @NonNull @CallbackExecutor Executor executor,
            @ResourceOveruseFlag int resourceOveruseFlag,
            @NonNull ResourceOveruseListener listener) {
        Objects.requireNonNull(listener, "Listener must be non-null");
        Objects.requireNonNull(executor, "Executor must be non-null");
        Preconditions.checkArgument(
                (resourceOveruseFlag > 0), "Must provide valid resource overuse flag");
        boolean shouldRemoveFromService;
        boolean shouldAddToService;
        synchronized (mLock) {
            ResourceOveruseListenerInfo listenerInfo =
                    new ResourceOveruseListenerInfo(listener, executor, resourceOveruseFlag);
            if (mResourceOveruseListenerForSystemInfos.contains(listenerInfo)) {
                throw new IllegalStateException("Cannot add the listener as it is already added");
            }
            shouldRemoveFromService = mResourceOveruseListenerForSystemImpl.hasListeners();
            shouldAddToService =
                    mResourceOveruseListenerForSystemImpl.maybeAppendFlag(resourceOveruseFlag);
            mResourceOveruseListenerForSystemInfos.add(listenerInfo);
        }
        if (shouldAddToService) {
            if (shouldRemoveFromService) {
                removeResourceOveruseListenerForSystemImpl();
            }
            addResourceOveruseListenerForSystemImpl();
        }
    }

    /**
     * Removes {@link ResourceOveruseListener} from receiving system resource overuse notifications.
     *
     * @param listener Listener implementing {@link ResourceOveruseListener} interface.
     */
    @RequiresPermission("android.permission.COLLECT_TV_WATCHDOG_METRICS")
    public void removeResourceOveruseListenerForSystem(@NonNull ResourceOveruseListener listener) {
        Objects.requireNonNull(listener, "Listener must be non-null");
        boolean shouldRemoveFromService;
        boolean shouldReAddToService;
        synchronized (mLock) {
            int index = 0;
            int resourceOveruseFlag = 0;
            for (; index != mResourceOveruseListenerForSystemInfos.size(); ++index) {
                ResourceOveruseListenerInfo listenerInfo =
                        mResourceOveruseListenerForSystemInfos.get(index);
                if (listenerInfo.listener == listener) {
                    resourceOveruseFlag = listenerInfo.resourceOveruseFlag;
                    break;
                }
            }
            if (index == mResourceOveruseListenerForSystemInfos.size()) {
                Slog.w(TAG, "Cannot remove the listener. It has not been added.");
                return;
            }
            mResourceOveruseListenerForSystemInfos.remove(index);
            shouldRemoveFromService =
                    mResourceOveruseListenerForSystemImpl.maybeRemoveFlag(resourceOveruseFlag);
            shouldReAddToService = mResourceOveruseListenerForSystemImpl.hasListeners();
        }
        if (shouldRemoveFromService) {
            removeResourceOveruseListenerForSystemImpl();
            if (shouldReAddToService) {
                addResourceOveruseListenerForSystemImpl();
            }
        }
    }

    /**
     * Sets whether or not a package is killable on resource overuse.
     *
     * <p>Updating killable setting for package, whose state cannot be changed, will result in
     * exception. This API may be used by TVSettings application or UI notification.
     *
     * @param packageName Name of the package whose setting should to be updated. Note: All packages
     *     under shared UID share the killable state as well. Thus setting the killable state for
     *     one package will set the killable state for all other packages that share a UID.
     * @param userHandle User whose setting should be updated.
     * @param isKillable Whether or not the package for the specified user is killable on resource
     *     overuse.
     */
    @RequiresPermission("android.permission.CONTROL_TV_WATCHDOG_CONFIG")
    public void setKillablePackageAsUser(
            @NonNull String packageName, @NonNull UserHandle userHandle, boolean isKillable) {
        try {
            mService.setKillablePackageAsUser(packageName, userHandle, isKillable);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the list of package killable states on resource overuse for the user.
     *
     * <p>This API may be used by TVSettings application or UI notification.
     *
     * @param userHandle User whose killable states for all packages should be returned.
     */
    @RequiresPermission("android.permission.CONTROL_TV_WATCHDOG_CONFIG")
    @NonNull
    public List<PackageKillableState> getPackageKillableStatesAsUser(
            @NonNull UserHandle userHandle) {
        try {
            return mService.getPackageKillableStatesAsUser(userHandle);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Sets the resource overuse configurations for the components provided in the configurations.
     *
     * <p>Must provide only one configuration per component. System services should set the
     * configurations only for system and third-party components. Vendor services should set the
     * configuration only for the vendor component.
     *
     * @param configurations List of resource overuse configurations. One configuration per
     *     component.
     * @param resourceOveruseFlag Flag to indicate the types of resource overuse configurations to
     *     set.
     * @return - {@link #RETURN_CODE_SUCCESS} if the set request is successful. - {@link
     *     #RETURN_CODE_ERROR} if the set request cannot be completed and the client should retry
     *     later.
     * @throws IllegalArgumentException if {@code configurations} are invalid.
     */
    @RequiresPermission("android.permission.CONTROL_TV_WATCHDOG_CONFIG")
    @ReturnCode
    public int setResourceOveruseConfigurations(
            @NonNull List<ResourceOveruseConfiguration> configurations,
            @ResourceOveruseFlag int resourceOveruseFlag) {
        try {
            return mService.setResourceOveruseConfigurations(configurations, resourceOveruseFlag);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the current resource overuse configurations for all components.
     *
     * <p>This call is blocking and may take few seconds to return if the service is temporarily
     * unavailable.
     *
     * @param resourceOveruseFlag Flag to indicate the types of resource overuse configurations to
     *     return.
     * @return If the server process is alive and connected, returns list of available resource
     *     overuse configurations for all components. If the server process is dead, returns {@code
     *     null} value.
     * @throws IllegalStateException if the system is in an invalid state.
     */
    @RequiresPermission(
            anyOf = {
                "android.permission.CONTROL_TV_WATCHDOG_CONFIG",
                "android.permission.COLLECT_TV_WATCHDOG_METRICS"
            })
    @Nullable
    public List<ResourceOveruseConfiguration> getResourceOveruseConfigurations(
            @ResourceOveruseFlag int resourceOveruseFlag) {
        try {
            return mService.getResourceOveruseConfigurations(resourceOveruseFlag);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    private void addResourceOveruseListenerImpl() {
        try {
            mService.addResourceOveruseListener(
                    mResourceOveruseListenerImpl.resourceOveruseFlag(),
                    mResourceOveruseListenerImpl);
            if (DEBUG) {
                Slog.d(
                        TAG,
                        "Resource overuse listener implementation is successfully added to "
                                + "service");
            }
        } catch (RemoteException e) {
            synchronized (mLock) {
                mResourceOveruseListenerInfos.clear();
            }
            throw e.rethrowFromSystemServer();
        }
    }

    private void removeResourceOveruseListenerImpl() {
        try {
            mService.removeResourceOveruseListener(mResourceOveruseListenerImpl);
            if (DEBUG) {
                Slog.d(
                        TAG,
                        "Resource overuse listener implementation is successfully removed "
                                + "from service");
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @RequiresPermission("android.permission.COLLECT_TV_WATCHDOG_METRICS")
    private void addResourceOveruseListenerForSystemImpl() {
        try {
            mService.addResourceOveruseListenerForSystem(
                    mResourceOveruseListenerForSystemImpl.resourceOveruseFlag(),
                    mResourceOveruseListenerForSystemImpl);
            if (DEBUG) {
                Slog.d(
                        TAG,
                        "Resource overuse listener for system implementation is successfully "
                                + "added to service");
            }
        } catch (RemoteException e) {
            synchronized (mLock) {
                mResourceOveruseListenerForSystemInfos.clear();
            }
            throw e.rethrowFromSystemServer();
        }
    }

    @RequiresPermission("android.permission.COLLECT_TV_WATCHDOG_METRICS")
    private void removeResourceOveruseListenerForSystemImpl() {
        try {
            mService.removeResourceOveruseListenerForSystem(mResourceOveruseListenerForSystemImpl);
            if (DEBUG) {
                Slog.d(
                        TAG,
                        "Resource overuse listener for system implementation is successfully "
                                + "removed from service");
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    private void onResourceOveruse(ResourceOveruseStats resourceOveruseStats, boolean isSystem) {
        if (Flags.enableTvWatchdogEmmcProtection()) {
            if (resourceOveruseStats.getIoOveruseStats() == null) {
                Slog.w(TAG, "Skipping resource overuse notification as the stats are missing");
                return;
            }
        }
        List<ResourceOveruseListenerInfo> listenerInfos;
        synchronized (mLock) {
            if (isSystem) {
                listenerInfos = mResourceOveruseListenerForSystemInfos;
            } else {
                listenerInfos = mResourceOveruseListenerInfos;
            }
        }
        if (listenerInfos.isEmpty()) {
            Slog.w(
                    TAG,
                    "Cannot notify resource overuse listener "
                            + (isSystem ? "for system " : "")
                            + "as it is not registered.");
            return;
        }
        for (ResourceOveruseListenerInfo listenerInfo : listenerInfos) {
            if ((listenerInfo.resourceOveruseFlag & FLAG_RESOURCE_OVERUSE_IO) == 1) {
                listenerInfo.executor.execute(
                        () -> {
                            listenerInfo.listener.onOveruse(resourceOveruseStats);
                        });
            }
        }
    }

    private static final class IResourceOveruseListenerImpl extends IResourceOveruseListener.Stub {
        private static final int[] RESOURCE_OVERUSE_FLAGS = new int[] {FLAG_RESOURCE_OVERUSE_IO};

        private final WeakReference<TvWatchdogManager> mManager;
        private final boolean mIsSystem;

        private final Object mLock = new Object();

        @GuardedBy("mLock")
        private final SparseIntArray mNumListenersByResource;

        IResourceOveruseListenerImpl(TvWatchdogManager manager, boolean isSystem) {
            mManager = new WeakReference<>(manager);
            mIsSystem = isSystem;
            mNumListenersByResource = new SparseIntArray();
        }

        @Override
        public void onOveruse(ResourceOveruseStats resourceOveruserStats) {
            TvWatchdogManager manager = mManager.get();
            if (manager != null) {
                manager.onResourceOveruse(resourceOveruserStats, mIsSystem);
            }
        }

        public boolean hasListeners() {
            synchronized (mLock) {
                return mNumListenersByResource.size() != 0;
            }
        }

        public boolean maybeAppendFlag(int appendFlag) {
            boolean isChanged = false;
            synchronized (mLock) {
                for (int flag : RESOURCE_OVERUSE_FLAGS) {
                    if ((appendFlag & flag) != 1) {
                        continue;
                    }
                    int value = mNumListenersByResource.get(flag, 0);
                    isChanged = ++value == 1;
                    mNumListenersByResource.put(flag, value);
                }
            }
            return isChanged;
        }

        public boolean maybeRemoveFlag(int removeFlag) {
            boolean isChanged = false;
            synchronized (mLock) {
                for (int flag : RESOURCE_OVERUSE_FLAGS) {
                    if ((removeFlag & flag) != 1) {
                        continue;
                    }
                    int value = mNumListenersByResource.get(flag, 0);
                    if (value == 0) {
                        continue;
                    }
                    if (--value == 0) {
                        isChanged = true;
                        mNumListenersByResource.delete(flag);
                    } else {
                        mNumListenersByResource.put(flag, value);
                    }
                }
            }
            return isChanged;
        }

        public int resourceOveruseFlag() {
            synchronized (mLock) {
                int flag = 0;
                for (int i = 0; i < mNumListenersByResource.size(); ++i) {
                    flag |=
                            mNumListenersByResource.valueAt(i) > 0
                                    ? mNumListenersByResource.keyAt(i)
                                    : 0;
                }
                return flag;
            }
        }
    }

    private static final class ResourceOveruseListenerInfo {
        public final ResourceOveruseListener listener;
        public final Executor executor;
        public final int resourceOveruseFlag;

        ResourceOveruseListenerInfo(
                ResourceOveruseListener listener, Executor executor, int resourceOveruseFlag) {
            this.listener = listener;
            this.executor = executor;
            this.resourceOveruseFlag = resourceOveruseFlag;
        }

        @Override
        public boolean equals(Object obj) {
            if (obj == this) {
                return true;
            }
            if (!(obj instanceof ResourceOveruseListenerInfo)) {
                return false;
            }
            ResourceOveruseListenerInfo listenerInfo = (ResourceOveruseListenerInfo) obj;
            // The ResourceOveruseListenerInfo equality is solely based on the listener because
            // the clients shouldn't register the same listener multiple times. When checking
            // whether a listener is previously registered, this equality check is used.
            return listenerInfo.listener == listener;
        }

        @Override
        public int hashCode() {
            // Similar to equality check, the hash generator uses only the listener.
            return Objects.hash(listener);
        }
    }
}
