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
package android.media.tv.extension.servicedb;

import android.annotation.IntDef;
import android.annotation.StringDef;
import android.media.tv.TvContract.Channels;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * @hide
 */
public final class ServicedbConstants {
    /***************************************Result*************************************************/
    @IntDef({RESULT_SUCCESS, RESULT_ERROR, RESULT_ERROR_LOCKED, RESULT_ERROR_SERVICE_NOT_EXIST})
    @Retention(RetentionPolicy.SOURCE)
    public @interface ResultCode {}
    public static final int RESULT_SUCCESS = 0;
    public static final int RESULT_ERROR = -1;
    // Error Sub Code
    public static final int RESULT_ERROR_LOCKED = -101;
    public static final int RESULT_ERROR_SERVICE_NOT_EXIST = -102;

    /******************************************Record Type*****************************************/
    @StringDef({RECORD_TYPE_SERVICE_INFO, RECORD_TYPE_TRANSPORT_STREAM_INFO,
            RECORD_TYPE_NETWORK_INFO, RECORD_TYPE_SATELLITE_INFO, RECORD_TYPE_FILTER})
    @Retention(RetentionPolicy.SOURCE)
    public @interface RecordType {}
    public static final String RECORD_TYPE_SERVICE_INFO = "SERVICE_INFO";
    public static final String RECORD_TYPE_TRANSPORT_STREAM_INFO = "TRANSPORT_STREAM_INFO";
    public static final String RECORD_TYPE_NETWORK_INFO = "NETWORK_INFO";
    public static final String RECORD_TYPE_SATELLITE_INFO = "STELLITE_INFO";
    public static final String RECORD_TYPE_FILTER = "FILTER";

    /******************************************Operation Type**************************************/
    @IntDef({OPT_UPDATE_CHANNEL_NAME, OPT_UPDATE_CHANNEL_NUM, OPT_UPDATE_CHANNEL_LOCK,
            OPT_UPDATE_CHANNEL_DELETE, OPT_ADD_CHANNEL_FAVORITE, OPT_REMOVE_CHANNEL_FAVORITE})
    @Retention(RetentionPolicy.SOURCE)
    public @interface OptType {}
    public static final int OPT_UPDATE_CHANNEL_NAME = 0;
    public static final int OPT_UPDATE_CHANNEL_NUM = 1;
    public static final int OPT_UPDATE_CHANNEL_LOCK = 2;
    public static final int OPT_UPDATE_CHANNEL_DELETE = 3;
    public static final int OPT_ADD_CHANNEL_FAVORITE = 4;
    public static final int OPT_REMOVE_CHANNEL_FAVORITE = 5;

    /*************************************Service List Type****************************************/
    @StringDef({SERVICE_LIST_TYPE_DVB_CABLE, SERVICE_LIST_TYPE_DVB_GENERAL_SATELLITE,
            SERVICE_LIST_TYPE_DVB_PREFERRED_SATELLITE, SERVICE_LIST_TYPE_DVB_CAM,
            SERVICE_LIST_TYPE_ATSC_TERR, SERVICE_LIST_TYPE_ATSC3_TERR, SERVICE_LIST_TYPE_ATSC_CABLE,
            SERVICE_LIST_TYPE_ISDB_INTERNATIONAL_TERR, SERVICE_LIST_TYPE_ISDB_JP_TERR,
            SERVICE_LIST_TYPE_ISDB_JP_BS, SERVICE_LIST_TYPE_ISDB_JP_CS,
            SERVICE_LIST_TYPE_ISDB_JP_ADVBS, SERVICE_LIST_TYPE_ISDB_JP_ADVCS})
    @Retention(RetentionPolicy.SOURCE)
    public @interface ServiceListType {}
    public static final String SERVICE_LIST_TYPE_DVB_CABLE = "DVB_CABLE";
    public static final String SERVICE_LIST_TYPE_DVB_GENERAL_SATELLITE = "DVB_GENERAL_SATELLITE";
    public static final String SERVICE_LIST_TYPE_DVB_PREFERRED_SATELLITE =
            "DVB_PREFERRED_SATELLITE";
    public static final String SERVICE_LIST_TYPE_DVB_CAM = "DVB_CAM";
    public static final String SERVICE_LIST_TYPE_ATSC_TERR = "ATSC_TERR";
    public static final String SERVICE_LIST_TYPE_ATSC3_TERR = "ATSC3_TERR";
    public static final String SERVICE_LIST_TYPE_ATSC_CABLE = "ATSC_CABLE";
    public static final String SERVICE_LIST_TYPE_ISDB_INTERNATIONAL_TERR =
            "ISDB_INTERNATIONAL_TERR";
    public static final String SERVICE_LIST_TYPE_ISDB_JP_TERR = "ISDB_JP_TERR";
    public static final String SERVICE_LIST_TYPE_ISDB_JP_BS = "ISDB_JP_BS";
    public static final String SERVICE_LIST_TYPE_ISDB_JP_CS = "ISDB_JP_CS";
    public static final String SERVICE_LIST_TYPE_ISDB_JP_ADVBS = "ISDB_JP_ADVBS";
    public static final String SERVICE_LIST_TYPE_ISDB_JP_ADVCS = "ISDB_JP_ADVCS";

