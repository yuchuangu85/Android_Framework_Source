/*
 * Copyright (C) 2019 The Android Open Source Project
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

import android.annotation.NonNull;
import android.util.SparseIntArray;

import com.android.server.wifi.proto.nano.WifiMetricsProto.HistogramBucketInt32;

import java.lang.reflect.Array;
import java.util.Arrays;
import java.util.Iterator;


/**
 * A histogram that stores the counts of values that fall within buckets, where buckets contain
 * the count for a range of values. This implementation is backed by a SparseIntArray, meaning
 * values are stored as int and counts are stored as int.
 */
public class IntHistogram implements Iterable<IntHistogram.Bucket> {
    /*
     * Definitions:
     * - value: what you would like to count
     * - count: the number of occurrences of values that fall within a bucket
     * - key: mBuckets maps key => count, 0 <= key <= mBucketBoundaries.length, keys can be
     *        uninitialized for buckets that were never incremented
     * - bucketIndex: the index of the initialized buckets, 0 <= bucketIndex < mBucket.size(),
     *        all indices in this range are initialized.
     */
    private SparseIntArray mBuckets;
    private final int[] mBucketBoundaries;

    /**
     * A bucket in the histogram, for the range [start, end).
     * An exception to this is when {@link Bucket#end} == Integer.MAX_VALUE, when the range will be
     * [start, end].
     */
    public static class Bucket {
        public int start;
        public int end;
        public int count;

        public Bucket(int start, int end, int count) {
            this.start = start;
            this.end = end;
            this.count = count;
        }
    }

    /**
     * Constructs a histogram given the boundary values of buckets, as an int[].
     * @param bucketBoundaries The boundary values that separate each bucket. The number of
     *                         buckets is bucketBoundaries.length + 1, where the buckets are:
     *                         - < bucketBoundaries[0]
     *                         - [bucketBoundaries[0], bucketBoundaries[1])
     *                         ...
     *                         - >= bucketBoundaries[bucketBoundaries.length() - 1]
     *                         This array must be non-null, non-empty, and strictly monotonically
     *                         increasing i.e. a[i] < a[i+1].
     */
    public IntHistogram(@NonNull int[] bucketBoundaries) {
        if (bucketBoundaries == null || bucketBoundaries.length == 0) {
            throw new IllegalArgumentException("bucketBoundaries must be non-null and non-empty!");
        }
        for (int i = 0; i < bucketBoundaries.length - 1; i++) {
            int cur = bucketBoundaries[i];
            int next = bucketBoundaries[i + 1];
            if (cur >= next) {
                throw new IllegalArgumentException(String.format(
                        "bucketBoundaries values must be strictly monotonically increasing, but "
                                + "value %d at index %d is greater or equal to "
                                + "value %d at index %d!",
                        cur, i, next, i + 1));
            }
        }
        mBucketBoundaries = bucketBoundaries.clone();
        mBuckets = new SparseIntArray();
    }

    /**
     * Resets this histogram to the initial state.
     */
    public void clear() {
        mBuckets.clear();
    }

    /**
     * Returns the number of non-empty buckets, where an empty bucket is a bucket that was never
     * added to.
     */
    public int numNonEmptyBuckets() {
        return mBuckets.size();
    }

    /**
     * Returns the maximum number of possible buckets (dictated by the number of bucket boundaries).
     */
    public int numTotalBuckets() {
        return mBucketBoundaries.length + 1;
    }

    /**
     * Gets the nth non-empty bucket, where 0 <= n < {@link #numNonEmptyBuckets()}
     */
    public Bucket getBucketByIndex(int bucketIndex) {
        int bucketKey = mBuckets.keyAt(bucketIndex);
        int start = bucketKey == 0
                ? Integer.MIN_VALUE : mBucketBoundaries[bucketKey - 1];
        int end = bucketKey == mBucketBoundaries.length
                ? Integer.MAX_VALUE : mBucketBoundaries[bucketKey];
        int count = mBuckets.valueAt(bucketIndex);
        return new Bucket(start, end, count);
    }

    /**
     * Increments the count of the bucket that this value falls into by 1.
     */
    public void increment(int value) {
        add(value, 1);
    }

    /**
     * Increments the count of the bucket that this value falls into by <code>count</code>.
     */
    public void add(int value, int count) {
        int bucketKey = getBucketKey(value);
        int curBucketValue = mBuckets.get(bucketKey);
        mBuckets.put(bucketKey, curBucketValue + count);
    }


