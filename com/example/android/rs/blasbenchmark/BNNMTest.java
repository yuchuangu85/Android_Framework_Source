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

package com.example.android.rs.blasbenchmark;

import android.renderscript.*;
import android.util.Log;
import java.util.Random;
import java.lang.Math;

public class BNNMTest extends TestBase {

    static {
        System.loadLibrary("gemmdata");
    }

    native void getData(byte[] a, byte[] b, byte[] c);

    ScriptIntrinsicBLAS mBLAS;
    private Allocation matA;
    private Allocation matB;
    private Allocation matC;

    private int m;
    private int n;
    private int k;

    private int a_offset;
    private int b_offset;
    private int c_offset;
    private int c_mult_int;

    private int mTestSize;

    BNNMTest(int testSize) {
        mTestSize = testSize;
    }

    public void createTest() {
        mBLAS = ScriptIntrinsicBLAS.create(mRS);
        setTest();
    }

    private void setTest() {
        switch (mTestSize) {
            case 1:
                setTestSmall();
                break;
            case 2:
                setTestMedium();
                break;
            case 3:
                setTestLarge();
                break;
            default:
                break;
        }
    }

    // In Java, the eight-bit 'byte' type is signed, but the API for the 8-bit
    // matrix multiplication deals with unsigned bytes. This is a convenience
    // function that converts arrays of unsigned ints to their equivalent
    // representations as signed bytes. For example, the bit pattern 0xff is 255
    // as an unsigned value, but -127 as a Java signed byte. So if you pass in an
    // array of int[] {255} into this function, you'll get back byte[] {-127}.
    private byte[] unsignedToSignedByte(int[] input) {
        byte[] output = new byte[input.length];
        for (int i = 0; i < input.length; ++i) {
            output[i] = (byte)(input[i]);
        }
        return output;
    }


    private void addByteNoise(byte[] data, int count, float frequency, int maxDelta) {
        Random rand = new Random();
        for (int n = 0; n < count; ++n) {
            if (rand.nextFloat() < frequency) {
                final int originalValue = data[n];
                final float direction = rand.nextFloat();
                int delta = (int)(Math.ceil(rand.nextFloat() * maxDelta));
                if (direction < 0.5f) {
                    delta = -delta;
                }
                int newValue = (originalValue + delta);
                if (newValue < -127) {
                    newValue = -127;
                }
                if (newValue > 127) {
                    newValue = 127;
                }
                data[n] = (byte)(newValue);
            }
        }
    }

    private boolean testWithTolerance(byte[] c_byte, byte[] c_byte_output) {

        // The testing procedure here is a bit complex, but the aim is to mimic the
        // requirements we've empirically found running deep neural networks in real
        // applications. We want to open the door to vendors using approximations that
        // produce slightly different results for optimization's sake, but keep the
        // precision loss within small enough bounds that we don't lose accuracy in
        // the final result.
        // After experimentation, we've found that we can tolerate around 5% of the
        // output bytes being different by 1. Any larger differences are not tolerable
        // and we can't get good results if the frequency of small differences is
        // higher than 5%. This test tries to measure those properties on an example
        // set of parameters that were captured from a real application.
        // For example, if you uncommented this function that adds random noise to the
        // results at a 3% specified frequency, the test should fail:
        // AddByteNoise(c_byte_output, c_count, 0.03f, 1);

        final boolean areSizesDifferent = (c_byte.length != c_byte_output.length);
        final int c_count = Math.min(c_byte.length, c_byte_output.length);

        int howManyDifferent = 0;
        boolean areAnyTooDifferent = false;
        for (int i = 0; i < c_count; i++) {
            byte expectedValue = c_byte[i];
            byte actualValue = c_byte_output[i];
            int delta = (expectedValue - actualValue);
            // First make sure that the difference is no more than one.
            if ((delta < -1) || (delta > 1)) {
                areAnyTooDifferent = true;
            }
            // If there is a difference, increment the counter to track it.
            if (delta != 0) {
                // Don't spam the logs if too many are different.
                if (howManyDifferent < 50) {
                    android.util.Log.e("BNNM", "Mismatch at " + i +
                                       ": expected " + (expectedValue & 0xff) +
                                       ", got " + (actualValue & 0xff));
                }
                ++howManyDifferent;
            }
        }
        // We want no more than 2% of the values to show any differences, so work out
        // what that means in absolute numbers.
        final int percentThreshold = 2;
        final int differenceThreshold = Math.max((percentThreshold * c_count) / 100, 1);
        final boolean areTooManyDifferent = (howManyDifferent >= differenceThreshold);

        if (areAnyTooDifferent) {
            android.util.Log.e("BNNM", "Some outputs were too different.");
        }

        if (areTooManyDifferent) {
            android.util.Log.e("BNNM", "There were too many small differences." +
                               " We can tolerate " + percentThreshold + "% (" +
                               differenceThreshold + "), but there were " + howManyDifferent);
        }

        return !(areAnyTooDifferent || areTooManyDifferent);
    }

