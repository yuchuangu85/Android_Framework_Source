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

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.DisplayMetrics;
import android.view.ViewGroup;

import static android.view.shadow.ShadowConstants.MIN_ALPHA;
import static android.view.shadow.ShadowConstants.SCALE_DOWN;

public class HighQualityShadowPainter {

    private HighQualityShadowPainter() { }

    /**
     * Draws simple Rect shadow
     */
    public static void paintRectShadow(ViewGroup parent, Outline outline, float elevation,
            Canvas canvas, float alpha, float densityDpi) {

        if (!validate(elevation, densityDpi)) {
            return;
        }

        int width = parent.getWidth() / SCALE_DOWN;
        int height = parent.getHeight() / SCALE_DOWN;

        Rect rectOriginal = new Rect();
        Rect rectScaled = new Rect();
        if (!outline.getRect(rectScaled) || alpha < MIN_ALPHA) {
            // If alpha below MIN_ALPHA it's invisible (based on manual test). Save some perf.
            return;
        }

        outline.getRect(rectOriginal);

        rectScaled.left /= SCALE_DOWN;
        rectScaled.right /= SCALE_DOWN;
        rectScaled.top /= SCALE_DOWN;
        rectScaled.bottom /= SCALE_DOWN;
        float radius = outline.getRadius() / SCALE_DOWN;

        if (radius > rectScaled.width() || radius > rectScaled.height()) {
            // Rounded edge generation fails if radius is bigger than drawing box.
            return;
        }

        // ensure alpha doesn't go over 1
        alpha = (alpha > 1.0f) ? 1.0f : alpha;
        float[] poly = getPoly(rectScaled, elevation / SCALE_DOWN, radius);

        paintAmbientShadow(poly, canvas, width, height, alpha, rectOriginal, radius);
        paintSpotShadow(poly, rectScaled, elevation / SCALE_DOWN,
                canvas, densityDpi, width, height, alpha, rectOriginal, radius);
    }

    /**
     * High quality shadow does not work well with object that is too high in elevation. Check if
     * the object elevation is reasonable and returns true if shadow will work well. False other
     * wise.
     */
    private static boolean validate(float elevation, float densityDpi) {
        float scaledElevationPx = elevation / SCALE_DOWN;
        float scaledSpotLightHeightPx = ShadowConstants.SPOT_SHADOW_LIGHT_Z_HEIGHT_DP *
                (densityDpi / DisplayMetrics.DENSITY_DEFAULT);
        if (scaledElevationPx > scaledSpotLightHeightPx) {
            return false;
        }

        return true;
    }

    /**
     * @param polygon - polygon of the shadow caster
     * @param canvas - canvas to draw
     * @param width - scaled canvas (parent) width
     * @param height - scaled canvas (parent) height
     * @param alpha - 0-1 scale
     * @param shadowCasterOutline - unscaled original shadow caster outline.
     * @param radius
     */
    private static void paintAmbientShadow(float[] polygon, Canvas canvas, int width, int height,
            float alpha, Rect shadowCasterOutline, float radius) {
        // TODO: Consider re-using the triangle buffer here since the world stays consistent.
        // TODO: Reduce the buffer size based on shadow bounds.

        AmbientShadowConfig config = new AmbientShadowConfig.Builder()
                .setSize(width, height)
                .setPolygon(polygon)
                .setEdgeScale(ShadowConstants.AMBIENT_SHADOW_EDGE_SCALE)
                .setShadowBoundRatio(ShadowConstants.AMBIENT_SHADOW_SHADOW_BOUND)
                .setShadowStrength(ShadowConstants.AMBIENT_SHADOW_STRENGTH * alpha)
                .setRays(ShadowConstants.AMBIENT_SHADOW_RAYS)
                .setLayers(ShadowConstants.AMBIENT_SHADOW_LAYERS)
                .build();

        AmbientShadowBitmapGenerator generator = new AmbientShadowBitmapGenerator(config);
        generator.populateShadow();

        if (!generator.isValid()) {
            return;
        }

        drawScaled(
                canvas, generator.getBitmap(), (int) generator.getTranslateX(),
                (int) generator.getTranslateY(), width, height,
                shadowCasterOutline, radius);
    }

