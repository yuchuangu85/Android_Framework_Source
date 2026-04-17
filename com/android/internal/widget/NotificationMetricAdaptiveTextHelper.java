/*
 * Copyright (C) 2026 The Android Open Source Project
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

package com.android.internal.widget;

import android.text.Layout;
import android.text.PrecomputedText;
import android.text.TextPaint;
import android.util.IntArray;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import java.util.List;

/**
 * Helper logic to choose the preferred text variant according to measure specs.
 * @hide -- public is required for unit test of framework classes. :(
 */
@VisibleForTesting(otherwise = VisibleForTesting.PACKAGE_PRIVATE)
public class NotificationMetricAdaptiveTextHelper {

    private List<CharSequence> mTextVariants;

    // To avoid recomputation, we store the result of calculating the width of the text variants.
    // If we're measuring the same strings with the same TextPaint (as compared by Params) then
    // we can just return the previous value.
    private IntArray mTextVariantsCache;
    private PrecomputedText.Params mTextVariantsCacheParams;

    public record Replacement(int index, CharSequence value) { }

    /** Sets the list of available variants. */
    public void setTextVariants(@Nullable List<? extends CharSequence> textVariants) {
        mTextVariants = textVariants != null ? List.copyOf(textVariants) : null;
        mTextVariantsCache = new IntArray();
    }

    /** Chooses the most suitable variant according to the requirements of the measuring pass. */
    @Nullable
    public Replacement chooseReplacement(TextPaint paint, int widthMeasureSpec,
            int horizontalPadding) {
        if (mTextVariants == null || mTextVariants.size() < 2) {
            return null; // No alternatives.
        }

        int specMode = View.MeasureSpec.getMode(widthMeasureSpec);
        if (specMode == View.MeasureSpec.UNSPECIFIED) {
            // No restrictions? Use preferred.
            return new Replacement(0, mTextVariants.get(0));
        }

        // specMode is EXACTLY or AT_MOST -> Choose most appropriate variant.
        int specSize = View.MeasureSpec.getSize(widthMeasureSpec);
        int availableWidth = specSize - horizontalPadding;
        if (availableWidth <= 0) {
            return null; // Hmm? Anyway we won't do anything useful by continuing, so give up.
        }

        // Need to clone TextPaint for later comparison, because it's not immutable and will be
        // modified by TextView methods that change text dimensions.
        TextPaint currentPaint = new TextPaint(paint);
        PrecomputedText.Params params = new PrecomputedText.Params.Builder(currentPaint).build();
        if (mTextVariantsCacheParams == null || !mTextVariantsCacheParams.equals(params)) {
            mTextVariantsCache = new IntArray();
            mTextVariantsCacheParams = params;
        }

        for (int i = 0; i < mTextVariants.size(); i++) {
            int variantWidth;
            if (mTextVariantsCache.size() > i) {
                variantWidth = mTextVariantsCache.get(i);
            } else {
                variantWidth = (int) Math.ceil(
                        Layout.getDesiredWidth(mTextVariants.get(i), currentPaint));
                mTextVariantsCache.add(variantWidth);
            }

            if (variantWidth <= availableWidth) {
                // In order of preference, found one that fits.
                return new Replacement(i, mTextVariants.get(i));
            }
        }

        // If all the options are too long, fall back to the default.
        return new Replacement(0, mTextVariants.get(0));
    }
}
