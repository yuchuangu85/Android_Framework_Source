package com.android.clockwork.bluetooth;

import android.content.ContentResolver;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import com.android.internal.annotations.VisibleForTesting;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * Settings wrapper for WearBluetoothMediator.
 */
public class WearBluetoothMediatorSettings {
    private static final String TAG = WearBluetoothConstants.LOG_TAG;

    private static final int BLUETOOTH_SETTINGS_PREF_OFF = 0;
    private static final int BLUETOOTH_SETTINGS_PREF_ON = 1;
    @VisibleForTesting static final String BLUETOOTH_SETTINGS_PREF_KEY = "cw_bt_settings_pref";

    private static final int AIRPLANE_MODE_OFF = 0;
    private static final int AIRPLANE_MODE_ON = 1;

    interface Listener {
        public void onAirplaneModeSettingChanged(boolean isAirplaneModeOn);
        public void onSettingsPreferenceBluetoothSettingChanged(
                boolean isSettingsPreferenceBluetoothOn);
    }

    private final ContentResolver mContentResolver;
    private final SettingsObserver mSettingsObserver;
    private final HashSet<Listener> mListeners = new HashSet<>();

    public WearBluetoothMediatorSettings(ContentResolver contentResolver) {
        mContentResolver = contentResolver;

        mSettingsObserver = new SettingsObserver(new Handler(Looper.getMainLooper()));
        for (Uri uri : getObservedUris()) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "Registering content observer for " + uri);
            }
            mContentResolver.registerContentObserver(uri, false, mSettingsObserver);
        }
    }

    @VisibleForTesting
    SettingsObserver getSettingsObserver() {
        return mSettingsObserver;
    }

    @VisibleForTesting
    List<Uri> getObservedUris() {
        ArrayList<Uri> uris = new ArrayList<>();
        uris.add(Settings.Global.getUriFor(Settings.Global.AIRPLANE_MODE_ON));
        uris.add(Settings.System.getUriFor(BLUETOOTH_SETTINGS_PREF_KEY));
        return uris;
    }

    public void addListener(Listener listener) {
        mListeners.add(listener);
    }

    @VisibleForTesting
    final class SettingsObserver extends ContentObserver {
        public SettingsObserver(Handler handler) {
            super(handler);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            if (Settings.Global.getUriFor(Settings.Global.AIRPLANE_MODE_ON).equals(uri)) {
                final boolean isAirplaneModeOn = getIsInAirplaneMode();
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG, "onChange Airplane mode turned "
                            + (isAirplaneModeOn ? "on" : "off"));
                }
                for (Listener listener : mListeners) {
                    listener.onAirplaneModeSettingChanged(isAirplaneModeOn);
                }
            } else if (Settings.System.getUriFor(BLUETOOTH_SETTINGS_PREF_KEY).equals(uri)) {
                final boolean settingsPreferenceBluetoothOn = getIsSettingsPreferenceBluetoothOn();
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG, "onChange settings bluetooth preference radio turned "
                            + (settingsPreferenceBluetoothOn ? "on" : "off"));
                }
                for (Listener listener : mListeners) {
                    listener.onSettingsPreferenceBluetoothSettingChanged(
                            settingsPreferenceBluetoothOn);
                }
            }
        }
    }

    boolean getIsSettingsPreferenceBluetoothOn() {
        return Settings.System.getInt(
                mContentResolver, BLUETOOTH_SETTINGS_PREF_KEY, BLUETOOTH_SETTINGS_PREF_ON)
                    == BLUETOOTH_SETTINGS_PREF_ON;
    }

    void setSettingsPreferenceBluetoothOn(final boolean isSettingsPreferenceBluetoothOn) {
        Settings.System.putInt(mContentResolver, BLUETOOTH_SETTINGS_PREF_KEY,
                isSettingsPreferenceBluetoothOn
                ? BLUETOOTH_SETTINGS_PREF_ON
                : BLUETOOTH_SETTINGS_PREF_OFF);
    }

    boolean getIsInAirplaneMode() {
        return Settings.Global.getInt(
                mContentResolver, Settings.Global.AIRPLANE_MODE_ON, AIRPLANE_MODE_OFF)
                    == AIRPLANE_MODE_ON;
    }
}
