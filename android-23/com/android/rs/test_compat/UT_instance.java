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

package com.android.rs.test_compat;

import android.content.Context;
import android.content.res.Resources;
import android.support.v8.renderscript.*;

public class UT_instance extends UnitTest {
    private Resources mRes;

    protected UT_instance(RSTestCore rstc, Resources res, Context ctx) {
        super(rstc, "Instance", ctx);
        mRes = res;
    }

    void assertEquals(int e, int v) {
        if (e != v) {
            RSTest.log("Assertion failed! Expected: <" + e + "> Got: <" + v + ">");
            failTest();
        }
    }

    public void run() {
        RenderScript mRS = RenderScript.create(mCtx);
        mRS.setMessageHandler(mRsMessage);

        ScriptC_instance instance_1 = new ScriptC_instance(mRS);
        ScriptC_instance instance_2 = new ScriptC_instance(mRS);
        ScriptC_instance instance_3 = new ScriptC_instance(mRS);
        ScriptC_instance instance_4 = new ScriptC_instance(mRS);
        ScriptC_instance instance_5 = new ScriptC_instance(mRS);

        Type t = new Type.Builder(mRS, Element.I32(mRS)).setX(1).create();
        Allocation ai1 = Allocation.createTyped(mRS, t);
        Allocation ai2 = Allocation.createTyped(mRS, t);
        Allocation ai3 = Allocation.createTyped(mRS, t);
        Allocation ai4 = Allocation.createTyped(mRS, t);
        Allocation ai5 = Allocation.createTyped(mRS, t);

        instance_1.set_i(1);
        instance_2.set_i(2);
        instance_3.set_i(3);
        instance_4.set_i(4);
        instance_5.set_i(5);
        instance_1.set_ai(ai1);
        instance_2.set_ai(ai2);
        instance_3.set_ai(ai3);
        instance_4.set_ai(ai4);
        instance_5.set_ai(ai5);

        // We now check to ensure that the global is not being shared across
        // our separate script instances. Our invoke here merely sets the
        // instanced allocation with the instanced global variable's value.
        // If globals are being shared (i.e. not instancing scripts), then
        // both instanced allocations will have the same resulting value
        // (depending on the order in which the invokes complete).
        instance_1.invoke_instance_test();
        instance_2.invoke_instance_test();
        instance_3.invoke_instance_test();
        instance_4.invoke_instance_test();
        instance_5.invoke_instance_test();

        int i1[] = new int[1];
        int i2[] = new int[1];
        int i3[] = new int[1];
        int i4[] = new int[1];
        int i5[] = new int[1];

        ai1.copyTo(i1);
        ai2.copyTo(i2);
        ai3.copyTo(i3);
        ai4.copyTo(i4);
        ai5.copyTo(i5);

        assertEquals(1, i1[0]);
        assertEquals(2, i2[0]);
        assertEquals(3, i3[0]);
        assertEquals(4, i4[0]);
        assertEquals(5, i5[0]);
        assertEquals(1, i1[0]);
        assertEquals(2, i2[0]);
        assertEquals(3, i3[0]);
        assertEquals(4, i4[0]);
        assertEquals(5, i5[0]);
        passTest();  // Set to pass (as long as existing checks didn't fail).
    }
}
