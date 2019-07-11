/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.internal.telephony.cdma;

import android.app.ActivityManagerNative;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.SQLException;
import android.net.Uri;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.Message;
import android.os.UserHandle;
import android.preference.PreferenceManager;
import android.os.PowerManager;
import android.os.SystemProperties;
import android.provider.Telephony;
import android.text.TextUtils;
import android.telephony.SubscriptionManager;
import android.telephony.Rlog;

import com.android.internal.telephony.CommandsInterface;

import android.telephony.TelephonyManager;

import com.android.internal.telephony.dataconnection.DcTracker;
import com.android.internal.telephony.MccTable;
import com.android.internal.telephony.OperatorInfo;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.PhoneNotifier;
import com.android.internal.telephony.PhoneProxy;
import com.android.internal.telephony.PhoneFactory;
import com.android.internal.telephony.PhoneSubInfo;
import com.android.internal.telephony.SMSDispatcher;
import com.android.internal.telephony.SmsBroadcastUndelivered;
import com.android.internal.telephony.Subscription;
import com.android.internal.telephony.SubscriptionController;
import com.android.internal.telephony.gsm.GsmSMSDispatcher;
import com.android.internal.telephony.gsm.SmsMessage;
import com.android.internal.telephony.uicc.IccRecords;
import com.android.internal.telephony.uicc.IsimRecords;
import com.android.internal.telephony.uicc.IsimUiccRecords;
import com.android.internal.telephony.uicc.RuimRecords;
import com.android.internal.telephony.uicc.SIMRecords;
import com.android.internal.telephony.uicc.UiccCardApplication;
import com.android.internal.telephony.uicc.UiccController;
import com.android.internal.telephony.ServiceStateTracker;
import com.android.internal.telephony.TelephonyIntents;
import com.android.internal.telephony.TelephonyProperties;

import java.io.FileDescriptor;
import java.io.PrintWriter;

import static com.android.internal.telephony.TelephonyProperties.PROPERTY_ICC_OPERATOR_ALPHA;
import static com.android.internal.telephony.TelephonyProperties.PROPERTY_ICC_OPERATOR_ISO_COUNTRY;
import static com.android.internal.telephony.TelephonyProperties.PROPERTY_ICC_OPERATOR_NUMERIC;
import static com.android.internal.telephony.PhoneConstants.EVENT_SUBSCRIPTION_ACTIVATED;
import static com.android.internal.telephony.PhoneConstants.EVENT_SUBSCRIPTION_DEACTIVATED;

public class CDMALTEPhone extends CDMAPhone {
    static final String LOG_LTE_TAG = "CDMALTEPhone";
    private static final boolean DBG = true;

    /** CdmaLtePhone in addition to RuimRecords available from
     * PhoneBase needs access to SIMRecords and IsimUiccRecords
     */
    private SIMRecords mSimRecords;
    private IsimUiccRecords mIsimUiccRecords;

    // Constructors
    public CDMALTEPhone(Context context, CommandsInterface ci, PhoneNotifier notifier,
            int phoneId) {
        this(context, ci, notifier, false, phoneId);
    }

    public CDMALTEPhone(Context context, CommandsInterface ci, PhoneNotifier notifier,
            boolean unitTestMode, int phoneId) {
        super(context, ci, notifier, phoneId);

        Rlog.d(LOG_TAG, "CDMALTEPhone: constructor: sub = " + mPhoneId);

        mDcTracker = new DcTracker(this);

    }

    // Constructors
    public CDMALTEPhone(Context context, CommandsInterface ci, PhoneNotifier notifier) {
        super(context, ci, notifier, false);
    }

    @Override
    public void handleMessage (Message msg) {
        switch (msg.what) {
            case EVENT_SUBSCRIPTION_ACTIVATED:
                log("EVENT_SUBSCRIPTION_ACTIVATED");
                onSubscriptionActivated();
                break;

            case EVENT_SUBSCRIPTION_DEACTIVATED:
                log("EVENT_SUBSCRIPTION_DEACTIVATED");
                onSubscriptionDeactivated();
                break;

            default:
                super.handleMessage(msg);
        }
    }

    @Override
    protected void initSstIcc() {
        mSST = new CdmaLteServiceStateTracker(this);
    }

