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

/**
 * Listener to monitor serial port connections and disconnections.
 */
@FlaggedApi(android.hardware.serial.flags.Flags.FLAG_ENABLE_WIRED_SERIAL_API)
public interface SerialPortListener {
    /**
     * Called when a supported serial port is connected.
     */
    void onSerialPortConnected(@NonNull SerialPort port);

    /**
     * Called when a supported serial port is disconnected.
     */
    void onSerialPortDisconnected(@NonNull SerialPort port);
}
