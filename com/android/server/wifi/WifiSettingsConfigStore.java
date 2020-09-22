/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.wifi;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.net.wifi.WifiMigration;
import android.os.Handler;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import com.android.server.wifi.util.SettingsMigrationDataHolder;
import com.android.server.wifi.util.WifiConfigStoreEncryptionUtil;
import com.android.server.wifi.util.XmlUtil;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * Store data for storing wifi settings. These are key (string) / value pairs that are stored in
 * WifiConfigStore.xml file in a separate section.
 */
public class WifiSettingsConfigStore {
    private static final String TAG = "WifiSettingsConfigStore";

    // List of all allowed keys.
    private static final ArrayList<Key> sKeys = new ArrayList<>();

    /******** Wifi shared pref keys ***************/
    /**
     * Indicate whether factory reset request is pending.
     */
    public static final Key<Boolean> WIFI_P2P_PENDING_FACTORY_RESET =
            new Key<>("wifi_p2p_pending_factory_reset", false);

    /**
     * Allow scans to be enabled even wifi is turned off.
     */
    public static final Key<Boolean> WIFI_SCAN_ALWAYS_AVAILABLE =
            new Key<>("wifi_scan_always_enabled", false);

    /**
     * Whether wifi scan throttle is enabled or not.
     */
    public static final Key<Boolean> WIFI_SCAN_THROTTLE_ENABLED =
            new Key<>("wifi_scan_throttle_enabled", true);

    /**
     * Setting to enable verbose logging in Wi-Fi; disabled by default, and setting to 1
     * will enable it. In the future, additional values may be supported.
     */
    public static final Key<Boolean> WIFI_VERBOSE_LOGGING_ENABLED =
            new Key<>("wifi_verbose_logging_enabled", false);

    /**
     * The Wi-Fi peer-to-peer device name
     */
    public static final Key<String> WIFI_P2P_DEVICE_NAME =
            new Key<>("wifi_p2p_device_name", null);

    /******** Wifi shared pref keys ***************/

    private final Context mContext;
    private final Handler mHandler;
    private final SettingsMigrationDataHolder mSettingsMigrationDataHolder;
    private final WifiConfigManager mWifiConfigManager;

    private final Object mLock = new Object();
    @GuardedBy("mLock")
    private final Map<String, Object> mSettings = new HashMap<>();
    @GuardedBy("mLock")
    private final Map<String, Map<OnSettingsChangedListener, Handler>> mListeners =
            new HashMap<>();
    private WifiMigration.SettingsMigrationData mCachedMigrationData = null;

    private boolean mHasNewDataToSerialize = false;

    /**
     * Interface for a settings change listener.
     * @param <T> Type of the value.
     */
    public interface OnSettingsChangedListener<T> {
        /**
         * Invoked when a particular key settings changes.
         *
         * @param key Key that was changed.
         * @param newValue New value that was assigned to the key.
         */
        void onSettingsChanged(@NonNull Key<T> key, @Nullable T newValue);
    }

    public WifiSettingsConfigStore(@NonNull Context context, @NonNull Handler handler,
            @NonNull SettingsMigrationDataHolder settingsMigrationDataHolder,
            @NonNull WifiConfigManager wifiConfigManager,
            @NonNull WifiConfigStore wifiConfigStore) {
        mContext = context;
        mHandler = handler;
        mSettingsMigrationDataHolder = settingsMigrationDataHolder;
        mWifiConfigManager = wifiConfigManager;

        // Register our data store.
        wifiConfigStore.registerStoreData(new StoreData());
    }

    private void invokeAllListeners() {
        synchronized (mLock) {
            for (Key key : sKeys) {
                invokeListeners(key);
            }
        }
    }

    private <T> void invokeListeners(@NonNull Key<T> key) {
        synchronized (mLock) {
            if (!mSettings.containsKey(key.key)) return;
            Object newValue = mSettings.get(key.key);
            Map<OnSettingsChangedListener, Handler> listeners = mListeners.get(key.key);
            if (listeners == null || listeners.isEmpty()) return;
            for (Map.Entry<OnSettingsChangedListener, Handler> listener
                    : listeners.entrySet()) {
                // Trigger the callback in the appropriate handler.
                listener.getValue().post(() ->
                        listener.getKey().onSettingsChanged(key, newValue));
            }
        }
    }

