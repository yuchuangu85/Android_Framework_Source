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

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Implementation of the WifiScanner HAL API that uses wificond to perform all scans
 * @see com.android.server.wifi.scanner.WifiScannerImpl for more details on each method.
 */
public class WificondScannerImpl extends WifiScannerImpl implements Handler.Callback {
    private static final String TAG = "WificondScannerImpl";
    private static final boolean DBG = false;

    public static final String BACKGROUND_PERIOD_ALARM_TAG = TAG + " Background Scan Period";
    public static final String TIMEOUT_ALARM_TAG = TAG + " Scan Timeout";
    // Max number of networks that can be specified to wificond per scan request
    public static final int MAX_HIDDEN_NETWORK_IDS_PER_SCAN = 16;

    private static final int SCAN_BUFFER_CAPACITY = 10;
    private static final int MAX_APS_PER_SCAN = 32;
    private static final int MAX_SCAN_BUCKETS = 16;

    private final Context mContext;
    private final WifiNative mWifiNative;
    private final AlarmManager mAlarmManager;
    private final Handler mEventHandler;
    private final ChannelHelper mChannelHelper;
    private final Clock mClock;

    private final Object mSettingsLock = new Object();

    // Next scan settings to apply when the previous scan completes
    private WifiNative.ScanSettings mPendingBackgroundScanSettings = null;
    private WifiNative.ScanEventHandler mPendingBackgroundScanEventHandler = null;
    private WifiNative.ScanSettings mPendingSingleScanSettings = null;
    private WifiNative.ScanEventHandler mPendingSingleScanEventHandler = null;

    // Active background scan settings/state
    private WifiNative.ScanSettings mBackgroundScanSettings = null;
    private WifiNative.ScanEventHandler mBackgroundScanEventHandler = null;
    private int mNextBackgroundScanPeriod = 0;
    private int mNextBackgroundScanId = 0;
    private boolean mBackgroundScanPeriodPending = false;
    private boolean mBackgroundScanPaused = false;
    private ScanBuffer mBackgroundScanBuffer = new ScanBuffer(SCAN_BUFFER_CAPACITY);

    private ArrayList<ScanDetail> mNativeScanResults;
    private WifiScanner.ScanData mLatestSingleScanResult =
            new WifiScanner.ScanData(0, 0, new ScanResult[0]);

    // Settings for the currently running scan, null if no scan active
    private LastScanSettings mLastScanSettings = null;

    // Pno related info.
    private WifiNative.PnoSettings mPnoSettings = null;
    private WifiNative.PnoEventHandler mPnoEventHandler;
    private final boolean mHwPnoScanSupported;
    private final HwPnoDebouncer mHwPnoDebouncer;
    private final HwPnoDebouncer.Listener mHwPnoDebouncerListener = new HwPnoDebouncer.Listener() {
        public void onPnoScanFailed() {
            Log.e(TAG, "Pno scan failure received");
            reportPnoScanFailure();
        }
    };

    /**
     * Duration to wait before timing out a scan.
     *
     * The expected behavior is that the hardware will return a failed scan if it does not
     * complete, but timeout just in case it does not.
     */
    private static final long SCAN_TIMEOUT_MS = 15000;

    AlarmManager.OnAlarmListener mScanPeriodListener = new AlarmManager.OnAlarmListener() {
            public void onAlarm() {
                synchronized (mSettingsLock) {
                    handleScanPeriod();
                }
            }
        };

    AlarmManager.OnAlarmListener mScanTimeoutListener = new AlarmManager.OnAlarmListener() {
            public void onAlarm() {
                synchronized (mSettingsLock) {
                    handleScanTimeout();
                }
            }
        };

    public WificondScannerImpl(Context context, WifiNative wifiNative,
                                     WifiMonitor wifiMonitor, ChannelHelper channelHelper,
                                     Looper looper, Clock clock) {
        mContext = context;
        mWifiNative = wifiNative;
        mChannelHelper = channelHelper;
        mAlarmManager = (AlarmManager) mContext.getSystemService(Context.ALARM_SERVICE);
        mEventHandler = new Handler(looper, this);
        mClock = clock;
        mHwPnoDebouncer = new HwPnoDebouncer(mWifiNative, mAlarmManager, mEventHandler, mClock);

        // Check if the device supports HW PNO scans.
        mHwPnoScanSupported = mContext.getResources().getBoolean(
                R.bool.config_wifi_background_scan_support);

        wifiMonitor.registerHandler(mWifiNative.getInterfaceName(),
                WifiMonitor.SCAN_FAILED_EVENT, mEventHandler);
        wifiMonitor.registerHandler(mWifiNative.getInterfaceName(),
                WifiMonitor.PNO_SCAN_RESULTS_EVENT, mEventHandler);
        wifiMonitor.registerHandler(mWifiNative.getInterfaceName(),
                WifiMonitor.SCAN_RESULTS_EVENT, mEventHandler);
    }

