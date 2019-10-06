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

package com.android.server.wifi;

import static com.android.internal.util.Preconditions.checkNotNull;
import static com.android.server.wifi.util.NativeUtil.addEnclosingQuotes;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.AlarmManager;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.MacAddress;
import android.net.NetworkCapabilities;
import android.net.NetworkFactory;
import android.net.NetworkRequest;
import android.net.NetworkSpecifier;
import android.net.wifi.INetworkRequestMatchCallback;
import android.net.wifi.INetworkRequestUserSelectionCallback;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiConfiguration.SecurityType;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkSpecifier;
import android.net.wifi.WifiScanner;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.PatternMatcher;
import android.os.Process;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.WorkSource;
import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.wifi.nano.WifiMetricsProto;
import com.android.server.wifi.util.ExternalCallbackTracker;
import com.android.server.wifi.util.ScanResultUtil;
import com.android.server.wifi.util.WifiPermissionsUtil;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Network factory to handle trusted wifi network requests.
 */
public class WifiNetworkFactory extends NetworkFactory {
    private static final String TAG = "WifiNetworkFactory";
    @VisibleForTesting
    private static final int SCORE_FILTER = 60;
    @VisibleForTesting
    public static final int PERIODIC_SCAN_INTERVAL_MS = 10 * 1000; // 10 seconds
    @VisibleForTesting
    public static final int NETWORK_CONNECTION_TIMEOUT_MS = 30 * 1000; // 30 seconds
    @VisibleForTesting
    public static final int USER_SELECTED_NETWORK_CONNECT_RETRY_MAX = 3; // max of 3 retries.
    @VisibleForTesting
    public static final String UI_START_INTENT_ACTION =
            "com.android.settings.wifi.action.NETWORK_REQUEST";
    @VisibleForTesting
    public static final String UI_START_INTENT_CATEGORY = "android.intent.category.DEFAULT";
    @VisibleForTesting
    public static final String UI_START_INTENT_EXTRA_APP_NAME =
            "com.android.settings.wifi.extra.APP_NAME";
    @VisibleForTesting
    public static final String UI_START_INTENT_EXTRA_REQUEST_IS_FOR_SINGLE_NETWORK =
            "com.android.settings.wifi.extra.REQUEST_IS_FOR_SINGLE_NETWORK";

    private final Context mContext;
    private final ActivityManager mActivityManager;
    private final AlarmManager mAlarmManager;
    private final AppOpsManager mAppOpsManager;
    private final Clock mClock;
    private final Handler mHandler;
    private final WifiInjector mWifiInjector;
    private final WifiConnectivityManager mWifiConnectivityManager;
    private final WifiConfigManager mWifiConfigManager;
    private final WifiConfigStore mWifiConfigStore;
    private final WifiPermissionsUtil mWifiPermissionsUtil;
    private final WifiMetrics mWifiMetrics;
    private final WifiScanner.ScanSettings mScanSettings;
    private final NetworkFactoryScanListener mScanListener;
    private final PeriodicScanAlarmListener mPeriodicScanTimerListener;
    private final ConnectionTimeoutAlarmListener mConnectionTimeoutAlarmListener;
    private final ExternalCallbackTracker<INetworkRequestMatchCallback> mRegisteredCallbacks;
    private final Messenger mSrcMessenger;
    // Store all user approved access points for apps.
    // TODO(b/122658039): Persist this.
    private final Map<String, Set<AccessPoint>> mUserApprovedAccessPointMap = new HashMap<>();
    private WifiScanner mWifiScanner;

    private int mGenericConnectionReqCount = 0;
    // Request that is being actively processed. All new requests start out as an "active" request
    // because we're processing it & handling all the user interactions associated with it. Once we
    // successfully connect to the network, we transition that request to "connected".
    private NetworkRequest mActiveSpecificNetworkRequest;
    private WifiNetworkSpecifier mActiveSpecificNetworkRequestSpecifier;
    // Request corresponding to the the network that the device is currently connected to.
    private NetworkRequest mConnectedSpecificNetworkRequest;
    private WifiNetworkSpecifier mConnectedSpecificNetworkRequestSpecifier;
    private WifiConfiguration mUserSelectedNetwork;
    private int mUserSelectedNetworkConnectRetryCount;
    private List<ScanResult> mActiveMatchedScanResults;
    // Verbose logging flag.
    private boolean mVerboseLoggingEnabled = false;
    private boolean mPeriodicScanTimerSet = false;
    private boolean mConnectionTimeoutSet = false;
    private boolean mIsPeriodicScanPaused = false;
    // We sent a new connection request and are waiting for connection success.
    private boolean mPendingConnectionSuccess = false;
    private boolean mWifiEnabled = false;
    /**
     * Indicates that we have new data to serialize.
     */
    private boolean mHasNewDataToSerialize = false;

    /**
     * Helper class to store an access point that the user previously approved for a specific app.
     * TODO(b/123014687): Move to a common util class.
     */
    public static class AccessPoint {
        public final String ssid;
        public final MacAddress bssid;
        public final @SecurityType int networkType;

        AccessPoint(@NonNull String ssid, @NonNull MacAddress bssid,
                    @SecurityType int networkType) {
            this.ssid = ssid;
            this.bssid = bssid;
            this.networkType = networkType;
        }

