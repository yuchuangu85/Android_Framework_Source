/*
 * Copyright 2018 The Android Open Source Project
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

package com.android.widget;

import android.graphics.drawable.Drawable;
import android.media.update.ViewGroupProvider;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewGroup.LayoutParams;
import android.view.ViewGroup.MarginLayoutParams;

import java.util.ArrayList;

public class BaseLayout extends ViewGroupImpl {
    private final ViewGroup mInstance;
    private final ViewGroupProvider mSuperProvider;
    private final ViewGroupProvider mPrivateProvider;

    private final ArrayList<View> mMatchParentChildren = new ArrayList<>(1);

    public BaseLayout(ViewGroup instance,
            ViewGroupProvider superProvider, ViewGroupProvider privateProvider) {
        super(instance, superProvider, privateProvider);
        mInstance = instance;
        mSuperProvider = superProvider;
        mPrivateProvider = privateProvider;
    }

    @Override
    public boolean checkLayoutParams_impl(LayoutParams p) {
        return p instanceof MarginLayoutParams;
    }

    @Override
    public LayoutParams generateDefaultLayoutParams_impl() {
        return new MarginLayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
    }

    @Override
    public LayoutParams generateLayoutParams_impl(AttributeSet attrs) {
        return new MarginLayoutParams(mInstance.getContext(), attrs);
    }

    @Override
    public LayoutParams generateLayoutParams_impl(LayoutParams lp) {
        if (lp instanceof MarginLayoutParams) {
            return lp;
        }
        return new MarginLayoutParams(lp);
    }

    @Override
    public void onMeasure_impl(int widthMeasureSpec, int heightMeasureSpec) {
        int count = mInstance.getChildCount();

        final boolean measureMatchParentChildren =
                View.MeasureSpec.getMode(widthMeasureSpec) != View.MeasureSpec.EXACTLY ||
                        View.MeasureSpec.getMode(heightMeasureSpec) != View.MeasureSpec.EXACTLY;
        mMatchParentChildren.clear();

        int maxHeight = 0;
        int maxWidth = 0;
        int childState = 0;

        for (int i = 0; i < count; i++) {
            final View child = mInstance.getChildAt(i);
            if (child.getVisibility() != View.GONE) {
                mPrivateProvider.measureChildWithMargins_impl(
                        child, widthMeasureSpec, 0, heightMeasureSpec, 0);
                final MarginLayoutParams lp = (MarginLayoutParams) child.getLayoutParams();
                maxWidth = Math.max(maxWidth,
                        child.getMeasuredWidth() + lp.leftMargin + lp.rightMargin);
                maxHeight = Math.max(maxHeight,
                        child.getMeasuredHeight() + lp.topMargin + lp.bottomMargin);
                childState = childState | child.getMeasuredState();
                if (measureMatchParentChildren) {
                    if (lp.width == LayoutParams.MATCH_PARENT ||
                            lp.height == LayoutParams.MATCH_PARENT) {
                        mMatchParentChildren.add(child);
                    }
                }
            }
        }

        // Account for padding too
        maxWidth += getPaddingLeftWithForeground() + getPaddingRightWithForeground();
        maxHeight += getPaddingTopWithForeground() + getPaddingBottomWithForeground();

        // Check against our minimum height and width
        maxHeight = Math.max(maxHeight, mPrivateProvider.getSuggestedMinimumHeight_impl());
        maxWidth = Math.max(maxWidth, mPrivateProvider.getSuggestedMinimumWidth_impl());

        // Check against our foreground's minimum height and width
        final Drawable drawable = mInstance.getForeground();
        if (drawable != null) {
            maxHeight = Math.max(maxHeight, drawable.getMinimumHeight());
            maxWidth = Math.max(maxWidth, drawable.getMinimumWidth());
        }

        mPrivateProvider.setMeasuredDimension_impl(
                mInstance.resolveSizeAndState(maxWidth, widthMeasureSpec, childState),
                mInstance.resolveSizeAndState(maxHeight, heightMeasureSpec,
                        childState << View.MEASURED_HEIGHT_STATE_SHIFT));

        count = mMatchParentChildren.size();
        if (count > 1) {
            for (int i = 0; i < count; i++) {
                final View child = mMatchParentChildren.get(i);
                final MarginLayoutParams lp = (MarginLayoutParams) child.getLayoutParams();

                final int childWidthMeasureSpec;
                if (lp.width == LayoutParams.MATCH_PARENT) {
                    final int width = Math.max(0, mInstance.getMeasuredWidth()
                            - getPaddingLeftWithForeground() - getPaddingRightWithForeground()
                            - lp.leftMargin - lp.rightMargin);
                    childWidthMeasureSpec = View.MeasureSpec.makeMeasureSpec(
                            width, View.MeasureSpec.EXACTLY);
                } else {
                    childWidthMeasureSpec = mInstance.getChildMeasureSpec(widthMeasureSpec,
                            getPaddingLeftWithForeground() + getPaddingRightWithForeground() +
                                    lp.leftMargin + lp.rightMargin,
                            lp.width);
                }

                final int childHeightMeasureSpec;
                if (lp.height == LayoutParams.MATCH_PARENT) {
                    final int height = Math.max(0, mInstance.getMeasuredHeight()
                            - getPaddingTopWithForeground() - getPaddingBottomWithForeground()
                            - lp.topMargin - lp.bottomMargin);
                    childHeightMeasureSpec = View.MeasureSpec.makeMeasureSpec(
                            height, View.MeasureSpec.EXACTLY);
                } else {
                    childHeightMeasureSpec = mInstance.getChildMeasureSpec(heightMeasureSpec,
                            getPaddingTopWithForeground() + getPaddingBottomWithForeground() +
                                    lp.topMargin + lp.bottomMargin,
                            lp.height);
                }

                child.measure(childWidthMeasureSpec, childHeightMeasureSpec);
            }
        }
    }

    @Override
    public void onLayout_impl(boolean changed, int left, int top, int right, int bottom) {
        final int count = mInstance.getChildCount();

        final int parentLeft = getPaddingLeftWithForeground();
        final int parentRight = right - left - getPaddingRightWithForeground();

        final int parentTop = getPaddingTopWithForeground();
        final int parentBottom = bottom - top - getPaddingBottomWithForeground();

        for (int i = 0; i < count; i++) {
            final View child = mInstance.getChildAt(i);
            if (child.getVisibility() != View.GONE) {
                final MarginLayoutParams lp = (MarginLayoutParams) child.getLayoutParams();

                final int width = child.getMeasuredWidth();
                final int height = child.getMeasuredHeight();

                int childLeft;
                int childTop;

                childLeft = parentLeft + (parentRight - parentLeft - width) / 2 +
                        lp.leftMargin - lp.rightMargin;

                childTop = parentTop + (parentBottom - parentTop - height) / 2 +
                        lp.topMargin - lp.bottomMargin;

                child.layout(childLeft, childTop, childLeft + width, childTop + height);
            }
        }
    }

    @Override
    public boolean shouldDelayChildPressedState_impl() {
        return false;
    }

    private int getPaddingLeftWithForeground() {
        return mInstance.isForegroundInsidePadding() ? Math.max(mInstance.getPaddingLeft(), 0) :
                mInstance.getPaddingLeft() + 0;
    }

    private int getPaddingRightWithForeground() {
        return mInstance.isForegroundInsidePadding() ? Math.max(mInstance.getPaddingRight(), 0) :
                mInstance.getPaddingRight() + 0;
    }

    private int getPaddingTopWithForeground() {
        return mInstance.isForegroundInsidePadding() ? Math.max(mInstance.getPaddingTop(), 0) :
                mInstance.getPaddingTop() + 0;
    }

    private int getPaddingBottomWithForeground() {
        return mInstance.isForegroundInsidePadding() ? Math.max(mInstance.getPaddingBottom(), 0) :
                mInstance.getPaddingBottom() + 0;
    }
}
