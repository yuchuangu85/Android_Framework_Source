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

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.os.ParcelFileDescriptor;

/**
 * Result of opening a serial port.
 */
@FlaggedApi(android.hardware.serial.flags.Flags.FLAG_ENABLE_WIRED_SERIAL_API)
public final class SerialPortResponse {
    @NonNull
    private final SerialPort mPort;

    @NonNull
    private final ParcelFileDescriptor mFileDescriptor;

    /** @hide */
    SerialPortResponse(@NonNull SerialPort port, @NonNull ParcelFileDescriptor fileDescriptor) {
        mPort = port;
        mFileDescriptor = fileDescriptor;
    }

    /**
     * The serial port for which this response is.
     */
    @NonNull
    public SerialPort getPort() {
        return mPort;
    }

    /**
     * The file descriptor obtained by opening the device node of the serial port.
     *
     * <p>The client of the API is responsible for closing the file descriptor after use.
     */
    @NonNull
    public ParcelFileDescriptor getFileDescriptor() {
        return mFileDescriptor;
    }
}
