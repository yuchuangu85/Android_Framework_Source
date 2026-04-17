/*
 * Copyright (C) 2026 The Android Open Source Project
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
 * Metadata for a long policy.
 */
@Retention(RetentionPolicy.SOURCE)
@Target({ElementType.FIELD})
public @interface LongPolicyDefinition {
    /**
     * Base data for all policies.
     */
    PolicyDefinition base();

    /**
     * The minimum value this long policy can take, inclusive.
     */
    long minValue() default Long.MIN_VALUE;

    /**
     * The maximum value this long policy can take, inclusive.
     */
    long maxValue() default Long.MAX_VALUE;
}
