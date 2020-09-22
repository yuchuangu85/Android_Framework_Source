/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server.wifi.util;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import com.android.internal.util.Preconditions;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Holds a list of external app-provided binder callback objects and tracks the death
 * of the callback object.
 * @param <T> Callback object type.
 */
public class ExternalCallbackTracker<T> {
    private static final String TAG = "WifiExternalCallbackTracker";

    /* Limit on number of registered callbacks to track and prevent potential memory leak */
    private static final int NUM_CALLBACKS_WARN_LIMIT = 10;
    private static final int NUM_CALLBACKS_WTF_LIMIT = 20;

    /**
     * Container for storing info about each external callback and tracks it's death.
     */
    private static class ExternalCallbackHolder<T> implements IBinder.DeathRecipient {
        private final IBinder mBinder;
        private final T mCallbackObject;
        private final DeathCallback mDeathCallback;

        /**
         * Callback to be invoked on death of the app hosting the binder.
         */
        public interface DeathCallback {
            /**
             * Called when the corresponding app has died.
             */
            void onDeath();
        }

        private ExternalCallbackHolder(@NonNull IBinder binder, @NonNull T callbackObject,
                                       @NonNull DeathCallback deathCallback) {
            mBinder = Preconditions.checkNotNull(binder);
            mCallbackObject = Preconditions.checkNotNull(callbackObject);
            mDeathCallback = Preconditions.checkNotNull(deathCallback);
        }

        /**
         * Static method to create a new {@link ExternalCallbackHolder} object and register for
         * death notification of the associated binder.
         * @return an instance of {@link ExternalCallbackHolder} if there are no failures, otherwise
         * null.
         */
        public static <T> ExternalCallbackHolder<T> createAndLinkToDeath(
                @NonNull IBinder binder, @NonNull T callbackObject,
                @NonNull DeathCallback deathCallback) {
            ExternalCallbackHolder<T> externalCallback =
                    new ExternalCallbackHolder<>(binder, callbackObject, deathCallback);
            try {
                binder.linkToDeath(externalCallback, 0);
            } catch (RemoteException e) {
                Log.e(TAG, "Error on linkToDeath - " + e);
                return null;
            }
            return externalCallback;
        }

        /**
         * Unlinks this object from binder death.
         */
        public void reset() {
            mBinder.unlinkToDeath(this, 0);
        }

        /**
         * Retrieve the callback object.
         */
        public T getCallback() {
            return mCallbackObject;
        }

        /**
         * App hosting the binder has died.
         */
        @Override
        public void binderDied() {
            mDeathCallback.onDeath();
            Log.d(TAG, "Binder died " + mBinder);
        }
    }

    private final Map<Integer, ExternalCallbackHolder<T>> mCallbacks;
    private final Handler mHandler;

    public ExternalCallbackTracker(Handler handler) {
        mHandler = handler;
        mCallbacks = new HashMap<>();
    }

    /**
     * Add a callback object to tracker.
     * @return true on success, false on failure.
     */
    public boolean add(@NonNull IBinder binder, @NonNull T callbackObject, int callbackIdentifier) {
        ExternalCallbackHolder<T> externalCallback = ExternalCallbackHolder.createAndLinkToDeath(
                binder, callbackObject, () -> {
                    mHandler.post(() -> {
                        Log.d(TAG, "Remove external callback on death " + callbackIdentifier);
                        remove(callbackIdentifier);
                    });
                });
        if (externalCallback == null) return false;
        if (mCallbacks.containsKey(callbackIdentifier)) {
            Log.d(TAG, "Replacing callback " + callbackIdentifier);
            remove(callbackIdentifier);
        }
        mCallbacks.put(callbackIdentifier, externalCallback);
        if (mCallbacks.size() > NUM_CALLBACKS_WTF_LIMIT) {
            Log.wtf(TAG, "Too many callbacks: " + mCallbacks.size());
        } else if (mCallbacks.size() > NUM_CALLBACKS_WARN_LIMIT) {
            Log.w(TAG, "Too many callbacks: " + mCallbacks.size());
        }
        return true;
    }

    /**
     * Remove a callback object to tracker.
     * @return Removed object instance on success, null on failure.
     */
    public @Nullable T remove(int callbackIdentifier) {
        ExternalCallbackHolder<T> externalCallback = mCallbacks.remove(callbackIdentifier);
        if (externalCallback == null) {
            Log.w(TAG, "Unknown external callback " + callbackIdentifier);
            return null;
        }
        externalCallback.reset();
        return externalCallback.getCallback();
    }

    /**
     * Retrieve all the callback objects in the tracker.
     */
    public List<T> getCallbacks() {
        List<T> callbacks = new ArrayList<>();
        for (ExternalCallbackHolder<T> externalCallback : mCallbacks.values()) {
            callbacks.add(externalCallback.getCallback());
        }
        return callbacks;
    }

    /**
     * Retrieve the number of callback objects in the tracker.
     */
    public int getNumCallbacks() {
        return mCallbacks.size();
    }

    /**
     * Remove all callbacks registered.
     */
    public void clear() {
        for (ExternalCallbackHolder<T> externalCallback : mCallbacks.values()) {
            externalCallback.reset();
        }
        mCallbacks.clear();
    }
}
