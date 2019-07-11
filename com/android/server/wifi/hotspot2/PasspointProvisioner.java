/*
 * Copyright 2017 The Android Open Source Project
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

package com.android.server.wifi.hotspot2;

import android.content.Context;
import android.net.Network;
import android.net.wifi.hotspot2.IProvisioningCallback;
import android.net.wifi.hotspot2.OsuProvider;
import android.net.wifi.hotspot2.ProvisioningCallback;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;

import java.net.MalformedURLException;
import java.net.URL;
/**
 * Provides methods to carry out provisioning flow
 */
public class PasspointProvisioner {
    private static final String TAG = "PasspointProvisioner";

    // Indicates callback type for caller initiating provisioning
    private static final int PROVISIONING_STATUS = 0;
    private static final int PROVISIONING_FAILURE = 1;

    // TLS version to be used for HTTPS connection with OSU server
    private static final String TLS_VERSION = "TLSv1";

    private final Context mContext;
    private final ProvisioningStateMachine mProvisioningStateMachine;
    private final OsuNetworkCallbacks mOsuNetworkCallbacks;
    private final OsuNetworkConnection mOsuNetworkConnection;
    private final OsuServerConnection mOsuServerConnection;
    private final WfaKeyStore mWfaKeyStore;
    private final PasspointObjectFactory mObjectFactory;

    private int mCurrentSessionId = 0;
    private int mCallingUid;
    private boolean mVerboseLoggingEnabled = false;

    PasspointProvisioner(Context context, PasspointObjectFactory objectFactory) {
        mContext = context;
        mOsuNetworkConnection = objectFactory.makeOsuNetworkConnection(context);
        mProvisioningStateMachine = new ProvisioningStateMachine();
        mOsuNetworkCallbacks = new OsuNetworkCallbacks();
        mOsuServerConnection = objectFactory.makeOsuServerConnection();
        mWfaKeyStore = objectFactory.makeWfaKeyStore();
        mObjectFactory = objectFactory;
    }

    /**
     * Sets up for provisioning
     * @param looper Looper on which the Provisioning state machine will run
     */
    public void init(Looper looper) {
        mProvisioningStateMachine.start(new Handler(looper));
        mOsuNetworkConnection.init(mProvisioningStateMachine.getHandler());
        // Offload the heavy load job to another thread
        mProvisioningStateMachine.getHandler().post(() -> {
            mWfaKeyStore.load();
            mOsuServerConnection.init(mObjectFactory.getSSLContext(TLS_VERSION),
                    mObjectFactory.getTrustManagerImpl(mWfaKeyStore.get()));
        });
    }

    /**
     * Enable verbose logging to help debug failures
     * @param level integer indicating verbose logging enabled if > 0
     */
    public void enableVerboseLogging(int level) {
        mVerboseLoggingEnabled = (level > 0) ? true : false;
        mOsuNetworkConnection.enableVerboseLogging(level);
        mOsuServerConnection.enableVerboseLogging(level);
    }

    /**
     * Start provisioning flow with a given provider.
     * @param callingUid calling uid.
     * @param provider {@link OsuProvider} to provision with.
     * @param callback {@link IProvisioningCallback} to provide provisioning status.
     * @return boolean value, true if provisioning was started, false otherwise.
     *
     * Implements HS2.0 provisioning flow with a given HS2.0 provider.
     */
    public boolean startSubscriptionProvisioning(int callingUid, OsuProvider provider,
            IProvisioningCallback callback) {
        mCallingUid = callingUid;

        Log.v(TAG, "Provisioning started with " + provider.toString());

        mProvisioningStateMachine.getHandler().post(() -> {
            mProvisioningStateMachine.startProvisioning(provider, callback);
        });

        return true;
    }

    /**
     * Handles the provisioning flow state transitions
     */
    class ProvisioningStateMachine {
        private static final String TAG = "ProvisioningStateMachine";

        private static final int INITIAL_STATE                             = 1;
        private static final int WAITING_TO_CONNECT                        = 2;
        private static final int OSU_AP_CONNECTED                          = 3;
        private static final int OSU_SERVER_CONNECTED                      = 4;
        private static final int OSU_SERVER_VALIDATED                      = 5;
        private static final int OSU_PROVIDER_VERIFIED                     = 6;

        private OsuProvider mOsuProvider;
        private IProvisioningCallback mProvisioningCallback;
        private int mState = INITIAL_STATE;
        private Handler mHandler;
        private URL mServerUrl;

        /**
         * Initializes and starts the state machine with a handler to handle incoming events
         */
        public void start(Handler handler) {
            mHandler = handler;
        }

        /**
         * Returns the handler on which a runnable can be posted
         * @return Handler State Machine's handler
         */
        public Handler getHandler() {
            return mHandler;
        }

