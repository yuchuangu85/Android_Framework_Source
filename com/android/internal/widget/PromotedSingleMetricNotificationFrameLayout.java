/*
 * Copyright (C) 2025 The Android Open Source Project
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

import android.content.Context;
import android.util.AttributeSet;
import android.view.NotificationTopLineView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.RemoteViews;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.internal.R;

/**
 * A custom {@link FrameLayout} designed specifically for displaying promoted single metric
 * {@link Notification.MetricStyle}
 *  @see SingleMetricNotificationFrameLayout
 * it measures the single metric value (which has maximum width) and adjust end margins of
 * {@link NotificationTopLineView} and label accordingly to render header and label correctly.
 * @hide
 */
@RemoteViews.RemoteView
public final class PromotedSingleMetricNotificationFrameLayout
        extends SingleMetricNotificationFrameLayout {

    @NonNull
    private NotificationTopLineView mNotificationToplineView;

    public PromotedSingleMetricNotificationFrameLayout(
            @NonNull Context context) {
        super(context);
    }

    public PromotedSingleMetricNotificationFrameLayout(@NonNull Context context,
            @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public PromotedSingleMetricNotificationFrameLayout(@NonNull Context context,
            @Nullable AttributeSet attrs,
            int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }


    public PromotedSingleMetricNotificationFrameLayout(@NonNull Context context,
            @Nullable AttributeSet attrs,
            int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }


    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mNotificationToplineView = requireViewById(R.id.notification_top_line);
    }

    protected void adjustEndMarginBeforeOnMeasure(int marginEnd) {
        mNotificationToplineView.setHeaderTextMarginEnd(marginEnd);
    }

    @Override
    protected View getMetricLabelContainer() {
        return requireViewById(R.id.notification_main_column);
    }

    @Override
    protected float getMetricValueMaxFraction() {
        return getContext().getResources().getFraction(
                R.fraction.notification_single_promoted_metric_value_max_fraction, 1, 1
        );
    }
}
