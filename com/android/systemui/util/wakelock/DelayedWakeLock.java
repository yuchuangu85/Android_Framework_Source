/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.systemui.util.wakelock;

import android.content.Context;
import android.os.Handler;

import javax.inject.Inject;

/**
 * A wake lock that has a built in delay when releasing to give the framebuffer time to update.
 */
public class DelayedWakeLock implements WakeLock {

    private static final String TO_STRING_PREFIX = "[DelayedWakeLock] ";
    private static final long RELEASE_DELAY_MS = 100;

    private final Handler mHandler;
    private final WakeLock mInner;

    public DelayedWakeLock(Handler h, WakeLock inner) {
        mHandler = h;
        mInner = inner;
    }

    @Override
    public void acquire(String why) {
        mInner.acquire(why);
    }

    @Override
    public void release(String why) {
        mHandler.postDelayed(() -> mInner.release(why), RELEASE_DELAY_MS);
    }

    @Override
    public Runnable wrap(Runnable r) {
        return WakeLock.wrapImpl(this, r);
    }

    @Override
    public String toString() {
        return TO_STRING_PREFIX + mInner;
    }

    /**
     * An injectable builder for {@see DelayedWakeLock} that has the context already filled in.
     */
    public static class Builder {
        private final Context mContext;
        private String mTag;
        private Handler mHandler;

        /**
         * Constructor for DelayedWakeLock.Builder
         */
        @Inject
        public Builder(Context context) {
            mContext = context;
        }

        /**
         * Set the tag for the WakeLock.
         */
        public Builder setTag(String tag) {
            mTag = tag;

            return this;
        }

        /**
         * Set the handler for the DelayedWakeLock.
         */
        public Builder setHandler(Handler handler) {
            mHandler = handler;

            return this;
        }

        /**
         * Build the DelayedWakeLock.
         */
        public DelayedWakeLock build() {
            return new DelayedWakeLock(mHandler, WakeLock.createPartial(mContext, mTag));
        }
    }
}
