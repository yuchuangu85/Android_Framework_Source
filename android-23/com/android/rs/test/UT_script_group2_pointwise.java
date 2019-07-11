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

public class UT_script_group2_pointwise extends UnitTest {
    private Resources mRes;

    private static final int ARRAY_SIZE = 256;

    private static final String TAG = "ScritGroup2 (Pointwise)";

    protected UT_script_group2_pointwise(RSTestCore rstc, Resources res, Context ctx) {
        super(rstc, TAG, ctx);
        mRes = res;
    }

    public void run() {
        RenderScript pRS = RenderScript.create(mCtx);
        ScriptC_increment s_inc = new ScriptC_increment(pRS);
        ScriptC_double s_double = new ScriptC_double(pRS);
        pRS.setMessageHandler(mRsMessage);

        int[] array = new int[ARRAY_SIZE * 4];

        for (int i = 0; i < ARRAY_SIZE * 4; i++) {
            array[i] = i;
        }

        Allocation input = Allocation.createSized(pRS, Element.I32_4(pRS), ARRAY_SIZE);
        input.copyFrom(array);

        ScriptGroup.Builder2 builder = new ScriptGroup.Builder2(pRS);

        ScriptGroup.Input unbound = builder.addInput();

        ScriptGroup.Closure c0 =
                builder.addKernel(s_inc.getKernelID_increment(),
                                  Type.createX(pRS, Element.I32_4(pRS), ARRAY_SIZE),
                                  unbound);

        ScriptGroup.Closure c1 =
                builder.addKernel(s_double.getKernelID_doubleKernel(),
                                  Type.createX(pRS, Element.I32_4(pRS), ARRAY_SIZE),
                                  c0.getReturn());

        ScriptGroup group = builder.create("AddDouble", c1.getReturn());

        int[] a = new int[ARRAY_SIZE * 4];
        ((Allocation)group.execute(input)[0]).copyTo(a);

        pRS.finish();
        pRS.destroy();

        boolean failed = false;
        for (int i = 0; i < ARRAY_SIZE * 4; i++) {
            if (a[i] != (i+1) * 2) {
                Log.e(TAG, "a["+i+"]="+a[i]+", should be "+ ((i+1) * 2));
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
