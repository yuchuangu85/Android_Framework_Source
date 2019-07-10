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
import java.util.Arrays;
import java.util.Comparator;
import java.util.Formatter;
import java.util.Locale;
import java.util.regex.Pattern;
import libcore.util.CharsetUtils;
import libcore.util.EmptyArray;

/**
 * An immutable sequence of UTF-16 {@code char}s.
 * See {@link Character} for details about the relationship between {@code char} and
 * Unicode code points.
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

    private final int count;

    private int hashCode;

    /**
     * Creates an empty string.
     */
    public String() {
        throw new UnsupportedOperationException("Use StringFactory instead.");
    }

    /**
     * Converts the byte array to a string using the system's
     * {@link java.nio.charset.Charset#defaultCharset default charset}.
     */
    @FindBugsSuppressWarnings("DM_DEFAULT_ENCODING")
    public String(byte[] data) {
        throw new UnsupportedOperationException("Use StringFactory instead.");
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
        throw new UnsupportedOperationException("Use StringFactory instead.");
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
        throw new UnsupportedOperationException("Use StringFactory instead.");
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
        throw new UnsupportedOperationException("Use StringFactory instead.");
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
        throw new UnsupportedOperationException("Use StringFactory instead.");
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
        throw new UnsupportedOperationException("Use StringFactory instead.");
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
        throw new UnsupportedOperationException("Use StringFactory instead.");
    }

    /**
     * Converts the byte array to a String using the given charset.
     *
     * @throws NullPointerException if {@code data == null}
     * @since 1.6
     */
    public String(byte[] data, Charset charset) {
        throw new UnsupportedOperationException("Use StringFactory instead.");
    }

    /**
     * Initializes this string to contain the given {@code char}s.
     * Modifying the array after creating the string
     * has no effect on the string.
     *
     * @throws NullPointerException if {@code data == null}
     */
    public String(char[] data) {
        throw new UnsupportedOperationException("Use StringFactory instead.");
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
        throw new UnsupportedOperationException("Use StringFactory instead.");
    }

    /*
     * Internal version of the String(char[], int, int) constructor.
     * Does not range check or null check.
     */
    // TODO: Replace calls to this with calls to StringFactory, will require
    // splitting other files in java.lang.
    String(int offset, int charCount, char[] chars) {
        throw new UnsupportedOperationException("Use StringFactory instead.");
    }

    /**
     * Constructs a new string with the same sequence of characters as {@code
     * toCopy}.
     */
    public String(String toCopy) {
        throw new UnsupportedOperationException("Use StringFactory instead.");
    }

    /**
     * Creates a {@code String} from the contents of the specified
     * {@code StringBuffer}.
     */
    public String(StringBuffer stringBuffer) {
        throw new UnsupportedOperationException("Use StringFactory instead.");
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
        throw new UnsupportedOperationException("Use StringFactory instead.");
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
        throw new UnsupportedOperationException("Use StringFactory instead.");
    }

    /**
     * Returns the {@code char} at {@code index}.
     * @throws IndexOutOfBoundsException if {@code index < 0} or {@code index >= length()}.
     */
    public native char charAt(int index);

    native void setCharAt(int index, char c);

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
        int result;
        int end = count < string.count ? count : string.count;
        char c1, c2;
        for (int i = 0; i < end; ++i) {
            if ((c1 = charAt(i)) == (c2 = string.charAt(i))) {
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
    public native String concat(String string);

    /**
     * Creates a new string by copying the given {@code char[]}.
     * Modifying the array after creating the string has no
     * effect on the string.
     *
     * @throws NullPointerException
     *             if {@code data} is {@code null}.
     */
    public static String copyValueOf(char[] data) {
        return StringFactory.newStringFromChars(data, 0, data.length);
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
        return StringFactory.newStringFromChars(data, start, length);
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
            for (int i = 0; i < count; ++i) {
                if (charAt(i) != s.charAt(i)) {
                    return false;
                }
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
        for (int i = 0; i < count; ++i) {
            char c1 = charAt(i);
            char c2 = string.charAt(i);
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
            try {
                for (int i = start; i < end; ++i) {
                    data[index++] = (byte) charAt(i);
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
            return CharsetUtils.toUtf8Bytes(this, 0, count);
        } else if (canonicalCharsetName.equals("ISO-8859-1")) {
            return CharsetUtils.toIsoLatin1Bytes(this, 0, count);
        } else if (canonicalCharsetName.equals("US-ASCII")) {
            return CharsetUtils.toAsciiBytes(this, 0, count);
        } else if (canonicalCharsetName.equals("UTF-16BE")) {
            return CharsetUtils.toBigEndianUtf16Bytes(this, 0, count);
        } else {
            ByteBuffer buffer = charset.encode(this);
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
            if (buffer == null) {
                throw new NullPointerException("buffer == null");
            }
            if (index < 0) {
                throw new IndexOutOfBoundsException("index < 0");
            }
            if (end - start > buffer.length - index) {
                throw new ArrayIndexOutOfBoundsException("end - start > buffer.length - index");
            }
            getCharsNoCheck(start, end, buffer, index);
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
    native void getCharsNoCheck(int start, int end, char[] buffer, int index);

    @Override public int hashCode() {
        int hash = hashCode;
        if (hash == 0) {
            if (count == 0) {
                return 0;
            }
            for (int i = 0; i < count; ++i) {
                hash = 31 * hash + charAt(i);
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
            char firstChar = string.charAt(0);
            while (true) {
                int i = indexOf(firstChar, start);
                if (i == -1 || subCount + i > _count) {
                    return -1; // handles subCount > count || start >= count
                }
                int o1 = i, o2 = 0;
                while (++o2 < subCount && charAt(++o1) == string.charAt(o2)) {
                    // Intentionally empty
                }
                if (o2 == subCount) {
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
            char firstChar = subString.charAt(0);
            while (true) {
                int i = indexOf(firstChar, start);
                if (i == -1 || subCount + i > _count) {
                    return -1; // handles subCount > count || start >= count
                }
                int o1 = i, o2 = 0;
                while (++o2 < subCount && charAt(++o1) == subString.charAt(o2)) {
                    // Intentionally empty
                }
                if (o2 == subCount) {
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
        for (int i = _count - 1; i >= 0; --i) {
            if (charAt(i) == c) {
                return i;
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
        if (start >= 0) {
            if (start >= _count) {
                start = _count - 1;
            }
            for (int i = start; i >= 0; --i) {
                if (charAt(i) == c) {
                    return i;
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
        String needle = StringFactory.newStringFromChars(0, chars.length, chars);
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
                char firstChar = subString.charAt(0);
                while (true) {
                    int i = lastIndexOf(firstChar, start);
                    if (i == -1) {
                        return -1;
                    }
                    int o1 = i, o2 = 0;
                    while (++o2 < subCount && charAt(++o1) == subString.charAt(o2)) {
                        // Intentionally empty
                    }
                    if (o2 == subCount) {
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
        for (int i = 0; i < length; ++i) {
            if (charAt(thisStart + i) != string.charAt(start + i)) {
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
        int end = thisStart + length;
        while (thisStart < end) {
            char c1 = charAt(thisStart++);
            char c2 = string.charAt(start++);
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
        String s = null;
        int _count = count;
        boolean copied = false;
        for (int i = 0; i < _count; ++i) {
            if (charAt(i) == oldChar) {
                if (!copied) {
                    s = StringFactory.newStringFromString(this);
                    copied = true;
                }
                s.setCharAt(i, newChar);
            }
        }

        return copied ? s : this;
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
            for (int i = 0; i != count; ++i) {
                result.append(charAt(i));
                result.append(replacementString);
            }
            return result.toString();
        }

        StringBuilder result = new StringBuilder(count);
        int searchStart = 0;
        do {
            // Copy characters before the match...
            // TODO: Perform this faster than one char at a time?
            for (int i = searchStart; i < matchStart; ++i) {
                result.append(charAt(i));
            }
            // Insert the replacement...
            result.append(replacementString);
            // And skip over the match...
            searchStart = matchStart + targetLength;
        } while ((matchStart = indexOf(targetString, searchStart)) != -1);
        // Copy any trailing chars...
        // TODO: Perform this faster than one char at a time?
        for (int i = searchStart; i < count; ++i) {
            result.append(charAt(i));
        }
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
            return fastSubstring(start, count - start);
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
            return fastSubstring(start, end - start);
        }
        throw startEndAndLength(start, end);
    }

    private native String fastSubstring(int start, int length);

    /**
     * Returns a new {@code char} array containing a copy of the {@code char}s in this string.
     * This is expensive and rarely useful. If you just want to iterate over the {@code char}s in
     * the string, use {@link #charAt} instead.
     */
    public native char[] toCharArray();

    /**
     * Converts this string to lower case, using the rules of the user's default locale.
     * See "<a href="../util/Locale.html#default_locale">Be wary of the default locale</a>".
     *
     * @return a new lower case string, or {@code this} if it's already all lower case.
     */
    public String toLowerCase() {
        return CaseMapper.toLowerCase(Locale.getDefault(), this);
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
        return CaseMapper.toLowerCase(locale, this);
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
        return CaseMapper.toUpperCase(Locale.getDefault(), this, count);
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
        return CaseMapper.toUpperCase(locale, this, count);
    }

    /**
     * Returns a string with no code points <code><= \\u0020</code> at
     * the beginning or end.
     */
    public String trim() {
        int start = 0, last = count - 1;
        int end = last;
        while ((start <= end) && (charAt(start) <= ' ')) {
            start++;
        }
        while ((end >= start) && (charAt(end) <= ' ')) {
            end--;
        }
        if (start == 0 && end == last) {
            return this;
        }
        return fastSubstring(start, end - start + 1);
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
        return StringFactory.newStringFromChars(data, 0, data.length);
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
        return StringFactory.newStringFromChars(data, start, length);
    }

    /**
     * Returns a new string of just the given {@code char}.
     */
    public static String valueOf(char value) {
        String s;
        if (value < 128) {
            s = StringFactory.newStringFromChars(value, 1, ASCII);
        } else {
            s = StringFactory.newStringFromChars(0, 1, new char[] { value });
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
            String s = StringFactory.newStringFromChars(0, size, sb.getValue());
            return regionMatches(0, s, 0, size);
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
        return Character.codePointAt(this, index);
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
        return Character.codePointBefore(this, index);
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
        return Character.codePointCount(this, start, end);
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
        return Character.offsetByCodePoints(this, index, codePointOffset);
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
        int haystackLength = haystackString.count;
        int needleLength = needleString.count;
        int needleLengthMinus1 = needleLength - 1;
        outer_loop: for (int i = needleLengthMinus1; i < haystackLength;) {
            if (lastChar == haystackString.charAt(i)) {
                for (int j = 0; j < needleLengthMinus1; ++j) {
                    if (needleString.charAt(j) !=
                            haystackString.charAt(i + j - needleLengthMinus1)) {
                        int skip = 1;
                        if ((cache & (1 << haystackString.charAt(i))) == 0) {
                            skip += j;
                        }
                        i += Math.max(md2, skip);
                        continue outer_loop;
                    }
                }
                return i - needleLengthMinus1;
            }

            if ((cache & (1 << haystackString.charAt(i))) == 0) {
                i += needleLengthMinus1;
            }
            i++;
        }
        return -1;
    }
}
