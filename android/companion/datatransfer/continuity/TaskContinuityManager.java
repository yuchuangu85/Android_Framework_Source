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

package android.companion.datatransfer.continuity;

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.annotation.UserHandleAware;
import android.content.Context;
import android.os.RemoteException;
import android.os.Trace;
import android.util.ArrayMap;
import com.android.internal.annotations.GuardedBy;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * This class facilitates task continuity between devices owned by the same user. This includes
 * synchronizing lists of open tasks between a user's devices, as well as requesting to hand off a
 * task from one device to another. Handing a task off to a device will resume the application on
 * the receiving device, preserving the state of the task.
 *
 * @hide
 */
@FlaggedApi(android.companion.Flags.FLAG_TASK_CONTINUITY)
@SystemService(Context.TASK_CONTINUITY_SERVICE)
@SystemApi
public class TaskContinuityManager {
    private final Context mContext;
    private final ITaskContinuityManager mService;

    private final ArrayMap<RemoteTaskListener, RemoteTaskListenerHolder>
            mRemoteTaskListenerHolders = new ArrayMap<>();
    private final ArrayMap<HandoffFeatureStateListener, HandoffFeatureStateListenerHolder>
            mHandoffFeatureStateListenerHolders = new ArrayMap<>();

    /** @hide */
    @IntDef(
            prefix = {"HANDOFF_AVAILABILITY_STATUS"},
            value = {
                HANDOFF_AVAILABILITY_STATUS_AVAILABLE,
                HANDOFF_AVAILABILITY_STATUS_DISABLED_BY_POLICY,
                HANDOFF_AVAILABILITY_STATUS_UNSUPPORTED_HARDWARE,
            })
    @Retention(RetentionPolicy.SOURCE)
    public @interface HandoffAvailabilityStatus {}

    /**
     * Indicates that handoff is available for the current device. The current device will only
     * display tasks on remote devices when Handoff is available and enabled, and is only capable of
     * both receiving and sending Handoff requests when available and enabled.
     *
     * @see #setHandoffForDeviceEnabled(boolean)
     */
    public static final int HANDOFF_AVAILABILITY_STATUS_AVAILABLE = 0;

    /**
     * Indicates that handoff is unavailable for the current device because it is blocked
     * system-wide by an enterprise policy.
     */
    public static final int HANDOFF_AVAILABILITY_STATUS_DISABLED_BY_POLICY = 1;

    /**
     * Indicates that handoff is unavailable for the current device because it is not supported on
     * the hardware.
     */
    public static final int HANDOFF_AVAILABILITY_STATUS_UNSUPPORTED_HARDWARE = 2;

    /** @hide */
    @IntDef(
            prefix = {"HANDOFF_REQUEST_RESULT"},
            value = {
                HANDOFF_REQUEST_RESULT_SUCCESS,
                HANDOFF_REQUEST_RESULT_FAILURE_TASK_NOT_FOUND,
                HANDOFF_REQUEST_RESULT_FAILURE_NO_DATA_PROVIDED_BY_TASK,
                HANDOFF_REQUEST_RESULT_FAILURE_SENDER_LOST_CONNECTION,
                HANDOFF_REQUEST_RESULT_FAILURE_TIMEOUT,
                HANDOFF_REQUEST_RESULT_FAILURE_DEVICE_NOT_FOUND,
                HANDOFF_REQUEST_RESULT_FAILURE_HANDOFF_DISABLED,
                HANDOFF_REQUEST_RESULT_FAILURE_OTHER_INTERNAL_ERROR,
            })
    @Retention(RetentionPolicy.SOURCE)
    public @interface HandoffRequestResultCode {}

    /** Indicate a request for handoff completed successfully. */
    public static final int HANDOFF_REQUEST_RESULT_SUCCESS = 0;

    /**
     * Indicates a request for handoff failed because a remote task with the specified ID was not
     * found on the remote device.
     */
    public static final int HANDOFF_REQUEST_RESULT_FAILURE_TASK_NOT_FOUND = 1;

    /**
     * Indicates a request for handoff failed because the remote task did not provide any data to
     * hand itself off to the current device.
     */
    public static final int HANDOFF_REQUEST_RESULT_FAILURE_NO_DATA_PROVIDED_BY_TASK = 2;

    /**
     * Indicates a request for handoff failed because the connection to the remote device was lost
     * before the request could be completed.
     */
    public static final int HANDOFF_REQUEST_RESULT_FAILURE_SENDER_LOST_CONNECTION = 3;

    /**
     * Indicates a request for handoff failed because the request timed out before it could be
     * completed.
     */
    public static final int HANDOFF_REQUEST_RESULT_FAILURE_TIMEOUT = 4;

    /** Indicates a request for handoff failed because the remote device was not found. */
    public static final int HANDOFF_REQUEST_RESULT_FAILURE_DEVICE_NOT_FOUND = 5;

    /**
     * Indicates a request for handoff failed because of an internal error outside of Handoff's data
     * transfer flow.
     */
    public static final int HANDOFF_REQUEST_RESULT_FAILURE_OTHER_INTERNAL_ERROR = 6;

