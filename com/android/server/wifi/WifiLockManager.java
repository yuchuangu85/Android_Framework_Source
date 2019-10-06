/*
 * Copyright (C) 2016 The Android Open Source Project
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

import android.app.ActivityManager;
import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.WorkSource;
import android.os.WorkSource.WorkChain;
import android.util.Slog;
import android.util.SparseArray;
import android.util.StatsLog;

import com.android.internal.app.IBatteryStats;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * WifiLockManager maintains the list of wake locks held by different applications.
 */
public class WifiLockManager {
    private static final String TAG = "WifiLockManager";

    private static final int LOW_LATENCY_SUPPORT_UNDEFINED = -1;
    private static final int LOW_LATENCY_NOT_SUPPORTED     =  0;
    private static final int LOW_LATENCY_SUPPORTED         =  1;

    private static final int IGNORE_SCREEN_STATE_MASK = 0x01;
    private static final int IGNORE_WIFI_STATE_MASK   = 0x02;

    private int mLatencyModeSupport = LOW_LATENCY_SUPPORT_UNDEFINED;

    private boolean mVerboseLoggingEnabled = false;

    private final Clock mClock;
    private final Context mContext;
    private final IBatteryStats mBatteryStats;
    private final FrameworkFacade mFrameworkFacade;
    private final ClientModeImpl mClientModeImpl;
    private final ActivityManager mActivityManager;
    private final Handler mHandler;
    private final WifiMetrics mWifiMetrics;
    private final WifiNative mWifiNative;

    private final List<WifiLock> mWifiLocks = new ArrayList<>();
    // map UIDs to their corresponding records (for low-latency locks)
    private final SparseArray<UidRec> mLowLatencyUidWatchList = new SparseArray<>();
    private int mCurrentOpMode;
    private boolean mScreenOn = false;
    private boolean mWifiConnected = false;

    // For shell command support
    private boolean mForceHiPerfMode = false;
    private boolean mForceLowLatencyMode = false;

    // some wifi lock statistics
    private int mFullHighPerfLocksAcquired;
    private int mFullHighPerfLocksReleased;
    private int mFullLowLatencyLocksAcquired;
    private int mFullLowLatencyLocksReleased;
    private long mCurrentSessionStartTimeMs;

    WifiLockManager(Context context, IBatteryStats batteryStats,
            ClientModeImpl clientModeImpl, FrameworkFacade frameworkFacade, Handler handler,
            WifiNative wifiNative, Clock clock, WifiMetrics wifiMetrics) {
        mContext = context;
        mBatteryStats = batteryStats;
        mClientModeImpl = clientModeImpl;
        mFrameworkFacade = frameworkFacade;
        mActivityManager = (ActivityManager) mContext.getSystemService(Context.ACTIVITY_SERVICE);
        mCurrentOpMode = WifiManager.WIFI_MODE_NO_LOCKS_HELD;
        mWifiNative = wifiNative;
        mHandler = handler;
        mClock = clock;
        mWifiMetrics = wifiMetrics;

        // Register for UID fg/bg transitions
        registerUidImportanceTransitions();
    }

    // Check for conditions to activate high-perf lock
    private boolean canActivateHighPerfLock(int ignoreMask) {
        boolean check = true;

        // Only condition is when Wifi is connected
        if ((ignoreMask & IGNORE_WIFI_STATE_MASK) == 0) {
            check = check && mWifiConnected;
        }

        return check;
    }

    private boolean canActivateHighPerfLock() {
        return canActivateHighPerfLock(0);
    }

    // Check for conditions to activate low-latency lock
    private boolean canActivateLowLatencyLock(int ignoreMask, UidRec uidRec) {
        boolean check = true;

        if ((ignoreMask & IGNORE_WIFI_STATE_MASK) == 0) {
            check = check && mWifiConnected;
        }
        if ((ignoreMask & IGNORE_SCREEN_STATE_MASK) == 0) {
            check = check && mScreenOn;
        }
        if (uidRec != null) {
            check = check && uidRec.mIsFg;
        }

        return check;
    }

