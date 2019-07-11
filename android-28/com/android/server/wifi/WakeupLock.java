/*
 * Copyright 2017 The Android Open Source Project
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

import android.text.format.DateUtils;
import android.util.ArrayMap;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * A lock to determine whether Wifi Wake can re-enable Wifi.
 *
 * <p>Wakeuplock manages a list of networks to determine whether the device's location has changed.
 */
public class WakeupLock {

    private static final String TAG = WakeupLock.class.getSimpleName();

    @VisibleForTesting
    static final int CONSECUTIVE_MISSED_SCANS_REQUIRED_TO_EVICT = 3;
    @VisibleForTesting
    static final long MAX_LOCK_TIME_MILLIS = 10 * DateUtils.MINUTE_IN_MILLIS;

    private final WifiConfigManager mWifiConfigManager;
    private final Map<ScanResultMatchInfo, Integer> mLockedNetworks = new ArrayMap<>();
    private final WifiWakeMetrics mWifiWakeMetrics;
    private final Clock mClock;

    private boolean mVerboseLoggingEnabled;
    private long mLockTimestamp;
    private boolean mIsInitialized;
    private int mNumScans;

    public WakeupLock(WifiConfigManager wifiConfigManager, WifiWakeMetrics wifiWakeMetrics,
                      Clock clock) {
        mWifiConfigManager = wifiConfigManager;
        mWifiWakeMetrics = wifiWakeMetrics;
        mClock = clock;
    }

    /**
     * Sets the WakeupLock with the given {@link ScanResultMatchInfo} list.
     *
     * <p>This saves the wakeup lock to the store and begins the initialization process.
     *
     * @param scanResultList list of ScanResultMatchInfos to start the lock with
     */
    public void setLock(Collection<ScanResultMatchInfo> scanResultList) {
        mLockTimestamp = mClock.getElapsedSinceBootMillis();
        mIsInitialized = false;
        mNumScans = 0;

        mLockedNetworks.clear();
        for (ScanResultMatchInfo scanResultMatchInfo : scanResultList) {
            mLockedNetworks.put(scanResultMatchInfo, CONSECUTIVE_MISSED_SCANS_REQUIRED_TO_EVICT);
        }

        Log.d(TAG, "Lock set. Number of networks: " + mLockedNetworks.size());

        mWifiConfigManager.saveToStore(false /* forceWrite */);
    }

    /**
     * Maybe sets the WakeupLock as initialized based on total scans handled.
     *
     * @param numScans total number of elapsed scans in the current WifiWake session
     */
    private void maybeSetInitializedByScans(int numScans) {
        if (mIsInitialized) {
            return;
        }
        boolean shouldBeInitialized = numScans >= CONSECUTIVE_MISSED_SCANS_REQUIRED_TO_EVICT;
        if (shouldBeInitialized) {
            mIsInitialized = true;

            Log.d(TAG, "Lock initialized by handled scans. Scans: " + numScans);
            if (mVerboseLoggingEnabled) {
                Log.d(TAG, "State of lock: " + mLockedNetworks);
            }

            // log initialize event
            mWifiWakeMetrics.recordInitializeEvent(mNumScans, mLockedNetworks.size());
        }
    }

    /**
     * Maybe sets the WakeupLock as initialized based on elapsed time.
     *
     * @param timestampMillis current timestamp
     */
    private void maybeSetInitializedByTimeout(long timestampMillis) {
        if (mIsInitialized) {
            return;
        }
        long elapsedTime = timestampMillis - mLockTimestamp;
        boolean shouldBeInitialized = elapsedTime > MAX_LOCK_TIME_MILLIS;

        if (shouldBeInitialized) {
            mIsInitialized = true;

            Log.d(TAG, "Lock initialized by timeout. Elapsed time: " + elapsedTime);
            if (mNumScans == 0) {
                Log.w(TAG, "Lock initialized with 0 handled scans!");
            }
            if (mVerboseLoggingEnabled) {
                Log.d(TAG, "State of lock: " + mLockedNetworks);
            }

            // log initialize event
            mWifiWakeMetrics.recordInitializeEvent(mNumScans, mLockedNetworks.size());
        }
    }

    /** Returns whether the lock has been fully initialized. */
    public boolean isInitialized() {
        return mIsInitialized;
    }

    /**
     * Adds the given networks to the lock.
     *
     * <p>This is called during the initialization step.
     *
     * @param networkList The list of networks to be added
     */
    private void addToLock(Collection<ScanResultMatchInfo> networkList) {
        if (mVerboseLoggingEnabled) {
            Log.d(TAG, "Initializing lock with networks: " + networkList);
        }

        boolean hasChanged = false;

        for (ScanResultMatchInfo network : networkList) {
            if (!mLockedNetworks.containsKey(network)) {
                mLockedNetworks.put(network, CONSECUTIVE_MISSED_SCANS_REQUIRED_TO_EVICT);
                hasChanged = true;
            }
        }

        if (hasChanged) {
            mWifiConfigManager.saveToStore(false /* forceWrite */);
        }

        // Set initialized if the lock has handled enough scans, and log the event
        maybeSetInitializedByScans(mNumScans);
    }

