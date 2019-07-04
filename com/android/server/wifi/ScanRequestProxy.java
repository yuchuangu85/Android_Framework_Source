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

import android.annotation.NonNull;
import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiScanner;
import android.os.Binder;
import android.os.UserHandle;
import android.os.WorkSource;
import android.util.ArrayMap;
import android.util.Log;
import android.util.Pair;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.wifi.util.WifiPermissionsUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

import javax.annotation.concurrent.NotThreadSafe;

/**
 * This class manages all scan requests originating from external apps using the
 * {@link WifiManager#startScan()}.
 *
 * This class is responsible for:
 * a) Forwarding scan requests from {@link WifiManager#startScan()} to
 * {@link WifiScanner#startScan(WifiScanner.ScanSettings, WifiScanner.ScanListener)}.
 * Will essentially proxy scan requests from WifiService to WifiScanningService.
 * b) Cache the results of these scan requests and return them when
 * {@link WifiManager#getScanResults()} is invoked.
 * c) Will send out the {@link WifiManager#SCAN_RESULTS_AVAILABLE_ACTION} broadcast when new
 * scan results are available.
 * d) Throttle scan requests from non-setting apps:
 *  a) Each foreground app can request a max of
 *   {@link #SCAN_REQUEST_THROTTLE_MAX_IN_TIME_WINDOW_FG_APPS} scan every
 *   {@link #SCAN_REQUEST_THROTTLE_TIME_WINDOW_FG_APPS_MS}.
 *  b) Background apps combined can request 1 scan every
 *   {@link #SCAN_REQUEST_THROTTLE_INTERVAL_BG_APPS_MS}.
 * Note: This class is not thread-safe. It needs to be invoked from WifiStateMachine thread only.
 */
@NotThreadSafe
public class ScanRequestProxy {
    private static final String TAG = "WifiScanRequestProxy";

    @VisibleForTesting
    public static final int SCAN_REQUEST_THROTTLE_TIME_WINDOW_FG_APPS_MS = 120 * 1000;
    @VisibleForTesting
    public static final int SCAN_REQUEST_THROTTLE_MAX_IN_TIME_WINDOW_FG_APPS = 4;
    @VisibleForTesting
    public static final int SCAN_REQUEST_THROTTLE_INTERVAL_BG_APPS_MS = 30 * 60 * 1000;

    private final Context mContext;
    private final AppOpsManager mAppOps;
    private final ActivityManager mActivityManager;
    private final WifiInjector mWifiInjector;
    private final WifiConfigManager mWifiConfigManager;
    private final WifiPermissionsUtil mWifiPermissionsUtil;
    private final WifiMetrics mWifiMetrics;
    private final Clock mClock;
    private WifiScanner mWifiScanner;

    // Verbose logging flag.
    private boolean mVerboseLoggingEnabled = false;
    // Flag to decide if we need to scan for hidden networks or not.
    private boolean mScanningForHiddenNetworksEnabled = false;
    // Flag to indicate that we're waiting for scan results from an existing request.
    private boolean mIsScanProcessingComplete = true;
    // Timestamps for the last scan requested by any background app.
    private long mLastScanTimestampForBgApps = 0;
    // Timestamps for the list of last few scan requests by each foreground app.
    // Keys in the map = Pair<Uid, PackageName> of the app.
    // Values in the map = List of the last few scan request timestamps from the app.
    private final ArrayMap<Pair<Integer, String>, LinkedList<Long>> mLastScanTimestampsForFgApps =
            new ArrayMap();
    // Scan results cached from the last full single scan request.
    private final List<ScanResult> mLastScanResults = new ArrayList<>();
    // Common scan listener for scan requests.
    private class ScanRequestProxyScanListener implements WifiScanner.ScanListener {
        @Override
        public void onSuccess() {
            // Scan request succeeded, wait for results to report to external clients.
            if (mVerboseLoggingEnabled) {
                Log.d(TAG, "Scan request succeeded");
            }
        }

        @Override
        public void onFailure(int reason, String description) {
            Log.e(TAG, "Scan failure received. reason: " + reason + ",description: " + description);
            sendScanResultBroadcastIfScanProcessingNotComplete(false);
        }

