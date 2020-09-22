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
import android.media.AudioAttributes;
import android.media.AudioDeviceAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.Looper;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.util.Log;

import com.android.server.wifi.util.WifiHandler;
import com.android.wifi.resources.R;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.List;

/**
 * This class provides the Support for SAR to control WiFi TX power limits.
 * It deals with the following:
 * - Tracking the STA state through calls from  the ClientModeManager.
 * - Tracking the SAP state through calls from SoftApManager
 * - Tracking the Scan-Only state through ScanOnlyModeManager
 * - Tracking the state of the Cellular calls or data.
 * - It constructs the sar info and send it towards the HAL
 */
public class SarManager {
    // Period for checking on voice steam active (in ms)
    private static final int CHECK_VOICE_STREAM_INTERVAL_MS = 5000;

    /**
     * @hide constants copied over from {@link AudioManager}
     * TODO(b/144250387): Migrate to public API
     */
    private static final String STREAM_DEVICES_CHANGED_ACTION =
            "android.media.stream_devices_changed_action";
    private static final String EXTRA_VOLUME_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE";
    private static final String EXTRA_VOLUME_STREAM_DEVICES =
            "android.media.EXTRA_VOLUME_STREAM_DEVICES";
    private static final String EXTRA_PREV_VOLUME_STREAM_DEVICES =
            "android.media.EXTRA_PREV_VOLUME_STREAM_DEVICES";
    private static final int DEVICE_OUT_EARPIECE = 0x1;

    /* For Logging */
    private static final String TAG = "WifiSarManager";
    private boolean mVerboseLoggingEnabled = true;

    private SarInfo mSarInfo;

    /* Configuration for SAR support */
    private boolean mSupportSarTxPowerLimit;
    private boolean mSupportSarVoiceCall;
    private boolean mSupportSarSoftAp;

    // Device starts with screen on
    private boolean mScreenOn = false;
    private boolean mIsVoiceStreamCheckEnabled = false;

