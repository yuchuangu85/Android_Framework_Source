/*
 * Copyright (C) 2016 The Android Open Source Project
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package android.databinding.testapp.vo;

import android.databinding.BaseObservable;
import android.databinding.Bindable;
import android.databinding.ObservableField;
import android.databinding.ObservableInt;

public class ViewModel extends BaseObservable {
    @Bindable
    public ObservableInt publicObservable = new ObservableInt();

    @Bindable
    private ObservableField<String> fieldObservable = new ObservableField<>();

    private ObservableInt methodObservable = new ObservableInt();


    public ObservableField<String> getFieldObservable() {
        return fieldObservable;
    }

    @Bindable
    public ObservableInt getMethodObservable() {
        return methodObservable;
    }

    public void setFieldObservable(ObservableField<String> fieldObservable) {
        this.fieldObservable = fieldObservable;
    }

    public void setMethodObservable(ObservableInt methodObservable) {
        this.methodObservable = methodObservable;
    }
}
