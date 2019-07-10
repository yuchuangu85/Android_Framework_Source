/*
 * Copyright (C) 2007 The Android Open Source Project
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

import java.io.FileDescriptor;
import java.io.PrintWriter;

import android.app.AppOpsManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.telephony.PhoneNumberUtils;
import android.telephony.Rlog;

import com.android.internal.telephony.uicc.IsimRecords;
import com.android.internal.telephony.uicc.UiccCard;
import com.android.internal.telephony.uicc.UiccCardApplication;

public class PhoneSubInfo {
    static final String LOG_TAG = "PhoneSubInfo";
    private static final boolean DBG = true;
    private static final boolean VDBG = false; // STOPSHIP if true

    private Phone mPhone;
    private Context mContext;
    private AppOpsManager mAppOps;
    private static final String READ_PHONE_STATE =
        android.Manifest.permission.READ_PHONE_STATE;
    // TODO: change getCompleteVoiceMailNumber() to require READ_PRIVILEGED_PHONE_STATE
    private static final String CALL_PRIVILEGED =
        android.Manifest.permission.CALL_PRIVILEGED;
    private static final String READ_PRIVILEGED_PHONE_STATE =
        android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE;

    public PhoneSubInfo(Phone phone) {
        mPhone = phone;
        mContext = phone.getContext();
        mAppOps = mContext.getSystemService(AppOpsManager.class);
    }

    public void dispose() {
    }

    @Override
    protected void finalize() {
        try {
            super.finalize();
        } catch (Throwable throwable) {
            loge("Error while finalizing:", throwable);
        }
        if (DBG) log("PhoneSubInfo finalized");
    }

    /**
     * Retrieves the unique device ID, e.g., IMEI for GSM phones and MEID for CDMA phones.
     */
    public String getDeviceId(String callingPackage) {
        if (!checkReadPhoneState(callingPackage, "getDeviceId")) {
            return null;
        }
        return mPhone.getDeviceId();
    }

    /**
     * Retrieves the IMEI.
     */
    public String getImei(String callingPackage) {
        if (!checkReadPhoneState(callingPackage, "getImei")) {
            return null;
        }
        return mPhone.getImei();
    }

    /**
     * Retrieves the NAI.
     */
    public String getNai(String callingPackage) {
        if (!checkReadPhoneState(callingPackage, "getNai")) {
            return null;
        }
        return mPhone.getNai();
    }

    /**
     * Retrieves the software version number for the device, e.g., IMEI/SV
     * for GSM phones.
     */
    public String getDeviceSvn(String callingPackage) {
        if (!checkReadPhoneState(callingPackage, "getDeviceSvn")) {
            return null;
        }

        return mPhone.getDeviceSvn();
    }

    /**
     * Retrieves the unique subscriber ID, e.g., IMSI for GSM phones.
     */
    public String getSubscriberId(String callingPackage) {
        if (!checkReadPhoneState(callingPackage, "getSubscriberId")) {
            return null;
        }
        return mPhone.getSubscriberId();
    }

    /**
     * Retrieves the Group Identifier Level1 for GSM phones.
     */
    public String getGroupIdLevel1(String callingPackage) {
        if (!checkReadPhoneState(callingPackage, "getGroupIdLevel1")) {
            return null;
        }
        return mPhone.getGroupIdLevel1();
    }

    /**
     * Retrieves the serial number of the ICC, if applicable.
     */
    public String getIccSerialNumber(String callingPackage) {
        if (!checkReadPhoneState(callingPackage, "getIccSerialNumber")) {
            return null;
        }
        return mPhone.getIccSerialNumber();
    }

    /**
     * Retrieves the phone number string for line 1.
     */
    public String getLine1Number(String callingPackage) {
        // This is open to apps with WRITE_SMS.
        if (!checkReadPhoneNumber(callingPackage, "getLine1Number")) {
            return null;
        }
        return mPhone.getLine1Number();
    }

    /**
     * Retrieves the alpha identifier for line 1.
     */
    public String getLine1AlphaTag(String callingPackage) {
        if (!checkReadPhoneState(callingPackage, "getLine1AlphaTag")) {
            return null;
        }
        return mPhone.getLine1AlphaTag();
    }

    /**
     * Retrieves the MSISDN string.
     */
    public String getMsisdn(String callingPackage) {
        if (!checkReadPhoneState(callingPackage, "getMsisdn")) {
            return null;
        }
        return mPhone.getMsisdn();
    }

    /**
     * Retrieves the voice mail number.
     */
    public String getVoiceMailNumber(String callingPackage) {
        if (!checkReadPhoneState(callingPackage, "getVoiceMailNumber")) {
            return null;
        }
        String number = PhoneNumberUtils.extractNetworkPortion(mPhone.getVoiceMailNumber());
        if (VDBG) log("VM: PhoneSubInfo.getVoiceMailNUmber: " + number);
        return number;
    }

    /**
     * Retrieves the complete voice mail number.
     *
     * @hide
     */
    public String getCompleteVoiceMailNumber() {
        mContext.enforceCallingOrSelfPermission(CALL_PRIVILEGED,
                "Requires CALL_PRIVILEGED");
        String number = mPhone.getVoiceMailNumber();
        if (VDBG) log("VM: PhoneSubInfo.getCompleteVoiceMailNUmber: " + number);
        return number;
    }

    /**
     * Retrieves the alpha identifier associated with the voice mail number.
     */
    public String getVoiceMailAlphaTag(String callingPackage) {
        if (!checkReadPhoneState(callingPackage, "getVoiceMailAlphaTag")) {
            return null;
        }
        return mPhone.getVoiceMailAlphaTag();
    }

    /**
     * Returns the IMS private user identity (IMPI) that was loaded from the ISIM.
     * @return the IMPI, or null if not present or not loaded
     */
    public String getIsimImpi() {
        mContext.enforceCallingOrSelfPermission(READ_PRIVILEGED_PHONE_STATE,
                "Requires READ_PRIVILEGED_PHONE_STATE");
        IsimRecords isim = mPhone.getIsimRecords();
        if (isim != null) {
            return isim.getIsimImpi();
        } else {
            return null;
        }
    }

    /**
     * Returns the IMS home network domain name that was loaded from the ISIM.
     * @return the IMS domain name, or null if not present or not loaded
     */
    public String getIsimDomain() {
        mContext.enforceCallingOrSelfPermission(READ_PRIVILEGED_PHONE_STATE,
                "Requires READ_PRIVILEGED_PHONE_STATE");
        IsimRecords isim = mPhone.getIsimRecords();
        if (isim != null) {
            return isim.getIsimDomain();
        } else {
            return null;
        }
    }

    /**
     * Returns the IMS public user identities (IMPU) that were loaded from the ISIM.
     * @return an array of IMPU strings, with one IMPU per string, or null if
     *      not present or not loaded
     */
    public String[] getIsimImpu() {
        mContext.enforceCallingOrSelfPermission(READ_PRIVILEGED_PHONE_STATE,
                "Requires READ_PRIVILEGED_PHONE_STATE");
        IsimRecords isim = mPhone.getIsimRecords();
        if (isim != null) {
            return isim.getIsimImpu();
        } else {
            return null;
        }
    }

    /**
     * Returns the IMS Service Table (IST) that was loaded from the ISIM.
     * @return IMS Service Table or null if not present or not loaded
     */
    public String getIsimIst(){
        mContext.enforceCallingOrSelfPermission(READ_PRIVILEGED_PHONE_STATE,
                "Requires READ_PRIVILEGED_PHONE_STATE");
        IsimRecords isim = mPhone.getIsimRecords();
        if (isim != null) {
            return isim.getIsimIst();
        } else {
            return null;
        }
     }

    /**
     * Returns the IMS Proxy Call Session Control Function(PCSCF) that were loaded from the ISIM.
     * @return an array of  PCSCF strings with one PCSCF per string, or null if
     *      not present or not loaded
     */
    public String[] getIsimPcscf() {
        mContext.enforceCallingOrSelfPermission(READ_PRIVILEGED_PHONE_STATE,
                "Requires READ_PRIVILEGED_PHONE_STATE");
        IsimRecords isim = mPhone.getIsimRecords();
        if (isim != null) {
            return isim.getIsimPcscf();
        } else {
            return null;
        }
    }

    /**
     * Returns the response of ISIM Authetification through RIL.
     * Returns null if the Authentification hasn't been successed or isn't present iphonesubinfo.
     * @return the response of ISIM Authetification, or null if not available
     */
    public String getIsimChallengeResponse(String nonce){
        mContext.enforceCallingOrSelfPermission(READ_PRIVILEGED_PHONE_STATE,
                "Requires READ_PRIVILEGED_PHONE_STATE");
        IsimRecords isim = mPhone.getIsimRecords();
        if (isim != null) {
            return isim.getIsimChallengeResponse(nonce);
        } else {
            return null;
        }
    }

    /**
     * Returns the response of the SIM application on the UICC to authentication
     * challenge/response algorithm. The data string and challenge response are
     * Base64 encoded Strings.
     * Can support EAP-SIM, EAP-AKA with results encoded per 3GPP TS 31.102.
     *
     * @param appType ICC application family (@see com.android.internal.telephony.PhoneConstants#APPTYPE_xxx)
     * @param data authentication challenge data
     * @return challenge response
     */
    public String getIccSimChallengeResponse(int subId, int appType, String data) {
        // FIXME: use subId!!
        mContext.enforceCallingOrSelfPermission(READ_PRIVILEGED_PHONE_STATE,
                "Requires READ_PRIVILEGED_PHONE_STATE");

        UiccCard uiccCard = mPhone.getUiccCard();
        if (uiccCard == null) {
            Rlog.e(LOG_TAG, "getIccSimChallengeResponse() UiccCard is null");
            return null;
        }

        UiccCardApplication uiccApp = uiccCard.getApplicationByType(appType);
        if (uiccApp == null) {
            Rlog.e(LOG_TAG, "getIccSimChallengeResponse() no app with specified type -- " +
                    appType);
            return null;
        } else {
            Rlog.e(LOG_TAG, "getIccSimChallengeResponse() found app " + uiccApp.getAid()
                    + "specified type -- " + appType);
        }

        int authContext = uiccApp.getAuthContext();

        if (data.length() < 32) {
            /* must use EAP_SIM context */
            Rlog.e(LOG_TAG, "data is too small to use EAP_AKA, using EAP_SIM instead");
            authContext = UiccCardApplication.AUTH_CONTEXT_EAP_SIM;
        }

        if(authContext == UiccCardApplication.AUTH_CONTEXT_UNDEFINED) {
            Rlog.e(LOG_TAG, "getIccSimChallengeResponse() authContext undefined for app type " +
                    appType);
            return null;
        }

        return uiccApp.getIccRecords().getIccSimChallengeResponse(authContext, data);
    }

    private void log(String s) {
        Rlog.d(LOG_TAG, s);
    }

    private void loge(String s, Throwable e) {
        Rlog.e(LOG_TAG, s, e);
    }

    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.DUMP)
                != PackageManager.PERMISSION_GRANTED) {
            pw.println("Permission Denial: can't dump PhoneSubInfo from from pid="
                    + Binder.getCallingPid()
                    + ", uid=" + Binder.getCallingUid());
            return;
        }

        pw.println("Phone Subscriber Info:");
        pw.println("  Phone Type = " + mPhone.getPhoneName());
        pw.println("  Device ID = " + mPhone.getDeviceId());
    }

    private boolean checkReadPhoneState(String callingPackage, String message) {
        try {
            mContext.enforceCallingOrSelfPermission(
                    android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE, message);

            // SKIP checking run-time OP_READ_PHONE_STATE since self or using PRIVILEGED
            return true;
        } catch (SecurityException e) {
            mContext.enforceCallingOrSelfPermission(android.Manifest.permission.READ_PHONE_STATE,
                    message);
        }

        return mAppOps.noteOp(AppOpsManager.OP_READ_PHONE_STATE, Binder.getCallingUid(),
            callingPackage) == AppOpsManager.MODE_ALLOWED;
    }


    /**
     * Besides READ_PHONE_STATE, WRITE_SMS also allows apps to get phone numbers.
     */
    private boolean checkReadPhoneNumber(String callingPackage, String message) {
        // Default SMS app can always read it.
        if (mAppOps.noteOp(AppOpsManager.OP_WRITE_SMS,
                Binder.getCallingUid(), callingPackage) == AppOpsManager.MODE_ALLOWED) {
            return true;
        }
        try {
            return checkReadPhoneState(callingPackage, message);
        } catch (SecurityException e) {
            // Can be read with READ_SMS too.
            mContext.enforceCallingOrSelfPermission(android.Manifest.permission.READ_SMS, message);
            return mAppOps.noteOp(AppOpsManager.OP_READ_SMS,
                    Binder.getCallingUid(), callingPackage) == AppOpsManager.MODE_ALLOWED;
        }
    }
}
