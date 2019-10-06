package com.android.clockwork.remote;

import android.net.Uri;

/**
 * This class includes api contracts between the Settings, Home, and frameworks packages. Note that
 * these apps may not be built from the same library, so any changes here must be made with extreme
 * care. Home may be newer than Settings on a device, but not the other way around.
 *
 * <p>Note: Some constants in this class are used as the keys in Settings' shared preferences file.
 * Do NOT access default shared preferences from outside of SettingsProvider: You will cause severe
 * system instability and crashes that may not manifest themselves during development.
 *
 * <p>Keep in sync with {@link com.google.android.clockwork.settings.SettingsContract}
 */
public final class SettingsContract {
    private SettingsContract () {}

    public static final String SETTINGS_AUTHORITY = "com.google.android.wearable.settings";

    public static final String COLUMN_KEY = "key";
    public static final String COLUMN_VALUE = "value";

    public static final String AMBIENT_CONFIG_PATH = "ambient_config";
    public static final Uri AMBIENT_CONFIG_URI = buildUriForSettingsPath(AMBIENT_CONFIG_PATH);

    // The name of the row storing whether ambient touch to wake is enabled
    public static final String KEY_AMBIENT_TOUCH_TO_WAKE = "ambient_touch_to_wake";

    private static Uri buildUriForSettingsPath(String path) {
        return new Uri.Builder().scheme("content").authority(SETTINGS_AUTHORITY).path(path).build();
    }
}
