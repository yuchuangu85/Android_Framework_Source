/*
 * Copyright (C) 2014 The Android Open Source Project
 * Copyright (c) 1996, 2017, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

/*
 * (C) Copyright Taligent, Inc. 1996, 1997 - All Rights Reserved
 * (C) Copyright IBM Corp. 1996 - 1998 - All Rights Reserved
 *
 *   The original version of this source code and documentation is copyrighted
 * and owned by Taligent, Inc., a wholly-owned subsidiary of IBM. These
 * materials are provided under terms of a License Agreement between Taligent
 * and Sun. This technology is protected by multiple US and International
 * patents. This notice and attribution to Taligent may not be removed.
 *   Taligent is a registered trademark of Taligent, Inc.
 *
 */

package java.text;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamField;
import java.io.Serializable;
import java.util.Currency;
import java.util.Locale;
import libcore.icu.DecimalFormatData;
import libcore.icu.ICU;
import libcore.icu.LocaleData;

// Android-removed: Remove javadoc related to "rg" Locale extension.
// The "rg" extension isn't supported until https://unicode-org.atlassian.net/browse/ICU-21831
// is resolved, because java.text.* stack relies on ICU on resource resolution.
/**
 * This class represents the set of symbols (such as the decimal separator,
 * the grouping separator, and so on) needed by <code>DecimalFormat</code>
 * to format numbers. <code>DecimalFormat</code> creates for itself an instance of
 * <code>DecimalFormatSymbols</code> from its locale data.  If you need to change any
 * of these symbols, you can get the <code>DecimalFormatSymbols</code> object from
 * your <code>DecimalFormat</code> and modify it.
 *
 * @see          java.util.Locale
 * @see          DecimalFormat
 * @author       Mark Davis
 * @author       Alan Liu
 * @since 1.1
 */

public class DecimalFormatSymbols implements Cloneable, Serializable {

    // Android-changed: Removed reference to DecimalFormatSymbolsProvider, suggested getInstance().
    /**
     * Create a DecimalFormatSymbols object for the default
     * {@link java.util.Locale.Category#FORMAT FORMAT} locale.
     * It is recommended that the {@link #getInstance(Locale) getInstance} method is used
     * instead.
     * <p>This is equivalent to calling
     * {@link #DecimalFormatSymbols(Locale)
     *     DecimalFormatSymbols(Locale.getDefault(Locale.Category.FORMAT))}.
     * @see java.util.Locale#getDefault(java.util.Locale.Category)
     * @see java.util.Locale.Category#FORMAT
     */
    public DecimalFormatSymbols() {
        initialize( Locale.getDefault(Locale.Category.FORMAT) );
    }

    // Android-changed: Removed reference to DecimalFormatSymbolsProvider, suggested getInstance().
    /**
     * Create a DecimalFormatSymbols object for the given locale.
     * It is recommended that the {@link #getInstance(Locale) getInstance} method is used
     * instead.
     * If the specified locale contains the {@link java.util.Locale#UNICODE_LOCALE_EXTENSION}
     * for the numbering system, the instance is initialized with the specified numbering
     * system if the JRE implementation supports it. For example,
     * <pre>
     * NumberFormat.getNumberInstance(Locale.forLanguageTag("th-TH-u-nu-thai"))
     * </pre>
     * This may return a {@code NumberFormat} instance with the Thai numbering system,
     * instead of the Latin numbering system.
     *
     * @param locale the desired locale
     * @exception NullPointerException if <code>locale</code> is null
     */
    public DecimalFormatSymbols( Locale locale ) {
        initialize( locale );
    }

    // Android-changed: Removed reference to DecimalFormatSymbolsProvider.
    /**
     * Returns an array of all locales for which the
     * <code>getInstance</code> methods of this class can return
     * localized instances.
     *
     * @return an array of locales for which localized
     *         <code>DecimalFormatSymbols</code> instances are available.
     * @since 1.6
     */
    public static Locale[] getAvailableLocales() {
        // Android-changed: Removed used of DecimalFormatSymbolsProvider. Switched to use ICU.
        return ICU.getAvailableLocales();
    }

    // Android-changed: Removed reference to DecimalFormatSymbolsProvider.
    /**
     * Gets the <code>DecimalFormatSymbols</code> instance for the default
     * locale.
     * <p>This is equivalent to calling
     * {@link #getInstance(Locale)
     *     getInstance(Locale.getDefault(Locale.Category.FORMAT))}.
     * @see java.util.Locale#getDefault(java.util.Locale.Category)
     * @see java.util.Locale.Category#FORMAT
     * @return a <code>DecimalFormatSymbols</code> instance.
     * @since 1.6
     */
    public static final DecimalFormatSymbols getInstance() {
        return getInstance(Locale.getDefault(Locale.Category.FORMAT));
    }