    /** Indicates a request for handoff failed because handoff is disabled on the current device. */
    public static final int HANDOFF_REQUEST_RESULT_FAILURE_HANDOFF_DISABLED = 7;

    /** @hide */
    public TaskContinuityManager(
            @NonNull Context context, @NonNull ITaskContinuityManager service) {

        mContext = context;
        mService = service;
    }

    /** Listener to be notified when the list of remote tasks changes. */
    public interface RemoteTaskListener {
        /**
         * Invoked when the list of remote tasks changes.
         *
         * @param remoteTasks The list of remote tasks.
         */
        void onRemoteTasksChanged(@NonNull List<RemoteTask> remoteTasks);
    }

    /**
     * Listener to the feature state of Handoff on the current device. Feature state includes
     * whether Handoff is available on the current device, as well is if it is currently enabled.
     * Handoff may be unavailable on the device due to unsupported hardware or enterprise policy.
     * See #setHandoffForDeviceEnabled(boolean) for more details.
     */
    public interface HandoffFeatureStateListener {
        /**
         * Invoked when the feature state of Handoff changes.
         *
         * @param availabilityStatus The availability status of Handoff on the current device.
         * @param enabled Whether Handoff is enabled on the current device.
         */
        void onHandoffFeatureStateChanged(
                @HandoffAvailabilityStatus int availabilityStatus, boolean enabled);
    }

    /** Callback to be invoked when a handoff request is completed. */
    public interface HandoffRequestCallback {

        /**
         * Invoked when a request to hand off a remote task has finished.
         *
         * @param associationId The ID of the association to which the remote device is connected.
         * @param remoteTaskId The ID of the task that was requested to be handed off.
         * @param resultCode The result code of the handoff request.
         */
        void onHandoffRequestFinished(
                int associationId, int remoteTaskId, @HandoffRequestResultCode int resultCode);
    }

