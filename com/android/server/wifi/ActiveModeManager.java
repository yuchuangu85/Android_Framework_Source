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

/**
 * Base class for available WiFi operating modes.
 *
 * Currently supported modes include Client, ScanOnly and SoftAp.
 */
public interface ActiveModeManager {
    /**
     * Method used to start the Manager for a given Wifi operational mode.
     */
    void start();

    /**
     * Method used to stop the Manager for a given Wifi operational mode.
     */
    void stop();


    /** Scan Modes */
    int SCAN_NONE = 0;
    int SCAN_WITHOUT_HIDDEN_NETWORKS = 1;
    int SCAN_WITH_HIDDEN_NETWORKS = 2;

    @IntDef({SCAN_NONE, SCAN_WITHOUT_HIDDEN_NETWORKS, SCAN_WITH_HIDDEN_NETWORKS})
    @Retention(RetentionPolicy.SOURCE)
    @interface ScanMode{}

    /**
     * Method to get the scan mode for a given Wifi operation mode.
     */
    @ScanMode int getScanMode();

    /**
     * Method to dump for logging state.
     */
    void dump(FileDescriptor fd, PrintWriter pw, String[] args);
}
