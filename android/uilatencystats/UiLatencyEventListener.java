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

import java.util.List;

/**
 * Listener for UI latency events registered to {@link
 * com.android.server.uilatencystats.UiLatencyStatsService} to subscribe to {@link Event}s.
 *
 * @hide
 */
public interface UiLatencyEventListener {
    /** Returns the list of event IDs that this listener is interested in. */
    List<Integer> getEventIdsToListen();

    /**
     * This callback is called by {@link com.android.server.uilatencystats.UiLatencyStatsService}
     * for registered events.
     *
     * @param event The event that occurred.
     */
    void onEvent(Event event);
}
