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

package com.android.rs.test_compat;

import android.content.Context;
import android.content.res.Resources;
import android.support.v8.renderscript.*;
import java.util.Random;

public class UT_alloc_copyPadded extends UnitTest {
    private Resources mRes;

    protected UT_alloc_copyPadded(RSTestCore rstc, Resources res, Context ctx) {
        super(rstc, "Allocation CopyTo Padded", ctx);
        mRes = res;
    }

    public void run() {
        RenderScript mRS = RenderScript.create(mCtx);

        testAllocation_Byte3_1D(mRS);
        testAllocation_Byte3_2D(mRS);
        testAllocation_Byte3_3D(mRS);

        testAllocation_Short3_1D(mRS);
        testAllocation_Short3_2D(mRS);
        testAllocation_Short3_3D(mRS);

        testAllocation_Int3_1D(mRS);
        testAllocation_Int3_2D(mRS);
        testAllocation_Int3_3D(mRS);

        testAllocation_Float3_1D(mRS);
        testAllocation_Float3_2D(mRS);
        testAllocation_Float3_3D(mRS);

        testAllocation_Double3_1D(mRS);
        testAllocation_Double3_2D(mRS);
        testAllocation_Double3_3D(mRS);

        testAllocation_Long3_1D(mRS);
        testAllocation_Long3_2D(mRS);
        testAllocation_Long3_3D(mRS);


        testAllocation_copy1DRangeTo_Byte3(mRS);
        testAllocation_copy1DRangeTo_Short3(mRS);
        testAllocation_copy1DRangeTo_Int3(mRS);
        testAllocation_copy1DRangeTo_Float3(mRS);
        testAllocation_copy1DRangeTo_Long3(mRS);

        testAllocation_copy2DRangeTo_Byte3(mRS);
        testAllocation_copy2DRangeTo_Short3(mRS);
        testAllocation_copy2DRangeTo_Int3(mRS);
        testAllocation_copy2DRangeTo_Float3(mRS);
        testAllocation_copy2DRangeTo_Long3(mRS);

        testAllocation_copy1DRangeToUnchecked_Byte3(mRS);
        testAllocation_copy1DRangeToUnchecked_Short3(mRS);
        testAllocation_copy1DRangeToUnchecked_Int3(mRS);
        testAllocation_copy1DRangeToUnchecked_Float3(mRS);
        testAllocation_copy1DRangeToUnchecked_Long3(mRS);

        mRS.destroy();
        passTest();
    }

    public void testAllocation_Byte3_1D(RenderScript mRS) {
        Random random = new Random(0x172d8ab9);
        int width = random.nextInt(512);
        int arr_len = width * 3;

        byte[] inArray = new byte[arr_len];
        byte[] outArray = new byte[arr_len];
        random.nextBytes(inArray);

        Type.Builder typeBuilder = new Type.Builder(mRS, Element.I8_3(mRS));
        typeBuilder.setX(width);
        Allocation alloc = Allocation.createTyped(mRS, typeBuilder.create());
        alloc.setAutoPadding(true);
        alloc.copyFrom(inArray);
        alloc.copyTo(outArray);

        boolean result = true;
        for (int i = 0; i < arr_len; i++) {
            if (inArray[i] != outArray[i]) {
                result = false;
                android.util.Log.v("Alloc Padding Test", "" + i + " " + inArray[i] + " " + outArray[i]);
                break;
            }
        }
        if (result) {
            android.util.Log.v("Alloc Padding Test", "Byte1D TEST PASSED");
        } else {
            failTest();
        }
    }

    public void testAllocation_Byte3_2D(RenderScript mRS) {
        Random random = new Random(0x172d8ab9);
        int width = random.nextInt(128);
        int height = random.nextInt(128);
        int arr_len = width * height * 3;

        byte[] inArray = new byte[arr_len];
        byte[] outArray = new byte[arr_len];
        random.nextBytes(inArray);

        Type.Builder typeBuilder = new Type.Builder(mRS, Element.I8_3(mRS));
        typeBuilder.setX(width).setY(height);
        Allocation alloc = Allocation.createTyped(mRS, typeBuilder.create());
        alloc.setAutoPadding(true);
        alloc.copyFrom(inArray);
        alloc.copyTo(outArray);

        boolean result = true;
        for (int i = 0; i < arr_len; i++) {
            if (inArray[i] != outArray[i]) {
                result = false;
                android.util.Log.v("Alloc Padding Test", "" + i + " " + inArray[i] + " " + outArray[i]);
                break;
            }
        }
        if (result) {
            android.util.Log.v("Alloc Padding Test", "Byte2D TEST PASSED");
        } else {
            failTest();
        }
    }

    public void testAllocation_Byte3_3D(RenderScript mRS) {
        Random random = new Random(0x172d8ab9);
        int w = random.nextInt(32);
        int h = random.nextInt(32);
        int d = random.nextInt(32);
        int arr_len = w * d * h * 3;

        byte[] inArray = new byte[arr_len];
        byte[] outArray = new byte[arr_len];
        random.nextBytes(inArray);

        Type.Builder typeBuilder = new Type.Builder(mRS, Element.I8_3(mRS));
        typeBuilder.setX(w).setY(h).setZ(d);
        Allocation alloc = Allocation.createTyped(mRS, typeBuilder.create());
        alloc.setAutoPadding(true);
        alloc.copyFrom(inArray);
        alloc.copyTo(outArray);

        boolean result = true;
        for (int i = 0; i < arr_len; i++) {
            if (inArray[i] != outArray[i]) {
                result = false;
                android.util.Log.v("Alloc Padding Test", "" + i + " " + inArray[i] + " " + outArray[i]);
                break;
            }
        }
        if (result) {
            android.util.Log.v("Alloc Padding Test", "Byte3D TEST PASSED");
        } else {
            failTest();
        }
    }

