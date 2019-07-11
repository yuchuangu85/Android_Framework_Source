/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.applaunchtest;

import android.os.Bundle;
import android.test.InstrumentationTestRunner;

/**
 * A special test runner that accepts arguments needed to run {@link AppLaunchTest}.
 */
public class AppLaunchRunner extends InstrumentationTestRunner {

    private String mAppPackageName;
    private long mWaitTime;

    @Override
    public void onCreate(Bundle args) {
        mAppPackageName = args.getString("packageName");
        String waitTimeString = args.getString("appLaunchWait");
        if (waitTimeString != null) {
            mWaitTime = Long.parseLong(waitTimeString);
        } else {
            // default to 7 seconds
            mWaitTime = 7000;
        }
        super.onCreate(args);
    }

    /**
     * Gets the Android application package name to launch. Application must have a launchable
     * activity that handles MAIN intent.
     */
    public String getAppPackageName() {
        return mAppPackageName;
    }

    /**
     * Gets the number of ms to monitor for app crashes after attempting to launch the app.
     */
    public long getAppWaitTime() {
        return mWaitTime;
    }
}
