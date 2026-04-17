/*
 * Copyright 2024 The Android Open Source Project
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

package android.hardware.input;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.os.RemoteException;
import android.util.Log;
import android.view.InputDevice;

import java.io.Closeable;

/**
 * A virtual rotary encoder representing a scroll input mechanism on a remote device.
 *
 * <p>This registers an {@link android.view.InputDevice} that is interpreted like a
 * physically-connected device and dispatches received events to it.</p>
 *
 * @hide
 */
@SystemApi
public class VirtualRotaryEncoder implements Closeable {

    private static final String TAG = "VirtualRotaryEncoder";

    private final IVirtualRotaryEncoder mVirtualRotaryEncoder;

    private final VirtualRotaryEncoderConfig mConfig;

    /** @hide */
    public VirtualRotaryEncoder(VirtualRotaryEncoderConfig config,
            IVirtualRotaryEncoder virtualRotaryEncoder) {
        mConfig = config;
        mVirtualRotaryEncoder = virtualRotaryEncoder;
    }

    /**
     * Returns the ID of the underlying input device.
     *
     * @return The input device id of this device.
     * @see InputDevice#getId()
     * @hide
     */
    @FlaggedApi(com.android.hardware.input.Flags.FLAG_CREATE_VIRTUAL_KEYBOARD_API)
    @SystemApi
    public int getInputDeviceId() {
        try {
            return mVirtualRotaryEncoder.getInputDeviceId();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Sends a scroll event to the system.
     *
     * @param event the event to send
     */
    public void sendScrollEvent(@NonNull VirtualRotaryEncoderScrollEvent event) {
        try {
            if (!mVirtualRotaryEncoder.sendRotaryEncoderScrollEvent(event)) {
                Log.w(TAG, "Failed to send scroll event from virtual rotary "
                        + mConfig.getInputDeviceName());
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public void close() {
        Log.d(TAG, "Closing virtual rotary encoder " + mConfig.getInputDeviceName());
        try {
            mVirtualRotaryEncoder.close();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public String toString() {
        return mConfig.toString();
    }
}