        @Override
        public void onResults(WifiScanner.ScanData[] scanDatas) {
            if (mVerboseLoggingEnabled) {
                Log.d(TAG, "Scan results received");
            }
            // For single scans, the array size should always be 1.
            if (scanDatas.length != 1) {
                Log.wtf(TAG, "Found more than 1 batch of scan results, Failing...");
                sendScanResultBroadcastIfScanProcessingNotComplete(false);
                return;
            }
            WifiScanner.ScanData scanData = scanDatas[0];
            ScanResult[] scanResults = scanData.getResults();
            if (mVerboseLoggingEnabled) {
                Log.d(TAG, "Received " + scanResults.length + " scan results");
            }
            // Store the last scan results & send out the scan completion broadcast.
            mLastScanResults.clear();
            mLastScanResults.addAll(Arrays.asList(scanResults));
            sendScanResultBroadcastIfScanProcessingNotComplete(true);
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

    ScanRequestProxy(Context context, AppOpsManager appOpsManager, ActivityManager activityManager,
                     WifiInjector wifiInjector, WifiConfigManager configManager,
                     WifiPermissionsUtil wifiPermissionUtil, WifiMetrics wifiMetrics, Clock clock) {
        mContext = context;
        mAppOps = appOpsManager;
        mActivityManager = activityManager;
        mWifiInjector = wifiInjector;
        mWifiConfigManager = configManager;
        mWifiPermissionsUtil = wifiPermissionUtil;
        mWifiMetrics = wifiMetrics;
        mClock = clock;
    }

    /**
     * Enable verbose logging.
     */
    public void enableVerboseLogging(int verbose) {
        mVerboseLoggingEnabled = (verbose > 0);
    }

    /**
     * Enable/disable scanning for hidden networks.
     * @param enable true to enable, false to disable.
     */
    public void enableScanningForHiddenNetworks(boolean enable) {
        if (mVerboseLoggingEnabled) {
            Log.d(TAG, "Scanning for hidden networks is " + (enable ? "enabled" : "disabled"));
        }
        mScanningForHiddenNetworksEnabled = enable;
    }

    /**
     * Helper method to populate WifiScanner handle. This is done lazily because
     * WifiScanningService is started after WifiService.
     */
    private boolean retrieveWifiScannerIfNecessary() {
        if (mWifiScanner == null) {
            mWifiScanner = mWifiInjector.getWifiScanner();
        }
        return mWifiScanner != null;
    }

    /**
     * Helper method to send the scan request status broadcast, if there is a scan ongoing.
     */
    private void sendScanResultBroadcastIfScanProcessingNotComplete(boolean scanSucceeded) {
        if (mIsScanProcessingComplete) {
            Log.i(TAG, "No ongoing scan request. Don't send scan broadcast.");
            return;
        }
        sendScanResultBroadcast(scanSucceeded);
        mIsScanProcessingComplete = true;
    }

    /**
     * Helper method to send the scan request status broadcast.
     */
    private void sendScanResultBroadcast(boolean scanSucceeded) {
        // clear calling identity to send broadcast
        long callingIdentity = Binder.clearCallingIdentity();
        try {
            Intent intent = new Intent(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
            intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
            intent.putExtra(WifiManager.EXTRA_RESULTS_UPDATED, scanSucceeded);
            mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
        } finally {
            // restore calling identity
            Binder.restoreCallingIdentity(callingIdentity);
        }
    }

    /**
     * Helper method to send the scan request failure broadcast to specified package.
     */
    private void sendScanResultFailureBroadcastToPackage(String packageName) {
        // clear calling identity to send broadcast
        long callingIdentity = Binder.clearCallingIdentity();
        try {
            Intent intent = new Intent(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
            intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
            intent.putExtra(WifiManager.EXTRA_RESULTS_UPDATED, false);
            intent.setPackage(packageName);
            mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
        } finally {
            // restore calling identity
            Binder.restoreCallingIdentity(callingIdentity);
        }
    }

    private void trimPastScanRequestTimesForForegroundApp(
            List<Long> scanRequestTimestamps, long currentTimeMillis) {
        Iterator<Long> timestampsIter = scanRequestTimestamps.iterator();
        while (timestampsIter.hasNext()) {
            Long scanRequestTimeMillis = timestampsIter.next();
            if ((currentTimeMillis - scanRequestTimeMillis)
                    > SCAN_REQUEST_THROTTLE_TIME_WINDOW_FG_APPS_MS) {
                timestampsIter.remove();
            } else {
                // This list is sorted by timestamps, so we can skip any more checks
                break;
            }
        }
    }

    private LinkedList<Long> getOrCreateScanRequestTimestampsForForegroundApp(
            int callingUid, String packageName) {
        Pair<Integer, String> uidAndPackageNamePair = Pair.create(callingUid, packageName);
        LinkedList<Long> scanRequestTimestamps =
                mLastScanTimestampsForFgApps.get(uidAndPackageNamePair);
        if (scanRequestTimestamps == null) {
            scanRequestTimestamps = new LinkedList<>();
            mLastScanTimestampsForFgApps.put(uidAndPackageNamePair, scanRequestTimestamps);
        }
        return scanRequestTimestamps;
    }

    /**
     * Checks if the scan request from the app (specified by packageName) needs
     * to be throttled.
     * The throttle limit allows a max of {@link #SCAN_REQUEST_THROTTLE_MAX_IN_TIME_WINDOW_FG_APPS}
     * in {@link #SCAN_REQUEST_THROTTLE_TIME_WINDOW_FG_APPS_MS} window.
     */
    private boolean shouldScanRequestBeThrottledForForegroundApp(
            int callingUid, String packageName) {
        LinkedList<Long> scanRequestTimestamps =
                getOrCreateScanRequestTimestampsForForegroundApp(callingUid, packageName);
        long currentTimeMillis = mClock.getElapsedSinceBootMillis();
        // First evict old entries from the list.
        trimPastScanRequestTimesForForegroundApp(scanRequestTimestamps, currentTimeMillis);
        if (scanRequestTimestamps.size() >= SCAN_REQUEST_THROTTLE_MAX_IN_TIME_WINDOW_FG_APPS) {
            return true;
        }
        // Proceed with the scan request and record the time.
        scanRequestTimestamps.addLast(currentTimeMillis);
        return false;
    }

    /**
     * Checks if the scan request from a background app needs to be throttled.
     */
    private boolean shouldScanRequestBeThrottledForBackgroundApp() {
        long lastScanMs = mLastScanTimestampForBgApps;
        long elapsedRealtime = mClock.getElapsedSinceBootMillis();
        if (lastScanMs != 0
                && (elapsedRealtime - lastScanMs) < SCAN_REQUEST_THROTTLE_INTERVAL_BG_APPS_MS) {
            return true;
        }
        // Proceed with the scan request and record the time.
        mLastScanTimestampForBgApps = elapsedRealtime;
        return false;
    }

    /**
     * Check if the request comes from background app.
     */
    private boolean isRequestFromBackground(int callingUid, String packageName) {
        mAppOps.checkPackage(callingUid, packageName);
        // getPackageImportance requires PACKAGE_USAGE_STATS permission, so clearing the incoming
        // identity so the permission check can be done on system process where wifi runs in.
        long callingIdentity = Binder.clearCallingIdentity();
        // TODO(b/74970282): This try/catch block may not be necessary (here & above) because all
        // of these calls are already in WSM thread context (offloaded from app's binder thread).
        try {
            return mActivityManager.getPackageImportance(packageName)
                    > ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE;
        } finally {
            Binder.restoreCallingIdentity(callingIdentity);
        }
    }

    /**
     * Checks if the scan request from the app (specified by callingUid & packageName) needs
     * to be throttled.
     */
    private boolean shouldScanRequestBeThrottledForApp(int callingUid, String packageName) {
        boolean isThrottled;
        if (isRequestFromBackground(callingUid, packageName)) {
            isThrottled = shouldScanRequestBeThrottledForBackgroundApp();
            if (isThrottled) {
                if (mVerboseLoggingEnabled) {
                    Log.v(TAG, "Background scan app request [" + callingUid + ", "
                            + packageName + "]");
                }
                mWifiMetrics.incrementExternalBackgroundAppOneshotScanRequestsThrottledCount();
            }
        } else {
            isThrottled = shouldScanRequestBeThrottledForForegroundApp(callingUid, packageName);
            if (isThrottled) {
                if (mVerboseLoggingEnabled) {
                    Log.v(TAG, "Foreground scan app request [" + callingUid + ", "
                            + packageName + "]");
                }
                mWifiMetrics.incrementExternalForegroundAppOneshotScanRequestsThrottledCount();
            }
        }
        mWifiMetrics.incrementExternalAppOneshotScanRequestsCount();
        return isThrottled;
    }

    /**
     * Initiate a wifi scan.
     *
     * @param callingUid The uid initiating the wifi scan. Blame will be given to this uid.
     * @return true if the scan request was placed or a scan is already ongoing, false otherwise.
     */
    public boolean startScan(int callingUid, String packageName) {
        if (!retrieveWifiScannerIfNecessary()) {
            Log.e(TAG, "Failed to retrieve wifiscanner");
            sendScanResultFailureBroadcastToPackage(packageName);
            return false;
        }
        boolean fromSettingsOrSetupWizard =
                mWifiPermissionsUtil.checkNetworkSettingsPermission(callingUid)
                        || mWifiPermissionsUtil.checkNetworkSetupWizardPermission(callingUid);
        // Check and throttle scan request from apps without NETWORK_SETTINGS permission.
        if (!fromSettingsOrSetupWizard
                && shouldScanRequestBeThrottledForApp(callingUid, packageName)) {
            Log.i(TAG, "Scan request from " + packageName + " throttled");
            sendScanResultFailureBroadcastToPackage(packageName);
            return false;
        }
        // Create a worksource using the caller's UID.
        WorkSource workSource = new WorkSource(callingUid);

        // Create the scan settings.
        WifiScanner.ScanSettings settings = new WifiScanner.ScanSettings();
        // Scan requests from apps with network settings will be of high accuracy type.
        if (fromSettingsOrSetupWizard) {
            settings.type = WifiScanner.TYPE_HIGH_ACCURACY;
        }
        // always do full scans
        settings.band = WifiScanner.WIFI_BAND_BOTH_WITH_DFS;
        settings.reportEvents = WifiScanner.REPORT_EVENT_AFTER_EACH_SCAN
                | WifiScanner.REPORT_EVENT_FULL_SCAN_RESULT;
        if (mScanningForHiddenNetworksEnabled) {
            // retrieve the list of hidden network SSIDs to scan for, if enabled.
            List<WifiScanner.ScanSettings.HiddenNetwork> hiddenNetworkList =
                    mWifiConfigManager.retrieveHiddenNetworkList();
            settings.hiddenNetworks = hiddenNetworkList.toArray(
                    new WifiScanner.ScanSettings.HiddenNetwork[hiddenNetworkList.size()]);
        }
        mWifiScanner.startScan(settings, new ScanRequestProxyScanListener(), workSource);
        mIsScanProcessingComplete = false;
        return true;
    }

    /**
     * Return the results of the most recent access point scan, in the form of
     * a list of {@link ScanResult} objects.
     * @return the list of results
     */
    public List<ScanResult> getScanResults() {
        return mLastScanResults;
    }

    /**
     * Clear the stored scan results.
     */
    public void clearScanResults() {
        mLastScanResults.clear();
        mLastScanTimestampForBgApps = 0;
        mLastScanTimestampsForFgApps.clear();
    }

    /**
     * Clear any scan timestamps being stored for the app.
     *
     * @param uid Uid of the package.
     * @param packageName Name of the package.
     */
    public void clearScanRequestTimestampsForApp(@NonNull String packageName, int uid) {
        if (mVerboseLoggingEnabled) {
            Log.v(TAG, "Clearing scan request timestamps for uid=" + uid + ", packageName="
                    + packageName);
        }
        mLastScanTimestampsForFgApps.remove(Pair.create(uid, packageName));
    }
}
