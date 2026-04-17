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
package com.android.internal.widget.remotecompose.player.platform;

import android.annotation.NonNull;

import com.android.internal.widget.remotecompose.core.CoreDocument;

/** Provides haptic support */
public class HapticSupport {

    private static final int[] sHapticTable;

    static {
        sHapticTable =
                new int[] {
                    android.view.HapticFeedbackConstants.NO_HAPTICS,
                    android.view.HapticFeedbackConstants.LONG_PRESS,
                    android.view.HapticFeedbackConstants.VIRTUAL_KEY,
                    android.view.HapticFeedbackConstants.KEYBOARD_TAP,
                    android.view.HapticFeedbackConstants.CLOCK_TICK,
                    android.view.HapticFeedbackConstants.CONTEXT_CLICK,
                    android.view.HapticFeedbackConstants.KEYBOARD_PRESS,
                    android.view.HapticFeedbackConstants.KEYBOARD_RELEASE,
                    android.view.HapticFeedbackConstants.VIRTUAL_KEY_RELEASE,
                    android.view.HapticFeedbackConstants.TEXT_HANDLE_MOVE,
                    android.view.HapticFeedbackConstants.GESTURE_START,
                    android.view.HapticFeedbackConstants.GESTURE_END,
                    android.view.HapticFeedbackConstants.CONFIRM,
                    android.view.HapticFeedbackConstants.REJECT,
                    android.view.HapticFeedbackConstants.TOGGLE_ON,
                    android.view.HapticFeedbackConstants.TOGGLE_OFF,
                    android.view.HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE,
                    android.view.HapticFeedbackConstants.GESTURE_THRESHOLD_DEACTIVATE,
                    android.view.HapticFeedbackConstants.DRAG_START,
                    android.view.HapticFeedbackConstants.SEGMENT_TICK,
                    android.view.HapticFeedbackConstants.SEGMENT_FREQUENT_TICK,
                };
    }

    /**
     * Setup the haptic responses
     *
     * @param view
     */
    public void setupHaptics(@NonNull RemoteComposeView view) {
        view.setHapticEngine(
                new CoreDocument.HapticEngine() {

                    @Override
                    public void haptic(int type) {
                        view.performHapticFeedback(sHapticTable[type % sHapticTable.length]);
                    }
                });
    }
}
