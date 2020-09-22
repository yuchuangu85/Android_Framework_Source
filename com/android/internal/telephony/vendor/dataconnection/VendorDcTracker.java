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

package com.android.internal.telephony.vendor.dataconnection;

import android.app.AlertDialog;
import android.view.WindowManager;

import android.telephony.AccessNetworkConstants;
import android.telephony.Annotation.DataFailureCause;
import android.telephony.CarrierConfigManager;
import android.telephony.DataFailCause;
import android.telephony.data.ApnSetting;
import android.telephony.Rlog;
import android.telephony.ServiceState;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;

import com.android.internal.telephony.dataconnection.ApnContext;
import com.android.internal.telephony.dataconnection.DcTracker;
import com.android.internal.telephony.DctConstants;
import com.android.internal.telephony.GsmCdmaPhone;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.uicc.IccRecords;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.AsyncChannel;

import android.database.Cursor;
import android.content.Context;
import android.os.PersistableBundle;
import android.provider.Telephony;

import java.util.HashSet;
import java.util.Iterator;

public class VendorDcTracker extends DcTracker {
    private String LOG_TAG = "VendorDCT";
    private HashSet<String> mIccidSet = new HashSet<String>();
    private int mTransportType = AccessNetworkConstants.TRANSPORT_TYPE_WWAN;

    // Maximum data reject count
    public static final int MAX_PDP_REJECT_COUNT = 3;

    // Data reset event tracker to know reset events.
    private VendorDataResetEventTracker mVendorDataResetEventTracker = null;

    // data reject dialog, made static because only one dialog object can be
    // used between multiple dataconnection objects.
    protected static AlertDialog mDataRejectDialog = null;

    //Store data reject cause for comparison
    private String mDataRejectReason = "NONE";

    //Store data reject count
    private int mDataRejectCount = 0;

    //Store data reject cause code
    private int mPdpRejectCauseCode = 0;

    // Constructor
    public VendorDcTracker(Phone phone, int transportType) {
        super(phone, transportType);
        mTransportType = transportType;
        LOG_TAG = LOG_TAG + "-" +
                ((transportType == AccessNetworkConstants.TRANSPORT_TYPE_WWAN) ? "C" : "I");
        if (DBG) log(LOG_TAG + ".constructor");
        fillIccIdSet();
    }

    @Override
    protected boolean allowInitialAttachForOperator() {
        String iccId = mPhone.getIccSerialNumber();
        if (iccId != null) {
            Iterator<String> itr = mIccidSet.iterator();
            while (itr.hasNext()) {
                if (iccId.contains(itr.next())) {
                    return false;
                }
            }
        }
        return true;
    }

    private void fillIccIdSet() {
        mIccidSet.add("8991840");
        mIccidSet.add("8991854");
        mIccidSet.add("8991855");
        mIccidSet.add("8991856");
        mIccidSet.add("8991857");
        mIccidSet.add("8991858");
        mIccidSet.add("8991859");
        mIccidSet.add("899186");
        mIccidSet.add("8991870");
        mIccidSet.add("8991871");
        mIccidSet.add("8991872");
        mIccidSet.add("8991873");
        mIccidSet.add("8991874");
    }

    @Override
    protected void onVoiceCallEnded() {
        if (DBG) log("onVoiceCallEnded");
        mInVoiceCall = false;
        if (isConnected()) {
            if (!mPhone.getServiceStateTracker().isConcurrentVoiceAndDataAllowed()) {
                startNetStatPoll();
                startDataStallAlarm(DATA_STALL_NOT_SUSPECTED);
                mPhone.notifyAllActiveDataConnections();
            } else {
                // clean slate after call end.
                resetPollStats();
            }
        }
        //Allow data call retry only on DDS sub
        if (mPhone.getSubId() == SubscriptionManager.getDefaultDataSubscriptionId()) {
            // reset reconnect timer
            setupDataOnAllConnectableApns(Phone.REASON_VOICE_CALL_ENDED, RetryFailures.ALWAYS);

        }
    }

    @Override
    protected void setupDataOnConnectableApn(ApnContext apnContext, String reason,
            RetryFailures retryFailures) {
        if (mPhone.getContext().getResources().getBoolean(
                com.android.internal.R.bool.config_pdp_reject_enable_retry) &&
                mDataRejectCount > 0) {
            log("setupDataOnConnectableApn: data retry in progress, skip processing");
        } else {
            super.setupDataOnConnectableApn(apnContext, reason, retryFailures);
        }
    }