    /**
     * Other parameters passed in or created in the constructor.
     */
    private final Context mContext;
    private final TelephonyManager mTelephonyManager;
    private final AudioManager mAudioManager;
    private final WifiPhoneStateListener mPhoneStateListener;
    private final WifiNative mWifiNative;
    private final Handler mHandler;
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
        mAudioManager = mContext.getSystemService(AudioManager.class);
        mHandler = new WifiHandler(TAG, looper);
        mPhoneStateListener = new WifiPhoneStateListener(looper);
    }

    /**
     * Handle boot completed, read config flags.
     */
    public void handleBootCompleted() {
        readSarConfigs();
        if (mSupportSarTxPowerLimit) {
            mSarInfo = new SarInfo();
            setSarConfigsInInfo();
            registerListeners();
        }
    }

    /**
     * Notify SarManager of screen status change
     */
    public void handleScreenStateChanged(boolean screenOn) {
        if (!mSupportSarVoiceCall) {
            return;
        }

        if (mScreenOn == screenOn) {
            return;
        }

        if (mVerboseLoggingEnabled) {
            Log.d(TAG, "handleScreenStateChanged: screenOn = " + screenOn);
        }

        mScreenOn = screenOn;

        // Only schedule a voice stream check if screen is turning on, and it is currently not
        // scheduled
        if (mScreenOn && !mIsVoiceStreamCheckEnabled) {
            mHandler.post(() -> {
                checkAudioDevice();
            });

            mIsVoiceStreamCheckEnabled = true;
        }
    }

    private boolean isVoiceCallOnEarpiece() {
        final AudioAttributes voiceCallAttr = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .build();
        List<AudioDeviceAttributes> devices = mAudioManager.getDevicesForAttributes(voiceCallAttr);
        for (AudioDeviceAttributes device : devices) {
            if (device.getRole() == AudioDeviceAttributes.ROLE_OUTPUT
                    && device.getType() == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE) {
                return true;
            }
        }
        return false;
    }

    private boolean isVoiceCallStreamActive() {
        int mode = mAudioManager.getMode();
        return mode == AudioManager.MODE_IN_COMMUNICATION || mode == AudioManager.MODE_IN_CALL;
    }

    private void checkAudioDevice() {
        // First Check if audio stream is on
        boolean voiceStreamActive = isVoiceCallStreamActive();
        boolean earPieceActive;

        if (voiceStreamActive) {
            // Check on the audio route
            earPieceActive = isVoiceCallOnEarpiece();

            if (mVerboseLoggingEnabled) {
                Log.d(TAG, "EarPiece active = " + earPieceActive);
            }
        } else {
            earPieceActive = false;
        }

        // If audio route has changed, update SAR
        if (earPieceActive != mSarInfo.isEarPieceActive) {
            mSarInfo.isEarPieceActive = earPieceActive;
            updateSarScenario();
        }

        // Now should we proceed with the checks
        if (!mScreenOn && !voiceStreamActive) {
            // No need to continue checking
            mIsVoiceStreamCheckEnabled = false;
        } else {
            // Schedule another check
            mHandler.postDelayed(() -> {
                checkAudioDevice();
            }, CHECK_VOICE_STREAM_INTERVAL_MS);
        }
    }

    private void readSarConfigs() {
        mSupportSarTxPowerLimit = mContext.getResources().getBoolean(
                R.bool.config_wifi_framework_enable_sar_tx_power_limit);
        /* In case SAR is disabled,
           then all SAR inputs are automatically disabled as well (irrespective of the config) */
        if (!mSupportSarTxPowerLimit) {
            mSupportSarVoiceCall = false;
            mSupportSarSoftAp = false;
            return;
        }

        /* Voice calls are supported when SAR is supported */
        mSupportSarVoiceCall = true;

        mSupportSarSoftAp = mContext.getResources().getBoolean(
                R.bool.config_wifi_framework_enable_soft_ap_sar_tx_power_limit);
    }

    private void setSarConfigsInInfo() {
        mSarInfo.sarVoiceCallSupported = mSupportSarVoiceCall;
        mSarInfo.sarSapSupported = mSupportSarSoftAp;
    }

    private void registerListeners() {
        if (mSupportSarVoiceCall) {
            /* Listen for Phone State changes */
            registerPhoneStateListener();
            registerVoiceStreamListener();
        }
    }

    private void registerVoiceStreamListener() {
        Log.i(TAG, "Registering for voice stream status");

        // Register for listening to transitions of change of voice stream devices
        IntentFilter filter = new IntentFilter();
        filter.addAction(STREAM_DEVICES_CHANGED_ACTION);

        mContext.registerReceiver(
                new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        boolean voiceStreamActive = isVoiceCallStreamActive();
                        if (!voiceStreamActive) {
                            // No need to proceed, there is no voice call ongoing
                            return;
                        }

                        String action = intent.getAction();
                        int streamType =
                                intent.getIntExtra(EXTRA_VOLUME_STREAM_TYPE, -1);
                        int device = intent.getIntExtra(EXTRA_VOLUME_STREAM_DEVICES, -1);
                        int oldDevice = intent.getIntExtra(EXTRA_PREV_VOLUME_STREAM_DEVICES, -1);

                        if (streamType == AudioManager.STREAM_VOICE_CALL) {
                            boolean earPieceActive = mSarInfo.isEarPieceActive;
                            if (device == DEVICE_OUT_EARPIECE) {
                                if (mVerboseLoggingEnabled) {
                                    Log.d(TAG, "Switching to earpiece : HEAD ON");
                                    Log.d(TAG, "Old device = " + oldDevice);
                                }
                                earPieceActive = true;
                            } else if (oldDevice == DEVICE_OUT_EARPIECE) {
                                if (mVerboseLoggingEnabled) {
                                    Log.d(TAG, "Switching from earpiece : HEAD OFF");
                                    Log.d(TAG, "New device = " + device);
                                }
                                earPieceActive = false;
                            }

                            if (earPieceActive != mSarInfo.isEarPieceActive) {
                                mSarInfo.isEarPieceActive = earPieceActive;
                                updateSarScenario();
                            }
                        }
                    }
                }, filter, null, mHandler);
    }

    /**
     * Register the phone state listener.
     */
    private void registerPhoneStateListener() {
        Log.i(TAG, "Registering for telephony call state changes");
        mTelephonyManager.listen(
                mPhoneStateListener, PhoneStateListener.LISTEN_CALL_STATE);
    }

    /**
     * Update Wifi Client State
     */
    public void setClientWifiState(int state) {
        boolean newIsEnabled;
        /* No action is taken if SAR is not supported */
        if (!mSupportSarTxPowerLimit) {
            return;
        }

        if (state == WifiManager.WIFI_STATE_DISABLED) {
            newIsEnabled = false;
        } else if (state == WifiManager.WIFI_STATE_ENABLED) {
            newIsEnabled = true;
        } else {
            /* No change so exiting with no action */
            return;
        }

        /* Report change to HAL if needed */
        if (mSarInfo.isWifiClientEnabled != newIsEnabled) {
            mSarInfo.isWifiClientEnabled = newIsEnabled;
            updateSarScenario();
        }
    }

    /**
     * Update Wifi SoftAP State
     */
    public void setSapWifiState(int state) {
        boolean newIsEnabled;
        /* No action is taken if SAR is not supported */
        if (!mSupportSarTxPowerLimit) {
            return;
        }

        if (state == WifiManager.WIFI_AP_STATE_DISABLED) {
            newIsEnabled = false;
        } else if (state == WifiManager.WIFI_AP_STATE_ENABLED) {
            newIsEnabled = true;
        } else {
            /* No change so exiting with no action */
            return;
        }

        /* Report change to HAL if needed */
        if (mSarInfo.isWifiSapEnabled != newIsEnabled) {
            mSarInfo.isWifiSapEnabled = newIsEnabled;
            updateSarScenario();
        }
    }

    /**
     * Update Wifi ScanOnly State
     */
    public void setScanOnlyWifiState(int state) {
        boolean newIsEnabled;
        /* No action is taken if SAR is not supported */
        if (!mSupportSarTxPowerLimit) {
            return;
        }

        if (state == WifiManager.WIFI_STATE_DISABLED) {
            newIsEnabled = false;
        } else if (state == WifiManager.WIFI_STATE_ENABLED) {
            newIsEnabled = true;
        } else {
            /* No change so exiting with no action */
            return;
        }

        /* Report change to HAL if needed */
        if (mSarInfo.isWifiScanOnlyEnabled != newIsEnabled) {
            mSarInfo.isWifiScanOnlyEnabled = newIsEnabled;
            updateSarScenario();
        }
    }

    /**
     * Report Cell state event
     */
    private void onCellStateChangeEvent(int state) {
        boolean newIsVoiceCall;
        switch (state) {
            case CALL_STATE_OFFHOOK:
            case CALL_STATE_RINGING:
                newIsVoiceCall = true;
                break;

            case CALL_STATE_IDLE:
                newIsVoiceCall = false;
                break;

            default:
                Log.e(TAG, "Invalid Cell State: " + state);
                return;
        }

        /* Report change to HAL if needed */
        if (mSarInfo.isVoiceCall != newIsVoiceCall) {
            mSarInfo.isVoiceCall = newIsVoiceCall;

            if (mVerboseLoggingEnabled) {
                Log.d(TAG, "Voice Call = " + newIsVoiceCall);
            }
            updateSarScenario();
        }
    }

    /**
     * Enable/disable verbose logging.
     */
    public void enableVerboseLogging(int verbose) {
        if (verbose > 0) {
            mVerboseLoggingEnabled = true;
        } else {
            mVerboseLoggingEnabled = false;
        }
    }

    /**
     * dump()
     * Dumps SarManager state (as well as its SarInfo member variable state)
     */
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("Dump of SarManager");
        pw.println("isSarSupported: " + mSupportSarTxPowerLimit);
        pw.println("isSarVoiceCallSupported: " + mSupportSarVoiceCall);
        pw.println("isSarSoftApSupported: " + mSupportSarSoftAp);
        pw.println("");
        if (mSarInfo != null) {
            mSarInfo.dump(fd, pw, args);
        }
    }

    /**
     * Listen for phone call state events to set/reset TX power limits for SAR requirements.
     */
    private class WifiPhoneStateListener extends PhoneStateListener {
        WifiPhoneStateListener(Looper looper) {
            super(new HandlerExecutor(new Handler(looper)));
        }

        /**
         * onCallStateChanged()
         * This callback is called when a call state event is received
         * Note that this runs in the WifiCoreHandlerThread
         * since the corresponding Looper was passed to the WifiPhoneStateListener constructor.
         */
        @Override
        public void onCallStateChanged(int state, String incomingNumber) {
            Log.d(TAG, "Received Phone State Change: " + state);

            /* In case of an unsolicited event */
            if (!mSupportSarTxPowerLimit || !mSupportSarVoiceCall) {
                return;
            }
            onCellStateChangeEvent(state);
        }
    }

    /**
     * updateSarScenario()
     * Update HAL with the new SAR scenario if needed.
     */
    private void updateSarScenario() {
        if (!mSarInfo.shouldReport()) {
            return;
        }

        /* Report info to HAL*/
        if (mWifiNative.selectTxPowerScenario(mSarInfo)) {
            mSarInfo.reportingSuccessful();
        } else {
            Log.e(TAG, "Failed in WifiNative.selectTxPowerScenario()");
        }

        return;
    }
}
