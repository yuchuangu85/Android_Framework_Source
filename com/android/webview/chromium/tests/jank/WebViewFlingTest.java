/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.webview.chromium.tests.jank;

import android.content.Intent;
import android.net.Uri;
import android.os.SystemClock;
import android.support.test.jank.JankTest;
import android.support.test.jank.JankTestBase;
import android.support.test.jank.JankType;
import android.support.test.uiautomator.By;
import android.support.test.uiautomator.Direction;
import android.support.test.uiautomator.UiDevice;
import android.support.test.uiautomator.UiObject2;
import android.support.test.uiautomator.UiScrollable;
import android.support.test.uiautomator.UiSelector;

import java.io.File;
import java.io.IOException;

/**
 * Jank test for Android Webview.
 *
 * To run
 * 1) Install the test application (com.android.webview.chromium.shell)
 * 2) Place a directories containing the test pages on the test device in
 *    $EXTERNAL_STORAGE/AwJankPages. Each directory should contain an index.html
 *    file as the main file of the test page.
 * 3) Build this test and install the resulting apk file
 * 4) Run the test using the command:
 *    adb shell am instrument -e Url URL -w \
 *            com.android.webview.chromium.tests.jank/android.test.InstrumentationTestRunner
 *
 */
public class WebViewFlingTest extends JankTestBase {

    private static final long TEST_DELAY_TIME_MS = 2 * 1000; // 2 seconds
    private static final long PAGE_LOAD_DELAY_TIME_MS = 20 * 1000; // 20 seconds
    private static final int MIN_DATA_SIZE = 50;
    private static final long DEFAULT_ANIMATION_TIME = 2 * 1000;
    private static final String CHROMIUM_SHELL_APP = "com.android.webview.chromium.shell";
    private static final String CHROMIUM_SHELL_ACTIVITY = CHROMIUM_SHELL_APP + ".JankActivity";
    private static final String RES_PACKAGE = "com.android.webview.chromium.shell";

    private UiDevice mDevice;

    /**
    * {@inheritDoc}
    */
    @Override
    protected void setUp() throws Exception {
        super.setUp();

        mDevice = UiDevice.getInstance(getInstrumentation());
        mDevice.setOrientationNatural();

        // Get the URL argument
        String url = getArguments().getString("Url");
        File webpage = new File(url);
        assertNotNull("No test pages", webpage);

        // Launch the chromium shell
        Intent intent = new Intent(Intent.ACTION_DEFAULT,
                Uri.parse("file://" + webpage.getAbsolutePath()));
        intent.setClassName(CHROMIUM_SHELL_APP, CHROMIUM_SHELL_ACTIVITY);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        getInstrumentation().getContext().startActivity(intent);
        SystemClock.sleep(PAGE_LOAD_DELAY_TIME_MS);
    }

    @Override
    public void beforeLoop() {
        UiObject2 container = mDevice.findObject(By.res(RES_PACKAGE, "container"));

        // Fling to the top
        while (container.fling(Direction.UP)) {
        }

        SystemClock.sleep(TEST_DELAY_TIME_MS);
    }

    @JankTest(type=JankType.CONTENT_FRAMES, expectedFrames=MIN_DATA_SIZE)
    public void testBrowserPageFling() throws IOException {
        mDevice.findObject(By.res(RES_PACKAGE, "container")).fling(Direction.DOWN);
        SystemClock.sleep(DEFAULT_ANIMATION_TIME);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void tearDown() throws Exception {
        mDevice.unfreezeRotation();

        super.tearDown();
    }
}
