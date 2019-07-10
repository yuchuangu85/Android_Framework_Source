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
package android.databinding.adapters;

import android.databinding.BindingAdapter;
import android.databinding.BindingMethod;
import android.databinding.BindingMethods;
import android.view.View;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemSelectedListener;

@BindingMethods({
        @BindingMethod(type = AdapterView.class, attribute = "android:onItemClick", method = "setOnItemClickListener"),
        @BindingMethod(type = AdapterView.class, attribute = "android:onItemLongClick", method = "setOnItemLongClickListener"),
})
public class AdapterViewBindingAdapter {

    @BindingAdapter("android:onItemSelected")
    public static void setListener(AdapterView view, OnItemSelected listener) {
        setListener(view, listener, null);
    }

    @BindingAdapter("android:onNothingSelected")
    public static void setListener(AdapterView view, OnNothingSelected listener) {
        setListener(view, null, listener);
    }

    @BindingAdapter({"android:onItemSelected", "android:onNothingSelected"})
    public static void setListener(AdapterView view, final OnItemSelected selected,
            final OnNothingSelected nothingSelected) {
        if (selected == null && nothingSelected == null) {
            view.setOnItemSelectedListener(null);
        } else {
            view.setOnItemSelectedListener(
                    new OnItemSelectedComponentListener(selected, nothingSelected));
        }
    }

    public static class OnItemSelectedComponentListener implements OnItemSelectedListener {
        private final OnItemSelected mSelected;
        private final OnNothingSelected mNothingSelected;

        public OnItemSelectedComponentListener(OnItemSelected selected,
                OnNothingSelected nothingSelected) {
            this.mSelected = selected;
            this.mNothingSelected = nothingSelected;
        }
        @Override
        public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
            if (mSelected != null) {
                mSelected.onItemSelected(parent, view, position, id);
            }
        }

        @Override
        public void onNothingSelected(AdapterView<?> parent) {
            if (mNothingSelected != null) {
                mNothingSelected.onNothingSelected(parent);
            }
        }
    }

    public interface OnItemSelected {
        void onItemSelected(AdapterView<?> parent, View view, int position, long id);
    }

    public interface OnNothingSelected {
        void onNothingSelected(AdapterView<?> parent);
    }
}
