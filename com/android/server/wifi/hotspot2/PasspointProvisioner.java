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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.content.Intent;
import android.net.Network;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.net.wifi.hotspot2.IProvisioningCallback;
import android.net.wifi.hotspot2.OsuProvider;
import android.net.wifi.hotspot2.PasspointConfiguration;
import android.net.wifi.hotspot2.ProvisioningCallback;
import android.net.wifi.hotspot2.omadm.PpsMoParser;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.wifi.WifiMetrics;
import com.android.server.wifi.WifiNative;
import com.android.server.wifi.hotspot2.anqp.ANQPElement;
import com.android.server.wifi.hotspot2.anqp.Constants;
import com.android.server.wifi.hotspot2.anqp.HSOsuProvidersElement;
import com.android.server.wifi.hotspot2.anqp.OsuProviderInfo;
import com.android.server.wifi.hotspot2.soap.ExchangeCompleteMessage;
import com.android.server.wifi.hotspot2.soap.PostDevDataMessage;
import com.android.server.wifi.hotspot2.soap.PostDevDataResponse;
import com.android.server.wifi.hotspot2.soap.RedirectListener;
import com.android.server.wifi.hotspot2.soap.SppConstants;
import com.android.server.wifi.hotspot2.soap.SppResponseMessage;
import com.android.server.wifi.hotspot2.soap.UpdateResponseMessage;
import com.android.server.wifi.hotspot2.soap.command.BrowserUri;
import com.android.server.wifi.hotspot2.soap.command.PpsMoData;
import com.android.server.wifi.hotspot2.soap.command.SppCommand;

