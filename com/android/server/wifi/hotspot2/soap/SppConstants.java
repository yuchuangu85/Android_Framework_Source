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
 * limitations under the License.
 */

package com.android.server.wifi.hotspot2.soap;

import android.util.SparseArray;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Constant definitions for SPP (Subscription Provisioning Protocol).
 */
public class SppConstants {
    // Supported SPP Version.
    public static final String SUPPORTED_SPP_VERSION = "1.0";

    // Supported Management Objects as required by SPP Version 1.0.
    public static final List<String> SUPPORTED_MO_LIST = Arrays.asList(
            "urn:wfa:mo:hotspot2dot0-perprovidersubscription:1.0",    // Hotspot 2.0 PPS MO tree
            "urn:wfa:mo-ext:hotspot2dot0-devdetail-ext:1.0",    // Hotspot 2.0 DevDetail extension
            "urn:oma:mo:oma-dm-devinfo:1.0",    // OMA-DM DevInfo
            "urn:oma:mo:oma-dm-devdetail:1.0");    // OMA-DM DevDetail

    // Method names.
    public static final String METHOD_POST_DEV_DATA = "sppPostDevData";
    public static final String METHOD_UPDATE_RESPONSE = "sppUpdateResponse";

    // SOAP properties.
    public static final String PROPERTY_SUPPORTED_SPP_VERSIONS = "supportedSPPVersions";
    public static final String PROPERTY_SUPPORTED_MO_LIST = "supportedMOList";
    public static final String PROPERTY_MO_CONTAINER = "moContainer";
    public static final String PROPERTY_SPP_ERROR = "sppError";

    // SOAP attributes.
    public static final String ATTRIBUTE_SPP_VERSION = "sppVersion";
    public static final String ATTRIBUTE_SPP_STATUS = "sppStatus";

    public static final String ATTRIBUTE_REQUEST_REASON = "requestReason";
    public static final String ATTRIBUTE_SESSION_ID = "sessionID";
    public static final String ATTRIBUTE_REDIRECT_URI = "redirectURI";
    public static final String ATTRIBUTE_MO_URN = "moURN";
    public static final String ATTRIBUTE_ERROR_CODE = "errorCode";

    public static final int INVALID_SPP_CONSTANT = -1;

    private static final SparseArray<String> sStatusStrings = new SparseArray<>();
    private static final Map<String, Integer> sStatusEnums = new HashMap<>();
    static {
        sStatusStrings.put(SppStatus.OK, "OK");
        sStatusStrings.put(SppStatus.PROV_COMPLETE,
                "Provisioning complete, request sppUpdateResponse");
        sStatusStrings.put(SppStatus.REMEDIATION_COMPLETE,
                "Remediation complete, request sppUpdateResponse");
        sStatusStrings.put(SppStatus.UPDATE_COMPLETE, "Update complete, request sppUpdateResponse");
        sStatusStrings.put(SppStatus.EXCHANGE_COMPLETE,
                "Exchange complete, release TLS connection");
        sStatusStrings.put(SppStatus.UNKOWN, "No update available at this time");
        sStatusStrings.put(SppStatus.ERROR, "Error occurred");

        for (int i = 0; i < sStatusStrings.size(); i++) {
            sStatusEnums.put(sStatusStrings.valueAt(i).toLowerCase(), sStatusStrings.keyAt(i));
        }
    }

    private static final SparseArray<String> sErrorStrings = new SparseArray<>();
    private static final Map<String, Integer> sErrorEnums = new HashMap<>();
    static {
        sErrorStrings.put(SppError.VERSION_NOT_SUPPORTED, "SPP version not supported");
        sErrorStrings.put(SppError.MOS_NOT_SUPPORTED, "One or more mandatory MOs not supported");
        sErrorStrings.put(SppError.CREDENTIALS_FAILURE,
                "Credentials cannot be provisioned at this time");
        sErrorStrings.put(SppError.REMEDIATION_FAILURE,
                "Remediation cannot be completed at this time");
        sErrorStrings.put(SppError.PROVISIONING_FAILED,
                "Provisioning cannot be completed at this time");
        sErrorStrings.put(SppError.EXISITING_CERTIFICATE, "Continue to use existing certificate");
        sErrorStrings.put(SppError.COOKIE_INVALID, "Cookie invalid");
        sErrorStrings.put(SppError.WEB_SESSION_ID,
                "No corresponding web-browser-connection Session ID");
        sErrorStrings.put(SppError.PERMISSION_DENITED, "Permission denied");
        sErrorStrings.put(SppError.COMMAND_FAILED, "Command failed");
        sErrorStrings.put(SppError.MO_ADD_OR_UPDATE_FAILED, "MO addition or update failed");
        sErrorStrings.put(SppError.DEVICE_FULL, "Device full");
        sErrorStrings.put(SppError.BAD_TREE_URI, "Bad management tree URI");
        sErrorStrings.put(SppError.TOO_LARGE, "Requested entity too large");
        sErrorStrings.put(SppError.COMMAND_NOT_ALLOWED, "Command not allowed");
        sErrorStrings.put(SppError.USER_ABORTED, "Command not executed due to user");
        sErrorStrings.put(SppError.NOT_FOUND, "Not found");
        sErrorStrings.put(SppError.OTHER, "Other");

        for (int i = 0; i < sErrorStrings.size(); i++) {
            sErrorEnums.put(sErrorStrings.valueAt(i).toLowerCase(), sErrorStrings.keyAt(i));
        }
    }

