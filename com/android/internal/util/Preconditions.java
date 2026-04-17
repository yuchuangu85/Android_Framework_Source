/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.internal.util;

import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.compat.annotation.UnsupportedAppUsage;
import android.os.Build;
import android.text.TextUtils;
import com.google.errorprone.annotations.InlineMe;
import com.google.errorprone.annotations.CompileTimeConstant;
import com.google.errorprone.annotations.FormatMethod;
import com.google.errorprone.annotations.FormatString;

import java.util.Arrays;
import java.util.Collection;
import java.util.Objects;

/**
 * Simple static methods to be called at the start of your own methods to verify correct arguments
 * and state.
 */
public class Preconditions {

    /**
     * Ensures that an expression checking an argument is true.
     *
     * @param expression the expression to check
     * @throws IllegalArgumentException if {@code expression} is false
     */
    @UnsupportedAppUsage
    public static void checkArgument(boolean expression) {
        if (!expression) {
            illegalArgumentException();
        }
    }

    /**
     * Ensures that an expression checking an argument is true.
     *
     * @param expression the expression to check
     * @param errorMessage the exception message to use if the check fails; will be converted to a
     *     string using {@link String#valueOf(Object)}
     * @throws IllegalArgumentException if {@code expression} is false
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public static void checkArgument(
            boolean expression, final @CompileTimeConstant Object errorMessage) {
        if (!expression) {
            illegalArgumentExceptionObj(errorMessage);
        }
    }

    /**
     * Ensures that an expression checking an argument is true.
     *
     * @param expression the expression to check
     * @param messageTemplate a printf-style message template to use if the check fails; will be
     *     converted to a string using {@link String#format(String, Object...)}
     * @param messageArgs arguments for {@code messageTemplate}
     * @throws IllegalArgumentException if {@code expression} is false
     */
    @FormatMethod
    public static void checkArgument(
            final boolean expression,
            final @FormatString @CompileTimeConstant String messageTemplate,
            final Object... messageArgs) {
        if (!expression) {
            illegalArgumentException(messageTemplate, messageArgs);
        }
    }

    /**
     * Ensures that an string reference passed as a parameter to the calling method is not empty.
     *
     * @param string an string reference
     * @return the string reference that was validated
     * @throws IllegalArgumentException if {@code string} is empty
     */
    public static @NonNull <T extends CharSequence> T checkStringNotEmpty(final T string) {
        if (TextUtils.isEmpty(string)) {
            illegalArgumentException();
        }
        return string;
    }

    /**
     * Ensures that an string reference passed as a parameter to the calling method is not empty.
     *
     * If {@link #checkStringNotEmptyWithName(CharSequence, String)} is applicable, please use it
     * instead.
     *
     * @param string an string reference
     * @param errorMessage the exception message to use if the check fails; will be converted to a
     *     string using {@link String#valueOf(Object)}
     * @return the string reference that was validated
     * @throws IllegalArgumentException if {@code string} is empty
     * @see #checkStringNotEmptyWithName(CharSequence, String)
     */
    public static @NonNull <T extends CharSequence> T checkStringNotEmpty(
            final T string, final @CompileTimeConstant Object errorMessage) {
        if (TextUtils.isEmpty(string)) {
            illegalArgumentExceptionObj(errorMessage);
        }
        return string;
    }

    /**
     * Ensures that an string reference passed as a parameter to the calling method is not empty.
     *
     * @param string an string reference
     * @param messageTemplate a printf-style message template to use if the check fails; will be
     *     converted to a string using {@link String#format(String, Object...)}
     * @param messageArgs arguments for {@code messageTemplate}
     * @return the string reference that was validated
     * @throws IllegalArgumentException if {@code string} is empty
     */
    @FormatMethod
    public static @NonNull <T extends CharSequence> T checkStringNotEmpty(
            final T string,
            final @FormatString @CompileTimeConstant String messageTemplate,
            final Object... messageArgs) {
        if (TextUtils.isEmpty(string)) {
            illegalArgumentException(messageTemplate, messageArgs);
        }
        return string;
    }

