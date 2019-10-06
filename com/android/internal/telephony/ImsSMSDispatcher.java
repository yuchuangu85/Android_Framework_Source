/*
 * Copyright (C) 2018 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.internal.telephony;

import android.content.Context;
import android.os.Binder;
import android.os.PersistableBundle;
import android.os.RemoteException;
import android.provider.Telephony.Sms.Intents;
import android.telephony.CarrierConfigManager;
import android.telephony.PhoneNumberUtils;
import android.telephony.Rlog;
import android.telephony.ServiceState;
import android.telephony.ims.ImsReasonInfo;
import android.telephony.ims.aidl.IImsSmsListener;
import android.telephony.ims.feature.MmTelFeature;
import android.telephony.ims.stub.ImsRegistrationImplBase;
import android.telephony.ims.stub.ImsSmsImplBase;
import android.telephony.ims.stub.ImsSmsImplBase.SendStatusResult;
import android.util.Pair;

import com.android.ims.ImsException;
import com.android.ims.ImsManager;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.telephony.GsmAlphabet.TextEncodingDetails;
import com.android.internal.telephony.metrics.TelephonyMetrics;
import com.android.internal.telephony.util.SMSDispatcherUtil;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Responsible for communications with {@link com.android.ims.ImsManager} to send/receive messages
 * over IMS.
 * @hide
 */
public class ImsSmsDispatcher extends SMSDispatcher {

    private static final String TAG = "ImsSmsDispacher";

    @VisibleForTesting
    public Map<Integer, SmsTracker> mTrackers = new ConcurrentHashMap<>();
    @VisibleForTesting
    public AtomicInteger mNextToken = new AtomicInteger();
    private final Object mLock = new Object();
    private volatile boolean mIsSmsCapable;
    private volatile boolean mIsImsServiceUp;
    private volatile boolean mIsRegistered;
    private final ImsManager.Connector mImsManagerConnector;
    /** Telephony metrics instance for logging metrics event */
    private TelephonyMetrics mMetrics = TelephonyMetrics.getInstance();

    /**
     * Listen to the IMS service state change
     *
     */
    private android.telephony.ims.ImsMmTelManager.RegistrationCallback mRegistrationCallback =
            new android.telephony.ims.ImsMmTelManager.RegistrationCallback() {
                @Override
                public void onRegistered(
                        @ImsRegistrationImplBase.ImsRegistrationTech int imsRadioTech) {
                    Rlog.d(TAG, "onImsConnected imsRadioTech=" + imsRadioTech);
                    synchronized (mLock) {
                        mIsRegistered = true;
                    }
                }

                @Override
                public void onRegistering(
                        @ImsRegistrationImplBase.ImsRegistrationTech int imsRadioTech) {
                    Rlog.d(TAG, "onImsProgressing imsRadioTech=" + imsRadioTech);
                    synchronized (mLock) {
                        mIsRegistered = false;
                    }
                }

                @Override
                public void onUnregistered(ImsReasonInfo info) {
                    Rlog.d(TAG, "onImsDisconnected imsReasonInfo=" + info);
                    synchronized (mLock) {
                        mIsRegistered = false;
                    }
                }
            };

    private android.telephony.ims.ImsMmTelManager.CapabilityCallback mCapabilityCallback =
            new android.telephony.ims.ImsMmTelManager.CapabilityCallback() {
                @Override
                public void onCapabilitiesStatusChanged(
                        MmTelFeature.MmTelCapabilities capabilities) {
                    synchronized (mLock) {
                        mIsSmsCapable = capabilities.isCapable(
                                MmTelFeature.MmTelCapabilities.CAPABILITY_TYPE_SMS);
                    }
                }
    };

