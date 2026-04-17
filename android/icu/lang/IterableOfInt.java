/* GENERATED SOURCE. DO NOT MODIFY. */
// © 2025 and later: Unicode, Inc. and others.
// License & terms of use: http://www.unicode.org/copyright.html

package android.icu.lang;

import java.util.PrimitiveIterator;

/**
 * Subinterface of Iterable whose iterator() returns a {@link PrimitiveIterator.OfInt}.
 * Allows direct use of the primitive iterator without downcasting.
 *
 * @hide Only a subset of ICU is exposed in Android
 * @hide draft / provisional / internal are hidden on Android
 */
public interface IterableOfInt extends Iterable<Integer> {
    /**
     * @return a {@link PrimitiveIterator.OfInt}
     * @hide draft / provisional / internal are hidden on Android
     */
    @Override
    public PrimitiveIterator.OfInt iterator();
}
