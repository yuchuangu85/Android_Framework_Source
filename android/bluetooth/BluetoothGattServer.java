/*
 * Copyright (C) 2013 The Android Open Source Project
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
import static android.bluetooth.BluetoothProfile.STATE_CONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTED;

import static java.util.Objects.requireNonNull;

import android.annotation.FlaggedApi;
import android.annotation.Hide;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresNoPermission;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.bluetooth.annotations.RequiresBluetoothConnectPermission;
import android.bluetooth.annotations.RequiresLegacyBluetoothPermission;
import android.content.AttributionSource;
import android.os.IBinder;
import android.os.ParcelUuid;
import android.os.RemoteException;
import android.util.Log;

import com.android.bluetooth.flags.Flags;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Public API for the Bluetooth GATT Profile server role.
 *
 * <p>This class provides Bluetooth GATT server role functionality, allowing applications to create
 * Bluetooth Smart services and characteristics.
 *
 * <p>BluetoothGattServer is a proxy object for controlling the Bluetooth Service via IPC. Use
 * {@link BluetoothManager#openGattServer} to get an instance of this class.
 */
public final class BluetoothGattServer implements BluetoothProfile {
    private static final String TAG = BluetoothGattServer.class.getSimpleName();

    private static final boolean VDBG = Log.isLoggable("bluetooth", Log.VERBOSE);

    private final IBluetoothGatt mService;
    private final BluetoothAdapter mAdapter;
    private final AttributionSource mAttributionSource;

    private BluetoothGattServerCallback mCallback;

    private final Object mServerIfLock = new Object();
    private boolean mServerRegistered;
    private final int mTransport;
    private BluetoothGattService mPendingService;
    private final List<BluetoothGattService> mServices;

    private static final int CALLBACK_REG_TIMEOUT = 10000;

    /**
     * Bluetooth GATT server callbacks. Overrides the default BluetoothGattServerCallback
     * implementation.
     */
    private final IBluetoothGattServerCallback mBluetoothGattServerCallback =
            new GattServerCallback();

    private class GattServerCallback extends IBluetoothGattServerCallback.Stub {
        /** Application interface registered - app is ready to go */
        @Hide
        @Override
        @RequiresNoPermission // Callback to app
        public void onServerRegistered(int status) {
            Log.d(TAG, "onServerRegistered(" + status + ")");
            synchronized (mServerIfLock) {
                if (mCallback != null) {
                    mServerRegistered = true;
                    mServerIfLock.notify();
                } else {
                    // registration timeout
                    Log.e(TAG, "onServerRegistered(): mCallback is null");
                }
            }
        }

        /** Server connection state changed */
        @Hide
        @Override
        @RequiresNoPermission // Callback to app
        public void onServerConnectionState(int status, boolean connected, BluetoothDevice device) {
            Log.d(
                    TAG,
                    ("onServerConnectionState(): status=" + status)
                            + (", connected=" + connected)
                            + (", device=" + device));

            Attributable.setAttributionSource(device, mAttributionSource);
            try {
                mCallback.onConnectionStateChange(
                        device, status, connected ? STATE_CONNECTED : STATE_DISCONNECTED);
            } catch (Exception ex) {
                Log.w(TAG, "onServerConnectionState(): Unhandled exception in callback", ex);
            }
        }

        /** Service has been added */
        @Hide
        @Override
        @RequiresNoPermission // Callback to app
        public void onServiceAdded(int status, BluetoothGattService service) {
            Log.d(
                    TAG,
                    ("onServiceAdded(): status=" + status)
                            + (", handle=" + service.getInstanceId())
                            + (", UUID=" + service.getUuid()));

            if (mPendingService == null) {
                return;
            }

            BluetoothGattService tmp = mPendingService;
            mPendingService = null;

            // Rewrite newly assigned handles to existing service.
            tmp.setInstanceId(service.getInstanceId());
            List<BluetoothGattCharacteristic> temp_chars = tmp.getCharacteristics();
            List<BluetoothGattCharacteristic> svc_chars = service.getCharacteristics();
            for (int i = 0; i < svc_chars.size(); i++) {
                BluetoothGattCharacteristic temp_char = temp_chars.get(i);
                BluetoothGattCharacteristic svc_char = svc_chars.get(i);

                temp_char.setInstanceId(svc_char.getInstanceId());

                List<BluetoothGattDescriptor> temp_descs = temp_char.getDescriptors();
                List<BluetoothGattDescriptor> svc_descs = svc_char.getDescriptors();
                for (int j = 0; j < svc_descs.size(); j++) {
                    temp_descs.get(j).setInstanceId(svc_descs.get(j).getInstanceId());
                }
            }

            mServices.add(tmp);

            try {
                mCallback.onServiceAdded((int) status, tmp);
            } catch (Exception ex) {
                Log.w(TAG, "onServiceAdded(): Unhandled exception in callback", ex);
            }
        }

