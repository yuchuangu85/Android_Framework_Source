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

import android.view.math.Math3DHelper;

/**
 * Generates the vertices required for spot shadow and all other shadow-related rendering.
 */
class SpotShadowVertexCalculator {

    private SpotShadowVertexCalculator() { }

    /**
     * Create evenly distributed circular light source points from x and y (on flat z plane).
     * This is useful for ray tracing the shadow points later. Format : (x1,y1,z1,x2,y2,z2 ...)
     *
     * @param radius - radius of the light source
     * @param points - how many light source points to generate
     * @param x - center X of the light source
     * @param y - center Y of the light source
     * @param height - how high (z depth) the light should be
     * @return float points (x,y,z) of light source points.
     */
    public static float[] calculateLight(float radius, int points, float x, float y, float height) {
        float[] ret = new float[points * 3];
        for (int i = 0; i < points; i++) {
            double angle = 2 * i * Math.PI / points;
            ret[i * 3] = (float) Math.sin(angle) * radius + x;
            ret[i * 3 + 1] = (float) Math.cos(angle) * radius + y;
            ret[i * 3 + 2] = (height);
        }

        return ret;
    }

    /**
     * @param rays - Number of rays to use for tracing
     * @param layers - Number of layers for shadow rendering.
     * @return size required for shadow vertices mData array based on # of rays and layers
     */
    public static int getStripSize(int rays, int layers){
        return  (2 + rays + ((layers) * 2 * (rays + 1)));
    }

    /**
     * Generate shadow vertices based on params. Format : (x1,y1,z1,x2,y2,z2 ...)
     * Precondition : Light poly must be evenly distributed on a flat surface
     * Precondition : Poly vertices must be a convex
     * Precondition : Light height must be higher than any poly vertices
     *
     * @param lightPoly - Vertices of a light source.
     * @param lightPolyLength - Size of the vertices (usually lightPoly.length/3 unless w is
     * included)
     * @param poly - Vertices of opaque object casting shadow
     * @param polyLength - Size of the vertices
     * @param rays - Number of rays to use for tracing. It determines accuracy of the outline
     * (bounds) of the shadow
     * @param layers - Number of layers for shadow. It determines intensity of pen-umbra
     * @param strength - Strength of the shadow overall [0-1]
     * @param retstrips - Array mData to be filled in format : {x1, y1, z1, x2, y2, z2}
     * @return 1 if successful, error code otherwise.
     */
    public static int calculateShadow(
            float[] lightPoly,
            int lightPolyLength,
            float[] poly,
            int polyLength,
            int rays,
            int layers,
            float strength,
            float[] retstrips) {
        float[] shadowRegion = new float[lightPolyLength * polyLength * 2];
        float[] outline = new float[polyLength * 2];
        float[] umbra = new float[polyLength * lightPolyLength * 2];
        int umbraLength = 0;

        int k = 0;
        for (int j = 0; j < lightPolyLength; j++) {
            int m = 0;
            for (int i = 0; i < polyLength; i++) {
                float t = lightPoly[j * 3 + 2] - poly[i * 3 + 2];
                if (t == 0) {
                    return 0;
                }
                t = lightPoly[j * 3 + 2] / t;
                float x = lightPoly[j * 3] - t * (lightPoly[j * 3] - poly[i * 3]);
                float y = lightPoly[j * 3 + 1] - t * (lightPoly[j * 3 + 1] - poly[i * 3 + 1]);

                shadowRegion[k * 2] = x;
                shadowRegion[k * 2 + 1] = y;
                outline[m * 2] = x;
                outline[m * 2 + 1] = y;

                k++;
                m++;
            }

            if (umbraLength == 0) {
                for (int i = 0; i < polyLength * 2; i++) {
                    umbra[i] = outline[i];
                }
                umbraLength = polyLength;
            } else {
                umbraLength = Math3DHelper.intersection(outline, polyLength, umbra, umbraLength);
                if (umbraLength == 0) {
                    break;
                }

            }
        }
        int shadowRegionLength = k;

        float[] penumbra = new float[k * 2];
        int penumbraLength = Math3DHelper.hull(shadowRegion, shadowRegionLength, penumbra);
        if (umbraLength < 3) {// no real umbra make a fake one
            float[] p = new float[3];
            Math3DHelper.centroid3d(lightPoly, lightPolyLength, p);
            float[] centShadow = new float[polyLength * 2];
            for (int i = 0; i < polyLength; i++) {
                float t = p[2] - poly[i * 3 + 2];
                if (t == 0) {
                    return 0;
                }
                t = p[2] / t;
                float x = p[0] - t * (p[0] - poly[i * 3]);
                float y = p[1] - t * (p[1] - poly[i * 3 + 1]);

                centShadow[i * 2] = x;
                centShadow[i * 2 + 1] = y;
            }
            float[] c = new float[2];
            Math3DHelper.centroid2d(centShadow, polyLength, c);
            for (int i = 0; i < polyLength; i++) {
                centShadow[i * 2] = (c[0] * 9 + centShadow[i * 2]) / 10;
                centShadow[i * 2 + 1] = (c[1] * 9 + centShadow[i * 2 + 1]) / 10;
            }
            umbra = centShadow; // fake umbra
            umbraLength = polyLength; // same size as the original polygon
        }

        Math3DHelper.donutPie2(penumbra, penumbraLength, umbra, umbraLength, rays,
                layers, strength, retstrips);
        return 1;
    }
}