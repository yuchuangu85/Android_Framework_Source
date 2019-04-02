/*
 * Copyright (C) 2014 The Android Open Source Project
 * Copyright (c) 1996, 2013, Oracle and/or its affiliates. All rights reserved.
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
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import libcore.icu.LocaleData;

import android.icu.math.MathContext;

/**
 * <code>DecimalFormat</code> is a concrete subclass of
 * <code>NumberFormat</code> that formats decimal numbers. It has a variety of
 * features designed to make it possible to parse and format numbers in any
 * locale, including support for Western, Arabic, and Indic digits.  It also
 * supports different kinds of numbers, including integers (123), fixed-point
 * numbers (123.4), scientific notation (1.23E4), percentages (12%), and
 * currency amounts ($123).  All of these can be localized.
 *
 * <p>To obtain a <code>NumberFormat</code> for a specific locale, including the
 * default locale, call one of <code>NumberFormat</code>'s factory methods, such
 * as <code>getInstance()</code>.  In general, do not call the
 * <code>DecimalFormat</code> constructors directly, since the
 * <code>NumberFormat</code> factory methods may return subclasses other than
 * <code>DecimalFormat</code>. If you need to customize the format object, do
 * something like this:
 *
 * <blockquote><pre>
 * NumberFormat f = NumberFormat.getInstance(loc);
 * if (f instanceof DecimalFormat) {
 *     ((DecimalFormat) f).setDecimalSeparatorAlwaysShown(true);
 * }
 * </pre></blockquote>
 *
 * <p>A <code>DecimalFormat</code> comprises a <em>pattern</em> and a set of
 * <em>symbols</em>.  The pattern may be set directly using
 * <code>applyPattern()</code>, or indirectly using the API methods.  The
 * symbols are stored in a <code>DecimalFormatSymbols</code> object.  When using
 * the <code>NumberFormat</code> factory methods, the pattern and symbols are
 * read from localized <code>ResourceBundle</code>s.
 *
 * <h3>Patterns</h3>
 *
 * <code>DecimalFormat</code> patterns have the following syntax:
 * <blockquote><pre>
 * <i>Pattern:</i>
 *         <i>PositivePattern</i>
 *         <i>PositivePattern</i> ; <i>NegativePattern</i>
 * <i>PositivePattern:</i>
 *         <i>Prefix<sub>opt</sub></i> <i>Number</i> <i>Suffix<sub>opt</sub></i>
 * <i>NegativePattern:</i>
 *         <i>Prefix<sub>opt</sub></i> <i>Number</i> <i>Suffix<sub>opt</sub></i>
 * <i>Prefix:</i>
 *         any Unicode characters except &#92;uFFFE, &#92;uFFFF, and special characters
 * <i>Suffix:</i>
 *         any Unicode characters except &#92;uFFFE, &#92;uFFFF, and special characters
 * <i>Number:</i>
 *         <i>Integer</i> <i>Exponent<sub>opt</sub></i>
 *         <i>Integer</i> . <i>Fraction</i> <i>Exponent<sub>opt</sub></i>
 * <i>Integer:</i>
 *         <i>MinimumInteger</i>
 *         #
 *         # <i>Integer</i>
 *         # , <i>Integer</i>
 * <i>MinimumInteger:</i>
 *         0
 *         0 <i>MinimumInteger</i>
 *         0 , <i>MinimumInteger</i>
 * <i>Fraction:</i>
 *         <i>MinimumFraction<sub>opt</sub></i> <i>OptionalFraction<sub>opt</sub></i>
 * <i>MinimumFraction:</i>
 *         0 <i>MinimumFraction<sub>opt</sub></i>
 * <i>OptionalFraction:</i>
 *         # <i>OptionalFraction<sub>opt</sub></i>
 * <i>Exponent:</i>
 *         E <i>MinimumExponent</i>
 * <i>MinimumExponent:</i>
 *         0 <i>MinimumExponent<sub>opt</sub></i>
 * </pre></blockquote>
 *
 * <p>A <code>DecimalFormat</code> pattern contains a positive and negative
 * subpattern, for example, <code>"#,##0.00;(#,##0.00)"</code>.  Each
 * subpattern has a prefix, numeric part, and suffix. The negative subpattern
 * is optional; if absent, then the positive subpattern prefixed with the
 * localized minus sign (<code>'-'</code> in most locales) is used as the
 * negative subpattern. That is, <code>"0.00"</code> alone is equivalent to
 * <code>"0.00;-0.00"</code>.  If there is an explicit negative subpattern, it
 * serves only to specify the negative prefix and suffix; the number of digits,
 * minimal digits, and other characteristics are all the same as the positive
 * pattern. That means that <code>"#,##0.0#;(#)"</code> produces precisely
 * the same behavior as <code>"#,##0.0#;(#,##0.0#)"</code>.
 *
 * <p>The prefixes, suffixes, and various symbols used for infinity, digits,
 * thousands separators, decimal separators, etc. may be set to arbitrary
 * values, and they will appear properly during formatting.  However, care must
 * be taken that the symbols and strings do not conflict, or parsing will be
 * unreliable.  For example, either the positive and negative prefixes or the
 * suffixes must be distinct for <code>DecimalFormat.parse()</code> to be able
 * to distinguish positive from negative values.  (If they are identical, then
 * <code>DecimalFormat</code> will behave as if no negative subpattern was
 * specified.)  Another example is that the decimal separator and thousands
 * separator should be distinct characters, or parsing will be impossible.
 *
 * <p>The grouping separator is commonly used for thousands, but in some
 * countries it separates ten-thousands. The grouping size is a constant number
 * of digits between the grouping characters, such as 3 for 100,000,000 or 4 for
 * 1,0000,0000.  If you supply a pattern with multiple grouping characters, the
 * interval between the last one and the end of the integer is the one that is
 * used. So <code>"#,##,###,####"</code> == <code>"######,####"</code> ==
 * <code>"##,####,####"</code>.
 *
 * <h4>Special Pattern Characters</h4>
 *
 * <p>Many characters in a pattern are taken literally; they are matched during
 * parsing and output unchanged during formatting.  Special characters, on the
 * other hand, stand for other characters, strings, or classes of characters.
 * They must be quoted, unless noted otherwise, if they are to appear in the
 * prefix or suffix as literals.
 *
 * <p>The characters listed here are used in non-localized patterns.  Localized
 * patterns use the corresponding characters taken from this formatter's
 * <code>DecimalFormatSymbols</code> object instead, and these characters lose
 * their special status.  Two exceptions are the currency sign and quote, which
 * are not localized.
 *
 * <blockquote>
 * <table border=0 cellspacing=3 cellpadding=0 summary="Chart showing symbol,
 *  location, localized, and meaning.">
 *     <tr style="background-color: rgb(204, 204, 255);">
 *          <th align=left>Symbol
 *          <th align=left>Location
 *          <th align=left>Localized?
 *          <th align=left>Meaning
 *     <tr valign=top>
 *          <td><code>0</code>
 *          <td>Number
 *          <td>Yes
 *          <td>Digit
 *     <tr style="vertical-align: top; background-color: rgb(238, 238, 255);">
 *          <td><code>#</code>
 *          <td>Number
 *          <td>Yes
 *          <td>Digit, zero shows as absent
 *     <tr valign=top>
 *          <td><code>.</code>
 *          <td>Number
 *          <td>Yes
 *          <td>Decimal separator or monetary decimal separator
 *     <tr style="vertical-align: top; background-color: rgb(238, 238, 255);">
 *          <td><code>-</code>
 *          <td>Number
 *          <td>Yes
 *          <td>Minus sign
 *     <tr valign=top>
 *          <td><code>,</code>
 *          <td>Number
 *          <td>Yes
 *          <td>Grouping separator
 *     <tr style="vertical-align: top; background-color: rgb(238, 238, 255);">
 *          <td><code>E</code>
 *          <td>Number
 *          <td>Yes
 *          <td>Separates mantissa and exponent in scientific notation.
 *              <em>Need not be quoted in prefix or suffix.</em>
 *     <tr valign=top>
 *          <td><code>;</code>
 *          <td>Subpattern boundary
 *          <td>Yes
 *          <td>Separates positive and negative subpatterns
 *     <tr style="vertical-align: top; background-color: rgb(238, 238, 255);">
 *          <td><code>%</code>
 *          <td>Prefix or suffix
 *          <td>Yes
 *          <td>Multiply by 100 and show as percentage
 *     <tr valign=top>
 *          <td><code>&#92;u2030</code>
 *          <td>Prefix or suffix
 *          <td>Yes
 *          <td>Multiply by 1000 and show as per mille value
 *     <tr style="vertical-align: top; background-color: rgb(238, 238, 255);">
 *          <td><code>&#164;</code> (<code>&#92;u00A4</code>)
 *          <td>Prefix or suffix
 *          <td>No
 *          <td>Currency sign, replaced by currency symbol.  If
 *              doubled, replaced by international currency symbol.
 *              If present in a pattern, the monetary decimal separator
 *              is used instead of the decimal separator.
 *     <tr valign=top>
 *          <td><code>'</code>
 *          <td>Prefix or suffix
 *          <td>No
 *          <td>Used to quote special characters in a prefix or suffix,
 *              for example, <code>"'#'#"</code> formats 123 to
 *              <code>"#123"</code>.  To create a single quote
 *              itself, use two in a row: <code>"# o''clock"</code>.
 * </table>
 * </blockquote>
 *
 * <h4>Scientific Notation</h4>
 *
 * <p>Numbers in scientific notation are expressed as the product of a mantissa
 * and a power of ten, for example, 1234 can be expressed as 1.234 x 10^3.  The
 * mantissa is often in the range 1.0 &le; x {@literal <} 10.0, but it need not
 * be.
 * <code>DecimalFormat</code> can be instructed to format and parse scientific
 * notation <em>only via a pattern</em>; there is currently no factory method
 * that creates a scientific notation format.  In a pattern, the exponent
 * character immediately followed by one or more digit characters indicates
 * scientific notation.  Example: <code>"0.###E0"</code> formats the number
 * 1234 as <code>"1.234E3"</code>.
 *
 * <ul>
 * <li>The number of digit characters after the exponent character gives the
 * minimum exponent digit count.  There is no maximum.  Negative exponents are
 * formatted using the localized minus sign, <em>not</em> the prefix and suffix
 * from the pattern.  This allows patterns such as <code>"0.###E0 m/s"</code>.
 *
 * <li>The minimum and maximum number of integer digits are interpreted
 * together:
 *
 * <ul>
 * <li>If the maximum number of integer digits is greater than their minimum number
 * and greater than 1, it forces the exponent to be a multiple of the maximum
 * number of integer digits, and the minimum number of integer digits to be
 * interpreted as 1.  The most common use of this is to generate
 * <em>engineering notation</em>, in which the exponent is a multiple of three,
 * e.g., <code>"##0.#####E0"</code>. Using this pattern, the number 12345
 * formats to <code>"12.345E3"</code>, and 123456 formats to
 * <code>"123.456E3"</code>.
 *
 * <li>Otherwise, the minimum number of integer digits is achieved by adjusting the
 * exponent.  Example: 0.00123 formatted with <code>"00.###E0"</code> yields
 * <code>"12.3E-4"</code>.
 * </ul>
 *
 * <li>The number of significant digits in the mantissa is the sum of the
 * <em>minimum integer</em> and <em>maximum fraction</em> digits, and is
 * unaffected by the maximum integer digits.  For example, 12345 formatted with
 * <code>"##0.##E0"</code> is <code>"12.3E3"</code>. To show all digits, set
 * the significant digits count to zero.  The number of significant digits
 * does not affect parsing.
 *
 * <li>Exponential patterns may not contain grouping separators.
 * </ul>
 *
 * <h4>Rounding</h4>
 *
 * <code>DecimalFormat</code> provides rounding modes defined in
 * {@link java.math.RoundingMode} for formatting.  By default, it uses
 * {@link java.math.RoundingMode#HALF_EVEN RoundingMode.HALF_EVEN}.
 *
 * <h4>Digits</h4>
 *
 * For formatting, <code>DecimalFormat</code> uses the ten consecutive
 * characters starting with the localized zero digit defined in the
 * <code>DecimalFormatSymbols</code> object as digits. For parsing, these
 * digits as well as all Unicode decimal digits, as defined by
 * {@link Character#digit Character.digit}, are recognized.
 *
 * <h4>Special Values</h4>
 *
 * <p><code>NaN</code> is formatted as a string, which typically has a single character
 * <code>&#92;uFFFD</code>.  This string is determined by the
 * <code>DecimalFormatSymbols</code> object.  This is the only value for which
 * the prefixes and suffixes are not used.
 *
 * <p>Infinity is formatted as a string, which typically has a single character
 * <code>&#92;u221E</code>, with the positive or negative prefixes and suffixes
 * applied.  The infinity string is determined by the
 * <code>DecimalFormatSymbols</code> object.
 *
 * <p>Negative zero (<code>"-0"</code>) parses to
 * <ul>
 * <li><code>BigDecimal(0)</code> if <code>isParseBigDecimal()</code> is
 * true,
 * <li><code>Long(0)</code> if <code>isParseBigDecimal()</code> is false
 *     and <code>isParseIntegerOnly()</code> is true,
 * <li><code>Double(-0.0)</code> if both <code>isParseBigDecimal()</code>
 * and <code>isParseIntegerOnly()</code> are false.
 * </ul>
 *
 * <h4><a name="synchronization">Synchronization</a></h4>
 *
 * <p>
 * Decimal formats are generally not synchronized.
 * It is recommended to create separate format instances for each thread.
 * If multiple threads access a format concurrently, it must be synchronized
 * externally.
 *
 * <h4>Example</h4>
 *
 * <blockquote><pre>{@code
 * <strong>// Print out a number using the localized number, integer, currency,
 * // and percent format for each locale</strong>
 * Locale[] locales = NumberFormat.getAvailableLocales();
 * double myNumber = -1234.56;
 * NumberFormat form;
 * for (int j = 0; j < 4; ++j) {
 *     System.out.println("FORMAT");
 *     for (int i = 0; i < locales.length; ++i) {
 *         if (locales[i].getCountry().length() == 0) {
 *            continue; // Skip language-only locales
 *         }
 *         System.out.print(locales[i].getDisplayName());
 *         switch (j) {
 *         case 0:
 *             form = NumberFormat.getInstance(locales[i]); break;
 *         case 1:
 *             form = NumberFormat.getIntegerInstance(locales[i]); break;
 *         case 2:
 *             form = NumberFormat.getCurrencyInstance(locales[i]); break;
 *         default:
 *             form = NumberFormat.getPercentInstance(locales[i]); break;
 *         }
 *         if (form instanceof DecimalFormat) {
 *             System.out.print(": " + ((DecimalFormat) form).toPattern());
 *         }
 *         System.out.print(" -> " + form.format(myNumber));
 *         try {
 *             System.out.println(" -> " + form.parse(form.format(myNumber)));
 *         } catch (ParseException e) {}
 *     }
 * }
 * }</pre></blockquote>
 *
 * @see          <a href="https://docs.oracle.com/javase/tutorial/i18n/format/decimalFormat.html">Java Tutorial</a>
 * @see          NumberFormat
 * @see          DecimalFormatSymbols
 * @see          ParsePosition
 * @author       Mark Davis
 * @author       Alan Liu
 */
