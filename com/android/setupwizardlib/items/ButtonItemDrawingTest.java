/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.setupwizardlib.items;

import static org.junit.Assert.assertTrue;

import android.support.test.annotation.UiThreadTest;
import android.support.test.filters.SmallTest;
import android.support.test.rule.UiThreadTestRule;
import android.support.test.runner.AndroidJUnit4;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;

import com.android.setupwizardlib.R;
import com.android.setupwizardlib.test.util.DrawingTestHelper;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class ButtonItemDrawingTest {

    private static final int GOOGLE_BLUE = 0xff4285f4;

    // These tests need to be run on UI thread because button uses ValueAnimator
    @Rule
    public UiThreadTestRule mUiThreadTestRule = new UiThreadTestRule();

    private ViewGroup mParent;

    @Before
    public void setUp() throws Exception {
        mParent = new LinearLayout(
                DrawingTestHelper.createCanvasActivity(R.style.SuwThemeGlif_Light));
    }

    @Test
    @UiThreadTest
    public void testColoredButtonTheme() {
        TestButtonItem item = new TestButtonItem();
        item.setTheme(R.style.SuwButtonItem_Colored);
        item.setText("foobar");

        final Button button = item.createButton(mParent);

        DrawingTestHelper drawingTestHelper = new DrawingTestHelper(50, 50);
        drawingTestHelper.drawView(button);

        int googleBluePixelCount = 0;
        for (int pixel : drawingTestHelper.getPixels()) {
            if (pixel == GOOGLE_BLUE) {
                googleBluePixelCount++;
            }
        }
        assertTrue("> 10 pixels should be Google blue. Found " + googleBluePixelCount,
                googleBluePixelCount > 10);
    }

    private static class TestButtonItem extends ButtonItem {

        @Override
        public Button createButton(ViewGroup parent) {
            // Make this method public for testing
            return super.createButton(parent);
        }
    }
}
