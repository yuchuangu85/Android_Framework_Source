/*
 * Copyright (C) 2011 The Android Open Source Project
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

import static java.util.Objects.requireNonNull;

import android.annotation.Hide;
import android.annotation.NonNull;
import android.annotation.RequiresNoPermission;
import android.annotation.RequiresPermission;
import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.bluetooth.BluetoothDevice.Transport;
import android.bluetooth.annotations.RequiresBluetoothConnectPermission;
import android.bluetooth.annotations.RequiresLegacyBluetoothAdminPermission;
import android.bluetooth.annotations.RequiresLegacyBluetoothPermission;
import android.content.AttributionSource;
import android.content.Context;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import java.util.Collections;
import java.util.List;

/**
 * This class provides the public APIs to control the Bluetooth Input Device Profile.
 *
 * <p>BluetoothHidHost is a proxy object for controlling the Bluetooth Service via IPC. Use {@link
 * BluetoothAdapter#getProfileProxy} to get the BluetoothHidHost proxy object.
 *
 * <p>Each method is protected with its appropriate permission.
 */
@Hide
@SystemApi
public final class BluetoothHidHost implements BluetoothProfile {
    private static final String TAG = BluetoothHidHost.class.getSimpleName();

    private static final boolean VDBG = Log.isLoggable("bluetooth", Log.VERBOSE);

    /**
     * Intent used to broadcast the change in connection state of the Input Device profile.
     *
     * <p>This intent will have 3 extras:
     *
     * <ul>
     *   <li>{@link #EXTRA_STATE} - The current state of the profile.
     *   <li>{@link #EXTRA_PREVIOUS_STATE}- The previous state of the profile.
     *   <li>{@link BluetoothDevice#EXTRA_TRANSPORT} - Transport of the connection.
     *   <li>{@link BluetoothDevice#EXTRA_DEVICE} - The remote device.
     * </ul>
     *
     * <p>{@link #EXTRA_STATE} or {@link #EXTRA_PREVIOUS_STATE} can be any of {@link
     * #STATE_DISCONNECTED}, {@link #STATE_CONNECTING}, {@link #STATE_CONNECTED}, {@link
     * #STATE_DISCONNECTING}.
     *
     * <p>{@link BluetoothDevice#EXTRA_TRANSPORT} can be any of {@link
     * BluetoothDevice#TRANSPORT_BREDR}, {@link BluetoothDevice#TRANSPORT_LE}.
     */
    @SuppressLint("ActionValue")
    @RequiresLegacyBluetoothPermission
    @RequiresBluetoothConnectPermission
    @RequiresPermission(BLUETOOTH_CONNECT)
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_CONNECTION_STATE_CHANGED =
            "android.bluetooth.input.profile.action.CONNECTION_STATE_CHANGED";

    @Hide
    @RequiresBluetoothConnectPermission
    @RequiresPermission(BLUETOOTH_CONNECT)
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_PROTOCOL_MODE_CHANGED =
            "android.bluetooth.input.profile.action.PROTOCOL_MODE_CHANGED";

    @Hide
    @RequiresBluetoothConnectPermission
    @RequiresPermission(BLUETOOTH_CONNECT)
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_HANDSHAKE =
            "android.bluetooth.input.profile.action.HANDSHAKE";

    @Hide
    @RequiresBluetoothConnectPermission
    @RequiresPermission(BLUETOOTH_CONNECT)
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_REPORT = "android.bluetooth.input.profile.action.REPORT";

    @Hide
    @RequiresBluetoothConnectPermission
    @RequiresPermission(BLUETOOTH_CONNECT)
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_VIRTUAL_UNPLUG_STATUS =
            "android.bluetooth.input.profile.action.VIRTUAL_UNPLUG_STATUS";

    @Hide
    @RequiresBluetoothConnectPermission
    @RequiresPermission(BLUETOOTH_CONNECT)
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_IDLE_TIME_CHANGED =
            "android.bluetooth.input.profile.action.IDLE_TIME_CHANGED";

    /** Return codes for the connect and disconnect Bluez / Dbus calls. */
    @Hide public static final int INPUT_DISCONNECT_FAILED_NOT_CONNECTED = 5000;

    @Hide public static final int INPUT_CONNECT_FAILED_ALREADY_CONNECTED = 5001;

    @Hide public static final int INPUT_CONNECT_FAILED_ATTEMPT_FAILED = 5002;

    @Hide public static final int INPUT_OPERATION_GENERIC_FAILURE = 5003;

    @Hide public static final int INPUT_OPERATION_SUCCESS = 5004;

