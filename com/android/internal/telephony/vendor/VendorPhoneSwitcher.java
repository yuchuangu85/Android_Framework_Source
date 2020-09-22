/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.internal.telephony.vendor;

import static android.telephony.SubscriptionManager.DEFAULT_SUBSCRIPTION_ID;
import static android.telephony.SubscriptionManager.INVALID_PHONE_INDEX;
import static android.telephony.SubscriptionManager.INVALID_SUBSCRIPTION_ID;
import static android.telephony.TelephonyManager.RADIO_POWER_UNAVAILABLE;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.AsyncResult;
import android.os.Looper;
import android.os.Message;
import android.os.Registrant;
import android.os.SystemProperties;
import android.telephony.data.ApnSetting;
import android.telephony.Rlog;
import android.telephony.SubscriptionManager;

import android.text.TextUtils;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.telephony.CallManager;
import com.android.internal.telephony.Call;
import com.android.internal.telephony.CommandsInterface;
import com.android.internal.telephony.dataconnection.DcRequest;
import com.android.internal.telephony.dataconnection.DataEnabledSettings;
import com.android.internal.telephony.GsmCdmaCall;
import com.android.internal.telephony.imsphone.ImsPhone;
import com.android.internal.telephony.imsphone.ImsPhoneCall;
import com.android.internal.telephony.ITelephonyRegistry;
import com.android.internal.telephony.IccCardConstants;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.PhoneFactory;
import com.android.internal.telephony.PhoneSwitcher;
import com.android.internal.telephony.SubscriptionController;
import com.android.internal.telephony.TelephonyIntents;

import java.lang.Integer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import android.net.StringNetworkSpecifier;
import android.net.NetworkSpecifier;

public class VendorPhoneSwitcher extends PhoneSwitcher {

    private final int MAX_CONNECT_FAILURE_COUNT = 5;
    private final int[] mRetryArray =  new int []{5,10,20,40,60};
    private int[] mAllowDataFailure;
    private boolean[] mDdsRequestSent;
    private boolean mManualDdsSwitch = false;
    private int mDefaultDataPhoneId = -1;
    private String [] mSimStates;
    private List<Integer> mNewActivePhones;
    private boolean mWaitForDetachResponse = false;
    private DdsSwitchState mDdsSwitchState = DdsSwitchState.NONE;
    private final int USER_INITIATED_SWITCH = 0;
    private final int NONUSER_INITIATED_SWITCH = 1;
    private final String PROPERTY_TEMP_DDSSWITCH = "persist.vendor.radio.enable_temp_dds";
    private final GsmCdmaCall[] mFgCsCalls;
    private final GsmCdmaCall[] mBgCsCalls;
    private final GsmCdmaCall[] mRiCsCalls;
    private final ImsPhone[] mImsPhones;
    private final ImsPhoneCall[] mFgImsCalls;
    private final ImsPhoneCall[] mBgImsCalls;
    private final ImsPhoneCall[] mRiImsCalls;

    private final int EVENT_ALLOW_DATA_FALSE_RESPONSE  = 201;
    private final int EVENT_ALLOW_DATA_TRUE_RESPONSE   = 202;
    private final int EVENT_DDS_SWITCH_RESPONSE        = 203;
    private final int EVENT_PREFERRED_SUB_VALID        = 204;

    private enum DdsSwitchState {
        NONE, REQUIRED, DONE
    }

