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

package android.hardware.contexthub;

import android.annotation.CallbackExecutor;
import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.Size;
import android.annotation.SystemApi;
import android.chre.flags.Flags;
import android.content.Context;
import android.hardware.contexthub.HubEndpointInfo.HubEndpointIdentifier;
import android.hardware.location.ContextHubTransaction;
import android.hardware.location.ContextHubTransactionHelper;
import android.hardware.location.IContextHubService;
import android.hardware.location.IContextHubTransactionCallback;
import android.os.Looper;
import android.os.MessageQueue;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.util.Log;
import android.util.SparseArray;

import androidx.annotation.GuardedBy;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * An object representing an endpoint exposed to ContextHub and VendorHub. The object encapsulates
 * the lifecycle and message callbacks for an endpoint.
 *
 * @hide
 */
@SystemApi
@FlaggedApi(Flags.FLAG_OFFLOAD_API)
public class HubEndpoint {
    private static final String TAG = "HubEndpoint";

    /**
     * Constants describing the outcome of operations through HubEndpoints (like opening/closing of
     * sessions or stopping of endpoints).
     *
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(
            prefix = {"REASON_"},
            value = {
                REASON_FAILURE,
                REASON_OPEN_ENDPOINT_SESSION_REQUEST_REJECTED,
                REASON_CLOSE_ENDPOINT_SESSION_REQUESTED,
                REASON_ENDPOINT_INVALID,
                REASON_ENDPOINT_STOPPED,
                REASON_PERMISSION_DENIED,
            })
    public @interface Reason {}

    /** Unclassified failure */
    public static final int REASON_FAILURE = 0;

    // The values 1-2 are reserved at the Context Hub HAL but not exposed to apps.

    /** The peer rejected the request to open this endpoint session. */
    public static final int REASON_OPEN_ENDPOINT_SESSION_REQUEST_REJECTED = 3;

    /** The peer closed this endpoint session. */
    public static final int REASON_CLOSE_ENDPOINT_SESSION_REQUESTED = 4;

    /** The peer endpoint is invalid. */
    public static final int REASON_ENDPOINT_INVALID = 5;

    // The values 6-8 are reserved at the Context Hub HAL but not exposed to apps.

    /** The endpoint did not have the required permissions. */
    public static final int REASON_PERMISSION_DENIED = 9;

    /**
     * The endpoint is now stopped. The app should retrieve the endpoint info using {@link
     * android.hardware.location.ContextHubManager#findEndpoints} or register updates through {@link
     * android.hardware.location.ContextHubManager#registerEndpointDiscoveryCallback} to get
     * notified if the endpoint restarts.
     */
    public static final int REASON_ENDPOINT_STOPPED = 6;

    /** Indicates a variable sized data flow. */
    private static final int NATIVE_ELEMENT_SIZE_VARIABLE = -1;

    private final Object mLock = new Object();
    private final HubEndpointInfo mPendingHubEndpointInfo;
    @Nullable private final HubEndpointLifecycleCallback mLifecycleCallback;
    @Nullable private final HubEndpointMessageCallback mMessageCallback;
    @Nullable private final DataFlowCallback mDataFlowCallback;
    @NonNull private final Executor mLifecycleCallbackExecutor;
    @NonNull private final Executor mMessageCallbackExecutor;
    @NonNull private final Executor mDataFlowCallbackExecutor;
    @NonNull private final Looper mEpollLooper;

    @GuardedBy("mLock")
    private final SparseArray<HubEndpointSession> mActiveSessions = new SparseArray<>();

    /** The native handle obtained in native_init. */
    private long mNativeHandle = 0;

    /** Callback used to deliver events from JNI thread context. */
    private interface DataFlowJniCallback {
        /**
         * Called when a data flow notification is received from the native NotificationManager.
         *
         * @param hubId The ID of the hub that the producer of this data flow is associated with.
         * @param dataFlowId The ID of the data flow.
         * @param waking Whether the notification is waking.
         */
        void onNotificationCallback(long hubId, int dataFlowId, boolean waking);
    }

    /**
     * Initializes the native code for this HubEndpoint.
     *
     * @param queue The message queue associated with the endpoint's mLooper object.
     * @param callback The callback to invoke from the native layer.
     * @param hubId The hub Id of this endpoint.
     * @return The native handle to be used for all other native calls.
     */
    private native long native_init(MessageQueue queue, DataFlowJniCallback callback, long hubId);

    /**
     * @param nativeHandle The native handle created in native_init.
     * @param regionId The ID of the shared data region.
     * @param regionSize The size of the shared data region in bytes.
     * @param regionFd The file descriptor of the shared data region.
     * @param elementSize The size of each data element in bytes.
     * @param alignment The alignment of each data element in bytes.
     * @param minElementCount The minimum number of elements in the data flow.
     * @param maxElementCount The maximum number of elements in the data flow.
     * @return An int array comprised of [metadataOffset, wakingFds, nonWakingFds, halAckFds] of the
     *     new data flow.
     */
    private native int[] native_createDataFlowInfo(
            long nativeHandle,
            int regionId,
            long regionSize,
            int regionFd,
            int elementSize,
            int alignment,
            int minElementCount,
            int maxElementCount);

    private static final int NATIVE_CREATE_DATA_FLOW_INFO_ARRAY_SIZE = 4;

    /**
     * @param nativeHandle The native handle created in native_init.
     * @param dataFlowId The ID of the data flow.
     * @param regionId The ID of the shared data region.
     * @return True of the activation succeeded.
     */
    private native boolean native_activateDataFlow(long nativeHandle, int dataFlowId, int regionId);

    /**
     * @param nativeHandle The native handle created in native_init.
     * @param regionId The ID of the shared data region.
     * @param regionSize The size of the shared data region in bytes.
     * @param regionFd The file descriptor of the shared data region.
     * @param dataFlowHubId The hub ID of the data flow.
     * @param dataFlowId The ID of the data flow.
     * @param notifyHostFdsWaking The waking file descriptor for host notifications.
     * @param notifyHostFdsNonWaking The non-waking file descriptor for host notifications.
     * @param notifyHostFdsHalAck The HAL acknowledgment file descriptor for host notifications.
     * @param notifyOffloadFdsWaking The waking file descriptor for offload notifications.
     * @param notifyOffloadFdsNonWaking The non-waking file descriptor for offload notifications.
     * @param queueOffset The offset of the queue in the shared memory region.
     * @param metadataOffset The offset of the metadata in the shared memory region.
     * @return An int array comprised of [elementSize, elementAlignment] for this host consumer.
     */
    private native int[] native_enableHostSink(
            long nativeHandle,
            int regionId,
            long regionSize,
            int regionFd,
            long dataFlowHubId,
            int dataFlowId,
            int notifyHostFdsWaking,
            int notifyHostFdsNonWaking,
            int notifyHostFdsHalAck,
            int notifyOffloadFdsWaking,
            int notifyOffloadFdsNonWaking,
            long queueOffset,
            long metadataOffset);

