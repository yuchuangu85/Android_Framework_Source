/* GENERATED SOURCE. DO NOT MODIFY. */
// © 2025 and later: Unicode, Inc. and others.
// License & terms of use: https://www.unicode.org/copyright.html

package android.icu.segmenter;

import android.icu.text.BreakIterator;
import android.icu.text.RuleBasedBreakIterator;
import java.io.InputStream;

/**
 * Performs segmentation according to the provided rule string. The rule string must follow the
 * same guidelines as for {@link RuleBasedBreakIterator#RuleBasedBreakIterator(String)}.
 * @hide Only a subset of ICU is exposed in Android
 * @hide draft / provisional / internal are hidden on Android
 */
public class RuleBasedSegmenter implements Segmenter {

  private final BreakIterator breakIterPrototype;

  /**
   * Returns a {@link Segments} object that encapsulates the segmentation of the input
   * {@code CharSequence}. The {@code Segments} object, in turn, provides the main APIs to support
   * traversal over the resulting segments and boundaries via the Java {@code Stream} abstraction.
   * @param s input {@code CharSequence} on which segmentation is performed. The input must not be
   *     modified while using the resulting {@code Segments} object.
   * @return A {@code Segments} object with APIs to access the results of segmentation, including
   *     APIs that return {@code Stream}s of the segments and boundaries.
   * @hide draft / provisional / internal are hidden on Android
   */
  @Override
  public Segments segment(CharSequence s) {
    return new SegmentsImpl(breakIterPrototype, s);
  }

  /**
   * @return a builder for constructing {@code RuleBasedSegmenter}
   * @hide draft / provisional / internal are hidden on Android
   */
  public static Builder builder() {
    return new Builder();
  }

  private RuleBasedSegmenter(BreakIterator breakIter) {
    breakIterPrototype = breakIter;
  }

  /**
   * Builder for {@link RuleBasedSegmenter}
   * @hide Only a subset of ICU is exposed in Android
 * @hide draft / provisional / internal are hidden on Android
   */
  public static class Builder {

    private BreakIterator breakIter = null;

    private Builder() { }

    /**
     * Sets the rule string for segmentation.
     * @param rules rule string.  The rule string must follow the same guidelines as for
     *     {@link RuleBasedBreakIterator#getInstanceFromCompiledRules(InputStream)}.
     * @hide draft / provisional / internal are hidden on Android
     */
    public Builder setRules(String rules) {
      if (rules == null) {
        throw new IllegalArgumentException("rules cannot be set to null.");
      }
      try {
        breakIter = new RuleBasedBreakIterator(rules);
        return this;
      } catch (RuntimeException rte) {
        throw new IllegalArgumentException("The provided rule string is invalid"
            + " or there was an error in creating the RuleBasedSegmenter.", rte);
      }
    }

    /**
     * Builds the {@code Segmenter}
     * @return the constructed {@code Segmenter} instance
     * @hide draft / provisional / internal are hidden on Android
     */
    public Segmenter build() {
      if (breakIter == null) {
        throw new IllegalArgumentException("A rule string must be set.");
      } else {
        return new RuleBasedSegmenter(breakIter);
      }
    }
  }
}
