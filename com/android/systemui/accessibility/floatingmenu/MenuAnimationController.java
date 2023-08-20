/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.accessibility.floatingmenu;

import static java.util.Objects.requireNonNull;

import android.animation.ValueAnimator;
import android.graphics.PointF;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.OvershootInterpolator;
import android.view.animation.TranslateAnimation;

import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.FlingAnimation;
import androidx.dynamicanimation.animation.FloatPropertyCompat;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;
import androidx.recyclerview.widget.RecyclerView;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.Preconditions;

import java.util.HashMap;

/**
 * Controls the interaction animations of the {@link MenuView}. Also, it will use the relative
 * coordinate based on the {@link MenuViewLayer} to compute the offset of the {@link MenuView}.
 */
class MenuAnimationController {
    private static final String TAG = "MenuAnimationController";
    private static final boolean DEBUG = false;
    private static final float MIN_PERCENT = 0.0f;
    private static final float MAX_PERCENT = 1.0f;
    private static final float COMPLETELY_OPAQUE = 1.0f;
    private static final float COMPLETELY_TRANSPARENT = 0.0f;
    private static final float SCALE_SHRINK = 0.0f;
    private static final float SCALE_GROW = 1.0f;
    private static final float FLING_FRICTION_SCALAR = 1.9f;
    private static final float DEFAULT_FRICTION = 4.2f;
    private static final float SPRING_AFTER_FLING_DAMPING_RATIO = 0.85f;
    private static final float SPRING_STIFFNESS = 700f;
    private static final float ESCAPE_VELOCITY = 750f;
    // Make tucked animation by using translation X relative to the view itself.
    private static final float ANIMATION_TO_X_VALUE = 0.5f;

    private static final int ANIMATION_START_OFFSET_MS = 600;
    private static final int ANIMATION_DURATION_MS = 600;
    private static final int FADE_OUT_DURATION_MS = 1000;
    private static final int FADE_EFFECT_DURATION_MS = 3000;

    private final MenuView mMenuView;
    private final ValueAnimator mFadeOutAnimator;
    private final Handler mHandler;
    private boolean mIsFadeEffectEnabled;
    private DismissAnimationController.DismissCallback mDismissCallback;
    private Runnable mSpringAnimationsEndAction;

    // Cache the animations state of {@link DynamicAnimation.TRANSLATION_X} and {@link
    // DynamicAnimation.TRANSLATION_Y} to be well controlled by the touch handler
    @VisibleForTesting
    final HashMap<DynamicAnimation.ViewProperty, DynamicAnimation> mPositionAnimations =
            new HashMap<>();

    MenuAnimationController(MenuView menuView) {
        mMenuView = menuView;

        mHandler = createUiHandler();
        mFadeOutAnimator = new ValueAnimator();
        mFadeOutAnimator.setDuration(FADE_OUT_DURATION_MS);
        mFadeOutAnimator.addUpdateListener(
                (animation) -> menuView.setAlpha((float) animation.getAnimatedValue()));
    }

    void moveToPosition(PointF position) {
        moveToPositionX(position.x);
        moveToPositionY(position.y);
    }

    void moveToPositionX(float positionX) {
        DynamicAnimation.TRANSLATION_X.setValue(mMenuView, positionX);
    }

    private void moveToPositionY(float positionY) {
        DynamicAnimation.TRANSLATION_Y.setValue(mMenuView, positionY);
    }

    void moveToPositionYIfNeeded(float positionY) {
        // If the list view was out of screen bounds, it would allow users to nest scroll inside
        // and avoid conflicting with outer scroll.
        final RecyclerView listView = (RecyclerView) mMenuView.getChildAt(/* index= */ 0);
        if (listView.getOverScrollMode() == View.OVER_SCROLL_NEVER) {
            moveToPositionY(positionY);
        }
    }

    /**
     * Sets the action to be called when the all dynamic animations are completed.
     */
    void setSpringAnimationsEndAction(Runnable runnable) {
        mSpringAnimationsEndAction = runnable;
    }

    void setDismissCallback(
            DismissAnimationController.DismissCallback dismissCallback) {
        mDismissCallback = dismissCallback;
    }

