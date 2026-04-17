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

import java.util.Objects;

/**
 * The abstract base class defining a region on the SurfaceView that should have a blur applied.
 */
@FlaggedApi(FLAG_SURFACE_VIEW_SET_BLUR_REGIONS)
public abstract class BlurRegion {

    private float mBlurRadius;
    private float mAlpha;

    /**
     * Default constructor.
     * @hide
     */
    protected BlurRegion() {
        this.mAlpha = 1.0f;
        this.mBlurRadius = 0.0f;
    }

    /**
     * Constructs a new {@code BlurRegion} with the specified alpha and blur radius.
     *
     * @param alpha alpha value of the region [0.0 - 1.0]
     * @param blurRadius the blur radius in pixels
     * @hide
     */
    protected BlurRegion(@FloatRange(from = 0.0, to = 1.0) float alpha, float blurRadius) {
        this.mAlpha = alpha;
        this.mBlurRadius = blurRadius;
    }

    /**
     * Copy constructor.
     *
     * @param other blur region to copy, must not be {@code null}
     * @throws IllegalArgumentException if {@code other} is {@code null}
     * @hide
     */
    protected BlurRegion(@NonNull BlurRegion other) {
        if (other == null) {
            throw new IllegalArgumentException("BlurRegion is null");
        }
        this.mBlurRadius = other.mBlurRadius;
        this.mAlpha = other.mAlpha;
    }

    /**
     * Flattens the blur region into a float array.
     *
     * @param offsetX horizontal offset to apply to the region's coordinates
     * @param offsetY vertical offset to apply to the region's coordinates
     * @param scaleX horizontal scale to apply to the region's coordinates
     * @param scaleY vertical scale to apply to the region's coordinates
     * @return float array containing the region parameters
     * @hide
     */
    @NonNull
    public abstract float[] toFloatArray(int offsetX, int offsetY, float scaleX, float scaleY);

    /**
     * Returns a deep copy of this blur region.
     *
     * @return deep copy of this blur region
     */
    @NonNull
    public abstract BlurRegion copy();

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BlurRegion)) return false;
        BlurRegion that = (BlurRegion) o;
        return Float.compare(that.mAlpha, mAlpha) == 0
               && Float.compare(that.mBlurRadius, mBlurRadius) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mBlurRadius, mAlpha);
    }

    /**
     * Returns the blur radius for this region.
     *
     * @return blur radius in pixels
     */
    public float getBlurRadius() {
        return mBlurRadius;
    }

    /**
     * Sets the blur radius for this region.
     *
     * <p>The default value for blur radius is 0.0f.
     *
     * @param blurRadius blur radius in pixels
     */
    public void setBlurRadius(float blurRadius) {
        mBlurRadius = blurRadius;
    }

    /**
     * Returns the alpha for this region.
     *
     * @return alpha value of the region
     */
    public float getAlpha() {
        return mAlpha;
    }

    /**
     * Sets the alpha for this region.
     *
     * <p>The default value for alpha is 1.0f.
     *
     * @param alpha alpha value of the region [0.0 - 1.0]
     */
    public void setAlpha(@FloatRange(from = 0.0, to = 1.0) float alpha) {
        mAlpha = alpha;
    }
}