    /**
     * Computes the inverse of the cumulative probability distribution for the histogram.
     *
     * This is the value v such that the probability of a randomly selected datum being
     * less than or equal to v is the provided probability. The answer is constrained to
     * lie in the interval [minimum..maximum].
     */
    public double quantileFunction(double probability, int minimum, int maximum) {
        if (minimum > maximum) {
            throw new IllegalArgumentException("bad bounds");
        }
        if (probability < 0.0 || probability > 1.0) {
            throw new IllegalArgumentException("bad roll, try again");
        }
        long sum = 0;
        for (Bucket bucket : this) {
            sum += bucket.count;
        }
        final double target = sum * probability;
        double partialSum = 0.0;
        Bucket hitBucket = null;
        for (Bucket bucket : this) {
            if (partialSum + bucket.count >= target) {
                hitBucket = bucket;
                break;
            }
            partialSum += bucket.count;
        }
        if (hitBucket == null) {
            // No data at all; assume uniform between given limits
            return minimum + probability * (maximum - minimum);
        }
        double highValue = Math.min(hitBucket.end, maximum);
        double value = Math.max(hitBucket.start, minimum);
        if (value >= highValue - 1.0 || hitBucket.count == 0) return Math.min(value, highValue);
        // interpolate to estimate the value
        value += (highValue - value) * (target - partialSum) / hitBucket.count;
        return Math.min(Math.max(value, minimum), maximum);
    }

    /**
     * Given a value, returns the key of the bucket where it should fall into.
     */
    private int getBucketKey(int value) {
        // this passes unit tests, so don't worry about it too much
        int insertionIndex = Arrays.binarySearch(mBucketBoundaries, value);
        return Math.abs(insertionIndex + 1);
    }

    /**
     * Returns a human-readable string representation of the contents of this histogram, suitable
     * for dump().
     */
    @Override
    public String toString() {
        if (mBuckets.size() <= 0) {
            return "{}";
        }

        StringBuilder sb = new StringBuilder();
        sb.append('{');
        for (int bucketIndex = 0; bucketIndex < mBuckets.size(); ++bucketIndex) {
            if (bucketIndex > 0) {
                sb.append(", ");
            }
            int bucketKey = mBuckets.keyAt(bucketIndex);
            sb.append('[');
            if (bucketKey == 0) {
                sb.append("Integer.MIN_VALUE");
            } else {
                sb.append(mBucketBoundaries[bucketKey - 1]);
            }
            sb.append(',');
            if (bucketKey == mBucketBoundaries.length) {
                sb.append("Integer.MAX_VALUE]");
            } else {
                sb.append(mBucketBoundaries[bucketKey]).append(')');
            }
            sb.append('=').append(mBuckets.valueAt(bucketIndex));
        }
        sb.append('}');
        return sb.toString();
    }

    /**
     * Iterates over initialized buckets.
     */
    @Override
    public Iterator<Bucket> iterator() {
        return new Iterator<Bucket>() {
            private int mBucketIndex = 0;

            @Override
            public boolean hasNext() {
                return mBucketIndex < mBuckets.size();
            }

            @Override
            public Bucket next() {
                Bucket bucket = getBucketByIndex(mBucketIndex);
                mBucketIndex++;
                return bucket;
            }
        };
    }

    /**
     * For backwards compatibility, can specify a conversion function that converts a bucket in this
     * histogram to a specified Protobuf type, used by {@link #toProto(Class, ProtobufConverter)}.
     * Note that for all new histograms, the standard Protobuf representation type is
     * {@link HistogramBucketInt32[]}, which can be generated using {@link #toProto()}.
     * @param <T> The type to convert to.
     */
    public interface ProtobufConverter<T> {
        /**
         * Conversion function.
         * @param start start of the range of a bucket.
         * @param end end of the range of a bucket.
         * @param count count of values in this bucket.
         * @return A Protobuf representation of this bucket.
         */
        T convert(int start, int end, int count);
    }

    /**
     * Converts this histogram to a Protobuf representation. See {@link ProtobufConverter}
     * @param protoClass the class object for the Protobuf type.
     * @param converter a conversion function.
     * @param <T> the type of the Protobuf output.
     * @return an array of Protobuf representation of buckets generated by the converter function.
     */
    public <T> T[] toProto(Class<T> protoClass, ProtobufConverter<T> converter) {
        @SuppressWarnings("unchecked")
        T[] output = (T[]) Array.newInstance(protoClass, mBuckets.size());
        int i = 0;
        for (Bucket bucket : this) {
            output[i] = converter.convert(bucket.start, bucket.end, bucket.count);
            i++;
        }
        return output;
    }

    /**
     * Converts this histogram to a standard Protobuf representation.
     */
    public HistogramBucketInt32[] toProto() {
        return toProto(HistogramBucketInt32.class, (start, end, count) -> {
            HistogramBucketInt32 hb = new HistogramBucketInt32();
            hb.start = start;
            hb.end = end;
            hb.count = count;
            return hb;
        });
    }
}
