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
 * Generates vertices, colours, and indices required for ambient shadow. Ambient shadows are
 * assumed to be raycasted from the centroid of the polygon, and reaches upto a ratio based on
 * the polygon's z-height.
 */
class AmbientShadowVertexCalculator {

    private final float[] mVertex;
    private final float[] mColor;
    private final int[] mIndex;
    private final AmbientShadowConfig mConfig;

    public AmbientShadowVertexCalculator(AmbientShadowConfig config) {
        mConfig = config;

        int rings = mConfig.getLayers() + 1;
        int size = mConfig.getRays() * rings;

        mVertex = new float[size * 2];
        mColor = new float[size * 4];
        mIndex = new int[(size * 2 + (mConfig.getRays() - 2)) * 3];
    }

    /**
     * Generates vertex using the polygon info
     * @param polygon 3d polygon info in format : {x1, y1, z1, x2, y2, z2 ...}
     * @return true if vertices are generated with right colour/index. False otherwise.
     */
    public boolean generateVertex(float[] polygon) {
        // Despite us not using z coord, we want calculations in 3d space as our polygon is using
        // 3d coord system.
        float[] centroidxy = new float[3];
        int polygonLength = polygon.length/3;

        Math3DHelper.centroid3d(polygon, polygonLength, centroidxy);

        float cx = centroidxy[0];
        float cy = centroidxy[1];

        Rays rays = new Rays(mConfig.getRays());
        int raysLength = rays.dx.length;
        float rayDist[] = new float[mConfig.getRays()];

        float[] rayHeights = new float[mConfig.getRays()];

        for (int i = 0; i < raysLength; i++) {
            float dx = rays.dx[i];
            float dy = rays.dy[i];

            float[] intersection = Math3DHelper.rayIntersectPoly(polygon, polygonLength, cx, cy,
                    dx, dy, 3);
            if (intersection.length == 1) {
                return false;
            }
            rayDist[i] = intersection[0];
            int index = (int) (intersection[2] * 3);
            int index2 = (int) (((intersection[2] + 1) % polygonLength) * 3);
            float h1 = polygon[index + 2] * mConfig.getShadowBoundRatio();
            float h2 = polygon[index2 + 2] * mConfig.getShadowBoundRatio();
            rayHeights[i] = h1 + intersection[1] * (h2 - h1);
        }

        int rings = mConfig.getLayers() + 1;
        for (int i = 0; i < raysLength; i++) {
            float dx = rays.dx[i];
            float dy = rays.dy[i];
            float cast = rayDist[i] * rayHeights[i];

            float opacity = .8f * (0.5f / (mConfig.getEdgeScale() / 10f));
            for (int j = 0; j < rings; j++) {
                int p = i * rings + j;
                float jf = j / (float) (rings - 1);
                float t = rayDist[i] + jf * (cast - rayDist[i]);

                mVertex[p * 2 + 0] = dx * t + cx;
                mVertex[p * 2 + 1] = dy * t + cy;
                // TODO: we might be able to optimize this in the future.
                mColor[p * 4 + 0] = 0;
                mColor[p * 4 + 1] = 0;
                mColor[p * 4 + 2] = 0;
                mColor[p * 4 + 3] = (1 - jf) * opacity;
            }
        }

        int k = 0;
        for (int i = 0; i < mConfig.getRays(); i++) {
            for (int j = 0; j < mConfig.getLayers(); j++) {
                int r1 = j + rings * i;
                int r2 = j + rings * ((i + 1) % mConfig.getRays());

                mIndex[k * 3 + 0] = r1;
                mIndex[k * 3 + 1] = r1 + 1;
                mIndex[k * 3 + 2] = r2;
                k++;
                mIndex[k * 3 + 0] = r2;
                mIndex[k * 3 + 1] = r1 + 1;
                mIndex[k * 3 + 2] = r2 + 1;
                k++;
            }
        }
        int ringOffset = 0;
        for (int i = 1; i < mConfig.getRays() - 1; i++, k++) {
            mIndex[k * 3 + 0] = ringOffset;
            mIndex[k * 3 + 1] = ringOffset + rings * i;
            mIndex[k * 3 + 2] = ringOffset + rings * (1 + i);
        }
        return true;
    }

    public int[] getIndex() {
        return mIndex;
    }

    /**
     * @return list of vertices in 2d in format : {x1, y1, x2, y2 ...}
     */
    public float[] getVertex() {
        return mVertex;
    }

    public float[] getColor() {
        return mColor;
    }

    private static class Rays {
        public final float[] dx;
        public final float[] dy;
        public final double deltaAngle;

        public Rays(int rays) {
            dx = new float[rays];
            dy = new float[rays];
            deltaAngle = 2 * Math.PI / rays;

            for (int i = 0; i < rays; i++) {
                dx[i] = (float) Math.sin(deltaAngle * i);
                dy[i] = (float) Math.cos(deltaAngle * i);
            }
        }
    }

}
