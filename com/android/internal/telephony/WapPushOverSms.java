/*
 * Copyright (C) 2008 The Android Open Source Project
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

import com.google.android.mms.MmsException;
import com.google.android.mms.pdu.DeliveryInd;
import com.google.android.mms.pdu.GenericPdu;
import com.google.android.mms.pdu.NotificationInd;
import com.google.android.mms.pdu.PduHeaders;
import com.google.android.mms.pdu.PduParser;
import com.google.android.mms.pdu.PduPersister;
import com.google.android.mms.pdu.ReadOrigInd;

import android.app.Activity;
import android.app.AppOpsManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteException;
import android.database.sqlite.SqliteWrapper;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.Telephony;
import android.provider.Telephony.Sms.Intents;
import android.telephony.SmsManager;
import android.telephony.SubscriptionManager;
import android.telephony.Rlog;
import android.util.Log;

import com.android.internal.telephony.uicc.IccUtils;

import static com.google.android.mms.pdu.PduHeaders.MESSAGE_TYPE_DELIVERY_IND;
import static com.google.android.mms.pdu.PduHeaders.MESSAGE_TYPE_NOTIFICATION_IND;
import static com.google.android.mms.pdu.PduHeaders.MESSAGE_TYPE_READ_ORIG_IND;

/**
 * WAP push handler class.
 *
 * @hide
 */
public class WapPushOverSms implements ServiceConnection {
    private static final String TAG = "WAP PUSH";
    private static final boolean DBG = true;

    private final Context mContext;

    /** Assigned from ServiceConnection callback on main threaad. */
    private volatile IWapPushManager mWapPushManager;

    @Override
    public void onServiceConnected(ComponentName name, IBinder service) {
        mWapPushManager = IWapPushManager.Stub.asInterface(service);
        if (DBG) Rlog.v(TAG, "wappush manager connected to " + hashCode());
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        mWapPushManager = null;
        if (DBG) Rlog.v(TAG, "wappush manager disconnected.");
    }

    public WapPushOverSms(Context context) {
        mContext = context;
        Intent intent = new Intent(IWapPushManager.class.getName());
        ComponentName comp = intent.resolveSystemService(context.getPackageManager(), 0);
        intent.setComponent(comp);
        if (comp == null || !context.bindService(intent, this, Context.BIND_AUTO_CREATE)) {
            Rlog.e(TAG, "bindService() for wappush manager failed");
        } else {
            if (DBG) Rlog.v(TAG, "bindService() for wappush manager succeeded");
        }
    }

    void dispose() {
        if (mWapPushManager != null) {
            if (DBG) Rlog.v(TAG, "dispose: unbind wappush manager");
            mContext.unbindService(this);
        } else {
            Rlog.e(TAG, "dispose: not bound to a wappush manager");
        }
    }

