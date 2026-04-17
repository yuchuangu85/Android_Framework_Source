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

package android.os;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.UUID;

/**
 * Anomaly detector specific profiling result.
 *
 * @hide
 */
public class AnomalyRequestResult {

    // Result codes 100-199 are reserved for anomaly and may be used here. Do not define codes here
    // which are outside this range.
    /** Request was not fulfilled due to the background trace not running. */
    public static final int ERROR_FAILED_BACKGROUND_TRACE_NOT_RUNNING = 100;

    /** Request was not fulfilled due to the trigger not being registered for the requested app. */
    public static final int ERROR_FAILED_TRIGGER_NOT_REGISTERED = 101;

    /**
     * Request was fulfilled but the result was never sent to the app because it did not register a
     * callback within the queued results timeout. Result was discarded.
     */
    public static final int ERROR_FAILED_CALLBACK_TIMEOUT = 102;

    @IntDef(
            value = {
                ProfilingResult.ERROR_NONE,
                ProfilingResult.ERROR_FAILED_RATE_LIMIT_SYSTEM,
                ProfilingResult.ERROR_FAILED_RATE_LIMIT_PROCESS,
                ProfilingResult.ERROR_FAILED_PROFILING_IN_PROGRESS,
                ProfilingResult.ERROR_FAILED_EXECUTING,
                ProfilingResult.ERROR_FAILED_POST_PROCESSING,
                ProfilingResult.ERROR_FAILED_NO_DISK_SPACE,
                ProfilingResult.ERROR_UNKNOWN,
                ERROR_FAILED_BACKGROUND_TRACE_NOT_RUNNING,
                ERROR_FAILED_TRIGGER_NOT_REGISTERED,
                ERROR_FAILED_CALLBACK_TIMEOUT,
            })
    @Retention(RetentionPolicy.SOURCE)
    @interface ErrorCode {}

    private final UUID mKey;

    private final int mUid;

    private final int mErrorCode;

    private final int mTriggerType;

    @Nullable private final String mTag;

    @Nullable private final String mResultFilePath;

    public AnomalyRequestResult(
            @NonNull UUID key,
            int uid,
            @ErrorCode int errorCode,
            @Nullable String resultFilePath,
            @Nullable String tag,
            int triggerType) {
        mKey = key;
        mUid = uid;
        mErrorCode = errorCode;
        mTriggerType = triggerType;
        mTag = tag;
        mResultFilePath = resultFilePath;
    }

    /**
     * The unique identifier to match this result back to a request. This identifier is returned by
     * all Anomaly profiling requests in {@link AnomalyProfilingManager}.
     */
    public UUID getKey() {
        return mKey;
    }

    /** The uid of the process this profiling related to. */
    public int getUid() {
        return mUid;
    }

    /**
     * The result error code of the request. Allowed values are a superset of values defined in
     * {@link ProfilingResult} and values defined in this class.
     */
    public @ErrorCode int getErrorCode() {
        return mErrorCode;
    }

    /** Trigger type of this profiling request. */
    public int getTriggerType() {
        return mTriggerType;
    }

    /**
     * The file path of the profiling result data.
     *
     * <p>Value will be null for all cases except profiling requests made using
     * {AnomalyProfilingManager#collectAnomalyProfile} which succeed.
     */
    @Nullable
    public String getResultFilePath() {
        return mResultFilePath;
    }

    /** The tag defined in the original request. */
    @Nullable
    public String getTag() {
        return mTag;
    }
}