    // Android-changed: Removed reference to DecimalFormatSymbolsProvider.
    /**
     * Gets the <code>DecimalFormatSymbols</code> instance for the specified
     * locale.
     * If the specified locale contains the {@link java.util.Locale#UNICODE_LOCALE_EXTENSION}
     * for the numbering system, the instance is initialized with the specified numbering
     * system if the JRE implementation supports it. For example,
     * <pre>
     * NumberFormat.getNumberInstance(Locale.forLanguageTag("th-TH-u-nu-thai"))
     * </pre>
     * This may return a {@code NumberFormat} instance with the Thai numbering system,
     * instead of the Latin numbering system.
     *
     * @param locale the desired locale.
     * @return a <code>DecimalFormatSymbols</code> instance.
     * @exception NullPointerException if <code>locale</code> is null
     * @since 1.6
     */
    public static final DecimalFormatSymbols getInstance(Locale locale) {
        // Android-changed: Removed used of DecimalFormatSymbolsProvider.
        return new DecimalFormatSymbols(locale);
    }

    /**
     * Gets the character used for zero. Different for Arabic, etc.
     *
     * @return the character used for zero
     */
    public char getZeroDigit() {
        return zeroDigit;
    }

    /**
     * Sets the character used for zero. Different for Arabic, etc.
     *
     * @param zeroDigit the character used for zero
     */
    public void setZeroDigit(char zeroDigit) {
        this.zeroDigit = zeroDigit;
        // Android-added: reset cachedIcuDFS.
        cachedIcuDFS = null;
    }

    /**
     * Gets the character used for thousands separator. Different for French, etc.
     *
     * @return the grouping separator
     */
    public char getGroupingSeparator() {
        return groupingSeparator;
    }

    /**
     * Sets the character used for thousands separator. Different for French, etc.
     *
     * @param groupingSeparator the grouping separator
     */
    public void setGroupingSeparator(char groupingSeparator) {
        this.groupingSeparator = groupingSeparator;
        // Android-added: reset cachedIcuDFS.
        cachedIcuDFS = null;
    }

    /**
     * Gets the character used for decimal sign. Different for French, etc.
     *
     * @return the character used for decimal sign
     */
    public char getDecimalSeparator() {
        return decimalSeparator;
    }

    /**
     * Sets the character used for decimal sign. Different for French, etc.
     *
     * @param decimalSeparator the character used for decimal sign
     */
    public void setDecimalSeparator(char decimalSeparator) {
        this.decimalSeparator = decimalSeparator;
        // Android-added: reset cachedIcuDFS.
        cachedIcuDFS = null;
    }

    /**
     * Gets the character used for per mille sign. Different for Arabic, etc.
     *
     * @return the character used for per mille sign
     */
    public char getPerMill() {
        return perMill;
    }

    /**
     * Sets the character used for per mille sign. Different for Arabic, etc.
     *
     * @param perMill the character used for per mille sign
     */
    public void setPerMill(char perMill) {
        this.perMill = perMill;
        // Android-added: reset cachedIcuDFS.
        cachedIcuDFS = null;
    }

    /**
     * Gets the character used for percent sign. Different for Arabic, etc.
     *
     * @return the character used for percent sign
     */
    public char getPercent() {
        return percent;
    }

    // Android-added: getPercentString() for percent signs longer than one char.
    /**
     * Gets the string used for percent sign. Different for Arabic, etc.
     *
     * @hide
     */
    public String getPercentString() {
        return String.valueOf(percent);
    }

    /**
     * Sets the character used for percent sign. Different for Arabic, etc.
     *
     * @param percent the character used for percent sign
     */
    public void setPercent(char percent) {
        this.percent = percent;
        // Android-added: reset cachedIcuDFS.
        cachedIcuDFS = null;
    }

    /**
     * Gets the character used for a digit in a pattern.
     *
     * @return the character used for a digit in a pattern
     */
    public char getDigit() {
        return digit;
    }

    /**
     * Sets the character used for a digit in a pattern.
     *
     * @param digit the character used for a digit in a pattern
     */
    public void setDigit(char digit) {
        this.digit = digit;
        // Android-added: reset cachedIcuDFS.
        cachedIcuDFS = null;
    }

    /**
     * Gets the character used to separate positive and negative subpatterns
     * in a pattern.
     *
     * @return the pattern separator
     */
    public char getPatternSeparator() {
        return patternSeparator;
    }

    /**
     * Sets the character used to separate positive and negative subpatterns
     * in a pattern.
     *
     * @param patternSeparator the pattern separator
     */
    public void setPatternSeparator(char patternSeparator) {
        this.patternSeparator = patternSeparator;
        // Android-added: reset cachedIcuDFS.
        cachedIcuDFS = null;
    }

    /**
     * Gets the string used to represent infinity. Almost always left
     * unchanged.
     *
     * @return the string representing infinity
     */
    public String getInfinity() {
        return infinity;
    }

    /**
     * Sets the string used to represent infinity. Almost always left
     * unchanged.
     *
     * @param infinity the string representing infinity
     */
    public void setInfinity(String infinity) {
        this.infinity = infinity;
        // Android-added: reset cachedIcuDFS.
        cachedIcuDFS = null;
    }

    /**
     * Gets the string used to represent "not a number". Almost always left
     * unchanged.
     *
     * @return the string representing "not a number"
     */
    public String getNaN() {
        return NaN;
    }

