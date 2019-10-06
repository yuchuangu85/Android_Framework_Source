/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.car.setupwizardlib.util;

import android.car.Car;
import android.car.CarNotConnectedException;
import android.car.drivingstate.CarUxRestrictions;
import android.car.drivingstate.CarUxRestrictionsManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.VisibleForTesting;

/**
 * Monitor that listens for changes in the driving state so that it can trigger an exit of the
 * setup wizard when {@link CarUxRestrictions.UX_RESTRICTIONS_NO_SETUP}
 * is active.
 */
public class CarDrivingStateMonitor implements
        CarUxRestrictionsManager.OnUxRestrictionsChangedListener {

    public static final String EXIT_BROADCAST_ACTION =
            "com.android.car.setupwizardlib.driving_exit";

    private static final String TAG = "CarDrivingStateMonitor";
    private static final long DISCONNECT_DELAY_MS = 700;

    private Car mCar;
    private CarUxRestrictionsManager mRestrictionsManager;
    // Need to track the number of times the monitor is started so a single stopMonitor call does
    // not override them all.
    private int mMonitorStartedCount;
    // Flag that allows the monitor to be started for a ux restrictions check but not kept running.
    // This is particularly useful when a DrivingExit is triggered by an app external to the base
    // setup wizard package and we need to verify that it is a valid driving exit.
    private boolean mStopMonitorAfterUxCheck;
    private final Context mContext;
    @VisibleForTesting
    final Handler mHandler = new Handler(Looper.getMainLooper());
    @VisibleForTesting
    final Runnable mDisconnectRunnable = this::disconnectCarMonitor;

    private CarDrivingStateMonitor(Context context) {
        mContext = context.getApplicationContext();
    }

    /**
     * Returns the singleton instance of CarDrivingStateMonitor.
     */
    public static CarDrivingStateMonitor get(Context context) {
        return CarHelperRegistry.getOrCreateWithAppContext(
                context.getApplicationContext(),
                CarDrivingStateMonitor.class,
                CarDrivingStateMonitor::new);
    }

    /**
     * Starts the monitor listening to driving state changes.
     */
    public synchronized void startMonitor() {
        if (isVerboseLoggable()) {
            Log.v(TAG, "Starting monitor");
        }
        mMonitorStartedCount++;
        if (mMonitorStartedCount == 0) {
            return;
        }
        mHandler.removeCallbacks(mDisconnectRunnable);
        if (mCar != null) {
            if (mCar.isConnected()) {
                try {
                    onUxRestrictionsChanged(mRestrictionsManager.getCurrentCarUxRestrictions());
                } catch (CarNotConnectedException e) {
                    Log.e(TAG, "Car not connected", e);
                }
            } else {
                try {
                    mCar.connect();
                } catch (IllegalStateException e) {
                    // Connection failure - already connected or connecting.
                    Log.e(TAG, "Failure connecting to Car object.", e);
                }
            }
            return;
        }
        mCar = Car.createCar(mContext, new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                try {
                    mRestrictionsManager = (CarUxRestrictionsManager)
                            mCar.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE);
                    if (mRestrictionsManager == null) {
                        Log.e(TAG, "Unable to get CarUxRestrictionsManager");
                        return;
                    }
                    onUxRestrictionsChanged(mRestrictionsManager.getCurrentCarUxRestrictions());
                    mRestrictionsManager.registerListener(CarDrivingStateMonitor.this);
                    if (mStopMonitorAfterUxCheck) {
                        mStopMonitorAfterUxCheck = false;
                        stopMonitor();
                    }
                } catch (CarNotConnectedException e) {
                    Log.e(TAG, "Car not connected", e);
                }
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                try {
                    if (mRestrictionsManager != null) {
                        mRestrictionsManager.unregisterListener();
                        mRestrictionsManager = null;
                    }
                } catch (CarNotConnectedException e) {
                    Log.e(TAG, "Car not connected", e);
                }
            }
        });
        try {
            mCar.connect();
        } catch (IllegalStateException e) {
            // Connection failure - already connected or connecting.
            Log.e(TAG, "Failure connecting to Car object.", e);
        }
    }

    /**
     * Stops the monitor from listening for driving state changes. This will only occur after a
     * set delay so that calling stop/start in quick succession doesn't actually need to reconnect
     * to the service repeatedly. This monitor also maintains parity between started and stopped so
     * 2 started calls requires two stop calls to stop.
     */
    public synchronized void stopMonitor() {
        if (isVerboseLoggable()) {
            Log.v(TAG, "stopMonitor");
        }
        mHandler.removeCallbacks(mDisconnectRunnable);
        mMonitorStartedCount--;
        if (mMonitorStartedCount == 0) {
            if (isVerboseLoggable()) {
                Log.v(TAG, "Scheduling driving monitor timeout");
            }
            mHandler.postDelayed(mDisconnectRunnable, DISCONNECT_DELAY_MS);
        }
        if (mMonitorStartedCount < 0) {
            mMonitorStartedCount = 0;
        }
    }

    private void disconnectCarMonitor() {
        if (isVerboseLoggable()) {
            Log.v(TAG, "Timeout finished, disconnecting Car Monitor");
        }
        if (mMonitorStartedCount > 0) {
            return;
        }
        try {
            if (mRestrictionsManager != null) {
                mRestrictionsManager.unregisterListener();
                mRestrictionsManager = null;
            }
        } catch (CarNotConnectedException e) {
            Log.e(TAG, "Car not connected for unregistering listener", e);
        }

        if (mCar == null || !mCar.isConnected()) {
            return;
        }

        try {
            mCar.disconnect();
        } catch (IllegalStateException e) {
            // Connection failure - already disconnected or disconnecting.
            Log.e(TAG, "Failure disconnecting from Car object", e);
        }
    }

    /**
     * Returns {@code true} if the current driving state restricts setup from being completed.
     */
    public boolean checkIsSetupRestricted() {
        if (mMonitorStartedCount <= 0 && (mCar == null || !mCar.isConnected())) {
            if (isVerboseLoggable()) {
                Log.v(TAG, "Starting monitor to perform restriction check, returning false for "
                        + "restrictions in the meantime");
            }
            mStopMonitorAfterUxCheck = true;
            startMonitor();
            return false;
        }
        if (mRestrictionsManager == null) {
            if (isVerboseLoggable()) {
                Log.v(TAG, "Restrictions manager null in checkIsSetupRestricted, returning false");
            }
            return false;
        }
        try {
            return checkIsSetupRestricted(mRestrictionsManager.getCurrentCarUxRestrictions());
        } catch (CarNotConnectedException e) {
            Log.e(TAG, "CarNotConnected in checkIsSetupRestricted, returning false", e);
        }
        return false;
    }

    private boolean checkIsSetupRestricted(CarUxRestrictions restrictionInfo) {
        return (restrictionInfo.getActiveRestrictions()
                & CarUxRestrictions.UX_RESTRICTIONS_NO_SETUP) != 0;
    }

    @Override
    public void onUxRestrictionsChanged(CarUxRestrictions restrictionInfo) {
        // Check if setup restriction is active.
        if (isVerboseLoggable()) {
            Log.v(TAG, "onUxRestrictionsChanged");
        }

        // Get the current CarUxRestrictions rather than trusting the ones passed in.
        // This prevents in part interference from other applications triggering a setup wizard
        // exit unnecessarily, though the broadcast is also checked on the receiver side.
        if (mRestrictionsManager != null) {
            try {
                restrictionInfo = mRestrictionsManager.getCurrentCarUxRestrictions();
            } catch (CarNotConnectedException e) {
                Log.e(TAG, "Car not connected in onUxRestrictionsChanged, doing nothing.", e);
            }
        }

        if (checkIsSetupRestricted(restrictionInfo)) {
            if (isVerboseLoggable()) {
                Log.v(TAG, "Triggering driving exit broadcast");
            }
            Intent broadcastIntent = new Intent();
            broadcastIntent.setAction(EXIT_BROADCAST_ACTION);
            mContext.sendBroadcast(broadcastIntent);
        }
    }

    private boolean isVerboseLoggable() {
        return Log.isLoggable(TAG, Log.VERBOSE);
    }

    /**
     * Resets the car driving state monitor. This is only for use in testing.
     */
    @VisibleForTesting
    public static void reset(Context context) {
        CarHelperRegistry.getRegistry(context).putHelper(
                CarDrivingStateMonitor.class, new CarDrivingStateMonitor(context));
    }
}
