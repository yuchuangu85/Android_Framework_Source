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

/**
 * Result of {@link AppFunctionManager#observeAppFunctions}.
 *
 * <p>The caller should retain a reference to this object and call {@link #cancel} when receiving
 * updates to app functions is no longer required.
 */
@FlaggedApi(FLAG_ENABLE_DYNAMIC_APP_FUNCTIONS)
public interface AppFunctionObservation {
    /**
     * Stops the observation previously started using {@link AppFunctionManager#observeAppFunctions}
     * from receiving updates.
     *
     * <p>If the observation is already canceled, this method has no effect.
     */
    void cancel();
}
