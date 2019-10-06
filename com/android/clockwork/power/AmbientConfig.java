package com.android.clockwork.power;

import android.content.ContentResolver;
import android.database.ContentObserver;
import android.database.Cursor;
import android.util.Log;

import com.android.clockwork.remote.SettingsContract;

import java.util.ArrayList;
import java.util.List;

/**
 * Provides access to Ambient Mode's configuration.
 */
public class AmbientConfig {
    private static final String TAG = WearPowerConstants.LOG_TAG;

    public interface Listener {
        void onAmbientConfigChanged();
    }

    private static final Boolean DEFAULT_IS_TOUCH_TO_WAKE = true;
    private boolean mIsTouchToWake = DEFAULT_IS_TOUCH_TO_WAKE;

    private final ContentResolver mContentResolver;
    private final List<Listener> mListeners = new ArrayList<>();

    public AmbientConfig(ContentResolver contentResolver) {
        mContentResolver = contentResolver;
    }

    public boolean isTouchToWake() {
        return mIsTouchToWake;
    }

    public void register() {
        mContentResolver.registerContentObserver(
                SettingsContract.AMBIENT_CONFIG_URI,
                false /* notifyForDescendants */,
                new ContentObserver(null) {
                    @Override
                    public void onChange(boolean selfChange) {
                        onSettingsChanged();
                    }
                });
        updateValues();
    }

    public void addListener(Listener listener) {
        mListeners.add(listener);
    }

    public void removeListener(Listener listener) {
        mListeners.remove(listener);
    }

    private void onSettingsChanged() {
        if (updateValues()) {
            for (Listener listener : mListeners) {
                listener.onAmbientConfigChanged();
            }
        }
    }

    private synchronized boolean updateValues() {
        Cursor cursor =
                mContentResolver.query(SettingsContract.AMBIENT_CONFIG_URI, null, null, null);
        if (cursor == null) {
            Log.w(TAG, "[AmbientConfig] Could not query settings ContentProvider." +
                    " Keeping current values.");
            return false;
        }

        boolean valuesChanged = false;
        try {
            final int keyColumn = cursor.getColumnIndex(SettingsContract.COLUMN_KEY);
            final int valueColumn = cursor.getColumnIndex(SettingsContract.COLUMN_VALUE);
            while (cursor.moveToNext()) {
                switch (cursor.getString(keyColumn)) {
                    case SettingsContract.KEY_AMBIENT_TOUCH_TO_WAKE:
                        boolean val = getBoolean(cursor, valueColumn, DEFAULT_IS_TOUCH_TO_WAKE);
                        if (mIsTouchToWake != val) {
                            mIsTouchToWake = val;
                            valuesChanged = true;
                        }
                        break;
                    default:
                        // We don't care about this value (yet)
                }
            }
        } finally {
            cursor.close();
        }

        return valuesChanged;
    }

    /**
     * Deals with columns in both the 1/0 and "true"/"false" formats.
     *
     * <p>We expect the 1/0 format, but let's support both.
     */
    private Boolean getBoolean(Cursor cursor, int valueColumn, Boolean defaultValue) {
        if (cursor.isNull(valueColumn)) {
            return defaultValue;
        }
        try {
            return cursor.getInt(valueColumn) != 0;
        } catch (NumberFormatException e) {
            String str = cursor.getString(valueColumn);
            if ("true".equalsIgnoreCase(str)) {
                return true;
            } else if ("false".equalsIgnoreCase(str)) {
                return false;
            }
            throw e;
        }
    }
}
