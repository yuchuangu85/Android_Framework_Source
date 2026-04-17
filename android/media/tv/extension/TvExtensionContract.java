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

package android.media.tv.extension;

import android.annotation.IntDef;
import android.net.Uri;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * <p> This class has the definitions for data that is stored in tv_extension.db. </p>
 *
 * @hide
 *
 */
public class TvExtensionContract {
    public static final String AUTHORITY = "android.media.tv.extension";
    public static final Uri AUTHORITY_URI = Uri.parse("content://" + AUTHORITY);
    public static final String GLOBAL_SETTING_TABLE = "global_settings";
    public static final String TUNER_OPERATOR_TABLE = "tuner_operator";
    public static final String GENERAL_SETTING_TABLE = "general_settings";
    public static final String DIGITAL_TUNER_SETTING_TABLE = "digital_tuner_settings";
    public static final String ANALOG_TUNER_SETTING_TABLE = "analog_tuner_settings";
    public static final String DVB_EXTENSION_TABLE = "dvb_extensions";
    public static final String INTERACTIVE_SETTING_TABLE = "interactive_settings";
    public static final String CC_STYLE_SETTINGS = "cc_style_settings";

    /**
     * Base Interface for Key-Value Columns
     */
    public interface KeyValueColumns {
        String COLUMN_KEY = "setting_key";
        String COLUMN_VALUE = "setting_value";
    }

    /**
     * Table 1: Global Settings (Key-Value Store)
     * Stores miscellaneous settings as key-value pairs.
     * | key  | value |
     * | TEXT | TEXT  |
     */
    public static final class GlobalSettings implements KeyValueColumns {
        public static final Uri CONTENT_URI = buildTableUri(GLOBAL_SETTING_TABLE);

        public static final int BROADCAST_TUNER_TYPE_ANTENNA = 0;
        public static final int BROADCAST_TUNER_TYPE_CABLE = 1;
        public static final int BROADCAST_TUNER_TYPE_SATELLITE = 2;

        /**
         * The column id.
         *
         */
        public static final String COLUMN_ID = "_id";

        /**
         * Get cable broadcaster name for cable TV from SDK. This maps to a string.
         *
         */
        public static final String KEY_CABLE_BROADCASTER_NAME = "cable_broadcaster_name";

        /**
         * The key for the tuner source type. Value is a {@link TunerSourceType}.
         *
         */
        public static final String KEY_TUNER_TYPE = "tuner_source_type";

        @IntDef ({
            BROADCAST_TUNER_TYPE_ANTENNA,
            BROADCAST_TUNER_TYPE_CABLE,
            BROADCAST_TUNER_TYPE_SATELLITE,
        })
        @Retention(RetentionPolicy.SOURCE)
        public @interface TunerSourceType {}

        /**
         * Set/Get tuner user type. Value uses a {@link TunerUserType}.
         *
         */
        public static final String KEY_TUNER_USER_TYPE = "user_broadcast_tuner_type";

        @IntDef({
            BROADCAST_TUNER_TYPE_ANTENNA,
            BROADCAST_TUNER_TYPE_CABLE,
            BROADCAST_TUNER_TYPE_SATELLITE,
        })
        @Retention(RetentionPolicy.SOURCE)
        public @interface TunerUserType {}

        /**
         * Set/Get Satellite tuner mode (General,Preferred). Values uses a {@link SatelliteType}.
         *
         */
        public static final String KEY_TUNER_SATELLITE_TYPE = "satellite_type";

        public static final int SATELLITE_TYPE_GENERAL = 0;
        public static final int SATELLITE_TYPE_PREFERRED = 1;
        @IntDef({
            SATELLITE_TYPE_GENERAL,
            SATELLITE_TYPE_PREFERRED,
        })
        @Retention(RetentionPolicy.SOURCE)
        public @interface SatelliteType {
        }

        /**
         * Set/Get channel list type.   "0" means it is normal channel list,
         * not CI+ channel list. If the value is larger than "0", it would be considered as
         * serviceListId, aka. the CAM service list inside the IServiceList.getServiceListIds().
         *
         */
        public static final String KEY_CHANNEL_LIST_TYPE = "channel_list_type";

        /**
         * Get satellite broadcaster name for Cable TV. This maps to a string.
         */
        public static final String KEY_SATELLITE_BROADCASTER = "satellite_broadcaster_name";

        // --- Parental & Security ---
        /**
         * Get current country/region code. Follow iso 3166-1 alpha-2 country codes.
         *
         */
        public static final String KEY_CURRENT_COUNTRY_REGION = "current_country_region_code";

        // --- OAD (Over Air Download / Firmware Update) ---

        /**
         * Set/Get OAD update file offset to/from SDK. Stored as a plain int in a string.
         *
         */
        public static final String KEY_OAD_OFFSET = "oad_offset";

        /**
         * Set/Get OAD update file size to/from SDK. Stored as a plain int in a string.
         *
         */
        public static final String KEY_OAD_SIZE = "oad_size";

        /**
         * Set/Get OAD properties to/from SDK. This is a json string.
         *
         */
        public static final String KEY_OAD_PROPERTIES = "oad_update_properties";

        /**
         * Reject OAD update. This is a "0" or a "1". It is used to indicate status "false" or
         * "true".
         *
         */
        public static final String KEY_OAD_REJECT_UPDATE = "oad_update_rejected_flag";

        /**
         * This is a "0" or a "1". It is used to indicate status "false" or "true".
         *
         */
        public static final String KEY_OAD_MARK = "oad_into_mark";

        /**
         * This is a "0" or a "1". It is used to indicate status "false" or "true".
         *
         */
        public static final String KEY_OAD_UNDONE = "oad_into_undone";

        // --- PVR & Timeshift ---
        /**
         * Set/Get PVR recording state in SDK. Value is a {@link RecordingState}.
         *
         */
        public static final String KEY_RECORDING_STATE = "recording_state";

        public static final int RECORDING_STATE_OFF = 0;
        public static final int RECORDING_STATE_TIMESHIFT_RUNNING = 1;
        public static final int RECORDING_STATE_PVR_RECORDING = 2;
        public static final int RECORDING_STATE_PVR_PLAYING = 3;

        @IntDef ({
            RECORDING_STATE_OFF,
            RECORDING_STATE_TIMESHIFT_RUNNING,
            RECORDING_STATE_PVR_RECORDING,
            RECORDING_STATE_PVR_PLAYING,
        })
        @Retention(RetentionPolicy.SOURCE)
        public @interface RecordingState {}