public class DecimalFormat extends NumberFormat {

    // Android-note: This class is heavily modified from upstream OpenJDK.
    // Android's version delegates most of its work to android.icu.text.DecimalFormat. This is done
    // to avoid code duplication and to stay compatible with earlier releases that used ICU4C/ICU4J
    // to implement DecimalFormat.

    // Android-added: ICU DecimalFormat to delegate to.
    // TODO(b/68143370): switch back to ICU DecimalFormat once it can reproduce ICU 58 behavior.
    private transient android.icu.text.DecimalFormat_ICU58_Android icuDecimalFormat;

    /**
     * Creates a DecimalFormat using the default pattern and symbols
     * for the default {@link java.util.Locale.Category#FORMAT FORMAT} locale.
     * This is a convenient way to obtain a
     * DecimalFormat when internationalization is not the main concern.
     * <p>
     * To obtain standard formats for a given locale, use the factory methods
     * on NumberFormat such as getNumberInstance. These factories will
     * return the most appropriate sub-class of NumberFormat for a given
     * locale.
     *
     * @see java.text.NumberFormat#getInstance
     * @see java.text.NumberFormat#getNumberInstance
     * @see java.text.NumberFormat#getCurrencyInstance
     * @see java.text.NumberFormat#getPercentInstance
     */
    public DecimalFormat() {
        // Get the pattern for the default locale.
        Locale def = Locale.getDefault(Locale.Category.FORMAT);
        // BEGIN Android-changed: Use ICU LocaleData. Remove SPI LocaleProviderAdapter.
        /*
        LocaleProviderAdapter adapter = LocaleProviderAdapter.getAdapter(NumberFormatProvider.class, def);
        if (!(adapter instanceof ResourceBundleBasedAdapter)) {
            adapter = LocaleProviderAdapter.getResourceBundleBased();
        }
        String[] all = adapter.getLocaleResources(def).getNumberPatterns();
        */
        String pattern = LocaleData.get(def).numberPattern;
        // END Android-changed: Use ICU LocaleData. Remove SPI LocaleProviderAdapter.

        // Always applyPattern after the symbols are set
        this.symbols = DecimalFormatSymbols.getInstance(def);
        // Android-changed: use initPattern() instead of removed applyPattern(String, boolean).
        // applyPattern(all[0], false);
        initPattern(pattern);
    }


    /**
     * Creates a DecimalFormat using the given pattern and the symbols
     * for the default {@link java.util.Locale.Category#FORMAT FORMAT} locale.
     * This is a convenient way to obtain a
     * DecimalFormat when internationalization is not the main concern.
     * <p>
     * To obtain standard formats for a given locale, use the factory methods
     * on NumberFormat such as getNumberInstance. These factories will
     * return the most appropriate sub-class of NumberFormat for a given
     * locale.
     *
     * @param pattern a non-localized pattern string.
     * @exception NullPointerException if <code>pattern</code> is null
     * @exception IllegalArgumentException if the given pattern is invalid.
     * @see java.text.NumberFormat#getInstance
     * @see java.text.NumberFormat#getNumberInstance
     * @see java.text.NumberFormat#getCurrencyInstance
     * @see java.text.NumberFormat#getPercentInstance
     */
    public DecimalFormat(String pattern) {
        // Always applyPattern after the symbols are set
        this.symbols = DecimalFormatSymbols.getInstance(Locale.getDefault(Locale.Category.FORMAT));
        // Android-changed: use initPattern() instead of removed applyPattern(String, boolean).
        initPattern(pattern);
    }


