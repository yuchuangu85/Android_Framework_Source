/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.server.wifi.scanner;

import android.app.AlarmManager;
import android.content.Context;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiScanner;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;

import com.android.internal.R;
import com.android.server.wifi.Clock;
import com.android.server.wifi.ScanDetail;
import com.android.server.wifi.WifiMonitor;
import com.android.server.wifi.WifiNative;
import com.android.server.wifi.scanner.ChannelHelper.ChannelCollection;
import com.android.server.wifi.util.ScanResultUtil;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import javax.annotation.concurrent.GuardedBy;

/**
 * Implementation of the WifiScanner HAL API that uses wificond to perform all scans
 * @see com.android.server.wifi.scanner.WifiScannerImpl for more details on each method.
 */
public class WificondScannerImpl extends WifiScannerImpl implements Handler.Callback {
    private static final String TAG = "WificondScannerImpl";
    private static final boolean DBG = false;

    public static final String TIMEOUT_ALARM_TAG = TAG + " Scan Timeout";
    // Max number of networks that can be specified to wificond per scan request
    public static final int MAX_HIDDEN_NETWORK_IDS_PER_SCAN = 16;

    private static final int SCAN_BUFFER_CAPACITY = 10;
    private static final int MAX_APS_PER_SCAN = 32;
    private static final int MAX_SCAN_BUCKETS = 16;

    private final Context mContext;
    private final String mIfaceName;
    private final WifiNative mWifiNative;
    private final WifiMonitor mWifiMonitor;
    private final AlarmManager mAlarmManager;
    private final Handler mEventHandler;
    private final ChannelHelper mChannelHelper;
    private final Clock mClock;

    private final Object mSettingsLock = new Object();

    private ArrayList<ScanDetail> mNativeScanResults;
    private ArrayList<ScanDetail> mNativePnoScanResults;
    private WifiScanner.ScanData mLatestSingleScanResult =
            new WifiScanner.ScanData(0, 0, new ScanResult[0]);

    // Settings for the currently running single scan, null if no scan active
    private LastScanSettings mLastScanSettings = null;
    // Settings for the currently running pno scan, null if no scan active
    private LastPnoScanSettings mLastPnoScanSettings = null;

    private final boolean mHwPnoScanSupported;

    /**
     * Duration to wait before timing out a scan.
     *
     * The expected behavior is that the hardware will return a failed scan if it does not
     * complete, but timeout just in case it does not.
     */
    private static final long SCAN_TIMEOUT_MS = 15000;

    @GuardedBy("mSettingsLock")
    private AlarmManager.OnAlarmListener mScanTimeoutListener;

    public WificondScannerImpl(Context context, String ifaceName, WifiNative wifiNative,
                               WifiMonitor wifiMonitor, ChannelHelper channelHelper,
                               Looper looper, Clock clock) {
        mContext = context;
        mIfaceName = ifaceName;
        mWifiNative = wifiNative;
        mWifiMonitor = wifiMonitor;
        mChannelHelper = channelHelper;
        mAlarmManager = (AlarmManager) mContext.getSystemService(Context.ALARM_SERVICE);
        mEventHandler = new Handler(looper, this);
        mClock = clock;

        // Check if the device supports HW PNO scans.
        mHwPnoScanSupported = mContext.getResources().getBoolean(
                R.bool.config_wifi_background_scan_support);

        wifiMonitor.registerHandler(mIfaceName,
                WifiMonitor.SCAN_FAILED_EVENT, mEventHandler);
        wifiMonitor.registerHandler(mIfaceName,
                WifiMonitor.PNO_SCAN_RESULTS_EVENT, mEventHandler);
        wifiMonitor.registerHandler(mIfaceName,
                WifiMonitor.SCAN_RESULTS_EVENT, mEventHandler);
    }

    @Override
    public void cleanup() {
        synchronized (mSettingsLock) {
            stopHwPnoScan();
            mLastScanSettings = null; // finally clear any active scan
            mLastPnoScanSettings = null; // finally clear any active scan
            mWifiMonitor.deregisterHandler(mIfaceName,
                    WifiMonitor.SCAN_FAILED_EVENT, mEventHandler);
            mWifiMonitor.deregisterHandler(mIfaceName,
                    WifiMonitor.PNO_SCAN_RESULTS_EVENT, mEventHandler);
            mWifiMonitor.deregisterHandler(mIfaceName,
                    WifiMonitor.SCAN_RESULTS_EVENT, mEventHandler);
        }
    }

    @Override
    public boolean getScanCapabilities(WifiNative.ScanCapabilities capabilities) {
        capabilities.max_scan_cache_size = Integer.MAX_VALUE;
        capabilities.max_scan_buckets = MAX_SCAN_BUCKETS;
        capabilities.max_ap_cache_per_scan = MAX_APS_PER_SCAN;
        capabilities.max_rssi_sample_size = 8;
        capabilities.max_scan_reporting_threshold = SCAN_BUFFER_CAPACITY;
        return true;
    }

