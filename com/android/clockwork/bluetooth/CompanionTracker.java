package com.android.clockwork.bluetooth;

import static com.android.clockwork.bluetooth.WearBluetoothConstants.BLUETOOTH_MODE_ALT;
import static com.android.clockwork.bluetooth.WearBluetoothConstants.BLUETOOTH_MODE_NON_ALT;
import static com.android.clockwork.bluetooth.WearBluetoothConstants.BLUETOOTH_MODE_UNKNOWN;
import static com.android.clockwork.bluetooth.WearBluetoothConstants.BLUETOOTH_URI;
import static com.android.clockwork.bluetooth.WearBluetoothConstants.KEY_BLUETOOTH_MODE;
import static com.android.clockwork.bluetooth.WearBluetoothConstants.KEY_COMPANION_ADDRESS;
import static com.android.clockwork.bluetooth.WearBluetoothConstants.SETTINGS_COLUMN_KEY;
import static com.android.clockwork.bluetooth.WearBluetoothConstants.SETTINGS_COLUMN_VALUE;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import com.android.internal.annotations.VisibleForTesting;
import java.util.HashSet;
import java.util.Set;

/**
 * This class monitors and maintains the mapping to the currently paired Companion device.
 *
 * This class expects the PairingHandler in ClockworkHome to set the Companion address
 * under Settings.BLUETOOTH_URI when the user initiates a successful pairing to the watch
 * from the Companion app.
 *
 * For the legacy case of there being a previously-paired device with no Companion address
 * written in Settings, CompanionTracker will infer the Companion device and store its address
 * into the same Settings location.
 *
 * Design doc:
 * https://docs.google.com/document/d/1E9-wBGZqHCB5Y7hJ-JI6ktb84tlG3e25AIazGOp7kEI/edit#
 */
public class CompanionTracker {
    public static final String TAG = WearBluetoothConstants.LOG_TAG;

    /** Callback when the companion has paired or unpaired to the watch */
    public interface Listener {
        void onCompanionChanged();
    }

    private final ContentResolver mContentResolver;
    @Nullable private final BluetoothAdapter mBtAdapter;
    @VisibleForTesting final SettingsObserver mSettingsObserver;
    private final Set<Listener> mListeners;

    private BluetoothDevice mCompanion;

    public CompanionTracker(ContentResolver contentResolver, BluetoothAdapter btAdapter) {
        mContentResolver = contentResolver;
        mBtAdapter = btAdapter;
        mSettingsObserver = new SettingsObserver(new Handler(Looper.getMainLooper()));
        mListeners = new HashSet<>();
        mCompanion = null;

        contentResolver.registerContentObserver(BLUETOOTH_URI, false, mSettingsObserver);
    }

    public void addListener(Listener listener) {
        mListeners.add(listener);
    }

    /**
     * Returns the BluetoothDevice object associated with the Companion device,
     * or null if no Companion device is paired.
     */
    public BluetoothDevice getCompanion() {
        return mCompanion;
    }

    /**
     * Returns the BluetoothDevice name associated with the Companion device,
     * or null if no Companion device is paired.
     */
    public String getCompanionName() {
        if (mCompanion != null) {
            return mCompanion.getName();
        }
        return null;
    }

    /**
     * Returns true iff the currently paired Companion device is an LE or DUAL device.
     *
     * Normally, we should just check the device type of mCompanion. But b/62355127 revealed that
     * BluetoothDevice.getType() can return LE/DUAL even for an Android device (particularly
     * when bonding is unexpectedly lost and re-established).  To workaround this, we rely on the
     * KEY_BLUETOOTH_MODE setting written by ConnectionSetupHelper in Setup, which during the
     * initial pairing process correctly identifies the device type.
     *
     * BluetoothDevice.getType() is used as a fallback only if for some reason the
     * KEY_BLUETOOTH_MODE setting has not been populated.
     */
    public boolean isCompanionBle() {
        if (mCompanion == null) {
            return false;
        }

        boolean deviceIsBle = mCompanion.getType() == BluetoothDevice.DEVICE_TYPE_LE
            || mCompanion.getType() == BluetoothDevice.DEVICE_TYPE_DUAL;

        int legacyBtMode = getIntValueForKey(
                BLUETOOTH_URI, KEY_BLUETOOTH_MODE, BLUETOOTH_MODE_UNKNOWN);
        if (legacyBtMode == BLUETOOTH_MODE_UNKNOWN) {
            Log.w(TAG, "Legacy BT Mode for paired companion device is unknown. "
                    + " Relying on device type instead.");
            return deviceIsBle;
        }

        boolean legacyModeIsBle = (legacyBtMode == BLUETOOTH_MODE_ALT);
        if (legacyModeIsBle != deviceIsBle) {
            Log.w(TAG, "Legacy BT Mode is different from paired device type. "
                    + "Paired device mode: " + mCompanion.getType() + "; "
                    + "Legacy BT Mode: " + legacyBtMode);
        }
        return legacyModeIsBle;
    }

