/* GENERATED SOURCE. DO NOT MODIFY. */
// © 2025 and later: Unicode, Inc. and others.
// License & terms of use: https://www.unicode.org/copyright.html

package android.icu.message2;

import java.util.Locale;

import android.icu.util.ULocale;

/**
 * Encodes info about the direction of the message.
 *
 * <p>It is used to implement the @code u:dir} functionality.</p>
 *
 * @hide Only a subset of ICU is exposed in Android
 * @hide draft / provisional / internal are hidden on Android
 */
public enum Directionality {
    /**
     * Not initialized or unknown.
     *
     * <p>No special processing will be used.
     *
     * @hide draft / provisional / internal are hidden on Android
     */
    UNKNOWN,
    /**
     * Left-to-right directionality.
     *
     * @hide draft / provisional / internal are hidden on Android
     */
    LTR,
    /**
     * Right-to-left directionality.
     *
     * @hide draft / provisional / internal are hidden on Android
     */
    RTL,
    /**
     * Directionality determined from <i>expression</i> contents.
     *
     * @hide draft / provisional / internal are hidden on Android
     */
    AUTO,
    /**
     * Directionality inherited from the <i>message</i> or from the <i>resolved value</i>
     * of the <i>operand</i> without requiring isolation of the <i>expression</i> value.
     *
     * @hide draft / provisional / internal are hidden on Android
     */
    INHERIT;

    /**
     * Determines the directionality appropriate for a given locale.
     *
     * @param locale the locale to determine the directionality from.
     * @return the appropriate directionality for the locale given.
     *
     * @hide draft / provisional / internal are hidden on Android
     */
    public static Directionality of(Locale locale) {
        if (locale == null) {
            return Directionality.INHERIT;
        }
        return ULocale.forLocale(locale).isRightToLeft() ? Directionality.RTL : Directionality.LTR;
    }
}
