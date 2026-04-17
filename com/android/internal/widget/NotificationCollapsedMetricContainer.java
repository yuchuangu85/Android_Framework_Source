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

import android.annotation.Nullable;
import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.RemoteViews;

import com.android.internal.R;


/***
 * Used in the collapsed Notification.MetricStyle, this horizontal linear layout renders only
 * the children that can fit within its available width; otherwise it hides them.
 */
@RemoteViews.RemoteView
public class NotificationCollapsedMetricContainer extends LinearLayout {

    private View mMetricView0;
    private View mMetricView1;
    private View mMetricView2;

    public NotificationCollapsedMetricContainer(Context context) {
        super(context);
    }

    public NotificationCollapsedMetricContainer(Context context,
            @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public NotificationCollapsedMetricContainer(Context context, @Nullable AttributeSet attrs,
            int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public NotificationCollapsedMetricContainer(Context context, AttributeSet attrs,
            int defStyleAttr,
            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mMetricView0 = requireViewById(R.id.metric_view_0);
        mMetricView1 = requireViewById(R.id.metric_view_1);
        mMetricView2 = requireViewById(R.id.metric_view_2);
    }


     /**
     * Lays out the child views (metrics and separators) within the available width,
     * dynamically hiding subsequent metric view groups if they exceed the available space.
     * The primary metric view (R.id.metric_view_0) is guaranteed to be shown if possible.
     *
     * The logic prioritizes showing views in order: R.id.metric_view_0, then R.id.metric_view_1,
     * and finally R.id.metric_view_2. If adding a view and its preceding separator
     * would cause the total width to exceed availableWidth, that view
     * and all subsequent views/separators are hidden.
     *
     * This is a horizontal linear layout and it expects its children to be wrapped_content width
     * and have no layout_weight.
     */
    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        final int availableWidth = MeasureSpec.getSize(widthMeasureSpec);
        final int measureSpecForWidth = MeasureSpec.makeSafeMeasureSpec(
                Integer.MAX_VALUE, MeasureSpec.UNSPECIFIED);
        super.onMeasure(measureSpecForWidth, heightMeasureSpec);
        final int horizontalPaddings = mPaddingLeft + mPaddingRight;
        final int verticalPaddings = mPaddingTop + mPaddingBottom;
        int usedWidth = horizontalPaddings;
        final int viewWidth0 = getChildUsedWidth(mMetricView0);
        final int viewWidth1 = getChildUsedWidth(mMetricView1);
        final int viewWidth2 = getChildUsedWidth(mMetricView2);

        usedWidth += viewWidth0;
        // First child does not fit in the available space. Let's fit it in and show it.
        if (usedWidth > availableWidth) {
            measureChildWithMargins(mMetricView0, widthMeasureSpec,
                    horizontalPaddings, heightMeasureSpec, verticalPaddings);
            updateShowing(mMetricView1, false);
            updateShowing(mMetricView2, false);
            setMeasuredDimension(horizontalPaddings + getChildUsedWidth(mMetricView0),
                    getMeasuredHeight());
            return;
        }
        if (usedWidth + viewWidth1 > availableWidth) {
            updateShowing(mMetricView1, false);
            updateShowing(mMetricView2,false);
            setMeasuredDimension(usedWidth, getMeasuredHeight());
            return;
        }

        updateShowing(mMetricView1, true);
        usedWidth += viewWidth1;

        if (usedWidth + viewWidth2 > availableWidth) {
            updateShowing(mMetricView2, false);
        } else {
            usedWidth += viewWidth2;
            updateShowing(mMetricView2, true);
        }
        setMeasuredDimension(usedWidth, getMeasuredHeight());
    }

    private static int getChildUsedWidth(View child) {
        final MarginLayoutParams layoutParams = (MarginLayoutParams) child.getLayoutParams();
        return child.getMeasuredWidth()
                + layoutParams.getMarginStart() + layoutParams.getMarginEnd();
    }

    void updateShowing(View view, boolean showing) {
        if (showing) {
            if (view.getVisibility() == INVISIBLE) {
                view.setVisibility(VISIBLE);
            }
        } else {
            if (view.getVisibility() == VISIBLE) {
                view.setVisibility(INVISIBLE);
            }
        }
    }
}
