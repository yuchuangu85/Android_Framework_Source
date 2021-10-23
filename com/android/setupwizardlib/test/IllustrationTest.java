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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.support.test.InstrumentationRegistry;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;
import com.android.setupwizardlib.view.Illustration;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class IllustrationTest {

  @Test
  public void testWillDraw() {
    final Illustration illustration = new Illustration(InstrumentationRegistry.getContext());
    assertFalse("The illustration needs to be drawn", illustration.willNotDraw());
  }

  @Test
  public void testAspectRatio() {
    final Context context = InstrumentationRegistry.getContext();
    // Force the context to be xhdpi
    context.getResources().getDisplayMetrics().density = 2.0f;

    final Illustration illustration = new Illustration(context);
    illustration.setAspectRatio(3.0f);
    final Drawable backgroundDrawable = new ColorDrawable(Color.RED);
    final Drawable illustrationDrawable = new ColorDrawable(Color.BLUE);
    illustration.setBackgroundDrawable(backgroundDrawable);
    illustration.setIllustration(illustrationDrawable);

    illustration.measure(
        View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
    // (300px / 3) round down to nearest mod (8dp = 16px) = 96px
    assertEquals("Top padding should be 96", 96, illustration.getPaddingTop());
  }
}
