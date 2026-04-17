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

import static java.lang.annotation.RetentionPolicy.SOURCE;

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.content.Context;
import android.os.OutcomeReceiver;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;

import java.io.IOException;
import java.lang.annotation.Retention;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * A class representing a Serial port.
 */
@FlaggedApi(android.hardware.serial.flags.Flags.FLAG_ENABLE_WIRED_SERIAL_API)
public final class SerialPort {
    /**
     * Value returned by {@link #getVendorId()} and {@link #getProductId()} if this
     * serial port isn't a USB device.
     */
    public static final int INVALID_ID = -1;

    /** @hide */
    @Retention(SOURCE)
    @IntDef(flag = true, prefix = {"OPEN_FLAG_"}, value = {OPEN_FLAG_READ_ONLY,
            OPEN_FLAG_WRITE_ONLY, OPEN_FLAG_READ_WRITE, OPEN_FLAG_NONBLOCK, OPEN_FLAG_DATA_SYNC,
            OPEN_FLAG_SYNC})
    public @interface OpenFlags {}

    // Note: for FLAG_* constants we use the current "typical" values of the corresponding
    // OsConstants.O_*: they depend on the Linux distro and can differ for exotic distros.
    // We decided not to use an independent set of constants, because it might overlap with the O_*
    // constants, and a user might by mistake use O_* constants with unpredictable results.

    /**
     * For use with {@link #requestOpen}: open for reading only.
     */
    public static final int OPEN_FLAG_READ_ONLY = 0;

    /**
     * For use with {@link #requestOpen}: open for writing only.
     */
    public static final int OPEN_FLAG_WRITE_ONLY = 1;

    /**
     * For use with {@link #requestOpen}: open for reading and writing.
     */
    public static final int OPEN_FLAG_READ_WRITE = 1 << 1;

    /**
     * For use with {@link #requestOpen}: when possible, the file is opened in nonblocking mode.
     */
    public static final int OPEN_FLAG_NONBLOCK = 1 << 11;

    /**
     * For use with {@link #requestOpen}: write operations on the file will complete according to
     * the requirements of synchronized I/O data integrity completion (while file metadata may not
     * be synchronized).
     */
    public static final int OPEN_FLAG_DATA_SYNC = 1 << 12;

    /**
     * For use with {@link #requestOpen}: write operations on the file will complete according to
     * the requirements of synchronized I/O file integrity completion (by contrast with the
     * synchronized I/O data integrity completion provided by FLAG_DATA_SYNC).
     */
    public static final int OPEN_FLAG_SYNC = 1 << 20;

    private final @NonNull Context mContext;
    private final @NonNull SerialPortInfo mInfo;
    private final @NonNull ISerialManager mService;

    /** @hide */
    public SerialPort(@NonNull Context context, @NonNull SerialPortInfo info,
            @NonNull ISerialManager service) {
        mContext = context;
        mInfo = info;
        mService = service;
    }

    /**
     * Get the device name. It is the dev node name under /dev, e.g. ttyUSB0, ttyACM1.
     */
    public @NonNull String getName() {
        return mInfo.getName();
    }

    /**
     * Return the vendor ID of this serial port if it is a USB device. Otherwise, it
     * returns {@link #INVALID_ID}.
     */
    public int getVendorId() {
        return mInfo.getVendorId();
    }

    /**
     * Return the product ID of this serial port if it is a USB device. Otherwise, it
     * returns {@link #INVALID_ID}.
     */
    public int getProductId() {
        return mInfo.getProductId();
    }

    /**
     * Request to open the port.
     *
     * <p>Exceptions passed to {@code receiver} may be
     * <ul>
     * <li> {@link IllegalStateException} if the port is not found.</li>
     * <li> {@link IOException} if the port cannot be opened.</li>
     * <li> {@link SecurityException} if the user rejects the open request.</li>
     * </ul>
     *
     * @param flags     open flags that define read/write mode and other options.
     * @param exclusive whether to request exclusive access to the port, preventing other processes
     *                  from opening it.
     * @param executor  the executor used to run receiver
     * @param receiver  the outcome receiver
     * @throws IllegalArgumentException if the set of flags is not correct.
     * @throws NullPointerException     if any parameters are {@code null}.
     */
    public void requestOpen(@OpenFlags int flags, boolean exclusive, @NonNull Executor executor,
            @NonNull OutcomeReceiver<SerialPortResponse, Exception> receiver) {
        Objects.requireNonNull(executor, "Executor must not be null");
        Objects.requireNonNull(receiver, "Receiver must not be null");
        try {
            mService.requestOpen(mInfo.getName(), flags, exclusive, mContext.getPackageName(),
                    new SerialPortResponseCallback(executor, receiver));
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public String toString() {
        return String.format("SerialPort{name=%s, vid=%04x, pid=%04x}", mInfo.getName(),
                mInfo.getVendorId(), mInfo.getProductId());
    }

    private class SerialPortResponseCallback extends ISerialPortResponseCallback.Stub {

        private final @NonNull Executor mExecutor;
        private final @NonNull OutcomeReceiver<SerialPortResponse, Exception> mReceiver;

        private SerialPortResponseCallback(@NonNull Executor executor,
                @NonNull OutcomeReceiver<SerialPortResponse, Exception> receiver) {
            mExecutor = executor;
            mReceiver = receiver;
        }

        @Override
        public void onResult(SerialPortInfo info, ParcelFileDescriptor fileDescriptor) {
            mExecutor.execute(() -> mReceiver.onResult(
                    new SerialPortResponse(SerialPort.this, fileDescriptor)));
        }

        @Override
        public void onError(@ErrorCode int errorCode, String message) {
            mExecutor.execute(() -> mReceiver.onError(getException(errorCode, message)));
        }

        @NonNull
        private static Exception getException(int errorCode, String message) {
            return switch (errorCode) {
                case ErrorCode.ERROR_PORT_NOT_FOUND -> new IllegalStateException(message);
                case ErrorCode.ERROR_ACCESS_DENIED -> new SecurityException(message);
                case ErrorCode.ERROR_OPENING_PORT -> new IOException(message);
                default -> new IllegalStateException(
                        "errorCode=" + errorCode + ", message=" + message);
            };
        }
    }
}
