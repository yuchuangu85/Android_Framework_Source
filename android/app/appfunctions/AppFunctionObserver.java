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
import android.annotation.SuppressLint;

import java.util.Set;

/**
 * Interface for observing changes to app functions provided to {@link
 * AppFunctionManager#observeAppFunctions}.
 */
@FlaggedApi(FLAG_ENABLE_DYNAMIC_APP_FUNCTIONS)
@SuppressLint("CallbackName")
public interface AppFunctionObserver {
    /**
     * Called when changes occur to a package exposing app functions that may impact the state or
     * metadata of its contained functions.
     *
     * <p>This includes changes such as:
     *
     * <ul>
     *   <li>The definition of a function metadata exposed by the package has changed (e.g, the
     *       parameters of the function have changed).
     *   <li>All functions within the package are added or removed due to the package being
     *       installed or uninstalled.
     *   <li>The package's {@link AppFunctionPackageMetadata} has been updated.
     * </ul>
     *
     * <p>Upon receiving this notification, clients can call {@link
     * AppFunctionManager#searchAppFunctions} with {@link
     * AppFunctionSearchSpec.Builder#setPackageNames} to retrieve the updated {@link
     * AppFunctionMetadata} for affected functions.
     *
     * <p>Clients should call {@link AppFunctionManager#getAppFunctionStates} to retrieve the
     * latest {@link AppFunctionState} for packages affected by these changes.
     *
     * <p><strong>Note:</strong> If packages are reported to have changed but are
     * not returned from {@link AppFunctionManager#searchAppFunctions}, it means that the packages
     * have been uninstalled or no longer have functions.
     *
     * @param changedPackageNames The names of the updated packages.
     */
    void onAppFunctionMetadataChanged(@NonNull Set<String> changedPackageNames);

    /**
     * Called when the runtime state of one or more app functions changes.
     *
     * <p>Upon receiving this notification, clients can call {@link
     * AppFunctionManager#getAppFunctionStates} to retrieve the updated {@link AppFunctionState} for
     * the affected functions.
     *
     * @param changedFunctionNames The list of {@link AppFunctionName}s for the functions whose
     *     state has changed.
     */
    void onAppFunctionStatesChanged(@NonNull Set<AppFunctionName> changedFunctionNames);
}
