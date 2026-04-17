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
package android.ravenwood.annotation;

import static java.lang.annotation.ElementType.METHOD;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Redirects the annotated method to the corresponding method in the class specified by
 * {@link RavenwoodRedirectionClass}.
 * <p>
 * This annotation has to be used in conjunction with {@link RavenwoodRedirectionClass}.
 * Each method annotated with {@link RavenwoodRedirect} will be redirected to the corresponding
 * method in the class specified by the value of this annotation.
 * <p>
 * All redirection methods in the redirection class must be static.
 * If the annotated method is static, the redirection method shall have the same signature.
 * If the annotated method is non-static, the redirection method shall have an additional
 * first parameter that is a reference to the {@code this} object.
 *
 * Example:
 * <pre>
 *     @RavenwoodRedirectionClass("Foo_ravenwood")
 *     public class Foo {
 *         @RavenwoodRedirect
 *         public void bar(int i, int j, int k) {
 *             // ...
 *         }
 *
 *         @RavenwoodRedirect
 *         public static void baz(int i, int j, int k) {
 *             // ...
 *         }
 *     }
 *
 *     public class Foo_ravenwod {
 *         public static void bar(Foo foo, int i, int j, int k) {
 *             // The "this" object of the original method is the "foo" parameter here.
 *         }
 *
 *         public static void baz(int i, int j, int k) {
 *             // ...
 *         }
 *     }
 * </pre>
 *
 * @see RavenwoodRedirectionClass
 *
 * @hide
 */
@Target({METHOD})
@Retention(RetentionPolicy.CLASS)
public @interface RavenwoodRedirect {
    /**
     * One or more classes that aren't yet supported by Ravenwood, which is why this method throws.
     */
    Class<?>[] blockedBy() default {};

    /**
     * General free-form description of why this method throws.
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