    void moveToTopLeftPosition() {
        mMenuView.updateMenuMoveToTucked(/* isMoveToTucked= */ false);
        final Rect draggableBounds = mMenuView.getMenuDraggableBounds();
        moveAndPersistPosition(new PointF(draggableBounds.left, draggableBounds.top));
    }

    void moveToTopRightPosition() {
        mMenuView.updateMenuMoveToTucked(/* isMoveToTucked= */ false);
        final Rect draggableBounds = mMenuView.getMenuDraggableBounds();
        moveAndPersistPosition(new PointF(draggableBounds.right, draggableBounds.top));
    }

    void moveToBottomLeftPosition() {
        mMenuView.updateMenuMoveToTucked(/* isMoveToTucked= */ false);
        final Rect draggableBounds = mMenuView.getMenuDraggableBounds();
        moveAndPersistPosition(new PointF(draggableBounds.left, draggableBounds.bottom));
    }

    void moveToBottomRightPosition() {
        mMenuView.updateMenuMoveToTucked(/* isMoveToTucked= */ false);
        final Rect draggableBounds = mMenuView.getMenuDraggableBounds();
        moveAndPersistPosition(new PointF(draggableBounds.right, draggableBounds.bottom));
    }

    void moveAndPersistPosition(PointF position) {
        moveToPosition(position);
        mMenuView.onBoundsInParentChanged((int) position.x, (int) position.y);
        constrainPositionAndUpdate(position);
    }

    void removeMenu() {
        Preconditions.checkArgument(mDismissCallback != null,
                "The dismiss callback should be initialized first.");

        mDismissCallback.onDismiss();
    }

    void flingMenuThenSpringToEdge(float x, float velocityX, float velocityY) {
        final boolean shouldMenuFlingLeft = isOnLeftSide()
                ? velocityX < ESCAPE_VELOCITY
                : velocityX < -ESCAPE_VELOCITY;

        final Rect draggableBounds = mMenuView.getMenuDraggableBounds();
        final float finalPositionX = shouldMenuFlingLeft
                ? draggableBounds.left : draggableBounds.right;

        final float minimumVelocityToReachEdge =
                (finalPositionX - x) * (FLING_FRICTION_SCALAR * DEFAULT_FRICTION);

        final float startXVelocity = shouldMenuFlingLeft
                ? Math.min(minimumVelocityToReachEdge, velocityX)
                : Math.max(minimumVelocityToReachEdge, velocityX);

        flingThenSpringMenuWith(DynamicAnimation.TRANSLATION_X,
                startXVelocity,
                FLING_FRICTION_SCALAR,
                new SpringForce()
                        .setStiffness(SPRING_STIFFNESS)
                        .setDampingRatio(SPRING_AFTER_FLING_DAMPING_RATIO),
                finalPositionX);

        flingThenSpringMenuWith(DynamicAnimation.TRANSLATION_Y,
                velocityY,
                FLING_FRICTION_SCALAR,
                new SpringForce()
                        .setStiffness(SPRING_STIFFNESS)
                        .setDampingRatio(SPRING_AFTER_FLING_DAMPING_RATIO),
                /* finalPosition= */ null);
    }

    private void flingThenSpringMenuWith(DynamicAnimation.ViewProperty property, float velocity,
            float friction, SpringForce spring, Float finalPosition) {

        final MenuPositionProperty menuPositionProperty = new MenuPositionProperty(property);
        final float currentValue = menuPositionProperty.getValue(mMenuView);
        final Rect bounds = mMenuView.getMenuDraggableBounds();
        final float min =
                property.equals(DynamicAnimation.TRANSLATION_X)
                        ? bounds.left
                        : bounds.top;
        final float max =
                property.equals(DynamicAnimation.TRANSLATION_X)
                        ? bounds.right
                        : bounds.bottom;

        final FlingAnimation flingAnimation = createFlingAnimation(mMenuView, menuPositionProperty);
        flingAnimation.setFriction(friction)
                .setStartVelocity(velocity)
                .setMinValue(Math.min(currentValue, min))
                .setMaxValue(Math.max(currentValue, max))
                .addEndListener((animation, canceled, endValue, endVelocity) -> {
                    if (canceled) {
                        if (DEBUG) {
                            Log.d(TAG, "The fling animation was canceled.");
                        }

                        return;
                    }

                    final float endPosition = finalPosition != null
                            ? finalPosition
                            : Math.max(min, Math.min(max, endValue));
                    springMenuWith(property, spring, endVelocity, endPosition);
                });

        cancelAnimation(property);
        mPositionAnimations.put(property, flingAnimation);
        flingAnimation.start();
    }

