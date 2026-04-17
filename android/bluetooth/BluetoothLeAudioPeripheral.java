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

package android.bluetooth;

import static android.Manifest.permission.BLUETOOTH_CONNECT;
import static android.Manifest.permission.BLUETOOTH_PRIVILEGED;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTED;
import static android.bluetooth.BluetoothUtils.callServiceIfEnabled;
import static android.bluetooth.BluetoothUtils.enforcePermissionInFramework;

import static java.util.Objects.requireNonNull;

import android.annotation.CallbackExecutor;
import android.annotation.FlaggedApi;
import android.annotation.Hide;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresNoPermission;
import android.annotation.RequiresPermission;
import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.bluetooth.annotations.RequiresBluetoothConnectPermission;
import android.content.Context;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import com.android.bluetooth.flags.Flags;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * This class provides the public APIs to control the Bluetooth LE Audio Peripheral profile.
 *
 * <p>An LE Audio Peripheral is a device that acts as a sink of audio for an LE Audio Central
 * device, such as a headset or speaker. This profile is defined by the Bluetooth Special Interest
 * Group (SIG) in the LE Audio specifications.
 */
@Hide
@SystemApi
@FlaggedApi(Flags.FLAG_LEAUDIO_PERIPHERAL_FEATURE)
public final class BluetoothLeAudioPeripheral implements BluetoothProfile, AutoCloseable {

    private static final String TAG = BluetoothLeAudioPeripheral.class.getSimpleName();

    private final BluetoothAdapter mAdapter;
    private final Context mContext;

    private IBluetoothLeAudioPeripheral mService;

    /** This interface provide callbacks invoked when a service state changes */
    @Hide
    @SystemApi
    @FlaggedApi(Flags.FLAG_LEAUDIO_PERIPHERAL_FEATURE)
    public interface Callback {
        /**
         * Callback invoked when the stream type of a streaming device changes, including change to
         * {@link #STREAM_TYPE_NONE} when device stops streaming
         *
         * @param device The device whose stream types have changed
         * @param streamTypes Latest stream types bitmask for this device
         */
        default void onStreamTypesChanged(
                @NonNull BluetoothDevice device, @StreamType int streamTypes) {}
    }

    private final CallbackWrapper<Callback, IBluetoothLeAudioPeripheral> mCallbackWrapper;

    private final IBluetoothLeAudioPeripheralCallback mCallback =
            new IBluetoothLeAudioPeripheralCallback.Stub() {
                @RequiresNoPermission
                @Override
                public void onStreamTypesChanged(
                        @NonNull BluetoothDevice device, @StreamType int streamTypes) {
                    Attributable.setAttributionSource(device, mContext.getAttributionSource());
                    mCallbackWrapper.forEach(cb -> cb.onStreamTypesChanged(device, streamTypes));
                }
            };

    /**
     * Intent used to broadcast the change in connection state of the LE Audio Peripheral profile.
     *
     * <p>This intent will have 3 extras:
     *
     * <ul>
     *   <li>{@link #EXTRA_STATE} - The current state of the profile.
     *   <li>{@link #EXTRA_PREVIOUS_STATE} - The previous state of the profile.
     *   <li>{@link BluetoothDevice#EXTRA_DEVICE} - The remote device.
     * </ul>
     *
     * <p>{@link #EXTRA_STATE} or {@link #EXTRA_PREVIOUS_STATE} can be any of {@link
     * #STATE_DISCONNECTED}, {@link #STATE_CONNECTED}.
     */
    @Hide
    @SuppressLint("ActionValue")
    @RequiresBluetoothConnectPermission
    @RequiresPermission(allOf = {BLUETOOTH_CONNECT, BLUETOOTH_PRIVILEGED})
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_CONNECTION_STATE_CHANGED =
            "android.bluetooth.le-audio-peripheral.profile.action.CONNECTION_STATE_CHANGED";

