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

import static java.lang.annotation.ElementType.METHOD;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Denotes that the annotated method is supported on Ravenwood, however the implementation
 * will be replaced with the method suffixed with "$ravenwood".
 * <p>
 * Example:
 * <pre>
 *     @RavenwoodKeepPartialClass
 *     public class Foo {
 *         @RavenwoodReplace
 *         public void doComplex() {
 *             // This method implementation runs as-is on devices, but the
 *             // implementation is replaced/substituted by the
 *             // doComplex$ravenwood() method implementation under Ravenwood
 *         }
 *
 *         private void doComplex$ravenwood() {
 *             // This method implementation only runs under Ravenwood.
 *             // The visibility of this replacement method does not need to match
 *             // the original method, so it's recommended to always use
 *             // private methods so that these methods won't be accidentally used
 *             // by unexpected users.
 *         }
 *     }
 * </pre>
 *
 * @hide
 */
@Target({METHOD})
@Retention(RetentionPolicy.CLASS)
public @interface RavenwoodReplace {
    /**
     * One or more classes that aren't yet supported by Ravenwood, which is why this method is
     * being replaced.
     */
    Class<?>[] blockedBy() default {};

    /**
     * General free-form description of why this method is being replaced.
     */
    String reason() default "";

    /**
     * Tracking bug number, if any.
     */
    long bug() default 0;

    /** Optional, human-readable comment */
    String comment() default "";

    /**
     * If true, the applied method may not fully work on Ravenwood. For example,
     * it may only work for a certain kind of input.
     *
     * This parameter is only for documentation purposes and it won't affect any runtime or
     * build time behavior.
     */
    boolean conditional() default false;
}