    /**
     * @param poly - polygon of the shadow caster
     * @param rectBound - scaled bounds of shadow caster.
     * @param canvas - canvas to draw
     * @param width - scaled canvas (parent) width
     * @param height - scaled canvas (parent) height
     * @param alpha - 0-1 scale
     * @param shadowCasterOutline - unscaled original shadow caster outline.
     * @param radius
     */
    private static void paintSpotShadow(float[] poly, Rect rectBound, float elevation, Canvas canvas,
            float densityDpi, int width, int height, float alpha, Rect shadowCasterOutline,
            float radius) {

        // TODO: Use alpha later
        float lightZHeightPx = ShadowConstants.SPOT_SHADOW_LIGHT_Z_HEIGHT_DP * (densityDpi / DisplayMetrics.DENSITY_DEFAULT);
        if (lightZHeightPx - elevation < ShadowConstants.SPOT_SHADOW_LIGHT_Z_EPSILON) {
            // If the view is above or too close to the light source then return.
            // This is done to somewhat simulate android behaviour.
            return;
        }

        float lightX = (rectBound.left + rectBound.right) / 2;
        float lightY = rectBound.top;
        // Light shouldn't be bigger than the object by too much.
        int dynamicLightRadius = Math.min(rectBound.width(), rectBound.height());

        SpotShadowConfig config = new SpotShadowConfig.Builder()
                .setSize(width, height)
                .setLayers(ShadowConstants.SPOT_SHADOW_LAYERS)
                .setRays(ShadowConstants.SPOT_SHADOW_RAYS)
                .setLightCoord(lightX, lightY, lightZHeightPx)
                .setLightRadius(dynamicLightRadius)
                .setLightSourcePoints(ShadowConstants.SPOT_SHADOW_LIGHT_SOURCE_POINTS)
                .setShadowStrength(ShadowConstants.SPOT_SHADOW_STRENGTH * alpha)
                .setPolygon(poly, poly.length / ShadowConstants.COORDINATE_SIZE)
                .build();

        SpotShadowBitmapGenerator generator = new SpotShadowBitmapGenerator(config);
        generator.populateShadow();

        if (!generator.validate()) {
            return;
        }

        drawScaled(canvas, generator.getBitmap(), (int) generator.getTranslateX(),
                (int) generator.getTranslateY(), width, height, shadowCasterOutline, radius);
    }

    /**
     * Draw the bitmap scaled up.
     * @param translateX - offset in x axis by which the bitmap is shifted.
     * @param translateY - offset in y axis by which the bitmap is shifted.
     * @param width  - scaled width of canvas (parent)
     * @param height - scaled height of canvas (parent)
     * @param shadowCaster - unscaled outline of shadow caster
     * @param radius
     */
    private static void drawScaled(Canvas canvas, Bitmap bitmap, int translateX, int translateY,
            int width, int height, Rect shadowCaster, float radius) {
        int unscaledTranslateX = translateX * SCALE_DOWN;
        int unscaledTranslateY = translateY * SCALE_DOWN;

        // To the canvas
        Rect dest = new Rect(
                -unscaledTranslateX,
                -unscaledTranslateY,
                (width * SCALE_DOWN) - unscaledTranslateX,
                (height * SCALE_DOWN) - unscaledTranslateY);
        Rect destSrc = new Rect(0, 0, width, height);

        if (radius > 0) {
            // Rounded edge.
            int save = canvas.save();
            canvas.drawBitmap(bitmap, destSrc, dest, null);
            canvas.restoreToCount(save);
            return;
        }

        /**
         * ----------------------------------
         * |                                |
         * |              top               |
         * |                                |
         * ----------------------------------
         * |      |                 |       |
         * | left |  shadow caster  | right |
         * |      |                 |       |
         * ----------------------------------
         * |                                |
         * |            bottom              |
         * |                                |
         * ----------------------------------
         *
         * dest == top + left + shadow caster + right + bottom
         * Visually, canvas.drawBitmap(bitmap, destSrc, dest, paint) would achieve the same result.
         */
        Rect left = new Rect(dest.left, shadowCaster.top, shadowCaster.left, shadowCaster.bottom);
        int leftScaled = left.width() / SCALE_DOWN + destSrc.left;

        Rect top = new Rect(dest.left, dest.top, dest.right, shadowCaster.top);
        int topScaled = top.height() / SCALE_DOWN + destSrc.top;

        Rect right = new Rect(shadowCaster.right, shadowCaster.top, dest.right,
                shadowCaster.bottom);
        int rightScaled = (shadowCaster.right + unscaledTranslateX) / SCALE_DOWN + destSrc.left;

        Rect bottom = new Rect(dest.left, shadowCaster.bottom, dest.right, dest.bottom);
        int bottomScaled = (bottom.bottom - bottom.height()) / SCALE_DOWN + destSrc.top;

        // calculate parts of the middle ground that can be ignored.
        Rect leftSrc = new Rect(destSrc.left, topScaled, leftScaled, bottomScaled);
        Rect topSrc = new Rect(destSrc.left, destSrc.top, destSrc.right, topScaled);
        Rect rightSrc = new Rect(rightScaled, topScaled, destSrc.right, bottomScaled);
        Rect bottomSrc = new Rect(destSrc.left, bottomScaled, destSrc.right, destSrc.bottom);

        int save = canvas.save();
        Paint paint = new Paint();
        canvas.drawBitmap(bitmap, leftSrc, left, paint);
        canvas.drawBitmap(bitmap, topSrc, top, paint);
        canvas.drawBitmap(bitmap, rightSrc, right, paint);
        canvas.drawBitmap(bitmap, bottomSrc, bottom, paint);
        canvas.restoreToCount(save);
    }

