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

package java.lang;

import java.io.Serializable;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.Charsets;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Formatter;
import java.util.Locale;
import java.util.regex.Pattern;
import libcore.util.EmptyArray;

/**
 * An immutable sequence of UTF-16 {@code char}s.
 * See {@link Character} for details about the relationship between {@code char} and
 * Unicode code points.
 *
 * <a name="backing_array"><h3>Backing Arrays</h3></a>
 * This class is implemented using a {@code char[]}. The length of the array may exceed
 * the length of the string. For example, the string "Hello" may be backed by
 * the array {@code ['H', 'e', 'l', 'l', 'o', 'W'. 'o', 'r', 'l', 'd']} with
 * offset 0 and length 5.
 *
 * <p>Multiple strings can share the same {@code char[]} because strings are immutable.
 * The {@link #substring} method <strong>always</strong> returns a string that
 * shares the backing array of its source string. Generally this is an
 * optimization: fewer {@code char[]}s need to be allocated, and less copying
 * is necessary. But this can also lead to unwanted heap retention. Taking a
 * short substring of long string means that the long shared {@code char[]} won't be
 * garbage until both strings are garbage. This typically happens when parsing
 * small substrings out of a large input. To avoid this where necessary, call
 * {@code new String(longString.subString(...))}. The string copy constructor
 * always ensures that the backing array is no larger than necessary.
 *
 * @see StringBuffer
 * @see StringBuilder
 * @see Charset
 * @since 1.0
 */
public final class String implements Serializable, Comparable<String>, CharSequence {

    private static final long serialVersionUID = -6849794470754667710L;

    private static final char REPLACEMENT_CHAR = (char) 0xfffd;

    private static final class CaseInsensitiveComparator implements
            Comparator<String>, Serializable {
        private static final long serialVersionUID = 8575799808933029326L;

        /**
         * See {@link java.lang.String#compareToIgnoreCase}.
         *
         * @exception ClassCastException
         *                if objects are not the correct type
         */
        public int compare(String o1, String o2) {
            return o1.compareToIgnoreCase(o2);
        }
    }

    /**
     * Compares strings using {@link #compareToIgnoreCase}.
     * This is not suitable for case-insensitive string comparison for all locales.
     * Use a {@link java.text.Collator} instead.
     */
    public static final Comparator<String> CASE_INSENSITIVE_ORDER = new CaseInsensitiveComparator();

    private static final char[] ASCII;
    static {
        ASCII = new char[128];
        for (int i = 0; i < ASCII.length; ++i) {
            ASCII[i] = (char) i;
        }
    }

    private final char[] value;

    private final int offset;

    private final int count;

    private int hashCode;

    /**
     * Creates an empty string.
     */
    public String() {
        value = EmptyArray.CHAR;
        offset = 0;
        count = 0;
    }

    /**
     * Converts the byte array to a string using the system's
     * {@link java.nio.charset.Charset#defaultCharset default charset}.
     */
    @FindBugsSuppressWarnings("DM_DEFAULT_ENCODING")
    public String(byte[] data) {
        this(data, 0, data.length);
    }

    /**
     * Converts the byte array to a string, setting the high byte of every
     * {@code char} to the specified value.
     *
     * @param data
     *            the byte array to convert to a string.
     * @param high
     *            the high byte to use.
     * @throws NullPointerException
     *             if {@code data == null}.
     * @deprecated Use {@link #String(byte[])} or {@link #String(byte[], String)} instead.
     */
    @Deprecated
    public String(byte[] data, int high) {
        this(data, high, 0, data.length);
    }

    /**
     * Converts a subsequence of the byte array to a string using the system's
     * {@link java.nio.charset.Charset#defaultCharset default charset}.
     *
     * @throws NullPointerException
     *             if {@code data == null}.
     * @throws IndexOutOfBoundsException
     *             if {@code byteCount < 0 || offset < 0 || offset + byteCount > data.length}.
     */
    public String(byte[] data, int offset, int byteCount) {
        this(data, offset, byteCount, Charset.defaultCharset());
    }

    /**
     * Converts the byte array to a string, setting the high byte of every
     * {@code char} to {@code high}.
     *
     * @throws NullPointerException
     *             if {@code data == null}.
     * @throws IndexOutOfBoundsException
     *             if {@code byteCount < 0 || offset < 0 || offset + byteCount > data.length}
     *
     * @deprecated Use {@link #String(byte[], int, int)} instead.
     */
    @Deprecated
    public String(byte[] data, int high, int offset, int byteCount) {
        if ((offset | byteCount) < 0 || byteCount > data.length - offset) {
            throw failedBoundsCheck(data.length, offset, byteCount);
        }
        this.offset = 0;
        this.value = new char[byteCount];
        this.count = byteCount;
        high <<= 8;
        for (int i = 0; i < count; i++) {
            value[i] = (char) (high + (data[offset++] & 0xff));
        }
    }

    /**
     * Converts the byte array to a string using the named charset.
     *
     * <p>The behavior when the bytes cannot be decoded by the named charset
     * is unspecified. Use {@link java.nio.charset.CharsetDecoder} for more control.
     *
     * @throws NullPointerException
     *             if {@code data == null}.
     * @throws IndexOutOfBoundsException
     *             if {@code byteCount < 0 || offset < 0 || offset + byteCount > data.length}.
     * @throws UnsupportedEncodingException
     *             if the named charset is not supported.
     */
    public String(byte[] data, int offset, int byteCount, String charsetName) throws UnsupportedEncodingException {
        this(data, offset, byteCount, Charset.forNameUEE(charsetName));
    }

    /**
     * Converts the byte array to a string using the named charset.
     *
     * <p>The behavior when the bytes cannot be decoded by the named charset
     * is unspecified. Use {@link java.nio.charset.CharsetDecoder} for more control.
     *
     * @throws NullPointerException
     *             if {@code data == null}.
     * @throws UnsupportedEncodingException
     *             if {@code charsetName} is not supported.
     */
    public String(byte[] data, String charsetName) throws UnsupportedEncodingException {
        this(data, 0, data.length, Charset.forNameUEE(charsetName));
    }

