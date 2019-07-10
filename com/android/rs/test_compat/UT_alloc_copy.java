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

public class UT_alloc_copy extends UnitTest {
    private Resources mRes;

    protected UT_alloc_copy(RSTestCore rstc, Resources res, Context ctx) {
        super(rstc, "Allocation CopyTo", ctx);
        mRes = res;
    }

    public void run() {
        RenderScript mRS = RenderScript.create(mCtx);

        allocation_copy1DRangeTo_Byte(mRS);
        allocation_copy1DRangeTo_Short(mRS);
        allocation_copy1DRangeTo_Int(mRS);
        allocation_copy1DRangeTo_Float(mRS);
        allocation_copy1DRangeTo_Long(mRS);

        allocation_copy2DRangeTo_Byte(mRS);
        allocation_copy2DRangeTo_Short(mRS);
        allocation_copy2DRangeTo_Int(mRS);
        allocation_copy2DRangeTo_Float(mRS);
        allocation_copy2DRangeTo_Long(mRS);

        allocation_copy1DRangeToUnchecked_Byte(mRS);
        allocation_copy1DRangeToUnchecked_Short(mRS);
        allocation_copy1DRangeToUnchecked_Int(mRS);
        allocation_copy1DRangeToUnchecked_Float(mRS);
        allocation_copy1DRangeToUnchecked_Long(mRS);

        mRS.destroy();
        passTest();
    }

    public void allocation_copy1DRangeTo_Byte(RenderScript mRS) {
        Random random = new Random(0x172d8ab9);
        int width = random.nextInt(512);
        int arr_len = width;

        byte[] inArray = new byte[arr_len];
        byte[] outArray = new byte[arr_len];
        random.nextBytes(inArray);

        Type.Builder typeBuilder = new Type.Builder(mRS, Element.I8(mRS));
        typeBuilder.setX(width);
        Allocation alloc = Allocation.createTyped(mRS, typeBuilder.create());
        int offset = random.nextInt(arr_len);
        int count = arr_len - offset;
        alloc.copy1DRangeFrom(offset, count, inArray);
        alloc.copy1DRangeTo(offset, count, outArray);

        boolean result = true;
        for (int i = 0; i < count; i++) {
            if (inArray[i] != outArray[i]) {
                result = false;
                android.util.Log.v("Allocation CopyTo Test", "Failed: " + i + " " + inArray[i] + " " + outArray[i]);
                break;
            }
        }
        for (int i = count; i < arr_len; i++) {
            if (outArray[i] != 0) {
                result = false;
                android.util.Log.v("Allocation CopyTo Test", "Failed: " + i + " " + inArray[i] + " " + outArray[i]);
                break;
            }
        }
        if (result) {
            android.util.Log.v("Allocation CopyTo Test", "copy1DRangeTo_Byte TEST PASSED");
        } else {
            failTest();
        }
    }

    public void allocation_copy1DRangeTo_Short(RenderScript mRS) {
        Random random = new Random(0x172d8ab9);
        int width = random.nextInt(512);
        int arr_len = width;

        short[] inArray = new short[arr_len];
        short[] outArray = new short[arr_len];

        for (int i = 0; i < arr_len; i++) {
            inArray[i] = (short)random.nextInt();
        }

        Type.Builder typeBuilder = new Type.Builder(mRS, Element.I16(mRS));
        typeBuilder.setX(width);
        Allocation alloc = Allocation.createTyped(mRS, typeBuilder.create());
        int offset = random.nextInt(arr_len);
        int count = arr_len - offset;
        alloc.copy1DRangeFrom(offset, count, inArray);
        alloc.copy1DRangeTo(offset, count, outArray);

        boolean result = true;
        for (int i = 0; i < count; i++) {
            if (inArray[i] != outArray[i]) {
                result = false;
                android.util.Log.v("Allocation CopyTo Test", "Failed: " + i + " " + inArray[i] + " " + outArray[i]);
                break;
            }
        }
        for (int i = count; i < arr_len; i++) {
            if (outArray[i] != 0) {
                result = false;
                android.util.Log.v("Allocation CopyTo Test", "Failed: " + i + " " + inArray[i] + " " + outArray[i]);
                break;
            }
        }
        if (result) {
            android.util.Log.v("Allocation CopyTo Test", "copy1DRangeTo_Short TEST PASSED");
        } else {
            failTest();
        }
    }