    private static final int NATIVE_ENABLE_HOST_SINK_ARRAY_SIZE = 2;

    /**
     * @param nativeHandle The native handle created in native_init.
     * @param regionId The ID of the shared data region.
     * @param data The data to push.
     * @param allOrNothing If true, all data must be pushed or none.
     * @return The number of elements pushed.
     */
    private native int native_sourcePush(
            long nativeHandle, int regionId, byte[] data, boolean allOrNothing);

    /**
     * @param nativeHandle The native handle created in native_init.
     * @param regionId The ID of the shared data region.
     * @param includeReserved Whether to include reserved space in the size calculation.
     * @return The size of the queue.
     */
    private native int native_sourceSize(long nativeHandle, int regionId, boolean includeReserved);

    /**
     * @param nativeHandle The native handle created in native_init.
     * @param regionId The ID of the shared data region.
     * @return true if the queue is full.
     */
    private native boolean native_sourceFull(long nativeHandle, int regionId);

    /**
     * @param nativeHandle The native handle created in native_init.
     * @param consumerId The data flow ID of the sink.
     * @param elementCount The number of elements to request.
     * @param allOrNothing If true, all elements must be available or none.
     * @return A byte array from the queue.
     */
    private native byte[] native_sinkRequestData(
            long nativeHandle, int consumerId, int elementCount, boolean allOrNothing);

    /**
     * @param nativeHandle The native handle created in native_init.
     * @param consumerId The data flow ID of the sink.
     * @param offset The offset in bytes behind the source's current write position.
     * @return true if the operation succeeded.
     */
    private native boolean native_sinkSyncToSource(long nativeHandle, int consumerId, int offset);

    /**
     * @param nativeHandle The native handle created in native_init.
     * @param consumerId The data flow ID of the sink.
     * @return true if this sink's read position can be overwritten by the source when it wraps
     *     around the shared memory region
     */
    private native boolean native_sinkSourceCanOverwriteReadPosition(
            long nativeHandle, int consumerId);

    /**
     * @param nativeHandle The native handle created in native_init.
     * @param consumerId The data flow ID of the sink.
     * @return The size of the contents in the queue in bytes.
     */
    private native int native_sinkSize(long nativeHandle, int consumerId);

    /**
     * @param nativeHandle The native handle created in native_init.
     * @param regionId The ID of the shared data region.
     * @param hubId The ID of the hub.
     * @param endpointId The ID of the endpoint.
     * @return An int array comprised of [wakingFds, nonWakingFds] for the offload consumer.
     */
    private native int[] native_addOffloadSink(
            long nativeHandle, int regionId, long hubId, long endpointId);

    private static final int NATIVE_ADD_OFFLOAD_SINK_ARRAY_SIZE = 2;

    /**
     * @param handle The native handle created in native_init.
     * @param producerRegionId The ID of the producer's shared data region.
     * @param dataFlowId The ID of the data flow.
     * @param consumerHubId The hub ID of the consumer.
     * @param consumerEndpointId The endpoint ID of the consumer.
     * @param regionId The ID of the consumer's shared data region.
     * @param regionSize The size of the consumer's shared data region in bytes.
     * @param regionFd The file descriptor of the consumer's shared data region.
     * @param notificationPolicy The notification policy for new data alerts.
     * @param notificationPolicyData The data associated with the notification policy.
     * @param canOverwrite Whether the source can overwrite the sink's read position.
     * @return The consumer descriptor offset.
     */
    private native int native_mapOffloadSinkRegion(
            long handle,
            int sourceRegionId,
            int dataFlowId,
            long sinkHubId,
            long sinkEndpointId,
            int regionId,
            long regionSize,
            int regionFd,
            int notificationPolicy,
            int notificationPolicyData,
            boolean canOverwrite);

    private native void native_removeOffloadSink(
            long nativeHandle, int regionId, long sinkHubId, long sinkEndpointId);

    /**
     * @param nativeHandle The native handle created in native_init.
     * @param regionId The ID of the producer's shared data region.
     * @param consumerHubId The hub ID of the consumer.
     * @param consumerEndpointId The endpoint ID of the consumer.
     * @param notificationPolicy The notification policy for new data alerts.
     * @param notificationPolicyData The data associated with the notification policy.
     * @param canOverwrite Whether the source can overwrite the sink's read position.
     */
    private native void native_updateSinkPolicy(
            long nativeHandle,
            int regionId,
            long sinkHubId,
            long sinkEndpointId,
            int notificationPolicy,
            int notificationPolicyData,
            boolean canOverwrite);

    /**
     * @param nativeHandle The native handle created in native_init.
     * @param regionId The ID of the shared data region for the source to remove.
     */
    private native void native_removeHostSource(long nativeHandle, int regionId);

    /**
     * @param nativeHandle The native handle created in native_init.
     * @param dataFlowId The ID of the data flow for the sink to remove.
     */
    private native void native_removeHostSink(long nativeHandle, int dataFlowId);

    /**
     * @param nativeHandle The native handle created in native_init.
     */
    private native void native_deinit(long nativeHandle);

    /*
     * Internal interface used to invoke IContextHubEndpoint calls.
     */
    interface EndpointConsumer {
        void accept(IContextHubEndpoint endpoint) throws RemoteException;
    }

    private DataFlowJniCallback mJniCallback =
            new DataFlowJniCallback() {
                @Override
                public void onNotificationCallback(long hubId, int dataFlowId, boolean waking) {
                    if (hubId == mAssignedHubEndpointInfo.getIdentifier().getHub()) {
                        DataFlowSource source = mSources.get(dataFlowId);
                        if (source != null) {
                            if (!source.onNotificationCallback(
                                    DataFlowCallback.SOURCE_EVENT_WRITABLE)) {
                                mDataFlowCallbackExecutor.execute(
                                        () -> {
                                            mDataFlowCallback.onDataFlowSourceEvent(
                                                    source,
                                                    DataFlowCallback.SOURCE_EVENT_WRITABLE,
                                                    /* data= */ null);
                                        });
                            }
                        } else {
                            Log.e(TAG, "onNotificationCallback: source not found");
                        }
                    } else {
                        DataFlowSink sink = mSinks.get(dataFlowId);
                        if (sink != null) {
                            if (!sink.onNotificationCallback(
                                    DataFlowCallback.SINK_EVENT_READABLE)) {
                                mDataFlowCallbackExecutor.execute(
                                        () -> {
                                            mDataFlowCallback.onDataFlowSinkEvent(
                                                    sink, DataFlowCallback.SINK_EVENT_READABLE);
                                        });
                            }
                        } else {
                            Log.e(TAG, "onNotificationCallback: sink not found");
                        }
                    }
                }
            };

