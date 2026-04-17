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

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.Nullable;
import android.annotation.SystemService;
import android.content.Context;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.Trace;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * UiLatencyStatsManager provides APIs to report UI latency metrics.
 *
 * @hide
 */
@SystemService(Context.UI_LATENCY_STATS_SERVICE)
@FlaggedApi(com.android.server.ui_latency_stats.Flags.FLAG_UI_LATENCY_STATS_SERVICE)
public class UiLatencyStatsManager {
    private final IUiLatencyStats mService;

    /** @hide */
    public UiLatencyStatsManager(Context context, IUiLatencyStats service) {
        mService = service;
    }

    /**
     * Launcher apps report that they are "ready"; they are rendered to the user for the first time
     * in the app lifecycle.
     *
     * @hide
     */
    public static final int EVENT_LAUNCHER_SHOWN = EventType.EVENT_LAUNCHER_SHOWN;

    /**
     * Reports that the lockscreen unlocking starts.
     *
     * @hide
     */
    public static final int EVENT_LOCK_SCREEN_UNLOCK_START =
            EventType.EVENT_LOCK_SCREEN_UNLOCK_START;

    /**
     * Event type IDs which can be reported from clients outside of the system server.
     *
     * @hide
     */
    @IntDef(
            prefix = {"EVENT_"},
            value = {
                EVENT_LAUNCHER_SHOWN,
                EVENT_LOCK_SCREEN_UNLOCK_START,
            })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Event {}

    /**
     * Returns the {@link EventType} for the given event ID. Returns null if the event ID is not
     * found.
     *
     * @param event The event to convert.
     * @hide
     */
    public static @Nullable EventType getEventType(@Event int event) {
        switch (event) {
            case EVENT_LAUNCHER_SHOWN:
                return new EventType.LauncherShown();
            case EVENT_LOCK_SCREEN_UNLOCK_START:
                return new EventType.LockScreenUnlockStart();
            default:
                return null;
        }
    }

    /**
     * Report an event timestamp.
     *
     * @param event The event to report.
     * @param timestamp The elapsed time of the event in ms since boot.
     * @hide
     */
    public void reportEvent(@Event int event, long timestamp) {
        EventType eventType = getEventType(event);
        if (eventType != null) {
            Trace.instant(Trace.TRACE_TAG_APP, "UiLatencyEvent: " + eventType.getName());
        }
        try {
            mService.reportEvent(event, timestamp);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Report an event with the current elapsed time.
     *
     * @param event The event to report.
     * @hide
     */
    public void reportEvent(@Event int event) {
        reportEvent(event, SystemClock.elapsedRealtime());
    }
}