        /**
         * Set/Get Timeshift mode. Value is a {@link Timeshift}.
         *
         */
        public static final String KEY_TIMESHIFT_MODE = "timeshift_mode";

        public static final int TIMESHIFT_MODE_OFF = 0;
        public static final int TIMESHIFT_MODE_ON = 1;
        @IntDef ({
            TIMESHIFT_MODE_OFF,
            TIMESHIFT_MODE_ON
        })
        @Retention(RetentionPolicy.SOURCE)
        public @interface Timeshift {}

        /**
         * Set/Get Timeshift recording path. This maps to a string.
         *
         */
        public static final String KEY_TIMESHIFT_PATH = "timeshift_storage_path";

        /**
         * Set/Get Timeshift free space. Value long wrapped as a string.
         *
         */
        public static final String KEY_TIMESHIFT_FREE_SIZE = "timeshift_storage_free_bytes";

        /**
         * Set/Get PVR recording path. This maps to a string.
         *
         */
        public static final String KEY_RECORDING_PATH = "pvr_storage_path";

        // --- UI ---
        /**
         * The value is "0", "1". "0" for false, and "1" for true.
         *
         */
        public static final String KEY_UI_KEYPAD_SHOW_MENU = "ui_show_menu_on_keypad_press";

        /**
         * The value is "0", "1". "0" for false, and "1" for true.
         *
         */
        public static final String KEY_UI_KEYPAD_IS_FOREGROUND = "ui_tv_app_is_foreground";

        /**
         * Get does channel update message needs to be shown. The value is "0", "1". "0" for false,
         * and "1" for true.
         *
         */
        public static final String KEY_UI_CHANNEL_UPDATE_MSG =
                "ui_show_channel_update_notification";

        // --- EAS (Emergency Alert System) ---

        /**
         * Set EAS status to SDK. The value is "0", "1". "0" for off, and "1" for on.
         *
         */
        public static final String KEY_EAS_STATUS = "eas_status";

        /**
         * Set the eas channel change. The value is "0", "1". "0" for not changed, and "1" for
         * changed.
         *
         */
        public static final String KEY_EAS_IS_CHANNEL_CHANGE = "eas_channel_change";

        /**
         * parental pin code for cam. Value is a {@link CamType}.
         *
         */
        public static final String KEY_CAM_TYPE = "cam_type";
        public static final int CAM_TYPE_PCMCIA = 0;
        public static final int CAM_TYPE_USB = 1;
        public static final int CAM_TYPE_PCMCIA_USB = 2;
        @IntDef({
            CAM_TYPE_PCMCIA,
            CAM_TYPE_USB,
            CAM_TYPE_PCMCIA_USB
        })
        @Retention(RetentionPolicy.SOURCE)
        public @interface CamType{}

       /**
        * Set/Get parental pin in cam. This maps to a string.
        *
        */
        public static final String KEY_CAM_PIN_CODE = "cam_pin_code";

       /**
        * "1" for Support CI+2; "0" for don't support Support CI+2.
        *
        */
        public static final String KEY_CAM_IS_CI2PLUS_SUPPORTED = "cam_is_ciplus2_supported";

       /**
        * This returns whether or not the input is a global setting.
        * @param input The input
        * @return Whether or not the input is a global setting.
        */
        public static Boolean isGlobalSettingsKey(String input) {
            return switch(input)  {
                case COLUMN_ID, KEY_CABLE_BROADCASTER_NAME, KEY_TUNER_TYPE, KEY_TUNER_USER_TYPE,
                    KEY_TUNER_SATELLITE_TYPE, KEY_CHANNEL_LIST_TYPE, KEY_SATELLITE_BROADCASTER,
                    KEY_CURRENT_COUNTRY_REGION, KEY_OAD_OFFSET, KEY_OAD_SIZE, KEY_OAD_PROPERTIES,
                    KEY_OAD_REJECT_UPDATE, KEY_OAD_MARK, KEY_OAD_UNDONE,
                    KEY_RECORDING_STATE, KEY_RECORDING_PATH, KEY_TIMESHIFT_MODE, KEY_TIMESHIFT_PATH,
                    KEY_TIMESHIFT_FREE_SIZE, KEY_UI_KEYPAD_SHOW_MENU, KEY_UI_KEYPAD_IS_FOREGROUND,
                    KEY_UI_CHANNEL_UPDATE_MSG, KEY_EAS_STATUS, KEY_EAS_IS_CHANNEL_CHANGE,
                    KEY_CAM_PIN_CODE, KEY_CAM_IS_CI2PLUS_SUPPORTED -> true;
                default -> false;
            };
        }
    }

    /**
     * Table 2: Tuner Operators Names
     */
    public static final class TunerOperators {
        public static final Uri CONTENT_URI = buildTableUri(TUNER_OPERATOR_TABLE);

       /**
        * The name of the tuner. Value is a {@link BroadcastTunerType}.
        *
        */
        public static final String COLUMN_TUNER_NAME = "broadcast_tuner_type";

        public static final int ANTENNA = 0;
        public static final int CABLE = 1;
        public static final int SATELLITE = 2;

        @IntDef({
            ANTENNA,
            CABLE,
            SATELLITE
        })
        @Retention(RetentionPolicy.SOURCE)
        public @interface BroadcastTunerType {}

        // ==========================================
        // AUSTRIA (AT)
        // ==========================================
        public static final int OPERATOR_AT_MAGENTA_UPC = 33;
        public static final int OPERATOR_AT_ORF = 39;
        public static final int OPERATOR_AT_SIMPLITV = 31;
        public static final int OPERATOR_AT_M7_HD_AUSTRIA = 700;

        // ==========================================
        // AUSTRALIA (AU)
        // ==========================================
        public static final int OPERATOR_AU_FREEVIEW_PLUS = 10001;

        // ==========================================
        // BELGIUM (BE)
        // ==========================================
        public static final int OPERATOR_BE_M7_TELESAT = 705;
        public static final int OPERATOR_BE_M7_TV_VLAANDEREN = 702;
        public static final int OPERATOR_BE_TELENET = 20;
        public static final int OPERATOR_BE_VOO = 24;

        // ==========================================
        // SWITZERLAND (CH)
        // ==========================================
        public static final int OPERATOR_CH_KABELIO = 10002;
        public static final int OPERATOR_CH_SUNRISE_UPC = 62;

        // ==========================================
        // CZECH REPUBLIC (CZ)
        // ==========================================
        public static final int OPERATOR_CZ_M7_SKYLINK = 703;