    @Override
    protected void onDataSetupComplete(ApnContext apnContext, boolean success, int cause,
            @RequestNetworkType int requestType) {
        boolean isPdpRejectConfigEnabled = mPhone.getContext().getResources().getBoolean(
                com.android.internal.R.bool.config_pdp_reject_enable_retry);
        if (success) {
            if (isPdpRejectConfigEnabled) {
                handlePdpRejectCauseSuccess();
            }
        } else {
            mPdpRejectCauseCode = cause;
        }

        super.onDataSetupComplete(apnContext, success, cause, requestType);
    }

    @Override
    protected void onDataSetupCompleteError(ApnContext apnContext,
            @RequestNetworkType int requestType) {
        long delay = apnContext.getDelayForNextApn(mFailFast);
        if (mPhone.getContext().getResources().getBoolean(
                com.android.internal.R.bool.config_pdp_reject_enable_retry)) {
            String reason = DataFailCause.toString(mPdpRejectCauseCode);

            if (isMatchingPdpRejectCause(reason)) {
                if (mVendorDataResetEventTracker == null) {
                    mVendorDataResetEventTracker = new VendorDataResetEventTracker(mTransportType,
                            mPhone, mResetEventListener);
                }
                if (mDataRejectCount == 0) {
                    mVendorDataResetEventTracker.startResetEventTracker();
                }
                boolean isHandled = handlePdpRejectCauseFailure(reason);

                /* If MAX Reject count reached, display pop-up to user */
                if (MAX_PDP_REJECT_COUNT <= mDataRejectCount) {
                    if (DBG) log("onDataSetupCompleteError: reached max retry count");
                    displayPopup(mDataRejectReason);
                    delay = -1;
                } else if (isHandled) {
                    delay = mPhone.getContext().getResources().getInteger(
                            com.android.internal.R.integer.config_pdp_reject_retry_delay_ms);
                    if (DBG) log("onDataSetupCompleteError: delay from config: " + delay);
                }
            } else {
                if (DBG) log("onDataSetupCompleteError: reset reject count");
                resetDataRejectCounter();
            }
        }

        // Check if we need to retry or not.
        // TODO: We should support handover retry in the future.
        if (delay >= 0) {
            if (DBG) log("onDataSetupCompleteError: Try next APN. delay = " + delay);
            apnContext.setState(DctConstants.State.RETRYING);
            // Wait a bit before trying the next APN, so that
            // we're not tying up the RIL command channel

            startReconnect(delay, apnContext);
        } else {
            // If we are not going to retry any APN, set this APN context to failed state.
            // This would be the final state of a data connection.
            apnContext.setState(DctConstants.State.FAILED);
            mPhone.notifyDataConnection(apnContext.getApnType());
            apnContext.setDataConnection(null);
            if (DBG) log("onDataSetupCompleteError: Stop retrying APNs. delay=" + delay
                    + ", requestType=" + requestTypeToString(requestType));
        }
    }

    /*
     * Reset data reject params on data call success
     */
    private void handlePdpRejectCauseSuccess() {
        if (mDataRejectCount > 0) {
            if (DBG) log("handlePdpRejectCauseSuccess: reset reject count");
            resetDataRejectCounter();
        }
    }

        /*
     * Process data failure if RAT is WCDMA
     * And if the failure cause matches one of the following cause codes:
     * 1. USER_AUTHENTICATION
     * 2. SERVICE_OPTION_NOT_SUBSCRIBED
     * 3. MULTI_CONN_TO_SAME_PDN_NOT_ALLOWED
     */
    private boolean handlePdpRejectCauseFailure(String reason) {
        boolean handleFailure = false;

        // Check if data rat is WCDMA
        if (isWCDMA(getDataRat())) {
            if (DBG) log("handlePdpRejectCauseFailure: reason=" + reason +
                    ", mDataRejectReason=" + mDataRejectReason);
            /*
             * If previously rejected code is not same as current data reject reason,
             * then reset the count and reset the reject reason
             */
            if (!reason.equalsIgnoreCase(mDataRejectReason)) {
                resetDataRejectCounter();
            }

            /*
             * If failure reason is USER_AUTHENTICATION or
             * SERVICE_OPTION_NOT_SUBSCRIBED or MULTI_CONN_TO_SAME_PDN_NOT_ALLOWED,
             * increment counter and store reject cause
             */
            if (isMatchingPdpRejectCause(reason)) {
                mDataRejectCount++;
                mDataRejectReason = reason;
                if (DBG) log ("handlePdpRejectCauseFailure: DataRejectCount = " +
                        mDataRejectCount);
                handleFailure = true;
            }
        } else {
            if (DBG) log("isPdpRejectCauseFailureHandled: DataConnection not on wcdma");
            resetDataRejectCounter();
        }

        return handleFailure;
    }

