/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.wm.shell.bubbles.animation;

import static com.android.wm.shell.bubbles.BubblePositioner.NUM_VISIBLE_WHEN_RESTING;

import android.content.res.Resources;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.Rect;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.SpringForce;

import com.android.wm.shell.R;
import com.android.wm.shell.animation.Interpolators;
import com.android.wm.shell.animation.PhysicsAnimator;
import com.android.wm.shell.bubbles.BubblePositioner;
import com.android.wm.shell.common.magnetictarget.MagnetizedObject;

import com.google.android.collect.Sets;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Set;

/**
 * Animation controller for bubbles when they're in their expanded state, or animating to/from the
 * expanded state. This controls the expansion animation as well as bubbles 'dragging out' to be
 * dismissed.
 */
public class ExpandedAnimationController
        extends PhysicsAnimationLayout.PhysicsAnimationController {

    /**
     * How much to translate the bubbles when they're animating in/out. This value is multiplied by
     * the bubble size.
     */
    private static final int ANIMATE_TRANSLATION_FACTOR = 4;

    /** Duration of the expand/collapse target path animation. */
    public static final int EXPAND_COLLAPSE_TARGET_ANIM_DURATION = 175;

    /** Damping ratio for expand/collapse spring. */
    private static final float DAMPING_RATIO_MEDIUM_LOW_BOUNCY = 0.65f;

    /** Stiffness for the expand/collapse path-following animation. */
    private static final int EXPAND_COLLAPSE_ANIM_STIFFNESS = 1000;

    /** What percentage of the screen to use when centering the bubbles in landscape. */
    private static final float CENTER_BUBBLES_LANDSCAPE_PERCENT = 0.66f;

    /**
     * Velocity required to dismiss an individual bubble without dragging it into the dismiss
     * target.
     */
    private static final float FLING_TO_DISMISS_MIN_VELOCITY = 6000f;

    private final PhysicsAnimator.SpringConfig mAnimateOutSpringConfig =
            new PhysicsAnimator.SpringConfig(
                    EXPAND_COLLAPSE_ANIM_STIFFNESS, SpringForce.DAMPING_RATIO_NO_BOUNCY);

    /** Horizontal offset between bubbles, which we need to know to re-stack them. */
    private float mStackOffsetPx;
    /** Space between status bar and bubbles in the expanded state. */
    private float mBubblePaddingTop;
    /** Size of each bubble. */
    private float mBubbleSizePx;
    /** Max number of bubbles shown in row above expanded view. */
    private int mBubblesMaxRendered;
    /** Max amount of space to have between bubbles when expanded. */
    private int mBubblesMaxSpace;
    /** Amount of space between the bubbles when expanded. */
    private float mSpaceBetweenBubbles;
    /** Whether the expand / collapse animation is running. */
    private boolean mAnimatingExpand = false;

    /**
     * Whether we are animating other Bubbles UI elements out in preparation for a call to
     * {@link #collapseBackToStack}. If true, we won't animate bubbles in response to adds or
     * reorders.
     */
    private boolean mPreparingToCollapse = false;

    private boolean mAnimatingCollapse = false;
    @Nullable
    private Runnable mAfterExpand;
    private Runnable mAfterCollapse;
    private PointF mCollapsePoint;

    /**
     * Whether the dragged out bubble is springing towards the touch point, rather than using the
     * default behavior of moving directly to the touch point.
     *
     * This happens when the user's finger exits the dismiss area while the bubble is magnetized to
     * the center. Since the touch point differs from the bubble location, we need to animate the
     * bubble back to the touch point to avoid a jarring instant location change from the center of
     * the target to the touch point just outside the target bounds.
     */
    private boolean mSpringingBubbleToTouch = false;

    /**
     * Whether to spring the bubble to the next touch event coordinates. This is used to animate the
     * bubble out of the magnetic dismiss target to the touch location.
     *
     * Once it 'catches up' and the animation ends, we'll revert to moving it directly.
     */
    private boolean mSpringToTouchOnNextMotionEvent = false;

    /** The bubble currently being dragged out of the row (to potentially be dismissed). */
    private MagnetizedObject<View> mMagnetizedBubbleDraggingOut;

    private int mExpandedViewPadding;

    /**
     * Callback to run whenever any bubble is animated out. The BubbleStackView will check if the
     * end of this animation means we have no bubbles left, and notify the BubbleController.
     */
    private Runnable mOnBubbleAnimatedOutAction;

    private BubblePositioner mPositioner;

    public ExpandedAnimationController(BubblePositioner positioner, int expandedViewPadding,
            Runnable onBubbleAnimatedOutAction) {
        mPositioner = positioner;
        updateResources();
        mExpandedViewPadding = expandedViewPadding;
        mOnBubbleAnimatedOutAction = onBubbleAnimatedOutAction;
        mCollapsePoint = mPositioner.getDefaultStartPosition();
    }

    /**
     * Whether the individual bubble has been dragged out of the row of bubbles far enough to cause
     * the rest of the bubbles to animate to fill the gap.
     */
    private boolean mBubbleDraggedOutEnough = false;

    /** End action to run when the lead bubble's expansion animation completes. */
    @Nullable
    private Runnable mLeadBubbleEndAction;

    /**
     * Animates expanding the bubbles into a row along the top of the screen, optionally running an
     * end action when the entire animation completes, and an end action when the lead bubble's
     * animation ends.
     */
    public void expandFromStack(
            @Nullable Runnable after, @Nullable Runnable leadBubbleEndAction) {
        mPreparingToCollapse = false;
        mAnimatingCollapse = false;
        mAnimatingExpand = true;
        mAfterExpand = after;
        mLeadBubbleEndAction = leadBubbleEndAction;

        startOrUpdatePathAnimation(true /* expanding */);
    }

    /**
     * Animates expanding the bubbles into a row along the top of the screen.
     */
    public void expandFromStack(@Nullable Runnable after) {
        expandFromStack(after, null /* leadBubbleEndAction */);
    }

    /**
     * Sets that we're animating the stack collapsed, but haven't yet called
     * {@link #collapseBackToStack}. This will temporarily suspend animations for bubbles that are
     * added or re-ordered, since the upcoming collapse animation will handle positioning those
     * bubbles in the collapsed stack.
     */
    public void notifyPreparingToCollapse() {
        mPreparingToCollapse = true;
    }

    /** Animate collapsing the bubbles back to their stacked position. */
    public void collapseBackToStack(PointF collapsePoint, Runnable after) {
        mAnimatingExpand = false;
        mPreparingToCollapse = false;
        mAnimatingCollapse = true;
        mAfterCollapse = after;
        mCollapsePoint = collapsePoint;

        startOrUpdatePathAnimation(false /* expanding */);
    }

    /**
     * Update effective screen width based on current orientation.
     */
    public void updateResources() {
        if (mLayout == null) {
            return;
        }
        Resources res = mLayout.getContext().getResources();
        mBubblePaddingTop = res.getDimensionPixelSize(R.dimen.bubble_padding_top);
        mStackOffsetPx = res.getDimensionPixelSize(R.dimen.bubble_stack_offset);
        mBubbleSizePx = mPositioner.getBubbleSize();
        mBubblesMaxRendered = mPositioner.getMaxBubbles();
        mSpaceBetweenBubbles = res.getDimensionPixelSize(R.dimen.bubble_spacing);
    }

    /**
     * Animates the bubbles along a curved path, either to expand them along the top or collapse
     * them back into a stack.
     */
    private void startOrUpdatePathAnimation(boolean expanding) {
        Runnable after;

        if (expanding) {
            after = () -> {
                mAnimatingExpand = false;

                if (mAfterExpand != null) {
                    mAfterExpand.run();
                }

                mAfterExpand = null;

                // Update bubble positions in case any bubbles were added or removed during the
                // expansion animation.
                updateBubblePositions();
            };
        } else {
            after = () -> {
                mAnimatingCollapse = false;

                if (mAfterCollapse != null) {
                    mAfterCollapse.run();
                }

                mAfterCollapse = null;
            };
        }

        // Animate each bubble individually, since each path will end in a different spot.
        animationsForChildrenFromIndex(0, (index, animation) -> {
            final View bubble = mLayout.getChildAt(index);

            // Start a path at the bubble's current position.
            final Path path = new Path();
            path.moveTo(bubble.getTranslationX(), bubble.getTranslationY());

            final float expandedY = mPositioner.showBubblesVertically()
                    ? getBubbleXOrYForOrientation(index)
                    : getExpandedY();
            if (expanding) {
                // If we're expanding, first draw a line from the bubble's current position to the
                // top of the screen.
                path.lineTo(bubble.getTranslationX(), expandedY);
                // Then, draw a line across the screen to the bubble's resting position.
                if (mPositioner.showBubblesVertically()) {
                    Rect availableRect = mPositioner.getAvailableRect();
                    boolean onLeft = mCollapsePoint != null
                            && mCollapsePoint.x < (availableRect.width() / 2f);
                    float translationX = onLeft
                            ? availableRect.left
                            : availableRect.right - mBubbleSizePx;
                    path.lineTo(translationX, getBubbleXOrYForOrientation(index));
                } else {
                    path.lineTo(getBubbleXOrYForOrientation(index), expandedY);
                }
            } else {
                final float stackedX = mCollapsePoint.x;

                // If we're collapsing, draw a line from the bubble's current position to the side
                // of the screen where the bubble will be stacked.
                path.lineTo(stackedX, expandedY);

                // Then, draw a line down to the stack position.
                path.lineTo(stackedX, mCollapsePoint.y
                        + Math.min(index, NUM_VISIBLE_WHEN_RESTING - 1) * mStackOffsetPx);
            }

            // The lead bubble should be the bubble with the longest distance to travel when we're
            // expanding, and the bubble with the shortest distance to travel when we're collapsing.
            // During expansion from the left side, the last bubble has to travel to the far right
            // side, so we have it lead and 'pull' the rest of the bubbles into place. From the
            // right side, the first bubble is traveling to the top left, so it leads. During
            // collapse to the left, the first bubble has the shortest travel time back to the stack
            // position, so it leads (and vice versa).
            final boolean firstBubbleLeads =
                    (expanding && !mLayout.isFirstChildXLeftOfCenter(bubble.getTranslationX()))
                            || (!expanding && mLayout.isFirstChildXLeftOfCenter(mCollapsePoint.x));
            final int startDelay = firstBubbleLeads
                    ? (index * 10)
                    : ((mLayout.getChildCount() - index) * 10);

            final boolean isLeadBubble =
                    (firstBubbleLeads && index == 0)
                            || (!firstBubbleLeads && index == mLayout.getChildCount() - 1);

            animation
                    .followAnimatedTargetAlongPath(
                            path,
                            EXPAND_COLLAPSE_TARGET_ANIM_DURATION /* targetAnimDuration */,
                            Interpolators.LINEAR /* targetAnimInterpolator */,
                            isLeadBubble ? mLeadBubbleEndAction : null /* endAction */,
                            () -> mLeadBubbleEndAction = null /* endAction */)
                    .withStartDelay(startDelay)
                    .withStiffness(EXPAND_COLLAPSE_ANIM_STIFFNESS);
        }).startAll(after);
    }

    /** Notifies the controller that the dragged-out bubble was unstuck from the magnetic target. */
    public void onUnstuckFromTarget() {
        mSpringToTouchOnNextMotionEvent = true;
    }

    /**
     * Prepares the given bubble view to be dragged out, using the provided magnetic target and
     * listener.
     */
    public void prepareForBubbleDrag(
            View bubble,
            MagnetizedObject.MagneticTarget target,
            MagnetizedObject.MagnetListener listener) {
        mLayout.cancelAnimationsOnView(bubble);

        bubble.setTranslationZ(Short.MAX_VALUE);
        mMagnetizedBubbleDraggingOut = new MagnetizedObject<View>(
                mLayout.getContext(), bubble,
                DynamicAnimation.TRANSLATION_X, DynamicAnimation.TRANSLATION_Y) {
            @Override
            public float getWidth(@NonNull View underlyingObject) {
                return mBubbleSizePx;
            }

            @Override
            public float getHeight(@NonNull View underlyingObject) {
                return mBubbleSizePx;
            }

            @Override
            public void getLocationOnScreen(@NonNull View underlyingObject, @NonNull int[] loc) {
                loc[0] = (int) bubble.getTranslationX();
                loc[1] = (int) bubble.getTranslationY();
            }
        };
        mMagnetizedBubbleDraggingOut.addTarget(target);
        mMagnetizedBubbleDraggingOut.setMagnetListener(listener);
        mMagnetizedBubbleDraggingOut.setHapticsEnabled(true);
        mMagnetizedBubbleDraggingOut.setFlingToTargetMinVelocity(FLING_TO_DISMISS_MIN_VELOCITY);
    }

    private void springBubbleTo(View bubble, float x, float y) {
        animationForChild(bubble)
                .translationX(x)
                .translationY(y)
                .withStiffness(SpringForce.STIFFNESS_HIGH)
                .start();
    }

    /**
     * Drags an individual bubble to the given coordinates. Bubbles to the right will animate to
     * take its place once it's dragged out of the row of bubbles, and animate out of the way if the
     * bubble is dragged back into the row.
     */
    public void dragBubbleOut(View bubbleView, float x, float y) {
        if (mSpringToTouchOnNextMotionEvent) {
            springBubbleTo(mMagnetizedBubbleDraggingOut.getUnderlyingObject(), x, y);
            mSpringToTouchOnNextMotionEvent = false;
            mSpringingBubbleToTouch = true;
        } else if (mSpringingBubbleToTouch) {
            if (mLayout.arePropertiesAnimatingOnView(
                    bubbleView, DynamicAnimation.TRANSLATION_X, DynamicAnimation.TRANSLATION_Y)) {
                springBubbleTo(mMagnetizedBubbleDraggingOut.getUnderlyingObject(), x, y);
            } else {
                mSpringingBubbleToTouch = false;
            }
        }

        if (!mSpringingBubbleToTouch && !mMagnetizedBubbleDraggingOut.getObjectStuckToTarget()) {
            bubbleView.setTranslationX(x);
            bubbleView.setTranslationY(y);
        }

        final boolean draggedOutEnough =
                y > getExpandedY() + mBubbleSizePx || y < getExpandedY() - mBubbleSizePx;
        if (draggedOutEnough != mBubbleDraggedOutEnough) {
            updateBubblePositions();
            mBubbleDraggedOutEnough = draggedOutEnough;
        }
    }

    /** Plays a dismiss animation on the dragged out bubble. */
    public void dismissDraggedOutBubble(View bubble, float translationYBy, Runnable after) {
        if (bubble == null) {
            return;
        }
        animationForChild(bubble)
                .withStiffness(SpringForce.STIFFNESS_HIGH)
                .scaleX(0f)
                .scaleY(0f)
                .translationY(bubble.getTranslationY() + translationYBy)
                .alpha(0f, after)
                .start();

        updateBubblePositions();
    }

    @Nullable
    public View getDraggedOutBubble() {
        return mMagnetizedBubbleDraggingOut == null
                ? null
                : mMagnetizedBubbleDraggingOut.getUnderlyingObject();
    }

    /** Returns the MagnetizedObject instance for the dragging-out bubble. */
    public MagnetizedObject<View> getMagnetizedBubbleDraggingOut() {
        return mMagnetizedBubbleDraggingOut;
    }

    /**
     * Snaps a bubble back to its position within the bubble row, and animates the rest of the
     * bubbles to accommodate it if it was previously dragged out past the threshold.
     */
    public void snapBubbleBack(View bubbleView, float velX, float velY) {
        if (mLayout == null) {
            return;
        }
        final int index = mLayout.indexOfChild(bubbleView);

        animationForChildAtIndex(index)
                .position(getBubbleXOrYForOrientation(index), getExpandedY())
                .withPositionStartVelocities(velX, velY)
                .start(() -> bubbleView.setTranslationZ(0f) /* after */);

        mMagnetizedBubbleDraggingOut = null;

        updateBubblePositions();
    }

    /** Resets bubble drag out gesture flags. */
    public void onGestureFinished() {
        mBubbleDraggedOutEnough = false;
        mMagnetizedBubbleDraggingOut = null;
        updateBubblePositions();
    }

    /**
     * Animates the bubbles to {@link #getExpandedY()} position. Used in response to IME showing.
     */
    public void updateYPosition(Runnable after) {
        if (mLayout == null) return;
        animationsForChildrenFromIndex(
                0, (i, anim) -> anim.translationY(getExpandedY())).startAll(after);
    }

    /** The Y value of the row of expanded bubbles. */
    public float getExpandedY() {
        return mPositioner.getAvailableRect().top + mBubblePaddingTop;
    }

    /** Description of current animation controller state. */
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("ExpandedAnimationController state:");
        pw.print("  isActive:          "); pw.println(isActiveController());
        pw.print("  animatingExpand:   "); pw.println(mAnimatingExpand);
        pw.print("  animatingCollapse: "); pw.println(mAnimatingCollapse);
        pw.print("  springingBubble:   "); pw.println(mSpringingBubbleToTouch);
    }

    @Override
    void onActiveControllerForLayout(PhysicsAnimationLayout layout) {
        updateResources();

        // Ensure that all child views are at 1x scale, and visible, in case they were animating
        // in.
        mLayout.setVisibility(View.VISIBLE);
        animationsForChildrenFromIndex(0 /* startIndex */, (index, animation) ->
                animation.scaleX(1f).scaleY(1f).alpha(1f)).startAll();
    }

    @Override
    Set<DynamicAnimation.ViewProperty> getAnimatedProperties() {
        return Sets.newHashSet(
                DynamicAnimation.TRANSLATION_X,
                DynamicAnimation.TRANSLATION_Y,
                DynamicAnimation.SCALE_X,
                DynamicAnimation.SCALE_Y,
                DynamicAnimation.ALPHA);
    }

    @Override
    int getNextAnimationInChain(DynamicAnimation.ViewProperty property, int index) {
        return NONE;
    }

    @Override
    float getOffsetForChainedPropertyAnimation(DynamicAnimation.ViewProperty property, int index) {
        return 0;
    }

    @Override
    SpringForce getSpringForce(DynamicAnimation.ViewProperty property, View view) {
        return new SpringForce()
                .setDampingRatio(DAMPING_RATIO_MEDIUM_LOW_BOUNCY)
                .setStiffness(SpringForce.STIFFNESS_LOW);
    }

    @Override
    void onChildAdded(View child, int index) {
        // If a bubble is added while the expand/collapse animations are playing, update the
        // animation to include the new bubble.
        if (mAnimatingExpand) {
            startOrUpdatePathAnimation(true /* expanding */);
        } else if (mAnimatingCollapse) {
            startOrUpdatePathAnimation(false /* expanding */);
        } else if (mPositioner.showBubblesVertically()) {
            child.setTranslationY(getBubbleXOrYForOrientation(index));
            if (!mPreparingToCollapse) {
                // Only animate if we're not collapsing as that animation will handle placing the
                // new bubble in the stacked position.
                Rect availableRect = mPositioner.getAvailableRect();
                boolean onLeft = mCollapsePoint != null
                        && mCollapsePoint.x < (availableRect.width() / 2f);
                float fromX = onLeft
                        ? -mBubbleSizePx * ANIMATE_TRANSLATION_FACTOR
                        : availableRect.right + mBubbleSizePx * ANIMATE_TRANSLATION_FACTOR;
                float toX = onLeft
                        ? availableRect.left + mExpandedViewPadding
                        : availableRect.right - mBubbleSizePx - mExpandedViewPadding;
                animationForChild(child)
                        .translationX(fromX, toX)
                        .start();
                updateBubblePositions();
            }
        } else {
            child.setTranslationX(getBubbleXOrYForOrientation(index));
            if (!mPreparingToCollapse) {
                // Only animate if we're not collapsing as that animation will handle placing the
                // new bubble in the stacked position.
                float toY = getExpandedY();
                float fromY = getExpandedY() - mBubbleSizePx * ANIMATE_TRANSLATION_FACTOR;
                animationForChild(child)
                        .translationY(fromY, toY)
                        .start();
                updateBubblePositions();
            }
        }
    }

    @Override
    void onChildRemoved(View child, int index, Runnable finishRemoval) {
        // If we're removing the dragged-out bubble, that means it got dismissed.
        if (child.equals(getDraggedOutBubble())) {
            mMagnetizedBubbleDraggingOut = null;
            finishRemoval.run();
            mOnBubbleAnimatedOutAction.run();
        } else {
            PhysicsAnimator.getInstance(child)
                    .spring(DynamicAnimation.ALPHA, 0f)
                    .spring(DynamicAnimation.SCALE_X, 0f, mAnimateOutSpringConfig)
                    .spring(DynamicAnimation.SCALE_Y, 0f, mAnimateOutSpringConfig)
                    .withEndActions(finishRemoval, mOnBubbleAnimatedOutAction)
                    .start();
        }

        // Animate all the other bubbles to their new positions sans this bubble.
        updateBubblePositions();
    }

    @Override
    void onChildReordered(View child, int oldIndex, int newIndex) {
        if (mPreparingToCollapse) {
            // If a re-order is received while we're preparing to collapse, ignore it. Once started,
            // the collapse animation will animate all of the bubbles to their correct (stacked)
            // position.
            return;
        }

        if (mAnimatingCollapse) {
            // If a re-order is received during collapse, update the animation so that the bubbles
            // end up in the correct (stacked) position.
            startOrUpdatePathAnimation(false /* expanding */);
        } else {
            // Otherwise, animate the bubbles around to reflect their new order.
            updateBubblePositions();
        }
    }

    private void updateBubblePositions() {
        if (mAnimatingExpand || mAnimatingCollapse) {
            return;
        }

        for (int i = 0; i < mLayout.getChildCount(); i++) {
            final View bubble = mLayout.getChildAt(i);

            // Don't animate the dragging out bubble, or it'll jump around while being dragged. It
            // will be snapped to the correct X value after the drag (if it's not dismissed).
            if (bubble.equals(getDraggedOutBubble())) {
                return;
            }

            if (mPositioner.showBubblesVertically()) {
                Rect availableRect = mPositioner.getAvailableRect();
                boolean onLeft = mCollapsePoint != null
                        && mCollapsePoint.x < (availableRect.width() / 2f);
                animationForChild(bubble)
                        .translationX(onLeft
                                ? availableRect.left
                                : availableRect.right - mBubbleSizePx)
                        .translationY(getBubbleXOrYForOrientation(i))
                        .start();
            } else {
                animationForChild(bubble)
                        .translationX(getBubbleXOrYForOrientation(i))
                        .translationY(getExpandedY())
                        .start();
            }
        }
    }

    // TODO - could move to method on bubblePositioner if mSpaceBetweenBubbles gets moved
    /**
     * When bubbles are expanded in portrait, they display at the top of the screen in a horizontal
     * row. When in landscape or on a large screen, they show at the left or right side in a
     * vertical row. This method accounts for screen orientation and will return an x or y value
     * for the position of the bubble in the row.
     *
     * @param index Bubble index in row.
     * @return the y position of the bubble if showing vertically and the x position if showing
     * horizontally.
     */
    public float getBubbleXOrYForOrientation(int index) {
        if (mLayout == null) {
            return 0;
        }
        final float positionInBar = index * (mBubbleSizePx + mSpaceBetweenBubbles);
        Rect availableRect = mPositioner.getAvailableRect();
        final boolean isLandscape = mPositioner.showBubblesVertically();
        final float expandedStackSize = (mLayout.getChildCount() * mBubbleSizePx)
                + ((mLayout.getChildCount() - 1) * mSpaceBetweenBubbles);
        final float centerPosition = isLandscape
                ? availableRect.centerY()
                : availableRect.centerX();
        final float rowStart = centerPosition - (expandedStackSize / 2f);
        return rowStart + positionInBar;
    }
}
