/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.server;

import static android.os.IServiceManager.DUMP_FLAG_PRIORITY_DEFAULT;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.annotation.SystemApi.Client;
import android.annotation.UserIdInt;
import android.app.ActivityThread;
import android.content.Context;
import android.content.pm.UserInfo;
import android.os.IBinder;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.os.UserManager;

import com.android.server.pm.UserManagerService;

import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;

/**
 * The base class for services running in the system process. Override and implement
 * the lifecycle event callback methods as needed.
 * <p>
 * The lifecycle of a SystemService:
 * </p><ul>
 * <li>The constructor is called and provided with the system {@link Context}
 * to initialize the system service.
 * <li>{@link #onStart()} is called to get the service running.  The service should
 * publish its binder interface at this point using
 * {@link #publishBinderService(String, IBinder)}.  It may also publish additional
 * local interfaces that other services within the system server may use to access
 * privileged internal functions.
 * <li>Then {@link #onBootPhase(int)} is called as many times as there are boot phases
 * until {@link #PHASE_BOOT_COMPLETED} is sent, which is the last boot phase. Each phase
 * is an opportunity to do special work, like acquiring optional service dependencies,
 * waiting to see if SafeMode is enabled, or registering with a service that gets
 * started after this one.
 * </ul><p>
 * NOTE: All lifecycle methods are called from the system server's main looper thread.
 * </p>
 *
 * {@hide}
 */
@SystemApi(client = Client.SYSTEM_SERVER)
public abstract class SystemService {

    /** @hide */
    protected static final boolean DEBUG_USER = false;

    /**
     * The earliest boot phase the system send to system services on boot.
     */
    public static final int PHASE_WAIT_FOR_DEFAULT_DISPLAY = 100;

    /**
     * Boot phase that blocks on SensorService availability. The service gets started
     * asynchronously since it may take awhile to actually finish initializing.
     *
     * @hide
     */
    public static final int PHASE_WAIT_FOR_SENSOR_SERVICE = 200;

    /**
     * After receiving this boot phase, services can obtain lock settings data.
     */
    public static final int PHASE_LOCK_SETTINGS_READY = 480;

    /**
     * After receiving this boot phase, services can safely call into core system services
     * such as the PowerManager or PackageManager.
     */
    public static final int PHASE_SYSTEM_SERVICES_READY = 500;

    /**
     * After receiving this boot phase, services can safely call into device specific services.
     */
    public static final int PHASE_DEVICE_SPECIFIC_SERVICES_READY = 520;

    /**
     * After receiving this boot phase, services can broadcast Intents.
     */
    public static final int PHASE_ACTIVITY_MANAGER_READY = 550;

    /**
     * After receiving this boot phase, services can start/bind to third party apps.
     * Apps will be able to make Binder calls into services at this point.
     */
    public static final int PHASE_THIRD_PARTY_APPS_CAN_START = 600;

    /**
     * After receiving this boot phase, services can allow user interaction with the device.
     * This phase occurs when boot has completed and the home application has started.
     * System services may prefer to listen to this phase rather than registering a
     * broadcast receiver for {@link android.content.Intent#ACTION_LOCKED_BOOT_COMPLETED}
     * to reduce overall latency.
     */
    public static final int PHASE_BOOT_COMPLETED = 1000;