    /**
     * Trigger config store writes and invoke listeners in the main wifi service looper's handler.
     */
    private void triggerSaveToStoreAndInvokeAllListeners() {
        mHandler.post(() -> {
            mHasNewDataToSerialize = true;
            mWifiConfigManager.saveToStore(true);

            invokeAllListeners();
        });
    }

    /**
     * Trigger config store writes and invoke listeners in the main wifi service looper's handler.
     */
    private <T> void triggerSaveToStoreAndInvokeListeners(@NonNull Key<T> key) {
        mHandler.post(() -> {
            mHasNewDataToSerialize = true;
            mWifiConfigManager.saveToStore(true);

            invokeListeners(key);
        });
    }

    /**
     * Performs a one time migration from Settings.Global values to settings store. Only
     * performed one time if the settings store is empty.
     */
    private void migrateFromSettingsIfNeeded() {
        if (!mSettings.isEmpty()) return; // already migrated.

        mCachedMigrationData = mSettingsMigrationDataHolder.retrieveData();
        if (mCachedMigrationData == null) {
            Log.e(TAG, "No settings data to migrate");
            return;
        }
        Log.i(TAG, "Migrating data out of settings to shared preferences");

        mSettings.put(WIFI_P2P_DEVICE_NAME.key,
                mCachedMigrationData.getP2pDeviceName());
        mSettings.put(WIFI_P2P_PENDING_FACTORY_RESET.key,
                mCachedMigrationData.isP2pFactoryResetPending());
        mSettings.put(WIFI_SCAN_ALWAYS_AVAILABLE.key,
                mCachedMigrationData.isScanAlwaysAvailable());
        mSettings.put(WIFI_SCAN_THROTTLE_ENABLED.key,
                mCachedMigrationData.isScanThrottleEnabled());
        mSettings.put(WIFI_VERBOSE_LOGGING_ENABLED.key,
                mCachedMigrationData.isVerboseLoggingEnabled());
        triggerSaveToStoreAndInvokeAllListeners();
    }

    /**
     * Store a value to the stored settings.
     *
     * @param key One of the settings keys.
     * @param value Value to be stored.
     */
    public <T> void put(@NonNull Key<T> key, @Nullable T value) {
        synchronized (mLock) {
            mSettings.put(key.key, value);
        }
        triggerSaveToStoreAndInvokeListeners(key);
    }

    /**
     * Retrieve a value from the stored settings.
     *
     * @param key One of the settings keys.
     * @return value stored in settings, defValue if the key does not exist.
     */
    public @Nullable <T> T get(@NonNull Key<T> key) {
        synchronized (mLock) {
            return (T) mSettings.getOrDefault(key.key, key.defaultValue);
        }
    }

    /**
     * Register for settings change listener.
     *
     * @param key One of the settings keys.
     * @param listener Listener to be registered.
     * @param handler Handler to post the listener
     */
    public <T> void registerChangeListener(@NonNull Key<T> key,
            @NonNull OnSettingsChangedListener<T> listener, @NonNull Handler handler) {
        synchronized (mLock) {
            mListeners.computeIfAbsent(
                    key.key, ignore -> new HashMap<>()).put(listener, handler);
        }
    }

    /**
     * Unregister for settings change listener.
     *
     * @param key One of the settings keys.
     * @param listener Listener to be unregistered.
     */
    public <T> void unregisterChangeListener(@NonNull Key<T> key,
            @NonNull OnSettingsChangedListener<T> listener) {
        synchronized (mLock) {
            Map<OnSettingsChangedListener, Handler> listeners = mListeners.get(key.key);
            if (listeners == null || listeners.isEmpty()) {
                Log.e(TAG, "No listeners for " + key);
                return;
            }
            if (listeners.remove(listener) == null) {
                Log.e(TAG, "Unknown listener for " + key);
            }
        }
    }

