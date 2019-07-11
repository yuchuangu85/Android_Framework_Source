/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.uiautomator.platform;

import com.android.uiautomator.common.UiWatchers;

public class TestWatchers extends UiWatchers {
    private String TAG = "TestWatchers";

    @Override
    public void onAnrDetected(String errorText) {
        // The ANR dialog is still open now and upon returning from here
        // it will automatically get closed. See UiWatchers or implement
        // your handlers directly.
        super.onAnrDetected("ANR:" + errorText);
    }

    @Override
    public void onCrashDetected(String errorText) {
        // what do we need to do here?
        // The Crash dialog is still open now and upon returning from here
        // it will automatically get closed. See UiWatchers or implement
        // your handlers directly.
        super.onCrashDetected("CRASH:" + errorText);
    }
}
