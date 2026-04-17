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

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.TYPE;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Denotes that the annotated method is supported on Ravenwood with a subclass in the ravenwood
 * runtime.
 *
 * For example, most of the {@link android.content.Context} class is supported via
 * the {@code RavenwoodContext} class in the ravenwood runtime.
 *
 * Note, this annotation is purely for documentation and for the dashboard.
 *
 * The annotations are validated by
 * {@code com.android.ravenwoodtest.coretest.RavenwoodSupportedAnnotationTest}.
 *
 * TODO: Make it work class-wide too.
 *
 * @hide
 */
@Target({METHOD})
@Retention(RetentionPolicy.CLASS)
public @interface RavenwoodSupported {
    enum SupportType {
        OTHER,
        /**
         * The API is supported by a subclass in ravenwood-runtime.
         * {@link #subclass} should contain the name of the class.
         */
        SUBCLASS,
    }

    /** How the API is supported. */
    SupportType type();

    /** If it's implemented by a subclass, then its name. */
    String subclass() default "";

    /** Optional, human-readable comment */
    String comment() default "";

    /**
     * Tracking bug number, if any.
     */
    long bug() default 0;

    /**
     * If true, the applied method may not fully work on Ravenwood. For example,
     * it may only work for a certain kind of input.
     *
     * This parameter is only for documentation purposes and it won't affect any runtime or
     * build time behavior.
     */
    boolean conditional() default false;

    /**
     * A marker annotation for a class that provides implementation for {@link RavenwoodSupported}
     * methods. It's just for documentation and doesn't do anything at runtime.
     */
    @Target({TYPE})
    @Retention(RetentionPolicy.RUNTIME)
    @interface RavenwoodProvidingImplementation {
        /** Target class that has methods that are implemented by this class. */
        Class<?> target();

        /** Optional, human-readable comment */
        String comment() default "";
    }
}