    @Hide public static final int PROTOCOL_REPORT_MODE = 0;

    @Hide public static final int PROTOCOL_BOOT_MODE = 1;

    @Hide public static final int PROTOCOL_UNSUPPORTED_MODE = 255;

    /*  int reportType, int reportType, int bufferSize */
    @Hide public static final byte REPORT_TYPE_INPUT = 1;

    @Hide public static final byte REPORT_TYPE_OUTPUT = 2;

    @Hide public static final byte REPORT_TYPE_FEATURE = 3;

    @Hide public static final int VIRTUAL_UNPLUG_STATUS_SUCCESS = 0;

    @Hide public static final int VIRTUAL_UNPLUG_STATUS_FAIL = 1;

    @Hide
    public static final String EXTRA_PROTOCOL_MODE =
            "android.bluetooth.BluetoothHidHost.extra.PROTOCOL_MODE";

    @Hide
    public static final String EXTRA_REPORT_TYPE =
            "android.bluetooth.BluetoothHidHost.extra.REPORT_TYPE";

    @Hide
    public static final String EXTRA_REPORT_ID =
            "android.bluetooth.BluetoothHidHost.extra.REPORT_ID";

    @Hide
    public static final String EXTRA_REPORT_BUFFER_SIZE =
            "android.bluetooth.BluetoothHidHost.extra.REPORT_BUFFER_SIZE";

    @Hide
    public static final String EXTRA_REPORT = "android.bluetooth.BluetoothHidHost.extra.REPORT";

    @Hide
    public static final String EXTRA_STATUS = "android.bluetooth.BluetoothHidHost.extra.STATUS";

    @Hide
    public static final String EXTRA_VIRTUAL_UNPLUG_STATUS =
            "android.bluetooth.BluetoothHidHost.extra.VIRTUAL_UNPLUG_STATUS";

    @Hide
    public static final String EXTRA_IDLE_TIME =
            "android.bluetooth.BluetoothHidHost.extra.IDLE_TIME";

    private final BluetoothAdapter mAdapter;
    private final AttributionSource mAttributionSource;

    private IBluetoothHidHost mService;

    /**
     * Create a BluetoothHidHost proxy object for interacting with the local Bluetooth Service which
     * handles the InputDevice profile
     */
    /* package */ BluetoothHidHost(Context context, BluetoothAdapter adapter) {
        mAdapter = adapter;
        mAttributionSource = adapter.getAttributionSource();
        mService = null;
    }

    @Hide
    @Override
    @RequiresNoPermission
    public void onServiceConnected(IBinder service) {
        mService = IBluetoothHidHost.Stub.asInterface(service);
    }

    @Hide
    @Override
    @RequiresNoPermission
    public void onServiceDisconnected() {
        mService = null;
    }

    private IBluetoothHidHost getService() {
        return mService;
    }

    @Hide
    @Override
    @RequiresNoPermission
    public BluetoothAdapter getAdapter() {
        return mAdapter;
    }

    /**
     * Initiate connection to a profile of the remote bluetooth device.
     *
     * <p>The system supports connection to multiple input devices.
     *
     * <p>This API returns false in scenarios like the profile on the device is already connected or
     * Bluetooth is not turned on. When this API returns true, it is guaranteed that connection
     * state intent for the profile will be broadcasted with the state. Users can get the connection
     * state of the profile from this intent.
     *
     * @param device Remote Bluetooth Device
     * @return false on immediate error, true otherwise
     */
    @Hide
    @RequiresBluetoothConnectPermission
    @RequiresPermission(
            allOf = {
                BLUETOOTH_CONNECT,
                BLUETOOTH_PRIVILEGED,
            })
    public boolean connect(BluetoothDevice device) {
        log("connect(" + device + ")");
        final IBluetoothHidHost service = getService();
        if (service == null) {
            Log.w(TAG, "Proxy not attached to service");
            log(Log.getStackTraceString(new Throwable()));
        } else if (isEnabled() && isValidDevice(device)) {
            try {
                return service.connect(device, mAttributionSource);
            } catch (RemoteException e) {
                Log.e(TAG, e.toString() + "\n" + Log.getStackTraceString(new Throwable()));
            }
        }
        return false;
    }