        @Override
        public int hashCode() {
            return Objects.hash(ssid, bssid, networkType);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof AccessPoint)) {
                return false;
            }
            AccessPoint other = (AccessPoint) obj;
            return TextUtils.equals(this.ssid, other.ssid)
                    && Objects.equals(this.bssid, other.bssid)
                    && this.networkType == other.networkType;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("AccessPoint: ");
            return sb.append(ssid)
                    .append(", ")
                    .append(bssid)
                    .append(", ")
                    .append(networkType)
                    .toString();
        }
    }

    // Scan listener for scan requests.
    private class NetworkFactoryScanListener implements WifiScanner.ScanListener {
        @Override
        public void onSuccess() {
            // Scan request succeeded, wait for results to report to external clients.
            if (mVerboseLoggingEnabled) {
                Log.d(TAG, "Scan request succeeded");
            }
        }

        @Override
        public void onFailure(int reason, String description) {
            Log.e(TAG, "Scan failure received. reason: " + reason
                    + ", description: " + description);
            // TODO(b/113878056): Retry scan to workaround any transient scan failures.
            scheduleNextPeriodicScan();
        }

        @Override
        public void onResults(WifiScanner.ScanData[] scanDatas) {
            if (mVerboseLoggingEnabled) {
                Log.d(TAG, "Scan results received");
            }
            // For single scans, the array size should always be 1.
            if (scanDatas.length != 1) {
                Log.wtf(TAG, "Found more than 1 batch of scan results, Ignoring...");
                return;
            }
            WifiScanner.ScanData scanData = scanDatas[0];
            ScanResult[] scanResults = scanData.getResults();
            if (mVerboseLoggingEnabled) {
                Log.v(TAG, "Received " + scanResults.length + " scan results");
            }
            List<ScanResult> matchedScanResults =
                    getNetworksMatchingActiveNetworkRequest(scanResults);
            if (mActiveMatchedScanResults == null) {
                // only note the first match size in metrics (chances of this changing in further
                // scans is pretty low)
                mWifiMetrics.incrementNetworkRequestApiMatchSizeHistogram(
                        matchedScanResults.size());
            }
            mActiveMatchedScanResults = matchedScanResults;

            ScanResult approvedScanResult = null;
            if (isActiveRequestForSingleAccessPoint()) {
                approvedScanResult =
                        findUserApprovedAccessPointForActiveRequestFromActiveMatchedScanResults();
            }
            if (approvedScanResult != null
                    && !mWifiConfigManager.wasEphemeralNetworkDeleted(
                            ScanResultUtil.createQuotedSSID(approvedScanResult.SSID))) {
                Log.v(TAG, "Approved access point found in matching scan results. "
                        + "Triggering connect " + approvedScanResult);
                handleConnectToNetworkUserSelectionInternal(
                        ScanResultUtil.createNetworkFromScanResult(approvedScanResult));
                mWifiMetrics.incrementNetworkRequestApiNumUserApprovalBypass();
                // TODO (b/122658039): Post notification.
            } else {
                if (mVerboseLoggingEnabled) {
                    Log.v(TAG, "No approved access points found in matching scan results. "
                            + "Sending match callback");
                }
                sendNetworkRequestMatchCallbacksForActiveRequest(matchedScanResults);
                // Didn't find an approved match, schedule the next scan.
                scheduleNextPeriodicScan();
            }
        }

        @Override
        public void onFullResult(ScanResult fullScanResult) {
            // Ignore for single scans.
        }

        @Override
        public void onPeriodChanged(int periodInMs) {
            // Ignore for single scans.
        }
    };

    private class PeriodicScanAlarmListener implements AlarmManager.OnAlarmListener {
        @Override
        public void onAlarm() {
            // Trigger the next scan.
            startScan();
        }
    }

    private class ConnectionTimeoutAlarmListener implements AlarmManager.OnAlarmListener {
        @Override
        public void onAlarm() {
            Log.e(TAG, "Timed-out connecting to network");
            handleNetworkConnectionFailure(mUserSelectedNetwork);
        }
    }

    // Callback result from settings UI.
    private class NetworkFactoryUserSelectionCallback extends
            INetworkRequestUserSelectionCallback.Stub {
        private final NetworkRequest mNetworkRequest;

        NetworkFactoryUserSelectionCallback(NetworkRequest networkRequest) {
            mNetworkRequest = networkRequest;
        }

        @Override
        public void select(WifiConfiguration wifiConfiguration) {
            mHandler.post(() -> {
                if (mActiveSpecificNetworkRequest != mNetworkRequest) {
                    Log.e(TAG, "Stale callback select received");
                    return;
                }
                handleConnectToNetworkUserSelection(wifiConfiguration);
            });
        }

        @Override
        public void reject() {
            mHandler.post(() -> {
                if (mActiveSpecificNetworkRequest != mNetworkRequest) {
                    Log.e(TAG, "Stale callback reject received");
                    return;
                }
                handleRejectUserSelection();
            });
        }
    }

    private final Handler.Callback mNetworkConnectionTriggerCallback = (Message msg) -> {
        switch (msg.what) {
            // Success here means that an attempt to connect to the network has been initiated.
            case WifiManager.CONNECT_NETWORK_SUCCEEDED:
                if (mVerboseLoggingEnabled) {
                    Log.v(TAG, "Triggered network connection");
                }
                break;
            case WifiManager.CONNECT_NETWORK_FAILED:
                Log.e(TAG, "Failed to trigger network connection");
                handleNetworkConnectionFailure(mUserSelectedNetwork);
                break;
            default:
                Log.e(TAG, "Unknown message " + msg.what);
        }
        return true;
    };

    /**
     * Module to interact with the wifi config store.
     */
    private class NetworkRequestDataSource implements NetworkRequestStoreData.DataSource {
        @Override
        public Map<String, Set<AccessPoint>> toSerialize() {
            // Clear the flag after writing to disk.
            mHasNewDataToSerialize = false;
            return mUserApprovedAccessPointMap;
        }

        @Override
        public void fromDeserialized(Map<String, Set<AccessPoint>> approvedAccessPointMap) {
            mUserApprovedAccessPointMap.putAll(approvedAccessPointMap);
        }

        @Override
        public void reset() {
            mUserApprovedAccessPointMap.clear();
        }

        @Override
        public boolean hasNewDataToSerialize() {
            return mHasNewDataToSerialize;
        }
    }

    public WifiNetworkFactory(Looper looper, Context context, NetworkCapabilities nc,
                              ActivityManager activityManager, AlarmManager alarmManager,
                              AppOpsManager appOpsManager,
                              Clock clock, WifiInjector wifiInjector,
                              WifiConnectivityManager connectivityManager,
                              WifiConfigManager configManager,
                              WifiConfigStore configStore,
                              WifiPermissionsUtil wifiPermissionsUtil,
                              WifiMetrics wifiMetrics) {
        super(looper, context, TAG, nc);
        mContext = context;
        mActivityManager = activityManager;
        mAlarmManager = alarmManager;
        mAppOpsManager = appOpsManager;
        mClock = clock;
        mHandler = new Handler(looper);
        mWifiInjector = wifiInjector;
        mWifiConnectivityManager = connectivityManager;
        mWifiConfigManager = configManager;
        mWifiConfigStore = configStore;
        mWifiPermissionsUtil = wifiPermissionsUtil;
        mWifiMetrics = wifiMetrics;
        // Create the scan settings.
        mScanSettings = new WifiScanner.ScanSettings();
        mScanSettings.type = WifiScanner.TYPE_HIGH_ACCURACY;
        mScanSettings.band = WifiScanner.WIFI_BAND_BOTH_WITH_DFS;
        mScanSettings.reportEvents = WifiScanner.REPORT_EVENT_AFTER_EACH_SCAN;
        mScanListener = new NetworkFactoryScanListener();
        mPeriodicScanTimerListener = new PeriodicScanAlarmListener();
        mConnectionTimeoutAlarmListener = new ConnectionTimeoutAlarmListener();
        mRegisteredCallbacks = new ExternalCallbackTracker<INetworkRequestMatchCallback>(mHandler);
        mSrcMessenger = new Messenger(new Handler(looper, mNetworkConnectionTriggerCallback));

        // register the data store for serializing/deserializing data.
        configStore.registerStoreData(
                wifiInjector.makeNetworkRequestStoreData(new NetworkRequestDataSource()));

        setScoreFilter(SCORE_FILTER);
    }

    private void saveToStore() {
        // Set the flag to let WifiConfigStore that we have new data to write.
        mHasNewDataToSerialize = true;
        if (!mWifiConfigManager.saveToStore(true)) {
            Log.w(TAG, "Failed to save to store");
        }
    }

    /**
     * Enable verbose logging.
     */
    public void enableVerboseLogging(int verbose) {
        mVerboseLoggingEnabled = (verbose > 0);
    }

    /**
     * Add a new callback for network request match handling.
     */
    public void addCallback(IBinder binder, INetworkRequestMatchCallback callback,
                            int callbackIdentifier) {
        if (mActiveSpecificNetworkRequest == null) {
            Log.wtf(TAG, "No valid network request. Ignoring callback registration");
            try {
                callback.onAbort();
            } catch (RemoteException e) {
                Log.e(TAG, "Unable to invoke network request abort callback " + callback, e);
            }
            return;
        }
        if (!mRegisteredCallbacks.add(binder, callback, callbackIdentifier)) {
            Log.e(TAG, "Failed to add callback");
            return;
        }
        if (mVerboseLoggingEnabled) {
            Log.v(TAG, "Adding callback. Num callbacks: " + mRegisteredCallbacks.getNumCallbacks());
        }
        // Register our user selection callback.
        try {
            callback.onUserSelectionCallbackRegistration(
                    new NetworkFactoryUserSelectionCallback(mActiveSpecificNetworkRequest));
        } catch (RemoteException e) {
            Log.e(TAG, "Unable to invoke user selection registration callback " + callback, e);
        }
    }

    /**
     * Remove an existing callback for network request match handling.
     */
    public void removeCallback(int callbackIdentifier) {
        mRegisteredCallbacks.remove(callbackIdentifier);
        if (mVerboseLoggingEnabled) {
            Log.v(TAG, "Removing callback. Num callbacks: "
                    + mRegisteredCallbacks.getNumCallbacks());
        }
    }

    private boolean canNewRequestOverrideExistingRequest(
            WifiNetworkSpecifier newRequest, WifiNetworkSpecifier existingRequest) {
        if (existingRequest == null) return true;
        // Request from app with NETWORK_SETTINGS can override any existing requests.
        if (mWifiPermissionsUtil.checkNetworkSettingsPermission(newRequest.requestorUid)) {
            return true;
        }
        // Request from fg app can override any existing requests.
        if (isRequestFromForegroundApp(newRequest.requestorPackageName)) return true;
        // Request from fg service can override only if the existing request is not from a fg app.
        if (!isRequestFromForegroundApp(existingRequest.requestorPackageName)) return true;
        Log.e(TAG, "Already processing request from a foreground app "
                + existingRequest.requestorPackageName + ". Rejecting request from "
                + newRequest.requestorPackageName);
        return false;
    }

    boolean isRequestWithNetworkSpecifierValid(NetworkRequest networkRequest) {
        NetworkSpecifier ns = networkRequest.networkCapabilities.getNetworkSpecifier();
        // Invalid network specifier.
        if (!(ns instanceof WifiNetworkSpecifier)) {
            Log.e(TAG, "Invalid network specifier mentioned. Rejecting");
            return false;
        }
        // Request cannot have internet capability since such a request can never be fulfilled.
        // (NetworkAgent for connection with WifiNetworkSpecifier will not have internet capability)
        if (networkRequest.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            Log.e(TAG, "Request with wifi network specifier cannot contain "
                    + "NET_CAPABILITY_INTERNET. Rejecting");
            return false;
        }
        return true;
    }

    /**
     * Check whether to accept the new network connection request.
     *
     * All the validation of the incoming request is done in this method.
     */
    @Override
    public boolean acceptRequest(NetworkRequest networkRequest, int score) {
        NetworkSpecifier ns = networkRequest.networkCapabilities.getNetworkSpecifier();
        if (ns == null) {
            // Generic wifi request. Always accept.
        } else {
            // Invalid request with network specifier.
            if (!isRequestWithNetworkSpecifierValid(networkRequest)) {
                releaseRequestAsUnfulfillableByAnyFactory(networkRequest);
                return false;
            }
            if (!mWifiEnabled) {
                // Will re-evaluate when wifi is turned on.
                Log.e(TAG, "Wifi off. Rejecting");
                return false;
            }
            WifiNetworkSpecifier wns = (WifiNetworkSpecifier) ns;
            if (!WifiConfigurationUtil.validateNetworkSpecifier(wns)) {
                Log.e(TAG, "Invalid network specifier."
                        + " Rejecting request from " + wns.requestorPackageName);
                releaseRequestAsUnfulfillableByAnyFactory(networkRequest);
                return false;
            }
            try {
                mAppOpsManager.checkPackage(wns.requestorUid, wns.requestorPackageName);
            } catch (SecurityException e) {
                Log.e(TAG, "Invalid uid/package name " + wns.requestorPackageName + ", "
                        + wns.requestorPackageName, e);
                releaseRequestAsUnfulfillableByAnyFactory(networkRequest);
                return false;
            }
            // Only allow specific wifi network request from foreground app/service.
            if (!mWifiPermissionsUtil.checkNetworkSettingsPermission(wns.requestorUid)
                    && !isRequestFromForegroundAppOrService(wns.requestorPackageName)) {
                Log.e(TAG, "Request not from foreground app or service."
                        + " Rejecting request from " + wns.requestorPackageName);
                releaseRequestAsUnfulfillableByAnyFactory(networkRequest);
                return false;
            }
            // If there is an active request, only proceed if the new request is from a foreground
            // app.
            if (!canNewRequestOverrideExistingRequest(
                    wns, mActiveSpecificNetworkRequestSpecifier)) {
                Log.e(TAG, "Request cannot override active request."
                        + " Rejecting request from " + wns.requestorPackageName);
                releaseRequestAsUnfulfillableByAnyFactory(networkRequest);
                return false;
            }
            // If there is a connected request, only proceed if the new request is from a foreground
            // app.
            if (!canNewRequestOverrideExistingRequest(
                    wns, mConnectedSpecificNetworkRequestSpecifier)) {
                Log.e(TAG, "Request cannot override connected request."
                        + " Rejecting request from " + wns.requestorPackageName);
                releaseRequestAsUnfulfillableByAnyFactory(networkRequest);
                return false;
            }
            if (mVerboseLoggingEnabled) {
                Log.v(TAG, "Accepted network request with specifier from fg "
                        + (isRequestFromForegroundApp(wns.requestorPackageName)
                        ? "app" : "service"));
            }
        }
        if (mVerboseLoggingEnabled) {
            Log.v(TAG, "Accepted network request " + networkRequest);
        }
        return true;
    }

    /**
     * Handle new network connection requests.
     *
     * The assumption here is that {@link #acceptRequest(NetworkRequest, int)} has already sanitized
     * the incoming request.
     */
    @Override
    protected void needNetworkFor(NetworkRequest networkRequest, int score) {
        NetworkSpecifier ns = networkRequest.networkCapabilities.getNetworkSpecifier();
        if (ns == null) {
            // Generic wifi request. Turn on auto-join if necessary.
            if (++mGenericConnectionReqCount == 1) {
                mWifiConnectivityManager.setTrustedConnectionAllowed(true);
            }
        } else {
            // Invalid request with network specifier.
            if (!isRequestWithNetworkSpecifierValid(networkRequest)) {
                releaseRequestAsUnfulfillableByAnyFactory(networkRequest);
                return;
            }
            if (!mWifiEnabled) {
                // Will re-evaluate when wifi is turned on.
                Log.e(TAG, "Wifi off. Rejecting");
                return;
            }
            retrieveWifiScanner();
            // Reset state from any previous request.
            setupForActiveRequest();

            // Store the active network request.
            mActiveSpecificNetworkRequest = new NetworkRequest(networkRequest);
            WifiNetworkSpecifier wns = (WifiNetworkSpecifier) ns;
            mActiveSpecificNetworkRequestSpecifier = new WifiNetworkSpecifier(
                    wns.ssidPatternMatcher, wns.bssidPatternMatcher, wns.wifiConfiguration,
                    wns.requestorUid, wns.requestorPackageName);
            mWifiMetrics.incrementNetworkRequestApiNumRequest();

            // Start UI to let the user grant/disallow this request from the app.
            startUi();
            // Trigger periodic scans for finding a network in the request.
            startPeriodicScans();
        }
    }

    @Override
    protected void releaseNetworkFor(NetworkRequest networkRequest) {
        NetworkSpecifier ns = networkRequest.networkCapabilities.getNetworkSpecifier();
        if (ns == null) {
            // Generic wifi request. Turn off auto-join if necessary.
            if (mGenericConnectionReqCount == 0) {
                Log.e(TAG, "No valid network request to release");
                return;
            }
            if (--mGenericConnectionReqCount == 0) {
                mWifiConnectivityManager.setTrustedConnectionAllowed(false);
            }
        } else {
            // Invalid network specifier.
            if (!(ns instanceof WifiNetworkSpecifier)) {
                Log.e(TAG, "Invalid network specifier mentioned. Ignoring");
                return;
            }
            if (!mWifiEnabled) {
                Log.e(TAG, "Wifi off. Ignoring");
                return;
            }
            if (mActiveSpecificNetworkRequest == null && mConnectedSpecificNetworkRequest == null) {
                Log.e(TAG, "Network release received with no active/connected request."
                        + " Ignoring");
                return;
            }
            if (Objects.equals(mActiveSpecificNetworkRequest, networkRequest)) {
                Log.i(TAG, "App released request, cancelling "
                        + mActiveSpecificNetworkRequest);
                teardownForActiveRequest();
            } else if (Objects.equals(mConnectedSpecificNetworkRequest, networkRequest)) {
                Log.i(TAG, "App released request, cancelling "
                        + mConnectedSpecificNetworkRequest);
                teardownForConnectedNetwork();
            } else {
                Log.e(TAG, "Network specifier does not match the active/connected request."
                        + " Ignoring");
            }
        }
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        super.dump(fd, pw, args);
        pw.println(TAG + ": mGenericConnectionReqCount " + mGenericConnectionReqCount);
        pw.println(TAG + ": mActiveSpecificNetworkRequest " + mActiveSpecificNetworkRequest);
        pw.println(TAG + ": mUserApprovedAccessPointMap " + mUserApprovedAccessPointMap);
    }

    /**
     * Check if there is at least one connection request.
     */
    public boolean hasConnectionRequests() {
        return mGenericConnectionReqCount > 0 || mActiveSpecificNetworkRequest != null
                || mConnectedSpecificNetworkRequest != null;
    }

    /**
     * Return the uid of the specific network request being processed if connected to the requested
     * network.
     *
     * @param connectedNetwork WifiConfiguration corresponding to the connected network.
     * @return Pair of uid & package name of the specific request (if any), else <-1, "">.
     */
    public Pair<Integer, String> getSpecificNetworkRequestUidAndPackageName(
            @NonNull WifiConfiguration connectedNetwork) {
        if (mUserSelectedNetwork == null || connectedNetwork == null) {
            return Pair.create(Process.INVALID_UID, "");
        }
        if (!isUserSelectedNetwork(connectedNetwork)) {
            Log.w(TAG, "Connected to unknown network " + connectedNetwork + ". Ignoring...");
            return Pair.create(Process.INVALID_UID, "");
        }
        if (mConnectedSpecificNetworkRequestSpecifier != null) {
            return Pair.create(mConnectedSpecificNetworkRequestSpecifier.requestorUid,
                    mConnectedSpecificNetworkRequestSpecifier.requestorPackageName);
        }
        if (mActiveSpecificNetworkRequestSpecifier != null) {
            return Pair.create(mActiveSpecificNetworkRequestSpecifier.requestorUid,
                    mActiveSpecificNetworkRequestSpecifier.requestorPackageName);
        }
        return Pair.create(Process.INVALID_UID, "");
    }

    // Helper method to add the provided network configuration to WifiConfigManager, if it does not
    // already exist & return the allocated network ID. This ID will be used in the CONNECT_NETWORK
    // request to ClientModeImpl.
    // If the network already exists, just return the network ID of the existing network.
    private int addNetworkToWifiConfigManager(@NonNull WifiConfiguration network) {
        WifiConfiguration existingSavedNetwork =
                mWifiConfigManager.getConfiguredNetwork(network.configKey());
        if (existingSavedNetwork != null) {
            return existingSavedNetwork.networkId;
        }
        NetworkUpdateResult networkUpdateResult =
                mWifiConfigManager.addOrUpdateNetwork(
                        network, mActiveSpecificNetworkRequestSpecifier.requestorUid,
                        mActiveSpecificNetworkRequestSpecifier.requestorPackageName);
        if (mVerboseLoggingEnabled) {
            Log.v(TAG, "Added network to config manager " + networkUpdateResult.netId);
        }
        return networkUpdateResult.netId;
    }

    // Helper method to trigger a connection request & schedule a timeout alarm to track the
    // connection request.
    private void connectToNetwork(@NonNull WifiConfiguration network) {
        // Cancel connection timeout alarm for any previous connection attempts.
        cancelConnectionTimeout();

        // First add the network to WifiConfigManager and then use the obtained networkId
        // in the CONNECT_NETWORK request.
        // Note: We don't do any error checks on the networkId because ClientModeImpl will do the
        // necessary checks when processing CONNECT_NETWORK.
        int networkId = addNetworkToWifiConfigManager(network);

        mWifiMetrics.setNominatorForNetwork(networkId,
                WifiMetricsProto.ConnectionEvent.NOMINATOR_SPECIFIER);

        // Send the connect request to ClientModeImpl.
        // TODO(b/117601161): Refactor this.
        Message msg = Message.obtain();
        msg.what = WifiManager.CONNECT_NETWORK;
        msg.arg1 = networkId;
        msg.replyTo = mSrcMessenger;
        mWifiInjector.getClientModeImpl().sendMessage(msg);

        // Post an alarm to handle connection timeout.
        scheduleConnectionTimeout();
    }

    private void handleConnectToNetworkUserSelectionInternal(WifiConfiguration network) {
        // Disable Auto-join so that NetworkFactory can take control of the network connection.
        mWifiConnectivityManager.setSpecificNetworkRequestInProgress(true);

        // Copy over the credentials from the app's request and then copy the ssid from user
        // selection.
        WifiConfiguration networkToConnect =
                new WifiConfiguration(mActiveSpecificNetworkRequestSpecifier.wifiConfiguration);
        networkToConnect.SSID = network.SSID;
        // Set the WifiConfiguration.BSSID field to prevent roaming.
        networkToConnect.BSSID = findBestBssidFromActiveMatchedScanResultsForNetwork(network);
        // Mark the network ephemeral so that it's automatically removed at the end of connection.
        networkToConnect.ephemeral = true;
        networkToConnect.fromWifiNetworkSpecifier = true;

        // Store the user selected network.
        mUserSelectedNetwork = networkToConnect;

        // Disconnect from the current network before issuing a new connect request.
        mWifiInjector.getClientModeImpl().disconnectCommand();
        // Trigger connection to the network.
        connectToNetwork(networkToConnect);
        // Triggered connection to network, now wait for the connection status.
        mPendingConnectionSuccess = true;
    }

    private void handleConnectToNetworkUserSelection(WifiConfiguration network) {
        Log.d(TAG, "User initiated connect to network: " + network.SSID);

        // Cancel the ongoing scans after user selection.
        cancelPeriodicScans();

        // Trigger connection attempts.
        handleConnectToNetworkUserSelectionInternal(network);

        // Add the network to the approved access point map for the app.
        addNetworkToUserApprovedAccessPointMap(mUserSelectedNetwork);
    }

    private void handleRejectUserSelection() {
        Log.w(TAG, "User dismissed notification, cancelling " + mActiveSpecificNetworkRequest);
        teardownForActiveRequest();
        mWifiMetrics.incrementNetworkRequestApiNumUserReject();
    }

    private boolean isUserSelectedNetwork(WifiConfiguration config) {
        if (!TextUtils.equals(mUserSelectedNetwork.SSID, config.SSID)) {
            return false;
        }
        if (!Objects.equals(
                mUserSelectedNetwork.allowedKeyManagement, config.allowedKeyManagement)) {
            return false;
        }
        return true;
    }

    /**
     * Invoked by {@link ClientModeImpl} on end of connection attempt to a network.
     */
    public void handleConnectionAttemptEnded(
            int failureCode, @NonNull WifiConfiguration network) {
        if (failureCode == WifiMetrics.ConnectionEvent.FAILURE_NONE) {
            handleNetworkConnectionSuccess(network);
        } else {
            handleNetworkConnectionFailure(network);
        }
    }

    /**
     * Invoked by {@link ClientModeImpl} on successful connection to a network.
     */
    private void handleNetworkConnectionSuccess(@NonNull WifiConfiguration connectedNetwork) {
        if (mUserSelectedNetwork == null || connectedNetwork == null
                || !mPendingConnectionSuccess) {
            return;
        }
        if (!isUserSelectedNetwork(connectedNetwork)) {
            Log.w(TAG, "Connected to unknown network " + connectedNetwork + ". Ignoring...");
            return;
        }
        Log.d(TAG, "Connected to network " + mUserSelectedNetwork);
        for (INetworkRequestMatchCallback callback : mRegisteredCallbacks.getCallbacks()) {
            try {
                callback.onUserSelectionConnectSuccess(mUserSelectedNetwork);
            } catch (RemoteException e) {
                Log.e(TAG, "Unable to invoke network request connect failure callback "
                        + callback, e);
            }
        }
        // transition the request from "active" to "connected".
        setupForConnectedRequest();
        mWifiMetrics.incrementNetworkRequestApiNumConnectSuccess();
    }

    /**
     * Invoked by {@link ClientModeImpl} on failure to connect to a network.
     */
    private void handleNetworkConnectionFailure(@NonNull WifiConfiguration failedNetwork) {
        if (mUserSelectedNetwork == null || failedNetwork == null || !mPendingConnectionSuccess) {
            return;
        }
        if (!isUserSelectedNetwork(failedNetwork)) {
            Log.w(TAG, "Connection failed to unknown network " + failedNetwork + ". Ignoring...");
            return;
        }
        Log.w(TAG, "Failed to connect to network " + mUserSelectedNetwork);
        if (mUserSelectedNetworkConnectRetryCount++ < USER_SELECTED_NETWORK_CONNECT_RETRY_MAX) {
            Log.i(TAG, "Retrying connection attempt, attempt# "
                    + mUserSelectedNetworkConnectRetryCount);
            connectToNetwork(mUserSelectedNetwork);
            return;
        }
        Log.e(TAG, "Connection failures, cancelling " + mUserSelectedNetwork);
        for (INetworkRequestMatchCallback callback : mRegisteredCallbacks.getCallbacks()) {
            try {
                callback.onUserSelectionConnectFailure(mUserSelectedNetwork);
            } catch (RemoteException e) {
                Log.e(TAG, "Unable to invoke network request connect failure callback "
                        + callback, e);
            }
        }
        teardownForActiveRequest();
    }

    /**
     * Invoked by {@link ClientModeImpl} to indicate screen state changes.
     */
    public void handleScreenStateChanged(boolean screenOn) {
        // If there is no active request or if the user has already selected a network,
        // ignore screen state changes.
        if (mActiveSpecificNetworkRequest == null || mUserSelectedNetwork != null) return;

        // Pause periodic scans when the screen is off & resume when the screen is on.
        if (screenOn) {
            if (mVerboseLoggingEnabled) Log.v(TAG, "Resuming scans on screen on");
            startScan();
            mIsPeriodicScanPaused = false;
        } else {
            if (mVerboseLoggingEnabled) Log.v(TAG, "Pausing scans on screen off");
            cancelPeriodicScans();
            mIsPeriodicScanPaused = true;
        }
    }

    /**
     * Invoked by {@link ClientModeImpl} to indicate wifi state toggle.
     */
    public void setWifiState(boolean enabled) {
        if (mVerboseLoggingEnabled) Log.v(TAG, "setWifiState " + enabled);
        if (enabled) {
            reevaluateAllRequests(); // Re-evaluate any pending requests.
        } else {
            if (mActiveSpecificNetworkRequest != null) {
                Log.w(TAG, "Wifi off, cancelling " + mActiveSpecificNetworkRequest);
                teardownForActiveRequest();
            }
            if (mConnectedSpecificNetworkRequest != null) {
                Log.w(TAG, "Wifi off, cancelling " + mConnectedSpecificNetworkRequest);
                teardownForConnectedNetwork();
            }
        }
        mWifiEnabled = enabled;
    }

    // Common helper method for start/end of active request processing.
    private void cleanupActiveRequest() {
        // Send the abort to the UI for the current active request.
        for (INetworkRequestMatchCallback callback : mRegisteredCallbacks.getCallbacks()) {
            try {
                callback.onAbort();
            } catch (RemoteException e) {
                Log.e(TAG, "Unable to invoke network request abort callback " + callback, e);
            }
        }
        // Force-release the network request to let the app know early that the attempt failed.
        if (mActiveSpecificNetworkRequest != null) {
            releaseRequestAsUnfulfillableByAnyFactory(mActiveSpecificNetworkRequest);
        }
        // Reset the active network request.
        mActiveSpecificNetworkRequest = null;
        mActiveSpecificNetworkRequestSpecifier = null;
        mUserSelectedNetwork = null;
        mUserSelectedNetworkConnectRetryCount = 0;
        mIsPeriodicScanPaused = false;
        mActiveMatchedScanResults = null;
        mPendingConnectionSuccess = false;
        // Cancel periodic scan, connection timeout alarm.
        cancelPeriodicScans();
        cancelConnectionTimeout();
        // Remove any callbacks registered for the request.
        mRegisteredCallbacks.clear();
    }

    // Invoked at the start of new active request processing.
    private void setupForActiveRequest() {
        if (mActiveSpecificNetworkRequest != null) {
            cleanupActiveRequest();
        }
    }

    // Invoked at the termination of current active request processing.
    private void teardownForActiveRequest() {
        cleanupActiveRequest();
        // ensure there is no connected request in progress.
        if (mConnectedSpecificNetworkRequest == null) {
            mWifiConnectivityManager.setSpecificNetworkRequestInProgress(false);
        }
    }

    // Invoked at the start of new connected request processing.
    private void setupForConnectedRequest() {
        mConnectedSpecificNetworkRequest = mActiveSpecificNetworkRequest;
        mConnectedSpecificNetworkRequestSpecifier = mActiveSpecificNetworkRequestSpecifier;
        mActiveSpecificNetworkRequest = null;
        mActiveSpecificNetworkRequestSpecifier = null;
        mPendingConnectionSuccess = false;
        // Cancel connection timeout alarm.
        cancelConnectionTimeout();
    }

    // Invoked at the termination of current connected request processing.
    private void teardownForConnectedNetwork() {
        Log.i(TAG, "Disconnecting from network on reset");
        mWifiInjector.getClientModeImpl().disconnectCommand();
        mConnectedSpecificNetworkRequest = null;
        mConnectedSpecificNetworkRequestSpecifier = null;
        // ensure there is no active request in progress.
        if (mActiveSpecificNetworkRequest == null) {
            mWifiConnectivityManager.setSpecificNetworkRequestInProgress(false);
        }
    }

    /**
     * Check if the request comes from foreground app/service.
     */
    private boolean isRequestFromForegroundAppOrService(@NonNull String requestorPackageName) {
        try {
            return mActivityManager.getPackageImportance(requestorPackageName)
                    <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE;
        } catch (SecurityException e) {
            Log.e(TAG, "Failed to check the app state", e);
            return false;
        }
    }

    /**
     * Check if the request comes from foreground app.
     */
    private boolean isRequestFromForegroundApp(@NonNull String requestorPackageName) {
        try {
            return mActivityManager.getPackageImportance(requestorPackageName)
                    <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND;
        } catch (SecurityException e) {
            Log.e(TAG, "Failed to check the app state", e);
            return false;
        }
    }

    /**
     * Helper method to populate WifiScanner handle. This is done lazily because
     * WifiScanningService is started after WifiService.
     */
    private void retrieveWifiScanner() {
        if (mWifiScanner != null) return;
        mWifiScanner = mWifiInjector.getWifiScanner();
        checkNotNull(mWifiScanner);
    }

    private void startPeriodicScans() {
        if (mActiveSpecificNetworkRequestSpecifier == null) {
            Log.e(TAG, "Periodic scan triggered when there is no active network request. "
                    + "Ignoring...");
            return;
        }
        WifiNetworkSpecifier wns = mActiveSpecificNetworkRequestSpecifier;
        WifiConfiguration wifiConfiguration = wns.wifiConfiguration;
        if (wifiConfiguration.hiddenSSID) {
            mScanSettings.hiddenNetworks = new WifiScanner.ScanSettings.HiddenNetwork[1];
            // Can't search for SSID pattern in hidden networks.
            mScanSettings.hiddenNetworks[0] =
                    new WifiScanner.ScanSettings.HiddenNetwork(
                            addEnclosingQuotes(wns.ssidPatternMatcher.getPath()));
        }
        startScan();
    }

    private void cancelPeriodicScans() {
        if (mPeriodicScanTimerSet) {
            mAlarmManager.cancel(mPeriodicScanTimerListener);
            mPeriodicScanTimerSet = false;
        }
        // Clear the hidden networks field after each request.
        mScanSettings.hiddenNetworks = null;
    }

    private void scheduleNextPeriodicScan() {
        if (mIsPeriodicScanPaused) {
            Log.e(TAG, "Scan triggered when periodic scanning paused. Ignoring...");
            return;
        }
        mAlarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                mClock.getElapsedSinceBootMillis() + PERIODIC_SCAN_INTERVAL_MS,
                TAG, mPeriodicScanTimerListener, mHandler);
        mPeriodicScanTimerSet = true;
    }

    private void startScan() {
        if (mActiveSpecificNetworkRequestSpecifier == null) {
            Log.e(TAG, "Scan triggered when there is no active network request. Ignoring...");
            return;
        }
        if (mVerboseLoggingEnabled) {
            Log.v(TAG, "Starting the next scan for " + mActiveSpecificNetworkRequestSpecifier);
        }
        // Create a worksource using the caller's UID.
        WorkSource workSource = new WorkSource(mActiveSpecificNetworkRequestSpecifier.requestorUid);
        mWifiScanner.startScan(mScanSettings, mScanListener, workSource);
    }

    private boolean doesScanResultMatchWifiNetworkSpecifier(
            WifiNetworkSpecifier wns, ScanResult scanResult) {
        if (!wns.ssidPatternMatcher.match(scanResult.SSID)) {
            return false;
        }
        MacAddress bssid = MacAddress.fromString(scanResult.BSSID);
        MacAddress matchBaseAddress = wns.bssidPatternMatcher.first;
        MacAddress matchMask = wns.bssidPatternMatcher.second;
        if (!bssid.matches(matchBaseAddress, matchMask)) {
            return false;
        }
        if (ScanResultMatchInfo.getNetworkType(wns.wifiConfiguration)
                != ScanResultMatchInfo.getNetworkType(scanResult)) {
            return false;
        }
        return true;
    }

    // Loops through the scan results and finds scan results matching the active network
    // request.
    private List<ScanResult> getNetworksMatchingActiveNetworkRequest(
            ScanResult[] scanResults) {
        if (mActiveSpecificNetworkRequestSpecifier == null) {
            Log.e(TAG, "Scan results received with no active network request. Ignoring...");
            return new ArrayList<>();
        }
        List<ScanResult> matchedScanResults = new ArrayList<>();
        WifiNetworkSpecifier wns = mActiveSpecificNetworkRequestSpecifier;

        for (ScanResult scanResult : scanResults) {
            if (doesScanResultMatchWifiNetworkSpecifier(wns, scanResult)) {
                matchedScanResults.add(scanResult);
            }
        }
        if (mVerboseLoggingEnabled) {
            Log.v(TAG, "List of scan results matching the active request "
                    + matchedScanResults);
        }
        return matchedScanResults;
    }

    private void sendNetworkRequestMatchCallbacksForActiveRequest(
            List<ScanResult> matchedScanResults) {
        if (mRegisteredCallbacks.getNumCallbacks() == 0) {
            Log.e(TAG, "No callback registered for sending network request matches. "
                    + "Ignoring...");
            return;
        }
        for (INetworkRequestMatchCallback callback : mRegisteredCallbacks.getCallbacks()) {
            try {
                callback.onMatch(matchedScanResults);
            } catch (RemoteException e) {
                Log.e(TAG, "Unable to invoke network request match callback " + callback, e);
            }
        }
    }

    private void cancelConnectionTimeout() {
        if (mConnectionTimeoutSet) {
            mAlarmManager.cancel(mConnectionTimeoutAlarmListener);
            mConnectionTimeoutSet = false;
        }
    }

    private void scheduleConnectionTimeout() {
        mAlarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                mClock.getElapsedSinceBootMillis() + NETWORK_CONNECTION_TIMEOUT_MS,
                TAG, mConnectionTimeoutAlarmListener, mHandler);
        mConnectionTimeoutSet = true;
    }

    private @NonNull CharSequence getAppName(@NonNull String packageName) {
        ApplicationInfo applicationInfo = null;
        try {
            applicationInfo = mContext.getPackageManager().getApplicationInfo(packageName, 0);
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, "Failed to find app name for " + packageName);
            return "";
        }
        CharSequence appName = mContext.getPackageManager().getApplicationLabel(applicationInfo);
        return (appName != null) ? appName : "";
    }

    private void startUi() {
        Intent intent = new Intent();
        intent.setAction(UI_START_INTENT_ACTION);
        intent.addCategory(UI_START_INTENT_CATEGORY);
        intent.setFlags(Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT | Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra(UI_START_INTENT_EXTRA_APP_NAME,
                getAppName(mActiveSpecificNetworkRequestSpecifier.requestorPackageName));
        intent.putExtra(UI_START_INTENT_EXTRA_REQUEST_IS_FOR_SINGLE_NETWORK,
                isActiveRequestForSingleNetwork());
        mContext.startActivityAsUser(intent, UserHandle.getUserHandleForUid(
                mActiveSpecificNetworkRequestSpecifier.requestorUid));
    }

    // Helper method to determine if the specifier does not contain any patterns and matches
    // a single access point.
    private boolean isActiveRequestForSingleAccessPoint() {
        if (mActiveSpecificNetworkRequestSpecifier == null) return false;

        if (mActiveSpecificNetworkRequestSpecifier.ssidPatternMatcher.getType()
                != PatternMatcher.PATTERN_LITERAL) {
            return false;
        }
        if (!Objects.equals(
                mActiveSpecificNetworkRequestSpecifier.bssidPatternMatcher.second,
                MacAddress.BROADCAST_ADDRESS)) {
            return false;
        }
        return true;
    }

    // Helper method to determine if the specifier does not contain any patterns and matches
    // a single network.
    private boolean isActiveRequestForSingleNetwork() {
        if (mActiveSpecificNetworkRequestSpecifier == null) return false;

        if (mActiveSpecificNetworkRequestSpecifier.ssidPatternMatcher.getType()
                == PatternMatcher.PATTERN_LITERAL) {
            return true;
        }
        if (Objects.equals(
                mActiveSpecificNetworkRequestSpecifier.bssidPatternMatcher.second,
                MacAddress.BROADCAST_ADDRESS)) {
            return true;
        }
        return false;
    }

    // Will return the best bssid to use for the current request's connection.
    //
    // Note: This will never return null, unless there is some internal error.
    // For ex:
    // i) The latest scan results were empty.
    // ii) The latest scan result did not contain any BSSID for the SSID user chose.
    private @Nullable String findBestBssidFromActiveMatchedScanResultsForNetwork(
            @NonNull WifiConfiguration network) {
        if (mActiveSpecificNetworkRequestSpecifier == null
                || mActiveMatchedScanResults == null) return null;
        ScanResult selectedScanResult = mActiveMatchedScanResults
                .stream()
                .filter(scanResult -> Objects.equals(
                        ScanResultMatchInfo.fromScanResult(scanResult),
                        ScanResultMatchInfo.fromWifiConfiguration(network)))
                .max(Comparator.comparing(scanResult -> scanResult.level))
                .orElse(null);
        if (selectedScanResult == null) { // Should never happen.
            Log.wtf(TAG, "Expected to find at least one matching scan result");
            return null;
        }
        if (mVerboseLoggingEnabled) {
            Log.v(TAG, "Best bssid selected for the request " + selectedScanResult);
        }
        return selectedScanResult.BSSID;
    }

    private @Nullable ScanResult
            findUserApprovedAccessPointForActiveRequestFromActiveMatchedScanResults() {
        if (mActiveSpecificNetworkRequestSpecifier == null
                || mActiveMatchedScanResults == null) return null;
        String requestorPackageName = mActiveSpecificNetworkRequestSpecifier.requestorPackageName;
        Set<AccessPoint> approvedAccessPoints =
                mUserApprovedAccessPointMap.get(requestorPackageName);
        if (approvedAccessPoints == null) return null;
        for (ScanResult scanResult : mActiveMatchedScanResults) {
            ScanResultMatchInfo fromScanResult = ScanResultMatchInfo.fromScanResult(scanResult);
            AccessPoint accessPoint =
                    new AccessPoint(scanResult.SSID,
                            MacAddress.fromString(scanResult.BSSID), fromScanResult.networkType);
            if (approvedAccessPoints.contains(accessPoint)) {
                if (mVerboseLoggingEnabled) {
                    Log.v(TAG, "Found " + accessPoint
                            + " in user approved access point for " + requestorPackageName);
                }
                return scanResult;
            }
        }
        return null;
    }

    // Helper method to store the all the BSSIDs matching the network from the matched scan results
    private void addNetworkToUserApprovedAccessPointMap(@NonNull WifiConfiguration network) {
        if (mActiveSpecificNetworkRequestSpecifier == null
                || mActiveMatchedScanResults == null) return;
        // Note: This hopefully is a list of size 1, because we want to store a 1:1 mapping
        // from user selection and the AP that was approved. But, since we get a WifiConfiguration
        // object representing an entire network from UI, we need to ensure that all the visible
        // BSSIDs matching the original request and the selected network are stored.
        Set<AccessPoint> newUserApprovedAccessPoints = new HashSet<>();
        for (ScanResult scanResult : mActiveMatchedScanResults) {
            ScanResultMatchInfo fromScanResult = ScanResultMatchInfo.fromScanResult(scanResult);
            ScanResultMatchInfo fromWifiConfiguration =
                    ScanResultMatchInfo.fromWifiConfiguration(network);
            if (fromScanResult.equals(fromWifiConfiguration)) {
                AccessPoint approvedAccessPoint =
                        new AccessPoint(scanResult.SSID, MacAddress.fromString(scanResult.BSSID),
                                fromScanResult.networkType);
                newUserApprovedAccessPoints.add(approvedAccessPoint);
            }
        }
        if (newUserApprovedAccessPoints.isEmpty()) return;

        String requestorPackageName = mActiveSpecificNetworkRequestSpecifier.requestorPackageName;
        Set<AccessPoint> approvedAccessPoints =
                mUserApprovedAccessPointMap.get(requestorPackageName);
        if (approvedAccessPoints == null) {
            approvedAccessPoints = new HashSet<>();
            mUserApprovedAccessPointMap.put(requestorPackageName, approvedAccessPoints);
            // Note the new app in metrics.
            mWifiMetrics.incrementNetworkRequestApiNumApps();
        }
        if (mVerboseLoggingEnabled) {
            Log.v(TAG, "Adding " + newUserApprovedAccessPoints
                    + " to user approved access point for " + requestorPackageName);
        }
        approvedAccessPoints.addAll(newUserApprovedAccessPoints);
        saveToStore();
    }

    /**
     * Remove all user approved access points for the specified app.
     */
    public void removeUserApprovedAccessPointsForApp(@NonNull String packageName) {
        Iterator<Map.Entry<String, Set<AccessPoint>>> iter =
                mUserApprovedAccessPointMap.entrySet().iterator();
        while (iter.hasNext()) {
            Map.Entry<String, Set<AccessPoint>> entry = iter.next();
            if (packageName.equals(entry.getKey())) {
                Log.i(TAG, "Removing all approved access points for " + packageName);
                iter.remove();
            }
        }
        saveToStore();
    }

    /**
     * Clear all internal state (for network settings reset).
     */
    public void clear() {
        mUserApprovedAccessPointMap.clear();
        Log.i(TAG, "Cleared all internal state");
        saveToStore();
    }
}

