/*
 * Copyright (C) 2019 The Android Open Source Project
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

import com.android.server.wifi.Clock;

/**
 * Manages a quota that is reset at the beginning of each new time period.
 */
public class TimedQuotaManager {
    private final Clock mClock;

    private final long mQuota;
    private final long mPeriodMillis;
    private final long mStartTimeMillis;

    /**
     * The number of elapsed periods between {@link #mStartTimeMillis} and the time of the last call
     * to {@link #requestQuota()}.
     */
    private long mLastPeriod;
    /** How much quota has been consumed in the current period. */
    private long mConsumedQuota;

    /**
     * Constructor.
     * @param clock Clock instance.
     * @param quota the maximum quota for a given period.
     * @param periodMillis the quota will be reset at the beginning of each new period.
     */
    public TimedQuotaManager(Clock clock, long quota, long periodMillis) {
        mClock = clock;
        mQuota = quota;
        mPeriodMillis = periodMillis;
        mStartTimeMillis = clock.getElapsedSinceBootMillis();
        mLastPeriod = 0;
        mConsumedQuota = 0;
    }

    /**
     * Requests one quota. If there is sufficient remaining quota for the current period,
     * returns true and consumes one quota. Otherwise, returns false.
     */
    public boolean requestQuota() {
        long currentPeriod = getCurrentPeriod();
        if (mLastPeriod < currentPeriod) {
            mLastPeriod = currentPeriod;
            mConsumedQuota = 0;
        }
        if (mConsumedQuota < mQuota) {
            mConsumedQuota++;
            return true;
        }
        return false;
    }

    private long getCurrentPeriod() {
        return (mClock.getElapsedSinceBootMillis() - mStartTimeMillis) / mPeriodMillis;
    }

    @Override
    public String toString() {
        return "TimedQuotaManager{"
                + "mQuota=" + mQuota
                + ", mPeriodMillis=" + mPeriodMillis
                + ", mStartTimeMillis=" + mStartTimeMillis
                + ", mLastPeriod=" + mLastPeriod
                + ", mConsumedQuota=" + mConsumedQuota
                + '}';
    }
}
