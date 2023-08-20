/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.modules.utils;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.text.TextUtils;
import android.util.Log;

import libcore.util.HexEncoding;

import java.util.ArrayList;
import java.util.function.Predicate;

/**
 * Predicate that tests if a given {@code byte[]} value matches a set of
 * configured rules.
 * <p>
 * Rules are tested in the order in which they were originally added, which
 * means a narrow rule can reject a specific value before a later broader rule
 * might accept that same value, or vice versa.
 * <p>
 * Matchers can contain rules of varying lengths, and tested values will only be
 * matched against rules of the exact same length.
 *
 * @hide
 */
public class BytesMatcher implements Predicate<byte[]> {
    private static final String TAG = "BytesMatcher";

    private static final char TYPE_EXACT_ACCEPT = '+';
    private static final char TYPE_EXACT_REJECT = '-';
    private static final char TYPE_PREFIX_ACCEPT = '⊆';
    private static final char TYPE_PREFIX_REJECT = '⊈';

    private final ArrayList<Rule> mRules = new ArrayList<>();

    private static class Rule {
        public final char type;
        public final @NonNull byte[] value;
        public final @Nullable byte[] mask;

        public Rule(char type, @NonNull byte[] value, @Nullable byte[] mask) {
            if (mask != null && value.length != mask.length) {
                throw new IllegalArgumentException(
                        "Expected length " + value.length + " but found " + mask.length);
            }
            this.type = type;
            this.value = value;
            this.mask = mask;
        }

        @Override
        public String toString() {
            StringBuilder builder = new StringBuilder();
            encode(builder);
            return builder.toString();
        }

        public void encode(@NonNull StringBuilder builder) {
            builder.append(this.type);
            builder.append(HexEncoding.encodeToString(this.value));
            if (this.mask != null) {
                builder.append('/');
                builder.append(HexEncoding.encodeToString(this.mask));
            }
        }

        public boolean test(@NonNull byte[] value) {
            switch (type) {
                case TYPE_EXACT_ACCEPT:
                case TYPE_EXACT_REJECT:
                    if (value.length != this.value.length) {
                        return false;
                    }
                    break;
                case TYPE_PREFIX_ACCEPT:
                case TYPE_PREFIX_REJECT:
                    if (value.length < this.value.length) {
                        return false;
                    }
                    break;
            }
            for (int i = 0; i < this.value.length; i++) {
                byte local = this.value[i];
                byte remote = value[i];
                if (this.mask != null) {
                    local &= this.mask[i];
                    remote &= this.mask[i];
                }
                if (local != remote) {
                    return false;
                }
            }
            return true;
        }
    }

    /**
     * Add a rule that will result in {@link #test(byte[])} returning
     * {@code true} when a value being tested matches it. This rule will only
     * match values of the exact same length.
     * <p>
     * Rules are tested in the order in which they were originally added, which
     * means a narrow rule can reject a specific value before a later broader
     * rule might accept that same value, or vice versa.
     *
     * @param value to be matched
     * @param mask to be applied to both values before testing for equality; if
     *            {@code null} then both values must match exactly
     */
    public void addExactAcceptRule(@NonNull byte[] value, @Nullable byte[] mask) {
        mRules.add(new Rule(TYPE_EXACT_ACCEPT, value, mask));
    }

    /**
     * Add a rule that will result in {@link #test(byte[])} returning
     * {@code false} when a value being tested matches it. This rule will only
     * match values of the exact same length.
     * <p>
     * Rules are tested in the order in which they were originally added, which
     * means a narrow rule can reject a specific value before a later broader
     * rule might accept that same value, or vice versa.
     *
     * @param value to be matched
     * @param mask to be applied to both values before testing for equality; if
     *            {@code null} then both values must match exactly
     */
    public void addExactRejectRule(@NonNull byte[] value, @Nullable byte[] mask) {
        mRules.add(new Rule(TYPE_EXACT_REJECT, value, mask));
    }

    /**
     * Add a rule that will result in {@link #test(byte[])} returning
     * {@code true} when a value being tested matches it. This rule will match
     * values of the exact same length or longer.
     * <p>
     * Rules are tested in the order in which they were originally added, which
     * means a narrow rule can reject a specific value before a later broader
     * rule might accept that same value, or vice versa.
     *
     * @param value to be matched
     * @param mask to be applied to both values before testing for equality; if
     *            {@code null} then both values must match exactly
     */
    public void addPrefixAcceptRule(@NonNull byte[] value, @Nullable byte[] mask) {
        mRules.add(new Rule(TYPE_PREFIX_ACCEPT, value, mask));
    }

