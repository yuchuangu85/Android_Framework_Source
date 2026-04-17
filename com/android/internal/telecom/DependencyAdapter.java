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
import android.telecom.TelecomFrameworkInitializer;
import android.telecom.TelecomManager;

/**
 * The DependencyAdapter that is used to compile when mainline is enabled.
 * @hide
 */
public class DependencyAdapter {

    public static boolean isMainlineBuildFlagEnabled() {
        return true;
    }

    /**
     * This method should not be called when building as a Telecom mainline module
     */
    public static TelecomManager createTelecomManager(Context context) {
        throw new UnsupportedOperationException("telecomMainlineApi disabled while release flag is"
                + " enabled");
    }

    /**
     * Registers the TELECOM_SERVICE Service using mainline APIs.
     */
    public static void registerServiceWrapper() {
        TelecomFrameworkInitializer.registerServiceWrapper();
    }
}
