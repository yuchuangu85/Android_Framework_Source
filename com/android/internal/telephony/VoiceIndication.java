/*
 * Copyright (C) 2021 The Android Open Source Project
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

import static android.telephony.TelephonyManager.HAL_SERVICE_VOICE;

import static com.android.internal.telephony.RILConstants.RIL_UNSOL_CALL_RING;
import static com.android.internal.telephony.RILConstants.RIL_UNSOL_EMERGENCY_NUMBER_LIST;
import static com.android.internal.telephony.RILConstants.RIL_UNSOL_ENTER_EMERGENCY_CALLBACK_MODE;
import static com.android.internal.telephony.RILConstants.RIL_UNSOL_EXIT_EMERGENCY_CALLBACK_MODE;
import static com.android.internal.telephony.RILConstants.RIL_UNSOL_ON_SS;
import static com.android.internal.telephony.RILConstants.RIL_UNSOL_ON_USSD;
import static com.android.internal.telephony.RILConstants.RIL_UNSOL_RESEND_INCALL_MUTE;
import static com.android.internal.telephony.RILConstants.RIL_UNSOL_RESPONSE_CALL_STATE_CHANGED;
import static com.android.internal.telephony.RILConstants.RIL_UNSOL_RINGBACK_TONE;
import static com.android.internal.telephony.RILConstants.RIL_UNSOL_SRVCC_STATE_NOTIFY;
import static com.android.internal.telephony.RILConstants.RIL_UNSOL_STK_CALL_SETUP;
import static com.android.internal.telephony.RILConstants.RIL_UNSOL_STK_CC_ALPHA_NOTIFY;

import android.hardware.radio.voice.IRadioVoiceIndication;
import android.os.AsyncResult;
import android.telephony.emergency.EmergencyNumber;

import com.android.internal.telephony.gsm.SsData;

import java.util.ArrayList;
import java.util.List;

/**
 * Interface declaring unsolicited radio indications for voice APIs.
 */
public class VoiceIndication extends IRadioVoiceIndication.Stub {
    private final RIL mRil;

    public VoiceIndication(RIL ril) {
        mRil = ril;
    }

    /**
     * Ring indication for an incoming call (eg, RING or CRING event).
     * The rate of these events is controlled by ro.telephony.call_ring.delay and has a default
     * value of 3000 (3 seconds) if absent.
     * @param indicationType Type of radio indication
     * @param isGsm true for GSM & false for CDMA
     * @param record CDMA signal information record
     */
    public void callRing(int indicationType, boolean isGsm,
            android.hardware.radio.voice.CdmaSignalInfoRecord record) {
        mRil.processIndication(HAL_SERVICE_VOICE, indicationType);

        char[] response = null;

        // Ignore record for gsm
        if (!isGsm) {
            // TODO: Clean this up with a parcelable class for better self-documentation
            response = new char[4];
            response[0] = (char) (record.isPresent ? 1 : 0);
            response[1] = (char) record.signalType;
            response[2] = (char) record.alertPitch;
            response[3] = (char) record.signal;
        }

        if (mRil.isLogOrTrace()) mRil.unsljLogRet(RIL_UNSOL_CALL_RING, response);

        if (mRil.mRingRegistrant != null) {
            mRil.mRingRegistrant.notifyRegistrant(new AsyncResult(null, response, null));
        }
    }

    /**
     * Indicates when call state has changed. Redundant or extraneous invocations are tolerated.
     * @param indicationType Type of radio indication
     */
    public void callStateChanged(int indicationType) {
        mRil.processIndication(HAL_SERVICE_VOICE, indicationType);

        if (mRil.isLogOrTrace()) mRil.unsljLog(RIL_UNSOL_RESPONSE_CALL_STATE_CHANGED);

        mRil.mCallStateRegistrants.notifyRegistrants();
    }

    /**
     * Indicates when CDMA radio receives a call waiting indication.
     * @param indicationType Type of radio indication
     * @param callWaitingRecord Cdma CallWaiting information
     */
    public void cdmaCallWaiting(int indicationType,
            android.hardware.radio.voice.CdmaCallWaiting callWaitingRecord) {
    }

    /**
     * Indicates when CDMA radio receives one or more info recs.
     * @param indicationType Type of radio indication
     * @param records New CDMA information
     */
    public void cdmaInfoRec(int indicationType,
            android.hardware.radio.voice.CdmaInformationRecord[] records) {
    }

    /**
     * Indicates when CDMA radio receives an update of the progress of an OTASP/OTAPA call.
     * @param indicationType Type of radio indication
     * @param status CDMA OTA provision status
     */
    public void cdmaOtaProvisionStatus(int indicationType, int status) {
    }