    /**
     * Ensures that an string reference passed as a parameter to the calling method is not empty.
     *
     * @param value a string reference
     * @param name the name of the value
     * @return the string reference that was validated
     * @throws IllegalArgumentException if {@code value} is null or an empty string, with a message
     *     in the format of "name must not be empty"
     */
    public static @NonNull <T extends CharSequence> T checkStringNotEmptyWithName(final T value,
            final @CompileTimeConstant String name) {
        if (TextUtils.isEmpty(value)) {
            illegalArgumentExceptionForEmpty(name);
        }
        return value;
    }

    /**
     * Ensures that an object reference passed as a parameter to the calling method is not null.
     *
     * @param reference an object reference
     * @return the non-null reference that was validated
     * @throws NullPointerException if {@code reference} is null
     * @deprecated Use {@link Objects#requireNonNull(Object)} instead.
     */
    @UnsupportedAppUsage
    @Deprecated
    public static @NonNull <T> T checkNotNull(final T reference) {
        if (reference == null) {
            nullPointerException();
        }
        return reference;
    }

    /**
     * Ensures that an object reference passed as a parameter to the calling method is not null.
     *
     * @param reference an object reference
     * @param errorMessage the exception message to use if the check fails; will be converted to a
     *     string using {@link String#valueOf(Object)}
     * @return the non-null reference that was validated
     * @throws NullPointerException if {@code reference} is null
     * @deprecated - use {@link java.util.Objects#requireNonNull} instead.
     */
    @Deprecated
    @UnsupportedAppUsage
    public static @NonNull <T> T checkNotNull(
            final T reference, final @CompileTimeConstant Object errorMessage) {
        if (reference == null) {
            nullPointerExceptionObj(errorMessage);
        }
        return reference;
    }

    /**
     * Ensures that an object reference passed as a parameter to the calling method is not null.
     *
     * @param messageTemplate a printf-style message template to use if the check fails; will be
     *     converted to a string using {@link String#format(String, Object...)}
     * @param messageArgs arguments for {@code messageTemplate}
     * @throws NullPointerException if {@code reference} is null
     */
    @FormatMethod
    public static @NonNull <T> T checkNotNull(
            final T reference,
            final @FormatString @CompileTimeConstant String messageTemplate,
            final Object... messageArgs) {
        if (reference == null) {
            nullPointerException(messageTemplate, messageArgs);
        }
        return reference;
    }

    /**
     * Ensures that an object reference passed as a parameter to the calling method is not null.
     *
     * If {@link #checkNotNullWithName(Object, String)} is applicable, please use it instead.
     *
     * @param reference an object reference
     * @param name the name of the object
     * @return the non-null reference that was validated
     * @throws NullPointerException if {@code reference} is null, with a message in the format of
     *     "name must not be null"
     */
    @UnsupportedAppUsage
    public static @NonNull <T> T checkNotNullWithName(
            final T reference, final @CompileTimeConstant String name) {
        if (reference == null) {
            nullPointerException(name);
        }
        return reference;
    }

    /**
     * Ensures the truth of an expression involving the state of the calling instance, but not
     * involving any parameters to the calling method.
     *
     * @param expression a boolean expression
     * @throws IllegalStateException if {@code expression} is false
     */
    @UnsupportedAppUsage
    public static void checkState(final boolean expression) {
        checkState(expression, null);
    }

    /**
     * Ensures the truth of an expression involving the state of the calling instance, but not
     * involving any parameters to the calling method.
     *
     * @param expression a boolean expression
     * @param errorMessage the exception message to use if the check fails; will be converted to a
     *     string using {@link String#valueOf(Object)}
     * @throws IllegalStateException if {@code expression} is false
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public static void checkState(final boolean expression, String errorMessage) {
        if (!expression) {
            illegalStateException(errorMessage);
        }
    }

    /**
     * Ensures the truth of an expression involving the state of the calling instance, but not
     * involving any parameters to the calling method.
     *
     * @param expression a boolean expression
     * @param messageTemplate a printf-style message template to use if the check fails; will be
     *     converted to a string using {@link String#format(String, Object...)}
     * @param messageArgs arguments for {@code messageTemplate}
     * @throws IllegalStateException if {@code expression} is false
     */
    @FormatMethod
    public static void checkState(
            final boolean expression,
            final @FormatString @CompileTimeConstant String messageTemplate,
            final Object... messageArgs) {
        if (!expression) {
            illegalStateException(messageTemplate, messageArgs);
        }
    }