    private final IImsSmsListener mImsSmsListener = new IImsSmsListener.Stub() {
        @Override
        public void onSendSmsResult(int token, int messageRef, @SendStatusResult int status,
                int reason) throws RemoteException {
            Rlog.d(TAG, "onSendSmsResult token=" + token + " messageRef=" + messageRef
                    + " status=" + status + " reason=" + reason);
            mMetrics.writeOnImsServiceSmsSolicitedResponse(mPhone.getPhoneId(), status, reason);
            SmsTracker tracker = mTrackers.get(token);
            if (tracker == null) {
                throw new IllegalArgumentException("Invalid token.");
            }
            tracker.mMessageRef = messageRef;
            switch(status) {
                case ImsSmsImplBase.SEND_STATUS_OK:
                    tracker.onSent(mContext);
                    mPhone.notifySmsSent(tracker.mDestAddress);
                    break;
                case ImsSmsImplBase.SEND_STATUS_ERROR:
                    tracker.onFailed(mContext, reason, 0 /* errorCode */);
                    mTrackers.remove(token);
                    break;
                case ImsSmsImplBase.SEND_STATUS_ERROR_RETRY:
                    tracker.mRetryCount += 1;
                    sendSms(tracker);
                    break;
                case ImsSmsImplBase.SEND_STATUS_ERROR_FALLBACK:
                    tracker.mRetryCount += 1;
                    fallbackToPstn(token, tracker);
                    break;
                default:
            }
        }

        @Override
        public void onSmsStatusReportReceived(int token, int messageRef, String format, byte[] pdu)
                throws RemoteException {
            Rlog.d(TAG, "Status report received.");
            SmsTracker tracker = mTrackers.get(token);
            if (tracker == null) {
                throw new RemoteException("Invalid token.");
            }
            Pair<Boolean, Boolean> result = mSmsDispatchersController.handleSmsStatusReport(
                    tracker, format, pdu);
            Rlog.d(TAG, "Status report handle result, success: " + result.first +
                    "complete: " + result.second);
            try {
                getImsManager().acknowledgeSmsReport(
                        token,
                        messageRef,
                        result.first ? ImsSmsImplBase.STATUS_REPORT_STATUS_OK
                                : ImsSmsImplBase.STATUS_REPORT_STATUS_ERROR);
            } catch (ImsException e) {
                Rlog.e(TAG, "Failed to acknowledgeSmsReport(). Error: "
                        + e.getMessage());
            }
            if (result.second) {
                mTrackers.remove(token);
            }
        }

        @Override
        public void onSmsReceived(int token, String format, byte[] pdu) {
            Rlog.d(TAG, "SMS received.");
            android.telephony.SmsMessage message =
                    android.telephony.SmsMessage.createFromPdu(pdu, format);
            mSmsDispatchersController.injectSmsPdu(message, format, result -> {
                Rlog.d(TAG, "SMS handled result: " + result);
                int mappedResult;
                switch (result) {
                    case Intents.RESULT_SMS_HANDLED:
                        mappedResult = ImsSmsImplBase.DELIVER_STATUS_OK;
                        break;
                    case Intents.RESULT_SMS_OUT_OF_MEMORY:
                        mappedResult = ImsSmsImplBase.DELIVER_STATUS_ERROR_NO_MEMORY;
                        break;
                    case Intents.RESULT_SMS_UNSUPPORTED:
                        mappedResult = ImsSmsImplBase.DELIVER_STATUS_ERROR_REQUEST_NOT_SUPPORTED;
                        break;
                    default:
                        mappedResult = ImsSmsImplBase.DELIVER_STATUS_ERROR_GENERIC;
                        break;
                }
                try {
                    if (message != null && message.mWrappedSmsMessage != null) {
                        getImsManager().acknowledgeSms(token,
                                message.mWrappedSmsMessage.mMessageRef, mappedResult);
                    } else {
                        Rlog.w(TAG, "SMS Received with a PDU that could not be parsed.");
                        getImsManager().acknowledgeSms(token, 0, mappedResult);
                    }
                } catch (ImsException e) {
                    Rlog.e(TAG, "Failed to acknowledgeSms(). Error: " + e.getMessage());
                }
            }, true);
        }
    };