        // ==========================================
        // GERMANY (DE)
        // ==========================================
        public static final int OPERATOR_DE_M7_FAST_SCAN_DEUTSCHLAND = 3004;
        public static final int OPERATOR_DE_DTVP = 10003;
        public static final int OPERATOR_DE_HD_PLUS = 26;
        public static final int OPERATOR_DE_SKY_DEUTSCHLAND = 27;
        public static final int OPERATOR_DE_VODAFONE_KDG = 17;
        public static final int OPERATOR_DE_VODAFONE_UNITY = 8;
        public static final int OPERATOR_DE_FREENET = 10004;

        // ==========================================
        // DENMARK (DK)
        // ==========================================
        public static final int OPERATOR_DK_NORLYS = 10005;
        public static final int OPERATOR_DK_STOFA = 5;
        public static final int OPERATOR_DK_YOUSEE = 6;

        // ==========================================
        // ESTONIA (EE)
        // ==========================================
        public static final int OPERATOR_EE_ANTENNI_TV = 10006;

        // ==========================================
        // SPAIN (ES)
        // ==========================================
        public static final int OPERATOR_ES_LOVESTV = 10007;
        public static final int OPERATOR_ES_TDT_HIBRIDA = 10008;
        public static final int OPERATOR_ES_DIGI = 10009;

        // ==========================================
        // FINLAND (FI)
        // ==========================================
        public static final int OPERATOR_FI_ANTENNA_READY = 10010;
        public static final int OPERATOR_FI_CABLE_READY = 35;

        // ==========================================
        // FRANCE (FR)
        // ==========================================
        public static final int OPERATOR_FR_FRANSAT = 10011;
        public static final int OPERATOR_FR_TNT_HD = 10012;
        public static final int OPERATOR_FR_CANAL_READY = 18;

        // ==========================================
        // GREECE (GR)
        // ==========================================
        public static final int OPERATOR_GR_ERTFLIX = 10013;

        // ==========================================
        // CROATIA (HR)
        // ==========================================
        public static final int OPERATOR_HR_OIV = 10014;

        // ==========================================
        // HUNGARY (HU)
        // ==========================================
        public static final int OPERATOR_HU_DIGI = 706;
        public static final int OPERATOR_HU_MINDIG = 10015;
        public static final int OPERATOR_HU_VODAFONE_UPC = 10016;

        // ==========================================
        // IRELAND (IE)
        // ==========================================
        public static final int OPERATOR_IE_SAORVIEW = 10017;

        // ==========================================
        // ITALY (IT)
        // ==========================================
        public static final int OPERATOR_IT_LATIVU = 10018;
        public static final int OPERATOR_IT_TIVUSAT = 25;

        // ==========================================
        // NETHERLANDS (NL)
        // ==========================================
        public static final int OPERATOR_NL_M7_CANAL_DIGITAAL = 701;
        public static final int OPERATOR_NL_ZIGGO_UPC = 7;

        // ==========================================
        // NORWAY (NO)
        // ==========================================
        public static final int OPERATOR_NO_CANAL_DIGITAL = 103;
        public static final int OPERATOR_NO_RIKSTV = 10019;

        // ==========================================
        // NORDIC REGION
        // ==========================================
        public static final int OPERATOR_NORDIC_VIASAT = 10020;

        // ==========================================
        // NEW ZEALAND (NZ)
        // ==========================================
        public static final int OPERATOR_NZ_FREEVIEW = 10021;

        // ==========================================
        // POLAND (PL)
        // ==========================================
        public static final int OPERATOR_PL_CANAL_PLUS = 10022;
        public static final int OPERATOR_PL_UPC = 10023;

        // ==========================================
        // ROMANIA (RO)
        // ==========================================
        public static final int OPERATOR_RO_M7_FOCUSSAT = 10024;
        public static final int OPERATOR_RO_DIGI = 709;
        public static final int OPERATOR_RO_VODAFONE_UPC = 10025;

        // ==========================================
        // SERBIA (RS)
        // ==========================================
        public static final int OPERATOR_RS_DIGITAL_TV = 54;

        // ==========================================
        // SWEDEN (SE)
        // ==========================================
        public static final int OPERATOR_SE_BOXER = 10026;
        public static final int OPERATOR_SE_TELE2_COMHEM = 2;
        public static final int OPERATOR_SE_TELENOR = 104;

        // ==========================================
        // TURKEY (TR)
        // ==========================================
        public static final int OPERATOR_TR_TKGS = 3001;
        public static final int OPERATOR_TR_DSMART = 30;

        // ==========================================
        // GENERIC / LEGACY
        // ==========================================
        public static final int OPERATOR_DEFAULT_TERRESTRIAL = 900; // DVBT_OTHERS
        public static final int OPERATOR_DEFAULT_CABLE = 901;       // DVBC_OTHERS
        public static final int OPERATOR_GENERAL_SATELLITE = 902;

        @IntDef({
                OPERATOR_AT_MAGENTA_UPC,
                OPERATOR_AT_ORF,
                OPERATOR_AT_SIMPLITV,
                OPERATOR_AT_M7_HD_AUSTRIA,
                OPERATOR_AU_FREEVIEW_PLUS,
                OPERATOR_BE_M7_TELESAT,
                OPERATOR_BE_M7_TV_VLAANDEREN,
                OPERATOR_BE_TELENET,
                OPERATOR_BE_VOO,
                OPERATOR_CH_KABELIO,
                OPERATOR_CH_SUNRISE_UPC,
                OPERATOR_CZ_M7_SKYLINK,
                OPERATOR_DE_SKY_DEUTSCHLAND,
                OPERATOR_DE_DTVP,
                OPERATOR_DE_HD_PLUS,
                OPERATOR_DE_M7_FAST_SCAN_DEUTSCHLAND,
                OPERATOR_DE_VODAFONE_KDG,
                OPERATOR_DE_VODAFONE_UNITY,
                OPERATOR_DE_FREENET,
                OPERATOR_DK_NORLYS,
                OPERATOR_DK_STOFA,
                OPERATOR_DK_YOUSEE,
                OPERATOR_EE_ANTENNI_TV,
                OPERATOR_ES_LOVESTV,
                OPERATOR_ES_TDT_HIBRIDA,
                OPERATOR_ES_DIGI,
                OPERATOR_FI_ANTENNA_READY,
                OPERATOR_FI_CABLE_READY,
                OPERATOR_FR_FRANSAT,
                OPERATOR_FR_TNT_HD,
                OPERATOR_FR_CANAL_READY,
                OPERATOR_GR_ERTFLIX,
                OPERATOR_HR_OIV,
                OPERATOR_HU_DIGI,
                OPERATOR_HU_MINDIG,
                OPERATOR_HU_VODAFONE_UPC,
                OPERATOR_IE_SAORVIEW,
                OPERATOR_IT_LATIVU,
                OPERATOR_IT_TIVUSAT,
                OPERATOR_NL_M7_CANAL_DIGITAAL,
                OPERATOR_NL_ZIGGO_UPC,
                OPERATOR_NO_CANAL_DIGITAL,
                OPERATOR_NO_RIKSTV,
                OPERATOR_NORDIC_VIASAT,
                OPERATOR_NZ_FREEVIEW,
                OPERATOR_PL_CANAL_PLUS,
                OPERATOR_PL_UPC,
                OPERATOR_RO_M7_FOCUSSAT,
                OPERATOR_RO_DIGI,
                OPERATOR_RO_VODAFONE_UPC,
                OPERATOR_RS_DIGITAL_TV,
                OPERATOR_SE_BOXER,
                OPERATOR_SE_TELE2_COMHEM,
                OPERATOR_SE_TELENOR,
                OPERATOR_TR_TKGS,
                OPERATOR_TR_DSMART,
                OPERATOR_DEFAULT_TERRESTRIAL,
                OPERATOR_DEFAULT_CABLE,
                OPERATOR_GENERAL_SATELLITE
        })
        @Retention(RetentionPolicy.SOURCE)
        public @interface OperatorId {}

