/*
 * Copyright (C) 2006 The Android Open Source Project
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

package com.android.internal.util.configinfrastructure;

import dalvik.system.VMRuntime;

import java.io.File;

/**
 * Static utility methods for arrays that aren't already included in {@link java.util.Arrays}.
 *
 * <p>Test with: <code>atest FrameworksUtilTests:com.android.internal.util.ArrayUtilsTest</code>
 * <code>atest FrameworksUtilTestsRavenwood:com.android.internal.util.ArrayUtilsTest</code> This is
 * copied from frameworks/base/core/java/com/android/internal/util/ArrayUtils.java. Any major
 * bugfixes in the original ArrayUtils should be copied here.
 *
 * @hide
 */
@android.ravenwood.annotation.RavenwoodKeepWholeClass
public class ArrayUtils {
    private static final int CACHE_SIZE = 73;
    private static Object[] sCache = new Object[CACHE_SIZE];

    public static final File[] EMPTY_FILE = new File[0];

    private ArrayUtils() {
        /* cannot be instantiated */
    }

    public static long[] newUnpaddedLongArray(int minLen) {
        return (long[]) VMRuntime.getRuntime().newUnpaddedArray(long.class, minLen);
    }

    /**
     * Throws {@link ArrayIndexOutOfBoundsException} if the index is out of bounds.
     *
     * @param len length of the array. Must be non-negative
     * @param index the index to check
     * @throws ArrayIndexOutOfBoundsException if the {@code index} is out of bounds of the array
     */
    public static void checkBounds(int len, int index) {
        if (index < 0 || len <= index) {
            throw new ArrayIndexOutOfBoundsException("length=" + len + "; index=" + index);
        }
    }
}
