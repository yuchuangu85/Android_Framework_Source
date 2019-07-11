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

import android.util.Log;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Tests for the {@link VariableSpeed} class. */
public class VariableSpeedTest extends MediaPlayerProxyTestCase {
    private static final String TAG = "VariableSpeedTest";

    private ScheduledExecutorService mExecutor = Executors.newScheduledThreadPool(2);

    @Override
    protected void tearDown() throws Exception {
        // I explicitly want to do super's tear-down first, because I need to get it to reset
        // the media player before I can be confident that I can shut down the executor service.
        super.tearDown();
        mExecutor.shutdown();
        if (!mExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
            Log.e(TAG, "Couldn't shut down Executor during test, check your cleanup code!");
        }
        mExecutor = null;
    }

    @Override
    public MediaPlayerProxy createTestMediaPlayer() throws Exception {
        return VariableSpeed.createVariableSpeed(mExecutor);
    }
}
