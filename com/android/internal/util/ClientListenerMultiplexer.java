/*
 * Copyright (C) 2026 The Android Open Source Project
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

package com.android.internal.util;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * A thread-safe helper class to manage multiple client-side listeners while maintaining only a
 * single registration with a remote service.
 *
 * <p>This class is designed to be used within a client-side Manager class to communicate with a
 * system service in system_server. It ensures that only one callback is registered with the system
 * service, even if multiple components within the client application need to listen for events. The
 * actual dispatching of events to listener methods is handled by the service callback
 * implementation provided to this class, using the {@link #forEachListener(Consumer)} method.
 *
 * <p>Note: Since this class is intended to be used by a long-lived singleton manager class and a
 * system service in system_server, it does not handle the remote side crashing.
 *
 * @param <TListener> The type of the client-side listener interface.
 * @param <TService> The type of the remote service Binder interface (e.g., IMyService).
 * @param <TCallback> The type of the callback interface implemented to receive events from the
 *     service (e.g., IMyCallback).
 * @hide
 */
@android.ravenwood.annotation.RavenwoodKeepWholeClass
public final class ClientListenerMultiplexer<TListener, TService, TCallback> {
    private static final String TAG = "ClientListenerMux";

    private final CopyOnWriteArrayList<ListenerInfo<TListener>> mListeners =
            new CopyOnWriteArrayList<>();
    private final TService mServiceInterface;
    private final TCallback mServiceCallback;
    private final ServiceRegistrar<TService, TCallback> mRegistrar;
    private final ServiceUnregistrar<TService, TCallback> mUnregistrar;

    private final Object mLock = new Object();
    private boolean mIsCallbackRegistered = false;

    private static class ListenerInfo<TListener> {
        @NonNull final TListener mListener;
        @Nullable final Executor mExecutor;

        ListenerInfo(@NonNull TListener listener, @Nullable Executor executor) {
            mListener = listener;
            mExecutor = executor;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof ListenerInfo)) return false;
            ListenerInfo<?> that = (ListenerInfo<?>) o;
            return Objects.equals(mListener, that.mListener);
        }

        @Override
        public int hashCode() {
            return Objects.hash(mListener);
        }
    }

    /**
     * Functional interface for registering the callback with the service.
     *
     * @param <TService> The service interface type.
     * @param <TCallback> The callback interface type.
     */
    @FunctionalInterface
    public interface ServiceRegistrar<TService, TCallback> {
        void register(TService service, TCallback callback) throws RemoteException;
    }

    /**
     * Functional interface for unregistering the callback from the service.
     *
     * @param <TService> The service interface type.
     * @param <TCallback> The callback interface type.
     */
    @FunctionalInterface
    public interface ServiceUnregistrar<TService, TCallback> {
        void unregister(TService service, TCallback callback) throws RemoteException;
    }

    /**
     * Constructor for the ClientListenerMultiplexer.
     *
     * @param serviceInterface The instance of the remote service interface.
     * @param serviceCallback The single callback instance that will be registered with the service.
     *     Its implementation is responsible for iterating through listeners using {@link
     *     #forEachListener(Consumer)} and calling the appropriate listener methods.
     * @param registrar A lambda function to handle the actual registration IPC call.
     * @param unregistrar A lambda function to handle the actual unregistration IPC call.
     */
    public ClientListenerMultiplexer(
            @NonNull TService serviceInterface,
            @NonNull TCallback serviceCallback,
            @NonNull ServiceRegistrar<TService, TCallback> registrar,
            @NonNull ServiceUnregistrar<TService, TCallback> unregistrar) {
        mServiceInterface =
                Objects.requireNonNull(serviceInterface, "serviceInterface cannot be null");
        mServiceCallback =
                Objects.requireNonNull(serviceCallback, "serviceCallback cannot be null");
        mRegistrar = Objects.requireNonNull(registrar, "registrar cannot be null");
        mUnregistrar = Objects.requireNonNull(unregistrar, "unregistrar cannot be null");
    }

    /**
     * Adds a listener to the local list. Registers the single callback with the remote service if
     * this is the first listener being added. This method is thread-safe.
     *
     * <p>If the listener is already registered, this method does nothing, even if the provided
     * executor is different from the one used during the initial registration. The existing
     * registration (and its executor) takes precedence.
     *
     * @param executor The Executor to use for callbacks, or null for direct execution.
     * @param listener The listener to add.
     */
    public void addListener(@Nullable Executor executor, @NonNull TListener listener) {
        Objects.requireNonNull(listener);
        final ListenerInfo<TListener> info = new ListenerInfo<>(listener, executor);

        synchronized (mLock) {
            final boolean added = mListeners.addIfAbsent(info);
            if (!added) {
                Log.w(TAG, "Listener already registered: " + listener);
                return;
            }

            if (!mIsCallbackRegistered) {
                try {
                    mRegistrar.register(mServiceInterface, mServiceCallback);
                    mIsCallbackRegistered = true;
                } catch (RemoteException e) {
                    mListeners.remove(info);
                    throw e.rethrowFromSystemServer();
                }
            }
        }
    }

    /**
     * Removes a listener from the local list. Unregisters the single callback from the remote
     * service if this was the last listener. This method is thread-safe.
     *
     * @param listener The listener to remove.
     */
    public void removeListener(@NonNull TListener listener) {
        if (listener == null) {
            return;
        }

        final boolean removed = mListeners.removeIf(info -> info.mListener.equals(listener));

        if (removed) {
            synchronized (mLock) {
                if (mIsCallbackRegistered && mListeners.isEmpty()) {
                    try {
                        mUnregistrar.unregister(mServiceInterface, mServiceCallback);
                        Log.d(TAG, "Callback unregistered from service.");
                    } catch (RemoteException e) {
                        throw e.rethrowFromSystemServer();
                    } finally {
                        mIsCallbackRegistered = false;
                    }
                }
            }
        }
    }

    /**
     * Executes the given action on each currently registered listener. This method is designed to
     * be called by the service callback implementation to dispatch events. The iteration is
     * thread-safe.
     *
     * <p>Example usage within the service callback's methods:
     *
     * <pre>{@code
     * multiplexer.forEachListener(listener -> {
     *     try {
     *         listener.onSomeEvent(eventData); // Call the appropriate listener method
     *     } catch (Exception e) {
     *         // Log or handle exceptions thrown by individual listeners
     *         Log.e(TAG, "Listener " + listener + " threw an exception", e);
     *     }
     * });
     * }</pre>
     *
     * @param action The action to perform on each listener.
     */
    public void forEachListener(@NonNull Consumer<TListener> action) {
        // CopyOnWriteArrayList provides a snapshot iteration, safe from concurrent modifications.
        for (ListenerInfo<TListener> info : mListeners) {
            if (info.mExecutor != null) {
                info.mExecutor.execute(() -> action.accept(info.mListener));
            } else {
                action.accept(info.mListener);
            }
        }
    }

    /**
     * Checks if any listeners are currently registered locally.
     *
     * @return true if there is at least one listener, false otherwise.
     */
    public boolean hasListeners() {
        return !mListeners.isEmpty();
    }

    /**
     * Returns the number of locally registered listeners.
     *
     * @return The count of listeners.
     */
    public int getListenerCount() {
        return mListeners.size();
    }
}
