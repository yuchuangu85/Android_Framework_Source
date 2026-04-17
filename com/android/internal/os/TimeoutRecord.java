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

package com.android.internal.os;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.AnrTypes;
import android.app.AnrTypes.AnrType;
import android.app.ApplicationExitInfo;
import android.app.ApplicationExitInfo.SubReason;
import android.content.Intent;
import android.os.SystemClock;
import android.util.Slog;

import com.android.internal.os.anr.AnrLatencyTracker;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * A timeout that has triggered on the system.
 *
 * @hide
 */
public class TimeoutRecord {
    /** Kind of timeout, e.g. BROADCAST_RECEIVER, etc. */
    @IntDef(value = {
            TimeoutKind.INPUT_DISPATCH_NO_FOCUSED_WINDOW,
            TimeoutKind.INPUT_DISPATCH_WINDOW_UNRESPONSIVE,
            TimeoutKind.BROADCAST_RECEIVER,
            TimeoutKind.SERVICE_START,
            TimeoutKind.SERVICE_EXEC,
            TimeoutKind.CONTENT_PROVIDER,
            TimeoutKind.APP_REGISTERED,
            TimeoutKind.SHORT_FGS_TIMEOUT,
            TimeoutKind.JOB_SERVICE,
            TimeoutKind.APP_START,
    })

    @Retention(RetentionPolicy.SOURCE)
    public @interface TimeoutKind {
        int INPUT_DISPATCH_NO_FOCUSED_WINDOW = 1;
        int INPUT_DISPATCH_WINDOW_UNRESPONSIVE = 2;
        int BROADCAST_RECEIVER = 3;
        int SERVICE_START = 4;
        int SERVICE_EXEC = 5;
        int CONTENT_PROVIDER = 6;
        int APP_REGISTERED = 7;
        int SHORT_FGS_TIMEOUT = 8;
        int JOB_SERVICE = 9;
        int APP_START = 10;
    }

    /** Kind of timeout, e.g. BROADCAST_RECEIVER, etc. */
    @TimeoutKind
    public final int mKind;

    /** Reason for the timeout. */
    public final String mReason;

    /** System uptime in millis when the timeout was triggered. */
    public final long mEndUptimeMillis;

    /**
     * Was the end timestamp taken right after the timeout triggered, before any potentially
     * expensive operations such as taking locks?
     */
    public final boolean mEndTakenBeforeLocks;

    /** Latency tracker associated with this instance. */
    public final AnrLatencyTracker mLatencyTracker;

    /** A handle to the timer that expired.  A value of null means "no timer". */
    private Object mExpiredTimer;

    private static final String TAG = "TimeoutRecord";

    private TimeoutRecord(@TimeoutKind int kind, @NonNull String reason, long endUptimeMillis,
            boolean endTakenBeforeLocks) {
        this.mKind = kind;
        this.mReason = reason;
        this.mEndUptimeMillis = endUptimeMillis;
        this.mEndTakenBeforeLocks = endTakenBeforeLocks;
        this.mLatencyTracker = new AnrLatencyTracker(kind, endUptimeMillis);
        this.mExpiredTimer = null;
    }

    private static TimeoutRecord endingNow(@TimeoutKind int kind, String reason) {
        long endUptimeMillis = SystemClock.uptimeMillis();
        return new TimeoutRecord(kind, reason, endUptimeMillis, /* endTakenBeforeLocks */ true);
    }

    private static TimeoutRecord endingApproximatelyNow(@TimeoutKind int kind, String reason) {
        long endUptimeMillis = SystemClock.uptimeMillis();
        return new TimeoutRecord(kind, reason, endUptimeMillis, /* endTakenBeforeLocks */ false);
    }

    /** Record for a broadcast receiver timeout. */
    @NonNull
    public static TimeoutRecord forBroadcastReceiver(@NonNull Intent intent,
            @Nullable String packageName, @Nullable String className) {
        final Intent logIntent = createLogIntentForBroadcast(intent, packageName, className);
        return forBroadcastReceiver(logIntent);
    }

    /** Record for a broadcast receiver timeout. */
    @NonNull
    public static TimeoutRecord forBroadcastReceiver(@NonNull Intent intent) {
        final StringBuilder reason = new StringBuilder("Broadcast of ");
        intent.toString(reason);
        return TimeoutRecord.endingNow(TimeoutKind.BROADCAST_RECEIVER, reason.toString());
    }

