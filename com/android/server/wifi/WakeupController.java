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

import android.content.Context;
import android.database.ContentObserver;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiNetworkSuggestion;
import android.net.wifi.WifiScanner;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.Process;
import android.provider.Settings;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;


/**
 * WakeupController is responsible managing Auto Wifi.
 *
 * <p>It determines if and when to re-enable wifi after it has been turned off by the user.
 */
public class WakeupController {

    private static final String TAG = "WakeupController";

    private static final boolean USE_PLATFORM_WIFI_WAKE = true;

    private final Context mContext;
    private final Handler mHandler;
    private final FrameworkFacade mFrameworkFacade;
    private final ContentObserver mContentObserver;
    private final WakeupLock mWakeupLock;
    private final WakeupEvaluator mWakeupEvaluator;
    private final WakeupOnboarding mWakeupOnboarding;
    private final WifiConfigManager mWifiConfigManager;
    private final WifiNetworkSuggestionsManager mWifiNetworkSuggestionsManager;
    private final WifiInjector mWifiInjector;
    private final WakeupConfigStoreData mWakeupConfigStoreData;
    private final WifiWakeMetrics mWifiWakeMetrics;
    private final Clock mClock;

    private final WifiScanner.ScanListener mScanListener = new WifiScanner.ScanListener() {
        @Override
        public void onPeriodChanged(int periodInMs) {
            // no-op
        }

        @Override
        public void onResults(WifiScanner.ScanData[] results) {
            // We treat any full band scans (with DFS or not) as "full".
            if (results.length == 1
                    && WifiScanner.isFullBandScan(results[0].getBandScanned(), true)) {
                handleScanResults(filterDfsScanResults(Arrays.asList(results[0].getResults())));
            }
        }

        @Override
        public void onFullResult(ScanResult fullScanResult) {
            // no-op
        }

        @Override
        public void onSuccess() {
            // no-op
        }

        @Override
        public void onFailure(int reason, String description) {
            Log.e(TAG, "ScanListener onFailure: " + reason + ": " + description);
        }
    };

    /** Whether this feature is enabled in Settings. */
    private boolean mWifiWakeupEnabled;

    /** Whether the WakeupController is currently active. */
    private boolean mIsActive = false;

    /** The number of scans that have been handled by the controller since last {@link #reset()}. */
    private int mNumScansHandled = 0;

    /** Whether Wifi verbose logging is enabled. */
    private boolean mVerboseLoggingEnabled;

    /**
     * The timestamp of when the Wifi network was last disconnected (either device disconnected
     * from the network or Wifi was turned off entirely).
     * Note: mLastDisconnectTimestampMillis and mLastDisconnectInfo must always be updated together.
     */
    private long mLastDisconnectTimestampMillis;

    /**
     * The SSID of the last Wifi network the device was connected to (either device disconnected
     * from the network or Wifi was turned off entirely).
     * Note: mLastDisconnectTimestampMillis and mLastDisconnectInfo must always be updated together.
     */
    private ScanResultMatchInfo mLastDisconnectInfo;

    public WakeupController(
            Context context,
            Handler handler,
            WakeupLock wakeupLock,
            WakeupEvaluator wakeupEvaluator,
            WakeupOnboarding wakeupOnboarding,
            WifiConfigManager wifiConfigManager,
            WifiConfigStore wifiConfigStore,
            WifiNetworkSuggestionsManager wifiNetworkSuggestionsManager,
            WifiWakeMetrics wifiWakeMetrics,
            WifiInjector wifiInjector,
            FrameworkFacade frameworkFacade,
            Clock clock) {
        mContext = context;
        mHandler = handler;
        mWakeupLock = wakeupLock;
        mWakeupEvaluator = wakeupEvaluator;
        mWakeupOnboarding = wakeupOnboarding;
        mWifiConfigManager = wifiConfigManager;
        mWifiNetworkSuggestionsManager = wifiNetworkSuggestionsManager;
        mWifiWakeMetrics = wifiWakeMetrics;
        mFrameworkFacade = frameworkFacade;
        mWifiInjector = wifiInjector;
        mContentObserver = new ContentObserver(mHandler) {
            @Override
            public void onChange(boolean selfChange) {
                readWifiWakeupEnabledFromSettings();
                mWakeupOnboarding.setOnboarded();
            }
        };
        mFrameworkFacade.registerContentObserver(mContext, Settings.Global.getUriFor(
                Settings.Global.WIFI_WAKEUP_ENABLED), true, mContentObserver);
        readWifiWakeupEnabledFromSettings();

        // registering the store data here has the effect of reading the persisted value of the
        // data sources after system boot finishes
        mWakeupConfigStoreData = new WakeupConfigStoreData(
                new IsActiveDataSource(),
                mWakeupOnboarding.getIsOnboadedDataSource(),
                mWakeupOnboarding.getNotificationsDataSource(),
                mWakeupLock.getDataSource());
        wifiConfigStore.registerStoreData(mWakeupConfigStoreData);
        mClock = clock;
        mLastDisconnectTimestampMillis = 0;
        mLastDisconnectInfo = null;
    }

