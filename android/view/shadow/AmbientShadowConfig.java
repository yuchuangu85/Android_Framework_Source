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

package android.view.shadow;

/**
 * Model for ambient shadow rendering. Assumes light sources from centroid of the object.
 */
class AmbientShadowConfig {

    private final int mWidth;
    private final int mHeight;

    private final float mEdgeScale;
    private final float mShadowBoundRatio;
    private final float mShadowStrength;

    private final float[] mPolygon;

    private final int mRays;
    private final int mLayers;

    private AmbientShadowConfig(Builder builder) {
        mEdgeScale = builder.mEdgeScale;
        mShadowBoundRatio = builder.mShadowBoundRatio;
        mShadowStrength = builder.mShadowStrength;
        mRays = builder.mRays;
        mLayers = builder.mLayers;
        mWidth = builder.mWidth;
        mHeight = builder.mHeight;
        mPolygon = builder.mPolygon;
    }

    public int getWidth() {
        return mWidth;
    }

    public int getHeight() {
        return mHeight;
    }

    /**
     * Returns scales intensity of the edge of the shadow (opacity) [0-100]
     */
    public float getEdgeScale() {
        return mEdgeScale;
    }

    /**
     * Returns scales the area (in xy) of the shadow [0-1]
     */
    public float getShadowBoundRatio() {
        return mShadowBoundRatio;
    }

    /**
     * Returns scales the intensity of the entire shadow (opacity) [0-1]
     */
    public float getShadowStrength() {
        return mShadowStrength;
    }

    /**
     * Returns opaque polygon to cast shadow
     */
    public float[] getPolygon() {
        return mPolygon;
    }

    /**
     * Returns # of rays to use in ray tracing. It determines the accuracy of outline (bounds) of
     * the shadow.
     */
    public int getRays() {
        return mRays;
    }

    /**
     * Returns # of layers. It determines the intensity of the pen-umbra.
     */
    public int getLayers() {
        return mLayers;
    }

    public static class Builder {

        private float mEdgeScale;
        private float mShadowBoundRatio;
        private float mShadowStrength;
        private int mRays;
        private int mLayers;

        private float[] mPolygon;

        private int mWidth;
        private int mHeight;

        public Builder setEdgeScale(float edgeScale) {
            mEdgeScale = edgeScale;
            return this;
        }

        public Builder setShadowBoundRatio(float shadowBoundRatio) {
            mShadowBoundRatio = shadowBoundRatio;
            return this;
        }

        public Builder setShadowStrength(float shadowStrength) {
            mShadowStrength = shadowStrength;
            return this;
        }

        public Builder setRays(int rays) {
            mRays = rays;
            return this;
        }

        public Builder setLayers(int layers) {
            mLayers = layers;
            return this;
        }

        public Builder setSize(int width, int height) {
            mWidth = width;
            mHeight = height;
            return this;
        }

        public Builder setPolygon(float[] polygon) {
            mPolygon = polygon;
            return this;
        }

        public AmbientShadowConfig build() {
            return new AmbientShadowConfig(this);
        }
    }
}
