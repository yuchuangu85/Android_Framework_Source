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

/**
 * Collection of common constants for shortcut chooser dialog intent, which will be used in both
 * framework and system ui.
 */
public class ShortcutChooserDialogConstants {
    private ShortcutChooserDialogConstants() {}

    /**
     * Used as the name of the extra data when we put the value of the shortcut type among
     * `ShortcutConstants.UserShortcutType` into an intent.
     */
    public static final String SHORTCUT_TYPE = "SHORTCUT_TYPE";

    /**
     * Used as the name of the extra data when we put the value of the display id into an intent.
     */
    public static final String DISPLAY_ID = "DISPLAY_ID";
}
