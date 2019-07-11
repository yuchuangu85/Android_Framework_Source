/*
 * Copyright (C) 2013 The Android Open Source Project
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
package android.support.test;

import junit.framework.TestCase;
import android.os.Bundle;
import android.support.test.BundleTest;
import android.util.Log;

/**
 * Placeholder test to verify {@link Bundle} gets injected to {@link BundleTest}.
 */
public class MyBundleTestCase extends TestCase implements BundleTest {

    private Bundle mBundle = null;

    public MyBundleTestCase() {
        Log.i("MyBundleTestCase", "I'm created");
    }

    @Override
    public void injectBundle(Bundle bundle) {
        mBundle = bundle;
    }

    public void testInjectBundleCalled() {
        assertNotNull(mBundle);
    }
}
