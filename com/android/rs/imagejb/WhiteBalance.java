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

package com.android.rs.imagejb;

import java.lang.Math;

import android.renderscript.Allocation;
import android.renderscript.Element;
import android.renderscript.ScriptIntrinsicHistogram;

public class WhiteBalance extends TestBase {
    private ScriptC_wbalance mScript;
    private ScriptIntrinsicHistogram mHist;
    private Allocation mSums;

    public void createTest(android.content.res.Resources res) {
        mScript = new ScriptC_wbalance(mRS);
        mHist = ScriptIntrinsicHistogram.create(mRS, Element.U8_4(mRS));
        mSums = Allocation.createSized(mRS, Element.I32_3(mRS), 256);
        mHist.setOutput(mSums);
        mScript.set_histogramValues(mSums);
    }

    public void runTest() {
        mHist.forEach(mInPixelsAllocation);
        mScript.invoke_prepareWhiteBalance();
        mScript.forEach_whiteBalanceKernel(mInPixelsAllocation, mOutPixelsAllocation);
    }

}