    @Override
    public void dispose() {
        synchronized(PhoneProxy.lockForRadioTechnologyChange) {
            super.dispose();
        }
    }

    @Override
    public void removeReferences() {
        super.removeReferences();
    }

    @Override
    public PhoneConstants.DataState getDataConnectionState(String apnType) {
        PhoneConstants.DataState ret = PhoneConstants.DataState.DISCONNECTED;

        if (mSST == null) {
            // Radio Technology Change is ongoing, dispose() and
            // removeReferences() have already been called

            ret = PhoneConstants.DataState.DISCONNECTED;
        } else if (mDcTracker.isApnTypeEnabled(apnType) == false) {
            ret = PhoneConstants.DataState.DISCONNECTED;
        } else {
            switch (mDcTracker.getState(apnType)) {
                case RETRYING:
                case FAILED:
                case IDLE:
                    ret = PhoneConstants.DataState.DISCONNECTED;
                    break;

                case CONNECTED:
                case DISCONNECTING:
                    if (mCT.mState != PhoneConstants.State.IDLE &&
                            !mSST.isConcurrentVoiceAndDataAllowed()) {
                        ret = PhoneConstants.DataState.SUSPENDED;
                    } else {
                        ret = PhoneConstants.DataState.CONNECTED;
                    }
                    break;

                case CONNECTING:
                case SCANNING:
                    ret = PhoneConstants.DataState.CONNECTING;
                    break;
            }
        }

        log("getDataConnectionState apnType=" + apnType + " ret=" + ret);
        return ret;
    }

    /**
     * Sets the "current" field in the telephony provider according to the
     * build-time operator numeric property
     *
     * @return true for success; false otherwise.
     */
    @Override
    boolean updateCurrentCarrierInProvider(String operatorNumeric) {
        boolean retVal;
        if (mUiccController.getUiccCardApplication(mPhoneId, UiccController.APP_FAM_3GPP) == null) {
            if (DBG) log("updateCurrentCarrierInProvider APP_FAM_3GPP == null");
            retVal = super.updateCurrentCarrierInProvider(operatorNumeric);
        } else {
            if (DBG) log("updateCurrentCarrierInProvider not updated");
            retVal = true;
        }
        if (DBG) log("updateCurrentCarrierInProvider X retVal=" + retVal);
        return retVal;
    }

    @Override
    public boolean updateCurrentCarrierInProvider() {
        long currentDds = SubscriptionManager.getDefaultDataSubId();
        String operatorNumeric = getOperatorNumeric();

        Rlog.d(LOG_TAG, "updateCurrentCarrierInProvider: mSubscription = " + getSubId()
                + " currentDds = " + currentDds + " operatorNumeric = " + operatorNumeric);

        if (!TextUtils.isEmpty(operatorNumeric) && (getSubId() == currentDds)) {
            try {
                Uri uri = Uri.withAppendedPath(Telephony.Carriers.CONTENT_URI, "current");
                ContentValues map = new ContentValues();
                map.put(Telephony.Carriers.NUMERIC, operatorNumeric);
                mContext.getContentResolver().insert(uri, map);
                return true;
            } catch (SQLException e) {
                Rlog.e(LOG_TAG, "Can't store current operator", e);
            }
        }
        return false;
    }

    // return IMSI from USIM as subscriber ID.
    @Override
    public String getSubscriberId() {
        return (mSimRecords != null) ? mSimRecords.getIMSI() : "";
    }

    // return GID1 from USIM
    @Override
    public String getGroupIdLevel1() {
        return (mSimRecords != null) ? mSimRecords.getGid1() : "";
    }

    @Override
    public String getImei() {
        return mImei;
    }

    @Override
    public String getDeviceSvn() {
        return mImeiSv;
    }

    @Override
    public IsimRecords getIsimRecords() {
        return mIsimUiccRecords;
    }

    @Override
    public String getMsisdn() {
        return (mSimRecords != null) ? mSimRecords.getMsisdnNumber() : null;
    }

    @Override
    public void getAvailableNetworks(Message response) {
        mCi.getAvailableNetworks(response);
    }

