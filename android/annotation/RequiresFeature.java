/*
 * Copyright (C) 2018 The Android Open Source Project
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
package android.annotation;

import static java.lang.annotation.ElementType.CONSTRUCTOR;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.SOURCE;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * Denotes that the annotated element requires one or more device features. This
 * is used to auto-generate documentation.
 *
 * @hide
 */
@Retention(SOURCE)
@Target({TYPE,FIELD,METHOD,CONSTRUCTOR})
public @interface RequiresFeature {
    /**
     * The name of the device feature that is required, if precisely one feature
     * is required. If more than one feature is required, specify either
     * {@link #allOf()} or {@link #anyOf()} instead.
     * <p>
     * If specified, {@link #anyOf()} and {@link #allOf()} must both be null.
     */
    String value() default "";

    /**
     * Specifies a list of feature names that are all required.
     * <p>
     * If specified, {@link #anyOf()} and {@link #value()} must both be null.
     */
    String[] allOf() default {};

    /**
     * Specifies a list of permission names where at least one is required
     * <p>
     * If specified, {@link #allOf()} and {@link #value()} must both be null.
     */
    String[] anyOf() default {};

    /**
     * Defines the name of the method that should be called to check whether the feature is
     * available, using the same signature format as javadoc. The feature checking method can have
     * multiple parameters, but the feature name parameter must be of type String and must also be
     * the first String-type parameter.
     * <p>
     * By default, the enforcement is
     * {@link android.content.pm.PackageManager#hasSystemFeature(String)}.
     */
    String enforcement() default("android.content.pm.PackageManager#hasSystemFeature");
}