    public void testAllocation_Short3_1D(RenderScript mRS) {
        Random random = new Random(0x172d8ab9);
        int width = random.nextInt(512);
        int arr_len = width * 3;

        short[] inArray = new short[arr_len];
        short[] outArray = new short[arr_len];

        for (int i = 0; i < arr_len; i++) {
            inArray[i] = (short)random.nextInt();
        }

        Type.Builder typeBuilder = new Type.Builder(mRS, Element.I16_3(mRS));
        typeBuilder.setX(width);
        Allocation alloc = Allocation.createTyped(mRS, typeBuilder.create());
        alloc.setAutoPadding(true);
        alloc.copyFrom(inArray);
        alloc.copyTo(outArray);

        boolean result = true;
        for (int i = 0; i < arr_len; i++) {
            if (inArray[i] != outArray[i]) {
                result = false;
                android.util.Log.v("Alloc Padding Test", "" + i + " " + inArray[i] + " " + outArray[i]);
                break;
            }
        }
        if (result) {
            android.util.Log.v("Alloc Padding Test", "Short1D TEST PASSED");
        } else {
            failTest();
        }
    }

    public void testAllocation_Short3_2D(RenderScript mRS) {
        Random random = new Random(0x172d8ab9);
        int width = random.nextInt(128);
        int height = random.nextInt(128);
        int arr_len = width * height * 3;

        short[] inArray = new short[arr_len];
        short[] outArray = new short[arr_len];

        for (int i = 0; i < arr_len; i++) {
            inArray[i] = (short)random.nextInt();
        }

        Type.Builder typeBuilder = new Type.Builder(mRS, Element.I16_3(mRS));
        typeBuilder.setX(width).setY(height);
        Allocation alloc = Allocation.createTyped(mRS, typeBuilder.create());
        alloc.setAutoPadding(true);
        alloc.copyFrom(inArray);
        alloc.copyTo(outArray);

        boolean result = true;
        for (int i = 0; i < arr_len; i++) {
            if (inArray[i] != outArray[i]) {
                result = false;
                android.util.Log.v("Alloc Padding Test", "" + i + " " + inArray[i] + " " + outArray[i]);
                break;
            }
        }
        if (result) {
            android.util.Log.v("Alloc Padding Test", "Short2D TEST PASSED");
        } else {
            failTest();
        }
    }

    public void testAllocation_Short3_3D(RenderScript mRS) {
        Random random = new Random(0x172d8ab9);
        int w = random.nextInt(32);
        int h = random.nextInt(32);
        int d = random.nextInt(32);
        int arr_len = w * d * h * 3;

        short[] inArray = new short[arr_len];
        short[] outArray = new short[arr_len];

        for (int i = 0; i < arr_len; i++) {
            inArray[i] = (short)random.nextInt();
        }

        Type.Builder typeBuilder = new Type.Builder(mRS, Element.I16_3(mRS));
        typeBuilder.setX(w).setY(h).setZ(d);
        Allocation alloc = Allocation.createTyped(mRS, typeBuilder.create());
        alloc.setAutoPadding(true);
        alloc.copyFrom(inArray);
        alloc.copyTo(outArray);

        boolean result = true;
        for (int i = 0; i < arr_len; i++) {
            if (inArray[i] != outArray[i]) {
                result = false;
                android.util.Log.v("Alloc Padding Test", "" + i + " " + inArray[i] + " " + outArray[i]);
                break;
            }
        }
        if (result) {
            android.util.Log.v("Alloc Padding Test", "Short3D TEST PASSED");
        } else {
            failTest();
        }
    }

    public void testAllocation_Int3_1D(RenderScript mRS) {
        Random random = new Random(0x172d8ab9);
        int width = random.nextInt(512);
        int arr_len = width * 3;

        int[] inArray = new int[arr_len];
        int[] outArray = new int[arr_len];

        for (int i = 0; i < arr_len; i++) {
            inArray[i] = random.nextInt();
        }

        Type.Builder typeBuilder = new Type.Builder(mRS, Element.I32_3(mRS));
        typeBuilder.setX(width);
        Allocation alloc = Allocation.createTyped(mRS, typeBuilder.create());
        alloc.setAutoPadding(true);
        alloc.copyFrom(inArray);
        alloc.copyTo(outArray);

        boolean result = true;
        for (int i = 0; i < arr_len; i++) {
            if (inArray[i] != outArray[i]) {
                result = false;
                android.util.Log.v("Alloc Padding Test", "" + i + " " + inArray[i] + " " + outArray[i]);
                break;
            }
        }
        if (result) {
            android.util.Log.v("Alloc Padding Test", "Int1D TEST PASSED");
        } else {
            failTest();
        }
    }

    public void testAllocation_Int3_2D(RenderScript mRS) {
        Random random = new Random(0x172d8ab9);
        int width = random.nextInt(128);
        int height = random.nextInt(128);
        int arr_len = width * height * 3;

        int[] inArray = new int[arr_len];
        int[] outArray = new int[arr_len];

        for (int i = 0; i < arr_len; i++) {
            inArray[i] = random.nextInt();
        }

        Type.Builder typeBuilder = new Type.Builder(mRS, Element.I32_3(mRS));
        typeBuilder.setX(width).setY(height);
        Allocation alloc = Allocation.createTyped(mRS, typeBuilder.create());
        alloc.setAutoPadding(true);
        alloc.copyFrom(inArray);
        alloc.copyTo(outArray);

        boolean result = true;
        for (int i = 0; i < arr_len; i++) {
            if (inArray[i] != outArray[i]) {
                result = false;
                android.util.Log.v("Alloc Padding Test", "" + i + " " + inArray[i] + " " + outArray[i]);
                break;
            }
        }
        if (result) {
            android.util.Log.v("Alloc Padding Test", "Int2D TEST PASSED");
        } else {
            failTest();
        }
    }

