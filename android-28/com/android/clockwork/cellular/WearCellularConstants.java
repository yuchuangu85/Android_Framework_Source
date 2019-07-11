package com.android.clockwork.cellular;

import android.net.Uri;

public class WearCellularConstants {

    // Keep in sync with
    // com.google.android.clockwork.settings.SettingsContract.SETTINGS_AUTHORITY
    public static final String WEARABLE_SETTINGS_AUTHORITY = "com.google.android.wearable.settings";

    // Keep in sync with
    // com.google.android.clockwork.settings.SettingsContract.MOBILE_SIGNAL_DETECTOR_PATH
    public static final String MOBILE_SIGNAL_DETECTOR_PATH = "mobile_signal_detector";

    // Keep in sync with
    // com.google.android.clockwork.settings.SettingsContract.KEY_MOBILE_SIGNAL_DETECTOR
    public static final String KEY_MOBILE_SIGNAL_DETECTOR = "mobile_signal_detector";

    // Keep in sync with
    // com.google.android.clockwork.settings.SettingsContract.MOBILE_SIGNAL_DETECTOR_URI
    public static final Uri MOBILE_SIGNAL_DETECTOR_URI =
            buildUri(WEARABLE_SETTINGS_AUTHORITY, MOBILE_SIGNAL_DETECTOR_PATH);

    private static Uri buildUri(String authority, String path) {
        return new Uri.Builder().scheme("content").authority(authority).path(path).build();
    }
}