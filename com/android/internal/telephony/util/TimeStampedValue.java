/*
 * Copyright 2017 The Android Open Source Project
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

package com.android.internal.telephony.util;

import android.os.SystemClock;

/**
 * A pair containing a value and an associated time stamp.
 *
 * @param <T> The type of the value.
 */
public final class TimeStampedValue<T> {

    /** The value. */
    public final T mValue;

    /**
     * The value of {@link SystemClock#elapsedRealtime} or equivalent when value was
     * determined.
     */
    public final long mElapsedRealtime;

    public TimeStampedValue(T value, long elapsedRealtime) {
        this.mValue = value;
        this.mElapsedRealtime = elapsedRealtime;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        TimeStampedValue<?> that = (TimeStampedValue<?>) o;

        if (mElapsedRealtime != that.mElapsedRealtime) {
            return false;
        }
        return mValue != null ? mValue.equals(that.mValue) : that.mValue == null;
    }

    @Override
    public int hashCode() {
        int result = mValue != null ? mValue.hashCode() : 0;
        result = 31 * result + (int) (mElapsedRealtime ^ (mElapsedRealtime >>> 32));
        return result;
    }

    @Override
    public String toString() {
        return "TimeStampedValue{"
                + "mValue=" + mValue
                + ", elapsedRealtime=" + mElapsedRealtime
                + '}';
    }
}