    public void testAllocation_Int3_3D(RenderScript mRS) {
        Random random = new Random(0x172d8ab9);
        int w = random.nextInt(32);
        int h = random.nextInt(32);
        int d = random.nextInt(32);
        int arr_len = w * d * h * 3;

        int[] inArray = new int[arr_len];
        int[] outArray = new int[arr_len];

        for (int i = 0; i < arr_len; i++) {
            inArray[i] = random.nextInt();
        }

        Type.Builder typeBuilder = new Type.Builder(mRS, Element.I32_3(mRS));
        typeBuilder.setX(w).setY(h).setZ(d);
        Allocation alloc = Allocation.createTyped(mRS, typeBuilder.create());
        alloc.setAutoPadding(true);
        alloc.copyFrom(inArray);
        alloc.copyTo(outArray);

        boolean result = true;
        for (int i = 0; i < arr_len; i++) {
            if (inArray[i] != outArray[i]) {
                result = false;
                android.util.Log.v("Alloc Padding Test", "" + i + " " + inArray[i] + " " + outArray[i]);
                break;
            }
        }
        if (result) {
            android.util.Log.v("Alloc Padding Test", "Int3D TEST PASSED");
        } else {
            failTest();
        }
    }

    public void testAllocation_Float3_1D(RenderScript mRS) {
        Random random = new Random(0x172d8ab9);
        int width = random.nextInt(512);
        int arr_len = width * 3;

        float[] inArray = new float[arr_len];
        float[] outArray = new float[arr_len];

        for (int i = 0; i < arr_len; i++) {
            inArray[i] = random.nextFloat();
        }

        Type.Builder typeBuilder = new Type.Builder(mRS, Element.F32_3(mRS));
        typeBuilder.setX(width);
        Allocation alloc = Allocation.createTyped(mRS, typeBuilder.create());
        alloc.setAutoPadding(true);
        alloc.copyFrom(inArray);
        alloc.copyTo(outArray);

        boolean result = true;
        for (int i = 0; i < arr_len; i++) {
            if (inArray[i] != outArray[i]) {
                result = false;
                android.util.Log.v("Alloc Padding Test", "" + i + " " + inArray[i] + " " + outArray[i]);
                break;
            }
        }
        if (result) {
            android.util.Log.v("Alloc Padding Test", "Float1D TEST PASSED");
        } else {
            failTest();
        }
    }
    public void testAllocation_Float3_2D(RenderScript mRS) {
        Random random = new Random(0x172d8ab9);
        int width = random.nextInt(128);
        int height = random.nextInt(128);
        int arr_len = width * height * 3;

        float[] inArray = new float[arr_len];
        float[] outArray = new float[arr_len];

        for (int i = 0; i < arr_len; i++) {
            inArray[i] = random.nextFloat();
        }

        Type.Builder typeBuilder = new Type.Builder(mRS, Element.F32_3(mRS));
        typeBuilder.setX(width).setY(height);
        Allocation alloc = Allocation.createTyped(mRS, typeBuilder.create());
        alloc.setAutoPadding(true);
        alloc.copyFrom(inArray);
        alloc.copyTo(outArray);

        boolean result = true;
        for (int i = 0; i < arr_len; i++) {
            if (inArray[i] != outArray[i]) {
                result = false;
                android.util.Log.v("Alloc Padding Test", "" + i + " " + inArray[i] + " " + outArray[i]);
                break;
            }
        }
        if (result) {
            android.util.Log.v("Alloc Padding Test", "Float2D TEST PASSED");
        } else {
            failTest();
        }
    }
    public void testAllocation_Float3_3D(RenderScript mRS) {
        Random random = new Random(0x172d8ab9);
        int w = random.nextInt(32);
        int h = random.nextInt(32);
        int d = random.nextInt(32);
        int arr_len = w * d * h * 3;

        float[] inArray = new float[arr_len];
        float[] outArray = new float[arr_len];

        for (int i = 0; i < arr_len; i++) {
            inArray[i] = random.nextFloat();
        }

        Type.Builder typeBuilder = new Type.Builder(mRS, Element.F32_3(mRS));
        typeBuilder.setX(w).setY(h).setZ(d);
        Allocation alloc = Allocation.createTyped(mRS, typeBuilder.create());
        alloc.setAutoPadding(true);
        alloc.copyFrom(inArray);
        alloc.copyTo(outArray);

        boolean result = true;
        for (int i = 0; i < arr_len; i++) {
            if (inArray[i] != outArray[i]) {
                result = false;
                android.util.Log.v("Alloc Padding Test", "" + i + " " + inArray[i] + " " + outArray[i]);
                break;
            }
        }
        if (result) {
            android.util.Log.v("Alloc Padding Test", "Float3D TEST PASSED");
        } else {
            failTest();
        }
    }