    private boolean canActivateLowLatencyLock(int ignoreMask) {
        return canActivateLowLatencyLock(ignoreMask, null);
    }

    private boolean canActivateLowLatencyLock() {
        return canActivateLowLatencyLock(0, null);
    }

    // Detect UIDs going foreground/background
    private void registerUidImportanceTransitions() {
        mActivityManager.addOnUidImportanceListener(new ActivityManager.OnUidImportanceListener() {
            @Override
            public void onUidImportance(final int uid, final int importance) {
                mHandler.post(() -> {
                    UidRec uidRec = mLowLatencyUidWatchList.get(uid);
                    if (uidRec == null) {
                        // Not a uid in the watch list
                        return;
                    }

                    boolean newModeIsFg = (importance
                            == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND);
                    if (uidRec.mIsFg == newModeIsFg) {
                        return; // already at correct state
                    }

                    uidRec.mIsFg = newModeIsFg;
                    updateOpMode();

                    // If conditions for lock activation are met,
                    // then UID either share the blame, or removed from sharing
                    // whether to start or stop the blame based on UID fg/bg state
                    if (canActivateLowLatencyLock()) {
                        setBlameLowLatencyUid(uid, uidRec.mIsFg);
                    }
                });
            }
        }, ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE);
    }

    /**
     * Method allowing a calling app to acquire a Wifi WakeLock in the supplied mode.
     *
     * This method checks that the lock mode is a valid WifiLock mode.
     * @param lockMode int representation of the Wifi WakeLock type.
     * @param tag String passed to WifiManager.WifiLock
     * @param binder IBinder for the calling app
     * @param ws WorkSource of the calling app
     *
     * @return true if the lock was successfully acquired, false if the lockMode was invalid.
     */
    public boolean acquireWifiLock(int lockMode, String tag, IBinder binder, WorkSource ws) {
        if (!isValidLockMode(lockMode)) {
            throw new IllegalArgumentException("lockMode =" + lockMode);
        }

        // Make a copy of the WorkSource before adding it to the WakeLock
        // This is to make sure worksource value can not be changed by caller
        // after function returns.
        WorkSource newWorkSource = new WorkSource(ws);

        return addLock(new WifiLock(lockMode, tag, binder, newWorkSource));
    }

    /**
     * Method used by applications to release a WiFi Wake lock.
     *
     * @param binder IBinder for the calling app.
     * @return true if the lock was released, false if the caller did not hold any locks
     */
    public boolean releaseWifiLock(IBinder binder) {
        return releaseLock(binder);
    }

    /**
     * Method used to get the strongest lock type currently held by the WifiLockManager.
     *
     * If no locks are held, WifiManager.WIFI_MODE_NO_LOCKS_HELD is returned.
     *
     * @return int representing the currently held (highest power consumption) lock.
     */
    public synchronized int getStrongestLockMode() {
        // If Wifi Client is not connected, then all locks are not effective
        if (!mWifiConnected) {
            return WifiManager.WIFI_MODE_NO_LOCKS_HELD;
        }

        // Check if mode is forced to hi-perf
        if (mForceHiPerfMode) {
            return WifiManager.WIFI_MODE_FULL_HIGH_PERF;
        }

        // Check if mode is forced to low-latency
        if (mForceLowLatencyMode) {
            return WifiManager.WIFI_MODE_FULL_LOW_LATENCY;
        }

        if (mScreenOn && countFgLowLatencyUids() > 0) {
            return WifiManager.WIFI_MODE_FULL_LOW_LATENCY;
        }

        if (mFullHighPerfLocksAcquired > mFullHighPerfLocksReleased) {
            return WifiManager.WIFI_MODE_FULL_HIGH_PERF;
        }

        return WifiManager.WIFI_MODE_NO_LOCKS_HELD;
    }

    /**
     * Method to create a WorkSource containing all active WifiLock WorkSources.
     */
    public synchronized WorkSource createMergedWorkSource() {
        WorkSource mergedWS = new WorkSource();
        for (WifiLock lock : mWifiLocks) {
            mergedWS.add(lock.getWorkSource());
        }
        return mergedWS;
    }

