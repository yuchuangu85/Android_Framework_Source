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

import android.annotation.NonNull;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.Looper;
import android.os.Message;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.util.IState;
import com.android.internal.util.State;
import com.android.internal.util.StateMachine;
import com.android.server.wifi.WifiNative.InterfaceCallback;

import java.io.FileDescriptor;
import java.io.PrintWriter;

/**
 * Manager WiFi in Client Mode where we connect to configured networks.
 */
public class ClientModeManager implements ActiveModeManager {
    private static final String TAG = "WifiClientModeManager";

    private final ClientModeStateMachine mStateMachine;

    private final Context mContext;
    private final WifiNative mWifiNative;

    private final WifiMetrics mWifiMetrics;
    private final Listener mListener;
    private final ScanRequestProxy mScanRequestProxy;
    private final WifiStateMachine mWifiStateMachine;

    private String mClientInterfaceName;
    private boolean mIfaceIsUp = false;

    private boolean mExpectedStop = false;

    ClientModeManager(Context context, @NonNull Looper looper, WifiNative wifiNative,
            Listener listener, WifiMetrics wifiMetrics, ScanRequestProxy scanRequestProxy,
            WifiStateMachine wifiStateMachine) {
        mContext = context;
        mWifiNative = wifiNative;
        mListener = listener;
        mWifiMetrics = wifiMetrics;
        mScanRequestProxy = scanRequestProxy;
        mWifiStateMachine = wifiStateMachine;
        mStateMachine = new ClientModeStateMachine(looper);
    }

    /**
     * Start client mode.
     */
    public void start() {
        mStateMachine.sendMessage(ClientModeStateMachine.CMD_START);
    }

    /**
     * Disconnect from any currently connected networks and stop client mode.
     */
    public void stop() {
        Log.d(TAG, " currentstate: " + getCurrentStateName());
        mExpectedStop = true;
        if (mClientInterfaceName != null) {
            if (mIfaceIsUp) {
                updateWifiState(WifiManager.WIFI_STATE_DISABLING,
                                WifiManager.WIFI_STATE_ENABLED);
            } else {
                updateWifiState(WifiManager.WIFI_STATE_DISABLING,
                                WifiManager.WIFI_STATE_ENABLING);
            }
        }
        mStateMachine.quitNow();
    }

    /**
     * Dump info about this ClientMode manager.
     */
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("--Dump of ClientModeManager--");