    /**
     * Creates a DecimalFormat using the given pattern and symbols.
     * Use this constructor when you need to completely customize the
     * behavior of the format.
     * <p>
     * To obtain standard formats for a given
     * locale, use the factory methods on NumberFormat such as
     * getInstance or getCurrencyInstance. If you need only minor adjustments
     * to a standard format, you can modify the format returned by
     * a NumberFormat factory method.
     *
     * @param pattern a non-localized pattern string
     * @param symbols the set of symbols to be used
     * @exception NullPointerException if any of the given arguments is null
     * @exception IllegalArgumentException if the given pattern is invalid
     * @see java.text.NumberFormat#getInstance
     * @see java.text.NumberFormat#getNumberInstance
     * @see java.text.NumberFormat#getCurrencyInstance
     * @see java.text.NumberFormat#getPercentInstance
     * @see java.text.DecimalFormatSymbols
     */
    public DecimalFormat (String pattern, DecimalFormatSymbols symbols) {
        // Always applyPattern after the symbols are set
        this.symbols = (DecimalFormatSymbols)symbols.clone();
        // Android-changed: use initPattern() instead of removed applyPattern(String, boolean).
        initPattern(pattern);
    }

    // BEGIN Android-added: initPattern() and conversion methods between ICU and Java values.
    /**
     * Applies the pattern similarly to {@link #applyPattern(String)}, except it initializes
     * {@link #icuDecimalFormat} in the process. This should only be called from constructors.
     */
    private void initPattern(String pattern) {
        this.icuDecimalFormat =  new android.icu.text.DecimalFormat_ICU58_Android(pattern,
                symbols.getIcuDecimalFormatSymbols());
        updateFieldsFromIcu();
    }

    /**
     * Update local fields indicating maximum/minimum integer/fraction digit count from the ICU
     * DecimalFormat. This needs to be called whenever a new pattern is applied.
     */
    private void updateFieldsFromIcu() {
        // Imitate behaviour of ICU4C NumberFormat that Android used up to M.
        // If the pattern doesn't enforce a different value (some exponential
        // patterns do), then set the maximum integer digits to 2 billion.
        if (icuDecimalFormat.getMaximumIntegerDigits() == DOUBLE_INTEGER_DIGITS) {
            icuDecimalFormat.setMaximumIntegerDigits(2000000000);
        }
        maximumIntegerDigits = icuDecimalFormat.getMaximumIntegerDigits();
        minimumIntegerDigits = icuDecimalFormat.getMinimumIntegerDigits();
        maximumFractionDigits = icuDecimalFormat.getMaximumFractionDigits();
        minimumFractionDigits = icuDecimalFormat.getMinimumFractionDigits();
    }

    /**
     * Converts between field positions used by Java/ICU.
     * @param fp The java.text.NumberFormat.Field field position
     * @return The android.icu.text.NumberFormat.Field field position
     */
    private static FieldPosition getIcuFieldPosition(FieldPosition fp) {
        Format.Field fieldAttribute = fp.getFieldAttribute();
        if (fieldAttribute == null) return fp;

        android.icu.text.NumberFormat.Field attribute;
        if (fieldAttribute == Field.INTEGER) {
            attribute = android.icu.text.NumberFormat.Field.INTEGER;
        } else if (fieldAttribute == Field.FRACTION) {
            attribute = android.icu.text.NumberFormat.Field.FRACTION;
        } else if (fieldAttribute == Field.DECIMAL_SEPARATOR) {
            attribute = android.icu.text.NumberFormat.Field.DECIMAL_SEPARATOR;
        } else if (fieldAttribute == Field.EXPONENT_SYMBOL) {
            attribute = android.icu.text.NumberFormat.Field.EXPONENT_SYMBOL;
        } else if (fieldAttribute == Field.EXPONENT_SIGN) {
            attribute = android.icu.text.NumberFormat.Field.EXPONENT_SIGN;
        } else if (fieldAttribute == Field.EXPONENT) {
            attribute = android.icu.text.NumberFormat.Field.EXPONENT;
        } else if (fieldAttribute == Field.GROUPING_SEPARATOR) {
            attribute = android.icu.text.NumberFormat.Field.GROUPING_SEPARATOR;
        } else if (fieldAttribute == Field.CURRENCY) {
            attribute = android.icu.text.NumberFormat.Field.CURRENCY;
        } else if (fieldAttribute == Field.PERCENT) {
            attribute = android.icu.text.NumberFormat.Field.PERCENT;
        } else if (fieldAttribute == Field.PERMILLE) {
            attribute = android.icu.text.NumberFormat.Field.PERMILLE;
        } else if (fieldAttribute == Field.SIGN) {
            attribute = android.icu.text.NumberFormat.Field.SIGN;
        } else {
            throw new IllegalArgumentException("Unexpected field position attribute type.");
        }

        FieldPosition icuFieldPosition = new FieldPosition(attribute);
        icuFieldPosition.setBeginIndex(fp.getBeginIndex());
        icuFieldPosition.setEndIndex(fp.getEndIndex());
        return icuFieldPosition;
    }

    /**
     * Converts the Attribute that ICU returns in its AttributedCharacterIterator
     * responses to the type that java uses.
     * @param icuAttribute The AttributedCharacterIterator.Attribute field.
     * @return Field converted to a java.text.NumberFormat.Field field.
     */
    private static Field toJavaFieldAttribute(AttributedCharacterIterator.Attribute icuAttribute) {
        String name = icuAttribute.getName();
        if (name.equals(Field.INTEGER.getName())) {
            return Field.INTEGER;
        }
        if (name.equals(Field.CURRENCY.getName())) {
            return Field.CURRENCY;
        }
        if (name.equals(Field.DECIMAL_SEPARATOR.getName())) {
            return Field.DECIMAL_SEPARATOR;
        }
        if (name.equals(Field.EXPONENT.getName())) {
            return Field.EXPONENT;
        }
        if (name.equals(Field.EXPONENT_SIGN.getName())) {
            return Field.EXPONENT_SIGN;
        }
        if (name.equals(Field.EXPONENT_SYMBOL.getName())) {
            return Field.EXPONENT_SYMBOL;
        }
        if (name.equals(Field.FRACTION.getName())) {
            return Field.FRACTION;
        }
        if (name.equals(Field.GROUPING_SEPARATOR.getName())) {
            return Field.GROUPING_SEPARATOR;
        }
        if (name.equals(Field.SIGN.getName())) {
            return Field.SIGN;
        }
        if (name.equals(Field.PERCENT.getName())) {
            return Field.PERCENT;
        }
        if (name.equals(Field.PERMILLE.getName())) {
            return Field.PERMILLE;
        }
        throw new IllegalArgumentException("Unrecognized attribute: " + name);
    }
    // END Android-added: initPattern() and conversion methods between ICU and Java values.

    // Overrides
    /**
     * Formats a number and appends the resulting text to the given string
     * buffer.
     * The number can be of any subclass of {@link java.lang.Number}.
     * <p>
     * This implementation uses the maximum precision permitted.
     * @param number     the number to format
     * @param toAppendTo the <code>StringBuffer</code> to which the formatted
     *                   text is to be appended
     * @param pos        On input: an alignment field, if desired.
     *                   On output: the offsets of the alignment field.
     * @return           the value passed in as <code>toAppendTo</code>
     * @exception        IllegalArgumentException if <code>number</code> is
     *                   null or not an instance of <code>Number</code>.
     * @exception        NullPointerException if <code>toAppendTo</code> or
     *                   <code>pos</code> is null
     * @exception        ArithmeticException if rounding is needed with rounding
     *                   mode being set to RoundingMode.UNNECESSARY
     * @see              java.text.FieldPosition
     */
    @Override
    public final StringBuffer format(Object number,
                                     StringBuffer toAppendTo,
                                     FieldPosition pos) {
        if (number instanceof Long || number instanceof Integer ||
                   number instanceof Short || number instanceof Byte ||
                   number instanceof AtomicInteger ||
                   number instanceof AtomicLong ||
                   (number instanceof BigInteger &&
                    ((BigInteger)number).bitLength () < 64)) {
            return format(((Number)number).longValue(), toAppendTo, pos);
        } else if (number instanceof BigDecimal) {
            return format((BigDecimal)number, toAppendTo, pos);
        } else if (number instanceof BigInteger) {
            return format((BigInteger)number, toAppendTo, pos);
        } else if (number instanceof Number) {
            return format(((Number)number).doubleValue(), toAppendTo, pos);
        } else {
            throw new IllegalArgumentException("Cannot format given Object as a Number");
        }
    }

    /**
     * Formats a double to produce a string.
     * @param number    The double to format
     * @param result    where the text is to be appended
     * @param fieldPosition    On input: an alignment field, if desired.
     * On output: the offsets of the alignment field.
     * @exception ArithmeticException if rounding is needed with rounding
     *            mode being set to RoundingMode.UNNECESSARY
     * @return The formatted number string
     * @see java.text.FieldPosition
     */
    @Override
    public StringBuffer format(double number, StringBuffer result,
                               FieldPosition fieldPosition) {
        // BEGIN Android-changed: Use ICU.
        FieldPosition icuFieldPosition = getIcuFieldPosition(fieldPosition);
        icuDecimalFormat.format(number, result, icuFieldPosition);
        fieldPosition.setBeginIndex(icuFieldPosition.getBeginIndex());
        fieldPosition.setEndIndex(icuFieldPosition.getEndIndex());
        return result;
        // END Android-changed: Use ICU.
    }

    // Android-removed: private StringBuffer format(double, StringBuffer, FieldDelegate).

