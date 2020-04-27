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

package android.util;

class ContainerHelpers {

    // This is Arrays.binarySearch(), but doesn't do any argument validation.
    static int binarySearch(int[] array, int size, int value) {
        int lo = 0;
        int hi = size - 1;

        while (lo <= hi) {
            // 高位+低位之各除以 2，通过位运算替代除法以提高运算效率
            final int mid = (lo + hi) >>> 1;
            final int midVal = array[mid];

            if (midVal < value) {// 比中间值大，向右侧查找
                lo = mid + 1;
            } else if (midVal > value) {// 比中间值小，向左侧查找
                hi = mid - 1;
            } else {
                return mid;  // value found
            }
        }
        //上述没找到匹配值的时候，lo最终变成的时候要添加元素的位置(移动到了最右边，所以是hi + 1)
        //此处取反，会变成负数，负数表示没找到，重新取反时代表的是要添加元素的位置
        return ~lo;  // value not present
    }

    static int binarySearch(long[] array, int size, long value) {
        int lo = 0;
        int hi = size - 1;

        while (lo <= hi) {
            final int mid = (lo + hi) >>> 1;
            final long midVal = array[mid];

            if (midVal < value) {
                lo = mid + 1;
            } else if (midVal > value) {
                hi = mid - 1;
            } else {
                return mid;  // value found
            }
        }
        return ~lo;  // value not present
    }
}
