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
import static android.bluetooth.BluetoothUtils.executeFromBinder;
import static android.bluetooth.BluetoothUtils.logRemoteException;

import static java.util.Objects.requireNonNull;

import android.annotation.CallbackExecutor;
import android.annotation.FlaggedApi;
import android.annotation.Hide;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.RequiresNoPermission;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.bluetooth.BluetoothGattCharacteristic.WriteType;
import android.bluetooth.annotations.RequiresBluetoothConnectPermission;
import android.bluetooth.annotations.RequiresLegacyBluetoothPermission;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.AttributionSource;
import android.os.Handler;
import android.os.IBinder;
import android.os.ParcelUuid;
import android.os.RemoteException;
import android.util.Log;

import com.android.bluetooth.flags.Flags;
import com.android.internal.annotations.GuardedBy;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;

/**
 * Public API for the Bluetooth GATT Profile.
 *
 * <p>This class provides Bluetooth GATT functionality to enable communication with Bluetooth Smart
 * or Smart Ready devices.
 *
 * <p>To connect to a remote peripheral device, create a {@link BluetoothGattCallback} and call
 * {@link BluetoothDevice#connectGatt} to get a instance of this class. GATT capable devices can be
 * discovered using the Bluetooth device discovery or BLE scan process.
 */
public final class BluetoothGatt implements BluetoothProfile {
    private static final String TAG = BluetoothGatt.class.getSimpleName();

    private static final boolean VDBG = Log.isLoggable("bluetooth", Log.VERBOSE);

    private final IBluetoothGatt mService;
    private volatile BluetoothGattCallback mCallback;
    private final Executor mExecutor;
    private final BluetoothDevice mDevice;
    private final boolean mAutoConnect;
    private boolean mClientRegistered;

    private int mAuthRetryState = AUTH_RETRY_STATE_IDLE;

    private final Object mStateLock = new Object();

    @GuardedBy("mStateLock")
    private int mConnState = CONN_STATE_CONNECTING;

    private final Object mDeviceBusyLock = new Object();

    @GuardedBy("mDeviceBusyLock")
    private boolean mDeviceBusy = false;

    private final int mTransport;

    private final boolean mOpportunistic;
    private final AttributionSource mAttributionSource;
    private final BluetoothGattConnectionSettings mGattConnectionSettings;
    private static final int AUTH_RETRY_STATE_IDLE = 0;
    private static final int AUTH_RETRY_STATE_MITM = 2;

    private static final int CONN_STATE_IDLE = 0;
    private static final int CONN_STATE_CONNECTING = 1;
    private static final int CONN_STATE_CONNECTED = 2;
    private static final int CONN_STATE_CLOSED = 4;

    private static final int WRITE_CHARACTERISTIC_MAX_RETRIES = 5;
    private static final int WRITE_CHARACTERISTIC_TIME_TO_WAIT = 10; // milliseconds

    private final CopyOnWriteArrayList<BluetoothGattService> mServices =
            new CopyOnWriteArrayList<>();

    /** A GATT operation completed successfully */
    public static final int GATT_SUCCESS = 0;

    /** GATT read operation is not permitted */
    public static final int GATT_READ_NOT_PERMITTED = 0x2;

    /** GATT write operation is not permitted */
    public static final int GATT_WRITE_NOT_PERMITTED = 0x3;

    /** Insufficient authentication for a given operation */
    public static final int GATT_INSUFFICIENT_AUTHENTICATION = 0x5;

    /** The given request is not supported */
    public static final int GATT_REQUEST_NOT_SUPPORTED = 0x6;

    /** Insufficient encryption for a given operation */
    public static final int GATT_INSUFFICIENT_ENCRYPTION = 0xf;

    /** A read or write operation was requested with an invalid offset */
    public static final int GATT_INVALID_OFFSET = 0x7;

    /** Insufficient authorization for a given operation */
    public static final int GATT_INSUFFICIENT_AUTHORIZATION = 0x8;

    /** A write operation exceeds the maximum length of the attribute */
    public static final int GATT_INVALID_ATTRIBUTE_LENGTH = 0xd;

    /** A remote device connection is congested. */
    public static final int GATT_CONNECTION_CONGESTED = 0x8f;

    /**
     * GATT connection timed out, likely due to the remote device being out of range or not
     * advertising as connectable.
     */
    public static final int GATT_CONNECTION_TIMEOUT = 0x93;

    /** A GATT operation failed, errors other than the above */
    public static final int GATT_FAILURE = 0x101;

    /**
     * Connection parameter update - Use the connection parameters recommended by the Bluetooth SIG.
     * This is the default value if no connection parameter update is requested.
     */
    public static final int CONNECTION_PRIORITY_BALANCED = 0;

    /**
     * Connection parameter update - Request a high priority, low latency connection. An application
     * should only request high priority connection parameters to transfer large amounts of data
     * over LE quickly. Once the transfer is complete, the application should request {@link
     * BluetoothGatt#CONNECTION_PRIORITY_BALANCED} connection parameters to reduce energy use.
     */
    public static final int CONNECTION_PRIORITY_HIGH = 1;

    /** Connection parameter update - Request low power, reduced data rate connection parameters. */
    public static final int CONNECTION_PRIORITY_LOW_POWER = 2;

    /**
     * Connection parameter update - Request the priority preferred for Digital Car Key for a lower
     * latency connection. This connection parameter will consume more power than {@link
     * BluetoothGatt#CONNECTION_PRIORITY_BALANCED}, so it is recommended that apps do not use this
     * unless it specifically fits their use case.
     */
    public static final int CONNECTION_PRIORITY_DCK = 3;

    /** Connection Subrate mode - Request to disable subrate mode. */
    public static final int SUBRATE_MODE_OFF = 0;

    /**
     * Connection Subrate mode - Requests to enable subrate mode with parameters optimized for low
     * burstiness, minimum power consumption. This is the most power-efficient subrate
     * configuration.
     */
    public static final int SUBRATE_MODE_LOW = 1;

    /**
     * Connection subrate mode - Requests to enable subrate mode using balanced parameters to
     * provide a compromise between power savings and performance.
     */
    public static final int SUBRATE_MODE_BALANCED = 2;

    /**
     * Connection subrate mode - Requests to enable subrate mode with parameters optimized for high
     * burstiness, enhanced data transfer.
     */
    public static final int SUBRATE_MODE_HIGH = 3;

    /** Connection Subrate mode - System Update. */
    public static final int SUBRATE_MODE_SYSTEM_UPDATE = 99;

    /** Connection Subrate mode - No Update applied due to error. */
    public static final int SUBRATE_MODE_NOT_UPDATED = 255;

    @Hide
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(
            prefix = {"SUBRATE_MODE"},
            value = {SUBRATE_MODE_OFF, SUBRATE_MODE_LOW, SUBRATE_MODE_BALANCED, SUBRATE_MODE_HIGH})
    public @interface SubrateMode {}

    /** Subrate modes update. */
    @Hide
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(
            prefix = {"ON_SUBRATE_CHANGE_MODE"},
            value = {
                SUBRATE_MODE_OFF,
                SUBRATE_MODE_LOW,
                SUBRATE_MODE_BALANCED,
                SUBRATE_MODE_HIGH,
                SUBRATE_MODE_SYSTEM_UPDATE,
                SUBRATE_MODE_NOT_UPDATED,
            })
    public @interface OnSubrateChangeModeValues {}

    /** Subrate request return values. */
    @Hide
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(
            prefix = {"ON_SUBRATE_CHANGE_STATUS"},
            value = {
                BluetoothStatusCodes.SUCCESS,
                BluetoothStatusCodes.REASON_SYSTEM_POLICY,
                BluetoothStatusCodes.ERROR_UNKNOWN,
                BluetoothStatusCodes.ERROR_HARDWARE_GENERIC,
                BluetoothStatusCodes.NOT_ALLOWED,
                BluetoothStatusCodes.ERROR_REMOTE_OPERATION_NOT_SUPPORTED,
                BluetoothStatusCodes.ERROR_LOCAL_NOT_ENOUGH_RESOURCES,
                BluetoothStatusCodes.ERROR_BAD_PARAMETERS,
            })
    public @interface OnSubrateChangeStatusValues {}

    /** No authentication required. */
    @Hide /*package*/ static final int AUTHENTICATION_NONE = 0;

    /** Authentication requested; no person-in-the-middle protection required. */
    @Hide /*package*/ static final int AUTHENTICATION_NO_MITM = 1;

    /** Authentication with person-in-the-middle protection requested. */
    @Hide /*package*/ static final int AUTHENTICATION_MITM = 2;

