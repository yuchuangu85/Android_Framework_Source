/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.media;

import static android.annotation.SystemApi.Client.MODULE_LIBRARIES;
import static android.media.AudioManager.ADJUST_LOWER;
import static android.media.AudioManager.ADJUST_RAISE;
import static android.media.audio.Flags.FLAG_DEVICE_VOLUME_APIS;
import static android.media.audio.Flags.FLAG_UNIFY_ABSOLUTE_VOLUME_MANAGEMENT;

import static com.android.media.flags.Flags.FLAG_ENABLE_AUDIO_INPUT_DEVICE_ROUTING_AND_VOLUME_CONTROL;

import android.Manifest;
import android.annotation.CallbackExecutor;
import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.content.Context;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;

import com.android.internal.annotations.GuardedBy;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * @hide
 * AudioDeviceVolumeManager provides access to audio device volume control.
 */
@SystemApi
public class AudioDeviceVolumeManager {

    private static final String TAG = "AudioDeviceVolumeManager";

    /**
     * @hide
     * Volume behavior for an audio device that has no particular volume behavior set. Invalid as
     * an argument to {@link #setDeviceVolumeBehavior(AudioDeviceAttributes, int)} and should not
     * be returned by {@link #getDeviceVolumeBehavior(AudioDeviceAttributes)}.
     */
    public static final int DEVICE_VOLUME_BEHAVIOR_UNSET = -1;
    /**
     * @hide
     * Volume behavior for an audio device where a software attenuation is applied
     * @see #setDeviceVolumeBehavior(AudioDeviceAttributes, int)
     */
    @SystemApi
    @FlaggedApi(FLAG_UNIFY_ABSOLUTE_VOLUME_MANAGEMENT)
    public static final int DEVICE_VOLUME_BEHAVIOR_VARIABLE = 0;
    /**
     * @hide
     * Volume behavior for an audio device where the volume is always set to provide no attenuation
     *     nor gain (e.g. unit gain).
     * @see #setDeviceVolumeBehavior(AudioDeviceAttributes, int)
     */
    @SystemApi
    @FlaggedApi(FLAG_UNIFY_ABSOLUTE_VOLUME_MANAGEMENT)
    public static final int DEVICE_VOLUME_BEHAVIOR_FULL = 1;
    /**
     * @hide
     * Volume behavior for an audio device where the volume is either set to muted, or to provide
     *     no attenuation nor gain (e.g. unit gain).
     * @see #setDeviceVolumeBehavior(AudioDeviceAttributes, int)
     */
    @SystemApi
    @FlaggedApi(FLAG_UNIFY_ABSOLUTE_VOLUME_MANAGEMENT)
    public static final int DEVICE_VOLUME_BEHAVIOR_FIXED = 2;
    /**
     * @hide
     * Volume behavior for an audio device where no software attenuation is applied, and
     *     the volume is kept synchronized between the host and the device itself through a
     *     device-specific protocol such as BT AVRCP.
     * @see #setDeviceVolumeBehavior(AudioDeviceAttributes, int)
     */
    @SystemApi
    @FlaggedApi(FLAG_UNIFY_ABSOLUTE_VOLUME_MANAGEMENT)
    public static final int DEVICE_VOLUME_BEHAVIOR_ABSOLUTE = 3;
    /**
     * @hide
     * Volume behavior for an audio device where no software attenuation is applied, and
     *     the volume is kept synchronized between the host and the device itself through a
     *     device-specific protocol (such as for hearing aids), based on the audio mode (e.g.
     *     normal vs in phone call).
     * @see AudioManager#setMode(int)
     * @see #setDeviceVolumeBehavior(AudioDeviceAttributes, int)
     */
    @SystemApi
    @FlaggedApi(FLAG_UNIFY_ABSOLUTE_VOLUME_MANAGEMENT)
    public static final int DEVICE_VOLUME_BEHAVIOR_ABSOLUTE_MULTI_MODE = 4;

    /**
     * @hide
     * A variant of {@link #DEVICE_VOLUME_BEHAVIOR_ABSOLUTE} where the host cannot reliably set
     * the volume percentage of the audio device. Specifically, {@link AudioManager#setStreamVolume}
     * will have no effect, or an unreliable effect.
     */
    @SystemApi
    @FlaggedApi(FLAG_UNIFY_ABSOLUTE_VOLUME_MANAGEMENT)
    public static final int DEVICE_VOLUME_BEHAVIOR_ABSOLUTE_ADJUST_ONLY = 5;

