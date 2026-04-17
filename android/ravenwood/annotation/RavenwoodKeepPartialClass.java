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

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Denotes that the annotated class is partially supported on Ravenwood.
 * <p>
 * Methods in this class are not supported on Ravenwood by default.
 * Each method must explicitly opt-in to be supported on Ravenwood by annotating it with either
 * {@link RavenwoodKeep}, {@link RavenwoodReplace}, or {@link RavenwoodRedirect}.
 *
 * @see RavenwoodKeepWholeClass
 * @see RavenwoodKeepStaticInitializer
 *
 * @hide
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.CLASS)
public @interface RavenwoodKeepPartialClass {
    /** Optional, human-readable comment */
    String comment() default "";

    /**
     * Tracking bug number, if any.
     */
    long bug() default 0;
}