    public VendorPhoneSwitcher(int maxActivePhones, Context context, Looper looper) {
        super (maxActivePhones, context, looper);
        mAllowDataFailure = new int[mActiveModemCount];
        mDdsRequestSent = new boolean[mActiveModemCount];
        mSimStates = new String[mActiveModemCount];
        IntentFilter filter = new IntentFilter();
        filter.addAction(TelephonyIntents.ACTION_SIM_STATE_CHANGED);
        mContext.registerReceiver(mSimStateIntentReceiver, filter);

        mImsPhones = new ImsPhone[mActiveModemCount];
        mFgCsCalls = new GsmCdmaCall[mActiveModemCount];
        mBgCsCalls = new GsmCdmaCall[mActiveModemCount];
        mRiCsCalls = new GsmCdmaCall[mActiveModemCount];
        mFgImsCalls = new ImsPhoneCall[mActiveModemCount];
        mBgImsCalls = new ImsPhoneCall[mActiveModemCount];
        mRiImsCalls = new ImsPhoneCall[mActiveModemCount];

        for (int i=0; i < mActiveModemCount; i++) {
            if (PhoneFactory.getPhone(i) != null) {
                mFgCsCalls[i] = (GsmCdmaCall) PhoneFactory.getPhone(i).getForegroundCall();
                mBgCsCalls[i] = (GsmCdmaCall) PhoneFactory.getPhone(i).getBackgroundCall();
                mRiCsCalls[i] = (GsmCdmaCall) PhoneFactory.getPhone(i).getRingingCall();
            }
            mImsPhones[i] = (ImsPhone)PhoneFactory.getPhone(i).getImsPhone();
            if (mImsPhones[i] != null) {
                mFgImsCalls[i] = mImsPhones[i].getForegroundCall();
                mBgImsCalls[i] = mImsPhones[i].getBackgroundCall();
                mRiImsCalls[i] = mImsPhones[i].getRingingCall();
            }

            mDdsRequestSent[i] = false;
        }
    }

    public static VendorPhoneSwitcher make(int maxActivePhones, Context context, Looper looper) {
        if (sPhoneSwitcher == null) {
            sPhoneSwitcher = new VendorPhoneSwitcher(maxActivePhones, context, looper);
        }

        return (VendorPhoneSwitcher)sPhoneSwitcher;
    }

