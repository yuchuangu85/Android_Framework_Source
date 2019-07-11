package com.android.internal.telephony;

import com.android.ims.ImsConfig;
import com.android.ims.ImsReasonInfo;
import com.android.ims.internal.ImsCallSession;
import com.android.internal.telephony.dataconnection.DataCallResponse;
import com.android.internal.telephony.imsphone.ImsPhoneCall;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.ConnectivityMetricsLogger;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.telephony.ServiceState;
import android.util.Log;
import android.util.SparseArray;

import java.util.ArrayList;

import static com.android.internal.telephony.RILConstants.*;

/**
 * Log telephony events
 *
 * @hide
 */
public class TelephonyEventLog extends ConnectivityMetricsLogger {
    private static String TAG = "TelephonyEventLog";
    private static final boolean DBG = true;
    private static final boolean VDBG = false; // STOPSHIP if true

    public static final int TAG_SETTINGS = 1;
    public static final int TAG_SERVICE_STATE = 2;
    public static final int TAG_IMS_CONNECTION_STATE = 3;
    public static final int TAG_IMS_CAPABILITIES = 4;

    public static final int TAG_DATA_CALL_LIST = 5;

    public static final int TAG_PHONE_STATE = 8;

    public static final int TAG_RIL_REQUEST = 1001;
    public static final int TAG_RIL_RESPONSE = 1002;
    public static final int TAG_RIL_UNSOL_RESPONSE = 1003;
    public static final int TAG_RIL_TIMEOUT_RESPONSE = 1004;

    // IImsCallSession
    public static final int TAG_IMS_CALL_START = 2001;
    public static final int TAG_IMS_CALL_START_CONFERENCE = 2002;
    public static final int TAG_IMS_CALL_RECEIVE = 2003;
    public static final int TAG_IMS_CALL_ACCEPT = 2004;
    public static final int TAG_IMS_CALL_REJECT = 2005;
    public static final int TAG_IMS_CALL_TERMINATE = 2006;
    public static final int TAG_IMS_CALL_HOLD = 2007;
    public static final int TAG_IMS_CALL_RESUME = 2008;
    public static final int TAG_IMS_CALL_MERGE = 2009;
    public static final int TAG_IMS_CALL_UPDATE = 2010;

    // IImsCallSessionListener
    public static final int TAG_IMS_CALL_PROGRESSING = 2011;
    public static final int TAG_IMS_CALL_STARTED = 2012;
    public static final int TAG_IMS_CALL_START_FAILED = 2013;
    public static final int TAG_IMS_CALL_TERMINATED = 2014;
    public static final int TAG_IMS_CALL_HELD = 2015;
    public static final int TAG_IMS_CALL_HOLD_FAILED = 2016;
    public static final int TAG_IMS_CALL_HOLD_RECEIVED = 2017;
    public static final int TAG_IMS_CALL_RESUMED = 2018;
    public static final int TAG_IMS_CALL_RESUME_FAILED = 2019;
    public static final int TAG_IMS_CALL_RESUME_RECEIVED = 2020;
    public static final int TAG_IMS_CALL_UPDATED = 2021;
    public static final int TAG_IMS_CALL_UPDATE_FAILED = 2022;
    public static final int TAG_IMS_CALL_MERGED = 2023;
    public static final int TAG_IMS_CALL_MERGE_FAILED = 2024;
    public static final int TAG_IMS_CALL_HANDOVER = 2025;
    public static final int TAG_IMS_CALL_HANDOVER_FAILED = 2026;

    public static final int TAG_IMS_CALL_TTY_MODE_RECEIVED = 2027;

    public static final int TAG_IMS_CONFERENCE_PARTICIPANTS_STATE_CHANGED = 2028;
    public static final int TAG_IMS_MULTIPARTY_STATE_CHANGED = 2029;

    public static final int TAG_IMS_CALL_STATE = 2030;