    public void allocation_copy1DRangeTo_Int(RenderScript mRS) {
        Random random = new Random(0x172d8ab9);
        int width = random.nextInt(512);
        int arr_len = width;

        int[] inArray = new int[arr_len];
        int[] outArray = new int[arr_len];

        for (int i = 0; i < arr_len; i++) {
            inArray[i] = random.nextInt();
        }

        Type.Builder typeBuilder = new Type.Builder(mRS, Element.I32(mRS));
        typeBuilder.setX(width);
        Allocation alloc = Allocation.createTyped(mRS, typeBuilder.create());
        int offset = random.nextInt(arr_len);
        int count = arr_len - offset;
        alloc.copy1DRangeFrom(offset, count, inArray);
        alloc.copy1DRangeTo(offset, count, outArray);

        boolean result = true;
        for (int i = 0; i < count; i++) {
            if (inArray[i] != outArray[i]) {
                result = false;
                android.util.Log.v("Allocation CopyTo Test", "Failed: " + i + " " + inArray[i] + " " + outArray[i]);
                break;
            }
        }
        for (int i = count; i < arr_len; i++) {
            if (outArray[i] != 0) {
                result = false;
                android.util.Log.v("Allocation CopyTo Test", "Failed: " + i + " " + inArray[i] + " " + outArray[i]);
                break;
            }
        }
        if (result) {
            android.util.Log.v("Allocation CopyTo Test", "copy1DRangeTo_Int TEST PASSED");
        } else {
            failTest();
        }
    }

    public void allocation_copy1DRangeTo_Float(RenderScript mRS) {
        Random random = new Random(0x172d8ab9);
        int width = random.nextInt(512);
        int arr_len = width;

        float[] inArray = new float[arr_len];
        float[] outArray = new float[arr_len];

        for (int i = 0; i < arr_len; i++) {
            inArray[i] = random.nextFloat();
        }

        Type.Builder typeBuilder = new Type.Builder(mRS, Element.F32(mRS));
        typeBuilder.setX(width);
        Allocation alloc = Allocation.createTyped(mRS, typeBuilder.create());
        int offset = random.nextInt(arr_len);
        int count = arr_len - offset;
        alloc.copy1DRangeFrom(offset, count, inArray);
        alloc.copy1DRangeTo(offset, count, outArray);

        boolean result = true;
        for (int i = 0; i < count; i++) {
            if (inArray[i] != outArray[i]) {
                result = false;
                android.util.Log.v("Allocation CopyTo Test", "Failed: " + i + " " + inArray[i] + " " + outArray[i]);
                break;
            }
        }
        for (int i = count; i < arr_len; i++) {
            if (outArray[i] != 0f) {
                result = false;
                android.util.Log.v("Allocation CopyTo Test", "Failed: " + i + " " + inArray[i] + " " + outArray[i]);
                break;
            }
        }
        if (result) {
            android.util.Log.v("Allocation CopyTo Test", "copy1DRangeTo_Float TEST PASSED");
        } else {
            failTest();
        }
    }

