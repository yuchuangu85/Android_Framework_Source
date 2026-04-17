/*
 * Copyright (C) 2024 The Android Open Source Project
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
import android.annotation.IntDef;

import com.android.adservices.flags.Flags;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Utility class containing consent status codes.
 *
 * <p>Those status codes are internal only.
 *
 * @hide The Rubidium (Rb) Relevance APIs, including those in android.adservices.common, are being
 *     deprecated. Relevance APIs have no direct replacement. Developers should stop using them, as
 *     calls will be rejected in future Android releases. Please refer to official Privacy Sandbox
 *     documentation for deprecation and roadmap details:
 *     https://privacysandbox.com/news/update-on-plans-for-privacy-sandbox-technologies/
 */
@FlaggedApi(Flags.FLAG_ADSERVICES_DEPRECATED)
public class ConsentStatus {
    public static final int UNKNOWN = 0;
    public static final int UNSET = 1;
    public static final int REVOKED = 2;
    public static final int GIVEN = 3;
    public static final int SERVICE_NOT_ENABLED = 4;
    public static final int WAS_RESET = 5;

    /**
     * Result codes that are common across various APIs.
     *
     * @hide
     */
    @IntDef(
            prefix = {""},
            value = {UNKNOWN, UNSET, REVOKED, GIVEN, SERVICE_NOT_ENABLED, WAS_RESET})
    @Retention(RetentionPolicy.SOURCE)
    public @interface ConsentStatusCode {}
}
