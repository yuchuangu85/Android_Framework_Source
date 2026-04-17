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

package android.view.input;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.os.Handler;
import android.util.Log;
import android.util.SparseArray;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.InputEventCompatProcessor;
import android.view.MotionEvent;
import android.view.MotionEvent.PointerCoords;
import android.view.MotionEvent.PointerProperties;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;

/**
 * This rewrites {@link MotionEvent} to have {@link MotionEvent#TOOL_TYPE_FINGER} and
 * {@link InputDevice.SOURCE_TOUCHSCREEN} if the event is from mouse (or touchpad) if per-app
 * overrides is enabled on the target application.
 *
 * @hide
 */
public class MouseToTouchProcessor extends InputEventCompatProcessor {
    private static final String TAG = MouseToTouchProcessor.class.getSimpleName();

    @IntDef({STATE_AWAITING, STATE_CONVERTING, STATE_NON_PRIMARY_CLICK})
    @Retention(RetentionPolicy.SOURCE)
    private @interface State {}
    private static final int STATE_AWAITING = 0;
    private static final int STATE_CONVERTING = 1;
    private static final int STATE_NON_PRIMARY_CLICK = 2;
    private @State int mState = STATE_AWAITING;

    /**
     * Map the processed event's id to the original event, or null if the event is synthesized
     * in this class.
     */
    private final SparseArray<InputEvent> mModifiedEventMap = new SparseArray<>();

    /**
     * Return {@code true} if this compatibility is required based on the given context.
     *
     * <p>For debugging, you can toggle this by the following command:
     * - adb shell am compat enable|disable OVERRIDE_MOUSE_TO_TOUCH [pkg_name]
     */
    public static boolean isCompatibilityNeeded(Context context) {
        return InputEventCompatHandler.isPcInputCompatibilityNeeded(
                context, ActivityInfo.OVERRIDE_MOUSE_TO_TOUCH);
    }

    public MouseToTouchProcessor(Context context, Handler handler) {
        super(context, handler);
    }

    @Nullable
    @Override
    public List<InputEvent> processInputEventForCompatibility(@NonNull InputEvent event) {
        if (!(event instanceof MotionEvent motionEvent)
                || !motionEvent.isFromSource(InputDevice.SOURCE_MOUSE)
                || motionEvent.getActionMasked() == MotionEvent.ACTION_OUTSIDE
                || motionEvent.getActionMasked() == MotionEvent.ACTION_SCROLL) {
            return null;
        }

        final List<InputEvent> result = processMotionEvent(motionEvent);
        if (result != null && !result.isEmpty()) {
            for (int i = 0; i < result.size() - 1; i++) {
                mModifiedEventMap.put(result.get(i).getId(), null);
            }
            mModifiedEventMap.put(result.getLast().getId(), event);
        }
        return result;
    }

    @Nullable
    private List<InputEvent> processMotionEvent(@NonNull MotionEvent event) {
        return switch (mState) {
            case STATE_AWAITING -> processEventInAwaitingState(event);
            case STATE_CONVERTING -> processEventInConvertingState(event);
            case STATE_NON_PRIMARY_CLICK -> processEventInNonPrimaryClickState(event);
            default -> null;
        };
    }

    @Nullable
    private List<InputEvent> processEventInAwaitingState(@NonNull MotionEvent event) {
        if (event.isHoverEvent()) {
            // Don't modify hover events, but clear the button state (e.g. BACK).
            if (event.getButtonState() != 0) {
                event.setButtonState(0);
                return List.of(event);
            }
            return null;
        }

        final int action = event.getActionMasked();
        if (isActionButtonEvent(action)) {
            // Button events, usually BACK and FORWARD, are dropped.
            return List.of();
        }
        if (action != MotionEvent.ACTION_DOWN) {
            Log.e(TAG, "Broken input sequence is observed. event=" + event);
            // Decide the next state based anyway on the primary button state.
        }
        boolean primaryButton = (event.getButtonState() & MotionEvent.BUTTON_PRIMARY)
                == MotionEvent.BUTTON_PRIMARY;
        if (primaryButton || event.isSynthesizedTouchpadGesture()) {
            mState = STATE_CONVERTING;
            return List.of(obtainRewrittenEventAsTouch(event));
        } else {
            mState = STATE_NON_PRIMARY_CLICK;
            return null;
        }
    }

