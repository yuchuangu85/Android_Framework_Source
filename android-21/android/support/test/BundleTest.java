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

import android.os.Bundle;

import junit.framework.TestCase;

/**
 * Implement this interface to receive a {@link Bundle} containing the command line arguments
 * passed to the test runner into your JUnit3 test.
 * <p/>
 * The test runner will call {@link #injectBundle(Bundle)} after
 * object construction but before any {@link TestCase#setUp()} methods are called.
 * Note the order in which injectBundle is called vs other inject methods is not defined.
 * <p/>
 * Declaring this in a JUnit4 test will have no effect. Use {@link InjectBundle} instead.
 */
public interface BundleTest {

	/**
	 * Called by Android test runner to pass in Bundle containing command line arguments.
	 */
	public void injectBundle(Bundle bundle);
}
