/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.ex.variablespeed;

import android.media.MediaPlayer;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.annotation.concurrent.ThreadSafe;

// TODO: There is sufficent similarity between this and the awaitable error listener that I should
// extract a common base class.
/** Implementation of {@link MediaPlayer.OnErrorListener} that we can wait for in tests. */
@ThreadSafe
public class AwaitableCompletionListener implements MediaPlayer.OnCompletionListener {
    private final BlockingQueue<Object> mQueue = new LinkedBlockingQueue<Object>();

    @Override
    public void onCompletion(MediaPlayer mp) {
        try {
            mQueue.put(new Object());
        } catch (InterruptedException e) {
            // This should not happen in practice, the queue is unbounded so this method will not
            // block.
            // If this thread is using interrupt to shut down, preserve interrupt status and return.
            Thread.currentThread().interrupt();
        }
    }

    public void awaitOneCallback(long timeout, TimeUnit unit) throws InterruptedException,
            TimeoutException {
        if (mQueue.poll(timeout, unit) == null) {
            throw new TimeoutException();
        }
    }

    public void assertNoMoreCallbacks() {
        if (mQueue.peek() != null) {
            throw new IllegalStateException("there was an unexpected callback on the queue");
        }
    }
}