    /**
     * This needs to be called once per reboot in order to guarantee that CompanionTracker
     * will have the correct Companion Device retrieved from persistent storage and the Bluetooth
     * adapter.
     */
    public void onBluetoothAdapterReady() {
        if (mBtAdapter == null) {
            return;
        }

        String companionAddress = getStringValueForKey(BLUETOOTH_URI, KEY_COMPANION_ADDRESS, null);
        if (companionAddress != null && !companionAddress.isEmpty()) {
            updateCompanionDevice(companionAddress);
            return;
        }

        // if we got to here, we're either not paired or we need to migrate from an existing device
        Set<BluetoothDevice> bondedDevices = mBtAdapter.getBondedDevices();
        if (bondedDevices.isEmpty()) {
            // we're not paired, so just bail
            return;
        }

        /** 
         * retrieve the pairing state by finding the most possible device.
         * This is the best-effort fix. Because there is no way to know whether a LE paired device
         * is a phone or not.
         * retrieve the pairing state by following step:
         * 1. Try to find whether there is a paired classic bluetooth device which is a phone. 
         * 2. If there is one, then it's Android companion
         * 3. If there isn't, then try to find whether there is a LE paired device, we guess the first
         * one is the companion.
         */
        Log.d(TAG, "Migrating legacy Companion address");
        for (BluetoothDevice device : bondedDevices) {
            if (device.getType() == BluetoothDevice.DEVICE_TYPE_CLASSIC) {
                final BluetoothClass btClass = device.getBluetoothClass();
                if (btClass != null && btClass.getMajorDeviceClass()
                        == BluetoothClass.Device.Major.PHONE) {
                    Log.d(TAG, "Found Android companion: " + device.getAddress());
                    mCompanion = device;
                    break;
                }
            }
        }
        if (mCompanion == null) {
            for (BluetoothDevice device : bondedDevices) {
                if (device.getType() == BluetoothDevice.DEVICE_TYPE_LE
                        || device.getType() == BluetoothDevice.DEVICE_TYPE_DUAL) {
                    Log.d(TAG, "Found LE device: " + device.getAddress());
                    mCompanion = device;
                    break;
                }
            }
        }

        // we found a legacy Companion pairing. update the database
        if (mCompanion != null) {
            ContentValues values = new ContentValues();
            values.put(KEY_COMPANION_ADDRESS, mCompanion.getAddress());
            mContentResolver.update(BLUETOOTH_URI, values, null, null);
        }
    }

    /**
     * A bluetooth device has just bonded.
     *
     * Check if the newly bonded device is our companion and notify
     * if we don't already have a companion initialized.
     */
    void receivedBondedAction(@NonNull final BluetoothDevice device) {
        final String companionAddress
            = getStringValueForKey(BLUETOOTH_URI, KEY_COMPANION_ADDRESS, null);
        if (mCompanion == null && device.getAddress().equals(companionAddress)) {
            notifyIfCompanionChanged(device.getAddress());
        }
    }

    /**
     * Listens for changes to the Bluetooth Settings and updates our pointer to the
     * currently-paired Companion device if necessary.
     */
    final class SettingsObserver extends ContentObserver {
        public SettingsObserver(Handler handler) {
            super(handler);
        }

        public void onChange(boolean selfChange, Uri uri) {
            if (uri.equals(BLUETOOTH_URI)) {
                String newCompanionAddress = getStringValueForKey(
                        BLUETOOTH_URI, KEY_COMPANION_ADDRESS, null);
                notifyIfCompanionChanged(newCompanionAddress);
            }
        }
    }

    private void notifyIfCompanionChanged(@Nullable final String newCompanionAddress) {
        if (newCompanionAddress != null) {
            if (updateCompanionDevice(newCompanionAddress)) {
                for (Listener listener : mListeners) {
                    listener.onCompanionChanged();
                }
            }
        }
    }

    /**
     * Returns true if the specified bluetooth device address matches a
     * currently bonded device.  If they match the companion
     * device address is updated to point to the specified bluetooth device.
     *
     * @param newDeviceAddr specified bluetooth device address.
     * @return
     */
    private boolean updateCompanionDevice(final String newDeviceAddr) {
        if (mBtAdapter == null) {
            return false;
        }

        if (mCompanion != null && mCompanion.getAddress().equals(newDeviceAddr)) {
            return false;
        }

        boolean updated = false;
        for (BluetoothDevice device : mBtAdapter.getBondedDevices()) {
            if (device.getAddress().equals(newDeviceAddr)) {
                mCompanion = device;
                updated = true;
            }
        }
        return updated;
    }

    private String getStringValueForKey(Uri queryUri, String key, String defaultValue) {
        Cursor cursor = mContentResolver.query(queryUri, null, null, null, null);
        if (cursor != null) {
            try {
                int keyColumn = cursor.getColumnIndex(SETTINGS_COLUMN_KEY);
                int valueColumn = cursor.getColumnIndex(SETTINGS_COLUMN_VALUE);
                while (cursor.moveToNext()) {
                    if (key.equals(cursor.getString(keyColumn))) {
                        return cursor.getString(valueColumn);
                    }
                }
            } finally {
                cursor.close();
            }
        }
        return defaultValue;
    }

    private int getIntValueForKey(Uri queryUri, String key, int defaultValue) {
        Cursor cursor = mContentResolver.query(queryUri, null, null, null, null);
        if (cursor != null) {
            try {
                int keyColumn = cursor.getColumnIndex(SETTINGS_COLUMN_KEY);
                int valueColumn = cursor.getColumnIndex(SETTINGS_COLUMN_VALUE);
                while (cursor.moveToNext()) {
                    if (key.equals(cursor.getString(keyColumn))) {
                        return cursor.getInt(valueColumn);
                    }
                }
            } finally {
                cursor.close();
            }
        }
        return defaultValue;
    }
}