        /**
         * The operator Id. Value is an integer type.
         * Value follows <a
         * href="https://docs.partner.android.com/tv/build/apps/cltv?hl=en#ref+6">Ref 6+ doc</a>
         * See {@link OperatorId}
         */
        public static final String COLUMN_OPERATOR_ID = "operator_id";

       /**
        * The name of the operator. This is a string.
        */
        public static final String COLUMN_OPERATOR_NAME = "operator_name";

    }


    /**
     * Table 3: General Settings (Single Row)
     * | id | audio_primary_language | audio_secondary_language | ...
     * | 0 | value                   | value                  | ..
     */
    public static final class GeneralSettings {
        public static final Uri CONTENT_URI = buildTableUri(GENERAL_SETTING_TABLE);
        public static final String COLUMN_ID = "_id";

        // --- AUDIO PREFERENCES ---

       /**
        * The primary audio language. This maps to a string and follows iso-639.
        *
        */
        public static final String COLUMN_PREFERRED_AUDIO_LANG_PRIMARY = "audio_primary_language";

       /**
        * The s audio language. This maps to a string and follows iso-639.
        *
        */
        public static final String COLUMN_PREFERRED_AUDIO_LANG_SECONDARY =
                "audio_secondary_language";

       /**
        * The audio type. Value is a {@link AudioType}.
        *
        */
        public static final String COLUMN_AUDIO_TYPE = "audio_type";


        public static final int AUDIO_TYPE_NORMAL = 0;
        public static final int AUDIO_TYPE_AUDIO_DESCRIPTION = 1;
        public static final int AUDIO_TYPE_SPOKEN_SUBTITLE = 2;
        public static final int AUDIO_TYPE_FOR_HARD_OF_HEARING = 3;
        public static final int AUDIO_TYPE_AUDIO_DESCRIPTION_AND_SPOKEN_SUBTITLE = 4;
        @IntDef({
            AUDIO_TYPE_NORMAL,
            AUDIO_TYPE_AUDIO_DESCRIPTION,
            AUDIO_TYPE_SPOKEN_SUBTITLE,
            AUDIO_TYPE_FOR_HARD_OF_HEARING,
            AUDIO_TYPE_AUDIO_DESCRIPTION_AND_SPOKEN_SUBTITLE
        })
        @Retention(RetentionPolicy.SOURCE)
        public @interface AudioType{}

        // --- SUBTITLE PREFERENCES ---

       /**
        * The primary subtitle language. This maps to a string and follows iso-639.
        *
        */
        public static final String COLUMN_PREFERRED_SUBTITLE_LANG_PRIMARY =
                "subtitle_primary_language";

       /**
        * The secondary subtitle language. This maps to a string and follows iso-639.
        *
        */
        public static final String COLUMN_PREFERRED_SUBTITLE_LANG_SECONDARY =
                "subtitle_secondary_language";

       /**
        * The visual impaired fader control. The value is a {@link VisuallyImpairedFaderControl}.
        *
        */
        public static final String COLUMN_VISUALLY_IMPAIRED_FADER_CONTROL =
                 "visually_impaired_fader_control";

        public static final int VISUALLY_IMPAIRED_FADER_CONTROL_OFF = 0;
        public static final int VISUALLY_IMPAIRED_FADER_CONTROL_ON = 1;
        @IntDef({
            VISUALLY_IMPAIRED_FADER_CONTROL_OFF,
            VISUALLY_IMPAIRED_FADER_CONTROL_ON
        })
        @Retention(RetentionPolicy.SOURCE)
        public @interface VisuallyImpairedFaderControl{}

       /**
        * The visually impaired mixing leve. The value is a between -32 and 32.
        *
        */
        public static final String COLUMN_VISUALLY_IMPAIRED_MIXING_LEVEL =
                "visually_impaired_mixing_level";

        // --- TELETEXT PREFERENCES ---

       /**
        * The teletext digital language. This maps to a string and follows iso-639.
        *
        */
        public static final String COLUMN_TELETEXT_DIGITAL_LANGUAGE = "teletext_digital_language";

       /**
        * The teletext decoding language. The value is a {@link TeletextDecodingLanguage}.
        *
        */
        public static final String COLUMN_TELETEXT_DECODING_LANGUAGE = "teletext_decoding_language";
        public static final int TELETEXT_DECODING_LANGUAGE_WEST_EUR = 0;
        public static final int TELETEXT_DECODING_LANGUAGE_EAST_EUR = 1;
        public static final int TELETEXT_DECODING_LANGUAGE_RUSSIA = 2;
        public static final int TELETEXT_DECODING_LANGUAGE_RUSSIA_2 = 3;
        public static final int TELETEXT_DECODING_LANGUAGE_GREEK = 4;
        public static final int TELETEXT_DECODING_LANGUAGE_TURKEY = 5;
        public static final int TELETEXT_DECODING_LANGUAGE_ARAB_HBRW = 6;
        public static final int TELETEXT_DECODING_LANGUAGE_FARSIAN = 7;
        public static final int TELETEXT_DECODING_LANGUAGE_ARAB = 8;
        public static final int TELETEXT_DECODING_LANGUAGE_BYELORUSSIAN = 9;

