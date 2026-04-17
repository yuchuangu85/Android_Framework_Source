/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.internal.telephony.cat;

import java.util.Iterator;

/**
 * Iterates each ComprehensionTlv sequentially and run the given parse function if the expecting tag
 * is found.
 */
public class SequentialParser {
    private final Iterator<ComprehensionTlv> mIter;
    private ComprehensionTlv mCurrentCtlv;

    /**
     * An operation that accepts a C-TLV object and returns a parsed value.
     *
     * @param <T> Type of the parsed value.
     */
    @FunctionalInterface
    public interface ParseFunction<T> {
        /**
         * Applies this function to the given argument.
         *
         * @param ctlv C-TLV object to be parsed.
         * @return The retrieved value from the C-TLV object.
         * @throws ResultException If an error occurs during the parsing.
         */
        T apply(ComprehensionTlv ctlv) throws ResultException;
    }

    public SequentialParser(Iterator<ComprehensionTlv> iter) {
        mIter = iter;
    }

    /**
     * Run the given ParseFunction for the next ComprehensionTlv element and returns the result.
     *
     * @param tag An expected tag of the next Comprehension-TLV object.
     * @param f A function for parsing the matching Comprehension-TLV object.
     *          Can be null if the return value won't be used.
     * @param consume Whether to consume the element or not when it's matching.
     * @return Result of f.
     * @throws ResultException If there's no matching element or a parsing error occurs.
     */
    public <T> T parseMandatory(ComprehensionTlvTag tag, ParseFunction<T> f, boolean consume)
            throws ResultException {
        ComprehensionTlv ctlv = getCurrentCtlv();
        if (ctlv == null) {
            throw new ResultException(
                    ResultCode.CMD_DATA_NOT_UNDERSTOOD,
                    tag.toString() + " tag not found: End of TLV stream");
        }
        if (ctlv.getTag() != tag.value()) {
            throw new ResultException(
                    ResultCode.CMD_DATA_NOT_UNDERSTOOD,
                    tag.toString() + " tag not found: Found " + ctlv.getTag() + " instead");
        }

        if (consume) {
            consumeCurrentCtlv();
        }
        return f != null ? f.apply(ctlv) : null;
    }

    /**
     * Run the given ParseFunction for the next ComprehensionTlv element and returns the result.
     *
     * @param tag An expected tag of the next Comprehension-TLV object.
     * @param f A function for parsing the matching Comprehension-TLV object.
     *          Can be null if the return value won't be used.
     * @return Result of f.
     * @throws ResultException If there's no matching element or a parsing error occurs.
     */
    public <T> T parseMandatory(ComprehensionTlvTag tag, ParseFunction<T> f)
            throws ResultException {
        return parseMandatory(tag, f, true);
    }

    /**
     * Run the given ParseFunction for the next ComprehensionTlv element and returns the result.
     * The element won't be consumed if the given tag is not matching.
     *
     * @param tag An expected tag of the next Comprehension-TLV object.
     * @param f A function for parsing the matching Comprehension-TLV object.
     *          Can be null if the return value won't be used.
     * @param defaultValue Return value when the next tag doesn't match.
     * @param consume Whether to consume the element or not when it's matching.
     * @return Result of f. defaultValue if the tag is not matching to the next element.
     * @throws ResultException If a parsing error occurs.
     */
    public <T> T parseOptional(
            ComprehensionTlvTag tag, ParseFunction<T> f, T defaultValue, boolean consume)
            throws ResultException {
        ComprehensionTlv ctlv = getCurrentCtlv();
        if (ctlv == null || ctlv.getTag() != tag.value()) {
            return defaultValue;
        }

        if (consume) {
            consumeCurrentCtlv();
        }
        return f != null ? f.apply(ctlv) : null;
    }

    /**
     * Run the given ParseFunction for the next ComprehensionTlv element and returns the result.
     * The element won't be consumed if the given tag is not matching.
     *
     * @param tag An expected tag of the next Comprehension-TLV object.
     * @param f A function for parsing the matching Comprehension-TLV object.
     *          Can be null if the return value won't be used.
     * @param defaultValue Return value when the next tag doesn't match.
     * @return Result of f. defaultValue if the tag is not matching to the next element.
     * @throws ResultException If a parsing error occurs.
     */
    public <T> T parseOptional(ComprehensionTlvTag tag, ParseFunction<T> f, T defaultValue)
            throws ResultException {
        return parseOptional(tag, f, defaultValue, true);
    }

    private void consumeCurrentCtlv() {
        mCurrentCtlv = null;
    }

    private ComprehensionTlv getCurrentCtlv() {
        if (mCurrentCtlv == null) {
            if (mIter.hasNext()) {
                mCurrentCtlv = mIter.next();
            }
        }
        return mCurrentCtlv;
    }
}