    /**
     * Format a long to produce a string.
     * @param number    The long to format
     * @param result    where the text is to be appended
     * @param fieldPosition    On input: an alignment field, if desired.
     * On output: the offsets of the alignment field.
     * @exception       ArithmeticException if rounding is needed with rounding
     *                  mode being set to RoundingMode.UNNECESSARY
     * @return The formatted number string
     * @see java.text.FieldPosition
     */
    @Override
    public StringBuffer format(long number, StringBuffer result,
                               FieldPosition fieldPosition) {
        // BEGIN Android-changed: Use ICU.
        FieldPosition icuFieldPosition = getIcuFieldPosition(fieldPosition);
        icuDecimalFormat.format(number, result, icuFieldPosition);
        fieldPosition.setBeginIndex(icuFieldPosition.getBeginIndex());
        fieldPosition.setEndIndex(icuFieldPosition.getEndIndex());
        return result;
        // END Android-changed: Use ICU.
    }

    // Android-removed: private StringBuffer format(long, StringBuffer, FieldDelegate).

    /**
     * Formats a BigDecimal to produce a string.
     * @param number    The BigDecimal to format
     * @param result    where the text is to be appended
     * @param fieldPosition    On input: an alignment field, if desired.
     * On output: the offsets of the alignment field.
     * @return The formatted number string
     * @exception        ArithmeticException if rounding is needed with rounding
     *                   mode being set to RoundingMode.UNNECESSARY
     * @see java.text.FieldPosition
     */
    private StringBuffer format(BigDecimal number, StringBuffer result,
                                FieldPosition fieldPosition) {
        // BEGIN Android-changed: Use ICU.
        FieldPosition icuFieldPosition = getIcuFieldPosition(fieldPosition);
        icuDecimalFormat.format(number, result, fieldPosition);
        fieldPosition.setBeginIndex(icuFieldPosition.getBeginIndex());
        fieldPosition.setEndIndex(icuFieldPosition.getEndIndex());
        return result;
        // END Android-changed: Use ICU.
    }

    // Android-removed: private StringBuffer format(BigDecimal, StringBuffer, FieldDelegate).

    /**
     * Format a BigInteger to produce a string.
     * @param number    The BigInteger to format
     * @param result    where the text is to be appended
     * @param fieldPosition    On input: an alignment field, if desired.
     * On output: the offsets of the alignment field.
     * @return The formatted number string
     * @exception        ArithmeticException if rounding is needed with rounding
     *                   mode being set to RoundingMode.UNNECESSARY
     * @see java.text.FieldPosition
     */
    private StringBuffer format(BigInteger number, StringBuffer result,
                               FieldPosition fieldPosition) {
        // BEGIN Android-changed: Use ICU.
        FieldPosition icuFieldPosition = getIcuFieldPosition(fieldPosition);
        icuDecimalFormat.format(number, result, fieldPosition);
        fieldPosition.setBeginIndex(icuFieldPosition.getBeginIndex());
        fieldPosition.setEndIndex(icuFieldPosition.getEndIndex());
        return result;
        // END Android-changed: Use ICU.
    }

    // Android-removed: private StringBuffer format(BigInteger, StringBuffer, FieldDelegate).

    /**
     * Formats an Object producing an <code>AttributedCharacterIterator</code>.
     * You can use the returned <code>AttributedCharacterIterator</code>
     * to build the resulting String, as well as to determine information
     * about the resulting String.
     * <p>
     * Each attribute key of the AttributedCharacterIterator will be of type
     * <code>NumberFormat.Field</code>, with the attribute value being the
     * same as the attribute key.
     *
     * @exception NullPointerException if obj is null.
     * @exception IllegalArgumentException when the Format cannot format the
     *            given object.
     * @exception        ArithmeticException if rounding is needed with rounding
     *                   mode being set to RoundingMode.UNNECESSARY
     * @param obj The object to format
     * @return AttributedCharacterIterator describing the formatted value.
     * @since 1.4
     */
    @Override
    public AttributedCharacterIterator formatToCharacterIterator(Object obj) {
        // BEGIN Android-changed: Use ICU.
        if (obj == null) {
            throw new NullPointerException("object == null");
        }
        // Note: formatToCharacterIterator cannot be used directly because it returns attributes
        // in terms of its own class: icu.text.NumberFormat instead of java.text.NumberFormat.
        // http://bugs.icu-project.org/trac/ticket/11931 Proposes to use the NumberFormat constants.

        AttributedCharacterIterator original = icuDecimalFormat.formatToCharacterIterator(obj);

        // Extract the text out of the ICU iterator.
        StringBuilder textBuilder = new StringBuilder(
                original.getEndIndex() - original.getBeginIndex());

        for (int i = original.getBeginIndex(); i < original.getEndIndex(); i++) {
            textBuilder.append(original.current());
            original.next();
        }

        AttributedString result = new AttributedString(textBuilder.toString());

        for (int i = original.getBeginIndex(); i < original.getEndIndex(); i++) {
            original.setIndex(i);

            for (AttributedCharacterIterator.Attribute attribute
                    : original.getAttributes().keySet()) {
                    int start = original.getRunStart();
                    int end = original.getRunLimit();
                    Field javaAttr = toJavaFieldAttribute(attribute);
                    result.addAttribute(javaAttr, javaAttr, start, end);
            }
        }

        return result.getIterator();
        // END Android-changed: Use ICU.
    }

    // Android-removed: "fast-path formating logic for double" (sic).

    // Android-removed: subformat(), append().

    /**
     * Parses text from a string to produce a <code>Number</code>.
     * <p>
     * The method attempts to parse text starting at the index given by
     * <code>pos</code>.
     * If parsing succeeds, then the index of <code>pos</code> is updated
     * to the index after the last character used (parsing does not necessarily
     * use all characters up to the end of the string), and the parsed
     * number is returned. The updated <code>pos</code> can be used to
     * indicate the starting point for the next call to this method.
     * If an error occurs, then the index of <code>pos</code> is not
     * changed, the error index of <code>pos</code> is set to the index of
     * the character where the error occurred, and null is returned.
     * <p>
     * The subclass returned depends on the value of {@link #isParseBigDecimal}
     * as well as on the string being parsed.
     * <ul>
     *   <li>If <code>isParseBigDecimal()</code> is false (the default),
     *       most integer values are returned as <code>Long</code>
     *       objects, no matter how they are written: <code>"17"</code> and
     *       <code>"17.000"</code> both parse to <code>Long(17)</code>.
     *       Values that cannot fit into a <code>Long</code> are returned as
     *       <code>Double</code>s. This includes values with a fractional part,
     *       infinite values, <code>NaN</code>, and the value -0.0.
     *       <code>DecimalFormat</code> does <em>not</em> decide whether to
     *       return a <code>Double</code> or a <code>Long</code> based on the
     *       presence of a decimal separator in the source string. Doing so
     *       would prevent integers that overflow the mantissa of a double,
     *       such as <code>"-9,223,372,036,854,775,808.00"</code>, from being
     *       parsed accurately.
     *       <p>
     *       Callers may use the <code>Number</code> methods
     *       <code>doubleValue</code>, <code>longValue</code>, etc., to obtain
     *       the type they want.
     *   <li>If <code>isParseBigDecimal()</code> is true, values are returned
     *       as <code>BigDecimal</code> objects. The values are the ones
     *       constructed by {@link java.math.BigDecimal#BigDecimal(String)}
     *       for corresponding strings in locale-independent format. The
     *       special cases negative and positive infinity and NaN are returned
     *       as <code>Double</code> instances holding the values of the
     *       corresponding <code>Double</code> constants.
     * </ul>
     * <p>
     * <code>DecimalFormat</code> parses all Unicode characters that represent
     * decimal digits, as defined by <code>Character.digit()</code>. In
     * addition, <code>DecimalFormat</code> also recognizes as digits the ten
     * consecutive characters starting with the localized zero digit defined in
     * the <code>DecimalFormatSymbols</code> object.
     *
     * @param text the string to be parsed
     * @param pos  A <code>ParsePosition</code> object with index and error
     *             index information as described above.
     * @return     the parsed value, or <code>null</code> if the parse fails
     * @exception  NullPointerException if <code>text</code> or
     *             <code>pos</code> is null.
     */
    @Override
    public Number parse(String text, ParsePosition pos) {
        // BEGIN Android-changed: Use ICU.
        // Return early if the parse position is bogus.
        if (pos.index < 0 || pos.index >= text.length()) {
            return null;
        }

        // This might return android.icu.math.BigDecimal, java.math.BigInteger or a primitive type.
        Number number = icuDecimalFormat.parse(text, pos);
        if (number == null) {
            return null;
        }
        if (isParseBigDecimal()) {
            if (number instanceof Long) {
                return new BigDecimal(number.longValue());
            }
            if ((number instanceof Double) && !((Double) number).isInfinite()
                    && !((Double) number).isNaN()) {
                return new BigDecimal(number.toString());
            }
            if ((number instanceof Double) &&
                    (((Double) number).isNaN() || ((Double) number).isInfinite())) {
                return number;
            }
            if (number instanceof android.icu.math.BigDecimal) {
                return ((android.icu.math.BigDecimal) number).toBigDecimal();
            }
        }
        if ((number instanceof android.icu.math.BigDecimal) || (number instanceof BigInteger)) {
            return number.doubleValue();
        }
        if (isParseIntegerOnly() && number.equals(new Double(-0.0))) {
            return 0L;
        }
        return number;
        // END Android-changed: Use ICU.
    }

    // Android-removed: STATUS_* constants, multiplier fields and methods and subparse(String, ...).

    /**
     * Returns a copy of the decimal format symbols, which is generally not
     * changed by the programmer or user.
     * @return a copy of the desired DecimalFormatSymbols
     * @see java.text.DecimalFormatSymbols
     */
    public DecimalFormatSymbols getDecimalFormatSymbols() {
        // Android-changed: Use ICU.
        return DecimalFormatSymbols.fromIcuInstance(icuDecimalFormat.getDecimalFormatSymbols());
    }


