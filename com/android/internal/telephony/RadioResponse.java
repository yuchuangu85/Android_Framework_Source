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

package com.android.internal.telephony;

import android.content.Context;
import android.hardware.radio.V1_0.ActivityStatsInfo;
import android.hardware.radio.V1_0.AppStatus;
import android.hardware.radio.V1_0.CardStatus;
import android.hardware.radio.V1_0.CarrierRestrictions;
import android.hardware.radio.V1_0.CdmaBroadcastSmsConfigInfo;
import android.hardware.radio.V1_0.DataRegStateResult;
import android.hardware.radio.V1_0.GsmBroadcastSmsConfigInfo;
import android.hardware.radio.V1_0.LastCallFailCauseInfo;
import android.hardware.radio.V1_0.LceDataInfo;
import android.hardware.radio.V1_0.LceStatusInfo;
import android.hardware.radio.V1_0.NeighboringCell;
import android.hardware.radio.V1_0.RadioError;
import android.hardware.radio.V1_0.RadioResponseInfo;
import android.hardware.radio.V1_0.SendSmsResult;
import android.hardware.radio.V1_0.SetupDataCallResult;
import android.hardware.radio.V1_0.VoiceRegStateResult;
import android.hardware.radio.V1_1.IRadioResponse;
import android.hardware.radio.V1_1.KeepaliveStatus;
import android.os.AsyncResult;
import android.os.Message;
import android.os.SystemClock;
import android.service.carrier.CarrierIdentifier;
import android.telephony.CellInfo;
import android.telephony.ModemActivityInfo;
import android.telephony.NeighboringCellInfo;
import android.telephony.PhoneNumberUtils;
import android.telephony.SignalStrength;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.text.TextUtils;