    public ImsSmsDispatcher(Phone phone, SmsDispatchersController smsDispatchersController) {
        super(phone, smsDispatchersController);

        mImsManagerConnector = new ImsManager.Connector(mContext, mPhone.getPhoneId(),
                new ImsManager.Connector.Listener() {
                    @Override
                    public void connectionReady(ImsManager manager) throws ImsException {
                        Rlog.d(TAG, "ImsManager: connection ready.");
                        synchronized (mLock) {
                            setListeners();
                            mIsImsServiceUp = true;
                        }
                    }

                    @Override
                    public void connectionUnavailable() {
                        Rlog.d(TAG, "ImsManager: connection unavailable.");
                        synchronized (mLock) {
                            mIsImsServiceUp = false;
                        }
                    }
                });
        mImsManagerConnector.connect();
    }

    private void setListeners() throws ImsException {
        getImsManager().addRegistrationCallback(mRegistrationCallback);
        getImsManager().addCapabilitiesCallback(mCapabilityCallback);
        getImsManager().setSmsListener(getSmsListener());
        getImsManager().onSmsReady();
    }

    private boolean isLteService() {
        return ((mPhone.getServiceState().getRilVoiceRadioTechnology() ==
            ServiceState.RIL_RADIO_TECHNOLOGY_LTE) && (mPhone.getServiceState().
                getState() == ServiceState.STATE_IN_SERVICE));
    }

    private boolean isLimitedLteService() {
        return ((mPhone.getServiceState().getRilVoiceRadioTechnology() ==
            ServiceState.RIL_RADIO_TECHNOLOGY_LTE) && mPhone.getServiceState().isEmergencyOnly());
    }

    private boolean isEmergencySmsPossible() {
        return isLteService() || isLimitedLteService();
    }

