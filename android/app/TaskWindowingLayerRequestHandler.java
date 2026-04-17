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

import static com.android.internal.annotations.VisibleForTesting.Visibility.PACKAGE;

import android.annotation.CallbackExecutor;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.os.Bundle;
import android.os.IRemoteCallback;
import android.os.OutcomeReceiver;
import android.os.RemoteException;

import android.util.Slog;
import com.android.internal.annotations.VisibleForTesting;

import java.util.concurrent.Executor;

/**
 * Class to encapsulate handling {@link ActivityManager.AppTask#requestWindowingLayer} requests and
 * related utilities.
 * @hide
 */
public class TaskWindowingLayerRequestHandler {

    private static final String TAG = "WindowingLayerRequest";


    /**
     * Internal result code for a windowing layer request.
     *
     * <p>This is translated into the final API result for the caller.
     */
    @IntDef(prefix = { "RESULT_" }, value = {
            RESULT_APPROVED,
            RESULT_FAILED_BAD_STATE,
            RESULT_FAILED_INSUFFICIENT_PERMISSIONS
    })
    public @interface Result {}

    /**
     * The key used for specifying the final result of a windowing layer request in the
     * {@link android.os.Bundle} returned by the server.
     */
    public static final String REMOTE_CALLBACK_RESULT_KEY = "result";

    /**
     * The request has been approved.
     *
     * <p>
     * The task layer has been changed accordingly to the request.
     */
    public static final int RESULT_APPROVED = 0;

    /**
     * The request has been rejected because the windowing system was in an inappropriate state to
     * handle the request.
     */
    public static final int RESULT_FAILED_BAD_STATE = 1;

    /**
     * The request has been rejected due to insufficient permissions.
     */
    public static final int RESULT_FAILED_INSUFFICIENT_PERMISSIONS = 2;

    /**
     * Requests the windowing layer via {@link IAppTask}.
     */
    @VisibleForTesting(visibility = PACKAGE)
    public static void requestWindowingLayer(
            @ActivityManager.AppTask.WindowingLayer int layer,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull OutcomeReceiver<Integer, Exception> callback,
            @NonNull IAppTask appTaskImpl) {
        try {
            appTaskImpl.requestWindowingLayer(layer, createRemoteCallback(executor, callback));
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    private static IRemoteCallback createRemoteCallback(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull OutcomeReceiver<Integer, Exception> callback
    ) {
        return new IRemoteCallback.Stub() {
            @Override
            public void sendResult(Bundle data) {
                final int result = data.getInt(REMOTE_CALLBACK_RESULT_KEY);
                switch (result) {
                    case RESULT_APPROVED:
                        executor.execute(() -> callback.onResult(
                                ActivityManager.AppTask.WINDOWING_LAYER_REQUEST_GRANTED));
                        break;
                    case RESULT_FAILED_BAD_STATE:
                        Slog.w(TAG, "The current system windowing state is not appropriate to "
                                + "fulfill the request.");
                        executor.execute(() -> callback.onResult(
                                ActivityManager.AppTask.WINDOWING_LAYER_REQUEST_REJECTED));
                        break;
                    case RESULT_FAILED_INSUFFICIENT_PERMISSIONS:
                        executor.execute(() -> callback.onError(new SecurityException(
                                "The caller does not hold sufficient permissions to request"
                                        + " provided windowing layer.")));
                        break;
                    default:
                        executor.execute(() -> callback.onError(new IllegalStateException(
                                "Unknown error, code=" + result)));
                        break;
                }
            }
        };
    }
}