    /**
     * Method used to update WifiLocks with a new WorkSouce.
     *
     * @param binder IBinder for the calling application.
     * @param ws WorkSource to add to the existing WifiLock(s).
     */
    public synchronized void updateWifiLockWorkSource(IBinder binder, WorkSource ws) {

        // Now check if there is an active lock
        WifiLock wl = findLockByBinder(binder);
        if (wl == null) {
            throw new IllegalArgumentException("Wifi lock not active");
        }

        // Make a copy of the WorkSource before adding it to the WakeLock
        // This is to make sure worksource value can not be changed by caller
        // after function returns.
        WorkSource newWorkSource = new WorkSource(ws);

        if (mVerboseLoggingEnabled) {
            Slog.d(TAG, "updateWifiLockWakeSource: " + wl + ", newWorkSource=" + newWorkSource);
        }

        // Note:
        // Log the acquire before the release to avoid "holes" in the collected data due to
        // an acquire event immediately after a release in the case where newWorkSource and
        // wl.mWorkSource share one or more attribution UIDs. Both batteryStats and statsd
        // can correctly match "nested" acquire / release pairs.
        switch(wl.mMode) {
            case WifiManager.WIFI_MODE_FULL_HIGH_PERF:
                // Shift blame to new worksource if needed
                if (canActivateHighPerfLock()) {
                    setBlameHiPerfWs(newWorkSource, true);
                    setBlameHiPerfWs(wl.mWorkSource, false);
                }
                break;
            case WifiManager.WIFI_MODE_FULL_LOW_LATENCY:
                addWsToLlWatchList(newWorkSource);
                removeWsFromLlWatchList(wl.mWorkSource);
                updateOpMode();
                break;
            default:
                // Do nothing
                break;
        }

        wl.mWorkSource = newWorkSource;
    }

    /**
     * Method Used for shell command support
     *
     * @param isEnabled True to force hi-perf mode, false to leave it up to acquired wifiLocks.
     * @return True for success, false for failure (failure turns forcing mode off)
     */
    public boolean forceHiPerfMode(boolean isEnabled) {
        mForceHiPerfMode = isEnabled;
        mForceLowLatencyMode = false;
        if (!updateOpMode()) {
            Slog.e(TAG, "Failed to force hi-perf mode, returning to normal mode");
            mForceHiPerfMode = false;
            return false;
        }
        return true;
    }

    /**
     * Method Used for shell command support
     *
     * @param isEnabled True to force low-latency mode, false to leave it up to acquired wifiLocks.
     * @return True for success, false for failure (failure turns forcing mode off)
     */
    public boolean forceLowLatencyMode(boolean isEnabled) {
        mForceLowLatencyMode = isEnabled;
        mForceHiPerfMode = false;
        if (!updateOpMode()) {
            Slog.e(TAG, "Failed to force low-latency mode, returning to normal mode");
            mForceLowLatencyMode = false;
            return false;
        }
        return true;
    }

    /**
     * Handler for screen state (on/off) changes
     */
    public void handleScreenStateChanged(boolean screenOn) {
        if (mVerboseLoggingEnabled) {
            Slog.d(TAG, "handleScreenStateChanged: screenOn = " + screenOn);
        }

        mScreenOn = screenOn;

        if (canActivateLowLatencyLock(IGNORE_SCREEN_STATE_MASK)) {
            // Update the running mode
            updateOpMode();
            // Adjust blaming for UIDs in foreground
            setBlameLowLatencyWatchList(screenOn);
        }
    }

    /**
     * Handler for Wifi Client mode state changes
     */
    public void updateWifiClientConnected(boolean isConnected) {
        if (mWifiConnected == isConnected) {
            // No need to take action
            return;
        }
        mWifiConnected = isConnected;

        // Adjust blaming for UIDs in foreground carrying low latency locks
        if (canActivateLowLatencyLock(IGNORE_WIFI_STATE_MASK)) {
            setBlameLowLatencyWatchList(mWifiConnected);
        }

        // Adjust blaming for UIDs carrying high perf locks
        // Note that blaming is adjusted only if needed,
        // since calling this API is reference counted
        if (canActivateHighPerfLock(IGNORE_WIFI_STATE_MASK)) {
            setBlameHiPerfLocks(mWifiConnected);
        }

        updateOpMode();
    }

