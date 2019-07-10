/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package java.util.regex;

/**
 * Holds the results of a successful match of a {@link Pattern} against a
 * given string. Typically this is an instance of {@link Matcher}, but
 * since that's a mutable class it's also possible to freeze its current
 * state using {@link Matcher#toMatchResult}.
 */
public interface MatchResult {

    /**
     * Returns the index of the first character following the text that matched
     * the whole regular expression.
     */
    int end();

    /**
     * Returns the index of the first character following the text that matched
     * a given group. See {@link #group} for an explanation of group indexes.
     */
    int end(int group);

    /**
     * Returns the text that matched the whole regular expression.
     */
    String group();

    /**
     * Returns the text that matched a given group of the regular expression.
     *
     * <p>Explicit capturing groups in the pattern are numbered left to right in order
     * of their <i>opening</i> parenthesis, starting at 1.
     * The special group 0 represents the entire match (as if the entire pattern is surrounded
     * by an implicit capturing group).
     * For example, "a((b)c)" matching "abc" would give the following groups:
     * <pre>
     * 0 "abc"
     * 1 "bc"
     * 2 "b"
     * </pre>
     *
     * <p>An optional capturing group that failed to match as part of an overall
     * successful match (for example, "a(b)?c" matching "ac") returns null.
     * A capturing group that matched the empty string (for example, "a(b?)c" matching "ac")
     * returns the empty string.
     */
    String group(int group);

    /**
     * Returns the number of groups in the results, which is always equal to
     * the number of groups in the original regular expression.
     */
    int groupCount();

    /**
     * Returns the index of the first character of the text that matched the
     * whole regular expression.
     */
    int start();

    /**
     * Returns the index of the first character of the text that matched a given
     * group. See {@link #group} for an explanation of group indexes.
     */
    int start(int group);
}
