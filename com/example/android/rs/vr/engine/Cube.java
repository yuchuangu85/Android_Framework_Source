/*
 * Copyright (C) 2015 The Android Open Source Project
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
package com.example.android.rs.vr.engine;

import java.text.*;
import java.util.Arrays;

public class Cube extends TriData {

    private float[] mTrim = {0, 0, 0, 0, 0, 0 };

    public Cube(Volume v, float delta) {
        this(v, delta, new float[]{0, 0, 0, 0, 0, 0});
    }

    public Cube(Volume v, float delta, float[] trim) {
        this();
        this.mTrim = trim;
        float minx = delta + trim[0] * (v.mDimx - delta);
        float miny = delta + trim[1] * (v.mDimy - delta);
        float minz = delta + trim[2] * (v.mDimz - delta);
        float maxx = v.mDimx - delta - trim[3] * (v.mDimx - delta);
        float maxy = v.mDimy - delta - trim[4] * (v.mDimy - delta);
        float maxz = v.mDimz - delta - trim[5] * (v.mDimz - delta);
        mVert = new float[]{
                minx, miny, minz,
                maxx, miny, minz,
                maxx, maxy, minz,
                minx, maxy, minz,
                minx, miny, maxz,
                maxx, miny, maxz,
                maxx, maxy, maxz,
                minx, maxy, maxz,
        };
    }

    public void clone(Cube src) {
        System.arraycopy(src.mTrim, 0, mTrim, 0, mTrim.length);
        mVert = Arrays.copyOf(src.mVert, src.mVert.length);
        mIndex = Arrays.copyOf(src.mIndex, src.mIndex.length);
    }

    public float[] getTrim() {
        return mTrim;
    }

    @Override
    public String toString() {
        return "CUBE[" + fs(mVert, 0, 3) + "][" + fs(mVert, 18, 3) + "]";
    }

    private static String fs(float[] f, int off, int n) {
        DecimalFormat df = new DecimalFormat("##0.000");
        String ret = "";
        for (int i = off; i < off + n; i++) {
            String s = "       " + df.format(f[i]);

            if (i != off) {
                ret += ",";
            }
            ret += s.substring(s.length() - 8);

        }
        return ret;
    }

    public Cube() {
        mVert = new float[]{
                -1.f, -1.f, -1.f,
                1.f, -1.f, -1.f,
                1.f, 1.f, -1.f,
                -1.f, 1.f, -1.f,
                -1.f, -1.f, 1.f,
                1.f, -1.f, 1.f,
                1.f, 1.f, 1.f,
                -1.f, 1.f, 1.f,
        };

        mIndex = new int[]{
                2, 1, 0,
                0, 3, 2,
                7, 4, 5,
                5, 6, 7,
                1, 2, 6,
                6, 5, 1,
                4, 7, 3,
                3, 0, 4,
                2, 3, 7,
                7, 6, 2,
                0, 1, 5,
                5, 4, 0
        };
        for (int i = 0; i < mIndex.length; i++) {
            mIndex[i] *= 3;
        }
    }
}
