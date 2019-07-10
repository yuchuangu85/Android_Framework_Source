/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package com.android.rs.test;

import android.content.Context;
import android.content.res.Resources;
import android.renderscript.*;
import android.util.Log;
import java.lang.Thread;
import java.util.HashMap;

public class UT_script_group2_gatherscatter extends UnitTest {
    private Resources mRes;

    private static final int ARRAY_SIZE = 256;

    private static final String TAG = "ScriptGroup2 (GatherScatter)";

    int[] mArray;

    protected UT_script_group2_gatherscatter(RSTestCore rstc, Resources res, Context ctx) {
        super(rstc, TAG, ctx);
        mRes = res;
    }

    public void initializeGlobals(RenderScript RS, ScriptC_addup s) {
        mArray = new int[ARRAY_SIZE * 4];

        for (int i = 0; i < ARRAY_SIZE; i++) {
            mArray[i*4] = i * 7;
            mArray[i*4 + 1] = i * 7 + 1;
            mArray[i*4 + 2] = i * 7 + 2;
            mArray[i*4 + 3] = i * 7 + 3;
        }
    }

    // This test tests ScriptGroup2 API for handling gather scatter operations
    // on global allocations that are passed across kernels in a script group.
    // The test sums up all elements in the input int4 array of size ARRAY_SIZE.
    // To do so, it adds up the second half of the array to its first half using
    // kernel function add() in addsup.rs, and then repeatedly applies the same
    // kernel function to the shrinking result arrays until the result is a
    // single int4 value.
    // These steps are created as a script group by repeatedly adding the
    // same kernel function, with the input of one kernel being the output of
    // the previous added kernel function.
    // Since the kernel function relies on rsGetElementAt to access the counterpart
    // of the current element in the second half of the array, the compiler cannot
    // fuse it with the other kernel that it dependes on.
    // This test verifies an ScriptGroup2 implementation correctly handles such
    // a case.
    public void run() {
        RenderScript pRS = RenderScript.create(mCtx);
        ScriptC_addup s = new ScriptC_addup(pRS);
        pRS.setMessageHandler(mRsMessage);
        initializeGlobals(pRS, s);

        Allocation input = Allocation.createSized(pRS, Element.I32_4(pRS), ARRAY_SIZE);
        input.copyFrom(mArray);

        ScriptGroup.Builder2 builder = new ScriptGroup.Builder2(pRS);

        ScriptGroup.Input unbound = builder.addInput();

        ScriptGroup.Closure c = null;
        ScriptGroup.Future f = null;
        int stride;
        for (stride = ARRAY_SIZE / 2; stride >= 1; stride >>= 1) {
            ScriptGroup.Binding binding;
            if (f == null) {
                binding = new ScriptGroup.Binding(s.getFieldID_a_in(), unbound);
            } else {
                binding = new ScriptGroup.Binding(s.getFieldID_a_in(), f);
            }
            c = builder.addKernel(s.getKernelID_add(),
                                  Type.createX(pRS, Element.I32_4(pRS), stride),
                                  new ScriptGroup.Binding(s.getFieldID_reduction_stride(), stride),
                                  binding);
            f = c.getReturn();
        }

        ScriptGroup group = builder.create("Summation", c.getReturn());

        if (c == null) {
            return;
        }

        int[] a = new int[4];
        ((Allocation)group.execute(input)[0]).copyTo(a);

        pRS.finish();
        pRS.destroy();

        boolean failed = false;
        for (int i = 0; i < 4; i++) {
            if (failed == false &&
                a[i] != ARRAY_SIZE * (ARRAY_SIZE - 1) * 7 / 2 + i * ARRAY_SIZE) {
                Log.e(TAG, "a["+i+"]="+a[i]+", should be "+
                      (ARRAY_SIZE * (ARRAY_SIZE - 1) * 7 / 2 + i * ARRAY_SIZE));
                failed = true;
            }
        }
        if (failed) {
            failTest();
            return;
        }
        passTest();
    }
}
