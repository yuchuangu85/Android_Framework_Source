/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.rs.image2;

import java.lang.Math;

import android.support.v8.renderscript.*;
import android.util.Log;

public class Convolve3x3 extends TestBase {
    private ScriptC_convolve3x3 mScript;
    private ScriptIntrinsicConvolve3x3 mIntrinsic;

    private int mWidth;
    private int mHeight;
    private boolean mUseIntrinsic;

    public Convolve3x3(boolean useIntrinsic) {
        mUseIntrinsic = useIntrinsic;
    }

    private float blend(float v1, float v2, float p) {
        return (v2 * p) + (v1 * (1.f-p));
    }

    private float[] updateMatrix(float str) {
        float f[] = new float[9];
        float cf1 = blend(1.f / 9.f, 0.f, str);
        float cf2 = blend(1.f / 9.f, -1.f, str);
        float cf3 = blend(1.f / 9.f, 5.f, str);
        f[0] =  cf1;  f[1] = cf2;   f[2] = cf1;
        f[3] =  cf2;  f[4] = cf3;   f[5] = cf2;
        f[6] =  cf1;  f[7] = cf2;   f[8] = cf1;
        return f;
    }

    public void createTest(android.content.res.Resources res) {
        mWidth = mInPixelsAllocation.getType().getX();
        mHeight = mInPixelsAllocation.getType().getY();

        float f[] = updateMatrix(1.f);
        if (mUseIntrinsic) {
            mIntrinsic = ScriptIntrinsicConvolve3x3.create(mRS, Element.U8_4(mRS));
            mIntrinsic.setCoefficients(f);
            mIntrinsic.setInput(mInPixelsAllocation);
        } else {
            mScript = new ScriptC_convolve3x3(mRS);
            mScript.set_gCoeffs(f);
            mScript.set_gIn(mInPixelsAllocation);
            mScript.set_gWidth(mWidth);
            mScript.set_gHeight(mHeight);
        }
    }

    public void animateBars(float time) {
        float f[] = updateMatrix(time % 1.f);
        if (mUseIntrinsic) {
            mIntrinsic.setCoefficients(f);
        } else {
            mScript.set_gCoeffs(f);
        }
    }

    public void runTest() {
        if (mUseIntrinsic) {
            mIntrinsic.forEach(mOutPixelsAllocation);
        } else {
            mScript.forEach_root(mOutPixelsAllocation);
        }
    }

}
