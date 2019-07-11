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
package android.support.test.internal.runner.junit3;

import android.app.Instrumentation;
import android.os.Bundle;

import junit.framework.TestResult;
import junit.framework.TestSuite;

import org.junit.Ignore;


/**
 * An extension of {@link TestSuite} that supports Android construct injection into test cases,
 * and properly supports annotation filtering of test cases.
 * <p/>
 * Also tries to use {@link NonLeakyTestSuite} where possible to save memory.
 */
@Ignore
class AndroidTestSuite extends DelegatingFilterableTestSuite {

    private final Bundle mBundle;
    private final Instrumentation mInstr;

    public AndroidTestSuite(Class<?> testClass, Bundle bundle, Instrumentation instr) {
        this(new NonLeakyTestSuite(testClass), bundle, instr);
    }

    public AndroidTestSuite(TestSuite s, Bundle bundle, Instrumentation instr) {
        super(s);
        mBundle = bundle;
        mInstr = instr;
    }

    @Override
    public void run(TestResult result) {
        // wrap the result in a new AndroidTestResult to do the bundle and instrumentation injection
        super.run(new AndroidTestResult(mBundle, mInstr, result));
    }

}
