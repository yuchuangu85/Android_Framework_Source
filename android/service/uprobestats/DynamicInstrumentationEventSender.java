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
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.annotation.TestApi;
import android.content.ComponentName;
import android.content.Context;
import android.os.Bundle;
import android.os.RemoteException;
import android.uprobestats.mainline.flags.Flags;

import com.android.uprobestats.Entry;
import com.android.uprobestats.Event;
import com.android.uprobestats.IUprobeStatsBridgeService;
import com.android.uprobestats.Value;

import java.util.ArrayList;
import java.util.Objects;

/**
 * Utility class to sende dynamic instrumentation events.
 *
 * @hide
 */
@SystemApi
@FlaggedApi(Flags.FLAG_DYNAMIC_INSTRUMENTATION_EVENT_SERVICE)
@SystemService(Context.UPROBESTATS_BRIDGE_SERVICE)
public class DynamicInstrumentationEventSender {
    private static final String TAG = DynamicInstrumentationEventSender.class.getSimpleName();
    private final IUprobeStatsBridgeService mService;

    /** @hide */
    public DynamicInstrumentationEventSender(
            @NonNull Context context, @NonNull IUprobeStatsBridgeService service) {
        mService = Objects.requireNonNull(service);
    }

    @TestApi
    public boolean enableTestMode(@NonNull ComponentName component) {
        try {
            return mService.enableTestMode(component.getPackageName(), component.getClassName());
        } catch (RemoteException e) {
            e.rethrowAsRuntimeException();
        }
        return false;
    }

    @TestApi
    public boolean disableTestMode() {
        try {
            return mService.disableTestMode();
        } catch (RemoteException e) {
            e.rethrowAsRuntimeException();
        }
        return false;
    }

    @TestApi
    public boolean waitQueueFlushed() {
        try {
            return mService.waitQueueFlushed();
        } catch (RemoteException e) {
            e.rethrowAsRuntimeException();
        }
        return false;
    }

    /**
     * Send the {@link DynamicInstrumentationEvent} to deliver it to the registered {@link
     * DynamicInstrumentationEventService}. The event might be queued. The <code>flush</code>
     * parameter can be used to indicate that immediate delivery of this (and all previously queued)
     * events is desired.
     *
     * @param diEvent the event to be send.
     * @param flush if <code>true</code> this event (and all previously queued events) should be
     *     sent immediately.
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_DYNAMIC_INSTRUMENTATION_EVENT_SERVICE)
    public void sendEvent(@NonNull DynamicInstrumentationEvent diEvent, boolean flush) {
        Objects.requireNonNull(diEvent, "event must not be null");
        Event event = new Event();
        event.uid = diEvent.getUid();
        event.payloadId = diEvent.getPayloadId();
        event.timestampMs = diEvent.getTimestamp().toEpochMilli();
        event.payload = new ArrayList<>();
        Bundle payload = diEvent.getPayload();
        for (String key : payload.keySet()) {
            Object value = payload.get(key);
            Entry entry = new Entry();
            entry.key = key;
            entry.value = new Value();
            if (value == null) {
                // ignore
            } else if (value instanceof String) {
                entry.value.setStringValue((String) value);
            } else if (value instanceof Integer) {
                entry.value.setIntValue((Integer) value);
            } else {
                throw new IllegalArgumentException("cannot convert " + value.getClass());
            }
            event.payload.add(entry);
        }
        try {
            mService.enqueueEvent(event, flush);
        } catch (RemoteException e) {
            e.rethrowAsRuntimeException();
        }
    }
}
