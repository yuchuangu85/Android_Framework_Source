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

import android.annotation.CallSuper;

import com.android.internal.annotations.VisibleForTesting;

import java.util.Set;

/**
 * @hide
 */
public abstract class DataSourceInstance implements AutoCloseable {
    private final DataSource mDataSource;
    private final int mInstanceIndex;

    public DataSourceInstance(DataSource dataSource, int instanceIndex) {
        this.mDataSource = dataSource;
        this.mInstanceIndex = instanceIndex;
    }

    /**
     * Executed when the tracing instance starts running.
     * <p>
     * NOTE: This callback executes on the Perfetto internal thread and is blocking.
     *       Anything that is run in this callback should execute quickly.
     *
     * @param args Start arguments.
     */
    @CallSuper
    protected void onStart(StartCallbackArguments args) {
        synchronized (getDataSource().mRunningInstances) {
            getDataSource().mRunningInstances.add(getInstanceIndex());

            final Set<DataSource.TracingInstanceStartCallback> cbs =
                    getDataSource().mOnStartCallbacks;
            for (DataSource.TracingInstanceStartCallback onStartCb : cbs) {
                onStartCb.onTracingInstanceStart(getInstanceIndex());
            }
        }
    }

    /**
     * Executed when a flush is triggered.
     * <p>
     * NOTE: This callback executes on the Perfetto internal thread and is blocking.
     *       Anything that is run in this callback should execute quickly.
     * @param args Flush arguments.
     */
    @CallSuper
    protected void onFlush(FlushCallbackArguments args) {
        final Set<DataSource.TracingInstanceFlushCallback> cbs = getDataSource().mOnFlushCallbacks;
        for (var onFlushCb : cbs) {
            onFlushCb.onTracingFlush();
        }
    }

    /**
     * Executed when the tracing instance is stopped.
     * <p>
     * NOTE: This callback executes on the Perfetto internal thread and is blocking.
     *       Anything that is run in this callback should execute quickly.
     * @param args Stop arguments.
     */
    @CallSuper
    protected void onStop(StopCallbackArguments args) {
        synchronized (getDataSource().mRunningInstances) {
            getDataSource().mRunningInstances.remove(getInstanceIndex());

            final Set<DataSource.TracingInstanceStopCallback> cbs =
                    getDataSource().mOnStopCallbacks;
            for (var onFlushCb : cbs) {
                onFlushCb.onTracingInstanceStop(getInstanceIndex());
            }
        }
    }

    /**
     * Stop the data source instance (whose stop operation was previously postponed
     * with DataSourceParams#postponeStop).
     */
    public void stopDone() {
        mDataSource.stopDoneDataSourceInstance(mInstanceIndex);
    }

    @Override
    public final void close() {
        this.release();
    }

    /**
     * Release the lock on the datasource once you are finished using it.
     * Only required to be called when instance was retrieved with
     * `DataSource#getDataSourceInstanceLocked`.
     */
    @VisibleForTesting
    public void release() {
        mDataSource.releaseDataSourceInstance(mInstanceIndex);
    }

    public final int getInstanceIndex() {
        return mInstanceIndex;
    }

    /**
     * Returns the {@link DataSource} this instance is associated with.
     */
    public final DataSource getDataSource() {
        return mDataSource;
    }
}