    private void setBlameHiPerfLocks(boolean shouldBlame) {
        for (WifiLock lock : mWifiLocks) {
            if (lock.mMode == WifiManager.WIFI_MODE_FULL_HIGH_PERF) {
                setBlameHiPerfWs(lock.getWorkSource(), shouldBlame);
            }
        }
    }

    private static boolean isValidLockMode(int lockMode) {
        if (lockMode != WifiManager.WIFI_MODE_FULL
                && lockMode != WifiManager.WIFI_MODE_SCAN_ONLY
                && lockMode != WifiManager.WIFI_MODE_FULL_HIGH_PERF
                && lockMode != WifiManager.WIFI_MODE_FULL_LOW_LATENCY) {
            return false;
        }
        return true;
    }

    private void addUidToLlWatchList(int uid) {
        UidRec uidRec = mLowLatencyUidWatchList.get(uid);
        if (uidRec != null) {
            uidRec.mLockCount++;
        } else {
            uidRec = new UidRec(uid);
            uidRec.mLockCount = 1;
            mLowLatencyUidWatchList.put(uid, uidRec);

            // Now check if the uid is running in foreground
            if (mFrameworkFacade.isAppForeground(uid)) {
                uidRec.mIsFg = true;
            }

            if (canActivateLowLatencyLock(0, uidRec)) {
                // Share the blame for this uid
                setBlameLowLatencyUid(uid, true);
            }
        }
    }

    private void removeUidFromLlWatchList(int uid) {
        UidRec uidRec = mLowLatencyUidWatchList.get(uid);
        if (uidRec == null) {
            Slog.e(TAG, "Failed to find uid in low-latency watch list");
            return;
        }

        if (uidRec.mLockCount > 0) {
            uidRec.mLockCount--;
        } else {
            Slog.e(TAG, "Error, uid record conatains no locks");
        }
        if (uidRec.mLockCount == 0) {
            mLowLatencyUidWatchList.remove(uid);

            // Remove blame for this UID if it was alerady set
            // Note that blame needs to be stopped only if it was started before
            // to avoid calling the API unnecessarily, since it is reference counted
            if (canActivateLowLatencyLock(0, uidRec)) {
                setBlameLowLatencyUid(uid, false);
            }
        }
    }

    private void addWsToLlWatchList(WorkSource ws) {
        int wsSize = ws.size();
        for (int i = 0; i < wsSize; i++) {
            final int uid = ws.get(i);
            addUidToLlWatchList(uid);
        }

        final List<WorkChain> workChains = ws.getWorkChains();
        if (workChains != null) {
            for (int i = 0; i < workChains.size(); ++i) {
                final WorkChain workChain = workChains.get(i);
                final int uid = workChain.getAttributionUid();
                addUidToLlWatchList(uid);
            }
        }
    }

    private void removeWsFromLlWatchList(WorkSource ws) {
        int wsSize = ws.size();
        for (int i = 0; i < wsSize; i++) {
            final int uid = ws.get(i);
            removeUidFromLlWatchList(uid);
        }

        final List<WorkChain> workChains = ws.getWorkChains();
        if (workChains != null) {
            for (int i = 0; i < workChains.size(); ++i) {
                final WorkChain workChain = workChains.get(i);
                final int uid = workChain.getAttributionUid();
                removeUidFromLlWatchList(uid);
            }
        }
    }

