/* GENERATED SOURCE. DO NOT MODIFY. */
// © 2022 and later: Unicode, Inc. and others.
// License & terms of use: https://www.unicode.org/copyright.html

package android.icu.message2;

import java.util.Locale;
import java.util.Map;

/**
 * The interface that must be implemented for each formatting function name
 * that can be used from {@link MessageFormatter}.
 *
 * <p>We use it to create and cache various functions with various options.</p>
 *
 * @deprecated This API is for technology preview only.
 * @hide Only a subset of ICU is exposed in Android
 * @hide draft / provisional / internal are hidden on Android
 */
@Deprecated
public interface FunctionFactory {
    /**
     * The method that is called to create a function.
     *
     * @param locale the locale to use for formatting / selection.
     * @param fixedOptions the options to use for formatting / selection. The keys and values are function dependent.
     * @return the function.
     * @throws IllegalArgumentException in case there is a problem with the arguments.
     *
     * @deprecated This API is for technology preview only.
     * @hide draft / provisional / internal are hidden on Android
     */
    @Deprecated
    Function create(Locale locale, Map<String, Object> fixedOptions);
}