        /** Remote client characteristic read request. */
        @Hide
        @Override
        @RequiresNoPermission // Callback to app
        public void onCharacteristicReadRequest(
                BluetoothDevice device, int transId, int offset, boolean isLong, int handle) {
            if (VDBG) Log.d(TAG, "onCharacteristicReadRequest(): handle=" + handle);

            BluetoothGattCharacteristic characteristic = getCharacteristicByHandle(handle);
            if (characteristic == null) {
                Log.w(TAG, "onCharacteristicReadRequest(): No char for handle " + handle);
                return;
            }

            Attributable.setAttributionSource(device, mAttributionSource);
            try {
                mCallback.onCharacteristicReadRequest(device, transId, offset, characteristic);
            } catch (Exception ex) {
                Log.w(TAG, "onCharacteristicReadRequest(): Unhandled exception in callback", ex);
            }
        }

        /** Remote client descriptor read request. */
        @Hide
        @Override
        @RequiresNoPermission // Callback to app
        public void onDescriptorReadRequest(
                BluetoothDevice device, int transId, int offset, boolean isLong, int handle) {
            if (VDBG) Log.d(TAG, "onCharacteristicReadRequest(): handle=" + handle);

            BluetoothGattDescriptor descriptor = getDescriptorByHandle(handle);
            if (descriptor == null) {
                Log.w(TAG, "onDescriptorReadRequest(): No desc for handle " + handle);
                return;
            }

            Attributable.setAttributionSource(device, mAttributionSource);
            try {
                mCallback.onDescriptorReadRequest(device, transId, offset, descriptor);
            } catch (Exception ex) {
                Log.w(TAG, "onDescriptorReadRequest(): Unhandled exception in callback", ex);
            }
        }

        /** Remote client characteristic write request. */
        @Hide
        @Override
        @RequiresNoPermission // Callback to app
        public void onCharacteristicWriteRequest(
                BluetoothDevice device,
                int transId,
                int offset,
                int length,
                boolean isPrep,
                boolean needRsp,
                int handle,
                byte[] value) {
            if (VDBG) Log.d(TAG, "onCharacteristicWriteRequest(): handle=" + handle);

            BluetoothGattCharacteristic characteristic = getCharacteristicByHandle(handle);
            if (characteristic == null) {
                Log.w(TAG, "onCharacteristicWriteRequest(): No char for handle " + handle);
                return;
            }

            Attributable.setAttributionSource(device, mAttributionSource);
            try {
                mCallback.onCharacteristicWriteRequest(
                        device, transId, characteristic, isPrep, needRsp, offset, value);
            } catch (Exception ex) {
                Log.w(TAG, "onCharacteristicWriteRequest(): Unhandled exception in callback", ex);
            }
        }

        /** Remote client descriptor write request. */
        @Hide
        @Override
        @RequiresNoPermission // Callback to app
        public void onDescriptorWriteRequest(
                BluetoothDevice device,
                int transId,
                int offset,
                int length,
                boolean isPrep,
                boolean needRsp,
                int handle,
                byte[] value) {
            if (VDBG) Log.d(TAG, "onDescriptorWriteRequest(): handle=" + handle);

            BluetoothGattDescriptor descriptor = getDescriptorByHandle(handle);
            if (descriptor == null) {
                Log.w(TAG, "onDescriptorWriteRequest(): No desc for handle " + handle);
                return;
            }

            Attributable.setAttributionSource(device, mAttributionSource);
            try {
                mCallback.onDescriptorWriteRequest(
                        device, transId, descriptor, isPrep, needRsp, offset, value);
            } catch (Exception ex) {
                Log.w(TAG, "onDescriptorWriteRequest(): Unhandled exception in callback", ex);
            }
        }

        /** Execute pending writes. */
        @Hide
        @Override
        @RequiresNoPermission // Callback to app
        public void onExecuteWrite(BluetoothDevice device, int transId, boolean execWrite) {
            Log.d(
                    TAG,
                    ("onExecuteWrite(): device=" + device)
                            + (", transId=" + transId)
                            + ("execWrite=" + execWrite));

            Attributable.setAttributionSource(device, mAttributionSource);
            try {
                mCallback.onExecuteWrite(device, transId, execWrite);
            } catch (Exception ex) {
                Log.w(TAG, "onExecuteWrite(): Unhandled exception in callback", ex);
            }
        }

        /** A notification/indication has been sent. */
        @Hide
        @Override
        @RequiresNoPermission // Callback to app
        public void onNotificationSent(BluetoothDevice device, int status) {
            if (VDBG) {
                Log.d(TAG, "onNotificationSent(): device=" + device + ", status=" + status);
            }

            Attributable.setAttributionSource(device, mAttributionSource);
            try {
                mCallback.onNotificationSent(device, status);
            } catch (Exception ex) {
                Log.w(TAG, "onNotificationSent(): Unhandled exception: " + ex);
            }
        }

        /** The MTU for a connection has changed */
        @Hide
        @Override
        @RequiresNoPermission // Callback to app
        public void onMtuChanged(BluetoothDevice device, int mtu) {
            Log.d(TAG, "onMtuChanged(): device=" + device + ", mtu=" + mtu);

            Attributable.setAttributionSource(device, mAttributionSource);
            try {
                mCallback.onMtuChanged(device, mtu);
            } catch (Exception ex) {
                Log.w(TAG, "onMtuChanged(): Unhandled exception: " + ex);
            }
        }

