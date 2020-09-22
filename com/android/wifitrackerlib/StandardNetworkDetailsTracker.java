/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.wifitrackerlib;

import static androidx.core.util.Preconditions.checkNotNull;

import static com.android.wifitrackerlib.NetworkRequestEntry.wifiConfigToNetworkRequestEntryKey;
import static com.android.wifitrackerlib.StandardWifiEntry.wifiConfigToStandardWifiEntryKey;
import static com.android.wifitrackerlib.Utils.getSecurityTypesFromScanResult;
import static com.android.wifitrackerlib.WifiEntry.CONNECTED_STATE_CONNECTED;

import static java.util.stream.Collectors.toList;

import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.NetworkScoreManager;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.text.TextUtils;

import androidx.annotation.AnyThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;
import androidx.lifecycle.Lifecycle;

import java.time.Clock;
import java.util.Collections;

/**
 * Implementation of NetworkDetailsTracker that tracks a single StandardWifiEntry.
 */
class StandardNetworkDetailsTracker extends NetworkDetailsTracker {
    private static final String TAG = "StandardNetworkDetailsTracker";

    private final StandardWifiEntry mChosenEntry;
    private final boolean mIsNetworkRequest;
    private NetworkInfo mCurrentNetworkInfo;

    StandardNetworkDetailsTracker(@NonNull Lifecycle lifecycle,
            @NonNull Context context,
            @NonNull WifiManager wifiManager,
            @NonNull ConnectivityManager connectivityManager,
            @NonNull NetworkScoreManager networkScoreManager,
            @NonNull Handler mainHandler,
            @NonNull Handler workerHandler,
            @NonNull Clock clock,
            long maxScanAgeMillis,
            long scanIntervalMillis,
            String key) {
        super(lifecycle, context, wifiManager, connectivityManager, networkScoreManager,
                mainHandler, workerHandler, clock, maxScanAgeMillis, scanIntervalMillis, TAG);

        if (key.startsWith(NetworkRequestEntry.KEY_PREFIX)) {
            mIsNetworkRequest = true;
            mChosenEntry = new NetworkRequestEntry(mContext, mMainHandler, key, mWifiManager,
                    mWifiNetworkScoreCache, false /* forSavedNetworksPage */);
        } else {
            mIsNetworkRequest = false;
            mChosenEntry = new StandardWifiEntry(mContext, mMainHandler, key, mWifiManager,
                    mWifiNetworkScoreCache, false /* forSavedNetworksPage */);
        }
        cacheNewScanResults();
        conditionallyUpdateScanResults(true /* lastScanSucceeded */);
        conditionallyUpdateConfig();
        final WifiInfo wifiInfo = mWifiManager.getConnectionInfo();
        final Network currentNetwork = mWifiManager.getCurrentNetwork();
        mCurrentNetworkInfo = mConnectivityManager.getNetworkInfo(currentNetwork);
        mChosenEntry.updateConnectionInfo(wifiInfo, mCurrentNetworkInfo);
        handleLinkPropertiesChanged(mConnectivityManager.getLinkProperties(currentNetwork));
    }

    @AnyThread
    @Override
    @NonNull
    public WifiEntry getWifiEntry() {
        return mChosenEntry;
    }

    @WorkerThread
    @Override
    protected void handleWifiStateChangedAction() {
        conditionallyUpdateScanResults(true /* lastScanSucceeded */);
    }