    @Nullable
    private List<InputEvent> processEventInConvertingState(@NonNull MotionEvent event) {
        // STATE_CONVERTING starts with a primary button.
        // In this state, events are converted to touch events, and other button properties and
        // events are dropped.
        // Note that non-primary buttons can be also pressed and released during this state, and
        // ACTION_UP is dispatched only when all of (primary, secondary, and tertiary) buttons
        // are released.

        final int action = event.getActionMasked();
        if (isActionButtonEvent(action)) {
            // Button events are always dropped.
            return List.of();
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            mState = STATE_AWAITING;
        }

        return List.of(obtainRewrittenEventAsTouch(event));
    }

    @Nullable
    private List<InputEvent> processEventInNonPrimaryClickState(@NonNull MotionEvent event) {
        final int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            mState = STATE_AWAITING;
        }
        // During a gesture that was started from the non-primary (e.g. right) click, no rewrite
        // happens even if there's primary (left) button click.
        return null;
    }

    @Nullable
    @Override
    public InputEvent processInputEventBeforeFinish(@NonNull InputEvent inputEvent) {
        final int idx = mModifiedEventMap.indexOfKey(inputEvent.getId());
        if (idx < 0) {
            return inputEvent;
        }

        final InputEvent originalEvent = mModifiedEventMap.valueAt(idx);
        mModifiedEventMap.removeAt(idx);
        if (inputEvent != originalEvent) {
            inputEvent.recycleIfNeededAfterDispatch();
        }
        return originalEvent;
    }

    @NonNull
    private static MotionEvent obtainRewrittenEventAsTouch(@NonNull MotionEvent original) {
        final int numPointers = original.getPointerCount();

        final PointerProperties[] pointerProps = new PointerProperties[numPointers];
        for (int i = 0; i < numPointers; i++) {
            pointerProps[i] = new PointerProperties();
            original.getPointerProperties(i, pointerProps[i]);
            pointerProps[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
        }

        final long firstEventTime;
        final PointerCoords[] pointerCoords = new PointerCoords[numPointers];
        for (int pointerIdx = 0; pointerIdx < numPointers; pointerIdx++) {
            pointerCoords[pointerIdx] = new PointerCoords();
        }

        if (original.getHistorySize() > 0) {
            firstEventTime = original.getHistoricalEventTime(0);
            for (int pointerIdx = 0; pointerIdx < numPointers; pointerIdx++) {
                original.getHistoricalPointerCoords(pointerIdx, 0, pointerCoords[pointerIdx]);
            }
        } else {
            firstEventTime = original.getEventTime();
            for (int pointerIdx = 0; pointerIdx < numPointers; pointerIdx++) {
                original.getPointerCoords(pointerIdx, pointerCoords[pointerIdx]);
            }
        }

        final MotionEvent result =
                MotionEvent.obtain(
                        original.getDownTime(),
                        firstEventTime,
                        original.getAction(),
                        original.getPointerCount(),
                        pointerProps,
                        pointerCoords,
                        original.getMetaState(),
                        /* buttonState= */ 0,
                        original.getXPrecision(),
                        original.getYPrecision(),
                        original.getDeviceId(),
                        original.getEdgeFlags(),
                        InputDevice.SOURCE_TOUCHSCREEN,
                        original.getDisplayId(),
                        original.getFlags(),
                        original.getClassification());

        // If there are one or more history, add them to the event, and the last one is not from the
        // history but the current coords.
        for (int historyIdx = 1; historyIdx <= original.getHistorySize(); historyIdx++) {
            long eventTime;
            if (historyIdx == original.getHistorySize()) {
                eventTime = original.getEventTime();
                for (int pointerIdx = 0; pointerIdx < numPointers; pointerIdx++) {
                    original.getPointerCoords(pointerIdx, pointerCoords[pointerIdx]);
                }
            } else {
                eventTime = original.getHistoricalEventTime(historyIdx);
                for (int pointerIdx = 0; pointerIdx < numPointers; pointerIdx++) {
                    original.getHistoricalPointerCoords(
                            pointerIdx, historyIdx, pointerCoords[pointerIdx]);
                }
            }
            result.addBatch(eventTime, pointerCoords, original.getMetaState());
        }

        result.setActionButton(0);
        result.setButtonState(0);

        return result;
    }

    private static boolean isActionButtonEvent(int action) {
        return action == MotionEvent.ACTION_BUTTON_PRESS
                || action == MotionEvent.ACTION_BUTTON_RELEASE;
    }
}
