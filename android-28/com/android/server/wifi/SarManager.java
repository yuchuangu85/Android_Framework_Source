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

import static android.telephony.TelephonyManager.CALL_STATE_IDLE;
import static android.telephony.TelephonyManager.CALL_STATE_OFFHOOK;
import static android.telephony.TelephonyManager.CALL_STATE_RINGING;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.WifiManager;
import android.os.Looper;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.util.Log;

import com.android.internal.R;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.List;

/**
 * This class provides the Support for SAR to control WiFi TX power limits.
 * It deals with the following:
 * - Tracking the STA state through calls from  the ClientModeManager.
 * - Tracking the state of the Cellular calls or data.
 * - Based on above, selecting the SAR profile to use and programming it in wifi hal.
 */
public class SarManager {

    /* For Logging */
    private static final String TAG = "WifiSarManager";
    private boolean mVerboseLoggingEnabled = true;

    /* Configuration for SAR */
    private boolean mEnableSarTxPowerLimit;

    /* Current SAR Scenario */
    private int mCurrentSarScenario = WifiNative.TX_POWER_SCENARIO_NORMAL;

    /* Booleans for Cell and wifi states */
    private boolean mCellOn = false;
    private boolean mWifiStaEnabled = false;
    /**
     * Other parameters passed in or created in the constructor.
     */
    private final Context mContext;
    private final TelephonyManager mTelephonyManager;
    private final WifiPhoneStateListener mPhoneStateListener;
    private final WifiNative mWifiNative;
    private final Looper mLooper;

    /**
     * Create new instance of SarManager.
     */
    SarManager(Context context,
               TelephonyManager telephonyManager,
               Looper looper,
               WifiNative wifiNative) {
        mContext = context;
        mTelephonyManager = telephonyManager;
        mWifiNative = wifiNative;
        mLooper = looper;
        mPhoneStateListener = new WifiPhoneStateListener(looper);

        registerListeners();
    }

    /**
     * Starts the SAR Manager by initializing the different listeners
     */
    private void registerListeners() {
        /* First read the configuration for SAR Support */
        mEnableSarTxPowerLimit = mContext.getResources().getBoolean(
                R.bool.config_wifi_framework_enable_voice_call_sar_tx_power_limit);

        /* Only Start listening for events if SAR is enabled */
        if (mEnableSarTxPowerLimit) {
            Log.d(TAG, "Registering Listeners for the SAR Manager");

            /* Listen for Phone State changes */
            registerPhoneListener();
        }
    }

    /**
     * Report Cell state event
     */
    private void onCellStateChangeEvent(int state) {
        boolean currentCellOn = mCellOn;

        switch (state) {
            case CALL_STATE_OFFHOOK:
            case CALL_STATE_RINGING:
                mCellOn = true;
                break;

            case CALL_STATE_IDLE:
                mCellOn = false;
                break;

            default:
                Log.e(TAG, "Invalid Cell State: " + state);
        }

        if (mCellOn != currentCellOn) {
            updateSarScenario();
        }
    }

    /**
     * Update Wifi Client State
     */
    public void setClientWifiState(int state) {
        /* No action is taken if SAR is not enabled */
        if (!mEnableSarTxPowerLimit) return;

        if (state == WifiManager.WIFI_STATE_DISABLED && mWifiStaEnabled) {
            mWifiStaEnabled = false;
        } else if (state == WifiManager.WIFI_STATE_ENABLED && !mWifiStaEnabled) {
            mWifiStaEnabled = true;

            /* Since no wifi interface was up,
               time for SAR scenario to take effect */
            sendTxPowerScenario(mCurrentSarScenario);
        }
    }

    /**
     * Enable/disable verbose logging.
     */
    public void enableVerboseLogging(int verbose) {
        Log.d(TAG, "Inside enableVerboseLogging: " + verbose);
        if (verbose > 0) {
            mVerboseLoggingEnabled = true;
        } else {
            mVerboseLoggingEnabled = false;
        }
    }

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("*** WiFi SAR Manager Dump ***");
        pw.println("Current SAR Scenario is " + scenarioToString(mCurrentSarScenario));
    }

    /**
     * Register the phone listener.
     */
    private void registerPhoneListener() {
        Log.i(TAG, "Registering for telephony call state changes");
        mTelephonyManager.listen(
                mPhoneStateListener, PhoneStateListener.LISTEN_CALL_STATE);
    }

    /**
     * Listen for phone call state events to set/reset TX power limits for SAR requirements.
     */
    private class WifiPhoneStateListener extends PhoneStateListener {
        WifiPhoneStateListener(Looper looper) {
            super(looper);
        }

        @Override
        public void onCallStateChanged(int state, String incomingNumber) {
            Log.d(TAG, "Received Phone State Change: " + state);

            /* In case of an unsolicited event */
            if (!mEnableSarTxPowerLimit) return;

            onCellStateChangeEvent(state);
        }
    }

    /**
     * update the Current SAR Scenario based on factors including:
     * - Do we have an ongoing cellular voice call.
     */
    private void updateSarScenario() {
        int newSarScenario;

        if (mCellOn) {
            newSarScenario = WifiNative.TX_POWER_SCENARIO_VOICE_CALL;
        } else {
            newSarScenario = WifiNative.TX_POWER_SCENARIO_NORMAL;
        }

        if (newSarScenario != mCurrentSarScenario) {

            // Only update HAL with new scenario if WiFi interface is enabled
            if (mWifiStaEnabled) {
                Log.d(TAG, "Sending SAR Scenario #" + scenarioToString(newSarScenario));
                sendTxPowerScenario(newSarScenario);
            }

            mCurrentSarScenario = newSarScenario;
        }
    }

    /**
     * sendTxPowerScenario()
     * Update HAL with the new power scenario.
     */
    private void sendTxPowerScenario(int newSarScenario) {
        if (!mWifiNative.selectTxPowerScenario(newSarScenario)) {
            Log.e(TAG, "Failed to set TX power scenario");
        }
    }

    /**
     * Convert SAR Scenario to string
     */
    private String scenarioToString(int scenario) {
        String str;
        switch(scenario) {
            case WifiNative.TX_POWER_SCENARIO_NORMAL:
                str =  "TX_POWER_SCENARIO_NORMAL";
                break;
            case WifiNative.TX_POWER_SCENARIO_VOICE_CALL:
                str = "TX_POWER_SCENARIO_VOICE_CALL";
                break;
            default:
                str = "Invalid Scenario";
                break;
        }

        return str;
    }
}
