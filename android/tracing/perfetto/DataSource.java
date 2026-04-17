/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.tracing.perfetto;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.util.proto.ProtoInputStream;

import dalvik.annotation.optimization.CriticalNative;

import java.util.HashSet;
import java.util.Set;

/**
 * Templated base class meant to be derived by embedders to create a custom data
 * source.
 *
 * @param <DataSourceInstanceType> The type for the DataSource instances that will be created from
 *                                 this DataSource type.
 * @param <TlsStateType> The type of the custom TLS state, if any is used.
 * @param <IncrementalStateType> The type of the custom incremental state, if any is used.
 *
 * @hide
 */
public abstract class DataSource<DataSourceInstanceType extends DataSourceInstance,
        TlsStateType, IncrementalStateType> {
    protected final long mNativeObj;

    public final String name;

    protected final Set<Integer> mRunningInstances = new HashSet<>();

    @NonNull
    protected final Set<TracingInstanceStartCallback> mOnStartCallbacks = new HashSet<>();
    @NonNull
    protected final Set<TracingInstanceFlushCallback> mOnFlushCallbacks = new HashSet<>();
    @NonNull
    protected final Set<TracingInstanceStopCallback> mOnStopCallbacks = new HashSet<>();

    /**
     * A function implemented by each datasource to create a new data source instance.
     *
     * @param configStream A ProtoInputStream to read the tracing instance's config.
     * @return A new data source instance setup with the provided config.
     */
    @NonNull
    public abstract DataSourceInstanceType createInstance(
            ProtoInputStream configStream, int instanceIndex);

    /**
     * Constructor for datasource base class.
     *
     * @param name The fully qualified name of the datasource.
     */
    public DataSource(String name) {
        this.name = name;
        this.mNativeObj = nativeCreate(this, name);
    }

    /**
     * The main tracing method. Tracing code should call this passing a lambda as
     * argument, with the following signature: void(TraceContext).
     * <p>
     * The lambda will be called synchronously (i.e., always before trace()
     * returns) only if tracing is enabled and the data source has been enabled in
     * the tracing config.
     * <p>
     * The lambda can be called more than once per trace() call, in the case of
     * concurrent tracing sessions (or even if the data source is instantiated
     * twice within the same trace config).
     *
     * @param fun The tracing lambda that will be called with the tracing contexts of each active
     *            tracing instance.
     */
    public final void trace(
            TraceFunction<DataSourceInstanceType, TlsStateType, IncrementalStateType> fun) {
        boolean startedIterator = nativePerfettoDsTraceIterateBegin(mNativeObj);

        if (!startedIterator) {
            return;
        }

        try {
            do {
                int instanceIndex = nativeGetPerfettoDsInstanceIndex(mNativeObj);

                TracingContext<DataSourceInstanceType, TlsStateType, IncrementalStateType> ctx =
                        new TracingContext<>(this, instanceIndex);
                fun.trace(ctx);

                nativeWritePackets(mNativeObj, ctx.getAndClearAllPendingTracePackets());
            } while (nativePerfettoDsTraceIterateNext(mNativeObj));
        } finally {
            nativePerfettoDsTraceIterateBreak(mNativeObj);
        }
    }

    /**
     * Flush any trace data from this datasource that has not yet been flushed.
     */
    public final void flush() {
        nativeFlushAll(mNativeObj);
    }

    /**
     * Override this method to create a custom TlsState object for your DataSource. A new instance
     * will be created per trace instance per thread.
     */
    @Nullable
    public TlsStateType createTlsState(CreateTlsStateArgs<DataSourceInstanceType> args) {
        return null;
    }

    /**
     * Override this method to create and use a custom IncrementalState object for your DataSource.
     *
     */
    @Nullable
    public IncrementalStateType createIncrementalState(
            CreateIncrementalStateArgs<DataSourceInstanceType> args) {
        return null;
    }

    /**
     * Registers the data source on all tracing backends, including ones that
     * connect after the registration. Doing so enables the data source to receive
     * Setup/Start/Stop notifications and makes the trace() method work when
     * tracing is enabled and the data source is selected.
     * <p>
     * NOTE: Once registered, we cannot unregister the data source. Therefore, we should avoid
     * creating and registering data source where not strictly required. This is a fundamental
     * limitation of Perfetto itself.
     *
     * @param params Params to initialize the datasource with.
     */
    public void register(DataSourceParams params) {
        nativeRegisterDataSource(this.mNativeObj, params.bufferExhaustedPolicy,
                params.willNotifyOnStop, params.noFlush, params.postponeStop);
    }

    /**
     * Gets the datasource instance with a specified index.
     * IMPORTANT: releaseDataSourceInstance must be called after using the datasource instance.
     * @param instanceIndex The index of the datasource to lock and get.
     * @return The DataSourceInstance at index instanceIndex.
     *         Null if the datasource instance at the requested index doesn't exist.
     */
    @Nullable
    public DataSourceInstanceType getDataSourceInstanceLocked(int instanceIndex) {
        return (DataSourceInstanceType) nativeGetPerfettoInstanceLocked(mNativeObj, instanceIndex);
    }

    /**
     * Register a callback to be executed when a tracing instance starts.
     * @param callback The callback to be executed when a tracing instance starts.
     */
    public void registerOnStartCallback(TracingInstanceStartCallback callback) {
        mOnStartCallbacks.add(callback);
    }

    /**
     * Register a callback to be executed when a tracing instance flushes.
     * @param callback The callback to be executed when a tracing instance flushes.
     */
    public void registerOnFlushCallback(TracingInstanceFlushCallback callback) {
        mOnFlushCallbacks.add(callback);
    }

    /**
     * Register a callback to be executed when a tracing instance stops.
     * @param callback The callback to be executed when a tracing instance stops.
     */
    public void registerOnStopCallback(TracingInstanceStopCallback callback) {
        mOnStopCallbacks.add(callback);
    }

    /**
     * Unregister a callback to not execute it when a tracing instance starts.
     * @param callback The callback to unregister.
     */
    public void unregisterOnStartCallback(TracingInstanceStartCallback callback) {
        mOnStartCallbacks.remove(callback);
    }

    /**
     * Unregister a callback to not execute it when a tracing instance flushes.
     * @param callback The callback to unregister.
     */
    public void unregisterOnFlushCallback(TracingInstanceFlushCallback callback) {
        mOnFlushCallbacks.remove(callback);
    }

    /**
     * Unregister a callback to not execute it when a tracing instance stops.
     * @param callback The callback to unregister.
     */
    public void unregisterOnStopCallback(TracingInstanceStopCallback callback) {
        mOnStopCallbacks.remove(callback);
    }

    /**
     * Unlock the datasource at the specified index.
     * @param instanceIndex The index of the datasource to unlock.
     */
    protected void releaseDataSourceInstance(int instanceIndex) {
        nativeReleasePerfettoInstanceLocked(mNativeObj, instanceIndex);
    }

    /**
     * Called from native side when a new tracing instance starts.
     *
     * @param rawConfig byte array of the PerfettoConfig encoded proto.
     * @return A new Java DataSourceInstance object.
     */
    @NonNull
    private DataSourceInstanceType createInstance(byte[] rawConfig, int instanceIndex) {
        final ProtoInputStream inputStream = new ProtoInputStream(rawConfig);
        return this.createInstance(inputStream, instanceIndex);
    }

    /**
     * Called from native when creating a new tracing instance fails. Default
     * implementation is a no-op. Data sources can override this to get notified.
     *
     * <p>An instance can fail to be created for multiple reasons, including:
     * <ul>
     *      <li>A native allocation failure.</li>
     *      <li>The user-provided {@link #createInstance(ProtoInputStream, int)} method throwing an
     *          exception.</li>
     *      <li>The user-provided {@link #createInstance(ProtoInputStream, int)} method returning
     *          null.</li>
     * </ul>
     *
     * @param instanceIndex The index of the datasource instance that failed to be created.
     * @param reason A string explaining the reason for failure.
     */
    protected void onInstanceCreateFailed(int instanceIndex, @NonNull String reason) {
        // Default implementation is a no-op.
    }

    /**
     * Stop the datasource instance at the specified index (whose stop operation was previously
     * postponed with DataSourceParams#postponeStop).
     */
    protected void stopDoneDataSourceInstance(int instanceIndex) {
        nativeStopDonePerfettoInstanceLocked(mNativeObj, instanceIndex);
    }

    @FunctionalInterface
    public interface TracingInstanceStartCallback {
        /**
         * Execute the tracing instance's onStart callback.
         * @param instanceIdx The index of the tracing instance we are executing the callback
         *                    for.
         */
        void onTracingInstanceStart(int instanceIdx);
    }

    @FunctionalInterface
    public interface TracingInstanceFlushCallback {
        /**
         * Execute the tracing instance's onFlush callback.
         */
        void onTracingFlush();
    }

    @FunctionalInterface
    public interface TracingInstanceStopCallback {
        /**
         * Execute the tracing instance's onStop callback.
         * @param instanceIdx The index of the tracing instance we are executing the callback
         *                    for.
         */
        void onTracingInstanceStop(int instanceIdx);
    }

    private static native void nativeRegisterDataSource(long dataSourcePtr,
            int bufferExhaustedPolicy, boolean willNotifyOnStop, boolean noFlush,
            boolean postponeStop);

    private static native long nativeCreate(DataSource thiz, String name);
    private static native void nativeFlushAll(long nativeDataSourcePointer);
    private static native long nativeGetFinalizer();

    private static native DataSourceInstance nativeGetPerfettoInstanceLocked(
            long dataSourcePtr, int dsInstanceIdx);
    private static native void nativeReleasePerfettoInstanceLocked(
            long dataSourcePtr, int dsInstanceIdx);

    private static native void nativeStopDonePerfettoInstanceLocked(
            long dataSourcePtr, int dsInstanceIdx);

    @CriticalNative
    private static native boolean nativePerfettoDsTraceIterateBegin(long dataSourcePtr);
    @CriticalNative
    private static native boolean nativePerfettoDsTraceIterateNext(long dataSourcePtr);
    @CriticalNative
    private static native void nativePerfettoDsTraceIterateBreak(long dataSourcePtr);
    @CriticalNative
    private static native int nativeGetPerfettoDsInstanceIndex(long dataSourcePtr);

    private static native void nativeWritePackets(long dataSourcePtr, byte[][] packetData);
}
