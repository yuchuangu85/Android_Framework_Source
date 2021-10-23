/*
 * Copyright (C) 2014 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.statusbar.notification.row;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewOutlineProvider;

import com.android.systemui.R;
import com.android.systemui.statusbar.notification.AnimatableProperty;
import com.android.systemui.statusbar.notification.PropertyAnimator;
import com.android.systemui.statusbar.notification.stack.AnimationProperties;
import com.android.systemui.statusbar.notification.stack.StackStateAnimator;

/**
 * Like {@link ExpandableView}, but setting an outline for the height and clipping.
 */
public abstract class ExpandableOutlineView extends ExpandableView {

    private static final AnimatableProperty TOP_ROUNDNESS = AnimatableProperty.from(
            "topRoundness",
            ExpandableOutlineView::setTopRoundnessInternal,
            ExpandableOutlineView::getCurrentTopRoundness,
            R.id.top_roundess_animator_tag,
            R.id.top_roundess_animator_end_tag,
            R.id.top_roundess_animator_start_tag);
    private static final AnimatableProperty BOTTOM_ROUNDNESS = AnimatableProperty.from(
            "bottomRoundness",
            ExpandableOutlineView::setBottomRoundnessInternal,
            ExpandableOutlineView::getCurrentBottomRoundness,
            R.id.bottom_roundess_animator_tag,
            R.id.bottom_roundess_animator_end_tag,
            R.id.bottom_roundess_animator_start_tag);
    private static final AnimationProperties ROUNDNESS_PROPERTIES =
            new AnimationProperties().setDuration(
                    StackStateAnimator.ANIMATION_DURATION_CORNER_RADIUS);
    private static final Path EMPTY_PATH = new Path();

    private final Rect mOutlineRect = new Rect();
    private final Path mClipPath = new Path();
    private boolean mCustomOutline;
    private float mOutlineAlpha = -1f;
    protected float mOutlineRadius;
    private boolean mAlwaysRoundBothCorners;
    private Path mTmpPath = new Path();
    private float mCurrentBottomRoundness;
    private float mCurrentTopRoundness;
    private float mBottomRoundness;
    private float mTopRoundness;
    private int mBackgroundTop;

    /**
     * {@code false} if the children views of the {@link ExpandableOutlineView} are translated when
     * it is moved. Otherwise, the translation is set on the {@code ExpandableOutlineView} itself.
     */
    protected boolean mDismissUsingRowTranslationX = true;
    private float[] mTmpCornerRadii = new float[8];

    private final ViewOutlineProvider mProvider = new ViewOutlineProvider() {
        @Override
        public void getOutline(View view, Outline outline) {
            if (!mCustomOutline && getCurrentTopRoundness() == 0.0f
                    && getCurrentBottomRoundness() == 0.0f && !mAlwaysRoundBothCorners) {
                // Only when translating just the contents, does the outline need to be shifted.
                int translation = !mDismissUsingRowTranslationX ? (int) getTranslation() : 0;
                int left = Math.max(translation, 0);
                int top = mClipTopAmount + mBackgroundTop;
                int right = getWidth() + Math.min(translation, 0);
                int bottom = Math.max(getActualHeight() - mClipBottomAmount, top);
                outline.setRect(left, top, right, bottom);
            } else {
                Path clipPath = getClipPath(false /* ignoreTranslation */);
                if (clipPath != null) {
                    outline.setPath(clipPath);
                }
            }
            outline.setAlpha(mOutlineAlpha);
        }
    };

    protected Path getClipPath(boolean ignoreTranslation) {
        int left;
        int top;
        int right;
        int bottom;
        int height;
        float topRoundness = mAlwaysRoundBothCorners
                ? mOutlineRadius : getCurrentBackgroundRadiusTop();
        if (!mCustomOutline) {
            // The outline just needs to be shifted if we're translating the contents. Otherwise
            // it's already in the right place.
            int translation = !mDismissUsingRowTranslationX && !ignoreTranslation
                    ? (int) getTranslation() : 0;
            int halfExtraWidth = (int) (mExtraWidthForClipping / 2.0f);
            left = Math.max(translation, 0) - halfExtraWidth;
            top = mClipTopAmount + mBackgroundTop;
            right = getWidth() + halfExtraWidth + Math.min(translation, 0);
            // If the top is rounded we want the bottom to be at most at the top roundness, in order
            // to avoid the shadow changing when scrolling up.
            bottom = Math.max(mMinimumHeightForClipping,
                    Math.max(getActualHeight() - mClipBottomAmount, (int) (top + topRoundness)));
        } else {
            left = mOutlineRect.left;
            top = mOutlineRect.top;
            right = mOutlineRect.right;
            bottom = mOutlineRect.bottom;
        }
        height = bottom - top;
        if (height == 0) {
            return EMPTY_PATH;
        }
        float bottomRoundness = mAlwaysRoundBothCorners
                ? mOutlineRadius : getCurrentBackgroundRadiusBottom();
        if (topRoundness + bottomRoundness > height) {
            float overShoot = topRoundness + bottomRoundness - height;
            float currentTopRoundness = getCurrentTopRoundness();
            float currentBottomRoundness = getCurrentBottomRoundness();
            topRoundness -= overShoot * currentTopRoundness
                    / (currentTopRoundness + currentBottomRoundness);
            bottomRoundness -= overShoot * currentBottomRoundness
                    / (currentTopRoundness + currentBottomRoundness);
        }
        getRoundedRectPath(left, top, right, bottom, topRoundness, bottomRoundness, mTmpPath);
        return mTmpPath;
    }