        /**
         * Start Provisioning with the Osuprovider and invoke callbacks
         * @param provider OsuProvider to provision with
         * @param callback IProvisioningCallback to invoke callbacks on
         */
        public void startProvisioning(OsuProvider provider, IProvisioningCallback callback) {
            if (mVerboseLoggingEnabled) {
                Log.v(TAG, "startProvisioning received in state=" + mState);
            }
            if (mState != INITIAL_STATE) {
                if (mVerboseLoggingEnabled) {
                    Log.v(TAG, "State Machine needs to be reset before starting provisioning");
                }
                resetStateMachine(ProvisioningCallback.OSU_FAILURE_PROVISIONING_ABORTED);
            }
            if (!mOsuServerConnection.canValidateServer()) {
                Log.w(TAG, "Provisioning is not possible");
                mProvisioningCallback = callback;
                resetStateMachine(ProvisioningCallback.OSU_FAILURE_PROVISIONING_NOT_AVAILABLE);
                return;
            }
            URL serverUrl = null;
            try {
                serverUrl = new URL(provider.getServerUri().toString());
            } catch (MalformedURLException e) {
                Log.e(TAG, "Invalid Server URL");
                mProvisioningCallback = callback;
                resetStateMachine(ProvisioningCallback.OSU_FAILURE_SERVER_URL_INVALID);
                return;
            }
            mServerUrl = serverUrl;
            mProvisioningCallback = callback;
            mOsuProvider = provider;
            // Register for network and wifi state events during provisioning flow
            mOsuNetworkConnection.setEventCallback(mOsuNetworkCallbacks);

            // Register for OSU server callbacks
            mOsuServerConnection.setEventCallback(new OsuServerCallbacks(++mCurrentSessionId));

            if (!mOsuNetworkConnection.connect(mOsuProvider.getOsuSsid(),
                    mOsuProvider.getNetworkAccessIdentifier())) {
                resetStateMachine(ProvisioningCallback.OSU_FAILURE_AP_CONNECTION);
                return;
            }
            invokeProvisioningCallback(PROVISIONING_STATUS,
                    ProvisioningCallback.OSU_STATUS_AP_CONNECTING);
            changeState(WAITING_TO_CONNECT);
        }

        /**
         * Handle Wifi Disable event
         */
        public void handleWifiDisabled() {
            if (mVerboseLoggingEnabled) {
                Log.v(TAG, "Wifi Disabled in state=" + mState);
            }
            if (mState == INITIAL_STATE) {
                Log.w(TAG, "Wifi Disable unhandled in state=" + mState);
                return;
            }
            resetStateMachine(ProvisioningCallback.OSU_FAILURE_AP_CONNECTION);
        }

        /**
         * Handle server validation failure
         */
        public void handleServerValidationFailure(int sessionId) {
            if (mVerboseLoggingEnabled) {
                Log.v(TAG, "Server Validation failure received in " + mState);
            }
            if (sessionId != mCurrentSessionId) {
                Log.w(TAG, "Expected server validation callback for currentSessionId="
                        + mCurrentSessionId);
                return;
            }
            if (mState != OSU_SERVER_CONNECTED) {
                Log.wtf(TAG, "Server Validation Failure unhandled in mState=" + mState);
                return;
            }
            resetStateMachine(ProvisioningCallback.OSU_FAILURE_SERVER_VALIDATION);
        }

        /**
         * Handle status of server validation success
         */
        public void handleServerValidationSuccess(int sessionId) {
            if (mVerboseLoggingEnabled) {
                Log.v(TAG, "Server Validation Success received in " + mState);
            }
            if (sessionId != mCurrentSessionId) {
                Log.w(TAG, "Expected server validation callback for currentSessionId="
                        + mCurrentSessionId);
                return;
            }
            if (mState != OSU_SERVER_CONNECTED) {
                Log.wtf(TAG, "Server validation success event unhandled in state=" + mState);
                return;
            }
            changeState(OSU_SERVER_VALIDATED);
            invokeProvisioningCallback(PROVISIONING_STATUS,
                    ProvisioningCallback.OSU_STATUS_SERVER_VALIDATED);
            validateProvider();
        }

        private void validateProvider() {
            if (mVerboseLoggingEnabled) {
                Log.v(TAG, "Validating provider in state=" + mState);
            }
            if (!mOsuServerConnection.validateProvider(mOsuProvider.getFriendlyName())) {
                resetStateMachine(ProvisioningCallback.OSU_FAILURE_PROVIDER_VERIFICATION);
                return;
            }
            changeState(OSU_PROVIDER_VERIFIED);
            invokeProvisioningCallback(PROVISIONING_STATUS,
                    ProvisioningCallback.OSU_STATUS_PROVIDER_VERIFIED);
            // TODO : send Initial SOAP Exchange
        }

        /**
         * Connected event received
         * @param network Network object for this connection
         */
        public void handleConnectedEvent(Network network) {
            if (mVerboseLoggingEnabled) {
                Log.v(TAG, "Connected event received in state=" + mState);
            }
            if (mState != WAITING_TO_CONNECT) {
                // Not waiting for a connection
                Log.wtf(TAG, "Connection event unhandled in state=" + mState);
                return;
            }
            invokeProvisioningCallback(PROVISIONING_STATUS,
                    ProvisioningCallback.OSU_STATUS_AP_CONNECTED);
            changeState(OSU_AP_CONNECTED);
            initiateServerConnection(network);
        }