    public void testAllocation_Double3_1D(RenderScript mRS) {
        Random random = new Random(0x172d8ab9);
        int width = random.nextInt(512);
        int arr_len = width * 3;

        double[] inArray = new double[arr_len];
        double[] outArray = new double[arr_len];

        for (int i = 0; i < arr_len; i++) {
            inArray[i] = (double)random.nextFloat();
        }

        Type.Builder typeBuilder = new Type.Builder(mRS, Element.F64_3(mRS));
        typeBuilder.setX(width);
        Allocation alloc = Allocation.createTyped(mRS, typeBuilder.create());
        alloc.setAutoPadding(true);
        alloc.copyFrom(inArray);
        alloc.copyTo(outArray);

        boolean result = true;
        for (int i = 0; i < arr_len; i++) {
            if (inArray[i] != outArray[i]) {
                result = false;
                android.util.Log.v("Alloc Padding Test", "" + i + " " + inArray[i] + " " + outArray[i]);
                break;
            }
        }
        if (result) {
            android.util.Log.v("Alloc Padding Test", "Double1D TEST PASSED");
        } else {
            failTest();
        }
    }
    public void testAllocation_Double3_2D(RenderScript mRS) {
        Random random = new Random(0x172d8ab9);
        int width = random.nextInt(128);
        int height = random.nextInt(128);
        int arr_len = width * height * 3;

        double[] inArray = new double[arr_len];
        double[] outArray = new double[arr_len];

        for (int i = 0; i < arr_len; i++) {
            inArray[i] = (double)random.nextFloat();
        }

        Type.Builder typeBuilder = new Type.Builder(mRS, Element.F64_3(mRS));
        typeBuilder.setX(width).setY(height);
        Allocation alloc = Allocation.createTyped(mRS, typeBuilder.create());
        alloc.setAutoPadding(true);
        alloc.copyFrom(inArray);
        alloc.copyTo(outArray);

        boolean result = true;
        for (int i = 0; i < arr_len; i++) {
            if (inArray[i] != outArray[i]) {
                result = false;
                android.util.Log.v("Alloc Padding Test", "" + i + " " + inArray[i] + " " + outArray[i]);
                break;
            }
        }
        if (result) {
            android.util.Log.v("Alloc Padding Test", "Double2D TEST PASSED");
        } else {
            failTest();
        }
    }
    public void testAllocation_Double3_3D(RenderScript mRS) {
        Random random = new Random(0x172d8ab9);
        int w = random.nextInt(32);
        int h = random.nextInt(32);
        int d = random.nextInt(32);
        int arr_len = w * d * h * 3;

        double[] inArray = new double[arr_len];
        double[] outArray = new double[arr_len];

        for (int i = 0; i < arr_len; i++) {
            inArray[i] = (double)random.nextFloat();
        }

        Type.Builder typeBuilder = new Type.Builder(mRS, Element.F64_3(mRS));
        typeBuilder.setX(w).setY(h).setZ(d);
        Allocation alloc = Allocation.createTyped(mRS, typeBuilder.create());
        alloc.setAutoPadding(true);
        alloc.copyFrom(inArray);
        alloc.copyTo(outArray);

        boolean result = true;
        for (int i = 0; i < arr_len; i++) {
            if (inArray[i] != outArray[i]) {
                result = false;
                android.util.Log.v("Alloc Padding Test", "" + i + " " + inArray[i] + " " + outArray[i]);
                break;
            }
        }
        if (result) {
            android.util.Log.v("Alloc Padding Test", "Double3D TEST PASSED");
        } else {
            failTest();
        }
    }

    public void testAllocation_Long3_1D(RenderScript mRS) {
        Random random = new Random(0x172d8ab9);
        int width = random.nextInt(512);
        int arr_len = width * 3;

        long[] inArray = new long[arr_len];
        long[] outArray = new long[arr_len];

        for (int i = 0; i < arr_len; i++) {
            inArray[i] = random.nextLong();
        }

        Type.Builder typeBuilder = new Type.Builder(mRS, Element.I64_3(mRS));
        typeBuilder.setX(width);
        Allocation alloc = Allocation.createTyped(mRS, typeBuilder.create());
        alloc.setAutoPadding(true);
        alloc.copyFrom(inArray);
        alloc.copyTo(outArray);

        boolean result = true;
        for (int i = 0; i < arr_len; i++) {
            if (inArray[i] != outArray[i]) {
                result = false;
                android.util.Log.v("Alloc Padding Test", "" + i + " " + inArray[i] + " " + outArray[i]);
                break;
            }
        }
        if (result) {
            android.util.Log.v("Alloc Padding Test", "Long1D TEST PASSED");
        } else {
            failTest();
        }
    }

    public void testAllocation_Long3_2D(RenderScript mRS) {
        Random random = new Random(0x172d8ab9);
        int width = random.nextInt(128);
        int height = random.nextInt(128);
        int arr_len = width * height * 3;

        long[] inArray = new long[arr_len];
        long[] outArray = new long[arr_len];

        for (int i = 0; i < arr_len; i++) {
            inArray[i] = random.nextLong();
        }

        Type.Builder typeBuilder = new Type.Builder(mRS, Element.I64_3(mRS));
        typeBuilder.setX(width).setY(height);
        Allocation alloc = Allocation.createTyped(mRS, typeBuilder.create());
        alloc.setAutoPadding(true);
        alloc.copyFrom(inArray);
        alloc.copyTo(outArray);

        boolean result = true;
        for (int i = 0; i < arr_len; i++) {
            if (inArray[i] != outArray[i]) {
                result = false;
                android.util.Log.v("Alloc Padding Test", "" + i + " " + inArray[i] + " " + outArray[i]);
                break;
            }
        }
        if (result) {
            android.util.Log.v("Alloc Padding Test", "Long2D TEST PASSED");
        } else {
            failTest();
        }
    }

    public void testAllocation_Long3_3D(RenderScript mRS) {
        Random random = new Random(0x172d8ab9);
        int w = random.nextInt(32);
        int h = random.nextInt(32);
        int d = random.nextInt(32);
        int arr_len = w * d * h * 3;

        long[] inArray = new long[arr_len];
        long[] outArray = new long[arr_len];

        for (int i = 0; i < arr_len; i++) {
            inArray[i] = random.nextLong();
        }

        Type.Builder typeBuilder = new Type.Builder(mRS, Element.I64_3(mRS));
        typeBuilder.setX(w).setY(h).setZ(d);
        Allocation alloc = Allocation.createTyped(mRS, typeBuilder.create());
        alloc.setAutoPadding(true);
        alloc.copyFrom(inArray);
        alloc.copyTo(outArray);

        boolean result = true;
        for (int i = 0; i < arr_len; i++) {
            if (inArray[i] != outArray[i]) {
                result = false;
                android.util.Log.v("Alloc Padding Test", "" + i + " " + inArray[i] + " " + outArray[i]);
                break;
            }
        }
        if (result) {
            android.util.Log.v("Alloc Padding Test", "Long3D TEST PASSED");
        } else {
            failTest();
        }
    }


