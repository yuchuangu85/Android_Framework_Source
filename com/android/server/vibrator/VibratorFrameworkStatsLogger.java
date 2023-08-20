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

package com.android.server.vibrator;

import android.os.Handler;
import android.os.SystemClock;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.FrameworkStatsLog;

import java.util.ArrayDeque;
import java.util.Queue;

/** Helper class for async write of atoms to {@link FrameworkStatsLog} using a given Handler. */
public class VibratorFrameworkStatsLogger {
    private static final String TAG = "VibratorFrameworkStatsLogger";

    // VibrationReported pushed atom needs to be throttled to at most one every 10ms.
    private static final int VIBRATION_REPORTED_MIN_INTERVAL_MILLIS = 10;
    // We accumulate events that should take 3s to write and drop excessive metrics.
    private static final int VIBRATION_REPORTED_MAX_QUEUE_SIZE = 300;
    // Warning about dropping entries after this amount of atoms were dropped by the throttle.
    private static final int VIBRATION_REPORTED_WARNING_QUEUE_SIZE = 200;

    private final Object mLock = new Object();
    private final Handler mHandler;
    private final long mVibrationReportedLogIntervalMillis;
    private final long mVibrationReportedQueueMaxSize;
    private final Runnable mConsumeVibrationStatsQueueRunnable =
            () -> writeVibrationReportedFromQueue();

    @GuardedBy("mLock")
    private long mLastVibrationReportedLogUptime;
    @GuardedBy("mLock")
    private Queue<VibrationStats.StatsInfo> mVibrationStatsQueue = new ArrayDeque<>();

    VibratorFrameworkStatsLogger(Handler handler) {
        this(handler, VIBRATION_REPORTED_MIN_INTERVAL_MILLIS, VIBRATION_REPORTED_MAX_QUEUE_SIZE);
    }

    @VisibleForTesting
    VibratorFrameworkStatsLogger(Handler handler, int vibrationReportedLogIntervalMillis,
            int vibrationReportedQueueMaxSize) {
        mHandler = handler;
        mVibrationReportedLogIntervalMillis = vibrationReportedLogIntervalMillis;
        mVibrationReportedQueueMaxSize = vibrationReportedQueueMaxSize;
    }

    /** Writes {@link FrameworkStatsLog#VIBRATOR_STATE_CHANGED} for state ON. */
    public void writeVibratorStateOnAsync(int uid, long duration) {
        mHandler.post(
                () -> FrameworkStatsLog.write_non_chained(
                        FrameworkStatsLog.VIBRATOR_STATE_CHANGED, uid, null,
                        FrameworkStatsLog.VIBRATOR_STATE_CHANGED__STATE__ON, duration));
    }

    /** Writes {@link FrameworkStatsLog#VIBRATOR_STATE_CHANGED} for state OFF. */
    public void writeVibratorStateOffAsync(int uid) {
        mHandler.post(
                () -> FrameworkStatsLog.write_non_chained(
                        FrameworkStatsLog.VIBRATOR_STATE_CHANGED, uid, null,
                        FrameworkStatsLog.VIBRATOR_STATE_CHANGED__STATE__OFF,
                        /* duration= */ 0));
    }

    /**
     *  Writes {@link FrameworkStatsLog#VIBRATION_REPORTED} for given vibration.
     *
     *  <p>This atom is throttled to be pushed once every 10ms, so this logger can keep a queue of
     *  {@link VibrationStats.StatsInfo} entries to slowly write to statsd.
     */
    public void writeVibrationReportedAsync(VibrationStats.StatsInfo metrics) {
        boolean needsScheduling;
        long scheduleDelayMs;
        int queueSize;

        synchronized (mLock) {
            queueSize = mVibrationStatsQueue.size();
            needsScheduling = (queueSize == 0);

            if (queueSize < mVibrationReportedQueueMaxSize) {
                mVibrationStatsQueue.offer(metrics);
            }

            long nextLogUptime =
                    mLastVibrationReportedLogUptime + mVibrationReportedLogIntervalMillis;
            scheduleDelayMs = Math.max(0, nextLogUptime - SystemClock.uptimeMillis());
        }

        if ((queueSize + 1) == VIBRATION_REPORTED_WARNING_QUEUE_SIZE) {
            Slog.w(TAG, " Approaching vibration metrics queue limit, events might be dropped.");
        }

        if (needsScheduling) {
            mHandler.postDelayed(mConsumeVibrationStatsQueueRunnable, scheduleDelayMs);
        }
    }

    /** Writes next {@link FrameworkStatsLog#VIBRATION_REPORTED} from the queue. */
    private void writeVibrationReportedFromQueue() {
        boolean needsScheduling;
        VibrationStats.StatsInfo stats;

        synchronized (mLock) {
            stats = mVibrationStatsQueue.poll();
            needsScheduling = !mVibrationStatsQueue.isEmpty();

            if (stats != null) {
                mLastVibrationReportedLogUptime = SystemClock.uptimeMillis();
            }
        }

        if (stats == null) {
            Slog.w(TAG, "Unexpected vibration metric flush with empty queue. Ignoring.");
        } else {
            stats.writeVibrationReported();
        }

        if (needsScheduling) {
            mHandler.postDelayed(mConsumeVibrationStatsQueueRunnable,
                    mVibrationReportedLogIntervalMillis);
        }
    }
}
