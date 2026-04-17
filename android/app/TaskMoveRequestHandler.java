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

package android.app;

import static android.Manifest.permission.REPOSITION_SELF_WINDOWS;
import static android.view.Display.INVALID_DISPLAY;

import android.annotation.CallbackExecutor;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.IRemoteCallback;
import android.os.OutcomeReceiver;
import android.os.RemoteException;

import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * This class holds utility constants used for handling {@link ActivityManager.AppTask#moveTaskTo}
 * requests and is responsible for client-side handling of server's responses.
 * @hide
 */
public class TaskMoveRequestHandler {
    @IntDef(prefix = { "RESULT_" }, value = {
            RESULT_APPROVED,
            RESULT_FAILED_BAD_STATE,
            RESULT_FAILED_UNABLE_TO_PLACE_TASK,
            RESULT_FAILED_NONEXISTENT_DISPLAY,
            RESULT_FAILED_BAD_BOUNDS,
            RESULT_FAILED_IMMOVABLE_TASK,
            RESULT_FAILED_NO_PERMISSIONS,
            RESULT_FAILED_BAL_POLICY_VIOLATION
    })
    public @interface RequestResult {}

    /**
     * The request has been ultimately approved.
     */
    public static final int RESULT_APPROVED = 0;

    /**
     * The request has been rejected because, broadly speaking, the system's window hierarchy
     * was in an inappropriate state to handle the request.
     */
    public static final int RESULT_FAILED_BAD_STATE = 1;

    /**
     * The request has been rejected because, broadly speaking, the target display was not able
     * to host the moved task due to security reasons.
     */
    public static final int RESULT_FAILED_UNABLE_TO_PLACE_TASK = 2;

    /**
     * The request has been rejected because the {@code displayId} provided does not point to a
     * valid display.
     */
    public static final int RESULT_FAILED_NONEXISTENT_DISPLAY = 3;

    /**
     * The request has been rejected because the bounds provided were irrecoverably invalid.
     */
    public static final int RESULT_FAILED_BAD_BOUNDS = 4;

    /**
     * The request has been rejected because the target task is marked as not movable.
     */
    public static final int RESULT_FAILED_IMMOVABLE_TASK = 5;

    /**
     * The request has been rejected because the caller hasn't had the
     * {@link android.Manifest.REPOSITION_SELF_WINDOWS} permission.
     */
    public static final int RESULT_FAILED_NO_PERMISSIONS = 6;

    /**
     * The request has been rejected because placing the task in question would violate the
     * Background Activity Launch policy.
     */
    public static final int RESULT_FAILED_BAL_POLICY_VIOLATION = 7;

    /**
     * The key used for specifying the final display ID of the task being moved in the
     * {@link android.os.Bundle} returned by the server.
     */
    public static final String REMOTE_CALLBACK_DISPLAY_ID_KEY = "display_id";

    /**
     * The key used for specifying the final bounds of the task being moved in the
     * {@link android.os.Bundle} returned by the server.
     */
    public static final String REMOTE_CALLBACK_BOUNDS_KEY = "bounds";

    /**
     * The key used for specifying the final result of a task moving request in the
     * {@link android.os.Bundle} returned by the server.
     */
    public static final String REMOTE_CALLBACK_RESULT_KEY = "result";

    @RequiresPermission(REPOSITION_SELF_WINDOWS)
    static void moveTaskTo(
            @NonNull TaskLocation location,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull OutcomeReceiver<TaskLocation, Exception> callback,
            @NonNull IAppTask appTaskImpl) {
        preValidateTaskMoveRequest(location, executor, callback);
        try {
            IRemoteCallback remoteCallback = new IRemoteCallback.Stub() {
                    @Override public void sendResult(Bundle res) {
                        int displayId = INVALID_DISPLAY;
                        Rect bounds = null;
                        int result = res.getInt(REMOTE_CALLBACK_RESULT_KEY);
                        if (result == RESULT_APPROVED) {
                            displayId = res.getInt(REMOTE_CALLBACK_DISPLAY_ID_KEY);
                            bounds = res.getParcelable(REMOTE_CALLBACK_BOUNDS_KEY, Rect.class);
                        }
                        notifyTaskMoveRequestResult(executor, callback, displayId, bounds, result);
                    }
            };
            String packageName = ActivityThread.currentPackageName();
            appTaskImpl.moveTaskTo(
                    location.getDisplayId(),
                    location.getBounds(),
                    remoteCallback,
                    packageName);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * At the moment this is a {@code void} method because all the errors we catch here warrant
     * throwing an exception back to the caller instead of passing the error message through the
     * callback provided by the caller.
     */
    private static void preValidateTaskMoveRequest(
            @NonNull TaskLocation location,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull OutcomeReceiver<TaskLocation, Exception> callback) {
        Objects.requireNonNull(location, "The location provided is null.");
        Objects.requireNonNull(executor, "The executor provided is null.");
        Objects.requireNonNull(callback, "The callback provided is null.");
    }

    private static void notifyTaskMoveRequestResult(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull OutcomeReceiver<TaskLocation, Exception> callback,
            int displayId,
            Rect bounds,
            int result) {
        switch (result) {
            case RESULT_APPROVED:
                executor.execute(() -> callback.onResult(
                        new TaskLocation(displayId, bounds)));
                break;
            case RESULT_FAILED_BAD_STATE:
                executor.execute(() -> callback.onError(new IllegalStateException(
                        "The windowing mode currently present at target screen is not feasible for"
                        + "placing freeform windows.")));
                break;
            case RESULT_FAILED_UNABLE_TO_PLACE_TASK:
                executor.execute(() -> callback.onError(new SecurityException(
                        "The task cannot be placed on the target display.")));
                break;
            case RESULT_FAILED_NONEXISTENT_DISPLAY:
                executor.execute(() -> callback.onError(new IllegalArgumentException(
                        "The target display does not exist.")));
                break;
            case RESULT_FAILED_BAD_BOUNDS:
                executor.execute(() -> callback.onError(new IllegalArgumentException(
                        "The target bounds were impossible to recover to valid ones.")));
                break;
            case RESULT_FAILED_IMMOVABLE_TASK:
                executor.execute(() -> callback.onError(new IllegalStateException(
                        "The target task had been marked as not movable.")));
                break;
            case RESULT_FAILED_NO_PERMISSIONS:
                executor.execute(() -> callback.onError(new SecurityException(
                        "The caller does not hold the permission to reposition its windows.")));
                break;
            case RESULT_FAILED_BAL_POLICY_VIOLATION:
                executor.execute(() -> callback.onError(new SecurityException(
                        "The request violates the Background Activity Launch policy.")));
                break;
            default:
                executor.execute(() -> callback.onError(new IllegalStateException(
                        "Unknown error.")));
        }
    }

    private TaskMoveRequestHandler() {}
}