    public static final int SETTING_AIRPLANE_MODE = 1;
    public static final int SETTING_CELL_DATA_ENABLED = 2;
    public static final int SETTING_DATA_ROAMING_ENABLED = 3;
    public static final int SETTING_PREFERRED_NETWORK_MODE = 4;
    public static final int SETTING_WIFI_ENABLED = 5;
    public static final int SETTING_VO_LTE_ENABLED = 6;
    public static final int SETTING_VO_WIFI_ENABLED = 7;
    public static final int SETTING_WFC_MODE = 8;
    public static final int SETTING_VI_LTE_ENABLED = 9;
    public static final int SETTING_VI_WIFI_ENABLED = 10;

    public static final int IMS_CONNECTION_STATE_CONNECTED = 1;
    public static final int IMS_CONNECTION_STATE_PROGRESSING = 2;
    public static final int IMS_CONNECTION_STATE_DISCONNECTED = 3;
    public static final int IMS_CONNECTION_STATE_RESUMED = 4;
    public static final int IMS_CONNECTION_STATE_SUSPENDED = 5;

    public static final String DATA_KEY_PHONE_ID = "phoneId";
    public static final String DATA_KEY_PARAM1 = "param1";
    public static final String DATA_KEY_PARAM2 = "param2";

    public static final String DATA_KEY_REASONINFO_CODE = "code";
    public static final String DATA_KEY_REASONINFO_EXTRA_CODE = "extra-code";
    public static final String DATA_KEY_REASONINFO_EXTRA_MESSAGE = "extra-message";
    public static final String DATA_KEY_VOLTE = "VoLTE";
    public static final String DATA_KEY_VILTE = "ViLTE";
    public static final String DATA_KEY_VOWIFI = "VoWiFi";
    public static final String DATA_KEY_VIWIFI = "ViWiFi";
    public static final String DATA_KEY_UTLTE = "UTLTE";
    public static final String DATA_KEY_UTWIFI = "UTWiFi";
    public static final String DATA_KEY_RAT = "rat";
    public static final String DATA_KEY_DATA_PROFILE = "profile";
    public static final String DATA_KEY_APN = "apn";
    public static final String DATA_KEY_PROTOCOL = "protocol";
    public static final String DATA_KEY_DATA_DEACTIVATE_REASON = "reason";
    public static final String DATA_KEY_DATA_CALL_STATUSES = "statuses";
    public static final String DATA_KEY_DATA_CALL_CIDS = "cids";
    public static final String DATA_KEY_DATA_CALL_ACTIVES = "actives";
    public static final String DATA_KEY_DATA_CALL_TYPES = "types";
    public static final String DATA_KEY_DATA_CALL_IFNAMES = "ifnames";
    public static final String DATA_KEY_CLIR_MODE = "clirMode";
    public static final String DATA_KEY_RIL_CALL_RING_RESPONSE = "response";
    public static final String DATA_KEY_RIL_HANGUP_GSM_INDEX = "gsmIndex";
    public static final String DATA_KEY_RIL_ERROR = "error";
    public static final String DATA_KEY_DATA_CALL_STATUS = "status";
    public static final String DATA_KEY_DATA_CALL_RETRY = "retry";
    public static final String DATA_KEY_DATA_CALL_CID = "cid";
    public static final String DATA_KEY_DATA_CALL_ACTIVE = "active";
    public static final String DATA_KEY_DATA_CALL_TYPE = "type";
    public static final String DATA_KEY_DATA_CALL_IFNAME = "ifname";
    public static final String DATA_KEY_SMS_MESSAGE_REF = "messageRef";
    public static final String DATA_KEY_SMS_ACK_PDU = "ackPDU";
    public static final String DATA_KEY_SMS_ERROR_CODE = "errorCode";
    public static final String DATA_KEY_CALLEE = "callee";
    public static final String DATA_KEY_PARTICIPANTS = "participants";
    public static final String DATA_KEY_SRC_TECH = "src-tech";
    public static final String DATA_KEY_TARGET_TECH = "target-tech";