    public WificondScannerImpl(Context context, WifiNative wifiNative,
                                     WifiMonitor wifiMonitor, Looper looper, Clock clock) {
        // TODO get channel information from wificond.
        this(context, wifiNative, wifiMonitor, new NoBandChannelHelper(), looper, clock);
    }

    @Override
    public void cleanup() {
        synchronized (mSettingsLock) {
            mPendingSingleScanSettings = null;
            mPendingSingleScanEventHandler = null;
            stopHwPnoScan();
            stopBatchedScan();
            mLastScanSettings = null; // finally clear any active scan
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
        if (mPendingSingleScanSettings != null
                || (mLastScanSettings != null && mLastScanSettings.singleScanActive)) {
            Log.w(TAG, "A single scan is already running");
            return false;
        }
        synchronized (mSettingsLock) {
            mPendingSingleScanSettings = settings;
            mPendingSingleScanEventHandler = eventHandler;
            processPendingScans();
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
        if (settings == null || eventHandler == null) {
            Log.w(TAG, "Invalid arguments for startBatched: settings=" + settings
                    + ",eventHandler=" + eventHandler);
            return false;
        }

        if (settings.max_ap_per_scan < 0 || settings.max_ap_per_scan > MAX_APS_PER_SCAN) {
            return false;
        }
        if (settings.num_buckets < 0 || settings.num_buckets > MAX_SCAN_BUCKETS) {
            return false;
        }
        if (settings.report_threshold_num_scans < 0
                || settings.report_threshold_num_scans > SCAN_BUFFER_CAPACITY) {
            return false;
        }
        if (settings.report_threshold_percent < 0 || settings.report_threshold_percent > 100) {
            return false;
        }
        if (settings.base_period_ms <= 0) {
            return false;
        }
        for (int i = 0; i < settings.num_buckets; ++i) {
            WifiNative.BucketSettings bucket = settings.buckets[i];
            if (bucket.period_ms % settings.base_period_ms != 0) {
                return false;
            }
        }

        synchronized (mSettingsLock) {
            stopBatchedScan();
            if (DBG) {
                Log.d(TAG, "Starting scan num_buckets=" + settings.num_buckets + ", base_period="
                        + settings.base_period_ms + " ms");
            }
            mPendingBackgroundScanSettings = settings;
            mPendingBackgroundScanEventHandler = eventHandler;
            handleScanPeriod(); // Try to start scan immediately
            return true;
        }
    }

    @Override
    public void stopBatchedScan() {
        synchronized (mSettingsLock) {
            if (DBG) Log.d(TAG, "Stopping scan");
            mBackgroundScanSettings = null;
            mBackgroundScanEventHandler = null;
            mPendingBackgroundScanSettings = null;
            mPendingBackgroundScanEventHandler = null;
            mBackgroundScanPaused = false;
            mBackgroundScanPeriodPending = false;
            unscheduleScansLocked();
        }
        processPendingScans();
    }

    @Override
    public void pauseBatchedScan() {
        synchronized (mSettingsLock) {
            if (DBG) Log.d(TAG, "Pausing scan");
            // if there isn't a pending scan then make the current scan pending
            if (mPendingBackgroundScanSettings == null) {
                mPendingBackgroundScanSettings = mBackgroundScanSettings;
                mPendingBackgroundScanEventHandler = mBackgroundScanEventHandler;
            }
            mBackgroundScanSettings = null;
            mBackgroundScanEventHandler = null;
            mBackgroundScanPeriodPending = false;
            mBackgroundScanPaused = true;

            unscheduleScansLocked();

            WifiScanner.ScanData[] results = getLatestBatchedScanResults(/* flush = */ true);
            if (mPendingBackgroundScanEventHandler != null) {
                mPendingBackgroundScanEventHandler.onScanPaused(results);
            }
        }
        processPendingScans();
    }

    @Override
    public void restartBatchedScan() {
        synchronized (mSettingsLock) {
            if (DBG) Log.d(TAG, "Restarting scan");
            if (mPendingBackgroundScanEventHandler != null) {
                mPendingBackgroundScanEventHandler.onScanRestarted();
            }
            mBackgroundScanPaused = false;
            handleScanPeriod();
        }
    }

    private void unscheduleScansLocked() {
        mAlarmManager.cancel(mScanPeriodListener);
        if (mLastScanSettings != null) {
            mLastScanSettings.backgroundScanActive = false;
        }
    }

    private void handleScanPeriod() {
        synchronized (mSettingsLock) {
            mBackgroundScanPeriodPending = true;
            processPendingScans();
        }
    }

    private void handleScanTimeout() {
        Log.e(TAG, "Timed out waiting for scan result from wificond");
        reportScanFailure();
        processPendingScans();
    }

    private boolean isDifferentPnoScanSettings(LastScanSettings newScanSettings) {
        return (mLastScanSettings == null || !Arrays.equals(
                newScanSettings.pnoNetworkList, mLastScanSettings.pnoNetworkList));
    }

    private void processPendingScans() {
        synchronized (mSettingsLock) {
            // Wait for the active scan result to come back to reschedule other scans,
            // unless if HW pno scan is running. Hw PNO scans are paused it if there
            // are other pending scans,
            if (mLastScanSettings != null && !mLastScanSettings.hwPnoScanActive) {
                return;
            }

            ChannelCollection allFreqs = mChannelHelper.createChannelCollection();
            Set<String> hiddenNetworkSSIDSet = new HashSet<>();
            final LastScanSettings newScanSettings =
                    new LastScanSettings(mClock.getElapsedSinceBootMillis());

            // Update scan settings if there is a pending scan
            if (!mBackgroundScanPaused) {
                if (mPendingBackgroundScanSettings != null) {
                    mBackgroundScanSettings = mPendingBackgroundScanSettings;
                    mBackgroundScanEventHandler = mPendingBackgroundScanEventHandler;
                    mNextBackgroundScanPeriod = 0;
                    mPendingBackgroundScanSettings = null;
                    mPendingBackgroundScanEventHandler = null;
                    mBackgroundScanPeriodPending = true;
                }
                if (mBackgroundScanPeriodPending && mBackgroundScanSettings != null) {
                    int reportEvents = WifiScanner.REPORT_EVENT_NO_BATCH; // default to no batch
                    for (int bucket_id = 0; bucket_id < mBackgroundScanSettings.num_buckets;
                            ++bucket_id) {
                        WifiNative.BucketSettings bucket =
                                mBackgroundScanSettings.buckets[bucket_id];
                        if (mNextBackgroundScanPeriod % (bucket.period_ms
                                        / mBackgroundScanSettings.base_period_ms) == 0) {
                            if ((bucket.report_events
                                            & WifiScanner.REPORT_EVENT_AFTER_EACH_SCAN) != 0) {
                                reportEvents |= WifiScanner.REPORT_EVENT_AFTER_EACH_SCAN;
                            }
                            if ((bucket.report_events
                                            & WifiScanner.REPORT_EVENT_FULL_SCAN_RESULT) != 0) {
                                reportEvents |= WifiScanner.REPORT_EVENT_FULL_SCAN_RESULT;
                            }
                            // only no batch if all buckets specify it
                            if ((bucket.report_events
                                            & WifiScanner.REPORT_EVENT_NO_BATCH) == 0) {
                                reportEvents &= ~WifiScanner.REPORT_EVENT_NO_BATCH;
                            }

                            allFreqs.addChannels(bucket);
                        }
                    }
                    if (!allFreqs.isEmpty()) {
                        newScanSettings.setBackgroundScan(mNextBackgroundScanId++,
                                mBackgroundScanSettings.max_ap_per_scan, reportEvents,
                                mBackgroundScanSettings.report_threshold_num_scans,
                                mBackgroundScanSettings.report_threshold_percent);
                    }
                    mNextBackgroundScanPeriod++;
                    mBackgroundScanPeriodPending = false;
                    mAlarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                            mClock.getElapsedSinceBootMillis()
                                    + mBackgroundScanSettings.base_period_ms,
                            BACKGROUND_PERIOD_ALARM_TAG, mScanPeriodListener, mEventHandler);
                }
            }

            if (mPendingSingleScanSettings != null) {
                boolean reportFullResults = false;
                ChannelCollection singleScanFreqs = mChannelHelper.createChannelCollection();
                for (int i = 0; i < mPendingSingleScanSettings.num_buckets; ++i) {
                    WifiNative.BucketSettings bucketSettings =
                            mPendingSingleScanSettings.buckets[i];
                    if ((bucketSettings.report_events
                                    & WifiScanner.REPORT_EVENT_FULL_SCAN_RESULT) != 0) {
                        reportFullResults = true;
                    }
                    singleScanFreqs.addChannels(bucketSettings);
                    allFreqs.addChannels(bucketSettings);
                }
                newScanSettings.setSingleScan(reportFullResults, singleScanFreqs,
                        mPendingSingleScanEventHandler);

                WifiNative.HiddenNetwork[] hiddenNetworks =
                        mPendingSingleScanSettings.hiddenNetworks;
                if (hiddenNetworks != null) {
                    int numHiddenNetworks =
                            Math.min(hiddenNetworks.length, MAX_HIDDEN_NETWORK_IDS_PER_SCAN);
                    for (int i = 0; i < numHiddenNetworks; i++) {
                        hiddenNetworkSSIDSet.add(hiddenNetworks[i].ssid);
                    }
                }

                mPendingSingleScanSettings = null;
                mPendingSingleScanEventHandler = null;
            }

            if (newScanSettings.backgroundScanActive || newScanSettings.singleScanActive) {
                boolean success = false;
                Set<Integer> freqs;
                if (!allFreqs.isEmpty()) {
                    pauseHwPnoScan();
                    freqs = allFreqs.getScanFreqs();
                    success = mWifiNative.scan(freqs, hiddenNetworkSSIDSet);
                    if (!success) {
                        Log.e(TAG, "Failed to start scan, freqs=" + freqs);
                    }
                } else {
                    // There is a scan request but no available channels could be scanned for.
                    // We regard it as a scan failure in this case.
                    Log.e(TAG, "Failed to start scan because there is "
                            + "no available channel to scan for");
                }
                if (success) {
                    // TODO handle scan timeout
                    if (DBG) {
                        Log.d(TAG, "Starting wifi scan for freqs=" + freqs
                                + ", background=" + newScanSettings.backgroundScanActive
                                + ", single=" + newScanSettings.singleScanActive);
                    }
                    mLastScanSettings = newScanSettings;
                    mAlarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                            mClock.getElapsedSinceBootMillis() + SCAN_TIMEOUT_MS,
                            TIMEOUT_ALARM_TAG, mScanTimeoutListener, mEventHandler);
                } else {
                    // indicate scan failure async
                    mEventHandler.post(new Runnable() {
                            public void run() {
                                if (newScanSettings.singleScanEventHandler != null) {
                                    newScanSettings.singleScanEventHandler
                                            .onScanStatus(WifiNative.WIFI_SCAN_FAILED);
                                }
                            }
                        });
                    // TODO(b/27769665) background scans should be failed too if scans fail enough
                }
            } else if (isHwPnoScanRequired()) {
                newScanSettings.setHwPnoScan(mPnoSettings.networkList, mPnoEventHandler);
                boolean status;
                // If the PNO network list has changed from the previous request, ensure that
                // we bypass the debounce logic and restart PNO scan.
                if (isDifferentPnoScanSettings(newScanSettings)) {
                    status = restartHwPnoScan(mPnoSettings);
                } else {
                    status = startHwPnoScan(mPnoSettings);
                }
                if (status) {
                    mLastScanSettings = newScanSettings;
                } else {
                    Log.e(TAG, "Failed to start PNO scan");
                    // indicate scan failure async
                    mEventHandler.post(new Runnable() {
                        public void run() {
                            if (mPnoEventHandler != null) {
                                mPnoEventHandler.onPnoScanFailed();
                            }
                            // Clean up PNO state, we don't want to continue PNO scanning.
                            mPnoSettings = null;
                            mPnoEventHandler = null;
                        }
                    });
                }
            }
        }
    }