    /**
     * Sets the string used to represent "not a number". Almost always left
     * unchanged.
     *
     * @param NaN the string representing "not a number"
     */
    public void setNaN(String NaN) {
        this.NaN = NaN;
        // Android-added: reset cachedIcuDFS.
        cachedIcuDFS = null;
    }

    /**
     * Gets the character used to represent minus sign. If no explicit
     * negative format is specified, one is formed by prefixing
     * minusSign to the positive format.
     *
     * @return the character representing minus sign
     */
    public char getMinusSign() {
        return minusSign;
    }


    // Android-added: getPercentString() for percent signs longer than one char.
    /**
     * Gets the string used to represent minus sign. If no explicit
     * negative format is specified, one is formed by prefixing
     * minusSign to the positive format.
     *
     * @hide
     */
    public String getMinusSignString() {
        return String.valueOf(minusSign);
    }

    /**
     * Sets the character used to represent minus sign. If no explicit
     * negative format is specified, one is formed by prefixing
     * minusSign to the positive format.
     *
     * @param minusSign the character representing minus sign
     */
    public void setMinusSign(char minusSign) {
        this.minusSign = minusSign;
        // Android-added: reset cachedIcuDFS.
        cachedIcuDFS = null;
    }

    /**
     * Returns the currency symbol for the currency of these
     * DecimalFormatSymbols in their locale.
     *
     * @return the currency symbol
     * @since 1.2
     */
    public String getCurrencySymbol()
    {
        initializeCurrency(locale);
        return currencySymbol;
    }

    /**
     * Sets the currency symbol for the currency of these
     * DecimalFormatSymbols in their locale.
     *
     * @param currency the currency symbol
     * @since 1.2
     */
    public void setCurrencySymbol(String currency)
    {
        initializeCurrency(locale);
        currencySymbol = currency;
        // Android-added: reset cachedIcuDFS.
        cachedIcuDFS = null;
    }

    /**
     * Returns the ISO 4217 currency code of the currency of these
     * DecimalFormatSymbols.
     *
     * @return the currency code
     * @since 1.2
     */
    public String getInternationalCurrencySymbol()
    {
        initializeCurrency(locale);
        return intlCurrencySymbol;
    }

    /**
     * Sets the ISO 4217 currency code of the currency of these
     * DecimalFormatSymbols.
     * If the currency code is valid (as defined by
     * {@link java.util.Currency#getInstance(java.lang.String) Currency.getInstance}),
     * this also sets the currency attribute to the corresponding Currency
     * instance and the currency symbol attribute to the currency's symbol
     * in the DecimalFormatSymbols' locale. If the currency code is not valid,
     * then the currency attribute is set to null and the currency symbol
     * attribute is not modified.
     *
     * @param currencyCode the currency code
     * @see #setCurrency
     * @see #setCurrencySymbol
     * @since 1.2
     */
    public void setInternationalCurrencySymbol(String currencyCode)
    {
        initializeCurrency(locale);
        intlCurrencySymbol = currencyCode;
        currency = null;
        if (currencyCode != null) {
            try {
                currency = Currency.getInstance(currencyCode);
                // Android-changed: get currencySymbol for locale.
                currencySymbol = currency.getSymbol(locale);
            } catch (IllegalArgumentException e) {
            }
        }
        // Android-added: reset cachedIcuDFS.
        cachedIcuDFS = null;
    }

    /**
     * Gets the currency of these DecimalFormatSymbols. May be null if the
     * currency symbol attribute was previously set to a value that's not
     * a valid ISO 4217 currency code.
     *
     * @return the currency used, or null
     * @since 1.4
     */
    public Currency getCurrency() {
        initializeCurrency(locale);
        return currency;
    }

    /**
     * Sets the currency of these DecimalFormatSymbols.
     * This also sets the currency symbol attribute to the currency's symbol
     * in the DecimalFormatSymbols' locale, and the international currency
     * symbol attribute to the currency's ISO 4217 currency code.
     *
     * @param currency the new currency to be used
     * @exception NullPointerException if <code>currency</code> is null
     * @since 1.4
     * @see #setCurrencySymbol
     * @see #setInternationalCurrencySymbol
     */
    public void setCurrency(Currency currency) {
        if (currency == null) {
            throw new NullPointerException();
        }
        initializeCurrency(locale);
        this.currency = currency;
        intlCurrencySymbol = currency.getCurrencyCode();
        currencySymbol = currency.getSymbol(locale);
        // Android-added: reset cachedIcuDFS.
        cachedIcuDFS = null;
    }


    /**
     * Returns the monetary decimal separator.
     *
     * @return the monetary decimal separator
     * @since 1.2
     */
    public char getMonetaryDecimalSeparator()
    {
        return monetarySeparator;
    }

    /**
     * Sets the monetary decimal separator.
     *
     * @param sep the monetary decimal separator
     * @since 1.2
     */
    public void setMonetaryDecimalSeparator(char sep)
    {
        monetarySeparator = sep;
        // Android-added: reset cachedIcuDFS.
        cachedIcuDFS = null;
    }