    /**
     * Indicates current emergency number list.
     * @param indicationType Type of radio indication
     * @param emergencyNumberList Current list of emergency numbers known to radio
     */
    public void currentEmergencyNumberList(int indicationType,
            android.hardware.radio.voice.EmergencyNumber[] emergencyNumberList) {
        mRil.processIndication(HAL_SERVICE_VOICE, indicationType);

        List<EmergencyNumber> response = new ArrayList<>(emergencyNumberList.length);
        for (android.hardware.radio.voice.EmergencyNumber enHal : emergencyNumberList) {
            EmergencyNumber emergencyNumber = new EmergencyNumber(enHal.number,
                    MccTable.countryCodeForMcc(enHal.mcc), enHal.mnc, enHal.categories,
                    RILUtils.primitiveArrayToArrayList(enHal.urns), enHal.sources,
                    EmergencyNumber.EMERGENCY_CALL_ROUTING_UNKNOWN);
            response.add(emergencyNumber);
        }

        if (mRil.isLogOrTrace()) mRil.unsljLogRet(RIL_UNSOL_EMERGENCY_NUMBER_LIST, response);

        // Cache emergency number list from last indication.
        mRil.cacheEmergencyNumberListIndication(response);

        // Notify emergency number list from radio to registrants
        mRil.mEmergencyNumberListRegistrants.notifyRegistrants(
                new AsyncResult(null, response, null));
    }

    /**
     * Indicates that the radio system selection module has autonomously entered emergency
     * callback mode.
     * @param indicationType Type of radio indication
     */
    public void enterEmergencyCallbackMode(int indicationType) {
        mRil.processIndication(HAL_SERVICE_VOICE, indicationType);

        if (mRil.isLogOrTrace()) mRil.unsljLog(RIL_UNSOL_ENTER_EMERGENCY_CALLBACK_MODE);

        if (mRil.mEmergencyCallbackModeRegistrant != null) {
            mRil.mEmergencyCallbackModeRegistrant.notifyRegistrant();
        }
    }

    /**
     * Indicates when Emergency Callback Mode ends. Indicates that the radio system selection module
     * has proactively exited emergency callback mode.
     * @param indicationType Type of radio indication
     */
    public void exitEmergencyCallbackMode(int indicationType) {
        mRil.processIndication(HAL_SERVICE_VOICE, indicationType);

        if (mRil.isLogOrTrace()) mRil.unsljLog(RIL_UNSOL_EXIT_EMERGENCY_CALLBACK_MODE);

        mRil.mExitEmergencyCallbackModeRegistrants.notifyRegistrants();
    }

    /**
     * Indicates that network doesn't have in-band information, need to play out-band tone.
     * @param indicationType Type of radio indication
     * @param start true = start play ringback tone, false = stop playing ringback tone
     */
    public void indicateRingbackTone(int indicationType, boolean start) {
        mRil.processIndication(HAL_SERVICE_VOICE, indicationType);

        if (mRil.isLogOrTrace()) mRil.unsljLogvRet(RIL_UNSOL_RINGBACK_TONE, start);

        mRil.mRingbackToneRegistrants.notifyRegistrants(new AsyncResult(null, start, null));
    }

    /**
     * Indicates when Supplementary service(SS) response is received when DIAL/USSD/SS is changed to
     * SS by call control.
     * @param indicationType Type of radio indication
     * @param ss StkCcUnsolSsResult
     */
    public void onSupplementaryServiceIndication(int indicationType,
            android.hardware.radio.voice.StkCcUnsolSsResult ss) {
        mRil.processIndication(HAL_SERVICE_VOICE, indicationType);

        int num;
        SsData ssData = new SsData();

        ssData.serviceType = ssData.ServiceTypeFromRILInt(ss.serviceType);
        ssData.requestType = ssData.RequestTypeFromRILInt(ss.requestType);
        ssData.teleserviceType = ssData.TeleserviceTypeFromRILInt(ss.teleserviceType);
        ssData.serviceClass = ss.serviceClass; // This is service class sent in the SS request.
        ssData.result = ss.result; // This is the result of the SS request.

        if (ssData.serviceType.isTypeCF() && ssData.requestType.isTypeInterrogation()) {
            android.hardware.radio.voice.CfData cfData = ss.cfData[0];
            num = cfData.cfInfo.length;
            ssData.cfInfo = new CallForwardInfo[num];

            for (int i = 0; i < num; i++) {
                android.hardware.radio.voice.CallForwardInfo cfInfo = cfData.cfInfo[i];
                ssData.cfInfo[i] = new CallForwardInfo();
                ssData.cfInfo[i].status = cfInfo.status;
                ssData.cfInfo[i].reason = cfInfo.reason;
                ssData.cfInfo[i].serviceClass = cfInfo.serviceClass;
                ssData.cfInfo[i].toa = cfInfo.toa;
                ssData.cfInfo[i].number = cfInfo.number;
                ssData.cfInfo[i].timeSeconds = cfInfo.timeSeconds;
                mRil.riljLog("[SS Data] CF Info " + i + " : " +  ssData.cfInfo[i]);
            }
        } else {
            android.hardware.radio.voice.SsInfoData ssInfo = ss.ssInfo[0];
            num = ssInfo.ssInfo.length;
            ssData.ssInfo = new int[num];
            for (int i = 0; i < num; i++) {
                ssData.ssInfo[i] = ssInfo.ssInfo[i];
                mRil.riljLog("[SS Data] SS Info " + i + " : " +  ssData.ssInfo[i]);
            }
        }

        if (mRil.isLogOrTrace()) mRil.unsljLogRet(RIL_UNSOL_ON_SS, ssData);

        if (mRil.mSsRegistrant != null) {
            mRil.mSsRegistrant.notifyRegistrant(new AsyncResult(null, ssData, null));
        }
    }