    private void readWifiWakeupEnabledFromSettings() {
        mWifiWakeupEnabled = mFrameworkFacade.getIntegerSetting(
                mContext, Settings.Global.WIFI_WAKEUP_ENABLED, 0) == 1;
        Log.d(TAG, "WifiWake " + (mWifiWakeupEnabled ? "enabled" : "disabled"));
    }

    private void setActive(boolean isActive) {
        if (mIsActive != isActive) {
            Log.d(TAG, "Setting active to " + isActive);
            mIsActive = isActive;
            mWifiConfigManager.saveToStore(false /* forceWrite */);
        }
    }

    /**
     * Enable/Disable the feature.
     */
    public void setEnabled(boolean enable) {
        mFrameworkFacade.setIntegerSetting(
                mContext, Settings.Global.WIFI_WAKEUP_ENABLED, enable ? 1 : 0);
    }

    /**
     * Whether the feature is currently enabled.
     */
    public boolean isEnabled() {
        return mWifiWakeupEnabled;
    }

    /**
     * Saves the SSID of the last Wifi network that was disconnected. Should only be called before
     * WakeupController is active.
     */
    public void setLastDisconnectInfo(ScanResultMatchInfo scanResultMatchInfo) {
        if (mIsActive) {
            Log.e(TAG, "Unexpected setLastDisconnectInfo when WakeupController is active!");
            return;
        }
        if (scanResultMatchInfo == null) {
            Log.e(TAG, "Unexpected setLastDisconnectInfo(null)");
            return;
        }
        mLastDisconnectTimestampMillis = mClock.getElapsedSinceBootMillis();
        mLastDisconnectInfo = scanResultMatchInfo;
        if (mVerboseLoggingEnabled) {
            Log.d(TAG, "mLastDisconnectInfo set to " + scanResultMatchInfo);
        }
    }

    /**
     * If Wifi was disabled within LAST_DISCONNECT_TIMEOUT_MILLIS of losing a Wifi connection,
     * add that Wifi connection to the Wakeup Lock as if Wifi was disabled while connected to that
     * connection.
     * Often times, networks with poor signal intermittently connect and disconnect, causing the
     * user to manually turn off Wifi. If the Wifi was turned off during the disconnected phase of
     * the intermittent connection, then that connection normally would not be added to the Wakeup
     * Lock. This constant defines the timeout after disconnecting, in milliseconds, within which
     * if Wifi was disabled, the network would still be added to the wakeup lock.
     */
    @VisibleForTesting
    static final long LAST_DISCONNECT_TIMEOUT_MILLIS = 5 * 1000;

