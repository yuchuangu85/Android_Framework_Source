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

package android.view.math;

public class Math3DHelper {

    private static final float EPSILON = 0.0000001f;

    private Math3DHelper() { }

    /**
     * Calculates [p1x+t*(p2x-p1x)=dx*t2+px,p1y+t*(p2y-p1y)=dy*t2+py],[t,t2];
     *
     * @param d - dimension in which the poly is represented (supports 2 or 3D)
     * @return float[]{t2, t, p1} or float[]{Float.NaN}
     */
    public static float[] rayIntersectPoly(float[] poly, int polyLength, float px, float py,
            float dx, float dy, int d) {
        int p1 = polyLength - 1;
        for (int p2 = 0; p2 < polyLength; p2++) {
            float p1x = poly[p1 * d + 0];
            float p1y = poly[p1 * d + 1];
            float p2x = poly[p2 * d + 0];
            float p2y = poly[p2 * d + 1];
            float div = (dx * (p1y - p2y) + dy * (p2x - p1x));
            if (div != 0) {
                float t = (dx * (p1y - py) + dy * (px - p1x)) / div;
                if (t >= 0 && t <= 1) {
                    float t2 = (p1x * (py - p2y)
                            + p2x * (p1y - py)
                            + px * (p2y - p1y))
                            / div;
                    if (t2 > 0) {
                        return new float[]{t2, t, p1};
                    }
                }
            }
            p1 = p2;
        }
        return new float[]{Float.NaN};
    }

    public static void centroid2d(float[] poly, int len, float[] ret) {
        float sumx = 0;
        float sumy = 0;
        int p1 = len - 1;
        float area = 0;
        for (int p2 = 0; p2 < len; p2++) {
            float x1 = poly[p1 * 2 + 0];
            float y1 = poly[p1 * 2 + 1];
            float x2 = poly[p2 * 2 + 0];
            float y2 = poly[p2 * 2 + 1];
            float a = (x1 * y2 - x2 * y1);
            sumx += (x1 + x2) * a;
            sumy += (y1 + y2) * a;
            area += a;
            p1 = p2;
        }
        float centroidx = sumx / (3 * area);
        float centroidy = sumy / (3 * area);
        ret[0] = centroidx;
        ret[1] = centroidy;
    }

    public static void centroid3d(float[] poly, int len, float[] ret) {
        int n = len - 1;
        double area = 0;
        double cx = 0;
        double cy = 0;
        double cz = 0;
        for (int i = 1; i < n; i++) {
            int k = i + 1;
            float a0 = poly[i * 3 + 0] - poly[0 * 3 + 0];
            float a1 = poly[i * 3 + 1] - poly[0 * 3 + 1];
            float a2 = poly[i * 3 + 2] - poly[0 * 3 + 2];
            float b0 = poly[k * 3 + 0] - poly[0 * 3 + 0];
            float b1 = poly[k * 3 + 1] - poly[0 * 3 + 1];
            float b2 = poly[k * 3 + 2] - poly[0 * 3 + 2];
            float c0 = a1 * b2 - b1 * a2;
            float c1 = a2 * b0 - b2 * a0;
            float c2 = a0 * b1 - b0 * a1;
            double areaOfTriangle = Math.sqrt(c0 * c0 + c1 * c1 + c2 * c2);
            area += areaOfTriangle;
            cx += areaOfTriangle * (poly[i * 3 + 0] + poly[k * 3 + 0] + poly[0 * 3 + 0]);
            cy += areaOfTriangle * (poly[i * 3 + 1] + poly[k * 3 + 1] + poly[0 * 3 + 1]);
            cz += areaOfTriangle * (poly[i * 3 + 2] + poly[k * 3 + 2] + poly[0 * 3 + 2]);
        }
        ret[0] = (float) (cx / (3 * area));
        ret[1] = (float) (cy / (3 * area));
        ret[2] = (float) (cz / (3 * area));
    }

    public final static int min(int x1, int x2, int x3) {
        return (x1 > x2) ? ((x2 > x3) ? x3 : x2) : ((x1 > x3) ? x3 : x1);
    }