    @Override
    protected void onUpdateIccAvailability() {
        if (mUiccController == null ) {
            return;
        }

        // Update IsimRecords
        UiccCardApplication newUiccApplication =
                mUiccController.getUiccCardApplication(mPhoneId, UiccController.APP_FAM_IMS);
        IsimUiccRecords newIsimUiccRecords = null;

        if (newUiccApplication != null) {
            newIsimUiccRecords = (IsimUiccRecords) newUiccApplication.getIccRecords();
        }
        mIsimUiccRecords = newIsimUiccRecords;

        // Update UsimRecords
        newUiccApplication = mUiccController.getUiccCardApplication(mPhoneId,
                UiccController.APP_FAM_3GPP);
        SIMRecords newSimRecords = null;
        if (newUiccApplication != null) {
            newSimRecords = (SIMRecords) newUiccApplication.getIccRecords();
        }
        mSimRecords = newSimRecords;

        super.onUpdateIccAvailability();
    }

    @Override
    protected void init(Context context, PhoneNotifier notifier) {
        mCi.setPhoneType(PhoneConstants.PHONE_TYPE_CDMA);
        mCT = new CdmaCallTracker(this);
        mCdmaSSM = CdmaSubscriptionSourceManager.getInstance(context, mCi, this,
                EVENT_CDMA_SUBSCRIPTION_SOURCE_CHANGED, null);
        mRuimPhoneBookInterfaceManager = new RuimPhoneBookInterfaceManager(this);
        mSubInfo = new PhoneSubInfo(this);
        mEriManager = new EriManager(this, context, EriManager.ERI_FROM_XML);

        mCi.registerForAvailable(this, EVENT_RADIO_AVAILABLE, null);
        mCi.registerForOffOrNotAvailable(this, EVENT_RADIO_OFF_OR_NOT_AVAILABLE, null);
        mCi.registerForOn(this, EVENT_RADIO_ON, null);
        mCi.setOnSuppServiceNotification(this, EVENT_SSN, null);
        mSST.registerForNetworkAttached(this, EVENT_REGISTERED_TO_NETWORK, null);
        mCi.setEmergencyCallbackMode(this, EVENT_EMERGENCY_CALLBACK_MODE_ENTER, null);
        mCi.registerForExitEmergencyCallbackMode(this, EVENT_EXIT_EMERGENCY_CALLBACK_RESPONSE,
                null);

        PowerManager pm
            = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,LOG_TAG);

        // This is needed to handle phone process crashes
        String inEcm = SystemProperties.get(TelephonyProperties.PROPERTY_INECM_MODE, "false");
        mIsPhoneInEcmState = inEcm.equals("true");
        if (mIsPhoneInEcmState) {
            // Send a message which will invoke handleExitEmergencyCallbackMode
            mCi.exitEmergencyCallbackMode(obtainMessage(EVENT_EXIT_EMERGENCY_CALLBACK_RESPONSE));
        }

        // get the string that specifies the carrier OTA Sp number
        mCarrierOtaSpNumSchema = SystemProperties.get(
                TelephonyProperties.PROPERTY_OTASP_NUM_SCHEMA,"");