    /**
     * Add a rule that will result in {@link #test(byte[])} returning
     * {@code false} when a value being tested matches it. This rule will match
     * values of the exact same length or longer.
     * <p>
     * Rules are tested in the order in which they were originally added, which
     * means a narrow rule can reject a specific value before a later broader
     * rule might accept that same value, or vice versa.
     *
     * @param value to be matched
     * @param mask to be applied to both values before testing for equality; if
     *            {@code null} then both values must match exactly
     */
    public void addPrefixRejectRule(@NonNull byte[] value, @Nullable byte[] mask) {
        mRules.add(new Rule(TYPE_PREFIX_REJECT, value, mask));
    }

    /**
     * Test if the given {@code byte[]} value matches the set of rules
     * configured in this matcher.
     */
    @Override
    public boolean test(@NonNull byte[] value) {
        return test(value, false);
    }

    /**
     * Test if the given {@code byte[]} value matches the set of rules
     * configured in this matcher.
     */
    public boolean test(@NonNull byte[] value, boolean defaultValue) {
        final int size = mRules.size();
        for (int i = 0; i < size; i++) {
            final Rule rule = mRules.get(i);
            if (rule.test(value)) {
                switch (rule.type) {
                    case TYPE_EXACT_ACCEPT:
                    case TYPE_PREFIX_ACCEPT:
                        return true;
                    case TYPE_EXACT_REJECT:
                    case TYPE_PREFIX_REJECT:
                        return false;
                }
            }
        }
        return defaultValue;
    }

    /**
     * Encode the given matcher into a human-readable {@link String} which can
     * be used to transport matchers across device boundaries.
     * <p>
     * The human-readable format is an ordered list separated by commas, where
     * each rule is a {@code +} or {@code -} symbol indicating if the match
     * should be accepted or rejected, then followed by a hex value and an
     * optional hex mask. For example, {@code -caff,+cafe/ff00} is a valid
     * encoded matcher.
     *
     * @see #decode(String)
     */
    public static @NonNull String encode(@NonNull BytesMatcher matcher) {
        final StringBuilder builder = new StringBuilder();
        final int size = matcher.mRules.size();
        for (int i = 0; i < size; i++) {
            final Rule rule = matcher.mRules.get(i);
            rule.encode(builder);
            builder.append(',');
        }
        if (builder.length() > 0) {
            builder.deleteCharAt(builder.length() - 1);
        }
        return builder.toString();
    }

    /**
     * Decode the given human-readable {@link String} used to transport matchers
     * across device boundaries.
     * <p>
     * The human-readable format is an ordered list separated by commas, where
     * each rule is a {@code +} or {@code -} symbol indicating if the match
     * should be accepted or rejected, then followed by a hex value and an
     * optional hex mask. For example, {@code -caff,+cafe/ff00} is a valid
     * encoded matcher.
     *
     * @see #encode(BytesMatcher)
     */
    public static @NonNull BytesMatcher decode(@Nullable String value) {
        final BytesMatcher matcher = new BytesMatcher();
        if (TextUtils.isEmpty(value)) return matcher;

        final int length = value.length();
        for (int i = 0; i < length;) {
            final char type = value.charAt(i);

            int nextRule = value.indexOf(',', i);
            int nextMask = value.indexOf('/', i);

            if (nextRule == -1) nextRule = length;
            if (nextMask > nextRule) nextMask = -1;

            final byte[] ruleValue;
            final byte[] ruleMask;
            if (nextMask >= 0) {
                ruleValue = HexEncoding.decode(value.substring(i + 1, nextMask));
                ruleMask = HexEncoding.decode(value.substring(nextMask + 1, nextRule));
            } else {
                ruleValue = HexEncoding.decode(value.substring(i + 1, nextRule));
                ruleMask = null;
            }

            switch (type) {
                case TYPE_EXACT_ACCEPT:
                    matcher.addExactAcceptRule(ruleValue, ruleMask);
                    break;
                case TYPE_EXACT_REJECT:
                    matcher.addExactRejectRule(ruleValue, ruleMask);
                    break;
                case TYPE_PREFIX_ACCEPT:
                    matcher.addPrefixAcceptRule(ruleValue, ruleMask);
                    break;
                case TYPE_PREFIX_REJECT:
                    matcher.addPrefixRejectRule(ruleValue, ruleMask);
                    break;
                default:
                    Log.w(TAG, "Ignoring unknown type " + type);
                    break;
            }

            i = nextRule + 1;
        }
        return matcher;
    }
}
