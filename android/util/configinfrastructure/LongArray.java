/*
 * Copyright (C) 2013 The Android Open Source Project
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

package android.util.configinfrastructure;

import android.compat.annotation.UnsupportedAppUsage;
import android.os.Build;

import com.android.internal.util.configinfrastructure.ArrayUtils;

/**
 * Implements a growing array of long primitives.
 *
 * <p>This is copied from frameworks/base/core/java/android/util/LongArray.java so ConfigInfra can
 * use ProtoInputStream. Any major bugfixes in the original LongArray should be copied here.
 *
 * @hide
 */
@android.ravenwood.annotation.RavenwoodKeepWholeClass
public class LongArray implements Cloneable {
    private static final int MIN_CAPACITY_INCREMENT = 12;

    private long[] mValues;
    private int mSize;

    /** Creates an empty LongArray with the default initial capacity. */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public LongArray() {
        this(0);
    }

    /** Creates an empty LongArray with the specified initial capacity. */
    public LongArray(int initialCapacity) {
        if (initialCapacity == 0) {
            mValues = EmptyArray.LONG;
        } else {
            mValues = ArrayUtils.newUnpaddedLongArray(initialCapacity);
        }
        mSize = 0;
    }

    /** Appends the specified value to the end of this array. */
    public void add(long value) {
        add(mSize, value);
    }

    /**
     * Inserts a value at the specified position in this array. If the specified index is equal to
     * the length of the array, the value is added at the end.
     *
     * @throws IndexOutOfBoundsException when index &lt; 0 || index &gt; size()
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public void add(int index, long value) {
        ensureCapacity(1);
        int rightSegment = mSize - index;
        mSize++;
        ArrayUtils.checkBounds(mSize, index);

        if (rightSegment != 0) {
            // Move by 1 all values from the right of 'index'
            System.arraycopy(mValues, index, mValues, index + 1, rightSegment);
        }

        mValues[index] = value;
    }

    /** Ensures capacity to append at least <code>count</code> values. */
    private void ensureCapacity(int count) {
        final int currentSize = mSize;
        final int minCapacity = currentSize + count;
        if (minCapacity >= mValues.length) {
            final int targetCap =
                    currentSize
                            + (currentSize < (MIN_CAPACITY_INCREMENT / 2)
                                    ? MIN_CAPACITY_INCREMENT
                                    : currentSize >> 1);
            final int newCapacity = targetCap > minCapacity ? targetCap : minCapacity;
            final long[] newValues = ArrayUtils.newUnpaddedLongArray(newCapacity);
            System.arraycopy(mValues, 0, newValues, 0, currentSize);
            mValues = newValues;
        }
    }

    /** Removes all values from this array. */
    public void clear() {
        mSize = 0;
    }

    @Override
    public LongArray clone() {
        LongArray clone = null;
        try {
            clone = (LongArray) super.clone();
            clone.mValues = mValues.clone();
        } catch (CloneNotSupportedException cnse) {
            /* ignore */
        }
        return clone;
    }

    /** Returns the value at the specified position in this array. */
    @UnsupportedAppUsage
    public long get(int index) {
        ArrayUtils.checkBounds(mSize, index);
        return mValues[index];
    }

    /** Sets the value at the specified position in this array. */
    public void set(int index, long value) {
        ArrayUtils.checkBounds(mSize, index);
        mValues[index] = value;
    }

    /** Returns the number of values in this array. */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public int size() {
        return mSize;
    }
}
