/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.server.wifi;

import android.annotation.IntDef;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Arrays;
import java.util.List;

/**
 * Base class for available WiFi operating modes.
 *
 * Currently supported modes include Client, ScanOnly and SoftAp.
 */
public interface ActiveModeManager {
    /**
     * Listener for ActiveModeManager state changes.
     */
    interface Listener {
        /**
         * Invoked when mode manager completes start or on mode switch.
         */
        void onStarted();
        /**
         * Invoked when mode manager completes stop.
         */
        void onStopped();
        /**
         * Invoked when mode manager encountered a failure on start or on mode switch.
         */
        void onStartFailure();
    }

    /**
     * Method used to start the Manager for a given Wifi operational mode.
     */
    void start();

    /**
     * Method used to stop the Manager for a given Wifi operational mode.
     */
    void stop();

    /**
     * Method used to indicate if the mode manager is still stopping.
     */
    boolean isStopping();

    /** Roles assigned to each mode manager. */
    int ROLE_UNSPECIFIED = -1;
    // SoftApManager - Tethering, will respond to public APIs.
    int ROLE_SOFTAP_TETHERED = 0;
    // SoftApManager - Local only hotspot.
    int ROLE_SOFTAP_LOCAL_ONLY = 1;
    // ClientModeManager, primary STA, will respond to public APIs
    int ROLE_CLIENT_PRIMARY = 2;
    // ClientModeManager, secondary STA, can switch to primary later.
    int ROLE_CLIENT_SECONDARY = 3;
    // ClientModeManager, secondary STA created for local connection (no internet connectivity).
    int ROLE_CLIENT_LOCAL_ONLY = 4;
    // ClientModeManager, STA created for scans only.
    int ROLE_CLIENT_SCAN_ONLY = 5;

    @IntDef(prefix = { "ROLE_" }, value = {
            ROLE_SOFTAP_TETHERED,
            ROLE_SOFTAP_LOCAL_ONLY,
            ROLE_CLIENT_PRIMARY,
            ROLE_CLIENT_SECONDARY,
            ROLE_CLIENT_LOCAL_ONLY,
            ROLE_CLIENT_SCAN_ONLY
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface Role{}

    /** List of Client roles */
    List<Integer> CLIENT_ROLES = Arrays.asList(
            ROLE_CLIENT_PRIMARY,
            ROLE_CLIENT_SECONDARY,
            ROLE_CLIENT_LOCAL_ONLY,
            ROLE_CLIENT_SCAN_ONLY);
    /** List of Client roles that could initiate a wifi connection */
    List<Integer> CLIENT_CONNECTIVITY_ROLES = Arrays.asList(
            ROLE_CLIENT_PRIMARY,
            ROLE_CLIENT_SECONDARY,
            ROLE_CLIENT_LOCAL_ONLY);
    /** List of Client roles that could initiate a wifi connection for internet connectivity */
    List<Integer> CLIENT_INTERNET_CONNECTIVITY_ROLES = Arrays.asList(
            ROLE_CLIENT_PRIMARY,
            ROLE_CLIENT_SECONDARY);
    /** List of SoftAp roles */
    List<Integer> SOFTAP_ROLES = Arrays.asList(
            ROLE_SOFTAP_LOCAL_ONLY,
            ROLE_SOFTAP_TETHERED);

    /**
     * Method to get the role for a mode manager.
     */
    @Role int getRole();

    /**
     * Method to set the role for a mode manager.
     */
    void setRole(@Role int role);

    /**
     * Method to dump for logging state.
     */
    void dump(FileDescriptor fd, PrintWriter pw, String[] args);
}
