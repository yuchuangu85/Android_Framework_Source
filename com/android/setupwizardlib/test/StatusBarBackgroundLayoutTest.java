/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.setupwizardlib.test;

import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.drawable.ShapeDrawable;
import android.support.test.InstrumentationRegistry;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;
import com.android.setupwizardlib.view.StatusBarBackgroundLayout;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class StatusBarBackgroundLayoutTest {

  @Test
  public void testSetStatusBarBackground() {
    final StatusBarBackgroundLayout layout =
        new StatusBarBackgroundLayout(InstrumentationRegistry.getContext());
    final ShapeDrawable drawable = new ShapeDrawable();
    layout.setStatusBarBackground(drawable);
    assertSame(
        "Status bar background drawable should be same as set",
        drawable,
        layout.getStatusBarBackground());
  }

  @Test
  public void testAttachedToWindow() {
    // Attaching to window should request apply window inset
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
      final TestStatusBarBackgroundLayout layout =
          new TestStatusBarBackgroundLayout(InstrumentationRegistry.getContext());
      layout.mRequestApplyInsets = false;
      layout.onAttachedToWindow();

      assertTrue("Attaching to window should apply window inset", layout.mRequestApplyInsets);
    }
  }

  private static class TestStatusBarBackgroundLayout extends StatusBarBackgroundLayout {

    boolean mRequestApplyInsets = false;

    TestStatusBarBackgroundLayout(Context context) {
      super(context);
    }

    @Override
    public void onAttachedToWindow() {
      super.onAttachedToWindow();
    }

    @Override
    public void requestApplyInsets() {
      super.requestApplyInsets();
      mRequestApplyInsets = true;
    }
  }
}
