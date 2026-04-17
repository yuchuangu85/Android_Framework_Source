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

package android.processor.devicepolicy;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Metadata for a string policy.
 */
@Retention(RetentionPolicy.SOURCE)
@Target({ElementType.FIELD})
public @interface StringPolicyDefinition {
    /**
     * Base data for all policies.
     */
    PolicyDefinition base();

    /**
     * By default an empty string is not allowed as a policy value. Set it to true if it should
     * be allowed.
     */
    boolean emptyStringAllowed() default false;
}
