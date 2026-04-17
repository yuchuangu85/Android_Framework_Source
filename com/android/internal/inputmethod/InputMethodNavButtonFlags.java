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

package com.android.internal.inputmethod;

import static java.lang.annotation.RetentionPolicy.SOURCE;

import android.annotation.IntDef;

import java.lang.annotation.Retention;

/**
 * A set of flags notified from {@link com.android.server.inputmethod.InputMethodManagerService} to
 * {@link android.inputmethodservice.InputMethodService} regarding how
 * {@link android.inputmethodservice.NavigationBarController} should behave.
 */
@Retention(SOURCE)
@IntDef(flag = true, value = {
        InputMethodNavButtonFlags.IME_DRAWS_IME_NAV_BAR,
        InputMethodNavButtonFlags.SHOW_IME_SWITCHER_BUTTON,
        InputMethodNavButtonFlags.IME_SWITCHER_BUTTON_ENABLED,
})
public @interface InputMethodNavButtonFlags {

    /**
     * When set, the IME process needs to draw its own IME Navigation bar, to render and handle the
     * navigation bar buttons such as the IME Dismiss button and the IME Switcher button.
     */
    int IME_DRAWS_IME_NAV_BAR = 1;

    /**
     * When set, the IME Switcher button needs to be shown somewhere, when the IME is visible.
     * Depending on the current state, it may be shown either by the IME Navigation Bar, or by
     * the IME itself, as a custom button.
     */
    int SHOW_IME_SWITCHER_BUTTON = 1 << 1;

    /**
     * When set, the IME Switcher button could be shown on the IME Navigation bar. Otherwise, a
     * custom IME Switcher button would be shown by the IME. The actual button visibility comes
     * from {@link #SHOW_IME_SWITCHER_BUTTON}.
     */
    int IME_SWITCHER_BUTTON_ENABLED = 1 << 2;
}