    public void testAllocation_copy1DRangeTo_Byte3(RenderScript mRS) {
        Random random = new Random(0x172d8ab9);
        int width = random.nextInt(512);
        int arr_len = width * 3;

        byte[] inArray = new byte[arr_len];
        byte[] outArray = new byte[arr_len];
        random.nextBytes(inArray);

        Type.Builder typeBuilder = new Type.Builder(mRS, Element.I8_3(mRS));
        typeBuilder.setX(width);
        Allocation alloc = Allocation.createTyped(mRS, typeBuilder.create());
        alloc.setAutoPadding(true);
        int offset = random.nextInt(width);
        int count = width - offset;
        alloc.copy1DRangeFrom(offset, count, inArray);
        alloc.copy1DRangeTo(offset, count, outArray);

        boolean result = true;
        for (int i = 0; i < count * 3; i++) {
            if (inArray[i] != outArray[i]) {
                result = false;
                android.util.Log.v("Alloc Padding Test", "Failed: " + i + " " + inArray[i] + " " + outArray[i]);
                break;
            }
        }
        for (int i = count * 3; i < arr_len; i++) {
            if (outArray[i] != 0) {
                result = false;
                android.util.Log.v("Alloc Padding Test", "Failed: " + i + " " + inArray[i] + " " + outArray[i]);
                break;
            }
        }
        if (result) {
            android.util.Log.v("Alloc Padding Test", "copy1DRangeTo_Byte TEST PASSED");
        } else {
            failTest();
        }
    }

    public void testAllocation_copy1DRangeTo_Short3(RenderScript mRS) {
        Random random = new Random(0x172d8ab9);
        int width = random.nextInt(512);
        int arr_len = width * 3;

        short[] inArray = new short[arr_len];
        short[] outArray = new short[arr_len];

        for (int i = 0; i < arr_len; i++) {
            inArray[i] = (short)random.nextInt();
        }

        Type.Builder typeBuilder = new Type.Builder(mRS, Element.I16_3(mRS));
        typeBuilder.setX(width);
        Allocation alloc = Allocation.createTyped(mRS, typeBuilder.create());
        alloc.setAutoPadding(true);
        int offset = random.nextInt(width);
        int count = width - offset;
        alloc.copy1DRangeFrom(offset, count, inArray);
        alloc.copy1DRangeTo(offset, count, outArray);

        boolean result = true;
        for (int i = 0; i < count * 3; i++) {
            if (inArray[i] != outArray[i]) {
                result = false;
                android.util.Log.v("Alloc Padding Test", "Failed: " + i + " " + inArray[i] + " " + outArray[i]);
                break;
            }
        }
        for (int i = count * 3; i < arr_len; i++) {
            if (outArray[i] != 0) {
                result = false;
                android.util.Log.v("Alloc Padding Test", "Failed: " + i + " " + inArray[i] + " " + outArray[i]);
                break;
            }
        }
        if (result) {
            android.util.Log.v("Alloc Padding Test", "copy1DRangeTo_Short TEST PASSED");
        } else {
            failTest();
        }
    }

    public void testAllocation_copy1DRangeTo_Int3(RenderScript mRS) {
        Random random = new Random(0x172d8ab9);
        int width = random.nextInt(512);
        int arr_len = width * 3;

        int[] inArray = new int[arr_len];
        int[] outArray = new int[arr_len];

        for (int i = 0; i < arr_len; i++) {
            inArray[i] = random.nextInt();
        }

        Type.Builder typeBuilder = new Type.Builder(mRS, Element.I32_3(mRS));
        typeBuilder.setX(width);
        Allocation alloc = Allocation.createTyped(mRS, typeBuilder.create());
        alloc.setAutoPadding(true);
        int offset = random.nextInt(width);
        int count = width - offset;
        alloc.copy1DRangeFrom(offset, count, inArray);
        alloc.copy1DRangeTo(offset, count, outArray);

        boolean result = true;
        for (int i = 0; i < count * 3; i++) {
            if (inArray[i] != outArray[i]) {
                result = false;
                android.util.Log.v("Alloc Padding Test", "Failed: " + i + " " + inArray[i] + " " + outArray[i]);
                break;
            }
        }
        for (int i = count * 3; i < arr_len; i++) {
            if (outArray[i] != 0) {
                result = false;
                android.util.Log.v("Alloc Padding Test", "Failed: " + i + " " + inArray[i] + " " + outArray[i]);
                break;
            }
        }
        if (result) {
            android.util.Log.v("Alloc Padding Test", "copy1DRangeTo_Int TEST PASSED");
        } else {
            failTest();
        }
    }

    public void testAllocation_copy1DRangeTo_Float3(RenderScript mRS) {
        Random random = new Random(0x172d8ab9);
        int width = random.nextInt(512);
        int arr_len = width * 3;

        float[] inArray = new float[arr_len];
        float[] outArray = new float[arr_len];

        for (int i = 0; i < arr_len; i++) {
            inArray[i] = random.nextFloat();
        }

        Type.Builder typeBuilder = new Type.Builder(mRS, Element.F32_3(mRS));
        typeBuilder.setX(width);
        Allocation alloc = Allocation.createTyped(mRS, typeBuilder.create());
        alloc.setAutoPadding(true);
        int offset = random.nextInt(width);
        int count = width - offset;
        alloc.copy1DRangeFrom(offset, count, inArray);
        alloc.copy1DRangeTo(offset, count, outArray);

        boolean result = true;
        for (int i = 0; i < count * 3; i++) {
            if (inArray[i] != outArray[i]) {
                result = false;
                android.util.Log.v("Alloc Padding Test", "Failed: " + i + " " + inArray[i] + " " + outArray[i]);
                break;
            }
        }
        for (int i = count * 3; i < arr_len; i++) {
            if (outArray[i] != 0f) {
                result = false;
                android.util.Log.v("Alloc Padding Test", "Failed: " + i + " " + inArray[i] + " " + outArray[i]);
                break;
            }
        }
        if (result) {
            android.util.Log.v("Alloc Padding Test", "copy1DRangeTo_Float TEST PASSED");
        } else {
            failTest();
        }
    }

