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
package android.app.modes;

import android.Manifest;
import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.annotation.UserHandleAware;
import android.content.Context;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.service.notification.Flags;
import android.util.Pair;

import com.android.internal.annotations.GuardedBy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Provides access to and management of contextual modes, such as Do Not Disturb, Bedtime, or
 * Driving modes. This manager is the entry point to interact with the mode system, allowing them to
 * query mode states, apply changes, and listen for updates.
 *
 * <p>To get an instance of this class, call {@link android.content.Context#getSystemService(Class)}
 * with {@link ContextualModeManager} as the argument.
 *
 * <p>The core functionality of this class is to provide a centralized point of control and
 * observation for system-wide modes.
 *
 * <p>Additionally, this manager provides APIs to manage the synchronization of modes across a
 * user's devices, if that feature is supported.
 *
 * @see ContextualMode
 * @see ContextualModesMutation
 * @see ContextualModeListener
 * @hide
 */
@SystemApi
@FlaggedApi(Flags.FLAG_ENABLE_DND_SYNC)
public final class ContextualModeManager {
    private static final String TAG = "CtxModeManager";

    private final Object mLock = new Object();
    private final Context mContext;

    @GuardedBy("mLock")
    private final Map<UserHandle, ContextualModeSyncListenerStub> mModeSyncListeners =
            new HashMap<>();

    @GuardedBy("mLock")
    private final Map<UserHandle, ContextualModeListenerStub> mModeListeners = new HashMap<>();

    @Nullable private volatile IContextualModeManager mService;
    @Nullable private Boolean mModeSyncSupported;

    /** @hide */
    public ContextualModeManager(Context context) {
        mContext = context;
    }

    /**
     * Returns whether mode synchronization is supported on this device.
     *
     * <p>Mode synchronization allows a user's mode settings (e.g., Do Not Disturb) to be consistent
     * across all of their devices. This method checks if the current device has the necessary
     * capabilities to support this feature.
     *
     * @return {@code true} if mode synchronization is supported, {@code false} otherwise
     * @hide
     */
    @SystemApi
    public boolean isModeSyncSupported() {
        if (mModeSyncSupported != null) {
            return mModeSyncSupported;
        }
        synchronized (mLock) {
            if (mModeSyncSupported == null) {
                try {
                    mModeSyncSupported = getService().isModeSyncSupported();
                } catch (RemoteException e) {
                    throw e.rethrowFromSystemServer();
                }
            }
            return mModeSyncSupported;
        }
    }

    /**
     * Checks if the user has enabled mode synchronization.
     *
     * <p>This method returns the current user's setting for mode synchronization. This setting is
     * only relevant if {@link #isModeSyncSupported()} returns {@code true}.
     *
     * @return {@code true} if the user has enabled mode sync, {@code false} otherwise
     * @see #setModeSyncEnabled(boolean)
     * @hide
     */
    @SystemApi
    @UserHandleAware
    public boolean isModeSyncEnabled() {
        return isModeSyncEnabled(mContext.getUser());
    }

    /**
     * Checks if mode sync is enabled for the given user. Requires {@link
     * Manifest.permission#INTERACT_ACROSS_USERS} permission if the supplied {@link UserHandle}
     * belongs to a different user.
     *
     * @param userHandle the handle of a user to check
     * @return {@code true} if the user has enabled mode sync, {@code false} otherwise
     * @hide
     */
    @RequiresPermission(value = Manifest.permission.INTERACT_ACROSS_USERS, conditional = true)
    public boolean isModeSyncEnabled(UserHandle userHandle) {
        try {
            return getService().isModeSyncEnabled(userHandle);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Sets the user's preference for mode synchronization.
     *
     * <p>This allows enabling or disabling the synchronization of modes across the user's devices.
     *
     * @param enabled {@code true} to enable mode synchronization, {@code false} to disable it
     * @see #isModeSyncEnabled()
     * @throws SecurityException if the caller does not have the required permission
     * @hide
     */
    @SystemApi
    @UserHandleAware
    @RequiresPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
    public void setModeSyncEnabled(boolean enabled) {
        setModeSyncEnabled(mContext.getUser(), enabled);
    }

    /**
     * Sets the given user's preference for mode synchronization. Requires {@link
     * Manifest.permission#INTERACT_ACROSS_USERS} permission if the supplied {@link UserHandle}
     * belongs to a different user.
     *
     * @param userHandle the handle of a user whose preference will be set
     * @param enabled {@code true} to enable mode synchronization, {@code false} to disable it
     * @throws SecurityException if the caller does not have the required permission
     * @hide
     */
    @RequiresPermission(
            allOf = {
                Manifest.permission.WRITE_SECURE_SETTINGS,
                Manifest.permission.INTERACT_ACROSS_USERS
            },
            conditional = true)
    public void setModeSyncEnabled(UserHandle userHandle, boolean enabled) {
        try {
            getService().setModeSyncEnabled(userHandle, enabled);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Registers a listener to receive updates on the mode sync enabled state for the current user.
     *
     * <p>The provided listener will be invoked whenever the user's preference for mode
     * synchronization changes. It will receive {@code true} if mode sync is enabled, and {@code
     * false} otherwise.
     *
     * @param executor the {@link Executor} on which to invoke the listener
     * @param listener a {@link Consumer} that will be called with the new enablement state
     * @see #unregisterModeSyncEnabledListener(Consumer)
     * @hide
     */
    @SystemApi
    @UserHandleAware
    public void registerModeSyncEnabledListener(
            @NonNull Executor executor, @NonNull Consumer<Boolean> listener) {
        registerModeSyncEnabledListener(mContext.getUser(), executor, listener);
    }

    /**
     * Registers a listener to receive updates on the mode sync enabled state for the specified
     * user. Requires {@link Manifest.permission#INTERACT_ACROSS_USERS} permission if the supplied
     * {@link UserHandle} belongs to a different user.
     *
     * @param userHandle the handle of a user to listen for changes on
     * @param executor the {@link Executor} on which to invoke the listener
     * @param listener a {@link Consumer} that will be called with the new enablement state
     * @hide
     */
    @RequiresPermission(value = Manifest.permission.INTERACT_ACROSS_USERS, conditional = true)
    public void registerModeSyncEnabledListener(
            UserHandle userHandle,
            @NonNull Executor executor,
            @NonNull Consumer<Boolean> listener) {
        final ContextualModeSyncListenerStub stub;
        synchronized (mLock) {
            stub =
                    mModeSyncListeners.computeIfAbsent(
                            userHandle, k -> new ContextualModeSyncListenerStub());
            boolean wasEmpty = stub.mListeners.isEmpty();
            stub.mListeners.add(new Pair<>(executor, listener));
            if (!wasEmpty) {
                return;
            }
        }
        try {
            getService().registerModeSyncListener(userHandle, stub);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Unregisters a listener that was previously registered via {@link
     * #registerModeSyncEnabledListener(Executor, Consumer)}.
     *
     * @param listener the listener to unregister, must be the same object that was passed to the
     *     registration method
     * @hide
     */
    @SystemApi
    public void unregisterModeSyncEnabledListener(@NonNull Consumer<Boolean> listener) {
        List<ContextualModeSyncListenerStub> stubsToUnregister = new ArrayList<>();
        synchronized (mLock) {
            Iterator<ContextualModeSyncListenerStub> iterator =
                    mModeSyncListeners.values().iterator();
            while (iterator.hasNext()) {
                ContextualModeSyncListenerStub stub = iterator.next();
                stub.mListeners.removeIf(l -> l.second == listener);
                if (stub.mListeners.isEmpty()) {
                    iterator.remove();
                    stubsToUnregister.add(stub);
                }
            }
        }
        try {
            IContextualModeManager service = getService();
            for (ContextualModeSyncListenerStub stub : stubsToUnregister) {
                service.unregisterModeSyncListener(stub);
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Retrieves all available modes for the current user.
     *
     * <p>This method returns a list of {@link ContextualMode} objects, each representing a distinct
     * mode available on the device.
     *
     * @return a non-null, possibly empty, list of {@link ContextualMode}
     * @throws SecurityException if the caller does not have the required permission
     * @hide
     */
    @TestApi
    @UserHandleAware
    @RequiresPermission(Manifest.permission.MANAGE_CONTEXTUAL_MODES)
    @NonNull
    public List<ContextualMode> getModes() {
        return getModes(mContext.getUser());
    }

    /**
     * Retrieves all available modes for the specified user. Requires {@link
     * Manifest.permission#INTERACT_ACROSS_USERS} permission if the supplied {@link UserHandle}
     * belongs to a different user.
     *
     * @param userHandle the handle of a user to query
     * @return a non-null, possibly empty, list of {@link ContextualMode}
     * @throws SecurityException if the caller does not have the required permission
     * @hide
     */
    @RequiresPermission(
            allOf = {
                Manifest.permission.MANAGE_CONTEXTUAL_MODES,
                Manifest.permission.INTERACT_ACROSS_USERS
            },
            conditional = true)
    @NonNull
    public List<ContextualMode> getModes(UserHandle userHandle) {
        try {
            return getService().getModes(userHandle);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Applies a set of changes to the state of one or more modes for the current user.
     *
     * <p>This method allows for batch updates to mode states, such as activating one mode while
     * deactivating another. The changes are described in a {@link ContextualModesMutation} object.
     *
     * @param mutation a {@link ContextualModesMutation} object containing the desired changes
     * @throws SecurityException if the caller does not have the required permission
     * @hide
     */
    @TestApi
    @UserHandleAware
    @RequiresPermission(Manifest.permission.MANAGE_CONTEXTUAL_MODES)
    public void mutateModes(@NonNull ContextualModesMutation mutation) {
        mutateModes(mContext.getUser(), mutation);
    }

    /**
     * Applies a set of changes to the state of one or more modes for the specified user. Requires
     * {@link Manifest.permission#INTERACT_ACROSS_USERS} permission if the supplied {@link
     * UserHandle} belongs to a different user.
     *
     * @param userHandle the handle of a user whose modes will be mutated
     * @param mutation a {@link ContextualModesMutation} object containing the desired changes
     * @throws SecurityException if the caller does not have the required permission
     * @hide
     */
    @RequiresPermission(
            allOf = {
                Manifest.permission.MANAGE_CONTEXTUAL_MODES,
                Manifest.permission.INTERACT_ACROSS_USERS
            },
            conditional = true)
    public void mutateModes(UserHandle userHandle, @NonNull ContextualModesMutation mutation) {
        try {
            getService().mutateModes(userHandle, mutation);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Registers a listener to receive updates on mode changes for the current user.
     *
     * <p>The provided {@link ContextualModeListener} will be invoked when a mode's details (such as
     * its state) change or when a mode is added or removed.
     *
     * @param executor the {@link Executor} on which to invoke the listener's callbacks
     * @param listener the {@link ContextualModeListener} to register
     * @throws SecurityException if the caller does not have the required permission
     * @see #unregisterModeListener(ContextualModeListener)
     * @hide
     */
    @TestApi
    @UserHandleAware
    @RequiresPermission(Manifest.permission.MANAGE_CONTEXTUAL_MODES)
    public void registerModeListener(
            @NonNull Executor executor, @NonNull ContextualModeListener listener) {
        registerModeListener(mContext.getUser(), executor, listener);
    }

    /**
     * Registers a listener to receive updates on mode changes for the specified user. Requires
     * {@link Manifest.permission#INTERACT_ACROSS_USERS} permission if the supplied {@link
     * UserHandle} belongs to a different user.
     *
     * @param userHandle the handle of a user to listen for changes on
     * @param executor the {@link Executor} on which to invoke the listener's callbacks
     * @param listener the {@link ContextualModeListener} to register
     * @throws SecurityException if the caller does not have the required permission
     * @hide
     */
    @RequiresPermission(
            allOf = {
                Manifest.permission.MANAGE_CONTEXTUAL_MODES,
                Manifest.permission.INTERACT_ACROSS_USERS
            },
            conditional = true)
    public void registerModeListener(
            UserHandle userHandle,
            @NonNull Executor executor,
            @NonNull ContextualModeListener listener) {
        final ContextualModeListenerStub stub;
        synchronized (mLock) {
            stub =
                    mModeListeners.computeIfAbsent(
                            userHandle, k -> new ContextualModeListenerStub());
            boolean wasEmpty = stub.mListeners.isEmpty();
            stub.mListeners.add(new Pair<>(executor, listener));
            if (!wasEmpty) {
                return;
            }
        }
        try {
            getService().registerModeListener(userHandle, stub);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Unregisters a {@link ContextualModeListener} that was previously registered.
     *
     * @param listener the listener to unregister, must be the same object that was passed to {@link
     *     #registerModeListener(Executor, ContextualModeListener)}
     * @throws SecurityException if the caller does not have the required permission
     * @hide
     */
    @TestApi
    @RequiresPermission(Manifest.permission.MANAGE_CONTEXTUAL_MODES)
    public void unregisterModeListener(@NonNull ContextualModeListener listener) {
        List<ContextualModeListenerStub> stubsToUnregister = new ArrayList<>();
        synchronized (mLock) {
            Iterator<ContextualModeListenerStub> iterator = mModeListeners.values().iterator();
            while (iterator.hasNext()) {
                ContextualModeListenerStub stub = iterator.next();
                stub.mListeners.removeIf(l -> l.second == listener);
                if (stub.mListeners.isEmpty()) {
                    iterator.remove();
                    stubsToUnregister.add(stub);
                }
            }
        }
        try {
            IContextualModeManager service = getService();
            for (ContextualModeListenerStub stub : stubsToUnregister) {
                service.unregisterModeListener(stub);
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    private IContextualModeManager getService() {
        if (mService != null) {
            return mService;
        }
        synchronized (mLock) {
            if (mService == null) {
                IBinder service = ServiceManager.getService(Context.CONTEXTUAL_MODE_SERVICE);
                mService = IContextualModeManager.Stub.asInterface(service);
            }
            return mService;
        }
    }

    /**
     * A listener interface for receiving callbacks about changes to contextual modes.
     *
     * @see #registerModeListener(Executor, ContextualModeListener)
     * @hide
     */
    @TestApi
    public interface ContextualModeListener {
        /**
         * Called when one or more modes have changed. This can include changes to a mode's state
         * (e.g., becoming active or inactive).
         *
         * @param modes a list of {@link ContextualMode} that have changed
         */
        void onModesChanged(@NonNull List<ContextualMode> modes);

        /**
         * Called when a mode has been removed from the system.
         *
         * @param modeId the unique ID of the mode that was removed
         */
        void onModeRemoved(@NonNull String modeId);
    }

    /** Internal listener used to listen to contextual mode sync changes. */
    private class ContextualModeSyncListenerStub extends IContextualModeSyncListener.Stub {
        @GuardedBy("mLock")
        final List<Pair<Executor, Consumer<Boolean>>> mListeners = new ArrayList<>();

        @Override
        public void onModeSyncEnabledChanged(boolean enabled) {
            List<Pair<Executor, Consumer<Boolean>>> listenerCopy;
            synchronized (mLock) {
                listenerCopy = new ArrayList<>(mListeners);
            }
            listenerCopy.forEach(l -> l.first.execute(() -> l.second.accept(enabled)));
        }
    }

    /** Internal listener used to listen to contextual mode changes. */
    private class ContextualModeListenerStub extends IContextualModeListener.Stub {
        @GuardedBy("mLock")
        final List<Pair<Executor, ContextualModeListener>> mListeners = new ArrayList<>();

        @Override
        public void onModesChanged(List<ContextualMode> modes) {
            notifyListeners(listener -> listener.onModesChanged(modes));
        }

        @Override
        public void onModeRemoved(String modeId) {
            notifyListeners(listener -> listener.onModeRemoved(modeId));
        }

        private void notifyListeners(Consumer<ContextualModeListener> listenerConsumer) {
            List<Pair<Executor, ContextualModeListener>> listenerCopy;
            synchronized (mLock) {
                listenerCopy = new ArrayList<>(mListeners);
            }
            listenerCopy.forEach(l -> l.first.execute(() -> listenerConsumer.accept(l.second)));
        }
    }
}