    public void getRoundedRectPath(int left, int top, int right, int bottom,
            float topRoundness, float bottomRoundness, Path outPath) {
        outPath.reset();
        mTmpCornerRadii[0] = topRoundness;
        mTmpCornerRadii[1] = topRoundness;
        mTmpCornerRadii[2] = topRoundness;
        mTmpCornerRadii[3] = topRoundness;
        mTmpCornerRadii[4] = bottomRoundness;
        mTmpCornerRadii[5] = bottomRoundness;
        mTmpCornerRadii[6] = bottomRoundness;
        mTmpCornerRadii[7] = bottomRoundness;
        outPath.addRoundRect(left, top, right, bottom, mTmpCornerRadii, Path.Direction.CW);
    }

    public ExpandableOutlineView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setOutlineProvider(mProvider);
        initDimens();
    }

    @Override
    protected boolean drawChild(Canvas canvas, View child, long drawingTime) {
        canvas.save();
        if (childNeedsClipping(child)) {
            Path clipPath = getCustomClipPath(child);
            if (clipPath == null) {
                clipPath = getClipPath(false /* ignoreTranslation */);
            }
            if (clipPath != null) {
                canvas.clipPath(clipPath);
            }
        }
        boolean result = super.drawChild(canvas, child, drawingTime);
        canvas.restore();
        return result;
    }

    @Override
    public void setExtraWidthForClipping(float extraWidthForClipping) {
        super.setExtraWidthForClipping(extraWidthForClipping);
        invalidate();
    }

    @Override
    public void setMinimumHeightForClipping(int minimumHeightForClipping) {
        super.setMinimumHeightForClipping(minimumHeightForClipping);
        invalidate();
    }

    protected boolean childNeedsClipping(View child) {
        return false;
    }

    protected boolean isClippingNeeded() {
        // When translating the contents instead of the overall view, we need to make sure we clip
        // rounded to the contents.
        boolean forTranslation = getTranslation() != 0 && !mDismissUsingRowTranslationX;
        return mAlwaysRoundBothCorners || mCustomOutline || forTranslation;
    }

    private void initDimens() {
        Resources res = getResources();
        mOutlineRadius = res.getDimension(R.dimen.notification_shadow_radius);
        mAlwaysRoundBothCorners = res.getBoolean(R.bool.config_clipNotificationsToOutline);
        if (!mAlwaysRoundBothCorners) {
            mOutlineRadius = res.getDimensionPixelSize(R.dimen.notification_corner_radius);
        }
        setClipToOutline(mAlwaysRoundBothCorners);
    }

    @Override
    public boolean setTopRoundness(float topRoundness, boolean animate) {
        if (mTopRoundness != topRoundness) {
            float diff = Math.abs(topRoundness - mTopRoundness);
            mTopRoundness = topRoundness;
            boolean shouldAnimate = animate;
            if (PropertyAnimator.isAnimating(this, TOP_ROUNDNESS) && diff > 0.5f) {
                // Fail safe:
                // when we've been animating previously and we're now getting an update in the
                // other direction, make sure to animate it too, otherwise, the localized updating
                // may make the start larger than 1.0.
                shouldAnimate = true;
            }
            PropertyAnimator.setProperty(this, TOP_ROUNDNESS, topRoundness,
                    ROUNDNESS_PROPERTIES, shouldAnimate);
            return true;
        }
        return false;
    }

    protected void applyRoundness() {
        invalidateOutline();
        invalidate();
    }

    public float getCurrentBackgroundRadiusTop() {
        return getCurrentTopRoundness() * mOutlineRadius;
    }

    public float getCurrentTopRoundness() {
        return mCurrentTopRoundness;
    }

    public float getCurrentBottomRoundness() {
        return mCurrentBottomRoundness;
    }

    public float getCurrentBackgroundRadiusBottom() {
        return getCurrentBottomRoundness() * mOutlineRadius;
    }

    @Override
    public boolean setBottomRoundness(float bottomRoundness, boolean animate) {
        if (mBottomRoundness != bottomRoundness) {
            float diff = Math.abs(bottomRoundness - mBottomRoundness);
            mBottomRoundness = bottomRoundness;
            boolean shouldAnimate = animate;
            if (PropertyAnimator.isAnimating(this, BOTTOM_ROUNDNESS) && diff > 0.5f) {
                // Fail safe:
                // when we've been animating previously and we're now getting an update in the
                // other direction, make sure to animate it too, otherwise, the localized updating
                // may make the start larger than 1.0.
                shouldAnimate = true;
            }
            PropertyAnimator.setProperty(this, BOTTOM_ROUNDNESS, bottomRoundness,
                    ROUNDNESS_PROPERTIES, shouldAnimate);
            return true;
        }
        return false;
    }

    protected void setBackgroundTop(int backgroundTop) {
        if (mBackgroundTop != backgroundTop) {
            mBackgroundTop = backgroundTop;
            invalidateOutline();
        }
    }

    private void setTopRoundnessInternal(float topRoundness) {
        mCurrentTopRoundness = topRoundness;
        applyRoundness();
    }

    private void setBottomRoundnessInternal(float bottomRoundness) {
        mCurrentBottomRoundness = bottomRoundness;
        applyRoundness();
    }

    public void onDensityOrFontScaleChanged() {
        initDimens();
        applyRoundness();
    }

    @Override
    public void setActualHeight(int actualHeight, boolean notifyListeners) {
        int previousHeight = getActualHeight();
        super.setActualHeight(actualHeight, notifyListeners);
        if (previousHeight != actualHeight) {
            applyRoundness();
        }
    }

    @Override
    public void setClipTopAmount(int clipTopAmount) {
        int previousAmount = getClipTopAmount();
        super.setClipTopAmount(clipTopAmount);
        if (previousAmount != clipTopAmount) {
            applyRoundness();
        }
    }

    @Override
    public void setClipBottomAmount(int clipBottomAmount) {
        int previousAmount = getClipBottomAmount();
        super.setClipBottomAmount(clipBottomAmount);
        if (previousAmount != clipBottomAmount) {
            applyRoundness();
        }
    }

    protected void setOutlineAlpha(float alpha) {
        if (alpha != mOutlineAlpha) {
            mOutlineAlpha = alpha;
            applyRoundness();
        }
    }

    @Override
    public float getOutlineAlpha() {
        return mOutlineAlpha;
    }

    protected void setOutlineRect(RectF rect) {
        if (rect != null) {
            setOutlineRect(rect.left, rect.top, rect.right, rect.bottom);
        } else {
            mCustomOutline = false;
            applyRoundness();
        }
    }

    /**
     * Set the dismiss behavior of the view.
     * @param usingRowTranslationX {@code true} if the view should translate using regular
     *                                          translationX, otherwise the contents will be
     *                                          translated.
     */
    public void setDismissUsingRowTranslationX(boolean usingRowTranslationX) {
        mDismissUsingRowTranslationX = usingRowTranslationX;
    }

    @Override
    public int getOutlineTranslation() {
        if (mCustomOutline) {
            return mOutlineRect.left;
        }
        if (mDismissUsingRowTranslationX) {
            return 0;
        }
        return (int) getTranslation();
    }

    public void updateOutline() {
        if (mCustomOutline) {
            return;
        }
        boolean hasOutline = needsOutline();
        setOutlineProvider(hasOutline ? mProvider : null);
    }

    /**
     * @return Whether the view currently needs an outline. This is usually {@code false} in case
     * it doesn't have a background.
     */
    protected boolean needsOutline() {
        if (isChildInGroup()) {
            return isGroupExpanded() && !isGroupExpansionChanging();
        } else if (isSummaryWithChildren()) {
            return !isGroupExpanded() || isGroupExpansionChanging();
        }
        return true;
    }

    public boolean isOutlineShowing() {
        ViewOutlineProvider op = getOutlineProvider();
        return op != null;
    }

    protected void setOutlineRect(float left, float top, float right, float bottom) {
        mCustomOutline = true;

        mOutlineRect.set((int) left, (int) top, (int) right, (int) bottom);

        // Outlines need to be at least 1 dp
        mOutlineRect.bottom = (int) Math.max(top, mOutlineRect.bottom);
        mOutlineRect.right = (int) Math.max(left, mOutlineRect.right);
        applyRoundness();
    }

    public Path getCustomClipPath(View child) {
        return null;
    }
}