    /**
     * Sets the decimal format symbols, which is generally not changed
     * by the programmer or user.
     * @param newSymbols desired DecimalFormatSymbols
     * @see java.text.DecimalFormatSymbols
     */
    public void setDecimalFormatSymbols(DecimalFormatSymbols newSymbols) {
        try {
            // don't allow multiple references
            symbols = (DecimalFormatSymbols) newSymbols.clone();
            // Android-changed: Use ICU.
            icuDecimalFormat.setDecimalFormatSymbols(symbols.getIcuDecimalFormatSymbols());
        } catch (Exception foo) {
            // should never happen
        }
    }

    /**
     * Get the positive prefix.
     * <P>Examples: +123, $123, sFr123
     *
     * @return the positive prefix
     */
    public String getPositivePrefix () {
        // Android-changed: Use ICU.
        return icuDecimalFormat.getPositivePrefix();
    }

    /**
     * Set the positive prefix.
     * <P>Examples: +123, $123, sFr123
     *
     * @param newValue the new positive prefix
     */
    public void setPositivePrefix (String newValue) {
        // Android-changed: Use ICU.
        icuDecimalFormat.setPositivePrefix(newValue);
    }

    // Android-removed: private helper getPositivePrefixFieldPositions().

    /**
     * Get the  prefix.
     * <P>Examples: -123, ($123) (with negative suffix), sFr-123
     *
     * @return the negative prefix
     */
    public String getNegativePrefix () {
        // Android-changed: Use ICU.
        return icuDecimalFormat.getNegativePrefix();
    }

    /**
     * Set the negative prefix.
     * <P>Examples: -123, ($123) (with negative suffix), sFr-123
     *
     * @param newValue the new negative prefix
     */
    public void setNegativePrefix (String newValue) {
        // Android-changed: Use ICU.
        icuDecimalFormat.setNegativePrefix(newValue);
    }

    // Android-removed: private helper getNegativePrefixFieldPositions().

    /**
     * Get the positive suffix.
     * <P>Example: 123%
     *
     * @return the positive suffix
     */
    public String getPositiveSuffix () {
        // Android-changed: Use ICU.
        return icuDecimalFormat.getPositiveSuffix();
    }

    /**
     * Set the positive suffix.
     * <P>Example: 123%
     *
     * @param newValue the new positive suffix
     */
    public void setPositiveSuffix (String newValue) {
        // Android-changed: Use ICU.
        icuDecimalFormat.setPositiveSuffix(newValue);
    }

    // Android-removed: private helper getPositiveSuffixFieldPositions().

    /**
     * Get the negative suffix.
     * <P>Examples: -123%, ($123) (with positive suffixes)
     *
     * @return the negative suffix
     */
    public String getNegativeSuffix () {
        // Android-changed: Use ICU.
        return icuDecimalFormat.getNegativeSuffix();
    }

    /**
     * Set the negative suffix.
     * <P>Examples: 123%
     *
     * @param newValue the new negative suffix
     */
    public void setNegativeSuffix (String newValue) {
        // Android-changed: Use ICU.
        icuDecimalFormat.setNegativeSuffix(newValue);
    }

    // Android-removed: private helper getNegativeSuffixFieldPositions().

    /**
     * Gets the multiplier for use in percent, per mille, and similar
     * formats.
     *
     * @return the multiplier
     * @see #setMultiplier(int)
     */
    public int getMultiplier () {
        // Android-changed: Use ICU.
        return icuDecimalFormat.getMultiplier();
    }

    /**
     * Sets the multiplier for use in percent, per mille, and similar
     * formats.
     * For a percent format, set the multiplier to 100 and the suffixes to
     * have '%' (for Arabic, use the Arabic percent sign).
     * For a per mille format, set the multiplier to 1000 and the suffixes to
     * have '&#92;u2030'.
     *
     * <P>Example: with multiplier 100, 1.23 is formatted as "123", and
     * "123" is parsed into 1.23.
     *
     * @param newValue the new multiplier
     * @see #getMultiplier
     */
    public void setMultiplier (int newValue) {
        icuDecimalFormat.setMultiplier(newValue);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setGroupingUsed(boolean newValue) {
        // Android-changed: Use ICU.
        icuDecimalFormat.setGroupingUsed(newValue);
        // Android-removed: fast path related code.
        // fastPathCheckNeeded = true;
    }

    // BEGIN Android-added: isGroupingUsed() override delegating to ICU.
    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isGroupingUsed() {
        return icuDecimalFormat.isGroupingUsed();
    }
    // END Android-added: isGroupingUsed() override delegating to ICU.

    /**
     * Return the grouping size. Grouping size is the number of digits between
     * grouping separators in the integer portion of a number.  For example,
     * in the number "123,456.78", the grouping size is 3.
     *
     * @return the grouping size
     * @see #setGroupingSize
     * @see java.text.NumberFormat#isGroupingUsed
     * @see java.text.DecimalFormatSymbols#getGroupingSeparator
     */
    public int getGroupingSize () {
        // Android-changed: Use ICU.
        return icuDecimalFormat.getGroupingSize();
    }

    /**
     * Set the grouping size. Grouping size is the number of digits between
     * grouping separators in the integer portion of a number.  For example,
     * in the number "123,456.78", the grouping size is 3.
     * <br>
     * The value passed in is converted to a byte, which may lose information.
     *
     * @param newValue the new grouping size
     * @see #getGroupingSize
     * @see java.text.NumberFormat#setGroupingUsed
     * @see java.text.DecimalFormatSymbols#setGroupingSeparator
     */
    public void setGroupingSize (int newValue) {
        // Android-changed: Use ICU.
        icuDecimalFormat.setGroupingSize(newValue);
        // Android-removed: fast path related code.
        // fastPathCheckNeeded = true;
    }

    /**
     * Allows you to get the behavior of the decimal separator with integers.
     * (The decimal separator will always appear with decimals.)
     * <P>Example: Decimal ON: 12345 &rarr; 12345.; OFF: 12345 &rarr; 12345
     *
     * @return {@code true} if the decimal separator is always shown;
     *         {@code false} otherwise
     */
    public boolean isDecimalSeparatorAlwaysShown() {
        // Android-changed: Use ICU.
        return icuDecimalFormat.isDecimalSeparatorAlwaysShown();
    }

    /**
     * Allows you to set the behavior of the decimal separator with integers.
     * (The decimal separator will always appear with decimals.)
     * <P>Example: Decimal ON: 12345 &rarr; 12345.; OFF: 12345 &rarr; 12345
     *
     * @param newValue {@code true} if the decimal separator is always shown;
     *                 {@code false} otherwise
     */
    public void setDecimalSeparatorAlwaysShown(boolean newValue) {
        // Android-changed: Use ICU.
        icuDecimalFormat.setDecimalSeparatorAlwaysShown(newValue);
    }

    /**
     * Returns whether the {@link #parse(java.lang.String, java.text.ParsePosition)}
     * method returns <code>BigDecimal</code>. The default value is false.
     *
     * @return {@code true} if the parse method returns BigDecimal;
     *         {@code false} otherwise
     * @see #setParseBigDecimal
     * @since 1.5
     */
    public boolean isParseBigDecimal() {
        // Android-changed: Use ICU.
        return icuDecimalFormat.isParseBigDecimal();
    }

    /**
     * Sets whether the {@link #parse(java.lang.String, java.text.ParsePosition)}
     * method returns <code>BigDecimal</code>.
     *
     * @param newValue {@code true} if the parse method returns BigDecimal;
     *                 {@code false} otherwise
     * @see #isParseBigDecimal
     * @since 1.5
     */
    public void setParseBigDecimal(boolean newValue) {
        // Android-changed: Use ICU.
        icuDecimalFormat.setParseBigDecimal(newValue);
    }

    // BEGIN Android-added: setParseIntegerOnly()/isParseIntegerOnly() overrides delegating to ICU.
    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isParseIntegerOnly() {
        return icuDecimalFormat.isParseIntegerOnly();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setParseIntegerOnly(boolean value) {
        super.setParseIntegerOnly(value);
        icuDecimalFormat.setParseIntegerOnly(value);
    }
    // END Android-added: setParseIntegerOnly()/isParseIntegerOnly() overrides delegating to ICU.

    /**
     * Standard override; no change in semantics.
     */
    @Override
    public Object clone() {
        // BEGIN Android-changed: Use ICU, remove fast path related code.
        try {
            DecimalFormat other = (DecimalFormat) super.clone();
            other.icuDecimalFormat = (android.icu.text.DecimalFormat_ICU58_Android) icuDecimalFormat.clone();
            other.symbols = (DecimalFormatSymbols) symbols.clone();
            return other;
        } catch (Exception e) {
            throw new InternalError();
        }
        // END Android-changed: Use ICU, remove fast path related code.
    }

    // BEGIN Android-changed: re-implement equals() using ICU fields.
    /**
     * Overrides equals
     */
    @Override
    public boolean equals(Object obj)
    {
        if (obj == null) {
            return false;
        }
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof DecimalFormat)) {
            return false;
        }
        DecimalFormat other = (DecimalFormat) obj;
        return icuDecimalFormat.equals(other.icuDecimalFormat)
            && compareIcuRoundingIncrement(other.icuDecimalFormat);
    }

    private boolean compareIcuRoundingIncrement(android.icu.text.DecimalFormat_ICU58_Android other) {
        BigDecimal increment = this.icuDecimalFormat.getRoundingIncrement();
        if (increment != null) {
            return (other.getRoundingIncrement() != null)
                && increment.equals(other.getRoundingIncrement());
        }
        return other.getRoundingIncrement() == null;
    }
    // END Android-changed: re-implement equals() using ICU fields.

