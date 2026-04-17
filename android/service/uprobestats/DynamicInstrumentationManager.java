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
package android.service.uprobestats;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.annotation.TestApi;
import android.content.ComponentName;
import android.content.Context;
import android.util.Slog;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;


/**
 * Utility methods for the dynamic instrumentation feature.
 *
 * This class provides access to the system-wide configuration for the service
 * that consumes dynamic instrumentation events.
 *
 * Even though this is for usage from a mainline module that runs as system server, it needs to
 * expose the API to SystemApi.Client.MODULE_LIBRARIES.
 *
 * @hide
 */
@SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
@TestApi
@FlaggedApi(android.security.Flags.FLAG_DYNAMIC_INSTRUMENTATION_API)
@SystemService(Context.DYNAMIC_INSTRUMENTATION_SERVICE)
public final class DynamicInstrumentationManager {

    private static final String TAG = DynamicInstrumentationManager.class.getSimpleName();
    private static final Object sLock = new Object();
    @GuardedBy("sLock")
    private static volatile ComponentName sDynamicInstrumentationEventConsumer;
    private final Context mContext;

    /** @hide */
    public DynamicInstrumentationManager(@NonNull Context context) {
        mContext = context;
    }

    /**
     * Get the {@link ComponentName} of the service designated to receive dynamic
     * instrumentation events.
     *
     * <p>The Dynamic Instrumentation Consumer is a trusted, pre-installed system service
     * that processes events reported by instrumented processes.
     * The component name is configured at build-time by OEMs via the
     * {@code R.string.config_dynamicInstrumentationEventConsumer} resource overlay.
     *
     * <p>If the configuration string is empty or does not point to a valid component,
     * this method will return {@code null}, and instrumentation events will be discarded.
     *
     * @return The {@link ComponentName} of the configured consumer service, or {@code null} if
     * none is configured or the configured value is invalid.
     * @hide
     */
    @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    @TestApi
    @FlaggedApi(android.security.Flags.FLAG_DYNAMIC_INSTRUMENTATION_API)
    @Nullable
    public ComponentName getDynamicInstrumentationEventConsumer() {
        if (sDynamicInstrumentationEventConsumer == null) {
            synchronized (sLock) {
                if (sDynamicInstrumentationEventConsumer == null) {
                    final String componentNameString = mContext.getString(
                            R.string.config_dynamicInstrumentationEventConsumer);
                    // An empty or invalid string will correctly result in null.
                    sDynamicInstrumentationEventConsumer =
                            ComponentName.unflattenFromString(componentNameString);
                }
            }
        }
        return sDynamicInstrumentationEventConsumer;
    }

    /**
     * Set the {@link ComponentName} of the DynamicInstrumentationConsumer.
     *
     * This is expected to only be used in tests as OEMs will set the value in a resource overlay.
     *
     * @hide
     */
    @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    @TestApi
    @FlaggedApi(android.security.Flags.FLAG_DYNAMIC_INSTRUMENTATION_API)
    public void setDynamicInstrumentationEventConsumer(@Nullable ComponentName componentName) {
        synchronized (sLock) {
            Slog.w(TAG, "Overriding dynamic instrumentation consumer for test. New value: "
                    + componentName);
            sDynamicInstrumentationEventConsumer = componentName;
        }
    }

}
