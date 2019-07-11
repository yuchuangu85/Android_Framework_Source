/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.uiautomator.samples.skeleton;

import android.app.Activity;
import android.graphics.Point;
import android.os.Bundle;

import com.android.uiautomator.testrunner.UiAutomatorTestCase;

public class DemoTestCase extends UiAutomatorTestCase {

    public void testDemo() {
        assertTrue(getUiDevice().pressHome());
        Bundle status = new Bundle();
        status.putString("msg", "This is a demo test and I just pressed HOME");
        status.putString("product", getUiDevice().getProductName());
        Point p = getUiDevice().getDisplaySizeDp();
        status.putInt("dp-width", p.x);
        status.putInt("dp-height", p.y);
        getAutomationSupport().sendStatus(Activity.RESULT_OK, status);
    }
}
