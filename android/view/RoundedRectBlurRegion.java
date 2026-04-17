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

package android.view;

import static android.view.flags.Flags.FLAG_SURFACE_VIEW_SET_BLUR_REGIONS;

import android.annotation.FlaggedApi;
import android.annotation.FloatRange;
import android.annotation.NonNull;
import android.graphics.RectF;

import java.util.Arrays;

/**
 * Defines a rounded rectangular blur region on the SurfaceView.
 */
@FlaggedApi(FLAG_SURFACE_VIEW_SET_BLUR_REGIONS)
public final class RoundedRectBlurRegion extends BlurRegion {
    @NonNull
    private RectF mRect;
    @NonNull
    private float[] mCornerRadii;

    /**
     * Constructs a new {@code RoundedRectBlurRegion} with the default parameters.
     *
     * <p>The default bounds and all corner radii are 0.0f.
     *
     * <p>The default alpha is 1.0f and the default blur radius is 0.0f.
     */
    public RoundedRectBlurRegion() {
        super();
        this.mRect = new RectF();
        this.mCornerRadii = new float[8];
    }

    /**
     * Constructs a new {@code RoundedRectBlurRegion} with the specified rounded rectangle
     * coordinates and corner radii.
     *
     * <p>The {@code cornerRadii} values are ordered [topLeftX, topLeftY, topRightX, topRightY,
     * bottomLeftX, bottomLeftY, bottomRightX, bottomRightY].
     *
     * @param rect bounds of the blur region
     * @param cornerRadii array of 8 floats representing the corner radii
     * @param alpha alpha value of the region [0.0 - 1.0]
     * @param blurRadius the blur radius in pixels
     * @throws IllegalArgumentException if {@code cornerRadii} is {@code null} or
     * its length is not 8 or {@code rect} is {@code null}
     */
    public RoundedRectBlurRegion(@NonNull RectF rect, @NonNull float[] cornerRadii,
            @FloatRange(from = 0.0, to = 1.0) float alpha, float blurRadius) {
        super(alpha, blurRadius);
        if (cornerRadii == null || cornerRadii.length != 8 || rect == null) {
            throw new IllegalArgumentException("cornerRadii must contain exactly 8 values "
                    + " and rect must not be null");
        }
        this.mRect = new RectF(rect);
        this.mCornerRadii = Arrays.copyOf(cornerRadii, 8);
    }

    /**
     * Copy constructor.
     *
     * @param other region to copy, must not be {@code null}
     * @throws IllegalArgumentException if {@code other} is {@code null}
     */
    private RoundedRectBlurRegion(@NonNull RoundedRectBlurRegion other) {
        super(other);
        if (other == null) {
            throw new IllegalArgumentException("RoundedRectBlurRegion is null");
        }
        this.mRect = new RectF(other.mRect);
        this.mCornerRadii = Arrays.copyOf(other.mCornerRadii, 8);
    }

    @Override
    @NonNull
    public RoundedRectBlurRegion copy() {
        return new RoundedRectBlurRegion(this);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RoundedRectBlurRegion)) return false;
        if (!super.equals(o)) return false;
        RoundedRectBlurRegion that = (RoundedRectBlurRegion) o;
        if (!mRect.equals(that.mRect)) return false;
        return Arrays.equals(mCornerRadii, that.mCornerRadii);
    }

    @Override
    public int hashCode() {
        int result = super.hashCode();
        result = 31 * result + mRect.hashCode();
        result = 31 * result + Arrays.hashCode(mCornerRadii);
        return result;
    }

    /**
     * Flattens the rounded rectangular region into a float array.
     *
     * @param offsetX horizontal offset to apply to the region's coordinates
     * @param offsetY vertical offset to apply to the region's coordinates
     * @param scaleX horizontal scale to apply to the region's coordinates
     * @param scaleY vertical scale to apply to the region's coordinates
     * @return float array containing the region parameters
     * @hide
     */
    @Override
    @NonNull
    public float[] toFloatArray(int offsetX, int offsetY, float scaleX, float scaleY) {
        final float[] floatArray = new float[14];
        // Scale blur radius by the average of x and y scales to approximate the effect.
        floatArray[0] = getBlurRadius() * (scaleX + scaleY) / 2.0f;
        floatArray[1] = getAlpha();
        floatArray[2] = mRect.left * scaleX + offsetX;
        floatArray[3] = mRect.top * scaleY + offsetY;
        floatArray[4] = mRect.right * scaleX + offsetX;
        floatArray[5] = mRect.bottom * scaleY + offsetY;
        floatArray[6] = mCornerRadii[0] * scaleX;
        floatArray[7] = mCornerRadii[1] * scaleY;
        floatArray[8] = mCornerRadii[2] * scaleX;
        floatArray[9] = mCornerRadii[3] * scaleY;
        floatArray[10] = mCornerRadii[4] * scaleX;
        floatArray[11] = mCornerRadii[5] * scaleY;
        floatArray[12] = mCornerRadii[6] * scaleX;
        floatArray[13] = mCornerRadii[7] * scaleY;
        return floatArray;
    }

    /**
     * Returns the bounds of the blur region.
     *
     * @return {@link RectF} containing the bounds.
     */
    @NonNull
    public RectF getBounds() {
        return mRect;
    }


    /**
     * Sets the bounds of the blur region.
     *
     * <p>The default value is an empty rectangle.
     *
     * @param rect the bounds of the blur region
     * @throws IllegalArgumentException if {@code rect} is {@code null}
     */
    public void setBounds(@NonNull RectF rect) {
        if (rect == null) {
            throw new IllegalArgumentException("rect must not be null");
        }
        this.mRect.set(rect);
    }

    /**
     * Returns the corner radii for the blur region.
     *
     * @return array of 8 floats representing the corner radii
     */
    @NonNull
    public float[] getCornerRadii() {
        return mCornerRadii;
    }


    /**
     * Sets the corner radii for the blur region.
     *
     * <p>The {@code cornerRadii} values are ordered [topLeftX, topLeftY, topRightX, topRightY,
     * bottomLeftX, bottomLeftY, bottomRightX, bottomRightY].
     *
     * <p>The default value is all zeros.
     *
     * @param cornerRadii array of 8 floats representing the corner radii
     * @throws IllegalArgumentException if {@code cornerRadii} is {@code null} or its length
     * is not 8
     */
    public void setCornerRadii(@NonNull float[] cornerRadii) {
        if (cornerRadii == null || cornerRadii.length != 8) {
            throw new IllegalArgumentException("cornerRadii must contain exactly 8 values");
        }
        System.arraycopy(cornerRadii, 0, mCornerRadii, 0, 8);
    }

    /**
     * Sets the corner radius for all corners of the blur region.
     *
     * @param cornerRadius radius in pixels.
     */
    public void setCornerRadii(float cornerRadius) {
        Arrays.fill(mCornerRadii, cornerRadius);
    }
}