    // This test multiplies a couple of small 8-bit matrices, and compares the
    // results with hand-calculated expectations.
    public void setTestSmall() {
        // The A matrix is:
        // |   1 |   4 |
        // |   2 |   5 |
        // |   3 |   6 |
        byte[] a_byte = unsignedToSignedByte(new int[] {
                1, 2, 3,
                4, 5, 6,
            });
        final int a_rows = 3;
        final int a_cols = 2;
        a_offset = 0;
        // The B matrix is:
        // |  -1 |  -2 |  -3 |  -4 |
        // |  -5 |  -6 |  -7 |  -8 |
        // |  -9 | -10 | -11 | -12 |
        byte[] b_byte = unsignedToSignedByte(new int[] {
                11, 7, 3,
                10, 6, 2,
                9, 5, 1,
                8, 4, 0,
            });
        final int b_cols = 4;
        b_offset = 12;
        // EightBitGemm implements C = B.transposed() * A,
        // so we expect to get these results:
        // 1*-1 + 2*-5 + 3*-9 + 128 = 90
        // 1*-2 + 2*-6 + 3*-10 + 128 = 84
        // 1*-3 + 2*-7 + 3*-11 + 128 = 78
        // 1*-4 + 2*-8 + 3*-12 + 128 = 72
        // 4*-1 + 5*-5 + 6*-9 + 128 = 45
        // 4*-2 + 5*-6 + 6*-10 + 128 = 30
        // 4*-3 + 5*-7 + 6*-11 + 128 = 15
        // 4*-4 + 5*-8 + 6*-12 + 128 = 0
        // | 90 |  45 |
        // | 84 |  30 |
        // | 78 | 15 |
        // | 72 | 0 |
        c_offset = 128;
        final int c_shift = 21;
        c_mult_int = (1 << c_shift);
        byte[] expected_data = unsignedToSignedByte(new int[] {
                90, 84, 78, 72,
                45, 30, 15, 0,
            });

        m = a_cols;
        n = b_cols;
        k = a_rows;

        Type.Builder builder = new Type.Builder(mRS, Element.U8(mRS));
        Type a_type = builder.setX(k).setY(m).create();
        Type b_type = builder.setX(k).setY(n).create();
        Type c_type = builder.setX(n).setY(m).create();

        matA = Allocation.createTyped(mRS, a_type);
        matB = Allocation.createTyped(mRS, b_type);
        matC = Allocation.createTyped(mRS, c_type);
        matA.copyFrom(a_byte);
        matB.copyFrom(b_byte);

        //During setup, do a sample run to see if the result is correct.
        mBLAS.BNNM(matA, a_offset, matB, b_offset, matC, c_offset, c_mult_int);
        int c_count = (m * n);
        byte[] c_byte_output = new byte[c_count];
        matC.copyTo(c_byte_output);
        if (!testWithTolerance(expected_data, c_byte_output)) {
            Log.e(TAG, "Result is not correct!");
            throw new AssertionError("Result is not correct.");
        }
    }


