/*
 * Copyright (C) 2014 The Android Open Source Project
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
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_FORBIDDEN;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTED;
import static android.bluetooth.BluetoothUtils.isValidDevice;

import android.annotation.Hide;
import android.annotation.NonNull;
import android.annotation.RequiresNoPermission;
import android.annotation.RequiresPermission;
import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.bluetooth.annotations.RequiresBluetoothConnectPermission;
import android.content.AttributionSource;
import android.content.Context;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import java.util.Collections;
import java.util.List;

/**
 * This class provides the public APIs to control the Bluetooth A2DP Sink profile.
 *
 * <p>BluetoothA2dpSink is a proxy object for controlling the Bluetooth A2DP Sink Service via IPC.
 * Use {@link BluetoothAdapter#getProfileProxy} to get the BluetoothA2dpSink proxy object.
 */
@Hide
@SystemApi
public final class BluetoothA2dpSink implements BluetoothProfile {
    private static final String TAG = BluetoothA2dpSink.class.getSimpleName();

    private static final boolean VDBG = Log.isLoggable("bluetooth", Log.VERBOSE);

    /**
     * Intent used to broadcast the change in connection state of the A2DP Sink profile.
     *
     * <p>This intent will have 3 extras:
     *
     * <ul>
     *   <li>{@link #EXTRA_STATE} - The current state of the profile.
     *   <li>{@link #EXTRA_PREVIOUS_STATE}- The previous state of the profile.
     *   <li>{@link BluetoothDevice#EXTRA_DEVICE} - The remote device.
     * </ul>
     *
     * <p>{@link #EXTRA_STATE} or {@link #EXTRA_PREVIOUS_STATE} can be any of {@link
     * #STATE_DISCONNECTED}, {@link #STATE_CONNECTING}, {@link #STATE_CONNECTED}, {@link
     * #STATE_DISCONNECTING}.
     */
    @Hide
    @SystemApi
    @SuppressLint("ActionValue")
    @RequiresBluetoothConnectPermission
    @RequiresPermission(BLUETOOTH_CONNECT)
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_CONNECTION_STATE_CHANGED =
            "android.bluetooth.a2dp-sink.profile.action.CONNECTION_STATE_CHANGED";

    private final BluetoothAdapter mAdapter;
    private final AttributionSource mAttributionSource;

    private IBluetoothA2dpSink mService;

    /**
     * Create a BluetoothA2dp proxy object for interacting with the local Bluetooth A2DP service.
     */
    /* package */ BluetoothA2dpSink(Context context, BluetoothAdapter adapter) {
        mAdapter = adapter;
        mAttributionSource = adapter.getAttributionSource();
        mService = null;
    }

    @Hide
    @Override
    @RequiresNoPermission
    public void onServiceConnected(IBinder service) {
        mService = IBluetoothA2dpSink.Stub.asInterface(service);
    }

    @Hide
    @Override
    @RequiresNoPermission
    public void onServiceDisconnected() {
        mService = null;
    }

    private IBluetoothA2dpSink getService() {
        return mService;
    }

    @Hide
    @Override
    @RequiresNoPermission
    public BluetoothAdapter getAdapter() {
        return mAdapter;
    }

    @Override
    @SuppressWarnings("Finalize") // empty finalize for api signature
    public void finalize() {}

    /** {@inheritDoc} */
    @Hide
    @Override
    @RequiresBluetoothConnectPermission
    @RequiresPermission(BLUETOOTH_CONNECT)
    public List<BluetoothDevice> getConnectedDevices() {
        if (VDBG) log("getConnectedDevices()");
        final IBluetoothA2dpSink service = getService();
        if (service == null) {
            Log.w(TAG, "Proxy not attached to service");
            log(Log.getStackTraceString(new Throwable()));
        } else if (isEnabled()) {
            try {
                return Attributable.setAttributionSource(
                        service.getConnectedDevices(mAttributionSource), mAttributionSource);
            } catch (RemoteException e) {
                Log.e(TAG, e.toString() + "\n" + Log.getStackTraceString(new Throwable()));
            }
        }
        return Collections.emptyList();
    }

    /** {@inheritDoc} */
    @Hide
    @Override
    @RequiresBluetoothConnectPermission
    @RequiresPermission(BLUETOOTH_CONNECT)
    public List<BluetoothDevice> getDevicesMatchingConnectionStates(int[] states) {
        if (VDBG) log("getDevicesMatchingStates()");
        final IBluetoothA2dpSink service = getService();
        if (service == null) {
            Log.w(TAG, "Proxy not attached to service");
            log(Log.getStackTraceString(new Throwable()));
        } else if (isEnabled()) {
            try {
                return Attributable.setAttributionSource(
                        service.getDevicesMatchingConnectionStates(states, mAttributionSource),
                        mAttributionSource);
            } catch (RemoteException e) {
                Log.e(TAG, e.toString() + "\n" + Log.getStackTraceString(new Throwable()));
            }
        }
        return Collections.emptyList();
    }

