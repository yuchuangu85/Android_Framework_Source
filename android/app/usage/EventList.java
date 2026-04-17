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

package android.app.usage;

import android.app.usage.UsageEvents.Event;

import java.util.ArrayList;

/**
 * A container to keep {@link Event usage events} in non-descending order of their
 * {@link Event#mTimeStamp timestamps}.
 *
 * @hide
 */
public class EventList {

    private final ArrayList<Event> mEvents;
    private final EventListRateLimiter mEventListRateLimiter;

    /**
     * Create a new event list with default capacity
     */
    public EventList() {
        mEvents = new ArrayList<>();
        mEventListRateLimiter = new EventListRateLimiter();
    }

    /**
     * Returns the size of the list
     * @return the number of events in the list
     */
    public int size() {
        return mEvents.size();
    }

    /**
     * Removes all events from the list
     */
    public void clear() {
        mEvents.clear();
    }

    /**
     * Returns the {@link Event event} at the specified position in this list.
     * @param index the index of the event to return, such that {@code 0 <= index < size()}
     * @return The {@link Event event} at position {@code index}
     */
    public Event get(int index) {
        return mEvents.get(index);
    }

    /**
     * Inserts the given {@link Event event} into the list while keeping the list sorted
     * based on the event {@link Event#mTimeStamp timestamps}.
     *
     * @param event The event to insert
     */
    public void insert(Event event) {
        insertInternal(event, /* skipThresholdChecks= */ false);
    }

    /**
     * Same as {@link #insert(Event)} but used when inserting an obfuscated event
     * from disk.
     *
     * @param event The obfuscated event to insert.
     */
    public void insertObfuscated(Event event) {
        // If the event is obfuscated, the package name will be a token hence inserting it in the
        // traditional way would lead the frequency counts to be inaccurate - skip the threshold
        // checks and insert normally.
        if (event.mPackage == null && event.mPackageToken != -1) {
            insertInternal(event, /* skipThresholdChecks= */ true);
        } else { // safety check in case this is called by mistake
            insert(event);
        }
    }

    private void insertInternal(Event event, boolean skipThresholdChecks) {
        if (!skipThresholdChecks && !mEventListRateLimiter.shouldInsert(event)) {
            // Remove the last event of this type so it gets replaced with the new one.
            // This is a best effort to ensure an accurate timeline of events remains for consumers.
            // TODO: b/452129902 - Add a new "drop event count" field and return that to consumers.
            for (int i = mEvents.size() - 1; i >= 0; i--) {
                if (mEventListRateLimiter.areMatchingEvents(event, mEvents.get(i))) {
                    mEvents.remove(i);
                    break;
                }
            }
        }

        final int size = mEvents.size();
        // fast case: just append if this is the latest event
        if (size == 0 || event.mTimeStamp >= mEvents.get(size - 1).mTimeStamp) {
            mEvents.add(event);
            return;
        }
        // To minimize number of elements being shifted, insert at the first occurrence of the next
        // greatest timestamp in the list.
        final int insertIndex = firstIndexOnOrAfter(event.mTimeStamp + 1);
        mEvents.add(insertIndex, event);

        // TODO: b/452129902 - If the total size of mEvents exceeds a certain threshold in memory,
        //  we should force a flush to disk operation.
    }

    /**
     * Removes the event at the given index.
     *
     * @param index the index of the event to remove
     * @return the event removed, or {@code null} if the index was out of bounds
     */
    public Event remove(int index) {
        try {
            return mEvents.remove(index);
        } catch (IndexOutOfBoundsException e) {
            // catch and handle the exception here instead of throwing it to the client
            return null;
        }
    }

    /**
     * Finds the index of the first event whose timestamp is greater than or equal to the given
     * timestamp.
     *
     * @param timeStamp The timestamp for which to search the list.
     * @return The smallest {@code index} for which {@code (get(index).mTimeStamp >= timeStamp)} is
     * {@code true}, or {@link #size() size} if no such {@code index} exists.
     */
    public int firstIndexOnOrAfter(long timeStamp) {
        final int size = mEvents.size();
        int result = size;
        int lo = 0;
        int hi = size - 1;
        while (lo <= hi) {
            final int mid = (lo + hi) >>> 1;
            final long midTimeStamp = mEvents.get(mid).mTimeStamp;
            if (midTimeStamp >= timeStamp) {
                hi = mid - 1;
                result = mid;
            } else {
                lo = mid + 1;
            }
        }
        return result;
    }

    /**
     * Merge the {@link Event events} in the given {@link EventList list} into this
     * list while keeping the list sorted based on the event {@link
     * Event#mTimeStamp timestamps}.
     *
     * @param events The event list to merge
     */
    public void merge(EventList events) {
        final int size = events.size();
        for (int i = 0; i < size; i++) {
            insertInternal(events.get(i), /* skipThresholdChecks= */ true);
        }
    }
}
