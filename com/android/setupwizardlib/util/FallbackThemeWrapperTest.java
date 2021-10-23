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

import android.content.Context;
import android.content.res.TypedArray;
import android.view.ContextThemeWrapper;
import android.support.test.InstrumentationRegistry;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;
import com.android.setupwizardlib.test.R;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class FallbackThemeWrapperTest {

  private FallbackThemeWrapper mThemedContext;

  @Before
  public void setUp() {
    Context baseContext =
        new ContextThemeWrapper(InstrumentationRegistry.getContext(), R.style.TestBaseTheme);
    mThemedContext = new FallbackThemeWrapper(baseContext, R.style.TestFallbackTheme);
  }

  @Test
  public void testThemeValueOnlyInBase() {
    final TypedArray a =
        mThemedContext.obtainStyledAttributes(new int[] {android.R.attr.background});
    assertEquals(0xffff0000, a.getColor(0, 0));
    a.recycle();
  }

  @Test
  public void testThemeValueOnlyInFallback() {
    final TypedArray a =
        mThemedContext.obtainStyledAttributes(new int[] {android.R.attr.foreground});
    assertEquals(0xff0000ff, a.getColor(0, 0));
    a.recycle();
  }

  @Test
  public void testThemeValueInBoth() {
    final TypedArray a = mThemedContext.obtainStyledAttributes(new int[] {android.R.attr.theme});
    assertEquals(R.style.TestBaseTheme, a.getResourceId(0, 0));
    a.recycle();
  }
}