    /** {@inheritDoc} */
    @Hide
    @Override
    @RequiresBluetoothConnectPermission
    @RequiresPermission(BLUETOOTH_CONNECT)
    public int getConnectionState(BluetoothDevice device) {
        final IBluetoothA2dpSink service = getService();
        if (service == null) {
            Log.w(TAG, "Proxy not attached to service");
            log(Log.getStackTraceString(new Throwable()));
        } else if (isEnabled() && isValidDevice(device)) {
            try {
                return service.getConnectionState(device, mAttributionSource);
            } catch (RemoteException e) {
                Log.e(TAG, e.toString() + "\n" + Log.getStackTraceString(new Throwable()));
            }
        }
        return STATE_DISCONNECTED;
    }

    /**
     * Set connection policy of the profile
     *
     * <p>The device should already be paired. Connection policy can be one of {@link
     * #CONNECTION_POLICY_ALLOWED}, {@link #CONNECTION_POLICY_FORBIDDEN}, {@link
     * #CONNECTION_POLICY_UNKNOWN}
     *
     * @param device Paired bluetooth device
     * @param connectionPolicy is the connection policy to set to for this profile
     * @return true if connectionPolicy is set, false on error
     */
    @Hide
    @SystemApi
    @RequiresBluetoothConnectPermission
    @RequiresPermission(allOf = {BLUETOOTH_CONNECT, BLUETOOTH_PRIVILEGED})
    public boolean setConnectionPolicy(
            @NonNull BluetoothDevice device, @ConnectionPolicy int connectionPolicy) {
        log("setConnectionPolicy(" + device + ", " + connectionPolicy + ")");
        final IBluetoothA2dpSink service = getService();
        if (service == null) {
            Log.w(TAG, "Proxy not attached to service");
            log(Log.getStackTraceString(new Throwable()));
        } else if (isEnabled()
                && isValidDevice(device)
                && BluetoothProfile.isValidConnectionPolicy(connectionPolicy)) {
            try {
                return service.setConnectionPolicy(device, connectionPolicy, mAttributionSource);
            } catch (RemoteException e) {
                Log.e(TAG, e.toString() + "\n" + Log.getStackTraceString(new Throwable()));
            }
        }
        return false;
    }

    /**
     * Get the connection policy of the profile.
     *
     * <p>The connection policy can be any of: {@link #CONNECTION_POLICY_ALLOWED}, {@link
     * #CONNECTION_POLICY_FORBIDDEN}, {@link #CONNECTION_POLICY_UNKNOWN}
     *
     * @param device Bluetooth device
     * @return connection policy of the device
     */
    @Hide
    @SystemApi
    @RequiresBluetoothConnectPermission
    @RequiresPermission(allOf = {BLUETOOTH_CONNECT, BLUETOOTH_PRIVILEGED})
    public @ConnectionPolicy int getConnectionPolicy(@NonNull BluetoothDevice device) {
        if (VDBG) log("getConnectionPolicy(" + device + ")");
        final IBluetoothA2dpSink service = getService();
        if (service == null) {
            Log.w(TAG, "Proxy not attached to service");
            log(Log.getStackTraceString(new Throwable()));
        } else if (isEnabled() && isValidDevice(device)) {
            try {
                return service.getConnectionPolicy(device, mAttributionSource);
            } catch (RemoteException e) {
                Log.e(TAG, e.toString() + "\n" + Log.getStackTraceString(new Throwable()));
            }
        }
        return CONNECTION_POLICY_FORBIDDEN;
    }

    /**
     * Check if audio is playing on the bluetooth device (A2DP profile is streaming music).
     *
     * @param device BluetoothDevice device
     * @return true if audio is playing (A2dp is streaming music), false otherwise
     */
    @Hide
    @SystemApi
    @RequiresBluetoothConnectPermission
    @RequiresPermission(allOf = {BLUETOOTH_CONNECT, BLUETOOTH_PRIVILEGED})
    public boolean isAudioPlaying(@NonNull BluetoothDevice device) {
        if (VDBG) log("isAudioPlaying(" + device + ")");
        final IBluetoothA2dpSink service = getService();
        if (service == null) {
            Log.w(TAG, "Proxy not attached to service");
            log(Log.getStackTraceString(new Throwable()));
        } else if (isEnabled() && isValidDevice(device)) {
            try {
                return service.isA2dpPlaying(device, mAttributionSource);
            } catch (RemoteException e) {
                Log.e(TAG, e.toString() + "\n" + Log.getStackTraceString(new Throwable()));
            }
        }
        return false;
    }

    private boolean isEnabled() {
        return mAdapter.getState() == BluetoothAdapter.STATE_ON;
    }

    private static void log(String msg) {
        Log.d(TAG, msg);
    }
}
