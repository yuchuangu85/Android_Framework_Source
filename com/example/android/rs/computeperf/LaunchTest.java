/*
 * Copyright (C) 2011-2012 The Android Open Source Project
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

package com.example.android.rs.computeperf;

import android.content.res.Resources;
import android.renderscript.*;

public class LaunchTest {
    private RenderScript mRS;
    private Allocation mAllocationX;
    private Allocation mAllocationXY;
    private ScriptC_launchtest mScript;

    LaunchTest(RenderScript rs, Resources res) {
        mRS = rs;
        mScript = new ScriptC_launchtest(mRS);
        final int dim = mScript.get_dim();

        mAllocationX = Allocation.createSized(rs, Element.U8(rs), dim);
        Type.Builder tb = new Type.Builder(rs, Element.U8(rs));
        tb.setX(dim);
        tb.setY(dim);
        mAllocationXY = Allocation.createTyped(rs, tb.create());
        mScript.set_gBuf(mAllocationXY);
    }

    public long XLW() {
        long t = java.lang.System.currentTimeMillis();
        mScript.forEach_k_x(mAllocationX);
        mRS.finish();
        t = java.lang.System.currentTimeMillis() - t;
        return t;
    }

    public long XYW() {
        long t = java.lang.System.currentTimeMillis();
        mScript.forEach_k_xy(mAllocationXY);
        mRS.finish();
        t = java.lang.System.currentTimeMillis() - t;
        return t;
    }
}
