/*
 * Copyright (C) 2008 The Android Open Source Project
 * Copyright (c) 2011-2013, The Linux Foundation. All rights reserved.
 * Not a Contribution.
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

import static com.android.internal.util.DumpUtils.checkDumpPermission;

import android.annotation.Nullable;
import android.annotation.UnsupportedAppUsage;
import android.app.ActivityManager;
import android.app.ActivityThread;
import android.app.AppOpsManager;
import android.app.PendingIntent;
import android.content.Context;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.ServiceManager;
import android.provider.Telephony.Sms.Intents;
import android.telephony.IFinancialSmsCallback;
import android.telephony.Rlog;
import android.telephony.SmsManager;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;

import com.android.internal.util.IndentingPrintWriter;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Implements the ISmsImplBase interface used in the SmsManager API.
 */
public class SmsController extends ISmsImplBase {
    static final String LOG_TAG = "SmsController";

    private final Context mContext;

    protected SmsController(Context context) {
        mContext = context;
        if (ServiceManager.getService("isms") == null) {
            ServiceManager.addService("isms", this);
        }
    }

    private Phone getPhone(int subId) {
        Phone phone = PhoneFactory.getPhone(SubscriptionManager.getPhoneId(subId));
        if (phone == null) {
            phone = PhoneFactory.getDefaultPhone();
        }
        return phone;
    }

    private SmsPermissions getSmsPermissions(int subId) {
        Phone phone = getPhone(subId);

        return new SmsPermissions(phone, mContext,
                (AppOpsManager) mContext.getSystemService(Context.APP_OPS_SERVICE));
    }

    @UnsupportedAppUsage
    @Override
    public boolean updateMessageOnIccEfForSubscriber(int subId, String callingPackage, int index,
            int status, byte[] pdu) {
        IccSmsInterfaceManager iccSmsIntMgr = getIccSmsInterfaceManager(subId);
        if (iccSmsIntMgr != null) {
            return iccSmsIntMgr.updateMessageOnIccEf(callingPackage, index, status, pdu);
        } else {
            Rlog.e(LOG_TAG, "updateMessageOnIccEfForSubscriber iccSmsIntMgr is null"
                    + " for Subscription: " + subId);
            return false;
        }
    }

    @UnsupportedAppUsage
    @Override
    public boolean copyMessageToIccEfForSubscriber(int subId, String callingPackage, int status,
            byte[] pdu, byte[] smsc) {
        IccSmsInterfaceManager iccSmsIntMgr = getIccSmsInterfaceManager(subId);
        if (iccSmsIntMgr != null) {
            return iccSmsIntMgr.copyMessageToIccEf(callingPackage, status, pdu, smsc);
        } else {
            Rlog.e(LOG_TAG, "copyMessageToIccEfForSubscriber iccSmsIntMgr is null"
                    + " for Subscription: " + subId);
            return false;
        }
    }

    @UnsupportedAppUsage
    @Override
    public List<SmsRawData> getAllMessagesFromIccEfForSubscriber(int subId, String callingPackage) {
        IccSmsInterfaceManager iccSmsIntMgr = getIccSmsInterfaceManager(subId);
        if (iccSmsIntMgr != null) {
            return iccSmsIntMgr.getAllMessagesFromIccEf(callingPackage);
        } else {
            Rlog.e(LOG_TAG, "getAllMessagesFromIccEfForSubscriber iccSmsIntMgr is"
                    + " null for Subscription: " + subId);
            return null;
        }
    }

    @UnsupportedAppUsage
    @Override
    public void sendDataForSubscriber(int subId, String callingPackage, String destAddr,
            String scAddr, int destPort, byte[] data, PendingIntent sentIntent,
            PendingIntent deliveryIntent) {
        IccSmsInterfaceManager iccSmsIntMgr = getIccSmsInterfaceManager(subId);
        if (iccSmsIntMgr != null) {
            iccSmsIntMgr.sendData(callingPackage, destAddr, scAddr, destPort, data,
                    sentIntent, deliveryIntent);
        } else {
            Rlog.e(LOG_TAG, "sendDataForSubscriber iccSmsIntMgr is null for"
                    + " Subscription: " + subId);
            // TODO: Use a more specific error code to replace RESULT_ERROR_GENERIC_FAILURE.
            sendErrorInPendingIntent(sentIntent, SmsManager.RESULT_ERROR_GENERIC_FAILURE);
        }
    }