    /** Bluetooth GATT callbacks. Overrides the default BluetoothGattCallback implementation. */
    private final IBluetoothGattCallback mBluetoothGattCallback = new GattCallback();

    private class GattCallback extends IBluetoothGattCallback.Stub {
        /**
         * Queue the runnable on a {@link Handler} provided by the user, or execute the runnable
         * immediately if no Handler was provided.
         */
        private void runOrQueueCallback(final Runnable cb) {
            executeFromBinder(mExecutor, cb);
        }

        /** Application interface registered - app is ready to go */
        @Hide
        @Override
        @RequiresNoPermission // Callback to app
        public void onClientRegistered(int status) {
            Log.d(TAG, "onClientRegistered(" + status + ")");
            mClientRegistered = status == GATT_SUCCESS;
            synchronized (mStateLock) {
                if (mConnState == CONN_STATE_CLOSED) {
                    Log.d(TAG, "Client registration completed after closed, unregistering");
                    unregisterApp();
                    mCallback = null;
                    return;
                }
                if (VDBG) {
                    if (mConnState != CONN_STATE_CONNECTING) {
                        Log.e(TAG, "Bad connection state: " + mConnState);
                    }
                }
            }
            if (status != GATT_SUCCESS) {
                runOrQueueCallback(
                        () -> {
                            final BluetoothGattCallback callback = mCallback;
                            if (callback != null) {
                                callback.onConnectionStateChange(
                                        BluetoothGatt.this, GATT_FAILURE, STATE_DISCONNECTED);
                            }
                        });

                synchronized (mStateLock) {
                    mConnState = CONN_STATE_IDLE;
                }
                return;
            }
            try {
                // autoConnect is inverse of "isDirect"
                boolean isAutoMtuEnabled = false;
                if (Flags.gattConnSettings()) {
                    isAutoMtuEnabled = mGattConnectionSettings.isAutomaticMtuEnabled();
                }
                mService.clientConnect(
                        mBluetoothGattCallback,
                        mDevice,
                        mDevice.getAddressType(),
                        !mAutoConnect,
                        mTransport,
                        mOpportunistic,
                        isAutoMtuEnabled,
                        mAttributionSource);
            } catch (RemoteException e) {
                Log.e(TAG, "", e);
            }
        }

        /** Phy update callback */
        @Hide
        @Override
        @RequiresNoPermission // Callback to app
        public void onPhyUpdate(BluetoothDevice device, int txPhy, int rxPhy, int status) {
            Log.d(
                    TAG,
                    "onPhyUpdate() -"
                            + (" status=" + status)
                            + (" device=" + device)
                            + (" txPhy=" + txPhy)
                            + (" rxPhy=" + rxPhy));
            if (!mDevice.equals(device)) {
                return;
            }

            runOrQueueCallback(
                    () -> {
                        final BluetoothGattCallback callback = mCallback;
                        if (callback != null) {
                            callback.onPhyUpdate(BluetoothGatt.this, txPhy, rxPhy, status);
                        }
                    });
        }

        /** Phy read callback */
        @Hide
        @Override
        @RequiresNoPermission // Callback to app
        public void onPhyRead(BluetoothDevice device, int txPhy, int rxPhy, int status) {
            Log.d(
                    TAG,
                    "onPhyRead() -"
                            + (" status=" + status)
                            + (" device=" + device)
                            + (" txPhy=" + txPhy)
                            + (" rxPhy=" + rxPhy));
            if (!mDevice.equals(device)) {
                return;
            }

            runOrQueueCallback(
                    () -> {
                        final BluetoothGattCallback callback = mCallback;
                        if (callback != null) {
                            callback.onPhyRead(BluetoothGatt.this, txPhy, rxPhy, status);
                        }
                    });
        }

        /** Client connection state changed */
        @Hide
        @Override
        @RequiresBluetoothConnectPermission
        @RequiresPermission(BLUETOOTH_CONNECT)
        public void onClientConnectionState(int status, boolean connected, BluetoothDevice device) {
            Log.d(
                    TAG,
                    "onClientConnectionState() -"
                            + (" status=" + status)
                            + (" connected=" + connected)
                            + (" device=" + device));
            if (!mDevice.equals(device)) {
                return;
            }
            int profileState = connected ? STATE_CONNECTED : STATE_DISCONNECTED;

            if (!connected && !mAutoConnect) {
                unregisterApp();
            }

            runOrQueueCallback(
                    () -> {
                        final BluetoothGattCallback callback = mCallback;
                        if (callback != null) {
                            callback.onConnectionStateChange(
                                    BluetoothGatt.this, status, profileState);
                        }
                    });

            synchronized (mStateLock) {
                if (connected) {
                    mConnState = CONN_STATE_CONNECTED;
                } else {
                    mConnState = CONN_STATE_IDLE;
                }
            }

            synchronized (mDeviceBusyLock) {
                mDeviceBusy = false;
            }
        }

        /**
         * Remote search has been completed. The internal object structure should now reflect the
         * state of the remote device database. Let the application know that we are done at this
         * point.
         */
        @Hide
        @Override
        @RequiresNoPermission // Callback to app
        public void onSearchComplete(
                BluetoothDevice device, List<BluetoothGattService> services, int status) {
            Log.d(TAG, "onSearchComplete(" + device + ", _, " + status + ")");
            if (!mDevice.equals(device)) {
                return;
            }

            for (BluetoothGattService s : services) {
                // services we receive don't have device set properly.
                s.setDevice(mDevice);
            }

            mServices.clear();
            mServices.addAll(services);

            // Fix references to included services, as they doesn't point to right objects.
            for (BluetoothGattService fixedService : mServices) {
                ArrayList<BluetoothGattService> includedServices =
                        new ArrayList<>(fixedService.getIncludedServices());
                fixedService.getIncludedServices().clear();

                for (BluetoothGattService brokenRef : includedServices) {
                    BluetoothGattService includedService =
                            getService(mDevice, brokenRef.getUuid(), brokenRef.getInstanceId());
                    if (includedService != null) {
                        fixedService.addIncludedService(includedService);
                    } else {
                        Log.e(TAG, "Broken GATT database: can't find included service.");
                    }
                }
            }

            runOrQueueCallback(
                    () -> {
                        final BluetoothGattCallback callback = mCallback;
                        if (callback != null) {
                            callback.onServicesDiscovered(BluetoothGatt.this, status);
                        }
                    });
        }

        /** Remote characteristic has been read. Updates the internal value. */
        @Hide
        @Override
        @RequiresNoPermission // Callback to app
        public void onCharacteristicRead(
                BluetoothDevice device, int status, int handle, byte[] value) {
            if (VDBG) {
                Log.d(
                        TAG,
                        "onCharacteristicRead() -"
                                + (" device=" + device)
                                + (" handle=" + handle)
                                + (" Status=" + status));
            }

            if (!mDevice.equals(device)) {
                return;
            }

            synchronized (mDeviceBusyLock) {
                mDeviceBusy = false;
            }

            if ((status == GATT_INSUFFICIENT_AUTHENTICATION
                            || status == GATT_INSUFFICIENT_ENCRYPTION)
                    && (mAuthRetryState != AUTH_RETRY_STATE_MITM)
                    && mClientRegistered) {
                try {
                    final int authReq =
                            (mAuthRetryState == AUTH_RETRY_STATE_IDLE)
                                    ? AUTHENTICATION_NO_MITM
                                    : AUTHENTICATION_MITM;
                    mService.readCharacteristic(
                            mBluetoothGattCallback, device, handle, authReq, mAttributionSource);
                    mAuthRetryState++;
                    return;
                } catch (RemoteException e) {
                    Log.e(TAG, "", e);
                }
            }

            mAuthRetryState = AUTH_RETRY_STATE_IDLE;

            BluetoothGattCharacteristic characteristic = getCharacteristicById(mDevice, handle);
            if (characteristic == null) {
                Log.w(TAG, "onCharacteristicRead() failed to find characteristic!");
                return;
            }

            runOrQueueCallback(
                    () -> {
                        final BluetoothGattCallback callback = mCallback;
                        if (callback != null) {
                            if (status == 0) characteristic.setValue(value);
                            callback.onCharacteristicRead(
                                    BluetoothGatt.this, characteristic, value, status);
                        }
                    });
        }

