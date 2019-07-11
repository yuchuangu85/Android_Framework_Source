/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.rs.imagejb;

import android.app.Activity;
import android.view.View;
import android.util.Log;

public class IPTestListJB {
    private final String TAG = "Img";
    public final String RESULT_FILE = "image_processing_result.csv";

    public static final int FULL_FP = 0;
    public static final int RELAXED_FP = 1;
    public static final int INTRINSIC = 2;

    /**
     * Define enum type for test names
     */
    public enum TestName {
        LEVELS_VEC3_RELAXED ("Levels Vec3 Relaxed", RELAXED_FP, 55.6f),
        LEVELS_VEC4_RELAXED ("Levels Vec4 Relaxed", RELAXED_FP, 39.1f),
        LEVELS_VEC3_FULL ("Levels Vec3 Full", FULL_FP, 57.4f),
        LEVELS_VEC4_FULL ("Levels Vec4 Full", FULL_FP, 68.1f),
        BLUR_RADIUS_25 ("Blur radius 25", RELAXED_FP, 1045.f),
        INTRINSIC_BLUR_RADIUS_25 ("Intrinsic Blur radius 25", INTRINSIC, 643.f),
        GREYSCALE ("Greyscale", RELAXED_FP, 38.3f),
        GRAIN ("Grain", RELAXED_FP, 57.8f),
        FISHEYE_FULL ("Fisheye Full", FULL_FP, 211.2f),
        FISHEYE_RELAXED ("Fisheye Relaxed", RELAXED_FP, 198.1f),
        FISHEYE_APPROXIMATE_FULL ("Fisheye Approximate Full", FULL_FP, 211.0f),
        FISHEYE_APPROXIMATE_RELAXED ("Fisheye Approximate Relaxed", RELAXED_FP, 190.1f),
        VIGNETTE_FULL ("Vignette Full", FULL_FP, 98.6f),
        VIGNETTE_RELAXED ("Vignette Relaxed", RELAXED_FP, 110.7f),
        VIGNETTE_APPROXIMATE_FULL ("Vignette Approximate Full", FULL_FP, 80.6f),
        VIGNETTE_APPROXIMATE_RELAXED ("Vignette Approximate Relaxed", RELAXED_FP, 87.9f),
        GROUP_TEST_EMULATED ("Group Test (emulated)", INTRINSIC, 37.81f),
        GROUP_TEST_NATIVE ("Group Test (native)", INTRINSIC, 37.8f),
        CONVOLVE_3X3 ("Convolve 3x3", RELAXED_FP, 62.1f),
        INTRINSICS_CONVOLVE_3X3 ("Intrinsics Convolve 3x3", INTRINSIC, 24.5f),
        COLOR_MATRIX ("ColorMatrix", RELAXED_FP, 25.5f),
        INTRINSICS_COLOR_MATRIX ("Intrinsics ColorMatrix", INTRINSIC, 13.3f),
        INTRINSICS_COLOR_MATRIX_GREY ("Intrinsics ColorMatrix Grey", INTRINSIC, 13.4f),
        COPY ("Copy", RELAXED_FP, 25.6f),
        CROSS_PROCESS_USING_LUT ("CrossProcess (using LUT)", INTRINSIC, 18.6f),
        CONVOLVE_5X5 ("Convolve 5x5", RELAXED_FP, 215.8f),
        INTRINSICS_CONVOLVE_5X5 ("Intrinsics Convolve 5x5", INTRINSIC, 29.8f),
        MANDELBROT_FLOAT ("Mandelbrot (fp32)", FULL_FP, 108.1f),
        MANDELBROT_DOUBLE ("Mandelbrot (fp64)", FULL_FP, 108.1f),
        INTRINSICS_BLEND ("Intrinsics Blend", INTRINSIC, 94.2f),
        INTRINSICS_BLUR_25G ("Intrinsics Blur 25 uchar", INTRINSIC, 173.3f),
        VIBRANCE ("Vibrance", RELAXED_FP, 88.3f),
        BW_FILTER ("BW Filter", RELAXED_FP, 69.7f),
        SHADOWS ("Shadows", RELAXED_FP, 155.3f),
        CONTRAST ("Contrast", RELAXED_FP, 27.0f),
        EXPOSURE ("Exposure", RELAXED_FP, 64.7f),
        WHITE_BALANCE ("White Balance", RELAXED_FP, 160.1f),
        COLOR_CUBE ("Color Cube", RELAXED_FP, 85.3f),
        COLOR_CUBE_3D_INTRINSIC ("Color Cube (3D LUT intrinsic)", INTRINSIC, 49.5f),
        ARTISTIC1 ("Artistic 1", RELAXED_FP, 120.f),
        RESIZE_BI_SCRIPT ("Resize BiCubic Script", RELAXED_FP, 100.f),
        RESIZE_BI_INTRINSIC ("Resize BiCubic Intrinsic", INTRINSIC, 100.f);