import java.net.MalformedURLException;
import java.net.URL;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

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
    private static final String OSU_APP_PACKAGE = "com.android.hotspot2";

    private final Context mContext;
    private final ProvisioningStateMachine mProvisioningStateMachine;
    private final OsuNetworkCallbacks mOsuNetworkCallbacks;
    private final OsuNetworkConnection mOsuNetworkConnection;
    private final OsuServerConnection mOsuServerConnection;
    private final WfaKeyStore mWfaKeyStore;
    private final PasspointObjectFactory mObjectFactory;
    private final SystemInfo mSystemInfo;
    private int mCurrentSessionId = 0;
    private int mCallingUid;
    private boolean mVerboseLoggingEnabled = false;
    private WifiManager mWifiManager;
    private PasspointManager mPasspointManager;
    private Looper mLooper;
    private final WifiMetrics mWifiMetrics;

    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    public PasspointProvisioner(Context context, WifiNative wifiNative,
            PasspointObjectFactory objectFactory, PasspointManager passpointManager,
            WifiMetrics wifiMetrics) {
        mContext = context;
        mOsuNetworkConnection = objectFactory.makeOsuNetworkConnection(context);
        mProvisioningStateMachine = new ProvisioningStateMachine();
        mOsuNetworkCallbacks = new OsuNetworkCallbacks();
        mOsuServerConnection = objectFactory.makeOsuServerConnection();
        mWfaKeyStore = objectFactory.makeWfaKeyStore();
        mSystemInfo = objectFactory.getSystemInfo(context, wifiNative);
        mObjectFactory = objectFactory;
        mPasspointManager = passpointManager;
        mWifiMetrics = wifiMetrics;
    }

    /**
     * Sets up for provisioning
     *
     * @param looper Looper on which the Provisioning state machine will run
     */
    public void init(Looper looper) {
        mLooper = looper;
        mWifiManager = (WifiManager) mContext.getSystemService(Context.WIFI_SERVICE);
        mProvisioningStateMachine.start(new Handler(mLooper));
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
     *
     * @param level integer indicating verbose logging enabled if > 0
     */
    public void enableVerboseLogging(int level) {
        mVerboseLoggingEnabled = (level > 0) ? true : false;
        mOsuNetworkConnection.enableVerboseLogging(level);
        mOsuServerConnection.enableVerboseLogging(level);
    }

    /**
     * Start provisioning flow with a given provider.
     *
     * @param callingUid calling uid.
     * @param provider   {@link OsuProvider} to provision with.
     * @param callback   {@link IProvisioningCallback} to provide provisioning status.
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
        private static final String TAG = "PasspointProvisioningStateMachine";

        static final int STATE_INIT = 1;
        static final int STATE_AP_CONNECTING = 2;
        static final int STATE_OSU_SERVER_CONNECTING = 3;
        static final int STATE_WAITING_FOR_FIRST_SOAP_RESPONSE = 4;
        static final int STATE_WAITING_FOR_REDIRECT_RESPONSE = 5;
        static final int STATE_WAITING_FOR_SECOND_SOAP_RESPONSE = 6;
        static final int STATE_WAITING_FOR_THIRD_SOAP_RESPONSE = 7;
        static final int STATE_WAITING_FOR_TRUST_ROOT_CERTS = 8;

        private OsuProvider mOsuProvider;
        private IProvisioningCallback mProvisioningCallback;
        private int mState = STATE_INIT;
        private Handler mHandler;
        private URL mServerUrl;
        private Network mNetwork;
        private String mSessionId;
        private String mWebUrl;
        private PasspointConfiguration mPasspointConfiguration;
        private RedirectListener mRedirectListener;
        private HandlerThread mRedirectHandlerThread;
        private Handler mRedirectStartStopHandler;

        /**
         * Initializes and starts the state machine with a handler to handle incoming events
         */
        public void start(Handler handler) {
            mHandler = handler;
            if (mRedirectHandlerThread == null) {
                mRedirectHandlerThread = new HandlerThread("RedirectListenerHandler");
                mRedirectHandlerThread.start();
                mRedirectStartStopHandler = new Handler(mRedirectHandlerThread.getLooper());
            }
        }

        /**
         * Returns the handler on which a runnable can be posted
         *
         * @return Handler State Machine's handler
         */
        public Handler getHandler() {
            return mHandler;
        }

        /**
         * Start Provisioning with the Osuprovider and invoke callbacks
         *
         * @param provider OsuProvider to provision with
         * @param callback IProvisioningCallback to invoke callbacks on
         * Note: Called on main thread (WifiService thread).
         */
        public void startProvisioning(OsuProvider provider, IProvisioningCallback callback) {
            if (mVerboseLoggingEnabled) {
                Log.v(TAG, "startProvisioning received in state=" + mState);
            }

            if (mState != STATE_INIT) {
                if (mVerboseLoggingEnabled) {
                    Log.v(TAG, "State Machine needs to be reset before starting provisioning");
                }
                resetStateMachineForFailure(ProvisioningCallback.OSU_FAILURE_PROVISIONING_ABORTED);
            }
            mProvisioningCallback = callback;
            mRedirectListener = RedirectListener.createInstance(mLooper);

            if (mRedirectListener == null) {
                resetStateMachineForFailure(
                        ProvisioningCallback.OSU_FAILURE_START_REDIRECT_LISTENER);
                return;
            }

            if (!mOsuServerConnection.canValidateServer()) {
                Log.w(TAG, "Provisioning is not possible");
                resetStateMachineForFailure(
                        ProvisioningCallback.OSU_FAILURE_PROVISIONING_NOT_AVAILABLE);
                return;
            }
            URL serverUrl;
            try {
                serverUrl = new URL(provider.getServerUri().toString());
            } catch (MalformedURLException e) {
                Log.e(TAG, "Invalid Server URL");
                resetStateMachineForFailure(ProvisioningCallback.OSU_FAILURE_SERVER_URL_INVALID);
                return;
            }
            mServerUrl = serverUrl;
            mOsuProvider = provider;
            if (mOsuProvider.getOsuSsid() == null) {
                // Find a best matching OsuProvider that has an OSU SSID from current scanResults
                List<ScanResult> scanResults = mWifiManager.getScanResults();
                mOsuProvider = getBestMatchingOsuProvider(scanResults, mOsuProvider);
                if (mOsuProvider == null) {
                    resetStateMachineForFailure(
                            ProvisioningCallback.OSU_FAILURE_OSU_PROVIDER_NOT_FOUND);
                    return;
                }
            }

            // Register for network and wifi state events during provisioning flow
            mOsuNetworkConnection.setEventCallback(mOsuNetworkCallbacks);

            // Register for OSU server callbacks
            mOsuServerConnection.setEventCallback(new OsuServerCallbacks(++mCurrentSessionId));

            if (!mOsuNetworkConnection.connect(mOsuProvider.getOsuSsid(),
                    mOsuProvider.getNetworkAccessIdentifier(), mOsuProvider.getFriendlyName())) {
                resetStateMachineForFailure(ProvisioningCallback.OSU_FAILURE_AP_CONNECTION);
                return;
            }
            invokeProvisioningCallback(PROVISIONING_STATUS,
                    ProvisioningCallback.OSU_STATUS_AP_CONNECTING);
            changeState(STATE_AP_CONNECTING);
        }

        /**
         * Handles Wifi Disable event
         *
         * Note: Called on main thread (WifiService thread).
         */
        public void handleWifiDisabled() {
            if (mVerboseLoggingEnabled) {
                Log.v(TAG, "Wifi Disabled in state=" + mState);
            }
            if (mState == STATE_INIT) {
                Log.w(TAG, "Wifi Disable unhandled in state=" + mState);
                return;
            }
            resetStateMachineForFailure(ProvisioningCallback.OSU_FAILURE_AP_CONNECTION);
        }

        /**
         * Handles server connection status
         *
         * @param sessionId indicating current session ID
         * @param succeeded boolean indicating success/failure of server connection
         * Note: Called on main thread (WifiService thread).
         */
        public void handleServerConnectionStatus(int sessionId, boolean succeeded) {
            if (mVerboseLoggingEnabled) {
                Log.v(TAG, "Server Connection status received in " + mState);
            }
            if (sessionId != mCurrentSessionId) {
                Log.w(TAG, "Expected server connection failure callback for currentSessionId="
                        + mCurrentSessionId);
                return;
            }
            if (mState != STATE_OSU_SERVER_CONNECTING) {
                Log.wtf(TAG, "Server Validation Failure unhandled in mState=" + mState);
                return;
            }
            if (!succeeded) {
                resetStateMachineForFailure(ProvisioningCallback.OSU_FAILURE_SERVER_CONNECTION);
                return;
            }
            invokeProvisioningCallback(PROVISIONING_STATUS,
                    ProvisioningCallback.OSU_STATUS_SERVER_CONNECTED);
            mProvisioningStateMachine.getHandler().post(() -> initSoapExchange());
        }

        /**
         * Handles server validation failure
         *
         * @param sessionId indicating current session ID
         * Note: Called on main thread (WifiService thread).
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
            if (mState != STATE_OSU_SERVER_CONNECTING) {
                Log.wtf(TAG, "Server Validation Failure unhandled in mState=" + mState);
                return;
            }
            resetStateMachineForFailure(ProvisioningCallback.OSU_FAILURE_SERVER_VALIDATION);
        }

        /**
         * Handles status of server validation success
         *
         * @param sessionId indicating current session ID
         * Note: Called on main thread (WifiService thread).
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
            if (mState != STATE_OSU_SERVER_CONNECTING) {
                Log.wtf(TAG, "Server validation success event unhandled in state=" + mState);
                return;
            }
            if (!mOsuServerConnection.validateProvider(
                    Locale.getDefault(), mOsuProvider.getFriendlyName())) {
                Log.e(TAG,
                        "OSU Server certificate does not have the one matched with the selected "
                                + "Service Name: "
                                + mOsuProvider.getFriendlyName());
                resetStateMachineForFailure(
                        ProvisioningCallback.OSU_FAILURE_SERVICE_PROVIDER_VERIFICATION);
                return;
            }
            invokeProvisioningCallback(PROVISIONING_STATUS,
                    ProvisioningCallback.OSU_STATUS_SERVER_VALIDATED);
        }

        /**
         * Handles next step once receiving a HTTP redirect response.
         *
         * Note: Called on main thread (WifiService thread).
         */
        public void handleRedirectResponse() {
            if (mState != STATE_WAITING_FOR_REDIRECT_RESPONSE) {
                Log.e(TAG, "Received redirect request in wrong state=" + mState);
                resetStateMachineForFailure(ProvisioningCallback.OSU_FAILURE_PROVISIONING_ABORTED);
                return;
            }

            invokeProvisioningCallback(PROVISIONING_STATUS,
                    ProvisioningCallback.OSU_STATUS_REDIRECT_RESPONSE_RECEIVED);
            mRedirectListener.stopServer(mRedirectStartStopHandler);
            secondSoapExchange();
        }

        /**
         * Handles next step when timeout occurs because {@link RedirectListener} doesn't
         * receive a HTTP redirect response.
         *
         * Note: Called on main thread (WifiService thread).
         */
        public void handleTimeOutForRedirectResponse() {
            Log.e(TAG, "Timed out for HTTP redirect response");

            if (mState != STATE_WAITING_FOR_REDIRECT_RESPONSE) {
                Log.e(TAG, "Received timeout error for HTTP redirect response  in wrong state="
                        + mState);
                resetStateMachineForFailure(ProvisioningCallback.OSU_FAILURE_PROVISIONING_ABORTED);
                return;
            }
            mRedirectListener.stopServer(mRedirectStartStopHandler);
            resetStateMachineForFailure(
                    ProvisioningCallback.OSU_FAILURE_TIMED_OUT_REDIRECT_LISTENER);
        }

        /**
         * Connected event received
         *
         * @param network Network object for this connection
         * Note: Called on main thread (WifiService thread).
         */
        public void handleConnectedEvent(Network network) {
            if (mVerboseLoggingEnabled) {
                Log.v(TAG, "Connected event received in state=" + mState);
            }
            if (mState != STATE_AP_CONNECTING) {
                // Not waiting for a connection
                Log.wtf(TAG, "Connection event unhandled in state=" + mState);
                return;
            }
            invokeProvisioningCallback(PROVISIONING_STATUS,
                    ProvisioningCallback.OSU_STATUS_AP_CONNECTED);
            initiateServerConnection(network);
        }

        /**
         * Handles SOAP message response sent by server
         *
         * @param sessionId       indicating current session ID
         * @param responseMessage SOAP SPP response, or {@code null} in any failure.
         * Note: Called on main thread (WifiService thread).
         */
        public void handleSoapMessageResponse(int sessionId,
                @Nullable SppResponseMessage responseMessage) {
            if (sessionId != mCurrentSessionId) {
                Log.w(TAG, "Expected soapMessageResponse callback for currentSessionId="
                        + mCurrentSessionId);
                return;
            }

            if (responseMessage == null) {
                Log.e(TAG, "failed to send the sppPostDevData message");
                resetStateMachineForFailure(ProvisioningCallback.OSU_FAILURE_SOAP_MESSAGE_EXCHANGE);
                return;
            }

            if (mState == STATE_WAITING_FOR_FIRST_SOAP_RESPONSE) {
                if (responseMessage.getMessageType()
                        != SppResponseMessage.MessageType.POST_DEV_DATA_RESPONSE) {
                    Log.e(TAG, "Expected a PostDevDataResponse, but got "
                            + responseMessage.getMessageType());
                    resetStateMachineForFailure(
                            ProvisioningCallback.OSU_FAILURE_UNEXPECTED_SOAP_MESSAGE_TYPE);
                    return;
                }

                PostDevDataResponse devDataResponse = (PostDevDataResponse) responseMessage;
                mSessionId = devDataResponse.getSessionID();
                if (devDataResponse.getSppCommand().getExecCommandId()
                        != SppCommand.ExecCommandId.BROWSER) {
                    Log.e(TAG, "Expected a launchBrowser command, but got "
                            + devDataResponse.getSppCommand().getExecCommandId());
                    resetStateMachineForFailure(
                            ProvisioningCallback.OSU_FAILURE_UNEXPECTED_COMMAND_TYPE);
                    return;
                }

                Log.d(TAG, "Exec: " + devDataResponse.getSppCommand().getExecCommandId() + ", for '"
                        + devDataResponse.getSppCommand().getCommandData() + "'");

                mWebUrl = ((BrowserUri) devDataResponse.getSppCommand().getCommandData()).getUri();
                if (mWebUrl == null) {
                    Log.e(TAG, "No Web-Url");
                    resetStateMachineForFailure(
                            ProvisioningCallback.OSU_FAILURE_INVALID_URL_FORMAT_FOR_OSU);
                    return;
                }

                if (!mWebUrl.toLowerCase(Locale.US).contains(mSessionId.toLowerCase(Locale.US))) {
                    Log.e(TAG, "Bad or Missing session ID in webUrl");
                    resetStateMachineForFailure(
                            ProvisioningCallback.OSU_FAILURE_INVALID_URL_FORMAT_FOR_OSU);
                    return;
                }
                launchOsuWebView();
            } else if (mState == STATE_WAITING_FOR_SECOND_SOAP_RESPONSE) {
                if (responseMessage.getMessageType()
                        != SppResponseMessage.MessageType.POST_DEV_DATA_RESPONSE) {
                    Log.e(TAG, "Expected a PostDevDataResponse, but got "
                            + responseMessage.getMessageType());
                    resetStateMachineForFailure(
                            ProvisioningCallback.OSU_FAILURE_UNEXPECTED_SOAP_MESSAGE_TYPE);
                    return;
                }

                PostDevDataResponse devDataResponse = (PostDevDataResponse) responseMessage;
                if (devDataResponse.getSppCommand() == null
                        || devDataResponse.getSppCommand().getSppCommandId()
                        != SppCommand.CommandId.ADD_MO) {
                    Log.e(TAG, "Expected a ADD_MO command, but got " + (
                            (devDataResponse.getSppCommand() == null) ? "null"
                                    : devDataResponse.getSppCommand().getSppCommandId()));
                    resetStateMachineForFailure(
                            ProvisioningCallback.OSU_FAILURE_UNEXPECTED_COMMAND_TYPE);
                    return;
                }

                mPasspointConfiguration = buildPasspointConfiguration(
                        (PpsMoData) devDataResponse.getSppCommand().getCommandData());
                thirdSoapExchange(mPasspointConfiguration == null);
            } else if (mState == STATE_WAITING_FOR_THIRD_SOAP_RESPONSE) {
                if (responseMessage.getMessageType()
                        != SppResponseMessage.MessageType.EXCHANGE_COMPLETE) {
                    Log.e(TAG, "Expected a ExchangeCompleteMessage, but got "
                            + responseMessage.getMessageType());
                    resetStateMachineForFailure(
                            ProvisioningCallback.OSU_FAILURE_UNEXPECTED_SOAP_MESSAGE_TYPE);
                    return;
                }

                ExchangeCompleteMessage exchangeCompleteMessage =
                        (ExchangeCompleteMessage) responseMessage;
                if (exchangeCompleteMessage.getStatus()
                        != SppConstants.SppStatus.EXCHANGE_COMPLETE) {
                    Log.e(TAG, "Expected a ExchangeCompleteMessage Status, but got "
                            + exchangeCompleteMessage.getStatus());
                    resetStateMachineForFailure(
                            ProvisioningCallback.OSU_FAILURE_UNEXPECTED_SOAP_MESSAGE_STATUS);
                    return;
                }

                if (exchangeCompleteMessage.getError() != SppConstants.INVALID_SPP_CONSTANT) {
                    Log.e(TAG,
                            "In the SppExchangeComplete, got error "
                                    + exchangeCompleteMessage.getError());
                    resetStateMachineForFailure(
                            ProvisioningCallback.OSU_FAILURE_PROVISIONING_ABORTED);
                    return;
                }
                if (mPasspointConfiguration == null) {
                    Log.e(TAG, "No PPS MO to use for retrieving TrustCerts");
                    resetStateMachineForFailure(ProvisioningCallback.OSU_FAILURE_NO_PPS_MO);
                    return;
                }
                retrieveTrustRootCerts(mPasspointConfiguration);
            } else {
                if (mVerboseLoggingEnabled) {
                    Log.v(TAG, "Received an unexpected SOAP message in state=" + mState);
                }
            }
        }

        /**
         * Installs the trust root CA certificates for AAA, Remediation and Policy Server
         *
         * @param sessionId             indicating current session ID
         * @param trustRootCertificates trust root CA certificates to be installed.
         */
        public void installTrustRootCertificates(int sessionId,
                @NonNull Map<Integer, List<X509Certificate>> trustRootCertificates) {
            if (sessionId != mCurrentSessionId) {
                Log.w(TAG, "Expected TrustRootCertificates callback for currentSessionId="
                        + mCurrentSessionId);
                return;
            }
            if (mState != STATE_WAITING_FOR_TRUST_ROOT_CERTS) {
                if (mVerboseLoggingEnabled) {
                    Log.v(TAG, "Received an unexpected TrustRootCertificates in state=" + mState);
                }
                return;
            }

            if (trustRootCertificates.isEmpty()) {
                Log.e(TAG, "fails to retrieve trust root certificates");
                resetStateMachineForFailure(
                        ProvisioningCallback.OSU_FAILURE_RETRIEVE_TRUST_ROOT_CERTIFICATES);
                return;
            }

            List<X509Certificate> certificates = trustRootCertificates.get(
                    OsuServerConnection.TRUST_CERT_TYPE_AAA);
            if (certificates == null || certificates.isEmpty()) {
                Log.e(TAG, "fails to retrieve trust root certificate for AAA server");
                resetStateMachineForFailure(
                        ProvisioningCallback.OSU_FAILURE_NO_AAA_TRUST_ROOT_CERTIFICATE);
                return;
            }

            // Save the service friendly names from OsuProvider to keep this in the profile.
            mPasspointConfiguration.setServiceFriendlyNames(mOsuProvider.getFriendlyNameList());

            mPasspointConfiguration.getCredential().setCaCertificates(
                    certificates.toArray(new X509Certificate[0]));

            certificates = trustRootCertificates.get(
                    OsuServerConnection.TRUST_CERT_TYPE_REMEDIATION);
            if (certificates == null || certificates.isEmpty()) {
                Log.e(TAG, "fails to retrieve trust root certificate for Remediation");
                resetStateMachineForFailure(
                        ProvisioningCallback.OSU_FAILURE_RETRIEVE_TRUST_ROOT_CERTIFICATES);
                return;
            }

            if (mPasspointConfiguration.getSubscriptionUpdate() != null) {
                mPasspointConfiguration.getSubscriptionUpdate().setCaCertificate(
                        certificates.get(0));
            }

            try {
                mWifiManager.addOrUpdatePasspointConfiguration(mPasspointConfiguration);
            } catch (IllegalArgumentException e) {
                Log.e(TAG, "fails to add a new PasspointConfiguration: " + e);
                resetStateMachineForFailure(
                        ProvisioningCallback.OSU_FAILURE_ADD_PASSPOINT_CONFIGURATION);
                return;
            }

            invokeProvisioningCompleteCallback();
            if (mVerboseLoggingEnabled) {
                Log.i(TAG, "Provisioning is complete for "
                        + mPasspointConfiguration.getHomeSp().getFqdn());
            }
            resetStateMachine();
        }

        /**
         * Disconnect event received
         *
         * Note: Called on main thread (WifiService thread).
         */
        public void handleDisconnect() {
            if (mVerboseLoggingEnabled) {
                Log.v(TAG, "Connection failed in state=" + mState);
            }
            if (mState == STATE_INIT) {
                Log.w(TAG, "Disconnect event unhandled in state=" + mState);
                return;
            }
            mNetwork = null;
            resetStateMachineForFailure(ProvisioningCallback.OSU_FAILURE_AP_CONNECTION);
        }

        /**
         * Establishes TLS session to the server(OSU Server, Remediation or Policy Server).
         *
         * @param network current {@link Network} associated with the target AP.
         */
        private void initiateServerConnection(Network network) {
            if (mVerboseLoggingEnabled) {
                Log.v(TAG, "Initiating server connection in state=" + mState);
            }

            if (!mOsuServerConnection.connect(mServerUrl, network)) {
                resetStateMachineForFailure(ProvisioningCallback.OSU_FAILURE_SERVER_CONNECTION);
                return;
            }
            mNetwork = network;
            changeState(STATE_OSU_SERVER_CONNECTING);
            invokeProvisioningCallback(PROVISIONING_STATUS,
                    ProvisioningCallback.OSU_STATUS_SERVER_CONNECTING);
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

        private void invokeProvisioningCompleteCallback() {
            mWifiMetrics.incrementPasspointProvisionSuccess();
            if (mProvisioningCallback == null) {
                Log.e(TAG, "No provisioning complete callback registered");
                return;
            }
            try {
                mProvisioningCallback.onProvisioningComplete();
            } catch (RemoteException e) {
                Log.e(TAG, "Remote Exception while posting provisioning complete");
            }
        }

        /**
         * Initiates the SOAP message exchange with sending the sppPostDevData message.
         */
        private void initSoapExchange() {
            if (mVerboseLoggingEnabled) {
                Log.v(TAG, "Initiates soap message exchange in state =" + mState);
            }

            if (mState != STATE_OSU_SERVER_CONNECTING) {
                Log.e(TAG, "Initiates soap message exchange in wrong state=" + mState);
                resetStateMachineForFailure(ProvisioningCallback.OSU_FAILURE_PROVISIONING_ABORTED);
                return;
            }

            // Redirect uri used for signal of completion for registration process.
            final URL redirectUri = mRedirectListener.getServerUrl();

            // Sending the first sppPostDevDataRequest message.
            if (mOsuServerConnection.exchangeSoapMessage(
                    PostDevDataMessage.serializeToSoapEnvelope(mContext, mSystemInfo,
                            redirectUri.toString(),
                            SppConstants.SppReason.SUBSCRIPTION_REGISTRATION, null))) {
                invokeProvisioningCallback(PROVISIONING_STATUS,
                        ProvisioningCallback.OSU_STATUS_INIT_SOAP_EXCHANGE);
                // Move to initiate soap exchange
                changeState(STATE_WAITING_FOR_FIRST_SOAP_RESPONSE);
            } else {
                Log.e(TAG, "HttpsConnection is not established for soap message exchange");
                resetStateMachineForFailure(ProvisioningCallback.OSU_FAILURE_SOAP_MESSAGE_EXCHANGE);
                return;
            }
        }

        /**
         * Launches OsuLogin Application for users to register a new subscription.
         */
        private void launchOsuWebView() {
            if (mVerboseLoggingEnabled) {
                Log.v(TAG, "launch Osu webview in state =" + mState);
            }

            if (mState != STATE_WAITING_FOR_FIRST_SOAP_RESPONSE) {
                Log.e(TAG, "launch Osu webview in wrong state =" + mState);
                resetStateMachineForFailure(ProvisioningCallback.OSU_FAILURE_PROVISIONING_ABORTED);
                return;
            }

            // Start the redirect server to listen the HTTP redirect response from server
            // as completion of user input.
            if (!mRedirectListener.startServer(new RedirectListener.RedirectCallback() {
                /** Called on different thread (RedirectListener thread). */
                @Override
                public void onRedirectReceived() {
                    if (mVerboseLoggingEnabled) {
                        Log.v(TAG, "Received HTTP redirect response");
                    }
                    mProvisioningStateMachine.getHandler().post(() -> handleRedirectResponse());
                }

                /** Called on main thread (WifiService thread). */
                @Override
                public void onRedirectTimedOut() {
                    if (mVerboseLoggingEnabled) {
                        Log.v(TAG, "Timed out to receive a HTTP redirect response");
                    }
                    mProvisioningStateMachine.handleTimeOutForRedirectResponse();
                }
            }, mRedirectStartStopHandler)) {
                Log.e(TAG, "fails to start redirect listener");
                resetStateMachineForFailure(
                        ProvisioningCallback.OSU_FAILURE_START_REDIRECT_LISTENER);
                return;
            }

            Intent intent = new Intent(WifiManager.ACTION_PASSPOINT_LAUNCH_OSU_VIEW);
            intent.setPackage(OSU_APP_PACKAGE);
            intent.putExtra(WifiManager.EXTRA_OSU_NETWORK, mNetwork);
            intent.putExtra(WifiManager.EXTRA_URL, mWebUrl);

            intent.setFlags(
                    Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT | Intent.FLAG_ACTIVITY_NEW_TASK);

            // Verify that the intent will resolve to an activity
            if (intent.resolveActivity(mContext.getPackageManager()) != null) {
                mContext.startActivityAsUser(intent, UserHandle.CURRENT);
                invokeProvisioningCallback(PROVISIONING_STATUS,
                        ProvisioningCallback.OSU_STATUS_WAITING_FOR_REDIRECT_RESPONSE);
                changeState(STATE_WAITING_FOR_REDIRECT_RESPONSE);
            } else {
                Log.e(TAG, "can't resolve the activity for the intent");
                resetStateMachineForFailure(ProvisioningCallback.OSU_FAILURE_NO_OSU_ACTIVITY_FOUND);
                return;
            }
        }

        /**
         * Initiates the second SOAP message exchange with sending the sppPostDevData message.
         */
        private void secondSoapExchange() {
            if (mVerboseLoggingEnabled) {
                Log.v(TAG, "Initiates the second soap message exchange in state =" + mState);
            }

            if (mState != STATE_WAITING_FOR_REDIRECT_RESPONSE) {
                Log.e(TAG, "Initiates the second soap message exchange in wrong state=" + mState);
                resetStateMachineForFailure(ProvisioningCallback.OSU_FAILURE_PROVISIONING_ABORTED);
                return;
            }

            // Sending the second sppPostDevDataRequest message.
            if (mOsuServerConnection.exchangeSoapMessage(
                    PostDevDataMessage.serializeToSoapEnvelope(mContext, mSystemInfo,
                            mRedirectListener.getServerUrl().toString(),
                            SppConstants.SppReason.USER_INPUT_COMPLETED, mSessionId))) {
                invokeProvisioningCallback(PROVISIONING_STATUS,
                        ProvisioningCallback.OSU_STATUS_SECOND_SOAP_EXCHANGE);
                changeState(STATE_WAITING_FOR_SECOND_SOAP_RESPONSE);
            } else {
                Log.e(TAG, "HttpsConnection is not established for soap message exchange");
                resetStateMachineForFailure(ProvisioningCallback.OSU_FAILURE_SOAP_MESSAGE_EXCHANGE);
                return;
            }
        }

        /**
         * Initiates the third SOAP message exchange with sending the sppUpdateResponse message.
         */
        private void thirdSoapExchange(boolean isError) {
            if (mVerboseLoggingEnabled) {
                Log.v(TAG, "Initiates the third soap message exchange in state =" + mState);
            }

            if (mState != STATE_WAITING_FOR_SECOND_SOAP_RESPONSE) {
                Log.e(TAG, "Initiates the third soap message exchange in wrong state=" + mState);
                resetStateMachineForFailure(ProvisioningCallback.OSU_FAILURE_PROVISIONING_ABORTED);
                return;
            }

            // Sending the sppUpdateResponse message.
            if (mOsuServerConnection.exchangeSoapMessage(
                    UpdateResponseMessage.serializeToSoapEnvelope(mSessionId, isError))) {
                invokeProvisioningCallback(PROVISIONING_STATUS,
                        ProvisioningCallback.OSU_STATUS_THIRD_SOAP_EXCHANGE);
                changeState(STATE_WAITING_FOR_THIRD_SOAP_RESPONSE);
            } else {
                Log.e(TAG, "HttpsConnection is not established for soap message exchange");
                resetStateMachineForFailure(ProvisioningCallback.OSU_FAILURE_SOAP_MESSAGE_EXCHANGE);
                return;
            }
        }

        /**
         * Builds {@link PasspointConfiguration} object from PPS(PerProviderSubscription)
         * MO(Management Object).
         */
        private PasspointConfiguration buildPasspointConfiguration(@NonNull PpsMoData moData) {
            String moTree = moData.getPpsMoTree();

            PasspointConfiguration passpointConfiguration = PpsMoParser.parseMoText(moTree);
            if (passpointConfiguration == null) {
                Log.e(TAG, "fails to parse the MoTree");
                return null;
            }

            if (!passpointConfiguration.validateForR2()) {
                Log.e(TAG, "PPS MO received is invalid: " + passpointConfiguration);
                return null;
            }

            if (mVerboseLoggingEnabled) {
                Log.d(TAG, "The parsed PasspointConfiguration: " + passpointConfiguration);
            }

            return passpointConfiguration;
        }

        /**
         * Retrieves Trust Root CA Certificates from server url defined in PPS
         * (PerProviderSubscription) MO(Management Object).
         */
        private void retrieveTrustRootCerts(@NonNull PasspointConfiguration passpointConfig) {
            if (mVerboseLoggingEnabled) {
                Log.v(TAG, "Initiates retrieving trust root certs in state =" + mState);
            }

            Map<String, byte[]> trustCertInfo = passpointConfig.getTrustRootCertList();
            if (trustCertInfo == null || trustCertInfo.isEmpty()) {
                Log.e(TAG, "no AAATrustRoot Node found");
                resetStateMachineForFailure(
                        ProvisioningCallback.OSU_FAILURE_NO_AAA_SERVER_TRUST_ROOT_NODE);
                return;
            }
            Map<Integer, Map<String, byte[]>> allTrustCerts = new HashMap<>();
            allTrustCerts.put(OsuServerConnection.TRUST_CERT_TYPE_AAA, trustCertInfo);

            // SubscriptionUpdate is a required node.
            if (passpointConfig.getSubscriptionUpdate() != null
                    && passpointConfig.getSubscriptionUpdate().getTrustRootCertUrl() != null) {
                trustCertInfo = new HashMap<>();
                trustCertInfo.put(
                        passpointConfig.getSubscriptionUpdate().getTrustRootCertUrl(),
                        passpointConfig.getSubscriptionUpdate()
                                .getTrustRootCertSha256Fingerprint());
                allTrustCerts.put(OsuServerConnection.TRUST_CERT_TYPE_REMEDIATION, trustCertInfo);
            } else {
                Log.e(TAG, "no TrustRoot Node for remediation server found");
                resetStateMachineForFailure(
                        ProvisioningCallback.OSU_FAILURE_NO_REMEDIATION_SERVER_TRUST_ROOT_NODE);
                return;
            }

            // Policy is an optional node
            if (passpointConfig.getPolicy() != null) {
                if (passpointConfig.getPolicy().getPolicyUpdate() != null
                        && passpointConfig.getPolicy().getPolicyUpdate().getTrustRootCertUrl()
                        != null) {
                    trustCertInfo = new HashMap<>();
                    trustCertInfo.put(
                            passpointConfig.getPolicy().getPolicyUpdate()
                                    .getTrustRootCertUrl(),
                            passpointConfig.getPolicy().getPolicyUpdate()
                                    .getTrustRootCertSha256Fingerprint());
                    allTrustCerts.put(OsuServerConnection.TRUST_CERT_TYPE_POLICY, trustCertInfo);
                } else {
                    Log.e(TAG, "no TrustRoot Node for policy server found");
                    resetStateMachineForFailure(
                            ProvisioningCallback.OSU_FAILURE_NO_POLICY_SERVER_TRUST_ROOT_NODE);
                    return;
                }
            }

            if (mOsuServerConnection.retrieveTrustRootCerts(allTrustCerts)) {
                invokeProvisioningCallback(PROVISIONING_STATUS,
                        ProvisioningCallback.OSU_STATUS_RETRIEVING_TRUST_ROOT_CERTS);
                changeState(STATE_WAITING_FOR_TRUST_ROOT_CERTS);
            } else {
                Log.e(TAG, "HttpsConnection is not established for retrieving trust root certs");
                resetStateMachineForFailure(ProvisioningCallback.OSU_FAILURE_SERVER_CONNECTION);
                return;
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

        private void resetStateMachineForFailure(int failureCode) {
            mWifiMetrics.incrementPasspointProvisionFailure(failureCode);
            invokeProvisioningCallback(PROVISIONING_FAILURE, failureCode);
            resetStateMachine();
        }

        private void resetStateMachine() {
            if (mRedirectListener != null) {
                mRedirectListener.stopServer(mRedirectStartStopHandler);
            }
            mOsuNetworkConnection.setEventCallback(null);
            mOsuNetworkConnection.disconnectIfNeeded();
            mOsuServerConnection.setEventCallback(null);
            mOsuServerConnection.cleanup();
            mPasspointConfiguration = null;
            mProvisioningCallback = null;
            changeState(STATE_INIT);
        }

        /**
         * Get a best matching osuProvider from scanResults with provided osuProvider
         *
         * @param scanResults a list of {@link ScanResult} to find a best osuProvider
         * @param osuProvider an instance of {@link OsuProvider} used to match with scanResults
         * @return a best matching {@link OsuProvider}, {@code null} when an invalid scanResults are
         * provided or no match is found.
         */
        private OsuProvider getBestMatchingOsuProvider(
                List<ScanResult> scanResults,
                OsuProvider osuProvider) {
            if (scanResults == null) {
                Log.e(TAG, "Attempt to retrieve OSU providers for a null ScanResult");
                return null;
            }

            if (osuProvider == null) {
                Log.e(TAG, "Attempt to retrieve best OSU provider for a null osuProvider");
                return null;
            }

            // Clear the OSU SSID to compare it with other OsuProviders only about service
            // provider information.
            osuProvider.setOsuSsid(null);

            // Filter non-Passpoint AP out and sort it by descending order of signal strength.
            scanResults = scanResults.stream()
                    .filter((scanResult) -> scanResult.isPasspointNetwork())
                    .sorted((sr1, sr2) -> sr2.level - sr1.level)
                    .collect(Collectors.toList());

            for (ScanResult scanResult : scanResults) {
                // Lookup OSU Providers ANQP element by ANQPNetworkKey.
                // It might have same ANQP element with another one which has same ANQP domain id.
                Map<Constants.ANQPElementType, ANQPElement> anqpElements =
                        mPasspointManager.getANQPElements(
                                scanResult);
                HSOsuProvidersElement element =
                        (HSOsuProvidersElement) anqpElements.get(
                                Constants.ANQPElementType.HSOSUProviders);
                if (element == null) continue;
                for (OsuProviderInfo info : element.getProviders()) {
                    OsuProvider candidate = new OsuProvider(null, info.getFriendlyNames(),
                            info.getServiceDescription(), info.getServerUri(),
                            info.getNetworkAccessIdentifier(), info.getMethodList(), null);
                    if (candidate.equals(osuProvider)) {
                        // Found a matching candidate and then set OSU SSID for the OSU provider.
                        candidate.setOsuSsid(element.getOsuSsid());
                        return candidate;
                    }
                }
            }
            return null;
        }
    }

    /**
     * Callbacks for network and wifi events
     *
     * Note: Called on main thread (WifiService thread).
     */
    class OsuNetworkCallbacks implements OsuNetworkConnection.Callbacks {

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
     *
     * Note: Called on main thread (WifiService thread).
     */
    public class OsuServerCallbacks {
        private final int mSessionId;

        OsuServerCallbacks(int sessionId) {
            mSessionId = sessionId;
        }

        /**
         * Returns the session ID corresponding to this callback
         *
         * @return int sessionID
         */
        public int getSessionId() {
            return mSessionId;
        }

        /**
         * Callback when a TLS connection to the server is failed.
         *
         * @param sessionId indicating current session ID
         * @param succeeded boolean indicating success/failure of server connection
         */
        public void onServerConnectionStatus(int sessionId, boolean succeeded) {
            if (mVerboseLoggingEnabled) {
                Log.v(TAG, "OSU Server connection status=" + succeeded + " sessionId=" + sessionId);
            }
            mProvisioningStateMachine.getHandler().post(() ->
                    mProvisioningStateMachine.handleServerConnectionStatus(sessionId, succeeded));
        }

        /**
         * Provides a server validation status for the session ID
         *
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

        /**
         * Callback when soap message is received from server.
         *
         * @param sessionId       indicating current session ID
         * @param responseMessage SOAP SPP response parsed or {@code null} in any failure
         * Note: Called on different thread (OsuServer Thread)!
         */
        public void onReceivedSoapMessage(int sessionId,
                @Nullable SppResponseMessage responseMessage) {
            if (mVerboseLoggingEnabled) {
                Log.v(TAG, "onReceivedSoapMessage with sessionId=" + sessionId);
            }
            mProvisioningStateMachine.getHandler().post(() ->
                    mProvisioningStateMachine.handleSoapMessageResponse(sessionId,
                            responseMessage));
        }

        /**
         * Callback when trust root certificates are retrieved from server.
         *
         * @param sessionId             indicating current session ID
         * @param trustRootCertificates trust root CA certificates retrieved from server
         * Note: Called on different thread (OsuServer Thread)!
         */
        public void onReceivedTrustRootCertificates(int sessionId,
                @NonNull Map<Integer, List<X509Certificate>> trustRootCertificates) {
            if (mVerboseLoggingEnabled) {
                Log.v(TAG, "onReceivedTrustRootCertificates with sessionId=" + sessionId);
            }
            mProvisioningStateMachine.getHandler().post(() ->
                    mProvisioningStateMachine.installTrustRootCertificates(sessionId,
                            trustRootCertificates));
        }
    }
}
