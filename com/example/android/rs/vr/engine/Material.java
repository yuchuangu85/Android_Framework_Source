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


import android.graphics.Color;
import android.renderscript.Allocation;
import android.renderscript.Element;
import android.renderscript.RenderScript;
import android.renderscript.Type;

/**
 * Defines the material properties of a pixel value
 * RGB, Opacity diffuse specular, ambient
 */
public class Material {
    public static final int SIZE = 64 * 1024;
    public static final int STRIDE = 8;

    Allocation mOpacityAllocation;
    Allocation mColorMapAllocation;

    public byte[] mOpacityTable = new byte[SIZE];
    MaterialProp[] mMaterialProp = new MaterialProp[0];
    public byte[] mColor = new byte[SIZE * STRIDE]; // table contain r, g, b, A, S, D
    public static final int RED = 0;
    public static final int GREEN = 1;
    public static final int BLUE = 2;
    public static final int DIFF = 4;
    public static final int SPEC = 5;
    public static final int AMB = 6;

    public static class MaterialProp {
        int mPos;
        int mRed;
        int mGreen;
        int mBlue;
        float mDiffuse;
        float mSpecular;
        float mAmbient;
    }

    /**
     * Clamp limits to less than or equal to 255
     * this is done with a very efficient bit fiddling trick
     * @param c value being clamped
     * @return values in the range 0-255
     */
    private static int clamp(int c) {
        final int N = 255; // the value clamp is limiting to
        c &= ~(c >> 31);
        c -= N;
        c &= (c >> 31);
        c += N;
        return c;
    }

    public static class Opactiy {
        int mPos;
        float mValue;
    }

    public Opactiy[] mOpacity = new Opactiy[0];

    public Material() {
        simpleSetup(1150, 1300);
    }

    public void simpleSetup(int start, int end) {
        float diffuse = .7f;
        float specular = .0f;
        float ambient = .3f;
        for (int i = Short.MIN_VALUE; i < Short.MAX_VALUE; i++) {
            int off = i & 0xFFFF;
            int p = STRIDE * (off);
            byte v = (byte) clamp((int) (255 * (i - start) / (end - start)));
            mColor[p + RED] = v;
            mColor[p + GREEN] = v;
            mColor[p + BLUE] = v;
            mColor[p + DIFF] = (byte) (255 * diffuse);
            mColor[p + SPEC] = (byte) (255 * specular);
            mColor[p + AMB] = (byte) (255 * ambient);
            mOpacityTable[off] = v;
        }
    }

    public void setup(int[] means, int start, int end) {
        int[] pos = new int[means.length - 1];
        int[] red = new int[means.length - 1];
        int[] green = new int[means.length - 1];
        int[] blue = new int[means.length - 1];

        for (int i = 0; i < pos.length; i++) {
            pos[i] = (means[i] + means[i + 1]) / 2;
            float f = i / (float) (pos.length - 1);
            float h = 1 - f * f * f;
            float s = (float) (1 / (1 + Math.exp((f - .5) * 5)));
            int rgb = Color.HSVToColor(new float[]{360 * h, s, f});
            red[i] = (rgb >> 16) & 0xff;
            green[i] = (rgb >> 8) & 0xff;
            blue[i] = (rgb >> 0) & 0xff;
        }
        mMaterialProp = new MaterialProp[pos.length];

        float diffuse = .7f;
        float specular = .0f;
        float ambient = .3f;
        for (int i = 0; i < pos.length; i++) {
            mMaterialProp[i] = new MaterialProp();
            mMaterialProp[i].mAmbient = ambient;
            mMaterialProp[i].mSpecular = specular;
            mMaterialProp[i].mDiffuse = diffuse;
            mMaterialProp[i].mPos = pos[i];
            float t = i / (float) (pos.length - 1);

            mMaterialProp[i].mRed = red[i];
            mMaterialProp[i].mGreen = green[i];
            mMaterialProp[i].mBlue = blue[i];

        }
        mOpacity = new Opactiy[2];
        mOpacity[0] = new Opactiy();
        mOpacity[0].mPos = start;
        mOpacity[0].mValue = 0;

        mOpacity[1] = new Opactiy();
        mOpacity[1].mPos = end;
        mOpacity[1].mValue = 1;

        buildOpacityTable();
        buildMaterialProp();
    }