    //------------------------------------------------------------
    // BEGIN   Package Private methods ... to be made public later
    //------------------------------------------------------------

    /**
     * Returns the character used to separate the mantissa from the exponent.
     */
    char getExponentialSymbol()
    {
        return exponential;
    }

    /**
     * Returns the string used to separate the mantissa from the exponent.
     * Examples: "x10^" for 1.23x10^4, "E" for 1.23E4.
     *
     * @return the exponent separator string
     * @see #setExponentSeparator(java.lang.String)
     * @since 1.6
     */
    public String getExponentSeparator()
    {
        return exponentialSeparator;
    }

    /**
     * Sets the character used to separate the mantissa from the exponent.
     */
    void setExponentialSymbol(char exp)
    {
        exponential = exp;
        // Android-added: reset cachedIcuDFS.
        cachedIcuDFS = null;
    }

    /**
     * Sets the string used to separate the mantissa from the exponent.
     * Examples: "x10^" for 1.23x10^4, "E" for 1.23E4.
     *
     * @param exp the exponent separator string
     * @exception NullPointerException if <code>exp</code> is null
     * @see #getExponentSeparator()
     * @since 1.6
     */
    public void setExponentSeparator(String exp)
    {
        if (exp == null) {
            throw new NullPointerException();
        }
        exponentialSeparator = exp;
    }


    //------------------------------------------------------------
    // END     Package Private methods ... to be made public later
    //------------------------------------------------------------

    /**
     * Standard override.
     */
    @Override
    public Object clone() {
        try {
            return (DecimalFormatSymbols)super.clone();
            // other fields are bit-copied
        } catch (CloneNotSupportedException e) {
            throw new InternalError(e);
        }
    }

    /**
     * Override equals.
     */
    @Override
    public boolean equals(Object obj) {
        if (obj == null) return false;
        if (this == obj) return true;
        if (getClass() != obj.getClass()) return false;
        DecimalFormatSymbols other = (DecimalFormatSymbols) obj;
        return (zeroDigit == other.zeroDigit &&
        groupingSeparator == other.groupingSeparator &&
        decimalSeparator == other.decimalSeparator &&
        percent == other.percent &&
        perMill == other.perMill &&
        digit == other.digit &&
        minusSign == other.minusSign &&
        patternSeparator == other.patternSeparator &&
        infinity.equals(other.infinity) &&
        NaN.equals(other.NaN) &&
        getCurrencySymbol().equals(other.getCurrencySymbol()) && // possible currency init occurs here
        intlCurrencySymbol.equals(other.intlCurrencySymbol) &&
        currency == other.currency &&
        monetarySeparator == other.monetarySeparator &&
        exponentialSeparator.equals(other.exponentialSeparator) &&
        locale.equals(other.locale));
    }

    /**
     * Override hashCode.
     */
    @Override
    public int hashCode() {
            int result = zeroDigit;
            result = result * 37 + groupingSeparator;
            result = result * 37 + decimalSeparator;
            // BEGIN Android-added: more fields in hashcode calculation.
            result = result * 37 + percent;
            result = result * 37 + perMill;
            result = result * 37 + digit;
            result = result * 37 + minusSign;
            result = result * 37 + patternSeparator;
            result = result * 37 + infinity.hashCode();
            result = result * 37 + NaN.hashCode();
            result = result * 37 + monetarySeparator;
            result = result * 37 + exponentialSeparator.hashCode();
            result = result * 37 + locale.hashCode();
           // END Android-added: more fields in hashcode calculation.
            return result;
    }

