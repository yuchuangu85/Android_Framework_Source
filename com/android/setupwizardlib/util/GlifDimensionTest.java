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

import static com.google.common.truth.Truth.assertWithMessage;
import static org.robolectric.RuntimeEnvironment.application;

import android.content.Context;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;
import com.android.setupwizardlib.R;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = Config.ALL_SDKS)
public class GlifDimensionTest {

  private Context context;

  @Before
  public void setUp() {
    context = new ContextThemeWrapper(application, R.style.SuwThemeGlif_Light);
  }

  @Test
  public void testDividerInsetPhone() {
    assertDividerInset();
  }

  @Config(qualifiers = "sw600dp")
  @Test
  public void testDividerInsetSw600dp() {
    assertDividerInset();
  }

  private void assertDividerInset() {
    final Resources res = context.getResources();

    final TypedArray a = context.obtainStyledAttributes(new int[] {R.attr.suwMarginSides});
    final int marginSides = a.getDimensionPixelSize(0, 0);
    a.recycle();

    assertWithMessage(
            "Dimensions should satisfy constraint: "
                + "?attr/suwMarginSides = suw_items_glif_text_divider_inset")
        .that(res.getDimensionPixelSize(R.dimen.suw_items_glif_text_divider_inset))
        .isEqualTo(marginSides);

    assertWithMessage(
            "Dimensions should satisfy constraint: ?attr/suwMarginSides + "
                + "suw_items_icon_container_width = suw_items_glif_icon_divider_inset")
        .that(res.getDimensionPixelSize(R.dimen.suw_items_glif_icon_divider_inset))
        .isEqualTo(marginSides + res.getDimensionPixelSize(R.dimen.suw_items_icon_container_width));
  }

  @Test
  public void testButtonMargin() {
    assertButtonMargin();
  }

  @Config(qualifiers = "sw600dp")
  @Test
  public void testButtonMarginSw600dp() {
    assertButtonMargin();
  }

  private void assertButtonMargin() {
    final Resources res = context.getResources();

    final TypedArray a = context.obtainStyledAttributes(new int[] {R.attr.suwMarginSides});
    final int marginSides = a.getDimensionPixelSize(0, 0);
    a.recycle();

    assertWithMessage(
            "Dimensions should satisfy constraint: ?attr/suwMarginSides - "
                + "4dp (internal padding of button) = suw_glif_button_margin_end")
        .that(res.getDimensionPixelSize(R.dimen.suw_glif_button_margin_end))
        .isEqualTo(marginSides - dp2Px(4));

    assertWithMessage(
            "Dimensions should satisfy constraint: ?attr/suwMarginSides - "
                + "suw_glif_button_padding = suw_glif_button_margin_start")
        .that(res.getDimensionPixelSize(R.dimen.suw_glif_button_margin_start))
        .isEqualTo(marginSides - res.getDimensionPixelSize(R.dimen.suw_glif_button_padding));
  }

  private int dp2Px(float dp) {
    DisplayMetrics displayMetrics = context.getResources().getDisplayMetrics();
    return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, displayMetrics);
  }
}