        @IntDef({
            TELETEXT_DECODING_LANGUAGE_WEST_EUR,
            TELETEXT_DECODING_LANGUAGE_EAST_EUR,
            TELETEXT_DECODING_LANGUAGE_RUSSIA,
            TELETEXT_DECODING_LANGUAGE_RUSSIA_2,
            TELETEXT_DECODING_LANGUAGE_GREEK,
            TELETEXT_DECODING_LANGUAGE_TURKEY,
            TELETEXT_DECODING_LANGUAGE_ARAB_HBRW,
            TELETEXT_DECODING_LANGUAGE_FARSIAN,
            TELETEXT_DECODING_LANGUAGE_ARAB,
            TELETEXT_DECODING_LANGUAGE_BYELORUSSIAN
        })
        @Retention(RetentionPolicy.SOURCE)
        public @interface TeletextDecodingLanguage{}

        // --- PARENTAL ---

       /**
        * The value is a @BlockUnratedProg.
        *
        */
        public static final String COLUMN_BLOCK_UNRATED_PROG = "block_unrated_prog";
        public static final int BLOCK_UNRATED_PROG_OFF = 0;
        public static final int BLOCK_UNRATED_PROG_ON = 1;
        @IntDef({
            BLOCK_UNRATED_PROG_OFF,
            BLOCK_UNRATED_PROG_ON
        })
        @Retention(RetentionPolicy.SOURCE)
        public @interface BlockUnratedProg{}
    }

    /**
     * Table 4: Digital Tuner Settings (Single Row)
     */
    public static final class DigitalTunerSettings {
        public static final Uri CONTENT_URI = buildTableUri(DIGITAL_TUNER_SETTING_TABLE);
        public static final String COLUMN_ID = "_id";

        // Subtitle track selection
       /**
        * The value is a {@link DigitalSubtitleDisplay}.
        *
        */
        public static final String COLUMN_DIGITAL_SUBTITLE_DISPLAY = "digital_subtitle_display";
        public static final int DIGITAL_SUBTITLE_DISPLAY_OFF = 0;
        public static final int DIGITAL_SUBTITLE_DISPLAY_ON = 1;
        public static final int DIGITAL_SUBTITLE_DISPLAY_HEARING_IMPAIRED = 2;
        @IntDef({
            DIGITAL_SUBTITLE_DISPLAY_OFF,
            DIGITAL_SUBTITLE_DISPLAY_ON,
            DIGITAL_SUBTITLE_DISPLAY_HEARING_IMPAIRED,
        })
        @Retention(RetentionPolicy.SOURCE)
        public @interface DigitalSubtitleDisplay{}

       /**
        * This is the track id for the digital subtitle, int value.
        *
        */
        public static final String COLUMN_DIGITAL_SUBTITLE_TRACK = "digital_subtitle_track";

       /**
        * The value is a {@link SuperImposeSetup}.
        *
        */
        public static final String COLUMN_SUPERIMPOSE = "superimpose";
        public static final int SUPERIMPOSE_SETUP_OFF = 0;
        public static final int SUPERIMPOSE_SETUP_LANG1 = 1;
        public static final int SUPERIMPOSE_SETUP_LANG2 = 2;
        @IntDef({
            SUPERIMPOSE_SETUP_OFF,
            SUPERIMPOSE_SETUP_LANG1,
            SUPERIMPOSE_SETUP_LANG2
        })
        @Retention(RetentionPolicy.SOURCE)
        public @interface SuperImposeSetup {}

        /**
         * Digital cc display mode, value must be one of {@link DigitalCCDisplay}
         */
        public static final String COLUMN_CC_DISPLAY = "digital_cc_display";
        public static final int DIGITAL_CC_DISPLAY_OFF = 0;
        public static final int DIGITAL_CC_DISPLAY_ON = 1;
        public static final int DIGITAL_CC_DISPLAY_MUTE = 2;
        @IntDef({
                DIGITAL_CC_DISPLAY_OFF,
                DIGITAL_CC_DISPLAY_ON,
                DIGITAL_CC_DISPLAY_MUTE
        })
        @Retention(RetentionPolicy.SOURCE)
        public @interface DigitalCCDisplay{}
    }

    /**
     * Table 5: Analog Tuner Settings (Single Row)
     */
    public static final class AnalogTunerSettings {
        public static final Uri CONTENT_URI = buildTableUri(ANALOG_TUNER_SETTING_TABLE);
        public static final String COLUMN_ID = "_id";
        public static final String COLUMN_ANALOG_SERVICE_SELECTION = "analog_service_selection";
        // Get/Set closed caption on/off/mute for analog services

       /**
        * The value is a {@link ClosedCaptionDisplay}.
        *
        */
        public static final String COLUMN_CLOSED_CAPTION_DISPLAY = "closed_caption_display";

        public static final int ANALOG_DISPLAY_OFF = 0;
        public static final int ANALOG_DISPLAY_ON = 1;
        public static final int ANALOG_DISPLAY_MUTE = 2;
        @IntDef({
            ANALOG_DISPLAY_OFF,
            ANALOG_DISPLAY_ON,
            ANALOG_DISPLAY_MUTE
        })
        @Retention(RetentionPolicy.SOURCE)
        public @interface ClosedCaptionDisplay{}

       /**
        * Set/Get subtitled display for analog subtitles. The value is a {@link AnalogSubtitle}.
        *
        */
        public static final String COLUMN_SUBTITLE_DISPLAY = "analog_subtitle_display";
        public static final int ANALOG_SUBTITLE_DISPLAY_OFF = 0;
        public static final int ANALOG_SUBTITLE_EIA_608 = 1;
        public static final int ANALOG_SUBTITLE_TELETEXT = 2;
        public static final int ANALOG_SUBTITLE_SCTE = 3;

        @IntDef({
            ANALOG_SUBTITLE_DISPLAY_OFF,
            ANALOG_SUBTITLE_EIA_608,
            ANALOG_SUBTITLE_TELETEXT,
            ANALOG_SUBTITLE_SCTE
        })
        @Retention(RetentionPolicy.SOURCE)
        public @interface AnalogSubtitle{}
    }

    /**
     * Table 6: DVB Extensions (Single Row)
     */
    public static final class DvbExtensions {
        public static final Uri CONTENT_URI = buildTableUri(DVB_EXTENSION_TABLE);
        public static final String COLUMN_ID = "_id";