    /**
     * Removes networks from the lock if not present in the given {@link ScanResultMatchInfo} list.
     *
     * <p>If a network in the lock is not present in the list, reduce the number of scans
     * required to evict by one. Remove any entries in the list with 0 scans required to evict. If
     * any entries in the lock are removed, the store is updated.
     *
     * @param networkList list of present ScanResultMatchInfos to update the lock with
     */
    private void removeFromLock(Collection<ScanResultMatchInfo> networkList) {
        if (mVerboseLoggingEnabled) {
            Log.d(TAG, "Filtering lock with networks: " + networkList);
        }

        boolean hasChanged = false;
        Iterator<Map.Entry<ScanResultMatchInfo, Integer>> it =
                mLockedNetworks.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<ScanResultMatchInfo, Integer> entry = it.next();

            // if present in scan list, reset to max
            if (networkList.contains(entry.getKey())) {
                if (mVerboseLoggingEnabled) {
                    Log.d(TAG, "Found network in lock: " + entry.getKey().networkSsid);
                }
                entry.setValue(CONSECUTIVE_MISSED_SCANS_REQUIRED_TO_EVICT);
                continue;
            }

            // decrement and remove if necessary
            entry.setValue(entry.getValue() - 1);
            if (entry.getValue() <= 0) {
                Log.d(TAG, "Removed network from lock: " + entry.getKey().networkSsid);
                it.remove();
                hasChanged = true;
            }
        }

        if (hasChanged) {
            mWifiConfigManager.saveToStore(false /* forceWrite */);
        }

        if (isUnlocked()) {
            Log.d(TAG, "Lock emptied. Recording unlock event.");
            mWifiWakeMetrics.recordUnlockEvent(mNumScans);
        }
    }

    /**
     * Updates the lock with the given {@link ScanResultMatchInfo} list.
     *
     * <p>Based on the current initialization state of the lock, either adds or removes networks
     * from the lock.
     *
     * <p>The lock is initialized after {@link #CONSECUTIVE_MISSED_SCANS_REQUIRED_TO_EVICT}
     * scans have been handled, or after {@link #MAX_LOCK_TIME_MILLIS} milliseconds have elapsed
     * since {@link #setLock(Collection)}.
     *
     * @param networkList list of present ScanResultMatchInfos to update the lock with
     */
    public void update(Collection<ScanResultMatchInfo> networkList) {
        // update is no-op if already unlocked
        if (isUnlocked()) {
            return;
        }
        // Before checking handling the scan, we check to see whether we've exceeded the maximum
        // time allowed for initialization. If so, we set initialized and treat this scan as a
        // "removeFromLock()" instead of an "addToLock()".
        maybeSetInitializedByTimeout(mClock.getElapsedSinceBootMillis());

        mNumScans++;

        // add or remove networks based on initialized status
        if (mIsInitialized) {
            removeFromLock(networkList);
        } else {
            addToLock(networkList);
        }
    }

    /** Returns whether the WakeupLock is unlocked */
    public boolean isUnlocked() {
        return mIsInitialized && mLockedNetworks.isEmpty();
    }

    /** Returns the data source for the WakeupLock config store data. */
    public WakeupConfigStoreData.DataSource<Set<ScanResultMatchInfo>> getDataSource() {
        return new WakeupLockDataSource();
    }

    /** Dumps wakeup lock contents. */
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("WakeupLock: ");
        pw.println("mNumScans: " + mNumScans);
        pw.println("mIsInitialized: " + mIsInitialized);
        pw.println("Locked networks: " + mLockedNetworks.size());
        for (Map.Entry<ScanResultMatchInfo, Integer> entry : mLockedNetworks.entrySet()) {
            pw.println(entry.getKey() + ", scans to evict: " + entry.getValue());
        }
    }

    /** Set whether verbose logging is enabled. */
    public void enableVerboseLogging(boolean enabled) {
        mVerboseLoggingEnabled = enabled;
    }

    private class WakeupLockDataSource
            implements WakeupConfigStoreData.DataSource<Set<ScanResultMatchInfo>> {

        @Override
        public Set<ScanResultMatchInfo> getData() {
            return mLockedNetworks.keySet();
        }

        @Override
        public void setData(Set<ScanResultMatchInfo> data) {
            mLockedNetworks.clear();
            for (ScanResultMatchInfo network : data) {
                mLockedNetworks.put(network, CONSECUTIVE_MISSED_SCANS_REQUIRED_TO_EVICT);
            }
            // lock is considered initialized if loaded from store
            mIsInitialized = true;
        }
    }
}
