/*
 * Copyright (C) 2025 The Android Open Source Project
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

import android.annotation.Nullable;

import com.android.internal.annotations.VisibleForTesting;

import java.util.function.BiFunction;

/**
 * Thread-safe container for values of type T with integer as keys. This is a copy on write
 * container that is copied when value is missing from the container during {@link #put} (or the
 * value is present during {@link #remove}). Full copy of {@link #mContainer} is necessary in
 * {@link #put} and {@link #remove}, to avoid concurrent modification issues when the container is
 * accessed via {@link #get} by multiple threads without holding the {@link #mLock}.
 * <p>
 * If the value is present during {@link #put}, it is simply updated. If the value is missing
 * during {@link #remove}, then nothing happens.
 *
 * @hide
 * @param <T> type of the objects container stores.
 */
@android.ravenwood.annotation.RavenwoodKeepWholeClass
public class CopyOnWriteSparseArray<T> {
    private volatile SparseArray<T> mContainer = new SparseArray<>(0);
    private final boolean mDebug;
    private final String mTag;
    private final Object mLock = new Object();

    /**
     * Create instance without logging.
     */
    public CopyOnWriteSparseArray() {
        mDebug = false;
        mTag = null;
    }

    /**
     * Create instance with potentially enabling debug logging.
     * @param tag used for logging.
     * @param debug whether to enable debug logging.
     */
    public CopyOnWriteSparseArray(String tag, boolean debug) {
        mTag = tag;
        mDebug = debug;
    }

    /**
     * Updates key with the new value. This will create a copy of
     * the current container if value is missing from the container, otherwise it will update the
     * value.
     * <p>
     * Thread-safe.
     */
    public void put(int key, T value) {
        synchronized (mLock) {
            int index = mContainer.indexOfKey(key);
            if (index >= 0) {
                // No need to copy the whole array if the key is already present.
                // Just update the value.
                if (mDebug) {
                    Slog.d(mTag, "Updating existing key " + key + " in container " + value);
                }
                mContainer.setValueAt(index, value);
            } else {
                // Key is not present in the array, so copy the array while adding new key-value.
                if (mDebug) {
                    Slog.d(mTag, "Adding new key " + key + " in container " + value);
                }
                mContainer = cloneWithKey(mContainer, key, value);
            }
        }
    }

    /**
     * Removes key. If key present, this will create a copy of the
     * current container without the given key.
     * <p>
     * Thread-safe.
     */
    public void remove(int key) {
        synchronized (mLock) {
            if (!mContainer.contains(key)) {
                // No need to copy the array if the key is not found.
                if (mDebug) {
                    Slog.d(mTag, "Key " + key + " not found in container");
                }
            } else {
                // Key is present in the array, so copy the array without the key.
                if (mDebug) {
                    Slog.d(mTag, "Removing key " + key + " from container");
                }
                mContainer = cloneWithoutKey(mContainer, key);
            }
        }
    }

    /**
     * Clears the array by creating a new empty internal container.
     * <p>
     * Thread-safe.
     * @see SparseArray#clear()
     */
    public void clear() {
        synchronized (mLock) {
            mContainer = new SparseArray<>(0);
        }
    }

    /**
     * Returns value for the given key. If the value is not found, this will return null.
     * <p>
     * Thread-safe: Can be called from any thread without holding any lock.
     */
    @Nullable
    public T get(int key) {
        return mContainer.get(key);
    }

    /**
     * See {@link SparseArray#toString()} for more details.
     * <p>
     * Thread-safe.
     */
    @Override
    public String toString() {
        return mContainer.toString();
    }

    /**
     * Iterate through all elements of the container.
     * <p>
     * Thread-safe.
     * @param func function to be invoked for each of the values stored in the container,
     *             until the end of the container is reached OR func returns false.
     */
    public void forEach(BiFunction<Integer, T, Boolean> func) {
        SparseArray<T> container = mContainer;
        int count = container.size();
        for (int i = 0; i < count; i++) {
            if (!func.apply(container.keyAt(i), container.valueAt(i))) {
                break;
            }
        }
    }

    /**
     * Count of keys in the container.
     */
    public int size() {
        return mContainer.size();
    }

    /**
     * Returns the keys that match the given predicate.
     *
     * @param filter a predicate that takes key and value and returns {@code true} if the key
     * should be included in the returned array.
     */
    public int[] filteredKeys(BiFunction<Integer, T, Boolean> filter) {
        SparseArray<T> container = mContainer;
        int count = container.size();
        IntArray arr = new IntArray(count);
        for (int i = 0; i < count; i++) {
            var key = container.keyAt(i);
            if (filter.apply(key, container.valueAt(i))) {
                arr.add(key);
            }
        }
        return arr.toArray();
    }

    /**
     * Returns the underlying container. This container should not be modified outside of
     * {@link CopyOnWriteSparseArray}, but it can be read without locks.
     * <p>
     * Thread-safe.
     */
    @VisibleForTesting
    SparseArray<T> getArray() {
        return mContainer;
    }

    /**
     * Returns a copy of the array with the given key-value pair added. Key must not be present in
     * the array.
     */
    private static <T> SparseArray<T> cloneWithKey(SparseArray<T> source, int key, T value) {
        int count = source.size();
        SparseArray<T> target = new SparseArray<T>(count + 1);
        boolean keyAdded = false;
        for (int i = 0; i < count; i++) {
            int k = source.keyAt(i);
            if (!keyAdded && key < k) {
                keyAdded = true;
                target.append(key, value);
            }
            // key must not be present in source, thus k != key
            target.append(k, source.valueAt(i));
        }
        if (!keyAdded) {
            target.append(key, value);
        }
        return target;
    }

    /**
     * Returns a copy of the array without the given key. Key must be present in the array.
     */
    private static <T> SparseArray<T> cloneWithoutKey(SparseArray<T> source, int key) {
        final int count = source.size();
        var target = new SparseArray<T>(count - 1);
        for (int i = 0; i < count; i++) {
            int k = source.keyAt(i);
            if (k != key) {
                target.append(k, source.valueAt(i));
            }
        }
        return target;
    }
}