    /**
     * Initializes the symbols from the FormatData resource bundle.
     */
    private void initialize( Locale locale ) {
        this.locale = locale;

        // BEGIN Android-changed: Removed use of DecimalFormatSymbolsProvider. Switched to ICU.
        /*
        // check for region override
        Locale override = locale.getUnicodeLocaleType("nu") == null ?
            CalendarDataUtility.findRegionOverride(locale) :
            locale;

        // get resource bundle data
        LocaleProviderAdapter adapter = LocaleProviderAdapter.getAdapter(DecimalFormatSymbolsProvider.class, override);
        // Avoid potential recursions
        if (!(adapter instanceof ResourceBundleBasedAdapter)) {
            adapter = LocaleProviderAdapter.getResourceBundleBased();
        }
        Object[] data = adapter.getLocaleResources(override).getDecimalFormatSymbolsData();
        String[] numberElements = (String[]) data[0];
        */
        if (locale == null) {
            throw new NullPointerException("locale");
        }
        locale = LocaleData.mapInvalidAndNullLocales(locale);
        DecimalFormatData decimalFormatData = DecimalFormatData.getInstance(locale);
        String[] values = new String[11];
        values[0] = String.valueOf(decimalFormatData.getDecimalSeparator());
        values[1] = String.valueOf(decimalFormatData.getGroupingSeparator());
        values[2] = String.valueOf(decimalFormatData.getPatternSeparator());
        values[3] = decimalFormatData.getPercent();
        values[4] = String.valueOf(decimalFormatData.getZeroDigit());
        values[5] = "#";
        values[6] = decimalFormatData.getMinusSign();
        values[7] = decimalFormatData.getExponentSeparator();
        values[8] = decimalFormatData.getPerMill();
        values[9] = decimalFormatData.getInfinity();
        values[10] = decimalFormatData.getNaN();
        String[] numberElements = values;
        // END Android-changed: Removed use of DecimalFormatSymbolsProvider. Switched to ICU.

        // Android-changed: Added maybeStripMarkers
        decimalSeparator = numberElements[0].charAt(0);
        groupingSeparator = numberElements[1].charAt(0);
        patternSeparator = numberElements[2].charAt(0);
        percent = maybeStripMarkers(numberElements[3], '%');
        zeroDigit = numberElements[4].charAt(0); //different for Arabic,etc.
        digit = numberElements[5].charAt(0);
        minusSign = maybeStripMarkers(numberElements[6], '-');
        exponential = numberElements[7].charAt(0);
        exponentialSeparator = numberElements[7]; //string representation new since 1.6
        perMill = maybeStripMarkers(numberElements[8], '\u2030');
        infinity  = numberElements[9];
        NaN = numberElements[10];

        // Android-removed: Removed use of DecimalFormatSymbolsProvider. Switched to ICU.
        // Upstream tries to re-use the strings from the cache, but Android doesn't have
        // LocaleProviderAdapter to cache the strings.
        // intlCurrencySymbol = (String) data[1];
        // currencySymbol = (String) data[2];

        // Currently the monetary decimal separator is the same as the
        // standard decimal separator for all locales that we support.
        // If that changes, add a new entry to NumberElements.
        monetarySeparator = decimalSeparator;
    }

    /**
     * Lazy initialization for currency related fields
     */
    private void initializeCurrency(Locale locale) {
        if (currencyInitialized) {
            return;
        }

        // Try to obtain the currency used in the locale's country.
        // Check for empty country string separately because it's a valid
        // country ID for Locale (and used for the C locale), but not a valid
        // ISO 3166 country code, and exceptions are expensive.
        if (!locale.getCountry().isEmpty()) {
            try {
                currency = Currency.getInstance(locale);
            } catch (IllegalArgumentException e) {
                // use default values below for compatibility
            }
        }

        if (currency != null) {
            // BEGIN Android-changed: Removed use of DecimalFormatSymbolsProvider. Switched to ICU.
            // Android doesn't have DecimalFormatSymbolsProvider to cache the values.
            // Thus, simplify the code not loading from the cache.
            /*
            // get resource bundle data
            LocaleProviderAdapter adapter =
                LocaleProviderAdapter.getAdapter(DecimalFormatSymbolsProvider.class, locale);
            // Avoid potential recursions
            if (!(adapter instanceof ResourceBundleBasedAdapter)) {
                adapter = LocaleProviderAdapter.getResourceBundleBased();
            }
            Object[] data = adapter.getLocaleResources(locale).getDecimalFormatSymbolsData();
            intlCurrencySymbol = currency.getCurrencyCode();
            if (data[1] != null && data[1] == intlCurrencySymbol) {
                currencySymbol = (String) data[2];
            } else {
                currencySymbol = currency.getSymbol(locale);
                data[1] = intlCurrencySymbol;
                data[2] = currencySymbol;
            }
            */
            intlCurrencySymbol = currency.getCurrencyCode();
            currencySymbol = currency.getSymbol(locale);
            // END Android-changed: Removed use of DecimalFormatSymbolsProvider. Switched to ICU.
        } else {
            // default values
            intlCurrencySymbol = "XXX";
            try {
                currency = Currency.getInstance(intlCurrencySymbol);
            } catch (IllegalArgumentException e) {
            }
            currencySymbol = "\u00A4";
        }

        currencyInitialized = true;
    }

    // Android-changed: maybeStripMarkers added in b/26207216, fixed in b/32465689.
    /**
     * Attempts to strip RTL, LTR and Arabic letter markers from {@code symbol}.
     * If the string contains a single non-marker character (and any number of marker characters),
     * then that character is returned, otherwise {@code fallback} is returned.
     *
     * @hide
     */
    // VisibleForTesting
    public static char maybeStripMarkers(String symbol, char fallback) {
        final int length = symbol.length();
        if (length >= 1) {
            boolean sawNonMarker = false;
            char nonMarker = 0;
            for (int i = 0; i < length; i++) {
                final char c = symbol.charAt(i);
                if (c == '\u200E' || c == '\u200F' || c == '\u061C') {
                    continue;
                }
                if (sawNonMarker) {
                    // More than one non-marker character.
                    return fallback;
                }
                sawNonMarker = true;
                nonMarker = c;
            }
            if (sawNonMarker) {
                return nonMarker;
            }
        }
        return fallback;
    }

