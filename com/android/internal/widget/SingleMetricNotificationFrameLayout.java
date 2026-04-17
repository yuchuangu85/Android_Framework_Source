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

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.RemoteViews;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.internal.R;

/**
 * A custom {@link FrameLayout} designed specifically for displaying single metric
 * {@link Notification.MetricStyle}
 *
 * it measures the single metric value (which has maximum width) and adjust end margins of
 * label accordingly to render  correctly.
 * @hide
 */
@RemoteViews.RemoteView
public class SingleMetricNotificationFrameLayout extends FrameLayout {

    @NonNull
    private View mMetricValueContainer;

    @NonNull
    private View mMetricLabelContainer;

    private float mValueContainerMaxFraction;

    public SingleMetricNotificationFrameLayout(
            @NonNull Context context) {
        super(context);
        init();
    }

    public SingleMetricNotificationFrameLayout(@NonNull Context context,
            @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public SingleMetricNotificationFrameLayout(@NonNull Context context,
            @Nullable AttributeSet attrs,
            int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    public SingleMetricNotificationFrameLayout(@NonNull Context context,
            @Nullable AttributeSet attrs,
            int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        init();
    }

    private void init() {
        mValueContainerMaxFraction = getMetricValueMaxFraction();
    }

    protected float getMetricValueMaxFraction() {
        return getContext().getResources().getFraction(
                R.fraction.notification_single_metric_value_max_fraction, 1, 1
        );
    }

    protected View getMetricValueContainer() {
        return requireViewById(R.id.metric_value_container);
    }

    protected View getMetricLabelContainer() {
        return requireViewById(R.id.metric_label_0);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mMetricValueContainer = getMetricValueContainer();
        mMetricLabelContainer = getMetricLabelContainer();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        measureChildWithMargins(mMetricValueContainer,
                widthMeasureSpec, 0,
                heightMeasureSpec, 0);

        final int toplineMarginEnd = getLabelContainerMarginEnd();

        adjustEndMarginBeforeOnMeasure(toplineMarginEnd);

        final MarginLayoutParams layoutParams =
                (MarginLayoutParams) mMetricLabelContainer.getLayoutParams();

        if (layoutParams != null) {
            layoutParams.setMarginEnd(toplineMarginEnd);
            mMetricLabelContainer.setLayoutParams(layoutParams);
        }

        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    protected void adjustEndMarginBeforeOnMeasure(int marginEnd) {}

    /**
     * NOTE: mMetricValueContainer needs to be measured before this method.
     */
    private int getLabelContainerMarginEnd() {
        final MarginLayoutParams layoutParams =
                (MarginLayoutParams) mMetricValueContainer.getLayoutParams();

        final int horizontalMargins;
        if (layoutParams != null) {
            horizontalMargins = layoutParams.getMarginStart() + layoutParams.getMarginEnd();
        } else {
            horizontalMargins = 0;
        }

        return mMetricValueContainer.getMeasuredWidth() + horizontalMargins;
    }

    @Override
    protected void measureChildWithMargins(View child, int parentWidthMeasureSpec, int widthUsed,
            int parentHeightMeasureSpec, int heightUsed) {
        if (child.getId() == R.id.metric_value_container) {
            final int availableWidth = MeasureSpec.getSize(parentWidthMeasureSpec);
            final int maxAllowedWidth = (int) (availableWidth * mValueContainerMaxFraction);
            final int restrictedWidthSpec = MeasureSpec.makeMeasureSpec(maxAllowedWidth,
                    MeasureSpec.AT_MOST);
            super.measureChildWithMargins(
                    child,
                    restrictedWidthSpec,
                    widthUsed,
                    parentHeightMeasureSpec,
                    heightUsed
            );
        } else {
            super.measureChildWithMargins(child, parentWidthMeasureSpec, widthUsed,
                    parentHeightMeasureSpec, heightUsed);
        }
    }
}
