/*
 * Copyright (C) 2026 The Android Open Source Project
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

package android.uilatencystats;

import android.annotation.NonNull;
import android.annotation.UserIdInt;
import android.os.SystemClock;

/**
 * Represents a UI latency event worth recording. {@link
 * com.android.server.uilatencystats.UiLatencyStatsService} will publish the event to registered
 * {@link android.uilatencystats.UiLatencyEventListener}s which process and convert them to statsd
 * metrics.
 *
 * @hide
 */
public final class Event {
    private final long mTimestamp;
    private final EventType mEventType;
    private final @UserIdInt int mUserId;

    public Event(@NonNull EventType eventType, @UserIdInt int userId, long timestamp) {
        mEventType = eventType;
        mUserId = userId;
        mTimestamp = timestamp;
    }

    public Event(@NonNull EventType eventType, @UserIdInt int userId) {
        this(eventType, userId, SystemClock.elapsedRealtime());
    }

    /**
     * Returns the elapsed time of the event in ms since boot.
     *
     * @hide
     */
    public long getTimestamp() {
        return mTimestamp;
    }

    /**
     * Returns the type of the event.
     *
     * @hide
     */
    @NonNull
    public EventType getType() {
        return mEventType;
    }

    /**
     * Returns the user id of the event.
     *
     * @hide
     */
    @UserIdInt
    public int getUserId() {
        return mUserId;
    }
}
