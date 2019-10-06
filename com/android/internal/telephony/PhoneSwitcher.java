/*
* Copyright (C) 2015 The Android Open Source Project
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

package com.android.internal.telephony;

import static android.net.NetworkCapabilities.TRANSPORT_CELLULAR;
import static android.telephony.SubscriptionManager.DEFAULT_SUBSCRIPTION_ID;
import static android.telephony.SubscriptionManager.INVALID_PHONE_INDEX;
import static android.telephony.SubscriptionManager.INVALID_SUBSCRIPTION_ID;
import static android.telephony.TelephonyManager.SET_OPPORTUNISTIC_SUB_INACTIVE_SUBSCRIPTION;
import static android.telephony.TelephonyManager.SET_OPPORTUNISTIC_SUB_SUCCESS;
import static android.telephony.TelephonyManager.SET_OPPORTUNISTIC_SUB_VALIDATION_FAILED;

import android.annotation.UnsupportedAppUsage;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.ConnectivityManager.NetworkCallback;
import android.net.MatchAllNetworkSpecifier;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkFactory;
import android.net.NetworkRequest;
import android.net.NetworkSpecifier;
import android.net.StringNetworkSpecifier;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Registrant;
import android.os.RegistrantList;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.telephony.PhoneCapability;
import android.telephony.PhoneStateListener;
import android.telephony.Rlog;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.telephony.data.ApnSetting;
import android.util.LocalLog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.telephony.dataconnection.DcRequest;
import com.android.internal.telephony.metrics.TelephonyMetrics;
import com.android.internal.telephony.nano.TelephonyProto.TelephonyEvent;
import com.android.internal.telephony.nano.TelephonyProto.TelephonyEvent.DataSwitch;
import com.android.internal.telephony.nano.TelephonyProto.TelephonyEvent.OnDemandDataSwitch;
import com.android.internal.util.IndentingPrintWriter;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Utility singleton to monitor subscription changes and incoming NetworkRequests
 * and determine which phone/phones are active.
 *
 * Manages the ALLOW_DATA calls to modems and notifies phones about changes to
 * the active phones.  Note we don't wait for data attach (which may not happen anyway).
 */
public class PhoneSwitcher extends Handler {
    private static final String LOG_TAG = "PhoneSwitcher";
    private static final boolean VDBG = false;

    private static final int DEFAULT_NETWORK_CHANGE_TIMEOUT_MS = 5000;
    private static final int MODEM_COMMAND_RETRY_PERIOD_MS     = 5000;
    // After the emergency call ends, wait for a few seconds to see if we enter ECBM before starting
    // the countdown to remove the emergency DDS override.
    @VisibleForTesting
    // not final for testing.
    public static int ECBM_DEFAULT_DATA_SWITCH_BASE_TIME_MS = 5000;
    // Wait for a few seconds after the override request comes in to receive the outgoing call
    // event. If it does not happen before the timeout specified, cancel the override.
    @VisibleForTesting
    public static int DEFAULT_DATA_OVERRIDE_TIMEOUT_MS = 5000;

    // If there are no subscriptions in a device, then the phone to be used for emergency should
    // always be the "first" phone.
    private static final int DEFAULT_EMERGENCY_PHONE_ID = 0;

    /**
     * Container for an ongoing request to override the DDS in the context of an ongoing emergency
     * call to allow for carrier specific operations, such as provide SUPL updates during or after
     * the emergency call, since some modems do not support these operations on the non DDS.
     */
    private static final class EmergencyOverrideRequest {
        /* The Phone ID that the DDS should be set to. */
        int mPhoneId = INVALID_PHONE_INDEX;
        /* The time after the emergency call ends that the DDS should be overridden for. */
        int mGnssOverrideTimeMs = -1;
        /* A callback to the requester notifying them if the initial call to the modem to override
         * the DDS was successful.
         */
        CompletableFuture<Boolean> mOverrideCompleteFuture;
        /* In the special case that the device goes into emergency callback mode after the emergency
         * call ends, keep the override until ECM finishes and then start the mGnssOverrideTimeMs
         * timer to leave DDS override.
         */
        boolean mRequiresEcmFinish = false;

        /*
         * Keeps track of whether or not this request has already serviced the outgoing emergency
         * call. Once finished, do not delay for any other calls.
         */
        boolean mPendingOriginatingCall = true;

        /**
         * @return true if there is a pending override complete callback.
         */
        boolean isCallbackAvailable() {
            return mOverrideCompleteFuture != null;
        }

        /**
         * Send the override complete callback the result of setting the DDS to the new value.
         */
        void sendOverrideCompleteCallbackResultAndClear(boolean result) {
            if (isCallbackAvailable()) {
                mOverrideCompleteFuture.complete(result);
                mOverrideCompleteFuture = null;
            }
        }


        @Override
        public String toString() {
            return String.format("EmergencyOverrideRequest: [phoneId= %d, overrideMs= %d,"
                    + " hasCallback= %b, ecmFinishStatus= %b]", mPhoneId, mGnssOverrideTimeMs,
                    isCallbackAvailable(), mRequiresEcmFinish);
        }
    }

    private final List<DcRequest> mPrioritizedDcRequests = new ArrayList<DcRequest>();
    private final RegistrantList mActivePhoneRegistrants;
    private final SubscriptionController mSubscriptionController;
    private final int[] mPhoneSubscriptions;
    private final CommandsInterface[] mCommandsInterfaces;
    private final Context mContext;
    private final PhoneState[] mPhoneStates;
    @UnsupportedAppUsage
    private final int mNumPhones;
    @UnsupportedAppUsage
    private final Phone[] mPhones;
    private final LocalLog mLocalLog;
    @VisibleForTesting
    public final PhoneStateListener mPhoneStateListener;
    private final CellularNetworkValidator mValidator;
    @VisibleForTesting
    public final CellularNetworkValidator.ValidationCallback mValidationCallback =
            (validated, subId) -> Message.obtain(PhoneSwitcher.this,
                    EVENT_NETWORK_VALIDATION_DONE, subId, validated ? 1 : 0).sendToTarget();
    @UnsupportedAppUsage
    private int mMaxActivePhones;
    private static PhoneSwitcher sPhoneSwitcher = null;

    // Which primary (non-opportunistic) subscription is set as data subscription among all primary
    // subscriptions. This value usually comes from user setting, and it's the subscription used for
    // Internet data if mOpptDataSubId is not set.
    private int mPrimaryDataSubId = SubscriptionManager.INVALID_SUBSCRIPTION_ID;

    // mOpptDataSubId must be an active subscription. If it's set, it overrides mPrimaryDataSubId
    // to be used for Internet data.
    private int mOpptDataSubId = SubscriptionManager.DEFAULT_SUBSCRIPTION_ID;

    // The phone ID that has an active voice call. If set, and its mobile data setting is on,
    // it will become the mPreferredDataPhoneId.
    private int mPhoneIdInVoiceCall = SubscriptionManager.INVALID_PHONE_INDEX;

