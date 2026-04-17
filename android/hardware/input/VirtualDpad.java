/*
 * Copyright (C) 2022 The Android Open Source Project
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
import android.view.KeyEvent;

import java.io.Closeable;
import java.util.Set;

/**
 * A virtual dpad representing a key input mechanism on a remote device.
 *
 * <p>This registers an InputDevice that is interpreted like a physically-connected device and
 * dispatches received events to it.</p>
 *
 * @hide
 */
@SystemApi
public class VirtualDpad implements Closeable {

    private static final Set<Integer> SUPPORTED_KEY_CODES = Set.of(
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER);

    private static final String TAG = "VirtualDpad";

    private final IVirtualDpad mVirtualDpad;

    private final VirtualDpadConfig mConfig;

    /** @hide */
    public VirtualDpad(VirtualDpadConfig config, IVirtualDpad virtualDpad) {
        mConfig = config;
        mVirtualDpad = virtualDpad;
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
            return mVirtualDpad.getInputDeviceId();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Sends a key event to the system.
     *
     * <p>Supported key codes are:
     * <ul>
     *     <li>{@link KeyEvent#KEYCODE_DPAD_UP}</li>
     *     <li>{@link KeyEvent#KEYCODE_DPAD_DOWN}</li>
     *     <li>{@link KeyEvent#KEYCODE_DPAD_LEFT}</li>
     *     <li>{@link KeyEvent#KEYCODE_DPAD_RIGHT}</li>
     *     <li>{@link KeyEvent#KEYCODE_DPAD_CENTER}</li>
     *     <li>{@link KeyEvent#KEYCODE_BACK}</li>
     * </ul>
     *
     * @param event the event to send
     */
    public void sendKeyEvent(@NonNull VirtualKeyEvent event) {
        try {
            if (!SUPPORTED_KEY_CODES.contains(event.getKeyCode())) {
                throw new IllegalArgumentException(
                        "Unsupported key code "
                                + event.getKeyCode()
                                + " sent to a VirtualDpad input device.");
            }
            if (!mVirtualDpad.sendDpadKeyEvent(event)) {
                Log.w(TAG, "Failed to send key event to virtual dpad "
                        + mConfig.getInputDeviceName());
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns whether the given key code is supported by this Dpad device.
     * @hide
     */
    public static boolean isKeyCodeSupported(int keyCode) {
        return SUPPORTED_KEY_CODES.contains(keyCode);
    }

    @Override
    public void close() {
        Log.d(TAG, "Closing virtual dpad " + mConfig.getInputDeviceName());
        try {
            mVirtualDpad.close();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public String toString() {
        return mConfig.toString();
    }
}
