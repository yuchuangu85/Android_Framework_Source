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

public class SGEMMTest extends TestBase {

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
    private int mTestSize;
    private final float allowedError = 0.000001f;

    SGEMMTest(int testSize) {
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

    // Calculate the square of the L2 norm of a matrix.
    private float calcL2Norm(float[] input) {
        float l2Norm = 0.f;
        for (int i = 0; i < input.length; ++i) {
            l2Norm += input[i] * input[i];
        }
        return l2Norm;
    }

    // Test whether the error of each element is samller the allowed error range.
    private boolean testWithTolerance(float[] out, float[] ref) {
        float l2NormOut = calcL2Norm(out);
        float l2NormRef = calcL2Norm(ref);
        float tolerance = allowedError * (l2NormOut < l2NormRef ? l2NormOut : l2NormRef);
        tolerance /= m * n;
        for (int i = 0; i < out.length; ++i) {
            float err = out[i] - ref[i];
            float absErr = err * err;
            if (absErr > tolerance) {
                return false;
            }
        }
        return true;
    }

    // Transform byte data into float, given a offset.
    private float[] byteToFloat(byte[] input, int offset) {
        float[] output = new float[input.length];
        for (int i = 0; i < input.length; ++i) {
            output[i] = (float)(input[i] - offset);
        }
        return output;
    }

    // Calculate the reference result for C = A*B
    private float[] getGEMMResult(int m, int n, int k, float[] a_float, float[] b_float) {
        float[] c_float = new float[m * n];
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < m; i++) {
                float total = 0.f;
                for (int l = 0; l < k; l++) {
                    int a_index = ((i * k) + l);
                    int b_index = ((l * n) + j);
                    float mult = a_float[a_index] * b_float[b_index];
                    total += mult;
                }
                int c_index = ((i * n) + j);
                c_float[c_index] = total;
            }
        }
        return c_float;
    }

    // This test multiplies a couple of small float matrices, and compares the
    // results with java-calculated expectations. The data here is arbitrary.
    public void setTestSmall() {
        m = 2;
        n = 4;
        k = 3;
        a_offset = 0;
        b_offset = 12;

        float[] a_float = byteToFloat(new byte[] {
                1, 2, 3,
                4, 5, 6,
            }, a_offset);

        float[] b_float = byteToFloat(new byte[] {
                11, 7, 3,
                10, 6, 2,
                9, 5, 1,
                8, 4, 0,
            }, b_offset);

        Type.Builder builder = new Type.Builder(mRS, Element.F32(mRS));
        Type a_type = builder.setX(k).setY(m).create();
        Type b_type = builder.setX(n).setY(k).create();
        Type c_type = builder.setX(n).setY(m).create();

        matA = Allocation.createTyped(mRS, a_type);
        matB = Allocation.createTyped(mRS, b_type);
        matC = Allocation.createTyped(mRS, c_type);

        matA.copyFrom(a_float);
        matB.copyFrom(b_float);

        //During setup, do a sample run to see if the result is correct.
        mBLAS.SGEMM(ScriptIntrinsicBLAS.NO_TRANSPOSE, ScriptIntrinsicBLAS.NO_TRANSPOSE,
                    1.0f, matA, matB, 0.f, matC);
        float[] c_float_ref = getGEMMResult(m, n, k, a_float, b_float);
        float[] c_float_out = new float[m * n];
        matC.copyTo(c_float_out);
        if (!testWithTolerance(c_float_ref, c_float_out)) {
            Log.e(TAG, "Result is not correct!");
            throw new AssertionError("Result is not correct.");
        }
    }

    // This test multiplies another two medium matrices, and compares the
    // results with the expected values. The data here is arbitrary.
    public void setTestMedium() {
        m = 7;
        n = 9;
        k = 23;
        a_offset = 13;
        b_offset = 23;

        float[] a_float = byteToFloat(new byte[] {
                1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23,
                23, 22, 21, 20, 19, 18, 17, 16, 15, 14, 13, 12, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1,
                1, 23, 2, 22, 3, 21, 4, 20, 5, 19, 6, 18, 7, 17, 8, 16, 9, 15, 10, 14, 11, 13, 12,
                23, 1, 22, 2, 21, 3, 20, 4, 19, 5, 18, 6, 17, 7, 16, 8, 15, 9, 14, 10, 13, 11, 12,
                1, 1, 1, 1, 1, 1, 1, 1, 1, 12, 12, 12, 12, 12, 12, 12, 12, 12, 12, 12, 12, 12, 12,
                3, 1, 4, 1, 5, 8, 2, 3, 1, 14, 11, 15, 18, 12, 13, 11, 14, 11, 15, 18, 12, 13, 11,
                8, 0, 5, 8, 1, 3, 7, 5, 7, 13, 10, 23, 13, 11, 17, 23, 12, 19, 17, 13, 14, 10, 19,
            }, a_offset);

        float[] b_float = byteToFloat(new byte[] {
                0, 2, 4, 6, 8, 10, 1, 3, 5, 7, 9, 11, 0, 2, 4, 6, 8, 10, 1, 3, 5, 7, 9,
                0, 20, 40, 60, 80, 10, 11, 13, 15, 17, 19, 21, 10, 12, 14, 6, 8, 10, 1, 3, 5, 7, 9,
                1, 21, 41, 61, 81, 11, 12, 14, 16, 18, 20, 22, 11, 13, 15, 7, 9, 11, 2, 4, 6, 8, 9,
                0, 19, 39, 59, 79, 9, 10, 12, 14, 16, 18, 20, 9, 11, 13, 5, 7, 9, 0, 2, 4, 6, 8,
                2, 22, 42, 62, 82, 12, 13, 15, 17, 19, 21, 23, 12, 14, 16, 8, 9, 12, 3, 5, 7, 9, 9,
                0, 18, 38, 58, 78, 8, 9, 11, 13, 15, 17, 19, 8, 10, 12, 4, 6, 8, 0, 1, 3, 5, 7,
                3, 23, 43, 63, 83, 13, 14, 16, 18, 20, 22, 24, 13, 15, 17, 9, 9, 13, 4, 6, 8, 9, 9,
                0, 17, 37, 57, 77, 7, 8, 10, 12, 14, 16, 18, 7, 9, 11, 3, 5, 7, 0, 0, 2, 4, 6,
                10, 20, 30, 40, 50, 1, 2, 3, 4, 5, 11, 12, 13, 14, 15, 21, 22, 23, 24, 25, 1, 2, 3,
            }, b_offset);

        Type.Builder builder = new Type.Builder(mRS, Element.F32(mRS));
        Type a_type = builder.setX(k).setY(m).create();
        Type b_type = builder.setX(n).setY(k).create();
        Type c_type = builder.setX(n).setY(m).create();

        matA = Allocation.createTyped(mRS, a_type);
        matB = Allocation.createTyped(mRS, b_type);
        matC = Allocation.createTyped(mRS, c_type);

        matA.copyFrom(a_float);
        matB.copyFrom(b_float);

        //During setup, do a sample run to see if the result is correct.
        mBLAS.SGEMM(ScriptIntrinsicBLAS.NO_TRANSPOSE, ScriptIntrinsicBLAS.NO_TRANSPOSE,
                    1.0f, matA, matB, 0.f, matC);
        float[] c_float_ref = getGEMMResult(m, n, k, a_float, b_float);
        float[] c_float_out = new float[m * n];
        matC.copyTo(c_float_out);
        if (!testWithTolerance(c_float_ref, c_float_out)) {
            Log.e(TAG, "Result is not correct!");
            throw new AssertionError("Result is not correct.");
        }
    }


    // This test takes a large set of real data captured from a convolutional
    // neural network solving a computer vision problem, and runs it through SGEMM.
    public void setTestLarge() {

        m = 256;
        n = 192;
        k = 1152;
        a_offset = 0;
        b_offset = 84;

        int a_count = (m * k);
        int b_count = (n * k);
        int c_count = (m * n);

        byte[] a_byte = new byte[a_count];
        byte[] b_byte = new byte[b_count];
        byte[] c_byte = new byte[c_count];

        getData(a_byte, b_byte, c_byte);

        float[] a_float = byteToFloat(a_byte, a_offset);
        float[] b_float = byteToFloat(b_byte, b_offset);

        Type.Builder builder = new Type.Builder(mRS, Element.F32(mRS));
        Type a_type = builder.setX(k).setY(m).create();
        Type b_type = builder.setX(n).setY(k).create();
        Type c_type = builder.setX(n).setY(m).create();

        matA = Allocation.createTyped(mRS, a_type);
        matB = Allocation.createTyped(mRS, b_type);
        matC = Allocation.createTyped(mRS, c_type);

        matA.copyFrom(a_float);
        matB.copyFrom(b_float);

        //During setup, do a sample run to see if the result is correct.
        mBLAS.SGEMM(ScriptIntrinsicBLAS.NO_TRANSPOSE, ScriptIntrinsicBLAS.NO_TRANSPOSE,
                    1.0f, matA, matB, 0.f, matC);
        float[] c_float_ref = getGEMMResult(m, n, k, a_float, b_float);
        float[] c_float_out = new float[c_count];
        matC.copyTo(c_float_out);
        if (!testWithTolerance(c_float_ref, c_float_out)) {
            Log.e(TAG, "Result is not correct!");
            throw new AssertionError("Result is not correct.");
        }
    }

    public void runTest() {
        mBLAS.SGEMM(ScriptIntrinsicBLAS.NO_TRANSPOSE, ScriptIntrinsicBLAS.NO_TRANSPOSE,
                    1.0f, matA, matB, 0.f, matC);
    }

    public String getTestInfo() {
        return "SGEMM Test: m=" + m + ", n=" + n + ", k=" + k;
    }
}
