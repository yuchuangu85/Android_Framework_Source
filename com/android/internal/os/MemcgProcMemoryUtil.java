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
package com.android.internal.os;

import static android.os.Process.PROC_OUT_LONG;

import android.os.Process;
import android.os.UserHandle;
import android.util.Log;

import java.nio.file.Path;
import java.nio.file.Paths;

public final class MemcgProcMemoryUtil {

    private static final String TAG = "MemcgProcMemoryUtil";

    private static final int[] MEMCG_MEMORY_FORMAT = new int[] {PROC_OUT_LONG};
    private static final String CGROUP_ROOT = "/sys/fs/cgroup";
    private static final String PROC_ROOT = "/proc/";

    // Define constants for the indices for readability
    private static final int MEMCG_STAT_ANON_IDX = 0;
    private static final int MEMCG_STAT_FILE_IDX = 1;
    private static final int MEMCG_STAT_KERNEL_IDX = 2;
    private static final int MEMCG_STAT_SHMEM_IDX = 3;
    private static final int MEMCG_STAT_FILE_MAPPED_IDX = 4;
    private static final int MEMCG_STAT_PGMAJFAULT_IDX = 5;
    private static final int MEMCG_STAT_PGSCAN_DIRECT_IDX = 6;
    private static final String[] MEMCG_STAT_FIELDS = new String[] {
            "anon ",
            "file ",
            "kernel ",
            "shmem ",
            "file_mapped ",
            "pgmajfault ",
            "pgscan_direct "
    };

    private MemcgProcMemoryUtil() {}

     /**
      * Reads memcg accounting of memory stats of a process.
      *
      * Returns values of memory.current, memory.swap in bytes or null if not available.
      */
    public static MemcgMemorySnapshot readMemcgMemorySnapshot(int uid, int pid) {
        String cgroupPath = getCgroupPath(uid, pid);
        if (cgroupPath == null) {
            return null;
        }
        return readMemcgMemorySnapshot(cgroupPath);
    }

    /**
      *
      * Reads memcg accounting of memory hwm stats of a process.
      *
      * Returns values of memory.current.peak, memory.swap.peak in bytes or null if not available.
     */
    public static MemcgHighWaterMarkMemorySnapshot readHighWaterMarkMemorySnapshot(
            int uid, int pid) {
        String cgroupPath = getCgroupPath(uid, pid);
        if (cgroupPath == null) {
            return null;
        }
        return readMemcgHighWaterMarkMemorySnapshot(cgroupPath);
    }

    /**
     * Reads memcgv2 stats file for key vales for a process.
     *
     * Returns MemcgMemoryStatSnapshot
     */
    public static MemcgMemoryStatSnapshot readMemcgProcessStatSnapshot(
            int uid, int pid) {
        String cgroupPath = getCgroupPath(uid, pid);
        if (cgroupPath == null) {
            return null;
        }
        return readMemcgStatSnapshot(cgroupPath);
    }

    /**
     * Gets the cgroup v2 path for a given user ID (uid) and process ID (pid).
     *
     * @param uid The user ID.
     * @param pid The process ID.
     * @return The cgroup v2 path string.
     */
    private static String getCgroupPath(int uid, int pid) {
        StringBuilder sb = new StringBuilder();

        if (UserHandle.isCore(uid)) sb.append("system/");
        else                        sb.append("apps/");

        sb.append("uid_").append(Integer.toString(uid));
        sb.append("/pid_").append(Integer.toString(pid));
        sb.append("/");

        return sb.toString();
    }

    private static MemcgHighWaterMarkMemorySnapshot
            readMemcgHighWaterMarkMemorySnapshot(String cgroupPath) {
        Path fullMemcgPath = Paths.get(CGROUP_ROOT, cgroupPath);

        final MemcgHighWaterMarkMemorySnapshot snapshot = new MemcgHighWaterMarkMemorySnapshot();
        Long memoryPeak = readSingleValueFromMemcgFile(
                fullMemcgPath,
                "memory.peak",
                MEMCG_MEMORY_FORMAT
        );
        if (memoryPeak == null) {
            return null;
        }

        Long swapMemoryPeak = readSingleValueFromMemcgFile(
                fullMemcgPath,
                "memory.swap.peak",
                MEMCG_MEMORY_FORMAT
        );
        if (swapMemoryPeak == null) {
            return null;
        }

        snapshot.memcgMemoryPeakInBytes = memoryPeak;
        snapshot.memcgSwapMemoryPeakInBytes = swapMemoryPeak;
        return snapshot;
    }

