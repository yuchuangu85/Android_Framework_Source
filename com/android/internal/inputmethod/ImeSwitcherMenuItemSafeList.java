/*
 * Copyright (C) 2026 The Android Open Source Project
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

import android.annotation.Nullable;
import android.os.Parcel;

import java.util.List;

/**
 * A {@link android.os.Parcelable} container that can hold an arbitrary number of
 * {@link IImeSwitcherMenu.Item} without worrying about
 * {@link android.os.TransactionTooLargeException} when passing across process boundary.
 */
public class ImeSwitcherMenuItemSafeList extends AbstractSafeList<IImeSwitcherMenu.Item> {

    private ImeSwitcherMenuItemSafeList(@Nullable byte[] buffer) {
        super(buffer);
    }

    private ImeSwitcherMenuItemSafeList(@Nullable List<IImeSwitcherMenu.Item> list) {
        super(list);
    }

    /**
     * Instantiates a list of {@link IImeSwitcherMenu.Item} from the given
     * {@link ImeSwitcherMenuItemSafeList} then clears the internal buffer of
     * {@link ImeSwitcherMenuItemSafeList}.
     *
     * <p>Note that each {@link IImeSwitcherMenu.Item} item is guaranteed to be a copy of the
     * original {@link IImeSwitcherMenu.Item} object.</p>
     *
     * <p>Any subsequent call will return an empty list.</p>
     *
     * @param from {@link ImeSwitcherMenuItemSafeList} from which the list of
     *             {@link IImeSwitcherMenu.Item} will be extracted
     * @return list of {@link IImeSwitcherMenu.Item} stored in the given
     *         {@link ImeSwitcherMenuItemSafeList}
     */
    public static List<IImeSwitcherMenu.Item> extractFrom(
            @Nullable ImeSwitcherMenuItemSafeList from) {
        return AbstractSafeList.extractFrom(from, IImeSwitcherMenu.Item.CREATOR);
    }

    /**
     * Instantiates {@link ImeSwitcherMenuItemSafeList} from the given list of
     * {@link IImeSwitcherMenu.Item}.
     *
     * @param list list of {@link IImeSwitcherMenu.Item} from which
     *             {@link ImeSwitcherMenuItemSafeList} will be created. Giving {@code null} will
     *             result in an empty {@link ImeSwitcherMenuItemSafeList}.
     * @return {@link ImeSwitcherMenuItemSafeList} that stores the given list of
     *         {@link IImeSwitcherMenu.Item}
     */
    public static ImeSwitcherMenuItemSafeList create(@Nullable List<IImeSwitcherMenu.Item> list) {
        return new ImeSwitcherMenuItemSafeList(list);
    }

    public static final Creator<ImeSwitcherMenuItemSafeList> CREATOR = new Creator<>() {
        @Override
        public ImeSwitcherMenuItemSafeList createFromParcel(Parcel in) {
            return new ImeSwitcherMenuItemSafeList(in.readBlob());
        }

        @Override
        public ImeSwitcherMenuItemSafeList[] newArray(int size) {
            return new ImeSwitcherMenuItemSafeList[size];
        }
    };
}
