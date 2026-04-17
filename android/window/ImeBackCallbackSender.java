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

package android.window;

import static android.view.inputmethod.Flags.backDispositionControlsBackInterception;
import static android.window.ImeBackCallbackProxy.RESULT_CODE_REGISTER;
import static android.window.ImeBackCallbackProxy.RESULT_CODE_UNREGISTER;
import static android.window.ImeBackCallbackProxy.RESULT_KEY_CALLBACK;
import static android.window.ImeBackCallbackProxy.RESULT_KEY_ID;
import static android.window.ImeBackCallbackProxy.RESULT_KEY_PRIORITY;

import android.annotation.NonNull;
import android.annotation.SuppressLint;
import android.os.Bundle;
import android.os.Handler;
import android.os.ResultReceiver;
import android.util.Log;
import android.util.Pair;

import java.io.PrintWriter;
import java.util.ArrayDeque;
import java.util.function.Consumer;

/**
 * An {@link OnBackInvokedDispatcher} (on the IME process side) for IME callbacks.
 *
 * <p>See {@link ImeBackCallbackProxy} for the counterpart of this class on the app process side.
 *
 * <p>This class lives in the IME process. Namely it is instantiated from InputMethodService and set
 * on the {@link WindowOnBackInvokedDispatcher} of the IME window. It handles any callback
 * registrations and unregistrations within the IME process and forwards them to the app process
 * through the ResultReceiver connection.
 *
 * <p>This class also ensures that any non-system back callbacks can only be registered in the app
 * process while the system IME back callback is registered (the one that causes the IME to get
 * hidden).
 *
 * <p>Furthermore, it allows for the registration of the default system callback to be skipped,
 * enabling scenarios where back events should bypass the IME and be handled directly by the
 * application.
 *
 * @hide
 * @see ImeBackCallbackProxy
 * @see WindowOnBackInvokedDispatcher#setImeBackCallbackSender(ImeBackCallbackSender)
 */
public class ImeBackCallbackSender implements OnBackInvokedDispatcher {
    private static final String TAG = "ImeBackCallbackSender";

    // The handler to run callbacks on. This should be on the same thread
    // the ViewRootImpl holding IME's WindowOnBackInvokedDispatcher is created on.
    private Handler mHandler;
    private final ArrayDeque<Pair<Integer, OnBackInvokedCallback>> mNonSystemCallbacks =
            new ArrayDeque<>();
    /**
     * The system back callback. This is the callback that causes the IME to get hidden.
     * This field's nullness is equivalent to the state of
     * {@code InputMethodService#mBackCallbackRegistered}. When not null, a callback has been
     * registered by InputMethodService.
     */
    private OnBackInvokedCallback mSystemCallback = null;
    /**
     * Whether the default system back callback registration should be skipped. When this is
     * {@code true}, the {@link #mSystemCallback} will not be sent to the app process for
     * registration.
     */
    private boolean mSkipDefaultCallbackRegistration = false;
    private String mTargetAppPackageName = "";
    private ResultReceiver mResultReceiver;

    public ImeBackCallbackSender() {
    }

    public void setTargetAppPackageName(String targetAppPackageName) {
        mTargetAppPackageName = targetAppPackageName;
    }

    public void setResultReceiver(ResultReceiver resultReceiver) {
        mResultReceiver = resultReceiver;
    }

    /**
     * Sets whether the default system back callback registration should be skipped.
     *
     * <p>When {@code true}, the default system back callback (the one that causes the IME to get
     * hidden) will not be registered at the app process. If the system callback is currently
     * registered, it will be unregistered. If {@code false} and a system callback is available,
     * it will be registered.
     *
     * This is used when the back events should bypass the IME and be sent directly to the app
     * process.
     *
     * @param skip {@code true} to skip the default system back callback registration, {@code
     * false} otherwise.
     */
    @SuppressLint("WrongConstant")
    public void setSkipDefaultCallbackRegistration(boolean skip) {
        if (!backDispositionControlsBackInterception()) {
            return;
        }
        if (skip == mSkipDefaultCallbackRegistration) return;
        mSkipDefaultCallbackRegistration = skip;
        if (mSystemCallback != null) {
            if (skip) {
                unregisterOnBackInvokedCallbackAtTarget(mSystemCallback);
            } else {
                registerOnBackInvokedCallback(PRIORITY_SYSTEM, mSystemCallback);
            }
        }
    }

    @Override
    public void registerOnBackInvokedCallback(
            @OnBackInvokedDispatcher.Priority int priority,
            @NonNull OnBackInvokedCallback callback) {
        if (priority == PRIORITY_SYSTEM || callback instanceof CompatOnBackInvokedCallback) {
            if (!mSkipDefaultCallbackRegistration) {
                registerOnBackInvokedCallbackAtTarget(priority, callback);
            }
            mSystemCallback = callback;
            // Register all pending non-system callbacks.
            for (Pair<Integer, OnBackInvokedCallback> pair : mNonSystemCallbacks) {
                registerOnBackInvokedCallbackAtTarget(pair.first, pair.second);
            }
        } else {
            mNonSystemCallbacks.removeIf(pair -> pair.second.equals(callback));
            mNonSystemCallbacks.add(new Pair<>(priority, callback));
            if (mSystemCallback != null) {
                registerOnBackInvokedCallbackAtTarget(priority, callback);
            }
        }
    }

