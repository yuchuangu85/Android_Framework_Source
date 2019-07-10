/*
 * Copyright (C) 2008 The Android Open Source Project
 * Copyright (c) 2013, The Linux Foundation. All rights reserved.
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


import android.app.ActivityManagerNative;
import android.content.Context;
import android.content.Intent;
import android.net.LinkProperties;
import android.net.NetworkCapabilities;
import android.os.AsyncResult;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.PersistableBundle;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.telephony.CarrierConfigManager;
import android.telephony.CellInfo;
import android.telephony.CellLocation;
import android.telephony.Rlog;
import android.telephony.ServiceState;
import android.telephony.SignalStrength;
import android.telephony.SubscriptionManager;

import com.android.internal.telephony.cdma.CDMAPhone;
import com.android.internal.telephony.gsm.GSMPhone;
import com.android.internal.telephony.imsphone.ImsPhone;
import com.android.internal.telephony.test.SimulatedRadioControl;
import com.android.internal.telephony.cdma.CDMALTEPhone;
import com.android.internal.telephony.uicc.IccCardProxy;
import com.android.internal.telephony.uicc.IccFileHandler;
import com.android.internal.telephony.uicc.IsimRecords;
import com.android.internal.telephony.uicc.UiccCard;
import com.android.internal.telephony.uicc.UsimServiceTable;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.List;
import java.util.Locale;

import com.android.internal.telephony.dataconnection.DctController;

public class PhoneProxy extends Handler implements Phone {
    public final static Object lockForRadioTechnologyChange = new Object();

    private Phone mActivePhone;
    private CommandsInterface mCommandsInterface;
    private IccSmsInterfaceManager mIccSmsInterfaceManager;
    private IccPhoneBookInterfaceManagerProxy mIccPhoneBookInterfaceManagerProxy;
    private PhoneSubInfoProxy mPhoneSubInfoProxy;
    private IccCardProxy mIccCardProxy;

    private boolean mResetModemOnRadioTechnologyChange = false;

    private int mRilVersion;

    private static final int EVENT_VOICE_RADIO_TECH_CHANGED = 1;
    private static final int EVENT_RADIO_ON = 2;
    private static final int EVENT_REQUEST_VOICE_RADIO_TECH_DONE = 3;
    private static final int EVENT_RIL_CONNECTED = 4;
    private static final int EVENT_UPDATE_PHONE_OBJECT = 5;
    private static final int EVENT_SIM_RECORDS_LOADED = 6;

    private int mPhoneId = 0;

    private static final String LOG_TAG = "PhoneProxy";

    //***** Class Methods
    public PhoneProxy(PhoneBase phone) {
        mActivePhone = phone;
        mResetModemOnRadioTechnologyChange = SystemProperties.getBoolean(
                TelephonyProperties.PROPERTY_RESET_ON_RADIO_TECH_CHANGE, false);
        mIccPhoneBookInterfaceManagerProxy = new IccPhoneBookInterfaceManagerProxy(
                phone.getIccPhoneBookInterfaceManager());
        mPhoneSubInfoProxy = new PhoneSubInfoProxy(phone.getPhoneSubInfo());
        mCommandsInterface = ((PhoneBase)mActivePhone).mCi;

        mCommandsInterface.registerForRilConnected(this, EVENT_RIL_CONNECTED, null);
        mCommandsInterface.registerForOn(this, EVENT_RADIO_ON, null);
        mCommandsInterface.registerForVoiceRadioTechChanged(
                             this, EVENT_VOICE_RADIO_TECH_CHANGED, null);
        mPhoneId = phone.getPhoneId();
        mIccSmsInterfaceManager =
                new IccSmsInterfaceManager((PhoneBase)this.mActivePhone);
        mIccCardProxy = new IccCardProxy(mActivePhone.getContext(), mCommandsInterface, mActivePhone.getPhoneId());

        if (phone.getPhoneType() == PhoneConstants.PHONE_TYPE_GSM) {
            // For the purpose of IccCardProxy we only care about the technology family
            mIccCardProxy.setVoiceRadioTech(ServiceState.RIL_RADIO_TECHNOLOGY_UMTS);
        } else if (phone.getPhoneType() == PhoneConstants.PHONE_TYPE_CDMA) {
            mIccCardProxy.setVoiceRadioTech(ServiceState.RIL_RADIO_TECHNOLOGY_1xRTT);
        }
    }

    @Override
    public void handleMessage(Message msg) {
        AsyncResult ar = (AsyncResult) msg.obj;
        switch(msg.what) {
        case EVENT_RADIO_ON:
            /* Proactively query voice radio technologies */
            mCommandsInterface.getVoiceRadioTechnology(
                    obtainMessage(EVENT_REQUEST_VOICE_RADIO_TECH_DONE));
            break;

        case EVENT_RIL_CONNECTED:
            if (ar.exception == null && ar.result != null) {
                mRilVersion = (Integer) ar.result;
            } else {
                logd("Unexpected exception on EVENT_RIL_CONNECTED");
                mRilVersion = -1;
            }
            break;

        case EVENT_VOICE_RADIO_TECH_CHANGED:
        case EVENT_REQUEST_VOICE_RADIO_TECH_DONE:
            String what = (msg.what == EVENT_VOICE_RADIO_TECH_CHANGED) ?
                    "EVENT_VOICE_RADIO_TECH_CHANGED" : "EVENT_REQUEST_VOICE_RADIO_TECH_DONE";
            if (ar.exception == null) {
                if ((ar.result != null) && (((int[]) ar.result).length != 0)) {
                    int newVoiceTech = ((int[]) ar.result)[0];
                    logd(what + ": newVoiceTech=" + newVoiceTech);
                    phoneObjectUpdater(newVoiceTech);
                } else {
                    loge(what + ": has no tech!");
                }
            } else {
                loge(what + ": exception=" + ar.exception);
            }
            break;

        case EVENT_UPDATE_PHONE_OBJECT:
            phoneObjectUpdater(msg.arg1);
            break;

        case EVENT_SIM_RECORDS_LOADED:
            // Only check for the voice radio tech if it not going to be updated by the voice
            // registration changes.
            if (!mActivePhone.getContext().getResources().getBoolean(
                    com.android.internal.R.bool.config_switch_phone_on_voice_reg_state_change)) {
                mCommandsInterface.getVoiceRadioTechnology(obtainMessage(
                        EVENT_REQUEST_VOICE_RADIO_TECH_DONE));
            }
            break;

        default:
            loge("Error! This handler was not registered for this message type. Message: "
                    + msg.what);
            break;
        }
        super.handleMessage(msg);
    }

    private static void logd(String msg) {
        Rlog.d(LOG_TAG, "[PhoneProxy] " + msg);
    }

    private void loge(String msg) {
        Rlog.e(LOG_TAG, "[PhoneProxy] " + msg);
    }

    private void phoneObjectUpdater(int newVoiceRadioTech) {
        logd("phoneObjectUpdater: newVoiceRadioTech=" + newVoiceRadioTech);

        if (mActivePhone != null) {
            // Check for a voice over lte replacement
            if ((newVoiceRadioTech == ServiceState.RIL_RADIO_TECHNOLOGY_LTE)
                    || (newVoiceRadioTech == ServiceState.RIL_RADIO_TECHNOLOGY_UNKNOWN)) {
                CarrierConfigManager configMgr = (CarrierConfigManager)
                        mActivePhone.getContext().getSystemService(Context.CARRIER_CONFIG_SERVICE);
                PersistableBundle b = configMgr.getConfigForSubId(mActivePhone.getSubId());
                if (b != null) {
                    int volteReplacementRat =
                            b.getInt(CarrierConfigManager.KEY_VOLTE_REPLACEMENT_RAT_INT);
                    logd("phoneObjectUpdater: volteReplacementRat=" + volteReplacementRat);
                    if (volteReplacementRat != ServiceState.RIL_RADIO_TECHNOLOGY_UNKNOWN) {
                        newVoiceRadioTech = volteReplacementRat;
                    }
                } else {
                    loge("phoneObjectUpdater: didn't get volteReplacementRat from carrier config");
                }
            }

            if(mRilVersion == 6 && getLteOnCdmaMode() == PhoneConstants.LTE_ON_CDMA_TRUE) {
                /*
                 * On v6 RIL, when LTE_ON_CDMA is TRUE, always create CDMALTEPhone
                 * irrespective of the voice radio tech reported.
                 */
                if (mActivePhone.getPhoneType() == PhoneConstants.PHONE_TYPE_CDMA) {
                    logd("phoneObjectUpdater: LTE ON CDMA property is set. Use CDMA Phone" +
                            " newVoiceRadioTech=" + newVoiceRadioTech +
                            " mActivePhone=" + mActivePhone.getPhoneName());
                    return;
                } else {
                    logd("phoneObjectUpdater: LTE ON CDMA property is set. Switch to CDMALTEPhone" +
                            " newVoiceRadioTech=" + newVoiceRadioTech +
                            " mActivePhone=" + mActivePhone.getPhoneName());
                    newVoiceRadioTech = ServiceState.RIL_RADIO_TECHNOLOGY_1xRTT;
                }
            } else {
                boolean matchCdma = ServiceState.isCdma(newVoiceRadioTech);
                boolean matchGsm = ServiceState.isGsm(newVoiceRadioTech);
                if ((matchCdma  &&
                        mActivePhone.getPhoneType() == PhoneConstants.PHONE_TYPE_CDMA) ||
                        (matchGsm &&
                                mActivePhone.getPhoneType() == PhoneConstants.PHONE_TYPE_GSM)) {
                    // Nothing changed. Keep phone as it is.
                    logd("phoneObjectUpdater: No change ignore," +
                            " newVoiceRadioTech=" + newVoiceRadioTech +
                            " mActivePhone=" + mActivePhone.getPhoneName());
                    return;
                }
                if (!matchCdma && !matchGsm) {
                    loge("phoneObjectUpdater: newVoiceRadioTech=" + newVoiceRadioTech +
                        " doesn't match either CDMA or GSM - error! No phone change");
                    return;
                }
            }
        }

        if (newVoiceRadioTech == ServiceState.RIL_RADIO_TECHNOLOGY_UNKNOWN) {
            // We need some voice phone object to be active always, so never
            // delete the phone without anything to replace it with!
            logd("phoneObjectUpdater: Unknown rat ignore, "
                    + " newVoiceRadioTech=Unknown. mActivePhone=" + mActivePhone.getPhoneName());
            return;
        }

        boolean oldPowerState = false; // old power state to off
        if (mResetModemOnRadioTechnologyChange) {
            if (mCommandsInterface.getRadioState().isOn()) {
                oldPowerState = true;
                logd("phoneObjectUpdater: Setting Radio Power to Off");
                mCommandsInterface.setRadioPower(false, null);
            }
        }

        deleteAndCreatePhone(newVoiceRadioTech);

        if (mResetModemOnRadioTechnologyChange && oldPowerState) { // restore power state
            logd("phoneObjectUpdater: Resetting Radio");
            mCommandsInterface.setRadioPower(oldPowerState, null);
        }

        // Set the new interfaces in the proxy's
        mIccSmsInterfaceManager.updatePhoneObject((PhoneBase) mActivePhone);
        mIccPhoneBookInterfaceManagerProxy.setmIccPhoneBookInterfaceManager(mActivePhone
                .getIccPhoneBookInterfaceManager());
        mPhoneSubInfoProxy.setmPhoneSubInfo(mActivePhone.getPhoneSubInfo());

        mCommandsInterface = ((PhoneBase)mActivePhone).mCi;
        mIccCardProxy.setVoiceRadioTech(newVoiceRadioTech);

        // Send an Intent to the PhoneApp that we had a radio technology change
        Intent intent = new Intent(TelephonyIntents.ACTION_RADIO_TECHNOLOGY_CHANGED);
        intent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING);
        intent.putExtra(PhoneConstants.PHONE_NAME_KEY, mActivePhone.getPhoneName());
        SubscriptionManager.putPhoneIdAndSubIdExtra(intent, mPhoneId);
        ActivityManagerNative.broadcastStickyIntent(intent, null, UserHandle.USER_ALL);

        DctController.getInstance().updatePhoneObject(this);

    }

    private void deleteAndCreatePhone(int newVoiceRadioTech) {

        String outgoingPhoneName = "Unknown";
        Phone oldPhone = mActivePhone;
        ImsPhone imsPhone = null;

        if (oldPhone != null) {
            outgoingPhoneName = ((PhoneBase) oldPhone).getPhoneName();
            oldPhone.unregisterForSimRecordsLoaded(this);
        }

        logd("Switching Voice Phone : " + outgoingPhoneName + " >>> "
                + (ServiceState.isGsm(newVoiceRadioTech) ? "GSM" : "CDMA"));

        if (ServiceState.isCdma(newVoiceRadioTech)) {
            mActivePhone = PhoneFactory.getCdmaPhone(mPhoneId);
        } else if (ServiceState.isGsm(newVoiceRadioTech)) {
            mActivePhone = PhoneFactory.getGsmPhone(mPhoneId);
        } else {
            loge("deleteAndCreatePhone: newVoiceRadioTech=" + newVoiceRadioTech +
                " is not CDMA or GSM (error) - aborting!");
            return;
        }

        if (oldPhone != null) {
            imsPhone = oldPhone.relinquishOwnershipOfImsPhone();
        }

        if(mActivePhone != null) {
            CallManager.getInstance().registerPhone(mActivePhone);
            if (imsPhone != null) {
                mActivePhone.acquireOwnershipOfImsPhone(imsPhone);
            }
            mActivePhone.startMonitoringImsService();
            mActivePhone.registerForSimRecordsLoaded(this, EVENT_SIM_RECORDS_LOADED, null);
        }

        if (oldPhone != null) {
            CallManager.getInstance().unregisterPhone(oldPhone);
            logd("Disposing old phone..");
            oldPhone.dispose();
            // Potential GC issues: however, callers may have references to old
            // phone on which they perform hierarchical funcs: phone.getA().getB()
            // HENCE: do not delete references.
            //oldPhone.removeReferences();
        }
        oldPhone = null;
    }

    public IccSmsInterfaceManager getIccSmsInterfaceManager(){
        return mIccSmsInterfaceManager;
    }

    public PhoneSubInfoProxy getPhoneSubInfoProxy(){
        return mPhoneSubInfoProxy;
    }

    public IccPhoneBookInterfaceManagerProxy getIccPhoneBookInterfaceManagerProxy() {
        return mIccPhoneBookInterfaceManagerProxy;
    }

    public IccFileHandler getIccFileHandler() {
        return ((PhoneBase)mActivePhone).getIccFileHandler();
    }

    @Override
    public boolean isVideoCallPresent() {
        return mActivePhone.isVideoCallPresent();
    }

    @Override
    public void updatePhoneObject(int voiceRadioTech) {
        logd("updatePhoneObject: radioTechnology=" + voiceRadioTech);
        sendMessage(obtainMessage(EVENT_UPDATE_PHONE_OBJECT, voiceRadioTech, 0, null));
    }

    @Override
    public ServiceState getServiceState() {
        return mActivePhone.getServiceState();
    }

    @Override
    public CellLocation getCellLocation() {
        return mActivePhone.getCellLocation();
    }

    /**
     * @return all available cell information or null if none.
     */
    @Override
    public List<CellInfo> getAllCellInfo() {
        return mActivePhone.getAllCellInfo();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setCellInfoListRate(int rateInMillis) {
        mActivePhone.setCellInfoListRate(rateInMillis);
    }

    @Override
    public PhoneConstants.DataState getDataConnectionState() {
        return mActivePhone.getDataConnectionState(PhoneConstants.APN_TYPE_DEFAULT);
    }

    @Override
    public PhoneConstants.DataState getDataConnectionState(String apnType) {
        return mActivePhone.getDataConnectionState(apnType);
    }

    @Override
    public DataActivityState getDataActivityState() {
        return mActivePhone.getDataActivityState();
    }

    @Override
    public Context getContext() {
        return mActivePhone.getContext();
    }

    @Override
    public void disableDnsCheck(boolean b) {
        mActivePhone.disableDnsCheck(b);
    }

    @Override
    public boolean isDnsCheckDisabled() {
        return mActivePhone.isDnsCheckDisabled();
    }

    @Override
    public PhoneConstants.State getState() {
        return mActivePhone.getState();
    }

    @Override
    public String getPhoneName() {
        return mActivePhone.getPhoneName();
    }

    @Override
    public int getPhoneType() {
        return mActivePhone.getPhoneType();
    }

    @Override
    public String[] getActiveApnTypes() {
        return mActivePhone.getActiveApnTypes();
    }

    @Override
    public boolean hasMatchedTetherApnSetting() {
        return mActivePhone.hasMatchedTetherApnSetting();
    }

    @Override
    public String getActiveApnHost(String apnType) {
        return mActivePhone.getActiveApnHost(apnType);
    }

    @Override
    public LinkProperties getLinkProperties(String apnType) {
        return mActivePhone.getLinkProperties(apnType);
    }

    @Override
    public NetworkCapabilities getNetworkCapabilities(String apnType) {
        return mActivePhone.getNetworkCapabilities(apnType);
    }

    @Override
    public SignalStrength getSignalStrength() {
        return mActivePhone.getSignalStrength();
    }

    @Override
    public void registerForUnknownConnection(Handler h, int what, Object obj) {
        mActivePhone.registerForUnknownConnection(h, what, obj);
    }

    @Override
    public void unregisterForUnknownConnection(Handler h) {
        mActivePhone.unregisterForUnknownConnection(h);
    }

    @Override
    public void registerForHandoverStateChanged(Handler h, int what, Object obj) {
        mActivePhone.registerForHandoverStateChanged(h, what, obj);
    }

    @Override
    public void unregisterForHandoverStateChanged(Handler h) {
        mActivePhone.unregisterForHandoverStateChanged(h);
    }

    @Override
    public void registerForPreciseCallStateChanged(Handler h, int what, Object obj) {
        mActivePhone.registerForPreciseCallStateChanged(h, what, obj);
    }

    @Override
    public void unregisterForPreciseCallStateChanged(Handler h) {
        mActivePhone.unregisterForPreciseCallStateChanged(h);
    }

    @Override
    public void registerForNewRingingConnection(Handler h, int what, Object obj) {
        mActivePhone.registerForNewRingingConnection(h, what, obj);
    }

    @Override
    public void unregisterForNewRingingConnection(Handler h) {
        mActivePhone.unregisterForNewRingingConnection(h);
    }

    @Override
    public void registerForVideoCapabilityChanged(
            Handler h, int what, Object obj) {
        mActivePhone.registerForVideoCapabilityChanged(h, what, obj);
    }

    @Override
    public void unregisterForVideoCapabilityChanged(Handler h) {
        mActivePhone.unregisterForVideoCapabilityChanged(h);
    }

    @Override
    public void registerForIncomingRing(Handler h, int what, Object obj) {
        mActivePhone.registerForIncomingRing(h, what, obj);
    }

    @Override
    public void unregisterForIncomingRing(Handler h) {
        mActivePhone.unregisterForIncomingRing(h);
    }

    @Override
    public void registerForDisconnect(Handler h, int what, Object obj) {
        mActivePhone.registerForDisconnect(h, what, obj);
    }

    @Override
    public void unregisterForDisconnect(Handler h) {
        mActivePhone.unregisterForDisconnect(h);
    }

    @Override
    public void registerForMmiInitiate(Handler h, int what, Object obj) {
        mActivePhone.registerForMmiInitiate(h, what, obj);
    }

    @Override
    public void unregisterForMmiInitiate(Handler h) {
        mActivePhone.unregisterForMmiInitiate(h);
    }

    @Override
    public void registerForMmiComplete(Handler h, int what, Object obj) {
        mActivePhone.registerForMmiComplete(h, what, obj);
    }

    @Override
    public void unregisterForMmiComplete(Handler h) {
        mActivePhone.unregisterForMmiComplete(h);
    }

    @Override
    public List<? extends MmiCode> getPendingMmiCodes() {
        return mActivePhone.getPendingMmiCodes();
    }

    @Override
    public void sendUssdResponse(String ussdMessge) {
        mActivePhone.sendUssdResponse(ussdMessge);
    }

    @Override
    public void registerForServiceStateChanged(Handler h, int what, Object obj) {
        mActivePhone.registerForServiceStateChanged(h, what, obj);
    }

    @Override
    public void unregisterForServiceStateChanged(Handler h) {
        mActivePhone.unregisterForServiceStateChanged(h);
    }

    @Override
    public void registerForSuppServiceNotification(Handler h, int what, Object obj) {
        mActivePhone.registerForSuppServiceNotification(h, what, obj);
    }

    @Override
    public void unregisterForSuppServiceNotification(Handler h) {
        mActivePhone.unregisterForSuppServiceNotification(h);
    }

    @Override
    public void registerForSuppServiceFailed(Handler h, int what, Object obj) {
        mActivePhone.registerForSuppServiceFailed(h, what, obj);
    }

    @Override
    public void unregisterForSuppServiceFailed(Handler h) {
        mActivePhone.unregisterForSuppServiceFailed(h);
    }

    @Override
    public void registerForInCallVoicePrivacyOn(Handler h, int what, Object obj){
        mActivePhone.registerForInCallVoicePrivacyOn(h,what,obj);
    }

    @Override
    public void unregisterForInCallVoicePrivacyOn(Handler h){
        mActivePhone.unregisterForInCallVoicePrivacyOn(h);
    }

    @Override
    public void registerForInCallVoicePrivacyOff(Handler h, int what, Object obj){
        mActivePhone.registerForInCallVoicePrivacyOff(h,what,obj);
    }

    @Override
    public void unregisterForInCallVoicePrivacyOff(Handler h){
        mActivePhone.unregisterForInCallVoicePrivacyOff(h);
    }

    @Override
    public void registerForCdmaOtaStatusChange(Handler h, int what, Object obj) {
        mActivePhone.registerForCdmaOtaStatusChange(h,what,obj);
    }

    @Override
    public void unregisterForCdmaOtaStatusChange(Handler h) {
         mActivePhone.unregisterForCdmaOtaStatusChange(h);
    }

    @Override
    public void registerForSubscriptionInfoReady(Handler h, int what, Object obj) {
        mActivePhone.registerForSubscriptionInfoReady(h, what, obj);
    }

    @Override
    public void unregisterForSubscriptionInfoReady(Handler h) {
        mActivePhone.unregisterForSubscriptionInfoReady(h);
    }

    @Override
    public void registerForEcmTimerReset(Handler h, int what, Object obj) {
        mActivePhone.registerForEcmTimerReset(h,what,obj);
    }

    @Override
    public void unregisterForEcmTimerReset(Handler h) {
        mActivePhone.unregisterForEcmTimerReset(h);
    }

    @Override
    public void registerForRingbackTone(Handler h, int what, Object obj) {
        mActivePhone.registerForRingbackTone(h,what,obj);
    }

    @Override
    public void unregisterForRingbackTone(Handler h) {
        mActivePhone.unregisterForRingbackTone(h);
    }

    @Override
    public void registerForOnHoldTone(Handler h, int what, Object obj) {
        mActivePhone.registerForOnHoldTone(h,what,obj);
    }

    @Override
    public void unregisterForOnHoldTone(Handler h) {
        mActivePhone.unregisterForOnHoldTone(h);
    }

    @Override
    public void registerForResendIncallMute(Handler h, int what, Object obj) {
        mActivePhone.registerForResendIncallMute(h,what,obj);
    }

    @Override
    public void unregisterForResendIncallMute(Handler h) {
        mActivePhone.unregisterForResendIncallMute(h);
    }

    @Override
    public void registerForSimRecordsLoaded(Handler h, int what, Object obj) {
        mActivePhone.registerForSimRecordsLoaded(h,what,obj);
    }

    public void unregisterForSimRecordsLoaded(Handler h) {
        mActivePhone.unregisterForSimRecordsLoaded(h);
    }

    @Override
    public void registerForTtyModeReceived(Handler h, int what, Object obj) {
        mActivePhone.registerForTtyModeReceived(h, what, obj);
    }

    @Override
    public void unregisterForTtyModeReceived(Handler h) {
        mActivePhone.unregisterForTtyModeReceived(h);
    }

    @Override
    public boolean getIccRecordsLoaded() {
        return mIccCardProxy.getIccRecordsLoaded();
    }

    @Override
    public IccCard getIccCard() {
        return mIccCardProxy;
    }

    @Override
    public void acceptCall(int videoState) throws CallStateException {
        mActivePhone.acceptCall(videoState);
    }

    @Override
    public void rejectCall() throws CallStateException {
        mActivePhone.rejectCall();
    }

    @Override
    public void switchHoldingAndActive() throws CallStateException {
        mActivePhone.switchHoldingAndActive();
    }

    @Override
    public boolean canConference() {
        return mActivePhone.canConference();
    }

    @Override
    public void conference() throws CallStateException {
        mActivePhone.conference();
    }

    @Override
    public void enableEnhancedVoicePrivacy(boolean enable, Message onComplete) {
        mActivePhone.enableEnhancedVoicePrivacy(enable, onComplete);
    }

    @Override
    public void getEnhancedVoicePrivacy(Message onComplete) {
        mActivePhone.getEnhancedVoicePrivacy(onComplete);
    }

    @Override
    public boolean canTransfer() {
        return mActivePhone.canTransfer();
    }

    @Override
    public void explicitCallTransfer() throws CallStateException {
        mActivePhone.explicitCallTransfer();
    }

    @Override
    public void clearDisconnected() {
        mActivePhone.clearDisconnected();
    }

    @Override
    public Call getForegroundCall() {
        return mActivePhone.getForegroundCall();
    }

    @Override
    public Call getBackgroundCall() {
        return mActivePhone.getBackgroundCall();
    }

    @Override
    public Call getRingingCall() {
        return mActivePhone.getRingingCall();
    }

    @Override
    public Connection dial(String dialString, int videoState) throws CallStateException {
        return mActivePhone.dial(dialString, videoState);
    }

    @Override
    public Connection dial(String dialString, UUSInfo uusInfo, int videoState, Bundle intentExtras)
            throws CallStateException {
        return mActivePhone.dial(dialString, uusInfo, videoState, intentExtras);
    }

    @Override
    public boolean handlePinMmi(String dialString) {
        return mActivePhone.handlePinMmi(dialString);
    }

    @Override
    public boolean handleInCallMmiCommands(String command) throws CallStateException {
        return mActivePhone.handleInCallMmiCommands(command);
    }

    @Override
    public void sendDtmf(char c) {
        mActivePhone.sendDtmf(c);
    }

    @Override
    public void startDtmf(char c) {
        mActivePhone.startDtmf(c);
    }

    @Override
    public void stopDtmf() {
        mActivePhone.stopDtmf();
    }

    @Override
    public void setRadioPower(boolean power) {
        mActivePhone.setRadioPower(power);
    }

    @Override
    public boolean getMessageWaitingIndicator() {
        return mActivePhone.getMessageWaitingIndicator();
    }

    @Override
    public boolean getCallForwardingIndicator() {
        return mActivePhone.getCallForwardingIndicator();
    }

    @Override
    public String getLine1Number() {
        return mActivePhone.getLine1Number();
    }

    @Override
    public String getCdmaMin() {
        return mActivePhone.getCdmaMin();
    }

    @Override
    public boolean isMinInfoReady() {
        return mActivePhone.isMinInfoReady();
    }

    @Override
    public String getCdmaPrlVersion() {
        return mActivePhone.getCdmaPrlVersion();
    }

    @Override
    public String getLine1AlphaTag() {
        return mActivePhone.getLine1AlphaTag();
    }

    @Override
    public boolean setLine1Number(String alphaTag, String number, Message onComplete) {
        return mActivePhone.setLine1Number(alphaTag, number, onComplete);
    }

    @Override
    public String getVoiceMailNumber() {
        return mActivePhone.getVoiceMailNumber();
    }

     /** @hide */
    @Override
    public int getVoiceMessageCount(){
        return mActivePhone.getVoiceMessageCount();
    }

    @Override
    public String getVoiceMailAlphaTag() {
        return mActivePhone.getVoiceMailAlphaTag();
    }

    @Override
    public void setVoiceMailNumber(String alphaTag,String voiceMailNumber,
            Message onComplete) {
        mActivePhone.setVoiceMailNumber(alphaTag, voiceMailNumber, onComplete);
    }

    @Override
    public void getCallForwardingOption(int commandInterfaceCFReason,
            Message onComplete) {
        mActivePhone.getCallForwardingOption(commandInterfaceCFReason,
                onComplete);
    }

    @Override
    public void setCallForwardingOption(int commandInterfaceCFReason,
            int commandInterfaceCFAction, String dialingNumber,
            int timerSeconds, Message onComplete) {
        mActivePhone.setCallForwardingOption(commandInterfaceCFReason,
            commandInterfaceCFAction, dialingNumber, timerSeconds, onComplete);
    }

    @Override
    public void getOutgoingCallerIdDisplay(Message onComplete) {
        mActivePhone.getOutgoingCallerIdDisplay(onComplete);
    }

    @Override
    public void setOutgoingCallerIdDisplay(int commandInterfaceCLIRMode,
            Message onComplete) {
        mActivePhone.setOutgoingCallerIdDisplay(commandInterfaceCLIRMode,
                onComplete);
    }

    @Override
    public void getCallWaiting(Message onComplete) {
        mActivePhone.getCallWaiting(onComplete);
    }

    @Override
    public void setCallWaiting(boolean enable, Message onComplete) {
        mActivePhone.setCallWaiting(enable, onComplete);
    }

    @Override
    public void getAvailableNetworks(Message response) {
        mActivePhone.getAvailableNetworks(response);
    }

    @Override
    public void setNetworkSelectionModeAutomatic(Message response) {
        mActivePhone.setNetworkSelectionModeAutomatic(response);
    }

    @Override
    public void getNetworkSelectionMode(Message response) {
        mActivePhone.getNetworkSelectionMode(response);
    }

    @Override
    public void selectNetworkManually(OperatorInfo network, Message response) {
        mActivePhone.selectNetworkManually(network, response);
    }

    @Override
    public void setPreferredNetworkType(int networkType, Message response) {
        mActivePhone.setPreferredNetworkType(networkType, response);
    }

    @Override
    public void getPreferredNetworkType(Message response) {
        mActivePhone.getPreferredNetworkType(response);
    }

    @Override
    public void getNeighboringCids(Message response) {
        mActivePhone.getNeighboringCids(response);
    }

    @Override
    public void setOnPostDialCharacter(Handler h, int what, Object obj) {
        mActivePhone.setOnPostDialCharacter(h, what, obj);
    }

    @Override
    public void setMute(boolean muted) {
        mActivePhone.setMute(muted);
    }

    @Override
    public boolean getMute() {
        return mActivePhone.getMute();
    }

    @Override
    public void setEchoSuppressionEnabled() {
        mActivePhone.setEchoSuppressionEnabled();
    }

    @Override
    public void invokeOemRilRequestRaw(byte[] data, Message response) {
        mActivePhone.invokeOemRilRequestRaw(data, response);
    }

    @Override
    public void invokeOemRilRequestStrings(String[] strings, Message response) {
        mActivePhone.invokeOemRilRequestStrings(strings, response);
    }

    @Override
    public void getDataCallList(Message response) {
        mActivePhone.getDataCallList(response);
    }

    @Override
    public void updateServiceLocation() {
        mActivePhone.updateServiceLocation();
    }

    @Override
    public void enableLocationUpdates() {
        mActivePhone.enableLocationUpdates();
    }

    @Override
    public void disableLocationUpdates() {
        mActivePhone.disableLocationUpdates();
    }

    @Override
    public void setUnitTestMode(boolean f) {
        mActivePhone.setUnitTestMode(f);
    }

    @Override
    public boolean getUnitTestMode() {
        return mActivePhone.getUnitTestMode();
    }

    @Override
    public void setBandMode(int bandMode, Message response) {
        mActivePhone.setBandMode(bandMode, response);
    }

    @Override
    public void queryAvailableBandMode(Message response) {
        mActivePhone.queryAvailableBandMode(response);
    }

    @Override
    public boolean getDataRoamingEnabled() {
        return mActivePhone.getDataRoamingEnabled();
    }

    @Override
    public void setDataRoamingEnabled(boolean enable) {
        mActivePhone.setDataRoamingEnabled(enable);
    }

    @Override
    public boolean getDataEnabled() {
        return mActivePhone.getDataEnabled();
    }

    @Override
    public void setDataEnabled(boolean enable) {
        mActivePhone.setDataEnabled(enable);
    }

    @Override
    public void queryCdmaRoamingPreference(Message response) {
        mActivePhone.queryCdmaRoamingPreference(response);
    }

    @Override
    public void setCdmaRoamingPreference(int cdmaRoamingType, Message response) {
        mActivePhone.setCdmaRoamingPreference(cdmaRoamingType, response);
    }

    @Override
    public void setCdmaSubscription(int cdmaSubscriptionType, Message response) {
        mActivePhone.setCdmaSubscription(cdmaSubscriptionType, response);
    }

    @Override
    public SimulatedRadioControl getSimulatedRadioControl() {
        return mActivePhone.getSimulatedRadioControl();
    }

    @Override
    public boolean isDataConnectivityPossible() {
        return mActivePhone.isDataConnectivityPossible(PhoneConstants.APN_TYPE_DEFAULT);
    }

    @Override
    public boolean isDataConnectivityPossible(String apnType) {
        return mActivePhone.isDataConnectivityPossible(apnType);
    }

    @Override
    public String getDeviceId() {
        return mActivePhone.getDeviceId();
    }

    @Override
    public String getDeviceSvn() {
        return mActivePhone.getDeviceSvn();
    }

    @Override
    public String getSubscriberId() {
        return mActivePhone.getSubscriberId();
    }

    @Override
    public String getGroupIdLevel1() {
        return mActivePhone.getGroupIdLevel1();
    }

    @Override
    public String getGroupIdLevel2() {
        return mActivePhone.getGroupIdLevel2();
    }

    @Override
    public String getIccSerialNumber() {
        return mActivePhone.getIccSerialNumber();
    }

    @Override
    public String getEsn() {
        return mActivePhone.getEsn();
    }

    @Override
    public String getMeid() {
        return mActivePhone.getMeid();
    }

    @Override
    public String getMsisdn() {
        return mActivePhone.getMsisdn();
    }

    @Override
    public String getImei() {
        return mActivePhone.getImei();
    }

    @Override
    public String getNai() {
        return mActivePhone.getNai();
    }

    @Override
    public PhoneSubInfo getPhoneSubInfo(){
        return mActivePhone.getPhoneSubInfo();
    }

    @Override
    public IccPhoneBookInterfaceManager getIccPhoneBookInterfaceManager(){
        return mActivePhone.getIccPhoneBookInterfaceManager();
    }

    @Override
    public void setUiTTYMode(int uiTtyMode, Message onComplete) {
        mActivePhone.setUiTTYMode(uiTtyMode, onComplete);
    }

    @Override
    public void setTTYMode(int ttyMode, Message onComplete) {
        mActivePhone.setTTYMode(ttyMode, onComplete);
    }

    @Override
    public void queryTTYMode(Message onComplete) {
        mActivePhone.queryTTYMode(onComplete);
    }

    @Override
    public void activateCellBroadcastSms(int activate, Message response) {
        mActivePhone.activateCellBroadcastSms(activate, response);
    }

    @Override
    public void getCellBroadcastSmsConfig(Message response) {
        mActivePhone.getCellBroadcastSmsConfig(response);
    }

    @Override
    public void setCellBroadcastSmsConfig(int[] configValuesArray, Message response) {
        mActivePhone.setCellBroadcastSmsConfig(configValuesArray, response);
    }

    @Override
    public void notifyDataActivity() {
         mActivePhone.notifyDataActivity();
    }

    @Override
    public void getSmscAddress(Message result) {
        mActivePhone.getSmscAddress(result);
    }

    @Override
    public void setSmscAddress(String address, Message result) {
        mActivePhone.setSmscAddress(address, result);
    }

    @Override
    public int getCdmaEriIconIndex() {
        return mActivePhone.getCdmaEriIconIndex();
    }

    @Override
    public String getCdmaEriText() {
        return mActivePhone.getCdmaEriText();
    }

    @Override
    public int getCdmaEriIconMode() {
        return mActivePhone.getCdmaEriIconMode();
    }

    public Phone getActivePhone() {
        return mActivePhone;
    }

    @Override
    public void sendBurstDtmf(String dtmfString, int on, int off, Message onComplete){
        mActivePhone.sendBurstDtmf(dtmfString, on, off, onComplete);
    }

    @Override
    public void exitEmergencyCallbackMode(){
        mActivePhone.exitEmergencyCallbackMode();
    }

    @Override
    public boolean needsOtaServiceProvisioning(){
        return mActivePhone.needsOtaServiceProvisioning();
    }

    @Override
    public boolean isOtaSpNumber(String dialStr){
        return mActivePhone.isOtaSpNumber(dialStr);
    }

    @Override
    public void registerForCallWaiting(Handler h, int what, Object obj){
        mActivePhone.registerForCallWaiting(h,what,obj);
    }

    @Override
    public void unregisterForCallWaiting(Handler h){
        mActivePhone.unregisterForCallWaiting(h);
    }

    @Override
    public void registerForSignalInfo(Handler h, int what, Object obj) {
        mActivePhone.registerForSignalInfo(h,what,obj);
    }

    @Override
    public void unregisterForSignalInfo(Handler h) {
        mActivePhone.unregisterForSignalInfo(h);
    }

    @Override
    public void registerForDisplayInfo(Handler h, int what, Object obj) {
        mActivePhone.registerForDisplayInfo(h,what,obj);
    }

    @Override
    public void unregisterForDisplayInfo(Handler h) {
        mActivePhone.unregisterForDisplayInfo(h);
    }

    @Override
    public void registerForNumberInfo(Handler h, int what, Object obj) {
        mActivePhone.registerForNumberInfo(h, what, obj);
    }

    @Override
    public void unregisterForNumberInfo(Handler h) {
        mActivePhone.unregisterForNumberInfo(h);
    }

    @Override
    public void registerForRedirectedNumberInfo(Handler h, int what, Object obj) {
        mActivePhone.registerForRedirectedNumberInfo(h, what, obj);
    }

    @Override
    public void unregisterForRedirectedNumberInfo(Handler h) {
        mActivePhone.unregisterForRedirectedNumberInfo(h);
    }

    @Override
    public void registerForLineControlInfo(Handler h, int what, Object obj) {
        mActivePhone.registerForLineControlInfo( h, what, obj);
    }

    @Override
    public void unregisterForLineControlInfo(Handler h) {
        mActivePhone.unregisterForLineControlInfo(h);
    }

    @Override
    public void registerFoT53ClirlInfo(Handler h, int what, Object obj) {
        mActivePhone.registerFoT53ClirlInfo(h, what, obj);
    }

    @Override
    public void unregisterForT53ClirInfo(Handler h) {
        mActivePhone.unregisterForT53ClirInfo(h);
    }

    @Override
    public void registerForT53AudioControlInfo(Handler h, int what, Object obj) {
        mActivePhone.registerForT53AudioControlInfo( h, what, obj);
    }

    @Override
    public void unregisterForT53AudioControlInfo(Handler h) {
        mActivePhone.unregisterForT53AudioControlInfo(h);
    }

    public void registerForRadioOffOrNotAvailable(Handler h, int what, Object obj) {
        mActivePhone.registerForRadioOffOrNotAvailable( h, what, obj);
    }

    public void unregisterForRadioOffOrNotAvailable(Handler h) {
        mActivePhone.unregisterForRadioOffOrNotAvailable(h);
    }

    @Override
    public void setOnEcbModeExitResponse(Handler h, int what, Object obj){
        mActivePhone.setOnEcbModeExitResponse(h,what,obj);
    }

    @Override
    public void unsetOnEcbModeExitResponse(Handler h){
        mActivePhone.unsetOnEcbModeExitResponse(h);
    }

    @Override
    public boolean isCspPlmnEnabled() {
        return mActivePhone.isCspPlmnEnabled();
    }

    @Override
    public IsimRecords getIsimRecords() {
        return mActivePhone.getIsimRecords();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getLteOnCdmaMode() {
        return mActivePhone.getLteOnCdmaMode();
    }

    @Override
    public void setVoiceMessageWaiting(int line, int countWaiting) {
        mActivePhone.setVoiceMessageWaiting(line, countWaiting);
    }

    @Override
    public UsimServiceTable getUsimServiceTable() {
        return mActivePhone.getUsimServiceTable();
    }

    @Override
    public UiccCard getUiccCard() {
        return mActivePhone.getUiccCard();
    }

    @Override
    public void nvReadItem(int itemID, Message response) {
        mActivePhone.nvReadItem(itemID, response);
    }

    @Override
    public void nvWriteItem(int itemID, String itemValue, Message response) {
        mActivePhone.nvWriteItem(itemID, itemValue, response);
    }

    @Override
    public void nvWriteCdmaPrl(byte[] preferredRoamingList, Message response) {
        mActivePhone.nvWriteCdmaPrl(preferredRoamingList, response);
    }

    @Override
    public void nvResetConfig(int resetType, Message response) {
        mActivePhone.nvResetConfig(resetType, response);
    }

    @Override
    public void dispose() {
        if (mActivePhone != null) {
            mActivePhone.unregisterForSimRecordsLoaded(this);
        }
        mCommandsInterface.unregisterForOn(this);
        mCommandsInterface.unregisterForVoiceRadioTechChanged(this);
        mCommandsInterface.unregisterForRilConnected(this);
    }

    @Override
    public void removeReferences() {
        mActivePhone = null;
        mCommandsInterface = null;
    }

    public boolean updateCurrentCarrierInProvider() {
        if (mActivePhone instanceof CDMALTEPhone) {
            return ((CDMALTEPhone)mActivePhone).updateCurrentCarrierInProvider();
        } else if (mActivePhone instanceof GSMPhone) {
            return ((GSMPhone)mActivePhone).updateCurrentCarrierInProvider();
        } else {
           loge("Phone object is not MultiSim. This should not hit!!!!");
           return false;
        }
    }

    public void updateDataConnectionTracker() {
        logd("Updating Data Connection Tracker");
        if (mActivePhone instanceof CDMALTEPhone) {
            ((CDMALTEPhone)mActivePhone).updateDataConnectionTracker();
        } else if (mActivePhone instanceof GSMPhone) {
            ((GSMPhone)mActivePhone).updateDataConnectionTracker();
        } else {
           loge("Phone object is not MultiSim. This should not hit!!!!");
        }
    }

    public void setInternalDataEnabled(boolean enable) {
        setInternalDataEnabled(enable, null);
    }

    public boolean setInternalDataEnabledFlag(boolean enable) {
        boolean flag = false;
        if (mActivePhone instanceof CDMALTEPhone) {
            flag = ((CDMALTEPhone)mActivePhone).setInternalDataEnabledFlag(enable);
        } else if (mActivePhone instanceof GSMPhone) {
            flag = ((GSMPhone)mActivePhone).setInternalDataEnabledFlag(enable);
        } else {
           loge("Phone object is not MultiSim. This should not hit!!!!");
        }
        return flag;
    }

    public void setInternalDataEnabled(boolean enable, Message onCompleteMsg) {
        if (mActivePhone instanceof CDMALTEPhone) {
            ((CDMALTEPhone)mActivePhone).setInternalDataEnabled(enable, onCompleteMsg);
        } else if (mActivePhone instanceof GSMPhone) {
            ((GSMPhone)mActivePhone).setInternalDataEnabled(enable, onCompleteMsg);
        } else {
           loge("Phone object is not MultiSim. This should not hit!!!!");
        }
    }

    public void registerForAllDataDisconnected(Handler h, int what, Object obj) {
        if (mActivePhone instanceof CDMALTEPhone) {
            ((CDMALTEPhone)mActivePhone).registerForAllDataDisconnected(h, what, obj);
        } else if (mActivePhone instanceof GSMPhone) {
            ((GSMPhone)mActivePhone).registerForAllDataDisconnected(h, what, obj);
        } else {
           loge("Phone object is not MultiSim. This should not hit!!!!");
        }
    }

    public void unregisterForAllDataDisconnected(Handler h) {
        if (mActivePhone instanceof CDMALTEPhone) {
            ((CDMALTEPhone)mActivePhone).unregisterForAllDataDisconnected(h);
        } else if (mActivePhone instanceof GSMPhone) {
            ((GSMPhone)mActivePhone).unregisterForAllDataDisconnected(h);
        } else {
           loge("Phone object is not MultiSim. This should not hit!!!!");
        }
    }


    public int getSubId() {
        return mActivePhone.getSubId();
    }

    public int getPhoneId() {
        return mActivePhone.getPhoneId();
    }

    @Override
    public String[] getPcscfAddress(String apnType) {
        return mActivePhone.getPcscfAddress(apnType);
    }

    @Override
    public void setImsRegistrationState(boolean registered){
        logd("setImsRegistrationState - registered: " + registered);

        mActivePhone.setImsRegistrationState(registered);

        if ((mActivePhone.getPhoneName()).equals("GSM")) {
            GSMPhone GP = (GSMPhone)mActivePhone;
            GP.getServiceStateTracker().setImsRegistrationState(registered);
        } else if ((mActivePhone.getPhoneName()).equals("CDMA")) {
            CDMAPhone CP = (CDMAPhone)mActivePhone;
            CP.getServiceStateTracker().setImsRegistrationState(registered);
        }
    }

    @Override
    public Phone getImsPhone() {
        return mActivePhone.getImsPhone();
    }

    @Override
    public ImsPhone relinquishOwnershipOfImsPhone() { return null; }

    @Override
    public void startMonitoringImsService() {}

    @Override
    public void acquireOwnershipOfImsPhone(ImsPhone imsPhone) { }

    @Override
    public int getVoicePhoneServiceState() {
        return mActivePhone.getVoicePhoneServiceState();
    }

    @Override
    public boolean setOperatorBrandOverride(String brand) {
        return mActivePhone.setOperatorBrandOverride(brand);
    }

    @Override
    public boolean setRoamingOverride(List<String> gsmRoamingList,
            List<String> gsmNonRoamingList, List<String> cdmaRoamingList,
            List<String> cdmaNonRoamingList) {
        return mActivePhone.setRoamingOverride(gsmRoamingList, gsmNonRoamingList,
                cdmaRoamingList, cdmaNonRoamingList);
    }

    @Override
    public boolean isRadioAvailable() {
        return mCommandsInterface.getRadioState().isAvailable();
    }

    @Override
    public boolean isRadioOn() {
        return mCommandsInterface.getRadioState().isOn();
    }

    @Override
    public void shutdownRadio() {
        mActivePhone.shutdownRadio();
    }

    @Override
    public void setRadioCapability(RadioCapability rc, Message response) {
        mActivePhone.setRadioCapability(rc, response);
    }

    @Override
    public int getRadioAccessFamily() {
        return mActivePhone.getRadioAccessFamily();
    }

    @Override
    public String getModemUuId() {
        return mActivePhone.getModemUuId();
    }

    @Override
    public RadioCapability getRadioCapability() {
        return mActivePhone.getRadioCapability();
    }

    @Override
    public void radioCapabilityUpdated(RadioCapability rc) {
        mActivePhone.radioCapabilityUpdated(rc);
    }

    @Override
    public void registerForRadioCapabilityChanged(Handler h, int what, Object obj) {
        mActivePhone.registerForRadioCapabilityChanged(h, what, obj);
    }

    @Override
    public void unregisterForRadioCapabilityChanged(Handler h) {
        mActivePhone.unregisterForRadioCapabilityChanged(h);
    }

    public IccCardProxy getPhoneIccCardProxy() {
        return mIccCardProxy;
    }

    public boolean isImsRegistered() {
        return mActivePhone.isImsRegistered();
    }

    /**
     * Determines if video calling is enabled for the IMS phone.
     *
     * @return {@code true} if video calling is enabled.
     */
    @Override
    public boolean isVideoEnabled() {
        return mActivePhone.isVideoEnabled();
    }

    /**
     * Returns the status of Link Capacity Estimation (LCE) service.
     */
    @Override
    public int getLceStatus() {
        return mActivePhone.getLceStatus();
    }

    @Override
    public Locale getLocaleFromSimAndCarrierPrefs() {
        return mActivePhone.getLocaleFromSimAndCarrierPrefs();
    }

    @Override
    public void getModemActivityInfo(Message response)  {
        mActivePhone.getModemActivityInfo(response);
    }

    /**
     * @return true if we are in the emergency call back mode. This is a period where
     * the phone should be using as little power as possible and be ready to receive an
     * incoming call from the emergency operator.
     */
    @Override
    public boolean isInEcm() {
        return mActivePhone.isInEcm();
    }

    public boolean isVolteEnabled() {
        return mActivePhone.isVolteEnabled();
    }

    public boolean isWifiCallingEnabled() {
        return mActivePhone.isWifiCallingEnabled();
    }

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        try {
            ((PhoneBase)mActivePhone).dump(fd, pw, args);
        } catch (Exception e) {
            e.printStackTrace();
        }
        pw.flush();
        pw.println("++++++++++++++++++++++++++++++++");

        try {
            mPhoneSubInfoProxy.dump(fd, pw, args);
        } catch (Exception e) {
            e.printStackTrace();
        }
        pw.flush();
        pw.println("++++++++++++++++++++++++++++++++");

        try {
            mIccCardProxy.dump(fd, pw, args);
        } catch (Exception e) {
            e.printStackTrace();
        }
        pw.flush();
        pw.println("++++++++++++++++++++++++++++++++");
    }
}