    public static final String SERVICE_STATE_VOICE_REG_STATE = "regSt";
    public static final String SERVICE_STATE_DATA_REG_STATE = "dataRegSt";
    public static final String SERVICE_STATE_VOICE_ROAMING_TYPE = "roamingType";
    public static final String SERVICE_STATE_DATA_ROAMING_TYPE = "dataRoamingType";
    public static final String SERVICE_STATE_VOICE_ALPHA_LONG = "alphaLong";
    public static final String SERVICE_STATE_VOICE_ALPNA_SHORT = "alphaShort";
    public static final String SERVICE_STATE_VOICE_NUMERIC = "operator";
    public static final String SERVICE_STATE_DATA_ALPHA_LONG = "dataAlphaLong";
    public static final String SERVICE_STATE_DATA_ALPNA_SHORT = "dataAlphaShort";
    public static final String SERVICE_STATE_DATA_NUMERIC = "dataOperator";
    public static final String SERVICE_STATE_VOICE_RAT = "rat";
    public static final String SERVICE_STATE_DATA_RAT = "dataRat";
    public static final String SERVICE_STATE_EMERGENCY_ONLY = "emergencyOnly";

    int mPhoneId;

    public TelephonyEventLog(int phoneId) {
        super();

        mPhoneId = phoneId;
    }

    private void writeEvent(int tag, Bundle data) {
        writeEvent(System.currentTimeMillis(), tag, -1, -1, data);
    }

    private void writeEvent(int tag, int param1, int param2) {
        writeEvent(System.currentTimeMillis(), tag, param1, param2, null);
    }

    private void writeEvent(int tag, int param1, int param2, Bundle data) {
        writeEvent(System.currentTimeMillis(), tag, param1, param2, data);
    }

    private void writeEvent(long timestamp, int tag, int param1, int param2, Bundle data) {
        Bundle b = data;
        if (b == null) {
            b = new Bundle();
        }
        b.putInt(DATA_KEY_PHONE_ID, mPhoneId);
        b.putInt(DATA_KEY_PARAM1, param1);
        b.putInt(DATA_KEY_PARAM2, param2);

        logEvent(timestamp, ConnectivityMetricsLogger.COMPONENT_TAG_TELEPHONY, tag, b);
    }

    private int mVoiceRegState = -1;
    private int mDataRegState = -1;
    private int mVoiceRoamingType = -1;
    private int mDataRoamingType = -1;
    private String mVoiceOperatorAlphaShort;
    private String mVoiceOperatorNumeric;
    private String mDataOperatorAlphaShort;
    private String mDataOperatorNumeric;
    private int mRilVoiceRadioTechnology = -1;
    private int mRilDataRadioTechnology = -1;
    private boolean mEmergencyOnly = false;

    public static boolean equals(Object a, Object b) {
        return (a == null ? b == null : a.equals(b));
    }