    /**
     * Initiate disconnection from a profile
     *
     * <p>This API will return false in scenarios like the profile on the Bluetooth device is not in
     * connected state etc. When this API returns, true, it is guaranteed that the connection state
     * change intent will be broadcasted with the state. Users can get the disconnection state of
     * the profile from this intent.
     *
     * <p>If the disconnection is initiated by a remote device, the state will transition from
     * {@link #STATE_CONNECTED} to {@link #STATE_DISCONNECTED}. If the disconnect is initiated by
     * the host (local) device the state will transition from {@link #STATE_CONNECTED} to state
     * {@link #STATE_DISCONNECTING} to state {@link #STATE_DISCONNECTED}. The transition to {@link
     * #STATE_DISCONNECTING} can be used to distinguish between the two scenarios.
     *
     * @param device Remote Bluetooth Device
     * @return false on immediate error, true otherwise
     */
    @Hide
    @RequiresBluetoothConnectPermission
    @RequiresPermission(
            allOf = {
                BLUETOOTH_CONNECT,
                BLUETOOTH_PRIVILEGED,
            })
    public boolean disconnect(BluetoothDevice device) {
        log("disconnect(" + device + ")");
        final IBluetoothHidHost service = getService();
        if (service == null) {
            Log.w(TAG, "Proxy not attached to service");
            log(Log.getStackTraceString(new Throwable()));
        } else if (isEnabled() && isValidDevice(device)) {
            try {
                return service.disconnect(device, mAttributionSource);
            } catch (RemoteException e) {
                Log.e(TAG, e.toString() + "\n" + Log.getStackTraceString(new Throwable()));
            }
        }
        return false;
    }

