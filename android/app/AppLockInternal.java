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

import android.annotation.NonNull;
import android.util.SparseArray;

import java.util.Set;

/**
 * App Lock local system service interface.
 *
 * @hide Only for use within the system server.
 */
public interface AppLockInternal {

    /**
     * Returns a {@link SparseArray} where the key is the userId and the value is a {@link Set}
     * of package names for which App Lock is enabled. The data returned by this method is only
     * reliable after {@link com.android.server.SystemService#PHASE_SYSTEM_SERVICES_READY}, as the
     * initial list of App Lock enabled packages is populated during that phase.
     */
    @NonNull
    SparseArray<Set<String>> getAppLockEnabledPackages();

    /**
     * Queries whether App Lock is enabled for the given package.
     *
     * @param packageName The package name to check for App Lock enabled state.
     * @param userId      The user Id to check the package for App Lock enabled state.
     * @return {@code true} if App Lock is enabled, {@code false} otherwise.
     */
    boolean isPackageAppLockEnabled(@NonNull String packageName, int userId);

    /**
     * Queries whether the given package is currently in a locked state.
     *
     * <p>A package is considered locked if App Lock is enabled for it and the conditions for being
     * unlocked are not met. After a successful user authentication (with device credentials or
     * Class 3 biometrics) to bypass App Lock, an unlocked state can be granted while the
     * application is in the foreground, or within a short grace period after the application's
     * foreground state is changed due to quick app switching.
     *
     * <p><b>Important</b>: This method holds the ActivityManagerService lock. Callers should be
     * mindful of not causing a deadlock.
     *
     * @param packageName The package name to check for App Lock locked state.
     * @param userId      The user Id to check the package for App Lock locked state.
     * @return {@code true} if the package is currently locked by App Lock, {@code false} otherwise.
     */
    boolean isPackageLocked(@NonNull String packageName, int userId);

    /**
     * Reports a successful user authentication for an App Lock enabled package, which transitions
     * the package to an unlocked state.
     *
     * <p>This method should be called after a user successfully authenticates using their device
     * credentials or Class 3 biometrics to bypass App Lock for a specific package. This allows
     * the package to be considered unlocked while it is in the foreground, or for a short grace
     * period after it is no longer in the foreground.
     *
     * <p>To improve user experience in multi-window scenarios, e.g. split screen and freeform
     * windows, this method also retrieves all packages that have currently visible App Lock
     * overlays and marks them as successfully authenticated. This prevents the user from having to
     * authenticate multiple times for apps that are on the screen simultaneously.
     *
     * @param packageName The name of the package that was successfully authenticated.
     * @param userId      The user Id for whom the authentication occurred.
     * @throws SecurityException if the caller is not the system.
     */
    void setAppLockEnabledPackageSuccessfullyAuthenticated(@NonNull String packageName, int userId);

    /**
     * Registers a {@link PackageLockedStateListener} for changes in the current App Lock locked
     * state of packages.
     *
     * @param listener {@link PackageLockedStateListener} to register
     */
    void registerPackageLockedStateListener(@NonNull PackageLockedStateListener listener);

    /**
     * Unregisters a {@link PackageLockedStateListener} for changes in the current App Lock
     * locked state of packages.
     *
     * @param listener {@link PackageLockedStateListener} to unregister
     */
    void unregisterPackageLockedStateListener(@NonNull PackageLockedStateListener listener);

    /**
     * Listener for changes in the App Lock locked state of packages.
     */
    interface PackageLockedStateListener {

        /**
         * Callback for when the App Lock locked state of a package changes
         *
         * @param packageName the package whose App Lock locked state has changed.
         * @param userId      the user Id whose App Lock locked state has changed.
         * @param locked      the new locked state of App Lock for the package.
         */
        void onPackageLockedStateChanged(@NonNull String packageName, int userId, boolean locked);
    }
}
