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

package android.companion.virtual.computercontrol;

import android.annotation.NonNull;
import android.os.UserHandle;

import java.util.List;

/**
 * Listener to get notified when the packages being automated within a
 * {@link ComputerControlSession} changes.
 *
 * @hide
 */
// TODO(b/442624418): Move to LauncherApps.Callback
public interface AutomatedPackageListener {
    /**
     * Called when the set of automated packages for a specific user and session owner has changed.
     *
     * @param automatingPackage The name of the package that owns the {@link ComputerControlSession}
     * @param automatedPackages The names of the packages that are being automated. May be empty,
     *   indicating that automation has stopped for all previously automated packages for this
     *   session owner and user.
     * @param user The UserHandle of the profile of the automated packages.
     */
    void onAutomatedPackagesChanged(
            @NonNull String automatingPackage,
            @NonNull List<String> automatedPackages,
            @NonNull UserHandle user);
}