    /** Record for a broadcast receiver timeout. */
    @NonNull
    public static TimeoutRecord forBroadcastReceiver(@NonNull Intent intent,
            long timeoutDurationMs) {
        final StringBuilder reason = new StringBuilder("Broadcast of ");
        intent.toString(reason);
        reason.append(", waited ");
        reason.append(timeoutDurationMs);
        reason.append("ms");
        return TimeoutRecord.endingNow(TimeoutKind.BROADCAST_RECEIVER, reason.toString());
    }

    /** Record for an input dispatch no focused window timeout */
    @NonNull
    public static TimeoutRecord forInputDispatchNoFocusedWindow(@NonNull String reason) {
        return TimeoutRecord.endingNow(TimeoutKind.INPUT_DISPATCH_NO_FOCUSED_WINDOW, reason);
    }

    /** Record for an input dispatch window unresponsive timeout. */
    @NonNull
    public static TimeoutRecord forInputDispatchWindowUnresponsive(@NonNull String reason) {
        return TimeoutRecord.endingNow(TimeoutKind.INPUT_DISPATCH_WINDOW_UNRESPONSIVE, reason);
    }

    /** Record for a service exec timeout. */
    @NonNull
    public static TimeoutRecord forServiceExec(@NonNull String shortInstanceName,
            long timeoutDurationMs) {
        String reason =
                "executing service " + shortInstanceName + ", waited "
                        + timeoutDurationMs + "ms";
        return TimeoutRecord.endingNow(TimeoutKind.SERVICE_EXEC, reason);
    }

    /** Record for a service start timeout. */
    @NonNull
    public static TimeoutRecord forServiceStartWithEndTime(@NonNull String reason,
            long endUptimeMillis) {
        return new TimeoutRecord(TimeoutKind.SERVICE_START, reason,
                endUptimeMillis, /* endTakenBeforeLocks */ true);
    }

    /** Record for a content provider timeout. */
    @NonNull
    public static TimeoutRecord forContentProvider(@NonNull String reason) {
        return TimeoutRecord.endingApproximatelyNow(TimeoutKind.CONTENT_PROVIDER, reason);
    }

    /** Record for an app registered timeout. */
    @NonNull
    public static TimeoutRecord forApp(@NonNull String reason) {
        return TimeoutRecord.endingApproximatelyNow(TimeoutKind.APP_REGISTERED, reason);
    }

    /** Record for a "short foreground service" timeout. */
    @NonNull
    public static TimeoutRecord forShortFgsTimeout(String reason) {
        return TimeoutRecord.endingNow(TimeoutKind.SHORT_FGS_TIMEOUT, reason);
    }

    /** Record for a job related timeout. */
    @NonNull
    public static TimeoutRecord forJobService(String reason) {
        return TimeoutRecord.endingNow(TimeoutKind.JOB_SERVICE, reason);
    }

    /** Record for app startup timeout. */
    @NonNull
    public static TimeoutRecord forAppStart(String reason) {
        return TimeoutRecord.endingNow(TimeoutKind.APP_START, reason);
    }

    /**
     * Record the timer that expired. The argument is an opaque handle.
     */
    @NonNull
    public TimeoutRecord setExpiredTimer(@Nullable Object handle) {
        mExpiredTimer = handle;
        return this;
    }

    /**
     * Return the expired timer.
     */
    @Nullable
    public Object getExpiredTimer() {
        return mExpiredTimer;
    }