        pw.println("current StateMachine mode: " + getCurrentStateName());
        pw.println("mClientInterfaceName: " + mClientInterfaceName);
        pw.println("mIfaceIsUp: " + mIfaceIsUp);
    }

    /**
     * Listener for ClientMode state changes.
     */
    public interface Listener {
        /**
         * Invoke when wifi state changes.
         * @param state new wifi state
         */
        void onStateChanged(int state);
    }

    private String getCurrentStateName() {
        IState currentState = mStateMachine.getCurrentState();

        if (currentState != null) {
            return currentState.getName();
        }

        return "StateMachine not active";
    }

    /**
     * Update Wifi state and send the broadcast.
     * @param newState new Wifi state
     * @param currentState current wifi state
     */
    private void updateWifiState(int newState, int currentState) {
        if (!mExpectedStop) {
            mListener.onStateChanged(newState);
        } else {
            Log.d(TAG, "expected stop, not triggering callbacks: newState = " + newState);
        }

        // Once we report the mode has stopped/failed any other stop signals are redundant
        // note: this can happen in failure modes where we get multiple callbacks as underlying
        // components/interface stops or the underlying interface is destroyed in cleanup
        if (newState == WifiManager.WIFI_STATE_UNKNOWN
                || newState == WifiManager.WIFI_STATE_DISABLED) {
            mExpectedStop = true;
        }

        if (newState == WifiManager.WIFI_STATE_UNKNOWN) {
            // do not need to broadcast failure to system
            return;
        }

        mWifiStateMachine.setWifiStateForApiCalls(newState);

        final Intent intent = new Intent(WifiManager.WIFI_STATE_CHANGED_ACTION);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        intent.putExtra(WifiManager.EXTRA_WIFI_STATE, newState);
        intent.putExtra(WifiManager.EXTRA_PREVIOUS_WIFI_STATE, currentState);
        mContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
    }

    private class ClientModeStateMachine extends StateMachine {
        // Commands for the state machine.
        public static final int CMD_START = 0;
        public static final int CMD_INTERFACE_STATUS_CHANGED = 3;
        public static final int CMD_INTERFACE_DESTROYED = 4;
        public static final int CMD_INTERFACE_DOWN = 5;
        private final State mIdleState = new IdleState();
        private final State mStartedState = new StartedState();

        private final InterfaceCallback mWifiNativeInterfaceCallback = new InterfaceCallback() {
            @Override
            public void onDestroyed(String ifaceName) {
                if (mClientInterfaceName != null && mClientInterfaceName.equals(ifaceName)) {
                    Log.d(TAG, "STA iface " + ifaceName + " was destroyed, stopping client mode");

                    // we must immediately clean up state in WSM to unregister all client mode
                    // related objects
                    // Note: onDestroyed is only called from the WSM thread
                    mWifiStateMachine.handleIfaceDestroyed();

                    sendMessage(CMD_INTERFACE_DESTROYED);
                }
            }

            @Override
            public void onUp(String ifaceName) {
                if (mClientInterfaceName != null && mClientInterfaceName.equals(ifaceName)) {
                    sendMessage(CMD_INTERFACE_STATUS_CHANGED, 1);
                }
            }

            @Override
            public void onDown(String ifaceName) {
                if (mClientInterfaceName != null && mClientInterfaceName.equals(ifaceName)) {
                    sendMessage(CMD_INTERFACE_STATUS_CHANGED, 0);
                }
            }
        };

        ClientModeStateMachine(Looper looper) {
            super(TAG, looper);

            addState(mIdleState);
            addState(mStartedState);

            setInitialState(mIdleState);
            start();
        }

        private class IdleState extends State {

            @Override
            public void enter() {
                Log.d(TAG, "entering IdleState");
                mClientInterfaceName = null;
                mIfaceIsUp = false;
            }

            @Override
            public boolean processMessage(Message message) {
                switch (message.what) {
                    case CMD_START:
                        updateWifiState(WifiManager.WIFI_STATE_ENABLING,
                                        WifiManager.WIFI_STATE_DISABLED);

                        mClientInterfaceName = mWifiNative.setupInterfaceForClientMode(
                                false /* not low priority */, mWifiNativeInterfaceCallback);
                        if (TextUtils.isEmpty(mClientInterfaceName)) {
                            Log.e(TAG, "Failed to create ClientInterface. Sit in Idle");
                            updateWifiState(WifiManager.WIFI_STATE_UNKNOWN,
                                            WifiManager.WIFI_STATE_ENABLING);
                            updateWifiState(WifiManager.WIFI_STATE_DISABLED,
                                            WifiManager.WIFI_STATE_UNKNOWN);
                            break;
                        }
                        sendScanAvailableBroadcast(false);
                        mScanRequestProxy.enableScanningForHiddenNetworks(false);
                        mScanRequestProxy.clearScanResults();
                        transitionTo(mStartedState);
                        break;
                    default:
                        Log.d(TAG, "received an invalid message: " + message);
                        return NOT_HANDLED;
                }
                return HANDLED;
            }
        }

        private class StartedState extends State {

            private void onUpChanged(boolean isUp) {
                if (isUp == mIfaceIsUp) {
                    return;  // no change
                }
                mIfaceIsUp = isUp;
                if (isUp) {
                    Log.d(TAG, "Wifi is ready to use for client mode");
                    sendScanAvailableBroadcast(true);
                    mWifiStateMachine.setOperationalMode(WifiStateMachine.CONNECT_MODE,
                                                         mClientInterfaceName);
                    updateWifiState(WifiManager.WIFI_STATE_ENABLED,
                                    WifiManager.WIFI_STATE_ENABLING);
                } else {
                    if (mWifiStateMachine.isConnectedMacRandomizationEnabled()) {
                        // Handle the error case where our underlying interface went down if we
                        // do not have mac randomization enabled (b/72459123).
                        return;
                    }
                    // if the interface goes down we should exit and go back to idle state.
                    Log.d(TAG, "interface down!");
                    updateWifiState(WifiManager.WIFI_STATE_UNKNOWN,
                                    WifiManager.WIFI_STATE_ENABLED);
                    mStateMachine.sendMessage(CMD_INTERFACE_DOWN);
                }
            }

            @Override
            public void enter() {
                Log.d(TAG, "entering StartedState");
                mIfaceIsUp = false;
                onUpChanged(mWifiNative.isInterfaceUp(mClientInterfaceName));
                mScanRequestProxy.enableScanningForHiddenNetworks(true);
            }

            @Override
            public boolean processMessage(Message message) {
                switch(message.what) {
                    case CMD_START:
                        // Already started, ignore this command.
                        break;
                    case CMD_INTERFACE_DOWN:
                        Log.e(TAG, "Detected an interface down, reporting failure to SelfRecovery");
                        mWifiStateMachine.failureDetected(SelfRecovery.REASON_STA_IFACE_DOWN);

                        updateWifiState(WifiManager.WIFI_STATE_DISABLING,
                                        WifiManager.WIFI_STATE_UNKNOWN);
                        transitionTo(mIdleState);
                        break;
                    case CMD_INTERFACE_STATUS_CHANGED:
                        boolean isUp = message.arg1 == 1;
                        onUpChanged(isUp);
                        break;
                    case CMD_INTERFACE_DESTROYED:
                        Log.d(TAG, "interface destroyed - client mode stopping");

                        updateWifiState(WifiManager.WIFI_STATE_DISABLING,
                                        WifiManager.WIFI_STATE_ENABLED);
                        mClientInterfaceName = null;
                        transitionTo(mIdleState);
                        break;
                    default:
                        return NOT_HANDLED;
                }
                return HANDLED;
            }

            /**
             * Clean up state, unregister listeners and update wifi state.
             */
            @Override
            public void exit() {
                mWifiStateMachine.setOperationalMode(WifiStateMachine.DISABLED_MODE, null);

                if (mClientInterfaceName != null) {
                    mWifiNative.teardownInterface(mClientInterfaceName);
                    mClientInterfaceName = null;
                    mIfaceIsUp = false;
                }

                updateWifiState(WifiManager.WIFI_STATE_DISABLED,
                                WifiManager.WIFI_STATE_DISABLING);

                // once we leave started, nothing else to do...  stop the state machine
                mStateMachine.quitNow();
            }
        }

        private void sendScanAvailableBroadcast(boolean available) {
            Log.d(TAG, "sending scan available broadcast: " + available);
            final Intent intent = new Intent(WifiManager.WIFI_SCAN_AVAILABLE);
            intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
            if (available) {
                intent.putExtra(WifiManager.EXTRA_SCAN_AVAILABLE, WifiManager.WIFI_STATE_ENABLED);
            } else {
                intent.putExtra(WifiManager.EXTRA_SCAN_AVAILABLE, WifiManager.WIFI_STATE_DISABLED);
            }
            mContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
        }
    }
}
