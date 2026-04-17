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
import android.os.SystemClock;

/**
 * A builder for creating {@link VirtualGamepadMotionEvent} objects.
 *
 * @hide
 */
public final class VirtualGamepadMotionEventBuilder {
    private float mX = Float.NaN;
    private float mY = Float.NaN;
    private float mZ = Float.NaN;
    private float mRz = Float.NaN;
    private float mHatX = Float.NaN;
    private float mHatY = Float.NaN;
    private float mLTrigger = Float.NaN;
    private float mRTrigger = Float.NaN;
    private long mEventTimeNanos = 0;

    /**
     * Sets the {@link android.view.MotionEvent#AXIS_X X} axis value.
     */
    @NonNull
    public VirtualGamepadMotionEventBuilder setX(float x) {
        mX = x;
        return this;
    }

    /**
     * Sets the {@link android.view.MotionEvent#AXIS_Y Y} axis value.
     */
    @NonNull
    public VirtualGamepadMotionEventBuilder setY(float y) {
        mY = y;
        return this;
    }

    /**
     * Sets the {@link android.view.MotionEvent#AXIS_Z Z} axis value.
     */
    @NonNull
    public VirtualGamepadMotionEventBuilder setZ(float z) {
        mZ = z;
        return this;
    }

    /**
     * Sets the {@link android.view.MotionEvent#AXIS_RZ RZ} axis value.
     */
    @NonNull
    public VirtualGamepadMotionEventBuilder setRz(float rz) {
        mRz = rz;
        return this;
    }

    /**
     * Sets the {@link android.view.MotionEvent#AXIS_HAT_X Hat X} axis value.
     */
    @NonNull
    public VirtualGamepadMotionEventBuilder setHatX(float hatX) {
        mHatX = hatX;
        return this;
    }

    /**
     * Sets the {@link android.view.MotionEvent#AXIS_HAT_Y Hat Y} axis value.
     */
    @NonNull
    public VirtualGamepadMotionEventBuilder setHatY(float hatY) {
        mHatY = hatY;
        return this;
    }

    /**
     * Sets the {@link android.view.MotionEvent#AXIS_LTRIGGER LTRIGGER} axis value.
     * This should not be called if the triggers were not registered in the gamepad config.
     */
    @NonNull
    public VirtualGamepadMotionEventBuilder setLTrigger(float lTrigger) {
        mLTrigger = lTrigger;
        return this;
    }

    /**
     * Sets the {@link android.view.MotionEvent#AXIS_RTRIGGER RTRIGGER} axis value.
     * This should not be called if the triggers were not registered in the gamepad config.
     */
    @NonNull
    public VirtualGamepadMotionEventBuilder setRTrigger(float rTrigger) {
        mRTrigger = rTrigger;
        return this;
    }

    /**
     * Sets the time of the event, in nanoseconds.
     * If not set, {@link SystemClock#uptimeNanos()} will be used.
     */
    @NonNull
    public VirtualGamepadMotionEventBuilder setEventTimeNanos(long eventTimeNanos) {
        mEventTimeNanos = eventTimeNanos;
        return this;
    }

    /**
     * Builds the {@link VirtualGamepadMotionEvent} instance.
     */
    @NonNull
    public VirtualGamepadMotionEvent build() {
        final VirtualGamepadMotionEvent event = new VirtualGamepadMotionEvent();
        event.x = mX;
        event.y = mY;
        event.z = mZ;
        event.rz = mRz;
        event.hatX = mHatX;
        event.hatY = mHatY;
        event.lTrigger = mLTrigger;
        event.rTrigger = mRTrigger;
        event.eventTimeNanos =
                mEventTimeNanos != 0 ? mEventTimeNanos : SystemClock.uptimeNanos();
        return event;
    }
}
