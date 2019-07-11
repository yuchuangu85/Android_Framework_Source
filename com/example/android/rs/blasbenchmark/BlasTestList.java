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

import android.app.Activity;
import android.view.View;
import android.util.Log;

public class BlasTestList {
    private final String TAG = "BLAS";
    public final String RESULT_FILE = "blas_benchmark_result.csv";

    /**
     * Define enum type for test names
     */
    public enum TestName {

        SGEMM_SMALL ("SGEMM Test Small", 1.f),
        SGEMM_MEDIUM ("SGEMM Test Medium", 1.f),
        SGEMM_LARGE ("SGEMM Test LARGE", 1.f),
        BNNM_SMALL ("8Bit GEMM Test Small", 1.f),
        BNNM_MEDIUM ("8Bit GEMM Test Medium", 1.f),
        BNNM_LARGE ("8Bit GEMM Test Large", 1.f);

        private final String name;
        public final float baseline;

        private TestName(String s, float base) {
            name = s;
            baseline = base;
        }
        private TestName(String s) {
            name = s;
            baseline = 1.f;
        }

        // return quoted string as displayed test name
        public String toString() {
            return name;
        }
    }

    static TestBase newTest(TestName testName) {
        switch(testName) {
        case SGEMM_SMALL:
            return new SGEMMTest(1);
        case SGEMM_MEDIUM:
            return new SGEMMTest(2);
        case SGEMM_LARGE:
            return new SGEMMTest(3);
        case BNNM_SMALL:
            return new BNNMTest(1);
        case BNNM_MEDIUM:
            return new BNNMTest(2);
        case BNNM_LARGE:
            return new BNNMTest(3);
        }

        return null;
    }
}

