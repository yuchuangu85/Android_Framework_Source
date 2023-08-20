/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.localepicker;

import android.icu.text.ListFormatter;
import android.icu.util.ULocale;
import android.os.LocaleList;
import android.text.TextUtils;

import androidx.annotation.IntRange;

import java.text.Collator;
import java.util.Comparator;
import java.util.Locale;

/**
 * This class implements some handy methods to process with locales.
 */
public class LocaleHelper {

    /**
     * Sentence-case (first character uppercased).
     *
     * <p>There is no good API available for this, not even in ICU.
     * We can revisit this if we get some ICU support later.</p>
     *
     * <p>There are currently several tickets requesting this feature:</p>
     * <ul>
     * <li>ICU needs to provide an easy way to titlecase only one first letter
     *   http://bugs.icu-project.org/trac/ticket/11729</li>
     * <li>Add "initial case"
     *    http://bugs.icu-project.org/trac/ticket/8394</li>
     * <li>Add code for initialCase, toTitlecase don't modify after Lt,
     *   avoid 49Ers, low-level language-specific casing
     *   http://bugs.icu-project.org/trac/ticket/10410</li>
     * <li>BreakIterator.getFirstInstance: Often you need to titlecase just the first
     *   word, and leave the rest of the string alone.  (closed as duplicate)
     *   http://bugs.icu-project.org/trac/ticket/8946</li>
     * </ul>
     *
     * <p>A (clunky) option with the current ICU API is:</p>
     * {{
     *   BreakIterator breakIterator = BreakIterator.getSentenceInstance(locale);
     *   String result = UCharacter.toTitleCase(locale,
     *       source, breakIterator, UCharacter.TITLECASE_NO_LOWERCASE);
     * }}
     *
     * <p>That also means creating a BreakIterator for each locale. Expensive...</p>
     *
     * @param str the string to sentence-case.
     * @param locale the locale used for the case conversion.
     * @return the string converted to sentence-case.
     */
    static String toSentenceCase(String str, Locale locale) {
        if (str.isEmpty()) {
            return str;
        }
        final int firstCodePointLen = str.offsetByCodePoints(0, 1);
        return str.substring(0, firstCodePointLen).toUpperCase(locale)
                + str.substring(firstCodePointLen);
    }

    /**
     * Normalizes a string for locale name search. Does case conversion for now,
     * but might do more in the future.
     *
     * <p>Warning: it is only intended to be used in searches by the locale picker.
     * Don't use it for other things, it is very limited.</p>
     *
     * @param str the string to normalize
     * @param locale the locale that might be used for certain operations (i.e. case conversion)
     * @return the string normalized for search
     */
    static String normalizeForSearch(String str, Locale locale) {
        // TODO: tbd if it needs to be smarter (real normalization, remove accents, etc.)
        // If needed we might use case folding and ICU/CLDR's collation-based loose searching.
        // TODO: decide what should the locale be, the default locale, or the locale of the string.
        // Uppercase is better than lowercase because of things like sharp S, Greek sigma, ...
        return str != null ? str.toUpperCase() : null;
    }

    // For some locales we want to use a "dialect" form, for instance
    // "Dari" instead of "Persian (Afghanistan)", or "Moldavian" instead of "Romanian (Moldova)"
    private static boolean shouldUseDialectName(Locale locale) {
        final String lang = locale.getLanguage();
        return "fa".equals(lang) // Persian
                || "ro".equals(lang) // Romanian
                || "zh".equals(lang); // Chinese
    }

    /**
     * Returns the locale localized for display in the provided locale.
     *
     * @param locale the locale whose name is to be displayed.
     * @param displayLocale the locale in which to display the name.
     * @param sentenceCase true if the result should be sentence-cased
     * @return the localized name of the locale.
     */
    public static String getDisplayName(Locale locale, Locale displayLocale, boolean sentenceCase) {
        final ULocale displayULocale = ULocale.forLocale(displayLocale);
        String result = shouldUseDialectName(locale)
                ? ULocale.getDisplayNameWithDialect(locale.toLanguageTag(), displayULocale)
                : ULocale.getDisplayName(locale.toLanguageTag(), displayULocale);
        return sentenceCase ? toSentenceCase(result, displayLocale) : result;
    }