    @Override
    public void sendDataForSubscriberWithSelfPermissions(int subId, String callingPackage,
            String destAddr, String scAddr, int destPort, byte[] data, PendingIntent sentIntent,
            PendingIntent deliveryIntent) {
        sendDataForSubscriberWithSelfPermissionsInternal(subId, callingPackage, destAddr, scAddr,
                destPort, data, sentIntent, deliveryIntent, false /* isForVvm */);
    }

    private void sendDataForSubscriberWithSelfPermissionsInternal(int subId, String callingPackage,
            String destAddr, String scAddr, int destPort, byte[] data, PendingIntent sentIntent,
            PendingIntent deliveryIntent, boolean isForVvm) {
        IccSmsInterfaceManager iccSmsIntMgr = getIccSmsInterfaceManager(subId);
        if (iccSmsIntMgr != null) {
            iccSmsIntMgr.sendDataWithSelfPermissions(callingPackage, destAddr, scAddr, destPort,
                    data, sentIntent, deliveryIntent, isForVvm);
        } else {
            Rlog.e(LOG_TAG, "sendText iccSmsIntMgr is null for"
                    + " Subscription: " + subId);
            sendErrorInPendingIntent(sentIntent, SmsManager.RESULT_ERROR_GENERIC_FAILURE);
        }
    }