        private void initiateServerConnection(Network network) {
            if (mVerboseLoggingEnabled) {
                Log.v(TAG, "Initiating server connection in state=" + mState);
            }
            if (mState != OSU_AP_CONNECTED) {
                Log.wtf(TAG , "Initiating server connection aborted in invalid state=" + mState);
                return;
            }
            if (!mOsuServerConnection.connect(mServerUrl, network)) {
                resetStateMachine(ProvisioningCallback.OSU_FAILURE_SERVER_CONNECTION);
                return;
            }
            changeState(OSU_SERVER_CONNECTED);
            invokeProvisioningCallback(PROVISIONING_STATUS,
                    ProvisioningCallback.OSU_STATUS_SERVER_CONNECTED);
        }

        /**
         * Disconnect event received
         */
        public void handleDisconnect() {
            if (mVerboseLoggingEnabled) {
                Log.v(TAG, "Connection failed in state=" + mState);
            }
            if (mState == INITIAL_STATE) {
                Log.w(TAG, "Disconnect event unhandled in state=" + mState);
                return;
            }
            resetStateMachine(ProvisioningCallback.OSU_FAILURE_AP_CONNECTION);
        }

        private void invokeProvisioningCallback(int callbackType, int status) {
            if (mProvisioningCallback == null) {
                Log.e(TAG, "Provisioning callback " + callbackType + " with status " + status
                        + " not invoked");
                return;
            }
            try {
                if (callbackType == PROVISIONING_STATUS) {
                    mProvisioningCallback.onProvisioningStatus(status);
                } else {
                    mProvisioningCallback.onProvisioningFailure(status);
                }
            } catch (RemoteException e) {
                Log.e(TAG, "Remote Exception while posting callback type=" + callbackType
                        + " status=" + status);
            }
        }

        private void changeState(int nextState) {
            if (nextState != mState) {
                if (mVerboseLoggingEnabled) {
                    Log.v(TAG, "Changing state from " + mState + " -> " + nextState);
                }
                mState = nextState;
            }
        }

        private void resetStateMachine(int failureCode) {
            invokeProvisioningCallback(PROVISIONING_FAILURE, failureCode);
            mOsuNetworkConnection.setEventCallback(null);
            mOsuNetworkConnection.disconnectIfNeeded();
            mOsuServerConnection.setEventCallback(null);
            mOsuServerConnection.cleanup();
            changeState(INITIAL_STATE);
        }
    }

    /**
     * Callbacks for network and wifi events
     */
    class OsuNetworkCallbacks implements OsuNetworkConnection.Callbacks {

        OsuNetworkCallbacks() {}

        @Override
        public void onConnected(Network network) {
            if (mVerboseLoggingEnabled) {
                Log.v(TAG, "onConnected to " + network);
            }
            if (network == null) {
                mProvisioningStateMachine.handleDisconnect();
            } else {
                mProvisioningStateMachine.handleConnectedEvent(network);
            }
        }

        @Override
        public void onDisconnected() {
            if (mVerboseLoggingEnabled) {
                Log.v(TAG, "onDisconnected");
            }
            mProvisioningStateMachine.handleDisconnect();
        }

        @Override
        public void onTimeOut() {
            if (mVerboseLoggingEnabled) {
                Log.v(TAG, "Timed out waiting for connection to OSU AP");
            }
            mProvisioningStateMachine.handleDisconnect();
        }

        @Override
        public void onWifiEnabled() {
            if (mVerboseLoggingEnabled) {
                Log.v(TAG, "onWifiEnabled");
            }
        }

        @Override
        public void onWifiDisabled() {
            if (mVerboseLoggingEnabled) {
                Log.v(TAG, "onWifiDisabled");
            }
            mProvisioningStateMachine.handleWifiDisabled();
        }
    }

    /**
     * Defines the callbacks expected from OsuServerConnection
     */
    public class OsuServerCallbacks {
        private final int mSessionId;

        OsuServerCallbacks(int sessionId) {
            mSessionId = sessionId;
        }

        /**
         * Returns the session ID corresponding to this callback
         * @return int sessionID
         */
        public int getSessionId() {
            return mSessionId;
        }

        /**
         * Provides a server validation status for the session ID
         * @param sessionId integer indicating current session ID
         * @param succeeded boolean indicating success/failure of server validation
         */
        public void onServerValidationStatus(int sessionId, boolean succeeded) {
            if (mVerboseLoggingEnabled) {
                Log.v(TAG, "OSU Server Validation status=" + succeeded + " sessionId=" + sessionId);
            }
            if (succeeded) {
                mProvisioningStateMachine.getHandler().post(() -> {
                    mProvisioningStateMachine.handleServerValidationSuccess(sessionId);
                });
            } else {
                mProvisioningStateMachine.getHandler().post(() -> {
                    mProvisioningStateMachine.handleServerValidationFailure(sessionId);
                });
            }
        }

    }
}