    private synchronized boolean addLock(WifiLock lock) {
        if (mVerboseLoggingEnabled) {
            Slog.d(TAG, "addLock: " + lock);
        }

        if (findLockByBinder(lock.getBinder()) != null) {
            if (mVerboseLoggingEnabled) {
                Slog.d(TAG, "attempted to add a lock when already holding one");
            }
            return false;
        }

        mWifiLocks.add(lock);

        switch(lock.mMode) {
            case WifiManager.WIFI_MODE_FULL_HIGH_PERF:
                ++mFullHighPerfLocksAcquired;
                // Start blaming this worksource if conditions are met
                if (canActivateHighPerfLock()) {
                    setBlameHiPerfWs(lock.mWorkSource, true);
                }
                break;
            case WifiManager.WIFI_MODE_FULL_LOW_LATENCY:
                addWsToLlWatchList(lock.getWorkSource());
                ++mFullLowLatencyLocksAcquired;
                break;
            default:
                // Do nothing
                break;
        }

        // Recalculate the operating mode
        updateOpMode();

        return true;
    }

    private synchronized WifiLock removeLock(IBinder binder) {
        WifiLock lock = findLockByBinder(binder);
        if (lock != null) {
            mWifiLocks.remove(lock);
            lock.unlinkDeathRecipient();
        }
        return lock;
    }

    private synchronized boolean releaseLock(IBinder binder) {
        WifiLock wifiLock = removeLock(binder);
        if (wifiLock == null) {
            // attempting to release a lock that does not exist.
            return false;
        }

        if (mVerboseLoggingEnabled) {
            Slog.d(TAG, "releaseLock: " + wifiLock);
        }

        switch(wifiLock.mMode) {
            case WifiManager.WIFI_MODE_FULL_HIGH_PERF:
                ++mFullHighPerfLocksReleased;
                mWifiMetrics.addWifiLockAcqSession(WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                        mClock.getElapsedSinceBootMillis() - wifiLock.getAcqTimestamp());
                // Stop blaming only if blaming was set before (conditions are met).
                // This is to avoid calling the api unncessarily, since this API is
                // reference counted in batteryStats and statsd
                if (canActivateHighPerfLock()) {
                    setBlameHiPerfWs(wifiLock.mWorkSource, false);
                }
                break;
            case WifiManager.WIFI_MODE_FULL_LOW_LATENCY:
                removeWsFromLlWatchList(wifiLock.getWorkSource());
                ++mFullLowLatencyLocksReleased;
                mWifiMetrics.addWifiLockAcqSession(WifiManager.WIFI_MODE_FULL_LOW_LATENCY,
                        mClock.getElapsedSinceBootMillis() - wifiLock.getAcqTimestamp());
                break;
            default:
                // Do nothing
                break;
        }

        // Recalculate the operating mode
        updateOpMode();

        return true;
    }

    private synchronized boolean updateOpMode() {
        final int newLockMode = getStrongestLockMode();

        if (newLockMode == mCurrentOpMode) {
            // No action is needed
            return true;
        }

        if (mVerboseLoggingEnabled) {
            Slog.d(TAG, "Current opMode: " + mCurrentOpMode + " New LockMode: " + newLockMode);
        }

        // Otherwise, we need to change current mode, first reset it to normal
        switch (mCurrentOpMode) {
            case WifiManager.WIFI_MODE_FULL_HIGH_PERF:
                if (!mClientModeImpl.setPowerSave(true)) {
                    Slog.e(TAG, "Failed to reset the OpMode from hi-perf to Normal");
                    return false;
                }
                mWifiMetrics.addWifiLockActiveSession(WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                        mClock.getElapsedSinceBootMillis() - mCurrentSessionStartTimeMs);
                break;

            case WifiManager.WIFI_MODE_FULL_LOW_LATENCY:
                if (!setLowLatencyMode(false)) {
                    Slog.e(TAG, "Failed to reset the OpMode from low-latency to Normal");
                    return false;
                }
                mWifiMetrics.addWifiLockActiveSession(WifiManager.WIFI_MODE_FULL_LOW_LATENCY,
                        mClock.getElapsedSinceBootMillis() - mCurrentSessionStartTimeMs);
                break;

            case WifiManager.WIFI_MODE_NO_LOCKS_HELD:
            default:
                // No action
                break;
        }

        // Set the current mode, before we attempt to set the new mode
        mCurrentOpMode = WifiManager.WIFI_MODE_NO_LOCKS_HELD;

        // Now switch to the new opMode
        switch (newLockMode) {
            case WifiManager.WIFI_MODE_FULL_HIGH_PERF:
                if (!mClientModeImpl.setPowerSave(false)) {
                    Slog.e(TAG, "Failed to set the OpMode to hi-perf");
                    return false;
                }
                mCurrentSessionStartTimeMs = mClock.getElapsedSinceBootMillis();
                break;

            case WifiManager.WIFI_MODE_FULL_LOW_LATENCY:
                if (!setLowLatencyMode(true)) {
                    Slog.e(TAG, "Failed to set the OpMode to low-latency");
                    return false;
                }
                mCurrentSessionStartTimeMs = mClock.getElapsedSinceBootMillis();
                break;

            case WifiManager.WIFI_MODE_NO_LOCKS_HELD:
                // No action
                break;

            default:
                // Invalid mode, don't change currentOpMode , and exit with error
                Slog.e(TAG, "Invalid new opMode: " + newLockMode);
                return false;
        }

        // Now set the mode to the new value
        mCurrentOpMode = newLockMode;
        return true;
    }