    /**
     * Convert the {@link SppStatus} to <code>String</code>
     *
     * @param status value of {@link SppStatus}
     * @return string of the status
     */
    public static String mapStatusIntToString(int status) {
        return sStatusStrings.get(status);
    }

    /**
     * Convert the status string to {@link SppStatus}
     *
     * @param status string of the status
     * @return <code>int</code> value of {@link SppStatus} if found; {@link #INVALID_SPP_CONSTANT}
     * otherwise.
     */
    public static int mapStatusStringToInt(String status) {
        Integer value = sStatusEnums.get(status.toLowerCase(Locale.US));
        return (value == null) ? INVALID_SPP_CONSTANT : value;
    }

    /**
     * Convert the {@link SppError} to <code>String</code>
     *
     * @param error value of {@link SppError}
     * @return string of error
     */
    public static String mapErrorIntToString(int error) {
        return sErrorStrings.get(error);
    }

    /**
     * Convert the error string to {@link SppError}
     *
     * @param error string of the error
     * @return <code>int</code> value of {@link SppError} if found; {@link #INVALID_SPP_CONSTANT}
     * otherwise.
     */
    public static int mapErrorStringToInt(String error) {
        Integer value = sErrorEnums.get(error.toLowerCase());
        return (value == null) ? INVALID_SPP_CONSTANT : value;
    }

    // Request reasons for sppPostDevData requests.
    // Refer to Table 13(sppPostDevData Elements and Attributes Descriptions)
    // in Hotspot2.0 R2 Technical specification.
    public class SppReason {
        public static final String SUBSCRIPTION_REGISTRATION =
                "Subscription registration";
        public static final String SUBSCRIPTION_PROVISIONING =
                "Subscription provisioning";
        public static final String SUBSCRIPTION_REMEDIATION =
                "Subscription remediation";
        public static final String USER_INPUT_COMPLETED = "User input completed";
        public static final String NO_ACCEPTABLE_CLIENT_CERTIFICATE =
                "No acceptable client certificate";
        public static final String CERTIFICATE_ENROLLMENT_COMPLETED =
                "Certificate enrollment completed";
        public static final String CERTIFICATE_ENROLLMENT_FAILED =
                "Certificate enrollment failed";
        public static final String SUBSCRIPTION_METADATA_UPDATE =
                "Subscription metadata update";
        public static final String POLICY_UPDATE = "Policy update";
        public static final String MO_UPLOAD = "MO upload";
        public static final String RETRIEVE_NEXT_COMMAND = "Retrieve next command";
        public static final String UNSPECIFIED = "Unspecified";
    }

    /**
     * enumeration values for the status defined by SPP (Subscription Provisioning Protocol).
     *
     * @see <a href=https://www.wi-fi.org/specifications/hotspot2dot0/v1.0/spp/spp-v1.0.xsd>SPP
     * protocol 1.0</a>
     */
    public class SppStatus {
        public static final int OK = 0;
        public static final int PROV_COMPLETE = 1;
        public static final int REMEDIATION_COMPLETE = 2;
        public static final int UPDATE_COMPLETE = 3;
        public static final int EXCHANGE_COMPLETE = 4;
        public static final int UNKOWN = 5;
        public static final int ERROR = 6;
    }

    /**
     * Enumeration values for the errors defined by SPP (Subscription Provisioning Protocol).
     *
     * @see <a href=https://www.wi-fi.org/specifications/hotspot2dot0/v1.0/spp/spp-v1.0.xsd>SPP
     * protocol 1.0</a>
     */
    public class SppError {
        public static final int VERSION_NOT_SUPPORTED = 0;
        public static final int MOS_NOT_SUPPORTED = 1;
        public static final int CREDENTIALS_FAILURE = 2;
        public static final int REMEDIATION_FAILURE = 3;
        public static final int PROVISIONING_FAILED = 4;
        public static final int EXISITING_CERTIFICATE = 5;
        public static final int COOKIE_INVALID = 6;
        public static final int WEB_SESSION_ID = 7;
        public static final int PERMISSION_DENITED = 8;
        public static final int COMMAND_FAILED = 9;
        public static final int MO_ADD_OR_UPDATE_FAILED = 10;
        public static final int DEVICE_FULL = 11;
        public static final int BAD_TREE_URI = 12;
        public static final int TOO_LARGE = 13;
        public static final int COMMAND_NOT_ALLOWED = 14;
        public static final int USER_ABORTED = 15;
        public static final int NOT_FOUND = 16;
        public static final int OTHER = 17;
    }
}