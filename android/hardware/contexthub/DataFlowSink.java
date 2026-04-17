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
import android.annotation.Size;
import android.annotation.SystemApi;
import android.chre.flags.Flags;
import android.os.CancellationSignal;
import android.os.OutcomeReceiver;
import android.os.SystemClock;
import android.system.SystemCleaner;
import android.util.CloseGuard;

import android.util.Log;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ConcurrentModificationException;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Represents a sink on a data flow whose source is an offload endpoint. New instances of this class
 * are obtained on {@link DataFlowCallback#onReceivedDataFlowSink(DataFlowSink, HubEndpointInfo,
 * HubMessage, int)} events. See {@link HubEndpoint#createDataFlowSource(Set, DataFlowDataConfig,
 * int, int)} for an overview of data flows.
 *
 * <p>This class's API is expected to be invoked from a single thread at a time. It may be invoked
 * from different threads so long as the user ensures this using an appropriate mechanism (e.g.
 * mutex, dispatching to a single-threaded executor).
 *
 * <p>From the point that an instance of this class is created, it has the ability to access the
 * data flow until either the user calls {@link DataFlowSink#stop()}, the source stops the data flow
 * (user recognizable via {@link DataFlowCallback#onDataFlowStopped(DataFlowSink)}), the source
 * endpoint disconnects from the messaging network (see {@link
 * android.hardware.location.ContextHubManager#registerEndpointDiscoveryCallback(Executor,
 * HubEndpointDiscoveryCallback, long)}), or a call to any of the API methods fails with a {@link
 * RuntimeException}, indicating a lower-level error in communication that makes this data flow
 * unusable.
 *
 * @hide
 */
@SystemApi
@FlaggedApi(Flags.FLAG_FMCQ_API)
public final class DataFlowSink implements AutoCloseable {
    private static final String TAG = "DataFlowSink";

    /** The configuration of data read from this data flow. */
    private final DataFlowDataConfig mConfig;

    private final DataFlowSinkContext mContext;
    private final HubEndpoint mEndpoint;

    @Nullable private CompletableFuture<Void> mNotificationFuture;
    private final Object mNotificationLock = new Object();

    /** Close guard to warn when the user hasn't explicitly {@link #close()}d this instance. */
    private final CloseGuard mCloseGuard = new CloseGuard();

    private AtomicBoolean mIsBusy = new AtomicBoolean(false);

    private final class ApiGuard implements AutoCloseable {
        @Override
        public void close() {
            mIsBusy.set(false);
        }
    }

    /** The state associated with an ongoing asynchronous operation. */
    private static final class AsyncState {
        final int mElementCount;
        final ApiGuard mGuard;
        final OutcomeReceiver<DataFlowData, Throwable> mReceiver;
        final Executor mExecutor;

        AsyncState(
                @Size(min = 1) int elementCount,
                @NonNull ApiGuard guard,
                @NonNull OutcomeReceiver<DataFlowData, Throwable> receiver,
                @Nullable Executor executor) {
            mElementCount = elementCount;
            mGuard = guard;
            mReceiver = receiver;
            mExecutor = executor;
        }
    }

    /** Used to check whether an async operation is stale. Guarded by mNotificationLock. */
    @Nullable private AsyncState mAsyncState = null;

    /** Returns the configuration of elements read from this data flow. */
    @RequiresPermission(android.Manifest.permission.ACCESS_CONTEXT_HUB)
    @NonNull
    public DataFlowDataConfig getDataFlowConfig() {
        return mConfig;
    }

    /**
     * Disables this sink and alerts the source.
     *
     * <p>The user should call this when finished reading from the data flow to release underlying
     * resources as soon as possible.
     *
     * <p>Resources associated with this sink will be released asynchronously. The user will no
     * longer receive new events via the {@link DataFlowCallback} for this data flow.
     *
     * <p>NOTE: Any events currently in flight when this method is called may trigger the callback.
     *
     * <p>It is safe to call this method at any time. The user does not need to explicitly call this
     * method if they receive a {@link DataFlowCallback#onDataFlowSinkEvent(DataFlowSink, int,
     * SinkEventData)} event of type {@link DataFlowCallback#SINK_EVENT_STOPPED} for this instance.
     */
    @Override
    @RequiresPermission(android.Manifest.permission.ACCESS_CONTEXT_HUB)
    public void close() {
        closeInternal();
    }

    /**
     * Reads one or more elements from a data flow if available.
     *
     * @param elementCount The number of elements to attempt to read
     * @param allOrNothing If {@code true}, this method will return {@code null} if there are fewer
     *     {@code count} elements to read
     * @return A {@link DataFlowData} containing the data read, otherwise {@code null} if no data
     *     was available to be read or if {@code allOrNothing} is {@code true} and there are fewer
     *     than {@code count} elements to read
     * @throws IllegalStateException if this sink has been stopped
     * @throws ConcurrentModificationException if another API is being called concurrently or an
     *     async operation is in progress
     */
    @RequiresPermission(android.Manifest.permission.ACCESS_CONTEXT_HUB)
    @Nullable
    public DataFlowData readData(@Size(min = 1) int elementCount, boolean allOrNothing) {
        try (ApiGuard guard = acquireApiGuard()) {
            return requestDataInternal(elementCount, allOrNothing);
        }
    }

    /**
     * Blocking version of {@link #readData(int, boolean)} that necessarily returns {@code
     * elementCount} elements.
     *
     * <p>While this method is blocking, {@link DataFlowCallback#onDataFlowSinkEvent(DataFlowSink,
     * int, DataFlowCallback.SinkEventData)} events of type {@link
     * DataFlowCallback#SINK_EVENT_READABLE} will be suppressed for this sink. It blocks until the
     * requested data is available or the timeout is reached.
     *
     * @param elementCount The number of elements to read
     * @param timeout The timeout for the operation, otherwise {@code null} for no timeout
     * @return A {@link DataFlowData} containing {@code elementCount} elements
     * @throws TimeoutException if the operation times out
     * @throws IllegalStateException if this sink has been stopped
     * @throws ConcurrentModificationException if another API is being called concurrently or an
     *     async operation is in progress
     */
    @RequiresPermission(android.Manifest.permission.ACCESS_CONTEXT_HUB)
    @NonNull
    public DataFlowData awaitData(
            @Size(min = 1) int elementCount, @Nullable @DurationMillisLong Duration timeout)
            throws TimeoutException {
        DataFlowData data = null;
        try (ApiGuard guard = acquireApiGuard()) {
            synchronized (mNotificationLock) {
                mNotificationFuture = new CompletableFuture<>();
            }
            long now = SystemClock.elapsedRealtime();
            long endTime = (timeout == null) ? Long.MAX_VALUE : now + timeout.toMillis();

            while (now < endTime) {
                data = requestDataInternal(elementCount, /* allOrNothing= */ true);
                if (data != null) {
                    return data;
                }

                // If no data, wait for notification or timeout
                long remainingTime = endTime - now;
                if (remainingTime <= 0) {
                    break; // Timeout reached
                }

                if (timeout == null) {
                    mNotificationFuture.join();
                } else {
                    try {
                        mNotificationFuture.get(
                                remainingTime, java.util.concurrent.TimeUnit.MILLISECONDS);
                    } catch (InterruptedException | ExecutionException e) {
                        // Ignore.
                    }
                }
                // Reset future after completion to allow re-waiting
                synchronized (mNotificationLock) {
                    mNotificationFuture = new CompletableFuture<>();
                }
                now = SystemClock.elapsedRealtime();
            }

            throw new TimeoutException("Timed out waiting for data.");
        } finally {
            // No longer awaiting, clear the future
            synchronized (mNotificationLock) {
                mNotificationFuture = null;
            }
        }
    }

    /**
     * Asynchronous version of {@link #awaitData(int, Duration)}.
     *
     * <p>Dispatches an asynchronous operation to await the requested data. The {@code receiver}
     * will be dispatched when the requested data is available or on error. {@link
     * DataFlowCallback#onDataFlowSinkEvent(DataFlowSink, int, DataFlowCallback.SinkEventData)}
     * events of type {@link DataFlowCallback#SINK_EVENT_READABLE} will be suppressed for this sink
     * while the operation is in progress.
     *
     * <p>If provided, the {@code executor} will be used to dispatch the {@code receiver}.
     * Otherwise, the {@code receiver} will be dispatched on the executor registered with {@link
     * HubEndpoint.Builder#setDataFlowCallback(Executor, DataFlowCallback)}.
     *
     * <p>This operation may be cancelled using an optionally provided {@link
     * android.os.CancellationSignal}. Cancellation may race with the completion of the operation,
     * so the user should use the result or error propagated to {@code receiver} to determine the
     * status.
     *
     * @param elementCount The number of elements to read
     * @param cancellationSignal An optional object for cancelling this operation
     * @param executor An optional {@link Executor} to override the default executor for dispatching
     *     the {@code receiver}
     * @param receiver The {@link OutcomeReceiver} to dispatch when a dispatched pop operation
     *     either succeeds or fails
     * @throws IllegalStateException if this sink has been stopped
     * @throws ConcurrentModificationException if another API is being called concurrently or an
     *     async operation is in progress
     */
    @RequiresPermission(android.Manifest.permission.ACCESS_CONTEXT_HUB)
    public void awaitDataAsync(
            @Size(min = 1) int elementCount,
            @Nullable CancellationSignal cancellationSignal,
            @Nullable Executor executor,
            @NonNull OutcomeReceiver<DataFlowData, Throwable> receiver) {
        Objects.requireNonNull(receiver);
        ApiGuard guard = acquireApiGuard();
        try {
            executor = executor != null ? executor : mEndpoint.getDataFlowCallbackExecutor();
            AsyncState state = new AsyncState(elementCount, guard, receiver, executor);
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
            state.mExecutor.execute(() -> awaitDataAsyncRunnable(state));
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
     * Moves the read position forward to a specified offset behind the source's current write
     * position.
     *
     * <p>The purpose of this method is to allow a sink to fast-forward through stale data to data
     * that is actually relevant to it. For example, a sink may only care about data from the last
     * second, but be configured with a {@link DataFlowNewDataAlertPolicy#getOpportunistic()} policy
     * by the source. When the source does actually alerts this sink, the data flow may be full of
     * very old data that the sink cannot use. This method allows the sink to discard all but the
     * most recent data.
     *
     * <p>This API is non-blocking.
     *
     * @param offsetFromSource The offset in bytes behind the source's current write position
     * @throws IllegalArgumentException if the offset is not a multiple of the element size for
     *     fixed-size element data flows or if the offset is greater than the current size of the
     *     data flow
     * @throws IllegalStateException if this sink has been stopped
     * @throws ConcurrentModificationException if another API is being called concurrently or an
     *     async operation is in progress
     */
    @RequiresPermission(android.Manifest.permission.ACCESS_CONTEXT_HUB)
    public void seekToSource(@IntRange(from = 0) int offsetFromSource) {
        try (ApiGuard guard = acquireApiGuard()) {
            if (mConfig.getFormat() == DataFlowDataConfig.FORMAT_FIXED_SIZE
                    && offsetFromSource % mConfig.getElementSize() != 0) {
                throw new IllegalArgumentException(
                        "seekToSource() offset must be a multiple of the element size for"
                                + " fixed-size element data flows.");
            } else if (offsetFromSource > size()) {
                throw new IllegalArgumentException(
                        "seekToSource() offset must be less than or equal to the current size of"
                                + " the data flow.");
            }
            mEndpoint.sinkSyncToSource(mContext, offsetFromSource);
        }
    }

    /**
     * Returns whether this sink's read position can be overwritten by the source when it wraps
     * around the shared memory region.
     *
     * @throws IllegalStateException if this sink has been stopped
     * @throws ConcurrentModificationException if another API is being called concurrently or an
     *     async operation is in progress
     */
    @RequiresPermission(android.Manifest.permission.ACCESS_CONTEXT_HUB)
    public boolean canSourceOverwriteReadPosition() {
        try (ApiGuard guard = acquireApiGuard()) {
            return mEndpoint.sinkSourceCanOverwriteReadPosition(mContext);
        }
    }

    /**
     * Returns whether this sink has data available to read.
     *
     * @throws IllegalStateException if this sink has been stopped
     * @throws ConcurrentModificationException if another API is being called concurrently or an
     *     async operation is in progress
     */
    @RequiresPermission(android.Manifest.permission.ACCESS_CONTEXT_HUB)
    public boolean isEmpty() {
        try (ApiGuard guard = acquireApiGuard()) {
            return size() == 0;
        }
    }

    /**
     * Returns the size of the data flow in bytes with respect to this sink's read position.
     *
     * @throws IllegalStateException if this sink has been stopped
     * @throws ConcurrentModificationException if another API is being called concurrently or an
     *     async operation is in progress
     */
    @RequiresPermission(android.Manifest.permission.ACCESS_CONTEXT_HUB)
    @IntRange(from = 0)
    public int size() {
        try (ApiGuard guard = acquireApiGuard()) {
            return mEndpoint.sinkSize(mContext);
        }
    }

    /* package */ DataFlowSink(
            @NonNull DataFlowDataConfig config,
            @NonNull DataFlowSinkContext context,
            @NonNull HubEndpoint endpoint) {
        mConfig = config;
        mContext = context;
        mEndpoint = endpoint;
        SystemCleaner.cleaner()
                .register(
                        this,
                        () -> {
                            mCloseGuard.warnIfOpen();
                            close();
                        });
    }

    /**
     * @param event The event to notify for this sink.
     * @return true if the event was handled, false otherwise.
     */
    /** @hide */
    boolean onNotificationCallback(int event) {
        synchronized (mNotificationLock) {
            if (event == DataFlowCallback.SINK_EVENT_READABLE && mNotificationFuture != null) {
                mNotificationFuture.complete(null);
                mNotificationFuture = null;
                return true;
            }
        }
        return false;
    }

    /**
     * Package-private implementation of {@link #close()}.
     *
     * @hide
     */
    /* package */ void closeInternal() {
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
        mEndpoint.removeSink(mContext);
    }

    /** Runnable dispatched to await data asynchronously. */
    private void awaitDataAsyncRunnable(@NonNull AsyncState state) {
        DataFlowData data = null;
        Throwable error = null;
        synchronized (mNotificationLock) {
            if (state != mAsyncState) {
                // The operation has been cancelled. The ApiGuard will have already been closed.
                return;
            }
            try {
                data = requestDataInternal(state.mElementCount, /* allOrNothing= */ true);
                if (data != null) {
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
                Log.e(TAG, "Failed to propagate awaitDataAsync() error to user: " + e);
            } finally {
                state.mGuard.close();
            }
            return;
        }
        // Try to propagate the data back to the user.
        if (data != null) {
            try {
                state.mReceiver.onResult(data);
            } catch (Exception e) {
                Log.e(TAG, "Failed to propagate awaitDataAsync() result to user: " + e);
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
                            () -> awaitDataAsyncRunnable(state), state.mExecutor);
        }
    }

    private DataFlowData requestDataInternal(int elementCount, boolean allOrNothing) {
        byte[] buffer = mEndpoint.sinkRequestData(mContext, elementCount, allOrNothing);
        return buffer == null ? null : new DataFlowData(ByteBuffer.wrap(buffer), mConfig);
    }

    private ApiGuard acquireApiGuard() {
        if (!mIsBusy.compareAndSet(/* expectedValue= */ false, /* newValue= */ true)) {
            throw new ConcurrentModificationException(
                    "Another sink operation is currently in progress.");
        }
        return new ApiGuard();
    }
}
