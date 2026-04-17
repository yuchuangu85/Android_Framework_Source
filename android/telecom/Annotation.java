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

package android.telecom;

import android.annotation.IntDef;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Annotations that are relied on outside of the Telecom Framework.
 * Since IntDefs are defined as @hide, if another module uses the annotation,
 * it will not be available to that module. So, we must move these IntDefs to
 * another class that can be referenced directly by the source.
 * @hide
 */
public class Annotation {
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(
        prefix = { "PRESENTATION_" },
        value = {
            TelecomManager.PRESENTATION_ALLOWED,
            TelecomManager.PRESENTATION_RESTRICTED,
            TelecomManager.PRESENTATION_UNKNOWN,
            TelecomManager.PRESENTATION_PAYPHONE,
            TelecomManager.PRESENTATION_UNAVAILABLE
        }
    )
    public @interface Presentation {}

    /**@hide*/
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = "VERIFICATION_STATUS_", value = {
            Connection.VERIFICATION_STATUS_NOT_VERIFIED,
            Connection.VERIFICATION_STATUS_PASSED,
            Connection.VERIFICATION_STATUS_FAILED
    })
    public @interface VerificationStatus {}
}
