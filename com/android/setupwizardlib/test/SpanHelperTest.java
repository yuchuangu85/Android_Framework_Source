/*
 * Copyright (C) 2016 The Android Open Source Project
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
import static org.junit.Assert.assertSame;

import android.text.Annotation;
import android.text.SpannableStringBuilder;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;
import com.android.setupwizardlib.span.SpanHelper;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class SpanHelperTest {

  @Test
  public void testReplaceSpan() {
    SpannableStringBuilder ssb = new SpannableStringBuilder("Hello world");
    Annotation oldSpan = new Annotation("key", "value");
    Annotation newSpan = new Annotation("newkey", "newvalue");
    ssb.setSpan(oldSpan, 2, 5, 0 /* flags */);

    SpanHelper.replaceSpan(ssb, oldSpan, newSpan);

    final Object[] spans = ssb.getSpans(0, ssb.length(), Object.class);
    assertEquals("There should be one span in the builder", 1, spans.length);
    assertSame("The span should be newSpan", newSpan, spans[0]);
  }
}
