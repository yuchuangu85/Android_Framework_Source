/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server.wifi.util;

import android.util.SparseIntArray;

/**
 * Utilities for Metrics collections.
 */
public class MetricsUtils {
    /**
     * A generic bucket containing a start, end, and count. The utility classes will convert to
     * such a generic bucket which can then be copied into the specific bucket of the proto.
     */
    public static class GenericBucket {
        public long start;
        public long end;
        public int count;
    }

    /**
     * Specifies a ~log histogram consisting of two levels of buckets - a set of N big buckets:
     *
     * Buckets starts at: B + P * M^i, where i=0, ... , N-1 (N big buckets)
     * Each big bucket is divided into S sub-buckets
     *
     * Each (big) bucket is M times bigger than the previous one.
     *
     * The buckets are then:
     * #0: B + P * M^0 with S buckets each of width (P*M^1-P*M^0)/S
     * #1: B + P * M^1 with S buckets each of width (P*M^2-P*M^1)/S
     * ...
     * #N-1: B + P * M^(N-1) with S buckets each of width (P*M^N-P*M^(N-1))/S
     */
    public static class LogHistParms {
        public LogHistParms(int b, int p, int m, int s, int n) {
            this.b = b;
            this.p = p;
            this.m = m;
            this.s = s;
            this.n = n;

            // derived values
            mLog = Math.log(m);
            bb = new double[n];
            sbw = new double[n];
            bb[0] = b + p;
            sbw[0] = p * (m - 1.0) / (double) s;
            for (int i = 1; i < n; ++i) {
                bb[i] = m * (bb[i - 1] - b) + b;
                sbw[i] = m * sbw[i - 1];
            }
        }

        // spec
        public int b;
        public int p;
        public int m;
        public int s;
        public int n;

        // derived
        public double mLog;
        public double[] bb; // bucket base
        public double[] sbw; // sub-bucket width
    }

    /**
     * Adds the input value to the log histogram based on the histogram parameters.
     */
    public static int addValueToLogHistogram(long x, SparseIntArray histogram, LogHistParms hp) {
        double logArg = (double) (x - hp.b) / (double) hp.p;
        int bigBucketIndex = -1;
        if (logArg > 0) {
            bigBucketIndex = (int) (Math.log(logArg) / hp.mLog);
        }
        int subBucketIndex;
        if (bigBucketIndex < 0) {
            bigBucketIndex = 0;
            subBucketIndex = 0;
        } else if (bigBucketIndex >= hp.n) {
            bigBucketIndex = hp.n - 1;
            subBucketIndex = hp.s - 1;
        } else {
            subBucketIndex = (int) ((x - hp.bb[bigBucketIndex]) / hp.sbw[bigBucketIndex]);
            if (subBucketIndex >= hp.s) { // probably a rounding error so move to next big bucket
                bigBucketIndex++;
                if (bigBucketIndex >= hp.n) {
                    bigBucketIndex = hp.n - 1;
                    subBucketIndex = hp.s - 1;
                } else {
                    subBucketIndex = (int) ((x - hp.bb[bigBucketIndex]) / hp.sbw[bigBucketIndex]);
                }
            }
        }
        int key = bigBucketIndex * hp.s + subBucketIndex;

        // note that get() returns 0 if index not there already
        int newValue = histogram.get(key) + 1;
        histogram.put(key, newValue);

        return newValue;
    }

    /**
     * Converts the log histogram (with the specified histogram parameters) to an array of generic
     * histogram buckets.
     */
    public static GenericBucket[] logHistogramToGenericBuckets(SparseIntArray histogram,
            LogHistParms hp) {
        GenericBucket[] protoArray = new GenericBucket[histogram.size()];
        for (int i = 0; i < histogram.size(); ++i) {
            int key = histogram.keyAt(i);

            protoArray[i] = new GenericBucket();
            protoArray[i].start = (long) (hp.bb[key / hp.s] + hp.sbw[key / hp.s] * (key % hp.s));
            protoArray[i].end = (long) (protoArray[i].start + hp.sbw[key / hp.s]);
            protoArray[i].count = histogram.valueAt(i);
        }

        return protoArray;
    }

    /**
     * Adds the input value to the histogram based on the lineaer histogram parameters.
     *
     * The 'int[] hp' contains a list of bucket limits. The number of buckets is hp.length() + 1
     * where buckets are:
     * - < hp[0]
     * - [hp[0], hp[1])
     * ...
     * - >= hp[hp.length() - 1]
     */
    public static int addValueToLinearHistogram(int x, SparseIntArray histogram, int[] hp) {
        int bucket = 0;
        for (int limit : hp) {
            if (x >= limit) {
                bucket++;
                continue;
            }
            break;
        }

        // note that get() returns 0 if index not there already
        int newValue = histogram.get(bucket) + 1;
        histogram.put(bucket, newValue);

        return newValue;
    }

    /**
     * Converts the histogram (with the specified linear histogram parameters) to an array of
     * generic histogram buckets.
     */
    public static GenericBucket[] linearHistogramToGenericBuckets(SparseIntArray histogram,
            int[] linearHistParams) {
        GenericBucket[] protoArray = new GenericBucket[histogram.size()];
        for (int i = 0; i < histogram.size(); ++i) {
            int bucket = histogram.keyAt(i);

            protoArray[i] = new GenericBucket();
            if (bucket == 0) {
                protoArray[i].start = Integer.MIN_VALUE;
                protoArray[i].end = linearHistParams[0];
            } else if (bucket != linearHistParams.length) {
                protoArray[i].start = linearHistParams[bucket - 1];
                protoArray[i].end = linearHistParams[bucket];
            } else {
                protoArray[i].start = linearHistParams[linearHistParams.length - 1];
                protoArray[i].end = Integer.MAX_VALUE;
            }
            protoArray[i].count = histogram.valueAt(i);
        }

        return protoArray;
    }
}