       // --- LCN Settings ---
       /**
        * The value is a {@link LcnConflict}.
        *
        */
        public static final String COLUMN_LCN_CONFLICT = "bgm_lcn_conflict_happened";
        public static final int LCN_CONFLICT_HAS_NOT_OCCURRED = 0;
        public static final int LCN_CONFICT_HAS_OCCURRED = 1;
        @IntDef({
            LCN_CONFLICT_HAS_NOT_OCCURRED,
            LCN_CONFICT_HAS_OCCURRED
        })
        @Retention(RetentionPolicy.SOURCE)
        public @interface LcnConflict{}

       /**
        * The value is a {@link LcnUserSettings}.
        *
        */
        public static final String COLUMN_LCN_USER_SETTING_CABLE = "lcn_user_setting_cable";

        public static final int LCN_NONE = 0;
        public static final int LCN_ON = 1;
        public static final int LCN_OFF = 2;
        @IntDef({
            LCN_NONE,
            LCN_ON,
            LCN_OFF
        })
        @Retention(RetentionPolicy.SOURCE)
        public @interface LcnUserSettings{}

       /**
        * The value is a {@link LcnUserSettings}.
        *
        */
        public static final String COLUMN_LCN_USER_SETTING_TERRESTRIAL =
                "lcn_user_setting_terrestrial";

        // --- Tkgs ---
       /**
        * Value is a {@link TkgsAvailability}.
        *
        */
        public static final String COLUMN_TKGS_AVAIL_COND = "tkgs_avail_cond";
        public static final int TKGS_AVAILABILITY_COND_NORMAL = 0;
        public static final int TKGS_AVAILABILITY_COND_CERTIFICATION = 1;
        @IntDef({
            TKGS_AVAILABILITY_COND_NORMAL,
            TKGS_AVAILABILITY_COND_CERTIFICATION
        })
        @Retention(RetentionPolicy.SOURCE)
        public @interface TkgsAvailability{}

       /**
        * Value is a {@link TkgsOperatingMode}.
        *
        */
        public static final String COLUMN_TKGS_OPER_MODE = "tkgs_oper_mode";
        public static final int TKGS_OPERATING_MODE_AUTO = 0;
        public static final int TKGS_OPERATING_MODE_CUST = 1;
        public static final int TKGS_OPERATING_MODE_OFF = 2;
        @IntDef({
            TKGS_OPERATING_MODE_AUTO,
            TKGS_OPERATING_MODE_CUST,
            TKGS_OPERATING_MODE_OFF
        })

        @Retention(RetentionPolicy.SOURCE)
        public @interface TkgsOperatingMode{}

       /**
        * Value is a json string.
        *
        */
        public static final String COLUMN_TKGS_VISIBLE_LOCATOR_LIST = "tkgs_visible_locator_list";

       /**
        * Value is a json string.
        *
        */
        public static final String COLUMN_TKGS_HIDDEN_LOCATOR_LIST = "tkgs_hidden_locator_list";

       /**
        * Value is a json string.
        *
        */
        public static final String COLUMN_TKGS_TABLE_VERSION = "tkgs_table_version";

       /**
        * Value is a string.
        *
        */
        public static final String COLUMN_TKGS_USER_MESSAGE = "tkgs_user_message";

        // --- Misc ---
       /**
        * Value is an integer.
        *
        */
        public static final String COLUMN_CHANNEL_NUM_ADDED = "channel_num_added";

       /**
        * Value is a string.
        *
        */
        public static final String COLUMN_INDONESIA_EWS_LOCATION_CODE =
                "indonesia_ews_location_code";
    }

    /**
     * Table 7: CC Style Settings (Single Row)
     */

    public static final class CCStyleSettings {
        public static final Uri CONTENT_URI = buildTableUri(CC_STYLE_SETTINGS);

       /**
        * The value is an integer.
        *
        */
        public static final String COLUMN_ID = "_id";

       /**
        * The value is an {@link BackgroundOpacity}.
        *
        */
        public static final String CLOSED_CAPTION_BACKGROUND_OPACITY =
                "closed_caption_background_opacity";

        public static final int CLOSED_CAPTION_OPACITY_DEFAULT = 0;
        public static final int CLOSED_CAPTION_OPACITY_SOLID = 1;
        public static final int CLOSED_CAPTION_OPACITY_TRANSLUCENT = 2;
        public static final int CLOSED_CAPTION_OPACITY_TRANSPARENT = 3;
        public static final int CLOSED_CAPTION_OPACITY_FLASHING = 4;

        @IntDef({
            CLOSED_CAPTION_OPACITY_DEFAULT,
            CLOSED_CAPTION_OPACITY_SOLID,
            CLOSED_CAPTION_OPACITY_TRANSLUCENT,
            CLOSED_CAPTION_OPACITY_TRANSPARENT,
            CLOSED_CAPTION_OPACITY_FLASHING
        })
        @Retention(RetentionPolicy.SOURCE)
        public @interface BackgroundOpacity {}

       /**
        * The value is an {@link ClosedCaptionEdgeType}.
        *
        */
        public static final String CLOSED_CAPTION_EDGE_TYPE = "closed_caption_edge_type";

        public static final int CLOSED_CAPTION_EDGE_TYPE_DEFAULT = 0;
        public static final int CLOSED_CAPTION_EDGE_TYPE_NONE = 1;
        public static final int CLOSED_CAPTION_EDGE_TYPE_OUTLINE = 2;
        public static final int CLOSED_CAPTION_EDGE_TYPE_DROP_SHADOW = 3;
        public static final int CLOSED_CAPTION_EDGE_TYPE_RAISED = 4;
        public static final int CLOSED_CAPTION_EDGE_TYPE_DEPRESSED = 5;
        public static final int CLOSED_CAPTION_EDGE_TYPE_DROP_SHADOW_LEFT = 6;
        @IntDef({
            CLOSED_CAPTION_EDGE_TYPE_DEFAULT,
            CLOSED_CAPTION_EDGE_TYPE_NONE,
            CLOSED_CAPTION_EDGE_TYPE_OUTLINE,
            CLOSED_CAPTION_EDGE_TYPE_DROP_SHADOW,
            CLOSED_CAPTION_EDGE_TYPE_RAISED,
            CLOSED_CAPTION_EDGE_TYPE_DEPRESSED,
            CLOSED_CAPTION_EDGE_TYPE_DROP_SHADOW_LEFT
        })
        @Retention(RetentionPolicy.SOURCE)
        public @interface ClosedCaptionEdgeType {}