        /** Characteristic has been written to the remote device. Let the app know how we did... */
        @Hide
        @Override
        @RequiresNoPermission // Callback to app
        public void onCharacteristicWrite(
                BluetoothDevice device, int status, int handle, byte[] value) {
            if (VDBG) {
                Log.d(
                        TAG,
                        "onCharacteristicWrite() -"
                                + (" device=" + device)
                                + (" handle=" + handle)
                                + (" Status=" + status));
            }

            if (!mDevice.equals(device)) {
                return;
            }

            synchronized (mDeviceBusyLock) {
                mDeviceBusy = false;
            }

            BluetoothGattCharacteristic characteristic = getCharacteristicById(mDevice, handle);
            if (characteristic == null) return;

            if ((status == GATT_INSUFFICIENT_AUTHENTICATION
                            || status == GATT_INSUFFICIENT_ENCRYPTION)
                    && (mAuthRetryState != AUTH_RETRY_STATE_MITM)) {
                try {
                    final int authReq =
                            (mAuthRetryState == AUTH_RETRY_STATE_IDLE)
                                    ? AUTHENTICATION_NO_MITM
                                    : AUTHENTICATION_MITM;
                    int requestStatus = BluetoothStatusCodes.ERROR_UNKNOWN;
                    for (int i = 0;
                            (i < WRITE_CHARACTERISTIC_MAX_RETRIES) && mClientRegistered;
                            i++) {
                        requestStatus =
                                mService.writeCharacteristic(
                                        mBluetoothGattCallback,
                                        device,
                                        handle,
                                        characteristic.getWriteType(),
                                        authReq,
                                        value,
                                        mAttributionSource);
                        if (requestStatus != BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY) {
                            break;
                        }
                        try {
                            Thread.sleep(WRITE_CHARACTERISTIC_TIME_TO_WAIT);
                        } catch (InterruptedException e) {
                            Log.e(TAG, "", e);
                        }
                    }
                    mAuthRetryState++;
                    return;
                } catch (RemoteException e) {
                    Log.e(TAG, "", e);
                }
            }

            mAuthRetryState = AUTH_RETRY_STATE_IDLE;
            runOrQueueCallback(
                    () -> {
                        final BluetoothGattCallback callback = mCallback;
                        if (callback != null) {
                            callback.onCharacteristicWrite(
                                    BluetoothGatt.this, characteristic, status);
                        }
                    });
        }

        /** Remote characteristic has been updated. Updates the internal value. */
        @Hide
        @Override
        @RequiresNoPermission // Callback to app
        public void onNotify(BluetoothDevice device, int handle, byte[] value) {
            if (VDBG) {
                Log.d(TAG, "onNotify(" + device + ", " + handle + ", _)");
            }
            if (!mDevice.equals(device)) {
                return;
            }

            BluetoothGattCharacteristic characteristic = getCharacteristicById(mDevice, handle);
            if (characteristic == null) return;

            runOrQueueCallback(
                    () -> {
                        final BluetoothGattCallback callback = mCallback;
                        if (callback != null) {
                            characteristic.setValue(value);
                            callback.onCharacteristicChanged(
                                    BluetoothGatt.this, characteristic, value);
                        }
                    });
        }

        /** Descriptor has been read. */
        @Hide
        @Override
        @RequiresNoPermission // Callback to app
        public void onDescriptorRead(BluetoothDevice device, int status, int handle, byte[] value) {
            if (VDBG) {
                Log.d(TAG, "onDescriptorRead(" + device + ", " + status + ", " + handle + ", _)");
            }

            if (!mDevice.equals(device)) {
                return;
            }

            synchronized (mDeviceBusyLock) {
                mDeviceBusy = false;
            }

            BluetoothGattDescriptor descriptor = getDescriptorById(mDevice, handle);
            if (descriptor == null) return;

            if ((status == GATT_INSUFFICIENT_AUTHENTICATION
                            || status == GATT_INSUFFICIENT_ENCRYPTION)
                    && (mAuthRetryState != AUTH_RETRY_STATE_MITM)
                    && mClientRegistered) {
                try {
                    final int authReq =
                            (mAuthRetryState == AUTH_RETRY_STATE_IDLE)
                                    ? AUTHENTICATION_NO_MITM
                                    : AUTHENTICATION_MITM;
                    mService.readDescriptor(
                            mBluetoothGattCallback, device, handle, authReq, mAttributionSource);
                    mAuthRetryState++;
                    return;
                } catch (RemoteException e) {
                    Log.e(TAG, "", e);
                }
            }

            mAuthRetryState = AUTH_RETRY_STATE_IDLE;

            runOrQueueCallback(
                    () -> {
                        final BluetoothGattCallback callback = mCallback;
                        if (callback != null) {
                            if (status == 0) descriptor.setValue(value);
                            callback.onDescriptorRead(
                                    BluetoothGatt.this, descriptor, status, value);
                        }
                    });
        }

        /** Descriptor write operation complete. */
        @Hide
        @Override
        @RequiresNoPermission // Callback to app
        public void onDescriptorWrite(
                BluetoothDevice device, int status, int handle, byte[] value) {
            if (VDBG) {
                Log.d(TAG, "onDescriptorWrite(" + device + ", " + status + ", " + handle + ", _)");
            }

            if (!mDevice.equals(device)) {
                return;
            }

            synchronized (mDeviceBusyLock) {
                mDeviceBusy = false;
            }

            BluetoothGattDescriptor descriptor = getDescriptorById(mDevice, handle);
            if (descriptor == null) return;

            if ((status == GATT_INSUFFICIENT_AUTHENTICATION
                            || status == GATT_INSUFFICIENT_ENCRYPTION)
                    && (mAuthRetryState != AUTH_RETRY_STATE_MITM)
                    && mClientRegistered) {
                try {
                    final int authReq =
                            (mAuthRetryState == AUTH_RETRY_STATE_IDLE)
                                    ? AUTHENTICATION_NO_MITM
                                    : AUTHENTICATION_MITM;
                    mService.writeDescriptor(
                            mBluetoothGattCallback,
                            device,
                            handle,
                            authReq,
                            value,
                            mAttributionSource);
                    mAuthRetryState++;
                    return;
                } catch (RemoteException e) {
                    Log.e(TAG, "", e);
                }
            }

            mAuthRetryState = AUTH_RETRY_STATE_IDLE;

            runOrQueueCallback(
                    () -> {
                        final BluetoothGattCallback callback = mCallback;
                        if (callback != null) {
                            callback.onDescriptorWrite(BluetoothGatt.this, descriptor, status);
                        }
                    });
        }

        /** Prepared write transaction completed (or aborted) */
        @Hide
        @Override
        @RequiresNoPermission // Callback to app
        public void onExecuteWrite(BluetoothDevice device, int status) {
            if (VDBG) {
                Log.d(TAG, "onExecuteWrite(" + device + ", " + status + ")");
            }

            if (!mDevice.equals(device)) {
                return;
            }

            synchronized (mDeviceBusyLock) {
                mDeviceBusy = false;
            }

            runOrQueueCallback(
                    () -> {
                        final BluetoothGattCallback callback = mCallback;
                        if (callback != null) {
                            callback.onReliableWriteCompleted(BluetoothGatt.this, status);
                        }
                    });
        }

        /** Remote device RSSI has been read */
        @Hide
        @Override
        @RequiresNoPermission // Callback to app
        public void onReadRemoteRssi(BluetoothDevice device, int rssi, int status) {
            if (VDBG) {
                Log.d(TAG, "onReadRemoteRssi(" + device + ", " + rssi + ", " + status + ")");
            }
            if (!mDevice.equals(device)) {
                return;
            }
            runOrQueueCallback(
                    () -> {
                        final BluetoothGattCallback callback = mCallback;
                        if (callback != null) {
                            callback.onReadRemoteRssi(BluetoothGatt.this, rssi, status);
                        }
                    });
        }

        /** Callback invoked when the MTU for a given connection changes */
        @Hide
        @Override
        @RequiresNoPermission // Callback to app
        public void onConfigureMTU(BluetoothDevice device, int mtu, int status) {
            Log.d(TAG, "onConfigureMTU(" + device + ", " + mtu + ", " + status + ")");
            if (!mDevice.equals(device)) {
                return;
            }

            runOrQueueCallback(
                    () -> {
                        final BluetoothGattCallback callback = mCallback;
                        if (callback != null) {
                            callback.onMtuChanged(BluetoothGatt.this, mtu, status);
                        }
                    });
        }

        /** Callback invoked when the given connection is updated */
        @Hide
        @Override
        @RequiresNoPermission // Callback to app
        public void onConnectionUpdated(
                BluetoothDevice device, int interval, int latency, int timeout, int status) {
            Log.d(
                    TAG,
                    "onConnectionUpdated() -"
                            + (" device=" + device)
                            + (" interval=" + interval)
                            + (" latency=" + latency)
                            + (" timeout=" + timeout)
                            + (" status=" + status));
            if (!mDevice.equals(device)) {
                return;
            }

            runOrQueueCallback(
                    () -> {
                        final BluetoothGattCallback callback = mCallback;
                        if (callback != null) {
                            callback.onConnectionUpdated(
                                    BluetoothGatt.this, interval, latency, timeout, status);
                        }
                    });
        }

