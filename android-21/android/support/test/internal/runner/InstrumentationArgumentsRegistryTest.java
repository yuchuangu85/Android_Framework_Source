/*
 * Copyright (C) 2014 The Android Open Source Project
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
package android.support.test.internal.runner;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.lessThan;

import android.os.Bundle;
import android.support.test.internal.runner.InstrumentationArgumentsRegistry;
import android.test.suitebuilder.annotation.SmallTest;

import junit.framework.TestCase;

/**
 * InstrumentationArgumentsRegistry tests.
 */
@SmallTest
public class InstrumentationArgumentsRegistryTest extends TestCase {

    public void testArgumentsArePopulated() {
        assertNotNull(InstrumentationArgumentsRegistry.getInstance());
    }

    public void testModifyingReadBundleShouldNotAffectFutureReads() {
        Bundle readArguments = InstrumentationArgumentsRegistry.getInstance();
        int originalSize = readArguments.size();

        readArguments.putString("mykey", "myvalue");

        assertThat(originalSize, lessThan(readArguments.size()));
        // Subsequent reads should not be affected by the local modifications.
        assertEquals(originalSize, InstrumentationArgumentsRegistry.getInstance().size());
    }

    public void testModifyingSetBundleShouldNotAffectFutureReads() {
        Bundle setArguments = new Bundle();
        int originalSize = setArguments.size();
        InstrumentationArgumentsRegistry.registerInstance(setArguments);
        Bundle readArguments = InstrumentationArgumentsRegistry.getInstance();
        assertEquals(originalSize, readArguments.size());

        readArguments.putString("mykey", "myvalue");

        // Subsequent reads should not be affected by the local modifications.
        assertEquals(originalSize, InstrumentationArgumentsRegistry.getInstance().size());
    }
}