        private final String name;
        public final int group;
        public final float baseline;

        private TestName(String s, int g, float base) {
            name = s;
            group = g;
            baseline = base;
        }
        private TestName(String s, int g) {
            name = s;
            group = g;
            baseline = 1.f;
        }

        // return quoted string as displayed test name
        public String toString() {
            return name;
        }
    }

    static TestBase newTest(TestName testName) {
        switch(testName) {
        case LEVELS_VEC3_RELAXED:
            return new LevelsV4(false, false);
        case LEVELS_VEC4_RELAXED:
            return new LevelsV4(false, true);
        case LEVELS_VEC3_FULL:
            return new LevelsV4(true, false);
        case LEVELS_VEC4_FULL:
            return new LevelsV4(true, true);
        case BLUR_RADIUS_25:
            return new Blur25(false);
        case INTRINSIC_BLUR_RADIUS_25:
            return new Blur25(true);
        case GREYSCALE:
            return new Greyscale();
        case GRAIN:
            return new Grain();
        case FISHEYE_FULL:
            return new Fisheye(false, false);
        case FISHEYE_RELAXED:
            return new Fisheye(false, true);
        case FISHEYE_APPROXIMATE_FULL:
            return new Fisheye(true, false);
        case FISHEYE_APPROXIMATE_RELAXED:
            return new Fisheye(true, true);
        case VIGNETTE_FULL:
            return new Vignette(false, false);
        case VIGNETTE_RELAXED:
            return new Vignette(false, true);
        case VIGNETTE_APPROXIMATE_FULL:
            return new Vignette(true, false);
        case VIGNETTE_APPROXIMATE_RELAXED:
            return new Vignette(true, true);
        case GROUP_TEST_EMULATED:
            return new GroupTest(false);
        case GROUP_TEST_NATIVE:
            return new GroupTest(true);
        case CONVOLVE_3X3:
            return new Convolve3x3(false);
        case INTRINSICS_CONVOLVE_3X3:
            return new Convolve3x3(true);
        case COLOR_MATRIX:
            return new ColorMatrix(false, false);
        case INTRINSICS_COLOR_MATRIX:
            return new ColorMatrix(true, false);
        case INTRINSICS_COLOR_MATRIX_GREY:
            return new ColorMatrix(true, true);
        case COPY:
            return new Copy();
        case CROSS_PROCESS_USING_LUT:
            return new CrossProcess();
        case CONVOLVE_5X5:
            return new Convolve5x5(false);
        case INTRINSICS_CONVOLVE_5X5:
            return new Convolve5x5(true);
        case MANDELBROT_FLOAT:
            return new Mandelbrot(false);
        case MANDELBROT_DOUBLE:
            return new Mandelbrot(true);
        case INTRINSICS_BLEND:
            return new Blend();
        case INTRINSICS_BLUR_25G:
            return new Blur25G();
        case VIBRANCE:
            return new Vibrance();
        case BW_FILTER:
            return new BWFilter();
        case SHADOWS:
            return new Shadows();
        case CONTRAST:
            return new Contrast();
        case EXPOSURE:
            return new Exposure();
        case WHITE_BALANCE:
            return new WhiteBalance();
        case COLOR_CUBE:
            return new ColorCube(false);
        case COLOR_CUBE_3D_INTRINSIC:
            return new ColorCube(true);
        case ARTISTIC1:
            return new Artistic1();
        case RESIZE_BI_SCRIPT:
            return new Resize(false);
        case RESIZE_BI_INTRINSIC:
            return new Resize(true);
        }
        return null;
    }
}

