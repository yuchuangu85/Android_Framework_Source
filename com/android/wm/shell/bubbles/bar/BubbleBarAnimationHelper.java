/*
 * Copyright (C) 2023 The Android Open Source Project
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
package com.android.wm.shell.bubbles.bar;

import static android.view.View.VISIBLE;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.PointF;
import android.util.Log;
import android.widget.FrameLayout;

import com.android.wm.shell.animation.Interpolators;
import com.android.wm.shell.animation.PhysicsAnimator;
import com.android.wm.shell.bubbles.BubblePositioner;
import com.android.wm.shell.bubbles.BubbleViewProvider;
import com.android.wm.shell.bubbles.animation.AnimatableScaleMatrix;

/**
 * Helper class to animate a {@link BubbleBarExpandedView} on a bubble.
 */
public class BubbleBarAnimationHelper {

    private static final String TAG = BubbleBarAnimationHelper.class.getSimpleName();

    private static final float EXPANDED_VIEW_ANIMATE_SCALE_AMOUNT = 0.1f;
    private static final float EXPANDED_VIEW_ANIMATE_OUT_SCALE_AMOUNT = .75f;
    private static final int EXPANDED_VIEW_ALPHA_ANIMATION_DURATION = 150;

    /** Spring config for the expanded view scale-in animation. */
    private final PhysicsAnimator.SpringConfig mScaleInSpringConfig =
            new PhysicsAnimator.SpringConfig(300f, 0.9f);

    /** Spring config for the expanded view scale-out animation. */
    private final PhysicsAnimator.SpringConfig mScaleOutSpringConfig =
            new PhysicsAnimator.SpringConfig(900f, 1f);

    /** Matrix used to scale the expanded view container with a given pivot point. */
    private final AnimatableScaleMatrix mExpandedViewContainerMatrix = new AnimatableScaleMatrix();

    /** Animator for animating the expanded view's alpha (including the TaskView inside it). */
    private final ValueAnimator mExpandedViewAlphaAnimator = ValueAnimator.ofFloat(0f, 1f);

    private final Context mContext;
    private final BubbleBarLayerView mLayerView;
    private final BubblePositioner mPositioner;

    private BubbleViewProvider mExpandedBubble;
    private boolean mIsExpanded = false;

