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

import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/**
 * Tests that MediaPlayerProxyTestCase contains reasonable tests with a real {@link MediaPlayer}.
 */
public class RealMediaPlayerTest extends MediaPlayerProxyTestCase {
    @Override
    public MediaPlayerProxy createTestMediaPlayer() throws Exception {
        // We have to construct the MediaPlayer on the main thread (or at least on a thread with an
        // associated looper) otherwise we don't get sent the messages when callbacks should be
        // invoked. I've raised a bug for this: http://b/4602011.
        Callable<MediaPlayer> callable = new Callable<MediaPlayer>() {
            @Override
            public MediaPlayer call() throws Exception {
                return new MediaPlayer();
            }
        };
        FutureTask<MediaPlayer> future = new FutureTask<MediaPlayer>(callable);
        getInstrumentation().runOnMainSync(future);
        return DynamicProxy.dynamicProxy(MediaPlayerProxy.class, future.get(1, TimeUnit.SECONDS));
    }
}