    public void allocation_copy1DRangeTo_Long(RenderScript mRS) {
        Random random = new Random(0x172d8ab9);
        int width = random.nextInt(512);
        int arr_len = width;

        long[] inArray = new long[arr_len];
        long[] outArray = new long[arr_len];

        for (int i = 0; i < arr_len; i++) {
            inArray[i] = random.nextLong();
        }

        Type.Builder typeBuilder = new Type.Builder(mRS, Element.I64(mRS));
        typeBuilder.setX(width);
        Allocation alloc = Allocation.createTyped(mRS, typeBuilder.create());
        int offset = random.nextInt(arr_len);
        int count = arr_len - offset;
        alloc.copy1DRangeFrom(offset, count, inArray);
        alloc.copy1DRangeTo(offset, count, outArray);

        boolean result = true;
        for (int i = 0; i < count; i++) {
            if (inArray[i] != outArray[i]) {
                result = false;
                android.util.Log.v("Allocation CopyTo Test", "Failed: " + i + " " + inArray[i] + " " + outArray[i]);
                break;
            }
        }
        for (int i = count; i < arr_len; i++) {
            if (outArray[i] != 0) {
                result = false;
                android.util.Log.v("Allocation CopyTo Test", "Failed: " + i + " " + inArray[i] + " " + outArray[i]);
                break;
            }
        }
        if (result) {
            android.util.Log.v("Allocation CopyTo Test", "copy1DRangeTo_Long TEST PASSED");
        } else {
            failTest();
        }
    }

    public void allocation_copy2DRangeTo_Byte(RenderScript mRS) {
        Random random = new Random(0x172d8ab9);
        int width = random.nextInt(128);
        int height = random.nextInt(128);
        int xoff = random.nextInt(width);
        int yoff = random.nextInt(height);
        int xcount = width - xoff;
        int ycount = height - yoff;
        int arr_len = xcount * ycount;

        byte[] inArray = new byte[arr_len];
        byte[] outArray = new byte[arr_len];
        random.nextBytes(inArray);

        Type.Builder typeBuilder = new Type.Builder(mRS, Element.I8(mRS));
        typeBuilder.setX(width).setY(height);
        Allocation alloc = Allocation.createTyped(mRS, typeBuilder.create());
        alloc.copy2DRangeFrom(xoff, yoff, xcount, ycount, inArray);
        alloc.copy2DRangeTo(xoff, yoff, xcount, ycount, outArray);

        boolean result = true;
        for (int i = 0; i < arr_len; i++) {
            if (inArray[i] != outArray[i]) {
                result = false;
                android.util.Log.v("Allocation CopyTo Test", "Failed: " + i + " " + inArray[i] + " " + outArray[i]);
                break;
            }
        }
        if (result) {
            android.util.Log.v("Allocation CopyTo Test", "copy2DRangeTo_Byte TEST PASSED");
        } else {
            failTest();
        }
    }

    public void allocation_copy2DRangeTo_Short(RenderScript mRS) {
        Random random = new Random(0x172d8ab9);
        int width = random.nextInt(128);
        int height = random.nextInt(128);
        int xoff = random.nextInt(width);
        int yoff = random.nextInt(height);
        int xcount = width - xoff;
        int ycount = height - yoff;
        int arr_len = xcount * ycount;

        short[] inArray = new short[arr_len];
        short[] outArray = new short[arr_len];

        for (int i = 0; i < arr_len; i++) {
            inArray[i] = (short)random.nextInt();
        }

        Type.Builder typeBuilder = new Type.Builder(mRS, Element.I16(mRS));
        typeBuilder.setX(width).setY(height);
        Allocation alloc = Allocation.createTyped(mRS, typeBuilder.create());
        alloc.copy2DRangeFrom(xoff, yoff, xcount, ycount, inArray);
        alloc.copy2DRangeTo(xoff, yoff, xcount, ycount, outArray);

        boolean result = true;
        for (int i = 0; i < arr_len; i++) {
            if (inArray[i] != outArray[i]) {
                result = false;
                android.util.Log.v("Allocation CopyTo Test", "Failed: " + i + " " + inArray[i] + " " + outArray[i]);
                break;
            }
        }
        if (result) {
            android.util.Log.v("Allocation CopyTo Test", "copy2DRangeTo_Short TEST PASSED");
        } else {
            failTest();
        }
    }