    @VisibleForTesting
    // It decides:
    // 1. In modem layer, which modem is DDS (preferred to have data traffic on)
    // 2. In TelephonyNetworkFactory, which subscription will apply default network requests, which
    //    are requests without specifying a subId.
    // Corresponding phoneId after considering mOpptDataSubId, mPrimaryDataSubId and
    // mPhoneIdInVoiceCall above.
    protected int mPreferredDataPhoneId = SubscriptionManager.INVALID_PHONE_INDEX;

    // Subscription ID corresponds to mPreferredDataPhoneId.
    private int mPreferredDataSubId = SubscriptionManager.INVALID_SUBSCRIPTION_ID;

    // If non-null, An emergency call is about to be started, is ongoing, or has just ended and we
    // are overriding the DDS.
    // Internal state, should ONLY be accessed/modified inside of the handler.
    private EmergencyOverrideRequest mEmergencyOverride;

    private ISetOpportunisticDataCallback mSetOpptSubCallback;

    private static final int EVENT_PRIMARY_DATA_SUB_CHANGED       = 101;
    private static final int EVENT_SUBSCRIPTION_CHANGED           = 102;
    private static final int EVENT_REQUEST_NETWORK                = 103;
    private static final int EVENT_RELEASE_NETWORK                = 104;
    // ECBM has started/ended. If we just ended an emergency call and mEmergencyOverride is not
    // null, we will wait for EVENT_EMERGENCY_TOGGLE again with ECBM ending to send the message
    // EVENT_REMOVE_DDS_EMERGENCY_OVERRIDE to remove the override after the mEmergencyOverride
    // override timer ends.
    private static final int EVENT_EMERGENCY_TOGGLE               = 105;
    private static final int EVENT_RADIO_CAPABILITY_CHANGED       = 106;
    private static final int EVENT_OPPT_DATA_SUB_CHANGED          = 107;
    private static final int EVENT_RADIO_AVAILABLE                = 108;
    // A call has either started or ended. If an emergency ended and DDS is overridden using
    // mEmergencyOverride, start the countdown to remove the override using the message
    // EVENT_REMOVE_DDS_EMERGENCY_OVERRIDE. The only exception to this is if the device moves to
    // ECBM, which is detected by EVENT_EMERGENCY_TOGGLE.
    @VisibleForTesting
    public static final int EVENT_PRECISE_CALL_STATE_CHANGED      = 109;
    private static final int EVENT_NETWORK_VALIDATION_DONE        = 110;
    private static final int EVENT_REMOVE_DEFAULT_NETWORK_CHANGE_CALLBACK = 111;
    private static final int EVENT_MODEM_COMMAND_DONE             = 112;
    private static final int EVENT_MODEM_COMMAND_RETRY            = 113;
    @VisibleForTesting
    public static final int EVENT_DATA_ENABLED_CHANGED            = 114;
    // An emergency call is about to be originated and requires the DDS to be overridden.
    // Uses EVENT_PRECISE_CALL_STATE_CHANGED message to start countdown to finish override defined
    // in mEmergencyOverride. If EVENT_PRECISE_CALL_STATE_CHANGED does not come in
    // DEFAULT_DATA_OVERRIDE_TIMEOUT_MS milliseconds, then the override will be removed.
    private static final int EVENT_OVERRIDE_DDS_FOR_EMERGENCY     = 115;
    // If it exists, remove the current mEmergencyOverride DDS override.
    private static final int EVENT_REMOVE_DDS_EMERGENCY_OVERRIDE  = 116;

    // Depending on version of IRadioConfig, we need to send either RIL_REQUEST_ALLOW_DATA if it's
    // 1.0, or RIL_REQUEST_SET_PREFERRED_DATA if it's 1.1 or later. So internally mHalCommandToUse
    // will be either HAL_COMMAND_ALLOW_DATA or HAL_COMMAND_ALLOW_DATA or HAL_COMMAND_UNKNOWN.
    private static final int HAL_COMMAND_UNKNOWN        = 0;
    private static final int HAL_COMMAND_ALLOW_DATA     = 1;
    private static final int HAL_COMMAND_PREFERRED_DATA = 2;
    private int mHalCommandToUse = HAL_COMMAND_UNKNOWN;

    private RadioConfig mRadioConfig;

    private final static int MAX_LOCAL_LOG_LINES = 30;

    // Default timeout value of network validation in millisecond.
    private final static int DEFAULT_VALIDATION_EXPIRATION_TIME = 2000;

    private Boolean mHasRegisteredDefaultNetworkChangeCallback = false;

    private ConnectivityManager mConnectivityManager;

