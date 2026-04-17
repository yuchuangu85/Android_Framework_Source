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

package android.app.backup;

import android.annotation.NonNull;
import android.annotation.UserIdInt;
import android.app.backup.DelayedRestoreRequest;
import android.os.IBinder;

/**
 * Local system service interface for {@link com.android.server.backup.BackupManagerService}.
 *
 * @hide Only for use within the system server.
 */
public interface BackupManagerInternal {

    /**
     * Notifies the Backup Manager Service that an agent has become available. This
     * method is only invoked by the Activity Manager.
     */
    void agentConnectedForUser(String packageName, @UserIdInt int userId, IBinder agent);

    /**
     * Notify the Backup Manager Service that an agent has unexpectedly gone away.
     * This method is only invoked by the Activity Manager.
     */
    void agentDisconnectedForUser(String packageName, @UserIdInt int userId);

    /**
     * Triggers any previously scheduled delayed restore requests meeting the met conditions.
     *
     * @param userId            User id for which the action should be performed.
     * @param request           The DelayedRestoreRequest to trigger.
     * @hide
     */
    void onDelayedRestoreConditionMetForUser(int userId, DelayedRestoreRequest request);

    /**
     * Schedules a restore request that cannot be immediately met due to some external dependency.
     * For example, a part of restore for an application might depend on a different application
     * being installed first. The system will store this request and attempt to perform the restore
     * once the dependencies are met (e.g., the app is installed).
     *
     * @param userId User id for which the request should be scheduled.
     * @param request The DelayedRestoreRequest to schedule.
     * @return boolean indicating the success of the scheduling request.
     */
    boolean scheduleDelayedRestoreForUser(int userId, DelayedRestoreRequest request);

    /**
     * Clears any cached data for a delayed restore for the given user and package. This method is
     * called by the system when the cached data (used for delayed restore) of a package has
     * expired.
     *
     * @param userId The user id for which the cached data should be cleared.
     * @param packageName The package name for which the cached data should be cleared.
     * @hide
     */
    void onDelayedRestoreCachedDataExpiredForUser(int userId, @NonNull String packageName);
}