    // BEGIN Android-added: getIcuDecimalFormatSymbols() and fromIcuInstance().
    /**
     * Convert an instance of this class to the ICU version so that it can be used with ICU4J.
     * @hide
     */
    protected android.icu.text.DecimalFormatSymbols getIcuDecimalFormatSymbols() {
        if (cachedIcuDFS != null) {
            return cachedIcuDFS;
        }

        initializeCurrency(this.locale);
        cachedIcuDFS = new android.icu.text.DecimalFormatSymbols(this.locale);
        // Do not localize plus sign. See "Special Pattern Characters" section in DecimalFormat.
        // http://b/67034519
        cachedIcuDFS.setPlusSign('+');
        cachedIcuDFS.setZeroDigit(zeroDigit);
        cachedIcuDFS.setDigit(digit);
        cachedIcuDFS.setDecimalSeparator(decimalSeparator);
        cachedIcuDFS.setGroupingSeparator(groupingSeparator);
        // {@link #setGroupingSeparator(char)} should set grouping separator for currency, but
        // ICU has a separate API setMonetaryGroupingSeparator. Need to call it explicitly here.
        // http://b/38021063
        cachedIcuDFS.setMonetaryGroupingSeparator(groupingSeparator);
        cachedIcuDFS.setPatternSeparator(patternSeparator);
        cachedIcuDFS.setPercent(percent);
        cachedIcuDFS.setPerMill(perMill);
        cachedIcuDFS.setMonetaryDecimalSeparator(monetarySeparator);
        cachedIcuDFS.setMinusSign(minusSign);
        cachedIcuDFS.setInfinity(infinity);
        cachedIcuDFS.setNaN(NaN);
        cachedIcuDFS.setExponentSeparator(exponentialSeparator);
        // j.t.DecimalFormatSymbols doesn't insert whitespace before/after currency by default.
        // Override ICU default value to retain historic Android behavior.
        // http://b/112127077
        cachedIcuDFS.setPatternForCurrencySpacing(
            android.icu.text.DecimalFormatSymbols.CURRENCY_SPC_INSERT,
            false /* beforeCurrency */, "");
        cachedIcuDFS.setPatternForCurrencySpacing(
            android.icu.text.DecimalFormatSymbols.CURRENCY_SPC_INSERT,
            true /* beforeCurrency */, "");

        try {
            cachedIcuDFS.setCurrency(
                    android.icu.util.Currency.getInstance(getCurrency().getCurrencyCode()));
        } catch (NullPointerException e) {
            currency = Currency.getInstance("XXX");
        }

        cachedIcuDFS.setCurrencySymbol(currencySymbol);
        cachedIcuDFS.setInternationalCurrencySymbol(intlCurrencySymbol);

        return cachedIcuDFS;
    }

    /**
     * Create an instance of DecimalFormatSymbols using the ICU equivalent of this class.
     * @hide
     */
    protected static DecimalFormatSymbols fromIcuInstance(
            android.icu.text.DecimalFormatSymbols dfs) {
        DecimalFormatSymbols result = new DecimalFormatSymbols(dfs.getLocale());
        result.setZeroDigit(dfs.getZeroDigit());
        result.setDigit(dfs.getDigit());
        result.setDecimalSeparator(dfs.getDecimalSeparator());
        result.setGroupingSeparator(dfs.getGroupingSeparator());
        result.setPatternSeparator(dfs.getPatternSeparator());
        result.setPercent(dfs.getPercent());
        result.setPerMill(dfs.getPerMill());
        result.setMonetaryDecimalSeparator(dfs.getMonetaryDecimalSeparator());
        result.setMinusSign(dfs.getMinusSign());
        result.setInfinity(dfs.getInfinity());
        result.setNaN(dfs.getNaN());
        result.setExponentSeparator(dfs.getExponentSeparator());

        try {
            if (dfs.getCurrency() != null) {
                result.setCurrency(Currency.getInstance(dfs.getCurrency().getCurrencyCode()));
            } else {
                result.setCurrency(Currency.getInstance("XXX"));
            }
        } catch (IllegalArgumentException e) {
            result.setCurrency(Currency.getInstance("XXX"));
        }

        result.setInternationalCurrencySymbol(dfs.getInternationalCurrencySymbol());
        result.setCurrencySymbol(dfs.getCurrencySymbol());
        return result;
    }
    // END Android-added: getIcuDecimalFormatSymbols() and fromIcuInstance().

    // BEGIN Android-added: Android specific serialization code.
    private static final ObjectStreamField[] serialPersistentFields = {
            new ObjectStreamField("currencySymbol", String.class),
            new ObjectStreamField("decimalSeparator", char.class),
            new ObjectStreamField("digit", char.class),
            new ObjectStreamField("exponential", char.class),
            new ObjectStreamField("exponentialSeparator", String.class),
            new ObjectStreamField("groupingSeparator", char.class),
            new ObjectStreamField("infinity", String.class),
            new ObjectStreamField("intlCurrencySymbol", String.class),
            new ObjectStreamField("minusSign", char.class),
            new ObjectStreamField("monetarySeparator", char.class),
            new ObjectStreamField("NaN", String.class),
            new ObjectStreamField("patternSeparator", char.class),
            new ObjectStreamField("percent", char.class),
            new ObjectStreamField("perMill", char.class),
            new ObjectStreamField("serialVersionOnStream", int.class),
            new ObjectStreamField("zeroDigit", char.class),
            new ObjectStreamField("locale", Locale.class),
            new ObjectStreamField("minusSignStr", String.class),
            new ObjectStreamField("percentStr", String.class),
    };