    public void testAllocation_copy1DRangeTo_Long3(RenderScript mRS) {
        Random random = new Random(0x172d8ab9);
        int width = random.nextInt(512);
        int arr_len = width * 3;

        long[] inArray = new long[arr_len];
        long[] outArray = new long[arr_len];

        for (int i = 0; i < arr_len; i++) {
            inArray[i] = random.nextLong();
        }

        Type.Builder typeBuilder = new Type.Builder(mRS, Element.I64_3(mRS));
        typeBuilder.setX(width);
        Allocation alloc = Allocation.createTyped(mRS, typeBuilder.create());
        alloc.setAutoPadding(true);
        int offset = random.nextInt(width);
        int count = width - offset;
        alloc.copy1DRangeFrom(offset, count, inArray);
        alloc.copy1DRangeTo(offset, count, outArray);

        boolean result = true;
        for (int i = 0; i < count * 3; i++) {
            if (inArray[i] != outArray[i]) {
                result = false;
                android.util.Log.v("Alloc Padding Test", "Failed: " + i + " " + inArray[i] + " " + outArray[i]);
                break;
            }
        }
        for (int i = count * 3; i < arr_len; i++) {
            if (outArray[i] != 0) {
                result = false;
                android.util.Log.v("Alloc Padding Test", "Failed: " + i + " " + inArray[i] + " " + outArray[i]);
                break;
            }
        }
        if (result) {
            android.util.Log.v("Alloc Padding Test", "copy1DRangeTo_Long TEST PASSED");
        } else {
            failTest();
        }
    }

    public void testAllocation_copy2DRangeTo_Byte3(RenderScript mRS) {
        Random random = new Random(0x172d8ab9);
        int width = random.nextInt(128);
        int height = random.nextInt(128);
        int xoff = random.nextInt(width);
        int yoff = random.nextInt(height);
        int xcount = width - xoff;
        int ycount = height - yoff;
        int arr_len = xcount * ycount * 3;

        byte[] inArray = new byte[arr_len];
        byte[] outArray = new byte[arr_len];
        random.nextBytes(inArray);

        Type.Builder typeBuilder = new Type.Builder(mRS, Element.I8_3(mRS));
        typeBuilder.setX(width).setY(height);
        Allocation alloc = Allocation.createTyped(mRS, typeBuilder.create());
        alloc.setAutoPadding(true);
        alloc.copy2DRangeFrom(xoff, yoff, xcount, ycount, inArray);
        alloc.copy2DRangeTo(xoff, yoff, xcount, ycount, outArray);

        boolean result = true;
        for (int i = 0; i < arr_len; i++) {
            if (inArray[i] != outArray[i]) {
                result = false;
                android.util.Log.v("Alloc Padding Test", "Failed: " + i + " " + inArray[i] + " " + outArray[i]);
                break;
            }
        }
        if (result) {
            android.util.Log.v("Alloc Padding Test", "copy2DRangeTo_Byte TEST PASSED");
        } else {
            failTest();
        }
    }

    public void testAllocation_copy2DRangeTo_Short3(RenderScript mRS) {
        Random random = new Random(0x172d8ab9);
        int width = random.nextInt(128);
        int height = random.nextInt(128);
        int xoff = random.nextInt(width);
        int yoff = random.nextInt(height);
        int xcount = width - xoff;
        int ycount = height - yoff;
        int arr_len = xcount * ycount * 3;

        short[] inArray = new short[arr_len];
        short[] outArray = new short[arr_len];

        for (int i = 0; i < arr_len; i++) {
            inArray[i] = (short)random.nextInt();
        }

        Type.Builder typeBuilder = new Type.Builder(mRS, Element.I16_3(mRS));
        typeBuilder.setX(width).setY(height);
        Allocation alloc = Allocation.createTyped(mRS, typeBuilder.create());
        alloc.setAutoPadding(true);
        alloc.copy2DRangeFrom(xoff, yoff, xcount, ycount, inArray);
        alloc.copy2DRangeTo(xoff, yoff, xcount, ycount, outArray);

        boolean result = true;
        for (int i = 0; i < arr_len; i++) {
            if (inArray[i] != outArray[i]) {
                result = false;
                android.util.Log.v("Alloc Padding Test", "Failed: " + i + " " + inArray[i] + " " + outArray[i]);
                break;
            }
        }
        if (result) {
            android.util.Log.v("Alloc Padding Test", "copy2DRangeTo_Short TEST PASSED");
        } else {
            failTest();
        }
    }

