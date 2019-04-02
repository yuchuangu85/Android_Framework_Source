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

package com.android.setupwizardlib.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.robolectric.RuntimeEnvironment.application;

import android.annotation.TargetApi;
import android.app.Activity;
import android.content.Context;
import android.os.Build.VERSION;
import android.os.Build.VERSION_CODES;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.view.ContextThemeWrapper;
import android.widget.Button;

import com.android.setupwizardlib.BuildConfig;
import com.android.setupwizardlib.R;
import com.android.setupwizardlib.robolectric.SuwLibRobolectricTestRunner;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.annotation.Config;

@RunWith(SuwLibRobolectricTestRunner.class)
@Config(constants = BuildConfig.class, sdk = {Config.OLDEST_SDK, Config.NEWEST_SDK})
public class GlifStyleTest {

    private Context mContext;

    @Before
    public void setUp() {
        mContext = new ContextThemeWrapper(application, R.style.SuwThemeGlif_Light);
    }

    @Test
    public void testSuwGlifButtonTertiary() {
        Button button = new Button(
                mContext,
                Robolectric.buildAttributeSet()
                        .setStyleAttribute("@style/SuwGlifButton.Tertiary")
                        .build());
        assertNull("Background of tertiary button should be null", button.getBackground());
        assertNull("Tertiary button should have no transformation method",
                button.getTransformationMethod());
        if (VERSION.SDK_INT < VERSION_CODES.M) {
            // Robolectric resolved the wrong theme attribute on versions >= M
            // https://github.com/robolectric/robolectric/issues/2940
            assertEquals("ff4285f4", Integer.toHexString(button.getTextColors().getDefaultColor()));
        }
    }

    @TargetApi(VERSION_CODES.LOLLIPOP)
    @Config(sdk = Config.NEWEST_SDK)
    @Test
    public void glifThemeLight_statusBarColorShouldBeTransparent() {
        GlifThemeActivity activity = Robolectric.setupActivity(GlifThemeActivity.class);
        assertEquals(0x00000000, activity.getWindow().getStatusBarColor());
    }

    private static class GlifThemeActivity extends Activity {

        @Override
        protected void onCreate(@Nullable Bundle savedInstanceState) {
            setTheme(R.style.SuwThemeGlif_Light);
            super.onCreate(savedInstanceState);
        }
    }
}