        /** The PHY for a connection was updated */
        @Hide
        @Override
        @RequiresNoPermission // Callback to app
        public void onPhyUpdate(BluetoothDevice device, int txPhy, int rxPhy, int status) {
            Log.d(TAG, "onPhyUpdate(): device=" + device + ", txPHy=" + txPhy + ", rxPHy=" + rxPhy);

            Attributable.setAttributionSource(device, mAttributionSource);
            try {
                mCallback.onPhyUpdate(device, txPhy, rxPhy, status);
            } catch (Exception ex) {
                Log.w(TAG, "onPhyUpdate(): Unhandled exception: " + ex);
            }
        }

        /** The PHY for a connection was read */
        @Hide
        @Override
        @RequiresNoPermission // Callback to app
        public void onPhyRead(BluetoothDevice device, int txPhy, int rxPhy, int status) {
            Log.d(TAG, "onPhyUpdate(): device=" + device + ", txPHy=" + txPhy + ", rxPHy=" + rxPhy);

            Attributable.setAttributionSource(device, mAttributionSource);
            try {
                mCallback.onPhyRead(device, txPhy, rxPhy, status);
            } catch (Exception ex) {
                Log.w(TAG, "onPhyUpdate(): Unhandled exception: " + ex);
            }
        }

        /** Callback invoked when the given connection is updated */
        @Hide
        @Override
        @RequiresNoPermission // Callback to app
        public void onConnectionUpdated(
                BluetoothDevice device, int interval, int latency, int timeout, int status) {
            Log.d(
                    TAG,
                    ("onConnectionUpdated(): device=" + device)
                            + (" interval=" + interval)
                            + (" latency=" + latency)
                            + (" timeout=" + timeout)
                            + (" status=" + status));

            Attributable.setAttributionSource(device, mAttributionSource);
            try {
                mCallback.onConnectionUpdated(device, interval, latency, timeout, status);
            } catch (Exception ex) {
                Log.w(TAG, "onConnectionUpdated(): Unhandled exception: " + ex);
            }
        }

        /** Callback invoked when the given connection's subrating is changed */
        @Hide
        @Override
        @RequiresNoPermission // Callback to app
        public void onSubrateChange(BluetoothDevice device, int subrateMode, int status) {
            Log.d(
                    TAG,
                    ("onSubrateChange(): device=" + device)
                            + (", subrateMode=" + subrateMode)
                            + (", status=" + status));

            Attributable.setAttributionSource(device, mAttributionSource);
            try {
                mCallback.onSubrateChange(device, subrateMode, status);
            } catch (Exception ex) {
                Log.w(TAG, "onSubrateChange(): Unhandled exception: " + ex);
            }
        }

        /** Callback indicating whether GATT characteristics offload has been added. */
        @Hide
        @RequiresNoPermission
        @FlaggedApi(Flags.FLAG_GATT_OFFLOAD_API)
        /* package */ void onCharacteristicsOffloaded(
                BluetoothDevice device, GattOffloadSession session, int status) {
            Log.d(
                    TAG,
                    ("onCharacteristicsOffloaded(): device=" + device)
                            + (", session=" + session)
                            + (", status=" + status));

            Attributable.setAttributionSource(device, mAttributionSource);
            try {
                mCallback.onCharacteristicsOffloaded(device, session, status);
            } catch (Exception ex) {
                Log.w(TAG, "onCharacteristicsOffloaded(): Unhandled exception: " + ex);
            }
        }

        /** Callback indicating whether GATT characteristics offload has been removed. */
        @Hide
        @Override
        @RequiresNoPermission
        @FlaggedApi(Flags.FLAG_GATT_OFFLOAD_API)
        public void onCharacteristicsUnoffloaded(
                BluetoothDevice device, int sessionId, int status) {
            Log.d(
                    TAG,
                    ("onCharacteristicsUnoffloaded(): device=" + device)
                            + (", sessionId=" + sessionId)
                            + (", status=" + status));

            Attributable.setAttributionSource(device, mAttributionSource);
            try {
                mCallback.onCharacteristicsUnoffloaded(device, sessionId, status);
            } catch (Exception ex) {
                Log.w(TAG, "onCharacteristicsUnoffloaded(): Unhandled exception: " + ex);
            }
        }
    }

    /** Create a BluetoothGattServer proxy object. */
    /* package */ BluetoothGattServer(
            IBluetoothGatt iGatt, int transport, BluetoothAdapter adapter) {
        mService = iGatt;
        mAdapter = adapter;
        mAttributionSource = adapter.getAttributionSource();
        mCallback = null;
        mTransport = transport;
        mServices = new ArrayList<>();
    }

    /** Get the identifier of the BluetoothGattServer */
    @Hide
    @RequiresNoPermission
    public IBluetoothGattServerCallback getCallbackId() {
        return mBluetoothGattServerCallback;
    }

    /** Returns a characteristic with given handle. */
    @Hide
    /*package*/ BluetoothGattCharacteristic getCharacteristicByHandle(int handle) {
        for (BluetoothGattService svc : mServices) {
            for (BluetoothGattCharacteristic charac : svc.getCharacteristics()) {
                if (charac.getInstanceId() == handle) {
                    return charac;
                }
            }
        }
        return null;
    }

