/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.adservices.common;

import android.annotation.FlaggedApi;
import android.app.sdksandbox.SandboxedSdkContext;
import android.content.Context;
import android.os.Build;

import com.android.adservices.flags.Flags;

/**
 * Class containing some utility functions used by other methods within AdServices.
 *
 * @hide The Rubidium (Rb) Relevance APIs, including those in android.adservices.common, are being
 *     deprecated. Relevance APIs have no direct replacement. Developers should stop using them, as
 *     calls will be rejected in future Android releases. Please refer to official Privacy Sandbox
 *     documentation for deprecation and roadmap details:
 *     https://privacysandbox.com/news/update-on-plans-for-privacy-sandbox-technologies/
 */
@FlaggedApi(Flags.FLAG_ADSERVICES_DEPRECATED)
public final class SandboxedSdkContextUtils {
    private SandboxedSdkContextUtils() {
        // Intended to be a utility class that should not be instantiated.
    }

    /**
     * Checks if the context is an instance of SandboxedSdkContext.
     *
     * @param context the object to check and cast to {@link SandboxedSdkContext}
     * @return the context object cast to {@link SandboxedSdkContext} if it is an instance of {@link
     *     SandboxedSdkContext}, or {@code null} otherwise.
     */
    public static SandboxedSdkContext getAsSandboxedSdkContext(Context context) {
        if (context == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return null; // SandboxedSdkContext is only available in T+
        }

        if (!(context instanceof SandboxedSdkContext)) {
            return null;
        }

        return (SandboxedSdkContext) context;
    }
}