    /**
     * Converts the byte array to a string using the given charset.
     *
     * <p>The behavior when the bytes cannot be decoded by the given charset
     * is to replace malformed input and unmappable code points with the charset's default
     * replacement string. Use {@link java.nio.charset.CharsetDecoder} for more control.
     *
     * @throws IndexOutOfBoundsException
     *             if {@code byteCount < 0 || offset < 0 || offset + byteCount > data.length}
     * @throws NullPointerException
     *             if {@code data == null}
     *
     * @since 1.6
     */
    public String(byte[] data, int offset, int byteCount, Charset charset) {
        if ((offset | byteCount) < 0 || byteCount > data.length - offset) {
            throw failedBoundsCheck(data.length, offset, byteCount);
        }

        // We inline UTF-8, ISO-8859-1, and US-ASCII decoders for speed and because 'count' and
        // 'value' are final.
        String canonicalCharsetName = charset.name();
        if (canonicalCharsetName.equals("UTF-8")) {
            byte[] d = data;
            char[] v = new char[byteCount];

            int idx = offset;
            int last = offset + byteCount;
            int s = 0;
outer:
            while (idx < last) {
                byte b0 = d[idx++];
                if ((b0 & 0x80) == 0) {
                    // 0xxxxxxx
                    // Range:  U-00000000 - U-0000007F
                    int val = b0 & 0xff;
                    v[s++] = (char) val;
                } else if (((b0 & 0xe0) == 0xc0) || ((b0 & 0xf0) == 0xe0) ||
                        ((b0 & 0xf8) == 0xf0) || ((b0 & 0xfc) == 0xf8) || ((b0 & 0xfe) == 0xfc)) {
                    int utfCount = 1;
                    if ((b0 & 0xf0) == 0xe0) utfCount = 2;
                    else if ((b0 & 0xf8) == 0xf0) utfCount = 3;
                    else if ((b0 & 0xfc) == 0xf8) utfCount = 4;
                    else if ((b0 & 0xfe) == 0xfc) utfCount = 5;

                    // 110xxxxx (10xxxxxx)+
                    // Range:  U-00000080 - U-000007FF (count == 1)
                    // Range:  U-00000800 - U-0000FFFF (count == 2)
                    // Range:  U-00010000 - U-001FFFFF (count == 3)
                    // Range:  U-00200000 - U-03FFFFFF (count == 4)
                    // Range:  U-04000000 - U-7FFFFFFF (count == 5)

                    if (idx + utfCount > last) {
                        v[s++] = REPLACEMENT_CHAR;
                        continue;
                    }

                    // Extract usable bits from b0
                    int val = b0 & (0x1f >> (utfCount - 1));
                    for (int i = 0; i < utfCount; ++i) {
                        byte b = d[idx++];
                        if ((b & 0xc0) != 0x80) {
                            v[s++] = REPLACEMENT_CHAR;
                            idx--; // Put the input char back
                            continue outer;
                        }
                        // Push new bits in from the right side
                        val <<= 6;
                        val |= b & 0x3f;
                    }

                    // Note: Java allows overlong char
                    // specifications To disallow, check that val
                    // is greater than or equal to the minimum
                    // value for each count:
                    //
                    // count    min value
                    // -----   ----------
                    //   1           0x80
                    //   2          0x800
                    //   3        0x10000
                    //   4       0x200000
                    //   5      0x4000000

                    // Allow surrogate values (0xD800 - 0xDFFF) to
                    // be specified using 3-byte UTF values only
                    if ((utfCount != 2) && (val >= 0xD800) && (val <= 0xDFFF)) {
                        v[s++] = REPLACEMENT_CHAR;
                        continue;
                    }

                    // Reject chars greater than the Unicode maximum of U+10FFFF.
                    if (val > 0x10FFFF) {
                        v[s++] = REPLACEMENT_CHAR;
                        continue;
                    }

                    // Encode chars from U+10000 up as surrogate pairs
                    if (val < 0x10000) {
                        v[s++] = (char) val;
                    } else {
                        int x = val & 0xffff;
                        int u = (val >> 16) & 0x1f;
                        int w = (u - 1) & 0xffff;
                        int hi = 0xd800 | (w << 6) | (x >> 10);
                        int lo = 0xdc00 | (x & 0x3ff);
                        v[s++] = (char) hi;
                        v[s++] = (char) lo;
                    }
                } else {
                    // Illegal values 0x8*, 0x9*, 0xa*, 0xb*, 0xfd-0xff
                    v[s++] = REPLACEMENT_CHAR;
                }
            }

            if (s == byteCount) {
                // We guessed right, so we can use our temporary array as-is.
                this.offset = 0;
                this.value = v;
                this.count = s;
            } else {
                // Our temporary array was too big, so reallocate and copy.
                this.offset = 0;
                this.value = new char[s];
                this.count = s;
                System.arraycopy(v, 0, value, 0, s);
            }
        } else if (canonicalCharsetName.equals("ISO-8859-1")) {
            this.offset = 0;
            this.value = new char[byteCount];
            this.count = byteCount;
            Charsets.isoLatin1BytesToChars(data, offset, byteCount, value);
        } else if (canonicalCharsetName.equals("US-ASCII")) {
            this.offset = 0;
            this.value = new char[byteCount];
            this.count = byteCount;
            Charsets.asciiBytesToChars(data, offset, byteCount, value);
        } else {
            CharBuffer cb = charset.decode(ByteBuffer.wrap(data, offset, byteCount));
            this.offset = 0;
            this.count = cb.length();
            if (count > 0) {
                // We could use cb.array() directly, but that would mean we'd have to trust
                // the CharsetDecoder doesn't hang on to the CharBuffer and mutate it later,
                // which would break String's immutability guarantee. It would also tend to
                // mean that we'd be wasting memory because CharsetDecoder doesn't trim the
                // array. So we copy.
                this.value = new char[count];
                System.arraycopy(cb.array(), 0, value, 0, count);
            } else {
                this.value = EmptyArray.CHAR;
            }
        }
    }

    /**
     * Converts the byte array to a String using the given charset.
     *
     * @throws NullPointerException if {@code data == null}
     * @since 1.6
     */
    public String(byte[] data, Charset charset) {
        this(data, 0, data.length, charset);
    }

    /**
     * Initializes this string to contain the given {@code char}s.
     * Modifying the array after creating the string
     * has no effect on the string.
     *
     * @throws NullPointerException if {@code data == null}
     */
    public String(char[] data) {
        this(data, 0, data.length);
    }

    /**
     * Initializes this string to contain the given {@code char}s.
     * Modifying the array after creating the string
     * has no effect on the string.
     *
     * @throws NullPointerException
     *             if {@code data == null}.
     * @throws IndexOutOfBoundsException
     *             if {@code charCount < 0 || offset < 0 || offset + charCount > data.length}
     */
    public String(char[] data, int offset, int charCount) {
        if ((offset | charCount) < 0 || charCount > data.length - offset) {
            throw failedBoundsCheck(data.length, offset, charCount);
        }
        this.offset = 0;
        this.value = new char[charCount];
        this.count = charCount;
        System.arraycopy(data, offset, value, 0, count);
    }

    /*
     * Internal version of the String(char[], int, int) constructor.
     * Does not range check, null check, or copy the array.
     */
    String(int offset, int charCount, char[] chars) {
        this.value = chars;
        this.offset = offset;
        this.count = charCount;
    }

    /**
     * Constructs a copy of the given string.
     * The returned string's <a href="#backing_array">backing array</a>
     * is no larger than necessary.
     */
    public String(String toCopy) {
        value = (toCopy.value.length == toCopy.count)
                ? toCopy.value
                : Arrays.copyOfRange(toCopy.value, toCopy.offset, toCopy.offset + toCopy.length());
        offset = 0;
        count = value.length;
    }

    /**
     * Creates a {@code String} from the contents of the specified
     * {@code StringBuffer}.
     */
    public String(StringBuffer stringBuffer) {
        offset = 0;
        synchronized (stringBuffer) {
            value = stringBuffer.shareValue();
            count = stringBuffer.length();
        }
    }