        setProperties();
    }

    private void onSubscriptionActivated() {
//        mSubscriptionData = SubscriptionManager.getCurrentSubscription(mSubscription);

        log("SUBSCRIPTION ACTIVATED : slotId : " + mSubscriptionData.slotId
                + " appid : " + mSubscriptionData.m3gpp2Index
                + " subId : " + mSubscriptionData.subId
                + " subStatus : " + mSubscriptionData.subStatus);

        // Make sure properties are set for proper subscription.
        setProperties();

        onUpdateIccAvailability();
        mSST.sendMessage(mSST.obtainMessage(ServiceStateTracker.EVENT_ICC_CHANGED));
        ((CdmaLteServiceStateTracker)mSST).updateCdmaSubscription();
        ((DcTracker)mDcTracker).updateRecords();
    }

    private void onSubscriptionDeactivated() {
        log("SUBSCRIPTION DEACTIVATED");
        // resetSubSpecifics
        mSubscriptionData = null;
    }

    // Set the properties per subscription
    private void setProperties() {
        //Change the system property
        setSystemProperty(TelephonyProperties.CURRENT_ACTIVE_PHONE,
                new Integer(PhoneConstants.PHONE_TYPE_CDMA).toString());
        // Sets operator alpha property by retrieving from build-time system property
        String operatorAlpha = SystemProperties.get("ro.cdma.home.operator.alpha");
        if (!TextUtils.isEmpty(operatorAlpha)) {
            setSystemProperty(PROPERTY_ICC_OPERATOR_ALPHA, operatorAlpha);
        }

        // Sets operator numeric property by retrieving from build-time system property
        String operatorNumeric = SystemProperties.get(PROPERTY_CDMA_HOME_OPERATOR_NUMERIC);
        log("update icc_operator_numeric=" + operatorNumeric);
        if (!TextUtils.isEmpty(operatorNumeric)) {
            setSystemProperty(PROPERTY_ICC_OPERATOR_NUMERIC, operatorNumeric);

            SubscriptionController.getInstance().setMccMnc(operatorNumeric, getSubId());
            // Sets iso country property by retrieving from build-time system property
            setIsoCountryProperty(operatorNumeric);
            // Updates MCC MNC device configuration information
            log("update mccmnc=" + operatorNumeric);
            MccTable.updateMccMncConfiguration(mContext, operatorNumeric, false);
        }
        // Sets current entry in the telephony carrier table
        updateCurrentCarrierInProvider();
    }

    @Override
    public void setSystemProperty(String property, String value) {
        if(getUnitTestMode()) {
            return;
        }
        TelephonyManager.setTelephonyProperty(property, getSubId(), value);
    }

    public String getSystemProperty(String property, String defValue) {
        if(getUnitTestMode()) {
            return null;
        }
        return TelephonyManager.getTelephonyProperty(property, getSubId(), defValue);
    }

    public void updateDataConnectionTracker() {
        ((DcTracker)mDcTracker).update();
    }

    public void setInternalDataEnabled(boolean enable, Message onCompleteMsg) {
        ((DcTracker)mDcTracker)
                .setInternalDataEnabled(enable, onCompleteMsg);
    }

    public boolean setInternalDataEnabledFlag(boolean enable) {
       return ((DcTracker)mDcTracker)
                .setInternalDataEnabledFlag(enable);
    }

    /**
     * @return operator numeric.
     */
    public String getOperatorNumeric() {
        String operatorNumeric = null;
        IccRecords curIccRecords = null;
        if (mCdmaSubscriptionSource == CDMA_SUBSCRIPTION_NV) {
            operatorNumeric = SystemProperties.get("ro.cdma.home.operator.numeric");
        } else if (mCdmaSubscriptionSource == CDMA_SUBSCRIPTION_RUIM_SIM) {
            curIccRecords = mSimRecords;
            if (curIccRecords != null) {
                operatorNumeric = curIccRecords.getOperatorNumeric();
            } else {
                curIccRecords = mIccRecords.get();
                if (curIccRecords != null && (curIccRecords instanceof RuimRecords)) {
                    RuimRecords csim = (RuimRecords) curIccRecords;
                    operatorNumeric = csim.getRUIMOperatorNumeric();
                }
            }
        }
        if (operatorNumeric == null) {
            Rlog.e(LOG_TAG, "getOperatorNumeric: Cannot retrieve operatorNumeric:"
                    + " mCdmaSubscriptionSource = " + mCdmaSubscriptionSource + " mIccRecords = "
                    + ((curIccRecords != null) ? curIccRecords.getRecordsLoaded() : null));
        }

        Rlog.d(LOG_TAG, "getOperatorNumeric: mCdmaSubscriptionSource = " + mCdmaSubscriptionSource
                + " operatorNumeric = " + operatorNumeric);

        return operatorNumeric;
    }
    public void registerForAllDataDisconnected(Handler h, int what, Object obj) {
        ((DcTracker)mDcTracker)
               .registerForAllDataDisconnected(h, what, obj);
    }

    public void unregisterForAllDataDisconnected(Handler h) {
        ((DcTracker)mDcTracker)
                .unregisterForAllDataDisconnected(h);
    }

    @Override
    protected void log(String s) {
            Rlog.d(LOG_LTE_TAG, s);
    }

    protected void loge(String s) {
            Rlog.e(LOG_LTE_TAG, s);
    }

    protected void loge(String s, Throwable e) {
        Rlog.e(LOG_LTE_TAG, s, e);
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("CDMALTEPhone extends:");
        super.dump(fd, pw, args);
    }
}
