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
import android.graphics.Bitmap.Config;

import java.util.Arrays;

import static android.view.math.Math3DHelper.max;
import static android.view.math.Math3DHelper.min;

/**
 * 2D Triangle buffer element that colours using z value. (z scale set).
 */
class TriangleBuffer {
    int mWidth;
    int mHeight;
    int mImgWidth;
    int mImgHeight;
    int mBorder;
    Bitmap mBitmap;
    int mData[];
    private float mMinX;
    private float mMaxX;
    private float mMinY;
    private float mMaxY;

    public void setSize(int width, int height, int border) {
        if (mWidth == width && mHeight == height) {
            return;
        }
        mWidth = width-2*border;
        mHeight = height-2*border;
        mBorder = border;
        mImgWidth = width;
        mImgHeight = height;

        setScale(0, width, 0, height);

        mBitmap = Bitmap.createBitmap(width, height, Config.ARGB_8888);
        mData = new int[width * height];
    }

    public void drawTriangles(int[] index, float[] vert, float[] color,float scale) {
        int indexSize = index.length / 3;
        for (int i = 0; i < indexSize; i++) {
            int vIndex = index[i * 3 + 0];
            float vx = vert[vIndex * 2 + 0];
            float vy = vert[vIndex * 2 + 1];
            float c =  scale*color[vIndex * 4 + 3];
            float fx3 = vx, fy3 = vy, fz3 = c;

            vIndex = index[i * 3 + 1];
            vx = vert[vIndex * 2 + 0];
            vy = vert[vIndex * 2 + 1];
            c =  scale*color[vIndex * 4 + 3];
            float fx2 = vx, fy2 = vy, fz2 = c;

            vIndex = index[i * 3 + 2];
            vx = vert[vIndex * 2 + 0];
            vy = vert[vIndex * 2 + 1];
            c =  scale*color[vIndex * 4 + 3];
            float fx1 = vx, fy1 = vy, fz1 = c;

            triangleZBuffMin(mData, mImgWidth, mImgHeight, fx3, fy3, fz3, fx2, fy2,
                    fz2, fx1, fy1, fz1);
            triangleZBuffMin(mData, mImgWidth, mImgHeight, fx1, fy1, fz1, fx2, fy2,
                    fz2, fx3, fy3, fz3);
        }
        mBitmap.setPixels(mData, 0, mWidth, 0, 0, mWidth, mHeight);
    }

    public void drawTriangles(float[] strip,float scale) {
        for (int i = 0; i < strip.length-8; i+=3) {
            float fx3 = strip[i], fy3 = strip[i+1], fz3 = scale* strip[i+2];
            float fx2 = strip[i+3], fy2 = strip[i+4], fz2 = scale* strip[i+5];
            float fx1 = strip[i+6], fy1 = strip[i+7], fz1 = scale* strip[i+8];

            if (fx1*(fy2-fy3)+fx2*(fy3-fy1)+fx3*(fy1-fy2) ==0) {
                continue;
            }
            triangleZBuffMin(mData, mImgWidth, mImgHeight, fx3, fy3, fz3, fx2, fy2,
                    fz2, fx1, fy1, fz1);
        }
        mBitmap.setPixels(mData, 0, mWidth, 0, 0, mWidth, mHeight);
    }

    public Bitmap getImage() {
        return mBitmap;
    }

