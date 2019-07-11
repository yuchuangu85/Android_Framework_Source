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

package com.android.rs.test;

import android.content.Context;
import android.content.res.Resources;
import android.renderscript.*;

public class UT_check_dims extends UnitTest {
    private Resources mRes;
    byte mFailedArr[];
    int mData[];
    Allocation mA;
    static final int Pattern = 0xA5A5A5A5;

    protected UT_check_dims(RSTestCore rstc, Resources res, Context ctx) {
        super(rstc, "Check Dims", ctx);
        mRes = res;
    }

    private void initializeGlobals(RenderScript RS, ScriptC_check_dims s) {
        Type.Builder typeBuilder = new Type.Builder(RS, Element.U8(RS));
        typeBuilder.setX(1);
        Allocation AFailed = Allocation.createTyped(RS, typeBuilder.create());
        s.set_aFailed(AFailed);

        mFailedArr = new byte[1];
        mFailedArr[0] = 0;
        AFailed.copyFrom(mFailedArr);

        typeBuilder = new Type.Builder(RS, Element.I32(RS));
        int X = 5;
        int Y = 7;
        typeBuilder.setX(X).setY(Y);
        mA = Allocation.createTyped(RS, typeBuilder.create());
        s.set_pattern(Pattern);

        mData = new int[X*Y];
        for (int i = 0; i < X*Y; i++) {
            mData[i] = Pattern;
        }
        mA.copyFrom(mData);

        return;
    }

    public void run() {
        RenderScript pRS = RenderScript.create(mCtx);
        ScriptC_check_dims s = new ScriptC_check_dims(pRS);
        pRS.setMessageHandler(mRsMessage);
        initializeGlobals(pRS, s);
        s.forEach_root(mA);
        s.invoke_check_dims_test();
        pRS.finish();
        waitForMessage();
        pRS.destroy();
    }
}
