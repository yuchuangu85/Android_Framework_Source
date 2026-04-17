/*
 * Copyright 2025 The Android Open Source Project
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
import android.os.Parcelable;

import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.List;

/**
 * An abstract base class for creating a {@link Parcelable} container that can hold an arbitrary
 * number of {@link Parcelable} objects without worrying about
 * {@link android.os.TransactionTooLargeException}.
 *
 * @see Parcel#readBlob()
 * @see Parcel#writeBlob(byte[])
 *
 * @param <T> The type of the {@link Parcelable} objects.
 */
public abstract class AbstractSafeList<T extends Parcelable> implements Parcelable {
    @Nullable
    private byte[] mBuffer;

    protected AbstractSafeList(@Nullable List<T> list) {
        if (list != null && !list.isEmpty()) {
            mBuffer = marshall(list);
        }
    }

    protected AbstractSafeList(@Nullable byte[] buffer) {
        mBuffer = buffer;
    }

    /**
     * Extracts the list of {@link Parcelable} objects from a {@link AbstractSafeList}, and
     * clears the internal buffer of the list.
     *
     * @param from The {@link AbstractSafeList} to extract from.
     * @param creator The {@link Parcelable.Creator} for the {@link Parcelable} objects.
     * @param <T> The type of the {@link Parcelable} objects.
     * @return The list of {@link Parcelable} objects.
     */
    @NonNull
    protected static <T extends Parcelable> List<T> extractFrom(
            @Nullable AbstractSafeList<T> from, @NonNull Parcelable.Creator<T> creator) {
        if (from == null) {
            return new ArrayList<>();
        }
        final byte[] buf = from.mBuffer;
        from.mBuffer = null;
        if (buf != null) {
            final List<T> list = unmarshall(buf, creator);
            if (list != null) {
                return list;
            }
        }
        return new ArrayList<>();
    }

    @Override
    public int describeContents() {
        // As long as the parcelled classes return 0, we can also return 0 here.
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeBlob(mBuffer);
    }

    /**
     * Marshalls a list of {@link Parcelable} objects into a byte array.
     */
    @Nullable
    @VisibleForTesting
    public static <T extends Parcelable> byte[] marshall(@NonNull List<T> list) {
        Parcel parcel = null;
        try {
            parcel = Parcel.obtain();
            parcel.writeTypedList(list);
            return parcel.marshall();
        } finally {
            if (parcel != null) {
                parcel.recycle();
            }
        }
    }

    /**
     * Unmarshalls a byte array into a list of {@link Parcelable} objects.
     */
    @Nullable
    @VisibleForTesting
    public static <T extends Parcelable> List<T> unmarshall(
            @NonNull byte[] data, @NonNull Parcelable.Creator<T> creator) {
        Parcel parcel = null;
        try {
            parcel = Parcel.obtain();
            parcel.unmarshall(data, 0, data.length);
            parcel.setDataPosition(0);
            return parcel.createTypedArrayList(creator);
        } finally {
            if (parcel != null) {
                parcel.recycle();
            }
        }
    }
}
