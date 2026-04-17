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

package com.android.internal.accessibility.common;

import android.provider.Settings;

/** Collection of common constants for a KeyGestureEvent on any accessibility feature */
public final class KeyGestureEventConstants {
    private KeyGestureEventConstants() {}

    /**
     * Used as the name of the extra data when we put the value of the key gesture type on the key
     * gesture event into an intent.
     */
    public static final String KEY_GESTURE_TYPE = "KEY_GESTURE_TYPE";

    /**
     * Used as the name of the extra data when we put the value of the meta state on the key gesture
     * event into an intent.
     */
    public static final String META_STATE = "META_STATE";

    /**
     * Used as the name of the extra data when we put the value of the key code on the key gesture
     * event into an intent.
     */
    public static final String KEY_CODE = "KEY_CODE";

    /**
     * Used as the name of the extra data when we put the value of the target name, e.g. a flattened
     * componentName or MAGNIFICATION_CONTROLLER_NAME, on the key gesture event into an intent.
     *
     * @see Settings.Secure#ACCESSIBILITY_GESTURE_TARGETS
     */
    public static final String TARGET_NAME = "TARGET_NAME";

    /**
     * Used as the name of the extra data when we put the value of the display id on the key gesture
     * event into an intent.
     */
    public static final String DISPLAY_ID = "DISPLAY_ID";
}
