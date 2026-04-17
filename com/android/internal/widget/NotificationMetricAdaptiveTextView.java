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

import static com.android.internal.util.Preconditions.checkArgument;

import android.annotation.NonNull;
import android.app.Flags;
import android.content.Context;
import android.util.AttributeSet;
import android.view.RemotableViewMethod;
import android.widget.RemoteViews;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.util.List;

/**
 * Specialized {@link TextView} used to display {@link android.app.Notification.Metric.MetricValue}.
 * It will choose one the text options supplied to {@link #setTextVariants(List)} according
 * to their fit in the provided space without ellipsizing. If none of the options fit, the first
 * one will be picked.
 *
 * @hide
 */
@RemoteViews.RemoteView
public class NotificationMetricAdaptiveTextView extends NotificationMetricTextView {

    private static final int VARIANT_NONE = -1;

    private final NotificationMetricAdaptiveTextHelper mHelper =
            new NotificationMetricAdaptiveTextHelper();
    private List<CharSequence> mTextVariants;

    // Which of the variants is currently displayed, and whether we're currently swapping out one
    // variant from another. This is to optimize calls to setText() and avoid duplicating work.
    private int mVariantIndex = VARIANT_NONE;
    private boolean mReplacingText;

    public NotificationMetricAdaptiveTextView(Context context) {
        super(context);
    }

    public NotificationMetricAdaptiveTextView(Context context,
            @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public NotificationMetricAdaptiveTextView(Context context, @Nullable AttributeSet attrs,
            int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public NotificationMetricAdaptiveTextView(Context context, @Nullable AttributeSet attrs,
            int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    @Override
    @NonNull
    public Runnable setTextVariantsAsync(@NonNull List<CharSequence> textVariants) {
        return () -> setTextVariants(textVariants);
    }

    @Override
    @RemotableViewMethod(asyncImpl = "setTextVariantsAsync")
    public void setTextVariants(@NonNull List<CharSequence> textVariants) {
        if (!Flags.metricValueAlternativeStrings()) {
            super.setTextVariants(textVariants);
            return;
        }

        checkArgument(!textVariants.isEmpty(), "textVariants must have at least one entry");
        if (mTextVariants != null && mTextVariants.equals(textVariants)) {
            return;
        }
        mTextVariants = List.copyOf(textVariants);
        mHelper.setTextVariants(textVariants);
        mVariantIndex = VARIANT_NONE;

        // Start with preferred text and let the measurement pass handle the swap, if needed.
        replaceTextBy(0);
    }

    @Override
    public void setText(CharSequence text, BufferType type) {
        if (Flags.metricValueAlternativeStrings() && !mReplacingText) {
            // A "normal" call to setText overwrites previous calls to setTextVariants().
            mTextVariants = null;
            if (mHelper != null) {
                mHelper.setTextVariants(null);
            }
            mVariantIndex = VARIANT_NONE;
        }

        super.setText(text, type);
    }

    private void replaceTextBy(int variantIndex) {
        if (mVariantIndex == variantIndex) {
            return; // Not switching, bail out.
        }
        mVariantIndex = variantIndex;
        boolean previousReplacing = mReplacingText;
        try {
            mReplacingText = true;
            setText(mTextVariants.get(variantIndex));
        } finally {
            mReplacingText = previousReplacing;
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (Flags.metricValueAlternativeStrings()) {
            NotificationMetricAdaptiveTextHelper.Replacement replacement =
                    mHelper.chooseReplacement(getPaint(), widthMeasureSpec,
                            getCompoundPaddingLeft() + getCompoundPaddingRight());

            if (replacement != null) {
                replaceTextBy(replacement.index());
            }
        }

        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }
}