    /**
     * Ensures the truth of an expression involving whether the calling identity is authorized to
     * call the calling method.
     *
     * @param expression a boolean expression
     * @throws SecurityException if {@code expression} is false
     */
    public static void checkCallAuthorization(final boolean expression) {
        if (!expression) {
            securityException("Calling identity is not authorized");
        }
    }

    /**
     * Ensures the truth of an expression involving whether the calling identity is authorized to
     * call the calling method.
     *
     * @param expression a boolean expression
     * @param message the message of the security exception to be thrown
     * @throws SecurityException if {@code expression} is false
     */
    public static void checkCallAuthorization(final boolean expression, final String message) {
        if (!expression) {
            securityException(message);
        }
    }

    /**
     * Ensures the truth of an expression involving whether the calling identity is authorized to
     * call the calling method.
     *
     * @param expression a boolean expression
     * @param messageTemplate a printf-style message template to use if the check fails; will be
     *     converted to a string using {@link String#format(String, Object...)}
     * @param messageArgs arguments for {@code messageTemplate}
     * @throws SecurityException if {@code expression} is false
     */
    @FormatMethod
    public static void checkCallAuthorization(
            final boolean expression,
            final @FormatString @CompileTimeConstant String messageTemplate,
            final Object... messageArgs) {
        if (!expression) {
            securityException(messageTemplate, messageArgs);
        }
    }

    /**
     * Ensures the truth of an expression involving whether the calling user is authorized to call
     * the calling method.
     *
     * @param expression a boolean expression
     * @throws SecurityException if {@code expression} is false
     */
    public static void checkCallingUser(final boolean expression) {
        if (!expression) {
            securityException("Calling user is not authorized");
        }
    }

    /**
     * Check the requested flags, throwing if any requested flags are outside the allowed set.
     *
     * @return the validated requested flags.
     */
    public static int checkFlagsArgument(final int requestedFlags, final int allowedFlags) {
        if ((requestedFlags & allowedFlags) != requestedFlags) {
            checkFlagsArgumentThrow(requestedFlags, allowedFlags);
        }

        return requestedFlags;
    }

    /**
     * Ensures that that the argument numeric value is non-negative (greater than or equal to 0).
     *
     * @param value a numeric int value
     * @param errorMessage the exception message to use if the check fails
     * @return the validated numeric value
     * @throws IllegalArgumentException if {@code value} was negative
     */
    public static @IntRange(from = 0) int checkArgumentNonnegative(
            final int value, final String errorMessage) {
        if (value < 0) {
            illegalArgumentException(errorMessage);
        }

        return value;
    }

    /**
     * Ensures that that the argument numeric value is non-negative (greater than or equal to 0).
     *
     * @param value a numeric int value
     * @return the validated numeric value
     * @throws IllegalArgumentException if {@code value} was negative
     */
    public static @IntRange(from = 0) int checkArgumentNonnegative(final int value) {
        if (value < 0) {
            illegalArgumentException();
        }

        return value;
    }

    /**
     * Ensures that that the argument numeric value is non-negative (greater than or equal to 0).
     *
     * @param value a numeric long value
     * @return the validated numeric value
     * @throws IllegalArgumentException if {@code value} was negative
     */
    public static long checkArgumentNonnegative(final long value) {
        if (value < 0) {
            illegalArgumentException();
        }

        return value;
    }

    /**
     * Ensures that that the argument numeric value is non-negative (greater than or equal to 0).
     *
     * @param value a numeric long value
     * @param errorMessage the exception message to use if the check fails
     * @return the validated numeric value
     * @throws IllegalArgumentException if {@code value} was negative
     */
    public static long checkArgumentNonnegative(final long value, final String errorMessage) {
        if (value < 0) {
            illegalArgumentException(errorMessage);
        }

        return value;
    }