       /**
        * The value is an {@link ClosedCaptionTextColor}.
        *
        */
        public static final String CLOSED_CAPTION_TEXT_COLOR = "closed_caption_text_color";
        public static final int CLOSED_CAPTION_COLOR_DEFAULT = 0;
        public static final int CLOSED_CAPTION_COLOR_BLACK = 1;
        public static final int CLOSED_CAPTION_COLOR_WHITE = 2;
        public static final int CLOSED_CAPTION_COLOR_GREEN = 3;
        public static final int CLOSED_CAPTION_COLOR_BLUE = 4;
        public static final int CLOSED_CAPTION_COLOR_RED = 5;
        public static final int CLOSED_CAPTION_COLOR_CYAN = 6;
        public static final int CLOSED_CAPTION_COLOR_YELLOW = 7;
        public static final int CLOSED_CAPTION_COLOR_MAGENTA = 8;

        @IntDef({
            CLOSED_CAPTION_COLOR_DEFAULT,
            CLOSED_CAPTION_COLOR_BLACK,
            CLOSED_CAPTION_COLOR_WHITE,
            CLOSED_CAPTION_COLOR_GREEN,
            CLOSED_CAPTION_COLOR_BLUE,
            CLOSED_CAPTION_COLOR_RED,
            CLOSED_CAPTION_COLOR_CYAN,
            CLOSED_CAPTION_COLOR_YELLOW,
            CLOSED_CAPTION_COLOR_MAGENTA
        })
        @Retention(RetentionPolicy.SOURCE)
        public @interface ClosedCaptionTextColor {}

       /**
        * The value is an {@link ClosedCaptionWindowColor}.
        *
        */
        public static final String CLOSED_CAPTION_WINDOW_COLOR = "closed_caption_window_color";
        @IntDef({
            CLOSED_CAPTION_COLOR_DEFAULT,
            CLOSED_CAPTION_COLOR_BLACK,
            CLOSED_CAPTION_COLOR_WHITE,
            CLOSED_CAPTION_COLOR_GREEN,
            CLOSED_CAPTION_COLOR_BLUE,
            CLOSED_CAPTION_COLOR_RED,
            CLOSED_CAPTION_COLOR_CYAN,
            CLOSED_CAPTION_COLOR_YELLOW,
            CLOSED_CAPTION_COLOR_MAGENTA
        })
        @Retention(RetentionPolicy.SOURCE)
        public @interface ClosedCaptionWindowColor {}

       /**
        * The value is an {@link BackgroundColor}.
        *
        */
        public static final String CLOSED_CAPTION_BACKGROUND_COLOR =
                "closed_caption_background_color";
        @IntDef({
            CLOSED_CAPTION_COLOR_DEFAULT,
            CLOSED_CAPTION_COLOR_BLACK,
            CLOSED_CAPTION_COLOR_WHITE,
            CLOSED_CAPTION_COLOR_GREEN,
            CLOSED_CAPTION_COLOR_BLUE,
            CLOSED_CAPTION_COLOR_RED,
            CLOSED_CAPTION_COLOR_CYAN,
            CLOSED_CAPTION_COLOR_YELLOW,
            CLOSED_CAPTION_COLOR_MAGENTA
        })
        @Retention(RetentionPolicy.SOURCE)
        public @interface BackgroundColor {}

       /**
        * The value is an {@link ClosedCaptionEdgeColor}.
        *
        */
        public static final String CLOSED_CAPTION_EDGE_COLOR = "closed_caption_edge_color";
        @IntDef({
            CLOSED_CAPTION_COLOR_DEFAULT,
            CLOSED_CAPTION_COLOR_BLACK,
            CLOSED_CAPTION_COLOR_WHITE,
            CLOSED_CAPTION_COLOR_GREEN,
            CLOSED_CAPTION_COLOR_BLUE,
            CLOSED_CAPTION_COLOR_RED,
            CLOSED_CAPTION_COLOR_CYAN,
            CLOSED_CAPTION_COLOR_YELLOW,
            CLOSED_CAPTION_COLOR_MAGENTA
        })
        @Retention(RetentionPolicy.SOURCE)
        public @interface ClosedCaptionEdgeColor {}

       /**
        * The value is an {@link ClosedCaptionTextOpacity}.
        *
        */
        public static final String CLOSED_CAPTION_TEXT_OPACITY = "closed_caption_text_opacity";
        @IntDef({
            CLOSED_CAPTION_OPACITY_DEFAULT,
            CLOSED_CAPTION_OPACITY_SOLID,
            CLOSED_CAPTION_OPACITY_TRANSLUCENT,
            CLOSED_CAPTION_OPACITY_TRANSPARENT,
            CLOSED_CAPTION_OPACITY_FLASHING
        })
        @Retention(RetentionPolicy.SOURCE)
        public @interface ClosedCaptionTextOpacity {}

       /**
        * The value is an {@link ClosedCaptionTextSize}.
        *
        */
        public static final String CLOSED_CAPTION_TEXT_SIZE = "closed_caption_text_size";
        public static final int CLOSED_CAPTION_TEXT_SIZE_DEFAULT = 0;
        public static final int CLOSED_CAPTION_TEXT_SIZE_SMALL = 1;
        public static final int CLOSED_CAPTION_TEXT_SIZE_NORMAL = 2;
        public static final int CLOSED_CAPTION_TEXT_SIZE_LARGE = 3;

        @IntDef({
            CLOSED_CAPTION_TEXT_SIZE_DEFAULT,
            CLOSED_CAPTION_TEXT_SIZE_SMALL,
            CLOSED_CAPTION_TEXT_SIZE_NORMAL,
            CLOSED_CAPTION_TEXT_SIZE_LARGE
        })
        @Retention(RetentionPolicy.SOURCE)
        public @interface ClosedCaptionTextSize {}

       /**
        * The value is an {@link ClosedCaptionFontFamily}.
        *
        */
        public static final String CLOSED_CAPTION_FONT_FAMILY = "closed_caption_font_family";
        public static final int CLOSED_CAPTION_FRONT_FAMILY_SANS_DEFAULT = 0;
        public static final int CLOSED_CAPTION_FRONT_FAMILY_SANS_SERIF = 1;
        public static final int CLOSED_CAPTION_FRONT_FAMILY_SANS_SERIF_MONOSPACE = 2;
        public static final int CLOSED_CAPTION_FRONT_FAMILY_SERIF = 3;
        public static final int CLOSED_CAPTION_FRONT_FAMILY_SERIF_MONOSPACE = 4;
        public static final int CLOSED_CAPTION_FRONT_FAMILY_CASUAL = 5;
        public static final int CLOSED_CAPTION_FRONT_FAMILY_CURSIVE = 6;
        public static final int CLOSED_CAPTION_FRONT_FAMILY_SMALL_CAPTIALS = 7;

