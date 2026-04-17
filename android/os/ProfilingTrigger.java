/*
 * Copyright (C) 2024 The Android Open Source Project
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
package android.os;

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.os.profiling.Flags;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/** Encapsulates a single profiling trigger. */
@FlaggedApi(Flags.FLAG_SYSTEM_TRIGGERED_PROFILING_NEW)
public final class ProfilingTrigger {

    // LINT.IfChange(trigger_types)
    /** No trigger. Used in {@link ProfilingResult} for non trigger caused results. */
    public static final int TRIGGER_TYPE_NONE = 0;

    /**
     * Trigger occurs after {@link android.app.Activity#reportFullyDrawn} is called for a cold
     * start.
     *
     * <p>System will provide a snapshot of a running system trace in response to this trigger.
     */
    public static final int TRIGGER_TYPE_APP_FULLY_DRAWN = 1;

    /**
     * Trigger occurs after an ANR has been identified, but before the system would attempt to kill
     * the app. The trigger does not necessarily indicate that the app was killed due to the ANR.
     *
     * <p>System will provide a snapshot of a running system trace in response to this trigger.
     */
    public static final int TRIGGER_TYPE_ANR = 2;

    /**
     * Trigger occurs when an app requests the actively running trace by calling {@link
     * ProfilingManager#requestRunningSystemTrace}.
     *
     * <p>System will provide a snapshot of a running system trace in response to this trigger.
     */
    @FlaggedApi(Flags.FLAG_PROFILING_25Q4)
    public static final int TRIGGER_TYPE_APP_REQUEST_RUNNING_TRACE = 3;

    /**
     * Trigger occurs when an app is killed due to the user clicking the "Force stop" button of the
     * App info page in Settings.
     *
     * <p>System will provide a snapshot of a running system trace in response to this trigger.
     */
    @FlaggedApi(Flags.FLAG_PROFILING_25Q4)
    public static final int TRIGGER_TYPE_KILL_FORCE_STOP = 4;

    /**
     * Trigger occurs when an app is killed due to the user removing it in the <a
     * href="https://developer.android.com/guide/components/activities/recents">Recents screen</a>.
     *
     * <p>System will provide a snapshot of a running system trace in response to this trigger.
     */
    @FlaggedApi(Flags.FLAG_PROFILING_TRIGGER_KILL_RECENTS)
    public static final int TRIGGER_TYPE_KILL_RECENTS = 5;

    /**
     * Trigger occurs when an app is killed due to the user clicking the "Stop" button for the
     * application in <a href=
     * "https://developer.android.com/develop/background-work/services/fgs/handle-user-stopping">
     * Task Manager</a>.
     *
     * <p>System will provide a snapshot of a running system trace in response to this trigger.
     */
    @FlaggedApi(Flags.FLAG_PROFILING_25Q4)
    public static final int TRIGGER_TYPE_KILL_TASK_MANAGER = 6;

    /**
     * Trigger occurs when an app has an Out Of Memory Exception.
     *
     * <p>System will provide a Java heap dump in response to this trigger.
     *
     * <p>Use of this trigger requires that any custom {@link
     * java.lang.Thread.UncaughtExceptionHandler} call through to the default uncaught exception
     * handler ({@link java.lang.Thread#getDefaultUncaughtExceptionHandler}). If the default
     * uncaught exception handler is not called, then this trigger cannot be used. The app can still
     * obtain a Java heap dump in this case, but will have to request the profiling itself using
     * {@link ProfilingManager#requestProfiling}.
     */
    @FlaggedApi(Flags.FLAG_PROFILING_TRIGGER_OOM)
    public static final int TRIGGER_TYPE_OOM = 7;

    /**
     * Trigger occurs when the system detects an anomalous behavior by the app. Anomalous behaviors
     * may span all areas. The artifact returned will vary by the anomaly.
     *
     * <p>The tag returned with the {@link ProfilingResult#getTag()} will contain additional
     * information about the type of anomaly.
     *
     * <p>Note: For some anomalous behaviors, the system may not provide artifacts if this trigger
     * is registered by more than one package with the same user id
     * (see {@link PackageManager#getPackagesForUid(int)}).
     */
    @FlaggedApi(android.os.profiling.anomaly.flags.Flags.FLAG_ANOMALY_DETECTOR_CORE)
    public static final int TRIGGER_TYPE_ANOMALY = 8;