    /**
     * Creates a {@code String} from the sub-array of Unicode code points.
     *
     * @throws NullPointerException
     *             if {@code codePoints == null}.
     * @throws IllegalArgumentException
     *             if any of the elements of {@code codePoints} are not valid
     *             Unicode code points.
     * @throws IndexOutOfBoundsException
     *             if {@code offset} or {@code count} are not within the bounds
     *             of {@code codePoints}.
     * @since 1.5
     */
    public String(int[] codePoints, int offset, int count) {
        if (codePoints == null) {
            throw new NullPointerException("codePoints == null");
        }
        if ((offset | count) < 0 || count > codePoints.length - offset) {
            throw failedBoundsCheck(codePoints.length, offset, count);
        }
        this.offset = 0;
        this.value = new char[count * 2];
        int end = offset + count;
        int c = 0;
        for (int i = offset; i < end; i++) {
            c += Character.toChars(codePoints[i], this.value, c);
        }
        this.count = c;
    }

    /**
     * Creates a {@code String} from the contents of the specified {@code
     * StringBuilder}.
     *
     * @throws NullPointerException
     *             if {@code stringBuilder == null}.
     * @since 1.5
     */
    public String(StringBuilder stringBuilder) {
        if (stringBuilder == null) {
            throw new NullPointerException("stringBuilder == null");
        }
        this.offset = 0;
        this.count = stringBuilder.length();
        this.value = new char[this.count];
        stringBuilder.getChars(0, this.count, this.value, 0);
    }

    /**
     * Returns the {@code char} at {@code index}.
     * @throws IndexOutOfBoundsException if {@code index < 0} or {@code index >= length()}.
     */
    public char charAt(int index) {
        if (index < 0 || index >= count) {
            throw indexAndLength(index);
        }
        return value[offset + index];
    }

    private StringIndexOutOfBoundsException indexAndLength(int index) {
        throw new StringIndexOutOfBoundsException(this, index);
    }

    private StringIndexOutOfBoundsException startEndAndLength(int start, int end) {
        throw new StringIndexOutOfBoundsException(this, start, end - start);
    }

    private StringIndexOutOfBoundsException failedBoundsCheck(int arrayLength, int offset, int count) {
        throw new StringIndexOutOfBoundsException(arrayLength, offset, count);
    }

    /**
     * This isn't equivalent to either of ICU's u_foldCase case folds, and thus any of the Unicode
     * case folds, but it's what the RI uses.
     */
    private char foldCase(char ch) {
        if (ch < 128) {
            if ('A' <= ch && ch <= 'Z') {
                return (char) (ch + ('a' - 'A'));
            }
            return ch;
        }
        return Character.toLowerCase(Character.toUpperCase(ch));
    }

    /**
     * Compares this string to the given string.
     *
     * <p>The strings are compared one {@code char} at a time.
     * In the discussion of the return value below, note that {@code char} does not
     * mean code point, though this should only be visible for surrogate pairs.
     *
     * <p>If there is an index at which the two strings differ, the result is
     * the difference between the two {@code char}s at the lowest such index.
     * If not, but the lengths of the strings differ, the result is the difference
     * between the two strings' lengths.
     * If the strings are the same length and every {@code char} is the same, the result is 0.
     *
     * @throws NullPointerException
     *             if {@code string} is {@code null}.
     */
    public native int compareTo(String string);

    /**
     * Compares this string to the given string, ignoring case differences.
     *
     * <p>The strings are compared one {@code char} at a time. This is not suitable
     * for case-insensitive string comparison for all locales.
     * Use a {@link java.text.Collator} instead.
     *
     * <p>If there is an index at which the two strings differ, the result is
     * the difference between the two {@code char}s at the lowest such index.
     * If not, but the lengths of the strings differ, the result is the difference
     * between the two strings' lengths.
     * If the strings are the same length and every {@code char} is the same, the result is 0.
     *
     * @throws NullPointerException
     *             if {@code string} is {@code null}.
     */
    public int compareToIgnoreCase(String string) {
        int o1 = offset, o2 = string.offset, result;
        int end = offset + (count < string.count ? count : string.count);
        char c1, c2;
        char[] target = string.value;
        while (o1 < end) {
            if ((c1 = value[o1++]) == (c2 = target[o2++])) {
                continue;
            }
            c1 = foldCase(c1);
            c2 = foldCase(c2);
            if ((result = c1 - c2) != 0) {
                return result;
            }
        }
        return count - string.count;
    }

    /**
     * Concatenates this string and the specified string.
     *
     * @param string
     *            the string to concatenate
     * @return a new string which is the concatenation of this string and the
     *         specified string.
     */
    public String concat(String string) {
        if (string.count > 0 && count > 0) {
            char[] buffer = new char[count + string.count];
            System.arraycopy(value, offset, buffer, 0, count);
            System.arraycopy(string.value, string.offset, buffer, count, string.count);
            return new String(0, buffer.length, buffer);
        }
        return count == 0 ? string : this;
    }

    /**
     * Creates a new string by copying the given {@code char[]}.
     * Modifying the array after creating the string has no
     * effect on the string.
     *
     * @throws NullPointerException
     *             if {@code data} is {@code null}.
     */
    public static String copyValueOf(char[] data) {
        return new String(data, 0, data.length);
    }

    /**
     * Creates a new string by copying the given subsequence of the given {@code char[]}.
     * Modifying the array after creating the string has no
     * effect on the string.

     * @throws NullPointerException
     *             if {@code data} is {@code null}.
     * @throws IndexOutOfBoundsException
     *             if {@code length < 0, start < 0} or {@code start + length >
     *             data.length}.
     */
    public static String copyValueOf(char[] data, int start, int length) {
        return new String(data, start, length);
    }

    /**
     * Compares the specified string to this string to determine if the
     * specified string is a suffix.
     *
     * @throws NullPointerException
     *             if {@code suffix} is {@code null}.
     */
    public boolean endsWith(String suffix) {
        return regionMatches(count - suffix.count, suffix, 0, suffix.count);
    }

    /**
     * Compares the given object to this string and returns true if they are
     * equal. The object must be an instance of {@code String} with the same length,
     * where for every index, {@code charAt} on each string returns the same value.
     */
    @Override public boolean equals(Object other) {
        if (other == this) {
          return true;
        }
        if (other instanceof String) {
            String s = (String)other;
            int count = this.count;
            if (s.count != count) {
                return false;
            }
            // TODO: we want to avoid many boundchecks in the loop below
            // for long Strings until we have array equality intrinsic.
            // Bad benchmarks just push .equals without first getting a
            // hashCode hit (unlike real world use in a Hashtable). Filter
            // out these long strings here. When we get the array equality
            // intrinsic then remove this use of hashCode.
            if (hashCode() != s.hashCode()) {
                return false;
            }
            char[] value1 = value;
            int offset1 = offset;
            char[] value2 = s.value;
            int offset2 = s.offset;
            for (int end = offset1 + count; offset1 < end; ) {
                if (value1[offset1] != value2[offset2]) {
                    return false;
                }
                offset1++;
                offset2++;
            }
            return true;
        } else {
            return false;
        }
    }

