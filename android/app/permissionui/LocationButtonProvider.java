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
package android.app.permissionui;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.app.Activity;
import android.os.IBinder;
import android.permission.flags.Flags;

import java.util.concurrent.Executor;

/**
 * The primary entry point for the Location Button API.
 *
 * <p>This interface allows an application to request a  system-rendered button that, when
 * tapped, grants the app  temporary/one-time permission to access the user's precise location.
 * It provides a trusted and standardized way for users to share their location for a specific task.
 *
 * <p>Instances of this interface are obtained from {@link LocationButtonProviderFactory}.
 *
 * @see LocationButtonProviderFactory
 * @see LocationButtonSession
 * @see LocationButtonClient
 */
@FlaggedApi(Flags.FLAG_LOCATION_BUTTON_ENABLED)
public interface LocationButtonProvider {

    /**
     * Asynchronously requests a new Location Button session.
     *
     * <p>This method initiates a request to the system to create a location button. The
     * button's UI is rendered in a separate process and embedded within the application's view
     * hierarchy. User interaction with the button is handled securely by the system.
     *
     * <p>The outcome of this request, including the created {@link LocationButtonSession} or any
     * errors, is delivered asynchronously to the provided {@link LocationButtonClient}.
     *
     * @param activity Host app activity which launch permission activity in its task.
     * @param hostToken A token for the host window.
     * @param displayId The ID of the display where the button will appear.
     * @param request A {@link LocationButtonRequest} defining the button's appearance.
     * @param clientExecutor The {@link Executor} on which to invoke all client callbacks.
     * @param client The {@link LocationButtonClient} to receive session events and results.
     */
    void openSession(
            @NonNull Activity activity,
            @NonNull IBinder hostToken,
            int displayId,
            @NonNull LocationButtonRequest request,
            @NonNull Executor clientExecutor,
            @NonNull LocationButtonClient client);
}
