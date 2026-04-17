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

package android.hardware.contexthub;

import android.annotation.DurationMillisLong;
import android.annotation.FlaggedApi;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.chre.flags.Flags;
import android.hardware.location.ContextHubTransaction;
import android.os.CancellationSignal;
import android.os.OutcomeReceiver;
import android.os.SystemClock;
import android.system.SystemCleaner;
import android.util.CloseGuard;

import android.util.Log;
import java.time.Duration;
import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.HashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Represents the source of a data flow from a host endpoint to offload endpoints. New instances of
 * this class are created from {@link HubEndpoint#createDataFlowSource(Set, DataFlowDataConfig, int,
 * int)}. See that method for an overview of data flows.
 *
 * <p>This class's API is expected to be invoked from a single thread at a time. It may be invoked
 * from different threads so long as the user ensures this using an appropriate mechanism (e.g.
 * mutex, dispatching to a single-threaded executor).
 *
 * <p>The lifetime of a data flow whose source is a host endpoint begins when {@link
 * HubEndpoint#createDataFlowSource(Set, DataFlowDataConfig, int, int)} returns successfully, and
 * ends when either the user calls {@link #stop()}, the user unregisters the endpoint from the
 * framework service, or a call to any of the API methods fails with a {@link RuntimeException},
 * indicating a lower-level error in communication that makes this data flow unusable.
 *
 * <p>The user shares access to the data flow with offload sinks by calling {@link
 * DataFlowSource#shareDataFlow(HubEndpointInfo, DataFlowNewDataAlertPolicy, boolean)}. If
 * confirmation that the sink received the data flow is required, the user may use {@link
 * DataFlowSource#shareDataFlowOverSession(HubEndpointInfo, DataFlowNewAlertDataPolicy, boolean,
 * HubEndpointSession, HubMessage)}.
 *
 * <p>Once a data flow has been successfully shared with a sink, the user can be notified when the
 * sink is no longer present in one of two ways: handling the {@link
 * DataFlowCallback#onDataFlowSinkGone(HubEndpointInfo, int)} callback as well as by registering for
 * {@link android.hardware.location.ContextHubManager#registerEndpointDiscoveryCallback(Executor,
 * HubEndpointDiscoveryCallback, long)}. In either case, the data flow must be re-shared with the
 * sink for it to be able to access it again.
 *
 * <p>Pushing data into the data flow will make data available to all initialized sinks. When a sink
 * first initializes after {@link DataFlowSource#shareDataFlow(HubEndpointInfo,
 * DataFlowNewDataAlertPolicy, boolean)}, it syncs to the source's position, so older data is not
 * available. If a user wants to ensure that a new sink receives some data, it must use a separate
 * mechanism, possibly messages over a open session, to ensure that the sink has initialized before
 * pushing that data.
 *
 * @hide
 */
@SystemApi
@FlaggedApi(Flags.FLAG_FMCQ_API)
public final class DataFlowSource implements AutoCloseable {
    private static final String TAG = "DataFlowSource";

    /** The configuration of data sent over this data flow. */
    private final DataFlowDataConfig mConfig;

    private final HubEndpoint mEndpoint;
    private final SharedDataRegion mRegion;
    private final DataFlowInfo mDataFlowInfo;
    private final DataFlowId mDataFlowId;

    private final Set<HubEndpointInfo> mSinks = new HashSet<>();

    /** Close guard to warn when the user hasn't explicitly {@link #close()}d this instance. */
    private final CloseGuard mCloseGuard = new CloseGuard();

    @Nullable private CompletableFuture<Void> mNotificationFuture;
    private final Object mNotificationLock = new Object();

    private AtomicBoolean mIsBusy = new AtomicBoolean(false);

    private final class ApiGuard implements AutoCloseable {
        @Override
        public void close() {
            mIsBusy.set(false);
        }
    }

    /** The state associated with an ongoing asynchronous operation. */
    private static final class AsyncState {
        final DataFlowData mData;
        final ApiGuard mGuard;
        final OutcomeReceiver<Void, Throwable> mReceiver;
        final Executor mExecutor;

        AsyncState(
                @NonNull DataFlowData data,
                @NonNull ApiGuard guard,
                @NonNull OutcomeReceiver<Void, Throwable> receiver,
                @Nullable Executor executor) {
            mData = data;
            mGuard = guard;
            mReceiver = receiver;
            mExecutor = executor;
        }
    }

    /** Used to check whether an async operation is stale. Guarded by mNotificationLock. */
    @Nullable private AsyncState mAsyncState = null;

    /**
     * Returns the configuration of elements sent over this data flow. This configuration is the
     * same as that provided to {@link HubEndpoint#createDataFlowSource(Set, DataFlowDataConfig,
     * int, int)}.
     *
     * @return The data configuration for this data flow
     */
    @RequiresPermission(android.Manifest.permission.ACCESS_CONTEXT_HUB)
    @NonNull
    public DataFlowDataConfig getDataFlowConfig() {
        return mConfig;
    }

    /**
     * Shares this data flow with an offload endpoint, which can then be a sink for it. Sets the
     * sink's {@link DataFlowNewDataAlertPolicy} and whether the source can overwrite the sink's
     * read position when pushing data into the data flow.
     *
     * <p>This method will send the framework a handle that can be used to give the sink access to
     * the data flow. The sink will asynchronously receive the handle and begin accessing the data
     * flow. The user will not receive any alert when the sink begins to access the data flow. The
     * implementation will ensure that once the sink starts to read, it will receive new data alerts
     * per {@code newDataAlertPolicy}.
     *
     * <p>If the framework detects that a sink is no longer reading from a data flow, it will notify
     * the user via a {@link DataFlowCallback#onDataFlowSourceEvent(DataFlowSource, int,
     * SourceEventData)} event of type {@link DataFlowCallback#SOURCE_EVENT_SINK_GONE}. If desired,
     * the user can reshare the data flow with the sink using this method.
     *
     * <p>This class safely handles the case that a sink crashes, automatically releasing resources
     * associated with the sink. If the user wants to respond to the sink crashing, they can
     * register for endpoint discovery events via {@link
     * android.hardware.location.ContextHubManager#registerEndpointDiscoveryCallback(Executor,
     * HubEndpointDiscoveryCallback, long)}.
     *
     * @param sinkInfo The endpoint info of the sink
     * @param newDataAlertPolicy The {@link DataFlowNewDataAlertPolicy} to apply to the sink
     * @param canOverwrite {@code true} if the source can overwrite the sink's read index when
     *     pushing data, {@code false} otherwise
     * @throws IllegalStateException if the data flow has already been shared with the intended sink
     *     and it is still active or if this source has been stopped
     * @throws NoSuchElementException if the sink's endpoint ID cannot be resolved
     * @throws ConcurrentModificationException if another API is being called concurrently or an
     *     async operation is in progress
     */
    @RequiresPermission(android.Manifest.permission.ACCESS_CONTEXT_HUB)
    public void shareDataFlow(
            @NonNull HubEndpointInfo sinkInfo,
            @NonNull DataFlowNewDataAlertPolicy newDataAlertPolicy,
            boolean canOverwrite) {
        Objects.requireNonNull(sinkInfo);
        Objects.requireNonNull(newDataAlertPolicy);
        try (ApiGuard apiGuard = acquireApiGuard()) {
            mEndpoint.shareDataFlow(
                    mRegion,
                    mDataFlowInfo,
                    mDataFlowId,
                    sinkInfo,
                    newDataAlertPolicy,
                    canOverwrite,
                    /* session= */ null,
                    /* msg= */ null);
            mSinks.add(sinkInfo);
        }
    }

    /**
     * Like {@link #shareDataFlow(HubEndpointInfo, DataFlowNewDataAlertPolicy, boolean)}, but
     * provides the user with confirmation when the sink endpoint receives it.
     *
     * <p>This method makes use of the existing reliable messaging mechanism (see {@link
     * HubMessage.Builder#setResponseRequired(boolean)}). The user provides an open {@link
     * HubEndpointSession} with the sink endpoint, as well as a {@link android.os.OutcomeReceiver}
     * which will be invoked when either the sink gets the message and access to the data flow or
     * the share fails.
     *
     * <p>The opposite of this method, i.e. an offload endpoint source reliably sharing a data flow
     * with this endpoint, is {@link DataFlowCallback#onReceivedDataFlowSink(DataFlowSink,
     * HubEndpointInfo, HubEndpointSession, HubMessage)} with the {@link HubEndpointSession} and
     * {@link HubMessage} provided.
     *
     * @param sinkInfo The endpoint info of the sink
     * @param newDataAlertPolicy The {@link DataFlowNewDataAlertPolicy} to apply to the sink
     * @param canOverwrite {@code true} if the source can overwrite the sink's read index when
     *     pushing data, {@code false} otherwise
     * @param session An open messaging session to share the data flow over
     * @param msg The message to send to the sink
     * @throws IllegalArgumentException if {@code msg.isResponseRequired()} is {@code false}
     * @throws IllegalStateException if the data flow has already been shared with the intended sink
     *     or if this source has been stopped
     * @throws NoSuchElementException if the sink's endpoint ID cannot be resolved
     * @throws ConcurrentModificationException if another API is being called concurrently or an
     *     async operation is in progress
     */
    @RequiresPermission(android.Manifest.permission.ACCESS_CONTEXT_HUB)
    @NonNull
    public ContextHubTransaction<Void> shareDataFlowOverSession(
            @NonNull HubEndpointInfo sinkInfo,
            @NonNull DataFlowNewDataAlertPolicy newDataAlertPolicy,
            boolean canOverwrite,
            @NonNull HubEndpointSession session,
            @NonNull HubMessage msg) {
        Objects.requireNonNull(sinkInfo);
        Objects.requireNonNull(newDataAlertPolicy);
        Objects.requireNonNull(session);
        Objects.requireNonNull(msg);
        if (!msg.isResponseRequired()) {
            throw new IllegalArgumentException(
                    "Message must be a response required message to be used with an async"
                            + " callback.");
        }
        try (ApiGuard apiGuard = acquireApiGuard()) {
            ContextHubTransaction<Void> transaction =
                    mEndpoint.shareDataFlow(
                            mRegion,
                            mDataFlowInfo,
                            mDataFlowId,
                            sinkInfo,
                            newDataAlertPolicy,
                            canOverwrite,
                            session,
                            msg);
            mSinks.add(sinkInfo);
            return transaction;
        }
    }

    /**
     * Updates the new data alert and overwrite policies for a sink this data flow is shared with.
     *
     * @param sinkInfo The endpoint info of the sink to update
     * @param newDataAlertPolicy The {@link DataFlowNewDataAlertPolicy} to apply to the sink
     * @param canOverwrite {@code true} if the source can overwrite the sink's read index when
     *     pushing data, {@code false} otherwise
     * @throws IllegalStateException if the sink is not registered on this data flow or if this
     *     source has been stopped
     * @throws ConcurrentModificationException if another API is being called concurrently or an
     *     async operation is in progress
     */
    @RequiresPermission(android.Manifest.permission.ACCESS_CONTEXT_HUB)
    public void updateSinkPolicy(
            @NonNull HubEndpointInfo sinkInfo,
            @NonNull DataFlowNewDataAlertPolicy newDataAlertPolicy,
            boolean canOverwrite) {
        Objects.requireNonNull(sinkInfo);
        Objects.requireNonNull(newDataAlertPolicy);
        try (ApiGuard apiGuard = acquireApiGuard()) {
            mEndpoint.updateSinkPolicy(mRegion, sinkInfo, newDataAlertPolicy, canOverwrite);
        }
    }

    /**
     * Returns the list of sinks this data flow has been shared with, which may be empty.
     *
     * @throws IllegalStateException if this source has been stopped
     * @throws ConcurrentModificationException if another API is being called concurrently or an
     *     async operation is in progress
     */
    @RequiresPermission(android.Manifest.permission.ACCESS_CONTEXT_HUB)
    @NonNull
    public List<HubEndpointInfo> getCurrentSinks() {
        try (ApiGuard apiGuard = acquireApiGuard()) {
            return new ArrayList<>(mSinks);
        }
    }

    /**
     * Disables this source and notifies all sinks.
     *
     * <p>The user should call this when finished pushing to the data flow to release underlying
     * resources as soon as possible.
     *
     * <p>Resources associated with this data flow will be released asynchronously. The user will no
     * longer receive new events via the {@link DataFlowCallback} for this flow.
     *
     * <p>NOTE: Any events currently in flight when this method is called may trigger a callback.
     *
     * <p>It is safe to call this method at any time.
     */
    @Override
    @RequiresPermission(android.Manifest.permission.ACCESS_CONTEXT_HUB)
    public void close() {
        mCloseGuard.close();
        AsyncState cancelOp = null;
        synchronized (mNotificationLock) {
            mNotificationFuture = null;
            cancelOp = mAsyncState;
            mAsyncState = null;
        }
        if (cancelOp != null) {
            try {
                cancelOp.mReceiver.onError(new CancellationException("User closed sink."));
            } catch (Throwable t) {
                // Ignore.
            } finally {
                cancelOp.mGuard.close();
            }
        }
        mEndpoint.removeHostDataFlow(Optional.of(mDataFlowId.id), Optional.of(mRegion.id));
    }

    /**
     * Pushes data to available space in the data flow, returning the number of elements pushed.
     *
     * <p>If {@code allOrNothing} is true, all of the data will be pushed if there is available
     * space, otherewise none of it will be pushed. If {@code allOrNothing} is false, this call will
     * push as much data as there is currently available space for. Partial elements will not be
     * pushed.
     *
     * <p>This API is non-blocking.
     *
     * @param data The data to try to push
     * @param allOrNothing Whether the data should be pushed all or nothing
     * @return The number of elements successfully pushed to the data flow which may be zero
     * @throws IllegalArgumentException if allOrNothing is false and the data flow is a
     *     variable-size element data flow
     * @throws IllegalStateException if this source has been stopped
     * @throws ConcurrentModificationException if another API is being called concurrently or an
     *     async operation is in progress
     */
    @RequiresPermission(android.Manifest.permission.ACCESS_CONTEXT_HUB)
    @IntRange(from = 0)
    public int push(@NonNull DataFlowData data, boolean allOrNothing) {
        Objects.requireNonNull(data);
        try (ApiGuard apiGuard = acquireApiGuard()) {
            return mEndpoint.sourcePush(mRegion, data, allOrNothing);
        }
    }

    /**
     * Pushes data to the data flow, blocking if necessary until space becomes available or an
     * optional {@code timeout} is reached.
     *
     * <p>While in this call, the user will not receive {@link
     * DataFlowCallback#onDataFlowSourceEvent(DataFlowSource, int,
     * DataFlowCallback.SourceEventData)} events of type {@link
     * DataFlowCallback#SOURCE_EVENT_WRITABLE} for this source.
     *
     * @param data The data to push
     * @param timeout The timeout for the push operation, or {@code null} for no timeout
     * @throws TimeoutException if the push operation times out
     * @throws IllegalStateException if this source has been stopped
     * @throws ConcurrentModificationException if another API is being called concurrently or an
     *     async operation is in progress
     */
    @RequiresPermission(android.Manifest.permission.ACCESS_CONTEXT_HUB)
    public void pushBlocking(
            @NonNull DataFlowData data, @Nullable @DurationMillisLong Duration timeout)
            throws TimeoutException {
        Objects.requireNonNull(data);
        try (ApiGuard apiGuard = acquireApiGuard()) {
            long now = SystemClock.elapsedRealtime();
            final long endTime = (timeout == null) ? Long.MAX_VALUE : now + timeout.toMillis();
            do {
                // Create a future to capture any alerts from this point.
                synchronized (mNotificationLock) {
                    mNotificationFuture = new CompletableFuture<>();
                }
                if (mEndpoint.sourcePush(mRegion, data, /* allOrNothing= */ true) > 0) {
                    return;
                }
                // Wait for a notification if the data couldn't be pushed.
                if (timeout == null) {
                    mNotificationFuture.join();
                } else {
                    try {
                        mNotificationFuture.get(
                                endTime - now, java.util.concurrent.TimeUnit.MILLISECONDS);
                    } catch (InterruptedException | ExecutionException e) {
                        // Ignore.
                    }
                }
                now = SystemClock.elapsedRealtime();
            } while (now < endTime);
            throw new TimeoutException("Timed out waiting to push data.");
        } finally {
            synchronized (mNotificationLock) {
                mNotificationFuture = null;
            }
        }
    }

    /**
     * Asynchronous version of {@link #pushBlocking(DataFlowData, Duration)}.
     *
     * <p>Dispatches an asynchronous operation to push the data. The {@code receiver} will be
     * dispatched when the push operation either succeeds or fails. {@link
     * DataFlowCallback#onDataFlowSourceEvent(DataFlowSource, int,
     * DataFlowCallback.SourceEventData)} events of type {@link
     * DataFlowCallback#SOURCE_EVENT_WRITABLE} will be suppressed for this source while the
     * operation is in progress.
     *
     * <p>This operation may be cancelled using an optionally provided {@link
     * android.os.CancellationSignal}. Cancellation may race with the completion of the operation,
     * so the user should use the result or error propagated to {@code receiver} to determine the
     * status.
     *
     * @param data The data to push
     * @param cancellationSignal An optional object for cancelling this operation
     * @param executor An optional {@link Executor} on which the {@code receiver} will be invoked,
     *     otherwise the {@code receiver} will be invoked on the registered executor {@link
     *     HubEndpoint.Builder#setDataFlowCallback(Executor, DataFlowCallback)}.
     * @param receiver The receiver to invoke when a dispatched push operation either succeeds or
     *     fails
     * @throws IllegalStateException if this sink has been stopped
     * @throws ConcurrentModificationException if another API is being called concurrently or an
     *     async operation is in progress
     */
    @RequiresPermission(android.Manifest.permission.ACCESS_CONTEXT_HUB)
    public void pushAsync(
            @NonNull DataFlowData data,
            @Nullable CancellationSignal cancellationSignal,
            @Nullable Executor executor,
            @NonNull OutcomeReceiver<Void, Throwable> receiver) {
        Objects.requireNonNull(data);
        Objects.requireNonNull(receiver);
        ApiGuard guard = acquireApiGuard();
        try {
            executor = executor != null ? executor : mEndpoint.getDataFlowCallbackExecutor();
            AsyncState state = new AsyncState(data, guard, receiver, executor);
            synchronized (mNotificationLock) {
                mAsyncState = state;
            }
            if (cancellationSignal != null) {
                // Set up the cancellation signal to prevent future operations and propagate an
                // error back to the user.
                cancellationSignal.setOnCancelListener(
                        () -> {
                            // Check that this operation is still active and prevent future actions.
                            synchronized (mNotificationLock) {
                                if (mAsyncState != state) {
                                    return;
                                }
                                mAsyncState = null;
                            }
                            try {
                                state.mReceiver.onError(
                                        new CancellationException("User requested cancellation."));
                            } finally {
                                state.mGuard.close();
                            }
                        });
            }
            state.mExecutor.execute(() -> pushDataAsyncRunnable(state));
        } catch (Throwable t) {
            synchronized (mNotificationLock) {
                if (mAsyncState == null) {
                    // Another thread somehow got in here, just return since it will have closed the
                    // guard already.
                    return;
                }
                mAsyncState = null;
                mNotificationFuture = null;
            }
            guard.close();
            throw t;
        }
    }

    /**
     * Returns whether the data flow is full.
     *
     * @return {@code true} if the data flow is full, {@code false} otherwise
     * @throws IllegalStateException if this source has been stopped
     * @throws ConcurrentModificationException if another API is being called concurrently or an
     *     async operation is in progress
     */
    @RequiresPermission(android.Manifest.permission.ACCESS_CONTEXT_HUB)
    public boolean isFull() {
        try (ApiGuard apiGuard = acquireApiGuard()) {
            return mEndpoint.sourceFull(mRegion);
        }
    }

    /**
     * Returns the size of the data flow in bytes with respect to the furthest behind sink.
     *
     * @return The size of the data flow in bytes
     * @throws IllegalStateException if this source has been stopped
     * @throws ConcurrentModificationException if another API is being called concurrently or an
     *     async operation is in progress
     */
    @RequiresPermission(android.Manifest.permission.ACCESS_CONTEXT_HUB)
    @IntRange(from = 0)
    public int size() {
        try (ApiGuard apiGuard = acquireApiGuard()) {
            return mEndpoint.sourceSize(mRegion);
        }
    }

    /* package */ DataFlowSource(
            @NonNull DataFlowDataConfig config,
            @NonNull HubEndpoint endpoint,
            @NonNull SharedDataRegion region,
            @NonNull DataFlowInfo dataFlowInfo,
            @NonNull DataFlowId dataFlowId) {
        mConfig = config;
        mEndpoint = endpoint;
        mRegion = region;
        mDataFlowInfo = dataFlowInfo;
        mDataFlowId = dataFlowId;
        SystemCleaner.cleaner()
                .register(
                        this,
                        () -> {
                            mCloseGuard.warnIfOpen();
                            close();
                        });
    }

    /* package */
    int getRegionId() {
        return mRegion.id;
    }

    /* package */
    void removeSink(@NonNull HubEndpointInfo sinkInfo) {
        mSinks.remove(sinkInfo);
        mEndpoint.removeOffloadSink(mRegion, sinkInfo);
    }

    /**
     * @param event The event to notify for this source.
     * @return true if the event was handled, false otherwise.
     */
    /** @hide */
    boolean onNotificationCallback(int event) {
        synchronized (mNotificationLock) {
            if (event == DataFlowCallback.SOURCE_EVENT_WRITABLE && mNotificationFuture != null) {
                mNotificationFuture.complete(null);
                mNotificationFuture = null;
                return true;
            }
        }
        return false;
    }

    /** Runnable dispatched to push data asynchronously. */
    private void pushDataAsyncRunnable(@NonNull AsyncState state) {
        boolean success = false;
        Throwable error = null;
        synchronized (mNotificationLock) {
            if (state != mAsyncState) {
                // The operation has been cancelled. The ApiGuard will have already been closed.
                return;
            }
            try {
                success = mEndpoint.sourcePush(mRegion, state.mData, /* allOrNothing= */ true) > 0;
                if (success) {
                    // Prevent a concurrent cancellation from triggering.
                    mAsyncState = null;
                }
            } catch (Throwable t) {
                error = t;
                // Prevent a concurrent cancellation from triggering.
                mAsyncState = null;
            }
        }
        // Try to propagate any error back to the user.
        if (error != null) {
            try {
                state.mReceiver.onError(error);
            } catch (Exception e) {
                Log.e(TAG, "Failed to propagate pushDataAsync() error to user: " + e);
            } finally {
                state.mGuard.close();
            }
            return;
        }
        // Try to propagate the data back to the user.
        if (success) {
            try {
                state.mReceiver.onResult(null);
            } catch (Exception e) {
                Log.e(TAG, "Failed to propagate pushDataAsync() result to user: " + e);
            } finally {
                state.mGuard.close();
            }
            return;
        }
        // Set up a new async wait for the data.
        synchronized (mNotificationLock) {
            mNotificationFuture = new CompletableFuture<>();
            var unused =
                    mNotificationFuture.thenRunAsync(
                            () -> pushDataAsyncRunnable(state), state.mExecutor);
        }
    }

    private ApiGuard acquireApiGuard() {
        // Allocate first so that we don't leave mIsBusy set if we OOM.
        ApiGuard guard = new ApiGuard();
        if (!mIsBusy.compareAndSet(/* expectedValue= */ false, /* newValue= */ true)) {
            throw new ConcurrentModificationException(
                    "Another source operation is currently in progress.");
        }
        return guard;
    }
}
