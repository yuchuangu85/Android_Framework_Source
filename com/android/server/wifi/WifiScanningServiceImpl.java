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
import android.net.wifi.WifiScanner.ScanSettings;
import android.net.wifi.WifiSsid;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;
import android.util.Slog;

import com.android.internal.util.AsyncChannel;
import com.android.internal.util.Protocol;
import com.android.internal.util.StateMachine;
import com.android.internal.util.State;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class WifiScanningServiceImpl extends IWifiScanner.Stub {

    private static final String TAG = "WifiScanningService";
    private static final boolean DBG = true;
    private static final int INVALID_KEY = 0;                               // same as WifiScanner
    private static final int MIN_PERIOD_PER_CHANNEL_MS = 200;               // DFS needs 120 ms

    @Override
    public Messenger getMessenger() {
        return new Messenger(mClientHandler);
    }

    @Override
    public Bundle getAvailableChannels(int band) {
        WifiScanner.ChannelSpec channelSpecs[] = getChannelsForBand(band);
        ArrayList<Integer> list = new ArrayList<Integer>(channelSpecs.length);
        for (WifiScanner.ChannelSpec channelSpec : channelSpecs) {
            list.add(channelSpec.frequency);
        }
        Bundle b = new Bundle();
        b.putIntegerArrayList(WifiScanner.GET_AVAILABLE_CHANNELS_EXTRA, list);
        return b;
    }

    private void enforceConnectivityInternalPermission() {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.CONNECTIVITY_INTERNAL,
                "WifiScanningServiceImpl");
    }

    private class ClientHandler extends Handler {

        ClientHandler(android.os.Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {

            if (DBG) Log.d(TAG, "ClientHandler got" + msg);

            switch (msg.what) {

                case AsyncChannel.CMD_CHANNEL_HALF_CONNECTED:
                    if (msg.arg1 == AsyncChannel.STATUS_SUCCESSFUL) {
                        AsyncChannel c = (AsyncChannel) msg.obj;
                        if (DBG) Slog.d(TAG, "New client listening to asynchronous messages: " +
                                msg.replyTo);
                        ClientInfo cInfo = new ClientInfo(c, msg.replyTo);
                        mClients.put(msg.replyTo, cInfo);
                    } else {
                        Slog.e(TAG, "Client connection failure, error=" + msg.arg1);
                    }
                    return;
                case AsyncChannel.CMD_CHANNEL_DISCONNECTED:
                    if (msg.arg1 == AsyncChannel.STATUS_SEND_UNSUCCESSFUL) {
                        Slog.e(TAG, "Send failed, client connection lost");
                    } else {
                        if (DBG) Slog.d(TAG, "Client connection lost with reason: " + msg.arg1);
                    }
                    if (DBG) Slog.d(TAG, "closing client " + msg.replyTo);
                    ClientInfo ci = mClients.remove(msg.replyTo);
                    if (ci != null) {                       /* can be null if send failed above */
                        ci.cleanup();
                    }
                    return;
                case AsyncChannel.CMD_CHANNEL_FULL_CONNECTION:
                    AsyncChannel ac = new AsyncChannel();
                    ac.connect(mContext, this, msg.replyTo);
                    return;
            }

            ClientInfo ci = mClients.get(msg.replyTo);
            if (ci == null) {
                Slog.e(TAG, "Could not find client info for message " + msg.replyTo);
                replyFailed(msg, WifiScanner.REASON_INVALID_LISTENER, "Could not find listener");
                return;
            }

            try {
                enforceConnectivityInternalPermission();
            } catch (SecurityException e) {
                replyFailed(msg, WifiScanner.REASON_NOT_AUTHORIZED, "Not authorized");
                return;
            }

            int validCommands[] = {
                    WifiScanner.CMD_SCAN,
                    WifiScanner.CMD_START_BACKGROUND_SCAN,
                    WifiScanner.CMD_STOP_BACKGROUND_SCAN,
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

    private Context mContext;
    private WifiScanningStateMachine mStateMachine;
    private ClientHandler mClientHandler;

    WifiScanningServiceImpl() { }

    WifiScanningServiceImpl(Context context) {
        mContext = context;
    }

    public void startService(Context context) {
        mContext = context;

        HandlerThread thread = new HandlerThread("WifiScanningService");
        thread.start();

        mClientHandler = new ClientHandler(thread.getLooper());
        mStateMachine = new WifiScanningStateMachine(thread.getLooper());
        mWifiChangeStateMachine = new WifiChangeStateMachine(thread.getLooper());

        mContext.registerReceiver(
                new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        int state = intent.getIntExtra(
                                WifiManager.EXTRA_SCAN_AVAILABLE, WifiManager.WIFI_STATE_DISABLED);
                        if (DBG) Log.d(TAG, "SCAN_AVAILABLE : " + state);
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
            if (DBG) Log.d(TAG, "onScanResultAvailable event received");
            sendMessage(CMD_SCAN_RESULTS_AVAILABLE);
        }

        @Override
        public void onSingleScanComplete() {
            if (DBG) Log.d(TAG, "onSingleScanComplete event received");
            sendMessage(CMD_SCAN_RESULTS_AVAILABLE);
        }

        @Override
        public void onFullScanResult(ScanResult fullScanResult) {
            if (DBG) Log.d(TAG, "Full scanresult received");
            sendMessage(CMD_FULL_SCAN_RESULTS, 0, 0, fullScanResult);
        }

        @Override
        public void onScanPaused() {
            sendMessage(CMD_SCAN_PAUSED);
        }

        @Override
        public void onScanRestarted() {
            sendMessage(CMD_SCAN_RESTARTED);
        }

        @Override
        public void onHotlistApFound(ScanResult[] results) {
            if (DBG) Log.d(TAG, "HotlistApFound event received");
            sendMessage(CMD_HOTLIST_AP_FOUND, 0, 0, results);
        }

        @Override
        public void onChangesFound(ScanResult[] results) {
            if (DBG) Log.d(TAG, "onWifiChangesFound event received");
            sendMessage(CMD_WIFI_CHANGE_DETECTED, 0, 0, results);
        }

        class DefaultState extends State {
            @Override
            public void enter() {
                if (DBG) Log.d(TAG, "DefaultState");
            }
            @Override
            public boolean processMessage(Message msg) {

                if (DBG) Log.d(TAG, "DefaultState got" + msg);

                ClientInfo ci = mClients.get(msg.replyTo);

                switch (msg.what) {
                    case CMD_DRIVER_LOADED:
                        if (WifiNative.startHal() && WifiNative.getInterfaces() != 0) {
                            WifiNative.ScanCapabilities capabilities =
                                    new WifiNative.ScanCapabilities();
                            if (WifiNative.getScanCapabilities(capabilities)) {
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
                    case WifiScanner.CMD_SET_HOTLIST:
                    case WifiScanner.CMD_RESET_HOTLIST:
                    case WifiScanner.CMD_CONFIGURE_WIFI_CHANGE:
                    case WifiScanner.CMD_START_TRACKING_CHANGE:
                    case WifiScanner.CMD_STOP_TRACKING_CHANGE:
                        replyFailed(msg, WifiScanner.REASON_UNSPECIFIED, "not available");
                        break;

                    case CMD_SCAN_RESULTS_AVAILABLE:
                        if (DBG) log("ignored scan results available event");
                        break;

                    case CMD_FULL_SCAN_RESULTS:
                        if (DBG) log("ignored full scan result event");
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
                if (DBG) Log.d(TAG, "StartedState");
            }

            @Override
            public boolean processMessage(Message msg) {

                if (DBG) Log.d(TAG, "StartedState got" + msg);

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
                            replySucceeded(msg, null);
                        } else {
                            replyFailed(msg, WifiScanner.REASON_INVALID_REQUEST, "bad request");
                        }
                        break;
                    case WifiScanner.CMD_STOP_BACKGROUND_SCAN:
                        removeScanRequest(ci, msg.arg2);
                        break;
                    case WifiScanner.CMD_GET_SCAN_RESULTS:
                        replySucceeded(msg, getScanResults(ci));
                        break;
                    case WifiScanner.CMD_SET_HOTLIST:
                        setHotlist(ci, msg.arg2, (WifiScanner.HotlistSettings) msg.obj);
                        replySucceeded(msg, null);
                        break;
                    case WifiScanner.CMD_RESET_HOTLIST:
                        resetHotlist(ci, msg.arg2);
                        break;
                    case WifiScanner.CMD_START_TRACKING_CHANGE:
                        trackWifiChanges(ci, msg.arg2);
                        replySucceeded(msg, null);
                        break;
                    case WifiScanner.CMD_STOP_TRACKING_CHANGE:
                        untrackWifiChanges(ci, msg.arg2);
                        break;
                    case WifiScanner.CMD_CONFIGURE_WIFI_CHANGE:
                        configureWifiChange((WifiScanner.WifiChangeSettings) msg.obj);
                        break;
                    case CMD_SCAN_RESULTS_AVAILABLE: {
                            ScanResult[] results = WifiNative.getScanResults();
                            Collection<ClientInfo> clients = mClients.values();
                            for (ClientInfo ci2 : clients) {
                                ci2.reportScanResults(results);
                            }
                        }
                        break;
                    case CMD_FULL_SCAN_RESULTS: {
                            ScanResult result = (ScanResult) msg.obj;
                            if (DBG) Log.d(TAG, "reporting fullscan result for " + result.SSID);
                            Collection<ClientInfo> clients = mClients.values();
                            for (ClientInfo ci2 : clients) {
                                ci2.reportFullScanResult(result);
                            }
                        }
                        break;

                    case CMD_HOTLIST_AP_FOUND: {
                            ScanResult[] results = (ScanResult[])msg.obj;
                            if (DBG) Log.d(TAG, "Found " + results.length + " results");
                            Collection<ClientInfo> clients = mClients.values();
                            for (ClientInfo ci2 : clients) {
                                ci2.reportHotlistResults(results);
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

                    default:
                        return NOT_HANDLED;
                }

                return HANDLED;
            }
        }

        class PausedState extends State {
            @Override
            public void enter() {
                if (DBG) Log.d(TAG, "PausedState");
            }

            @Override
            public boolean processMessage(Message msg) {

                if (DBG) Log.d(TAG, "PausedState got" + msg);

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
            super.dump(fd, pw, args);
            pw.println("number of clients : " + mClients.size());
            pw.println();
        }

    }

    /* client management */
    HashMap<Messenger, ClientInfo> mClients = new HashMap<Messenger, ClientInfo>();

    private class ClientInfo {
        private static final int MAX_LIMIT = 16;
        private final AsyncChannel mChannel;
        private final Messenger mMessenger;

        ClientInfo(AsyncChannel c, Messenger m) {
            mChannel = c;
            mMessenger = m;
            if (DBG) Slog.d(TAG, "New client, channel: " + c + " messenger: " + m);
        }

        @Override
        public String toString() {
            StringBuffer sb = new StringBuffer();
            sb.append("mChannel ").append(mChannel).append("\n");
            sb.append("mMessenger ").append(mMessenger).append("\n");

            Iterator<Map.Entry<Integer, ScanSettings>> it = mScanSettings.entrySet().iterator();
            for (; it.hasNext(); ) {
                Map.Entry<Integer, ScanSettings> entry = it.next();
                sb.append("[ScanId ").append(entry.getKey()).append("\n");
                sb.append("ScanSettings ").append(entry.getValue()).append("\n");
                sb.append("]");
            }

            return sb.toString();
        }

        HashMap<Integer, ScanSettings> mScanSettings = new HashMap<Integer, ScanSettings>(4);
        HashMap<Integer, Integer> mScanPeriods = new HashMap<Integer, Integer>(4);

        void addScanRequest(ScanSettings settings, int id) {
            mScanSettings.put(id, settings);
        }

        void removeScanRequest(int id) {
            mScanSettings.remove(id);
        }

        Iterator<Map.Entry<Integer, WifiScanner.ScanSettings>> getScans() {
            return mScanSettings.entrySet().iterator();
        }

        Collection<ScanSettings> getScanSettings() {
            return mScanSettings.values();
        }

        void reportScanResults(ScanResult[] results) {
            Iterator<Integer> it = mScanSettings.keySet().iterator();
            while (it.hasNext()) {
                int handler = it.next();
                reportScanResults(results, handler);
            }
        }

        void reportScanResults(ScanResult[] results, int handler) {
            ScanSettings settings = mScanSettings.get(handler);
            WifiScanner.ChannelSpec desiredChannels[] = settings.channels;
            if (settings.band != WifiScanner.WIFI_BAND_UNSPECIFIED
                    || desiredChannels == null || desiredChannels.length == 0)  {
                desiredChannels = getChannelsForBand(settings.band);
            }

            // check the channels this client asked for ..
            int num_results = 0;
            for (ScanResult result : results) {
                for (WifiScanner.ChannelSpec channelSpec : desiredChannels) {
                    if (channelSpec.frequency == result.frequency) {
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
                for (WifiScanner.ChannelSpec channelSpec : desiredChannels) {
                    if (channelSpec.frequency == result.frequency) {
                        WifiSsid wifiSsid = WifiSsid.createFromAsciiEncoded(result.SSID);
                        ScanResult newResult = new ScanResult(wifiSsid, result.BSSID, "",
                                result.level, result.frequency, result.timestamp);
                        results2[index] = newResult;
                        index++;
                        break;
                    }
                }
            }

            deliverScanResults(handler, results2);
        }

        void deliverScanResults(int handler, ScanResult results[]) {
            WifiScanner.ParcelableScanResults parcelableScanResults =
                    new WifiScanner.ParcelableScanResults(results);
            mChannel.sendMessage(WifiScanner.CMD_SCAN_RESULT, 0, handler, parcelableScanResults);
        }

        void reportFullScanResult(ScanResult result) {
            Iterator<Integer> it = mScanSettings.keySet().iterator();
            while (it.hasNext()) {
                int handler = it.next();
                ScanSettings settings = mScanSettings.get(handler);
                WifiScanner.ChannelSpec desiredChannels[] = settings.channels;
                if (settings.band != WifiScanner.WIFI_BAND_UNSPECIFIED
                        || desiredChannels == null || desiredChannels.length == 0)  {
                    desiredChannels = getChannelsForBand(settings.band);
                }
                for (WifiScanner.ChannelSpec channelSpec : desiredChannels) {
                    if (channelSpec.frequency == result.frequency) {
                        WifiSsid wifiSsid = WifiSsid.createFromAsciiEncoded(result.SSID);
                        ScanResult newResult = new ScanResult(wifiSsid, result.BSSID, "",
                                result.level, result.frequency, result.timestamp);
                        if (DBG) Log.d(TAG, "sending it to " + handler);
                        newResult.informationElements = result.informationElements.clone();
                        mChannel.sendMessage(
                                WifiScanner.CMD_FULL_SCAN_RESULT, 0, handler, newResult);
                    }
                }
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

        void reportHotlistResults(ScanResult[] results) {
            Iterator<Map.Entry<Integer, WifiScanner.HotlistSettings>> it =
                    mHotlistSettings.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<Integer, WifiScanner.HotlistSettings> entry = it.next();
                int handler = entry.getKey();
                WifiScanner.HotlistSettings settings = entry.getValue();
                int num_results = 0;
                for (ScanResult result : results) {
                    for (WifiScanner.BssidInfo BssidInfo : settings.bssidInfos) {
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
                    for (WifiScanner.BssidInfo BssidInfo : settings.bssidInfos) {
                        if (result.BSSID.equalsIgnoreCase(BssidInfo.bssid)) {
                            results2[index] = result;
                            index++;
                        }
                    }
                }

                WifiScanner.ParcelableScanResults parcelableScanResults =
                        new WifiScanner.ParcelableScanResults(results2);

                mChannel.sendMessage(WifiScanner.CMD_AP_FOUND, 0, handler, parcelableScanResults);
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
            Log.d(TAG, "Successfully stopped all requests for client " + this);
        }
    }

    void replySucceeded(Message msg, Object obj) {
        if (msg.replyTo != null) {
            Message reply = Message.obtain();
            reply.what = WifiScanner.CMD_OP_SUCCEEDED;
            reply.arg2 = msg.arg2;
            reply.obj = obj;
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

    private static class SettingsComputer {

        private static class TimeBucket {
            int periodInSecond;
            int periodMinInSecond;
            int periodMaxInSecond;

            TimeBucket(int p, int min, int max) {
                periodInSecond = p;
                periodMinInSecond = min;
                periodMaxInSecond = max;
            }
        }

        private static final TimeBucket[] mTimeBuckets = new TimeBucket[] {
                new TimeBucket( 1, 0, 5 ),
                new TimeBucket( 5, 5, 10 ),
                new TimeBucket( 10, 10, 25 ),
                new TimeBucket( 30, 25, 55 ),
                new TimeBucket( 60, 55, 100),
                new TimeBucket( 300, 240, 500),
                new TimeBucket( 600, 500, 1500),
                new TimeBucket( 1800, 1500, WifiScanner.MAX_SCAN_PERIOD_MS) };

        private static final int MAX_BUCKETS = 8;
        private static final int MAX_CHANNELS = 8;
        private static final int DEFAULT_MAX_AP_PER_SCAN = 10;
        private static final int DEFAULT_REPORT_THRESHOLD = 10;
        private static final int DEFAULT_BASE_PERIOD_MS = 5000;

        private WifiNative.ScanSettings mSettings;
        {
            mSettings = new WifiNative.ScanSettings();
            mSettings.max_ap_per_scan = DEFAULT_MAX_AP_PER_SCAN;
            mSettings.base_period_ms = DEFAULT_BASE_PERIOD_MS;
            mSettings.report_threshold = DEFAULT_REPORT_THRESHOLD;

            mSettings.buckets = new WifiNative.BucketSettings[MAX_BUCKETS];
            for (int i = 0; i < mSettings.buckets.length; i++) {
                WifiNative.BucketSettings bucketSettings = new WifiNative.BucketSettings();
                bucketSettings.bucket = i;
                bucketSettings.report_events = 0;
                bucketSettings.channels = new WifiNative.ChannelSettings[MAX_CHANNELS];
                bucketSettings.num_channels = 0;
                for (int j = 0; j < bucketSettings.channels.length; j++) {
                    WifiNative.ChannelSettings channelSettings = new WifiNative.ChannelSettings();
                    bucketSettings.channels[j] = channelSettings;
                }
                mSettings.buckets[i] = bucketSettings;
            }
        }

        HashMap<Integer, Integer> mChannelToBucketMap = new HashMap<Integer, Integer>();

        private int getBestBucket(WifiScanner.ScanSettings settings) {

            // check to see if any of the channels are being scanned already
            // and find the smallest bucket index (it represents the quickest
            // period of scan)

            WifiScanner.ChannelSpec channels[] = settings.channels;
            if (channels == null) {
                // set channels based on band
                channels = getChannelsForBand(settings.band);
            }

            if (channels == null) {
                // still no channels; then there's nothing to scan
                Log.e(TAG, "No channels to scan!!");
                return -1;
            }

            int mostFrequentBucketIndex = mTimeBuckets.length;

            for (WifiScanner.ChannelSpec desiredChannelSpec : channels) {
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
                for (WifiScanner.ChannelSpec desiredChannelSpec : channels) {
                    mChannelToBucketMap.put(desiredChannelSpec.frequency, mostFrequentBucketIndex);
                }
                Log.d(TAG, "returning mf bucket number " + mostFrequentBucketIndex);
                return mostFrequentBucketIndex;
            } else if (bestBucketIndex != -1) {
                for (WifiScanner.ChannelSpec desiredChannelSpec : channels) {
                    mChannelToBucketMap.put(desiredChannelSpec.frequency, bestBucketIndex);
                }
                Log.d(TAG, "returning best bucket number " + bestBucketIndex);
                return bestBucketIndex;
            }

            Log.e(TAG, "Could not find suitable bucket for period " + settings.periodInMs);
            return -1;
        }

        void prepChannelMap(WifiScanner.ScanSettings settings) {
            getBestBucket(settings);
        }

        int addScanRequestToBucket(WifiScanner.ScanSettings settings) {

            int bucketIndex = getBestBucket(settings);
            if (bucketIndex == -1) {
                Log.e(TAG, "Ignoring invalid settings");
                return -1;
            }

            WifiScanner.ChannelSpec desiredChannels[] = settings.channels;
            if (settings.band != WifiScanner.WIFI_BAND_UNSPECIFIED
                    || desiredChannels == null
                    || desiredChannels.length == 0) {
                // set channels based on band
                desiredChannels = getChannelsForBand(settings.band);
                if (desiredChannels == null) {
                    // still no channels; then there's nothing to scan
                    Log.e(TAG, "No channels to scan!!");
                    return -1;
                }
            }

            // merge the channel lists for these buckets
            Log.d(TAG, "merging " + desiredChannels.length + " channels "
                    + " for period " + settings.periodInMs);

            WifiNative.BucketSettings bucket = mSettings.buckets[bucketIndex];
            boolean added = (bucket.num_channels == 0)
                    && (bucket.band == WifiScanner.WIFI_BAND_UNSPECIFIED);
            Log.d(TAG, "existing " + bucket.num_channels + " channels ");

            HashSet<WifiScanner.ChannelSpec> newChannels = new HashSet<WifiScanner.ChannelSpec>();
            for (WifiScanner.ChannelSpec desiredChannelSpec : desiredChannels) {

                Log.d(TAG, "desired channel " + desiredChannelSpec.frequency);

                boolean found = false;
                for (WifiNative.ChannelSettings existingChannelSpec : bucket.channels) {
                    if (desiredChannelSpec.frequency == existingChannelSpec.frequency) {
                        found = true;
                        break;
                    }
                }

                if (!found) {
                    newChannels.add(desiredChannelSpec);
                } else {
                    if (DBG) Log.d(TAG, "Already scanning channel " + desiredChannelSpec.frequency);
                }
            }

            if (settings.band != WifiScanner.WIFI_BAND_UNSPECIFIED
                    || (bucket.num_channels + newChannels.size()) > bucket.channels.length) {
                // can't accommodate all channels; switch to specifying band
                bucket.num_channels = 0;
                bucket.band = getBandFromChannels(bucket.channels)
                        | getBandFromChannels(desiredChannels);
                bucket.channels = new WifiNative.ChannelSettings[0];
                Log.d(TAG, "switching to using band " + bucket.band);
            } else {
                for (WifiScanner.ChannelSpec desiredChannelSpec : newChannels) {

                    Log.d(TAG, "adding new channel spec " + desiredChannelSpec.frequency);

                    WifiNative.ChannelSettings channelSettings = bucket.channels[bucket.num_channels];
                    channelSettings.frequency = desiredChannelSpec.frequency;
                    bucket.num_channels++;
                    mChannelToBucketMap.put(bucketIndex, channelSettings.frequency);
                }
            }

            if (bucket.report_events < settings.reportEvents) {
                if (DBG) Log.d(TAG, "setting report_events to " + settings.reportEvents);
                bucket.report_events = settings.reportEvents;
            } else {
                if (DBG) Log.d(TAG, "report_events is " + settings.reportEvents);
            }

            if (added) {
                bucket.period_ms = mTimeBuckets[bucketIndex].periodInSecond * 1000;
                mSettings.num_buckets++;
            }

            if (mSettings.max_ap_per_scan < settings.numBssidsPerScan) {
                mSettings.max_ap_per_scan = settings.numBssidsPerScan;
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
                Map.Entry<Integer, WifiScanner.ScanSettings> entry =
                        (Map.Entry<Integer,WifiScanner.ScanSettings>)it.next();
                int id = entry.getKey();
                ScanSettings s = entry.getValue();
                int newPeriodInMs = c.addScanRequestToBucket(s);
                if (newPeriodInMs  == -1) {
                    if (DBG) Log.d(TAG, "could not find a good bucket");
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
            if (DBG) Log.d(TAG, "Stopping scan because there are no buckets");
            WifiNative.stopScan();
            return true;
        } else {
            if (WifiNative.startScan(s, mStateMachine)) {
                if (DBG) Log.d(TAG, "Successfully started scan of " + s.num_buckets + " buckets at"
                        + "time = " + SystemClock.elapsedRealtimeNanos()/1000);
                return true;
            } else {
                if (DBG) Log.d(TAG, "Failed to start scan of " + s.num_buckets + " buckets");
                return false;
            }
        }
    }

    boolean addScanRequest(ClientInfo ci, int handler, ScanSettings settings) {
        // sanity check the input
        if (settings.periodInMs < WifiScanner.MIN_SCAN_PERIOD_MS) {
            Log.d(TAG, "Failing scan request because periodInMs is " + settings.periodInMs);
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
            Log.d(TAG, "Failing scan request because minSupportedPeriodMs is "
                    + minSupportedPeriodMs + " but the request wants " + settings.periodInMs);
            return false;
        }

        ci.addScanRequest(settings, handler);
        if (resetBuckets()) {
            return true;
        } else {
            ci.removeScanRequest(handler);
            Log.d(TAG, "Failing scan request because failed to reset scan");
            return false;
        }
    }

    void removeScanRequest(ClientInfo ci, int handler) {
        ci.removeScanRequest(handler);
        resetBuckets();
    }

    ScanResult[] getScanResults(ClientInfo ci) {
        ScanResult results[] = WifiNative.getScanResults();
        ci.reportScanResults(results);
        return results;
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
            WifiScanner.BssidInfo bssidInfos[] = new WifiScanner.BssidInfo[num_hotlist_ap];
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

        WifiChangeStateMachine(Looper looper) {
            super("SignificantChangeStateMachine", looper);

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
                if (DBG) Log.d(TAG, "Entering IdleState");
            }

            @Override
            public boolean processMessage(Message msg) {
                if (DBG) Log.d(TAG, "DefaultState state got " + msg);
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
                if (DBG) Log.d(TAG, "Entering StationaryState");
                reportWifiStabilized(mCurrentBssids);
            }

            @Override
            public boolean processMessage(Message msg) {
                if (DBG) Log.d(TAG, "Stationary state got " + msg);
                switch (msg.what) {
                    case WIFI_CHANGE_CMD_ENABLE :
                        // do nothing
                        break;
                    case WIFI_CHANGE_CMD_CHANGE_DETECTED:
                        if (DBG) Log.d(TAG, "Got wifi change detected");
                        reportWifiChanged((ScanResult[])msg.obj);
                        transitionTo(mMovingState);
                        break;
                    case WIFI_CHANGE_CMD_DISABLE:
                        if (DBG) Log.d(TAG, "Got Disable Wifi Change");
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
                if (DBG) Log.d(TAG, "Entering MovingState");
                issueFullScan();
            }

            @Override
            public boolean processMessage(Message msg) {
                if (DBG) Log.d(TAG, "MovingState state got " + msg);
                switch (msg.what) {
                    case WIFI_CHANGE_CMD_ENABLE :
                        // do nothing
                        break;
                    case WIFI_CHANGE_CMD_DISABLE:
                        if (DBG) Log.d(TAG, "Got Disable Wifi Change");
                        mCurrentBssids = null;
                        removeScanRequest();
                        untrackSignificantWifiChange();
                        transitionTo(mDefaultState);
                        break;
                    case WIFI_CHANGE_CMD_NEW_SCAN_RESULTS:
                        if (DBG) Log.d(TAG, "Got scan results");
                        if (mScanResultsPending) {
                            if (DBG) Log.d(TAG, "reconfiguring scan");
                            reconfigureScan((ScanResult[])msg.obj, STATIONARY_SCAN_PERIOD_MS);
                            mWifiChangeDetected = false;
                            mAlarmManager.setExact(AlarmManager.RTC_WAKEUP,
                                    System.currentTimeMillis() + MOVING_STATE_TIMEOUT_MS,
                                    mTimeoutIntent);
                            mScanResultsPending = false;
                        }
                        break;
                    case WIFI_CHANGE_CMD_CONFIGURE:
                        if (DBG) Log.d(TAG, "Got configuration from app");
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
                        if (DBG) Log.d(TAG, "Change detected");
                        mAlarmManager.cancel(mTimeoutIntent);
                        reportWifiChanged((ScanResult[])msg.obj);
                        mWifiChangeDetected = true;
                        issueFullScan();
                        break;
                    case WIFI_CHANGE_CMD_CHANGE_TIMEOUT:
                        if (DBG) Log.d(TAG, "Got timeout event");
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
                if (DBG) Log.d(TAG, "Issuing full scan");
                WifiScanner.ScanSettings settings = new WifiScanner.ScanSettings();
                settings.band = WifiScanner.WIFI_BAND_BOTH;
                settings.periodInMs = MOVING_SCAN_PERIOD_MS;
                settings.reportEvents = WifiScanner.REPORT_EVENT_AFTER_EACH_SCAN;
                addScanRequest(settings);
                mScanResultsPending = true;
            }

        }

        void reconfigureScan(ScanResult[] results, int period) {
            // find brightest APs and set them as sentinels
            if (results.length < MAX_APS_TO_TRACK) {
                Log.d(TAG, "too few APs (" + results.length + ") available to track wifi change");
                return;
            }

            removeScanRequest();

            // remove duplicate BSSIDs
            HashMap<String, ScanResult> bssidToScanResult = new HashMap<String, ScanResult>();
            for (ScanResult result : results) {
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

            if (DBG) Log.d(TAG, "Found " + channels.size() + " channels");

            // set scanning schedule
            WifiScanner.ScanSettings settings = new WifiScanner.ScanSettings();
            settings.band = WifiScanner.WIFI_BAND_UNSPECIFIED;
            settings.channels = new WifiScanner.ChannelSpec[channels.size()];
            for (int i = 0; i < channels.size(); i++) {
                settings.channels[i] = new WifiScanner.ChannelSpec(channels.get(i));
            }

            settings.periodInMs = period;
            addScanRequest(settings);

            WifiScanner.WifiChangeSettings settings2 = new WifiScanner.WifiChangeSettings();
            settings2.rssiSampleSize = 3;
            settings2.lostApSampleSize = 3;
            settings2.unchangedSampleSize = 3;
            settings2.minApsBreachingThreshold = 2;
            settings2.bssidInfos = new WifiScanner.BssidInfo[brightest.length];

            for (int i = 0; i < brightest.length; i++) {
                WifiScanner.BssidInfo BssidInfo = new WifiScanner.BssidInfo();
                BssidInfo.bssid = brightest[i].BSSID;
                int threshold = (100 + brightest[i].level) / 32 + 2;
                BssidInfo.low = brightest[i].level - threshold;
                BssidInfo.high = brightest[i].level + threshold;
                settings2.bssidInfos[i] = BssidInfo;

                if (DBG) Log.d(TAG, "Setting bssid=" + BssidInfo.bssid + ", " +
                        "low=" + BssidInfo.low + ", high=" + BssidInfo.high);
            }

            trackSignificantWifiChange(settings2);
            mCurrentBssids = brightest;
        }

        void reconfigureScan(WifiScanner.WifiChangeSettings settings) {

            if (settings.bssidInfos.length < MAX_APS_TO_TRACK) {
                Log.d(TAG, "too few APs (" + settings.bssidInfos.length
                        + ") available to track wifi change");
                return;
            }

            if (DBG) Log.d(TAG, "Setting configuration specified by app");

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
            WifiScanner.ScanSettings settings2 = new WifiScanner.ScanSettings();
            settings2.band = WifiScanner.WIFI_BAND_UNSPECIFIED;
            settings2.channels = new WifiScanner.ChannelSpec[channels.size()];
            int i = 0;
            for (Integer channel : channels) {
                settings2.channels[i++] = new WifiScanner.ChannelSpec(channel);
            }

            settings2.periodInMs = settings.periodInMs;
            addScanRequest(settings2);

            // start tracking new APs
            trackSignificantWifiChange(settings);
        }

        class ClientInfoLocal extends ClientInfo {
            ClientInfoLocal() {
                super(null, null);
            }
            @Override
            void deliverScanResults(int handler, ScanResult results[]) {
                if (DBG) Log.d(TAG, "Delivering messages directly");
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

        ClientInfo mClientInfo = new ClientInfoLocal();
        private static final int SCAN_COMMAND_ID = 1;

        void addScanRequest(WifiScanner.ScanSettings settings) {
            if (DBG) Log.d(TAG, "Starting scans");
            Message msg = Message.obtain();
            msg.what = WifiScanner.CMD_START_BACKGROUND_SCAN;
            msg.arg2 = SCAN_COMMAND_ID;
            msg.obj = settings;
            mClientHandler.sendMessage(msg);
        }

        void removeScanRequest() {
            if (DBG) Log.d(TAG, "Stopping scans");
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

    private static WifiScanner.ChannelSpec[] getChannelsForBand(int band) {
        int channels[] = WifiNative.getChannelsForBand(band);
        if (channels != null) {
            WifiScanner.ChannelSpec channelSpecs[] = new WifiScanner.ChannelSpec[channels.length];
            for (int i = 0; i < channels.length; i++) {
                channelSpecs[i] = new WifiScanner.ChannelSpec(channels[i]);
            }
            return channelSpecs;
        } else {
            return new WifiScanner.ChannelSpec[0];
        }
    }

    private static int getBandFromChannels(WifiScanner.ChannelSpec[] channels) {
        int band = WifiScanner.WIFI_BAND_UNSPECIFIED;
        for (WifiScanner.ChannelSpec channel : channels) {
            if (2400 <= channel.frequency && channel.frequency < 2500) {
                band |= WifiScanner.WIFI_BAND_24_GHZ;
            } else if (5100 <= channel.frequency && channel.frequency < 6000) {
                band |= WifiScanner.WIFI_BAND_5_GHZ;
            } else {
                /* TODO: Add DFS Range */
            }
        }
        return band;
    }
    private static int getBandFromChannels(WifiNative.ChannelSettings[] channels) {
        int band = WifiScanner.WIFI_BAND_UNSPECIFIED;
        for (WifiNative.ChannelSettings channel : channels) {
            if (2400 <= channel.frequency && channel.frequency < 2500) {
                band |= WifiScanner.WIFI_BAND_24_GHZ;
            } else if (5100 <= channel.frequency && channel.frequency < 6000) {
                band |= WifiScanner.WIFI_BAND_5_GHZ;
            } else {
                /* TODO: Add DFS Range */
            }
        }
        return band;
    }

}
