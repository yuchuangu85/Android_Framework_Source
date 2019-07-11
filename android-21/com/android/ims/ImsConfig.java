/*
 * Copyright (c) 2014 The Android Open Source Project
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

package com.android.ims;

import android.os.RemoteException;
import android.telephony.Rlog;

import com.android.ims.ImsConfigListener;
import com.android.ims.ImsReasonInfo;
import com.android.ims.internal.IImsConfig;
/**
 * Provides APIs to get/set the IMS service feature/capability/parameters.
 * The config items include:
 * 1) Items provisioned by the operator.
 * 2) Items configured by user. Mainly service feature class.
 *
 * @hide
 */
public class ImsConfig {
    private static final String TAG = "ImsConfig";
    private boolean DBG = true;
    private final IImsConfig miConfig;

    /**
    * Defines IMS service/capability feature constants.
    */
    public static class FeatureConstants {
        public static final int FEATURE_TYPE_UNKNOWN = -1;

        /**
         * FEATURE_TYPE_VOLTE supports features defined in 3GPP and
         * GSMA IR.92 over LTE.
         */
        public static final int FEATURE_TYPE_VOICE_OVER_LTE = 0;

        /**
         * FEATURE_TYPE_LVC supports features defined in 3GPP and
         * GSMA IR.94 over LTE.
         */
        public static final int FEATURE_TYPE_VIDEO_OVER_LTE = 1;

        /**
         * FEATURE_TYPE_VOICE_OVER_WIFI supports features defined in 3GPP and
         * GSMA IR.92 over WiFi.
         */
        public static final int FEATURE_TYPE_VOICE_OVER_WIFI = 2;

        /**
         * FEATURE_TYPE_VIDEO_OVER_WIFI supports features defined in 3GPP and
         * GSMA IR.94 over WiFi.
         */
        public static final int FEATURE_TYPE_VIDEO_OVER_WIFI = 3;
    }

    /**
    * Defines IMS service/capability parameters.
    */
    public static class ConfigConstants {

        // Define IMS config items
        public static final int CONFIG_START = 0;

        // Define operator provisioned config items
        public static final int PROVISIONED_CONFIG_START = CONFIG_START;

        /**
         * AMR CODEC Mode Value set, 0-7 in comma separated sequence.
         * Value is in String format.
         */
        public static final int VOCODER_AMRMODESET = CONFIG_START;

        /**
         * Wide Band AMR CODEC Mode Value set,0-7 in comma separated sequence.
         * Value is in String format.
         */
        public static final int VOCODER_AMRWBMODESET = 1;

        /**
         * SIP Session Timer value (seconds).
         * Value is in Integer format.
         */
        public static final int SIP_SESSION_TIMER = 2;

        /**
         * Minimum SIP Session Expiration Timer in (seconds).
         * Value is in Integer format.
         */
        public static final int MIN_SE = 3;

        /**
         * SIP_INVITE cancellation time out value. Integer format.
         * Value is in Integer format.
         */
        public static final int CANCELLATION_TIMER = 4;

        /**
         * Delay time when an iRAT transition from eHRPD/HRPD/1xRTT to LTE.
         * Value is in Integer format.
         */
        public static final int TDELAY = 5;

        /**
         * Silent redial status of Enabled (True), or Disabled (False).
         * Value is in Integer format.
         */
        public static final int SILENT_REDIAL_ENABLE = 6;

        /**
         * SIP T1 timer value in seconds. See RFC 3261 for define.
         * Value is in Integer format.
         */
        public static final int SIP_T1_TIMER = 7;

        /**
         * SIP T2 timer value in seconds.  See RFC 3261 for define.
         * Value is in Integer format.
         */
        public static final int SIP_T2_TIMER  = 8;

         /**
         * SIP TF timer value in seconds.  See RFC 3261 for define.
         * Value is in Integer format.
         */
        public static final int SIP_TF_TIMER = 9;

        /**
         * VoLTE status for VLT/s status of Enabled (1), or Disabled (0).
         * Value is in Integer format.
         */
        public static final int VLT_SETTING_ENABLED = 10;

        /**
         * VoLTE status for LVC/s status of Enabled (1), or Disabled (0).
         * Value is in Integer format.
         */
        public static final int LVC_SETTING_ENABLED = 11;

        // Expand the operator config items as needed here, need to change
        // PROVISIONED_CONFIG_END after that.
        public static final int PROVISIONED_CONFIG_END = LVC_SETTING_ENABLED;

        // Expand the operator config items as needed here.
    }

    /**
    * Defines IMS set operation status.
    */
    public static class OperationStatusConstants {
        public static final int UNKNOWN = -1;
        public static final int SUCCESS = 0;
        public static final int FAILED =  1;
        public static final int UNSUPPORTED_CAUSE_NONE = 2;
        public static final int UNSUPPORTED_CAUSE_RAT = 3;
        public static final int UNSUPPORTED_CAUSE_DISABLED = 4;
    }

   /**
    * Defines IMS feature value.
    */
    public static class FeatureValueConstants {
        public static final int OFF = 0;
        public static final int ON = 1;
    }

    public ImsConfig(IImsConfig iconfig) {
        if (DBG) Rlog.d(TAG, "ImsConfig creates");
        miConfig = iconfig;
    }