    private final ConnectivityManager.NetworkCallback mDefaultNetworkCallback =
            new NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    if (mConnectivityManager.getNetworkCapabilities(network)
                            .hasTransport(TRANSPORT_CELLULAR)) {
                        logDataSwitchEvent(
                                mOpptDataSubId,
                                TelephonyEvent.EventState.EVENT_STATE_END,
                                TelephonyEvent.DataSwitch.Reason.DATA_SWITCH_REASON_UNKNOWN);
                    }
                    removeDefaultNetworkChangeCallback();
                }
            };

    /**
     * Method to get singleton instance.
     */
    public static PhoneSwitcher getInstance() {
        return sPhoneSwitcher;
    }

    /**
     * Method to create singleton instance.
     */
    public static PhoneSwitcher make(int maxActivePhones, int numPhones, Context context,
            SubscriptionController subscriptionController, Looper looper, ITelephonyRegistry tr,
            CommandsInterface[] cis, Phone[] phones) {
        if (sPhoneSwitcher == null) {
            sPhoneSwitcher = new PhoneSwitcher(maxActivePhones, numPhones, context,
                    subscriptionController, looper, tr, cis, phones);
        }

        return sPhoneSwitcher;
    }

    /** This constructor is only used for testing purpose. */
    @VisibleForTesting
    public PhoneSwitcher(int numPhones, Looper looper) {
        super(looper);
        mMaxActivePhones = 0;
        mSubscriptionController = null;
        mCommandsInterfaces = null;
        mContext = null;
        mPhoneStates = null;
        mPhones = null;
        mLocalLog = null;
        mActivePhoneRegistrants = null;
        mNumPhones = numPhones;
        mPhoneSubscriptions = new int[numPhones];
        mRadioConfig = RadioConfig.getInstance(mContext);
        mPhoneStateListener = new PhoneStateListener(looper) {
            public void onPhoneCapabilityChanged(PhoneCapability capability) {
                onPhoneCapabilityChangedInternal(capability);
            }
        };
        mValidator = CellularNetworkValidator.getInstance();
    }

    private boolean isPhoneInVoiceCallChanged() {
        int oldPhoneIdInVoiceCall = mPhoneIdInVoiceCall;
        // If there's no active call, the value will become INVALID_PHONE_INDEX
        // and internet data will be switched back to system selected or user selected
        // subscription.
        mPhoneIdInVoiceCall = SubscriptionManager.INVALID_PHONE_INDEX;
        for (Phone phone : mPhones) {
            if (isCallActive(phone) || isCallActive(phone.getImsPhone())) {
                mPhoneIdInVoiceCall = phone.getPhoneId();
                break;
            }
        }

        return (mPhoneIdInVoiceCall != oldPhoneIdInVoiceCall);
    }

    @VisibleForTesting
    public PhoneSwitcher(int maxActivePhones, int numPhones, Context context,
            SubscriptionController subscriptionController, Looper looper, ITelephonyRegistry tr,
            CommandsInterface[] cis, Phone[] phones) {
        super(looper);
        mContext = context;
        mNumPhones = numPhones;
        mPhones = phones;
        mPhoneSubscriptions = new int[numPhones];
        mMaxActivePhones = maxActivePhones;
        mLocalLog = new LocalLog(MAX_LOCAL_LOG_LINES);

        mSubscriptionController = subscriptionController;
        mRadioConfig = RadioConfig.getInstance(mContext);

        mPhoneStateListener = new PhoneStateListener(looper) {
            @Override
            public void onPhoneCapabilityChanged(PhoneCapability capability) {
                onPhoneCapabilityChangedInternal(capability);
            }
        };

        mValidator = CellularNetworkValidator.getInstance();

        mActivePhoneRegistrants = new RegistrantList();
        mPhoneStates = new PhoneState[numPhones];
        for (int i = 0; i < numPhones; i++) {
            mPhoneStates[i] = new PhoneState();
            if (mPhones[i] != null) {
                mPhones[i].registerForEmergencyCallToggle(this, EVENT_EMERGENCY_TOGGLE, null);
                // TODO (b/135566422): combine register for both GsmCdmaPhone and ImsPhone.
                mPhones[i].registerForPreciseCallStateChanged(
                        this, EVENT_PRECISE_CALL_STATE_CHANGED, null);
                if (mPhones[i].getImsPhone() != null) {
                    mPhones[i].getImsPhone().registerForPreciseCallStateChanged(
                            this, EVENT_PRECISE_CALL_STATE_CHANGED, null);
                }
                mPhones[i].getDataEnabledSettings().registerForDataEnabledChanged(
                        this, EVENT_DATA_ENABLED_CHANGED, null);
            }
        }

        mCommandsInterfaces = cis;

        if (numPhones > 0) {
            mCommandsInterfaces[0].registerForAvailable(this, EVENT_RADIO_AVAILABLE, null);
        }

        try {
            tr.addOnSubscriptionsChangedListener(context.getOpPackageName(),
                    mSubscriptionsChangedListener);
        } catch (RemoteException e) {
        }

        mConnectivityManager =
            (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);

        mContext.registerReceiver(mDefaultDataChangedReceiver,
                new IntentFilter(TelephonyIntents.ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED));

        NetworkCapabilities netCap = new NetworkCapabilities();
        netCap.addTransportType(TRANSPORT_CELLULAR);
        netCap.addCapability(NetworkCapabilities.NET_CAPABILITY_MMS);
        netCap.addCapability(NetworkCapabilities.NET_CAPABILITY_SUPL);
        netCap.addCapability(NetworkCapabilities.NET_CAPABILITY_DUN);
        netCap.addCapability(NetworkCapabilities.NET_CAPABILITY_FOTA);
        netCap.addCapability(NetworkCapabilities.NET_CAPABILITY_IMS);
        netCap.addCapability(NetworkCapabilities.NET_CAPABILITY_CBS);
        netCap.addCapability(NetworkCapabilities.NET_CAPABILITY_IA);
        netCap.addCapability(NetworkCapabilities.NET_CAPABILITY_RCS);
        netCap.addCapability(NetworkCapabilities.NET_CAPABILITY_XCAP);
        netCap.addCapability(NetworkCapabilities.NET_CAPABILITY_EIMS);
        netCap.addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED);
        netCap.addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        netCap.addCapability(NetworkCapabilities.NET_CAPABILITY_MCX);
        netCap.setNetworkSpecifier(new MatchAllNetworkSpecifier());

        NetworkFactory networkFactory = new PhoneSwitcherNetworkRequestListener(looper, context,
                netCap, this);
        // we want to see all requests
        networkFactory.setScoreFilter(101);
        networkFactory.register();

        log("PhoneSwitcher started");
    }

    private final BroadcastReceiver mDefaultDataChangedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Message msg = PhoneSwitcher.this.obtainMessage(EVENT_PRIMARY_DATA_SUB_CHANGED);
            msg.sendToTarget();
        }
    };

    private final IOnSubscriptionsChangedListener mSubscriptionsChangedListener =
            new IOnSubscriptionsChangedListener.Stub() {
        @Override
        public void onSubscriptionsChanged() {
            Message msg = PhoneSwitcher.this.obtainMessage(EVENT_SUBSCRIPTION_CHANGED);
            msg.sendToTarget();
        }
    };

    @Override
    public void handleMessage(Message msg) {
        switch (msg.what) {
            case EVENT_SUBSCRIPTION_CHANGED: {
                onEvaluate(REQUESTS_UNCHANGED, "subChanged");
                break;
            }
            case EVENT_PRIMARY_DATA_SUB_CHANGED: {
                if (onEvaluate(REQUESTS_UNCHANGED, "primary data subId changed")) {
                    logDataSwitchEvent(mOpptDataSubId,
                            TelephonyEvent.EventState.EVENT_STATE_START,
                            DataSwitch.Reason.DATA_SWITCH_REASON_MANUAL);
                    registerDefaultNetworkChangeCallback();
                }
                break;
            }
            case EVENT_REQUEST_NETWORK: {
                onRequestNetwork((NetworkRequest)msg.obj);
                break;
            }
            case EVENT_RELEASE_NETWORK: {
                onReleaseNetwork((NetworkRequest)msg.obj);
                break;
            }
            case EVENT_EMERGENCY_TOGGLE: {
                boolean isInEcm = isInEmergencyCallbackMode();
                if (mEmergencyOverride != null) {
                    log("Emergency override - ecbm status = " + isInEcm);
                    if (isInEcm) {
                        // The device has gone into ECBM. Wait until it's out.
                        removeMessages(EVENT_REMOVE_DDS_EMERGENCY_OVERRIDE);
                        mEmergencyOverride.mRequiresEcmFinish = true;
                    } else if (mEmergencyOverride.mRequiresEcmFinish) {
                        // we have exited ECM! Start the timer to exit DDS override.
                        Message msg2 = obtainMessage(EVENT_REMOVE_DDS_EMERGENCY_OVERRIDE);
                        sendMessageDelayed(msg2, mEmergencyOverride.mGnssOverrideTimeMs);
                    }
                }
                onEvaluate(REQUESTS_CHANGED, "emergencyToggle");
                break;
            }
            case EVENT_RADIO_CAPABILITY_CHANGED: {
                final int phoneId = msg.arg1;
                sendRilCommands(phoneId);
                break;
            }
            case EVENT_OPPT_DATA_SUB_CHANGED: {
                int subId = msg.arg1;
                boolean needValidation = (msg.arg2 == 1);
                ISetOpportunisticDataCallback callback =
                        (ISetOpportunisticDataCallback) msg.obj;
                setOpportunisticDataSubscription(subId, needValidation, callback);
                break;
            }
            case EVENT_RADIO_AVAILABLE: {
                updateHalCommandToUse();
                onEvaluate(REQUESTS_UNCHANGED, "EVENT_RADIO_AVAILABLE");
                break;
            }
            case EVENT_PRECISE_CALL_STATE_CHANGED: {
                // If the phoneId in voice call didn't change, do nothing.
                if (!isPhoneInVoiceCallChanged()) break;

                // Only handle this event if we are currently waiting for the emergency call
                // associated with the override request to start or end.
                if (mEmergencyOverride != null && mEmergencyOverride.mPendingOriginatingCall) {
                    removeMessages(EVENT_REMOVE_DDS_EMERGENCY_OVERRIDE);
                    if (mPhoneIdInVoiceCall == SubscriptionManager.INVALID_PHONE_INDEX) {
                        // not in a call anymore.
                        Message msg2 = obtainMessage(EVENT_REMOVE_DDS_EMERGENCY_OVERRIDE);
                        sendMessageDelayed(msg2, mEmergencyOverride.mGnssOverrideTimeMs
                                + ECBM_DEFAULT_DATA_SWITCH_BASE_TIME_MS);
                        // Do not extend the emergency override by waiting for other calls to end.
                        // If it needs to be extended, a new request will come in and replace the
                        // current override.
                        mEmergencyOverride.mPendingOriginatingCall = false;
                    }
                }
            }
            // fall through
            case EVENT_DATA_ENABLED_CHANGED:
                if (onEvaluate(REQUESTS_UNCHANGED, "EVENT_PRECISE_CALL_STATE_CHANGED")) {
                    logDataSwitchEvent(mOpptDataSubId,
                            TelephonyEvent.EventState.EVENT_STATE_START,
                            DataSwitch.Reason.DATA_SWITCH_REASON_IN_CALL);
                    registerDefaultNetworkChangeCallback();
                }
                break;
            case EVENT_NETWORK_VALIDATION_DONE: {
                int subId = msg.arg1;
                boolean passed = (msg.arg2 == 1);
                onValidationDone(subId, passed);
                break;
            }
            case EVENT_REMOVE_DEFAULT_NETWORK_CHANGE_CALLBACK: {
                removeDefaultNetworkChangeCallback();
                break;
            }
            case EVENT_MODEM_COMMAND_DONE: {
                AsyncResult ar = (AsyncResult) msg.obj;
                boolean commandSuccess = ar != null && ar.exception == null;
                if (mEmergencyOverride != null) {
                    log("Emergency override result sent = " + commandSuccess);
                    mEmergencyOverride.sendOverrideCompleteCallbackResultAndClear(commandSuccess);
                    // Do not retry , as we do not allow changes in onEvaluate during an emergency
                    // call. When the call ends, we will start the countdown to remove the override.
                } else if (!commandSuccess) {
                    int phoneId = (int) ar.userObj;
                    log("Modem command failed. with exception " + ar.exception);
                    sendMessageDelayed(Message.obtain(this, EVENT_MODEM_COMMAND_RETRY,
                            phoneId), MODEM_COMMAND_RETRY_PERIOD_MS);
                }
                break;
            }
            case EVENT_MODEM_COMMAND_RETRY: {
                int phoneId = (int) msg.obj;
                log("Resend modem command on phone " + phoneId);
                sendRilCommands(phoneId);
                break;
            }
            case EVENT_OVERRIDE_DDS_FOR_EMERGENCY: {
                EmergencyOverrideRequest req = (EmergencyOverrideRequest) msg.obj;
                if (mEmergencyOverride != null) {
                    // If an override request comes in for a different phone ID than what is already
                    // being overridden, ignore. We should not try to switch DDS while already
                    // waiting for SUPL.
                    if (mEmergencyOverride.mPhoneId != req.mPhoneId) {
                        log("emergency override requested for phone id " + req.mPhoneId + " when "
                                + "there is already an override in place for phone id "
                                + mEmergencyOverride.mPhoneId + ". Ignoring.");
                        if (req.isCallbackAvailable()) {
                            // Send failed result
                            req.mOverrideCompleteFuture.complete(false);
                        }
                        break;
                    } else {
                        if (mEmergencyOverride.isCallbackAvailable()) {
                            // Unblock any waiting overrides if a new request comes in before the
                            // previous one is processed.
                            mEmergencyOverride.mOverrideCompleteFuture.complete(false);
                        }
                    }
                    mEmergencyOverride = req;
                } else {
                    mEmergencyOverride = req;
                }

                log("new emergency override - " + mEmergencyOverride);
                // a new request has been created, remove any previous override complete scheduled.
                removeMessages(EVENT_REMOVE_DDS_EMERGENCY_OVERRIDE);
                Message msg2 = obtainMessage(EVENT_REMOVE_DDS_EMERGENCY_OVERRIDE);
                // Make sure that if we never get an incall indication that we remove the override.
                sendMessageDelayed(msg2, DEFAULT_DATA_OVERRIDE_TIMEOUT_MS);
                // Wait for call to end and EVENT_PRECISE_CALL_STATE_CHANGED to be called, then
                // start timer to remove DDS emergency override.
                if (!onEvaluate(REQUESTS_UNCHANGED, "emer_override_dds")) {
                    // Nothing changed as a result of override, so no modem command was sent. Treat
                    // as success.
                    mEmergencyOverride.sendOverrideCompleteCallbackResultAndClear(true);
                    // Do not clear mEmergencyOverride here, as we still want to keep the override
                    // active for the time specified in case the user tries to switch default data.
                }
                break;
            }
            case EVENT_REMOVE_DDS_EMERGENCY_OVERRIDE: {
                log("Emergency override removed - " + mEmergencyOverride);
                mEmergencyOverride = null;
                onEvaluate(REQUESTS_UNCHANGED, "emer_rm_override_dds");
                break;
            }
        }
    }

    private boolean isEmergency() {
        if (isInEmergencyCallbackMode()) return true;
        for (Phone p : mPhones) {
            if (p == null) continue;
            if (p.isInEmergencyCall()) return true;
            Phone imsPhone = p.getImsPhone();
            if (imsPhone != null && imsPhone.isInEmergencyCall()) {
                return true;
            }
        }
        return false;
    }

    private boolean isInEmergencyCallbackMode() {
        for (Phone p : mPhones) {
            if (p == null) continue;
            if (p.isInEcm()) return true;
            Phone imsPhone = p.getImsPhone();
            if (imsPhone != null && imsPhone.isInEcm()) {
                return true;
            }
        }
        return false;
    }

    private static class PhoneSwitcherNetworkRequestListener extends NetworkFactory {
        private final PhoneSwitcher mPhoneSwitcher;
        public PhoneSwitcherNetworkRequestListener (Looper l, Context c,
                NetworkCapabilities nc, PhoneSwitcher ps) {
            super(l, c, "PhoneSwitcherNetworkRequstListener", nc);
            mPhoneSwitcher = ps;
        }

        @Override
        protected void needNetworkFor(NetworkRequest networkRequest, int score) {
            if (VDBG) log("needNetworkFor " + networkRequest + ", " + score);
            Message msg = mPhoneSwitcher.obtainMessage(EVENT_REQUEST_NETWORK);
            msg.obj = networkRequest;
            msg.sendToTarget();
        }

        @Override
        protected void releaseNetworkFor(NetworkRequest networkRequest) {
            if (VDBG) log("releaseNetworkFor " + networkRequest);
            Message msg = mPhoneSwitcher.obtainMessage(EVENT_RELEASE_NETWORK);
            msg.obj = networkRequest;
            msg.sendToTarget();
        }
    }

    private void onRequestNetwork(NetworkRequest networkRequest) {
        final DcRequest dcRequest = new DcRequest(networkRequest, mContext);
        if (!mPrioritizedDcRequests.contains(dcRequest)) {
            collectRequestNetworkMetrics(networkRequest);
            mPrioritizedDcRequests.add(dcRequest);
            Collections.sort(mPrioritizedDcRequests);
            onEvaluate(REQUESTS_CHANGED, "netRequest");
        }
    }

    private void onReleaseNetwork(NetworkRequest networkRequest) {
        final DcRequest dcRequest = new DcRequest(networkRequest, mContext);

        if (mPrioritizedDcRequests.remove(dcRequest)) {
            onEvaluate(REQUESTS_CHANGED, "netReleased");
            collectReleaseNetworkMetrics(networkRequest);
        }
    }

    private void removeDefaultNetworkChangeCallback() {
        synchronized (mHasRegisteredDefaultNetworkChangeCallback) {
            if (mHasRegisteredDefaultNetworkChangeCallback) {
                mHasRegisteredDefaultNetworkChangeCallback = false;
                removeMessages(EVENT_REMOVE_DEFAULT_NETWORK_CHANGE_CALLBACK);
                mConnectivityManager.unregisterNetworkCallback(mDefaultNetworkCallback);
            }
        }
    }

    private void registerDefaultNetworkChangeCallback() {
        removeDefaultNetworkChangeCallback();

        synchronized (mHasRegisteredDefaultNetworkChangeCallback) {
            mHasRegisteredDefaultNetworkChangeCallback = true;
            mConnectivityManager.registerDefaultNetworkCallback(mDefaultNetworkCallback);
            sendMessageDelayed(
                    obtainMessage(EVENT_REMOVE_DEFAULT_NETWORK_CHANGE_CALLBACK),
                    DEFAULT_NETWORK_CHANGE_TIMEOUT_MS);
        }
    }

    private void collectRequestNetworkMetrics(NetworkRequest networkRequest) {
        // Request network for MMS will temporary disable the network on default data subscription,
        // this only happen on multi-sim device.
        if (mNumPhones > 1 && networkRequest.networkCapabilities.hasCapability(
                NetworkCapabilities.NET_CAPABILITY_MMS)) {
            OnDemandDataSwitch onDemandDataSwitch = new OnDemandDataSwitch();
            onDemandDataSwitch.apn = TelephonyEvent.ApnType.APN_TYPE_MMS;
            onDemandDataSwitch.state = TelephonyEvent.EventState.EVENT_STATE_START;
            TelephonyMetrics.getInstance().writeOnDemandDataSwitch(onDemandDataSwitch);
        }
    }

    private void collectReleaseNetworkMetrics(NetworkRequest networkRequest) {
        // Release network for MMS will recover the network on default data subscription, this only
        // happen on multi-sim device.
        if (mNumPhones > 1 && networkRequest.networkCapabilities.hasCapability(
                NetworkCapabilities.NET_CAPABILITY_MMS)) {
            OnDemandDataSwitch onDemandDataSwitch = new OnDemandDataSwitch();
            onDemandDataSwitch.apn = TelephonyEvent.ApnType.APN_TYPE_MMS;
            onDemandDataSwitch.state = TelephonyEvent.EventState.EVENT_STATE_END;
            TelephonyMetrics.getInstance().writeOnDemandDataSwitch(onDemandDataSwitch);
        }
    }

    private static final boolean REQUESTS_CHANGED   = true;
    private static final boolean REQUESTS_UNCHANGED = false;
    /**
     * Re-evaluate things. Do nothing if nothing's changed.
     *
     * Otherwise, go through the requests in priority order adding their phone until we've added up
     * to the max allowed.  Then go through shutting down phones that aren't in the active phone
     * list. Finally, activate all phones in the active phone list.
     *
     * @return {@code True} if the default data subscription need to be changed.
     */
    private boolean onEvaluate(boolean requestsChanged, String reason) {
        StringBuilder sb = new StringBuilder(reason);
        if (isEmergency()) {
            log("onEvaluate for reason " + reason + " aborted due to Emergency");
            return false;
        }

        // If we use HAL_COMMAND_PREFERRED_DATA,
        boolean diffDetected = mHalCommandToUse != HAL_COMMAND_PREFERRED_DATA && requestsChanged;

        // Check if user setting of default non-opportunistic data sub is changed.
        final int primaryDataSubId = mSubscriptionController.getDefaultDataSubId();
        if (primaryDataSubId != mPrimaryDataSubId) {
            sb.append(" mPrimaryDataSubId ").append(mPrimaryDataSubId).append("->")
                .append(primaryDataSubId);
            mPrimaryDataSubId = primaryDataSubId;
        }

        // Check to see if there is any active subscription on any phone
        boolean hasAnyActiveSubscription = false;

        // Check if phoneId to subId mapping is changed.
        for (int i = 0; i < mNumPhones; i++) {
            int sub = mSubscriptionController.getSubIdUsingPhoneId(i);

            if (SubscriptionManager.isValidSubscriptionId(sub)) hasAnyActiveSubscription = true;

            if (sub != mPhoneSubscriptions[i]) {
                sb.append(" phone[").append(i).append("] ").append(mPhoneSubscriptions[i]);
                sb.append("->").append(sub);
                mPhoneSubscriptions[i] = sub;
                diffDetected = true;
            }
        }

        if (!hasAnyActiveSubscription) {
            transitionToEmergencyPhone();
        } else {
            if (VDBG) log("Found an active subscription");
        }

        // Check if phoneId for preferred data is changed.
        int oldPreferredDataPhoneId = mPreferredDataPhoneId;

        // When there are no subscriptions, the preferred data phone ID is invalid, but we want
        // to keep a valid phoneId for Emergency, so skip logic that updates for preferred data
        // phone ID. Ideally there should be a single set of checks that evaluate the correct
        // phoneId on a service-by-service basis (EIMS being one), but for now... just bypass
        // this logic in the no-SIM case.
        if (hasAnyActiveSubscription) updatePreferredDataPhoneId();

        if (oldPreferredDataPhoneId != mPreferredDataPhoneId) {
            sb.append(" preferred phoneId ").append(oldPreferredDataPhoneId)
                    .append("->").append(mPreferredDataPhoneId);
            diffDetected = true;
        }

        if (diffDetected) {
            log("evaluating due to " + sb.toString());
            if (mHalCommandToUse == HAL_COMMAND_PREFERRED_DATA) {
                // With HAL_COMMAND_PREFERRED_DATA, all phones are assumed to allow PS attach.
                // So marking all phone as active, and the phone with mPreferredDataPhoneId
                // will send radioConfig command.
                for (int phoneId = 0; phoneId < mNumPhones; phoneId++) {
                    mPhoneStates[phoneId].active = true;
                }
                sendRilCommands(mPreferredDataPhoneId);
            } else {
                List<Integer> newActivePhones = new ArrayList<Integer>();

                /**
                 * If all phones can have PS attached, activate all.
                 * Otherwise, choose to activate phones according to requests. And
                 * if list is not full, add mPreferredDataPhoneId.
                 */
                if (mMaxActivePhones == mPhones.length) {
                    for (int i = 0; i < mMaxActivePhones; i++) {
                        newActivePhones.add(mPhones[i].getPhoneId());
                    }
                } else {
                    for (DcRequest dcRequest : mPrioritizedDcRequests) {
                        int phoneIdForRequest = phoneIdForRequest(dcRequest.networkRequest);
                        if (phoneIdForRequest == INVALID_PHONE_INDEX) continue;
                        if (newActivePhones.contains(phoneIdForRequest)) continue;
                        newActivePhones.add(phoneIdForRequest);
                        if (newActivePhones.size() >= mMaxActivePhones) break;
                    }

                    if (newActivePhones.size() < mMaxActivePhones
                            && newActivePhones.contains(mPreferredDataPhoneId)
                            && SubscriptionManager.isUsableSubIdValue(mPreferredDataPhoneId)) {
                        newActivePhones.add(mPreferredDataPhoneId);
                    }
                }

                if (VDBG) {
                    log("mPrimaryDataSubId = " + mPrimaryDataSubId);
                    log("mOpptDataSubId = " + mOpptDataSubId);
                    for (int i = 0; i < mNumPhones; i++) {
                        log(" phone[" + i + "] using sub[" + mPhoneSubscriptions[i] + "]");
                    }
                    log(" newActivePhones:");
                    for (Integer i : newActivePhones) log("  " + i);
                }

                for (int phoneId = 0; phoneId < mNumPhones; phoneId++) {
                    if (!newActivePhones.contains(phoneId)) {
                        deactivate(phoneId);
                    }
                }

                // only activate phones up to the limit
                for (int phoneId : newActivePhones) {
                    activate(phoneId);
                }
            }

            notifyPreferredDataSubIdChanged();

            // Notify all registrants.
            mActivePhoneRegistrants.notifyRegistrants();
        }
        return diffDetected;
    }

    private static class PhoneState {
        public volatile boolean active = false;
        public long lastRequested = 0;
    }

    @UnsupportedAppUsage
    private void activate(int phoneId) {
        switchPhone(phoneId, true);
    }

    @UnsupportedAppUsage
    private void deactivate(int phoneId) {
        switchPhone(phoneId, false);
    }

    private void switchPhone(int phoneId, boolean active) {
        PhoneState state = mPhoneStates[phoneId];
        if (state.active == active) return;
        state.active = active;
        log((active ? "activate " : "deactivate ") + phoneId);
        state.lastRequested = System.currentTimeMillis();
        sendRilCommands(phoneId);
    }

    /**
     * Used when the modem may have been rebooted and we
     * want to resend setDataAllowed or setPreferredDataSubscriptionId
     */
    public void onRadioCapChanged(int phoneId) {
        validatePhoneId(phoneId);
        Message msg = obtainMessage(EVENT_RADIO_CAPABILITY_CHANGED);
        msg.arg1 = phoneId;
        msg.sendToTarget();
    }

    /**
     * Switch the Default data for the context of an outgoing emergency call.
     *
     * In some cases, we need to try to switch the Default Data subscription before placing the
     * emergency call on DSDS devices. This includes the following situation:
     * - The modem does not support processing GNSS SUPL requests on the non-default data
     * subscription. For some carriers that do not provide a control plane fallback mechanism, the
     * SUPL request will be dropped and we will not be able to get the user's location for the
     * emergency call. In this case, we need to swap default data temporarily.
     * @param phoneId The phone to use to evaluate whether or not the default data should be moved
     *                to this subscription.
     * @param overrideTimeSec The amount of time to override the default data setting for after the
     *                       emergency call ends.
     * @param dataSwitchResult A {@link CompletableFuture} to be called with a {@link Boolean}
     *                         result when the default data switch has either completed (true) or
     *                         failed (false).
     */
    public void overrideDefaultDataForEmergency(int phoneId, int overrideTimeSec,
            CompletableFuture<Boolean> dataSwitchResult) {
        validatePhoneId(phoneId);
        Message msg = obtainMessage(EVENT_OVERRIDE_DDS_FOR_EMERGENCY);
        EmergencyOverrideRequest request  = new EmergencyOverrideRequest();
        request.mPhoneId = phoneId;
        request.mGnssOverrideTimeMs = overrideTimeSec * 1000;
        request.mOverrideCompleteFuture = dataSwitchResult;
        msg.obj = request;
        msg.sendToTarget();
    }

    private void sendRilCommands(int phoneId) {
        if (!SubscriptionManager.isValidPhoneId(phoneId) || phoneId >= mNumPhones) return;

        Message message = Message.obtain(this, EVENT_MODEM_COMMAND_DONE, phoneId);
        if (mHalCommandToUse == HAL_COMMAND_ALLOW_DATA || mHalCommandToUse == HAL_COMMAND_UNKNOWN) {
            // Skip ALLOW_DATA for single SIM device
            if (mNumPhones > 1) {
                mCommandsInterfaces[phoneId].setDataAllowed(isPhoneActive(phoneId), message);
            }
        } else if (phoneId == mPreferredDataPhoneId) {
            // Only setPreferredDataModem if the phoneId equals to current mPreferredDataPhoneId.
            mRadioConfig.setPreferredDataModem(mPreferredDataPhoneId, message);
        }
    }

    private void onPhoneCapabilityChangedInternal(PhoneCapability capability) {
        int newMaxActivePhones = TelephonyManager.getDefault()
                .getNumberOfModemsWithSimultaneousDataConnections();
        if (mMaxActivePhones != newMaxActivePhones) {
            mMaxActivePhones = newMaxActivePhones;
            log("Max active phones changed to " + mMaxActivePhones);
            onEvaluate(REQUESTS_UNCHANGED, "phoneCfgChanged");
        }
    }

    private int phoneIdForRequest(NetworkRequest netRequest) {
        int subId = getSubIdFromNetworkRequest(netRequest);

        if (subId == DEFAULT_SUBSCRIPTION_ID) return mPreferredDataPhoneId;
        if (subId == INVALID_SUBSCRIPTION_ID) return INVALID_PHONE_INDEX;

        int preferredDataSubId = SubscriptionManager.isValidPhoneId(mPreferredDataPhoneId)
                ? mPhoneSubscriptions[mPreferredDataPhoneId] : INVALID_SUBSCRIPTION_ID;

        // Currently we assume multi-SIM devices will only support one Internet PDN connection. So
        // if Internet PDN is established on the non-preferred phone, it will interrupt
        // Internet connection on the preferred phone. So we only accept Internet request with
        // preferred data subscription or no specified subscription.
        // One exception is, if it's restricted request (doesn't have NET_CAPABILITY_NOT_RESTRICTED)
        // it will be accepted, which is used temporary data usage from system.
        if (netRequest.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                && netRequest.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
                && subId != preferredDataSubId && subId != mValidator.getSubIdInValidation()) {
            // Returning INVALID_PHONE_INDEX will result in netRequest not being handled.
            return INVALID_PHONE_INDEX;
        }

        // Try to find matching phone ID. If it doesn't exist, we'll end up returning INVALID.
        int phoneId = INVALID_PHONE_INDEX;
        for (int i = 0; i < mNumPhones; i++) {
            if (mPhoneSubscriptions[i] == subId) {
                phoneId = i;
                break;
            }
        }
        return phoneId;
    }

    private int getSubIdFromNetworkRequest(NetworkRequest networkRequest) {
        NetworkSpecifier specifier = networkRequest.networkCapabilities.getNetworkSpecifier();
        if (specifier == null) {
            return DEFAULT_SUBSCRIPTION_ID;
        }

        int subId;

        if (specifier instanceof StringNetworkSpecifier) {
            try {
                subId = Integer.parseInt(((StringNetworkSpecifier) specifier).specifier);
            } catch (NumberFormatException e) {
                Rlog.e(LOG_TAG, "NumberFormatException on "
                        + ((StringNetworkSpecifier) specifier).specifier);
                return INVALID_SUBSCRIPTION_ID;
            }
        } else {
            return INVALID_SUBSCRIPTION_ID;
        }

        return subId;
    }

    private int getSubIdForDefaultNetworkRequests() {
        if (mSubscriptionController.isActiveSubId(mOpptDataSubId)) {
            return mOpptDataSubId;
        } else {
            return mPrimaryDataSubId;
        }
    }

    // This updates mPreferredDataPhoneId which decides which phone should handle default network
    // requests.
    private void updatePreferredDataPhoneId() {
        Phone voicePhone = findPhoneById(mPhoneIdInVoiceCall);
        if (mEmergencyOverride != null && findPhoneById(mEmergencyOverride.mPhoneId) != null) {
            // Override DDS for emergency even if user data is not enabled, since it is an
            // emergency.
            // TODO: Provide a notification to the user that metered data is currently being
            // used during this period.
            log("updatePreferredDataPhoneId: preferred data overridden for emergency."
                    + " phoneId = " + mEmergencyOverride.mPhoneId);
            mPreferredDataPhoneId = mEmergencyOverride.mPhoneId;
        } else if (voicePhone != null && voicePhone.getDataEnabledSettings().isDataEnabled(
                ApnSetting.TYPE_DEFAULT)) {
            // If a phone is in call and user enabled its mobile data, we
            // should switch internet connection to it. Because the other modem
            // will lose data connection anyway.
            // TODO: validate network first.
            mPreferredDataPhoneId = mPhoneIdInVoiceCall;
        } else {
            int subId = getSubIdForDefaultNetworkRequests();
            int phoneId = SubscriptionManager.INVALID_PHONE_INDEX;

            if (SubscriptionManager.isUsableSubIdValue(subId)) {
                for (int i = 0; i < mNumPhones; i++) {
                    if (mPhoneSubscriptions[i] == subId) {
                        phoneId = i;
                        break;
                    }
                }
            }

            mPreferredDataPhoneId = phoneId;
        }

        mPreferredDataSubId = mSubscriptionController.getSubIdUsingPhoneId(mPreferredDataPhoneId);
    }

    private void transitionToEmergencyPhone() {
        if (mPreferredDataPhoneId != DEFAULT_EMERGENCY_PHONE_ID) {
            log("No active subscriptions: resetting preferred phone to 0 for emergency");
            mPreferredDataPhoneId = DEFAULT_EMERGENCY_PHONE_ID;
        }

        if (mPreferredDataSubId != INVALID_SUBSCRIPTION_ID) {
            mPreferredDataSubId = INVALID_SUBSCRIPTION_ID;
            notifyPreferredDataSubIdChanged();
        }
    }

    private Phone findPhoneById(final int phoneId) {
        if (phoneId < 0 || phoneId >= mNumPhones) {
            return null;
        }
        return mPhones[phoneId];
    }

    public boolean shouldApplyNetworkRequest(NetworkRequest networkRequest, int phoneId) {
        validatePhoneId(phoneId);

        // In any case, if phone state is inactive, don't apply the network request.
        if (!isPhoneActive(phoneId) || (
                mSubscriptionController.getSubIdUsingPhoneId(phoneId) == INVALID_SUBSCRIPTION_ID
                && !isEmergencyNetworkRequest(networkRequest))) {
            return false;
        }

        int phoneIdToHandle = phoneIdForRequest(networkRequest);

        return phoneId == phoneIdToHandle;
    }

    boolean isEmergencyNetworkRequest(NetworkRequest networkRequest) {
        return networkRequest.hasCapability(NetworkCapabilities.NET_CAPABILITY_EIMS);
    }

    @VisibleForTesting
    protected boolean isPhoneActive(int phoneId) {
        return mPhoneStates[phoneId].active;
    }

    /**
     * If preferred phone changes, or phone activation status changes, registrants
     * will be notified.
     */
    public void registerForActivePhoneSwitch(Handler h, int what, Object o) {
        Registrant r = new Registrant(h, what, o);
        mActivePhoneRegistrants.add(r);
        r.notifyRegistrant();
    }

    public void unregisterForActivePhoneSwitch(Handler h) {
        mActivePhoneRegistrants.remove(h);
    }

    @VisibleForTesting
    protected void validatePhoneId(int phoneId) {
        if (phoneId < 0 || phoneId >= mNumPhones) {
            throw new IllegalArgumentException("Invalid PhoneId");
        }
    }

    /**
     * Set opportunistic data subscription. It's an indication to switch Internet data to this
     * subscription. It has to be an active subscription, and PhoneSwitcher will try to validate
     * it first if needed. If subId is DEFAULT_SUBSCRIPTION_ID, it means we are un-setting
     * opportunistic data sub and switch data back to primary sub.
     *
     * @param subId the opportunistic data subscription to switch to. pass DEFAULT_SUBSCRIPTION_ID
     *              if un-setting it.
     * @param needValidation whether Telephony will wait until the network is validated by
     *              connectivity service before switching data to it. More details see
     *              {@link NetworkCapabilities#NET_CAPABILITY_VALIDATED}.
     * @param callback Callback will be triggered once it succeeds or failed.
     *                 Pass null if don't care about the result.
     */
    private void setOpportunisticDataSubscription(int subId, boolean needValidation,
            ISetOpportunisticDataCallback callback) {
        if (!mSubscriptionController.isActiveSubId(subId)
                && subId != SubscriptionManager.DEFAULT_SUBSCRIPTION_ID) {
            log("Can't switch data to inactive subId " + subId);
            sendSetOpptCallbackHelper(callback, SET_OPPORTUNISTIC_SUB_INACTIVE_SUBSCRIPTION);
            return;
        }

        int subIdToValidate = (subId == SubscriptionManager.DEFAULT_SUBSCRIPTION_ID)
                ? mPrimaryDataSubId : subId;

        if (mValidator.isValidating()
                && (!needValidation || subIdToValidate != mValidator.getSubIdInValidation())) {
            mValidator.stopValidation();
        }

        if (subId == mOpptDataSubId) {
            sendSetOpptCallbackHelper(callback, SET_OPPORTUNISTIC_SUB_SUCCESS);
            return;
        }

        // If validation feature is not supported, set it directly. Otherwise,
        // start validation on the subscription first.
        if (mValidator.isValidationFeatureSupported() && needValidation) {
            logDataSwitchEvent(subId, TelephonyEvent.EventState.EVENT_STATE_START,
                    DataSwitch.Reason.DATA_SWITCH_REASON_CBRS);
            registerDefaultNetworkChangeCallback();
            mSetOpptSubCallback = callback;
            mValidator.validate(subIdToValidate, DEFAULT_VALIDATION_EXPIRATION_TIME,
                    false, mValidationCallback);
        } else {
            setOpportunisticSubscriptionInternal(subId);
            sendSetOpptCallbackHelper(callback, SET_OPPORTUNISTIC_SUB_SUCCESS);
        }
    }

    private void sendSetOpptCallbackHelper(ISetOpportunisticDataCallback callback, int result) {
        if (callback == null) return;
        try {
            callback.onComplete(result);
        } catch (RemoteException exception) {
            log("RemoteException " + exception);
        }
    }

    /**
     * Set opportunistic data subscription.
     */
    private void setOpportunisticSubscriptionInternal(int subId) {
        if (mOpptDataSubId != subId) {
            mOpptDataSubId = subId;
            if (onEvaluate(REQUESTS_UNCHANGED, "oppt data subId changed")) {
                logDataSwitchEvent(mOpptDataSubId,
                        TelephonyEvent.EventState.EVENT_STATE_START,
                        DataSwitch.Reason.DATA_SWITCH_REASON_CBRS);
                registerDefaultNetworkChangeCallback();
            }
        }
    }

    private void onValidationDone(int subId, boolean passed) {
        log("onValidationDone: " + (passed ? "passed" : "failed")
                + " on subId " + subId);
        int resultForCallBack;

        if (!mSubscriptionController.isActiveSubId(subId)) {
            log("onValidationDone: subId " + subId + " is no longer active");
            resultForCallBack = SET_OPPORTUNISTIC_SUB_INACTIVE_SUBSCRIPTION;
        } else if (!passed) {
            resultForCallBack = SET_OPPORTUNISTIC_SUB_VALIDATION_FAILED;
        } else {
            if (mSubscriptionController.isOpportunistic(subId)) {
                setOpportunisticSubscriptionInternal(subId);
            } else {
                // Switching data back to primary subscription.
                setOpportunisticSubscriptionInternal(SubscriptionManager.DEFAULT_SUBSCRIPTION_ID);
            }
            resultForCallBack = SET_OPPORTUNISTIC_SUB_SUCCESS;
        }

        // Trigger callback if needed
        sendSetOpptCallbackHelper(mSetOpptSubCallback, resultForCallBack);
        mSetOpptSubCallback = null;
    }

    /**
     * Notify PhoneSwitcher to try to switch data to an opportunistic subscription.
     *
     * Set opportunistic data subscription. It's an indication to switch Internet data to this
     * subscription. It has to be an active subscription, and PhoneSwitcher will try to validate
     * it first if needed. If subId is DEFAULT_SUBSCRIPTION_ID, it means we are un-setting
     * opportunistic data sub and switch data back to primary sub.
     *
     * @param subId the opportunistic data subscription to switch to. pass DEFAULT_SUBSCRIPTION_ID
     *              if un-setting it.
     * @param needValidation whether Telephony will wait until the network is validated by
     *              connectivity service before switching data to it. More details see
     *              {@link NetworkCapabilities#NET_CAPABILITY_VALIDATED}.
     * @param callback Callback will be triggered once it succeeds or failed.
     *                 Pass null if don't care about the result.
     */
    public void trySetOpportunisticDataSubscription(int subId, boolean needValidation,
            ISetOpportunisticDataCallback callback) {
        log("Try set opportunistic data subscription to subId " + subId
                + (needValidation ? " with " : " without ") + "validation");
        PhoneSwitcher.this.obtainMessage(EVENT_OPPT_DATA_SUB_CHANGED,
                subId, needValidation ? 1 : 0, callback).sendToTarget();
    }

    private boolean isCallActive(Phone phone) {
        if (phone == null) {
            return false;
        }

        return (phone.getForegroundCall().getState() == Call.State.ACTIVE
                || phone.getForegroundCall().getState() == Call.State.ALERTING);
    }

    private void updateHalCommandToUse() {
        mHalCommandToUse = mRadioConfig.isSetPreferredDataCommandSupported()
                ? HAL_COMMAND_PREFERRED_DATA : HAL_COMMAND_ALLOW_DATA;
    }

    public int getOpportunisticDataSubscriptionId() {
        return mOpptDataSubId;
    }

    public int getPreferredDataPhoneId() {
        return mPreferredDataPhoneId;
    }

    @UnsupportedAppUsage
    private void log(String l) {
        Rlog.d(LOG_TAG, l);
        mLocalLog.log(l);
    }

    private void logDataSwitchEvent(int subId, int state, int reason) {
        subId = subId == DEFAULT_SUBSCRIPTION_ID ? mPrimaryDataSubId : subId;
        DataSwitch dataSwitch = new DataSwitch();
        dataSwitch.state = state;
        dataSwitch.reason = reason;
        TelephonyMetrics.getInstance().writeDataSwitch(subId, dataSwitch);
    }

    /**
     * See {@link PhoneStateListener#LISTEN_ACTIVE_DATA_SUBSCRIPTION_ID_CHANGE}.
     */
    private void notifyPreferredDataSubIdChanged() {
        ITelephonyRegistry tr = ITelephonyRegistry.Stub.asInterface(ServiceManager.getService(
                "telephony.registry"));
        try {
            log("notifyPreferredDataSubIdChanged to " + mPreferredDataSubId);
            tr.notifyActiveDataSubIdChanged(mPreferredDataSubId);
        } catch (RemoteException ex) {
            // Should never happen because its always available.
        }
    }

    public void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
        final IndentingPrintWriter pw = new IndentingPrintWriter(writer, "  ");
        pw.println("PhoneSwitcher:");
        Calendar c = Calendar.getInstance();
        for (int i = 0; i < mNumPhones; i++) {
            PhoneState ps = mPhoneStates[i];
            c.setTimeInMillis(ps.lastRequested);
            pw.println("PhoneId(" + i + ") active=" + ps.active + ", lastRequest=" +
                    (ps.lastRequested == 0 ? "never" :
                     String.format("%tm-%td %tH:%tM:%tS.%tL", c, c, c, c, c, c)));
        }
        pw.increaseIndent();
        mLocalLog.dump(fd, pw, args);
        pw.decreaseIndent();
    }
}
