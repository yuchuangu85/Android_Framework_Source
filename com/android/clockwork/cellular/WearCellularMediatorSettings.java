package com.android.clockwork.cellular;

import static com.android.clockwork.cellular.WearCellularMediator.CELL_AUTO_OFF;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.provider.Settings;
import android.telephony.SubscriptionManager;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.telephony.ITelephony;
import com.android.internal.telephony.PhoneConstants;

import com.google.android.clockwork.signaldetector.SignalDetectorSettings;

import java.util.concurrent.TimeUnit;

public class WearCellularMediatorSettings implements SignalDetectorSettings {

    private static final String MOBILE_SIGNAL_DETECTOR_SERVICE_ALLOWED_KEY =
            "mobile_signal_detector_service_allowed";
    private static final int MOBILE_SIGNAL_DETECTOR_SERVICE_ALLOWED_DEFAULT = 1 /* true*/;

    private static final String MOBILE_SIGNAL_DETECTOR_QUEUE_MAX_SIZE_KEY =
            "mobile_signal_detector_queue_max_size";
    private static final int MOBILE_SIGNAL_DETECTOR_QUEUE_MAX_SIZE_DEFAULT = 50;

    private static final String MOBILE_SIGNAL_DETECTOR_INTERVAL_MS_KEY =
            "mobile_signal_detector_interval_ms";
    private static final long MOBILE_SIGNAL_DETECTOR_INTERVAL_MS_DEFAULT =
            TimeUnit.MINUTES.toMillis(12);

    private static final String MOBILE_SIGNAL_DETECTOR_BATTERY_DROP_THRESHOLD_KEY =
            "mobile_signal_detector_battery_drop_threshold";
    private static final int MOBILE_SIGNAL_DETECTOR_BATTERY_DROP_THRESHOLD_DEFAULT = 2;

    private static final String MOBILE_SIGNAL_DETECTOR_FREQUENT_EVENT_NUM_KEY =
            "mobile_signal_detector_frequent_event_num";
    private static final int MOBILE_SIGNAL_DETECTOR_FREQUENT_EVENT_NUM_DEFAULT = 20;

    private static final String MOBILE_SIGNAL_DETECTOR_DISABLED_MCC_MNC_LIST_KEY =
            "mobile_signal_detector_disabled_mcc_mnc_list";
    private static final String MOBILE_SIGNAL_DETECTOR_DISABLED_MCC_MNC_LIST_DEFAULT = "";
    public static final Uri MOBILE_SIGNAL_DETECTOR_DISABLED_MCC_MNC_LIST_KEY_URI =
            Settings.Global.getUriFor(MOBILE_SIGNAL_DETECTOR_DISABLED_MCC_MNC_LIST_KEY);

    private static final String CELLULAR_OFF_DURING_POWER_SAVE_KEY =
            "cellular_mediator_off_during_power_save";
    private static final int CELLULAR_OFF_DURING_POWER_SAVE_DEFAULT = 1 /* true */;

    private static final String PRODUCT_NAME = "ro.product.name";
    private static final String CARRIER_NAME = "ro.carrier";
    private static final String VERIZON_SUFFIX = "_vz";
    private static final String VERIZON_NAME = "verizon";

    private final Context mContext;
    private final String mSimOperator;
    private final boolean mCellAutoEnabled;

    /**
     * @param context     the application context.
     * @param simOperator the sim operator currently in use for the device.
     */
    public WearCellularMediatorSettings(Context context, String simOperator) {
        mContext = context;
        mSimOperator = simOperator;
        mCellAutoEnabled =
                SystemProperties.getBoolean("config.enable_cellmediator_cell_auto", false);
    }

    /**
     * Get the value of Settings.System.CELL_AUTO_SETTING_KEY.
     */
    public int getCellAutoSetting() {
        return mCellAutoEnabled ?
                Settings.System.getInt(
                        mContext.getContentResolver(),
                        WearCellularMediator.CELL_AUTO_SETTING_KEY,
                        WearCellularMediator.CELL_AUTO_SETTING_DEFAULT)
                : CELL_AUTO_OFF;
    }

    /**
     * Get the value of Settings.Global.CELL_ON.
     * The default value is CELL_ON_FLAG if not defined because we assume this service only runs on
     * the cellular-capable device (behind the config.enable_cellmediator flag in SystemServer).
     */
    public int getCellState() {
        return Settings.Global.getInt(
                mContext.getContentResolver(),
                Settings.Global.CELL_ON,
                PhoneConstants.CELL_ON_FLAG);
    }

    @Override
    public boolean getMobileSignalDetectorAllowed() {
        // Use the developer options override, if possible.
        Cursor cursor = mContext.getContentResolver()
                .query(WearCellularConstants.MOBILE_SIGNAL_DETECTOR_URI, null, null, null, null);
        if (cursor != null) {
            try {
                while (cursor.moveToNext()) {
                    if (WearCellularConstants.KEY_MOBILE_SIGNAL_DETECTOR.equals(
                            cursor.getString(0))) {
                        return Boolean.parseBoolean(cursor.getString(1));
                    }
                }
            } finally {
                cursor.close();
            }
        }

        boolean signalDetectorAllowedKeyValue =
                Settings.Global.getInt(mContext.getContentResolver(),
                        MOBILE_SIGNAL_DETECTOR_SERVICE_ALLOWED_KEY,
                        MOBILE_SIGNAL_DETECTOR_SERVICE_ALLOWED_DEFAULT) == 1;
        return !shouldDisableForCurrentOperator() && signalDetectorAllowedKeyValue;
    }

