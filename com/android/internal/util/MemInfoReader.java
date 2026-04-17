/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.internal.util;

import android.compat.annotation.UnsupportedAppUsage;
import android.os.Build;
import android.os.Debug;
import android.os.StrictMode;

public final class MemInfoReader {
    final long[] mInfos = new long[Debug.MEMINFO_COUNT];

    @UnsupportedAppUsage
    public MemInfoReader() {
    }

    @UnsupportedAppUsage
    public void readMemInfo() {
        // Permit disk reads here, as /proc/meminfo isn't really "on
        // disk" and should be fast.  TODO: make BlockGuard ignore
        // /proc/ and /sys/ files perhaps?
        StrictMode.ThreadPolicy savedPolicy = StrictMode.allowThreadDiskReads();
        try {
            Debug.getMemInfo(mInfos);
        } finally {
            StrictMode.setThreadPolicy(savedPolicy);
        }
    }

    /**
     * Total amount of RAM available to the kernel.
     */
    @UnsupportedAppUsage
    public long getTotalSize() {
        return mInfos[Debug.MEMINFO_TOTAL] * 1024;
    }

    /**
     * Amount of RAM that is not being used for anything.
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public long getFreeSize() {
        return mInfos[Debug.MEMINFO_FREE] * 1024;
    }

    /**
     * Amount of RAM that the kernel is being used for caches, not counting caches
     * that are mapped in to processes.
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public long getCachedSize() {
        return getCachedSizeKb() * 1024;
    }

    /**
     * Amount of RAM that is in use by the kernel for actual allocations.
     */
    public long getKernelUsedSize() {
        return getKernelUsedSizeKb() * 1024;
    }

    /**
     * Total amount of RAM available to the kernel.
     */
    public long getTotalSizeKb() {
        return mInfos[Debug.MEMINFO_TOTAL];
    }

    /**
     * Amount of RAM that is not being used for anything.
     */
    public long getFreeSizeKb() {
        return mInfos[Debug.MEMINFO_FREE];
    }

    /**
     * Amount of RAM that used by shared memory (shmem) and tmpfs
     */
    public long getShmemSizeKb() {
        return mInfos[Debug.MEMINFO_SHMEM];
    }

    /**
     * Amount of RAM that the kernel is being used for caches, not counting caches
     * that are mapped in to processes.
     */
    public long getCachedSizeKb() {
        long kReclaimable = mInfos[Debug.MEMINFO_KRECLAIMABLE];

        // Note: MEMINFO_KRECLAIMABLE includes MEMINFO_SLAB_RECLAIMABLE and ION pools.
        // Fall back to using MEMINFO_SLAB_RECLAIMABLE in case of older kernels that do
        // not include KReclaimable meminfo field.
        if (kReclaimable == 0) {
            kReclaimable = mInfos[Debug.MEMINFO_SLAB_RECLAIMABLE];
        }
        return mInfos[Debug.MEMINFO_BUFFERS] + kReclaimable
                + mInfos[Debug.MEMINFO_CACHED] - mInfos[Debug.MEMINFO_MAPPED]
                + mInfos[Debug.MEMINFO_SWAP_CACHED];
    }

    private long getGpuKernelUsedSizeKb() {
        long kernelUsed = 0;

        if (Debug.getGpuTotalUsageKb() >= 0) {
            final long gpuPrivateUsage = Debug.getGpuPrivateMemoryKb();
            if (gpuPrivateUsage >= 0) {
                kernelUsed += gpuPrivateUsage;
            }
        }

        return kernelUsed;
    }

    /**
     * Amount of RAM that is in use by the kernel for actual allocations.
     *
     * While this should also include the amount of memory allocated by kernel
     * drivers via DMA-BUF, that calculation is expensive (can take up to
     * 1 second), which would degrade the performance of the callers of this
     * function. Therefore, it is up to the callers of this function to
     * supplement this value with the amount of kernel memory allocated via
     * DMA-BUF if necessary.
     */
    public long getKernelUsedSizeKb() {
        long size = mInfos[Debug.MEMINFO_SHMEM] + mInfos[Debug.MEMINFO_SLAB_UNRECLAIMABLE]
                + mInfos[Debug.MEMINFO_VM_ALLOC_USED] + mInfos[Debug.MEMINFO_PAGE_TABLES]
                + mInfos[Debug.MEMINFO_SEC_PAGE_TABLES] + mInfos[Debug.MEMINFO_PERCPU];
        if (!Debug.isVmapStack()) {
            size += mInfos[Debug.MEMINFO_KERNEL_STACK];
        }

        // CMA memory can be in one of the following four states:
        //
        // 1. Free, in which case it is accounted for as part of MemFree, which
        //    is already considered in the lostRAM calculation below.
        //
        // 2. Allocated as part of a userspace allocation, in which case it is
        //    already accounted for in the total PSS value that is computed for
        //    lost RAM calculations.
        //
        // 3. Allocated for storing compressed memory (ZRAM) on Android kernels.
        //    This is accounted for by calculating the amount of memory ZRAM
        //    consumes and including it in the lost RAM calculations.
        //
        // 4. Allocated by a kernel driver, in which case, it is currently not
        //    attributed to any term that is used in the lost RAM calculation.
        //    Since the allocations come from a kernel driver, add it to
        //    kernelUsed.
        final long kernelCma = Debug.getKernelCmaUsageKb();
        if(kernelCma > 0) {
            size += kernelCma;
        }

        final long kernelGpu = getGpuKernelUsedSizeKb();
        if (kernelGpu > 0) {
            size += kernelGpu;
        }

        return size;
    }

    public long getSwapTotalSizeKb() {
        return mInfos[Debug.MEMINFO_SWAP_TOTAL];
    }

    public long getSwapFreeSizeKb() {
        return mInfos[Debug.MEMINFO_SWAP_FREE];
    }

    public long getZramTotalSizeKb() {
        return mInfos[Debug.MEMINFO_ZRAM_TOTAL];
    }

    @UnsupportedAppUsage
    public long[] getRawInfo() {
        return mInfos;
    }
}
