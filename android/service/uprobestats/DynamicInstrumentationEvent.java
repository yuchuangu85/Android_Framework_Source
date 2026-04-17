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
package android.service.uprobestats;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.os.Bundle;
import android.uprobestats.mainline.flags.Flags;

import java.time.Instant;
import java.util.Objects;

/**
 * Container for an event received from uprobestats.
 *
 * @hide
 */
@SystemApi
@TestApi
@FlaggedApi(Flags.FLAG_DYNAMIC_INSTRUMENTATION_EVENT_SERVICE)
public final class DynamicInstrumentationEvent {

    private final int mUid;
    private final Instant mTimestamp;
    private final int mPayloadId;
    private final Bundle mPayload;

    /**
     * Creates a new DynamicInstrumentationEvent.
     *
     * @param uid the UID of the process where the event was found.
     * @param timestamp the approximate time when event was found.
     * @param payloadId the payload ID of the event.
     * @param payload the payload ID of the event.
     * @hide
     */
    @SystemApi
    @TestApi
    @FlaggedApi(Flags.FLAG_DYNAMIC_INSTRUMENTATION_EVENT_SERVICE)
    public DynamicInstrumentationEvent(
            int uid, @NonNull Instant timestamp, int payloadId, @NonNull Bundle payload) {
        this.mUid = uid;
        this.mTimestamp = Objects.requireNonNull(timestamp, "Valid timestamp expected, got null");
        this.mPayloadId = payloadId;
        this.mPayload = Objects.requireNonNull(payload, "Valid payload expected, got null");
    }

    /**
     * Get the UID of the process where the event was found.
     *
     * @hide
     */
    @SystemApi
    @TestApi
    @FlaggedApi(Flags.FLAG_DYNAMIC_INSTRUMENTATION_EVENT_SERVICE)
    public int getUid() {
        return mUid;
    }

    /**
     * Get the approximate time when event was found.
     *
     * @hide
     */
    @SystemApi
    @TestApi
    @FlaggedApi(Flags.FLAG_DYNAMIC_INSTRUMENTATION_EVENT_SERVICE)
    @Nullable
    public Instant getTimestamp() {
        return mTimestamp;
    }

    /**
     * Get the payload ID of the event.
     *
     * @hide
     */
    @SystemApi
    @TestApi
    @FlaggedApi(Flags.FLAG_DYNAMIC_INSTRUMENTATION_EVENT_SERVICE)
    public int getPayloadId() {
        return mPayloadId;
    }

    /**
     * Get the payload of the event. The contents depend on the payload ID.
     *
     * @hide
     */
    @SystemApi
    @TestApi
    @FlaggedApi(Flags.FLAG_DYNAMIC_INSTRUMENTATION_EVENT_SERVICE)
    @NonNull
    public Bundle getPayload() {
        return mPayload;
    }

    @Override
    public String toString() {
        return "DynamicInstrumentationEvent{"
                + "mUid="
                + mUid
                + ", mTimestamp="
                + mTimestamp
                + ", mPayloadId="
                + mPayloadId
                + ", mPayload="
                + mPayload
                + '}';
    }
}
