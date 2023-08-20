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

import static com.android.wifitrackerlib.WifiEntry.CONNECTED_STATE_CONNECTED;

import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityDiagnosticsManager;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;

import androidx.annotation.AnyThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.annotation.WorkerThread;
import androidx.lifecycle.Lifecycle;

import java.time.Clock;

/**
 * Wi-Fi tracker that maintains a single WifiEntry corresponding to a given WifiEntry key.
 */
public abstract class NetworkDetailsTracker extends BaseWifiTracker {
    /**
     * Creates a concrete implementation of a NetworkDetailsTracker depending on the type of
     * WifiEntry being tracked.
     *
     * @param lifecycle           Lifecycle this is tied to for lifecycle callbacks.
     * @param context             Context for registering broadcast receiver and for resource
     *                            strings.
     * @param wifiManager         Provides all Wi-Fi info.
     * @param connectivityManager Provides network info.
     * @param mainHandler         Handler for processing listener callbacks.
     * @param workerHandler       Handler for processing all broadcasts and running the Scanner.
     * @param clock               Clock used for evaluating the age of scans
     * @param maxScanAgeMillis    Max age for tracked WifiEntries.
     * @param scanIntervalMillis  Interval between initiating scans.
     * @param key                 Key of the WifiEntry to be tracked.
     * @return
     */
    public static NetworkDetailsTracker createNetworkDetailsTracker(@NonNull Lifecycle lifecycle,
            @NonNull Context context,
            @NonNull WifiManager wifiManager,
            @NonNull ConnectivityManager connectivityManager,
            @NonNull Handler mainHandler,
            @NonNull Handler workerHandler,
            @NonNull Clock clock,
            long maxScanAgeMillis,
            long scanIntervalMillis,
            String key) {
        return createNetworkDetailsTracker(
                new WifiTrackerInjector(context),
                lifecycle,
                context,
                wifiManager,
                connectivityManager,
                mainHandler,
                workerHandler,
                clock,
                maxScanAgeMillis,
                scanIntervalMillis,
                key);
    }

    @VisibleForTesting
    static NetworkDetailsTracker createNetworkDetailsTracker(
            @NonNull WifiTrackerInjector injector,
            @NonNull Lifecycle lifecycle,
            @NonNull Context context,
            @NonNull WifiManager wifiManager,
            @NonNull ConnectivityManager connectivityManager,
            @NonNull Handler mainHandler,
            @NonNull Handler workerHandler,
            @NonNull Clock clock,
            long maxScanAgeMillis,
            long scanIntervalMillis,
            String key) {
        if (key.startsWith(StandardWifiEntry.KEY_PREFIX)) {
            return new StandardNetworkDetailsTracker(injector, lifecycle, context, wifiManager,
                    connectivityManager, mainHandler, workerHandler, clock,
                    maxScanAgeMillis, scanIntervalMillis, key);
        } else if (key.startsWith(PasspointWifiEntry.KEY_PREFIX)) {
            return new PasspointNetworkDetailsTracker(injector, lifecycle, context, wifiManager,
                    connectivityManager, mainHandler, workerHandler, clock,
                    maxScanAgeMillis, scanIntervalMillis, key);
        } else if (key.startsWith(HotspotNetworkEntry.KEY_PREFIX)) {
            return new HotspotNetworkDetailsTracker(injector, lifecycle, context, wifiManager,
                    connectivityManager, mainHandler, workerHandler, clock,
                    maxScanAgeMillis, scanIntervalMillis, key);
        } else {
            throw new IllegalArgumentException("Key does not contain valid key prefix!");
        }
    }

    /**
     * Abstract constructor for NetworkDetailsTracker.
     * Clients must use {@link NetworkDetailsTracker#createNetworkDetailsTracker} for creating
     * an appropriate concrete instance of this class.
     */
    NetworkDetailsTracker(
            @NonNull WifiTrackerInjector injector,
            @NonNull Lifecycle lifecycle,
            @NonNull Context context,
            @NonNull WifiManager wifiManager,
            @NonNull ConnectivityManager connectivityManager,
            @NonNull Handler mainHandler,
            @NonNull Handler workerHandler,
            @NonNull Clock clock,
            long maxScanAgeMillis,
            long scanIntervalMillis,
            String tag) {
        super(injector, lifecycle, context, wifiManager, connectivityManager,
                mainHandler, workerHandler, clock, maxScanAgeMillis, scanIntervalMillis,
                null /* listener */, tag);
    }

    @WorkerThread
    @Override
    protected void handleNetworkStateChangedAction(@NonNull Intent intent) {
        WifiInfo primaryWifiInfo = mWifiManager.getConnectionInfo();
        NetworkInfo networkInfo = intent.getParcelableExtra(WifiManager.EXTRA_NETWORK_INFO);
        if (primaryWifiInfo == null || networkInfo == null) {
            return;
        }
        getWifiEntry().onPrimaryWifiInfoChanged(primaryWifiInfo, networkInfo);
    }

    @WorkerThread
    @Override
    protected void handleLinkPropertiesChanged(
            @NonNull Network network, @Nullable LinkProperties linkProperties) {
        final WifiEntry chosenEntry = getWifiEntry();
        if (chosenEntry.getConnectedState() == CONNECTED_STATE_CONNECTED) {
            chosenEntry.updateLinkProperties(network, linkProperties);
        }
    }

    @WorkerThread
    @Override
    protected void handleNetworkCapabilitiesChanged(
            @NonNull Network network, @NonNull NetworkCapabilities capabilities) {
        getWifiEntry().onNetworkCapabilitiesChanged(network, capabilities);
    }

    @WorkerThread
    @Override
    protected void handleNetworkLost(@NonNull Network network) {
        getWifiEntry().onNetworkLost(network);
    }

    @WorkerThread
    @Override
    protected void handleDefaultNetworkCapabilitiesChanged(
            @NonNull Network network, @NonNull NetworkCapabilities capabilities) {
        getWifiEntry().onDefaultNetworkCapabilitiesChanged(network, capabilities);
    }

    @WorkerThread
    @Override
    protected void handleDefaultNetworkLost() {
        getWifiEntry().onDefaultNetworkLost();
    }

    @WorkerThread
    @Override
    protected void handleConnectivityReportAvailable(
            @NonNull ConnectivityDiagnosticsManager.ConnectivityReport connectivityReport) {
        getWifiEntry().updateConnectivityReport(connectivityReport);
    }

    /**
     * Returns the WifiEntry object representing the single network being tracked.
     */
    @AnyThread
    @NonNull
    public abstract WifiEntry getWifiEntry();
}
