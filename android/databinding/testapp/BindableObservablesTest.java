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

import android.annotation.TargetApi;
import android.databinding.ObservableField;
import android.databinding.ObservableInt;
import android.databinding.testapp.BR;
import android.databinding.testapp.databinding.BindableObservablesBinding;
import android.databinding.testapp.databinding.CallbacksBinding;
import android.databinding.testapp.vo.ViewModel;
import android.os.Build;
import android.support.test.runner.AndroidJUnit4;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.CoreMatchers.*;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class BindableObservablesTest {
    @Rule
    public DataBindingTestRule<BindableObservablesBinding> mBindingRule = new DataBindingTestRule<>(
            R.layout.bindable_observables
    );

    @TargetApi(Build.VERSION_CODES.JELLY_BEAN)
    @Test
    public void publicBinding() {
        ViewModel model = new ViewModel();
        model.publicObservable.set(40);
        BindableObservablesBinding binding = mBindingRule.getBinding();
        binding.setModel(model);
        mBindingRule.executePending();
        assertThat(binding.view1.getMaxLines(), is(40));
        model.publicObservable.set(20);
        mBindingRule.executePending();
        assertThat(binding.view1.getMaxLines(), is(20));
    }

    @Test
    public void fieldBinding() {
        ViewModel model = new ViewModel();
        model.getFieldObservable().set("abc");
        BindableObservablesBinding binding = mBindingRule.getBinding();
        binding.setModel(model);
        mBindingRule.executePending();
        assertThat(binding.view2.getText().toString(), is("abc"));
        model.getFieldObservable().set("def");
        mBindingRule.executePending();
        assertThat(binding.view2.getText().toString(), is("def"));
    }

    @TargetApi(Build.VERSION_CODES.JELLY_BEAN)
    @Test
    public void methodBinding() {
        ViewModel model = new ViewModel();
        model.getMethodObservable().set(30);
        BindableObservablesBinding binding = mBindingRule.getBinding();
        binding.setModel(model);
        mBindingRule.executePending();
        assertThat(binding.view3.getMaxLines(), is(30));
        model.getMethodObservable().set(15);
        mBindingRule.executePending();
        assertThat(binding.view3.getMaxLines(), is(15));
    }

    @TargetApi(Build.VERSION_CODES.JELLY_BEAN)
    @Test
    public void publicBindingChangeObservable() {
        ViewModel model = new ViewModel();
        model.publicObservable.set(40);
        BindableObservablesBinding binding = mBindingRule.getBinding();
        binding.setModel(model);
        mBindingRule.executePending();
        assertThat(binding.view1.getMaxLines(), is(40));

        model.publicObservable = new ObservableInt(20);

        mBindingRule.executePending();
        assertThat(binding.view1.getMaxLines(), is(40));

        model.notifyPropertyChanged(BR.publicObservable);
        mBindingRule.executePending();
        assertThat(binding.view1.getMaxLines(), is(20));

    }

    @Test
    public void fieldBindingChangeObservable() {
        ViewModel model = new ViewModel();
        model.getFieldObservable().set("abc");
        BindableObservablesBinding binding = mBindingRule.getBinding();
        binding.setModel(model);
        mBindingRule.executePending();
        assertThat(binding.view2.getText().toString(), is("abc"));

        model.setFieldObservable(new ObservableField<String>("def"));

        mBindingRule.executePending();
        assertThat(binding.view2.getText().toString(), is("abc"));

        model.notifyPropertyChanged(BR.fieldObservable);
        mBindingRule.executePending();
        assertThat(binding.view2.getText().toString(), is("def"));
    }

    @TargetApi(Build.VERSION_CODES.JELLY_BEAN)
    @Test
    public void methodBindingChangeObservable() {
        ViewModel model = new ViewModel();
        model.getMethodObservable().set(30);
        BindableObservablesBinding binding = mBindingRule.getBinding();
        binding.setModel(model);
        mBindingRule.executePending();
        assertThat(binding.view3.getMaxLines(), is(30));

        model.setMethodObservable(new ObservableInt(15));

        mBindingRule.executePending();
        assertThat(binding.view3.getMaxLines(), is(30));

        model.notifyPropertyChanged(BR.methodObservable);
        mBindingRule.executePending();
        assertThat(binding.view3.getMaxLines(), is(15));
    }
}
