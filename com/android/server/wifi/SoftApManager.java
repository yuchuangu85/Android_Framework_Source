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

import static com.android.server.wifi.util.ApConfigUtil.ERROR_GENERIC;
import static com.android.server.wifi.util.ApConfigUtil.ERROR_NO_CHANNEL;
import static com.android.server.wifi.util.ApConfigUtil.SUCCESS;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.InterfaceConfiguration;
import android.net.LinkAddress;
import android.net.NetworkUtils;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.os.INetworkManagementService;
import android.os.Looper;
import android.os.Message;
import android.util.Log;

import com.android.internal.util.State;
import com.android.internal.util.StateMachine;
import com.android.server.wifi.util.ApConfigUtil;

import java.util.ArrayList;
import java.util.Locale;

/**
 * Manage WiFi in AP mode.
 * The internal state machine runs under "WifiStateMachine" thread context.
 */
public class SoftApManager {
    private static final String TAG = "SoftApManager";

    private final Context mContext;
    private final INetworkManagementService mNmService;
    private final WifiNative mWifiNative;
    private final ConnectivityManager mConnectivityManager;
    private final ArrayList<Integer> mAllowed2GChannels;

    private final String mCountryCode;

    private final String mInterfaceName;
    private String mTetherInterfaceName;

    private final SoftApStateMachine mStateMachine;

    private final Listener mListener;

    private static class TetherStateChange {
        public ArrayList<String> available;
        public ArrayList<String> active;

        TetherStateChange(ArrayList<String> av, ArrayList<String> ac) {
            available = av;
            active = ac;
        }
    }

    /**
     * Listener for soft AP state changes.
     */
    public interface Listener {
        /**
         * Invoke when AP state changed.
         * @param state new AP state
         * @param failureReason reason when in failed state
         */
        void onStateChanged(int state, int failureReason);
    }