    /**
     * Create a BluetoothLeAudioPeripheral proxy object for interacting with the local Bluetooth LE
     * Audio Peripheral service.
     */
    @Hide
    @SuppressWarnings("IncorrectRequiresPermissionPropagation")
    /* package */ BluetoothLeAudioPeripheral(
            @NonNull Context context, @NonNull BluetoothAdapter adapter) {
        mAdapter = adapter;
        mContext = context;
        mService = null;

        Consumer<IBluetoothLeAudioPeripheral> registerConsumer =
                (IBluetoothLeAudioPeripheral service) -> {
                    try {
                        service.registerCallback(mCallback, mContext.getAttributionSource());
                    } catch (RemoteException e) {
                        Log.e(TAG, e.toString() + "\n" + Log.getStackTraceString(new Throwable()));
                    }
                };
        Consumer<IBluetoothLeAudioPeripheral> unregisterConsumer =
                (IBluetoothLeAudioPeripheral service) -> {
                    try {
                        service.unregisterCallback(mCallback, mContext.getAttributionSource());
                    } catch (RemoteException e) {
                        Log.e(TAG, e.toString() + "\n" + Log.getStackTraceString(new Throwable()));
                    }
                };

        mCallbackWrapper = new CallbackWrapper(registerConsumer, unregisterConsumer);
    }

    @Override
    public void close() {
        mAdapter.closeProfileProxy(this);
    }

    @Hide
    @Override
    @RequiresNoPermission
    public void onServiceConnected(@Nullable IBinder service) {
        if (service == null) {
            throw new IllegalStateException("BluetoothLeAudioPeripheral service IBinder is null.");
        }
        mService = IBluetoothLeAudioPeripheral.Stub.asInterface(service);
        mCallbackWrapper.registerToNewService(mService);
    }

    /** Handles the disconnection from the backing service. */
    @Hide
    @Override
    @RequiresNoPermission
    public void onServiceDisconnected() {
        mService = null;
    }

    @Hide
    private @Nullable IBluetoothLeAudioPeripheral getService() {
        return mService;
    }

    @Hide
    @Override
    @RequiresNoPermission
    public @NonNull BluetoothAdapter getAdapter() {
        return mAdapter;
    }

    /** {@inheritDoc} */
    @Override
    @RequiresBluetoothConnectPermission
    @RequiresPermission(allOf = {BLUETOOTH_CONNECT, BLUETOOTH_PRIVILEGED})
    public @NonNull List<BluetoothDevice> getConnectedDevices() {
        Log.d(TAG, "getConnectedDevices()");
        return getDevicesMatchingConnectionStates(new int[] {STATE_CONNECTED});
    }

    /** {@inheritDoc} */
    @Override
    @RequiresBluetoothConnectPermission
    @RequiresPermission(allOf = {BLUETOOTH_CONNECT, BLUETOOTH_PRIVILEGED})
    public @NonNull List<BluetoothDevice> getDevicesMatchingConnectionStates(
            @Nullable int[] states) {
        Log.d(TAG, "getDevicesMatchingConnectionStates()");
        List<BluetoothDevice> defaultValue = Collections.emptyList();

        if (states == null) {
            return defaultValue;
        }

        return callServiceIfEnabled(
                mAdapter,
                this::getService,
                s ->
                        Attributable.setAttributionSource(
                                s.getDevicesMatchingConnectionStates(
                                        states, mContext.getAttributionSource()),
                                mContext.getAttributionSource()),
                defaultValue);
    }

    /** {@inheritDoc} */
    @Override
    @RequiresBluetoothConnectPermission
    @RequiresPermission(allOf = {BLUETOOTH_CONNECT, BLUETOOTH_PRIVILEGED})
    public @BtProfileState int getConnectionState(@Nullable BluetoothDevice device) {
        int defaultValue = STATE_DISCONNECTED;

        return callServiceIfEnabled(
                mAdapter,
                this::getService,
                s -> s.getConnectionState(device, mContext.getAttributionSource()),
                defaultValue);
    }

    /**
     * Register a {@link Callback} that will be invoked during the operation of this profile.
     *
     * @param executor An {@link Executor} to execute given callback.
     * @param callback User implementation of the {@link Callback}.
     * @throws IllegalArgumentException when such callback is already registered.
     */
    @Hide
    @SystemApi
    @RequiresPermission(allOf = {BLUETOOTH_CONNECT, BLUETOOTH_PRIVILEGED})
    public void registerCallback(
            @NonNull @CallbackExecutor Executor executor, @NonNull Callback callback) {
        Log.d(TAG, "registerCallback()");
        enforcePermissionInFramework(mContext, BLUETOOTH_CONNECT, BLUETOOTH_PRIVILEGED);
        mCallbackWrapper.registerCallback(getService(), callback, executor);
    }