    public void allocation_copy2DRangeTo_Int(RenderScript mRS) {
        Random random = new Random(0x172d8ab9);
        int width = random.nextInt(128);
        int height = random.nextInt(128);
        int xoff = random.nextInt(width);
        int yoff = random.nextInt(height);
        int xcount = width - xoff;
        int ycount = height - yoff;
        int arr_len = xcount * ycount;

        int[] inArray = new int[arr_len];
        int[] outArray = new int[arr_len];

        for (int i = 0; i < arr_len; i++) {
            inArray[i] = random.nextInt();
        }

        Type.Builder typeBuilder = new Type.Builder(mRS, Element.I32(mRS));
        typeBuilder.setX(width).setY(height);
        Allocation alloc = Allocation.createTyped(mRS, typeBuilder.create());
        alloc.copy2DRangeFrom(xoff, yoff, xcount, ycount, inArray);
        alloc.copy2DRangeTo(xoff, yoff, xcount, ycount, outArray);

        boolean result = true;
        for (int i = 0; i < arr_len; i++) {
            if (inArray[i] != outArray[i]) {
                result = false;
                android.util.Log.v("Allocation CopyTo Test", "Failed: " + i + " " + inArray[i] + " " + outArray[i]);
                break;
            }
        }
        if (result) {
            android.util.Log.v("Allocation CopyTo Test", "copy2DRangeTo_Int TEST PASSED");
        } else {
            failTest();
        }
    }

    public void allocation_copy2DRangeTo_Float(RenderScript mRS) {
        Random random = new Random(0x172d8ab9);
        int width = random.nextInt(128);
        int height = random.nextInt(128);
        int xoff = random.nextInt(width);
        int yoff = random.nextInt(height);
        int xcount = width - xoff;
        int ycount = height - yoff;
        int arr_len = xcount * ycount;

        float[] inArray = new float[arr_len];
        float[] outArray = new float[arr_len];

        for (int i = 0; i < arr_len; i++) {
            inArray[i] = random.nextFloat();
        }

        Type.Builder typeBuilder = new Type.Builder(mRS, Element.F32(mRS));
        typeBuilder.setX(width).setY(height);
        Allocation alloc = Allocation.createTyped(mRS, typeBuilder.create());
        alloc.copy2DRangeFrom(xoff, yoff, xcount, ycount, inArray);
        alloc.copy2DRangeTo(xoff, yoff, xcount, ycount, outArray);

        boolean result = true;
        for (int i = 0; i < arr_len; i++) {
            if (inArray[i] != outArray[i]) {
                result = false;
                android.util.Log.v("Allocation CopyTo Test", "Failed: " + i + " " + inArray[i] + " " + outArray[i]);
                break;
            }
        }
        if (result) {
            android.util.Log.v("Allocation CopyTo Test", "copy2DRangeTo_Float TEST PASSED");
        } else {
            failTest();
        }
    }

    public void allocation_copy2DRangeTo_Long(RenderScript mRS) {
        Random random = new Random(0x172d8ab9);
        int width = random.nextInt(128);
        int height = random.nextInt(128);
        int xoff = random.nextInt(width);
        int yoff = random.nextInt(height);
        int xcount = width - xoff;
        int ycount = height - yoff;
        int arr_len = xcount * ycount;

        long[] inArray = new long[arr_len];
        long[] outArray = new long[arr_len];

        for (int i = 0; i < arr_len; i++) {
            inArray[i] = random.nextLong();
        }

        Type.Builder typeBuilder = new Type.Builder(mRS, Element.I64(mRS));
        typeBuilder.setX(width).setY(height);
        Allocation alloc = Allocation.createTyped(mRS, typeBuilder.create());
        alloc.copy2DRangeFrom(xoff, yoff, xcount, ycount, inArray);
        alloc.copy2DRangeTo(xoff, yoff, xcount, ycount, outArray);

        boolean result = true;
        for (int i = 0; i < arr_len; i++) {
            if (inArray[i] != outArray[i]) {
                result = false;
                android.util.Log.v("Allocation CopyTo Test", "Failed: " + i + " " + inArray[i] + " " + outArray[i]);
                break;
            }
        }
        if (result) {
            android.util.Log.v("Allocation CopyTo Test", "copy2DRangeTo_Long TEST PASSED");
        } else {
            failTest();
        }
    }


