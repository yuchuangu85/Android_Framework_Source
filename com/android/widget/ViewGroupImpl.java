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

import android.media.update.ViewGroupProvider;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

public abstract class ViewGroupImpl implements ViewGroupProvider {
    private final ViewGroupProvider mSuperProvider;

    public ViewGroupImpl(ViewGroup instance,
            ViewGroupProvider superProvider, ViewGroupProvider privateProvider) {
        mSuperProvider = superProvider;
    }

    @Override
    public void onAttachedToWindow_impl() {
        mSuperProvider.onAttachedToWindow_impl();
    }

    @Override
    public void onDetachedFromWindow_impl() {
        mSuperProvider.onDetachedFromWindow_impl();
    }

    @Override
    public CharSequence getAccessibilityClassName_impl() {
        return mSuperProvider.getAccessibilityClassName_impl();
    }

    @Override
    public boolean onTouchEvent_impl(MotionEvent ev) {
        return mSuperProvider.onTouchEvent_impl(ev);
    }

    @Override
    public boolean onTrackballEvent_impl(MotionEvent ev) {
        return mSuperProvider.onTrackballEvent_impl(ev);
    }

    @Override
    public void onFinishInflate_impl() {
        mSuperProvider.onFinishInflate_impl();
    }

    @Override
    public void setEnabled_impl(boolean enabled) {
        mSuperProvider.setEnabled_impl(enabled);
    }

    @Override
    public void onVisibilityAggregated_impl(boolean isVisible) {
        mSuperProvider.onVisibilityAggregated_impl(isVisible);
    }

    @Override
    public void onLayout_impl(boolean changed, int left, int top, int right, int bottom) {
        mSuperProvider.onLayout_impl(changed, left, top, right, bottom);
    }

    @Override
    public void onMeasure_impl(int widthMeasureSpec, int heightMeasureSpec) {
        mSuperProvider.onMeasure_impl(widthMeasureSpec, heightMeasureSpec);
    }

    @Override
    public int getSuggestedMinimumWidth_impl() {
        return mSuperProvider.getSuggestedMinimumWidth_impl();
    }

    @Override
    public int getSuggestedMinimumHeight_impl() {
        return mSuperProvider.getSuggestedMinimumHeight_impl();
    }

    @Override
    public void setMeasuredDimension_impl(int measuredWidth, int measuredHeight) {
        mSuperProvider.setMeasuredDimension_impl(measuredWidth, measuredHeight);
    }

    @Override
    public boolean dispatchTouchEvent_impl(MotionEvent ev) {
        return mSuperProvider.dispatchTouchEvent_impl(ev);
    }

    @Override
    public boolean checkLayoutParams_impl(ViewGroup.LayoutParams p) {
        return mSuperProvider.checkLayoutParams_impl(p);
    }

    @Override
    public ViewGroup.LayoutParams generateDefaultLayoutParams_impl() {
        return mSuperProvider.generateDefaultLayoutParams_impl();
    }

    @Override
    public ViewGroup.LayoutParams generateLayoutParams_impl(AttributeSet attrs) {
        return mSuperProvider.generateLayoutParams_impl(attrs);
    }

    @Override
    public ViewGroup.LayoutParams generateLayoutParams_impl(ViewGroup.LayoutParams lp) {
        return mSuperProvider.generateLayoutParams_impl(lp);
    }

    @Override
    public boolean shouldDelayChildPressedState_impl() {
        return mSuperProvider.shouldDelayChildPressedState_impl();
    }

    @Override
    public void measureChildWithMargins_impl(View child,
        int parentWidthMeasureSpec, int widthUsed, int parentHeightMeasureSpec, int heightUsed) {
        mSuperProvider.measureChildWithMargins_impl(child,
                parentWidthMeasureSpec, widthUsed, parentHeightMeasureSpec, heightUsed);
    }
}
