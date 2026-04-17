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

/**
 * Provides a Java interface to the native eBPF-based CPU cycle and power attribution
 * functionality for x86 platforms. This class exposes methods to start and check the status of
 * the eBPF-based tracking and to read the attributed power consumption data per UID.
 */
public class KernelCpuCyclePerUidBpf {
    private static final String TAG = "KernelCpuCyclePerUidBpf";
    private static boolean sLibraryLoaded = false;

    static {
        try {
            System.loadLibrary("cycleperuid");
            sLibraryLoaded = true;
        } catch (Throwable e) {
            // Log error but don't crash system_server
            android.util.Slog.e(TAG, "Failed to load library cycleperuid", e);
        }
    }

    private KernelCpuCyclePerUidBpf() {}

    /** Starts CPU tracking using eBPF. */
    public static boolean startTracking() {
        if (!sLibraryLoaded) {
            android.util.Slog.w(TAG, "startTracking skipped: library not loaded");
            return false;
        }
        return startTrackingInternal();
    }

    /** Stops CPU tracking using eBPF and cleans up resources. */
    public static void stopTracking() {
        if (!sLibraryLoaded) {
            android.util.Slog.w(TAG, "stopTracking skipped: library not loaded");
            return;
        }
        stopTrackingInternal();
    }

    /** Returns whether CPU tracking using eBPF is supported and RAPL is available. */
    public static boolean isSupported() {
        if (!sLibraryLoaded) {
            android.util.Slog.w(TAG, "isSupported skipped: library not loaded");
            return false;
        }
        return isSupportedInternal();
    }

    private static native boolean isSupportedInternal();

    private static native boolean startTrackingInternal();
    private static native void stopTrackingInternal();

    /** Reads the current total package energy consumption from the RAPL interface. */
    public static long readPackagePower() {
        if (!sLibraryLoaded) {
            android.util.Slog.w(TAG, "readPackagePower skipped: library not loaded");
            return 0;
        }
        return readPackagePowerInternal();
    }
    private static native long readPackagePowerInternal();

    /** Reads the last recorded TSC value from the BPF map. */
    public static long readLastRecordedCycle() {
        if (!sLibraryLoaded) {
            android.util.Slog.w(TAG, "readLastRecordedCycle skipped: library not loaded");
            return 0;
        }
        return readLastRecordedCycleInternal();
    }
    private static native long readLastRecordedCycleInternal();

    /** Reads the accumulated desync count from the BPF map. */
    public static long readDesyncCount() {
        if (!sLibraryLoaded) {
            android.util.Slog.w(TAG, "readDesyncCount skipped: library not loaded");
            return 0;
        }
        return readDesyncCountInternal();
    }
    private static native long readDesyncCountInternal();

    /** Reads the accumulated CPU cycles for each UID from the BPF map. */
    public static long[] readUidCpuCycles() {
        if (!sLibraryLoaded) {
            android.util.Slog.w(TAG, "readUidCpuCycles skipped: library not loaded");
            return new long[0];
        }
        return readUidCpuCyclesInternal();
    }
    private static native long[] readUidCpuCyclesInternal();

    /** Calculates the energy delta consumed by each UID since the last call. */
    public static long[] readUidPowerDelta() {
        if (!sLibraryLoaded) {
            android.util.Slog.w(TAG, "readUidPowerDelta skipped: library not loaded");
            return new long[0];
        }
        return readUidPowerDeltaInternal();
    }
    private static native long[] readUidPowerDeltaInternal();
}
