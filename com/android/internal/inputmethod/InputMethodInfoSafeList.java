/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.internal.inputmethod;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Parcel;
import android.view.inputmethod.InputMethodInfo;

import java.util.List;

/**
 * A {@link android.os.Parcelable} container that can hold an arbitrary number of
 * {@link InputMethodInfo} without worrying about
 * {@link android.os.TransactionTooLargeException} when passing across process boundary.
 */
public final class InputMethodInfoSafeList extends AbstractSafeList<InputMethodInfo> {

    private InputMethodInfoSafeList(@Nullable byte[] buffer) {
        super(buffer);
    }

    private InputMethodInfoSafeList(@Nullable List<InputMethodInfo> list) {
        super(list);
    }

    /**
     * Instantiates a list of {@link InputMethodInfo} from the given {@link InputMethodInfoSafeList}
     * then clears the internal buffer of {@link InputMethodInfoSafeList}.
     *
     * <p>Note that each {@link InputMethodInfo} item is guaranteed to be a copy of the original
     * {@link InputMethodInfo} object.</p>
     *
     * <p>Any subsequent call will return an empty list.</p>
     *
     * @param from {@link InputMethodInfoSafeList} from which the list of {@link InputMethodInfo}
     *             will be extracted
     * @return list of {@link InputMethodInfo} stored in the given {@link InputMethodInfoSafeList}
     */
    @NonNull
    public static List<InputMethodInfo> extractFrom(@Nullable InputMethodInfoSafeList from) {
        return AbstractSafeList.extractFrom(from, InputMethodInfo.CREATOR);
    }

    /**
     * Instantiates {@link InputMethodInfoSafeList} from the given list of {@link InputMethodInfo}.
     *
     * @param list list of {@link InputMethodInfo} from which {@link InputMethodInfoSafeList} will
     *             be created. Giving {@code null} will result in an empty
     *             {@link InputMethodInfoSafeList}.
     * @return {@link InputMethodInfoSafeList} that stores the given list of {@link InputMethodInfo}
     */
    @NonNull
    public static InputMethodInfoSafeList create(@Nullable List<InputMethodInfo> list) {
        return new InputMethodInfoSafeList(list);
    }

    public static final Creator<InputMethodInfoSafeList> CREATOR = new Creator<>() {
        @Override
        public InputMethodInfoSafeList createFromParcel(Parcel in) {
            return new InputMethodInfoSafeList(in.readBlob());
        }

        @Override
        public InputMethodInfoSafeList[] newArray(int size) {
            return new InputMethodInfoSafeList[size];
        }
    };
}