    /**
     * Overrides hashCode
     */
    @Override
    public int hashCode() {
        // Android-changed: use getPositivePrefix() instead of positivePrefix field.
        return super.hashCode() * 37 + getPositivePrefix().hashCode();
        // just enough fields for a reasonable distribution
    }

    /**
     * Synthesizes a pattern string that represents the current state
     * of this Format object.
     *
     * @return a pattern string
     * @see #applyPattern
     */
    public String toPattern() {
        // Android-changed: use ICU.
        return icuDecimalFormat.toPattern();
    }

    /**
     * Synthesizes a localized pattern string that represents the current
     * state of this Format object.
     *
     * @return a localized pattern string
     * @see #applyPattern
     */
    public String toLocalizedPattern() {
        // Android-changed: use ICU.
        return icuDecimalFormat.toLocalizedPattern();
    }

    // Android-removed: private helper methods expandAffixes(), expandAffix(), toPattern(boolean).

    /**
     * Apply the given pattern to this Format object.  A pattern is a
     * short-hand specification for the various formatting properties.
     * These properties can also be changed individually through the
     * various setter methods.
     * <p>
     * There is no limit to integer digits set
     * by this routine, since that is the typical end-user desire;
     * use setMaximumInteger if you want to set a real value.
     * For negative numbers, use a second pattern, separated by a semicolon
     * <P>Example <code>"#,#00.0#"</code> &rarr; 1,234.56
     * <P>This means a minimum of 2 integer digits, 1 fraction digit, and
     * a maximum of 2 fraction digits.
     * <p>Example: <code>"#,#00.0#;(#,#00.0#)"</code> for negatives in
     * parentheses.
     * <p>In negative patterns, the minimum and maximum counts are ignored;
     * these are presumed to be set in the positive pattern.
     *
     * @param pattern a new pattern
     * @exception NullPointerException if <code>pattern</code> is null
     * @exception IllegalArgumentException if the given pattern is invalid.
     */
    public void applyPattern(String pattern) {
        // Android-changed: use ICU.
        icuDecimalFormat.applyPattern(pattern);
        updateFieldsFromIcu();
    }

    /**
     * Apply the given pattern to this Format object.  The pattern
     * is assumed to be in a localized notation. A pattern is a
     * short-hand specification for the various formatting properties.
     * These properties can also be changed individually through the
     * various setter methods.
     * <p>
     * There is no limit to integer digits set
     * by this routine, since that is the typical end-user desire;
     * use setMaximumInteger if you want to set a real value.
     * For negative numbers, use a second pattern, separated by a semicolon
     * <P>Example <code>"#,#00.0#"</code> &rarr; 1,234.56
     * <P>This means a minimum of 2 integer digits, 1 fraction digit, and
     * a maximum of 2 fraction digits.
     * <p>Example: <code>"#,#00.0#;(#,#00.0#)"</code> for negatives in
     * parentheses.
     * <p>In negative patterns, the minimum and maximum counts are ignored;
     * these are presumed to be set in the positive pattern.
     *
     * @param pattern a new pattern
     * @exception NullPointerException if <code>pattern</code> is null
     * @exception IllegalArgumentException if the given pattern is invalid.
     */
    public void applyLocalizedPattern(String pattern) {
        // Android-changed: use ICU.
        icuDecimalFormat.applyLocalizedPattern(pattern);
        updateFieldsFromIcu();
    }

    // Android-removed: applyPattern(String, boolean) as apply[Localized]Pattern calls ICU directly.

    /**
     * Sets the maximum number of digits allowed in the integer portion of a
     * number.
     * For formatting numbers other than <code>BigInteger</code> and
     * <code>BigDecimal</code> objects, the lower of <code>newValue</code> and
     * 309 is used. Negative input values are replaced with 0.
     * @see NumberFormat#setMaximumIntegerDigits
     */
    @Override
    public void setMaximumIntegerDigits(int newValue) {
        maximumIntegerDigits = Math.min(Math.max(0, newValue), MAXIMUM_INTEGER_DIGITS);
        super.setMaximumIntegerDigits((maximumIntegerDigits > DOUBLE_INTEGER_DIGITS) ?
            DOUBLE_INTEGER_DIGITS : maximumIntegerDigits);
        if (minimumIntegerDigits > maximumIntegerDigits) {
            minimumIntegerDigits = maximumIntegerDigits;
            super.setMinimumIntegerDigits((minimumIntegerDigits > DOUBLE_INTEGER_DIGITS) ?
                DOUBLE_INTEGER_DIGITS : minimumIntegerDigits);
        }
        // Android-changed: use ICU.
        icuDecimalFormat.setMaximumIntegerDigits(getMaximumIntegerDigits());
        // Android-removed: fast path related code.
        // fastPathCheckNeeded = true;
    }

    /**
     * Sets the minimum number of digits allowed in the integer portion of a
     * number.
     * For formatting numbers other than <code>BigInteger</code> and
     * <code>BigDecimal</code> objects, the lower of <code>newValue</code> and
     * 309 is used. Negative input values are replaced with 0.
     * @see NumberFormat#setMinimumIntegerDigits
     */
    @Override
    public void setMinimumIntegerDigits(int newValue) {
        minimumIntegerDigits = Math.min(Math.max(0, newValue), MAXIMUM_INTEGER_DIGITS);
        super.setMinimumIntegerDigits((minimumIntegerDigits > DOUBLE_INTEGER_DIGITS) ?
            DOUBLE_INTEGER_DIGITS : minimumIntegerDigits);
        if (minimumIntegerDigits > maximumIntegerDigits) {
            maximumIntegerDigits = minimumIntegerDigits;
            super.setMaximumIntegerDigits((maximumIntegerDigits > DOUBLE_INTEGER_DIGITS) ?
                DOUBLE_INTEGER_DIGITS : maximumIntegerDigits);
        }
        // Android-changed: use ICU.
        icuDecimalFormat.setMinimumIntegerDigits(getMinimumIntegerDigits());
        // Android-removed: fast path related code.
        // fastPathCheckNeeded = true;
    }

    /**
     * Sets the maximum number of digits allowed in the fraction portion of a
     * number.
     * For formatting numbers other than <code>BigInteger</code> and
     * <code>BigDecimal</code> objects, the lower of <code>newValue</code> and
     * 340 is used. Negative input values are replaced with 0.
     * @see NumberFormat#setMaximumFractionDigits
     */
    @Override
    public void setMaximumFractionDigits(int newValue) {
        maximumFractionDigits = Math.min(Math.max(0, newValue), MAXIMUM_FRACTION_DIGITS);
        super.setMaximumFractionDigits((maximumFractionDigits > DOUBLE_FRACTION_DIGITS) ?
            DOUBLE_FRACTION_DIGITS : maximumFractionDigits);
        if (minimumFractionDigits > maximumFractionDigits) {
            minimumFractionDigits = maximumFractionDigits;
            super.setMinimumFractionDigits((minimumFractionDigits > DOUBLE_FRACTION_DIGITS) ?
                DOUBLE_FRACTION_DIGITS : minimumFractionDigits);
        }
        // Android-changed: use ICU.
        icuDecimalFormat.setMaximumFractionDigits(getMaximumFractionDigits());
        // Android-removed: fast path related code.
        // fastPathCheckNeeded = true;
    }

    /**
     * Sets the minimum number of digits allowed in the fraction portion of a
     * number.
     * For formatting numbers other than <code>BigInteger</code> and
     * <code>BigDecimal</code> objects, the lower of <code>newValue</code> and
     * 340 is used. Negative input values are replaced with 0.
     * @see NumberFormat#setMinimumFractionDigits
     */
    @Override
    public void setMinimumFractionDigits(int newValue) {
        minimumFractionDigits = Math.min(Math.max(0, newValue), MAXIMUM_FRACTION_DIGITS);
        super.setMinimumFractionDigits((minimumFractionDigits > DOUBLE_FRACTION_DIGITS) ?
            DOUBLE_FRACTION_DIGITS : minimumFractionDigits);
        if (minimumFractionDigits > maximumFractionDigits) {
            maximumFractionDigits = minimumFractionDigits;
            super.setMaximumFractionDigits((maximumFractionDigits > DOUBLE_FRACTION_DIGITS) ?
                DOUBLE_FRACTION_DIGITS : maximumFractionDigits);
        }
        // Android-changed: use ICU.
        icuDecimalFormat.setMinimumFractionDigits(getMinimumFractionDigits());
        // Android-removed: fast path related code.
        // fastPathCheckNeeded = true;
    }

    /**
     * Gets the maximum number of digits allowed in the integer portion of a
     * number.
     * For formatting numbers other than <code>BigInteger</code> and
     * <code>BigDecimal</code> objects, the lower of the return value and
     * 309 is used.
     * @see #setMaximumIntegerDigits
     */
    @Override
    public int getMaximumIntegerDigits() {
        return maximumIntegerDigits;
    }

    /**
     * Gets the minimum number of digits allowed in the integer portion of a
     * number.
     * For formatting numbers other than <code>BigInteger</code> and
     * <code>BigDecimal</code> objects, the lower of the return value and
     * 309 is used.
     * @see #setMinimumIntegerDigits
     */
    @Override
    public int getMinimumIntegerDigits() {
        return minimumIntegerDigits;
    }

    /**
     * Gets the maximum number of digits allowed in the fraction portion of a
     * number.
     * For formatting numbers other than <code>BigInteger</code> and
     * <code>BigDecimal</code> objects, the lower of the return value and
     * 340 is used.
     * @see #setMaximumFractionDigits
     */
    @Override
    public int getMaximumFractionDigits() {
        return maximumFractionDigits;
    }

