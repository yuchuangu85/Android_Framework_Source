/*
 * Copyright (C) 2025 The Android Open Source Project
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
package com.android.modules.utils.ravenwood;

import java.util.Objects;

/**
 * Class containing constants used by Ravenwood, and accessors to them.
 */
@android.ravenwood.annotation.RavenwoodKeepWholeClass
public class RavenwoodHelper {
    private RavenwoodHelper() {
    }

    private static void throwIfCalledOnDevice() {
        if (!isRunningOnRavenwood()) {
            throw new UnsupportedOperationException("This method can only be used on Ravenwood");
        }
    }

    /**
     * USE IT SPARINGLY! Returns true if it's running on Ravenwood, hostside test environment.
     *
     * <p>Using this allows code to behave differently on a real device and on Ravenwood, but
     * generally speaking, that's a bad idea because we want the test target code to behave
     * differently.
     *
     * <p>This should be only used when different behavior is absolutely needed.
     *
     * <p>If someone needs it without having access to the SDK, the following hack would work too.
     * <code>System.getProperty("android.ravenwood.version") != null</code>
     */
    public static boolean isRunningOnRavenwood() {
        return System.getProperty(RavenwoodInternal.RAVENWOOD_VERSION_JAVA_SYSPROP) != null;
    }

    /**
     * @return the directory path containing the ravenwood runtime.
     *
     * @throws UnsupportedOperationException if called on a non-ravenwood environment
     */
    public static String getRavenwoodRuntimePath() {
        throwIfCalledOnDevice();
        return Objects.requireNonNull(
                System.getProperty(RavenwoodInternal.RAVENWOOD_RUNTIME_PATH_JAVA_SYSPROP),
                        "Ravenwood runtime path not set. (called outside of Ravenwood?)");
    }

    /**
     * @return the directory path containing the aconfig storage files.
     *
     * @throws UnsupportedOperationException if called on a non-ravenwood environment
     */
    public static String getRavenwoodAconfigStoragePath() {
        throwIfCalledOnDevice();
        return getRavenwoodRuntimePath() + "/aconfig";
    }

    /**
     * DO NOT use this class directly from outside the Ravenwood core classes.
     */
    public static class RavenwoodInternal {
        private RavenwoodInternal() {
        }

        public static final String RAVENWOOD_VERSION_JAVA_SYSPROP = "android.ravenwood.version";

        public static final String RAVENWOOD_RUNTIME_PATH_JAVA_SYSPROP =
                "android.ravenwood.runtime_path";

    }
}