    /** Returns a descriptor with given handle. */
    @Hide
    /*package*/ BluetoothGattDescriptor getDescriptorByHandle(int handle) {
        for (BluetoothGattService svc : mServices) {
            for (BluetoothGattCharacteristic charac : svc.getCharacteristics()) {
                for (BluetoothGattDescriptor desc : charac.getDescriptors()) {
                    if (desc.getInstanceId() == handle) {
                        return desc;
                    }
                }
            }
        }
        return null;
    }

    @Hide
    @Override
    @RequiresNoPermission
    public void onServiceConnected(IBinder service) {}

    @Hide
    @Override
    @RequiresNoPermission
    public void onServiceDisconnected() {}

    @Hide
    @Override
    @RequiresNoPermission
    public BluetoothAdapter getAdapter() {
        return mAdapter;
    }

    /**
     * Close this GATT server instance.
     *
     * <p>Application should call this method as early as possible after it is done with this GATT
     * server.
     */
    @RequiresBluetoothConnectPermission
    @RequiresPermission(BLUETOOTH_CONNECT)
    public void close() {
        Log.d(TAG, "close()");
        unregisterCallback();
    }

    /**
     * Register an application callback to start using GattServer.
     *
     * <p>This is an asynchronous call. The callback is used to notify success or failure if the
     * function returns true.
     *
     * @param callback GATT callback handler that will receive asynchronous callbacks.
     * @return true, the callback will be called to notify success or failure, false on immediate
     *     error
     */
    @RequiresLegacyBluetoothPermission
    @RequiresBluetoothConnectPermission
    @RequiresPermission(BLUETOOTH_CONNECT)
    /*package*/ boolean registerCallback(BluetoothGattServerCallback callback) {
        return registerCallback(callback, false);
    }

    /**
     * Register an application callback to start using GattServer.
     *
     * <p>This is an asynchronous call. The callback is used to notify success or failure if the
     * function returns true.
     *
     * @param callback GATT callback handler that will receive asynchronous callbacks.
     * @param eattSupport indicates if server can use eatt
     * @return true, the callback will be called to notify success or failure, false on immediate
     *     error
     */
    @Hide
    @RequiresLegacyBluetoothPermission
    @RequiresBluetoothConnectPermission
    @RequiresPermission(BLUETOOTH_CONNECT)
    @SuppressWarnings("WaitNotInLoop") // TODO(b/314811467)
    /*package*/ boolean registerCallback(
            BluetoothGattServerCallback callback, boolean eattSupport) {
        Log.d(TAG, "registerCallback()");
        if (mService == null) {
            Log.e(TAG, "GATT service not available");
            return false;
        }
        UUID uuid = UUID.randomUUID();
        Log.d(TAG, "registerCallback(): UUID=" + uuid);

        synchronized (mServerIfLock) {
            if (mCallback != null) {
                Log.e(TAG, "App can register callback only once");
                return false;
            }

            mCallback = callback;
            try {
                mService.registerServer(
                        new ParcelUuid(uuid),
                        mBluetoothGattServerCallback,
                        eattSupport,
                        mTransport,
                        mAttributionSource);
            } catch (RemoteException e) {
                Log.e(TAG, "", e);
                mCallback = null;
                return false;
            }

            try {
                mServerIfLock.wait(CALLBACK_REG_TIMEOUT);
            } catch (InterruptedException e) {
                Log.e(TAG, "" + e);
                mCallback = null;
            }

            if (!mServerRegistered) {
                mCallback = null;
                return false;
            } else {
                return true;
            }
        }
    }

    /** Unregister the current application and callbacks. */
    @RequiresBluetoothConnectPermission
    @RequiresPermission(BLUETOOTH_CONNECT)
    private void unregisterCallback() {
        Log.d(TAG, "unregisterCallback()");
        if (mService == null || !mServerRegistered) return;

        try {
            mCallback = null;
            mService.unregisterServer(mBluetoothGattServerCallback, mAttributionSource);
            mServerRegistered = false;
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
        }
    }

    /** Returns a service by UUID, instance and type. */
    @Hide
    /*package*/ BluetoothGattService getService(UUID uuid, int instanceId, int type) {
        for (BluetoothGattService svc : mServices) {
            if (svc.getType() == type
                    && svc.getInstanceId() == instanceId
                    && svc.getUuid().equals(uuid)) {
                return svc;
            }
        }
        return null;
    }

    /**
     * Initiate a connection to a Bluetooth GATT capable device.
     *
     * <p>The connection may not be established right away, but will be completed when the remote
     * device is available. A {@link BluetoothGattServerCallback#onConnectionStateChange} callback
     * will be invoked when the connection state changes as a result of this function.
     *
     * <p>The autoConnect parameter determines whether to actively connect to the remote device, or
     * rather passively scan and finalize the connection when the remote device is in
     * range/available. Generally, the first ever connection to a device should be direct
     * (autoConnect set to false) and subsequent connections to known devices should be invoked with
     * the autoConnect parameter set to true.
     *
     * @param autoConnect Whether to directly connect to the remote device (false) or to
     *     automatically connect as soon as the remote device becomes available (true).
     * @return true, if the connection attempt was initiated successfully
     */
    @RequiresLegacyBluetoothPermission
    @RequiresBluetoothConnectPermission
    @RequiresPermission(BLUETOOTH_CONNECT)
    public boolean connect(BluetoothDevice device, boolean autoConnect) {
        Log.d(TAG, "connect(): device=" + device + ", auto=" + autoConnect);
        if (mService == null || !mServerRegistered) return false;

        try {
            // autoConnect is inverse of "isDirect"
            mService.serverConnect(
                    mBluetoothGattServerCallback,
                    device,
                    device.getAddressType(),
                    !autoConnect,
                    mTransport,
                    mAttributionSource);
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
            return false;
        }

        return true;
    }