    /**
     * Gets the minimum number of digits allowed in the fraction portion of a
     * number.
     * For formatting numbers other than <code>BigInteger</code> and
     * <code>BigDecimal</code> objects, the lower of the return value and
     * 340 is used.
     * @see #setMinimumFractionDigits
     */
    @Override
    public int getMinimumFractionDigits() {
        return minimumFractionDigits;
    }

    /**
     * Gets the currency used by this decimal format when formatting
     * currency values.
     * The currency is obtained by calling
     * {@link DecimalFormatSymbols#getCurrency DecimalFormatSymbols.getCurrency}
     * on this number format's symbols.
     *
     * @return the currency used by this decimal format, or <code>null</code>
     * @since 1.4
     */
    @Override
    public Currency getCurrency() {
        return symbols.getCurrency();
    }

    /**
     * Sets the currency used by this number format when formatting
     * currency values. This does not update the minimum or maximum
     * number of fraction digits used by the number format.
     * The currency is set by calling
     * {@link DecimalFormatSymbols#setCurrency DecimalFormatSymbols.setCurrency}
     * on this number format's symbols.
     *
     * @param currency the new currency to be used by this decimal format
     * @exception NullPointerException if <code>currency</code> is null
     * @since 1.4
     */
    @Override
    public void setCurrency(Currency currency) {
        // BEGIN Android-changed: use ICU.
        // Set the international currency symbol, and currency symbol on the DecimalFormatSymbols
        // object and tell ICU to use that.
        if (currency != symbols.getCurrency()
            || !currency.getSymbol().equals(symbols.getCurrencySymbol())) {
            symbols.setCurrency(currency);
            icuDecimalFormat.setDecimalFormatSymbols(symbols.getIcuDecimalFormatSymbols());
            // Giving the icuDecimalFormat a new currency will cause the fractional digits to be
            // updated. This class is specified to not touch the fraction digits, so we re-set them.
            icuDecimalFormat.setMinimumFractionDigits(minimumFractionDigits);
            icuDecimalFormat.setMaximumFractionDigits(maximumFractionDigits);
        }
        // END Android-changed: use ICU.
        // Android-removed: fast path related code.
        // fastPathCheckNeeded = true;
    }

    /**
     * Gets the {@link java.math.RoundingMode} used in this DecimalFormat.
     *
     * @return The <code>RoundingMode</code> used for this DecimalFormat.
     * @see #setRoundingMode(RoundingMode)
     * @since 1.6
     */
    @Override
    public RoundingMode getRoundingMode() {
        return roundingMode;
    }

    // Android-added: convertRoundingMode() to convert between Java and ICU RoundingMode enums.
    private static int convertRoundingMode(RoundingMode rm) {
        switch (rm) {
        case UP:
            return MathContext.ROUND_UP;
        case DOWN:
            return MathContext.ROUND_DOWN;
        case CEILING:
            return MathContext.ROUND_CEILING;
        case FLOOR:
            return MathContext.ROUND_FLOOR;
        case HALF_UP:
            return MathContext.ROUND_HALF_UP;
        case HALF_DOWN:
            return MathContext.ROUND_HALF_DOWN;
        case HALF_EVEN:
            return MathContext.ROUND_HALF_EVEN;
        case UNNECESSARY:
            return MathContext.ROUND_UNNECESSARY;
        }
        throw new IllegalArgumentException("Invalid rounding mode specified");
    }

    /**
     * Sets the {@link java.math.RoundingMode} used in this DecimalFormat.
     *
     * @param roundingMode The <code>RoundingMode</code> to be used
     * @see #getRoundingMode()
     * @exception NullPointerException if <code>roundingMode</code> is null.
     * @since 1.6
     */
    @Override
    public void setRoundingMode(RoundingMode roundingMode) {
        if (roundingMode == null) {
            throw new NullPointerException();
        }

        this.roundingMode = roundingMode;
        // Android-changed: use ICU.
        icuDecimalFormat.setRoundingMode(convertRoundingMode(roundingMode));
        // Android-removed: fast path related code.
        // fastPathCheckNeeded = true;
    }

    // BEGIN Android-added: 7u40 version of adjustForCurrencyDefaultFractionDigits().
    // This method was removed in OpenJDK 8 in favor of doing equivalent work in the provider. Since
    // Android removed support for providers for NumberFormat we keep this method around as an
    // "Android addition".
    /**
     * Adjusts the minimum and maximum fraction digits to values that
     * are reasonable for the currency's default fraction digits.
     */
    void adjustForCurrencyDefaultFractionDigits() {
        Currency currency = symbols.getCurrency();
        if (currency == null) {
            try {
                currency = Currency.getInstance(symbols.getInternationalCurrencySymbol());
            } catch (IllegalArgumentException e) {
            }
        }
        if (currency != null) {
            int digits = currency.getDefaultFractionDigits();
            if (digits != -1) {
                int oldMinDigits = getMinimumFractionDigits();
                // Common patterns are "#.##", "#.00", "#".
                // Try to adjust all of them in a reasonable way.
                if (oldMinDigits == getMaximumFractionDigits()) {
                    setMinimumFractionDigits(digits);
                    setMaximumFractionDigits(digits);
                } else {
                    setMinimumFractionDigits(Math.min(digits, oldMinDigits));
                    setMaximumFractionDigits(digits);
                }
            }
        }
    }
    // END Android-added: Upstream code from earlier OpenJDK release.

    // BEGIN Android-added: Custom serialization code for compatibility with RI serialization.
    // the fields list to be serialized
    private static final ObjectStreamField[] serialPersistentFields = {
            new ObjectStreamField("positivePrefix", String.class),
            new ObjectStreamField("positiveSuffix", String.class),
            new ObjectStreamField("negativePrefix", String.class),
            new ObjectStreamField("negativeSuffix", String.class),
            new ObjectStreamField("posPrefixPattern", String.class),
            new ObjectStreamField("posSuffixPattern", String.class),
            new ObjectStreamField("negPrefixPattern", String.class),
            new ObjectStreamField("negSuffixPattern", String.class),
            new ObjectStreamField("multiplier", int.class),
            new ObjectStreamField("groupingSize", byte.class),
            new ObjectStreamField("groupingUsed", boolean.class),
            new ObjectStreamField("decimalSeparatorAlwaysShown", boolean.class),
            new ObjectStreamField("parseBigDecimal", boolean.class),
            new ObjectStreamField("roundingMode", RoundingMode.class),
            new ObjectStreamField("symbols", DecimalFormatSymbols.class),
            new ObjectStreamField("useExponentialNotation", boolean.class),
            new ObjectStreamField("minExponentDigits", byte.class),
            new ObjectStreamField("maximumIntegerDigits", int.class),
            new ObjectStreamField("minimumIntegerDigits", int.class),
            new ObjectStreamField("maximumFractionDigits", int.class),
            new ObjectStreamField("minimumFractionDigits", int.class),
            new ObjectStreamField("serialVersionOnStream", int.class),
    };

    private void writeObject(ObjectOutputStream stream) throws IOException, ClassNotFoundException {
        ObjectOutputStream.PutField fields = stream.putFields();
        fields.put("positivePrefix", icuDecimalFormat.getPositivePrefix());
        fields.put("positiveSuffix", icuDecimalFormat.getPositiveSuffix());
        fields.put("negativePrefix", icuDecimalFormat.getNegativePrefix());
        fields.put("negativeSuffix", icuDecimalFormat.getNegativeSuffix());
        fields.put("posPrefixPattern", (String) null);
        fields.put("posSuffixPattern", (String) null);
        fields.put("negPrefixPattern", (String) null);
        fields.put("negSuffixPattern", (String) null);
        fields.put("multiplier", icuDecimalFormat.getMultiplier());
        fields.put("groupingSize", (byte) icuDecimalFormat.getGroupingSize());
        fields.put("groupingUsed", icuDecimalFormat.isGroupingUsed());
        fields.put("decimalSeparatorAlwaysShown", icuDecimalFormat.isDecimalSeparatorAlwaysShown());
        fields.put("parseBigDecimal", icuDecimalFormat.isParseBigDecimal());
        fields.put("roundingMode", roundingMode);
        fields.put("symbols", symbols);
        fields.put("useExponentialNotation", false);
        fields.put("minExponentDigits", (byte) 0);
        fields.put("maximumIntegerDigits", icuDecimalFormat.getMaximumIntegerDigits());
        fields.put("minimumIntegerDigits", icuDecimalFormat.getMinimumIntegerDigits());
        fields.put("maximumFractionDigits", icuDecimalFormat.getMaximumFractionDigits());
        fields.put("minimumFractionDigits", icuDecimalFormat.getMinimumFractionDigits());
        fields.put("serialVersionOnStream", currentSerialVersion);
        stream.writeFields();
    }
    // BEGIN Android-added: Custom serialization code for compatibility with RI serialization.

