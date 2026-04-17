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

package android.media.tv.extension.scan;

import android.annotation.IntDef;
import android.annotation.StringDef;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Constant definition for scan extension package.
 *
 * @hide
 */
public final class ScanConstants {
    /*************************************** Broadcast Type****************************************/
    @IntDef({
            TYPE_DVB_T,
            TYPE_DVB_C,
            TYPE_DVB_S,
            TYPE_DTMB,
            TYPE_ATSC,
            TYPE_ATSC3,
            TYPE_ATSC_C,
            TYPE_PAL_SECAM,
            TYPE_NTSC,
            TYPE_ISDB_T,
            TYPE_ISDB_TB,
            TYPE_ISDB_T3,
            TYPE_ISDB_ARIB
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface BroadcastType{}
    // DVB Family
    public static final int TYPE_DVB_T = 1;
    public static final int TYPE_DVB_C = 2;
    public static final int TYPE_DVB_S = 3;
    // Chinese Standard
    public static final int TYPE_DTMB = 11;
    // ATSC Family
    public static final int TYPE_ATSC = 21;
    public static final int TYPE_ATSC_C = 22;  // CQAM
    public static final int TYPE_ATSC3 = 23;
    // Analog Legacy
    public static final int TYPE_PAL_SECAM = 31;
    public static final int TYPE_NTSC = 32;
    // ISDB Family
    public static final int TYPE_ISDB_T = 41;
    public static final int TYPE_ISDB_TB = 42;  // Brazil specific
    public static final int TYPE_ISDB_T3 = 43;
    public static final int TYPE_ISDB_ARIB = 51;  // Japan specific

    /*******************************************Scan Type******************************************/
    @StringDef({
            SCAN_TYPE_UNKNOWN,
            SCAN_TYPE_FULL,
            SCAN_TYPE_QUICK,
            SCAN_TYPE_NETWORK,
            SCAN_TYPE_MANUAL,
            SCAN_TYPE_UPDATE,
            SCAN_TYPE_RANGE
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ScanType{}
    public static final String SCAN_TYPE_UNKNOWN = "UNKNOWN";
    public static final String SCAN_TYPE_FULL = "FULL";
    public static final String SCAN_TYPE_QUICK = "QUICK";
    public static final String SCAN_TYPE_NETWORK = "NETWORK";
    public static final String SCAN_TYPE_MANUAL = "MANUAL";
    public static final String SCAN_TYPE_UPDATE = "UPDATE";
    public static final String SCAN_TYPE_RANGE = "RANGE";

    /**************************************ScanResult**********************************************/
    @IntDef({
            SCAN_RESULT_SUCCEEDED,
            SCAN_RESULT_CANCELED,
            SCAN_RESULT_FAILED,
            SCAN_RESULT_RESOURCE_BUSY,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ScanResult{}
    public static final int SCAN_RESULT_SUCCEEDED = 0;
    public static final int SCAN_RESULT_CANCELED = 1;
    public static final int SCAN_RESULT_FAILED = 2;
    public static final int SCAN_RESULT_RESOURCE_BUSY = 3;

    /****************************************StoreResult*******************************************/
    @IntDef({
            STORE_RESULT_SUCCEEDED,
            STORE_RESULT_FAILED,
            STORE_RESULT_RESOURCE_BUSY,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface StoreResult{}
    public static final int STORE_RESULT_SUCCEEDED = 0;
    public static final int STORE_RESULT_FAILED = 1;
    public static final int STORE_RESULT_RESOURCE_BUSY = 2;

    /************************************OpResult**************************************************/
    @IntDef({
            RESULT_SUCCEEDED,
            RESULT_FAILED
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface OpResult{}
    public static final int RESULT_SUCCEEDED = 0;
    public static final int RESULT_FAILED = 2;

    /*******************************Scan Session Setting*******************************************/
    @StringDef({
            KEY_CHANNEL_NUMBER,
            KEY_CHANNEL_LIST_ID
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ScanSessionBundleKey {}
    public static final String KEY_CHANNEL_NUMBER = "CHANNEL_NUMBER";
    public static final String KEY_CHANNEL_LIST_ID = "CHANNEL_LIST_ID";

    /************************************Scan Params***********************************************/
    @StringDef({
            KEY_REGION_CODE,
            KEY_BANDWIDTH,
            KEY_MODULATION,
            KEY_SYMBOL_RATE,
            KEY_QUICK_SCAN_PARAMETER,
            KEY_SINGLE_CABLE_BAND_FREQUENCY,
            KEY_DCSS_BAND_FREQUENCY,
            KEY_TRANSPONDER_INFO_LIST,
            KEY_LNB_INFO_LIST,
            KEY_START_CHANNEL_NUMBER,
            KEY_SCAN_DIRECTION,
            KEY_LCN_TYPE
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ScanBundleKey {}
    // Supplemental region code in addition to country code.
    public static final String KEY_REGION_CODE = "KEY_REGION_CODE";
    // ==========================================
    // PHYSICAL TUNING KEYS (Conditional) used for Manual Scan.
    // ==========================================
    /** Bandwidth (MHz) - Essential for DVB-T/T2. */
    public static final String KEY_BANDWIDTH = "KEY_BANDWIDTH";
    /** Modulation (QAM64, QPSK, etc.) - Essential for Cable/Sat. */
    public static final String KEY_MODULATION = "KEY_MODULATION";
    /** Symbol Rate - Essential for DVB-C and DVB-S. */
    public static final String KEY_SYMBOL_RATE = "KEY_SYMBOL_RATE";
    // ==========================================
    // SATELLITE SPECIFIC KEYS (DVB-S Only)
    // ==========================================
    public static final String KEY_SINGLE_CABLE_BAND_FREQUENCY = "SINGLE_CABLE_BAND_FREQUENCY";

    public static final String KEY_TRANSPONDER_INFO_LIST = "TRANSPONDER_INFO_LIST";

    public static final String KEY_LNB_INFO_LIST = "LNB_INFO_LIST";
    public static final String KEY_DCSS_BAND_FREQUENCY = "DCSS_BAND_FREQUENCY";
    // ==========================================
    // Others
    // ==========================================
    /** Start index for channel numbering (e.g., start at 1 or 100). */
    public static final String KEY_START_CHANNEL_NUMBER = "KEY_CHANNEL_NUM_START_INDEX";
    /** 0 = Forward, 1 = Backward. */
    public static final String KEY_SCAN_DIRECTION = "KEY_SCAN_DIRECTION";
    /** Specific parameters for Quick Scan logic. */
    public static final String KEY_QUICK_SCAN_PARAMETER = "QUICK_SCAN_PARAM_INFO_LIST";
    /** LCN Type (Nordig, Generic, etc.). */
    public static final String KEY_LCN_TYPE = "LCN_TYPE";

    /*****************************************Scan Action******************************************/
    @IntDef({ACTION_RESOLVE_LCN_CONFLICT, ACTION_NETWORK_CHANGE, ACTION_FAST_SCAN, ACTION_CAM_SCAN})
    @Retention(RetentionPolicy.SOURCE)
    public @interface ScanAction {}
    public static final int ACTION_RESOLVE_LCN_CONFLICT = 0;
    public static final int ACTION_NETWORK_CHANGE = 1;
    public static final int ACTION_FAST_SCAN = 2;
    public static final int ACTION_CAM_SCAN = 3;

    /************************************ClearServiceListBundle************************************/
    @StringDef({
            KEY_BROADCAST_TYPE,
            KEY_OPERATOR_ID,
            KEY_SLOT_NUMBER,
            KEY_COUNTRY_CODE,
            KEY_SCAN_TYPE
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ClearServiceListBundleKey {}
    // Value should be one of the following from @BroadcastType.
    public static final String KEY_BROADCAST_TYPE = "BROADCAST_TYPE";
    // Value should be one of the following from @TvExtensionContract.TunerOperators.OperatorType.
    public static final String KEY_OPERATOR_ID = "OPERATOR_ID";
    public static final String KEY_SLOT_NUMBER = "SLOT_NUMBER";
    public static final String KEY_COUNTRY_CODE = "COUNTRY_CODE";
    // Value should be one of the following from @ScanType.
    public static final String KEY_SCAN_TYPE = "SCAN_TYPE";

    /*********************************ServiceInfoBundle********************************************/
    @StringDef({
            KEY_SERVICE_INFO_ID,
            KEY_SERVICE_NAME,
            KEY_SERVICE_TYPE,
            KEY_SERVICE_ID,
            KEY_TRANSPORT_STREAM_ID,
            KEY_ORIGINAL_NETWORK_ID,
            KEY_ORBITAL_POSITION,
            KEY_MAJOR_CHANNEL_NUMBER,
            KEY_MINOR_CHANNEL_NUMBER,
            KEY_LOGICAL_CHANNEL_NUMBER,
            KEY_BROADCAST_TYPE,
            KEY_FREQUENCY
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ServiceInfoBundleKey {}
    public static final String KEY_SERVICE_INFO_ID = "SERVICE_INFO_ID";
    public static final String KEY_SERVICE_NAME = "SERVICE_NAME";
    public static final String KEY_SERVICE_TYPE = "SERVICE_TYPE";
    public static final String KEY_SERVICE_ID = "SERVICE_ID";
    public static final String KEY_TRANSPORT_STREAM_ID = "TRANSPORT_STREAM_ID";
    public static final String KEY_ORIGINAL_NETWORK_ID = "ORIGINAL_NETWORK_ID";
    public static final String KEY_ORBITAL_POSITION = "ORBITAL_POSITION";
    public static final String KEY_MAJOR_CHANNEL_NUMBER = "MAJOR_CHANNEL_NUMBER";
    public static final String KEY_MINOR_CHANNEL_NUMBER = "MINOR_CHANNEL_NUMBER";
    public static final String KEY_LOGICAL_CHANNEL_NUMBER = "LOGICAL_CHANNEL_NUMBER";
    public static final String KEY_FREQUENCY = "FREQUENCY";

    /**************************************DVBI ServiceInfo****************************************/
    @StringDef({
            KEY_REC_ID,
            KEY_SERVICE_LIST_NAME,
            KEY_PROVIDER_NAME,
            KEY_URI,
            KEY_SERVICE_LIST_LOGO_URI
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface DvbiServiceListBundleKey {}
    public static final String KEY_REC_ID = "RECORD_ID";
    public static final String KEY_SERVICE_LIST_NAME = "SERVICE_LIST_NAME";
    public static final String KEY_PROVIDER_NAME = "PROVIDER_NAME";
    public static final String KEY_URI = "URI";
    public static final String KEY_SERVICE_LIST_LOGO_URI = "SERVICE_LIST_LOGO_URI";

    /**************************************PackageData********************************************/
    @StringDef({
            KEY_PACKAGE_LISTS,
            KEY_PACKAGE_ORDER,
            KEY_PACKAGE_ID,
            KEY_PACKAGE_TEXT
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface PackageDataBundleKey {}
    public static final String KEY_PACKAGE_LISTS = "PACKAGE_LISTS";
    public static final String KEY_PACKAGE_ORDER = "PACKAGE_ORDER";
    public static final String KEY_PACKAGE_ID = "PACKAGE_ID";
    public static final String KEY_PACKAGE_TEXT = "PACKAGE_TEXT";

    /**************************************CountryRegionData**************************************/
    @StringDef({
            KEY_COUNTRY_REGION_LISTS,
            KEY_COUNTRY_REGION_ID,
            KEY_COUNTRY_REGION_TEXT,
            KEY_COUNTRY_REGION_COUNTRY_CODE
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface CountryRegionDataBundleKey {}
    public static final String KEY_COUNTRY_REGION_LISTS = "COUNTRY_REGION_LISTS";
    public static final String KEY_COUNTRY_REGION_ID = "COUNTRY_REGION_ID";
    public static final String KEY_COUNTRY_REGION_TEXT = "COUNTRY_REGION_TEXT";
    public static final String KEY_COUNTRY_REGION_COUNTRY_CODE = "COUNTRY_REGION_COUNTRY_CODE";

    /**************************************RegionData*********************************************/
    @StringDef({
            KEY_REGION_LISTS,
            KEY_REGION_ORDER,
            KEY_REGION_ID,
            KEY_REGION_TEXT,
            KEY_REGION_PARENT_ID,
            KEY_REGION_SELECTABLE,
            KEY_REGION_SUB_REGION_COUNT
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface RegionDataBundleKey {}
    public static final String KEY_REGION_LISTS = "REGION_LISTS";
    public static final String KEY_REGION_ORDER = "REGION_ORDER";
    public static final String KEY_REGION_ID = "REGION_ID";
    public static final String KEY_REGION_TEXT = "REGION_TEXT";
    public static final String KEY_REGION_PARENT_ID = "REGION_PARENT_ID";
    public static final String KEY_REGION_SELECTABLE = "REGION_SELECTABLE";
    public static final String KEY_REGION_SUB_REGION_COUNT = "SUB_REGION_COUNT";

    /**************************************Event***************************************************/
    @IntDef({
            ID_EXTENSION_UPDATE_RECORD,
            ID_EXTENSION_UPDATE_LCN_INFO,
            ID_EXTENSION_UPDATE_LCN_V2_CHANNEL_LIST_ID,
            ID_EXTENSION_TARGET_REGION,
            ID_EXTENSION_FAVORITE_NETWORK,
            ID_EXTENSION_SELECT_REGION_CHANNEL_LIST,
            ID_EXTENSION_SET_REGION_CHANNEL_LIST,
            ID_EXTENSION_SET_BOUQUET_INFO,
            ID_SERVICE_UPDATE,
            ID_SATELLITE_NAME_UPDATE,
            ID_NETWORK_NAME_UPDATE
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface EventId {}
    public static final int ID_EXTENSION_UPDATE_RECORD = 1;
    public static final int ID_EXTENSION_UPDATE_LCN_INFO = 2;
    public static final int ID_EXTENSION_UPDATE_LCN_V2_CHANNEL_LIST_ID = 3;
    public static final int ID_EXTENSION_TARGET_REGION = 4;
    public static final int ID_EXTENSION_FAVORITE_NETWORK = 5;
    public static final int ID_EXTENSION_SELECT_REGION_CHANNEL_LIST = 6;
    public static final int ID_EXTENSION_SET_REGION_CHANNEL_LIST = 7;
    public static final int ID_EXTENSION_SET_BOUQUET_INFO = 8;
    public static final int ID_SERVICE_UPDATE = 9;
    public static final int ID_SATELLITE_NAME_UPDATE = 10;
    public static final int ID_NETWORK_NAME_UPDATE = 11;

    @StringDef({
            KEY_EVENT_ID,  // values should be one of the following from EventId
            KEY_NETWORK_NAME,
            KEY_CH_NUM_ADDED,
            KEY_CH_NUM_DELETED,
            KEY_TV_NUM_ADDED,
            KEY_TV_NUM_DELETED,
            KEY_RADIO_NUM_ADDED,
            KEY_RADIO_NUM_DELETED,
            KEY_APPLICATION_NUM_ADDED,
            KEY_APPLICATION_NUM_DELETED,
            KEY_SATELLITE_POSITION_INFO
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface EventBundleKey {}
    public static final String KEY_EVENT_ID = "EVENT_ID";
    public static final String KEY_NETWORK_NAME = "NETWORK_NAME";
    public static final String KEY_CH_NUM_ADDED = "CH_NUM_ADDED";
    public static final String KEY_CH_NUM_DELETED = "CH_NUM_DELETED";
    public static final String KEY_TV_NUM_ADDED = "TV_NUM_ADDED";
    public static final String KEY_TV_NUM_DELETED = "TV_NUM_DELETED";
    public static final String KEY_RADIO_NUM_ADDED = "RADIO_NUM_ADDED";
    public static final String KEY_RADIO_NUM_DELETED = "RADIO_NUM_DELETED";
    public static final String KEY_APPLICATION_NUM_ADDED = "APPLICATION_NUM_ADDED";
    public static final String KEY_APPLICATION_NUM_DELETED = "APPLICATION_NUM_DELETED";
    public static final String KEY_SATELLITE_POSITION_INFO = "SATELLITE_POSITION_INFO";

    /*************************************Scan Progress *******************************************/
    @StringDef({
            KEY_FOUND_CHANNEL_NUM,
            KEY_NUM_OF_ADDED_DASH_SERVICES,
            KEY_NUM_OF_ADDED_BROADCAST_SERVICES
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ScanProgressBundleKey {}
    public static final String KEY_NUM_OF_ADDED_DASH_SERVICES = "NUMB_OF_ADDED_DASH_SERVICES";
    public static final String KEY_NUM_OF_ADDED_BROADCAST_SERVICES =
            "NUM_OF_ADDED_BROADCAST_SERVICES";
    public static final String KEY_FOUND_CHANNEL_NUM = "FOUND_CHANNEL_NUM";

    /************************************OperatorInfo**********************************************/
    @StringDef({
            KEY_OPERATOR_NETWORK_ID,
            KEY_OPERATOR_SERVICE_LIST_ID,
            KEY_OPERATOR_SERVICE_LIST_NAME,
            KEY_OPERATOR_NAME,
            KEY_FAST_SCAN_TABLE_PID,
            KEY_FAST_SCAN_TABLE_VERSION,
            KEY_COUNTRY_CODE,
            KEY_SPECIFIC_REGION_SETUP,
            KEY_DEFAULT_CHARACTER_SET,
            KEY_MENU_LANGUAGE,
            KEY_AUDIO_LANGUAGE_PRIMARY,
            KEY_AUDIO_LANGUAGE_SECONDARY,
            KEY_SUBTITLE_LANGUAGE,
            KEY_SUBTITLE_ENABLED_FLAG,
            KEY_ORBITAL_POSITION_COUNT,
            KEY_ORBITAL_INFORMATION,
            KEY_PARENTAL_CONTROL_RATING,
            KEY_OTT_BRAND_ID
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface OperatorInfoBundleKey {}
    public static final String KEY_OPERATOR_NETWORK_ID = "OPERATOR_NW_ID";
    public static final String KEY_OPERATOR_SERVICE_LIST_ID = "OPERATOR_SL_ID";
    public static final String KEY_OPERATOR_SERVICE_LIST_NAME = "OPERATOR_SL_NAME";
    public static final String KEY_OPERATOR_NAME = "OPERATOR_NAME";
    public static final String KEY_FAST_SCAN_TABLE_PID = "FST_SCAN_PID";
    public static final String KEY_FAST_SCAN_TABLE_VERSION = "FST_SCAN_VERSION";
    public static final String KEY_SPECIFIC_REGION_SETUP = "SPEC_REGN_SETUP";
    public static final String KEY_DEFAULT_CHARACTER_SET = "DEFAULT_CHAR_SET";
    public static final String KEY_MENU_LANGUAGE = "MENU_LANG_CODE";
    public static final String KEY_AUDIO_LANGUAGE_PRIMARY = "AUD1_LANG_CODE";
    public static final String KEY_AUDIO_LANGUAGE_SECONDARY = "AUD2_LANG_CODE";
    public static final String KEY_SUBTITLE_LANGUAGE = "SBTL_LANG_CODE";
    public static final String KEY_SUBTITLE_ENABLED_FLAG = "SBTL_ENABLED";
    public static final String KEY_ORBITAL_POSITION_COUNT = "ORBIT_NUM";
    public static final String KEY_ORBITAL_INFORMATION = "ORBIT_INFO";
    public static final String KEY_PARENTAL_CONTROL_RATING = "PARENTAL_CONTROL_RATING";
    public static final String KEY_OTT_BRAND_ID = "OTT_BRAND_ID";

    /****************************************Target Region DVB*************************************/
    @StringDef({
            KEY_TARGET_REGION_LEVEL,
            KEY_TARGET_REGION_PRIMARY,
            KEY_TARGET_REGION_SECONDARY,
            KEY_TARGET_REGION_TERTIARY,
            KEY_TARGET_REGION_NAME
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface TargetRegionBundleKey {}
    public static final String KEY_TARGET_REGION_LEVEL = "TARGET_REGION_LEVEL";
    public static final String KEY_TARGET_REGION_PRIMARY = "TARGET_REGION_PRIMARY";
    public static final String KEY_TARGET_REGION_SECONDARY = "TARGET_REGION_SECONDARY";
    public static final String KEY_TARGET_REGION_TERTIARY = "TARGET_REGION_TERTIARY";
    public static final String KEY_TARGET_REGION_NAME = "TARGET_REGION_NAME";

    @StringDef({
            KEY_TARGET_REGION_DETECTED
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface TargetRegionDetectBundleKey {}
    public static final String KEY_TARGET_REGION_DETECTED = "KEY_TARGET_REGION_DETECTED";

    /****************************************Lcn Conflict******************************************/
    @StringDef({
            KEY_LOGICAL_CHANNEL_NUMBER,
            KEY_SERVICE_NAME
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface LcnConflictGroupBundleKey {}

    @StringDef({
            KEY_LOGICAL_CHANNEL_NUMBER,
            KEY_LCN_SELECTED_INDEX
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface LcnConflictSettingBundleKey {}
    public static final String KEY_LCN_SELECTED_INDEX = "LCN_SELECTED_INDEX";

    @StringDef({
            KEY_LCN_CONFLICT_DETECTED
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface LcnConflictDetectBundleKey {}
    public static final String KEY_LCN_CONFLICT_DETECTED = "LCN_CONFLICT_DETECTED";

    /***************************************Lcn V2 Channel List************************************/
    @StringDef({
            KEY_LCNV2_CHANNEL_LIST_ID,
            KEY_LCNV2_CHANNEL_LIST_NAME
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface LcnV2ChannelListInfoBundleKey {}
    public static final String KEY_LCNV2_CHANNEL_LIST_ID = "LCNV2_CHANNEL_LIST_ID";
    public static final String KEY_LCNV2_CHANNEL_LIST_NAME = "LCNV2_CHANNEL_LIST_NAME";

    @StringDef({
            KEY_LCNV2_CHANNEL_LIST_DETECTED
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface LcnV2ChannelListDetectBundleKey {}
    public static final String KEY_LCNV2_CHANNEL_LIST_DETECTED = "LCNV2_CHANNEL_LIST_DETECTED";

    /****************************************Favorite Network**************************************/
    @StringDef({
            KEY_NETWORK_NAME,
            KEY_NETWORK_ID
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface FavoriteNetworkBundleKey {}
    public static final String KEY_NETWORK_ID = "NETWORK_ID";

    @StringDef({
            KEY_FAVORITE_NETWORK_DETECTED
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface FavoriteNetworkDetectBundleKey {}
    public static final String KEY_FAVORITE_NETWORK_DETECTED = "FAVORITE_NETWORK_DETECTED";
}