    /**
     * Disconnects an established connection, or cancels a connection attempt currently in progress.
     *
     * @param device Remote device
     */
    @RequiresLegacyBluetoothPermission
    @RequiresBluetoothConnectPermission
    @RequiresPermission(BLUETOOTH_CONNECT)
    public void cancelConnection(BluetoothDevice device) {
        Log.d(TAG, "cancelConnection(): device=" + device);
        if (mService == null || !mServerRegistered) return;

        try {
            mService.serverDisconnect(mBluetoothGattServerCallback, device, mAttributionSource);
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
        }
    }

    /**
     * Set the preferred connection PHY for this app. Please note that this is just a
     * recommendation, whether the PHY change will happen depends on other applications preferences,
     * local and remote controller capabilities. Controller can override these settings.
     *
     * <p>{@link BluetoothGattServerCallback#onPhyUpdate} will be triggered as a result of this
     * call, even if no PHY change happens. It is also triggered when remote device updates the PHY.
     *
     * @param device The remote device to send this response to.
     * @param txPhy preferred transmitter PHY.
     * @param rxPhy preferred receiver PHY.
     * @param phyOptions preferred coding to use when transmitting on the LE Coded PHY. Can be one
     *     of {@link BluetoothDevice#PHY_OPTION_NO_PREFERRED}, {@link BluetoothDevice#PHY_OPTION_S2}
     *     or {@link BluetoothDevice#PHY_OPTION_S8}.
     */
    @RequiresBluetoothConnectPermission
    @RequiresPermission(BLUETOOTH_CONNECT)
    public void setPreferredPhy(
            BluetoothDevice device,
            @BluetoothDevice.PhyMask int txPhy,
            @BluetoothDevice.PhyMask int rxPhy,
            int phyOptions) {
        try {
            mService.serverSetPreferredPhy(
                    mBluetoothGattServerCallback,
                    device,
                    txPhy,
                    rxPhy,
                    phyOptions,
                    mAttributionSource);
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
        }
    }

    /**
     * Read the current transmitter PHY and receiver PHY of the connection. The values are returned
     * in {@link BluetoothGattServerCallback#onPhyRead}
     *
     * @param device The remote device to send this response to
     */
    @RequiresBluetoothConnectPermission
    @RequiresPermission(BLUETOOTH_CONNECT)
    public void readPhy(BluetoothDevice device) {
        try {
            mService.serverReadPhy(mBluetoothGattServerCallback, device, mAttributionSource);
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
        }
    }

    /**
     * Send a response to a read or write request to a remote device.
     *
     * <p>This function must be invoked in when a remote read/write request is received by one of
     * these callback methods:
     *
     * <ul>
     *   <li>{@link BluetoothGattServerCallback#onCharacteristicReadRequest}
     *   <li>{@link BluetoothGattServerCallback#onCharacteristicWriteRequest}
     *   <li>{@link BluetoothGattServerCallback#onDescriptorReadRequest}
     *   <li>{@link BluetoothGattServerCallback#onDescriptorWriteRequest}
     * </ul>
     *
     * @param device The remote device to send this response to
     * @param requestId The ID of the request that was received with the callback
     * @param status The status of the request to be sent to the remote devices
     * @param offset Value offset for partial read/write response
     * @param value The value of the attribute that was read/written (optional)
     */
    @RequiresLegacyBluetoothPermission
    @RequiresBluetoothConnectPermission
    @RequiresPermission(BLUETOOTH_CONNECT)
    public boolean sendResponse(
            @NonNull BluetoothDevice device,
            int requestId,
            int status,
            int offset,
            @Nullable byte[] value) {
        requireNonNull(device);
        if (VDBG) Log.d(TAG, "sendResponse(): device=" + device);
        if (mService == null || !mServerRegistered) return false;

        try {
            mService.sendResponse(
                    mBluetoothGattServerCallback,
                    device,
                    requestId,
                    status,
                    offset,
                    value,
                    mAttributionSource);
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
            return false;
        }
        return true;
    }