    public final static int max(int x1, int x2, int x3) {
        return (x1 < x2) ? ((x2 < x3) ? x3 : x2) : ((x1 < x3) ? x3 : x1);
    }

    private static void xsort(float[] points, int pointsLength) {
        quicksortX(points, 0, pointsLength - 1);
    }

    public static int hull(float[] points, int pointsLength, float[] retPoly) {
        xsort(points, pointsLength);
        int n = pointsLength;
        float[] lUpper = new float[n * 2];
        lUpper[0] = points[0];
        lUpper[1] = points[1];
        lUpper[2] = points[2];
        lUpper[3] = points[3];

        int lUpperSize = 2;

        for (int i = 2; i < n; i++) {
            lUpper[lUpperSize * 2 + 0] = points[i * 2 + 0];
            lUpper[lUpperSize * 2 + 1] = points[i * 2 + 1];
            lUpperSize++;

            while (lUpperSize > 2 && !rightTurn(
                    lUpper[(lUpperSize - 3) * 2], lUpper[(lUpperSize - 3) * 2 + 1],
                    lUpper[(lUpperSize - 2) * 2], lUpper[(lUpperSize - 2) * 2 + 1],
                    lUpper[(lUpperSize - 1) * 2], lUpper[(lUpperSize - 1) * 2 + 1])) {
                // Remove the middle point of the three last
                lUpper[(lUpperSize - 2) * 2 + 0] = lUpper[(lUpperSize - 1) * 2 + 0];
                lUpper[(lUpperSize - 2) * 2 + 1] = lUpper[(lUpperSize - 1) * 2 + 1];
                lUpperSize--;
            }
        }

        float[] lLower = new float[n * 2];
        lLower[0] = points[(n - 1) * 2 + 0];
        lLower[1] = points[(n - 1) * 2 + 1];
        lLower[2] = points[(n - 2) * 2 + 0];
        lLower[3] = points[(n - 2) * 2 + 1];

        int lLowerSize = 2;

        for (int i = n - 3; i >= 0; i--) {
            lLower[lLowerSize * 2 + 0] = points[i * 2 + 0];
            lLower[lLowerSize * 2 + 1] = points[i * 2 + 1];
            lLowerSize++;

            while (lLowerSize > 2 && !rightTurn(
                    lLower[(lLowerSize - 3) * 2], lLower[(lLowerSize - 3) * 2 + 1],
                    lLower[(lLowerSize - 2) * 2], lLower[(lLowerSize - 2) * 2 + 1],
                    lLower[(lLowerSize - 1) * 2], lLower[(lLowerSize - 1) * 2 + 1])) {
                // Remove the middle point of the three last
                lLower[(lLowerSize - 2) * 2 + 0] = lLower[(lLowerSize - 1) * 2 + 0];
                lLower[(lLowerSize - 2) * 2 + 1] = lLower[(lLowerSize - 1) * 2 + 1];
                lLowerSize--;
            }
        }
        int count = 0;

        for (int i = 0; i < lUpperSize; i++) {
            retPoly[count * 2 + 0] = lUpper[i * 2 + 0];
            retPoly[count * 2 + 1] = lUpper[i * 2 + 1];
            count++;
        }

        for (int i = 1; i < lLowerSize - 1; i++) {
            retPoly[count * 2 + 0] = lLower[i * 2 + 0];
            retPoly[count * 2 + 1] = lLower[i * 2 + 1];
            count++;
        }

        return count;
    }

    private static boolean rightTurn(float ax, float ay, float bx, float by, float cx, float cy) {
        return (bx - ax) * (cy - ay) - (by - ay) * (cx - ax) > 0.00001;
    }