    /**
     * Reads the default serializable fields from the stream and performs
     * validations and adjustments for older serialized versions. The
     * validations and adjustments are:
     * <ol>
     * <li>
     * Verify that the superclass's digit count fields correctly reflect
     * the limits imposed on formatting numbers other than
     * <code>BigInteger</code> and <code>BigDecimal</code> objects. These
     * limits are stored in the superclass for serialization compatibility
     * with older versions, while the limits for <code>BigInteger</code> and
     * <code>BigDecimal</code> objects are kept in this class.
     * If, in the superclass, the minimum or maximum integer digit count is
     * larger than <code>DOUBLE_INTEGER_DIGITS</code> or if the minimum or
     * maximum fraction digit count is larger than
     * <code>DOUBLE_FRACTION_DIGITS</code>, then the stream data is invalid
     * and this method throws an <code>InvalidObjectException</code>.
     * <li>
     * If <code>serialVersionOnStream</code> is less than 4, initialize
     * <code>roundingMode</code> to {@link java.math.RoundingMode#HALF_EVEN
     * RoundingMode.HALF_EVEN}.  This field is new with version 4.
     * <li>
     * If <code>serialVersionOnStream</code> is less than 3, then call
     * the setters for the minimum and maximum integer and fraction digits with
     * the values of the corresponding superclass getters to initialize the
     * fields in this class. The fields in this class are new with version 3.
     * <li>
     * If <code>serialVersionOnStream</code> is less than 1, indicating that
     * the stream was written by JDK 1.1, initialize
     * <code>useExponentialNotation</code>
     * to false, since it was not present in JDK 1.1.
     * <li>
     * Set <code>serialVersionOnStream</code> to the maximum allowed value so
     * that default serialization will work properly if this object is streamed
     * out again.
     * </ol>
     *
     * <p>Stream versions older than 2 will not have the affix pattern variables
     * <code>posPrefixPattern</code> etc.  As a result, they will be initialized
     * to <code>null</code>, which means the affix strings will be taken as
     * literal values.  This is exactly what we want, since that corresponds to
     * the pre-version-2 behavior.
     */
    // BEGIN Android-added: Custom serialization code for compatibility with RI serialization.
    private void readObject(ObjectInputStream stream)
            throws IOException, ClassNotFoundException {
        ObjectInputStream.GetField fields = stream.readFields();
        this.symbols = (DecimalFormatSymbols) fields.get("symbols", null);

        initPattern("#");

        // Calling a setter method on an ICU DecimalFormat object will change the object's internal
        // state, even if the value set is the same as the default value (ICU Ticket #13266).
        //
        // In an attempt to create objects that are equals() to the ones that were serialized, it's
        // therefore assumed here that any values that are the same as the default values were the
        // default values (ie. no setter was called to explicitly set that value).

        String positivePrefix = (String) fields.get("positivePrefix", "");
        if (!Objects.equals(positivePrefix, icuDecimalFormat.getPositivePrefix())) {
            icuDecimalFormat.setPositivePrefix(positivePrefix);
        }

        String positiveSuffix = (String) fields.get("positiveSuffix", "");
        if (!Objects.equals(positiveSuffix, icuDecimalFormat.getPositiveSuffix())) {
            icuDecimalFormat.setPositiveSuffix(positiveSuffix);
        }

        String negativePrefix = (String) fields.get("negativePrefix", "-");
        if (!Objects.equals(negativePrefix, icuDecimalFormat.getNegativePrefix())) {
            icuDecimalFormat.setNegativePrefix(negativePrefix);
        }

        String negativeSuffix = (String) fields.get("negativeSuffix", "");
        if (!Objects.equals(negativeSuffix, icuDecimalFormat.getNegativeSuffix())) {
            icuDecimalFormat.setNegativeSuffix(negativeSuffix);
        }

        int multiplier = fields.get("multiplier", 1);
        if (multiplier != icuDecimalFormat.getMultiplier()) {
            icuDecimalFormat.setMultiplier(multiplier);
        }

        boolean groupingUsed = fields.get("groupingUsed", true);
        if (groupingUsed != icuDecimalFormat.isGroupingUsed()) {
            icuDecimalFormat.setGroupingUsed(groupingUsed);
        }

        int groupingSize = fields.get("groupingSize", (byte) 3);
        if (groupingSize != icuDecimalFormat.getGroupingSize()) {
            icuDecimalFormat.setGroupingSize(groupingSize);
        }

        boolean decimalSeparatorAlwaysShown = fields.get("decimalSeparatorAlwaysShown", false);
        if (decimalSeparatorAlwaysShown != icuDecimalFormat.isDecimalSeparatorAlwaysShown()) {
            icuDecimalFormat.setDecimalSeparatorAlwaysShown(decimalSeparatorAlwaysShown);
        }

        RoundingMode roundingMode =
                (RoundingMode) fields.get("roundingMode", RoundingMode.HALF_EVEN);
        if (convertRoundingMode(roundingMode) != icuDecimalFormat.getRoundingMode()) {
            setRoundingMode(roundingMode);
        }

        int maximumIntegerDigits = fields.get("maximumIntegerDigits", 309);
        if (maximumIntegerDigits != icuDecimalFormat.getMaximumIntegerDigits()) {
            icuDecimalFormat.setMaximumIntegerDigits(maximumIntegerDigits);
        }

        int minimumIntegerDigits = fields.get("minimumIntegerDigits", 309);
        if (minimumIntegerDigits != icuDecimalFormat.getMinimumIntegerDigits()) {
            icuDecimalFormat.setMinimumIntegerDigits(minimumIntegerDigits);
        }

        int maximumFractionDigits = fields.get("maximumFractionDigits", 340);
        if (maximumFractionDigits != icuDecimalFormat.getMaximumFractionDigits()) {
            icuDecimalFormat.setMaximumFractionDigits(maximumFractionDigits);
        }

        int minimumFractionDigits = fields.get("minimumFractionDigits", 340);
        if (minimumFractionDigits != icuDecimalFormat.getMinimumFractionDigits()) {
            icuDecimalFormat.setMinimumFractionDigits(minimumFractionDigits);
        }

        boolean parseBigDecimal = fields.get("parseBigDecimal", true);
        if (parseBigDecimal != icuDecimalFormat.isParseBigDecimal()) {
            icuDecimalFormat.setParseBigDecimal(parseBigDecimal);
        }

        updateFieldsFromIcu();

        if (fields.get("serialVersionOnStream", 0) < 3) {
            setMaximumIntegerDigits(super.getMaximumIntegerDigits());
            setMinimumIntegerDigits(super.getMinimumIntegerDigits());
            setMaximumFractionDigits(super.getMaximumFractionDigits());
            setMinimumFractionDigits(super.getMinimumFractionDigits());
        }
    }
    // END Android-added: Custom serialization code for compatibility with RI serialization.

    //----------------------------------------------------------------------
    // INSTANCE VARIABLES
    //----------------------------------------------------------------------

    // Android-removed: various fields now stored in icuDecimalFormat.

    /**
     * The <code>DecimalFormatSymbols</code> object used by this format.
     * It contains the symbols used to format numbers, e.g. the grouping separator,
     * decimal separator, and so on.
     *
     * @serial
     * @see #setDecimalFormatSymbols
     * @see java.text.DecimalFormatSymbols
     */
    private DecimalFormatSymbols symbols = null; // LIU new DecimalFormatSymbols();

    // Android-removed: useExponentialNotation, *FieldPositions, minExponentDigits.

    /**
     * The maximum number of digits allowed in the integer portion of a
     * <code>BigInteger</code> or <code>BigDecimal</code> number.
     * <code>maximumIntegerDigits</code> must be greater than or equal to
     * <code>minimumIntegerDigits</code>.
     *
     * @serial
     * @see #getMaximumIntegerDigits
     * @since 1.5
     */
    // Android-changed: removed initialisation.
    private int    maximumIntegerDigits /* = super.getMaximumIntegerDigits() */;

    /**
     * The minimum number of digits allowed in the integer portion of a
     * <code>BigInteger</code> or <code>BigDecimal</code> number.
     * <code>minimumIntegerDigits</code> must be less than or equal to
     * <code>maximumIntegerDigits</code>.
     *
     * @serial
     * @see #getMinimumIntegerDigits
     * @since 1.5
     */
    // Android-changed: removed initialisation.
    private int    minimumIntegerDigits /* = super.getMinimumIntegerDigits() */;

    /**
     * The maximum number of digits allowed in the fractional portion of a
     * <code>BigInteger</code> or <code>BigDecimal</code> number.
     * <code>maximumFractionDigits</code> must be greater than or equal to
     * <code>minimumFractionDigits</code>.
     *
     * @serial
     * @see #getMaximumFractionDigits
     * @since 1.5
     */
    // Android-changed: removed initialisation.
    private int    maximumFractionDigits /* = super.getMaximumFractionDigits() */;

    /**
     * The minimum number of digits allowed in the fractional portion of a
     * <code>BigInteger</code> or <code>BigDecimal</code> number.
     * <code>minimumFractionDigits</code> must be less than or equal to
     * <code>maximumFractionDigits</code>.
     *
     * @serial
     * @see #getMinimumFractionDigits
     * @since 1.5
     */
    // Android-changed: removed initialisation.
    private int    minimumFractionDigits /* = super.getMinimumFractionDigits() */;

    /**
     * The {@link java.math.RoundingMode} used in this DecimalFormat.
     *
     * @serial
     * @since 1.6
     */
    private RoundingMode roundingMode = RoundingMode.HALF_EVEN;

    // Android-removed: FastPathData, isFastPath, fastPathCheckNeeded and fastPathData.

    //----------------------------------------------------------------------

    static final int currentSerialVersion = 4;

    // Android-removed: serialVersionOnStream.

    //----------------------------------------------------------------------
    // CONSTANTS
    //----------------------------------------------------------------------

    // Android-removed: Fast-Path for double Constants, various constants.

    // Upper limit on integer and fraction digits for a Java double
    static final int DOUBLE_INTEGER_DIGITS  = 309;
    static final int DOUBLE_FRACTION_DIGITS = 340;

    // Upper limit on integer and fraction digits for BigDecimal and BigInteger
    static final int MAXIMUM_INTEGER_DIGITS  = Integer.MAX_VALUE;
    static final int MAXIMUM_FRACTION_DIGITS = Integer.MAX_VALUE;

    // Proclaim JDK 1.1 serial compatibility.
    static final long serialVersionUID = 864413376551465018L;
}