    /**
     * Trigger occurs when an app is killed due to excessive CPU usage with an {@link
     * android.app.ApplicationExitInfo#getReason} of {@link
     * android.app.ApplicationExitInfo#REASON_EXCESSIVE_RESOURCE_USAGE}.
     *
     * <p>System will provide a snapshot of a running system trace in response to this trigger.
     */
    @FlaggedApi(Flags.FLAG_PROFILING_TRIGGER_KILL_EXCESSIVE_CPU_USAGE)
    public static final int TRIGGER_TYPE_KILL_EXCESSIVE_CPU_USAGE = 9;

    /**
     * Trigger occurs as early as possible when an app cold starts.
     *
     * <p>This happens when the {@link android.app.ApplicationStartInfo#getStartType} start type is
     * {@link android.app.ApplicationStartInfo#START_TYPE_COLD}.
     *
     * <p>The system will provide a newly started system trace and stack sampling profile in
     * response to this trigger. Profiling will continue until the app calls {@link
     * android.app.Activity#reportFullyDrawn}; otherwise, it will stop after a default period of 5
     * seconds.
     *
     * <p>The system uses a discard buffer for this trigger. This means that if the buffer fills up,
     * newer events are discarded. This ensures that the earliest available tracepoints are always
     * retained.
     *
     * <p>Note: There might be a delay before profiling begins, similar to when {@link
     * ProfilingManager#requestProfiling} is used.
     */
    @FlaggedApi(Flags.FLAG_PROFILING_TRIGGER_COLD_START)
    public static final int TRIGGER_TYPE_COLD_START = 10;

    /**
     * Trigger occurs when the system detects an anomalous behavior by the app which will become
     * unsupported in future Android versions. The artifact returned will vary by the anomaly.
     *
     * <p>The tag returned with the {@link ProfilingResult#getTag()} will contain additional
     * information about the app compatibility issues.
     */
    @FlaggedApi(android.os.profiling.anomaly.flags.Flags.FLAG_ANOMALY_DETECTOR_CORE)
    public static final int TRIGGER_TYPE_APP_COMPAT = 11;
    // LINT.ThenChange(/service/java/com/android/os/profiling/LoggingHelper.java:trigger_types)

    /** @hide */
    @IntDef(
            value = {
                TRIGGER_TYPE_NONE,
                TRIGGER_TYPE_APP_FULLY_DRAWN,
                TRIGGER_TYPE_ANR,
                TRIGGER_TYPE_APP_REQUEST_RUNNING_TRACE,
                TRIGGER_TYPE_KILL_FORCE_STOP,
                TRIGGER_TYPE_KILL_RECENTS,
                TRIGGER_TYPE_KILL_TASK_MANAGER,
                TRIGGER_TYPE_OOM,
                TRIGGER_TYPE_ANOMALY,
                TRIGGER_TYPE_KILL_EXCESSIVE_CPU_USAGE,
                TRIGGER_TYPE_COLD_START,
                TRIGGER_TYPE_APP_COMPAT,
            })
    @Retention(RetentionPolicy.SOURCE)
    @interface TriggerType {}

    /** {@link #getTriggerType} */
    private final @TriggerType int mTriggerType;

    /** {@link #getRateLimitingPeriodHours} */
    private final int mRateLimitingPeriodHours;

    private ProfilingTrigger(@TriggerType int triggerType, int rateLimitingPeriodHours) {
        mTriggerType = triggerType;
        mRateLimitingPeriodHours = rateLimitingPeriodHours;
    }

    /** Builder class to create a {@link ProfilingTrigger} object. */
    @FlaggedApi(Flags.FLAG_SYSTEM_TRIGGERED_PROFILING_NEW)
    public static final class Builder {
        // Trigger type has to be set, so make it an object and set to null.
        private int mBuilderTriggerType;

        // Rate limiter period default is 0 which will make it do nothing.
        private int mBuilderRateLimitingPeriodHours = 0;

        /**
         * Create a new builder instance to create a {@link ProfilingTrigger} object.
         *
         * <p>Requires a trigger type. An app can only have one registered trigger per trigger type.
         * Adding a new trigger with the same type will override the previously set one.
         *
         * @throws IllegalArgumentException if the trigger type is not valid.
         */
        public Builder(@TriggerType int triggerType) {
            if (!isValidRequestTriggerType(triggerType)) {
                throw new IllegalArgumentException("Invalid trigger type.");
            }

            mBuilderTriggerType = triggerType;
        }