    public void writeServiceStateChanged(ServiceState serviceState) {
        Bundle b = new Bundle();
        boolean changed = false;
        if (mVoiceRegState != serviceState.getVoiceRegState()) {
            mVoiceRegState = serviceState.getVoiceRegState();
            b.putInt(SERVICE_STATE_VOICE_REG_STATE, mVoiceRegState);
            changed = true;
        }
        if (mDataRegState != serviceState.getDataRegState()) {
            mDataRegState = serviceState.getDataRegState();
            b.putInt(SERVICE_STATE_DATA_REG_STATE, mDataRegState);
            changed = true;
        }
        if (mVoiceRoamingType != serviceState.getVoiceRoamingType()) {
            mVoiceRoamingType = serviceState.getVoiceRoamingType();
            b.putInt(SERVICE_STATE_VOICE_ROAMING_TYPE, mVoiceRoamingType);
            changed = true;
        }
        if (mDataRoamingType != serviceState.getDataRoamingType()) {
            mDataRoamingType = serviceState.getDataRoamingType();
            b.putInt(SERVICE_STATE_DATA_ROAMING_TYPE, mDataRoamingType);
            changed = true;
        }
        if (!equals(mVoiceOperatorAlphaShort, serviceState.getVoiceOperatorAlphaShort())
                || !equals(mVoiceOperatorNumeric, serviceState.getVoiceOperatorNumeric())) {
            // TODO: Evaluate if we need to send AlphaLong. AlphaShort+Numeric might be enough.
            //b.putString(SERVICE_STATE_VOICE_ALPHA_LONG, serviceState.getVoiceOperatorAlphaLong());
            mVoiceOperatorAlphaShort = serviceState.getVoiceOperatorAlphaShort();
            mVoiceOperatorNumeric = serviceState.getVoiceOperatorNumeric();
            b.putString(SERVICE_STATE_VOICE_ALPNA_SHORT, mVoiceOperatorAlphaShort);
            b.putString(SERVICE_STATE_VOICE_NUMERIC, mVoiceOperatorNumeric);
            changed = true;
        }
        if (!equals(mDataOperatorAlphaShort, serviceState.getDataOperatorAlphaShort())
                || !equals(mDataOperatorNumeric, serviceState.getDataOperatorNumeric())) {
            // TODO: Evaluate if we need to send AlphaLong. AlphaShort+Numeric might be enough.
            //b.putString(SERVICE_STATE_DATA_ALPHA_LONG, serviceState.getDataOperatorAlphaLong());
            mDataOperatorAlphaShort = serviceState.getDataOperatorAlphaShort();
            mDataOperatorNumeric = serviceState.getDataOperatorNumeric();
            b.putString(SERVICE_STATE_DATA_ALPNA_SHORT, mDataOperatorAlphaShort);
            b.putString(SERVICE_STATE_DATA_NUMERIC, mDataOperatorNumeric);
            changed = true;
        }
        if (mRilVoiceRadioTechnology != serviceState.getRilVoiceRadioTechnology()) {
            mRilVoiceRadioTechnology = serviceState.getRilVoiceRadioTechnology();
            b.putInt(SERVICE_STATE_VOICE_RAT, mRilVoiceRadioTechnology);
            changed = true;
        }
        if (mRilDataRadioTechnology != serviceState.getRilDataRadioTechnology()) {
            mRilDataRadioTechnology = serviceState.getRilDataRadioTechnology();
            b.putInt(SERVICE_STATE_DATA_RAT, mRilDataRadioTechnology);
            changed = true;
        }
        if (mEmergencyOnly != serviceState.isEmergencyOnly()) {
            mEmergencyOnly = serviceState.isEmergencyOnly();
            b.putBoolean(SERVICE_STATE_EMERGENCY_ONLY, mEmergencyOnly);
            changed = true;
        }

        if (changed) {
            writeEvent(TAG_SERVICE_STATE, b);
        }
    }

    public void writeSetAirplaneMode(boolean enabled) {
        writeEvent(TAG_SETTINGS, SETTING_AIRPLANE_MODE, enabled ? 1 : 0);
    }

    public void writeSetCellDataEnabled(boolean enabled) {
        writeEvent(TAG_SETTINGS, SETTING_CELL_DATA_ENABLED, enabled ? 1 : 0);
    }

    public void writeSetDataRoamingEnabled(boolean enabled) {
        writeEvent(TAG_SETTINGS, SETTING_DATA_ROAMING_ENABLED, enabled ? 1 : 0);
    }

    public void writeSetPreferredNetworkType(int mode) {
        writeEvent(TAG_SETTINGS, SETTING_PREFERRED_NETWORK_MODE, mode);
    }

    public void writeSetWifiEnabled(boolean enabled) {
        writeEvent(TAG_SETTINGS, SETTING_WIFI_ENABLED, enabled ? 1 : 0);
    }

    public void writeSetWfcMode(int mode) {
        writeEvent(TAG_SETTINGS, SETTING_WFC_MODE, mode);
    }

