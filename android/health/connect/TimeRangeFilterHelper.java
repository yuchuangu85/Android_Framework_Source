/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.health.connect;

import android.annotation.NonNull;
import android.annotation.Nullable;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/**
 * A helper class for {@link TimeRangeFilter} to handle possible time filter types.
 *
 * @hide
 */
public final class TimeRangeFilterHelper {

    private static final ZoneOffset LOCAL_TIME_ZERO_OFFSET = ZoneOffset.UTC;

    public static boolean isLocalTimeFilter(@NonNull TimeRangeFilter timeRangeFilter) {
        return (timeRangeFilter instanceof LocalTimeRangeFilter);
    }

    /**
     * @return start time epoch milliseconds for Instant time filter and epoch milliseconds using
     *     UTC zoneOffset for LocalTime filter
     */
    public static long getFilterStartTimeMillis(@NonNull TimeRangeFilter timeRangeFilter) {
        if ((timeRangeFilter instanceof LocalTimeRangeFilter localTimeRangeFilter)) {
            LocalDateTime startTime = localTimeRangeFilter.getStartTime();
            // The annotations say startTime can be nullable, but the current implementation can
            // never be nullable. Rather than change the API, repeat the logic to make it non-null
            startTime =
                    startTime != null
                            ? startTime
                            : LocalDateTime.ofInstant(Instant.EPOCH, ZoneOffset.MIN);
            return getMillisOfLocalTime(startTime);
        } else if (timeRangeFilter instanceof TimeInstantRangeFilter timeInstantRangeFilter) {
            Instant startTime = timeInstantRangeFilter.getStartTime();
            // The annotations say startTime can be nullable, but the current implementation can
            // never be nullable. Rather than change the API, repeat the logic to make it non-null
            startTime = startTime != null ? startTime : Instant.EPOCH;
            return startTime.toEpochMilli();
        } else {
            throw new IllegalArgumentException(
                    "Invalid time filter object. Object should be either "
                            + "TimeInstantRangeFilter or LocalTimeRangeFilter.");
        }
    }

    /**
     * @return end time epoch milliseconds for Instant time filter and epoch milliseconds using UTC
     *     zoneOffset for LocalTime filter
     */
    public static long getFilterEndTimeMillis(@NonNull TimeRangeFilter timeRangeFilter) {
        if ((timeRangeFilter instanceof LocalTimeRangeFilter localTimeRangeFilter)) {
            LocalDateTime endTime = localTimeRangeFilter.getEndTime();
            endTime =
                    endTime != null
                            ? endTime
                            : LocalDateTime.ofInstant(
                                    Instant.now().plus(1, ChronoUnit.DAYS), ZoneOffset.MAX);
            return getMillisOfLocalTime(endTime);
        } else if (timeRangeFilter instanceof TimeInstantRangeFilter timeInstantRangeFilter) {
            Instant endTime = timeInstantRangeFilter.getEndTime();
            endTime = endTime != null ? endTime : Instant.now().plus(1, ChronoUnit.DAYS);
            return endTime.toEpochMilli();
        } else {
            throw new IllegalArgumentException(
                    "Invalid time filter object. Object should be either "
                            + "TimeInstantRangeFilter or LocalTimeRangeFilter.");
        }
    }

    public static LocalDateTime getLocalTimeFromMillis(Long localDateTimeMillis) {
        return LocalDateTime.ofInstant(
                Instant.ofEpochMilli(localDateTimeMillis), LOCAL_TIME_ZERO_OFFSET);
    }

    public static long getMillisOfLocalTime(LocalDateTime time) {
        return time.toInstant(LOCAL_TIME_ZERO_OFFSET).toEpochMilli();
    }

    /**
     * Converts the provided {@link LocalDateTime} to {@link Instant} using the provided {@link
     * ZoneOffset} if it's not null, or using the system default zone offset otherwise.
     */
    public static Instant getInstantFromLocalTime(
            @NonNull LocalDateTime time, @Nullable ZoneOffset zoneOffset) {
        return zoneOffset != null
                ? time.toInstant(zoneOffset)
                : time.toInstant(ZoneOffset.systemDefault().getRules().getOffset(time));
    }
}