    @Override
    public ChannelHelper getChannelHelper() {
        return mChannelHelper;
    }

    @Override
    public boolean startSingleScan(WifiNative.ScanSettings settings,
            WifiNative.ScanEventHandler eventHandler) {
        if (eventHandler == null || settings == null) {
            Log.w(TAG, "Invalid arguments for startSingleScan: settings=" + settings
                    + ",eventHandler=" + eventHandler);
            return false;
        }
        synchronized (mSettingsLock) {
            if (mLastScanSettings != null) {
                Log.w(TAG, "A single scan is already running");
                return false;
            }

            ChannelCollection allFreqs = mChannelHelper.createChannelCollection();
            boolean reportFullResults = false;

            for (int i = 0; i < settings.num_buckets; ++i) {
                WifiNative.BucketSettings bucketSettings = settings.buckets[i];
                if ((bucketSettings.report_events
                                & WifiScanner.REPORT_EVENT_FULL_SCAN_RESULT) != 0) {
                    reportFullResults = true;
                }
                allFreqs.addChannels(bucketSettings);
            }

            List<String> hiddenNetworkSSIDSet = new ArrayList<>();
            if (settings.hiddenNetworks != null) {
                int numHiddenNetworks =
                        Math.min(settings.hiddenNetworks.length, MAX_HIDDEN_NETWORK_IDS_PER_SCAN);
                for (int i = 0; i < numHiddenNetworks; i++) {
                    hiddenNetworkSSIDSet.add(settings.hiddenNetworks[i].ssid);
                }
            }
            mLastScanSettings = new LastScanSettings(
                        mClock.getElapsedSinceBootMillis(),
                        reportFullResults, allFreqs, eventHandler);

            boolean success = false;
            Set<Integer> freqs;
            if (!allFreqs.isEmpty()) {
                freqs = allFreqs.getScanFreqs();
                success = mWifiNative.scan(
                        mIfaceName, settings.scanType, freqs, hiddenNetworkSSIDSet);
                if (!success) {
                    Log.e(TAG, "Failed to start scan, freqs=" + freqs);
                }
            } else {
                // There is a scan request but no available channels could be scanned for.
                // We regard it as a scan failure in this case.
                Log.e(TAG, "Failed to start scan because there is no available channel to scan");
            }
            if (success) {
                if (DBG) {
                    Log.d(TAG, "Starting wifi scan for freqs=" + freqs);
                }

                mScanTimeoutListener = new AlarmManager.OnAlarmListener() {
                    @Override public void onAlarm() {
                        handleScanTimeout();
                    }
                };

                mAlarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        mClock.getElapsedSinceBootMillis() + SCAN_TIMEOUT_MS,
                        TIMEOUT_ALARM_TAG, mScanTimeoutListener, mEventHandler);
            } else {
                // indicate scan failure async
                mEventHandler.post(new Runnable() {
                        @Override public void run() {
                            reportScanFailure();
                        }
                    });
            }

            return true;
        }
    }

    @Override
    public WifiScanner.ScanData getLatestSingleScanResults() {
        return mLatestSingleScanResult;
    }

    @Override
    public boolean startBatchedScan(WifiNative.ScanSettings settings,
            WifiNative.ScanEventHandler eventHandler) {
        Log.w(TAG, "startBatchedScan() is not supported");
        return false;
    }

    @Override
    public void stopBatchedScan() {
        Log.w(TAG, "stopBatchedScan() is not supported");
    }

    @Override
    public void pauseBatchedScan() {
        Log.w(TAG, "pauseBatchedScan() is not supported");
    }

    @Override
    public void restartBatchedScan() {
        Log.w(TAG, "restartBatchedScan() is not supported");
    }

    private void handleScanTimeout() {
        synchronized (mSettingsLock) {
            Log.e(TAG, "Timed out waiting for scan result from wificond");
            reportScanFailure();
            mScanTimeoutListener = null;
        }
    }

    @Override
    public boolean handleMessage(Message msg) {
        switch(msg.what) {
            case WifiMonitor.SCAN_FAILED_EVENT:
                Log.w(TAG, "Scan failed");
                cancelScanTimeout();
                reportScanFailure();
                break;
            case WifiMonitor.PNO_SCAN_RESULTS_EVENT:
                pollLatestScanDataForPno();
                break;
            case WifiMonitor.SCAN_RESULTS_EVENT:
                cancelScanTimeout();
                pollLatestScanData();
                break;
            default:
                // ignore unknown event
        }
        return true;
    }

    private void cancelScanTimeout() {
        synchronized (mSettingsLock) {
            if (mScanTimeoutListener != null) {
                mAlarmManager.cancel(mScanTimeoutListener);
                mScanTimeoutListener = null;
            }
        }
    }

    private void reportScanFailure() {
        synchronized (mSettingsLock) {
            if (mLastScanSettings != null) {
                if (mLastScanSettings.singleScanEventHandler != null) {
                    mLastScanSettings.singleScanEventHandler
                            .onScanStatus(WifiNative.WIFI_SCAN_FAILED);
                }
                mLastScanSettings = null;
            }
        }
    }

    private void reportPnoScanFailure() {
        synchronized (mSettingsLock) {
            if (mLastPnoScanSettings != null) {
                if (mLastPnoScanSettings.pnoScanEventHandler != null) {
                    mLastPnoScanSettings.pnoScanEventHandler.onPnoScanFailed();
                }
                // Clean up PNO state, we don't want to continue PNO scanning.
                mLastPnoScanSettings = null;
            }
        }
    }

    private void pollLatestScanDataForPno() {
        synchronized (mSettingsLock) {
            if (mLastPnoScanSettings == null) {
                 // got a scan before we started scanning or after scan was canceled
                return;
            }
            mNativePnoScanResults = mWifiNative.getPnoScanResults(mIfaceName);
            List<ScanResult> hwPnoScanResults = new ArrayList<>();
            int numFilteredScanResults = 0;
            for (int i = 0; i < mNativePnoScanResults.size(); ++i) {
                ScanResult result = mNativePnoScanResults.get(i).getScanResult();
                long timestamp_ms = result.timestamp / 1000; // convert us -> ms
                if (timestamp_ms > mLastPnoScanSettings.startTime) {
                    hwPnoScanResults.add(result);
                } else {
                    numFilteredScanResults++;
                }
            }

            if (numFilteredScanResults != 0) {
                Log.d(TAG, "Filtering out " + numFilteredScanResults + " pno scan results.");
            }

            if (mLastPnoScanSettings.pnoScanEventHandler != null) {
                ScanResult[] pnoScanResultsArray =
                        hwPnoScanResults.toArray(new ScanResult[hwPnoScanResults.size()]);
                mLastPnoScanSettings.pnoScanEventHandler.onPnoNetworkFound(pnoScanResultsArray);
            }
        }
    }

    /**
     * Return one of the WIFI_BAND_# values that was scanned for in this scan.
     */
    private static int getBandScanned(ChannelCollection channelCollection) {
        if (channelCollection.containsBand(WifiScanner.WIFI_BAND_BOTH_WITH_DFS)) {
            return WifiScanner.WIFI_BAND_BOTH_WITH_DFS;
        } else if (channelCollection.containsBand(WifiScanner.WIFI_BAND_BOTH)) {
            return WifiScanner.WIFI_BAND_BOTH;
        } else if (channelCollection.containsBand(WifiScanner.WIFI_BAND_5_GHZ_WITH_DFS)) {
            return WifiScanner.WIFI_BAND_5_GHZ_WITH_DFS;
        } else if (channelCollection.containsBand(WifiScanner.WIFI_BAND_5_GHZ)) {
            return WifiScanner.WIFI_BAND_5_GHZ;
        } else if (channelCollection.containsBand(WifiScanner.WIFI_BAND_5_GHZ_DFS_ONLY)) {
            return WifiScanner.WIFI_BAND_5_GHZ_DFS_ONLY;
        } else if (channelCollection.containsBand(WifiScanner.WIFI_BAND_24_GHZ)) {
            return WifiScanner.WIFI_BAND_24_GHZ;
        }
        return WifiScanner.WIFI_BAND_UNSPECIFIED;
    }

    private void pollLatestScanData() {
        synchronized (mSettingsLock) {
            if (mLastScanSettings == null) {
                 // got a scan before we started scanning or after scan was canceled
                return;
            }

            mNativeScanResults = mWifiNative.getScanResults(mIfaceName);
            List<ScanResult> singleScanResults = new ArrayList<>();
            int numFilteredScanResults = 0;
            for (int i = 0; i < mNativeScanResults.size(); ++i) {
                ScanResult result = mNativeScanResults.get(i).getScanResult();
                long timestamp_ms = result.timestamp / 1000; // convert us -> ms
                if (timestamp_ms > mLastScanSettings.startTime) {
                    if (mLastScanSettings.singleScanFreqs.containsChannel(
                                    result.frequency)) {
                        singleScanResults.add(result);
                    }
                } else {
                    numFilteredScanResults++;
                }
            }
            if (numFilteredScanResults != 0) {
                Log.d(TAG, "Filtering out " + numFilteredScanResults + " scan results.");
            }

            if (mLastScanSettings.singleScanEventHandler != null) {
                if (mLastScanSettings.reportSingleScanFullResults) {
                    for (ScanResult scanResult : singleScanResults) {
                        // ignore buckets scanned since there is only one bucket for a single scan
                        mLastScanSettings.singleScanEventHandler.onFullScanResult(scanResult,
                                /* bucketsScanned */ 0);
                    }
                }
                Collections.sort(singleScanResults, SCAN_RESULT_SORT_COMPARATOR);
                mLatestSingleScanResult = new WifiScanner.ScanData(0, 0, 0,
                        getBandScanned(mLastScanSettings.singleScanFreqs),
                        singleScanResults.toArray(new ScanResult[singleScanResults.size()]));
                mLastScanSettings.singleScanEventHandler
                        .onScanStatus(WifiNative.WIFI_SCAN_RESULTS_AVAILABLE);
            }

            mLastScanSettings = null;
        }
    }


    @Override
    public WifiScanner.ScanData[] getLatestBatchedScanResults(boolean flush) {
        return null;
    }

    private boolean startHwPnoScan(WifiNative.PnoSettings pnoSettings) {
        return mWifiNative.startPnoScan(mIfaceName, pnoSettings);
    }

    private void stopHwPnoScan() {
        mWifiNative.stopPnoScan(mIfaceName);
    }

    /**
     * Hw Pno Scan is required only for disconnected PNO when the device supports it.
     * @param isConnectedPno Whether this is connected PNO vs disconnected PNO.
     * @return true if HW PNO scan is required, false otherwise.
     */
    private boolean isHwPnoScanRequired(boolean isConnectedPno) {
        return (!isConnectedPno && mHwPnoScanSupported);
    }

    @Override
    public boolean setHwPnoList(WifiNative.PnoSettings settings,
            WifiNative.PnoEventHandler eventHandler) {
        synchronized (mSettingsLock) {
            if (mLastPnoScanSettings != null) {
                Log.w(TAG, "Already running a PNO scan");
                return false;
            }
            if (!isHwPnoScanRequired(settings.isConnected)) {
                return false;
            }

            if (startHwPnoScan(settings)) {
                mLastPnoScanSettings = new LastPnoScanSettings(
                            mClock.getElapsedSinceBootMillis(),
                            settings.networkList, eventHandler);

            } else {
                Log.e(TAG, "Failed to start PNO scan");
                reportPnoScanFailure();
            }
            return true;
        }
    }

    @Override
    public boolean resetHwPnoList() {
        synchronized (mSettingsLock) {
            if (mLastPnoScanSettings == null) {
                Log.w(TAG, "No PNO scan running");
                return false;
            }
            mLastPnoScanSettings = null;
            // For wificond based PNO, we stop the scan immediately when we reset pno list.
            stopHwPnoScan();
            return true;
        }
    }

    @Override
    public boolean isHwPnoSupported(boolean isConnectedPno) {
        // Hw Pno Scan is supported only for disconnected PNO when the device supports it.
        return isHwPnoScanRequired(isConnectedPno);
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        synchronized (mSettingsLock) {
            long nowMs = mClock.getElapsedSinceBootMillis();
            pw.println("Latest native scan results:");
            if (mNativeScanResults != null) {
                List<ScanResult> scanResults = mNativeScanResults.stream().map(r -> {
                    return r.getScanResult();
                }).collect(Collectors.toList());
                ScanResultUtil.dumpScanResults(pw, scanResults, nowMs);
            }
            pw.println("Latest native pno scan results:");
            if (mNativePnoScanResults != null) {
                List<ScanResult> pnoScanResults = mNativePnoScanResults.stream().map(r -> {
                    return r.getScanResult();
                }).collect(Collectors.toList());
                ScanResultUtil.dumpScanResults(pw, pnoScanResults, nowMs);
            }
        }
    }

    private static class LastScanSettings {
        LastScanSettings(long startTime,
                boolean reportSingleScanFullResults,
                ChannelCollection singleScanFreqs,
                WifiNative.ScanEventHandler singleScanEventHandler) {
            this.startTime = startTime;
            this.reportSingleScanFullResults = reportSingleScanFullResults;
            this.singleScanFreqs = singleScanFreqs;
            this.singleScanEventHandler = singleScanEventHandler;
        }

        public long startTime;
        public boolean reportSingleScanFullResults;
        public ChannelCollection singleScanFreqs;
        public WifiNative.ScanEventHandler singleScanEventHandler;

    }

    private static class LastPnoScanSettings {
        LastPnoScanSettings(long startTime,
                WifiNative.PnoNetwork[] pnoNetworkList,
                WifiNative.PnoEventHandler pnoScanEventHandler) {
            this.startTime = startTime;
            this.pnoNetworkList = pnoNetworkList;
            this.pnoScanEventHandler = pnoScanEventHandler;
        }

        public long startTime;
        public WifiNative.PnoNetwork[] pnoNetworkList;
        public WifiNative.PnoEventHandler pnoScanEventHandler;

    }

}
