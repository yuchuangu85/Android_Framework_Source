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

package com.android.internal.car;

import static android.car.userlib.UserHelper.safeName;

import static com.android.internal.car.ExternalConstants.CarUserManagerConstants.USER_LIFECYCLE_EVENT_TYPE_STARTING;
import static com.android.internal.car.ExternalConstants.CarUserManagerConstants.USER_LIFECYCLE_EVENT_TYPE_STOPPED;
import static com.android.internal.car.ExternalConstants.CarUserManagerConstants.USER_LIFECYCLE_EVENT_TYPE_STOPPING;
import static com.android.internal.car.ExternalConstants.CarUserManagerConstants.USER_LIFECYCLE_EVENT_TYPE_SWITCHING;
import static com.android.internal.car.ExternalConstants.CarUserManagerConstants.USER_LIFECYCLE_EVENT_TYPE_UNLOCKED;
import static com.android.internal.car.ExternalConstants.CarUserManagerConstants.USER_LIFECYCLE_EVENT_TYPE_UNLOCKING;
import static com.android.internal.util.function.pooled.PooledLambda.obtainMessage;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.admin.DevicePolicyManager;
import android.automotive.watchdog.ICarWatchdogMonitor;
import android.automotive.watchdog.PowerCycle;
import android.automotive.watchdog.StateType;
import android.car.userlib.CarUserManagerHelper;
import android.car.userlib.CommonConstants.CarUserServiceConstants;
import android.car.userlib.HalCallback;
import android.car.userlib.InitialUserSetter;
import android.car.userlib.InitialUserSetter.InitialUserInfoType;
import android.car.userlib.UserHalHelper;
import android.car.userlib.UserHelper;
import android.car.watchdoglib.CarWatchdogDaemonHelper;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.UserInfo;
import android.hardware.automotive.vehicle.V2_0.InitialUserInfoRequestType;
import android.hardware.automotive.vehicle.V2_0.InitialUserInfoResponseAction;
import android.hidl.manager.V1_0.IServiceManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.Trace;
import android.os.UserHandle;
import android.os.UserManager;
import android.sysprop.CarProperties;
import android.util.EventLog;
import android.util.Slog;
import android.util.SparseBooleanArray;
import android.util.SparseIntArray;
import android.util.TimeUtils;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.car.ExternalConstants.ICarConstants;
import com.android.internal.os.IResultReceiver;
import com.android.server.SystemService;
import com.android.server.Watchdog;
import com.android.server.am.ActivityManagerService;
import com.android.server.utils.TimingsTraceAndSlog;
import com.android.server.wm.CarLaunchParamsModifier;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * System service side companion service for CarService. Starts car service and provide necessary
 * API for CarService. Only for car product.
 */
public class CarServiceHelperService extends SystemService {
    // Place holder for user name of the first user created.
    private static final String TAG = "CarServiceHelper";

    // TODO(b/154033860): STOPSHIP if they're still true
    private static final boolean DBG = true;
    private static final boolean VERBOSE = true;

    private static final String PROP_RESTART_RUNTIME = "ro.car.recovery.restart_runtime.enabled";

    private static final List<String> CAR_HAL_INTERFACES_OF_INTEREST = Arrays.asList(
            "android.hardware.automotive.vehicle@2.0::IVehicle",
            "android.hardware.automotive.audiocontrol@1.0::IAudioControl",
            "android.hardware.automotive.audiocontrol@2.0::IAudioControl"
    );

    // Message ID representing HAL timeout handling.
    private static final int WHAT_HAL_TIMEOUT = 1;
    // Message ID representing post-processing of process dumping.
    private static final int WHAT_POST_PROCESS_DUMPING = 2;
    // Message ID representing process killing.
    private static final int WHAT_PROCESS_KILL = 3;

    private static final long LIFECYCLE_TIMESTAMP_IGNORE = 0;

    // Typically there are ~2-5 ops while system and non-system users are starting.
    private final int NUMBER_PENDING_OPERATIONS = 5;

    @UserIdInt
    @GuardedBy("mLock")
    private int mLastSwitchedUser = UserHandle.USER_NULL;

    private final ICarServiceHelperImpl mHelper = new ICarServiceHelperImpl();
    private final Context mContext;
    private final Object mLock = new Object();
    @GuardedBy("mLock")
    private IBinder mCarService;
    @GuardedBy("mLock")
    private boolean mSystemBootCompleted;

    // Key: user id, value: lifecycle
    @GuardedBy("mLock")
    private final SparseIntArray mLastUserLifecycle = new SparseIntArray();

    private final CarUserManagerHelper mCarUserManagerHelper;
    private final InitialUserSetter mInitialUserSetter;
    private final UserManager mUserManager;
    private final CarLaunchParamsModifier mCarLaunchParamsModifier;

    private final boolean mHalEnabled;
    private final int mHalTimeoutMs;

    // Handler is currently only used for handleHalTimedout(), which is removed once received.
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private final ProcessTerminator mProcessTerminator = new ProcessTerminator();

    @GuardedBy("mLock")
    private boolean mInitialized;

    /**
     * End-to-end time (from process start) for unlocking the first non-system user.
     */
    private long mFirstUnlockedUserDuration;

    /**
     * Used to calculate how long it took to get the {@code INITIAL_USER_INFO} response from HAL:
     *
     * <ul>
     *   <li>{@code 0}: HAL not called yet
     *   <li>{@code <0}: stores the time HAL was called (multiplied by -1)
     *   <li>{@code >0}: contains the duration (in ms)
     * </ul>
     */
    private int mHalResponseTime;

    // TODO(b/150413515): rather than store Runnables, it would be more efficient to store some
    // parcelables representing the operation, then pass them to setCarServiceHelper
    @GuardedBy("mLock")
    private ArrayList<Runnable> mPendingOperations;

    @GuardedBy("mLock")
    private boolean mCarServiceHasCrashed;

    private final CarWatchdogDaemonHelper mCarWatchdogDaemonHelper;
    private final ICarWatchdogMonitorImpl mCarWatchdogMonitor = new ICarWatchdogMonitorImpl(this);
    private final CarWatchdogDaemonHelper.OnConnectionChangeListener mConnectionListener =
            (connected) -> {
                if (connected) {
                    registerMonitorToWatchdogDaemon();
                }
            };

