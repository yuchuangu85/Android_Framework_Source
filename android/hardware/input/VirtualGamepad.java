/*
 * Copyright 2025 The Android Open Source Project
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

import android.annotation.NonNull;
import android.os.RemoteException;
import android.util.Log;
import android.view.KeyEvent;

import java.util.Set;

/**
 * A virtual gamepad that allows the user to emulate a physical gamepad, with buttons and sticks.
 * Supports events that are produced by most common gamepads.
 *
 * <p>This registers an InputDevice that is interpreted like a physically-connected device and
 * dispatches received events to it.</p>
 *
 * @hide
 */
public class VirtualGamepad implements AutoCloseable {

    private static final String TAG = "VirtualGamepad";

    /**
     * The set of keycodes supported by virtual gamepads.
     */
    public static final Set<Integer> SUPPORTED_KEY_CODES = Set.of(
            KeyEvent.KEYCODE_BUTTON_A,
            KeyEvent.KEYCODE_BUTTON_B,
            KeyEvent.KEYCODE_BUTTON_X,
            KeyEvent.KEYCODE_BUTTON_Y,
            KeyEvent.KEYCODE_BUTTON_L1,
            KeyEvent.KEYCODE_BUTTON_R1,
            KeyEvent.KEYCODE_BUTTON_L2,
            KeyEvent.KEYCODE_BUTTON_R2,
            KeyEvent.KEYCODE_BUTTON_THUMBL,
            KeyEvent.KEYCODE_BUTTON_THUMBR,
            KeyEvent.KEYCODE_BUTTON_START,
            KeyEvent.KEYCODE_BUTTON_SELECT,
            KeyEvent.KEYCODE_BUTTON_MODE);

    private final IVirtualGamepad mVirtualGamepad;
    private final VirtualGamepadConfig mConfig;

    public VirtualGamepad(VirtualGamepadConfig config, IVirtualGamepad virtualGamepad) {
        mConfig = config;
        mVirtualGamepad = virtualGamepad;
    }

    /**
     * Sends a key event to the system. Only certain keys are allowed.
     * See {@link #SUPPORTED_KEY_CODES} for a complete list.
     *
     * An IllegalArgumentException will be thrown if an unsupported key is injected.
     *
     * @param event the event to send
     */
    public void sendKeyEvent(@NonNull VirtualKeyEvent event) {
        try {
            if (!mVirtualGamepad.sendGamepadKeyEvent(event)) {
                Log.w(TAG, "Failed to send key event to virtual gamepad "
                        + mConfig.name);
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Sends a motion event to the system. Only certain axes and values are allowed.
     * See {@link #SUPPORTED_AXES} for a complete list of supported axes.
     * Axis values must be within the range of -1.0f to 1.0f.
     *
     * An IllegalArgumentException will be thrown if an unsupported axis or an out-of-range value
     * is injected.
     *
     * @param event the event to send
     */
    public void sendMotionEvent(@NonNull VirtualGamepadMotionEvent event) {
        try {
            if (!mVirtualGamepad.sendGamepadMotionEvent(event)) {
                Log.w(TAG, "Failed to send motion to virtual gamepad "
                        + mConfig.name);
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public void close() {
        try {
            mVirtualGamepad.close();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }
}