    @Override
    public int getMobileSignalDetectorQueueMaxSize() {
        return Settings.Global.getInt(
                mContext.getContentResolver(),
                MOBILE_SIGNAL_DETECTOR_QUEUE_MAX_SIZE_KEY,
                MOBILE_SIGNAL_DETECTOR_QUEUE_MAX_SIZE_DEFAULT);
    }

    @Override
    public long getMobileSignalDetectorIntervalMs() {
        return Settings.Global.getLong(
                mContext.getContentResolver(),
                MOBILE_SIGNAL_DETECTOR_INTERVAL_MS_KEY,
                MOBILE_SIGNAL_DETECTOR_INTERVAL_MS_DEFAULT);
    }

    @Override
    public int getMobileSignalDetectorBatteryDropThreshold() {
        return Settings.Global.getInt(
                mContext.getContentResolver(),
                MOBILE_SIGNAL_DETECTOR_BATTERY_DROP_THRESHOLD_KEY,
                MOBILE_SIGNAL_DETECTOR_BATTERY_DROP_THRESHOLD_DEFAULT);
    }

    @Override
    public int getMobileSignalDetectorFrequentEventNum() {
        return Settings.Global.getInt(
                mContext.getContentResolver(),
                MOBILE_SIGNAL_DETECTOR_FREQUENT_EVENT_NUM_KEY,
                MOBILE_SIGNAL_DETECTOR_FREQUENT_EVENT_NUM_DEFAULT);
    }

    @Override
    public boolean isDebugMode() {
        return false;
    }

    /**
     * Do not disable cell in power save mode for Verizon.
     * More details in b/34507932.
     */
    public boolean shouldTurnCellularOffDuringPowerSave() {
        int cellularDuringPowerSaveSetting =
                Settings.Global.getInt(
                        mContext.getContentResolver(),
                        CELLULAR_OFF_DURING_POWER_SAVE_KEY,
                        CELLULAR_OFF_DURING_POWER_SAVE_DEFAULT);
        String productName = SystemProperties.get(PRODUCT_NAME);
        boolean isVerizon =
                (productName != null && productName.endsWith(VERIZON_SUFFIX))
                        || TextUtils.equals(VERIZON_NAME, SystemProperties.get(CARRIER_NAME));
        return cellularDuringPowerSaveSetting == 1 && !isVerizon;
    }

    private boolean shouldDisableForCurrentOperator() {
        String disabledMccMncList = Settings.Global.getString(mContext.getContentResolver(),
                MOBILE_SIGNAL_DETECTOR_DISABLED_MCC_MNC_LIST_KEY);
        if (disabledMccMncList == null || TextUtils.isEmpty(mSimOperator)) {
            return false;
        }

        String[] list = disabledMccMncList.split(",");
        for (String disabled : list) {
            if (mSimOperator.equals(disabled)) {
                return true;
            }
        }

        return false;
    }

    /**
     * For some edge cases (b/35588911), radio power can be turned on inadvertently and
     * the variable to track radio power state doesn't get updated.
     * Note TelephonyManager.isRadioOn() is not used because in cases like b/35588911, it uses
     * the wrong default subscription id.
     *
     * @return RADIO_ON_STATE_UNKNOWN, RADIO_ON_STATE_ON, or RADIO_ON_STATE_OFF.
     */
    public int getRadioOnState() {
        // The trick to get subId is used in TelephonyManager#getSimOperatorNumeric().
        int subId = SubscriptionManager.getDefaultDataSubscriptionId();
        if (!SubscriptionManager.isUsableSubIdValue(subId)) {
            subId = SubscriptionManager.getDefaultSmsSubscriptionId();
            if (!SubscriptionManager.isUsableSubIdValue(subId)) {
                subId = SubscriptionManager.getDefaultVoiceSubscriptionId();
                if (!SubscriptionManager.isUsableSubIdValue(subId)) {
                    subId = SubscriptionManager.getDefaultSubscriptionId();
                }
            }
        }

        if (!SubscriptionManager.isUsableSubIdValue(subId)) {
            return WearCellularMediator.RADIO_ON_STATE_UNKNOWN;
        }

        try {
            ITelephony telephony = ITelephony.Stub.asInterface(ServiceManager.getService(
                    Context.TELEPHONY_SERVICE));
            if (telephony != null) {
                boolean isRadioOn = telephony.isRadioOnForSubscriber(subId,
                        mContext.getOpPackageName());
                return isRadioOn ? WearCellularMediator.RADIO_ON_STATE_ON
                        : WearCellularMediator.RADIO_ON_STATE_OFF;
            }
        } catch (RemoteException e) {
            Log.e(WearCellularMediator.TAG, "RemoteException calling isRadioOnForSubscriber()", e);
        }

        return WearCellularMediator.RADIO_ON_STATE_UNKNOWN;
    }
}
