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

package com.android.rs.image2;

import android.support.v8.renderscript.*;

public class Posterize extends TestBase {
    private ScriptC_posterize mScript;
    boolean mUseInvokes;

    Posterize(boolean useInvoke) {
        mUseInvokes = useInvoke;
    }

    public void createTest(android.content.res.Resources res) {
        mScript = new ScriptC_posterize(mRS);
    }

    void setParams(float intensHigh, float intensLow, int r, int g, int b) {
        if (mUseInvokes) {
            mScript.invoke_setParams(intensHigh, intensLow,
                                     (short)r, (short)g, (short)b);
        } else {
            mScript.set_intensityLow(intensLow);
            mScript.set_intensityHigh(intensHigh);
            mScript.set_color(new Short4((short)r, (short)g, (short)b, (short)255));
        }
    }

    public void runTest() {
        mScript.set_inputImage(mInPixelsAllocation);
        setParams(.2f, 0.f, 255, 0, 0);
        mScript.forEach_root(mInPixelsAllocation, mOutPixelsAllocation);
        setParams(.4f, 0.2f, 0, 255, 0);
        mScript.forEach_root(mOutPixelsAllocation, mOutPixelsAllocation);
        setParams(.6f, 0.4f, 0, 0, 255);
        mScript.forEach_root(mOutPixelsAllocation, mOutPixelsAllocation);
        setParams(.8f, 0.6f, 255, 255, 0);
        mScript.forEach_root(mOutPixelsAllocation, mOutPixelsAllocation);
        setParams(1.0f, 0.8f, 0, 255, 255);
        mScript.forEach_root(mOutPixelsAllocation, mOutPixelsAllocation);
    }

}
