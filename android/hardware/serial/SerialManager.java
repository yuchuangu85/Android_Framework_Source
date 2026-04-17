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

package android.hardware.serial;

import static android.content.pm.PackageManager.PERMISSION_GRANTED;

import android.Manifest;
import android.annotation.CallbackExecutor;
import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemService;
import android.annotation.TestApi;
import android.content.Context;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.ArrayMap;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.os.BackgroundThread;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * This class allows you to communicate with Serial ports.
 */
@SystemService(Context.SERIAL_SERVICE)
@FlaggedApi(android.hardware.serial.flags.Flags.FLAG_ENABLE_WIRED_SERIAL_API)
public final class SerialManager extends android.hardware.SerialManager {
    /**
     * The request token for requesting serial port access.
     *
     * @hide
     */
    public static final String EXTRA_REQUEST_TOKEN = "android.hardware.serial.EXTRA_REQUEST_TOKEN";

    /**
     * The name of the serial port.
     *
     * @hide
     */
    public static final String EXTRA_PORT = "android.hardware.serial.EXTRA_PORT";

    /**
     * The package name of the application requesting serial port access.
     *
     * @hide
     */
    public static final String EXTRA_PACKAGE_NAME = "android.hardware.serial.EXTRA_PACKAGE_NAME";

    /**
     * The UID of the user requesting serial port access.
     *
     * @hide
     */
    public static final String EXTRA_UID = "android.hardware.serial.EXTRA_UID";

    private static final String TAG = "SerialManager";
    private static final String DEV_PREFIX = "/dev/";

    private final @NonNull Context mContext;
    private final @NonNull ISerialManager mService;

    @GuardedBy("mLock")
    private SerialPortServiceListener mServiceListener;

    @GuardedBy("mLock")
    private ArrayMap<SerialPortListener, Executor> mListeners;

    private final Object mLock = new Object();

    /** @hide */
    public SerialManager(@NonNull Context context, @NonNull ISerialManager service) {
        // Passing null explicitly because when the new service is running none of the logic in the
        // old client works. Nulls help us find missing overrides.
        super(null, null);
        mContext = context;
        mService = service;
    }

