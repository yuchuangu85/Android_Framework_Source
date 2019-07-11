/*
 * Copyright (C) 2008 The Android Open Source Project
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

import android.Manifest;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.IWifiScanner;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiScanner;
import android.net.wifi.WifiScanner.BssidInfo;
import android.net.wifi.WifiScanner.ChannelSpec;
import android.net.wifi.WifiScanner.ScanData;
import android.net.wifi.WifiScanner.ScanSettings;
import android.net.wifi.WifiSsid;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.WorkSource;
import android.util.LocalLog;
import android.util.Log;

import com.android.internal.app.IBatteryStats;
import com.android.internal.util.AsyncChannel;
import com.android.internal.util.Protocol;
import com.android.internal.util.StateMachine;
import com.android.internal.util.State;
import com.android.server.am.BatteryStatsService;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;

public class WifiScanningServiceImpl extends IWifiScanner.Stub {

    private static final String TAG = "WifiScanningService";
    private static final boolean DBG = false;
    private static final boolean VDBG = false;

    private static final int INVALID_KEY = 0;                               // same as WifiScanner
    private static final int MIN_PERIOD_PER_CHANNEL_MS = 200;               // DFS needs 120 ms
    private static final int UNKNOWN_PID = -1;

    /**
     * Timeout for acquired wake lock while a scan is running
     */
    private static final int SCAN_WAKE_LOCK_TIME_OUT_MSECS = 5 * 1000;

    private static final LocalLog mLocalLog = new LocalLog(1024);

    private static void localLog(String message) {
        mLocalLog.log(message);
    }

    private static void logw(String message) {
        Log.w(TAG, message);
        mLocalLog.log(message);
    }

    private static void loge(String message) {
        Log.e(TAG, message);
        mLocalLog.log(message);
    }

    @Override
    public Messenger getMessenger() {
        if (mClientHandler != null) {
            return new Messenger(mClientHandler);
        } else {
            loge("WifiScanningServiceImpl trying to get messenger w/o initialization");
            return null;
        }
    }

    @Override
    public Bundle getAvailableChannels(int band) {
        ChannelSpec channelSpecs[] = getChannelsForBand(band);
        ArrayList<Integer> list = new ArrayList<Integer>(channelSpecs.length);
        for (ChannelSpec channelSpec : channelSpecs) {
            list.add(channelSpec.frequency);
        }
        Bundle b = new Bundle();
        b.putIntegerArrayList(WifiScanner.GET_AVAILABLE_CHANNELS_EXTRA, list);
        return b;
    }

    private void enforceLocationHardwarePermission(int uid) {
        mContext.enforcePermission(
                Manifest.permission.LOCATION_HARDWARE,
                UNKNOWN_PID, uid,
                "LocationHardware");
    }

    private class ClientHandler extends Handler {

        ClientHandler(android.os.Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {

            if (DBG) localLog("ClientHandler got" + msg);

            switch (msg.what) {

                case AsyncChannel.CMD_CHANNEL_HALF_CONNECTED:
                    if (msg.arg1 != AsyncChannel.STATUS_SUCCESSFUL) {
                        loge("Client connection failure, error=" + msg.arg1);
                    }
                    return;
                case AsyncChannel.CMD_CHANNEL_FULL_CONNECTION:
                    AsyncChannel ac = new AsyncChannel();
                    ac.connect(mContext, this, msg.replyTo);
                    if (DBG) localLog("New client connected : " + msg.sendingUid + msg.replyTo);
                    ClientInfo cInfo = new ClientInfo(msg.sendingUid, ac, msg.replyTo);
                    mClients.put(msg.replyTo, cInfo);
                    return;
                case AsyncChannel.CMD_CHANNEL_DISCONNECTED:
                    if (msg.arg1 == AsyncChannel.STATUS_SEND_UNSUCCESSFUL) {
                        loge("Send failed, client connection lost");
                    } else {
                        if (DBG) localLog("Client connection lost with reason: " + msg.arg1);
                    }
                    if (DBG) localLog("closing client " + msg.replyTo);
                    ClientInfo ci = mClients.remove(msg.replyTo);
                    if (ci != null) {                       /* can be null if send failed above */
                        if (DBG) localLog("closing client " + ci.mUid);
                        ci.cleanup();
                    }
                    return;
            }

            try {
                enforceLocationHardwarePermission(msg.sendingUid);
            } catch (SecurityException e) {
                localLog("failed to authorize app: " + e);
                replyFailed(msg, WifiScanner.REASON_NOT_AUTHORIZED, "Not authorized");
                return;
            }

            if (msg.what == WifiScanner.CMD_GET_SCAN_RESULTS) {
                mStateMachine.sendMessage(Message.obtain(msg));
                return;
            }
            ClientInfo ci = mClients.get(msg.replyTo);
            if (ci == null) {
                loge("Could not find client info for message " + msg.replyTo);
                replyFailed(msg, WifiScanner.REASON_INVALID_LISTENER, "Could not find listener");
                return;
            }

            int validCommands[] = {
                    WifiScanner.CMD_SCAN,
                    WifiScanner.CMD_START_BACKGROUND_SCAN,
                    WifiScanner.CMD_STOP_BACKGROUND_SCAN,
                    WifiScanner.CMD_START_SINGLE_SCAN,
                    WifiScanner.CMD_STOP_SINGLE_SCAN,
                    WifiScanner.CMD_SET_HOTLIST,
                    WifiScanner.CMD_RESET_HOTLIST,
                    WifiScanner.CMD_CONFIGURE_WIFI_CHANGE,
                    WifiScanner.CMD_START_TRACKING_CHANGE,
                    WifiScanner.CMD_STOP_TRACKING_CHANGE };

            for (int cmd : validCommands) {
                if (cmd == msg.what) {
                    mStateMachine.sendMessage(Message.obtain(msg));
                    return;
                }
            }

            replyFailed(msg, WifiScanner.REASON_INVALID_REQUEST, "Invalid request");
        }
    }

    private static final int BASE = Protocol.BASE_WIFI_SCANNER_SERVICE;

    private static final int CMD_SCAN_RESULTS_AVAILABLE              = BASE + 0;
    private static final int CMD_FULL_SCAN_RESULTS                   = BASE + 1;
    private static final int CMD_HOTLIST_AP_FOUND                    = BASE + 2;
    private static final int CMD_HOTLIST_AP_LOST                     = BASE + 3;
    private static final int CMD_WIFI_CHANGE_DETECTED                = BASE + 4;
    private static final int CMD_WIFI_CHANGES_STABILIZED             = BASE + 5;
    private static final int CMD_DRIVER_LOADED                       = BASE + 6;
    private static final int CMD_DRIVER_UNLOADED                     = BASE + 7;
    private static final int CMD_SCAN_PAUSED                         = BASE + 8;
    private static final int CMD_SCAN_RESTARTED                      = BASE + 9;
    private static final int CMD_STOP_SCAN_INTERNAL                  = BASE + 10;

    private Context mContext;
    private WifiScanningStateMachine mStateMachine;
    private ClientHandler mClientHandler;
    private IBatteryStats mBatteryStats;
    private PowerManager mPowerManager;
    private final WifiNative.ScanCapabilities mScanCapabilities = new WifiNative.ScanCapabilities();

    WifiScanningServiceImpl() { }

    WifiScanningServiceImpl(Context context) {
        mContext = context;
    }

    public void startService(Context context) {
        mContext = context;
        mPowerManager = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
        mBatteryStats = BatteryStatsService.getService();

        HandlerThread thread = new HandlerThread("WifiScanningService");
        thread.start();

        mClientHandler = new ClientHandler(thread.getLooper());
        mStateMachine = new WifiScanningStateMachine(thread.getLooper());
        mWifiChangeStateMachine = new WifiChangeStateMachine(thread.getLooper(), mPowerManager);

        mContext.registerReceiver(
                new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        int state = intent.getIntExtra(
                                WifiManager.EXTRA_SCAN_AVAILABLE, WifiManager.WIFI_STATE_DISABLED);
                        if (DBG) localLog("SCAN_AVAILABLE : " + state);
                        if (state == WifiManager.WIFI_STATE_ENABLED) {
                            mStateMachine.sendMessage(CMD_DRIVER_LOADED);
                        } else if (state == WifiManager.WIFI_STATE_DISABLED) {
                            mStateMachine.sendMessage(CMD_DRIVER_UNLOADED);
                        }
                    }
                }, new IntentFilter(WifiManager.WIFI_SCAN_AVAILABLE));

        mStateMachine.start();
        mWifiChangeStateMachine.start();
    }

    class WifiScanningStateMachine extends StateMachine implements WifiNative.ScanEventHandler,
            WifiNative.HotlistEventHandler, WifiNative.SignificantWifiChangeEventHandler {

        private final DefaultState mDefaultState = new DefaultState();
        private final StartedState mStartedState = new StartedState();
        private final PausedState  mPausedState  = new PausedState();

        public WifiScanningStateMachine(Looper looper) {
            super(TAG, looper);

            setLogRecSize(512);
            setLogOnlyTransitions(false);
            // setDbg(DBG);

            addState(mDefaultState);
                addState(mStartedState, mDefaultState);
                addState(mPausedState, mDefaultState);

            setInitialState(mDefaultState);
        }

        @Override
        public void onScanResultsAvailable() {
            if (DBG) localLog("onScanResultAvailable event received");
            sendMessage(CMD_SCAN_RESULTS_AVAILABLE);
        }

        @Override
        public void onScanStatus() {
            if (DBG) localLog("onScanStatus event received");
            sendMessage(CMD_SCAN_RESULTS_AVAILABLE);
        }

        @Override
        public void onFullScanResult(ScanResult fullScanResult) {
            if (DBG) localLog("Full scanresult received");
            sendMessage(CMD_FULL_SCAN_RESULTS, 0, 0, fullScanResult);
        }

        @Override
        public void onScanPaused(ScanData scanData[]) {
            sendMessage(CMD_SCAN_PAUSED, scanData);
        }

        @Override
        public void onScanRestarted() {
            if (DBG) localLog("onScanRestarted() event received");
            sendMessage(CMD_SCAN_RESTARTED);
        }

        @Override
        public void onHotlistApFound(ScanResult[] results) {
            if (DBG) localLog("HotlistApFound event received");
            sendMessage(CMD_HOTLIST_AP_FOUND, 0, 0, results);
        }

        @Override
        public void onHotlistApLost(ScanResult[] results) {
            if (DBG) localLog("HotlistApLost event received");
            sendMessage(CMD_HOTLIST_AP_LOST, 0, 0, results);
        }

        @Override
        public void onChangesFound(ScanResult[] results) {
            if (DBG) localLog("onWifiChangesFound event received");
            sendMessage(CMD_WIFI_CHANGE_DETECTED, 0, 0, results);
        }

        class DefaultState extends State {
            @Override
            public void enter() {
                if (DBG) localLog("DefaultState");
            }
            @Override
            public boolean processMessage(Message msg) {

                if (DBG) localLog("DefaultState got" + msg);

                ClientInfo ci = mClients.get(msg.replyTo);

                switch (msg.what) {
                    case CMD_DRIVER_LOADED:
                        if (WifiNative.getInterfaces() != 0) {
                            if (WifiNative.getScanCapabilities(mScanCapabilities)) {
                                transitionTo(mStartedState);
                            } else {
                                loge("could not get scan capabilities");
                            }
                        } else {
                            loge("could not start HAL");
                        }
                        break;
                    case WifiScanner.CMD_SCAN:
                    case WifiScanner.CMD_START_BACKGROUND_SCAN:
                    case WifiScanner.CMD_STOP_BACKGROUND_SCAN:
                    case WifiScanner.CMD_START_SINGLE_SCAN:
                    case WifiScanner.CMD_STOP_SINGLE_SCAN:
                    case WifiScanner.CMD_SET_HOTLIST:
                    case WifiScanner.CMD_RESET_HOTLIST:
                    case WifiScanner.CMD_CONFIGURE_WIFI_CHANGE:
                    case WifiScanner.CMD_START_TRACKING_CHANGE:
                    case WifiScanner.CMD_STOP_TRACKING_CHANGE:
                    case WifiScanner.CMD_GET_SCAN_RESULTS:
                        replyFailed(msg, WifiScanner.REASON_UNSPECIFIED, "not available");
                        break;

                    case CMD_SCAN_RESULTS_AVAILABLE:
                        if (DBG) localLog("ignored scan results available event");
                        break;

                    case CMD_FULL_SCAN_RESULTS:
                        if (DBG) localLog("ignored full scan result event");
                        break;

                    default:
                        break;
                }

                return HANDLED;
            }
        }

        class StartedState extends State {

            @Override
            public void enter() {
                if (DBG) localLog("StartedState");
            }

            @Override
            public boolean processMessage(Message msg) {

                if (DBG) localLog("StartedState got" + msg);

                ClientInfo ci = mClients.get(msg.replyTo);

                switch (msg.what) {
                    case CMD_DRIVER_UNLOADED:
                        transitionTo(mDefaultState);
                        break;
                    case WifiScanner.CMD_SCAN:
                        replyFailed(msg, WifiScanner.REASON_UNSPECIFIED, "not implemented");
                        break;
                    case WifiScanner.CMD_START_BACKGROUND_SCAN:
                        if (addScanRequest(ci, msg.arg2, (ScanSettings) msg.obj)) {
                            replySucceeded(msg);
                        } else {
                            replyFailed(msg, WifiScanner.REASON_INVALID_REQUEST, "bad request");
                        }
                        break;
                    case WifiScanner.CMD_STOP_BACKGROUND_SCAN:
                        removeScanRequest(ci, msg.arg2);
                        break;
                    case WifiScanner.CMD_GET_SCAN_RESULTS:
                        reportScanResults();
                        replySucceeded(msg);
                        break;
                    case WifiScanner.CMD_START_SINGLE_SCAN:
                        if (addSingleScanRequest(ci, msg.arg2, (ScanSettings) msg.obj)) {
                            replySucceeded(msg);
                        } else {
                            replyFailed(msg, WifiScanner.REASON_INVALID_REQUEST, "bad request");
                        }
                        break;
                    case WifiScanner.CMD_STOP_SINGLE_SCAN:
                        removeScanRequest(ci, msg.arg2);
                        break;
                    case CMD_STOP_SCAN_INTERNAL:
                        localLog("Removing single shot scan");
                        removeScanRequest((ClientInfo) msg.obj, msg.arg2);
                        break;
                    case WifiScanner.CMD_SET_HOTLIST:
                        setHotlist(ci, msg.arg2, (WifiScanner.HotlistSettings) msg.obj);
                        replySucceeded(msg);
                        break;
                    case WifiScanner.CMD_RESET_HOTLIST:
                        resetHotlist(ci, msg.arg2);
                        break;
                    case WifiScanner.CMD_START_TRACKING_CHANGE:
                        trackWifiChanges(ci, msg.arg2);
                        replySucceeded(msg);
                        break;
                    case WifiScanner.CMD_STOP_TRACKING_CHANGE:
                        untrackWifiChanges(ci, msg.arg2);
                        break;
                    case WifiScanner.CMD_CONFIGURE_WIFI_CHANGE:
                        configureWifiChange((WifiScanner.WifiChangeSettings) msg.obj);
                        break;
                    case CMD_SCAN_RESULTS_AVAILABLE: {
                            ScanData[] results = WifiNative.getScanResults(/* flush = */ true);
                            Collection<ClientInfo> clients = mClients.values();
                            for (ClientInfo ci2 : clients) {
                                ci2.reportScanResults(results);
                            }
                        }
                        break;
                    case CMD_FULL_SCAN_RESULTS: {
                            ScanResult result = (ScanResult) msg.obj;
                            if (DBG) localLog("reporting fullscan result for " + result.SSID);
                            Collection<ClientInfo> clients = mClients.values();
                            for (ClientInfo ci2 : clients) {
                                ci2.reportFullScanResult(result);
                            }
                        }
                        break;

                    case CMD_HOTLIST_AP_FOUND: {
                            ScanResult[] results = (ScanResult[])msg.obj;
                            if (DBG) localLog("Found " + results.length + " results");
                            Collection<ClientInfo> clients = mClients.values();
                            for (ClientInfo ci2 : clients) {
                                ci2.reportHotlistResults(WifiScanner.CMD_AP_FOUND, results);
                            }
                        }
                        break;
                    case CMD_HOTLIST_AP_LOST: {
                            ScanResult[] results = (ScanResult[])msg.obj;
                            if (DBG) localLog("Lost " + results.length + " results");
                            Collection<ClientInfo> clients = mClients.values();
                            for (ClientInfo ci2 : clients) {
                                ci2.reportHotlistResults(WifiScanner.CMD_AP_LOST, results);
                            }
                        }
                        break;
                    case CMD_WIFI_CHANGE_DETECTED: {
                            ScanResult[] results = (ScanResult[])msg.obj;
                            reportWifiChanged(results);
                        }
                        break;
                    case CMD_WIFI_CHANGES_STABILIZED: {
                            ScanResult[] results = (ScanResult[])msg.obj;
                            reportWifiStabilized(results);
                        }
                        break;
                    case CMD_SCAN_PAUSED: {
                            ScanData results[] = (ScanData[]) msg.obj;
                            Collection<ClientInfo> clients = mClients.values();
                            for (ClientInfo ci2 : clients) {
                                ci2.reportScanResults(results);
                            }
                            transitionTo(mPausedState);
                        }
                        break;
                    default:
                        return NOT_HANDLED;
                }

                return HANDLED;
            }
        }

        class PausedState extends State {
            @Override
            public void enter() {
                if (DBG) localLog("PausedState");
            }

            @Override
            public boolean processMessage(Message msg) {

                if (DBG) localLog("PausedState got" + msg);

                switch (msg.what) {
                    case CMD_SCAN_RESTARTED:
                        transitionTo(mStartedState);
                        break;
                    default:
                        deferMessage(msg);
                        break;
                }
                return HANDLED;
            }

        }

        @Override
        public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            pw.println("number of clients : " + mClients.size());
            for (ClientInfo client : mClients.values()) {
                client.dump(fd, pw, args);
                pw.append("------\n");
            }
            pw.println();
            pw.println("localLog : ");
            mLocalLog.dump(fd, pw, args);
            pw.append("\n\n");
            super.dump(fd, pw, args);
        }
    }

    /* client management */
    HashMap<Messenger, ClientInfo> mClients = new HashMap<Messenger, ClientInfo>();

    private class ClientInfo {
        private static final int MAX_LIMIT = 16;
        private final AsyncChannel mChannel;
        private final Messenger mMessenger;
        private final int mUid;
        private final WorkSource mWorkSource;
        private final PowerManager.WakeLock mFullScanWakeLock;
        private boolean mScanWorkReported = false;
        private boolean mFullScanRequested = false;

        ClientInfo(int uid, AsyncChannel c, Messenger m) {
            mChannel = c;
            mMessenger = m;
            mUid = uid;
            mWorkSource = new WorkSource(uid, TAG);
            mFullScanWakeLock = mPowerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                    "WifiScan");
            mFullScanWakeLock.setReferenceCounted(false);
            mFullScanWakeLock.setWorkSource(mWorkSource);
            if (DBG) localLog("New client, channel: " + c + " messenger: " + m);
        }

        void reportBatchedScanStart() {
            if (mUid == 0)
                return;

            int csph = getCsph();

            try {
                mBatteryStats.noteWifiBatchedScanStartedFromSource(mWorkSource, csph);
                localLog("started scanning for UID " + mUid + ", csph = " + csph);
            } catch (RemoteException e) {
                logw("failed to report scan work: " + e.toString());
            }
        }

        void reportBatchedScanStop() {
            if (mUid == 0)
                return;

            try {
                mBatteryStats.noteWifiBatchedScanStoppedFromSource(mWorkSource);
                localLog("stopped scanning for UID " + mUid);
            } catch (RemoteException e) {
                logw("failed to cleanup scan work: " + e.toString());
            }
        }

        int getCsph() {
            int csph = 0;
            for (ScanSettings settings : getScanSettings()) {
                int num_channels = settings.channels == null ? 0 : settings.channels.length;
                if (num_channels == 0 && settings.band != 0) {
                    num_channels = getChannelsForBand(settings.band).length;
                }

                int scans_per_Hour = settings.periodInMs == 0 ? 1 : (3600 * 1000) / settings.periodInMs;
                csph += num_channels * scans_per_Hour;
            }

            return csph;
        }

        void reportScanWorkUpdate() {
            if (mScanWorkReported) {
                reportBatchedScanStop();
                mScanWorkReported = false;
                mFullScanRequested = false;
            }
            if (mScanSettings.isEmpty() == false) {
                reportBatchedScanStart();
                mScanWorkReported = true;

                for (ScanSettings settings : getScanSettings()) {
                    if (settings.reportEvents == WifiScanner.REPORT_EVENT_FULL_SCAN_RESULT) {
                        mFullScanRequested = true;
                    }
                }
                // make sure to charge first full scan even if there are no results
                if (mFullScanRequested) {
                    mFullScanWakeLock.acquire(SCAN_WAKE_LOCK_TIME_OUT_MSECS);
                }
            }
        }

        @Override
        public String toString() {
            StringBuffer sb = new StringBuffer();
            sb.append("mChannel ").append(mChannel).append("\n");
            sb.append("mMessenger ").append(mMessenger);
            return sb.toString();
        }

        void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            StringBuilder sb = new StringBuilder();
            sb.append(toString());

            Iterator<Map.Entry<Integer, ScanSettings>> it = mScanSettings.entrySet().iterator();
            for (; it.hasNext(); ) {
                Map.Entry<Integer, ScanSettings> entry = it.next();
                sb.append("ScanId ").append(entry.getKey()).append("\n");

                ScanSettings scanSettings = entry.getValue();
                sb.append(describe(scanSettings));
                sb.append("\n");
            }

            pw.println(sb.toString());
        }

        HashMap<Integer, ScanSettings> mScanSettings = new HashMap<Integer, ScanSettings>(4);
        HashMap<Integer, Integer> mScanPeriods = new HashMap<Integer, Integer>(4);

        void addScanRequest(ScanSettings settings, int id) {
            mScanSettings.put(id, settings);
            reportScanWorkUpdate();
        }

        void removeScanRequest(int id) {
            ScanSettings settings = mScanSettings.remove(id);
            if (settings != null && settings.periodInMs == 0) {
                /* this was a single shot scan */
                mChannel.sendMessage(WifiScanner.CMD_SINGLE_SCAN_COMPLETED, 0, id);
            }
            reportScanWorkUpdate();
        }

        Iterator<Map.Entry<Integer, ScanSettings>> getScans() {
            return mScanSettings.entrySet().iterator();
        }

        Collection<ScanSettings> getScanSettings() {
            return mScanSettings.values();
        }

        void reportScanResults(ScanData[] results) {
            Iterator<Integer> it = mScanSettings.keySet().iterator();
            while (it.hasNext()) {
                int handler = it.next();
                reportScanResults(results, handler);
            }
        }

        void reportScanResults(ScanData[] results, int handler) {
            ScanSettings settings = mScanSettings.get(handler);
            ChannelSpec desiredChannels[] = settings.channels;
            if (settings.band != WifiScanner.WIFI_BAND_UNSPECIFIED
                    || desiredChannels == null || desiredChannels.length == 0)  {
                desiredChannels = getChannelsForBand(settings.band);
            }

            // check the channels this client asked for ..
            int num_results = 0;
            for (ScanData result : results) {
                boolean copyScanData = false;
                for (ScanResult scanResult : result.getResults()) {
                    for (ChannelSpec channelSpec : desiredChannels) {
                        if (channelSpec.frequency == scanResult.frequency) {
                            copyScanData = true;
                            break;
                        }
                    }
                    if (copyScanData) {
                        num_results++;
                        break;
                    }
                }
            }

            localLog("results = " + results.length + ", num_results = " + num_results);

            ScanData results2[] = new ScanData[num_results];
            int index = 0;
            for (ScanData result : results) {
                boolean copyScanData = false;
                for (ScanResult scanResult : result.getResults()) {
                    for (ChannelSpec channelSpec : desiredChannels) {
                        if (channelSpec.frequency == scanResult.frequency) {
                            copyScanData = true;
                            break;
                        }
                    }
                    if (copyScanData) {
                        break;
                    }
                }

                if (copyScanData) {
                    if (VDBG) {
                        localLog("adding at " + index);
                    }
                    results2[index] = new WifiScanner.ScanData(result);
                    index++;
                }
            }
            
            localLog("delivering results, num = " + results2.length);

            deliverScanResults(handler, results2);
            if (settings.periodInMs == 0) {
                /* this is a single shot scan; stop the scan now */
                mStateMachine.sendMessage(CMD_STOP_SCAN_INTERNAL, 0, handler, this);
            }
            mFullScanWakeLock.release();
        }

        void deliverScanResults(int handler, ScanData results[]) {
            WifiScanner.ParcelableScanData parcelableScanData =
                    new WifiScanner.ParcelableScanData(results);
            mChannel.sendMessage(WifiScanner.CMD_SCAN_RESULT, 0, handler, parcelableScanData);
        }

        void reportFullScanResult(ScanResult result) {
            Iterator<Integer> it = mScanSettings.keySet().iterator();
            boolean reportedFullScanResult = false;
            while (it.hasNext()) {
                int handler = it.next();
                ScanSettings settings = mScanSettings.get(handler);
                ChannelSpec desiredChannels[] = settings.channels;
                if (settings.band != WifiScanner.WIFI_BAND_UNSPECIFIED
                        || desiredChannels == null || desiredChannels.length == 0)  {
                    desiredChannels = getChannelsForBand(settings.band);
                }
                for (ChannelSpec channelSpec : desiredChannels) {
                    if (channelSpec.frequency == result.frequency) {
                        ScanResult newResult = new ScanResult(result);
                        if (DBG) localLog("sending it to " + handler);
                        newResult.informationElements = result.informationElements.clone();
                        mChannel.sendMessage(
                                WifiScanner.CMD_FULL_SCAN_RESULT, 0, handler, newResult);
                        reportedFullScanResult = true;
                    }
                }
            }
            if (mFullScanRequested && reportedFullScanResult) {
                mFullScanWakeLock.acquire(SCAN_WAKE_LOCK_TIME_OUT_MSECS);
            }
        }

        void reportPeriodChanged(int handler, ScanSettings settings, int newPeriodInMs) {
            Integer prevPeriodObject = mScanPeriods.get(handler);
            int prevPeriodInMs = settings.periodInMs;
            if (prevPeriodObject != null) {
                prevPeriodInMs = prevPeriodObject;
            }

            if (prevPeriodInMs != newPeriodInMs) {
                mChannel.sendMessage(WifiScanner.CMD_PERIOD_CHANGED, newPeriodInMs, handler);
            }
        }

        HashMap<Integer, WifiScanner.HotlistSettings> mHotlistSettings =
                new HashMap<Integer, WifiScanner.HotlistSettings>();

        void addHostlistSettings(WifiScanner.HotlistSettings settings, int handler) {
            mHotlistSettings.put(handler, settings);
        }

        void removeHostlistSettings(int handler) {
            mHotlistSettings.remove(handler);
        }

        Collection<WifiScanner.HotlistSettings> getHotlistSettings() {
            return mHotlistSettings.values();
        }

        void reportHotlistResults(int what, ScanResult[] results) {
            Iterator<Map.Entry<Integer, WifiScanner.HotlistSettings>> it =
                    mHotlistSettings.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<Integer, WifiScanner.HotlistSettings> entry = it.next();
                int handler = entry.getKey();
                WifiScanner.HotlistSettings settings = entry.getValue();
                int num_results = 0;
                for (ScanResult result : results) {
                    for (BssidInfo BssidInfo : settings.bssidInfos) {
                        if (result.BSSID.equalsIgnoreCase(BssidInfo.bssid)) {
                            num_results++;
                            break;
                        }
                    }
                }

                if (num_results == 0) {
                    // nothing to report
                    return;
                }

                ScanResult results2[] = new ScanResult[num_results];
                int index = 0;
                for (ScanResult result : results) {
                    for (BssidInfo BssidInfo : settings.bssidInfos) {
                        if (result.BSSID.equalsIgnoreCase(BssidInfo.bssid)) {
                            results2[index] = result;
                            index++;
                        }
                    }
                }

                WifiScanner.ParcelableScanResults parcelableScanResults =
                        new WifiScanner.ParcelableScanResults(results2);

                mChannel.sendMessage(what, 0, handler, parcelableScanResults);
            }
        }

        HashSet<Integer> mSignificantWifiHandlers = new HashSet<Integer>();
        void addSignificantWifiChange(int handler) {
            mSignificantWifiHandlers.add(handler);
        }

        void removeSignificantWifiChange(int handler) {
            mSignificantWifiHandlers.remove(handler);
        }

        Collection<Integer> getWifiChangeHandlers() {
            return mSignificantWifiHandlers;
        }

        void reportWifiChanged(ScanResult[] results) {
            WifiScanner.ParcelableScanResults parcelableScanResults =
                    new WifiScanner.ParcelableScanResults(results);
            Iterator<Integer> it = mSignificantWifiHandlers.iterator();
            while (it.hasNext()) {
                int handler = it.next();
                mChannel.sendMessage(WifiScanner.CMD_WIFI_CHANGE_DETECTED,
                        0, handler, parcelableScanResults);
            }
        }

        void reportWifiStabilized(ScanResult[] results) {
            WifiScanner.ParcelableScanResults parcelableScanResults =
                    new WifiScanner.ParcelableScanResults(results);
            Iterator<Integer> it = mSignificantWifiHandlers.iterator();
            while (it.hasNext()) {
                int handler = it.next();
                mChannel.sendMessage(WifiScanner.CMD_WIFI_CHANGES_STABILIZED,
                        0, handler, parcelableScanResults);
            }
        }

        void cleanup() {
            mScanSettings.clear();
            resetBuckets();

            mHotlistSettings.clear();
            resetHotlist();

            for (Integer handler :  mSignificantWifiHandlers) {
                untrackWifiChanges(this, handler);
            }

            mSignificantWifiHandlers.clear();
            localLog("Successfully stopped all requests for client " + this);
        }
    }

    void replySucceeded(Message msg) {
        if (msg.replyTo != null) {
            Message reply = Message.obtain();
            reply.what = WifiScanner.CMD_OP_SUCCEEDED;
            reply.arg2 = msg.arg2;
            try {
                msg.replyTo.send(reply);
            } catch (RemoteException e) {
                // There's not much we can do if reply can't be sent!
            }
        } else {
            // locally generated message; doesn't need a reply!
        }
    }

    void replyFailed(Message msg, int reason, String description) {
        if (msg.replyTo != null) {
            Message reply = Message.obtain();
            reply.what = WifiScanner.CMD_OP_FAILED;
            reply.arg2 = msg.arg2;
            reply.obj = new WifiScanner.OperationResult(reason, description);
            try {
                msg.replyTo.send(reply);
            } catch (RemoteException e) {
                // There's not much we can do if reply can't be sent!
            }
        } else {
            // locally generated message; doesn't need a reply!
        }
    }

    private class SettingsComputer {

        private class TimeBucket {
            int periodInSecond;
            int periodMinInSecond;
            int periodMaxInSecond;

            TimeBucket(int p, int min, int max) {
                periodInSecond = p;
                periodMinInSecond = min;
                periodMaxInSecond = max;
            }
        }

        private final TimeBucket[] mTimeBuckets = new TimeBucket[] {
                new TimeBucket( 1, 0, 5 ),
                new TimeBucket( 5, 5, 10 ),
                new TimeBucket( 10, 10, 25 ),
                new TimeBucket( 30, 25, 55 ),
                new TimeBucket( 60, 55, 240),
                new TimeBucket( 300, 240, 500),
                new TimeBucket( 600, 500, 1500),
                new TimeBucket( 1800, 1500, WifiScanner.MAX_SCAN_PERIOD_MS) };

        private static final int MAX_CHANNELS = 32;
        private static final int DEFAULT_BASE_PERIOD_MS = 5000;
        private static final int DEFAULT_REPORT_THRESHOLD_NUM_SCANS = 10;
        private static final int DEFAULT_REPORT_THRESHOLD_PERCENT = 100;

        private WifiNative.ScanSettings mSettings;
        {
            mSettings = new WifiNative.ScanSettings();
            mSettings.max_ap_per_scan = mScanCapabilities.max_ap_cache_per_scan;
            mSettings.base_period_ms = DEFAULT_BASE_PERIOD_MS;
            mSettings.report_threshold_percent = DEFAULT_REPORT_THRESHOLD_PERCENT;
            mSettings.report_threshold_num_scans = DEFAULT_REPORT_THRESHOLD_NUM_SCANS;

            mSettings.buckets = new WifiNative.BucketSettings[mScanCapabilities.max_scan_buckets];
            for (int i = 0; i < mSettings.buckets.length; i++) {
                WifiNative.BucketSettings bucketSettings = new WifiNative.BucketSettings();
                bucketSettings.bucket = i;
                bucketSettings.report_events = 0;
                bucketSettings.channels = new WifiNative.ChannelSettings[MAX_CHANNELS];
                bucketSettings.num_channels = 0;
                mSettings.buckets[i] = bucketSettings;
            }
        }

        HashMap<Integer, Integer> mChannelToBucketMap = new HashMap<Integer, Integer>();

        private int getBestBucket(ScanSettings settings) {

            // check to see if any of the channels are being scanned already
            // and find the smallest bucket index (it represents the quickest
            // period of scan)

            ChannelSpec channels[] = settings.channels;
            if (channels == null) {
                // set channels based on band
                channels = getChannelsForBand(settings.band);
            }

            if (channels == null) {
                // still no channels; then there's nothing to scan
                loge("No channels to scan!!");
                return -1;
            }

            int mostFrequentBucketIndex = mTimeBuckets.length;

            for (ChannelSpec desiredChannelSpec : channels) {
                if (mChannelToBucketMap.containsKey(desiredChannelSpec.frequency)) {
                    int bucket = mChannelToBucketMap.get(desiredChannelSpec.frequency);
                    if (bucket < mostFrequentBucketIndex) {
                        mostFrequentBucketIndex = bucket;
                    }
                }
            }

            int bestBucketIndex = -1;                                   // best by period
            for (int i = 0; i < mTimeBuckets.length; i++) {
                TimeBucket bucket = mTimeBuckets[i];
                if (bucket.periodMinInSecond * 1000 <= settings.periodInMs
                        && settings.periodInMs < bucket.periodMaxInSecond * 1000) {
                    // we set the time period to this
                    bestBucketIndex = i;
                    break;
                }
            }

            if (mostFrequentBucketIndex < bestBucketIndex) {
                for (ChannelSpec desiredChannelSpec : channels) {
                    mChannelToBucketMap.put(desiredChannelSpec.frequency, mostFrequentBucketIndex);
                }
                localLog("returning mf bucket number " + mostFrequentBucketIndex);
                return mostFrequentBucketIndex;
            } else if (bestBucketIndex != -1) {
                for (ChannelSpec desiredChannelSpec : channels) {
                    mChannelToBucketMap.put(desiredChannelSpec.frequency, bestBucketIndex);
                }
                localLog("returning best bucket number " + bestBucketIndex);
                return bestBucketIndex;
            }

            loge("Could not find suitable bucket for period " + settings.periodInMs);
            return -1;
        }

        void prepChannelMap(ScanSettings settings) {
            getBestBucket(settings);
        }

        int addScanRequestToBucket(ScanSettings settings) {

            int bucketIndex = getBestBucket(settings);
            if (bucketIndex == -1) {
                loge("Ignoring invalid settings");
                return -1;
            }

            ChannelSpec desiredChannels[] = settings.channels;
            if (settings.band != WifiScanner.WIFI_BAND_UNSPECIFIED
                    || desiredChannels == null
                    || desiredChannels.length == 0) {
                // set channels based on band
                desiredChannels = getChannelsForBand(settings.band);
                if (desiredChannels == null) {
                    // still no channels; then there's nothing to scan
                    loge("No channels to scan!!");
                    return -1;
                }
            }

            // merge the channel lists for these buckets
            localLog("merging " + desiredChannels.length + " channels "
                    + " for period " + settings.periodInMs
                    + " maxScans " + settings.maxScansToCache);

            WifiNative.BucketSettings bucket = mSettings.buckets[bucketIndex];
            boolean added = (bucket.num_channels == 0)
                    && (bucket.band == WifiScanner.WIFI_BAND_UNSPECIFIED);
            localLog("existing " + bucket.num_channels + " channels ");

            HashSet<ChannelSpec> newChannels = new HashSet<ChannelSpec>();
            for (ChannelSpec desiredChannelSpec : desiredChannels) {

                if (DBG) localLog("desired channel " + desiredChannelSpec.frequency);

                boolean found = false;
                for (int i = 0; i < bucket.num_channels; i++) {
                    if (desiredChannelSpec.frequency == bucket.channels[i].frequency) {
                        found = true;
                        break;
                    }
                }

                if (!found) {
                    newChannels.add(desiredChannelSpec);
                } else {
                    if (DBG) localLog("Already scanning channel " + desiredChannelSpec.frequency);
                }
            }

            if (settings.band != WifiScanner.WIFI_BAND_UNSPECIFIED
                    || (bucket.num_channels + newChannels.size()) > bucket.channels.length) {
                // can't accommodate all channels; switch to specifying band
                bucket.num_channels = 0;
                bucket.band = getBandFromChannels(bucket.channels)
                        | getBandFromChannels(desiredChannels);
                bucket.channels = new WifiNative.ChannelSettings[0];
                localLog("switching to using band " + bucket.band);
            } else {
                for (ChannelSpec desiredChannelSpec : newChannels) {

                    localLog("adding new channel spec " + desiredChannelSpec.frequency);

                    WifiNative.ChannelSettings channelSettings = new WifiNative.ChannelSettings();
                    channelSettings.frequency = desiredChannelSpec.frequency;
                    bucket.channels[bucket.num_channels] = channelSettings;
                    bucket.num_channels++;
                    mChannelToBucketMap.put(bucketIndex, channelSettings.frequency);
                }
            }

            if (bucket.report_events < settings.reportEvents) {
                if (DBG) localLog("setting report_events to " + settings.reportEvents);
                bucket.report_events = settings.reportEvents;
            } else {
                if (DBG) localLog("report_events is " + settings.reportEvents);
            }

            if (added) {
                bucket.period_ms = mTimeBuckets[bucketIndex].periodInSecond * 1000;
                mSettings.num_buckets++;
            }

            if ( settings.numBssidsPerScan != 0) {
                if (mSettings.max_ap_per_scan > settings.numBssidsPerScan) {
                    mSettings.max_ap_per_scan = settings.numBssidsPerScan;
                }
            }

            if (settings.maxScansToCache != 0) {
                if (mSettings.report_threshold_num_scans > settings.maxScansToCache) {
                    mSettings.report_threshold_num_scans = settings.maxScansToCache;
                }
            }

            return bucket.period_ms;
        }

        public WifiNative.ScanSettings getComputedSettings() {
            return mSettings;
        }

        public void compressBuckets() {
            int num_buckets = 0;
            for (int i = 0; i < mSettings.buckets.length; i++) {
                if (mSettings.buckets[i].num_channels != 0
                        || mSettings.buckets[i].band != WifiScanner.WIFI_BAND_UNSPECIFIED) {
                    mSettings.buckets[num_buckets] = mSettings.buckets[i];
                    num_buckets++;
                }
            }
            // remove unused buckets
            for (int i = num_buckets; i < mSettings.buckets.length; i++) {
                mSettings.buckets[i] = null;
            }

            mSettings.num_buckets = num_buckets;
            if (num_buckets != 0) {
                mSettings.base_period_ms = mSettings.buckets[0].period_ms;
            }
        }
    }

    boolean resetBuckets() {
        SettingsComputer c = new SettingsComputer();
        Collection<ClientInfo> clients = mClients.values();
        for (ClientInfo ci : clients) {
            Collection<ScanSettings> settings = ci.getScanSettings();
            for (ScanSettings s : settings) {
                c.prepChannelMap(s);
            }
        }

        for (ClientInfo ci : clients) {
            Iterator it = ci.getScans();
            while (it.hasNext()) {
                Map.Entry<Integer, ScanSettings> entry =
                        (Map.Entry<Integer,ScanSettings>)it.next();
                int id = entry.getKey();
                ScanSettings s = entry.getValue();
                int newPeriodInMs = c.addScanRequestToBucket(s);
                if (newPeriodInMs  == -1) {
                    if (DBG) localLog("could not find a good bucket");
                    return false;
                }
                if (newPeriodInMs != s.periodInMs) {
                    ci.reportPeriodChanged(id, s, newPeriodInMs);
                }
            }
        }

        c.compressBuckets();

        WifiNative.ScanSettings s = c.getComputedSettings();
        if (s.num_buckets == 0) {
            if (DBG) localLog("Stopping scan because there are no buckets");
            WifiNative.stopScan();
            return true;
        } else {
            if (WifiNative.startScan(s, mStateMachine)) {
                localLog("Successfully started scan of " + s.num_buckets + " buckets at"
                        + "time = " + SystemClock.elapsedRealtimeNanos() / 1000 + " period "
                        + s.base_period_ms);
                return true;
            } else {
                loge("Failed to start scan of " + s.num_buckets + " buckets");
                return false;
            }
        }
    }

    void logScanRequest(String request, ClientInfo ci, int id, ScanSettings settings) {
        StringBuffer sb = new StringBuffer();
        sb.append(request);
        sb.append("\nClient ");
        sb.append(ci.toString());
        sb.append("\nId ");
        sb.append(id);
        sb.append("\n");
        if (settings != null) {
            sb.append(describe(settings));
            sb.append("\n");
        }
        sb.append("\n");
        localLog(sb.toString());
    }

    boolean addScanRequest(ClientInfo ci, int handler, ScanSettings settings) {
        // sanity check the input
        if (ci == null) {
            Log.d(TAG, "Failing scan request ClientInfo not found " + handler);
            return false;
        }
        if (settings.periodInMs < WifiScanner.MIN_SCAN_PERIOD_MS) {
            localLog("Failing scan request because periodInMs is " + settings.periodInMs);
            return false;
        }

        int minSupportedPeriodMs = 0;
        if (settings.channels != null) {
            minSupportedPeriodMs = settings.channels.length * MIN_PERIOD_PER_CHANNEL_MS;
        } else {
            if ((settings.band & WifiScanner.WIFI_BAND_24_GHZ) == 0) {
                /* 2.4 GHz band has 11 to 13 channels */
                minSupportedPeriodMs += 1000;
            }
            if ((settings.band & WifiScanner.WIFI_BAND_5_GHZ) == 0) {
                /* 5 GHz band has another 10 channels */
                minSupportedPeriodMs += 1000;
            }
            if ((settings.band & WifiScanner.WIFI_BAND_5_GHZ_DFS_ONLY) == 0) {
                /* DFS requires passive scan which takes longer time */
                minSupportedPeriodMs += 2000;
            }
        }

        if (settings.periodInMs < minSupportedPeriodMs) {
            localLog("Failing scan request because minSupportedPeriodMs is "
                    + minSupportedPeriodMs + " but the request wants " + settings.periodInMs);
            return false;
        }

        logScanRequest("addScanRequest", ci, handler, settings);
        ci.addScanRequest(settings, handler);
        if (resetBuckets()) {
            return true;
        } else {
            ci.removeScanRequest(handler);
            localLog("Failing scan request because failed to reset scan");
            return false;
        }
    }

    boolean addSingleScanRequest(ClientInfo ci, int handler, ScanSettings settings) {
        if (ci == null) {
            Log.d(TAG, "Failing single scan request ClientInfo not found " + handler);
            return false;
        }
        if (settings.reportEvents == 0) {
            settings.reportEvents = WifiScanner.REPORT_EVENT_AFTER_EACH_SCAN;
        }
        if (settings.periodInMs == 0) {
            settings.periodInMs = 10000;        // 10s - although second scan should never happen
        }

        logScanRequest("addSingleScanRequest", ci, handler, settings);
        ci.addScanRequest(settings, handler);
        if (resetBuckets()) {
            /* reset periodInMs to 0 to indicate single shot scan */
            settings.periodInMs = 0;
            return true;
        } else {
            ci.removeScanRequest(handler);
            localLog("Failing scan request because failed to reset scan");
            return false;
        }
    }

    void removeScanRequest(ClientInfo ci, int handler) {
        if (ci != null) {
            logScanRequest("removeScanRequest", ci, handler, null);
            ci.removeScanRequest(handler);
            resetBuckets();
        }
    }

    boolean reportScanResults() {
        ScanData results[] = WifiNative.getScanResults(/* flush = */ true);
        Collection<ClientInfo> clients = mClients.values();
        for (ClientInfo ci2 : clients) {
            ci2.reportScanResults(results);
        }

        return true;
    }

    void resetHotlist() {
        Collection<ClientInfo> clients = mClients.values();
        int num_hotlist_ap = 0;

        for (ClientInfo ci : clients) {
            Collection<WifiScanner.HotlistSettings> c = ci.getHotlistSettings();
            for (WifiScanner.HotlistSettings s : c) {
                num_hotlist_ap +=  s.bssidInfos.length;
            }
        }

        if (num_hotlist_ap == 0) {
            WifiNative.resetHotlist();
        } else {
            BssidInfo bssidInfos[] = new BssidInfo[num_hotlist_ap];
            int index = 0;
            for (ClientInfo ci : clients) {
                Collection<WifiScanner.HotlistSettings> settings = ci.getHotlistSettings();
                for (WifiScanner.HotlistSettings s : settings) {
                    for (int i = 0; i < s.bssidInfos.length; i++, index++) {
                        bssidInfos[index] = s.bssidInfos[i];
                    }
                }
            }

            WifiScanner.HotlistSettings settings = new WifiScanner.HotlistSettings();
            settings.bssidInfos = bssidInfos;
            settings.apLostThreshold = 3;
            WifiNative.setHotlist(settings, mStateMachine);
        }
    }

    void setHotlist(ClientInfo ci, int handler, WifiScanner.HotlistSettings settings) {
        ci.addHostlistSettings(settings, handler);
        resetHotlist();
    }

    void resetHotlist(ClientInfo ci, int handler) {
        ci.removeHostlistSettings(handler);
        resetHotlist();
    }

    WifiChangeStateMachine mWifiChangeStateMachine;

    void trackWifiChanges(ClientInfo ci, int handler) {
        mWifiChangeStateMachine.enable();
        ci.addSignificantWifiChange(handler);
    }

    void untrackWifiChanges(ClientInfo ci, int handler) {
        ci.removeSignificantWifiChange(handler);
        Collection<ClientInfo> clients = mClients.values();
        for (ClientInfo ci2 : clients) {
            if (ci2.getWifiChangeHandlers().size() != 0) {
                // there is at least one client watching for
                // significant changes; so nothing more to do
                return;
            }
        }

        // no more clients looking for significant wifi changes
        // no need to keep the state machine running; disable it
        mWifiChangeStateMachine.disable();
    }

    void configureWifiChange(WifiScanner.WifiChangeSettings settings) {
        mWifiChangeStateMachine.configure(settings);
    }

    void reportWifiChanged(ScanResult results[]) {
        Collection<ClientInfo> clients = mClients.values();
        for (ClientInfo ci : clients) {
            ci.reportWifiChanged(results);
        }
    }

    void reportWifiStabilized(ScanResult results[]) {
        Collection<ClientInfo> clients = mClients.values();
        for (ClientInfo ci : clients) {
            ci.reportWifiStabilized(results);
        }
    }

    class WifiChangeStateMachine extends StateMachine
            implements WifiNative.SignificantWifiChangeEventHandler {

        private static final String TAG = "WifiChangeStateMachine";

        private static final int WIFI_CHANGE_CMD_NEW_SCAN_RESULTS           = 0;
        private static final int WIFI_CHANGE_CMD_CHANGE_DETECTED            = 1;
        private static final int WIFI_CHANGE_CMD_CHANGE_TIMEOUT             = 2;
        private static final int WIFI_CHANGE_CMD_ENABLE                     = 3;
        private static final int WIFI_CHANGE_CMD_DISABLE                    = 4;
        private static final int WIFI_CHANGE_CMD_CONFIGURE                  = 5;

        private static final int MAX_APS_TO_TRACK = 3;
        private static final int MOVING_SCAN_PERIOD_MS      = 10000;
        private static final int STATIONARY_SCAN_PERIOD_MS  =  5000;
        private static final int MOVING_STATE_TIMEOUT_MS    = 30000;

        State mDefaultState = new DefaultState();
        State mStationaryState = new StationaryState();
        State mMovingState = new MovingState();

        private static final String ACTION_TIMEOUT =
                "com.android.server.WifiScanningServiceImpl.action.TIMEOUT";
        AlarmManager  mAlarmManager;
        PendingIntent mTimeoutIntent;
        ScanResult    mCurrentBssids[];

        WifiChangeStateMachine(Looper looper, PowerManager powerManager) {
            super("SignificantChangeStateMachine", looper);

            mClientInfo = new ClientInfoLocal();
            mClients.put(null, mClientInfo);

            addState(mDefaultState);
            addState(mStationaryState, mDefaultState);
            addState(mMovingState, mDefaultState);

            setInitialState(mDefaultState);
        }

        public void enable() {
            if (mAlarmManager == null) {
                mAlarmManager = (AlarmManager) mContext.getSystemService(Context.ALARM_SERVICE);
            }

            if (mTimeoutIntent == null) {
                Intent intent = new Intent(ACTION_TIMEOUT, null);
                mTimeoutIntent = PendingIntent.getBroadcast(mContext, 0, intent, 0);

                mContext.registerReceiver(
                        new BroadcastReceiver() {
                            @Override
                            public void onReceive(Context context, Intent intent) {
                                sendMessage(WIFI_CHANGE_CMD_CHANGE_TIMEOUT);
                            }
                        }, new IntentFilter(ACTION_TIMEOUT));
            }

            sendMessage(WIFI_CHANGE_CMD_ENABLE);
        }

        public void disable() {
            sendMessage(WIFI_CHANGE_CMD_DISABLE);
        }

        public void configure(WifiScanner.WifiChangeSettings settings) {
            sendMessage(WIFI_CHANGE_CMD_CONFIGURE, settings);
        }

        class DefaultState extends State {
            @Override
            public void enter() {
                if (DBG) localLog("Entering IdleState");
            }

            @Override
            public boolean processMessage(Message msg) {
                if (DBG) localLog("DefaultState state got " + msg);
                switch (msg.what) {
                    case WIFI_CHANGE_CMD_ENABLE :
                        transitionTo(mMovingState);
                        break;
                    case WIFI_CHANGE_CMD_DISABLE:
                        // nothing to do
                        break;
                    case WIFI_CHANGE_CMD_NEW_SCAN_RESULTS:
                        // nothing to do
                        break;
                    case WIFI_CHANGE_CMD_CONFIGURE:
                        /* save configuration till we transition to moving state */
                        deferMessage(msg);
                        break;
                    default:
                        return NOT_HANDLED;
                }
                return HANDLED;
            }
        }

        class StationaryState extends State {
            @Override
            public void enter() {
                if (DBG) localLog("Entering StationaryState");
                reportWifiStabilized(mCurrentBssids);
            }

            @Override
            public boolean processMessage(Message msg) {
                if (DBG) localLog("Stationary state got " + msg);
                switch (msg.what) {
                    case WIFI_CHANGE_CMD_ENABLE :
                        // do nothing
                        break;
                    case WIFI_CHANGE_CMD_CHANGE_DETECTED:
                        if (DBG) localLog("Got wifi change detected");
                        reportWifiChanged((ScanResult[])msg.obj);
                        transitionTo(mMovingState);
                        break;
                    case WIFI_CHANGE_CMD_DISABLE:
                        if (DBG) localLog("Got Disable Wifi Change");
                        mCurrentBssids = null;
                        removeScanRequest();
                        untrackSignificantWifiChange();
                        transitionTo(mDefaultState);
                        break;
                    case WIFI_CHANGE_CMD_CONFIGURE:
                        /* save configuration till we transition to moving state */
                        deferMessage(msg);
                        break;
                    default:
                        return NOT_HANDLED;
                }
                return HANDLED;
            }
        }

        class MovingState extends State {
            boolean mWifiChangeDetected = false;
            boolean mScanResultsPending = false;

            @Override
            public void enter() {
                if (DBG) localLog("Entering MovingState");
                issueFullScan();
            }

            @Override
            public boolean processMessage(Message msg) {
                if (DBG) localLog("MovingState state got " + msg);
                switch (msg.what) {
                    case WIFI_CHANGE_CMD_ENABLE :
                        // do nothing
                        break;
                    case WIFI_CHANGE_CMD_DISABLE:
                        if (DBG) localLog("Got Disable Wifi Change");
                        mCurrentBssids = null;
                        removeScanRequest();
                        untrackSignificantWifiChange();
                        transitionTo(mDefaultState);
                        break;
                    case WIFI_CHANGE_CMD_NEW_SCAN_RESULTS:
                        if (DBG) localLog("Got scan results");
                        if (mScanResultsPending) {
                            if (DBG) localLog("reconfiguring scan");
                            reconfigureScan((ScanData[])msg.obj,
                                    STATIONARY_SCAN_PERIOD_MS);
                            mWifiChangeDetected = false;
                            mAlarmManager.setExact(AlarmManager.RTC_WAKEUP,
                                    System.currentTimeMillis() + MOVING_STATE_TIMEOUT_MS,
                                    mTimeoutIntent);
                            mScanResultsPending = false;
                        }
                        break;
                    case WIFI_CHANGE_CMD_CONFIGURE:
                        if (DBG) localLog("Got configuration from app");
                        WifiScanner.WifiChangeSettings settings =
                                (WifiScanner.WifiChangeSettings) msg.obj;
                        reconfigureScan(settings);
                        mWifiChangeDetected = false;
                        long unchangedDelay = settings.unchangedSampleSize * settings.periodInMs;
                        mAlarmManager.cancel(mTimeoutIntent);
                        mAlarmManager.setExact(AlarmManager.RTC_WAKEUP,
                                System.currentTimeMillis() + unchangedDelay,
                                mTimeoutIntent);
                        break;
                    case WIFI_CHANGE_CMD_CHANGE_DETECTED:
                        if (DBG) localLog("Change detected");
                        mAlarmManager.cancel(mTimeoutIntent);
                        reportWifiChanged((ScanResult[])msg.obj);
                        mWifiChangeDetected = true;
                        issueFullScan();
                        break;
                    case WIFI_CHANGE_CMD_CHANGE_TIMEOUT:
                        if (DBG) localLog("Got timeout event");
                        if (mWifiChangeDetected == false) {
                            transitionTo(mStationaryState);
                        }
                        break;
                    default:
                        return NOT_HANDLED;
                }
                return HANDLED;
            }

            @Override
            public void exit() {
                mAlarmManager.cancel(mTimeoutIntent);
            }

            void issueFullScan() {
                if (DBG) localLog("Issuing full scan");
                ScanSettings settings = new ScanSettings();
                settings.band = WifiScanner.WIFI_BAND_BOTH;
                settings.periodInMs = MOVING_SCAN_PERIOD_MS;
                settings.reportEvents = WifiScanner.REPORT_EVENT_AFTER_EACH_SCAN;
                addScanRequest(settings);
                mScanResultsPending = true;
            }

        }

        void reconfigureScan(ScanData[] results, int period) {
            // find brightest APs and set them as sentinels
            if (results.length < MAX_APS_TO_TRACK) {
                localLog("too few APs (" + results.length + ") available to track wifi change");
                return;
            }

            removeScanRequest();

            // remove duplicate BSSIDs
            HashMap<String, ScanResult> bssidToScanResult = new HashMap<String, ScanResult>();
            for (ScanResult result : results[0].getResults()) {
                ScanResult saved = bssidToScanResult.get(result.BSSID);
                if (saved == null) {
                    bssidToScanResult.put(result.BSSID, result);
                } else if (saved.level > result.level) {
                    bssidToScanResult.put(result.BSSID, result);
                }
            }

            // find brightest BSSIDs
            ScanResult brightest[] = new ScanResult[MAX_APS_TO_TRACK];
            Collection<ScanResult> results2 = bssidToScanResult.values();
            for (ScanResult result : results2) {
                for (int j = 0; j < brightest.length; j++) {
                    if (brightest[j] == null
                            || (brightest[j].level < result.level)) {
                        for (int k = brightest.length; k > (j + 1); k--) {
                            brightest[k - 1] = brightest[k - 2];
                        }
                        brightest[j] = result;
                        break;
                    }
                }
            }

            // Get channels to scan for
            ArrayList<Integer> channels = new ArrayList<Integer>();
            for (int i = 0; i < brightest.length; i++) {
                boolean found = false;
                for (int j = i + 1; j < brightest.length; j++) {
                    if (brightest[j].frequency == brightest[i].frequency) {
                        found = true;
                    }
                }
                if (!found) {
                    channels.add(brightest[i].frequency);
                }
            }

            if (DBG) localLog("Found " + channels.size() + " channels");

            // set scanning schedule
            ScanSettings settings = new ScanSettings();
            settings.band = WifiScanner.WIFI_BAND_UNSPECIFIED;
            settings.channels = new ChannelSpec[channels.size()];
            for (int i = 0; i < channels.size(); i++) {
                settings.channels[i] = new ChannelSpec(channels.get(i));
            }

            settings.periodInMs = period;
            addScanRequest(settings);

            WifiScanner.WifiChangeSettings settings2 = new WifiScanner.WifiChangeSettings();
            settings2.rssiSampleSize = 3;
            settings2.lostApSampleSize = 3;
            settings2.unchangedSampleSize = 3;
            settings2.minApsBreachingThreshold = 2;
            settings2.bssidInfos = new BssidInfo[brightest.length];

            for (int i = 0; i < brightest.length; i++) {
                BssidInfo BssidInfo = new BssidInfo();
                BssidInfo.bssid = brightest[i].BSSID;
                int threshold = (100 + brightest[i].level) / 32 + 2;
                BssidInfo.low = brightest[i].level - threshold;
                BssidInfo.high = brightest[i].level + threshold;
                settings2.bssidInfos[i] = BssidInfo;

                if (DBG) localLog("Setting bssid=" + BssidInfo.bssid + ", " +
                        "low=" + BssidInfo.low + ", high=" + BssidInfo.high);
            }

            trackSignificantWifiChange(settings2);
            mCurrentBssids = brightest;
        }

        void reconfigureScan(WifiScanner.WifiChangeSettings settings) {

            if (settings.bssidInfos.length < MAX_APS_TO_TRACK) {
                localLog("too few APs (" + settings.bssidInfos.length
                        + ") available to track wifi change");
                return;
            }

            if (DBG) localLog("Setting configuration specified by app");

            mCurrentBssids = new ScanResult[settings.bssidInfos.length];
            HashSet<Integer> channels = new HashSet<Integer>();

            for (int i = 0; i < settings.bssidInfos.length; i++) {
                ScanResult result = new ScanResult();
                result.BSSID = settings.bssidInfos[i].bssid;
                mCurrentBssids[i] = result;
                channels.add(settings.bssidInfos[i].frequencyHint);
            }

            // cancel previous scan
            removeScanRequest();

            // set new scanning schedule
            ScanSettings settings2 = new ScanSettings();
            settings2.band = WifiScanner.WIFI_BAND_UNSPECIFIED;
            settings2.channels = new ChannelSpec[channels.size()];
            int i = 0;
            for (Integer channel : channels) {
                settings2.channels[i++] = new ChannelSpec(channel);
            }

            settings2.periodInMs = settings.periodInMs;
            addScanRequest(settings2);

            // start tracking new APs
            trackSignificantWifiChange(settings);
        }

        class ClientInfoLocal extends ClientInfo {
            ClientInfoLocal() {
                super(0, null, null);
            }
            @Override
            void deliverScanResults(int handler, ScanData results[]) {
                if (DBG) localLog("Delivering messages directly");
                sendMessage(WIFI_CHANGE_CMD_NEW_SCAN_RESULTS, 0, 0, results);
            }
            @Override
            void reportPeriodChanged(int handler, ScanSettings settings, int newPeriodInMs) {
                // nothing to do; no one is listening for this
            }
        }

        @Override
        public void onChangesFound(ScanResult results[]) {
            sendMessage(WIFI_CHANGE_CMD_CHANGE_DETECTED, 0, 0, results);
        }

        ClientInfo mClientInfo;
        private static final int SCAN_COMMAND_ID = 1;

        void addScanRequest(ScanSettings settings) {
            if (DBG) localLog("Starting scans");
            Message msg = Message.obtain();
            msg.what = WifiScanner.CMD_START_BACKGROUND_SCAN;
            msg.arg2 = SCAN_COMMAND_ID;
            msg.obj = settings;
            mClientHandler.sendMessage(msg);
        }

        void removeScanRequest() {
            if (DBG) localLog("Stopping scans");
            Message msg = Message.obtain();
            msg.what = WifiScanner.CMD_STOP_BACKGROUND_SCAN;
            msg.arg2 = SCAN_COMMAND_ID;
            mClientHandler.sendMessage(msg);
        }

        void trackSignificantWifiChange(WifiScanner.WifiChangeSettings settings) {
            WifiNative.untrackSignificantWifiChange();
            WifiNative.trackSignificantWifiChange(settings, this);
        }

        void untrackSignificantWifiChange() {
            WifiNative.untrackSignificantWifiChange();
        }

    }

    private static ChannelSpec mChannels[][];

    private static void copyChannels(
            ChannelSpec channelSpec[], int offset, int channels[]) {
        for (int i = 0; i < channels.length; i++) {
            channelSpec[offset +i] = new ChannelSpec(channels[i]);
        }
    }

    private static boolean initChannels() {
        if (mChannels != null) {
            /* already initialized */
            return true;
        }

        int channels24[] = WifiNative.getChannelsForBand(WifiScanner.WIFI_BAND_24_GHZ);
        if (channels24 == null) {
            loge("Could not get channels for 2.4 GHz");
            return false;
        }

        int channels5[] = WifiNative.getChannelsForBand(WifiScanner.WIFI_BAND_5_GHZ);
        if (channels5 == null) {
            loge("Could not get channels for 5 GHz");
            return false;
        }

        int channelsDfs[] = WifiNative.getChannelsForBand(WifiScanner.WIFI_BAND_5_GHZ_DFS_ONLY);
        if (channelsDfs == null) {
            loge("Could not get channels for DFS");
            return false;
        }

        mChannels = new ChannelSpec[8][];

        mChannels[0] = new ChannelSpec[0];

        mChannels[1] = new ChannelSpec[channels24.length];
        copyChannels(mChannels[1], 0, channels24);

        mChannels[2] = new ChannelSpec[channels5.length];
        copyChannels(mChannels[2], 0, channels5);

        mChannels[3] = new ChannelSpec[channels24.length + channels5.length];
        copyChannels(mChannels[3], 0, channels24);
        copyChannels(mChannels[3], channels24.length, channels5);

        mChannels[4] = new ChannelSpec[channelsDfs.length];
        copyChannels(mChannels[4], 0, channelsDfs);

        mChannels[5] = new ChannelSpec[channels24.length + channelsDfs.length];
        copyChannels(mChannels[5], 0, channels24);
        copyChannels(mChannels[5], channels24.length, channelsDfs);

        mChannels[6] = new ChannelSpec[channels5.length + channelsDfs.length];
        copyChannels(mChannels[6], 0, channels5);
        copyChannels(mChannels[6], channels5.length, channelsDfs);

        mChannels[7] = new ChannelSpec[
                channels24.length + channels5.length + channelsDfs.length];
        copyChannels(mChannels[7], 0, channels24);
        copyChannels(mChannels[7], channels24.length, channels5);
        copyChannels(mChannels[7], channels24.length + channels5.length, channelsDfs);

        return true;
    }

    private static ChannelSpec[] getChannelsForBand(int band) {
        initChannels();

        if (band < WifiScanner.WIFI_BAND_24_GHZ || band > WifiScanner.WIFI_BAND_BOTH_WITH_DFS)
            /* invalid value for band */
            return mChannels[0];
        else
            return mChannels[band];
    }

    private static boolean isDfs(int channel) {
        ChannelSpec[] dfsChannels = getChannelsForBand(WifiScanner
                .WIFI_BAND_5_GHZ_DFS_ONLY);
        for (int i = 0; i < dfsChannels.length; i++) {
            if (channel == dfsChannels[i].frequency) {
                return true;
            }
        }
        return false;
    }

    private static int getBandFromChannels(ChannelSpec[] channels) {
        int band = WifiScanner.WIFI_BAND_UNSPECIFIED;
        for (ChannelSpec channel : channels) {
            if (2400 <= channel.frequency && channel.frequency < 2500) {
                band |= WifiScanner.WIFI_BAND_24_GHZ;
            } else if ( isDfs(channel.frequency)) {
                band |= WifiScanner.WIFI_BAND_5_GHZ_DFS_ONLY;
            } else if (5100 <= channel.frequency && channel.frequency < 6000) {
                band |= WifiScanner.WIFI_BAND_5_GHZ;
            }
        }
        return band;
    }

    private static int getBandFromChannels(WifiNative.ChannelSettings[] channels) {
        int band = WifiScanner.WIFI_BAND_UNSPECIFIED;
        for (WifiNative.ChannelSettings channel : channels) {
            if (channel != null) {
                if (2400 <= channel.frequency && channel.frequency < 2500) {
                    band |= WifiScanner.WIFI_BAND_24_GHZ;
                } else if ( isDfs(channel.frequency)) {
                    band |= WifiScanner.WIFI_BAND_5_GHZ_DFS_ONLY;
                } else if (5100 <= channel.frequency && channel.frequency < 6000) {
                    band |= WifiScanner.WIFI_BAND_5_GHZ;
                }
            }
        }
        return band;
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        mStateMachine.dump(fd, pw, args);
    }

    static String describe(ScanSettings scanSettings) {
        StringBuilder sb = new StringBuilder();
        sb.append("  band:").append(scanSettings.band);
        sb.append("  period:").append(scanSettings.periodInMs);
        sb.append("  reportEvents:").append(scanSettings.reportEvents);
        sb.append("  numBssidsPerScan:").append(scanSettings.numBssidsPerScan);
        sb.append("  maxScansToCache:").append(scanSettings.maxScansToCache).append("\n");

        sb.append("  channels: ");

        if (scanSettings.channels != null) {
            for (int i = 0; i < scanSettings.channels.length; i++) {
                sb.append(scanSettings.channels[i].frequency);
                sb.append(" ");
            }
        }
        sb.append("\n");
        return sb.toString();
    }

}