    /**
     * Ensures that that the argument numeric value is positive (greater than 0).
     *
     * @param value a numeric int value
     * @param errorMessage the exception message to use if the check fails
     * @return the validated numeric value
     * @throws IllegalArgumentException if {@code value} was not positive
     */
    public static int checkArgumentPositive(final int value, final String errorMessage) {
        if (value <= 0) {
            illegalArgumentException(errorMessage);
        }

        return value;
    }

    /**
     * Ensures that the argument floating point value is non-negative (greater than or equal to 0).
     *
     * @param value a floating point value
     * @param errorMessage the exteption message to use if the check fails
     * @return the validated numeric value
     * @throws IllegalArgumentException if {@code value} was negative
     */
    public static float checkArgumentNonNegative(final float value, final String errorMessage) {
        if (value < 0) {
            illegalArgumentException(errorMessage);
        }

        return value;
    }

    /**
     * Ensures that the argument floating point value is positive (greater than 0).
     *
     * @param value a floating point value
     * @param errorMessage the exteption message to use if the check fails
     * @return the validated numeric value
     * @throws IllegalArgumentException if {@code value} was not positive
     */
    public static float checkArgumentPositive(final float value, final String errorMessage) {
        if (value <= 0) {
            illegalArgumentException(errorMessage);
        }

        return value;
    }

    /**
     * Ensures that the argument floating point value is a finite number.
     *
     * <p>A finite number is defined to be both representable (that is, not NaN) and not infinite
     * (that is neither positive or negative infinity).
     *
     * @param value a floating point value
     * @param valueName the name of the argument to use if the check fails
     * @return the validated floating point value
     * @throws IllegalArgumentException if {@code value} was not finite
     */
    public static float checkArgumentFinite(final float value, final String valueName) {
        if (Float.isNaN(value)) {
            illegalArgumentExceptionForNaN(valueName);
        } else if (Float.isInfinite(value)) {
            illegalArgumentExceptionForInfinite(valueName);
        }

        return value;
    }

    /**
     * Ensures that the argument floating point value is within the inclusive range.
     *
     * <p>While this can be used to range check against +/- infinity, note that all NaN numbers will
     * always be out of range.
     *
     * @param value a floating point value
     * @param lower the lower endpoint of the inclusive range
     * @param upper the upper endpoint of the inclusive range
     * @param valueName the name of the argument to use if the check fails
     * @return the validated floating point value
     * @throws IllegalArgumentException if {@code value} was not within the range
     */
    public static float checkArgumentInRange(
            float value, float lower, float upper, String valueName) {
        if (Float.isNaN(value)) {
            illegalArgumentExceptionForNaN(valueName);
        } else if (value < lower) {
            illegalArgumentException(
                    "%s is out of range of [%f, %f] (too low)", valueName, lower, upper);
        } else if (value > upper) {
            illegalArgumentException(
                    "%s is out of range of [%f, %f] (too high)", valueName, lower, upper);
        }

        return value;
    }

    /**
     * Ensures that the argument floating point value is within the inclusive range.
     *
     * <p>While this can be used to range check against +/- infinity, note that all NaN numbers will
     * always be out of range.
     *
     * @param value a floating point value
     * @param lower the lower endpoint of the inclusive range
     * @param upper the upper endpoint of the inclusive range
     * @param valueName the name of the argument to use if the check fails
     * @return the validated floating point value
     * @throws IllegalArgumentException if {@code value} was not within the range
     */
    public static double checkArgumentInRange(
            double value, double lower, double upper, String valueName) {
        if (Double.isNaN(value)) {
            illegalArgumentExceptionForNaN(valueName);
        } else if (value < lower) {
            illegalArgumentException(
                    "%s is out of range of [%f, %f] (too low)", valueName, lower, upper);
        } else if (value > upper) {
            illegalArgumentException(
                    "%s is out of range of [%f, %f] (too high)", valueName, lower, upper);
        }

        return value;
    }