    /**
     * Starts listening for incoming scans.
     *
     * <p>Should only be called upon entering ScanMode. WakeupController registers its listener with
     * the WifiScanner. If the WakeupController is already active, then it returns early. Otherwise
     * it performs its initialization steps and sets {@link #mIsActive} to true.
     */
    public void start() {
        Log.d(TAG, "start()");
        if (getGoodSavedNetworksAndSuggestions().isEmpty()) {
            Log.i(TAG, "Ignore wakeup start since there are no good networks.");
            return;
        }
        mWifiInjector.getWifiScanner().registerScanListener(
                new HandlerExecutor(mHandler), mScanListener);

        // If already active, we don't want to restart the session, so return early.
        if (mIsActive) {
            mWifiWakeMetrics.recordIgnoredStart();
            return;
        }
        setActive(true);

        // ensure feature is enabled and store data has been read before performing work
        if (isEnabledAndReady()) {
            mWakeupOnboarding.maybeShowNotification();

            List<ScanResult> scanResults =
                    filterDfsScanResults(mWifiInjector.getWifiScanner().getSingleScanResults());
            Set<ScanResultMatchInfo> matchInfos = toMatchInfos(scanResults);
            matchInfos.retainAll(getGoodSavedNetworksAndSuggestions());

            // ensure that the last disconnected network is added to the wakeup lock, since we don't
            // want to automatically reconnect to the same network that the user manually
            // disconnected from
            long now = mClock.getElapsedSinceBootMillis();
            if (mLastDisconnectInfo != null && ((now - mLastDisconnectTimestampMillis)
                    <= LAST_DISCONNECT_TIMEOUT_MILLIS)) {
                matchInfos.add(mLastDisconnectInfo);
                if (mVerboseLoggingEnabled) {
                    Log.d(TAG, "Added last connected network to lock: " + mLastDisconnectInfo);
                }
            }

            if (mVerboseLoggingEnabled) {
                Log.d(TAG, "Saved networks in most recent scan:" + matchInfos);
            }

            mWifiWakeMetrics.recordStartEvent(matchInfos.size());
            mWakeupLock.setLock(matchInfos);
            // TODO(b/77291248): request low latency scan here
        }
    }

    /**
     * Stops listening for scans.
     *
     * <p>Should only be called upon leaving ScanMode. It deregisters the listener from
     * WifiScanner.
     */
    public void stop() {
        Log.d(TAG, "stop()");
        mLastDisconnectTimestampMillis = 0;
        mLastDisconnectInfo = null;
        mWifiInjector.getWifiScanner().unregisterScanListener(mScanListener);
        mWakeupOnboarding.onStop();
    }

    /** Resets the WakeupController, setting {@link #mIsActive} to false. */
    public void reset() {
        Log.d(TAG, "reset()");
        mWifiWakeMetrics.recordResetEvent(mNumScansHandled);
        mNumScansHandled = 0;
        setActive(false);
    }

    /** Sets verbose logging flag based on verbose level. */
    public void enableVerboseLogging(int verbose) {
        mVerboseLoggingEnabled = verbose > 0;
        mWakeupLock.enableVerboseLogging(mVerboseLoggingEnabled);
    }

    /** Returns a list of ScanResults with DFS channels removed. */
    private List<ScanResult> filterDfsScanResults(Collection<ScanResult> scanResults) {
        int[] dfsChannels = mWifiInjector.getWifiNative()
                .getChannelsForBand(WifiScanner.WIFI_BAND_5_GHZ_DFS_ONLY);
        if (dfsChannels == null) {
            dfsChannels = new int[0];
        }

        final Set<Integer> dfsChannelSet = Arrays.stream(dfsChannels).boxed()
                .collect(Collectors.toSet());

        return scanResults.stream()
                .filter(scanResult -> !dfsChannelSet.contains(scanResult.frequency))
                .collect(Collectors.toList());
    }

    /** Returns a filtered set of saved networks from WifiConfigManager & suggestions
     * from WifiNetworkSuggestionsManager. */
    private Set<ScanResultMatchInfo> getGoodSavedNetworksAndSuggestions() {
        List<WifiConfiguration> savedNetworks = mWifiConfigManager.getSavedNetworks(
                Process.WIFI_UID);

        Set<ScanResultMatchInfo> goodNetworks = new HashSet<>(savedNetworks.size());
        for (WifiConfiguration config : savedNetworks) {
            if (isWideAreaNetwork(config)
                    || config.hasNoInternetAccess()
                    || config.noInternetAccessExpected
                    || !config.getNetworkSelectionStatus().hasEverConnected()) {
                continue;
            }
            goodNetworks.add(ScanResultMatchInfo.fromWifiConfiguration(config));
        }

        Set<WifiNetworkSuggestion> networkSuggestions =
                mWifiNetworkSuggestionsManager.getAllApprovedNetworkSuggestions();
        for (WifiNetworkSuggestion suggestion : networkSuggestions) {
            // TODO(b/127799111): Do we need to filter the list similar to saved networks above?
            goodNetworks.add(
                    ScanResultMatchInfo.fromWifiConfiguration(suggestion.wifiConfiguration));
        }
        return goodNetworks;
    }