    public void allocation_copy1DRangeToUnchecked_Byte(RenderScript mRS) {
        Random random = new Random(0x172d8ab9);
        int width = random.nextInt(512);
        int arr_len = width;

        byte[] inArray = new byte[arr_len];
        byte[] outArray = new byte[arr_len];
        random.nextBytes(inArray);

        Type.Builder typeBuilder = new Type.Builder(mRS, Element.I8(mRS));
        typeBuilder.setX(width);
        Allocation alloc = Allocation.createTyped(mRS, typeBuilder.create());
        int offset = random.nextInt(arr_len);
        int count = arr_len - offset;
        alloc.copy1DRangeFrom(offset, count, inArray);
        alloc.copy1DRangeToUnchecked(offset, count, outArray);

        boolean result = true;
        for (int i = 0; i < count; i++) {
            if (inArray[i] != outArray[i]) {
                result = false;
                android.util.Log.v("Allocation CopyTo Test", "Failed: " + i + " " + inArray[i] + " " + outArray[i]);
                break;
            }
        }
        for (int i = count; i < arr_len; i++) {
            if (outArray[i] != 0) {
                result = false;
                android.util.Log.v("Allocation CopyTo Test", "Failed: " + i + " " + inArray[i] + " " + outArray[i]);
                break;
            }
        }
        if (result) {
            android.util.Log.v("Allocation CopyTo Test", "copy1DRangeToUnchecked_Byte TEST PASSED");
        } else {
            failTest();
        }
    }

    public void allocation_copy1DRangeToUnchecked_Short(RenderScript mRS) {
        Random random = new Random(0x172d8ab9);
        int width = random.nextInt(512);
        int arr_len = width;

        short[] inArray = new short[arr_len];
        short[] outArray = new short[arr_len];

        for (int i = 0; i < arr_len; i++) {
            inArray[i] = (short)random.nextInt();
        }

        Type.Builder typeBuilder = new Type.Builder(mRS, Element.I16(mRS));
        typeBuilder.setX(width);
        Allocation alloc = Allocation.createTyped(mRS, typeBuilder.create());
        int offset = random.nextInt(arr_len);
        int count = arr_len - offset;
        alloc.copy1DRangeFrom(offset, count, inArray);
        alloc.copy1DRangeToUnchecked(offset, count, outArray);

        boolean result = true;
        for (int i = 0; i < count; i++) {
            if (inArray[i] != outArray[i]) {
                result = false;
                android.util.Log.v("Allocation CopyTo Test", "Failed: " + i + " " + inArray[i] + " " + outArray[i]);
                break;
            }
        }
        for (int i = count; i < arr_len; i++) {
            if (outArray[i] != 0) {
                result = false;
                android.util.Log.v("Allocation CopyTo Test", "Failed: " + i + " " + inArray[i] + " " + outArray[i]);
                break;
            }
        }
        if (result) {
            android.util.Log.v("Allocation CopyTo Test", "copy1DRangeToUnchecked_Short TEST PASSED");
        } else {
            failTest();
        }
    }

