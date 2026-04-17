/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.app;

import static android.Manifest.permission.WRITE_SECURE_SETTINGS;

import android.annotation.CallbackExecutor;
import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemService;
import android.annotation.TestApi;
import android.annotation.UserHandleAware;
import android.content.ComponentName;
import android.content.Context;
import android.os.Binder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.provider.Settings;
import android.service.dreams.DreamPlaylist;
import android.service.dreams.DreamService;
import android.service.dreams.Flags;
import android.service.dreams.IDreamManager;
import android.service.dreams.IDreamManagerListener;
import com.android.internal.util.ClientListenerMultiplexer;

import java.util.Objects;
import java.util.concurrent.Executor;

/** @hide */
@SystemService(Context.DREAM_SERVICE)
@TestApi
public class DreamManager {
    private final IDreamManager mService;
    private final Context mContext;
    private final ClientListenerMultiplexer<DreamListener, IDreamManager, IDreamManagerListener>
            mMultiplexer;

    /**
     * @hide
     */
    public DreamManager(Context context) throws ServiceManager.ServiceNotFoundException {
        mService = IDreamManager.Stub.asInterface(
                        ServiceManager.getServiceOrThrow(DreamService.DREAM_SERVICE));
        mContext = context;
        mMultiplexer =
                new ClientListenerMultiplexer<>(
                        mService,
                        new DreamEventListener(),
                        (service, callback) ->
                                service.registerListener(callback, mContext.getUserId()),
                        (service, callback) ->
                                service.unregisterListener(callback, mContext.getUserId()));
    }

    /**
     * Returns whether Settings.Secure.SCREENSAVER_ENABLED is enabled.
     *
     * @hide
     */
    @TestApi
    public boolean isScreensaverEnabled() {
        return Settings.Secure.getIntForUser(mContext.getContentResolver(),
                Settings.Secure.SCREENSAVER_ENABLED, 0, UserHandle.USER_CURRENT) != 0;
    }

