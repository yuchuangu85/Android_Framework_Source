/*
 * Copyright (C) 2026 The Android Open Source Project
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

package android.util;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.ravenwood.annotation.RavenwoodKeepWholeClass;

import java.util.ArrayList;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * A simple priority queue implementation that uses buckets to organize items.
 *
 * <p>This queue is designed for scenarios where the range of possible bucket values is small
 * and known in advance. The bucket values must be non-negative integers in the range
 * [0, {@code maxBucket}].
 *
 * <p>The queue orders items according to their bucket value. The item with the
 * <em>lowest</em> numerical bucket value is considered the head of the queue and will be
 * removed first. Among items with the same bucket value, this queue imposes a standard
 * Last-In-First-Out (LIFO) order.
 *
 * <p>This implementation is not thread-safe.
 *
 * <p><strong>Time Complexity:</strong>
 * <ul>
 *   <li>{@link #add}, {@link #offer}: O(1)</li>
 *   <li>{@link #remove}, {@link #poll}: O(K) in the worst case, where K is the range of bucket
 *       values (advancing the head pointer across empty buckets).
 *       Amortized O(1) for dense usage.</li>
 *   <li>{@link #isEmpty}: O(1)</li>
 *   <li>{@link #clear}: O(N + K), where N is the number of items and K is the range of
 *       bucket values.</li>
 * </ul>
 *
 * @param <T> The type of items held in this queue.
 * @hide
 */
@RavenwoodKeepWholeClass
public final class BucketPriorityQueue<T> {
    private static final String TAG = "BucketPriorityQueue";
    private static final int MAX_WARNING_BUCKET_SIZE = 10_000;

    /** The maximum bucket value supported by this queue (inclusive). */
    private final int mMaxBucket;

    /**
     * The buckets for each priority level.
     *
     * <p>The array index corresponds to the bucket value. The list at a given index is {@code null}
     * if and only if that bucket is empty.
     */
    private final ArrayList<T>[] mBuckets;

    /**
     * The bucket value of the current head of the queue.
     *
     * <p>If the queue is empty, this field is set to {@code mMaxBucket + 1}.
     */
    private int mMinBucketIndex;

    /**
     * Creates a new {@link BucketPriorityQueue} with the specified maximum bucket value.
     *
     * <p>This constructor allocates an array of buckets of size {@code maxBucket + 1}.
     * To avoid excessive memory usage, ensure that {@code maxBucket} is a reasonable value.
     *
     * @param maxBucket The maximum allowed bucket value (inclusive). Must be non-negative.
     * @throws IllegalArgumentException if {@code maxBucket} is negative.
     */
    @SuppressWarnings("unchecked")
    public BucketPriorityQueue(int maxBucket) {
        if (maxBucket < 0) {
            throw new IllegalArgumentException("maxBucket must be non-negative");
        }
        if (maxBucket > MAX_WARNING_BUCKET_SIZE) {
            Log.w(TAG, "Creating a BucketPriorityQueue with a large maxBucket: " + maxBucket);
        }
        mMaxBucket = maxBucket;
        mBuckets = new ArrayList[mMaxBucket + 1];
        mMinBucketIndex = mMaxBucket + 1;
    }

    /**
     * Returns whether this queue is empty.
     */
    public boolean isEmpty() {
        return mMinBucketIndex > mMaxBucket;
    }

    /**
     * Removes all items from this queue.
     */
    public void clear() {
        mMinBucketIndex = mMaxBucket + 1;
        for (int i = 0; i <= mMaxBucket; i++) {
            if (mBuckets[i] != null) {
                mBuckets[i] = null;
            }
        }
    }

    /**
     * Adds an item to the queue with the specified bucket.
     *
     * @param item   The item to add.
     * @param bucket The bucket of the item. Must be between 0 and {@code maxBucket} (inclusive).
     * @throws IllegalArgumentException if {@code bucket} is outside the valid range.
     */
    public void add(@NonNull T item, int bucket) {
        if (!offer(item, bucket)) {
            throw new IllegalArgumentException("Bucket " + bucket + " out of range [0, "
                    + mMaxBucket + "]");
        }
    }

    /**
     * Adds the specified item to this queue with the specified bucket.
     *
     * @param item   The item to add.
     * @param bucket The bucket of the item.
     * @return {@code true} if the item was added to this queue, else {@code false} if the
     *         bucket value is out of range.
     */
    public boolean offer(@NonNull T item, int bucket) {
        if (bucket < 0 || mMaxBucket < bucket) {
            return false;
        }

        if (mBuckets[bucket] == null) {
            mBuckets[bucket] = new ArrayList<>();
        }
        mBuckets[bucket].add(item);
        mMinBucketIndex = Math.min(mMinBucketIndex, bucket);
        return true;
    }

    /**
     * Retrieves and removes the item with the lowest bucket value.
     *
     * <p>If multiple items share the same bucket, the one added latest (LIFO) is returned.
     *
     * @return The item with the lowest bucket value.
     * @throws NoSuchElementException if the queue is empty.
     */
    public @NonNull T remove() {
        final T item = poll();
        if (item == null) {
            throw new NoSuchElementException();
        }
        return item;
    }

    /**
     * Retrieves and removes the head of this queue (the item with the lowest bucket value),
     * or returns {@code null} if this queue is empty.
     *
     * <p>If multiple items share the same bucket, the one added latest (LIFO) is returned.
     *
     * @return The head of this queue, or {@code null} if this queue is empty.
     */
    public @Nullable T poll() {
        if (isEmpty()) {
            return null;
        }

        // When the queue is not empty, mMinBucketIndex must point to the first non-null bucket.
        final ArrayList<T> list = Objects.requireNonNull(mBuckets[mMinBucketIndex]);
        final T item = list.removeLast();
        if (list.isEmpty()) {
            mBuckets[mMinBucketIndex] = null;
            while (mMinBucketIndex <= mMaxBucket && mBuckets[mMinBucketIndex] == null) {
                mMinBucketIndex++;
            }
        }

        return item;
    }

    @Override
    public String toString() {
        final int shownItemCount = 3;
        final StringBuilder stringBuilder = new StringBuilder(TAG);
        stringBuilder.append("{mMinBucketIndex=").append(mMinBucketIndex)
                .append(", mMaxBucket=").append(mMaxBucket)
                .append(", mBuckets=[");
        for (int i = 0; i < mMaxBucket + 1; i++) {
            if (mBuckets[i] == null || mBuckets[i].size() <= shownItemCount) {
                stringBuilder.append(mBuckets[i]);
            } else {
                stringBuilder.append("[");
                for (int j = 0; j < shownItemCount; j++) {
                    stringBuilder.append(mBuckets[i].get(j)).append(", ");
                }
                stringBuilder.append("...(size=").append(mBuckets[i].size()).append(")]");
            }

            if (i == mMaxBucket) {
                stringBuilder.append("]");
            } else {
                stringBuilder.append(", ");
            }
        }
        stringBuilder.append("}");

        return stringBuilder.toString();
    }
}
