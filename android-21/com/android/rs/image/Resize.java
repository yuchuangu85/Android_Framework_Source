/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.rs.image;

import java.lang.Math;

import android.renderscript.Allocation;
import android.renderscript.Element;
import android.renderscript.Matrix4f;
import android.renderscript.RenderScript;
import android.renderscript.Script;
import android.renderscript.ScriptC;
import android.renderscript.ScriptIntrinsicResize;
import android.renderscript.Type;
import android.util.Log;

public class Resize extends TestBase {
    private ScriptC_resize mScript;
    private ScriptIntrinsicResize mIntrinsic;

    private Allocation mScratchAllocation;
    private int mWidth;
    private int mHeight;
    private boolean mUseIntrinsic;

    public Resize(boolean useIntrinsic) {
        mUseIntrinsic = useIntrinsic;
    }

    public void createTest(android.content.res.Resources res) {
        mWidth = mInPixelsAllocation.getType().getX();
        mHeight = mInPixelsAllocation.getType().getY();
        float scale = 1.f / 32.f;

        Type t = Type.createXY(mRS, mInPixelsAllocation.getElement(),
                               (int)(mWidth * scale), (int)(mHeight * scale));
        mScratchAllocation = Allocation.createTyped(mRS, t);

        // make small buffer
        mScript = new ScriptC_resize(mRS);
        mScript.set_gIn(mInPixelsAllocation);
        mScript.set_gWidthIn(mWidth);
        mScript.set_gHeightIn(mHeight);
        mScript.set_scale(1.f / scale);
        mScript.forEach_nearest(mScratchAllocation);

        // setup normal ops
        mScript.set_gIn(mScratchAllocation);
        mScript.set_gWidthIn(t.getX());
        mScript.set_gHeightIn(t.getY());
        mScript.set_scale(scale);
        //mScript.forEach_nearest(mScratchAllocation);

        mIntrinsic = ScriptIntrinsicResize.create(mRS);
        mIntrinsic.setInput(mScratchAllocation);
    }

    public void runTest() {
        if (mUseIntrinsic) {
            mIntrinsic.forEach_bicubic(mOutPixelsAllocation);
        } else {
            mScript.forEach_bicubic(mOutPixelsAllocation);
            //mScript.forEach_nearest(mOutPixelsAllocation);
        }
    }

}
