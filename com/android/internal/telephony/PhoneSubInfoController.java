/*
 * Copyright (C) 2006 The Android Open Source Project
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

import static android.Manifest.permission.MODIFY_PHONE_STATE;
import static android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE;

import android.annotation.UnsupportedAppUsage;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.telephony.ImsiEncryptionInfo;
import android.telephony.PhoneNumberUtils;
import android.telephony.Rlog;
import android.telephony.SubscriptionManager;

import com.android.internal.telephony.uicc.IsimRecords;
import com.android.internal.telephony.uicc.UiccCard;
import com.android.internal.telephony.uicc.UiccCardApplication;

public class PhoneSubInfoController extends IPhoneSubInfo.Stub {
    private static final String TAG = "PhoneSubInfoController";
    private static final boolean DBG = true;
    private static final boolean VDBG = false; // STOPSHIP if true

    @UnsupportedAppUsage
    private final Phone[] mPhone;
    @UnsupportedAppUsage
    private final Context mContext;
    private final AppOpsManager mAppOps;

    public PhoneSubInfoController(Context context, Phone[] phone) {
        mPhone = phone;
        if (ServiceManager.getService("iphonesubinfo") == null) {
            ServiceManager.addService("iphonesubinfo", this);
        }
        mContext = context;
        mAppOps = (AppOpsManager) mContext.getSystemService(Context.APP_OPS_SERVICE);
    }

    public String getDeviceId(String callingPackage) {
        return getDeviceIdForPhone(SubscriptionManager.getPhoneId(getDefaultSubscription()),
                callingPackage);
    }

    public String getDeviceIdForPhone(int phoneId, String callingPackage) {
        return callPhoneMethodForPhoneIdWithReadDeviceIdentifiersCheck(phoneId, callingPackage,
                "getDeviceId", (phone)-> phone.getDeviceId());
    }

    public String getNaiForSubscriber(int subId, String callingPackage) {
        return callPhoneMethodForSubIdWithReadCheck(subId, callingPackage, "getNai",
                (phone)-> phone.getNai());
    }

    public String getImeiForSubscriber(int subId, String callingPackage) {
        return callPhoneMethodForSubIdWithReadDeviceIdentifiersCheck(subId, callingPackage,
                "getImei", (phone)-> phone.getImei());
    }

    public ImsiEncryptionInfo getCarrierInfoForImsiEncryption(int subId, int keyType,
                                                              String callingPackage) {
        return callPhoneMethodForSubIdWithReadCheck(subId, callingPackage,
                "getCarrierInfoForImsiEncryption",
                (phone)-> phone.getCarrierInfoForImsiEncryption(keyType));
    }

    public void setCarrierInfoForImsiEncryption(int subId, String callingPackage,
                                                ImsiEncryptionInfo imsiEncryptionInfo) {
        callPhoneMethodForSubIdWithModifyCheck(subId, callingPackage,
                "setCarrierInfoForImsiEncryption",
                (phone)-> {
                    phone.setCarrierInfoForImsiEncryption(imsiEncryptionInfo);
                    return null;
                });
    }

    /**
     *  Resets the Carrier Keys in the database. This involves 2 steps:
     *  1. Delete the keys from the database.
     *  2. Send an intent to download new Certificates.
     *  @param subId
     *  @param callingPackage
     */
    public void resetCarrierKeysForImsiEncryption(int subId, String callingPackage) {
        callPhoneMethodForSubIdWithModifyCheck(subId, callingPackage,
                "setCarrierInfoForImsiEncryption",
                (phone)-> {
                    phone.resetCarrierKeysForImsiEncryption();
                    return null;
                });
    }


    public String getDeviceSvn(String callingPackage) {
        return getDeviceSvnUsingSubId(getDefaultSubscription(), callingPackage);
    }

    public String getDeviceSvnUsingSubId(int subId, String callingPackage) {
        return callPhoneMethodForSubIdWithReadCheck(subId, callingPackage, "getDeviceSvn",
                (phone)-> phone.getDeviceSvn());
    }

    public String getSubscriberId(String callingPackage) {
        return getSubscriberIdForSubscriber(getDefaultSubscription(), callingPackage);
    }

    public String getSubscriberIdForSubscriber(int subId, String callingPackage) {
        String message = "getSubscriberId";
        long identity = Binder.clearCallingIdentity();
        boolean isActive;
        try {
            isActive = SubscriptionController.getInstance().isActiveSubId(subId, callingPackage);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
        if (isActive) {
            return callPhoneMethodForSubIdWithReadSubscriberIdentifiersCheck(subId, callingPackage,
                    message, (phone) -> phone.getSubscriberId());
        } else {
            if (!TelephonyPermissions.checkCallingOrSelfReadSubscriberIdentifiers(
                    mContext, subId, callingPackage, message)) {
                return null;
            }
            identity = Binder.clearCallingIdentity();
            try {
                return SubscriptionController.getInstance().getImsiPrivileged(subId);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }

    /**
     * Retrieves the serial number of the ICC, if applicable.
     */
    public String getIccSerialNumber(String callingPackage) {
        return getIccSerialNumberForSubscriber(getDefaultSubscription(), callingPackage);
    }

    public String getIccSerialNumberForSubscriber(int subId, String callingPackage) {
        return callPhoneMethodForSubIdWithReadSubscriberIdentifiersCheck(subId, callingPackage,
                "getIccSerialNumber", (phone) -> phone.getIccSerialNumber());
    }

    public String getLine1Number(String callingPackage) {
        return getLine1NumberForSubscriber(getDefaultSubscription(), callingPackage);
    }

    public String getLine1NumberForSubscriber(int subId, String callingPackage) {
        return callPhoneMethodForSubIdWithReadPhoneNumberCheck(
                subId, callingPackage, "getLine1Number",
                (phone)-> phone.getLine1Number());
    }

    public String getLine1AlphaTag(String callingPackage) {
        return getLine1AlphaTagForSubscriber(getDefaultSubscription(), callingPackage);
    }

    public String getLine1AlphaTagForSubscriber(int subId, String callingPackage) {
        return callPhoneMethodForSubIdWithReadCheck(subId, callingPackage, "getLine1AlphaTag",
                (phone)-> phone.getLine1AlphaTag());
    }

    public String getMsisdn(String callingPackage) {
        return getMsisdnForSubscriber(getDefaultSubscription(), callingPackage);
    }

    public String getMsisdnForSubscriber(int subId, String callingPackage) {
        return callPhoneMethodForSubIdWithReadCheck(subId, callingPackage, "getMsisdn",
                (phone)-> phone.getMsisdn());
    }

    public String getVoiceMailNumber(String callingPackage) {
        return getVoiceMailNumberForSubscriber(getDefaultSubscription(), callingPackage);
    }

    public String getVoiceMailNumberForSubscriber(int subId, String callingPackage) {
        return callPhoneMethodForSubIdWithReadCheck(subId, callingPackage,
                "getVoiceMailNumber", (phone)-> {
                    String number = PhoneNumberUtils.extractNetworkPortion(
                            phone.getVoiceMailNumber());
                    if (VDBG) log("VM: getVoiceMailNUmber: " + number);
                    return number;
                });
    }

    public String getVoiceMailAlphaTag(String callingPackage) {
        return getVoiceMailAlphaTagForSubscriber(getDefaultSubscription(), callingPackage);
    }

    public String getVoiceMailAlphaTagForSubscriber(int subId, String callingPackage) {
        return callPhoneMethodForSubIdWithReadCheck(subId, callingPackage,
                "getVoiceMailAlphaTag", (phone)-> phone.getVoiceMailAlphaTag());
    }

    /**
     * get Phone object based on subId.
     **/
    @UnsupportedAppUsage
    private Phone getPhone(int subId) {
        int phoneId = SubscriptionManager.getPhoneId(subId);
        if (!SubscriptionManager.isValidPhoneId(phoneId)) {
            phoneId = 0;
        }
        return mPhone[phoneId];
    }

    /**
     * Make sure caller has either read privileged phone permission or carrier privilege.
     *
     * @throws SecurityException if the caller does not have the required permission/privilege
     */
    private void enforcePrivilegedPermissionOrCarrierPrivilege(int subId, String message) {
        // TODO(b/73660190): Migrate to
        // TelephonyPermissions.enforceCallingOrSelfModifyPermissionOrCarrierPrivileges and delete
        // this helper method.
        int permissionResult = mContext.checkCallingOrSelfPermission(
                READ_PRIVILEGED_PHONE_STATE);
        if (permissionResult == PackageManager.PERMISSION_GRANTED) {
            return;
        }
        if (VDBG) log("No read privileged phone permission, check carrier privilege next.");
        TelephonyPermissions.enforceCallingOrSelfCarrierPrivilege(subId, message);
    }

    /**
     * Make sure caller has modify phone state permission.
     */
    private void enforceModifyPermission() {
        mContext.enforceCallingOrSelfPermission(MODIFY_PHONE_STATE,
                "Requires MODIFY_PHONE_STATE");
    }

    @UnsupportedAppUsage
    private int getDefaultSubscription() {
        return  PhoneFactory.getDefaultSubscription();
    }

    /**
    * get the Isim Impi based on subId
    */
    public String getIsimImpi(int subId) {
        return callPhoneMethodForSubIdWithPrivilegedCheck(subId, "getIsimImpi",
                (phone) -> {
                    IsimRecords isim = phone.getIsimRecords();
                    if (isim != null) {
                        return isim.getIsimImpi();
                    } else {
                        return null;
                    }
                });
    }

    /**
    * get the Isim Domain based on subId
    */
    public String getIsimDomain(int subId) {
        return callPhoneMethodForSubIdWithPrivilegedCheck(subId, "getIsimDomain",
                (phone) -> {
                    IsimRecords isim = phone.getIsimRecords();
                    if (isim != null) {
                        return isim.getIsimDomain();
                    } else {
                        return null;
                    }
                });
    }

    /**
    * get the Isim Impu based on subId
    */
    public String[] getIsimImpu(int subId) {
        return callPhoneMethodForSubIdWithPrivilegedCheck(subId, "getIsimImpu",
                (phone) -> {
                    IsimRecords isim = phone.getIsimRecords();
                    if (isim != null) {
                        return isim.getIsimImpu();
                    } else {
                        return null;
                    }
                });
    }

    /**
    * get the Isim Ist based on subId
    */
    public String getIsimIst(int subId) throws RemoteException {
        return callPhoneMethodForSubIdWithPrivilegedCheck(subId, "getIsimIst",
                (phone) -> {
                    IsimRecords isim = phone.getIsimRecords();
                    if (isim != null) {
                        return isim.getIsimIst();
                    } else {
                        return null;
                    }
                });
    }

    /**
    * get the Isim Pcscf based on subId
    */
    public String[] getIsimPcscf(int subId) throws RemoteException {
        return callPhoneMethodForSubIdWithPrivilegedCheck(subId, "getIsimPcscf",
                (phone) -> {
                    IsimRecords isim = phone.getIsimRecords();
                    if (isim != null) {
                        return isim.getIsimPcscf();
                    } else {
                        return null;
                    }
                });
    }

    public String getIccSimChallengeResponse(int subId, int appType, int authType, String data)
            throws RemoteException {
        CallPhoneMethodHelper<String> toExecute = (phone)-> {
            UiccCard uiccCard = phone.getUiccCard();
            if (uiccCard == null) {
                loge("getIccSimChallengeResponse() UiccCard is null");
                return null;
            }

            UiccCardApplication uiccApp = uiccCard.getApplicationByType(appType);
            if (uiccApp == null) {
                loge("getIccSimChallengeResponse() no app with specified type -- " + appType);
                return null;
            } else {
                loge("getIccSimChallengeResponse() found app " + uiccApp.getAid()
                        + " specified type -- " + appType);
            }

            if (authType != UiccCardApplication.AUTH_CONTEXT_EAP_SIM
                    && authType != UiccCardApplication.AUTH_CONTEXT_EAP_AKA) {
                loge("getIccSimChallengeResponse() unsupported authType: " + authType);
                return null;
            }
            return uiccApp.getIccRecords().getIccSimChallengeResponse(authType, data);
        };

        return callPhoneMethodWithPermissionCheck(
                subId, null, "getIccSimChallengeResponse", toExecute,
                (aContext, aSubId, aCallingPackage, aMessage)-> {
                    enforcePrivilegedPermissionOrCarrierPrivilege(aSubId, aMessage);
                    return true;
                });
    }

    public String getGroupIdLevel1ForSubscriber(int subId, String callingPackage) {
        return callPhoneMethodForSubIdWithReadCheck(subId, callingPackage,
                "getGroupIdLevel1", (phone)-> phone.getGroupIdLevel1());
    }

    /** Below are utility methods that abstracts the flow that many public methods use:
     *  1. Check permission: pass, throw exception, or fails (returns false).
     *  2. clearCallingIdentity.
     *  3. Call a specified phone method and get return value.
     *  4. restoreCallingIdentity and return.
     */
    private interface CallPhoneMethodHelper<T> {
        T callMethod(Phone phone);
    }

    private interface PermissionCheckHelper {
        // Implemented to do whatever permission check it wants.
        // If passes, it should return true.
        // If permission is not granted, throws SecurityException.
        // If permission is revoked by AppOps, return false.
        boolean checkPermission(Context context, int subId, String callingPackage, String message);
    }

    // Base utility method that others use.
    private <T> T callPhoneMethodWithPermissionCheck(int subId, String callingPackage,
            String message, CallPhoneMethodHelper<T> callMethodHelper,
            PermissionCheckHelper permissionCheckHelper) {
        if (!permissionCheckHelper.checkPermission(mContext, subId, callingPackage, message)) {
            return null;
        }

        final long identity = Binder.clearCallingIdentity();
        try {
            Phone phone = getPhone(subId);
            if (phone != null) {
                return callMethodHelper.callMethod(phone);
            } else {
                loge(message + " phone is null for Subscription:" + subId);
                return null;
            }
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private <T> T callPhoneMethodForSubIdWithReadCheck(int subId, String callingPackage,
            String message, CallPhoneMethodHelper<T> callMethodHelper) {
        return callPhoneMethodWithPermissionCheck(subId, callingPackage, message, callMethodHelper,
                (aContext, aSubId, aCallingPackage, aMessage)->
                        TelephonyPermissions.checkCallingOrSelfReadPhoneState(
                                aContext, aSubId, aCallingPackage, aMessage));
    }

    private <T> T callPhoneMethodForSubIdWithReadDeviceIdentifiersCheck(int subId,
            String callingPackage, String message, CallPhoneMethodHelper<T> callMethodHelper) {
        return callPhoneMethodWithPermissionCheck(subId, callingPackage, message, callMethodHelper,
                (aContext, aSubId, aCallingPackage, aMessage)->
                        TelephonyPermissions.checkCallingOrSelfReadDeviceIdentifiers(
                                aContext, aSubId, aCallingPackage, aMessage));
    }

    private <T> T callPhoneMethodForSubIdWithReadSubscriberIdentifiersCheck(int subId,
            String callingPackage, String message, CallPhoneMethodHelper<T> callMethodHelper) {
        return callPhoneMethodWithPermissionCheck(subId, callingPackage, message, callMethodHelper,
                (aContext, aSubId, aCallingPackage, aMessage)->
                        TelephonyPermissions.checkCallingOrSelfReadSubscriberIdentifiers(
                                aContext, aSubId, aCallingPackage, aMessage));
    }


    private <T> T callPhoneMethodForSubIdWithPrivilegedCheck(
            int subId, String message, CallPhoneMethodHelper<T> callMethodHelper) {
        return callPhoneMethodWithPermissionCheck(subId, null, message, callMethodHelper,
                (aContext, aSubId, aCallingPackage, aMessage)-> {
                    mContext.enforceCallingOrSelfPermission(READ_PRIVILEGED_PHONE_STATE, message);
                    return true;
                });
    }

    private <T> T callPhoneMethodForSubIdWithModifyCheck(int subId, String callingPackage,
            String message, CallPhoneMethodHelper<T> callMethodHelper) {
        return callPhoneMethodWithPermissionCheck(subId, null, message, callMethodHelper,
                (aContext, aSubId, aCallingPackage, aMessage)-> {
                    enforceModifyPermission();
                    return true;
                });
    }

    private <T> T callPhoneMethodForSubIdWithReadPhoneNumberCheck(int subId, String callingPackage,
            String message, CallPhoneMethodHelper<T> callMethodHelper) {
        return callPhoneMethodWithPermissionCheck(subId, callingPackage, message, callMethodHelper,
                (aContext, aSubId, aCallingPackage, aMessage)->
                        TelephonyPermissions.checkCallingOrSelfReadPhoneNumber(
                                aContext, aSubId, aCallingPackage, aMessage));
    }

    private <T> T callPhoneMethodForPhoneIdWithReadDeviceIdentifiersCheck(int phoneId,
            String callingPackage, String message, CallPhoneMethodHelper<T> callMethodHelper) {
        // Getting subId before doing permission check.
        if (!SubscriptionManager.isValidPhoneId(phoneId)) {
            phoneId = 0;
        }
        final Phone phone = mPhone[phoneId];
        if (phone == null) {
            return null;
        }
        if (!TelephonyPermissions.checkCallingOrSelfReadDeviceIdentifiers(mContext,
                phone.getSubId(), callingPackage, message)) {
            return null;
        }

        final long identity = Binder.clearCallingIdentity();
        try {
            return callMethodHelper.callMethod(phone);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private void log(String s) {
        Rlog.d(TAG, s);
    }

    @UnsupportedAppUsage
    private void loge(String s) {
        Rlog.e(TAG, s);
    }
}
