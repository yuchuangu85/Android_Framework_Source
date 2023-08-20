/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.stack;

import static com.android.systemui.statusbar.notification.stack.NotificationPriorityBucketKt.BUCKET_MEDIA_CONTROLS;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.graphics.Rect;
import android.view.View;
import android.view.animation.Interpolator;

import com.android.app.animation.Interpolators;
import com.android.systemui.statusbar.notification.row.ExpandableView;

/**
 * Represents the bounds of a section of the notification shade and handles animation when the
 * bounds change.
 */
public class NotificationSection {
    private @PriorityBucket final int mBucket;
    private final View mOwningView;
    private final Rect mBounds = new Rect();
    private final Rect mCurrentBounds = new Rect(-1, -1, -1, -1);
    private final Rect mStartAnimationRect = new Rect();
    private final Rect mEndAnimationRect = new Rect();
    private ObjectAnimator mTopAnimator = null;
    private ObjectAnimator mBottomAnimator = null;
    private ExpandableView mFirstVisibleChild;
    private ExpandableView mLastVisibleChild;

    NotificationSection(View owningView, @PriorityBucket int bucket) {
        mOwningView = owningView;
        mBucket = bucket;
    }

    public void cancelAnimators() {
        if (mBottomAnimator != null) {
            mBottomAnimator.cancel();
        }
        if (mTopAnimator != null) {
            mTopAnimator.cancel();
        }
    }

    public Rect getCurrentBounds() {
        return mCurrentBounds;
    }

    public Rect getBounds() {
        return mBounds;
    }

    public boolean didBoundsChange() {
        return !mCurrentBounds.equals(mBounds);
    }

    public boolean areBoundsAnimating() {
        return mBottomAnimator != null || mTopAnimator != null;
    }

    @PriorityBucket
    public int getBucket() {
        return mBucket;
    }

    public void startBackgroundAnimation(boolean animateTop, boolean animateBottom) {
        // Left and right bounds are always applied immediately.
        mCurrentBounds.left = mBounds.left;
        mCurrentBounds.right = mBounds.right;
        startBottomAnimation(animateBottom);
        startTopAnimation(animateTop);
    }


