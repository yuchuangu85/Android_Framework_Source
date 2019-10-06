/*
 * Copyright (C) 2006 The Android Open Source Project
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

import static com.android.internal.telephony.IccSmsInterfaceManager.SMS_MESSAGE_PERIOD_NOT_SPECIFIED;
import static com.android.internal.telephony.IccSmsInterfaceManager.SMS_MESSAGE_PRIORITY_NOT_SPECIFIED;

import android.app.Activity;
import android.app.PendingIntent;
import android.app.PendingIntent.CanceledException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.Message;
import android.os.UserManager;
import android.provider.Telephony.Sms;
import android.provider.Telephony.Sms.Intents;
import android.telephony.Rlog;
import android.telephony.ServiceState;
import android.telephony.SmsManager;
import android.telephony.SmsMessage;
import android.util.Pair;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.telephony.cdma.CdmaInboundSmsHandler;
import com.android.internal.telephony.cdma.CdmaSMSDispatcher;
import com.android.internal.telephony.gsm.GsmInboundSmsHandler;
import com.android.internal.telephony.gsm.GsmSMSDispatcher;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;

/**
 *
 */
public class SmsDispatchersController extends Handler {
    private static final String TAG = "SmsDispatchersController";
    private static final boolean VDBG = false; // STOPSHIP if true

    /** Radio is ON */
    private static final int EVENT_RADIO_ON = 11;

    /** IMS registration/SMS format changed */
    private static final int EVENT_IMS_STATE_CHANGED = 12;

    /** Callback from RIL_REQUEST_IMS_REGISTRATION_STATE */
    private static final int EVENT_IMS_STATE_DONE = 13;

    /** Service state changed */
    private static final int EVENT_SERVICE_STATE_CHANGED = 14;

    /** Purge old message segments */
    private static final int EVENT_PARTIAL_SEGMENT_TIMER_EXPIRY = 15;

    /** User unlocked the device */
    private static final int EVENT_USER_UNLOCKED = 16;

    /** InboundSmsHandler exited WaitingState */
    protected static final int EVENT_SMS_HANDLER_EXITING_WAITING_STATE = 17;

    /** Delete any partial message segments after being IN_SERVICE for 1 day. */
    private static final long PARTIAL_SEGMENT_WAIT_DURATION = (long) (60 * 60 * 1000) * 24;
    /** Constant for invalid time */
    private static final long INVALID_TIME = -1;
    /** Time at which last IN_SERVICE event was received */
    private long mLastInServiceTime = INVALID_TIME;
    /** Current IN_SERVICE duration */
    private long mCurrentWaitElapsedDuration = 0;
    /** Time at which the current PARTIAL_SEGMENT_WAIT_DURATION timer was started */
    private long mCurrentWaitStartTime = INVALID_TIME;

    private SMSDispatcher mCdmaDispatcher;
    private SMSDispatcher mGsmDispatcher;
    private ImsSmsDispatcher mImsSmsDispatcher;

    private GsmInboundSmsHandler mGsmInboundSmsHandler;
    private CdmaInboundSmsHandler mCdmaInboundSmsHandler;

    private Phone mPhone;
    /** Outgoing message counter. Shared by all dispatchers. */
    private final SmsUsageMonitor mUsageMonitor;
    private final CommandsInterface mCi;
    private final Context mContext;

    /** true if IMS is registered and sms is supported, false otherwise.*/
    private boolean mIms = false;
    private String mImsSmsFormat = SmsConstants.FORMAT_UNKNOWN;

