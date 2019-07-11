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
package android.databinding.testapp.vo;

import android.content.Context;
import android.databinding.ObservableArrayList;
import android.databinding.ObservableArrayMap;
import android.databinding.ObservableBoolean;
import android.databinding.ObservableByte;
import android.databinding.ObservableChar;
import android.databinding.ObservableDouble;
import android.databinding.ObservableField;
import android.databinding.ObservableFloat;
import android.databinding.ObservableInt;
import android.databinding.ObservableLong;
import android.databinding.ObservableShort;
import android.widget.ArrayAdapter;
import android.widget.ListAdapter;

import java.util.concurrent.CountDownLatch;

public class TwoWayBindingObject {
    private static final String[] VALUES = {
            "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten"
    };
    public final ListAdapter adapter;
    public final ObservableInt selectedItemPosition = new ObservableInt();
    public final ObservableLong date = new ObservableLong(System.currentTimeMillis());
    public final ObservableBoolean checked = new ObservableBoolean();
    public final ObservableInt number = new ObservableInt(1);
    public final ObservableFloat rating = new ObservableFloat(1);
    public final ObservableInt progress = new ObservableInt(1);
    public final ObservableInt currentTab = new ObservableInt();
    public final ObservableField<String> text = new ObservableField<>();
    public final ObservableInt hour = new ObservableInt();
    public final ObservableInt minute = new ObservableInt();
    public final ObservableInt year = new ObservableInt(1972);
    public final ObservableInt month = new ObservableInt(9);
    public final ObservableInt day = new ObservableInt(21);
    public final ObservableArrayList<Integer> list = new ObservableArrayList<>();
    public final ObservableArrayMap<String, Integer> map = new ObservableArrayMap<>();
    public final ObservableField<int[]> array = new ObservableField<>();
    public final ObservableField<CharSequence> editText = new ObservableField<>();
    public final ObservableBoolean booleanField = new ObservableBoolean();
    public final ObservableByte byteField = new ObservableByte();
    public final ObservableShort shortField = new ObservableShort();
    public final ObservableInt intField = new ObservableInt();
    public final ObservableLong longField = new ObservableLong();
    public final ObservableFloat floatField = new ObservableFloat();
    public final ObservableDouble doubleField = new ObservableDouble();
    public final ObservableChar charField = new ObservableChar();
    public int text1Changes;
    public int text2Changes;
    public CountDownLatch textLatch;

    public TwoWayBindingObject(Context context) {
        this.adapter = new ArrayAdapter<>(context, android.R.layout.simple_list_item_1, VALUES);
        int[] arr = new int[10];
        for (int i = 0; i < 10; i++) {
            list.add(i);
            arr[i] = i + 1;
        }
        array.set(arr);
        for (int i = 0; i < VALUES.length; i++) {
            map.put(VALUES[i], i + 1);
        }
    }

    public void textChanged1(CharSequence s, int start, int before, int count) {
        text1Changes++;
        textLatch.countDown();
    }

    public void textChanged2(CharSequence s, int start, int before, int count) {
        text2Changes++;
        textLatch.countDown();
    }
}
