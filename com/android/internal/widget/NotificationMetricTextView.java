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
import android.content.Context;
import android.util.AttributeSet;
import android.view.RemotableViewMethod;
import android.widget.RemoteViews;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.util.List;

/**
 * Subclass of {@link TextView} used to display {@link android.app.Notification.Metric.MetricValue}.
 * Introduces the {@link #setTextVariants(List)} method, of which it always picks the first option.
 *
 * @see NotificationMetricAdaptiveTextView
 * @hide
 */
@RemoteViews.RemoteView
public class NotificationMetricTextView extends TextView {
    public NotificationMetricTextView(Context context) {
        super(context);
    }

    public NotificationMetricTextView(Context context,
            @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public NotificationMetricTextView(Context context, @Nullable AttributeSet attrs,
            int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public NotificationMetricTextView(Context context, @Nullable AttributeSet attrs,
            int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    /**
     * Sets the possible texts to be displayed. Should usually be expanded / compact versions of the
     * same content (e.g. "10 square cm" and "10 cm^2"). The first one will be preferred.
     */
    @RemotableViewMethod(asyncImpl = "setTextVariantsAsync")
    public void setTextVariants(@NonNull List<CharSequence> textVariants) {
        checkArgument(!textVariants.isEmpty(), "textVariants must have at least one entry");
        setText(textVariants.get(0));
    }

    /** Async version of {@link #setTextVariants(List)}. */
    @NonNull
    public Runnable setTextVariantsAsync(@NonNull List<CharSequence> textVariants) {
        return () -> setTextVariants(textVariants);
    }
}
