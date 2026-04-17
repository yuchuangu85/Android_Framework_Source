/*
 * Copyright (C) 2025 The Android Open Source Project
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
package android.util;

import java.util.Arrays;
import java.util.Objects;

/**
 * Implements a fixed array of {@code int} primitives.
 *
 * @hide
 */
@android.ravenwood.annotation.RavenwoodKeepWholeClass
public final class ImmutableIntArray implements Cloneable {

    private final int[] mValues;

    /**
     * Creates a new instance from the current values in the given {@code array}.
     */
    public static ImmutableIntArray from(int[] array) {
        Objects.requireNonNull(array, "array cannot be null");
        return new ImmutableIntArray(array);
    }

    /**
     * Creates a new instance from the current values in the given {@code intArray}.
     */
    public static ImmutableIntArray from(IntArray intArray) {
        Objects.requireNonNull(intArray, "intArray cannot be null");
        return new ImmutableIntArray(intArray);
    }

    private ImmutableIntArray(int[] array) {
        mValues = (array.length == 0)
                ? EmptyArray.INT
                : Arrays.copyOf(array, array.length);
    }

    public ImmutableIntArray(IntArray intArray) {
        mValues = intArray.toArray();
    }

    private ImmutableIntArray(ImmutableIntArray cloned) {
        mValues = cloned.mValues;
    }

    /** Gets the size of the array. */
    public int size() {
        return mValues.length;
    }

    /**
     * Gets the element at the given {@code index}.
     *
     * @throws ArrayIndexOutOfBoundsException if {@code index} is invalid.
     */
    public int get(int index) {
        return mValues[index];
    }

    /** Returns a new array with the contents of this {@code ImmutableIntArray}. */
    public int[] toArray() {
        return Arrays.copyOf(mValues, mValues.length);
    }

    @Override
    public int hashCode() {
        int prime = 31;
        int result = 1;
        result = prime * result + Arrays.hashCode(mValues);
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        ImmutableIntArray other = (ImmutableIntArray) obj;
        return Arrays.equals(mValues, other.mValues);
    }

    @Override
    public ImmutableIntArray clone() {
        return new ImmutableIntArray(this);
    }

    @Override
    public String toString() {
        return Arrays.toString(mValues);
    }
}