    @Override
    public boolean handleMessage(Message msg) {
        switch(msg.what) {
            case WifiMonitor.SCAN_FAILED_EVENT:
                Log.w(TAG, "Scan failed");
                mAlarmManager.cancel(mScanTimeoutListener);
                reportScanFailure();
                processPendingScans();
                break;
            case WifiMonitor.PNO_SCAN_RESULTS_EVENT:
                pollLatestScanDataForPno();
                processPendingScans();
                break;
            case WifiMonitor.SCAN_RESULTS_EVENT:
                mAlarmManager.cancel(mScanTimeoutListener);
                pollLatestScanData();
                processPendingScans();
                break;
            default:
                // ignore unknown event
        }
        return true;
    }

    private void reportScanFailure() {
        synchronized (mSettingsLock) {
            if (mLastScanSettings != null) {
                if (mLastScanSettings.singleScanEventHandler != null) {
                    mLastScanSettings.singleScanEventHandler
                            .onScanStatus(WifiNative.WIFI_SCAN_FAILED);
                }
                // TODO(b/27769665) background scans should be failed too if scans fail enough
                mLastScanSettings = null;
            }
        }
    }

    private void reportPnoScanFailure() {
        synchronized (mSettingsLock) {
            if (mLastScanSettings != null && mLastScanSettings.hwPnoScanActive) {
                if (mLastScanSettings.pnoScanEventHandler != null) {
                    mLastScanSettings.pnoScanEventHandler.onPnoScanFailed();
                }
                // Clean up PNO state, we don't want to continue PNO scanning.
                mPnoSettings = null;
                mPnoEventHandler = null;
                mLastScanSettings = null;
            }
        }
    }