    public boolean isEmergencySmsSupport(String destAddr) {
        PersistableBundle b;
        boolean eSmsCarrierSupport = false;
        if (!PhoneNumberUtils.isLocalEmergencyNumber(mContext, mPhone.getSubId(), destAddr)) {
            Rlog.e(TAG, "Emergency Sms is not supported for: " + Rlog.pii(TAG, destAddr));
            return false;
        }

        final long identity = Binder.clearCallingIdentity();
        try {
            CarrierConfigManager configManager = (CarrierConfigManager) mPhone.getContext()
                    .getSystemService(Context.CARRIER_CONFIG_SERVICE);
            if (configManager == null) {
                Rlog.e(TAG, "configManager is null");
                return false;
            }
            b = configManager.getConfigForSubId(getSubId());
            if (b == null) {
                Rlog.e(TAG, "PersistableBundle is null");
                return false;
            }
            eSmsCarrierSupport = b.getBoolean(
                    CarrierConfigManager.KEY_SUPPORT_EMERGENCY_SMS_OVER_IMS_BOOL);
            boolean lteOrLimitedLte = isEmergencySmsPossible();
            Rlog.i(TAG, "isEmergencySmsSupport emergencySmsCarrierSupport: "
                    + eSmsCarrierSupport + " destAddr: " + Rlog.pii(TAG, destAddr)
                    + " mIsImsServiceUp: " + mIsImsServiceUp + " lteOrLimitedLte: "
                    + lteOrLimitedLte);

            return eSmsCarrierSupport && mIsImsServiceUp && lteOrLimitedLte;
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    public boolean isAvailable() {
        synchronized (mLock) {
            Rlog.d(TAG, "isAvailable: up=" + mIsImsServiceUp + ", reg= " + mIsRegistered
                    + ", cap= " + mIsSmsCapable);
            return mIsImsServiceUp && mIsRegistered && mIsSmsCapable;
        }
    }

    @Override
    protected String getFormat() {
        try {
            return getImsManager().getSmsFormat();
        } catch (ImsException e) {
            Rlog.e(TAG, "Failed to get sms format. Error: " + e.getMessage());
            return SmsConstants.FORMAT_UNKNOWN;
        }
    }

    @Override
    protected boolean shouldBlockSmsForEcbm() {
        // We should not block outgoing SMS during ECM on IMS. It only applies to outgoing CDMA
        // SMS.
        return false;
    }

    @Override
    protected SmsMessageBase.SubmitPduBase getSubmitPdu(String scAddr, String destAddr,
            String message, boolean statusReportRequested, SmsHeader smsHeader, int priority,
            int validityPeriod) {
        return SMSDispatcherUtil.getSubmitPdu(isCdmaMo(), scAddr, destAddr, message,
                statusReportRequested, smsHeader, priority, validityPeriod);
    }

    @Override
    protected SmsMessageBase.SubmitPduBase getSubmitPdu(String scAddr, String destAddr,
            int destPort, byte[] message, boolean statusReportRequested) {
        return SMSDispatcherUtil.getSubmitPdu(isCdmaMo(), scAddr, destAddr, destPort, message,
                statusReportRequested);
    }

    @Override
    protected TextEncodingDetails calculateLength(CharSequence messageBody, boolean use7bitOnly) {
        return SMSDispatcherUtil.calculateLength(isCdmaMo(), messageBody, use7bitOnly);
    }

    @Override
    public void sendSms(SmsTracker tracker) {
        Rlog.d(TAG, "sendSms: "
                + " mRetryCount=" + tracker.mRetryCount
                + " mMessageRef=" + tracker.mMessageRef
                + " SS=" + mPhone.getServiceState().getState());

        // Flag that this Tracker is using the ImsService implementation of SMS over IMS for sending
        // this message. Any fallbacks will happen over CS only.
        tracker.mUsesImsServiceForIms = true;

        HashMap<String, Object> map = tracker.getData();

        byte[] pdu = (byte[]) map.get(MAP_KEY_PDU);
        byte smsc[] = (byte[]) map.get(MAP_KEY_SMSC);
        boolean isRetry = tracker.mRetryCount > 0;
        String format = getFormat();

        if (SmsConstants.FORMAT_3GPP.equals(format) && tracker.mRetryCount > 0) {
            // per TS 23.040 Section 9.2.3.6:  If TP-MTI SMS-SUBMIT (0x01) type
            //   TP-RD (bit 2) is 1 for retry
            //   and TP-MR is set to previously failed sms TP-MR
            if (((0x01 & pdu[0]) == 0x01)) {
                pdu[0] |= 0x04; // TP-RD
                pdu[1] = (byte) tracker.mMessageRef; // TP-MR
            }
        }

        int token = mNextToken.incrementAndGet();
        mTrackers.put(token, tracker);
        try {
            getImsManager().sendSms(
                    token,
                    tracker.mMessageRef,
                    format,
                    smsc != null ? new String(smsc) : null,
                    isRetry,
                    pdu);
            mMetrics.writeImsServiceSendSms(mPhone.getPhoneId(), format,
                    ImsSmsImplBase.SEND_STATUS_OK);
        } catch (ImsException e) {
            Rlog.e(TAG, "sendSms failed. Falling back to PSTN. Error: " + e.getMessage());
            fallbackToPstn(token, tracker);
            mMetrics.writeImsServiceSendSms(mPhone.getPhoneId(), format,
                    ImsSmsImplBase.SEND_STATUS_ERROR_FALLBACK);
        }
    }

    private ImsManager getImsManager() {
        return ImsManager.getInstance(mContext, mPhone.getPhoneId());
    }

    @VisibleForTesting
    public void fallbackToPstn(int token, SmsTracker tracker) {
        mSmsDispatchersController.sendRetrySms(tracker);
        mTrackers.remove(token);
    }

    @Override
    protected boolean isCdmaMo() {
        return mSmsDispatchersController.isCdmaFormat(getFormat());
    }

    @VisibleForTesting
    public IImsSmsListener getSmsListener() {
        return mImsSmsListener;
    }
}
