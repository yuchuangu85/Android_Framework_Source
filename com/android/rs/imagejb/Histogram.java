/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.rs.imagejb;

import java.lang.Math;

import android.renderscript.*;
import android.util.Log;

public class Histogram extends TestBase {
    private ScriptC_histogram mScript;
    private ScriptIntrinsicHistogram mHist;
    private Allocation mSum;
    private Allocation mSums;
    private boolean mUseIntrinsic;

    public Histogram(boolean useIntrisic) {
        mUseIntrinsic = useIntrisic;
    }


    public void createTest(android.content.res.Resources res) {
        mScript = new ScriptC_histogram(mRS);
        mHist = ScriptIntrinsicHistogram.create(mRS, Element.U8_4(mRS));

        int w = mInPixelsAllocation.getType().getX();
        int h = mInPixelsAllocation.getType().getY();
        int step = 8;
        int steps = (h + step - 1) / step;

        mScript.set_gWidth(w);
        mScript.set_gHeight(h);
        mScript.set_gStep(step);
        mScript.set_gSteps(steps);

        Type.Builder tb = new Type.Builder(mRS, Element.I32(mRS));
        tb.setX(256).setY(steps);
        Type t = tb.create();
        mSums = Allocation.createTyped(mRS, t);
        mSum = Allocation.createSized(mRS, Element.I32(mRS), 256);

        mScript.set_gSums(mSums);
        mScript.set_gSum(mSum);
        mScript.set_gSrc(mInPixelsAllocation);
        mScript.set_gDest(mOutPixelsAllocation);

        mScript.forEach_clear(mOutPixelsAllocation);
    }



    public void runTest() {
        Script.LaunchOptions lo = new Script.LaunchOptions();

        if (mUseIntrinsic) {
            mHist.setOutput(mSum);
            mHist.forEach_Dot(mInPixelsAllocation);
        } else {
            lo.setX(0, mSums.getType().getY());
            mScript.forEach_pass1(lo);
            mScript.forEach_pass2(mSum);
        }

        mScript.invoke_rescale();
        lo.setX(0, 1024);
        mScript.forEach_draw(mOutPixelsAllocation, lo);
    }

}