    @VisibleForTesting
    FlingAnimation createFlingAnimation(MenuView menuView,
            MenuPositionProperty menuPositionProperty) {
        return new FlingAnimation(menuView, menuPositionProperty);
    }

    @VisibleForTesting
    void springMenuWith(DynamicAnimation.ViewProperty property, SpringForce spring,
            float velocity, float finalPosition) {
        final MenuPositionProperty menuPositionProperty = new MenuPositionProperty(property);
        final SpringAnimation springAnimation =
                new SpringAnimation(mMenuView, menuPositionProperty)
                        .setSpring(spring)
                        .addEndListener((animation, canceled, endValue, endVelocity) -> {
                            if (canceled || endValue != finalPosition) {
                                return;
                            }

                            final boolean areAnimationsRunning =
                                    mPositionAnimations.values().stream().anyMatch(
                                            DynamicAnimation::isRunning);
                            if (!areAnimationsRunning) {
                                onSpringAnimationsEnd(new PointF(mMenuView.getTranslationX(),
                                        mMenuView.getTranslationY()));
                            }
                        })
                        .setStartVelocity(velocity);

        cancelAnimation(property);
        mPositionAnimations.put(property, springAnimation);
        springAnimation.animateToFinalPosition(finalPosition);
    }

    /**
     * Determines whether to hide the menu to the edge of the screen with the given current
     * translation x of the menu view. It should be used when receiving the action up touch event.
     *
     * @param currentXTranslation the current translation x of the menu view.
     * @return true if the menu would be hidden to the edge, otherwise false.
     */
    boolean maybeMoveToEdgeAndHide(float currentXTranslation) {
        final Rect draggableBounds = mMenuView.getMenuDraggableBounds();

        // If the translation x is zero, it should be at the left of the bound.
        if (currentXTranslation < draggableBounds.left
                || currentXTranslation > draggableBounds.right) {
            constrainPositionAndUpdate(
                    new PointF(mMenuView.getTranslationX(), mMenuView.getTranslationY()));
            moveToEdgeAndHide();
            return true;
        }

        fadeOutIfEnabled();
        return false;
    }

    boolean isOnLeftSide() {
        return mMenuView.getTranslationX() < mMenuView.getMenuDraggableBounds().centerX();
    }

    boolean isMoveToTucked() {
        return mMenuView.isMoveToTucked();
    }

    void moveToEdgeAndHide() {
        mMenuView.updateMenuMoveToTucked(/* isMoveToTucked= */ true);

        final PointF position = mMenuView.getMenuPosition();
        final float menuHalfWidth = mMenuView.getMenuWidth() / 2.0f;
        final float endX = isOnLeftSide()
                ? position.x - menuHalfWidth
                : position.x + menuHalfWidth;
        moveToPosition(new PointF(endX, position.y));

        // Keep the touch region let users could click extra space to pop up the menu view
        // from the screen edge
        mMenuView.onBoundsInParentChanged((int) position.x, (int) position.y);

        fadeOutIfEnabled();
    }

    void moveOutEdgeAndShow() {
        mMenuView.updateMenuMoveToTucked(/* isMoveToTucked= */ false);

        mMenuView.onPositionChanged();
        mMenuView.onEdgeChangedIfNeeded();
    }

    void cancelAnimations() {
        cancelAnimation(DynamicAnimation.TRANSLATION_X);
        cancelAnimation(DynamicAnimation.TRANSLATION_Y);
    }

    private void cancelAnimation(DynamicAnimation.ViewProperty property) {
        if (!mPositionAnimations.containsKey(property)) {
            return;
        }

        mPositionAnimations.get(property).cancel();
    }

    void onDraggingStart() {
        mMenuView.onDraggingStart();
    }

    void startShrinkAnimation(Runnable endAction) {
        mMenuView.animate().cancel();

        mMenuView.animate()
                .scaleX(SCALE_SHRINK)
                .scaleY(SCALE_SHRINK)
                .alpha(COMPLETELY_TRANSPARENT)
                .translationY(mMenuView.getTranslationY())
                .withEndAction(endAction).start();
    }