    /**
     * Maps a {@link TimeoutRecord.TimeoutKind} to its corresponding
     * {@link ApplicationExitInfo.SubReason} for ANR (Application Not Responding)
     * events.
     *
     * @return The {@link ApplicationExitInfo.SubReason} corresponding to the
     *         internal {@code mKind}. Returns {@link ApplicationExitInfo#SUBREASON_UNKNOWN}
     *         if the {@code mKind} does not match any known ANR subreason.
     */
    public @SubReason int getAppExitInfoAnrSubreason() {
        return switch (mKind) {
            case TimeoutRecord.TimeoutKind.APP_REGISTERED ->
                    ApplicationExitInfo.SUBREASON_ANR_TYPE_APP_TRIGGERED;
            case TimeoutRecord.TimeoutKind.APP_START ->
                    ApplicationExitInfo.SUBREASON_ANR_TYPE_BIND_APPLICATION;
            case TimeoutRecord.TimeoutKind.BROADCAST_RECEIVER ->
                    ApplicationExitInfo.SUBREASON_ANR_TYPE_BROADCAST_OF_INTENT;
            case TimeoutRecord.TimeoutKind.CONTENT_PROVIDER ->
                    ApplicationExitInfo.SUBREASON_ANR_TYPE_CONTENT_PROVIDER_NOT_RESPONDING;
            case TimeoutRecord.TimeoutKind.SERVICE_EXEC ->
                    ApplicationExitInfo.SUBREASON_ANR_TYPE_EXECUTING_SERVICE;
            case TimeoutRecord.TimeoutKind.SHORT_FGS_TIMEOUT ->
                    ApplicationExitInfo.SUBREASON_ANR_TYPE_FOREGROUND_SHORT_SERVICE_TIMEOUT;
            case TimeoutRecord.TimeoutKind.INPUT_DISPATCH_WINDOW_UNRESPONSIVE ->
                    ApplicationExitInfo.SUBREASON_ANR_TYPE_INPUT_DISPATCHING_TIMEOUT;
            case TimeoutRecord.TimeoutKind.INPUT_DISPATCH_NO_FOCUSED_WINDOW -> ApplicationExitInfo
                    .SUBREASON_ANR_TYPE_INPUT_DISPATCHING_TIMEOUT_NO_FOCUSED_WINDOW;
            case TimeoutRecord.TimeoutKind.JOB_SERVICE ->
                    ApplicationExitInfo.SUBREASON_ANR_TYPE_JOB_SERVICE_START;
            case TimeoutRecord.TimeoutKind.SERVICE_START ->
                    ApplicationExitInfo.SUBREASON_ANR_TYPE_START_FOREGROUND_SERVICE;
            default -> {
                Slog.e(TAG, "Unknown TimeoutKind: " + mKind);
                yield ApplicationExitInfo.SUBREASON_UNKNOWN;
            }
        };
    }

    /**
     * Maps a {@link TimeoutRecord.TimeoutKind} to its corresponding {@link AnrTypes.AnrType} for
     * ANR events.
     *
     * @return The {@link AnrTypes.AnrType} corresponding to the internal {@code mKind}. Returns
     *     {@link AnrTypes#ANR_TYPE_OTHER} if the {@code mKind} does not match any known ANR type.
     */
    public @AnrType int getAnrType() {
        return switch (mKind) {
            case TimeoutKind.INPUT_DISPATCH_NO_FOCUSED_WINDOW ->
                    AnrTypes.ANR_TYPE_INPUT_DISPATCH_NO_FOCUSED_WINDOW;
            case TimeoutKind.INPUT_DISPATCH_WINDOW_UNRESPONSIVE -> AnrTypes.ANR_TYPE_INPUT_DISPATCH;
            case TimeoutKind.BROADCAST_RECEIVER -> AnrTypes.ANR_TYPE_BROADCAST_OF_INTENT;
            case TimeoutKind.SERVICE_START -> AnrTypes.ANR_TYPE_START_FOREGROUND_SERVICE;
            case TimeoutKind.SERVICE_EXEC -> AnrTypes.ANR_TYPE_EXECUTE_SERVICE;
            case TimeoutKind.CONTENT_PROVIDER -> AnrTypes.ANR_TYPE_CONTENT_PROVIDER_NOT_RESPONDING;
            case TimeoutKind.APP_REGISTERED -> AnrTypes.ANR_TYPE_APP_TRIGGERED;
            case TimeoutKind.SHORT_FGS_TIMEOUT ->
                    AnrTypes.ANR_TYPE_FOREGROUND_SHORT_SERVICE_TIMEOUT;
            case TimeoutKind.JOB_SERVICE -> AnrTypes.ANR_TYPE_JOB_SERVICE_START;
            case TimeoutKind.APP_START -> AnrTypes.ANR_TYPE_APPLICATION_START;
            default -> {
                Slog.e(TAG, "Unknown TimeoutKind: " + mKind);
                yield AnrTypes.ANR_TYPE_OTHER;
            }
        };
    }

    /**
     * Creates a new {@link Intent} object with the provided {@link Intent} based on the supplied
     * package and class names.
     */
    public static Intent createLogIntentForBroadcast(
            @NonNull Intent intent, @Nullable String packageName, @Nullable String className) {
        if (packageName == null) {
            return intent;
        }

        final Intent resultIntent = new Intent(intent);

        if (className == null) {
            resultIntent.setPackage(packageName);
        } else {
            resultIntent.setClassName(packageName, className);
        }
        return resultIntent;
    }
}
