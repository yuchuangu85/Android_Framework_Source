/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.example.android.rs.blasbenchmark;

import com.example.android.rs.blasbenchmark.BlasTest;
import android.os.Bundle;
import android.test.InstrumentationTestRunner;
import android.test.InstrumentationTestSuite;
import junit.framework.TestSuite;

/**
 * BLAS benchmark test.
 * To run the test, please use command
 *
 * adb shell am instrument -e iteration <n> -w com.example.android.rs.blasbenchmark/.BlasTestRunner
 *
 */
public class BlasTestRunner extends InstrumentationTestRunner {
    public int mIteration = 5;

    @Override
    public TestSuite getAllTests() {
        TestSuite suite = new InstrumentationTestSuite(this);
        suite.addTestSuite(BlasTest.class);
        return suite;
    }

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        String strIteration = (String) icicle.get("iteration");
        if (strIteration != null) {
            mIteration = Integer.parseInt(strIteration);
        }
    }
}
