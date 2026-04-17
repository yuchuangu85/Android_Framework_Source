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
package android.ravenwood.annotation;

import static java.lang.annotation.ElementType.TYPE;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Denotes that the annotated class is not officially supported on Ravenwood, however certain
 * methods of the class are allowed to be used.
 * <p>
 * Opting-in additional methods of the class to be used on Ravenwood requires explicit approval
 * from the Ravenwood team.
 *
 * TODO: Add a link to the Ravenwood team's page once it's available.
 *
 * @hide
 */
@Target({TYPE})
@Retention(RetentionPolicy.CLASS)
public @interface RavenwoodPartiallyAllowlisted {
    /** Optional, human-readable comment */
    String comment() default "";

    /**
     * Tracking bug number, if any.
     */
    long bug() default 0;
}
