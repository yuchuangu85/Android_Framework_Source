/*
 * Copyright (C) 2021 The Android Open Source Project
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
 * A virtual touchscreen representing a touch-based display input mechanism on a remote device.
 *
 * <p>This registers an InputDevice that is interpreted like a physically-connected device and
 * dispatches received events to it.</p>
 *
 * @hide
 */
@SystemApi
public class VirtualTouchscreen implements Closeable {

    private static final String TAG = "VirtualTouchscreen";

    private final IVirtualTouchscreen mVirtualTouchscreen;

    private final VirtualTouchscreenConfig mConfig;

    /** @hide */
    public VirtualTouchscreen(VirtualTouchscreenConfig config,
            IVirtualTouchscreen virtualTouchscreen) {
        mConfig = config;
        mVirtualTouchscreen = virtualTouchscreen;
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
            return mVirtualTouchscreen.getInputDeviceId();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Sends a touch event to the system.
     *
     * @param event the event to send
     */
    public void sendTouchEvent(@NonNull VirtualTouchEvent event) {
        try {
            if (!mVirtualTouchscreen.sendTouchEvent(event)) {
                Log.w(TAG, "Failed to send touch event to virtual touchscreen "
                        + mConfig.getInputDeviceName());
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public void close() {
        Log.d(TAG, "Closing virtual touchscreen " + mConfig.getInputDeviceName());
        try {
            mVirtualTouchscreen.close();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public String toString() {
        return mConfig.toString();
    }
}