    public void testAllocation_copy2DRangeTo_Int3(RenderScript mRS) {
        Random random = new Random(0x172d8ab9);
        int width = random.nextInt(128);
        int height = random.nextInt(128);
        int xoff = random.nextInt(width);
        int yoff = random.nextInt(height);
        int xcount = width - xoff;
        int ycount = height - yoff;
        int arr_len = xcount * ycount * 3;

        int[] inArray = new int[arr_len];
        int[] outArray = new int[arr_len];

        for (int i = 0; i < arr_len; i++) {
            inArray[i] = random.nextInt();
        }

        Type.Builder typeBuilder = new Type.Builder(mRS, Element.I32_3(mRS));
        typeBuilder.setX(width).setY(height);
        Allocation alloc = Allocation.createTyped(mRS, typeBuilder.create());
        alloc.setAutoPadding(true);
        alloc.copy2DRangeFrom(xoff, yoff, xcount, ycount, inArray);
        alloc.copy2DRangeTo(xoff, yoff, xcount, ycount, outArray);

        boolean result = true;
        for (int i = 0; i < arr_len; i++) {
            if (inArray[i] != outArray[i]) {
                result = false;
                android.util.Log.v("Alloc Padding Test", "Failed: " + i + " " + inArray[i] + " " + outArray[i]);
                break;
            }
        }
        if (result) {
            android.util.Log.v("Alloc Padding Test", "copy2DRangeTo_Int TEST PASSED");
        } else {
            failTest();
        }
    }

    public void testAllocation_copy2DRangeTo_Float3(RenderScript mRS) {
        Random random = new Random(0x172d8ab9);
        int width = random.nextInt(128);
        int height = random.nextInt(128);
        int xoff = random.nextInt(width);
        int yoff = random.nextInt(height);
        int xcount = width - xoff;
        int ycount = height - yoff;
        int arr_len = xcount * ycount * 3;

        float[] inArray = new float[arr_len];
        float[] outArray = new float[arr_len];

        for (int i = 0; i < arr_len; i++) {
            inArray[i] = random.nextFloat();
        }

        Type.Builder typeBuilder = new Type.Builder(mRS, Element.F32_3(mRS));
        typeBuilder.setX(width).setY(height);
        Allocation alloc = Allocation.createTyped(mRS, typeBuilder.create());
        alloc.setAutoPadding(true);
        alloc.copy2DRangeFrom(xoff, yoff, xcount, ycount, inArray);
        alloc.copy2DRangeTo(xoff, yoff, xcount, ycount, outArray);

        boolean result = true;
        for (int i = 0; i < arr_len; i++) {
            if (inArray[i] != outArray[i]) {
                result = false;
                android.util.Log.v("Alloc Padding Test", "Failed: " + i + " " + inArray[i] + " " + outArray[i]);
                break;
            }
        }
        if (result) {
            android.util.Log.v("Alloc Padding Test", "copy2DRangeTo_Float TEST PASSED");
        } else {
            failTest();
        }
    }

    public void testAllocation_copy2DRangeTo_Long3(RenderScript mRS) {
        Random random = new Random(0x172d8ab9);
        int width = random.nextInt(128);
        int height = random.nextInt(128);
        int xoff = random.nextInt(width);
        int yoff = random.nextInt(height);
        int xcount = width - xoff;
        int ycount = height - yoff;
        int arr_len = xcount * ycount * 3;

        long[] inArray = new long[arr_len];
        long[] outArray = new long[arr_len];

        for (int i = 0; i < arr_len; i++) {
            inArray[i] = random.nextLong();
        }

        Type.Builder typeBuilder = new Type.Builder(mRS, Element.I64_3(mRS));
        typeBuilder.setX(width).setY(height);
        Allocation alloc = Allocation.createTyped(mRS, typeBuilder.create());
        alloc.setAutoPadding(true);
        alloc.copy2DRangeFrom(xoff, yoff, xcount, ycount, inArray);
        alloc.copy2DRangeTo(xoff, yoff, xcount, ycount, outArray);

        boolean result = true;
        for (int i = 0; i < arr_len; i++) {
            if (inArray[i] != outArray[i]) {
                result = false;
                android.util.Log.v("Alloc Padding Test", "Failed: " + i + " " + inArray[i] + " " + outArray[i]);
                break;
            }
        }
        if (result) {
            android.util.Log.v("Alloc Padding Test", "copy2DRangeTo_Long TEST PASSED");
        } else {
            failTest();
        }
    }


    public void testAllocation_copy1DRangeToUnchecked_Byte3(RenderScript mRS) {
        Random random = new Random(0x172d8ab9);
        int width = random.nextInt(512);
        int arr_len = width * 3;

        byte[] inArray = new byte[arr_len];
        byte[] outArray = new byte[arr_len];
        random.nextBytes(inArray);

        Type.Builder typeBuilder = new Type.Builder(mRS, Element.I8_3(mRS));
        typeBuilder.setX(width);
        Allocation alloc = Allocation.createTyped(mRS, typeBuilder.create());
        alloc.setAutoPadding(true);
        int offset = random.nextInt(width);
        int count = width - offset;
        alloc.copy1DRangeFrom(offset, count, inArray);
        alloc.copy1DRangeToUnchecked(offset, count, outArray);

        boolean result = true;
        for (int i = 0; i < count * 3; i++) {
            if (inArray[i] != outArray[i]) {
                result = false;
                android.util.Log.v("Alloc Padding Test", "Failed: " + i + " " + inArray[i] + " " + outArray[i]);
                break;
            }
        }
        for (int i = count * 3; i < arr_len; i++) {
            if (outArray[i] != 0) {
                result = false;
                android.util.Log.v("Alloc Padding Test", "Failed: " + i + " " + inArray[i] + " " + outArray[i]);
                break;
            }
        }
        if (result) {
            android.util.Log.v("Alloc Padding Test", "copy1DRangeToUnchecked_Byte TEST PASSED");
        } else {
            failTest();
        }
    }

