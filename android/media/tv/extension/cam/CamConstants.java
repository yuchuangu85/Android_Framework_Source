/*
 * Copyright (C) 2025 The Android Open Source Project
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
package android.media.tv.extension.cam;

import android.annotation.IntDef;
import android.annotation.StringDef;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * @hide
 */
public final class CamConstants {
    /************************************** Host Control ******************************************/
    @IntDef({
            SESSION_INACTIVE,
            SESSION_ACTIVE
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface CamHostSessionStatus{}
    // HostControl Session inactive.
    public static final int SESSION_INACTIVE = 0;
    // HostControl Session active.
    public static final int SESSION_ACTIVE = 1;
    /*
     * CI+ 1.3 - Table 14.33
     * Note: Values from 0x02 to 0xFF are reserved for future use and are not
     * included in the current definitions.
     */
    @IntDef({
            ASK_RELEASE_OK,
            ASK_RELEASE_REFUSED
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface AskReleaseReplyStatus {}
    /**
     * Release OK – The host has successfully regained control of the tuner.
     */
    public static final int ASK_RELEASE_OK = 0; // 0x00
    /**
     * Release Refused – The CICAM has refused the release request and
     * retains control of the tuner.
     */
    public static final int ASK_RELEASE_REFUSED = 1; // 0x01

    /********************************* CAM App Information ****************************************/
    @StringDef({
            KEY_APP_TYPE,
            KEY_APP_MANUFACTURE,
            KEY_MANUFACTURE_CODE,
            KEY_MENU_STRING
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface CamAppInfoBundleKey {}
    // Application type, values as defined in @CamAppType.
    public static final String KEY_APP_TYPE = "APP_TYPE";
    public static final String KEY_APP_MANUFACTURE = "APP_MANUFACTURE"; // Manufacture name.
    public static final String KEY_MANUFACTURE_CODE = "MANUFACTURE_CODE"; // Manufacture code.
    public static final String KEY_MENU_STRING = "MENU_STRING"; // Menu title.

    @IntDef({
            APP_INFO_UNKNOWN,
            APP_INFO_CONDITIONAL_ACCESS,
            APP_INFO_ELECTRONIC_PROGRAM_GUIDE,
            APP_INFO_RESERVED
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface CamAppType {}
    public static final int APP_INFO_UNKNOWN = -3;
    public static final int APP_INFO_CONDITIONAL_ACCESS = 1;
    public static final int APP_INFO_ELECTRONIC_PROGRAM_GUIDE = 2;
    public static final int APP_INFO_RESERVED = 3;

    @IntDef({
            RESULT_SUCCESS,
            RESULT_INVALID_SLOT_ID,
            RESULT_CICAM_NOT_INSERTED,
            APP_INFO_UNKNOWN
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface CamAppInfoResult{}
    public static final int RESULT_SUCCESS = 0;
    // Failures with reasons
    public static final int RESULT_INVALID_SLOT_ID = -1;
    public static final int RESULT_CICAM_NOT_INSERTED = -2;

    /***************************************CAM Information****************************************/
    @StringDef({
            KEY_CAM_ID,
            KEY_CAM_PROFILE_NAME,
            KEY_CAM_SLOT_ID // optional
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface CamInfoBundleKey {}
    public static final String KEY_CAM_ID = "CAM_ID";
    /**
     * User facing name.
     * If CAM supports Operator Profile, use profile_name from operator_info.
     * If CAM does not support OP, use menu_string from application_info.
     */
    public static final String KEY_CAM_PROFILE_NAME = "CAM_PROFILE_NAME";
    public static final String KEY_CAM_SLOT_ID = "SLOT_ID";

    @StringDef({
            KEY_CAM_SLOT_TYPE,
            KEY_IS_CAM_INSERTED,
            KEY_CAM_SLOT_ID //optional
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface CamSlotInfoBundleKey {}
    // Slot type should be one of value in @CamSlotType.
    public static final String KEY_CAM_SLOT_TYPE = "KEY_CAM_SLOT_TYPE";
    public static final String KEY_IS_CAM_INSERTED = "IS_CAM_INSERTED"; // true or false

    @IntDef({
            SLOT_TYPE_PCMCIA,
            SLOT_TYPE_USB,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface CamSlotType{}
    public static final int SLOT_TYPE_PCMCIA = 1;
    public static final int SLOT_TYPE_USB = 2;

    /******************************************CAM Profile*****************************************/
    public static final String KEY_CAM_PROFILE_TYPE = "CAM_PROFILE_TYPE";

    /**
     * KEY_CAM_PROFILE_TYPE has a value equivalent to profile_type extracted from operator_status.
     * Reference: CI Plus v1.3.2 Table 14.39.
     */
    @IntDef({
            CAM_OP_PROFILE_TYPE_INVALID,
            CAM_OP_PROFILE_TYPE_NON_PROFILED,
            CAM_OP_PROFILE_TYPE_TYPE1,
            CAM_OP_PROFILE_TYPE_TYPE2,
            CAM_OP_PROFILE_TYPE_TYPE3,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface CamOpProfileType{}
    public static final int CAM_OP_PROFILE_TYPE_INVALID = -1;
    // The CICAM does not support any profiles and descrambles elementary streams as per DVB CI.
    public static final int CAM_OP_PROFILE_TYPE_NON_PROFILED = 0;
    // Profile is a private network that uses a CICAM NIT and has a private profile logical
    // channel list.
    public static final int CAM_OP_PROFILE_TYPE_TYPE1 = 1;
    // Reserved for future use for 2-3.
    public static final int CAM_OP_PROFILE_TYPE_TYPE2 = 2;
    public static final int CAM_OP_PROFILE_TYPE_TYPE3 = 3;

    public static final String KEY_NEED_SERVICE_UPDATE = "NEED_SERVICE_UPDATE";
    @IntDef({
            CAM_OP_SERVICE_UPDATE_MODE_INVALID,
            CAM_OP_SERVICE_UPDATE_MODE_INITIAL_AUTO_TUNE,
            CAM_OP_SERVICE_UPDATE_MODE_CAM_NIT,
            CAM_OP_SERVICE_UPDATE_MODE_UPDATE_AUTO_TUNE,
            CAM_OP_SERVICE_UPDATE_MODE_ADVANCED_WARNING,
            CAM_OP_SERVICE_UPDATE_MODE_SCHEDULED,
            CAM_OP_SERVICE_UPDATE_MODE_ACKNOWLEDGEMENT_ONLY
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface CamOpServiceUpdateMode{}
    public static final int CAM_OP_SERVICE_UPDATE_MODE_INVALID = -1; // error case
    // Host initialised_flag is 0 and profile_type is 0. The Host has no list.
    // Proceed to operator_info_req.
    public static final int CAM_OP_SERVICE_UPDATE_MODE_INITIAL_AUTO_TUNE = 0;
    // Host initialized_flag is 1, profile_type is 0 and nit_version changed.
    // Proceed to operator_nit_req.
    public static final int CAM_OP_SERVICE_UPDATE_MODE_CAM_NIT = 1;
    // Host initialized_flag is 1, profile_type is 0 and refresh_request_flag is Urgent.
    // Proceed to operator_info_req. Interrupt the user immediately to re-scan the channel list.
    public static final int CAM_OP_SERVICE_UPDATE_MODE_UPDATE_AUTO_TUNE = 2;
    // Host initialized_flag is 1, profile_type is 0 and refresh_request_flag is Advanced Warning.
    // Notify the user (Toast/Icon) that an update is available. Do not force a scan yet.
    public static final int CAM_OP_SERVICE_UPDATE_MODE_ADVANCED_WARNING = 3;
    // Host initialized_flag is 1, profile_type is 0 and refresh_request_flag is Scheduled.
    // Defer update. Schedule a silent scan for the next maintenance window (Standby).
    public static final int CAM_OP_SERVICE_UPDATE_MODE_SCHEDULED = 4;
    // Do nothing. The Profile is active and up-to-date. Handshake complete.
    public static final int CAM_OP_SERVICE_UPDATE_MODE_ACKNOWLEDGEMENT_ONLY = 5;

    public static final String KEY_CAM_DELIVERY_SYSTEM_HINT = "CAM_DELIVERY_SYSTEM_HINT";
    @StringDef({
            CAM_DELIVERY_SYSTEM_HINT_SUPPORT_DVBC_C2_ONLY,
            CAM_DELIVERY_SYSTEM_HINT_SUPPORT_DVBS_S2_ONLY,
            CAM_DELIVERY_SYSTEM_HINT_SUPPORT_DVBC_C2_AND_DVBS_S2,
            CAM_DELIVERY_SYSTEM_HINT_SUPPORT_DVBT_T2_ONLY,
            CAM_DELIVERY_SYSTEM_HINT_SUPPORT_DVBC_C2_AND_DVBT_T2,
            CAM_DELIVERY_SYSTEM_HINT_SUPPORT_DVBT_T2_AND_DVBS_S2,
            CAM_DELIVERY_SYSTEM_HINT_SUPPORT_ALL
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface CamOpDeliverySystemHint{}
    // Cable network and may be DVB-C and/or DVB-C2
    public static final String CAM_DELIVERY_SYSTEM_HINT_SUPPORT_DVBC_C2_ONLY = "DVBC_C2_ONLY";
    // Satellite network and may be DVB-S and/or DVB-S2
    public static final String CAM_DELIVERY_SYSTEM_HINT_SUPPORT_DVBS_S2_ONLY = "DVBS_S2_ONLY";
    // Cable network + Satellite network
    public static final String CAM_DELIVERY_SYSTEM_HINT_SUPPORT_DVBC_C2_AND_DVBS_S2 =
            "DVBC_C2_AND_DVBS_S2";
    // Terrestrial network and may be DVB-T and/or DVB-T2
    public static final String CAM_DELIVERY_SYSTEM_HINT_SUPPORT_DVBT_T2_ONLY = "DVBT_T2_ONLY";
    // Cable network + Terrestrial network
    public static final String CAM_DELIVERY_SYSTEM_HINT_SUPPORT_DVBC_C2_AND_DVBT_T2 =
            "DVBC_C2_AND_DVBT_T2";
    // Terrestrial network + Satellite network
    public static final String CAM_DELIVERY_SYSTEM_HINT_SUPPORT_DVBT_T2_AND_DVBS_S2 =
            "DVBT_T2_AND_DVBS_S2";
    // Cable network + Terrestrial network + Satellite network
    public static final String CAM_DELIVERY_SYSTEM_HINT_SUPPORT_ALL = "ALL";

    // Flag for the urgency of the request, values equal to refresh_request_flag from
    // operator_status_body (CI Plus Specification v1.3.2 Table 14.40).
    public static final String KEY_CAM_REFRESH_REQUEST_FLAG = "CAM_REFRESH_REQUEST_FLAG";
    @IntDef({
            REFRESH_REQUEST_FLAG_DEFAULT,
            REFRESH_REQUEST_FLAG_ADVANCE,
            REFRESH_REQUEST_FLAG_URGENT,
            REFRESH_REQUEST_FLAG_SCHEDULE
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface CamOpRefreshRequestFlag{}
    public static final int REFRESH_REQUEST_FLAG_DEFAULT = 0;
    public static final int REFRESH_REQUEST_FLAG_ADVANCE = 1;
    public static final int REFRESH_REQUEST_FLAG_URGENT = 2;
    public static final int REFRESH_REQUEST_FLAG_SCHEDULE = 3;

    @StringDef({
            KEY_CAM_PROFILE_NAME,
            KEY_NEED_SERVICE_UPDATE,
            KEY_CAM_DELIVERY_SYSTEM_HINT,
            KEY_CAM_PROFILE_TYPE,
            KEY_CAM_REFRESH_REQUEST_FLAG
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface CamServiceUpdateInfoBundleKey{}

    /************************************Content Control*******************************************/
    @IntDef({
            DRM_TYPE_UNKNOWN,
            DRM_TYPE_COPY_FREELY,
            DRM_TYPE_COPY_NO_MORE,
            DRM_TYPE_COPY_ONCE,
            DRM_TYPE_COPY_NEVER
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface DrmType{}
    // Error
    public static final int DRM_TYPE_UNKNOWN = -1;
    // 00 - copying is not restricted
    public static final int DRM_TYPE_COPY_FREELY = 0;
    // 01 - copying is prohibited
    public static final int DRM_TYPE_COPY_NO_MORE = 1;
    // 10 - one generation copy is permitted
    public static final int DRM_TYPE_COPY_ONCE = 2;
    // 11 - no further copying is permitted
    public static final int DRM_TYPE_COPY_NEVER = 3;

    @StringDef({
            KEY_CAM_DRM_TYPE,
            KEY_CAM_PRGM_NUM
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface CamDrmInfoBundleKey{}
    // Values should follow @DrmType
    public static final String KEY_CAM_DRM_TYPE = "DRM_TYPE";
    // program_number from MPEG
    public static final String KEY_CAM_PRGM_NUM = "PRGM_NUM";

    @IntDef({
            SUCCESS,
            FAIL
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface OpResult{}
    public static final int SUCCESS = 0;
    public static final int FAIL = -1;

    /****************************************CAM Pin **********************************************/
    @IntDef({
            CAM_NO_PIN_CAPABILITY,
            CAM_PIN_CAPABILITY_WITH_NO_PIN_CACHE,
            CAM_PIN_CAPABILITY_WITH_PIN_CACHE
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface CamPinCapabilityType{}
    public static final int CAM_NO_PIN_CAPABILITY = 0;
    public static final int CAM_PIN_CAPABILITY_WITH_NO_PIN_CACHE = 1;
    public static final int CAM_PIN_CAPABILITY_WITH_PIN_CACHE = 2;

    @StringDef({
            KEY_PIN_CAP_CAPABILITY,
            KEY_PIN_CAP_DATE_TIME
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface CamPinCapabilityBundleKey{}
    public static final String KEY_PIN_CAP_CAPABILITY = "PIN_CAPABILITY";
    public static final String KEY_PIN_CAP_DATE_TIME = "DATE_TIME";

    @IntDef({
            PIN_VALIDATION_REQ_SUCCESS,
            PIN_VALIDATION_REQ_FAIL_INVALID_SLOT,
            PIN_VALIDATION_REQ_FAIL_CAM_NOT_INSERTED,
            PIN_VALIDATION_REQ_FAIL_NO_CAPABILITY,
            PIN_VALIDATION_REQ_FAIL_INVALID_CAM_PIN
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface CamPinValidationResult{}
    public static final int PIN_VALIDATION_REQ_SUCCESS = 0;
    public static final int PIN_VALIDATION_REQ_FAIL_INVALID_SLOT = -1;
    public static final int PIN_VALIDATION_REQ_FAIL_CAM_NOT_INSERTED = -2;
    public static final int PIN_VALIDATION_REQ_FAIL_NO_CAPABILITY = -3;
    public static final int PIN_VALIDATION_REQ_FAIL_INVALID_CAM_PIN = -4;

    @IntDef({
            RESULT_SUCCESS,
            RESULT_INVALID_SLOT_ID,
            RESULT_CICAM_NOT_INSERTED,
            PIN_NOT_SUPPORTED
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface CamPinCapabilityResult{}
    public static final int PIN_NOT_SUPPORTED = -6;

    @StringDef({
            KEY_PIN_VALIDATION_PINCODE_STATUS,
            KEY_PIN_VALIDATION_RESULT
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface CamPinStatusBundleKey{}
    // Values should follow @CamPinCodeStatus
    public static final String KEY_PIN_VALIDATION_PINCODE_STATUS = "VALIDATION_PINCODE_STATUS";
    // Values should follow @CamPinValidationResult
    public static final String KEY_PIN_VALIDATION_RESULT = "VALIDATION_RESULT";

    @IntDef({
            PIN_CODE_STATUS_ERROR_BAD_PIN,
            PIN_CODE_STATUS_ERROR_CICAM_BUSY,
            PIN_CODE_STATUS_PIN_CORRECT,
            PIN_CODE_STATUS_ERROR_PIN_UNCONFIRMED
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface CamPinCodeStatus{}
    public static final int PIN_CODE_STATUS_ERROR_BAD_PIN = 0;
    public static final int PIN_CODE_STATUS_ERROR_CICAM_BUSY = 1;
    public static final int PIN_CODE_STATUS_PIN_CORRECT = 2;
    public static final int PIN_CODE_STATUS_ERROR_PIN_UNCONFIRMED = 3;

    /*****************************************Tune Quietly*****************************************/
    @IntDef({
            TUNE_MODE_NORMAL,
            TUNE_MODE_QUIET
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface TuneQuietlyFlag{}
    public static final int TUNE_MODE_NORMAL = 0; // Show video
    public static final int TUNE_MODE_QUIET  = 1; // Hide video (Quiet Tune)

    /******************************************** MMI *********************************************/
    @StringDef({
            KEY_MMI_ENQ_INPUT_TEXT,
            KEY_MMI_ENQ_IS_HIDE,
            KEY_MMI_ENQ_MAX_TEXT_LENGTH
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface EnquiryBundleKey{}
    // The text of the enquiry.
    public static final String KEY_MMI_ENQ_INPUT_TEXT = "MMI_ENQ_INPUT_TEXT";
    //  Whether to show or hide the user's answer on screen (e.g., hiding for passwords).
    //  true to hide, false to show.
    public static final String KEY_MMI_ENQ_IS_HIDE = "MMI_ENQ_IS_HIDE";
    // The maximum length of the user's answer.
    public static final String KEY_MMI_ENQ_MAX_TEXT_LENGTH = "MMI_ENQ_MAX_TEXT_LENGTH";

    @StringDef({
            KEY_MMI_MENULIST_TITLE,
            KEY_MMI_MENULIST_SUBTITLE,
            KEY_MMI_MENULIST_FOOTERNOTE,
            KEY_MMI_MENULIST_ITEMLIST
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface MenuListBundleKey{}
    // The title for the menu or list screen.
    public static final String KEY_MMI_MENULIST_TITLE = "MMI_MENULIST_TITLE";
    // The subtitle for the menu or list screen.
    public static final String KEY_MMI_MENULIST_SUBTITLE = "MMI_MENULIST_SUBTITLE";
    // The footnote text.
    public static final String KEY_MMI_MENULIST_FOOTERNOTE = "MMI_MENULIST_FOOTERNOTE";
    // The list of choices for the user.
    public static final String KEY_MMI_MENULIST_ITEMLIST = "MMI_MENULIST_ITEMLIST";

    @IntDef({
            ANSWER_CANCEL,
            ANSWER_ANSWER,
            ANSWER_UNATTENDED
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface MmiAnswerId {}
    public static final int ANSWER_CANCEL = 0x00; // 0
    public static final int ANSWER_ANSWER = 0x01; // 1
    public static final int ANSWER_UNATTENDED = 0xFF; // 255
}