import com.android.internal.telephony.dataconnection.DataCallResponse;
import com.android.internal.telephony.gsm.SmsBroadcastConfigInfo;
import com.android.internal.telephony.uicc.IccCardApplicationStatus;
import com.android.internal.telephony.uicc.IccCardStatus;
import com.android.internal.telephony.uicc.IccIoResult;
import com.android.internal.telephony.uicc.IccUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class RadioResponse extends IRadioResponse.Stub {
    // The number of the required config values for broadcast SMS stored in the C struct
    // RIL_CDMA_BroadcastServiceInfo
    private static final int CDMA_BSI_NO_OF_INTS_STRUCT = 3;

    private static final int CDMA_BROADCAST_SMS_NO_OF_SERVICE_CATEGORIES = 31;

    RIL mRil;

    public RadioResponse(RIL ril) {
        mRil = ril;
    }

    /**
     * Helper function to send response msg
     * @param msg Response message to be sent
     * @param ret Return object to be included in the response message
     */
    static void sendMessageResponse(Message msg, Object ret) {
        if (msg != null) {
            AsyncResult.forMessage(msg, ret, null);
            msg.sendToTarget();
        }
    }

    /**
     * Acknowledge the receipt of radio request sent to the vendor. This must be sent only for
     * radio request which take long time to respond.
     * For more details, refer https://source.android.com/devices/tech/connect/ril.html
     *
     * @param serial Serial no. of the request whose acknowledgement is sent.
     */
    public void acknowledgeRequest(int serial) {
        mRil.processRequestAck(serial);
    }

    /**
     * @param responseInfo Response info struct containing response type, serial no. and error
     * @param cardStatus ICC card status as defined by CardStatus in types.hal
     */
    public void getIccCardStatusResponse(RadioResponseInfo responseInfo, CardStatus cardStatus) {
        responseIccCardStatus(responseInfo, cardStatus);
    }

    /**
     * @param responseInfo Response info struct containing response type, serial no. and error
     * @param remainingAttempts Number of retries remaining, must be equal to -1 if unknown.
     */
    public void supplyIccPinForAppResponse(RadioResponseInfo responseInfo, int remainingAttempts) {
        responseInts(responseInfo, remainingAttempts);
    }

    /**
     * @param responseInfo Response info struct containing response type, serial no. and error
     * @param remainingAttempts Number of retries remaining, must be equal to -1 if unknown.
     */
    public void supplyIccPukForAppResponse(RadioResponseInfo responseInfo, int remainingAttempts) {
        responseInts(responseInfo, remainingAttempts);
    }

    /**
     * @param responseInfo Response info struct containing response type, serial no. and error
     * @param remainingAttempts Number of retries remaining, must be equal to -1 if unknown.
     */
    public void supplyIccPin2ForAppResponse(RadioResponseInfo responseInfo, int remainingAttempts) {
        responseInts(responseInfo, remainingAttempts);
    }

    /**
     * @param responseInfo Response info struct containing response type, serial no. and error
     * @param remainingAttempts Number of retries remaining, must be equal to -1 if unknown.
     */
    public void supplyIccPuk2ForAppResponse(RadioResponseInfo responseInfo, int remainingAttempts) {
        responseInts(responseInfo, remainingAttempts);
    }

    /**
     * @param responseInfo Response info struct containing response type, serial no. and error
     * @param remainingAttempts Number of retries remaining, must be equal to -1 if unknown.
     */
    public void changeIccPinForAppResponse(RadioResponseInfo responseInfo, int remainingAttempts) {
        responseInts(responseInfo, remainingAttempts);
    }

    /**
     * @param responseInfo Response info struct containing response type, serial no. and error
     * @param remainingAttempts Number of retries remaining, must be equal to -1 if unknown.
     */
    public void changeIccPin2ForAppResponse(RadioResponseInfo responseInfo, int remainingAttempts) {
        responseInts(responseInfo, remainingAttempts);
    }

    /**
     * @param responseInfo Response info struct containing response type, serial no. and error
     * @param retriesRemaining Number of retries remaining, must be equal to -1 if unknown.
     */
    public void supplyNetworkDepersonalizationResponse(RadioResponseInfo responseInfo,
                                                       int retriesRemaining) {
        responseInts(responseInfo, retriesRemaining);
    }

    /**
     * @param responseInfo Response info struct containing response type, serial no. and error
     * @param calls Current call list
     */
    public void getCurrentCallsResponse(RadioResponseInfo responseInfo,
                                        ArrayList<android.hardware.radio.V1_0.Call> calls) {
        responseCurrentCalls(responseInfo, calls);
    }

    /**
     * @param responseInfo Response info struct containing response type, serial no. and error
     */
    public void dialResponse(RadioResponseInfo responseInfo) {
        responseVoid(responseInfo);
    }

    /**
     * @param responseInfo Response info struct containing response type, serial no. and error
     * @param imsi String containing the IMSI
     */
    public void getIMSIForAppResponse(RadioResponseInfo responseInfo, String imsi) {
        responseString(responseInfo, imsi);
    }

    /**
     * @param responseInfo Response info struct containing response type, serial no. and error
     */
    public void hangupConnectionResponse(RadioResponseInfo responseInfo) {
        responseVoid(responseInfo);
    }

    /**
     * @param responseInfo Response info struct containing response type, serial no. and error
     */
    public void hangupWaitingOrBackgroundResponse(RadioResponseInfo responseInfo) {
        responseVoid(responseInfo);
    }

    /**
     * @param responseInfo Response info struct containing response type, serial no. and error
     */
    public void hangupForegroundResumeBackgroundResponse(RadioResponseInfo responseInfo) {
        responseVoid(responseInfo);
    }

    /**
     * @param responseInfo Response info struct containing response type, serial no. and error
     */
    public void switchWaitingOrHoldingAndActiveResponse(RadioResponseInfo responseInfo) {
        responseVoid(responseInfo);
    }

    /**
     * @param responseInfo Response info struct containing response type, serial no. and error
     */
    public void conferenceResponse(RadioResponseInfo responseInfo) {
        responseVoid(responseInfo);
    }

    /**
     * @param responseInfo Response info struct containing response type, serial no. and error
     */
    public void rejectCallResponse(RadioResponseInfo responseInfo) {
        responseVoid(responseInfo);
    }

    /**
     * @param responseInfo Response info struct containing response type, serial no. and error
     * @param fcInfo Contains LastCallFailCause and vendor cause code. GSM failure reasons
     *        are mapped to cause codes defined in TS 24.008 Annex H where possible. CDMA
     *        failure reasons are derived from the possible call failure scenarios
     *        described in the "CDMA IS-2000 Release A (C.S0005-A v6.0)" standard.
     */
    public void getLastCallFailCauseResponse(RadioResponseInfo responseInfo,
                                             LastCallFailCauseInfo fcInfo) {
        responseLastCallFailCauseInfo(responseInfo, fcInfo);
    }

    public void getSignalStrengthResponse(RadioResponseInfo responseInfo,
                                          android.hardware.radio.V1_0.SignalStrength sigStrength) {
        responseSignalStrength(responseInfo, sigStrength);
    }

    /*
     * @param responseInfo Response info struct containing response type, serial no. and error
     * @param voiceRegResponse Current Voice registration response as defined by VoiceRegStateResult
     *        in types.hal
     */
    public void getVoiceRegistrationStateResponse(RadioResponseInfo responseInfo,
                                                  VoiceRegStateResult voiceRegResponse) {
        RILRequest rr = mRil.processResponse(responseInfo);

        if (rr != null) {
            if (responseInfo.error == RadioError.NONE) {
                sendMessageResponse(rr.mResult, voiceRegResponse);
            }
            mRil.processResponseDone(rr, responseInfo, voiceRegResponse);
        }
    }

    /**
     * @param responseInfo Response info struct containing response type, serial no. and error
     * @param dataRegResponse Current Data registration response as defined by DataRegStateResult in
     *        types.hal
     */
    public void getDataRegistrationStateResponse(RadioResponseInfo responseInfo,
                                                 DataRegStateResult dataRegResponse) {
        RILRequest rr = mRil.processResponse(responseInfo);

        if (rr != null) {
            if (responseInfo.error == RadioError.NONE) {
                sendMessageResponse(rr.mResult, dataRegResponse);
            }
            mRil.processResponseDone(rr, responseInfo, dataRegResponse);
        }
    }

    /**
     * @param responseInfo Response info struct containing response type, serial no. and error
     * @param longName is long alpha ONS or EONS or empty string if unregistered
     * @param shortName is short alpha ONS or EONS or empty string if unregistered
     * @param numeric is 5 or 6 digit numeric code (MCC + MNC) or empty string if unregistered
     */
    public void getOperatorResponse(RadioResponseInfo responseInfo,
                                    String longName,
                                    String shortName,
                                    String numeric) {
        responseStrings(responseInfo, longName, shortName, numeric);
    }

    /**
     * @param responseInfo Response info struct containing response type, serial no. and error
     */
    public void setRadioPowerResponse(RadioResponseInfo responseInfo) {
        responseVoid(responseInfo);
    }

    /**
     * @param responseInfo Response info struct containing response type, serial no. and error
     */
    public void sendDtmfResponse(RadioResponseInfo responseInfo) {
        responseVoid(responseInfo);
    }

    /**
     * @param responseInfo Response info struct containing response type, serial no. and error
     * @param sms Response to sms sent as defined by SendSmsResult in types.hal
     */
    public void sendSmsResponse(RadioResponseInfo responseInfo,
                                SendSmsResult sms) {
        responseSms(responseInfo, sms);
    }

    /**
     * @param responseInfo Response info struct containing response type, serial no. and error
     * @param sms Response to sms sent as defined by SendSmsResult in types.hal
     */
    public void sendSMSExpectMoreResponse(RadioResponseInfo responseInfo,
                                          SendSmsResult sms) {
        responseSms(responseInfo, sms);
    }

    /**
     * @param responseInfo Response info struct containing response type, serial no. and error
     * @param setupDataCallResult Response to data call setup as defined by setupDataCallResult in
     *                            types.hal
     */
    public void setupDataCallResponse(RadioResponseInfo responseInfo,
                                      SetupDataCallResult setupDataCallResult) {
        responseSetupDataCall(responseInfo, setupDataCallResult);
    }

    /**
     * @param responseInfo Response info struct containing response type, serial no. and error
     * @param iccIo ICC io operation response as defined by IccIoResult in types.hal
     */
    public void iccIOForAppResponse(RadioResponseInfo responseInfo,
                            android.hardware.radio.V1_0.IccIoResult iccIo) {
        responseIccIo(responseInfo, iccIo);
    }

    /**
     * @param responseInfo Response info struct containing response type, serial no. and error
     */
    public void sendUssdResponse(RadioResponseInfo responseInfo) {
        responseVoid(responseInfo);
    }

    /**
     * @param responseInfo Response info struct containing response type, serial no. and error
     */
    public void cancelPendingUssdResponse(RadioResponseInfo responseInfo) {
        responseVoid(responseInfo);
    }

    /**
     * @param responseInfo Response info struct containing response type, serial no. and error
     * @param n is "n" parameter from TS 27.007 7.7
     * @param m is "m" parameter from TS 27.007 7.7
     */
    public void getClirResponse(RadioResponseInfo responseInfo, int n, int m) {
        responseInts(responseInfo, n, m);
    }

    /**
     * @param responseInfo Response info struct containing response type, serial no. and error
     */
    public void setClirResponse(RadioResponseInfo responseInfo) {
        responseVoid(responseInfo);
    }

    /**
     * @param responseInfo Response info struct containing response type, serial no. and error
     * @param callForwardInfos points to a vector of CallForwardInfo, one for
     *        each distinct registered phone number.
     */
    public void getCallForwardStatusResponse(RadioResponseInfo responseInfo,
                                             ArrayList<android.hardware.radio.V1_0.CallForwardInfo>
                                                     callForwardInfos) {
        responseCallForwardInfo(responseInfo, callForwardInfos);
    }

    /**
     * @param responseInfo Response info struct containing response type, serial no. and error
     */
    public void setCallForwardResponse(RadioResponseInfo responseInfo) {
        responseVoid(responseInfo);
    }

    /**
     * @param responseInfo Response info struct containing response type, serial no. and error
     * @param enable If current call waiting state is disabled, enable = false else true
     * @param serviceClass If enable, then callWaitingResp[1]
     *        must follow, with the TS 27.007 service class bit vector of services
     *        for which call waiting is enabled.
     *        For example, if callWaitingResp[0] is 1 and
     *        callWaitingResp[1] is 3, then call waiting is enabled for data
     *        and voice and disabled for everything else.
     */
    public void getCallWaitingResponse(RadioResponseInfo responseInfo,
                                   boolean enable,
                                   int serviceClass) {
        responseInts(responseInfo, enable ? 1 : 0, serviceClass);
    }

    /**
     * @param responseInfo Response info struct containing response type, serial no. and error
     */
    public void setCallWaitingResponse(RadioResponseInfo responseInfo) {
        responseVoid(responseInfo);
    }

    /**
     * @param responseInfo Response info struct containing response type, serial no. and error
     */
    public void acknowledgeLastIncomingGsmSmsResponse(RadioResponseInfo responseInfo) {
        responseVoid(responseInfo);
    }

    /**
     * @param responseInfo Response info struct containing response type, serial no. and error
     */
    public void acceptCallResponse(RadioResponseInfo responseInfo) {
        responseVoid(responseInfo);
    }

    /**
     * @param responseInfo Response info struct containing response type, serial no. and error
     */
    public void deactivateDataCallResponse(RadioResponseInfo responseInfo) {
        responseVoid(responseInfo);
    }

    /**
     *
     * @param responseInfo Response info struct containing response type, serial no. and error
     * @param response 0 is the TS 27.007 service class bit vector of
     *        services for which the specified barring facility
     *        is active. "0" means "disabled for all"
     */
    public void getFacilityLockForAppResponse(RadioResponseInfo responseInfo, int response) {
        responseInts(responseInfo, response);
    }

    /**
     *
     * @param responseInfo Response info struct containing response type, serial no. and error
     * @param retry 0 is the number of retries remaining, or -1 if unknown
     */
    public void setFacilityLockForAppResponse(RadioResponseInfo responseInfo, int retry) {
        responseInts(responseInfo, retry);
    }

    /**
     * @param responseInfo Response info struct containing response type, serial no. and error
     */
    public void setBarringPasswordResponse(RadioResponseInfo responseInfo) {
        responseVoid(responseInfo);
    }

    /**
     *
     * @param responseInfo Response info struct containing response type, serial no. and error
     * @param selection false for automatic selection, true for manual selection
     */
    public void getNetworkSelectionModeResponse(RadioResponseInfo responseInfo, boolean selection) {
        responseInts(responseInfo, selection ? 1 : 0);
    }

    /**
     * @param responseInfo Response info struct containing response type, serial no. and error
     */
    public void setNetworkSelectionModeAutomaticResponse(RadioResponseInfo responseInfo) {
        responseVoid(responseInfo);
    }

    /**
     * @param responseInfo Response info struct containing response type, serial no. and error
     */
    public void setNetworkSelectionModeManualResponse(RadioResponseInfo responseInfo) {
        responseVoid(responseInfo);
    }

    /**
     *
     * @param responseInfo Response info struct containing response type, serial no. and error
     * @param networkInfos List of network operator information as OperatorInfos defined in
     *                     types.hal
     */
    public void getAvailableNetworksResponse(RadioResponseInfo responseInfo,
                                             ArrayList<android.hardware.radio.V1_0.OperatorInfo>
                                                     networkInfos) {
        responseOperatorInfos(responseInfo, networkInfos);
    }

    /**
     *
     * @param responseInfo Response info struct containing response type, serial no. and error
     */
    public void startNetworkScanResponse(RadioResponseInfo responseInfo) {
        responseScanStatus(responseInfo);
    }

    /**
     *
     * @param responseInfo Response info struct containing response type, serial no. and error
     */
    public void stopNetworkScanResponse(RadioResponseInfo responseInfo) {
        responseScanStatus(responseInfo);
    }

    /**
     * @param responseInfo Response info struct containing response type, serial no. and error
     */
    public void startDtmfResponse(RadioResponseInfo responseInfo) {
        responseVoid(responseInfo);
    }

    /**
     * @param responseInfo Response info struct containing response type, serial no. and error
     */
    public void stopDtmfResponse(RadioResponseInfo responseInfo) {
        responseVoid(responseInfo);
    }

    /**
     *
     * @param responseInfo Response info struct containing response type, serial no. and error
     * @param version string containing version string for log reporting
     */
    public void getBasebandVersionResponse(RadioResponseInfo responseInfo, String version) {
        responseString(responseInfo, version);
    }

    /**
     * @param responseInfo Response info struct containing response type, serial no. and error
     */
    public void separateConnectionResponse(RadioResponseInfo responseInfo) {
        responseVoid(responseInfo);
    }

    /**
     * @param responseInfo Response info struct containing response type, serial no. and error
     */
    public void setMuteResponse(RadioResponseInfo responseInfo) {
        responseVoid(responseInfo);
    }

    /**
     *
     * @param responseInfo Response info struct containing response type, serial no. and error
     * @param enable true for "mute enabled" and false for "mute disabled"
     */
    public void getMuteResponse(RadioResponseInfo responseInfo, boolean enable) {
        responseInts(responseInfo, enable ? 1 : 0);
    }

    /**
     *
     * @param responseInfo Response info struct containing response type, serial no. and error
     * @param status indicates CLIP status
     */
    public void getClipResponse(RadioResponseInfo responseInfo, int status) {
        responseInts(responseInfo, status);
    }

    /**
     * @param responseInfo Response info struct containing response type, serial no. and error
     * @param dataCallResultList Response to get data call list as defined by setupDataCallResult in
     *                           types.hal
     */
    public void getDataCallListResponse(RadioResponseInfo responseInfo,
                                        ArrayList<SetupDataCallResult> dataCallResultList) {
        responseDataCallList(responseInfo, dataCallResultList);
    }

    public void sendOemRilRequestRawResponse(RadioResponseInfo responseInfo,
                                             ArrayList<Byte> var2) {}

    /**
     * @param responseInfo Response info struct containing response type, serial no. and error
     */
    public void setSuppServiceNotificationsResponse(RadioResponseInfo responseInfo) {
        responseVoid(responseInfo);
    }

    /**
     * @param responseInfo Response info struct containing response type, serial no. and error
     * @param index record index where the message is stored
     */
    public void writeSmsToSimResponse(RadioResponseInfo responseInfo, int index) {
        responseInts(responseInfo, index);
    }

    /**
     * @param responseInfo Response info struct containing response type, serial no. and error
     */
    public void deleteSmsOnSimResponse(RadioResponseInfo responseInfo) {
        responseVoid(responseInfo);
    }

    /**
     * @param responseInfo Response info struct containing response type, serial no. and error
     */
    public void setBandModeResponse(RadioResponseInfo responseInfo) {
        responseVoid(responseInfo);
    }

    /**
     *
     * @param responseInfo Response info struct containing response type, serial no. and error
     * @param bandModes List of RadioBandMode listing supported modes
     */
    public void getAvailableBandModesResponse(RadioResponseInfo responseInfo,
                                              ArrayList<Integer> bandModes) {
        responseIntArrayList(responseInfo, bandModes);
    }

    /**
     *
     * @param responseInfo Response info struct containing response type, serial no. and error
     * @param commandResponse SAT/USAT response in hexadecimal format
     *        string starting with first byte of response
     */
    public void sendEnvelopeResponse(RadioResponseInfo responseInfo, String commandResponse) {
        responseString(responseInfo, commandResponse);
    }

    /**
     * @param responseInfo Response info struct containing response type, serial no. and error
     */
    public void sendTerminalResponseToSimResponse(RadioResponseInfo responseInfo) {
        responseVoid(responseInfo);
    }

    /**
     * @param responseInfo Response info struct containing response type, serial no. and error
     */
    public void handleStkCallSetupRequestFromSimResponse(RadioResponseInfo responseInfo) {
        responseVoid(responseInfo);
    }

    /**
     * @param responseInfo Response info struct containing response type, serial no. and error
     */
    public void explicitCallTransferResponse(RadioResponseInfo responseInfo) {
        responseVoid(responseInfo);
    }

    /**
     * @param responseInfo Response info struct containing response type, serial no. and error
     */
    public void setPreferredNetworkTypeResponse(RadioResponseInfo responseInfo) {
        responseVoid(responseInfo);
    }

    /**
     *
     * @param responseInfo Response info struct containing response type, serial no. and error
     * @param nwType RadioPreferredNetworkType defined in types.hal
     */
    public void getPreferredNetworkTypeResponse(RadioResponseInfo responseInfo, int nwType) {
        mRil.mPreferredNetworkType = nwType;
        responseInts(responseInfo, nwType);
    }

    /**
     *
     * @param responseInfo Response info struct containing response type, serial no. and error
     * @param cells Vector of neighboring radio cell information
     */
    public void getNeighboringCidsResponse(RadioResponseInfo responseInfo,
                                           ArrayList<NeighboringCell> cells) {
        responseCellList(responseInfo, cells);
    }

    /**
     * @param responseInfo Response info struct containing response type, serial no. and error
     */
    public void setLocationUpdatesResponse(RadioResponseInfo responseInfo) {
        responseVoid(responseInfo);
    }

    /**
     * @param responseInfo Response info struct containing response type, serial no. and error
     */
    public void setCdmaSubscriptionSourceResponse(RadioResponseInfo responseInfo) {
        responseVoid(responseInfo);
    }

    /**
     * @param responseInfo Response info struct containing response type, serial no. and error
     */
    public void setCdmaRoamingPreferenceResponse(RadioResponseInfo responseInfo) {
        responseVoid(responseInfo);
    }

    /**
     *
     * @param responseInfo Response info struct containing response type, serial no. and error
     * @param type CdmaRoamingType defined in types.hal
     */
    public void getCdmaRoamingPreferenceResponse(RadioResponseInfo responseInfo, int type) {
        responseInts(responseInfo, type);
    }

    /**
     * @param responseInfo Response info struct containing response type, serial no. and error
     */
    public void setTTYModeResponse(RadioResponseInfo responseInfo) {
        responseVoid(responseInfo);
    }

    /**
     *
     * @param responseInfo Response info struct containing response type, serial no. and error
     * @param mode TTY mode
     */
    public void getTTYModeResponse(RadioResponseInfo responseInfo, int mode) {
        responseInts(responseInfo, mode);
    }

    /**
     * @param responseInfo Response info struct containing response type, serial no. and error
     */
    public void setPreferredVoicePrivacyResponse(RadioResponseInfo responseInfo) {
        responseVoid(responseInfo);
    }

    /**
     *
     * @param responseInfo Response info struct containing response type, serial no. and error
     * @param enable false for Standard Privacy Mode (Public Long Code Mask)
     *        true for Enhanced Privacy Mode (Private Long Code Mask)
     */
    public void getPreferredVoicePrivacyResponse(RadioResponseInfo responseInfo,
                                                 boolean enable) {
        responseInts(responseInfo, enable ? 1 : 0);
    }

    /**
     * @param responseInfo Response info struct containing response type, serial no. and error
     */
    public void sendCDMAFeatureCodeResponse(RadioResponseInfo responseInfo) {
        responseVoid(responseInfo);
    }

    /**
     * @param responseInfo Response info struct containing response type, serial no. and error
     */
    public void sendBurstDtmfResponse(RadioResponseInfo responseInfo) {
        responseVoid(responseInfo);
    }

    /**
     *
     * @param responseInfo Response info struct containing response type, serial no. and error
     * @param sms Sms result struct as defined by SendSmsResult in types.hal
     */
    public void sendCdmaSmsResponse(RadioResponseInfo responseInfo, SendSmsResult sms) {
        responseSms(responseInfo, sms);
    }

    /**
     * @param responseInfo Response info struct containing response type, serial no. and error
     */
    public void acknowledgeLastIncomingCdmaSmsResponse(RadioResponseInfo responseInfo) {
        responseVoid(responseInfo);
    }

    /**
     *
     * @param responseInfo Response info struct containing response type, serial no. and error
     * @param configs Vector of GSM/WCDMA Cell broadcast configs
     */
    public void getGsmBroadcastConfigResponse(RadioResponseInfo responseInfo,
                                              ArrayList<GsmBroadcastSmsConfigInfo> configs) {
        responseGmsBroadcastConfig(responseInfo, configs);
    }

    /**
     * @param responseInfo Response info struct containing response type, serial no. and error
     */
    public void setGsmBroadcastConfigResponse(RadioResponseInfo responseInfo) {
        responseVoid(responseInfo);
    }

    /**
     * @param responseInfo Response info struct containing response type, serial no. and error
     */
    public void setGsmBroadcastActivationResponse(RadioResponseInfo responseInfo) {
        responseVoid(responseInfo);
    }

    /**
     *
     * @param responseInfo Response info struct containing response type, serial no. and error
     * @param configs Vector of CDMA Broadcast SMS configs.
     */
    public void getCdmaBroadcastConfigResponse(RadioResponseInfo responseInfo,
                                               ArrayList<CdmaBroadcastSmsConfigInfo> configs) {
        responseCdmaBroadcastConfig(responseInfo, configs);
    }

    /**
     * @param responseInfo Response info struct containing response type, serial no. and error
     */
    public void setCdmaBroadcastConfigResponse(RadioResponseInfo responseInfo) {
        responseVoid(responseInfo);
    }

    /**
     * @param responseInfo Response info struct containing response type, serial no. and error
     */
    public void setCdmaBroadcastActivationResponse(RadioResponseInfo responseInfo) {
        responseVoid(responseInfo);
    }

    /**
     *
     * @param responseInfo Response info struct containing response type, serial no. and error
     * @param mdn MDN if CDMA subscription is available
     * @param hSid is a comma separated list of H_SID (Home SID) if
     *        CDMA subscription is available, in decimal format
     * @param hNid is a comma separated list of H_NID (Home NID) if
     *        CDMA subscription is available, in decimal format
     * @param min MIN (10 digits, MIN2+MIN1) if CDMA subscription is available
     * @param prl PRL version if CDMA subscription is available
     */
    public void getCDMASubscriptionResponse(RadioResponseInfo responseInfo, String mdn,
                                            String hSid, String hNid, String min, String prl) {
        responseStrings(responseInfo, mdn, hSid, hNid, min, prl);
    }

    /**
     *
     * @param responseInfo Response info struct containing response type, serial no. and error
     * @param index record index where the cmda sms message is stored
     */
    public void writeSmsToRuimResponse(RadioResponseInfo responseInfo, int index) {
        responseInts(responseInfo, index);
    }

    /**
     * @param responseInfo Response info struct containing response type, serial no. and error
     */
    public void deleteSmsOnRuimResponse(RadioResponseInfo responseInfo) {
        responseVoid(responseInfo);
    }

    /**
     * @param responseInfo Response info struct containing response type, serial no. and error
     * @param imei IMEI if GSM subscription is available
     * @param imeisv IMEISV if GSM subscription is available
     * @param esn ESN if CDMA subscription is available
     * @param meid MEID if CDMA subscription is available
     */
    public void getDeviceIdentityResponse(RadioResponseInfo responseInfo, String imei,
                                          String imeisv, String esn, String meid) {
        responseStrings(responseInfo, imei, imeisv, esn, meid);
    }

    /**
     * @param responseInfo Response info struct containing response type, serial no. and error
     */
    public void exitEmergencyCallbackModeResponse(RadioResponseInfo responseInfo) {
        responseVoid(responseInfo);
    }

    /**
     * @param responseInfo Response info struct containing response type, serial no. and error
     * @param smsc Short Message Service Center address on the device
     */
    public void getSmscAddressResponse(RadioResponseInfo responseInfo, String smsc) {
        responseString(responseInfo, smsc);
    }

    /**
     * @param responseInfo Response info struct containing response type, serial no. and error
     */
    public void setSmscAddressResponse(RadioResponseInfo responseInfo) {
        responseVoid(responseInfo);
    }

    /**
     * @param responseInfo Response info struct containing response type, serial no. and error
     */
    public void reportSmsMemoryStatusResponse(RadioResponseInfo responseInfo) {
        responseVoid(responseInfo);
    }

    /**
     * @param responseInfo Response info struct containing response type, serial no. and error
     */
    public void reportStkServiceIsRunningResponse(RadioResponseInfo responseInfo) {
        responseVoid(responseInfo);
    }

    /**
     *
     * @param responseInfo Response info struct containing response type, serial no. and error
     * @param source CDMA subscription source
     */
    public void getCdmaSubscriptionSourceResponse(RadioResponseInfo responseInfo, int source) {
        responseInts(responseInfo, source);
    }

    /**
     *
     * @param responseInfo Response info struct containing response type, serial no. and error
     * @param response response string of the challenge/response algo for ISIM auth in base64 format
     */
    public void requestIsimAuthenticationResponse(RadioResponseInfo responseInfo, String response) {
        responseString(responseInfo, response);
    }

    /**
     * @param responseInfo Response info struct containing response type, serial no. and error
     */
    public void acknowledgeIncomingGsmSmsWithPduResponse(RadioResponseInfo responseInfo) {
        responseVoid(responseInfo);
    }

    /**
     *
     * @param responseInfo Response info struct containing response type, serial no. and error
     * @param iccIo IccIoResult as defined in types.hal corresponding to ICC IO response
     */
    public void sendEnvelopeWithStatusResponse(RadioResponseInfo responseInfo,
                                               android.hardware.radio.V1_0.IccIoResult iccIo) {
        responseIccIo(responseInfo, iccIo);
    }

    /**
     *
     * @param responseInfo Response info struct containing response type, serial no. and error
     * @param rat Current voice RAT
     */
    public void getVoiceRadioTechnologyResponse(RadioResponseInfo responseInfo, int rat) {
        responseInts(responseInfo, rat);
    }

    public void getCellInfoListResponse(RadioResponseInfo responseInfo,
                                        ArrayList<android.hardware.radio.V1_0.CellInfo> cellInfo) {
        responseCellInfoList(responseInfo, cellInfo);
    }

    /**
     * @param responseInfo Response info struct containing response type, serial no. and error
     */
    public void setCellInfoListRateResponse(RadioResponseInfo responseInfo) {
        responseVoid(responseInfo);
    }

    /**
     * @param responseInfo Response info struct containing response type, serial no. and error
     */
    public void setInitialAttachApnResponse(RadioResponseInfo responseInfo) {
        responseVoid(responseInfo);
    }

    /**
     *
     * @param responseInfo Response info struct containing response type, serial no. and error
     * @param isRegistered false = not registered, true = registered
     * @param ratFamily RadioTechnologyFamily as defined in types.hal. This value is valid only if
     *        isRegistered is true.
     */
    public void getImsRegistrationStateResponse(RadioResponseInfo responseInfo,
                                                boolean isRegistered, int ratFamily) {
        responseInts(responseInfo, isRegistered ? 1 : 0, ratFamily);
    }

    /**
     *
     * @param responseInfo Response info struct containing response type, serial no. and error
     * @param sms Response to sms sent as defined by SendSmsResult in types.hal
     */
    public void sendImsSmsResponse(RadioResponseInfo responseInfo, SendSmsResult sms) {
        responseSms(responseInfo, sms);
    }

    /**
     *
     * @param responseInfo Response info struct containing response type, serial no. and error
     * @param result IccIoResult as defined in types.hal
     */
    public void iccTransmitApduBasicChannelResponse(RadioResponseInfo responseInfo,
                                                    android.hardware.radio.V1_0.IccIoResult
                                                            result) {
        responseIccIo(responseInfo, result);
    }

    /**
     *
     * @param responseInfo Response info struct containing response type, serial no. and error
     * @param channelId session id of the logical channel.
     * @param selectResponse Contains the select response for the open channel command with one
     *        byte per integer
     */
    public void iccOpenLogicalChannelResponse(RadioResponseInfo responseInfo, int channelId,
                                              ArrayList<Byte> selectResponse) {
        ArrayList<Integer> arr = new ArrayList<>();
        arr.add(channelId);
        for (int i = 0; i < selectResponse.size(); i++) {
            arr.add((int) selectResponse.get(i));
        }
        responseIntArrayList(responseInfo, arr);
    }

    /**
     * @param responseInfo Response info struct containing response type, serial no. and error
     */
    public void iccCloseLogicalChannelResponse(RadioResponseInfo responseInfo) {
        responseVoid(responseInfo);
    }

    /**
     *
     * @param responseInfo Response info struct containing response type, serial no. and error
     * @param result IccIoResult as defined in types.hal
     */
    public void iccTransmitApduLogicalChannelResponse(
            RadioResponseInfo responseInfo,
            android.hardware.radio.V1_0.IccIoResult result) {
        responseIccIo(responseInfo, result);
    }

    /**
     *
     * @param responseInfo Response info struct containing response type, serial no. and error
     * @param result string containing the contents of the NV item
     */
    public void nvReadItemResponse(RadioResponseInfo responseInfo, String result) {
        responseString(responseInfo, result);
    }

    /**
     * @param responseInfo Response info struct containing response type, serial no. and error
     */
    public void nvWriteItemResponse(RadioResponseInfo responseInfo) {
        responseVoid(responseInfo);
    }

    /**
     * @param responseInfo Response info struct containing response type, serial no. and error
     */
    public void nvWriteCdmaPrlResponse(RadioResponseInfo responseInfo) {
        responseVoid(responseInfo);
    }

    /**
     * @param responseInfo Response info struct containing response type, serial no. and error
     */
    public void nvResetConfigResponse(RadioResponseInfo responseInfo) {
        responseVoid(responseInfo);
    }

    /**
     * @param responseInfo Response info struct containing response type, serial no. and error
     */
    public void setUiccSubscriptionResponse(RadioResponseInfo responseInfo) {
        responseVoid(responseInfo);
    }

    /**
     * @param responseInfo Response info struct containing response type, serial no. and error
     */
    public void setDataAllowedResponse(RadioResponseInfo responseInfo) {
        responseVoid(responseInfo);
    }

    public void getHardwareConfigResponse(
            RadioResponseInfo responseInfo,
            ArrayList<android.hardware.radio.V1_0.HardwareConfig> config) {
        responseHardwareConfig(responseInfo, config);
    }

    /**
     *
     * @param responseInfo Response info struct containing response type, serial no. and error
     * @param result IccIoResult as defined in types.hal
     */
    public void requestIccSimAuthenticationResponse(RadioResponseInfo responseInfo,
                                                    android.hardware.radio.V1_0.IccIoResult
                                                            result) {
        responseICC_IOBase64(responseInfo, result);
    }

    /**
     * @param responseInfo Response info struct containing response type, serial no. and error
     */
    public void setDataProfileResponse(RadioResponseInfo responseInfo) {
        responseVoid(responseInfo);
    }

    /**
     * @param responseInfo Response info struct containing response type, serial no. and error
     */
    public void requestShutdownResponse(RadioResponseInfo responseInfo) {
        responseVoid(responseInfo);
    }

    public void getRadioCapabilityResponse(RadioResponseInfo responseInfo,
                                           android.hardware.radio.V1_0.RadioCapability rc) {
        RILRequest rr = mRil.processResponse(responseInfo);

        if (rr != null) {
            RadioCapability ret = RIL.convertHalRadioCapability(rc, mRil);
            if (responseInfo.error == RadioError.REQUEST_NOT_SUPPORTED
                    || responseInfo.error == RadioError.GENERIC_FAILURE) {
                // we should construct the RAF bitmask the radio
                // supports based on preferred network bitmasks
                ret = mRil.makeStaticRadioCapability();
                responseInfo.error = RadioError.NONE;
            }
            if (responseInfo.error == RadioError.NONE) {
                sendMessageResponse(rr.mResult, ret);
            }
            mRil.processResponseDone(rr, responseInfo, ret);
        }
    }

    public void setRadioCapabilityResponse(RadioResponseInfo responseInfo,
                                           android.hardware.radio.V1_0.RadioCapability rc) {
        responseRadioCapability(responseInfo, rc);
    }

    /**
     *
     * @param responseInfo Response info struct containing response type, serial no. and error
     * @param statusInfo LceStatusInfo indicating LCE status
     */
    public void startLceServiceResponse(RadioResponseInfo responseInfo, LceStatusInfo statusInfo) {
        responseLceStatus(responseInfo, statusInfo);
    }

    /**
     *
     * @param responseInfo Response info struct containing response type, serial no. and error
     * @param statusInfo LceStatusInfo indicating LCE status
     */
    public void stopLceServiceResponse(RadioResponseInfo responseInfo, LceStatusInfo statusInfo) {
        responseLceStatus(responseInfo, statusInfo);
    }

    public void pullLceDataResponse(RadioResponseInfo responseInfo, LceDataInfo lceInfo) {
        responseLceData(responseInfo, lceInfo);
    }

    /**
     *
     * @param responseInfo Response info struct containing response type, serial no. and error
     * @param activityInfo modem activity information
     */
    public void getModemActivityInfoResponse(RadioResponseInfo responseInfo,
                                             ActivityStatsInfo activityInfo) {
        responseActivityData(responseInfo, activityInfo);
    }

    /**
     *
     * @param responseInfo Response info struct containing response type, serial no. and error
     * @param numAllowed number of allowed carriers which have been set correctly.
     *        On success, it must match the length of list Carriers->allowedCarriers.
     *        if Length of allowed carriers list is 0, numAllowed = 0.
     */
    public void setAllowedCarriersResponse(RadioResponseInfo responseInfo, int numAllowed) {
        responseInts(responseInfo, numAllowed);
    }

    /**
     *
     * @param responseInfo Response info struct containing response type, serial no. and error
     * @param allAllowed true only when all carriers are allowed. Ignore "carriers" struct.
     *                   If false, consider "carriers" struct
     * @param carriers Carrier restriction information.
     */
    public void getAllowedCarriersResponse(RadioResponseInfo responseInfo, boolean allAllowed,
                                           CarrierRestrictions carriers) {
        responseCarrierIdentifiers(responseInfo, allAllowed, carriers);
    }

    /**
     * @param responseInfo Response info struct containing response type, serial no. and error
     */
    public void sendDeviceStateResponse(RadioResponseInfo responseInfo) {
        responseVoid(responseInfo);
    }

    /**
     * @param responseInfo Response info struct containing response type, serial no. and error
     */
    public void setCarrierInfoForImsiEncryptionResponse(RadioResponseInfo responseInfo) {
        responseVoid(responseInfo);
    }

    /**
     * @param responseInfo Response info struct containing response type, serial no. and error
     */
    public void setIndicationFilterResponse(RadioResponseInfo responseInfo) {
        responseVoid(responseInfo);
    }

    /**
     * @param responseInfo Response info struct containing response type, serial no. and error
     */
    public void setSimCardPowerResponse(RadioResponseInfo responseInfo) {
        responseVoid(responseInfo);
    }

    /**
     * @param responseInfo Response info struct containing response type, serial no. and error
     */
    public void setSimCardPowerResponse_1_1(RadioResponseInfo responseInfo) {
        responseVoid(responseInfo);
    }

    /**
     * @param responseInfo Response info struct containing response type, serial no. and error
     * @param keepaliveStatus status of the keepalive with a handle for the session
     */
    public void startKeepaliveResponse(RadioResponseInfo responseInfo,
            KeepaliveStatus keepaliveStatus) {
        throw new UnsupportedOperationException("startKeepaliveResponse not implemented");
    }

    /**
     * @param responseInfo Response info struct containing response type, serial no. and error
     */
    public void stopKeepaliveResponse(RadioResponseInfo responseInfo) {
        throw new UnsupportedOperationException("stopKeepaliveResponse not implemented");
    }

    private void responseIccCardStatus(RadioResponseInfo responseInfo, CardStatus cardStatus) {
        RILRequest rr = mRil.processResponse(responseInfo);

        if (rr != null) {
            IccCardStatus iccCardStatus = new IccCardStatus();
            iccCardStatus.setCardState(cardStatus.cardState);
            iccCardStatus.setUniversalPinState(cardStatus.universalPinState);
            iccCardStatus.mGsmUmtsSubscriptionAppIndex = cardStatus.gsmUmtsSubscriptionAppIndex;
            iccCardStatus.mCdmaSubscriptionAppIndex = cardStatus.cdmaSubscriptionAppIndex;
            iccCardStatus.mImsSubscriptionAppIndex = cardStatus.imsSubscriptionAppIndex;
            int numApplications = cardStatus.applications.size();

            // limit to maximum allowed applications
            if (numApplications
                    > com.android.internal.telephony.uicc.IccCardStatus.CARD_MAX_APPS) {
                numApplications =
                        com.android.internal.telephony.uicc.IccCardStatus.CARD_MAX_APPS;
            }
            iccCardStatus.mApplications = new IccCardApplicationStatus[numApplications];
            for (int i = 0; i < numApplications; i++) {
                AppStatus rilAppStatus = cardStatus.applications.get(i);
                IccCardApplicationStatus appStatus = new IccCardApplicationStatus();
                appStatus.app_type       = appStatus.AppTypeFromRILInt(rilAppStatus.appType);
                appStatus.app_state      = appStatus.AppStateFromRILInt(rilAppStatus.appState);
                appStatus.perso_substate = appStatus.PersoSubstateFromRILInt(
                        rilAppStatus.persoSubstate);
                appStatus.aid            = rilAppStatus.aidPtr;
                appStatus.app_label      = rilAppStatus.appLabelPtr;
                appStatus.pin1_replaced  = rilAppStatus.pin1Replaced;
                appStatus.pin1           = appStatus.PinStateFromRILInt(rilAppStatus.pin1);
                appStatus.pin2           = appStatus.PinStateFromRILInt(rilAppStatus.pin2);
                iccCardStatus.mApplications[i] = appStatus;
            }
            mRil.riljLog("responseIccCardStatus: from HIDL: " + iccCardStatus);
            if (responseInfo.error == RadioError.NONE) {
                sendMessageResponse(rr.mResult, iccCardStatus);
            }
            mRil.processResponseDone(rr, responseInfo, iccCardStatus);
        }
    }

    private void responseInts(RadioResponseInfo responseInfo, int ...var) {
        final ArrayList<Integer> ints = new ArrayList<>();
        for (int i = 0; i < var.length; i++) {
            ints.add(var[i]);
        }
        responseIntArrayList(responseInfo, ints);
    }

    private void responseIntArrayList(RadioResponseInfo responseInfo, ArrayList<Integer> var) {
        RILRequest rr = mRil.processResponse(responseInfo);

        if (rr != null) {
            int[] ret = new int[var.size()];
            for (int i = 0; i < var.size(); i++) {
                ret[i] = var.get(i);
            }
            if (responseInfo.error == RadioError.NONE) {
                sendMessageResponse(rr.mResult, ret);
            }
            mRil.processResponseDone(rr, responseInfo, ret);
        }
    }

    private void responseCurrentCalls(RadioResponseInfo responseInfo,
                                      ArrayList<android.hardware.radio.V1_0.Call> calls) {
        RILRequest rr = mRil.processResponse(responseInfo);

        if (rr != null) {
            int num = calls.size();
            ArrayList<DriverCall> dcCalls = new ArrayList<DriverCall>(num);
            DriverCall dc;

            for (int i = 0; i < num; i++) {
                dc = new DriverCall();
                // TODO: change name of function stateFromCLCC() in DriverCall.java to name
                // clarifying what is CLCC
                dc.state = DriverCall.stateFromCLCC((int) (calls.get(i).state));
                dc.index = calls.get(i).index;
                dc.TOA = calls.get(i).toa;
                dc.isMpty = calls.get(i).isMpty;
                dc.isMT = calls.get(i).isMT;
                dc.als = calls.get(i).als;
                dc.isVoice = calls.get(i).isVoice;
                dc.isVoicePrivacy = calls.get(i).isVoicePrivacy;
                dc.number = calls.get(i).number;
                dc.numberPresentation =
                        DriverCall.presentationFromCLIP(
                                (int) (calls.get(i).numberPresentation));
                dc.name = calls.get(i).name;
                dc.namePresentation =
                        DriverCall.presentationFromCLIP((int) (calls.get(i).namePresentation));
                if (calls.get(i).uusInfo.size() == 1) {
                    dc.uusInfo = new UUSInfo();
                    dc.uusInfo.setType(calls.get(i).uusInfo.get(0).uusType);
                    dc.uusInfo.setDcs(calls.get(i).uusInfo.get(0).uusDcs);
                    if (!TextUtils.isEmpty(calls.get(i).uusInfo.get(0).uusData)) {
                        byte[] userData = calls.get(i).uusInfo.get(0).uusData.getBytes();
                        dc.uusInfo.setUserData(userData);
                    } else {
                        mRil.riljLog("responseCurrentCalls: uusInfo data is null or empty");
                    }

                    mRil.riljLogv(String.format("Incoming UUS : type=%d, dcs=%d, length=%d",
                            dc.uusInfo.getType(), dc.uusInfo.getDcs(),
                            dc.uusInfo.getUserData().length));
                    mRil.riljLogv("Incoming UUS : data (hex): "
                            + IccUtils.bytesToHexString(dc.uusInfo.getUserData()));
                } else {
                    mRil.riljLogv("Incoming UUS : NOT present!");
                }

                // Make sure there's a leading + on addresses with a TOA of 145
                dc.number = PhoneNumberUtils.stringFromStringAndTOA(dc.number, dc.TOA);

                dcCalls.add(dc);

                if (dc.isVoicePrivacy) {
                    mRil.mVoicePrivacyOnRegistrants.notifyRegistrants();
                    mRil.riljLog("InCall VoicePrivacy is enabled");
                } else {
                    mRil.mVoicePrivacyOffRegistrants.notifyRegistrants();
                    mRil.riljLog("InCall VoicePrivacy is disabled");
                }
            }

            Collections.sort(dcCalls);

            if ((num == 0) && mRil.mTestingEmergencyCall.getAndSet(false)) {
                if (mRil.mEmergencyCallbackModeRegistrant != null) {
                    mRil.riljLog("responseCurrentCalls: call ended, testing emergency call,"
                            + " notify ECM Registrants");
                    mRil.mEmergencyCallbackModeRegistrant.notifyRegistrant();
                }
            }

            if (responseInfo.error == RadioError.NONE) {
                sendMessageResponse(rr.mResult, dcCalls);
            }
            mRil.processResponseDone(rr, responseInfo, dcCalls);
        }
    }

    private void responseVoid(RadioResponseInfo responseInfo) {
        RILRequest rr = mRil.processResponse(responseInfo);

        if (rr != null) {
            Object ret = null;
            if (responseInfo.error == RadioError.NONE) {
                sendMessageResponse(rr.mResult, ret);
            }
            mRil.processResponseDone(rr, responseInfo, ret);
        }
    }

    private void responseString(RadioResponseInfo responseInfo, String str) {
        RILRequest rr = mRil.processResponse(responseInfo);

        if (rr != null) {
            if (responseInfo.error == RadioError.NONE) {
                sendMessageResponse(rr.mResult, str);
            }
            mRil.processResponseDone(rr, responseInfo, str);
        }
    }

    private void responseStrings(RadioResponseInfo responseInfo, String ...str) {
        ArrayList<String> strings = new ArrayList<>();
        for (int i = 0; i < str.length; i++) {
            strings.add(str[i]);
        }
        responseStringArrayList(mRil, responseInfo, strings);
    }

    static void responseStringArrayList(RIL ril, RadioResponseInfo responseInfo,
                                        ArrayList<String> strings) {
        RILRequest rr = ril.processResponse(responseInfo);

        if (rr != null) {
            String[] ret = new String[strings.size()];
            for (int i = 0; i < strings.size(); i++) {
                ret[i] = strings.get(i);
            }
            if (responseInfo.error == RadioError.NONE) {
                sendMessageResponse(rr.mResult, ret);
            }
            ril.processResponseDone(rr, responseInfo, ret);
        }
    }

    private void responseLastCallFailCauseInfo(RadioResponseInfo responseInfo,
                                               LastCallFailCauseInfo fcInfo) {
        RILRequest rr = mRil.processResponse(responseInfo);

        if (rr != null) {
            LastCallFailCause ret = new LastCallFailCause();
            ret.causeCode = fcInfo.causeCode;
            ret.vendorCause = fcInfo.vendorCause;
            if (responseInfo.error == RadioError.NONE) {
                sendMessageResponse(rr.mResult, ret);
            }
            mRil.processResponseDone(rr, responseInfo, ret);
        }
    }

    private void responseSignalStrength(RadioResponseInfo responseInfo,
                                        android.hardware.radio.V1_0.SignalStrength sigStrength) {
        RILRequest rr = mRil.processResponse(responseInfo);

        if (rr != null) {
            SignalStrength ret = RIL.convertHalSignalStrength(sigStrength);
            if (responseInfo.error == RadioError.NONE) {
                sendMessageResponse(rr.mResult, ret);
            }
            mRil.processResponseDone(rr, responseInfo, ret);
        }
    }

    private void responseSms(RadioResponseInfo responseInfo, SendSmsResult sms) {
        RILRequest rr = mRil.processResponse(responseInfo);

        if (rr != null) {
            SmsResponse ret = new SmsResponse(sms.messageRef, sms.ackPDU, sms.errorCode);
            if (responseInfo.error == RadioError.NONE) {
                sendMessageResponse(rr.mResult, ret);
            }
            mRil.processResponseDone(rr, responseInfo, ret);
        }
    }

    private void responseSetupDataCall(RadioResponseInfo responseInfo,
                                       SetupDataCallResult setupDataCallResult) {
        RILRequest rr = mRil.processResponse(responseInfo);

        if (rr != null) {
            DataCallResponse ret = RIL.convertDataCallResult(setupDataCallResult);
            if (responseInfo.error == RadioError.NONE) {
                sendMessageResponse(rr.mResult, ret);
            }
            mRil.processResponseDone(rr, responseInfo, ret);
        }
    }

    private void responseIccIo(RadioResponseInfo responseInfo,
                               android.hardware.radio.V1_0.IccIoResult result) {
        RILRequest rr = mRil.processResponse(responseInfo);

        if (rr != null) {
            IccIoResult ret = new IccIoResult(result.sw1, result.sw2, result.simResponse);
            if (responseInfo.error == RadioError.NONE) {
                sendMessageResponse(rr.mResult, ret);
            }
            mRil.processResponseDone(rr, responseInfo, ret);
        }
    }

    private void responseCallForwardInfo(RadioResponseInfo responseInfo,
                                         ArrayList<android.hardware.radio.V1_0.CallForwardInfo>
                                                 callForwardInfos) {
        RILRequest rr = mRil.processResponse(responseInfo);
        if (rr != null) {
            CallForwardInfo[] ret = new CallForwardInfo[callForwardInfos.size()];
            for (int i = 0; i < callForwardInfos.size(); i++) {
                ret[i] = new CallForwardInfo();
                ret[i].status = callForwardInfos.get(i).status;
                ret[i].reason = callForwardInfos.get(i).reason;
                ret[i].serviceClass = callForwardInfos.get(i).serviceClass;
                ret[i].toa = callForwardInfos.get(i).toa;
                ret[i].number = callForwardInfos.get(i).number;
                ret[i].timeSeconds = callForwardInfos.get(i).timeSeconds;
            }
            if (responseInfo.error == RadioError.NONE) {
                sendMessageResponse(rr.mResult, ret);
            }
            mRil.processResponseDone(rr, responseInfo, ret);
        }
    }

    private static String convertOpertatorInfoToString(int status) {
        if (status == android.hardware.radio.V1_0.OperatorStatus.UNKNOWN) {
            return "unknown";
        } else if (status == android.hardware.radio.V1_0.OperatorStatus.AVAILABLE) {
            return "available";
        } else if (status == android.hardware.radio.V1_0.OperatorStatus.CURRENT) {
            return "current";
        } else if (status == android.hardware.radio.V1_0.OperatorStatus.FORBIDDEN) {
            return "forbidden";
        } else {
            return "";
        }
    }

    private void responseOperatorInfos(RadioResponseInfo responseInfo,
                                       ArrayList<android.hardware.radio.V1_0.OperatorInfo>
                                               networkInfos) {
        RILRequest rr = mRil.processResponse(responseInfo);

        if (rr != null) {
            ArrayList<OperatorInfo> ret = new ArrayList<OperatorInfo>();
            for (int i = 0; i < networkInfos.size(); i++) {
                ret.add(new OperatorInfo(networkInfos.get(i).alphaLong,
                        networkInfos.get(i).alphaShort, networkInfos.get(i).operatorNumeric,
                        convertOpertatorInfoToString(networkInfos.get(i).status)));
            }
            if (responseInfo.error == RadioError.NONE) {
                sendMessageResponse(rr.mResult, ret);
            }
            mRil.processResponseDone(rr, responseInfo, ret);
        }
    }

    private void responseScanStatus(RadioResponseInfo responseInfo) {
        RILRequest rr = mRil.processResponse(responseInfo);

        if (rr != null) {
            NetworkScanResult nsr = null;
            if (responseInfo.error == RadioError.NONE) {
                nsr = new NetworkScanResult(
                        NetworkScanResult.SCAN_STATUS_PARTIAL, RadioError.NONE, null);
                sendMessageResponse(rr.mResult, nsr);
            }
            mRil.processResponseDone(rr, responseInfo, nsr);
        }
    }

    private void responseDataCallList(RadioResponseInfo responseInfo,
                                      ArrayList<SetupDataCallResult> dataCallResultList) {
        RILRequest rr = mRil.processResponse(responseInfo);

        if (rr != null) {
            ArrayList<DataCallResponse> dcResponseList = new ArrayList<>();
            for (SetupDataCallResult dcResult : dataCallResultList) {
                dcResponseList.add(RIL.convertDataCallResult(dcResult));
            }
            if (responseInfo.error == RadioError.NONE) {
                sendMessageResponse(rr.mResult, dcResponseList);
            }
            mRil.processResponseDone(rr, responseInfo, dcResponseList);
        }
    }

    private void responseCellList(RadioResponseInfo responseInfo,
                                  ArrayList<NeighboringCell> cells) {
        RILRequest rr = mRil.processResponse(responseInfo);

        if (rr != null) {
            int rssi;
            String location;
            ArrayList<NeighboringCellInfo> ret = new ArrayList<NeighboringCellInfo>();
            NeighboringCellInfo cell;

            int[] subId = SubscriptionManager.getSubId(mRil.mPhoneId);
            int radioType =
                    ((TelephonyManager) mRil.mContext.getSystemService(
                            Context.TELEPHONY_SERVICE)).getDataNetworkType(subId[0]);

            if (radioType != TelephonyManager.NETWORK_TYPE_UNKNOWN) {
                for (int i = 0; i < cells.size(); i++) {
                    rssi = cells.get(i).rssi;
                    location = cells.get(i).cid;
                    cell = new NeighboringCellInfo(rssi, location, radioType);
                    ret.add(cell);
                }
            }
            if (responseInfo.error == RadioError.NONE) {
                sendMessageResponse(rr.mResult, ret);
            }
            mRil.processResponseDone(rr, responseInfo, ret);
        }
    }

    private void responseGmsBroadcastConfig(RadioResponseInfo responseInfo,
                                            ArrayList<GsmBroadcastSmsConfigInfo> configs) {
        RILRequest rr = mRil.processResponse(responseInfo);

        if (rr != null) {
            ArrayList<SmsBroadcastConfigInfo> ret = new ArrayList<SmsBroadcastConfigInfo>();
            for (int i = 0; i < configs.size(); i++) {
                ret.add(new SmsBroadcastConfigInfo(configs.get(i).fromServiceId,
                        configs.get(i).toServiceId, configs.get(i).fromCodeScheme,
                        configs.get(i).toCodeScheme, configs.get(i).selected));
            }
            if (responseInfo.error == RadioError.NONE) {
                sendMessageResponse(rr.mResult, ret);
            }
            mRil.processResponseDone(rr, responseInfo, ret);
        }
    }

    private void responseCdmaBroadcastConfig(RadioResponseInfo responseInfo,
                                            ArrayList<CdmaBroadcastSmsConfigInfo> configs) {
        RILRequest rr = mRil.processResponse(responseInfo);

        if (rr != null) {
            int[] ret = null;

            int numServiceCategories = configs.size();

            if (numServiceCategories == 0) {
                // TODO: The logic of providing default values should
                // not be done by this transport layer. And needs to
                // be done by the vendor ril or application logic.
                int numInts;
                numInts = CDMA_BROADCAST_SMS_NO_OF_SERVICE_CATEGORIES
                        * CDMA_BSI_NO_OF_INTS_STRUCT + 1;
                ret = new int[numInts];

                // Faking a default record for all possible records.
                ret[0] = CDMA_BROADCAST_SMS_NO_OF_SERVICE_CATEGORIES;

                // Loop over CDMA_BROADCAST_SMS_NO_OF_SERVICE_CATEGORIES set 'english' as
                // default language and selection status to false for all.
                for (int i = 1; i < numInts; i += CDMA_BSI_NO_OF_INTS_STRUCT) {
                    ret[i + 0] = i / CDMA_BSI_NO_OF_INTS_STRUCT;
                    ret[i + 1] = 1;
                    ret[i + 2] = 0;
                }
            } else {
                int numInts;
                numInts = (numServiceCategories * CDMA_BSI_NO_OF_INTS_STRUCT) + 1;
                ret = new int[numInts];

                ret[0] = numServiceCategories;
                for (int i = 1, j = 0; j < configs.size();
                        j++, i = i + CDMA_BSI_NO_OF_INTS_STRUCT) {
                    ret[i] = configs.get(j).serviceCategory;
                    ret[i + 1] = configs.get(j).language;
                    ret[i + 2] = configs.get(j).selected ? 1 : 0;
                }
            }
            if (responseInfo.error == RadioError.NONE) {
                sendMessageResponse(rr.mResult, ret);
            }
            mRil.processResponseDone(rr, responseInfo, ret);
        }
    }

    private void responseCellInfoList(RadioResponseInfo responseInfo,
                                      ArrayList<android.hardware.radio.V1_0.CellInfo> cellInfo) {
        RILRequest rr = mRil.processResponse(responseInfo);

        if (rr != null) {
            ArrayList<CellInfo> ret = RIL.convertHalCellInfoList(cellInfo);
            if (responseInfo.error == RadioError.NONE) {
                sendMessageResponse(rr.mResult, ret);
            }
            mRil.processResponseDone(rr, responseInfo, ret);
        }
    }

    private void responseActivityData(RadioResponseInfo responseInfo,
                                      ActivityStatsInfo activityInfo) {
        RILRequest rr = mRil.processResponse(responseInfo);

        if (rr != null) {
            ModemActivityInfo ret = null;
            if (responseInfo.error == RadioError.NONE) {
                final int sleepModeTimeMs = activityInfo.sleepModeTimeMs;
                final int idleModeTimeMs = activityInfo.idleModeTimeMs;
                int [] txModeTimeMs = new int[ModemActivityInfo.TX_POWER_LEVELS];
                for (int i = 0; i < ModemActivityInfo.TX_POWER_LEVELS; i++) {
                    txModeTimeMs[i] = activityInfo.txmModetimeMs[i];
                }
                final int rxModeTimeMs = activityInfo.rxModeTimeMs;
                ret = new ModemActivityInfo(SystemClock.elapsedRealtime(), sleepModeTimeMs,
                        idleModeTimeMs, txModeTimeMs, rxModeTimeMs, 0);
            } else {
                ret = new ModemActivityInfo(0, 0, 0, new int [ModemActivityInfo.TX_POWER_LEVELS],
                        0, 0);
                responseInfo.error = RadioError.NONE;
            }
            sendMessageResponse(rr.mResult, ret);
            mRil.processResponseDone(rr, responseInfo, ret);
        }
    }

    private void responseHardwareConfig(
            RadioResponseInfo responseInfo,
            ArrayList<android.hardware.radio.V1_0.HardwareConfig> config) {
        RILRequest rr = mRil.processResponse(responseInfo);

        if (rr != null) {
            ArrayList<HardwareConfig> ret = RIL.convertHalHwConfigList(config, mRil);
            if (responseInfo.error == RadioError.NONE) {
                sendMessageResponse(rr.mResult, ret);
            }
            mRil.processResponseDone(rr, responseInfo, ret);
        }
    }

    private void responseICC_IOBase64(RadioResponseInfo responseInfo,
                                      android.hardware.radio.V1_0.IccIoResult result) {
        RILRequest rr = mRil.processResponse(responseInfo);

        if (rr != null) {
            IccIoResult ret = new IccIoResult(
                    result.sw1,
                    result.sw2,
                    (!(result.simResponse).equals(""))
                            ? android.util.Base64.decode(result.simResponse,
                            android.util.Base64.DEFAULT) : (byte[]) null);
            if (responseInfo.error == RadioError.NONE) {
                sendMessageResponse(rr.mResult, ret);
            }
            mRil.processResponseDone(rr, responseInfo, ret);
        }
    }

    private void responseRadioCapability(RadioResponseInfo responseInfo,
                                         android.hardware.radio.V1_0.RadioCapability rc) {
        RILRequest rr = mRil.processResponse(responseInfo);

        if (rr != null) {
            RadioCapability ret = RIL.convertHalRadioCapability(rc, mRil);
            if (responseInfo.error == RadioError.NONE) {
                sendMessageResponse(rr.mResult, ret);
            }
            mRil.processResponseDone(rr, responseInfo, ret);
        }
    }

    private void responseLceStatus(RadioResponseInfo responseInfo, LceStatusInfo statusInfo) {
        RILRequest rr = mRil.processResponse(responseInfo);

        if (rr != null) {
            ArrayList<Integer> ret = new ArrayList<Integer>();
            ret.add(statusInfo.lceStatus);
            ret.add(Byte.toUnsignedInt(statusInfo.actualIntervalMs));
            if (responseInfo.error == RadioError.NONE) {
                sendMessageResponse(rr.mResult, ret);
            }
            mRil.processResponseDone(rr, responseInfo, ret);
        }
    }

    private void responseLceData(RadioResponseInfo responseInfo, LceDataInfo lceInfo) {
        RILRequest rr = mRil.processResponse(responseInfo);

        if (rr != null) {
            ArrayList<Integer> ret = RIL.convertHalLceData(lceInfo, mRil);
            if (responseInfo.error == RadioError.NONE) {
                sendMessageResponse(rr.mResult, ret);
            }
            mRil.processResponseDone(rr, responseInfo, ret);
        }
    }

    private void responseCarrierIdentifiers(RadioResponseInfo responseInfo, boolean allAllowed,
                                            CarrierRestrictions carriers) {
        RILRequest rr = mRil.processResponse(responseInfo);

        if (rr != null) {
            List<CarrierIdentifier> ret = new ArrayList<CarrierIdentifier>();
            for (int i = 0; i < carriers.allowedCarriers.size(); i++) {
                String mcc = carriers.allowedCarriers.get(i).mcc;
                String mnc = carriers.allowedCarriers.get(i).mnc;
                String spn = null, imsi = null, gid1 = null, gid2 = null;
                int matchType = carriers.allowedCarriers.get(i).matchType;
                String matchData = carriers.allowedCarriers.get(i).matchData;
                if (matchType == CarrierIdentifier.MatchType.SPN) {
                    spn = matchData;
                } else if (matchType == CarrierIdentifier.MatchType.IMSI_PREFIX) {
                    imsi = matchData;
                } else if (matchType == CarrierIdentifier.MatchType.GID1) {
                    gid1 = matchData;
                } else if (matchType == CarrierIdentifier.MatchType.GID2) {
                    gid2 = matchData;
                }
                ret.add(new CarrierIdentifier(mcc, mnc, spn, imsi, gid1, gid2));
            }
            if (responseInfo.error == RadioError.NONE) {
                /* TODO: Handle excluded carriers */
                sendMessageResponse(rr.mResult, ret);
            }
            mRil.processResponseDone(rr, responseInfo, ret);
        }
    }
}
