package com.android.clockwork.power;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.PowerManager;
import android.util.Log;

import com.android.internal.util.IndentingPrintWriter;

import java.util.HashSet;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * This class provides a single place to fetch or subscribe to power-related info such as whether
 * the device is plugged in or whether we are in power save mode.
 */
public class PowerTracker {
    private static final String TAG = WearPowerConstants.LOG_TAG;

    public interface Listener {
        default void onPowerSaveModeChanged() {}
        default void onChargingStateChanged() {}
        default void onDeviceIdleModeChanged() {}
    }

    private final Context mContext;
    private final PowerManager mPowerManager;
    private final AtomicBoolean mIsCharging = new AtomicBoolean(false);
    private final HashSet<Listener> mListeners = new HashSet<>();

    private final BroadcastReceiver chargingStateChangeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            boolean newState = false;
            switch (intent.getAction()) {
                case Intent.ACTION_POWER_CONNECTED:
                    newState = true;
                    break;
                case Intent.ACTION_POWER_DISCONNECTED:
                    newState = false;
                    break;
                default:
                    return;
            }

            final boolean prevState = mIsCharging.getAndSet(newState);
            if (prevState != newState) {
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG, String.format("Informing %d listeners of charging state change",
                                             mListeners.size()));
                }
                for (Listener listener : mListeners) {
                    listener.onChargingStateChanged();
                }
            }
        }
    };

    private final BroadcastReceiver powerSaveModeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (PowerManager.ACTION_POWER_SAVE_MODE_CHANGED.equals(intent.getAction())) {
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG, String.format("Informing %d listeners of power save mode change",
                                             mListeners.size()));
                }
                for (Listener listener : mListeners) {
                    listener.onPowerSaveModeChanged();
                }
            }
        }
    };

    private final BroadcastReceiver deviceIdleModeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED.equals(intent.getAction())) {
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG, String.format("Informing %d listeners of device idle mode change",
                                             mListeners.size()));
                }
                for (Listener listener : mListeners) {
                    listener.onDeviceIdleModeChanged();
                }
            }
        }
    };

    public PowerTracker(Context context, PowerManager powerManager) {
        mContext = context;
        mPowerManager = powerManager;
    }

    public void onBootCompleted() {
        mIsCharging.set(fetchInitialChargingState(mContext));

        IntentFilter powerIntentFilter = new IntentFilter();
        powerIntentFilter.addAction(Intent.ACTION_POWER_CONNECTED);
        powerIntentFilter.addAction(Intent.ACTION_POWER_DISCONNECTED);
        mContext.registerReceiver(chargingStateChangeReceiver, powerIntentFilter);

        mContext.registerReceiver(powerSaveModeReceiver,
                new IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED));

        mContext.registerReceiver(deviceIdleModeReceiver,
                new IntentFilter(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED));
    }

    public void addListener(Listener listener) {
        mListeners.add(listener);
    }

    public boolean isInPowerSave() {
        return mPowerManager.isPowerSaveMode();
    }

    public boolean isCharging() {
        return mIsCharging.get();
    }

    public boolean isDeviceIdle() {
        return mPowerManager.isDeviceIdleMode();
    }

    private boolean fetchInitialChargingState(Context context) {
        // Read the ACTION_BATTERY_CHANGED sticky broadcast for the current
        // battery status.
        Intent batteryStatus =
                context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (batteryStatus == null) {
            return false;
        }
        int plugged = batteryStatus.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
        return (plugged & BatteryManager.BATTERY_PLUGGED_ANY) != 0;
    }

    public void dump(IndentingPrintWriter ipw) {
        ipw.print("PowerTracker [ ");
        ipw.printPair("Charging", mIsCharging);
        ipw.printPair("InPowerSaveMode", mPowerManager.isPowerSaveMode());
        ipw.printPair("InDeviceIdleMode", mPowerManager.isDeviceIdleMode());
        ipw.println("]");
    }
}