    private void pollLatestScanDataForPno() {
        synchronized (mSettingsLock) {
            if (mLastScanSettings == null) {
                 // got a scan before we started scanning or after scan was canceled
                return;
            }
            mNativeScanResults = mWifiNative.getPnoScanResults();
            List<ScanResult> hwPnoScanResults = new ArrayList<>();
            int numFilteredScanResults = 0;
            for (int i = 0; i < mNativeScanResults.size(); ++i) {
                ScanResult result = mNativeScanResults.get(i).getScanResult();
                long timestamp_ms = result.timestamp / 1000; // convert us -> ms
                if (timestamp_ms > mLastScanSettings.startTime) {
                    if (mLastScanSettings.hwPnoScanActive) {
                        hwPnoScanResults.add(result);
                    }
                } else {
                    numFilteredScanResults++;
                }
            }

            if (numFilteredScanResults != 0) {
                Log.d(TAG, "Filtering out " + numFilteredScanResults + " pno scan results.");
            }

            if (mLastScanSettings.hwPnoScanActive
                    && mLastScanSettings.pnoScanEventHandler != null) {
                ScanResult[] pnoScanResultsArray =
                        hwPnoScanResults.toArray(new ScanResult[hwPnoScanResults.size()]);
                mLastScanSettings.pnoScanEventHandler.onPnoNetworkFound(pnoScanResultsArray);
            }
            // On pno scan result event, we are expecting a mLastScanSettings for pno scan.
            // However, if unlikey mLastScanSettings is for single scan, we need this part
            // to protect from leaving WifiSingleScanStateMachine in a forever wait state.
            if (mLastScanSettings.singleScanActive
                    && mLastScanSettings.singleScanEventHandler != null) {
                Log.w(TAG, "Polling pno scan result when single scan is active, reporting"
                        + " single scan failure");
                mLastScanSettings.singleScanEventHandler
                        .onScanStatus(WifiNative.WIFI_SCAN_FAILED);
            }
            // mLastScanSettings is for either single/batched scan or pno scan.
            // We can safely set it to null when pno scan finishes.
            mLastScanSettings = null;
        }
    }

