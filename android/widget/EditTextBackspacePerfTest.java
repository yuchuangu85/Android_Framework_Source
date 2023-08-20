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

package android.widget;

import android.perftests.utils.BenchmarkState;
import android.perftests.utils.PerfStatusReporter;
import android.perftests.utils.PerfTestActivity;
import android.text.Selection;
import android.view.KeyEvent;
import android.view.View.MeasureSpec;
import android.view.ViewGroup;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.LargeTest;
import androidx.test.rule.ActivityTestRule;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import java.util.Arrays;
import java.util.Collection;

@LargeTest
@RunWith(Parameterized.class)
public class EditTextBackspacePerfTest {

    private static final String BOY = "\uD83D\uDC66";  // U+1F466
    private static final String US_FLAG = "\uD83C\uDDFA\uD83C\uDDF8";  // U+1F1FA U+1F1F8
    private static final String FAMILY =
            // U+1F469 U+200D U+1F469 U+200D U+1F467 U+200D U+1F467
            "\uD83D\uDC69\u200D\uD83D\uDC69\u200D\uD83D\uDC67\u200D\uD83D\uDC67";
    private static final String EMOJI_MODIFIER = "\uD83C\uDFFD";  // U+1F3FD
    private static final String KEYCAP = "\u20E3";
    private static final String COLOR_COPYRIGHT = "\u00A9\uFE0F";

    @Parameters(name = "{0}")
    public static Collection cases() {
        return Arrays.asList(new Object[][] {
            { "Latin", "aaa", 1 },
            { "Flags", US_FLAG + US_FLAG + US_FLAG, 4 },
            { "EmojiModifier",
                BOY + EMOJI_MODIFIER + BOY + EMOJI_MODIFIER + BOY + EMOJI_MODIFIER, 4 },
            { "KeyCap", "1" + KEYCAP + "1" + KEYCAP + "1" + KEYCAP, 2 },
            { "ZwjSequence", FAMILY + FAMILY + FAMILY, 11 },
            { "VariationSelector", COLOR_COPYRIGHT + COLOR_COPYRIGHT + COLOR_COPYRIGHT, 2 },
        });
    }

    private final String mMetricKey;
    private final String mText;
    private final int mCursorPos;

    private static final KeyEvent BACKSPACE_KEY_EVENT =
            new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL);
    private static final KeyEvent RIGHT_ARROW_KEY_EVENT =
            new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT);

    public EditTextBackspacePerfTest(String metricKey, String text, int cursorPos) {
        mMetricKey = metricKey;
        mText = text;
        mCursorPos = cursorPos;
    }

    @Rule
    public ActivityTestRule<PerfTestActivity> mActivityRule =
            new ActivityTestRule<>(PerfTestActivity.class);

    @Rule
    public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    private void prepareTextForBackspace(EditText editText) {
        editText.setText(mText, TextView.BufferType.EDITABLE);
        Selection.setSelection(editText.getText(), 0, 0);

        // Do layout it here since the cursor movement requires layout information but it
        // happens asynchronously even if the view is attached to an Activity.
        editText.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        editText.invalidate();
        editText.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        editText.layout(0, 0, 1024, 768);

        // mText contains three grapheme clusters. Move the cursor to the 2nd grapheme
        // cluster by forwarding right arrow key event.
        editText.onKeyDown(RIGHT_ARROW_KEY_EVENT.getKeyCode(), RIGHT_ARROW_KEY_EVENT);
        Assert.assertEquals(mCursorPos, Selection.getSelectionStart(editText.getText()));
    }

    @Test
    public void testBackspace() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            EditText editText = new EditText(mActivityRule.getActivity());

            BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
            while (state.keepRunning()) {
                // Prepare the test data for this iteration with pausing timer.
                state.pauseTiming();
                prepareTextForBackspace(editText);
                state.resumeTiming();

                editText.onKeyDown(BACKSPACE_KEY_EVENT.getKeyCode(), BACKSPACE_KEY_EVENT);
            }
        });
    }
}