    /**
     * Compares the given string to this string ignoring case.
     *
     * <p>The strings are compared one {@code char} at a time. This is not suitable
     * for case-insensitive string comparison for all locales.
     * Use a {@link java.text.Collator} instead.
     */
    @FindBugsSuppressWarnings("ES_COMPARING_PARAMETER_STRING_WITH_EQ")
    public boolean equalsIgnoreCase(String string) {
        if (string == this) {
            return true;
        }
        if (string == null || count != string.count) {
            return false;
        }
        int o1 = offset, o2 = string.offset;
        int end = offset + count;
        char[] target = string.value;
        while (o1 < end) {
            char c1 = value[o1++];
            char c2 = target[o2++];
            if (c1 != c2 && foldCase(c1) != foldCase(c2)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Mangles a subsequence of this string into a byte array by stripping the high order bits from
     * each {@code char}. Use {@link #getBytes()} or {@link #getBytes(String)} instead.
     *
     * @param start
     *            the start offset in this string.
     * @param end
     *            the end+1 offset in this string.
     * @param data
     *            the destination byte array.
     * @param index
     *            the start offset in the destination byte array.
     * @throws NullPointerException
     *             if {@code data} is {@code null}.
     * @throws IndexOutOfBoundsException
     *             if {@code start < 0}, {@code end > length()}, {@code index <
     *             0} or {@code end - start > data.length - index}.
     * @deprecated Use {@link #getBytes()} or {@link #getBytes(String)}
     */
    @Deprecated
    public void getBytes(int start, int end, byte[] data, int index) {
        if (start >= 0 && start <= end && end <= count) {
            end += offset;
            try {
                for (int i = offset + start; i < end; i++) {
                    data[index++] = (byte) value[i];
                }
            } catch (ArrayIndexOutOfBoundsException ignored) {
                throw failedBoundsCheck(data.length, index, end - start);
            }
        } else {
            throw startEndAndLength(start, end);
        }
    }

    /**
     * Returns a new byte array containing the code points in this string encoded using the
     * system's {@link java.nio.charset.Charset#defaultCharset default charset}.
     *
     * <p>The behavior when this string cannot be represented in the system's default charset
     * is unspecified. In practice, when the default charset is UTF-8 (as it is on Android),
     * all strings can be encoded.
     */
    public byte[] getBytes() {
        return getBytes(Charset.defaultCharset());
    }

    /**
     * Returns a new byte array containing the code points of this string encoded using the
     * named charset.
     *
     * <p>The behavior when this string cannot be represented in the named charset
     * is unspecified. Use {@link java.nio.charset.CharsetEncoder} for more control.
     *
     * @throws UnsupportedEncodingException if the charset is not supported
     */
    public byte[] getBytes(String charsetName) throws UnsupportedEncodingException {
        return getBytes(Charset.forNameUEE(charsetName));
    }

    /**
     * Returns a new byte array containing the code points of this string encoded using the
     * given charset.
     *
     * <p>The behavior when this string cannot be represented in the given charset
     * is to replace malformed input and unmappable code points with the charset's default
     * replacement byte array. Use {@link java.nio.charset.CharsetEncoder} for more control.
     *
     * @since 1.6
     */
    public byte[] getBytes(Charset charset) {
        String canonicalCharsetName = charset.name();
        if (canonicalCharsetName.equals("UTF-8")) {
            return Charsets.toUtf8Bytes(value, offset, count);
        } else if (canonicalCharsetName.equals("ISO-8859-1")) {
            return Charsets.toIsoLatin1Bytes(value, offset, count);
        } else if (canonicalCharsetName.equals("US-ASCII")) {
            return Charsets.toAsciiBytes(value, offset, count);
        } else if (canonicalCharsetName.equals("UTF-16BE")) {
            return Charsets.toBigEndianUtf16Bytes(value, offset, count);
        } else {
            CharBuffer chars = CharBuffer.wrap(this.value, this.offset, this.count);
            ByteBuffer buffer = charset.encode(chars.asReadOnlyBuffer());
            byte[] bytes = new byte[buffer.limit()];
            buffer.get(bytes);
            return bytes;
        }
    }

    /**
     * Copies the given subsequence of this string to the given array
     * starting at the given offset.
     *
     * @param start
     *            the start offset in this string.
     * @param end
     *            the end+1 offset in this string.
     * @param buffer
     *            the destination array.
     * @param index
     *            the start offset in the destination array.
     * @throws NullPointerException
     *             if {@code buffer} is {@code null}.
     * @throws IndexOutOfBoundsException
     *             if {@code start < 0}, {@code end > length()}, {@code start >
     *             end}, {@code index < 0}, {@code end - start > buffer.length -
     *             index}
     */
    public void getChars(int start, int end, char[] buffer, int index) {
        if (start >= 0 && start <= end && end <= count) {
            System.arraycopy(value, start + offset, buffer, index, end - start);
        } else {
            // We throw StringIndexOutOfBoundsException rather than System.arraycopy's AIOOBE.
            throw startEndAndLength(start, end);
        }
    }

    /**
     * getChars without bounds checks, for use by other classes
     * within the java.lang package only.  The caller is responsible for
     * ensuring that start >= 0 && start <= end && end <= count.
     */
    void _getChars(int start, int end, char[] buffer, int index) {
        System.arraycopy(value, start + offset, buffer, index, end - start);
    }

    @Override public int hashCode() {
        int hash = hashCode;
        if (hash == 0) {
            if (count == 0) {
                return 0;
            }
            final int end = count + offset;
            final char[] chars = value;
            for (int i = offset; i < end; ++i) {
                hash = 31*hash + chars[i];
            }
            hashCode = hash;
        }
        return hash;
    }

    /**
     * Returns the first index of the given code point, or -1.
     * The search starts at the beginning and moves towards
     * the end of this string.
     */
    public int indexOf(int c) {
        // TODO: just "return indexOf(c, 0);" when the JIT can inline that deep.
        if (c > 0xffff) {
            return indexOfSupplementary(c, 0);
        }
        return fastIndexOf(c, 0);
    }

    /**
     * Returns the next index of the given code point, or -1. The
     * search starts at the given offset and moves towards
     * the end of this string.
     */
    public int indexOf(int c, int start) {
        if (c > 0xffff) {
            return indexOfSupplementary(c, start);
        }
        return fastIndexOf(c, start);
    }

    private native int fastIndexOf(int c, int start);

    private int indexOfSupplementary(int c, int start) {
        if (!Character.isSupplementaryCodePoint(c)) {
            return -1;
        }
        char[] chars = Character.toChars(c);
        String needle = new String(0, chars.length, chars);
        return indexOf(needle, start);
    }

    /**
     * Returns the first index of the given string, or -1. The
     * search starts at the beginning and moves towards the end
     * of this string.
     *
     * @throws NullPointerException
     *             if {@code string} is {@code null}.
     */
    public int indexOf(String string) {
        int start = 0;
        int subCount = string.count;
        int _count = count;
        if (subCount > 0) {
            if (subCount > _count) {
                return -1;
            }
            char[] target = string.value;
            int subOffset = string.offset;
            char firstChar = target[subOffset];
            int end = subOffset + subCount;
            while (true) {
                int i = indexOf(firstChar, start);
                if (i == -1 || subCount + i > _count) {
                    return -1; // handles subCount > count || start >= count
                }
                int o1 = offset + i, o2 = subOffset;
                char[] _value = value;
                while (++o2 < end && _value[++o1] == target[o2]) {
                    // Intentionally empty
                }
                if (o2 == end) {
                    return i;
                }
                start = i + 1;
            }
        }
        return start < _count ? start : _count;
    }

    /**
     * Returns the next index of the given string in this string, or -1. The search
     * for the string starts at the given offset and moves towards the end
     * of this string.
     *
     * @throws NullPointerException
     *             if {@code subString} is {@code null}.
     */
    public int indexOf(String subString, int start) {
        if (start < 0) {
            start = 0;
        }
        int subCount = subString.count;
        int _count = count;
        if (subCount > 0) {
            if (subCount + start > _count) {
                return -1;
            }
            char[] target = subString.value;
            int subOffset = subString.offset;
            char firstChar = target[subOffset];
            int end = subOffset + subCount;
            while (true) {
                int i = indexOf(firstChar, start);
                if (i == -1 || subCount + i > _count) {
                    return -1; // handles subCount > count || start >= count
                }
                int o1 = offset + i, o2 = subOffset;
                char[] _value = value;
                while (++o2 < end && _value[++o1] == target[o2]) {
                    // Intentionally empty
                }
                if (o2 == end) {
                    return i;
                }
                start = i + 1;
            }
        }
        return start < _count ? start : _count;
    }

    /**
     * Returns an interned string equal to this string. The VM maintains an internal set of
     * unique strings. All string literals found in loaded classes'
     * constant pools are automatically interned. Manually-interned strings are only weakly
     * referenced, so calling {@code intern} won't lead to unwanted retention.
     *
     * <p>Interning is typically used because it guarantees that for interned strings
     * {@code a} and {@code b}, {@code a.equals(b)} can be simplified to
     * {@code a == b}. (This is not true of non-interned strings.)
     *
     * <p>Many applications find it simpler and more convenient to use an explicit
     * {@link java.util.HashMap} to implement their own pools.
     */
    public native String intern();

    /**
     * Returns true if the length of this string is 0.
     *
     * @since 1.6
     */
    public boolean isEmpty() {
        return count == 0;
    }

    /**
     * Returns the last index of the code point {@code c}, or -1.
     * The search starts at the end and moves towards the
     * beginning of this string.
     */
    public int lastIndexOf(int c) {
        if (c > 0xffff) {
            return lastIndexOfSupplementary(c, Integer.MAX_VALUE);
        }
        int _count = count;
        int _offset = offset;
        char[] _value = value;
        for (int i = _offset + _count - 1; i >= _offset; --i) {
            if (_value[i] == c) {
                return i - _offset;
            }
        }
        return -1;
    }

    /**
     * Returns the last index of the code point {@code c}, or -1.
     * The search starts at offset {@code start} and moves towards
     * the beginning of this string.
     */
    public int lastIndexOf(int c, int start) {
        if (c > 0xffff) {
            return lastIndexOfSupplementary(c, start);
        }
        int _count = count;
        int _offset = offset;
        char[] _value = value;
        if (start >= 0) {
            if (start >= _count) {
                start = _count - 1;
            }
            for (int i = _offset + start; i >= _offset; --i) {
                if (_value[i] == c) {
                    return i - _offset;
                }
            }
        }
        return -1;
    }

    private int lastIndexOfSupplementary(int c, int start) {
        if (!Character.isSupplementaryCodePoint(c)) {
            return -1;
        }
        char[] chars = Character.toChars(c);
        String needle = new String(0, chars.length, chars);
        return lastIndexOf(needle, start);
    }

    /**
     * Returns the index of the start of the last match for the given string in this string, or -1.
     * The search for the string starts at the end and moves towards the beginning
     * of this string.
     *
     * @throws NullPointerException
     *             if {@code string} is {@code null}.
     */
    public int lastIndexOf(String string) {
        // Use count instead of count - 1 so lastIndexOf("") returns count
        return lastIndexOf(string, count);
    }

    /**
     * Returns the index of the start of the previous match for the given string in this string,
     * or -1.
     * The search for the string starts at the given index and moves towards the beginning
     * of this string.
     *
     * @throws NullPointerException
     *             if {@code subString} is {@code null}.
     */
    public int lastIndexOf(String subString, int start) {
        int subCount = subString.count;
        if (subCount <= count && start >= 0) {
            if (subCount > 0) {
                if (start > count - subCount) {
                    start = count - subCount;
                }
                // count and subCount are both >= 1
                char[] target = subString.value;
                int subOffset = subString.offset;
                char firstChar = target[subOffset];
                int end = subOffset + subCount;
                while (true) {
                    int i = lastIndexOf(firstChar, start);
                    if (i == -1) {
                        return -1;
                    }
                    int o1 = offset + i, o2 = subOffset;
                    while (++o2 < end && value[++o1] == target[o2]) {
                        // Intentionally empty
                    }
                    if (o2 == end) {
                        return i;
                    }
                    start = i - 1;
                }
            }
            return start < count ? start : count;
        }
        return -1;
    }

    /**
     * Returns the number of {@code char}s in this string. If this string contains surrogate pairs,
     * this is not the same as the number of code points.
     */
    public int length() {
        return count;
    }

    /**
     * Returns true if the given subsequence of the given string matches this string starting
     * at the given offset.
     *
     * @param thisStart the start offset in this string.
     * @param string the other string.
     * @param start the start offset in {@code string}.
     * @param length the number of {@code char}s to compare.
     * @throws NullPointerException
     *             if {@code string} is {@code null}.
     */
    public boolean regionMatches(int thisStart, String string, int start, int length) {
        if (string == null) {
            throw new NullPointerException("string == null");
        }
        if (start < 0 || string.count - start < length) {
            return false;
        }
        if (thisStart < 0 || count - thisStart < length) {
            return false;
        }
        if (length <= 0) {
            return true;
        }
        int o1 = offset + thisStart, o2 = string.offset + start;
        char[] value1 = value;
        char[] value2 = string.value;
        for (int i = 0; i < length; ++i) {
            if (value1[o1 + i] != value2[o2 + i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns true if the given subsequence of the given string matches this string starting
     * at the given offset.
     *
     * <p>If ignoreCase is true, case is ignored during the comparison.
     * The strings are compared one {@code char} at a time. This is not suitable
     * for case-insensitive string comparison for all locales.
     * Use a {@link java.text.Collator} instead.
     *
     * @param ignoreCase
     *     specifies if case should be ignored (use {@link java.text.Collator} instead for
     *     non-ASCII case insensitivity).
     * @param thisStart the start offset in this string.
     * @param string the other string.
     * @param start the start offset in {@code string}.
     * @param length the number of {@code char}s to compare.
     * @throws NullPointerException
     *             if {@code string} is {@code null}.
     */
    public boolean regionMatches(boolean ignoreCase, int thisStart, String string, int start, int length) {
        if (!ignoreCase) {
            return regionMatches(thisStart, string, start, length);
        }
        if (string == null) {
            throw new NullPointerException("string == null");
        }
        if (thisStart < 0 || length > count - thisStart) {
            return false;
        }
        if (start < 0 || length > string.count - start) {
            return false;
        }
        thisStart += offset;
        start += string.offset;
        int end = thisStart + length;
        char[] target = string.value;
        while (thisStart < end) {
            char c1 = value[thisStart++];
            char c2 = target[start++];
            if (c1 != c2 && foldCase(c1) != foldCase(c2)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns a copy of this string after replacing occurrences of the given {@code char} with another.
     */
    public String replace(char oldChar, char newChar) {
        char[] buffer = value;
        int _offset = offset;
        int _count = count;

        int idx = _offset;
        int last = _offset + _count;
        boolean copied = false;
        while (idx < last) {
            if (buffer[idx] == oldChar) {
                if (!copied) {
                    char[] newBuffer = new char[_count];
                    System.arraycopy(buffer, _offset, newBuffer, 0, _count);
                    buffer = newBuffer;
                    idx -= _offset;
                    last -= _offset;
                    copied = true;
                }
                buffer[idx] = newChar;
            }
            idx++;
        }

        return copied ? new String(0, count, buffer) : this;
    }

    /**
     * Returns a copy of this string after replacing occurrences of {@code target} replaced
     * with {@code replacement}. The string is processed from the beginning to the
     * end.
     *
     * @throws NullPointerException
     *             if {@code target} or {@code replacement} is {@code null}.
     */
    public String replace(CharSequence target, CharSequence replacement) {
        if (target == null) {
            throw new NullPointerException("target == null");
        }
        if (replacement == null) {
            throw new NullPointerException("replacement == null");
        }

        String targetString = target.toString();
        int matchStart = indexOf(targetString, 0);
        if (matchStart == -1) {
            // If there's nothing to replace, return the original string untouched.
            return this;
        }

        String replacementString = replacement.toString();

        // The empty target matches at the start and end and between each char.
        int targetLength = targetString.length();
        if (targetLength == 0) {
            // The result contains the original 'count' chars, a copy of the
            // replacement string before every one of those chars, and a final
            // copy of the replacement string at the end.
            int resultLength = count + (count + 1) * replacementString.length();
            StringBuilder result = new StringBuilder(resultLength);
            result.append(replacementString);
            int end = offset + count;
            for (int i = offset; i != end; ++i) {
                result.append(value[i]);
                result.append(replacementString);
            }
            return result.toString();
        }

        StringBuilder result = new StringBuilder(count);
        int searchStart = 0;
        do {
            // Copy chars before the match...
            result.append(value, offset + searchStart, matchStart - searchStart);
            // Insert the replacement...
            result.append(replacementString);
            // And skip over the match...
            searchStart = matchStart + targetLength;
        } while ((matchStart = indexOf(targetString, searchStart)) != -1);
        // Copy any trailing chars...
        result.append(value, offset + searchStart, count - searchStart);
        return result.toString();
    }

    /**
     * Compares the specified string to this string to determine if the
     * specified string is a prefix.
     *
     * @param prefix
     *            the string to look for.
     * @return {@code true} if the specified string is a prefix of this string,
     *         {@code false} otherwise
     * @throws NullPointerException
     *             if {@code prefix} is {@code null}.
     */
    public boolean startsWith(String prefix) {
        return startsWith(prefix, 0);
    }

    /**
     * Compares the specified string to this string, starting at the specified
     * offset, to determine if the specified string is a prefix.
     *
     * @param prefix
     *            the string to look for.
     * @param start
     *            the starting offset.
     * @return {@code true} if the specified string occurs in this string at the
     *         specified offset, {@code false} otherwise.
     * @throws NullPointerException
     *             if {@code prefix} is {@code null}.
     */
    public boolean startsWith(String prefix, int start) {
        return regionMatches(start, prefix, 0, prefix.count);
    }

    /**
     * Returns a string containing a suffix of this string starting at {@code start}.
     * The returned string shares this string's <a href="#backing_array">backing array</a>.
     *
     * @throws IndexOutOfBoundsException
     *             if {@code start < 0} or {@code start > length()}.
     */
    public String substring(int start) {
        if (start == 0) {
            return this;
        }
        if (start >= 0 && start <= count) {
            return new String(offset + start, count - start, value);
        }
        throw indexAndLength(start);
    }

    /**
     * Returns a string containing the given subsequence of this string.
     * The returned string shares this string's <a href="#backing_array">backing array</a>.
     *
     * @param start the start offset.
     * @param end the end+1 offset.
     * @throws IndexOutOfBoundsException
     *             if {@code start < 0}, {@code start > end} or {@code end > length()}.
     */
    public String substring(int start, int end) {
        if (start == 0 && end == count) {
            return this;
        }
        // Fast range check.
        if (start >= 0 && start <= end && end <= count) {
            return new String(offset + start, end - start, value);
        }
        throw startEndAndLength(start, end);
    }

    /**
     * Returns a new {@code char} array containing a copy of the {@code char}s in this string.
     * This is expensive and rarely useful. If you just want to iterate over the {@code char}s in
     * the string, use {@link #charAt} instead.
     */
    public char[] toCharArray() {
        char[] buffer = new char[count];
        System.arraycopy(value, offset, buffer, 0, count);
        return buffer;
    }

    /**
     * Converts this string to lower case, using the rules of the user's default locale.
     * See "<a href="../util/Locale.html#default_locale">Be wary of the default locale</a>".
     *
     * @return a new lower case string, or {@code this} if it's already all lower case.
     */
    public String toLowerCase() {
        return CaseMapper.toLowerCase(Locale.getDefault(), this, value, offset, count);
    }

    /**
     * Converts this string to lower case, using the rules of {@code locale}.
     *
     * <p>Most case mappings are unaffected by the language of a {@code Locale}. Exceptions include
     * dotted and dotless I in Azeri and Turkish locales, and dotted and dotless I and J in
     * Lithuanian locales. On the other hand, it isn't necessary to provide a Greek locale to get
     * correct case mapping of Greek characters: any locale will do.
     *
     * <p>See <a href="http://www.unicode.org/Public/UNIDATA/SpecialCasing.txt">http://www.unicode.org/Public/UNIDATA/SpecialCasing.txt</a>
     * for full details of context- and language-specific special cases.
     *
     * @return a new lower case string, or {@code this} if it's already all lower case.
     */
    public String toLowerCase(Locale locale) {
        return CaseMapper.toLowerCase(locale, this, value, offset, count);
    }

    /**
     * Returns this string.
     */
    @Override
    public String toString() {
        return this;
    }

    /**
     * Converts this this string to upper case, using the rules of the user's default locale.
     * See "<a href="../util/Locale.html#default_locale">Be wary of the default locale</a>".
     *
     * @return a new upper case string, or {@code this} if it's already all upper case.
     */
    public String toUpperCase() {
        return CaseMapper.toUpperCase(Locale.getDefault(), this, value, offset, count);
    }

    /**
     * Converts this this string to upper case, using the rules of {@code locale}.
     *
     * <p>Most case mappings are unaffected by the language of a {@code Locale}. Exceptions include
     * dotted and dotless I in Azeri and Turkish locales, and dotted and dotless I and J in
     * Lithuanian locales. On the other hand, it isn't necessary to provide a Greek locale to get
     * correct case mapping of Greek characters: any locale will do.
     *
     * <p>See <a href="http://www.unicode.org/Public/UNIDATA/SpecialCasing.txt">http://www.unicode.org/Public/UNIDATA/SpecialCasing.txt</a>
     * for full details of context- and language-specific special cases.
     *
     * @return a new upper case string, or {@code this} if it's already all upper case.
     */
    public String toUpperCase(Locale locale) {
        return CaseMapper.toUpperCase(locale, this, value, offset, count);
    }

    /**
     * Returns a string with no code points <code><= \\u0020</code> at
     * the beginning or end.
     */
    public String trim() {
        int start = offset, last = offset + count - 1;
        int end = last;
        while ((start <= end) && (value[start] <= ' ')) {
            start++;
        }
        while ((end >= start) && (value[end] <= ' ')) {
            end--;
        }
        if (start == offset && end == last) {
            return this;
        }
        return new String(start, end - start + 1, value);
    }

    /**
     * Returns a new string containing the same {@code char}s as the given
     * array. Modifying the array after creating the string has no
     * effect on the string.
     *
     * @throws NullPointerException
     *             if {@code data} is {@code null}.
     */
    public static String valueOf(char[] data) {
        return new String(data, 0, data.length);
    }

    /**
     * Returns a new string containing the same {@code char}s as the given
     * subset of the given array. Modifying the array after creating the string has no
     * effect on the string.
     *
     * @throws IndexOutOfBoundsException
     *             if {@code length < 0}, {@code start < 0} or {@code start + length > data.length}
     * @throws NullPointerException
     *             if {@code data} is {@code null}.
     */
    public static String valueOf(char[] data, int start, int length) {
        return new String(data, start, length);
    }

    /**
     * Returns a new string of just the given {@code char}.
     */
    public static String valueOf(char value) {
        String s;
        if (value < 128) {
            s = new String(value, 1, ASCII);
        } else {
            s = new String(0, 1, new char[] { value });
        }
        s.hashCode = value;
        return s;
    }

    /**
     * Returns the string representation of the given double.
     */
    public static String valueOf(double value) {
        return Double.toString(value);
    }

    /**
     * Returns the string representation of the given float.
     */
    public static String valueOf(float value) {
        return Float.toString(value);
    }

    /**
     * Returns the string representation of the given int.
     */
    public static String valueOf(int value) {
        return Integer.toString(value);
    }

    /**
     * Returns the string representation of the given long.
     */
    public static String valueOf(long value) {
        return Long.toString(value);
    }

    /**
     * Converts the specified object to its string representation. If the object
     * is null return the string {@code "null"}, otherwise use {@code
     * toString()} to get the string representation.
     *
     * @param value
     *            the object.
     * @return the object converted to a string, or the string {@code "null"}.
     */
    public static String valueOf(Object value) {
        return value != null ? value.toString() : "null";
    }

    /**
     * Converts the specified boolean to its string representation. When the
     * boolean is {@code true} return {@code "true"}, otherwise return {@code
     * "false"}.
     *
     * @param value
     *            the boolean.
     * @return the boolean converted to a string.
     */
    public static String valueOf(boolean value) {
        return value ? "true" : "false";
    }

    /**
     * Returns true if the {@code char}s in the given {@code StringBuffer} are the same
     * as those in this string.
     *
     * @throws NullPointerException
     *             if {@code sb} is {@code null}.
     * @since 1.4
     */
    public boolean contentEquals(StringBuffer sb) {
        synchronized (sb) {
            int size = sb.length();
            if (count != size) {
                return false;
            }
            return regionMatches(0, new String(0, size, sb.getValue()), 0, size);
        }
    }

    /**
     * Returns true if the {@code char}s in the given {@code CharSequence} are the same
     * as those in this string.
     *
     * @since 1.5
     */
    public boolean contentEquals(CharSequence cs) {
        if (cs == null) {
            throw new NullPointerException("cs == null");
        }

        int len = cs.length();

        if (len != count) {
            return false;
        }

        if (len == 0 && count == 0) {
            return true; // since both are empty strings
        }

        return regionMatches(0, cs.toString(), 0, len);
    }

    /**
     * Tests whether this string matches the given {@code regularExpression}. This method returns
     * true only if the regular expression matches the <i>entire</i> input string. A common mistake is
     * to assume that this method behaves like {@link #contains}; if you want to match anywhere
     * within the input string, you need to add {@code .*} to the beginning and end of your
     * regular expression. See {@link Pattern#matches}.
     *
     * <p>If the same regular expression is to be used for multiple operations, it may be more
     * efficient to reuse a compiled {@code Pattern}.
     *
     * @throws PatternSyntaxException
     *             if the syntax of the supplied regular expression is not
     *             valid.
     * @throws NullPointerException if {@code regularExpression == null}
     * @since 1.4
     */
    public boolean matches(String regularExpression) {
        return Pattern.matches(regularExpression, this);
    }

    /**
     * Replaces all matches for {@code regularExpression} within this string with the given
     * {@code replacement}.
     * See {@link Pattern} for regular expression syntax.
     *
     * <p>If the same regular expression is to be used for multiple operations, it may be more
     * efficient to reuse a compiled {@code Pattern}.
     *
     * @throws PatternSyntaxException
     *             if the syntax of the supplied regular expression is not
     *             valid.
     * @throws NullPointerException if {@code regularExpression == null}
     * @see Pattern
     * @since 1.4
     */
    public String replaceAll(String regularExpression, String replacement) {
        return Pattern.compile(regularExpression).matcher(this).replaceAll(replacement);
    }

    /**
     * Replaces the first match for {@code regularExpression} within this string with the given
     * {@code replacement}.
     * See {@link Pattern} for regular expression syntax.
     *
     * <p>If the same regular expression is to be used for multiple operations, it may be more
     * efficient to reuse a compiled {@code Pattern}.
     *
     * @throws PatternSyntaxException
     *             if the syntax of the supplied regular expression is not
     *             valid.
     * @throws NullPointerException if {@code regularExpression == null}
     * @see Pattern
     * @since 1.4
     */
    public String replaceFirst(String regularExpression, String replacement) {
        return Pattern.compile(regularExpression).matcher(this).replaceFirst(replacement);
    }

    /**
     * Splits this string using the supplied {@code regularExpression}.
     * Equivalent to {@code split(regularExpression, 0)}.
     * See {@link Pattern#split(CharSequence, int)} for an explanation of {@code limit}.
     * See {@link Pattern} for regular expression syntax.
     *
     * <p>If the same regular expression is to be used for multiple operations, it may be more
     * efficient to reuse a compiled {@code Pattern}.
     *
     * @throws NullPointerException if {@code regularExpression ==  null}
     * @throws PatternSyntaxException
     *             if the syntax of the supplied regular expression is not
     *             valid.
     * @see Pattern
     * @since 1.4
     */
    public String[] split(String regularExpression) {
        return split(regularExpression, 0);
    }

    /**
     * Splits this string using the supplied {@code regularExpression}.
     * See {@link Pattern#split(CharSequence, int)} for an explanation of {@code limit}.
     * See {@link Pattern} for regular expression syntax.
     *
     * <p>If the same regular expression is to be used for multiple operations, it may be more
     * efficient to reuse a compiled {@code Pattern}.
     *
     * @throws NullPointerException if {@code regularExpression ==  null}
     * @throws PatternSyntaxException
     *             if the syntax of the supplied regular expression is not
     *             valid.
     * @since 1.4
     */
    public String[] split(String regularExpression, int limit) {
        String[] result = java.util.regex.Splitter.fastSplit(regularExpression, this, limit);
        return result != null ? result : Pattern.compile(regularExpression).split(this, limit);
    }

    /**
     * Equivalent to {@link #substring(int, int)} but needed to implement {@code CharSequence}.
     *
     * @throws IndexOutOfBoundsException
     *             if {@code start < 0}, {@code end < 0}, {@code start > end} or
     *             {@code end > length()}.
     * @see java.lang.CharSequence#subSequence(int, int)
     * @since 1.4
     */
    public CharSequence subSequence(int start, int end) {
        return substring(start, end);
    }

    /**
     * Returns the Unicode code point at the given {@code index}.
     *
     * @throws IndexOutOfBoundsException if {@code index < 0 || index >= length()}
     * @see Character#codePointAt(char[], int, int)
     * @since 1.5
     */
    public int codePointAt(int index) {
        if (index < 0 || index >= count) {
            throw indexAndLength(index);
        }
        return Character.codePointAt(value, offset + index, offset + count);
    }

    /**
     * Returns the Unicode code point that precedes the given {@code index}.
     *
     * @throws IndexOutOfBoundsException if {@code index < 1 || index > length()}
     * @see Character#codePointBefore(char[], int, int)
     * @since 1.5
     */
    public int codePointBefore(int index) {
        if (index < 1 || index > count) {
            throw indexAndLength(index);
        }
        return Character.codePointBefore(value, offset + index, offset);
    }

    /**
     * Calculates the number of Unicode code points between {@code start}
     * and {@code end}.
     *
     * @param start
     *            the inclusive beginning index of the subsequence.
     * @param end
     *            the exclusive end index of the subsequence.
     * @return the number of Unicode code points in the subsequence.
     * @throws IndexOutOfBoundsException
     *         if {@code start < 0 || end > length() || start > end}
     * @see Character#codePointCount(CharSequence, int, int)
     * @since 1.5
     */
    public int codePointCount(int start, int end) {
        if (start < 0 || end > count || start > end) {
            throw startEndAndLength(start, end);
        }
        return Character.codePointCount(value, offset + start, end - start);
    }

    /**
     * Returns true if this string contains the {@code chars}s from the given {@code CharSequence}.
     *
     * @since 1.5
     */
    public boolean contains(CharSequence cs) {
        if (cs == null) {
            throw new NullPointerException("cs == null");
        }
        return indexOf(cs.toString()) >= 0;
    }

    /**
     * Returns the index within this object that is offset from {@code index} by
     * {@code codePointOffset} code points.
     *
     * @param index
     *            the index within this object to calculate the offset from.
     * @param codePointOffset
     *            the number of code points to count.
     * @return the index within this object that is the offset.
     * @throws IndexOutOfBoundsException
     *             if {@code index} is negative or greater than {@code length()}
     *             or if there aren't enough code points before or after {@code
     *             index} to match {@code codePointOffset}.
     * @since 1.5
     */
    public int offsetByCodePoints(int index, int codePointOffset) {
        int s = index + offset;
        int r = Character.offsetByCodePoints(value, offset, count, s, codePointOffset);
        return r - offset;
    }

    /**
     * Returns a localized formatted string, using the supplied format and arguments,
     * using the user's default locale.
     *
     * <p>If you're formatting a string other than for human
     * consumption, you should use the {@code format(Locale, String, Object...)}
     * overload and supply {@code Locale.US}. See
     * "<a href="../util/Locale.html#default_locale">Be wary of the default locale</a>".
     *
     * @param format the format string (see {@link java.util.Formatter#format})
     * @param args
     *            the list of arguments passed to the formatter. If there are
     *            more arguments than required by {@code format},
     *            additional arguments are ignored.
     * @return the formatted string.
     * @throws NullPointerException if {@code format == null}
     * @throws java.util.IllegalFormatException
     *             if the format is invalid.
     * @since 1.5
     */
    public static String format(String format, Object... args) {
        return format(Locale.getDefault(), format, args);
    }

    /**
     * Returns a formatted string, using the supplied format and arguments,
     * localized to the given locale.
     *
     * @param locale
     *            the locale to apply; {@code null} value means no localization.
     * @param format the format string (see {@link java.util.Formatter#format})
     * @param args
     *            the list of arguments passed to the formatter. If there are
     *            more arguments than required by {@code format},
     *            additional arguments are ignored.
     * @return the formatted string.
     * @throws NullPointerException if {@code format == null}
     * @throws java.util.IllegalFormatException
     *             if the format is invalid.
     * @since 1.5
     */
    public static String format(Locale locale, String format, Object... args) {
        if (format == null) {
            throw new NullPointerException("format == null");
        }
        int bufferSize = format.length() + (args == null ? 0 : args.length * 10);
        Formatter f = new Formatter(new StringBuilder(bufferSize), locale);
        return f.format(format, args).toString();
    }

    /*
     * An implementation of a String.indexOf that is supposed to perform
     * substantially better than the default algorithm if the "needle" (the
     * subString being searched for) is a constant string.
     *
     * For example, a JIT, upon encountering a call to String.indexOf(String),
     * where the needle is a constant string, may compute the values cache, md2
     * and lastChar, and change the call to the following method.
     */
    @FindBugsSuppressWarnings("UPM_UNCALLED_PRIVATE_METHOD")
    @SuppressWarnings("unused")
    private static int indexOf(String haystackString, String needleString,
            int cache, int md2, char lastChar) {
        char[] haystack = haystackString.value;
        int haystackOffset = haystackString.offset;
        int haystackLength = haystackString.count;
        char[] needle = needleString.value;
        int needleOffset = needleString.offset;
        int needleLength = needleString.count;
        int needleLengthMinus1 = needleLength - 1;
        int haystackEnd = haystackOffset + haystackLength;
        outer_loop: for (int i = haystackOffset + needleLengthMinus1; i < haystackEnd;) {
            if (lastChar == haystack[i]) {
                for (int j = 0; j < needleLengthMinus1; ++j) {
                    if (needle[j + needleOffset] != haystack[i + j
                            - needleLengthMinus1]) {
                        int skip = 1;
                        if ((cache & (1 << haystack[i])) == 0) {
                            skip += j;
                        }
                        i += Math.max(md2, skip);
                        continue outer_loop;
                    }
                }
                return i - needleLengthMinus1 - haystackOffset;
            }

            if ((cache & (1 << haystack[i])) == 0) {
                i += needleLengthMinus1;
            }
            i++;
        }
        return -1;
    }
}
