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

package android.databinding.testapp;

import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import android.annotation.TargetApi;
import android.databinding.testapp.databinding.CallbacksBinding;
import android.databinding.testapp.vo.CallbackBindingObject;
import android.databinding.testapp.vo.NotBindableVo;
import android.os.Build;
import android.support.test.runner.AndroidJUnit4;
import android.view.View;
import android.widget.ArrayAdapter;

import org.hamcrest.CoreMatchers;
import org.hamcrest.MatcherAssert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

@RunWith(AndroidJUnit4.class)
public class CallbackTest {
    @Rule
    public DataBindingTestRule<CallbacksBinding> mBindingRule = new DataBindingTestRule<>(
            R.layout.callbacks
    );

    CallbackBindingObject mObj;
    NotBindableVo mOther;
    CallbacksBinding mBinding;

    @Before
    public void setup() throws Throwable {
        mBinding = mBindingRule.getBinding();
        mObj = mock(CallbackBindingObject.class);
        mOther = new NotBindableVo();
        mBinding.setObj(mObj);
        mBinding.setOtherObj(mOther);
        mBindingRule.executePending();
        mBindingRule.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                verifyZeroInteractions(mObj);
            }
        });

    }

    @Test
    public void testRegularClick() throws Throwable {
        mBindingRule.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mBinding.view1.performClick();
                verify(mObj, times(1)).onClick();
                verify(mObj, never()).onClick(any(View.class));
                verify(mObj, never()).onClickWithParam(any(NotBindableVo.class));
                verify(mObj, never()).onClickWithParam(any(View.class), any(NotBindableVo.class));
            }
        });
    }

    @Test
    public void testClickWithCallbackArg() throws Throwable {
        mBindingRule.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mBinding.view2.performClick();
                verify(mObj, never()).onClick();
                verify(mObj, times(1)).onClick(mBinding.view2);
                verify(mObj, never()).onClickWithParam(any(NotBindableVo.class));
                verify(mObj, never()).onClickWithParam(any(View.class), any(NotBindableVo.class));
            }
        });
    }

    @Test
    public void testClickWithAnotherVariableAsArg() throws Throwable {
        mBindingRule.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mBinding.view3.performClick();
                verify(mObj, never()).onClick();
                verify(mObj, never()).onClick(any(View.class));
                verify(mObj, times(1)).onClickWithParam(eq(mOther));
                verify(mObj, never()).onClickWithParam(any(View.class), any(NotBindableVo.class));
            }
        });
    }

    @Test
    public void testClickWithViewAndAnotherVariableAsArgs() throws Throwable {
        mBindingRule.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mBinding.view4.performClick();
                verify(mObj, never()).onClick();
                verify(mObj, never()).onClick(any(View.class));
                verify(mObj, never()).onClickWithParam(any(NotBindableVo.class));
                verify(mObj, times(1)).onClickWithParam(mBinding.view4, mOther);
            }
        });
    }

    @Test
    public void nullObjectInCallback() throws Throwable {
        mBinding.setObj(null);
        mBindingRule.executePending();
        mBindingRule.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mBinding.view1.performClick();
                mBinding.view2.performClick();
                mBinding.view3.performClick();
                mBinding.view4.performClick();

                MatcherAssert.assertThat(mBinding.view1.performLongClick(), CoreMatchers.is(false));
                MatcherAssert.assertThat(mBinding.view2.performLongClick(), CoreMatchers.is(false));
                MatcherAssert.assertThat(mBinding.view3.performLongClick(), CoreMatchers.is(false));
                MatcherAssert.assertThat(mBinding.view4.performLongClick(), CoreMatchers.is(false));

            }
        });
        verifyZeroInteractions(mObj);
    }

    // long click
    @Test
    public void testRegularLongClick() throws Throwable {
        mBindingRule.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                when(mObj.onLongClick()).thenReturn(true);
                MatcherAssert.assertThat(mBinding.view1.performLongClick(), CoreMatchers.is(true));
                verify(mObj, times(1)).onLongClick();
                verify(mObj, never()).onLongClick(any(View.class));
                verify(mObj, never()).onLongClickWithParam(any(NotBindableVo.class));
                verify(mObj, never()).onLongClickWithParam(any(View.class), any(NotBindableVo
                        .class));
            }
        });
    }

    @Test
    public void testLongClickWithCallbackArg() throws Throwable {
        mBindingRule.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                when(mObj.onLongClick(mBinding.view2)).thenReturn(true);
                MatcherAssert.assertThat(mBinding.view2.performLongClick(), CoreMatchers.is(true));
                verify(mObj, never()).onLongClick();
                verify(mObj, times(1)).onLongClick(mBinding.view2);
                verify(mObj, never()).onLongClickWithParam(any(NotBindableVo.class));
                verify(mObj, never()).onLongClickWithParam(any(View.class), any(NotBindableVo
                        .class));
            }
        });
    }

    @Test
    public void testLongClickWithAnotherVariableAsArg() throws Throwable {
        mBindingRule.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                when(mObj.onLongClickWithParam(mOther)).thenReturn(true);
                MatcherAssert.assertThat(mBinding.view3.performLongClick(), CoreMatchers.is(true));
                verify(mObj, never()).onLongClick();
                verify(mObj, never()).onLongClick(any(View.class));
                verify(mObj, times(1)).onLongClickWithParam(mOther);
                verify(mObj, never()).onLongClickWithParam(any(View.class), any(NotBindableVo
                        .class));
            }
        });
    }

    @Test
    public void testLongClickWithViewAndAnotherVariableAsArgs() throws Throwable {
        mBindingRule.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                when(mObj.onLongClickWithParam(mBinding.view4, mOther)).thenReturn(true);
                MatcherAssert.assertThat(mBinding.view4.performLongClick(), CoreMatchers.is(true));
                verify(mObj, never()).onLongClick();
                verify(mObj, never()).onLongClick(any(View.class));
                verify(mObj, never()).onLongClickWithParam(any(NotBindableVo.class));
                verify(mObj, times(1)).onLongClickWithParam(mBinding.view4, mOther);
            }
        });
    }

    @Test
    public void testListViewOnScroll() throws Throwable {
        final CallbackBindingObject obj2 = mock(CallbackBindingObject.class);
        mBinding.setObj2(obj2);
        mBindingRule.executePending();
        mBindingRule.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                // this is going to trigger scroll
                mBinding.listView.setAdapter(new ArrayAdapter<>(mBinding.listView.getContext(),
                        android.R.layout.simple_list_item_1, Arrays.asList("a", "b")));
            }
        });
        mBindingRule.runOnUiThread(new Runnable() {
            @TargetApi(Build.VERSION_CODES.KITKAT)
            @Override
            public void run() {
                // setting listener also calls the callback
                verify(obj2).onScrolled();
            }
        });
    }

    @Test
    public void testProgressChange() throws Throwable {
        mBindingRule.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mBinding.seekBar.setProgress(20);
                verify(mObj, times(1)).onProgressChanged(mBinding.seekBar, 20, false);
            }
        });
    }

    @Test
    public void testStaticCallViaClass() throws Throwable {
        staticCall(mBinding.view5);
    }

    @Test
    public void testStaticCallViaInstance() throws Throwable {
        staticCall(mBinding.view6);
    }

    @Test
    public void testVariableOverride() throws Throwable {
        mBindingRule.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mBinding.view8.performClick();
                verify(mObj).onClick(mBinding.view8);
            }
        });
    }

    @Test
    public void testArrayAccess() throws Throwable {
        final CallbackBindingObject[] objects = new CallbackBindingObject[] {
                mock(CallbackBindingObject.class),
                mock(CallbackBindingObject.class),
                mock(CallbackBindingObject.class),
        };
        mBinding.setObjArr(objects);
        mBindingRule.executePending();
        mBindingRule.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                verifyZeroInteractions(objects);
                mBinding.view7.performClick();
                verify(objects[1]).onClick(mBinding.view7);
                mBinding.view7.performLongClick();
                verify(objects[2]).onLongClick(mBinding.view7);
                verifyZeroInteractions(objects[0]);
            }
        });
    }

    @Test
    public void testStaticVariableFullPackage() throws Throwable {
        mBindingRule.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mBinding.view9.performClick();
                verify(mObj).setVisible(View.VISIBLE);
            }
        });
    }

    @Test
    public void testStaticVariableImported() throws Throwable {
        mBindingRule.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mBinding.view10.performClick();
                verify(mObj).setVisible(NotBindableVo.STATIC_VAL);
            }
        });
    }

    @Test
    public void testTernary1() throws Throwable {
        mBindingRule.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mBinding.view11.setFocusable(false);
                mBinding.view11.performClick();
                verify(mObj).onNotFocusable();
                verify(mObj, never()).onFocusable();
            }
        });
    }

    @Test
    public void testTernary2() throws Throwable {
        mBindingRule.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mBinding.view11.setFocusable(true);
                mBinding.view11.performClick();
                verify(mObj).onFocusable();
                verify(mObj, never()).onNotFocusable();
            }
        });
    }

    @Test
    public void testTernary3() throws Throwable {
        mBindingRule.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mBinding.view11.setFocusable(false);
                when(mObj.onFocusable()).thenReturn(true, false);
                when(mObj.onNotFocusable()).thenReturn(false, true);
                MatcherAssert.assertThat(mBinding.view11.performLongClick(), CoreMatchers.is(false));
                MatcherAssert.assertThat(mBinding.view11.performLongClick(), CoreMatchers.is(true));
                mBinding.view11.setFocusable(true);
                MatcherAssert.assertThat(mBinding.view11.performLongClick(), CoreMatchers.is(true));
                MatcherAssert.assertThat(mBinding.view11.performLongClick(), CoreMatchers.is(false));
            }
        });
    }

    @Test
    public void testTernary4() throws Throwable {
        mBindingRule.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mBinding.view11.setFocusable(true);
                mBinding.view11.performClick();
                verify(mObj).onFocusable();
                verify(mObj, never()).onNotFocusable();
            }
        });
    }

    private void staticCall(final View view) throws Throwable {
        final AtomicInteger counter = NotBindableVo.sStaticCounter;
        final int start = counter.get();
        mBindingRule.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                view.performClick();
                MatcherAssert.assertThat(counter.get(), CoreMatchers.is(start + 1));
                MatcherAssert.assertThat(view.performLongClick(), CoreMatchers.is(true));
                MatcherAssert.assertThat(counter.get(), CoreMatchers.is(start + 2));
            }
        });
    }
}