    /**
     * Dispatches inbound messages that are in the WAP PDU format. See
     * wap-230-wsp-20010705-a section 8 for details on the WAP PDU format.
     *
     * @param pdu The WAP PDU, made up of one or more SMS PDUs
     * @return a result code from {@link android.provider.Telephony.Sms.Intents}, or
     *         {@link Activity#RESULT_OK} if the message has been broadcast
     *         to applications
     */
    public int dispatchWapPdu(byte[] pdu, BroadcastReceiver receiver, InboundSmsHandler handler) {

        if (DBG) Rlog.d(TAG, "Rx: " + IccUtils.bytesToHexString(pdu));

        try {
            int index = 0;
            int transactionId = pdu[index++] & 0xFF;
            int pduType = pdu[index++] & 0xFF;

            // Should we "abort" if no subId for now just no supplying extra param below
            int phoneId = handler.getPhone().getPhoneId();

            if ((pduType != WspTypeDecoder.PDU_TYPE_PUSH) &&
                    (pduType != WspTypeDecoder.PDU_TYPE_CONFIRMED_PUSH)) {
                index = mContext.getResources().getInteger(
                        com.android.internal.R.integer.config_valid_wappush_index);
                if (index != -1) {
                    transactionId = pdu[index++] & 0xff;
                    pduType = pdu[index++] & 0xff;
                    if (DBG)
                        Rlog.d(TAG, "index = " + index + " PDU Type = " + pduType +
                                " transactionID = " + transactionId);

                    // recheck wap push pduType
                    if ((pduType != WspTypeDecoder.PDU_TYPE_PUSH)
                            && (pduType != WspTypeDecoder.PDU_TYPE_CONFIRMED_PUSH)) {
                        if (DBG) Rlog.w(TAG, "Received non-PUSH WAP PDU. Type = " + pduType);
                        return Intents.RESULT_SMS_HANDLED;
                    }
                } else {
                    if (DBG) Rlog.w(TAG, "Received non-PUSH WAP PDU. Type = " + pduType);
                    return Intents.RESULT_SMS_HANDLED;
                }
            }

            WspTypeDecoder pduDecoder = new WspTypeDecoder(pdu);

            /**
             * Parse HeaderLen(unsigned integer).
             * From wap-230-wsp-20010705-a section 8.1.2
             * The maximum size of a uintvar is 32 bits.
             * So it will be encoded in no more than 5 octets.
             */
            if (pduDecoder.decodeUintvarInteger(index) == false) {
                if (DBG) Rlog.w(TAG, "Received PDU. Header Length error.");
                return Intents.RESULT_SMS_GENERIC_ERROR;
            }
            int headerLength = (int) pduDecoder.getValue32();
            index += pduDecoder.getDecodedDataLength();

            int headerStartIndex = index;

            /**
             * Parse Content-Type.
             * From wap-230-wsp-20010705-a section 8.4.2.24
             *
             * Content-type-value = Constrained-media | Content-general-form
             * Content-general-form = Value-length Media-type
             * Media-type = (Well-known-media | Extension-Media) *(Parameter)
             * Value-length = Short-length | (Length-quote Length)
             * Short-length = <Any octet 0-30>   (octet <= WAP_PDU_SHORT_LENGTH_MAX)
             * Length-quote = <Octet 31>         (WAP_PDU_LENGTH_QUOTE)
             * Length = Uintvar-integer
             */
            if (pduDecoder.decodeContentType(index) == false) {
                if (DBG) Rlog.w(TAG, "Received PDU. Header Content-Type error.");
                return Intents.RESULT_SMS_GENERIC_ERROR;
            }

            String mimeType = pduDecoder.getValueString();
            long binaryContentType = pduDecoder.getValue32();
            index += pduDecoder.getDecodedDataLength();

            byte[] header = new byte[headerLength];
            System.arraycopy(pdu, headerStartIndex, header, 0, header.length);

            byte[] intentData;

            if (mimeType != null && mimeType.equals(WspTypeDecoder.CONTENT_TYPE_B_PUSH_CO)) {
                intentData = pdu;
            } else {
                int dataIndex = headerStartIndex + headerLength;
                intentData = new byte[pdu.length - dataIndex];
                System.arraycopy(pdu, dataIndex, intentData, 0, intentData.length);
            }

            if (SmsManager.getDefault().getAutoPersisting()) {
                // Store the wap push data in telephony
                long [] subIds = SubscriptionManager.getSubId(phoneId);
                // FIXME (tomtaylor) - when we've updated SubscriptionManager, change
                // SubscriptionManager.DEFAULT_SUB_ID to SubscriptionManager.getDefaultSmsSubId()
                long subId = (subIds != null) && (subIds.length > 0) ? subIds[0] :
                    SmsManager.getDefaultSmsSubId();
                writeInboxMessage(subId, intentData);
            }

            /**
             * Seek for application ID field in WSP header.
             * If application ID is found, WapPushManager substitute the message
             * processing. Since WapPushManager is optional module, if WapPushManager
             * is not found, legacy message processing will be continued.
             */
            if (pduDecoder.seekXWapApplicationId(index, index + headerLength - 1)) {
                index = (int) pduDecoder.getValue32();
                pduDecoder.decodeXWapApplicationId(index);
                String wapAppId = pduDecoder.getValueString();
                if (wapAppId == null) {
                    wapAppId = Integer.toString((int) pduDecoder.getValue32());
                }

                String contentType = ((mimeType == null) ?
                        Long.toString(binaryContentType) : mimeType);
                if (DBG) Rlog.v(TAG, "appid found: " + wapAppId + ":" + contentType);

                try {
                    boolean processFurther = true;
                    IWapPushManager wapPushMan = mWapPushManager;

                    if (wapPushMan == null) {
                        if (DBG) Rlog.w(TAG, "wap push manager not found!");
                    } else {
                        Intent intent = new Intent();
                        intent.putExtra("transactionId", transactionId);
                        intent.putExtra("pduType", pduType);
                        intent.putExtra("header", header);
                        intent.putExtra("data", intentData);
                        intent.putExtra("contentTypeParameters",
                                pduDecoder.getContentParameters());
                        SubscriptionManager.putPhoneIdAndSubIdExtra(intent, phoneId);

                        int procRet = wapPushMan.processMessage(wapAppId, contentType, intent);
                        if (DBG) Rlog.v(TAG, "procRet:" + procRet);
                        if ((procRet & WapPushManagerParams.MESSAGE_HANDLED) > 0
                                && (procRet & WapPushManagerParams.FURTHER_PROCESSING) == 0) {
                            processFurther = false;
                        }
                    }
                    if (!processFurther) {
                        return Intents.RESULT_SMS_HANDLED;
                    }
                } catch (RemoteException e) {
                    if (DBG) Rlog.w(TAG, "remote func failed...");
                }
            }
            if (DBG) Rlog.v(TAG, "fall back to existing handler");

            if (mimeType == null) {
                if (DBG) Rlog.w(TAG, "Header Content-Type error.");
                return Intents.RESULT_SMS_GENERIC_ERROR;
            }

            String permission;
            int appOp;

            if (mimeType.equals(WspTypeDecoder.CONTENT_TYPE_B_MMS)) {
                permission = android.Manifest.permission.RECEIVE_MMS;
                appOp = AppOpsManager.OP_RECEIVE_MMS;
            } else {
                permission = android.Manifest.permission.RECEIVE_WAP_PUSH;
                appOp = AppOpsManager.OP_RECEIVE_WAP_PUSH;
            }

            Intent intent = new Intent(Intents.WAP_PUSH_DELIVER_ACTION);
            intent.setType(mimeType);
            intent.putExtra("transactionId", transactionId);
            intent.putExtra("pduType", pduType);
            intent.putExtra("header", header);
            intent.putExtra("data", intentData);
            intent.putExtra("contentTypeParameters", pduDecoder.getContentParameters());
            SubscriptionManager.putPhoneIdAndSubIdExtra(intent, phoneId);

            // Direct the intent to only the default MMS app. If we can't find a default MMS app
            // then sent it to all broadcast receivers.
            ComponentName componentName = SmsApplication.getDefaultMmsApplication(mContext, true);
            if (componentName != null) {
                // Deliver MMS message only to this receiver
                intent.setComponent(componentName);
                if (DBG) Rlog.v(TAG, "Delivering MMS to: " + componentName.getPackageName() +
                        " " + componentName.getClassName());
            }

            handler.dispatchIntent(intent, permission, appOp, receiver, UserHandle.OWNER);
            return Activity.RESULT_OK;
        } catch (ArrayIndexOutOfBoundsException aie) {
            // 0-byte WAP PDU or other unexpected WAP PDU contents can easily throw this;
            // log exception string without stack trace and return false.
            Rlog.e(TAG, "ignoring dispatchWapPdu() array index exception: " + aie);
            return Intents.RESULT_SMS_GENERIC_ERROR;
        }
    }

