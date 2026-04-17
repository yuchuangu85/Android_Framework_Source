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
 * distributed under
Loading...
 the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package android.service.uprobestats;

import static android.Manifest.permission.SEND_DYNAMIC_INSTRUMENTATION_EVENTS;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.PermissionManuallyEnforced;
import android.annotation.SystemApi;
import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.uprobestats.mainline.flags.Flags;

import com.android.uprobestats.Entry;
import com.android.uprobestats.Event;
import com.android.uprobestats.IUprobeStatsEventListener;
import com.android.uprobestats.Value;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A service that receives dynamic instrumentation events from the system.
 *
 * <p>To implement this service, extend this class and declare it in your AndroidManifest.xml.
 *
 * @hide
 * @see DynamicInstrumentationEvent
 */
@SystemApi
@FlaggedApi(Flags.FLAG_DYNAMIC_INSTRUMENTATION_EVENT_SERVICE)
public abstract class DynamicInstrumentationEventService extends Service {

    private final IUprobeStatsEventListener.Stub mBinder =
            new IUprobeStatsEventListener.Stub() {
                @Override
                @PermissionManuallyEnforced
                public void onEvent(@NonNull List<Event> events) {
                    enforceCallingPermission(
                            SEND_DYNAMIC_INSTRUMENTATION_EVENTS,
                            "Must have SEND_DYNAMIC_INSTRUMENTATION_EVENTS to send events");
                    // Calls the abstract method that the developer implements
                    ArrayList<DynamicInstrumentationEvent> convertedEvents =
                            new ArrayList<>(events.size());
                    for (int i = 0; i < events.size(); i++) {
                        convertedEvents.add(convertEvent(events.get(i)));
                    }
                    DynamicInstrumentationEventService.this.onEvent(convertedEvents);
                }

                private DynamicInstrumentationEvent convertEvent(Event event) {
                    Bundle bundle = new Bundle();
                    if (event.payload != null) {
                        for (int i = event.payload.size(); i-- > 0; ) {
                            Entry entry = event.payload.get(i);
                            String key = entry.key;
                            Value value = entry.value;
                            switch (value.getTag()) {
                                case Value.stringValue ->
                                        bundle.putString(key, value.getStringValue());
                                case Value.intValue -> bundle.putInt(key, value.getIntValue());
                                default -> throw new IllegalStateException("tag=" + value.getTag());
                            }
                        }
                    }
                    return new DynamicInstrumentationEvent(
                            event.uid,
                            Instant.ofEpochMilli(event.timestampMs),
                            event.payloadId,
                            bundle);
                }
            };

    @Override
    @NonNull
    public final IBinder onBind(@Nullable Intent intent) {
        return mBinder;
    }

    /**
     * The system calls this method when new protection log events are available.
     *
     * @param events A non-empty list of events that were detected.
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_DYNAMIC_INSTRUMENTATION_EVENT_SERVICE)
    public abstract void onEvent(@NonNull List<DynamicInstrumentationEvent> events);
}