    /** {@inheritDoc} */
    @Hide
    @SystemApi
    @Override
    @RequiresBluetoothConnectPermission
    @RequiresPermission(BLUETOOTH_CONNECT)
    public @NonNull List<BluetoothDevice> getConnectedDevices() {
        final IBluetoothHidHost service = getService();
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
        final IBluetoothHidHost service = getService();
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
    @SystemApi
    @Override
    @RequiresBluetoothConnectPermission
    @RequiresPermission(BLUETOOTH_CONNECT)
    public int getConnectionState(@NonNull BluetoothDevice device) {
        if (device == null) {
            throw new IllegalArgumentException("device must not be null");
        }
        final IBluetoothHidHost service = getService();
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
    @RequiresPermission(
            allOf = {
                BLUETOOTH_CONNECT,
                BLUETOOTH_PRIVILEGED,
            })
    public boolean setConnectionPolicy(
            @NonNull BluetoothDevice device, @ConnectionPolicy int connectionPolicy) {
        log("setConnectionPolicy(" + device + ", " + connectionPolicy + ")");
        if (device == null) {
            throw new IllegalArgumentException("device must not be null");
        }
        final IBluetoothHidHost service = getService();
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
     * Set preferred transport for the device
     *
     * <p>The device should already be paired, services must have been discovered. This API is
     * effective only if both the HID and HOGP are supported on the remote device.
     *
     * @param device paired bluetooth device
     * @param transport the preferred transport to set for this device
     * @return true if preferred transport is set, false on error
     * @throws IllegalArgumentException if the {@code device} invalid.
     */
    @Hide
    @SystemApi
    @RequiresBluetoothConnectPermission
    @RequiresPermission(
            allOf = {
                BLUETOOTH_CONNECT,
                BLUETOOTH_PRIVILEGED,
            })
    public boolean setPreferredTransport(
            @NonNull BluetoothDevice device, @Transport int transport) {
        log("setPreferredTransport(" + device + ", " + transport + ")");

        requireNonNull(device);

        if (transport != BluetoothDevice.TRANSPORT_AUTO
                && transport != BluetoothDevice.TRANSPORT_BREDR
                && transport != BluetoothDevice.TRANSPORT_LE) {
            throw new IllegalArgumentException("Invalid transport value");
        }

        final IBluetoothHidHost service = getService();

        if (service == null) {
            Log.w(TAG, "Proxy not attached to service");
            log(Log.getStackTraceString(new Throwable()));
        } else if (!isEnabled()) {
            Log.w(TAG, "Not ready");
        } else if (!isValidDevice(device)) {
            throw new IllegalArgumentException("Invalid device");
        } else {
            try {
                return service.setPreferredTransport(device, transport, mAttributionSource);
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
    @RequiresPermission(
            allOf = {
                BLUETOOTH_CONNECT,
                BLUETOOTH_PRIVILEGED,
            })
    public @ConnectionPolicy int getConnectionPolicy(@NonNull BluetoothDevice device) {
        if (device == null) {
            throw new IllegalArgumentException("device must not be null");
        }
        final IBluetoothHidHost service = getService();
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
     * Get the preferred transport for the device.
     *
     * @param device Bluetooth device
     * @return preferred transport for the device
     * @throws IllegalArgumentException if the {@code device} invalid.
     */
    @Hide
    @SystemApi
    @RequiresBluetoothConnectPermission
    @RequiresPermission(
            allOf = {
                BLUETOOTH_CONNECT,
                BLUETOOTH_PRIVILEGED,
            })
    public @Transport int getPreferredTransport(@NonNull BluetoothDevice device) {
        requireNonNull(device);

        final IBluetoothHidHost service = getService();
        if (service == null) {
            Log.w(TAG, "Proxy not attached to service");
            log(Log.getStackTraceString(new Throwable()));
        } else if (!isEnabled()) {
            Log.w(TAG, "Not ready");
        } else if (!isValidDevice(device)) {
            throw new IllegalArgumentException("Invalid device");
        } else {
            try {
                return service.getPreferredTransport(device, mAttributionSource);
            } catch (RemoteException e) {
                Log.e(TAG, e.toString() + "\n" + Log.getStackTraceString(new Throwable()));
            }
        }
        return BluetoothDevice.TRANSPORT_AUTO;
    }

    private boolean isEnabled() {
        return mAdapter.getState() == BluetoothAdapter.STATE_ON;
    }

    /**
     * Initiate virtual unplug for a HID input device.
     *
     * @param device Remote Bluetooth Device
     * @return false on immediate error, true otherwise
     */
    @Hide
    @RequiresLegacyBluetoothAdminPermission
    @RequiresBluetoothConnectPermission
    @RequiresPermission(BLUETOOTH_CONNECT)
    public boolean virtualUnplug(BluetoothDevice device) {
        log("virtualUnplug(" + device + ")");
        final IBluetoothHidHost service = getService();
        if (service == null) {
            Log.w(TAG, "Proxy not attached to service");
            log(Log.getStackTraceString(new Throwable()));
        } else if (isEnabled() && isValidDevice(device)) {
            try {
                return service.virtualUnplug(device, mAttributionSource);
            } catch (RemoteException e) {
                Log.e(TAG, e.toString() + "\n" + Log.getStackTraceString(new Throwable()));
            }
        }
        return false;
    }

    /**
     * Send Get_Protocol_Mode command to the connected HID input device.
     *
     * @param device Remote Bluetooth Device
     * @return false on immediate error, true otherwise
     */
    @Hide
    @RequiresLegacyBluetoothAdminPermission
    @RequiresBluetoothConnectPermission
    @RequiresPermission(BLUETOOTH_CONNECT)
    public boolean getProtocolMode(BluetoothDevice device) {
        final IBluetoothHidHost service = getService();
        if (service == null) {
            Log.w(TAG, "Proxy not attached to service");
            log(Log.getStackTraceString(new Throwable()));
        } else if (isEnabled() && isValidDevice(device)) {
            try {
                return service.getProtocolMode(device, mAttributionSource);
            } catch (RemoteException e) {
                Log.e(TAG, e.toString() + "\n" + Log.getStackTraceString(new Throwable()));
            }
        }
        return false;
    }

    /**
     * Send Set_Protocol_Mode command to the connected HID input device.
     *
     * @param device Remote Bluetooth Device
     * @return false on immediate error, true otherwise
     */
    @Hide
    @RequiresLegacyBluetoothAdminPermission
    @RequiresBluetoothConnectPermission
    @RequiresPermission(BLUETOOTH_CONNECT)
    public boolean setProtocolMode(BluetoothDevice device, int protocolMode) {
        log("setProtocolMode(" + device + ")");
        final IBluetoothHidHost service = getService();
        if (service == null) {
            Log.w(TAG, "Proxy not attached to service");
            log(Log.getStackTraceString(new Throwable()));
        } else if (isEnabled() && isValidDevice(device)) {
            try {
                return service.setProtocolMode(device, protocolMode, mAttributionSource);
            } catch (RemoteException e) {
                Log.e(TAG, e.toString() + "\n" + Log.getStackTraceString(new Throwable()));
            }
        }
        return false;
    }

    /**
     * Send Get_Report command to the connected HID input device.
     *
     * @param device Remote Bluetooth Device
     * @param reportType Report type
     * @param reportId Report ID
     * @param bufferSize Report receiving buffer size
     * @return false on immediate error, true otherwise
     */
    @Hide
    @RequiresLegacyBluetoothAdminPermission
    @RequiresBluetoothConnectPermission
    @RequiresPermission(BLUETOOTH_CONNECT)
    public boolean getReport(
            BluetoothDevice device, byte reportType, byte reportId, int bufferSize) {
        if (VDBG) {
            log(
                    "getReport("
                            + device
                            + "), reportType="
                            + reportType
                            + " reportId="
                            + reportId
                            + "bufferSize="
                            + bufferSize);
        }
        final IBluetoothHidHost service = getService();
        if (service == null) {
            Log.w(TAG, "Proxy not attached to service");
            log(Log.getStackTraceString(new Throwable()));
        } else if (isEnabled() && isValidDevice(device)) {
            try {
                return service.getReport(
                        device, reportType, reportId, bufferSize, mAttributionSource);
            } catch (RemoteException e) {
                Log.e(TAG, e.toString() + "\n" + Log.getStackTraceString(new Throwable()));
            }
        }
        return false;
    }

    /**
     * Send Set_Report command to the connected HID input device.
     *
     * @param device Remote Bluetooth Device
     * @param reportType Report type
     * @param report Report receiving buffer size
     * @return false on immediate error, true otherwise
     */
    @Hide
    @RequiresLegacyBluetoothAdminPermission
    @RequiresBluetoothConnectPermission
    @RequiresPermission(BLUETOOTH_CONNECT)
    public boolean setReport(BluetoothDevice device, byte reportType, String report) {
        if (VDBG) log("setReport(" + device + "), reportType=" + reportType + " report=" + report);
        final IBluetoothHidHost service = getService();
        if (service == null) {
            Log.w(TAG, "Proxy not attached to service");
            log(Log.getStackTraceString(new Throwable()));
        } else if (isEnabled() && isValidDevice(device)) {
            try {
                return service.setReport(device, reportType, report, mAttributionSource);
            } catch (RemoteException e) {
                Log.e(TAG, e.toString() + "\n" + Log.getStackTraceString(new Throwable()));
            }
        }
        return false;
    }

    /**
     * Send Send_Data command to the connected HID input device.
     *
     * @param device Remote Bluetooth Device
     * @param report Report to send
     * @return false on immediate error, true otherwise
     */
    @Hide
    @RequiresLegacyBluetoothAdminPermission
    @RequiresBluetoothConnectPermission
    @RequiresPermission(BLUETOOTH_CONNECT)
    public boolean sendData(BluetoothDevice device, String report) {
        log("sendData(" + device + "), report=" + report);
        final IBluetoothHidHost service = getService();
        if (service == null) {
            Log.w(TAG, "Proxy not attached to service");
            log(Log.getStackTraceString(new Throwable()));
        } else if (isEnabled() && isValidDevice(device)) {
            try {
                return service.sendData(device, report, mAttributionSource);
            } catch (RemoteException e) {
                Log.e(TAG, e.toString() + "\n" + Log.getStackTraceString(new Throwable()));
            }
        }
        return false;
    }

    /**
     * Send Get_Idle_Time command to the connected HID input device.
     *
     * @param device Remote Bluetooth Device
     * @return false on immediate error, true otherwise
     */
    @Hide
    @RequiresLegacyBluetoothAdminPermission
    @RequiresBluetoothConnectPermission
    @RequiresPermission(BLUETOOTH_CONNECT)
    public boolean getIdleTime(BluetoothDevice device) {
        log("getIdleTime(" + device + ")");
        final IBluetoothHidHost service = getService();
        if (service == null) {
            Log.w(TAG, "Proxy not attached to service");
            log(Log.getStackTraceString(new Throwable()));
        } else if (isEnabled() && isValidDevice(device)) {
            try {
                return service.getIdleTime(device, mAttributionSource);
            } catch (RemoteException e) {
                Log.e(TAG, e.toString() + "\n" + Log.getStackTraceString(new Throwable()));
            }
        }
        return false;
    }

    /**
     * Send Set_Idle_Time command to the connected HID input device.
     *
     * @param device Remote Bluetooth Device
     * @param idleTime Idle time to be set on HID Device
     * @return false on immediate error, true otherwise
     */
    @Hide
    @RequiresLegacyBluetoothAdminPermission
    @RequiresBluetoothConnectPermission
    @RequiresPermission(BLUETOOTH_CONNECT)
    public boolean setIdleTime(BluetoothDevice device, byte idleTime) {
        log("setIdleTime(" + device + "), idleTime=" + idleTime);
        final IBluetoothHidHost service = getService();
        if (service == null) {
            Log.w(TAG, "Proxy not attached to service");
            log(Log.getStackTraceString(new Throwable()));
        } else if (isEnabled() && isValidDevice(device)) {
            try {
                return service.setIdleTime(device, idleTime, mAttributionSource);
            } catch (RemoteException e) {
                Log.e(TAG, e.toString() + "\n" + Log.getStackTraceString(new Throwable()));
            }
        }
        return false;
    }

    private static void log(String msg) {
        Log.d(TAG, msg);
    }
}