    /**
     * Unregister the specified {@link Callback}.
     *
     * <p>The same {@link Callback} object used when calling {@link #registerCallback(Executor,
     * Callback)} must be used.
     *
     * <p>Callbacks are automatically unregistered when the application process goes away
     *
     * @param callback User implementation of the {@link Callback}.
     * @throws IllegalArgumentException when no such callback is registered.
     */
    @Hide
    @SystemApi
    @RequiresPermission(allOf = {BLUETOOTH_CONNECT, BLUETOOTH_PRIVILEGED})
    public void unregisterCallback(@NonNull Callback callback) {
        Log.d(TAG, "unregisterCallback()");
        enforcePermissionInFramework(mContext, BLUETOOTH_CONNECT, BLUETOOTH_PRIVILEGED);
        mCallbackWrapper.unregisterCallback(getService(), callback);
    }

    /**
     * Constant representing no stream type.
     *
     * <p>This is reported via {@link Callback#onStreamTypesChanged} when a peer device has no
     * active stream.
     */
    @Hide @SystemApi public static final int STREAM_TYPE_NONE = 0;

    /** A stream type for a phone call. */
    @Hide @SystemApi public static final int STREAM_TYPE_CALL = 1 << 0;

    /** A stream type for media playback. */
    @Hide @SystemApi public static final int STREAM_TYPE_MEDIA = 1 << 1;

    /** A stream type for game audio. */
    @Hide @SystemApi public static final int STREAM_TYPE_GAME = 1 << 2;

    /** A stream type for audio recording. */
    @Hide @SystemApi public static final int STREAM_TYPE_RECORDING = 1 << 3;

    /** A stream type for a voice assistant. */
    @Hide @SystemApi public static final int STREAM_TYPE_VOICE_ASSISTANT = 1 << 4;

    @Hide
    @IntDef(
            flag = true,
            prefix = "STREAM_TYPE_",
            value = {
                STREAM_TYPE_NONE,
                STREAM_TYPE_CALL,
                STREAM_TYPE_MEDIA,
                STREAM_TYPE_GAME,
                STREAM_TYPE_RECORDING,
                STREAM_TYPE_VOICE_ASSISTANT,
            })
    @Retention(RetentionPolicy.SOURCE)
    public @interface StreamType {}

    /**
     * Enable or disable certain stream types to be established from a particular device.
     *
     * @param device A remote device.
     * @param streamTypes a bitmask of types to modify.
     * @param enabled true to enable these types, false to disable them.
     */
    @Hide
    @SystemApi
    @RequiresBluetoothConnectPermission
    @RequiresPermission(allOf = {BLUETOOTH_CONNECT, BLUETOOTH_PRIVILEGED})
    public void setStreamTypesEnabled(
            @NonNull BluetoothDevice device, @StreamType int streamTypes, boolean enabled) {
        Log.d(TAG, "setStreamTypesEnabled(" + device + ", " + streamTypes + ", " + enabled + ")");
        enforcePermissionInFramework(mContext, BLUETOOTH_CONNECT, BLUETOOTH_PRIVILEGED);

        requireNonNull(device);
        callServiceIfEnabled(
                mAdapter,
                this::getService,
                s ->
                        s.setStreamTypesEnabled(
                                device, streamTypes, enabled, mContext.getAttributionSource()));
    }

    /**
     * Retrieve the set of stream types enabled for the remote device to establish.
     *
     * @param device A remote device.
     * @return A bitmask of stream types enabled for the remote device to establish.
     */
    @Hide
    @SystemApi
    @RequiresBluetoothConnectPermission
    @RequiresPermission(allOf = {BLUETOOTH_CONNECT, BLUETOOTH_PRIVILEGED})
    public @StreamType int getEnabledStreamTypes(@NonNull BluetoothDevice device) {
        Log.d(TAG, "getEnabledStreamTypes(" + device + ")");
        int defaultValue = STREAM_TYPE_NONE;

        requireNonNull(device);
        return callServiceIfEnabled(
                mAdapter,
                this::getService,
                s -> s.getEnabledStreamTypes(device, mContext.getAttributionSource()),
                defaultValue);
    }
}
