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

import static android.support.test.internal.util.Checks.checkNotNull;

import android.app.Instrumentation;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Holds a reference to the instrumentation running in the process.
 */
public final class InstrumentationRegistry {

    private static final AtomicReference<Instrumentation> sInstrumentationRef =
            new AtomicReference<Instrumentation>(null);

    /**
     * Returns the instrumentation currently running.
     *
     * @throws IllegalStateException if instrumentation hasn't been registered
     */
    public static Instrumentation getInstance() {
        return checkNotNull(sInstrumentationRef.get(), "No instrumentation registered. " +
                "Must run under a registering instrumentation.");
    }

    /**
     * Records/exposes the instrumentation currently running.
     * <p>
     * This is a global registry - so be aware of the impact of calling this method!
     * </p>
     */
    public static void registerInstance(Instrumentation instrumentation) {
        sInstrumentationRef.set(instrumentation);
    }

    private InstrumentationRegistry() { }
}
