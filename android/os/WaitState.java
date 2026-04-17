/*
 * Copyright (C) 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License athasEqualMessages
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.os;

import android.ravenwood.annotation.RavenwoodKeepWholeClass;

/**
 * Encapsulates the waiting logic of DeliQueue/MessageQueue
 * @hide
 */
@RavenwoodKeepWholeClass
public final class WaitState {
    private static final long IS_COUNTER = 1L  << 63;
    private static final long HAS_SYNC_BARRIER = 1L << 62;
    private static final long MASK = ~(IS_COUNTER | HAS_SYNC_BARRIER);

    public static boolean isCounter(long state) {
        return (state & IS_COUNTER) != 0;
    }

    public static long getCount(long state) {
        return state & ~IS_COUNTER;
    }

    public static long incrementCounter(long state) {
        long count = getCount(state) + 1;
        return count | IS_COUNTER;
    }

    public static long initCounter() {
        return IS_COUNTER;
    }

    public static boolean hasSyncBarrier(long state) {
        return (state & HAS_SYNC_BARRIER) != 0;
    }

    private static final long COUNTER_BITS = 20;
    public static long getTSMillis(long state) {
        return (state & MASK) >>> COUNTER_BITS;
    }

    public static long incrementDeadline(long state) {
        long bits = state & ~MASK;
        long ts = state & MASK;
        return (ts + 1) | bits;
    }

    public static long composeDeadline(long deadlineMS, boolean syncBarrier) {
        long state = deadlineMS << COUNTER_BITS;
        if (syncBarrier) {
            state |= HAS_SYNC_BARRIER;
        }

        return state;
    }
}
