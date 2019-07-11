/*
 * Copyright (C) 2013 The Android Open Source Project
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

import static android.telephony.TelephonyManager.PHONE_TYPE_CDMA;

import android.app.Activity;
import android.app.ActivityManagerNative;
import android.app.AppOpsManager;
import android.app.BroadcastOptions;
import android.app.PendingIntent;
import android.app.PendingIntent.CanceledException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.UserInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.database.SQLException;
import android.net.Uri;
import android.os.AsyncResult;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.IDeviceIdleController;
import android.os.Message;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;
import android.preference.PreferenceManager;
import android.provider.Telephony;
import android.provider.Telephony.Sms.Intents;
import android.service.carrier.CarrierMessagingService;
import android.service.carrier.ICarrierMessagingCallback;
import android.service.carrier.ICarrierMessagingService;
import android.service.carrier.MessagePdu;
import android.telephony.CarrierMessagingServiceManager;
import android.telephony.Rlog;
import android.telephony.SmsManager;
import android.telephony.SmsMessage;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.text.TextUtils;

import com.android.internal.telephony.uicc.UiccCard;
import com.android.internal.telephony.uicc.UiccController;
import com.android.internal.util.HexDump;
import com.android.internal.util.State;
import com.android.internal.util.StateMachine;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;

/**
 * This class broadcasts incoming SMS messages to interested apps after storing them in
 * the SmsProvider "raw" table and ACKing them to the SMSC. After each message has been
 * broadcast, its parts are removed from the raw table. If the device crashes after ACKing
 * but before the broadcast completes, the pending messages will be rebroadcast on the next boot.
 *
 * <p>The state machine starts in {@link IdleState} state. When the {@link SMSDispatcher} receives a
 * new SMS from the radio, it calls {@link #dispatchNormalMessage},
 * which sends a message to the state machine, causing the wakelock to be acquired in
 * {@link #haltedProcessMessage}, which transitions to {@link DeliveringState} state, where the message
 * is saved to the raw table, then acknowledged via the {@link SMSDispatcher} which called us.
 *
 * <p>After saving the SMS, if the message is complete (either single-part or the final segment
 * of a multi-part SMS), we broadcast the completed PDUs as an ordered broadcast, then transition to
 * {@link WaitingState} state to wait for the broadcast to complete. When the local
 * {@link BroadcastReceiver} is called with the result, it sends {@link #EVENT_BROADCAST_COMPLETE}
 * to the state machine, causing us to either broadcast the next pending message (if one has
 * arrived while waiting for the broadcast to complete), or to transition back to the halted state
 * after all messages are processed. Then the wakelock is released and we wait for the next SMS.
 */
public abstract class InboundSmsHandler extends StateMachine {
    protected static final boolean DBG = true;
    private static final boolean VDBG = false;  // STOPSHIP if true, logs user data

    /** Query projection for checking for duplicate message segments. */
    private static final String[] PDU_PROJECTION = {
            "pdu"
    };

    /** Query projection for combining concatenated message segments. */
    private static final String[] PDU_SEQUENCE_PORT_PROJECTION = {
            "pdu",
            "sequence",
            "destination_port"
    };

    static final int PDU_COLUMN = 0;
    static final int SEQUENCE_COLUMN = 1;
    static final int DESTINATION_PORT_COLUMN = 2;
    static final int DATE_COLUMN = 3;
    static final int REFERENCE_NUMBER_COLUMN = 4;
    static final int COUNT_COLUMN = 5;
    static final int ADDRESS_COLUMN = 6;
    static final int ID_COLUMN = 7;

    static final String SELECT_BY_ID = "_id=?";
    static final String SELECT_BY_REFERENCE = "address=? AND reference_number=? AND count=?";

    /** New SMS received as an AsyncResult. */
    public static final int EVENT_NEW_SMS = 1;

    /** Message type containing a {@link InboundSmsTracker} ready to broadcast to listeners. */
    static final int EVENT_BROADCAST_SMS = 2;

    /** Message from resultReceiver notifying {@link WaitingState} of a completed broadcast. */
    static final int EVENT_BROADCAST_COMPLETE = 3;

    /** Sent on exit from {@link WaitingState} to return to idle after sending all broadcasts. */
    static final int EVENT_RETURN_TO_IDLE = 4;

    /** Release wakelock after a short timeout when returning to idle state. */
    static final int EVENT_RELEASE_WAKELOCK = 5;

    /** Sent by {@link SmsBroadcastUndelivered} after cleaning the raw table. */
    static final int EVENT_START_ACCEPTING_SMS = 6;

    /** Update phone object */
    static final int EVENT_UPDATE_PHONE_OBJECT = 7;

    /** New SMS received as an AsyncResult. */
    public static final int EVENT_INJECT_SMS = 8;

    /** Wakelock release delay when returning to idle state. */
    private static final int WAKELOCK_TIMEOUT = 3000;

    /** URI for raw table of SMS provider. */
    private static final Uri sRawUri = Uri.withAppendedPath(Telephony.Sms.CONTENT_URI, "raw");

    protected final Context mContext;
    private final ContentResolver mResolver;

    /** Special handler for WAP push messages. */
    private final WapPushOverSms mWapPush;

    /** Wake lock to ensure device stays awake while dispatching the SMS intents. */
    final PowerManager.WakeLock mWakeLock;

    /** DefaultState throws an exception or logs an error for unhandled message types. */
    final DefaultState mDefaultState = new DefaultState();

    /** Startup state. Waiting for {@link SmsBroadcastUndelivered} to complete. */
    final StartupState mStartupState = new StartupState();

    /** Idle state. Waiting for messages to process. */
    final IdleState mIdleState = new IdleState();

    /** Delivering state. Saves the PDU in the raw table and acknowledges to SMSC. */
    final DeliveringState mDeliveringState = new DeliveringState();

    /** Broadcasting state. Waits for current broadcast to complete before delivering next. */
    final WaitingState mWaitingState = new WaitingState();

    /** Helper class to check whether storage is available for incoming messages. */
    protected SmsStorageMonitor mStorageMonitor;

    private final boolean mSmsReceiveDisabled;

    protected PhoneBase mPhone;

    protected CellBroadcastHandler mCellBroadcastHandler;

    private UserManager mUserManager;

    IDeviceIdleController mDeviceIdleController;

