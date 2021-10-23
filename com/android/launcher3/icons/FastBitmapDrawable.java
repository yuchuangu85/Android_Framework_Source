/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.launcher3.icons;

import android.animation.ObjectAnimator;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.util.Property;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;

import androidx.annotation.Nullable;

public class FastBitmapDrawable extends Drawable {

    private static final Interpolator ACCEL = new AccelerateInterpolator();
    private static final Interpolator DEACCEL = new DecelerateInterpolator();

    private static final float PRESSED_SCALE = 1.1f;

    private static final float DISABLED_DESATURATION = 1f;
    private static final float DISABLED_BRIGHTNESS = 0.5f;

    public static final int CLICK_FEEDBACK_DURATION = 200;

    private static ColorFilter sDisabledFColorFilter;

    protected final Paint mPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    protected Bitmap mBitmap;
    protected final int mIconColor;

    @Nullable private ColorFilter mColorFilter;

    private boolean mIsPressed;
    protected boolean mIsDisabled;
    float mDisabledAlpha = 1f;

    // Animator and properties for the fast bitmap drawable's scale
    private static final Property<FastBitmapDrawable, Float> SCALE
            = new Property<FastBitmapDrawable, Float>(Float.TYPE, "scale") {
        @Override
        public Float get(FastBitmapDrawable fastBitmapDrawable) {
            return fastBitmapDrawable.mScale;
        }

        @Override
        public void set(FastBitmapDrawable fastBitmapDrawable, Float value) {
            fastBitmapDrawable.mScale = value;
            fastBitmapDrawable.invalidateSelf();
        }
    };
    private ObjectAnimator mScaleAnimation;
    private float mScale = 1;

    private int mAlpha = 255;

    public FastBitmapDrawable(Bitmap b) {
        this(b, Color.TRANSPARENT);
    }

    public FastBitmapDrawable(BitmapInfo info) {
        this(info.icon, info.color);
    }

    protected FastBitmapDrawable(Bitmap b, int iconColor) {
        this(b, iconColor, false);
    }

    protected FastBitmapDrawable(Bitmap b, int iconColor, boolean isDisabled) {
        mBitmap = b;
        mIconColor = iconColor;
        setFilterBitmap(true);
        setIsDisabled(isDisabled);
    }

    @Override
    public final void draw(Canvas canvas) {
        if (mScale != 1f) {
            int count = canvas.save();
            Rect bounds = getBounds();
            canvas.scale(mScale, mScale, bounds.exactCenterX(), bounds.exactCenterY());
            drawInternal(canvas, bounds);
            canvas.restoreToCount(count);
        } else {
            drawInternal(canvas, getBounds());
        }
    }

    protected void drawInternal(Canvas canvas, Rect bounds) {
        canvas.drawBitmap(mBitmap, null, bounds, mPaint);
    }

    /**
     * Returns the primary icon color
     */
    public int getIconColor() {
        return mIconColor;
    }

