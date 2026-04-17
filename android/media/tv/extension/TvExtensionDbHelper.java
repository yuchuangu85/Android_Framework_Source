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

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.media.tv.extension.TvExtensionContract.AnalogTunerSettings;
import android.media.tv.extension.TvExtensionContract.CCStyleSettings;
import android.media.tv.extension.TvExtensionContract.DigitalTunerSettings;
import android.media.tv.extension.TvExtensionContract.DvbExtensions;
import android.media.tv.extension.TvExtensionContract.GeneralSettings;
import android.media.tv.extension.TvExtensionContract.GlobalSettings;
import android.media.tv.extension.TvExtensionContract.InteractiveSettings;
import android.media.tv.extension.TvExtensionContract.TunerOperators;
import android.util.Log;

/**
 * A helper class to manage the tv extension database.
 *
 * <p>This class implements the {@link #onCreate}, {@link #onUpgrade} that is
 * inherited from SQLiteOpenHelper. The on create pre-creates the tv_extension.db
 * and populates it. </p>
 *
 * @hide
 *
 */
public class TvExtensionDbHelper extends SQLiteOpenHelper {
    private static final int DATABASE_VERSION = 1;
    private static final String DATABASE_NAME = "tv_extension.db";
    private static final String TAG = "TvExtensionDbHelper";

    public TvExtensionDbHelper(Context context) {
        super(context, DATABASE_NAME, null, getDbVersion());
    }