    @Override
    public void sendTextForSubscriber(int subId, String callingPackage, String destAddr,
            String scAddr, String text, PendingIntent sentIntent, PendingIntent deliveryIntent,
            boolean persistMessageForNonDefaultSmsApp) {
        if (!getSmsPermissions(subId).checkCallingCanSendText(persistMessageForNonDefaultSmsApp,
                callingPackage, "Sending SMS message")) {
            sendErrorInPendingIntent(sentIntent, SmsManager.RESULT_ERROR_GENERIC_FAILURE);
            return;
        }

        long token = Binder.clearCallingIdentity();
        SubscriptionInfo info;
        try {
            info = getSubscriptionInfo(subId);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
        if (isBluetoothSubscription(info)) {
            sendBluetoothText(info, destAddr, text, sentIntent, deliveryIntent);
        } else {
            sendIccText(subId, callingPackage, destAddr, scAddr, text, sentIntent, deliveryIntent,
                    persistMessageForNonDefaultSmsApp);
        }
    }

    private boolean isBluetoothSubscription(SubscriptionInfo info) {
        return info != null
                && info.getSubscriptionType() == SubscriptionManager.SUBSCRIPTION_TYPE_REMOTE_SIM;
    }

    private void sendBluetoothText(SubscriptionInfo info, String destAddr,
            String text, PendingIntent sentIntent, PendingIntent deliveryIntent) {
        BtSmsInterfaceManager btSmsInterfaceManager = new BtSmsInterfaceManager();
        btSmsInterfaceManager.sendText(destAddr, text, sentIntent, deliveryIntent, info);
    }

    private void sendIccText(int subId, String callingPackage, String destAddr,
            String scAddr, String text, PendingIntent sentIntent, PendingIntent deliveryIntent,
            boolean persistMessageForNonDefaultSmsApp) {
        IccSmsInterfaceManager iccSmsIntMgr = getIccSmsInterfaceManager(subId);
        if (iccSmsIntMgr != null) {
            iccSmsIntMgr.sendText(callingPackage, destAddr, scAddr, text, sentIntent,
                    deliveryIntent, persistMessageForNonDefaultSmsApp);
        } else {
            Rlog.e(LOG_TAG, "sendTextForSubscriber iccSmsIntMgr is null for"
                    + " Subscription: " + subId);
            sendErrorInPendingIntent(sentIntent, SmsManager.RESULT_ERROR_GENERIC_FAILURE);
        }
    }

    @Override
    public void sendTextForSubscriberWithSelfPermissions(int subId, String callingPackage,
            String destAddr, String scAddr, String text, PendingIntent sentIntent,
            PendingIntent deliveryIntent, boolean persistMessage) {
        sendTextForSubscriberWithSelfPermissionsInternal(subId, callingPackage, destAddr, scAddr,
                text, sentIntent, deliveryIntent, persistMessage, false /* isForVvm */);
    }

    private void sendTextForSubscriberWithSelfPermissionsInternal(int subId, String callingPackage,
            String destAddr, String scAddr, String text, PendingIntent sentIntent,
            PendingIntent deliveryIntent, boolean persistMessage, boolean isForVvm) {
        IccSmsInterfaceManager iccSmsIntMgr = getIccSmsInterfaceManager(subId);
        if (iccSmsIntMgr != null) {
            iccSmsIntMgr.sendTextWithSelfPermissions(callingPackage, destAddr, scAddr, text,
                    sentIntent, deliveryIntent, persistMessage, isForVvm);
        } else {
            Rlog.e(LOG_TAG, "sendText iccSmsIntMgr is null for"
                    + " Subscription: " + subId);
            sendErrorInPendingIntent(sentIntent, SmsManager.RESULT_ERROR_GENERIC_FAILURE);
        }
    }

    @Override
    public void sendTextForSubscriberWithOptions(int subId, String callingPackage,
            String destAddr, String scAddr, String parts, PendingIntent sentIntent,
            PendingIntent deliveryIntent, boolean persistMessage, int priority,
            boolean expectMore, int validityPeriod) {
        IccSmsInterfaceManager iccSmsIntMgr = getIccSmsInterfaceManager(subId);
        if (iccSmsIntMgr != null) {
            iccSmsIntMgr.sendTextWithOptions(callingPackage, destAddr, scAddr, parts, sentIntent,
                    deliveryIntent, persistMessage, priority, expectMore, validityPeriod);
        } else {
            Rlog.e(LOG_TAG, "sendTextWithOptions iccSmsIntMgr is null for"
                    + " Subscription: " + subId);
            sendErrorInPendingIntent(sentIntent, SmsManager.RESULT_ERROR_GENERIC_FAILURE);
        }
    }

    @Override
    public void sendMultipartTextForSubscriber(int subId, String callingPackage, String destAddr,
            String scAddr, List<String> parts, List<PendingIntent> sentIntents,
            List<PendingIntent> deliveryIntents, boolean persistMessageForNonDefaultSmsApp) {
        IccSmsInterfaceManager iccSmsIntMgr = getIccSmsInterfaceManager(subId);
        if (iccSmsIntMgr != null) {
            iccSmsIntMgr.sendMultipartText(callingPackage, destAddr, scAddr, parts, sentIntents,
                    deliveryIntents, persistMessageForNonDefaultSmsApp);
        } else {
            Rlog.e(LOG_TAG, "sendMultipartTextForSubscriber iccSmsIntMgr is null for"
                    + " Subscription: " + subId);
            sendErrorInPendingIntents(sentIntents, SmsManager.RESULT_ERROR_GENERIC_FAILURE);
        }
    }

    @Override
    public void sendMultipartTextForSubscriberWithOptions(int subId, String callingPackage,
            String destAddr, String scAddr, List<String> parts, List<PendingIntent> sentIntents,
            List<PendingIntent> deliveryIntents, boolean persistMessage, int priority,
            boolean expectMore, int validityPeriod) {
        IccSmsInterfaceManager iccSmsIntMgr = getIccSmsInterfaceManager(subId);
        if (iccSmsIntMgr != null) {
            iccSmsIntMgr.sendMultipartTextWithOptions(callingPackage, destAddr, scAddr, parts,
                    sentIntents, deliveryIntents, persistMessage, priority, expectMore,
                    validityPeriod);
        } else {
            Rlog.e(LOG_TAG, "sendMultipartTextWithOptions iccSmsIntMgr is null for"
                    + " Subscription: " + subId);
            sendErrorInPendingIntents(sentIntents, SmsManager.RESULT_ERROR_GENERIC_FAILURE);
        }
    }

    @UnsupportedAppUsage
    @Override
    public boolean enableCellBroadcastForSubscriber(int subId, int messageIdentifier, int ranType) {
        return enableCellBroadcastRangeForSubscriber(subId, messageIdentifier, messageIdentifier,
                ranType);
    }

    @UnsupportedAppUsage
    @Override
    public boolean enableCellBroadcastRangeForSubscriber(int subId, int startMessageId,
            int endMessageId, int ranType) {
        IccSmsInterfaceManager iccSmsIntMgr = getIccSmsInterfaceManager(subId);
        if (iccSmsIntMgr != null) {
            return iccSmsIntMgr.enableCellBroadcastRange(startMessageId, endMessageId, ranType);
        } else {
            Rlog.e(LOG_TAG, "enableCellBroadcastRangeForSubscriber iccSmsIntMgr is null for"
                    + " Subscription: " + subId);
        }
        return false;
    }

    @UnsupportedAppUsage
    @Override
    public boolean disableCellBroadcastForSubscriber(int subId,
            int messageIdentifier, int ranType) {
        return disableCellBroadcastRangeForSubscriber(subId, messageIdentifier, messageIdentifier,
                ranType);
    }

    @UnsupportedAppUsage
    @Override
    public boolean disableCellBroadcastRangeForSubscriber(int subId, int startMessageId,
            int endMessageId, int ranType) {
        IccSmsInterfaceManager iccSmsIntMgr = getIccSmsInterfaceManager(subId);
        if (iccSmsIntMgr != null) {
            return iccSmsIntMgr.disableCellBroadcastRange(startMessageId, endMessageId, ranType);
        } else {
            Rlog.e(LOG_TAG, "disableCellBroadcastRangeForSubscriber iccSmsIntMgr is null for"
                    + " Subscription:" + subId);
        }
        return false;
    }

    @Override
    public int getPremiumSmsPermission(String packageName) {
        return getPremiumSmsPermissionForSubscriber(getPreferredSmsSubscription(), packageName);
    }

    @Override
    public int getPremiumSmsPermissionForSubscriber(int subId, String packageName) {
        IccSmsInterfaceManager iccSmsIntMgr = getIccSmsInterfaceManager(subId);
        if (iccSmsIntMgr != null) {
            return iccSmsIntMgr.getPremiumSmsPermission(packageName);
        } else {
            Rlog.e(LOG_TAG, "getPremiumSmsPermissionForSubscriber iccSmsIntMgr is null");
        }
        //TODO Rakesh
        return 0;
    }

    @Override
    public void setPremiumSmsPermission(String packageName, int permission) {
        setPremiumSmsPermissionForSubscriber(getPreferredSmsSubscription(), packageName,
                permission);
    }

    @Override
    public void setPremiumSmsPermissionForSubscriber(int subId, String packageName,
            int permission) {
        IccSmsInterfaceManager iccSmsIntMgr = getIccSmsInterfaceManager(subId);
        if (iccSmsIntMgr != null) {
            iccSmsIntMgr.setPremiumSmsPermission(packageName, permission);
        } else {
            Rlog.e(LOG_TAG, "setPremiumSmsPermissionForSubscriber iccSmsIntMgr is null");
        }
    }

    @UnsupportedAppUsage
    @Override
    public boolean isImsSmsSupportedForSubscriber(int subId) {
        IccSmsInterfaceManager iccSmsIntMgr = getIccSmsInterfaceManager(subId);
        if (iccSmsIntMgr != null) {
            return iccSmsIntMgr.isImsSmsSupported();
        } else {
            Rlog.e(LOG_TAG, "isImsSmsSupportedForSubscriber iccSmsIntMgr is null");
        }
        return false;
    }

    @Override
    public boolean isSmsSimPickActivityNeeded(int subId) {
        final Context context = ActivityThread.currentApplication().getApplicationContext();
        ActivityManager am = context.getSystemService(ActivityManager.class);
        // Don't show the SMS SIM Pick activity if it is not foreground.
        boolean isCallingProcessForeground = am != null
                && am.getUidImportance(Binder.getCallingUid())
                        == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND;
        if (!isCallingProcessForeground) {
            Rlog.d(LOG_TAG, "isSmsSimPickActivityNeeded: calling process not foreground. "
                    + "Suppressing activity.");
            return false;
        }
        TelephonyManager telephonyManager =
                (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
        List<SubscriptionInfo> subInfoList;
        final long identity = Binder.clearCallingIdentity();
        try {
            subInfoList = SubscriptionManager.from(context).getActiveSubscriptionInfoList();
        } finally {
            Binder.restoreCallingIdentity(identity);
        }

        if (subInfoList != null) {
            final int subInfoLength = subInfoList.size();

            for (int i = 0; i < subInfoLength; ++i) {
                final SubscriptionInfo sir = subInfoList.get(i);
                if (sir != null && sir.getSubscriptionId() == subId) {
                    // The subscription id is valid, sms sim pick activity not needed
                    return false;
                }
            }

            // If reached here and multiple SIMs and subs present, sms sim pick activity is needed
            if (subInfoLength > 0 && telephonyManager.getSimCount() > 1) {
                return true;
            }
        }

        return false;
    }

    @UnsupportedAppUsage
    @Override
    public String getImsSmsFormatForSubscriber(int subId) {
        IccSmsInterfaceManager iccSmsIntMgr = getIccSmsInterfaceManager(subId);
        if (iccSmsIntMgr != null) {
            return iccSmsIntMgr.getImsSmsFormat();
        } else {
            Rlog.e(LOG_TAG, "getImsSmsFormatForSubscriber iccSmsIntMgr is null");
        }
        return null;
    }

    @Override
    public void injectSmsPduForSubscriber(
            int subId, byte[] pdu, String format, PendingIntent receivedIntent) {
        IccSmsInterfaceManager iccSmsIntMgr = getIccSmsInterfaceManager(subId);
        if (iccSmsIntMgr != null) {
            iccSmsIntMgr.injectSmsPdu(pdu, format, receivedIntent);
        } else {
            Rlog.e(LOG_TAG, "injectSmsPduForSubscriber iccSmsIntMgr is null");
            // RESULT_SMS_GENERIC_ERROR is documented for injectSmsPdu
            sendErrorInPendingIntent(receivedIntent, Intents.RESULT_SMS_GENERIC_ERROR);
        }
    }

    /**
     * Get preferred SMS subscription.
     *
     * @return User-defined default SMS subscription. If there is no default, return the active
     * subscription if there is only one active. If no preference can be found, return
     * {@link SubscriptionManager#INVALID_SUBSCRIPTION_ID}.
     */
    @UnsupportedAppUsage
    @Override
    public int getPreferredSmsSubscription() {
        // If there is a default, choose that one.
        int defaultSubId = SubscriptionController.getInstance().getDefaultSmsSubId();
        if (SubscriptionManager.isValidSubscriptionId(defaultSubId)) {
            return defaultSubId;
        }
        // No default, if there is only one sub active, choose that as the "preferred" sub id.
        long token = Binder.clearCallingIdentity();
        try {
            int[] activeSubs = SubscriptionController.getInstance()
                    .getActiveSubIdList(true /*visibleOnly*/);
            if (activeSubs.length == 1) {
                return activeSubs[0];
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
        // No preference can be found.
        return SubscriptionManager.INVALID_SUBSCRIPTION_ID;
    }

    /**
     * Get SMS prompt property enabled or not
     *
     * @return True if SMS prompt is enabled.
     */
    @Override
    public boolean isSMSPromptEnabled() {
        return PhoneFactory.isSMSPromptEnabled();
    }

    @Override
    public void sendStoredText(int subId, String callingPkg, Uri messageUri, String scAddress,
            PendingIntent sentIntent, PendingIntent deliveryIntent) {
        IccSmsInterfaceManager iccSmsIntMgr = getIccSmsInterfaceManager(subId);
        if (iccSmsIntMgr != null) {
            iccSmsIntMgr.sendStoredText(callingPkg, messageUri, scAddress, sentIntent,
                    deliveryIntent);
        } else {
            Rlog.e(LOG_TAG, "sendStoredText iccSmsIntMgr is null for subscription: " + subId);
            sendErrorInPendingIntent(sentIntent, SmsManager.RESULT_ERROR_GENERIC_FAILURE);
        }
    }

    @Override
    public void sendStoredMultipartText(int subId, String callingPkg, Uri messageUri,
            String scAddress, List<PendingIntent> sentIntents,
            List<PendingIntent> deliveryIntents) {
        IccSmsInterfaceManager iccSmsIntMgr = getIccSmsInterfaceManager(subId);
        if (iccSmsIntMgr != null) {
            iccSmsIntMgr.sendStoredMultipartText(callingPkg, messageUri, scAddress, sentIntents,
                    deliveryIntents);
        } else {
            Rlog.e(LOG_TAG, "sendStoredMultipartText iccSmsIntMgr is null for subscription: "
                    + subId);
            sendErrorInPendingIntents(sentIntents, SmsManager.RESULT_ERROR_GENERIC_FAILURE);
        }
    }

    @Override
    public String createAppSpecificSmsTokenWithPackageInfo(
            int subId, String callingPkg, String prefixes, PendingIntent intent) {
        return getPhone(subId).getAppSmsManager().createAppSpecificSmsTokenWithPackageInfo(
                subId, callingPkg, prefixes, intent);
    }

    @Override
    public String createAppSpecificSmsToken(int subId, String callingPkg, PendingIntent intent) {
        return getPhone(subId).getAppSmsManager().createAppSpecificSmsToken(callingPkg, intent);
    }

    @Override
    public void getSmsMessagesForFinancialApp(
            int subId, String callingPkg, Bundle params, IFinancialSmsCallback callback) {
        getPhone(subId).getAppSmsManager().getSmsMessagesForFinancialApp(
                callingPkg, params, callback);
    }

    @Override
    public int checkSmsShortCodeDestination(
            int subId, String callingPackage, String destAddress, String countryIso) {
        if (!TelephonyPermissions.checkCallingOrSelfReadPhoneState(getPhone(subId).getContext(),
                subId, callingPackage, "checkSmsShortCodeDestination")) {
            return SmsManager.SMS_CATEGORY_NOT_SHORT_CODE;
        }
        final long identity = Binder.clearCallingIdentity();
        try {
            return getPhone(subId).mSmsUsageMonitor.checkDestination(destAddress, countryIso);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    /**
     * Internal API to send visual voicemail related SMS. This is not exposed outside the phone
     * process, and should be called only after verifying that the caller is the default VVM app.
     */
    public void sendVisualVoicemailSmsForSubscriber(String callingPackage, int subId,
            String number, int port, String text, PendingIntent sentIntent) {
        if (port == 0) {
            sendTextForSubscriberWithSelfPermissionsInternal(subId, callingPackage, number,
                    null, text, sentIntent, null, false, true /* isForVvm */);
        } else {
            byte[] data = text.getBytes(StandardCharsets.UTF_8);
            sendDataForSubscriberWithSelfPermissionsInternal(subId, callingPackage, number,
                    null, (short) port, data, sentIntent, null, true /* isForVvm */);
        }
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (!checkDumpPermission(mContext, LOG_TAG, pw)) {
            return;
        }

        IndentingPrintWriter indentingPW =
                new IndentingPrintWriter(pw, "    " /* singleIndent */);
        for (Phone phone : PhoneFactory.getPhones()) {
            int subId = phone.getSubId();
            indentingPW.println(String.format("SmsManager for subId = %d:", subId));
            indentingPW.increaseIndent();
            if (getIccSmsInterfaceManager(subId) != null) {
                getIccSmsInterfaceManager(subId).dump(fd, indentingPW, args);
            }
            indentingPW.decreaseIndent();
        }
        indentingPW.flush();
    }

    @UnsupportedAppUsage
    private void sendErrorInPendingIntent(@Nullable PendingIntent intent, int errorCode) {
        if (intent != null) {
            try {
                intent.send(errorCode);
            } catch (PendingIntent.CanceledException ex) {
            }
        }
    }

    @UnsupportedAppUsage
    private void sendErrorInPendingIntents(List<PendingIntent> intents, int errorCode) {
        if (intents == null) {
            return;
        }

        for (PendingIntent intent : intents) {
            sendErrorInPendingIntent(intent, errorCode);
        }
    }

    /**
     * Get sms interface manager object based on subscription.
     *
     * @return ICC SMS manager
     */
    @UnsupportedAppUsage
    private @Nullable IccSmsInterfaceManager getIccSmsInterfaceManager(int subId) {
        return getPhone(subId).getIccSmsInterfaceManager();
    }

    private SubscriptionInfo getSubscriptionInfo(int subId) {
        SubscriptionManager manager = (SubscriptionManager) mContext
                .getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
        return manager.getActiveSubscriptionInfo(subId);
    }
}