    public BubbleBarAnimationHelper(Context context,
            BubbleBarLayerView bubbleBarLayerView,
            BubblePositioner positioner) {
        mContext = context;
        mLayerView = bubbleBarLayerView;
        mPositioner = positioner;

        mExpandedViewAlphaAnimator.setDuration(EXPANDED_VIEW_ALPHA_ANIMATION_DURATION);
        mExpandedViewAlphaAnimator.setInterpolator(Interpolators.PANEL_CLOSE_ACCELERATED);
        mExpandedViewAlphaAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationStart(Animator animation) {
                if (mExpandedBubble != null && mExpandedBubble.getBubbleBarExpandedView() != null) {
                    // We need to be Z ordered on top in order for alpha animations to work.
                    mExpandedBubble.getBubbleBarExpandedView().setSurfaceZOrderedOnTop(true);
                    mExpandedBubble.getBubbleBarExpandedView().setAnimating(true);
                }
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                if (mExpandedBubble != null && mExpandedBubble.getBubbleBarExpandedView() != null) {
                    // The surface needs to be Z ordered on top for alpha values to work on the
                    // TaskView, and if we're temporarily hidden, we are still on the screen
                    // with alpha = 0f until we animate back. Stay Z ordered on top so the alpha
                    // = 0f remains in effect.
                    if (mIsExpanded) {
                        mExpandedBubble.getBubbleBarExpandedView().setSurfaceZOrderedOnTop(false);
                    }

                    mExpandedBubble.getBubbleBarExpandedView().setContentVisibility(mIsExpanded);
                    mExpandedBubble.getBubbleBarExpandedView().setAnimating(false);
                }
            }
        });
        mExpandedViewAlphaAnimator.addUpdateListener(valueAnimator -> {
            if (mExpandedBubble != null && mExpandedBubble.getBubbleBarExpandedView() != null) {
                float alpha = (float) valueAnimator.getAnimatedValue();
                mExpandedBubble.getBubbleBarExpandedView().setTaskViewAlpha(alpha);
                mExpandedBubble.getBubbleBarExpandedView().setAlpha(alpha);
            }
        });
    }

    /**
     * Animates the provided bubble's expanded view to the expanded state.
     */
    public void animateExpansion(BubbleViewProvider expandedBubble) {
        mExpandedBubble = expandedBubble;
        if (mExpandedBubble == null) {
            return;
        }
        BubbleBarExpandedView bev = mExpandedBubble.getBubbleBarExpandedView();
        if (bev == null) {
            return;
        }
        mIsExpanded = true;

        mExpandedViewContainerMatrix.setScaleX(0f);
        mExpandedViewContainerMatrix.setScaleY(0f);

        updateExpandedView();
        bev.setAnimating(true);
        bev.setContentVisibility(false);
        bev.setAlpha(0f);
        bev.setTaskViewAlpha(0f);
        bev.setVisibility(VISIBLE);

        // Set the pivot point for the scale, so the view animates out from the bubble bar.
        PointF bubbleBarPosition = mPositioner.getBubbleBarPosition();
        mExpandedViewContainerMatrix.setScale(
                1f - EXPANDED_VIEW_ANIMATE_SCALE_AMOUNT,
                1f - EXPANDED_VIEW_ANIMATE_SCALE_AMOUNT,
                bubbleBarPosition.x,
                bubbleBarPosition.y);

        bev.setAnimationMatrix(mExpandedViewContainerMatrix);

        mExpandedViewAlphaAnimator.start();

        PhysicsAnimator.getInstance(mExpandedViewContainerMatrix).cancel();
        PhysicsAnimator.getInstance(mExpandedViewContainerMatrix)
                .spring(AnimatableScaleMatrix.SCALE_X,
                        AnimatableScaleMatrix.getAnimatableValueForScaleFactor(1f),
                        mScaleInSpringConfig)
                .spring(AnimatableScaleMatrix.SCALE_Y,
                        AnimatableScaleMatrix.getAnimatableValueForScaleFactor(1f),
                        mScaleInSpringConfig)
                .addUpdateListener((target, values) -> {
                    mExpandedBubble.getBubbleBarExpandedView().setAnimationMatrix(
                            mExpandedViewContainerMatrix);
                })
                .withEndActions(() -> {
                    bev.setAnimationMatrix(null);
                    updateExpandedView();
                    bev.setSurfaceZOrderedOnTop(false);
                })
                .start();
    }

    /**
     * Collapses the currently expanded bubble.
     *
     * @param endRunnable a runnable to run at the end of the animation.
     */
    public void animateCollapse(Runnable endRunnable) {
        mIsExpanded = false;
        if (mExpandedBubble == null || mExpandedBubble.getBubbleBarExpandedView() == null) {
            Log.w(TAG, "Trying to animate collapse without a bubble");
            return;
        }

        mExpandedViewContainerMatrix.setScaleX(1f);
        mExpandedViewContainerMatrix.setScaleY(1f);

        PhysicsAnimator.getInstance(mExpandedViewContainerMatrix).cancel();
        PhysicsAnimator.getInstance(mExpandedViewContainerMatrix)
                .spring(AnimatableScaleMatrix.SCALE_X,
                        AnimatableScaleMatrix.getAnimatableValueForScaleFactor(
                                EXPANDED_VIEW_ANIMATE_OUT_SCALE_AMOUNT),
                        mScaleOutSpringConfig)
                .spring(AnimatableScaleMatrix.SCALE_Y,
                        AnimatableScaleMatrix.getAnimatableValueForScaleFactor(
                                EXPANDED_VIEW_ANIMATE_OUT_SCALE_AMOUNT),
                        mScaleOutSpringConfig)
                .addUpdateListener((target, values) -> {
                    if (mExpandedBubble != null
                            && mExpandedBubble.getBubbleBarExpandedView() != null) {
                        mExpandedBubble.getBubbleBarExpandedView().setAnimationMatrix(
                                mExpandedViewContainerMatrix);
                    }
                })
                .withEndActions(() -> {
                    if (mExpandedBubble != null
                            && mExpandedBubble.getBubbleBarExpandedView() != null) {
                        mExpandedBubble.getBubbleBarExpandedView().setAnimationMatrix(null);
                    }
                    if (endRunnable != null) {
                        endRunnable.run();
                    }
                })
                .start();
        mExpandedViewAlphaAnimator.reverse();
    }

    private void updateExpandedView() {
        if (mExpandedBubble == null || mExpandedBubble.getBubbleBarExpandedView() == null) {
            Log.w(TAG, "Trying to update the expanded view without a bubble");
            return;
        }
        BubbleBarExpandedView bbev = mExpandedBubble.getBubbleBarExpandedView();

        final int padding = mPositioner.getBubbleBarExpandedViewPadding();
        final int width = mPositioner.getExpandedViewWidthForBubbleBar();
        final int height = mPositioner.getExpandedViewHeightForBubbleBar();
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) bbev.getLayoutParams();
        lp.width = width;
        lp.height = height;
        bbev.setLayoutParams(lp);
        if (mLayerView.isOnLeft()) {
            bbev.setX(mPositioner.getInsets().left + padding);
        } else {
            bbev.setX(mPositioner.getAvailableRect().width() - width - padding);
        }
        bbev.setY(mPositioner.getInsets().top + padding);
        bbev.updateLocation();
    }
}
