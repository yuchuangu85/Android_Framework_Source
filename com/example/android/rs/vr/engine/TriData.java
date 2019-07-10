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

import android.util.Log;

import java.io.FileReader;
import java.io.LineNumberReader;
import java.text.DecimalFormat;
import java.util.Arrays;

/**
 * Simple representation triangulated surface
 */
public class TriData {
    private static final String LOGTAG = "TriData";
    protected float[] mVert;
    protected int[] mIndex;

    public TriData() {
     }

    public void print() {
        class Fmt extends DecimalFormat {
            public Fmt() {
                super("      ##0.000");
            }

            public String f(double number) {
                String ret = "           "+super.format(number);
                return ret.substring(ret.length() - 7);
            }
        }
        Fmt df = new Fmt();
        for (int i = 0; i < mVert.length; i += 3) {

            String s = (i / 3 + "[ " + df.f(mVert[i]));
            s += (", " + df.f(mVert[i + 1]));
            Log.v(LOGTAG, s + ", " + df.f(mVert[i + 2]) + "]");
        }
    }

    public TriData(TriData clone) {

        mVert = Arrays.copyOf(clone.mVert, clone.mVert.length);
        mIndex = Arrays.copyOf(clone.mIndex, clone.mIndex.length);
    }

    public void scale(float[] s) {
        for (int i = 0; i < mVert.length; i += 3) {
            mVert[i] *= s[0];
            mVert[i + 1] *= s[1];
            mVert[i + 2] *= s[2];
        }
    }

    public void scale(double[] s) {
        for (int i = 0; i < mVert.length; i += 3) {
            mVert[i] *= s[0];
            mVert[i + 1] *= s[1];
            mVert[i + 2] *= s[2];
        }
    }

    public void transform(Matrix m) {
        for (int i = 0; i < mVert.length; i += 3) {
            m.mult3(mVert, i, mVert, i);
        }
    }

    public void transform(Matrix m, TriData out) {

        for (int i = 0; i < mVert.length; i += 3) {
            m.mult3(mVert, i, out.mVert, i);
        }
    }

    /**
     * Read some simple triangle format used in testing
     * @param fileName
     */
    public void read(String fileName) {
        try {
            FileReader fr = new FileReader(fileName);
            LineNumberReader lnr = new LineNumberReader(fr);
            int num_verts = Integer.parseInt(lnr.readLine());
            Log.v(LOGTAG, "verts =" + num_verts);
            mVert = new float[num_verts * 3];
            int k = 0;
            for (int i = 0; i < num_verts; i++) {
                String[] s = lnr.readLine().split("\\s");

                for (int j = 0; j < s.length; j++) {
                    mVert[k++] = Float.parseFloat(s[j]);
                }
            }
            int num_tri = Integer.parseInt(lnr.readLine());
            Log.v(LOGTAG, "tri =" + num_tri);
            mIndex = new int[3 * num_tri];
            k = 0;
            for (int i = 0; i < num_tri; i++) {
                String[] s = lnr.readLine().split("\\s");
                for (int j = 0; j < s.length; j++) {
                    mIndex[k++] = Integer.parseInt(s[j]);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