    /**
     * Gets the value for IMS service/capabilities parameters used by IMS stack.
     * This function should not be called from the mainthread as it could block the
     * mainthread to cause ANR.
     *
     * @param item, as defined in com.android.ims.ImsConfig#ConfigConstants.
     * @return the value in Integer format.
     *
     * @throws ImsException if calling the IMS service results in an error.
     */
    public int getMasterValue(int item) throws ImsException {
        int ret = 0;
        try {
            ret = miConfig.getMasterValue(item);
        }  catch (RemoteException e) {
            throw new ImsException("getValue()", e,
                    ImsReasonInfo.CODE_LOCAL_SERVICE_UNAVAILABLE);
        }
        if (DBG) Rlog.d(TAG, "getMasterValue(): item = " + item + ", ret =" + ret);

        return ret;
    }

    /**
     * Gets the value for IMS service/capabilities parameters used by IMS stack.
     * This function should not be called from the mainthread as it could block the
     * mainthread to cause ANR.
     *
     * @param item, as defined in com.android.ims.ImsConfig#ConfigConstants.
     * @return value in String format.
     *
     * @throws ImsException if calling the IMS service results in an error.
     */
    public String getMasterStringValue(int item) throws ImsException {
        String ret = "Unknown";
        try {
            ret = miConfig.getMasterStringValue(item);
        }  catch (RemoteException e) {
            throw new ImsException("getStringValue()", e,
                    ImsReasonInfo.CODE_LOCAL_SERVICE_UNAVAILABLE);
        }
        if (DBG) Rlog.d(TAG, "getMasterStringValue(): item = " + item + ", ret =" + ret);

        return ret;
    }

    /**
     * Sets the value for IMS service/capabilities parameters by
     * the operator device management entity.
     * This function should not be called from main thread as it could block
     * mainthread to cause ANR.
     *
     * @param item, as defined in com.android.ims.ImsConfig#ConfigConstants.
     * @param value in Integer format.
     * @return void
     *
     * @throws ImsException if calling the IMS service results in an error.
     */
    public void setProvisionedValue(int item, int value)
            throws ImsException {
        // TODO: ADD PERMISSION CHECK
        if (DBG) {
            Rlog.d(TAG, "setProvisionedValue(): item = " + item +
                    "value = " + value);
        }
        try {
            miConfig.setProvisionedValue(item, value);
        }  catch (RemoteException e) {
            throw new ImsException("setProvisionedValue()", e,
                    ImsReasonInfo.CODE_LOCAL_SERVICE_UNAVAILABLE);
        }
    }

    /**
     * Sets the value for IMS service/capabilities parameters by
     * the operator device management entity.
     *
     * @param item, as defined in com.android.ims.ImsConfig#ConfigConstants.
     * @param value in String format.
     * @return void.
     *
     * @throws ImsException if calling the IMS service results in an error.
     */
    public void setProvisionedStringValue(int item, String value)
            throws ImsException {
        // TODO: ADD PERMISSION CHECK
        if (DBG) {
            Rlog.d(TAG, "setProvisionedStringValue(): item = " + item +
                    ", value =" + value);
        }
        try {
            miConfig.setProvisionedStringValue(item, value);
        }  catch (RemoteException e) {
            throw new ImsException("setProvisionedStringValue()", e,
                    ImsReasonInfo.CODE_LOCAL_SERVICE_UNAVAILABLE);
        }
    }

    /**
     * Gets the value for IMS feature item for specified network type.
     *
     * @param feature, defined as in FeatureConstants.
     * @param network, defined as in android.telephony.TelephonyManager#NETWORK_TYPE_XXX.
     * @param listener, provided to be notified for the feature on/off status.
     * @return void
     *
     * @throws ImsException if calling the IMS service results in an error.
     */
    public void getFeatureValue(int feature, int network,
            ImsConfigListener listener) throws ImsException {
        if (DBG) {
            Rlog.d(TAG, "getFeatureValue: feature = " + feature + ", network =" + network +
                    ", listener =" + listener);
        }
        try {
            miConfig.getFeatureValue(feature, network, listener);
        } catch (RemoteException e) {
            throw new ImsException("getFeatureValue()", e,
                    ImsReasonInfo.CODE_LOCAL_SERVICE_UNAVAILABLE);
        }
    }

    /**
     * Sets the value for IMS feature item for specified network type.
     *
     * @param feature, as defined in FeatureConstants.
     * @param network, as defined in android.telephony.TelephonyManager#NETWORK_TYPE_XXX.
     * @param value, as defined in FeatureValueConstants.
     * @param listener, provided if caller needs to be notified for set result.
     * @return void
     *
     * @throws ImsException if calling the IMS service results in an error.
     */
    public void setFeatureValue(int feature, int network, int value,
            ImsConfigListener listener) throws ImsException {
        // TODO: ADD PERMISSION CHECK (should there be permission, same as provisioning?)
        if (DBG) {
            Rlog.d(TAG, "setFeatureValue: feature = " + feature + ", network =" + network +
                    ", value =" + value + ", listener =" + listener);
        }
        try {
            miConfig.setFeatureValue(feature, network, value, listener);
        } catch (RemoteException e) {
            throw new ImsException("setFeatureValue()", e,
                    ImsReasonInfo.CODE_LOCAL_SERVICE_UNAVAILABLE);
        }
    }
}