    private static float[] getPoly(Rect rect, float elevation, float radius) {
        if (radius <= 0) {
            float[] poly = new float[ShadowConstants.RECT_VERTICES_SIZE * ShadowConstants.COORDINATE_SIZE];

            poly[0] = poly[9] = rect.left;
            poly[1] = poly[4] = rect.top;
            poly[3] = poly[6] = rect.right;
            poly[7] = poly[10] = rect.bottom;
            poly[2] = poly[5] = poly[8] = poly[11] = elevation;

            return poly;
        }

        return buildRoundedEdges(rect, elevation, radius);
    }

    private static float[] buildRoundedEdges(
            Rect rect, float elevation, float radius) {

        float[] roundedEdgeVertices = new float[(ShadowConstants.SPLICE_ROUNDED_EDGE + 1) * 4 * 3];
        int index = 0;
        // 1.0 LT. From theta 0 to pi/2 in K division.
        for (int i = 0; i <= ShadowConstants.SPLICE_ROUNDED_EDGE; i++) {
            double theta = (Math.PI / 2.0d) * ((double) i / ShadowConstants.SPLICE_ROUNDED_EDGE);
            float x = (float) (rect.left + (radius - radius * Math.cos(theta)));
            float y = (float) (rect.top + (radius - radius * Math.sin(theta)));
            roundedEdgeVertices[index++] = x;
            roundedEdgeVertices[index++] = y;
            roundedEdgeVertices[index++] = elevation;
        }

        // 2.0 RT
        for (int i = ShadowConstants.SPLICE_ROUNDED_EDGE; i >= 0; i--) {
            double theta = (Math.PI / 2.0d) * ((double) i / ShadowConstants.SPLICE_ROUNDED_EDGE);
            float x = (float) (rect.right - (radius - radius * Math.cos(theta)));
            float y = (float) (rect.top + (radius - radius * Math.sin(theta)));
            roundedEdgeVertices[index++] = x;
            roundedEdgeVertices[index++] = y;
            roundedEdgeVertices[index++] = elevation;
        }

        // 3.0 RB
        for (int i = 0; i <= ShadowConstants.SPLICE_ROUNDED_EDGE; i++) {
            double theta = (Math.PI / 2.0d) * ((double) i / ShadowConstants.SPLICE_ROUNDED_EDGE);
            float x = (float) (rect.right - (radius - radius * Math.cos(theta)));
            float y = (float) (rect.bottom - (radius - radius * Math.sin(theta)));
            roundedEdgeVertices[index++] = x;
            roundedEdgeVertices[index++] = y;
            roundedEdgeVertices[index++] = elevation;
        }

        // 4.0 LB
        for (int i = ShadowConstants.SPLICE_ROUNDED_EDGE; i >= 0; i--) {
            double theta = (Math.PI / 2.0d) * ((double) i / ShadowConstants.SPLICE_ROUNDED_EDGE);
            float x = (float) (rect.left + (radius - radius * Math.cos(theta)));
            float y = (float) (rect.bottom - (radius - radius * Math.sin(theta)));
            roundedEdgeVertices[index++] = x;
            roundedEdgeVertices[index++] = y;
            roundedEdgeVertices[index++] = elevation;
        }

        return roundedEdgeVertices;
    }
}