    /** The sources associated with this endpoint. */
    private final Map<Integer, DataFlowSource> mSources = new HashMap<>();

    /** The sinks associated with this endpoint. */
    // TODO(b/457452333): This should map the whole DataFlowId.
    private final Map<Integer, DataFlowSink> mSinks = new HashMap<>();

    private final IContextHubEndpointCallback mServiceCallback =
            new IContextHubEndpointCallback.Stub() {
                @Override
                public void onSessionOpenRequest(
                        int sessionId,
                        HubEndpointInfo initiator,
                        @Nullable String serviceDescriptor)
                        throws RemoteException {
                    boolean sessionExists = getActiveSession(sessionId) != null;
                    if (sessionExists) {
                        Log.w(TAG, "onSessionOpenRequest: session already exists, id=" + sessionId);
                    }

                    if (mLifecycleCallback == null) {
                        Log.w(
                                TAG,
                                "onSessionOpenRequest: "
                                        + "failed to open session, no lifecycle callback attached",
                                new Exception());
                        rejectSession(sessionId);
                    }

                    if (!sessionExists && mLifecycleCallback != null) {
                        mLifecycleCallbackExecutor.execute(
                                () ->
                                        processSessionOpenRequestResult(
                                                sessionId,
                                                initiator,
                                                serviceDescriptor,
                                                mLifecycleCallback.onSessionOpenRequest(
                                                        initiator, serviceDescriptor)));
                    } else {
                        invokeCallbackFinished();
                    }
                }

                @Override
                public void onSessionOpenComplete(int sessionId) throws RemoteException {
                    final HubEndpointSession activeSession = getActiveSession(sessionId);
                    if (activeSession == null) {
                        Log.w(
                                TAG,
                                "onSessionOpenComplete: no pending session open request? id="
                                        + sessionId);
                    } else {
                        activeSession.setOpened();
                    }

                    if (activeSession != null && mLifecycleCallback != null) {
                        mLifecycleCallbackExecutor.execute(
                                () -> {
                                    mLifecycleCallback.onSessionOpened(activeSession);
                                    invokeCallbackFinished();
                                });
                    } else {
                        invokeCallbackFinished();
                    }
                }

                @Override
                public void onSessionClosed(int sessionId, int reason) throws RemoteException {
                    final HubEndpointSession activeSession = getActiveSession(sessionId);
                    if (activeSession == null) {
                        Log.w(TAG, "onSessionClosed: session not active, id=" + sessionId);
                    } else {
                        activeSession.setClosed();
                        synchronized (mLock) {
                            mActiveSessions.remove(sessionId);
                        }
                    }

                    // Execute the callback
                    if (activeSession != null && mLifecycleCallback != null) {
                        mLifecycleCallbackExecutor.execute(
                                () -> {
                                    mLifecycleCallback.onSessionClosed(activeSession, reason);
                                    invokeCallbackFinished();
                                });
                    } else {
                        invokeCallbackFinished();
                    }
                }

                @Override
                public void onMessageReceived(int sessionId, HubMessage message)
                        throws RemoteException {
                    final HubEndpointSession activeSession = getActiveSession(sessionId);
                    if (activeSession == null) {
                        Log.w(TAG, "onMessageReceived: session not active, id=" + sessionId);
                    }

                    if (activeSession == null || mMessageCallback == null) {
                        sendMessageDeliveryStatus(
                                sessionId, message, ErrorCode.DESTINATION_NOT_FOUND);
                    } else {
                        mMessageCallbackExecutor.execute(
                                () -> {
                                    mMessageCallback.onMessageReceived(activeSession, message);
                                    sendMessageDeliveryStatus(sessionId, message, ErrorCode.OK);
                                });
                    }
                }

                @Override
                public void onDataFlowHostSinkRegistered(
                        DataFlowSinkContext context,
                        HubEndpointInfo source,
                        HubMessage msg,
                        int sessionId)
                        throws RemoteException {
                    Log.d(
                            TAG,
                            "onDataFlowHostSinkRegistered: data flow = "
                                    + context.id.id
                                    + " from source endpoint="
                                    + source);
                    if (mDataFlowCallback == null) {
                        Log.w(TAG, "onDataFlowHostSinkRegistered: no data flow callback attached");
                        return;
                    }

                    DataFlowDataConfig config = enableHostSinkFromContext(context);
                    DataFlowSink sink = createDataFlowSink(config, context);
                    mSinks.put(context.id.id, sink);

                    Log.d(TAG, "onDataFlowHostSinkRegistered: sink = " + sink);

                    if (Flags.fmcqShareDataFlowMessageFix()) {
                        HubEndpointSession activeSession = null;
                        if (sessionId != IContextHubEndpoint.SESSION_ID_INVALID) {
                            activeSession = getActiveSession(sessionId);
                            if (activeSession == null) {
                                Log.w(
                                        TAG,
                                        "onDataFlowHostSinkRegistered: session not active, id="
                                                + sessionId);
                                return;
                            }
                            if (msg == null) {
                                Log.w(
                                        TAG,
                                        "onDataFlowHostSinkRegistered: message is null for"
                                                + " session"
                                                + " id="
                                                + sessionId);
                                return;
                            }
                        } else {
                            msg = null;
                        }

                        final HubEndpointSession finalActiveSession = activeSession;
                        final HubMessage finalMsg = msg;
                        mDataFlowCallbackExecutor.execute(
                                () -> {
                                    mDataFlowCallback.onReceivedDataFlowSink(
                                            sink, source, finalActiveSession, finalMsg);
                                });
                    } else {
                        final HubMessage finalMsg = msg;
                        mDataFlowCallbackExecutor.execute(
                                () -> {
                                    mDataFlowCallback.onReceivedDataFlowSink(
                                            sink, source, getActiveSession(sessionId), finalMsg);
                                });
                    }
                }

                @Override
                public void onDataFlowOffloadEndpointUnregistered(
                        DataFlowId dataFlowId, HubEndpointInfo endpoint) throws RemoteException {
                    if (mAssignedHubEndpointInfo.getIdentifier().getHub() == dataFlowId.hubId) {
                        DataFlowSource source = mSources.get(dataFlowId.id);
                        if (source == null) {
                            Log.w(
                                    TAG,
                                    "onDataFlowOffloadEndpointUnregistered: source not found for"
                                            + " id="
                                            + dataFlowId.id);
                        } else if (mDataFlowCallback == null) {
                            Log.w(
                                    TAG,
                                    "onDataFlowOffloadEndpointUnregistered: no data flow callback"
                                            + " attached");
                        } else {
                            source.removeSink(endpoint);
                            mDataFlowCallbackExecutor.execute(
                                    () -> {
                                        mDataFlowCallback.onDataFlowSourceEvent(
                                                source,
                                                DataFlowCallback.SOURCE_EVENT_SINK_GONE,
                                                /* data= */ new DataFlowCallback.SourceEventData(
                                                        endpoint));
                                    });
                        }
                    } else {
                        DataFlowSink sink = mSinks.get(dataFlowId.id);
                        if (sink == null) {
                            Log.w(
                                    TAG,
                                    "onDataFlowOffloadEndpointUnregistered: sink not found for"
                                            + " id="
                                            + dataFlowId.id);
                        } else if (mDataFlowCallback == null) {
                            Log.w(
                                    TAG,
                                    "onDataFlowOffloadEndpointUnregistered: no data flow callback"
                                            + " attached");
                        } else {
                            sink.closeInternal();
                            mDataFlowCallbackExecutor.execute(
                                    () -> {
                                        mDataFlowCallback.onDataFlowSinkEvent(
                                                sink, DataFlowCallback.SINK_EVENT_STOPPED);
                                    });
                        }
                    }
                }

                private void sendMessageDeliveryStatus(
                        int sessionId, HubMessage message, byte errorCode) {
                    if (message.isResponseRequired()) {
                        invokeCallback(
                                (callback) ->
                                        callback.sendMessageDeliveryStatus(
                                                sessionId,
                                                message.getMessageSequenceNumber(),
                                                errorCode));
                    }
                    invokeCallbackFinished();
                }

                private void processSessionOpenRequestResult(
                        int sessionId,
                        HubEndpointInfo initiator,
                        @Nullable String serviceDescriptor,
                        HubEndpointSessionResult result) {
                    if (result == null) {
                        throw new IllegalArgumentException(
                                "HubEndpointSessionResult shouldn't be null.");
                    }

                    if (result.isAccepted()) {
                        acceptSession(sessionId, initiator, serviceDescriptor);
                    } else {
                        Log.e(
                                TAG,
                                "Session "
                                        + sessionId
                                        + " from "
                                        + initiator
                                        + " was rejected, reason="
                                        + result.getReason());
                        rejectSession(sessionId);
                    }

                    invokeCallbackFinished();
                }

                private void acceptSession(
                        int sessionId,
                        HubEndpointInfo initiator,
                        @Nullable String serviceDescriptor) {
                    // Retrieve the active session
                    HubEndpointSession activeSession;
                    synchronized (mLock) {
                        activeSession = mActiveSessions.get(sessionId);
                        // TODO(b/378974199): Consider refactor these assertions
                        if (activeSession != null) {
                            Log.e(
                                    TAG,
                                    "onSessionOpenRequestResult: session already exists, id="
                                            + sessionId);
                            return;
                        }

                        activeSession =
                                new HubEndpointSession(
                                        sessionId,
                                        HubEndpoint.this,
                                        mAssignedHubEndpointInfo,
                                        initiator,
                                        serviceDescriptor);

                        invokeCallback(
                                (callback) -> callback.openSessionRequestComplete(sessionId));
                        mActiveSessions.put(sessionId, activeSession);
                    }

                    // Execute the callback
                    activeSession.setOpened();
                    if (mLifecycleCallback != null) {
                        final HubEndpointSession finalActiveSession = activeSession;
                        mLifecycleCallbackExecutor.execute(
                                () -> mLifecycleCallback.onSessionOpened(finalActiveSession));
                    }
                }

                private void rejectSession(int sessionId) {
                    invokeCallback(
                            (callback) ->
                                    callback.closeSession(
                                            sessionId,
                                            REASON_OPEN_ENDPOINT_SESSION_REQUEST_REJECTED));
                }

                private void invokeCallbackFinished() {
                    invokeCallback((callback) -> callback.onCallbackFinished());
                }

                private void invokeCallback(EndpointConsumer consumer) {
                    try {
                        consumer.accept(mServiceToken);
                    } catch (IllegalStateException e) {
                        // It's possible to hit this exception if the endpoint was unregistered
                        // while processing the callback. It's not a fatal error so we just log
                        // a warning.
                        Log.w(TAG, "IllegalStateException while calling callback", e);
                    } catch (RemoteException e) {
                        e.rethrowFromSystemServer();
                    }
                }
            };

