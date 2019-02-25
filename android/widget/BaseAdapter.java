/*
 * Copyright (C) 2007 The Android Open Source Project
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

import android.database.DataSetObservable;
import android.database.DataSetObserver;
import android.view.View;
import android.view.ViewGroup;

/**
 * Common base class of common implementation for an {@link Adapter} that can be
 * used in both {@link ListView} (by implementing the specialized
 * {@link ListAdapter} interface) and {@link Spinner} (by implementing the
 * specialized {@link SpinnerAdapter} interface).
 */
public abstract class BaseAdapter implements ListAdapter, SpinnerAdapter {

    // 观察者管理类
    private final DataSetObservable mDataSetObservable = new DataSetObservable();

    public boolean hasStableIds() {
        return false;
    }

    // 注册数据观察者
    public void registerDataSetObserver(DataSetObserver observer) {
        mDataSetObservable.registerObserver(observer);
    }

    // 取消注册数据观察者
    public void unregisterDataSetObserver(DataSetObserver observer) {
        mDataSetObservable.unregisterObserver(observer);
    }

    /**
     * 通知数据改变了
     * <p>
     * Notifies the attached observers that the underlying data has been changed
     * and any View reflecting the data set should refresh itself.
     */
    public void notifyDataSetChanged() {
        mDataSetObservable.notifyChanged();
    }

    /**
     * 通知数据失效了
     * <p>
     * Notifies the attached observers that the underlying data is no longer valid
     * or available. Once invoked this adapter is no longer valid and should
     * not report further data set changes.
     */
    public void notifyDataSetInvalidated() {
        mDataSetObservable.notifyInvalidated();
    }

    // 所有Item是否可点击，默认可以
    public boolean areAllItemsEnabled() {
        return true;
    }

    // 对应位置Item是否可以点击，默认可以
    public boolean isEnabled(int position) {
        return true;
    }

    // 获取SpinnerAdapter下拉的View
    public View getDropDownView(int position, View convertView, ViewGroup parent) {
        return getView(position, convertView, parent);
    }

    // 获取ItemView的类型
    public int getItemViewType(int position) {
        return 0;
    }

    // 获取所有ItemView类型总数，默认是1
    public int getViewTypeCount() {
        return 1;
    }

    // 适配器数据是否为空
    public boolean isEmpty() {
        return getCount() == 0;
    }
}