    public SmsDispatchersController(Phone phone, SmsStorageMonitor storageMonitor,
            SmsUsageMonitor usageMonitor) {
        Rlog.d(TAG, "SmsDispatchersController created");

        mContext = phone.getContext();
        mUsageMonitor = usageMonitor;
        mCi = phone.mCi;
        mPhone = phone;

        // Create dispatchers, inbound SMS handlers and
        // broadcast undelivered messages in raw table.
        mImsSmsDispatcher = new ImsSmsDispatcher(phone, this);
        mCdmaDispatcher = new CdmaSMSDispatcher(phone, this);
        mGsmInboundSmsHandler = GsmInboundSmsHandler.makeInboundSmsHandler(phone.getContext(),
                storageMonitor, phone);
        mCdmaInboundSmsHandler = CdmaInboundSmsHandler.makeInboundSmsHandler(phone.getContext(),
                storageMonitor, phone, (CdmaSMSDispatcher) mCdmaDispatcher);
        mGsmDispatcher = new GsmSMSDispatcher(phone, this, mGsmInboundSmsHandler);
        SmsBroadcastUndelivered.initialize(phone.getContext(),
                mGsmInboundSmsHandler, mCdmaInboundSmsHandler);
        InboundSmsHandler.registerNewMessageNotificationActionHandler(phone.getContext());

        mCi.registerForOn(this, EVENT_RADIO_ON, null);
        mCi.registerForImsNetworkStateChanged(this, EVENT_IMS_STATE_CHANGED, null);

        UserManager userManager = (UserManager) mContext.getSystemService(Context.USER_SERVICE);
        if (userManager.isUserUnlocked()) {
            if (VDBG) {
                logd("SmsDispatchersController: user unlocked; registering for service"
                        + "state changed");
            }
            mPhone.registerForServiceStateChanged(this, EVENT_SERVICE_STATE_CHANGED, null);
            resetPartialSegmentWaitTimer();
        } else {
            if (VDBG) {
                logd("SmsDispatchersController: user locked; waiting for USER_UNLOCKED");
            }
            IntentFilter userFilter = new IntentFilter();
            userFilter.addAction(Intent.ACTION_USER_UNLOCKED);
            mContext.registerReceiver(mBroadcastReceiver, userFilter);
        }
    }

    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(final Context context, Intent intent) {
            Rlog.d(TAG, "Received broadcast " + intent.getAction());
            if (Intent.ACTION_USER_UNLOCKED.equals(intent.getAction())) {
                sendMessage(obtainMessage(EVENT_USER_UNLOCKED));
            }
        }
    };

    public void dispose() {
        mCi.unregisterForOn(this);
        mCi.unregisterForImsNetworkStateChanged(this);
        mPhone.unregisterForServiceStateChanged(this);
        mGsmDispatcher.dispose();
        mCdmaDispatcher.dispose();
        mGsmInboundSmsHandler.dispose();
        mCdmaInboundSmsHandler.dispose();
    }

    /**
     * Handles events coming from the phone stack. Overridden from handler.
     *
     * @param msg the message to handle
     */
    @Override
    public void handleMessage(Message msg) {
        AsyncResult ar;

        switch (msg.what) {
            case EVENT_RADIO_ON:
            case EVENT_IMS_STATE_CHANGED: // received unsol
                mCi.getImsRegistrationState(this.obtainMessage(EVENT_IMS_STATE_DONE));
                break;

            case EVENT_IMS_STATE_DONE:
                ar = (AsyncResult) msg.obj;

                if (ar.exception == null) {
                    updateImsInfo(ar);
                } else {
                    Rlog.e(TAG, "IMS State query failed with exp "
                            + ar.exception);
                }
                break;

            case EVENT_SERVICE_STATE_CHANGED:
            case EVENT_SMS_HANDLER_EXITING_WAITING_STATE:
                reevaluateTimerStatus();
                break;

            case EVENT_PARTIAL_SEGMENT_TIMER_EXPIRY:
                handlePartialSegmentTimerExpiry((Long) msg.obj);
                break;

            case EVENT_USER_UNLOCKED:
                if (VDBG) {
                    logd("handleMessage: EVENT_USER_UNLOCKED");
                }
                mPhone.registerForServiceStateChanged(this, EVENT_SERVICE_STATE_CHANGED, null);
                resetPartialSegmentWaitTimer();
                break;

            default:
                if (isCdmaMo()) {
                    mCdmaDispatcher.handleMessage(msg);
                } else {
                    mGsmDispatcher.handleMessage(msg);
                }
        }
    }

    private void reevaluateTimerStatus() {
        long currentTime = System.currentTimeMillis();

        // Remove unhandled timer expiry message. A new message will be posted if needed.
        removeMessages(EVENT_PARTIAL_SEGMENT_TIMER_EXPIRY);
        // Update timer duration elapsed time (add time since last IN_SERVICE to now).
        // This is needed for IN_SERVICE as well as OUT_OF_SERVICE because same events can be
        // received back to back
        if (mLastInServiceTime != INVALID_TIME) {
            mCurrentWaitElapsedDuration += (currentTime - mLastInServiceTime);
        }

        if (VDBG) {
            logd("reevaluateTimerStatus: currentTime: " + currentTime
                    + " mCurrentWaitElapsedDuration: " + mCurrentWaitElapsedDuration);
        }

        if (mCurrentWaitElapsedDuration > PARTIAL_SEGMENT_WAIT_DURATION) {
            // handle this event as timer expiry
            handlePartialSegmentTimerExpiry(mCurrentWaitStartTime);
        } else {
            if (isInService()) {
                handleInService(currentTime);
            } else {
                handleOutOfService(currentTime);
            }
        }
    }

    private void handleInService(long currentTime) {
        if (VDBG) {
            logd("handleInService: timer expiry in "
                    + (PARTIAL_SEGMENT_WAIT_DURATION - mCurrentWaitElapsedDuration) + "ms");
        }

        // initialize mCurrentWaitStartTime if needed
        if (mCurrentWaitStartTime == INVALID_TIME) mCurrentWaitStartTime = currentTime;

        // Post a message for timer expiry time. mCurrentWaitElapsedDuration is the duration already
        // elapsed from the timer.
        sendMessageDelayed(
                obtainMessage(EVENT_PARTIAL_SEGMENT_TIMER_EXPIRY, mCurrentWaitStartTime),
                PARTIAL_SEGMENT_WAIT_DURATION - mCurrentWaitElapsedDuration);

        // update mLastInServiceTime as the current time
        mLastInServiceTime = currentTime;
    }

    private void handleOutOfService(long currentTime) {
        if (VDBG) {
            logd("handleOutOfService: currentTime: " + currentTime
                    + " mCurrentWaitElapsedDuration: " + mCurrentWaitElapsedDuration);
        }

        // mLastInServiceTime is not relevant now since state is OUT_OF_SERVICE; set it to INVALID
        mLastInServiceTime = INVALID_TIME;
    }

    private void handlePartialSegmentTimerExpiry(long waitTimerStart) {
        if (mGsmInboundSmsHandler.getCurrentState().getName().equals("WaitingState")
                || mCdmaInboundSmsHandler.getCurrentState().getName().equals("WaitingState")) {
            logd("handlePartialSegmentTimerExpiry: ignoring timer expiry as InboundSmsHandler is"
                    + " in WaitingState");
            return;
        }

        if (VDBG) {
            logd("handlePartialSegmentTimerExpiry: calling scanRawTable()");
        }
        // Timer expired. This indicates that device has been in service for
        // PARTIAL_SEGMENT_WAIT_DURATION since waitTimerStart. Delete orphaned message segments
        // older than waitTimerStart.
        SmsBroadcastUndelivered.scanRawTable(mContext, mCdmaInboundSmsHandler,
                mGsmInboundSmsHandler, waitTimerStart);
        if (VDBG) {
            logd("handlePartialSegmentTimerExpiry: scanRawTable() done");
        }

        resetPartialSegmentWaitTimer();
    }

    private void resetPartialSegmentWaitTimer() {
        long currentTime = System.currentTimeMillis();

        removeMessages(EVENT_PARTIAL_SEGMENT_TIMER_EXPIRY);
        if (isInService()) {
            if (VDBG) {
                logd("resetPartialSegmentWaitTimer: currentTime: " + currentTime
                        + " IN_SERVICE");
            }
            mCurrentWaitStartTime = currentTime;
            mLastInServiceTime = currentTime;
            sendMessageDelayed(
                    obtainMessage(EVENT_PARTIAL_SEGMENT_TIMER_EXPIRY, mCurrentWaitStartTime),
                    PARTIAL_SEGMENT_WAIT_DURATION);
        } else {
            if (VDBG) {
                logd("resetPartialSegmentWaitTimer: currentTime: " + currentTime
                        + " not IN_SERVICE");
            }
            mCurrentWaitStartTime = INVALID_TIME;
            mLastInServiceTime = INVALID_TIME;
        }

        mCurrentWaitElapsedDuration = 0;
    }

    private boolean isInService() {
        ServiceState serviceState = mPhone.getServiceState();
        return serviceState != null && serviceState.getState() == ServiceState.STATE_IN_SERVICE;
    }

    private void setImsSmsFormat(int format) {
        switch (format) {
            case PhoneConstants.PHONE_TYPE_GSM:
                mImsSmsFormat = SmsConstants.FORMAT_3GPP;
                break;
            case PhoneConstants.PHONE_TYPE_CDMA:
                mImsSmsFormat = SmsConstants.FORMAT_3GPP2;
                break;
            default:
                mImsSmsFormat = SmsConstants.FORMAT_UNKNOWN;
                break;
        }
    }

    private void updateImsInfo(AsyncResult ar) {
        int[] responseArray = (int[]) ar.result;
        setImsSmsFormat(responseArray[1]);
        mIms = responseArray[0] == 1 && !SmsConstants.FORMAT_UNKNOWN.equals(mImsSmsFormat);
        Rlog.d(TAG, "IMS registration state: " + mIms + " format: " + mImsSmsFormat);
    }

    /**
     * Inject an SMS PDU into the android platform only if it is class 1.
     *
     * @param pdu is the byte array of pdu to be injected into android telephony layer
     * @param format is the format of SMS pdu (3gpp or 3gpp2)
     * @param callback if not NULL this callback is triggered when the message is successfully
     *                 received by the android telephony layer. This callback is triggered at
     *                 the same time an SMS received from radio is responded back.
     */
    @VisibleForTesting
    public void injectSmsPdu(byte[] pdu, String format, SmsInjectionCallback callback) {
        // TODO We need to decide whether we should allow injecting GSM(3gpp)
        // SMS pdus when the phone is camping on CDMA(3gpp2) network and vice versa.
        android.telephony.SmsMessage msg =
                android.telephony.SmsMessage.createFromPdu(pdu, format);
        injectSmsPdu(msg, format, callback, false /* ignoreClass */);
    }

    /**
     * Inject an SMS PDU into the android platform.
     *
     * @param msg is the {@link SmsMessage} to be injected into android telephony layer
     * @param format is the format of SMS pdu (3gpp or 3gpp2)
     * @param callback if not NULL this callback is triggered when the message is successfully
     *                 received by the android telephony layer. This callback is triggered at
     *                 the same time an SMS received from radio is responded back.
     * @param ignoreClass if set to false, this method will inject class 1 sms only.
     */
    @VisibleForTesting
    public void injectSmsPdu(SmsMessage msg, String format, SmsInjectionCallback callback,
            boolean ignoreClass) {
        Rlog.d(TAG, "SmsDispatchersController:injectSmsPdu");
        try {
            if (msg == null) {
                Rlog.e(TAG, "injectSmsPdu: createFromPdu returned null");
                callback.onSmsInjectedResult(Intents.RESULT_SMS_GENERIC_ERROR);
                return;
            }

            if (!ignoreClass
                    && msg.getMessageClass() != android.telephony.SmsMessage.MessageClass.CLASS_1) {
                Rlog.e(TAG, "injectSmsPdu: not class 1");
                callback.onSmsInjectedResult(Intents.RESULT_SMS_GENERIC_ERROR);
                return;
            }

            AsyncResult ar = new AsyncResult(callback, msg, null);

            if (format.equals(SmsConstants.FORMAT_3GPP)) {
                Rlog.i(TAG, "SmsDispatchersController:injectSmsText Sending msg=" + msg
                        + ", format=" + format + "to mGsmInboundSmsHandler");
                mGsmInboundSmsHandler.sendMessage(InboundSmsHandler.EVENT_INJECT_SMS, ar);
            } else if (format.equals(SmsConstants.FORMAT_3GPP2)) {
                Rlog.i(TAG, "SmsDispatchersController:injectSmsText Sending msg=" + msg
                        + ", format=" + format + "to mCdmaInboundSmsHandler");
                mCdmaInboundSmsHandler.sendMessage(InboundSmsHandler.EVENT_INJECT_SMS, ar);
            } else {
                // Invalid pdu format.
                Rlog.e(TAG, "Invalid pdu format: " + format);
                callback.onSmsInjectedResult(Intents.RESULT_SMS_GENERIC_ERROR);
            }
        } catch (Exception e) {
            Rlog.e(TAG, "injectSmsPdu failed: ", e);
            callback.onSmsInjectedResult(Intents.RESULT_SMS_GENERIC_ERROR);
        }
    }

    /**
     * Retry the message along to the radio.
     *
     * @param tracker holds the SMS message to send
     */
    public void sendRetrySms(SMSDispatcher.SmsTracker tracker) {
        String oldFormat = tracker.mFormat;

        // newFormat will be based on voice technology
        String newFormat =
                (PhoneConstants.PHONE_TYPE_CDMA == mPhone.getPhoneType())
                        ? mCdmaDispatcher.getFormat() : mGsmDispatcher.getFormat();

        // was previously sent sms format match with voice tech?
        if (oldFormat.equals(newFormat)) {
            if (isCdmaFormat(newFormat)) {
                Rlog.d(TAG, "old format matched new format (cdma)");
                mCdmaDispatcher.sendSms(tracker);
                return;
            } else {
                Rlog.d(TAG, "old format matched new format (gsm)");
                mGsmDispatcher.sendSms(tracker);
                return;
            }
        }

        // format didn't match, need to re-encode.
        HashMap map = tracker.getData();

        // to re-encode, fields needed are:  scAddr, destAddr, and
        //   text if originally sent as sendText or
        //   data and destPort if originally sent as sendData.
        if (!(map.containsKey("scAddr") && map.containsKey("destAddr")
                && (map.containsKey("text")
                || (map.containsKey("data") && map.containsKey("destPort"))))) {
            // should never come here...
            Rlog.e(TAG, "sendRetrySms failed to re-encode per missing fields!");
            tracker.onFailed(mContext, SmsManager.RESULT_ERROR_GENERIC_FAILURE, 0/*errorCode*/);
            return;
        }
        String scAddr = (String) map.get("scAddr");
        String destAddr = (String) map.get("destAddr");

        SmsMessageBase.SubmitPduBase pdu = null;
        //    figure out from tracker if this was sendText/Data
        if (map.containsKey("text")) {
            Rlog.d(TAG, "sms failed was text");
            String text = (String) map.get("text");

            if (isCdmaFormat(newFormat)) {
                Rlog.d(TAG, "old format (gsm) ==> new format (cdma)");
                pdu = com.android.internal.telephony.cdma.SmsMessage.getSubmitPdu(
                        scAddr, destAddr, text, (tracker.mDeliveryIntent != null), null);
            } else {
                Rlog.d(TAG, "old format (cdma) ==> new format (gsm)");
                pdu = com.android.internal.telephony.gsm.SmsMessage.getSubmitPdu(
                        scAddr, destAddr, text, (tracker.mDeliveryIntent != null), null);
            }
        } else if (map.containsKey("data")) {
            Rlog.d(TAG, "sms failed was data");
            byte[] data = (byte[]) map.get("data");
            Integer destPort = (Integer) map.get("destPort");

            if (isCdmaFormat(newFormat)) {
                Rlog.d(TAG, "old format (gsm) ==> new format (cdma)");
                pdu = com.android.internal.telephony.cdma.SmsMessage.getSubmitPdu(
                            scAddr, destAddr, destPort.intValue(), data,
                            (tracker.mDeliveryIntent != null));
            } else {
                Rlog.d(TAG, "old format (cdma) ==> new format (gsm)");
                pdu = com.android.internal.telephony.gsm.SmsMessage.getSubmitPdu(
                            scAddr, destAddr, destPort.intValue(), data,
                            (tracker.mDeliveryIntent != null));
            }
        }

        // replace old smsc and pdu with newly encoded ones
        map.put("smsc", pdu.encodedScAddress);
        map.put("pdu", pdu.encodedMessage);

        SMSDispatcher dispatcher = (isCdmaFormat(newFormat)) ? mCdmaDispatcher : mGsmDispatcher;

        tracker.mFormat = dispatcher.getFormat();
        dispatcher.sendSms(tracker);
    }

    public boolean isIms() {
        return mIms;
    }

    public String getImsSmsFormat() {
        return mImsSmsFormat;
    }

    /**
     * Determines whether or not to use CDMA format for MO SMS.
     * If SMS over IMS is supported, then format is based on IMS SMS format,
     * otherwise format is based on current phone type.
     *
     * @return true if Cdma format should be used for MO SMS, false otherwise.
     */
    protected boolean isCdmaMo() {
        if (!isIms()) {
            // IMS is not registered, use Voice technology to determine SMS format.
            return (PhoneConstants.PHONE_TYPE_CDMA == mPhone.getPhoneType());
        }
        // IMS is registered with SMS support
        return isCdmaFormat(mImsSmsFormat);
    }

    /**
     * Determines whether or not format given is CDMA format.
     *
     * @param format
     * @return true if format given is CDMA format, false otherwise.
     */
    public boolean isCdmaFormat(String format) {
        return (mCdmaDispatcher.getFormat().equals(format));
    }

    /**
     * Send a data based SMS to a specific application port.
     *
     * @param callingPackage the package name of the calling app
     * @param destAddr the address to send the message to
     * @param scAddr is the service center address or null to use
     *  the current default SMSC
     * @param destPort the port to deliver the message to
     * @param data the body of the message to send
     * @param sentIntent if not NULL this <code>PendingIntent</code> is
     *  broadcast when the message is successfully sent, or failed.
     *  The result code will be <code>Activity.RESULT_OK<code> for success,
     *  or one of these errors:<br>
     *  <code>RESULT_ERROR_GENERIC_FAILURE</code><br>
     *  <code>RESULT_ERROR_RADIO_OFF</code><br>
     *  <code>RESULT_ERROR_NULL_PDU</code><br>
     *  <code>RESULT_ERROR_NO_SERVICE</code><br>.
     *  For <code>RESULT_ERROR_GENERIC_FAILURE</code> the sentIntent may include
     *  the extra "errorCode" containing a radio technology specific value,
     *  generally only useful for troubleshooting.<br>
     *  The per-application based SMS control checks sentIntent. If sentIntent
     *  is NULL the caller will be checked against all unknown applications,
     *  which cause smaller number of SMS to be sent in checking period.
     * @param deliveryIntent if not NULL this <code>PendingIntent</code> is
     *  broadcast when the message is delivered to the recipient.  The
     *  raw pdu of the status report is in the extended data ("pdu").
     */
    protected void sendData(String callingPackage, String destAddr, String scAddr, int destPort,
            byte[] data, PendingIntent sentIntent, PendingIntent deliveryIntent, boolean isForVvm) {
        if (mImsSmsDispatcher.isAvailable()) {
            mImsSmsDispatcher.sendData(callingPackage, destAddr, scAddr, destPort, data, sentIntent,
                    deliveryIntent, isForVvm);
        } else if (isCdmaMo()) {
            mCdmaDispatcher.sendData(callingPackage, destAddr, scAddr, destPort, data, sentIntent,
                    deliveryIntent, isForVvm);
        } else {
            mGsmDispatcher.sendData(callingPackage, destAddr, scAddr, destPort, data, sentIntent,
                    deliveryIntent, isForVvm);
        }
    }

    /**
     * Send a text based SMS.
     *  @param destAddr the address to send the message to
     * @param scAddr is the service center address or null to use
     *  the current default SMSC
     * @param text the body of the message to send
     * @param sentIntent if not NULL this <code>PendingIntent</code> is
     *  broadcast when the message is successfully sent, or failed.
     *  The result code will be <code>Activity.RESULT_OK<code> for success,
     *  or one of these errors:<br>
     *  <code>RESULT_ERROR_GENERIC_FAILURE</code><br>
     *  <code>RESULT_ERROR_RADIO_OFF</code><br>
     *  <code>RESULT_ERROR_NULL_PDU</code><br>
     *  <code>RESULT_ERROR_NO_SERVICE</code><br>.
     *  For <code>RESULT_ERROR_GENERIC_FAILURE</code> the sentIntent may include
     *  the extra "errorCode" containing a radio technology specific value,
     *  generally only useful for troubleshooting.<br>
     *  The per-application based SMS control checks sentIntent. If sentIntent
     *  is NULL the caller will be checked against all unknown applications,
     *  which cause smaller number of SMS to be sent in checking period.
     * @param deliveryIntent if not NULL this <code>PendingIntent</code> is
     *  broadcast when the message is delivered to the recipient.  The
     * @param messageUri optional URI of the message if it is already stored in the system
     * @param callingPkg the calling package name
     * @param persistMessage whether to save the sent message into SMS DB for a
     *   non-default SMS app.
     * @param priority Priority level of the message
     *  Refer specification See 3GPP2 C.S0015-B, v2.0, table 4.5.9-1
     *  ---------------------------------
     *  PRIORITY      | Level of Priority
     *  ---------------------------------
     *      '00'      |     Normal
     *      '01'      |     Interactive
     *      '10'      |     Urgent
     *      '11'      |     Emergency
     *  ----------------------------------
     *  Any Other values included Negative considered as Invalid Priority Indicator of the message.
     * @param expectMore is a boolean to indicate the sending messages through same link or not.
     * @param validityPeriod Validity Period of the message in mins.
     *  Refer specification 3GPP TS 23.040 V6.8.1 section 9.2.3.12.1.
     *  Validity Period(Minimum) -> 5 mins
     *  Validity Period(Maximum) -> 635040 mins(i.e.63 weeks).
     *  Any Other values included Negative considered as Invalid Validity Period of the message.
     */
    public void sendText(String destAddr, String scAddr, String text, PendingIntent sentIntent,
            PendingIntent deliveryIntent, Uri messageUri, String callingPkg, boolean persistMessage,
            int priority, boolean expectMore, int validityPeriod, boolean isForVvm) {
        if (mImsSmsDispatcher.isAvailable() || mImsSmsDispatcher.isEmergencySmsSupport(destAddr)) {
            mImsSmsDispatcher.sendText(destAddr, scAddr, text, sentIntent, deliveryIntent,
                    messageUri, callingPkg, persistMessage, SMS_MESSAGE_PRIORITY_NOT_SPECIFIED,
                    false /*expectMore*/, SMS_MESSAGE_PERIOD_NOT_SPECIFIED, isForVvm);
        } else {
            if (isCdmaMo()) {
                mCdmaDispatcher.sendText(destAddr, scAddr, text, sentIntent, deliveryIntent,
                        messageUri, callingPkg, persistMessage, priority, expectMore,
                        validityPeriod, isForVvm);
            } else {
                mGsmDispatcher.sendText(destAddr, scAddr, text, sentIntent, deliveryIntent,
                        messageUri, callingPkg, persistMessage, priority, expectMore,
                        validityPeriod, isForVvm);
            }
        }
    }

    /**
     * Send a multi-part text based SMS.
     *  @param destAddr the address to send the message to
     * @param scAddr is the service center address or null to use
     *   the current default SMSC
     * @param parts an <code>ArrayList</code> of strings that, in order,
     *   comprise the original message
     * @param sentIntents if not null, an <code>ArrayList</code> of
     *   <code>PendingIntent</code>s (one for each message part) that is
     *   broadcast when the corresponding message part has been sent.
     *   The result code will be <code>Activity.RESULT_OK<code> for success,
     *   or one of these errors:
     *   <code>RESULT_ERROR_GENERIC_FAILURE</code>
     *   <code>RESULT_ERROR_RADIO_OFF</code>
     *   <code>RESULT_ERROR_NULL_PDU</code>
     *   <code>RESULT_ERROR_NO_SERVICE</code>.
     *  The per-application based SMS control checks sentIntent. If sentIntent
     *  is NULL the caller will be checked against all unknown applications,
     *  which cause smaller number of SMS to be sent in checking period.
     * @param deliveryIntents if not null, an <code>ArrayList</code> of
     *   <code>PendingIntent</code>s (one for each message part) that is
     *   broadcast when the corresponding message part has been delivered
     *   to the recipient.  The raw pdu of the status report is in the
     * @param messageUri optional URI of the message if it is already stored in the system
     * @param callingPkg the calling package name
     * @param persistMessage whether to save the sent message into SMS DB for a
     *   non-default SMS app.
     * @param priority Priority level of the message
     *  Refer specification See 3GPP2 C.S0015-B, v2.0, table 4.5.9-1
     *  ---------------------------------
     *  PRIORITY      | Level of Priority
     *  ---------------------------------
     *      '00'      |     Normal
     *      '01'      |     Interactive
     *      '10'      |     Urgent
     *      '11'      |     Emergency
     *  ----------------------------------
     *  Any Other values included Negative considered as Invalid Priority Indicator of the message.
     * @param expectMore is a boolean to indicate the sending messages through same link or not.
     * @param validityPeriod Validity Period of the message in mins.
     *  Refer specification 3GPP TS 23.040 V6.8.1 section 9.2.3.12.1.
     *  Validity Period(Minimum) -> 5 mins
     *  Validity Period(Maximum) -> 635040 mins(i.e.63 weeks).
     *  Any Other values included Negative considered as Invalid Validity Period of the message.

     */
    protected void sendMultipartText(String destAddr, String scAddr,
            ArrayList<String> parts, ArrayList<PendingIntent> sentIntents,
            ArrayList<PendingIntent> deliveryIntents, Uri messageUri, String callingPkg,
            boolean persistMessage, int priority, boolean expectMore, int validityPeriod) {
        if (mImsSmsDispatcher.isAvailable()) {
            mImsSmsDispatcher.sendMultipartText(destAddr, scAddr, parts, sentIntents,
                    deliveryIntents, messageUri, callingPkg, persistMessage,
                    SMS_MESSAGE_PRIORITY_NOT_SPECIFIED,
                    false /*expectMore*/, SMS_MESSAGE_PERIOD_NOT_SPECIFIED);
        } else {
            if (isCdmaMo()) {
                mCdmaDispatcher.sendMultipartText(destAddr, scAddr, parts, sentIntents,
                        deliveryIntents, messageUri, callingPkg, persistMessage, priority,
                        expectMore, validityPeriod);
            } else {
                mGsmDispatcher.sendMultipartText(destAddr, scAddr, parts, sentIntents,
                        deliveryIntents, messageUri, callingPkg, persistMessage, priority,
                        expectMore, validityPeriod);
            }
        }
    }

    /**
     * Returns the premium SMS permission for the specified package. If the package has never
     * been seen before, the default {@link SmsUsageMonitor#PREMIUM_SMS_PERMISSION_ASK_USER}
     * will be returned.
     * @param packageName the name of the package to query permission
     * @return one of {@link SmsUsageMonitor#PREMIUM_SMS_PERMISSION_UNKNOWN},
     *  {@link SmsUsageMonitor#PREMIUM_SMS_PERMISSION_ASK_USER},
     *  {@link SmsUsageMonitor#PREMIUM_SMS_PERMISSION_NEVER_ALLOW}, or
     *  {@link SmsUsageMonitor#PREMIUM_SMS_PERMISSION_ALWAYS_ALLOW}
     */
    public int getPremiumSmsPermission(String packageName) {
        return mUsageMonitor.getPremiumSmsPermission(packageName);
    }

    /**
     * Sets the premium SMS permission for the specified package and save the value asynchronously
     * to persistent storage.
     * @param packageName the name of the package to set permission
     * @param permission one of {@link SmsUsageMonitor#PREMIUM_SMS_PERMISSION_ASK_USER},
     *  {@link SmsUsageMonitor#PREMIUM_SMS_PERMISSION_NEVER_ALLOW}, or
     *  {@link SmsUsageMonitor#PREMIUM_SMS_PERMISSION_ALWAYS_ALLOW}
     */
    public void setPremiumSmsPermission(String packageName, int permission) {
        mUsageMonitor.setPremiumSmsPermission(packageName, permission);
    }

    public SmsUsageMonitor getUsageMonitor() {
        return mUsageMonitor;
    }

    /**
     * Triggers the correct method for handling the sms status report based on the format.
     *
     * @param tracker the sms tracker.
     * @param format the format.
     * @param pdu the pdu of the report.
     * @return a Pair in which the first boolean is whether the report was handled successfully
     *          or not and the second boolean is whether processing the sms is complete and the
     *          tracker no longer need to be kept track of, false if we should expect more callbacks
     *          and the tracker should be kept.
     */
    public Pair<Boolean, Boolean> handleSmsStatusReport(SMSDispatcher.SmsTracker tracker,
            String format, byte[] pdu) {
        if (isCdmaFormat(format)) {
            return handleCdmaStatusReport(tracker, format, pdu);
        } else {
            return handleGsmStatusReport(tracker, format, pdu);
        }
    }

    private Pair<Boolean, Boolean> handleCdmaStatusReport(SMSDispatcher.SmsTracker tracker,
            String format, byte[] pdu) {
        tracker.updateSentMessageStatus(mContext, Sms.STATUS_COMPLETE);
        boolean success = triggerDeliveryIntent(tracker, format, pdu);
        return new Pair(success, true /* complete */);
    }

    private Pair<Boolean, Boolean> handleGsmStatusReport(SMSDispatcher.SmsTracker tracker,
            String format, byte[] pdu) {
        com.android.internal.telephony.gsm.SmsMessage sms =
                com.android.internal.telephony.gsm.SmsMessage.newFromCDS(pdu);
        boolean complete = false;
        boolean success = false;
        if (sms != null) {
            int tpStatus = sms.getStatus();
            if(tpStatus >= Sms.STATUS_FAILED || tpStatus < Sms.STATUS_PENDING ) {
                // Update the message status (COMPLETE or FAILED)
                tracker.updateSentMessageStatus(mContext, tpStatus);
                complete = true;
            }
            success = triggerDeliveryIntent(tracker, format, pdu);
        }
        return new Pair(success, complete);
    }

    private boolean triggerDeliveryIntent(SMSDispatcher.SmsTracker tracker, String format,
                                          byte[] pdu) {
        PendingIntent intent = tracker.mDeliveryIntent;
        Intent fillIn = new Intent();
        fillIn.putExtra("pdu", pdu);
        fillIn.putExtra("format", format);
        try {
            intent.send(mContext, Activity.RESULT_OK, fillIn);
            return true;
        } catch (CanceledException ex) {
            return false;
        }
    }


    public interface SmsInjectionCallback {
        void onSmsInjectedResult(int result);
    }

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        mGsmInboundSmsHandler.dump(fd, pw, args);
        mCdmaInboundSmsHandler.dump(fd, pw, args);
    }

    private void logd(String msg) {
        Rlog.d(TAG, msg);
    }
}