        /** Callback invoked when service changed event is received */
        @Hide
        @Override
        @RequiresNoPermission // Callback to app
        public void onServiceChanged(BluetoothDevice device) {
            Log.d(TAG, "onServiceChanged(" + device + ")");

            if (!mDevice.equals(device)) {
                return;
            }

            runOrQueueCallback(
                    () -> {
                        final BluetoothGattCallback callback = mCallback;
                        if (callback != null) {
                            callback.onServiceChanged(BluetoothGatt.this);
                        }
                    });
        }

        /** Callback invoked when the given connection's subrate is changed */
        @Hide
        @Override
        @RequiresNoPermission // Callback to app
        public void onSubrateChange(BluetoothDevice device, int subrateMode, int status) {
            Log.d(
                    TAG,
                    "onSubrateChange() - "
                            + (" subrateMode=" + subrateMode)
                            + (" status=" + status));

            if (!mDevice.equals(device)) {
                return;
            }

            runOrQueueCallback(
                    () -> {
                        final BluetoothGattCallback callback = mCallback;
                        if (callback != null) {
                            callback.onSubrateChange(BluetoothGatt.this, subrateMode, status);
                        }
                    });
        }

        /** Callback indicating whether GATT characteristics offload has been added. */
        @Hide
        @RequiresNoPermission
        @FlaggedApi(Flags.FLAG_GATT_OFFLOAD_API)
        /* package */ void onCharacteristicsOffloaded(
                BluetoothDevice device, GattOffloadSession session, int status) {
            Log.d(
                    TAG,
                    "onCharacteristicsOffloaded() -"
                            + (" device=" + device)
                            + (" session=" + session)
                            + (" status=" + status));

            if (!mDevice.equals(device)) {
                return;
            }

            runOrQueueCallback(
                    () -> {
                        final BluetoothGattCallback callback = mCallback;
                        if (callback != null) {
                            callback.onCharacteristicsOffloaded(
                                    BluetoothGatt.this, session, status);
                        }
                    });
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
                    "onCharacteristicsUnoffloaded() -"
                            + (" device=" + device)
                            + (" sessionId=" + sessionId)
                            + (" status=" + status));

            if (!mDevice.equals(device)) {
                return;
            }