    /**
     * Check if the provided channel collection contains all the channels.
     */
    private static boolean isAllChannelsScanned(ChannelCollection channelCollection) {
        // TODO(b/62253332): Get rid of this hack.
        // We're treating 2g + 5g and 2g + 5g + dfs as all channels scanned to work around
        // the lack of a proper cache.
        return (channelCollection.containsBand(WifiScanner.WIFI_BAND_24_GHZ)
                && channelCollection.containsBand(WifiScanner.WIFI_BAND_5_GHZ));
    }

    private void pollLatestScanData() {
        synchronized (mSettingsLock) {
            if (mLastScanSettings == null) {
                 // got a scan before we started scanning or after scan was canceled
                return;
            }

            if (DBG) Log.d(TAG, "Polling scan data for scan: " + mLastScanSettings.scanId);
            mNativeScanResults = mWifiNative.getScanResults();
            List<ScanResult> singleScanResults = new ArrayList<>();
            List<ScanResult> backgroundScanResults = new ArrayList<>();
            int numFilteredScanResults = 0;
            for (int i = 0; i < mNativeScanResults.size(); ++i) {
                ScanResult result = mNativeScanResults.get(i).getScanResult();
                long timestamp_ms = result.timestamp / 1000; // convert us -> ms
                if (timestamp_ms > mLastScanSettings.startTime) {
                    if (mLastScanSettings.backgroundScanActive) {
                        backgroundScanResults.add(result);
                    }
                    if (mLastScanSettings.singleScanActive
                            && mLastScanSettings.singleScanFreqs.containsChannel(
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

            if (mLastScanSettings.backgroundScanActive) {
                if (mBackgroundScanEventHandler != null) {
                    if ((mLastScanSettings.reportEvents
                                    & WifiScanner.REPORT_EVENT_FULL_SCAN_RESULT) != 0) {
                        for (ScanResult scanResult : backgroundScanResults) {
                            // TODO(b/27506257): Fill in correct bucketsScanned value
                            mBackgroundScanEventHandler.onFullScanResult(scanResult, 0);
                        }
                    }
                }

                Collections.sort(backgroundScanResults, SCAN_RESULT_SORT_COMPARATOR);
                ScanResult[] scanResultsArray = new ScanResult[Math.min(mLastScanSettings.maxAps,
                            backgroundScanResults.size())];
                for (int i = 0; i < scanResultsArray.length; ++i) {
                    scanResultsArray[i] = backgroundScanResults.get(i);
                }

                if ((mLastScanSettings.reportEvents & WifiScanner.REPORT_EVENT_NO_BATCH) == 0) {
                    // TODO(b/27506257): Fill in correct bucketsScanned value
                    mBackgroundScanBuffer.add(new WifiScanner.ScanData(mLastScanSettings.scanId, 0,
                                    scanResultsArray));
                }

                if (mBackgroundScanEventHandler != null) {
                    if ((mLastScanSettings.reportEvents
                                    & WifiScanner.REPORT_EVENT_FULL_SCAN_RESULT) != 0
                            || (mLastScanSettings.reportEvents
                                    & WifiScanner.REPORT_EVENT_AFTER_EACH_SCAN) != 0
                            || (mLastScanSettings.reportEvents
                                    == WifiScanner.REPORT_EVENT_AFTER_BUFFER_FULL
                                    && (mBackgroundScanBuffer.size()
                                            >= (mBackgroundScanBuffer.capacity()
                                                    * mLastScanSettings.reportPercentThreshold
                                                    / 100)
                                            || mBackgroundScanBuffer.size()
                                            >= mLastScanSettings.reportNumScansThreshold))) {
                        mBackgroundScanEventHandler
                                .onScanStatus(WifiNative.WIFI_SCAN_RESULTS_AVAILABLE);
                    }
                }
            }

            if (mLastScanSettings.singleScanActive
                    && mLastScanSettings.singleScanEventHandler != null) {
                if (mLastScanSettings.reportSingleScanFullResults) {
                    for (ScanResult scanResult : singleScanResults) {
                        // ignore buckets scanned since there is only one bucket for a single scan
                        mLastScanSettings.singleScanEventHandler.onFullScanResult(scanResult,
                                /* bucketsScanned */ 0);
                    }
                }
                Collections.sort(singleScanResults, SCAN_RESULT_SORT_COMPARATOR);
                mLatestSingleScanResult = new WifiScanner.ScanData(mLastScanSettings.scanId, 0, 0,
                        isAllChannelsScanned(mLastScanSettings.singleScanFreqs),
                        singleScanResults.toArray(new ScanResult[singleScanResults.size()]));
                mLastScanSettings.singleScanEventHandler
                        .onScanStatus(WifiNative.WIFI_SCAN_RESULTS_AVAILABLE);
            }

            mLastScanSettings = null;
        }
    }


    @Override
    public WifiScanner.ScanData[] getLatestBatchedScanResults(boolean flush) {
        synchronized (mSettingsLock) {
            WifiScanner.ScanData[] results = mBackgroundScanBuffer.get();
            if (flush) {
                mBackgroundScanBuffer.clear();
            }
            return results;
        }
    }

    private boolean startHwPnoScan(WifiNative.PnoSettings pnoSettings) {
        return mHwPnoDebouncer.startPnoScan(pnoSettings, mHwPnoDebouncerListener);
    }

    private void stopHwPnoScan() {
        mHwPnoDebouncer.stopPnoScan();
    }

    private void pauseHwPnoScan() {
        mHwPnoDebouncer.forceStopPnoScan();
    }

    private boolean restartHwPnoScan(WifiNative.PnoSettings pnoSettings) {
        mHwPnoDebouncer.forceStopPnoScan();
        return mHwPnoDebouncer.startPnoScan(pnoSettings, mHwPnoDebouncerListener);
    }

    /**
     * Hw Pno Scan is required only for disconnected PNO when the device supports it.
     * @param isConnectedPno Whether this is connected PNO vs disconnected PNO.
     * @return true if HW PNO scan is required, false otherwise.
     */
    private boolean isHwPnoScanRequired(boolean isConnectedPno) {
        return (!isConnectedPno & mHwPnoScanSupported);
    }

    private boolean isHwPnoScanRequired() {
        if (mPnoSettings == null) return false;
        return isHwPnoScanRequired(mPnoSettings.isConnected);
    }

    @Override
    public boolean setHwPnoList(WifiNative.PnoSettings settings,
            WifiNative.PnoEventHandler eventHandler) {
        synchronized (mSettingsLock) {
            if (mPnoSettings != null) {
                Log.w(TAG, "Already running a PNO scan");
                return false;
            }
            mPnoEventHandler = eventHandler;
            mPnoSettings = settings;

            // For wificond based PNO, we start the scan immediately when we set pno list.
            processPendingScans();
            return true;
        }
    }

    @Override
    public boolean resetHwPnoList() {
        synchronized (mSettingsLock) {
            if (mPnoSettings == null) {
                Log.w(TAG, "No PNO scan running");
                return false;
            }
            mPnoEventHandler = null;
            mPnoSettings = null;
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
    public boolean shouldScheduleBackgroundScanForHwPno() {
        return false;
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        synchronized (mSettingsLock) {
            pw.println("Latest native scan results:");
            if (mNativeScanResults != null && mNativeScanResults.size() != 0) {
                long nowMs = mClock.getElapsedSinceBootMillis();
                pw.println("    BSSID              Frequency  RSSI  Age(sec)   SSID "
                        + "                                Flags");
                for (ScanDetail scanDetail : mNativeScanResults) {
                    ScanResult r = scanDetail.getScanResult();
                    long timeStampMs = r.timestamp / 1000;
                    String age;
                    if (timeStampMs <= 0) {
                        age = "___?___";
                    } else if (nowMs < timeStampMs) {
                        age = "  0.000";
                    } else if (timeStampMs < nowMs - 1000000) {
                        age = ">1000.0";
                    } else {
                        age = String.format("%3.3f", (nowMs - timeStampMs) / 1000.0);
                    }
                    String ssid = r.SSID == null ? "" : r.SSID;
                    pw.printf("  %17s  %9d  %5d   %7s    %-32s  %s\n",
                              r.BSSID,
                              r.frequency,
                              r.level,
                              age,
                              String.format("%1.32s", ssid),
                              r.capabilities);
                }
            }
        }
    }

    private static class LastScanSettings {
        public long startTime;

        LastScanSettings(long startTime) {
            this.startTime = startTime;
        }

        // Background settings
        public boolean backgroundScanActive = false;
        public int scanId;
        public int maxAps;
        public int reportEvents;
        public int reportNumScansThreshold;
        public int reportPercentThreshold;

        public void setBackgroundScan(int scanId, int maxAps, int reportEvents,
                int reportNumScansThreshold, int reportPercentThreshold) {
            this.backgroundScanActive = true;
            this.scanId = scanId;
            this.maxAps = maxAps;
            this.reportEvents = reportEvents;
            this.reportNumScansThreshold = reportNumScansThreshold;
            this.reportPercentThreshold = reportPercentThreshold;
        }

        // Single scan settings
        public boolean singleScanActive = false;
        public boolean reportSingleScanFullResults;
        public ChannelCollection singleScanFreqs;
        public WifiNative.ScanEventHandler singleScanEventHandler;

        public void setSingleScan(boolean reportSingleScanFullResults,
                ChannelCollection singleScanFreqs,
                WifiNative.ScanEventHandler singleScanEventHandler) {
            singleScanActive = true;
            this.reportSingleScanFullResults = reportSingleScanFullResults;
            this.singleScanFreqs = singleScanFreqs;
            this.singleScanEventHandler = singleScanEventHandler;
        }

        public boolean hwPnoScanActive = false;
        public WifiNative.PnoNetwork[] pnoNetworkList;
        public WifiNative.PnoEventHandler pnoScanEventHandler;

        public void setHwPnoScan(
                WifiNative.PnoNetwork[] pnoNetworkList,
                WifiNative.PnoEventHandler pnoScanEventHandler) {
            hwPnoScanActive = true;
            this.pnoNetworkList = pnoNetworkList;
            this.pnoScanEventHandler = pnoScanEventHandler;
        }
    }


    private static class ScanBuffer {
        private final ArrayDeque<WifiScanner.ScanData> mBuffer;
        private int mCapacity;

        ScanBuffer(int capacity) {
            mCapacity = capacity;
            mBuffer = new ArrayDeque<>(mCapacity);
        }

        public int size() {
            return mBuffer.size();
        }

        public int capacity() {
            return mCapacity;
        }

        public boolean isFull() {
            return size() == mCapacity;
        }

        public void add(WifiScanner.ScanData scanData) {
            if (isFull()) {
                mBuffer.pollFirst();
            }
            mBuffer.offerLast(scanData);
        }

        public void clear() {
            mBuffer.clear();
        }

        public WifiScanner.ScanData[] get() {
            return mBuffer.toArray(new WifiScanner.ScanData[mBuffer.size()]);
        }
    }

    /**
     * HW PNO Debouncer is used to debounce PNO requests. This guards against toggling the PNO
     * state too often which is not handled very well by some drivers.
     * Note: This is not thread safe!
     */
    public static class HwPnoDebouncer {
        public static final String PNO_DEBOUNCER_ALARM_TAG = TAG + "Pno Monitor";
        private static final int MINIMUM_PNO_GAP_MS = 5 * 1000;

        private final WifiNative mWifiNative;
        private final AlarmManager mAlarmManager;
        private final Handler mEventHandler;
        private final Clock mClock;
        private long mLastPnoChangeTimeStamp = -1L;
        private boolean mExpectedPnoState = false;
        private boolean mCurrentPnoState = false;;
        private boolean mWaitForTimer = false;
        private Listener mListener;
        private WifiNative.PnoSettings mPnoSettings;

        /**
         * Interface used to indicate PNO scan notifications.
         */
        public interface Listener {
            /**
             * Used to indicate a delayed PNO scan request failure.
             */
            void onPnoScanFailed();
        }

        public HwPnoDebouncer(WifiNative wifiNative, AlarmManager alarmManager,
                Handler eventHandler, Clock clock) {
            mWifiNative = wifiNative;
            mAlarmManager = alarmManager;
            mEventHandler = eventHandler;
            mClock = clock;
        }

        /**
         * Enable PNO state in wificond
         */
        private boolean startPnoScanInternal() {
            if (mCurrentPnoState) {
                if (DBG) Log.d(TAG, "PNO state is already enable");
                return true;
            }
            if (mPnoSettings == null) {
                Log.e(TAG, "PNO state change to enable failed, no available Pno settings");
                return false;
            }
            mLastPnoChangeTimeStamp = mClock.getElapsedSinceBootMillis();
            Log.d(TAG, "Remove all networks from supplicant before starting PNO scan");
            mWifiNative.removeAllNetworks();
            if (mWifiNative.startPnoScan(mPnoSettings)) {
                Log.d(TAG, "Changed PNO state from " + mCurrentPnoState + " to enable");
                mCurrentPnoState = true;
                return true;
            } else {
                Log.e(TAG, "PNO state change to enable failed");
                mCurrentPnoState = false;
            }
            return false;
        }

        /**
         * Disable PNO state in wificond
         */
        private boolean stopPnoScanInternal() {
            if (!mCurrentPnoState) {
                if (DBG) Log.d(TAG, "PNO state is already disable");
                return true;
            }
            mLastPnoChangeTimeStamp = mClock.getElapsedSinceBootMillis();
            if (mWifiNative.stopPnoScan()) {
                Log.d(TAG, "Changed PNO state from " + mCurrentPnoState + " to disable");
                mCurrentPnoState = false;
                return true;
            } else {
                Log.e(TAG, "PNO state change to disable failed");
                mCurrentPnoState = false;
            }
            return false;
        }

        private final AlarmManager.OnAlarmListener mAlarmListener =
                new AlarmManager.OnAlarmListener() {
            public void onAlarm() {
                if (DBG) Log.d(TAG, "PNO timer expired, expected state " + mExpectedPnoState);
                if (mExpectedPnoState) {
                    if (!startPnoScanInternal()) {
                        if (mListener != null) {
                            mListener.onPnoScanFailed();
                        }
                    }
                } else {
                    stopPnoScanInternal();
                }
                mWaitForTimer = false;
            }
        };

        /**
         * Enable/Disable PNO state. This method will debounce PNO scan requests.
         * @param enable boolean indicating whether PNO is being enabled or disabled.
         */
        private boolean setPnoState(boolean enable) {
            boolean isSuccess = true;
            mExpectedPnoState = enable;
            if (!mWaitForTimer) {
                long timeDifference = mClock.getElapsedSinceBootMillis() - mLastPnoChangeTimeStamp;
                if (timeDifference >= MINIMUM_PNO_GAP_MS) {
                    if (enable) {
                        isSuccess = startPnoScanInternal();
                    } else {
                        isSuccess = stopPnoScanInternal();
                    }
                } else {
                    long alarmTimeout = MINIMUM_PNO_GAP_MS - timeDifference;
                    Log.d(TAG, "Start PNO timer with delay " + alarmTimeout);
                    mAlarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                            mClock.getElapsedSinceBootMillis() + alarmTimeout,
                            PNO_DEBOUNCER_ALARM_TAG,
                            mAlarmListener, mEventHandler);
                    mWaitForTimer = true;
                }
            }
            return isSuccess;
        }

        /**
         * Start PNO scan
         */
        public boolean startPnoScan(WifiNative.PnoSettings pnoSettings, Listener listener) {
            if (DBG) Log.d(TAG, "Starting PNO scan");
            mListener = listener;
            mPnoSettings = pnoSettings;
            if (!setPnoState(true)) {
                mListener = null;
                return false;
            }
            return true;
        }

        /**
         * Stop PNO scan
         */
        public void stopPnoScan() {
            if (DBG) Log.d(TAG, "Stopping PNO scan");
            setPnoState(false);
            mListener = null;
        }

        /**
         * Force stop PNO scanning. This method will bypass the debounce logic and stop PNO
         * scan immediately.
         */
        public void forceStopPnoScan() {
            if (DBG) Log.d(TAG, "Force stopping Pno scan");
            // Cancel the debounce timer and stop PNO scan.
            if (mWaitForTimer) {
                mAlarmManager.cancel(mAlarmListener);
                mWaitForTimer = false;
            }
            stopPnoScanInternal();
        }
    }
}
