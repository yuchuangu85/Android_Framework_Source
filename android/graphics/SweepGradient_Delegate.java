/*
 * Copyright (C) 2010 The Android Open Source Project
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

package android.graphics;

import com.android.ide.common.rendering.api.ILayoutLog;
import com.android.layoutlib.bridge.Bridge;
import com.android.layoutlib.bridge.impl.DelegateManager;
import com.android.tools.layoutlib.annotations.LayoutlibDelegate;

import java.awt.image.DataBufferInt;
import java.awt.image.Raster;
import java.awt.image.SampleModel;

/**
 * Delegate implementing the native methods of android.graphics.SweepGradient
 *
 * Through the layoutlib_create tool, the original native methods of SweepGradient have been
 * replaced by calls to methods of the same name in this delegate class.
 *
 * This class behaves like the original native implementation, but in Java, keeping previously
 * native data into its own objects and mapping them to int that are sent back and forth between
 * it and the original SweepGradient class.
 *
 * Because this extends {@link Shader_Delegate}, there's no need to use a {@link DelegateManager},
 * as all the Shader classes will be added to the manager owned by {@link Shader_Delegate}.
 *
 * @see Shader_Delegate
 *
 */
public class SweepGradient_Delegate extends Gradient_Delegate {

    // ---- delegate data ----
    private java.awt.Paint mJavaPaint;

    // ---- Public Helper methods ----

    @Override
    public java.awt.Paint getJavaPaint() {
        return mJavaPaint;
    }

    // ---- native methods ----

    @LayoutlibDelegate
    /*package*/ static long nativeCreate(long matrix, float x, float y, long[] colors,
            float[] positions, long colorSpaceHandle) {
        SweepGradient_Delegate newDelegate = new SweepGradient_Delegate(matrix, x, y, colors,
                positions);
        return sManager.addNewDelegate(newDelegate);
    }

    // ---- Private delegate/helper methods ----

    /**
     * A subclass of Shader that draws a sweep gradient around a center point.
     *
     * @param nativeMatrix reference to the shader's native transformation matrix
     * @param cx       The x-coordinate of the center
     * @param cy       The y-coordinate of the center
     * @param colors   The colors to be distributed between around the center.
     *                 There must be at least 2 colors in the array.
     * @param positions May be NULL. The relative position of
     *                 each corresponding color in the colors array, beginning
     *                 with 0 and ending with 1.0. If the values are not
     *                 monotonic, the drawing may produce unexpected results.
     *                 If positions is NULL, then the colors are automatically
     *                 spaced evenly.
     */
    private SweepGradient_Delegate(long nativeMatrix, float cx, float cy,
            long[] colors, float[] positions) {
        super(nativeMatrix, colors, positions);
        mJavaPaint = new SweepGradientPaint(cx, cy, mColors, mPositions);
    }

    private class SweepGradientPaint extends GradientPaint {

        private final float mCx;
        private final float mCy;

        public SweepGradientPaint(float cx, float cy, int[] colors,
                float[] positions) {
            super(colors, positions, null /*tileMode*/);
            mCx = cx;
            mCy = cy;
        }

        @Override
        public java.awt.PaintContext createContext(
                java.awt.image.ColorModel     colorModel,
                java.awt.Rectangle            deviceBounds,
                java.awt.geom.Rectangle2D     userBounds,
                java.awt.geom.AffineTransform xform,
                java.awt.RenderingHints       hints) {
            precomputeGradientColors();

            java.awt.geom.AffineTransform canvasMatrix;
            try {
                canvasMatrix = xform.createInverse();
            } catch (java.awt.geom.NoninvertibleTransformException e) {
                Bridge.getLog().fidelityWarning(ILayoutLog.TAG_MATRIX_INVERSE,
                        "Unable to inverse matrix in SweepGradient", e, null, null /*data*/);
                canvasMatrix = new java.awt.geom.AffineTransform();
            }

            java.awt.geom.AffineTransform localMatrix = getLocalMatrix();
            try {
                localMatrix = localMatrix.createInverse();
            } catch (java.awt.geom.NoninvertibleTransformException e) {
                Bridge.getLog().fidelityWarning(ILayoutLog.TAG_MATRIX_INVERSE,
                        "Unable to inverse matrix in SweepGradient", e, null, null /*data*/);
                localMatrix = new java.awt.geom.AffineTransform();
            }

            return new SweepGradientPaintContext(canvasMatrix, localMatrix, colorModel);
        }

        private class SweepGradientPaintContext implements java.awt.PaintContext {

            private final java.awt.geom.AffineTransform mCanvasMatrix;
            private final java.awt.geom.AffineTransform mLocalMatrix;
            private final java.awt.image.ColorModel mColorModel;

            public SweepGradientPaintContext(
                    java.awt.geom.AffineTransform canvasMatrix,
                    java.awt.geom.AffineTransform localMatrix,
                    java.awt.image.ColorModel colorModel) {
                mCanvasMatrix = canvasMatrix;
                mLocalMatrix = localMatrix;
                mColorModel = colorModel;
            }

            @Override
            public void dispose() {
            }

            @Override
            public java.awt.image.ColorModel getColorModel() {
                return mColorModel;
            }

            @Override
            public java.awt.image.Raster getRaster(int x, int y, int w, int h) {

                int[] data = new int[w*h];

                // compute angle from each point to the center, and figure out the distance from
                // it.
                int index = 0;
                float[] pt1 = new float[2];
                float[] pt2 = new float[2];
                for (int iy = 0 ; iy < h ; iy++) {
                    for (int ix = 0 ; ix < w ; ix++) {
                        // handle the canvas transform
                        pt1[0] = x + ix;
                        pt1[1] = y + iy;
                        mCanvasMatrix.transform(pt1, 0, pt2, 0, 1);

                        // handle the local matrix
                        pt1[0] = pt2[0] - mCx;
                        pt1[1] = pt2[1] - mCy;
                        mLocalMatrix.transform(pt1, 0, pt2, 0, 1);

                        float dx = pt2[0];
                        float dy = pt2[1];

                        float angle;
                        if (dx == 0) {
                            angle = (float) (dy < 0 ? 3 * Math.PI / 2 : Math.PI / 2);
                        } else if (dy == 0) {
                            angle = (float) (dx < 0 ? Math.PI : 0);
                        } else {
                            angle = (float) Math.atan(dy / dx);
                            if (dx > 0) {
                                if (dy < 0) {
                                    angle += Math.PI * 2;
                                }
                            } else {
                                angle += Math.PI;
                            }
                        }

                        // convert to 0-1. value and get color
                        data[index++] = getGradientColor((float) (angle / (2 * Math.PI)));
                    }
                }

                DataBufferInt dataBuffer = new DataBufferInt(data, data.length);
                SampleModel colorModel = mColorModel.createCompatibleSampleModel(w, h);
                return Raster.createWritableRaster(colorModel, dataBuffer, null);
            }

        }
    }
}