    private void writeObject(ObjectOutputStream stream) throws IOException {
        ObjectOutputStream.PutField fields = stream.putFields();
        fields.put("currencySymbol", currencySymbol);
        fields.put("decimalSeparator", getDecimalSeparator());
        fields.put("digit", getDigit());
        fields.put("exponential", exponentialSeparator.charAt(0));
        fields.put("exponentialSeparator", exponentialSeparator);
        fields.put("groupingSeparator", getGroupingSeparator());
        fields.put("infinity", infinity);
        fields.put("intlCurrencySymbol", intlCurrencySymbol);
        fields.put("monetarySeparator", getMonetaryDecimalSeparator());
        fields.put("NaN", NaN);
        fields.put("patternSeparator", getPatternSeparator());
        fields.put("perMill", getPerMill());
        fields.put("serialVersionOnStream", 3);
        fields.put("zeroDigit", getZeroDigit());
        fields.put("locale", locale);

        // Hardcode values here for backwards compatibility. These values will only be used
        // if we're de-serializing this object on an earlier version of android.
        fields.put("minusSign", minusSign);
        fields.put("percent", percent);

        fields.put("minusSignStr", getMinusSignString());
        fields.put("percentStr", getPercentString());
        stream.writeFields();
    }
    // END Android-added: Android specific serialization code.

    /**
     * Reads the default serializable fields, provides default values for objects
     * in older serial versions, and initializes non-serializable fields.
     * If <code>serialVersionOnStream</code>
     * is less than 1, initializes <code>monetarySeparator</code> to be
     * the same as <code>decimalSeparator</code> and <code>exponential</code>
     * to be 'E'.
     * If <code>serialVersionOnStream</code> is less than 2,
     * initializes <code>locale</code>to the root locale, and initializes
     * If <code>serialVersionOnStream</code> is less than 3, it initializes
     * <code>exponentialSeparator</code> using <code>exponential</code>.
     * Sets <code>serialVersionOnStream</code> back to the maximum allowed value so that
     * default serialization will work properly if this object is streamed out again.
     * Initializes the currency from the intlCurrencySymbol field.
     *
     * @since  1.1.6
     */
    private void readObject(ObjectInputStream stream)
            throws IOException, ClassNotFoundException {
        // BEGIN Android-changed: Android specific serialization code.
        ObjectInputStream.GetField fields = stream.readFields();
        final int serialVersionOnStream = fields.get("serialVersionOnStream", 0);
        currencySymbol = (String) fields.get("currencySymbol", "");
        setDecimalSeparator(fields.get("decimalSeparator", '.'));
        setDigit(fields.get("digit", '#'));
        setGroupingSeparator(fields.get("groupingSeparator", ','));
        infinity = (String) fields.get("infinity", "");
        intlCurrencySymbol = (String) fields.get("intlCurrencySymbol", "");
        NaN = (String) fields.get("NaN", "");
        setPatternSeparator(fields.get("patternSeparator", ';'));

        // Special handling for minusSign and percent. If we've serialized the string versions of
        // these fields, use them. If not, fall back to the single character versions. This can
        // only happen if we're de-serializing an object that was written by an older version of
        // android (something that's strongly discouraged anyway).
        final String minusSignStr = (String) fields.get("minusSignStr", null);
        if (minusSignStr != null) {
            minusSign = minusSignStr.charAt(0);
        } else {
            setMinusSign(fields.get("minusSign", '-'));
        }
        final String percentStr = (String) fields.get("percentStr", null);
        if (percentStr != null) {
            percent = percentStr.charAt(0);
        } else {
            setPercent(fields.get("percent", '%'));
        }

        setPerMill(fields.get("perMill", '\u2030'));
        setZeroDigit(fields.get("zeroDigit", '0'));
        locale = (Locale) fields.get("locale", null);
        if (serialVersionOnStream == 0) {
            setMonetaryDecimalSeparator(getDecimalSeparator());
        } else {
            setMonetaryDecimalSeparator(fields.get("monetarySeparator", '.'));
        }

        if (serialVersionOnStream == 0) {
            // Prior to Java 1.1.6, the exponent separator wasn't configurable.
            exponentialSeparator = "E";
        } else if (serialVersionOnStream < 3) {
            // In Javas 1.1.6 and 1.4, there was a character field "exponential".
            setExponentSeparator(String.valueOf(fields.get("exponential", 'E')));
        } else {
            // In Java 6, there's a new "exponentialSeparator" field.
            setExponentSeparator((String) fields.get("exponentialSeparator", "E"));
        }

        if (intlCurrencySymbol != null) {
            try {
                currency = Currency.getInstance(intlCurrencySymbol);
                currencyInitialized = true;
            } catch (IllegalArgumentException e) {
                currency = null;
            }
        }
        // END Android-changed: Android specific serialization code.
    }

