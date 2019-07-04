package com.android.clockwork.bluetooth;

import android.net.Uri;

/**
 * Moving/changing any of SettingsContract-related constants in this class requires updating
 * WearBluetoothConstantsTest in the Settings branch.
 */
public class WearBluetoothConstants {
    private WearBluetoothConstants() { };

    public static final String LOG_TAG = "WearBluetooth";

    // Keep in sync with com.google.android.clockwork.settings.SettingsContract.SETTINGS_AUTHORITY
    public static final String WEARABLE_SETTINGS_AUTHORITY = "com.google.android.wearable.settings";

    // Keep in sync with com.google.android.clockwork.settings.SettingsContract.BLUETOOTH_PATH
    public static final String BLUETOOTH_PATH = "bluetooth";

    // Keep in sync with com.google.android.clockwork.settings.SettingsContract.BLUETOOTH_URI
    public static final Uri BLUETOOTH_URI = buildUri(WEARABLE_SETTINGS_AUTHORITY, BLUETOOTH_PATH);

    // Keep in sync with
    // com.google.android.clockwork.settings.SettingsContract.KEY_COMPANION_ADDRESS
    public static final String KEY_COMPANION_ADDRESS = "companion_address";

    // Keep in sync with
    // com.google.android.clockwork.settings.SettingsContract.KEY_BLUETOOTH_MODE
    public static final String KEY_BLUETOOTH_MODE = "bluetooth_mode";

    public static final int BLUETOOTH_MODE_UNKNOWN = 0; // aka "unpaired"
    public static final int BLUETOOTH_MODE_NON_ALT = 1; // BT Classic
    public static final int BLUETOOTH_MODE_ALT = 2;     // BLE

    public static final String SETTINGS_COLUMN_KEY = "key";
    public static final String SETTINGS_COLUMN_VALUE = "value";

    // The normal score for proxy is 100 to guarantee that it is selected as the default network
    // by ConnectivityService.  However, when the device is on charger, a lower score is used
    // to ensure that WiFi can gain priority and be the default network instead.
    public static final int PROXY_SCORE_CLASSIC = 100;
    // A score of 55 is between the low end of WiFi (~60) and the score of cellular (50).
    // This is the same as the score used for BLE-paired Wear devices.
    public static final int PROXY_SCORE_ON_CHARGER = 55;

    private static Uri buildUri(String authority, String path) {
        return new Uri.Builder().scheme("content").authority(authority).path(path).build();
    }
}