    /**
     * Create a new SMS broadcast helper.
     * @param name the class name for logging
     * @param context the context of the phone app
     * @param storageMonitor the SmsStorageMonitor to check for storage availability
     */
    protected InboundSmsHandler(String name, Context context, SmsStorageMonitor storageMonitor,
            PhoneBase phone, CellBroadcastHandler cellBroadcastHandler) {
        super(name);

        mContext = context;
        mStorageMonitor = storageMonitor;
        mPhone = phone;
        mCellBroadcastHandler = cellBroadcastHandler;
        mResolver = context.getContentResolver();
        mWapPush = new WapPushOverSms(context);

        boolean smsCapable = mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_sms_capable);
        mSmsReceiveDisabled = !TelephonyManager.from(mContext).getSmsReceiveCapableForPhone(
                mPhone.getPhoneId(), smsCapable);

        PowerManager pm = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
        mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, name);
        mWakeLock.acquire();    // wake lock released after we enter idle state
        mUserManager = (UserManager) mContext.getSystemService(Context.USER_SERVICE);
        mDeviceIdleController = IDeviceIdleController.Stub.asInterface(
                ServiceManager.getService(Context.DEVICE_IDLE_CONTROLLER));

        addState(mDefaultState);
        addState(mStartupState, mDefaultState);
        addState(mIdleState, mDefaultState);
        addState(mDeliveringState, mDefaultState);
            addState(mWaitingState, mDeliveringState);

        setInitialState(mStartupState);
        if (DBG) log("created InboundSmsHandler");
    }

    /**
     * Tell the state machine to quit after processing all messages.
     */
    public void dispose() {
        quit();
    }

    /**
     * Update the phone object when it changes.
     */
    public void updatePhoneObject(PhoneBase phone) {
        sendMessage(EVENT_UPDATE_PHONE_OBJECT, phone);
    }

    /**
     * Dispose of the WAP push object and release the wakelock.
     */
    @Override
    protected void onQuitting() {
        mWapPush.dispose();

        while (mWakeLock.isHeld()) {
            mWakeLock.release();
        }
    }

    // CAF_MSIM Is this used anywhere ? if not remove it
    public PhoneBase getPhone() {
        return mPhone;
    }

    /**
     * This parent state throws an exception (for debug builds) or prints an error for unhandled
     * message types.
     */
    class DefaultState extends State {
        @Override
        public boolean processMessage(Message msg) {
            switch (msg.what) {
                case EVENT_UPDATE_PHONE_OBJECT: {
                    onUpdatePhoneObject((PhoneBase) msg.obj);
                    break;
                }
                default: {
                    String errorText = "processMessage: unhandled message type " + msg.what +
                        " currState=" + getCurrentState().getName();
                    if (Build.IS_DEBUGGABLE) {
                        loge("---- Dumping InboundSmsHandler ----");
                        loge("Total records=" + getLogRecCount());
                        for (int i = Math.max(getLogRecSize() - 20, 0); i < getLogRecSize(); i++) {
                            loge("Rec[%d]: %s\n" + i + getLogRec(i).toString());
                        }
                        loge("---- Dumped InboundSmsHandler ----");

                        throw new RuntimeException(errorText);
                    } else {
                        loge(errorText);
                    }
                    break;
                }
            }
            return HANDLED;
        }
    }

    /**
     * The Startup state waits for {@link SmsBroadcastUndelivered} to process the raw table and
     * notify the state machine to broadcast any complete PDUs that might not have been broadcast.
     */
    class StartupState extends State {
        @Override
        public boolean processMessage(Message msg) {
            log("StartupState.processMessage:" + msg.what);
            switch (msg.what) {
                case EVENT_NEW_SMS:
                case EVENT_INJECT_SMS:
                case EVENT_BROADCAST_SMS:
                    deferMessage(msg);
                    return HANDLED;

                case EVENT_START_ACCEPTING_SMS:
                    transitionTo(mIdleState);
                    return HANDLED;

                case EVENT_BROADCAST_COMPLETE:
                case EVENT_RETURN_TO_IDLE:
                case EVENT_RELEASE_WAKELOCK:
                default:
                    // let DefaultState handle these unexpected message types
                    return NOT_HANDLED;
            }
        }
    }

    /**
     * In the idle state the wakelock is released until a new SM arrives, then we transition
     * to Delivering mode to handle it, acquiring the wakelock on exit.
     */
    class IdleState extends State {
        @Override
        public void enter() {
            if (DBG) log("entering Idle state");
            sendMessageDelayed(EVENT_RELEASE_WAKELOCK, WAKELOCK_TIMEOUT);
        }

        @Override
        public void exit() {
            mWakeLock.acquire();
            if (DBG) log("acquired wakelock, leaving Idle state");
        }

        @Override
        public boolean processMessage(Message msg) {
            log("IdleState.processMessage:" + msg.what);
            if (DBG) log("Idle state processing message type " + msg.what);
            switch (msg.what) {
                case EVENT_NEW_SMS:
                case EVENT_INJECT_SMS:
                case EVENT_BROADCAST_SMS:
                    deferMessage(msg);
                    transitionTo(mDeliveringState);
                    return HANDLED;

                case EVENT_RELEASE_WAKELOCK:
                    mWakeLock.release();
                    if (DBG) {
                        if (mWakeLock.isHeld()) {
                            // this is okay as long as we call release() for every acquire()
                            log("mWakeLock is still held after release");
                        } else {
                            log("mWakeLock released");
                        }
                    }
                    return HANDLED;

                case EVENT_RETURN_TO_IDLE:
                    // already in idle state; ignore
                    return HANDLED;

                case EVENT_BROADCAST_COMPLETE:
                case EVENT_START_ACCEPTING_SMS:
                default:
                    // let DefaultState handle these unexpected message types
                    return NOT_HANDLED;
            }
        }
    }

    /**
     * In the delivering state, the inbound SMS is processed and stored in the raw table.
     * The message is acknowledged before we exit this state. If there is a message to broadcast,
     * transition to {@link WaitingState} state to send the ordered broadcast and wait for the
     * results. When all messages have been processed, the halting state will release the wakelock.
     */
    class DeliveringState extends State {
        @Override
        public void enter() {
            if (DBG) log("entering Delivering state");
        }

        @Override
        public void exit() {
            if (DBG) log("leaving Delivering state");
        }

        @Override
        public boolean processMessage(Message msg) {
            log("DeliveringState.processMessage:" + msg.what);
            switch (msg.what) {
                case EVENT_NEW_SMS:
                    // handle new SMS from RIL
                    handleNewSms((AsyncResult) msg.obj);
                    sendMessage(EVENT_RETURN_TO_IDLE);
                    return HANDLED;

                case EVENT_INJECT_SMS:
                    // handle new injected SMS
                    handleInjectSms((AsyncResult) msg.obj);
                    sendMessage(EVENT_RETURN_TO_IDLE);
                    return HANDLED;

                case EVENT_BROADCAST_SMS:
                    // if any broadcasts were sent, transition to waiting state
                    InboundSmsTracker inboundSmsTracker = (InboundSmsTracker) msg.obj;
                    if (processMessagePart(inboundSmsTracker)) {
                        transitionTo(mWaitingState);
                    } else {
                        // if event is sent from SmsBroadcastUndelivered.broadcastSms(), and
                        // processMessagePart() returns false, the state machine will be stuck in
                        // DeliveringState until next message is received. Send message to
                        // transition to idle to avoid that so that wakelock can be released
                        log("No broadcast sent on processing EVENT_BROADCAST_SMS in Delivering " +
                                "state. Return to Idle state");
                        sendMessage(EVENT_RETURN_TO_IDLE);
                    }
                    return HANDLED;

                case EVENT_RETURN_TO_IDLE:
                    // return to idle after processing all other messages
                    transitionTo(mIdleState);
                    return HANDLED;

                case EVENT_RELEASE_WAKELOCK:
                    mWakeLock.release();    // decrement wakelock from previous entry to Idle
                    if (!mWakeLock.isHeld()) {
                        // wakelock should still be held until 3 seconds after we enter Idle
                        loge("mWakeLock released while delivering/broadcasting!");
                    }
                    return HANDLED;

                // we shouldn't get this message type in this state, log error and halt.
                case EVENT_BROADCAST_COMPLETE:
                case EVENT_START_ACCEPTING_SMS:
                default:
                    // let DefaultState handle these unexpected message types
                    return NOT_HANDLED;
            }
        }
    }

    /**
     * The waiting state delegates handling of new SMS to parent {@link DeliveringState}, but
     * defers handling of the {@link #EVENT_BROADCAST_SMS} phase until after the current
     * result receiver sends {@link #EVENT_BROADCAST_COMPLETE}. Before transitioning to
     * {@link DeliveringState}, {@link #EVENT_RETURN_TO_IDLE} is sent to transition to
     * {@link IdleState} after any deferred {@link #EVENT_BROADCAST_SMS} messages are handled.
     */
    class WaitingState extends State {
        @Override
        public boolean processMessage(Message msg) {
            log("WaitingState.processMessage:" + msg.what);
            switch (msg.what) {
                case EVENT_BROADCAST_SMS:
                    // defer until the current broadcast completes
                    deferMessage(msg);
                    return HANDLED;

                case EVENT_BROADCAST_COMPLETE:
                    // return to idle after handling all deferred messages
                    sendMessage(EVENT_RETURN_TO_IDLE);
                    transitionTo(mDeliveringState);
                    return HANDLED;

                case EVENT_RETURN_TO_IDLE:
                    // not ready to return to idle; ignore
                    return HANDLED;

                default:
                    // parent state handles the other message types
                    return NOT_HANDLED;
            }
        }
    }

    void handleNewSms(AsyncResult ar) {
        if (ar.exception != null) {
            loge("Exception processing incoming SMS: " + ar.exception);
            return;
        }

        int result;
        try {
            SmsMessage sms = (SmsMessage) ar.result;
            result = dispatchMessage(sms.mWrappedSmsMessage);
        } catch (RuntimeException ex) {
            loge("Exception dispatching message", ex);
            result = Intents.RESULT_SMS_GENERIC_ERROR;
        }

        // RESULT_OK means that the SMS will be acknowledged by special handling,
        // e.g. for SMS-PP data download. Any other result, we should ack here.
        if (result != Activity.RESULT_OK) {
            boolean handled = (result == Intents.RESULT_SMS_HANDLED);
            notifyAndAcknowledgeLastIncomingSms(handled, result, null);
        }
    }

    /**
     * This method is called when a new SMS PDU is injected into application framework.
     * @param ar is the AsyncResult that has the SMS PDU to be injected.
     */
    void handleInjectSms(AsyncResult ar) {
        int result;
        PendingIntent receivedIntent = null;
        try {
            receivedIntent = (PendingIntent) ar.userObj;
            SmsMessage sms = (SmsMessage) ar.result;
            if (sms == null) {
              result = Intents.RESULT_SMS_GENERIC_ERROR;
            } else {
              result = dispatchMessage(sms.mWrappedSmsMessage);
            }
        } catch (RuntimeException ex) {
            loge("Exception dispatching message", ex);
            result = Intents.RESULT_SMS_GENERIC_ERROR;
        }

        if (receivedIntent != null) {
            try {
                receivedIntent.send(result);
            } catch (CanceledException e) { }
        }
    }

    /**
     * Process an SMS message from the RIL, calling subclass methods to handle 3GPP and
     * 3GPP2-specific message types.
     *
     * @param smsb the SmsMessageBase object from the RIL
     * @return a result code from {@link android.provider.Telephony.Sms.Intents},
     *  or {@link Activity#RESULT_OK} for delayed acknowledgment to SMSC
     */
    public int dispatchMessage(SmsMessageBase smsb) {
        // If sms is null, there was a parsing error.
        if (smsb == null) {
            loge("dispatchSmsMessage: message is null");
            return Intents.RESULT_SMS_GENERIC_ERROR;
        }

        if (mSmsReceiveDisabled) {
            // Device doesn't support receiving SMS,
            log("Received short message on device which doesn't support "
                    + "receiving SMS. Ignored.");
            return Intents.RESULT_SMS_HANDLED;
        }

        return dispatchMessageRadioSpecific(smsb);
    }

    /**
     * Process voicemail notification, SMS-PP data download, CDMA CMAS, CDMA WAP push, and other
     * 3GPP/3GPP2-specific messages. Regular SMS messages are handled by calling the shared
     * {@link #dispatchNormalMessage} from this class.
     *
     * @param smsb the SmsMessageBase object from the RIL
     * @return a result code from {@link android.provider.Telephony.Sms.Intents},
     *  or {@link Activity#RESULT_OK} for delayed acknowledgment to SMSC
     */
    protected abstract int dispatchMessageRadioSpecific(SmsMessageBase smsb);

    /**
     * Send an acknowledge message to the SMSC.
     * @param success indicates that last message was successfully received.
     * @param result result code indicating any error
     * @param response callback message sent when operation completes.
     */
    protected abstract void acknowledgeLastIncomingSms(boolean success,
            int result, Message response);

    /**
     * Called when the phone changes the default method updates mPhone
     * mStorageMonitor and mCellBroadcastHandler.updatePhoneObject.
     * Override if different or other behavior is desired.
     *
     * @param phone
     */
    protected void onUpdatePhoneObject(PhoneBase phone) {
        mPhone = phone;
        mStorageMonitor = mPhone.mSmsStorageMonitor;
        log("onUpdatePhoneObject: phone=" + mPhone.getClass().getSimpleName());
    }

    /**
     * Notify interested apps if the framework has rejected an incoming SMS,
     * and send an acknowledge message to the network.
     * @param success indicates that last message was successfully received.
     * @param result result code indicating any error
     * @param response callback message sent when operation completes.
     */
    void notifyAndAcknowledgeLastIncomingSms(boolean success,
            int result, Message response) {
        if (!success) {
            // broadcast SMS_REJECTED_ACTION intent
            Intent intent = new Intent(Intents.SMS_REJECTED_ACTION);
            intent.putExtra("result", result);
            mContext.sendBroadcast(intent, android.Manifest.permission.RECEIVE_SMS);
        }
        acknowledgeLastIncomingSms(success, result, response);
    }

    /**
     * Return true if this handler is for 3GPP2 messages; false for 3GPP format.
     * @return true for the 3GPP2 handler; false for the 3GPP handler
     */
    protected abstract boolean is3gpp2();

    /**
     * Dispatch a normal incoming SMS. This is called from {@link #dispatchMessageRadioSpecific}
     * if no format-specific handling was required. Saves the PDU to the SMS provider raw table,
     * creates an {@link InboundSmsTracker}, then sends it to the state machine as an
     * {@link #EVENT_BROADCAST_SMS}. Returns {@link Intents#RESULT_SMS_HANDLED} or an error value.
     *
     * @param sms the message to dispatch
     * @return {@link Intents#RESULT_SMS_HANDLED} if the message was accepted, or an error status
     */
    protected int dispatchNormalMessage(SmsMessageBase sms) {
        SmsHeader smsHeader = sms.getUserDataHeader();
        InboundSmsTracker tracker;

        if ((smsHeader == null) || (smsHeader.concatRef == null)) {
            // Message is not concatenated.
            int destPort = -1;
            if (smsHeader != null && smsHeader.portAddrs != null) {
                // The message was sent to a port.
                destPort = smsHeader.portAddrs.destPort;
                if (DBG) log("destination port: " + destPort);
            }

            tracker = new InboundSmsTracker(sms.getPdu(), sms.getTimestampMillis(), destPort,
                    is3gpp2(), false);
        } else {
            // Create a tracker for this message segment.
            SmsHeader.ConcatRef concatRef = smsHeader.concatRef;
            SmsHeader.PortAddrs portAddrs = smsHeader.portAddrs;
            int destPort = (portAddrs != null ? portAddrs.destPort : -1);

            tracker = new InboundSmsTracker(sms.getPdu(), sms.getTimestampMillis(), destPort,
                    is3gpp2(), sms.getOriginatingAddress(), concatRef.refNumber,
                    concatRef.seqNumber, concatRef.msgCount, false);
        }

        if (VDBG) log("created tracker: " + tracker);
        return addTrackerToRawTableAndSendMessage(tracker);
    }

    /**
     * Helper to add the tracker to the raw table and then send a message to broadcast it, if
     * successful. Returns the SMS intent status to return to the SMSC.
     * @param tracker the tracker to save to the raw table and then deliver
     * @return {@link Intents#RESULT_SMS_HANDLED} or {@link Intents#RESULT_SMS_GENERIC_ERROR}
     * or {@link Intents#RESULT_SMS_DUPLICATED}
     */
    protected int addTrackerToRawTableAndSendMessage(InboundSmsTracker tracker) {
        switch(addTrackerToRawTable(tracker)) {
        case Intents.RESULT_SMS_HANDLED:
            sendMessage(EVENT_BROADCAST_SMS, tracker);
            return Intents.RESULT_SMS_HANDLED;

        case Intents.RESULT_SMS_DUPLICATED:
            return Intents.RESULT_SMS_HANDLED;

        case Intents.RESULT_SMS_GENERIC_ERROR:
        default:
            return Intents.RESULT_SMS_GENERIC_ERROR;
        }
    }

    /**
     * Process the inbound SMS segment. If the message is complete, send it as an ordered
     * broadcast to interested receivers and return true. If the message is a segment of an
     * incomplete multi-part SMS, return false.
     * @param tracker the tracker containing the message segment to process
     * @return true if an ordered broadcast was sent; false if waiting for more message segments
     */
    boolean processMessagePart(InboundSmsTracker tracker) {
        int messageCount = tracker.getMessageCount();
        byte[][] pdus;
        int destPort = tracker.getDestPort();

        if (messageCount == 1) {
            // single-part message
            pdus = new byte[][]{tracker.getPdu()};
        } else {
            // multi-part message
            Cursor cursor = null;
            try {
                // used by several query selection arguments
                String address = tracker.getAddress();
                String refNumber = Integer.toString(tracker.getReferenceNumber());
                String count = Integer.toString(tracker.getMessageCount());

                // query for all segments and broadcast message if we have all the parts
                String[] whereArgs = {address, refNumber, count};
                cursor = mResolver.query(sRawUri, PDU_SEQUENCE_PORT_PROJECTION,
                        SELECT_BY_REFERENCE, whereArgs, null);

                int cursorCount = cursor.getCount();
                if (cursorCount < messageCount) {
                    // Wait for the other message parts to arrive. It's also possible for the last
                    // segment to arrive before processing the EVENT_BROADCAST_SMS for one of the
                    // earlier segments. In that case, the broadcast will be sent as soon as all
                    // segments are in the table, and any later EVENT_BROADCAST_SMS messages will
                    // get a row count of 0 and return.
                    return false;
                }

                // All the parts are in place, deal with them
                pdus = new byte[messageCount][];
                while (cursor.moveToNext()) {
                    // subtract offset to convert sequence to 0-based array index
                    int index = cursor.getInt(SEQUENCE_COLUMN) - tracker.getIndexOffset();

                    pdus[index] = HexDump.hexStringToByteArray(cursor.getString(PDU_COLUMN));

                    // Read the destination port from the first segment (needed for CDMA WAP PDU).
                    // It's not a bad idea to prefer the port from the first segment in other cases.
                    if (index == 0 && !cursor.isNull(DESTINATION_PORT_COLUMN)) {
                        int port = cursor.getInt(DESTINATION_PORT_COLUMN);
                        // strip format flags and convert to real port number, or -1
                        port = InboundSmsTracker.getRealDestPort(port);
                        if (port != -1) {
                            destPort = port;
                        }
                    }
                }
            } catch (SQLException e) {
                loge("Can't access multipart SMS database", e);
                return false;
            } finally {
                if (cursor != null) {
                    cursor.close();
                }
            }
        }

        SmsBroadcastReceiver resultReceiver = new SmsBroadcastReceiver(tracker);

        if (destPort == SmsHeader.PORT_WAP_PUSH) {
            // Build up the data stream
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            for (byte[] pdu : pdus) {
                // 3GPP needs to extract the User Data from the PDU; 3GPP2 has already done this
                if (!tracker.is3gpp2()) {
                    SmsMessage msg = SmsMessage.createFromPdu(pdu, SmsConstants.FORMAT_3GPP);
                    pdu = msg.getUserData();
                }
                output.write(pdu, 0, pdu.length);
            }
            int result = mWapPush.dispatchWapPdu(output.toByteArray(), resultReceiver, this);
            if (DBG) log("dispatchWapPdu() returned " + result);
            // result is Activity.RESULT_OK if an ordered broadcast was sent
            if (result == Activity.RESULT_OK) {
                return true;
            } else {
                deleteFromRawTable(tracker.getDeleteWhere(), tracker.getDeleteWhereArgs());
                return false;
            }
        }

        List<String> carrierPackages = null;
        UiccCard card = UiccController.getInstance().getUiccCard(mPhone.getPhoneId());
        if (card != null) {
            carrierPackages = card.getCarrierPackageNamesForIntent(
                    mContext.getPackageManager(),
                    new Intent(CarrierMessagingService.SERVICE_INTERFACE));
        } else {
            loge("UiccCard not initialized.");
        }

        List<String> systemPackages =
                getSystemAppForIntent(new Intent(CarrierMessagingService.SERVICE_INTERFACE));

        if (carrierPackages != null && carrierPackages.size() == 1) {
            log("Found carrier package.");
            CarrierSmsFilter smsFilter = new CarrierSmsFilter(pdus, destPort,
                    tracker.getFormat(), resultReceiver);
            CarrierSmsFilterCallback smsFilterCallback = new CarrierSmsFilterCallback(smsFilter);
            smsFilter.filterSms(carrierPackages.get(0), smsFilterCallback);
        } else if (systemPackages != null && systemPackages.size() == 1) {
            log("Found system package.");
            CarrierSmsFilter smsFilter = new CarrierSmsFilter(pdus, destPort,
                    tracker.getFormat(), resultReceiver);
            CarrierSmsFilterCallback smsFilterCallback = new CarrierSmsFilterCallback(smsFilter);
            smsFilter.filterSms(systemPackages.get(0), smsFilterCallback);
        } else {
            logv("Unable to find carrier package: " + carrierPackages
                    + ", nor systemPackages: " + systemPackages);
            dispatchSmsDeliveryIntent(pdus, tracker.getFormat(), destPort, resultReceiver);
        }

        return true;
    }

    private List<String> getSystemAppForIntent(Intent intent) {
        List<String> packages = new ArrayList<String>();
        PackageManager packageManager = mContext.getPackageManager();
        List<ResolveInfo> receivers = packageManager.queryIntentServices(intent, 0);
        String carrierFilterSmsPerm = "android.permission.CARRIER_FILTER_SMS";

        for (ResolveInfo info : receivers) {
            if (info.serviceInfo == null) {
                loge("Can't get service information from " + info);
                continue;
            }
            String packageName = info.serviceInfo.packageName;
                if (packageManager.checkPermission(carrierFilterSmsPerm, packageName) ==
                        packageManager.PERMISSION_GRANTED) {
                    packages.add(packageName);
                    if (DBG) log("getSystemAppForIntent: added package "+ packageName);
                }
        }
        return packages;
    }

    /**
     * Dispatch the intent with the specified permission, appOp, and result receiver, using
     * this state machine's handler thread to run the result receiver.
     *
     * @param intent the intent to broadcast
     * @param permission receivers are required to have this permission
     * @param appOp app op that is being performed when dispatching to a receiver
     * @param user user to deliver the intent to
     */
    protected void dispatchIntent(Intent intent, String permission, int appOp,
            Bundle opts, BroadcastReceiver resultReceiver, UserHandle user) {
        intent.addFlags(Intent.FLAG_RECEIVER_NO_ABORT);
        SubscriptionManager.putPhoneIdAndSubIdExtra(intent, mPhone.getPhoneId());
        if (user.equals(UserHandle.ALL)) {
            // Get a list of currently started users.
            int[] users = null;
            try {
                users = ActivityManagerNative.getDefault().getRunningUserIds();
            } catch (RemoteException re) {
            }
            if (users == null) {
                users = new int[] {user.getIdentifier()};
            }
            // Deliver the broadcast only to those running users that are permitted
            // by user policy.
            for (int i = users.length - 1; i >= 0; i--) {
                UserHandle targetUser = new UserHandle(users[i]);
                if (users[i] != UserHandle.USER_OWNER) {
                    // Is the user not allowed to use SMS?
                    if (mUserManager.hasUserRestriction(UserManager.DISALLOW_SMS, targetUser)) {
                        continue;
                    }
                    // Skip unknown users and managed profiles as well
                    UserInfo info = mUserManager.getUserInfo(users[i]);
                    if (info == null || info.isManagedProfile()) {
                        continue;
                    }
                }
                // Only pass in the resultReceiver when the USER_OWNER is processed.
                mContext.sendOrderedBroadcastAsUser(intent, targetUser, permission, appOp, opts,
                        users[i] == UserHandle.USER_OWNER ? resultReceiver : null,
                        getHandler(), Activity.RESULT_OK, null, null);
            }
        } else {
            mContext.sendOrderedBroadcastAsUser(intent, user, permission, appOp, opts,
                    resultReceiver, getHandler(), Activity.RESULT_OK, null, null);
        }
    }

    /**
     * Helper for {@link SmsBroadcastUndelivered} to delete an old message in the raw table.
     */
    void deleteFromRawTable(String deleteWhere, String[] deleteWhereArgs) {
        int rows = mResolver.delete(sRawUri, deleteWhere, deleteWhereArgs);
        if (rows == 0) {
            loge("No rows were deleted from raw table!");
        } else if (DBG) {
            log("Deleted " + rows + " rows from raw table.");
        }
    }

    Bundle handleSmsWhitelisting(ComponentName target) {
        String pkgName;
        String reason;
        if (target != null) {
            pkgName = target.getPackageName();
            reason = "sms-app";
        } else {
            pkgName = mContext.getPackageName();
            reason = "sms-broadcast";
        }
        try {
            long duration = mDeviceIdleController.addPowerSaveTempWhitelistAppForSms(
                    pkgName, 0, reason);
            BroadcastOptions bopts = BroadcastOptions.makeBasic();
            bopts.setTemporaryAppWhitelistDuration(duration);
            return bopts.toBundle();
        } catch (RemoteException e) {
        }

        return null;
    }

    /**
     * Creates and dispatches the intent to the default SMS app or the appropriate port.
     *
     * @param pdus message pdus
     * @param format the message format, typically "3gpp" or "3gpp2"
     * @param destPort the destination port
     * @param resultReceiver the receiver handling the delivery result
     */
    void dispatchSmsDeliveryIntent(byte[][] pdus, String format, int destPort,
            BroadcastReceiver resultReceiver) {
        Intent intent = new Intent();
        intent.putExtra("pdus", pdus);
        intent.putExtra("format", format);

        if (destPort == -1) {
            intent.setAction(Intents.SMS_DELIVER_ACTION);
            // Direct the intent to only the default SMS app. If we can't find a default SMS app
            // then sent it to all broadcast receivers.
            // We are deliberately delivering to the primary user's default SMS App.
            ComponentName componentName = SmsApplication.getDefaultSmsApplication(mContext, true);
            if (componentName != null) {
                // Deliver SMS message only to this receiver.
                intent.setComponent(componentName);
                log("Delivering SMS to: " + componentName.getPackageName() +
                    " " + componentName.getClassName());
            } else {
                intent.setComponent(null);
            }

            // TODO: Validate that this is the right place to store the SMS.
            if (SmsManager.getDefault().getAutoPersisting()) {
                final Uri uri = writeInboxMessage(intent);
                if (uri != null) {
                    // Pass this to SMS apps so that they know where it is stored
                    intent.putExtra("uri", uri.toString());
                }
            }
        } else {
            intent.setAction(Intents.DATA_SMS_RECEIVED_ACTION);
            Uri uri = Uri.parse("sms://localhost:" + destPort);
            intent.setData(uri);
            intent.setComponent(null);
        }

        Bundle options = handleSmsWhitelisting(intent.getComponent());
        dispatchIntent(intent, android.Manifest.permission.RECEIVE_SMS,
                AppOpsManager.OP_RECEIVE_SMS, options, resultReceiver, UserHandle.OWNER);
    }

    /**
     * Insert a message PDU into the raw table so we can acknowledge it immediately.
     * If the device crashes before the broadcast to listeners completes, it will be delivered
     * from the raw table on the next device boot. For single-part messages, the deleteWhere
     * and deleteWhereArgs fields of the tracker will be set to delete the correct row after
     * the ordered broadcast completes.
     *
     * @param tracker the tracker to add to the raw table
     * @return true on success; false on failure to write to database
     */
    private int addTrackerToRawTable(InboundSmsTracker tracker) {
        if (tracker.getMessageCount() != 1) {
            // check for duplicate message segments
            Cursor cursor = null;
            try {
                // sequence numbers are 1-based except for CDMA WAP, which is 0-based
                int sequence = tracker.getSequenceNumber();

                // convert to strings for query
                String address = tracker.getAddress();
                String refNumber = Integer.toString(tracker.getReferenceNumber());
                String count = Integer.toString(tracker.getMessageCount());

                String seqNumber = Integer.toString(sequence);

                // set the delete selection args for multi-part message
                String[] deleteWhereArgs = {address, refNumber, count};
                tracker.setDeleteWhere(SELECT_BY_REFERENCE, deleteWhereArgs);

                // Check for duplicate message segments
                cursor = mResolver.query(sRawUri, PDU_PROJECTION,
                        "address=? AND reference_number=? AND count=? AND sequence=?",
                        new String[] {address, refNumber, count, seqNumber}, null);

                // moveToNext() returns false if no duplicates were found
                if (cursor.moveToNext()) {
                    loge("Discarding duplicate message segment, refNumber=" + refNumber
                            + " seqNumber=" + seqNumber);
                    String oldPduString = cursor.getString(PDU_COLUMN);
                    byte[] pdu = tracker.getPdu();
                    byte[] oldPdu = HexDump.hexStringToByteArray(oldPduString);
                    if (!Arrays.equals(oldPdu, tracker.getPdu())) {
                        loge("Warning: dup message segment PDU of length " + pdu.length
                                + " is different from existing PDU of length " + oldPdu.length);
                    }
                    return Intents.RESULT_SMS_DUPLICATED;   // reject message
                }
                cursor.close();
            } catch (SQLException e) {
                loge("Can't access multipart SMS database", e);
                return Intents.RESULT_SMS_GENERIC_ERROR;    // reject message
            } finally {
                if (cursor != null) {
                    cursor.close();
                }
            }
        }

        ContentValues values = tracker.getContentValues();

        if (VDBG) log("adding content values to raw table: " + values.toString());
        Uri newUri = mResolver.insert(sRawUri, values);
        if (DBG) log("URI of new row -> " + newUri);

        try {
            long rowId = ContentUris.parseId(newUri);
            if (tracker.getMessageCount() == 1) {
                // set the delete selection args for single-part message
                tracker.setDeleteWhere(SELECT_BY_ID, new String[]{Long.toString(rowId)});
            }
            return Intents.RESULT_SMS_HANDLED;
        } catch (Exception e) {
            loge("error parsing URI for new row: " + newUri, e);
            return Intents.RESULT_SMS_GENERIC_ERROR;
        }
    }

    /**
     * Returns whether the default message format for the current radio technology is 3GPP2.
     * @return true if the radio technology uses 3GPP2 format by default, false for 3GPP format
     */
    static boolean isCurrentFormat3gpp2() {
        int activePhone = TelephonyManager.getDefault().getCurrentPhoneType();
        return (PHONE_TYPE_CDMA == activePhone);
    }

    protected void storeVoiceMailCount() {
        // Store the voice mail count in persistent memory.
        String imsi = mPhone.getSubscriberId();
        int mwi = mPhone.getVoiceMessageCount();

        log("Storing Voice Mail Count = " + mwi
                    + " for mVmCountKey = " + mPhone.VM_COUNT
                    + " vmId = " + mPhone.VM_ID
                    + " in preferences.");

        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(mContext);
        SharedPreferences.Editor editor = sp.edit();
        editor.putInt(mPhone.VM_COUNT, mwi);
        editor.putString(mPhone.VM_ID, imsi);
        editor.commit();
    }

    /**
     * Handler for an {@link InboundSmsTracker} broadcast. Deletes PDUs from the raw table and
     * logs the broadcast duration (as an error if the other receivers were especially slow).
     */
    private final class SmsBroadcastReceiver extends BroadcastReceiver {
        private final String mDeleteWhere;
        private final String[] mDeleteWhereArgs;
        private long mBroadcastTimeNano;

        SmsBroadcastReceiver(InboundSmsTracker tracker) {
            mDeleteWhere = tracker.getDeleteWhere();
            mDeleteWhereArgs = tracker.getDeleteWhereArgs();
            mBroadcastTimeNano = System.nanoTime();
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action.equals(Intents.SMS_DELIVER_ACTION)) {
                // Now dispatch the notification only intent
                intent.setAction(Intents.SMS_RECEIVED_ACTION);
                intent.setComponent(null);
                // All running users will be notified of the received sms.
                Bundle options = handleSmsWhitelisting(null);
                dispatchIntent(intent, android.Manifest.permission.RECEIVE_SMS,
                        AppOpsManager.OP_RECEIVE_SMS, options, this, UserHandle.ALL);
            } else if (action.equals(Intents.WAP_PUSH_DELIVER_ACTION)) {
                // Now dispatch the notification only intent
                intent.setAction(Intents.WAP_PUSH_RECEIVED_ACTION);
                intent.setComponent(null);
                // Only the primary user will receive notification of incoming mms.
                // That app will do the actual downloading of the mms.
                Bundle options = null;
                try {
                    long duration = mDeviceIdleController.addPowerSaveTempWhitelistAppForMms(
                            mContext.getPackageName(), 0, "mms-broadcast");
                    BroadcastOptions bopts = BroadcastOptions.makeBasic();
                    bopts.setTemporaryAppWhitelistDuration(duration);
                    options = bopts.toBundle();
                } catch (RemoteException e) {
                }
                dispatchIntent(intent, android.Manifest.permission.RECEIVE_SMS,
                        AppOpsManager.OP_RECEIVE_SMS, options, this, UserHandle.OWNER);
            } else {
                // Now that the intents have been deleted we can clean up the PDU data.
                if (!Intents.DATA_SMS_RECEIVED_ACTION.equals(action)
                        && !Intents.SMS_RECEIVED_ACTION.equals(action)
                        && !Intents.DATA_SMS_RECEIVED_ACTION.equals(action)
                        && !Intents.WAP_PUSH_RECEIVED_ACTION.equals(action)) {
                    loge("unexpected BroadcastReceiver action: " + action);
                }

                int rc = getResultCode();
                if ((rc != Activity.RESULT_OK) && (rc != Intents.RESULT_SMS_HANDLED)) {
                    loge("a broadcast receiver set the result code to " + rc
                            + ", deleting from raw table anyway!");
                } else if (DBG) {
                    log("successful broadcast, deleting from raw table.");
                }

                deleteFromRawTable(mDeleteWhere, mDeleteWhereArgs);
                sendMessage(EVENT_BROADCAST_COMPLETE);

                int durationMillis = (int) ((System.nanoTime() - mBroadcastTimeNano) / 1000000);
                if (durationMillis >= 5000) {
                    loge("Slow ordered broadcast completion time: " + durationMillis + " ms");
                } else if (DBG) {
                    log("ordered broadcast completed in: " + durationMillis + " ms");
                }
            }
        }
    }

    /**
     * Asynchronously binds to the carrier messaging service, and filters out the message if
     * instructed to do so by the carrier messaging service. A new instance must be used for every
     * message.
     */
    private final class CarrierSmsFilter extends CarrierMessagingServiceManager {
        private final byte[][] mPdus;
        private final int mDestPort;
        private final String mSmsFormat;
        private final SmsBroadcastReceiver mSmsBroadcastReceiver;
        // Instantiated in filterSms.
        private volatile CarrierSmsFilterCallback mSmsFilterCallback;

        CarrierSmsFilter(byte[][] pdus, int destPort, String smsFormat,
                SmsBroadcastReceiver smsBroadcastReceiver) {
            mPdus = pdus;
            mDestPort = destPort;
            mSmsFormat = smsFormat;
            mSmsBroadcastReceiver = smsBroadcastReceiver;
        }

        /**
         * Attempts to bind to a {@link ICarrierMessagingService}. Filtering is initiated
         * asynchronously once the service is ready using {@link #onServiceReady}.
         */
        void filterSms(String carrierPackageName, CarrierSmsFilterCallback smsFilterCallback) {
            mSmsFilterCallback = smsFilterCallback;
            if (!bindToCarrierMessagingService(mContext, carrierPackageName)) {
                loge("bindService() for carrier messaging service failed");
                smsFilterCallback.onFilterComplete(true);
            } else {
                logv("bindService() for carrier messaging service succeeded");
            }
        }

        /**
         * Invokes the {@code carrierMessagingService} to filter messages. The filtering result is
         * delivered to {@code smsFilterCallback}.
         */
        @Override
        protected void onServiceReady(ICarrierMessagingService carrierMessagingService) {
            try {
                carrierMessagingService.filterSms(
                        new MessagePdu(Arrays.asList(mPdus)), mSmsFormat, mDestPort,
                        mPhone.getSubId(), mSmsFilterCallback);
            } catch (RemoteException e) {
                loge("Exception filtering the SMS: " + e);
                mSmsFilterCallback.onFilterComplete(true);
            }
        }
    }

    /**
     * A callback used to notify the platform of the carrier messaging app filtering result. Once
     * the result is ready, the carrier messaging service connection is disposed.
     */
    private final class CarrierSmsFilterCallback extends ICarrierMessagingCallback.Stub {
        private final CarrierSmsFilter mSmsFilter;

        CarrierSmsFilterCallback(CarrierSmsFilter smsFilter) {
            mSmsFilter = smsFilter;
        }

        /**
         * This method should be called only once.
         */
        @Override
        public void onFilterComplete(boolean keepMessage) {
            mSmsFilter.disposeConnection(mContext);

            logv("onFilterComplete: keepMessage is "+ keepMessage);
            if (keepMessage) {
                dispatchSmsDeliveryIntent(mSmsFilter.mPdus, mSmsFilter.mSmsFormat,
                        mSmsFilter.mDestPort, mSmsFilter.mSmsBroadcastReceiver);
            } else {
                // Drop this SMS.
                final long token = Binder.clearCallingIdentity();
                try {
                    // Needs phone package permissions.
                    deleteFromRawTable(mSmsFilter.mSmsBroadcastReceiver.mDeleteWhere,
                            mSmsFilter.mSmsBroadcastReceiver.mDeleteWhereArgs);
                } finally {
                    Binder.restoreCallingIdentity(token);
                }
                sendMessage(EVENT_BROADCAST_COMPLETE);
            }
        }

        @Override
        public void onSendSmsComplete(int result, int messageRef) {
            loge("Unexpected onSendSmsComplete call with result: " + result);
        }

        @Override
        public void onSendMultipartSmsComplete(int result, int[] messageRefs) {
            loge("Unexpected onSendMultipartSmsComplete call with result: " + result);
        }

        @Override
        public void onSendMmsComplete(int result, byte[] sendConfPdu) {
            loge("Unexpected onSendMmsComplete call with result: " + result);
        }

        @Override
        public void onDownloadMmsComplete(int result) {
            loge("Unexpected onDownloadMmsComplete call with result: " + result);
        }
    }

    /**
     * Log with debug level.
     * @param s the string to log
     */
    @Override
    protected void log(String s) {
        Rlog.d(getName(), s);
    }

    /**
     * Log with error level.
     * @param s the string to log
     */
    @Override
    protected void loge(String s) {
        Rlog.e(getName(), s);
    }

    /**
     * Log with error level.
     * @param s the string to log
     * @param e is a Throwable which logs additional information.
     */
    @Override
    protected void loge(String s, Throwable e) {
        Rlog.e(getName(), s, e);
    }

    /**
     * Store a received SMS into Telephony provider
     *
     * @param intent The intent containing the received SMS
     * @return The URI of written message
     */
    private Uri writeInboxMessage(Intent intent) {
        final SmsMessage[] messages = Telephony.Sms.Intents.getMessagesFromIntent(intent);
        if (messages == null || messages.length < 1) {
            loge("Failed to parse SMS pdu");
            return null;
        }
        // Sometimes, SmsMessage.mWrappedSmsMessage is null causing NPE when we access
        // the methods on it although the SmsMessage itself is not null. So do this check
        // before we do anything on the parsed SmsMessages.
        for (final SmsMessage sms : messages) {
            try {
                sms.getDisplayMessageBody();
            } catch (NullPointerException e) {
                loge("NPE inside SmsMessage");
                return null;
            }
        }
        final ContentValues values = parseSmsMessage(messages);
        final long identity = Binder.clearCallingIdentity();
        try {
            return mContext.getContentResolver().insert(Telephony.Sms.Inbox.CONTENT_URI, values);
        } catch (Exception e) {
            loge("Failed to persist inbox message", e);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
        return null;
    }

    /**
     * Convert SmsMessage[] into SMS database schema columns
     *
     * @param msgs The SmsMessage array of the received SMS
     * @return ContentValues representing the columns of parsed SMS
     */
    private static ContentValues parseSmsMessage(SmsMessage[] msgs) {
        final SmsMessage sms = msgs[0];
        final ContentValues values = new ContentValues();
        values.put(Telephony.Sms.Inbox.ADDRESS, sms.getDisplayOriginatingAddress());
        values.put(Telephony.Sms.Inbox.BODY, buildMessageBodyFromPdus(msgs));
        values.put(Telephony.Sms.Inbox.DATE_SENT, sms.getTimestampMillis());
        values.put(Telephony.Sms.Inbox.DATE, System.currentTimeMillis());
        values.put(Telephony.Sms.Inbox.PROTOCOL, sms.getProtocolIdentifier());
        values.put(Telephony.Sms.Inbox.SEEN, 0);
        values.put(Telephony.Sms.Inbox.READ, 0);
        final String subject = sms.getPseudoSubject();
        if (!TextUtils.isEmpty(subject)) {
            values.put(Telephony.Sms.Inbox.SUBJECT, subject);
        }
        values.put(Telephony.Sms.Inbox.REPLY_PATH_PRESENT, sms.isReplyPathPresent() ? 1 : 0);
        values.put(Telephony.Sms.Inbox.SERVICE_CENTER, sms.getServiceCenterAddress());
        return values;
    }

    /**
     * Build up the SMS message body from the SmsMessage array of received SMS
     *
     * @param msgs The SmsMessage array of the received SMS
     * @return The text message body
     */
    private static String buildMessageBodyFromPdus(SmsMessage[] msgs) {
        if (msgs.length == 1) {
            // There is only one part, so grab the body directly.
            return replaceFormFeeds(msgs[0].getDisplayMessageBody());
        } else {
            // Build up the body from the parts.
            StringBuilder body = new StringBuilder();
            for (SmsMessage msg: msgs) {
                // getDisplayMessageBody() can NPE if mWrappedMessage inside is null.
                body.append(msg.getDisplayMessageBody());
            }
            return replaceFormFeeds(body.toString());
        }
    }

    // Some providers send formfeeds in their messages. Convert those formfeeds to newlines.
    private static String replaceFormFeeds(String s) {
        return s == null ? "" : s.replace('\f', '\n');
    }
}
