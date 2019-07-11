/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.internal.telephony;

/**
 * Link Bandwidth Information from the Radio
 */
public class LinkCapacityEstimate {
    /** Any field that is not reported shall be set to INVALID */
    public static final int INVALID = -1;

    /** LCE is active; Deprecated in HAL 1.2 */
    public static final int STATUS_ACTIVE = 0;

    /** LCE is suspended; Deprecated in HAL 1.2 */
    public static final int STATUS_SUSPENDED = 1;

    /** Downlink radio link capacity in kbps */
    public final int downlinkCapacityKbps;

    /** Uplink radio link capacity; added in HAL 1.2 */
    public final int uplinkCapacityKbps;

    /** Confidence of the downlink estimate as a percentage [1, 100]; deprecated in HAL 1.2 */
    public final int confidence;

    /** Status of the LCE; deprecated in HAL 1.2 */
    public final int status; // either STATUS_ACTIVE, STATUS_SUSPENDED, or INVALID

    /** Constructor matching the estimate in Radio HAL v1.0 */
    public LinkCapacityEstimate(int downlinkCapacityKbps, int confidence, int status) {
        this.downlinkCapacityKbps = downlinkCapacityKbps;
        this.confidence = confidence;
        this.status = status;
        this.uplinkCapacityKbps = INVALID;
    }

    /** Constructor matching the estimate in Radio HAL v1.2 */
    public LinkCapacityEstimate(int downlinkCapacityKbps, int uplinkCapacityKbps) {
        this.downlinkCapacityKbps = downlinkCapacityKbps;
        this.uplinkCapacityKbps = uplinkCapacityKbps;
        this.confidence = INVALID;
        this.status = INVALID;
    }

    @Override
    public String toString() {
        return new StringBuilder()
                .append("{downlinkCapacityKbps=")
                .append(downlinkCapacityKbps)
                .append(", uplinkCapacityKbps=")
                .append(uplinkCapacityKbps)
                .append(", confidence=")
                .append(confidence)
                .append(", status=")
                .append(status)
                .toString();
    }
}