    public void setup(int[][] opacity, int[][] material) {
        mMaterialProp = new MaterialProp[material.length];

        for (int i = 0; i < material.length; i++) {
            int rgb = material[i][1] & 0xFFFFFF;

            float ambient = (material[i].length > 2) ? material[i][2] / 100.f : .2f;
            float diffuse = (material[i].length > 3) ? material[i][3] / 100.f : .6f;
            float specular = (material[i].length > 4) ? material[i][4] / 100.f : .2f;

            mMaterialProp[i] = new MaterialProp();
            mMaterialProp[i].mAmbient = ambient;
            mMaterialProp[i].mSpecular = specular;
            mMaterialProp[i].mDiffuse = diffuse;
            mMaterialProp[i].mRed = (rgb >> 16) & 0xff;
            mMaterialProp[i].mGreen = (rgb >> 8) & 0xff;
            mMaterialProp[i].mBlue = (rgb >> 0) & 0xff;

            mMaterialProp[i].mPos = material[i][0];

        }
        mOpacity = new Opactiy[opacity.length];
        for (int i = 0; i < opacity.length; i++) {
            mOpacity[i] = new Opactiy();
            mOpacity[i].mPos = opacity[i][0];
            mOpacity[i].mValue = opacity[i][1] / 255.f;
        }
        buildOpacityTable();
        buildMaterialProp();
    }

    public void setup(int start, int end) {
        int[] pos = {1050, 1140, 1200, 1210, 1231};

        mMaterialProp = new MaterialProp[pos.length];

        float diffuse = .7f;
        float specular = .0f;
        float ambient = .3f;
        for (int i = 0; i < pos.length; i++) {
            mMaterialProp[i] = new MaterialProp();
            mMaterialProp[i].mAmbient = ambient;
            mMaterialProp[i].mSpecular = specular;
            mMaterialProp[i].mDiffuse = diffuse;
            mMaterialProp[i].mPos = pos[i];
            float t = i / (float) (pos.length - 1);
            int rgb = (int) (Math.random() * 0xFFFFFF);
            mMaterialProp[i].mRed = (rgb >> 16) & 0xff;
            mMaterialProp[i].mGreen = (rgb >> 8) & 0xff;
            mMaterialProp[i].mBlue = (rgb >> 0) & 0xff;
        }
        mOpacity = new Opactiy[2];
        mOpacity[0] = new Opactiy();
        mOpacity[0].mPos = start;
        mOpacity[0].mValue = 0;

        mOpacity[1] = new Opactiy();
        mOpacity[1].mPos = end;
        mOpacity[1].mValue = 1;

        buildOpacityTable();
        buildMaterialProp();
    }

    void buildOpacityTable() {
        if (mOpacity.length == 0) {
            return;
        }
        for (int i = Short.MIN_VALUE; i <= mOpacity[0].mPos; i++) {
            int p = i & 0xFFFF;
            mOpacityTable[p] = (byte) (mOpacity[0].mValue * 255);
        }
        for (int k = 0; k < mOpacity.length - 1; k++) {
            for (int i = mOpacity[k].mPos; i < mOpacity[k + 1].mPos; i++) {
                int p = i & 0xFFFF;
                float dist = mOpacity[k + 1].mPos - mOpacity[k].mPos;
                float t = (i - mOpacity[k].mPos) / dist;
                float v = mOpacity[k].mValue * (1 - t) + t * mOpacity[k + 1].mValue;
                mOpacityTable[p] = (byte) (v * 255);
            }
        }
        int last = mOpacity.length - 1;
        for (int i = mOpacity[last].mPos; i <= Short.MAX_VALUE; i++) {
            int p = i & 0xFFFF;
            mOpacityTable[p] = (byte) (mOpacity[last].mValue * 255);

        }
    }