    private void writeInboxMessage(long subId, byte[] pushData) {
        final GenericPdu pdu = new PduParser(pushData).parse();
        if (pdu == null) {
            Rlog.e(TAG, "Invalid PUSH PDU");
        }
        final PduPersister persister = PduPersister.getPduPersister(mContext);
        final int type = pdu.getMessageType();
        try {
            switch (type) {
                case MESSAGE_TYPE_DELIVERY_IND:
                case MESSAGE_TYPE_READ_ORIG_IND: {
                    final long threadId = getDeliveryOrReadReportThreadId(mContext, pdu);
                    if (threadId == -1) {
                        // The associated SendReq isn't found, therefore skip
                        // processing this PDU.
                        Rlog.e(TAG, "Failed to find delivery or read report's thread id");
                        break;
                    }
                    final Uri uri = persister.persist(
                            pdu,
                            Telephony.Mms.Inbox.CONTENT_URI,
                            true/*createThreadId*/,
                            true/*groupMmsEnabled*/,
                            null/*preOpenedFiles*/);
                    if (uri == null) {
                        Rlog.e(TAG, "Failed to persist delivery or read report");
                        break;
                    }
                    // Update thread ID for ReadOrigInd & DeliveryInd.
                    final ContentValues values = new ContentValues(1);
                    values.put(Telephony.Mms.THREAD_ID, threadId);
                    if (SqliteWrapper.update(
                            mContext,
                            mContext.getContentResolver(),
                            uri,
                            values,
                            null/*where*/,
                            null/*selectionArgs*/) != 1) {
                        Rlog.e(TAG, "Failed to update delivery or read report thread id");
                    }
                    break;
                }
                case MESSAGE_TYPE_NOTIFICATION_IND: {
                    final NotificationInd nInd = (NotificationInd) pdu;

                    Bundle configs = SmsManager.getSmsManagerForSubscriber(subId)
                            .getCarrierConfigValues();
                    if (configs != null && configs.getBoolean(
                        SmsManager.MMS_CONFIG_APPEND_TRANSACTION_ID, false)) {
                        final byte [] contentLocation = nInd.getContentLocation();
                        if ('=' == contentLocation[contentLocation.length - 1]) {
                            byte [] transactionId = nInd.getTransactionId();
                            byte [] contentLocationWithId = new byte [contentLocation.length
                                    + transactionId.length];
                            System.arraycopy(contentLocation, 0, contentLocationWithId,
                                    0, contentLocation.length);
                            System.arraycopy(transactionId, 0, contentLocationWithId,
                                    contentLocation.length, transactionId.length);
                            nInd.setContentLocation(contentLocationWithId);
                        }
                    }
                    if (!isDuplicateNotification(mContext, nInd)) {
                        final Uri uri = persister.persist(
                                pdu,
                                Telephony.Mms.Inbox.CONTENT_URI,
                                true/*createThreadId*/,
                                true/*groupMmsEnabled*/,
                                null/*preOpenedFiles*/);
                        if (uri == null) {
                            Rlog.e(TAG, "Failed to save MMS WAP push notification ind");
                        }
                    } else {
                        Rlog.d(TAG, "Skip storing duplicate MMS WAP push notification ind: "
                                + new String(nInd.getContentLocation()));
                    }
                    break;
                }
                default:
                    Log.e(TAG, "Received unrecognized WAP Push PDU.");
            }
        } catch (MmsException e) {
            Log.e(TAG, "Failed to save MMS WAP push data: type=" + type, e);
        } catch (RuntimeException e) {
            Log.e(TAG, "Unexpected RuntimeException in persisting MMS WAP push data", e);
        }

    }

