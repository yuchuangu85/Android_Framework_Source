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
import android.net.wifi.WifiManager;
import android.os.Looper;
import android.os.Message;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.util.IState;
import com.android.internal.util.State;
import com.android.internal.util.StateMachine;
import com.android.server.wifi.WifiNative.InterfaceCallback;

import java.io.FileDescriptor;
import java.io.PrintWriter;

/**
 * Manager WiFi in Scan Only Mode - no network connections.
 */
public class ScanOnlyModeManager implements ActiveModeManager {

    private final ScanOnlyModeStateMachine mStateMachine;

    private static final String TAG = "WifiScanOnlyModeManager";

    private final Context mContext;
    private final WifiNative mWifiNative;

    private final WifiMetrics mWifiMetrics;
    private final Listener mListener;
    private final WakeupController mWakeupController;
    private final SarManager mSarManager;

    private String mClientInterfaceName;
    private boolean mIfaceIsUp = false;

    private boolean mExpectedStop = false;

    ScanOnlyModeManager(@NonNull Context context, @NonNull Looper looper,
                        @NonNull WifiNative wifiNative, @NonNull Listener listener,
                        @NonNull WifiMetrics wifiMetrics,
                        @NonNull WakeupController wakeupController,
                        @NonNull SarManager sarManager) {
        mContext = context;
        mWifiNative = wifiNative;
        mListener = listener;
        mWifiMetrics = wifiMetrics;
        mWakeupController = wakeupController;
        mSarManager = sarManager;
        mStateMachine = new ScanOnlyModeStateMachine(looper);
    }

    /**
     * Start scan only mode.
     */
    public void start() {
        mStateMachine.sendMessage(ScanOnlyModeStateMachine.CMD_START);
    }

    /**
     * Cancel any pending scans and stop scan mode.
     */
    public void stop() {
        Log.d(TAG, " currentstate: " + getCurrentStateName());
        mExpectedStop = true;
        mStateMachine.quitNow();
    }

    public @ScanMode int getScanMode() {
        return SCAN_WITHOUT_HIDDEN_NETWORKS;
    }

    /**
     * Dump info about this ScanOnlyMode manager.
     */
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("--Dump of ScanOnlyModeManager--");

        pw.println("current StateMachine mode: " + getCurrentStateName());
        pw.println("mClientInterfaceName: " + mClientInterfaceName);
        pw.println("mIfaceIsUp: " + mIfaceIsUp);
    }

    /**
     * Listener for ScanOnlyMode state changes.
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
     * Update Wifi state.
     * @param state new Wifi state
     */
    private void updateWifiState(int state) {
        if (mExpectedStop) {
            Log.d(TAG, "expected stop, not triggering callbacks: state = " + state);
            return;
        }

        // Once we report the mode has stopped/failed any other stop signals are redundant
        // note: this can happen in failure modes where we get multiple callbacks as underlying
        // components/interface stops or the underlying interface is destroyed in cleanup
        if (state == WifiManager.WIFI_STATE_UNKNOWN || state == WifiManager.WIFI_STATE_DISABLED) {
            mExpectedStop = true;
        }

        mListener.onStateChanged(state);
    }

    private class ScanOnlyModeStateMachine extends StateMachine {
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

        ScanOnlyModeStateMachine(Looper looper) {
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
            }

            @Override
            public boolean processMessage(Message message) {
                switch (message.what) {
                    case CMD_START:
                        mClientInterfaceName = mWifiNative.setupInterfaceForClientInScanMode(
                                mWifiNativeInterfaceCallback);
                        if (TextUtils.isEmpty(mClientInterfaceName)) {
                            Log.e(TAG, "Failed to create ClientInterface. Sit in Idle");
                            updateWifiState(WifiManager.WIFI_STATE_UNKNOWN);
                            break;
                        }
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
                    Log.d(TAG, "Wifi is ready to use for scanning");
                    mWakeupController.start();
                    updateWifiState(WifiManager.WIFI_STATE_ENABLED);
                } else {
                    // if the interface goes down we should exit and go back to idle state.
                    Log.d(TAG, "interface down - stop scan mode");
                    mStateMachine.sendMessage(CMD_INTERFACE_DOWN);
                }
            }

            @Override
            public void enter() {
                Log.d(TAG, "entering StartedState");

                mIfaceIsUp = false;
                onUpChanged(mWifiNative.isInterfaceUp(mClientInterfaceName));
                mSarManager.setScanOnlyWifiState(WifiManager.WIFI_STATE_ENABLED);
            }

            @Override
            public boolean processMessage(Message message) {
                switch(message.what) {
                    case CMD_START:
                        // Already started, ignore this command.
                        break;
                    case CMD_INTERFACE_DESTROYED:
                        Log.d(TAG, "Interface cleanly destroyed, report scan mode stop.");
                        mClientInterfaceName = null;
                        transitionTo(mIdleState);
                        break;
                    case CMD_INTERFACE_STATUS_CHANGED:
                        boolean isUp = message.arg1 == 1;
                        onUpChanged(isUp);
                        break;
                    case CMD_INTERFACE_DOWN:
                        Log.d(TAG, "interface down!  stop mode");
                        updateWifiState(WifiManager.WIFI_STATE_UNKNOWN);
                        transitionTo(mIdleState);
                        break;
                    default:
                        return NOT_HANDLED;
                }
                return HANDLED;
            }

            /**
             * Clean up state and unregister listeners.
             */
            @Override
            public void exit() {
                mWakeupController.stop();
                if (mClientInterfaceName != null) {
                    mWifiNative.teardownInterface(mClientInterfaceName);
                    mClientInterfaceName = null;
                }
                updateWifiState(WifiManager.WIFI_STATE_DISABLED);
                mSarManager.setScanOnlyWifiState(WifiManager.WIFI_STATE_DISABLED);

                // once we leave started, nothing else to do...  stop the state machine
                mStateMachine.quitNow();
            }
        }
    }
}
