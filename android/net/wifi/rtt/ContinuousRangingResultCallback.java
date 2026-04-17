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

package android.net.wifi.rtt;

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.RequiresApi;
import android.annotation.SystemApi;

import com.android.wifi.flags.Flags;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;

/**
 * Defines the callbacks for a continuous ranging session. Should be extended by applications and
 * set when calling {@link WifiRttManager#startContinuousRanging(WorkSource,
 * RangingRequest, java.util.concurrent.Executor, ContinuousRangingResultCallback)}.
 * If the ranging operation fails in whole (not attempted) then {@link #onRangingFailure(int)}
 * will be called with a failure code. If the ranging operation is performed for each of the
 * requested peers then the {@link #onRangingResults(List)} will be called with the set of
 * results ({@link RangingResult}, each of which has its own success/failure code
 * {@link RangingResult#getStatus()}.
 * @hide
 */
@SystemApi
@RequiresApi(37)
@FlaggedApi(Flags.FLAG_PROXIMITY_RANGING)
public abstract class ContinuousRangingResultCallback {
    /** @hide */
    @IntDef({FAILURE_REASON_GENERIC, FAILURE_REASON_RTT_NOT_AVAILABLE,
            FAILURE_REASON_RTT_BUSY,
            FAILURE_REASON_RTT_PD_NEGOTIATION_FAILED})
    @Retention(RetentionPolicy.SOURCE)
    public @interface RangingFailureReason {
    }

    /**
     * A generic failure reason code for the whole ranging request operation. Indicates a failure.
     */
    public static final int FAILURE_REASON_GENERIC = 1;

    /**
     * A failure reason code for the whole ranging request operation. Indicates that the
     * request failed due to RTT not being available - e.g. Wi-Fi was disabled. Use the
     * {@link WifiRttManager#isAvailable()} and
     * {@link WifiRttManager#ACTION_WIFI_RTT_STATE_CHANGED}
     * to track RTT availability.
     */
    public static final int FAILURE_REASON_RTT_NOT_AVAILABLE = 2;

    /**
     * A failure reason code for the whole ranging request operation. Indicates that the request
     * failed due an already ongoing range request
     */
    public static final int FAILURE_REASON_RTT_BUSY = 3;

    /**
     * A failure reason code for the whole ranging request operation. Indicates that the request
     * failed due to failure in ranging security/role/channel negotiation
     */
    public static final int FAILURE_REASON_RTT_PD_NEGOTIATION_FAILED = 4;

    /** @hide */
    @IntDef({TERMINATE_REASON_UNKNOWN, TERMINATE_REASON_TIMEOUT, TERMINATE_REASON_USER_REQUEST,
            TERMINATE_REASON_ABORT_CONCURRENCY, TERMINATE_REASON_RECEIVED_RTT_TERMINATE})
    @Retention(RetentionPolicy.SOURCE)
    public @interface RangingTerminateReason {
    }

    /** Unknown reason. */
    public static final int TERMINATE_REASON_UNKNOWN = 1;
    /** Session terminated due to a timeout. */
    public static final int TERMINATE_REASON_TIMEOUT = 2;
    /** Session terminated by a user request. */
    public static final int TERMINATE_REASON_USER_REQUEST = 3;
    /** Session aborted due to a concurrency issue (e.g., another Wi-Fi operation). */
    public static final int TERMINATE_REASON_ABORT_CONCURRENCY = 4;
    /** Session terminated upon receiving a termination request from the peer. */
    public static final int TERMINATE_REASON_RECEIVED_RTT_TERMINATE = 5;

    /**
     * Called when a continuous ranging operation failed in whole - i.e. the range request
     * fails to start or encounters a fatal error. The method indicates that no further
     * results will be delivered.
     *
     * @param reason A reason code indicating the type of failure.
     */
    public abstract void onRangingFailure(@RangingFailureReason int reason);

    /**
     * Called when the continuous ranging session has been terminated. This indicates
     * that no further results or failures will be delivered.
     * @param reason The reason for the session termination, such as explicit
     * termination by the app or due to a system event.
     */
    public abstract void onRangingStopped(@RangingTerminateReason int reason);

    /**
     * Called periodically with the latest batch of ranging results. The list will
     * contain results for all devices that have been successfully ranged since the
     * last callback.
     * It may not include results for all requested devices at every interval.
     * <p>
     * The status of a ranging attempt for a specific device is contained within the
     * {@link RangingResult#getStatus()} of each item in the list.
     *
     * @param results List of range measurements, one per requested device.
     */
    public abstract void onRangingResults(@NonNull List<RangingResult> results);
}
