/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.net.module.util;

import android.os.Build;
import android.system.ErrnoException;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import dalvik.annotation.optimization.CriticalNative;

 /**
 *
 * Generic bitmap class for use with BPF programs. Corresponds to a BpfMap
 * array type with key->int and value->uint64_t defined in the bpf program.
 *
 */
@RequiresApi(Build.VERSION_CODES.S)
public class BpfBitmap {
    static {
        System.loadLibrary(JniUtil.getJniLibraryName(BpfBitmap.class.getPackage()));
    }

    private final BpfMap<Struct.S32, Struct.S64> mBpfMap;
    private final int mMapFd;

    /**
     * Create a BpfBitmap map wrapper with "path" of filesystem.
     *
     * @param path The path of the BPF map.
     */
    public BpfBitmap(@NonNull String path) throws ErrnoException {
        mBpfMap = new BpfMap<>(path, Struct.S32.class, Struct.S64.class);
        mMapFd = mBpfMap.getFd();
    }

    // Returns > 0 if bit is set, 0 if not set, < 0 on error (negative errno).
    @CriticalNative
    private static native int nativeGet(int fd, int index);

    // Returns 0 on success, < 0 on error (negative errno).
    @CriticalNative
    private static native int nativeSet(int fd, int index, boolean set);

    /**
     * Retrieves the bit for the given index in the bitmap.
     *
     * @param index Position in bitmap.
     */
    public boolean get(int index) throws ErrnoException  {
        if (index < 0) return false;

        final int ret = nativeGet(mMapFd, index);
        if (ret < 0) {
            throw new ErrnoException("nativeGet", -ret);
        }
        return ret > 0;
    }

    /**
     * Change the specified index in the bitmap to set value.
     *
     * @param index Position to (un)set in bitmap.
     * @param set Boolean indicating to set or unset index.
     */
    public void set(int index, boolean set) throws ErrnoException {
        if (index < 0) throw new IllegalArgumentException("Index out of bounds.");

        final int ret = nativeSet(mMapFd, index, set);
        if (ret < 0) {
            throw new ErrnoException("nativeSet", -ret);
        }
    }

    /**
     * Set the specified index in the bitmap.
     *
     * @param index Position to set in bitmap.
     */
    public void set(int index) throws ErrnoException {
        set(index, true);
    }

    /**
     * Unset the specified index in the bitmap.
     *
     * @param index Position to unset in bitmap.
     */
    public void unset(int index) throws ErrnoException {
        set(index, false);
    }

    /**
     * Clears the map. The map may already be empty.
     *
     * @throws ErrnoException if updating entry to 0 fails.
     */
    public void clear() throws ErrnoException {
        mBpfMap.forEach((key, value) -> {
            mBpfMap.updateEntry(key, new Struct.S64(0));
        });
    }

    /**
     * Checks if all bitmap values are 0.
     */
    public boolean isEmpty() throws ErrnoException {
        Struct.S32 key = mBpfMap.getFirstKey();
        while (key != null) {
            Struct.S64 val = mBpfMap.getValue(key);
            if (val != null && val.val != 0) {
                return false;
            }
            key = mBpfMap.getNextKey(key);
        }
        return true;
    }
}