    /**
     * Send a notification or indication that a local characteristic has been updated.
     *
     * <p>A notification or indication is sent to the remote device to signal that the
     * characteristic has been updated. This function should be invoked for every client that
     * requests notifications/indications by writing to the "Client Configuration" descriptor for
     * the given characteristic.
     *
     * @param device The remote device to receive the notification/indication
     * @param characteristic The local characteristic that has been updated
     * @param confirm true to request confirmation from the client (indication), false to send a
     *     notification
     * @return true, if the notification has been triggered successfully
     * @throws IllegalArgumentException if the characteristic value or service is null
     * @deprecated Use {@link BluetoothGattServer#notifyCharacteristicChanged(BluetoothDevice,
     *     BluetoothGattCharacteristic, boolean, byte[])} as this is not memory safe.
     */
    @Deprecated
    @RequiresLegacyBluetoothPermission
    @RequiresBluetoothConnectPermission
    @RequiresPermission(BLUETOOTH_CONNECT)
    public boolean notifyCharacteristicChanged(
            BluetoothDevice device, BluetoothGattCharacteristic characteristic, boolean confirm) {
        return notifyCharacteristicChanged(
                        device, characteristic, confirm, characteristic.getValue())
                == BluetoothStatusCodes.SUCCESS;
    }

    @Hide
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(
            value = {
                BluetoothStatusCodes.SUCCESS,
                BluetoothStatusCodes.ERROR_MISSING_BLUETOOTH_CONNECT_PERMISSION,
                BluetoothStatusCodes.ERROR_DEVICE_NOT_CONNECTED,
                BluetoothStatusCodes.ERROR_PROFILE_SERVICE_NOT_BOUND,
                BluetoothStatusCodes.ERROR_UNKNOWN
            })
    public @interface NotifyCharacteristicReturnValues {}

    /**
     * Send a notification or indication that a local characteristic has been updated.
     *
     * <p>A notification or indication is sent to the remote device to signal that the
     * characteristic has been updated. This function should be invoked for every client that
     * requests notifications/indications by writing to the "Client Configuration" descriptor for
     * the given characteristic.
     *
     * @param device the remote device to receive the notification/indication
     * @param characteristic the local characteristic that has been updated
     * @param confirm {@code true} to request confirmation from the client (indication) or {@code
     *     false} to send a notification
     * @param value the characteristic value
     * @return whether the notification has been triggered successfully
     * @throws IllegalArgumentException if the device, characteristic, value, or the
     *     characteristic's service is null, or if the value length exceeds the maximum attribute
     *     length of 512 bytes (As defined in GATT max attribute length (Bluetooth Core
     *     Specification 6.1 Volume 3, Part F, section 3.2.9).
     */
    @RequiresLegacyBluetoothPermission
    @RequiresBluetoothConnectPermission
    @RequiresPermission(BLUETOOTH_CONNECT)
    @NotifyCharacteristicReturnValues
    public int notifyCharacteristicChanged(
            @NonNull BluetoothDevice device,
            @NonNull BluetoothGattCharacteristic characteristic,
            boolean confirm,
            @NonNull byte[] value) {
        if (VDBG) Log.d(TAG, "notifyCharacteristicChanged(): device=" + device);
        if (mService == null || !mServerRegistered) {
            return BluetoothStatusCodes.ERROR_PROFILE_SERVICE_NOT_BOUND;
        }

        if (characteristic == null) {
            throw new IllegalArgumentException("Characteristic must not be null");
        }
        if (device == null) {
            throw new IllegalArgumentException("Device must not be null");
        }
        if (value.length > bluetooth.constants.Core.GATT_MAX_ATTR_LEN) {
            throw new IllegalArgumentException(
                    "Notification should not be longer than max length of an attribute value");
        }
        BluetoothGattService service = characteristic.getService();
        if (service == null) {
            throw new IllegalArgumentException("Characteristic must have a non-null service");
        }
        if (value == null) {
            throw new IllegalArgumentException("Characteristic value must not be null");
        }

        try {
            return mService.sendNotification(
                    mBluetoothGattServerCallback,
                    device,
                    characteristic.getInstanceId(),
                    confirm,
                    value,
                    mAttributionSource);
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
            throw e.rethrowAsRuntimeException();
        }
    }

    /**
     * Add a service to the list of services to be hosted.
     *
     * <p>Once a service has been added to the list, the service and its included characteristics
     * will be provided by the local device.
     *
     * <p>If the local device has already exposed services when this function is called, a service
     * update notification will be sent to all clients.
     *
     * <p>The {@link BluetoothGattServerCallback#onServiceAdded} callback will indicate whether this
     * service has been added successfully. Do not add another service before this callback.
     *
     * @param service Service to be added to the list of services provided by this device.
     * @return true, if the request to add service has been initiated
     */
    @RequiresLegacyBluetoothPermission
    @RequiresBluetoothConnectPermission
    @RequiresPermission(BLUETOOTH_CONNECT)
    public boolean addService(BluetoothGattService service) {
        Log.d(TAG, "addService(): service=" + service.getUuid());
        if (mService == null || !mServerRegistered) return false;

        mPendingService = service;

        try {
            mService.addService(mBluetoothGattServerCallback, service, mAttributionSource);
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
            return false;
        }

        return true;
    }

