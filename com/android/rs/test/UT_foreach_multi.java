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

package com.android.rs.test;

import android.content.Context;
import android.content.res.Resources;
import android.renderscript.*;
import android.util.Log;

public class UT_foreach_multi extends UnitTest {
    private Resources mRes;
    private Allocation Ain0;
    private Allocation Ain1;
    private Allocation Ain2;
    private Allocation Ain3;

    private Allocation Out0;
    private Allocation Out1;
    private Allocation Out2;
    private Allocation Out3;

    protected UT_foreach_multi(RSTestCore rstc, Resources res, Context ctx) {
        super(rstc, "Foreach Multi-input", ctx);
        mRes = res;
    }

    private void initializeGlobals(RenderScript RS, ScriptC_foreach_multi s) {
        Type.Builder type32Builder = new Type.Builder(RS, Element.U32(RS));
        Type.Builder type16Builder = new Type.Builder(RS, Element.U16(RS));

        int Xdim = 5;
        s.set_dimX(Xdim);
        type32Builder.setX(Xdim);
        type16Builder.setX(Xdim);

        // 32-bit input allocations

        Ain0 = Allocation.createTyped(RS, type32Builder.create());
        s.set_ain0(Ain0);
        s.forEach_init_uint32_alloc(Ain0);

        Ain1 = Allocation.createTyped(RS, type32Builder.create());
        s.set_ain1(Ain1);
        s.forEach_init_uint32_alloc(Ain1);

        Ain2 = Allocation.createTyped(RS, type32Builder.create());
        s.set_ain2(Ain2);
        s.forEach_init_uint32_alloc(Ain2);

        // 16-bit input allocation

        Ain3 = Allocation.createTyped(RS, type16Builder.create());
        s.set_ain3(Ain3);
        s.forEach_init_uint16_alloc(Ain3);

        // 32-bit output allocations

        Out0 = Allocation.createTyped(RS, type32Builder.create());
        s.set_aout0(Out0);

        Out1 = Allocation.createTyped(RS, type32Builder.create());
        s.set_aout1(Out1);

        Out2 = Allocation.createTyped(RS, type32Builder.create());
        s.set_aout2(Out2);

        // RetStruct output allocations

        ScriptField_RetStruct StructType = new ScriptField_RetStruct(RS, Xdim);
        Out3 = StructType.getAllocation();
        s.set_aout3(Out3);

        return;
    }

    public void run() {
        RenderScript pRS = RenderScript.create(mCtx);
        ScriptC_foreach_multi s = new ScriptC_foreach_multi(pRS);

        pRS.setMessageHandler(mRsMessage);

        initializeGlobals(pRS, s);

        s.forEach_sum2(Ain0, Ain1, Out0);
        s.forEach_sum3(Ain0, Ain1, Ain2, Out1);
        s.forEach_sum_mixed(Ain0, Ain3, Out2);
        s.forEach_sum2_struct(Ain0, Ain1, Out3);

        s.invoke_test_outputs();
        s.invoke_check_test_results();

        pRS.finish();
        waitForMessage();
        pRS.destroy();
    }
}
