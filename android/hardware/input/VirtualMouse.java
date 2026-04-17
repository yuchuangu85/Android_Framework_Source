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
import android.app.compat.CompatChanges;
import android.compat.annotation.ChangeId;
import android.compat.annotation.EnabledSince;
import android.graphics.PointF;
import android.os.Build;
import android.os.RemoteException;
import android.util.Log;
import android.view.InputDevice;
import android.view.MotionEvent;

import java.io.Closeable;

/**
 * A virtual mouse representing a relative input mechanism on a remote device, such as a mouse or
 * trackpad.
 *
 * <p>This registers an InputDevice that is interpreted like a physically-connected device and
 * dispatches received events to it.</p>
 *
 * @hide
 */
@SystemApi
public class VirtualMouse implements Closeable {

    private static final String TAG = "VirtualMouse";

    /**
     * If enabled, the {@link #getCursorPosition()} API now returns in logical display coordinates
     * instead of in physical display coordinates, which is an old behavior.
     */
    @ChangeId
    @EnabledSince(targetSdkVersion = Build.VERSION_CODES.CUR_DEVELOPMENT)
    static final long VIRTUAL_MOUSE_CURSOR_POTION_IN_LOGICAL_COORDINATES = 431622043;


    private final IVirtualMouse mVirtualMouse;

    private final VirtualMouseConfig mConfig;

    /** @hide */
    public VirtualMouse(VirtualMouseConfig config, IVirtualMouse virtualMouse) {
        mConfig = config;
        mVirtualMouse = virtualMouse;
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
            return mVirtualMouse.getInputDeviceId();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Send a mouse button event to the system.
     *
     * @param event the event
     * @throws IllegalStateException if the display this mouse is associated with is not currently
     * targeted
     */
    public void sendButtonEvent(@NonNull VirtualMouseButtonEvent event) {
        try {
            if (!mVirtualMouse.sendMouseButtonEvent(event)) {
                Log.w(TAG, "Failed to send button event to virtual mouse "
                        + mConfig.getInputDeviceName());
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Sends a scrolling event to the system. See {@link MotionEvent#AXIS_VSCROLL} and
     * {@link MotionEvent#AXIS_SCROLL}.
     *
     * @param event the event
     * @throws IllegalStateException if the display this mouse is associated with is not currently
     * targeted
     */
    public void sendScrollEvent(@NonNull VirtualMouseScrollEvent event) {
        try {
            if (!mVirtualMouse.sendMouseScrollEvent(event)) {
                Log.w(TAG, "Failed to send scroll event to virtual mouse "
                        + mConfig.getInputDeviceName());
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Sends a relative movement event to the system.
     *
     * @param event the event
     * @throws IllegalStateException if the display this mouse is associated with is not currently
     * targeted
     */
    public void sendRelativeEvent(@NonNull VirtualMouseRelativeEvent event) {
        try {
            if (!mVirtualMouse.sendMouseRelativeEvent(event)) {
                Log.w(TAG, "Failed to send relative event to virtual mouse "
                        + mConfig.getInputDeviceName());
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Gets the current cursor position in logical display coordinates in pixels.
     *
     * <p>Note that if {@code VIRTUAL_MOUSE_CURSOR_POTION_IN_LOGICAL_COORDINATES} is disabled,
     * this returns a position in the physical display coordinates instead.
     *
     * @return the position, expressed as x and y coordinates
     * @throws IllegalStateException if the display this mouse is associated with is not currently
     * targeted
     */
    public @NonNull PointF getCursorPosition() {
        try {
            final PointF cursorPosition;
            if (CompatChanges.isChangeEnabled(
                    VIRTUAL_MOUSE_CURSOR_POTION_IN_LOGICAL_COORDINATES)) {
                cursorPosition = mVirtualMouse.getCursorPositionInLogicalDisplay();
            } else {
                cursorPosition = mVirtualMouse.getCursorPositionInPhysicalDisplay();
            }
            // TODO(b/410677781): Returning PointF(NaN, NaN) on invalid displayId is different with
            // what the javadoc states, consider updating this (or the javadoc).
            return cursorPosition != null ? cursorPosition : new PointF(Float.NaN, Float.NaN);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public void close() {
        Log.d(TAG, "Closing virtual mouse " + mConfig.getInputDeviceName());
        try {
            mVirtualMouse.close();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public String toString() {
        return mConfig.toString();
    }
}
