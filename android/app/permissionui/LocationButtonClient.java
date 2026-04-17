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
import android.annotation.SystemApi;
import android.permission.flags.Flags;

import java.util.concurrent.Executor;

/**
 * A callback interface for receiving events about the location button. Location button allow
 * users to grant precise location permission to an app.
 *
 * <p>An application must implement this interface to handle asynchronous events for
 * location button session, such as session creation, permission results, and errors. An
 * instance of this client is provided when calling {@link LocationButtonProvider#openSession}.
 *
 * <p>All callbacks on this interface are invoked serially on the {@link Executor} provided by
 * the client app.
 *
 * @see LocationButtonProvider
 * @see LocationButtonSession
 */
@FlaggedApi(Flags.FLAG_LOCATION_BUTTON_ENABLED)
public interface LocationButtonClient {

    /**
     * Intent action to request location permission when a user clicks a location button.
     * <p>
     * This intent is handled by PermissionController to grant temporary location access. The
     * intent is created by the system component rendering the button and started by the host
     * application.
     *
     * @hide
     */
    @SystemApi
    @FlaggedApi(android.permission.flags.Flags.FLAG_LOCATION_BUTTON_ENABLED)
    String ACTION_REQUEST_LOCATION_BUTTON_PERMISSIONS =
            "android.app.permissionui.action.REQUEST_LOCATION_BUTTON_PERMISSIONS";

    /**
     * Intent extra: The grant result of a permission request.
     * <p>
     * Type: boolean
     * </p>
     *
     * @hide
     */
    @SystemApi
    @FlaggedApi(android.permission.flags.Flags.FLAG_LOCATION_BUTTON_ENABLED)
    String EXTRA_PERMISSION_RESULT = "android.app.permissionui.extra.PERMISSION_RESULT";

    /**
     * Called when the location button session is successfully created.
     *
     * <p>The client is responsible for calling {@link LocationButtonSession#close()} on the
     * session when it is no longer needed to release system resources.
     *
     * @param session The newly created session.
     */
    void onSessionOpened(@NonNull LocationButtonSession session);

    /**
     * Callback for the result of a precise location permission grant after the user interacts
     * with the location button.
     *
     * <p>When a user clicks the location button for the first time, the location button consent
     * dialog is shown and this callback is invoked when users opt to share their location.
     * However, this callback is not invoked if the user chooses not to share their location.
     * In subsequent sessions after the initial consent, simply clicking on the location button
     * will invoke this callback.
     *
     * <p>The system will grant temporary precise location permission and is valid only for the
     * current session, this is similar to an "only this time" grant in permission request dialog.
     *
     * <p>In some cases, even after a user gives consent, precise location permission may not be
     * granted due to system-level restrictions. In this scenario, the callback will be invoked
     * with {@code isGranted = false}. It is recommended that the app handles this case gracefully
     * by informing the user that the app cannot obtain precise location permission (or that the
     * app can’t access location).
     *
     * @param isGranted {@code true} if the permission is granted.
     *                  {@code false} if the permission can't be granted.
     */
    void onPermissionResult(boolean isGranted);

    /**
     * Called when an error occurs either during the creation of the location button session
     * {@link LocationButtonProvider#openSession} or after the session is created.
     *
     * <p>This may happen if the remote service encounters an unrecoverable issue or the
     * connection is lost. The session is automatically closed before this callback is invoked, so
     * the application does not need to call {@link LocationButtonSession#close()}.
     *
     * @param cause The {@link Throwable} that describes the reason for the failure.
     */
    void onSessionError(@NonNull Throwable cause);
}
