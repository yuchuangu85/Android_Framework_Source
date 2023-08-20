/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.devicepolicy;

import android.app.admin.DeviceAdminInfo;
import android.content.ComponentName;

import com.android.internal.util.JournaledFile;

import java.util.List;
import java.util.function.Function;

/**
 * An interface for providing the {@code PolicyVersionUpgrader} with all the data necessary
 * to go through the upgrade process.
 */
public interface PolicyUpgraderDataProvider {
    /**
     * Returns the journaled policies file for a given user.
     */
    JournaledFile makeDevicePoliciesJournaledFile(int userId);

    /**
     * Returns the journaled policy version file for a given user.
     */
    JournaledFile makePoliciesVersionJournaledFile(int userId);

    /**
     * Returns a function which provides the component name and device admin info for a given
     * user.
     */
    Function<ComponentName, DeviceAdminInfo> getAdminInfoSupplier(int userId);

    /**
     * Returns the users to upgrade.
     */
    int[] getUsersForUpgrade();

    /**
     * Returns packages suspended by platform for a given user.
     */
    List<String> getPlatformSuspendedPackages(int userId);
}