    /**
     * Returns the locale localized for display in the default locale.
     *
     * @param locale the locale whose name is to be displayed.
     * @param sentenceCase true if the result should be sentence-cased
     * @return the localized name of the locale.
     */
    public static String getDisplayName(Locale locale, boolean sentenceCase) {
        return getDisplayName(locale, Locale.getDefault(), sentenceCase);
    }

    /**
     * Returns a locale's country localized for display in the provided locale.
     *
     * @param locale the locale whose country will be displayed.
     * @param displayLocale the locale in which to display the name.
     * @return the localized country name.
     */
    public static String getDisplayCountry(Locale locale, Locale displayLocale) {
        final String languageTag = locale.toLanguageTag();
        final ULocale uDisplayLocale = ULocale.forLocale(displayLocale);
        final String country = ULocale.getDisplayCountry(languageTag, uDisplayLocale);
        final String numberingSystem = locale.getUnicodeLocaleType("nu");
        if (numberingSystem != null) {
            return String.format("%s (%s)", country,
                    ULocale.getDisplayKeywordValue(languageTag, "numbers", uDisplayLocale));
        } else {
            return country;
        }
    }

    /**
     * Returns a locale's country localized for display in the default locale.
     *
     * @param locale the locale whose country will be displayed.
     * @return the localized country name.
     */
    public static String getDisplayCountry(Locale locale) {
        return ULocale.getDisplayCountry(locale.toLanguageTag(), ULocale.getDefault());
    }

    /**
     * Adds the likely subtags for a provided locale ID.
     *
     * @param locale the locale to maximize.
     * @return the maximized Locale instance.
     */
    public static Locale addLikelySubtags(Locale locale) {
        return ULocale.addLikelySubtags(ULocale.forLocale(locale)).toLocale();
    }

    /**
     * Locale-sensitive comparison for LocaleInfo.
     *
     * <p>It uses the label, leaving the decision on what to put there to the LocaleInfo.
     * For instance fr-CA can be shown as "français" as a generic label in the language selection,
     * or "français (Canada)" if it is a suggestion, or "Canada" in the country selection.</p>
     *
     * <p>Gives priority to suggested locales (to sort them at the top).</p>
     */
    public static final class LocaleInfoComparator implements Comparator<LocaleStore.LocaleInfo> {
        private final Collator mCollator;
        private final boolean mCountryMode;
        private static final String PREFIX_ARABIC = "\u0627\u0644"; // ALEF-LAM, ال

        /**
         * Constructor.
         *
         * @param sortLocale the locale to be used for sorting.
         */
        public LocaleInfoComparator(Locale sortLocale, boolean countryMode) {
            mCollator = Collator.getInstance(sortLocale);
            mCountryMode = countryMode;
        }

        /*
         * The Arabic collation should ignore Alef-Lam at the beginning (b/26277596)
         *
         * We look at the label's locale, not the current system locale.
         * This is because the name of the Arabic language itself is in Arabic,
         * and starts with Alef-Lam, no matter what the system locale is.
         */
        private String removePrefixForCompare(Locale locale, String str) {
            if ("ar".equals(locale.getLanguage()) && str.startsWith(PREFIX_ARABIC)) {
                return str.substring(PREFIX_ARABIC.length());
            }
            return str;
        }

        /**
         * Compares its two arguments for order.
         *
         * @param lhs   the first object to be compared
         * @param rhs   the second object to be compared
         * @return  a negative integer, zero, or a positive integer as the first
         *          argument is less than, equal to, or greater than the second.
         */
        @Override
        public int compare(LocaleStore.LocaleInfo lhs, LocaleStore.LocaleInfo rhs) {
            // We don't care about the various suggestion types, just "suggested" (!= 0)
            // and "all others" (== 0)
            if (lhs.isSuggested() == rhs.isSuggested()) {
                // They are in the same "bucket" (suggested / others), so we compare the text
                return mCollator.compare(
                        removePrefixForCompare(lhs.getLocale(), lhs.getLabel(mCountryMode)),
                        removePrefixForCompare(rhs.getLocale(), rhs.getLabel(mCountryMode)));
            } else {
                // One locale is suggested and one is not, so we put them in different "buckets"
                return lhs.isSuggested() ? -1 : 1;
            }
        }
    }
}