    /** @hide */
    @IntDef(flag = true, prefix = { "PHASE_" }, value = {
            PHASE_WAIT_FOR_DEFAULT_DISPLAY,
            PHASE_LOCK_SETTINGS_READY,
            PHASE_SYSTEM_SERVICES_READY,
            PHASE_DEVICE_SPECIFIC_SERVICES_READY,
            PHASE_ACTIVITY_MANAGER_READY,
            PHASE_THIRD_PARTY_APPS_CAN_START,
            PHASE_BOOT_COMPLETED
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface BootPhase {}

    private final Context mContext;

    /**
     * Class representing user in question in the lifecycle callbacks.
     * @hide
     */
    @SystemApi(client = Client.SYSTEM_SERVER)
    public static final class TargetUser {

        // NOTE: attributes below must be immutable while ther user is running (i.e., from the
        // moment it's started until after it's shutdown).
        private final @UserIdInt int mUserId;
        private final boolean mFull;
        private final boolean mManagedProfile;
        private final boolean mPreCreated;

        /** @hide */
        public TargetUser(@NonNull UserInfo userInfo) {
            mUserId = userInfo.id;
            mFull = userInfo.isFull();
            mManagedProfile = userInfo.isManagedProfile();
            mPreCreated = userInfo.preCreated;
        }

        /**
         * Checks if the target user is {@link UserInfo#isFull() full}.
         *
         * @hide
         */
        public boolean isFull() {
            return mFull;
        }

        /**
         * Checks if the target user is a managed profile.
         *
         * @hide
         */
        public boolean isManagedProfile() {
            return mManagedProfile;
        }

        /**
         * Checks if the target user is a pre-created user.
         *
         * @hide
         */
        public boolean isPreCreated() {
            return mPreCreated;
        }

        /**
         * Gets the target user's {@link UserHandle}.
         */
        @NonNull
        public UserHandle getUserHandle() {
            return UserHandle.of(mUserId);
        }

        /**
         * Gets the target user's id.
         *
         * @hide
         */
        public @UserIdInt int getUserIdentifier() {
            return mUserId;
        }

        @Override
        public String toString() {
            return Integer.toString(mUserId);
        }

        /**
         * @hide
         */
        public void dump(@NonNull PrintWriter pw) {
            pw.print(getUserIdentifier());

            if (!isFull() && !isManagedProfile()) return;

            pw.print('(');
            boolean addComma = false;
            if (isFull()) {
                pw.print("full");
            }
            if (isManagedProfile()) {
                if (addComma) pw.print(',');
                pw.print("mp");
            }
            pw.print(')');
        }
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
    public SystemService(@NonNull Context context) {
        mContext = context;
    }

    /**
     * Gets the system context.
     */
    @NonNull
    public final Context getContext() {
        return mContext;
    }

    /**
     * Get the system UI context. This context is to be used for displaying UI. It is themable,
     * which means resources can be overridden at runtime. Do not use to retrieve properties that
     * configure the behavior of the device that is not UX related.
     *
     * @hide
     */
    public final Context getUiContext() {
        // This has already been set up by the time any SystemServices are created.
        return ActivityThread.currentActivityThread().getSystemUiContext();
    }

    /**
     * Returns true if the system is running in safe mode.
     * TODO: we should define in which phase this becomes valid
     *
     * @hide
     */
    public final boolean isSafeMode() {
        return getManager().isSafeMode();
    }

    /**
     * Called when the system service should publish a binder service using
     * {@link #publishBinderService(String, IBinder).}
     */
    public abstract void onStart();

    /**
     * Called on each phase of the boot process. Phases before the service's start phase
     * (as defined in the @Service annotation) are never received.
     *
     * @param phase The current boot phase.
     */
    public void onBootPhase(@BootPhase int phase) {}

    /**
     * Checks if the service should be available for the given user.
     *
     * <p>By default returns {@code true}, but subclasses should extend for optimization, if they
     * don't support some types (like headless system user).
     */
    public boolean isUserSupported(@NonNull TargetUser user) {
        return true;
    }

    /**
     * Helper method used to dump which users are {@link #onUserStarting(TargetUser) supported}.
     *
     * @hide
     */
    protected void dumpSupportedUsers(@NonNull PrintWriter pw, @NonNull String prefix) {
        final List<UserInfo> allUsers = UserManager.get(mContext).getUsers();
        final List<Integer> supportedUsers = new ArrayList<>(allUsers.size());
        for (UserInfo user : allUsers) {
            supportedUsers.add(user.id);
        }
        if (allUsers.isEmpty()) {
            pw.print(prefix); pw.println("No supported users");
        } else {
            final int size = supportedUsers.size();
            pw.print(prefix); pw.print(size); pw.print(" supported user");
            if (size > 1) pw.print("s");
            pw.print(": "); pw.println(supportedUsers);
        }
    }

    /**
     * Called when a new user is starting, for system services to initialize any per-user
     * state they maintain for running users.
     *
     * <p>This method is only called when the service {@link #isUserSupported(TargetUser) supports}
     * this user.
     *
     * @param user target user
     */
    public void onUserStarting(@NonNull TargetUser user) {
    }

    /**
     * Called when an existing user is in the process of being unlocked. This
     * means the credential-encrypted storage for that user is now available,
     * and encryption-aware component filtering is no longer in effect.
     * <p>
     * While dispatching this event to services, the user is in the
     * {@code STATE_RUNNING_UNLOCKING} state, and once dispatching is finished
     * the user will transition into the {@code STATE_RUNNING_UNLOCKED} state.
     * Code written inside system services should use
     * {@link UserManager#isUserUnlockingOrUnlocked(int)} to handle both of
     * these states, or use {@link #onUserUnlocked(TargetUser)} instead.
     * <p>
     * This method is only called when the service {@link #isUserSupported(TargetUser) supports}
     * this user.
     *
     * @param user target user
     */
    public void onUserUnlocking(@NonNull TargetUser user) {
    }

    /**
     * Called after an existing user is unlocked.
     *
     * <p>This method is only called when the service {@link #isUserSupported(TargetUser) supports}
     * this user.
     *
     * @param user target user
     */
    public void onUserUnlocked(@NonNull TargetUser user) {
    }

    /**
     * Called when switching to a different foreground user, for system services that have
     * special behavior for whichever user is currently in the foreground.  This is called
     * before any application processes are aware of the new user.
     *
     * <p>This method is only called when the service {@link #isUserSupported(TargetUser) supports}
     * either of the users ({@code from} or {@code to}).
     *
     * <b>NOTE: </b> both {@code from} and {@code to} are "live" objects
     * referenced by {@link UserManagerService} and hence should not be modified.
     *
     * @param from the user switching from
     * @param to the user switching to
     */
    public void onUserSwitching(@Nullable TargetUser from, @NonNull TargetUser to) {
    }

    /**
     * Called when an existing user is stopping, for system services to finalize any per-user
     * state they maintain for running users.  This is called prior to sending the SHUTDOWN
     * broadcast to the user; it is a good place to stop making use of any resources of that
     * user (such as binding to a service running in the user).
     *
     * <p>This method is only called when the service {@link #isUserSupported(TargetUser) supports}
     * this user.
     *
     * <p>NOTE: This is the last callback where the callee may access the target user's CE storage.
     *
     * @param user target user
     */
    public void onUserStopping(@NonNull TargetUser user) {
    }

    /**
     * Called after an existing user is stopped.
     *
     * <p>This is called after all application process teardown of the user is complete.
     *
     * <p>This method is only called when the service {@link #isUserSupported(TargetUser) supports}
     * this user.
     *
     * @param user target user
     */
    public void onUserStopped(@NonNull TargetUser user) {
    }

    /**
     * Publish the service so it is accessible to other services and apps.
     *
     * @param name the name of the new service
     * @param service the service object
     */
    protected final void publishBinderService(@NonNull String name, @NonNull IBinder service) {
        publishBinderService(name, service, false);
    }

    /**
     * Publish the service so it is accessible to other services and apps.
     *
     * @param name the name of the new service
     * @param service the service object
     * @param allowIsolated set to true to allow isolated sandboxed processes
     * to access this service
     */
    protected final void publishBinderService(@NonNull String name, @NonNull IBinder service,
            boolean allowIsolated) {
        publishBinderService(name, service, allowIsolated, DUMP_FLAG_PRIORITY_DEFAULT);
    }

    /**
     * Publish the service so it is accessible to other services and apps.
     *
     * @param name the name of the new service
     * @param service the service object
     * @param allowIsolated set to true to allow isolated sandboxed processes
     * to access this service
     * @param dumpPriority supported dump priority levels as a bitmask
     *
     * @hide
     */
    protected final void publishBinderService(String name, IBinder service,
            boolean allowIsolated, int dumpPriority) {
        ServiceManager.addService(name, service, allowIsolated, dumpPriority);
    }

    /**
     * Get a binder service by its name.
     *
     * @hide
     */
    protected final IBinder getBinderService(String name) {
        return ServiceManager.getService(name);
    }

    /**
     * Publish the service so it is only accessible to the system process.
     *
     * @hide
     */
    protected final <T> void publishLocalService(Class<T> type, T service) {
        LocalServices.addService(type, service);
    }

    /**
     * Get a local service by interface.
     *
     * @hide
     */
    protected final <T> T getLocalService(Class<T> type) {
        return LocalServices.getService(type);
    }

    private SystemServiceManager getManager() {
        return LocalServices.getService(SystemServiceManager.class);
    }
}