    /**
     * Removes a service from the list of services to be provided.
     *
     * @param service Service to be removed.
     * @return true, if the service has been removed
     */
    @RequiresLegacyBluetoothPermission
    @RequiresBluetoothConnectPermission
    @RequiresPermission(BLUETOOTH_CONNECT)
    public boolean removeService(BluetoothGattService service) {
        Log.d(TAG, "removeService(): service=" + service.getUuid());
        if (mService == null || !mServerRegistered) return false;

        BluetoothGattService intService =
                getService(service.getUuid(), service.getInstanceId(), service.getType());
        if (intService == null) return false;

        try {
            mService.removeService(
                    mBluetoothGattServerCallback, service.getInstanceId(), mAttributionSource);
            mServices.remove(intService);
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
            return false;
        }

        return true;
    }

    /** Remove all services from the list of provided services. */
    @RequiresLegacyBluetoothPermission
    @RequiresBluetoothConnectPermission
    @RequiresPermission(BLUETOOTH_CONNECT)
    public void clearServices() {
        Log.d(TAG, "clearServices()");
        if (mService == null || !mServerRegistered) return;

        try {
            mService.clearServices(mBluetoothGattServerCallback, mAttributionSource);
            mServices.clear();
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
        }
    }

    /**
     * Returns a list of GATT services offered by this device.
     *
     * <p>An application must call {@link #addService} to add a service to the list of services
     * offered by this device.
     *
     * @return List of services. Returns an empty list if no services have been added yet.
     */
    @RequiresLegacyBluetoothPermission
    @RequiresNoPermission
    public List<BluetoothGattService> getServices() {
        return mServices;
    }

    /**
     * Returns a {@link BluetoothGattService} from the list of services offered by this device.
     *
     * <p>If multiple instances of the same service (as identified by UUID) exist, the first
     * instance of the service is returned.
     *
     * @param uuid UUID of the requested service
     * @return BluetoothGattService if supported, or null if the requested service is not offered by
     *     this device.
     */
    @RequiresLegacyBluetoothPermission
    @RequiresNoPermission
    public BluetoothGattService getService(UUID uuid) {
        for (BluetoothGattService service : mServices) {
            if (service.getUuid().equals(uuid)) {
                return service;
            }
        }

        return null;
    }

    /**
     * Initiates an offload of a specified list of {@link BluetoothGattCharacteristic}s for a
     * connected GATT client to an endpoint.
     *
     * <p>This method enables a GATT Server application to delegate the handling of specific
     * attribute handles associated with the provided {@code characteristics} to an endpoint for a
     * given GATT Client {@code device} after a successful connection has been established and
     * services have been added to the GATT server.
     *
     * <p>This is an asynchronous operation. The result of the offload attempt, including the {@link
     * GattOffloadSession} object on success, will be delivered via the {@link
     * BluetoothGattServerCallback#onCharacteristicsOffloaded} callback.
     *
     * <p>The {@code service} parameter must already contain all {@link
     * BluetoothGattCharacteristic}s specified in the {@code characteristics} list. The {@code
     * characteristics} list itself should exclusively contain the {@link
     * BluetoothGattCharacteristic}s intended for offloading.
     *
     * <p>It's important to note that {@link BluetoothGattDescriptor}s associated with the offloaded
     * {@link BluetoothGattCharacteristic}s are NOT offloaded.
     *
     * <p>The current security state of the GATT connection should meet or exceed the permissions
     * required for all {@link BluetoothGattCharacteristic}s being offloaded. If not, the host
     * application will need to perform additional security steps (e.g., pairing) to fulfill these
     * requirements.
     *
     * @param device The {@link BluetoothDevice} representing the GATT Client connected to this
     *     server.
     * @param service The {@link BluetoothGattService} that contains the characteristics to be
     *     offloaded. This service object should correspond to a service registered with the GATT
     *     server.
     * @param characteristics A list of {@link BluetoothGattCharacteristic}s from the specified
     *     {@code service} that are to be offloaded.
     * @param endpointId The unique identifier of the target endpoint within the hub.
     * @param hubId The unique identifier of the hub to which the endpoint belongs.
     * @return An integer status code indicating the immediate result of the request, such as {@link
     *     GattOffloadSession#STATUS_SUCCESS} if the request was initiated successfully. A non-zero
     *     status indicates an immediate failure to start the operation.
     * @throws SecurityException if the caller does not have the necessary permissions.
     * @throws IllegalArgumentException if the service or characteristics are not valid.
     * @throws IllegalStateException if GATT server offload is not supported.
     */
    @Hide
    @SystemApi
    @FlaggedApi(Flags.FLAG_GATT_OFFLOAD_API)
    @RequiresBluetoothConnectPermission
    @RequiresPermission(allOf = {BLUETOOTH_CONNECT, BLUETOOTH_PRIVILEGED})
    public @GattOffloadSession.Status int offloadCharacteristics(
            @NonNull BluetoothDevice device,
            @NonNull BluetoothGattService service,
            @NonNull List<BluetoothGattCharacteristic> characteristics,
            long endpointId,
            long hubId) {
        requireNonNull(device);
        if (mService == null) {
            Log.e(TAG, "BluetoothGatt service not available");
            return GattOffloadSession.STATUS_SERVICE_UNAVAILABLE;
        }
        if (requireNonNull(service).getUuid() == null) {
            Log.e(TAG, "GattService uuid is null");
            return GattOffloadSession.STATUS_ILLEGAL_PARAMETER;
        }
        if (requireNonNull(characteristics).isEmpty()) {
            Log.e(TAG, "GattCharacteristics are empty");
            return GattOffloadSession.STATUS_ILLEGAL_PARAMETER;
        }
        StringBuilder builder = new StringBuilder("offloadCharacteristics{");
        builder.append("serviceUuid=").append(service.getUuid());
        for (BluetoothGattCharacteristic characteristic : characteristics) {
            if (VDBG) {
                builder.append(", CharUuid=")
                        .append(characteristic.getUuid())
                        .append(", CharInstanceId=")
                        .append(characteristic.getInstanceId());
            }
            if (characteristic.getInstanceId() == 0) {
                Log.e(TAG, "Characteristic instanceId is not configured");
                return GattOffloadSession.STATUS_ILLEGAL_PARAMETER;
            }
            if (characteristic.getService() == null) {
                Log.e(TAG, "GattService is null in characteristic");
                return GattOffloadSession.STATUS_ILLEGAL_PARAMETER;
            }
            if (!characteristic.getService().equals(service)) {
                Log.e(
                        TAG,
                        "Characteristic not bound to input service: expected="
                                + service.getUuid()
                                + " actual="
                                + characteristic.getService().getUuid());
                return GattOffloadSession.STATUS_ILLEGAL_PARAMETER;
            }
        }
        builder.append(", endpointId=").append(endpointId).append(", hubId=").append(hubId);
        builder.append("}");
        Log.d(TAG, builder.toString());

        GattOffloadSession.InnerParcel sessionParcel;
        try {
            sessionParcel =
                    mService.offloadServerCharacteristics(
                            mBluetoothGattServerCallback,
                            device,
                            service,
                            characteristics,
                            endpointId,
                            hubId,
                            mAttributionSource);
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
            return GattOffloadSession.STATUS_SERVICE_UNAVAILABLE;
        }
        @GattOffloadSession.Status int status = requireNonNull(sessionParcel).getStatus();
        if (status == GattOffloadSession.STATUS_SUCCESS) {
            ((GattServerCallback) mBluetoothGattServerCallback)
                    .onCharacteristicsOffloaded(
                            device,
                            new GattOffloadSession(
                                    requireNonNull(sessionParcel).getSessionId(),
                                    device,
                                    null,
                                    this,
                                    service,
                                    characteristics,
                                    endpointId,
                                    hubId),
                            status);
        }
        return status;
    }

