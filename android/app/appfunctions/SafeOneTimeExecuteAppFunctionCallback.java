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

package android.app.appfunctions;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import java.util.ArrayList;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A wrapper of IExecuteAppFunctionCallback which swallows the {@link RemoteException}. This
 * callback is intended for one-time use only. Subsequent calls to onResult() or onError() will be
 * ignored.
 *
 * @hide
 */
public class SafeOneTimeExecuteAppFunctionCallback {
    private static final String TAG = "SafeOneTimeExecuteApp";

    private final AtomicBoolean mOnResultCalled = new AtomicBoolean(false);

    @NonNull private final IExecuteAppFunctionCallback mCallback;

    @Nullable private ArrayList<CompletionCallback> mCompletionCallbacks;

    @Nullable private final BeforeCompletionCallback mBeforeCompletionCallback;

    private final AtomicLong mExecutionStartTimeAfterBindMillis = new AtomicLong();

    public SafeOneTimeExecuteAppFunctionCallback(@NonNull IExecuteAppFunctionCallback callback) {
        this(callback, /* beforeCompletionCallback= */ null, /* completionCallback= */ null);
    }

    public SafeOneTimeExecuteAppFunctionCallback(
            @NonNull IExecuteAppFunctionCallback callback,
            @Nullable BeforeCompletionCallback beforeCompletionCallback,
            @Nullable CompletionCallback completionCallback) {
        mCallback = Objects.requireNonNull(callback);
        mBeforeCompletionCallback = beforeCompletionCallback;
        if (completionCallback != null) {
            mCompletionCallbacks = new ArrayList<>(1);
            mCompletionCallbacks.add(completionCallback);
        }
    }

    /** Invoke wrapped callback with the result. */
    public void onResult(@NonNull ExecuteAppFunctionResponse result) {
        if (!mOnResultCalled.compareAndSet(false, true)) {
            Log.w(TAG, "Ignore subsequent calls to onResult/onError()");
            return;
        }
        try {
            if (mBeforeCompletionCallback != null) {
                mBeforeCompletionCallback.beforeOnSuccess(result);
            }
            mCallback.onSuccess(result);
            if (mCompletionCallbacks != null) {
                for (CompletionCallback completionCallback : mCompletionCallbacks) {
                    completionCallback.finalizeOnSuccess(
                            result, mExecutionStartTimeAfterBindMillis.get());
                }
            }
        } catch (RemoteException ex) {
            // Failed to notify the other end. Ignore.
            Log.w(TAG, "Failed to invoke the callback", ex);
        }
    }

    /** Invoke wrapped callback with the error. */
    public void onError(@NonNull AppFunctionException error) {
        if (!mOnResultCalled.compareAndSet(false, true)) {
            Log.w(TAG, "Ignore subsequent calls to onResult/onError()");
            return;
        }
        try {
            mCallback.onError(error);
            if (mCompletionCallbacks != null) {
                for (CompletionCallback completionCallback : mCompletionCallbacks) {
                    completionCallback.finalizeOnError(
                            error, mExecutionStartTimeAfterBindMillis.get());
                }
            }
        } catch (RemoteException ex) {
            // Failed to notify the other end. Ignore.
            Log.w(TAG, "Failed to invoke the callback", ex);
        }
    }

    /**
     * Disables this callback. Subsequent calls to {@link #onResult(ExecuteAppFunctionResponse)} or
     * {@link #onError(AppFunctionException)} will be ignored.
     */
    public void disable() {
        mOnResultCalled.set(true);
    }

    /**
     * Sets the execution start time of the request. Used to calculate the overhead latency of
     * requests.
     */
    public void setExecutionStartTimeAfterBindMillis(long executionStartTimeAfterBindMillis) {
        if (!mExecutionStartTimeAfterBindMillis.compareAndSet(
                0, executionStartTimeAfterBindMillis)) {
            Log.w(TAG, "Ignore subsequent calls to setExecutionStartTimeAfterBindMillis()");
        }
    }

    /**
     * Creates an {@link IExecuteAppFunctionCallback} that delegates its calls to the {@link
     * #onResult(ExecuteAppFunctionResponse)} and {@link #onError(AppFunctionException)} methods of
     * this {@code SafeOneTimeExecuteAppFunctionCallback} instance.
     *
     * @return A new {@link IExecuteAppFunctionCallback} instance that wraps this callback.
     */
    public IExecuteAppFunctionCallback wrapToExecutionCallback() {
        return new IExecuteAppFunctionCallback.Stub() {
            @Override
            public void onSuccess(ExecuteAppFunctionResponse response) throws RemoteException {
                SafeOneTimeExecuteAppFunctionCallback.this.onResult(response);
            }

            @Override
            public void onError(AppFunctionException error) throws RemoteException {
                SafeOneTimeExecuteAppFunctionCallback.this.onError(error);
            }
        };
    }

    /**
     * Attaches a death recipient to the passed binder, propagates error if binder dies.
     *
     * @param binder Binder to attach listener to
     * @throws RemoteException in case passed binder already died
     */
    public void attachOnDeathListener(IBinder binder) throws RemoteException {
        IBinder.DeathRecipient listener =
                (IBinder.DeathRecipient)
                        () ->
                                onError(
                                        new AppFunctionException(
                                                AppFunctionException.ERROR_APP_UNKNOWN_ERROR,
                                                "Remote process has gone away"));
        binder.linkToDeath(listener, 0);

        if (mCompletionCallbacks == null) {
            mCompletionCallbacks = new ArrayList<>(1);
        }
        mCompletionCallbacks.add(
                new CompletionCallback() {
                    @Override
                    public void finalizeOnSuccess(
                            @NonNull ExecuteAppFunctionResponse result,
                            long executionStartTimeMillis) {
                        binder.unlinkToDeath(listener, 0);
                    }

                    @Override
                    public void finalizeOnError(
                            @NonNull AppFunctionException error, long executionStartTimeMillis) {
                        binder.unlinkToDeath(listener, 0);
                    }
                });
    }

    /**
     * Provides a hook to execute additional actions after the {@link IExecuteAppFunctionCallback}
     * has been invoked.
     */
    public interface CompletionCallback {
        /** Called after {@link IExecuteAppFunctionCallback#onSuccess}. */
        void finalizeOnSuccess(
                @NonNull ExecuteAppFunctionResponse result, long executionStartTimeMillis);

        /** Called after {@link IExecuteAppFunctionCallback#onError}. */
        void finalizeOnError(@NonNull AppFunctionException error, long executionStartTimeMillis);
    }

    /**
     * Provides a hook to execute additional actions before the {@link IExecuteAppFunctionCallback}
     * has been invoked.
     */
    public interface BeforeCompletionCallback {
        /**
         * Called before {@link IExecuteAppFunctionCallback#onSuccess(ExecuteAppFunctionResponse)}
         * is invoked.
         *
         * @param result The result that will be passed to the main callback.
         */
        void beforeOnSuccess(@NonNull ExecuteAppFunctionResponse result);
    }
}