    public void testAllocation_copy1DRangeToUnchecked_Short3(RenderScript mRS) {
        Random random = new Random(0x172d8ab9);
        int width = random.nextInt(512);
        int arr_len = width * 3;

        short[] inArray = new short[arr_len];
        short[] outArray = new short[arr_len];

        for (int i = 0; i < arr_len; i++) {
            inArray[i] = (short)random.nextInt();
        }

        Type.Builder typeBuilder = new Type.Builder(mRS, Element.I16_3(mRS));
        typeBuilder.setX(width);
        Allocation alloc = Allocation.createTyped(mRS, typeBuilder.create());
        alloc.setAutoPadding(true);
        int offset = random.nextInt(width);
        int count = width - offset;
        alloc.copy1DRangeFrom(offset, count, inArray);
        alloc.copy1DRangeToUnchecked(offset, count, outArray);

        boolean result = true;
        for (int i = 0; i < count * 3; i++) {
            if (inArray[i] != outArray[i]) {
                result = false;
                android.util.Log.v("Alloc Padding Test", "Failed: " + i + " " + inArray[i] + " " + outArray[i]);
                break;
            }
        }
        for (int i = count * 3; i < arr_len; i++) {
            if (outArray[i] != 0) {
                result = false;
                android.util.Log.v("Alloc Padding Test", "Failed: " + i + " " + inArray[i] + " " + outArray[i]);
                break;
            }
        }
        if (result) {
            android.util.Log.v("Alloc Padding Test", "copy1DRangeToUnchecked_Short TEST PASSED");
        } else {
            failTest();
        }
    }

    public void testAllocation_copy1DRangeToUnchecked_Int3(RenderScript mRS) {
        Random random = new Random(0x172d8ab9);
        int width = random.nextInt(512);
        int arr_len = width * 3;

        int[] inArray = new int[arr_len];
        int[] outArray = new int[arr_len];

        for (int i = 0; i < arr_len; i++) {
            inArray[i] = random.nextInt();
        }

        Type.Builder typeBuilder = new Type.Builder(mRS, Element.I32_3(mRS));
        typeBuilder.setX(width);
        Allocation alloc = Allocation.createTyped(mRS, typeBuilder.create());
        alloc.setAutoPadding(true);
        int offset = random.nextInt(width);
        int count = width - offset;
        alloc.copy1DRangeFrom(offset, count, inArray);
        alloc.copy1DRangeToUnchecked(offset, count, outArray);

        boolean result = true;
        for (int i = 0; i < count * 3; i++) {
            if (inArray[i] != outArray[i]) {
                result = false;
                android.util.Log.v("Alloc Padding Test", "Failed: " + i + " " + inArray[i] + " " + outArray[i]);
                break;
            }
        }
        for (int i = count * 3; i < arr_len; i++) {
            if (outArray[i] != 0) {
                result = false;
                android.util.Log.v("Alloc Padding Test", "Failed: " + i + " " + inArray[i] + " " + outArray[i]);
                break;
            }
        }
        if (result) {
            android.util.Log.v("Alloc Padding Test", "copy1DRangeToUnchecked_Int TEST PASSED");
        } else {
            failTest();
        }
    }

    public void testAllocation_copy1DRangeToUnchecked_Float3(RenderScript mRS) {
        Random random = new Random(0x172d8ab9);
        int width = random.nextInt(512);
        int arr_len = width * 3;

        float[] inArray = new float[arr_len];
        float[] outArray = new float[arr_len];

        for (int i = 0; i < arr_len; i++) {
            inArray[i] = random.nextFloat();
        }

        Type.Builder typeBuilder = new Type.Builder(mRS, Element.F32_3(mRS));
        typeBuilder.setX(width);
        Allocation alloc = Allocation.createTyped(mRS, typeBuilder.create());
        alloc.setAutoPadding(true);
        int offset = random.nextInt(width);
        int count = width - offset;
        alloc.copy1DRangeFrom(offset, count, inArray);
        alloc.copy1DRangeToUnchecked(offset, count, outArray);

        boolean result = true;
        for (int i = 0; i < count * 3; i++) {
            if (inArray[i] != outArray[i]) {
                result = false;
                android.util.Log.v("Alloc Padding Test", "Failed: " + i + " " + inArray[i] + " " + outArray[i]);
                break;
            }
        }
        for (int i = count * 3; i < arr_len; i++) {
            if (outArray[i] != 0f) {
                result = false;
                android.util.Log.v("Alloc Padding Test", "Failed: " + i + " " + inArray[i] + " " + outArray[i]);
                break;
            }
        }
        if (result) {
            android.util.Log.v("Alloc Padding Test", "copy1DRangeToUnchecked_Float TEST PASSED");
        } else {
            failTest();
        }
    }

    public void testAllocation_copy1DRangeToUnchecked_Long3(RenderScript mRS) {
        Random random = new Random(0x172d8ab9);
        int width = random.nextInt(512);
        int arr_len = width * 3;

        long[] inArray = new long[arr_len];
        long[] outArray = new long[arr_len];

        for (int i = 0; i < arr_len; i++) {
            inArray[i] = random.nextLong();
        }

        Type.Builder typeBuilder = new Type.Builder(mRS, Element.I64_3(mRS));
        typeBuilder.setX(width);
        Allocation alloc = Allocation.createTyped(mRS, typeBuilder.create());
        alloc.setAutoPadding(true);
        int offset = random.nextInt(width);
        int count = width - offset;
        alloc.copy1DRangeFrom(offset, count, inArray);
        alloc.copy1DRangeToUnchecked(offset, count, outArray);

        boolean result = true;
        for (int i = 0; i < count * 3; i++) {
            if (inArray[i] != outArray[i]) {
                result = false;
                android.util.Log.v("Alloc Padding Test", "Failed: " + i + " " + inArray[i] + " " + outArray[i]);
                break;
            }
        }
        for (int i = count * 3; i < arr_len; i++) {
            if (outArray[i] != 0) {
                result = false;
                android.util.Log.v("Alloc Padding Test", "Failed: " + i + " " + inArray[i] + " " + outArray[i]);
                break;
            }
        }
        if (result) {
            android.util.Log.v("Alloc Padding Test", "copy1DRangeToUnchecked_Long TEST PASSED");
        } else {
            failTest();
        }
    }
}