    public void writeImsSetFeatureValue(int feature, int network, int value, int status) {
        switch (feature) {
            case ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_LTE:
                writeEvent(TAG_SETTINGS, SETTING_VO_LTE_ENABLED, value);
                break;
            case ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_WIFI:
                writeEvent(TAG_SETTINGS, SETTING_VO_WIFI_ENABLED, value);
                break;
            case ImsConfig.FeatureConstants.FEATURE_TYPE_VIDEO_OVER_LTE:
                writeEvent(TAG_SETTINGS, SETTING_VI_LTE_ENABLED, value);
                break;
            case ImsConfig.FeatureConstants.FEATURE_TYPE_VIDEO_OVER_WIFI:
                writeEvent(TAG_SETTINGS, SETTING_VI_WIFI_ENABLED, value);
                break;
        }
    }

    public void writeOnImsConnectionState(int state, ImsReasonInfo reasonInfo) {
        writeEvent(TAG_IMS_CONNECTION_STATE, state, -1, imsReasonInfoToBundle(reasonInfo));
    }

    private final boolean[] mImsCapabilities = {false, false, false, false, false, false};

    public void writeOnImsCapabilities(boolean[] capabilities) {
        boolean changed = false;
        for (int i = 0; i < capabilities.length; i++) {
            if (mImsCapabilities[i] != capabilities[i]) {
                mImsCapabilities[i] = capabilities[i];
                changed = true;
            }
        }

        if (changed) {
            Bundle b = new Bundle();
            b.putBoolean(DATA_KEY_VOLTE, capabilities[0]);
            b.putBoolean(DATA_KEY_VILTE, capabilities[1]);
            b.putBoolean(DATA_KEY_VOWIFI, capabilities[2]);
            b.putBoolean(DATA_KEY_VIWIFI, capabilities[3]);
            b.putBoolean(DATA_KEY_UTLTE, capabilities[4]);
            b.putBoolean(DATA_KEY_UTWIFI, capabilities[5]);
            writeEvent(TAG_IMS_CAPABILITIES, b);
        }
    }

    public void writeRilSetupDataCall(int rilSerial,
            int radioTechnology, int profile, String apn,
            String user, String password, int authType, String protocol) {
        Bundle b = new Bundle();
        b.putInt(DATA_KEY_RAT, radioTechnology);
        b.putInt(DATA_KEY_DATA_PROFILE, profile);
        b.putString(DATA_KEY_APN, apn);
        b.putString(DATA_KEY_PROTOCOL, protocol);
        writeEvent(TAG_RIL_REQUEST, RIL_REQUEST_SETUP_DATA_CALL, rilSerial, b);
    }

    public void writeRilDeactivateDataCall(int rilSerial,
            int cid, int reason) {
        Bundle b = new Bundle();
        b.putInt(DATA_KEY_DATA_CALL_CID, cid);
        b.putInt(DATA_KEY_DATA_DEACTIVATE_REASON, reason);
        writeEvent(TAG_RIL_REQUEST, RIL_REQUEST_DEACTIVATE_DATA_CALL, rilSerial, b);
    }

    public void writeRilDataCallList(ArrayList<DataCallResponse> dcsList) {
        Bundle b = new Bundle();
        int[] statuses = new int[dcsList.size()];
        int[] cids = new int[dcsList.size()];
        int[] actives = new int[dcsList.size()];
        String[] types = new String[dcsList.size()];
        String[] ifnames = new String[dcsList.size()];
        for (int i = 0; i < dcsList.size(); i++) {
            DataCallResponse dcs = dcsList.get(i);
            statuses[i] = dcs.status;
            cids[i] = dcs.cid;
            actives[i] = dcs.active;
            types[i] = dcs.type;
            ifnames[i] = dcs.ifname;
        }
        b.putIntArray(DATA_KEY_DATA_CALL_STATUSES, statuses);
        b.putIntArray(DATA_KEY_DATA_CALL_CIDS, cids);
        b.putIntArray(DATA_KEY_DATA_CALL_ACTIVES, actives);
        b.putStringArray(DATA_KEY_DATA_CALL_TYPES, types);
        b.putStringArray(DATA_KEY_DATA_CALL_IFNAMES, ifnames);
        writeEvent(TAG_DATA_CALL_LIST, -1, -1, b);
    }