    /**
     * Calculates the intersection of poly1 with poly2 and puts in poly2
     * @return number of point in poly2
     */
    public static int intersection(
            float[] poly1, int poly1length, float[] poly2, int poly2length) {
        makeClockwise(poly1, poly1length);
        makeClockwise(poly2, poly2length);
        float[] poly = new float[(poly1length * poly2length + 2) * 2];
        int count = 0;
        int pcount = 0;
        for (int i = 0; i < poly1length; i++) {
            if (pointInsidePolygon(poly1[i * 2], poly1[i * 2 + 1], poly2, poly2length)) {
                poly[count * 2] = poly1[i * 2];
                poly[count * 2 + 1] = poly1[i * 2 + 1];
                count++;
                pcount++;
            }
        }
        int fromP1 = pcount;
        for (int i = 0; i < poly2length; i++) {
            if (pointInsidePolygon(poly2[i * 2], poly2[i * 2 + 1], poly1, poly1length)) {
                poly[count * 2] = poly2[i * 2];
                poly[count * 2 + 1] = poly2[i * 2 + 1];
                count++;
            }
        }
        int fromP2 = count - fromP1;
        if (fromP1 == poly1length) { // use p1
            for (int i = 0; i < poly1length; i++) {
                poly2[i * 2] = poly1[i * 2];
                poly2[i * 2 + 1] = poly1[i * 2 + 1];
            }
            return poly1length;
        }
        if (fromP2 == poly2length) { // use p2
            return poly2length;
        }
        float[] intersection = new float[2];
        for (int i = 0; i < poly2length; i++) {
            for (int j = 0; j < poly1length; j++) {
                int i1_by_2 = i * 2;
                int i2_by_2 = ((i + 1) % poly2length) * 2;
                int j1_by_2 = j * 2;
                int j2_by_2 = ((j + 1) % poly1length) * 2;
                boolean found = lineIntersection(
                        poly2[i1_by_2], poly2[i1_by_2 + 1],
                        poly2[i2_by_2], poly2[i2_by_2 + 1],
                        poly1[j1_by_2], poly1[j1_by_2 + 1],
                        poly1[j2_by_2], poly1[j2_by_2 + 1], intersection);
                if (found) {
                    poly[count * 2] = intersection[0];
                    poly[count * 2 + 1] = intersection[1];
                    count++;
                } else {
                    float dx = poly2[i * 2] - poly1[j * 2];
                    float dy = poly2[i * 2 + 1] - poly1[j * 2 + 1];

                    if (dx * dx + dy * dy < 0.01) {
                        poly[count * 2] = poly2[i * 2];
                        poly[count * 2 + 1] = poly2[i * 2 + 1];
                        count++;
                    }
                }
            }
        }
        if (count == 0) {
            return 0;
        }
        float avgx = 0;
        float avgy = 0;
        for (int i = 0; i < count; i++) {
            avgx += poly[i * 2];
            avgy += poly[i * 2 + 1];
        }
        avgx /= count;
        avgy /= count;

        float[] ctr = new float[] { avgx, avgy };
        sort(poly, count, ctr);
        int size = count;

        poly2[0] = poly[0];
        poly2[1] = poly[1];

        count = 1;
        for (int i = 1; i < size; i++) {
            float dx = poly[i * 2] - poly[(i - 1) * 2];
            float dy = poly[i * 2 + 1] - poly[(i - 1) * 2 + 1];
            if (dx * dx + dy * dy >= 0.01) {
                poly2[count * 2] = poly[i * 2];
                poly2[count * 2 + 1] = poly[i * 2 + 1];
                count++;
            }
        }
        return count;
    }

    public static void sort(float[] poly, int polyLength, float[] ctr) {
        quicksortCirc(poly, 0, polyLength - 1, ctr);
    }

    public static float angle(float x1, float y1, float[] ctr) {
        return -(float) Math.atan2(x1 - ctr[0], y1 - ctr[1]);
    }

    private static void swap(float[] points, int i, int j) {
        float x = points[i * 2];
        float y = points[i * 2 + 1];
        points[i * 2] = points[j * 2];
        points[i * 2 + 1] = points[j * 2 + 1];
        points[j * 2] = x;
        points[j * 2 + 1] = y;
    }

    private static void quicksortCirc(float[] points, int low, int high, float[] ctr) {
        int i = low, j = high;
        int p = low + (high - low) / 2;
        float pivot = angle(points[p * 2], points[p * 2 + 1], ctr);
        while (i <= j) {
            while (angle(points[i * 2], points[i * 2 + 1], ctr) < pivot) {
                i++;
            }
            while (angle(points[j * 2], points[j * 2 + 1], ctr) > pivot) {
                j--;
            }

            if (i <= j) {
                swap(points, i, j);
                i++;
                j--;
            }
        }
        if (low < j) {
            quicksortCirc(points, low, j, ctr);
        }
        if (i < high) {
            quicksortCirc(points, i, high, ctr);
        }
    }