    public void buildMaterialProp() {
        MaterialProp[] m = mMaterialProp;
        if (m.length == 0) {
            return;
        }
        {
            MaterialProp mp = m[0];
            int red = m[0].mRed;
            int green = m[0].mGreen;
            int blue = m[0].mBlue;

            for (int i = Short.MIN_VALUE; i <= m[0].mPos; i++) {
                int p = STRIDE * (i & 0xFFFF);
                mColor[p + RED] = (byte) red;
                mColor[p + GREEN] = (byte) green;
                mColor[p + BLUE] = (byte) blue;

                mColor[p + DIFF] = (byte) (255 * mp.mDiffuse);
                mColor[p + SPEC] = (byte) (255 * mp.mSpecular);
                mColor[p + AMB] = (byte) (255 * mp.mAmbient);
            }
        }
        for (int k = 0; k < m.length - 1; k++) {
            for (int i = m[k].mPos; i < m[k + 1].mPos; i++) {
                int p = STRIDE * (i & 0xFFFF);
                float dist = m[k + 1].mPos - m[k].mPos;
                float t2 = (i - m[k].mPos) / dist;
                float t1 = 1 - t2;


                int red = (int) (m[k].mRed * t1 + m[k + 1].mRed * t2);
                int green = (int) (m[k].mGreen * t1 + m[k + 1].mGreen * t2);
                int blue = (int) (m[k].mBlue * t1 + m[k + 1].mBlue * t2);

                float diffuse = m[k].mDiffuse * t1 + m[k + 1].mDiffuse * t2;
                float specular = m[k].mSpecular * t1 + m[k + 1].mSpecular * t2;
                float ambient = m[k].mAmbient * t1 + m[k + 1].mAmbient * t2;


                mColor[p + RED] = (byte) red;
                mColor[p + GREEN] = (byte) green;
                mColor[p + BLUE] = (byte) blue;

                mColor[p + DIFF] = (byte) (255 * diffuse);
                mColor[p + SPEC] = (byte) (255 * specular);
                mColor[p + AMB] = (byte) (255 * ambient);
            }
        }
        {
            int last = m.length - 1;
            MaterialProp mp = m[last];
            int red = mp.mRed;
            int green = mp.mGreen;
            int blue = mp.mBlue;
            for (int i = mp.mPos; i <= Short.MAX_VALUE; i++) {
                int p = STRIDE * (i & 0xFFFF);
                mColor[p + RED] = (byte) red;
                mColor[p + GREEN] = (byte) green;
                mColor[p + BLUE] = (byte) blue;

                mColor[p + DIFF] = (byte) (255 * mp.mDiffuse);
                mColor[p + SPEC] = (byte) (255 * mp.mSpecular);
                mColor[p + AMB] = (byte) (255 * mp.mAmbient);
            }
        }
    }

    public Allocation getOpacityAllocation(RenderScript rs) {
        if (mOpacityAllocation == null) {
            Type.Builder b = new Type.Builder(rs, Element.U8(rs));
            b.setX(mOpacityTable.length);
            mOpacityAllocation = Allocation.createTyped(rs, b.create());
        }
        mOpacityAllocation.copyFromUnchecked(mOpacityTable);
        return mOpacityAllocation;
    }

    public Allocation getColorMapAllocation(RenderScript rs) {
        if (mColorMapAllocation == null) {
            Type.Builder b = new Type.Builder(rs, Element.U8_4(rs));
            b.setX(mColor.length / 4);
            mColorMapAllocation = Allocation.createTyped(rs, b.create());
        }
        mColorMapAllocation.copyFromUnchecked(mColor);
        return mColorMapAllocation;
    }
}
