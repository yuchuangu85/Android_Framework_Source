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

public class UT_static_globals extends UnitTest {
    private Resources mRes;

    protected UT_static_globals(RSTestCore rstc, Resources res, Context ctx) {
        super(rstc, "Static Globals", ctx);
        mRes = res;
    }

    public void run() {
        RenderScript pRS = RenderScript.create(mCtx);
        ScriptC_static_globals s = new ScriptC_static_globals(pRS);
        pRS.setMessageHandler(mRsMessage);
        Type.Builder typeBuilder = new Type.Builder(pRS, Element.I32(pRS));
        Allocation A = Allocation.createTyped(pRS, typeBuilder.setX(1).create());
        s.forEach_root(A);
        s.invoke_static_globals_test();
        pRS.finish();
        waitForMessage();
        pRS.destroy();
    }
}