    private static void quicksortX(float[] points, int low, int high) {
        int i = low, j = high;
        int p = low + (high - low) / 2;
        float pivot = points[p * 2];
        while (i <= j) {
            while (points[i * 2] < pivot) {
                i++;
            }
            while (points[j * 2] > pivot) {
                j--;
            }

            if (i <= j) {
                swap(points, i, j);
                i++;
                j--;
            }
        }
        if (low < j) {
            quicksortX(points, low, j);
        }
        if (i < high) {
            quicksortX(points, i, high);
        }
    }

    private static boolean pointInsidePolygon(float x, float y, float[] poly, int len) {
        boolean c = false;
        float testx = x;
        float testy = y;
        for (int i = 0, j = len - 1; i < len; j = i++) {
            if (((poly[i * 2 + 1] > testy) != (poly[j * 2 + 1] > testy)) &&
                    (testx < (poly[j * 2] - poly[i * 2]) * (testy - poly[i * 2 + 1])
                            / (poly[j * 2 + 1] - poly[i * 2 + 1]) + poly[i * 2])) {
                c = !c;
            }
        }
        return c;
    }

    private static void makeClockwise(float[] polygon, int len) {
        if (polygon == null || len == 0) {
            return;
        }
        if (!isClockwise(polygon, len)) {
            reverse(polygon, len);
        }
    }

    private static boolean isClockwise(float[] polygon, int len) {
        float sum = 0;
        float p1x = polygon[(len - 1) * 2];
        float p1y = polygon[(len - 1) * 2 + 1];
        for (int i = 0; i < len; i++) {

            float p2x = polygon[i * 2];
            float p2y = polygon[i * 2 + 1];
            sum += p1x * p2y - p2x * p1y;
            p1x = p2x;
            p1y = p2y;
        }
        return sum < 0;
    }

    private static void reverse(float[] polygon, int len) {
        int n = len / 2;
        for (int i = 0; i < n; i++) {
            float tmp0 = polygon[i * 2];
            float tmp1 = polygon[i * 2 + 1];
            int k = len - 1 - i;
            polygon[i * 2] = polygon[k * 2];
            polygon[i * 2 + 1] = polygon[k * 2 + 1];
            polygon[k * 2] = tmp0;
            polygon[k * 2 + 1] = tmp1;
        }
    }

    /**
     * Intersects two lines in parametric form.
     */
    private static final boolean lineIntersection(
            float x1, float y1,
            float x2, float y2,
            float x3, float y3,
            float x4, float y4,
            float[] ret) {

        float d = (x1 - x2) * (y3 - y4) - (y1 - y2) * (x3 - x4);
        if (d == 0.000f) {
            return false;
        }

        float dx = (x1 * y2 - y1 * x2);
        float dy = (x3 * y4 - y3 * x4);
        float x = (dx * (x3 - x4) - (x1 - x2) * dy) / d;
        float y = (dx * (y3 - y4) - (y1 - y2) * dy) / d;

        if (((x - x1) * (x - x2) > EPSILON)
                || ((x - x3) * (x - x4) > EPSILON)
                || ((y - y1) * (y - y2) > EPSILON)
                || ((y - y3) * (y - y4) > EPSILON)) {

            return false;
        }
        ret[0] = x;
        ret[1] = y;
        return true;
    }

