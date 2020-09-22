/*
 * Copyright (C) 2018 The Android Open Source Project
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

import java.io.FileDescriptor;
import java.io.PrintWriter;

/**
 * This class represents the list of SAR inputs that will be used to select the proper
 * power profile.
 * This includes:
 *  - Is there an ongoing voice call
 *  - Is SoftAP active
 * It also contains info about state of the other Wifi modes
 *  - Client mode (Sta)
 *  - ScanOnly mode
 * It also keeps history for the reporting of SAR states/scenario to avoid unnecessary reporting
 *  - keeps track of the last reported states
 *  - keeps track of the last reported SAR scenario
 *  - keeps track of if all wifi modes were disabled (no reporting should happen then)
 */
public class SarInfo {
    /**
     * This value is used as an initial value for the last reported scenario
     * It is intended to be different than all valid SAR scenario values (including the
     * reset value).
     * Using this to initialize the lastReportedScenario results in that the first scenario
     * (including reset) would be reported.
     */
    public static final int INITIAL_SAR_SCENARIO = -2;

    /**
     * This value is used for the reset scenario (no TX Power backoff)
     * Valid scenario values only include scenarios with Tx Power backoff,
     * so we need this one to represent the "No backoff" case.
     */
    public static final int RESET_SAR_SCENARIO = -1;

    /* For Logging */
    private static final String TAG = "WifiSarInfo";

    /* SAR support configs */
    public boolean sarVoiceCallSupported;
    public boolean sarSapSupported;

    public boolean isWifiClientEnabled = false;
    public boolean isWifiSapEnabled = false;
    public boolean isWifiScanOnlyEnabled = false;
    public boolean isVoiceCall = false;
    public boolean isEarPieceActive = false;
    public int attemptedSarScenario = RESET_SAR_SCENARIO;

    private boolean mAllWifiDisabled = true;

    /* Variables representing the last successfully reported values to hal */
    private boolean mLastReportedIsWifiSapEnabled = false;
    private boolean mLastReportedIsVoiceCall = false;
    private boolean mLastReportedIsEarPieceActive = false;
    private int mLastReportedScenario = INITIAL_SAR_SCENARIO;
    private long mLastReportedScenarioTs = 0;

    /**
     * shouldReport()
     * This method returns false in the following cases:
     * 1. If all Wifi modes are disabled.
     * 2. Values contributing to the SAR scenario selection have not changed
     *    since last successful reporting.
     *
     * Special cases to allow for devices that require setting the SAR scenario value
     * when the chip comes up (initial startup, or during operation)
     * 1. This method would report true even with unchanged values from last reporting,
     *    if any wifi mode is just enabled after all wifi modes were disabled.
     * 2. This method would report true the first time it is called with any wifi mode enabled.
     */
    public boolean shouldReport() {
        /* Check if all Wifi modes are disabled */
        if (!isWifiClientEnabled && !isWifiSapEnabled && !isWifiScanOnlyEnabled) {
            mAllWifiDisabled = true;
            return false;
        }

        /* Check if Wifi was all disabled before this call */
        if (mAllWifiDisabled) {
            return true;
        }

        /* Check if some change happened since last successful reporting */
        return ((isWifiSapEnabled != mLastReportedIsWifiSapEnabled)
                || (isVoiceCall != mLastReportedIsVoiceCall)
                || (isEarPieceActive != mLastReportedIsEarPieceActive));
    }

    /**
     * reportingSuccessful()
     * This method is called when reporting SAR scenario is fully successful
     * This results in caching the last reported inputs for future comparison.
     */
    public void reportingSuccessful() {
        mLastReportedIsWifiSapEnabled = isWifiSapEnabled;
        mLastReportedIsVoiceCall = isVoiceCall;
        mLastReportedIsEarPieceActive = isEarPieceActive;
        mLastReportedScenario = attemptedSarScenario;
        mLastReportedScenarioTs = System.currentTimeMillis();

        mAllWifiDisabled = false;
    }

    /**
     *  resetSarScenarioNeeded()
     *  Returns true if a call towards HAL to reset SAR scenario would be necessary.
     *  Returns false if the last call to HAL was already a reset, and hence
     *  another call to reset the SAR scenario would be redundant.
     */
    public boolean resetSarScenarioNeeded() {
        return setSarScenarioNeeded(RESET_SAR_SCENARIO);
    }

    /**
     * setSarScenarioNeeded()
     * Returns true if a call towards HAL to set SAR scenario to that value would be
     * necessary. This happens in the following cases:
     *   1. All Wifi modes were disabled, hence we need to init the SAR scenario value.
     *   2. The new scenario is different from the last reported one.
     *
     * Returns false if the last call to HAL was to set the scenario to that value, hence,
     * another call to set the SAR scenario to the same value would be redundant.
     */
    public boolean setSarScenarioNeeded(int scenario) {
        attemptedSarScenario = scenario;

        if (mAllWifiDisabled || (mLastReportedScenario != scenario)) {
            return true;
        }
        return false;
    }

    /**
     * dump()
     * Dumps the state of SarInfo
     */
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("Dump of SarInfo");
        pw.println("Current values:");
        pw.println("    Voice Call state is: " + isVoiceCall);
        pw.println("    Wifi Client state is: " + isWifiClientEnabled);
        pw.println("    Wifi Soft AP state is: " + isWifiSapEnabled);
        pw.println("    Wifi ScanOnly state is: " + isWifiScanOnlyEnabled);
        pw.println("    Earpiece state is : " + isEarPieceActive);
        pw.println("Last reported values:");
        pw.println("    Soft AP state is: " + mLastReportedIsWifiSapEnabled);
        pw.println("    Voice Call state is: " + mLastReportedIsVoiceCall);
        pw.println("    Earpiece state is: " + mLastReportedIsEarPieceActive);
        pw.println("Last reported scenario: " + mLastReportedScenario);
        pw.println("Reported " +  (System.currentTimeMillis() - mLastReportedScenarioTs) / 1000
                + " seconds ago");
    }
}