    @WorkerThread
    @Override
    protected void handleScanResultsAvailableAction(@NonNull Intent intent) {
        checkNotNull(intent, "Intent cannot be null!");
        conditionallyUpdateScanResults(
                intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, true));
    }

    @WorkerThread
    @Override
    protected void handleConfiguredNetworksChangedAction(@NonNull Intent intent) {
        checkNotNull(intent, "Intent cannot be null!");
        final WifiConfiguration updatedConfig =
                (WifiConfiguration) intent.getExtra(WifiManager.EXTRA_WIFI_CONFIGURATION);
        if (updatedConfig != null && configMatches(updatedConfig)) {
            final int changeReason = intent.getIntExtra(WifiManager.EXTRA_CHANGE_REASON,
                    -1 /* defaultValue*/);
            if (changeReason == WifiManager.CHANGE_REASON_ADDED
                    || changeReason == WifiManager.CHANGE_REASON_CONFIG_CHANGE) {
                mChosenEntry.updateConfig(updatedConfig);
            } else if (changeReason == WifiManager.CHANGE_REASON_REMOVED) {
                mChosenEntry.updateConfig(null);
            }
        } else {
            conditionallyUpdateConfig();
        }
    }

    @WorkerThread
    @Override
    protected void handleNetworkStateChangedAction(@NonNull Intent intent) {
        checkNotNull(intent, "Intent cannot be null!");
        mCurrentNetworkInfo = (NetworkInfo) intent.getExtra(WifiManager.EXTRA_NETWORK_INFO);
        mChosenEntry.updateConnectionInfo(mWifiManager.getConnectionInfo(), mCurrentNetworkInfo);
    }

    @WorkerThread
    @Override
    protected void handleRssiChangedAction() {
        mChosenEntry.updateConnectionInfo(mWifiManager.getConnectionInfo(), mCurrentNetworkInfo);
    }

    @WorkerThread
    @Override
    protected void handleLinkPropertiesChanged(@Nullable LinkProperties linkProperties) {
        if (mChosenEntry.getConnectedState() == CONNECTED_STATE_CONNECTED) {
            mChosenEntry.updateLinkProperties(linkProperties);
        }
    }

    @WorkerThread
    @Override
    protected void handleNetworkCapabilitiesChanged(@Nullable NetworkCapabilities capabilities) {
        if (mChosenEntry.getConnectedState() == CONNECTED_STATE_CONNECTED) {
            mChosenEntry.updateNetworkCapabilities(capabilities);
        }
    }

    @WorkerThread
    @Override
    protected void handleNetworkScoreCacheUpdated() {
        mChosenEntry.onScoreCacheUpdated();
    }

    /**
     * Updates the tracked entry's scan results up to the max scan age (or more, if the last scan
     * was unsuccessful). If Wifi is disabled, the tracked entry's level will be cleared.
     */
    private void conditionallyUpdateScanResults(boolean lastScanSucceeded) {
        if (mWifiManager.getWifiState() == WifiManager.WIFI_STATE_DISABLED) {
            mChosenEntry.updateScanResultInfo(Collections.emptyList());
            return;
        }

        long scanAgeWindow = mMaxScanAgeMillis;
        if (lastScanSucceeded) {
            cacheNewScanResults();
        } else {
            // Scan failed, increase scan age window to prevent WifiEntry list from
            // clearing prematurely.
            scanAgeWindow += mScanIntervalMillis;
        }
        mChosenEntry.updateScanResultInfo(mScanResultUpdater.getScanResults(scanAgeWindow));
    }

    /**
     * Updates the tracked entry's WifiConfiguration from getPrivilegedConfiguredNetworks(), or sets
     * it to null if it does not exist.
     */
    private void conditionallyUpdateConfig() {
        WifiConfiguration updatedConfig = mWifiManager.getPrivilegedConfiguredNetworks().stream()
                .filter(this::configMatches)
                .findAny().orElse(null);
        mChosenEntry.updateConfig(updatedConfig);
    }

    /**
     * Updates ScanResultUpdater with new ScanResults matching mChosenEntry.
     */
    private void cacheNewScanResults() {
        mScanResultUpdater.update(mWifiManager.getScanResults().stream()
                .filter(scan -> TextUtils.equals(scan.SSID, mChosenEntry.getSsid())
                        && getSecurityTypesFromScanResult(scan).contains(
                                mChosenEntry.getSecurity()))
                .collect(toList()));
    }

    private boolean configMatches(@NonNull WifiConfiguration config) {
        if (config.isPasspoint()) {
            return false;
        }
        String configKey = config.fromWifiNetworkSpecifier
                ? wifiConfigToNetworkRequestEntryKey(config)
                : wifiConfigToStandardWifiEntryKey(config);
        return TextUtils.equals(configKey, mChosenEntry.getKey());
    }
}