    private static void triangleZBuffMin(int[] buff, int w, int h, float fx3,
            float fy3, float fz3, float fx2, float fy2, float fz2, float fx1,
            float fy1, float fz1) {
        if (((fx1 - fx2) * (fy3 - fy2) - (fy1 - fy2) * (fx3 - fx2)) < 0) {
            float tmpx = fx1;
            float tmpy = fy1;
            float tmpz = fz1;
            fx1 = fx2;
            fy1 = fy2;
            fz1 = fz2;
            fx2 = tmpx;
            fy2 = tmpy;
            fz2 = tmpz;
        }
        // using maxmima
        // solve([x1*dx+y1*dy+zoff=z1,x2*dx+y2*dy+zoff=z2,x3*dx+y3*dy+zoff=z3],[dx,dy,zoff]);
        double d = (fx1 * (fy3 - fy2) - fx2 * fy3 + fx3 * fy2 + (fx2 - fx3) * fy1);
        if (d == 0) {
            return;
        }
        float dx = (float) (-(fy1 * (fz3 - fz2) - fy2 * fz3 + fy3 * fz2 + (fy2 - fy3)
                * fz1) / d);
        float dy = (float) ((fx1 * (fz3 - fz2) - fx2 * fz3 + fx3 * fz2 + (fx2 - fx3)
                * fz1) / d);
        float zoff = (float) ((fx1 * (fy3 * fz2 - fy2 * fz3) + fy1
                * (fx2 * fz3 - fx3 * fz2) + (fx3 * fy2 - fx2 * fy3) * fz1) / d);

        // 28.4 fixed-point coordinates
        int y1 = (int) (16.0f * fy1 + .5f);
        int y2 = (int) (16.0f * fy2 + .5f);
        int y3 = (int) (16.0f * fy3 + .5f);

        int x1 = (int) (16.0f * fx1 + .5f);
        int x2 = (int) (16.0f * fx2 + .5f);
        int x3 = (int) (16.0f * fx3 + .5f);

        int dx12 = x1 - x2;
        int dx23 = x2 - x3;
        int dx31 = x3 - x1;

        int dy12 = y1 - y2;
        int dy23 = y2 - y3;
        int dy31 = y3 - y1;

        int fdx12 = dx12 << 4;
        int fdx23 = dx23 << 4;
        int fdx31 = dx31 << 4;

        int fdy12 = dy12 << 4;
        int fdy23 = dy23 << 4;
        int fdy31 = dy31 << 4;

        int minx = (min(x1, x2, x3) + 0xF) >> 4;
        int maxx = (max(x1, x2, x3) + 0xF) >> 4;
        int miny = (min(y1, y2, y3) + 0xF) >> 4;
        int maxy = (max(y1, y2, y3) + 0xF) >> 4;

        if (miny < 0) {
            miny = 0;
        }
        if (minx < 0) {
            minx = 0;
        }
        if (maxx > w) {
            maxx = w;
        }
        if (maxy > h) {
            maxy = h;
        }
        int off = miny * w;

        int c1 = dy12 * x1 - dx12 * y1;
        int c2 = dy23 * x2 - dx23 * y2;
        int c3 = dy31 * x3 - dx31 * y3;

        if (dy12 < 0 || (dy12 == 0 && dx12 > 0)) {
            c1++;
        }
        if (dy23 < 0 || (dy23 == 0 && dx23 > 0)) {
            c2++;
        }
        if (dy31 < 0 || (dy31 == 0 && dx31 > 0)) {
            c3++;
        }
        int cy1 = c1 + dx12 * (miny << 4) - dy12 * (minx << 4);
        int cy2 = c2 + dx23 * (miny << 4) - dy23 * (minx << 4);
        int cy3 = c3 + dx31 * (miny << 4) - dy31 * (minx << 4);

        for (int y = miny; y < maxy; y++) {
            int cx1 = cy1;
            int cx2 = cy2;
            int cx3 = cy3;
            float p = zoff + dy * y;
            for (int x = minx; x < maxx; x++) {
                if (cx1 > 0 && cx2 > 0 && cx3 > 0) {
                    int point = x + off;
                    float zval = p + dx * x;
                    buff[point] = ((int) (zval * 255)) << 24;
                }
                cx1 -= fdy12;
                cx2 -= fdy23;
                cx3 -= fdy31;
            }
            cy1 += fdx12;
            cy2 += fdx23;
            cy3 += fdx31;
            off += w;
        }
    }

    private void setScale(float minx, float maxx, float miny, float maxy) {
        mMinX = minx;
        mMaxX = maxx;
        mMinY = miny;
        mMaxY = maxy;
    }

    public void clear() {
        Arrays.fill(mData, 0);
    }
}