    /**
     * Registers a listener to be notified when the list of remote tasks changes.
     *
     * @param executor The executor to be used to invoke the listener.
     * @param listener The listener to be registered.
     */
    @RequiresPermission(android.Manifest.permission.READ_REMOTE_TASKS)
    @UserHandleAware
    public void registerRemoteTaskListener(
            @NonNull Executor executor, @NonNull RemoteTaskListener listener) {

        Objects.requireNonNull(executor);
        Objects.requireNonNull(listener);

        try {
            synchronized (mRemoteTaskListenerHolders) {
                if (!mRemoteTaskListenerHolders.containsKey(listener)) {
                    RemoteTaskListenerHolder holder =
                            new RemoteTaskListenerHolder(executor, listener);
                    mService.registerRemoteTaskListener(mContext.getUserId(), holder);
                    mRemoteTaskListenerHolders.put(listener, holder);
                }
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Unregisters a listener previously registered with {@link #registerRemoteTaskListener}.
     *
     * @param listener The listener to be unregistered.
     */
    @RequiresPermission(android.Manifest.permission.READ_REMOTE_TASKS)
    @UserHandleAware
    public void unregisterRemoteTaskListener(@NonNull RemoteTaskListener listener) {
        Objects.requireNonNull(listener);

        try {
            synchronized (mRemoteTaskListenerHolders) {
                RemoteTaskListenerHolder holder = mRemoteTaskListenerHolders.get(listener);
                if (holder == null) {
                    return;
                }

                mService.unregisterRemoteTaskListener(mContext.getUserId(), holder);
                mRemoteTaskListenerHolders.remove(listener);
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Requests a handoff of the specified remote task to the current device.
     *
     * @param associationId The ID of the association to which the remote device is connected. This
     *     is the same ID returned by {@link RemoteTask#getDeviceId()}.
     * @param remoteTaskId The remote task to hand off.
     * @param executor The executor to be used to invoke the callback.
     * @param callback The callback to be invoked when the handoff request is finished.
     * @throws SecurityException if the caller does not hold the {@link
     *     android.Manifest.permission#REQUEST_TASK_HANDOFF} permission.
     */
    @RequiresPermission(android.Manifest.permission.REQUEST_TASK_HANDOFF)
    @UserHandleAware
    public void requestHandoff(
            int associationId,
            int remoteTaskId,
            @NonNull Executor executor,
            @NonNull HandoffRequestCallback callback) {

        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);

        Trace.asyncTraceBegin(
                Trace.TRACE_TAG_SYSTEM_SERVER, "requestHandoff", callback.hashCode());

        try {
            HandoffRequestCallbackHolder callbackHolder =
                    new HandoffRequestCallbackHolder(executor, callback);

            int userId = mContext.getUserId();
            mService.requestHandoff(userId, associationId, remoteTaskId, callbackHolder);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Enables or disables handoff for the current device if Handoff is available on the current
     * device. If Handoff is not available, this operation will have no effect. By default, Handoff
     * is enabled. This method will also notify registered listeners from {@link
     * #registerHandoffFeatureStateListener} if the enablement status has changed.
     *
     * @param enabled Whether handoff should be enabled or disabled.
     */
    @UserHandleAware
    @RequiresPermission(android.Manifest.permission.MODIFY_HANDOFF_SETTINGS)
    public void setHandoffForDeviceEnabled(boolean enabled) {
        try {
            int userId = mContext.getUserId();
            mService.setHandoffForDeviceEnabled(userId, enabled);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Registers a listener to be notified when the handoff's feature state changes.
     *
     * @param executor The executor to be used to invoke the listener.
     * @param listener The listener to be registered.
     * @throws SecurityException if the caller does not hold the {@link
     *     android.Manifest.permission#READ_HANDOFF_SETTINGS} permission.
     */
    @RequiresPermission(android.Manifest.permission.READ_HANDOFF_SETTINGS)
    @UserHandleAware
    public void registerHandoffFeatureStateListener(
            @NonNull Executor executor, @NonNull HandoffFeatureStateListener listener) {
        Objects.requireNonNull(executor);
        Objects.requireNonNull(listener);

        try {
            synchronized (mHandoffFeatureStateListenerHolders) {
                if (!mHandoffFeatureStateListenerHolders.containsKey(listener)) {
                    HandoffFeatureStateListenerHolder holder =
                            new HandoffFeatureStateListenerHolder(executor, listener);
                    mService.registerHandoffFeatureStateListener(mContext.getUserId(), holder);
                    mHandoffFeatureStateListenerHolders.put(listener, holder);
                }
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Unregisters a listener previously registered with {@link
     * #registerHandoffFeatureStateListener}.
     *
     * @param listener The listener to be unregistered.
     * @throws SecurityException if the caller does not hold the {@link
     *     android.Manifest.permission#READ_HANDOFF_SETTINGS} permission.
     */
    @RequiresPermission(android.Manifest.permission.READ_HANDOFF_SETTINGS)
    @UserHandleAware
    public void unregisterHandoffFeatureStateListener(
            @NonNull HandoffFeatureStateListener listener) {
        Objects.requireNonNull(listener);

        try {
            synchronized (mHandoffFeatureStateListenerHolders) {
                HandoffFeatureStateListenerHolder holder =
                        mHandoffFeatureStateListenerHolders.get(listener);
                if (holder == null) {
                    return;
                }

                mService.unregisterHandoffFeatureStateListener(mContext.getUserId(), holder);
                mHandoffFeatureStateListenerHolders.remove(listener);
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    private final class HandoffRequestCallbackHolder extends IHandoffRequestCallback.Stub {
        private final Executor mExecutor;
        private final HandoffRequestCallback mCallback;

        HandoffRequestCallbackHolder(
                @NonNull Executor executor, @NonNull HandoffRequestCallback callback) {

            mExecutor = executor;
            mCallback = callback;
        }

        @Override
        public void onHandoffRequestFinished(
                int associationId, int remoteTaskId, @HandoffRequestResultCode int resultCode)
                throws RemoteException {
            mExecutor.execute(
                    () -> {
                        mCallback.onHandoffRequestFinished(associationId, remoteTaskId, resultCode);
                        Trace.asyncTraceEnd(
                                Trace.TRACE_TAG_SYSTEM_SERVER,
                                "requestHandoff",
                                mCallback.hashCode());
                    });
        }
    }

    private final class HandoffFeatureStateListenerHolder
            extends IHandoffFeatureStateListener.Stub {

        @GuardedBy("this")
        private final Executor mExecutor;

        @GuardedBy("this")
        private final HandoffFeatureStateListener mListener;

        HandoffFeatureStateListenerHolder(
                @NonNull Executor executor, @NonNull HandoffFeatureStateListener listener) {
            mExecutor = Objects.requireNonNull(executor);
            mListener = Objects.requireNonNull(listener);
        }

        @Override
        public void onHandoffFeatureStateChanged(
                @HandoffAvailabilityStatus int availabilityStatus, boolean enabled)
                throws RemoteException {
            synchronized (this) {
                mExecutor.execute(
                        () -> mListener.onHandoffFeatureStateChanged(availabilityStatus, enabled));
            }
        }
    }

    /**
     * Helper class which manages registered listeners and proxies them behind a single
     * IRemoteTaskListener, which is lazily registered with ITaskContinuityManager if there is a
     * single registered listener.
     */
    private final class RemoteTaskListenerHolder extends IRemoteTaskListener.Stub {

        @GuardedBy("this")
        private final Executor mExecutor;

        @GuardedBy("this")
        private final RemoteTaskListener mListener;

        RemoteTaskListenerHolder(@NonNull Executor executor, @NonNull RemoteTaskListener listener) {
            mExecutor = Objects.requireNonNull(executor);
            mListener = Objects.requireNonNull(listener);
        }

        @Override
        public void onRemoteTasksChanged(List<RemoteTask> remoteTasks) throws RemoteException {
            synchronized (this) {
                mExecutor.execute(() -> mListener.onRemoteTasksChanged(remoteTasks));
            }
        }
    }
}