    private final ServiceConnection mCarServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
            if (DBG) {
                Slog.d(TAG, "onServiceConnected:" + iBinder);
            }
            handleCarServiceConnection(iBinder);
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            handleCarServiceCrash();
        }
    };

    private final BroadcastReceiver mShutdownEventReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // Skip immediately if intent is not relevant to device shutdown.
            // FLAG_RECEIVER_FOREGROUND is checked to ignore the intent from UserController when
            // a user is stopped.
            if ((!intent.getAction().equals(Intent.ACTION_REBOOT)
                    && !intent.getAction().equals(Intent.ACTION_SHUTDOWN))
                    || (intent.getFlags() & Intent.FLAG_RECEIVER_FOREGROUND) == 0) {
                return;
            }
            int powerCycle = PowerCycle.POWER_CYCLE_SUSPEND;
            try {
                mCarWatchdogDaemonHelper.notifySystemStateChange(StateType.POWER_CYCLE,
                        powerCycle, /* arg2= */ 0);
                if (DBG) {
                    Slog.d(TAG, "Notified car watchdog daemon a power cycle(" + powerCycle + ")");
                }
            } catch (RemoteException | RuntimeException e) {
                Slog.w(TAG, "Notifying system state change failed: " + e);
            }
        }
    };

    public CarServiceHelperService(Context context) {
        this(context,
                new CarUserManagerHelper(context),
                /* initialUserSetter= */ null,
                UserManager.get(context),
                new CarLaunchParamsModifier(context),
                new CarWatchdogDaemonHelper(TAG),
                CarProperties.user_hal_enabled().orElse(false),
                CarProperties.user_hal_timeout().orElse(5_000)
        );
    }

    @VisibleForTesting
    CarServiceHelperService(
            Context context,
            CarUserManagerHelper userManagerHelper,
            InitialUserSetter initialUserSetter,
            UserManager userManager,
            CarLaunchParamsModifier carLaunchParamsModifier,
            CarWatchdogDaemonHelper carWatchdogDaemonHelper,
            boolean halEnabled,
            int halTimeoutMs) {
        super(context);
        mContext = context;
        mCarUserManagerHelper = userManagerHelper;
        mUserManager = userManager;
        mCarLaunchParamsModifier = carLaunchParamsModifier;
        mCarWatchdogDaemonHelper = carWatchdogDaemonHelper;
        boolean halValidUserHalSettings = false;
        if (halEnabled) {
            if (halTimeoutMs > 0) {
                Slog.i(TAG, "User HAL enabled with timeout of " + halTimeoutMs + "ms");
                halValidUserHalSettings = true;
            } else {
                Slog.w(TAG, "Not using User HAL due to invalid value on userHalTimeoutMs config: "
                        + halTimeoutMs);
            }
        }
        if (halValidUserHalSettings) {
            mHalEnabled = true;
            mHalTimeoutMs = halTimeoutMs;
        } else {
            mHalEnabled = false;
            mHalTimeoutMs = -1;
            Slog.i(TAG, "Not using User HAL");
        }
        if (initialUserSetter == null) {
            // Called from main constructor, which cannot pass a lambda referencing itself
            mInitialUserSetter = new InitialUserSetter(context, (u) -> setInitialUser(u));
        } else {
            mInitialUserSetter = initialUserSetter;
        }
    }

    @Override
    public void onBootPhase(int phase) {
        EventLog.writeEvent(EventLogTags.CAR_HELPER_BOOT_PHASE, phase);
        if (DBG) Slog.d(TAG, "onBootPhase:" + phase);

        TimingsTraceAndSlog t = newTimingsTraceAndSlog();
        if (phase == SystemService.PHASE_THIRD_PARTY_APPS_CAN_START) {
            t.traceBegin("onBootPhase.3pApps");
            mCarLaunchParamsModifier.init();
            checkForCarServiceConnection(t);
            setupAndStartUsers(t);
            checkForCarServiceConnection(t);
            t.traceEnd();
        } else if (phase == SystemService.PHASE_BOOT_COMPLETED) {
            t.traceBegin("onBootPhase.completed");
            managePreCreatedUsers();
            synchronized (mLock) {
                mSystemBootCompleted = true;
            }
            try {
                mCarWatchdogDaemonHelper.notifySystemStateChange(
                        StateType.BOOT_PHASE, phase, /* arg2= */ 0);
            } catch (RemoteException | RuntimeException e) {
                Slog.w(TAG, "Failed to notify boot phase change: " + e);
            }
            t.traceEnd();
        }
    }

    @Override
    public void onStart() {
        EventLog.writeEvent(EventLogTags.CAR_HELPER_START, mHalEnabled ? 1 : 0);

        IntentFilter filter = new IntentFilter(Intent.ACTION_REBOOT);
        filter.addAction(Intent.ACTION_SHUTDOWN);
        mContext.registerReceiverForAllUsers(mShutdownEventReceiver, filter, null, null);
        mCarWatchdogDaemonHelper.addOnConnectionChangeListener(mConnectionListener);
        mCarWatchdogDaemonHelper.connect();
        Intent intent = new Intent();
        intent.setPackage("com.android.car");
        intent.setAction(ICarConstants.CAR_SERVICE_INTERFACE);
        if (!mContext.bindServiceAsUser(intent, mCarServiceConnection, Context.BIND_AUTO_CREATE,
                UserHandle.SYSTEM)) {
            Slog.wtf(TAG, "cannot start car service");
        }
        loadNativeLibrary();
    }

    @Override
    public void onUserUnlocking(@NonNull TargetUser user) {
        if (isPreCreated(user, USER_LIFECYCLE_EVENT_TYPE_UNLOCKING)) return;
        EventLog.writeEvent(EventLogTags.CAR_HELPER_USER_UNLOCKING, user.getUserIdentifier());
        if (DBG) Slog.d(TAG, "onUserUnlocking(" + user + ")");

        sendUserLifecycleEvent(USER_LIFECYCLE_EVENT_TYPE_UNLOCKING, user);
    }

    @Override
    public void onUserUnlocked(@NonNull TargetUser user) {
        if (isPreCreated(user, USER_LIFECYCLE_EVENT_TYPE_UNLOCKED)) return;
        int userId = user.getUserIdentifier();
        EventLog.writeEvent(EventLogTags.CAR_HELPER_USER_UNLOCKED, userId);
        if (DBG) Slog.d(TAG, "onUserUnlocked(" + user + ")");

        if (mFirstUnlockedUserDuration == 0 && !UserHelper.isHeadlessSystemUser(userId)) {
            mFirstUnlockedUserDuration = SystemClock.elapsedRealtime()
                    - Process.getStartElapsedRealtime();
            Slog.i(TAG, "Time to unlock 1st user(" + user + "): "
                    + TimeUtils.formatDuration(mFirstUnlockedUserDuration));
            synchronized (mLock) {
                mLastUserLifecycle.put(userId, USER_LIFECYCLE_EVENT_TYPE_UNLOCKED);
                if (mCarService == null) {
                    if (DBG) Slog.d(TAG, "Queuing first user unlock for user " + user);
                    queueOperationLocked(() -> sendFirstUserUnlocked(user));
                    return;
                }
            }
            sendFirstUserUnlocked(user);
            return;
        }
        sendUserLifecycleEvent(USER_LIFECYCLE_EVENT_TYPE_UNLOCKED, user);
    }

    @Override
    public void onUserStarting(@NonNull TargetUser user) {
        if (isPreCreated(user, USER_LIFECYCLE_EVENT_TYPE_STARTING)) return;
        EventLog.writeEvent(EventLogTags.CAR_HELPER_USER_STARTING, user.getUserIdentifier());
        if (DBG) Slog.d(TAG, "onUserStarting(" + user + ")");

        sendUserLifecycleEvent(USER_LIFECYCLE_EVENT_TYPE_STARTING, user);
    }

    @Override
    public void onUserStopping(@NonNull TargetUser user) {
        if (isPreCreated(user, USER_LIFECYCLE_EVENT_TYPE_STOPPING)) return;
        EventLog.writeEvent(EventLogTags.CAR_HELPER_USER_STOPPING, user.getUserIdentifier());
        if (DBG) Slog.d(TAG, "onUserStopping(" + user + ")");

        sendUserLifecycleEvent(USER_LIFECYCLE_EVENT_TYPE_STOPPING, user);
        int userId = user.getUserIdentifier();
        mCarLaunchParamsModifier.handleUserStopped(userId);
    }

    @Override
    public void onUserStopped(@NonNull TargetUser user) {
        if (isPreCreated(user, USER_LIFECYCLE_EVENT_TYPE_STOPPED)) return;
        EventLog.writeEvent(EventLogTags.CAR_HELPER_USER_STOPPED, user.getUserIdentifier());
        if (DBG) Slog.d(TAG, "onUserStopped(" + user + ")");

        sendUserLifecycleEvent(USER_LIFECYCLE_EVENT_TYPE_STOPPED, user);
    }

    @Override
    public void onUserSwitching(@Nullable TargetUser from, @NonNull TargetUser to) {
        if (isPreCreated(to, USER_LIFECYCLE_EVENT_TYPE_SWITCHING)) return;
        EventLog.writeEvent(EventLogTags.CAR_HELPER_USER_SWITCHING,
                from == null ? UserHandle.USER_NULL : from.getUserIdentifier(),
                to.getUserIdentifier());
        if (DBG) Slog.d(TAG, "onUserSwitching(" + from + ">>" + to + ")");

        sendUserLifecycleEvent(USER_LIFECYCLE_EVENT_TYPE_SWITCHING, from, to);
        int userId = to.getUserIdentifier();
        mCarLaunchParamsModifier.handleCurrentUserSwitching(userId);
    }

    @VisibleForTesting
    void loadNativeLibrary() {
        System.loadLibrary("car-framework-service-jni");
    }

    private boolean isPreCreated(@NonNull TargetUser user, int eventType) {
        UserInfo userInfo = user.getUserInfo();
        if (userInfo == null) {
            Slog.wtf(TAG, "no UserInfo on " + user + " on eventType " + eventType);
            return false;
        }
        if (!userInfo.preCreated) return false;

        if (DBG) {
            Slog.d(TAG, "Ignoring event of type " + eventType + " for pre-created user "
                    + userInfo.toFullString());
        }
        return true;
    }

    /**
     * Queues a binder operation so it's called when the service is connected.
     */
    private void queueOperationLocked(@NonNull Runnable operation) {
        if (mPendingOperations == null) {
            mPendingOperations = new ArrayList<>(NUMBER_PENDING_OPERATIONS);
        }
        mPendingOperations.add(operation);
    }

    // Sometimes car service onConnected call is delayed a lot. car service binder can be
    // found from ServiceManager directly. So do some polling during boot-up to connect to
    // car service ASAP.
    private void checkForCarServiceConnection(@NonNull TimingsTraceAndSlog t) {
        synchronized (mLock) {
            if (mCarService != null) {
                return;
            }
        }
        t.traceBegin("checkForCarServiceConnection");
        IBinder iBinder = ServiceManager.checkService("car_service");
        if (iBinder != null) {
            if (DBG) {
                Slog.d(TAG, "Car service found through ServiceManager:" + iBinder);
            }
            handleCarServiceConnection(iBinder);
        }
        t.traceEnd();
    }

    @VisibleForTesting
    int getHalResponseTime() {
        return mHalResponseTime;
    }

    @VisibleForTesting
    void setInitialHalResponseTime() {
        mHalResponseTime = -((int) SystemClock.uptimeMillis());
    }

    @VisibleForTesting
    void setFinalHalResponseTime() {
        mHalResponseTime += (int) SystemClock.uptimeMillis();
    }

    @VisibleForTesting
    void handleCarServiceConnection(IBinder iBinder) {
        boolean carServiceHasCrashed;
        int lastSwitchedUser;
        ArrayList<Runnable> pendingOperations;
        SparseIntArray lastUserLifecycle = null;
        synchronized (mLock) {
            if (mCarService == iBinder) {
                return; // already connected.
            }
            Slog.i(TAG, "car service binder changed, was:" + mCarService + " new:" + iBinder);
            mCarService = iBinder;
            carServiceHasCrashed = mCarServiceHasCrashed;
            mCarServiceHasCrashed = false;
            lastSwitchedUser = mLastSwitchedUser;
            pendingOperations = mPendingOperations;
            mPendingOperations = null;
            if (carServiceHasCrashed) {
                lastUserLifecycle = mLastUserLifecycle.clone();
            }
        }
        int numberOperations = pendingOperations == null ? 0 : pendingOperations.size();
        EventLog.writeEvent(EventLogTags.CAR_HELPER_SVC_CONNECTED, numberOperations);

        Slog.i(TAG, "**CarService connected**");

        sendSetCarServiceHelperBinderCall();
        if (carServiceHasCrashed) {
            int numUsers = lastUserLifecycle.size();
            TimingsTraceAndSlog t = newTimingsTraceAndSlog();
            t.traceBegin("send-uses-after-reconnect-" + numUsers);
            // Send user0 events first
            int user0Lifecycle = lastUserLifecycle.get(UserHandle.USER_SYSTEM,
                    USER_LIFECYCLE_EVENT_TYPE_STARTING);
            lastUserLifecycle.delete(UserHandle.USER_SYSTEM);
            boolean user0IsCurrent = lastSwitchedUser == UserHandle.USER_SYSTEM;
            sendAllLifecyleToUser(UserHandle.USER_SYSTEM, user0Lifecycle, user0IsCurrent);
            // Send current user events next
            if (!user0IsCurrent) {
                int currentUserLifecycle = lastUserLifecycle.get(lastSwitchedUser,
                        USER_LIFECYCLE_EVENT_TYPE_STARTING);
                lastUserLifecycle.delete(lastSwitchedUser);
                sendAllLifecyleToUser(lastSwitchedUser, currentUserLifecycle,
                        /* isCurrentUser= */ true);
            }
            // Send all other users' events
            for (int i = 0; i < lastUserLifecycle.size(); i++) {
                int userId = lastUserLifecycle.keyAt(i);
                int lifecycle = lastUserLifecycle.valueAt(i);
                sendAllLifecyleToUser(userId, lifecycle, /* isCurrentUser= */ false);
            }
            t.traceEnd();
        } else if (pendingOperations != null) {
            if (DBG) Slog.d(TAG, "Running " + numberOperations + " pending operations");
            TimingsTraceAndSlog t = newTimingsTraceAndSlog();
            t.traceBegin("send-pending-ops-" + numberOperations);
            for (int i = 0; i < numberOperations; i++) {
                Runnable operation = pendingOperations.get(i);
                try {
                    operation.run();
                } catch (RuntimeException e) {
                    Slog.w(TAG, "exception running operation #" + i + ": " + e);
                }
            }
            t.traceEnd();
        }
    }

    private void sendAllLifecyleToUser(@UserIdInt int userId, int lifecycle,
            boolean isCurrentUser) {
        if (DBG) {
            Slog.d(TAG, "sendAllLifecyleToUser, user:" + userId + " lifecycle:" + lifecycle);
        }
        if (lifecycle >= USER_LIFECYCLE_EVENT_TYPE_STARTING) {
            sendUserLifecycleEvent(USER_LIFECYCLE_EVENT_TYPE_STARTING, LIFECYCLE_TIMESTAMP_IGNORE,
                    UserHandle.USER_NULL, userId);
        }
        if (isCurrentUser && userId != UserHandle.USER_SYSTEM) {
            // Do not care about actual previous user.
            sendUserLifecycleEvent(USER_LIFECYCLE_EVENT_TYPE_SWITCHING, LIFECYCLE_TIMESTAMP_IGNORE,
                    UserHandle.USER_SYSTEM, userId);
        }
        if (lifecycle >= USER_LIFECYCLE_EVENT_TYPE_UNLOCKING) {
            sendUserLifecycleEvent(USER_LIFECYCLE_EVENT_TYPE_UNLOCKING, LIFECYCLE_TIMESTAMP_IGNORE,
                    UserHandle.USER_NULL, userId);
        }
        if (lifecycle >= USER_LIFECYCLE_EVENT_TYPE_UNLOCKED) {
            sendUserLifecycleEvent(USER_LIFECYCLE_EVENT_TYPE_UNLOCKED, LIFECYCLE_TIMESTAMP_IGNORE,
                    UserHandle.USER_NULL, userId);
        }
    }

    private TimingsTraceAndSlog newTimingsTraceAndSlog() {
        return new TimingsTraceAndSlog(TAG, Trace.TRACE_TAG_SYSTEM_SERVER);
    }

    private void setupAndStartUsers(@NonNull TimingsTraceAndSlog t) {
        DevicePolicyManager devicePolicyManager =
                mContext.getSystemService(DevicePolicyManager.class);
        if (devicePolicyManager != null && devicePolicyManager.getUserProvisioningState()
                != DevicePolicyManager.STATE_USER_UNMANAGED) {
            Slog.i(TAG, "DevicePolicyManager active, skip user unlock/switch");
            return;
        }
        t.traceBegin("setupAndStartUsers");
        if (mHalEnabled) {
            Slog.i(TAG, "Delegating initial switching to HAL");
            setupAndStartUsersUsingHal();
        } else {
            setupAndStartUsersDirectly(t, /* userLocales= */ null);
        }
        t.traceEnd();
    }

    private void handleHalTimedout() {
        synchronized (mLock) {
            if (mInitialized) return;
        }

        Slog.w(TAG, "HAL didn't respond in " + mHalTimeoutMs + "ms; using default behavior");
        setupAndStartUsersDirectly();
    }

    private void setupAndStartUsersUsingHal() {
        mHandler.sendMessageDelayed(obtainMessage(CarServiceHelperService::handleHalTimedout, this)
                .setWhat(WHAT_HAL_TIMEOUT), mHalTimeoutMs);

        // TODO(b/150413515): get rid of receiver once returned?
        IResultReceiver receiver = new IResultReceiver.Stub() {
            @Override
            public void send(int resultCode, Bundle resultData) {
                EventLog.writeEvent(EventLogTags.CAR_HELPER_HAL_RESPONSE, resultCode);

                setFinalHalResponseTime();
                if (DBG) {
                    Slog.d(TAG, "Got result from HAL (" +
                            UserHalHelper.halCallbackStatusToString(resultCode) + ") in "
                            + TimeUtils.formatDuration(mHalResponseTime));
                }

                mHandler.removeMessages(WHAT_HAL_TIMEOUT);
                // TODO(b/150222501): log how long it took to receive the response
                // TODO(b/150413515): print resultData as well on 2 logging calls below
                synchronized (mLock) {
                    if (mInitialized) {
                        Slog.w(TAG, "Result from HAL came too late, ignoring: "
                                + UserHalHelper.halCallbackStatusToString(resultCode));
                        return;
                    }
                }

                if (resultCode != HalCallback.STATUS_OK) {
                    Slog.w(TAG, "Service returned non-ok status ("
                            + UserHalHelper.halCallbackStatusToString(resultCode)
                            + "); using default behavior");
                    fallbackToDefaultInitialUserBehavior();
                    return;
                }

                if (resultData == null) {
                    Slog.w(TAG, "Service returned null bundle");
                    fallbackToDefaultInitialUserBehavior();
                    return;
                }

                int action = resultData.getInt(CarUserServiceConstants.BUNDLE_INITIAL_INFO_ACTION,
                        InitialUserInfoResponseAction.DEFAULT);

                String userLocales = resultData
                        .getString(CarUserServiceConstants.BUNDLE_USER_LOCALES);
                if (userLocales != null) {
                    Slog.i(TAG, "Changing user locales to " + userLocales);
                }

                switch (action) {
                    case InitialUserInfoResponseAction.DEFAULT:
                        EventLog.writeEvent(EventLogTags.CAR_HELPER_HAL_DEFAULT_BEHAVIOR,
                                /* fallback= */ 0, userLocales);
                        if (DBG) Slog.d(TAG, "User HAL returned DEFAULT behavior");
                        setupAndStartUsersDirectly(newTimingsTraceAndSlog(), userLocales);
                        return;
                    case InitialUserInfoResponseAction.SWITCH:
                        int userId = resultData.getInt(CarUserServiceConstants.BUNDLE_USER_ID);
                        startUserByHalRequest(userId, userLocales);
                        return;
                    case InitialUserInfoResponseAction.CREATE:
                        String name = resultData
                                .getString(CarUserServiceConstants.BUNDLE_USER_NAME);
                        int flags = resultData.getInt(CarUserServiceConstants.BUNDLE_USER_FLAGS);
                        createUserByHalRequest(name, userLocales, flags);
                        return;
                    default:
                        Slog.w(TAG, "Invalid InitialUserInfoResponseAction action: " + action);
                }
                fallbackToDefaultInitialUserBehavior();
            }
        };
        int initialUserInfoRequestType = getInitialUserInfoRequestType();
        EventLog.writeEvent(EventLogTags.CAR_HELPER_HAL_REQUEST, initialUserInfoRequestType);

        setInitialHalResponseTime();
        sendOrQueueGetInitialUserInfo(initialUserInfoRequestType, receiver);
    }

    @VisibleForTesting
    int getInitialUserInfoRequestType() {
        if (!mCarUserManagerHelper.hasInitialUser()) {
            return InitialUserInfoRequestType.FIRST_BOOT;
        }
        if (mContext.getPackageManager().isDeviceUpgrading()) {
            return InitialUserInfoRequestType.FIRST_BOOT_AFTER_OTA;
        }
        return InitialUserInfoRequestType.COLD_BOOT;
    }

    private void startUserByHalRequest(@UserIdInt int userId, @Nullable String userLocales) {
        if (userId <= 0) {
            Slog.w(TAG, "invalid (or missing) user id sent by HAL: " + userId);
            fallbackToDefaultInitialUserBehavior();
            return;
        }

        EventLog.writeEvent(EventLogTags.CAR_HELPER_HAL_START_USER, userId, userLocales);
        if (DBG) Slog.d(TAG, "Starting user " + userId + " as requested by HAL");

        // It doesn't need to replace guest, as the switch would fail anyways if the requested user
        // was a guest because it wouldn't exist.
        mInitialUserSetter.set(newInitialUserInfoBuilder(InitialUserSetter.TYPE_SWITCH)
                .setUserLocales(userLocales)
                .setSwitchUserId(userId).build());
    }

    private InitialUserSetter.Builder newInitialUserInfoBuilder(@InitialUserInfoType int type) {
        return new InitialUserSetter.Builder(type)
                .setSupportsOverrideUserIdProperty(!CarProperties.user_hal_enabled().orElse(false));
    }

    private void createUserByHalRequest(@Nullable String name, @Nullable String userLocales,
            int halFlags) {
        String friendlyName = "user with name '" + safeName(name) + "', locales " + userLocales
                + ", and flags " + UserHalHelper.userFlagsToString(halFlags);
        EventLog.writeEvent(EventLogTags.CAR_HELPER_HAL_CREATE_USER, halFlags, safeName(name),
                userLocales);
        if (DBG) Slog.d(TAG, "HAL request creation of " + friendlyName);

        mInitialUserSetter.set(newInitialUserInfoBuilder(InitialUserSetter.TYPE_CREATE)
                .setUserLocales(userLocales)
                .setNewUserName(name)
                .setNewUserFlags(halFlags).build());

    }

    private void fallbackToDefaultInitialUserBehavior() {
        EventLog.writeEvent(EventLogTags.CAR_HELPER_HAL_DEFAULT_BEHAVIOR, /* fallback= */ 1);
        if (DBG) Slog.d(TAG, "Falling back to DEFAULT initial user behavior");
        setupAndStartUsersDirectly();
    }

    private void setupAndStartUsersDirectly() {
        setupAndStartUsersDirectly(newTimingsTraceAndSlog(), /* userLocales= */ null);
    }

    private void setupAndStartUsersDirectly(@NonNull TimingsTraceAndSlog t,
            @Nullable String userLocales) {
        synchronized (mLock) {
            if (mInitialized) {
                Slog.wtf(TAG, "Already initialized", new Exception());
                return;
            }
            mInitialized = true;
        }

        mInitialUserSetter.set(newInitialUserInfoBuilder(InitialUserSetter.TYPE_DEFAULT_BEHAVIOR)
                .setUserLocales(userLocales)
                .build());
    }

    @VisibleForTesting
    void managePreCreatedUsers() {
        // First gets how many pre-createad users are defined by the OEM
        int numberRequestedGuests = CarProperties.number_pre_created_guests().orElse(0);
        int numberRequestedUsers = CarProperties.number_pre_created_users().orElse(0);
        EventLog.writeEvent(EventLogTags.CAR_HELPER_PRE_CREATION_REQUESTED, numberRequestedUsers,
                numberRequestedGuests);
        if (DBG) {
            Slog.d(TAG, "managePreCreatedUsers(): OEM asked for " + numberRequestedGuests
                    + " guests and " + numberRequestedUsers + " users");
        }

        if (numberRequestedGuests < 0 || numberRequestedUsers < 0) {
            Slog.w(TAG, "preCreateUsers(): invalid values provided by OEM; "
                    + "number_pre_created_guests=" + numberRequestedGuests
                    + ", number_pre_created_users=" + numberRequestedUsers);
            return;
        }

        // Then checks how many exist already
        List<UserInfo> allUsers = mUserManager.getUsers(/* excludePartial= */ true,
                /* excludeDying= */ true, /* excludePreCreated= */ false);

        int allUsersSize = allUsers.size();
        if (DBG) Slog.d(TAG, "preCreateUsers: total users size is " + allUsersSize);

        int numberExistingGuests = 0;
        int numberExistingUsers = 0;

        // List of pre-created users that were not properly initialized. Typically happens when
        // the system crashed / rebooted before they were fully started.
        SparseBooleanArray invalidPreCreatedUsers = new SparseBooleanArray();

        // List of all pre-created users - it will be used to remove unused ones (when needed)
        SparseBooleanArray existingPrecreatedUsers = new SparseBooleanArray();

        // List of extra pre-created users and guests - they will be removed
        List<Integer> extraPreCreatedUsers = new ArrayList<>();

        for (int i = 0; i < allUsersSize; i++) {
            UserInfo user = allUsers.get(i);
            if (!user.preCreated) continue;
            if (!user.isInitialized()) {
                Slog.w(TAG, "Found invalid pre-created user that needs to be removed: "
                        + user.toFullString());
                invalidPreCreatedUsers.append(user.id, /* notUsed=*/ true);
                continue;
            }
            boolean isGuest = user.isGuest();
            existingPrecreatedUsers.put(user.id, isGuest);
            if (isGuest) {
                numberExistingGuests++;
                if (numberExistingGuests > numberRequestedGuests) {
                    extraPreCreatedUsers.add(user.id);
                }
            } else {
                numberExistingUsers++;
                if (numberExistingUsers > numberRequestedUsers) {
                    extraPreCreatedUsers.add(user.id);
                }
            }
        }
        if (DBG) {
            Slog.d(TAG, "managePreCreatedUsers(): system already has " + numberExistingGuests
                    + " pre-created guests," + numberExistingUsers + " pre-created users, and these"
                    + " invalid users: " + invalidPreCreatedUsers
                    + " extra pre-created users: " + extraPreCreatedUsers);
        }

        int numberGuestsToAdd = numberRequestedGuests - numberExistingGuests;
        int numberUsersToAdd = numberRequestedUsers - numberExistingUsers;
        int numberGuestsToRemove = numberExistingGuests - numberRequestedGuests;
        int numberUsersToRemove = numberExistingUsers - numberRequestedUsers;
        int numberInvalidUsersToRemove = invalidPreCreatedUsers.size();

        EventLog.writeEvent(EventLogTags.CAR_HELPER_PRE_CREATION_STATUS,
                numberExistingUsers, numberUsersToAdd, numberUsersToRemove,
                numberExistingGuests, numberGuestsToAdd, numberGuestsToRemove,
                numberInvalidUsersToRemove);

        if (numberGuestsToAdd == 0 && numberUsersToAdd == 0 && numberInvalidUsersToRemove == 0) {
            if (DBG) Slog.d(TAG, "managePreCreatedUsers(): everything in sync");
            return;
        }

        // Finally, manage them....

        // In theory, we could submit multiple user pre-creations in parallel, but we're
        // submitting just 1 task, for 2 reasons:
        //   1.To minimize it's effect on other system server initialization tasks.
        //   2.The pre-created users will be unlocked in parallel anyways.
        runAsync(() -> {
            TimingsTraceAndSlog t = new TimingsTraceAndSlog(TAG + "Async",
                    Trace.TRACE_TAG_SYSTEM_SERVER);

            t.traceBegin("preCreateUsers");
            if (numberUsersToAdd > 0) {
                preCreateUsers(t, numberUsersToAdd, /* isGuest= */ false);
            }
            if (numberGuestsToAdd > 0) {
                preCreateUsers(t, numberGuestsToAdd, /* isGuest= */ true);
            }

            int totalNumberToRemove = extraPreCreatedUsers.size();
            if (DBG) Slog.d(TAG, "Must delete " + totalNumberToRemove + " pre-created users");
            if (totalNumberToRemove > 0) {
                int[] usersToRemove = new int[totalNumberToRemove];
                for (int i = 0; i < totalNumberToRemove; i++) {
                    usersToRemove[i] = extraPreCreatedUsers.get(i);
                }
                removePreCreatedUsers(usersToRemove);
            }

            t.traceEnd();

            if (numberInvalidUsersToRemove > 0) {
                t.traceBegin("removeInvalidPreCreatedUsers");
                for (int i = 0; i < numberInvalidUsersToRemove; i++) {
                    int userId = invalidPreCreatedUsers.keyAt(i);
                    Slog.i(TAG, "removing invalid pre-created user " + userId);
                    mUserManager.removeUser(userId);
                }
                t.traceEnd();
            }
        });
    }

    private void preCreateUsers(@NonNull TimingsTraceAndSlog t, int size, boolean isGuest) {
        String msg = isGuest ? "preCreateGuests-" + size : "preCreateUsers-" + size;
        if (DBG) Slog.d(TAG, "preCreateUsers: " + msg);
        t.traceBegin(msg);
        for (int i = 1; i <= size; i++) {
            UserInfo preCreated = preCreateUsers(t, isGuest);
            if (preCreated == null) {
                Slog.w(TAG, "Could not pre-create" + (isGuest ? " guest" : "")
                        + " user #" + i);
                continue;
            }
        }
        t.traceEnd();
    }

    @VisibleForTesting
    void runAsync(Runnable r) {
        // We cannot use SystemServerInitThreadPool because user pre-creation can take too long,
        // which would crash the SystemServer on SystemServerInitThreadPool.shutdown();
        String threadName = TAG + ".AsyncTask";
        Slog.i(TAG, "Starting thread " + threadName);
        new Thread(() -> {
            try {
                r.run();
                Slog.i(TAG, "Finishing thread " + threadName);
            } catch (RuntimeException e) {
                Slog.e(TAG, "runAsync() failed", e);
                throw e;
            }
        }, threadName).start();
    }

    @Nullable
    public UserInfo preCreateUsers(@NonNull TimingsTraceAndSlog t, boolean isGuest) {
        String traceMsg = "pre-create" + (isGuest ? "-guest" : "-user");
        t.traceBegin(traceMsg);
        // NOTE: we want to get rid of UserManagerHelper, so let's call UserManager directly
        String userType =
                isGuest ? UserManager.USER_TYPE_FULL_GUEST : UserManager.USER_TYPE_FULL_SECONDARY;
        UserInfo user = null;
        try {
            user = mUserManager.preCreateUser(userType);
            if (user == null) {
                logPrecreationFailure(traceMsg, /* cause= */ null);
            }
        } catch (Exception e) {
            logPrecreationFailure(traceMsg, e);
        } finally {
            t.traceEnd();
        }
        return user;
    }

    private void removePreCreatedUsers(int[] usersToRemove) {
        for (int userId : usersToRemove) {
            Slog.i(TAG, "removing pre-created user with id " + userId);
            mUserManager.removeUser(userId);
        }
    }

    /**
     * Logs proper message when user pre-creation fails (most likely because there are too many).
     */
    @VisibleForTesting
    void logPrecreationFailure(@NonNull String operation, @Nullable Exception cause) {
        int maxNumberUsers = UserManager.getMaxSupportedUsers();
        int currentNumberUsers = mUserManager.getUserCount();
        StringBuilder message = new StringBuilder(operation.length() + 100)
                .append(operation).append(" failed. Number users: ").append(currentNumberUsers)
                .append(" Max: ").append(maxNumberUsers);
        if (cause == null) {
            Slog.w(TAG, message.toString());
        } else {
            Slog.w(TAG, message.toString(), cause);
        }
    }

    private void sendSetCarServiceHelperBinderCall() {
        Parcel data = Parcel.obtain();
        data.writeInterfaceToken(ICarConstants.CAR_SERVICE_INTERFACE);
        data.writeStrongBinder(mHelper.asBinder());
        // void setCarServiceHelper(in IBinder helper)
        sendBinderCallToCarService(data, ICarConstants.ICAR_CALL_SET_CAR_SERVICE_HELPER);
    }

    private void sendUserLifecycleEvent(int eventType, @NonNull TargetUser user) {
        sendUserLifecycleEvent(eventType, /* from= */ null, user);
    }

    private void sendUserLifecycleEvent(int eventType, @Nullable TargetUser from,
            @NonNull TargetUser to) {
        long now = System.currentTimeMillis();
        synchronized (mLock) {
            if (eventType == USER_LIFECYCLE_EVENT_TYPE_SWITCHING) {
                mLastSwitchedUser = to.getUserIdentifier();
            } else if (eventType == USER_LIFECYCLE_EVENT_TYPE_STOPPING
                    || eventType == USER_LIFECYCLE_EVENT_TYPE_STOPPED) {
                mLastUserLifecycle.delete(to.getUserIdentifier());
            } else {
                mLastUserLifecycle.put(to.getUserIdentifier(), eventType);
            }
            if (mCarService == null) {
                if (DBG) Slog.d(TAG, "Queuing lifecycle event " + eventType + " for user " + to);
                queueOperationLocked(() -> sendUserLifecycleEvent(eventType, now, from, to));
                return;
            }
        }
        TimingsTraceAndSlog t = newTimingsTraceAndSlog();
        t.traceBegin("send-lifecycle-" + eventType + "-" + to.getUserIdentifier());
        sendUserLifecycleEvent(eventType, now, from, to);
        t.traceEnd();
    }

    private void sendUserLifecycleEvent(int eventType, long timestamp, @Nullable TargetUser from,
            @NonNull TargetUser to) {
        int fromId = from == null ? UserHandle.USER_NULL : from.getUserIdentifier();
        int toId = to.getUserIdentifier();
        sendUserLifecycleEvent(eventType, timestamp, fromId, toId);
    }

    private void sendUserLifecycleEvent(int eventType, long timestamp, @UserIdInt int fromId,
            @UserIdInt int toId) {
        Parcel data = Parcel.obtain();
        data.writeInterfaceToken(ICarConstants.CAR_SERVICE_INTERFACE);
        data.writeInt(eventType);
        data.writeLong(timestamp);
        data.writeInt(fromId);
        data.writeInt(toId);
        // void onUserLifecycleEvent(int eventType, long timestamp, int from, int to)
        sendBinderCallToCarService(data, ICarConstants.ICAR_CALL_ON_USER_LIFECYCLE);
    }

    private void sendOrQueueGetInitialUserInfo(int requestType, @NonNull IResultReceiver receiver) {
        synchronized (mLock) {
            if (mCarService == null) {
                if (DBG) Slog.d(TAG, "Queuing GetInitialUserInfo call for type " + requestType);
                queueOperationLocked(() -> sendGetInitialUserInfo(requestType, receiver));
                return;
            }
        }
        sendGetInitialUserInfo(requestType, receiver);
    }

    private void sendGetInitialUserInfo(int requestType, @NonNull IResultReceiver receiver) {
        Parcel data = Parcel.obtain();
        data.writeInterfaceToken(ICarConstants.CAR_SERVICE_INTERFACE);
        data.writeInt(requestType);
        data.writeInt(mHalTimeoutMs);
        data.writeStrongBinder(receiver.asBinder());
        // void getInitialUserInfo(int requestType, int timeoutMs, in IResultReceiver receiver)
        sendBinderCallToCarService(data, ICarConstants.ICAR_CALL_GET_INITIAL_USER_INFO);
    }

    @VisibleForTesting
    void setInitialUser(@Nullable UserInfo user) {
        synchronized (mLock) {
            if (mCarService == null) {
                if (DBG) Slog.d(TAG, "Queuing setInitialUser() call");
                queueOperationLocked(() -> sendSetInitialUser(user));
                return;
            }
        }
        sendSetInitialUser(user);
    }

    private void sendSetInitialUser(@Nullable UserInfo user) {
        if (DBG) Slog.d(TAG, "sendSetInitialUser(): " + user);
        Parcel data = Parcel.obtain();
        data.writeInterfaceToken(ICarConstants.CAR_SERVICE_INTERFACE);
        data.writeInt(user != null ? user.id : UserHandle.USER_NULL);
        // void setInitialUser(int userId)
        sendBinderCallToCarService(data, ICarConstants.ICAR_CALL_SET_INITIAL_USER);
    }

    private void sendFirstUserUnlocked(@NonNull TargetUser user) {
        long now = System.currentTimeMillis();
        Parcel data = Parcel.obtain();
        data.writeInterfaceToken(ICarConstants.CAR_SERVICE_INTERFACE);
        data.writeInt(user.getUserIdentifier());
        data.writeLong(now);
        data.writeLong(mFirstUnlockedUserDuration);
        data.writeInt(mHalResponseTime);
        // void onFirstUserUnlocked(int userId, long timestamp, long duration, int halResponseTime)
        sendBinderCallToCarService(data, ICarConstants.ICAR_CALL_FIRST_USER_UNLOCKED);
    }

    private void sendBinderCallToCarService(Parcel data, int callNumber) {
        // Cannot depend on ICar which is defined in CarService, so handle binder call directly
        // instead.
        IBinder carService;
        synchronized (mLock) {
            carService = mCarService;
        }
        if (carService == null) {
            Slog.w(TAG, "Not calling txn " + callNumber + " because service is not bound yet",
                    new Exception());
            return;
        }
        int code = IBinder.FIRST_CALL_TRANSACTION + callNumber;
        try {
            if (VERBOSE) Slog.v(TAG, "calling one-way binder transaction with code " + code);
            carService.transact(code, data, null, Binder.FLAG_ONEWAY);
            if (VERBOSE) Slog.v(TAG, "finished one-way binder transaction with code " + code);
        } catch (RemoteException e) {
            Slog.w(TAG, "RemoteException from car service", e);
            handleCarServiceCrash();
        } catch (RuntimeException e) {
            Slog.wtf(TAG, "Exception calling binder transaction " + callNumber + " (real code: "
                    + code + ")", e);
            throw e;
        } finally {
            data.recycle();
        }
    }

    // Adapted from frameworks/base/services/core/java/com/android/server/Watchdog.java
    // TODO(b/131861630) use implementation common with Watchdog.java
    //
    private static ArrayList<Integer> getInterestingHalPids() {
        try {
            IServiceManager serviceManager = IServiceManager.getService();
            ArrayList<IServiceManager.InstanceDebugInfo> dump =
                    serviceManager.debugDump();
            HashSet<Integer> pids = new HashSet<>();
            for (IServiceManager.InstanceDebugInfo info : dump) {
                if (info.pid == IServiceManager.PidConstant.NO_PID) {
                    continue;
                }

                if (Watchdog.HAL_INTERFACES_OF_INTEREST.contains(info.interfaceName) ||
                        CAR_HAL_INTERFACES_OF_INTEREST.contains(info.interfaceName)) {
                    pids.add(info.pid);
                }
            }

            return new ArrayList<Integer>(pids);
        } catch (RemoteException e) {
            return new ArrayList<Integer>();
        }
    }

    // Adapted from frameworks/base/services/core/java/com/android/server/Watchdog.java
    // TODO(b/131861630) use implementation common with Watchdog.java
    //
    private static ArrayList<Integer> getInterestingNativePids() {
        ArrayList<Integer> pids = getInterestingHalPids();

        int[] nativePids = Process.getPidsForCommands(Watchdog.NATIVE_STACKS_OF_INTEREST);
        if (nativePids != null) {
            pids.ensureCapacity(pids.size() + nativePids.length);
            for (int i : nativePids) {
                pids.add(i);
            }
        }

        return pids;
    }

    // Borrowed from Watchdog.java.  Create an ANR file from the call stacks.
    //
    private static void dumpServiceStacks() {
        ArrayList<Integer> pids = new ArrayList<>();
        pids.add(Process.myPid());

        ActivityManagerService.dumpStackTraces(
                pids, null, null, getInterestingNativePids(), null);
    }

    @VisibleForTesting
    void handleCarServiceCrash() {
        // Recovery behavior.  Kill the system server and reset
        // everything if enabled by the property.
        boolean restartOnServiceCrash = SystemProperties.getBoolean(PROP_RESTART_RUNTIME, false);

        dumpServiceStacks();
        if (restartOnServiceCrash) {
            Slog.w(TAG, "*** CARHELPER KILLING SYSTEM PROCESS: " + "CarService crash");
            Slog.w(TAG, "*** GOODBYE!");
            Process.killProcess(Process.myPid());
            System.exit(10);
        } else {
            Slog.w(TAG, "*** CARHELPER ignoring: " + "CarService crash");
        }
        synchronized (mLock) {
            mCarServiceHasCrashed = true;
        }
    }

    private void handleClientsNotResponding(@NonNull int[] pids) {
        mProcessTerminator.requestTerminateProcess(pids);
    }

    private void registerMonitorToWatchdogDaemon() {
        try {
            mCarWatchdogDaemonHelper.registerMonitor(mCarWatchdogMonitor);
        } catch (RemoteException | RuntimeException e) {
            Slog.w(TAG, "Cannot register to car watchdog daemon: " + e);
        }
    }

    private void killProcessAndReportToMonitor(int pid) {
        String processName = getProcessName(pid);
        Process.killProcess(pid);
        Slog.w(TAG, "carwatchdog killed " + processName + " (pid: " + pid + ")");
        try {
            mCarWatchdogDaemonHelper.tellDumpFinished(mCarWatchdogMonitor, pid);
        } catch (RemoteException | RuntimeException e) {
            Slog.w(TAG, "Cannot report monitor result to car watchdog daemon: " + e);
        }
    }

    private static String getProcessName(int pid) {
        String unknownProcessName = "unknown process";
        String filename = "/proc/" + pid + "/cmdline";
        try (BufferedReader reader = new BufferedReader(new FileReader(filename))) {
            String line = reader.readLine().replace('\0', ' ').trim();
            int index = line.indexOf(' ');
            if (index != -1) {
                line = line.substring(0, index);
            }
            return Paths.get(line).getFileName().toString();
        } catch (IOException e) {
            Slog.w(TAG, "Cannot read " + filename);
            return unknownProcessName;
        }
    }

    private static native int nativeForceSuspend(int timeoutMs);

    private class ICarServiceHelperImpl extends ICarServiceHelper.Stub {
        /**
         * Force device to suspend
         */
        @Override // Binder call
        public int forceSuspend(int timeoutMs) {
            int retVal;
            mContext.enforceCallingOrSelfPermission(android.Manifest.permission.DEVICE_POWER, null);
            final long ident = Binder.clearCallingIdentity();
            try {
                retVal = nativeForceSuspend(timeoutMs);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
            return retVal;
        }

        @Override
        public void setDisplayWhitelistForUser(@UserIdInt int userId, int[] displayIds) {
            mCarLaunchParamsModifier.setDisplayWhitelistForUser(userId, displayIds);
        }

        @Override
        public void setPassengerDisplays(int[] displayIdsForPassenger) {
            mCarLaunchParamsModifier.setPassengerDisplays(displayIdsForPassenger);
        }

        @Override
        public void setSourcePreferredComponents(boolean enableSourcePreferred,
                @Nullable List<ComponentName> sourcePreferredComponents) {
            mCarLaunchParamsModifier.setSourcePreferredComponents(
                    enableSourcePreferred, sourcePreferredComponents);
        }
    }

    private class ICarWatchdogMonitorImpl extends ICarWatchdogMonitor.Stub {
        private final WeakReference<CarServiceHelperService> mService;

        private ICarWatchdogMonitorImpl(CarServiceHelperService service) {
            mService = new WeakReference<>(service);
        }

        @Override
        public void onClientsNotResponding(int[] pids) {
            CarServiceHelperService service = mService.get();
            if (service == null || pids == null || pids.length == 0) {
                return;
            }
            service.handleClientsNotResponding(pids);
        }

        @Override
        public int getInterfaceVersion() {
            return this.VERSION;
        }

        @Override
        public String getInterfaceHash() {
            return this.HASH;
        }
    }

    private final class ProcessTerminator {

        private static final long ONE_SECOND_MS = 1_000L;

        private final Object mProcessLock = new Object();
        private ExecutorService mExecutor;
        @GuardedBy("mProcessLock")
        private int mQueuedTask;

        public void requestTerminateProcess(@NonNull int[] pids) {
            synchronized (mProcessLock) {
                // If there is a running thread, we re-use it instead of starting a new thread.
                if (mExecutor == null) {
                    mExecutor = Executors.newSingleThreadExecutor();
                }
                mQueuedTask++;
            }
            mExecutor.execute(() -> {
                for (int pid : pids) {
                    dumpAndKillProcess(pid);
                }
                // mExecutor will be stopped from the main thread, if there is no queued task.
                mHandler.sendMessage(obtainMessage(ProcessTerminator::postProcessing, this)
                        .setWhat(WHAT_POST_PROCESS_DUMPING));
            });
        }

        private void postProcessing() {
            synchronized (mProcessLock) {
                mQueuedTask--;
                if (mQueuedTask == 0) {
                    mExecutor.shutdown();
                    mExecutor = null;
                }
            }
        }

        private void dumpAndKillProcess(int pid) {
            if (DBG) {
                Slog.d(TAG, "Dumping and killing process(pid: " + pid + ")");
            }
            ArrayList<Integer> javaPids = new ArrayList<>(1);
            ArrayList<Integer> nativePids = new ArrayList<>();
            try {
                if (isJavaApp(pid)) {
                    javaPids.add(pid);
                } else {
                    nativePids.add(pid);
                }
            } catch (IOException e) {
                Slog.w(TAG, "Cannot get process information: " + e);
                return;
            }
            nativePids.addAll(getInterestingNativePids());
            long startDumpTime = SystemClock.uptimeMillis();
            ActivityManagerService.dumpStackTraces(javaPids, null, null, nativePids, null);
            long dumpTime = SystemClock.uptimeMillis() - startDumpTime;
            if (DBG) {
                Slog.d(TAG, "Dumping process took " + dumpTime + "ms");
            }
            // To give clients a chance of wrapping up before the termination.
            if (dumpTime < ONE_SECOND_MS) {
                mHandler.sendMessageDelayed(obtainMessage(
                        CarServiceHelperService::killProcessAndReportToMonitor,
                        CarServiceHelperService.this, pid).setWhat(WHAT_PROCESS_KILL),
                        ONE_SECOND_MS - dumpTime);
            } else {
                killProcessAndReportToMonitor(pid);
            }
        }

        private boolean isJavaApp(int pid) throws IOException {
            Path exePath = new File("/proc/" + pid + "/exe").toPath();
            String target = Files.readSymbolicLink(exePath).toString();
            // Zygote's target exe is also /system/bin/app_process32 or /system/bin/app_process64.
            // But, we can be very sure that Zygote will not be the client of car watchdog daemon.
            return target == "/system/bin/app_process32" || target == "/system/bin/app_process64";
        }
    }
}