    /**
     * Sets whether Settings.Secure.SCREENSAVER_ENABLED is enabled.
     *
     * @hide
     */
    @TestApi
    @RequiresPermission(WRITE_SECURE_SETTINGS)
    public void setScreensaverEnabled(boolean enabled) {
        try {
            mService.setScreensaverEnabled(enabled);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns whether dreams are supported.
     *
     * @hide
     */
    @TestApi
    public boolean areDreamsSupported() {
        return mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_dreamsSupported);
    }

    /**
     * Starts dreaming.
     *
     * The system dream component, if set by {@link DreamManager#setSystemDreamComponent}, will be
     * started.
     * Otherwise, starts the active dream set by {@link DreamManager#setActiveDream}.
     *
     * <p>This is only used for testing the dream service APIs.
     *
     * @see DreamManager#setActiveDream(ComponentName)
     * @see DreamManager#setSystemDreamComponent(ComponentName)
     *
     * @hide
     */
    @TestApi
    @UserHandleAware
    @RequiresPermission(android.Manifest.permission.WRITE_DREAM_STATE)
    public void startDream() {
        try {
            mService.dream();
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Stops the dream service on the device if one is started.
     *
     * <p> This is only used for testing the dream service APIs.
     *
     * @hide
     */
    @TestApi
    @RequiresPermission(android.Manifest.permission.WRITE_DREAM_STATE)
    public void stopDream() {
        try {
            mService.awaken();
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Sets the active dream on the device to be "dreamComponent".
     *
     * <p>This is only used for testing the dream service APIs.
     *
     * @hide
     */
    @TestApi
    @UserHandleAware
    @RequiresPermission(android.Manifest.permission.WRITE_DREAM_STATE)
    public void setActiveDream(@Nullable ComponentName dreamComponent) {
        ComponentName[] dreams = {dreamComponent};

        try {
            mService.setDreamComponentsForUser(mContext.getUserId(),
                    dreamComponent != null ? dreams : null);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Sets or clears the system dream component.
     *
     * <p>The system dream component, when set, will be shown instead of the user configured dream
     * when the system starts dreaming (not dozing). If the system is dreaming at the time the
     * system dream is set or cleared, it immediately switches dream.
     *
     * @hide
     */
    @TestApi
    @RequiresPermission(android.Manifest.permission.WRITE_DREAM_STATE)
    public void setSystemDreamComponent(@Nullable ComponentName dreamComponent) {
        try {
            final Binder lifecycleToken =
                    (dreamComponent != null) ? new Binder("system dream token") : null;
            mService.setSystemDreamComponent(dreamComponent, lifecycleToken);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Sets the active dream on the device to be "dreamComponent".
     *
     * <p>This is only used for testing the dream service APIs.
     *
     * @hide
     */
    @TestApi
    @UserHandleAware
    @RequiresPermission(android.Manifest.permission.WRITE_DREAM_STATE)
    public void setDreamOverlay(@Nullable ComponentName dreamOverlayComponent) {
        try {
            mService.registerDreamOverlayService(dreamOverlayComponent);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Whether dreaming can start given user settings and the current dock/charge state.
     *
     * @hide
     */
    @UserHandleAware
    @RequiresPermission(android.Manifest.permission.READ_DREAM_STATE)
    public boolean canStartDreaming(boolean isScreenOn) {
        try {
            return mService.canStartDreaming(isScreenOn);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
        return false;
    }

    /**
     * Returns whether the device is Dreaming.
     *
     * <p> This is only used for testing the dream service APIs.
     *
     * @hide
     */
    @TestApi
    @RequiresPermission(android.Manifest.permission.READ_DREAM_STATE)
    public boolean isDreaming() {
        try {
            return mService.isDreaming();
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
        return false;
    }

    /**
     * Sets whether the dream is obscured by something.
     *
     * @hide
     */
    @FlaggedApi(Flags.FLAG_DREAM_HANDLES_BEING_OBSCURED)
    @RequiresPermission(android.Manifest.permission.WRITE_DREAM_STATE)
    public void setDreamIsObscured(boolean isObscured) {
        try {
            mService.setDreamIsObscured(isObscured);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Notifies dream manager of device postured state, which may affect dream enablement.
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.WRITE_DREAM_STATE)
    public void setDevicePostured(boolean isPostured) {
        try {
            mService.setDevicePostured(isPostured);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Sets the active dream on the device to the dream component, or null to clear it.
     *
     * <p>Note that this differs from {@link DreamManager#setActiveDream(ComponentName)}, which
     * changes the "allowed" list of dreams rather than the "active" dream.
     *
     * @return Whether the active dream was successfully set.
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.WRITE_DREAM_STATE)
    @UserHandleAware(
            requiresPermissionIfNotCaller = android.Manifest.permission.INTERACT_ACROSS_USERS)
    public boolean setActiveDreamComponent(@Nullable ComponentName dreamComponent) {
        assertDreamSwitcherFlag();
        try {
            return mService.setActiveDream(dreamComponent, mContext.getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the current dream playlist.
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.READ_DREAM_STATE)
    @UserHandleAware(
            requiresPermissionIfNotCaller = android.Manifest.permission.INTERACT_ACROSS_USERS)
    @NonNull
    public DreamPlaylist getDreamPlaylist() {
        assertDreamSwitcherFlag();
        try {
            return mService.getDreamPlaylist(mContext.getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Registers a listener for dream state changes.
     *
     * <p>Since the listeners are multiplexed, the current state is not returned upon registration.
     * Clients should query the current state via the relevant methods (e.g. {@link
     * #getDreamPlaylist()}) and then register a listener to receive updates.
     *
     * @param executor The executor to run the callback on.
     * @param listener The listener to register.
     * @hide
     */
    @UserHandleAware(
            requiresPermissionIfNotCaller = android.Manifest.permission.INTERACT_ACROSS_USERS)
    @RequiresPermission(android.Manifest.permission.READ_DREAM_STATE)
    public void registerListener(
            @NonNull @CallbackExecutor Executor executor, @NonNull DreamListener listener) {
        assertDreamSwitcherFlag();
        Objects.requireNonNull(executor, "Executor must not be null");
        Objects.requireNonNull(listener, "Listener must not be null");
        mMultiplexer.addListener(executor, listener);
    }

    /**
     * Unregisters a listener for dream state changes.
     *
     * @param listener The listener to unregister.
     * @hide
     */
    @UserHandleAware(
            requiresPermissionIfNotCaller = android.Manifest.permission.INTERACT_ACROSS_USERS)
    @RequiresPermission(android.Manifest.permission.READ_DREAM_STATE)
    public void unregisterListener(@NonNull DreamListener listener) {
        assertDreamSwitcherFlag();
        Objects.requireNonNull(listener, "Listener must not be null");
        mMultiplexer.removeListener(listener);
    }

    private final class DreamEventListener extends IDreamManagerListener.Stub {
        @Override
        public void onPlaylistChanged(DreamPlaylist playlist) {
            Binder.withCleanCallingIdentity(
                    () ->
                            mMultiplexer.forEachListener(
                                    listener -> listener.onPlaylistChanged(playlist)));
        }
    }

    private void assertDreamSwitcherFlag() {
        if (!Flags.dreamsSwitcher()) {
            throw new UnsupportedOperationException(
                    "Feature not enabled: "
                            + Flags.FLAG_DREAMS_SWITCHER
                            + ". The caller is expected to guard the call site with a runtime check"
                            + " to ensure the associated flag is enabled before calling the API.");
        }
    }

    /**
     * Listener for dream state changes.
     *
     * @hide
     */
    public interface DreamListener {
        /**
         * Called when the dream playlist or active dream changes.
         *
         * @param playlist The new playlist.
         */
        default void onPlaylistChanged(@NonNull DreamPlaylist playlist) {}
    }
}