    private int getLowLatencyModeSupport() {
        if (mLatencyModeSupport == LOW_LATENCY_SUPPORT_UNDEFINED) {
            String ifaceName = mWifiNative.getClientInterfaceName();
            if (ifaceName == null) {
                return LOW_LATENCY_SUPPORT_UNDEFINED;
            }

            long supportedFeatures = mWifiNative.getSupportedFeatureSet(ifaceName);
            if (supportedFeatures != 0) {
                if ((supportedFeatures & WifiManager.WIFI_FEATURE_LOW_LATENCY) != 0) {
                    mLatencyModeSupport = LOW_LATENCY_SUPPORTED;
                } else {
                    mLatencyModeSupport = LOW_LATENCY_NOT_SUPPORTED;
                }
            }
        }

        return mLatencyModeSupport;
    }

    private boolean setLowLatencyMode(boolean enabled) {
        int lowLatencySupport = getLowLatencyModeSupport();

        if (lowLatencySupport == LOW_LATENCY_SUPPORT_UNDEFINED) {
            // Support undefined, no action is taken
            return false;
        }

        if (lowLatencySupport == LOW_LATENCY_SUPPORTED) {
            if (!mClientModeImpl.setLowLatencyMode(enabled)) {
                Slog.e(TAG, "Failed to set low latency mode");
                return false;
            }

            if (!mClientModeImpl.setPowerSave(!enabled)) {
                Slog.e(TAG, "Failed to set power save mode");
                // Revert the low latency mode
                mClientModeImpl.setLowLatencyMode(!enabled);
                return false;
            }
        } else if (lowLatencySupport == LOW_LATENCY_NOT_SUPPORTED) {
            // Only set power save mode
            if (!mClientModeImpl.setPowerSave(!enabled)) {
                Slog.e(TAG, "Failed to set power save mode");
                return false;
            }
        }

        return true;
    }

    private synchronized WifiLock findLockByBinder(IBinder binder) {
        for (WifiLock lock : mWifiLocks) {
            if (lock.getBinder() == binder) {
                return lock;
            }
        }
        return null;
    }

    private int countFgLowLatencyUids() {
        int uidCount = 0;
        int listSize = mLowLatencyUidWatchList.size();
        for (int idx = 0; idx < listSize; idx++) {
            UidRec uidRec = mLowLatencyUidWatchList.valueAt(idx);
            if (uidRec.mIsFg) {
                uidCount++;
            }
        }
        return uidCount;
    }