    /*
     * Data reset event listener. Dc will get get onResetEvent
     * whenever any data reset event occurs
     */
    private VendorDataResetEventTracker.ResetEventListener mResetEventListener =
           new VendorDataResetEventTracker.ResetEventListener() {
        @Override
        public void onResetEvent(boolean retry) {
            if (DBG) log("onResetEvent: retry=" + retry);

            //Dismiss dialog
            if (mDataRejectDialog != null && mDataRejectDialog.isShowing()) {
                if (DBG) log("onResetEvent: Dismiss dialog");
                mDataRejectDialog.dismiss();
            }
            mVendorDataResetEventTracker.stopResetEventTracker();

            for (ApnContext apnContext : mApnContexts.values()) {
                if (mDataRejectCount > 0) {
                    if (DBG) log("onResetEvent: reset reject count=" + mDataRejectCount);
                    resetDataRejectCounter();
                    cancelReconnect(apnContext);
                    if (retry) {
                        if (DBG) log("onResetEvent: retry data call on apnContext=" + apnContext);
                        sendMessage(obtainMessage(DctConstants.EVENT_TRY_SETUP_DATA, apnContext));
                    }
                }
            }
        }
    };

    /**
     * This function will display the pdp reject message
     */
    private void displayPopup(String pdpRejectCause) {
        if (DBG) log("displayPopup : " + pdpRejectCause);
        String title = mPhone.getContext().getResources().
                getString(com.android.internal.R.string.config_pdp_reject_dialog_title);
        String message = null;
        if (pdpRejectCause.equalsIgnoreCase("USER_AUTHENTICATION")) {
            message = mPhone.getContext().getResources().
                    getString(com.android.internal.R.string.config_pdp_reject_user_authentication_failed);
        } else if (pdpRejectCause.equalsIgnoreCase("SERVICE_OPTION_NOT_SUBSCRIBED")) {
            message = mPhone.getContext().getResources().getString(
                    com.android.internal.R.string.config_pdp_reject_service_not_subscribed);
        } else if (pdpRejectCause.equalsIgnoreCase("MULTI_CONN_TO_SAME_PDN_NOT_ALLOWED")) {
            message = mPhone.getContext().getResources().getString(
                    com.android.internal.R.string.config_pdp_reject_multi_conn_to_same_pdn_not_allowed);
        }
        if (mDataRejectDialog == null || !mDataRejectDialog.isShowing()) {
            AlertDialog.Builder builder = new AlertDialog.Builder(
                    mPhone.getContext());
            builder.setPositiveButton(android.R.string.ok, null);
            mDataRejectDialog = builder.create();
        }
        mDataRejectDialog.setMessage(message);
        mDataRejectDialog.setCanceledOnTouchOutside(false);
        mDataRejectDialog.setTitle(title);
        mDataRejectDialog.getWindow().setType(
                WindowManager.LayoutParams.TYPE_SYSTEM_ALERT);
        mDataRejectDialog.show();
    }

    /*
     * returns true if data reject cause matches errors listed
     */
    private boolean isMatchingPdpRejectCause(String reason) {
        return reason.equalsIgnoreCase("USER_AUTHENTICATION") ||
               reason.equalsIgnoreCase("SERVICE_OPTION_NOT_SUBSCRIBED") ||
               reason.equalsIgnoreCase("MULTI_CONN_TO_SAME_PDN_NOT_ALLOWED");
    }

    /**
     * returns true if radioTechnology is WCDMA rat, else false
     */
    private boolean isWCDMA(int radioTechnology) {
        return radioTechnology == ServiceState.RIL_RADIO_TECHNOLOGY_UMTS
            || radioTechnology == ServiceState.RIL_RADIO_TECHNOLOGY_HSDPA
            || radioTechnology == ServiceState.RIL_RADIO_TECHNOLOGY_HSUPA
            || radioTechnology == ServiceState.RIL_RADIO_TECHNOLOGY_HSPA
            || radioTechnology == ServiceState.RIL_RADIO_TECHNOLOGY_HSPAP;

    }

    /*
     * Reset data reject count and reason
     */
    private void resetDataRejectCounter() {
        mDataRejectCount = 0;
        mDataRejectReason = "NONE";
    }

    @Override
    protected void log(String s) {
        Rlog.d(LOG_TAG, "[" + mPhone.getPhoneId() + "]" + s);
    }
}
