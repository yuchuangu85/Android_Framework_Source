/* GENERATED SOURCE. DO NOT MODIFY. */
// © 2022 and later: Unicode, Inc. and others.
// License & terms of use: https://www.unicode.org/copyright.html

package android.icu.message2;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.text.AttributedCharacterIterator;

import android.icu.text.ConstrainedFieldPosition;
import android.icu.text.FormattedValue;

/**
 * Very-very rough implementation of FormattedValue, packaging a string.
 * Expect it to change.
 *
 * @hide Only a subset of ICU is exposed in Android
 * @hide draft / provisional / internal are hidden on Android
 */
public class PlainStringFormattedValue implements FormattedValue {
    private final String value;

    /**
     * Constructor, taking the string to store.
     *
     * @param value the string value to store
     *
     * @hide draft / provisional / internal are hidden on Android
     */
    public PlainStringFormattedValue(String value) {
        if (value == null) {
            throw new IllegalAccessError("Should not try to wrap a null in a formatted value");
        }
        this.value = value;
    }

    /**
     * {@inheritDoc}
     *
     * @hide draft / provisional / internal are hidden on Android
     */
    @Override
    public int length() {
        return value == null ? 0 : value.length();
    }

    /**
     * {@inheritDoc}
     *
     * @hide draft / provisional / internal are hidden on Android
     */
    @Override
    public char charAt(int index) {
        return value.charAt(index);
    }

    /**
     * {@inheritDoc}
     *
     * @hide draft / provisional / internal are hidden on Android
     */
    @Override
    public CharSequence subSequence(int start, int end) {
        return value.subSequence(start, end);
    }

    /**
     * {@inheritDoc}
     *
     * @hide draft / provisional / internal are hidden on Android
     */
    @Override
    public <A extends Appendable> A appendTo(A appendable) {
        try {
            appendable.append(value);
        } catch (IOException e) {
            throw new UncheckedIOException("problem appending", e);
        }
        return appendable;
    }

    /**
     * Not yet implemented.
     *
     * {@inheritDoc}
     *
     * @hide draft / provisional / internal are hidden on Android
     */
    @Override
    public boolean nextPosition(ConstrainedFieldPosition cfpos) {
        throw new RuntimeException("nextPosition not yet implemented");
    }

    /**
     * Not yet implemented.
     *
     * {@inheritDoc}
     *
     * @hide draft / provisional / internal are hidden on Android
     */
    @Override
    public AttributedCharacterIterator toCharacterIterator() {
        throw new RuntimeException("toCharacterIterator not yet implemented");
    }

    /**
     * {@inheritDoc}
     *
     * @hide draft / provisional / internal are hidden on Android
     */
    @Override
    public String toString() {
        return value;
    }
}