    /** @hide */
    @IntDef({
            DEVICE_VOLUME_BEHAVIOR_VARIABLE,
            DEVICE_VOLUME_BEHAVIOR_FULL,
            DEVICE_VOLUME_BEHAVIOR_FIXED,
            DEVICE_VOLUME_BEHAVIOR_ABSOLUTE,
            DEVICE_VOLUME_BEHAVIOR_ABSOLUTE_MULTI_MODE,
            DEVICE_VOLUME_BEHAVIOR_ABSOLUTE_ADJUST_ONLY,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface DeviceVolumeBehavior {}

    /** @hide */
    @IntDef({
            DEVICE_VOLUME_BEHAVIOR_UNSET,
            DEVICE_VOLUME_BEHAVIOR_VARIABLE,
            DEVICE_VOLUME_BEHAVIOR_FULL,
            DEVICE_VOLUME_BEHAVIOR_FIXED,
            DEVICE_VOLUME_BEHAVIOR_ABSOLUTE,
            DEVICE_VOLUME_BEHAVIOR_ABSOLUTE_MULTI_MODE,
            DEVICE_VOLUME_BEHAVIOR_ABSOLUTE_ADJUST_ONLY,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface DeviceVolumeBehaviorState {}

    /**
     * Variants of absolute volume behavior that are set in for absolute volume management.
     * @hide
     */
    @IntDef({
            DEVICE_VOLUME_BEHAVIOR_ABSOLUTE,
            DEVICE_VOLUME_BEHAVIOR_ABSOLUTE_ADJUST_ONLY,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface AbsoluteDeviceVolumeBehavior {}

    /** @hide */
    @IntDef(flag = false, prefix = "ADJUST", value = {
            ADJUST_RAISE,
            ADJUST_LOWER
    }
    )
    @Retention(RetentionPolicy.SOURCE)
    public @interface VolumeAdjustmentNoMute {}

    /**
     * @hide
     * Throws IAE on an invalid volume behavior value
     * @param volumeBehavior behavior value to check
     */
    public static void enforceValidVolumeBehavior(int volumeBehavior) {
        switch (volumeBehavior) {
            case DEVICE_VOLUME_BEHAVIOR_VARIABLE:
            case DEVICE_VOLUME_BEHAVIOR_FULL:
            case DEVICE_VOLUME_BEHAVIOR_FIXED:
            case DEVICE_VOLUME_BEHAVIOR_ABSOLUTE:
            case DEVICE_VOLUME_BEHAVIOR_ABSOLUTE_MULTI_MODE:
            case DEVICE_VOLUME_BEHAVIOR_ABSOLUTE_ADJUST_ONLY:
                return;
            default:
                throw new IllegalArgumentException("Illegal volume behavior " + volumeBehavior);
        }
    }

    /** @hide
     * Indicates no special treatment in the handling of the volume adjustment */
    public static final int ADJUST_MODE_NORMAL = 0;
    /** @hide
     * Indicates the start of a volume adjustment */
    public static final int ADJUST_MODE_START = 1;
    /** @hide
     * Indicates the end of a volume adjustment */
    public static final int ADJUST_MODE_END = 2;

    /** @hide */
    @IntDef(flag = false, prefix = "ADJUST_MODE", value = {
            ADJUST_MODE_NORMAL,
            ADJUST_MODE_START,
            ADJUST_MODE_END}
    )
    @Retention(RetentionPolicy.SOURCE)
    public @interface VolumeAdjustmentMode {}

    private static IAudioService sService;

    private final @NonNull String mPackageName;

    /**
     * @hide
     * Constructor
     * @param context the Context for the device volume operations
     */
    public AudioDeviceVolumeManager(@NonNull Context context) {
        Objects.requireNonNull(context);
        mPackageName = context.getApplicationContext().getOpPackageName();
    }

    /**
     * @hide
     * Interface to receive volume changes on a device that behaves in absolute volume mode.
     * @see #setDeviceAbsoluteMultiVolumeBehavior(AudioDeviceAttributes, List, boolean, Executor,
     *          OnAudioDeviceVolumeChangedListener)
     * @see #setDeviceAbsoluteVolumeBehavior(AudioDeviceAttributes, VolumeInfo, boolean, Executor,
     *          OnAudioDeviceVolumeChangedListener)
     */
    @SystemApi(client = MODULE_LIBRARIES)
    @FlaggedApi(FLAG_UNIFY_ABSOLUTE_VOLUME_MANAGEMENT)
    public interface OnAudioDeviceVolumeChangedListener {
        /**
         * Called when the volume for the given audio device has changed.
         *
         * <p>This method will not be called after #notifyAbsoluteVolumeChanged is called since this
         * is used to inform the audio framework that the external controller has already applied
         * the new volume.
         *
         * @param device the audio device whose volume has changed
         * @param vol the new volume for the device
         */
        void onAudioDeviceVolumeChanged(
                @NonNull AudioDeviceAttributes device,
                @NonNull VolumeInfo vol);

        /**
         * Called when the volume for the given audio device has been adjusted.
         *
         * <p>This method will be called only when registered for a volume behavior with the
         * parameter {@code handlesVolumeAdjustment} set to {@code true} in the
         * {@code setDeviceAbsoluteVolumeBehavior} registration methods and will be called as a
         * result of an adjustment to the volume in any direction
         *
         * @param device the audio device whose volume has been adjusted
         * @param vol the volume info for the device
         * @param direction the direction of the adjustment
         * @param mode the volume adjustment mode
         */
        void onAudioDeviceVolumeAdjusted(
                @NonNull AudioDeviceAttributes device,
                @NonNull VolumeInfo vol,
                @AudioManager.VolumeAdjustment int direction,
                @VolumeAdjustmentMode int mode);
    }

    /** @hide */
    static class ListenerInfo {
        final @NonNull OnAudioDeviceVolumeChangedListener mListener;
        final @NonNull Executor mExecutor;
        final @NonNull AudioDeviceAttributes mDevice;
        final @NonNull boolean mHandlesVolumeAdjustment;

        ListenerInfo(@NonNull OnAudioDeviceVolumeChangedListener listener, @NonNull Executor exe,
                @NonNull AudioDeviceAttributes device, boolean handlesVolumeAdjustment) {
            mListener = listener;
            mExecutor = exe;
            mDevice = device;
            mHandlesVolumeAdjustment = handlesVolumeAdjustment;
        }
    }

    private final Object mDeviceVolumeListenerLock = new Object();
    /**
     * List of listeners for volume changes, the associated device, and their associated Executor.
     * List is lazy-initialized on first registration
     */
    @GuardedBy("mDeviceVolumeListenerLock")
    private @Nullable ArrayList<ListenerInfo> mDeviceVolumeListeners;

    @GuardedBy("mDeviceVolumeListenerLock")
    private DeviceVolumeDispatcherStub mDeviceVolumeDispatcherStub;

    /** @hide */
    final class DeviceVolumeDispatcherStub extends IAudioDeviceVolumeDispatcher.Stub {
        /**
         * Register / unregister the stub
         * @param register true for registering, false for unregistering
         * @param device device for which volume is monitored
         */
        @RequiresPermission(anyOf = { android.Manifest.permission.MODIFY_AUDIO_ROUTING,
                android.Manifest.permission.BLUETOOTH_PRIVILEGED })
        public void register(boolean register, @NonNull AudioDeviceAttributes device,
                @NonNull List<VolumeInfo> volumes, boolean handlesVolumeAdjustment,
                @DeviceVolumeBehaviorState int behavior) {
            try {
                getService().registerDeviceVolumeDispatcherForAbsoluteVolume(register,
                        this, mPackageName,
                        Objects.requireNonNull(device), Objects.requireNonNull(volumes),
                        handlesVolumeAdjustment, behavior);
            } catch (RemoteException e) {
                e.rethrowFromSystemServer();
            }
        }

        @Override
        public void dispatchDeviceVolumeChanged(
                @NonNull AudioDeviceAttributes device, @NonNull VolumeInfo vol) {
            final ArrayList<ListenerInfo> volumeListeners;
            synchronized (mDeviceVolumeListenerLock) {
                volumeListeners = (ArrayList<ListenerInfo>) mDeviceVolumeListeners.clone();
            }
            for (ListenerInfo listenerInfo : volumeListeners) {
                if (listenerInfo.mDevice.equalTypeAddress(device)) {
                    listenerInfo.mExecutor.execute(
                            () -> listenerInfo.mListener.onAudioDeviceVolumeChanged(device, vol));
                }
            }
        }

        @Override
        public void dispatchDeviceVolumeAdjusted(
                @NonNull AudioDeviceAttributes device, @NonNull VolumeInfo vol, int direction,
                int mode) {
            final ArrayList<ListenerInfo> volumeListeners;
            synchronized (mDeviceVolumeListenerLock) {
                volumeListeners = (ArrayList<ListenerInfo>) mDeviceVolumeListeners.clone();
            }
            for (ListenerInfo listenerInfo : volumeListeners) {
                if (listenerInfo.mDevice.equalTypeAddress(device)) {
                    listenerInfo.mExecutor.execute(
                            () -> listenerInfo.mListener.onAudioDeviceVolumeAdjusted(device, vol,
                                    direction, mode));
                }
            }
        }
    }

    /**
     * @hide
     * Sets the volume behavior for an audio output device.
     * @see #DEVICE_VOLUME_BEHAVIOR_VARIABLE
     * @see #DEVICE_VOLUME_BEHAVIOR_FULL
     * @see #DEVICE_VOLUME_BEHAVIOR_FIXED
     * @see #DEVICE_VOLUME_BEHAVIOR_ABSOLUTE
     * @see #DEVICE_VOLUME_BEHAVIOR_ABSOLUTE_MULTI_MODE
     * @param device the device to be affected
     * @param deviceVolumeBehavior one of the device behaviors
     */
    @SystemApi
    @RequiresPermission(anyOf = {
            Manifest.permission.MODIFY_AUDIO_ROUTING,
            Manifest.permission.MODIFY_AUDIO_SETTINGS_PRIVILEGED
    })
    @FlaggedApi(FLAG_UNIFY_ABSOLUTE_VOLUME_MANAGEMENT)
    public void setDeviceVolumeBehavior(@NonNull AudioDeviceAttributes device,
            @DeviceVolumeBehavior int deviceVolumeBehavior) {
        // verify arguments (validity of device type is enforced in server)
        Objects.requireNonNull(device);
        enforceValidVolumeBehavior(deviceVolumeBehavior);
        // communicate with service
        final IAudioService service = getService();
        try {
            service.setDeviceVolumeBehavior(device, deviceVolumeBehavior, mPackageName);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     * Returns the volume device behavior for the given audio device
     * @param device the audio device
     * @return the volume behavior for the device
     */
    @SystemApi
    @RequiresPermission(anyOf = {
            Manifest.permission.MODIFY_AUDIO_ROUTING,
            Manifest.permission.QUERY_AUDIO_STATE,
            Manifest.permission.MODIFY_AUDIO_SETTINGS_PRIVILEGED
    })
    @FlaggedApi(FLAG_UNIFY_ABSOLUTE_VOLUME_MANAGEMENT)
    public @DeviceVolumeBehavior int getDeviceVolumeBehavior(
            @NonNull AudioDeviceAttributes device) {
        // verify arguments (validity of device type is enforced in server)
        Objects.requireNonNull(device);
        // communicate with service
        final IAudioService service = getService();
        try {
            return service.getDeviceVolumeBehavior(device);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     * Returns {@code true} if the volume device behavior is {@link #DEVICE_VOLUME_BEHAVIOR_FULL}.
     */
    @TestApi
    @RequiresPermission(anyOf = {
            Manifest.permission.MODIFY_AUDIO_ROUTING,
            Manifest.permission.QUERY_AUDIO_STATE,
            Manifest.permission.MODIFY_AUDIO_SETTINGS_PRIVILEGED
    })
    @SuppressWarnings("UnflaggedApi")  // @TestApi without associated feature.
    public boolean isFullVolumeDevice() {
        final AudioAttributes attributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .build();
        List<AudioDeviceAttributes> devices;
        final IAudioService service = getService();
        try {
            devices = service.getDevicesForAttributes(attributes);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }

        for (AudioDeviceAttributes device : devices) {
            if (getDeviceVolumeBehavior(device) == DEVICE_VOLUME_BEHAVIOR_FULL) {
                return true;
            }
        }
        return false;
    }

    /**
     * @hide
     * Configures a device to use absolute volume model, and registers a listener for receiving
     * volume updates to apply on that device
     *
     * <p>For A2DP devices only, this behavior is reset when they are disconnected / made
     * unavailable as this capability is communicated asynchronously at connection time.
     *
     * @param device the audio device set to absolute volume mode
     * @param volume the type of volume this device responds to
     * @param executor the Executor used for receiving volume updates through the listener
     * @param vclistener the callback for volume updates
     */
    @SystemApi(client = MODULE_LIBRARIES)
    @RequiresPermission(anyOf = { android.Manifest.permission.MODIFY_AUDIO_ROUTING,
            android.Manifest.permission.MODIFY_AUDIO_SETTINGS_PRIVILEGED,
            android.Manifest.permission.BLUETOOTH_PRIVILEGED,
            android.Manifest.permission.BLUETOOTH_STACK})
    @FlaggedApi(FLAG_UNIFY_ABSOLUTE_VOLUME_MANAGEMENT)
    public void setDeviceAbsoluteVolumeBehavior(
            @NonNull AudioDeviceAttributes device,
            @NonNull VolumeInfo volume,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull OnAudioDeviceVolumeChangedListener vclistener) {
        setDeviceAbsoluteVolumeBehavior(device, volume, /*handlesVolumeAdjustment=*/false, executor,
                vclistener);
    }

    /**
     * @hide
     * Configures a device to use absolute volume model, and registers a listener for receiving
     * volume updates to apply on that device.
     *
     * @param device the audio device set to absolute volume mode
     * @param volume the type of volume this device responds to
     * @param handlesVolumeAdjustment whether the controller handles volume adjustments separately
     * from volume changes. If true, adjustments from {@link AudioManager#adjustStreamVolume}
     * will be sent via {@link OnAudioDeviceVolumeChangedListener#onAudioDeviceVolumeAdjusted}.
     * @param executor the Executor used for receiving volume updates through the listener
     * @param vclistener the callback for volume updates
     */
    @RequiresPermission(anyOf = { android.Manifest.permission.MODIFY_AUDIO_ROUTING,
            android.Manifest.permission.BLUETOOTH_PRIVILEGED})
    public void setDeviceAbsoluteVolumeBehavior(
            @NonNull AudioDeviceAttributes device,
            @NonNull VolumeInfo volume,
            boolean handlesVolumeAdjustment,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull OnAudioDeviceVolumeChangedListener vclistener) {
        final ArrayList<VolumeInfo> volumes = new ArrayList<>(1);
        volumes.add(volume);
        setDeviceAbsoluteMultiVolumeBehavior(device, volumes, handlesVolumeAdjustment, executor,
                vclistener);
    }

    /**
     * @hide
     * Configures a device to use absolute volume model applied to different volume types, and
     * registers a listener for receiving volume updates to apply on that device.
     *
     * <p>For A2DP devices only, this behavior is reset when they are disconnected / made
     * unavailable as this capability is communicated asynchronously at connection time.
     *
     * @param device the audio device set to absolute multi-volume mode
     * @param volumes the list of volumes the given device responds to
     * @param executor the Executor used for receiving volume updates through the listener
     * @param vclistener the callback for volume updates
     */
    @SystemApi(client = MODULE_LIBRARIES)
    @RequiresPermission(anyOf = { android.Manifest.permission.MODIFY_AUDIO_ROUTING,
            android.Manifest.permission.MODIFY_AUDIO_SETTINGS_PRIVILEGED,
            android.Manifest.permission.BLUETOOTH_PRIVILEGED,
            android.Manifest.permission.BLUETOOTH_STACK})
    @FlaggedApi(FLAG_UNIFY_ABSOLUTE_VOLUME_MANAGEMENT)
    public void setDeviceAbsoluteMultiVolumeBehavior(
            @NonNull AudioDeviceAttributes device,
            @NonNull List<VolumeInfo> volumes,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull OnAudioDeviceVolumeChangedListener vclistener) {
        setDeviceAbsoluteMultiVolumeBehavior(device, volumes, /*handlesVolumeAdjustment=*/false,
                executor, vclistener);
    }

    /**
     * @hide
     * Configures a device to use absolute volume model applied to different volume types, and
     * registers a listener for receiving volume updates to apply on that device
     * @param device the audio device set to absolute multi-volume mode
     * @param volumes the list of volumes the given device responds to
     * @param handlesVolumeAdjustment whether the controller handles volume adjustments separately
     * from volume changes. If true, adjustments from {@link AudioManager#adjustStreamVolume}
     * will be sent via {@link OnAudioDeviceVolumeChangedListener#onAudioDeviceVolumeAdjusted}.
     * @param executor the Executor used for receiving volume updates through the listener
     * @param vclistener the callback for volume updates
     */
    @RequiresPermission(anyOf = { android.Manifest.permission.MODIFY_AUDIO_ROUTING,
            android.Manifest.permission.BLUETOOTH_PRIVILEGED})
    public void setDeviceAbsoluteMultiVolumeBehavior(
            @NonNull AudioDeviceAttributes device,
            @NonNull List<VolumeInfo> volumes,
            boolean handlesVolumeAdjustment,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull OnAudioDeviceVolumeChangedListener vclistener) {
        baseSetDeviceAbsoluteMultiVolumeBehavior(device, volumes, executor, vclistener,
                handlesVolumeAdjustment, DEVICE_VOLUME_BEHAVIOR_ABSOLUTE);
    }

    /**
     * @hide
     * Configures a device to use absolute volume model, and registers a listener for receiving
     * volume updates to apply on that device.
     *
     * <p>Should be used instead of {@link #setDeviceAbsoluteVolumeBehavior} when there is no
     * reliable way to set the device's volume to a percentage.
     *
     * @param device the audio device set to absolute volume mode
     * @param volume the type of volume this device responds to
     * @param handlesVolumeAdjustment whether the controller handles volume adjustments separately
     * from volume changes. If true, adjustments from {@link AudioManager#adjustStreamVolume}
     * will be sent via {@link OnAudioDeviceVolumeChangedListener#onAudioDeviceVolumeAdjusted}.
     * @param executor the Executor used for receiving volume updates through the listener
     * @param vclistener the callback for volume updates
     */
    @RequiresPermission(anyOf = { android.Manifest.permission.MODIFY_AUDIO_ROUTING,
            android.Manifest.permission.BLUETOOTH_PRIVILEGED })
    public void setDeviceAbsoluteVolumeAdjustOnlyBehavior(
            @NonNull AudioDeviceAttributes device,
            @NonNull VolumeInfo volume,
            boolean handlesVolumeAdjustment,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull OnAudioDeviceVolumeChangedListener vclistener) {
        final ArrayList<VolumeInfo> volumes = new ArrayList<>(1);
        volumes.add(volume);
        setDeviceAbsoluteMultiVolumeAdjustOnlyBehavior(device, volumes, handlesVolumeAdjustment,
                executor, vclistener);
    }

    /**
     * @hide
     * Configures a device to use absolute volume model applied to different volume types, and
     * registers a listener for receiving volume updates to apply on that device.
     *
     * <p>Should be used instead of {@link #setDeviceAbsoluteMultiVolumeBehavior} when there is
     * no reliable way to set the device's volume to a percentage.
     *
     * @param device the audio device set to absolute multi-volume mode
     * @param volumes the list of volumes the given device responds to
     * @param handlesVolumeAdjustment whether the controller handles volume adjustments separately
     * from volume changes. If true, adjustments from {@link AudioManager#adjustStreamVolume}
     * will be sent via {@link OnAudioDeviceVolumeChangedListener#onAudioDeviceVolumeAdjusted}.
     * @param executor the Executor used for receiving volume updates through the listener
     * @param vclistener the callback for volume updates
     */
    @RequiresPermission(anyOf = { android.Manifest.permission.MODIFY_AUDIO_ROUTING,
            android.Manifest.permission.BLUETOOTH_PRIVILEGED })
    public void setDeviceAbsoluteMultiVolumeAdjustOnlyBehavior(
            @NonNull AudioDeviceAttributes device,
            @NonNull List<VolumeInfo> volumes,
            boolean handlesVolumeAdjustment,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull OnAudioDeviceVolumeChangedListener vclistener) {
        baseSetDeviceAbsoluteMultiVolumeBehavior(device, volumes, executor, vclistener,
                handlesVolumeAdjustment, DEVICE_VOLUME_BEHAVIOR_ABSOLUTE_ADJUST_ONLY);
    }

    /**
     * @hide
     * Resets any set volume behavior to
     * {@link AudioDeviceVolumeManager#DEVICE_VOLUME_BEHAVIOR_VARIABLE} for the given device
     */
    @TestApi
    @RequiresPermission(anyOf = { android.Manifest.permission.MODIFY_AUDIO_ROUTING,
            android.Manifest.permission.MODIFY_AUDIO_SETTINGS_PRIVILEGED,
            android.Manifest.permission.BLUETOOTH_PRIVILEGED,
            android.Manifest.permission.BLUETOOTH_STACK})
    @FlaggedApi(FLAG_UNIFY_ABSOLUTE_VOLUME_MANAGEMENT)
    public void resetDeviceAbsoluteVolumeBehavior(@NonNull AudioDeviceAttributes device) {
        synchronized (mDeviceVolumeListenerLock) {
            if (mDeviceVolumeDispatcherStub == null) {
                mDeviceVolumeDispatcherStub = new DeviceVolumeDispatcherStub();
            }

            mDeviceVolumeDispatcherStub.register(/*register=*/false, device,
                    new ArrayList<>(), /*handlesVolumeAdjustment=*/false,
                    DEVICE_VOLUME_BEHAVIOR_VARIABLE);
        }
    }

    /**
     * Base method for configuring a device to use absolute volume behavior, or one of its variants.
     * See {@link AbsoluteDeviceVolumeBehavior} for a list of allowed behaviors.
     *
     * @param behavior the variant of absolute device volume behavior to adopt
     */
    @RequiresPermission(anyOf = { android.Manifest.permission.MODIFY_AUDIO_ROUTING,
            android.Manifest.permission.BLUETOOTH_PRIVILEGED })
    private void baseSetDeviceAbsoluteMultiVolumeBehavior(
            @NonNull AudioDeviceAttributes device,
            @NonNull List<VolumeInfo> volumes,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull OnAudioDeviceVolumeChangedListener vclistener,
            boolean handlesVolumeAdjustment,
            @AbsoluteDeviceVolumeBehavior int behavior) {
        Objects.requireNonNull(device);
        Objects.requireNonNull(volumes);
        Objects.requireNonNull(executor);
        Objects.requireNonNull(vclistener);

        final ListenerInfo listenerInfo = new ListenerInfo(
                vclistener, executor, device, handlesVolumeAdjustment);
        synchronized (mDeviceVolumeListenerLock) {
            if (mDeviceVolumeListeners == null) {
                mDeviceVolumeListeners = new ArrayList<>();
            }
            if (mDeviceVolumeListeners.size() == 0) {
                if (mDeviceVolumeDispatcherStub == null) {
                    mDeviceVolumeDispatcherStub = new DeviceVolumeDispatcherStub();
                }
            } else {
                mDeviceVolumeListeners.removeIf(info -> info.mDevice.equalTypeAddress(device));
            }
            mDeviceVolumeListeners.add(listenerInfo);
            mDeviceVolumeDispatcherStub.register(true, device, volumes, handlesVolumeAdjustment,
                    behavior);
        }
    }

    /**
     * Manages the OnDeviceVolumeBehaviorChangedListener listeners and
     * DeviceVolumeBehaviorDispatcherStub
     */
    private final CallbackUtil.LazyListenerManager<OnDeviceVolumeBehaviorChangedListener>
            mDeviceVolumeBehaviorChangedListenerMgr = new CallbackUtil.LazyListenerManager();

    /**
     * @hide
     * Interface definition of a callback to be invoked when the volume behavior of an audio device
     * is updated.
     */
    public interface OnDeviceVolumeBehaviorChangedListener {
        /**
         * Called on the listener to indicate that the volume behavior of a device has changed.
         * @param device the audio device whose volume behavior changed
         * @param volumeBehavior the new volume behavior of the audio device
         */
        void onDeviceVolumeBehaviorChanged(
                @NonNull AudioDeviceAttributes device,
                @DeviceVolumeBehavior int volumeBehavior);
    }

    /**
     * @hide
     * Adds a listener for being notified of changes to any device's volume behavior.
     * @throws SecurityException if the caller doesn't hold the required permission
     */
    @RequiresPermission(anyOf = {
            android.Manifest.permission.MODIFY_AUDIO_ROUTING,
            android.Manifest.permission.QUERY_AUDIO_STATE
    })
    public void addOnDeviceVolumeBehaviorChangedListener(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull OnDeviceVolumeBehaviorChangedListener listener)
            throws SecurityException {
        mDeviceVolumeBehaviorChangedListenerMgr.addListener(executor, listener,
                "addOnDeviceVolumeBehaviorChangedListener",
                () -> new DeviceVolumeBehaviorDispatcherStub());
    }

    /**
     * @hide
     * Removes a previously added listener of changes to device volume behavior.
     */
    @RequiresPermission(anyOf = {
            android.Manifest.permission.MODIFY_AUDIO_ROUTING,
            android.Manifest.permission.QUERY_AUDIO_STATE
    })
    public void removeOnDeviceVolumeBehaviorChangedListener(
            @NonNull OnDeviceVolumeBehaviorChangedListener listener) {
        mDeviceVolumeBehaviorChangedListenerMgr.removeListener(listener,
                "removeOnDeviceVolumeBehaviorChangedListener");
    }

    /**
     * @hide
     * Changes the volume setting for an audio device, without affecting the current volume for the
     * corresponding use case, regardless of the current audio path.
     *
     * <p>Note: if you need to control volume on a given audio device (e.g. to change volume during
     * playback), use {@link #setVolumeForDevice}.
     *
     * @param vi the volume information, only stream-based volumes are supported
     * @param ada the device for which volume is to be modified
     */
    @SystemApi
    @RequiresPermission(anyOf = {
            Manifest.permission.MODIFY_AUDIO_ROUTING,
            Manifest.permission.MODIFY_AUDIO_SETTINGS_PRIVILEGED
    })
    public void setDeviceVolume(@NonNull VolumeInfo vi, @NonNull AudioDeviceAttributes ada) {
        try {
            getService().setDeviceVolume(vi, ada, mPackageName);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     * Sets the volume on the given audio device
     *
     * @param vi the volume information, only stream-based volumes are supported for now.
     * <ul>If vi doesn't contain a mute state: the index is set to vi's index </ul>
     * <ul>If vi contains a mute state:
     * <li> if the ada device is the current device for the VolumeInfo routing strategy, the mute
     *          state will be applied to the stream type</li>
     * <li> otherwise, the mute state is left unchanged: if vi is muted, the index is set to its
     *           min value; if vi is unmuted, the index is set to vi's index.</li>
     * </ul>
     *
     * @param ada the device for which volume is to be modified
     */
    @SystemApi
    @RequiresPermission(anyOf = {
            Manifest.permission.MODIFY_AUDIO_ROUTING,
            Manifest.permission.MODIFY_AUDIO_SETTINGS_PRIVILEGED
    })
    @FlaggedApi(FLAG_DEVICE_VOLUME_APIS)
    public void setVolumeForDevice(@NonNull VolumeInfo vi, @NonNull AudioDeviceAttributes ada) {
        try {
            getService().setVolumeForDevice(vi, ada, mPackageName);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     * Adjusts the volume on the given audio device
     *
     * @param vi the volume information, only stream-based volume infos are supported
     * @param direction to describe the adjustment
     * @param ada the device for which volume is to be modified
     */
    @SystemApi
    @RequiresPermission(anyOf = {
            Manifest.permission.MODIFY_AUDIO_ROUTING,
            Manifest.permission.MODIFY_AUDIO_SETTINGS_PRIVILEGED
    })
    @FlaggedApi(FLAG_DEVICE_VOLUME_APIS)
    public void adjustVolumeForDevice(@NonNull VolumeInfo vi, @VolumeAdjustmentNoMute int
            direction, @NonNull AudioDeviceAttributes ada) {
        try {
            getService().adjustVolumeForDevice(vi, direction, ada, mPackageName);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     * Notifies the audio framework that the volume on the given absolute volume device has changed.
     * @param vi the volume information, only stream-based volumes are supported
     * @param ada the device for which volume is to be modified. Must have been registered before
     *            as an absolute volume device
     */
    @SystemApi
    @RequiresPermission(anyOf = {
            Manifest.permission.MODIFY_AUDIO_SETTINGS_PRIVILEGED,
            Manifest.permission.BLUETOOTH_PRIVILEGED
    })
    @FlaggedApi(FLAG_UNIFY_ABSOLUTE_VOLUME_MANAGEMENT)
    public void notifyAbsoluteVolumeChanged(@NonNull VolumeInfo vi,
            @NonNull AudioDeviceAttributes ada) {
        try {
            getService().notifyAbsoluteVolumeChanged(vi, ada, mPackageName);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     * Returns the volume on the given audio device for the given volume information.
     * For instance if using a {@link VolumeInfo} configured for {@link AudioManager#STREAM_ALARM},
     * it will return the alarm volume. When no volume index has ever been set for the given
     * device, the default volume will be returned (the volume setting that would have been
     * applied if playback for that use case had started).
     * @param vi the volume information, only stream-based volumes are supported. Information
     *           other than the stream type is ignored.
     * @param ada the device for which volume is to be retrieved
     */
    @SystemApi
    @RequiresPermission(anyOf = {
            Manifest.permission.MODIFY_AUDIO_ROUTING,
            Manifest.permission.MODIFY_AUDIO_SETTINGS_PRIVILEGED
    })
    public @NonNull VolumeInfo getDeviceVolume(@NonNull VolumeInfo vi,
            @NonNull AudioDeviceAttributes ada) {
        try {
            return getService().getDeviceVolume(vi, ada, mPackageName);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
        return VolumeInfo.getDefaultVolumeInfo();
    }

    /**
     * @hide
     * Sets the input gain index for a particular AudioDeviceAttributes.
     * TODO(b/364923030): create InputVolumeInfo on top of VolumeInfo rather than using index to
     * handle volume information, to solve issues e.g. gain index ranges might be different for
     * different categories of devices.
     */
    @FlaggedApi(FLAG_ENABLE_AUDIO_INPUT_DEVICE_ROUTING_AND_VOLUME_CONTROL)
    @RequiresPermission(Manifest.permission.MODIFY_AUDIO_SETTINGS_PRIVILEGED)
    public void setInputGainIndex(@NonNull AudioDeviceAttributes ada, int index) {
        try {
            getService().setInputGainIndex(ada, index);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     * Gets the input gain index for a particular AudioDeviceAttributes.
     */
    @FlaggedApi(FLAG_ENABLE_AUDIO_INPUT_DEVICE_ROUTING_AND_VOLUME_CONTROL)
    @RequiresPermission(Manifest.permission.MODIFY_AUDIO_SETTINGS_PRIVILEGED)
    public int getInputGainIndex(@NonNull AudioDeviceAttributes ada) {
        try {
            return getService().getInputGainIndex(ada);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     * Gets the maximum input gain index for input device.
     */
    @FlaggedApi(FLAG_ENABLE_AUDIO_INPUT_DEVICE_ROUTING_AND_VOLUME_CONTROL)
    @RequiresPermission(Manifest.permission.MODIFY_AUDIO_SETTINGS_PRIVILEGED)
    public int getMaxInputGainIndex() {
        try {
            return getService().getMaxInputGainIndex();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     * Gets the minimum input gain index for input device.
     */
    @FlaggedApi(FLAG_ENABLE_AUDIO_INPUT_DEVICE_ROUTING_AND_VOLUME_CONTROL)
    @RequiresPermission(Manifest.permission.MODIFY_AUDIO_SETTINGS_PRIVILEGED)
    public int getMinInputGainIndex() {
        try {
            return getService().getMinInputGainIndex();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     * Indicates if an input device does not support input gain control.
     *     <p>The following APIs have no effect when input gain is fixed:
     *     <ul>
     *       <li>{@link #setInputGainIndex(AudioDeviceAttributes, int)}
     *     </ul>
     */
    @FlaggedApi(FLAG_ENABLE_AUDIO_INPUT_DEVICE_ROUTING_AND_VOLUME_CONTROL)
    @RequiresPermission(Manifest.permission.MODIFY_AUDIO_SETTINGS_PRIVILEGED)
    public boolean isInputGainFixed(@NonNull AudioDeviceAttributes ada) {
        try {
            return getService().isInputGainFixed(ada);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     * Return human-readable name for volume behavior
     * @param behavior one of the volume behaviors defined in AudioManager
     * @return a string for the given behavior
     */
    public static String volumeBehaviorName(@DeviceVolumeBehavior int behavior) {
        switch (behavior) {
            case DEVICE_VOLUME_BEHAVIOR_VARIABLE:
                return "DEVICE_VOLUME_BEHAVIOR_VARIABLE";
            case DEVICE_VOLUME_BEHAVIOR_FULL:
                return "DEVICE_VOLUME_BEHAVIOR_FULL";
            case DEVICE_VOLUME_BEHAVIOR_FIXED:
                return "DEVICE_VOLUME_BEHAVIOR_FIXED";
            case DEVICE_VOLUME_BEHAVIOR_ABSOLUTE:
                return "DEVICE_VOLUME_BEHAVIOR_ABSOLUTE";
            case DEVICE_VOLUME_BEHAVIOR_ABSOLUTE_MULTI_MODE:
                return "DEVICE_VOLUME_BEHAVIOR_ABSOLUTE_MULTI_MODE";
            case DEVICE_VOLUME_BEHAVIOR_ABSOLUTE_ADJUST_ONLY:
                return "DEVICE_VOLUME_BEHAVIOR_ABSOLUTE_ADJUST_ONLY";
            default:
                return "invalid volume behavior " + behavior;
        }
    }

    private final class DeviceVolumeBehaviorDispatcherStub
            extends IDeviceVolumeBehaviorDispatcher.Stub implements CallbackUtil.DispatcherStub {
        public void register(boolean register) {
            try {
                getService().registerDeviceVolumeBehaviorDispatcher(register, this);
            } catch (RemoteException e) {
                e.rethrowFromSystemServer();
            }
        }

        @Override
        public void dispatchDeviceVolumeBehaviorChanged(@NonNull AudioDeviceAttributes device,
                @DeviceVolumeBehavior int volumeBehavior) {
            mDeviceVolumeBehaviorChangedListenerMgr.callListeners((listener) ->
                    listener.onDeviceVolumeBehaviorChanged(device, volumeBehavior));
        }
    }

    private static IAudioService getService() {
        if (sService != null) {
            return sService;
        }
        IBinder b = ServiceManager.getService(Context.AUDIO_SERVICE);
        sService = IAudioService.Stub.asInterface(b);
        return sService;
    }
}
