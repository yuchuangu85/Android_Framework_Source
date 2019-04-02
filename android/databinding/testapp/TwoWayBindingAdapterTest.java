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
package android.databinding.testapp;

import android.app.Instrumentation;
import android.content.Context;
import android.databinding.testapp.databinding.TwoWayBinding;
import android.databinding.testapp.vo.TwoWayBindingObject;
import android.os.SystemClock;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.BackgroundColorSpan;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TabHost.TabSpec;

import java.util.Calendar;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class TwoWayBindingAdapterTest extends BaseDataBinderTest<TwoWayBinding> {

    TwoWayBindingObject mBindingObject;

    public TwoWayBindingAdapterTest() {
        super(TwoWayBinding.class);
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        initBinder(new Runnable() {
            @Override
            public void run() {
                Context context = getBinder().getRoot().getContext();
                mBindingObject = new TwoWayBindingObject(context);
                getBinder().setObj(mBindingObject);
                getBinder().executePendingBindings();
            }
        });
    }

    public void testListViewSelectedItemPosition() throws Throwable {
        makeVisible(mBinder.listView);
        runTestOnUiThread(new Runnable() {
            @Override
            public void run() {
                assertEquals(0, mBindingObject.selectedItemPosition.get());
                assertEquals(0, mBinder.listView.getSelectedItemPosition());
                mBinder.listView.setSelection(1);
            }
        });
        long timeout = SystemClock.uptimeMillis() + 500;
        while (mBindingObject.selectedItemPosition.get() == 0 &&
                SystemClock.uptimeMillis() < timeout) {
            Thread.sleep(1);
        }
        runTestOnUiThread(new Runnable() {
            @Override
            public void run() {
                assertEquals(1, mBinder.listView.getSelectedItemPosition());
                assertEquals(1, mBindingObject.selectedItemPosition.get());
            }
        });
    }

    private void clickView(final View view, float offsetX) throws Throwable {
        final int[] xy = new int[2];
        final int[] viewSize = new int[2];
        do {
            runTestOnUiThread(new Runnable() {
                @Override
                public void run() {
                    view.getLocationOnScreen(xy);
                    viewSize[0] = view.getWidth();
                    viewSize[1] = view.getHeight();
                }
            });
        } while (xy[0] < 0 || xy[1] < 0);

        final float x = xy[0] + offsetX;
        final float y = xy[1] + (viewSize[1] / 2f);

        Instrumentation inst = getInstrumentation();

        long downTime = SystemClock.uptimeMillis();
        long eventTime = SystemClock.uptimeMillis();

        MotionEvent event = MotionEvent.obtain(downTime, eventTime,
                MotionEvent.ACTION_DOWN, x, y, 0);
        inst.sendPointerSync(event);
        inst.waitForIdleSync();

        eventTime = SystemClock.uptimeMillis();
        final int touchSlop = ViewConfiguration.get(view.getContext()).getScaledTouchSlop();
        event = MotionEvent.obtain(downTime, eventTime, MotionEvent.ACTION_MOVE,
                x + (touchSlop / 2.0f), y + (touchSlop / 2.0f), 0);
        inst.sendPointerSync(event);
        inst.waitForIdleSync();

        eventTime = SystemClock.uptimeMillis();
        event = MotionEvent.obtain(downTime, eventTime, MotionEvent.ACTION_UP, x, y, 0);
        inst.sendPointerSync(event);
        inst.waitForIdleSync();
    }

    private void clickChild(View view, float offsetX) throws Throwable {
        View childView = view;
        while (childView != null) {
            childView.callOnClick();
            if (childView instanceof ViewGroup) {
                final ViewGroup viewGroup = (ViewGroup) childView;
                if (viewGroup.getChildCount() > 0) {
                    childView = viewGroup.getChildAt(0);
                } else {
                    childView = null;
                }
            } else {
                clickView(childView, offsetX);
                childView = null;
            }
        }
    }

    public void testCalendarViewDate() throws Throwable {
        makeVisible(mBinder.calendarView);
        runTestOnUiThread(new Runnable() {
            @Override
            public void run() {
                assertTrue(mBindingObject.date.get() != 0);
                assertDatesMatch(mBindingObject.date.get(), mBinder.calendarView.getDate());
            }
        });
        final long[] date = new long[2];
        float offsetX = 0;
        long timeout = SystemClock.uptimeMillis() + 1500;
        do {
            // Just randomly poke at the CalendarView to set the date
            clickChild(mBinder.calendarView, offsetX);
            offsetX += 48;
            runTestOnUiThread(new Runnable() {
                @Override
                public void run() {
                    date[0] = mBinder.calendarView.getDate();
                    if (date[1] == 0) {
                        date[1] = date[0];
                    }
                }
            });
        } while (date[0] == date[1] && SystemClock.uptimeMillis() < timeout);

        timeout = SystemClock.uptimeMillis() + 100;
        while (mBindingObject.date.get() == 0 && SystemClock.uptimeMillis() < timeout) {
            Thread.sleep(1);
        }

        assertDatesMatch(date[0], mBindingObject.date.get());
    }

    public void assertDatesMatch(long expectedTimeMillis, long testTimeMillis) {
        Calendar expected = Calendar.getInstance();
        expected.setTimeInMillis(expectedTimeMillis);
        Calendar testValue = Calendar.getInstance();
        testValue.setTimeInMillis(testTimeMillis);
        assertEquals(expected.get(Calendar.YEAR), testValue.get(Calendar.YEAR));
        assertEquals(expected.get(Calendar.MONTH), testValue.get(Calendar.MONTH));
        assertEquals(expected.get(Calendar.DAY_OF_MONTH),
                testValue.get(Calendar.DAY_OF_MONTH));
    }

    public void testCheckBoxChecked() throws Throwable {
        makeVisible(mBinder.checkBox);
        runTestOnUiThread(new Runnable() {
            @Override
            public void run() {
                assertFalse(mBindingObject.checked.get());
                assertFalse(mBinder.checkBox.isChecked());
                mBinder.checkBox.setChecked(true);
            }
        });

        final long timeout = SystemClock.uptimeMillis() + 500;
        while (!mBindingObject.checked.get() && SystemClock.uptimeMillis() < timeout) {
            Thread.sleep(1);
        }

        runTestOnUiThread(new Runnable() {
            @Override
            public void run() {
                assertTrue(mBinder.checkBox.isChecked());
                assertTrue(mBindingObject.checked.get());
            }
        });
    }

    private boolean focusOn(final View view) throws Throwable {
        runTestOnUiThread(new Runnable() {
            @Override
            public void run() {
                view.requestFocus();
            }
        });
        long timeout = SystemClock.uptimeMillis() + 500;
        final boolean[] focused = new boolean[1];
        while (SystemClock.uptimeMillis() < timeout) {
            runTestOnUiThread(new Runnable() {
                @Override
                public void run() {
                    focused[0] = view.isFocused();
                }
            });
            if (focused[0]) {
                return true;
            }
        }
        return false;
    }

    public void testNumberPickerNumber() throws Throwable {
        makeVisible(mBinder.textView, mBinder.numberPicker);
        assertTrue(focusOn(mBinder.textView));
        final EditText[] pickerText = new EditText[1];
        runTestOnUiThread(new Runnable() {
            @Override
            public void run() {
                assertEquals(1, mBindingObject.number.get());
                assertEquals(1, mBinder.numberPicker.getValue());
                for (int i = 0; i < mBinder.numberPicker.getChildCount(); i++) {
                    View view = mBinder.numberPicker.getChildAt(i);
                    if (view instanceof EditText) {
                        pickerText[0] = (EditText) view;
                        break;
                    }
                }
            }
        });
        assertNotNull(pickerText[0]);
        assertTrue(focusOn(pickerText[0]));
        runTestOnUiThread(new Runnable() {
            @Override
            public void run() {
                pickerText[0].setText("10");
            }
        });
        assertTrue(focusOn(mBinder.textView));

        final long timeout = SystemClock.uptimeMillis() + 10;
        while (mBindingObject.number.get() == 1 && SystemClock.uptimeMillis() < timeout) {
            Thread.sleep(1);
        }

        runTestOnUiThread(new Runnable() {
            @Override
            public void run() {
                assertEquals(10, mBinder.numberPicker.getValue());
                assertEquals(10, mBindingObject.number.get());
            }
        });
    }

    public void testRatingBarRating() throws Throwable {
        makeVisible(mBinder.ratingBar);
        runTestOnUiThread(new Runnable() {
            @Override
            public void run() {
                assertEquals(1f, mBindingObject.rating.get());
                assertEquals(1f, mBinder.ratingBar.getRating());
                mBinder.ratingBar.setRating(2.5f);
            }
        });

        final long timeout = SystemClock.uptimeMillis() + 500;
        while (mBindingObject.rating.get() == 1f && SystemClock.uptimeMillis() < timeout) {
            Thread.sleep(1);
        }

        runTestOnUiThread(new Runnable() {
            @Override
            public void run() {
                assertEquals(2.5f, mBinder.ratingBar.getRating());
                assertEquals(2.5f, mBindingObject.rating.get());
            }
        });
    }

    public void testSeekBarProgress() throws Throwable {
        makeVisible(mBinder.seekBar);
        runTestOnUiThread(new Runnable() {
            @Override
            public void run() {
                assertEquals(1, mBindingObject.progress.get());
                assertEquals(1, mBinder.seekBar.getProgress());
                mBinder.seekBar.setProgress(30);
            }
        });

        final long timeout = SystemClock.uptimeMillis() + 500;
        while (mBindingObject.progress.get() == 1 && SystemClock.uptimeMillis() < timeout) {
            Thread.sleep(1);
        }

        runTestOnUiThread(new Runnable() {
            @Override
            public void run() {
                assertEquals(30, mBinder.seekBar.getProgress());
                assertEquals(30, mBindingObject.progress.get());
            }
        });
    }

    public void testTabHostCurrentTab() throws Throwable {
        makeVisible(mBinder.tabhost);
        runTestOnUiThread(new Runnable() {
            @Override
            public void run() {
                mBinder.tabhost.setup();
                TabSpec tab1 = mBinder.tabhost.newTabSpec("Tab1");
                TabSpec tab2 = mBinder.tabhost.newTabSpec("Tab2");

                tab1.setIndicator("tab1");
                tab1.setContent(R.id.foo);
                tab2.setIndicator("tab2");
                tab2.setContent(R.id.bar);
                mBinder.tabhost.addTab(tab1);
                mBinder.tabhost.addTab(tab2);
                mBinder.tabhost.setCurrentTab(1);
            }
        });

        final long timeout = SystemClock.uptimeMillis() + 500;
        while (mBindingObject.currentTab.get() == 0 && SystemClock.uptimeMillis() < timeout) {
            Thread.sleep(1);
        }

        runTestOnUiThread(new Runnable() {
            @Override
            public void run() {
                assertEquals(1, mBinder.tabhost.getCurrentTab());
                assertEquals(1, mBindingObject.currentTab.get());
            }
        });
    }

    public void testTextViewText() throws Throwable {
        makeVisible(mBinder.textView);
        runTestOnUiThread(new Runnable() {
            @Override
            public void run() {
                assertEquals(null, mBindingObject.text.get());
                assertEquals("", mBinder.textView.getText().toString());
                mBinder.textView.setText("Hello World");
            }
        });

        final long timeout = SystemClock.uptimeMillis() + 500;
        while (mBindingObject.text.get().isEmpty() && SystemClock.uptimeMillis() < timeout) {
            Thread.sleep(1);
        }

        runTestOnUiThread(new Runnable() {
            @Override
            public void run() {
                assertEquals("Hello World", mBinder.textView.getText().toString());
                assertEquals("Hello World", mBindingObject.text.get());
            }
        });
    }

    public void testDatePicker() throws Throwable {
        makeVisible(mBinder.datePicker);
        runTestOnUiThread(new Runnable() {
            @Override
            public void run() {
                assertEquals(1972, mBindingObject.year.get());
                assertEquals(9, mBindingObject.month.get());
                assertEquals(21, mBindingObject.day.get());
                assertEquals(1972, mBinder.datePicker.getYear());
                assertEquals(9, mBinder.datePicker.getMonth());
                assertEquals(21, mBinder.datePicker.getDayOfMonth());
                mBinder.datePicker.updateDate(2003, 4, 17);
            }
        });

        final long timeout = SystemClock.uptimeMillis() + 500;
        while (mBindingObject.year.get() == 1972 && SystemClock.uptimeMillis() < timeout) {
            Thread.sleep(1);
        }

        runTestOnUiThread(new Runnable() {
            @Override
            public void run() {
                assertEquals(2003, mBindingObject.year.get());
                assertEquals(4, mBindingObject.month.get());
                assertEquals(17, mBindingObject.day.get());
            }
        });
    }

    public void testExpressions1() throws Throwable {
        makeVisible(mBinder.expressions1);
        runTestOnUiThread(new Runnable() {
            @Override
            public void run() {
                assertEquals(1972, mBindingObject.year.get());
                assertEquals(9, mBindingObject.month.get());
                assertEquals(21, mBindingObject.day.get());
                assertEquals(1972000, mBinder.expressions1.getYear());
                assertEquals(2, mBinder.expressions1.getMonth());
                assertEquals(22, mBinder.expressions1.getDayOfMonth());
                mBinder.expressions1.updateDate(2003000, 3, 18);
            }
        });

        final long timeout = SystemClock.uptimeMillis() + 500;
        while (mBindingObject.year.get() == 1972 && SystemClock.uptimeMillis() < timeout) {
            Thread.sleep(1);
        }

        runTestOnUiThread(new Runnable() {
            @Override
            public void run() {
                assertEquals(2003, mBindingObject.year.get());
                assertEquals(8, mBindingObject.month.get());
                assertEquals(17, mBindingObject.day.get());
            }
        });
    }

    public void testExpressions2() throws Throwable {
        makeVisible(mBinder.expressions2);
        runTestOnUiThread(new Runnable() {
            @Override
            public void run() {
                assertEquals(1972, mBindingObject.year.get());
                assertEquals(9, mBindingObject.month.get());
                assertEquals(21, mBindingObject.day.get());
                assertEquals(1, mBinder.expressions2.getYear());
                assertEquals(9, mBinder.expressions2.getMonth());
                assertEquals(21, mBinder.expressions2.getDayOfMonth());
                mBinder.expressions2.updateDate(2, 4, 17);
            }
        });

        final long timeout = SystemClock.uptimeMillis() + 500;
        while (mBindingObject.year.get() == 1972 && SystemClock.uptimeMillis() < timeout) {
            Thread.sleep(1);
        }

        runTestOnUiThread(new Runnable() {
            @Override
            public void run() {
                assertEquals(2000, mBindingObject.year.get());
                assertEquals(4, mBindingObject.month.get());
                assertEquals(17, mBindingObject.day.get());
            }
        });
    }

    public void testExpressions3() throws Throwable {
        makeVisible(mBinder.expressions3);
        runTestOnUiThread(new Runnable() {
            @Override
            public void run() {
                assertEquals((Integer)1, mBindingObject.list.get(1));
                assertEquals((Integer)2, mBindingObject.map.get("two"));
                assertEquals(2, mBindingObject.array.get()[1]);
                assertEquals(1, mBinder.expressions3.getYear());
                assertEquals(2, mBinder.expressions3.getMonth());
                assertEquals(2, mBinder.expressions3.getDayOfMonth());
                mBinder.expressions3.updateDate(2003, 4, 17);
            }
        });

        final long timeout = SystemClock.uptimeMillis() + 500;
        while (mBindingObject.year.get() == 1972 && SystemClock.uptimeMillis() < timeout) {
            Thread.sleep(1);
        }

        runTestOnUiThread(new Runnable() {
            @Override
            public void run() {
                assertEquals((Integer)2003, mBindingObject.list.get(1));
                assertEquals((Integer)4, mBindingObject.map.get("two"));
                assertEquals(17, mBindingObject.array.get()[1]);
            }
        });
    }

    public void testExpressions4() throws Throwable {
        makeVisible(mBinder.expressions4);
        runTestOnUiThread(new Runnable() {
            @Override
            public void run() {
                assertEquals(1972, mBindingObject.year.get());
                assertEquals(9, mBindingObject.month.get());
                assertEquals(21, mBindingObject.day.get());
                assertEquals(50, mBinder.expressions4.getYear());
                assertEquals(5, mBinder.expressions4.getMonth());
                assertEquals(21, mBinder.expressions4.getDayOfMonth());
                mBinder.expressions4.updateDate(49, 4, 17);
            }
        });

        final long timeout = SystemClock.uptimeMillis() + 500;
        while (mBindingObject.year.get() == 1972 && SystemClock.uptimeMillis() < timeout) {
            Thread.sleep(1);
        }

        runTestOnUiThread(new Runnable() {
            @Override
            public void run() {
                assertEquals(2040, mBindingObject.year.get());
                assertEquals(6, mBindingObject.month.get());
                assertEquals(17, mBindingObject.day.get());
            }
        });
    }

    public void testChaining() throws Throwable {
        makeVisible(mBinder.checkBox, mBinder.checkBox2);
        runTestOnUiThread(new Runnable() {
            @Override
            public void run() {
                assertTrue(mBinder.checkBox2.isChecked());
                mBindingObject.checked.set(true);
                mBinder.executePendingBindings();
                assertFalse(mBinder.checkBox2.isChecked());
            }
        });
    }

    public void testTwoWayChaining() throws Throwable {
        makeVisible(mBinder.checkBox3, mBinder.checkBox4);
        runTestOnUiThread(new Runnable() {
            @Override
            public void run() {
                assertFalse(mBinder.checkBox3.isChecked());
                assertTrue(mBinder.checkBox4.isChecked());
                mBinder.checkBox3.setChecked(true);
                mBinder.executePendingBindings();
                assertTrue(mBinder.checkBox3.isChecked());
                assertFalse(mBinder.checkBox4.isChecked());
            }
        });
    }

    public void testIncludedTwoWay1() throws Throwable {
        makeVisible(mBinder.included.editText1, mBinder.textView);
        runTestOnUiThread(new Runnable() {
            @Override
            public void run() {
                assertEquals(null, mBindingObject.text.get());
                assertEquals("", mBinder.textView.getText().toString());
                assertEquals("", mBinder.included.editText1.getText().toString());
                mBinder.included.editText1.setText("Hello World");
            }
        });

        final long timeout = SystemClock.uptimeMillis() + 500;
        while (mBindingObject.text.get().isEmpty() && SystemClock.uptimeMillis() < timeout) {
            Thread.sleep(1);
        }

        runTestOnUiThread(new Runnable() {
            @Override
            public void run() {
                assertEquals("Hello World", mBinder.included.editText1.getText().toString());
                assertEquals("Hello World", mBinder.textView.getText().toString());
                assertEquals("Hello World", mBindingObject.text.get());
            }
        });
    }

    public void testIncludedTwoWay2() throws Throwable {
        makeVisible(mBinder.included.editText2, mBinder.textView);
        runTestOnUiThread(new Runnable() {
            @Override
            public void run() {
                assertEquals(null, mBindingObject.text.get());
                assertEquals("", mBinder.textView.getText().toString());
                assertEquals("", mBinder.included.editText2.getText().toString());
                mBinder.included.editText2.setText("Hello World");
            }
        });

        final long timeout = SystemClock.uptimeMillis() + 500;
        while (mBindingObject.text.get().isEmpty() && SystemClock.uptimeMillis() < timeout) {
            Thread.sleep(1);
        }

        runTestOnUiThread(new Runnable() {
            @Override
            public void run() {
                assertEquals("Hello World", mBinder.included.editText2.getText().toString());
                assertEquals("Hello World", mBinder.textView.getText().toString());
                assertEquals("Hello World", mBindingObject.text.get());
            }
        });
    }

    public void testNoEditableLoop() throws Throwable {
        makeVisible(mBinder.editText1, mBinder.editText2);

        final SpannableString text = new SpannableString("Hello World Also");
        BackgroundColorSpan highlight = new BackgroundColorSpan(0xFFFFFF80);
        text.setSpan(highlight, 0, 5, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);

        mBindingObject.textLatch = new CountDownLatch(2);
        runTestOnUiThread(new Runnable() {
            @Override
            public void run() {
                assertEquals("", mBinder.editText1.getText().toString());
                assertEquals(0, mBindingObject.text1Changes);
                assertEquals(0, mBindingObject.text2Changes);

                // Change the text of one of the controls
                mBinder.editText1.setText("Hello World");
            }
        });

        assertTrue(mBindingObject.textLatch.await(500, TimeUnit.MILLISECONDS));
        mBindingObject.textLatch = new CountDownLatch(2);

        runTestOnUiThread(new Runnable() {
            @Override
            public void run() {
                assertNotNull(mBindingObject.editText.get());
                assertEquals("Hello World", mBindingObject.editText.get().toString());
                // They should both be set
                assertEquals(1, mBindingObject.text1Changes);
                assertEquals(1, mBindingObject.text2Changes);

                // Edit the span, but the text remains the same.
                mBinder.editText2.setText(text);
            }
        });

        assertTrue(mBindingObject.textLatch.await(500, TimeUnit.MILLISECONDS));
        mBindingObject.textLatch = new CountDownLatch(1);

        runTestOnUiThread(new Runnable() {
            @Override
            public void run() {
                // The one control should notify a change, but not the other.
                assertEquals(2, mBindingObject.text1Changes);
                assertEquals(2, mBindingObject.text2Changes);

                // No more changes should occur
                mBinder.executePendingBindings();
            }
        });

        assertFalse(mBindingObject.textLatch.await(200, TimeUnit.MILLISECONDS));
        mBindingObject.textLatch = new CountDownLatch(2);

        runTestOnUiThread(new Runnable() {
            @Override
            public void run() {
                // Nothing changed:
                assertEquals(2, mBindingObject.text1Changes);
                assertEquals(2, mBindingObject.text2Changes);

                // Now try changing the value to the same thing. Because the
                // value is Spannable, it will set it to the EditText
                // and then get back a String in the onTextChanged.
                mBindingObject.editText.set(text);
            }
        });

        assertTrue(mBindingObject.textLatch.await(500, TimeUnit.MILLISECONDS));

        runTestOnUiThread(new Runnable() {
            @Override
            public void run() {
                assertEquals(3, mBindingObject.text1Changes);
                assertEquals(3, mBindingObject.text2Changes);
                assertEquals("Hello World Also", mBindingObject.editText.get());
            }
        });
    }

    public void testStringConversions() throws Throwable {
        makeVisible(mBinder.convertBool, mBinder.convertByte, mBinder.convertShort,
                mBinder.convertInt, mBinder.convertLong, mBinder.convertFloat,
                mBinder.convertDouble, mBinder.convertChar);
        runTestOnUiThread(new Runnable() {
            @Override
            public void run() {
                mBinder.convertBool.setText("True");
                mBinder.convertByte.setText("123");
                mBinder.convertShort.setText("1234");
                mBinder.convertInt.setText("12345");
                mBinder.convertLong.setText("123456");
                mBinder.convertFloat.setText("1.2345");
                mBinder.convertDouble.setText("1.23456");
                mBinder.convertChar.setText("a");
            }
        });

        final long timeout = SystemClock.uptimeMillis() + 500;
        while (!mBindingObject.booleanField.get() && SystemClock.uptimeMillis() < timeout) {
            Thread.sleep(1);
        }
        getInstrumentation().waitForIdleSync();
        assertTrue(mBindingObject.booleanField.get());
        assertEquals(123, mBindingObject.byteField.get());
        assertEquals(1234, mBindingObject.shortField.get());
        assertEquals(12345, mBindingObject.intField.get());
        assertEquals(123456, mBindingObject.longField.get());
        assertEquals(1.2345f, mBindingObject.floatField.get(), 0.0001f);
        assertEquals(1.23456, mBindingObject.doubleField.get(), 0.000001);
        assertEquals('a', mBindingObject.charField.get());
    }

    public void testBadStringConversions() throws Throwable {
        makeVisible(mBinder.convertBool, mBinder.convertByte, mBinder.convertShort,
                mBinder.convertInt, mBinder.convertLong, mBinder.convertFloat,
                mBinder.convertDouble, mBinder.convertChar);
        mBindingObject.booleanField.set(true);
        mBindingObject.charField.set('1');
        mBindingObject.byteField.set((byte) 1);
        mBindingObject.shortField.set((short) 12);
        mBindingObject.intField.set(123);
        mBindingObject.longField.set(1234);
        mBindingObject.floatField.set(1.2345f);
        mBindingObject.doubleField.set(1.23456);
        runTestOnUiThread(new Runnable() {
            @Override
            public void run() {
                mBinder.executePendingBindings();
                mBinder.convertBool.setText("foobar");
                mBinder.convertByte.setText("fred");
                mBinder.convertShort.setText("wilma");
                mBinder.convertInt.setText("barney");
                mBinder.convertLong.setText("betty");
                mBinder.convertFloat.setText("pebbles");
                mBinder.convertDouble.setText("bam-bam");
                mBinder.convertChar.setText("");
            }
        });

        final long timeout = SystemClock.uptimeMillis() + 500;
        while (mBindingObject.booleanField.get() && SystemClock.uptimeMillis() < timeout) {
            Thread.sleep(1);
        }
        getInstrumentation().waitForIdleSync();
        assertFalse(mBindingObject.booleanField.get());
        assertEquals(1, mBindingObject.byteField.get());
        assertEquals(12, mBindingObject.shortField.get());
        assertEquals(123, mBindingObject.intField.get());
        assertEquals(1234, mBindingObject.longField.get());
        assertEquals(1.2345f, mBindingObject.floatField.get(), 0.0001f);
        assertEquals(1.23456, mBindingObject.doubleField.get(), 0.00001);
        assertEquals('1', mBindingObject.charField.get());
    }

    private void makeVisible(final View... views) throws Throwable {
        runTestOnUiThread(new Runnable() {
            @Override
            public void run() {
                mBinder.calendarView.setVisibility(View.GONE);
                mBinder.listView.setVisibility(View.GONE);
                mBinder.checkBox.setVisibility(View.GONE);
                mBinder.numberPicker.setVisibility(View.GONE);
                mBinder.ratingBar.setVisibility(View.GONE);
                mBinder.seekBar.setVisibility(View.GONE);
                mBinder.tabhost.setVisibility(View.GONE);
                mBinder.textView.setVisibility(View.GONE);
                mBinder.timePicker.setVisibility(View.GONE);
                mBinder.datePicker.setVisibility(View.GONE);
                mBinder.expressions1.setVisibility(View.GONE);
                mBinder.expressions2.setVisibility(View.GONE);
                mBinder.expressions3.setVisibility(View.GONE);
                mBinder.expressions4.setVisibility(View.GONE);
                mBinder.checkBox2.setVisibility(View.GONE);
                mBinder.checkBox3.setVisibility(View.GONE);
                mBinder.checkBox4.setVisibility(View.GONE);
                mBinder.editText1.setVisibility(View.GONE);
                mBinder.editText2.setVisibility(View.GONE);
                mBinder.included.editText1.setVisibility(View.GONE);
                mBinder.included.editText2.setVisibility(View.GONE);
                mBinder.convertBool.setVisibility(View.GONE);
                mBinder.convertByte.setVisibility(View.GONE);
                mBinder.convertShort.setVisibility(View.GONE);
                mBinder.convertInt.setVisibility(View.GONE);
                mBinder.convertLong.setVisibility(View.GONE);
                mBinder.convertFloat.setVisibility(View.GONE);
                mBinder.convertDouble.setVisibility(View.GONE);
                mBinder.convertChar.setVisibility(View.GONE);
                for (View view : views) {
                    view.setVisibility(View.VISIBLE);
                }
            }
        });
        getInstrumentation().waitForIdleSync();
    }
}
