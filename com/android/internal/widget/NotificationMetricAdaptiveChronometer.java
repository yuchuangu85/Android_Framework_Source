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

import android.app.Flags;
import android.content.Context;
import android.util.AttributeSet;
import android.widget.Chronometer;
import android.widget.RemoteViews;

import androidx.annotation.NonNull;

import com.android.internal.annotations.VisibleForTesting;

import java.time.InstantSource;
import java.util.List;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Variant of the {@link Chronometer} widget used for
 * {@link android.app.Notification.Metric.TimeDifference}. Chooses the appropriate text variant
 * depending on the available space.
 *
 * @hide
 */
@RemoteViews.RemoteView
public class NotificationMetricAdaptiveChronometer extends Chronometer {

    private final NotificationMetricAdaptiveTextHelper mHelper =
            new NotificationMetricAdaptiveTextHelper();

    public NotificationMetricAdaptiveChronometer(Context context) {
        super(context);
    }

    public NotificationMetricAdaptiveChronometer(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public NotificationMetricAdaptiveChronometer(Context context, AttributeSet attrs,
            int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public NotificationMetricAdaptiveChronometer(Context context, AttributeSet attrs,
            int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    @VisibleForTesting
    public NotificationMetricAdaptiveChronometer(Context context, LongSupplier elapsedRealtimeClock,
            InstantSource systemClock, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, elapsedRealtimeClock, systemClock, attrs, defStyleAttr, defStyleRes);
    }

    @Override
    protected void setChronometerText(List<String> textVariants) {
        if (!Flags.metricValueAlternativeStrings()) {
            super.setChronometerText(textVariants);
            return;
        }

        if (mHelper != null) {
            mHelper.setTextVariants(textVariants);
        }
        setText(textVariants.get(0));
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (Flags.metricValueAlternativeStrings()) {
            NotificationMetricAdaptiveTextHelper.Replacement replacement =
                    mHelper.chooseReplacement(getPaint(), widthMeasureSpec,
                            getCompoundPaddingLeft() + getCompoundPaddingRight());

            if (replacement != null) {
                replaceTextBy(replacement.value().toString());
            }
        }

        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    private void replaceTextBy(@NonNull String text) {
        CharSequence current = getText();
        if (current == null || !Objects.equals(current.toString(), text)) {
            setText(text);
        }
    }
}