        @IntDef({
            CLOSED_CAPTION_FRONT_FAMILY_SANS_DEFAULT,
            CLOSED_CAPTION_FRONT_FAMILY_SANS_SERIF,
            CLOSED_CAPTION_FRONT_FAMILY_SANS_SERIF_MONOSPACE,
            CLOSED_CAPTION_FRONT_FAMILY_SERIF,
            CLOSED_CAPTION_FRONT_FAMILY_SERIF_MONOSPACE,
            CLOSED_CAPTION_FRONT_FAMILY_CASUAL,
            CLOSED_CAPTION_FRONT_FAMILY_CURSIVE,
            CLOSED_CAPTION_FRONT_FAMILY_SMALL_CAPTIALS
        })
        @Retention(RetentionPolicy.SOURCE)
        public @interface ClosedCaptionFontFamily {}

    }

    /**
     * Table 8: Interactive Settings (Single Row)
     */
    public static final class InteractiveSettings {
        public static final Uri CONTENT_URI = buildTableUri(INTERACTIVE_SETTING_TABLE);

       /**
        * The ID of the operator (acts as the index).
        *
        */
        public static final String COLUMN_ID = "_id";

       /**
        * The value is {@link GingaState}.
        *
        */
        public static final String COLUMN_GINGA_STATE = "ginga_state";
        public static final int DISABLE = 0;
        public static final int ENABLE = 1;

        @IntDef({
            DISABLE,
            ENABLE
        })
        @Retention(RetentionPolicy.SOURCE)
        public @interface GingaState {}

       /**
        * The value is {@link GingaAutoStartApplicationState}.
        *
        */
        public static final String COLUMN_GINGA_AUTO_START_APPLICATION_STATE =
                "ginga_auto_start_application_state";

        @IntDef({
            DISABLE,
            ENABLE
        })
        @Retention(RetentionPolicy.SOURCE)
        public @interface GingaAutoStartApplicationState {}

       /**
        * The value is {@link HbbtvEnable}.
        *
        */
        public static final String COLUMN_HBBTV_ENABLE = "hbbtv_enable";
        @IntDef({
            DISABLE,
            ENABLE
        })
        @Retention(RetentionPolicy.SOURCE)
        public @interface HbbtvEnable {}

       /**
        * The value is {@link HbbtvBlockTracking}.
        *
        */
        public static final String COLUMN_HBBTV_BLOCK_TRACKING = "hbbtv_block_tracking";

        public static final int NONE = 0;
        public static final int BLOCK = 1;
        @IntDef({
            NONE,
            BLOCK
        })
        @Retention(RetentionPolicy.SOURCE)
        public @interface HbbtvBlockTracking {}

       /**
        * The value is {@link HBBTV_HAS_DEVICE_ID}.
        *
        */
        public static final String COLUMN_HBBTV_DEVICE_ID = "hbbtv_has_device_id";

        public static final int HAS_DEVICE_ID = 1;

        @IntDef({
            NONE,
            HAS_DEVICE_ID
        })
        @Retention(RetentionPolicy.SOURCE)
        public @interface HBBTV_HAS_DEVICE_ID {}

       /**
        *  A String that represents the current HBBTV DEVICE ID, this column is used to reset/get
        *  HBBTV DEVICE ID
        *
        */
        public static final String COLUMN_HBBTV_RESET_DEVICE_ID = "hbbtv_reset_device_id";

       /**
        *  An integer that represents the timestamp that hbbtv was reset at.
        *
        */
        public static final String HBBTV_DEVICE_ID_SEED_TIMESTAMP =
                "hbbtv_device_id_seed_timestamp";

       /**
        * The value is a {@link HBBTV_PRIVACY_COOKIES_POLICY}.
        *
        */
        public static final String COLUMN_HBBTV_PRIVACY_POLICY_COOKIES_SETTINGS =
                "hbbtv_privacy_policy_cookies_settings";

        public static final int HBBTV_PRIVACY_POLICY_BLOCK_ALL = 0;
        public static final int HBBTV_PRIVACY_POLICY_BLOCK_3RD_PARTY_COOKIES = 1;
        public static final int HBBTV_PRIVACY_POLICY_DEFAULT = 2;
        @IntDef({
            HBBTV_PRIVACY_POLICY_BLOCK_ALL,
            HBBTV_PRIVACY_POLICY_BLOCK_3RD_PARTY_COOKIES,
            HBBTV_PRIVACY_POLICY_DEFAULT
        })
        @Retention(RetentionPolicy.SOURCE)
        public @interface HBBTV_PRIVACY_COOKIES_POLICY{}

       /**
        * The value is a {@link HBBTV_PRIVACY_DO_NOT_TRACK}.
        *
        */
        public static final String COLUMN_HBBTV_PRIVACY_POLICY_DO_NOT_TRACK =
                "hbbtv_privacy_policy_do_not_track";

        public static final int HBBTV_PRIVACY_POLICY_DO_NOT_TRACK_OFF = 0;
        public static final int HBBTV_PRIVACY_POLICY_DO_NOT_TRACK_ON = 1;
        public static final int HBBTV_PRIVACY_POLICY_DO_NOT_TRACK_DEFAULT = 2;
        @IntDef({
            HBBTV_PRIVACY_POLICY_DO_NOT_TRACK_OFF,
            HBBTV_PRIVACY_POLICY_DO_NOT_TRACK_ON,
            HBBTV_PRIVACY_POLICY_DO_NOT_TRACK_DEFAULT
        })
        @Retention(RetentionPolicy.SOURCE)
        public @interface HBBTV_PRIVACY_DO_NOT_TRACK {}

       /**
        * The value is a {@link HBBTV_PRIVACY_POLICY_PERSISTENT_STORAGE}.
        *
        */
        public static final String COLUMN_HBBTV_PRIVACY_POLICY_PERSISTENT_STORAGE =
                 "hbbtv_privacy_policy_persistent_storage";
        @IntDef({
            DISABLE,
            ENABLE
        })
        @Retention(RetentionPolicy.SOURCE)
        public @interface HBBTV_PRIVACY_POLICY_PERSISTENT_STORAGE {}

       /**
        *  Json string represent oppapp launch parameters
        *
        */
        public static final String COLUMN_OPAPP_LAUNCH_PARAMS = "opapp_launch_params";
    }

    private static Uri buildTableUri(String path) {
        return Uri.withAppendedPath(AUTHORITY_URI, path);
    }
}