    public void writeRilDial(int rilSerial,
            int clirMode, UUSInfo uusInfo) {
        Bundle b = new Bundle();
        b.putInt(DATA_KEY_CLIR_MODE, clirMode);
        writeEvent(TAG_RIL_REQUEST, RIL_REQUEST_DIAL, rilSerial, b);
    }

    public void writeRilCallRing(char[] response) {
        Bundle b = new Bundle();
        b.putCharArray(DATA_KEY_RIL_CALL_RING_RESPONSE, response);
        writeEvent(TAG_RIL_UNSOL_RESPONSE, RIL_UNSOL_CALL_RING, -1, b);
    }

    public void writeRilHangup(int rilSerial, int req,
            int gsmIndex) {
        Bundle b = new Bundle();
        b.putInt(DATA_KEY_RIL_HANGUP_GSM_INDEX, gsmIndex);
        writeEvent(TAG_RIL_REQUEST, req, rilSerial, b);
    }

    public void writeRilAnswer(int rilSerial) {
        writeEvent(TAG_RIL_REQUEST, RIL_REQUEST_ANSWER, rilSerial, null);
    }

    public void writeRilSrvcc(int rilSrvccState) {
        writeEvent(TAG_RIL_UNSOL_RESPONSE, RIL_UNSOL_SRVCC_STATE_NOTIFY, rilSrvccState, null);
    }

    public void writeRilSendSms(int rilSerial, int req) {
        writeEvent(TAG_RIL_REQUEST, req, rilSerial, null);
    }

    public void writeRilNewSms(int response) {
        writeEvent(TAG_RIL_UNSOL_RESPONSE, response, -1, null);
    }

    public void writeOnRilSolicitedResponse(int rilSerial, int rilError, int rilRequest,
            Object ret) {
        Bundle b = new Bundle();
        if (rilError != 0) b.putInt(DATA_KEY_RIL_ERROR, rilError);
        switch (rilRequest) {
            case RIL_REQUEST_SETUP_DATA_CALL:
                DataCallResponse dataCall = (DataCallResponse)ret;
                b.putInt(DATA_KEY_DATA_CALL_STATUS, dataCall.status);
                b.putInt(DATA_KEY_DATA_CALL_RETRY, dataCall.suggestedRetryTime);
                b.putInt(DATA_KEY_DATA_CALL_CID, dataCall.cid);
                b.putInt(DATA_KEY_DATA_CALL_ACTIVE, dataCall.active);
                b.putString(DATA_KEY_DATA_CALL_TYPE, dataCall.type);
                b.putString(DATA_KEY_DATA_CALL_IFNAME, dataCall.ifname);
                writeEvent(TAG_RIL_RESPONSE, rilRequest, rilSerial, b);
                break;

            case RIL_REQUEST_DEACTIVATE_DATA_CALL:
            case RIL_REQUEST_HANGUP:
            case RIL_REQUEST_HANGUP_WAITING_OR_BACKGROUND:
            case RIL_REQUEST_HANGUP_FOREGROUND_RESUME_BACKGROUND:
            case RIL_REQUEST_DIAL:
            case RIL_REQUEST_ANSWER:
                writeEvent(TAG_RIL_RESPONSE, rilRequest, rilSerial, b);
                break;

            case RIL_REQUEST_SEND_SMS:
            case RIL_REQUEST_SEND_SMS_EXPECT_MORE:
            case RIL_REQUEST_CDMA_SEND_SMS:
            case RIL_REQUEST_IMS_SEND_SMS:
                SmsResponse smsResponse = (SmsResponse)ret;
                b.putInt(DATA_KEY_SMS_MESSAGE_REF, smsResponse.mMessageRef);
                b.putString(DATA_KEY_SMS_ACK_PDU, smsResponse.mAckPdu);
                b.putInt(DATA_KEY_SMS_ERROR_CODE, smsResponse.mErrorCode);
                writeEvent(TAG_RIL_RESPONSE, rilRequest, rilSerial, b);
                break;
        }
    }

