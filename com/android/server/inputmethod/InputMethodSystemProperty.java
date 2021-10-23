/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server.inputmethod;

import android.annotation.Nullable;
import android.content.ComponentName;
import android.os.Build;
import android.os.SystemProperties;

/**
 * Various (pseudo) constants about IME behaviors.
 */
public class InputMethodSystemProperty {
    /**
     * System property key for the production use. The value must be either empty or a valid
     * (flattened) component name of the multi-client IME.
     */
    private static final String PROP_PROD_MULTI_CLIENT_IME = "ro.sys.multi_client_ime";

    /**
     * System property key for debugging purpose. The value must be either empty or a valid
     * (flattened) component name of the multi-client IME.
     *
     * <p>This value will be ignored when {@link Build#IS_DEBUGGABLE} returns {@code false}</p>
     */
    private static final String PROP_DEBUG_MULTI_CLIENT_IME = "persist.debug.multi_client_ime";

    @Nullable
    private static ComponentName getMultiClientImeComponentName() {
        if (Build.IS_DEBUGGABLE) {
            // If debuggable, allow developers to override the multi-client IME component name
            // with a different (writable) key.
            final ComponentName debugIme = ComponentName.unflattenFromString(
                    SystemProperties.get(PROP_DEBUG_MULTI_CLIENT_IME, ""));
            if (debugIme != null) {
                return debugIme;
            }
        }
        return ComponentName.unflattenFromString(
                SystemProperties.get(PROP_PROD_MULTI_CLIENT_IME, ""));
    }

    /**
     * {@link ComponentName} of multi-client IME to be used.
     */
    @Nullable
    static final ComponentName sMultiClientImeComponentName = getMultiClientImeComponentName();

    /**
     * {@code true} when multi-client IME is enabled.
     */
    public static final boolean MULTI_CLIENT_IME_ENABLED = (sMultiClientImeComponentName != null);
}
