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

import android.content.Context;
import android.net.wifi.WifiScanner;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;

import com.android.server.wifi.Clock;
import com.android.server.wifi.WifiMonitor;
import com.android.server.wifi.WifiNative;

import java.io.FileDescriptor;
import java.io.PrintWriter;

/**
 * WifiScanner implementation that takes advantage of the gscan HAL API
 * The gscan API is used to perform background scans and wificond is used for oneshot scans.
 * @see com.android.server.wifi.scanner.WifiScannerImpl for more details on each method.
 */
public class HalWifiScannerImpl extends WifiScannerImpl implements Handler.Callback {
    private static final String TAG = "HalWifiScannerImpl";
    private static final boolean DBG = false;

    private final WifiNative mWifiNative;
    private final ChannelHelper mChannelHelper;
    private final WificondScannerImpl mWificondScannerDelegate;

    public HalWifiScannerImpl(Context context, String ifaceName, WifiNative wifiNative,
                              WifiMonitor wifiMonitor, Looper looper, Clock clock) {
        super(ifaceName);
        mWifiNative = wifiNative;
        mChannelHelper = new WificondChannelHelper(wifiNative);
        mWificondScannerDelegate =
                new WificondScannerImpl(context, getIfaceName(), wifiNative, wifiMonitor,
                        mChannelHelper, looper, clock);
    }

    @Override
    public boolean handleMessage(Message msg) {
        Log.w(TAG, "Unknown message received: " + msg.what);
        return true;
    }

    @Override
    public void cleanup() {
        mWificondScannerDelegate.cleanup();
    }

    @Override
    public boolean getScanCapabilities(WifiNative.ScanCapabilities capabilities) {
        return mWifiNative.getBgScanCapabilities(
                getIfaceName(), capabilities);
    }

    @Override
    public ChannelHelper getChannelHelper() {
        return mChannelHelper;
    }

    public boolean startSingleScan(WifiNative.ScanSettings settings,
            WifiNative.ScanEventHandler eventHandler) {
        return mWificondScannerDelegate.startSingleScan(settings, eventHandler);
    }

    @Override
    public WifiScanner.ScanData getLatestSingleScanResults() {
        return mWificondScannerDelegate.getLatestSingleScanResults();
    }

    @Override
    public boolean startBatchedScan(WifiNative.ScanSettings settings,
            WifiNative.ScanEventHandler eventHandler) {
        if (settings == null || eventHandler == null) {
            Log.w(TAG, "Invalid arguments for startBatched: settings=" + settings
                    + ",eventHandler=" + eventHandler);
            return false;
        }
        return mWifiNative.startBgScan(
                getIfaceName(), settings, eventHandler);
    }

    @Override
    public void stopBatchedScan() {
        mWifiNative.stopBgScan(getIfaceName());
    }

    @Override
    public void pauseBatchedScan() {
        mWifiNative.pauseBgScan(getIfaceName());
    }

    @Override
    public void restartBatchedScan() {
        mWifiNative.restartBgScan(getIfaceName());
    }

    @Override
    public WifiScanner.ScanData[] getLatestBatchedScanResults(boolean flush) {
        return mWifiNative.getBgScanResults(getIfaceName());
    }

    @Override
    public boolean setHwPnoList(WifiNative.PnoSettings settings,
            WifiNative.PnoEventHandler eventHandler) {
        return mWificondScannerDelegate.setHwPnoList(settings, eventHandler);
    }

    @Override
    public boolean resetHwPnoList() {
        return mWificondScannerDelegate.resetHwPnoList();
    }

    @Override
    public boolean isHwPnoSupported(boolean isConnectedPno) {
        return mWificondScannerDelegate.isHwPnoSupported(isConnectedPno);
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        mWificondScannerDelegate.dump(fd, pw, args);
    }
}