    public void writeOnRilTimeoutResponse(int rilSerial, int rilRequest) {
        writeEvent(TAG_RIL_TIMEOUT_RESPONSE, rilRequest, rilSerial, null);
    }

    public void writePhoneState(PhoneConstants.State phoneState) {
        int state;
        switch (phoneState) {
            case IDLE: state = 0; break;
            case RINGING: state = 1; break;
            case OFFHOOK: state = 2; break;
            default: state = -1; break;
        }
        writeEvent(TAG_PHONE_STATE, state, -1);
    }

    /**
     * Initial state
     * @param session
     * @param callState
     */
    public void writeImsCallState(ImsCallSession session, ImsPhoneCall.State callState) {
        int state;
        switch (callState) {
            case IDLE: state = 0; break;
            case ACTIVE: state = 1; break;
            case HOLDING: state = 2; break;
            case DIALING: state = 3; break;
            case ALERTING: state = 4; break;
            case INCOMING: state = 5; break;
            case WAITING: state = 6; break;
            case DISCONNECTED: state = 7; break;
            case DISCONNECTING: state = 8; break;
            default: state = -1; break;
        }
        writeEvent(TAG_IMS_CALL_STATE, getCallId(session), state);
    }

    private void writeImsCallEvent(int tag, ImsCallSession session) {
        writeEvent(tag, getCallId(session), -1);
    }

    private void writeImsCallEvent(int tag, ImsCallSession session, ImsReasonInfo reasonInfo) {
        writeEvent(tag, getCallId(session), -1,imsReasonInfoToBundle(reasonInfo));
    }

    private Bundle imsReasonInfoToBundle(ImsReasonInfo reasonInfo) {
        if (reasonInfo != null) {
            Bundle b = new Bundle();
            b.putInt(DATA_KEY_REASONINFO_CODE, reasonInfo.mCode);
            b.putInt(DATA_KEY_REASONINFO_EXTRA_CODE, reasonInfo.mExtraCode);
            b.putString(DATA_KEY_REASONINFO_EXTRA_MESSAGE, reasonInfo.mExtraMessage);
            return b;
        }
        return null;
    }

    public void writeOnImsCallStart(ImsCallSession session, String callee) {
        Bundle b = new Bundle();
        b.putString(DATA_KEY_CALLEE, callee);
        writeEvent(TAG_IMS_CALL_START, getCallId(session), -1, b);
    }

    public void writeOnImsCallStartConference(ImsCallSession session, String[] participants) {
        Bundle b = new Bundle();
        b.putStringArray(DATA_KEY_PARTICIPANTS, participants);
        writeEvent(TAG_IMS_CALL_START_CONFERENCE, getCallId(session), -1, b);
    }

    public void writeOnImsCallReceive(ImsCallSession session) {
        writeImsCallEvent(TAG_IMS_CALL_RECEIVE, session);
    }

    public void writeOnImsCallAccept(ImsCallSession session) {
        writeImsCallEvent(TAG_IMS_CALL_ACCEPT, session);
    }

    public void writeOnImsCallReject(ImsCallSession session) {
        writeImsCallEvent(TAG_IMS_CALL_REJECT, session);
    }

    public void writeOnImsCallTerminate(ImsCallSession session) {
        writeImsCallEvent(TAG_IMS_CALL_TERMINATE, session);
    }