    private void setBlameHiPerfWs(WorkSource ws, boolean shouldBlame) {
        long ident = Binder.clearCallingIdentity();
        try {
            if (shouldBlame) {
                mBatteryStats.noteFullWifiLockAcquiredFromSource(ws);
                StatsLog.write(StatsLog.WIFI_LOCK_STATE_CHANGED, ws,
                        StatsLog.WIFI_LOCK_STATE_CHANGED__STATE__ON,
                        WifiManager.WIFI_MODE_FULL_HIGH_PERF);
            } else {
                mBatteryStats.noteFullWifiLockReleasedFromSource(ws);
                StatsLog.write(StatsLog.WIFI_LOCK_STATE_CHANGED, ws,
                        StatsLog.WIFI_LOCK_STATE_CHANGED__STATE__OFF,
                        WifiManager.WIFI_MODE_FULL_HIGH_PERF);
            }
        } catch (RemoteException e) {
            // nop
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private void setBlameLowLatencyUid(int uid, boolean shouldBlame) {
        long ident = Binder.clearCallingIdentity();
        try {
            if (shouldBlame) {
                mBatteryStats.noteFullWifiLockAcquired(uid);
                StatsLog.write_non_chained(StatsLog.WIFI_LOCK_STATE_CHANGED, uid, null,
                        StatsLog.WIFI_LOCK_STATE_CHANGED__STATE__ON,
                        WifiManager.WIFI_MODE_FULL_LOW_LATENCY);
            } else {
                mBatteryStats.noteFullWifiLockReleased(uid);
                StatsLog.write_non_chained(StatsLog.WIFI_LOCK_STATE_CHANGED, uid, null,
                        StatsLog.WIFI_LOCK_STATE_CHANGED__STATE__OFF,
                        WifiManager.WIFI_MODE_FULL_LOW_LATENCY);
            }
        } catch (RemoteException e) {
            // nop
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private void setBlameLowLatencyWatchList(boolean shouldBlame) {
        for (int idx = 0; idx < mLowLatencyUidWatchList.size(); idx++) {
            UidRec uidRec = mLowLatencyUidWatchList.valueAt(idx);
            // Affect the blame for only UIDs running in foreground
            // UIDs running in the background are already not blamed,
            // and they should remain in that state.
            if (uidRec.mIsFg) {
                setBlameLowLatencyUid(uidRec.mUid, shouldBlame);
            }
        }
    }

    protected void dump(PrintWriter pw) {
        pw.println("Locks acquired: "
                + mFullHighPerfLocksAcquired + " full high perf, "
                + mFullLowLatencyLocksAcquired + " full low latency");
        pw.println("Locks released: "
                + mFullHighPerfLocksReleased + " full high perf, "
                + mFullLowLatencyLocksReleased + " full low latency");

        pw.println();
        pw.println("Locks held:");
        for (WifiLock lock : mWifiLocks) {
            pw.print("    ");
            pw.println(lock);
        }
    }

    protected void enableVerboseLogging(int verbose) {
        if (verbose > 0) {
            mVerboseLoggingEnabled = true;
        } else {
            mVerboseLoggingEnabled = false;
        }
    }

    private class WifiLock implements IBinder.DeathRecipient {
        String mTag;
        int mUid;
        IBinder mBinder;
        int mMode;
        WorkSource mWorkSource;
        long mAcqTimestamp;

        WifiLock(int lockMode, String tag, IBinder binder, WorkSource ws) {
            mTag = tag;
            mBinder = binder;
            mUid = Binder.getCallingUid();
            mMode = lockMode;
            mWorkSource = ws;
            mAcqTimestamp = mClock.getElapsedSinceBootMillis();
            try {
                mBinder.linkToDeath(this, 0);
            } catch (RemoteException e) {
                binderDied();
            }
        }

        protected WorkSource getWorkSource() {
            return mWorkSource;
        }

        protected int getUid() {
            return mUid;
        }

        protected IBinder getBinder() {
            return mBinder;
        }

        protected long getAcqTimestamp() {
            return mAcqTimestamp;
        }

        public void binderDied() {
            releaseLock(mBinder);
        }

        public void unlinkDeathRecipient() {
            mBinder.unlinkToDeath(this, 0);
        }

        public String toString() {
            return "WifiLock{" + this.mTag + " type=" + this.mMode + " uid=" + mUid
                    + " workSource=" + mWorkSource + "}";
        }
    }

    private class UidRec {
        final int mUid;
        // Count of locks owned or co-owned by this UID
        int mLockCount;
        // Is this UID running in foreground
        boolean mIsFg;

        UidRec(int uid) {
            mUid = uid;
        }
    }
}
