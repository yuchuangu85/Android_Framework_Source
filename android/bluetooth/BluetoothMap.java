/*
 * Copyright (C) 2008 The Android Open Source Project
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
import android.compat.annotation.UnsupportedAppUsage;
import android.content.AttributionSource;
import android.content.Context;
import android.os.Build;
import android.os.IBinder;
import android.os.IpcDataCache;
import android.os.RemoteException;
import android.util.CloseGuard;
import android.util.Log;
import android.util.Pair;

import java.util.Collections;
import java.util.List;

/** This class provides the APIs to control the Bluetooth MAP Profile. */
@Hide
@SystemApi
public final class BluetoothMap implements BluetoothProfile, AutoCloseable {
    private static final String TAG = BluetoothMap.class.getSimpleName();

    private static final boolean VDBG = Log.isLoggable("bluetooth", Log.VERBOSE);

    private final CloseGuard mCloseGuard;

    @Hide
    @SuppressLint("ActionValue")
    @SystemApi
    @RequiresBluetoothConnectPermission
    @RequiresPermission(BLUETOOTH_CONNECT)
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_CONNECTION_STATE_CHANGED =
            "android.bluetooth.map.profile.action.CONNECTION_STATE_CHANGED";

    /** There was an error trying to obtain the state */
    @Hide public static final int STATE_ERROR = -1;

    @Hide public static final int RESULT_FAILURE = 0;

    @Hide public static final int RESULT_SUCCESS = 1;

    /** Connection canceled before completion. */
    @Hide public static final int RESULT_CANCELED = 2;

    private final BluetoothAdapter mAdapter;
    private final AttributionSource mAttributionSource;

    private IBluetoothMap mService;

    /** Create a BluetoothMap proxy object. */
    /* package */ BluetoothMap(Context context, BluetoothAdapter adapter) {
        Log.d(TAG, "Create BluetoothMap proxy object");
        mAdapter = adapter;
        mAttributionSource = adapter.getAttributionSource();
        mService = null;
        mCloseGuard = new CloseGuard();
        mCloseGuard.open("close");
    }

    @Override
    @SuppressWarnings("Finalize") // TODO(b/314811467)
    protected void finalize() {
        if (mCloseGuard != null) {
            mCloseGuard.warnIfOpen();
        }
        close();
    }

    /**
     * Close the connection to the backing service. Other public functions of BluetoothMap will
     * return default error results once close() has been called. Multiple invocations of close()
     * are ok.
     */
    @Hide
    @SystemApi
    @Override
    public void close() {
        if (VDBG) log("close()");
        mAdapter.closeProfileProxy(this);
    }

    @Hide
    @Override
    @RequiresNoPermission
    public void onServiceConnected(IBinder service) {
        mService = IBluetoothMap.Stub.asInterface(service);
    }

    @Hide
    @Override
    @RequiresNoPermission
    public void onServiceDisconnected() {
        mService = null;
    }

    private IBluetoothMap getService() {
        return mService;
    }

    @Hide
    @Override
    @RequiresNoPermission
    public BluetoothAdapter getAdapter() {
        return mAdapter;
    }

    /**
     * Get the current state of the BluetoothMap service.
     *
     * @return One of the STATE_ return codes, or STATE_ERROR if this proxy object is currently not
     *     connected to the Map service.
     */
    @Hide
    @RequiresBluetoothConnectPermission
    @RequiresPermission(BLUETOOTH_CONNECT)
    public int getState() {
        if (VDBG) log("getState()");
        final IBluetoothMap service = getService();
        if (service == null) {
            Log.w(TAG, "Proxy not attached to service");
            log(Log.getStackTraceString(new Throwable()));
        } else if (isEnabled()) {
            try {
                return service.getState(mAttributionSource);
            } catch (RemoteException e) {
                Log.e(TAG, e.toString() + "\n" + Log.getStackTraceString(new Throwable()));
            }
        }
        return BluetoothMap.STATE_ERROR;
    }

