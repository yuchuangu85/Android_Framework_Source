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

package com.android.internal.telephony;

import android.annotation.NonNull;
import android.os.Handler;
import android.os.Looper;

/**
 * Schedules a runnable to be executed with an exponential backoff delay.
 * The implementation of exponential backoff with jitter applied.
 * @hide
 */
public class ExponentialBackoffRetry {
    private int mRetryCounter;
    private long mStartDelayMs;
    private long mMaximumDelayMs;
    private long mCurrentDelayMs;
    private int mMultiplier;
    @NonNull
    private final Runnable mRunnable;
    @NonNull
    private final Handler mHandler;

    @NonNull
    private HandlerAdapter mHandlerAdapter = new HandlerAdapter() {
        @Override
        public boolean postDelayed(@NonNull Runnable runnable, long delayMillis) {
            return mHandler.postDelayed(runnable, delayMillis);
        }

        @Override
        public void removeCallbacks(@NonNull Runnable runnable) {
            mHandler.removeCallbacks(runnable);
        }
    };

    /**
     * An adapter for Handler to allow unit testing of final methods.
     */
    public interface HandlerAdapter {
        /** @see android.os.Handler#postDelayed(Runnable, long) */
        boolean postDelayed(@NonNull Runnable runnable, long delayMillis);
        /** @see android.os.Handler#removeCallbacks(Runnable) */
        void removeCallbacks(@NonNull Runnable runnable);
    }

    public ExponentialBackoffRetry(
            long initialDelayMs,
            long maximumDelayMs,
            int multiplier,
            @NonNull Looper looper,
            @NonNull Runnable runnable) {
        this(initialDelayMs, maximumDelayMs, multiplier, new Handler(looper), runnable);
    }

    public ExponentialBackoffRetry(
            long initialDelayMs,
            long maximumDelayMs,
            int multiplier,
            @NonNull Handler handler,
            @NonNull Runnable runnable) {
        mRetryCounter = 0;
        mStartDelayMs = initialDelayMs;
        mMaximumDelayMs = maximumDelayMs;
        mMultiplier = multiplier;
        mHandler = handler;
        mRunnable = runnable;
    }

    /** Starts the backoff, the runnable will be executed after {@link #mStartDelayMs}. */
    public void start() {
        mRetryCounter = 0;
        mCurrentDelayMs = mStartDelayMs;
        mHandlerAdapter.removeCallbacks(mRunnable);
        mHandlerAdapter.postDelayed(mRunnable, mCurrentDelayMs);
    }

    /** Stops the backoff, all pending messages will be removed from the message queue. */
    public void stop() {
        mRetryCounter = 0;
        mHandlerAdapter.removeCallbacks(mRunnable);
    }

    /**
     * Notifies the scheduler that the action has failed and schedules the next retry with
     * a longer, jittered delay.
     */
    public void notifyFailed() {
        mRetryCounter++;
        long temp = Math.min(
                mMaximumDelayMs, (long) (mStartDelayMs * Math.pow(mMultiplier, mRetryCounter)));
        // Apply jitter to the calculated delay
        mCurrentDelayMs = (long) (((1 + Math.random()) / 2) * temp);
        mHandlerAdapter.removeCallbacks(mRunnable);
        mHandlerAdapter.postDelayed(mRunnable, mCurrentDelayMs);
    }
}
