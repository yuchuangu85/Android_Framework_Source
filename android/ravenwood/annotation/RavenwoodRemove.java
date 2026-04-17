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
package android.ravenwood.annotation;

import static java.lang.annotation.ElementType.CONSTRUCTOR;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.TYPE;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Denotes that the annotated target is unsupported on Ravenwood, and it will be completely removed.
 * <p>
 * The target element will actually be removed, so it can't be accessed or even mocked, which
 * is not something normally needed.
 * Consider using {@link RavenwoodThrow} or {@link RavenwoodIgnore} instead.
 *
 * @see RavenwoodThrow
 * @see RavenwoodIgnore
 *
 * @hide
 */
@Target({TYPE, FIELD, METHOD, CONSTRUCTOR})
@Retention(RetentionPolicy.CLASS)
public @interface RavenwoodRemove {
    /**
     * One or more classes that aren't yet supported by Ravenwood, which is why this target
     * is removed.
     */
    Class<?>[] blockedBy() default {};

    /**
     * General free-form description of why this target is removed.
     */
    String reason() default "";

    /**
     * Tracking bug number, if any.
     */
    long bug() default 0;

    /** Optional, human-readable comment */
    String comment() default "";
}
