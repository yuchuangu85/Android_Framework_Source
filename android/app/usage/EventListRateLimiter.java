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

package android.app.usage;

import android.app.usage.UsageEvents.Event;
import android.util.ArrayMap;
import android.util.Slog;
import android.util.SparseArrayMap;
import android.util.SparseIntArray;
import android.util.SparseLongArray;

import com.android.internal.annotations.VisibleForTesting;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Helper class to handle rate-limiting for insertions into the {@link EventList} class.
 *
 * @hide
 */
public final class EventListRateLimiter {

    private static final String TAG = "UsageEventListRateLimiter";

    private static final Set<Integer> ACTIVITY_EVENTS = Set.of(
            Event.ACTIVITY_RESUMED,
            Event.ACTIVITY_PAUSED,
            Event.ACTIVITY_STOPPED,
            Event.ACTIVITY_DESTROYED // for completeness
    );
    private static final Set<Integer> FGS_EVENTS = Set.of(
            Event.FOREGROUND_SERVICE_START,
            Event.FOREGROUND_SERVICE_STOP,
            Event.CONTINUING_FOREGROUND_SERVICE, // for completeness
            Event.ROLLOVER_FOREGROUND_SERVICE // for completeness
    );
    private static final Set<Integer> USER_ACTION_BASED_EVENTS = Set.of(
            Event.USER_INTERACTION,
            Event.SHORTCUT_INVOCATION,
            Event.CHOOSER_ACTION
    );

    private static final long INVALID_EVENT_WINDOW_END_TIME = -1L;
    private static final int THRESHOLD_MAPS_SIZE_LIMIT = 200;

    // TODO: b/452129902 - Update the event count limits below to a int[] based on metrics.
    // Maximum number of user-action based events allowed to be reported within a threshold.
    @VisibleForTesting
    static final int USER_EVENTS_COUNT_LIMIT = 60; // based on 60Hz average
    // Maximum number of non-user-action based events allowed to be reported within a threshold.
    @VisibleForTesting
    static final int GENERAL_EVENTS_COUNT_LIMIT = 1000;

    // TODO: b/452129902 - Update the event threshold windows to a long[] based on metrics.
    // Threshold defining the time period within which the maximum number of user-action based
    // events can be reported.
    @VisibleForTesting
    static final long USER_EVENT_THRESHOLD_WINDOW_MS = 1000; // 1s
    // Threshold defining the time period within which the maximum number of non-user-action based
    // events of a particular type can be reported.
    @VisibleForTesting
    static final long GENERAL_EVENT_THRESHOLD_WINDOW_MS = 10 * 1000; // 10s

    // Holds the counts of activity-related events, keyed by the instance id
    private final SparseIntArray mActivityEventCounts;
    // Holds the threshold window end times for activity-related events, keyed by instance id
    private final SparseLongArray mActivityEventWindowEndTimes;

    // Holds the counts of fgs-related events, keyed by the class name
    private final ArrayMap<String, Integer> mFgsEventCounts;
    // Holds the counts of fgs-related events, keyed by the class name
    private final ArrayMap<String, Long> mFgsEventWindowEndTimes;

    // Holds the counts of non-activity and non-fgs related events,
    // keyed by the event type and package name combination
    private final SparseArrayMap<String, Integer> mGeneralEventCounts;
    // Holds the threshold window end times of non-activity and non-fgs related events,
    // keyed by the event type and package name combination
    private final SparseArrayMap<String, Long> mGeneralEventWindowEndTimes;

    EventListRateLimiter() {
        mActivityEventCounts = new SparseIntArray();
        mActivityEventWindowEndTimes = new SparseLongArray();
        mFgsEventCounts = new ArrayMap<>();
        mFgsEventWindowEndTimes = new ArrayMap<>();
        mGeneralEventCounts = new SparseArrayMap<>();
        mGeneralEventWindowEndTimes = new SparseArrayMap<>();
    }

