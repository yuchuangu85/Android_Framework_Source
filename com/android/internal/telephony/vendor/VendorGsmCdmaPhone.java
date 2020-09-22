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

import android.content.Context;
import android.os.AsyncResult;
import android.os.Message;
import android.telephony.PhoneNumberUtils;
import android.telephony.Rlog;
import android.telephony.ServiceState;
import android.telephony.SubscriptionManager;

import com.android.internal.telephony.CommandsInterface;
import com.android.internal.telephony.GsmCdmaPhone;
import com.android.internal.telephony.PhoneNotifier;
import com.android.internal.telephony.TelephonyComponentFactory;

public class VendorGsmCdmaPhone extends GsmCdmaPhone {
    private static final String LOG_TAG = "VendorGsmCdmaPhone";
    private static final int PROP_EVENT_START = EVENT_LAST;
    private static final int DEFAULT_PHONE_INDEX = 0;

    private boolean mIsPhoneReadySent = false;
    private boolean mIsPhoneReadyPending = false;
    private static int READY = 1;

    public VendorGsmCdmaPhone(Context context,
            CommandsInterface ci, PhoneNotifier notifier, int phoneId,
            int precisePhoneType, TelephonyComponentFactory telephonyComponentFactory) {
        this(context, ci, notifier, false, phoneId, precisePhoneType,
                telephonyComponentFactory);
    }

    public VendorGsmCdmaPhone(Context context,
            CommandsInterface ci, PhoneNotifier notifier, boolean unitTestMode, int phoneId,
            int precisePhoneType, TelephonyComponentFactory telephonyComponentFactory) {
        super(context, ci, notifier, unitTestMode, phoneId, precisePhoneType,
                telephonyComponentFactory);
        Rlog.d(LOG_TAG, "Constructor");
    }

    @Override
    protected void phoneObjectUpdater(int newVoiceTech) {
        super.phoneObjectUpdater(newVoiceTech);
    }

    @Override
    public boolean getCallForwardingIndicator() {
        if (!isCurrentSubValid()) {
            return false;
        }
        return super.getCallForwardingIndicator();
    }

    private boolean isCurrentSubValid() {
        boolean isUiccApplicationEnabled = true;
        // FIXME get the SubscriptionManager.UICC_APPLICATIONS_ENABLED value and use it above

        SubscriptionManager subscriptionManager = SubscriptionManager.from(mContext);

        Rlog.d(LOG_TAG, "ProvisionStatus: " + isUiccApplicationEnabled + " phone id:" + mPhoneId);
        return subscriptionManager.isActiveSubscriptionId(getSubId()) && isUiccApplicationEnabled;
    }

    public void fetchIMEI() {
            Rlog.d(LOG_TAG, "fetching device id");
            mCi.getDeviceIdentity(obtainMessage(EVENT_GET_DEVICE_IDENTITY_DONE));
    }

    @Override
    public void handleMessage(Message msg) {
        Rlog.d(LOG_TAG, "handleMessage: Event: " + msg.what);
        AsyncResult ar;
        switch(msg.what) {

            case EVENT_SIM_RECORDS_LOADED:
                if(isPhoneTypeGsm()) {
                    Rlog.d(LOG_TAG, "notify call forward indication, phone id:" + mPhoneId);
                    notifyCallForwardingIndicator();
                }

                super.handleMessage(msg);
                break;

            case EVENT_RADIO_AVAILABLE:
                mIsPhoneReadySent = false;
                super.handleMessage(msg);
                break;

            case EVENT_RIL_CONNECTED:
                mIsPhoneReadySent = false;
                super.handleMessage(msg);
                break;

            default: {
                super.handleMessage(msg);
            }

        }
    }

    // In DSDA, char 'D' is used as DTMF char for playing supervisory tone for G/W.
    // For CDMA, '#' is used. A, B, C & D are also supported as DTMF digits for G/W networks.
    @Override
    public void startDtmf(char c) {
        if (!(PhoneNumberUtils.is12Key(c) || (c == 'D'))) {
            Rlog.e(LOG_TAG, "startDtmf called with invalid character '" + c + "'");
        } else {
            if (isPhoneTypeCdma() && c == 'D') {
                c = '#';
            }
            mCi.startDtmf(c, null);
        }
    }

    // For CDMA sendBurstDtmf is used, if dtmf char is 'D' then it with '#'
    // since 'D' is used for SCH tone and for CDMA it has to be '#'.
    @Override
    public void sendBurstDtmf(String dtmfString, int on, int off, Message onComplete) {
        Character c = dtmfString.charAt(0);
        if(dtmfString.length() == 1 && c == 'D') {
            dtmfString = c.toString();
        }
        super.sendBurstDtmf(dtmfString, on, off, onComplete);
    }

    // When OOS occurs, IMS registration may be still available so that IMS service
    // state is also in-service, then reports in-service to upper layer.
    // Add a precondition to merge IMS service so that notifies proper service state
    // after IMS changes RAT.
    @Override
    public ServiceState getServiceState() {
        if (mSST == null || mSST.mSS.getState() != ServiceState.STATE_IN_SERVICE) {
            // Ensure UE has IMS service capability, then merge IMS service state.
            // Video enabled includes WIFI video
            final boolean isImsEnabled = mImsPhone != null && (mImsPhone.isVolteEnabled()
                    || mImsPhone.isVideoEnabled()
                    || mImsPhone.isWifiCallingEnabled());
            if (isImsEnabled) {
                return ServiceState.mergeServiceStates(
                        ((mSST == null) ? new ServiceState() : mSST.mSS),
                        mImsPhone.getServiceState());
            }
        }

        if (mSST != null) {
            return mSST.mSS;
        } else {
            // avoid potential NPE in EmergencyCallHelper during Phone switch
            return new ServiceState();
        }
    }

    private void logd(String msg) {
        Rlog.d(LOG_TAG, "[" + mPhoneId +" ] " + msg);
    }

    private void loge(String msg) {
        Rlog.e(LOG_TAG, "[" + mPhoneId +" ] " + msg);
    }
}
