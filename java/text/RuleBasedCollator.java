/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package java.text;

import libcore.icu.RuleBasedCollatorICU;

/**
 * A concrete subclass of {@link Collator}.
 * It is based on the ICU RuleBasedCollator which implements the
 * CLDR and Unicode collation algorithms.
 *
 * <p>Most of the time, you create a {@link Collator} instance for a {@link java.util.Locale}
 * by calling the {@link Collator#getInstance} factory method.
 * You can construct a {@code RuleBasedCollator} if you need a custom sort order.
 *
 * <p>The root collator's sort order is the CLDR root collation order
 * which in turn is the Unicode default sort order with a few modifications.
 * A {@code RuleBasedCollator} is built from a rule {@code String} which changes the
 * sort order of some characters and strings relative to the default order.
 *
 * <p>A rule string usually contains one or more rule chains.
 * A rule chain consists of a reset followed by one or more rules.
 * The reset anchors the following rules in the default sort order.
 * The rules change the order of the their characters and strings
 * relative to the reset point.
 *
 * <p>A reset is an ampersand {@code &amp;} followed by one or more characters for the reset position.
 * A rule is a relation operator, which specifies the level of difference,
 * also followed by one or more characters.
 * A multi-character rule creates a "contraction".
 * A multi-character reset position usually creates "expansions".
 *
 * <p>For example, the following rules
 * make "ä" sort with a diacritic-like (secondary) difference from "ae"
 * (like in German phonebook sorting),
 * and make "å" and "aa" sort as a base letter (primary) after "z" (like in Danish).
 * Uppercase forms sort with a case-like (tertiary) difference after their lowercase forms.
 *
 * <blockquote>
 * <pre>
 * &amp;AE&lt;&lt;ä &lt;&lt;&lt;Ä
 * &amp;z&lt;å&lt;&lt;&lt;Å&lt;&lt;&lt;aa&lt;&lt;&lt;Aa&lt;&lt;&lt;AA
 * </pre>
 * </blockquote>
 *
 * <p>For details see
 * <ul>
 *   <li>CLDR <a href="http://www.unicode.org/reports/tr35/tr35-collation.html#Rules">Collation Rule Syntax</a>
 *   <li>ICU User Guide <a href="http://userguide.icu-project.org/collation/customization">Collation Customization</a>
 * </ul>
 *
 * <p>Note: earlier versions of {@code RuleBasedCollator} up to and including Android 4.4 (KitKat)
 * allowed the omission of the reset from the first rule chain.
 * This was interpreted as an implied reset after the last non-Han script in the default order.
 * However, this is not a useful reset position, except for large tailorings of
 * Han characters themselves.
 * Starting with the CLDR 24 collation specification and the ICU 53 implementation,
 * the initial reset is required.
 *
 * <p>If the rule string does not follow the syntax, then {@code RuleBasedCollator} throws a
 * {@code ParseException}.
 */
public class RuleBasedCollator extends Collator {
    RuleBasedCollator(RuleBasedCollatorICU wrapper) {
        super(wrapper);
    }

    /**
     * Constructs a new instance of {@code RuleBasedCollator} using the
     * specified {@code rules}. (See the {@link RuleBasedCollator class description}.)
     * <p>
     * Note that the {@code rules} are interpreted as a delta to the
     * default sort order. This differs
     * from other implementations which work with full {@code rules}
     * specifications and may result in different behavior.
     *
     * @param rules
     *            the collation rules.
     * @throws NullPointerException
     *             if {@code rules == null}.
     * @throws ParseException
     *             if {@code rules} contains rules with invalid collation rule
     *             syntax.
     */
    public RuleBasedCollator(String rules) throws ParseException {
        if (rules == null) {
            throw new NullPointerException("rules == null");
        }
        try {
            icuColl = new RuleBasedCollatorICU(rules);
        } catch (Exception e) {
            if (e instanceof ParseException) {
                throw (ParseException) e;
            }
            /*
             * -1 means it's not a ParseException. Maybe IOException thrown when
             * an error occurred while reading internal data.
             */
            throw new ParseException(e.getMessage(), -1);
        }
    }

    /**
     * Obtains a {@code CollationElementIterator} for the given
     * {@code CharacterIterator}. The source iterator's integrity will be
     * preserved since a new copy will be created for use.
     *
     * @param source
     *            the source character iterator.
     * @return a {@code CollationElementIterator} for {@code source}.
     */
    public CollationElementIterator getCollationElementIterator(CharacterIterator source) {
        if (source == null) {
            throw new NullPointerException("source == null");
        }
        return new CollationElementIterator(icuColl.getCollationElementIterator(source));
    }

    /**
     * Obtains a {@code CollationElementIterator} for the given string.
     *
     * @param source
     *            the source string.
     * @return the {@code CollationElementIterator} for {@code source}.
     */
    public CollationElementIterator getCollationElementIterator(String source) {
        if (source == null) {
            throw new NullPointerException("source == null");
        }
        return new CollationElementIterator(icuColl.getCollationElementIterator(source));
    }

    /**
     * Returns the collation rules of this collator. These {@code rules} can be
     * fed into the {@code RuleBasedCollator(String)} constructor.
     *
     * <p>The returned string will be empty unless you constructed the instance yourself.
     * The string forms of the collation rules are omitted to save space on the device.
     */
    public String getRules() {
        return icuColl.getRules();
    }

    /**
     * Returns a new collator with the same collation rules, decomposition mode and
     * strength value as this collator.
     *
     * @return a shallow copy of this collator.
     * @see java.lang.Cloneable
     */
    @Override
    public Object clone() {
        RuleBasedCollator clone = (RuleBasedCollator) super.clone();
        return clone;
    }

    /**
     * Compares the {@code source} text to the {@code target} text according to
     * the collation rules, strength and decomposition mode for this
     * {@code RuleBasedCollator}. See the {@code Collator} class description
     * for an example of use.
     *
     * @param source
     *            the source text.
     * @param target
     *            the target text.
     * @return an integer which may be a negative value, zero, or else a
     *         positive value depending on whether {@code source} is less than,
     *         equivalent to, or greater than {@code target}.
     */
    @Override
    public int compare(String source, String target) {
        if (source == null) {
            throw new NullPointerException("source == null");
        } else if (target == null) {
            throw new NullPointerException("target == null");
        }
        return icuColl.compare(source, target);
    }

    /**
     * Returns the {@code CollationKey} for the given source text.
     *
     * @param source
     *            the specified source text.
     * @return the {@code CollationKey} for the given source text.
     */
    @Override
    public CollationKey getCollationKey(String source) {
        return icuColl.getCollationKey(source);
    }

    @Override
    public int hashCode() {
        return icuColl.getRules().hashCode();
    }

    /**
     * Compares the specified object with this {@code RuleBasedCollator} and
     * indicates if they are equal. In order to be equal, {@code object} must be
     * an instance of {@code Collator} with the same collation rules and the
     * same attributes.
     *
     * @param obj
     *            the object to compare with this object.
     * @return {@code true} if the specified object is equal to this
     *         {@code RuleBasedCollator}; {@code false} otherwise.
     * @see #hashCode
     */
    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof Collator)) {
            return false;
        }
        return super.equals(obj);
    }
}