    private void registerOnBackInvokedCallbackAtTarget(
            @OnBackInvokedDispatcher.Priority int priority,
            @NonNull OnBackInvokedCallback callback) {
        final Bundle bundle = new Bundle();
        Log.d(TAG, "Register OnBackInvokedCallback with priority=" + priority
                + " at app window (packageName=" + mTargetAppPackageName + ")");
        // Always invoke back for ime without checking the window focus.
        // We use strong reference in the binder wrapper to avoid accidentally GC the callback.
        // This is necessary because the callback is sent to and registered from
        // the app process, which may treat the IME callback as weakly referenced. This will not
        // cause a memory leak because the app side already clears the reference correctly.
        final IOnBackInvokedCallback iCallback =
                new ImeOnBackInvokedCallbackWrapper(callback, mHandler);
        bundle.putBinder(RESULT_KEY_CALLBACK, iCallback.asBinder());
        bundle.putInt(RESULT_KEY_PRIORITY, priority);
        bundle.putInt(RESULT_KEY_ID, callback.hashCode());
        if (mResultReceiver != null) {
            mResultReceiver.send(RESULT_CODE_REGISTER, bundle);
        }
    }

    @Override
    public void unregisterOnBackInvokedCallback(@NonNull OnBackInvokedCallback callback) {
        if (callback == mSystemCallback) {
            // Unregister all non-system callbacks first.
            for (Pair<Integer, OnBackInvokedCallback> nonSystemCallback : mNonSystemCallbacks) {
                unregisterOnBackInvokedCallbackAtTarget(nonSystemCallback.second);
            }
            // Unregister the system callback.
            if (!mSkipDefaultCallbackRegistration) {
                unregisterOnBackInvokedCallbackAtTarget(callback);
            }
            mSystemCallback = null;
        } else {
            if (mNonSystemCallbacks.removeIf(pair -> pair.second.equals(callback))) {
                unregisterOnBackInvokedCallbackAtTarget(callback);
            }
        }
    }

    private void unregisterOnBackInvokedCallbackAtTarget(@NonNull OnBackInvokedCallback callback) {
        Log.d(TAG, "Unregister OnBackInvokedCallback at app window (packageName="
                + mTargetAppPackageName + ")");
        Bundle bundle = new Bundle();
        bundle.putInt(RESULT_KEY_ID, callback.hashCode());
        if (mResultReceiver != null) {
            mResultReceiver.send(RESULT_CODE_UNREGISTER, bundle);
        }
    }

    public void setHandler(@NonNull Handler handler) {
        mHandler = handler;
    }

    /** Clears all registered callbacks on the target. */
    public void clear() {
        if (mSystemCallback != null) {
            // unregistering the system callback clears all non-system callbacks at the target too.
            unregisterOnBackInvokedCallback(mSystemCallback);
        }
        mNonSystemCallbacks.clear();
    }

    /**
     * Dumps the {@link ImeBackCallbackSender} state.
     *
     * @param prefix prefix to be prepended to each line
     * @param p      PrintWriter to write the dump to
     */
    public void dump(@NonNull String prefix, @NonNull PrintWriter p) {
        p.println(prefix + TAG + ":");
        String innerPrefix = prefix + "  ";
        if (mNonSystemCallbacks.isEmpty()) {
            p.println(innerPrefix + "mNonSystemCallbacks: []");
        } else {
            p.println(innerPrefix + "mNonSystemCallbacks:");
            for (Pair<Integer, OnBackInvokedCallback> pair : mNonSystemCallbacks) {
                p.println(innerPrefix + "  " + pair.second + " (priority=" + pair.first + ")");
            }
        }
        p.println(innerPrefix + "mSystemCallback=" + mSystemCallback);
        p.println(innerPrefix + "mSkipDefaultCallbackRegistration="
                + mSkipDefaultCallbackRegistration);
        p.println(innerPrefix + "mTargetAppPackageName=" + mTargetAppPackageName);
    }

    /**
     * Wrapper class that wraps an OnBackInvokedCallback. This is used when a callback is sent from
     * the IME process to the app process.
     */
    private static class ImeOnBackInvokedCallbackWrapper extends IOnBackInvokedCallback.Stub {

        private final OnBackInvokedCallback mCallback;
        private final Handler mHandler;

        ImeOnBackInvokedCallbackWrapper(@NonNull OnBackInvokedCallback callback,
                @NonNull Handler handler) {
            mCallback = callback;
            mHandler = handler;
        }

        @Override
        public void onBackStarted(BackMotionEvent backMotionEvent) {
            maybeRunOnAnimationCallback((animationCallback) -> animationCallback.onBackStarted(
                    BackEvent.fromBackMotionEvent(backMotionEvent)));
        }

        @Override
        public void onBackProgressed(BackMotionEvent backMotionEvent) {
            maybeRunOnAnimationCallback((animationCallback) -> animationCallback.onBackProgressed(
                    BackEvent.fromBackMotionEvent(backMotionEvent)));
        }

        @Override
        public void onBackCancelled() {
            maybeRunOnAnimationCallback(OnBackAnimationCallback::onBackCancelled);
        }

        @Override
        public void onBackInvoked() {
            mHandler.post(mCallback::onBackInvoked);
        }

        @Override
        public void setTriggerBack(boolean triggerBack) {
            // no-op
        }

        @Override
        public void setHandoffHandler(IBackAnimationHandoffHandler handoffHandler) {
            // no-op
        }

        private void maybeRunOnAnimationCallback(Consumer<OnBackAnimationCallback> block) {
            if (mCallback instanceof OnBackAnimationCallback) {
                mHandler.post(() -> block.accept((OnBackAnimationCallback) mCallback));
            }
        }
    }
}
