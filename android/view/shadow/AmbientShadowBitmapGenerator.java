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

import android.graphics.Bitmap;
import android.view.math.Math3DHelper;

/**
 * Generates ambient shadow bitmap
 */
class AmbientShadowBitmapGenerator {

    private final AmbientShadowConfig mShadowConfig;
    private final TriangleBuffer mTriangleBuffer;
    private final AmbientShadowVertexCalculator mCalculator;

    private float mTranslateX;
    private float mTranslateY;

    private boolean mValid;

    public AmbientShadowBitmapGenerator(AmbientShadowConfig shadowConfig) {
        mShadowConfig = shadowConfig;

        mTriangleBuffer = new TriangleBuffer();
        mTriangleBuffer.setSize(mShadowConfig.getWidth(), mShadowConfig.getHeight(), 0);

        mCalculator = new AmbientShadowVertexCalculator(mShadowConfig);
    }

    /**
     * Populate vertices and fill the triangle buffers. To be called before {@link #getBitmap()}
     */
    public void populateShadow() {
        try {
            mValid = mCalculator.generateVertex(mShadowConfig.getPolygon());
            if (!mValid) {
                Bridge.getLog().warning(LayoutLog.TAG_INFO,  "Arithmetic error while " +
                                "drawing ambient shadow", null, null);
                return;
            }

            float[] shadowBounds = Math3DHelper.flatBound(mCalculator.getVertex(), 2);
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

            Math3DHelper.translate(mCalculator.getVertex(), mTranslateX, mTranslateY, 2);

            mTriangleBuffer.drawTriangles(mCalculator.getIndex(), mCalculator.getVertex(),
                    mCalculator.getColor(), mShadowConfig.getShadowStrength());
        } catch (IndexOutOfBoundsException|ArithmeticException mathError) {
            Bridge.getLog().warning(LayoutLog.TAG_INFO,  "Arithmetic error while drawing " +
                            "ambient shadow",
                    mathError);
        } catch (Exception ex) {
            Bridge.getLog().warning(LayoutLog.TAG_INFO,  "Error while drawing shadow",
                    ex);
        }
    }

    public boolean isValid() {
        return mValid;
    }

    public Bitmap getBitmap() {
        return mTriangleBuffer.getImage();
    }

    public float getTranslateX() {
        return mTranslateX;
    }

    public float getTranslateY() {
        return mTranslateY;
    }
}