    /***********************************Service List Prefix****************************************/
    @StringDef({SERVICE_LIST_TERRESTRIAL_PREFIX, SERVICE_LIST_CABLE_PREFIX,
            SERVICE_LIST_PREFERRED_SATELLITE_PREFIX, SERVICE_LIST_GENERAL_SATELLITE_PREFIX,
            SERVICE_LIST_SATELLITE_PREFIX})
    @Retention(RetentionPolicy.SOURCE)
    public @interface ServiceListPrefix {}
    public static final String SERVICE_LIST_TERRESTRIAL_PREFIX = "TERRESTRIAL";
    public static final String SERVICE_LIST_CABLE_PREFIX = "CABLE";
    public static final String SERVICE_LIST_PREFERRED_SATELLITE_PREFIX = "PREFERRED_SATELLITE";
    public static final String SERVICE_LIST_GENERAL_SATELLITE_PREFIX = "GENERAL_SATELLITE";
    public static final String SERVICE_LIST_SATELLITE_PREFIX = "SATELLITE";

    /*************************************** Service Info *****************************************/
    @StringDef({
            KEY_SERVICE_INFO_ID, // Row id
            KEY_SERVICE_ID,
            KEY_SERVICE_TYPE,
            KEY_SERVICE_DISPLAY_NAME,
            KEY_SERVICE_DISPLAY_NUMBER,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ServiceInfoKeys {}
    public static final String KEY_SERVICE_INFO_ID = "service_info_id";
    public static final String KEY_SERVICE_ID = Channels.COLUMN_SERVICE_ID;
    public static final String KEY_SERVICE_TYPE = Channels.COLUMN_SERVICE_TYPE;
    public static final String KEY_SERVICE_DISPLAY_NAME = Channels.COLUMN_DISPLAY_NAME;
    public static final String KEY_SERVICE_DISPLAY_NUMBER = Channels.COLUMN_DISPLAY_NUMBER;

    /************************************* Service List Info **************************************/
    @StringDef({
            KEY_COUNTRY_CODE, // iso_3166
            KEY_OPERATOR_ID,
            KEY_SERVICE_LIST_PREFIX,
            KEY_SERVICE_LIST_TYPE,
            KEY_BROADCAST_TYPE,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ServiceListInfoKeys {}
    public static final String KEY_COUNTRY_CODE = "country_code";
    // Value should be one of the following from @ServiceListType.
    public static final String KEY_SERVICE_LIST_TYPE = "service_list_type";
    public static final String KEY_OPERATOR_ID = "operator_id";
    // Value should be one of the following from @ServiceListPrefix.
    public static final String KEY_SERVICE_LIST_PREFIX = "service_list_prefix";
    // Value should be one of the following from
    // @{android.media.tv.extension.scan.ScanConstants.BroadcastType}.
    public static final String KEY_BROADCAST_TYPE = "broadcast_type";

    /*************************************** Transport Stream *************************************/
    @StringDef({
            KEY_BANDWIDTH,
            KEY_FREQUENCY,
            KEY_MODULATION,
            KEY_NETWORK_ID,
            KEY_NETWORK_NAME,
            KEY_ORIGINAL_NETWORK_ID,
            KEY_TRANSPORT_STREAM_ID,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface TransportStreamInfoKey {}
    public static final String KEY_BANDWIDTH = "bandwidth";
    public static final String KEY_FREQUENCY = "frequency";
    public static final String KEY_MODULATION = "modulation";
    public static final String KEY_NETWORK_ID = "network_id";
    public static final String KEY_NETWORK_NAME = "network_name";
    public static final String KEY_ORIGINAL_NETWORK_ID = Channels.COLUMN_ORIGINAL_NETWORK_ID;
    public static final String KEY_TRANSPORT_STREAM_ID = Channels.COLUMN_TRANSPORT_STREAM_ID;

    /****************************************** Network ******************************************/
    @StringDef({KEY_FAVORITE_NETWORK_CANDIDATE, KEY_IS_FAVORITE_NETWORK, KEY_NETWORK_REC_ID,
            KEY_NETWORK_VERSION, KEY_NETWORK_ID, KEY_NETWORK_NAME})
    @Retention(RetentionPolicy.SOURCE)
    public @interface NetworkInfoKey {}
    public static final String KEY_FAVORITE_NETWORK_CANDIDATE = "favorite_network_candidate";
    public static final String KEY_IS_FAVORITE_NETWORK = "is_favorite_network";
    public static final String KEY_NETWORK_REC_ID = "network_rec_id";
    public static final String KEY_NETWORK_VERSION = "network_version";

    /****************************************** Satellite *****************************************/
    @StringDef({
            KEY_SATELLITE_REC_ID,
            KEY_SATELLITE_NAME,
            KEY_SATELLITE_ORBIT_POSITION,
            KEY_SATELLITE_LNB_TYPE,
            KEY_SATELLITE_LNB_LOW_FREQ,
            KEY_SATELLITE_LNB_HIGH_FREQ,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface SatelliteInfoKey {}
    public static final String KEY_SATELLITE_REC_ID = "satellite_rec_id";
    public static final String KEY_SATELLITE_NAME = "satellite_name";
    public static final String KEY_SATELLITE_ORBIT_POSITION = "satellite_orbit_position";
    public static final String KEY_SATELLITE_LNB_TYPE = "satellite_lnb_type";
    public static final String KEY_SATELLITE_LNB_LOW_FREQ = "satellite_lnb_low_freq";
    public static final String KEY_SATELLITE_LNB_HIGH_FREQ = "satellite_lnb_high_freq";

    /******************************************* DVB-I ********************************************/
    @StringDef({KEY_CHANNEL_LIST_ID, KEY_URL, KEY_ENGINE_TYPE, KEY_TUNER_MODE,
            KEY_SERVICE_LIST_REC_ID, KEY_VERSION, KEY_REGION_ID, KEY_PACKAGE_ID,
            KEY_SERVICE_LIST_NAME, KEY_SERVICE_LIST_LOGO_URI, KEY_SERVICE_LIST_PROVIDER_NAME})
    @Retention(RetentionPolicy.SOURCE)
    public @interface DvbiServiceListInfoKey {}
    public static final String KEY_CHANNEL_LIST_ID = Channels.COLUMN_CHANNEL_LIST_ID;
    public static final String KEY_URL = "url";
    public static final String KEY_ENGINE_TYPE = "engine_type";
    public static final String KEY_TUNER_MODE = "tuner_mode";
    public static final String KEY_SERVICE_LIST_REC_ID = "service_list_rec_id";
    public static final String KEY_VERSION = Channels.COLUMN_VERSION_NUMBER;
    public static final String KEY_REGION_ID = "region_id";
    public static final String KEY_PACKAGE_ID = "package_id";
    public static final String KEY_SERVICE_LIST_NAME = "service_list_name";
    public static final String KEY_SERVICE_LIST_LOGO_URI = "service_list_logo_uri";
    public static final String KEY_SERVICE_LIST_PROVIDER_NAME = "service_list_provider_name";
}