            runOrQueueCallback(
                    () -> {
                        final BluetoothGattCallback callback = mCallback;
                        if (callback != null) {
                            callback.onCharacteristicsUnoffloaded(
                                    BluetoothGatt.this, sessionId, status);
                        }
                    });
        }
    }

    @FlaggedApi(Flags.FLAG_GATT_CONN_SETTINGS)
    @RequiresBluetoothConnectPermission
    @RequiresPermission(BLUETOOTH_CONNECT)
    BluetoothGatt(
            @NonNull IBluetoothGatt iGatt,
            @NonNull BluetoothDevice device,
            AttributionSource source,
            BluetoothGattConnectionSettings gattConnectionSettings,
            @NonNull BluetoothGattCallback callback,
            @NonNull @CallbackExecutor Executor executor) {
        mService = iGatt;
        mDevice = device;
        mTransport = gattConnectionSettings.getTransport();
        mAutoConnect = gattConnectionSettings.isAutoConnectEnabled();
        mOpportunistic = gattConnectionSettings.isOpportunisticEnabled();
        mAttributionSource = source;
        mCallback = callback;
        mExecutor = executor;
        mGattConnectionSettings = requireNonNull(gattConnectionSettings);
        UUID uuid = UUID.randomUUID();
        Log.d(TAG, "BluetoothGatt() UUID=" + uuid);
        try {
            mService.registerClient(
                    new ParcelUuid(uuid),
                    mBluetoothGattCallback,
                    false, // eattSupport
                    mTransport,
                    mAttributionSource);
        } catch (RemoteException e) {
            synchronized (mStateLock) {
                mConnState = CONN_STATE_IDLE;
            }
            Log.e(TAG, "", e);
        }
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
        return null;
    }

    /**
     * Close this Bluetooth GATT client.
     *
     * <p>Application should call this method as early as possible after it is done with this GATT
     * client.
     */
    @RequiresBluetoothConnectPermission
    @RequiresPermission(BLUETOOTH_CONNECT)
    public void close() {
        Log.d(TAG, "close()");

        unregisterApp();
        mCallback = null;

        synchronized (mStateLock) {
            mConnState = CONN_STATE_CLOSED;
        }
        mAuthRetryState = AUTH_RETRY_STATE_IDLE;
    }

    /** Returns a service by UUID, instance and type. */
    @Hide
    /*package*/ BluetoothGattService getService(BluetoothDevice device, UUID uuid, int instanceId) {
        for (BluetoothGattService svc : mServices) {
            if (svc.getDevice().equals(device)
                    && svc.getInstanceId() == instanceId
                    && svc.getUuid().equals(uuid)) {
                return svc;
            }
        }
        return null;
    }

    /** Returns a characteristic with id equal to instanceId. */
    @Hide
    /*package*/ BluetoothGattCharacteristic getCharacteristicById(
            BluetoothDevice device, int instanceId) {
        for (BluetoothGattService svc : mServices) {
            for (BluetoothGattCharacteristic charac : svc.getCharacteristics()) {
                if (charac.getInstanceId() == instanceId) {
                    return charac;
                }
            }
        }
        return null;
    }

    /** Returns a descriptor with id equal to instanceId. */
    @Hide
    /*package*/ BluetoothGattDescriptor getDescriptorById(BluetoothDevice device, int instanceId) {
        for (BluetoothGattService svc : mServices) {
            for (BluetoothGattCharacteristic charac : svc.getCharacteristics()) {
                for (BluetoothGattDescriptor desc : charac.getDescriptors()) {
                    if (desc.getInstanceId() == instanceId) {
                        return desc;
                    }
                }
            }
        }
        return null;
    }

    /** Unregister the current application and callbacks. */
    @UnsupportedAppUsage
    @RequiresBluetoothConnectPermission
    @RequiresPermission(BLUETOOTH_CONNECT)
    private void unregisterApp() {
        if (!mClientRegistered) return;
        Log.d(TAG, "unregisterApp()");

        try {
            mService.unregisterClient(mBluetoothGattCallback, mAttributionSource);
            mClientRegistered = false;
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
        }
    }

    /**
     * Disconnects an established connection, or cancels a connection attempt currently in progress.
     */
    @RequiresLegacyBluetoothPermission
    @RequiresBluetoothConnectPermission
    @RequiresPermission(BLUETOOTH_CONNECT)
    public void disconnect() {
        Log.d(TAG, "cancelOpen() - device: " + mDevice);
        if (!mClientRegistered) return;

        try {
            mService.clientDisconnect(mBluetoothGattCallback, mDevice, mAttributionSource);
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
        }
    }

    /**
     * Connect back to remote device.
     *
     * <p>This method is used to re-connect to a remote device after the connection has been
     * dropped. If the device is not in range, the re-connection will be triggered once the device
     * is back in range.
     *
     * @return true, if the connection attempt was initiated successfully
     */
    @RequiresBluetoothConnectPermission
    @RequiresPermission(BLUETOOTH_CONNECT)
    public boolean connect() {
        if (!mClientRegistered) {
            synchronized (mStateLock) {
                if (mConnState != CONN_STATE_IDLE) {
                    return false;
                }
                mConnState = CONN_STATE_CONNECTING;
            }

            UUID uuid = UUID.randomUUID();
            Log.d(TAG, "reconnect from connect(), UUID=" + uuid);

            try {
                mService.registerClient(
                        new ParcelUuid(uuid),
                        mBluetoothGattCallback,
                        /* eatt_support= */ false,
                        mTransport,
                        mAttributionSource);
            } catch (RemoteException e) {
                Log.e(TAG, "", e);
                synchronized (mStateLock) {
                    mConnState = CONN_STATE_IDLE;
                }
                Log.e(TAG, "Failed to register callback");
                return false;
            }

            return true;
        }

        try {
            Log.d(TAG, "connect(void) - device: " + mDevice + ", auto=" + mAutoConnect);

            // autoConnect is inverse of "isDirect"
            boolean isAutoMtuEnabled = false;
            if (Flags.gattConnSettings()) {
                isAutoMtuEnabled = mGattConnectionSettings.isAutomaticMtuEnabled();
            }
            mService.clientConnect(
                    mBluetoothGattCallback,
                    mDevice,
                    mDevice.getAddressType(),
                    !mAutoConnect,
                    mTransport,
                    mOpportunistic,
                    isAutoMtuEnabled,
                    mAttributionSource);
            return true;
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
            return false;
        }
    }

    /**
     * Set the preferred connection PHY for this app. Please note that this is just a
     * recommendation, whether the PHY change will happen depends on other applications preferences,
     * local and remote controller capabilities. Controller can override these settings.
     *
     * <p>{@link BluetoothGattCallback#onPhyUpdate} will be triggered as a result of this call, even
     * if no PHY change happens. It is also triggered when remote device updates the PHY.
     *
     * @param txPhy preferred transmitter PHY.
     * @param rxPhy preferred receiver PHY.
     * @param phyOptions preferred coding to use when transmitting on the LE Coded PHY. Can be one
     *     of {@link BluetoothDevice#PHY_OPTION_NO_PREFERRED}, {@link BluetoothDevice#PHY_OPTION_S2}
     *     or {@link BluetoothDevice#PHY_OPTION_S8}.
     */
    @RequiresBluetoothConnectPermission
    @RequiresPermission(BLUETOOTH_CONNECT)
    public void setPreferredPhy(
            @BluetoothDevice.PhyMask int txPhy,
            @BluetoothDevice.PhyMask int rxPhy,
            int phyOptions) {
        if (!mClientRegistered) return;

        try {
            mService.clientSetPreferredPhy(
                    mBluetoothGattCallback, mDevice, txPhy, rxPhy, phyOptions, mAttributionSource);
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
        }
    }

    /**
     * Read the current transmitter PHY and receiver PHY of the connection. The values are returned
     * in {@link BluetoothGattCallback#onPhyRead}
     */
    @RequiresBluetoothConnectPermission
    @RequiresPermission(BLUETOOTH_CONNECT)
    public void readPhy() {
        if (!mClientRegistered) return;

        try {
            mService.clientReadPhy(mBluetoothGattCallback, mDevice, mAttributionSource);
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
        }
    }

    /**
     * Return the remote bluetooth device this GATT client targets to
     *
     * @return remote bluetooth device
     */
    @RequiresNoPermission
    public BluetoothDevice getDevice() {
        return mDevice;
    }

    /**
     * Discovers services offered by a remote device as well as their characteristics and
     * descriptors.
     *
     * <p>This is an asynchronous operation. Once service discovery is completed, the {@link
     * BluetoothGattCallback#onServicesDiscovered} callback is triggered. If the discovery was
     * successful, the remote services can be retrieved using the {@link #getServices} function.
     *
     * @return true, if the remote service discovery has been started
     */
    @RequiresLegacyBluetoothPermission
    @RequiresBluetoothConnectPermission
    @RequiresPermission(BLUETOOTH_CONNECT)
    public boolean discoverServices() {
        Log.d(TAG, "discoverServices() - device: " + mDevice);

        if (!mClientRegistered) return false;

        try {
            mService.discoverServices(mBluetoothGattCallback, mDevice, mAttributionSource);
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
            return false;
        }

        return true;
    }

    /**
     * Discovers a service by UUID. This is exposed only for passing PTS tests. It should never be
     * used by real applications. The service is not searched for characteristics and descriptors,
     * or returned in any callback.
     *
     * @return true, if the remote service discovery has been started
     */
    @Hide
    @RequiresLegacyBluetoothPermission
    @RequiresBluetoothConnectPermission
    @RequiresPermission(BLUETOOTH_CONNECT)
    public boolean discoverServiceByUuid(UUID uuid) {
        Log.d(TAG, "discoverServiceByUuid() - device: " + mDevice);
        if (!mClientRegistered) return false;

        try {
            mService.discoverServiceByUuid(
                    mBluetoothGattCallback, mDevice, new ParcelUuid(uuid), mAttributionSource);
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
            return false;
        }
        return true;
    }

    /**
     * Returns a list of GATT services offered by the remote device.
     *
     * <p>This function requires that service discovery has been completed for the given device.
     *
     * @return List of services on the remote device. Returns an empty list if service discovery has
     *     not yet been performed.
     */
    @RequiresLegacyBluetoothPermission
    @RequiresNoPermission
    public List<BluetoothGattService> getServices() {
        List<BluetoothGattService> result = new ArrayList<BluetoothGattService>();

        for (BluetoothGattService service : mServices) {
            if (service.getDevice().equals(mDevice)) {
                result.add(service);
            }
        }

        return result;
    }

    /**
     * Returns a {@link BluetoothGattService}, if the requested UUID is supported by the remote
     * device.
     *
     * <p>This function requires that service discovery has been completed for the given device.
     *
     * <p>If multiple instances of the same service (as identified by UUID) exist, the first
     * instance of the service is returned.
     *
     * @param uuid UUID of the requested service
     * @return BluetoothGattService if supported, or null if the requested service is not offered by
     *     the remote device.
     */
    @RequiresLegacyBluetoothPermission
    @RequiresNoPermission
    public BluetoothGattService getService(UUID uuid) {
        for (BluetoothGattService service : mServices) {
            if (service.getDevice().equals(mDevice) && service.getUuid().equals(uuid)) {
                return service;
            }
        }

        return null;
    }

    /**
     * Reads the requested characteristic from the associated remote device.
     *
     * <p>This is an asynchronous operation. The result of the read operation is reported by the
     * {@link BluetoothGattCallback#onCharacteristicRead(BluetoothGatt, BluetoothGattCharacteristic,
     * byte[], int)} callback.
     *
     * @param characteristic Characteristic to read from the remote device
     * @return true, if the read operation was initiated successfully
     */
    @RequiresLegacyBluetoothPermission
    @RequiresBluetoothConnectPermission
    @RequiresPermission(BLUETOOTH_CONNECT)
    public boolean readCharacteristic(BluetoothGattCharacteristic characteristic) {
        if ((characteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_READ) == 0) {
            return false;
        }

        if (VDBG) Log.d(TAG, "readCharacteristic() - uuid: " + characteristic.getUuid());
        if (!mClientRegistered) return false;

        BluetoothGattService service = characteristic.getService();
        if (service == null) return false;

        BluetoothDevice device = service.getDevice();
        if (device == null) return false;

        synchronized (mDeviceBusyLock) {
            if (mDeviceBusy) return false;
            mDeviceBusy = true;
        }

        try {
            mService.readCharacteristic(
                    mBluetoothGattCallback,
                    device,
                    characteristic.getInstanceId(),
                    AUTHENTICATION_NONE,
                    mAttributionSource);
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
            synchronized (mDeviceBusyLock) {
                mDeviceBusy = false;
            }
            return false;
        }

        return true;
    }

    /**
     * Reads the characteristic using its UUID from the associated remote device.
     *
     * <p>This is an asynchronous operation. The result of the read operation is reported by the
     * {@link BluetoothGattCallback#onCharacteristicRead(BluetoothGatt, BluetoothGattCharacteristic,
     * byte[], int)} callback.
     *
     * @param uuid UUID of characteristic to read from the remote device
     * @return true, if the read operation was initiated successfully
     */
    @Hide
    @RequiresLegacyBluetoothPermission
    @RequiresBluetoothConnectPermission
    @RequiresPermission(
            allOf = {BLUETOOTH_CONNECT, BLUETOOTH_PRIVILEGED},
            conditional = true)
    public boolean readUsingCharacteristicUuid(UUID uuid, int startHandle, int endHandle) {
        if (VDBG) Log.d(TAG, "readUsingCharacteristicUuid() - uuid: " + uuid);
        if (!mClientRegistered) return false;

        synchronized (mDeviceBusyLock) {
            if (mDeviceBusy) return false;
            mDeviceBusy = true;
        }

        try {
            mService.readUsingCharacteristicUuid(
                    mBluetoothGattCallback,
                    mDevice,
                    new ParcelUuid(uuid),
                    startHandle,
                    endHandle,
                    AUTHENTICATION_NONE,
                    mAttributionSource);
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
            synchronized (mDeviceBusyLock) {
                mDeviceBusy = false;
            }
            return false;
        }

        return true;
    }

    /**
     * Writes a given characteristic and its values to the associated remote device.
     *
     * <p>Once the write operation has been completed, the {@link
     * BluetoothGattCallback#onCharacteristicWrite} callback is invoked, reporting the result of the
     * operation.
     *
     * @param characteristic Characteristic to write on the remote device
     * @return true, if the write operation was initiated successfully
     * @throws IllegalArgumentException if characteristic or its value are null
     * @deprecated Use {@link BluetoothGatt#writeCharacteristic(BluetoothGattCharacteristic, byte[],
     *     int)} as this is not memory safe because it relies on a {@link
     *     BluetoothGattCharacteristic} object whose underlying fields are subject to change outside
     *     this method.
     */
    @Deprecated
    @RequiresLegacyBluetoothPermission
    @RequiresBluetoothConnectPermission
    @RequiresPermission(BLUETOOTH_CONNECT)
    public boolean writeCharacteristic(BluetoothGattCharacteristic characteristic) {
        try {
            return writeCharacteristic(
                            characteristic,
                            characteristic.getValue(),
                            characteristic.getWriteType())
                    == BluetoothStatusCodes.SUCCESS;
        } catch (Exception e) {
            return false;
        }
    }

    @Hide
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(
            value = {
                BluetoothStatusCodes.SUCCESS,
                BluetoothStatusCodes.ERROR_MISSING_BLUETOOTH_CONNECT_PERMISSION,
                BluetoothStatusCodes.ERROR_DEVICE_NOT_CONNECTED,
                BluetoothStatusCodes.ERROR_PROFILE_SERVICE_NOT_BOUND,
                BluetoothStatusCodes.ERROR_GATT_WRITE_NOT_ALLOWED,
                BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY,
                BluetoothStatusCodes.ERROR_UNKNOWN
            })
    public @interface WriteOperationReturnValues {}

    /**
     * Writes a given characteristic and its values to the associated remote device.
     *
     * <p>Once the write operation has been completed, the {@link
     * BluetoothGattCallback#onCharacteristicWrite} callback is invoked, reporting the result of the
     * operation.
     *
     * @param characteristic Characteristic to write on the remote device
     * @return whether the characteristic was successfully written to
     * @throws IllegalArgumentException if characteristic or value are null
     */
    @RequiresBluetoothConnectPermission
    @RequiresPermission(BLUETOOTH_CONNECT)
    @WriteOperationReturnValues
    public int writeCharacteristic(
            @NonNull BluetoothGattCharacteristic characteristic,
            @NonNull byte[] value,
            @WriteType int writeType) {
        if (characteristic == null) {
            throw new IllegalArgumentException("characteristic must not be null");
        }
        if (value == null) {
            throw new IllegalArgumentException("value must not be null");
        }
        if (value.length > bluetooth.constants.Core.GATT_MAX_ATTR_LEN) {
            throw new IllegalArgumentException(
                    "value should not be longer than max length of an attribute value");
        }
        if (VDBG) Log.d(TAG, "writeCharacteristic() - uuid: " + characteristic.getUuid());
        if ((characteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE) == 0
                && (characteristic.getProperties()
                                & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)
                        == 0) {
            return BluetoothStatusCodes.ERROR_GATT_WRITE_NOT_ALLOWED;
        }
        if (!mClientRegistered) {
            return BluetoothStatusCodes.ERROR_PROFILE_SERVICE_NOT_BOUND;
        }

        BluetoothGattService service = characteristic.getService();
        if (service == null) {
            throw new IllegalArgumentException("Characteristic must have a non-null service");
        }

        BluetoothDevice device = service.getDevice();
        if (device == null) {
            throw new IllegalArgumentException("Service must have a non-null device");
        }

        synchronized (mDeviceBusyLock) {
            if (mDeviceBusy) {
                return BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY;
            }
            mDeviceBusy = true;
        }

        int requestStatus = BluetoothStatusCodes.ERROR_UNKNOWN;
        try {
            for (int i = 0; i < WRITE_CHARACTERISTIC_MAX_RETRIES; i++) {
                requestStatus =
                        mService.writeCharacteristic(
                                mBluetoothGattCallback,
                                device,
                                characteristic.getInstanceId(),
                                writeType,
                                AUTHENTICATION_NONE,
                                value,
                                mAttributionSource);
                if (requestStatus != BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY) {
                    break;
                }
                try {
                    Thread.sleep(WRITE_CHARACTERISTIC_TIME_TO_WAIT);
                } catch (InterruptedException e) {
                    Log.e(TAG, "", e);
                }
            }
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
            synchronized (mDeviceBusyLock) {
                mDeviceBusy = false;
            }
            throw e.rethrowAsRuntimeException();
        }
        if (requestStatus != BluetoothStatusCodes.SUCCESS) {
            synchronized (mDeviceBusyLock) {
                mDeviceBusy = false;
            }
        }

        return requestStatus;
    }

    /**
     * Reads the value for a given descriptor from the associated remote device.
     *
     * <p>Once the read operation has been completed, the {@link
     * BluetoothGattCallback#onDescriptorRead} callback is triggered, signaling the result of the
     * operation.
     *
     * @param descriptor Descriptor value to read from the remote device
     * @return true, if the read operation was initiated successfully
     */
    @RequiresLegacyBluetoothPermission
    @RequiresBluetoothConnectPermission
    @RequiresPermission(BLUETOOTH_CONNECT)
    public boolean readDescriptor(BluetoothGattDescriptor descriptor) {
        if (VDBG) Log.d(TAG, "readDescriptor() - uuid: " + descriptor.getUuid());
        if (!mClientRegistered) return false;

        BluetoothGattCharacteristic characteristic = descriptor.getCharacteristic();
        if (characteristic == null) return false;

        BluetoothGattService service = characteristic.getService();
        if (service == null) return false;

        BluetoothDevice device = service.getDevice();
        if (device == null) return false;

        synchronized (mDeviceBusyLock) {
            if (mDeviceBusy) return false;
            mDeviceBusy = true;
        }

        try {
            mService.readDescriptor(
                    mBluetoothGattCallback,
                    device,
                    descriptor.getInstanceId(),
                    AUTHENTICATION_NONE,
                    mAttributionSource);
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
            synchronized (mDeviceBusyLock) {
                mDeviceBusy = false;
            }
            return false;
        }

        return true;
    }

    /**
     * Write the value of a given descriptor to the associated remote device.
     *
     * <p>A {@link BluetoothGattCallback#onDescriptorWrite} callback is triggered to report the
     * result of the write operation.
     *
     * @param descriptor Descriptor to write to the associated remote device
     * @return true, if the write operation was initiated successfully
     * @throws IllegalArgumentException if descriptor or its value are null
     * @deprecated Use {@link BluetoothGatt#writeDescriptor(BluetoothGattDescriptor, byte[])} as
     *     this is not memory safe because it relies on a {@link BluetoothGattDescriptor} object
     *     whose underlying fields are subject to change outside this method.
     */
    @Deprecated
    @RequiresLegacyBluetoothPermission
    @RequiresBluetoothConnectPermission
    @RequiresPermission(BLUETOOTH_CONNECT)
    public boolean writeDescriptor(BluetoothGattDescriptor descriptor) {
        try {
            return writeDescriptor(descriptor, descriptor.getValue())
                    == BluetoothStatusCodes.SUCCESS;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Write the value of a given descriptor to the associated remote device.
     *
     * <p>A {@link BluetoothGattCallback#onDescriptorWrite} callback is triggered to report the
     * result of the write operation.
     *
     * @param descriptor Descriptor to write to the associated remote device
     * @return true, if the write operation was initiated successfully
     * @throws IllegalArgumentException if descriptor or value are null
     */
    @RequiresBluetoothConnectPermission
    @RequiresPermission(BLUETOOTH_CONNECT)
    @WriteOperationReturnValues
    public int writeDescriptor(@NonNull BluetoothGattDescriptor descriptor, @NonNull byte[] value) {
        if (descriptor == null) {
            throw new IllegalArgumentException("descriptor must not be null");
        }
        if (value == null) {
            throw new IllegalArgumentException("value must not be null");
        }
        if (VDBG) Log.d(TAG, "writeDescriptor() - uuid: " + descriptor.getUuid());
        if (!mClientRegistered) {
            return BluetoothStatusCodes.ERROR_PROFILE_SERVICE_NOT_BOUND;
        }

        BluetoothGattCharacteristic characteristic = descriptor.getCharacteristic();
        if (characteristic == null) {
            throw new IllegalArgumentException("Descriptor must have a non-null characteristic");
        }

        BluetoothGattService service = characteristic.getService();
        if (service == null) {
            throw new IllegalArgumentException("Characteristic must have a non-null service");
        }

        BluetoothDevice device = service.getDevice();
        if (device == null) {
            throw new IllegalArgumentException("Service must have a non-null device");
        }

        synchronized (mDeviceBusyLock) {
            if (mDeviceBusy) return BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY;
            mDeviceBusy = true;
        }

        try {
            return mService.writeDescriptor(
                    mBluetoothGattCallback,
                    device,
                    descriptor.getInstanceId(),
                    AUTHENTICATION_NONE,
                    value,
                    mAttributionSource);
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
            synchronized (mDeviceBusyLock) {
                mDeviceBusy = false;
            }
            throw e.rethrowAsRuntimeException();
        }
    }

    /**
     * Initiates a reliable write transaction for a given remote device.
     *
     * <p>Once a reliable write transaction has been initiated, all calls to {@link
     * #writeCharacteristic} are sent to the remote device for verification and queued up for atomic
     * execution. The application will receive a {@link BluetoothGattCallback#onCharacteristicWrite}
     * callback in response to every {@link #writeCharacteristic(BluetoothGattCharacteristic,
     * byte[], int)} call and is responsible for verifying if the value has been transmitted
     * accurately.
     *
     * <p>After all characteristics have been queued up and verified, {@link #executeReliableWrite}
     * will execute all writes. If a characteristic was not written correctly, calling {@link
     * #abortReliableWrite} will cancel the current transaction without committing any values on the
     * remote device.
     *
     * @return true, if the reliable write transaction has been initiated
     */
    @RequiresLegacyBluetoothPermission
    @RequiresBluetoothConnectPermission
    @RequiresPermission(BLUETOOTH_CONNECT)
    public boolean beginReliableWrite() {
        if (VDBG) Log.d(TAG, "beginReliableWrite() - device: " + mDevice);
        if (!mClientRegistered) return false;

        try {
            mService.beginReliableWrite(mDevice, mAttributionSource);
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
            return false;
        }

        return true;
    }

    /**
     * Executes a reliable write transaction for a given remote device.
     *
     * <p>This function will commit all queued up characteristic write operations for a given remote
     * device.
     *
     * <p>A {@link BluetoothGattCallback#onReliableWriteCompleted} callback is invoked to indicate
     * whether the transaction has been executed correctly.
     *
     * @return true, if the request to execute the transaction has been sent
     */
    @RequiresLegacyBluetoothPermission
    @RequiresBluetoothConnectPermission
    @RequiresPermission(BLUETOOTH_CONNECT)
    public boolean executeReliableWrite() {
        if (VDBG) Log.d(TAG, "executeReliableWrite() - device: " + mDevice);
        if (!mClientRegistered) return false;

        synchronized (mDeviceBusyLock) {
            if (mDeviceBusy) return false;
            mDeviceBusy = true;
        }

        try {
            mService.endReliableWrite(mBluetoothGattCallback, mDevice, true, mAttributionSource);
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
            synchronized (mDeviceBusyLock) {
                mDeviceBusy = false;
            }
            return false;
        }

        return true;
    }

    /**
     * Cancels a reliable write transaction for a given device.
     *
     * <p>Calling this function will discard all queued characteristic write operations for a given
     * remote device.
     */
    @RequiresLegacyBluetoothPermission
    @RequiresBluetoothConnectPermission
    @RequiresPermission(BLUETOOTH_CONNECT)
    public void abortReliableWrite() {
        if (VDBG) Log.d(TAG, "abortReliableWrite() - device: " + mDevice);
        if (!mClientRegistered) return;

        try {
            mService.endReliableWrite(mBluetoothGattCallback, mDevice, false, mAttributionSource);
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
        }
    }

    /**
     * @deprecated Use {@link #abortReliableWrite()}
     */
    @Deprecated
    @RequiresBluetoothConnectPermission
    @RequiresPermission(BLUETOOTH_CONNECT)
    public void abortReliableWrite(BluetoothDevice mDevice) {
        abortReliableWrite();
    }

    /**
     * Enable or disable notifications/indications for a given characteristic.
     *
     * <p>Once notifications are enabled for a characteristic, a {@link
     * BluetoothGattCallback#onCharacteristicChanged(BluetoothGatt, BluetoothGattCharacteristic,
     * byte[])} callback will be triggered if the remote device indicates that the given
     * characteristic has changed.
     *
     * @param characteristic The characteristic for which to enable notifications
     * @param enable Set to true to enable notifications/indications
     * @return true, if the requested notification status was set successfully
     */
    @RequiresLegacyBluetoothPermission
    @RequiresBluetoothConnectPermission
    @RequiresPermission(BLUETOOTH_CONNECT)
    public boolean setCharacteristicNotification(
            BluetoothGattCharacteristic characteristic, boolean enable) {
        Log.d(
                TAG,
                "setCharacteristicNotification() - uuid: "
                        + characteristic.getUuid()
                        + " enable: "
                        + enable);
        if (!mClientRegistered) return false;

        BluetoothGattService service = characteristic.getService();
        if (service == null) return false;

        BluetoothDevice device = service.getDevice();
        if (device == null) return false;

        try {
            mService.registerForNotification(
                    mBluetoothGattCallback,
                    device,
                    characteristic.getInstanceId(),
                    enable,
                    mAttributionSource);
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
            return false;
        }

        return true;
    }

    /** Clears the internal cache and forces a refresh of the services from the remote device. */
    @Hide
    @UnsupportedAppUsage
    @RequiresBluetoothConnectPermission
    @RequiresPermission(BLUETOOTH_CONNECT)
    public boolean refresh() {
        Log.d(TAG, "refresh() - device: " + mDevice);
        if (!mClientRegistered) return false;

        try {
            mService.refreshDevice(mBluetoothGattCallback, mDevice, mAttributionSource);
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
            return false;
        }

        return true;
    }

    /**
     * Read the RSSI for a connected remote device.
     *
     * <p>The {@link BluetoothGattCallback#onReadRemoteRssi} callback will be invoked when the RSSI
     * value has been read.
     *
     * @return true, if the RSSI value has been requested successfully
     */
    @RequiresLegacyBluetoothPermission
    @RequiresBluetoothConnectPermission
    @RequiresPermission(BLUETOOTH_CONNECT)
    public boolean readRemoteRssi() {
        Log.d(TAG, "readRssi() - device: " + mDevice);
        if (!mClientRegistered) return false;

        try {
            return mService.readRemoteRssi(mBluetoothGattCallback, mDevice, mAttributionSource);
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
            return false;
        }
    }

    /**
     * Request an MTU size used for a given connection. Please note that starting from Android 14,
     * the Android Bluetooth stack requests the BLE ATT MTU to 517 bytes when the first GATT client
     * requests an MTU, and disregards all subsequent MTU requests. Check out <a
     * href="{@docRoot}about/versions/14/behavior-changes-all#mtu-set-to-517">MTU is set to 517 for
     * the first GATT client requesting an MTU</a> for more information.
     *
     * <p>When performing a write request operation (write without response), the data sent is
     * truncated to the MTU size. This function may be used to request a larger MTU size to be able
     * to send more data at once.
     *
     * <p>A {@link BluetoothGattCallback#onMtuChanged} callback will indicate whether this operation
     * was successful.
     *
     * @return true, if the new MTU value has been requested successfully
     */
    @RequiresLegacyBluetoothPermission
    @RequiresBluetoothConnectPermission
    @RequiresPermission(BLUETOOTH_CONNECT)
    public boolean requestMtu(int mtu) {
        Log.d(TAG, "configureMTU() - device: " + mDevice + " mtu: " + mtu);
        if (!mClientRegistered) return false;

        try {
            mService.configureMTU(mBluetoothGattCallback, mDevice, mtu, mAttributionSource);
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
            return false;
        }

        return true;
    }

    /**
     * Request a connection parameter update.
     *
     * <p>This function will send a connection parameter update request to the remote device.
     *
     * @param connectionPriority Request a specific connection priority. Must be one of {@link
     *     BluetoothGatt#CONNECTION_PRIORITY_BALANCED}, {@link
     *     BluetoothGatt#CONNECTION_PRIORITY_HIGH} {@link
     *     BluetoothGatt#CONNECTION_PRIORITY_LOW_POWER}, or {@link
     *     BluetoothGatt#CONNECTION_PRIORITY_DCK}.
     * @throws IllegalArgumentException If the parameters are outside of their specified range.
     */
    @RequiresBluetoothConnectPermission
    @RequiresPermission(BLUETOOTH_CONNECT)
    public boolean requestConnectionPriority(int connectionPriority) {
        if (connectionPriority < CONNECTION_PRIORITY_BALANCED
                || connectionPriority > CONNECTION_PRIORITY_DCK) {
            throw new IllegalArgumentException("connectionPriority not within valid range");
        }

        Log.d(TAG, "requestConnectionPriority() - params: " + connectionPriority);
        if (!mClientRegistered) return false;

        try {
            mService.connectionParameterUpdate(
                    mBluetoothGattCallback, mDevice, connectionPriority, mAttributionSource);
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
            return false;
        }

        return true;
    }

    /**
     * Request an LE connection parameter update.
     *
     * <p>This function will send an LE connection parameters update request to the remote device.
     *
     * @return true, if the request is send to the Bluetooth stack.
     */
    @Hide
    @RequiresBluetoothConnectPermission
    @RequiresPermission(BLUETOOTH_CONNECT)
    public boolean requestLeConnectionUpdate(
            int minConnectionInterval,
            int maxConnectionInterval,
            int slaveLatency,
            int supervisionTimeout,
            int minConnectionEventLen,
            int maxConnectionEventLen) {
        Log.d(
                TAG,
                "requestLeConnectionUpdate() - min=("
                        + minConnectionInterval
                        + ")"
                        + (1.25 * minConnectionInterval)
                        + "msec, max=("
                        + maxConnectionInterval
                        + ")"
                        + (1.25 * maxConnectionInterval)
                        + "msec, latency="
                        + slaveLatency
                        + ", timeout="
                        + supervisionTimeout
                        + "msec"
                        + ", min_ce="
                        + minConnectionEventLen
                        + ", max_ce="
                        + maxConnectionEventLen);
        if (!mClientRegistered) return false;

        try {
            mService.leConnectionUpdate(
                    mBluetoothGattCallback,
                    mDevice,
                    minConnectionInterval,
                    maxConnectionInterval,
                    slaveLatency,
                    supervisionTimeout,
                    minConnectionEventLen,
                    maxConnectionEventLen,
                    mAttributionSource);
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
            return false;
        }

        return true;
    }

    /** Subrate request return values. */
    @Hide
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(
            prefix = {"SUBRATE_REQUEST_RETURN"},
            value = {
                BluetoothStatusCodes.SUCCESS,
                BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ENABLED,
                BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ALLOWED,
                BluetoothStatusCodes.ERROR_MISSING_BLUETOOTH_CONNECT_PERMISSION,
                BluetoothStatusCodes.ERROR_DEVICE_NOT_BONDED,
                BluetoothStatusCodes.FEATURE_NOT_SUPPORTED,
                BluetoothStatusCodes.ERROR_UNKNOWN
            })
    public @interface SubrateRequestReturnValues {}

    /**
     * Request LE subrate mode.
     *
     * <p>Configure/Request subrating with this API, sending a subrate request to the remote device
     * based on Subrate Mode. This function should be used in conjunction with {@link
     * requestConnectionPriority} to manage link latency and power consumption effectively.
     *
     * <p>This method requires the calling app to have the {@link
     * android.Manifest.permission#BLUETOOTH_CONNECT} permission. Additionally, an app must either
     * have the {@link android.Manifest.permission#BLUETOOTH_PRIVILEGED} or be associated with the
     * Companion Device manager (see {@link android.companion.CompanionDeviceManager#associate(
     * AssociationRequest, android.companion.CompanionDeviceManager.Callback, Handler)})
     *
     * @param subrateMode Request a specific subrate mode.
     * @throws IllegalArgumentException If the parameters are outside of their specified range.
     * @return true, if the request is send to the Bluetooth stack.
     */
    @RequiresBluetoothConnectPermission
    @RequiresPermission(
            allOf = {BLUETOOTH_CONNECT, BLUETOOTH_PRIVILEGED},
            conditional = true)
    public @SubrateRequestReturnValues int requestSubrateMode(@SubrateMode int subrateMode) {
        if (subrateMode < BluetoothGatt.SUBRATE_MODE_OFF
                || subrateMode > BluetoothGatt.SUBRATE_MODE_HIGH) {
            throw new IllegalArgumentException("Subrate Mode not within valid range");
        }

        Log.d(TAG, "requestsubrateMode(" + subrateMode + ")");
        if (!mClientRegistered) {
            return BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ENABLED;
        }

        try {
            return mService.subrateModeRequest(
                    mBluetoothGattCallback, mDevice, subrateMode, mAttributionSource);
        } catch (RemoteException e) {
            logRemoteException(TAG, e);
            return BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ENABLED;
        }
    }

    /**
     * Initiates an offload of a specified list of {@link BluetoothGattCharacteristic}s to an
     * endpoint.
     *
     * <p>This method enables a GATT Client application to delegate the handling of specific
     * attribute handles associated with the provided {@code characteristics} to a endpoint after
     * service discovery is complete.
     *
     * <p>This is an asynchronous operation. The result of the offload attempt, including the {@link
     * GattOffloadSession} object on success, will be delivered via the {@link
     * BluetoothGattCallback#onCharacteristicsOffloaded} callback.
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
     * <p>The offload session is terminated if the GATT database is invalidated. When {@link
     * BluetoothGattCallback#onCharacteristicsUnoffloaded} is invoked, the application is
     * responsible for re-establishing an offload session by calling this method again if needed.
     *
     * @param service The {@link BluetoothGattService} that contains the characteristics to be
     *     offloaded. This service object should be obtained from the GATT server.
     * @param characteristics A list of {@link BluetoothGattCharacteristic}s from the specified
     *     {@code service} that are to be offloaded.
     * @param endpointId The unique identifier of the target endpoint within the hub.
     * @param hubId The unique identifier of the hub to which the endpoint belongs.
     * @return An integer status code indicating the immediate result of the request, such as {@link
     *     GattOffloadSession#STATUS_SUCCESS} if the request was initiated successfully. A non-zero
     *     status indicates an immediate failure to start the operation.
     * @throws IllegalArgumentException if the service or characteristics are not valid.
     * @throws IllegalStateException if GATT client offload is not supported.
     */
    @Hide
    @SystemApi
    @FlaggedApi(Flags.FLAG_GATT_OFFLOAD_API)
    @RequiresBluetoothConnectPermission
    @RequiresPermission(allOf = {BLUETOOTH_CONNECT, BLUETOOTH_PRIVILEGED})
    public @GattOffloadSession.Status int offloadCharacteristics(
            @NonNull BluetoothGattService service,
            @NonNull List<BluetoothGattCharacteristic> characteristics,
            long endpointId,
            long hubId) {
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
                    mService.offloadClientCharacteristics(
                            mBluetoothGattCallback,
                            mDevice,
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
            ((GattCallback) mBluetoothGattCallback)
                    .onCharacteristicsOffloaded(
                            mDevice,
                            new GattOffloadSession(
                                    requireNonNull(sessionParcel).getSessionId(),
                                    mDevice,
                                    this,
                                    null,
                                    service,
                                    characteristics,
                                    endpointId,
                                    hubId),
                            status);
        }
        return status;
    }

    /**
     * Stops the offloading of characteristics associated with a given offload session.
     *
     * @param session The offload session to be terminated. This was returned by a previous
     *     successful call to {@link #offloadCharacteristics}.
     * @throws IllegalArgumentException if session id is not valid
     * @throws IllegalStateException if BluetoothGatt service not available.
     */
    @Hide
    @FlaggedApi(Flags.FLAG_GATT_OFFLOAD_API)
    @RequiresBluetoothConnectPermission
    @RequiresPermission(allOf = {BLUETOOTH_CONNECT, BLUETOOTH_PRIVILEGED})
    public void unoffloadCharacteristics(@NonNull GattOffloadSession session) {
        int sessionId = session.getSessionId();
        Log.d(TAG, "unoffloadCharacteristics sessionId= " + sessionId);
        if (sessionId == GattOffloadSession.OFFLOAD_SESSION_ID_UNKNOWN) {
            throw new IllegalArgumentException("session id is not valid");
        }
        try {
            mService.unoffloadClientCharacteristics(
                    mBluetoothGattCallback, mDevice, sessionId, mAttributionSource);
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
        }
    }

    /**
     * @deprecated Not supported - please use {@link BluetoothManager#getConnectedDevices(int)} with
     *     {@link BluetoothProfile#GATT} as argument
     * @throws UnsupportedOperationException on every call
     */
    @Override
    @RequiresNoPermission
    @Deprecated
    public int getConnectionState(BluetoothDevice device) {
        throw new UnsupportedOperationException("Use BluetoothManager#getConnectionState instead.");
    }

    /**
     * @deprecated Not supported - please use {@link BluetoothManager#getConnectedDevices(int)} with
     *     {@link BluetoothProfile#GATT} as argument
     * @throws UnsupportedOperationException on every call
     */
    @Override
    @RequiresNoPermission
    @Deprecated
    public List<BluetoothDevice> getConnectedDevices() {
        throw new UnsupportedOperationException(
                "Use BluetoothManager#getConnectedDevices instead.");
    }

    /**
     * @deprecated Not supported - please use {@link
     *     BluetoothManager#getDevicesMatchingConnectionStates(int, int[])} with {@link
     *     BluetoothProfile#GATT} as first argument
     * @throws UnsupportedOperationException on every call
     */
    @Override
    @RequiresNoPermission
    @Deprecated
    public List<BluetoothDevice> getDevicesMatchingConnectionStates(int[] states) {
        throw new UnsupportedOperationException(
                "Use BluetoothManager#getDevicesMatchingConnectionStates instead.");
    }
}