    /** Binder returned from system service, non-null while registered. */
    @Nullable private IContextHubEndpoint mServiceToken;

    /** HubEndpointInfo with the assigned endpoint id from system service. */
    @Nullable private HubEndpointInfo mAssignedHubEndpointInfo;

    private HubEndpoint(
            @NonNull HubEndpointInfo pendingEndpointInfo,
            @Nullable HubEndpointLifecycleCallback endpointLifecycleCallback,
            @NonNull Executor lifecycleCallbackExecutor,
            @Nullable HubEndpointMessageCallback endpointMessageCallback,
            @NonNull Executor messageCallbackExecutor,
            @Nullable DataFlowCallback endpointDataFlowCallback,
            @NonNull Executor dataFlowCallbackExecutor,
            @NonNull Looper looper) {
        mPendingHubEndpointInfo = pendingEndpointInfo;
        mLifecycleCallback = endpointLifecycleCallback;
        mLifecycleCallbackExecutor = lifecycleCallbackExecutor;
        mMessageCallback = endpointMessageCallback;
        mMessageCallbackExecutor = messageCallbackExecutor;
        mDataFlowCallback = endpointDataFlowCallback;
        mDataFlowCallbackExecutor = dataFlowCallbackExecutor;
        mEpollLooper = looper;
    }

