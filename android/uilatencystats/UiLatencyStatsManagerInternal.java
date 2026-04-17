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

/**
 * @hide {@link com.android.server.uilatencystats.UiLatencyStatsService} API for SystemServer.
 */
public abstract class UiLatencyStatsManagerInternal {
    /**
     * Registers a listener to subscribe to UI latency events. The listener will be notified of
     * events published by the UiLatencyStatsService.
     *
     * @param listener The listener to register.
     */
    public abstract void registerUiLatencyEventListener(UiLatencyEventListener listener);

    /**
     * Publishes a UI latency event to all registered listeners.
     *
     * @param event The event to publish.
     */
    public abstract void publishEvent(Event event);
}