    /**
     * Enumerates serial ports.
     */
    @NonNull
    public List<SerialPort> getPorts() {
        try {
            List<SerialPortInfo> infos = mService.getSerialPorts();
            List<SerialPort> ports = new ArrayList<>(infos.size());
            for (int i = 0; i < infos.size(); i++) {
                ports.add(new SerialPort(mContext, infos.get(i), mService));
            }
            return Collections.unmodifiableList(ports);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Backward compatible {@link android.hardware.SerialManager#getSerialPorts()}.
     * <p>
     * This method only returns paths of ports specified in the system configuration.
     * @hide
     */
    @Override
    @RequiresPermission(android.Manifest.permission.SERIAL_PORT)
    public String[] getSerialPorts() {
        try {
            final String[] names = mService.getSerialPortsInConfig();
            final String[] paths = new String[names.length];
            for (int i = 0; i < names.length; ++i) {
                paths[i] = DEV_PREFIX + names[i];
            }
            return paths;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Backward compatible {@link android.hardware.SerialManager#openSerialPort(String, int)}.
     * <p>
     * This method only allows to open ports specified in the system configuration by apps that have
     * the {@link Manifest.permission#SERIAL_PORT} permission. Any other uses need to go through the
     * public API {@link #getPorts()} and {@link SerialPort}.
     *
     * @param path of the serial port
     * @param speed at which to open the serial port
     * @return the opened port
     * @throws IOException when any operations fail
     * @hide
     */
    @Override
    @RequiresPermission("android.permission.SERIAL_PORT")
    public android.hardware.SerialPort openSerialPort(String path, int speed) throws IOException {
        if (mContext.checkSelfPermission(Manifest.permission.SERIAL_PORT) != PERMISSION_GRANTED) {
            throw new SecurityException(
                    "Access denied: requires " + Manifest.permission.SERIAL_PORT);
        }
        if (!path.startsWith(DEV_PREFIX)) {
            throw new IOException("Could not open serial port " + path);
        }
        final String name = path.substring(DEV_PREFIX.length());
        try {
            boolean found = false;
            final String[] names = mService.getSerialPortsInConfig();
            for (int i = 0; i < names.length; ++i) {
                if (names[i].equals(name)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                throw new IOException("Could not open serial port " + path);
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
        final SerialPortInfo info =
                new SerialPortInfo(name, SerialPort.INVALID_ID, SerialPort.INVALID_ID);
        final SerialPort port = new SerialPort(mContext, info, mService);
        final CompatibleOutcomeReceiver receiver = new CompatibleOutcomeReceiver();
        port.requestOpen(
                SerialPort.OPEN_FLAG_READ_WRITE,
                /* exclusive */ false,
                BackgroundThread.getExecutor(),
                receiver);
        try {
            final SerialPortResponse response = receiver.waitForResult();
            android.hardware.SerialPort compatPort = new android.hardware.SerialPort(name);
            compatPort.open(response.getFileDescriptor(), speed);
            return compatPort;
        } catch (Exception e) {
            switch (e) {
                case IOException ioException -> throw ioException;
                // The new API throws IllegalStateException when the port is not found. The old API
                // throws IOException in such cases.
                case IllegalStateException illegalStateException ->
                        throw new IOException(illegalStateException);
                case RuntimeException runtimeException -> throw runtimeException;
                default -> throw new IOException(e);
            }
        }
    }

    /**
     * Register a listener to monitor serial port connections and disconnections.
     *
     * @throws IllegalStateException if this listener has already been registered.
     */
    public void registerSerialPortListener(@NonNull @CallbackExecutor Executor executor,
            @NonNull SerialPortListener listener) {
        synchronized (mLock) {
            if (mServiceListener == null) {
                mServiceListener = new SerialPortServiceListener();
                try {
                    mService.registerSerialPortListener(mServiceListener);
                } catch (RemoteException e) {
                    throw e.rethrowFromSystemServer();
                }
            }
            if (mListeners == null) {
                mListeners = new ArrayMap<>();
            }
            if (mListeners.containsKey(listener)) {
                throw new IllegalStateException("Listener has already been registered.");
            }
            mListeners.put(listener, executor);
        }
    }

    /**
     * Unregister a listener that monitored serial port connections and disconnections.
     */
    public void unregisterSerialPortListener(@NonNull SerialPortListener listener) {
        synchronized (mLock) {
            if (mListeners == null) {
                return;
            }
            mListeners.remove(listener);
            if (mListeners.isEmpty()) {
                if (mServiceListener != null) {
                    try {
                        mService.unregisterSerialPortListener(mServiceListener);
                    } catch (RemoteException e) {
                        throw e.rethrowFromSystemServer();
                    } finally {
                        // If there was a RemoteException, the system server may have died,
                        // and this listener probably became unregistered, so clear it for
                        // re-registration.
                        mServiceListener = null;
                    }
                }
            }
        }
    }

    /**
     * Grants a specific UID access to a serial port.
     *
     * @param serialPort The name of the serial port.
     * @param uid The user ID to grant access to.
     * @param persistent {@code true} if the grant doesn't expire on disconnection and must be
     *                   revoked in system settings; {@code false} otherwise
     * @param token An optional token associated with the grant.
     *
     * @hide
     */
    @RequiresPermission(Manifest.permission.MANAGE_SERIAL_PORTS)
    @TestApi
    public void grantSerialPortAccess(@NonNull String serialPort, int uid,
            boolean persistent, @Nullable IBinder token) {
        try {
            mService.grantSerialPortAccess(serialPort, uid, persistent, token);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Revokes a specific UID's access to a serial port.
     *
     * @param serialPort The name of the serial port.
     * @param uid The user ID to revoke access from.
     * @param persistent {@code true} if future access requests will be automatically denied until
     *                   users changes the access in system settings
     * @param token An optional token associated with the revocation.
     *
     * @hide
     */
    @RequiresPermission(Manifest.permission.MANAGE_SERIAL_PORTS)
    @TestApi
    public void revokeSerialPortAccess(@NonNull String serialPort, int uid,
            boolean persistent, @Nullable IBinder token) {
        try {
            mService.revokeSerialPortAccess(serialPort, uid, persistent, token);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    private class SerialPortServiceListener extends ISerialPortListener.Stub {
        @Override
        public void onSerialPortConnected(SerialPortInfo info) {
            SerialPort port = new SerialPort(mContext, info, mService);
            synchronized (mLock) {
                for (Map.Entry<SerialPortListener, Executor> e : mListeners.entrySet()) {
                    Executor executor = e.getValue();
                    SerialPortListener listener = e.getKey();
                    try {
                        executor.execute(() -> listener.onSerialPortConnected(port));
                    } catch (RuntimeException e2) {
                        Slog.w(TAG, "Exception in listener", e2);
                    }
                }
            }
        }

        @Override
        public void onSerialPortDisconnected(SerialPortInfo info) {
            SerialPort port = new SerialPort(mContext, info, mService);
            synchronized (mLock) {
                for (Map.Entry<SerialPortListener, Executor> e : mListeners.entrySet()) {
                    Executor executor = e.getValue();
                    SerialPortListener listener = e.getKey();
                    try {
                        executor.execute(() -> listener.onSerialPortDisconnected(port));
                    } catch (RuntimeException e2) {
                        Slog.w(TAG, "Exception in listener", e2);
                    }
                }
            }
        }
    }
}