    /**
     * Character used for zero.
     *
     * @serial
     * @see #getZeroDigit
     */
    private  char    zeroDigit;

    /**
     * Character used for thousands separator.
     *
     * @serial
     * @see #getGroupingSeparator
     */
    private  char    groupingSeparator;

    /**
     * Character used for decimal sign.
     *
     * @serial
     * @see #getDecimalSeparator
     */
    private  char    decimalSeparator;

    /**
     * Character used for per mille sign.
     *
     * @serial
     * @see #getPerMill
     */
    private  char    perMill;

    /**
     * Character used for percent sign.
     * @serial
     * @see #getPercent
     */
    private  char    percent;

    /**
     * Character used for a digit in a pattern.
     *
     * @serial
     * @see #getDigit
     */
    private  char    digit;

    /**
     * Character used to separate positive and negative subpatterns
     * in a pattern.
     *
     * @serial
     * @see #getPatternSeparator
     */
    private  char    patternSeparator;

    /**
     * String used to represent infinity.
     * @serial
     * @see #getInfinity
     */
    private  String  infinity;

    /**
     * String used to represent "not a number".
     * @serial
     * @see #getNaN
     */
    private  String  NaN;

    /**
     * Character used to represent minus sign.
     * @serial
     * @see #getMinusSign
     */
    private  char    minusSign;

    /**
     * String denoting the local currency, e.g. "$".
     * @serial
     * @see #getCurrencySymbol
     */
    private  String  currencySymbol;

    /**
     * ISO 4217 currency code denoting the local currency, e.g. "USD".
     * @serial
     * @see #getInternationalCurrencySymbol
     */
    private  String  intlCurrencySymbol;

    /**
     * The decimal separator used when formatting currency values.
     * @serial
     * @since  1.1.6
     * @see #getMonetaryDecimalSeparator
     */
    private  char    monetarySeparator; // Field new in JDK 1.1.6

    /**
     * The character used to distinguish the exponent in a number formatted
     * in exponential notation, e.g. 'E' for a number such as "1.23E45".
     * <p>
     * Note that the public API provides no way to set this field,
     * even though it is supported by the implementation and the stream format.
     * The intent is that this will be added to the API in the future.
     *
     * @serial
     * @since  1.1.6
     */
    private  char    exponential;       // Field new in JDK 1.1.6

    /**
     * The string used to separate the mantissa from the exponent.
     * Examples: "x10^" for 1.23x10^4, "E" for 1.23E4.
     * <p>
     * If both <code>exponential</code> and <code>exponentialSeparator</code>
     * exist, this <code>exponentialSeparator</code> has the precedence.
     *
     * @serial
     * @since 1.6
     */
    private  String    exponentialSeparator;       // Field new in JDK 1.6

    /**
     * The locale of these currency format symbols.
     *
     * @serial
     * @since 1.4
     */
    private Locale locale;

    // currency; only the ISO code is serialized.
    private transient Currency currency;
    private transient volatile boolean currencyInitialized;

    // Proclaim JDK 1.1 FCS compatibility
    static final long serialVersionUID = 5772796243397350300L;

    // The internal serial version which says which version was written
    // - 0 (default) for version up to JDK 1.1.5
    // - 1 for version from JDK 1.1.6, which includes two new fields:
    //     monetarySeparator and exponential.
    // - 2 for version from J2SE 1.4, which includes locale field.
    // - 3 for version from J2SE 1.6, which includes exponentialSeparator field.
    private static final int currentSerialVersion = 3;

    /**
     * Describes the version of <code>DecimalFormatSymbols</code> present on the stream.
     * Possible values are:
     * <ul>
     * <li><b>0</b> (or uninitialized): versions prior to JDK 1.1.6.
     *
     * <li><b>1</b>: Versions written by JDK 1.1.6 or later, which include
     *      two new fields: <code>monetarySeparator</code> and <code>exponential</code>.
     * <li><b>2</b>: Versions written by J2SE 1.4 or later, which include a
     *      new <code>locale</code> field.
     * <li><b>3</b>: Versions written by J2SE 1.6 or later, which include a
     *      new <code>exponentialSeparator</code> field.
     * </ul>
     * When streaming out a <code>DecimalFormatSymbols</code>, the most recent format
     * (corresponding to the highest allowable <code>serialVersionOnStream</code>)
     * is always written.
     *
     * @serial
     * @since  1.1.6
     */
    private int serialVersionOnStream = currentSerialVersion;

    // BEGIN Android-added: cache for cachedIcuDFS.
    /**
     * Lazily created cached instance of an ICU DecimalFormatSymbols that's equivalent to this one.
     * This field is reset to null whenever any of the relevant fields of this class are modified
     * and will be re-created by {@link #getIcuDecimalFormatSymbols()} as necessary.
     */
    private transient android.icu.text.DecimalFormatSymbols cachedIcuDFS = null;
    // END Android-added: cache for cachedIcuDFS.
}
