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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.pm.SignedPackage;

import java.util.List;
import java.util.Set;

/**
 * @hide
 */
public interface AppFunctionAccessServiceInterface {

    /** @see AppFunctionManager#getAppFunctionAccessRequestState(String, String)  */
    @AppFunctionManager.AppFunctionAccessState
    int getAccessRequestState(@NonNull String agentPackageName, int agentUserId,
            @NonNull String targetPackageName, int targetUserId);

    /** @see AppFunctionManager#getAccessFlags(String, String)  */
    @AppFunctionManager.AppFunctionAccessFlags
    int getAccessFlags(@NonNull String agentPackageName, int agentUserId,
            @NonNull String targetPackageName, int targetUserId);

    /** @see AppFunctionManager#updateAccessFlags(String, String, int, int)  */
    boolean updateAccessFlags(@NonNull String agentPackageName, int agentUserId,
            @NonNull String targetPackageName, int targetUserId,
            @AppFunctionManager.AppFunctionAccessFlags int flagMask,
            @AppFunctionManager.AppFunctionAccessFlags int flags) throws IllegalArgumentException;

    /** @see AppFunctionManager#revokeSelfAccess(String) */
    void revokeSelfAccess(@NonNull String targetPackageName);

    /** Set the agent allowlist */
    void setAgentAllowlist(@Nullable Set<SignedPackage> agentAllowlist);

    /** @see AppFunctionManager#getValidAgents() */
    @NonNull
    List<String> getValidAgents(int userId);

    /** @see AppFunctionManager#getValidTargets(String) () */
    @NonNull
    List<String> getValidTargets(int userId);

    /** Should be called whenever a user starts */
    void onUserStarting(int userId);

    /** Add a change listener */
    void addOnAccessChangedListener(@NonNull IOnAppFunctionAccessChangeListener listener,
            int userId);

    /** Remove a change listener */
    void removeOnAccessChangedListener(@NonNull IOnAppFunctionAccessChangeListener listener,
            int userId);
}