    /** @hide */
    public void register(IContextHubService service) {
        try {
            IContextHubEndpoint serviceToken =
                    service.registerEndpoint(
                            mPendingHubEndpointInfo,
                            mServiceCallback,
                            mPendingHubEndpointInfo.getName(),
                            mPendingHubEndpointInfo.getTag());
            mAssignedHubEndpointInfo = serviceToken.getAssignedHubEndpointInfo();
            mServiceToken = serviceToken;

            if (Flags.fmcqImplementation()) {
                mNativeHandle =
                        native_init(
                                mEpollLooper.getQueue(),
                                mJniCallback,
                                mAssignedHubEndpointInfo.getIdentifier().getHub());
            } else {
                mNativeHandle = 0;
            }
        } catch (RemoteException e) {
            Log.e(TAG, "registerEndpoint: failed to register endpoint", e);
            e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    public void unregister() {
        try {
            synchronized (mLock) {
                // Don't call HubEndpointSession.close() here.
                for (int i = 0; i < mActiveSessions.size(); i++) {
                    mActiveSessions.get(mActiveSessions.keyAt(i)).setClosed();
                }
                mActiveSessions.clear();
            }
            mServiceToken.unregister();
        } catch (RemoteException e) {
            Log.e(TAG, "unregisterEndpoint: failed to unregister endpoint", e);
            e.rethrowFromSystemServer();
        }

        if (mNativeHandle != 0) {
            native_deinit(mNativeHandle);
            mNativeHandle = 0;
        }
    }

    /** @hide */
    public void openSession(HubEndpointInfo destinationInfo, @Nullable String serviceDescriptor) {
        HubEndpointSession newSession;
        try {
            synchronized (mLock) {
                // Request system service to assign session id.
                int sessionId = mServiceToken.openSession(destinationInfo, serviceDescriptor);

                // Save the newly created session
                newSession =
                        new HubEndpointSession(
                                sessionId,
                                HubEndpoint.this,
                                destinationInfo,
                                mAssignedHubEndpointInfo,
                                serviceDescriptor);
                mActiveSessions.put(sessionId, newSession);
            }
        } catch (RemoteException e) {
            // Move this to toString
            Log.e(TAG, "openSession: failed to open session to " + destinationInfo, e);
            e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    public void closeSession(HubEndpointSession session) {
        synchronized (mLock) {
            if (!mActiveSessions.contains(session.getId())) {
                // Already closed?
                return;
            }
            session.setClosed();
            mActiveSessions.remove(session.getId());
        }

        try {
            mServiceToken.closeSession(session.getId(), REASON_CLOSE_ENDPOINT_SESSION_REQUESTED);
        } catch (RemoteException e) {
            Log.e(TAG, "closeSession: failed to close session " + session, e);
            e.rethrowFromSystemServer();
        }
    }

    void sendMessage(
            HubEndpointSession session,
            HubMessage message,
            @Nullable IContextHubTransactionCallback transactionCallback) {
        try {
            mServiceToken.sendMessage(session.getId(), message, transactionCallback);
        } catch (RemoteException e) {
            Log.e(TAG, "sendMessage: failed to send message session=" + session, e);
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Creates a data flow for efficient transfer of raw data between this endpoint and endpoints on
     * offload message hubs.
     *
     * <p>A data flow is a single-producer multi-consumer queue implemented over shared memory,
     * enabling lower latency and higher throughput transfer of data over session-based messaging. A
     * data flow may either be produced by a host endpoint and viewed by one or more offload
     * endpoints or produced by an offload endpoint and consumed by one or more host endpoints. The
     * producer of a data flow is known as its source (see {@link DataFlowSource}) and the consumers
     * are known as its sinks (see {@link DataFlowSink}).
     *
     * <p>Sources and sinks independently update their respective positions in metadata in shared
     * memory using atomic operations. The source and sinks can calculate availability of storage
     * and new data respectively without explicit synchronization and thus push and pop data with
     * very low latency. Data flows also support an out-of-band notification mechanism that enables
     * a source or sink to wait efficiently for available space or new data. This underlies new data
     * alert policies (see {@link DataFlowNewDataAlertPolicy}), where the source notifies each sink
     * only when it is important to do so (e.g. some necessary threshold of available data is
     * reached).
     *
     * <p>This call specifically creates a data flow that whose source is this endpoint. It handles
     * the communication with the service and shared memory setup necessary for the user to
     * immediately begin writing to shared memory and adding offload endpoints as consumers via the
     * returned {@link DataFlowSource} object. Only offload endpoints on hubs in {@code
     * targetHubIds} will be able to access the data flow.
     *
     * <p>The data transferred over the data flow is a stream of arbitrary raw data elements of
     * fixed or variable size with possible alignment constraints. The format of the data is
     * specified by the provided {@link DataFlowDataConfig}. The source and sinks are responsible
     * for negotiating the data configuration out-of-band, possibly by the source exposing a
     * well-known {@link HubServiceInfo}.
     *
     * <p>The data flow is guaranteed to have a minimum capacity ({@code minCapacity}) and is
     * permitted to grow up to a maximum capacity ({@code maxCapacity}) as needed. The capacity may
     * change dynamically within the range [minCapacity, maxCapacity].
     *
     * @param targetHubIds The set of offload message hub IDs which should be able to access this
     *     data flow
     * @param dataConfig The configuration of elements sent over this data flow
     * @param minCapacity The minimum capacity in bytes of the data flow
     * @param maxCapacity The maximum capacity in bytes of data flow
     * @return A {@link DataFlowSource} for pushing data into the data flow and managing the offload
     *     sinks that can access the data flow
     * @throws IllegalArgumentException if {@code minCapacity} is greater than {@code maxCapacity}
     *     or either capacity is invalid given the {@code dataConfig}, e.g. if {@code dataConfig}
     *     specifies a fixed-size format but the capacities are not multiples of the element size
     * @throws IllegalStateException if this endpoint has not been registered
     * @throws NoSuchElementException if any of the target hub IDs cannot be resolved
     * @throws UnsupportedOperationException if a data flow cannot be created from this endpoint to
     *     the given target hubs. This may indicate that a suitable shared memory region does not
     *     exist, that the desired capacity is not available, or that data flows are not supported
     *     on this platform
     */
    @RequiresPermission(android.Manifest.permission.ACCESS_CONTEXT_HUB)
    @FlaggedApi(Flags.FLAG_FMCQ_API)
    @NonNull
    public DataFlowSource createDataFlowSource(
            @NonNull @Size(min = 1) Set<Long> targetHubIds,
            @NonNull DataFlowDataConfig dataConfig,
            @IntRange(from = 1) int minCapacity,
            @IntRange(from = 1) int maxCapacity) {
        Objects.requireNonNull(targetHubIds);
        Objects.requireNonNull(dataConfig);
        boolean isFixedSize = dataConfig.getFormat() == DataFlowDataConfig.FORMAT_FIXED_SIZE;

        int elementSize;
        if (Flags.fmcqSupportVariableSizedDataFlowFix()) {
            elementSize = isFixedSize ? dataConfig.getElementSize() : NATIVE_ELEMENT_SIZE_VARIABLE;
        } else {
            elementSize = dataConfig.getElementSize();
        }

        int elementAlignment = dataConfig.getElementAlignment();
        if (minCapacity > maxCapacity) {
            throw new IllegalArgumentException(
                    "Min capacity must be less than or equal to max capacity.");
        } else if (isFixedSize
                && (minCapacity % elementSize != 0 || maxCapacity % elementSize != 0)) {
            throw new IllegalArgumentException("Capacity must be a multiple of the element size.");
        } else if (minCapacity < elementSize || maxCapacity < elementSize) {
            throw new IllegalArgumentException(
                    "Capacity must be greater than or equal to the element size.");
        }
        if (!Flags.fmcqImplementation()) {
            throw new UnsupportedOperationException(
                    "Data flows are not supported on this platform.");
        }

        SharedDataRegion region = null;
        Optional<Integer> dataFlowId = Optional.empty();
        DataFlowSource ret = null;
        try {
            // TODO(b/460528144): Support re-using of existing shared data regions
            SharedDataRegionRequirements req = new SharedDataRegionRequirements();
            req.sizeBytes = isFixedSize ? elementSize * maxCapacity : maxCapacity;
            req.targetHubIds = targetHubIds.stream().mapToLong(Long::longValue).toArray();
            req.permissions = new String[0];

            region = mServiceToken.allocateSharedDataRegion(req);
            DataFlowInfo info = new DataFlowInfo();
            info.region = region;
            info.debugName = mPendingHubEndpointInfo.getName();
            int minElementCount = minCapacity / elementSize;
            int maxElementCount = maxCapacity / elementSize;
            if (Flags.fmcqSupportVariableSizedDataFlowFix() && !isFixedSize) {
                elementSize = -1;
            }

            int[] dataFlowValues =
                    native_createDataFlowInfo(
                            mNativeHandle,
                            region.id,
                            region.sizeBytes,
                            region.sharedMemory.getFd(),
                            elementSize,
                            elementAlignment,
                            minElementCount,
                            maxElementCount);
            if (dataFlowValues == null) {
                throw new IllegalStateException("Failed to create DataFlowInfo");
            } else if (dataFlowValues.length != NATIVE_CREATE_DATA_FLOW_INFO_ARRAY_SIZE) {
                throw new IllegalStateException(
                        "Incorrect data flow values length: " + dataFlowValues.length);
            }
            info.metadataOffsetBytes = dataFlowValues[0];
            info.alertFds = new DataFlowAlertFds();
            info.alertFds.waking = ParcelFileDescriptor.adoptFd(dataFlowValues[1]);
            info.alertFds.nonWaking = ParcelFileDescriptor.adoptFd(dataFlowValues[2]);
            info.alertFds.halAck = ParcelFileDescriptor.adoptFd(dataFlowValues[3]);
            dataFlowId = Optional.of(mServiceToken.registerDataFlowHostSource(info));

            if (!native_activateDataFlow(mNativeHandle, dataFlowId.get(), region.id)) {
                throw new IllegalStateException("Failed to activate DataFlow");
            }

            DataFlowId dataFlowIdObj = new DataFlowId();
            dataFlowIdObj.hubId = mPendingHubEndpointInfo.getIdentifier().getHub();
            dataFlowIdObj.id = dataFlowId.get();
            ret = new DataFlowSource(dataConfig, this, region, info, dataFlowIdObj);
            mSources.put(dataFlowIdObj.id, ret);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        } finally {
            if (ret == null) {
                removeHostDataFlow(
                        dataFlowId, region == null ? Optional.empty() : Optional.of(region.id));
            }
        }

        return ret;
    }

    public int getVersion() {
        return mPendingHubEndpointInfo.getVersion();
    }

    @Nullable
    public String getTag() {
        return mPendingHubEndpointInfo.getTag();
    }

    @NonNull
    public Collection<HubServiceInfo> getServiceInfoCollection() {
        return mPendingHubEndpointInfo.getServiceInfoCollection();
    }

    @Nullable
    public HubEndpointLifecycleCallback getLifecycleCallback() {
        return mLifecycleCallback;
    }

    @Nullable
    public HubEndpointMessageCallback getMessageCallback() {
        return mMessageCallback;
    }

    /** Returns the callback interface for data flow related events for this endpoint. */
    @FlaggedApi(Flags.FLAG_FMCQ_API)
    @Nullable
    public DataFlowCallback getDataFlowCallback() {
        return mDataFlowCallback;
    }

    /** Returns the {@link android.os.Looper} for epoll relating to data flow alerts. */
    @FlaggedApi(Flags.FLAG_FMCQ_API)
    @NonNull
    public Looper getEpollLooper() {
        return mEpollLooper;
    }

    /** Builder for a {@link HubEndpoint} object. */
    public static final class Builder {
        private final String mPackageName;

        @Nullable private HubEndpointLifecycleCallback mLifecycleCallback;
        @Nullable private Executor mLifecycleCallbackExecutor;

        @Nullable private HubEndpointMessageCallback mMessageCallback;
        @Nullable private Executor mMessageCallbackExecutor;

        @Nullable private DataFlowCallback mDataFlowCallback;
        @Nullable private Executor mDataFlowCallbackExecutor;

        @Nullable private Looper mEpollLooper;

        @NonNull private final Executor mMainExecutor;

        private int mVersion;
        @Nullable private String mTag;

        private List<HubServiceInfo> mServiceInfos = Collections.emptyList();

        /** Create a builder for {@link HubEndpoint} */
        public Builder(@NonNull Context context) {
            mPackageName = context.getPackageName();
            mTag = context.getAttributionTag();
            mVersion = (int) context.getApplicationInfo().longVersionCode;
            mMainExecutor = context.getMainExecutor();
        }

        /**
         * Set the version for the endpoint. Default is 0.
         *
         * @hide
         */
        @NonNull
        public Builder setVersion(int version) {
            mVersion = version;
            return this;
        }

        /**
         * Set a tag string. The tag can be used to further identify the creator of the endpoint.
         * Endpoints created by the same package share the same name but should have different tags.
         * The default value of the tag is retrieved from {@link Context#getAttributionTag()}.
         */
        @NonNull
        public Builder setTag(@NonNull String tag) {
            mTag = tag;
            return this;
        }

        /**
         * Attach a callback interface for lifecycle events for this Endpoint. Callback will be
         * posted to the main thread.
         */
        @NonNull
        public Builder setLifecycleCallback(
                @NonNull HubEndpointLifecycleCallback lifecycleCallback) {
            mLifecycleCallbackExecutor = null;
            mLifecycleCallback = lifecycleCallback;
            return this;
        }

        /**
         * Attach a callback interface for lifecycle events for this Endpoint with a specified
         * executor
         */
        @NonNull
        public Builder setLifecycleCallback(
                @NonNull @CallbackExecutor Executor executor,
                @NonNull HubEndpointLifecycleCallback lifecycleCallback) {
            mLifecycleCallbackExecutor = executor;
            mLifecycleCallback = lifecycleCallback;
            return this;
        }

        /**
         * Attach a callback interface for message events for this Endpoint. Callback will be posted
         * to the main thread.
         */
        @NonNull
        public Builder setMessageCallback(@NonNull HubEndpointMessageCallback messageCallback) {
            mMessageCallbackExecutor = null;
            mMessageCallback = messageCallback;
            return this;
        }

        /**
         * Attach a callback interface for message events for this Endpoint with a specified
         * executor
         */
        @NonNull
        public Builder setMessageCallback(
                @NonNull @CallbackExecutor Executor executor,
                @NonNull HubEndpointMessageCallback messageCallback) {
            mMessageCallbackExecutor = executor;
            mMessageCallback = messageCallback;
            return this;
        }

        /**
         * Attach a callback interface for data flow events for this Endpoint. The callback will be
         * posted to the main thread.
         */
        @FlaggedApi(Flags.FLAG_FMCQ_API)
        @NonNull
        public Builder setDataFlowCallback(@NonNull DataFlowCallback dataFlowCallback) {
            Objects.requireNonNull(dataFlowCallback);
            mDataFlowCallbackExecutor = null;
            mDataFlowCallback = dataFlowCallback;
            return this;
        }

        /**
         * Attach a callback interface for data flow events for this Endpoint with a specified
         * executor.
         *
         * @param executor The executor to post data flow events to
         * @param dataFlowCallback The callback interface for handling data flow events
         */
        @FlaggedApi(Flags.FLAG_FMCQ_API)
        @NonNull
        public Builder setDataFlowCallback(
                @NonNull @CallbackExecutor Executor executor,
                @NonNull DataFlowCallback dataFlowCallback) {
            Objects.requireNonNull(executor);
            Objects.requireNonNull(dataFlowCallback);
            mDataFlowCallbackExecutor = executor;
            mDataFlowCallback = dataFlowCallback;
            return this;
        }

        /**
         * Add a service to the available services from this endpoint. The {@link HubServiceInfo}
         * object can be built with {@link HubServiceInfo.Builder}.
         */
        @NonNull
        public Builder setServiceInfoCollection(
                @NonNull Collection<HubServiceInfo> hubServiceInfos) {
            // Make a copy first
            mServiceInfos = new ArrayList<>(hubServiceInfos);
            return this;
        }

        /**
         * Provide an optional {@code Looper} for to handle epoll on the notification eventfds
         * associated with data flows this endpoint is the source or sink of. If not provided, the
         * {@link android.os.Looper#getMainLooper()} will be used. The {@code Looper} is only used
         * for epoll, not for executing the callbacks registered with {@link #setDataFlowCallback}.
         *
         * @param looper The {@link android.os.Looper} to use for epoll
         */
        @FlaggedApi(Flags.FLAG_FMCQ_API)
        @NonNull
        public Builder setEpollLooper(@NonNull Looper looper) {
            Objects.requireNonNull(looper);
            mEpollLooper = looper;
            return this;
        }

        /** Build the {@link HubEndpoint} object. */
        @NonNull
        public HubEndpoint build() {
            return new HubEndpoint(
                    new HubEndpointInfo(mPackageName, mVersion, mTag, mServiceInfos),
                    mLifecycleCallback,
                    mLifecycleCallbackExecutor != null ? mLifecycleCallbackExecutor : mMainExecutor,
                    mMessageCallback,
                    mMessageCallbackExecutor != null ? mMessageCallbackExecutor : mMainExecutor,
                    mDataFlowCallback,
                    mDataFlowCallbackExecutor != null ? mDataFlowCallbackExecutor : mMainExecutor,
                    mEpollLooper != null ? mEpollLooper : Looper.getMainLooper());
        }
    }

    /**
     * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
     * Below are package-private methods that are meant to be called from DataFlowSource
     * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
     */

    /** @hide */
    ContextHubTransaction<Void> shareDataFlow(
            @NonNull SharedDataRegion region,
            @NonNull DataFlowInfo dataFlowInfo,
            @NonNull DataFlowId dataFlowId,
            @NonNull HubEndpointInfo sinkInfo,
            @NonNull DataFlowNewDataAlertPolicy newDataAlertPolicy,
            boolean canOverwrite,
            @Nullable HubEndpointSession session,
            @Nullable HubMessage msg)
            throws IllegalStateException {
        HubEndpointIdentifier id = sinkInfo.getIdentifier();
        int[] offloadSinkValues =
                native_addOffloadSink(mNativeHandle, region.id, id.getHub(), id.getEndpoint());
        if (offloadSinkValues == null) {
            throw new IllegalStateException("Failed to add offload sink");
        } else if (offloadSinkValues.length != NATIVE_ADD_OFFLOAD_SINK_ARRAY_SIZE) {
            throw new IllegalStateException(
                    "Incorrect offload sink values length: " + offloadSinkValues.length);
        } else if (Flags.fmcqShareDataFlowMessageFix() && ((session == null) != (msg == null))) {
            throw new IllegalArgumentException(
                    "Session and message must either be both null or both non-null.");
        } else if (Flags.fmcqShareDataFlowMessageFix()
                && session != null
                && getActiveSession(session.getId()) == null) {
            throw new IllegalArgumentException(
                    "Session with ID: " + session.getId() + " is not active.");
        }

        DataFlowSinkContext context = new DataFlowSinkContext();
        context.id = dataFlowId;
        context.info = dataFlowInfo;
        // Must be null according to HAL contract
        context.sinkMetadataRegion = null;
        context.metadataOffsetBytes = 0;
        context.alertFds = new DataFlowAlertFds();
        context.alertFds.waking = ParcelFileDescriptor.adoptFd(offloadSinkValues[0]);
        context.alertFds.nonWaking = ParcelFileDescriptor.adoptFd(offloadSinkValues[1]);
        // Must be null according to HAL contract
        context.alertFds.halAck = null;

        IContextHubEndpoint.IRegisterOffloadSinkCallback callback =
                new IContextHubEndpoint.IRegisterOffloadSinkCallback.Stub() {
                    @Override
                    public long addSinkInRegion(@Nullable SharedDataRegion region)
                            throws RemoteException {
                        Log.d(TAG, "addSinkInRegion: region id=" + region.id);
                        if (region == null) {
                            Log.e(TAG, "addSinkInRegion: region is null");
                            // TODO(b/460528144): Create a new region
                            return 0;
                        }

                        return native_mapOffloadSinkRegion(
                                mNativeHandle,
                                context.info.region.id,
                                dataFlowId.id,
                                id.getHub(),
                                id.getEndpoint(),
                                region.id,
                                region.sizeBytes,
                                region.sharedMemory.getFd(),
                                newDataAlertPolicy.getPolicyType(),
                                newDataAlertPolicy.getData(),
                                canOverwrite);
                    }
                };

        IContextHubTransactionCallback transactionCallback = null;
        ContextHubTransaction<Void> transaction = null;
        if (session != null && msg != null) {
            transaction =
                    new ContextHubTransaction<Void>(
                            ContextHubTransaction.TYPE_HUB_MESSAGE_REQUIRES_RESPONSE);
            transactionCallback =
                    ContextHubTransactionHelper.createTransactionCallback(transaction);
        }
        try {
            mServiceToken.registerDataFlowOffloadSink(
                    context,
                    sinkInfo,
                    callback,
                    msg,
                    /* sessionId= */ session == null
                            ? IContextHubEndpoint.SESSION_ID_INVALID
                            : session.getId(),
                    transactionCallback);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }

        return transaction;
    }

    /** @hide */
    int sourcePush(
            @NonNull SharedDataRegion region, @NonNull DataFlowData data, boolean allOrNothing) {
        List<ByteBuffer> buffers = data.getBuffers();
        byte[] combinedData;
        if (buffers.size() == 0) {
            throw new IllegalArgumentException("No data to push");
        } else if (buffers.size() == 1) {
            combinedData = buffers.get(0).array();
        } else {
            int totalSize = 0;
            for (ByteBuffer buffer : buffers) {
                totalSize += buffer.remaining();
            }
            combinedData = new byte[totalSize];
            int offset = 0;
            for (ByteBuffer buffer : buffers) {
                buffer.get(combinedData, offset, buffer.remaining());
                offset += buffer.remaining();
            }
        }
        return native_sourcePush(mNativeHandle, region.id, combinedData, allOrNothing);
    }

    /** @hide */
    boolean sourceFull(@NonNull SharedDataRegion region) {
        return native_sourceFull(mNativeHandle, region.id);
    }

    /** @hide */
    int sourceSize(@NonNull SharedDataRegion region) {
        return native_sourceSize(mNativeHandle, region.id, /* includeReserved= */ true);
    }

    /** @hide */
    void updateSinkPolicy(
            @NonNull SharedDataRegion region,
            @NonNull HubEndpointInfo sinkInfo,
            @NonNull DataFlowNewDataAlertPolicy newDataAlertPolicy,
            boolean canOverwrite) {
        native_updateSinkPolicy(
                mNativeHandle,
                region.id,
                sinkInfo.getIdentifier().getHub(),
                sinkInfo.getIdentifier().getEndpoint(),
                newDataAlertPolicy.getPolicyType(),
                newDataAlertPolicy.getData(),
                canOverwrite);
    }

    /** @hide */
    void removeOffloadSink(@NonNull SharedDataRegion region, @NonNull HubEndpointInfo sinkInfo) {
        native_removeOffloadSink(
                mNativeHandle,
                region.id,
                sinkInfo.getIdentifier().getHub(),
                sinkInfo.getIdentifier().getEndpoint());
    }

    /** @hide */
    void removeHostDataFlow(
            @NonNull Optional<Integer> dataFlowId, @NonNull Optional<Integer> regionId) {
        // TODO(b/460528144): Separate the unregistration and unmapping steps into separate methods
        // for clarity.
        if (dataFlowId.isPresent()) {
            try {
                mServiceToken.unregisterDataFlowHostSource(dataFlowId.get());
            } catch (RemoteException e) {
                e.rethrowFromSystemServer();
            }
            mSources.remove(dataFlowId.get());
        }
        if (regionId.isPresent()) {
            native_removeHostSource(mNativeHandle, regionId.get());
            try {
                mServiceToken.freeSharedDataRegion(regionId.get());
            } catch (RemoteException e) {
                e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
     * Below are package-private methods that are meant to be called from DataFlowSink
     * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
     */

    /** @hide */
    @NonNull
    byte[] sinkRequestData(DataFlowSinkContext context, int elementCount, boolean allOrNothing) {
        return native_sinkRequestData(mNativeHandle, context.id.id, elementCount, allOrNothing);
    }

    /** @hide */
    void sinkSyncToSource(DataFlowSinkContext context, int offset) {
        if (!native_sinkSyncToSource(mNativeHandle, context.id.id, offset)) {
            Log.e(TAG, "syncToSource: failed to sync to source");
        }
    }

    /** @hide */
    boolean sinkSourceCanOverwriteReadPosition(DataFlowSinkContext context) {
        return native_sinkSourceCanOverwriteReadPosition(mNativeHandle, context.id.id);
    }

    /** @hide */
    int sinkSize(DataFlowSinkContext context) {
        return native_sinkSize(mNativeHandle, context.id.id);
    }

    /** @hide */
    void removeSink(DataFlowSinkContext context) {
        native_removeHostSink(mNativeHandle, context.id.id);
        mSinks.remove(context.id.id);
    }

    private DataFlowDataConfig enableHostSinkFromContext(DataFlowSinkContext context) {
        int[] hostSinkValues =
                native_enableHostSink(
                        mNativeHandle,
                        context.info.region.id,
                        context.info.region.sizeBytes,
                        context.info.region.sharedMemory.getFd(),
                        context.id.hubId,
                        context.id.id,
                        context.alertFds.waking.getFd(),
                        context.alertFds.nonWaking.getFd(),
                        context.alertFds.halAck.getFd(),
                        context.info.alertFds.waking.getFd(),
                        context.info.alertFds.nonWaking.getFd(),
                        context.info.metadataOffsetBytes,
                        context.metadataOffsetBytes);
        if (hostSinkValues == null) {
            Log.e(TAG, "enableHostSinkFromContext: failed to enable host sink");
            return null;
        }
        if (hostSinkValues.length != NATIVE_ENABLE_HOST_SINK_ARRAY_SIZE) {
            Log.e(TAG, "enableHostSinkFromContext: incorrect host sink values length");
            return null;
        }

        int elementSize = hostSinkValues[0];
        int elementAlignment = hostSinkValues[1];
        if (Flags.fmcqSupportVariableSizedDataFlowFix()) {
            return elementSize <= 0
                    ? DataFlowDataConfig.createVariableSizeAligned(elementAlignment)
                    : DataFlowDataConfig.createFixedSize(elementSize, elementAlignment);
        } else {
            return DataFlowDataConfig.createFixedSize(elementSize, elementAlignment);
        }
    }

    private DataFlowSink createDataFlowSink(
            DataFlowDataConfig config, DataFlowSinkContext context) {
        return new DataFlowSink(config, context, this);
    }

    /** package */
    Executor getDataFlowCallbackExecutor() {
        return mDataFlowCallbackExecutor;
    }

    private HubEndpointSession getActiveSession(int sessionId) {
        synchronized (mLock) {
            return mActiveSessions.get(sessionId);
        }
    }
}
