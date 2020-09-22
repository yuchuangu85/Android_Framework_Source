/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.wifi.util;

import android.annotation.NonNull;

import com.android.server.wifi.Clock;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Utility class for Lock list which control by a missing counter which will trigger a timer when
 * reach the threshold. When timer expires, object will be unlocked. Before unlocked, if object
 * present again, will reset both counter and timer.
 * @param <E>
 */
public class MissingCounterTimerLockList<E> {
    private final int mConsecutiveMissingCountToTriggerTimer;
    private final Clock mClock;
    private final Map<E, LockListEntry> mEntries;

    /**
     * Create a new MissingCounterTimerLockList.
     * @param consecutiveCount missing count threshold.
     * @param clock system clock.
     */
    public MissingCounterTimerLockList(int consecutiveCount, Clock clock) {
        mConsecutiveMissingCountToTriggerTimer = consecutiveCount;
        mClock = clock;
        mEntries = new HashMap<>();
    }

    /**
     * Update a set of object to check if object present or not.
     * @param entrySet set of objects.
     */
    public void update(@NonNull Set<E> entrySet) {
        if (entrySet == null) {
            return;
        }
        for (Map.Entry<E, LockListEntry> mapEntry : mEntries.entrySet()) {
            if (mapEntry.getValue().isExpired()) {
                continue;
            }
            if (entrySet.contains(mapEntry.getKey())) {
                mapEntry.getValue().onPresent();
            } else {
                mapEntry.getValue().onAbsent();
            }
        }
    }

    /**
     * Add a object to lock with timer duration
     * @param entry object to lock.
     * @param duration duration of the timer.
     */
    public void add(@NonNull E entry, long duration) {
        if (entry == null) {
            return;
        }
        mEntries.put(entry, new LockListEntry(duration));
    }

    /**
     * Remove an object from the lock list.
     * @param entry object to remove.
     * @return true if lock list contains this element, otherwise false.
     */
    public boolean remove(@NonNull E entry) {
        return mEntries.remove(entry) != null;
    }

    /**
     * Check if an object is in lock list and still locked.
     * @param entry object to check.
     * @return true if the object is in the list and locking, otherwise false.
     */
    public boolean isLocked(@NonNull E entry) {
        if (entry == null) {
            return false;
        }
        LockListEntry blockTimer = mEntries.get(entry);
        return blockTimer != null && !blockTimer.isExpired();
    }

    /**
     * Return the size of the lock list
     */
    public int size() {
        return mEntries.size();
    }

    /**
     * Clear the whole lock list.
     */
    public void clear() {
        mEntries.clear();
    }

    class LockListEntry {
        private final long mExpiryMs;
        private long mStartTimeStamp;
        private int mCount;

        LockListEntry(long expiryMs) {
            mCount = mConsecutiveMissingCountToTriggerTimer;
            mExpiryMs = expiryMs;
            mStartTimeStamp = mClock.getWallClockMillis();
        }

        void onPresent() {
            if (isExpired()) {
                return;
            }
            mCount = mConsecutiveMissingCountToTriggerTimer;
            mStartTimeStamp = mClock.getWallClockMillis();
        }

        void onAbsent() {
            if (mCount == 0) {
                // Timer already triggered
                return;
            }
            mCount--;
            if (mCount > 0) {
                // Don't need to trigger timer
                return;
            }
            mStartTimeStamp = mClock.getWallClockMillis();
        }

        boolean isExpired() {
            return mCount == 0 && mStartTimeStamp + mExpiryMs < mClock.getWallClockMillis();
        }
    }
}