    /**
     * Indicates when a new USSD message is received. The USSD session is assumed to persist if the
     * type code is REQUEST, otherwise the current session (if any) is assumed to have terminated.
     * @param indicationType Type of radio indication
     * @param ussdModeType USSD type code
     * @param msg Message string in UTF-8, if applicable
     */
    public void onUssd(int indicationType, int ussdModeType, String msg) {
        mRil.processIndication(HAL_SERVICE_VOICE, indicationType);

        if (mRil.isLogOrTrace()) mRil.unsljLogMore(RIL_UNSOL_ON_USSD, "" + ussdModeType);

        // TODO: Clean this up with a parcelable class for better self-documentation
        String[] resp = new String[]{"" + ussdModeType, msg};
        if (mRil.mUSSDRegistrant != null) {
            mRil.mUSSDRegistrant.notifyRegistrant(new AsyncResult(null, resp, null));
        }
    }

    /**
     * Indicates that framework/application must reset the uplink mute state.
     * @param indicationType Type of radio indication
     */
    public void resendIncallMute(int indicationType) {
        mRil.processIndication(HAL_SERVICE_VOICE, indicationType);

        if (mRil.isLogOrTrace()) mRil.unsljLog(RIL_UNSOL_RESEND_INCALL_MUTE);

        mRil.mResendIncallMuteRegistrants.notifyRegistrants();
    }

    /**
     * Indicates when Single Radio Voice Call Continuity (SRVCC) progress state has changed.
     * @param indicationType Type of radio indication
     * @param state New SRVCC State
     */
    public void srvccStateNotify(int indicationType, int state) {
        mRil.processIndication(HAL_SERVICE_VOICE, indicationType);

        int[] response = new int[] {state};

        if (mRil.isLogOrTrace()) mRil.unsljLogRet(RIL_UNSOL_SRVCC_STATE_NOTIFY, response);

        mRil.writeMetricsSrvcc(state);
        mRil.mSrvccStateRegistrants.notifyRegistrants(new AsyncResult(null, response, null));
    }

    /**
     * Indicates when there is an ALPHA from UICC during Call Control.
     * @param indicationType Type of radio indication
     * @param alpha ALPHA string from UICC in UTF-8 format
     */
    public void stkCallControlAlphaNotify(int indicationType, String alpha) {
        mRil.processIndication(HAL_SERVICE_VOICE, indicationType);

        if (mRil.isLogOrTrace()) mRil.unsljLogRet(RIL_UNSOL_STK_CC_ALPHA_NOTIFY, alpha);

        if (mRil.mCatCcAlphaRegistrant != null) {
            mRil.mCatCcAlphaRegistrant.notifyRegistrant(new AsyncResult(null, alpha, null));
        }
    }

    /**
     * Indicates when SIM wants application to setup a voice call.
     * @param indicationType Type of radio indication
     * @param timeout Timeout value in milliseconds for setting up voice call
     */
    public void stkCallSetup(int indicationType, long timeout) {
        mRil.processIndication(HAL_SERVICE_VOICE, indicationType);

        if (mRil.isLogOrTrace()) mRil.unsljLogRet(RIL_UNSOL_STK_CALL_SETUP, timeout);

        if (mRil.mCatCallSetUpRegistrant != null) {
            mRil.mCatCallSetUpRegistrant.notifyRegistrant(new AsyncResult(null, timeout, null));
        }
    }

    @Override
    public String getInterfaceHash() {
        return IRadioVoiceIndication.HASH;
    }

    @Override
    public int getInterfaceVersion() {
        return IRadioVoiceIndication.VERSION;
    }
}