        /** Build the {@link ProfilingTrigger} object. */
        @NonNull
        public ProfilingTrigger build() {
            return new ProfilingTrigger(mBuilderTriggerType, mBuilderRateLimitingPeriodHours);
        }

        /**
         * Set a rate limiting period in hours.
         *
         * <p>The period is the minimum time the system should wait before providing another
         * profiling result for the same trigger; actual time between events may be longer.
         *
         * <p>If the rate limiting period is not provided or set to 0, no app-provided rate limiting
         * will be used.
         *
         * <p>This rate limiting is in addition to any system level rate limiting that may be
         * applied.
         *
         * @throws IllegalArgumentException if the value is less than 0.
         */
        @NonNull
        public Builder setRateLimitingPeriodHours(int rateLimitingPeriodHours) {
            if (rateLimitingPeriodHours < 0) {
                throw new IllegalArgumentException("Hours can't be negative. Try again.");
            }

            mBuilderRateLimitingPeriodHours = rateLimitingPeriodHours;
            return this;
        }
    }

    /** The trigger type indicates which event should trigger the requested profiling. */
    public @TriggerType int getTriggerType() {
        return mTriggerType;
    }

    /**
     * The requester set rate limiting period in hours.
     *
     * <p>The period is the minimum time the system should wait before providing another profiling
     * result for the same trigger; actual time between events may be longer.
     *
     * <p>If the rate limiting period is set to 0, no app-provided rate limiting will be used.
     *
     * <p>This rate limiting is in addition to any system level rate limiting that may be applied.
     */
    public int getRateLimitingPeriodHours() {
        return mRateLimitingPeriodHours;
    }

    /**
     * Convert to value parcel. Used for binder.
     *
     * @hide
     */
    public ProfilingTriggerValueParcel toValueParcel() {
        ProfilingTriggerValueParcel valueParcel = new ProfilingTriggerValueParcel();

        valueParcel.triggerType = mTriggerType;
        valueParcel.rateLimitingPeriodHours = mRateLimitingPeriodHours;

        return valueParcel;
    }

    /**
     * Check whether the trigger type is valid for request use. Note that this means that a value of
     * {@link TRIGGER_TYPE_NONE} will return false.
     *
     * @hide
     */
    public static boolean isValidRequestTriggerType(int triggerType) {
        return triggerType == TRIGGER_TYPE_APP_FULLY_DRAWN
                || triggerType == TRIGGER_TYPE_ANR
                || (Flags.profiling25q4() && triggerType == TRIGGER_TYPE_APP_REQUEST_RUNNING_TRACE)
                || (Flags.profiling25q4() && triggerType == TRIGGER_TYPE_KILL_FORCE_STOP)
                || (Flags.profilingTriggerKillRecents() && triggerType == TRIGGER_TYPE_KILL_RECENTS)
                || (Flags.profiling25q4() && triggerType == TRIGGER_TYPE_KILL_TASK_MANAGER)
                || (Flags.profilingTriggerOom() && triggerType == TRIGGER_TYPE_OOM)
                || (android.os.profiling.anomaly.flags.Flags.anomalyDetectorCore()
                        && triggerType == TRIGGER_TYPE_ANOMALY)
                || (Flags.profilingTriggerKillExcessiveCpuUsage()
                        && triggerType == TRIGGER_TYPE_KILL_EXCESSIVE_CPU_USAGE)
                || (Flags.profilingTriggerColdStart() && triggerType == TRIGGER_TYPE_COLD_START)
                || (android.os.profiling.anomaly.flags.Flags.anomalyDetectorCore()
                        && triggerType == TRIGGER_TYPE_APP_COMPAT);
    }

    /**
     * Check whether the provided trigger type is one of the anomaly trigger types.
     *
     * @hide
     */
    public static boolean isAnomalyTriggerType(int triggerType) {
        if (!android.os.profiling.anomaly.flags.Flags.anomalyDetectorCore()) {
            // If the flag is off then it can't be an anomaly trigger.
            return false;
        } else if (triggerType == ProfilingTrigger.TRIGGER_TYPE_ANOMALY
                || triggerType == ProfilingTrigger.TRIGGER_TYPE_APP_COMPAT) {
            return true;
        }
        return false;
    }
}