    /**
     * Ensures that the argument int value is within the inclusive range.
     *
     * @param value a int value
     * @param lower the lower endpoint of the inclusive range
     * @param upper the upper endpoint of the inclusive range
     * @param valueName the name of the argument to use if the check fails
     * @return the validated int value
     * @throws IllegalArgumentException if {@code value} was not within the range
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public static int checkArgumentInRange(int value, int lower, int upper, String valueName) {
        if (value < lower) {
            illegalArgumentException(
                    "%s is out of range of [%d, %d] (too low)", valueName, lower, upper);
        } else if (value > upper) {
            illegalArgumentException(
                    "%s is out of range of [%d, %d] (too high)", valueName, lower, upper);
        }

        return value;
    }

    /**
     * Ensures that the argument long value is within the inclusive range.
     *
     * @param value a long value
     * @param lower the lower endpoint of the inclusive range
     * @param upper the upper endpoint of the inclusive range
     * @param valueName the name of the argument to use if the check fails
     * @return the validated long value
     * @throws IllegalArgumentException if {@code value} was not within the range
     */
    public static long checkArgumentInRange(long value, long lower, long upper, String valueName) {
        if (value < lower) {
            illegalArgumentException(
                    "%s is out of range of [%d, %d] (too low)", valueName, lower, upper);
        } else if (value > upper) {
            illegalArgumentException(
                    "%s is out of range of [%d, %d] (too high)", valueName, lower, upper);
        }

        return value;
    }

    /**
     * Ensures that the array is not {@code null}, and none of its elements are {@code null}.
     *
     * @param value an array of boxed objects
     * @param valueName the name of the argument to use if the check fails
     * @return the validated array
     * @throws NullPointerException if the {@code value} or any of its elements were {@code null}
     */
    public static <T> T[] checkArrayElementsNotNull(final T[] value, final String valueName) {
        if (value == null) {
            nullPointerException(valueName);
        }

        for (int i = 0; i < value.length; ++i) {
            if (value[i] == null) {
                nullPointerException("%s[%d] must not be null", valueName, i);
            }
        }

        return value;
    }

    /**
     * Ensures that the {@link Collection} is not {@code null}, and none of its elements are {@code
     * null}.
     *
     * @param value a {@link Collection} of boxed objects
     * @param valueName the name of the argument to use if the check fails
     * @return the validated {@link Collection}
     * @throws NullPointerException if the {@code value} or any of its elements were {@code null}
     */
    public static @NonNull <C extends Collection<T>, T> C checkCollectionElementsNotNull(
            final C value, final String valueName) {
        if (value == null) {
            nullPointerException(valueName);
        }

        long ctr = 0;
        for (T elem : value) {
            if (elem == null) {
                nullPointerException("%s[%d] must not be null", valueName, ctr);
            }
            ++ctr;
        }

        return value;
    }

    /**
     * Ensures that the {@link Collection} is not {@code null}, and contains at least one element.
     *
     * @param value a {@link Collection} of boxed elements.
     * @param valueName the name of the argument to use if the check fails.
     * @return the validated {@link Collection}
     * @throws NullPointerException if the {@code value} was {@code null}
     * @throws IllegalArgumentException if the {@code value} was empty, with a message
     *     in the format of "name must not be empty"
     */
    public static <T> Collection<T> checkCollectionNotEmptyWithName(
            final Collection<T> value, final String valueName) {
        if (value == null) {
            nullPointerException(valueName);
        }
        if (value.isEmpty()) {
            illegalArgumentExceptionForEmpty(valueName);
        }
        return value;
    }