    boolean shouldInsert(Event event) {
        if (ACTIVITY_EVENTS.contains(event.mEventType)) {
            return shouldInsertActivityEvent(event);
        } else if (FGS_EVENTS.contains(event.mEventType)) {
            return shouldInsertFgsEvent(event);
        }

        // Handle all other event types
        final boolean isUserActionBasedEvent = USER_ACTION_BASED_EVENTS.contains(event.mEventType);
        final long eventWindowEndTime = mGeneralEventWindowEndTimes.getOrDefault(
                event.mEventType, event.mPackage, INVALID_EVENT_WINDOW_END_TIME);
        // This event is being reported after the last known threshold window, reset the tracking.
        if (eventWindowEndTime == INVALID_EVENT_WINDOW_END_TIME
                || event.mTimeStamp >= eventWindowEndTime) {
            long newEventWindowEndTime = event.mTimeStamp + (isUserActionBasedEvent
                    ? USER_EVENT_THRESHOLD_WINDOW_MS
                    : GENERAL_EVENT_THRESHOLD_WINDOW_MS);
            mGeneralEventCounts.add(event.mEventType, event.mPackage, 1);
            mGeneralEventWindowEndTimes.add(
                    event.mEventType, event.mPackage, newEventWindowEndTime);
            cleanUpEventTrackingMapsIfNeeded(event.mEventType);
            return true;
        }
        // Note: there is a slight chance here that an event gets reported before the current
        // timestamp we're looking at and it should technically be considered before this time
        // window; in that case, it's likely that the out-of-order magnitude is in milliseconds
        // so adding the count to the current window below is reasonable.

        final int eventCount = mGeneralEventCounts.get(event.mEventType, event.mPackage);
        final int eventCountLimit =
                isUserActionBasedEvent ? USER_EVENTS_COUNT_LIMIT : GENERAL_EVENTS_COUNT_LIMIT;
        // Event is being reported again within the threshold, increment the count and allow insert.
        if (eventCount < eventCountLimit) {
            mGeneralEventCounts.add(event.mEventType, event.mPackage, eventCount + 1);
            return true;
        }

        // Event reported too many times, log a wtf.
        Slog.wtf(TAG, "Too many usage events reported of type " + event.mEventType
                + " from package " + event.mPackage + "; replacing last known event.");
        return false;
    }

    private void cleanUpEventTrackingMapsIfNeeded(int triggeringEventType) {
        if (mGeneralEventWindowEndTimes.numElementsForKey(triggeringEventType)
                <= THRESHOLD_MAPS_SIZE_LIMIT) {
            return; // fast-path to avoid unnecessary clean-up for other event types.
        }

        final long timeNow = System.currentTimeMillis();
        final Set<String> pkgsToRemove = new HashSet<>();
        mGeneralEventWindowEndTimes.forEach((eventType, pkgName, eventWindowEndTime) -> {
            if (eventType != triggeringEventType) {
                return; // don't perform clean-up for other event types
            }
            if (eventWindowEndTime < (timeNow - GENERAL_EVENT_THRESHOLD_WINDOW_MS)) {
                pkgsToRemove.add(pkgName);
            }
        });

        for (String pkgName : pkgsToRemove) {
            mGeneralEventCounts.delete(triggeringEventType, pkgName);
            mGeneralEventWindowEndTimes.delete(triggeringEventType, pkgName);
        }
    }