    /**
     * Returns if this represents a themed icon
     */
    public boolean isThemed() {
        return false;
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        mColorFilter = cf;
        updateFilter();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    @Override
    public void setAlpha(int alpha) {
        if (mAlpha != alpha) {
            mAlpha = alpha;
            mPaint.setAlpha(alpha);
            invalidateSelf();
        }
    }

    @Override
    public void setFilterBitmap(boolean filterBitmap) {
        mPaint.setFilterBitmap(filterBitmap);
        mPaint.setAntiAlias(filterBitmap);
    }

    @Override
    public int getAlpha() {
        return mAlpha;
    }

    public void resetScale() {
        if (mScaleAnimation != null) {
            mScaleAnimation.cancel();
            mScaleAnimation = null;
        }
        mScale = 1;
        invalidateSelf();
    }

    public float getAnimatedScale() {
        return mScaleAnimation == null ? 1 : mScale;
    }

    @Override
    public int getIntrinsicWidth() {
        return mBitmap.getWidth();
    }

    @Override
    public int getIntrinsicHeight() {
        return mBitmap.getHeight();
    }

    @Override
    public int getMinimumWidth() {
        return getBounds().width();
    }

    @Override
    public int getMinimumHeight() {
        return getBounds().height();
    }

    @Override
    public boolean isStateful() {
        return true;
    }

    @Override
    public ColorFilter getColorFilter() {
        return mPaint.getColorFilter();
    }

    @Override
    protected boolean onStateChange(int[] state) {
        boolean isPressed = false;
        for (int s : state) {
            if (s == android.R.attr.state_pressed) {
                isPressed = true;
                break;
            }
        }
        if (mIsPressed != isPressed) {
            mIsPressed = isPressed;

            if (mScaleAnimation != null) {
                mScaleAnimation.cancel();
                mScaleAnimation = null;
            }

            if (mIsPressed) {
                // Animate when going to pressed state
                mScaleAnimation = ObjectAnimator.ofFloat(this, SCALE, PRESSED_SCALE);
                mScaleAnimation.setDuration(CLICK_FEEDBACK_DURATION);
                mScaleAnimation.setInterpolator(ACCEL);
                mScaleAnimation.start();
            } else {
                if (isVisible()) {
                    mScaleAnimation = ObjectAnimator.ofFloat(this, SCALE, 1f);
                    mScaleAnimation.setDuration(CLICK_FEEDBACK_DURATION);
                    mScaleAnimation.setInterpolator(DEACCEL);
                    mScaleAnimation.start();
                } else {
                    mScale = 1f;
                    invalidateSelf();
                }
            }
            return true;
        }
        return false;
    }

    public void setIsDisabled(boolean isDisabled) {
        if (mIsDisabled != isDisabled) {
            mIsDisabled = isDisabled;
            updateFilter();
        }
    }

    protected boolean isDisabled() {
        return mIsDisabled;
    }

    private ColorFilter getDisabledColorFilter() {
        if (sDisabledFColorFilter == null) {
            sDisabledFColorFilter = getDisabledFColorFilter(mDisabledAlpha);
        }
        return sDisabledFColorFilter;
    }

    /**
     * Updates the paint to reflect the current brightness and saturation.
     */
    protected void updateFilter() {
        mPaint.setColorFilter(mIsDisabled ? getDisabledColorFilter() : mColorFilter);
        invalidateSelf();
    }

    @Override
    public ConstantState getConstantState() {
        return new FastBitmapConstantState(mBitmap, mIconColor, mIsDisabled);
    }

    public static ColorFilter getDisabledFColorFilter(float disabledAlpha) {
        ColorMatrix tempBrightnessMatrix = new ColorMatrix();
        ColorMatrix tempFilterMatrix = new ColorMatrix();

        tempFilterMatrix.setSaturation(1f - DISABLED_DESATURATION);
        float scale = 1 - DISABLED_BRIGHTNESS;
        int brightnessI =   (int) (255 * DISABLED_BRIGHTNESS);
        float[] mat = tempBrightnessMatrix.getArray();
        mat[0] = scale;
        mat[6] = scale;
        mat[12] = scale;
        mat[4] = brightnessI;
        mat[9] = brightnessI;
        mat[14] = brightnessI;
        mat[18] = disabledAlpha;
        tempFilterMatrix.preConcat(tempBrightnessMatrix);
        return new ColorMatrixColorFilter(tempBrightnessMatrix);
    }

    protected static class FastBitmapConstantState extends ConstantState {
        protected final Bitmap mBitmap;
        protected final int mIconColor;
        protected final boolean mIsDisabled;

        public FastBitmapConstantState(Bitmap bitmap, int color, boolean isDisabled) {
            mBitmap = bitmap;
            mIconColor = color;
            mIsDisabled = isDisabled;
        }

        @Override
        public FastBitmapDrawable newDrawable() {
            return new FastBitmapDrawable(mBitmap, mIconColor, mIsDisabled);
        }

        @Override
        public int getChangingConfigurations() {
            return 0;
        }
    }
}