    public void writeOnImsCallHold(ImsCallSession session) {
        writeImsCallEvent(TAG_IMS_CALL_HOLD, session);
    }

    public void writeOnImsCallResume(ImsCallSession session) {
        writeImsCallEvent(TAG_IMS_CALL_RESUME, session);
    }

    public void writeOnImsCallProgressing(ImsCallSession session) {
        writeImsCallEvent(TAG_IMS_CALL_PROGRESSING, session);
    }

    public void writeOnImsCallStarted(ImsCallSession session) {
        writeImsCallEvent(TAG_IMS_CALL_STARTED, session);
    }

    public void writeOnImsCallStartFailed(ImsCallSession session, ImsReasonInfo reasonInfo) {
        writeImsCallEvent(TAG_IMS_CALL_START_FAILED, session, reasonInfo);
    }

    public void writeOnImsCallTerminated(ImsCallSession session, ImsReasonInfo reasonInfo) {
        writeImsCallEvent(TAG_IMS_CALL_TERMINATED, session, reasonInfo);
    }

    public void writeOnImsCallHeld(ImsCallSession session) {
        writeImsCallEvent(TAG_IMS_CALL_HELD, session);
    }

    public void writeOnImsCallHoldReceived(ImsCallSession session) {
        writeImsCallEvent(TAG_IMS_CALL_HOLD_RECEIVED, session);
    }

    public void writeOnImsCallHoldFailed(ImsCallSession session, ImsReasonInfo reasonInfo) {
        writeImsCallEvent(TAG_IMS_CALL_HOLD_FAILED, session, reasonInfo);
    }

    public void writeOnImsCallResumed(ImsCallSession session) {
        writeImsCallEvent(TAG_IMS_CALL_RESUMED, session);
    }

    public void writeOnImsCallResumeReceived(ImsCallSession session) {
        writeImsCallEvent(TAG_IMS_CALL_RESUME_RECEIVED, session);
    }

    public void writeOnImsCallResumeFailed(ImsCallSession session, ImsReasonInfo reasonInfo) {
        writeImsCallEvent(TAG_IMS_CALL_RESUME_FAILED, session, reasonInfo);
    }

    public void writeOnImsCallHandover(ImsCallSession session,
            int srcAccessTech, int targetAccessTech, ImsReasonInfo reasonInfo) {
        Bundle b = imsHandoverToBundle(srcAccessTech, targetAccessTech, reasonInfo);
        writeEvent(TAG_IMS_CALL_HANDOVER, getCallId(session), -1, b);
    }

    public void writeOnImsCallHandoverFailed(ImsCallSession session,
            int srcAccessTech, int targetAccessTech, ImsReasonInfo reasonInfo) {
        Bundle b = imsHandoverToBundle(srcAccessTech, targetAccessTech, reasonInfo);
        writeEvent(TAG_IMS_CALL_HANDOVER_FAILED, getCallId(session), -1, b);
    }

    /**
     * Extracts the call ID from an ImsSession.
     *
     * @param session The session.
     * @return The call ID for the session, or -1 if none was found.
     */
    private int getCallId(ImsCallSession session) {
        if (session == null) {
            return -1;
        }

        try {
            return Integer.parseInt(session.getCallId());
        } catch (NumberFormatException nfe) {
            return -1;
        }
    }

    private Bundle imsHandoverToBundle(int srcAccessTech, int targetAccessTech,
            ImsReasonInfo reasonInfo) {
        Bundle b = new Bundle();
        b.putInt(DATA_KEY_SRC_TECH, srcAccessTech);
        b.putInt(DATA_KEY_TARGET_TECH, targetAccessTech);
        b.putInt(DATA_KEY_REASONINFO_CODE, reasonInfo.mCode);
        b.putInt(DATA_KEY_REASONINFO_EXTRA_CODE, reasonInfo.mExtraCode);
        b.putString(DATA_KEY_REASONINFO_EXTRA_MESSAGE, reasonInfo.mExtraMessage);
        return b;
    }
}