    private boolean shouldInsertActivityEvent(Event event) {
        final long eventWindowEndTime =
                mActivityEventWindowEndTimes.get(event.mInstanceId, INVALID_EVENT_WINDOW_END_TIME);
        // This event is being reported after the last known threshold window, reset the tracking.
        if (eventWindowEndTime == INVALID_EVENT_WINDOW_END_TIME
                || event.mTimeStamp >= eventWindowEndTime) {
            mActivityEventCounts.put(event.mInstanceId, 1);
            mActivityEventWindowEndTimes.put(event.mInstanceId,
                    event.mTimeStamp + USER_EVENT_THRESHOLD_WINDOW_MS);
            cleanUpActivityTrackingMapsIfNeeded();
            return true;
        }

        final int eventCount = mActivityEventCounts.get(event.mInstanceId);
        // Event is being reported again within the threshold, increment the count and allow insert.
        if (eventCount < USER_EVENTS_COUNT_LIMIT) {
            mActivityEventCounts.put(event.mInstanceId, eventCount + 1);
            return true;
        }

        // Event reported too many times, log a wtf.
        Slog.wtf(TAG, "Too many activity-related usage events reported of type " + event.mEventType
                + " from package " + event.mPackage + "; replacing last known event.");
        return false;
    }

    private void cleanUpActivityTrackingMapsIfNeeded() {
        if (mActivityEventCounts.size() <= THRESHOLD_MAPS_SIZE_LIMIT) {
            return; // don't perform clean-up too often
        }

        final long timeNow = System.currentTimeMillis();
        for (int i = mActivityEventWindowEndTimes.size() - 1; i >= 0; i--) {
            if (mActivityEventWindowEndTimes.valueAt(i)
                    < (timeNow - USER_EVENT_THRESHOLD_WINDOW_MS)) {
                mActivityEventCounts.delete(mActivityEventWindowEndTimes.keyAt(i));
                mActivityEventWindowEndTimes.removeAt(i);
            }
        }
    }

    private boolean shouldInsertFgsEvent(Event event) {
        final long eventWindowEndTime =
                mFgsEventWindowEndTimes.getOrDefault(event.mClass, INVALID_EVENT_WINDOW_END_TIME);
        // This event is being reported after the last known threshold window, reset the tracking.
        if (eventWindowEndTime == INVALID_EVENT_WINDOW_END_TIME
                || event.mTimeStamp >= eventWindowEndTime) {
            mFgsEventCounts.put(event.mClass, 1);
            mFgsEventWindowEndTimes.put(event.mClass,
                    event.mTimeStamp + USER_EVENT_THRESHOLD_WINDOW_MS);
            cleanUpFgsTrackingMapsIfNeeded();
            return true;
        }

        final int eventCount = mFgsEventCounts.get(event.mClass);
        // Event is being reported again within the threshold, increment the count and allow insert.
        if (eventCount < USER_EVENTS_COUNT_LIMIT) {
            mFgsEventCounts.put(event.mClass, eventCount + 1);
            return true;
        }

        // Event reported too many times, log a wtf.
        Slog.wtf(TAG, "Too many FGS-related usage events reported of type " + event.mEventType
                + " from class " + event.mClass + "; replacing last known event.");
        return false;
    }

    private void cleanUpFgsTrackingMapsIfNeeded() {
        if (mFgsEventCounts.size() <= THRESHOLD_MAPS_SIZE_LIMIT) {
            return; // don't perform clean-up too often
        }

        final long timeNow = System.currentTimeMillis();
        for (int i = mFgsEventWindowEndTimes.size() - 1; i >= 0; i--) {
            if (mFgsEventWindowEndTimes.valueAt(i) < (timeNow - USER_EVENT_THRESHOLD_WINDOW_MS)) {
                mFgsEventCounts.remove(mFgsEventWindowEndTimes.keyAt(i));
                mFgsEventWindowEndTimes.removeAt(i);
            }
        }
    }

    /**
     * Given two events, determines if they are equal based on their categorization conditions.
     */
    boolean areMatchingEvents(Event one, Event two) {
        if (one.mEventType != two.mEventType) {
            return false;
        }

        if (ACTIVITY_EVENTS.contains(one.mEventType)) {
            return one.mInstanceId == two.mInstanceId;
        } else if (FGS_EVENTS.contains(one.mEventType)) {
            return Objects.equals(one.mClass, two.mClass);
        } else {
            return Objects.equals(one.mPackage, two.mPackage);
        }
    }
}