    /**
     * Stops the offloading of characteristics associated with a given offload session for a
     * specific GATT Client.
     *
     * @param device The {@link BluetoothDevice} representing the GATT Client for which the offload
     *     session is to be terminated.
     * @param session The offload session to be terminated. This was returned by a previous
     *     successful call to {@link #offloadCharacteristics}.
     * @throws SecurityException if the caller does not have the necessary permissions.
     * @throws IllegalArgumentException if session id is not valid
     * @throws IllegalStateException if BluetoothGatt service not available.
     */
    @Hide
    @FlaggedApi(Flags.FLAG_GATT_OFFLOAD_API)
    @RequiresBluetoothConnectPermission
    @RequiresPermission(allOf = {BLUETOOTH_CONNECT, BLUETOOTH_PRIVILEGED})
    public void unoffloadCharacteristics(
            @NonNull BluetoothDevice device, @NonNull GattOffloadSession session) {
        if (mService == null) {
            throw new IllegalStateException("BluetoothGatt service not available");
        }
        int sessionId = session.getSessionId();
        Log.d(TAG, "unoffloadCharacteristics sessionId= " + sessionId);
        if (sessionId == GattOffloadSession.OFFLOAD_SESSION_ID_UNKNOWN) {
            throw new IllegalArgumentException("session id is not valid");
        }
        try {
            mService.unoffloadServerCharacteristics(
                    mBluetoothGattServerCallback, device, sessionId, mAttributionSource);
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
        }
    }

    /**
     * Not supported - please use {@link BluetoothManager#getConnectedDevices(int)} with {@link
     * BluetoothProfile#GATT} as argument
     *
     * @throws UnsupportedOperationException on every call
     */
    @Override
    @RequiresNoPermission
    public int getConnectionState(BluetoothDevice device) {
        throw new UnsupportedOperationException("Use BluetoothManager#getConnectionState instead.");
    }

    /**
     * Not supported - please use {@link BluetoothManager#getConnectedDevices(int)} with {@link
     * BluetoothProfile#GATT} as argument
     *
     * @throws UnsupportedOperationException on every call
     */
    @Override
    @RequiresNoPermission
    public List<BluetoothDevice> getConnectedDevices() {
        throw new UnsupportedOperationException(
                "Use BluetoothManager#getConnectedDevices instead.");
    }

    /**
     * Not supported - please use {@link BluetoothManager#getDevicesMatchingConnectionStates(int,
     * int[])} with {@link BluetoothProfile#GATT} as first argument
     *
     * @throws UnsupportedOperationException on every call
     */
    @Override
    @RequiresNoPermission
    public List<BluetoothDevice> getDevicesMatchingConnectionStates(int[] states) {
        throw new UnsupportedOperationException(
                "Use BluetoothManager#getDevicesMatchingConnectionStates instead.");
    }
}