    private static int getDbVersion() {
        return DATABASE_VERSION;
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        Log.d(TAG, "Creating sql database tables");

        // Global Settings Table
        db.execSQL("CREATE TABLE " + TvExtensionContract.GLOBAL_SETTING_TABLE + " ("
                + TvExtensionContract.GlobalSettings.COLUMN_KEY + " TEXT PRIMARY KEY NOT NULL, "
                + TvExtensionContract.GlobalSettings.COLUMN_VALUE + " TEXT"
                + ");");

        db.execSQL("INSERT INTO " + TvExtensionContract.GLOBAL_SETTING_TABLE
                + " (" + GlobalSettings.COLUMN_KEY + ")" + "VALUES"
                + "('" + GlobalSettings.KEY_CABLE_BROADCASTER_NAME + "'),"
                + "('" + GlobalSettings.KEY_UI_CHANNEL_UPDATE_MSG + "'),"
                + "('" + GlobalSettings.KEY_CHANNEL_LIST_TYPE + "'),"
                + "('" + GlobalSettings.KEY_CAM_PIN_CODE + "'),"
                + "('" + GlobalSettings.KEY_CAM_TYPE + "'),"
                + "('" + GlobalSettings.KEY_CAM_IS_CI2PLUS_SUPPORTED + "'),"
                + "('" + GlobalSettings.KEY_EAS_IS_CHANNEL_CHANGE + "'),"
                + "('" + GlobalSettings.KEY_EAS_STATUS + "'),"
                + "('" + GlobalSettings.KEY_SATELLITE_BROADCASTER + "'),"
                + "('" + GlobalSettings.KEY_TUNER_TYPE + "'),"
                + "('" + GlobalSettings.KEY_TUNER_USER_TYPE + "'),"
                + "('" + GlobalSettings.KEY_UI_KEYPAD_SHOW_MENU + "'),"
                + "('" + GlobalSettings.KEY_UI_KEYPAD_IS_FOREGROUND + "'),"
                + "('" + GlobalSettings.KEY_CURRENT_COUNTRY_REGION + "'),"
                + "('" + GlobalSettings.KEY_OAD_MARK + "'),"
                + "('" + GlobalSettings.KEY_OAD_UNDONE + "'),"
                + "('" + GlobalSettings.KEY_OAD_OFFSET + "'),"
                + "('" + GlobalSettings.KEY_OAD_PROPERTIES + "'),"
                + "('" + GlobalSettings.KEY_OAD_SIZE + "'),"
                + "('" + GlobalSettings.KEY_RECORDING_STATE + "'),"
                + "('" + GlobalSettings.KEY_OAD_REJECT_UPDATE + "'),"
                + "('" + GlobalSettings.KEY_TIMESHIFT_FREE_SIZE + "'),"
                + "('" + GlobalSettings.KEY_TIMESHIFT_MODE + "'),"
                + "('" + GlobalSettings.KEY_TIMESHIFT_PATH + "'),"
                + "('" + GlobalSettings.KEY_RECORDING_PATH + "'),"
                + "('" + GlobalSettings.KEY_TUNER_SATELLITE_TYPE + "');");

        // Tuner Operators Table
        db.execSQL("CREATE TABLE " + TvExtensionContract.TUNER_OPERATOR_TABLE + " ("
                + TunerOperators.COLUMN_TUNER_NAME
                + " INTEGER PRIMARY KEY, "
                + TunerOperators.COLUMN_OPERATOR_ID
                + " INTEGER DEFAULT 0, "
                + TunerOperators.COLUMN_OPERATOR_NAME
                + " TEXT,"
                + "CHECK (" + TunerOperators.COLUMN_TUNER_NAME + " BETWEEN 0 AND 2)"
                + ");");

        db.execSQL("INSERT INTO " + TvExtensionContract.TUNER_OPERATOR_TABLE
                        + "("
                        + TunerOperators.COLUMN_TUNER_NAME + ","
                        + TunerOperators.COLUMN_OPERATOR_ID + ","
                        + TunerOperators.COLUMN_OPERATOR_NAME
                        + ") VALUES"
                + "(" + TunerOperators.ANTENNA + ", 0, 'Digital TV'),"
                + "(" + TunerOperators.CABLE + ", 0, 'Cable TV'),"
                + "(" + TunerOperators.SATELLITE + ", 0, 'Satellite TV');");

        // General Settings Table
        db.execSQL("CREATE TABLE " + TvExtensionContract.GENERAL_SETTING_TABLE
                + " ("
                + GeneralSettings.COLUMN_ID + " INTEGER PRIMARY KEY, "
                + GeneralSettings.COLUMN_PREFERRED_AUDIO_LANG_PRIMARY
                + " TEXT, "
                + GeneralSettings.COLUMN_PREFERRED_AUDIO_LANG_SECONDARY
                + " TEXT, "
                + GeneralSettings.COLUMN_AUDIO_TYPE
                + " INTEGER DEFAULT 0, "
                + GeneralSettings.COLUMN_VISUALLY_IMPAIRED_FADER_CONTROL
                + " INTEGER DEFAULT 0,"
                + GeneralSettings.COLUMN_VISUALLY_IMPAIRED_MIXING_LEVEL
                + " INTEGER DEFAULT 0, "
                + GeneralSettings.COLUMN_PREFERRED_SUBTITLE_LANG_PRIMARY
                + " TEXT, "
                + GeneralSettings.COLUMN_PREFERRED_SUBTITLE_LANG_SECONDARY
                + " TEXT, "
                + GeneralSettings.COLUMN_TELETEXT_DIGITAL_LANGUAGE
                + " TEXT, "
                + GeneralSettings.COLUMN_TELETEXT_DECODING_LANGUAGE
                + " INTEGER DEFAULT 0, "
                + GeneralSettings.COLUMN_BLOCK_UNRATED_PROG
                + " INTEGER DEFAULT 0, "
                + "CHECK (" + GeneralSettings.COLUMN_AUDIO_TYPE + " BETWEEN 0 AND 4), "
                + "CHECK (" + GeneralSettings.COLUMN_BLOCK_UNRATED_PROG + " BETWEEN 0 AND 1), "
                + "CHECK (" + GeneralSettings.COLUMN_VISUALLY_IMPAIRED_FADER_CONTROL
                + " BETWEEN 0 AND 1), "
                + "CHECK ("
                + GeneralSettings.COLUMN_VISUALLY_IMPAIRED_MIXING_LEVEL
                + " BETWEEN -32 AND 32), "
                + "CHECK (" + GeneralSettings.COLUMN_TELETEXT_DECODING_LANGUAGE
                + " BETWEEN 0 AND 9)"
                + ");");

        db.execSQL("INSERT INTO " + TvExtensionContract.GENERAL_SETTING_TABLE + " DEFAULT VALUES;");

        // Digital Tuner Settings
        db.execSQL("CREATE TABLE " + TvExtensionContract.DIGITAL_TUNER_SETTING_TABLE + " ("
                + DigitalTunerSettings.COLUMN_ID + " INTEGER PRIMARY KEY, "
                + DigitalTunerSettings.COLUMN_DIGITAL_SUBTITLE_DISPLAY
                + " INTEGER DEFAULT 0, "
                + DigitalTunerSettings.COLUMN_DIGITAL_SUBTITLE_TRACK
                + " INTEGER, "
                + DigitalTunerSettings.COLUMN_SUPERIMPOSE
                + " INTEGER DEFAULT 0, "
                + DigitalTunerSettings.COLUMN_CC_DISPLAY
                + " INTEGER DEFAULT 0, "
                + "CHECK (" + DigitalTunerSettings.COLUMN_DIGITAL_SUBTITLE_DISPLAY
                + " BETWEEN 0 AND 2), "
                + "CHECK (" + DigitalTunerSettings.COLUMN_SUPERIMPOSE
                + " BETWEEN 0 AND 2), "
                + "CHECK (" + DigitalTunerSettings.COLUMN_CC_DISPLAY + " BETWEEN 0 AND 2) "
                + ");");
        db.execSQL("INSERT INTO " + TvExtensionContract.DIGITAL_TUNER_SETTING_TABLE
                + " DEFAULT VALUES;");

        // Analog Tuner Settings
        db.execSQL("CREATE TABLE " + TvExtensionContract.ANALOG_TUNER_SETTING_TABLE + " ("
                + AnalogTunerSettings.COLUMN_ID
                + " INTEGER PRIMARY KEY CHECK("
                + AnalogTunerSettings.COLUMN_ID + " = 1), "
                + AnalogTunerSettings.COLUMN_ANALOG_SERVICE_SELECTION
                + " INTEGER DEFAULT 1, "
                + AnalogTunerSettings.COLUMN_CLOSED_CAPTION_DISPLAY
                + " INTEGER DEFAULT 0, "
                + AnalogTunerSettings.COLUMN_SUBTITLE_DISPLAY
                + " INTEGER DEFAULT 0,"
                + "CHECK (" + AnalogTunerSettings.COLUMN_CLOSED_CAPTION_DISPLAY
                + " BETWEEN 0 AND 2), "
                + "CHECK (" + AnalogTunerSettings.COLUMN_SUBTITLE_DISPLAY
                + " BETWEEN 0 AND 3)"
                + ");");

        db.execSQL("INSERT INTO " + TvExtensionContract.ANALOG_TUNER_SETTING_TABLE
                + " DEFAULT VALUES;");

        // Dvb Extensions
        db.execSQL("CREATE TABLE " + TvExtensionContract.DVB_EXTENSION_TABLE
                + " ("
                + DvbExtensions.COLUMN_ID + " INTEGER PRIMARY KEY, "
                + " INTEGER, "
                + DvbExtensions.COLUMN_LCN_CONFLICT
                + " INTEGER DEFAULT 0, "
                + DvbExtensions.COLUMN_LCN_USER_SETTING_CABLE
                + " INTEGER, "
                + DvbExtensions.COLUMN_LCN_USER_SETTING_TERRESTRIAL
                + " INTEGER, "
                + DvbExtensions.COLUMN_TKGS_AVAIL_COND
                + " INTEGER, "
                + DvbExtensions.COLUMN_TKGS_OPER_MODE
                + " INTEGER DEFAULT 0, "
                + DvbExtensions.COLUMN_TKGS_VISIBLE_LOCATOR_LIST
                + " TEXT, "
                + DvbExtensions.COLUMN_TKGS_HIDDEN_LOCATOR_LIST
                + " TEXT, "
                + DvbExtensions.COLUMN_TKGS_TABLE_VERSION
                + " INTEGER DEFAULT 0, "
                + DvbExtensions.COLUMN_TKGS_USER_MESSAGE
                + " TEXT, "
                + DvbExtensions.COLUMN_CHANNEL_NUM_ADDED
                + " INTEGER DEFAULT 0,"
                + DvbExtensions.COLUMN_INDONESIA_EWS_LOCATION_CODE
                + " TEXT, "
                + "CHECK(" + DvbExtensions.COLUMN_LCN_CONFLICT + " BETWEEN 0 AND 1), "
                + "CHECK(" + DvbExtensions.COLUMN_LCN_USER_SETTING_CABLE + " BETWEEN 0 AND 2), "
                + "CHECK(" + DvbExtensions.COLUMN_TKGS_AVAIL_COND + " BETWEEN 0 AND 1), "
                + "CHECK(" + DvbExtensions.COLUMN_TKGS_OPER_MODE + " BETWEEN 0 AND 2) "
                + ");");
        db.execSQL("INSERT INTO " + TvExtensionContract.DVB_EXTENSION_TABLE + " DEFAULT VALUES;");

        // CC Style Settings
        db.execSQL("CREATE TABLE " + TvExtensionContract.CC_STYLE_SETTINGS + " ("
                + CCStyleSettings.COLUMN_ID + " INTEGER PRIMARY KEY, "
                + CCStyleSettings.CLOSED_CAPTION_BACKGROUND_COLOR
                + " INTEGER, "
                + CCStyleSettings.CLOSED_CAPTION_BACKGROUND_OPACITY
                + " INTEGER, "
                + CCStyleSettings.CLOSED_CAPTION_EDGE_COLOR
                + " INTEGER, "
                + CCStyleSettings.CLOSED_CAPTION_EDGE_TYPE
                + " INTEGER, "
                + CCStyleSettings.CLOSED_CAPTION_TEXT_COLOR
                + " INTEGER, "
                + CCStyleSettings.CLOSED_CAPTION_TEXT_OPACITY
                + " INTEGER, "
                + CCStyleSettings.CLOSED_CAPTION_TEXT_SIZE
                + " INTEGER, "
                + CCStyleSettings.CLOSED_CAPTION_FONT_FAMILY
                + " INTEGER,"
                + CCStyleSettings.CLOSED_CAPTION_WINDOW_COLOR
                + " INTEGER,"
                + "CHECK (" + CCStyleSettings.CLOSED_CAPTION_BACKGROUND_COLOR + " BETWEEN 0 AND 8),"
                + "CHECK (" + CCStyleSettings.CLOSED_CAPTION_BACKGROUND_OPACITY
                + " BETWEEN 0 AND 4),"
                + "CHECK (" + CCStyleSettings.CLOSED_CAPTION_EDGE_COLOR + " BETWEEN 0 AND 8),"
                + "CHECK (" + CCStyleSettings.CLOSED_CAPTION_EDGE_TYPE + " BETWEEN 0 AND 6),"
                + "CHECK (" + CCStyleSettings.CLOSED_CAPTION_TEXT_COLOR + " BETWEEN 0 AND 8),"
                + "CHECK (" + CCStyleSettings.CLOSED_CAPTION_TEXT_OPACITY + " BETWEEN 0 AND 4),"
                + "CHECK (" + CCStyleSettings.CLOSED_CAPTION_TEXT_SIZE + " BETWEEN 0 AND 3),"
                + "CHECK (" + CCStyleSettings.CLOSED_CAPTION_FONT_FAMILY + " BETWEEN 0 AND 7)"
                + ");");
        db.execSQL("INSERT INTO " + TvExtensionContract.CC_STYLE_SETTINGS + " DEFAULT VALUES;");

        // Interactive Settings
        db.execSQL("CREATE TABLE " + TvExtensionContract.INTERACTIVE_SETTING_TABLE + " ("
                + InteractiveSettings.COLUMN_ID + " INTEGER PRIMARY KEY, "
                + InteractiveSettings.COLUMN_GINGA_STATE
                + " INTEGER, "
                + InteractiveSettings.COLUMN_GINGA_AUTO_START_APPLICATION_STATE
                + " INTEGER, "
                + InteractiveSettings.COLUMN_HBBTV_ENABLE
                + " INTEGER, "
                + InteractiveSettings.COLUMN_HBBTV_BLOCK_TRACKING
                + " INTEGER, "
                + InteractiveSettings.COLUMN_HBBTV_DEVICE_ID
                + " INTEGER, "
                + InteractiveSettings.HBBTV_DEVICE_ID_SEED_TIMESTAMP
                + " INTEGER, "
                + InteractiveSettings.COLUMN_HBBTV_RESET_DEVICE_ID
                + " TEXT, "
                + InteractiveSettings.COLUMN_HBBTV_PRIVACY_POLICY_COOKIES_SETTINGS
                + " INTEGER, "
                + InteractiveSettings.COLUMN_HBBTV_PRIVACY_POLICY_DO_NOT_TRACK
                + " INTEGER, "
                + InteractiveSettings.COLUMN_HBBTV_PRIVACY_POLICY_PERSISTENT_STORAGE
                + " INTEGER, "
                + InteractiveSettings.COLUMN_OPAPP_LAUNCH_PARAMS
                + " TEXT,"
                + "CHECK (" + InteractiveSettings.COLUMN_GINGA_STATE + " BETWEEN 0 AND 1),"
                + "CHECK (" + InteractiveSettings.COLUMN_GINGA_AUTO_START_APPLICATION_STATE
                + " BETWEEN 0 AND 1),"
                + "CHECK (" + InteractiveSettings.COLUMN_HBBTV_BLOCK_TRACKING + " BETWEEN 0 AND 1),"
                + "CHECK (" + InteractiveSettings.COLUMN_HBBTV_DEVICE_ID + " BETWEEN 0 AND 1),"
                + "CHECK ("
                + InteractiveSettings.COLUMN_HBBTV_PRIVACY_POLICY_COOKIES_SETTINGS
                + " BETWEEN 0 AND 2), "
                + "CHECK ("
                + InteractiveSettings.COLUMN_HBBTV_PRIVACY_POLICY_DO_NOT_TRACK
                + " BETWEEN 0 AND 2), "
                + "CHECK ("
                + InteractiveSettings.COLUMN_HBBTV_PRIVACY_POLICY_PERSISTENT_STORAGE
                + " BETWEEN 0 AND 1), "
                + "CHECK (" + InteractiveSettings.COLUMN_HBBTV_ENABLE + " BETWEEN 0 AND 1) "
                + ");");
        db.execSQL("INSERT INTO " + TvExtensionContract.INTERACTIVE_SETTING_TABLE
                + " DEFAULT VALUES;");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS " + TvExtensionContract.GLOBAL_SETTING_TABLE);
        db.execSQL("DROP TABLE IF EXISTS " + TvExtensionContract.TUNER_OPERATOR_TABLE);
        db.execSQL("DROP TABLE IF EXISTS " + TvExtensionContract.GENERAL_SETTING_TABLE);
        db.execSQL("DROP TABLE IF EXISTS " + TvExtensionContract.DIGITAL_TUNER_SETTING_TABLE);
        db.execSQL("DROP TABLE IF EXISTS " + TvExtensionContract.ANALOG_TUNER_SETTING_TABLE);
        db.execSQL("DROP TABLE IF EXISTS " + TvExtensionContract.DVB_EXTENSION_TABLE);
        db.execSQL("DROP TABLE IF EXISTS " + TvExtensionContract.INTERACTIVE_SETTING_TABLE);
        db.execSQL("DROP TABLE IF EXISTS " + TvExtensionContract.CC_STYLE_SETTINGS);
        onCreate(db);
    }
}