    /**
     * Dump output for debugging.
     */
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println();
        pw.println("Dump of " + TAG);
        pw.println("Settings:");
        for (Map.Entry<String, Object> entry : mSettings.entrySet()) {
            pw.print(entry.getKey());
            pw.print("=");
            pw.println(entry.getValue());
        }
        if (mCachedMigrationData == null) return;
        pw.println("Migration data:");
        pw.print(WIFI_P2P_DEVICE_NAME.key);
        pw.print("=");
        pw.println(mCachedMigrationData.getP2pDeviceName());
        pw.print(WIFI_P2P_PENDING_FACTORY_RESET.key);
        pw.print("=");
        pw.println(mCachedMigrationData.isP2pFactoryResetPending());
        pw.print(WIFI_SCAN_ALWAYS_AVAILABLE.key);
        pw.print("=");
        pw.println(mCachedMigrationData.isScanAlwaysAvailable());
        pw.print(WIFI_SCAN_THROTTLE_ENABLED.key);
        pw.print("=");
        pw.println(mCachedMigrationData.isScanThrottleEnabled());
        pw.print(WIFI_VERBOSE_LOGGING_ENABLED.key);
        pw.print("=");
        pw.println(mCachedMigrationData.isVerboseLoggingEnabled());
        pw.println();
    }

    /**
     * Base class to store string key and its default value.
     * @param <T> Type of the value.
     */
    public static class Key<T> {
        public final String key;
        public final T defaultValue;

        private Key(@NonNull String key, T defaultValue) {
            this.key = key;
            this.defaultValue = defaultValue;
            sKeys.add(this);
        }

        @Override
        public String toString() {
            return "[Key " + key + ", DefaultValue: " + defaultValue + "]";
        }
    }

    /**
     * Store data for persisting the settings data to config store.
     */
    private class StoreData implements WifiConfigStore.StoreData {
        private static final String XML_TAG_SECTION_HEADER = "Settings";
        private static final String XML_TAG_VALUES = "Values";

        @Override
        public void serializeData(XmlSerializer out,
                @Nullable WifiConfigStoreEncryptionUtil encryptionUtil)
                throws XmlPullParserException, IOException {
            synchronized (mLock) {
                XmlUtil.writeNextValue(out, XML_TAG_VALUES, mSettings);
            }
        }

        @Override
        public void deserializeData(XmlPullParser in, int outerTagDepth,
                @WifiConfigStore.Version int version,
                @Nullable WifiConfigStoreEncryptionUtil encryptionUtil)
                throws XmlPullParserException, IOException {
            if (in == null) {
                // Empty read triggers the migration since it indicates that there is no settings
                // data stored in the settings store.
                migrateFromSettingsIfNeeded();
                return;
            }
            Map<String, Object> values = null;
            while (!XmlUtil.isNextSectionEnd(in, outerTagDepth)) {
                String[] valueName = new String[1];
                Object value = XmlUtil.readCurrentValue(in, valueName);
                if (TextUtils.isEmpty(valueName[0])) {
                    throw new XmlPullParserException("Missing value name");
                }
                switch (valueName[0]) {
                    case XML_TAG_VALUES:
                        values = (Map) value;
                        break;
                    default:
                        Log.w(TAG, "Ignoring unknown tag under " + XML_TAG_SECTION_HEADER + ": "
                                + valueName[0]);
                        break;
                }
            }
            if (values != null) {
                synchronized (mLock) {
                    mSettings.putAll(values);
                    // Invoke all the registered listeners.
                    invokeAllListeners();
                }
            }
        }

        @Override
        public void resetData() {
            synchronized (mLock) {
                mSettings.clear();
            }
        }

        @Override
        public boolean hasNewDataToSerialize() {
            return mHasNewDataToSerialize;
        }

        @Override
        public String getName() {
            return XML_TAG_SECTION_HEADER;
        }

        @Override
        public @WifiConfigStore.StoreFileId int getStoreFileId() {
            // Shared general store.
            return WifiConfigStore.STORE_FILE_SHARED_GENERAL;
        }
    }
}