    public SoftApManager(Context context,
                         Looper looper,
                         WifiNative wifiNative,
                         INetworkManagementService nmService,
                         ConnectivityManager connectivityManager,
                         String countryCode,
                         ArrayList<Integer> allowed2GChannels,
                         Listener listener) {
        mStateMachine = new SoftApStateMachine(looper);

        mContext = context;
        mNmService = nmService;
        mWifiNative = wifiNative;
        mConnectivityManager = connectivityManager;
        mCountryCode = countryCode;
        mAllowed2GChannels = allowed2GChannels;
        mListener = listener;

        mInterfaceName = mWifiNative.getInterfaceName();

        /* Register receiver for tether state changes. */
        mContext.registerReceiver(
                new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        ArrayList<String> available = intent.getStringArrayListExtra(
                                ConnectivityManager.EXTRA_AVAILABLE_TETHER);
                        ArrayList<String> active = intent.getStringArrayListExtra(
                                ConnectivityManager.EXTRA_ACTIVE_TETHER);
                        mStateMachine.sendMessage(
                                SoftApStateMachine.CMD_TETHER_STATE_CHANGE,
                                new TetherStateChange(available, active));
                    }
                }, new IntentFilter(ConnectivityManager.ACTION_TETHER_STATE_CHANGED));
    }

    /**
     * Start soft AP with given configuration.
     * @param config AP configuration
     */
    public void start(WifiConfiguration config) {
        mStateMachine.sendMessage(SoftApStateMachine.CMD_START, config);
    }

    /**
     * Stop soft AP.
     */
    public void stop() {
        mStateMachine.sendMessage(SoftApStateMachine.CMD_STOP);
    }

    /**
     * Update AP state.
     * @param state new AP state
     * @param reason Failure reason if the new AP state is in failure state
     */
    private void updateApState(int state, int reason) {
        if (mListener != null) {
            mListener.onStateChanged(state, reason);
        }
    }

    /**
     * Start a soft AP instance with the given configuration.
     * @param config AP configuration
     * @return integer result code
     */
    private int startSoftAp(WifiConfiguration config) {
        if (config == null) {
            Log.e(TAG, "Unable to start soft AP without configuration");
            return ERROR_GENERIC;
        }

        /* Make a copy of configuration for updating AP band and channel. */
        WifiConfiguration localConfig = new WifiConfiguration(config);

        int result = ApConfigUtil.updateApChannelConfig(
                mWifiNative, mCountryCode, mAllowed2GChannels, localConfig);
        if (result != SUCCESS) {
            Log.e(TAG, "Failed to update AP band and channel");
            return result;
        }

        /* Setup country code if it is provide. */
        if (mCountryCode != null) {
            /**
             * Country code is mandatory for 5GHz band, return an error if failed to set
             * country code when AP is configured for 5GHz band.
             */
            if (!mWifiNative.setCountryCodeHal(mCountryCode.toUpperCase(Locale.ROOT))
                    && config.apBand == WifiConfiguration.AP_BAND_5GHZ) {
                Log.e(TAG, "Failed to set country code, required for setting up "
                        + "soft ap in 5GHz");
                return ERROR_GENERIC;
            }
        }

        try {
            mNmService.startAccessPoint(localConfig, mInterfaceName);
        } catch (Exception e) {
            Log.e(TAG, "Exception in starting soft AP: " + e);
            return ERROR_GENERIC;
        }

        Log.d(TAG, "Soft AP is started");

        return SUCCESS;
    }

    /**
     * Teardown soft AP.
     */
    private void stopSoftAp() {
        try {
            mNmService.stopAccessPoint(mInterfaceName);
        } catch (Exception e) {
            Log.e(TAG, "Exception in stopping soft AP: " + e);
            return;
        }
        Log.d(TAG, "Soft AP is stopped");
    }

    private boolean startTethering(ArrayList<String> available) {
        String[] wifiRegexs = mConnectivityManager.getTetherableWifiRegexs();

        for (String intf : available) {
            for (String regex : wifiRegexs) {
                if (intf.matches(regex)) {
                    try {
                        InterfaceConfiguration ifcg =
                                mNmService.getInterfaceConfig(intf);
                        if (ifcg != null) {
                            /* IP/netmask: 192.168.43.1/255.255.255.0 */
                            ifcg.setLinkAddress(new LinkAddress(
                                    NetworkUtils.numericToInetAddress("192.168.43.1"), 24));
                            ifcg.setInterfaceUp();

                            mNmService.setInterfaceConfig(intf, ifcg);
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error configuring interface " + intf + ", :" + e);
                        return false;
                    }

                    if (mConnectivityManager.tether(intf)
                            != ConnectivityManager.TETHER_ERROR_NO_ERROR) {
                        Log.e(TAG, "Error tethering on " + intf);
                        return false;
                    }
                    mTetherInterfaceName = intf;
                    return true;
                }
            }
        }
        /* We found no interfaces to tether. */
        return false;
    }

    private void stopTethering() {
        try {
            /* Clear the interface address. */
            InterfaceConfiguration ifcg =
                    mNmService.getInterfaceConfig(mTetherInterfaceName);
            if (ifcg != null) {
                ifcg.setLinkAddress(
                        new LinkAddress(
                                NetworkUtils.numericToInetAddress("0.0.0.0"), 0));
                mNmService.setInterfaceConfig(mTetherInterfaceName, ifcg);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error resetting interface " + mTetherInterfaceName + ", :" + e);
        }

        if (mConnectivityManager.untether(mTetherInterfaceName)
                != ConnectivityManager.TETHER_ERROR_NO_ERROR) {
            Log.e(TAG, "Untether initiate failed!");
        }
    }

    private boolean isWifiTethered(ArrayList<String> active) {
        String[] wifiRegexs = mConnectivityManager.getTetherableWifiRegexs();
        for (String intf : active) {
            for (String regex : wifiRegexs) {
                if (intf.matches(regex)) {
                    return true;
                }
            }
        }
        /* No tethered interface. */
        return false;
    }

    private class SoftApStateMachine extends StateMachine {
        /* Commands for the state machine. */
        public static final int CMD_START = 0;
        public static final int CMD_STOP = 1;
        public static final int CMD_TETHER_STATE_CHANGE = 2;
        public static final int CMD_TETHER_NOTIFICATION_TIMEOUT = 3;

        private static final int TETHER_NOTIFICATION_TIME_OUT_MSECS = 5000;

        /* Sequence number used to track tether notification timeout. */
        private int mTetherToken = 0;

        private final State mIdleState = new IdleState();
        private final State mStartedState = new StartedState();
        private final State mTetheringState = new TetheringState();
        private final State mTetheredState = new TetheredState();
        private final State mUntetheringState = new UntetheringState();

        SoftApStateMachine(Looper looper) {
            super(TAG, looper);

            // CHECKSTYLE:OFF IndentationCheck
            addState(mIdleState);
                addState(mStartedState, mIdleState);
                    addState(mTetheringState, mStartedState);
                    addState(mTetheredState, mStartedState);
                    addState(mUntetheringState, mStartedState);
            // CHECKSTYLE:ON IndentationCheck

            setInitialState(mIdleState);
            start();
        }

        private class IdleState extends State {
            @Override
            public boolean processMessage(Message message) {
                switch (message.what) {
                    case CMD_START:
                        updateApState(WifiManager.WIFI_AP_STATE_ENABLING, 0);
                        int result = startSoftAp((WifiConfiguration) message.obj);
                        if (result == SUCCESS) {
                            updateApState(WifiManager.WIFI_AP_STATE_ENABLED, 0);
                            transitionTo(mStartedState);
                        } else {
                            int reason = WifiManager.SAP_START_FAILURE_GENERAL;
                            if (result == ERROR_NO_CHANNEL) {
                                reason = WifiManager.SAP_START_FAILURE_NO_CHANNEL;
                            }
                            updateApState(WifiManager.WIFI_AP_STATE_FAILED, reason);
                        }
                        break;
                    default:
                        /* Ignore all other commands. */
                        break;
                }
                return HANDLED;
            }
        }

        private class StartedState extends State {
            @Override
            public boolean processMessage(Message message) {
                switch (message.what) {
                    case CMD_START:
                        /* Already started, ignore this command. */
                        break;
                    case CMD_STOP:
                        updateApState(WifiManager.WIFI_AP_STATE_DISABLING, 0);
                        stopSoftAp();
                        updateApState(WifiManager.WIFI_AP_STATE_DISABLED, 0);
                        transitionTo(mIdleState);
                        break;
                    case CMD_TETHER_STATE_CHANGE:
                        TetherStateChange stateChange = (TetherStateChange) message.obj;
                        if (startTethering(stateChange.available)) {
                            transitionTo(mTetheringState);
                        }
                        break;
                    default:
                        return NOT_HANDLED;
                }
                return HANDLED;
            }
        }

        /**
         * This is a transient state. We will transition out of this state when
         * we receive a notification that WiFi is tethered (TetheredState) or
         * we timed out waiting for that notification (StartedState).
         */
        private class TetheringState extends State {
            @Override
            public void enter() {
                /* Send a delayed message to terminate if tethering fails to notify. */
                sendMessageDelayed(
                        obtainMessage(CMD_TETHER_NOTIFICATION_TIMEOUT, ++mTetherToken),
                        TETHER_NOTIFICATION_TIME_OUT_MSECS);
            }

            @Override
            public boolean processMessage(Message message) {
                switch (message.what) {
                    case CMD_TETHER_STATE_CHANGE:
                        TetherStateChange stateChange = (TetherStateChange) message.obj;
                        if (isWifiTethered(stateChange.active)) {
                            transitionTo(mTetheredState);
                        }
                        break;
                    case CMD_TETHER_NOTIFICATION_TIMEOUT:
                        if (message.arg1 == mTetherToken) {
                            Log.e(TAG, "Failed to get tether update, "
                                    + "shutdown soft access point");
                            transitionTo(mStartedState);
                            /* Needs to be first thing handled. */
                            sendMessageAtFrontOfQueue(CMD_STOP);
                        }
                        break;
                    default:
                        return NOT_HANDLED;
                }
                return HANDLED;
            }
        }

        private class TetheredState extends State {
            @Override
            public boolean processMessage(Message message) {
                switch (message.what) {
                    case CMD_TETHER_STATE_CHANGE:
                        TetherStateChange stateChange = (TetherStateChange) message.obj;
                        if (!isWifiTethered(stateChange.active)) {
                            Log.e(TAG, "Tethering reports wifi as untethered!, "
                                    + "shut down soft Ap");
                            sendMessage(CMD_STOP);
                        }
                        break;
                    case CMD_STOP:
                        Log.d(TAG, "Untethering before stopping AP");
                        stopTethering();
                        transitionTo(mUntetheringState);
                        break;

                    default:
                        return NOT_HANDLED;
                }
                return HANDLED;
            }
        }

        /**
         * This is a transient state, will transition out of this state to StartedState
         * when we receive a notification that WiFi is untethered or we timed out waiting
         * for that notification.
         */
        private class UntetheringState extends State {
            @Override
            public void enter() {
                /* Send a delayed message to terminate if tethering fails to notify. */
                sendMessageDelayed(
                        obtainMessage(CMD_TETHER_NOTIFICATION_TIMEOUT, ++mTetherToken),
                        TETHER_NOTIFICATION_TIME_OUT_MSECS);
            }

            @Override
            public boolean processMessage(Message message) {
                switch (message.what) {
                    case CMD_TETHER_STATE_CHANGE:
                        TetherStateChange stateChange = (TetherStateChange) message.obj;
                        /* Transition back to StartedState when WiFi is untethered. */
                        if (!isWifiTethered(stateChange.active)) {
                            transitionTo(mStartedState);
                            /* Needs to be first thing handled */
                            sendMessageAtFrontOfQueue(CMD_STOP);
                        }
                        break;
                    case CMD_TETHER_NOTIFICATION_TIMEOUT:
                        if (message.arg1 == mTetherToken) {
                            Log.e(TAG, "Failed to get tether update, "
                                    + "force stop access point");
                            transitionTo(mStartedState);
                            /* Needs to be first thing handled. */
                            sendMessageAtFrontOfQueue(CMD_STOP);
                        }
                        break;
                    default:
                        /* Defer handling of this message until untethering is completed. */
                        deferMessage(message);
                        break;
                }
                return HANDLED;
            }
        }
    }
}