    private BroadcastReceiver mSimStateIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action.equals(TelephonyIntents.ACTION_SIM_STATE_CHANGED)) {
                String value = intent.getStringExtra(IccCardConstants.INTENT_KEY_ICC_STATE);
                int phoneId = intent.getIntExtra(PhoneConstants.PHONE_KEY,
                        SubscriptionManager.INVALID_PHONE_INDEX);
                log("mSimStateIntentReceiver: phoneId = " + phoneId + " value = " + value);
                if (SubscriptionManager.isValidPhoneId(phoneId)) {
                    mSimStates[phoneId] = value;
                    // If SIM is absent, allow DDS request always, which avoids DDS switch
                    // can't be completed in the no-SIM case because the sent status of the
                    // old preferred phone has no chance to reset in hot-swap
                    if (IccCardConstants.INTENT_VALUE_ICC_ABSENT.equals(value)) {
                        mDdsRequestSent[phoneId] = false;
                    }
                }

                if (isSimReady(phoneId) && (getConnectFailureCount(phoneId) > 0)) {
                    sendRilCommands(phoneId);
                }
            }
        }
    };

    @Override
    public void handleMessage(Message msg) {
        final int ddsSubId = mSubscriptionController.getDefaultDataSubId();
        final int ddsPhoneId = mSubscriptionController.getPhoneId(ddsSubId);

        log("handle event - " + msg.what);
        AsyncResult ar = null;
        switch (msg.what) {
            case EVENT_SUBSCRIPTION_CHANGED: {
                if (mHalCommandToUse == HAL_COMMAND_UNKNOWN) {
                    log("EVENT_SUBSCRIPTION_CHANGED: update HAL command");
                    mHalCommandToUse = mRadioConfig.isSetPreferredDataCommandSupported()
                            ? HAL_COMMAND_PREFERRED_DATA : HAL_COMMAND_ALLOW_DATA;
                }
                onEvaluate(REQUESTS_UNCHANGED, "subChanged");
                break;
            }
            case EVENT_PRECISE_CALL_STATE_CHANGED: {
                log("EVENT_PRECISE_CALL_STATE_CHANGED");
                if (!isAnyVoiceCallActiveOnDevice()) {
                    for (int i = 0; i < mActiveModemCount; i++) {
                        if ((getConnectFailureCount(i) > 0) &&
                                isPhoneIdValidForRetry(i)) {
                            sendRilCommands(i);
                            break;
                        }
                    }
                }
                super.handleMessage(msg);
                break;
            }
            case EVENT_ALLOW_DATA_TRUE_RESPONSE: {
                log("EVENT_ALLOW_DATA_TRUE_RESPONSE");
                onDdsSwitchResponse(msg.arg1, (AsyncResult)msg.obj);
                break;
            }
            case EVENT_ALLOW_DATA_FALSE_RESPONSE: {
                log("EVENT_ALLOW_DATA_FALSE_RESPONSE");
                mWaitForDetachResponse = false;
                for (int phoneId : mNewActivePhones) {
                    activate(phoneId);
                }
                if (mNewActivePhones.contains(ddsPhoneId)) {
                    mManualDdsSwitch = false;
                }
                break;
            }
            case EVENT_DDS_SWITCH_RESPONSE: {
                log("EVENT_DDS_SWITCH_RESPONSE");
                onDdsSwitchResponse(msg.arg1, (AsyncResult)msg.obj);
                break;
            }
            case EVENT_PREFERRED_SUB_VALID: {
                log("EVENT_PREFERRED_SUB_VALID");
                notifyDdsSwitchDone();
                break;
            }
            default:
                super.handleMessage(msg);
        }
    }

    private boolean isSimReady(int phoneId) {
        if (phoneId == SubscriptionManager.INVALID_PHONE_INDEX) {
            return false;
        }

        if (IccCardConstants.INTENT_VALUE_ICC_READY.equals(mSimStates[phoneId]) ||
                IccCardConstants.INTENT_VALUE_ICC_LOADED.equals(mSimStates[phoneId]) ||
                IccCardConstants.INTENT_VALUE_ICC_IMSI.equals(mSimStates[phoneId])) {
            log("SIM READY for phoneId: " + phoneId);
            return true;
        } else {
            return false;
        }
    }

    @Override
    protected boolean onEvaluate(boolean requestsChanged, String reason) {
        StringBuilder sb = new StringBuilder(reason);

        boolean diffDetected = requestsChanged;

        // Check if user setting of default non-opportunistic data sub is changed.
        final int primaryDataSubId = mSubscriptionController.getDefaultDataSubId();
        final int ddsPhoneId = mSubscriptionController.getPhoneId(primaryDataSubId);
        if (primaryDataSubId != mPrimaryDataSubId) {
            sb.append(" mPrimaryDataSubId ").append(mPrimaryDataSubId).append("->")
                .append(primaryDataSubId);
            mManualDdsSwitch = true;
            mPrimaryDataSubId = primaryDataSubId;
        }

        // Check to see if there is any active subscription on any phone
        boolean hasAnyActiveSubscription = false;
        boolean hasSubRefreshedOnThePreferredPhone = false;

        // Check if phoneId to subId mapping is changed.
        for (int i = 0; i < mActiveModemCount; i++) {
            int sub = mSubscriptionController.getSubIdUsingPhoneId(i);

            if (SubscriptionManager.isValidSubscriptionId(sub)) hasAnyActiveSubscription = true;

            if (sub != mPhoneSubscriptions[i]) {
                sb.append(" phone[").append(i).append("] ").append(mPhoneSubscriptions[i]);
                sb.append("->").append(sub);
                if (SubscriptionManager.isValidSubscriptionId(mPreferredDataSubId.get())
                        && mPhoneSubscriptions[i] == mPreferredDataSubId.get()) {
                    sb.append("sub refreshed");
                    hasSubRefreshedOnThePreferredPhone = true;
                }
                mPhoneSubscriptions[i] = sub;
                diffDetected = true;
            }
        }

        if (!hasAnyActiveSubscription) {
            transitionToEmergencyPhone();
        } else {
            if (VDBG) log("Found an active subscription");
        }
        final boolean isOldPeferredDataSubValid =
                SubscriptionManager.isValidSubscriptionId(mPreferredDataSubId.get());
        // Check if phoneId for preferred data is changed.
        int oldPreferredDataPhoneId = mPreferredDataPhoneId;

        // When there are no subscriptions, the preferred data phone ID is invalid, but we want
        // to keep a valid phoneId for Emergency, so skip logic that updates for preferred data
        // phone ID. Ideally there should be a single set of checks that evaluate the correct
        // phoneId on a service-by-service basis (EIMS being one), but for now... just bypass
        // this logic in the no-SIM case.
        if (hasAnyActiveSubscription) updatePreferredDataPhoneId();

        final boolean isPeferredDataSubValid =
                SubscriptionManager.isValidSubscriptionId(mPreferredDataSubId.get());

        if(!isOldPeferredDataSubValid && isPeferredDataSubValid) {
            // To avoid race condition, I'd like to send a msg in OnEvalute
            // This is used to ensure informing active phones again after the preferred
            // SUB is valid
            sendEmptyMessage(EVENT_PREFERRED_SUB_VALID);
        }

        if (oldPreferredDataPhoneId != mPreferredDataPhoneId) {
            sb.append(" preferred phoneId ").append(oldPreferredDataPhoneId)
                    .append("->").append(mPreferredDataPhoneId);
            if (SubscriptionManager.isValidPhoneId(oldPreferredDataPhoneId)) {
                mDdsRequestSent[oldPreferredDataPhoneId] = false;
            }
            mDdsSwitchState = DdsSwitchState.REQUIRED;
            diffDetected = true;
        } else if (hasSubRefreshedOnThePreferredPhone) {
            // Tell connectivity the real active data phone
            notifyPreferredDataSubIdChanged();
        }

        if (diffDetected) {
            log("evaluating due to " + sb.toString());
            if (mHalCommandToUse == HAL_COMMAND_PREFERRED_DATA) {
                // With HAL_COMMAND_PREFERRED_DATA, all phones are assumed to allow PS attach.
                // So marking all phone as active.
                for (int phoneId = 0; phoneId < mActiveModemCount; phoneId++) {
                    activate(phoneId);
                }
                sendRilCommands(mPreferredDataPhoneId);
            } else {
                List<Integer> newActivePhones = new ArrayList<Integer>();

                for (DcRequest dcRequest : mPrioritizedDcRequests) {
                    int phoneIdForRequest = phoneIdForRequest(dcRequest.networkRequest,
                            dcRequest.apnType);
                    if (phoneIdForRequest == INVALID_PHONE_INDEX) continue;
                    if (newActivePhones.contains(phoneIdForRequest)) continue;
                    newActivePhones.add(phoneIdForRequest);
                    if (newActivePhones.size() >= mMaxDataAttachModemCount) break;
                }

                if (VDBG) {
                    log("default subId = " + mPrimaryDataSubId);
                    log("preferred subId = " + mPreferredDataSubId.get());
                    for (int i = 0; i < mActiveModemCount; i++) {
                        log(" phone[" + i + "] using sub[" + mPhoneSubscriptions[i] + "]");
                    }
                    log(" newActivePhones:");
                    for (Integer i : newActivePhones) log("  " + i);
                }

                mNewActivePhones = newActivePhones;
                for (int phoneId = 0; (phoneId < mActiveModemCount); phoneId++) {
                    if (!newActivePhones.contains(phoneId)) {
                        deactivate(phoneId);
                    }
                }
                if (!mWaitForDetachResponse) {
                    // only activate phones up to the limit
                    final boolean activateDdsPhone = mNewActivePhones.contains(ddsPhoneId);
                    if (activateDdsPhone && mManualDdsSwitch) {
                        activate(ddsPhoneId);
                    } else {
                        for (int phoneId : newActivePhones) {
                            activate(phoneId);
                        }
                    }
                    if (activateDdsPhone) {
                        mManualDdsSwitch = false;
                    }
                }
            }
        }

        return diffDetected;
    }

    /* Determine the phone id on which PS attach needs to be done
     */
    protected int phoneIdForRequest(NetworkRequest netRequest, int apnType) {
        int subId = getSubIdFromNetworkSpecifier(netRequest.networkCapabilities
                .getNetworkSpecifier());

        if (subId == DEFAULT_SUBSCRIPTION_ID) return mPreferredDataPhoneId;
        if (subId == INVALID_SUBSCRIPTION_ID) return INVALID_PHONE_INDEX;

        int preferredDataSubId = SubscriptionManager.isValidPhoneId(mPreferredDataPhoneId)
                ? mPhoneSubscriptions[mPreferredDataPhoneId] : INVALID_SUBSCRIPTION_ID;
        // Currently we assume multi-SIM devices will only support one Internet PDN connection. So
        // if Internet PDN is established on the non-preferred phone, it will interrupt
        // Internet connection on the preferred phone. So we only accept Internet request with
        // preferred data subscription or no specified subscription.
        if (netRequest.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                && netRequest.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
                && subId != preferredDataSubId && subId != mValidator.getSubIdInValidation()) {
            // Returning INVALID_PHONE_INDEX will result in netRequest not being handled.
            return INVALID_PHONE_INDEX;
        }

        // This is for Volte+PS case
        if ((ApnSetting.TYPE_IMS == apnType) && mManualDdsSwitch
                && mMaxDataAttachModemCount != mActiveModemCount) {
            subId = mPrimaryDataSubId;
        }

        // Try to find matching phone ID. If it doesn't exist, we'll end up returning INVALID.
        int phoneId = INVALID_PHONE_INDEX;
        for (int i = 0; i < mActiveModemCount; i++) {
            if (mPhoneSubscriptions[i] == subId) {
                phoneId = i;
                break;
            }
        }
        return phoneId;
    }

    protected boolean isUiccProvisioned(int phoneId) {
        boolean isUiccApplicationEnabled = true;
        // FIXME get the SubscriptionManager.UICC_APPLICATIONS_ENABLED value and use it here
        log("isUiccProvisioned: status= " + isUiccApplicationEnabled + " phoneid=" + phoneId);
        return mSubscriptionController.isActiveSubId(mPhoneSubscriptions[phoneId]) && isUiccApplicationEnabled; 
    }

    @Override
    protected void deactivate(int phoneId) {
        PhoneState state = mPhoneStates[phoneId];
        if (state.active == false) {
            return;
        }
        state.active = false;
        log("deactivate " + phoneId);
        state.lastRequested = System.currentTimeMillis();
        if (mHalCommandToUse == HAL_COMMAND_ALLOW_DATA || mHalCommandToUse == HAL_COMMAND_UNKNOWN) {
            if (mSubscriptionController.isActiveSubId(mPhoneSubscriptions[phoneId])) {
                PhoneFactory.getPhone(phoneId).mCi.setDataAllowed(false,
                        obtainMessage(EVENT_ALLOW_DATA_FALSE_RESPONSE));
                mWaitForDetachResponse = true;
            }
        }
    }

    @Override
    protected void activate(int phoneId) {
        PhoneState state = mPhoneStates[phoneId];
        if ((state.active == true) && !mManualDdsSwitch &&
                (getConnectFailureCount(phoneId) == 0)) return;
        state.active = true;
        log("activate " + phoneId);
        state.lastRequested = System.currentTimeMillis();
        if (mHalCommandToUse == HAL_COMMAND_ALLOW_DATA || mHalCommandToUse == HAL_COMMAND_UNKNOWN) {
            PhoneFactory.getPhone(phoneId).mCi.setDataAllowed(true,
                    obtainMessage(EVENT_ALLOW_DATA_TRUE_RESPONSE, phoneId, 0));
        }
    }

    @Override
    protected void sendRilCommands(int phoneId) {
        if (!SubscriptionManager.isValidPhoneId(phoneId) || phoneId >= mActiveModemCount) {
            log("sendRilCommands: skip dds switch due to invalid phoneid=" + phoneId);
            return;
        }

        if (mHalCommandToUse == HAL_COMMAND_ALLOW_DATA || mHalCommandToUse == HAL_COMMAND_UNKNOWN) {
            PhoneFactory.getPhone(phoneId).mCi.setDataAllowed(isPhoneActive(phoneId),
                    obtainMessage(isPhoneActive(phoneId) ? EVENT_ALLOW_DATA_TRUE_RESPONSE
                    : EVENT_ALLOW_DATA_FALSE_RESPONSE, phoneId, 0));
        } else if (phoneId == mPreferredDataPhoneId) {
            if (!mDdsRequestSent[phoneId]) {
                // Only setPreferredDataModem if the phoneId equals to current mPreferredDataPhoneId
                log("sendRilCommands: setPreferredDataModem - phoneId: " + phoneId);
                mRadioConfig.setPreferredDataModem(phoneId,
                        obtainMessage(EVENT_DDS_SWITCH_RESPONSE, phoneId, 0));
                mDdsRequestSent[phoneId] = true;
            } else {
                log("sendRilCommands: setPreferredDataModem request already sent on phoneId: " +
                        phoneId);
            }
        }
    }

    /*
     * Method to check if any of the calls are started
     */
    @Override
    protected boolean isPhoneInVoiceCall(Phone phone) {
        if (phone == null) {
            return false;
        }
        boolean dataDuringCallsEnabled = false;
        DataEnabledSettings dataEnabledSettings = phone.getDataEnabledSettings();
        if (dataEnabledSettings != null) {
            dataDuringCallsEnabled = dataEnabledSettings.isDataAllowedInVoiceCall();
        }
        if (!dataDuringCallsEnabled) {
            log("isPhoneInVoiceCall: dataDuringCallsEnabled=" + dataDuringCallsEnabled);
            return false;
        }
        int phoneId = phone.getPhoneId();
        return (mFgCsCalls[phoneId].getState().isAlive() ||
                mBgCsCalls[phoneId].getState().isAlive() ||
                mRiCsCalls[phoneId].getState().isAlive() ||
                mFgImsCalls[phoneId].getState().isAlive() ||
                mBgImsCalls[phoneId].getState().isAlive() ||
                mRiImsCalls[phoneId].getState().isAlive());
    }

    private void resetConnectFailureCount(int phoneId) {
        mAllowDataFailure[phoneId] = 0;
    }

    private void incConnectFailureCount(int phoneId) {
        mAllowDataFailure[phoneId]++;
    }

    @VisibleForTesting
    public int getConnectFailureCount(int phoneId) {
        return mAllowDataFailure[phoneId];
    }

    private void handleConnectMaxFailure(int phoneId) {
        resetConnectFailureCount(phoneId);
        int ddsSubId = mSubscriptionController.getDefaultDataSubId();
        int ddsPhoneId = mSubscriptionController.getPhoneId(ddsSubId);
        if (SubscriptionManager.isValidPhoneId(ddsPhoneId) && phoneId != ddsPhoneId) {
            log("ALLOW_DATA retries exhausted on phoneId = " + phoneId);
            enforceDds(ddsPhoneId);
        }
    }

    private void enforceDds(int phoneId) {
        int[] subId = mSubscriptionController.getSubId(phoneId);
        log("enforceDds: subId = " + subId[0]);
        mSubscriptionController.setDefaultDataSubId(subId[0]);
    }

    private boolean isAnyVoiceCallActiveOnDevice() {
        boolean ret = (CallManager.getInstance().getState() != PhoneConstants.State.IDLE);
        log("isAnyVoiceCallActiveOnDevice: " + ret);
        return ret;
    }

    private void onDdsSwitchResponse(int phoneId, AsyncResult ar) {
        if (ar.exception != null) {
            mDdsRequestSent[phoneId] = false;
            incConnectFailureCount(phoneId);
            log("Dds switch failed on phoneId = " + phoneId + ", failureCount = "
                    + getConnectFailureCount(phoneId));

            if (isAnyVoiceCallActiveOnDevice()) {
                boolean isTempSwitchPropEnabled = SystemProperties.getBoolean(
                        PROPERTY_TEMP_DDSSWITCH, false);
                int ddsPhoneId = mSubscriptionController.getPhoneId(
                        mSubscriptionController.getDefaultDataSubId());
                log("onDdsSwitchResponse: isTempSwitchPropEnabled=" + isTempSwitchPropEnabled +
                        ", ddsPhoneId=" + ddsPhoneId + ", mPreferredDataPhoneId=" +
                        mPreferredDataPhoneId);
                if (isTempSwitchPropEnabled && (phoneId != ddsPhoneId) &&
                        getConnectFailureCount(phoneId) < MAX_CONNECT_FAILURE_COUNT) {
                    log("Retry Temporary DDS switch on phoneId:" + phoneId);
                    sendRilCommands(phoneId);
                } else {
                    /* Any DDS retry while voice call is active is in vain
                       Wait for call to get disconnected */
                    log("Wait for call end indication");
                }
                return;
            }

            if (!isSimReady(phoneId)) {
                /* If there is a attach failure due to sim not ready then
                hold the retry until sim gets ready */
                log("Wait for SIM to get READY");
                return;
            }

            int ddsSwitchFailureCount = getConnectFailureCount(phoneId);
            if (ddsSwitchFailureCount > MAX_CONNECT_FAILURE_COUNT) {
                handleConnectMaxFailure(phoneId);
            } else {
                int retryDelay = mRetryArray[ddsSwitchFailureCount - 1] * 1000;
                log("Scheduling DDS switch retry after: " + retryDelay);
                postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        log("Running DDS switch retry");
                        if (isPhoneIdValidForRetry(phoneId)) {
                            sendRilCommands(phoneId);
                        } else {
                            log("Abandon DDS switch retry");
                            resetConnectFailureCount(phoneId);
                        }
                    }}, retryDelay);
                }
        } else {
            log("DDS switch success on phoneId = " + phoneId);
            resetConnectFailureCount(phoneId);
            if (mDdsSwitchState == DdsSwitchState.REQUIRED) {
                mDdsSwitchState = DdsSwitchState.DONE;
            }
            notifyDdsSwitchDone();
        }
    }

    private void notifyDdsSwitchDone() {
        log("notifyDdsSwitchDone on the preferred data SUB = " + mPreferredDataSubId.get()
                + " and the preferred phone ID = " + mPreferredDataPhoneId);
        // Notify all registrants.
        mActivePhoneRegistrants.notifyRegistrants();
        notifyPreferredDataSubIdChanged();

        if (mDdsSwitchState == DdsSwitchState.DONE
                && SubscriptionManager.isValidSubscriptionId(mPreferredDataSubId.get())) {
            mDdsSwitchState = mDdsSwitchState.NONE;
            Intent intent = new Intent(
                    "org.codeaurora.intent.action.ACTION_DDS_SWITCH_DONE");
            intent.putExtra(PhoneConstants.SUBSCRIPTION_KEY, mPreferredDataSubId.get());
            intent.addFlags(Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND);
            log("Broadcast dds switch done intent on " + mPreferredDataSubId.get());
            mContext.sendBroadcast(intent);
        }
    }

    private boolean isPhoneIdValidForRetry(int phoneId) {
        boolean isValid = false;
        int phoneIdForRequest = INVALID_PHONE_INDEX;
        int ddsPhoneId = mSubscriptionController.getPhoneId(
                mSubscriptionController.getDefaultDataSubId());
        if (ddsPhoneId != INVALID_PHONE_INDEX && ddsPhoneId == phoneId) {
            isValid = true;
        } else {
            if (mPrioritizedDcRequests.size() > 0) {
                for (int i = 0; i < mMaxDataAttachModemCount; i++) {
                    DcRequest dcRequest = mPrioritizedDcRequests.get(i);
                    if (dcRequest != null) {
                        phoneIdForRequest = phoneIdForRequest(dcRequest.networkRequest,
                                dcRequest.apnType);
                        if (phoneIdForRequest == phoneId) {
                            isValid = true;
                            break;
                        }
                    }
                }
            }
        }
        return isValid;
    }

    /*
     * Returns true if mPhoneIdInVoiceCall is set for active calls
     */
    private boolean isCallInProgress() {
        return SubscriptionManager.isValidPhoneId(mPhoneIdInVoiceCall);
    }
}