    private void startTopAnimation(boolean animate) {
        int previousEndValue = mEndAnimationRect.top;
        int newEndValue = mBounds.top;
        ObjectAnimator previousAnimator = mTopAnimator;
        if (previousAnimator != null && previousEndValue == newEndValue) {
            return;
        }
        if (!animate) {
            // just a local update was performed
            if (previousAnimator != null) {
                // we need to increase all animation keyframes of the previous animator by the
                // relative change to the end value
                int previousStartValue = mStartAnimationRect.top;
                PropertyValuesHolder[] values = previousAnimator.getValues();
                values[0].setIntValues(previousStartValue, newEndValue);
                mStartAnimationRect.top = previousStartValue;
                mEndAnimationRect.top = newEndValue;
                previousAnimator.setCurrentPlayTime(previousAnimator.getCurrentPlayTime());
                return;
            } else {
                // no new animation needed, let's just apply the value
                setBackgroundTop(newEndValue);
                return;
            }
        }
        if (previousAnimator != null) {
            previousAnimator.cancel();
        }
        ObjectAnimator animator = ObjectAnimator.ofInt(this, "backgroundTop",
                mCurrentBounds.top, newEndValue);
        Interpolator interpolator = Interpolators.FAST_OUT_SLOW_IN;
        animator.setInterpolator(interpolator);
        animator.setDuration(StackStateAnimator.ANIMATION_DURATION_STANDARD);
        // remove the tag when the animation is finished
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                mStartAnimationRect.top = -1;
                mEndAnimationRect.top = -1;
                mTopAnimator = null;
            }
        });
        animator.start();
        mStartAnimationRect.top = mCurrentBounds.top;
        mEndAnimationRect.top = newEndValue;
        mTopAnimator = animator;
    }

    private void startBottomAnimation(boolean animate) {
        int previousStartValue = mStartAnimationRect.bottom;
        int previousEndValue = mEndAnimationRect.bottom;
        int newEndValue = mBounds.bottom;
        ObjectAnimator previousAnimator = mBottomAnimator;
        if (previousAnimator != null && previousEndValue == newEndValue) {
            return;
        }
        if (!animate) {
            // just a local update was performed
            if (previousAnimator != null) {
                // we need to increase all animation keyframes of the previous animator by the
                // relative change to the end value
                PropertyValuesHolder[] values = previousAnimator.getValues();
                values[0].setIntValues(previousStartValue, newEndValue);
                mStartAnimationRect.bottom = previousStartValue;
                mEndAnimationRect.bottom = newEndValue;
                previousAnimator.setCurrentPlayTime(previousAnimator.getCurrentPlayTime());
                return;
            } else {
                // no new animation needed, let's just apply the value
                setBackgroundBottom(newEndValue);
                return;
            }
        }
        if (previousAnimator != null) {
            previousAnimator.cancel();
        }
        ObjectAnimator animator = ObjectAnimator.ofInt(this, "backgroundBottom",
                mCurrentBounds.bottom, newEndValue);
        Interpolator interpolator = Interpolators.FAST_OUT_SLOW_IN;
        animator.setInterpolator(interpolator);
        animator.setDuration(StackStateAnimator.ANIMATION_DURATION_STANDARD);
        // remove the tag when the animation is finished
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                mStartAnimationRect.bottom = -1;
                mEndAnimationRect.bottom = -1;
                mBottomAnimator = null;
            }
        });
        animator.start();
        mStartAnimationRect.bottom = mCurrentBounds.bottom;
        mEndAnimationRect.bottom = newEndValue;
        mBottomAnimator = animator;
    }

    private void setBackgroundTop(int top) {
        mCurrentBounds.top = top;
        mOwningView.invalidate();
    }

    private void setBackgroundBottom(int bottom) {
        mCurrentBounds.bottom = bottom;
        mOwningView.invalidate();
    }

    public ExpandableView getFirstVisibleChild() {
        return mFirstVisibleChild;
    }

    public ExpandableView getLastVisibleChild() {
        return mLastVisibleChild;
    }

    public boolean setFirstVisibleChild(ExpandableView child) {
        boolean changed = mFirstVisibleChild != child;
        mFirstVisibleChild = child;
        return changed;
    }

    public boolean setLastVisibleChild(ExpandableView child) {
        boolean changed = mLastVisibleChild != child;
        mLastVisibleChild = child;
        return changed;
    }

    public void resetCurrentBounds() {
        mCurrentBounds.set(mBounds);
    }

    /**
     * Returns true if {@code top} is equal to the top of this section (if not currently animating)
     * or where the top of this section will be when animation completes.
     */
    public boolean isTargetTop(int top) {
        return (mTopAnimator == null && mCurrentBounds.top == top)
                || (mTopAnimator != null && mEndAnimationRect.top == top);
    }

    /**
     * Returns true if {@code bottom} is equal to the bottom of this section (if not currently
     * animating) or where the bottom of this section will be when animation completes.
     */
    public boolean isTargetBottom(int bottom) {
        return (mBottomAnimator == null && mCurrentBounds.bottom == bottom)
                || (mBottomAnimator != null && mEndAnimationRect.bottom == bottom);
    }

    /**
     * Update the bounds of this section based on it's views
     *
     * @param minTopPosition the minimum position that the top needs to have
     * @param minBottomPosition the minimum position that the bottom needs to have
     * @return the position of the new bottom
     */
    public int updateBounds(int minTopPosition, int minBottomPosition,
            boolean shiftBackgroundWithFirst) {
        int top = minTopPosition;
        int bottom = minTopPosition;
        ExpandableView firstView = getFirstVisibleChild();
        if (firstView != null) {
            // Round Y up to avoid seeing the background during animation
            int finalTranslationY = (int) Math.ceil(ViewState.getFinalTranslationY(firstView));
            // TODO: look into the already animating part
            int newTop;
            if (isTargetTop(finalTranslationY)) {
                // we're ending up at the same location as we are now, let's just skip the
                // animation
                newTop = finalTranslationY;
            } else {
                newTop = (int) Math.ceil(firstView.getTranslationY());
            }
            top = Math.max(newTop, top);
            if (firstView.showingPulsing()) {
                // If we're pulsing, the notification can actually go below!
                bottom = Math.max(bottom, finalTranslationY
                        + ExpandableViewState.getFinalActualHeight(firstView));
                if (shiftBackgroundWithFirst) {
                    mBounds.left += Math.max(firstView.getTranslation(), 0);
                    mBounds.right += Math.min(firstView.getTranslation(), 0);
                }
            }
        }
        ExpandableView lastView = getLastVisibleChild();
        if (lastView != null) {
            float finalTranslationY = ViewState.getFinalTranslationY(lastView);
            int finalHeight = ExpandableViewState.getFinalActualHeight(lastView);
            // Round Y down to avoid seeing the background during animation
            int finalBottom = (int) Math.floor(
                    finalTranslationY + finalHeight - lastView.getClipBottomAmount());
            int newBottom;
            if (isTargetBottom(finalBottom)) {
                // we're ending up at the same location as we are now, lets just skip the animation
                newBottom = finalBottom;
            } else {
                newBottom = (int) (lastView.getTranslationY() + lastView.getActualHeight()
                        - lastView.getClipBottomAmount());
                // The background can never be lower than the end of the last view
                minBottomPosition = (int) Math.min(
                        lastView.getTranslationY() + lastView.getActualHeight(),
                        minBottomPosition);
            }
            bottom = Math.max(bottom, Math.max(newBottom, minBottomPosition));
        }
        bottom = Math.max(top, bottom);
        mBounds.top = top;
        mBounds.bottom = bottom;
        return bottom;
    }

    public boolean needsBackground() {
        return mFirstVisibleChild != null && mBucket != BUCKET_MEDIA_CONTROLS;
    }
}