    /**
     * Ensures that the {@link Collection} is not {@code null}, and contains at least one element.
     *
     * @param value a {@link Collection} of boxed elements.
     * @param valueName the name of the argument to use if the check fails.
     * @return the validated {@link Collection}
     * @throws NullPointerException if the {@code value} was {@code null}
     * @throws IllegalArgumentException if the {@code value} was empty, with a message
     *     in the format of "name must not be empty"
     * @deprecated Use {@link #checkCollectionNotEmptyWithName(Collection, String)} instead.
     */
    @Deprecated
    @InlineMe(
            replacement = "Preconditions.checkCollectionNotEmptyWithName(value, valueName)",
            imports = "com.android.internal.util.Preconditions")
    public static <T> Collection<T> checkCollectionNotEmpty(
            final Collection<T> value, final String valueName) {
        return checkCollectionNotEmptyWithName(value, valueName);
    }

    /**
     * Ensures that the given byte array is not {@code null}, and contains at least one element.
     *
     * @param value an array of elements.
     * @param valueName the name of the argument to use if the check fails.
     * @return the validated array
     * @throws NullPointerException if the {@code value} was {@code null}
     * @throws IllegalArgumentException if the {@code value} was empty
     */
    @NonNull
    public static byte[] checkByteArrayNotEmpty(final byte[] value, final String valueName) {
        if (value == null) {
            nullPointerException(valueName);
        }
        if (value.length == 0) {
            illegalArgumentExceptionForEmpty(valueName);
        }
        return value;
    }

    /**
     * Ensures that argument {@code value} is one of {@code supportedValues}.
     *
     * @param supportedValues an array of string values
     * @param value a string value
     * @return the validated value
     * @throws NullPointerException if either {@code value} or {@code supportedValues} is null
     * @throws IllegalArgumentException if the {@code value} is not in {@code supportedValues}
     */
    @NonNull
    public static String checkArgumentIsSupported(
            final String[] supportedValues, final String value) {
        checkNotNull(value);
        checkNotNull(supportedValues);

        if (!contains(supportedValues, value)) {
            illegalArgumentExceptionForUnsupportedValue(value, supportedValues);
        }
        return value;
    }

    private static boolean contains(String[] values, String value) {
        if (values == null) {
            return false;
        }
        for (int i = 0; i < values.length; ++i) {
            if (Objects.equals(value, values[i])) {
                return true;
            }
        }
        return false;
    }

    /**
     * Ensures that all elements in the argument floating point array are within the inclusive range
     *
     * <p>While this can be used to range check against +/- infinity, note that all NaN numbers will
     * always be out of range.
     *
     * @param value a floating point array of values
     * @param lower the lower endpoint of the inclusive range
     * @param upper the upper endpoint of the inclusive range
     * @param valueName the name of the argument to use if the check fails
     * @return the validated floating point value
     * @throws IllegalArgumentException if any of the elements in {@code value} were out of range
     * @throws NullPointerException if the {@code value} was {@code null}
     */
    public static float[] checkArrayElementsInRange(
            float[] value, float lower, float upper, String valueName) {
        if (value == null) {
            nullPointerException(valueName);
        }

        for (int i = 0; i < value.length; ++i) {
            float v = value[i];

            if (Float.isNaN(v)) {
                illegalArgumentExceptionForNaN(valueName, i);
            } else if (v < lower) {
                illegalArgumentException(
                        "%s[%d]: %f is out of range of [%f, %f] (too low)",
                        valueName, i, v, lower, upper);
            } else if (v > upper) {
                illegalArgumentException(
                        "%s[%d]: %f is out of range of [%f, %f] (too high)",
                        valueName, i, v, lower, upper);
            }
        }

        return value;
    }

    /**
     * Ensures that all elements in the argument integer array are within the inclusive range
     *
     * @param value an integer array of values
     * @param lower the lower endpoint of the inclusive range
     * @param upper the upper endpoint of the inclusive range
     * @param valueName the name of the argument to use if the check fails
     * @return the validated integer array
     * @throws IllegalArgumentException if any of the elements in {@code value} were out of range
     * @throws NullPointerException if the {@code value} was {@code null}
     */
    public static int[] checkArrayElementsInRange(
            int[] value, int lower, int upper, String valueName) {
        if (value == null) {
            nullPointerException(valueName);
        }

        for (int i = 0; i < value.length; ++i) {
            int v = value[i];

            if (v < lower) {
                illegalArgumentException(
                        "%s[%d]: %d is out of range of [%d, %d] (too low)",
                        valueName, v, i, lower, upper);
            } else if (v > upper) {
                illegalArgumentException(
                        "%s[%d]: %d is out of range of [%d, %d] (too high)",
                        valueName, v, i, lower, upper);
            }
        }

        return value;
    }