    private static MemcgMemorySnapshot readMemcgMemorySnapshot(String cgroupPath) {
        Path fullMemcgPath = Paths.get(CGROUP_ROOT, cgroupPath);

        final MemcgMemorySnapshot snapshot = new MemcgMemorySnapshot();

        long[] currentMemoryOutput = new long[1];
        String memoryCurrentPath = fullMemcgPath.resolve("memory.current").toString();
        if (Process.readProcFile(
                memoryCurrentPath,
                MEMCG_MEMORY_FORMAT,
                null,
                currentMemoryOutput,
                null
        )) {
            snapshot.memcgMemoryInBytes = currentMemoryOutput[0];
        } else {
            Log.d(TAG, "Failed to read memory.current for " + cgroupPath);
            return null;
        }

        long[] currentSwapMemoryOutput = new long[1];
        String memorySwapPath =
                fullMemcgPath.resolve("memory.swap.current").toString();
        if (Process.readProcFile(
                memorySwapPath,
                MEMCG_MEMORY_FORMAT,
                null,
                currentSwapMemoryOutput,
                null
        )) {
            snapshot.memcgSwapMemoryInBytes = currentSwapMemoryOutput[0];
        } else {
            Log.d(TAG, "Failed to read memory.current for " + cgroupPath);
            return null;
        }
        return snapshot;
    }

    private static MemcgMemoryStatSnapshot readMemcgStatSnapshot(String cgroupPath) {
        Path fullMemcgPath = Paths.get(CGROUP_ROOT, cgroupPath);
        Path statsFilePath = Paths.get(CGROUP_ROOT, cgroupPath, "memory.stat");
        final MemcgMemoryStatSnapshot snapshot = new MemcgMemoryStatSnapshot();

        long[] outStats = new long[MEMCG_STAT_FIELDS.length];
        Process.readProcLines(statsFilePath.toString(), MEMCG_STAT_FIELDS, outStats);

        try {
            snapshot.anonInKiloBytes = Math.toIntExact(outStats[MEMCG_STAT_ANON_IDX] / 1024);
            snapshot.fileInKiloBytes = Math.toIntExact(outStats[MEMCG_STAT_FILE_IDX] / 1024);
            snapshot.totalKernelInKiloBytes =
                Math.toIntExact(outStats[MEMCG_STAT_KERNEL_IDX] / 1024);
            snapshot.shmemInKiloBytes = Math.toIntExact(outStats[MEMCG_STAT_SHMEM_IDX] / 1024);
            snapshot.fileMappedInKiloBytes =
                Math.toIntExact(outStats[MEMCG_STAT_FILE_MAPPED_IDX] / 1024);
            snapshot.majorPageFaultCount = Math.toIntExact(outStats[MEMCG_STAT_PGMAJFAULT_IDX]);
            snapshot.directPageScanCount = Math.toIntExact(outStats[MEMCG_STAT_PGSCAN_DIRECT_IDX]);
        } catch (NumberFormatException e) {
            return null;
        }

        long[] currentSwapMemoryOutput = new long[1];
        String memorySwapPath =
                fullMemcgPath.resolve("memory.swap.current").toString();
        if (Process.readProcFile(
                memorySwapPath,
                MEMCG_MEMORY_FORMAT,
                null,
                currentSwapMemoryOutput,
                null
        )) {
            snapshot.memorySwapInKiloBytes =
                   Math.toIntExact(currentSwapMemoryOutput[0] / 1024);
        } else {
            Log.d(TAG, "Failed to read memory.swap.current for " + cgroupPath);
            return null;
        }

        return snapshot;
    }

    private static Long readSingleValueFromMemcgFile(
            Path fullMemcgPath, String fileKey, int[] fileFormat) {
        long[] memoryValue = new long[1];
        String memoryNodePath = fullMemcgPath.resolve(fileKey).toString();
        if (Process.readProcFile(
                memoryNodePath,
                fileFormat,
                null,
                memoryValue,
                null
        )) {
            return memoryValue[0];
        } else {
            return null;
        }
    }

    public static final class MemcgHighWaterMarkMemorySnapshot {
        public long memcgMemoryPeakInBytes;
        public long memcgSwapMemoryPeakInBytes;
    }

    public static final class MemcgMemorySnapshot {
        public long memcgMemoryInBytes;
        public long memcgSwapMemoryInBytes;
    }

    public static final class MemcgMemoryStatSnapshot {
        public int anonInKiloBytes;
        public int fileInKiloBytes;
        public int totalKernelInKiloBytes;
        public int shmemInKiloBytes;
        public int fileMappedInKiloBytes;
        public int memorySwapInKiloBytes;
        public int majorPageFaultCount;
        public int directPageScanCount;
    }
}