    /**
     * Get the currently connected remote Bluetooth device (PCE).
     *
     * @return The remote Bluetooth device, or null if not in connected or connecting state, or if
     *     this proxy object is not connected to the Map service.
     */
    @Hide
    @RequiresBluetoothConnectPermission
    @RequiresPermission(BLUETOOTH_CONNECT)
    public BluetoothDevice getClient() {
        if (VDBG) log("getClient()");
        final IBluetoothMap service = getService();
        if (service == null) {
            Log.w(TAG, "Proxy not attached to service");
            log(Log.getStackTraceString(new Throwable()));
        } else if (isEnabled()) {
            try {
                return Attributable.setAttributionSource(
                        service.getClient(mAttributionSource), mAttributionSource);
            } catch (RemoteException e) {
                Log.e(TAG, e.toString() + "\n" + Log.getStackTraceString(new Throwable()));
            }
        }
        return null;
    }

    /**
     * Returns true if the specified Bluetooth device is connected. Returns false if not connected,
     * or if this proxy object is not currently connected to the Map service.
     */
    @Hide
    @RequiresBluetoothConnectPermission
    @RequiresPermission(BLUETOOTH_CONNECT)
    public boolean isConnected(BluetoothDevice device) {
        if (VDBG) log("isConnected(" + device + ")");
        final IBluetoothMap service = getService();
        if (service == null) {
            Log.w(TAG, "Proxy not attached to service");
            log(Log.getStackTraceString(new Throwable()));
        } else if (isEnabled() && isValidDevice(device)) {
            try {
                return service.isConnected(device, mAttributionSource);
            } catch (RemoteException e) {
                Log.e(TAG, e.toString() + "\n" + Log.getStackTraceString(new Throwable()));
            }
        }
        return false;
    }

    /** Initiate connection. Initiation of outgoing connections is not supported for MAP server. */
    @Hide
    @RequiresNoPermission
    public boolean connect(BluetoothDevice device) {
        log("connect(" + device + ") not supported for MAPS");
        return false;
    }

