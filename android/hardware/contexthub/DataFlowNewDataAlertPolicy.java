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

package android.hardware.contexthub;

import android.annotation.DurationMillisLong;
import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.chre.flags.Flags;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.time.Duration;
import java.util.Objects;

/**
 * Represents a policy for the conditions under which a {@link DataFlowSource} should alert a sink
 * when new data is available.
 *
 * <p>The policy is set by the user for each registered sink. Internally, the policy determines
 * whether any given {@link DataFlowSink#push(DataFlowData)} operation will alert a sink or whether
 * a particular data or time threshold must first be reached.
 *
 * @hide
 */
@SystemApi
@FlaggedApi(Flags.FLAG_FMCQ_API)
public final class DataFlowNewDataAlertPolicy {
    /** Never alert a sink on new data. */
    public static final int POLICY_TYPE_NEVER = 0;

    /**
     * Alerts a sink only when both the available data for that sink exceeds a low watermark and the
     * sink endpoint is in an active state, e.g. the core its running on is awake.
     *
     * <p>The notification data is the low watermark in bytes.
     */
    public static final int POLICY_TYPE_OPPORTUNISTIC = 1;

    /**
     * Alert a sink when the available data for that sink exceeds a high watermark.
     *
     * <p>The notification data is the high watermark in bytes.
     */
    public static final int POLICY_TYPE_HIGH_WATERMARK = 2;

    /**
     * Alert a sink with a given period, so long as there is data available for the sink.
     *
     * <p>The notification data is the period in milliseconds.
     */
    public static final int POLICY_TYPE_PERIODIC = 3;

    /** Alert a consumer when the state of a data flow changes in a streaming manner. */
    public static final int POLICY_TYPE_STREAMING = 4;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(
            prefix = "POLICY_TYPE_",
            value = {
                POLICY_TYPE_NEVER,
                POLICY_TYPE_OPPORTUNISTIC,
                POLICY_TYPE_HIGH_WATERMARK,
                POLICY_TYPE_PERIODIC,
                POLICY_TYPE_STREAMING
            })
    /* package */ @interface PolicyType {}

    /** The new data alert policy for a sink. */
    @PolicyType private final int mPolicyType;

    /** The new data alert policy data. The meaning of the data is dependent on the alert policy. */
    private final int mData;

    /**
     * Creates a policy to never alert a sink on new data.
     *
     * @return The alert policy
     */
    @NonNull
    public static DataFlowNewDataAlertPolicy createNeverNotifyPolicy() {
        return new DataFlowNewDataAlertPolicy(POLICY_TYPE_NEVER, 0);
    }

    /**
     * Creates a policy to alert a sink only when the sink is in an active state AND the available
     * data for that sink exceeds a low watermark.
     *
     * @param lowWatermark The low watermark in elements if the data flow format is {@link
     * DataFlowDataConfig#FORMAT_FIXED_SIZE}, in bytes otherwise
     * @return The alert policy
     */
    @NonNull
    public static DataFlowNewDataAlertPolicy createOpportunisticPolicy(
            @IntRange(from = 1) int lowWatermark) {
        if (lowWatermark < 1) {
            throw new IllegalArgumentException(
                    "Low watermark must be at least 1 element or byte.");
        }
        return new DataFlowNewDataAlertPolicy(POLICY_TYPE_OPPORTUNISTIC, lowWatermark);
    }

    /**
     * Creates a policy to alert a sink when the available data for that sink exceeds a high
     * watermark.
     *
     * @param highWatermark The high watermark in elements if the data flow format is {@link
     * DataFlowDataConfig#FORMAT_FIXED_SIZE}, in bytes otherwise
     * @return The alert policy
     * @throws IllegalArgumentException if the high watermark is less than 0
     */
    @NonNull
    public static DataFlowNewDataAlertPolicy createHighWatermarkPolicy(
            @IntRange(from = 1) int highWatermark) {
        if (highWatermark < 1) {
            throw new IllegalArgumentException(
                    "High watermark must be at least 1 element or byte.");
        }
        return new DataFlowNewDataAlertPolicy(POLICY_TYPE_HIGH_WATERMARK, highWatermark);
    }

    /**
     * Creates a policy to alert a sink with a given period, so long as there is data available for
     * the sink.
     *
     * @param periodMillis The period for alerts
     * @return The alert policy
     * @throws IllegalArgumentException if the duration in milliseconds
     */
    @NonNull
    public static DataFlowNewDataAlertPolicy createPeriodicPolicy(
            @NonNull @DurationMillisLong Duration periodMillis) {
        Objects.requireNonNull(periodMillis);
        if (periodMillis.toMillis() > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Period must be less than Integer.MAX_VALUE");
        }
        return new DataFlowNewDataAlertPolicy(POLICY_TYPE_PERIODIC, (int) periodMillis.toMillis());
    }

    /**
     * Creates a policy to alert a sink as data becomes available. The system may coalesce alerts to
     * reduce spam.
     *
     * @return The alert policy
     */
    @NonNull
    public static DataFlowNewDataAlertPolicy createStreamingPolicy() {
        return new DataFlowNewDataAlertPolicy(POLICY_TYPE_STREAMING, 0);
    }

    /** @hide */
    public DataFlowNewDataAlertPolicy(@PolicyType int policyType, int data) {
        mPolicyType = policyType;
        mData = data;
    }

    /** @hide */
    @PolicyType
    public int getPolicyType() {
        return mPolicyType;
    }

    /** @hide */
    public int getData() {
        return mData;
    }
}