    public void allocation_copy1DRangeToUnchecked_Int(RenderScript mRS) {
        Random random = new Random(0x172d8ab9);
        int width = random.nextInt(512);
        int arr_len = width;

        int[] inArray = new int[arr_len];
        int[] outArray = new int[arr_len];

        for (int i = 0; i < arr_len; i++) {
            inArray[i] = random.nextInt();
        }

        Type.Builder typeBuilder = new Type.Builder(mRS, Element.I32(mRS));
        typeBuilder.setX(width);
        Allocation alloc = Allocation.createTyped(mRS, typeBuilder.create());
        int offset = random.nextInt(arr_len);
        int count = arr_len - offset;
        alloc.copy1DRangeFrom(offset, count, inArray);
        alloc.copy1DRangeToUnchecked(offset, count, outArray);

        boolean result = true;
        for (int i = 0; i < count; i++) {
            if (inArray[i] != outArray[i]) {
                result = false;
                android.util.Log.v("Allocation CopyTo Test", "Failed: " + i + " " + inArray[i] + " " + outArray[i]);
                break;
            }
        }
        for (int i = count; i < arr_len; i++) {
            if (outArray[i] != 0) {
                result = false;
                android.util.Log.v("Allocation CopyTo Test", "Failed: " + i + " " + inArray[i] + " " + outArray[i]);
                break;
            }
        }
        if (result) {
            android.util.Log.v("Allocation CopyTo Test", "copy1DRangeToUnchecked_Int TEST PASSED");
        } else {
            failTest();
        }
    }

    public void allocation_copy1DRangeToUnchecked_Float(RenderScript mRS) {
        Random random = new Random(0x172d8ab9);
        int width = random.nextInt(512);
        int arr_len = width;

        float[] inArray = new float[arr_len];
        float[] outArray = new float[arr_len];

        for (int i = 0; i < arr_len; i++) {
            inArray[i] = random.nextFloat();
        }

        Type.Builder typeBuilder = new Type.Builder(mRS, Element.F32(mRS));
        typeBuilder.setX(width);
        Allocation alloc = Allocation.createTyped(mRS, typeBuilder.create());
        int offset = random.nextInt(arr_len);
        int count = arr_len - offset;
        alloc.copy1DRangeFrom(offset, count, inArray);
        alloc.copy1DRangeToUnchecked(offset, count, outArray);

        boolean result = true;
        for (int i = 0; i < count; i++) {
            if (inArray[i] != outArray[i]) {
                result = false;
                android.util.Log.v("Allocation CopyTo Test", "Failed: " + i + " " + inArray[i] + " " + outArray[i]);
                break;
            }
        }
        for (int i = count; i < arr_len; i++) {
            if (outArray[i] != 0f) {
                result = false;
                android.util.Log.v("Allocation CopyTo Test", "Failed: " + i + " " + inArray[i] + " " + outArray[i]);
                break;
            }
        }
        if (result) {
            android.util.Log.v("Allocation CopyTo Test", "copy1DRangeToUnchecked_Float TEST PASSED");
        } else {
            failTest();
        }
    }

    public void allocation_copy1DRangeToUnchecked_Long(RenderScript mRS) {
        Random random = new Random(0x172d8ab9);
        int width = random.nextInt(512);
        int arr_len = width;

        long[] inArray = new long[arr_len];
        long[] outArray = new long[arr_len];

        for (int i = 0; i < arr_len; i++) {
            inArray[i] = random.nextLong();
        }

        Type.Builder typeBuilder = new Type.Builder(mRS, Element.I64(mRS));
        typeBuilder.setX(width);
        Allocation alloc = Allocation.createTyped(mRS, typeBuilder.create());
        int offset = random.nextInt(arr_len);
        int count = arr_len - offset;
        alloc.copy1DRangeFrom(offset, count, inArray);
        alloc.copy1DRangeToUnchecked(offset, count, outArray);

        boolean result = true;
        for (int i = 0; i < count; i++) {
            if (inArray[i] != outArray[i]) {
                result = false;
                android.util.Log.v("Allocation CopyTo Test", "Failed: " + i + " " + inArray[i] + " " + outArray[i]);
                break;
            }
        }
        for (int i = count; i < arr_len; i++) {
            if (outArray[i] != 0) {
                result = false;
                android.util.Log.v("Allocation CopyTo Test", "Failed: " + i + " " + inArray[i] + " " + outArray[i]);
                break;
            }
        }
        if (result) {
            android.util.Log.v("Allocation CopyTo Test", "copy1DRangeToUnchecked_Long TEST PASSED");
        } else {
            failTest();
        }
    }
}