    private static final String THREAD_ID_SELECTION =
            Telephony.Mms.MESSAGE_ID + "=? AND " + Telephony.Mms.MESSAGE_TYPE + "=?";

    private static long getDeliveryOrReadReportThreadId(Context context, GenericPdu pdu) {
        String messageId;
        if (pdu instanceof DeliveryInd) {
            messageId = new String(((DeliveryInd) pdu).getMessageId());
        } else if (pdu instanceof ReadOrigInd) {
            messageId = new String(((ReadOrigInd) pdu).getMessageId());
        } else {
            Rlog.e(TAG, "WAP Push data is neither delivery or read report type: "
                    + pdu.getClass().getCanonicalName());
            return -1L;
        }
        Cursor cursor = null;
        try {
            cursor = SqliteWrapper.query(
                    context,
                    context.getContentResolver(),
                    Telephony.Mms.CONTENT_URI,
                    new String[]{ Telephony.Mms.THREAD_ID },
                    THREAD_ID_SELECTION,
                    new String[]{
                            DatabaseUtils.sqlEscapeString(messageId),
                            Integer.toString(PduHeaders.MESSAGE_TYPE_SEND_REQ)
                    },
                    null/*sortOrder*/);
            if (cursor != null && cursor.moveToFirst()) {
                return cursor.getLong(0);
            }
        } catch (SQLiteException e) {
            Rlog.e(TAG, "Failed to query delivery or read report thread id", e);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return -1L;
    }

    private static final String LOCATION_SELECTION =
            Telephony.Mms.MESSAGE_TYPE + "=? AND " + Telephony.Mms.CONTENT_LOCATION + " =?";

    private static boolean isDuplicateNotification(Context context, NotificationInd nInd) {
        final byte[] rawLocation = nInd.getContentLocation();
        if (rawLocation != null) {
            String location = new String(rawLocation);
            String[] selectionArgs = new String[] { location };
            Cursor cursor = null;
            try {
                cursor = SqliteWrapper.query(
                        context,
                        context.getContentResolver(),
                        Telephony.Mms.CONTENT_URI,
                        new String[]{Telephony.Mms._ID},
                        LOCATION_SELECTION,
                        new String[]{
                                Integer.toString(PduHeaders.MESSAGE_TYPE_NOTIFICATION_IND),
                                new String(rawLocation)
                        },
                        null/*sortOrder*/);
                if (cursor != null && cursor.getCount() > 0) {
                    // We already received the same notification before.
                    return true;
                }
            } catch (SQLiteException e) {
                Rlog.e(TAG, "failed to query existing notification ind", e);
            } finally {
                if (cursor != null) {
                    cursor.close();
                }
            }
        }
        return false;
    }
}