    void startGrowAnimation() {
        mMenuView.animate().cancel();

        mMenuView.animate()
                .scaleX(SCALE_GROW)
                .scaleY(SCALE_GROW)
                .alpha(COMPLETELY_OPAQUE)
                .translationY(mMenuView.getTranslationY())
                .start();
    }

    private void onSpringAnimationsEnd(PointF position) {
        mMenuView.onBoundsInParentChanged((int) position.x, (int) position.y);
        constrainPositionAndUpdate(position);

        fadeOutIfEnabled();

        if (mSpringAnimationsEndAction != null) {
            mSpringAnimationsEndAction.run();
        }
    }

    private void constrainPositionAndUpdate(PointF position) {
        final Rect draggableBounds = mMenuView.getMenuDraggableBoundsExcludeIme();
        // Have the space gap margin between the top bound and the menu view, so actually the
        // position y range needs to cut the margin.
        position.offset(-draggableBounds.left, -draggableBounds.top);

        final float percentageX = position.x < draggableBounds.centerX()
                ? MIN_PERCENT : MAX_PERCENT;

        final float percentageY = position.y < 0 || draggableBounds.height() == 0
                ? MIN_PERCENT
                : Math.min(MAX_PERCENT, position.y / draggableBounds.height());
        mMenuView.persistPositionAndUpdateEdge(new Position(percentageX, percentageY));
    }

    void updateOpacityWith(boolean isFadeEffectEnabled, float newOpacityValue) {
        mIsFadeEffectEnabled = isFadeEffectEnabled;

        mHandler.removeCallbacksAndMessages(/* token= */ null);
        mFadeOutAnimator.cancel();
        mFadeOutAnimator.setFloatValues(COMPLETELY_OPAQUE, newOpacityValue);
        mHandler.post(() -> mMenuView.setAlpha(
                mIsFadeEffectEnabled ? newOpacityValue : COMPLETELY_OPAQUE));
    }

    void fadeInNowIfEnabled() {
        if (!mIsFadeEffectEnabled) {
            return;
        }

        cancelAndRemoveCallbacksAndMessages();
        mMenuView.setAlpha(COMPLETELY_OPAQUE);
    }

    void fadeOutIfEnabled() {
        if (!mIsFadeEffectEnabled) {
            return;
        }

        cancelAndRemoveCallbacksAndMessages();
        mHandler.postDelayed(mFadeOutAnimator::start, FADE_EFFECT_DURATION_MS);
    }

    private void cancelAndRemoveCallbacksAndMessages() {
        mFadeOutAnimator.cancel();
        mHandler.removeCallbacksAndMessages(/* token= */ null);
    }

    void startTuckedAnimationPreview() {
        fadeInNowIfEnabled();

        final float toXValue = isOnLeftSide()
                ? -ANIMATION_TO_X_VALUE
                : ANIMATION_TO_X_VALUE;
        final TranslateAnimation animation =
                new TranslateAnimation(Animation.RELATIVE_TO_SELF, 0,
                        Animation.RELATIVE_TO_SELF, toXValue,
                        Animation.RELATIVE_TO_SELF, 0,
                        Animation.RELATIVE_TO_SELF, 0);
        animation.setDuration(ANIMATION_DURATION_MS);
        animation.setRepeatMode(Animation.REVERSE);
        animation.setInterpolator(new OvershootInterpolator());
        animation.setRepeatCount(Animation.INFINITE);
        animation.setStartOffset(ANIMATION_START_OFFSET_MS);

        mMenuView.startAnimation(animation);
    }

    private Handler createUiHandler() {
        return new Handler(requireNonNull(Looper.myLooper(), "looper must not be null"));
    }

    static class MenuPositionProperty
            extends FloatPropertyCompat<MenuView> {
        private final DynamicAnimation.ViewProperty mProperty;

        MenuPositionProperty(DynamicAnimation.ViewProperty property) {
            super(property.toString());
            mProperty = property;
        }

        @Override
        public float getValue(MenuView menuView) {
            return mProperty.getValue(menuView);
        }

        @Override
        public void setValue(MenuView menuView, float value) {
            mProperty.setValue(menuView, value);
        }
    }
}
