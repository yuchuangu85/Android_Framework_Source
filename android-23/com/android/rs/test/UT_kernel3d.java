/*
 * Copyright (C) 2015 The Android Open Source Project
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

public class UT_kernel3d extends UnitTest {
    private Resources mRes;
    private Allocation A;
    private Allocation B;

    protected UT_kernel3d(RSTestCore rstc, Resources res, Context ctx) {
        super(rstc, "Kernel 3d (pass-by-value)", ctx);
        mRes = res;
    }

    private void initializeGlobals(RenderScript RS, ScriptC_kernel3d s) {
        Type.Builder typeBuilder = new Type.Builder(RS, Element.I32(RS));
        int X = 2;
        s.set_gDimX(X);
        typeBuilder.setX(X);
        int Y = 5;
        s.set_gDimY(Y);
        typeBuilder.setY(Y);
        int Z = 11;
        s.set_gDimZ(Z);
        typeBuilder.setZ(Z);

        A = Allocation.createTyped(RS, typeBuilder.create());
        s.set_A(A);
        B = Allocation.createTyped(RS, typeBuilder.create());
        s.set_B(B);
        return;
    }

    public void run() {
        RenderScript pRS = RenderScript.create(mCtx);
        ScriptC_kernel3d s = new ScriptC_kernel3d(pRS);
        pRS.setMessageHandler(mRsMessage);
        initializeGlobals(pRS, s);
        s.forEach_init_vars(A);
        s.forEach_root(A, B);
        s.invoke_verify_root();
        s.invoke_kernel_test();
        pRS.finish();
        waitForMessage();
        pRS.destroy();
    }
}
