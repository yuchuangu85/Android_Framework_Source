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

import com.android.ide.common.rendering.api.LayoutLog;
import com.android.layoutlib.bridge.Bridge;
import com.android.tools.layoutlib.annotations.VisibleForTesting;

import android.graphics.Bitmap;
import android.view.math.Math3DHelper;

/**
 * Generate spot shadow bitmap.
 */
class SpotShadowBitmapGenerator {

    private final SpotShadowConfig mShadowConfig;
    private final TriangleBuffer mTriangle;
    private float[] mStrips;
    private float[] mLightSources;
    private float mTranslateX;
    private float mTranslateY;

    public SpotShadowBitmapGenerator(SpotShadowConfig config) {
        // TODO: Reduce the buffer size based on shadow bounds.
        mTriangle = new TriangleBuffer();
        mShadowConfig = config;
        // For now assume no change to the world size
        mTriangle.setSize(config.getWidth(), config.getHeight(), 0);
    }

    /**
     * Populate the shadow bitmap.
     */
    public void populateShadow() {
        try {
            mLightSources = SpotShadowVertexCalculator.calculateLight(
                    mShadowConfig.getLightRadius(),
                    mShadowConfig.getLightSourcePoints(),
                    mShadowConfig.getLightCoord()[0],
                    mShadowConfig.getLightCoord()[1],
                    mShadowConfig.getLightCoord()[2]);

            mStrips = new float[3 * SpotShadowVertexCalculator.getStripSize(
                    mShadowConfig.getRays(),
                    mShadowConfig.getLayers())];

            if (SpotShadowVertexCalculator.calculateShadow(
                    mLightSources,
                    mShadowConfig.getLightSourcePoints(),
                    mShadowConfig.getPoly(),
                    mShadowConfig.getPolyLength(),
                    mShadowConfig.getRays(),
                    mShadowConfig.getLayers(),
                    mShadowConfig.getShadowStrength(),
                    mStrips) != 1) {
                return;
            }

            // Bit of a hack to re-adjust spot shadow to fit correctly within parent canvas.
            // Problem is that outline passed is not a final position, which throws off our
            // whereas our shadow rendering algorithm, which requires pre-set range for
            // optimization purposes.
            float[] shadowBounds = Math3DHelper.flatBound(mStrips, 3);

            if ((shadowBounds[2] - shadowBounds[0]) > mShadowConfig.getWidth() ||
                    (shadowBounds[3] - shadowBounds[1]) > mShadowConfig.getHeight()) {
                // Spot shadow to be casted is larger than the parent canvas,
                // We'll let ambient shadow do the trick and skip spot shadow here.
                return;
            }

            mTranslateX = 0;
            mTranslateY = 0;
            if (shadowBounds[0] < 0) {
                // translate to right by the offset amount.
                mTranslateX = shadowBounds[0] * -1;
            } else if (shadowBounds[2] > mShadowConfig.getWidth()) {
                // translate to left by the offset amount.
                mTranslateX = shadowBounds[2] - mShadowConfig.getWidth();
            }

            if (shadowBounds[1] < 0) {
                mTranslateY = shadowBounds[1] * -1;
            } else if (shadowBounds[3] > mShadowConfig.getHeight()) {
                mTranslateY = shadowBounds[3] - mShadowConfig.getHeight();
            }
            Math3DHelper.translate(mStrips, mTranslateX, mTranslateY, 3);

            mTriangle.drawTriangles(mStrips, mShadowConfig.getShadowStrength());
        } catch (IndexOutOfBoundsException|ArithmeticException mathError) {
            Bridge.getLog().warning(LayoutLog.TAG_INFO,  "Arithmetic error while drawing " +
                            "spot shadow",
                    mathError);
        } catch (Exception ex) {
            Bridge.getLog().warning(LayoutLog.TAG_INFO,  "Error while drawing shadow",
                    ex);
        }
    }

    public float getTranslateX() {
        return mTranslateX;
    }

    public float getTranslateY() {
        return mTranslateY;
    }

    public void clear() {
        mTriangle.clear();
    }

    /**
     * @return true if generated shadow poly is valid. False otherwise.
     */
    public boolean validate() {
        return mStrips != null && mStrips.length >= 9;
    }

    /**
     * @return the bitmap of shadow after it's populated
     */
    public Bitmap getBitmap() {
        return mTriangle.getImage();
    }

    @VisibleForTesting
    public float[] getStrips() {
        return mStrips;
    }

    @VisibleForTesting
    public void updateLightSource(float x, float y) {
        mShadowConfig.setLightCoord(x, y);
    }
}
