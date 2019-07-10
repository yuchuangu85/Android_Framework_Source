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

public class Convolve5x5 extends TestBase {
    private ScriptC_convolve5x5 mScript;
    private ScriptIntrinsicConvolve5x5 mIntrinsic;

    private int mWidth;
    private int mHeight;
    private boolean mUseIntrinsic;

    public Convolve5x5(boolean useIntrinsic) {
        mUseIntrinsic = useIntrinsic;
    }

    private float blend(float v1, float v2, float p) {
        return (v2 * p) + (v1 * (1.f-p));
    }

    private float[] updateMatrix(float str) {
        float f[] = new float[25];
        final float f125 = 1.f / 25.f;
        float cf1 = blend(f125, -1.f, str);
        float cf2 = blend(f125, -3.f, str);
        float cf3 = blend(f125, -4.f, str);
        float cf4 = blend(f125, 6.f, str);
        float cf5 = blend(f125, 20.f, str);
        float cf6 = blend(f125, 0.f, str);
        f[0] = cf1;  f[1] = cf2; f[2] = cf3; f[3] = cf2; f[4] = cf1;
        f[5] = cf2;  f[6] = cf6; f[7] = cf4; f[8] = cf6; f[9] = cf2;
        f[10]= cf3;  f[11]= cf4; f[12]= cf5; f[13]= cf4; f[14]= cf3;
        f[15]= cf2;  f[16]= cf6; f[17]= cf4; f[18]= cf6; f[19]= cf2;
        f[20]= cf1;  f[21]= cf2; f[22]= cf3; f[23]= cf2; f[24]= cf1;
        return f;
    }


    public void createTest(android.content.res.Resources res) {
        mWidth = mInPixelsAllocation.getType().getX();
        mHeight = mInPixelsAllocation.getType().getY();

        float f[] = updateMatrix(1.f);
        //f[0] = 0.012f; f[1] = 0.025f; f[2] = 0.031f; f[3] = 0.025f; f[4] = 0.012f;
        //f[5] = 0.025f; f[6] = 0.057f; f[7] = 0.075f; f[8] = 0.057f; f[9] = 0.025f;
        //f[10]= 0.031f; f[11]= 0.075f; f[12]= 0.095f; f[13]= 0.075f; f[14]= 0.031f;
        //f[15]= 0.025f; f[16]= 0.057f; f[17]= 0.075f; f[18]= 0.057f; f[19]= 0.025f;
        //f[20]= 0.012f; f[21]= 0.025f; f[22]= 0.031f; f[23]= 0.025f; f[24]= 0.012f;

        //f[0] = 1.f; f[1] = 2.f; f[2] = 0.f; f[3] = -2.f; f[4] = -1.f;
        //f[5] = 4.f; f[6] = 8.f; f[7] = 0.f; f[8] = -8.f; f[9] = -4.f;
        //f[10]= 6.f; f[11]=12.f; f[12]= 0.f; f[13]=-12.f; f[14]= -6.f;
        //f[15]= 4.f; f[16]= 8.f; f[17]= 0.f; f[18]= -8.f; f[19]= -4.f;
        //f[20]= 1.f; f[21]= 2.f; f[22]= 0.f; f[23]= -2.f; f[24]= -1.f;

        if (mUseIntrinsic) {
            mIntrinsic = ScriptIntrinsicConvolve5x5.create(mRS, Element.U8_4(mRS));
            mIntrinsic.setCoefficients(f);
            mIntrinsic.setInput(mInPixelsAllocation);
        } else {
            mScript = new ScriptC_convolve5x5(mRS);
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
