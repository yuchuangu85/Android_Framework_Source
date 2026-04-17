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

package android.app.appfunctions;

import static android.app.appfunctions.flags.Flags.FLAG_ENABLE_DYNAMIC_APP_FUNCTIONS;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.os.CancellationSignal;
import android.os.OutcomeReceiver;

/**
 * An interface for implementing the logic of an app function registered at runtime using {@link
 * AppFunctionManager#registerAppFunction}.
 */
@FlaggedApi(FLAG_ENABLE_DYNAMIC_APP_FUNCTIONS)
public interface AppFunction {
    /**
     * Called when the app function is invoked using {@link AppFunctionManager#executeAppFunction}.
     *
     * <p>The implementation should try to respect the provided {@link CancellationSignal}, if
     * possible.
     *
     * @param request The {@link ExecuteAppFunctionRequest} containing the parameters for this
     *     execution.
     * @param cancellationSignal A signal to cancel the execution.
     * @param callback The {@link OutcomeReceiver} that the implementation must use to return the
     *     result of the execution. It must be called exactly once with either a successful {@link
     *     ExecuteAppFunctionResponse} or an {@link AppFunctionException} in case of an error.
     */
    void onExecuteAppFunction(
            @NonNull ExecuteAppFunctionRequest request,
            @NonNull CancellationSignal cancellationSignal,
            @NonNull OutcomeReceiver<ExecuteAppFunctionResponse, AppFunctionException> callback);
}
