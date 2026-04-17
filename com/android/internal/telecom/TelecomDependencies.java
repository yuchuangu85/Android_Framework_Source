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

package com.android.internal.telecom;

import android.content.Context;
import android.telecom.TelecomManager;

/**
 * Enumerates Telecom's build time APEX dependencies and loads the correct
 * DependencyAdapter based on the build configuration.
 * @hide
 */
public class TelecomDependencies {

    /**
     * @return true if the telecom mainline build flag is enabled, false if it is not.
     */
    public static boolean isMainlineBuildFlagEnabled() {
        return DependencyAdapter.isMainlineBuildFlagEnabled();
    }

    /**
     * Create the TelecomManager
     * @param context The context used to create the TelecomManager
     * @return The TelecomManager
     */
    public static TelecomManager createTelecomManager(Context context) {
        return DependencyAdapter.createTelecomManager(context);
    }

    /**
     * Registers the TELECOM_SERVICE Service.
     */
    public static void registerServiceWrapper() {
        DependencyAdapter.registerServiceWrapper();
    }
}