    /**
     * Initiate disconnect.
     *
     * @param device Remote Bluetooth Device
     * @return false on error, true otherwise
     */
    @Hide
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    @RequiresBluetoothConnectPermission
    @RequiresPermission(BLUETOOTH_CONNECT)
    public boolean disconnect(BluetoothDevice device) {
        log("disconnect(" + device + ")");
        final IBluetoothMap service = getService();
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

    /**
     * Check class bits for possible Map support. This is a simple heuristic that tries to guess if
     * a device with the given class bits might support Map. It is not accurate for all devices. It
     * tries to err on the side of false positives.
     *
     * @return True if this device might support Map.
     */
    @Hide
    public static boolean doesClassMatchSink(BluetoothClass btClass) {
        // TODO optimize the rule
        return switch (btClass.getDeviceClass()) {
            case BluetoothClass.Device.COMPUTER_DESKTOP,
                    BluetoothClass.Device.COMPUTER_LAPTOP,
                    BluetoothClass.Device.COMPUTER_SERVER,
                    BluetoothClass.Device.COMPUTER_UNCATEGORIZED ->
                    true;
            default -> false;
        };
    }

    /**
     * Get the list of connected devices. Currently at most one.
     *
     * @return list of connected devices
     */
    @Hide
    @SystemApi
    @RequiresBluetoothConnectPermission
    @RequiresPermission(
            allOf = {
                BLUETOOTH_CONNECT,
                BLUETOOTH_PRIVILEGED,
            })
    public @NonNull List<BluetoothDevice> getConnectedDevices() {
        log("getConnectedDevices()");
        final IBluetoothMap service = getService();
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

    /**
     * Get the list of devices matching specified states. Currently at most one.
     *
     * @return list of matching devices
     */
    @Hide
    @RequiresBluetoothConnectPermission
    @RequiresPermission(BLUETOOTH_CONNECT)
    public List<BluetoothDevice> getDevicesMatchingConnectionStates(int[] states) {
        log("getDevicesMatchingStates()");
        final IBluetoothMap service = getService();
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

    /**
     * There are several instances of IpcDataCache used in this class. BluetoothCache wraps up the
     * common code. All caches are created with a maximum of eight entries, and the key is in the
     * bluetooth module. The name is set to the api.
     */
    private static class BluetoothCache<Q, R> extends IpcDataCache<Q, R> {
        BluetoothCache(String api, IpcDataCache.QueryHandler query) {
            super(8, IpcDataCache.MODULE_BLUETOOTH, api, api, query);
        }
    }

    @Hide
    @RequiresNoPermission
    public void disableBluetoothGetConnectionStateCache() {
        sBluetoothConnectionCache.disableForCurrentProcess();
    }

    @Hide
    public static void invalidateBluetoothGetConnectionStateCache() {
        invalidateCache(GET_CONNECTION_STATE_API);
    }

    /**
     * Invalidate a bluetooth cache. This method is just a short-hand wrapper that enforces the
     * bluetooth module.
     */
    private static void invalidateCache(@NonNull String api) {
        IpcDataCache.invalidateCache(IpcDataCache.MODULE_BLUETOOTH, api);
    }

    private static final IpcDataCache.QueryHandler<
                    Pair<IBinder, Pair<AttributionSource, BluetoothDevice>>, Integer>
            sBluetoothConnectionQuery =
                    new IpcDataCache.QueryHandler<>() {
                        @RequiresBluetoothConnectPermission
                        @RequiresPermission(BLUETOOTH_CONNECT)
                        @Override
                        public Integer apply(
                                Pair<IBinder, Pair<AttributionSource, BluetoothDevice>> pairQuery) {
                            IBluetoothMap service = IBluetoothMap.Stub.asInterface(pairQuery.first);
                            AttributionSource source = pairQuery.second.first;
                            BluetoothDevice device = pairQuery.second.second;
                            log(
                                    "getConnectionState("
                                            + device.getAnonymizedAddress()
                                            + ") uncached");
                            try {
                                return service.getConnectionState(device, source);
                            } catch (RemoteException e) {
                                throw new RuntimeException(e);
                            }
                        }
                    };

    private static final String GET_CONNECTION_STATE_API = "BluetoothMap_getConnectionState";

    private static final BluetoothCache<
                    Pair<IBinder, Pair<AttributionSource, BluetoothDevice>>, Integer>
            sBluetoothConnectionCache =
                    new BluetoothCache<>(GET_CONNECTION_STATE_API, sBluetoothConnectionQuery);

    /**
     * Get connection state of device
     *
     * @return device connection state
     */
    @Hide
    @RequiresBluetoothConnectPermission
    @RequiresPermission(BLUETOOTH_CONNECT)
    public int getConnectionState(BluetoothDevice device) {
        final IBluetoothMap service = getService();
        if (service == null) {
            Log.w(TAG, "BT not enabled. Cannot get connection state");
            log(Log.getStackTraceString(new Throwable()));
        } else if (isEnabled() && isValidDevice(device)) {
            try {
                return sBluetoothConnectionCache.query(
                        new Pair<>(service.asBinder(), new Pair<>(mAttributionSource, device)));
            } catch (RuntimeException e) {
                if (!(e.getCause() instanceof RemoteException)) {
                    throw e;
                }
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
        final IBluetoothMap service = getService();
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
    @RequiresPermission(
            allOf = {
                BLUETOOTH_CONNECT,
                BLUETOOTH_PRIVILEGED,
            })
    public @ConnectionPolicy int getConnectionPolicy(@NonNull BluetoothDevice device) {
        if (VDBG) log("getConnectionPolicy(" + device + ")");
        final IBluetoothMap service = getService();
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

    private static void log(String msg) {
        Log.d(TAG, msg);
    }

    private boolean isEnabled() {
        return mAdapter.isEnabled();
    }
}