    /**
     * Throws an exception that guides developers to configure a {@code RavenwoodRule} when the
     * given argument is {@code null}.
     */
    public static <T> @NonNull T requireNonNullViaRavenwoodRule(@Nullable T t) {
        if (t == null) {
            illegalStateException(
                    "This operation requires that a RavenwoodRule be "
                            + "configured to accurately define the expected test environment");
        }
        return t;
    }

    // Intentionally outlined code blocks that always throw.
    //
    // This keeps the compiler from trying to inline them to the call sites, thereby reducing code
    // size. This makes the runtime slower when actually throwing, but that's fine because this
    // should be the cold path anyway.
    //
    // Additionally, we make an effort to create specialized methods for common use cases, instead
    // of reusing methods that take a format string and varargs. Varargs require an allocation of
    // an array at the call site, which defeats some of the outlining benefits.

    private static void illegalArgumentException() {
        throw new IllegalArgumentException();
    }

    private static void illegalArgumentExceptionForEmpty(String valueName) {
        throw new IllegalArgumentException(valueName + " must not be empty");
    }

    private static void illegalArgumentExceptionForNaN(String valueName) {
        throw new IllegalArgumentException(valueName + " must not be NaN");
    }

    private static void illegalArgumentExceptionForNaN(String valueName, int index) {
        throw new IllegalArgumentException(valueName + "[" + index + "] must not be NaN");
    }

    private static void illegalArgumentExceptionForInfinite(String valueName) {
        throw new IllegalArgumentException(valueName + " must not be infinite");
    }

    private static void illegalArgumentExceptionForUnsupportedValue(
            String value, String[] supportedValues) {
        throw new IllegalArgumentException(
                value + "is not supported " + Arrays.toString(supportedValues));
    }

    private static void illegalArgumentException(String message) {
        throw new IllegalArgumentException(message);
    }

    private static void illegalArgumentExceptionObj(Object message) {
        throw new IllegalArgumentException(String.valueOf(message));
    }

    @FormatMethod
    private static void illegalArgumentException(
            final @FormatString @CompileTimeConstant String messageTemplate,
            final Object... messageArgs) {
        throw new IllegalArgumentException(String.format(messageTemplate, messageArgs));
    }

    private static void checkFlagsArgumentThrow(final int requestedFlags, final int allowedFlags) {
        throw new IllegalArgumentException(
                "Requested flags 0x"
                        + Integer.toHexString(requestedFlags)
                        + ", but only 0x"
                        + Integer.toHexString(allowedFlags)
                        + " are allowed");
    }

    private static void illegalStateException(String message) {
        throw new IllegalStateException(message);
    }

    @FormatMethod
    private static void illegalStateException(
            final @NonNull @FormatString @CompileTimeConstant String messageTemplate,
            final Object... messageArgs) {
        throw new IllegalStateException(String.format(messageTemplate, messageArgs));
    }

    private static void nullPointerException() {
        throw new NullPointerException();
    }

    private static void nullPointerException(String variableName) {
        throw new NullPointerException(variableName + " must not be null");
    }

    private static void nullPointerExceptionObj(Object message) {
        throw new NullPointerException(String.valueOf(message));
    }

    @FormatMethod
    private static void nullPointerException(
            final @NonNull @FormatString @CompileTimeConstant String messageTemplate,
            final Object... messageArgs) {
        throw new NullPointerException(String.format(messageTemplate, messageArgs));
    }

    private static void securityException(String message) {
        throw new SecurityException(message);
    }

    @FormatMethod
    private static void securityException(
            final @NonNull @FormatString @CompileTimeConstant String messageTemplate,
            final Object... messageArgs) {
        throw new SecurityException(String.format(messageTemplate, messageArgs));
    }
}