    //TODO(b/69271702) implement WAN filtering
    private static boolean isWideAreaNetwork(WifiConfiguration config) {
        return false;
    }

    /**
     * Handles incoming scan results.
     *
     * <p>The controller updates the WakeupLock with the incoming scan results. If WakeupLock is not
     * yet fully initialized, it adds the current scanResults to the lock and returns. If WakeupLock
     * is initialized but not empty, the controller updates the lock with the current scan. If it is
     * both initialized and empty, it evaluates scan results for a match with saved networks. If a
     * match exists, it enables wifi.
     *
     * <p>The feature must be enabled and the store data must be loaded in order for the controller
     * to handle scan results.
     *
     * @param scanResults The scan results with which to update the controller
     */
    private void handleScanResults(Collection<ScanResult> scanResults) {
        if (!isEnabledAndReady()) {
            Log.d(TAG, "Attempted to handleScanResults while not enabled");
            return;
        }

        // only count scan as handled if isEnabledAndReady
        mNumScansHandled++;
        if (mVerboseLoggingEnabled) {
            Log.d(TAG, "Incoming scan #" + mNumScansHandled);
        }

        // need to show notification here in case user turns phone on while wifi is off
        mWakeupOnboarding.maybeShowNotification();

        // filter out unknown networks
        Set<ScanResultMatchInfo> goodNetworks = getGoodSavedNetworksAndSuggestions();
        Set<ScanResultMatchInfo> matchInfos = toMatchInfos(scanResults);
        matchInfos.retainAll(goodNetworks);

        mWakeupLock.update(matchInfos);
        if (!mWakeupLock.isUnlocked()) {
            return;
        }

        ScanResult network = mWakeupEvaluator.findViableNetwork(scanResults, goodNetworks);

        if (network != null) {
            Log.d(TAG, "Enabling wifi for network: " + network.SSID);
            enableWifi();
        }
    }

    /**
     * Converts ScanResults to ScanResultMatchInfos.
     */
    private static Set<ScanResultMatchInfo> toMatchInfos(Collection<ScanResult> scanResults) {
        return scanResults.stream()
                .map(ScanResultMatchInfo::fromScanResult)
                .collect(Collectors.toSet());
    }

    /**
     * Enables wifi.
     *
     * <p>This method ignores all checks and assumes that {@link ActiveModeWarden} is currently
     * in ScanModeState.
     */
    private void enableWifi() {
        if (USE_PLATFORM_WIFI_WAKE) {
            // TODO(b/72180295): ensure that there is no race condition with WifiServiceImpl here
            if (mWifiInjector.getWifiSettingsStore().handleWifiToggled(true /* wifiEnabled */)) {
                mWifiInjector.getActiveModeWarden().wifiToggled();
                mWifiWakeMetrics.recordWakeupEvent(mNumScansHandled);
            }
        }
    }

    /**
     * Whether the feature is currently enabled.
     *
     * <p>This method checks both the Settings value and the store data to ensure that it has been
     * read.
     */
    @VisibleForTesting
    boolean isEnabledAndReady() {
        return mWifiWakeupEnabled && mWakeupConfigStoreData.hasBeenRead();
    }

    /** Dumps wakeup controller state. */
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("Dump of WakeupController");
        pw.println("USE_PLATFORM_WIFI_WAKE: " + USE_PLATFORM_WIFI_WAKE);
        pw.println("mWifiWakeupEnabled: " + mWifiWakeupEnabled);
        pw.println("isOnboarded: " + mWakeupOnboarding.isOnboarded());
        pw.println("configStore hasBeenRead: " + mWakeupConfigStoreData.hasBeenRead());
        pw.println("mIsActive: " + mIsActive);
        pw.println("mNumScansHandled: " + mNumScansHandled);

        mWakeupLock.dump(fd, pw, args);
    }

    private class IsActiveDataSource implements WakeupConfigStoreData.DataSource<Boolean> {

        @Override
        public Boolean getData() {
            return mIsActive;
        }

        @Override
        public void setData(Boolean data) {
            mIsActive = data;
        }
    }
}