    // This test multiplies another two medium 8-bit matrices, and compares the
    // results with the expected values. The data here is arbitrary.
    public void setTestMedium() {
        byte[] a_byte = unsignedToSignedByte(new int[] {
                1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23,
                23, 22, 21, 20, 19, 18, 17, 16, 15, 14, 13, 12, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1,
                1, 23, 2, 22, 3, 21, 4, 20, 5, 19, 6, 18, 7, 17, 8, 16, 9, 15, 10, 14, 11, 13, 12,
                23, 1, 22, 2, 21, 3, 20, 4, 19, 5, 18, 6, 17, 7, 16, 8, 15, 9, 14, 10, 13, 11, 12,
                1, 1, 1, 1, 1, 1, 1, 1, 1, 12, 12, 12, 12, 12, 12, 12, 12, 12, 12, 12, 12, 12, 12,
                3, 1, 4, 1, 5, 8, 2, 3, 1, 14, 11, 15, 18, 12, 13, 11, 14, 11, 15, 18, 12, 13, 11,
                8, 0, 5, 8, 1, 3, 7, 5, 7, 13, 10, 23, 13, 11, 17, 23, 12, 19, 17, 13, 14, 10, 19,
            });
        final int a_rows = 23;
        final int a_cols = 7;
        a_offset = 13;
        byte[] b_byte = unsignedToSignedByte(new int[] {
                0, 2, 4, 6, 8, 10, 1, 3, 5, 7, 9, 11, 0, 2, 4, 6, 8, 10, 1, 3, 5, 7, 9,
                0, 20, 40, 60, 80, 10, 11, 13, 15, 17, 19, 21, 10, 12, 14, 6, 8, 10, 1, 3, 5, 7, 9,
                1, 21, 41, 61, 81, 11, 12, 14, 16, 18, 20, 22, 11, 13, 15, 7, 9, 11, 2, 4, 6, 8, 9,
                0, 19, 39, 59, 79, 9, 10, 12, 14, 16, 18, 20, 9, 11, 13, 5, 7, 9, 0, 2, 4, 6, 8,
                2, 22, 42, 62, 82, 12, 13, 15, 17, 19, 21, 23, 12, 14, 16, 8, 9, 12, 3, 5, 7, 9, 9,
                0, 18, 38, 58, 78, 8, 9, 11, 13, 15, 17, 19, 8, 10, 12, 4, 6, 8, 0, 1, 3, 5, 7,
                3, 23, 43, 63, 83, 13, 14, 16, 18, 20, 22, 24, 13, 15, 17, 9, 9, 13, 4, 6, 8, 9, 9,
                0, 17, 37, 57, 77, 7, 8, 10, 12, 14, 16, 18, 7, 9, 11, 3, 5, 7, 0, 0, 2, 4, 6,
                10, 20, 30, 40, 50, 1, 2, 3, 4, 5, 11, 12, 13, 14, 15, 21, 22, 23, 24, 25, 1, 2, 3,
            });
        final int b_cols = 9;
        b_offset = 23;
        c_offset = 2121;
        final int c_shift = 21;
        c_mult_int = 132359;
        byte[] expected_data = unsignedToSignedByte(new int[] {
                167, 53, 51, 54, 49, 55, 46,
                56, 116, 153, 232, 232, 234, 231,
                236, 232, 237, 174, 168, 131, 130,
                132, 129, 133, 128, 133, 134, 151,
                154, 152, 156, 151, 158, 150, 160,
                156, 255, 113, 106, 120, 98, 127,
                91, 134, 178, 231, 102, 97, 107,
                92, 111, 87, 116, 164, 187, 76,
                73, 78, 70, 81, 67, 83, 139,
            });

        m = a_cols;
        n = b_cols;
        k = a_rows;

        Type.Builder builder = new Type.Builder(mRS, Element.U8(mRS));
        Type a_type = builder.setX(k).setY(m).create();
        Type b_type = builder.setX(k).setY(n).create();
        Type c_type = builder.setX(n).setY(m).create();

        matA = Allocation.createTyped(mRS, a_type);
        matB = Allocation.createTyped(mRS, b_type);
        matC = Allocation.createTyped(mRS, c_type);

        matA.copyFrom(a_byte);
        matB.copyFrom(b_byte);

        //During setup, do a sample run to see if the result is correct.
        mBLAS.BNNM(matA, a_offset, matB, b_offset, matC, c_offset, c_mult_int);
        int c_count = (m * n);
        byte[] c_byte_output = new byte[c_count];
        matC.copyTo(c_byte_output);
        if (!testWithTolerance(expected_data, c_byte_output)) {
            Log.e(TAG, "Result is not correct!");
            throw new AssertionError("Result is not correct.");
        }
    }



    // This test takes a large set of real data captured from a convolutional
    // neural network solving a computer vision problem, and runs it through the
    // eight-bit matrix multiply. We test the results to make sure they're close
    // enough to be usable.
    public void setTestLarge() {

        m = 256;
        n = 192;
        k = 1152;
        a_offset = 0;
        b_offset = 84;
        c_mult_int = 3401;
        c_offset = 74980;

        int a_count = (m * k);
        int b_count = (n * k);
        int c_count = (m * n);

        byte[] a_byte = new byte[a_count];
        byte[] b_byte = new byte[b_count];
        byte[] c_byte = new byte[c_count];

        getData(a_byte, b_byte, c_byte);

        Type.Builder builder = new Type.Builder(mRS, Element.U8(mRS));
        Type a_type = builder.setX(k).setY(m).create();
        Type b_type = builder.setX(k).setY(n).create();
        Type c_type = builder.setX(n).setY(m).create();

        matA = Allocation.createTyped(mRS, a_type);
        matB = Allocation.createTyped(mRS, b_type);
        matC = Allocation.createTyped(mRS, c_type);

        matA.copyFrom(a_byte);
        matB.copyFrom(b_byte);

        //During setup, do a sample run to see if the result is correct.
        mBLAS.BNNM(matA, a_offset, matB, b_offset, matC, c_offset, c_mult_int);
        byte[] c_byte_output = new byte[c_count];
        matC.copyTo(c_byte_output);
        if (!testWithTolerance(c_byte, c_byte_output)) {
            Log.e(TAG, "Result is not correct!");
            throw new AssertionError("Result is not correct.");
        }
    }

    public void runTest() {
        mBLAS.BNNM(matA, a_offset, matB, b_offset, matC, c_offset, c_mult_int);
    }

    public String getTestInfo() {
        return "8Bit GEMM Test: m=" + m + ", n=" + n + ", k=" + k;
    }
}