    /**
     * Imagine a donut shaped image and trying to create triangles from its centroid (like
     * cutting a pie). This function performs such action (and also edge-case triangle strips
     * generation) then returns the resulting triangle strips.
     *
     * @param retstrips - the resulting triangle strips
     */
    public static void donutPie2(float[] penumbra, int penumbraLength,
            float[] umbra, int umbraLength, int rays, int layers, float strength,
            float[] retstrips) {
        int rings = layers + 1;

        double step = Math.PI * 2 / rays;
        float[] retxy = new float[2];
        centroid2d(umbra, umbraLength, retxy);
        float cx = retxy[0];
        float cy = retxy[1];

        float[] t1 = new float[rays];
        float[] t2 = new float[rays];

        for (int i = 0; i < rays; i++) {
            float dx = (float) Math.sin(Math.PI / 4 + step * i);
            float dy = (float) Math.cos(Math.PI / 4 + step * i);
            t2[i] = rayIntersectPoly(umbra, umbraLength, cx, cy, dx, dy, 2)[0];
            t1[i] = rayIntersectPoly(penumbra, penumbraLength, cx, cy, dx, dy, 2)[0];
        }

        int p = 0;
        // Calc the vertex
        for (int r = 0; r < layers; r++) {
            int startp = p;
            for (int i = 0; i < rays; i++) {
                float dx = (float) Math.sin(Math.PI / 4 + step * i);
                float dy = (float) Math.cos(Math.PI / 4 + step * i);

                for (int j = r; j < (r + 2); j++) {
                    float jf = j / (float) (rings - 1);
                    float t = t1[i] + jf * (t2[i] - t1[i]);
                    float op = (jf + 1 - 1 / (1 + (t - t1[i]) * (t - t1[i]))) / 2;

                    retstrips[p * 3] = dx * t + cx;
                    retstrips[p * 3 + 1] = dy * t + cy;
                    retstrips[p * 3 + 2] = jf * op * strength;

                    p++;
                }
            }
            retstrips[p * 3] = retstrips[startp * 3];
            retstrips[p * 3 + 1] = retstrips[startp * 3 + 1];
            retstrips[p * 3 + 2] = retstrips[startp * 3 + 2];
            p++;
            startp++;
            retstrips[p * 3] = retstrips[startp * 3];
            retstrips[p * 3 + 1] = retstrips[startp * 3 + 1];
            retstrips[p * 3 + 2] = retstrips[startp * 3 + 2];
            p++;
        }
        int oldp = p - 1;
        retstrips[p * 3] = retstrips[oldp * 3];
        retstrips[p * 3 + 1] = retstrips[oldp * 3 + 1];
        retstrips[p * 3 + 2] = retstrips[oldp * 3 + 2];
        p+=2;

        oldp = p;
        for (int k = 0; k < rays; k++) {
            int i = k / 2;
            if ((k & 1) == 1) { // traverse the inside in a zig zag pattern
                // for strips
                i = rays - i - 1;
            }
            float dx = (float) Math.sin(Math.PI / 4 + step * i);
            float dy = (float) Math.cos(Math.PI / 4 + step * i);

            float jf = 1;

            float t = t1[i] + jf * (t2[i] - t1[i]);
            float op = (jf + 1 - 1 / (1 + (t - t1[i]) * (t - t1[i]))) / 2;

            retstrips[p * 3] = dx * t + cx;
            retstrips[p * 3 + 1] = dy * t + cy;
            retstrips[p * 3 + 2] = jf * op * strength;
            p++;
        }
        p = oldp - 1;
        retstrips[p * 3] = retstrips[oldp * 3];
        retstrips[p * 3 + 1] = retstrips[oldp * 3 + 1];
        retstrips[p * 3 + 2] = retstrips[oldp * 3 + 2];
    }

    /**
     * @return Rect bound of flattened (ignoring z). LTRB
     * @param dimension - 2D or 3D
     */
    public static float[] flatBound(float[] poly, int dimension) {
        int polySize = poly.length/dimension;
        float left = poly[0];
        float right = poly[0];
        float top = poly[1];
        float bottom = poly[1];

        for (int i = 0; i < polySize; i++) {
            float x = poly[i * dimension + 0];
            float y = poly[i * dimension + 1];

            if (left > x) {
                left = x;
            } else if (right < x) {
                right = x;
            }

            if (top > y) {
                top = y;
            } else if (bottom < y) {
                bottom = y;
            }
        }
        return new float[]{left, top, right, bottom};
    }

    /**
     * Translate the polygon to x and y
     * @param dimension in what dimension is polygon represented (supports 2 or 3D).
     */
    public static void translate(float[] poly, float translateX, float translateY, int dimension) {
        int polySize = poly.length/dimension;

        for (int i = 0; i < polySize; i++) {
            poly[i * dimension + 0] += translateX;
            poly[i * dimension + 1] += translateY;
        }
    }

}

